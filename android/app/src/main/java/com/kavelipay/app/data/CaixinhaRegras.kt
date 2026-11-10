package com.kavelipay.app.data

import java.math.BigDecimal
import java.time.YearMonth

/**
 * Conversões de entrada da caixinha — as mesmas regras que o backend aplica,
 * mas resolvidas antes do payload para a mensagem sair na hora.
 *
 * O servidor continua sendo a fonte da verdade: isto só evita mandar lixo e
 * receber um 422 depois de esperar a rede.
 */

/** `2026-11` — o mês local de quem está usando o app (o backend usa UTC). */
fun mesLocal(): String = YearMonth.now().toString()

/**
 * `"2,5"` → `"2.5"`. Devolve null quando não é taxa válida: o banco é
 * `NUMERIC(5,2)` e o servidor recusa zero/negativo e acima de 999,99.
 */
internal fun taxaParaApi(entrada: String): String? {
    val texto = entrada.trim().replace(',', '.')
    val valor = texto.toBigDecimalOrNull() ?: return null
    if (valor <= BigDecimal.ZERO || valor > BigDecimal("999.99")) return null
    // `stripTrailingZeros` faz "3.00" virar "3" (a API serializa taxa sem
    // zeros); `toPlainString` evita notação científica em valores pequenos.
    return valor.stripTrailingZeros().toPlainString()
}

/** Só dígitos, no máximo 11 — o servidor valida DDD (10 ou 11). */
internal fun telefoneDigitos(entrada: String): String =
    entrada.filter(Char::isDigit).take(11)

internal fun telefoneValido(digitos: String): Boolean =
    digitos.length == 10 || digitos.length == 11
