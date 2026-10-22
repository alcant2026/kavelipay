//! Health checks para o Render.
//!
//! `/health` é liveness: responde se o processo está de pé. O Render usa este
//! endpoint para decidir se a instância pode receber tráfego e para reiniciar
//! processo travado. **Não consulta o banco de propósito**: o Render bate no
//! health check com frequência, e um `SELECT 1` a cada batida manteria o Neon
//! free acordado 24/7 — estourando os 100 CU-hours do plano gratuito. O Neon
//! deve poder suspender depois de 5 minutos sem uso.
//!
//! `/health/db` é readiness: faz `SELECT 1` e serve para diagnóstico manual ou
//! para um monitor externo que aceite acordar o banco. Não use como health
//! check do Render.
//!
//! Nenhum dos dois exige autenticação: health check de plataforma não carrega
//! token. As respostas não expõem schema, versão nem erro do banco.

use axum::Json;
use axum::extract::State;
use axum::http::StatusCode;
use serde_json::{Value, json};

use crate::state::AppState;

pub async fn health() -> Json<Value> {
    Json(json!({ "status": "ok" }))
}

pub async fn health_db(State(state): State<AppState>) -> (StatusCode, Json<Value>) {
    match sqlx::query_scalar::<_, i32>("SELECT 1")
        .fetch_one(&state.db)
        .await
    {
        Ok(_) => (
            StatusCode::OK,
            Json(json!({ "status": "ok", "db": "ok" })),
        ),
        Err(erro) => {
            // O detalhe fica no log do Render; o corpo público não vaza
            // host, usuário nem mensagem do Postgres.
            tracing::error!(error = %erro, "health check do banco falhou");
            (
                StatusCode::SERVICE_UNAVAILABLE,
                Json(json!({ "status": "unavailable", "db": "error" })),
            )
        }
    }
}
