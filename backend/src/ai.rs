//! Cliente mínimo de chat completion (OpenRouter) para o relatório mensal.
//!
//! **A IA não calcula nada.** Ela recebe só os agregados que o ledger já
//! produziu e devolve texto qualitativo (leitura do mês e sugestões). Todo
//! número do relatório vem do banco; o texto é descartado se vier com dígito,
//! porque valor inventado num relatório financeiro é pior que relatório sem
//! análise.
//!
//! Sem `OPENROUTER_API_KEY` a feature fica desligada e a rota devolve 503 —
//! o backend sobe sem ela, igual ao Mercado Pago.

use std::time::Duration;

use serde::Deserialize;
use serde_json::json;

use crate::error::{AppError, AppResult};

/// Modelo padrão: DeepSeek V4.1 Flash (floor). Barato e suficiente para
/// escrever um parágrafo a partir de números prontos.
pub const MODELO_PADRAO: &str = "deepseek/deepseek-v4.1-flash-1017:floor";
pub const BASE_URL_PADRAO: &str = "https://openrouter.ai/api/v1";

#[derive(Clone)]
pub struct AiClient {
    http: reqwest::Client,
    api_key: Option<String>,
    model: String,
    base_url: String,
}

impl AiClient {
    pub fn new(api_key: Option<String>, model: String, base_url: String) -> AppResult<Self> {
        let http = reqwest::Client::builder()
            // Sem timeout o handler fica pendurado se o provedor não responder,
            // segurando uma das 10 conexões do pool.
            .timeout(Duration::from_secs(25))
            .build()
            .map_err(|e| AppError::Internal(anyhow::anyhow!("cliente http da IA: {e}")))?;
        Ok(Self {
            http,
            api_key,
            model,
            base_url,
        })
    }

    pub fn configurado(&self) -> bool {
        self.api_key.is_some()
    }

    /// Uma chamada de chat completion. `sistema` fixa o papel e as proibições;
    /// `usuario` leva os dados. Erro vira 503 — o relatório sem IA continua
    /// existindo, então indisponibilidade do provedor não é erro do usuário.
    pub async fn completar(&self, sistema: &str, usuario: &str) -> AppResult<String> {
        let chave = self.api_key.as_deref().ok_or(AppError::Unavailable)?;

        let corpo = json!({
            "model": self.model,
            "messages": [
                { "role": "system", "content": sistema },
                { "role": "user", "content": usuario },
            ],
            // 3000 e não 700: o modelo é de raciocínio e gasta a maior parte
            // do teto pensando (medido: ~1900 tokens de raciocínio + ~800 de
            // texto). Teto baixo devolvia `finish_reason: length`, texto
            // cortado no meio da frase.
            "max_tokens": 3000,
            "temperature": 0.3,
        });

        let resposta = self
            .http
            .post(format!(
                "{}/chat/completions",
                self.base_url.trim_end_matches('/')
            ))
            .bearer_auth(chave)
            .json(&corpo)
            .send()
            .await
            .map_err(|e| {
                tracing::warn!("IA indisponível: {e}");
                AppError::Unavailable
            })?;

        if !resposta.status().is_success() {
            tracing::warn!("IA respondeu {}", resposta.status());
            return Err(AppError::Unavailable);
        }

        let dados: Resposta = resposta.json().await.map_err(|e| {
            tracing::warn!("resposta da IA fora do formato: {e}");
            AppError::Unavailable
        })?;

        let Some(escolha) = dados.choices.into_iter().next() else {
            return Err(AppError::Unavailable);
        };
        if escolha.finish_reason.as_deref() == Some("length") {
            tracing::warn!("IA cortou a resposta no teto de tokens");
            return Err(AppError::Unavailable);
        }
        let texto = escolha
            .message
            .content
            .unwrap_or_default()
            .trim()
            .to_string();

        if texto.is_empty() {
            return Err(AppError::Unavailable);
        }
        Ok(texto)
    }
}

#[derive(Deserialize)]
struct Resposta {
    choices: Vec<Escolha>,
}

#[derive(Deserialize)]
struct Escolha {
    message: Mensagem,
    /// `"length"` = o teto de tokens cortou a resposta. Texto cortado não
    /// entra no relatório: prefiro a seção ausente a uma frase pela metade.
    finish_reason: Option<String>,
}

#[derive(Deserialize)]
struct Mensagem {
    content: Option<String>,
}
