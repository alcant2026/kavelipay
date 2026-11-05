//! Assinatura premium: como o app paga as próprias contas.
//!
//! O KaveliPay não é banco e não custodia dinheiro do usuário. A assinatura é
//! **receita da empresa**, e por isso vive em tabela própria — nunca no ledger
//! da carteira de quem assina. Registrar a compra como transação do cliente
//! misturaria as duas contas e sujaria o saldo demonstrativo.
//!
//! ## Dois caminhos, um direito
//!
//! - **Código de ativação** (funciona hoje, em APK sideload): o dono vende por
//!   checkout externo e entrega um código; o app troca por período.
//! - **Play Billing** (quando publicar na Play Store): o app manda o purchase
//!   token e o backend valida no Google. A tabela `assinaturas` não muda.
//!
//! O direito é sempre DERIVADO de `status = 'ACTIVE' AND periodo_fim > now()`.
//! Não existe coluna "premium" em users: expirar é o tempo passar, não um
//! UPDATE que alguém esquece de rodar.
//!
//! ## O que o plano pago libera
//!
//! Recursos que geram custo real de API: relatório mensal, análise por IA
//! (`/investments/lookup`) e cotação de mercado (`/investments/quote`). O
//! núcleo — saldo, extrato, contas, cobranças, dívidas — continua grátis; um
//! app de finanças que não serve para organizar o mês é desinstalado antes de
//! assinar. A exportação LGPD também é grátis por lei: portabilidade de dados
//! não pode ficar atrás de paywall.

use axum::Json;
use axum::extract::{Path, State};
use axum::response::Html;
use axum::http::HeaderMap;
use chrono::{DateTime, Duration, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::PgPool;
use uuid::Uuid;

use crate::auth::admin::exigir_admin;
use crate::routes::charges::escapar;
use crate::auth::AuthUser;
use crate::error::{AppError, AppResult};
use crate::state::AppState;

/// Preço de referência do plano mensal. A API devolve como texto porque
/// dinheiro nunca é float — nem no JSON, nem no app.
pub const PRECO_MENSAL: &str = "9.90";
pub const PRECO_ANUAL: &str = "79.90";

/// Recursos que o plano libera. Lista explícita para o app desenhar a tela sem
/// inventar promessa que o backend não cumpre.
///
/// Texto de USUÁRIO: quem lê isto no app não sabe o que é brapi nem Yahoo.
/// O nome da fonte fica no código (`cotacao.rs`), não na tela.
pub const RECURSOS_PREMIUM: &[&str] = &[
    "Relatórios mensais por categoria",
    "Análise de ativos por IA",
    "Cotação de mercado dos seus investimentos",
];

const ERRO_PLANO: &str = "este recurso faz parte do plano Premium — conheça o plano";

#[derive(Serialize)]
pub struct PlanoResponse {
    pub premium: bool,
    pub plano: Option<String>,
    pub periodo_fim: Option<DateTime<Utc>>,
    pub recursos_premium: &'static [&'static str],
    pub preco_mensal: &'static str,
    pub preco_anual: &'static str,
    /// Novo access token com o claim `premium` já atualizado. Só vem na
    /// resposta de `POST /billing/ativar`: sem ele, o app continuaria com o
    /// token antigo (`premium = false`) por até 15 minutos e a tela de
    /// assinatura apareceria logo depois de pagar. Ausente no GET.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub access_token: Option<String>,
}

/// Direito vigente do usuário. `None` = sem assinatura ativa (nunca existiu,
/// expirou ou foi cancelada).
///
/// Público dentro do crate porque o login/refresh também precisam dele: o
/// access token carrega o retrato do direito no momento da emissão.
pub(crate) async fn assinatura_vigente(
    db: &PgPool,
    user_id: Uuid,
) -> AppResult<Option<(String, DateTime<Utc>)>> {
    sqlx::query_as::<_, (String, DateTime<Utc>)>(
        "SELECT plano, periodo_fim FROM assinaturas
          WHERE user_id = $1 AND status = 'ACTIVE' AND periodo_fim > now()",
    )
    .bind(user_id)
    .fetch_optional(db)
    .await
    .map_err(AppError::from)
}

/// Guarda de rota paga baseada no claim do token — sem consultar o banco.
///
/// O claim é emitido a partir da tabela `assinaturas` e vale por 15 minutos;
/// a alternativa seria um `SELECT` em toda chamada de relatório/IA/cotação.
/// 402 (Payment Required), não 403: não é falta de permissão nem de login, é
/// falta de plano — e o app abre a tela de assinatura a partir deste código.
pub fn exigir_premium_do_token(auth: &AuthUser) -> AppResult<()> {
    if auth.premium {
        Ok(())
    } else {
        Err(AppError::PaymentRequired(ERRO_PLANO))
    }
}

/// `GET /billing/plano` — o que o usuário tem hoje e quanto custa.
pub async fn plano(
    State(state): State<AppState>,
    auth: AuthUser,
) -> AppResult<Json<PlanoResponse>> {
    let vigente = assinatura_vigente(&state.db, auth.user_id()).await?;
    Ok(Json(montar_plano(vigente)))
}

fn montar_plano(vigente: Option<(String, DateTime<Utc>)>) -> PlanoResponse {
    let (plano, periodo_fim) = match vigente {
        Some((p, fim)) => (Some(p), Some(fim)),
        None => (None, None),
    };
    PlanoResponse {
        premium: plano.is_some(),
        plano,
        periodo_fim,
        recursos_premium: RECURSOS_PREMIUM,
        preco_mensal: PRECO_MENSAL,
        preco_anual: PRECO_ANUAL,
        access_token: None,
    }
}

#[derive(Deserialize)]
pub struct AtivarRequest {
    pub codigo: String,
}

/// `POST /billing/ativar` — troca o código de ativação por período de acesso.
///
/// O código é de uso único e tem validade própria (30 dias a partir da
/// emissão). Renovar com o usuário já ativo **soma** ao período restante em
/// vez de reiniciar: quem renova no meio do mês não perde os dias que pagou.
pub async fn ativar(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(req): Json<AtivarRequest>,
) -> AppResult<Json<PlanoResponse>> {
    let codigo = req.codigo.trim().to_uppercase().replace(' ', "");
    if codigo.len() < 8 || codigo.len() > 40 {
        return Err(AppError::Validation(
            "código de ativação inválido".to_string(),
        ));
    }
    let hash = hash_codigo(&codigo);

    let mut tx = state.db.begin().await?;

    // `FOR UPDATE` trava o código: dois cliques no botão não podem ativar duas
    // vezes o mesmo código. A unique de `assinaturas` cobre o recibo repetido,
    // mas o lock evita a segunda requisição nem chegar ao upsert.
    let linha = sqlx::query_as::<_, (Uuid, String, i32, Option<DateTime<Utc>>)>(
        "SELECT id, plano, dias, usado_em FROM codigos_ativacao
          WHERE codigo_hash = $1 AND expira_em > now()
          FOR UPDATE",
    )
    .bind(&hash)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or_else(|| {
        AppError::Validation(
            "código inválido ou expirado — confira o e-mail da compra".to_string(),
        )
    })?;

    let (codigo_id, plano, dias, usado_em) = linha;
    if usado_em.is_some() {
        return Err(AppError::Validation(
            "este código já foi usado".to_string(),
        ));
    }

    sqlx::query(
        "UPDATE codigos_ativacao SET usado_em = now(), usado_por = $2 WHERE id = $1",
    )
    .bind(codigo_id)
    .bind(auth.user_id())
    .execute(&mut *tx)
    .await?;

    let periodo_fim = periodo_e_upsert(
        &mut tx,
        auth.user_id(),
        "codigo",
        &codigo_id.to_string(),
        &plano,
        i64::from(dias),
    )
    .await?;

    tx.commit().await?;

    // O direito acabou de mudar: devolve um access token novo para o app não
    // ficar com o token antigo (`premium = false`) até o próximo refresh.
    let mut resposta = montar_plano(Some((plano, periodo_fim)));
    resposta.access_token = Some(state.tokens.issue_access(auth.user_id(), true)?);
    Ok(Json(resposta))
}

/// Dias que cada plano compra. Fonte única: o código de ativação e o
/// pagamento pelo Mercado Pago não podem discordar sobre quanto dura o mês.
pub(crate) fn dias_do_plano(plano: &str) -> Option<i64> {
    match plano {
        "mensal" => Some(30),
        "anual" => Some(365),
        _ => None,
    }
}

fn valor_do_plano(plano: &str) -> Option<Decimal> {
    match plano {
        "mensal" => PRECO_MENSAL.parse().ok(),
        "anual" => PRECO_ANUAL.parse().ok(),
        _ => None,
    }
}

/// Grava o período comprado. Base = fim vigente (renovação antecipada não
/// perde dias) ou agora. Usada pelo código de ativação e pelo Mercado Pago:
/// duas versões disso divergiriam na primeira correção de preço.
async fn periodo_e_upsert(
    conn: &mut sqlx::PgConnection,
    user_id: Uuid,
    provider: &str,
    provider_sub_id: &str,
    plano: &str,
    dias: i64,
) -> AppResult<DateTime<Utc>> {
    let vigente = sqlx::query_as::<_, (String, DateTime<Utc>)>(
        "SELECT plano, periodo_fim FROM assinaturas
          WHERE user_id = $1 AND status = 'ACTIVE' AND periodo_fim > now()
          FOR UPDATE",
    )
    .bind(user_id)
    .fetch_optional(&mut *conn)
    .await?;

    let base = vigente
        .map(|(_, fim)| fim)
        .filter(|fim| *fim > Utc::now())
        .unwrap_or_else(Utc::now);
    let periodo_fim = base + Duration::days(dias);

    sqlx::query(
        "INSERT INTO assinaturas
             (user_id, provider, provider_sub_id, plano, status, periodo_fim)
         VALUES ($1, $2, $3, $4, 'ACTIVE', $5)
         ON CONFLICT (user_id) DO UPDATE SET
             provider = EXCLUDED.provider,
             provider_sub_id = EXCLUDED.provider_sub_id,
             plano = EXCLUDED.plano,
             status = 'ACTIVE',
             periodo_fim = EXCLUDED.periodo_fim,
             atualizado_em = now()",
    )
    .bind(user_id)
    .bind(provider)
    .bind(provider_sub_id)
    .bind(plano)
    .bind(periodo_fim)
    .execute(&mut *conn)
    .await?;

    Ok(periodo_fim)
}

/// Marca o checkout como pago e ativa o período — as duas coisas na mesma
/// transação. Quem chama é o Brick (pagamento aprovado na hora) e o webhook
/// (Pix aprovado depois); `concluido_em` é o que torna a segunda chamada inócua.
pub(crate) async fn concluir_checkout(
    db: &PgPool,
    checkout_id: Uuid,
    user_id: Uuid,
    plano: &str,
    pagamento_id: &str,
) -> AppResult<()> {
    let dias = dias_do_plano(plano).unwrap_or(30);

    let mut tx = db.begin().await?;
    let marcado = sqlx::query(
        "UPDATE premium_checkouts
            SET concluido_em = now(), mp_payment_id = $2
          WHERE id = $1 AND concluido_em IS NULL",
    )
    .bind(checkout_id)
    .bind(pagamento_id)
    .execute(&mut *tx)
    .await?;

    if marcado.rows_affected() == 0 {
        // Já concluído (webhook chegou depois do Brick): nada a fazer.
        tx.rollback().await?;
        return Ok(());
    }

    periodo_e_upsert(
        &mut tx,
        user_id,
        "mercadopago",
        pagamento_id,
        plano,
        dias,
    )
    .await?;

    tx.commit().await?;
    Ok(())
}

fn hash_codigo(codigo: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(codigo.as_bytes());
    format!("{:x}", hasher.finalize())
}

#[derive(Deserialize)]
pub struct GerarCodigoRequest {
    pub plano: String,
    /// Sobrescreve a duração padrão do plano. Serve para cortesia/renovação
    /// manual; o plano continua valendo para preço e rótulo.
    pub dias: Option<i32>,
}

#[derive(Serialize)]
pub struct CodigoResponse {
    /// O código cru aparece UMA vez. Depois disso só existe o hash no banco —
    /// se o admin perder, emite outro.
    pub codigo: String,
    pub plano: String,
    pub dias: i32,
    pub expira_em: DateTime<Utc>,
}

/// `POST /admin/assinaturas/codigos` — emite código de uso único.
///
/// Fica atrás do `X-Admin-Token` porque é receita: só o dono emite código.
pub async fn gerar_codigo(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<GerarCodigoRequest>,
) -> AppResult<Json<CodigoResponse>> {
    exigir_admin(&state, &headers)?;

    let plano = req.plano.trim().to_lowercase();
    let dias = req.dias.unwrap_or(match plano.as_str() {
        "mensal" => 30,
        "anual" => 365,
        _ => {
            return Err(AppError::Validation(
                "plano deve ser mensal ou anual".to_string(),
            ));
        }
    });
    if !(1..=400).contains(&dias) {
        return Err(AppError::Validation(
            "dias deve ficar entre 1 e 400".to_string(),
        ));
    }

    let codigo = gerar_codigo_legivel();
    let hash = hash_codigo(&codigo);
    let expira_em = Utc::now() + Duration::days(30);

    sqlx::query(
        "INSERT INTO codigos_ativacao (codigo_hash, plano, dias, expira_em)
         VALUES ($1, $2, $3, $4)",
    )
    .bind(&hash)
    .bind(&plano)
    .bind(dias)
    .bind(expira_em)
    .execute(&state.db)
    .await?;

    Ok(Json(CodigoResponse {
        codigo,
        plano,
        dias,
        expira_em,
    }))
}

/// `KPL-XXXX-XXXX-XXXX-XXXX`, alfabeto sem `0/O` e `1/I` — o código é lido de
/// e-mail e digitado à mão; caractere ambíguo é suporte garantido.
///
/// Usado pelo código de ativação e pelo de redefinição de senha: os dois saem
/// de e-mail e voltam digitados, então a regra de legibilidade é a mesma.
pub(crate) fn gerar_codigo_legivel() -> String {
    const ALFABETO: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    let bytes: [u8; 16] = rand::random();
    let mut out = String::with_capacity(23);
    out.push_str("KPL");
    for (i, b) in bytes.iter().enumerate() {
        if i % 4 == 0 {
            out.push('-');
        }
        out.push(ALFABETO[(*b as usize) % ALFABETO.len()] as char);
    }
    out
}

// ── Pagamento do premium pelo Mercado Pago ──────────────────────────────────
//
// O app abre `GET /assinar/{token}` num WebView; a página renderiza o Payment
// Brick com a NOSSA chave pública. O cartão é tokenizado no aparelho pelo SDK,
// então o número nunca passa por este backend — aqui chega só o token de
// pagamento. O valor, porém, NÃO vem do aparelho: é lido de
// `premium_checkouts`, igual ao resto do dinheiro do sistema.

#[derive(Deserialize)]
pub struct CheckoutRequest {
    pub plano: String,
}

#[derive(Serialize)]
pub struct CheckoutResponse {
    pub url: String,
}

struct Checkout {
    id: Uuid,
    user_id: Uuid,
    plano: String,
    valor: Decimal,
    email: String,
    concluido: bool,
}

/// `POST /billing/checkout` — devolve o link da tela de pagamento. Cada clique
/// cria um checkout novo: o anterior pode ter sido abandonado, e reaproveitar
/// link velho confunde quem paga duas vezes.
pub async fn criar_checkout(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(req): Json<CheckoutRequest>,
) -> AppResult<Json<CheckoutResponse>> {
    if state.mp.public_key().is_none() {
        return Err(AppError::Unavailable);
    }
    let plano = req.plano.trim().to_lowercase();
    let valor = valor_do_plano(&plano).ok_or_else(|| {
        AppError::Validation("plano deve ser mensal ou anual".to_string())
    })?;

    let (checkout_id, token) = sqlx::query_as::<_, (Uuid, String)>(
        "INSERT INTO premium_checkouts (user_id, plano, valor)
         VALUES ($1, $2, $3) RETURNING id, token",
    )
    .bind(auth.user_id())
    .bind(&plano)
    .bind(valor)
    .fetch_one(&state.db)
    .await?;

    // No modo de teste o Payment Brick não tem como cobrar: a API de pagamentos
    // recusa credencial de teste (401 "Unauthorized use of live credentials").
    // O Checkout Pro, esse, aceita cartão de teste — então no sandbox o app abre
    // o checkout do Mercado Pago em vez da página com o Brick. O webhook
    // `plano:<id>` libera o acesso do mesmo jeito.
    if state.mp.sandbox() {
        let preferencia = state
            .mp
            .criar_preferencia(
                &format!("plano:{checkout_id}"),
                &format!("KaveliPay Premium ({plano})"),
                valor,
                "BRL",
                &format!("{}/assinar/{token}", state.public_base_url),
                None,
                None,
            )
            .await?;
        return Ok(Json(CheckoutResponse {
            url: preferencia.init_point,
        }));
    }

    Ok(Json(CheckoutResponse {
        url: format!("{}/assinar/{token}", state.public_base_url),
    }))
}

/// Token de 128 bits do banco, 32 hex. Malformado e inexistente dão o mesmo
/// 404 — diferenciar transformaria a rota num oráculo de tokens.
async fn checkout_por_token(state: &AppState, token: &str) -> AppResult<Checkout> {
    let token = token.trim().to_ascii_lowercase();
    if token.len() != 32 || !token.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(AppError::NaoEncontrado("Assinatura não encontrada.".into()));
    }

    let row = sqlx::query_as::<_, (Uuid, Uuid, String, Decimal, String, bool)>(
        "SELECT c.id, c.user_id, c.plano, c.valor, u.email, c.concluido_em IS NOT NULL
           FROM premium_checkouts c
           JOIN users u ON u.id = c.user_id
          WHERE c.token = $1",
    )
    .bind(&token)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NaoEncontrado("Assinatura não encontrada.".into()))?;

    Ok(Checkout {
        id: row.0,
        user_id: row.1,
        plano: row.2,
        valor: row.3,
        email: row.4,
        concluido: row.5,
    })
}

const PAGINA_ASSINATURA: &str = r#"<!doctype html>
<html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<title>KaveliPay Premium</title>
<script src="https://sdk.mercadopago.com/js/v2"></script>
<style>
:root{color-scheme:light dark}
body{margin:0;min-height:100vh;background:#f5f5f7;
font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;color:#111}
main{background:#fff;max-width:440px;margin:0 auto;min-height:100vh;padding:24px 20px 32px;
box-sizing:border-box}
h1{font-size:15px;font-weight:600;color:#666;margin:0 0 4px;text-align:center}
.valor{font-size:34px;font-weight:700;margin:6px 0 2px;letter-spacing:-1px;text-align:center}
.desc{font-size:15px;color:#666;margin:0 0 20px;text-align:center}
#ok{margin-top:24px;padding:16px;border-radius:14px;background:#e8f6ee;color:#1a6b3c;text-align:center}
#ok p{margin:0 0 14px;font-size:15px}
.voltar{display:block;padding:15px;border-radius:14px;background:#7b2ff7;color:#fff;
text-decoration:none;font-weight:600}
.rodape{margin-top:22px;font-size:12px;color:#999;text-align:center}
@media(prefers-color-scheme:dark){body{background:#0e0e10;color:#f2f2f2}
main{background:#111114}#ok{background:#15301f;color:#8fdcae}}
</style></head><body><main>
<h1>KaveliPay Premium</h1>
<p class="valor">R$ __VALOR__</p>
<p class="desc">Plano __PLANO__</p>
<div id="brick"></div>
<div id="ok" hidden>
<p>Pagamento confirmado. O Premium já está liberado.</p>
<a class="voltar" href="kavelipay://premium/ok">Voltar ao app</a>
</div>
<p class="rodape">Pagamento processado pelo Mercado Pago.</p>
</main>
<script>
const mp = new MercadoPago("__CHAVE__", {locale: "pt-BR"});
mp.bricks().create("payment", "brick", {
  initialization: {amount: __VALOR_NUM__, payer: {email: "__EMAIL__"}},
  customization: {paymentMethods: {creditCard: "all", debitCard: "all", pix: "all"}},
  callbacks: {
    onSubmit: function (dados) {
      return fetch("__PAGAR__", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify(dados.formData),
      }).then(function (r) {
        return r.json().then(function (j) {
          if (!r.ok) throw new Error(j.erro || "Não deu para pagar agora.");
          if (j.status === "approved") {
            document.getElementById("brick").hidden = true;
            document.getElementById("ok").hidden = false;
          }
          return j;
        });
      });
    },
  },
});
</script></body></html>"#;

/// `GET /assinar/{token}` — tela de pagamento do premium. Pública: o token é
/// o segredo. Quem já pagou vê o aviso em vez do formulário — a mesma página
/// nunca oferece cobrança repetida.
pub async fn pagina_assinatura(
    State(state): State<AppState>,
    Path(token): Path<String>,
) -> AppResult<Html<String>> {
    let chave = state.mp.public_key().ok_or(AppError::Unavailable)?;
    let c = checkout_por_token(&state, &token).await?;

    if c.concluido {
        return Ok(crate::routes::charges::pagina(
            "KAVELIPAY PREMIUM",
            c.valor,
            &format!("Plano {}", c.plano),
            None,
            "",
            "<p class=\"aviso ok\">Pagamento confirmado.</p>",
        ));
    }

    let valor_num = c
        .valor
        .to_string()
        .parse::<f64>()
        .map_err(|e| AppError::InvalidAmount(format!("valor inválido: {e}")))?;

    Ok(Html(
        PAGINA_ASSINATURA
            .replace("__VALOR__", &format!("{:.2}", c.valor).replace('.', ","))
            .replace("__VALOR_NUM__", &valor_num.to_string())
            .replace("__PLANO__", &escapar(&c.plano))
            .replace("__CHAVE__", &escapar(chave))
            .replace("__EMAIL__", &escapar(&c.email))
            .replace("__PAGAR__", &format!("/assinar/{token}/pagar")),
    ))
}

/// `POST /assinar/{token}/pagar` — recebe o `formData` do Brick (já com o
/// cartão tokenizado), cria o pagamento na API do Mercado Pago e, se ele veio
/// aprovado, libera o período na hora. Devolve o JSON cru porque é ele que o
/// Brick usa para desenhar o resultado (Pix inclusive).
pub async fn pagar_assinatura(
    State(state): State<AppState>,
    Path(token): Path<String>,
    Json(mut corpo): Json<serde_json::Value>,
) -> AppResult<Json<serde_json::Value>> {
    let c = checkout_por_token(&state, &token).await?;
    if c.concluido {
        return Err(AppError::Conflict(
            "este pagamento já foi concluído".to_string(),
        ));
    }

    // Fronteira de confiança: o aparelho manda o meio de pagamento, nunca o
    // preço nem a quem o dinheiro se refere.
    corpo["transaction_amount"] = serde_json::json!(
        c.valor
            .to_string()
            .parse::<f64>()
            .map_err(|e| AppError::InvalidAmount(format!("valor inválido: {e}")))?
    );
    corpo["description"] = serde_json::json!(format!("KaveliPay Premium ({})", c.plano));
    corpo["external_reference"] = serde_json::json!(format!("plano:{}", c.id));
    corpo["payer"]["email"] = serde_json::json!(c.email);

    let pago = state.mp.criar_pagamento(corpo).await?;

    if pago.get("status").and_then(|v| v.as_str()) == Some("approved") {
        let pagamento_id = pago
            .get("id")
            .map(|v| v.to_string().trim_matches('"').to_string())
            .unwrap_or_default();
        concluir_checkout(&state.db, c.id, c.user_id, &c.plano, &pagamento_id).await?;
    }

    Ok(Json(pago))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codigo_tem_formato_legivel() {
        let c = gerar_codigo_legivel();
        assert_eq!(c.len(), 23);
        assert!(c.starts_with("KPL-"));
        assert_eq!(c.matches('-').count(), 4);
        assert!(c
            .chars()
            .filter(|c| *c != '-')
            .all(|c| c.is_ascii_uppercase() || c.is_ascii_digit()));
    }

    #[test]
    fn codigos_diferentes_nao_colidem() {
        let a = gerar_codigo_legivel();
        let b = gerar_codigo_legivel();
        assert_ne!(a, b);
    }

    #[test]
    fn hash_e_estavel_e_nao_guarda_o_codigo() {
        let h = hash_codigo("KPL-ABCD-EFGH-JKLM-NPQR");
        assert_eq!(h.len(), 64);
        assert!(!h.contains("ABCD"));
        assert_eq!(h, hash_codigo("KPL-ABCD-EFGH-JKLM-NPQR"));
    }
}

/// GET / — página de apresentação do produto.
///
/// Existe por um motivo burocrático: o Mercado Pago exige a URL de um site
/// para liberar as credenciais de produção. Sem esta página, o cadastro pede
/// um endereço e não há o que informar. Também é o mínimo que alguém espera
/// ao encontrar o domínio: o que é isto, para quem é, com quem falar.
pub async fn raiz() -> Html<String> {
    Html(format!(
        r#"<!doctype html>
<html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>KaveliPay · seu dinheiro do mês, sem planilha</title>
<meta name="description" content="Organize saldo, contas a pagar, cobranças e divisões de conta num app Android só para pessoa física. Não é banco, não guarda dinheiro.">
<style>
:root{{color-scheme:light dark}}
body{{margin:0;background:#f5f5f7;color:#111;
font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;line-height:1.5}}
main{{max-width:640px;margin:0 auto;padding:56px 24px}}
h1{{font-size:34px;letter-spacing:-1px;margin:0 0 8px}}
.tag{{font-size:19px;color:#555;margin:0 0 32px}}
h2{{font-size:16px;text-transform:uppercase;letter-spacing:.08em;color:#666;margin:36px 0 12px}}
ul{{margin:0;padding-left:20px}} li{{margin:6px 0}}
.card{{background:#fff;border-radius:18px;padding:24px;margin:24px 0;
box-shadow:0 6px 24px rgba(0,0,0,.06)}}
a{{color:#7b2ff7}}
footer{{margin-top:40px;font-size:13px;color:#888}}
@media(prefers-color-scheme:dark){{body{{background:#0e0e10;color:#f2f2f2}}
.card{{background:#1b1b1f;box-shadow:none}} .tag,h2,footer{{color:#a1a1aa}}}}
</style></head>
<body><main>
<h1>KaveliPay</h1>
<p class="tag">Seu dinheiro do mês, sem planilha.</p>

<p>App Android de organização financeira para <strong>pessoa física</strong>: saldo,
contas a pagar, cobranças e — o que mais falta nos outros — dividir a conta
com quem estava junto.</p>

<div class="card">
<h2>Aplicativo</h2>
<ul>
<li>Saldo e extrato que fecham sempre (as contas são um livro-razão, não um número que alguém digita)</li>
<li>Contas a pagar, com vencimento e alerta</li>
<li>Dividir uma despesa e mandar o link de pagamento para cada pessoa</li>
<li>Relatório mensal por categoria</li>
</ul>
<p style="margin-bottom:0"><a href="https://kavelipay-backend.onrender.com/health">Status do serviço</a></p>
</div>

<div class="card">
<h2>O que o KaveliPay não é</h2>
<p style="margin-bottom:0">Não é banco, não é financeira, não é provedor de PIX e
<strong>não guarda dinheiro de ninguém</strong>. O saldo mostrado é demonstrativo,
calculado a partir dos lançamentos. Pagamentos são processados pelo Mercado Pago,
direto na conta de quem cobrou.</p>
</div>

<footer>
Dúvidas, suporte e privacidade: <a href="mailto:{email}">{email}</a><br>
KaveliPay · feito no Brasil
</footer>
</main></body></html>"#,
        email = "josiassm701@gmail.com"
    ))
}
