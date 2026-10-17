package com.kavelipay.app.ui.screens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Regras do leitor de código de barras da Nova conta.
 *
 * Duas puras, sem câmera e sem emulador: o que o scanner aceita como boleto
 * ([normalizarLinhaDigitavel]) e a rotação da matriz de luminância
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
}
