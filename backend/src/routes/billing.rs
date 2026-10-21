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
use axum::extract::State;
use axum::http::HeaderMap;
use chrono::{DateTime, Duration, Utc};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::PgPool;
use uuid::Uuid;

use crate::auth::admin::exigir_admin;
use crate::auth::AuthUser;
use crate::error::{AppError, AppResult};
use crate::state::AppState;

/// Preço de referência do plano mensal. A API devolve como texto porque
/// dinheiro nunca é float — nem no JSON, nem no app.
pub const PRECO_MENSAL: &str = "9.90";
pub const PRECO_ANUAL: &str = "79.90";

/// Recursos que o plano libera. Lista explícita para o app desenhar a tela sem
/// inventar promessa que o backend não cumpre.
pub const RECURSOS_PREMIUM: &[&str] = &[
    "Relatórios mensais por categoria",
    "Análise de ativos por IA",
    "Cotação de mercado (brapi/Yahoo)",
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

    // Base = fim atual se ainda vigente; senão agora. Evita perder dias
    // pagos numa renovação antecipada.
    let base = assinatura_vigente(&state.db, auth.user_id())
        .await?
        .map(|(_, fim)| fim)
        .filter(|fim| *fim > Utc::now())
        .unwrap_or_else(Utc::now);
    let periodo_fim = base + Duration::days(i64::from(dias));

    sqlx::query(
        "INSERT INTO assinaturas
             (user_id, provider, provider_sub_id, plano, status, periodo_fim)
         VALUES ($1, 'codigo', $2, $3, 'ACTIVE', $4)
         ON CONFLICT (user_id) DO UPDATE SET
             provider = 'codigo',
             provider_sub_id = EXCLUDED.provider_sub_id,
             plano = EXCLUDED.plano,
             status = 'ACTIVE',
             periodo_fim = EXCLUDED.periodo_fim,
             atualizado_em = now()",
    )
    .bind(auth.user_id())
    .bind(codigo_id.to_string())
    .bind(&plano)
    .bind(periodo_fim)
    .execute(&mut *tx)
    .await?;

    tx.commit().await?;

    // O direito acabou de mudar: devolve um access token novo para o app não
    // ficar com o token antigo (`premium = false`) até o próximo refresh.
    let mut resposta = montar_plano(Some((plano, periodo_fim)));
    resposta.access_token = Some(state.tokens.issue_access(auth.user_id(), true)?);
    Ok(Json(resposta))
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
fn gerar_codigo_legivel() -> String {
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
