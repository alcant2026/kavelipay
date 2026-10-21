//! Porta do administrador: um header `X-Admin-Token` guarda as rotas que
//! mudam conteúdo global (vitrine, códigos de assinatura).
//!
//! Duas falhas, de propósito:
//!
//! - Token ausente na configuração = rota desligada (**503**). Um servidor sem
//!   `ADMIN_TOKEN` não deve ter admin aberto por acidente; melhor recusar tudo
//!   até alguém configurar.
//! - Token errado = **401**.
//!
//! A comparação é em tempo constante: comparar com `==` vaza, pelo tempo de
//! resposta, quantos caracteres do começo estão certos.

use axum::http::HeaderMap;

use crate::error::{AppError, AppResult};
use crate::state::AppState;

pub fn exigir_admin(state: &AppState, headers: &HeaderMap) -> AppResult<()> {
    let esperado = state.admin_token.as_deref().ok_or(AppError::Unavailable)?;

    let recebido = headers
        .get("x-admin-token")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");

    if !tempo_constante_igual(recebido.as_bytes(), esperado.as_bytes()) {
        return Err(AppError::Unauthorized);
    }
    Ok(())
}

pub(crate) fn tempo_constante_igual(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    a.iter()
        .zip(b)
        .fold(0u8, |acc, (x, y)| acc | (x ^ y))
        == 0
}

#[cfg(test)]
mod tests {
    use super::tempo_constante_igual;

    #[test]
    fn tokens_iguais_passam() {
        assert!(tempo_constante_igual(b"abc123", b"abc123"));
    }

    #[test]
    fn tamanho_diferente_falha() {
        assert!(!tempo_constante_igual(b"abc", b"abcd"));
    }

    #[test]
    fn um_byte_diferente_falha() {
        assert!(!tempo_constante_igual(b"abc123", b"abc124"));
    }
}
