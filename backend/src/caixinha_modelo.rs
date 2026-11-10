//! Derivações da Caixinha — funções PURAS, sem banco, sem IO.
//!
//! Espelham o protótipo `CaixinhaModelo.kt` (a especificação viva) e as
//! fórmulas de `docs/caixinha-api.md`: todo número da caixinha é derivado dos
//! movimentos, nada de saldo guardado. Só movimento `CONFIRMADO` entra em
//! qualquer soma — a regra "quem paga propõe, quem recebe confirma" vive aqui.
//!
//! Arredondamento: HALF_UP (`MidpointAwayFromZero` do rust_decimal, 2 casas)
//! — o mesmo que o protótipo Kotlin faz e que o Postgres faz com `numeric`.
//! Dinheiro é `Decimal` de ponta a ponta; `f64` em dinheiro é bug.

use chrono::{DateTime, Datelike, NaiveDate, Utc};
use rust_decimal::Decimal;
use rust_decimal::prelude::{FromPrimitive, FromStr, ToPrimitive};
use rust_decimal::RoundingStrategy;
use serde::{Deserialize, Deserializer, Serialize, Serializer, de::{Unexpected, Visitor}};
use std::fmt;
use uuid::Uuid;

pub const APORTE: &str = "APORTE";
pub const RENDIMENTO: &str = "RENDIMENTO";
pub const DEVOLUCAO: &str = "DEVOLUCAO";

pub const PROPOSTO: &str = "PROPOSTO";
pub const CONFIRMADO: &str = "CONFIRMADO";
pub const RECUSADO: &str = "RECUSADO";
pub const CANCELADO: &str = "CANCELADO";

/// Ano-mês (`YYYY-MM`). Coisa de gente, não de servidor: o app manda o mês
/// LOCAL dele e o default aqui é o mês atual UTC — ver contrato.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Mes {
    pub ano: i32,
    pub mes: u32, // 1..=12
}

impl Mes {
    pub fn novo(ano: i32, mes: u32) -> Option<Self> {
        if (1..=12).contains(&mes) {
            Some(Mes { ano, mes })
        } else {
            None
        }
    }

    /// `YYYY-MM` estrito: 4 dígitos, hífen, mês com 2 dígitos (01–12).
    /// É o formato que o app manda (`YearMonth.toString()`).
    pub fn parse(texto: &str) -> Option<Self> {
        let bytes = texto.as_bytes();
        if bytes.len() != 7 || bytes[4] != b'-' {
            return None;
        }
        if !bytes[..4].iter().all(u8::is_ascii_digit)
            || !bytes[5..].iter().all(u8::is_ascii_digit)
        {
            return None;
        }
        let ano: i32 = texto[..4].parse().ok()?;
        let mes: u32 = texto[5..].parse().ok()?;
        Mes::novo(ano, mes)
    }

    /// Mês atual UTC — o default de todo endpoint que devolve detalhe.
    pub fn atual_utc() -> Self {
        let agora = Utc::now();
        Mes { ano: agora.year(), mes: agora.month() }
    }

    pub fn proximo(&self) -> Mes {
        if self.mes == 12 {
            Mes { ano: self.ano + 1, mes: 1 }
        } else {
            Mes { ano: self.ano, mes: self.mes + 1 }
        }
    }

    /// Até que competência o dono pode propor rendimento: o próprio mês ou o
    /// seguinte (vence logo depois do aporte). Mais à frente seria cobrar juros
    /// de tempo que ainda não passou; meses anteriores ficam livres para quitar
    /// atraso.
    pub fn competencia_permitida(&self, competencia: Mes) -> bool {
        let limite = self.proximo();
        (competencia.ano, competencia.mes) <= (limite.ano, limite.mes)
    }

    /// Dia 1 do mês como data ingênua — é o que vai na coluna `competencia`.
    pub fn inicio_naive(&self) -> NaiveDate {
        NaiveDate::from_ymd_opt(self.ano, self.mes, 1).expect("mes validado na construção")
    }

    /// Dia 1 do mês, 00:00 UTC — a fronteira "confirmado antes do mês".
    pub fn inicio_utc(&self) -> DateTime<Utc> {
        self.inicio_naive()
            .and_hms_opt(0, 0, 0)
            .expect("meia-noite sempre existe")
            .and_utc()
    }

    pub fn texto(&self) -> String {
        format!("{}-{:02}", self.ano, self.mes)
    }
}

impl fmt::Display for Mes {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.texto())
    }
}

/// Movimento como as derivações enxergam. Os handlers carregam as linhas do
/// banco e convertem para aqui; as funções abaixo nunca tocam SQL.
#[derive(Debug, Clone)]
pub struct Movimento {
    pub tipo: String,
    pub membro: Uuid,
    pub valor: Decimal,
    /// Mês do rendimento; `None` em aporte e devolução.
    pub competencia: Option<Mes>,
    pub status: String,
    pub confirmado_em: Option<DateTime<Utc>>,
}

// ── derivações ───────────────────────────────────────────────────────────────

/// HALF_UP em 2 casas — o único arredondamento que existe na caixinha.
pub fn duas_casas(d: Decimal) -> Decimal {
    d.round_dp_with_strategy(2, RoundingStrategy::MidpointAwayFromZero)
}

/// Valor em reais no formato das mensagens pt-BR: "600,00" (vírgula, sem
/// separador de milhar — o mesmo que o protótipo Kotlin emite).
pub fn reais_ptbr(d: Decimal) -> String {
    format!("{:.2}", duas_casas(d)).replace('.', ",")
}

fn soma_filtrado<'a>(
    movs: impl Iterator<Item = &'a Movimento>,
    tipo: &str,
    status: &str,
) -> Decimal {
    movs.filter(|m| m.tipo == tipo && m.status == status)
        .map(|m| m.valor)
        .sum()
}

/// Σ APORTE CONFIRMADO (todos os membros).
pub fn arrecadado(movs: &[Movimento]) -> Decimal {
    duas_casas(soma_filtrado(movs.iter(), APORTE, CONFIRMADO))
}

/// Σ APORTE PROPOSTO — proposta pendente reserva o espaço do pedido.
pub fn arrecadado_pendente(movs: &[Movimento]) -> Decimal {
    duas_casas(soma_filtrado(movs.iter(), APORTE, PROPOSTO))
}

/// Espaço que falta no pedido, descontando o já arrecadado E o proposto.
pub fn disponivel_aporte(valor_pedido: Decimal, movs: &[Movimento]) -> Decimal {
    duas_casas(
        (valor_pedido - arrecadado(movs) - arrecadado_pendente(movs)).max(Decimal::ZERO),
    )
}

/// Progresso 0..1 com 4 casas (f32 na API — não é dinheiro).
pub fn progresso(arrecadado: Decimal, valor_pedido: Decimal) -> f32 {
    if valor_pedido <= Decimal::ZERO {
        return 0.0;
    }
    let razao = (arrecadado / valor_pedido)
        .round_dp_with_strategy(4, RoundingStrategy::MidpointAwayFromZero)
        .clamp(Decimal::ZERO, Decimal::ONE);
    razao.to_f32().unwrap_or(0.0)
}

/// Σ APORTE CONFIRMADO do membro.
pub fn aportado(movs: &[Movimento], membro: Uuid) -> Decimal {
    duas_casas(soma_filtrado(movs.iter().filter(|m| m.membro == membro), APORTE, CONFIRMADO))
}

/// O que o responsável ainda deve ao membro: aportes menos devoluções, nunca
/// negativo.
pub fn saldo_em_aberto(movs: &[Movimento], membro: Uuid) -> Decimal {
    let do_membro = movs.iter().filter(|m| m.membro == membro);
    let a = soma_filtrado(do_membro.clone(), APORTE, CONFIRMADO);
    let d = soma_filtrado(do_membro, DEVOLUCAO, CONFIRMADO);
    duas_casas((a - d).max(Decimal::ZERO))
}

/// Base do rendimento do mês: aportes confirmados ANTES do dia 1, menos
/// devolução PARCIAL que não reduz (só a TOTAL zera). Sem pro rata —
/// o combinado é juro sobre o valor pego.
pub fn base_do_mes(movs: &[Movimento], membro: Uuid, mes: Mes) -> Decimal {
    let inicio = mes.inicio_utc();
    let do_membro = movs
        .iter()
        .filter(|m| m.membro == membro)
        .filter(|m| m.confirmado_em.is_some_and(|c| c < inicio));
    let a = soma_filtrado(do_membro.clone(), APORTE, CONFIRMADO);
    let d = soma_filtrado(do_membro, DEVOLUCAO, CONFIRMADO);
    if d >= a {
        Decimal::ZERO
    } else {
        duas_casas(a)
    }
}

/// Rendimento mensal: base × taxa / 100, HALF_UP em 2 casas. Base ou taxa
/// sem sentido viram zero (o protótipo devolve `null`; o devido vira "0.00").
pub fn rendimento_mensal(base: Decimal, taxa: Decimal) -> Decimal {
    if base <= Decimal::ZERO || taxa <= Decimal::ZERO {
        return Decimal::ZERO;
    }
    duas_casas(base * taxa / Decimal::from(100))
}

/// Rendimento devido pelo responsável ao membro no mês.
pub fn devido_mes(movs: &[Movimento], membro: Uuid, mes: Mes, taxa: Decimal) -> Decimal {
    rendimento_mensal(base_do_mes(movs, membro, mes), taxa)
}

/// Σ RENDIMENTO CONFIRMADO do membro no mês (pela competência).
pub fn pago_mes(movs: &[Movimento], membro: Uuid, mes: Mes) -> Decimal {
    duas_casas(soma_filtrado(
        movs
            .iter()
            .filter(|m| m.membro == membro && m.competencia == Some(mes)),
        RENDIMENTO,
        CONFIRMADO,
    ))
}

/// O que ainda falta pagar no mês.
pub fn falta_mes(movs: &[Movimento], membro: Uuid, mes: Mes, taxa: Decimal) -> Decimal {
    duas_casas(
        (devido_mes(movs, membro, mes, taxa) - pago_mes(movs, membro, mes)).max(Decimal::ZERO),
    )
}

/// Falta do mês MENOS o rendimento já proposto (proposta reserva o mês).
pub fn rendimento_disponivel(
    movs: &[Movimento],
    membro: Uuid,
    mes: Mes,
    taxa: Decimal,
) -> Decimal {
    let proposto = soma_filtrado(
        movs
            .iter()
            .filter(|m| m.membro == membro && m.competencia == Some(mes)),
        RENDIMENTO,
        PROPOSTO,
    );
    duas_casas((falta_mes(movs, membro, mes, taxa) - proposto).max(Decimal::ZERO))
}

/// Existe rendimento PROPOSTO do membro no mês (a tela deriva daqui).
pub fn rendimento_pendente(movs: &[Movimento], membro: Uuid, mes: Mes) -> bool {
    movs.iter().any(|m| {
        m.membro == membro && m.competencia == Some(mes)
            && m.tipo == RENDIMENTO && m.status == PROPOSTO
    })
}

/// Saldo em aberto MENOS devoluções já propostas (proposta reserva o saldo).
pub fn disponivel_devolucao(movs: &[Movimento], membro: Uuid) -> Decimal {
    let proposto = soma_filtrado(
        movs.iter().filter(|m| m.membro == membro),
        DEVOLUCAO,
        PROPOSTO,
    );
    duas_casas((saldo_em_aberto(movs, membro) - proposto).max(Decimal::ZERO))
}

/// Totais do mês para o dono: devido e pago somam membro a membro, e a falta
/// total é `max(Σ devido − Σ pago, 0)` — igual ao protótipo.
pub fn totais_mes(
    movs: &[Movimento],
    membros: &[Uuid],
    mes: Mes,
    taxa: Decimal,
) -> (Decimal, Decimal, Decimal) {
    let devido: Decimal = membros
        .iter()
        .map(|m| devido_mes(movs, *m, mes, taxa))
        .sum();
    let pago: Decimal = membros.iter().map(|m| pago_mes(movs, *m, mes)).sum();
    (duas_casas(devido), duas_casas(pago), duas_casas((devido - pago).max(Decimal::ZERO)))
}

/// Média (vinda de um `AVG` do SQL) com UMA casa e vírgula pt-BR ("4,5"),
/// ou `None` — o formato que a tela mostra.
pub fn media_texto(media: Option<Decimal>) -> Option<String> {
    media.map(|m| {
        let m = m.round_dp_with_strategy(1, RoundingStrategy::MidpointAwayFromZero);
        format!("{m:.1}").replace('.', ",")
    })
}

/// Média das estrelas com UMA casa e vírgula pt-BR ("4,5"), ou `None`
/// enquanto ninguém avaliou — o formato que a tela mostra.
pub fn media_estrelas(estrelas: &[i16]) -> Option<String> {
    if estrelas.is_empty() {
        return None;
    }
    let soma = Decimal::from(estrelas.iter().map(|e| i64::from(*e)).sum::<i64>());
    media_texto(Some(soma / Decimal::from(estrelas.len())))
}

/// `KAV-` + 12 hex maiúsculo, da entropia do UUID v4 (sem dependência nova).
/// 48 bits: adivinhar um convite ativo por força bruta deixa de ser viável.
/// Colisão a cada ~2,8e14 códigos ativos: o `ON CONFLICT` + retry é defensivo.
pub fn gerar_chave() -> String {
    let hex = Uuid::new_v4().simple().to_string(); // 32 hex minúsculo
    format!("KAV-{}", hex[..12].to_uppercase())
}

// ── Taxa ao mês (serde) ───────────────────────────────────────────────────────

/// Taxa ao mês em % (`NUMERIC(5,2)`): aceita string decimal ou inteiro e
/// recusa float (mesma fronteira do `Money` — f64 perde precisão). Na
/// resposta sai SEM zeros à direita ("3", "2.5"): taxa não é dinheiro.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Taxa(Decimal);

impl Taxa {
    pub fn new(valor: Decimal) -> Self {
        Taxa(valor)
    }

    pub fn valor(&self) -> Decimal {
        self.0
    }
}

impl fmt::Display for Taxa {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0.normalize().to_string())
    }
}

impl Serialize for Taxa {
    fn serialize<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&self.to_string())
    }
}

impl<'de> Deserialize<'de> for Taxa {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        struct TaxaVisitor;

        impl<'de> Visitor<'de> for TaxaVisitor {
            type Value = Taxa;

            fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                f.write_str("string decimal, ex: \"3\" ou \"2.5\". Float é recusado.")
            }

            fn visit_str<E: serde::de::Error>(self, v: &str) -> Result<Taxa, E> {
                Decimal::from_str(v.trim())
                    .map(Taxa)
                    .map_err(|_| E::invalid_value(Unexpected::Str(v), &self))
            }

            // Inteiro é seguro converter: não há perda de precisão.
            fn visit_i64<E: serde::de::Error>(self, v: i64) -> Result<Taxa, E> {
                Decimal::from_i64(v).map(Taxa).ok_or_else(|| {
                    E::invalid_value(Unexpected::Signed(v), &self)
                })
            }

            fn visit_u64<E: serde::de::Error>(self, v: u64) -> Result<Taxa, E> {
                Decimal::from_u64(v).map(Taxa).ok_or_else(|| {
                    E::invalid_value(Unexpected::Unsigned(v), &self)
                })
            }

            // Float: recusado por perder precisão — dinheiro (e taxa que
            // multiplica dinheiro) nunca passa por f64.
            fn visit_f64<E: serde::de::Error>(self, v: f64) -> Result<Taxa, E> {
                Err(E::invalid_value(
                    Unexpected::Float(v),
                    &"string decimal — float é recusado por perder precisão",
                ))
            }
        }

        d.deserialize_any(TaxaVisitor)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn taxa() -> Decimal {
        Decimal::new(3, 0) // 3%
    }

    fn dec(texto: &str) -> Decimal {
        Decimal::from_str(texto).unwrap()
    }

    // "2026-09-15 12:00:00" → instante UTC
    fn instante(data: &str) -> Option<DateTime<Utc>> {
        chrono::NaiveDateTime::parse_from_str(data, "%Y-%m-%d %H:%M:%S")
            .ok()
            .map(|d| d.and_utc())
    }

    fn mov(
        tipo: &str,
        membro: Uuid,
        valor: &str,
        status: &str,
        competencia: Option<Mes>,
        confirmado_em: Option<DateTime<Utc>>,
    ) -> Movimento {
        Movimento {
            tipo: tipo.to_string(),
            membro,
            valor: dec(valor),
            competencia,
            status: status.to_string(),
            confirmado_em,
        }
    }

    fn antes_do_mes() -> Option<DateTime<Utc>> {
        instante("2026-09-15 12:00:00")
    }

    #[test]
    fn aporte_so_conta_depois_que_o_dono_confirma() {
        let ana = Uuid::new_v4();
        let proposto = vec![mov(APORTE, ana, "600.00", PROPOSTO, None, None)];

        assert_eq!(arrecadado(&proposto), dec("0.00"));
        assert_eq!(arrecadado_pendente(&proposto), dec("600.00"));
        assert_eq!(disponivel_aporte(dec("1000.00"), &proposto), dec("400.00"));

        let confirmado = vec![mov(APORTE, ana, "600.00", CONFIRMADO, None, antes_do_mes())];
        assert_eq!(arrecadado(&confirmado), dec("600.00"));
        assert_eq!(arrecadado_pendente(&confirmado), dec("0.00"));
        assert_eq!(disponivel_aporte(dec("1000.00"), &confirmado), dec("400.00"));
        assert_eq!(progresso(arrecadado(&confirmado), dec("1000.00")), 0.6);

        // Pedido alcançado: disponível fecha em zero, mesmo sem pendentes.
        let cheio = vec![mov(APORTE, ana, "1000.00", CONFIRMADO, None, antes_do_mes())];
        assert_eq!(disponivel_aporte(dec("1000.00"), &cheio), dec("0.00"));
        // E o progresso nunca passa de 1.
        assert_eq!(progresso(arrecadado(&cheio), dec("1000.00")), 1.0);
    }

    #[test]
    fn rendimento_comeca_no_mes_seguinte_ao_aporte_confirmado() {
        let ana = Uuid::new_v4();
        let mes = Mes::novo(2026, 10).unwrap();
        let mes_que_vem = mes.proximo();

        // Aporte confirmado DENTRO do mês: não vira base deste mês.
        let dentro = vec![mov(
            APORTE,
            ana,
            "600.00",
            CONFIRMADO,
            None,
            instante("2026-10-23 12:00:00"),
        )];
        assert_eq!(devido_mes(&dentro, ana, mes, taxa()), dec("0.00"));
        assert_eq!(devido_mes(&dentro, ana, mes_que_vem, taxa()), dec("18.00"));

        // Fronteira exata: confirmado na virada do dia 1, 00:00 UTC.
        let na_virada = vec![mov(
            APORTE,
            ana,
            "600.00",
            CONFIRMADO,
            None,
            instante("2026-10-01 00:00:00"),
        )];
        assert_eq!(base_do_mes(&na_virada, ana, mes), dec("0.00"));
        let um_segundo_antes = vec![mov(
            APORTE,
            ana,
            "600.00",
            CONFIRMADO,
            None,
            instante("2026-09-30 23:59:59"),
        )];
        assert_eq!(base_do_mes(&um_segundo_antes, ana, mes), dec("600.00"));
    }

    #[test]
    fn devolucao_parcial_nao_reduz_a_base_e_a_total_zera() {
        let ana = Uuid::new_v4();
        let mes = Mes::novo(2026, 10).unwrap();
        let mes_que_vem = mes.proximo();
        // Aporte confirmado ANTES do mês; devolução confirmada DENTRO dele —
        // igual ao CaixinhaModeloTest: devolver em outubro não reduz o juro
        // DE outubro, só a base do mês seguinte.
        let aporte = mov(APORTE, ana, "600.00", CONFIRMADO, None, antes_do_mes());
        let devolucao = |valor: &str| {
            mov(DEVOLUCAO, ana, valor, CONFIRMADO, None, instante("2026-10-05 12:00:00"))
        };

        // Parcial: saldo cai, juro NÃO.
        let parcial = vec![aporte.clone(), devolucao("200.00")];
        assert_eq!(saldo_em_aberto(&parcial, ana), dec("400.00"));
        assert_eq!(devido_mes(&parcial, ana, mes, taxa()), dec("18.00"));
        assert_eq!(devido_mes(&parcial, ana, mes_que_vem, taxa()), dec("18.00"));

        // Total: saldo zera e o juro do mês SEGUINTE zera junto.
        let total = vec![aporte, devolucao("600.00")];
        assert_eq!(saldo_em_aberto(&total, ana), dec("0.00"));
        assert_eq!(devido_mes(&total, ana, mes, taxa()), dec("18.00"));
        assert_eq!(devido_mes(&total, ana, mes_que_vem, taxa()), dec("0.00"));
    }

    #[test]
    fn saldo_em_aberto_nunca_fica_negativo() {
        let ana = Uuid::new_v4();
        let exagero = vec![
            mov(APORTE, ana, "600.00", CONFIRMADO, None, antes_do_mes()),
            mov(DEVOLUCAO, ana, "700.00", CONFIRMADO, None, antes_do_mes()),
        ];
        assert_eq!(saldo_em_aberto(&exagero, ana), dec("0.00"));
    }

    #[test]
    fn rendimento_arredonda_half_up() {
        // Os mesmos casos do CaixinhaRegrasTest.
        assert_eq!(rendimento_mensal(dec("1000.00"), taxa()), dec("30.00"));
        assert_eq!(rendimento_mensal(dec("300"), dec("2.5")), dec("7.50"));
        assert_eq!(rendimento_mensal(dec("600.00"), taxa()), dec("18.00"));
        // Midpoint exato vai PARA CIMA (HALF_UP, nunca banker's rounding):
        // 5 × 2,5% = 0.125 → 0.13.
        assert_eq!(rendimento_mensal(dec("5.00"), dec("2.5")), dec("0.13"));
        // Base ou taxa sem sentido → zero (não deve, não empresta).
        assert_eq!(rendimento_mensal(Decimal::ZERO, taxa()), dec("0"));
        assert_eq!(rendimento_mensal(dec("600.00"), Decimal::ZERO), dec("0"));
    }

    #[test]
    fn falta_do_mes_desconta_proposta_pendente() {
        let ana = Uuid::new_v4();
        let mes = Mes::novo(2026, 10).unwrap();
        let aporte = mov(APORTE, ana, "600.00", CONFIRMADO, None, antes_do_mes());

        // Nada proposto: falta é o devido inteiro e o mês está livre.
        let sem_proposta = vec![aporte.clone()];
        assert_eq!(falta_mes(&sem_proposta, ana, mes, taxa()), dec("18.00"));
        assert_eq!(
            rendimento_disponivel(&sem_proposta, ana, mes, taxa()),
            dec("18.00")
        );
        assert!(!rendimento_pendente(&sem_proposta, ana, mes));

        // Rendimento proposto reserva o mês inteiro.
        let com_proposta = vec![
            aporte.clone(),
            mov(RENDIMENTO, ana, "18.00", PROPOSTO, Some(mes), None),
        ];
        assert_eq!(falta_mes(&com_proposta, ana, mes, taxa()), dec("18.00"));
        assert_eq!(
            rendimento_disponivel(&com_proposta, ana, mes, taxa()),
            dec("0.00")
        );
        assert!(rendimento_pendente(&com_proposta, ana, mes));
    }

    #[test]
    fn pagamento_so_conta_depois_que_o_membro_confirma() {
        let ana = Uuid::new_v4();
        let mes = Mes::novo(2026, 10).unwrap();
        let base = vec![mov(APORTE, ana, "600.00", CONFIRMADO, None, antes_do_mes())];

        let proposto = vec![
            base[0].clone(),
            mov(RENDIMENTO, ana, "18.00", PROPOSTO, Some(mes), None),
        ];
        assert_eq!(pago_mes(&proposto, ana, mes), dec("0.00"));
        assert_eq!(falta_mes(&proposto, ana, mes, taxa()), dec("18.00"));

        let confirmado = vec![
            base[0].clone(),
            mov(
                RENDIMENTO,
                ana,
                "18.00",
                CONFIRMADO,
                Some(mes),
                instante("2026-10-20 12:00:00"),
            ),
        ];
        assert_eq!(pago_mes(&confirmado, ana, mes), dec("18.00"));
        assert_eq!(falta_mes(&confirmado, ana, mes, taxa()), dec("0.00"));

        // Mês diferente não soma: competência é o mês do rendimento.
        let outro_mes = vec![
            base[0].clone(),
            mov(
                RENDIMENTO,
                ana,
                "18.00",
                CONFIRMADO,
                Some(mes.proximo()),
                instante("2026-10-20 12:00:00"),
            ),
        ];
        assert_eq!(pago_mes(&outro_mes, ana, mes), dec("0.00"));
    }

    #[test]
    fn recusado_e_cancelado_nao_entram_nas_contas() {
        let ana = Uuid::new_v4();
        let mes = Mes::novo(2026, 10).unwrap();
        let movs = vec![
            mov(APORTE, ana, "100.00", RECUSADO, None, None),
            mov(APORTE, ana, "50.00", CANCELADO, None, None),
            mov(APORTE, ana, "600.00", CONFIRMADO, None, antes_do_mes()),
            mov(
                RENDIMENTO,
                ana,
                "18.00",
                RECUSADO,
                Some(mes),
                None,
            ),
        ];
        assert_eq!(arrecadado(&movs), dec("600.00"));
        assert_eq!(arrecadado_pendente(&movs), dec("0.00"));
        assert_eq!(pago_mes(&movs, ana, mes), dec("0.00"));
        assert!(!rendimento_pendente(&movs, ana, mes));
    }

    #[test]
    fn disponivel_devolucao_desconta_propostas() {
        let ana = Uuid::new_v4();
        let movs = vec![
            mov(APORTE, ana, "600.00", CONFIRMADO, None, antes_do_mes()),
            mov(DEVOLUCAO, ana, "200.00", PROPOSTO, None, None),
        ];
        assert_eq!(saldo_em_aberto(&movs, ana), dec("600.00"));
        assert_eq!(disponivel_devolucao(&movs, ana), dec("400.00"));

        let sem_proposta = vec![mov(APORTE, ana, "600.00", CONFIRMADO, None, antes_do_mes())];
        assert_eq!(disponivel_devolucao(&sem_proposta, ana), dec("600.00"));

        let sem_saldo: Vec<Movimento> = vec![];
        assert_eq!(disponivel_devolucao(&sem_saldo, ana), dec("0.00"));
    }

    #[test]
    fn totais_do_mes_somam_os_membros() {
        let ana = Uuid::new_v4();
        let bruno = Uuid::new_v4();
        let mes = Mes::novo(2026, 10).unwrap();
        let movs = vec![
            mov(APORTE, ana, "600.00", CONFIRMADO, None, antes_do_mes()),
            mov(APORTE, bruno, "400.00", CONFIRMADO, None, antes_do_mes()),
            mov(
                RENDIMENTO,
                ana,
                "18.00",
                CONFIRMADO,
                Some(mes),
                instante("2026-10-20 12:00:00"),
            ),
        ];
        let (devido, pago, falta) = totais_mes(&movs, &[ana, bruno], mes, taxa());
        assert_eq!(devido, dec("30.00")); // 18 + 12
        assert_eq!(pago, dec("18.00"));
        assert_eq!(falta, dec("12.00"));

        // Tudo pago: falta fecha em zero, nunca negativo.
        let pago_tudo = vec![
            mov(APORTE, ana, "600.00", CONFIRMADO, None, antes_do_mes()),
            mov(
                RENDIMENTO,
                ana,
                "18.00",
                CONFIRMADO,
                Some(mes),
                instante("2026-10-20 12:00:00"),
            ),
        ];
        let (_, _, falta) = totais_mes(&pago_tudo, &[ana], mes, taxa());
        assert_eq!(falta, dec("0.00"));
    }

    #[test]
    fn progresso_fica_entre_zero_e_um() {
        assert_eq!(progresso(dec("50.00"), dec("100.00")), 0.5);
        assert_eq!(progresso(dec("150.00"), dec("100.00")), 1.0);
        assert_eq!(progresso(dec("50.00"), Decimal::ZERO), 0.0);
    }

    #[test]
    fn media_de_estrelas_tem_uma_casa_e_virgula() {
        assert_eq!(media_estrelas(&[]), None);
        assert_eq!(media_estrelas(&[4]), Some("4,0".to_string()));
        assert_eq!(media_estrelas(&[4, 5]), Some("4,5".to_string()));
        // 13/3 = 4,333… → 4,3
        assert_eq!(media_estrelas(&[4, 4, 5]), Some("4,3".to_string()));
        // Midpoint: 7/2 = 3,5 exato
        assert_eq!(media_estrelas(&[3, 4]), Some("3,5".to_string()));
        // O mesmo formato sai do AVG do SQL (listagens usam a média pronta).
        assert_eq!(media_texto(Some(dec("4.5000000000000000"))), Some("4,5".to_string()));
        assert_eq!(media_texto(Some(dec("4"))), Some("4,0".to_string()));
        assert_eq!(media_texto(None), None);
    }

    #[test]
    fn chave_gera_no_formato_do_contrato() {
        // ^KAV-[0-9A-F]{12}$ — sem regex no crate, o formato é conferido na mão.
        for _ in 0..200 {
            let chave = gerar_chave();
            assert_eq!(chave.len(), 16, "chave {chave} deveria ter 16 caracteres");
            assert!(chave.starts_with("KAV-"), "chave {chave} sem prefixo KAV-");
            for c in chave[4..].chars() {
                assert!(
                    c.is_ascii_digit() || ('A'..='F').contains(&c),
                    "chave {chave} tem caractere fora do hex maiúsculo: {c}"
                );
            }
        }
    }

    #[test]
    fn competencia_limitada_ao_mes_seguinte() {
        let atual = Mes::novo(2026, 12).unwrap();
        assert!(atual.competencia_permitida(Mes::novo(2025, 1).unwrap()));
        assert!(atual.competencia_permitida(Mes::novo(2026, 12).unwrap()));
        assert!(atual.competencia_permitida(Mes::novo(2027, 1).unwrap()));
        assert!(!atual.competencia_permitida(Mes::novo(2027, 2).unwrap()));
    }

    #[test]
    fn mes_parse_valida_o_formato() {
        assert_eq!(Mes::parse("2026-10"), Some(Mes { ano: 2026, mes: 10 }));
        assert_eq!(Mes::novo(2026, 12).unwrap().proximo(), Mes { ano: 2027, mes: 1 });

        for invalido in ["2026-1", "2026-13", "2026-00", "26-10", "2026/10", "2026-10-05", ""] {
            assert_eq!(Mes::parse(invalido), None, "{invalido} não deveria parsear");
        }

        let mes = Mes::parse("2026-10").unwrap();
        assert_eq!(mes.texto(), "2026-10");
        assert_eq!(mes.inicio_utc().to_rfc3339(), "2026-10-01T00:00:00+00:00");
    }

    #[test]
    fn taxa_serializa_sem_zeros_a_direita() {
        assert_eq!(Taxa::new(dec("3.00")).to_string(), "3");
        assert_eq!(Taxa::new(dec("2.50")).to_string(), "2.5");
        assert_eq!(Taxa::new(dec("12.34")).to_string(), "12.34");
        assert_eq!(
            serde_json::to_string(&Taxa::new(dec("3.00"))).unwrap(),
            "\"3\""
        );
        assert_eq!(
            serde_json::to_string(&Taxa::new(dec("2.5"))).unwrap(),
            "\"2.5\""
        );
    }

    #[test]
    fn taxa_recusa_float_e_aceita_string_e_inteiro() {
        let taxa: Taxa = serde_json::from_str("\"3\"").unwrap();
        assert_eq!(taxa.valor(), dec("3"));
        let taxa: Taxa = serde_json::from_str("3").unwrap();
        assert_eq!(taxa.valor(), dec("3"));
        assert!(serde_json::from_str::<Taxa>("2.5").is_err());
        assert!(serde_json::from_str::<Taxa>("\"2,5\"").is_err());
        assert!(serde_json::from_str::<Taxa>("\"abc\"").is_err());
    }

    #[test]
    fn reais_ptbr_usa_virgula() {
        assert_eq!(reais_ptbr(dec("600.00")), "600,00");
        assert_eq!(reais_ptbr(dec("100")), "100,00");
        assert_eq!(reais_ptbr(Decimal::ZERO), "0,00");
    }
}
