//! Score 0..=1000 do responsável pela caixinha — reputação DECLARADA e
//! confirmada entre as partes, não score de crédito.
//!
//! A regra central é a mesma do ledger: nada de saldo guardado. O score é
//! `BASE + eventos + penalidades de atraso`, derivado a cada leitura. Eventos
//! são append-only (só `AJUSTE` corrige) e cada movimento pontua no máximo uma
//! vez (`movimento_id UNIQUE`).
//!
//! O que impede a fraude fácil:
//!  - só movimento CONFIRMADO pontua; proposta, recusa e cancelamento não;
//!  - quem confirma é a contraparte, não quem propõe;
//!  - teto por caixinha e por par (dono, membro): criar caixinhas entre os
//!    mesmos dois amigos não infla o score;
//!  - acima do teto sem evidência exige pelo menos dois membros distintos que
//!    aportaram — uma conta sozinha não fabrica reputação alta;
//!  - atraso de rendimento derruba, mês a mês.
//!
//! Limite honesto: sem custódia do dinheiro, o sistema registra o que as
//! partes afirmam. Colusão entre dono e membros continua possível; por isso a
//! tela nunca deve chamar isto de garantia ou de score de crédito.

use std::collections::HashMap;

use chrono::{DateTime, Datelike, TimeZone, Utc};
use uuid::Uuid;

use crate::caixinha_modelo::Mes;

pub const BASE: i32 = 500;
pub const MIN: i32 = 0;
pub const MAX: i32 = 1000;

/// Rendimento confirmado em dia; cada mês de atraso tira 10.
pub const PONTOS_RENDIMENTO: i32 = 25;
pub const PENALIDADE_POR_MES: i32 = 10;
/// Quitação total do saldo em aberto de um membro.
pub const PONTOS_QUITACAO: i32 = 70;

/// Teto de ganho por caixinha e por par (dono, membro).
pub const CAP_CAIXINHA: i32 = 120;
pub const CAP_PAR: i32 = 100;
/// Penalidade de rendimento em aberto: no máximo 6 meses por caixinha.
pub const MESES_ATRASO_MAX: i32 = 6;

/// Acima disto o score precisa de pelo menos duas testemunhas distintas.
pub const TETO_SEM_EVIDENCIA: i32 = 600;
pub const MIN_EVIDENCIAS: i64 = 2;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Tipo {
    Rendimento,
    Devolucao,
    Ajuste,
}

impl Tipo {
    pub fn do_banco(texto: &str) -> Option<Self> {
        match texto {
            "RENDIMENTO" => Some(Tipo::Rendimento),
            "DEVOLUCAO" => Some(Tipo::Devolucao),
            "AJUSTE" => Some(Tipo::Ajuste),
            _ => None,
        }
    }

    pub fn texto(&self) -> &'static str {
        match self {
            Tipo::Rendimento => "RENDIMENTO",
            Tipo::Devolucao => "DEVOLUCAO",
            Tipo::Ajuste => "AJUSTE",
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct Evento {
    pub caixinha_id: Uuid,
    pub membro_id: Option<Uuid>,
    pub tipo: Tipo,
    pub pontos: i32,
}

/// O rendimento da competência vence no dia 10 do mês seguinte, 00:00 UTC.
pub fn vencimento(competencia: Mes) -> DateTime<Utc> {
    let seguinte = competencia.proximo();
    Utc.with_ymd_and_hms(seguinte.ano, seguinte.mes, 10, 0, 0, 0)
        .single()
        .expect("dia 10 existe em todo mês")
}

/// Meses INTEIROS de atraso depois do vencimento. Pagar antes ou no dia 10
/// conta como em dia; pagar em 09/01 o rendimento de 11/2026 ainda é 0 mês.
pub fn atraso_meses(competencia: Mes, pago_em: DateTime<Utc>) -> i32 {
    let vence = vencimento(competencia);
    if pago_em <= vence {
        return 0;
    }
    let mut meses = (pago_em.year() * 12 + pago_em.month() as i32)
        - (vence.year() * 12 + vence.month() as i32);
    if pago_em.day() < vence.day() {
        meses -= 1;
    }
    meses.max(0)
}

pub fn pontos_rendimento(atraso_meses: i32) -> i32 {
    (PONTOS_RENDIMENTO - PENALIDADE_POR_MES * atraso_meses.max(0)).max(0)
}

/// Penalidade dos meses em aberto (valor negativo, limitado a 6 meses).
pub fn penalidade_atraso(meses_em_aberto: i32) -> i32 {
    -(PENALIDADE_POR_MES * meses_em_aberto.clamp(0, MESES_ATRASO_MAX))
}

/// `penalidades`: um item por caixinha com a soma dos meses em aberto.
pub fn calcular(
    eventos: &[Evento],
    penalidades: &[(Uuid, i32)],
    evidencias: i64,
) -> i32 {
    let mut pares: HashMap<(Uuid, Uuid), i32> = HashMap::new();
    let mut por_caixinha: HashMap<Uuid, i32> = HashMap::new();

    for e in eventos {
        match e.membro_id {
            Some(membro) => *pares.entry((e.caixinha_id, membro)).or_default() += e.pontos,
            None => *por_caixinha.entry(e.caixinha_id).or_default() += e.pontos,
        }
    }
    for ((caixinha, _), pontos) in pares {
        *por_caixinha.entry(caixinha).or_default() += pontos.clamp(-CAP_PAR, CAP_PAR);
    }
    for (caixinha, pontos) in penalidades {
        *por_caixinha.entry(*caixinha).or_default() += pontos;
    }

    let mut total = BASE;
    for (_, pontos) in por_caixinha {
        total += pontos.clamp(-CAP_CAIXINHA, CAP_CAIXINHA);
    }

    let teto = if evidencias >= MIN_EVIDENCIAS {
        MAX
    } else {
        TETO_SEM_EVIDENCIA
    };
    total.clamp(MIN, teto)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mes(ano: i32, m: u32) -> Mes {
        Mes::novo(ano, m).unwrap()
    }

    fn data(ano: i32, m: u32, d: u32) -> DateTime<Utc> {
        Utc.with_ymd_and_hms(ano, m, d, 12, 0, 0).single().unwrap()
    }

    #[test]
    fn atraso_conta_meses_inteiros_desde_o_dia_dez() {
        let competencia = mes(2026, 11); // vence 2026-12-10
        assert_eq!(atraso_meses(competencia, data(2026, 12, 9)), 0);
        assert_eq!(atraso_meses(competencia, data(2026, 12, 10)), 0);
        assert_eq!(atraso_meses(competencia, data(2027, 1, 9)), 0);
        assert_eq!(atraso_meses(competencia, data(2027, 1, 10)), 1);
        assert_eq!(atraso_meses(competencia, data(2027, 3, 15)), 3);
    }

    #[test]
    fn rendimento_em_dia_vale_vinte_e_cinco() {
        assert_eq!(pontos_rendimento(0), 25);
        assert_eq!(pontos_rendimento(1), 15);
        assert_eq!(pontos_rendimento(3), 0);
        assert_eq!(pontos_rendimento(9), 0);
    }

    #[test]
    fn penalidade_de_atraso_tem_teto_de_seis_meses() {
        assert_eq!(penalidade_atraso(0), 0);
        assert_eq!(penalidade_atraso(2), -20);
        assert_eq!(penalidade_atraso(6), -60);
        assert_eq!(penalidade_atraso(99), -60);
    }

    #[test]
    fn teto_por_par_nao_deixa_o_mesmo_amigo_inflar() {
        let caixinha = Uuid::new_v4();
        let membro = Uuid::new_v4();
        let eventos = vec![
            Evento { caixinha_id: caixinha, membro_id: Some(membro), tipo: Tipo::Devolucao, pontos: 70 },
            Evento { caixinha_id: caixinha, membro_id: Some(membro), tipo: Tipo::Devolucao, pontos: 70 },
            Evento { caixinha_id: caixinha, membro_id: Some(membro), tipo: Tipo::Devolucao, pontos: 70 },
        ];
        assert_eq!(calcular(&eventos, &[], 2), 600);
    }

    #[test]
    fn teto_por_caixinha_vale_mesmo_com_varios_membros() {
        let caixinha = Uuid::new_v4();
        let eventos: Vec<Evento> = (0..3)
            .map(|_| Evento {
                caixinha_id: caixinha,
                membro_id: Some(Uuid::new_v4()),
                tipo: Tipo::Devolucao,
                pontos: 70,
            })
            .collect();
        assert_eq!(calcular(&eventos, &[], 3), 620);
    }

    #[test]
    fn sem_duas_evidencias_o_score_para_em_seiscentos() {
        let caixinha = Uuid::new_v4();
        let eventos: Vec<Evento> = (0..4)
            .map(|_| Evento {
                caixinha_id: caixinha,
                membro_id: Some(Uuid::new_v4()),
                tipo: Tipo::Devolucao,
                pontos: 70,
            })
            .collect();
        assert_eq!(calcular(&eventos, &[], 1), TETO_SEM_EVIDENCIA);
        assert!(calcular(&eventos, &[], 2) > TETO_SEM_EVIDENCIA);
    }

    #[test]
    fn atraso_derruba_e_o_score_nunca_sai_da_faixa() {
        let caixinha = Uuid::new_v4();
        let penalidades = vec![(caixinha, -60)];
        assert_eq!(calcular(&[], &penalidades, 0), 440);

        let mut muitos = Vec::new();
        for _ in 0..50 {
            muitos.push((Uuid::new_v4(), -60));
        }
        assert_eq!(calcular(&[], &muitos, 0), MIN);
    }
}
