package com.kavelipay.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Redução da foto no decode.
 *
 * A regra existe para caber em celular fraco: foto de 1000x1000 decodificada
 * inteira ocupa 4 MB. O teste fixa as bordas que importam — nunca devolver
 * imagem MAIOR que o limite, e nunca reduzir demais a ponto de borrar o card.
 */
class TamanhoImagemTest {

    @Test
    fun `imagem pequena nao e reduzida`() {
        assertEquals(1, fatorAmostragem(480, 320))
    }

    @Test
    fun `imagem no limite nao e reduzida`() {
        assertEquals(1, fatorAmostragem(512, 512))
    }

    @Test
    fun `mil pixels viram quinhentos`() {
        assertEquals(2, fatorAmostragem(1000, 1000))
    }

    @Test
    fun `foto gigante cai em potencia de dois`() {
        // 3000 -> 1500 -> 750 -> 375: para no primeiro passo que cabe em 512.
        assertEquals(8, fatorAmostragem(3000, 3000))
    }

    @Test
    fun `vale o maior lado`() {
        // Retangular: 2000 de largura manda no cálculo, não os 300 de altura.
        assertEquals(4, fatorAmostragem(2000, 300))
    }

    @Test
    fun `nunca devolve lado acima do limite`() {
        for (lado in listOf(513, 700, 1024, 2048, 4096, 8000)) {
            val fator = fatorAmostragem(lado, lado)
            assert(lado / fator <= LADO_MAXIMO_PX) { "$lado/$fator passou de $LADO_MAXIMO_PX" }
        }
    }
}
