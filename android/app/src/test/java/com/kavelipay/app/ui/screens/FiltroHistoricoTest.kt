package com.kavelipay.app.ui.screens

import com.kavelipay.app.data.ItemHistorico
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filtros do histórico: busca por texto e por mês, puros e sem UI.
 *
 * A busca é local (o backend não tem `?q=`), então a regra precisa estar
 * presa aqui: se ela errar, o histórico "perde" lançamentos que existem.
 */
class FiltroHistoricoTest {

    private val itens = listOf(
        item("Conta de luz", "BILL", "2026-11-04T12:00:00Z"),
        item("Mercado", "TRANSACTION", "2026-11-08T12:00:00Z"),
        item("Aluguel", "BILL", "2026-10-05T12:00:00Z"),
        item("Cobrança do João", "CHARGE", "2026-09-20T12:00:00Z"),
        item("Sem data", "SPLIT", ""),
    )

    private fun item(titulo: String, kind: String, criadoEm: String) = ItemHistorico(
        kind = kind,
        title = titulo,
        amount = "10.00",
        isSaida = true,
        criadoEm = criadoEm,
    )

    @Test
    fun `busca vazia devolve tudo na ordem original`() {
        assertEquals(itens, FiltroHistorico.aplicar(itens, ""))
        assertEquals(itens, FiltroHistorico.aplicar(itens, "   "))
    }

    @Test
    fun `busca casa com parte do título sem diferenciar caixa`() {
        assertEquals(
            listOf(itens[0]),
            FiltroHistorico.aplicar(itens, "luz"),
        )
        assertEquals(
            listOf(itens[2]),
            FiltroHistorico.aplicar(itens, "ALUGUEL"),
        )
    }

    @Test
    fun `busca casa com o tipo para quem procura por categoria de lançamento`() {
        assertEquals(
            listOf(itens[0], itens[2]),
            FiltroHistorico.aplicar(itens, "bill"),
        )
    }

    @Test
    fun `filtro de mês usa o prefixo YYYY-MM do occurred_at`() {
        assertEquals(
            listOf(itens[0], itens[1]),
            FiltroHistorico.aplicar(itens, "", "2026-11"),
        )
        assertEquals(
            listOf(itens[2]),
            FiltroHistorico.aplicar(itens, "", "2026-10"),
        )
    }

    @Test
    fun `texto e mês se combinam`() {
        assertEquals(
            listOf(itens[1]),
            FiltroHistorico.aplicar(itens, "mercado", "2026-11"),
        )
        assertTrue(FiltroHistorico.aplicar(itens, "luz", "2026-09").isEmpty())
    }

    @Test
    fun `mês que não existe na lista não devolve nada em vez de tudo`() {
        assertTrue(FiltroHistorico.aplicar(itens, "", "2020-01").isEmpty())
    }

    @Test
    fun `item sem data nunca aparece em filtro de mês`() {
        assertTrue(FiltroHistorico.aplicar(itens, "", "2026-11").none { it.title == "Sem data" })
    }

    @Test
    fun `meses presentes vem distintos e do mais recente para o mais antigo`() {
        assertEquals(
            listOf("2026-11", "2026-10", "2026-09"),
            FiltroHistorico.meses(itens),
        )
    }
}