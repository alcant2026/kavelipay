//! Configuração da aplicação. Toda env var é validada na inicialização —
//! processo que sobe sem config válida morre na hora, não no primeiro request.

use std::env;

use crate::error::{AppError, AppResult};

/// Duração do access token. Curto de propósito: o refresh token é o de longa
/// duração e é revogável; o access token só existe para não hitting o banco.
const ACCESS_TOKEN_TTL_MINUTES: i64 = 15;
const REFRESH_TOKEN_TTL_DAYS: i64 = 30;

/// Segredo JWT tem 32 bytes. Abaixo disso um segredo curto é adivinhável.
const MIN_SECRET_LEN: usize = 32;

/// Piso do token de admin. 24 caracteres aleatórios já não se adivinham por
/// força bruta; abaixo disso o valor é quase certamente "1234" digitado com
/// pressa.
const MIN_ADMIN_TOKEN_LEN: usize = 24;

#[derive(Clone)]
pub struct Config {
    pub database_url: String,
    pub bind_addr: String,
    /// Teto do pool de conexões. Neon cobra por CU-hour e limita conexões
    /// simultâneas; 10 é folgado para um processo e não estoura o free tier.
    pub db_max_connections: u32,
    /// Requisições por minuto aceitas por IP no limitador global. `0` desliga
    /// (só serve para teste local; em produção a porta fica aberta).
    pub limite_por_minuto: u32,
    /// Chave HS256 de assinatura dos access tokens.
    pub jwt_secret: String,
    /// Minutos de validade do access token.
    pub access_token_ttl_minutes: i64,
    /// Dias de validade do refresh token.
    pub refresh_token_ttl_days: i64,
    /// Token do Mercado Pago (Checkout Pro). `None` desliga a cobrança por
    /// link: `POST /charges/{id}/pagamento` devolve 503 e o app fica só com o
    /// deep link/QR. Opcional pelo mesmo motivo da OpenRouter.
    pub mp_access_token: Option<String>,
    /// Base URL da API do Mercado Pago. Config para os testes apontarem para
    /// um mock local; em produção ninguém mexe.
    pub mp_base_url: String,
    /// URL pública que o Mercado Pago chama no webhook. `None` = preferência
    /// criada sem notificação (só pagamento manual de teste).
    pub mp_notification_url: Option<String>,
    /// Chave pública do Mercado Pago: vai para o HTML do Brick (o cartão é
    /// tokenizado no aparelho, o número nunca passa por aqui).
    pub mp_public_key: Option<String>,
    /// OAuth do Mercado Pago. `None` desliga "conectar minha conta": a rota
    /// devolve 503 e ninguém recebe dinheiro em nome de outro.
    pub mp_client_id: Option<String>,
    pub mp_client_secret: Option<String>,
    /// Chave simétrica dos tokens do Mercado Pago guardados no banco. Sem ela
    /// a conexão falha em vez de gravar segredo de terceiro em claro.
    pub mp_token_key: Option<String>,
    /// Modo de teste do Mercado Pago: em vez do checkout de produção, o link
    /// de pagamento aponta para o sandbox, onde cartão de teste é aceito.
    pub mp_sandbox: bool,
    /// Base pública do backend, sem barra no fim. É o que vira link de
    /// pagamento para quem não tem o app (`{base}/cobranca/{token}/pagar`).
    pub public_base_url: String,
    /// Segredo do admin (emissão de código de assinatura, `POST
    /// /admin/assinaturas/codigos`).
    ///
    /// `None` desliga a rota com 503 — o padrão é NÃO existir admin. Um token
    /// padrão seria uma porta dos fundos: quem lê o repositório emitiria
    /// assinatura para qualquer conta.
    pub admin_token: Option<String>,
    /// Envio de e-mail (recuperação de senha). `None` = SMTP não configurado:
    /// o backend sobe, e `POST /auth/forgot-password` devolve 503 em vez de
    /// fingir que mandou.
    pub mailer: Option<crate::mail::Mailer>,
    /// Chave do OpenRouter para o texto do relatório mensal. `None` desliga a
    /// análise por IA: o relatório continua sendo gerado com os números do
    /// ledger, só sem o parágrafo escrito pelo modelo.
    pub openrouter_api_key: Option<String>,
    /// Modelo usado no relatório. Padrão: DeepSeek V4.1 Flash (floor).
    pub openrouter_model: String,
    /// Base URL da API de chat. Config para os testes apontarem para um mock.
    pub openrouter_base_url: String,
}

impl Config {
    /// Lê a config do ambiente. Erro aqui é fatal de propósito: subir com
    /// segredo padrão é pior que não subir.
    pub fn from_env() -> AppResult<Self> {
        let database_url = required("DATABASE_URL")?;

        let jwt_secret = required("JWT_SECRET")?;
        if jwt_secret.len() < MIN_SECRET_LEN {
            return Err(AppError::Config(format!(
                "JWT_SECRET tem {} caracteres; mínimo {MIN_SECRET_LEN}",
                jwt_secret.len()
            )));
        }

        // Opcional: env var ausente (ou vazia, pra facilitar `unset`) não é
        // erro — é feature desligada. Erro de boot fica para o que é
        // obrigatório (banco, segredo).
        //
        // Mesmo tratamento, mas com piso de tamanho: um token de 4 caracteres
        // é adivinhável em segundos, e este token emite assinatura. Curto
        // demais é erro de boot, não aviso.
        let admin_token = env::var("ADMIN_TOKEN")
            .ok()
            .map(|k| k.trim().to_string())
            .filter(|k| !k.is_empty());
        if let Some(token) = &admin_token {
            if token.len() < MIN_ADMIN_TOKEN_LEN {
                return Err(AppError::Config(format!(
                    "ADMIN_TOKEN tem {} caracteres; mínimo {MIN_ADMIN_TOKEN_LEN}",
                    token.len()
                )));
            }
        }

        // Render injeta a porta em `PORT`; o dev local usa `BIND_ADDR`.
        // `BIND_ADDR` tem prioridade para não quebrar o ambiente atual.
        let bind_addr = match env::var("BIND_ADDR") {
            Ok(valor) if !valor.trim().is_empty() => valor,
            _ => match env::var("PORT") {
                Ok(porta) if !porta.trim().is_empty() => format!("0.0.0.0:{porta}"),
                _ => "0.0.0.0:3000".to_string(),
            },
        };

        // Neon free tem limite de conexões; o pool padrão do sqlx é 10.
        // Deixar configurável evita surpresa ao subir mais de uma instância.
        let db_max_connections = env::var("DB_MAX_CONNECTIONS")
            .ok()
            .and_then(|valor| valor.trim().parse::<u32>().ok())
            .filter(|n| (1..=100).contains(n))
            .unwrap_or(10);

        // Freio global por IP. Um plano free (0,1 vCPU) cai com um `for` em
        // paralelo. 300/min = 5 por segundo: um app inteiro abrindo gasta ~10
        // de uma vez, então gente não percebe. Não desça para 120 — operadora
        // com CGNAT põe muitos clientes no mesmo IP público.
        let limite_por_minuto = env::var("LIMITE_POR_MINUTO")
            .ok()
            .and_then(|valor| valor.trim().parse::<u32>().ok())
            .filter(|n| *n <= 10_000)
            .unwrap_or(300);

        Ok(Self {
            database_url,
            bind_addr,
            db_max_connections,
            limite_por_minuto,
            jwt_secret,
            access_token_ttl_minutes: ACCESS_TOKEN_TTL_MINUTES,
            refresh_token_ttl_days: REFRESH_TOKEN_TTL_DAYS,
            mp_access_token: env::var("MP_ACCESS_TOKEN")
                .ok()
                .map(|k| k.trim().to_string())
                .filter(|k| !k.is_empty()),
            mp_base_url: env::var("MP_BASE_URL")
                .unwrap_or_else(|_| crate::mercadopago::BASE_URL_PADRAO.to_string()),
            mp_public_key: env::var("MP_PUBLIC_KEY")
                .ok()
                .map(|k| k.trim().to_string())
                .filter(|k| !k.is_empty()),
            mp_sandbox: env::var("MP_SANDBOX")
                .map(|v| matches!(v.trim().to_ascii_lowercase().as_str(), "1" | "true" | "sim"))
                .unwrap_or(false),
            mp_client_id: env::var("MP_CLIENT_ID")
                .ok()
                .map(|k| k.trim().to_string())
                .filter(|k| !k.is_empty()),
            mp_client_secret: env::var("MP_CLIENT_SECRET")
                .ok()
                .map(|k| k.trim().to_string())
                .filter(|k| !k.is_empty()),
            mp_token_key: env::var("MP_TOKEN_KEY")
                .ok()
                .map(|k| k.trim().to_string())
                .filter(|k| !k.is_empty()),
            mp_notification_url: env::var("MP_NOTIFICATION_URL")
                .ok()
                .map(|u| u.trim().to_string())
                .filter(|u| !u.is_empty()),
            public_base_url: env::var("PUBLIC_BASE_URL")
                .ok()
                .map(|u| u.trim().trim_end_matches('/').to_string())
                .filter(|u| !u.is_empty())
                .unwrap_or_else(|| "https://kavelipay-backend.onrender.com".to_string()),
            admin_token,
            mailer: crate::mail::Mailer::from_env()?,
            openrouter_api_key: env::var("OPENROUTER_API_KEY")
                .ok()
                .map(|k| k.trim().to_string())
                .filter(|k| !k.is_empty()),
            openrouter_model: env::var("OPENROUTER_MODEL")
                .ok()
                .map(|m| m.trim().to_string())
                .filter(|m| !m.is_empty())
                .unwrap_or_else(|| crate::ai::MODELO_PADRAO.to_string()),
            openrouter_base_url: env::var("OPENROUTER_BASE_URL")
                .ok()
                .map(|u| u.trim().trim_end_matches('/').to_string())
                .filter(|u| !u.is_empty())
                .unwrap_or_else(|| crate::ai::BASE_URL_PADRAO.to_string()),
        })
    }
}

fn required(key: &str) -> AppResult<String> {
    env::var(key).map_err(|_| AppError::Config(format!("{key} não definida")))
}