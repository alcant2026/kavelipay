package com.kavelipay.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Faixas de vencimento das contas a pagar, puras.
 *
 * O "hoje" entra como parâmetro: sem isso o teste diria "vence hoje" só na
 * data em que foi escrito, e a regra pareceria quebrada em qualquer outro dia.
 */
class PrazosContaTest {

    private val hoje = LocalDate.of(2026, 11, 21)

    @Test
    fun `parse aceita o formato da API e ignora hora`() {
        assertEquals(LocalDate.of(2026, 11, 25), PrazosConta.parse("2026-11-25"))
        assertEquals(LocalDate.of(2026, 11, 25), PrazosConta.parse("2026-11-25T00:00:00Z"))
        assertNull(PrazosConta.parse("25/11/2026"))
        assertNull(PrazosConta.parse(""))
    }

    @Test
    fun `dias ate conta a diferenca em dias`() {
        assertEquals(0L, PrazosConta.diasAte("2026-11-21", hoje))
        assertEquals(4L, PrazosConta.diasAte("2026-11-25", hoje))
        assertEquals(-3L, PrazosConta.diasAte("2026-11-18", hoje))
        // Mês virado: não é só o dia do mês.
        assertEquals(10L, PrazosConta.diasAte("2026-12-01", hoje))
    }

    @Test
    fun `vencida e o dia anterior a hoje, nunca hoje`() {
        assertFalse(PrazosConta.vencidaA("2026-11-21", hoje))
        assertTrue(PrazosConta.vencidaA("2026-11-20", hoje))
        assertFalse(PrazosConta.vencidaA("2026-11-22", hoje))
        // Data ruim não vira "vencida": a tela trata como não informada.
        assertFalse(PrazosConta.vencidaA("quando der", hoje))
    }

    @Test
    fun `rotulo diz hoje, amanha e a contagem na primeira semana`() {
        assertEquals("Hoje", PrazosConta.rotulo("2026-11-21", hoje))
        assertEquals("Amanhã", PrazosConta.rotulo("2026-11-22", hoje))
        assertEquals("Em 6 dias", PrazosConta.rotulo("2026-11-27", hoje))
    }

    @Test
    fun `depois de uma semana volta para a data`() {
        assertEquals("25/12", PrazosConta.rotulo("2026-12-25", hoje))
        assertEquals("25/12", PrazosConta.rotulo("2027-12-25", hoje))
    }

    @Test
    fun `vencida conta ha quantos dias`() {
        assertEquals("Há 1 dia", PrazosConta.rotulo("2026-11-20", hoje))
        assertEquals("Há 3 dias", PrazosConta.rotulo("2026-11-18", hoje))
    }

    @Test
    fun `rotulo de mes sai em ptBR`() {
        assertEquals("novembro", PrazosConta.rotuloMes("2026-11-05"))
        assertEquals("dezembro", PrazosConta.rotuloMes("2026-12-01"))
    }

    @Test
    fun `lista ordena por vencimento e leva as vencidas na frente`() {
        val lista = listOf("2026-11-25", "2026-11-18", "2026-12-01", "2026-11-21")
        assertEquals(
            listOf("2026-11-18", "2026-11-21", "2026-11-25", "2026-12-01"),
            PrazosConta.ordenadasPorVencimento(lista),
        )
    }

    @Test
    fun `data ruim vai para o fim em vez de estourar`() {
        val lista = listOf("quando der", "2026-11-25")
        assertEquals(listOf("2026-11-25", "quando der"), PrazosConta.ordenadasPorVencimento(lista))
    }
}