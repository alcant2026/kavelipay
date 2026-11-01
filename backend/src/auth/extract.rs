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

/// IP do cliente, quando existir. Com `into_make_service_with_connect_info` o
/// axum injeta o endereço; requests fabricados (testes) ficam com `None`. Este
/// extractor NUNCA falha — por isso não é `Option<ConnectInfo>`, que não é
/// extractor válido no axum 0.8.
///
/// **Ordem de preferência**: `X-Forwarded-For` antes do socket. Em produção o
/// Render termina o TLS e repassa do próprio proxy, então o socket é sempre o
/// IP do balanceador — limitar por ele travaria o mundo inteiro numa cota só.
/// Usa a última entrada do header (a do hop mais próximo, que é o proxy e não
/// o valor que o cliente digitou), caindo para o socket quando não há header.
pub struct ClientIp(pub Option<std::net::IpAddr>);

impl FromRequestParts<AppState> for ClientIp {
    type Rejection = std::convert::Infallible;

    async fn from_request_parts(
        parts: &mut Parts,
        _state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let do_proxy = parts
            .headers
            .get("x-forwarded-for")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.rsplit(',').next())
            .and_then(|v| v.trim().parse().ok());
        let do_socket = parts
            .extensions
            .get::<axum::extract::ConnectInfo<std::net::SocketAddr>>()
            .map(|c| c.0.ip());
        Ok(ClientIp(do_proxy.or(do_socket)))
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