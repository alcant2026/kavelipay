//! Investimentos: registro pessoal de carteira de ativos.
//!
//! O KaveliPay NÃO é corretora, não custodia ativos e não dá recomendação. O
//! que existe aqui é o que o usuário afirma ter comprado e vendido, com o
//! cálculo de custo médio e o extrato da posição. É bookkeeping, não
//! custódia — a mesma fronteira de `bills/{id}/pay` e `charges/{id}/receive`:
//! o sistema registra o que a pessoa declarou, e nada confirma por conta própria.
//!
//! NÃO HÁ COTAÇÃO NEM PREÇO JUSTO aqui, e a ausência é deliberada: uma tela
//! que mostra "PETR4, preço justo R$ 42" é recomendação de investimento, que
//! no Brasil é regulada (assessor de investimento exige registro na CVM). Fora
//! disso, "quanto rendeu" sai dos próprios números do usuário.
//!
//! CONTABILIDADE. Cada movimento tem uma perna no ledger:
//!  - compra:     débito USER_AVAILABLE, crédito INVESTMENT_ASSETS
//!  - venda:      débito INVESTMENT_ASSETS, crédito USER_AVAILABLE
//!  - dividendo:  débito INCOME, crédito USER_AVAILABLE
//!
//! Compra NÃO é despesa: o dinheiro não sumiu, mudou de forma. Debitar
//! EXPENSE aqui faria o saldo cair como se o usuário tivesse gastado, e o
//! relatório mensal diria que ele gastou R$ 350 ao comprar 10 ações — que foi
//! justamente o que ele não fez.

use axum::Json;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use rust_decimal::Decimal;
use rust_decimal::prelude::FromStr;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::{AppError, AppResult};
use crate::ledger::{self, TransactionType};
use crate::money::Money;
use crate::state::wallet_ativa;
use crate::state::AppState;

// ── requests ──────────────────────────────────────────────────────────────────

#[derive(Deserialize)]
pub struct NewInvestment {
    pub symbol: String,
    pub name: String,
    /// 'STOCK' | 'FII' | 'BOND' | 'FUND' | qualquer outro texto.
    pub kind: Option<String>,
    pub notes: Option<String>,
    pub summary: Option<String>,
    pub next_dividend_at: Option<String>,
    /// Preço unitário atual, digitado. Ver `migrations/0008` para por que não
    /// é cotação automática.
    pub current_price: Option<String>,
}

#[derive(Deserialize)]
pub struct UpdateInvestment {
    pub name: Option<String>,
    pub kind: Option<String>,
    pub notes: Option<String>,
    pub summary: Option<String>,
    pub next_dividend_at: Option<String>,
    /// Preço unitário atual, DIGITADO pelo usuário. Ver `migrations/0008`.
    ///
    /// String vazia limpa o campo (volta a "desconhecido"), igual ao
    /// `next_dividend_at`.
    pub current_price: Option<String>,
}

#[derive(Deserialize)]
pub struct NewMovement {
    /// 'BUY' | 'SELL' | 'DIVIDEND'
    pub kind: String,
    pub quantity: String,
    pub price_per_unit: Option<String>,
    pub amount: String,
    pub fees: Option<String>,
    pub traded_at: String,
    pub note: Option<String>,
}

#[derive(Deserialize)]
pub struct InvestmentQuery {
    pub symbol: Option<String>,
}

// ── responses ─────────────────────────────────────────────────────────────────

#[derive(Serialize)]
pub struct PositionResponse {
    pub id: Uuid,
    pub symbol: String,
    pub name: String,
    pub kind: String,
    pub notes: Option<String>,
    pub summary: Option<String>,
    pub next_dividend_at: Option<chrono::NaiveDate>,
    /// Preço unitário atual digitado. `None` = desconhecido — e a tela diz
    /// isso, em vez de mostrar zero.
    pub current_price: Option<Decimal>,
    pub created_at: chrono::DateTime<chrono::Utc>,

    /// Quantidade que o usuário tem hoje. Derivada dos movimentos, nunca
    /// digitada: um campo editável aqui permitiria a posição não bater com o
    /// extrato, e o primeiro sinal seria o relatório.
    pub quantity: Decimal,
    pub average_price: Decimal,
    pub cost_basis: Decimal,
    pub realized_pnl: Decimal,
    pub fees_total: Decimal,
    pub dividends_total: Decimal,
    pub movements_count: i64,

    // ── Derivados do preço atual ─────────────────────────────────────────────
    /// `current_price * quantity`, quando o preço é conhecido. `None` quando
    /// não é — a tela omite a linha em vez de mostrar R$ 0,00.
    pub market_value: Option<Decimal>,
    /// `market_value - cost_basis`. É o "quanto eu ganhei sem vender".
    pub unrealized_pnl: Option<Decimal>,
    /// `(market_value - cost_basis) / cost_basis`, em fração 0..=n. `None`
    /// quando o custo é zero (posição zerada) — divisão por zero aqui daria
    /// um número infinito na tela.
    pub unrealized_return: Option<Decimal>,
}

#[derive(Serialize)]
pub struct MovementResponse {
    pub id: Uuid,
    pub investment_id: Uuid,
    pub kind: String,
    pub quantity: Decimal,
    pub price_per_unit: Option<Decimal>,
    pub amount: Money,
    pub fees: Money,
    pub traded_at: chrono::NaiveDate,
    pub note: Option<String>,
    pub transaction_id: Option<Uuid>,
}

#[derive(Serialize)]
pub struct PortfolioResponse {
    pub items: Vec<PositionResponse>,
    pub total_cost_basis: Decimal,
    pub total_realized_pnl: Decimal,
    pub total_dividends: Decimal,
    pub total_fees: Decimal,
    /// Quantidade somada de todos os ativos. Só faz sentido como informação
    /// de diversify, e é por isso que vem com a contagem: 12 posições de 1 ação
    /// cada não é a mesma coisa que 12 ações de um ativo só.
    pub positions_count: i64,
}

#[derive(Serialize)]
pub struct PortfolioSummaryResponse {
    pub total_cost_basis: Money,
    pub total_realized_pnl: Money,
    pub total_dividends: Money,
    pub total_fees: Money,
    pub positions_count: i64,
    /// Ganho não realizado depende do preço atual, que o app não tem. O campo
    /// existe para a tela dizer explicitamente que NÃO está calculando isso,
    /// em vez de a ausência parecer um bug.
    pub unrealized_note: &'static str,
}

// ── POST /investments ─────────────────────────────────────────────────────────

pub async fn create_investment(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Json(req): Json<NewInvestment>,
) -> AppResult<(StatusCode, Json<PositionResponse>)> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    // O `kind` vem ANTES da normalização porque ele decide a regra do
    // formato: ação/FII exigem ticker B3, renda fixa não.
    let kind = req
        .kind
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or("STOCK")
        .to_string();

    let symbol = normalizar_simbolo(&req.symbol, &kind)?;
    let name = req.name.trim().to_string();
    if name.is_empty() {
        return Err(AppError::Validation("nome do ativo é obrigatório".into()));
    }

    let next_dividend_at = req
        .next_dividend_at
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(parse_data)
        .transpose()?;

    // `query_scalar` decodifica a PRIMEIRA coluna. Um `RETURNING id` numa tabela
    // é perfeito; o problema seria `RETURNING` com várias colunas.
    let current_price = req
        .current_price
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(|s| parse_decimal(s, "current_price"))
        .transpose()?;

    if current_price.is_some_and(|p| p <= Decimal::ZERO) {
        return Err(AppError::Validation(
            "preço atual deve ser maior que zero".into(),
        ));
    }

    let id = sqlx::query_scalar::<_, Uuid>(
        "INSERT INTO investments
             (wallet_id, symbol, name, kind, notes, summary, next_dividend_at, current_price)
         SELECT w.id, $2, $3, $4, $5, $6, $7, $8 FROM wallets w WHERE w.id = $1
         RETURNING id",
    )
    .bind(wallet)
    .bind(&symbol)
    .bind(&name)
    .bind(&kind)
    .bind(req.notes.as_deref().map(str::trim).filter(|s| !s.is_empty()))
    .bind(req.summary.as_deref().map(str::trim).filter(|s| !s.is_empty()))
    .bind(next_dividend_at)
    .bind(current_price)
    .fetch_one(&state.db)
    .await
    .map_err(mapear_erro_criacao)?;

    Ok((
        StatusCode::CREATED,
        Json(buscar_posicao(&state, id, wallet).await?),
    ))
}

fn mapear_erro_criacao(e: sqlx::Error) -> AppError {
    // `investments_symbol_unique` violado é o usuário criando o mesmo ativo
    // duas vezes. Sem este mapeamento, viraria 500 — e o cliente não teria como
    // saber que é duplicata.
    if let sqlx::Error::Database(ref db) = e
        && db.constraint() == Some("investments_symbol_unique")
    {
        return AppError::Conflict(
            "você já registrou este ativo; use o existente para registrar mais movimentos".into(),
        );
    }
    AppError::from(e)
}

// ── GET /investments ──────────────────────────────────────────────────────────

pub async fn list_investments(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Query(q): Query<InvestmentQuery>,
) -> AppResult<Json<PortfolioResponse>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;

    // Filtro por símbolo: `BOND` e `OTHER` não têm ticker de bolsa, então
    // valida o formato como texto livre. Sem isto, filtrar por
    // "Tesouro Selic 2029" seria recusado como formato inválido.
    let filtro_simbolo = match q.symbol.as_deref() {
        None => None,
        Some(s) => Some(normalizar_simbolo(s, "OTHER")?),
    };

    let ids: Vec<Uuid> = sqlx::query_scalar(
        "SELECT id FROM investments
          WHERE wallet_id = $1
            AND ($2::text IS NULL OR symbol = $2)
          ORDER BY symbol",
    )
    .bind(wallet)
    .bind(filtro_simbolo.as_deref())
    .fetch_all(&state.db)
    .await?;

    let mut items = Vec::with_capacity(ids.len());
    for id in ids {
        items.push(buscar_posicao(&state, id, wallet).await?);
    }

    let somar = |f: fn(&PositionResponse) -> Decimal| -> Decimal {
        items.iter().map(f).sum::<Decimal>()
    };

    Ok(Json(PortfolioResponse {
        total_cost_basis: somar(|p| p.cost_basis),
        total_realized_pnl: somar(|p| p.realized_pnl),
        total_dividends: somar(|p| p.dividends_total),
        total_fees: somar(|p| p.fees_total),
        positions_count: items.len() as i64,
        items,
    }))
}

// ── GET /investments/{id} ─────────────────────────────────────────────────────

pub async fn get_investment(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
) -> AppResult<Json<PositionResponse>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;
    Ok(Json(buscar_posicao(&state, id, wallet).await?))
}

// ── PATCH /investments/{id} ────────────────────────────────────────────────────

pub async fn update_investment(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
    Json(req): Json<UpdateInvestment>,
) -> AppResult<Json<PositionResponse>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;
    garantir_dono(&state, id, wallet).await?;

    // String vazia LIMPA o campo: é assim que o usuário apaga a data que
    // digitou, já que não mandamos `null` como "apague" (que seria ambíguo
    // com "não mexe").
    let next_dividend_at = match req.next_dividend_at.as_deref() {
        Some(s) if s.trim().is_empty() => Some(None),
        Some(s) => Some(Some(parse_data(s)?)),
        None => None,
    };

    let current_price = match req.current_price.as_deref() {
        Some(s) if s.trim().is_empty() => Some(None),
        Some(s) => {
            let p = parse_decimal(s, "current_price")?;
            if p <= Decimal::ZERO {
                return Err(AppError::Validation(
                    "preço atual deve ser maior que zero".into(),
                ));
            }
            Some(Some(p))
        }
        None => None,
    };

    sqlx::query(
        "UPDATE investments
            SET name        = COALESCE($3, name),
                kind        = COALESCE($4, kind),
                notes       = COALESCE($5, notes),
                summary     = COALESCE($6, summary),
                next_dividend_at = COALESCE($7, next_dividend_at),
                current_price    = COALESCE($8, current_price),
                updated_at  = now()
          WHERE id = $1 AND wallet_id = $2",
    )
    .bind(id)
    .bind(wallet)
    .bind(req.name.as_deref().map(str::trim).filter(|s| !s.is_empty()))
    .bind(req.kind.as_deref().map(str::trim).filter(|s| !s.is_empty()))
    .bind(req.notes.as_deref().map(str::trim).filter(|s| !s.is_empty()))
    .bind(req.summary.as_deref().map(str::trim).filter(|s| !s.is_empty()))
    .bind(next_dividend_at)
    .bind(current_price)
    .execute(&state.db)
    .await
    .map_err(mapear_erro_criacao)?;

    Ok(Json(buscar_posicao(&state, id, wallet).await?))
}

// ── POST /investments/{id}/movements ───────────────────────────────────────────

/// Registra compra, venda ou dividendo.
///
/// É a ÚNICA rota que mexe no ledger de investimento, e ela sempre o faz:
/// uma posição registrada sem movimento correspondente existiria só na tabela
/// `investments` e o dinheiro da carteira não bateria com o que o app mostra
/// investido.
pub async fn create_movement(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
    Json(req): Json<NewMovement>,
) -> AppResult<(StatusCode, Json<MovementResponse>)> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;
    garantir_dono(&state, id, wallet).await?;

    let tipo = req.kind.trim().to_ascii_uppercase();
    let tipo_enum = match tipo.as_str() {
        "BUY" => TransactionType::InvestmentBuy,
        "SELL" => TransactionType::InvestmentSell,
        "DIVIDEND" => TransactionType::InvestmentDividend,
        other => {
            return Err(AppError::Validation(format!(
                "tipo de movimento inválido: '{other}'. Use BUY, SELL ou DIVIDEND."
            )));
        }
    };

    let quantity = parse_decimal(&req.quantity, "quantity")?;
    let amount = parse_decimal(&req.amount, "amount")?;
    let fees = match req.fees.as_deref() {
        Some(s) if !s.trim().is_empty() => parse_decimal(s, "fees")?,
        _ => Decimal::ZERO,
    };

    if quantity <= Decimal::ZERO {
        return Err(AppError::Validation("quantidade deve ser maior que zero".into()));
    }
    if amount <= Decimal::ZERO {
        return Err(AppError::Validation("valor deve ser maior que zero".into()));
    }
    if fees < Decimal::ZERO {
        return Err(AppError::Validation("custos não podem ser negativos".into()));
    }

    // Dividendo não tem preço unitário; compra e venda têm.
    let price_per_unit = match (tipo_enum, req.price_per_unit.as_deref()) {
        (TransactionType::InvestmentDividend, _) => None,
        (_, Some(s)) if !s.trim().is_empty() => {
            let p = parse_decimal(s, "price_per_unit")?;
            if p <= Decimal::ZERO {
                return Err(AppError::Validation("preço unitário deve ser maior que zero".into()));
            }
            Some(p)
        }
        _ => None,
    };

    if tipo_enum != TransactionType::InvestmentDividend && price_per_unit.is_none() {
        return Err(AppError::Validation(
            "compra e venda exigem price_per_unit".into(),
        ));
    }

    let traded_at = parse_data(&req.traded_at)?;

    // Venda a descoberto é recusado aqui com mensagem clara, ANTES do trigger.
    // O trigger continua existindo (bypass de API e concorrência), mas a
    // mensagem do trigger chega como 500 genérico se não vier do Postgres —
    // então a regra de negócio é checada duas vezes: para dar erro legível e
    // para não depender só do banco.
    if tipo_enum == TransactionType::InvestmentSell {
        let posicao = quantidade_atual(&state, id).await?;
        if posicao < quantity {
            return Err(AppError::Validation(format!(
                "venda a descoberto: você tem {posicao} e tentou vender {quantity}"
            )));
        }
    }

    // `query_scalar` decodifica a PRIMEIRA coluna, então um `RETURNING` com
    // várias colunas não funciona aqui: ele tenta decodificar `(symbol, name)`
    // como `TEXT` e o banco devolve erro de tipo em vez de 500 genérico.
    let ativo = sqlx::query_as::<_, (String, String)>(
        "SELECT symbol, name FROM investments WHERE id = $1 AND wallet_id = $2",
    )
    .bind(id)
    .bind(wallet)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NotFound("investimento"))?;

    let descricao = match tipo_enum {
        TransactionType::InvestmentBuy => {
            format!("Compra: {} {}", trim_decimal(quantity), ativo.0)
        }
        TransactionType::InvestmentSell => {
            format!("Venda: {} {}", trim_decimal(quantity), ativo.0)
        }
        TransactionType::InvestmentDividend => format!("Dividendo: {}", ativo.0),
        _ => ativo.1.clone(),
    };

    // Custos entram no valor do lançamento em compra e venda, e são dinheiro
    // que sai. No dividendo não há custo — não se paga corretagem para receber.
    let valor_lancamento = if tipo_enum == TransactionType::InvestmentDividend {
        amount
    } else {
        amount + fees
    };

    // Ledger e registro do movimento na MESMA transação: o trigger deferrable
    // de venda a descoberto passa a abortar os dois lados juntos. Sem isso,
    // dois SELL concorrentes creditavam a carteira 2× e o segundo INSERT
    // morria no trigger — crédito no ledger sem movimento registrado.
    let mut tx = state.db.begin().await?;

    let transaction_id = ledger::postar_em_com_categoria(
        &mut tx,
        wallet,
        tipo_enum,
        Money::new(valor_lancamento),
        Some(&descricao),
        Some(serde_json::json!({
            "investment_id": id,
            "symbol": ativo.0,
            "movement": tipo,
        })),
        None,
        Some(auth.user_id()),
    )
    .await?;

    // `cost_basis_after` fica para o cálculo consolidado, não por aqui: a
    // posição é derivada da série inteira (ver `calcular_posicao`), e gravar o
    // acumulado aqui exigiria recalcular a cada leitura de qualquer outro ativo.
    let movement_id = sqlx::query_scalar::<_, Uuid>(
        "INSERT INTO investment_transactions
             (investment_id, type, quantity, price_per_unit, amount, fees, traded_at, note)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
         RETURNING id",
    )
    .bind(id)
    .bind(&tipo)
    .bind(quantity)
    .bind(price_per_unit)
    .bind(amount)
    .bind(fees)
    .bind(traded_at)
    .bind(req.note.as_deref().map(str::trim).filter(|s| !s.is_empty()))
    .fetch_one(&mut *tx)
    .await?;

    tx.commit().await?;

    Ok((
        StatusCode::CREATED,
        Json(MovementResponse {
            id: movement_id,
            investment_id: id,
            kind: tipo,
            quantity,
            price_per_unit,
            amount: Money::new(amount),
            fees: Money::new(fees),
            traded_at,
            note: req
                .note
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_string),
            transaction_id: Some(transaction_id),
        }),
    ))
}

// ── GET /investments/{id}/movements ────────────────────────────────────────────

pub async fn list_movements(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
) -> AppResult<Json<Vec<MovementResponse>>> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;
    garantir_dono(&state, id, wallet).await?;

    let linhas = sqlx::query_as::<_, MovementRow>(
        "SELECT m.id, m.investment_id, m.type, m.quantity, m.price_per_unit,
                m.amount, m.fees, m.traded_at, m.note
           FROM investment_transactions m
          WHERE m.investment_id = $1
          ORDER BY m.traded_at DESC, m.created_at DESC",
    )
    .bind(id)
    .fetch_all(&state.db)
    .await?;

    Ok(Json(linhas.into_iter().map(MovementResponse::from).collect()))
}

// ── GET /investments/summary ───────────────────────────────────────────────────

/// Consolidado da carteira de investimento, para a tela mostrar os totais.
pub async fn summary(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
) -> AppResult<Json<PortfolioSummaryResponse>> {
    let p = list_investments(
        State(state.clone()),
        auth,
        Query(InvestmentQuery { symbol: None }),
    )
    .await?;

    let itens = p.0;

    Ok(Json(PortfolioSummaryResponse {
        total_cost_basis: Money::new(itens.total_cost_basis),
        total_realized_pnl: Money::new(itens.total_realized_pnl),
        total_dividends: Money::new(itens.total_dividends),
        total_fees: Money::new(itens.total_fees),
        positions_count: itens.positions_count,
        unrealized_note: "o app não calcula ganho não realizado: informe \
                          o preço atual no resumo do ativo se quiser comparar.",
    }))
}

// ── POST /investments/lookup ──────────────────────────────────────────────────

#[derive(Deserialize)]
pub struct LookupRequest {
    pub symbol: String,
    #[serde(default)]
    pub kind: Option<String>,
}

/// Resposta do lookup: a IA diz quem é o ativo, o brapi diz quando paga.
/// `next_dividend` vem do mercado, não da IA.
#[derive(Serialize)]
pub struct LookupResponse {
    found: bool,
    name: Option<String>,
    summary: Option<String>,
    /// Próxima data de pagamento (AAAA-MM-DD), se o ativo paga e a fonte
    /// sabe (brapi para ação; statusinvest para ação e fundo). Nulo = não
    /// toque no campo.
    next_dividend: Option<chrono::NaiveDate>,
}

/// Pesquisa o ativo na IA para o app preencher nome e resumo do formulário.
///
/// Três regras organizam o handler:
///
/// 1. **Valida o símbolo ANTES de chamar a IA.** Cada chamada custa dinheiro
///    (~US$ 0,000003, barato mas não zero). `"max renda"` — o nome do fundo
///    no lugar do código, o caso real que motivou a validação — devolve 422
///    sem gastar um centavo de token.
/// 2. **`found: false` não é erro.** Ativo que não existe devolve 200 com
///    `found: false` e o app não preenche nada. Erro seria mentir: "não
///    achei" e "deu ruim" são estados diferentes, e só o segundo aparece no
///    log.
/// 3. **O nome vem do mercado (rápido, grátis) e ancora o resumo da IA.**
///    O Yahoo devolve o `longname` oficial da B3 (match exato com `.SA`);
///    esse nome entra como contexto no prompt — o modelo que só vê o ticker
///    adivinha a empresa errada (CPTS11 → "Cyrela"; o certo é o FII
///    Capitania Securities II). Sem mercado (Yahoo fora, renda fixa), a IA
///    trabalha só com o ticker e o nome dela vira fallback. A data do
///    dividendo vem do brapi (ação) ou do statusinvest (fundo), em paralelo
///    com a IA: o custo total é o da IA (a lenta), não a soma. Fonte fora =
///    só perde o campo dela.
///
/// O handler NÃO toca no banco: não lê nem escreve carteira, é uma consulta
/// stateless. Autenticação existe para não virar balde furado do nosso saldo
/// na OpenRouter — qualquer um descobrindo a URL gastaria nosso crédito.
pub async fn lookup(
    State(state): State<AppState>,
    // O extractor roda e autentica mesmo com o nome ignorado: aqui o token
    // serve para EXIGIR login, não para saber de quem é a consulta.
    auth: crate::auth::AuthUser,
    Json(req): Json<LookupRequest>,
) -> AppResult<Json<LookupResponse>> {
    // Consulta à IA tem custo por chamada; fica no Premium junto com a cotação.
    crate::routes::billing::exigir_premium_do_token(&auth)?;
    let kind = req
        .kind
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or("STOCK");

    // Normaliza e valida. Símbolo inválido morre aqui — antes de qualquer
    // chamada externa, antes de qualquer token gasto.
    let symbol = normalizar_simbolo(&req.symbol, kind)?;

    // Nome oficial primeiro (rápido, grátis): ele vira CONTEXTO da IA — o
    // modelo que só vê o ticker adivinha a empresa errada (CPTS11 →
    // "Cyrela"), com o nome oficial ele resume a empresa certa.
    let nome_mercado = state.cotacao.nome_oficial(&symbol, kind).await;

    // IA (lenta, paga) e brapi (rápido, de graça) em paralelo: o `?` da IA
    // sobe depois do join, então brapi fora não derruba o lookup. A latência
    // da busca do nome (~centenas de ms) é paga antes, e some na da IA.
    let (pesquisa, dividendo) = tokio::join!(
        state.ai.pesquisar(&symbol, nome_mercado.as_deref()),
        state.cotacao.proximo_dividendo(&symbol, kind),
    );
    let pesquisa = pesquisa?;

    // found=false descarta tudo, inclusive a data e o nome oficial: "não
    // preencha nada" é o contrato, e um campo preenchido no formulário
    // vazio seria a metade errada.
    let next_dividend = pesquisa.found.then_some(dividendo).flatten();
    // Nome do mercado vence o da IA; sem mercado, o da IA continua valendo
    // — sugestão quebrada nunca arrasta o que funciona.
    let name = pesquisa
        .found
        .then_some(nome_mercado)
        .flatten()
        .or(pesquisa.name);

    Ok(Json(LookupResponse {
        found: pesquisa.found,
        name,
        summary: pesquisa.summary,
        next_dividend,
    }))
}

/// Corpo de `POST /investments/quote`. Mesmo formato do lookup — o app já
/// tem o símbolo e o tipo na tela quando sugere o preço.
#[derive(Deserialize)]
pub struct QuoteRequest {
    pub symbol: String,
    #[serde(default)]
    pub kind: Option<String>,
}

/// Sugestão de preço. Tudo nulo = não sugira nada (renda fixa, ticker não
/// listado, provedor fora). O app só preenche o campo com valor não-nulo —
/// e a pessoa pode limpar antes de salvar: sugestão, não verdade.
#[derive(Serialize)]
pub struct QuoteResponse {
    /// "56.00" — dinheiro na API é sempre string, nunca número JSON.
    price: Option<crate::money::Money>,
    /// Quem respondeu ("brapi" | "yahoo") para o app mostrar a origem.
    source: Option<String>,
    /// Momento da cotação segundo o provedor (RFC3339), se veio.
    as_of: Option<String>,
}

/// Sugere preço de mercado para o ativo. Ação → brapi; FII → Yahoo `.SA`;
/// renda fixa → sem fonte viva (a API do Tesouro Direto morreu), resposta
/// vazia.
///
/// **Nunca grava nada**: é o usuário quem confirma o valor na tela de
/// preço. Provedor fora devolve 200 vazio em vez de 500 — sugestão não pode
/// ser a razão de a tela quebrar.
pub async fn quote(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Json(req): Json<QuoteRequest>,
) -> AppResult<Json<QuoteResponse>> {
    crate::routes::billing::exigir_premium_do_token(&auth)?;
    let kind = req
        .kind
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or("STOCK");

    // Mesma validação do lookup: 422 ensina a escrever o ticker certo, e
    // sem rede gasta.
    let symbol = normalizar_simbolo(&req.symbol, kind)?;

    let sugestao = state.cotacao.preco(&symbol, kind).await;

    Ok(Json(QuoteResponse {
        price: sugestao.preco,
        source: sugestao.fonte,
        as_of: sugestao.as_of,
    }))
}

// ── cálculo ───────────────────────────────────────────────────────────────────

/// Posição derivada da série de movimentos.
///
/// CUSTO MÉDIO, não FIFO: é o método padrão do varejo brasileiro e o que a
/// maioria das corretoras mostra. A consequência importante: **vender não
/// altera o preço médio**. O custo médio só sobe quando se compra, e só desce
/// quando se zera a posição.
///
/// A sequência importa: os movimentos são ordenados por data, e não por id.
/// Dois registros com a mesma data são aplicados na ordem de criação (o `id`
/// do desempate), que é o que o usuário digitou — reordenar por data depois
/// mudaria o resultado de quem comprou e vendeu no mesmo dia.
///
/// Números de uma posição, derivados da série de movimentos.
///
/// Struct em vez de tupla de 6 `Decimal`: os nomes são o que torna a ordem
/// legível, e é a ordem que este código mais erra — trocar dois campos de tipo
/// igual compila, passa em todos os testes e devolve o número errado para a
/// tela. Um struct nomeado não tem como ser trocado em silêncio.
#[derive(Debug, Clone, Copy, PartialEq)]
struct Posicao {
    quantity: Decimal,
    average_price: Decimal,
    cost_basis: Decimal,
    realized_pnl: Decimal,
    fees_total: Decimal,
    dividends_total: Decimal,
}

/// Uma linha crua de `investment_transactions`, na ordem das colunas do SELECT.
type LinhaMovimento = (
    String,
    Decimal,
    Option<Decimal>,
    Decimal,
    Decimal,
);

async fn calcular_posicao(state: &AppState, id: Uuid) -> AppResult<Posicao> {

    let movimentos = sqlx::query_as::<_, LinhaMovimento>(
        "SELECT type, quantity, price_per_unit, amount, fees
           FROM investment_transactions
          WHERE investment_id = $1
          ORDER BY traded_at, created_at, id",
    )
    .bind(id)
    .fetch_all(&state.db)
    .await?;

    let mut quantidade = Decimal::ZERO;
    let mut custo = Decimal::ZERO;
    let mut realizado = Decimal::ZERO;
    let mut custos_total = Decimal::ZERO;
    let mut dividendos_total = Decimal::ZERO;

    for (tipo, qtd, _preco, valor, fees) in movimentos {
        custos_total += fees;

        match tipo.as_str() {
            "BUY" => {
                // O custo inclui os custos: R$ 35,00 + R$ 1,50 de corretagem
                // custam R$ 36,50 por ação. Deixar a corretagem fora faz o
                // ganho calculado ser maior que o real — que é o erro clássico
                // de quem monta a planilha no papel.
                custo += valor + fees;
                quantidade += qtd;
            }
            "SELL" => {
                // Preço médio ANTES da venda: vender não muda a média.
                let media = media_de(&custo, &quantidade);
                realizado += valor - (media * qtd) - fees;

                let custo_vendido = media * qtd;
                custo -= custo_vendido;
                quantidade -= qtd;

                // Vender tudo zera a posição: sem isto, `custo` ficaria com
                // resto de arredondamento e o preço médio da posição vazia
                // viraria um número enorme sem sentido.
                if quantidade <= Decimal::ZERO {
                    quantidade = Decimal::ZERO;
                    custo = Decimal::ZERO;
                }
            }
            "DIVIDEND" => {
                // Não mexe em custo nem quantidade: dividendo é retorno sobre o
                // capital que já está na posição.
                dividendos_total += valor;
                realizado += valor;
            }
            _ => {}
        }
    }

    let media = media_de(&custo, &quantidade);

    Ok(Posicao {
        quantity: quantidade,
        average_price: media,
        cost_basis: custo,
        realized_pnl: realizado,
        fees_total: custos_total,
        dividends_total: dividendos_total,
    })
}

/// Preço médio. Com posição zerada o preço médio é zero, não um número
/// indefinido — dividir por zero em `Decimal` estoura, e o app precisa mostrar
/// R$ 0,00 numa posição que não existe em vez de quebrar.
fn media_de(custo: &Decimal, quantidade: &Decimal) -> Decimal {
    if *quantidade <= Decimal::ZERO {
        return Decimal::ZERO;
    }
    let m = *custo / *quantidade;
    // Escala 4: o preço unitário no banco tem 4 casas, e média de fração de
    // ação pode ter mais — arredondar aqui mantém a soma fechando.
    m.round_dp(4)
}

/// Valor de mercado e ganho não realizado, a partir do preço digitado.
///
/// Todos os três devolvem `None` quando o preço é desconhecido. Mostrar
/// `R$ 0,00` para "não sei quanto vale" é o pior tipo de erro numa tela de
/// dinheiro: parece um número verdadeiro e não é.
fn valor_de_mercado(
    preco_atual: Option<Decimal>,
    quantidade: Decimal,
    custo: Decimal,
) -> (Option<Decimal>, Option<Decimal>, Option<Decimal>) {
    let Some(preco) = preco_atual else {
        return (None, None, None);
    };
    if preco <= Decimal::ZERO {
        return (None, None, None);
    }

    let valor = (preco * quantidade).round_dp(4);
    let ganho = (valor - custo).round_dp(4);

    // Retorno percentual: `None` com custo zerado. Dividir por zero em
    // `Decimal` estoura, e uma posição zerada não tem "retorno" de coisa
    // nenhuma — mostrar 0% seria tão errado quanto mostrar infinito.
    let retorno = if custo > Decimal::ZERO {
        Some((ganho / custo).round_dp(6))
    } else {
        None
    };

    (Some(valor), Some(ganho), retorno)
}

async fn buscar_posicao(state: &AppState, id: Uuid, wallet: Uuid) -> AppResult<PositionResponse> {
    let row = sqlx::query_as::<_, (
        Uuid,
        String,
        String,
        String,
        Option<String>,
        Option<String>,
        Option<chrono::NaiveDate>,
        Option<Decimal>,
        chrono::DateTime<chrono::Utc>,
    )>(
        "SELECT id, symbol, name, kind, notes, summary, next_dividend_at,
                current_price, created_at
           FROM investments WHERE id = $1 AND wallet_id = $2",
    )
    .bind(id)
    .bind(wallet)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NotFound("investimento"))?;

    let pos = calcular_posicao(state, id).await?;

    let movements_count = sqlx::query_scalar::<_, i64>(
        "SELECT count(*) FROM investment_transactions WHERE investment_id = $1",
    )
    .bind(id)
    .fetch_one(&state.db)
    .await?;

    let (valor_atual, ganho_nao_realizado, retorno) =
        valor_de_mercado(row.7, pos.quantity, pos.cost_basis);

    Ok(PositionResponse {
        id: row.0,
        symbol: row.1,
        name: row.2,
        kind: row.3,
        notes: row.4,
        summary: row.5,
        next_dividend_at: row.6,
        current_price: row.7,
        created_at: row.8,
        quantity: pos.quantity,
        average_price: pos.average_price,
        cost_basis: pos.cost_basis,
        realized_pnl: pos.realized_pnl,
        fees_total: pos.fees_total,
        dividends_total: pos.dividends_total,
        movements_count,
        market_value: valor_atual,
        unrealized_pnl: ganho_nao_realizado,
        unrealized_return: retorno,
    })
}

async fn quantidade_atual(state: &AppState, id: Uuid) -> AppResult<Decimal> {
    Ok(sqlx::query_scalar::<_, Decimal>(
        "SELECT COALESCE(SUM(
                    CASE type
                        WHEN 'BUY'  THEN  quantity
                        WHEN 'SELL' THEN -quantity
                        ELSE 0
                    END), 0)
           FROM investment_transactions WHERE investment_id = $1",
    )
    .bind(id)
    .fetch_one(&state.db)
    .await?)
}

// ── helpers ───────────────────────────────────────────────────────────────────

async fn garantir_dono(state: &AppState, id: Uuid, wallet: Uuid) -> AppResult<()> {
    let existe: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM investments WHERE id = $1 AND wallet_id = $2)",
    )
    .bind(id)
    .bind(wallet)
    .fetch_one(&state.db)
    .await?;

    if !existe {
        return Err(AppError::NotFound("investimento"));
    }
    Ok(())
}

/// Símbolo em caixa alta, sem espaços, e no formato de ativo brasileiro.
///
/// `petr4`, `PETR4 ` e ` PETR4` são o mesmo ativo — e sem normalizar, o
/// usuário criaria três posições separadas do mesmo papel e o consolidado
/// estaria errado sem nenhum erro visível.
///
/// O formato é validado porque aconteceu: o usuário digitou "max renda" (o
/// nome do fundo) no campo de código, e `MXRF11` ficou com nome "max renda".
/// Aceitar qualquer texto no campo de código transforma um engano de digitação
/// em dado permanente que ninguém percebe.
///
/// Regras (B3): ação `AAAA3` (PETR4, VALE3), unitário `AAAA4` (BBDC4,
/// AALR11 — unitário tem letra+número), FII `AAAA11` (MXRF11, HGLG11).
///
/// O comprimento NÃO é fixo: `PETR4` tem 5 caracteres e `MXRF11` tem 6, e
/// `AALR11` tem 6 com sufixo de unitário. A regra real é "4 letras seguidas
/// de 1 a 4 dígitos" — e é isso que se valida. Um `len() == 7` fixo recusaria
/// a maioria das ações.
///
/// `kind` muda a regra: renda fixa ("Tesouro Selic 2029") e fundos abertos
/// não têm ticker de bolsa, e recusar o nome lá seria o app atrapalhando.
fn normalizar_simbolo(s: &str, kind: &str) -> AppResult<String> {
    let limpo = s.trim().to_ascii_uppercase();

    if limpo.is_empty() {
        return Err(AppError::Validation("símbolo do ativo é obrigatório".into()));
    }
    if comprido(&limpo) > 20 {
        return Err(AppError::Validation(
            "símbolo do ativo muito longo (máx. 20 caracteres)".into(),
        ));
    }

    if eh_ticker_bolsa(kind) {
        // `chars()` e não slicing por byte: `&s[4..]` panic em string multibyte
        // quando os 4 primeiros bytes não são um caractere inteiro.
        let c: Vec<char> = limpo.chars().collect();
        let tem_4_letras = c.len() >= 5 && c[..4].iter().all(|x| x.is_ascii_alphabetic());
        let digitos_ok = tem_4_letras
            && (1..=4).contains(&(c.len() - 4))
            && c[4..].iter().all(|x| x.is_ascii_digit());

        if !digitos_ok {
            return Err(AppError::Validation(format!(
                "'{limpo}' não parece um código de ativo. Ação: 4 letras + números \
                 (PETR4, VALE3); fundo imobiliário: 4 letras + 4 números (MXRF11). \
                 Se for renda fixa, escreva no campo 'nome' e escolha o tipo correspondente."
            )));
        }
    }

    Ok(limpo)
}

/// Contagem de caracteres, não de bytes: o limite de 20 é para o que a pessoa
/// lê, e `str::len()` contaria bytes (um acento ocupa 2).
fn comprido(s: &str) -> usize {
    s.chars().count()
}

/// Tipos que têm ticker de bolsa. `STOCK` e `FII` seguem o padrão B3; `BOND`
/// (tesouro, CDB) e `OTHER` são digitados pelo nome.
fn eh_ticker_bolsa(kind: &str) -> bool {
    matches!(kind, "STOCK" | "FII")
}

fn parse_decimal(s: &str, campo: &str) -> AppResult<Decimal> {
    let t = s.trim();
    if t.is_empty() {
        return Err(AppError::Validation(format!("{campo} é obrigatório")));
    }
    // Aceita vírgula porque o app digita em pt-BR; `Decimal::from_str` só
    // entende ponto e recusaria "1234,56" com um erro de parse, não de
    // validação.
    let normalizado = t.replace(',', ".");
    Decimal::from_str(&normalizado)
        .map_err(|_| AppError::Validation(format!("{campo} deve ser decimal")))
}

/// Data no formato que o cliente digita. Aceita `dd/mm/aaaa` (padrão de quem
/// usa o app — mesmo racional do `parse_decimal`, que aceita vírgula "porque
/// o app digita em pt-BR") e `YYYY-MM-DD` (ISO, o formato de armazenamento).
/// Os dois são desambíguos pelo separador: barra só num formato, hífen no
/// outro. A mensagem é amigável e não nomeia formato: nada de jargão vaza
/// pra tela do usuário.
fn parse_data(s: &str) -> AppResult<chrono::NaiveDate> {
    let t = s.trim();
    chrono::NaiveDate::parse_from_str(t, "%Y-%m-%d")
        .or_else(|_| chrono::NaiveDate::parse_from_str(t, "%d/%m/%Y"))
        .map_err(|_| AppError::Validation("data inválida".into()))
}

/// `Decimal` exibe `35.0000` com scale explícito; a tela quer `35`.
fn trim_decimal(d: Decimal) -> String {
    let s = d.normalize().to_string();
    s
}

// ── tipos de linha ────────────────────────────────────────────────────────────

type MovementRow = (
    Uuid,
    Uuid,
    String,
    Decimal,
    // `Option`, NÃO `Decimal`: dividendo não tem preço unitário (a migration
    // exige `type = 'DIVIDEND' OR price_per_unit IS NOT NULL`), e decodificar
    // NULL num `Decimal` dá 500 "unexpected null" na leitura do extrato —
    // exatamente o que acontecia em `GET /investments/{id}/movements` depois
    // que o usuário registrava o primeiro dividendo.
    Option<Decimal>,
    Decimal,
    Decimal,
    chrono::NaiveDate,
    Option<String>,
);

impl From<MovementRow> for MovementResponse {
    fn from(r: MovementRow) -> Self {
        MovementResponse {
            id: r.0,
            investment_id: r.1,
            kind: r.2,
            quantity: r.3,
            price_per_unit: r.4,
            amount: Money::new(r.5),
            fees: Money::new(r.6),
            traded_at: r.7,
            note: r.8,
            // O `transaction_id` vive no ledger, não na tabela de movimentos:
            // opcional aqui porque um movimento importado antes do ledger
            // existir não teria um.
            transaction_id: None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn media_com_posicao_vazia_e_zero() {
        // Dividir por zero estoura em `Decimal`. Uma posição que não existe
        // tem de mostrar R$ 0,00, não quebrar a tela.
        assert_eq!(media_de(&Decimal::from(100), &Decimal::ZERO), Decimal::ZERO);
        assert_eq!(media_de(&Decimal::ZERO, &Decimal::ZERO), Decimal::ZERO);
    }

    #[test]
    fn media_e_custo_sobre_quantidade() {
        // 550,00 / 15 = 36,6666… → arredonda em 4 casas: 36,6667.
        // É este arredondamento que a brokerage mostra, e é por isso que a
        // média não fecha com a última casa.
        let custo = Decimal::new(55000, 2);
        let qtd = Decimal::new(1500, 2); // 15
        assert_eq!(media_de(&custo, &qtd), Decimal::new(366667, 4));
    }

    #[test]
    fn media_sobe_na_compra_e_nao_muda_na_venda() {
        // A propriedade que faz o método ser "custo médio" e não FIFO: vender
        // NÃO altera o preço médio. Se alguém mudar a fórmula para recalcular
        // a média após a venda, este teste falha.
        //
        // Cenário: compra 10 a 35,00 (média 35,00), compra 5 a 40,00 (média
        // 36,6667), vende 3. A média da posição restante continua 36,6667.
        let custo = Decimal::new(55000, 2);
        let qtd = Decimal::new(1500, 2);
        let media_apos_duas_compras = media_de(&custo, &qtd);

        let vendido = Decimal::from(3);
        let custo_restante = custo - (media_apos_duas_compras * vendido);
        let qtd_restante = qtd - vendido;

        assert_eq!(
            media_de(&custo_restante, &qtd_restante),
            media_apos_duas_compras,
            "vender não pode mudar o preço médio da posição restante"
        );
    }

    #[test]
    fn ganho_realizado_de_una_venda() {
        // 10 ações a 35,00 (média 35,00), vende 3 a 38,00:
        // recebido 114,00 − custo dos 3 (105,00) = R$ 9,00 de ganho.
        let custo = Decimal::new(35000, 2);
        let qtd = Decimal::new(1000, 2); // 10
        let media = media_de(&custo, &qtd);
        assert_eq!(media, Decimal::new(3500, 2));

        let recebido = Decimal::new(11400, 2); // 3 × 38,00
        let ganho = recebido - (media * Decimal::from(3));
        assert_eq!(ganho, Decimal::new(900, 2), "R$ 9,00 de ganho");
    }

    #[test]
    fn corretagem_entra_no_custo_medio() {
        // 10 ações a 35,00 + R$ 1,50 de corretagem = R$ 351,50 no custo, ou
        // R$ 35,15 por ação. Deixar a corretagem fora faz o ganho calculado
        // ser MAIOR que o real — o erro clássico de planilha no papel.
        let custo_com_corretagem = Decimal::new(35150, 2);
        let qtd = Decimal::from(10);
        assert_eq!(media_de(&custo_com_corretagem, &qtd), Decimal::new(3515, 2));

        let custo_sem_corretagem = Decimal::new(35000, 2);
        assert_eq!(media_de(&custo_sem_corretagem, &qtd), Decimal::new(3500, 2));
    }

    #[test]
    fn zera_posicao_ao_vender_tudo() {
        // Vender tudo tem de zerar custo E quantidade. Com custo redondo
        // (351,50 ÷ 10 = 35,15 exato) o resto já é zero; o caso que importa é
        // quando a média é fracionária, e é aí que o resto de arredondamento
        // apareceria sem o zero explícito no fim do cálculo.
        let custo = Decimal::new(35150, 2);
        let qtd = Decimal::from(10);
        let media = media_de(&custo, &qtd);
        assert_eq!(media, Decimal::new(3515, 2));

        assert_eq!(custo - (media * qtd), Decimal::ZERO);

        // Média fracionária: 550,00 ÷ 15 = 36,6667 (arredondada). Vender os 15
        // multiplica a média arredondada de volta, e sobra 0,0005 — não zero.
        // É EXATAMENTE por isso que `calcular_posicao` zera custo e quantidade
        // explicitamente quando a posição fecha: sem isso, a posição vazia
        // carregaria um custo residual e o preço médio apareceria enorme.
        let custo2 = Decimal::new(55000, 2);
        let qtd2 = Decimal::new(1500, 2);
        let media2 = media_de(&custo2, &qtd2);
        assert_eq!(
            custo2 - (media2 * qtd2),
            Decimal::new(-5, 4),
            "sobra 0,0005 de arredondamento ao multiplicar a média pela quantidade"
        );

        // E o que o app mostra numa posição zerada: preço médio zero, não um
        // número indefinido.
        assert_eq!(media_de(&Decimal::ZERO, &Decimal::ZERO), Decimal::ZERO);
    }

    #[test]
    fn simbolo_normalizado_tem_caixa_alta_e_sem_espaco() {
        // 'petr4' e ' PETR4 ' são o mesmo ativo; sem normalizar viravam duas
        // posições do mesmo papel. Usa `OTHER` para exercitar só a caixa alta:
        // o formato B3 tem teste próprio abaixo.
        assert_eq!(normalizar_simbolo("petr4", "OTHER").unwrap(), "PETR4");
        assert_eq!(normalizar_simbolo("  petr4  ", "OTHER").unwrap(), "PETR4");
        assert_eq!(normalizar_simbolo("HGLG11", "OTHER").unwrap(), "HGLG11");
    }

    #[test]
    fn ticker_de_bolsa_com_formato_certo_e_aceito() {
        assert_eq!(normalizar_simbolo("petr4", "STOCK").unwrap(), "PETR4");
        assert_eq!(normalizar_simbolo("  vale3 ", "STOCK").unwrap(), "VALE3");
        assert_eq!(normalizar_simbolo("MXRF11", "FII").unwrap(), "MXRF11");
        assert_eq!(normalizar_simbolo("hglg11", "FII").unwrap(), "HGLG11");
    }

    #[test]
    fn ticker_fora_do_formato_e_recusado() {
        // O caso que aconteceu: o usuário digitou o NOME do fundo ("max
        // renda", com espaço) no campo de código. Aceitar transformava um
        // engano de digitação em dado permanente.
        assert!(
            normalizar_simbolo("max renda", "FII").is_err(),
            "nome com espaço não é ticker"
        );
        // 4 letras + 1 a 4 dígitos. `PETR45` (2 dígitos) é VÁLIDO: existem ações
        // com dois dígitos. `PETR4567` (4 dígitos) também é válido (unitário).
        assert!(normalizar_simbolo("PETR", "STOCK").is_err(), "sem número");
        assert!(normalizar_simbolo("PETR45", "STOCK").is_ok(), "2 dígitos é válido");
        assert!(normalizar_simbolo("MXRF11", "FII").is_ok(), "FII com 4 dígitos");
        assert!(normalizar_simbolo("PETR4567", "STOCK").is_ok(), "4 dígitos é válido (unitário)");
        // 5 dígitos ultrapassa o sufixo máximo da B3.
        assert!(normalizar_simbolo("PETR45678", "STOCK").is_err(), "5 dígitos");
        assert!(normalizar_simbolo("4PETR", "STOCK").is_err(), "começa com número");
        assert!(normalizar_simbolo("PETR4A", "STOCK").is_err(), "letra no fim");
        assert!(normalizar_simbolo("A1B2C3", "STOCK").is_err(), "número no meio");
    }

    #[test]
    fn renda_fixa_aceita_nome_livre() {
        // Tesouro e CDB não têm ticker de bolsa. Recusar o nome lá seria o
        // app atrapalhando o usuário em vez de ajudar.
        assert_eq!(
            normalizar_simbolo("Tesouro Selic 2029", "BOND").unwrap(),
            "TESOURO SELIC 2029"
        );
        assert_eq!(normalizar_simbolo("CDB Banco X", "BOND").unwrap(), "CDB BANCO X");
    }

    #[test]
    fn ticker_com_acento_nao_panic() {
        // `&s[4..]` panicaria em string multibyte. Este é o teste que pega
        // a regressão se alguém voltar a fatiar por byte.
        assert!(normalizar_simbolo("AÇÃO1", "STOCK").is_err());
        assert!(normalizar_simbolo("ação", "BOND").is_ok());
    }

    #[test]
    fn simbolo_vazio_e_longo() {
        assert!(normalizar_simbolo("", "STOCK").is_err());
        assert!(normalizar_simbolo("   ", "STOCK").is_err());
        assert!(normalizar_simbolo(&"X".repeat(21), "OTHER").is_err());
    }

    #[test]
    fn decimal_aceita_virgula_pt_br() {
        // O app digita "1234,56"; recusar aqui daria um erro de parse em vez de
        // dizer que o campo é inválido.
        assert_eq!(
            parse_decimal("1234,56", "amount").unwrap(),
            Decimal::new(123456, 2)
        );
        assert_eq!(
            parse_decimal("35.00", "amount").unwrap(),
            Decimal::new(3500, 2)
        );
    }

    #[test]
    fn decimal_invalido_e_recusado() {
        assert!(parse_decimal("abc", "amount").is_err());
        assert!(parse_decimal("", "amount").is_err());
    }

    #[test]
    fn valor_de_mercado_sem_preco_devolve_none() {
        // Mostrar R$ 0,00 para "não sei o preço" é o pior erro possível numa
        // tela de dinheiro: parece um número verdadeiro e não é.
        let (v, g, r) = valor_de_mercado(None, Decimal::from(10), Decimal::from(350));
        assert!(v.is_none(), "sem preço, não há valor de mercado");
        assert!(g.is_none(), "sem preço, não há ganho não realizado");
        assert!(r.is_none(), "sem preço, não há retorno");
    }

    #[test]
    fn valor_de_mercado_calcula_a_partir_do_preco_digitado() {
        // 10 ações, preço atual 38,00, custo 351,50 (com corretagem):
        // valor 380,00 · ganho 28,50 · retorno 8,11% (28,50 ÷ 351,50).
        let (v, g, r) = valor_de_mercado(
            Some(Decimal::new(3800, 2)),
            Decimal::from(10),
            Decimal::new(35150, 2),
        );
        assert_eq!(v.unwrap(), Decimal::new(38000, 2), "valor de mercado R$ 380,00");
        assert_eq!(g.unwrap(), Decimal::new(2850, 2), "ganho R$ 28,50");
        assert_eq!(r.unwrap(), Decimal::new(81081, 6), "retorno 0,081081 = 8,11%");
    }

    #[test]
    fn valor_de_mercado_com_preco_zero_e_nulo() {
        // Preço zero é dado inválido, não "vale nada". O handler já recusa na
        // entrada, mas a função se protege porque pode ser chamada com dado
        // de antes da validação existir.
        let (v, _, _) = valor_de_mercado(Some(Decimal::ZERO), Decimal::from(10), Decimal::from(100));
        assert!(v.is_none());
    }

    #[test]
    fn retorno_e_nulo_quando_custo_e_zero() {
        // Posição zerada não tem retorno: dividir por zero estoura, e mostrar
        // 0% seria tão errado quanto mostrar infinito.
        let (_, g, r) = valor_de_mercado(
            Some(Decimal::new(3800, 2)),
            Decimal::ZERO,
            Decimal::ZERO,
        );
        assert_eq!(g.unwrap(), Decimal::ZERO, "sem custo, ganho é zero");
        assert!(r.is_none(), "sem custo, não há retorno percentual");
    }

    #[test]
    fn ganho_negativo_aparece_com_sinal() {
        // Ativo caiu: preço 30,00 com custo 351,50. O ganho é NEGATIVO, e é
        // isso que a tela tem de mostrar — esconder a perda atrás de valor
        // absoluto seria enganoso.
        let (v, g, r) = valor_de_mercado(
            Some(Decimal::new(3000, 2)),
            Decimal::from(10),
            Decimal::new(35150, 2),
        );
        assert_eq!(v.unwrap(), Decimal::new(30000, 2));
        assert_eq!(g.unwrap(), Decimal::new(-5150, 2), "perda de R$ 51,50");
        assert!(r.unwrap() < Decimal::ZERO, "retorno negativo");
    }

    #[test]
    fn fracao_de_acao_preserva_precisao() {
        // Corretoras vendem 0,005 ação. `f64` erraria a terceira casa e a
        // diferença apareceria no custo médio depois de algumas operações.
        let q = parse_decimal("0.005", "quantity").unwrap();
        assert_eq!(q, Decimal::new(5, 3));
        // 10 compras de 0,005 dão exatamente 0,05 — não 0.05000000000000001.
        assert_eq!(q * Decimal::from(10), Decimal::new(5, 2));
    }
}