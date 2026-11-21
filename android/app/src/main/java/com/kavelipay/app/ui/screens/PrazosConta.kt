package com.kavelipay.app.ui.screens

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Datas de vencimento das contas a pagar, resumidas em faixas que a pessoa
 * entende de relance.
 *
 * Pura (recebe o "hoje" em vez de ler o relógio): assim o teste não depende da
 * data em que roda, e "vence hoje" não vira "vence amanhã" na véspera.
 *
 * A conta vencida é `PENDING` com `due_date` no passado — não existe status
 * `OVERDUE` no banco. Quem decide isso aqui é a data, não uma coluna.
 */
internal object PrazosConta {

    /** `YYYY-MM-DD` (o formato da API) → [LocalDate]. `null` se não parsear. */
    fun parse(iso: String): LocalDate? = runCatching {
        LocalDate.parse(iso.trim().take(10))
    }.getOrNull()

    /** Daqui a quantos dias vence. Negativo = vencido há [abs] dias. */
    fun diasAte(vencimento: String, hoje: LocalDate): Long? =
        parse(vencimento)?.let { it.toEpochDay() - hoje.toEpochDay() }

    /** true = venceu antes de hoje (o mesmo critério do backend para `overdue`). */
    fun vencidaA(vencimento: String, hoje: LocalDate): Boolean {
        val d = diasAte(vencimento, hoje) ?: return false
        return d < 0
    }

    /**
     * Rótulo curto: `Hoje`, `Amanhã`, `Em 5 dias`, `Há 3 dias`.
     *
     * A partir de uma semana a contagem em dias para de ajudar ("em 63 dias"
     * não diz nada), então cai na data — que é o que a pessoa procura na
     * verdade.
     */
    fun rotulo(vencimento: String, hoje: LocalDate): String {
        val dias = diasAte(vencimento, hoje) ?: return vencimento
        return when {
            dias == 0L -> "Hoje"
            dias == 1L -> "Amanhã"
            dias > 1L && dias <= 6 -> "Em $dias dias"
            dias < 0 -> "Há ${-dias} dia${if (-dias == 1L) "" else "s"}"
            else -> {
                val d = parse(vencimento) ?: return vencimento
                "${d.dayOfMonth.toString().padStart(2, '0')}/" +
                    "${d.monthValue.toString().padStart(2, '0')}"
            }
        }
    }

    /**
     * `YYYY-MM` do vencimento → `novembro` (pt-BR), ou a própria data se não
     * parsear. Serve para o seletor de mês e para agrupar a lista.
     */
    fun rotuloMes(vencimento: String): String {
        val d = parse(vencimento) ?: return vencimento
        return runCatching {
            val fmt = java.text.SimpleDateFormat("MMMM", java.util.Locale("pt", "BR"))
            fmt.format(java.util.Date.from(d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()))
        }.getOrDefault("${d.monthValue}")
    }

    /**
     * Ordena por vencimento, mas mantém as vencidas primeiro.
     *
     * `YYYY-MM-DD` em ordem alfabética já é ordem cronológica, então não há
     * parse aqui — só a prioridade da faixa. Conta sem data parseável vai para
     * o fim em vez de estourar a lista.
     */
    fun ordenadasPorVencimento(lista: List<String>): List<String> =
        lista.sortedWith(
            compareBy<String>({ parse(it) == null }, { parse(it) ?: LocalDate.MAX }),
        )
}