//! Hash de senha com argon2id.
//!
//! argon2id é o único modo correto da família: argon2i é vulnerável a
//! side-channel e argon2d a ataque de tempo em GPU. A senha em claro nunca
//! sai daqui — só o hash PHC vai para o banco.

use argon2::password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString, rand_core::OsRng};
use argon2::{Algorithm, Argon2, Params, Version};

use crate::error::{AppError, AppResult};

/// argon2id com 19 MiB de memória, 2 passes e 1 lane. Custo estável e
/// documentado: mudar sem versionar o hash quebraria verificação de senhas
/// antigas, então este perfil é fixo.
fn argon2id() -> Result<Argon2<'static>, AppError> {
    let params = Params::new(19 * 1024, 2, 1, None)
        .map_err(|e| AppError::Internal(anyhow::anyhow!("parâmetros argon2 inválidos: {e}")))?;

    Ok(Argon2::new(Algorithm::Argon2id, Version::V0x13, params))
}

/// Gera o hash PHC de uma senha.
pub fn hash(senha: &str) -> AppResult<String> {
    let salt = SaltString::generate(&mut OsRng);
    argon2id()?
        .hash_password(senha.as_bytes(), &salt)
        .map(|h| h.to_string())
        .map_err(|e| AppError::Internal(anyhow::anyhow!("falha ao gerar hash: {e}")))
}

/// Confere a senha contra o hash guardado. Timing-safe por construção: um
/// hash malformado retorna `false`, não erro, para não distinguir "usuário não
/// existe" de "hash corrompido".
pub fn verify(senha: &str, hash_php: &str) -> bool {
    match PasswordHash::new(hash_php) {
        Ok(parsed) => argon2id()
            .map(|a| {
                a.verify_password(senha.as_bytes(), &parsed)
                    .is_ok()
            })
            .unwrap_or(false),
        Err(_) => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn senha_correta_verifica() {
        let hash = hash("minha-senha-forte").unwrap();
        assert!(verify("minha-senha-forte", &hash));
    }

    #[test]
    fn senha_errada_nao_verifica() {
        let hash = hash("minha-senha-forte").unwrap();
        assert!(!verify("outra-senha", &hash));
    }

    #[test]
    fn hash_da_nao_revela_a_senha() {
        let hash = hash("minha-senha-forte").unwrap();
        assert!(!hash.contains("minha-senha-forte"));
        assert!(hash.starts_with("$argon2id$"));
    }

    #[test]
    fn dois_hashes_da_mesma_senha_sao_diferentes() {
        // Salts distintos: sem isso o banco revelaria quem usa a mesma senha.
        assert_ne!(hash("igual").unwrap(), hash("igual").unwrap());
    }

    #[test]
    fn hash_malformado_retorna_false_sem_panicar() {
        assert!(!verify("qualquer", "não-é-um-hash"));
        assert!(!verify("qualquer", ""));
    }
}