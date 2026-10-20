package com.kavelipay.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/**
 * Hora da sessão no fuso do aparelho.
 *
 * A API manda UTC (`...Z`). Fatiar a string como `formatarData` faz mostraria
 * 04:18 para uma sessão aberta às 01:18 em São Paulo — e, numa lista de
 * sessões, é justamente a hora que separa uma da outra. Errar por três horas
 * aqui é errar a única informação que diferencia as linhas.
 */
class MoneyDataHoraTest {

    @Test
    fun `timestamp vira dia e hora no fuso pedido`() {
        assertEquals(
            "20/10 às 04:18",
            formatarDataHora("2026-10-20T04:18:26.657543Z", ZoneId.of("UTC")),
        )
    }

    @Test
    fun `sao paulo recua tres horas`() {
        assertEquals(
            "20/10 às 01:18",
            formatarDataHora("2026-10-20T04:18:26.657543Z", ZoneId.of("America/Sao_Paulo")),
        )
    }

    @Test
    fun `virada de dia respeita o fuso`() {
        // 02:30 UTC do dia 20 é 23:30 do dia 19 em São Paulo: a data muda.
        assertEquals(
            "19/10 às 23:30",
            formatarDataHora("2026-10-20T02:30:00Z", ZoneId.of("America/Sao_Paulo")),
        )
    }

    @Test
    fun `data sem hora cai no formato curto`() {
        assertEquals(
            "20/10",
            formatarDataHora("2026-10-20", ZoneId.of("UTC")),
        )
    }

    @Test
    fun `entrada malformada nao estoura`() {
        assertEquals("lixo", formatarDataHora("lixo", ZoneId.of("UTC")))
    }
}
