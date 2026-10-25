package com.kavelipay.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regras puras de entrada da Caixinha: dinheiro sem `Double`, telefone com
 * DDD. Todo o cálculo (arrecadado, saldo, rendimento) é do servidor — aqui
 * só se valida o que a pessoa digitou.
 */
class CaixinhaRegrasTest {

    @Test
    fun `aceita virgula e ponto e arredonda para centavos`() {
        assertEquals("10.50", CaixinhaRegras.parseValor("10,50")?.toPlainString())
        assertEquals("10.51", CaixinhaRegras.parseValor("10,505")?.toPlainString())
        assertEquals("0.99", CaixinhaRegras.parseValor("0.99")?.toPlainString())
    }

    @Test
    fun `recusa vazio, zero, negativo e texto`() {
        assertNull(CaixinhaRegras.parseValor(""))
        assertNull(CaixinhaRegras.parseValor("0"))
        assertNull(CaixinhaRegras.parseValor("-1"))
        assertNull(CaixinhaRegras.parseValor("abc"))
    }

    @Test
    fun `telefone com ddd vira formato legivel e recusa incompleto`() {
        assertEquals("(11) 98888-7777", CaixinhaRegras.formatarTelefone("11988887777"))
        assertEquals("(11) 3888-7777", CaixinhaRegras.formatarTelefone("(11) 3888-7777"))
        assertNull(CaixinhaRegras.formatarTelefone("98888-7777"))
        assertNull(CaixinhaRegras.formatarTelefone("119888877771"))
    }

    @Test
    fun `telefone para api guarda so digitos`() {
        assertEquals("11988887777", CaixinhaRegras.telefoneParaApi("(11) 98888-7777"))
        assertEquals("1138887777", CaixinhaRegras.telefoneParaApi("11 3888-7777"))
        assertNull(CaixinhaRegras.telefoneParaApi("98888-7777"))
        assertNull(CaixinhaRegras.telefoneParaApi(""))
        assertNull(CaixinhaRegras.telefoneParaApi("119888877771"))
    }
}
