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
    /// Chave da OpenRouter para o prefill de ativos. `None` desliga a
    /// feature: `POST /investments/lookup` devolve 503 e o app não preenche
    /// nada. Opcional de propósito — o backend tem de subir sem ela, porque
    /// ela é segredo de terceiro, não configuração de infraestrutura.
    pub openrouter_api_key: Option<String>,
    /// Modelo da OpenRouter usado no prefill. O padrão é o mais barato que
    /// resolve a tarefa (~US$ 0,03/M tokens); trocar é mudar env var.
    pub openrouter_model: String,
    /// Base URL da API da OpenRouter. Existe como config para os testes de
    /// integração apontarem para um mock local — em produção ninguém mexe.
    pub openrouter_base_url: String,
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
    /// Base pública do backend, sem barra no fim. É o que vira link de
    /// pagamento para quem não tem o app (`{base}/cobranca/{token}/pagar`).
    pub public_base_url: String,
    /// Base URL do brapi (cotação de ação + dividendos). Grátis e sem
    /// chave (verificado ao vivo) — como a OpenRouter, o hook de teste é a
    /// base URL.
    pub brapi_base_url: String,
    /// Base URL do Yahoo Finance (cotação de FII — brapi dá 401 em fundo
    /// sem token). Mesmo motivo da anterior.
    pub yahoo_base_url: String,
    /// Base URL do statusinvest (provento de FII e reserva da ação — tem a
    /// data de PAGAMENTO, que é a que interessa). Mesmo motivo da anterior.
    pub statusinvest_base_url: String,
    /// Segredo do cadastro de ofertas da vitrine (`POST /admin/parceiros`).
    ///
    /// `None` desliga a rota com 503 — o padrão é NÃO existir admin. Um token
    /// padrão seria uma porta dos fundos: quem lê o repositório cadastraria
    /// propaganda no app de todo mundo. Igual à OpenRouter, é segredo de
    /// operação, não configuração de infraestrutura.
    pub admin_token: Option<String>,
    /// Envio de e-mail (recuperação de senha). `None` = SMTP não configurado:
    /// o backend sobe, e `POST /auth/forgot-password` devolve 503 em vez de
    /// fingir que mandou.
    pub mailer: Option<crate::mail::Mailer>,
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
        let openrouter_api_key = env::var("OPENROUTER_API_KEY")
            .ok()
            .map(|k| k.trim().to_string())
            .filter(|k| !k.is_empty());

        // Mesmo tratamento da chave da IA, mas com piso de tamanho: um token
        // de 4 caracteres é adivinhável em segundos, e este token escreve
        // conteúdo que aparece para todos os usuários. Curto demais é erro de
        // boot, não aviso.
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
            openrouter_api_key,
            openrouter_model: env::var("OPENROUTER_MODEL")
                .unwrap_or_else(|_| crate::ai::MODELO_PADRAO.to_string()),
            openrouter_base_url: env::var("OPENROUTER_BASE_URL")
                .unwrap_or_else(|_| crate::ai::BASE_URL_PADRAO.to_string()),
            brapi_base_url: env::var("BRAPI_BASE_URL")
                .unwrap_or_else(|_| crate::cotacao::BRAPI_BASE_PADRAO.to_string()),
            yahoo_base_url: env::var("YAHOO_BASE_URL")
                .unwrap_or_else(|_| crate::cotacao::YAHOO_BASE_PADRAO.to_string()),
            statusinvest_base_url: env::var("STATUSINVEST_BASE_URL")
                .unwrap_or_else(|_| crate::cotacao::STATUSINVEST_BASE_PADRAO.to_string()),
            mp_access_token: env::var("MP_ACCESS_TOKEN")
                .ok()
                .map(|k| k.trim().to_string())
                .filter(|k| !k.is_empty()),
            mp_base_url: env::var("MP_BASE_URL")
                .unwrap_or_else(|_| crate::mercadopago::BASE_URL_PADRAO.to_string()),
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
        })
    }
}

fn required(key: &str) -> AppResult<String> {
    env::var(key).map_err(|_| AppError::Config(format!("{key} não definida")))
}