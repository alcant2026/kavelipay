//! Estado compartilhado da aplicação, passado ao axum.

use sqlx::PgPool;
use uuid::Uuid;

use crate::ai::AiClient;
use crate::auth::TokenService;
use crate::cotacao::CotacaoClient;
use crate::error::{AppError, AppResult};
use crate::rate_limit::LoginGate;

#[derive(Clone)]
pub struct AppState {
    pub db: PgPool,
    pub tokens: TokenService,
    /// Cliente da IA para prefill de ativos. Uma estrutura só: o
    /// `reqwest::Client` dentro segura o pool de conexões TLS — construir
    /// um por consulta abriria handshake novo toda vez que a pessoa
    /// digitasse um ticker.
    pub ai: AiClient,
    /// Cliente das fontes de cotação (brapi/Yahoo) — sugestão de preço e
    /// data de dividendo. Mesmo motivo do `ai`: um pool, não um por tela.
    pub cotacao: CotacaoClient,
    /// Token do cadastro de ofertas da vitrine. `None` = rota de admin
    /// desligada (503). Fica no estado, e não numa env lida no handler, para
    /// o processo morrer no boot se o token for fraco — e para os testes
    /// conseguirem ligar a rota sem mexer no ambiente do processo.
    pub admin_token: Option<String>,
    /// Trava de tentativas de login (brute force). Em memória: MVP de uma
    /// instância só; se um dia houver mais de um processo, migra para tabela.
    pub login_gate: LoginGate,
    /// Envio de e-mail. `None` = SMTP não configurado — recuperação de senha
    /// responde 503 (o resto do app não depende de e-mail).
    pub mailer: Option<crate::mail::Mailer>,
    /// Freio de pedidos de redefinição de senha. Separado do `login_gate`
    /// porque a semântica é outra: aqui não existe "login deu certo", só
    /// quantos envios o IP já pediu na última hora.
    pub reset_gate: crate::rate_limit::Cooldown,
}

/// Wallet do usuário, exigindo ACTIVE: wallet bloqueada não se move. Ponto
/// único da fronteira — sete módulos idênticos viraram este.
pub async fn wallet_ativa(state: &AppState, user_id: Uuid) -> AppResult<Uuid> {
    let row = sqlx::query_as::<_, (Uuid, String)>(
        "SELECT id, status FROM wallets WHERE user_id = $1",
    )
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