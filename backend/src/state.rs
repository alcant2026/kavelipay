//! Estado compartilhado da aplicação, passado ao axum.

use sqlx::PgPool;
use uuid::Uuid;

use crate::auth::TokenService;
use crate::error::{AppError, AppResult};
use crate::rate_limit::LoginGate;

#[derive(Clone)]
pub struct AppState {
    pub db: PgPool,
    pub tokens: TokenService,
    /// Cliente do Mercado Pago (Checkout Pro). Um pool TLS só, não um por
    /// cobrança.
    pub mp: crate::mercadopago::MercadoPago,
    /// OAuth do Mercado Pago: id/segredo da aplicação e a chave que cifra os
    /// tokens dos usuários. `None` = ninguém pode conectar a conta.
    pub mp_oauth: Option<crate::routes::mp_conta::OAuth>,
    /// Base pública do backend, sem barra no fim. É o que vira link de pagamento
    /// para quem não tem o app (`{base}/cobranca/{token}/pagar`).
    pub public_base_url: String,
    /// Token do admin (emissão de código de assinatura). `None` = rota de
    /// admin desligada (503). Fica no estado, e não numa env lida no handler,
    /// para o processo morrer no boot se o token for fraco — e para os testes
    /// conseguirem ligar a rota sem mexer no ambiente do processo.
    pub admin_token: Option<String>,
    /// Trava de tentativas de login (brute force). Em memória: MVP de uma
    /// instância só; se um dia houver mais de um processo, migra para tabela.
    pub login_gate: LoginGate,
    /// Trava de tentativas de convite de caixinha, por usuário autenticado.
    /// Separado do `login_gate`: adivinhar código não é tentar senha, e a
    /// chave aqui é o `user_id`, não o e-mail. Mesma classe, contadores
    /// independentes; em memória como o resto (uma instância no MVP).
    pub convite_gate: LoginGate,
    /// Envio de e-mail. `None` = SMTP não configurado — recuperação de senha
    /// responde 503 (o resto do app não depende de e-mail).
    pub mailer: Option<crate::mail::Mailer>,
    /// Freio de pedidos de redefinição de senha. Separado do `login_gate`
    /// porque a semântica é outra: aqui não existe "login deu certo", só
    /// quantos envios o IP já pediu na última hora.
    pub reset_gate: crate::rate_limit::Cooldown,
    /// Teto global de requisições por IP. Existe para a instância free (0,1
    /// vCPU) não cair inteira por causa de um script — o `login_gate` protege
    /// uma conta, este protege o processo.
    pub limite_global: crate::rate_limit::LimiteGlobal,
    /// Cliente de IA do relatório mensal. Sem chave, a rota devolve 503 e o
    /// relatório sai só com os números do ledger.
    pub ai: crate::ai::AiClient,
    /// Teto de gerações de relatório por usuário/hora: cada uma custa token
    /// de terceiro.
    pub relatorio_gate: crate::rate_limit::LimiteUsuario,
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