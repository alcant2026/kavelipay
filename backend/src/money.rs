//! Dinheiro na fronteira. `f64` é recusado na desserialização, não apenas
//! desaconselhado: um cliente que mande `10.50` (JSON number) recebe 422 em vez
//! de ter o valor convertido por um ponto flutuante que já perdeu precisão.
//!
//! Aceita string (`"10.50"`), inteiro, ou nada. O app Android deve sempre
//! mandar string — é o formato que `rust_decimal` serializa.

use rust_decimal::prelude::{FromPrimitive, FromStr};
use rust_decimal::Decimal;
use serde::de::{Deserializer, Unexpected, Visitor};
use serde::{Deserialize, Serialize, Serializer};
use std::fmt;

/// Valor monetário exato. Só existe para tornar a restrição do tipo explícita:
/// não há `From<f64>` e o deserializer rejeita float.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct Money(Decimal);

impl Money {
    pub fn new(valor: Decimal) -> Self {
        Money(valor)
    }

    /// Valor > 0. Transação com valor zero ou negativo não faz sentido no
    /// ledger — o sinal vem do tipo da transação (entrada vs despesa).
    pub fn is_positive(&self) -> bool {
        self.0 > Decimal::ZERO
    }

    pub fn amount(&self) -> Decimal {
        self.0
    }
}

impl fmt::Display for Money {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        // Sempre 2 casas na exibição: R$ 10,50. A escala 4 do banco serve para
        // valores que não são moeda (juros, cotação); o usuário vê real.
        write!(f, "{:.2}", self.0)
    }
}

impl Serialize for Money {
    fn serialize<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        // String, nunca float. Usa as mesmas 2 casas do `Display` para que
        // saldo e extrato apareçam iguais na tela: `Decimal::to_string()`
        // devolveria "700" onde o app precisa ler "700.00".
        s.serialize_str(&format!("{:.2}", self.0))
    }
}

impl<'de> Deserialize<'de> for Money {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        struct MoneyVisitor;

        impl<'de> Visitor<'de> for MoneyVisitor {
            type Value = Money;

            fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
                f.write_str("string decimal, ex: \"10.50\". Float é recusado.")
            }

            fn visit_str<E: serde::de::Error>(self, v: &str) -> Result<Money, E> {
                let d = Decimal::from_str(v)
                    .map_err(|_| E::invalid_value(Unexpected::Str(v), &self))?;
                // O banco é NUMERIC(19,4): mais de 4 casas é ARREDONDADO no
                // INSERT, e o arredondamento quebra a soma validada por
                // trigger do split (500 no commit em vez de erro claro aqui).
                if d.scale() > 4 {
                    return Err(E::invalid_value(
                        Unexpected::Str(v),
                        &"string decimal com no máximo 4 casas decimais, ex: \"10.50\"",
                    ));
                }
                Ok(Money(d))
            }

            // Inteiro é seguro converter: não há perda de precisão.
            fn visit_i64<E: serde::de::Error>(self, v: i64) -> Result<Money, E> {
                Decimal::from_i64(v).map(Money).ok_or_else(|| {
                    E::invalid_value(Unexpected::Signed(v), &self)
                })
            }

            fn visit_u64<E: serde::de::Error>(self, v: u64) -> Result<Money, E> {
                Decimal::from_u64(v).map(Money).ok_or_else(|| {
                    E::invalid_value(Unexpected::Unsigned(v), &self)
                })
            }

            // Ponto explícito: recusar float com mensagem que diz por quê.
            fn visit_f64<E: serde::de::Error>(self, v: f64) -> Result<Money, E> {
                Err(E::invalid_value(
                    Unexpected::Float(v),
                    &"string decimal — float é recusado por perder precisão",
                ))
            }
        }

        d.deserialize_any(MoneyVisitor)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn json_de<T: for<'de> Deserialize<'de>>(s: &str) -> Result<T, serde_json::Error> {
        serde_json::from_str(s)
    }

    #[test]
    fn string_decimal_e_aceito() {
        let m: Money = json_de("\"10.50\"").unwrap();
        assert_eq!(m.amount(), Decimal::new(1050, 2));
        assert!(m.is_positive());
    }

    #[test]
    fn quatro_casas_e_aceito() {
        let m: Money = json_de("\"0.0001\"").unwrap();
        assert_eq!(m.amount(), Decimal::new(1, 4));
    }

    #[test]
    fn mais_de_quatro_casas_e_recusado() {
        assert!(json_de::<Money>("\"0.00005\"").is_err());
        assert!(json_de::<Money>("\"10.12345\"").is_err());
    }

    #[test]
    fn float_json_e_recusado() {
        // Este é o teste que protege a regra: se alguém aceitar f64, ele falha.
        let r: Result<Money, _> = json_de("10.50");
        assert!(r.is_err(), "float JSON não pode virar Money");
    }

    #[test]
    fn float_com_exponente_também_e_recusado() {
        let r: Result<Money, _> = json_de("1.05e2");
        assert!(r.is_err());
    }

    #[test]
    fn inteiro_e_aceito() {
        let m: Money = json_de("100").unwrap();
        assert_eq!(m.amount(), Decimal::from(100));
    }

    #[test]
    fn string_invalida_e_recusada() {
        assert!(json_de::<Money>("\"dez reais\"").is_err());
        assert!(json_de::<Money>("\"\"empty\"").is_err());
        assert!(json_de::<Money>("\"10,50\"").is_err(), "vírgula é separador decimal pt-BR, inválido em JSON");
    }

    #[test]
    fn negativo_e_aceito_na_fronteira() {
        // O valor negativo é rejeitado na regra de negócio (is_positive), não
        // na desserialização: às vezes o sinal chega antes do tipo da operação.
        let m: Money = json_de("\"-50.00\"").unwrap();
        assert!(!m.is_positive());
    }

    #[test]
    fn serializa_como_string() {
        let m = Money::new(Decimal::new(1050, 2));
        // Mantém as 2 casas: o app recebe "10.50", não "10.5".
        assert_eq!(serde_json::to_string(&m).unwrap(), "\"10.50\"");
    }

    #[test]
    fn valor_inteiro_serializa_com_duas_casas() {
        // Sem isso a API devolveria "700" e o saldo do extrato apareceria
        // diferente do saldo da carteira na tela.
        assert_eq!(
            serde_json::to_string(&Money::new(Decimal::from(700))).unwrap(),
            "\"700.00\""
        );
        assert_eq!(
            serde_json::to_string(&Money::new(Decimal::ZERO)).unwrap(),
            "\"0.00\""
        );
    }

    #[test]
    fn round_trip_preserva_o_valor() {
        let original: Money = json_de("\"1234567.89\"").unwrap();
        let texto = serde_json::to_string(&original).unwrap();
        let de_volta: Money = json_de(&texto).unwrap();
        assert_eq!(original, de_volta);
    }

    #[test]
    fn display_mostra_duas_casas() {
        assert_eq!(Money::new(Decimal::new(1050, 2)).to_string(), "10.50");
        assert_eq!(Money::new(Decimal::new(10000, 2)).to_string(), "100.00");
        assert_eq!(Money::new(Decimal::new(10, 1)).to_string(), "1.00");
        assert_eq!(Money::new(Decimal::ZERO).to_string(), "0.00");
    }
}