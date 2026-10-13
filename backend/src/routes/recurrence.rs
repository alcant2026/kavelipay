//! Contas recorrentes.
//!
//! Decisão de modelagem: uma recorrência **não é uma entidade nova**. É a
//! próxima ocorrência de um `bills` normal, com `recurrence` descrevendo o
//! padrão. Gerar a ocorrência de novembro é criar um boleto como qualquer outro
//! — pagar, cancelar e notificar seguem o mesmo caminho já testado.
//!
//! O alternativa (tabela `recurring_bills` que gera boletos) duplicaria o fluxo
//! inteiro e criaria duas fontes de verdade para "o que vence este mês".

use axum::Json;
use axum::extract::State;
use axum::http::StatusCode;
use chrono::{Datelike, NaiveDate};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::{AppError, AppResult};
use crate::state::AppState;

#[derive(Serialize)]
pub struct RecurrenceResponse {
    pub source_bill_id: Uuid,
    pub created_bill_id: Uuid,
    pub description: String,
    pub amount: crate::money::Money,
    pub due_date: chrono::NaiveDate,
}

#[derive(Serialize)]
pub struct GenerateResponse {
    pub created: u64,
    pub bills: Vec<RecurrenceResponse>,
}

#[derive(Deserialize)]
pub struct GenerateRequest {
    /// Só gera para este boleto. Ausente = gera todas as séries pendentes.
    pub bill_id: Option<Uuid>,
    /// Quantos períodos à frente gerar de uma vez. Teto duro: sem ele, um app
    /// com bug pediria 10 anos de boletos num request só.
    pub months_ahead: Option<i32>,
}

const MAX_MESES: i32 = 24;
const MAX_SERIES: usize = 100;

/// POST /bills/generate-recurring
///
/// Idempotente por construção: só gera a ocorrência se ainda não existir um
/// `bills` PENDING com a mesma `recurrence_key`. A chave é
/// `<bill-fonte-id>:<YYYY-MM>`.
pub async fn generate(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Json(req): Json<GenerateRequest>,
) -> AppResult<(StatusCode, Json<GenerateResponse>)> {
    let wallet = wallet_ativa(&state, auth.user_id()).await?;
    let meses = req.months_ahead.unwrap_or(1).clamp(1, MAX_MESES);
    let hoje = chrono::Local::now().date_naive();

    let series = sqlx::query_as::<_, (
        Uuid,
        String,
        Option<String>,
        rust_decimal::Decimal,
        chrono::NaiveDate,
        String,
        Option<String>,
        Option<chrono::NaiveDate>,
        Option<Uuid>,
    )>(
        "SELECT id, description, beneficiario, amount, due_date, recurrence,
                linha_digitavel, recurrence_until, category_id
           FROM bills
          WHERE wallet_id = $1
            AND recurrence IS NOT NULL
            AND status = 'PENDING'
            AND ($2::uuid IS NULL OR id = $2)
          ORDER BY due_date
          LIMIT $3",
    )
    .bind(wallet)
    .bind(req.bill_id)
    .bind(MAX_SERIES as i64)
    .fetch_all(&state.db)
    .await?;

    let mut criadas = Vec::new();

    for (bill_id, descricao, beneficiario, valor, vencimento, recurrence, linha, ate, categoria) in
        series
    {
        for passo in 1..=meses {
            let proximo = avanca(vencimento, recurrence.as_str(), passo)?;

            // Série encerrada: passou de `recurrence_until`.
            if let Some(limite) = ate
                && proximo > limite
            {
                break;
            }

            // Não volta no tempo: uma série gerada em outubro não pode criar
            // vencimento de agosto.
            if proximo < hoje {
                continue;
            }

            let chave = format!("{bill_id}:{}", proximo.format("%Y-%m"));

            // Dedup no banco, não em memória: duas chamadas concorrentes
            // gerariam o mesmo boleto duas vezes sem isso.
            // ON CONFLICT precisa repetir o predicado do índice PARCIAL, senão o planner
            // não consegue casar a inferência e o banco erra
            // "no unique or exclusion constraint matching the ON CONFLICT".
            let inserir = sqlx::query_scalar::<_, Uuid>(
                "INSERT INTO bills
                     (wallet_id, description, beneficiario, amount, due_date,
                      linha_digitavel, category_id, recurrence_key)
                 SELECT w.id, $3, $4, $5, $6, $7, $8, $9
                   FROM wallets w
                  WHERE w.id = $1
                 ON CONFLICT (wallet_id, recurrence_key)
                     WHERE recurrence_key IS NOT NULL
                 DO NOTHING
                 RETURNING id",
            )
            .bind(wallet)
            .bind(&chave)
            .bind(&descricao)
            .bind(beneficiario.as_deref())
            .bind(valor)
            .bind(proximo)
            .bind(linha.as_deref())
            .bind(categoria)
            .bind(&chave)
            .fetch_optional(&state.db)
            .await?;

            let Some(novo_id) = inserir else {
                continue;
            }; // já existia

            tracing::info!(%bill_id, chave, "ocorrência recorrente gerada");

            criadas.push(RecurrenceResponse {
                source_bill_id: bill_id,
                created_bill_id: novo_id,
                description: descricao.clone(),
                amount: crate::money::Money::new(valor),
                due_date: proximo,
            });
        }
    }

    let n = criadas.len() as u64;
    Ok((
        StatusCode::CREATED,
        Json(GenerateResponse {
            created: n,
            bills: criadas,
        }),
    ))
}

/// Avança `passo` períodos a partir da data-base.
fn avanca(base: chrono::NaiveDate, recurrence: &str, passo: i32) -> AppResult<chrono::NaiveDate> {
    match recurrence {
        "WEEKLY" => Ok(base + chrono::Duration::weeks(passo as i64)),
        "MONTHLY" => Ok(soma_meses(base, passo)),
        "YEARLY" => Ok(soma_meses(base, passo * 12)),
        outro => Err(AppError::Validation(format!(
            "recorrência desconhecida: '{outro}'"
        ))),
    }
}

/// Soma meses fixando o dia no último dia do mês quando necessário: dia 31 +
/// um mês não pode virar dia 3 de março.
fn soma_meses(base: chrono::NaiveDate, meses: i32) -> chrono::NaiveDate {
    // Índice absoluto de meses. `div_euclid`/`rem_euclid` mantêm o resultado
    // correto em viradas de ano (div_euclid, não `/`, que trunca para zero).
    let total = base.year() * 12 + base.month0() as i32 + meses;
    let ano = total.div_euclid(12);
    let mes = (total.rem_euclid(12) + 1) as u32;

    let dia = base.day().min(ultimo_dia_do_mes(ano, mes));

    NaiveDate::from_ymd_opt(ano, mes, dia).unwrap_or(base)
}

fn ultimo_dia_do_mes(ano: i32, mes: u32) -> u32 {
    // Dia 0 do mês seguinte = último dia do mês alvo.
    let (a, m) = if mes == 12 { (ano + 1, 1) } else { (ano, mes + 1) };
    NaiveDate::from_ymd_opt(a, m, 1)
        .and_then(|d| d.pred_opt())
        .map(|d| d.day())
        .unwrap_or(28)
}

async fn wallet_ativa(state: &AppState, user_id: Uuid) -> AppResult<Uuid> {
    let row = sqlx::query_as::<_, (Uuid, String)>("SELECT id, status FROM wallets WHERE user_id = $1")
        .bind(user_id)
        .fetch_optional(&state.db)
        .await?
        .ok_or(AppError::NotFound("wallet"))?;

    if row.1 != "ACTIVE" {
        return Err(AppError::Conflict(format!(
            "carteira {}: não aceita operações",
            row.1
        )));
    }
    Ok(row.0)
}

/// Reexporta a validação de data, compartilhada com a criação de boleto.
pub fn parse_data(s: &str) -> AppResult<NaiveDate> {
    chrono::NaiveDate::parse_from_str(s.trim(), "%Y-%m-%d")
        .map_err(|_| AppError::Validation("due_date deve ser YYYY-MM-DD".into()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(y: i32, m: u32, day: u32) -> NaiveDate {
        NaiveDate::from_ymd_opt(y, m, day).unwrap()
    }

    #[test]
    fn mensal_avanca_mes_a_mes() {
        assert_eq!(avanca(d(2026, 1, 10), "MONTHLY", 1).unwrap(), d(2026, 2, 10));
        assert_eq!(avanca(d(2026, 1, 10), "MONTHLY", 12).unwrap(), d(2027, 1, 10));
    }

    #[test]
    fn dia_31_nao_vira_3_de_marco() {
        // O erro clássico de recorrência: 31/01 + 1 mês com aritmética cega dá
        // 03/03, e o boleto passa a vencer no mês errado.
        assert_eq!(avanca(d(2026, 1, 31), "MONTHLY", 1).unwrap(), d(2026, 2, 28));
        assert_eq!(avanca(d(2026, 1, 30), "MONTHLY", 1).unwrap(), d(2026, 2, 28));
        // Fevereiro de ano bissexto tem 29.
        assert_eq!(avanca(d(2028, 1, 31), "MONTHLY", 1).unwrap(), d(2028, 2, 29));
    }

    #[test]
    fn dia_mantem_quando_o_mes_alcanca_o_tamanho() {
        assert_eq!(avanca(d(2026, 3, 31), "MONTHLY", 1).unwrap(), d(2026, 4, 30));
        assert_eq!(avanca(d(2026, 1, 31), "MONTHLY", 2).unwrap(), d(2026, 3, 31));
    }

    #[test]
    fn mensal_atravessa_a_virada_do_ano() {
        assert_eq!(avanca(d(2026, 11, 5), "MONTHLY", 3).unwrap(), d(2027, 2, 5));
        assert_eq!(avanca(d(2026, 12, 20), "MONTHLY", 1).unwrap(), d(2027, 1, 20));
    }

    #[test]
    fn anual_avanca_12_meses() {
        assert_eq!(avanca(d(2026, 3, 10), "YEARLY", 1).unwrap(), d(2027, 3, 10));
    }

    #[test]
    fn semanal_avanca_7_dias() {
        assert_eq!(avanca(d(2026, 1, 1), "WEEKLY", 2).unwrap(), d(2026, 1, 15));
    }

    #[test]
    fn recorrencia_desconhecida_e_erro() {
        assert!(avanca(d(2026, 1, 1), "DIARIAMENTE", 1).is_err());
    }

    #[test]
    fn soma_meses_nao_panca_em_ano_muito_distante() {
        // Ano muito negativo indicaria bug de aritmética no índice de meses.
        for passo in 1..=24 {
            assert!(soma_meses(d(2026, 1, 15), passo).year() >= 2026);
        }
    }
}