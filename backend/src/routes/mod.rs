//! Rotas HTTP. Cada módulo registra seu prefixo; `router()` é a montagem única.

pub mod auth;
pub mod bills;
pub mod billing;
pub mod caixinhas;
pub mod categories;
pub mod charges;
pub mod debts;
pub mod derivation;
pub mod health;
pub mod history;
pub mod investments;
pub mod lgpd;
pub mod notifications;
pub mod parceiros;
pub mod profile;
pub mod recurrence;
pub mod reports;
pub mod splits;
pub mod wallet;

use axum::extract::State;
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, patch, post};
use axum::{extract::Request, Router};

use crate::error::AppError;
use crate::state::AppState;

/// 429 para quem passa do teto por minuto (ver `rate_limit::LimiteGlobal`).
///
/// Fica como camada, não dentro do handler: assim nenhuma rota nova nasce sem
/// o freio. O IP vem de `X-Forwarded-For` quando existe (produção está atrás
/// do proxy do Render), senão do socket.
pub async fn limite_global(
    State(state): State<AppState>,
    ip: crate::auth::ClientIp,
    req: Request,
    next: Next,
) -> Response {
    let ip = ip.0.unwrap_or(std::net::Ipv4Addr::UNSPECIFIED.into());
    if !state.limite_global.permite(ip) {
        return AppError::TooManyRequests(
            "muitos pedidos em pouco tempo; aguarde um minuto".to_string(),
        )
        .into_response();
    }
    next.run(req).await
}

pub fn router(state: AppState) -> Router {
    Router::new()
        // liveness do Render (sem banco); readiness manual em /health/db
        .route("/health", get(health::health))
        .route("/health/db", get(health::health_db))
        // auth
        .route("/auth/register", post(auth::register))
        .route("/auth/login", post(auth::login))
        .route("/auth/refresh", post(auth::refresh))
        .route("/auth/logout", post(auth::logout))
        .route("/auth/me", get(auth::me))
        // recuperação de senha: as duas rotas são PÚBLICAS por definição —
        // quem esqueceu a senha não tem sessão. O segredo é o código enviado
        // por e-mail, não o login.
        .route("/auth/forgot-password", post(auth::forgot_password))
        .route("/auth/reset-password", post(auth::reset_password))
        // perfil e segurança
        .route("/profile", patch(profile::update))
        .route("/profile/password", post(profile::change_password))
        .route("/profile/sessions", get(profile::list_sessions))
        .route(
            "/profile/sessions/revoke-all",
            post(profile::revoke_all_sessions),
        )
        .route("/profile/sessions/{id}", delete(profile::revoke_session))
        // carteira e ledger
        .route("/wallet", get(wallet::get_wallet))
        .route("/wallet/transactions", post(wallet::create_transaction))
        .route("/wallet/transactions", get(wallet::list_transactions))
        .route(
            "/wallet/transactions/{id}/category",
            patch(wallet::classify_transaction),
        )
        // contas a pagar
        .route("/bills", post(bills::create_bill))
        .route("/bills", get(bills::list_bills))
        .route("/bills/{id}/pay", post(bills::mark_paid))
        .route("/bills/{id}", delete(bills::cancel_bill))
        // divisão de despesa
        .route("/splits", post(splits::create_split))
        .route("/splits", get(splits::list_splits))
        .route("/splits/{id}/parts/{part_id}/settle", post(splits::settle_part))
        // dívidas ("eu devo") — o lado inverso das divisões
        .route("/debts", post(debts::create_debt))
        .route("/debts", get(debts::list_debts))
        .route("/debts/{id}/pay", post(debts::pay_debt))
        .route("/debts/{id}/confirm", post(debts::confirm_debt))
        .route("/debts/{id}", patch(debts::update_debt))
        .route("/debts/{id}", delete(debts::delete_debt))
        // cobranças
        .route("/charges", post(charges::create_charge))
        .route("/charges", get(charges::list_charges))
        .route("/charges/{id}/qr", get(charges::charge_qr))
        .route("/charges/{id}/receive", post(charges::receive_charge))
        .route("/charges/{id}/pagamento", post(charges::link_pagamento))
        .route("/charges/{id}", delete(charges::cancel_charge))
        // webhook do Mercado Pago: público por definição (quem chama é o MP)
        .route("/webhooks/mercadopago", post(charges::webhook))
        // cobrança pública: o que o QR do app aponta. Sem sessão de propósito —
        // quem recebe a cobrança pode não ter conta (ou estar deslogado). O
        // segredo é o token do link, não o login.
        .route("/cobranca/{token}", get(charges::cobranca_publica))
        .route("/cobranca/{token}/pagar", get(charges::pagina_pagamento))
        .route("/cobranca/{token}/pagar", post(charges::pagar_pela_pagina))
        .route("/cobranca/parte/{token}/pagar", get(splits::pagina_parte))
        .route("/cobranca/parte/{token}/pagar", post(splits::pagar_pela_parte))
        // notificações
        .route("/notifications", get(notifications::list))
        .route("/notifications/generate", post(notifications::generate))
        .route("/notifications/read-all", post(notifications::mark_all_read))
        .route("/notifications/{id}/read", post(notifications::mark_read))
        // histórico
        .route("/history", get(history::list))
        // saldo demonstrativo e a prova de que ele é real
        .route("/wallet/derivation", get(derivation::derivation))
        // categorias
        .route("/categories", post(categories::create))
        .route("/categories", get(categories::list))
        .route("/categories/reorder", post(categories::reorder))
        .route("/categories/{id}", patch(categories::update))
        .route("/categories/{id}", delete(categories::delete))
        // relatórios
        .route("/reports/monthly", get(reports::monthly))
        // recorrência
        .route("/bills/generate-recurring", post(recurrence::generate))
        // investimentos
        // Ordem importa: `/investments/summary` antes de `/investments/{id}`,
        // senão o axum casa "summary" como se fosse um UUID e devolve 400.
        .route("/investments", post(investments::create_investment))
        .route("/investments", get(investments::list_investments))
        .route("/investments/summary", get(investments::summary))
        // Lookup por IA para prefill do formulário. ANTES de `/investments/{id}`
        // pelo mesmo motivo do summary: "lookup" casaria como UUID e devolveria
        // 400 de parse em vez de 404 de rota.
        .route("/investments/lookup", post(investments::lookup))
        // Sugestão de preço (brapi/Yahoo). Mesma regra de ordem: "quote"
        // casaria como UUID se viesse depois de `/investments/{id}`.
        .route("/investments/quote", post(investments::quote))
        .route("/investments/{id}", get(investments::get_investment))
        .route("/investments/{id}", patch(investments::update_investment))
        .route(
            "/investments/{id}/movements",
            post(investments::create_movement),
        )
        .route(
            "/investments/{id}/movements",
            get(investments::list_movements),
        )
        // caixinhas — empréstimo registrado entre conhecidos, FORA do ledger
        // (nenhum handler chama ledger::postar*: a carteira não muda).
        // Ordem importa (como investments): os literais /publicas e /entrar
        // vêm ANTES de /caixinhas/{id}, senão casariam como UUID (400 de
        // parse em vez da rota certa).
        .route("/caixinhas", post(caixinhas::criar))
        .route("/caixinhas", get(caixinhas::minhas))
        .route("/caixinhas/publicas", get(caixinhas::publicas))
        .route("/caixinhas/entrar", post(caixinhas::entrar))
        .route("/caixinhas/{id}", get(caixinhas::detalhe))
        .route("/caixinhas/{id}/aportes", post(caixinhas::propor_aporte))
        .route(
            "/caixinhas/{id}/rendimentos",
            post(caixinhas::propor_rendimento),
        )
        .route(
            "/caixinhas/{id}/devolucoes",
            post(caixinhas::propor_devolucao),
        )
        .route(
            "/caixinhas/{id}/movimentos/{mov_id}/confirmar",
            post(caixinhas::confirmar_movimento),
        )
        .route(
            "/caixinhas/{id}/movimentos/{mov_id}/recusar",
            post(caixinhas::recusar_movimento),
        )
        .route(
            "/caixinhas/{id}/movimentos/{mov_id}/cancelar",
            post(caixinhas::cancelar_movimento),
        )
        .route("/caixinhas/{id}/avaliacao", post(caixinhas::avaliar))
        .route(
            "/caixinhas/{id}/meu-telefone",
            patch(caixinhas::meu_telefone),
        )
        .route(
            "/caixinhas/{id}/publica",
            patch(caixinhas::definir_publica),
        )
        // vitrine de lojas parceiras (indicação de afiliado): usuário só lê;
        // cadastro é /admin com X-Admin-Token (503 se ADMIN_TOKEN não existe)
        .route("/parceiros", get(parceiros::list))
        .route("/admin/parceiros", post(parceiros::criar))
        .route("/admin/parceiros/{id}", delete(parceiros::desativar))
        // assinatura premium: o app paga as próprias contas sem virar banco.
        // Leitura e ativação são do usuário; emissão de código é do admin.
        .route("/billing/plano", get(billing::plano))
        .route("/billing/ativar", post(billing::ativar))
        .route("/admin/assinaturas/codigos", post(billing::gerar_codigo))
        // LGPD
        .route("/lgpd/export.csv", get(lgpd::export_csv))
        .route("/lgpd/account", delete(lgpd::delete_account))
        .with_state(state)
}