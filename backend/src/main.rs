//! Binário do servidor. Falhar no boot é o comportamento correto: melhor
//! nenhum processo do que um processo com segredo padrão ou banco errado.

use kavelipay_backend::ai::AiClient;
use kavelipay_backend::config::Config;
use kavelipay_backend::state::AppState;
use kavelipay_backend::{AppError, auth, routes};
use sqlx::postgres::PgPoolOptions;
use std::time::Duration;
use tower_http::cors::CorsLayer;
use tower_http::trace::TraceLayer;

#[tokio::main]
async fn main() -> Result<(), AppError> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info".into()),
        )
        .init();

    let config = Config::from_env()?;

    // Neon fecha conexões ociosas e suspende o compute no free tier. Um pool
    // que guarda conexão velha por tempo demais falha no primeiro request
    // depois da pausa; timeouts curtos reciclam a conexão e o sqlx reconecta.
    // `sslmode=require` vai na própria DATABASE_URL do Neon.
    let db = PgPoolOptions::new()
        .max_connections(config.db_max_connections)
        .acquire_timeout(Duration::from_secs(5))
        .idle_timeout(Duration::from_secs(300))
        .max_lifetime(Duration::from_secs(1800))
        .connect(&config.database_url)
        .await?;

    // Aplica migrations no boot. Sem isto a aplicação sobe contra um schema
    // desatualizado e o primeiro request falha com erro de coluna — mais
    // difícil de diagnosticar do que uma falha de boot explícita.
    sqlx::migrate!("./migrations").run(&db).await?;

    let addr = config.bind_addr.clone();
    let ai = AiClient::new(
        config.openrouter_api_key.clone(),
        config.openrouter_model.clone(),
        config.openrouter_base_url.clone(),
    )?;

    if config.admin_token.is_none() {
        tracing::warn!(
            "ADMIN_TOKEN não definida: cadastro de ofertas da vitrine \
             desligado (POST /admin/parceiros devolve 503)"
        );
    }

    if config.openrouter_api_key.is_none() {
        tracing::warn!(
            "OPENROUTER_API_KEY não definida: prefill de ativos desligado \
             (POST /investments/lookup devolve 503)"
        );
    }

    let cotacao = kavelipay_backend::cotacao::CotacaoClient::new(
        config.brapi_base_url.clone(),
        config.yahoo_base_url.clone(),
        config.statusinvest_base_url.clone(),
    )?;

    let state = AppState {
        db,
        tokens: auth::TokenService::new(
            &config.jwt_secret,
            config.access_token_ttl_minutes,
            config.refresh_token_ttl_days,
        ),
        ai,
        cotacao,
        admin_token: config.admin_token.clone(),
    };

    let app = routes::router(state)
        .layer(TraceLayer::new_for_http())
        .layer(CorsLayer::permissive());

    let listener = tokio::net::TcpListener::bind(&addr).await?;
    tracing::info!(%addr, "KaveliPay backend no ar");

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await?;

    Ok(())
}

async fn shutdown_signal() {
    let ctrl_c = async {
        tokio::signal::ctrl_c()
            .await
            .expect("falha ao instalar handler de SIGINT");
    };

    #[cfg(unix)]
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("falha ao instalar handler de SIGTERM")
            .recv()
            .await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        () = ctrl_c => {}
        () = terminate => {}
    }

    tracing::info!("encerrando");
}