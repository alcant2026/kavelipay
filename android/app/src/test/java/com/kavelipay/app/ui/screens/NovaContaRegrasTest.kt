package com.kavelipay.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regras de data da tela de Nova conta.
 *
 * `POST /bills` é estrito em `YYYY-MM-DD` (422 "due_date deve ser
 * YYYY-MM-DD" fora do formato), mas o campo do app pede `dd/mm/aaaa` — o
 * formato em que o cliente digita. Quem faz a ponte é [dataParaIso], e uma
 * conversão errada aqui vira 422 no meio do cadastro da conta. Regra pura,
 * sem rede: testa como as outras (`DividaRegrasTest`).
 */
class NovaContaRegrasTest {

    @Test
    fun `dd mmmm aaaa vira ISO`() {
        assertEquals("2026-10-13", dataParaIso("13/10/2026"))
        assertEquals("2026-01-01", dataParaIso("01/01/2026"))
        assertEquals("2026-12-31", dataParaIso("31/12/2026"))
    }

    @Test
    fun `ISO colada passa intacta`() {
        // O resto do app já segue "o backend aceita os dois"; colar a data
        // do app de banco (2026-10-13) não pode virar erro.
        assertEquals("2026-10-13", dataParaIso("2026-10-13"))
    }

    @Test
    fun `data que não existe no calendário é rejeitada`() {
        // LocalDate.of valida de verdade: fevereiro não tem 30 dias e
        // descobrir isso aqui é melhor que 422 do backend.
        assertNull(dataParaIso("30/02/2026"))
        assertNull(dataParaIso("31/04/2026"))
        assertNull(dataParaIso("31/13/2026"))
    }

    @Test
    fun `formato parcial ou lixo devolve null`() {
        assertNull(dataParaIso(""))
        assertNull(dataParaIso("13/10"))
        assertNull(dataParaIso("13/10/26"))
        assertNull(dataParaIso("outubro"))
    }

    @Test
    fun `espaço nas bordas não quebra a conversão`() {
        assertEquals("2026-10-13", dataParaIso("  13/10/2026  "))
    }
}
