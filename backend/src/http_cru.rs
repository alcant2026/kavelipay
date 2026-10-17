//! HTTP/1.1 cru — só para a fonte que recusa cliente normal.
//!
//! O statusinvest fica atrás de um Cloudflare que devolve 403 ("Just a
//! moment…") para requisição HTTP/1.1 com nome de header em minúsculo.
//! Verificado ao vivo em 2026-10-17, mesma URL e mesmo momento: `curl` com
//! `User-Agent:` responde 200, com `user-agent:` responde 403 (e HTTP/2 —
//! onde todo nome é minúsculo por especificação — também 403, além do
//! fingerprint de h2 ser recusado). O `reqwest`/`hyper` não tem como fugir
//! disso: o tipo `HeaderName` do crate `http` normaliza para minúsculo na
//! construção. Por isso este módulo escreve o pedido na mão: TCP, TLS
//! (rustls, o mesmo provider do resto do backend) e request line + headers
//! em HTTP/1.1.
//!
//! O escopo é de propósito mínimo — GET, sem redirect, sem cookie, sem
//! `Accept-Encoding` — porque é tudo o que a fonte precisa: um JSON público,
//! uma vez por cadastro de ativo. Qualquer coisa fora do esperado vira
//! `None` com aviso no log; nunca erro para a tela. O contrato de "sugestão
//! é conforto" é o mesmo do `cotacao.rs`.
//!
//! **Isto não é um cliente HTTP de uso geral.** É uma saída de emergência
//! para uma fonte específica, documentada como não oficial: se o site mudar
//! de formato ou apertar o Cloudflare, o campo simplesmente deixa de ser
//! preenchido.

use std::sync::Arc;
use std::time::Duration;

/// Tempo máximo da requisição inteira (conexão + TLS + corpo). Sugestão de
/// formulário não pode prender a tela: estourou, desiste.
const PRAZO: Duration = Duration::from_secs(8);

/// Teto do corpo lido. O JSON de proventos tem dezenas de KB; 2 MiB cobre
/// folgado e evita que uma resposta errada coma memória.
const LIMITE_CORPO: usize = 2 * 1024 * 1024;

/// Identificação de navegador — o mesmo motivo do `User-Agent` do
/// `CotacaoClient`: a fonte recusa cliente que se anuncia como robô.
const AGENTE: &str = "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0";

/// GET em `url` e devolve o corpo como texto, ou `None` se qualquer etapa
/// falhar (DNS, TLS, status ≠ 200, corpo ilegível).
pub async fn get(url: &str) -> Option<String> {
    match tokio::time::timeout(PRAZO, get_interno(url)).await {
        Ok(resposta) => resposta,
        Err(_) => {
            tracing::warn!(url, "http cru: prazo estourado");
            None
        }
    }
}

async fn get_interno(url: &str) -> Option<String> {
    let alvo = url::Url::parse(url).ok()?;
    let host = alvo.host_str()?.to_string();
    let porta = alvo.port_or_known_default()?;
    let caminho = match alvo.query() {
        Some(query) => format!("{}?{query}", alvo.path()),
        None => alvo.path().to_string(),
    };

    // Cabeçalhos com nome capitalizado — é exatamente o que o Cloudflare
    // exige (ver o cabeçalho deste módulo). `Connection: close` faz o
    // servidor fechar no fim do corpo: o fim da leitura é o fim da resposta,
    // sem precisar confiar em `Content-Length`.
    let pedido = format!(
        "GET {caminho} HTTP/1.1\r\n\
         Host: {host}\r\n\
         User-Agent: {AGENTE}\r\n\
         Accept: application/json, text/plain, */*\r\n\
         Accept-Language: pt-BR,pt;q=0.9,en;q=0.8\r\n\
         Connection: close\r\n\r\n"
    );

    let tcp = tokio::net::TcpStream::connect((host.as_str(), porta))
        .await
        .map_err(|e| tracing::warn!(url, "http cru: conexão falhou: {e}"))
        .ok()?;

    let bruto = match alvo.scheme() {
        // Os testes apontam para um mock em `http://127.0.0.1:porta` — o
        // resto do caminho (pedido, leitura, parse) é o mesmo do TLS.
        "https" => enviar_tls(tcp, &host, pedido).await?,
        "http" => enviar(tcp, pedido).await?,
        outro => {
            tracing::warn!(url, scheme = outro, "http cru: esquema não suportado");
            return None;
        }
    };

    resposta_para_corpo(&bruto)
}

async fn enviar_tls(tcp: tokio::net::TcpStream, host: &str, pedido: String) -> Option<Vec<u8>> {
    // Provider explícito (ring, o mesmo que o reqwest usa aqui): evita a
    // escolha automática de provider do rustls, que entra em pânico se mais
    // de um estiver habilitado no binário.
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let config = rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .ok()?
        .with_root_certificates(raizes_do_sistema())
        .with_no_client_auth();

    let nome = rustls_pki_types::ServerName::try_from(host.to_string()).ok()?;
    let conector = tokio_rustls::TlsConnector::from(Arc::new(config));
    let fluxo = conector
        .connect(nome, tcp)
        .await
        .map_err(|e| tracing::warn!(host, "http cru: TLS falhou: {e}"))
        .ok()?;

    enviar(fluxo, pedido).await
}

/// Raízes de confiança do webpki (as mesmas que o reqwest/rustls usam por
/// padrão) — não dependemos do store do sistema.
fn raizes_do_sistema() -> rustls::RootCertStore {
    let mut raizes = rustls::RootCertStore::empty();
    raizes.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    raizes
}

/// Escreve o pedido e lê até o servidor fechar (`Connection: close`).
async fn enviar<S>(mut fluxo: S, pedido: String) -> Option<Vec<u8>>
where
    S: tokio::io::AsyncReadExt + tokio::io::AsyncWriteExt + Unpin,
{
    fluxo.write_all(pedido.as_bytes()).await.ok()?;
    fluxo.flush().await.ok()?;

    let mut bruto = Vec::new();
    let mut bloco = [0u8; 8192];
    loop {
        match fluxo.read(&mut bloco).await {
            Ok(0) => break,
            Ok(lidos) => {
                bruto.extend_from_slice(&bloco[..lidos]);
                if bruto.len() > LIMITE_CORPO {
                    tracing::warn!("http cru: corpo acima do limite; descartando");
                    return None;
                }
            }
            Err(e) => {
                tracing::warn!("http cru: leitura falhou: {e}");
                return None;
            }
        }
    }
    Some(bruto)
}

/// Separa cabeçalho e corpo, exige 200 e devolve o corpo como texto. Corpo
/// com `Transfer-Encoding: chunked` é remontado; `Content-Encoding: gzip`
/// (não pedimos) é recusado em vez de virar lixo — não temos como descomprimir
/// sem trazer mais uma dependência.
fn resposta_para_corpo(bruto: &[u8]) -> Option<String> {
    let corte = encontrar(bruto, b"\r\n\r\n")?;
    let cabecalho = String::from_utf8_lossy(&bruto[..corte]).to_ascii_lowercase();
    let status: u16 = cabecalho
        .lines()
        .next()?
        .split_whitespace()
        .nth(1)?
        .parse()
        .ok()?;

    if status != 200 {
        tracing::warn!(status, "http cru: resposta não-200");
        return None;
    }
    if cabecalho.contains("content-encoding: gzip") {
        tracing::warn!("http cru: resposta comprimida; descartando");
        return None;
    }

    let corpo = &bruto[corte + 4..];
    let corpo = if cabecalho.contains("transfer-encoding: chunked") {
        deschunk(corpo)?
    } else {
        corpo.to_vec()
    };

    match String::from_utf8(corpo) {
        Ok(texto) => Some(texto),
        Err(_) => {
            tracing::warn!("http cru: corpo não é UTF-8");
            None
        }
    }
}

/// Remonta um corpo em `Transfer-Encoding: chunked`: cada bloco vem com o
/// tamanho em hexadecimal, CRLF, os bytes, CRLF — e o bloco de tamanho 0
/// fecha. Formato inesperado no meio = `None` (melhor não sugerir nada do
/// que sugerir data de um corpo mal lido).
fn deschunk(corpo: &[u8]) -> Option<Vec<u8>> {
    let mut saida = Vec::new();
    let mut resto = corpo;

    loop {
        let fim = encontrar(resto, b"\r\n")?;
        let linha = std::str::from_utf8(&resto[..fim]).ok()?;
        // O tamanho pode vir com extensão de chunk depois de `;`.
        let tamanho = usize::from_str_radix(linha.split(';').next()?.trim(), 16).ok()?;
        resto = &resto[fim + 2..];

        if tamanho == 0 {
            return Some(saida);
        }
        if resto.len() < tamanho {
            return None;
        }
        saida.extend_from_slice(&resto[..tamanho]);
        // Pula o CRLF que fecha o bloco.
        resto = resto.get(tamanho + 2..)?;
    }
}

fn encontrar(agulha_palheiro: &[u8], agulha: &[u8]) -> Option<usize> {
    agulha_palheiro
        .windows(agulha.len())
        .position(|janela| janela == agulha)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    /// Mock que imita a regra do Cloudflare: só responde 200 se o pedido vier
    /// com `User-Agent:` capitalizado. Devolve o que leu do pedido.
    async fn subir_mock(resposta_ok: &'static str) -> (String, tokio::task::JoinHandle<String>) {
        let ouvinte = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let porta = ouvinte.local_addr().unwrap().port();

        let tarefa = tokio::spawn(async move {
            let (mut fluxo, _) = ouvinte.accept().await.unwrap();
            let mut pedido = Vec::new();
            let mut bloco = [0u8; 4096];
            // Lê até o fim do cabeçalho do pedido.
            loop {
                let lidos = fluxo.read(&mut bloco).await.unwrap();
                if lidos == 0 {
                    break;
                }
                pedido.extend_from_slice(&bloco[..lidos]);
                if encontrar(&pedido, b"\r\n\r\n").is_some() {
                    break;
                }
            }

            let texto = String::from_utf8_lossy(&pedido).to_string();
            // A regra que motivou este módulo: minúsculo toma 403.
            let resposta = if texto.contains("User-Agent: ") {
                resposta_ok.to_string()
            } else {
                "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_string()
            };
            fluxo.write_all(resposta.as_bytes()).await.unwrap();
            fluxo.flush().await.unwrap();
            texto
        });

        (format!("http://127.0.0.1:{porta}"), tarefa)
    }

    #[tokio::test]
    async fn pedido_sai_com_header_capitalizado() {
        // Se este módulo um dia passar a mandar minúsculo (ou a usar um
        // cliente normal), o mock responde 403 e o teste quebra — é a regra
        // do Cloudflare que motivou o arquivo inteiro.
        let (base, tarefa) =
            subir_mock("HTTP/1.1 200 OK\r\nContent-Length: 11\r\nConnection: close\r\n\r\n{\"ok\":true}").await;

        let corpo = get(&format!("{base}/fii/companytickerprovents?ticker=XPML11&chartProventsType=2"))
            .await
            .expect("mock deveria responder 200");

        assert_eq!(corpo, "{\"ok\":true}");
        let pedido = tarefa.await.unwrap();
        assert!(pedido.contains("User-Agent: Mozilla/5.0"), "pedido recebido: {pedido}");
        assert!(pedido.starts_with("GET /fii/companytickerprovents?ticker=XPML11&chartProventsType=2 HTTP/1.1"));
        assert!(pedido.contains("Host: 127.0.0.1"));
        assert!(pedido.contains("Connection: close"));
    }

    #[tokio::test]
    async fn resposta_chunked_e_remontada() {
        let (base, _) = subir_mock(
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n\
             7\r\n{\"a\":1,\r\n\
             1\r\n}\r\n\
             0\r\n\r\n",
        )
        .await;

        assert_eq!(get(&base).await.unwrap(), "{\"a\":1,}");
    }

    #[tokio::test]
    async fn status_diferente_de_200_e_none() {
        let (base, _) = subir_mock("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").await;

        assert!(get(&base).await.is_none());
    }

    #[tokio::test]
    async fn porta_morta_e_none_sem_erro() {
        assert!(get("http://127.0.0.1:1/nada").await.is_none());
    }

    #[test]
    fn deschunk_remonta_blocos_e_ignora_extensao() {
        // Tamanho em hex com extensão de chunk (`;x`) e blocos de tamanhos
        // diferentes — o corpo final é a concatenação.
        let bruto = b"4;ext=1\r\nabcd\r\n3\r\nefg\r\n0\r\n\r\n";
        assert_eq!(deschunk(bruto).unwrap(), b"abcdefg");
    }

    #[test]
    fn deschunk_truncado_e_none() {
        // Bloco que diz 5 e só traz 3 bytes: melhor nada do que lixo.
        assert!(deschunk(b"5\r\nabc").is_none());
        assert!(deschunk(b"zz\r\nabc\r\n").is_none());
    }

    #[test]
    fn resposta_gzip_e_recusada_em_vez_de_virar_lixo() {
        let bruto = b"HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 3\r\nConnection: close\r\n\r\n\x1f\x8b\x08";
        assert!(resposta_para_corpo(bruto).is_none());
    }

    #[test]
    fn resposta_sem_cabecalho_completo_e_none() {
        assert!(resposta_para_corpo(b"HTTP/1.1 200 OK\r\nsem fim de cabecalho").is_none());
        assert!(resposta_para_corpo(b"lixo").is_none());
    }
}
