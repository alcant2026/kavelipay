package com.kavelipay.app.ui.screens

/**
 * O que fazer com um link clicado dentro da WebView da loja.
 *
 * O pedido do produto é "não sair do app": a compra acontece numa WebView
 * nossa. Mas "não sair" não pode virar "carregar qualquer coisa": a página é
 * de terceiro, e o link dentro dela não é nosso.
 *
 *  - `DENTRO`     → http/https. Fica na WebView.
 *  - `FORA`       → esquema de outro app (`mercadolivre://`, `intent:`,
 *                   `market:`, `tel:`). O Android entrega ao app certo; isso
 *                   NÃO é o usuário indo pro navegador.
 *  - `BLOQUEADO`  → `javascript:`, `data:`, `file:`, `content:`, `blob:`,
 *                   `about:` e URL sem esquema. Nenhum desses tem motivo
 *                   legítimo para aparecer num clique de loja, e vários são
 *                   vetor de execução/leitura local.
 *
 * Função pura de propósito: é a regra de segurança da tela, então ela é
 * testável sem emulador (`NavegacaoWebTest`).
 */
enum class DestinoWeb { DENTRO, FORA, BLOQUEADO }

/** Esquemas que executam código ou alcançam o armazenamento do aparelho. */
private val ESQUEMAS_PROIBIDOS = setOf(
    "javascript",
    "data",
    "file",
    "content",
    "blob",
    "about",
    "chrome",
    "resource",
    "view-source",
)

fun classificarDestinoWeb(url: String?): DestinoWeb {
    val limpo = url?.trim().orEmpty()
    if (limpo.isEmpty()) return DestinoWeb.BLOQUEADO

    val minusculo = limpo.lowercase()
    if (minusculo.startsWith("https://") || minusculo.startsWith("http://")) {
        return DestinoWeb.DENTRO
    }

    // `substringBefore(':')` num texto sem ':' devolve a string inteira; por
    // isso o teste de presença vem antes — caminho relativo solto não é
    // esquema nenhum e não deve ir para o Android como se fosse.
    if (':' !in minusculo) return DestinoWeb.BLOQUEADO

    val esquema = minusculo.substringBefore(':')
    val esquemaValido = esquema.isNotEmpty() && esquema.all {
        it.isLetterOrDigit() || it == '+' || it == '-' || it == '.'
    }
    if (!esquemaValido) return DestinoWeb.BLOQUEADO
    if (esquema in ESQUEMAS_PROIBIDOS) return DestinoWeb.BLOQUEADO

    return DestinoWeb.FORA
}
