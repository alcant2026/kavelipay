package com.kavelipay.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cálculo do "Valor total pago/recebido" em "Registrar movimento".
 *
 * Regra da indústria de corretagem: o total NÃO é digitado — ele sai de
 * `quantidade × preço unitário`, como em qualquer corretora. Digitar o total
 * em cima de quantidade e preço já digitados é porta pra divergência (o
 * erro clássico de preencher R$ 210,90 na mão e gravar 210,00).
 *
 * `BigDecimal`, nunca `Double`: 0,5 × 10,50 em `Double` pode virar
 * 5.250000000000001, e esse 0,000000000000001 é dinheiro no ledger.
 */
class MoneyTotalPagoTest {

    @Test
    fun `total de 2 acoes a 105,45 e 210,90`() {
        assertEquals("210.90", Money.totalPago("2", "105.45"))
    }

    @Test
    fun `aceita virgula como separador decimal`() {
        // O campo filtra vírgula pra ponto antes de chegar aqui, mas a função
        // não deve explodir se receber o formato pt-BR cru.
        assertEquals("210.90", Money.totalPago("2", "105,45"))
    }

    @Test
    fun `quantidade fracionaria e exata sem erro de ponto flutuante`() {
        assertEquals("5.25", Money.totalPago("0.5", "10.50"))
    }

    @Test
    fun `arredonda para centavos no meio`() {
        // 3 × 0,015 = 0,045 exato — meio entre 0,04 e 0,05: corretagem cobra
        // em centavos, HALF_UP é o arredondamento do dinheiro brasileiro.
        assertEquals("0.05", Money.totalPago("3", "0.015"))
    }

    @Test
    fun `campo incompleto retorna nulo`() {
        // Digitando ainda não dá total — a tela mostra vazio, não "0".
        assertNull(Money.totalPago("2", ""))
        assertNull(Money.totalPago("", "105.45"))
    }

    @Test
    fun `entrada nao numerica retorna nulo`() {
        assertNull(Money.totalPago("abc", "105.45"))
        assertNull(Money.totalPago("2", "10a.45"))
    }
}
