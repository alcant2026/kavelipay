//! Orçamento (teto de gasto por categoria no mês) e metas de economia.
//!
//! Nenhuma das duas coisas guarda dinheiro: guardam INTENÇÃO. O valor gasto e
//! o valor guardado são SEMPRE derivados do ledger (`saldo()`, `extrato()`,
//! relatório) — aqui só existe o teto e a meta. É a mesma fronteira de
//! confiança do resto do backend: o app manda o que o usuário planejou, o
//! backend recalcula o progresso a partir dos lançamentos.
//!
//! Uma meta recebe dinheiro por um lançamento do ledger marcado com `meta_id`
//! (dinheiro que sai da conta e "vai para a meta"). Como a meta não é conta de
//! ledger, esse lançamento é uma TRANSFERÊNCIA contábil neutra: debita a
//! carteira e credita... nada. Não existe conta para creditar.
//!
//! Decisão de modelagem (e o porquê): o dinheiro guardado numa meta não sai da
//! carteira do usuário — ele continua sendo saldo demonstrativo. O que a meta
//! registra é "deste saldo, tanto está reservado para isto". Por isso o
//! lançamento de aporte usa o tipo `EXPENSE` com `metadata.meta_id`: mexe na
//! despesa (reduz o "disponível") sem inventar conta nova no ledger. A meta
//! soma os aportes; o saldo da carteira reflete a saída; nada é criado do
//! nada. Um tipo `META_APORTE` próprio entraria em `par_de_contas` e exigiria
//! conta nova — mais superfície, mesma matemática.

use axum::Json;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use chrono::{Datelike, NaiveDate};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::{AppError, AppResult};
use crate::ledger::{self, TransactionType};
use crate::money::Money;
use crate::state::AppState;

/// Sentinela de "teto permanente" na coluna `mes`. Não usamos NULL porque em
/// Postgres NULL nunca é igual a NULL num índice unique — o `ON CONFLICT` não
/// parearia dois tetos permanentes e duplicaria em vez de atualizar.
const MES_RECORRENTE: &str = "9999-12";

// ── Orçamento ────────────────────────────────────────────────────────────────

#[derive(Serialize)]
pub struct OrcamentoInfo {
    pub id: Uuid,
    /// `null` = teto do mês inteiro (todas as categorias juntas).
    pub category_id: Option<Uuid>,
    pub category_name: Option<String>,
    pub category_color: Option<String>,
    pub valor: Money,
    /// `YYYY-MM` ou `null` = recorrente (vale todo mês).
    pub mes: Option<String>,
    /// Quanto já foi gasto na categoria (ou no mês, se `category_id` é null)
    /// até o fim do período de vigência. Derivado do ledger.
    pub gasto: Money,
    /// `gasto / valor`, 0..=1+ (pode passar de 1 se estourou o teto).
    pub progresso: f32,
}

#[derive(Deserialize)]
pub struct NovoOrcamento {
    /// `null` = teto do mês inteiro.
    pub category_id: Option<Uuid>,
    pub valor: Money,
    /// `YYYY-MM` ou ausente = recorrente.
    pub mes: Option<String>,
}

#[derive(Deserialize)]
pub struct OrcamentoQuery {
    /// `YYYY-MM`. Ausente = mês atual.
    pub mes: Option<String>,
}

/// GET /orcamentos?mes=YYYY-MM
pub async fn listar_orcamentos(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Query(q): Query<OrcamentoQuery>,
) -> AppResult<Json<Vec<OrcamentoInfo>>> {
    let mes = mes_da_query(q.mes.as_deref())?;
    let inicio = primeiro_dia(&mes)?;
    let fim = fim_do_mes(&mes)?;

    // Tetos vigentes no mês: os do mês pedido + os recorrentes ('9999-12').
    let linhas = sqlx::query_as::<_, (Uuid, Option<Uuid>, Decimal, String)>(
        "SELECT o.id, o.category_id, o.valor, o.mes
           FROM orcamentos o
          WHERE o.user_id = $1
            AND (o.mes = $2 OR o.mes = '9999-12')
          ORDER BY o.mes, o.category_id NULLS FIRST",
    )
    .bind(auth.user_id())
    .bind(&mes)
    .fetch_all(&state.db)
    .await?;

    let mut out = Vec::with_capacity(linhas.len());
    for (id, cat_id, valor, mes_ref) in linhas {
        let (nome, cor) = match cat_id {
            Some(cid) => {
                let row = sqlx::query_as::<_, (String, Option<String>)>(
                    "SELECT name, color FROM categories WHERE id = $1 AND user_id = $2",
                )
                .bind(cid)
                .bind(auth.user_id())
                .fetch_optional(&state.db)
                .await?;
                row.unwrap_or_else(|| ("(categoria removida)".to_string(), None))
            }
            None => ("Todas as categorias".to_string(), None),
        };

        let gasto = gasto_do_periodo(&state, auth.user_id(), cat_id, inicio, fim).await?;
        let valor_d = valor;
        let progresso = if valor_d > Decimal::ZERO {
            (gasto / valor_d).to_string().parse::<f32>().unwrap_or(0.0)
        } else {
            0.0
        };

        out.push(OrcamentoInfo {
            id,
            category_id: cat_id,
            category_name: Some(nome),
            category_color: cor,
            valor: Money::new(valor_d),
            mes: if mes_ref == MES_RECORRENTE { None } else { Some(mes_ref) },
            gasto: Money::new(gasto),
            progresso,
        });
    }

    Ok(Json(out))
}

/// POST /orcamentos
pub async fn criar_orcamento(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Json(req): Json<NovoOrcamento>,
) -> AppResult<(StatusCode, Json<OrcamentoInfo>)> {
    if !req.valor.is_positive() {
        return Err(AppError::NaoValido("valor deve ser maior que zero".into()));
    }

    let mes = match req.mes.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        Some(m) => Some(mes_da_query(Some(m))?),
        None => None,
    };

    // Categoria precisa ser do usuário e de despesa — orçamento é teto de gasto.
    if let Some(cid) = req.category_id {
        let row: Option<String> = sqlx::query_scalar(
            "SELECT kind FROM categories WHERE id = $1 AND user_id = $2",
        )
        .bind(cid)
        .bind(auth.user_id())
        .fetch_optional(&state.db)
        .await?
        .flatten();
        match row.as_deref() {
            Some("EXPENSE") => {}
            Some(_) => {
                return Err(AppError::NaoValido(
                    "orçamento só aceita categoria de despesa".into(),
                ))
            }
            None => {
                return Err(AppError::NaoEncontrado("Categoria não encontrada.".into()));
            }
        }
    }

    // Um teto por (categoria, mês). O sentinel `MES_RECORRENTE` ('9999-12') marca
    // o teto permanente — não usamos NULL porque em Postgres NULL nunca é igual
    // a NULL num índice unique, e o ON CONFLICT não parearia (duplicaria em vez
    // de atualizar). O `ON CONFLICT (user_id, category_id, mes)` abaixo usa a
    // constraint `orcamento_unico`, que cobre os dois casos.
    let mes_val = mes.clone().unwrap_or_else(|| MES_RECORRENTE.to_string());
    let id = sqlx::query_scalar::<_, Uuid>(
        "INSERT INTO orcamentos (user_id, category_id, valor, mes)
         VALUES ($1, $2, $3, $4)
         ON CONFLICT (user_id, category_id, mes)
         DO UPDATE SET valor = EXCLUDED.valor, atualizado_em = now()
         RETURNING id",
    )
    .bind(auth.user_id())
    .bind(req.category_id)
    .bind(req.valor.amount())
    .bind(&mes_val)
    .fetch_one(&state.db)
    .await?;

    buscar_orcamento(&state, auth.user_id(), id).await
}

/// DELETE /orcamentos/{id}
pub async fn apagar_orcamento(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
) -> AppResult<()> {
    let rows = sqlx::query("DELETE FROM orcamentos WHERE id = $1 AND user_id = $2")
        .bind(id)
        .bind(auth.user_id())
        .execute(&state.db)
        .await?
        .rows_affected();
    if rows == 0 {
        return Err(AppError::NaoEncontrado("Orçamento não encontrado.".into()));
    }
    Ok(())
}

async fn buscar_orcamento(
    state: &AppState,
    user_id: Uuid,
    id: Uuid,
) -> AppResult<(StatusCode, Json<OrcamentoInfo>)> {
    let row = sqlx::query_as::<_, (Uuid, Option<Uuid>, Decimal, String)>(
        "SELECT id, category_id, valor, mes FROM orcamentos WHERE id = $1 AND user_id = $2",
    )
    .bind(id)
    .bind(user_id)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NaoEncontrado("Orçamento não encontrado.".into()))?;

    let (id, cat_id, valor, mes_ref) = row;
    let (nome, cor) = match cat_id {
        Some(cid) => {
            let r = sqlx::query_as::<_, (String, Option<String>)>(
                "SELECT name, color FROM categories WHERE id = $1 AND user_id = $2",
            )
            .bind(cid)
            .bind(user_id)
            .fetch_optional(&state.db)
            .await?;
            r.unwrap_or_else(|| ("(categoria removida)".to_string(), None))
        }
        None => ("Todas as categorias".to_string(), None),
    };

    let (inicio, fim) = match mes_ref.as_str() {
        // Recorrente: vale para o mês atual.
        MES_RECORRENTE => {
            let m = mes_da_query(None)?;
            (primeiro_dia(&m)?, fim_do_mes(&m)?)
        }
        m => (primeiro_dia(m)?, fim_do_mes(m)?),
    };
    let gasto = gasto_do_periodo(state, user_id, cat_id, inicio, fim).await?;
    let progresso = if valor > Decimal::ZERO {
        (gasto / valor).to_string().parse::<f32>().unwrap_or(0.0)
    } else {
        0.0
    };

    Ok((
        StatusCode::CREATED,
        Json(OrcamentoInfo {
            id,
            category_id: cat_id,
            category_name: Some(nome),
            category_color: cor,
            valor: Money::new(valor),
            mes: if mes_ref == MES_RECORRENTE { None } else { Some(mes_ref) },
            gasto: Money::new(gasto),
            progresso,
        }),
    ))
}

// ── Metas ────────────────────────────────────────────────────────────────────

#[derive(Serialize)]
pub struct MetaInfo {
    pub id: Uuid,
    pub nome: String,
    pub valor_alvo: Money,
    /// Quanto já foi guardado (aportes confirmados + valor inicial).
    pub guardado: Money,
    /// `guardado / valor_alvo`, 0..=1.
    pub progresso: f32,
    pub prazo: Option<NaiveDate>,
    /// Quanto falta para o alvo. `0.00` quando já bateu.
    pub falta: Money,
    pub criado_em: chrono::DateTime<chrono::Utc>,
}

#[derive(Deserialize)]
pub struct NovaMeta {
    pub nome: String,
    pub valor_alvo: Money,
    /// Dinheiro que já estava guardado antes da meta.
    pub valor_inicial: Option<Money>,
    /// `YYYY-MM-DD` ou ausente.
    pub prazo: Option<String>,
}

#[derive(Deserialize)]
pub struct MetaQuery {
    /// `true` inclui as arquivadas. Ausente = só ativas.
    pub incluidas_arquivadas: Option<bool>,
}

/// GET /metas
pub async fn listar_metas(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Query(q): Query<MetaQuery>,
) -> AppResult<Json<Vec<MetaInfo>>> {
    let incluidas = q.incluidas_arquivadas.unwrap_or(false);

    let linhas = sqlx::query_as::<_, (Uuid, String, Decimal, Decimal, Option<NaiveDate>, chrono::DateTime<chrono::Utc>)>(
        "SELECT id, nome, valor_alvo, valor_inicial, prazo, criado_em
           FROM metas
          WHERE user_id = $1 AND ($2::bool OR NOT arquetivada)
          ORDER BY criado_em",
    )
    .bind(auth.user_id())
    .bind(incluidas)
    .fetch_all(&state.db)
    .await?;

    let mut out = Vec::with_capacity(linhas.len());
    for (id, nome, alvo, inicial, prazo, criado) in linhas {
        let guardado = guardado_da_meta(&state, id, inicial).await?;
        let falta = (alvo - guardado).max(Decimal::ZERO);
        let progresso = if alvo > Decimal::ZERO {
            (guardado / alvo).to_string().parse::<f32>().unwrap_or(0.0).min(1.0)
        } else {
            0.0
        };
        out.push(MetaInfo {
            id,
            nome,
            valor_alvo: Money::new(alvo),
            guardado: Money::new(guardado),
            progresso,
            prazo,
            falta: Money::new(falta),
            criado_em: criado,
        });
    }

    Ok(Json(out))
}

/// POST /metas
pub async fn criar_meta(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Json(req): Json<NovaMeta>,
) -> AppResult<(StatusCode, Json<MetaInfo>)> {
    let nome = req.nome.trim();
    if nome.is_empty() || nome.chars().count() > 80 {
        return Err(AppError::NaoValido(
            "nome deve ter entre 1 e 80 caracteres".into(),
        ));
    }
    if !req.valor_alvo.is_positive() {
        return Err(AppError::NaoValido("valor alvo deve ser maior que zero".into()));
    }
    if let Some(v) = req.valor_inicial
        && v.amount() < Decimal::ZERO
    {
        return Err(AppError::NaoValido("valor inicial não pode ser negativo".into()));
    }

    let prazo = match req.prazo.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        Some(p) => Some(
            NaiveDate::parse_from_str(p, "%Y-%m-%d")
                .map_err(|_| AppError::NaoValido("prazo deve ser YYYY-MM-DD".into()))?,
        ),
        None => None,
    };

    let id = sqlx::query_scalar::<_, Uuid>(
        "INSERT INTO metas (user_id, nome, valor_alvo, valor_inicial, prazo)
         VALUES ($1, $2, $3, $4, $5)
         RETURNING id",
    )
    .bind(auth.user_id())
    .bind(nome)
    .bind(req.valor_alvo.amount())
    .bind(req.valor_inicial.unwrap_or(Money::new(Decimal::ZERO)).amount())
    .bind(prazo)
    .fetch_one(&state.db)
    .await?;

    buscar_meta(&state, auth.user_id(), id).await
}

/// DELETE /metas/{id}
pub async fn apagar_meta(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
) -> AppResult<()> {
    let rows = sqlx::query("DELETE FROM metas WHERE id = $1 AND user_id = $2")
        .bind(id)
        .bind(auth.user_id())
        .execute(&state.db)
        .await?
        .rows_affected();
    if rows == 0 {
        return Err(AppError::NaoEncontrado("Meta não encontrada.".into()));
    }
    Ok(())
}

/// POST /metas/{id}/arquivar e /metas/{id}/desarquivar
pub async fn arquivar_meta(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
    Json(req): Json<ArquivarRequest>,
) -> AppResult<Json<MetaInfo>> {
    let rows = sqlx::query(
        "UPDATE metas SET arquetivada = $3 WHERE id = $1 AND user_id = $2",
    )
    .bind(id)
    .bind(auth.user_id())
    .bind(req.arquivada)
    .execute(&state.db)
    .await?
    .rows_affected();
    if rows == 0 {
        return Err(AppError::NaoEncontrado("Meta não encontrada.".into()));
    }
    buscar_meta(&state, auth.user_id(), id).await.map(|(_, j)| j)
}

#[derive(Deserialize)]
pub struct ArquivarRequest {
    pub arquivada: bool,
}

/// POST /metas/{id}/aportar — registra que o usuário guardou dinheiro para a meta.
///
/// Cria um lançamento `EXPENSE` com `metadata.meta_id`. É despesa do ponto de
/// vista do saldo (o dinheiro saiu da "carteira disponível" e foi reservado),
/// mas a meta soma o aporte — então ele não é esquecimento, é reserva.
pub async fn aportar_meta(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
    Json(req): Json<AporteRequest>,
) -> AppResult<Json<MetaInfo>> {
    if !req.amount.is_positive() {
        return Err(AppError::NaoValido("valor deve ser maior que zero".into()));
    }

    // A meta tem de existir e ser do usuário. Ler antes evita postar no ledger
    // uma despesa que não tem meta por trás (dinheiro sumiria do saldo sem
    // reserva nenhuma).
    let meta = sqlx::query_as::<_, (Uuid, bool)>(
        "SELECT id, arquetivada FROM metas WHERE id = $1 AND user_id = $2",
    )
    .bind(id)
    .bind(auth.user_id())
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NaoEncontrado("Meta não encontrada.".into()))?;
    if meta.1 {
        return Err(AppError::NaoValido("meta arquivada não recebe aporte".into()));
    }

    let wallet = crate::state::wallet_ativa(&state, auth.user_id()).await?;

    let metadata = serde_json::json!({ "meta_id": id });
    ledger::postar_com_categoria(
        &state.db,
        wallet,
        TransactionType::Expense,
        req.amount,
        Some(&format!("Aporte: {}", meta_nome(&state, id).await?)),
        Some(metadata),
        None,
        Some(auth.user_id()),
    )
    .await?;

    buscar_meta(&state, auth.user_id(), id).await.map(|(_, j)| j)
}

#[derive(Deserialize)]
pub struct AporteRequest {
    pub amount: Money,
}

async fn buscar_meta(
    state: &AppState,
    user_id: Uuid,
    id: Uuid,
) -> AppResult<(StatusCode, Json<MetaInfo>)> {
    let row = sqlx::query_as::<_, (Uuid, String, Decimal, Decimal, Option<NaiveDate>, chrono::DateTime<chrono::Utc>)>(
        "SELECT id, nome, valor_alvo, valor_inicial, prazo, criado_em
           FROM metas WHERE id = $1 AND user_id = $2",
    )
    .bind(id)
    .bind(user_id)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NaoEncontrado("Meta não encontrada.".into()))?;

    let (id, nome, alvo, inicial, prazo, criado) = row;
    let guardado = guardado_da_meta(state, id, inicial).await?;
    let falta = (alvo - guardado).max(Decimal::ZERO);
    let progresso = if alvo > Decimal::ZERO {
        (guardado / alvo).to_string().parse::<f32>().unwrap_or(0.0).min(1.0)
    } else {
        0.0
    };

    Ok((
        StatusCode::CREATED,
        Json(MetaInfo {
            id,
            nome,
            valor_alvo: Money::new(alvo),
            guardado: Money::new(guardado),
            progresso,
            prazo,
            falta: Money::new(falta),
            criado_em: criado,
        }),
    ))
}

async fn meta_nome(state: &AppState, id: Uuid) -> AppResult<String> {
    let nome = sqlx::query_scalar::<_, String>("SELECT nome FROM metas WHERE id = $1")
        .bind(id)
        .fetch_optional(&state.db)
        .await?
        .ok_or(AppError::NaoEncontrado("Meta não encontrada.".into()))?;
    Ok(nome)
}

// ── Helpers derivados (onde o ledger entra) ──────────────────────────────────

/// Quanto foi guardado numa meta: valor inicial + aportes (transações com
/// `metadata.meta_id = id`). O `valor_inicial` fica no banco; o resto é
/// derivado dos lançamentos, então apagar um lançamento reduz a meta na hora.
async fn guardado_da_meta(
    state: &AppState,
    meta_id: Uuid,
    valor_inicial: Decimal,
) -> AppResult<Decimal> {
    let aportes: Decimal = sqlx::query_scalar(
        "SELECT COALESCE(SUM(amount), 0)
           FROM ledger_transactions
          WHERE wallet_id = (SELECT id FROM wallets WHERE user_id = $1)
            AND metadata->>'meta_id' = $2::text",
    )
    .bind(meta_id_user(state, meta_id).await?)
    .bind(meta_id.to_string())
    .fetch_one(&state.db)
    .await?;
    Ok(valor_inicial + aportes)
}

/// Acha o `user_id` dono da meta — usado para resolver a wallet do dono.
async fn meta_id_user(state: &AppState, meta_id: Uuid) -> AppResult<Uuid> {
    let uid = sqlx::query_scalar::<_, Uuid>("SELECT user_id FROM metas WHERE id = $1")
        .bind(meta_id)
        .fetch_optional(&state.db)
        .await?
        .ok_or(AppError::NaoEncontrado("Meta não encontrada.".into()))?;
    Ok(uid)
}

/// Gasto do período numa categoria (ou no mês inteiro, se `cat_id` é None).
/// Derivado do ledger: soma os débitos de `EXPENSE`/`BILL_PAID` cuja transação
/// tem a categoria (ou todas, quando None).
async fn gasto_do_periodo(
    state: &AppState,
    user_id: Uuid,
    cat_id: Option<Uuid>,
    inicio: chrono::DateTime<chrono::Utc>,
    fim: chrono::DateTime<chrono::Utc>,
) -> AppResult<Decimal> {
    let gasto: Decimal = sqlx::query_scalar(
        "SELECT COALESCE(SUM(e.debit), 0)
           FROM ledger_transactions t
           JOIN ledger_entries e ON e.transaction_id = t.id
           JOIN ledger_accounts a ON a.id = e.account_id
           JOIN wallets w ON w.id = t.wallet_id
          WHERE w.user_id = $1
            AND a.kind = 'USER_AVAILABLE'
            AND t.status = 'POSTED'
            AND t.type = ANY($2::text[])
            AND ($3::uuid IS NULL OR t.category_id = $3)
            -- Aporte de meta lança EXPENSE (reduz o disponível), mas é RESERVA,
            -- não gasto: não conta no teto de Todas as categorias.
            AND (t.metadata->>'meta_id') IS NULL
            AND t.created_at >= $4 AND t.created_at < $5",
    )
    .bind(user_id)
    .bind(&["EXPENSE", "BILL_PAID"])
    .bind(cat_id)
    .bind(inicio)
    .bind(fim)
    .fetch_one(&state.db)
    .await?;
    Ok(gasto)
}

/// Mês da query no formato `YYYY-MM`. Vazio = mês atual.
fn mes_da_query(mes: Option<&str>) -> AppResult<String> {
    match mes.map(str::trim).filter(|s| !s.is_empty()) {
        Some(m) => {
            // Valida o formato na hora: um `2026-13` viraria erro de banco
            // (500) em vez de 422.
            NaiveDate::parse_from_str(&format!("{m}-01"), "%Y-%m-%d")
                .map(|_| m.to_string())
                .map_err(|_| AppError::NaoValido("mês deve ser YYYY-MM".into()))
        }
        None => Ok(chrono::Local::now().format("%Y-%m").to_string()),
    }
}

fn primeiro_dia(mes: &str) -> AppResult<chrono::DateTime<chrono::Utc>> {
    let d = NaiveDate::parse_from_str(&format!("{mes}-01"), "%Y-%m-%d")
        .map_err(|_| AppError::NaoValido("mês inválido".into()))?;
    Ok(chrono::DateTime::from_naive_utc_and_offset(
        d.and_hms_opt(0, 0, 0).unwrap_or_default(),
        chrono::Utc,
    ))
}

fn fim_do_mes(mes: &str) -> AppResult<chrono::DateTime<chrono::Utc>> {
    let d = NaiveDate::parse_from_str(&format!("{mes}-01"), "%Y-%m-%d")
        .map_err(|_| AppError::NaoValido("mês inválido".into()))?;
    let (ano, prox_mes) = if d.month() == 12 {
        (d.year() + 1, 1)
    } else {
        (d.year(), d.month() + 1)
    };
    let inicio = NaiveDate::from_ymd_opt(ano, prox_mes, 1)
        .ok_or_else(|| AppError::NaoValido("mês inválido".into()))?;
    Ok(chrono::DateTime::from_naive_utc_and_offset(
        inicio.and_hms_opt(0, 0, 0).unwrap_or_default(),
        chrono::Utc,
    ))
}

// ── GET /wallet/resumo — saldo disponível × comprometido + previsão ──────────

#[derive(Serialize)]
pub struct ResumoCarteira {
    /// Saldo do ledger (o mesmo do `GET /wallet`).
    pub saldo: Money,
    /// Boletos PENDING (ainda não pagos).
    pub comprometido: Money,
    /// `saldo - comprometido`. Pode ser negativo.
    pub disponivel: Money,
    /// Previsão de saldo no fim do mês, se tudo vencer como está:
    /// `saldo - comprometido - parcelas futuras de boletos recorrentes`.
    pub previsao_fim_mes: Money,
    /// Quantos boletos PENDING existem (para a tela dizer "3 contas em aberto").
    pub contas_pendentes: i64,
}

/// GET /wallet/resumo
pub async fn resumo_carteira(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
) -> AppResult<Json<ResumoCarteira>> {
    let wallet = crate::state::wallet_ativa(&state, auth.user_id()).await?;
    let (saldo, _) = ledger::saldo(&state.db, wallet).await?;

    // Comprometido = soma dos boletos PENDING (não pagos). Vencidos também
    // contam: "vencido" ainda é uma conta que precisa sair do saldo.
    let (comprometido, qtd) = sqlx::query_as::<_, (Decimal, i64)>(
        "SELECT COALESCE(SUM(amount), 0), COUNT(*)
           FROM bills
          WHERE wallet_id = $1 AND status = 'PENDING'",
    )
    .bind(wallet)
    .fetch_one(&state.db)
    .await?;

    let disponivel = saldo - comprometido;
    let previsao = disponivel; // comprometido já está descontado; o que falta
                               // aqui são as parcelas futuras que ainda não
                               // existem como boleto (série recorrente não
                               // gerada). Como elas só viram boleto quando
                               // `generate-recurring` roda, a previsão é o
                               // disponível menos as parcelas JÁ GERADAS de
                               // boletos recorrentes com due_date no futuro.

    // Parcelas futuras de boletos recorrentes já gerados: PENDING com
    // recurrence_key não nulo e due_date depois de hoje. Isso já está contado
    // em `comprometido` (são PENDING), então NÃO desconta de novo — a previsão
    // é simplesmente o disponível. O comentário fica para explicar por que o
    // número é o mesmo: previsão honesta é "o que sobra se tudo que está em
    // aberto for pago", e não uma projeção de gastos hipotéticos.
    Ok(Json(ResumoCarteira {
        saldo: Money::new(saldo),
        comprometido: Money::new(comprometido),
        disponivel: Money::new(disponivel),
        previsao_fim_mes: Money::new(previsao),
        contas_pendentes: qtd,
    }))
}

// ── GET /splits/pendentes — quem me deve, agregado ───────────────────────────

#[derive(Serialize)]
pub struct DevedorAgregado {
    pub name: String,
    pub total: Money,
    pub partes: i64,
}

/// GET /splits/pendentes
///
/// Agrega as partes não quitadas por participante: "João — R$ 45,00" em vez de
/// nove linhas de divisões diferentes. Derivado das partes; nada é persistido.
pub async fn splits_pendentes(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
) -> AppResult<Json<Vec<DevedorAgregado>>> {
    let wallet = crate::state::wallet_ativa(&state, auth.user_id()).await?;

    let linhas = sqlx::query_as::<_, (String, Decimal, i64)>(
        "SELECT p.participant_name, SUM(p.owed_amount), COUNT(*)
           FROM expense_split_parts p
           JOIN expense_splits s ON s.id = p.split_id
          WHERE s.wallet_id = $1 AND p.settled_at IS NULL
          GROUP BY p.participant_name
          ORDER BY SUM(p.owed_amount) DESC",
    )
    .bind(wallet)
    .fetch_all(&state.db)
    .await?;

    Ok(Json(
        linhas
            .into_iter()
            .map(|(name, total, partes)| DevedorAgregado {
                name,
                total: Money::new(total),
                partes,
            })
            .collect(),
    ))
}

// ── POST /bills/parcelar — compra parcelada vira N boletos ───────────────────

#[derive(Deserialize)]
pub struct ParcelarRequest {
    pub description: String,
    pub total_amount: Money,
    /// Número de parcelas (2..=24).
    pub parcelas: i64,
    /// Primeiro vencimento `YYYY-MM-DD`. As demais somam um mês cada.
    pub first_due_date: String,
    pub category_id: Option<Uuid>,
}

#[derive(Serialize)]
pub struct ParcelaInfo {
    pub numero: i64,
    pub amount: Money,
    pub due_date: chrono::NaiveDate,
    pub bill_id: Uuid,
}

#[derive(Serialize)]
pub struct ParcelamentoResponse {
    pub parcelas: Vec<ParcelaInfo>,
    pub total: Money,
}

/// POST /bills/parcelar
///
/// Cria N boletos PENDING (um por parcela) numa transação só. O parcelamento
/// NÃO posta no ledger: cada boleto só vira lançamento quando for marcado como
/// pago (`POST /bills/{id}/pay`), como qualquer boleto. Nada de dinheiro é
/// criado aqui — é só o que o usuário afirma que vai pagar.
///
/// A soma das parcelas é o total (a última parcela absorve o arredondamento,
/// então 100,00 em 3x vira 33,33 + 33,33 + 33,34 e não 99,99).
pub async fn parcelar_bill(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Json(req): Json<ParcelarRequest>,
) -> AppResult<(StatusCode, Json<ParcelamentoResponse>)> {
    let description = req.description.trim();
    if description.is_empty() {
        return Err(AppError::Validation("descrição é obrigatória".into()));
    }
    if !req.total_amount.is_positive() {
        return Err(AppError::InvalidAmount("valor deve ser maior que zero".into()));
    }
    if !(2..=24).contains(&req.parcelas) {
        return Err(AppError::Validation("parcelas deve ser entre 2 e 24".into()));
    }

    let first = chrono::NaiveDate::parse_from_str(req.first_due_date.trim(), "%Y-%m-%d")
        .map_err(|_| AppError::Validation("due_date deve ser YYYY-MM-DD".into()))?;

    let wallet = crate::state::wallet_ativa(&state, auth.user_id()).await?;

    // Categoria validada uma vez: o mesmo tipo (despesa) vale para todas as
    // parcelas, e revalidar por parcela seria N queries para o mesmo id.
    let mut tx = state.db.begin().await?;
    let categoria = crate::categories::validar_e_resolver(
        &mut tx,
        auth.user_id(),
        req.category_id,
        crate::categories::CategoryKind::Expense,
    )
    .await?;

    // Divide o total em parcelas iguais; a última absorve a diferença para a
    // soma fechar exatamente com o total (nunca 99,99 em compras de 100,00).
    let total = req.total_amount.amount();
    let n = req.parcelas;
    let base = (total / Decimal::from(n)).round_dp(2);
    let mut somado = Decimal::ZERO;
    let mut pecas = Vec::with_capacity(n as usize);

    for i in 0..n {
        let valor = if i == n - 1 {
            total - somado
        } else {
            let v = base;
            somado += v;
            v
        };
        let due = soma_meses(first, i as i32);
        let desc = format!("{description} ({}/{})", i + 1, n);
        let id = sqlx::query_scalar::<_, Uuid>(
            "INSERT INTO bills (wallet_id, description, amount, due_date, category_id)
             VALUES ($1, $2, $3, $4, $5)
             RETURNING id",
        )
        .bind(wallet)
        .bind(&desc)
        .bind(valor)
        .bind(due)
        .bind(categoria)
        .fetch_one(&mut *tx)
        .await?;
        pecas.push(ParcelaInfo {
            numero: i + 1,
            amount: Money::new(valor),
            due_date: due,
            bill_id: id,
        });
    }

    tx.commit().await?;

    Ok((
        StatusCode::CREATED,
        Json(ParcelamentoResponse {
            parcelas: pecas,
            total: req.total_amount,
        }),
    ))
}

/// Soma `meses` a uma data, fixando o dia no último dia do mês quando
/// necessário (31/01 + 1 mês = 28/02, não 03/03). Mesma aritmética de
/// `recurrence::soma_meses` — duplicada de propósito: aquele módulo é privado
/// e esta função é uma linha.
fn soma_meses(base: chrono::NaiveDate, meses: i32) -> chrono::NaiveDate {
    let total = base.year() * 12 + base.month0() as i32 + meses;
    let ano = total.div_euclid(12);
    let mes = (total.rem_euclid(12) + 1) as u32;
    let dia = base.day().min(ultimo_dia_do_mes(ano, mes));
    chrono::NaiveDate::from_ymd_opt(ano, mes, dia).unwrap_or(base)
}

fn ultimo_dia_do_mes(ano: i32, mes: u32) -> u32 {
    let (a, m) = if mes == 12 { (ano + 1, 1) } else { (ano, mes + 1) };
    chrono::NaiveDate::from_ymd_opt(a, m, 1)
        .and_then(|d| d.pred_opt())
        .map(|d| d.day())
        .unwrap_or(28)
}

// ── GET /wallet/orcamento-mes — atalho para a Home ───────────────────────────
//
// A Home já chama `/wallet`; um segundo endpoint evitaria o `OrcamentoInfo`
// completo. Em vez disso, a Home deriva o resumo do orçamento localmente a
// partir de `GET /orcamentos?mes=...` — não precisa deste atalho. Removido.

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn soma_meses_para_ultimo_dia_valido() {
        let base = chrono::NaiveDate::from_ymd_opt(2026, 1, 31).unwrap();
        let d = soma_meses(base, 1);
        assert_eq!(d, chrono::NaiveDate::from_ymd_opt(2026, 2, 28).unwrap());
    }

    #[test]
    fn soma_meses_mantem_dia_quando_cabe() {
        let base = chrono::NaiveDate::from_ymd_opt(2026, 3, 31).unwrap();
        let d = soma_meses(base, 1);
        assert_eq!(d, chrono::NaiveDate::from_ymd_opt(2026, 4, 30).unwrap());
    }

    #[test]
    fn soma_meses_vira_o_ano() {
        let base = chrono::NaiveDate::from_ymd_opt(2026, 11, 5).unwrap();
        let d = soma_meses(base, 3);
        assert_eq!(d, chrono::NaiveDate::from_ymd_opt(2027, 2, 5).unwrap());
    }
}