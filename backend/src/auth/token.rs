//! Access token (JWT HS256) e refresh token (opaco, guardado como hash).
//!
//! Por que dois tipos: o access token é autocontido e curto, o refresh é longo
//! e revogável. Se o access fosse longo, revogar sessão exigiria esperar a
//! expiração. O refresh nunca é JWT — precisa poder ser apagado do banco.

use chrono::{DateTime, Duration, Utc};
use jsonwebtoken::{Algorithm, DecodingKey, EncodingKey, Header, Validation, decode, encode};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::{AppError, AppResult};

/// Claims do access token. Deliberadamente mínimo: só o que o servidor precisa
/// para autorizar. Papel/permissão aqui exigiria reemitir token a cada mudança
/// de privilégio, e o usuário do MVP é pessoa física sem papéis.
#[derive(Debug, Serialize, Deserialize)]
pub struct Claims {
    /// `sub` = user_id. É a ÚNICA fonte de identidade: nunca vem do body.
    pub sub: Uuid,
    pub exp: i64,
    pub iat: i64,
}

#[derive(Clone)]
pub struct TokenService {
    encoding: EncodingKey,
    decoding: DecodingKey,
    access_ttl: Duration,
    refresh_ttl: Duration,
}

impl TokenService {
    pub fn new(secret: &str, access_ttl_minutes: i64, refresh_ttl_days: i64) -> Self {
        Self {
            encoding: EncodingKey::from_secret(secret.as_bytes()),
            decoding: DecodingKey::from_secret(secret.as_bytes()),
            access_ttl: Duration::minutes(access_ttl_minutes),
            refresh_ttl: Duration::days(refresh_ttl_days),
        }
    }

    /// Emite o access token JWT e o par (refresh cru, hash do refresh).
    pub fn issue(&self, user_id: Uuid) -> AppResult<(String, String, String)> {
        let now = Utc::now();
        let claims = Claims {
            sub: user_id,
            iat: now.timestamp(),
            exp: (now + self.access_ttl).timestamp(),
        };

        let access = encode(&Header::new(Algorithm::HS256), &claims, &self.encoding)
            .map_err(|e| AppError::Internal(anyhow::anyhow!("falha ao assinar token: {e}")))?;

        // Refresh opaco: 32 bytes aleatórios em base64url. Entropia suficiente
        // para não ser adivinhável e curto o bastante para ir no header.
        let raw = random_token();
        let hash = hash_refresh(&raw);

        Ok((access, raw, hash))
    }

    pub fn refresh_expiry(&self) -> DateTime<Utc> {
        Utc::now() + self.refresh_ttl
    }

    /// Valida assinatura e expiração. Qualquer falha vira `Unauthorized` — o
    /// cliente não precisa (e não deve) saber se o token expirou ou foi forjado.
    pub fn verify_access(&self, token: &str) -> AppResult<Uuid> {
        let mut validation = Validation::new(Algorithm::HS256);
        validation.validate_exp = true;
        // Rejeita token de algoritmo diferente (algoritmo-confusion attack):
        // fixar HS256 impede o clássico "RS256 -> HS256 com a public key".
        validation.algorithms = vec![Algorithm::HS256];

        match decode::<Claims>(token, &self.decoding, &validation) {
            Ok(data) => Ok(data.claims.sub),
            Err(_) => Err(AppError::Unauthorized),
        }
    }
}

/// 32 bytes aleatórios em base64url.
fn random_token() -> String {
    use base64::Engine;

    let bytes: [u8; 32] = rand::random();
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

/// SHA-256 do refresh token: o que vai para o banco. O token cru só existe
/// no cliente — vazamento do banco não permite forjar sessão.
pub fn hash_refresh(raw: &str) -> String {
    use sha2::{Digest, Sha256};

    let mut hasher = Sha256::new();
    hasher.update(raw.as_bytes());
    format!("{:x}", hasher.finalize())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn service() -> TokenService {
        TokenService::new(&"x".repeat(32), 15, 30)
    }

    #[test]
    fn access_token_circulariza_o_user_id() {
        let id = Uuid::new_v4();
        let svc = service();
        let (token, _, _) = svc.issue(id).unwrap();

        assert_eq!(svc.verify_access(&token).unwrap(), id);
    }

    #[test]
    fn refresh_tokens_sao_aleatorios() {
        let svc = service();
        let (_, a, hash_a) = svc.issue(Uuid::new_v4()).unwrap();
        let (_, b, hash_b) = svc.issue(Uuid::new_v4()).unwrap();

        assert_ne!(a, b, "dois refresh tokens não podem coincidir");
        assert_ne!(hash_a, hash_b);
    }

    #[test]
    fn token_assinado_com_outro_segredo_e_rejeitado() {
        let id = Uuid::new_v4();
        let (token, _, _) = TokenService::new(&"a".repeat(32), 15, 30)
            .issue(id)
            .unwrap();

        assert!(service().verify_access(&token).is_err());
    }

    #[test]
    fn token_adulterado_e_rejeitado() {
        let svc = service();
        let (mut token, _, _) = svc.issue(Uuid::new_v4()).unwrap();
        token.push('x'); // quebra a assinatura

        assert!(svc.verify_access(&token).is_err());
    }

    #[test]
    fn token_lixo_e_rejeitado_sem_panicar() {
        assert!(service().verify_access("").is_err());
        assert!(service().verify_access("abc.def.ghi").is_err());
        assert!(service().verify_access("não-é-jwt").is_err());
    }

    #[test]
    fn hash_de_refresh_e_deterministico() {
        // O mesmo token precisa gerar o mesmo hash para o lookup no banco funcionar.
        assert_eq!(hash_refresh("abc"), hash_refresh("abc"));
        assert_ne!(hash_refresh("abc"), hash_refresh("abd"));
    }
}