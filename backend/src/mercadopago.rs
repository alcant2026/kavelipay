//! Cliente da API do Mercado Pago (Checkout Pro).
//!
//! **Fronteira:** quem processa o pagamento é o Mercado Pago, na conta do
//! próprio dono do app. O KaveliPay não custodia dinheiro: cria a preferência
//! de pagamento, e no webhook LÊ o status real do pagamento na API deles — não
//! acredita no corpo da notificação, que qualquer um pode forjar.
//!
//! Sem `MP_ACCESS_TOKEN` a feature fica desligada e as rotas devolvem 503,
//! igual à OpenRouter. O backend tem de subir sem ela.

use rust_decimal::Decimal;
use serde::Deserialize;
use serde_json::json;

use crate::error::{AppError, AppResult};

pub const BASE_URL_PADRAO: &str = "https://api.mercadopago.com";

#[derive(Clone)]
pub struct MercadoPago {
    http: reqwest::Client,
    token: Option<String>,
    base_url: String,
    /// URL pública que o Mercado Pago chama quando o pagamento muda de status.
    /// `None` = preferência criada sem webhook; serve só para teste manual.
    notification_url: Option<String>,
    /// Chave pública: só o HTML do Payment Brick usa. Não é segredo — ela
    /// identifica a conta para o SDK no navegador; quem cobra é o token.
    public_key: Option<String>,
}

/// Preferência criada: o app abre `init_point` no navegador para pagar.
pub struct Preferencia {
    pub id: String,
    pub init_point: String,
}

/// O que interessa do pagamento consultado. O valor NÃO vem daqui: quem manda
/// é o `amount` da cobrança no nosso banco.
#[derive(Deserialize)]
pub struct Pagamento {
    pub status: String,
    pub external_reference: Option<String>,
}

impl MercadoPago {
    pub fn new(
        token: Option<String>,
        base_url: String,
        notification_url: Option<String>,
        public_key: Option<String>,
    ) -> AppResult<Self> {
        let http = reqwest::Client::builder()
            // Sem timeout o handler fica pendurado se o Mercado Pago não
            // responder, segurando uma das 10 conexões do pool.
            .timeout(std::time::Duration::from_secs(20))
            .build()
            .map_err(|e| AppError::Internal(anyhow::anyhow!("cliente http: {e}")))?;

        Ok(Self {
            http,
            token: token.filter(|t| !t.trim().is_empty()),
            base_url,
            notification_url: notification_url.filter(|u| !u.trim().is_empty()),
            public_key: public_key.filter(|k| !k.trim().is_empty()),
        })
    }

    fn token(&self) -> AppResult<&str> {
        self.token.as_deref().ok_or(AppError::Unavailable)
    }

    /// Quem recebe: o token do dono da cobrança quando ele conectou a própria
    /// conta, senão a credencial da aplicação (receita própria, como o
    /// premium). Nunca o contrário — dinheiro de terceiro não passa por aqui.
    fn token_de<'a>(&'a self, dono: Option<&'a str>) -> AppResult<&'a str> {
        match dono {
            Some(t) => Ok(t),
            None => self.token(),
        }
    }

    /// Base da API — para o OAuth montar `{base}/oauth/token`.
    pub fn base_url(&self) -> &str {
        &self.base_url
    }

    /// Chave pública do Payment Brick. `None` = sem Mercado Pago configurado.
    pub fn public_key(&self) -> Option<&str> {
        self.public_key.as_deref()
    }

    /// Cria o pagamento direto na API (Payment Brick): o cartão foi
    /// tokenizado no aparelho pelo SDK, então aqui chega só o token.
    /// Devolve o JSON cru porque é ele que o Brick precisa para desenhar a
    /// tela de resultado (Pix inclusive, com o QR).
    pub async fn criar_pagamento(
        &self,
        mut corpo: serde_json::Value,
    ) -> AppResult<serde_json::Value> {
        if let Some(url) = &self.notification_url {
            corpo["notification_url"] = json!(url);
        }

        let resposta = self
            .http
            .post(format!("{}/v1/payments", self.base_url))
            .bearer_auth(self.token()?)
            .json(&corpo)
            .send()
            .await
            .map_err(|e| AppError::Internal(anyhow::anyhow!("mercadopago: {e}")))?;

        let status = resposta.status();
        let texto = resposta.text().await.unwrap_or_default();
        let json: serde_json::Value = serde_json::from_str(&texto)
            .map_err(|e| AppError::Internal(anyhow::anyhow!("mercadopago: resposta inválida: {e}")))?;

        if !status.is_success() {
            // 4xx é recusa de negócio (cartão recusado, valor inválido): o
            // app precisa mostrar o motivo, então a mensagem deles vai junto.
            return Err(AppError::Validation(format!(
                "o Mercado Pago recusou o pagamento: {}",
                json.pointer("/message")
                    .and_then(|v| v.as_str())
                    .unwrap_or("tente outro meio de pagamento")
            )));
        }

        Ok(json)
    }

    /// Cria a preferência de Checkout Pro. `volta` é para onde o Mercado Pago
    /// manda o pagador depois: o deep link da cobrança no app, ou a página
    /// pública da parte para quem paga no navegador sem ter o app.
    /// `referencia` é o `external_reference` que volta no webhook: o id da
    /// cobrança, ou `parte:<id>` quando o que se cobra é a cota de uma divisão.
    pub async fn criar_preferencia(
        &self,
        referencia: &str,
        descricao: &str,
        valor: Decimal,
        moeda: &str,
        volta: &str,
        dono: Option<&str>,
    ) -> AppResult<Preferencia> {
        // Única conversão para f64 do caminho de dinheiro: a API do Mercado
        // Pago só aceita número em `unit_price`. O valor cobrado continua
        // sendo o do banco (NUMERIC(19,4)) — isto aqui é a borda com terceiro.
        let unit_price = valor
            .to_string()
            .parse::<f64>()
            .map_err(|e| AppError::InvalidAmount(format!("valor inválido para o pagamento: {e}")))?;

        let mut corpo = json!({
            "items": [{
                "id": referencia,
                "title": descricao,
                "quantity": 1,
                "currency_id": moeda,
                "unit_price": unit_price,
            }],
            "external_reference": referencia,
            "back_urls": { "success": volta, "pending": volta, "failure": volta },
        });
        if let Some(url) = &self.notification_url {
            corpo["notification_url"] = json!(url);
        }

        let resposta = self
            .http
            .post(format!("{}/checkout/preferences", self.base_url))
            .bearer_auth(self.token_de(dono)?)
            .json(&corpo)
            .send()
            .await
            .map_err(|e| AppError::Internal(anyhow::anyhow!("mercadopago: {e}")))?;

        let status = resposta.status();
        let texto = resposta.text().await.unwrap_or_default();
        if !status.is_success() {
            return Err(AppError::Internal(anyhow::anyhow!(
                "mercadopago recusou a preferência ({status}): {texto}"
            )));
        }

        let json: serde_json::Value = serde_json::from_str(&texto)
            .map_err(|e| AppError::Internal(anyhow::anyhow!("mercadopago: resposta inválida: {e}")))?;

        let id = json
            .get("id")
            .and_then(|v| v.as_str())
            .ok_or_else(|| AppError::Internal(anyhow::anyhow!("mercadopago: preferência sem id")))?;
        let init_point = json
            .get("init_point")
            .and_then(|v| v.as_str())
            .ok_or_else(|| {
                AppError::Internal(anyhow::anyhow!("mercadopago: preferência sem init_point"))
            })?;

        Ok(Preferencia {
            id: id.to_string(),
            init_point: init_point.to_string(),
        })
    }

    /// Consulta o pagamento na fonte. É esta resposta que decide se a cobrança
    /// foi paga — nunca o corpo do webhook.
    pub async fn pagamento(&self, id: &str, dono: Option<&str>) -> AppResult<Pagamento> {
        let resposta = self
            .http
            .get(format!("{}/v1/payments/{id}", self.base_url))
            .bearer_auth(self.token_de(dono)?)
            .send()
            .await
            .map_err(|e| AppError::Internal(anyhow::anyhow!("mercadopago: {e}")))?;

        let status = resposta.status();
        let texto = resposta.text().await.unwrap_or_default();
        if !status.is_success() {
            return Err(AppError::Internal(anyhow::anyhow!(
                "mercadopago: consulta de pagamento falhou ({status}): {texto}"
            )));
        }

        serde_json::from_str(&texto)
            .map_err(|e| AppError::Internal(anyhow::anyhow!("mercadopago: resposta inválida: {e}")))
    }
}
