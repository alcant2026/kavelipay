//! Cotação de mercado — sugestão, nunca verdade.
//!
//! O usuário pediu: o app sugere preço real da B3 e a data do próximo
//! dividendo, e a pessoa pode mudar ou limpar antes de salvar. As regras de
//! ouro da feature:
//!
//! 1. **Fontes verificadas ao vivo** (2026-10-12): ação → brapi.dev (grátis,
//!    sem chave); FII → Yahoo Finance com sufixo `.SA` (o brapi dá 401 em
//!    fundo); dividendos → só o brapi (tem `paymentDate`, a data que o
//!    dinheiro cai — Yahoo só tem data-ex, que ia enganar a pessoa).
//!    Renda fixa não tem fonte viva (a API do Tesouro Direto morreu:
//!    DNS nem resolve) — fica manual.
//! 2. **Falha é silêncio.** Provedor fora, JSON estranho, rede morta: tudo
//!    vira `None`, o handler responde 200 com null e o app não preenche.
//!    Sugestão é conforto; nunca pode ser a razão de um 500.
//! 3. **Número vira `Decimal` pelo TEXTO**, nunca por aritmética de `f64`:
//!    o JSON traz o número, `serde_json` devolve a representação curta
//!    ("9.45"), `Decimal::from_str` é exato, e o `round_dp(2)` só limpa a
//!    sujeira binária do Yahoo ("9.449999809265137" → 9.45).
//! 4. **Nada de chave no app.** Assim como a IA, o backend faz as chamadas;
//!    estas duas fontes são gratuitas e anônimas (verificado ao vivo).

use chrono::{DateTime, FixedOffset, NaiveDate};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

use crate::money::Money;

/// brapi.dev — grátis sem chave para ação e FIIs pagos; cotação e dividendos.
pub const BRAPI_BASE_PADRAO: &str = "https://brapi.dev";

/// query1.finance.yahoo.com — a mesma fonte que o brapi consome por trás.
/// Usada para FII (brapi responde 401 em fundo imobiliário sem token).
pub const YAHOO_BASE_PADRAO: &str = "https://query1.finance.yahoo.com";

/// Sugestão de preço para uma tela. `preco: None` = não sugira nada.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SugestaoPreco {
    /// String com 2 casas ("56.00") — dinheiro na API é sempre string.
    pub preco: Option<Money>,
    /// Quem respondeu: "brapi" | "yahoo". `None` junto de `preco: None`.
    pub fonte: Option<String>,
    /// Momento da cotação segundo o provedor (RFC3339), se veio.
    pub as_of: Option<String>,
}

impl SugestaoPreco {
    pub fn vazia() -> Self {
        Self { preco: None, fonte: None, as_of: None }
    }
}

/// Cliente das fontes de cotação. Um `reqwest::Client` só: reusa o pool de
/// conexões TLS em vez de abrir handshake novo a cada sugestão.
#[derive(Clone)]
pub struct CotacaoClient {
    http: reqwest::Client,
    brapi_base: String,
    yahoo_base: String,
}

impl CotacaoClient {
    pub fn new(brapi_base: String, yahoo_base: String) -> crate::AppResult<Self> {
        let http = reqwest::Client::builder()
            // Sugestão não pode prender a tela: provedor lento = desiste.
            .timeout(std::time::Duration::from_secs(8))
            // O Yahoo responde 429 para cliente sem User-Agent de navegador
            // (verificado ao vivo em 2026-10-13: reqwest sem header levou
            // 429, curl com Mozilla/5.0 passou). É o mesmo dado público que
            // o navegador recebe — só a identificação do cliente muda.
            .user_agent("Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0")
            .build()
            .map_err(|e| crate::AppError::Internal(anyhow::anyhow!("cliente http: {e}")))?;

        Ok(Self {
            http,
            brapi_base: brapi_base.trim_end_matches('/').to_string(),
            yahoo_base: yahoo_base.trim_end_matches('/').to_string(),
        })
    }

    /// Preço de mercado de `symbol`. Ação → brapi; FII → Yahoo `.SA`;
    /// qualquer outro tipo → `None` sem tocar em rede (renda fixa é manual).
    /// Toda falha vira `None`: isso é sugestão, não consulta obrigatória.
    pub async fn preco(&self, symbol: &str, kind: &str) -> SugestaoPreco {
        match kind {
            "STOCK" => self.preco_brapi(symbol).await,
            "FII" => self.preco_yahoo(symbol).await,
            _ => SugestaoPreco::vazia(),
        }
    }

    /// Data do próximo pagamento de dividendos. Só ação, só brapi: o `rate`
    /// do Yahoo é data-ex (quando o direito se extingue), não a data em que
    /// o dinheiro cai — sugerir data-ex como "próximo dividendo" enganaria.
    pub async fn proximo_dividendo(&self, symbol: &str, kind: &str) -> Option<NaiveDate> {
        let hoje = chrono::Local::now().date_naive();
        if kind != "STOCK" {
            return None;
        }

        let url = format!("{}/api/quote/{}?dividends=true", self.brapi_base, symbol);
        let Ok(resposta) = self.http.get(&url).send().await else {
            tracing::warn!(symbol, "brapi inacessível ao buscar dividendos");
            return None;
        };
        if !resposta.status().is_success() {
            tracing::warn!(symbol, status = %resposta.status(), "brapi recusou dividendos");
            return None;
        }
        let Ok(corpo) = resposta.json::<serde_json::Value>().await else {
            return None;
        };
        // `dividendo_brapi` já filtra os futuros e pega o menor.
        dividendo_brapi(&corpo, hoje)
    }

    /// Nome oficial do ativo segundo o mercado. Busca do Yahoo
    /// (`/v1/finance/search`, grátis e sem token) devolve o `longname` na B3
    /// quando o ticker existe. É DADO, não opinião: a IA preencheu "Cyrela"
    /// para CPTS11 (que é o FII Capitania Securities II) com convicção —
    /// nome de mercado não alucina. Renda fixa não pergunta: não é ticker.
    pub async fn nome_oficial(&self, symbol: &str, kind: &str) -> Option<String> {
        if kind != "STOCK" && kind != "FII" {
            return None;
        }

        let url = format!(
            "{}/v1/finance/search?q={}.SA&quotesCount=3",
            self.yahoo_base, symbol
        );
        let resposta = match self.http.get(&url).send().await {
            Ok(r) if r.status().is_success() => r,
            Ok(r) => {
                tracing::warn!(symbol, status = %r.status(), "yahoo recusou busca de nome");
                return None;
            }
            Err(e) => {
                tracing::warn!(symbol, "yahoo inacessível na busca de nome: {e}");
                return None;
            }
        };
        let corpo = resposta.json::<serde_json::Value>().await.ok()?;
        nome_yahoo(&corpo, symbol)
    }

    async fn preco_brapi(&self, symbol: &str) -> SugestaoPreco {
        let url = format!("{}/api/quote/{}", self.brapi_base, symbol);
        let resposta = match self.http.get(&url).send().await {
            Ok(r) if r.status().is_success() => r,
            Ok(r) => {
                tracing::warn!(symbol, status = %r.status(), "brapi recusou cotação");
                return SugestaoPreco::vazia();
            }
            Err(e) => {
                tracing::warn!(symbol, "brapi inacessível: {e}");
                return SugestaoPreco::vazia();
            }
        };
        let Ok(corpo) = resposta.json::<serde_json::Value>().await else {
            return SugestaoPreco::vazia();
        };

        match (preco_brapi(&corpo), momento_brapi(&corpo)) {
            (Some(preco), as_of) => SugestaoPreco {
                preco: Some(Money::new(preco)),
                fonte: Some("brapi".into()),
                as_of,
            },
            _ => SugestaoPreco::vazia(),
        }
    }

    async fn preco_yahoo(&self, symbol: &str) -> SugestaoPreco {
        // Papéis da B3 no Yahoo levam sufixo ".SA" (PETR4 → PETR4.SA).
        let url = format!("{}/v8/finance/chart/{}.SA", self.yahoo_base, symbol);
        let resposta = match self.http.get(&url).send().await {
            Ok(r) if r.status().is_success() => r,
            Ok(r) => {
                tracing::warn!(symbol, status = %r.status(), "yahoo recusou cotação");
                return SugestaoPreco::vazia();
            }
            Err(e) => {
                tracing::warn!(symbol, "yahoo inacessível: {e}");
                return SugestaoPreco::vazia();
            }
        };
        let Ok(corpo) = resposta.json::<serde_json::Value>().await else {
            return SugestaoPreco::vazia();
        };

        match (preco_yahoo(&corpo), momento_yahoo(&corpo)) {
            (Some(preco), as_of) => SugestaoPreco {
                preco: Some(Money::new(preco)),
                fonte: Some("yahoo".into()),
                as_of,
            },
            _ => SugestaoPreco::vazia(),
        }
    }
}

/// `results[0].regularMarketPrice` do brapi. `results: []` = ticker não
/// existe; JSON sem o campo = formato mudou. Nos dois casos, None — não
/// inventa preço.
fn preco_brapi(corpo: &serde_json::Value) -> Option<Decimal> {
    let bruto = corpo
        .pointer("/results/0/regularMarketPrice")?
        .as_number()?
        .to_string();
    Decimal::from_str_exact(&bruto).ok().map(arredondar_centavos)
}

/// `results[0].regularMarketTime` (ISO) do brapi, repassado como está.
fn momento_brapi(corpo: &serde_json::Value) -> Option<String> {
    corpo
        .pointer("/results/0/regularMarketTime")?
        .as_str()
        .map(str::to_string)
}

/// `chart.result[0].meta.regularMarketPrice` do Yahoo. `chart.error` não
/// nulo = ticker desconhecido.
fn preco_yahoo(corpo: &serde_json::Value) -> Option<Decimal> {
    let bruto = corpo
        .pointer("/chart/result/0/meta/regularMarketPrice")?
        .as_number()?
        .to_string();
    Decimal::from_str_exact(&bruto).ok().map(arredondar_centavos)
}

/// `chart.result[0].meta.regularMarketTime` (epoch) → RFC3339.
fn momento_yahoo(corpo: &serde_json::Value) -> Option<String> {
    let epoch = corpo
        .pointer("/chart/result/0/meta/regularMarketTime")?
        .as_i64()?;
    let momento = DateTime::from_timestamp(epoch, 0)?;
    Some(momento.to_rfc3339())
}

/// `quotes[]` da busca do Yahoo: só o match EXATO de `symbol` com sufixo
/// `.SA` vale — a busca traz parecidos de outras corretoras, e preencher o
/// parecido seria o mesmo erro da alucinação com outro sabor. `longname` é
/// o nome oficial; `shortname` é o fallback.
fn nome_yahoo(corpo: &serde_json::Value, symbol: &str) -> Option<String> {
    let esperado = format!("{symbol}.SA");
    let achado = corpo
        .pointer("/quotes")?
        .as_array()?
        .iter()
        .find(|q| q.get("symbol").and_then(|s| s.as_str()) == Some(esperado.as_str()))?;

    let nome = achado
        .get("longname")
        .and_then(|n| n.as_str())
        .or_else(|| achado.get("shortname").and_then(|n| n.as_str()))?
        .trim();

    (!nome.is_empty()).then(|| nome.to_string())
}

/// Dividendo: o primeiro `paymentDate` VERIFICADO ainda por vir. A lista do
/// brapi vem em ordem decrescente e tem entradas antigas com `paymentDate`
/// nulo — por isso varre inteira e pega o menor futuro, em vez de assumir
/// a primeira linha.
fn dividendo_brapi(corpo: &serde_json::Value, hoje: NaiveDate) -> Option<NaiveDate> {
    let entradas = corpo
        .pointer("/results/0/dividendsData/cashDividends")?
        .as_array()?;

    // O brapi grava meia-noite em 03:00Z; converter para -03:00 (sem DST no
    // Brasil desde 2019) antes de cortar a data evita um dia errado quando o
    // provedor mandar outro horário. `west_opt` só falha com offset absurdo;
    // 3h para oeste é fixo e sempre válido.
    let menos_tres = FixedOffset::west_opt(3 * 3600).expect("offset -03:00 é válido");

    entradas
        .iter()
        .filter(|e| e.get("verified").and_then(|v| v.as_bool()) == Some(true))
        .filter_map(|e| e.get("paymentDate")?.as_str())
        .filter_map(|iso| DateTime::parse_from_rfc3339(iso).ok())
        .map(|dt| dt.with_timezone(&menos_tres).date_naive())
        .filter(|d| *d >= hoje)
        .min()
}

/// Cotação da B3 tem tick de R$ 0,01. O `round_dp(2)` só limpa a sujeira
/// binária que o Yahoo às vezes devolve ("9.449999809265137") — o parse já
/// foi exato pelo texto, aqui não há aritmética de float.
fn arredondar_centavos(d: Decimal) -> Decimal {
    d.round_dp(2)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn brapi_com_preco(json: &str) -> serde_json::Value {
        serde_json::from_str(json).unwrap()
    }

    #[test]
    fn preco_brapi_le_o_preco_como_decimal_exato() {
        // "56" vem como inteiro no JSON; "9.45" com decimal. Ambos viram
        // Money "56.00"/"9.45" — string com 2 casas, nunca número JSON.
        let c = brapi_com_preco(r#"{"results":[{"regularMarketPrice":56}]}"#);
        assert_eq!(preco_brapi(&c), Some(Decimal::new(56, 0)));

        let c = brapi_com_preco(r#"{"results":[{"regularMarketPrice":67.9}]}"#);
        assert_eq!(preco_brapi(&c), Some(Decimal::new(679, 1)));
    }

    #[test]
    fn preco_brapi_vazio_ou_malformado_e_none() {
        // results vazio = ticker não existe. Campo ausente = formato mudou.
        // Nos dois casos: não inventa preço, não panica.
        assert_eq!(preco_brapi(&brapi_com_preco(r#"{"results":[]}"#)), None);
        assert_eq!(preco_brapi(&brapi_com_preco(r#"{"results":[{}]}"#)), None);
        assert_eq!(preco_brapi(&brapi_com_preco(r#"{"error":"x"}"#)), None);
    }

    #[test]
    fn preco_yahoo_limpa_sujeira_binaria_para_dois_centavos() {
        // O Yahoo devolve artefato de float de vez em quando. O parse é
        // exato pelo texto e o arredondamento limpa — sem passar por f64.
        let c = brapi_com_preco(
            r#"{"chart":{"result":[{"meta":{"regularMarketPrice":9.449999809265137}}]}}"#,
        );
        assert_eq!(preco_yahoo(&c), Some(Decimal::new(945, 2)));
    }

    #[test]
    fn preco_yahoo_erro_do_grafico_e_none() {
        let c = brapi_com_preco(r#"{"chart":{"result":null,"error":{"code":"Not Found"}}}"#);
        assert_eq!(preco_yahoo(&c), None);
    }

    #[test]
    fn nome_yahoo_so_aceita_match_exato_com_sufixo_sa() {
        // A busca traz parecidos (outro exchange, contrato futuro). Só o
        // symbol exato "<TICKER>.SA" vale — preencher o parecido seria o
        // mesmo erro da alucinação da IA com outro sabor.
        let c = brapi_com_preco(r#"{"quotes":[
            {"symbol":"CPTS11.SA","longname":"Capitania Securities II FII"},
            {"symbol":"CPTS11.SA.FUT","longname":"Contrato Futuro"}
        ]}"#);
        assert_eq!(
            nome_yahoo(&c, "CPTS11"),
            Some("Capitania Securities II FII".into())
        );
        assert_eq!(nome_yahoo(&c, "OUTR11"), None);
    }

    #[test]
    fn nome_yahoo_fica_no_shortname_e_ignora_vazio() {
        // Sem longname, o shortname serve de fallback. Nome que trimar vazio
        // é nome nenhum: não preenche o formulário com espaço.
        let c = brapi_com_preco(r#"{"quotes":[
            {"symbol":"MXRF11.SA","shortname":"FII Maxi Renda"},
            {"symbol":"PETR4.SA","longname":"   "}
        ]}"#);
        assert_eq!(nome_yahoo(&c, "MXRF11"), Some("FII Maxi Renda".into()));
        assert_eq!(nome_yahoo(&c, "PETR4"), None);
    }

    #[test]
    fn dividendo_pega_o_primeiro_futuro_verificado() {
        // Lista decrescente com entrada passada, entrada sem data e entrada
        // não verificada no meio: o certo é o menor futuro verificado.
        let c = brapi_com_preco(r#"{"results":[{"dividendsData":{"cashDividends":[
            {"paymentDate":"2026-12-21T03:00:00.000Z","verified":true},
            {"paymentDate":null,"verified":true},
            {"paymentDate":"2026-03-21T03:00:00.000Z","verified":true},
            {"paymentDate":"2027-04-20T03:00:00.000Z","verified":false}
        ]}}]}"#);
        let hoje = NaiveDate::from_ymd_opt(2026, 10, 12).unwrap();

        assert_eq!(
            dividendo_brapi(&c, hoje),
            Some(NaiveDate::from_ymd_opt(2026, 12, 21).unwrap())
        );
    }

    #[test]
    fn dividendo_sem_dividendos_futuros_e_none() {
        let hoje = NaiveDate::from_ymd_opt(2026, 10, 12).unwrap();

        // Pagou tudo que tinha pra pagar: não há próximo — não inventa.
        let c = brapi_com_preco(r#"{"results":[{"dividendsData":{"cashDividends":[
            {"paymentDate":"2026-09-21T03:00:00.000Z","verified":true}
        ]}}]}"#);
        assert_eq!(dividendo_brapi(&c, hoje), None);

        // Ativo sem dividendos (ITSA? fundo sem rendimento): nada.
        let c = brapi_com_preco(r#"{"results":[{}]}"#);
        assert_eq!(dividendo_brapi(&c, hoje), None);
    }

    #[test]
    fn money_serializa_sempre_com_duas_casas() {
        let c = brapi_com_preco(r#"{"results":[{"regularMarketPrice":56}]}"#);
        let preco = Money::new(preco_brapi(&c).unwrap());
        assert_eq!(serde_json::to_string(&preco).unwrap(), r#""56.00""#);
    }

    #[tokio::test]
    async fn preco_renda_fixa_nao_toca_em_rede() {
        // Base URLs apontam para porta morta de propósito: se este teste
        // chegasse a tocar em rede, falharia por timeout, não por vazio.
        let cliente = CotacaoClient::new("http://127.0.0.1:1".into(), "http://127.0.0.1:1".into())
            .unwrap();

        let s = cliente.preco("Tesouro Selic 2029", "BOND").await;

        assert_eq!(s, SugestaoPreco::vazia());
    }

    #[tokio::test]
    async fn dividendos_de_renda_fixa_e_fii_nao_tocam_em_rede() {
        // Só STOCK consulta dividendos; FII o brapi responde 401 sem token
        // (verificado ao vivo) — nem adianta perguntar.
        let cliente = CotacaoClient::new("http://127.0.0.1:1".into(), "http://127.0.0.1:1".into())
            .unwrap();

        assert!(cliente.proximo_dividendo("MXRF11", "FII").await.is_none());
        assert!(cliente.proximo_dividendo("Tesouro Selic 2029", "BOND").await.is_none());
    }

    #[tokio::test]
    async fn provedor_morto_e_silencio_nunca_erro() {
        // O contrato da tela: falha de rede não pode explodir em 500 — vira
        // "sem sugestão" e a pessoa digita o preço à mão.
        let cliente = CotacaoClient::new("http://127.0.0.1:1".into(), "http://127.0.0.1:1".into())
            .unwrap();

        let s = cliente.preco("PETR4", "STOCK").await;
        assert_eq!(s, SugestaoPreco::vazia());

        assert!(cliente.proximo_dividendo("PETR4", "STOCK").await.is_none());
    }
}
