use axum::http::StatusCode;
use thiserror::Error;

/// Erros de domínio. `sqlx::Error` e `jsonwebtoken::Error` são conversíveis
/// para que handlers só precisem lidar com `AppError`.
#[derive(Debug, Error)]
pub enum AppError {
    #[error("não autorizado")]
    Unauthorized,

    #[error("acesso negado")]
    Forbidden,

    #[error("{0} não encontrado")]
    NotFound(&'static str),

    #[error("transação não equilibrada: débitos {debits} != créditos {credits}")]
    UnbalancedTransaction { debits: String, credits: String },

    #[error("valor inválido: {0}")]
    InvalidAmount(String),

    #[error("dados inválidos: {0}")]
    Validation(String),

    #[error("{0}")]
    Conflict(String),

    /// Recurso do plano Premium sem assinatura vigente. 402, não 403: não é
    /// falta de permissão nem de login, é falta de plano — e o app usa esse
    /// código para abrir a tela de assinatura em vez de mostrar "acesso
    /// negado". Mensagem sempre convidativa, nunca acusatória.
    #[error("{0}")]
    PaymentRequired(&'static str),

    /// Recurso desligado ou fora do ar (ex.: `OPENROUTER_API_KEY` não
    /// configurada). 503, não 500: não é defeito do servidor, é
    /// indisponibilidade — e o cliente deve tratar como "tente depois",
    /// não como "corrija seu request".
    #[error("recurso indisponível no momento")]
    Unavailable,

    #[error("configuração inválida: {0}")]
    Config(String),

    #[error("erro de banco de dados: {0}")]
    Database(#[from] sqlx::Error),

    #[error("erro de token: {0}")]
    Token(#[from] jsonwebtoken::errors::Error),

    #[error("erro interno: {0}")]
    Internal(#[from] anyhow::Error),

    #[error("falha de IO: {0}")]
    Io(#[from] std::io::Error),

    #[error("falha de migração: {0}")]
    Migrate(#[from] sqlx::migrate::MigrateError),
}

impl AppError {
    /// Status HTTP correspondente. Regra: erro de domínio é 4xx com detalhe
    /// útil; erro de infraestrutura é 5xx sem detalhe.
    pub fn status(&self) -> StatusCode {
        match self {
            AppError::Unauthorized => StatusCode::UNAUTHORIZED,
            AppError::Forbidden => StatusCode::FORBIDDEN,
            AppError::NotFound(_) => StatusCode::NOT_FOUND,
            AppError::Conflict(_) => StatusCode::CONFLICT,
            AppError::PaymentRequired(_) => StatusCode::PAYMENT_REQUIRED,
            AppError::Unavailable => StatusCode::SERVICE_UNAVAILABLE,
            AppError::UnbalancedTransaction { .. }
            | AppError::InvalidAmount(_)
            | AppError::Validation(_) => StatusCode::UNPROCESSABLE_ENTITY,
            AppError::Database(_) | AppError::Token(_) | AppError::Internal(_) => {
                StatusCode::INTERNAL_SERVER_ERROR
            }
            // Config é falha de boot, não de request: se aparecer num handler
            // é bug de código, não input do cliente. Io/Migrate idem — só
            // ocorrem no boot.
            AppError::Config(_) | AppError::Io(_) | AppError::Migrate(_) => {
                StatusCode::INTERNAL_SERVER_ERROR
            }
        }
    }

    /// Código estável para o cliente tratar Programmaticamente.
    pub fn code(&self) -> &'static str {
        match self {
            AppError::Unauthorized => "unauthorized",
            AppError::Forbidden => "forbidden",
            AppError::NotFound(_) => "not_found",
            AppError::Conflict(_) => "conflict",
            AppError::PaymentRequired(_) => "plano_necessario",
            AppError::Unavailable => "unavailable",
            AppError::UnbalancedTransaction { .. }
            | AppError::InvalidAmount(_)
            | AppError::Validation(_) => "invalid_transaction",
            AppError::Database(_)
            | AppError::Token(_)
            | AppError::Internal(_)
            | AppError::Config(_)
            | AppError::Io(_)
            | AppError::Migrate(_) => "internal",
        }
    }

    /// Mensagem que vai para o cliente. Erros 5xx NUNCA revelam o motivo
    /// interno (SQL, nome de tabela, segredo) — só o log tem isso.
    pub fn client_message(&self) -> String {
        if self.status() == StatusCode::INTERNAL_SERVER_ERROR {
            "erro interno".to_string()
        } else {
            self.to_string()
        }
    }
}

impl axum::response::IntoResponse for AppError {
    fn into_response(self) -> axum::response::Response {
        let status = self.status();
        let code = self.code();
        let message = self.client_message();

        tracing::error!(error = %self, code, status = status.as_u16(), "requisição falhou");

        (
            status,
            axum::Json(serde_json::json!({ "error": code, "message": message })),
        )
            .into_response()
    }
}

pub type AppResult<T> = Result<T, AppError>;