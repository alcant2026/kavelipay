//! Histórico unificado. Objetivo 19 da spec.
//!
//! Junta em um feed só o que está espalhado em `ledger_transactions`, `bills`,
//! `charges` e `expense_splits`. Cada item declara `kind` para o app saber que
//! tela abrir.
//!
//! O feed é de LEITURA. Nenhum saldo é recalculado aqui — o item de transação
//! carrega o valor que o ledger gravou, e a resposta inclui o saldo atual
//! derivado por `ledger::saldo`. Se este handler calculasse saldo, teria duas
//! fontes de verdade.

use axum::Json;
use axum::extract::{Query, State};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::AppResult;
use crate::ledger;
use crate::money::Money;
use crate::state::AppState;

#[derive(Deserialize)]
pub struct HistoryQuery {
    pub limit: Option<i64>,
    pub offset: Option<i64>,
    /// Filtra por tipo de item: TRANSACTION | BILL | CHARGE | SPLIT.
    pub kind: Option<String>,
}

#[derive(Serialize)]
pub struct HistoryItem {
    pub kind: &'static str,
    pub id: Uuid,
    pub title: String,
    /// Sentinela de data para ordenar com `created_at`. Só para ordenação; o app
    /// não deve mostrar. Evita serializar duas vezes a mesma data em formatos
    /// diferentes por linha.
    pub occurred_at: chrono::DateTime<chrono::Utc>,
    pub amount: Money,
    /// true = dinheiro saiu da carteira.
    pub is_saida: bool,
    pub status: String,
    pub detail_url: String,
}

#[derive(Serialize)]
pub struct HistoryResponse {
    pub items: Vec<HistoryItem>,
    pub balance: Money,
}

pub async fn list(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Query(q): Query<HistoryQuery>,
) -> AppResult<Json<HistoryResponse>> {
    let wallet = sqlx::query_scalar::<_, Uuid>("SELECT id FROM wallets WHERE user_id = $1")
        .bind(auth.user_id())
        .fetch_optional(&state.db)
        .await?
        .ok_or(crate::error::AppError::NotFound("wallet"))?;

    let limite = q.limit.unwrap_or(50).clamp(1, 200);
    let offset = q.offset.unwrap_or(0).max(0);

    let filtro = q.kind.as_deref().filter(|k| {
        matches!(*k, "TRANSACTION" | "BILL" | "CHARGE" | "SPLIT")
    });

    // Uma UNION ALL só: PostgreSQL exige que todas as colunas tenham tipos
    // casáveis, daí o `::text` no kind e no status. `occurred_at` em todos os
    // ramos é o que permite o ORDER BY único no fim.
    let itens = sqlx::query_as::<_, (
        String,
        Uuid,
        String,
        chrono::DateTime<chrono::Utc>,
        rust_decimal::Decimal,
        bool,
        String,
        String,
    )>(
        "SELECT * FROM (
            SELECT 'TRANSACTION'::text AS kind, t.id, COALESCE(t.description, t.type) AS title,
                   t.created_at AS occurred_at,
                   CASE WHEN e.debit > 0 THEN e.debit ELSE e.credit END AS amount,
                   (e.debit > 0) AS is_saida, t.status::text AS status,
                   '/wallet/transactions'::text AS detail_url
              FROM ledger_transactions t
              JOIN ledger_entries e ON e.transaction_id = t.id
              JOIN ledger_accounts a ON a.id = e.account_id
             WHERE t.wallet_id = $1 AND a.kind = 'USER_AVAILABLE'

            UNION ALL

            SELECT 'BILL'::text, b.id, b.description, b.created_at, b.amount,
                   true, b.status::text, '/bills/' || b.id::text
              FROM bills b
             WHERE b.wallet_id = $1 AND b.status <> 'CANCELED'

            UNION ALL

            SELECT 'CHARGE'::text, c.id, c.description, c.created_at, c.amount,
                   false, c.status::text, '/charges/' || c.id::text
              FROM charges c
             WHERE c.wallet_id = $1 AND c.status <> 'CANCELED'

            UNION ALL

            SELECT 'SPLIT'::text, s.id, s.description, s.created_at, s.total_amount,
                   true, 'OPEN'::text, '/splits/' || s.id::text
              FROM expense_splits s
             WHERE s.wallet_id = $1
         ) h
         WHERE ($2::text IS NULL OR h.kind = $2)
         ORDER BY h.occurred_at DESC, h.id DESC
         LIMIT $3 OFFSET $4",
    )
    .bind(wallet)
    .bind(filtro)
    .bind(limite)
    .bind(offset)
    .fetch_all(&state.db)
    .await?;

    let (saldo, _) = ledger::saldo(&state.db, wallet).await?;

    Ok(Json(HistoryResponse {
        items: itens
            .into_iter()
            .map(
                |(kind, id, title, occurred_at, amount, is_saida, status, detail_url)| {
                    HistoryItem {
                        kind: match kind.as_str() {
                            "TRANSACTION" => "TRANSACTION",
                            "BILL" => "BILL",
                            "CHARGE" => "CHARGE",
                            _ => "SPLIT",
                        },
                        id,
                        title,
                        occurred_at,
                        amount: Money::new(amount),
                        is_saida,
                        status,
                        detail_url,
                    }
                },
            )
            .collect(),
        balance: Money::new(saldo),
    }))
}