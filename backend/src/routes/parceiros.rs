//! Parceiros: vitrine de indicações de loja (afiliado).
//!
//! LEITURA para o usuário, ESCRITA só para o admin. O cadastro é
//! `POST /admin/parceiros` com o header `X-Admin-Token`: quem controla a
//! vitrine é o dono do app, não o usuário — uma rota de escrita para usuário
//! comum transformaria o KaveliPay num catálogo de terceiro, que é justamente
//! o que o app não é (pessoa física, sem painel de lojista, sem estoque).
//!
//! A compra NÃO passa por aqui. O app abre o destino numa WebView; nada é
//! cobrado, nada é registrado no ledger, e nenhum valor desta tabela vira
//! saldo ou despesa. Se a pessoa comprar, quem sabe disso é a loja — não nós.
//!
//! Autenticado de propósito: a vitrine só existe dentro do app logado, e
//! assim o endpoint não vira uma URL pública de propaganda raspável.
//!
//! ## Por que o admin manda link e o servidor vai buscar título
//!
//! Pedir o link colado é o fluxo que não erra. Título e imagem, quando não
//! vêm no corpo, são lidos do `og:title`/`og:image` da própria página. Isso
//! funciona na maioria das lojas e falha no Mercado Livre, que bloqueia
//! leitura automática — por isso a falha é explícita (422 pedindo o título) em
//! vez de card sem título ou título inventado.

use std::time::Duration;

use axum::Json;
use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, StatusCode};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::auth::AuthUser;
use crate::error::{AppError, AppResult};
use crate::state::AppState;

/// Quantas ofertas o app pede por página.
pub const PAGINA_PADRAO: i64 = 10;

/// Teto de página. Acima disso a vitrine vira download e o app não usa.
const PAGINA_MAXIMA: i64 = 50;

const TITULO_MAX: usize = 120;
const LOJA_MAX: usize = 40;

#[derive(Serialize)]
pub struct ParceiroResponse {
    pub id: Uuid,
    pub loja: String,
    pub titulo: String,
    /// Pode ser nulo: nem toda loja publica `og:image`. O app desenha o ícone
    /// de loja nesse caso — melhor que exigir imagem e cadastrar URL quebrada.
    pub imagem_url: Option<String>,
    pub destino_url: String,
}

#[derive(Serialize)]
pub struct PaginaParceiros {
    pub itens: Vec<ParceiroResponse>,
    /// Total de ofertas ATIVAS, não o tamanho da página: é o que permite ao
    /// app dizer "tem mais" sem pedir uma página vazia para descobrir.
    pub total: i64,
    pub limit: i64,
    pub offset: i64,
}

#[derive(Deserialize)]
pub struct Paginacao {
    pub limit: Option<i64>,
    pub offset: Option<i64>,
}

/// `GET /parceiros?limit=10&offset=0` — ativas, mais novas primeiro.
///
/// Sem preço: ver o cabeçalho de `migrations/0011` para por que o campo não
/// existe. O app mostra "ver preço na loja" e o número verdadeiro fica sempre
/// no destino, onde não envelhece calado.
pub async fn list(
    State(state): State<AppState>,
    Query(q): Query<Paginacao>,
    _auth: AuthUser,
) -> AppResult<Json<PaginaParceiros>> {
    let limit = q.limit.unwrap_or(PAGINA_PADRAO).clamp(1, PAGINA_MAXIMA);
    let offset = q.offset.unwrap_or(0).max(0);

    // Sem filtro por `user_id`: esta tabela não tem dono. Ela não guarda dado
    // de ninguém — é a mesma lista para todo mundo, como um banner.
    let total: i64 = sqlx::query_scalar("SELECT count(*) FROM parceiros_ofertas WHERE ativo")
        .fetch_one(&state.db)
        .await?;

    let linhas = sqlx::query_as::<_, (Uuid, String, String, Option<String>, String)>(
        "SELECT id, loja, titulo, imagem_url, destino_url
           FROM parceiros_ofertas
          WHERE ativo
          ORDER BY criado_em DESC, id DESC
          LIMIT $1 OFFSET $2",
    )
    .bind(limit)
    .bind(offset)
    .fetch_all(&state.db)
    .await?;

    Ok(Json(PaginaParceiros {
        itens: linhas
            .into_iter()
            .map(
                |(id, loja, titulo, imagem_url, destino_url)| ParceiroResponse {
                    id,
                    loja,
                    titulo,
                    imagem_url,
                    destino_url,
                },
            )
            .collect(),
        total,
        limit,
        offset,
    }))
}

#[derive(Deserialize)]
pub struct NovaOferta {
    /// O link que o admin colou. É o único campo obrigatório.
    pub link: String,
    /// Opcional: quando ausente, é lido do `og:title` da página.
    #[serde(default)]
    pub titulo: Option<String>,
    /// Opcional: quando ausente, é lida do `og:image` da página.
    #[serde(default)]
    pub imagem: Option<String>,
    /// Opcional: quando ausente, é deduzida do domínio do link.
    #[serde(default)]
    pub loja: Option<String>,
}

/// `POST /admin/parceiros` — cadastra a oferta e ela já aparece no app.
///
/// Idempotente no link: reenviar o mesmo `destino_url` de uma oferta ATIVA dá
/// 409 (não duplica card); reenviar o de uma oferta desativada a reativa, com
/// os dados novos.
pub async fn criar(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(corpo): Json<NovaOferta>,
) -> AppResult<(StatusCode, Json<ParceiroResponse>)> {
    exigir_admin(&state, &headers)?;

    let link = corpo.link.trim().to_string();
    // `host_do_link` só devolve algo para https:// — http puro (mixed content
    // na WebView) e esquemas como `javascript:` morrem aqui, antes do banco.
    let host = host_do_link(&link).ok_or_else(|| {
        AppError::Validation(
            "o link precisa começar com https:// (http puro e esquemas como \
             javascript: ficam fora)"
                .into(),
        )
    })?;

    let mut titulo = limpar(corpo.titulo);
    let mut imagem = limpar(corpo.imagem);
    let loja = limpar(corpo.loja);

    if let Some(url) = &imagem {
        if !url.starts_with("https://") {
            return Err(AppError::Validation(
                "a imagem precisa começar com https://".into(),
            ));
        }
    }

    // Só busca a página quando falta algo. Admin que manda tudo não espera
    // rede nenhuma — e o Mercado Livre, que bloqueia bot, não atrasa um
    // cadastro já completo.
    if titulo.is_none() || imagem.is_none() {
        let meta = buscar_metadados(&link).await;
        titulo = titulo.or(meta.titulo);
        imagem = imagem.or(meta.imagem);
    }

    let titulo = titulo.ok_or_else(|| {
        AppError::Validation(
            "não consegui ler o título desse link (a loja pode bloquear leitura \
             automática). Mande `titulo` junto com o `link`."
                .into(),
        )
    })?;

    if titulo.chars().count() > TITULO_MAX {
        return Err(AppError::Validation(format!(
            "o título tem mais de {TITULO_MAX} caracteres; ele precisa caber em \
             duas linhas de card"
        )));
    }

    let loja = loja.unwrap_or_else(|| nome_da_loja(&host));
    if loja.chars().count() > LOJA_MAX {
        return Err(AppError::Validation(format!(
            "o nome da loja tem mais de {LOJA_MAX} caracteres"
        )));
    }

    // Link já cadastrado: reativa se estava fora, recusa se está na tela.
    let existente: Option<(Uuid, bool)> = sqlx::query_as(
        "SELECT id, ativo FROM parceiros_ofertas WHERE destino_url = $1",
    )
    .bind(&link)
    .fetch_optional(&state.db)
    .await?;

    let linha = match existente {
        Some((_id, true)) => {
            return Err(AppError::Conflict(
                "esse link já está na vitrine; desative a oferta antes de \
                 cadastrar de novo"
                    .into(),
            ));
        }
        Some((id, false)) => {
            sqlx::query_as::<_, (Uuid, String, String, Option<String>, String)>(
                "UPDATE parceiros_ofertas
                    SET ativo = TRUE, loja = $2, titulo = $3, imagem_url = $4,
                        criado_em = now()
                  WHERE id = $1
              RETURNING id, loja, titulo, imagem_url, destino_url",
            )
            .bind(id)
            .bind(&loja)
            .bind(&titulo)
            .bind(&imagem)
            .fetch_one(&state.db)
            .await?
        }
        None => {
            sqlx::query_as::<_, (Uuid, String, String, Option<String>, String)>(
                "INSERT INTO parceiros_ofertas (loja, titulo, imagem_url, destino_url)
                 VALUES ($1, $2, $3, $4)
                 RETURNING id, loja, titulo, imagem_url, destino_url",
            )
            .bind(&loja)
            .bind(&titulo)
            .bind(&imagem)
            .bind(&link)
            .fetch_one(&state.db)
            .await?
        }
    };

    let (id, loja, titulo, imagem_url, destino_url) = linha;
    Ok((
        StatusCode::CREATED,
        Json(ParceiroResponse {
            id,
            loja,
            titulo,
            imagem_url,
            destino_url,
        }),
    ))
}

/// `DELETE /admin/parceiros/{id}` — desativa (não apaga).
///
/// Desativar em vez de apagar: o link continua único (não dá para recadastrar
/// o mesmo produto em duplicata) e a linha serve de histórico do que já foi
/// oferecido. O app nunca vê oferta inativa.
pub async fn desativar(
    State(state): State<AppState>,
    Path(id): Path<Uuid>,
    headers: HeaderMap,
) -> AppResult<StatusCode> {
    exigir_admin(&state, &headers)?;

    let resultado = sqlx::query("UPDATE parceiros_ofertas SET ativo = FALSE WHERE id = $1 AND ativo")
        .bind(id)
        .execute(&state.db)
        .await?;

    if resultado.rows_affected() == 0 {
        return Err(AppError::NotFound("oferta"));
    }
    Ok(StatusCode::NO_CONTENT)
}

// ── Porta do admin ──────────────────────────────────────────────────────────

/// Token ausente na config = rota desligada (503). Token errado = 401.
///
/// A comparação é em tempo constante: comparar com `==` vaza, pelo tempo de
/// resposta, quantos caracteres do começo estão certos.
fn exigir_admin(state: &AppState, headers: &HeaderMap) -> AppResult<()> {
    let esperado = state.admin_token.as_deref().ok_or(AppError::Unavailable)?;

    let recebido = headers
        .get("x-admin-token")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");

    if !tempo_constante_igual(recebido.as_bytes(), esperado.as_bytes()) {
        return Err(AppError::Unauthorized);
    }
    Ok(())
}

fn tempo_constante_igual(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    a.iter()
        .zip(b)
        .fold(0u8, |acc, (x, y)| acc | (x ^ y))
        == 0
}

fn limpar(valor: Option<String>) -> Option<String> {
    valor
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
}

// ── Link: validação e nome da loja ──────────────────────────────────────────

/// Domínio (sem `www.`) de um link https. `None` para qualquer outra coisa.
fn host_do_link(link: &str) -> Option<String> {
    let sem_esquema = link.strip_prefix("https://")?;

    let host = sem_esquema.split(['/', '?', '#']).next()?;
    // userinfo (`https://user:senha@host/`) e porta (`host:8443`) não são
    // parte do domínio.
    let host = host.rsplit('@').next()?;
    let host = host.split(':').next()?;

    let host = host.trim_start_matches("www.").to_lowercase();
    if host.is_empty() || !host.contains('.') {
        return None;
    }
    Some(host)
}

/// Nome de exibição a partir do domínio.
///
/// Curadoria só para as lojas que o app de fato usa; o resto vira o rótulo do
/// domínio ("lojaqualquer.com.br" → "Lojaqualquer"), que é honesto e cobre
/// qualquer loja nova sem precisar de deploy.
fn nome_da_loja(host: &str) -> String {
    const CONHECIDAS: &[(&str, &str)] = &[
        ("mercadolivre.com.br", "Mercado Livre"),
        ("mercadolivre.com", "Mercado Livre"),
        ("produto.mercadolivre.com.br", "Mercado Livre"),
        ("amazon.com.br", "Amazon"),
        ("amazon.com", "Amazon"),
        ("shopee.com.br", "Shopee"),
        ("shopee.com", "Shopee"),
        ("aliexpress.com", "AliExpress"),
        ("magazineluiza.com.br", "Magalu"),
        ("magalu.com", "Magalu"),
        ("americanas.com.br", "Americanas"),
        ("casasbahia.com.br", "Casas Bahia"),
        ("nike.com.br", "Nike"),
        ("adidas.com.br", "Adidas"),
    ];

    for (dominio, nome) in CONHECIDAS {
        if host == *dominio || host.ends_with(&format!(".{dominio}")) {
            return (*nome).to_string();
        }
    }

    // "loja.exemplo.com.br" → "Exemplo" e "outra.com" → "Outra": o rótulo é o
    // pedaço antes do sufixo, e `.com.br` tem dois pedaços de sufixo (sem
    // tratar isso, "lojaqualquer.com.br" viraria "Com").
    let partes: Vec<&str> = host.split('.').collect();
    let n = partes.len();
    let rotulo = if n >= 3 && matches!(partes[n - 2], "com" | "net" | "org" | "store") {
        partes[n - 3]
    } else if n >= 2 {
        partes[n - 2]
    } else {
        host
    };

    let mut chars = rotulo.chars();
    match chars.next() {
        Some(primeiro) => primeiro.to_uppercase().collect::<String>() + chars.as_str(),
        None => host.to_string(),
    }
}

// ── Leitura de og:title / og:image ──────────────────────────────────────────

#[derive(Default)]
struct Metadados {
    titulo: Option<String>,
    imagem: Option<String>,
}

/// Busca a página e extrai título/imagem. Silencioso por natureza: qualquer
/// falha (timeout, 403, página sem meta) vira `Metadados` vazio e quem decide
/// o que fazer é o chamador — que responde 422 pedindo o título.
async fn buscar_metadados(url: &str) -> Metadados {
    let cliente = match reqwest::Client::builder()
        .timeout(Duration::from_secs(6))
        .user_agent("KaveliPayBot/1.0 (+vitrine de parceiros)")
        .build()
    {
        Ok(c) => c,
        Err(_) => return Metadados::default(),
    };

    let resposta = match cliente.get(url).send().await {
        Ok(r) => r,
        Err(_) => return Metadados::default(),
    };
    if !resposta.status().is_success() {
        return Metadados::default();
    }

    let corpo = match resposta.text().await {
        Ok(c) => c,
        Err(_) => return Metadados::default(),
    };
    // Meta tag mora no `<head>`. 512 KB é folgado para o head e impede que um
    // link para um arquivo enorme trave o handler.
    let corpo: String = corpo.chars().take(512 * 1024).collect();

    let titulo = extrair_meta(&corpo, "og:title").or_else(|| tag_title(&corpo));
    let imagem = extrair_meta(&corpo, "og:image")
        .filter(|url| url.starts_with("https://"));

    Metadados { titulo, imagem }
}

/// Procura `<meta … og:title … content="…">`, aceitando a ordem de atributos
/// que a loja quiser (as duas ordens aparecem na prática).
fn extrair_meta(html: &str, propriedade: &str) -> Option<String> {
    let mut resto = html;

    while let Some(pos) = resto.find("<meta") {
        resto = &resto[pos + "<meta".len()..];
        let fim = resto.find('>')?;
        let tag = &resto[..fim];
        resto = &resto[fim..];

        if !tag.contains(propriedade) {
            continue;
        }
        let valor = atributo(tag, "content")?;
        let valor = decodificar_entidades(&valor);
        let valor = valor.trim();
        if !valor.is_empty() {
            return Some(valor.to_string());
        }
    }
    None
}

fn atributo(tag: &str, nome: &str) -> Option<String> {
    for aspas in ['"', '\''] {
        let procurado = format!("{nome}={aspas}");
        if let Some(inicio) = tag.find(&procurado) {
            let depois = &tag[inicio + procurado.len()..];
            if let Some(fim) = depois.find(aspas) {
                return Some(depois[..fim].to_string());
            }
        }
    }
    None
}

fn tag_title(html: &str) -> Option<String> {
    let inicio = html.find("<title")?;
    let abre = inicio + html[inicio..].find('>')? + 1;
    let fecha = html[abre..].find("</title>")?;
    let valor = decodificar_entidades(&html[abre..abre + fecha]).trim().to_string();
    if valor.is_empty() { None } else { Some(valor) }
}

/// Só as entidades que aparecem em título de produto. Não é um decoder de
/// HTML completo de propósito: nome de loja com `&eacute;` é raro e um parser
/// pela metade seria pior que nenhum.
fn decodificar_entidades(texto: &str) -> String {
    texto
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn so_https_tem_host() {
        assert_eq!(
            host_do_link("https://www.mercadolivre.com.br/produto/p/MLB1"),
            Some("mercadolivre.com.br".to_string())
        );
        assert_eq!(host_do_link("http://loja.com/x"), None);
        assert_eq!(host_do_link("javascript:alert(1)"), None);
        assert_eq!(host_do_link("https://sem-ponto/x"), None);
    }

    #[test]
    fn porta_e_userinfo_nao_entram_no_host() {
        assert_eq!(
            host_do_link("https://user:senha@loja.com.br:8443/p?x=1"),
            Some("loja.com.br".to_string())
        );
    }

    #[test]
    fn loja_conhecida_tem_nome_bonito() {
        assert_eq!(nome_da_loja("mercadolivre.com.br"), "Mercado Livre");
        assert_eq!(nome_da_loja("produto.mercadolivre.com.br"), "Mercado Livre");
        assert_eq!(nome_da_loja("www.amazon.com.br".trim_start_matches("www.")), "Amazon");
    }

    #[test]
    fn loja_desconhecida_usa_o_dominio() {
        assert_eq!(nome_da_loja("lojaqualquer.com.br"), "Lojaqualquer");
        assert_eq!(nome_da_loja("outra.com"), "Outra");
    }

    #[test]
    fn og_title_com_content_depois_da_propriedade() {
        let html = r#"<html><head><meta property="og:title" content="Fone Bluetooth" /></head></html>"#;
        assert_eq!(extrair_meta(html, "og:title").as_deref(), Some("Fone Bluetooth"));
    }

    #[test]
    fn og_title_com_content_antes_da_propriedade() {
        let html = r#"<meta content='Cafeteira 220v' property='og:title'>"#;
        assert_eq!(extrair_meta(html, "og:title").as_deref(), Some("Cafeteira 220v"));
    }

    #[test]
    fn primeira_meta_que_nao_e_og_e_ignorada() {
        let html = r#"<meta name="description" content="x"><meta property="og:image" content="https://x/y.png">"#;
        assert_eq!(
            extrair_meta(html, "og:image").as_deref(),
            Some("https://x/y.png")
        );
    }

    #[test]
    fn entidades_do_titulo_sao_decodificadas() {
        let html = r#"<meta property="og:title" content="Camisa &quot;dry fit&quot; &amp; shorts">"#;
        assert_eq!(
            extrair_meta(html, "og:title").as_deref(),
            Some("Camisa \"dry fit\" & shorts")
        );
    }

    #[test]
    fn tag_title_e_o_fallback() {
        let html = "<html><head><title>  Mesa 120x60  </title></head>";
        assert_eq!(tag_title(html).as_deref(), Some("Mesa 120x60"));
    }

    #[test]
    fn comparacao_de_token_e_estrita() {
        assert!(tempo_constante_igual(b"abc", b"abc"));
        assert!(!tempo_constante_igual(b"abc", b"abd"));
        assert!(!tempo_constante_igual(b"abc", b"ab"));
    }
}
