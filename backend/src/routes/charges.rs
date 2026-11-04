//! Cobranças: pedir dinheiro a outra pessoa, gerar QR Code, receber.
//!
//! O KaveliPay NÃO é provedor de PIX nem processador. O QR gerado aqui é um
//! deep link do próprio app (`kavelipay://cobranca/<token>`), não um BR Code/EMV
//! — ver `payment::DemoPaymentProvider`. O token é o segredo de
//! compartilhamento (128 bits), não o id da cobrança.
//!
//! Uma cobrança tem duas pernas contábeis e elas NÃO podem ser confundidas:
//! cobrar registra um direito a receber (a carteira não muda); receber move o
//! dinheiro de fato. Uma rota que fizesse as duas de uma vez apareceria como
//! "ganhei R$ 50" ao simplesmente pedir.

use axum::Json;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{Html, Redirect};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::{AppError, AppResult};
use crate::ledger::{self, TransactionType};
use crate::money::Money;
use rust_decimal::Decimal;
use crate::payment::{DemoPaymentProvider, PaymentPayload, PaymentProvider};
use crate::state::wallet_ativa;
use crate::state::AppState;

#[derive(Deserialize)]
pub struct NewCharge {
    pub description: String,
    pub amount: Money,
    pub debtor_name: Option<String>,
    pub due_date: Option<String>,
}

#[derive(Serialize)]
pub struct ChargeResponse {
    pub id: Uuid,
    pub description: String,
    pub amount: Money,
    pub currency: String,
    pub debtor_name: Option<String>,
    pub due_date: Option<chrono::NaiveDate>,
    /// PENDING | RECEIVED | CANCELED
    pub status: String,
    pub received_at: Option<chrono::DateTime<chrono::Utc>>,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Serialize)]
pub struct ChargeWithQr {
    #[serde(flatten)]
    pub charge: ChargeResponse,
    pub qr: PaymentPayload,
}

#[derive(Deserialize)]
pub struct ChargeQuery {
    pub status: Option<String>,
    pub limit: Option<i64>,
    pub offset: Option<i64>,
}

/// POST /charges
///
/// Cria a cobrança e devolve o QR. **Não gera lançamento no ledger**: pedir
/// dinheiro não é receber dinheiro. O valor a receber é o somatório das
/// cobranças PENDING; o primeiro e único toque na carteira é em
/// `POST /charges/{id}/receive`.
pub async fn create_charge(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Json(req): Json<NewCharge>,
) -> AppResult<(StatusCode, Json<ChargeWithQr>)> {
    let description = req.description.trim();
    if description.is_empty() {
        return Err(AppError::Validation("descrição é obrigatória".into()));
    }
    if !req.amount.is_positive() {
        return Err(AppError::Validation("valor deve ser maior que zero".into()));
    }

    let due_date = req
        .due_date
        .as_deref()
        .map(str::trim)
        .map(|d| {
            chrono::NaiveDate::parse_from_str(d, "%Y-%m-%d")
                .map_err(|_| AppError::Validation("due_date deve ser YYYY-MM-DD".into()))
        })
        .transpose()?;

    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    // O token vem do banco (DEFAULT da migration 0010), não do app: é o
    // segredo que vai no QR e a única coisa que quem recebe o link carrega.
    let (id, share_token) = sqlx::query_as::<_, (Uuid, String)>(
        "INSERT INTO charges (wallet_id, description, amount, due_date, debtor_name)
         SELECT w.id, $2, $3, $4, $5 FROM wallets w WHERE w.id = $1
         RETURNING id, share_token",
    )
    .bind(wallet)
    .bind(description)
    .bind(req.amount.amount())
    .bind(due_date)
    .bind(req.debtor_name.as_deref())
    .fetch_one(&state.db)
    .await?;

    let charge = buscar(&state, id, wallet).await?;
    let qr = DemoPaymentProvider.qr_payload(&share_token);

    Ok((StatusCode::CREATED, Json(ChargeWithQr { charge, qr })))
}

/// GET /charges
pub async fn list_charges(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Query(q): Query<ChargeQuery>,
) -> AppResult<Json<Vec<ChargeResponse>>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    if let Some(s) = q.status.as_deref()
        && !matches!(s, "PENDING" | "RECEIVED" | "CANCELED")
    {
        return Err(AppError::Validation(format!(
            "status inválido: '{s}'. Use PENDING, RECEIVED ou CANCELED."
        )));
    }

    let linhas = sqlx::query_as::<_, (
        Uuid,
        String,
        rust_decimal::Decimal,
        String,
        Option<String>,
        Option<chrono::NaiveDate>,
        String,
        Option<chrono::DateTime<chrono::Utc>>,
        chrono::DateTime<chrono::Utc>,
    )>(
        "SELECT id, description, amount, currency, debtor_name, due_date,
                status, received_at, created_at
           FROM charges
          WHERE wallet_id = $1
            AND ($2::text IS NULL OR status = $2)
          ORDER BY created_at DESC
          LIMIT $3 OFFSET $4",
    )
    .bind(wallet)
    .bind(q.status.as_deref())
    .bind(q.limit.unwrap_or(50).clamp(1, 200))
    .bind(q.offset.unwrap_or(0).max(0))
    .fetch_all(&state.db)
    .await?;

    Ok(Json(linhas.into_iter().map(convert).collect()))
}

/// GET /charges/{id}/qr
///
/// Só PENDING e CANCELED. Um QR de cobrança já recebida levaria alguém a pagar
/// de novo — o pior tipo de erro numa cobrança.
pub async fn charge_qr(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
) -> AppResult<Json<QrComLink>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;
    let charge = buscar(&state, id, wallet).await?;

    if charge.status == "RECEIVED" {
        return Err(AppError::Conflict(
            "cobrança já recebida; QR não deve ser exibido".into(),
        ));
    }

    let token = token_do_qr(&state, id, wallet).await?;
    Ok(Json(QrComLink {
        link: link_publico(&state, &token),
        qr: DemoPaymentProvider.qr_payload(&token),
    }))
}

/// QR do app **mais** o link https que qualquer pessoa abre no navegador.
/// O `payload` continua sendo deep link `kavelipay://` (a spec proíbe fingir
/// BR Code/PIX); o `link` é a página de pagamento pública.
#[derive(Serialize)]
pub struct QrComLink {
    #[serde(flatten)]
    pub qr: PaymentPayload,
    pub link: String,
}

fn link_publico(state: &AppState, token: &str) -> String {
    format!("{}/cobranca/{token}/pagar", state.public_base_url)
}

/// Lê o token de compartilhamento da cobrança. Só o dono chega aqui: o id vem
/// da URL de uma rota autenticada e `buscar()` já filtrou por carteira.
async fn token_do_qr(state: &AppState, id: Uuid, wallet: Uuid) -> AppResult<String> {
    sqlx::query_scalar::<_, String>(
        "SELECT share_token FROM charges WHERE id = $1 AND wallet_id = $2",
    )
    .bind(id)
    .bind(wallet)
    .fetch_one(&state.db)
    .await
    .map_err(AppError::from)
}

/// O que quem recebeu o link vê. Não é a cobrança inteira: descrição, valor,
/// status e quem cobra são o suficiente para entender o pedido.
#[derive(Serialize)]
pub struct CobrancaPublica {
    pub description: String,
    pub amount: Money,
    pub currency: String,
    /// PENDING | RECEIVED | CANCELED.
    pub status: String,
    pub due_date: Option<chrono::NaiveDate>,
    /// Nome de quem cobra, se preenchido no perfil. Sem nome o app mostra só
    /// "Cobrança" — melhor que inventar identificação.
    pub cobrador: Option<String>,
}

/// GET /cobranca/{token} — **pública**, sem sessão.
///
/// É o endereço que o QR da cobrança carrega. O token é segredo de 128 bits
/// gerado pelo banco: quem recebeu o link vê a cobrança; quem não recebeu não
/// vê nada. Token malformado e token inexistente devolvem o **mesmo** 404 —
/// diferenciar os dois transformaria a rota num oráculo para adivinhar tokens.
///
/// A resposta não inclui wallet, id da cobrança, nome do devedor nem
/// histórico: o link é público e não leva a carteira inteira junto. O valor
/// vem daqui, nunca do link — é a fronteira de confiança da cobrança.
pub async fn cobranca_publica(
    State(state): State<AppState>,
    Path(token): Path<String>,
) -> AppResult<Json<CobrancaPublica>> {
    let c = por_token(&state, &token).await?;
    Ok(Json(CobrancaPublica {
        description: c.description,
        amount: Money::new(c.amount),
        currency: c.currency,
        status: c.status,
        due_date: c.due_date,
        cobrador: c.cobrador,
    }))
}

/// Cobrança vista pelo link público. O `id` fica fora da resposta JSON — só a
/// página precisa dele para gerar a preferência.
struct Publica {
    id: Uuid,
    description: String,
    amount: Decimal,
    currency: String,
    status: String,
    due_date: Option<chrono::NaiveDate>,
    cobrador: Option<String>,
}

/// Token de 128 bits gerado pelo banco: 32 hex. Malformado e inexistente dão o
/// mesmo 404 — diferenciar tornaria a rota um oráculo para adivinhar tokens.
fn normalizar_token(token: &str) -> AppResult<String> {
    let token = token.trim().to_ascii_lowercase();
    if token.len() != 32 || !token.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(AppError::NotFound("cobrança"));
    }
    Ok(token)
}

async fn por_token(state: &AppState, token: &str) -> AppResult<Publica> {
    let token = normalizar_token(token)?;
    let row = sqlx::query_as::<_, (
        Uuid,
        String,
        Decimal,
        String,
        String,
        Option<chrono::NaiveDate>,
        Option<String>,
    )>(
        "SELECT c.id, c.description, c.amount, c.currency, c.status, c.due_date, u.display_name
           FROM charges c
           JOIN wallets w ON w.id = c.wallet_id
           JOIN users u ON u.id = w.user_id
          WHERE c.share_token = $1",
    )
    .bind(&token)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NotFound("cobrança"))?;

    Ok(Publica {
        id: row.0,
        description: row.1,
        amount: row.2,
        currency: row.3,
        status: row.4,
        due_date: row.5,
        cobrador: row.6.filter(|n| !n.trim().is_empty()),
    })
}

/// Escapa texto de usuário antes de entrar no HTML. A descrição da cobrança é
/// livre, então sem isso o link público viraria XSS refletido.
pub(crate) fn escapar(texto: &str) -> String {
    texto
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&#39;")
}

/// Esqueleto da página pública. Cobrança e parte de divisão mostram a mesma
/// coisa para quem paga — valor, do que é, de quem — então o HTML mora aqui.
pub(crate) fn pagina(
    titulo: &str,
    valor: Decimal,
    descricao: &str,
    cobrador: Option<&str>,
    nota: &str,
    acao: &str,
) -> Html<String> {
    let quem = match cobrador.filter(|n| !n.trim().is_empty()) {
        Some(n) => format!("<p class=\"quem\">Cobrança de {}</p>", escapar(n)),
        None => String::new(),
    };

    Html(format!(
        r#"<!doctype html>
<html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{titulo} · KaveliPay</title>
<style>
:root{{color-scheme:light dark}}
body{{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
background:#f5f5f7;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;color:#111}}
main{{background:#fff;border-radius:20px;padding:32px 24px;max-width:380px;width:100%;
box-shadow:0 8px 30px rgba(0,0,0,.08);text-align:center}}
h1{{font-size:16px;font-weight:600;color:#666;margin:0 0 4px}}
.valor{{font-size:40px;font-weight:700;margin:8px 0 4px;letter-spacing:-1px}}
.desc{{font-size:17px;margin:0 0 20px}}
.quem,.pe{{font-size:14px;color:#666;margin:6px 0}}
.aviso{{margin-top:20px;padding:14px;border-radius:12px;background:#fff4e5;color:#8a5300;font-size:15px}}
.aviso.ok{{background:#e8f6ee;color:#1a6b3c}}
button{{width:100%;margin-top:20px;padding:16px;border:0;border-radius:14px;background:#7b2ff7;
color:#fff;font-size:17px;font-weight:600;cursor:pointer}}
.rodape{{margin-top:22px;font-size:12px;color:#999}}
@media(prefers-color-scheme:dark){{body{{background:#0e0e10;color:#f2f2f2}}
main{{background:#1b1b1f;box-shadow:none}}.aviso{{background:#3a2b12;color:#f0c27a}}
.aviso.ok{{background:#15301f;color:#8fdcae}}}}
</style></head>
<body><main>
<h1>{titulo}</h1>
<p class="valor">R$ {valor}</p>
<p class="desc">{desc}</p>
{quem}{nota}
{acao}
<p class="rodape">KaveliPay · não é banco, não guarda dinheiro</p>
</main></body></html>"#,
        titulo = escapar(titulo),
        valor = format!("{:.2}", valor).replace('.', ","),
        desc = escapar(descricao),
        quem = quem,
        nota = nota,
        acao = acao,
    ))
}

/// GET /cobranca/{token}/pagar — página pública de pagamento.
///
/// É o endereço que se manda por WhatsApp: abre no navegador, sem app e sem
/// conta. O botão só cria a preferência do Checkout Pro e manda para o Mercado
/// Pago; quem confirma o recebimento continua sendo o webhook.
pub async fn pagina_pagamento(
    State(state): State<AppState>,
    Path(token): Path<String>,
) -> AppResult<Html<String>> {
    let c = por_token(&state, &token).await?;

    let acao = if c.status == "PENDING" {
        "<form method=\"post\"><button type=\"submit\">Pagar com Mercado Pago</button></form>
         <p class=\"pe\">Pix ou cartão, na página do Mercado Pago.</p>"
    } else {
        // Reabrir o link de uma cobrança já resolvida não deve oferecer pagamento
        // de novo — o pior erro possível numa cobrança é cobrar duas vezes.
        match c.status.as_str() {
            "RECEIVED" => "<p class=\"aviso ok\">Esta cobrança já foi paga.</p>",
            _ => "<p class=\"aviso\">Esta cobrança foi cancelada.</p>",
        }
    };

    let nota = match c.due_date {
        Some(d) => format!("<p class=\"pe\">Vence em {}</p>", d.format("%d/%m/%Y")),
        None => String::new(),
    };

    Ok(pagina(
        "COBRANÇA",
        c.amount,
        &c.description,
        c.cobrador.as_deref(),
        &nota,
        acao,
    ))
}

/// POST /cobranca/{token}/pagar — cria (ou reaproveita) a preferência e manda
/// o pagador para o Checkout Pro. Público: quem paga não tem conta nem sessão.
pub async fn pagar_pela_pagina(
    State(state): State<AppState>,
    Path(token): Path<String>,
) -> AppResult<Redirect> {
    let token = normalizar_token(&token)?;
    let c = por_token(&state, &token).await?;
    if c.status != "PENDING" {
        return Ok(Redirect::to(&format!("/cobranca/{token}/pagar")));
    }

    // Reaproveita a preferência já gerada: dois cliques no botão não devem
    // criar duas preferências para a mesma cobrança.
    let existente = sqlx::query_scalar::<_, Option<String>>(
        "SELECT mp_preference_id FROM charges WHERE id = $1",
    )
    .bind(c.id)
    .fetch_one(&state.db)
    .await?;
    if let Some(id) = existente {
        return Ok(Redirect::to(&init_point(&id)));
    }

    let preferencia = state
        .mp
        .criar_preferencia(
            &c.id.to_string(),
            &c.description,
            c.amount,
            &c.currency,
            &format!("kavelipay://cobranca/{token}"),
        )
        .await?;

    sqlx::query(
        "UPDATE charges SET mp_preference_id = $2, updated_at = now() WHERE id = $1",
    )
    .bind(c.id)
    .bind(&preferencia.id)
    .execute(&state.db)
    .await?;

    Ok(Redirect::to(&preferencia.init_point))
}

/// URL do Checkout Pro a partir da preferência. Usada quando a preferência já
/// existe (o `init_point` só é devolvido na criação).
pub(crate) fn init_point(preferencia: &str) -> String {
    format!("https://www.mercadopago.com.br/checkout/v1/redirect?pref_id={preferencia}")
}

/// POST /charges/{id}/receive
///
/// Registra que a cobrança foi recebida. **Isto não confirma um pagamento** —
/// o usuário está declareando que recebeu, exatamente como no `bills/{id}/pay`.
/// Não há integração, webhook ou polling: nada no sistema pode confirmar por
/// conta própria, e é por isso que `DemoPaymentProvider::moves_real_money()`
/// é `false`.
pub async fn receive_charge(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
) -> AppResult<Json<ChargeResponse>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    let mut tx = state.db.begin().await?;

    // FOR UPDATE serializa dois recebimentos concorrentes — o segundo espera
    // o COMMIT do primeiro, enxerga RECEIVED e é recusado antes de postar.
    let pendente = sqlx::query_as::<_, (String, Decimal, String)>(
        "SELECT status, amount, description
           FROM charges
          WHERE id = $1 AND wallet_id = $2
          FOR UPDATE",
    )
    .bind(id)
    .bind(wallet)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or(AppError::NotFound("cobrança"))?;

    let (status, amount, description) = pendente;
    if status != "PENDING" {
        return Err(AppError::Conflict(format!(
            "cobrança já está {}; só PENDING pode ser recebida",
            status
        )));
    }

    let transaction_id = ledger::postar_em_com_categoria(
        &mut tx,
        wallet,
        TransactionType::ChargeReceived,
        Money::new(amount),
        Some(&format!("Recebimento: {}", description)),
        Some(serde_json::json!({ "charge_id": id })),
        None,
        Some(auth.user_id()),
    )
    .await?;

    let rows = sqlx::query(
        "UPDATE charges
            SET status = 'RECEIVED', received_at = now(),
                received_transaction_id = $3, updated_at = now()
          WHERE id = $1 AND wallet_id = $2 AND status = 'PENDING'",
    )
    .bind(id)
    .bind(wallet)
    .bind(transaction_id)
    .execute(&mut *tx)
    .await?;

    if rows.rows_affected() == 0 {
        // Corrida defensiva: desfaz TUDO — o ledger não pode ficar com crédito
        // órfão de uma cobrança não marcada como recebida.
        tracing::warn!(charge_id = %id, "recebimento concorrente; transação desfeita");
        tx.rollback().await?;
        return Err(AppError::Conflict("cobrança já foi recebida".into()));
    }

    tx.commit().await?;

    buscar(&state, id, wallet).await.map(Json)
}

/// DELETE /charges/{id} — cancela. Só PENDING.
pub async fn cancel_charge(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
) -> AppResult<Json<ChargeResponse>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    let rows = sqlx::query(
        "UPDATE charges SET status = 'CANCELED', updated_at = now()
          WHERE id = $1 AND wallet_id = $2 AND status = 'PENDING'",
    )
    .bind(id)
    .bind(wallet)
    .execute(&state.db)
    .await?;

    if rows.rows_affected() == 0 {
        return Err(AppError::Conflict("cobrança não está pendente".into()));
    }

    buscar(&state, id, wallet).await.map(Json)
}

// ── helpers ──────────────────────────────────────────────────────────────────

type ChargeRow = (
    Uuid,
    String,
    rust_decimal::Decimal,
    String,
    Option<String>,
    Option<chrono::NaiveDate>,
    String,
    Option<chrono::DateTime<chrono::Utc>>,
    chrono::DateTime<chrono::Utc>,
);

async fn buscar(state: &AppState, id: Uuid, wallet: Uuid) -> AppResult<ChargeResponse> {
    let row = sqlx::query_as::<_, ChargeRow>(
        "SELECT id, description, amount, currency, debtor_name, due_date,
                status, received_at, created_at
           FROM charges WHERE id = $1 AND wallet_id = $2",
    )
    .bind(id)
    .bind(wallet)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NotFound("cobrança"))?;

    Ok(convert(row))
}

fn convert(r: ChargeRow) -> ChargeResponse {
    ChargeResponse {
        id: r.0,
        description: r.1,
        amount: Money::new(r.2),
        currency: r.3,
        debtor_name: r.4,
        due_date: r.5,
        status: r.6,
        received_at: r.7,
        created_at: r.8,
    }
}
/// Resposta de `POST /charges/{id}/pagamento`.
#[derive(Serialize)]
pub struct LinkPagamento {
    pub preference_id: String,
    /// Página do Checkout Pro. O app abre no navegador externo.
    pub init_point: String,
}

/// POST /charges/{id}/pagamento — cria o link do Checkout Pro.
///
/// Isto NÃO marca nada como pago: só gera a página de pagamento. A confirmação
/// vem do webhook, que lê o status na API do Mercado Pago. Um botão que
/// marcasse "recebido" aqui seria exatamente o "fingir pagamento" que a spec
/// proíbe.
pub async fn link_pagamento(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
) -> AppResult<Json<LinkPagamento>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    let linha = sqlx::query_as::<_, (String, Decimal, String, String, Option<String>)>(
        "SELECT status, amount, description, currency, share_token
           FROM charges WHERE id = $1 AND wallet_id = $2",
    )
    .bind(id)
    .bind(wallet)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NotFound("cobrança"))?;

    let (status, amount, description, currency, share_token) = linha;
    if status != "PENDING" {
        return Err(AppError::Conflict(format!(
            "cobrança já está {status}; só PENDING pode ser paga"
        )));
    }
    // Toda cobrança nasce com token (migration 0010); sem ele não há link de
    // volta para o app, então é erro de dado, não do usuário.
    let token = share_token
        .ok_or_else(|| AppError::Internal(anyhow::anyhow!("cobrança {id} sem share_token")))?;

    let preferencia = state
        .mp
        .criar_preferencia(
            &id.to_string(),
            &description,
            amount,
            &currency,
            &format!("kavelipay://cobranca/{token}"),
        )
        .await?;

    sqlx::query(
        "UPDATE charges SET mp_preference_id = $3, updated_at = now()
          WHERE id = $1 AND wallet_id = $2",
    )
    .bind(id)
    .bind(wallet)
    .bind(&preferencia.id)
    .execute(&state.db)
    .await?;

    Ok(Json(LinkPagamento {
        preference_id: preferencia.id,
        init_point: preferencia.init_point,
    }))
}

/// POST /webhooks/mercadopago — o Mercado Pago avisa que o pagamento mudou.
///
/// Público (o Mercado Pago é quem chama, não tem sessão). A confiança NÃO vem
/// do corpo: qualquer um pode postar `{"type":"payment","data":{"id":"1"}}`.
/// Vem de consultar o pagamento na API deles com o nosso token — só pagamento
/// que existe lá, `approved`, apontando para uma cobrança nossa, credita.
///
/// Sempre 200 quando não há o que fazer: status != 200 faz o Mercado Pago
/// reenviar a mesma notificação por horas.
pub async fn webhook(
    State(state): State<AppState>,
    Json(corpo): Json<serde_json::Value>,
) -> AppResult<StatusCode> {
    if corpo.get("type").and_then(|v| v.as_str()) != Some("payment") {
        return Ok(StatusCode::OK);
    }
    let Some(pagamento_id) = corpo.pointer("/data/id").and_then(|v| v.as_str()) else {
        return Ok(StatusCode::OK);
    };

    let pagamento = state.mp.pagamento(pagamento_id).await?;
    if pagamento.status != "approved" {
        return Ok(StatusCode::OK);
    }
    let referencia = pagamento.external_reference.as_deref().unwrap_or_default();

    // Cota de divisão (`parte:<uuid>`): mesma confiança da cobrança — quem
    // confirma é a consulta ao Mercado Pago, não o clique do dono.
    if let Some(resto) = referencia.strip_prefix("parte:") {
        let Ok(parte_id) = resto.parse::<Uuid>() else {
            return Ok(StatusCode::OK);
        };
        let mut tx = state.db.begin().await?;
        let linha = sqlx::query_as::<_, (String, Decimal, bool, Uuid, Uuid, Option<String>, Uuid)>(
            "SELECT p.participant_name, p.owed_amount, p.settled_at IS NOT NULL,
                    p.split_id, w.id, p.mp_payment_id, w.user_id
               FROM expense_split_parts p
               JOIN expense_splits s ON s.id = p.split_id
               JOIN wallets w ON w.id = s.wallet_id
              WHERE p.id = $1
              FOR UPDATE OF p",
        )
        .bind(parte_id)
        .fetch_optional(&mut *tx)
        .await?;

        let Some((nome, valor, quitada, split_id, wallet, ja_pago, user_id)) = linha else {
            return Ok(StatusCode::OK);
        };
        if quitada || ja_pago.is_some() {
            return Ok(StatusCode::OK);
        }

        // Conflito aqui é corrida com outra quitação: o ledger já foi desfeito
        // pelo rollback, e responder 200 evita o MP reenviar por horas.
        if crate::routes::splits::quitar_parte(
            &mut tx,
            wallet,
            split_id,
            parte_id,
            &nome,
            valor,
            user_id,
            Some(pagamento_id),
        )
        .await
        .is_err()
        {
            tx.rollback().await?;
            return Ok(StatusCode::OK);
        }

        tx.commit().await?;
        return Ok(StatusCode::OK);
    }

    let Ok(charge_id) = referencia.parse::<Uuid>() else {
        return Ok(StatusCode::OK);
    };

    let mut tx = state.db.begin().await?;
    // FOR UPDATE OF c: dois webhooks do mesmo pagamento não postam duas vezes.
    let linha = sqlx::query_as::<_, (String, Decimal, String, Uuid, Uuid, Option<String>)>(
        "SELECT c.status, c.amount, c.description, c.wallet_id, w.user_id, c.mp_payment_id
           FROM charges c
           JOIN wallets w ON w.id = c.wallet_id
          WHERE c.id = $1
          FOR UPDATE OF c",
    )
    .bind(charge_id)
    .fetch_optional(&mut *tx)
    .await?;

    let Some((status, amount, description, wallet, user_id, ja_pago)) = linha else {
        return Ok(StatusCode::OK);
    };
    if status != "PENDING" || ja_pago.is_some() {
        return Ok(StatusCode::OK);
    }

    let transaction_id = ledger::postar_em_com_categoria(
        &mut tx,
        wallet,
        TransactionType::ChargeReceived,
        Money::new(amount),
        Some(&format!("Recebimento: {description}")),
        Some(serde_json::json!({ "charge_id": charge_id, "mp_payment_id": pagamento_id })),
        None,
        Some(user_id),
    )
    .await?;

    let rows = sqlx::query(
        "UPDATE charges
            SET status = 'RECEIVED', received_at = now(), received_transaction_id = $3,
                mp_payment_id = $4, updated_at = now()
          WHERE id = $1 AND wallet_id = $2 AND status = 'PENDING'",
    )
    .bind(charge_id)
    .bind(wallet)
    .bind(transaction_id)
    .bind(pagamento_id)
    .execute(&mut *tx)
    .await?;

    if rows.rows_affected() == 0 {
        tx.rollback().await?;
        return Ok(StatusCode::OK);
    }

    tx.commit().await?;
    Ok(StatusCode::OK)
}
