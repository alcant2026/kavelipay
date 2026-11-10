use kavelipay_backend::{AppError, StatusCode};

#[test]
fn erro_de_infraestrutura_nao_vaza_detalhe_para_o_cliente() {
    // Um erro de banco pode carregar SQL, nome de tabela ou valor de coluna.
    // Nada disso pode chegar ao cliente — o log tem o motivo, a resposta não.
    let erro = AppError::Database(sqlx::Error::RowNotFound);

    assert_eq!(erro.status(), StatusCode::INTERNAL_SERVER_ERROR);
    assert_eq!(erro.client_message(), "erro interno");
    assert!(
        !erro.client_message().contains("RowNotFound"),
        "detalhe interno vazou para o cliente"
    );
}

#[test]
fn erro_de_token_também_nao_vaza() {
    let erro = AppError::Token(jsonwebtoken::errors::Error::from(
        jsonwebtoken::errors::ErrorKind::InvalidSignature,
    ));

    assert_eq!(erro.status(), StatusCode::INTERNAL_SERVER_ERROR);
    assert_eq!(erro.client_message(), "erro interno");
}

#[test]
fn indisponível_é_503_não_500_nem_404() {
    // Recurso desligado não é erro do cliente nem
    // defeito interno: é o servidor dizendo "não tenho o recurso agora".
    // 404 mentiria ("rota inexistente"), 500 culparia a infra na cara do app.
    let erro = AppError::Unavailable;

    assert_eq!(erro.status(), StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(erro.code(), "unavailable");
    assert_eq!(erro.client_message(), "recurso indisponível no momento");
}

#[test]
fn erro_de_dominio_mantém_o_detalhe_útil() {
    // 4xx precisa ser acionável: o cliente precisa saber o que corrigir.
    let erro = AppError::UnbalancedTransaction {
        debits: "40.00".into(),
        credits: "100.00".into(),
    };

    assert_eq!(erro.status(), StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(erro.code(), "invalid_transaction");
    assert!(erro.client_message().contains("40.00"));
    assert!(erro.client_message().contains("100.00"));
}

#[test]
fn acesso_negado_mapeia_para_403() {
    let erro = AppError::Forbidden;

    assert_eq!(erro.status(), StatusCode::FORBIDDEN);
    assert_eq!(erro.code(), "forbidden");
}

#[test]
fn nao_autorizado_mapeia_para_401() {
    let erro = AppError::Unauthorized;

    assert_eq!(erro.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(erro.code(), "unauthorized");
}