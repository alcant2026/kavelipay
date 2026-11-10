//! Relatórios por categoria e por período.
//!
//! A utilidade real de organizar dinheiro está aqui, não no extrato. Tudo sai
//! do ledger: relatório que recalcula saldo é relatório que diverge do saldo.

use axum::Json;
use axum::extract::{Query, State};
use axum::http::header;
use axum::response::{IntoResponse, Response};
use chrono::{Datelike, NaiveDate};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::{AppError, AppResult};
use crate::ledger;
use crate::money::Money;
use crate::routes::charges::escapar;
use crate::state::AppState;

/// Tipos de transação que entram no relatório mensal de receita/despesa.
///
/// A lista é explícita de propósito. Um filtro por "tudo que mexe a carteira"
/// contaria compra e venda de ativo como despesa, e o relatório diria que o
/// usuário gastou o dinheiro que ele aplicou. `INVESTMENT_DIVIDEND` ENTRA,
/// porque dividendo é receita de verdade — só não é salário, e o que o
/// distingue no extrato é o tipo, não a conta.
const RECEITA_E_DESPESA: &[&str] = &[
    "ENTRY",
    "EXPENSE",
    "BILL_PAID",
    "SETTLEMENT",
    "CHARGE_RECEIVED",
    "INVESTMENT_DIVIDEND",
];

/// Tipos que são consumo: são os que podem virar "gastou R$ X em Y".
const DESPESA: &[&str] = &["EXPENSE", "BILL_PAID", "SETTLEMENT"];

/// Tipos que são entrada de dinheiro.
const RECEITA: &[&str] = &["ENTRY", "CHARGE_RECEIVED", "INVESTMENT_DIVIDEND"];

#[derive(Deserialize)]
pub struct ReportQuery {
    /// `YYYY-MM`. Ausente = mês atual.
    pub month: Option<String>,
    /// Ignora o filtro de mês (para "tudo").
    pub all_time: Option<bool>,
}

#[derive(Serialize)]
pub struct CategorySlice {
    pub category_id: Option<Uuid>,
    /// Resolvido agora. Categoria apagada volta como "(sem categoria)" — por
    /// isso o id é opcional: a fatia continua existindo depois do DELETE.
    pub name: String,
    pub color: Option<String>,
    pub total: Money,
    pub count: u64,
    /// Fração do total da despesa, 0..=1. O app desenha a barra com isto.
    pub share: f64,
}

#[derive(Serialize)]
pub struct MonthReport {
    pub month: Option<String>,
    pub currency: String,
    pub is_demonstrative: bool,
    pub income: Money,
    pub expense: Money,
    /// `income - expense`. É o RESULTADO do período, não o saldo da carteira:
    /// os dois números são diferentes e confundi-los é erro de leitura.
    pub net: Money,
    pub balance: Money,
    pub by_expense_category: Vec<CategorySlice>,
    pub by_income_category: Vec<CategorySlice>,
    pub transaction_count: u64,
    /// Caixinhas: dinheiro que entrou/saiu por aporte, rendimento e devolução
    /// CONFIRMADOS no período. Fora do ledger, por isso consulta própria.
    pub caixinha_in: Money,
    pub caixinha_out: Money,
}

/// Uma linha agregada do relatório.
#[derive(sqlx::FromRow)]
struct Linha {
    category_id: Option<Uuid>,
    category_name: Option<String>,
    color: Option<String>,
    /// Soma dos movimentos de SAÍDA no período.
    total_expense: Decimal,
    /// Soma dos movimentos de ENTRADA no período.
    total_income: Decimal,
    qtd: i64,
}

/// GET /reports/monthly
pub async fn monthly(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Query(q): Query<ReportQuery>,
) -> AppResult<Json<MonthReport>> {
    // Relatório é o recurso que gera valor de insight (e custa desenvolvimento
    // contínuo). O extrato continua grátis; o relatório por categoria é
    // Premium. 402 faz o app abrir a tela de assinatura.
    crate::routes::billing::exigir_premium_do_token(&auth)?;
    Ok(Json(
        montar_relatorio(
            &state,
            auth.user_id(),
            q.month.as_deref(),
            q.all_time.unwrap_or(false),
        )
        .await?,
    ))
}

/// Agregação única: o JSON e o HTML baixável saem daqui, então os dois nunca
/// divergem.
async fn montar_relatorio(
    state: &AppState,
    user_id: Uuid,
    month: Option<&str>,
    all_time: bool,
) -> AppResult<MonthReport> {
    let (wallet_id, currency, is_demonstrative) =
        sqlx::query_as::<_, (Uuid, String, bool)>(
            "SELECT id, currency, is_demonstrative FROM wallets WHERE user_id = $1",
        )
        .bind(user_id)
        .fetch_optional(&state.db)
        .await?
        .ok_or(AppError::NotFound("wallet"))?;

    let (inicio, rotulo) = periodo(month, all_time)?;

    // Um lançamento tem DUAS entradas no ledger (carteira + categoria de
    // destino). Filtrar por `a.kind = 'USER_AVAILABLE'` garante que ele conte
    // uma vez só — sem isso, "Mercado" apareceria dobrado no relatório.
    //
    // E o TIPO é o que separa despesa de aplicação. Uma compra de ativo
    // DEBITA a carteira (o dinheiro sai), então contar pelo lado do débito
    // diria que o usuário "gastou" R$ 350 ao comprar 10 ações — que foi
    // justamente o que ele não fez. Por isso a soma usa o tipo, e não o lado
    // da entrada: `SUM(...) FILTER (WHERE t.type = ANY($5))` para receita,
    // `$6` para despesa.
    let linhas = sqlx::query_as::<_, Linha>(
        "SELECT t.category_id,
                c.name  AS category_name,
                c.color,
                COALESCE(SUM(e.debit)  FILTER (WHERE t.type = ANY($5::text[])), 0) AS total_expense,
                COALESCE(SUM(e.credit) FILTER (WHERE t.type = ANY($6::text[])), 0) AS total_income,
                COUNT(*) FILTER (WHERE t.type = ANY($5::text[]) OR t.type = ANY($6::text[])) AS qtd
           FROM ledger_transactions t
           JOIN ledger_entries e   ON e.transaction_id = t.id
           JOIN ledger_accounts a ON a.id = e.account_id
           LEFT JOIN categories c ON c.id = t.category_id
          WHERE t.wallet_id = $1
            AND a.kind = 'USER_AVAILABLE'
            AND t.status = 'POSTED'
            AND t.type = ANY($7::text[])
            AND ($2::bool OR (t.created_at >= $3 AND t.created_at < $4))
          GROUP BY t.category_id, c.name, c.color",
    )
    .bind(wallet_id)
    .bind(all_time)
    .bind(inicio)
    .bind(fim_do_periodo(inicio))
    .bind(DESPESA)
    .bind(RECEITA)
    .bind(RECEITA_E_DESPESA)
    .fetch_all(&state.db)
    .await?;

    let mut income = Decimal::ZERO;
    let mut expense = Decimal::ZERO;
    let mut count = 0u64;
    let mut despesas: Vec<(Option<Uuid>, String, Option<String>, Decimal, u64)> = Vec::new();
    let mut receitas: Vec<(Option<Uuid>, String, Option<String>, Decimal, u64)> = Vec::new();

    for l in linhas {
        income += l.total_income;
        expense += l.total_expense;
        count += l.qtd as u64;

        let nome = l
            .category_name
            .unwrap_or_else(|| SEM_CATEGORIA.to_string());

        if !l.total_expense.is_zero() {
            acumula(&mut despesas, l.category_id, nome.clone(), l.color.clone(), l.total_expense, l.qtd as u64);
        }
        if !l.total_income.is_zero() {
            acumula(&mut receitas, l.category_id, nome, l.color, l.total_income, l.qtd as u64);
        }
    }

    let (saldo, _) = ledger::saldo(&state.db, wallet_id).await?;
    let (caixinha_in, caixinha_out) =
        caixinhas_do_periodo(state, user_id, inicio, fim_do_periodo(inicio), all_time).await?;

    Ok(MonthReport {
        month: rotulo,
        currency,
        is_demonstrative,
        income: Money::new(income),
        expense: Money::new(expense),
        net: Money::new(income - expense),
        balance: Money::new(saldo),
        by_expense_category: monta(despesas, expense),
        by_income_category: monta(receitas, income),
        transaction_count: count,
        caixinha_in: Money::new(caixinha_in),
        caixinha_out: Money::new(caixinha_out),
    })
}

/// Caixinhas ficam FORA do ledger, então entram no relatório por consulta
/// própria. Só movimento `CONFIRMADO` conta, e o sinal depende do papel:
/// dono recebe aporte e paga rendimento/devolução; membro paga aporte e
/// recebe rendimento/devolução.
async fn caixinhas_do_periodo(
    state: &AppState,
    user_id: Uuid,
    inicio: chrono::DateTime<chrono::Utc>,
    fim: chrono::DateTime<chrono::Utc>,
    all_time: bool,
) -> AppResult<(Decimal, Decimal)> {
    let (entrou, saiu) = sqlx::query_as::<_, (Decimal, Decimal)>(
        "SELECT
            COALESCE(SUM(m.valor) FILTER (WHERE
                (c.dono_user_id = $1 AND m.tipo = 'APORTE')
             OR (m.membro_user_id = $1 AND m.tipo IN ('RENDIMENTO','DEVOLUCAO'))
            ), 0) AS entrou,
            COALESCE(SUM(m.valor) FILTER (WHERE
                (c.dono_user_id = $1 AND m.tipo IN ('RENDIMENTO','DEVOLUCAO'))
             OR (m.membro_user_id = $1 AND m.tipo = 'APORTE')
            ), 0) AS saiu
           FROM caixinha_movimentos m
           JOIN caixinhas c ON c.id = m.caixinha_id
          WHERE m.status = 'CONFIRMADO'
            AND (c.dono_user_id = $1 OR m.membro_user_id = $1)
            AND ($2::bool OR (m.confirmado_em >= $3 AND m.confirmado_em < $4))",
    )
    .bind(user_id)
    .bind(all_time)
    .bind(inicio)
    .bind(fim)
    .fetch_one(&state.db)
    .await?;
    Ok((entrou, saiu))
}

const SEM_CATEGORIA: &str = "(sem categoria)";

type Fatia = (Option<Uuid>, String, Option<String>, Decimal, u64);

fn acumula(lista: &mut Vec<Fatia>, id: Option<Uuid>, nome: String, cor: Option<String>, valor: Decimal, n: u64) {
    for item in lista.iter_mut() {
        // Casa por id **e** nome: categoria apagada e recriada com o mesmo nome
        // são fatias diferentes e devem ficar separadas.
        if item.0 == id && item.1 == nome {
            item.3 += valor;
            item.4 += n;
            return;
        }
    }
    lista.push((id, nome, cor, valor, n));
}

fn monta(mut lista: Vec<Fatia>, total: Decimal) -> Vec<CategorySlice> {
    lista.sort_by(|a, b| b.3.cmp(&a.3));

    let total_f = to_f(total).abs();

    lista
        .into_iter()
        .map(|(id, name, color, total, count)| CategorySlice {
            category_id: id,
            name,
            color,
            total: Money::new(total),
            count,
            share: if total_f == 0.0 {
                0.0
            } else {
                // Arredonda em 4 casas: o app exibe "12,3%" e não
                // 0.12300000000000002. Não é arredondamento financeiro, é
                // apresentação de proporção.
                ((to_f(total) / total_f) * 10_000.0).round() / 10_000.0
            },
        })
        .collect()
}

fn to_f(d: Decimal) -> f64 {
    d.to_string().parse().unwrap_or(0.0)
}

/// `(início do período, rótulo para a resposta)`.
fn periodo(month: Option<&str>, all_time: bool) -> AppResult<(chrono::DateTime<chrono::Utc>, Option<String>)> {
    if all_time {
        return Ok((inicio_do_epoch(), None));
    }

    let hoje = chrono::Local::now().date_naive();

    let texto = month
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(|s| {
            chrono::NaiveDate::parse_from_str(&format!("{s}-01"), "%Y-%m-%d")
                .map_err(|_| AppError::Validation("month deve ser YYYY-MM".into()))
        })
        .transpose()?;

    let inicio = match texto {
        Some(d) => d,
        None => NaiveDate::from_ymd_opt(hoje.year(), hoje.month(), 1).unwrap_or(hoje),
    };

    let rotulo = inicio.format("%Y-%m").to_string();
    Ok((para_timestamp(inicio), Some(rotulo)))
}

/// Fim EXCLUSIVO do período: primeiro dia do mês seguinte.
fn fim_do_periodo(inicio: chrono::DateTime<chrono::Utc>) -> chrono::DateTime<chrono::Utc> {
    let d = inicio.date_naive();
    let (ano, mes) = if d.month() == 12 {
        (d.year() + 1, 1)
    } else {
        (d.year(), d.month() + 1)
    };
    NaiveDate::from_ymd_opt(ano, mes, 1)
        .map(para_timestamp)
        .unwrap_or(inicio)
}

fn inicio_do_epoch() -> chrono::DateTime<chrono::Utc> {
    para_timestamp(NaiveDate::from_ymd_opt(1970, 1, 1).unwrap())
}

fn para_timestamp(d: NaiveDate) -> chrono::DateTime<chrono::Utc> {
    chrono::DateTime::from_naive_utc_and_offset(
        d.and_hms_opt(0, 0, 0).unwrap_or_default(),
        chrono::Utc,
    )
}
// ── GET /reports/monthly/relatorio — HTML para baixar/imprimir ───────────────

/// Instruções fixas do modelo. A IA escreve só o texto qualitativo: os
/// números do relatório vêm do ledger, e o texto é descartado se citar
/// qualquer dígito (ver `texto_confiavel`).
const SISTEMA_RELATORIO: &str = "\
Você escreve a análise de um relatório financeiro pessoal em português do Brasil. \
Regras obrigatórias: use SOMENTE os dados fornecidos; não invente valores, categorias nem fatos; \
NÃO escreva números, nem mesmo em listas (use hífen como marcador); \
não faça recomendação de investimento; trate o leitor por 'você'. \
Escreva no máximo três parágrafos curtos e termine com duas ou três sugestões práticas \
para economizar ou aumentar a renda.";

/// GET /reports/monthly/relatorio
///
/// Mesmo relatório do JSON, em HTML autocontido com `Content-Disposition:
/// attachment` — o app salva em Downloads e o Android imprime em PDF.
pub async fn relatorio_html(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Query(q): Query<ReportQuery>,
) -> AppResult<Response> {
    crate::routes::billing::exigir_premium_do_token(&auth)?;

    let relatorio = montar_relatorio(
        &state,
        auth.user_id(),
        q.month.as_deref(),
        q.all_time.unwrap_or(false),
    )
    .await?;

    // Sem chave configurada o relatório continua baixável, só sem a análise.
    // Com chave, o teto por usuário protege o custo de token de terceiro.
    let analise = if state.ai.configurado() {
        if !state.relatorio_gate.permite(auth.user_id()) {
            return Err(AppError::TooManyRequests(
                "limite de relatórios por hora atingido; tente de novo mais tarde".into(),
            ));
        }
        let dados = dados_para_ia(&relatorio);
        let mut analise = None;
        // Duas tentativas no máximo: o modelo às vezes ignora a proibição de
        // algarismo na primeira resposta. A segunda pede explicitamente sem
        // dígito; se insistir, o relatório sai sem análise (número inventado
        // é pior que seção ausente).
        for tentativa in 0..2 {
            let sistema = if tentativa == 0 {
                SISTEMA_RELATORIO.to_string()
            } else {
                format!(
                    "{SISTEMA_RELATORIO} A resposta anterior veio com algarismos. \
                     Reescreva usando nenhum caractere de zero a nove."
                )
            };
            match state.ai.completar(&sistema, &dados).await {
                Ok(texto) => {
                    if let Some(limpo) = texto_confiavel(&texto) {
                        analise = Some(limpo);
                        break;
                    }
                    tracing::warn!("análise do relatório veio com número; tentando de novo");
                }
                Err(e) => {
                    tracing::warn!("análise do relatório indisponível: {e}");
                    break;
                }
            }
        }
        analise
    } else {
        None
    };

    let html = montar_html(&relatorio, analise.as_deref());
    let nome = format!(
        "kavelipay-relatorio-{}.html",
        relatorio.month.as_deref().unwrap_or("tudo")
    );

    Ok((
        [
            (header::CONTENT_TYPE, "text/html; charset=utf-8".to_string()),
            (
                header::CONTENT_DISPOSITION,
                format!("attachment; filename=\"{nome}\""),
            ),
        ],
        html,
    )
        .into_response())
}

/// Só os agregados vão para o provedor: nada de extrato linha a linha.
fn dados_para_ia(r: &MonthReport) -> String {
    let mut s = format!(
        "Período: {}\nEntradas: R$ {}\nSaídas: R$ {}\nResultado: R$ {}\n\
         Saldo da carteira: R$ {}\nLançamentos: {}\n\
         Caixinhas — entrou: R$ {}; saiu: R$ {}\n",
        r.month.as_deref().unwrap_or("todo o histórico"),
        r.income,
        r.expense,
        r.net,
        r.balance,
        r.transaction_count,
        r.caixinha_in,
        r.caixinha_out,
    );

    if !r.by_expense_category.is_empty() {
        s.push_str("Despesas por categoria:\n");
        for f in &r.by_expense_category {
            s.push_str(&format!("- {}: R$ {} ({} lançamentos)\n", f.name, f.total, f.count));
        }
    }
    if !r.by_income_category.is_empty() {
        s.push_str("Receitas por categoria:\n");
        for f in &r.by_income_category {
            s.push_str(&format!("- {}: R$ {} ({} lançamentos)\n", f.name, f.total, f.count));
        }
    }
    s
}

/// Dígito no texto = valor inventado. Nesse caso a seção cai para o aviso
/// padrão: relatório sem análise é melhor que relatório com número errado.
fn texto_confiavel(texto: &str) -> Option<String> {
    let limpo = texto.trim();
    if limpo.is_empty() || limpo.chars().any(|c| c.is_ascii_digit()) {
        return None;
    }
    Some(limpo.chars().take(4000).collect())
}

const CSS_RELATORIO: &str = "\
body{font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;margin:0;background:#f6f6f8;color:#1b1b1f}\
main{max-width:720px;margin:0 auto;padding:32px 20px 48px}\
h1{font-size:24px;margin:0 0 4px}h2{font-size:16px;margin:28px 0 8px}\
.sub{color:#5f5f6b;font-size:13px;margin:0 0 24px}\
table{width:100%;border-collapse:collapse;background:#fff;border-radius:12px;overflow:hidden}\
th,td{padding:10px 12px;text-align:left;font-size:14px;border-bottom:1px solid #ececf1}\
th{background:#fafafc;color:#5f5f6b;font-weight:600}\
tr:last-child td{border-bottom:none}.num{text-align:right;white-space:nowrap}\
.resumo td:first-child{color:#5f5f6b}\
.analise{background:#fff;border-radius:12px;padding:16px 18px;font-size:14px;line-height:1.55}\
.analise p{margin:0 0 10px}.analise p:last-child{margin-bottom:0}\
.aviso{color:#8a5a00}\
.rodape{margin-top:28px;color:#5f5f6b;font-size:12px;line-height:1.5}\
@media print{body{background:#fff}main{padding:0}table,.analise{break-inside:avoid}}";

fn montar_html(r: &MonthReport, analise: Option<&str>) -> String {
    let periodo = r
        .month
        .as_deref()
        .map(|m| m.replace('-', "/"))
        .unwrap_or_else(|| "todo o histórico".to_string());

    let mut h = String::new();
    h.push_str("<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\">");
    h.push_str("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
    h.push_str("<title>Relatório KaveliPay</title><style>");
    h.push_str(CSS_RELATORIO);
    h.push_str("</style></head><body><main>");

    h.push_str("<h1>Relatório KaveliPay</h1>");
    h.push_str(&format!("<p class=\"sub\">Período: {}</p>", escapar(&periodo)));

    h.push_str("<h2>Resumo</h2><table class=\"resumo\">");
    h.push_str(&format!(
        "<tr><td>Entradas</td><td class=\"num\">R$ {}</td></tr>",
        r.income
    ));
    h.push_str(&format!(
        "<tr><td>Saídas</td><td class=\"num\">R$ {}</td></tr>",
        r.expense
    ));
    h.push_str(&format!(
        "<tr><td>Resultado do período</td><td class=\"num\">R$ {}</td></tr>",
        r.net
    ));
    h.push_str(&format!(
        "<tr><td>Saldo da carteira</td><td class=\"num\">R$ {}</td></tr>",
        r.balance
    ));
    h.push_str(&format!(
        "<tr><td>Caixinhas — entrou</td><td class=\"num\">R$ {}</td></tr>",
        r.caixinha_in
    ));
    h.push_str(&format!(
        "<tr><td>Caixinhas — saiu</td><td class=\"num\">R$ {}</td></tr>",
        r.caixinha_out
    ));
    h.push_str("</table>");

    h.push_str("<h2>Para onde foi</h2>");
    if r.by_expense_category.is_empty() {
        h.push_str("<p class=\"sub\">Sem despesas classificadas no período.</p>");
    } else {
        h.push_str("<table><tr><th>Categoria</th><th class=\"num\">Total</th><th class=\"num\">Fatia</th></tr>");
        for f in &r.by_expense_category {
            h.push_str(&format!(
                "<tr><td>{}</td><td class=\"num\">R$ {}</td><td class=\"num\">{:.1}%</td></tr>",
                escapar(&f.name),
                f.total,
                f.share * 100.0
            ));
        }
        h.push_str("</table>");
    }

    if !r.by_income_category.is_empty() {
        h.push_str("<h2>De onde veio</h2><table><tr><th>Categoria</th><th class=\"num\">Total</th></tr>");
        for f in &r.by_income_category {
            h.push_str(&format!(
                "<tr><td>{}</td><td class=\"num\">R$ {}</td></tr>",
                escapar(&f.name),
                f.total
            ));
        }
        h.push_str("</table>");
    }

    h.push_str("<h2>Análise</h2><div class=\"analise\">");
    match analise {
        Some(texto) => {
            for paragrafo in texto.split('\n') {
                let p = paragrafo.trim();
                if !p.is_empty() {
                    h.push_str(&format!("<p>{}</p>", escapar(p)));
                }
            }
        }
        None => h.push_str(
            "<p class=\"aviso\">A análise por IA não está disponível agora. \
             Os números deste relatório continuam corretos.</p>",
        ),
    }
    h.push_str("</div>");

    h.push_str(
        "<p class=\"rodape\">Relatório gerado a partir dos lançamentos registrados no KaveliPay. \
         Valores demonstrativos: o app não custodia dinheiro. \
         A análise é texto gerado por IA e não é recomendação de investimento.</p>",
    );
    h.push_str("</main></body></html>");
    h
}

#[cfg(test)]
mod tests {
    use super::texto_confiavel;

    #[test]
    fn analise_com_algarismo_e_descartada() {
        assert!(texto_confiavel("Você gastou 10 reais.").is_none());
        assert!(texto_confiavel("   ").is_none());
        assert_eq!(
            texto_confiavel(" Tudo certo. ").as_deref(),
            Some("Tudo certo.")
        );
    }
}
