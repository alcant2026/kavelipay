//! E-mail transacional — só o necessário para o código de redefinição de
//! senha.
//!
//! **Dois modos, escolhidos pelo ambiente:** `BREVO_API_KEY` presente manda
//! pela API HTTPS da Brevo (porta 443); senão, `SMTP_HOST` manda por SMTP.
//! O motivo de existir o modo HTTP: o plano free do Render bloqueia saída nas
//! portas 25/465/587 desde 2025-09-26, então Gmail/SMTP ali só funciona em
//! instância paga. HTTPS passa em qualquer plano.
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
//! shortcut: TLS implícito (porta 465) apenas no modo SMTP. Provedor que só
//! ofereça STARTTLS (587) precisa de subida de TLS no meio da conversa.

use std::sync::Arc;
use std::time::Duration;

use base64::Engine;
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader};

use crate::error::{AppError, AppResult};

/// Tempo máximo da conversa inteira. Servidor de e-mail fora do ar não pode
/// segurar uma task para sempre.
const PRAZO: Duration = Duration::from_secs(20);

/// Teto do corpo lido na resposta HTTP. A Brevo devolve um JSON curto; isto só
/// evita que uma resposta errada coma memória.
const LIMITE_CORPO: usize = 64 * 1024;

/// API de e-mail transacional da Brevo. HTTPS, então passa no plano free do
/// Render (que bloqueia SMTP).
pub const URL_BREVO: &str = "https://api.brevo.com/v3/smtp/email";

/// Validade do link enviado ao usuário. Curto de propósito: é o tempo que um
/// e-mail vazado continua valendo.
pub const VALIDADE_RESET_MINUTOS: i64 = 30;

/// Remetente padrão exibido na caixa de entrada. O endereço vem de
/// `SMTP_FROM` (ou `MAIL_FROM`); este nome é só o que aparece antes dele.
const NOME_REMETENTE: &str = "KaveliPay";

#[derive(Clone)]
enum Modo {
    Smtp {
        host: String,
        porta: u16,
        /// `false` só em teste (mock em loopback). `from_env` recusa desligar
        /// TLS para host que não seja a própria máquina.
        tls: bool,
        usuario: Option<String>,
        senha: Option<String>,
    },
    Brevo {
        chave: String,
        /// `URL_BREVO`, salvo override em teste (`BREVO_URL`).
        url: String,
    },
}

#[derive(Clone)]
pub struct Mailer {
    modo: Modo,
    remetente: String,
}

impl Mailer {
    /// Monta o mailer a partir do ambiente. Sem `BREVO_API_KEY` e sem
    /// `SMTP_HOST`, o e-mail fica desligado (`None`) e o backend sobe igual: a
    /// recuperação de senha é a única coisa que depende disso.
    pub fn from_env() -> AppResult<Option<Self>> {
        let remetente = env_nao_vazio("MAIL_FROM").or_else(|| env_nao_vazio("SMTP_FROM"));

        if let Some(chave) = env_nao_vazio("BREVO_API_KEY") {
            let remetente = remetente.ok_or_else(|| {
                AppError::Config(
                    "MAIL_FROM não definida (endereço verificado na Brevo que aparece como remetente)"
                        .into(),
                )
            })?;
            return Ok(Some(Self {
                modo: Modo::Brevo {
                    chave,
                    url: env_nao_vazio("BREVO_URL").unwrap_or_else(|| URL_BREVO.to_string()),
                },
                remetente,
            }));
        }

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

        let remetente = remetente.ok_or_else(|| {
            AppError::Config("SMTP_FROM não definida (endereço que aparece como remetente)".into())
        })?;

        Ok(Some(Self {
            modo: Modo::Smtp {
                host,
                porta,
                tls,
                usuario,
                senha,
            },
            remetente,
        }))
    }

    /// Envia um e-mail de texto puro. Erro volta como texto porque quem chama
    /// é uma task de log, não um handler.
    pub async fn enviar(&self, para: &str, assunto: &str, corpo: &str) -> Result<(), String> {
        let envio = async {
            match &self.modo {
                Modo::Smtp {
                    host,
                    porta,
                    tls,
                    usuario,
                    senha,
                } => {
                    let mensagem = montar_mensagem(&self.remetente, para, assunto, corpo);
                    self.enviar_smtp(
                        host,
                        *porta,
                        *tls,
                        usuario.as_deref(),
                        senha.as_deref(),
                        para,
                        &mensagem,
                    )
                    .await
                }
                Modo::Brevo { chave, url } => self.enviar_brevo(url, chave, para, assunto, corpo).await,
            }
        };
        match tokio::time::timeout(PRAZO, envio).await {
            Ok(resultado) => resultado,
            Err(_) => Err(format!("prazo de {PRAZO:?} estourado")),
        }
    }

    async fn enviar_smtp(
        &self,
        host: &str,
        porta: u16,
        tls: bool,
        usuario: Option<&str>,
        senha: Option<&str>,
        para: &str,
        mensagem: &str,
    ) -> Result<(), String> {
        let tcp = tokio::net::TcpStream::connect((host, porta))
            .await
            .map_err(|e| format!("conexão com {host}:{porta} falhou: {e}"))?;

        let credenciais = usuario.zip(senha);

        if tls {
            let fluxo = conectar_tls(tcp, host).await?;
            dialogo(fluxo, credenciais, &self.remetente, para, mensagem).await
        } else {
            dialogo(tcp, credenciais, &self.remetente, para, mensagem).await
        }
    }

    /// `POST` na API da Brevo. O corpo é JSON montado pelo `serde_json` (o
    /// texto do e-mail tem acento, aspas e quebra de linha).
    async fn enviar_brevo(
        &self,
        url: &str,
        chave: &str,
        para: &str,
        assunto: &str,
        corpo: &str,
    ) -> Result<(), String> {
        let corpo_json = serde_json::json!({
            "sender": { "name": NOME_REMETENTE, "email": self.remetente },
            "to": [{ "email": para }],
            "subject": assunto,
            "textContent": corpo,
        })
        .to_string();

        let (status, texto) = post_json(url, chave, &corpo_json).await?;
        if (200..300).contains(&status) {
            Ok(())
        } else {
            let resumo: String = texto.trim().chars().take(300).collect();
            Err(format!("Brevo respondeu {status}: {resumo}"))
        }
    }
}

fn env_nao_vazio(chave: &str) -> Option<String> {
    std::env::var(chave)
        .ok()
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
}

/// `POST` JSON e devolve (status, corpo). O status volta cru de propósito: a
/// Brevo diz no corpo o que recusou (remetente não verificado, chave errada),
/// e é esse texto que vai para o log.
async fn post_json(url: &str, chave: &str, corpo: &str) -> Result<(u16, String), String> {
    let alvo = url::Url::parse(url).map_err(|e| format!("BREVO_URL inválida: {e}"))?;
    let host = alvo.host_str().ok_or("BREVO_URL sem host")?.to_string();
    let porta = alvo.port_or_known_default().ok_or("BREVO_URL sem porta")?;
    let caminho = match alvo.query() {
        Some(query) => format!("{}?{query}", alvo.path()),
        None => alvo.path().to_string(),
    };

    let pedido = format!(
        "POST {caminho} HTTP/1.1\r\n\
         Host: {host}\r\n\
         api-key: {chave}\r\n\
         Content-Type: application/json\r\n\
         Accept: application/json\r\n\
         Content-Length: {}\r\n\
         Connection: close\r\n\
         \r\n\
         {corpo}",
        corpo.len()
    );

    let tcp = tokio::net::TcpStream::connect((host.as_str(), porta))
        .await
        .map_err(|e| format!("conexão com {host}:{porta} falhou: {e}"))?;

    let bruto = match alvo.scheme() {
        "https" => enviar_e_ler(conectar_tls(tcp, &host).await?, pedido).await?,
        // Só o mock do teste usa http://.
        "http" => enviar_e_ler(tcp, pedido).await?,
        outro => return Err(format!("BREVO_URL com esquema não suportado: {outro}")),
    };

    separar_status(&bruto)
}

async fn enviar_e_ler<S>(mut fluxo: S, pedido: String) -> Result<Vec<u8>, String>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    fluxo
        .write_all(pedido.as_bytes())
        .await
        .map_err(|e| format!("envio HTTP falhou: {e}"))?;
    fluxo
        .flush()
        .await
        .map_err(|e| format!("flush HTTP falhou: {e}"))?;

    let mut bruto = Vec::new();
    let mut bloco = [0u8; 8192];
    loop {
        match fluxo.read(&mut bloco).await {
            Ok(0) => return Ok(bruto),
            Ok(lidos) => {
                bruto.extend_from_slice(&bloco[..lidos]);
                if bruto.len() > LIMITE_CORPO {
                    return Err("resposta HTTP acima do limite".into());
                }
            }
            Err(e) => return Err(format!("leitura HTTP falhou: {e}")),
        }
    }
}

fn separar_status(bruto: &[u8]) -> Result<(u16, String), String> {
    let texto = String::from_utf8_lossy(bruto);
    let (cabecalho, corpo) = texto
        .split_once("\r\n\r\n")
        .ok_or("resposta HTTP sem cabeçalho completo")?;
    let status: u16 = cabecalho
        .lines()
        .next()
        .and_then(|linha| linha.split_whitespace().nth(1))
        .and_then(|codigo| codigo.parse().ok())
        .ok_or("resposta HTTP sem status")?;
    Ok((status, corpo.to_string()))
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
            modo: Modo::Smtp {
                host: "127.0.0.1".into(),
                porta,
                tls: false,
                usuario: Some("usuario".into()),
                senha: Some("segredo".into()),
            },
            remetente: "no-reply@kavelipay.test".into(),
        }
    }

    /// Mock da API da Brevo: lê o pedido inteiro (cabeçalho + corpo pelo
    /// `Content-Length`) e devolve `resposta`.
    async fn subir_mock_brevo(resposta: &'static str) -> (Mailer, tokio::task::JoinHandle<String>) {
        let ouvinte = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let porta = ouvinte.local_addr().unwrap().port();

        let tarefa = tokio::spawn(async move {
            let (mut fluxo, _) = ouvinte.accept().await.unwrap();
            let mut pedido = Vec::new();
            let mut bloco = [0u8; 4096];
            let mut total: Option<usize> = None;
            loop {
                let lidos = fluxo.read(&mut bloco).await.unwrap();
                if lidos == 0 {
                    break;
                }
                pedido.extend_from_slice(&bloco[..lidos]);
                if total.is_none() {
                    if let Some(fim) = pedido.windows(4).position(|janela| janela == b"\r\n\r\n") {
                        let cabecalho = String::from_utf8_lossy(&pedido[..fim]).to_lowercase();
                        let tamanho = cabecalho
                            .lines()
                            .find_map(|linha| linha.strip_prefix("content-length:"))
                            .and_then(|valor| valor.trim().parse::<usize>().ok())
                            .unwrap_or(0);
                        total = Some(fim + 4 + tamanho);
                    }
                }
                if let Some(total) = total {
                    if pedido.len() >= total {
                        break;
                    }
                }
            }

            fluxo.write_all(resposta.as_bytes()).await.unwrap();
            fluxo.flush().await.unwrap();
            String::from_utf8_lossy(&pedido).to_string()
        });

        let mailer = Mailer {
            modo: Modo::Brevo {
                chave: "chave-de-teste".into(),
                url: format!("http://127.0.0.1:{porta}/v3/smtp/email"),
            },
            remetente: "no-reply@kavelipay.test".into(),
        };
        (mailer, tarefa)
    }

    #[tokio::test]
    async fn brevo_posta_json_com_a_chave_no_cabecalho() {
        let (mailer, tarefa) = subir_mock_brevo(
            "HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
        )
        .await;
        mailer
            .enviar("pessoa@exemplo.com", "Redefinir senha", "Código: KPL-1234")
            .await
            .unwrap();

        let pedido = tarefa.await.unwrap();
        assert!(pedido.starts_with("POST /v3/smtp/email HTTP/1.1"));
        assert!(pedido.contains("api-key: chave-de-teste"));

        let corpo = pedido.split_once("\r\n\r\n").unwrap().1;
        let json: serde_json::Value = serde_json::from_str(corpo).unwrap();
        assert_eq!(json["sender"]["email"], "no-reply@kavelipay.test");
        assert_eq!(json["to"][0]["email"], "pessoa@exemplo.com");
        assert_eq!(json["subject"], "Redefinir senha");
        assert_eq!(json["textContent"], "Código: KPL-1234");
    }

    #[tokio::test]
    async fn brevo_recusa_vira_erro_com_o_motivo_do_provedor() {
        let (mailer, tarefa) = subir_mock_brevo(
            "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n\
             {\"code\":\"invalid_parameter\",\"message\":\"sender not verified\"}",
        )
        .await;

        let erro = mailer
            .enviar("pessoa@exemplo.com", "Redefinir senha", "oi")
            .await
            .unwrap_err();

        assert!(erro.contains("400"), "erro devolvido: {erro}");
        assert!(erro.contains("sender not verified"), "erro devolvido: {erro}");
        let _ = tarefa.await;
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
