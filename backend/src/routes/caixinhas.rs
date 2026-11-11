//! Caixinha: empréstimo registrado entre conhecidos. O responsável (tomador)
//! pede um valor, cada membro (credor) aporta, e o responsável paga rendimento
//! mensal sobre o aportado. Regra central: **quem paga propõe, quem recebe
//! confirma** — só movimento `CONFIRMADO` entra em qualquer número.
//!
//! **FORA do ledger** (decisão fechada em `docs/caixinha-api.md`): a caixinha
//! registra o que cada pessoa AFIRMA. Nenhum handler aqui chama
//! `ledger::postar*` — o saldo da carteira não muda em nenhuma operação, e o
//! e2e prova conferindo `GET /wallet` do dono e do membro do começo ao fim.
//!
//! **Sem vitrine pública.** Entrada só por convite entre conhecidos: captar
//! de credores desconhecidos com rendimento fixo é oferta pública de Contrato
//! de Investimento Coletivo (valor mobiliário, Lei 6.385/76) e exigiria CVM.
//! A coluna `publica` fica sempre `false` (constraint no banco); o endpoint
//! da vitrine não existe.
//!
//! Fronteira de confiança:
//!  - Identidade SEMPRE do token (`AuthUser`); `user_id` nunca do body.
//!  - Quem não é dono nem membro: a caixinha não existe para ele → 404
//!    `Caixinha não encontrada.` (nunca 403 — mesmo padrão das outras rotas).
//!  - Membro vê só os próprios números e movimentos; o dono vê todos. O
//!    convite ativo é visível só ao dono, e a API nunca devolve código já
//!    usado.
//!  - Rendimento NUNCA recebe valor do cliente: o servidor calcula a base ×
//!    taxa. Aporte e devolução vêm do cliente (são afirmações), validados.
//!
//! Todo número é derivado dos movimentos pelas funções puras de
//! `crate::caixinha_modelo` — nada de saldo guardado.

use std::collections::{HashMap, HashSet};

use axum::Json;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use chrono::{DateTime, Datelike, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use sqlx::{PgExecutor, PgPool, Postgres, Transaction};
use uuid::Uuid;

use crate::caixinha_modelo::{self as modelo, Mes, Taxa};
use crate::caixinha_score;
use crate::error::{AppError, AppResult};
use crate::money::Money;
use crate::state::AppState;

/// `?mes=YYYY-MM` — todo endpoint que devolve detalhe aceita; default é o
/// mês atual UTC. O app manda o mês local dele.
#[derive(Deserialize)]
pub struct MesQuery {
    pub mes: Option<String>,
}

/// Quantas caixinhas um usuário pode criar. Sem teto, uma conta pode poluir a
/// tabela e a lista de convites; 20 cobre com folga o uso de pessoa física.
const LIMITE_CAIXINHAS: i64 = 20;

/// Teto do pedido em `NUMERIC(19,4)` (4 casas): acima disso o banco estouraria
/// como 500, então recusamos como 422 com mensagem de contrato.
fn valor_pedido_maximo() -> Decimal {
    Decimal::new(999_999_999_999_999, 4)
}

fn mes_da_query(q: &MesQuery) -> AppResult<Mes> {
    match q.mes.as_deref() {
        None => Ok(Mes::atual_utc()),
        Some(texto) => Mes::parse(texto).ok_or_else(|| {
            AppError::NaoValido(format!("mês inválido: '{texto}'. Use o formato YYYY-MM."))
        }),
    }
}

// ── requests ──────────────────────────────────────────────────────────────────

#[derive(Deserialize)]
pub struct NovaCaixinha {
    pub nome: String,
    pub valor_pedido: Money,
    pub taxa_mensal: Taxa,
    pub previsao_quitacao: Option<String>,
    pub telefone: Option<String>,
}

#[derive(Deserialize)]
pub struct EntrarRequest {
    pub chave: String,
}

#[derive(Deserialize)]
pub struct AporteRequest {
    pub amount: Money,
}

#[derive(Deserialize)]
pub struct RendimentoRequest {
    pub membro_id: Uuid,
    pub competencia: String,
}

#[derive(Deserialize)]
pub struct DevolucaoRequest {
    pub membro_id: Uuid,
    pub amount: Money,
}

#[derive(Deserialize)]
pub struct TelefoneRequest {
    pub telefone: String,
}

// ── responses ─────────────────────────────────────────────────────────────────

#[derive(Serialize)]
pub struct DonoInfo {
    pub user_id: Uuid,
    pub nome: String,
    pub telefone: Option<String>,
}

#[derive(Serialize)]
pub struct MembroInfo {
    pub user_id: Uuid,
    pub nome: String,
    pub telefone: Option<String>,
    pub aportado: Money,
    pub saldo_em_aberto: Money,
    pub devido_mes: Money,
    pub pago_mes: Money,
    pub falta_mes: Money,
    pub rendimento_disponivel: Money,
    pub rendimento_pendente: bool,
}

#[derive(Serialize)]
pub struct MovimentoInfo {
    pub id: Uuid,
    pub tipo: String,
    pub membro_id: Uuid,
    pub membro_nome: String,
    pub valor: Money,
    /// `"2026-10"` ou `null` (só rendimento tem competência).
    pub competencia: Option<String>,
    pub status: String,
    pub proposto_por: Uuid,
    pub criado_em: DateTime<Utc>,
    pub confirmado_em: Option<DateTime<Utc>>,
}

#[derive(Serialize)]
pub struct CaixinhaDetalhe {
    pub id: Uuid,
    pub nome: String,
    /// "dono" | "membro"
    pub papel: String,
    pub dono: DonoInfo,
    pub valor_pedido: Money,
    pub taxa_mensal: Taxa,
    pub previsao_quitacao: Option<String>,
    pub arrecadado: Money,
    /// Soma dos aportes PROPOSTOS (de todos os membros) — só o dono vê; o
    /// membro recebe `null` (número de terceiros não é número dele).
    pub arrecadado_pendente: Option<Money>,
    pub disponivel_aporte: Money,
    pub progresso: f32,
    /// Reputação do responsável (0..=1000), derivada dos eventos e atrasos.
    pub responsavel_score: i32,
    /// Quantos membros distintos já aportaram — a "testemunha" do score.
    pub responsavel_evidencias: i32,
    /// Convite ATIVO — só o dono vê; códigos usados nunca voltam.
    pub convite: Option<String>,
    pub membros: Vec<MembroInfo>,
    /// Totais do `?mes=` — só fazem sentido pro dono (membro recebe `null`).
    pub total_devido_mes: Option<Money>,
    pub total_pago_mes: Option<Money>,
    pub total_falta_mes: Option<Money>,
    pub movimentos: Vec<MovimentoInfo>,
}

#[derive(Serialize)]
pub struct MinhaCaixinha {
    pub id: Uuid,
    pub nome: String,
    pub papel: String,
    pub valor_pedido: Money,
    pub taxa_mensal: Taxa,
    pub arrecadado: Money,
    pub progresso: f32,
    pub responsavel_score: i32,
    pub responsavel_evidencias: i32,
    pub criado_em: DateTime<Utc>,
}

// ── POST /caixinhas — criar ──────────────────────────────────────────────────

/// Cria a caixinha e o PRIMEIRO convite na mesma transação: o dono sai da
/// criação já com o código para compartilhar. Nenhuma transação de ledger —
/// criar um registro não move dinheiro.
pub async fn criar(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Query(q): Query<MesQuery>,
    Json(req): Json<NovaCaixinha>,
) -> AppResult<(StatusCode, Json<CaixinhaDetalhe>)> {
    // Validação no Rust ANTES de tocar o banco: o CHECK do banco recusaria o
    // mesmo, mas como 500 de check_violation, não como 422 com mensagem útil.
    let nome = req.nome.trim();
    if nome.is_empty() {
        return Err(AppError::NaoValido("nome é obrigatório".into()));
    }
    if nome.chars().count() > 80 {
        return Err(AppError::NaoValido(
            "nome deve ter no máximo 80 caracteres".into(),
        ));
    }
    if !req.valor_pedido.is_positive() {
        return Err(AppError::NaoValido("valor deve ser maior que zero".into()));
    }
    if req.valor_pedido.amount() > valor_pedido_maximo() {
        return Err(AppError::NaoValido("valor pedido acima do limite".into()));
    }
    // NUMERIC(5,2) no banco: acima de 999,99 a coluna não aceita. A mesma
    // mensagem da taxa cobre o caso — é a única mensagem de taxa no contrato.
    let taxa = req.taxa_mensal.valor();
    if taxa <= Decimal::ZERO || taxa > Decimal::new(99999, 2) {
        return Err(AppError::NaoValido(
            "taxa ao mês deve ser maior que zero".into(),
        ));
    }
    let telefone = req.telefone.as_deref().map(telefone_digits).transpose()?;
    let previsao = req
        .previsao_quitacao
        .as_deref()
        .map(str::trim)
        .filter(|p| !p.is_empty());
    if let Some(p) = previsao {
        if p.chars().count() > 60 {
            return Err(AppError::NaoValido(
                "previsão deve ter no máximo 60 caracteres".into(),
            ));
        }
    }
    let mes = mes_da_query(&q)?;

    let existentes = sqlx::query_scalar::<_, i64>(
        "SELECT count(*) FROM caixinhas WHERE dono_user_id = $1",
    )
    .bind(auth.user_id())
    .fetch_one(&state.db)
    .await?;
    if existentes >= LIMITE_CAIXINHAS {
        return Err(AppError::NaoValido("limite de caixinhas atingido".into()));
    }
    // A primeira é grátis; da segunda em diante exige Premium. Cobrar aqui é
    // freio de fraude também: inflar o score pede caixinhas com membros
    // distintos, e cada conta a mais custa assinatura.
    if existentes >= 1 {
        crate::routes::billing::exigir_premium_do_token(&auth)?;
    }

    let mut tx = state.db.begin().await?;
    let id = sqlx::query_scalar::<_, Uuid>(
        "INSERT INTO caixinhas
             (dono_user_id, nome, valor_pedido, taxa_mensal, previsao_quitacao, telefone_dono)
         VALUES ($1, $2, $3, $4, $5, $6)
         RETURNING id",
    )
    .bind(auth.user_id())
    .bind(nome)
    .bind(req.valor_pedido.amount())
    .bind(taxa)
    .bind(previsao)
    .bind(telefone)
    .fetch_one(&mut *tx)
    .await?;

    inserir_convite(&mut tx, id).await?;
    tx.commit().await?;

    let detalhe = carregar_detalhe(&state, id, auth.user_id(), mes).await?;
    Ok((StatusCode::CREATED, Json(detalhe)))
}

// ── GET /caixinhas — minhas (dono ou membro) ─────────────────────────────────

pub async fn minhas(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
) -> AppResult<Json<Vec<MinhaCaixinha>>> {
    let user = auth.user_id();

    let rows = sqlx::query_as::<_, MinhaRow>(
        "SELECT c.id, c.nome, c.dono_user_id, c.valor_pedido, c.taxa_mensal, c.criado_em,
                COALESCE(m.aportes, 0)
           FROM caixinhas c
           LEFT JOIN (SELECT caixinha_id, SUM(valor) AS aportes
                        FROM caixinha_movimentos
                       WHERE tipo = 'APORTE' AND status = 'CONFIRMADO'
                       GROUP BY caixinha_id) m ON m.caixinha_id = c.id
          WHERE c.dono_user_id = $1
             OR EXISTS (SELECT 1 FROM caixinha_membros cm
                         WHERE cm.caixinha_id = c.id AND cm.user_id = $1)
          ORDER BY c.criado_em DESC",
    )
    .bind(user)
    .fetch_all(&state.db)
    .await?;

    // O score é do DONO; numa lista com caixinhas de donos diferentes, cada
    // dono é calculado uma vez só.
    let mut scores: HashMap<Uuid, (i32, i32)> = HashMap::new();
    for dono in rows.iter().map(|r| r.2).collect::<HashSet<_>>() {
        scores.insert(dono, score_do_dono(&state.db, dono).await?);
    }

    let itens = rows
        .into_iter()
        .map(|r| {
            let (score, evidencias) = scores.get(&r.2).copied().unwrap_or((caixinha_score::BASE, 0));
            MinhaCaixinha {
                id: r.0,
                nome: r.1,
                papel: if r.2 == user { "dono" } else { "membro" }.to_string(),
                valor_pedido: Money::new(r.3),
                taxa_mensal: Taxa::new(r.4),
                arrecadado: Money::new(modelo::duas_casas(r.6)),
                progresso: modelo::progresso(modelo::duas_casas(r.6), r.3),
                responsavel_score: score,
                responsavel_evidencias: evidencias,
                criado_em: r.5,
            }
        })
        .collect();

    Ok(Json(itens))
}

// ── POST /caixinhas/entrar — entrar com chave ────────────────────────────────

/// Entrada por convite de uso único. Transação única: trava o convite com
/// `FOR UPDATE` → valida dono/membro (erro NÃO consome a chave) → marca
/// `usado_em`/`usado_por` → insere o membro → gera o PRÓXIMO código da
/// caixinha. Sem o `FOR UPDATE`, dois requests com a mesma chave entrariam
/// os dois: o segundo espera o COMMIT do primeiro, vê `usado_em` e recebe 409.
pub async fn entrar(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    ip_auth: crate::auth::ClientIp,
    Query(q): Query<MesQuery>,
    Json(req): Json<EntrarRequest>,
) -> AppResult<Json<CaixinhaDetalhe>> {
    let ip = ip_auth.0.unwrap_or(std::net::Ipv4Addr::UNSPECIFIED.into());
    let chave_gate = auth.user_id().to_string();
    // A chave é curta o bastante para valer a pena adivinhar: sem este freio,
    // uma conta autenticada testa convites alheios na velocidade da rede.
    if let Err(espera) = state.convite_gate.aceita(ip, &chave_gate) {
        return Err(AppError::TooManyRequests(format!(
            "muitas tentativas de convite; tente de novo em {}s",
            espera.as_secs().max(1)
        )));
    }
    let chave = req.chave.trim().to_uppercase();
    let mes = mes_da_query(&q)?;

    let mut tx = state.db.begin().await?;

    // `usado_em NULLS FIRST`: se a mesma chave existir como histórico E como
    // ativa (colisão defensiva), a ativa é a que vale.
    let convite = sqlx::query_as::<_, (Uuid, Uuid, Option<DateTime<Utc>>)>(
        "SELECT id, caixinha_id, usado_em
           FROM caixinha_convites
          WHERE chave = $1
          ORDER BY usado_em NULLS FIRST, criado_em DESC
          LIMIT 1
          FOR UPDATE",
    )
    .bind(&chave)
    .fetch_optional(&mut *tx)
    .await?;
    let Some((convite_id, caixinha_id, usado_em)) = convite else {
        state.convite_gate.registrar_falha(ip, &chave_gate);
        return Err(AppError::NaoEncontrado("Chave inválida.".into()));
    };

    if usado_em.is_some() {
        state.convite_gate.registrar_falha(ip, &chave_gate);
        return Err(AppError::Conflict("Essa chave já foi usada.".into()));
    }

    // Validação ANTES de consumir: erro aqui deixa a chave intacta para
    // quem tem direito a ela.
    let dono = sqlx::query_scalar::<_, Uuid>(
        "SELECT dono_user_id FROM caixinhas WHERE id = $1",
    )
    .bind(caixinha_id)
    .fetch_one(&mut *tx)
    .await?;

    if dono == auth.user_id() {
        state.convite_gate.registrar_sucesso(ip, &chave_gate);
        return Err(AppError::Conflict(
            "Você é o responsável desta caixinha.".into(),
        ));
    }
    let ja_membro = sqlx::query_scalar::<_, bool>(
        "SELECT EXISTS (SELECT 1 FROM caixinha_membros
                         WHERE caixinha_id = $1 AND user_id = $2)",
    )
    .bind(caixinha_id)
    .bind(auth.user_id())
    .fetch_one(&mut *tx)
    .await?;
    if ja_membro {
        state.convite_gate.registrar_sucesso(ip, &chave_gate);
        return Err(AppError::Conflict("Você já está nesta caixinha.".into()));
    }

    sqlx::query("UPDATE caixinha_convites SET usado_em = now(), usado_por = $2 WHERE id = $1")
        .bind(convite_id)
        .bind(auth.user_id())
        .execute(&mut *tx)
        .await?;
    sqlx::query("INSERT INTO caixinha_membros (caixinha_id, user_id) VALUES ($1, $2)")
        .bind(caixinha_id)
        .bind(auth.user_id())
        .execute(&mut *tx)
        .await?;

    // O próximo código nasce na MESMA transação que consome o atual: sempre
    // existe exatamente um convite ativo por caixinha.
    inserir_convite(&mut tx, caixinha_id).await?;
    tx.commit().await?;
    state.convite_gate.registrar_sucesso(ip, &chave_gate);

    Ok(Json(carregar_detalhe(&state, caixinha_id, auth.user_id(), mes).await?))
}

// ── GET /caixinhas/{id} — detalhe ────────────────────────────────────────────

pub async fn detalhe(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
    Query(q): Query<MesQuery>,
) -> AppResult<Json<CaixinhaDetalhe>> {
    let mes = mes_da_query(&q)?;
    Ok(Json(carregar_detalhe(&state, id, auth.user_id(), mes).await?))
}

// ── POST /caixinhas/{id}/aportes — membro propõe ──────────────────────────────

/// O membro afirma que aportou. Nasce `PROPOSTO`: só entra nos números quando
/// o dono confirmar. Valor vem do cliente (é afirmação), mas validado contra
/// o espaço que falta no pedido — propostas pendentes também reservam espaço.
pub async fn propor_aporte(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
    Query(q): Query<MesQuery>,
    Json(req): Json<AporteRequest>,
) -> AppResult<(StatusCode, Json<CaixinhaDetalhe>)> {
    let mes = mes_da_query(&q)?;
    if !req.amount.is_positive() {
        return Err(AppError::NaoValido("valor deve ser maior que zero".into()));
    }

    let mut tx = state.db.begin().await?;
    let (caixinha, eh_dono) = acesso(&mut tx, id, auth.user_id()).await?;
    if eh_dono {
        return Err(AppError::Conflict(
            "O responsável não aporta na própria caixinha.".into(),
        ));
    }

    let movs = movimentos_da_caixinha(&mut *tx, id).await?;
    let modelo_movs = movs.iter().map(para_modelo).collect::<Vec<_>>();
    let disponivel = modelo::disponivel_aporte(caixinha.3, &modelo_movs);

    if disponivel <= Decimal::ZERO {
        return Err(AppError::NaoValido("O valor pedido já foi alcançado.".into()));
    }
    if req.amount.amount() > disponivel {
        return Err(AppError::NaoValido(format!(
            "Faltam R$ {} para completar o pedido.",
            modelo::reais_ptbr(disponivel)
        )));
    }

    sqlx::query(
        "INSERT INTO caixinha_movimentos
             (caixinha_id, tipo, membro_user_id, valor, competencia, status, proposto_por)
         VALUES ($1, 'APORTE', $2, $3, NULL, 'PROPOSTO', $2)",
    )
    .bind(id)
    .bind(auth.user_id())
    .bind(req.amount.amount())
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;

    let detalhe = carregar_detalhe(&state, id, auth.user_id(), mes).await?;
    Ok((StatusCode::CREATED, Json(detalhe)))
}

// ── POST /caixinhas/{id}/rendimentos — dono propõe ───────────────────────────

/// O dono propõe o rendimento do membro no mês. **Sem valor no body**: o
/// servidor calcula `rendimento_disponivel` (falta do mês menos o já
/// proposto) e propõe esse valor inteiro — o cliente nunca decide quanto
/// juro deve.
pub async fn propor_rendimento(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
    Query(q): Query<MesQuery>,
    Json(req): Json<RendimentoRequest>,
) -> AppResult<(StatusCode, Json<CaixinhaDetalhe>)> {
    let mes = mes_da_query(&q)?;
    let competencia = Mes::parse(&req.competencia).ok_or_else(|| {
        AppError::NaoValido(format!(
            "mês inválido: '{}'. Use o formato YYYY-MM.",
            req.competencia
        ))
    })?;
    if !Mes::atual_utc().competencia_permitida(competencia) {
        return Err(AppError::NaoValido(
            "competência não pode ser maior que o próximo mês.".into(),
        ));
    }

    let mut tx = state.db.begin().await?;
    let (caixinha, eh_dono) = acesso(&mut tx, id, auth.user_id()).await?;
    if !eh_dono {
        return Err(AppError::Conflict(
            "Só o responsável pode propor o rendimento.".into(),
        ));
    }

    let movs = movimentos_da_caixinha(&mut *tx, id).await?;
    let modelo_movs = movs.iter().map(para_modelo).collect::<Vec<_>>();
    let disponivel =
        modelo::rendimento_disponivel(&modelo_movs, req.membro_id, competencia, caixinha.4);
    if disponivel <= Decimal::ZERO {
        return Err(AppError::NaoValido(
            "Não há rendimento em aberto neste mês.".into(),
        ));
    }

    sqlx::query(
        "INSERT INTO caixinha_movimentos
             (caixinha_id, tipo, membro_user_id, valor, competencia, status, proposto_por)
         VALUES ($1, 'RENDIMENTO', $2, $3, $4, 'PROPOSTO', $5)",
    )
    .bind(id)
    .bind(req.membro_id)
    .bind(disponivel)
    .bind(competencia.inicio_naive())
    .bind(auth.user_id())
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;

    let detalhe = carregar_detalhe(&state, id, auth.user_id(), mes).await?;
    Ok((StatusCode::CREATED, Json(detalhe)))
}

// ── POST /caixinhas/{id}/devolucoes — dono propõe ────────────────────────────

/// O dono propõe devolver parte (ou tudo) do que deve ao membro. Validado
/// contra o saldo em aberto MENOS devoluções já propostas — proposta também
/// reserva saldo.
pub async fn propor_devolucao(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
    Query(q): Query<MesQuery>,
    Json(req): Json<DevolucaoRequest>,
) -> AppResult<(StatusCode, Json<CaixinhaDetalhe>)> {
    let mes = mes_da_query(&q)?;
    if !req.amount.is_positive() {
        return Err(AppError::NaoValido("valor deve ser maior que zero".into()));
    }

    let mut tx = state.db.begin().await?;
    let (_, eh_dono) = acesso(&mut tx, id, auth.user_id()).await?;
    if !eh_dono {
        return Err(AppError::Conflict(
            "Só o responsável pode propor a devolução.".into(),
        ));
    }

    let movs = movimentos_da_caixinha(&mut *tx, id).await?;
    let modelo_movs = movs.iter().map(para_modelo).collect::<Vec<_>>();
    let disponivel = modelo::disponivel_devolucao(&modelo_movs, req.membro_id);

    if disponivel <= Decimal::ZERO {
        return Err(AppError::NaoValido("Não há saldo em aberto.".into()));
    }
    if req.amount.amount() > disponivel {
        return Err(AppError::NaoValido(format!(
            "O saldo em aberto é R$ {}.",
            modelo::reais_ptbr(disponivel)
        )));
    }

    sqlx::query(
        "INSERT INTO caixinha_movimentos
             (caixinha_id, tipo, membro_user_id, valor, competencia, status, proposto_por)
         VALUES ($1, 'DEVOLUCAO', $2, $3, NULL, 'PROPOSTO', $4)",
    )
    .bind(id)
    .bind(req.membro_id)
    .bind(req.amount.amount())
    .bind(auth.user_id())
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;

    let detalhe = carregar_detalhe(&state, id, auth.user_id(), mes).await?;
    Ok((StatusCode::CREATED, Json(detalhe)))
}

// ── confirmar / recusar / cancelar ───────────────────────────────────────────

/// Contraparte confirma: aporte é confirmado pelo dono; rendimento e
/// devolução, pelo membro. Revalida o estado (as mesmas regras da proposta)
/// porque entre propor e confirmar o mundo pode ter mudado — com as
/// mensagens exatas do contrato.
pub async fn confirmar_movimento(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path((id, mov_id)): Path<(Uuid, Uuid)>,
    Query(q): Query<MesQuery>,
) -> AppResult<Json<CaixinhaDetalhe>> {
    let mes = mes_da_query(&q)?;
    let mut tx = state.db.begin().await?;
    let (caixinha, eh_dono) = acesso(&mut tx, id, auth.user_id()).await?;

    let mov = movimento_travado(&mut tx, id, mov_id, auth.user_id(), eh_dono).await?;
    let contraparte = if mov.1 == modelo::APORTE { caixinha.1 } else { mov.2 };
    // Permissão antes do estado (mesma regra do recusar).
    if auth.user_id() != contraparte {
        return Err(AppError::Conflict("Só a outra parte pode confirmar.".into()));
    }
    if mov.5 != modelo::PROPOSTO {
        return Err(AppError::Conflict("Este movimento já foi resolvido.".into()));
    }

    // Revalidação com o estado ATUAL, travado pela linha da caixinha. A
    // proposta já desconta pendentes, então no fluxo sequencial da API este
    // 409 não ocorre; ele fica como rede de segurança contra concorrência e
    // escrita direta fora do fluxo proposta/confirm.
    let movs = movimentos_da_caixinha(&mut *tx, id).await?;
    let modelo_movs = movs.iter().map(para_modelo).collect::<Vec<_>>();
    match mov.1.as_str() {
        modelo::APORTE => {
            if modelo::arrecadado(&modelo_movs) + mov.3 > caixinha.3 {
                return Err(AppError::Conflict("O valor pedido já foi alcançado.".into()));
            }
        }
        modelo::RENDIMENTO => {
            let competencia = mov
                .4
                .map(mes_da_data)
                .ok_or_else(|| {
                    AppError::Internal(anyhow::anyhow!(
                        "rendimento {mov_id} sem competência"
                    ))
                })?;
            let ja_pago = modelo::pago_mes(&modelo_movs, mov.2, competencia);
            let devido = modelo::devido_mes(&modelo_movs, mov.2, competencia, caixinha.4);
            if ja_pago + mov.3 > devido {
                return Err(AppError::Conflict(
                    "O rendimento deste mês já foi pago.".into(),
                ));
            }
        }
        modelo::DEVOLUCAO => {
            if mov.3 > modelo::saldo_em_aberto(&modelo_movs, mov.2) {
                return Err(AppError::Conflict(
                    "O saldo em aberto já foi quitado.".into(),
                ));
            }
        }
        _ => {}
    }

    let rows = sqlx::query(
        "UPDATE caixinha_movimentos
            SET status = 'CONFIRMADO', confirmado_em = now()
          WHERE id = $1 AND status = 'PROPOSTO'",
    )
    .bind(mov_id)
    .execute(&mut *tx)
    .await?;
    if rows.rows_affected() == 0 {
        // Corrida defensiva: com os FOR UPDATE acima não deveria acontecer,
        // mas se o movimento saiu de PROPOSTO entre o SELECT e o UPDATE,
        // desfaz — nada foi gravado.
        tracing::warn!(movimento = %mov_id, "confirmação concorrente; transação desfeita");
        tx.rollback().await?;
        return Err(AppError::Conflict("Este movimento já foi resolvido.".into()));
    }

    // Score do responsável: só movimento CONFIRMADO pontua, na MESMA
    // transação do UPDATE — se a confirmação falhar, o evento não existe.
    let evento = match mov.1.as_str() {
        modelo::RENDIMENTO => {
            let competencia = mov.4.map(mes_da_data).ok_or_else(|| {
                AppError::Internal(anyhow::anyhow!(
                    "rendimento {mov_id} sem competência"
                ))
            })?;
            let atraso = caixinha_score::atraso_meses(competencia, Utc::now());
            Some((caixinha_score::Tipo::Rendimento, caixinha_score::pontos_rendimento(atraso)))
        }
        modelo::DEVOLUCAO => {
            let depois = movimentos_da_caixinha(&mut *tx, id).await?;
            let modelo_depois = depois.iter().map(para_modelo).collect::<Vec<_>>();
            let quitou = modelo::saldo_em_aberto(&modelo_depois, mov.2) <= Decimal::ZERO;
            let ja_pontuou = sqlx::query_scalar::<_, bool>(
                "SELECT EXISTS (
                     SELECT 1 FROM caixinha_score_eventos e
                       JOIN caixinha_movimentos m ON m.id = e.movimento_id
                      WHERE e.tipo = 'DEVOLUCAO' AND e.caixinha_id = $1
                        AND m.membro_user_id = $2)",
            )
            .bind(id)
            .bind(mov.2)
            .fetch_one(&mut *tx)
            .await?;
            if quitou && !ja_pontuou {
                Some((caixinha_score::Tipo::Devolucao, caixinha_score::PONTOS_QUITACAO))
            } else {
                None
            }
        }
        _ => None,
    };
    if let Some((tipo, pontos)) = evento {
        sqlx::query(
            "INSERT INTO caixinha_score_eventos
                 (id, dono_user_id, caixinha_id, tipo, pontos, movimento_id)
             VALUES ($1, $2, $3, $4, $5, $6)
             ON CONFLICT (movimento_id) DO NOTHING",
        )
        .bind(Uuid::new_v4())
        .bind(caixinha.1)
        .bind(id)
        .bind(tipo.texto())
        .bind(pontos)
        .bind(mov_id)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;

    Ok(Json(carregar_detalhe(&state, id, auth.user_id(), mes).await?))
}

/// Contraparte recusa: o movimento vira `RECUSADO` e fica no histórico —
/// recusado nunca entra nas contas.
pub async fn recusar_movimento(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path((id, mov_id)): Path<(Uuid, Uuid)>,
    Query(q): Query<MesQuery>,
) -> AppResult<Json<CaixinhaDetalhe>> {
    let mes = mes_da_query(&q)?;
    let mut tx = state.db.begin().await?;
    let (caixinha, eh_dono) = acesso(&mut tx, id, auth.user_id()).await?;

    let mov = movimento_travado(&mut tx, id, mov_id, auth.user_id(), eh_dono).await?;
    let contraparte = if mov.1 == modelo::APORTE { caixinha.1 } else { mov.2 };
    // Permissão antes do estado: quem nem podia resolver não descobre o
    // estado da pendência (a mensagem certa para ele é "Só a outra parte").
    if auth.user_id() != contraparte {
        return Err(AppError::Conflict("Só a outra parte pode recusar.".into()));
    }
    if mov.5 != modelo::PROPOSTO {
        return Err(AppError::Conflict("Este movimento já foi resolvido.".into()));
    }

    sqlx::query(
        "UPDATE caixinha_movimentos SET status = 'RECUSADO'
          WHERE id = $1 AND status = 'PROPOSTO'",
    )
    .bind(mov_id)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;

    Ok(Json(carregar_detalhe(&state, id, auth.user_id(), mes).await?))
}

/// Quem propôs cancela a própria proposta antes da contraparte resolver.
pub async fn cancelar_movimento(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path((id, mov_id)): Path<(Uuid, Uuid)>,
    Query(q): Query<MesQuery>,
) -> AppResult<Json<CaixinhaDetalhe>> {
    let mes = mes_da_query(&q)?;
    let mut tx = state.db.begin().await?;
    let (_, eh_dono) = acesso(&mut tx, id, auth.user_id()).await?;

    let mov = movimento_travado(&mut tx, id, mov_id, auth.user_id(), eh_dono).await?;
    // Permissão antes do estado: só o autor da proposta mexe nela.
    if mov.6 != auth.user_id() {
        return Err(AppError::Conflict("Só quem propôs pode cancelar.".into()));
    }
    if mov.5 != modelo::PROPOSTO {
        return Err(AppError::Conflict("Este movimento já foi resolvido.".into()));
    }

    sqlx::query(
        "UPDATE caixinha_movimentos SET status = 'CANCELADO'
          WHERE id = $1 AND status = 'PROPOSTO'",
    )
    .bind(mov_id)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;

    Ok(Json(carregar_detalhe(&state, id, auth.user_id(), mes).await?))
}

// ── PATCH /caixinhas/{id}/meu-telefone ───────────────────────────────────────

/// Cada um grava o próprio telefone: dono em `caixinhas.telefone_dono`,
/// membro na sua linha de `caixinha_membros`. Só dígitos (10 ou 11) —
/// formatação é coisa da tela.
pub async fn meu_telefone(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
    Query(q): Query<MesQuery>,
    Json(req): Json<TelefoneRequest>,
) -> AppResult<Json<CaixinhaDetalhe>> {
    let mes = mes_da_query(&q)?;
    let digitos = telefone_digits(&req.telefone)?;

    let mut tx = state.db.begin().await?;
    let (_, eh_dono) = acesso(&mut tx, id, auth.user_id()).await?;
    if eh_dono {
        sqlx::query("UPDATE caixinhas SET telefone_dono = $2 WHERE id = $1")
            .bind(id)
            .bind(&digitos)
            .execute(&mut *tx)
            .await?;
    } else {
        sqlx::query(
            "UPDATE caixinha_membros SET telefone = $3
              WHERE caixinha_id = $1 AND user_id = $2",
        )
        .bind(id)
        .bind(auth.user_id())
        .bind(&digitos)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;

    Ok(Json(carregar_detalhe(&state, id, auth.user_id(), mes).await?))
}

// ── helpers ──────────────────────────────────────────────────────────────────

/// Linha de `caixinhas`: id, dono, nome, valor_pedido, taxa_mensal,
/// previsao_quitacao, telefone_dono, criado_em.
type CaixinhaRow = (
    Uuid,
    Uuid,
    String,
    Decimal,
    Decimal,
    Option<String>,
    Option<String>,
    DateTime<Utc>,
);

/// Movimento com nome do membro: id, tipo, membro_user_id, valor,
/// competencia, status, proposto_por, criado_em, confirmado_em, membro_nome.
type MovRow = (
    Uuid,
    String,
    Uuid,
    Decimal,
    Option<NaiveDate>,
    String,
    Uuid,
    DateTime<Utc>,
    Option<DateTime<Utc>>,
    String,
);

/// Movimento travado (sem nome — os verbos de resolver não precisam).
type MovLockRow = (Uuid, String, Uuid, Decimal, Option<NaiveDate>, String, Uuid);

/// Linha de `GET /caixinhas`: id, nome, dono, valor_pedido, taxa_mensal,
/// criado_em, arrecadado.
type MinhaRow = (
    Uuid,
    String,
    Uuid,
    Decimal,
    Decimal,
    DateTime<Utc>,
    Decimal,
);

/// Membro da caixinha: user_id, nome, telefone, entrou_em.
type MembroRow = (Uuid, String, Option<String>, DateTime<Utc>);

/// Telefone válido = só dígitos, 10 ou 11 (com DDD). Guardam-se apenas os
/// dígitos; a formatação "(11) 98888-7777" é da tela, não do banco.
fn telefone_digits(texto: &str) -> AppResult<String> {
    let digitos: String = texto.chars().filter(|c| c.is_ascii_digit()).collect();
    if digitos.len() != 10 && digitos.len() != 11 {
        return Err(AppError::NaoValido("Digite um telefone com DDD.".into()));
    }
    Ok(digitos)
}

fn mes_da_data(d: NaiveDate) -> Mes {
    Mes::novo(d.year(), d.month()).expect("competencia guardada como dia 1 válido")
}

fn para_modelo(r: &MovRow) -> modelo::Movimento {
    modelo::Movimento {
        tipo: r.1.clone(),
        membro: r.2,
        valor: r.3,
        competencia: r.4.map(mes_da_data),
        status: r.5.clone(),
        confirmado_em: r.8,
    }
}

/// Caixinha travada (`FOR UPDATE`) + papel de quem pergunta. A linha travada
/// serializa as mutações concorrentes da MESMA caixinha: validação e INSERT
/// sempre enxergam o mesmo estado. Inexistente e não-dono-não-membro são
/// indistinguíveis de propósito — 404 `Caixinha não encontrada.`, nunca 403.
async fn acesso(
    tx: &mut Transaction<'_, Postgres>,
    id: Uuid,
    user: Uuid,
) -> AppResult<(CaixinhaRow, bool)> {
    let caixinha = sqlx::query_as::<_, CaixinhaRow>(
        "SELECT id, dono_user_id, nome, valor_pedido, taxa_mensal, previsao_quitacao,
                telefone_dono, criado_em
           FROM caixinhas WHERE id = $1
          FOR UPDATE",
    )
    .bind(id)
    .fetch_optional(&mut **tx)
    .await?
    .ok_or_else(|| AppError::NaoEncontrado("Caixinha não encontrada.".into()))?;

    let eh_dono = caixinha.1 == user;
    if !eh_dono {
        let eh_membro = sqlx::query_scalar::<_, bool>(
            "SELECT EXISTS (SELECT 1 FROM caixinha_membros
                             WHERE caixinha_id = $1 AND user_id = $2)",
        )
        .bind(id)
        .bind(user)
        .fetch_one(&mut **tx)
        .await?;
        if !eh_membro {
            return Err(AppError::NaoEncontrado("Caixinha não encontrada.".into()));
        }
    }
    Ok((caixinha, eh_dono))
}

/// Movimento travado (`FOR UPDATE`) dentro da caixinha. A visibilidade vale
/// aqui também: movimento de outro membro é 404 `Movimento não encontrado.`
/// para quem não pode vê-lo — a fronteira do detalhe não tem exceção.
async fn movimento_travado(
    tx: &mut Transaction<'_, Postgres>,
    caixinha_id: Uuid,
    mov_id: Uuid,
    user: Uuid,
    eh_dono: bool,
) -> AppResult<MovLockRow> {
    let sql = if eh_dono {
        "SELECT id, tipo, membro_user_id, valor, competencia, status, proposto_por
           FROM caixinha_movimentos
          WHERE id = $1 AND caixinha_id = $2
          FOR UPDATE"
    } else {
        "SELECT id, tipo, membro_user_id, valor, competencia, status, proposto_por
           FROM caixinha_movimentos
          WHERE id = $1 AND caixinha_id = $2 AND membro_user_id = $3
          FOR UPDATE"
    };

    let mut query = sqlx::query_as::<_, MovLockRow>(sql)
        .bind(mov_id)
        .bind(caixinha_id);
    if !eh_dono {
        query = query.bind(user);
    }

    query
        .fetch_optional(&mut **tx)
        .await?
        .ok_or_else(|| AppError::NaoEncontrado("Movimento não encontrado.".into()))
}

/// Todos os movimentos da caixinha, mais recentes primeiro, com o nome do
/// membro. As visões filtram; as somas da caixinha (arrecadado) usam tudo —
/// `arrecadado` é número da caixinha, não do membro.
async fn movimentos_da_caixinha<'e, E: PgExecutor<'e>>(
    exec: E,
    caixinha_id: Uuid,
) -> AppResult<Vec<MovRow>> {
    Ok(sqlx::query_as::<_, MovRow>(
        "SELECT m.id, m.tipo, m.membro_user_id, m.valor, m.competencia, m.status,
                m.proposto_por, m.criado_em, m.confirmado_em,
                COALESCE(u.display_name, split_part(u.email, '@', 1)) AS membro_nome
           FROM caixinha_movimentos m
           JOIN users u ON u.id = m.membro_user_id
          WHERE m.caixinha_id = $1
          ORDER BY m.criado_em DESC, m.id DESC",
    )
    .bind(caixinha_id)
    .fetch_all(exec)
    .await?)
}

/// Gera o próximo código da caixinha: `ON CONFLICT DO NOTHING` contra o
/// índice parcial da chave ativa + retry (máx. 5). Colisão é raridade
/// (~16,7M de códigos ativos no mundo inteiro) — o retry é defensivo.
async fn inserir_convite(
    tx: &mut Transaction<'_, Postgres>,
    caixinha_id: Uuid,
) -> AppResult<()> {
    for _ in 0..5 {
        let chave = modelo::gerar_chave();
        let r = sqlx::query(
            "INSERT INTO caixinha_convites (caixinha_id, chave)
             VALUES ($1, $2)
             ON CONFLICT DO NOTHING",
        )
        .bind(caixinha_id)
        .bind(&chave)
        .execute(&mut **tx)
        .await?;
        if r.rows_affected() > 0 {
            return Ok(());
        }
    }
    Err(AppError::Internal(anyhow::anyhow!(
        "não foi possível gerar um convite novo para a caixinha {caixinha_id}"
    )))
}

/// Score do responsável: eventos CONFIRMADOS + atraso de rendimento derivado.
/// Nada é guardado — mesma filosofia do saldo. Devolve (score, evidências).
pub async fn score_do_dono(db: &PgPool, dono: Uuid) -> AppResult<(i32, i32)> {
    let caixinhas = sqlx::query_as::<_, (Uuid, Decimal)>(
        "SELECT id, taxa_mensal FROM caixinhas WHERE dono_user_id = $1",
    )
    .bind(dono)
    .fetch_all(db)
    .await?;

    let eventos_rows = sqlx::query_as::<_, (Uuid, String, i32, Option<Uuid>)>(
        "SELECT e.caixinha_id, e.tipo, e.pontos, m.membro_user_id
           FROM caixinha_score_eventos e
           LEFT JOIN caixinha_movimentos m ON m.id = e.movimento_id
          WHERE e.dono_user_id = $1",
    )
    .bind(dono)
    .fetch_all(db)
    .await?;

    let eventos: Vec<caixinha_score::Evento> = eventos_rows
        .into_iter()
        .filter_map(|(caixinha_id, tipo, pontos, membro_id)| {
            let Some(tipo) = caixinha_score::Tipo::do_banco(&tipo) else {
                tracing::warn!(%caixinha_id, tipo, "evento de score com tipo desconhecido");
                return None;
            };
            Some(caixinha_score::Evento { caixinha_id, membro_id, tipo, pontos })
        })
        .collect();

    let atual = Mes::atual_utc();
    let mut penalidades = Vec::new();
    for (caixinha_id, taxa) in &caixinhas {
        let movs = movimentos_da_caixinha(db, *caixinha_id).await?;
        let modelo_movs = movs.iter().map(para_modelo).collect::<Vec<_>>();
        let mut ids: Vec<Uuid> = movs
            .iter()
            .filter(|m| m.1 == modelo::APORTE && m.5 == modelo::CONFIRMADO)
            .map(|m| m.2)
            .collect();
        ids.sort();
        ids.dedup();
        // Sem aporte confirmado não há rendimento devido nem o que penalizar.
        if ids.is_empty() {
            continue;
        }
        let Some(primeiro_aporte) = movs
            .iter()
            .filter(|m| m.1 == modelo::APORTE && m.5 == modelo::CONFIRMADO)
            .filter_map(|m| m.8)
            .min()
        else {
            continue;
        };
        let inicio = Mes { ano: primeiro_aporte.year(), mes: primeiro_aporte.month() };
        let mut comp = inicio.proximo();
        let mut meses_atrasados = 0;
        while (comp.ano, comp.mes) < (atual.ano, atual.mes)
            && meses_atrasados < caixinha_score::MESES_ATRASO_MAX
        {
            if modelo::totais_mes(&modelo_movs, &ids, comp, *taxa).2 > Decimal::ZERO {
                meses_atrasados += 1;
            }
            comp = comp.proximo();
        }
        if meses_atrasados > 0 {
            penalidades.push((*caixinha_id, caixinha_score::penalidade_atraso(meses_atrasados)));
        }
    }

    let evidencias = sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(DISTINCT m.membro_user_id)::int8
           FROM caixinha_movimentos m
           JOIN caixinhas c ON c.id = m.caixinha_id
          WHERE c.dono_user_id = $1
            AND m.tipo = 'APORTE' AND m.status = 'CONFIRMADO'",
    )
    .bind(dono)
    .fetch_one(db)
    .await?;

    Ok((
        caixinha_score::calcular(&eventos, &penalidades, evidencias),
        evidencias as i32,
    ))
}

/// Monta o `CaixinhaDetalhe` — a resposta de GET, entrar e de TODA mutação.
/// Dono vê tudo; membro vê `membros` e `movimentos` só dele, `convite`
/// nulo e totais `null`. Todo número sai das funções puras do modelo.
async fn carregar_detalhe(
    state: &AppState,
    id: Uuid,
    user: Uuid,
    mes: Mes,
) -> AppResult<CaixinhaDetalhe> {
    let caixinha = sqlx::query_as::<_, CaixinhaRow>(
        "SELECT id, dono_user_id, nome, valor_pedido, taxa_mensal, previsao_quitacao,
                telefone_dono, criado_em
           FROM caixinhas WHERE id = $1",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await?
    .ok_or_else(|| AppError::NaoEncontrado("Caixinha não encontrada.".into()))?;

    let eh_dono = caixinha.1 == user;
    if !eh_dono {
        let eh_membro = sqlx::query_scalar::<_, bool>(
            "SELECT EXISTS (SELECT 1 FROM caixinha_membros
                             WHERE caixinha_id = $1 AND user_id = $2)",
        )
        .bind(id)
        .bind(user)
        .fetch_one(&state.db)
        .await?;
        if !eh_membro {
            return Err(AppError::NaoEncontrado("Caixinha não encontrada.".into()));
        }
    }
    let papel = if eh_dono { "dono" } else { "membro" };

    let dono_nome = sqlx::query_scalar::<_, String>(
        "SELECT COALESCE(u.display_name, split_part(u.email, '@', 1))
           FROM users u WHERE u.id = $1",
    )
    .bind(caixinha.1)
    .fetch_one(&state.db)
    .await?;

    let membros_rows = sqlx::query_as::<_, MembroRow>(
        "SELECT cm.user_id,
                COALESCE(u.display_name, split_part(u.email, '@', 1)),
                cm.telefone, cm.entrou_em
           FROM caixinha_membros cm
           JOIN users u ON u.id = cm.user_id
          WHERE cm.caixinha_id = $1
          ORDER BY cm.entrou_em",
    )
    .bind(id)
    .fetch_all(&state.db)
    .await?;

    let movs = movimentos_da_caixinha(&state.db, id).await?;
    let modelo_movs = movs.iter().map(para_modelo).collect::<Vec<_>>();

    let (responsavel_score, responsavel_evidencias) =
        score_do_dono(&state.db, caixinha.1).await?;

    // Convite SEMPRE o ativo — e só pro dono. Usado é histórico: a API não
    // devolve, a tela do dono não polui.
    let convite = if eh_dono {
        sqlx::query_scalar::<_, String>(
            "SELECT chave FROM caixinha_convites
             WHERE caixinha_id = $1 AND usado_em IS NULL",
        )
        .bind(id)
        .fetch_optional(&state.db)
        .await?
    } else {
        None
    };

    let taxa = caixinha.4;
    let arrecadado = modelo::arrecadado(&modelo_movs);
    let arrecadado_pendente = modelo::arrecadado_pendente(&modelo_movs);

    // Membro vê só ele; dono vê todos.
    let visiveis: Vec<&MembroRow> = if eh_dono {
        membros_rows.iter().collect()
    } else {
        membros_rows.iter().filter(|m| m.0 == user).collect()
    };
    let membros = visiveis
        .iter()
        .map(|m| MembroInfo {
            user_id: m.0,
            nome: m.1.clone(),
            telefone: m.2.clone(),
            aportado: Money::new(modelo::aportado(&modelo_movs, m.0)),
            saldo_em_aberto: Money::new(modelo::saldo_em_aberto(&modelo_movs, m.0)),
            devido_mes: Money::new(modelo::devido_mes(&modelo_movs, m.0, mes, taxa)),
            pago_mes: Money::new(modelo::pago_mes(&modelo_movs, m.0, mes)),
            falta_mes: Money::new(modelo::falta_mes(&modelo_movs, m.0, mes, taxa)),
            rendimento_disponivel: Money::new(modelo::rendimento_disponivel(
                &modelo_movs, m.0, mes, taxa,
            )),
            rendimento_pendente: modelo::rendimento_pendente(&modelo_movs, m.0, mes),
        })
        .collect();

    // Totais do mês: só pro dono (o membro recebe null — número de caixinha
    // inteira não é número dele).
    let totais = if eh_dono {
        let ids: Vec<Uuid> = membros_rows.iter().map(|m| m.0).collect();
        let (devido, pago, falta) = modelo::totais_mes(&modelo_movs, &ids, mes, taxa);
        (
            Some(Money::new(devido)),
            Some(Money::new(pago)),
            Some(Money::new(falta)),
        )
    } else {
        (None, None, None)
    };

    // "Para confirmar" = todos os PROPOSTO visíveis, UNION os últimos 10
    // resolvidos visíveis, criado_em DESC. PROPOSTO e resolvido são conjuntos
    // disjuntos (status é um só por linha): a união não duplica.
    let movimentos_visiveis: Vec<&MovRow> = movs
        .iter()
        .filter(|m| eh_dono || m.2 == user)
        .collect();
    let mut lista: Vec<&MovRow> = movimentos_visiveis
        .iter()
        .filter(|m| m.5 == modelo::PROPOSTO)
        .map(|m| *m)
        .collect();
    for m in movimentos_visiveis
        .iter()
        .filter(|m| m.5 != modelo::PROPOSTO)
        .take(10)
    {
        lista.push(*m);
    }
    // O SQL já devolve criado_em DESC; o sort é o da união das duas fontes.
    lista.sort_by(|a, b| b.7.cmp(&a.7).then_with(|| b.0.cmp(&a.0)));

    let movimentos = lista
        .iter()
        .map(|m| MovimentoInfo {
            id: m.0,
            tipo: m.1.clone(),
            membro_id: m.2,
            membro_nome: m.9.clone(),
            valor: Money::new(m.3),
            competencia: m.4.map(|d| mes_da_data(d).texto()),
            status: m.5.clone(),
            proposto_por: m.6,
            criado_em: m.7,
            confirmado_em: m.8,
        })
        .collect();

    Ok(CaixinhaDetalhe {
        id: caixinha.0,
        nome: caixinha.2.clone(),
        papel: papel.to_string(),
        dono: DonoInfo {
            user_id: caixinha.1,
            nome: dono_nome,
            telefone: caixinha.6.clone(),
        },
        valor_pedido: Money::new(caixinha.3),
        taxa_mensal: Taxa::new(taxa),
        previsao_quitacao: caixinha.5.clone(),
        arrecadado: Money::new(arrecadado),
        arrecadado_pendente: if eh_dono {
            Some(Money::new(arrecadado_pendente))
        } else {
            None
        },
        disponivel_aporte: Money::new(modelo::disponivel_aporte(caixinha.3, &modelo_movs)),
        progresso: modelo::progresso(arrecadado, caixinha.3),
        responsavel_score,
        responsavel_evidencias,
        convite,
        membros,
        total_devido_mes: totais.0,
        total_pago_mes: totais.1,
        total_falta_mes: totais.2,
        movimentos,
    })
}
