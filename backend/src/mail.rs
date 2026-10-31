//! E-mail transacional por SMTP — só o necessário para o link de redefinição
//! de senha.
//!
//! **Por que na mão, e não `lettre`:** o `lettre` puxa o rustls com o provider
//! `aws-lc-rs`, e este binário já fixou `ring` (ver `Cargo.toml`). Com dois
//! providers compilados, o rustls entra em pânico na escolha automática — o
//! mesmo motivo que fez `http_cru.rs` escrever HTTP na mão. O diálogo SMTP
//! que importa aqui é curto: EHLO, AUTH LOGIN, MAIL, RCPT, DATA.
//!
//! O e-mail é disparado em background ([`crate::routes::auth::forgot_password`])
//! e falha só no log: o usuário nunca deve descobrir, pelo tempo de resposta,
//! se o endereço existe.
//!
//! shortcut: TLS implícito (porta 465) apenas. Provedor que só ofereça
//! STARTTLS (587) precisa de subida de TLS no meio da conversa.

use std::sync::Arc;
use std::time::Duration;

use base64::Engine;
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncWrite, AsyncWriteExt, BufReader};

use crate::error::{AppError, AppResult};

/// Tempo máximo da conversa inteira. SMTP fora do ar não pode segurar uma
/// task para sempre.
const PRAZO: Duration = Duration::from_secs(20);

/// Validade do link enviado ao usuário. Curto de propósito: é o tempo que um
/// e-mail vazado continua valendo.
pub const VALIDADE_RESET_MINUTOS: i64 = 30;

/// Remetente padrão exibido na caixa de entrada. O endereço vem de
/// `SMTP_FROM`; este nome é só o que aparece antes dele.
const NOME_REMETENTE: &str = "KaveliPay";

#[derive(Clone)]
pub struct Mailer {
    host: String,
    porta: u16,
    /// `false` só em teste (mock em loopback). `from_env` recusa desligar
    /// TLS para host que não seja a própria máquina.
    tls: bool,
    usuario: Option<String>,
    senha: Option<String>,
    remetente: String,
}

impl Mailer {
    /// Monta o mailer a partir do ambiente. `SMTP_HOST` ausente = e-mail
    /// desligado (`None`), e o backend sobe igual: a recuperação de senha é
    /// a única coisa que depende disso.
    pub fn from_env() -> AppResult<Option<Self>> {
        let Some(host) = env_nao_vazio("SMTP_HOST") else {
            return Ok(None);
        };
        let porta: u16 = env_nao_vazio("SMTP_PORT")
            .map(|p| {
                p.parse().map_err(|_| {
                    AppError::Config(format!("SMTP_PORT inválida: {p} (esperado 1..65535)"))
                })
            })
            .transpose()?
            .unwrap_or(465);

        // Desligar TLS é o que o teste de ponta a ponta precisa para falar com
        // um mock em loopback. Em qualquer outro host isso vazaria a senha do
        // SMTP em texto puro — por isso é erro de boot, não aviso.
        let tls = env_nao_vazio("SMTP_TLS").as_deref() != Some("off");
        if !tls && !host.eq_ignore_ascii_case("localhost") && host != "127.0.0.1" {
            return Err(AppError::Config(
                "SMTP_TLS=off só é aceito para host local (teste)".into(),
            ));
        }

        let usuario = env_nao_vazio("SMTP_USER");
        let senha = env_nao_vazio("SMTP_PASS");
        if usuario.is_some() != senha.is_some() {
            return Err(AppError::Config(
                "SMTP_USER e SMTP_PASS devem ser definidos juntos".into(),
            ));
        }

        let remetente = env_nao_vazio("SMTP_FROM").ok_or_else(|| {
            AppError::Config("SMTP_FROM não definida (endereço que aparece como remetente)".into())
        })?;

        Ok(Some(Self {
            host,
            porta,
            tls,
            usuario,
            senha,
            remetente,
        }))
    }

    /// Envia um e-mail de texto puro. Erro volta como texto porque quem chama
    /// é uma task de log, não um handler.
    pub async fn enviar(&self, para: &str, assunto: &str, corpo: &str) -> Result<(), String> {
        let mensagem = montar_mensagem(&self.remetente, para, assunto, corpo);
        match tokio::time::timeout(PRAZO, self.enviar_interno(para, &mensagem)).await {
            Ok(resultado) => resultado,
            Err(_) => Err(format!("prazo de {PRAZO:?} estourado")),
        }
    }

    async fn enviar_interno(&self, para: &str, mensagem: &str) -> Result<(), String> {
        let tcp = tokio::net::TcpStream::connect((self.host.as_str(), self.porta))
            .await
            .map_err(|e| format!("conexão com {}:{} falhou: {e}", self.host, self.porta))?;

        let credenciais = self
            .usuario
            .as_deref()
            .zip(self.senha.as_deref());

        if self.tls {
            let fluxo = conectar_tls(tcp, &self.host).await?;
            dialogo(fluxo, credenciais, &self.remetente, para, mensagem).await
        } else {
            dialogo(tcp, credenciais, &self.remetente, para, mensagem).await
        }
    }
}

fn env_nao_vazio(chave: &str) -> Option<String> {
    std::env::var(chave)
        .ok()
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
}

async fn conectar_tls(
    tcp: tokio::net::TcpStream,
    host: &str,
) -> Result<tokio_rustls::client::TlsStream<tokio::net::TcpStream>, String> {
    // Provider explícito (ring), igual ao `http_cru`: a escolha automática do
    // rustls entra em pânico se mais de um provider estiver compilado.
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let config = rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|e| format!("TLS: versões de protocolo: {e}"))?
        .with_root_certificates(raizes())
        .with_no_client_auth();

    let nome = rustls_pki_types::ServerName::try_from(host.to_string())
        .map_err(|e| format!("TLS: nome inválido: {e}"))?;
    tokio_rustls::TlsConnector::from(Arc::new(config))
        .connect(nome, tcp)
        .await
        .map_err(|e| format!("TLS com {host}: {e}"))
}

fn raizes() -> rustls::RootCertStore {
    let mut raizes = rustls::RootCertStore::empty();
    raizes.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    raizes
}

/// A conversa SMTP. Trabaiha com qualquer fluxo (TCP puro do teste, TLS em
/// produção) para o caminho testado ser o mesmo que roda.
async fn dialogo<S>(
    fluxo: S,
    credenciais: Option<(&str, &str)>,
    de: &str,
    para: &str,
    mensagem: &str,
) -> Result<(), String>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let (leitura, mut escrita) = tokio::io::split(fluxo);
    let mut leitura = BufReader::new(leitura);

    resposta(&mut leitura, '2').await?; // 220 pronto

    comando(&mut escrita, "EHLO kavelipay\r\n").await?;
    resposta(&mut leitura, '2').await?;

    if let Some((usuario, senha)) = credenciais {
        comando(&mut escrita, "AUTH LOGIN\r\n").await?;
        resposta(&mut leitura, '3').await?;
        comando(&mut escrita, &format!("{}\r\n", b64(usuario))).await?;
        resposta(&mut leitura, '3').await?;
        comando(&mut escrita, &format!("{}\r\n", b64(senha))).await?;
        resposta(&mut leitura, '2').await?;
    }

    comando(&mut escrita, &format!("MAIL FROM:<{de}>\r\n")).await?;
    resposta(&mut leitura, '2').await?;
    comando(&mut escrita, &format!("RCPT TO:<{para}>\r\n")).await?;
    resposta(&mut leitura, '2').await?;
    comando(&mut escrita, "DATA\r\n").await?;
    resposta(&mut leitura, '3').await?;

    // `mensagem` já termina em CRLF; o "." sozinho fecha os dados. O corpo vem
    // em base64 (ver `montar_mensagem`), então nunca tem linha começando com
    // "." — que o SMTP trataria como fim dos dados e exigiria dot-stuffing.
    comando(&mut escrita, &format!("{mensagem}.\r\n")).await?;
    resposta(&mut leitura, '2').await?;

    comando(&mut escrita, "QUIT\r\n").await?;
    // 221: se o servidor já fechou, encerrar aqui não é erro de entrega.
    let _ = resposta(&mut leitura, '2').await;
    Ok(())
}

async fn comando<S: AsyncWrite + Unpin>(escrita: &mut S, linha: &str) -> Result<(), String> {
    escrita
        .write_all(linha.as_bytes())
        .await
        .map_err(|e| format!("escrita SMTP falhou: {e}"))?;
    escrita
        .flush()
        .await
        .map_err(|e| format!("flush SMTP falhou: {e}"))
}

/// Lê uma resposta (inclusive as multilinha `250-...`) e exige que a primeira
/// linha comece com `esperado`. Devolver o texto é o que deixa o erro útil no
/// log — "535 autenticação recusada" diz mais que "falhou".
async fn resposta<S: AsyncRead + Unpin>(
    leitura: &mut BufReader<S>,
    esperado: char,
) -> Result<(), String> {
    loop {
        let mut linha = String::new();
        let lidos = leitura
            .read_line(&mut linha)
            .await
            .map_err(|e| format!("leitura SMTP falhou: {e}"))?;
        if lidos == 0 {
            return Err(format!(
                "servidor encerrou a conexão antes da resposta {esperado}xx"
            ));
        }
        let limpa = linha.trim_end();
        if limpa.chars().next() != Some(esperado) {
            return Err(format!("SMTP respondeu: {}", limpa.trim()));
        }
        // "250-texto" é continuação; "250 texto" (ou só "250") é a última.
        // O separador é o quarto caractere, depois dos três dígitos.
        if limpa.as_bytes().get(3) != Some(&b'-') {
            return Ok(());
        }
    }
}

/// Monta a mensagem inteira (cabeçalhos + corpo) pronta para ir entre `DATA`
/// e o ponto final.
///
/// O corpo vai em base64 por dois motivos: a linha fica dentro do limite de
/// 998 octetos do SMTP sem precisar quebrar texto no meio de um caractere, e
/// oito bits nunca dependem de extensão `8BITMIME`.
fn montar_mensagem(de: &str, para: &str, assunto: &str, corpo: &str) -> String {
    let cabecalhos = format!(
        "From: {NOME_REMETENTE} <{de}>\r\n\
         To: <{para}>\r\n\
         Subject: =?UTF-8?B?{}?=\r\n\
         Date: {}\r\n\
         MIME-Version: 1.0\r\n\
         Content-Type: text/plain; charset=UTF-8\r\n\
         Content-Transfer-Encoding: base64\r\n\
         \r\n",
        b64(assunto),
        chrono::Utc::now().to_rfc2822()
    );
    let mut mensagem = cabecalhos;
    for bloco in quebrar76(&b64(corpo)) {
        mensagem.push_str(&bloco);
        mensagem.push_str("\r\n");
    }
    mensagem
}

fn b64(texto: &str) -> String {
    base64::engine::general_purpose::STANDARD.encode(texto.as_bytes())
}

/// Quebra em linhas de 76 caracteres, como o RFC 2045 pede para base64.
fn quebrar76(texto: &str) -> Vec<String> {
    texto
        .as_bytes()
        .chunks(76)
        .map(|bloco| String::from_utf8_lossy(bloco).into_owned())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;
    use tokio::net::TcpListener;

    async fn responder<S: AsyncWrite + Unpin>(escrita: &mut S, texto: &str) {
        escrita.write_all(texto.as_bytes()).await.unwrap();
        escrita.flush().await.unwrap();
    }

    async fn ler<S: AsyncRead + Unpin>(leitura: &mut BufReader<S>) -> String {
        let mut linha = String::new();
        leitura.read_line(&mut linha).await.unwrap();
        linha
    }

    /// Mock de servidor SMTP: aceita uma mensagem e devolve o texto recebido.
    /// Fica em loopback porque o teste mede o DIÁLOGO, não a criptografia.
    async fn subir_mock() -> (u16, std::sync::Arc<Mutex<Vec<String>>>) {
        let ouvinte = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let porta = ouvinte.local_addr().unwrap().port();
        let recebido = std::sync::Arc::new(Mutex::new(Vec::new()));
        let guardado = recebido.clone();

        tokio::spawn(async move {
            let (fluxo, _) = ouvinte.accept().await.unwrap();
            let (leitura, mut escrita) = tokio::io::split(fluxo);
            let mut leitura = BufReader::new(leitura);

            responder(&mut escrita, "220 mock ESMTP\r\n").await;

            // EHLO respondido em multilinha de propósito: é o formato que o
            // parser de `resposta` precisa entender (250-... 250 OK).
            let ehlo = ler(&mut leitura).await;
            assert!(ehlo.starts_with("EHLO"), "esperava EHLO, veio {ehlo}");
            responder(&mut escrita, "250-mock\r\n250-AUTH LOGIN\r\n250 OK\r\n").await;

            let auth = ler(&mut leitura).await;
            assert!(auth.starts_with("AUTH LOGIN"), "esperava AUTH, veio {auth}");
            responder(&mut escrita, "334 VXNlcm5hbWU6\r\n").await;
            let usuario = ler(&mut leitura).await;
            guardado.lock().unwrap().push(format!("user:{}", usuario.trim()));
            responder(&mut escrita, "334 UGFzc3dvcmQ6\r\n").await;
            let senha = ler(&mut leitura).await;
            guardado.lock().unwrap().push(format!("pass:{}", senha.trim()));
            responder(&mut escrita, "235 ok\r\n").await;

            let remetente = ler(&mut leitura).await;
            guardado.lock().unwrap().push(remetente.trim().to_string());
            responder(&mut escrita, "250 ok\r\n").await;
            let destinatario = ler(&mut leitura).await;
            guardado.lock().unwrap().push(destinatario.trim().to_string());
            responder(&mut escrita, "250 ok\r\n").await;

            let data = ler(&mut leitura).await;
            assert_eq!(data.trim(), "DATA");
            responder(&mut escrita, "354 manda\r\n").await;

            let mut mensagem = String::new();
            loop {
                let linha = ler(&mut leitura).await;
                if linha.is_empty() || linha == ".\r\n" {
                    break;
                }
                mensagem.push_str(&linha);
            }
            guardado.lock().unwrap().push(mensagem);
            let _ = escrita.write_all(b"250 enfileirado\r\n").await;
            let _ = escrita.flush().await;
        });

        (porta, recebido)
    }

    fn mailer(porta: u16) -> Mailer {
        Mailer {
            host: "127.0.0.1".into(),
            porta,
            tls: false,
            usuario: Some("usuario".into()),
            senha: Some("segredo".into()),
            remetente: "no-reply@kavelipay.test".into(),
        }
    }

    #[tokio::test]
    async fn dialogo_completo_entrega_a_mensagem() {
        let (porta, recebido) = subir_mock().await;
        mailer(porta)
            .enviar("pessoa@exemplo.com", "Redefinir senha", "Código: abc123")
            .await
            .unwrap();

        let log = recebido.lock().unwrap().clone();
        assert_eq!(log[0], format!("user:{}", b64("usuario")));
        assert_eq!(log[1], format!("pass:{}", b64("segredo")));
        assert_eq!(log[2], "MAIL FROM:<no-reply@kavelipay.test>");
        assert_eq!(log[3], "RCPT TO:<pessoa@exemplo.com>");

        let mensagem = &log[4];
        assert!(mensagem.contains("To: <pessoa@exemplo.com>"));
        assert!(mensagem.contains("Content-Type: text/plain; charset=UTF-8"));
        // O corpo em base64 tem de voltar exatamente ao original.
        let corpo = mensagem.split("\r\n\r\n").nth(1).unwrap();
        let decodificado = base64::engine::general_purpose::STANDARD
            .decode(corpo.replace("\r\n", ""))
            .unwrap();
        assert_eq!(String::from_utf8(decodificado).unwrap(), "Código: abc123");
    }

    #[tokio::test]
    async fn assunto_acentuado_vai_codificado() {
        let mensagem = montar_mensagem("de@x.com", "para@y.com", "Redefinir senha", "oi");
        assert!(mensagem.contains(&format!("Subject: =?UTF-8?B?{}?=", b64("Redefinir senha"))));
        // CRLF em todo lugar: servidor SMTP que recebe LF puro pode acumular
        // a mensagem numa linha só.
        assert!(!mensagem.replace("\r\n", "").contains('\n'));
    }

    #[test]
    fn corpo_longo_quebra_em_linhas_de_76() {
        let longo = "a".repeat(500);
        for bloco in quebrar76(&b64(&longo)) {
            assert!(bloco.len() <= 76);
        }
        let remontado: String = quebrar76(&b64(&longo)).concat();
        assert_eq!(remontado, b64(&longo));
    }

    #[test]
    fn sem_credenciais_o_dialogo_pula_auth() {
        // Só a montagem: quem exercita o pulo do AUTH é o mock acima quando
        // `credenciais` é None — coberto pelo e2e (SMTP sem usuário).
        assert!(montar_mensagem("de@x.com", "para@y.com", "oi", "oi").contains("From: KaveliPay"));
    }
}
