//! POST /investments/lookup — prefill do formulário de ativo por IA.
//!
//! Os testes sobem um MOCK da OpenRouter em porta aleatória: o caminho real
//! custa dinheiro e é não-determinístico. O mock registra o header e o corpo
//! de cada chamada, o que permite provar as duas regras de ouro da feature:
//!
//! 1. A chave vai no header `Authorization`, nunca no corpo — se fosse no
//!    corpo, o modelo a leria, e um log de request a revelaria.
//! 2. Símbolo inválido NÃO chega a gastar chamada: validação roda antes da
//!    IA, e cada chamada que não acontece é dinheiro que não sai do bolso.

use std::sync::{Arc, Mutex};

use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use axum::routing::post;
use axum::{Json, Router};
use kavelipay_backend::ai::AiClient;
use kavelipay_backend::auth::TokenService;
use kavelipay_backend::state::AppState;
use uuid::Uuid;

// ── mock da OpenRouter ────────────────────────────────────────────────────────

#[derive(Clone)]
struct MockOpenRouter {
    /// O `content` que o mock devolve em `choices[0].message.content`.
    conteudo: String,
    /// Cada chamada recebida: (header Authorization, corpo JSON).
    chamadas: Arc<Mutex<Vec<(Option<String>, serde_json::Value)>>>,
}

async fn mock_chat(
    State(m): State<MockOpenRouter>,
    headers: HeaderMap,
    Json(corpo): Json<serde_json::Value>,
) -> Json<serde_json::Value> {
    let auth = headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .map(str::to_string);

    m.chamadas.lock().unwrap().push((auth, corpo));

    Json(serde_json::json!({
        "choices": [{ "message": { "content": m.conteudo } }]
    }))
}

/// Sobe o mock e devolve a base URL (ex.: `http://127.0.0.1:41234`) e o
/// registrador compartilhado de chamadas.
async fn subir_mock(conteudo: &str) -> (String, Arc<Mutex<Vec<(Option<String>, serde_json::Value)>>>) {
    let mock = MockOpenRouter {
        conteudo: conteudo.to_string(),
        chamadas: Arc::new(Mutex::new(Vec::new())),
    };
    let registrador = mock.chamadas.clone();

    let app = Router::new().route("/chat/completions", post(mock_chat)).with_state(mock);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });

    (format!("http://{addr}"), registrador)
}

// ── app sob teste ─────────────────────────────────────────────────────────────

fn token_de_teste() -> String {
    TokenService::new("segredo-de-teste-com-32-bytes-no-minimo!!", 15, 30)
        .issue(Uuid::new_v4(), true)
        .unwrap()
        .0
}

/// Sobe o router real de produção em porta aleatória com o `ai` apontando
/// para o mock (ou desligado). O pool é lazy de propósito: o handler de
/// lookup NÃO toca em banco — se tocasse, este teste travaria no connect
/// em vez de passar, e a falha diria "o lookup virou consulta de carteira".
async fn subir_app(ai: AiClient) -> String {
    let db = sqlx::postgres::PgPoolOptions::new()
        .connect_lazy("postgres://nao-conecta@127.0.0.1:1/nao_existe")
        .unwrap();
    let tokens = TokenService::new("segredo-de-teste-com-32-bytes-no-minimo!!", 15, 30);
    // Porta morta de propósito: os testes de lookup não devem tocar em
    // brapi/Yahoo. O teste de dividendos sobe seu próprio mock e um
    // `subir_app_com` apontando para ele.
    let cotacao = kavelipay_backend::cotacao::CotacaoClient::new(
        "http://127.0.0.1:1".into(),
        "http://127.0.0.1:1".into(),
        "http://127.0.0.1:1".into(),
    )
    .unwrap();

    let app = kavelipay_backend::routes::router(AppState { db, tokens, ai, cotacao, mp: kavelipay_backend::mercadopago::MercadoPago::new(None, kavelipay_backend::mercadopago::BASE_URL_PADRAO.into(), None).unwrap(), public_base_url: "http://localhost:3000".into(), admin_token: None, login_gate: Default::default(), mailer: None, reset_gate: Default::default(), limite_global: Default::default() });
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });

    format!("http://{addr}")
}

fn ai_apontando_para(base: &str) -> AiClient {
    AiClient::new(
        Some("chave-teste-secreta".into()),
        "modelo/teste".into(),
        base.into(),
    )
    .unwrap()
}

async fn post_lookup(base: &str, token: Option<&str>, corpo: &serde_json::Value) -> (StatusCode, serde_json::Value) {
    let mut req = reqwest::Client::new()
        .post(format!("{base}/investments/lookup"))
        .header("content-type", "application/json");
    if let Some(t) = token {
        req = req.header("authorization", format!("Bearer {t}"));
    }

    let resp = req.json(corpo).send().await.unwrap();
    let status = resp.status();
    let json: serde_json::Value = resp.json().await.unwrap_or(serde_json::Value::Null);
    (status, json)
}

fn corpo_lookup(symbol: &str) -> serde_json::Value {
    serde_json::json!({ "symbol": symbol, "kind": "STOCK" })
}

// ── testes ────────────────────────────────────────────────────────────────────

#[tokio::test]
async fn sem_token_devolve_401() {
    // Sem login não existe lookup: a chave da OpenRouter é do servidor, e
    // um endpoint anônimo seria um balde furado gastando nosso saldo.
    let (base_mock, _) = subir_mock(r#"{"found": false}"#).await;
    let base = subir_app(ai_apontando_para(&base_mock)).await;

    let (status, _) = post_lookup(&base, None, &corpo_lookup("PETR4")).await;

    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn simbolo_invalido_devolve_422_e_nao_gasta_chamada_na_ia() {
    // "max renda" é o NOME do fundo, não o código — o caso real que motivou
    // a validação. O mock tem de receber ZERO chamadas: validação falha
    // antes da IA, e cada chamada evitada é dinheiro que não sai.
    let (base_mock, chamadas) = subir_mock(r#"{"found": false}"#).await;
    let base = subir_app(ai_apontando_para(&base_mock)).await;

    let (status, json) = post_lookup(&base, Some(&token_de_teste()), &corpo_lookup("max renda")).await;

    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    assert!(
        json["message"].as_str().unwrap().contains("não parece um código"),
        "mensagem devolvida: {json}"
    );
    assert!(
        chamadas.lock().unwrap().is_empty(),
        "símbolo inválido chegou a gastar chamada na IA"
    );
}

#[tokio::test]
async fn sem_chave_configurada_devolve_503_indisponivel() {
    // Feature desligada (sem OPENROUTER_API_KEY) não pode ser confundida com
    // "ativo não existe": uma mente "não preencha nada" igual, mas 503 diz
    // a verdade (recurso desligado) e aparece no log como tal.
    let base = subir_app(AiClient::sem_chave()).await;

    let (status, json) = post_lookup(&base, Some(&token_de_teste()), &corpo_lookup("PETR4")).await;

    assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(json["error"], "unavailable");
}

#[tokio::test]
async fn encontrado_devolve_nome_e_resumo() {
    let (base_mock, _) = subir_mock(
        r#"{"found": true, "name": "Petrobras", "summary": "Empresa de petróleo e gás."}"#,
    )
    .await;
    let base = subir_app(ai_apontando_para(&base_mock)).await;

    let (status, json) = post_lookup(&base, Some(&token_de_teste()), &corpo_lookup("PETR4")).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(json["found"], true);
    assert_eq!(json["name"], "Petrobras");
    assert_eq!(json["summary"], "Empresa de petróleo e gás.");
}

#[tokio::test]
async fn nao_encontrado_devolve_found_false_sem_preencher_nada() {
    let (base_mock, _) = subir_mock(r#"{"found": false}"#).await;
    let base = subir_app(ai_apontando_para(&base_mock)).await;

    let (status, json) = post_lookup(&base, Some(&token_de_teste()), &corpo_lookup("ZZZZ9")).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(json["found"], false);
    // Nome e resumo nulos — não string vazia. O app só preenche campo com
    // valor não-nulo; string vazia apagaria o que a pessoa já digitou.
    assert!(json["name"].is_null(), "name devolvido: {json}");
    assert!(json["summary"].is_null(), "summary devolvido: {json}");
}

#[tokio::test]
async fn chave_vai_no_header_e_nunca_no_corpo() {
    // O teste de segurança da feature: se a chave aparecer no corpo, ou o
    // modelo a lê, ou um log de request a registra. Ela só pode estar no
    // header Authorization — que é para a OpenRouter, não para o modelo.
    let (base_mock, chamadas) = subir_mock(r#"{"found": false}"#).await;
    let base = subir_app(ai_apontando_para(&base_mock)).await;

    let (_, _) = post_lookup(&base, Some(&token_de_teste()), &corpo_lookup("PETR4")).await;

    let registradas = chamadas.lock().unwrap();
    assert_eq!(registradas.len(), 1, "esperava exatamente uma chamada");

    let (auth, corpo) = &registradas[0];
    assert_eq!(auth.as_deref(), Some("Bearer chave-teste-secreta"));

    let corpo_texto = corpo.to_string();
    assert!(
        !corpo_texto.contains("chave-teste-secreta"),
        "a chave vazou para o corpo da requisição: {corpo_texto}"
    );
    assert_eq!(corpo["model"], "modelo/teste");
    assert_eq!(corpo["messages"][1]["content"], "PETR4");
}

#[tokio::test]
async fn simbolo_com_espaco_e_minusculas_e_normalizado_antes_da_ia() {
    // " petr4 " é o mesmo ativo que "PETR4": sem normalizar, o usuário
    // criaria três posições separadas do mesmo papel. O mock registra o
    // corpo — o que chega na IA tem de ser o símbolo já normalizado.
    let (base_mock, chamadas) = subir_mock(r#"{"found": false}"#).await;
    let base = subir_app(ai_apontando_para(&base_mock)).await;

    let (status, _) = post_lookup(&base, Some(&token_de_teste()), &corpo_lookup(" petr4 ")).await;

    assert_eq!(status, StatusCode::OK);
    let registradas = chamadas.lock().unwrap();
    assert_eq!(registradas[0].1["messages"][1]["content"], "PETR4");
}

#[tokio::test]
async fn resumo_com_valor_monetario_e_descartado_mas_nome_fica() {
    // A IA pode inventar "ação que vale R$ 35,00" — e o trigger da migration
    // 0007 recusaria o INSERT na hora de salvar, cobrando da pessoa um erro
    // que ela não causou. Descartar o resumo aqui (e manter o nome, que
    // continua útil) é a diferença entre prefill que ajuda e prefill que
    // transforma o salvar em 500.
    let (base_mock, _) = subir_mock(
        r#"{"found": true, "name": "Petrobras", "summary": "Ação da Petrobras que vale R$ 35,00 no mercado."}"#,
    )
    .await;
    let base = subir_app(ai_apontando_para(&base_mock)).await;

    let (status, json) = post_lookup(&base, Some(&token_de_teste()), &corpo_lookup("PETR4")).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(json["name"], "Petrobras");
    assert!(
        json["summary"].is_null(),
        "resumo com valor monetário devia ser descartado, veio: {json}"
    );
}
