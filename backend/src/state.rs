//! Estado compartilhado da aplicação, passado ao axum.

use sqlx::PgPool;

use crate::ai::AiClient;
use crate::auth::TokenService;
use crate::cotacao::CotacaoClient;

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
}