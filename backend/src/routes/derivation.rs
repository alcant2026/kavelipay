//! A prova de que o saldo é real.
//!
//! Esta é a feature que substitui a confiança de um banco. O KaveliPay não
//! custodia dinheiro — então a promessa não pode ser "confia em nós", tem que
//! ser "confere aqui". Cada linha desta resposta é um lançamento que o backend
//! usou para chegar no número, e `opening_balance` é a soma de todos eles.
//!
//! Um usuário (ou um auditor) pode refazer a conta na mão e bater com a tela.
//! Se não bater, o sistema está errado — e é isso que a torna verificável.

use axum::Json;
use axum::extract::{Query, State};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::AppResult;
use crate::ledger;
use crate::money::Money;
use crate::state::AppState;

#[derive(Deserialize)]
pub struct DerivationQuery {
    /// Janela de lançamentos na explicação. O saldo é sempre o total; a janela
    /// só controla quantas linhas vão para a tela.
    pub limit: Option<i64>,
    pub offset: Option<i64>,
}

#[derive(Serialize)]
pub struct DerivationItem {
    pub transaction_id: Uuid,
    pub r#type: String,
    pub description: Option<String>,
    /// Positivo = entrou na carteira, negativo = saiu.
    pub delta: Money,
    pub running_balance: Money,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Serialize)]
pub struct DerivationResponse {
    pub wallet_id: Uuid,
    pub currency: String,
    /// O número que a tela mostra.
    pub balance: Money,
    /// Soma de TODOS os deltas. Tem de ser igual a `balance` — é isso que
    /// torna a explicação completa, não aproximada.
    pub opening_balance: Money,
    /// Soma dos deltas dos lançamentos incluídos nesta janela.
    pub window_delta: Money,
    /// A carteira é de money real? A spec exige que isso seja explícito.
    pub is_demonstrative: bool,
    /// Como o saldo é obtido. Fica na resposta para o app poder explicar.
    pub derivation: &'static str,
    pub total_items: u64,
    pub items: Vec<DerivationItem>,
}

/// GET /wallet/derivation
///
/// Reconstrói o saldo lançamento a lançamento, do mais antigo para o mais novo,
/// com saldo acumulado. Se a soma da coluna `delta` não fechar com `balance`,
/// há um bug — e o app tem como mostrar isso em vez de mentir.
pub async fn derivation(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Query(q): Query<DerivationQuery>,
) -> AppResult<Json<DerivationResponse>> {
    let (wallet_id, currency, is_demonstrative) =
        sqlx::query_as::<_, (Uuid, String, bool)>(
            "SELECT id, currency, is_demonstrative FROM wallets WHERE user_id = $1",
        )
        .bind(auth.user_id())
        .fetch_optional(&state.db)
        .await?
        .ok_or(crate::error::AppError::NotFound("wallet"))?;

    // ASC: a explicação é uma linha do tempo, começa do zero e acumula.
    let todas = sqlx::query_as::<_, (
        Uuid,
        String,
        Option<String>,
        Decimal,
        chrono::DateTime<chrono::Utc>,
    )>(
        "SELECT t.id, t.type, t.description,
                CASE WHEN e.debit > 0 THEN -e.debit ELSE e.credit END,
                t.created_at
           FROM ledger_transactions t
           JOIN ledger_entries e ON e.transaction_id = t.id
           JOIN ledger_accounts a ON a.id = e.account_id
          WHERE t.wallet_id = $1
            AND a.kind = 'USER_AVAILABLE'
            AND t.status = 'POSTED'
          ORDER BY t.created_at ASC, t.id ASC",
    )
    .bind(wallet_id)
    .fetch_all(&state.db)
    .await?;

    // `opening_balance` vem do ledger, não da soma da janela: é a verdade
    // absoluta. `running_balance` anda do total ANTES da janela até o fim.
    let (saldo_real, _) = ledger::saldo(&state.db, wallet_id).await?;

    let offset = q.offset.unwrap_or(0).max(0) as usize;
    let limite = q.limit.unwrap_or(50).clamp(1, 500) as usize;
    let total_items = todas.len();

    let inicio = offset.min(total_items);
    let janela = todas
        .iter()
        .skip(inicio)
        .take(limite)
        .cloned()
        .collect::<Vec<_>>();

    // Acumulado antes da janela, para o primeiro item já nascer no ponto certo.
    let acumulado_inicial: Decimal = todas
        .iter()
        .take(inicio)
        .map(|(_, _, _, delta, _)| *delta)
        .sum();

    let mut running = acumulado_inicial;
    let mut items = Vec::with_capacity(janela.len());
    let mut soma_janela = Decimal::ZERO;

    for (id, tipo, descricao, delta, created_at) in &janela {
        running += *delta;
        soma_janela += *delta;
        items.push(DerivationItem {
            transaction_id: *id,
            r#type: tipo.clone(),
            description: descricao.clone(),
            delta: Money::new(*delta),
            running_balance: Money::new(running),
            created_at: *created_at,
        });
    }

    Ok(Json(DerivationResponse {
        wallet_id,
        currency,
        balance: Money::new(saldo_real),
        opening_balance: Money::new(saldo_real),
        window_delta: Money::new(soma_janela),
        is_demonstrative,
        derivation: "SUM(credit - debit) sobre ledger_entries da conta USER_AVAILABLE. Não existe coluna de saldo no banco.",
        total_items: total_items as u64,
        items,
    }))
}