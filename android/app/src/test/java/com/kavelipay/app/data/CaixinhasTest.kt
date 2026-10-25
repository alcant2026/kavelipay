package com.kavelipay.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Funções puras da Caixinha (docs/caixinha-api.md): a derivação do card
 * "Para confirmar" a partir do detalhe que o servidor devolve, e a
 * formatação pt-BR que as telas usam.
 *
 * `org.json` NÃO roda em `testDebugUnitTest` (é stub do android.jar), então
 * os DTOs são montados direto em Kotlin — o parse em si é conferido contra
 * o backend rodando, como `Models.kt` sempre fez.
 */
class CaixinhasTest {

    private fun movimento(
        tipo: String,
        status: String = "PROPOSTO",
        membroId: String = "ana",
        membroNome: String = "Ana",
    ) = MovimentoCaixinha(
        id = "mov-$tipo-$status",
        tipo = tipo,
        membroId = membroId,
        membroNome = membroNome,
        valor = "600.00",
        competencia = if (tipo == "RENDIMENTO") "2026-10" else null,
        status = status,
        propostoPor = "alguem",
        criadoEm = "2026-10-23T12:00:00Z",
        confirmadoEm = if (status == "CONFIRMADO") "2026-10-23T12:00:00Z" else null,
    )

    private fun detalhe(papel: String, vararg movimentos: MovimentoCaixinha) = DetalheCaixinha(
        id = "cx-1",
        nome = "Viagem da família",
        papel = papel,
        dono = DonoCaixinha("dono-1", "João", "11999990000"),
        publica = false,
        valorPedido = "1000.00",
        taxaMensal = "3",
        previsaoQuitacao = null,
        arrecadado = "600.00",
        arrecadadoPendente = "200.00",
        disponivelAporte = "200.00",
        progresso = 0.6f,
        mediaAvaliacoes = "4,5",
        totalAvaliacoes = 2,
        convite = null,
        minhaAvaliacao = null,
        membros = emptyList(),
        totalDevidoMes = null,
        totalPagoMes = null,
        totalFaltaMes = null,
        movimentos = movimentos.toList(),
    )

    @Test
    fun `responsavel confirma o aporte proposto pelo membro`() {
        val pendencias = pendenciasCaixinha(detalhe("dono", movimento("APORTE")))

        assertEquals(1, pendencias.size)
        assertTrue(pendencias.single().confirmavelPorMim)
        assertFalse(pendencias.single().cancelavelPorMim)
    }

    @Test
    fun `responsavel espera o membro confirmar rendimento e devolucao que ele propos`() {
        val pendencias = pendenciasCaixinha(
            detalhe("dono", movimento("RENDIMENTO"), movimento("DEVOLUCAO")),
        )

        assertEquals(2, pendencias.size)
        // Quem propôe o rendimento/devolução é o responsável: ele cancela,
        // quem confirma é o membro da linha.
        assertFalse(pendencias[0].confirmavelPorMim)
        assertTrue(pendencias[0].cancelavelPorMim)
        assertFalse(pendencias[1].confirmavelPorMim)
        assertTrue(pendencias[1].cancelavelPorMim)
    }

    @Test
    fun `membro confirma rendimento e devolucao e cancela o proprio aporte`() {
        val pendencias = pendenciasCaixinha(
            detalhe("membro", movimento("APORTE"), movimento("RENDIMENTO"), movimento("DEVOLUCAO")),
        )

        assertEquals(3, pendencias.size)
        // Aporte: quem confirma é o responsável — o membro só cancela.
        assertFalse(pendencias[0].confirmavelPorMim)
        assertTrue(pendencias[0].cancelavelPorMim)
        // Rendimento e devolução: o membro da linha (o próprio) confirma.
        assertTrue(pendencias[1].confirmavelPorMim)
        assertFalse(pendencias[1].cancelavelPorMim)
        assertTrue(pendencias[2].confirmavelPorMim)
        assertFalse(pendencias[2].cancelavelPorMim)
    }

    @Test
    fun `movimento resolvido nao vira pendencia`() {
        val pendencias = pendenciasCaixinha(
            detalhe(
                "dono",
                movimento("APORTE", status = "CONFIRMADO"),
                movimento("RENDIMENTO", status = "RECUSADO"),
                movimento("DEVOLUCAO", status = "CANCELADO"),
            ),
        )

        assertTrue(pendencias.isEmpty())
    }

    @Test
    fun `sem movimento proposto nao ha pendencia`() {
        assertTrue(pendenciasCaixinha(detalhe("dono")).isEmpty())
        assertTrue(pendenciasCaixinha(detalhe("membro")).isEmpty())
    }

    @Test
    fun `taxa sai no formato que o cliente le`() {
        // O servidor serializa sem zeros à direita: "3", "2.5".
        assertEquals("3", formatarTaxa("3"))
        assertEquals("2,5", formatarTaxa("2.5"))
        assertEquals("2,5", formatarTaxa(" 2.5 "))
        assertEquals("", formatarTaxa(""))
    }

    @Test
    fun `competencia sai como mes curto`() {
        assertEquals("10/2026", mesCurto("2026-10"))
        assertEquals("10/2026", mesCurto(" 2026-10 "))
        // Texto inesperado volta como veio — a tela não inventa data.
        assertEquals("depois do carnava", mesCurto("depois do carnava"))
    }

    @Test
    fun `progresso da vitrine e a razao de dois numeros do servidor`() {
        assertEquals(0.6f, progressoVitrine("600.00", "1000.00"), 0.0001f)
        // Arrecadado acima do pedido: barra cheia, não > 1.
        assertEquals(1f, progressoVitrine("1500.00", "1000.00"), 0.0001f)
        assertEquals(0f, progressoVitrine("600.00", "0"), 0.0001f)
        assertEquals(0f, progressoVitrine("600.00", ""), 0.0001f)
        assertEquals(0f, progressoVitrine("abc", "1000.00"), 0.0001f)
    }

    @Test
    fun `papel do detalhe decide quem e responsavel`() {
        assertTrue(detalhe("dono").ehDono)
        assertFalse(detalhe("membro").ehDono)
    }
}
