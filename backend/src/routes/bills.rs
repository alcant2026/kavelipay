//! Contas a pagar (boletos).
//!
//! Duas regras da spec governam este módulo:
//!
//! 1. `OVERDUE` NUNCA é persistido. É a apresentação de um `PENDING` cujo
//!    `due_date` passou, calculada na leitura. O CHECK do banco recusa a
//!    gravação; aqui só existe como campo de apresentação.
//! 2. O MVP NÃO paga boleto real. `mark_paid` marca o que o USUÁRIO afirma ter
//!    pago. Não há integração, não há confirmação automática, e a transação do
//!    ledger registra o fato declarado, não um pagamento.

use axum::Json;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::{AppError, AppResult};
use crate::ledger::{self, TransactionType};
use crate::money::Money;
use rust_decimal::Decimal;
use crate::state::wallet_ativa;
use crate::state::AppState;

#[derive(Deserialize)]
pub struct NewBill {
    pub description: String,
    pub beneficiario: Option<String>,
    /// String decimal, ex: "89.90". Float é 422.
    pub amount: Money,
    /// `YYYY-MM-DD`. Fuso do servidor — a spec não define timezone de
    /// vencimento (lacuna conhecida no AGENTS.md).
    pub due_date: String,
    pub linha_digitavel: Option<String>,
    /// `WEEKLY | MONTHLY | YEARLY`. A PRÓXIMA ocorrência é gerada por
    /// `POST /bills/generate-recurring`; aqui só se declara o padrão.
    pub recurrence: Option<String>,
    /// Último vencimento da série. Ausente = série aberta.
    pub recurrence_until: Option<String>,
    pub category_id: Option<Uuid>,
}

#[derive(Serialize)]
pub struct BillResponse {
    pub id: Uuid,
    pub description: String,
    pub beneficiario: Option<String>,
    pub amount: Money,
    pub currency: String,
    pub due_date: chrono::NaiveDate,
    pub linha_digitavel: Option<String>,
    /// Estado persistido: PENDING | PAID | CANCELED.
    pub status: String,
    /// DERIVADO na leitura — nunca é gravado no banco.
    pub is_overdue: bool,
    pub paid_at: Option<chrono::DateTime<chrono::Utc>>,
    pub paid_transaction_id: Option<Uuid>,
    pub comprovante_url: Option<String>,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Serialize)]
pub struct BillListResponse {
    pub items: Vec<BillResponse>,
    pub balance: Money,
}

#[derive(Deserialize)]
pub struct BillQuery {
    /// Filtra por status persistido. `OVERDUE` não é aceito aqui: para achar
    /// vencidos use `?overdue=true`.
    pub status: Option<String>,
    pub overdue: Option<bool>,
    pub limit: Option<i64>,
    pub offset: Option<i64>,
}

/// POST /bills
pub async fn create_bill(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Json(req): Json<NewBill>,
) -> AppResult<(StatusCode, Json<BillResponse>)> {
    let description = req.description.trim();
    if description.is_empty() {
        return Err(AppError::Validation("descrição é obrigatória".into()));
    }
    if !req.amount.is_positive() {
        return Err(AppError::InvalidAmount("valor deve ser maior que zero".into()));
    }

    let due_date = chrono::NaiveDate::parse_from_str(req.due_date.trim(), "%Y-%m-%d")
        .map_err(|_| AppError::Validation("due_date deve ser YYYY-MM-DD".into()))?;

    // Recorrência é validada aqui para dar 422 com mensagem, não um check_violation
    // do banco virando 500.
    let recurrence = req
        .recurrence
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(|s| {
            if matches!(s, "WEEKLY" | "MONTHLY" | "YEARLY") {
                Ok(s.to_string())
            } else {
                Err(AppError::Validation(format!(
                    "recurrence inválida: '{s}'. Use WEEKLY, MONTHLY ou YEARLY."
                )))
            }
        })
        .transpose()?;

    let recurrence_until = req
        .recurrence_until
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(|s| {
            chrono::NaiveDate::parse_from_str(s, "%Y-%m-%d")
                .map_err(|_| AppError::Validation("recurrence_until deve ser YYYY-MM-DD".into()))
        })
        .transpose()?;

    if let Some(ate) = recurrence_until
        && ate < due_date
    {
        return Err(AppError::Validation(
            "recurrence_until não pode ser anterior ao primeiro vencimento".into(),
        ));
    }

    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    // Categoria validada contra o tipo da operação: um boleto é sempre despesa.
    let mut tx = state.db.begin().await?;
    let categoria = crate::categories::validar_e_resolver(
        &mut tx,
        auth.user_id(),
        req.category_id,
        crate::categories::CategoryKind::Expense,
    )
    .await?;

    // Grava sempre como PENDING. Não existe caminho de código que crie um
    // boleto já pago ou já vencido.
    let id = sqlx::query_scalar::<_, Uuid>(
        "INSERT INTO bills (wallet_id, description, beneficiario, amount, due_date,
                            linha_digitavel, recurrence, recurrence_until, category_id)
         SELECT w.id, $2, $3, $4, $5, $6, $7, $8, $9 FROM wallets w WHERE w.id = $1
         RETURNING id",
    )
    .bind(wallet)
    .bind(description)
    .bind(req.beneficiario.as_deref())
    .bind(req.amount.amount())
    .bind(due_date)
    .bind(req.linha_digitavel.as_deref())
    .bind(&recurrence)
    .bind(recurrence_until)
    .bind(categoria)
    .fetch_one(&mut *tx)
    .await?;
    tx.commit().await?;

    let bill = buscar(&state, id, wallet).await?;
    Ok((StatusCode::CREATED, Json(bill)))
}

/// GET /bills — lista da wallet do usuário.
pub async fn list_bills(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Query(q): Query<BillQuery>,
) -> AppResult<Json<BillListResponse>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    if let Some(s) = q.status.as_deref()
        && !matches!(s, "PENDING" | "PAID" | "CANCELED")
    {
        // "OVERDUE" aqui é o erro provável e o mais instrutivo: não é status.
        return Err(AppError::Validation(format!(
            "status inválido: '{s}'. Use PENDING, PAID ou CANCELED. \
             Para vencidos use ?overdue=true — OVERDUE não é um estado persistido."
        )));
    }

    let hoje = chrono::Local::now().date_naive();

    let itens = sqlx::query_as::<_, (
        Uuid,
        String,
        Option<String>,
        rust_decimal::Decimal,
        String,
        chrono::NaiveDate,
        Option<String>,
        String,
        chrono::NaiveDate,
        Option<chrono::DateTime<chrono::Utc>>,
        Option<Uuid>,
        Option<String>,
        chrono::DateTime<chrono::Utc>,
    )>(
        "SELECT id, description, beneficiario, amount, currency, due_date,
                linha_digitavel, status, due_date, paid_at,
                paid_transaction_id, comprovante_url, created_at
           FROM bills
          WHERE wallet_id = $1
            AND ($2::text IS NULL OR status = $2)
            AND ($3::bool IS NULL
                 OR (status = 'PENDING' AND due_date < $4) = $3)
          ORDER BY due_date ASC, created_at DESC
          LIMIT $5 OFFSET $6",
    )
    .bind(wallet)
    .bind(q.status.as_deref())
    .bind(q.overdue)
    .bind(hoje)
    .bind(q.limit.unwrap_or(50).clamp(1, 200))
    .bind(q.offset.unwrap_or(0).max(0))
    .fetch_all(&state.db)
    .await?;

    let (saldo, _) = ledger::saldo(&state.db, wallet).await?;

    Ok(Json(BillListResponse {
        items: itens
            .into_iter()
            .map(convert)
            .collect(),
        balance: Money::new(saldo),
    }))
}

#[derive(Deserialize)]
pub struct MarkPaidRequest {
    /// Comprovante do usuário. O MVP não faz upload; é uma referência externa.
    pub comprovante_url: Option<String>,
}

/// POST /bills/:id/pay
///
/// Marca como pago o que o USUÁRIO afirma ter pago, e gera a transação do ledger
/// correspondente. Não há pagamento real, não há confirmação externa, e o
/// recurso não simula sucesso de boleto.
///
/// Idempotente por construção: o boleto é lido com `FOR UPDATE` dentro da
/// mesma transação que posta no ledger e marca PAID. Pagar duas vezes: o
/// segundo request espera o COMMIT do primeiro, enxerga PAID e devolve 409
/// sem debitar a carteira de novo e sem transação órfã.
pub async fn mark_paid(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
    Json(req): Json<MarkPaidRequest>,
) -> AppResult<Json<BillResponse>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    let mut tx = state.db.begin().await?;

    // Filtra por id E wallet_id: boleto de outra carteira não existe (404).
    // FOR UPDATE serializa dois pagamentos concorrentes — o segundo espera o
    // COMMIT do primeiro e é recusado antes de postar no ledger.
    let pendente = sqlx::query_as::<_, (String, Decimal, String)>(
        "SELECT status, amount, description
           FROM bills
          WHERE id = $1 AND wallet_id = $2
          FOR UPDATE",
    )
    .bind(id)
    .bind(wallet)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or(AppError::NotFound("boleto"))?;

    let (status, amount, description) = pendente;
    if status != "PENDING" {
        return Err(AppError::Conflict(format!(
            "boleto já está {}; só PENDING pode ser pago",
            status
        )));
    }

    // Transação primeiro: se o ledger recusar (desequilíbrio, wallet inválida),
    // o boleto continua PENDING. O inverso deixaria o boleto marcado como pago
    // sem dinheiro correspondente no ledger — e o rollback do tx desfaz os dois
    // lados juntos em qualquer falha depois daqui.
    let transaction_id = ledger::postar_em_com_categoria(
        &mut tx,
        wallet,
        TransactionType::BillPaid,
        Money::new(amount),
        Some(&format!("Pagamento: {}", description)),
        Some(serde_json::json!({ "bill_id": id })),
        None,
        Some(auth.user_id()),
    )
    .await?;

    let rows = sqlx::query(
        "UPDATE bills
            SET status = 'PAID',
                paid_at = now(),
                paid_transaction_id = $3,
                comprovante_url = COALESCE($4, comprovante_url),
                updated_at = now()
          WHERE id = $1 AND wallet_id = $2 AND status = 'PENDING'",
    )
    .bind(id)
    .bind(wallet)
    .bind(transaction_id)
    .bind(req.comprovante_url.as_deref())
    .execute(&mut *tx)
    .await?;

    if rows.rows_affected() == 0 {
        // Corrida defensiva: com o FOR UPDATE acima não deveria acontecer, mas
        // se acontecer, desfaz TUDO — o ledger não pode ficar com débito
        // órfão de um boleto não marcado como pago.
        tracing::warn!(bill_id = %id, "boleto pago concorrente; transação desfeita");
        tx.rollback().await?;
        return Err(AppError::Conflict("boleto já foi pago".into()));
    }

    tx.commit().await?;

    buscar(&state, id, wallet).await.map(Json)
}

/// DELETE /bills/:id — cancela (soft delete: o lançamento contábil é histórico).
pub async fn cancel_bill(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
) -> AppResult<Json<BillResponse>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    let atual = buscar(&state, id, wallet).await?;
    if atual.status == "PAID" {
        return Err(AppError::Conflict(
            "boleto pago não pode ser cancelado".into(),
        ));
    }

    let rows = sqlx::query(
        "UPDATE bills SET status = 'CANCELED', updated_at = now()
          WHERE id = $1 AND wallet_id = $2 AND status = 'PENDING'",
    )
    .bind(id)
    .bind(wallet)
    .execute(&state.db)
    .await?;

    if rows.rows_affected() == 0 {
        return Err(AppError::Conflict("boleto não está pendente".into()));
    }

    buscar(&state, id, wallet).await.map(Json)
}

// ── helpers ──────────────────────────────────────────────────────────────────

/// Busca por id **e** wallet. O filtro por wallet_id é a fronteira de
/// autorização: um id de boleto de outra pessoa devolve NotFound, não os dados.
async fn buscar(state: &AppState, id: Uuid, wallet: Uuid) -> AppResult<BillResponse> {
    let row = sqlx::query_as::<_, (
        Uuid,
        String,
        Option<String>,
        rust_decimal::Decimal,
        String,
        chrono::NaiveDate,
        Option<String>,
        String,
        chrono::NaiveDate,
        Option<chrono::DateTime<chrono::Utc>>,
        Option<Uuid>,
        Option<String>,
        chrono::DateTime<chrono::Utc>,
    )>(
        "SELECT id, description, beneficiario, amount, currency, due_date,
                linha_digitavel, status, due_date, paid_at,
                paid_transaction_id, comprovante_url, created_at
           FROM bills WHERE id = $1 AND wallet_id = $2",
    )
    .bind(id)
    .bind(wallet)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NotFound("boleto"))?;

    Ok(convert(row))
}

type BillRow = (
    Uuid,
    String,
    Option<String>,
    rust_decimal::Decimal,
    String,
    chrono::NaiveDate,
    Option<String>,
    String,
    chrono::NaiveDate,
    Option<chrono::DateTime<chrono::Utc>>,
    Option<Uuid>,
    Option<String>,
    chrono::DateTime<chrono::Utc>,
);

/// `is_overdue` é DERIVADO aqui, na leitura. Nunca vem do banco — o CHECK de
/// `bills_status_check` impede que exista na tabela.
fn convert(r: BillRow) -> BillResponse {
    let hoje = chrono::Local::now().date_naive();
    let is_overdue = r.7 == "PENDING" && r.8 < hoje;

    BillResponse {
        id: r.0,
        description: r.1,
        beneficiario: r.2,
        amount: Money::new(r.3),
        currency: r.4,
        due_date: r.5,
        linha_digitavel: r.6,
        status: r.7,
        is_overdue,
        paid_at: r.9,
        paid_transaction_id: r.10,
        comprovante_url: r.11,
        created_at: r.12,
    }
}