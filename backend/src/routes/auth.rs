//! Handlers de autenticação: cadastro, login, refresh, logout.
//!
//! Regra transversal: o `user_id` dos requests subsequentes vem do token
//! (ver `auth::extract::AuthUser`). Aqui o id é criado pelo servidor a partir
//! do que o usuário informou — nunca aceito do cliente.

use axum::Json;
use axum::extract::State;
use serde::{Deserialize, Serialize};

use crate::error::{AppError, AppResult};
use crate::state::AppState;

#[derive(Deserialize)]
pub struct RegisterRequest {
    pub email: String,
    pub password: String,
    pub display_name: Option<String>,
}

#[derive(Serialize)]
pub struct TokenResponse {
    pub access_token: String,
    pub refresh_token: String,
    pub token_type: &'static str,
}

#[derive(Deserialize)]
pub struct LoginRequest {
    pub email: String,
    pub password: String,
}

#[derive(Deserialize)]
pub struct RefreshRequest {
    pub refresh_token: String,
}

/// POST /auth/register
pub async fn register(
    State(state): State<AppState>,
    Json(req): Json<RegisterRequest>,
) -> AppResult<Json<TokenResponse>> {
    let email = normalizar_email(&req.email)?;
    validar_senha(&req.password)?;
    let hash = crate::auth::hash(&req.password)?;

    // INSERT direto: uma violação de unique (users_email_lower_uniq) é
    // mapped por `mapear_erro_cadastro` para 409, não para 500. Deixar
    // fetch_optional esperar None aqui nunca aconteceria — a unique estoura
    // como erro de banco, não como linha vazia.
    let user_id = sqlx::query_scalar::<_, uuid::Uuid>(
        "INSERT INTO users (email, password_hash, display_name)
         VALUES ($1, $2, $3)
         RETURNING id",
    )
    .bind(&email)
    .bind(&hash)
    .bind(req.display_name.as_deref())
    .fetch_one(&state.db)
    .await
    .map_err(mapear_erro_cadastro)?;

    // A wallet nasce JÁ COM as contas do ledger, na mesma transação do cadastro.
    // Um usuário sem `USER_AVAILABLE` é um usuário cujo saldo não existe, e o
    // primeiro endpoint de saldo devolveria 404 para uma conta recém-criada.
    let mut tx = state.db.begin().await?;
    let wallet_id = sqlx::query_scalar::<_, uuid::Uuid>(
        "INSERT INTO wallets (user_id) VALUES ($1) RETURNING id",
    )
    .bind(user_id)
    .fetch_one(&mut *tx)
    .await?;
    crate::ledger::garantir_contas(&mut *tx, wallet_id).await?;
    // Categorias padrão semeadas junto: a spec lista as duas, e pedir ao
    // usuário para cadastrar "Aluguel" antes de pagar o primeiro boleto é
    // atrito evitável.
    crate::categories::semear(&mut *tx, user_id).await?;
    tx.commit().await?;

    emitir(state, user_id).await
}

/// POST /auth/login
#[axum::debug_handler]
pub async fn login(
    State(state): State<AppState>,
    ip_auth: crate::auth::ClientIp,
    Json(req): Json<LoginRequest>,
) -> AppResult<Json<TokenResponse>> {
    let email = normalizar_email(&req.email)?;

    // Porteira de brute force. Sem ConnectInfo (request fabricado em teste)
    // cai no IP 0.0.0.0 — a contagem via igual.
    let ip = ip_auth.0.unwrap_or(std::net::Ipv4Addr::UNSPECIFIED.into());
    match state.login_gate.aceita(ip, &email) {
        Ok(()) => {}
        Err(espera) => {
            return Err(AppError::TooManyRequests(format!(
                "muitas tentativas de login; tente de novo em {}s",
                espera.as_secs().max(1)
            )));
        }
    }

    let row = sqlx::query_as::<_, (uuid::Uuid, String)>(
        "SELECT id, password_hash FROM users WHERE email = $1",
    )
    .bind(&email)
    .fetch_optional(&state.db)
    .await?;

    let Some((user_id, hash)) = row else {
        // Custo constante: verificamos a senha contra um hash descartável antes
        // de devolver erro, senão o tempo de resposta revelaria quais emails
        // existem (user enumeration).
        let _ = crate::auth::verify(&req.password, DUMMY_HASH);
        state.login_gate.registrar_falha(ip, &email);
        return Err(AppError::Unauthorized);
    };

    if !crate::auth::verify(&req.password, &hash) {
        state.login_gate.registrar_falha(ip, &email);
        return Err(AppError::Unauthorized);
    }

    state.login_gate.registrar_sucesso(ip, &email);
    emitir(state, user_id).await
}

/// POST /auth/refresh
///
/// Rotação: o refresh usado é revogado e um novo é emitido. Se um token já
/// revogado for reusado, isso indica token roubado — todas as sessões do usuário
/// morrem e o cliente precisa refazer login.
pub async fn refresh(
    State(state): State<AppState>,
    Json(req): Json<RefreshRequest>,
) -> AppResult<Json<TokenResponse>> {
    let hash = crate::auth::hash_refresh(&req.refresh_token);

    let found = sqlx::query_as::<_, (uuid::Uuid,)>(
        "UPDATE refresh_tokens
            SET revoked_at = now()
          WHERE token_hash = $1
            AND revoked_at IS NULL
            AND expires_at > now()
        RETURNING user_id",
    )
    .bind(&hash)
    .fetch_optional(&state.db)
    .await?;

    let Some((user_id,)) = found else {
        // Token não utilizável. Distinguimos dois casos:
        //  - token existe mas JÁ foi rotacionado → possível roubo;
        //  - token nunca existiu → só um refresh inválido.
        // No primeiro caso matamos todas as sessões do usuário: o atacante e a
        // vítima perdem o acesso, e a vítima percebe no próximo login.
        if let Some((owner,)) = dono_do_token(&state, &hash).await {
            tracing::warn!(
                user_id = %owner,
                "refresh token reutilizado após revogação — revogando todas as sessões"
            );
            sqlx::query("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL")
                .bind(owner)
                .execute(&state.db)
                .await?;
        }
        return Err(AppError::Unauthorized);
    };

    emitir(state, user_id).await
}

/// POST /auth/logout — revoga o refresh informado. O access token expira sozinho.
pub async fn logout(State(state): State<AppState>, Json(req): Json<RefreshRequest>) -> AppResult<()> {
    let hash = crate::auth::hash_refresh(&req.refresh_token);
    sqlx::query("UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = $1")
        .bind(&hash)
        .execute(&state.db)
        .await?;
    Ok(())
}

#[derive(Deserialize)]
pub struct ForgotPasswordRequest {
    pub email: String,
}

/// Resposta de um passo que NÃO pode confirmar existência de conta.
#[derive(Serialize)]
pub struct MensagemResponse {
    pub mensagem: &'static str,
}

/// Texto único do "esqueci a senha". Igual com conta e sem conta — a diferença
/// entregaria uma lista de e-mails cadastrados.
const RESPOSTA_GENERICA: &str =
    "Se este e-mail estiver cadastrado, enviamos um código de redefinição.";

/// POST /auth/forgot-password
///
/// Três cuidados que não são opcionais:
/// 1. a resposta é sempre a mesma (sem enumeração de contas);
/// 2. o envio roda em background — o tempo de resposta também denunciaria
///    quem tem conta, e SMTP lento não pode segurar o request;
/// 3. um freio por IP, senão o endpoint vira remetente de spam usando o seu
///    domínio.
pub async fn forgot_password(
    State(state): State<AppState>,
    ip_auth: crate::auth::ClientIp,
    Json(req): Json<ForgotPasswordRequest>,
) -> AppResult<Json<MensagemResponse>> {
    // Sem SMTP configurado é 503: melhor a tela dizer "indisponível" do que
    // fingir que mandou um e-mail que nunca sai.
    let mailer = state.mailer.clone().ok_or(AppError::Unavailable)?;

    let ip = ip_auth
        .0
        .unwrap_or(std::net::Ipv4Addr::UNSPECIFIED.into());
    if !state.reset_gate.permite(ip) {
        return Err(AppError::TooManyRequests(
            "muitos pedidos de redefinição; tente de novo em 1 hora".into(),
        ));
    }

    let email = normalizar_email(&req.email)?;

    let conta = sqlx::query_as::<_, (uuid::Uuid, String)>(
        "SELECT id, email FROM users WHERE email = $1",
    )
    .bind(&email)
    .fetch_optional(&state.db)
    .await?;

    if let Some((user_id, email)) = conta {
        // Cooldown por conta: dois toques no botão não viram dois e-mails.
        // Conta o PEDIDO, não o envio — a linha nasce antes do SMTP.
        let pedido_recente = sqlx::query_scalar::<_, i32>(
            "SELECT 1 FROM password_resets
              WHERE user_id = $1 AND criado_em > now() - INTERVAL '60 seconds'",
        )
        .bind(user_id)
        .fetch_optional(&state.db)
        .await?
        .is_some();

        if !pedido_recente {
            let codigo = crate::routes::billing::gerar_codigo_legivel();
            // Mesmo SHA-256 dos refresh tokens: o banco guarda só o hash, então
            // quem lê a tabela não redefine a senha de ninguém.
            let hash = crate::auth::hash_refresh(&codigo);

            // Pedido novo apaga os pendentes: dois códigos vivos ao mesmo tempo
            // só aumentam a superfície de ataque.
            sqlx::query("DELETE FROM password_resets WHERE user_id = $1")
                .bind(user_id)
                .execute(&state.db)
                .await?;
            sqlx::query(
                "INSERT INTO password_resets (user_id, token_hash, expira_em)
                 VALUES ($1, $2, now() + make_interval(mins => $3))",
            )
            .bind(user_id)
            .bind(&hash)
            .bind(crate::mail::VALIDADE_RESET_MINUTOS as i32)
            .execute(&state.db)
            .await?;

            let corpo = corpo_reset(&codigo);
            tokio::spawn(async move {
                if let Err(erro) = mailer
                    .enviar(&email, "Redefinir sua senha KaveliPay", &corpo)
                    .await
                {
                    tracing::error!(email = %email, "envio do código de redefinição falhou: {erro}");
                }
            });
        }
    }

    Ok(Json(MensagemResponse {
        mensagem: RESPOSTA_GENERICA,
    }))
}

#[derive(Deserialize)]
pub struct ResetPasswordRequest {
    pub token: String,
    pub new_password: String,
}

#[derive(Serialize)]
pub struct PasswordResetOk {
    pub sessions_revoked: u64,
}

/// POST /auth/reset-password
///
/// O código é de uso único e tem prazo. Consumo e troca de senha acontecem na
/// MESMA transação: se o UPDATE da senha falhasse depois de queimar o código, o
/// usuário ficaria sem o código antigo e sem a senha nova.
pub async fn reset_password(
    State(state): State<AppState>,
    Json(req): Json<ResetPasswordRequest>,
) -> AppResult<Json<PasswordResetOk>> {
    validar_senha(&req.new_password)?;

    let hash_do_codigo = crate::auth::hash_refresh(req.token.trim());
    let novo_hash = crate::auth::hash(&req.new_password)?;

    let mut tx = state.db.begin().await?;

    // Um único UPDATE condicional resolve "existe, não venceu, não foi usado" e
    // já marca o uso. Dois requests simultâneos com o mesmo código: o segundo
    // não encontra a linha (o predicado é reavaliado depois do lock).
    let consumo = sqlx::query_as::<_, (uuid::Uuid,)>(
        "UPDATE password_resets
            SET usado_em = now()
          WHERE token_hash = $1
            AND usado_em IS NULL
            AND expira_em > now()
        RETURNING user_id",
    )
    .bind(&hash_do_codigo)
    .fetch_optional(&mut *tx)
    .await?;

    let Some((user_id,)) = consumo else {
        return Err(AppError::NaoValido(
            "código inválido ou expirado; peça um novo".into(),
        ));
    };

    sqlx::query("UPDATE users SET password_hash = $2, updated_at = now() WHERE id = $1")
        .bind(user_id)
        .bind(&novo_hash)
        .execute(&mut *tx)
        .await?;

    // Senha nova derruba toda sessão aberta: quem estava logado com a senha
    // antiga pode ser justamente quem a perdeu.
    let revogadas = sqlx::query(
        "UPDATE refresh_tokens SET revoked_at = now()
          WHERE user_id = $1 AND revoked_at IS NULL",
    )
    .bind(user_id)
    .execute(&mut *tx)
    .await?
    .rows_affected();

    tx.commit().await?;

    tracing::info!(user_id = %user_id, revogadas, "senha redefinida por código; sessões revogadas");

    Ok(Json(PasswordResetOk {
        sessions_revoked: revogadas,
    }))
}

/// Corpo do e-mail. Diz o que fazer e o que acontece se não foi o dono — sem
/// jargão e sem link: o código é digitado/colado no app.
fn corpo_reset(codigo: &str) -> String {
    format!(
        "Alguém pediu para redefinir a senha da sua conta KaveliPay.\n\
         \n\
         Seu código (vale por {} minutos e só uma vez):\n\
         \n\
         {codigo}\n\
         \n\
         Abra o app, toque em \"Esqueci minha senha\", cole o código e escolha a \
         senha nova.\n\
         \n\
         Se não foi você, ignore esta mensagem: sua senha continua a mesma.\n",
        crate::mail::VALIDADE_RESET_MINUTOS
    )
}

// ── helpers ──────────────────────────────────────────────────────────────────

/// Hash descartável de um usuário inexistente, para igualar o custo do login.
const DUMMY_HASH: &str = "$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHRzb21lc2FsdA$Yn5pNXNtYWx0c2FsdGJhc2U2NHNvbW";

/// Quantas sessões ativas uma conta mantém ao mesmo tempo.
///
/// Cada login abre uma sessão de 30 dias e nada as fechava: quem entrava do
/// celular, do navegador e de alguns `curl` de teste acumulava dezenas, e a
/// tela "Sessões abertas" virava uma parede de linhas quase idênticas — foi
/// exatamente a queixa do usuário ("mostra várias sessões abertas").
///
/// Cinco cobre o uso real (celular, tablet, dois navegadores e uma sobra) e
/// mantém a lista legível. O limite é aplicado no login, então quem já tem 30
/// se limpa no próximo; não é migração de dados.
const MAX_SESSOES_ATIVAS: i64 = 5;

async fn emitir(state: AppState, user_id: uuid::Uuid) -> AppResult<Json<TokenResponse>> {
    // O claim `premium` é congelado agora: o direito real está na tabela
    // `assinaturas`, e as rotas pagas leem o token em vez de consultar o banco
    // a cada requisição. Vale por 15 min — o refresh renova o retrato.
    let premium = crate::routes::billing::assinatura_vigente(&state.db, user_id)
        .await?
        .is_some();
    let (access, raw_refresh, hash) = state.tokens.issue(user_id, premium)?;

    sqlx::query(
        "INSERT INTO refresh_tokens (user_id, token_hash, expires_at)
         VALUES ($1, $2, $3)",
    )
    .bind(user_id)
    .bind(&hash)
    .bind(state.tokens.refresh_expiry())
    .execute(&state.db)
    .await?;

    // Poda DEPOIS do INSERT: a sessão recém-criada é a mais nova e fica.
    // Revoga (não apaga): a linha revogada é o que permite detectar reuso de
    // refresh token roubado, então apagar aqui enfraqueceria a detecção.
    sqlx::query(
        "UPDATE refresh_tokens SET revoked_at = now()
          WHERE user_id = $1 AND revoked_at IS NULL
            AND id NOT IN (
                SELECT id FROM refresh_tokens
                 WHERE user_id = $1 AND revoked_at IS NULL
                 ORDER BY created_at DESC, id DESC
                 LIMIT $2
            )",
    )
    .bind(user_id)
    .bind(MAX_SESSOES_ATIVAS)
    .execute(&state.db)
    .await?;

    Ok(Json(TokenResponse {
        access_token: access,
        refresh_token: raw_refresh,
        token_type: "Bearer",
    }))
}

/// Email em minúsculas e sem espaços: evita duas contas para a mesma pessoa.
fn normalizar_email(email: &str) -> AppResult<String> {
    let e = email.trim().to_lowercase();
    if e.is_empty() || !e.contains('@') || e.len() > 320 {
        return Err(AppError::Validation("email inválido".into()));
    }
    Ok(e)
}

/// Regra de senha, compartilhada por cadastro e troca de senha. Pública porque
/// duas regras divergentes é como uma delas deixa de ser aplicada.
pub fn validar_senha(senha: &str) -> AppResult<()> {
    if senha.len() < 8 {
        return Err(AppError::Validation(
            "senha deve ter ao menos 8 caracteres".into(),
        ));
    }
    if senha.len() > 200 {
        return Err(AppError::Validation("senha muito longa".into()));
    }
    Ok(())
}

/// Violação de unique vira 409, não 500. Sem isto, um cadastro repetido
/// voltaria 500 com cara de falha de infraestrutura — e o usuário real
/// receberia "erro interno" ao tentar criar a conta duas vezes.
fn mapear_erro_cadastro(err: sqlx::Error) -> AppError {
    if let sqlx::Error::Database(db) = &err
        && db.code().as_deref() == Some("23505")
    {
        return AppError::Conflict("email já cadastrado".into());
    }
    AppError::Database(err)
}

/// Dono de um refresh token, MESMO já revogado. É o que permite detectar
/// reuso de token rotacionado e derrubar as sessões do dono.
async fn dono_do_token(state: &AppState, hash: &str) -> Option<(uuid::Uuid,)> {
    sqlx::query_as::<_, (uuid::Uuid,)>(
        "SELECT user_id FROM refresh_tokens WHERE token_hash = $1",
    )
    .bind(hash)
    .fetch_optional(&state.db)
    .await
    .ok()
    .flatten()
}

// ── rota protegida ───────────────────────────────────────────────────────────

#[derive(Serialize)]
pub struct MeResponse {
    pub user_id: uuid::Uuid,
    pub email: String,
    pub display_name: Option<String>,
}

/// GET /auth/me — o teste mais direto da fronteira de confiança.
///
/// O `user_id` vem do token via `AuthUser`, nunca do body ou da query string.
/// Se alguém "consertar" este handler para aceitar `?user_id=`, a segurança
/// inteira quebra e este endpoint deixa de provar nada.
pub async fn me(
    State(state): State<AppState>,
    auth: crate::auth::extract::AuthUser,
) -> AppResult<Json<MeResponse>> {
    // Busca pelo id do token; um token válido para usuário inexistente ou
    // removido é 401, não um Wallet vazio.
    let row = sqlx::query_as::<_, (uuid::Uuid, String, Option<String>)>(
        "SELECT id, email, display_name FROM users WHERE id = $1",
    )
    .bind(auth.user_id())
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::Unauthorized)?;

    Ok(Json(MeResponse {
        user_id: row.0,
        email: row.1,
        display_name: row.2,
    }))
}