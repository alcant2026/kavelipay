package com.kavelipay.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regras do prefill por IA em "Adicionar ativo".
 *
 * A consulta ao servidor gasta token de verdade — dinheiro saindo do bolso do
 * KaveliPay a cada chamada. As duas proteções contra desperdício (não repetir
 * a mesma consulta, não consultar código malformado) e a proteção contra
 * sobrescrever o que a pessoa digitou são regra pura, e regra pura se testa
 * sem emulador. O resto do fluxo (rede, tela) fica no build + Waydroid.
 */
class PesquisaAtivoRegrasTest {

    // ── deveConsultar: quando vale a pena gastar um token ─────────────────────

    @Test
    fun `campo vazio não consulta`() {
        assertFalse(PesquisaAtivoRegras.deveConsultar("", "STOCK", ""))
        assertFalse(PesquisaAtivoRegras.deveConsultar("   ", "STOCK", ""))
    }

    @Test
    fun `mesmo símbolo consultado não consulta de novo`() {
        // O caso real: a pessoa tabula pro campo Nome e volta pro Código sem
        // mudar nada. Sem este guard, cada ida e volta é um token pago.
        assertFalse(PesquisaAtivoRegras.deveConsultar("PETR4", "STOCK", "PETR4"))
    }

    @Test
    fun `símbolo novo depois de consultar consulta`() {
        assertTrue(PesquisaAtivoRegras.deveConsultar("VALE3", "STOCK", "PETR4"))
    }

    @Test
    fun `espaços e caixa baixa não contam como símbolo diferente`() {
        // O servidor normaliza " petr4 " para "PETR4"; o guard tem de ver o
        // mesmo ativo, senão a comparação de "já consultei?" mente.
        assertFalse(PesquisaAtivoRegras.deveConsultar(" petr4 ", "STOCK", "PETR4"))
        assertFalse(PesquisaAtivoRegras.deveConsultar("petr4", "STOCK", "PETR4"))
    }

    @Test
    fun `código com formato inválido de bolsa não consulta`() {
        // "MAXRENDA" é o nome do fundo no lugar do código — o caso que motivou
        // a validação. O servidor devolveria 422; nem a chamada vale a pena.
        assertFalse(PesquisaAtivoRegras.deveConsultar("MAXRENDA", "STOCK", ""))
        assertFalse(PesquisaAtivoRegras.deveConsultar("PETR", "STOCK", ""))
        assertFalse(PesquisaAtivoRegras.deveConsultar("ABC1234567", "FII", ""))
    }

    @Test
    fun `formato válido de ação e de fundo consulta`() {
        assertTrue(PesquisaAtivoRegras.deveConsultar("PETR4", "STOCK", ""))
        assertTrue(PesquisaAtivoRegras.deveConsultar("MXRF11", "FII", ""))
        assertTrue(PesquisaAtivoRegras.deveConsultar("AALR11", "FII", ""))
    }

    @Test
    fun `renda fixa é texto livre e qualquer coisa não vazia consulta`() {
        // BOND não tem formato de ticker: "Tesouro Selic 2029" passa direto.
        assertTrue(PesquisaAtivoRegras.deveConsultar("Tesouro Selic 2029", "BOND", ""))
    }

    // ── aplicar: preencher sem apagar o que a pessoa digitou ──────────────────

    private val achado = PesquisaAtivo(
        achou = true,
        nome = "Petrobras",
        resumo = "Empresa de petróleo e gás.",
    )

    @Test
    fun `não encontrado não preenche nada`() {
        val nada = PesquisaAtivo(achou = false, nome = null, resumo = null)
        assertNull(PesquisaAtivoRegras.aplicar("", "", "", nada))
    }

    @Test
    fun `preenche nome e resumo quando os dois estão vazios`() {
        val s = PesquisaAtivoRegras.aplicar("", "", "", achado)!!

        assertEquals("Petrobras", s.nome)
        assertEquals("Empresa de petróleo e gás.", s.resumo)
    }

    @Test
    fun `não sobrescreve o nome que a pessoa digitou`() {
        val s = PesquisaAtivoRegras.aplicar("Minha ação favorita", "", "", achado)!!

        assertNull(s.nome)
        assertEquals("Empresa de petróleo e gás.", s.resumo)
    }

    @Test
    fun `não sobrescreve o resumo que a pessoa digitou`() {
        val s = PesquisaAtivoRegras.aplicar("", "Resumo escrito à mão.", "", achado)!!

        assertEquals("Petrobras", s.nome)
        assertNull(s.resumo)
    }

    @Test
    fun `resumo ausente na IA deixa o campo como está`() {
        val semResumo = PesquisaAtivo(achou = true, nome = "Petrobras", resumo = null)

        val s = PesquisaAtivoRegras.aplicar("", "", "", semResumo)!!

        assertEquals("Petrobras", s.nome)
        assertNull(s.resumo)
    }

    // ── próximo dividendo: a data vem do mercado, não da IA ───────────────────

    private val achadoComData = PesquisaAtivo(
        achou = true,
        nome = "Petrobras",
        resumo = "Empresa de petróleo e gás.",
        proximoDividendo = "2026-12-21",
    )

    @Test
    fun `preenche o próximo dividendo quando o campo está vazio`() {
        // O pedido do usuário: se o ativo paga, sugere a data como sugestão
        // editável — o campo vazio recebe, o preenchido é respeitado.
        val s = PesquisaAtivoRegras.aplicar("", "", "", achadoComData)!!

        assertEquals("2026-12-21", s.proximoDividendo)
    }

    @Test
    fun `não sobrescreve a data que a pessoa digitou`() {
        // Mesma regra de nome e resumo: sugestão nunca apaga o digitado. A
        // tela passa o que já está no campo; se estiver preenchido, a data
        // do mercado fica de fora.
        val s = PesquisaAtivoRegras.aplicar("", "", "2027-01-31", achadoComData)!!

        assertNull(s.proximoDividendo)
    }

    @Test
    fun `ativo sem pagamento não sugere data`() {
        val semData = PesquisaAtivo(achou = true, nome = "Petrobras", resumo = null)

        val s = PesquisaAtivoRegras.aplicar("", "", "", semData)!!

        assertNull(s.proximoDividendo)
    }

    // ── sugestão de preço: só quando o campo está vazio, e só string ──────────

    @Test
    fun `sugere preço quando o campo está vazio`() {
        val s = PesquisaAtivoRegras.sugerirPreco("", SugestaoPreco("56.00", "brapi"))

        assertEquals("56.00", s)
    }

    @Test
    fun `não sobrescreve o preço digitado`() {
        // A pessoa digitou o que viu na corretora; a sugestão de terceiro não
        // pode substituir isso.
        val s = PesquisaAtivoRegras.sugerirPreco("49.90", SugestaoPreco("56.00", "brapi"))

        assertEquals("49.90", s)
    }

    @Test
    fun `sugestão vazia ou malformada não preenche`() {
        // Renda fixa, ticker não listado, provedor fora: tudo vira sem
        // sugestão, e a tela continua dizendo "não informado".
        assertNull(PesquisaAtivoRegras.sugerirPreco("", SugestaoPreco(null, null)))
        assertNull(PesquisaAtivoRegras.sugerirPreco("", null))
        assertNull(PesquisaAtivoRegras.sugerirPreco("", SugestaoPreco("", "brapi")))
    }

    @Test
    fun `sugestão que não parece número não preenche`() {
        // Dinheiro é string, mas tem de virar BigDecimal limpo. Uma string
        // torta ("R$ 56,00abc") não preenche o campo — o filtrarValor da tela
        // não deixaria passar se fosse digitada.
        assertNull(PesquisaAtivoRegras.sugerirPreco("", SugestaoPreco("abc", "brapi")))
    }
}
