package com.kavelipay.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regras do QR Code, sem emulador.
 *
 * O bug que motivou estes testes: a conversão de `Color` para ARGB lia os bits
 * baixos de `Color.value`, mas o Compose guarda o ARGB nos bits ALTOS. Preto e
 * branco viravam `0x00000000`, e o QR renderizava um quadrado transparente —
 * a tela parecia vazia e nenhum teste de lógica pegava, porque o problema só
 * existia na hora de desenhar.
 *
 * Por isso [pixelsDoQr] é puro (recebe e devolve `Int`): dá para afirmar que
 * existe pixel escuro de verdade sem `Bitmap` nem Android.
 */
class QrCodeTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val payload = "kavelipay://cobranca/$token"

    @Test
    fun `cor do Compose vira ARGB opaco`() {
        // 0xFF000000 (preto opaco) e 0xFFFFFFFF (branco opaco), como Int.
        assertEquals(0xFF000000.toInt(), Color.Black.toArgb())
        assertEquals(0xFFFFFFFF.toInt(), Color.White.toArgb())
    }

    @Test
    fun `matriz do payload de cobranca e gerada`() {
        val modulos = gerarModulos(payload)
        assertNotNull("ZXing não gerou matriz para $payload", modulos)
        assertTrue((modulos?.width ?: 0) > 0)
        assertEquals(modulos?.width, modulos?.height)
    }

    @Test
    fun `QR tem pixels escuros de verdade`() {
        val modulos = gerarModulos(payload)!!
        val preto = Color.Black.toArgb()
        val branco = Color.White.toArgb()

        val pixels = pixelsDoQr(modulos, escala = 3, fundo = branco, codigo = preto)
        val escuros = pixels.count { it == preto }

        assertTrue("nenhum pixel escuro: QR sairia transparente/branco", escuros > 0)
        val proporcao = escuros.toDouble() / pixels.size
        // QR não é nem 5% nem 95% escuro; se for, a matriz foi lida errada.
        assertTrue("proporção suspeita de módulos escuros: $proporcao", proporcao in 0.10..0.90)
    }

    @Test
    fun `QR tem quiet zone de 4 modulos em volta`() {
        // Sem quiet zone o leitor não acha o código — e a margem de 1 módulo
        // que existia antes só passava em screenshot ideal: na tela escura, o
        // fundo logo em volta é quase preto. Este teste prende a norma
        // (ISO/IEC 18004: 4 módulos) contra o "economizar área útil".
        val modulos = gerarModulos(payload)!!
        val margem = 4

        for (y in 0 until margem) {
            for (x in 0 until modulos.width) {
                assertFalse("módulo escuro na quiet zone (linha $y)", modulos.get(x, y))
                assertFalse(
                    "módulo escuro na quiet zone (coluna $y)",
                    modulos.get(y, x),
                )
            }
        }
        // E a matriz não ficou só de fundo: existe código de verdade.
        assertTrue((margem until modulos.width).any { x -> modulos.get(x, margem) })
    }

    @Test
    fun `cada modulo vira escala x escala pixels`() {
        val modulos = gerarModulos(payload)!!
        val escala = 4
        val pixels = pixelsDoQr(modulos, escala, Color.White.toArgb(), Color.Black.toArgb())

        assertEquals(modulos.width * modulos.height * escala * escala, pixels.size)
    }

    @Test
    fun `escala nunca e menor que 1 pixel por modulo`() {
        val modulos = gerarModulos(payload)!!
        // Tela estreita (ou densidade baixa): mesmo assim o QR não pode colapsar.
        assertEquals(1, escalaPara(modulos, tamanho = 10.dp, densidade = 1f))
        assertTrue(escalaPara(modulos, tamanho = 260.dp, densidade = 2.75f) >= 1)
    }

    @Test
    fun `payload vazio nao vira QR`() {
        assertEquals(null, gerarModulos(""))
        assertEquals(null, gerarModulos("   "))
    }
}
