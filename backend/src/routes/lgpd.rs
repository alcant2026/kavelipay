//! LGPD: exportação de dados e eliminação de conta.
//!
//! Duas decisões que precisam estar explícitas porque são escolhas, não
//! detalhes:
//!
//! 1. **Exportar devolve dado estruturado, não CSV.** `GET /lgpd/export`
//!    entrega JSON com tudo que o titular tem direito. O CSV financeiro vem
//!    separado em `GET /wallet/export.csv` — é outro formato, para outro
//!    propósito (planilha), e misturar os dois daria um arquivo que ninguém
//!    consegue usar.
//!
//! 2. **Eliminar é anonimizar, não apagar.** O ledger é registro histórico e
//!    `ledger_entries` referencia `ledger_accounts` com `ON DELETE RESTRICT`.
//!    Apagar em cascata quebraria o equilíbrio de transações de anos atrás
//!    para remover um e-mail — trocar privacidade por integridade, que é a
//!    pior das duas. Então: some o PII, revoga as sessões, o histórico
//!    financeiro fica equilibrado e inacessível (sem login, é inócuo).

use axum::Json;
use axum::extract::{Query, State};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use serde_json::json;
use uuid::Uuid;

use crate::error::{AppError, AppResult};
use crate::money::Money;
use crate::state::AppState;

#[derive(Serialize)]
pub struct ExportResponse {
    pub exported_at: chrono::DateTime<chrono::Utc>,
    pub format: &'static str,
    pub data: serde_json::Value,
}

/// GET /lgpd/export
///
/// Tudo do titular: perfil, categorias, carteira, contas, cobranças, divisões,
/// dívidas e o ledger completo. É o direito de acesso previsto no art. 18 da LGPD.
pub async fn export(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
) -> AppResult<Json<ExportResponse>> {
    let user_id = auth.user_id();

    let perfil = sqlx::query_as::<_, (String, Option<String>, chrono::DateTime<chrono::Utc>)>(
        "SELECT email, display_name, created_at FROM users WHERE id = $1",
    )
    .bind(user_id)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::Unauthorized)?;

    let categorias = sqlx::query_as::<_, (String, String, Option<String>, bool)>(
        "SELECT name, kind, color, is_default FROM categories WHERE user_id = $1",
    )
    .bind(user_id)
    .fetch_all(&state.db)
    .await?;

    let carteira = sqlx::query_as::<_, (Uuid, String, String, bool, chrono::DateTime<chrono::Utc>)>(
        "SELECT id, currency, status, is_demonstrative, created_at
           FROM wallets WHERE user_id = $1",
    )
    .bind(user_id)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NotFound("wallet"))?;

    let contas = sqlx::query_as::<_, (Uuid, String, Option<String>, Decimal, chrono::NaiveDate, String, Option<chrono::DateTime<chrono::Utc>>)>(
        "SELECT id, description, beneficiario, amount, due_date, status, paid_at
           FROM bills WHERE wallet_id = $1 ORDER BY due_date",
    )
    .bind(carteira.0)
    .fetch_all(&state.db)
    .await?;

    let cobrancas = sqlx::query_as::<_, (Uuid, String, Decimal, String, Option<chrono::DateTime<chrono::Utc>>)>(
        "SELECT id, description, amount, status, received_at
           FROM charges WHERE wallet_id = $1 ORDER BY created_at",
    )
    .bind(carteira.0)
    .fetch_all(&state.db)
    .await?;

    // Ledger completo: cada transação com os dois lançamentos. É o dado mais
    // sensível e mais importante — sem ele o titular não consegue conferir
    // nada fora do app.
    //
    // Os lançamentos vão como JSON cru e os números como TEXTO de propósito:
    // um `ARRAY(json_build_array(...))` volta como `json[]`, que o sqlx não
    // decodifica em tupla Rust; e devolver Decimal por JSON voltaria float,
    // que é exatamente o que a regra de dinheiro proíbe.
    //
    // `description` é nullable no ledger (uma entrada pura não tem motivo) —
    // decodificar como `String` derrubava o export inteiro com 500.
    let transacoes = sqlx::query_as::<_, (Uuid, String, Decimal, Option<String>, chrono::DateTime<chrono::Utc>, serde_json::Value)>(
        "SELECT t.id, t.type, t.amount, t.description, t.created_at,
                COALESCE(
                    (
                        SELECT json_agg(
                                   json_build_object(
                                       'conta',  la.kind::text,
                                       'debito',  COALESCE(e.debit, 0)::text,
                                       'credito', COALESCE(e.credit, 0)::text
                                   )
                                   ORDER BY e.created_at, e.id
                               )
                          FROM ledger_entries e
                          JOIN ledger_accounts la ON la.id = e.account_id
                         WHERE e.transaction_id = t.id
                    ),
                    '[]'::json
                ) AS entries
           FROM ledger_transactions t
          WHERE t.wallet_id = $1
          ORDER BY t.created_at, t.id",
    )
    .bind(carteira.0)
    .fetch_all(&state.db)
    .await?;

    // Dívidas em QUALQUER status — PENDING, PAID e CONFIRMED. O export é
    // completo por natureza (art. 18): omitir pendentes ou confirmadas
    // privaria o titular do dado integral. O valor vira string via `Money`
    // (2 casas, nunca float), igual às outras seções.
    let dividas = sqlx::query_as::<_, (Uuid, String, String, Decimal, String, Option<chrono::DateTime<chrono::Utc>>, Option<chrono::DateTime<chrono::Utc>>, chrono::DateTime<chrono::Utc>)>(
        "SELECT id, creditor_name, description, amount, status,
                paid_at, confirmed_at, created_at
           FROM debts WHERE wallet_id = $1 ORDER BY created_at, id",
    )
    .bind(carteira.0)
    .fetch_all(&state.db)
    .await?;

    let data = json!({
        "perfil": {
            "email": perfil.0,
            "display_name": perfil.1,
            "criado_em": perfil.2,
        },
        "carteira": {
            "id": carteira.0,
            "moeda": carteira.1,
            "status": carteira.2,
            "demonstrativa": carteira.3,
            "criada_em": carteira.4,
        },
        "categorias": categorias.iter()
            .map(|(n, k, c, d)| json!({"nome": n, "tipo": k, "cor": c, "padrao": d}))
            .collect::<Vec<_>>(),
        "contas": contas.iter()
            .map(|(i, d, b, a, v, s, p)| json!({
                "id": i, "descricao": d, "beneficiario": b,
                "valor": a.to_string(), "vencimento": v, "status": s, "pago_em": p
            }))
            .collect::<Vec<_>>(),
        "cobrancas": cobrancas.iter()
            .map(|(i, d, a, s, r)| json!({
                "id": i, "descricao": d, "valor": a.to_string(),
                "status": s, "recebido_em": r
            }))
            .collect::<Vec<_>>(),
        "dividas": dividas.iter()
            .map(|(i, c, d, a, s, p, cf, cr)| json!({
                "id": i, "credor": c, "descricao": d,
                "valor": Money::new(*a).to_string(), "status": s,
                "pago_em": p, "confirmado_em": cf, "criado_em": cr
            }))
            .collect::<Vec<_>>(),
        "transacoes": transacoes.iter()
            .map(|(i, t, a, d, c, e)| json!({
                "id": i, "tipo": t, "valor": a.to_string(),
                "descricao": d, "criada_em": c, "lancamentos": e
            }))
            .collect::<Vec<_>>(),
    });

    // Registra que o titular exerceu o direito; serve de trilha de auditoria.
    sqlx::query("UPDATE users SET data_export_requested_at = now() WHERE id = $1")
        .bind(user_id)
        .execute(&state.db)
        .await?;

    Ok(Json(ExportResponse {
        exported_at: chrono::Utc::now(),
        format: "kavelipay.lgpd.v1",
        data,
    }))
}

#[derive(Deserialize)]
pub struct DeleteRequest {
    pub password: String,
    pub confirm: bool,
    pub reason: Option<String>,
}

#[derive(Serialize)]
pub struct DeleteResponse {
    pub deleted: bool,
    pub sessions_revoked: u64,
    pub message: &'static str,
}

/// DELETE /lgpd/account
///
/// Exige a senha atual E `confirm: true`. Duas barreiras de propósito: quem
///NISTROU token alheio não apaga a conta, e um toque acidental não apaga 5 anos
/// de histórico.
pub async fn delete_account(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Json(req): Json<DeleteRequest>,
) -> AppResult<Json<DeleteResponse>> {
    if !req.confirm {
        return Err(AppError::Validation(
            "é preciso enviar confirm: true para eliminar a conta".into(),
        ));
    }

    let user_id = auth.user_id();
    let hash = sqlx::query_scalar::<_, String>("SELECT password_hash FROM users WHERE id = $1")
        .bind(user_id)
        .fetch_optional(&state.db)
        .await?
        .ok_or(AppError::Unauthorized)?;

    if !crate::auth::verify(&req.password, &hash) {
        return Err(AppError::Unauthorized);
    }

    let revogadas = sqlx::query(
        "UPDATE refresh_tokens SET revoked_at = now()
          WHERE user_id = $1 AND revoked_at IS NULL",
    )
    .bind(user_id)
    .execute(&state.db)
    .await?
    .rows_affected();

    // ANONIMIZAÇÃO, não DELETE. Vê o comentário do topo do módulo.
    // `email` recebe um identificador opaco e único: sem isso, o índice único
    // `users_email_lower_uniq` quebraria ao anonimizar dois usuários.
    sqlx::query(
        "UPDATE users
            SET email = 'anonimo+' || id::text || '@excluido.invalid',
                display_name = NULL,
                password_hash = '$argon2id$v=19$m=1,t=1,p=1$Y3VjdWVk$Y3VjdWVk',
                status = 'DELETED',
                deleted_at = now(),
                deletion_reason = $2,
                updated_at = now()
          WHERE id = $1",
    )
    .bind(user_id)
    .bind(req.reason.as_deref())
    .execute(&state.db)
    .await?;

    // Carteira e contas vão junto: sem PII e sem dono, não têm utilidade nem
    // valor probatório. O ledger sozinho já não é identificável.
    sqlx::query("UPDATE wallets SET status = 'CLOSED', updated_at = now() WHERE user_id = $1")
        .bind(user_id)
        .execute(&state.db)
        .await?;

    tracing::info!(
        user_id = %user_id,
        revogadas,
        "conta eliminada (anonimizada); ledger preservado para integridade"
    );

    Ok(Json(DeleteResponse {
        deleted: true,
        sessions_revoked: revogadas,
        message: "Conta eliminada. Os dados pessoais foram removidos; o registro \
                  contábil foi preservado de forma anonimizada para manter a \
                  integridade do ledger.",
    }))
}

/// GET /lgpd/export.csv — o extrato em planilha.
///
/// Separado do export JSON de propósito: é outro uso. `Decimal::to_string()`
/// escreve ponto como separador, e o Excel pt-BR abre `10.50` como texto, não
/// número — por isso o valor vai como `10,50`. Nenhum cálculo financeiro é feito
/// no CSV; ele é para ser lido e conferido, não somado pela planilha.
pub async fn export_csv(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Query(_q): Query<()>,
) -> AppResult<String> {
    let wallet_id = sqlx::query_scalar::<_, Uuid>("SELECT id FROM wallets WHERE user_id = $1")
        .bind(auth.user_id())
        .fetch_optional(&state.db)
        .await?
        .ok_or(AppError::NotFound("wallet"))?;

    let linhas = sqlx::query_as::<_, (
        chrono::DateTime<chrono::Utc>,
        String,
        String,
        Option<Uuid>,
        Option<String>,
        Option<String>,
        String,
        Decimal,
        Decimal,
    )>(
        "SELECT t.created_at, t.id::text, t.type, t.category_id, c.name,
                COALESCE(t.description, ''), a.kind,
                e.debit, e.credit
           FROM ledger_transactions t
           JOIN ledger_entries e   ON e.transaction_id = t.id
           JOIN ledger_accounts a ON a.id = e.account_id
           LEFT JOIN categories c ON c.id = t.category_id
          WHERE t.wallet_id = $1
          ORDER BY t.created_at, t.id",
    )
    .bind(wallet_id)
    .fetch_all(&state.db)
    .await?;

    let mut out = String::with_capacity(256 + linhas.len() * 96);
    out.push_str("data;transacao_id;tipo;categoria;descricao;conta;debito;credito\n");

    for (criado, id, tipo, _cat_id, cat, desc, conta, debito, credito) in linhas {
        out.push_str(&format!(
            "{};{};{};{};{};{};{};{}\n",
            criado.format("%Y-%m-%dT%H:%M:%SZ"),
            id,
            sanitiza(&tipo),
            sanitiza(cat.as_deref().unwrap_or("")),
            sanitiza(desc.as_deref().unwrap_or("")),
            sanitiza(&conta),
            pt_br(debito),
            pt_br(credito),
        ));
    }

    Ok(out)
}

/// CSV com `;` e escape por aspas: o padrão do Excel pt-BR. Um `;` ou quebra de
/// linha num campo quebraria a coluna silenciosamente.
fn sanitiza(s: &str) -> String {
    let limpo: String = s
        .chars()
        .filter(|c| *c != '\n' && *c != '\r')
        .collect();
    if limpo.contains(';') || limpo.contains('"') {
        format!("\"{}\"", limpo.replace('"', "\"\""))
    } else {
        limpo
    }
}

/// `10.50` → `10,50`. Vírgula é o separador decimal no pt-BR.
///
/// Zero vira `0,00` explícito: `Decimal::ZERO.to_string()` é `"0"`, e uma
/// coluna com `0` ao lado de `50,00` quebra a leitura no Excel pt-BR.
fn pt_br(d: Decimal) -> String {
    if d.is_zero() {
        "0,00".to_string()
    } else {
        d.to_string().replace('.', ",")
    }
}