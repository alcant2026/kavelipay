package com.kavelipay.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regra de navegação da WebView da loja.
 *
 * É a fronteira de segurança da única tela do app que roda conteúdo de
 * terceiro, então ela tem teste próprio: um `javascript:` que passasse como
 * "link normal" executaria código dentro do nosso processo.
 */
class NavegacaoWebTest {

    @Test
    fun `https carrega dentro do app`() {
        assertEquals(
            DestinoWeb.DENTRO,
            classificarDestinoWeb("https://www.mercadolivre.com.br/produto/p/MLB123"),
        )
    }

    @Test
    fun `http tambem fica dentro`() {
        assertEquals(DestinoWeb.DENTRO, classificarDestinoWeb("http://exemplo.com.br"))
    }

    @Test
    fun `esquema em maiuscula nao escapa da regra`() {
        assertEquals(DestinoWeb.DENTRO, classificarDestinoWeb("HTTPS://LOJA.COM.BR"))
    }

    @Test
    fun `espacos em volta nao confundem`() {
        assertEquals(DestinoWeb.DENTRO, classificarDestinoWeb("  https://loja.com.br/x  "))
    }

    @Test
    fun `javascript e bloqueado`() {
        assertEquals(DestinoWeb.BLOQUEADO, classificarDestinoWeb("javascript:alert(1)"))
        assertEquals(DestinoWeb.BLOQUEADO, classificarDestinoWeb("JavaScript:alert(1)"))
        assertEquals(DestinoWeb.BLOQUEADO, classificarDestinoWeb(" javascript:void(0)"))
    }

    @Test
    fun `data e file e content sao bloqueados`() {
        assertEquals(DestinoWeb.BLOQUEADO, classificarDestinoWeb("data:text/html,<h1>x</h1>"))
        assertEquals(DestinoWeb.BLOQUEADO, classificarDestinoWeb("file:///etc/passwd"))
        assertEquals(DestinoWeb.BLOQUEADO, classificarDestinoWeb("content://com.android.providers/1"))
        assertEquals(DestinoWeb.BLOQUEADO, classificarDestinoWeb("blob:https://loja.com.br/abc"))
    }

    @Test
    fun `vazio ou nulo e bloqueado`() {
        assertEquals(DestinoWeb.BLOQUEADO, classificarDestinoWeb(null))
        assertEquals(DestinoWeb.BLOQUEADO, classificarDestinoWeb(""))
        assertEquals(DestinoWeb.BLOQUEADO, classificarDestinoWeb("   "))
    }

    @Test
    fun `caminho relativo solto e bloqueado`() {
        assertEquals(DestinoWeb.BLOQUEADO, classificarDestinoWeb("/produto/123"))
        assertEquals(DestinoWeb.BLOQUEADO, classificarDestinoWeb("produto/123"))
    }

    @Test
    fun `esquema de outro app vai para o Android`() {
        assertEquals(DestinoWeb.FORA, classificarDestinoWeb("intent://produto#Intent;scheme=ml;end"))
        assertEquals(DestinoWeb.FORA, classificarDestinoWeb("mercadolivre://produto/MLB123"))
        assertEquals(DestinoWeb.FORA, classificarDestinoWeb("market://details?id=com.mercadolibre"))
        assertEquals(DestinoWeb.FORA, classificarDestinoWeb("tel:+5511999999999"))
        assertEquals(DestinoWeb.FORA, classificarDestinoWeb("mailto:alguem@exemplo.com"))
    }

    @Test
    fun `esquema com caractere invalido e bloqueado`() {
        // "jav ascript:" não é esquema válido; não pode virar "entrega para o
        // Android" por acidente.
        assertEquals(DestinoWeb.BLOQUEADO, classificarDestinoWeb("jav ascript:alert(1)"))
    }
}
