//! Sugestão de cotação: `POST /investments/quote` (preço) e o campo
//! `next_dividend` do `POST /investments/lookup` (data do próximo pagamento).
//!
//! Um mock único serve OpenRouter, brapi, Yahoo e statusinvest por path —
//! cada teste aponta o `AiClient`/`CotacaoClient` para ele. Os testes provam
//! as três regras da feature: validação antes de rede, silêncio na falha do
//! provedor, e o roteamento por tipo (preço: ação→brapi, FII→Yahoo `.SA`,
//! renda fixa→nada; provento: ação→brapi com statusinvest de reserva,
//! FII→statusinvest).

use std::sync::{Arc, Mutex};

use axum::extract::OriginalUri;
use axum::http::StatusCode;
use axum::{Json, Router};
use kavelipay_backend::ai::AiClient;
use kavelipay_backend::auth::TokenService;
use kavelipay_backend::cotacao::CotacaoClient;
use kavelipay_backend::state::AppState;
use uuid::Uuid;

// ── mock: um servidor, três fontes, roteado por path ──────────────────────────

#[derive(Clone, Default)]
struct MockFontes {
    /// Resposta do `/chat/completions` (IA). `None` = 404.
    ia: Option<serde_json::Value>,
    /// Resposta do `/api/quote/*` (brapi). `None` = 404.
    brapi: Option<serde_json::Value>,
    /// Resposta do `/v8/finance/chart/*` (Yahoo). `None` = 404.
    yahoo: Option<serde_json::Value>,
    /// Resposta do `/v1/finance/search` (busca do Yahoo). `None` = 404.
    yahoo_busca: Option<serde_json::Value>,
    /// Resposta de `/fii/companytickerprovents` e `/acao/companytickerprovents`
    /// (proventos do statusinvest). `None` = 404.
    statusinvest: Option<serde_json::Value>,
    /// Paths GET recebidos, em ordem.
    chamadas: Arc<Mutex<Vec<String>>>,
    /// Corpo recebido no `/chat/completions` — os testes provam o CONTEXTO
    /// enviado à IA (o nome oficial vira âncora do resumo).
    corpo_ia_recebido: Arc<Mutex<Option<serde_json::Value>>>,
}

async fn servir(
    axum::extract::State(m): axum::extract::State<MockFontes>,
    OriginalUri(uri): OriginalUri,
    headers: axum::http::HeaderMap,
    corpo: axum::body::Bytes,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let path = uri.path().to_string();
    // Path + query: o contrato não é só a rota, é também o `ticker=` e o
    // `chartProventsType=` que cada tipo pede. Sem query, registra o path
    // puro (nada de "?" pendurado).
    let completo = match uri.query() {
        Some(q) => format!("{path}?{q}"),
        None => path.clone(),
    };
    m.chamadas.lock().unwrap().push(completo);

    // O Yahoo responde 429 para cliente sem User-Agent de navegador
    // (verificado ao vivo em 2026-10-13: reqwest sem header levou 429 nas
    // rotas de chart e de search; curl com Mozilla/5.0 passou). O mock
    // espelha essa exigência nas rotas do Yahoo.
    let eh_yahoo = path.starts_with("/v8/finance/chart/") || path == "/v1/finance/search";
    if eh_yahoo && headers.get("user-agent").is_none() {
        return Err(StatusCode::TOO_MANY_REQUESTS);
    }

    if path == "/chat/completions" {
        if let Ok(v) = serde_json::from_slice::<serde_json::Value>(&corpo) {
            *m.corpo_ia_recebido.lock().unwrap() = Some(v);
        }
        return m.ia.clone().map(Json).ok_or(StatusCode::NOT_FOUND);
    }
    if path.starts_with("/api/quote/") {
        return m.brapi.clone().map(Json).ok_or(StatusCode::NOT_FOUND);
    }
    if path.starts_with("/v8/finance/chart/") {
        return m.yahoo.clone().map(Json).ok_or(StatusCode::NOT_FOUND);
    }
    if path == "/v1/finance/search" {
        // O sufixo `.SA` no `q=` é parte do contrato (papel da B3 no Yahoo) e
        // os testes querem prová-lo — o path+query já foi registrado acima.
        return m.yahoo_busca.clone().map(Json).ok_or(StatusCode::NOT_FOUND);
    }
    if path.starts_with("/fii/companytickerprovents")
        || path.starts_with("/acao/companytickerprovents")
    {
        return m.statusinvest.clone().map(Json).ok_or(StatusCode::NOT_FOUND);
    }
    Err(StatusCode::NOT_FOUND)
}

/// Sobe o mock com POST /chat/completions e GET para as duas fontes.
async fn subir_mock(ia: serde_json::Value, brapi: serde_json::Value, yahoo: serde_json::Value)
    -> (String, Arc<Mutex<Vec<String>>>) {
    let (base, chamadas, _) = subir_mock_com_busca(ia, brapi, yahoo, None).await;
    (base, chamadas)
}

/// Mesmo mock, com a busca do Yahoo (`/v1/finance/search`) ligada. Devolve
/// também o registro do corpo recebido pela IA.
async fn subir_mock_com_busca(
    ia: serde_json::Value,
    brapi: serde_json::Value,
    yahoo: serde_json::Value,
    yahoo_busca: Option<serde_json::Value>,
) -> (
    String,
    Arc<Mutex<Vec<String>>>,
    Arc<Mutex<Option<serde_json::Value>>>,
) {
    subir_mock_completo(ia, brapi, yahoo, yahoo_busca, None).await
}

/// Mock completo: as três fontes de mercado (brapi, Yahoo, statusinvest)
/// roteadas por path. O statusinvest entra por último porque só a sugestão de
/// provento o consulta.
async fn subir_mock_completo(
    ia: serde_json::Value,
    brapi: serde_json::Value,
    yahoo: serde_json::Value,
    yahoo_busca: Option<serde_json::Value>,
    statusinvest: Option<serde_json::Value>,
) -> (
    String,
    Arc<Mutex<Vec<String>>>,
    Arc<Mutex<Option<serde_json::Value>>>,
) {
    let mock = MockFontes {
        ia: Some(ia),
        brapi: Some(brapi),
        yahoo: Some(yahoo),
        yahoo_busca,
        statusinvest,
        chamadas: Arc::new(Mutex::new(Vec::new())),
        corpo_ia_recebido: Arc::new(Mutex::new(None)),
    };
    let registrador = mock.chamadas.clone();
    let corpo_ia = mock.corpo_ia_recebido.clone();

    // UM fallback para tudo: POST /chat/completions e GET das duas fontes.
    // Rota explícita para /chat/completions aqui sombreava o fallback e
    // ECOAVA o corpo da requisição da IA de volta — o AiClient não achava
    // choices/message/content e o lookup virava 500.
    let app = Router::new().fallback(servir).with_state(mock);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });

    (format!("http://{addr}"), registrador, corpo_ia)
}

// ── app sob teste ─────────────────────────────────────────────────────────────

fn token_de_teste() -> String {
    TokenService::new("segredo-de-teste-com-32-bytes-no-minimo!!", 15, 30)
        .issue(Uuid::new_v4())
        .unwrap()
        .0
}

/// Router real em porta aleatória. Pool lazy: os handlers desta feature não
/// tocam em banco — se tocassem, o teste travaria no connect em vez de
/// passar, e a falha mentiria o motivo.
async fn subir_app_com(ai: AiClient, cotacao: CotacaoClient) -> String {
    let db = sqlx::postgres::PgPoolOptions::new()
        .connect_lazy("postgres://nao-conecta@127.0.0.1:1/nao_existe")
        .unwrap();
    let tokens = TokenService::new("segredo-de-teste-com-32-bytes-no-minimo!!", 15, 30);

    let app = kavelipay_backend::routes::router(AppState { db, tokens, ai, cotacao });
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });

    format!("http://{addr}")
}

/// App com IA e fontes apontando para o mock.
async fn subir_app(ia: serde_json::Value, brapi: serde_json::Value, yahoo: serde_json::Value)
    -> (String, Arc<Mutex<Vec<String>>>) {
    let (base, chamadas, _) = subir_app_com_busca(ia, brapi, yahoo, None).await;
    (base, chamadas)
}

/// App com IA, fontes e busca do Yahoo apontando para o mock. Devolve
/// também o registro do corpo recebido pela IA.
async fn subir_app_com_busca(
    ia: serde_json::Value,
    brapi: serde_json::Value,
    yahoo: serde_json::Value,
    yahoo_busca: Option<serde_json::Value>,
) -> (
    String,
    Arc<Mutex<Vec<String>>>,
    Arc<Mutex<Option<serde_json::Value>>>,
) {
    let (base_mock, chamadas, corpo_ia) =
        subir_mock_com_busca(ia, brapi, yahoo, yahoo_busca).await;
    let ai = AiClient::new(Some("chave-teste".into()), "modelo/teste".into(), base_mock.clone())
        .unwrap();
    let cotacao = CotacaoClient::new(base_mock.clone(), base_mock.clone(), base_mock).unwrap();
    (subir_app_com(ai, cotacao).await, chamadas, corpo_ia)
}

/// App com as fontes de cotação mortas (porta inválida) — o estado "provedor
/// fora do ar" de verdade, sem depender de rede.
async fn subir_app_sem_fontes(ia: serde_json::Value) -> String {
    let (base_mock, _) = subir_mock(ia, serde_json::json!({}), serde_json::json!({})).await;
    let ai = AiClient::new(Some("chave-teste".into()), "modelo/teste".into(), base_mock).unwrap();
    let cotacao = CotacaoClient::new(
        "http://127.0.0.1:1".into(),
        "http://127.0.0.1:1".into(),
        "http://127.0.0.1:1".into(),
    )
    .unwrap();
    subir_app_com(ai, cotacao).await
}

/// App com as fontes apontando para o mock e o statusinvest ligado — o
/// consumidor dele é a sugestão de provento (FII e reserva da ação).
async fn subir_app_com_proventos(
    ia: serde_json::Value,
    brapi: serde_json::Value,
    statusinvest: serde_json::Value,
) -> (String, Arc<Mutex<Vec<String>>>) {
    let (base_mock, chamadas, _) = subir_mock_completo(
        ia,
        brapi,
        serde_json::json!({}),
        None,
        Some(statusinvest),
    )
    .await;
    let ai = AiClient::new(Some("chave-teste".into()), "modelo/teste".into(), base_mock.clone())
        .unwrap();
    let cotacao = CotacaoClient::new(base_mock.clone(), base_mock.clone(), base_mock).unwrap();
    (subir_app_com(ai, cotacao).await, chamadas)
}

// ── helpers de request ────────────────────────────────────────────────────────

async fn post_json(base: &str, rota: &str, token: Option<&str>, corpo: &serde_json::Value)
    -> (StatusCode, serde_json::Value) {
    let mut req = reqwest::Client::new()
        .post(format!("{base}{rota}"))
        .header("content-type", "application/json");
    if let Some(t) = token {
        req = req.header("authorization", format!("Bearer {t}"));
    }

    let resp = req.json(corpo).send().await.unwrap();
    let status = resp.status();
    let json = resp.json().await.unwrap_or(serde_json::Value::Null);
    (status, json)
}

/// Resposta da OpenRouter com `content` embutido.
fn corpo_ia(content: &str) -> serde_json::Value {
    serde_json::json!({ "choices": [{ "message": { "content": content } }] })
}

/// brapi com preço e uma lista de dividendos (formato real, enxugado).
fn corpo_brapi(preco: serde_json::Value, cash_dividends: serde_json::Value) -> serde_json::Value {
    serde_json::json!({
        "results": [{
            "regularMarketPrice": preco,
            "regularMarketTime": "2026-10-12T20:44:30.000Z",
            "dividendsData": { "cashDividends": cash_dividends }
        }]
    })
}

fn corpo_yahoo(preco: serde_json::Value) -> serde_json::Value {
    serde_json::json!({
        "chart": {
            "result": [{
                "meta": {
                    "regularMarketPrice": preco,
                    "regularMarketTime": 1791837870i64,
                    "currency": "BRL"
                }
            }],
            "error": null
        }
    })
}

/// Busca do Yahoo no formato real (enxugado): `longname` é o nome oficial
/// do ativo na B3.
fn corpo_yahoo_busca(symbol_sa: &str, longname: &str) -> serde_json::Value {
    serde_json::json!({
        "count": 1,
        "quotes": [{
            "shortname": longname,
            "longname": longname,
            "symbol": symbol_sa,
            "quoteType": "EQUITY"
        }]
    })
}

fn corpo_quote(symbol: &str, kind: &str) -> serde_json::Value {
    serde_json::json!({ "symbol": symbol, "kind": kind })
}

// ── POST /investments/quote ───────────────────────────────────────────────────

#[tokio::test]
async fn quote_sem_token_devolve_401() {
    let (base, _) = subir_app(
        corpo_ia(r#"{"found": false}"#),
        corpo_brapi(serde_json::json!(56), serde_json::json!([])),
        corpo_yahoo(serde_json::json!(9.45)),
    ).await;

    let (status, _) = post_json(&base, "/investments/quote", None, &corpo_quote("PETR4", "STOCK"))
        .await;

    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn quote_acao_pede_brapi_e_devolve_preco_como_string() {
    // Dinheiro na API é sempre string com 2 casas: "56.00", nunca número
    // JSON 56. O cliente ignora float por contrato — se o backend mandar
    // número, o parser do app descarta a sugestão.
    let (base, chamadas) = subir_app(
        corpo_ia(r#"{"found": false}"#),
        corpo_brapi(serde_json::json!(56), serde_json::json!([])),
        corpo_yahoo(serde_json::json!(9.45)),
    ).await;

    let (status, json) = post_json(
        &base, "/investments/quote", Some(&token_de_teste()), &corpo_quote("PETR4", "STOCK"),
    ).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(json["price"], "56.00");
    assert_eq!(json["source"], "brapi");
    assert_eq!(json["as_of"], "2026-10-12T20:44:30.000Z");

    let registradas = chamadas.lock().unwrap();
    assert_eq!(registradas.as_slice(), ["/api/quote/PETR4".to_string()]);
}

#[tokio::test]
async fn quote_fii_pede_yahoo_com_sufixo_sa() {
    // brapi responde 401 em FII sem token (verificado ao vivo); Yahoo é a
    // fonte do FII, e papel da B3 leva ".SA" lá.
    let (base, chamadas) = subir_app(
        corpo_ia(r#"{"found": false}"#),
        corpo_brapi(serde_json::json!(0), serde_json::json!([])),
        corpo_yahoo(serde_json::json!(9.45)),
    ).await;

    let (status, json) = post_json(
        &base, "/investments/quote", Some(&token_de_teste()), &corpo_quote("MXRF11", "FII"),
    ).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(json["price"], "9.45");
    assert_eq!(json["source"], "yahoo");
    // Epoch 1791837870 (2026-10-12T20:44:30Z) do mock virou RFC3339.
    assert!(json["as_of"].as_str().unwrap().starts_with("2026-10-12"));

    let registradas = chamadas.lock().unwrap();
    assert_eq!(registradas.as_slice(), ["/v8/finance/chart/MXRF11.SA".to_string()]);
}

#[tokio::test]
async fn quote_renda_fixa_e_200_vazio_sem_chamar_ninguem() {
    // "Tesouro Selic 2029" não tem fonte viva (API do Tesouro morreu —
    // verificado). A resposta honesta é "sem sugestão", não erro: a pessoa
    // digita o valor à mão, como sempre.
    let (base, chamadas) = subir_app(
        corpo_ia(r#"{"found": false}"#),
        corpo_brapi(serde_json::json!(56), serde_json::json!([])),
        corpo_yahoo(serde_json::json!(9.45)),
    ).await;

    let (status, json) = post_json(
        &base, "/investments/quote", Some(&token_de_teste()),
        &corpo_quote("Tesouro Selic 2029", "BOND"),
    ).await;

    assert_eq!(status, StatusCode::OK);
    assert!(json["price"].is_null(), "renda fixa não tem preço: {json}");
    assert!(json["source"].is_null());
    assert!(
        chamadas.lock().unwrap().is_empty(),
        "renda fixa não deve gastar chamada em provedor nenhum"
    );
}

#[tokio::test]
async fn quote_simbolo_invalido_e_422_sem_chamar_provedor() {
    // Mesma regra do lookup: validação antes de rede. Cotação não custa
    // dinheiro como a IA, mas custa latência — e 422 ensina a pessoa a
    // escrever o ticker certo.
    let (base, chamadas) = subir_app(
        corpo_ia(r#"{"found": false}"#),
        corpo_brapi(serde_json::json!(56), serde_json::json!([])),
        corpo_yahoo(serde_json::json!(9.45)),
    ).await;

    let (status, json) = post_json(
        &base, "/investments/quote", Some(&token_de_teste()),
        &corpo_quote("max renda", "STOCK"),
    ).await;

    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    assert!(json["message"].as_str().unwrap().contains("não parece um código"));
    assert!(chamadas.lock().unwrap().is_empty());
}

#[tokio::test]
async fn quote_ticker_inexistente_e_200_sem_preco() {
    // Ticker bem-formado mas não listado: brapi devolve results vazio.
    // "Não achei" é resposta, não erro — o app não preenche e segue.
    let (base, _) = subir_app(
        corpo_ia(r#"{"found": false}"#),
        serde_json::json!({ "results": [] }),
        serde_json::json!({ "chart": { "result": null, "error": { "code": "Not Found" } } }),
    ).await;

    let (status, json) = post_json(
        &base, "/investments/quote", Some(&token_de_teste()), &corpo_quote("ZZZZ9", "STOCK"),
    ).await;

    assert_eq!(status, StatusCode::OK);
    assert!(json["price"].is_null(), "ticker sem listação não tem preço: {json}");
    assert!(json["source"].is_null());
}

#[tokio::test]
async fn quote_provedor_fora_e_200_vazio_silencioso() {
    // Porta morta = provedor caiu. O contrato da tela: nunca 500 por causa
    // de sugestão; a pessoa digita o preço à mão e ninguém vê erro.
    let base = subir_app_sem_fontes(corpo_ia(r#"{"found": false}"#)).await;

    let (status, json) = post_json(
        &base, "/investments/quote", Some(&token_de_teste()), &corpo_quote("PETR4", "STOCK"),
    ).await;

    assert_eq!(status, StatusCode::OK);
    assert!(json["price"].is_null(), "falha de provedor deve ser silêncio: {json}");
    assert!(json["source"].is_null());
    assert!(json["as_of"].is_null());
}

// ── POST /investments/lookup com next_dividend ────────────────────────────────

#[tokio::test]
async fn lookup_acao_devolve_data_do_proximo_pagamento() {
    // A IA diz quem é o ativo; o brapi diz quando o dinheiro cai. O mock
    // traz dois futuros (21/12 e 20/03) — o certo é o primeiro.
    let ia = corpo_ia(r#"{"found": true, "name": "Petrobras", "summary": "Petróleo."}"#);
    let brapi = corpo_brapi(serde_json::json!(56), serde_json::json!([
        { "paymentDate": "2026-12-21T03:00:00.000Z", "verified": true },
        { "paymentDate": "2027-03-20T03:00:00.000Z", "verified": true }
    ]));
    let (base, _) = subir_app(ia, brapi, corpo_yahoo(serde_json::json!(0))).await;

    let (status, json) = post_json(
        &base, "/investments/lookup", Some(&token_de_teste()),
        &serde_json::json!({ "symbol": "PETR4", "kind": "STOCK" }),
    ).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(json["found"], true);
    assert_eq!(json["name"], "Petrobras");
    // Formato AAAA-MM-DD, o mesmo que a máscara do campo espera.
    assert_eq!(json["next_dividend"], "2026-12-21");
}

#[tokio::test]
async fn lookup_ativo_sem_pagamento_devolve_next_dividend_nulo() {
    // Ativo que não paga (ou já pagou tudo): next_dividend nulo, e o app
    // não toca no campo — a pessoa deixa vazio ou digita a mão.
    let ia = corpo_ia(r#"{"found": true, "name": "Petrobras", "summary": "Petróleo."}"#);
    let brapi = corpo_brapi(serde_json::json!(56), serde_json::json!([]));
    let (base, _) = subir_app(ia, brapi, corpo_yahoo(serde_json::json!(0))).await;

    let (status, json) = post_json(
        &base, "/investments/lookup", Some(&token_de_teste()),
        &serde_json::json!({ "symbol": "PETR4", "kind": "STOCK" }),
    ).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(json["found"], true);
    assert!(json["next_dividend"].is_null(), "sem pagamento futuro: {json}");
}

#[tokio::test]
async fn lookup_ativo_nao_encontrado_nao_sugere_dividendo() {
    // found=false descarta nome, resumo E data: "não preencha nada" é o
    // contrato, e um campo preenchido no formulário vazio seria exatamente
    // a metade errada.
    let ia = corpo_ia(r#"{"found": false}"#);
    let brapi = corpo_brapi(serde_json::json!(56), serde_json::json!([
        { "paymentDate": "2026-12-21T03:00:00.000Z", "verified": true }
    ]));
    let (base, _) = subir_app(ia, brapi, corpo_yahoo(serde_json::json!(0))).await;

    let (status, json) = post_json(
        &base, "/investments/lookup", Some(&token_de_teste()),
        &serde_json::json!({ "symbol": "ZZZZ9", "kind": "STOCK" }),
    ).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(json["found"], false);
    assert!(json["next_dividend"].is_null(), "não encontrado não sugere data: {json}");
}

#[tokio::test]
async fn lookup_fii_pergunta_provento_ao_statusinvest() {
    // FII no brapi é 401 sem token (verificado ao vivo em 2026-10-17). Quem
    // tem a data de PAGAMENTO do rendimento é o statusinvest: o handler
    // pergunta lá, na rota de fundo, e o campo do app é preenchido.
    let ia = corpo_ia(r#"{"found": true, "name": "Maxi Renda", "summary": "Fundo."}"#);
    let (base, chamadas) = subir_app_com_proventos(
        ia,
        corpo_brapi(serde_json::json!(0), serde_json::json!([])),
        serde_json::json!({ "assetEarningsModels": [{ "pd": "31/12/2099" }] }),
    )
    .await;

    let (status, json) = post_json(
        &base, "/investments/lookup", Some(&token_de_teste()),
        &serde_json::json!({ "symbol": "MXRF11", "kind": "FII" }),
    ).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(json["found"], true);
    assert_eq!(json["next_dividend"], "2099-12-31");
    let registradas = chamadas.lock().unwrap();
    assert!(
        !registradas.iter().any(|p| p.starts_with("/api/quote/")),
        "FII não deve bater no brapi para dividendos (401 sem token): {registradas:?}"
    );
    assert!(
        registradas
            .iter()
            .any(|p| p == "/fii/companytickerprovents?ticker=MXRF11&chartProventsType=2"),
        "FII deve perguntar o provento na rota de fundo do statusinvest: {registradas:?}"
    );
}

#[tokio::test]
async fn lookup_brapi_fora_nao_quebra_a_ia() {
    // O brapi caiu; a IA respondeu. O lookup continua útil (nome + resumo),
    // só sem a data — sugestão quebrada nunca arrasta o que funciona.
    let base = subir_app_sem_fontes(
        corpo_ia(r#"{"found": true, "name": "Petrobras", "summary": "Petróleo."}"#),
    ).await;

    let (status, json) = post_json(
        &base, "/investments/lookup", Some(&token_de_teste()),
        &serde_json::json!({ "symbol": "PETR4", "kind": "STOCK" }),
    ).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(json["found"], true);
    assert_eq!(json["name"], "Petrobras");
    assert!(json["next_dividend"].is_null(), "sem brapi, sem data: {json}");
}

// ── POST /investments/lookup: nome vem do mercado, resumo vem da IA ───────────

#[tokio::test]
async fn lookup_nome_oficial_do_mercado_vence_nome_da_ia() {
    // Caso real que motivou: a IA (mistral-nemo, temperature 0) preencheu
    // "Cyrela" para CPTS11 — confiante e ERRADO. O CPTS11 é o FII Capitania
    // Securities II (verificado no Yahoo ao vivo). O nome é DADO de mercado
    // (longname oficial, grátis e sem token); a IA fica só com o resumo.
    let ia = corpo_ia(r#"{"found": true, "name": "Cyrela", "summary": "Incorporadora de imóveis."}"#);
    let busca = corpo_yahoo_busca(
        "CPTS11.SA",
        "Capitania Securities II Fundo Investimento Imobiliario FII",
    );
    let (base, chamadas, _) = subir_app_com_busca(
        ia,
        corpo_brapi(serde_json::json!(0), serde_json::json!([])),
        corpo_yahoo(serde_json::json!(0)),
        Some(busca),
    ).await;

    let (status, json) = post_json(
        &base, "/investments/lookup", Some(&token_de_teste()),
        &serde_json::json!({ "symbol": "CPTS11", "kind": "FII" }),
    ).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(json["found"], true);
    assert_eq!(
        json["name"],
        "Capitania Securities II Fundo Investimento Imobiliario FII",
        "nome oficial do mercado vence o alucinado pela IA: {json}"
    );
    // O resumo continua sendo da IA — é onde o modelo agrega.
    assert_eq!(json["summary"], "Incorporadora de imóveis.");
    assert!(
        chamadas.lock().unwrap().iter().any(|p| p.starts_with("/v1/finance/search?q=CPTS11.SA")),
        "a busca deve consultar o ticker com sufixo .SA"
    );
}

#[tokio::test]
async fn lookup_nome_da_ia_fica_quando_yahoo_nao_sabe_o_nome() {
    // Yahoo fora do ar (ou sem o ticker): o lookup continua útil com o
    // nome da IA — sugestão quebrada nunca arrasta o que funciona.
    let ia = corpo_ia(r#"{"found": true, "name": "Petrobras", "summary": "Petróleo."}"#);
    let (base, _, _) = subir_app_com_busca(
        ia,
        corpo_brapi(serde_json::json!(0), serde_json::json!([])),
        corpo_yahoo(serde_json::json!(0)),
        None, // /v1/finance/search responde 404
    ).await;

    let (status, json) = post_json(
        &base, "/investments/lookup", Some(&token_de_teste()),
        &serde_json::json!({ "symbol": "PETR4", "kind": "STOCK" }),
    ).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(json["name"], "Petrobras", "sem mercado, o nome da IA vale: {json}");
}

#[tokio::test]
async fn lookup_renda_fixa_nao_pergunta_nome_ao_yahoo() {
    // "Tesouro Selic 2029" não é ticker de bolsa — a validação de formato
    // só existe para STOCK/FII, e a busca do Yahoo também não deve ser
    // chamada: não existe `<texto>.SA` a procurar.
    let ia = corpo_ia(r#"{"found": true, "name": "Tesouro Selic 2029", "summary": "Renda fixa."}"#);
    let (base, chamadas, _) = subir_app_com_busca(
        ia,
        corpo_brapi(serde_json::json!(0), serde_json::json!([])),
        corpo_yahoo(serde_json::json!(0)),
        Some(corpo_yahoo_busca("X.SA", "Nada a ver")),
    ).await;

    let (status, json) = post_json(
        &base, "/investments/lookup", Some(&token_de_teste()),
        &serde_json::json!({ "symbol": "Tesouro Selic 2029", "kind": "BOND" }),
    ).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(json["name"], "Tesouro Selic 2029");
    assert!(
        !chamadas.lock().unwrap().iter().any(|p| p.starts_with("/v1/finance/search")),
        "renda fixa não deve gastar chamada na busca do Yahoo"
    );
}

#[tokio::test]
async fn lookup_ativo_nao_encontrado_nao_devolve_nome_oficial() {
    // Contrato: found=false descarta TUDO — inclusive o nome oficial. Se a
    // IA diz que o ativo não existe, um nome de mercado preenchido seria a
    // metade errada no formulário vazio.
    let ia = corpo_ia(r#"{"found": false}"#);
    let busca = corpo_yahoo_busca("ZZZZ9.SA", "Algo Que Existe no Yahoo");
    let (base, _, _) = subir_app_com_busca(
        ia,
        corpo_brapi(serde_json::json!(0), serde_json::json!([])),
        corpo_yahoo(serde_json::json!(0)),
        Some(busca),
    ).await;

    let (status, json) = post_json(
        &base, "/investments/lookup", Some(&token_de_teste()),
        &serde_json::json!({ "symbol": "ZZZZ9", "kind": "STOCK" }),
    ).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(json["found"], false);
    assert!(json["name"].is_null(), "found=false não preenche nome: {json}");
}

#[tokio::test]
async fn lookup_ancora_o_resumo_da_ia_no_nome_oficial() {
    // O resumo vem da IA, mas a IA precisa RECEBER o nome oficial como
    // contexto: o mistral-nemo resumiu "Cyrela" para CPTS11 porque só viu
    // o ticker e adivinhou a empresa errada. Com o nome oficial no prompt,
    // ela resume a empresa certa.
    let ia = corpo_ia(r#"{"found": true, "summary": "Fundo que investe em títulos de renda fixa."}"#);
    let busca = corpo_yahoo_busca(
        "CPTS11.SA",
        "Capitania Securities II Fundo Investimento Imobiliario FII",
    );
    let (base, _, corpo_ia_recebido) = subir_app_com_busca(
        ia,
        corpo_brapi(serde_json::json!(0), serde_json::json!([])),
        corpo_yahoo(serde_json::json!(0)),
        Some(busca),
    ).await;

    let (status, _) = post_json(
        &base, "/investments/lookup", Some(&token_de_teste()),
        &serde_json::json!({ "symbol": "CPTS11", "kind": "FII" }),
    ).await;

    assert_eq!(status, StatusCode::OK);
    let enviado = corpo_ia_recebido
        .lock()
        .unwrap()
        .clone()
        .expect("a IA deve ter sido chamada");
    let user_msg = enviado["messages"][1]["content"].as_str().unwrap();
    assert!(
        user_msg.contains("Capitania Securities II"),
        "o prompt à IA deve ancorar o resumo no nome oficial; recebido: {user_msg}"
    );
}
