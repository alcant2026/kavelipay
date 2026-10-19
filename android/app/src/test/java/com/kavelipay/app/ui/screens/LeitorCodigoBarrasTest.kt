package com.kavelipay.app.ui.screens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.google.zxing.BarcodeFormat

/**
 * Regras do leitor único (boleto 1D e QR de cobrança), sem câmera e sem
 * emulador.
 *
 * Puras: o que o scanner aceita como boleto ([normalizarLinhaDigitavel]), o que
 * ele reconhece como cobrança ([tokenDeCobranca]), o veredito por tela
 * ([classificarLeitura]) e a rotação da matriz de luminância
 * ([rotacionarMatriz]) — sem girar pelos graus do sensor, o zxing não lê o
 * código deitado e o leitor "não funciona" no aparelho inteiro.
 */
class LeitorCodigoBarrasTest {

    // ── normalização do código lido ──────────────────────────────────────────

    @Test
    fun `linha digitável de 47 dígitos passa, com espaços fora`() {
        val linha = "34191.79001 01043.510047 91020.150008 8 99990000032145"
        val esperado = "3419179001" + "01043510047" + "91020150008" + "8" + "99990000032145"
        assertEquals(47, esperado.length)
        assertEquals(esperado, normalizarLinhaDigitavel(linha))
    }

    @Test
    fun `código de barras de 44 posições (o que a imagem codifica) também passa`() {
        // O impresso codifica as 44 posições; a linha digitável tem 47.
        // Os dois chegam aqui e os dois são texto livre no backend.
        val barras = "34199999000003214510435100479102015000888889"
        assertEquals(44, barras.length)
        assertEquals(barras, normalizarLinhaDigitavel(barras))
    }

    @Test
    fun `tamanho que não é de boleto é rejeitado`() {
        assertNull(normalizarLinhaDigitavel("34191790010104351004791020150008")) // curto
        assertNull(normalizarLinhaDigitavel("1234567890".repeat(5))) // 50
        assertNull(normalizarLinhaDigitavel(""))
        assertNull(normalizarLinhaDigitavel("QR de cobrança kavelipay://cobranca/123"))
    }

    @Test
    fun `texto com sujeira vira só os dígitos antes de contar`() {
        val barras = "3419 9999 0000 0321 4510 4351 0047 9102 0150 0888 8899"
        assertEquals(44, barras.filter { it.isDigit() }.length)
        assertEquals(44, normalizarLinhaDigitavel(barras)?.length)
    }

    // ── rotação da matriz de luminância ──────────────────────────────────────

    private val original = byteArrayOf(0, 1, 2, 3, 4, 5) // 3 col × 2 lin

    @Test
    fun `90 graus gira no sentido horário e troca as dimensões`() {
        //  0 1 2        3 0
        //  3 4 5   →    4 1
        //               5 2
        val (girada, larg, alt) = rotacionarMatriz(original, 3, 2, 90)
        assertArrayEquals(byteArrayOf(3, 0, 4, 1, 5, 2), girada)
        assertEquals(2, larg)
        assertEquals(3, alt)
    }

    @Test
    fun `180 graus vira a imagem de ponta cabeça`() {
        val (girada, larg, alt) = rotacionarMatriz(original, 3, 2, 180)
        assertArrayEquals(byteArrayOf(5, 4, 3, 2, 1, 0), girada)
        assertEquals(3, larg)
        assertEquals(2, alt)
    }

    @Test
    fun `270 graus gira no sentido anti-horário`() {
        //  0 1 2        2 5
        //  3 4 5   →    1 4
        //               0 3
        val (girada, larg, alt) = rotacionarMatriz(original, 3, 2, 270)
        assertArrayEquals(byteArrayOf(2, 5, 1, 4, 0, 3), girada)
        assertEquals(2, larg)
        assertEquals(3, alt)
    }

    @Test
    fun `graus fora de 90 não mexem na matriz`() {
        val (girada, larg, alt) = rotacionarMatriz(original, 3, 2, 0)
        assertSame(original, girada)
        assertEquals(3, larg)
        assertEquals(2, alt)
        // 360 é o mesmo que 0 — o sensor reporta assim às vezes.
        assertEquals(6, rotacionarMatriz(original, 3, 2, 360).first.size)
    }

    // ── link de cobrança (QR do próprio app) ─────────────────────────────────

    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun `link de cobrança vira token`() {
        assertEquals(token, tokenDeCobranca("kavelipay://cobranca/$token"))
        // Esquema/host em caixa alta e token em caixa alta: o Android não
        // garante a caixa do que veio de um QR.
        assertEquals(token, tokenDeCobranca("  KAVELIPAY://COBRANCA/${token.uppercase()}  "))
    }

    @Test
    fun `query e fragmento antigos não atrapalham`() {
        // QR de versão anterior carregava `?valor=`; o token continua o mesmo.
        assertEquals(token, tokenDeCobranca("kavelipay://cobranca/$token?valor=23.00#x"))
    }

    @Test
    fun `link torto é recusado em vez de abrir tela com 404`() {
        assertNull(tokenDeCobranca(""))
        assertNull(tokenDeCobranca("kavelipay://cobranca/"))
        assertNull(tokenDeCobranca("kavelipay://cobranca/abc"))
        assertNull(tokenDeCobranca("kavelipay://cobranca/${token}0")) // 33 hex
        assertNull(tokenDeCobranca("kavelipay://cobranca/${"g".repeat(32)}"))
        assertNull(tokenDeCobranca("https://cobranca/$token"))
        assertNull(tokenDeCobranca("kavelipay://outra/$token"))
        assertNull(tokenDeCobranca("kavelipay://cobranca/../../etc/passwd"))
    }

    // ── formatos que o decodificador procura ─────────────────────────────────

    @Test
    fun `a mesma camera procura boleto e QR na lista unica`() {
        // O bug que este teste prende: a câmera é uma só, mas sem `QR_CODE`
        // na lista o leitor NUNCA acha o QR do app — a tela fica "procurando"
        // para sempre e parece câmera quebrada. E sem os 1D o boleto morre.
        val formatos = formatosDeLeitura()
        assertTrue(formatos.contains(BarcodeFormat.QR_CODE))
        assertTrue(formatos.contains(BarcodeFormat.CODE_128))
        assertTrue(formatos.contains(BarcodeFormat.ITF))
        assertTrue(formatos.contains(BarcodeFormat.CODE_39))
        assertTrue(formatos.contains(BarcodeFormat.EAN_13))
    }

    // ── reconhecer o que foi lido (um leitor, dois formatos) ─────────────────

    private val linhaBoleto = "34191790010104351004791020150008291070026000"

    @Test
    fun `boleto reconhecido e aceito na Nova conta`() {
        assertEquals(
            ResultadoLeitura.Aceito(linhaBoleto),
            classificarLeitura(linhaBoleto, ContextoLeitura.CONTA),
        )
    }

    @Test
    fun `QR de cobranca reconhecido e aceito em Cobrancas`() {
        assertEquals(
            ResultadoLeitura.Aceito(token),
            classificarLeitura("kavelipay://cobranca/$token", ContextoLeitura.COBRANCA),
        )
    }

    @Test
    fun `codigo certo na tela errada nao e invalido, e fora de contexto`() {
        // Reconhecer o tipo é o trabalho do app: quem apontou a câmera não
        // devia saber de antemão o que é boleto e o que é QR. Válido na tela
        // errada merece "isto vai em tal lugar", não "não consegui ler".
        assertEquals(
            ResultadoLeitura.CobrancaForaDeContexto,
            classificarLeitura("kavelipay://cobranca/$token", ContextoLeitura.CONTA),
        )
        assertEquals(
            ResultadoLeitura.BoletoForaDeContexto,
            classificarLeitura(linhaBoleto, ContextoLeitura.COBRANCA),
        )
    }

    @Test
    fun `nem boleto nem cobranca vira nao reconhecido`() {
        assertEquals(
            ResultadoLeitura.NaoReconhecido,
            classificarLeitura("https://exemplo.com", ContextoLeitura.COBRANCA),
        )
        assertEquals(
            ResultadoLeitura.NaoReconhecido,
            classificarLeitura("kavelipay://cobranca/abc", ContextoLeitura.COBRANCA),
        )
        assertEquals(
            ResultadoLeitura.NaoReconhecido,
            classificarLeitura("", ContextoLeitura.CONTA),
        )
    }

    @Test
    fun `mensagem de fora de contexto ensina para onde o codigo vai`() {
        // Tom do gate de review: pt-BR, sem jargão, e dizendo o próximo passo.
        // "Código inválido" seria falso (o código está certo) e inútil.
        val emContas = mensagemLeitura(
            ResultadoLeitura.CobrancaForaDeContexto,
            ContextoLeitura.CONTA,
        )
        assertTrue(emContas.contains("cobrança"))
        assertTrue(emContas.contains("Cobranças"))

        val emNovaConta = mensagemLeitura(
            ResultadoLeitura.BoletoForaDeContexto,
            ContextoLeitura.COBRANCA,
        )
        assertTrue(emNovaConta.contains("boleto"))
        assertTrue(emNovaConta.contains("Nova conta"))
    }
}
