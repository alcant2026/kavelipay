package com.kavelipay.app.ui.screens

import com.kavelipay.app.data.ItemHistorico

/**
 * Filtros do histórico — pura, sem UI e sem rede, para poder ser testada.
 *
 * O histórico da API já vem com `kind` (o filtro de tipo é do servidor); busca
 * por **texto** e por **mês** não existem lá e são feitas no cliente. Só faz
 * sentido filtrar localmente porque a lista é pequena e paginada no servidor —
 * buscar no servidor exigiria `GET /history?q=`, que o backend não tem.
 *
 * Mês é `YYYY-MM` (o mesmo formato do relatório), então "novembro de 2026" vira
 * "2026-11" e casa com o começo do `occurred_at`, que é ISO-8601.
 */
internal object FiltroHistorico {

    fun aplicar(
        itens: List<ItemHistorico>,
        busca: String,
        mes: String? = null,
    ): List<ItemHistorico> {
        val termo = busca.trim().lowercase()
        return itens.filter { item ->
            val casaTexto = termo.isEmpty() ||
                item.title.lowercase().contains(termo) ||
                item.kind.lowercase().contains(termo)
            val casaMes = mes == null || item.criadoEm.startsWith(mes)
            casaTexto && casaMes
        }
    }

    /**
     * Meses presentes na lista, mais recentes primeiro, como `YYYY-MM`.
     *
     * Sai da própria lista em vez de um seletor de meses do calendário: quem
     * olha o histórico quer "o que aconteceu em outubro", e outubro só é opção
     * se existe outubro ali. Duas listas de meses (a do calendário e a dos
     * dados) divergiriam e uma delas mostraria um mês vazio.
     */
    fun meses(itens: List<ItemHistorico>): List<String> =
        itens.mapNotNull { it.criadoEm.takeIf(String::isNotBlank)?.take(7) }
            .distinct()
            .sortedDescending()
}