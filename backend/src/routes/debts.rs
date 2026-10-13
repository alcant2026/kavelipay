//! Dívidas: o usuário registra a quem deve ("eu devo") — o lado inverso de
//! `splits`, onde os outros devem a ele.
//!
//! Três estados (`PENDING → PAID → CONFIRMED`) e duas garantias:
//!  1. Criar NÃO mexe no saldo: dívida pendente é só registro, como cobrança
//!     (pedir não é receber; dever não é pagar). O dinheiro sai apenas com
//!     o POST explícito do dono declarando o pagamento.
//!  2. Ninguém vê dívida alheia: toda query filtra pela carteira ativa do
//!     dono; id de outra carteira não existe (404, nunca 403).
//!
//! O que NÃO existe aqui: convite/vínculo com o credor (single-user, o
//! credor pode nem ter conta), vencimento, pagamento parcial, categoria.

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
pub struct NewDebt {
    pub creditor_name: String,
    pub amount: Money,
    pub description: String,
}

#[derive(Serialize)]
pub struct DebtResponse {
    pub id: Uuid,
    pub creditor_name: String,
    pub description: String,
    pub amount: Money,
    /// PENDING | PAID | CONFIRMED
    pub status: String,
    pub paid_at: Option<chrono::DateTime<chrono::Utc>>,
    pub confirmed_at: Option<chrono::DateTime<chrono::Utc>>,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

/// POST /debts
///
/// Cria a dívida. **Nenhuma transação de ledger**: registrar que devo não
/// move dinheiro — o saldo só cai quando o dono declara o pagamento. É a
/// simetria de `POST /charges`: pedir não é receber, dever não é pagar.
pub async fn create_debt(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Json(req): Json<NewDebt>,
) -> AppResult<(StatusCode, Json<DebtResponse>)> {
    // Validação no Rust ANTES de tocar o banco: o CHECK do banco recusaria o
    // mesmo, mas como 500 de check_violation, não como 422 com mensagem útil.
    let creditor = req.creditor_name.trim();
    if creditor.is_empty() {
        return Err(AppError::Validation("credor é obrigatório".into()));
    }
    let description = req.description.trim();
    if description.is_empty() {
        return Err(AppError::Validation("descrição é obrigatória".into()));
    }
    if !req.amount.is_positive() {
        return Err(AppError::Validation("valor deve ser maior que zero".into()));
    }

    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    let id = sqlx::query_scalar::<_, Uuid>(
        "INSERT INTO debts (wallet_id, creditor_name, description, amount)
         SELECT w.id, $2, $3, $4 FROM wallets w WHERE w.id = $1
         RETURNING id",
    )
    .bind(wallet)
    .bind(creditor)
    .bind(description)
    .bind(req.amount.amount())
    .fetch_one(&state.db)
    .await?;

    buscar(&state, id, wallet)
        .await
        .map(|d| (StatusCode::CREATED, Json(d)))
}

/// GET /debts — da carteira ativa, mais recentes primeiro.
pub async fn list_debts(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
) -> AppResult<Json<Vec<DebtResponse>>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    let rows = sqlx::query_as::<_, DebtRow>(
        "SELECT id, creditor_name, description, amount, status,
                paid_at, confirmed_at, created_at
           FROM debts WHERE wallet_id = $1
          ORDER BY created_at DESC",
    )
    .bind(wallet)
    .fetch_all(&state.db)
    .await?;

    Ok(Json(rows.into_iter().map(convert).collect()))
}

/// POST /debts/{id}/pay
///
/// Declara o pagamento da dívida. O dinheiro sai do ledger AGORA, dentro da
/// mesma transação SQL que marca a dívida como PAID: uma sem a outra seria
/// dinheiro movido sem registro, ou registro sem dinheiro.
///
/// Idempotente por construção (como `bills/{id}/pay`): a linha é travada com
/// `FOR UPDATE` e o UPDATE é condicional a `status = 'PENDING'`. Pagar duas
/// vezes devolve 409 sem debitar a carteira de novo — a segunda tentativa nem
/// chega a postar no ledger.
pub async fn pay_debt(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
) -> AppResult<Json<DebtResponse>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    let mut tx = state.db.begin().await?;

    // Filtra por id E wallet_id: dívida de outra carteira não existe (404).
    // FOR UPDATE serializa dois pagamentos concorrentes — o segundo espera o
    // COMMIT do primeiro, enxerga PAID e é recusado antes de postar.
    let pendente = sqlx::query_as::<_, (String, String, Decimal)>(
        "SELECT status, creditor_name, amount
           FROM debts
          WHERE id = $1 AND wallet_id = $2
          FOR UPDATE",
    )
    .bind(id)
    .bind(wallet)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or(AppError::NotFound("dívida"))?;

    let (status, creditor_name, amount) = pendente;
    if status != "PENDING" {
        return Err(AppError::Conflict("só dívidas pendentes podem ser pagas".into()));
    }

    // Ledger ANTES do status (mesma ordem de bills/{id}/pay): se o ledger
    // recusar, a dívida continua PENDING e nada mudou.
    let transaction_id = ledger::postar_em_com_categoria(
        &mut tx,
        wallet,
        TransactionType::Expense,
        Money::new(amount),
        Some(&format!("Dívida: {}", creditor_name)),
        Some(serde_json::json!({ "debt_id": id })),
        None,
        Some(auth.user_id()),
    )
    .await?;

    let rows = sqlx::query(
        "UPDATE debts
            SET status = 'PAID',
                paid_at = now(),
                paid_transaction_id = $3
          WHERE id = $1 AND wallet_id = $2 AND status = 'PENDING'",
    )
    .bind(id)
    .bind(wallet)
    .bind(transaction_id)
    .execute(&mut *tx)
    .await?;

    if rows.rows_affected() == 0 {
        // Corrida defensiva: com o FOR UPDATE acima não deveria acontecer,
        // mas se outro request pagou entre o SELECT e o UPDATE, desfaz tudo —
        // o ledger não pode ficar com débito órfão de uma dívida não marcada.
        tracing::warn!(debt_id = %id, "pagamento concorrente; transação desfeita");
        tx.rollback().await?;
        return Err(AppError::Conflict("só dívidas pendentes podem ser pagas".into()));
    }

    tx.commit().await?;

    buscar(&state, id, wallet).await.map(Json)
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

async fn buscar(state: &AppState, id: Uuid, wallet: Uuid) -> AppResult<DebtResponse> {
    // Filtra por id E wallet_id: dívida de outra carteira (ou de outro
    // usuário) simplesmente não existe para quem pergunta — 404, nunca 403.
    let row = sqlx::query_as::<_, DebtRow>(
        "SELECT id, creditor_name, description, amount, status,
                paid_at, confirmed_at, created_at
           FROM debts WHERE id = $1 AND wallet_id = $2",
    )
    .bind(id)
    .bind(wallet)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NotFound("dívida"))?;

    Ok(convert(row))
}

type DebtRow = (
    Uuid,
    String,
    String,
    Decimal,
    String,
    Option<chrono::DateTime<chrono::Utc>>,
    Option<chrono::DateTime<chrono::Utc>>,
    chrono::DateTime<chrono::Utc>,
);

fn convert(r: DebtRow) -> DebtResponse {
    DebtResponse {
        id: r.0,
        creditor_name: r.1,
        description: r.2,
        amount: Money::new(r.3),
        status: r.4,
        paid_at: r.5,
        confirmed_at: r.6,
        created_at: r.7,
    }
}
