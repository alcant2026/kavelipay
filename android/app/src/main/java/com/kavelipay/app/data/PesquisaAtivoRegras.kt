package com.kavelipay.app.data

/**
 * Regras puras do prefill por IA em "Adicionar ativo".
 *
 * A consulta a `POST /investments/lookup` gasta token de verdade — dinheiro
 * saindo do saldo do KaveliPay a cada chamada, por mais barata que seja. As
 * proteções contra desperdício (não repetir consulta, não consultar código
 * malformado) e contra sobrescrever o digitado moram aqui, funções puras:
 * testáveis com JUnit puro, sem emulador e sem rede.
 *
 * O que NÃO é regra pura fica no build + Waydroid: a chamada HTTP, o
 * trigger de foco na tela, o preenchimento dos campos de verdade.
 */
object PesquisaAtivoRegras {

    /**
     * Formato de código na B3: 4 letras + 1 a 4 dígitos (PETR4, VALE3,
     * MXRF11, AALR11). Espelha `normalizar_simbolo` do backend: o que passa
     * aqui passa lá; o que não passa aqui nem sai do aparelho.
     */
    private val FORMATO_B3 = Regex("[A-Z]{4}\\d{1,4}")

    /**
     * Vale a pena gastar um token nesta consulta?
     *
     * Falso para: campo vazio; código malformado (o backend devolveria 422
     * ANTES de chegar na IA — nem o roundtrip vale a pena); e código já
     * consultado — tabular pro campo Nome e voltar sem mudar nada não pode
     * virar chamada nova.
     *
     * BOND/OTHER é texto livre (renda fixa não tem ticker: "Tesouro Selic
     * 2029"), então só o não-vazio vale.
     */
    fun deveConsultar(symbol: String, tipo: String, ultimaConsulta: String): Boolean {
        // O servidor normaliza " petr4 " em "PETR4"; o guard tem de ver o
        // mesmo ativo dos dois lados, senão a comparação de "já consultei?"
        // mente por causa de um espaço.
        val limpo = symbol.trim().uppercase()
        if (limpo.isEmpty()) return false
        if (limpo == ultimaConsulta.trim().uppercase()) return false
        return if (tipo == "BOND" || tipo == "OTHER") true else FORMATO_B3.matches(limpo)
    }

    /**
     * O que a sugestão da IA pode preencher. Cada campo só vem não-nulo
     * quando o correspondente está vazio: resposta da IA nunca sobrescreve o
     * que a pessoa digitou. `null` no retorno inteiro = não encontrado, não
     * preencha nada.
     *
     * `dividendoAtual` é o campo "Próximo dividendo" da tela (vazio, se a
     * pessoa não digitou nada). A data vem do brapi, não da IA — e obedece a
     * mesma regra dos outros campos: preenche só o vazio.
     */
    fun aplicar(
        nomeAtual: String,
        resumoAtual: String,
        dividendoAtual: String,
        achado: PesquisaAtivo,
    ): SugestaoPreenchimento? {
        if (!achado.achou) return null
        return SugestaoPreenchimento(
            nome = if (nomeAtual.isBlank()) achado.nome else null,
            resumo = if (resumoAtual.isBlank()) achado.resumo else null,
            proximoDividendo = if (dividendoAtual.isBlank()) achado.proximoDividendo else null,
        )
    }

    /**
     * O que a sugestão de preço pode colocar no campo "Preço atual".
     *
     * Se o campo já tem valor, devolve ele intacto — a sugestão de terceiro
     * (brapi/Yahoo) não substitui o que a pessoa digitou olhando a corretora.
     * Se está vazio, devolve a sugestão só se for dinheiro de verdade:
     * `BigDecimal` é exato, e uma string torta ("R$ 56,00abc", lixo de um
     * provedor) não preenche o campo. `null` = não sugira nada; a tela
     * continua em "não informado".
     */
    fun sugerirPreco(precoAtual: String, sugestao: SugestaoPreco?): String? {
        val bruto = sugestao?.preco?.trim().orEmpty()
        val limpo = bruto.removePrefix("R$").trim().replace(',', '.')
        val valido = limpo.toBigDecimalOrNull()?.let {
            it > java.math.BigDecimal.ZERO
        } == true
        // O campo já tem valor = devolve intacto (a sugestão não substitui o
        // digitado). Vazio = devolve a sugestão se for válida, senão null.
        return when {
            precoAtual.isNotBlank() -> precoAtual
            valido -> limpo
            else -> null
        }
    }
}

/** Campos que a tela pode aplicar sem apagar o digitado. */
data class SugestaoPreenchimento(
    val nome: String?,
    val resumo: String?,
    val proximoDividendo: String?,
)
