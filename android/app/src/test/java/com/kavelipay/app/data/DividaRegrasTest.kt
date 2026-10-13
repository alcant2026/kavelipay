package com.kavelipay.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regras de "Eu devo" (dívidas).
 *
 * Os três status do backend (`PENDING → PAID → CONFIRMED`, ver
 * `backend/src/routes/debts.rs`) decidem o que a tela habilita: pagar só
 * pendente, confirmar só pago, editar/apagar só pendente (depois disso é
 * histórico do ledger e o backend responde 409). A validação do formulário
 * também é regra pura — o mesmo trim/maior-que-zero que o backend cobra em
 * 422 — então se testa aqui, sem emulador e sem rede.
 */
class DividaRegrasTest {

    // ── disponibilidade por status ────────────────────────────────────────────

    @Test
    fun `pagar só está disponível para dívida pendente`() {
        assertTrue(DividaRegras.podePagar("PENDING"))
        assertFalse(DividaRegras.podePagar("PAID"))
        assertFalse(DividaRegras.podePagar("CONFIRMED"))
    }

    @Test
    fun `confirmar só está disponível para dívida paga`() {
        assertTrue(DividaRegras.podeConfirmar("PAID"))
        assertFalse(DividaRegras.podeConfirmar("PENDING"))
        assertFalse(DividaRegras.podeConfirmar("CONFIRMED"))
    }

    @Test
    fun `editar e apagar só estão disponíveis para dívida pendente`() {
        // Pago é histórico do ledger (a transação de `pay` referencia a
        // linha); apagar pendente não estorna nada porque pendente nunca
        // tocou o ledger.
        assertTrue(DividaRegras.podeEditarApagar("PENDING"))
        assertFalse(DividaRegras.podeEditarApagar("PAID"))
        assertFalse(DividaRegras.podeEditarApagar("CONFIRMED"))
    }

    // ── rótulo em português ───────────────────────────────────────────────────

    @Test
    fun `rótulo de cada status em português`() {
        assertEquals("Devendo", DividaRegras.rotulo("PENDING"))
        assertEquals("Pago", DividaRegras.rotulo("PAID"))
        assertEquals("Confirmado pelo credor", DividaRegras.rotulo("CONFIRMED"))
    }

    // ── validação do formulário ───────────────────────────────────────────────

    @Test
    fun `salvar exige credor, descrição e valor maior que zero`() {
        // Valor aceita vírgula (como o Brasil digita) e ponto (formato da API).
        assertTrue(DividaRegras.podeSalvar("João", "50,50", "Jantar"))
        assertTrue(DividaRegras.podeSalvar("João", "50.50", "Jantar"))

        // Valor vazio, não numérico ou não positivo não salva — o backend
        // recusaria os mesmos com 422 ("valor deve ser maior que zero").
        assertFalse(DividaRegras.podeSalvar("João", "", "Jantar"))
        assertFalse(DividaRegras.podeSalvar("João", "abc", "Jantar"))
        assertFalse(DividaRegras.podeSalvar("João", "0", "Jantar"))
        assertFalse(DividaRegras.podeSalvar("João", "-1", "Jantar"))

        // Credor vazio e descrição só com espaços não salvam (mesmo trim
        // do `validar_campos` do backend: " " é vazio).
        assertFalse(DividaRegras.podeSalvar("", "50.50", "Jantar"))
        assertFalse(DividaRegras.podeSalvar("   ", "50.50", "Jantar"))
        assertFalse(DividaRegras.podeSalvar("João", "50.50", "   "))
    }
}
