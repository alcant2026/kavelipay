//! Divisão de despesa: uma pessoa paga, N participants devem partes.
//!
//! Duas garantias:
//!  1. A soma das partes é o total — verificado por constraint trigger no banco.
//!  2. Quitar uma parte devolve dinheiro ao dono da wallet via `Settlement`,
//!     que é a inversão contábil de `BillPaid`.
//!
//! Cada parte em aberto ganha um link público (`/cobranca/parte/{token}/pagar`)
//! que o participante abre sem conta: ele paga no Checkout Pro e quem confirma
//! é o webhook do Mercado Pago, não a palavra do dono. O clique manual em
//! `settle_part` continua existindo para quem pagou por fora (Pix direto).

use axum::Json;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{Html, Redirect};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::{AppError, AppResult};
use crate::ledger::{self, TransactionType};
use crate::money::Money;
use crate::routes::charges;
use crate::state::wallet_ativa;
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
    /// Link https para o participante pagar sem ter o app. `None` quando já
    /// quitada — link aberto de parte paga só mostra "pagamento confirmado".
    pub link: Option<String>,
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

    let mut tx = state.db.begin().await?;

    // Filtra por split_id E wallet_id: um part_id de outra divisão (ou de
    // outro usuário) simplesmente não existe para quem pergunta. FOR UPDATE
    // serializa duas quitações concorrentes.
    let parte = sqlx::query_as::<_, (Decimal, String, bool)>(
        "SELECT p.owed_amount, p.participant_name, p.settled_at IS NOT NULL
           FROM expense_split_parts p
           JOIN expense_splits s ON s.id = p.split_id
          WHERE p.id = $1 AND p.split_id = $2 AND s.wallet_id = $3
          FOR UPDATE OF p",
    )
    .bind(part_id)
    .bind(split_id)
    .bind(wallet)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or(AppError::NotFound("participante"))?;

    if parte.2 {
        return Err(AppError::Conflict("parte já quitada".into()));
    }

    // Transação primeiro: se o ledger recusar, a parte continua pendente —
    // e qualquer falha depois daqui desfaz os dois lados juntos.
    if let Err(e) = quitar_parte(&mut tx, wallet, split_id, part_id, &parte.1, parte.0, auth.user_id(), None).await
    {
        tx.rollback().await?;
        return Err(e);
    }

    tx.commit().await?;

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
        let parts = sqlx::query_as::<
            _,
            (
                Uuid,
                String,
                Decimal,
                bool,
                Option<chrono::DateTime<chrono::Utc>>,
                String,
            ),
        >(
            "SELECT id, participant_name, owed_amount, settled_at IS NOT NULL, settled_at, share_token
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
                .map(|(pid, name, owed, settled, at, token)| SplitPartResponse {
                    id: pid,
                    name,
                    owed_amount: Money::new(owed),
                    is_settled: settled,
                    settled_at: at,
                    link: link_da_parte(&state, settled, &token),
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

    let parts = sqlx::query_as::<
        _,
        (
            Uuid,
            String,
            Decimal,
            bool,
            Option<chrono::DateTime<chrono::Utc>>,
            String,
        ),
    >(
        "SELECT id, participant_name, owed_amount, settled_at IS NOT NULL, settled_at, share_token
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
            .map(|(pid, name, owed, settled, at, token)| SplitPartResponse {
                id: pid,
                name,
                owed_amount: Money::new(owed),
                is_settled: settled,
                settled_at: at,
                link: link_da_parte(&state, settled, &token),
            })
            .collect(),
        pending_amount: Money::new(pendente),
        balance: Money::new(saldo),
        created_at: row.3,
    })
}
/// Link público da parte — é o que o dono manda no WhatsApp. `None` quando já
/// está quitada: link de coisa paga só gera cobrança repetida.
fn link_da_parte(state: &AppState, quitada: bool, token: &str) -> Option<String> {
    if quitada {
        None
    } else {
        Some(format!("{}/cobranca/parte/{token}/pagar", state.public_base_url))
    }
}

/// Quita uma parte dentro da transação de quem chamou (o dono clicando, ou o
/// webhook do Mercado Pago confirmando). Ledger primeiro: se ele recusar, a
/// parte continua pendente e nada fica pela metade.
pub(crate) async fn quitar_parte(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    wallet: Uuid,
    split_id: Uuid,
    part_id: Uuid,
    nome: &str,
    valor: Decimal,
    user_id: Uuid,
    mp_payment_id: Option<&str>,
) -> AppResult<()> {
    let transaction_id = ledger::postar_em_com_categoria(
        tx,
        wallet,
        TransactionType::Settlement,
        Money::new(valor),
        Some(&format!("Quitação de {nome}")),
        Some(serde_json::json!({ "split_id": split_id, "part_id": part_id })),
        None,
        Some(user_id),
    )
    .await?;

    let rows = sqlx::query(
        "UPDATE expense_split_parts
            SET settled_at = now(), settled_transaction_id = $3, mp_payment_id = $4
          WHERE id = $1 AND split_id = $2 AND settled_at IS NULL",
    )
    .bind(part_id)
    .bind(split_id)
    .bind(transaction_id)
    .bind(mp_payment_id)
    .execute(&mut **tx)
    .await?;

    if rows.rows_affected() == 0 {
        // Corrida defensiva: desfaz TUDO — o ledger não pode ficar com crédito
        // órfão de uma parte não quitada.
        tracing::warn!(%split_id, %part_id, "quitação concorrente; transação desfeita");
        return Err(AppError::Conflict("parte já quitada".into()));
    }

    Ok(())
}

struct PartePublica {
    id: Uuid,
    name: String,
    owed_amount: Decimal,
    settled: bool,
    description: String,
    cobrador: Option<String>,
}

async fn por_token_parte(state: &AppState, token: &str) -> AppResult<PartePublica> {
    let token = token.trim().to_ascii_lowercase();
    if token.len() != 32 || !token.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(AppError::NotFound("divisão"));
    }
    let row = sqlx::query_as::<_, (Uuid, Uuid, String, Decimal, bool, String, Option<String>)>(
        "SELECT p.id, p.split_id, p.participant_name, p.owed_amount, p.settled_at IS NOT NULL,
                s.description, u.display_name
           FROM expense_split_parts p
           JOIN expense_splits s ON s.id = p.split_id
           JOIN wallets w ON w.id = s.wallet_id
           JOIN users u ON u.id = w.user_id
          WHERE p.share_token = $1",
    )
    .bind(&token)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NotFound("divisão"))?;

    Ok(PartePublica {
        id: row.0,
        name: row.2,
        owed_amount: row.3,
        settled: row.4,
        description: row.5,
        cobrador: row.6.filter(|n| !n.trim().is_empty()),
    })
}

/// GET /cobranca/parte/{token}/pagar — a cota de uma divisão, pública.
pub async fn pagina_parte(
    State(state): State<AppState>,
    Path(token): Path<String>,
) -> AppResult<Html<String>> {
    let p = por_token_parte(&state, &token).await?;

    let acao = if p.settled {
        "<p class=\"aviso ok\">Pagamento confirmado.</p>"
    } else {
        "<form method=\"post\"><button type=\"submit\">Pagar com Mercado Pago</button></form>
         <p class=\"pe\">Pix ou cartão, na página do Mercado Pago.</p>"
    };

    Ok(charges::pagina(
        "DIVISÃO",
        p.owed_amount,
        &format!("{} · {}", p.name, p.description),
        p.cobrador.as_deref(),
        "",
        acao,
    ))
}

/// POST /cobranca/parte/{token}/pagar — cria (ou reaproveita) a preferência e
/// manda o participante para o Checkout Pro.
pub async fn pagar_pela_parte(
    State(state): State<AppState>,
    Path(token): Path<String>,
) -> AppResult<Redirect> {
    let p = por_token_parte(&state, &token).await?;
    if p.settled {
        return Ok(Redirect::to(&format!("/cobranca/parte/{token}/pagar")));
    }

    let existente = |id: String| Ok(Redirect::to(&charges::init_point(&id)));
    let salva = sqlx::query_scalar::<_, Option<String>>(
        "SELECT mp_preference_id FROM expense_split_parts WHERE id = $1",
    )
    .bind(p.id)
    .fetch_one(&state.db)
    .await?;
    if let Some(id) = salva {
        return existente(id);
    }

    let preferencia = state
        .mp
        .criar_preferencia(
            &format!("parte:{}", p.id),
            &format!("{} · {}", p.name, p.description),
            p.owed_amount,
            "BRL",
            &format!("{}/cobranca/parte/{token}/pagar", state.public_base_url),
        )
        .await?;

    // Só grava se ainda não houver preferência: dois POSTs simultâneos não
    // podem deixar duas preferências pagáveis para a mesma cota (o pagador
    // poderia pagar duas vezes e o segundo webhook não teria o que quitar).
    let gravada = sqlx::query(
        "UPDATE expense_split_parts SET mp_preference_id = $2
          WHERE id = $1 AND mp_preference_id IS NULL",
    )
    .bind(p.id)
    .bind(&preferencia.id)
    .execute(&state.db)
    .await?;
    if gravada.rows_affected() == 0 {
        let atual = sqlx::query_scalar::<_, String>(
            "SELECT mp_preference_id FROM expense_split_parts WHERE id = $1",
        )
        .bind(p.id)
        .fetch_one(&state.db)
        .await?;
        return existente(atual);
    }

    Ok(Redirect::to(&preferencia.init_point))
}
