package com.kavelipay.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Prefill da data no padrão que o cliente digita.
 *
 * O rótulo do campo já diz "(dd/mm/aaaa)" — se o campo abre com
 * `2026-10-13`, o app manda o cliente ler ISO no meio de um formulário
 * pt-BR, que é exatamente o vazio de jargão que o gate de review proíbe.
 */
class MoneyDataAnoTest {

    @Test
    fun `iso vira dd mm aaaa`() {
        assertEquals("13/10/2026", Money.dataAno("2026-10-13"))
    }

    @Test
    fun `entrada malformada volta como esta`() {
        // Mesmo contrato de Money.data: nãoexplode em dado surpresa.
        assertEquals("13/10/2026", Money.dataAno("13/10/2026"))
        assertEquals("lixo", Money.dataAno("lixo"))
    }
}
