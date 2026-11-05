//! Conta do Mercado Pago de cada usuário (OAuth, authorization code).
//!
//! ## Por que isto existe
//!
//! Cobrança e divisão usavam uma credencial só, a do dono do app. Todo
//! pagamento caía nessa conta: o KaveliPay virava custódia do dinheiro de
//! terceiro, o que a spec proíbe. Com o token do próprio dono da cobrança, o
//! dinheiro cai direto na conta de quem cobrou — a plataforma não toca em nada.
//!
//! ## Como o token chega aqui
//!
//! `GET /mercadopago/conectar` monta a URL de autorização do Mercado Pago com
//! um `state` de uso único guardado no banco (o `state` é o que impede colar o
//! código de uma pessoa na conta de outra). Depois do aceite, o Mercado Pago
//! chama `GET /mercadopago/callback`, que troca o código por access token +
//! refresh token e devolve o usuário para o app.
//!
//! Os tokens ficam cifrados com `pgp_sym_encrypt`; a chave é env var
//! (`MP_TOKEN_KEY`). Um dump do banco não entrega a conta de ninguém.

use axum::Json;
use axum::extract::{Query, State};
use axum::response::{Html, Redirect};
use chrono::{DateTime, Duration, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::auth::AuthUser;
use crate::error::{AppError, AppResult};
use crate::state::AppState;

/// Configuração do OAuth. Junta os três segredos num `Option`: ou existe o
/// conjunto inteiro, ou a conexão está desligada — meio configurado não serve.
#[derive(Clone)]
pub struct OAuth {
    pub id: String,
    pub segredo: String,
    pub chave: String,
}

// Domínio com `.br` de propósito: `auth.mercadopago.com` (sem `.br`) entrega o
// seletor "crossite" do Mercado Livre, que fica girando para sempre dentro de
// WebView. O `.br` manda direto para o login, que é o fluxo de verdade.
const AUTORIZACAO: &str = "https://auth.mercadopago.com.br/authorization";

/// O código de autorização vale 10 minutos; o state daqui vale o mesmo.
const VALIDADE_STATE_MINUTOS: i64 = 10;

/// Renova o token um dia antes de vencer em vez de esperar o 401 aparecer no
/// meio de um pagamento real.
const MARGEM_RENOVACAO_HORAS: i64 = 24;

#[derive(Serialize)]
pub struct ContaResponse {
    pub conectado: bool,
    pub mp_user_id: Option<i64>,
}

#[derive(Deserialize)]
struct Tokens {
    access_token: String,
    refresh_token: String,
    expires_in: i64,
    user_id: i64,
}

fn oauth(state: &AppState) -> AppResult<&OAuth> {
    state.mp_oauth.as_ref().ok_or(AppError::Unavailable)
}

/// 32 hex: mesmo formato dos tokens de compartilhamento do banco.
fn novo_state() -> String {
    Uuid::new_v4().simple().to_string()
}

/// GET /mercadopago/conectar — manda o usuário autorizar a conta dele.
/// Monta a URL de autorização e grava o `state` — é o que impede um callback
/// de fora de conectar a conta a um usuário que não pediu.
async fn url_de_conexao(state: &AppState, user_id: Uuid) -> AppResult<String> {
    let oauth = oauth(state)?;
    let state_token = novo_state();

    // Um state por usuário: tentativa antiga abandonada não acumula lixo.
    sqlx::query("DELETE FROM mp_oauth_states WHERE user_id = $1")
        .bind(user_id)
        .execute(&state.db)
        .await?;
    sqlx::query("INSERT INTO mp_oauth_states (state, user_id) VALUES ($1, $2)")
        .bind(&state_token)
        .bind(user_id)
        .execute(&state.db)
        .await?;

    // `redirect_uri` tem de ser exatamente a cadastrada no painel do Mercado
    // Pago; por isso ela nasce do `public_base_url`, não de header do request.
    Ok(format!(
        "{AUTORIZACAO}?client_id={}&response_type=code&platform_id=mp&state={state_token}\
         &redirect_uri={}/mercadopago/callback",
        oauth.id, state.public_base_url
    ))
}

/// `GET /mercadopago/conectar` — abre a tela do Mercado Pago no navegador.
pub async fn conectar(State(state): State<AppState>, auth: AuthUser) -> AppResult<Redirect> {
    Ok(Redirect::to(&url_de_conexao(&state, auth.user_id()).await?))
}

/// `GET /mercadopago/link` — a mesma URL, em JSON. O app não consegue mandar
/// o header de sessão num WebView, então busca o link já autenticado e abre
/// só o domínio do Mercado Pago.
pub async fn link(
    State(state): State<AppState>,
    auth: AuthUser,
) -> AppResult<Json<serde_json::Value>> {
    let url = url_de_conexao(&state, auth.user_id()).await?;
    Ok(Json(serde_json::json!({ "url": url })))
}

#[derive(Deserialize)]
pub struct Callback {
    pub code: Option<String>,
    pub state: Option<String>,
}

/// GET /mercadopago/callback — o Mercado Pago volta aqui com o código.
/// Público (quem chama é o navegador, sem sessão): a identidade vem do
/// `state`, que só existe para quem começou a conexão autenticado.
pub async fn callback(
    State(state): State<AppState>,
    Query(q): Query<Callback>,
) -> AppResult<Html<String>> {
    let oauth = oauth(&state)?;
    let falha = || AppError::Validation("conexão com o Mercado Pago expirou — tente de novo".into());

    let state_token = q.state.ok_or_else(falha)?;
    let code = q.code.ok_or_else(falha)?;

    // Uso único: o DELETE já consome o state. Se ninguém consumir, expira
    // sozinho pela janela de tempo abaixo.
    let user_id = sqlx::query_scalar::<_, Uuid>(
        "DELETE FROM mp_oauth_states
          WHERE state = $1 AND criado_em > now() - ($2 || ' minutes')::interval
      RETURNING user_id",
    )
    .bind(&state_token)
    .bind(VALIDADE_STATE_MINUTOS.to_string())
    .fetch_optional(&state.db)
    .await?
    .ok_or_else(falha)?;

    let tokens = trocar(&state, serde_json::json!({
        "client_id": oauth.id,
        "client_secret": oauth.segredo,
        "code": code,
        "grant_type": "authorization_code",
        "redirect_uri": format!("{}/mercadopago/callback", state.public_base_url),
    }))
    .await?;

    guardar(&state, oauth, user_id, &tokens).await?;

    Ok(pagina_conectado())
}

/// A autorização do Mercado Pago só abre no navegador (a página deles é um
/// interstitial de anti-fraude que não renderiza dentro de WebView), então a
/// volta é uma tela de navegador: confirma e oferece o link que reabre o app.
fn pagina_conectado() -> Html<String> {
    Html(r#"<!doctype html>
<html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Conta conectada · KaveliPay</title>
<style>
:root{color-scheme:light dark}
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
background:#f5f5f7;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;color:#111}
main{background:#fff;border-radius:20px;padding:32px 24px;max-width:380px;width:100%;
box-shadow:0 8px 30px rgba(0,0,0,.08);text-align:center}
h1{font-size:18px;margin:0 0 8px}
p{font-size:15px;color:#666;margin:0 0 20px}
a{display:block;padding:16px;border-radius:14px;background:#7b2ff7;color:#fff;
text-decoration:none;font-weight:600;font-size:17px}
@media(prefers-color-scheme:dark){body{background:#0e0e10;color:#f2f2f2}
main{background:#1b1b1f;box-shadow:none}p{color:#aaa}}
</style></head>
<body><main>
<h1>Conta conectada</h1>
<p>Os pagamentos das suas cobranças vão cair direto nela.</p>
<a href="kavelipay://mercadopago/conectado">Voltar ao KaveliPay</a>
</main></body></html>"#
        .to_string())
}

/// POST /mercadopago/desconectar — para de receber na própria conta.
pub async fn desconectar(
    State(state): State<AppState>,
    auth: AuthUser,
) -> AppResult<axum::http::StatusCode> {
    sqlx::query("DELETE FROM mp_contas WHERE user_id = $1")
        .bind(auth.user_id())
        .execute(&state.db)
        .await?;
    Ok(axum::http::StatusCode::NO_CONTENT)
}

/// GET /mercadopago/status — o app precisa saber se já pode cobrar.
pub async fn status(
    State(state): State<AppState>,
    auth: AuthUser,
) -> AppResult<Json<ContaResponse>> {
    let mp_user_id = sqlx::query_scalar::<_, i64>("SELECT mp_user_id FROM mp_contas WHERE user_id = $1")
        .bind(auth.user_id())
        .fetch_optional(&state.db)
        .await?;
    Ok(Json(ContaResponse {
        conectado: mp_user_id.is_some(),
        mp_user_id,
    }))
}

/// Essa pessoa já conectou a conta? Usado nas páginas públicas: sem conexão
/// não existe botão de pagar, porque não há para onde mandar o dinheiro.
pub(crate) async fn conectada(state: &AppState, user_id: Uuid) -> bool {
    sqlx::query_scalar::<_, bool>("SELECT EXISTS (SELECT 1 FROM mp_contas WHERE user_id = $1)")
        .bind(user_id)
        .fetch_one(&state.db)
        .await
        .unwrap_or(false)
}

/// Token do dono da cobrança, renovando se estiver perto de vencer.
/// `None` = essa pessoa nunca conectou a conta (e não pode cobrar pelo app).
pub(crate) async fn token_do_dono(state: &AppState, user_id: Uuid) -> AppResult<Option<String>> {
    let Some(oauth) = state.mp_oauth.as_ref() else {
        return Ok(None);
    };
    let linha = sqlx::query_as::<_, (String, String, DateTime<Utc>)>(
        "SELECT pgp_sym_decrypt(decode(access_token, 'base64'), $2)::text,
                pgp_sym_decrypt(decode(refresh_token, 'base64'), $2)::text,
                expira_em
           FROM mp_contas WHERE user_id = $1",
    )
    .bind(user_id)
    .bind(&oauth.chave)
    .fetch_optional(&state.db)
    .await?;

    let Some((access, refresh, expira)) = linha else {
        return Ok(None);
    };
    if expira > Utc::now() + Duration::hours(MARGEM_RENOVACAO_HORAS) {
        return Ok(Some(access));
    }

    let tokens = trocar(state, serde_json::json!({
        "client_id": oauth.id,
        "client_secret": oauth.segredo,
        "refresh_token": refresh,
        "grant_type": "refresh_token",
    }))
    .await?;
    guardar(state, oauth, user_id, &tokens).await?;
    Ok(Some(tokens.access_token))
}

/// De quem é este pagamento, segundo o Mercado Pago? O webhook só manda o id
/// do pagamento, e para consultá-lo é preciso o token do vendedor — que é
/// justamente o que este mapa resolve.
pub(crate) async fn dono_por_mp_user(state: &AppState, mp_user_id: i64) -> Option<Uuid> {
    sqlx::query_scalar::<_, Uuid>("SELECT user_id FROM mp_contas WHERE mp_user_id = $1")
        .bind(mp_user_id)
        .fetch_optional(&state.db)
        .await
        .ok()
        .flatten()
}

async fn trocar(state: &AppState, corpo: serde_json::Value) -> AppResult<Tokens> {
    // Cliente próprio: OAuth acontece uma vez por conta, não vale carregar
    // outro pool TLS no estado da aplicação.
    let resposta = reqwest::Client::new()
        .post(format!("{}/oauth/token", base(state)))
        .json(&corpo)
        .send()
        .await
        .map_err(|e| AppError::Internal(anyhow::anyhow!("oauth mercadopago: {e}")))?;

    if !resposta.status().is_success() {
        let texto = resposta.text().await.unwrap_or_default();
        return Err(AppError::Validation(format!(
            "o Mercado Pago recusou a conexão: {}",
            resumo(&texto)
        )));
    }
    resposta
        .json::<Tokens>()
        .await
        .map_err(|e| AppError::Internal(anyhow::anyhow!("oauth mercadopago: {e}")))
}

async fn guardar(
    state: &AppState,
    oauth: &OAuth,
    user_id: Uuid,
    tokens: &Tokens,
) -> AppResult<()> {
    sqlx::query(
        "INSERT INTO mp_contas (user_id, mp_user_id, access_token, refresh_token, expira_em)
         VALUES ($1, $2, encode(pgp_sym_encrypt($3, $5), 'base64'),
                 encode(pgp_sym_encrypt($4, $5), 'base64'), $6)
         ON CONFLICT (user_id) DO UPDATE SET
             mp_user_id = EXCLUDED.mp_user_id,
             access_token = EXCLUDED.access_token,
             refresh_token = EXCLUDED.refresh_token,
             expira_em = EXCLUDED.expira_em,
             atualizado_em = now()",
    )
    .bind(user_id)
    .bind(tokens.user_id)
    .bind(&tokens.access_token)
    .bind(&tokens.refresh_token)
    .bind(&oauth.chave)
    .bind(Utc::now() + Duration::seconds(tokens.expires_in))
    .execute(&state.db)
    .await?;
    Ok(())
}

fn base(state: &AppState) -> &str {
    state.mp.base_url()
}

/// Mensagem de erro curta: o corpo do Mercado Pago é JSON com a razão; jogar
/// o JSON inteiro na tela de quem está conectando não ajuda ninguém.
fn resumo(texto: &str) -> String {
    serde_json::from_str::<serde_json::Value>(texto)
        .ok()
        .and_then(|v| {
            v.get("message")
                .and_then(|m| m.as_str())
                .map(|m| m.to_string())
        })
        .unwrap_or_else(|| "erro desconhecido".to_string())
}
