package com.kavelipay.app.data

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Regras de entrada da Caixinha.
 *
 * Só o que a pessoa DIGITA passa por aqui: valor (aceita vírgula, padrão do
 * cliente) e telefone (DDD + número). Dinheiro é `BigDecimal`, nunca
 * `Double`.
 *
 * Todo o cálculo da caixinha — arrecadado, saldo em aberto, rendimento
 * devido, progresso — é do SERVIDOR (deriva dos movimentos confirmados).
 * Nada aqui soma, subtrai ou calcula juro: número calculado no app é bug.
 */
object CaixinhaRegras {

    /** Valor digitado → BigDecimal com 2 casas, ou `null` se inválido/zero. */
    fun parseValor(texto: String): BigDecimal? =
        texto.trim().replace(',', '.')
            .toBigDecimalOrNull()
            ?.takeIf { it > BigDecimal.ZERO }
            ?.setScale(2, RoundingMode.HALF_UP)

    /** Telefone digitado → `(11) 98888-7777`; `null` se não tiver 10 ou 11 dígitos. */
    fun formatarTelefone(texto: String): String? {
        val digitos = texto.filter { it.isDigit() }
        if (digitos.length !in 10..11) return null
        val ddd = digitos.take(2)
        val numero = digitos.drop(2)
        val corte = if (numero.length == 9) 5 else 4
        return "($ddd) ${numero.take(corte)}-${numero.drop(corte)}"
    }

    /**
     * Telefone digitado → só dígitos, o formato da API: o servidor guarda 10
     * ou 11 dígitos e recusa o resto (`Digite um telefone com DDD.`).
     * `null` quando está incompleto — a tela valida antes do request.
     */
    fun telefoneParaApi(texto: String): String? {
        val digitos = texto.filter { it.isDigit() }
        return digitos.takeIf { it.length in 10..11 }
    }
}
