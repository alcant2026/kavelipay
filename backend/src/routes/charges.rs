//! Cobranças: pedir dinheiro a outra pessoa, gerar QR Code, receber.
//!
//! O KaveliPay NÃO é provedor de PIX nem processador. O QR gerado aqui é um
//! deep link do próprio app (`kavelipay://cobranca/<id>`), não um BR Code/EMV —
//! ver `payment::DemoPaymentProvider`.
//!
//! Uma cobrança tem duas pernas contábeis e elas NÃO podem ser confundidas:
//! cobrar registra um direito a receber (a carteira não muda); receber move o
//! dinheiro de fato. Uma rota que fizesse as duas de uma vez apareceria como
//! "ganhei R$ 50" ao simplesmente pedir.

use axum::Json;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::{AppError, AppResult};
use crate::ledger::{self, TransactionType};
use crate::money::Money;
use crate::payment::{DemoPaymentProvider, PaymentPayload, PaymentProvider};
use crate::state::AppState;

#[derive(Deserialize)]
pub struct NewCharge {
    pub description: String,
    pub amount: Money,
    pub debtor_name: Option<String>,
    pub due_date: Option<String>,
}

#[derive(Serialize)]
pub struct ChargeResponse {
    pub id: Uuid,
    pub description: String,
    pub amount: Money,
    pub currency: String,
    pub debtor_name: Option<String>,
    pub due_date: Option<chrono::NaiveDate>,
    /// PENDING | RECEIVED | CANCELED
    pub status: String,
    pub received_at: Option<chrono::DateTime<chrono::Utc>>,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Serialize)]
pub struct ChargeWithQr {
    #[serde(flatten)]
    pub charge: ChargeResponse,
    pub qr: PaymentPayload,
}

#[derive(Deserialize)]
pub struct ChargeQuery {
    pub status: Option<String>,
    pub limit: Option<i64>,
    pub offset: Option<i64>,
}

/// POST /charges
///
/// Cria a cobrança e devolve o QR. **Não gera lançamento no ledger**: pedir
/// dinheiro não é receber dinheiro. O valor a receber é o somatório das
/// cobranças PENDING; o primeiro e único toque na carteira é em
/// `POST /charges/{id}/receive`.
pub async fn create_charge(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Json(req): Json<NewCharge>,
) -> AppResult<(StatusCode, Json<ChargeWithQr>)> {
    let description = req.description.trim();
    if description.is_empty() {
        return Err(AppError::Validation("descrição é obrigatória".into()));
    }
    if !req.amount.is_positive() {
        return Err(AppError::Validation("valor deve ser maior que zero".into()));
    }

    let due_date = req
        .due_date
        .as_deref()
        .map(str::trim)
        .map(|d| {
            chrono::NaiveDate::parse_from_str(d, "%Y-%m-%d")
                .map_err(|_| AppError::Validation("due_date deve ser YYYY-MM-DD".into()))
        })
        .transpose()?;

    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    let id = sqlx::query_scalar::<_, Uuid>(
        "INSERT INTO charges (wallet_id, description, amount, due_date, debtor_name)
         SELECT w.id, $2, $3, $4, $5 FROM wallets w WHERE w.id = $1
         RETURNING id",
    )
    .bind(wallet)
    .bind(description)
    .bind(req.amount.amount())
    .bind(due_date)
    .bind(req.debtor_name.as_deref())
    .fetch_one(&state.db)
    .await?;

    let charge = buscar(&state, id, wallet).await?;
    let qr = DemoPaymentProvider.qr_payload(id, req.amount.amount());

    Ok((StatusCode::CREATED, Json(ChargeWithQr { charge, qr })))
}

/// GET /charges
pub async fn list_charges(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Query(q): Query<ChargeQuery>,
) -> AppResult<Json<Vec<ChargeResponse>>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    if let Some(s) = q.status.as_deref()
        && !matches!(s, "PENDING" | "RECEIVED" | "CANCELED")
    {
        return Err(AppError::Validation(format!(
            "status inválido: '{s}'. Use PENDING, RECEIVED ou CANCELED."
        )));
    }

    let linhas = sqlx::query_as::<_, (
        Uuid,
        String,
        rust_decimal::Decimal,
        String,
        Option<String>,
        Option<chrono::NaiveDate>,
        String,
        Option<chrono::DateTime<chrono::Utc>>,
        chrono::DateTime<chrono::Utc>,
    )>(
        "SELECT id, description, amount, currency, debtor_name, due_date,
                status, received_at, created_at
           FROM charges
          WHERE wallet_id = $1
            AND ($2::text IS NULL OR status = $2)
          ORDER BY created_at DESC
          LIMIT $3 OFFSET $4",
    )
    .bind(wallet)
    .bind(q.status.as_deref())
    .bind(q.limit.unwrap_or(50).clamp(1, 200))
    .bind(q.offset.unwrap_or(0).max(0))
    .fetch_all(&state.db)
    .await?;

    Ok(Json(linhas.into_iter().map(convert).collect()))
}

/// GET /charges/{id}/qr
///
/// Só PENDING e CANCELED. Um QR de cobrança já recebida levaria alguém a pagar
/// de novo — o pior tipo de erro numa cobrança.
pub async fn charge_qr(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
) -> AppResult<Json<PaymentPayload>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;
    let charge = buscar(&state, id, wallet).await?;

    if charge.status == "RECEIVED" {
        return Err(AppError::Conflict(
            "cobrança já recebida; QR não deve ser exibido".into(),
        ));
    }

    Ok(Json(
        DemoPaymentProvider.qr_payload(id, charge.amount.amount()),
    ))
}

/// POST /charges/{id}/receive
///
/// Registra que a cobrança foi recebida. **Isto não confirma um pagamento** —
/// o usuário está declareando que recebeu, exatamente como no `bills/{id}/pay`.
/// Não há integração, webhook ou polling: nada no sistema pode confirmar por
/// conta própria, e é por isso que `DemoPaymentProvider::moves_real_money()`
/// é `false`.
pub async fn receive_charge(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
) -> AppResult<Json<ChargeResponse>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;
    let atual = buscar(&state, id, wallet).await?;

    if atual.status != "PENDING" {
        return Err(AppError::Conflict(format!(
            "cobrança já está {}; só PENDING pode ser recebida",
            atual.status
        )));
    }

    let transaction_id = ledger::postar(
        &state.db,
        wallet,
        TransactionType::ChargeReceived,
        atual.amount,
        Some(&format!("Recebimento: {}", atual.description)),
        Some(serde_json::json!({ "charge_id": id })),
    )
    .await?;

    let rows = sqlx::query(
        "UPDATE charges
            SET status = 'RECEIVED', received_at = now(),
                received_transaction_id = $3, updated_at = now()
          WHERE id = $1 AND wallet_id = $2 AND status = 'PENDING'",
    )
    .bind(id)
    .bind(wallet)
    .bind(transaction_id)
    .execute(&state.db)
    .await?;

    if rows.rows_affected() == 0 {
        tracing::warn!(charge_id = %id, "recebimento concorrente; segunda tentativa recusada");
        return Err(AppError::Conflict("cobrança já foi recebida".into()));
    }

    buscar(&state, id, wallet).await.map(Json)
}

/// DELETE /charges/{id} — cancela. Só PENDING.
pub async fn cancel_charge(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
) -> AppResult<Json<ChargeResponse>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    let rows = sqlx::query(
        "UPDATE charges SET status = 'CANCELED', updated_at = now()
          WHERE id = $1 AND wallet_id = $2 AND status = 'PENDING'",
    )
    .bind(id)
    .bind(wallet)
    .execute(&state.db)
    .await?;

    if rows.rows_affected() == 0 {
        return Err(AppError::Conflict("cobrança não está pendente".into()));
    }

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

type ChargeRow = (
    Uuid,
    String,
    rust_decimal::Decimal,
    String,
    Option<String>,
    Option<chrono::NaiveDate>,
    String,
    Option<chrono::DateTime<chrono::Utc>>,
    chrono::DateTime<chrono::Utc>,
);

async fn buscar(state: &AppState, id: Uuid, wallet: Uuid) -> AppResult<ChargeResponse> {
    let row = sqlx::query_as::<_, ChargeRow>(
        "SELECT id, description, amount, currency, debtor_name, due_date,
                status, received_at, created_at
           FROM charges WHERE id = $1 AND wallet_id = $2",
    )
    .bind(id)
    .bind(wallet)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NotFound("cobrança"))?;

    Ok(convert(row))
}

fn convert(r: ChargeRow) -> ChargeResponse {
    ChargeResponse {
        id: r.0,
        description: r.1,
        amount: Money::new(r.2),
        currency: r.3,
        debtor_name: r.4,
        due_date: r.5,
        status: r.6,
        received_at: r.7,
        created_at: r.8,
    }
}