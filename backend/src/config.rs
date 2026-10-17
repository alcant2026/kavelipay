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

#[derive(Clone)]
pub struct Config {
    pub database_url: String,
    pub bind_addr: String,
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

        Ok(Self {
            database_url,
            bind_addr: env::var("BIND_ADDR").unwrap_or_else(|_| "0.0.0.0:3000".to_string()),
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
        })
    }
}

fn required(key: &str) -> AppResult<String> {
    env::var(key).map_err(|_| AppError::Config(format!("{key} não definida")))
}