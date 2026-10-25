//! Endpoints de carteira e ledger. Todos exigem `AuthUser`: a wallet é
//! resolvida pelo `user_id` do token, nunca por um id da URL ou do body.

use axum::Json;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::{AppError, AppResult};
use crate::ledger::{self, TransactionType};
use crate::money::Money;
use crate::state::AppState;

#[derive(Serialize)]
pub struct WalletResponse {
    pub wallet_id: Uuid,
    pub currency: String,
    pub status: String,
    pub balance: Money,
    /// A spec (seção 4) exige que o backend deixe explícito, no modelo de
    /// dados, que o saldo NÃO é dinheiro real. Sem este campo o app não tem
    /// como avisar o usuário de nada.
    pub is_demonstrative: bool,
}

/// GET /wallet — saldo derivado do ledger. Nunca há coluna de saldo.
pub async fn get_wallet(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
) -> AppResult<Json<WalletResponse>> {
    let (wallet_id, currency, status, is_demonstrative) = wallet_do_usuario(&state, auth.user_id()).await?;
    let (saldo, _) = ledger::saldo(&state.db, wallet_id).await?;

    Ok(Json(WalletResponse {
        wallet_id,
        currency,
        status,
        balance: Money::new(saldo),
        is_demonstrative,
    }))
}

#[derive(Deserialize)]
pub struct NewTransaction {
    /// "ENTRY" (entrada) ou "EXPENSE" (despesa).
    pub r#type: String,
    /// String decimal, ex: "10.50". Float é recusado com 422.
    pub amount: Money,
    pub description: Option<String>,
    pub metadata: Option<serde_json::Value>,
    /// Classificação (opcional). Categoria de despesa não classifica receita —
    /// validado contra o tipo, com 422.
    pub category_id: Option<Uuid>,
}

#[derive(Serialize)]
pub struct TransactionResponse {
    pub transaction_id: Uuid,
    pub balance: Money,
}

/// POST /wallet/transactions — registra entrada ou despesa.
///
/// O valor vem do app, mas o SALDO devolvido é sempre recalculado do ledger pelo
/// backend. O app nunca decide o próprio saldo.
pub async fn create_transaction(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Json(req): Json<NewTransaction>,
) -> AppResult<(StatusCode, Json<TransactionResponse>)> {
    let tipo = TransactionType::from_str(&req.r#type).ok_or_else(|| {
        AppError::Validation(format!(
            "type inválido: '{}'. Use ENTRY, EXPENSE ou BILL_PAID.",
            req.r#type
        ))
    })?;

    // Só os tipos de lancamento manual. Os internos (SETTLEMENT,
    // CHARGE_RECEIVED, INVESTMENT_*) nascem de pagamentos, recebimentos e
    // movimentos de investimento reais — aceitá-los aqui creditaria/debitaria
    // a carteira sem o recurso de origem existir (o extrato e o relatório
    // contariam dinheiro que nunca teve por trás um bill/charge/split).
    let tipo = match tipo {
        TransactionType::Entry | TransactionType::Expense | TransactionType::BillPaid => tipo,
        _ => {
            return Err(AppError::Validation(format!(
                "type '{}' não é lançamento manual. Use ENTRY, EXPENSE ou BILL_PAID.",
                req.r#type
            )));
        }
    };

    let (wallet_id, _, status, _) = wallet_do_usuario(&state, auth.user_id()).await?;

    // Wallet bloqueada ou fechada não movimenta dinheiro.
    if status != "ACTIVE" {
        return Err(AppError::Conflict(format!(
            "carteira {status}: não aceita movimentações"
        )));
    }

    let transaction_id = ledger::postar_com_categoria(
        &state.db,
        wallet_id,
        tipo,
        req.amount,
        req.description.as_deref(),
        req.metadata,
        req.category_id,
        Some(auth.user_id()),
    )
    .await?;

    // Recalcula do zero a partir dos lançamentos — nunca "saldo anterior + valor".
    let (saldo, _) = ledger::saldo(&state.db, wallet_id).await?;

    Ok((
        StatusCode::CREATED,
        Json(TransactionResponse {
            transaction_id,
            balance: Money::new(saldo),
        }),
    ))
}

#[derive(Deserialize)]
pub struct ClassifyRequest {
    /// Categoria a aplicar. `null` remove a classificação.
    pub category_id: Option<Uuid>,
}

/// PATCH /wallet/transactions/{id}/category
///
/// Classificar (ou desclassificar) um lançamento já existente. O app sempre
/// precisa disso: a pessoa registra o gasto na hora e classifica depois, ou
/// reclassifica porque escolheu a categoria errada.
///
/// Validado como na criação: categoria de despesa não classifica receita, e
/// categoria de outra pessoa é 422.
pub async fn classify_transaction(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
    Json(req): Json<ClassifyRequest>,
) -> AppResult<Json<TransactionResponse>> {
    let (wallet_id, _, status, _) = wallet_do_usuario(&state, auth.user_id()).await?;

    if status != "ACTIVE" {
        return Err(AppError::Conflict(format!(
            "carteira {status}: não aceita operações"
        )));
    }

    let tipo = sqlx::query_scalar::<_, String>(
        "SELECT type FROM ledger_transactions WHERE id = $1 AND wallet_id = $2",
    )
    .bind(id)
    .bind(wallet_id)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NotFound("transação"))?;

    let tipo = crate::ledger::TransactionType::from_str(&tipo)
        .ok_or_else(|| AppError::Internal(anyhow::anyhow!("tipo de transação desconhecido: {tipo}")))?;

    // Valida na mesma transação do UPDATE: categoria inválida não pode deixar a
    // classificação pela metade.
    let mut tx = state.db.begin().await?;
    let categoria =
        crate::categories::validar_e_resolver(
            &mut tx,
            auth.user_id(),
            req.category_id,
            crate::categories::kind_para_tipo(tipo),
        )
        .await?;

    sqlx::query("UPDATE ledger_transactions SET category_id = $2 WHERE id = $1")
        .bind(id)
        .bind(categoria)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;

    let (saldo, _) = ledger::saldo(&state.db, wallet_id).await?;

    Ok(Json(TransactionResponse {
        transaction_id: id,
        balance: Money::new(saldo),
    }))
}

#[derive(Deserialize)]
pub struct ExtratoQuery {
    pub limit: Option<i64>,
    pub offset: Option<i64>,
}

#[derive(Serialize)]
pub struct ExtratoItem {
    pub transaction_id: Uuid,
    pub r#type: String,
    pub amount: Money,
    pub description: String,
    /// true = saiu da carteira (débito); false = entrou (crédito).
    pub is_saida: bool,
    pub created_at: chrono::DateTime<chrono::Utc>,
    /// Nome da categoria, se classificado. NULL = "sem categoria".
    pub category: Option<String>,
    pub category_color: Option<String>,
}

#[derive(Serialize)]
pub struct ExtratoResponse {
    pub items: Vec<ExtratoItem>,
    pub balance: Money,
}

/// GET /wallet/transactions — extrato paginado.
pub async fn list_transactions(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Query(q): Query<ExtratoQuery>,
) -> AppResult<Json<ExtratoResponse>> {
    let (wallet_id, _, _, _) = wallet_do_usuario(&state, auth.user_id()).await?;

    let linhas = ledger::extrato(
        &state.db,
        wallet_id,
        q.limit.unwrap_or(50),
        q.offset.unwrap_or(0),
    )
    .await?;

    let (saldo, _) = ledger::saldo(&state.db, wallet_id).await?;

    Ok(Json(ExtratoResponse {
        items: linhas
            .into_iter()
            .map(
                |(id, tipo, valor, descricao, is_saida, created_at, categoria, cor)| {
                    ExtratoItem {
                        transaction_id: id,
                        r#type: tipo,
                        amount: Money::new(valor),
                        description: descricao,
                        is_saida,
                        created_at,
                        category: categoria,
                        category_color: cor,
                    }
                },
            )
            .collect(),
        balance: Money::new(saldo),
    }))
}

/// Wallet do usuário autenticado. Filtro por `user_id` — o token.
/// `is_demonstrative` viaja junto porque o app precisa avisar antes de exibir.
async fn wallet_do_usuario(
    state: &AppState,
    user_id: Uuid,
) -> AppResult<(Uuid, String, String, bool)> {
    sqlx::query_as::<_, (Uuid, String, String, bool)>(
        "SELECT id, currency, status, is_demonstrative FROM wallets WHERE user_id = $1",
    )
    .bind(user_id)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NotFound("wallet"))
}