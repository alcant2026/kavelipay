//! Divisão de despesa: uma pessoa paga, N participants devem partes.
//!
//! Duas garantias:
//!  1. A soma das partes é o total — verificado por constraint trigger no banco.
//!  2. Quitar uma parte devolve dinheiro ao dono da wallet via `Settlement`,
//!     que é a inversão contábil de `BillPaid`.
//!
//! O que NÃO existe aqui: cobrança, PIX, transferência real. Uma parte
//! quitada é o registro de que o usuário informou que o dinheiro voltou.

use axum::Json;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::{AppError, AppResult};
use crate::ledger::{self, TransactionType};
use crate::money::Money;
use crate::state::AppState;

#[derive(Deserialize)]
pub struct NewSplit {
    pub description: String,
    /// Total pago pelo dono da wallet. Precisa bater com a soma das partes.
    pub total_amount: Money,
    pub parts: Vec<NewPart>,
    /// Classificação da despesa (opcional).
    pub category_id: Option<Uuid>,
}

#[derive(Deserialize)]
pub struct NewPart {
    pub name: String,
    /// Valor que ESTE participante deve. A soma tem de fechar com o total.
    pub amount: Money,
}

#[derive(Serialize)]
pub struct SplitPartResponse {
    pub id: Uuid,
    pub name: String,
    pub owed_amount: Money,
    pub is_settled: bool,
    pub settled_at: Option<chrono::DateTime<chrono::Utc>>,
}

#[derive(Serialize)]
pub struct SplitResponse {
    pub id: Uuid,
    pub description: String,
    pub total_amount: Money,
    pub currency: String,
    pub parts: Vec<SplitPartResponse>,
    /// Soma das partes não quitadas — o que ainda precisa voltar.
    pub pending_amount: Money,
    pub balance: Money,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

/// POST /splits
///
/// Cria a divisão e debita o total da carteira numa transação só. As partes
/// entram na mesma transação SQL porque o trigger que valida a soma roda no
/// COMMIT — separá-las deixaria um estado intermediário inconsistente.
pub async fn create_split(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Json(req): Json<NewSplit>,
) -> AppResult<(StatusCode, Json<SplitResponse>)> {
    let description = req.description.trim();
    if description.is_empty() {
        return Err(AppError::Validation("descrição é obrigatória".into()));
    }
    if !req.total_amount.is_positive() {
        return Err(AppError::Validation("total deve ser maior que zero".into()));
    }
    if req.parts.is_empty() {
        return Err(AppError::Validation(
            "divisão precisa de ao menos um participante".into(),
        ));
    }
    if req.parts.len() > 100 {
        return Err(AppError::Validation("máximo de 100 participantes".into()));
    }

    // Validação no Rust ANTES de tocar o banco: o trigger daria o mesmo erro,
    // mas como 500 de check_violation, não como 422 com mensagem útil.
    let mut soma = Decimal::ZERO;
    for p in &req.parts {
        if p.name.trim().is_empty() {
            return Err(AppError::Validation("participante sem nome".into()));
        }
        if !p.amount.is_positive() {
            return Err(AppError::Validation(
                "cada parte deve ser maior que zero".into(),
            ));
        }
        soma += p.amount.amount();
    }

    if soma != req.total_amount.amount() {
        return Err(AppError::Validation(format!(
            "a soma das partes ({soma}) não bate com o total ({})",
            req.total_amount.amount()
        )));
    }

    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    let mut tx = state.db.begin().await?;

    // O dono paga a despesa inteira agora; as partes são a quem cabe cada
    // fatia do reembolso. `postar_em` roda dentro desta transação para que
    // a despesa e as partes entrem no MESMO commit — se as partes não
    // fecharem, o trigger recusa o commit e a despesa não fica órfã.
    let transaction_id = ledger::postar_em_com_categoria(
        &mut tx,
        wallet,
        TransactionType::Expense,
        req.total_amount,
        Some(description),
        Some(serde_json::json!({ "split": true })),
        req.category_id,
        Some(auth.user_id()),
    )
    .await?;

    let split_id = sqlx::query_scalar::<_, Uuid>(
        "INSERT INTO expense_splits
             (wallet_id, description, total_amount, ledger_transaction_id)
         SELECT w.id, $2, $3, $4 FROM wallets w WHERE w.id = $1
         RETURNING id",
    )
    .bind(wallet)
    .bind(description)
    .bind(req.total_amount.amount())
    .bind(transaction_id)
    .fetch_one(&mut *tx)
    .await?;

    for p in &req.parts {
        sqlx::query(
            "INSERT INTO expense_split_parts (split_id, participant_name, owed_amount)
             VALUES ($1, $2, $3)",
        )
        .bind(split_id)
        .bind(p.name.trim())
        .bind(p.amount.amount())
        .execute(&mut *tx)
        .await?;
    }

    // O COMMIT dispara os triggers deferrados: se algo não fechar, a
    // transação inteira volta e nada é gravado.
    tx.commit().await?;

    buscar(&state, split_id, wallet).await.map(|s| (StatusCode::CREATED, Json(s)))
}

/// POST /splits/{id}/parts/{part_id}/settle
///
/// Registra que a parte foi quitada e devolve o dinheiro ao dono da wallet.
/// Idempotente: a parte já quitada é 409 e não move dinheiro duas vezes.
pub async fn settle_part(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path((split_id, part_id)): Path<(Uuid, Uuid)>,
) -> AppResult<Json<SplitResponse>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    // Filtra por split_id E wallet_id: um part_id de outra divisão (ou de
    // outro usuário) simplesmente não existe para quem pergunta.
    let parte = sqlx::query_as::<_, (Decimal, String, bool, Option<chrono::DateTime<chrono::Utc>>)>(
        "SELECT p.owed_amount, p.participant_name, p.settled_at IS NOT NULL, p.settled_at
           FROM expense_split_parts p
           JOIN expense_splits s ON s.id = p.split_id
          WHERE p.id = $1 AND p.split_id = $2 AND s.wallet_id = $3",
    )
    .bind(part_id)
    .bind(split_id)
    .bind(wallet)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NotFound("participante"))?;

    if parte.2 {
        return Err(AppError::Conflict("parte já quitada".into()));
    }

    let valor = Money::new(parte.0);
    let nome = parte.1.clone();

    // Transação primeiro: se o ledger recusar, a parte continua pendente.
    let transaction_id = ledger::postar(
        &state.db,
        wallet,
        TransactionType::Settlement,
        valor,
        Some(&format!("Quitação de {nome}")),
        Some(serde_json::json!({ "split_id": split_id, "part_id": part_id })),
    )
    .await?;

    let rows = sqlx::query(
        "UPDATE expense_split_parts
            SET settled_at = now(), settled_transaction_id = $3
          WHERE id = $1 AND split_id = $2 AND settled_at IS NULL",
    )
    .bind(part_id)
    .bind(split_id)
    .bind(transaction_id)
    .execute(&state.db)
    .await?;

    if rows.rows_affected() == 0 {
        tracing::warn!(%split_id, %part_id, "quitação concorrente; segunda tentativa recusada");
        return Err(AppError::Conflict("parte já quitada".into()));
    }

    buscar(&state, split_id, wallet).await.map(Json)
}

/// GET /splits
pub async fn list_splits(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
) -> AppResult<Json<Vec<SplitResponse>>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    let splits = sqlx::query_as::<_, (Uuid, String, Decimal, String, chrono::DateTime<chrono::Utc>)>(
        "SELECT id, description, total_amount, currency, created_at
           FROM expense_splits WHERE wallet_id = $1
          ORDER BY created_at DESC",
    )
    .bind(wallet)
    .fetch_all(&state.db)
    .await?;

    let (saldo, _) = ledger::saldo(&state.db, wallet).await?;
    let mut out = Vec::with_capacity(splits.len());

    for (id, desc, total, currency, created_at) in splits {
        let parts = sqlx::query_as::<_, (Uuid, String, Decimal, bool, Option<chrono::DateTime<chrono::Utc>>)>(
            "SELECT id, participant_name, owed_amount, settled_at IS NOT NULL, settled_at
               FROM expense_split_parts WHERE split_id = $1 ORDER BY created_at",
        )
        .bind(id)
        .fetch_all(&state.db)
        .await?;

        let pendente: Decimal = parts
            .iter()
            .filter(|p| !p.3)
            .map(|p| p.2)
            .sum();

        out.push(SplitResponse {
            id,
            description: desc,
            total_amount: Money::new(total),
            currency,
            parts: parts
                .into_iter()
                .map(|(pid, name, owed, settled, at)| SplitPartResponse {
                    id: pid,
                    name,
                    owed_amount: Money::new(owed),
                    is_settled: settled,
                    settled_at: at,
                })
                .collect(),
            pending_amount: Money::new(pendente),
            balance: Money::new(saldo),
            created_at,
        });
    }

    Ok(Json(out))
}

// ── helpers ──────────────────────────────────────────────────────────────────

async fn wallet_ativa(state: &AppState, user_id: Uuid) -> AppResult<Uuid> {
    let row = sqlx::query_as::<_, (Uuid, String)>("SELECT id, status FROM wallets WHERE user_id = $1")
        .bind(user_id)
        .fetch_optional(&state.db)
        .await?
        .ok_or(AppError::NotFound("wallet"))?;

    if row.1 != "ACTIVE" {
        return Err(AppError::Conflict(format!(
            "carteira {}: não aceita operações",
            row.1
        )));
    }
    Ok(row.0)
}

async fn buscar(state: &AppState, id: Uuid, wallet: Uuid) -> AppResult<SplitResponse> {
    let row = sqlx::query_as::<_, (String, Decimal, String, chrono::DateTime<chrono::Utc>)>(
        "SELECT description, total_amount, currency, created_at
           FROM expense_splits WHERE id = $1 AND wallet_id = $2",
    )
    .bind(id)
    .bind(wallet)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NotFound("divisão"))?;

    let parts = sqlx::query_as::<_, (Uuid, String, Decimal, bool, Option<chrono::DateTime<chrono::Utc>>)>(
        "SELECT id, participant_name, owed_amount, settled_at IS NOT NULL, settled_at
           FROM expense_split_parts WHERE split_id = $1 ORDER BY created_at",
    )
    .bind(id)
    .fetch_all(&state.db)
    .await?;

    let pendente: Decimal = parts.iter().filter(|p| !p.3).map(|p| p.2).sum();
    let (saldo, _) = ledger::saldo(&state.db, wallet).await?;

    Ok(SplitResponse {
        id,
        description: row.0,
        total_amount: Money::new(row.1),
        currency: row.2,
        parts: parts
            .into_iter()
            .map(|(pid, name, owed, settled, at)| SplitPartResponse {
                id: pid,
                name,
                owed_amount: Money::new(owed),
                is_settled: settled,
                settled_at: at,
            })
            .collect(),
        pending_amount: Money::new(pendente),
        balance: Money::new(saldo),
        created_at: row.3,
    })
}