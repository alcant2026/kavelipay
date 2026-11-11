//! Rotas HTTP. Cada módulo registra seu prefixo; `router()` é a montagem única.

pub mod auth;
pub mod bills;
pub mod billing;
pub mod caixinhas;
pub mod categories;
pub mod charges;
pub mod derivation;
pub mod health;
pub mod history;
pub mod lgpd;
pub mod mp_conta;
pub mod notifications;
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
        // conta do Mercado Pago de cada usuário (o dinheiro cai direto nela)
        .route("/mercadopago/conectar", get(mp_conta::conectar))
        .route("/mercadopago/callback", get(mp_conta::callback))
        .route("/mercadopago/link", get(mp_conta::link))
        .route("/mercadopago/status", get(mp_conta::status))
        .route("/mercadopago/conta", delete(mp_conta::desconectar))
        // apresentação do produto (o Mercado Pago exige uma URL de site)
        .route("/", get(billing::raiz))
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
        .route("/reports/monthly/relatorio", get(reports::relatorio_html))
        // recorrência
        .route("/bills/generate-recurring", post(recurrence::generate))
        // caixinhas — empréstimo registrado entre conhecidos, FORA do ledger
        // (nenhum handler chama ledger::postar*: a carteira não muda).
        // Sem vitrine pública: captação pública com rendimento é CIC/CVM.
        // Ordem importa (como investments): o literal /entrar vem ANTES de
        // /caixinhas/{id}, senão casaria como UUID (400 em vez da rota certa).
        .route("/caixinhas", post(caixinhas::criar))
        .route("/caixinhas", get(caixinhas::minhas))
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
        .route(
            "/caixinhas/{id}/meu-telefone",
            patch(caixinhas::meu_telefone),
        )
        // assinatura premium: o app paga as próprias contas sem virar banco.
        // Leitura e ativação são do usuário; emissão de código é do admin.
        .route("/billing/plano", get(billing::plano))
        .route("/billing/ativar", post(billing::ativar))
        .route("/billing/checkout", post(billing::criar_checkout))
        // Tela de pagamento do premium: pública, o token é o segredo.
        .route("/assinar/{token}", get(billing::pagina_assinatura))
        .route("/assinar/{token}/pagar", post(billing::pagar_assinatura))
        .route("/admin/assinaturas/codigos", post(billing::gerar_codigo))
        // LGPD
        .route("/lgpd/export.csv", get(lgpd::export_csv))
        .route("/lgpd/account", delete(lgpd::delete_account))
        .with_state(state)
}