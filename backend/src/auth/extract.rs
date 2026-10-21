use axum::extract::FromRequestParts;
use axum::http::request::Parts;

use uuid::Uuid;

use crate::error::{AppError, AppResult};
use crate::state::AppState;

/// Identidade extraída do access token — o `sub` do JWT e o direito premium
/// congelado na emissão.
///
/// Este é o ÚNICO jeito de saber de quem é a requisição. Handlers NUNCA
/// aceitam `user_id` do body ou da query string: isso permitiria um cliente
/// pedir a carteira de outra pessoa só trocando um id.
#[derive(Debug, Clone, Copy)]
pub struct AuthUser {
    pub user_id: Uuid,
    /// Assinatura vigente no momento em que o token foi emitido. Evita uma
    /// consulta ao banco em cada requisição paga; o refresh renova o retrato
    /// a cada 15 minutos.
    pub premium: bool,
}

impl AuthUser {
    pub fn user_id(&self) -> Uuid {
        self.user_id
    }

    pub fn premium(&self) -> bool {
        self.premium
    }
}

impl FromRequestParts<AppState> for AuthUser {
    type Rejection = AppError;

    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> AppResult<Self> {
        let header = parts
            .headers
            .get(axum::http::header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .ok_or(AppError::Unauthorized)?;

        // Exatamente "Bearer <token>". Sem fallback para query param: token em
        // query string vaza em log de servidor e histórico.
        let token = header
            .strip_prefix("Bearer ")
            .ok_or(AppError::Unauthorized)?
            .trim();

        let claims = state.tokens.verify_access(token)?;
        Ok(AuthUser {
            user_id: claims.sub,
            premium: claims.premium,
        })
    }
}