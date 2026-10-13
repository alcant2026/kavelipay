//! Notificações. Objetivo 20 da spec.
//!
//! Tudo aqui é DERIVADO: o aviso de "conta vencendo" não é gravado por um
//! processo agendado, é gerado a partir de `bills` no momento em que o usuário
//! pede. Não há cron, não há push no background. Consequência prática: a
//! notificação existe enquanto o boleto existir, e some quando ele for pago.
//!
//! A geração é idempotente por `(wallet, type, referência)` — abrir o app três
//! vezes não cria três avisos do mesmo boleto.

use axum::Json;
use axum::extract::{Path, Query, State};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::{AppError, AppResult};
use crate::state::AppState;

#[derive(Serialize)]
pub struct NotificationResponse {
    pub id: Uuid,
    pub r#type: String,
    pub title: String,
    pub body: Option<String>,
    pub reference_type: String,
    pub reference_id: Uuid,
    pub read_at: Option<chrono::DateTime<chrono::Utc>>,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Deserialize)]
pub struct NotificationQuery {
    pub unread_only: Option<bool>,
    pub limit: Option<i64>,
    pub offset: Option<i64>,
}

/// Janela de antecedência do aviso de vencimento.
const DIAS_ANTES: i32 = 3;

/// POST /notifications/generate
///
/// Gera avisos a partir de `bills` PENDING. Idempotente. Só cria `BILL_DUE`
/// (vence em até 3 dias) e `BILL_OVERDUE` (já venceu) — nunca os dois para o
/// mesmo boleto, porque o segundo sobrescreveria o primeiro e o usuário
/// perderia o lembrete antes da data.
pub async fn generate(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
) -> AppResult<Json<GenerateResponse>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;
    let hoje = chrono::Local::now().date_naive();
    let limite = hoje + chrono::Duration::days(DIAS_ANTES as i64);

    // Um único INSERT para os dois tipos: CASE decide pelo sinal de
    // (due_date - hoje). Assim um boleto não entra nos dois ramos.
    let criadas = sqlx::query(
        "INSERT INTO notifications (wallet_id, type, title, body, reference_type, reference_id)
         SELECT b.wallet_id,
                CASE WHEN b.due_date < $2 THEN 'BILL_OVERDUE' ELSE 'BILL_DUE' END,
                CASE WHEN b.due_date < $2 THEN 'Conta vencida' ELSE 'Conta a vencer' END,
                b.description || ' — ' || to_char(b.due_date, 'DD/MM/YYYY')
                    || ' — ' || to_char(b.amount, 'FM999999990.00'),
                'bill', b.id
           FROM bills b
          WHERE b.wallet_id = $1
            AND b.status = 'PENDING'
            AND b.due_date <= $3
         ON CONFLICT (wallet_id, type, reference_type, reference_id) DO NOTHING",
    )
    .bind(wallet)
    .bind(hoje)
    .bind(limite)
    .execute(&state.db)
    .await?
    .rows_affected();

    Ok(Json(GenerateResponse {
        criadas: criadas as u64,
        unread: contar_nao_lidas(&state, wallet).await?,
    }))
}

#[derive(Serialize)]
pub struct GenerateResponse {
    /// Quantas foram criadas agora (as que já existiam não contam).
    pub criadas: u64,
    pub unread: u64,
}

/// GET /notifications
pub async fn list(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Query(q): Query<NotificationQuery>,
) -> AppResult<Json<Vec<NotificationResponse>>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    let linhas = sqlx::query_as::<_, (
        Uuid,
        String,
        String,
        Option<String>,
        String,
        Uuid,
        Option<chrono::DateTime<chrono::Utc>>,
        chrono::DateTime<chrono::Utc>,
    )>(
        "SELECT id, type, title, body, reference_type, reference_id, read_at, created_at
           FROM notifications
          WHERE wallet_id = $1
            AND ($2::bool IS NULL OR (read_at IS NULL) = $2)
          ORDER BY created_at DESC
          LIMIT $3 OFFSET $4",
    )
    .bind(wallet)
    .bind(q.unread_only)
    .bind(q.limit.unwrap_or(50).clamp(1, 200))
    .bind(q.offset.unwrap_or(0).max(0))
    .fetch_all(&state.db)
    .await?;

    Ok(Json(linhas.into_iter().map(convert).collect()))
}

/// POST /notifications/{id}/read
pub async fn mark_read(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
) -> AppResult<Json<NotificationResponse>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    sqlx::query(
        "UPDATE notifications SET read_at = now()
          WHERE id = $1 AND wallet_id = $2 AND read_at IS NULL",
    )
    .bind(id)
    .bind(wallet)
    .execute(&state.db)
    .await?;

    let linha = sqlx::query_as::<_, (
        Uuid,
        String,
        String,
        Option<String>,
        String,
        Uuid,
        Option<chrono::DateTime<chrono::Utc>>,
        chrono::DateTime<chrono::Utc>,
    )>(
        "SELECT id, type, title, body, reference_type, reference_id, read_at, created_at
           FROM notifications WHERE id = $1 AND wallet_id = $2",
    )
    .bind(id)
    .bind(wallet)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NotFound("notificação"))?;

    Ok(Json(convert(linha)))
}

/// POST /notifications/read-all
pub async fn mark_all_read(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
) -> AppResult<Json<GenerateResponse>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    let revogados = sqlx::query(
        "UPDATE notifications SET read_at = now()
          WHERE wallet_id = $1 AND read_at IS NULL",
    )
    .bind(wallet)
    .execute(&state.db)
    .await?
    .rows_affected();

    tracing::debug!(revogados, "notificações marcadas como lidas");

    Ok(Json(GenerateResponse {
        criadas: 0,
        unread: 0,
    }))
}

async fn contar_nao_lidas(state: &AppState, wallet: Uuid) -> AppResult<u64> {
    Ok(sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(*) FROM notifications WHERE wallet_id = $1 AND read_at IS NULL",
    )
    .bind(wallet)
    .fetch_one(&state.db)
    .await? as u64)
}

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

type Row = (
    Uuid,
    String,
    String,
    Option<String>,
    String,
    Uuid,
    Option<chrono::DateTime<chrono::Utc>>,
    chrono::DateTime<chrono::Utc>,
);

fn convert(r: Row) -> NotificationResponse {
    NotificationResponse {
        id: r.0,
        r#type: r.1,
        title: r.2,
        body: r.3,
        reference_type: r.4,
        reference_id: r.5,
        read_at: r.6,
        created_at: r.7,
    }
}