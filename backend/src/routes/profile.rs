//! Perfil e segurança. Objetivo 21 da spec.
//!
//! Tudo aqui respeita duas regras: trocar senha exige a senha atual, e revogar
//! sessão é sempre do próprio usuário (`user_id` do token, nunca da URL).

use axum::Json;
use axum::extract::{Path, State};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::{AppError, AppResult};
use crate::state::AppState;

#[derive(Deserialize)]
pub struct UpdateProfile {
    pub display_name: Option<String>,
}

#[derive(Serialize)]
pub struct ProfileResponse {
    pub user_id: Uuid,
    pub email: String,
    pub display_name: Option<String>,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

/// PATCH /profile
pub async fn update(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Json(req): Json<UpdateProfile>,
) -> AppResult<Json<ProfileResponse>> {
    // `None` explicitamente esvazia o nome; ausente não muda nada. Por isso o
    // `Option<Option<String>>` seria necessário para distinguir — aqui ausencia
    // significa "não mexe", que é o comportamento desejado no app.
    let nome = req.display_name.as_deref().map(str::trim);

    if let Some(n) = nome
        && n.len() > 120
    {
        return Err(AppError::Validation("nome muito longo (máx 120)".into()));
    }

    let row = sqlx::query_as::<_, (Uuid, String, Option<String>, chrono::DateTime<chrono::Utc>)>(
        "UPDATE users
            SET display_name = COALESCE($2, display_name), updated_at = now()
          WHERE id = $1
        RETURNING id, email, display_name, created_at",
    )
    .bind(auth.user_id())
    .bind(nome)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::Unauthorized)?;

    Ok(Json(ProfileResponse {
        user_id: row.0,
        email: row.1,
        display_name: row.2,
        created_at: row.3,
    }))
}

#[derive(Deserialize)]
pub struct ChangePassword {
    pub current_password: String,
    pub new_password: String,
}

#[derive(Serialize)]
pub struct PasswordChanged {
    /// Todas as sessões foram revogadas: trocar senha encerra acesso em
    /// qualquer dispositivo, inclusive o que está executando este request.
    pub sessions_revoked: u64,
}

/// POST /profile/password
///
/// Exige a senha atual: sem isso, um token vazado numa tela de bloqueio de
/// celular permitiria tomar a conta inteira. Revoga todas as sessões ao final —
/// é o que a pessoa espera ao trocar a senha por suspeita de vazamento.
pub async fn change_password(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Json(req): Json<ChangePassword>,
) -> AppResult<Json<PasswordChanged>> {
    let hash_atual = sqlx::query_scalar::<_, String>(
        "SELECT password_hash FROM users WHERE id = $1",
    )
    .bind(auth.user_id())
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::Unauthorized)?;

    if !crate::auth::verify(&req.current_password, &hash_atual) {
        return Err(AppError::Unauthorized);
    }

    // Reaproveita a mesma regra do cadastro — duas regras de senha divergindo é
    // como uma delas deixa de ser aplicada.
    crate::routes::auth::validar_senha(&req.new_password)?;

    let novo_hash = crate::auth::hash(&req.new_password)?;

    sqlx::query("UPDATE users SET password_hash = $2, updated_at = now() WHERE id = $1")
        .bind(auth.user_id())
        .bind(&novo_hash)
        .execute(&state.db)
        .await?;

    let revogadas = sqlx::query(
        "UPDATE refresh_tokens SET revoked_at = now()
          WHERE user_id = $1 AND revoked_at IS NULL",
    )
    .bind(auth.user_id())
    .execute(&state.db)
    .await?
    .rows_affected();

    tracing::info!(
        user_id = %auth.user_id(),
        revogadas,
        "senha alterada; sessões revogadas"
    );

    Ok(Json(PasswordChanged {
        sessions_revoked: revogadas,
    }))
}

#[derive(Serialize)]
pub struct SessionResponse {
    pub id: Uuid,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub expires_at: chrono::DateTime<chrono::Utc>,
}

/// GET /profile/sessions — dispositivos com sessão ativa.
pub async fn list_sessions(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
) -> AppResult<Json<Vec<SessionResponse>>> {
    let linhas = sqlx::query_as::<_, (
        Uuid,
        chrono::DateTime<chrono::Utc>,
        chrono::DateTime<chrono::Utc>,
    )>(
        "SELECT id, created_at, expires_at
           FROM refresh_tokens
          WHERE user_id = $1 AND revoked_at IS NULL AND expires_at > now()
          ORDER BY created_at DESC",
    )
    .bind(auth.user_id())
    .fetch_all(&state.db)
    .await?;

    Ok(Json(
        linhas
            .into_iter()
            .map(|(id, created_at, expires_at)| SessionResponse {
                id,
                created_at,
                expires_at,
            })
            .collect(),
    ))
}

/// DELETE /profile/sessions/{id}
pub async fn revoke_session(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
) -> AppResult<()> {
    // Filtra por user_id: revogar sessão de outra pessoa daria 404, e o id
    // adivinhado não faz nada.
    let rows = sqlx::query(
        "UPDATE refresh_tokens SET revoked_at = now()
          WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL",
    )
    .bind(id)
    .bind(auth.user_id())
    .execute(&state.db)
    .await?
    .rows_affected();

    if rows == 0 {
        return Err(AppError::NotFound("sessão"));
    }

    Ok(())
}

/// POST /profile/sessions/revoke-all
pub async fn revoke_all_sessions(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
) -> AppResult<Json<PasswordChanged>> {
    let revogadas = sqlx::query(
        "UPDATE refresh_tokens SET revoked_at = now()
          WHERE user_id = $1 AND revoked_at IS NULL",
    )
    .bind(auth.user_id())
    .execute(&state.db)
    .await?
    .rows_affected();

    Ok(Json(PasswordChanged {
        sessions_revoked: revogadas,
    }))
}