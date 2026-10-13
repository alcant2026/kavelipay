//! Pesquisa de ativo por IA (OpenRouter) — prefill do formulário de
//! "Adicionar ativo".
//!
//! A pessoa digita só o ticker; a IA devolve nome e resumo. Duas regras
//! organizam tudo aqui:
//!
//! 1. **A chave é do servidor.** Ela vai no header `Authorization` da chamada
//!    para a OpenRouter e NUNCA aparece no app nem no corpo da requisição —
//!    se ela fosse para o APK, qualquer pessoa descompilaria o app e gastaria
//!    o nosso saldo.
//! 2. **Cada consulta custa dinheiro** (~US$ 0,000003 com o modelo padrão —
//!    barato, mas não zero). Por isso o formato do símbolo é validado ANTES
//!    da chamada (rota devolve 422 sem gastar um centavo de token) e
//!    `found: false` preenche nada: se o ativo não existe, não faz nada.
//!
//! O resumo passa por `sanitizar_resumo`, que espelha o trigger
//! `assert_investment_summary_sem_valor` da migration 0007: o banco recusa
//! resumo com valor monetário, e uma IA livre pode facilmente escrever
//! "ação que vale R$ 35,00". Descartar o resumo (e manter o nome) é muito
//! melhor que deixar o usuário descobrir o erro no 500 ao salvar.

use serde::{Deserialize, Serialize};

use crate::error::{AppError, AppResult};

/// Modelo padrão: US$ 0,03/milhão de tokens de entrada. Cobre a tarefa
/// (identificar ticker da B3 e escrever duas frases) e cabe no orçamento de
/// um app que consulta a cada foco num campo. Configurável por
/// `OPENROUTER_MODEL` — trocar de modelo é mudança de env var, não de código.
pub const MODELO_PADRAO: &str = "mistralai/mistral-nemo";

pub const BASE_URL_PADRAO: &str = "https://openrouter.ai/api/v1";

/// Teto de tokens da resposta. Um `found: false` gasta ~6 tokens; um JSON
/// completo com duas frases, ~50. 200 é folga para o modelo não ser
/// interrompido no meio do JSON.
const MAX_TOKENS: u32 = 200;

/// System prompt enxuto de propósito: cada token dele é pago em TODA consulta.
/// Testado ao vivo com o modelo padrão antes de virar código.
const PROMPT_SISTEMA: &str = concat!(
    "Identifique o ativo do mercado brasileiro (B3) pelo código. ",
    "Responda SOMENTE com JSON no formato ",
    "{\"found\": true|false, \"name\": \"...\", \"summary\": \"...\"}. ",
    "name = nome curto oficial (ex.: \"Petrobras\"). ",
    "summary = 1-2 frases em português descrevendo o ativo, SEM valores ",
    "monetários (nada de R$, preços ou dividendos). ",
    "Se o código não existir na B3 ou você não tiver certeza, responda ",
    "{\"found\": false}.",
);

/// Resultado da pesquisa. `found: false` significa "não preencha nada" —
/// seja porque o ativo não existe, seja porque a IA não teve certeza.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Pesquisa {
    pub found: bool,
    pub name: Option<String>,
    pub summary: Option<String>,
}

impl Pesquisa {
    fn nao_encontrada() -> Self {
        Self { found: false, name: None, summary: None }
    }
}

/// Cliente da OpenRouter. Segura um `reqwest::Client` reutilizado (pool de
/// conexões) — construir um por consulta abriria TLS do zero toda vez.
#[derive(Clone)]
pub struct AiClient {
    http: reqwest::Client,
    api_key: Option<String>,
    modelo: String,
    base_url: String,
}

impl AiClient {
    /// `api_key: None` desliga a feature: `pesquisar` devolve
    /// `AppError::Unavailable` (503) sem tocar em rede nenhuma.
    pub fn new(api_key: Option<String>, modelo: String, base_url: String) -> AppResult<Self> {
        let http = reqwest::Client::builder()
            // Sem timeout o handler fica pendurado para sempre se a OpenRouter
            // não responder — e a pessoa fica olhando o campo vazio.
            .timeout(std::time::Duration::from_secs(20))
            .build()
            .map_err(|e| AppError::Internal(anyhow::anyhow!("cliente http: {e}")))?;

        Ok(Self {
            http,
            api_key: api_key.filter(|k| !k.trim().is_empty()),
            modelo,
            base_url,
        })
    }

    pub fn sem_chave() -> Self {
        Self {
            http: reqwest::Client::new(),
            api_key: None,
            modelo: MODELO_PADRAO.to_string(),
            base_url: BASE_URL_PADRAO.to_string(),
        }
    }

    /// Consulta a IA sobre `symbol`. Símbolo já deve ter passado por
    /// `normalizar_simbolo` — esta função não valida formato, ela GASTA.
    ///
    /// `nome_oficial` (quando o mercado sabe) entra como CONTEXTO: sem
    /// âncora, o modelo adivinha a empresa pelo ticker e já resumiu a
    /// errada com convicção (CPTS11 → "Cyrela"; o certo é o FII Capitania
    /// Securities II). O nome que o app salva vem do mercado; a IA fica
    /// com o resumo — e resume a empresa certa porque sabe quem ela é.
    pub async fn pesquisar(&self, symbol: &str, nome_oficial: Option<&str>) -> AppResult<Pesquisa> {
        let chave = self.api_key.as_deref().ok_or(AppError::Unavailable)?;

        let url = format!("{}/chat/completions", self.base_url.trim_end_matches('/'));
        let resposta = self
            .http
            .post(&url)
            // `bearer_auth` monta "Authorization: Bearer <chave>". A chave vai
            // no header porque é o único lugar que não vaza em log de URL nem
            // chega ao corpo que o modelo lê.
            .bearer_auth(chave)
            .json(&montar_corpo(&self.modelo, symbol, nome_oficial))
            .send()
            .await
            .map_err(|e| AppError::Internal(anyhow::anyhow!("openrouter inacessível: {e}")))?;

        let status = resposta.status();
        if !status.is_success() {
            return Err(AppError::Internal(anyhow::anyhow!("openrouter respondeu {status}")));
        }

        let corpo: serde_json::Value = resposta
            .json()
            .await
            .map_err(|e| AppError::Internal(anyhow::anyhow!("resposta não é JSON: {e}")))?;

        let conteudo = corpo
            .pointer("/choices/0/message/content")
            .and_then(|v| v.as_str())
            .ok_or_else(|| AppError::Internal(anyhow::anyhow!("resposta sem choices[0].message.content")))?;

        interpretar(conteudo)
    }
}

/// Corpo da requisição ao chat completions (formato OpenAI, que a OpenRouter
/// segue). `temperature: 0` — não há criatividade aqui a recompensar, e
/// temperatura alta em tarefa de extração só aumenta o resumo inveniado.
///
/// Com `nome_oficial`, o user message carrega a âncora: o ticker sozinho é
/// ambíuo na mão do modelo ("CPTS11" virou "Cyrela"), o nome oficial não.
fn montar_corpo(modelo: &str, symbol: &str, nome_oficial: Option<&str>) -> serde_json::Value {
    let usuario = match nome_oficial {
        Some(nome) => format!("{symbol} (nome oficial na B3: {nome})"),
        None => symbol.to_string(),
    };
    serde_json::json!({
        "model": modelo,
        "temperature": 0,
        "max_tokens": MAX_TOKENS,
        "messages": [
            { "role": "system", "content": PROMPT_SISTEMA },
            { "role": "user", "content": usuario },
        ],
    })
}

/// Interpreta o conteúdo que o modelo escreveu. Tolerante à cercadeira de
/// código (```` ```json ````) que modelos baratos adoram colocar; intolerante
/// a JSON malformado — nesse caso erro, e o app ignora silenciosamente.
fn interpretar(conteudo: &str) -> AppResult<Pesquisa> {
    let limpo = remover_cerca(conteudo.trim());

    let bruto: serde_json::Value =
        serde_json::from_str(limpo).map_err(|e| {
            AppError::Internal(anyhow::anyhow!("resposta da IA não é JSON válido: {e}"))
        })?;

    let found = bruto.get("found").and_then(|v| v.as_bool()).unwrap_or(false);
    if !found {
        // `found: false` descarta qualquer nome/resumo que tenha vindo junto:
        // dizer "não existe" e mesmo assim devolver um nome é como a IA mente.
        return Ok(Pesquisa::nao_encontrada());
    }

    let name = bruto
        .get("name")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty());

    // `found: true` sem nome preencheria só o resumo — formulário meio
    // preenchido é pior que vazio, a pessoa não sabe o que completar.
    let Some(name) = name else {
        return Ok(Pesquisa::nao_encontrada());
    };

    let summary = bruto
        .get("summary")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .and_then(sanitizar_resumo);

    Ok(Pesquisa { found: true, name: Some(name.to_string()), summary })
}

/// Remove uma eventual cercadeira ```` ```json ... ``` ````. Modelos baratos
/// fazem isso mesmo o prompt pedindo "SOMENTE JSON" — e uma resposta bela
/// que não parseia não serve para nada.
fn remover_cerca(texto: &str) -> &str {
    let sem_abertura = texto.strip_prefix("```json").or_else(|| texto.strip_prefix("```")).unwrap_or(texto);
    sem_abertura.strip_suffix("```").unwrap_or(sem_abertura).trim()
}

/// Descarta o resumo se ele contiver valor monetário — espelho do trigger
/// `assert_investment_summary_sem_valor` (migration 0007). O banco recusa;
/// aqui a gente descarta em silêncio e mantém o nome, que continua útil.
///
/// Percentual, data e ticker são permitidos nos dois lados ("PETR4 caiu 3,2%
/// em 2026-10-05" é informação legítima, não preço).
fn sanitizar_resumo(resumo: &str) -> Option<String> {
    if contem_valor_monetario(resumo) {
        return None;
    }
    Some(resumo.to_string())
}

/// Reproduz as regras do trigger sem depender do crate `regex`:
/// 1. remove percentuais (`3,2%`), datas (`2026-10-05`) e tickers (`PETR4`);
/// 2. no resto, procura `R$` seguido de dígito ou `NN,NN` seguido de
///    "reais"/"por" — o padrão de preço que o trigger rejeita.
fn contem_valor_monetario(texto: &str) -> bool {
    let sem_ruído = remover_percentuais_datas_tickers(texto);

    let chars: Vec<char> = sem_ruído.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        if chars[i] == 'R' && chars.get(i + 1) == Some(&'$') {
            let mut j = i + 2;
            while j < chars.len() && chars[j] == ' ' {
                j += 1;
            }
            if j < chars.len() && chars[j].is_ascii_digit() {
                return true;
            }
        }

        // NN,NN seguido (sem palavra no meio) de "reais" ou "por".
        if chars[i].is_ascii_digit() {
            if let Some(fim_inteiros) = (i..chars.len()).find(|&k| !chars[k].is_ascii_digit()) {
                if chars.get(fim_inteiros) == Some(&',')
                    && chars.get(fim_inteiros + 1).is_some_and(|c| c.is_ascii_digit())
                    && chars.get(fim_inteiros + 2).is_some_and(|c| c.is_ascii_digit())
                {
                    let mut j = fim_inteiros + 3;
                    while j < chars.len() && chars[j] == ' ' {
                        j += 1;
                    }
                    let resto: String = chars[j..].iter().collect();
                    if resto.starts_with("reais") || resto.starts_with("por") {
                        return true;
                    }
                }
            }
        }

        i += 1;
    }

    false
}

/// Remove da string os padrões que o trigger também remove antes de julgar.
fn remover_percentuais_datas_tickers(texto: &str) -> String {
    let chars: Vec<char> = texto.chars().collect();
    let mut saida = String::with_capacity(texto.len());
    let mut i = 0;

    while i < chars.len() {
        // Data YYYY-MM-DD.
        if i + 9 < chars.len()
            && (0..4).all(|k| chars[i + k].is_ascii_digit())
            && chars[i + 4] == '-'
            && chars[i + 7] == '-'
        {
            i += 10;
            continue;
        }

        // Percentual: dígitos, opcionalmente vírgula/ponto e mais dígitos,
        // espaços opcionais e o '%' final.
        if chars[i].is_ascii_digit() {
            let mut j = i;
            while j < chars.len() && chars[j].is_ascii_digit() {
                j += 1;
            }
            if j < chars.len() && (chars[j] == ',' || chars[j] == '.') {
                let mut k = j + 1;
                if k < chars.len() && chars[k].is_ascii_digit() {
                    while k < chars.len() && chars[k].is_ascii_digit() {
                        k += 1;
                    }
                    let mut m = k;
                    while m < chars.len() && chars[m] == ' ' {
                        m += 1;
                    }
                    if m < chars.len() && chars[m] == '%' {
                        i = m + 1;
                        continue;
                    }
                }
            }
            // Percentual sem parte decimal: "5 %".
            let mut m = j;
            while m < chars.len() && chars[m] == ' ' {
                m += 1;
            }
            if m < chars.len() && chars[m] == '%' {
                i = m + 1;
                continue;
            }
        }

        // Ticker: 4 letras maiúsculas + 1 ou 2 dígitos (o regex do trigger é
        // `[A-Z]{4}\d{1,2}` — unitário como AALR11 tem dois dígitos).
        if i + 4 < chars.len()
            && (0..4).all(|k| chars[i + k].is_ascii_uppercase())
            && chars[i + 4].is_ascii_digit()
        {
            let mut fim = i + 5;
            if fim < chars.len() && chars[fim].is_ascii_digit() {
                fim += 1;
            }
            i = fim;
            continue;
        }

        saida.push(chars[i]);
        i += 1;
    }

    saida
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn corpo_requisicao_leva_modelo_simbolo_e_poucos_tokens() {
        let corpo = montar_corpo("modelo/barato", "PETR4", None);

        assert_eq!(corpo["model"], "modelo/barato");
        assert_eq!(corpo["temperature"], 0);
        assert!(corpo["max_tokens"].as_u64().unwrap() <= 200);
        assert_eq!(corpo["messages"][1]["content"], "PETR4");
        assert!(corpo["messages"][0]["content"].as_str().unwrap().contains("JSON"));
    }

    #[test]
    fn interpretar_json_valido_vira_pesquisa() {
        let p = interpretar(r#"{"found": true, "name": "Petrobras", "summary": "Empresa de petróleo e gás."}"#).unwrap();

        assert!(p.found);
        assert_eq!(p.name.as_deref(), Some("Petrobras"));
        assert_eq!(p.summary.as_deref(), Some("Empresa de petróleo e gás."));
    }

    #[test]
    fn interpretar_found_false_descarta_nome_e_resumo() {
        // A IA pode devolver found:false junto de um palpite — descartar tudo
        // é o contrato: não encontrado = não preenche nada.
        let p = interpretar(r#"{"found": false, "name": "PETR4", "summary": "palpite"}"#).unwrap();

        assert!(!p.found);
        assert!(p.name.is_none());
        assert!(p.summary.is_none());
    }

    #[test]
    fn interpretar_found_sem_nome_vira_nao_encontrado() {
        // "Existe" sem nome preencheria só o resumo — formulário meio pronto
        // é pior que vazio: a pessoa não sabe o que falta.
        let p = interpretar(r#"{"found": true, "summary": "alguma coisa"}"#).unwrap();

        assert!(!p.found);
    }

    #[test]
    fn interpretar_aceita_cerca_de_codigo() {
        let p = interpretar("```json\n{\"found\": false}\n```").unwrap();

        assert!(!p.found);
    }

    #[test]
    fn interpretar_json_malformado_erro() {
        // Erro, não panic e não "não encontrado": o handler vira 500, o app
        // ignora, e o log mostra que a resposta veio corrompida.
        assert!(interpretar("desculpe, não sei").is_err());
    }

    #[test]
    fn resumo_com_valor_monetario_e_descartado() {
        // O trigger do banco recusaria estes INSERTs — descartar aqui é a
        // diferença entre "resumo vazio" e "500 ao salvar".
        assert_eq!(sanitizar_resumo("Ação que vale R$ 35,00."), None);
        assert_eq!(sanitizar_resumo("Pagou 35,00 reais por cota."), None);
        assert_eq!(sanitizar_resumo("R$ 35,00 por ação."), None);
    }

    #[test]
    fn resumo_com_percentual_data_e_ticker_passa() {
        // O trigger também permite estes: são informação, não preço.
        let r = "PETR4 caiu 3,2% em 2026-10-05 após anúncio de dividendos.";
        assert_eq!(sanitizar_resumo(r), Some(r.to_string()));
    }

    #[test]
    fn resumo_limpo_passa_intacto() {
        let r = "Maior empresa de petróleo do Brasil, controlada pela União.";
        assert_eq!(sanitizar_resumo(r), Some(r.to_string()));
    }

    #[tokio::test]
    async fn pesquisar_sem_chave_e_indisponivel_sem_rede() {
        // base_url aponta para lugar inválido de propósito: se este teste
        // chegasse a tocar em rede, falharia por timeout, não por Unavailable.
        let cliente = AiClient::new(None, MODELO_PADRAO.into(), "http://127.0.0.1:1".into()).unwrap();

        let erro = cliente.pesquisar("PETR4", None).await.unwrap_err();

        assert!(matches!(erro, AppError::Unavailable));
    }
}
