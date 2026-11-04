//! KaveliPay backend — crate root.
//!
//! O saldo NUNCA é persistido. Todo saldo é derivado do ledger:
//! `wallet -> ledger_account -> ledger_transaction -> ledger_entry`.

pub mod ai;
pub mod auth;
pub mod caixinha_modelo;
pub mod categories;
pub mod cotacao;
pub mod config;
pub mod error;
pub mod http_cru;
pub mod ledger;
pub mod mail;
pub mod money;
pub mod mercadopago;
pub mod payment;
pub mod rate_limit;
pub mod routes;
pub mod state;

pub use axum::http::StatusCode;
pub use error::{AppError, AppResult};
pub use money::Money;
pub use state::AppState;