//! Rotas HTTP. Cada módulo registra seu prefixo; `router()` é a montagem única.

pub mod auth;
pub mod bills;
pub mod categories;
pub mod charges;
pub mod debts;
pub mod derivation;
pub mod history;
pub mod investments;
pub mod lgpd;
pub mod notifications;
pub mod profile;
pub mod recurrence;
pub mod reports;
pub mod splits;
pub mod wallet;

use axum::routing::{delete, get, patch, post};
use axum::Router;

use crate::state::AppState;

pub fn router(state: AppState) -> Router {
    Router::new()
        // auth
        .route("/auth/register", post(auth::register))
        .route("/auth/login", post(auth::login))
        .route("/auth/refresh", post(auth::refresh))
        .route("/auth/logout", post(auth::logout))
        .route("/auth/me", get(auth::me))
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
        // cobranças
        .route("/charges", post(charges::create_charge))
        .route("/charges", get(charges::list_charges))
        .route("/charges/{id}/qr", get(charges::charge_qr))
        .route("/charges/{id}/receive", post(charges::receive_charge))
        .route("/charges/{id}", delete(charges::cancel_charge))
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
        // LGPD
        .route("/lgpd/export", get(lgpd::export))
        .route("/lgpd/export.csv", get(lgpd::export_csv))
        .route("/lgpd/account", delete(lgpd::delete_account))
        .with_state(state)
}