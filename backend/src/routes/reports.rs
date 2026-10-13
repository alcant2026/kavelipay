//! Relatórios por categoria e por período.
//!
//! A utilidade real de organizar dinheiro está aqui, não no extrato. Tudo sai
//! do ledger: relatório que recalcula saldo é relatório que diverge do saldo.

use axum::Json;
use axum::extract::{Query, State};
use chrono::{Datelike, NaiveDate};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::{AppError, AppResult};
use crate::ledger;
use crate::money::Money;
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
    let (wallet_id, currency, is_demonstrative) =
        sqlx::query_as::<_, (Uuid, String, bool)>(
            "SELECT id, currency, is_demonstrative FROM wallets WHERE user_id = $1",
        )
        .bind(auth.user_id())
        .fetch_optional(&state.db)
        .await?
        .ok_or(AppError::NotFound("wallet"))?;

    let all_time = q.all_time.unwrap_or(false);
    let (inicio, rotulo) = periodo(q.month.as_deref(), all_time)?;

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

    Ok(Json(MonthReport {
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
    }))
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