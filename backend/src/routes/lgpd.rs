//! LGPD: exportação do extrato em planilha e eliminação de conta.
//!
//! Duas decisões que precisam estar explícitas porque são escolhas, não
//! detalhes:
//!
//! 1. **Exportar devolve UMA planilha, não dois formatos.** Antes existiam
//!    `GET /lgpd/export` (JSON com o banco inteiro) e `GET /lgpd/export.csv`.
//!    Nenhum dos dois servia sozinho: JSON é formato de programador — quem
//!    pede o próprio extrato quer abrir no Excel e ler — e o CSV despejava os
//!    lançamentos contábeis (duas linhas por transação, contas internas como
//!    `USER_AVAILABLE`, valores em quatro casas). Ficou só o CSV, agora como
//!    extrato: uma linha por transação, com o valor que entrou ou saiu da
//!    carteira.
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
use uuid::Uuid;

use crate::error::{AppError, AppResult};
use crate::state::AppState;

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
/// **Uma linha por transação, não por lançamento.** A versão anterior lia
/// `ledger_entries` e despejava a escrituração: cada compra virava duas linhas
/// (débito numa conta, crédito em outra), com nomes internos
/// (`USER_AVAILABLE`, `INCOME`) e `100,0000` de quatro casas. Para quem só quer
/// conferir o extrato, aquilo não era extrato — era o razão do banco.
///
/// Agora a linha é a transação, e o valor é o que a carteira sentiu:
/// `credit - debit` da conta `USER_AVAILABLE`, com sinal (entrada positivo,
/// saída negativo). Transação que não toca a carteira (ex.: troca entre
/// ativos de investimento) não aparece — ela não mudou nenhum centavo do
/// saldo, e incluí-la faria a soma da planilha não fechar com o saldo.
///
/// Vírgula decimal porque o Excel pt-BR lê `10.50` como texto. Nenhum cálculo
/// financeiro é feito aqui: o CSV é para ler e conferir, não para ser somado
/// por fórmula.
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
        Option<String>,
        Option<String>,
        Decimal,
    )>(
        "SELECT t.created_at, t.type, c.name, t.description,
                (e.credit - e.debit) AS valor
           FROM ledger_transactions t
           JOIN ledger_entries e  ON e.transaction_id = t.id
           JOIN ledger_accounts a ON a.id = e.account_id AND a.kind = 'USER_AVAILABLE'
           LEFT JOIN categories c ON c.id = t.category_id
          WHERE t.wallet_id = $1
          ORDER BY t.created_at, t.id",
    )
    .bind(wallet_id)
    .fetch_all(&state.db)
    .await?;

    let mut out = String::with_capacity(256 + linhas.len() * 64);
    // BOM: sem ele o Excel abre `Descrição` como `DescriÃ§Ã£o`. Planilha com
    // acento quebrado é o primeiro lugar onde o usuário desconfia do arquivo.
    out.push('\u{FEFF}');
    out.push_str("data;tipo;categoria;descricao;valor\n");

    for (criado, tipo, cat, desc, valor) in linhas {
        out.push_str(&format!(
            "{};{};{};{};{}\n",
            criado.format("%Y-%m-%dT%H:%M:%SZ"),
            rotulo_do_tipo(&tipo),
            sanitiza(cat.as_deref().unwrap_or("")),
            sanitiza(desc.as_deref().unwrap_or("")),
            pt_br(valor),
        ));
    }

    Ok(out)
}

/// Nome do tipo de transação em português.
///
/// `ENTRY`/`EXPENSE` são os nomes do domínio; numa planilha que a pessoa abre
/// no Excel, "Despesa" diz o que é. Tipo desconhecido volta como veio — melhor
/// um rótulo feio do que uma linha em branco.
fn rotulo_do_tipo(tipo: &str) -> &'static str {
    match tipo {
        "ENTRY" => "Entrada",
        "EXPENSE" => "Despesa",
        "BILL_PAID" => "Conta paga",
        "SETTLEMENT" => "Divisão recebida",
        "CHARGE_RECEIVED" => "Cobrança recebida",
        "INVESTMENT_BUY" => "Investimento (compra)",
        "INVESTMENT_SELL" => "Investimento (venda)",
        "INVESTMENT_DIVIDEND" => "Rendimento",
        _ => "Outro",
    }
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

/// `1050` → `1050,00`; `-10.5` → `-10,50`.
///
/// Duas casas sempre, inclusive no zero e no negativo: `Money` também exibe
/// duas casas, então planilha e tela mostram o mesmo número. Ponto vira
/// vírgula porque é o separador decimal do pt-BR — e o Excel pt-BR lê `10.50`
/// como texto.
fn pt_br(valor: Decimal) -> String {
    format!("{:.2}", valor).replace('.', ",")
}
