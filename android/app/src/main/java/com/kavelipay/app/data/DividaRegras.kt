package com.kavelipay.app.data

import java.math.BigDecimal

/**
 * Regras puras de "Eu devo" (dívidas).
 *
 * Os três status do backend — `PENDING → PAID → CONFIRMED` (ver
 * `backend/src/routes/debts.rs`) — decidem o que a tela habilita. Espelhar
 * essa guarda no cliente evita botão que sempre acaba em 409: pagar só
 * pendente, confirmar só pago, editar/apagar só pendente (depois disso é
 * histórico do ledger e o backend recusa).
 *
 * A validação do formulário também mora aqui: mesmo trim e mesmo
 * "maior que zero" que o `validar_campos` do backend cobra em 422 — falhar
 * aqui antes do request é erro na hora, sem roundtrip.
 *
 * Tudo função pura, sem import de Android/Compose: testável com JUnit puro,
 * sem emulador e sem rede (ver `DividaRegrasTest`).
 */
object DividaRegras {

    /** Pagar debita o ledger; só dívida pendente pode pagar (senão 409). */
    fun podePagar(status: String): Boolean = status == "PENDING"

    /** Confirmar é atestado do credor: `PAID → CONFIRMED`, sem ledger. */
    fun podeConfirmar(status: String): Boolean = status == "PAID"

    /**
     * Pago/confirmado é histórico — a transação de `pay` referencia a linha —
     * então editar e apagar valem só enquanto `PENDING` (409 depois disso).
     */
    fun podeEditarApagar(status: String): Boolean = status == "PENDING"

    /** Rótulo do status em português, para a lista. */
    fun rotulo(status: String): String = when (status) {
        "PENDING" -> "Devendo"
        "PAID" -> "Pago"
        "CONFIRMED" -> "Confirmado pelo credor"
        // Status desconhecido não vira rótulo inventado: a UI mostra o que o
        // servidor mandou, que é o que dá para depurar.
        else -> status
    }

    /**
     * O formulário pode salvar?
     *
     * Credor e descrição não vazios (trim — " " é vazio, como o `btrim` do
     * CHECK no banco) e valor parseável em `BigDecimal` aceitando vírgula
     * ("50,50", como o Brasil digita) ou ponto ("50.50", formato da API),
     * maior que zero. Dinheiro é string aqui e `BigDecimal` na checagem —
     * nunca `Double`.
     */
    fun podeSalvar(credor: String, valor: String, descricao: String): Boolean {
        if (credor.trim().isEmpty()) return false
        if (descricao.trim().isEmpty()) return false
        val numero = valor.trim().replace(',', '.').toBigDecimalOrNull() ?: return false
        return numero > BigDecimal.ZERO
    }
}
