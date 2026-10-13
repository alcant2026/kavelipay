package com.kavelipay.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.ValorLista

object Money {

    /** Formatação de dinheiro no cliente.
     * O backend já manda `"10.50"` como string pronta. Aqui só se troca o ponto por
     * vírgula para exibição pt-BR — **nunca** se converte para Double: um
     * `toDouble()` aqui devolveria 10.5 e o `.2f` traria de volta 10.50 por
     * acidente, o que funciona hoje e quebra no primeiro valor que não tiver duas
     * casas. */
    fun brl(valor: String): String {
        val limpo = valor.trim()
        if (limpo.isEmpty() || limpo.contains(',')) return if (limpo.isEmpty()) "0,00" else limpo
        val partes = limpo.split('.')
        val inteiro = partes.getOrElse(0) { "0" }.ifEmpty { "0" }
        if (partes.size < 2) return "$inteiro,00"
        val centavos = partes[1].padEnd(2, '0').take(2)
        return "$inteiro,$centavos"
    }

    /**
 * Formata um `BigDecimal` como dinheiro pt-BR.
 *
 * Separado de [brl] porque este recebe um valor JÁ calculado (prévia da tela,
 * nova média, ganho previsto), não a string do servidor. `setScale(2)` é o que
 * garante duas casas: sem ele, `35.150` apareceria como "R$ 35,150" e o
 * arredondamento do banco apareceria direto na tela.
 */
fun brlTotal(valor: java.math.BigDecimal): String =
    valor.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
        .let { brl(it) }

    /** `2026-10-08` → `08/10`. Ver a versão livre no fim do arquivo. */
    fun data(iso: String): String {
        val d = iso.substringBefore('T').trim()
        val p = d.split("-")
        return if (p.size >= 3) "${p[2]}/${p[1]}" else d
    }

    /**
     * `2026-10-13` → `13/10/2026`. Prefill da data de hoje no formato que o
     * cliente digita: o rótulo do campo diz "(dd/mm/aaaa)" e o campo não pode
     * abrir em ISO — é o mesmo vazio de jargão que o gate de review proíbe
     * no erro. Entrada malformada volta intocada (mesmo contrato de [data]).
     */
    fun dataAno(iso: String): String {
        val d = iso.substringBefore('T').trim()
        val p = d.split("-")
        return if (p.size >= 3) "${p[2]}/${p[1]}/${p[0]}" else d
    }

    /**
     * Quantidade de ativo, sem casas irrelevantes.
     *
     * `BigDecimal`, nunca `Double`: quantidade de ação pode ser fracionária
     * (0,005) e `stripTrailingZeros` em `Double` daria 0.005000000000000001.
     * A escala do banco é 8 casas (`NUMERIC(19,8)`), então o valor chega como
     * "10.00000000" e sai como "10".
     */
    fun quantidade(valor: String): String =
        valor.trim().toBigDecimalOrNull()
            ?.stripTrailingZeros()
            ?.toPlainString()
            ?: valor.trim()

    /**
     * Total da negociação = quantidade × preço, no padrão da corretagem.
     *
     * O total na tela é DERIVADO, não digitável — em corretora ninguém digita
     * o total, digita-se quantidade e preço. `BigDecimal`, nunca `Double`
     * (0,5 × 10,50 em `Double` daria 5.250000000000001, e isso é dinheiro).
     * Aceita vírgula porque é o que o cliente pt-BR digita — mesmo racional do
     * `parse_decimal` do backend. `setScale(2, HALF_UP)`: centavos é o menor
     * passo do dinheiro (3 × 0,015 = 0,045 vira 0,05). `null` = ainda não dá
     * pra calcular, e a tela mostra vazio em vez de zero.
     */
    fun totalPago(quantidade: String, preco: String): String? {
        val q = quantidade.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
        val p = preco.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
        return q.multiply(p)
            .setScale(2, java.math.RoundingMode.HALF_UP)
            .toPlainString()
    }

    /** Valor com sinal explícito: `+ 1.000,00` / `− 500,00`. */
    @Composable
    fun TextoValor(
        valor: String,
        modifier: Modifier = Modifier,
        entrada: Boolean,
        usarSinal: Boolean = true,
        cor: Color = if (entrada) KpTheme.semanticas.receita else KpTheme.semanticas.despesa,
    ) {
        val sinal = if (usarSinal) (if (entrada) "+" else "−") else ""
        Text(
            "$sinal R$ ${brl(valor)}",
            modifier = modifier,
            style = ValorLista,
            color = cor,
            maxLines = 1,
        )
    }

    /** Seta de direção do movimento. Verde para entrar, neutro para sair. */
    @Composable
    fun SetaMovimento(entrada: Boolean, modifier: Modifier = Modifier, size: Int = 16) {
        Icon(
            imageVector = if (entrada) {
                Icons.Filled.ArrowDownward
            } else {
                Icons.Filled.ArrowUpward
            },
            contentDescription = if (entrada) "Entrada" else "Saída",
            tint = if (entrada) KpTheme.semanticas.receita else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.size(size.dp),
        )
    }

    /** Linha de lançamento: título, categoria e valor na direita.
     * Uma linha só, altura fixa. Extrato com altura variável é o que faz uma lista
     * de dinheiro parecer bagunçada — e lista de dinheiro precisa parecer organized. */
    @Composable
    fun LinhaLancamento(
        titulo: String,
        valor: String,
        entrada: Boolean,
        modifier: Modifier = Modifier,
        subtitulo: String? = null,
        categoria: String? = null,
        corCategoria: Color? = null,
        trailing: @Composable (() -> Unit)? = null,
        onClick: (() -> Unit)? = null,
    ) {
        val modifierComClique = if (onClick != null) modifier.then(Modifier.clickable { onClick() }) else modifier

        Row(
            modifier = modifierComClique.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            SetaMovimento(entrada)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    titulo.ifBlank { "Sem descrição" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = categoria ?: subtitulo
                if (!sub.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = corCategoria ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            TextoValor(valor = valor, entrada = entrada)

            trailing?.let {
                Spacer(Modifier.width(Spacing.sm))
                it()
            }
        }
    }
}

/**
 * Data vinda da API → `dd/mm`.
 *
 * O backend usa dois formatos e os dois chegam aqui:
 * - `2026-10-08` (só data, em `bills.due_date`)
 * - `2026-10-06T02:38:38.367193Z` (timestamp ISO-8601, em `settled_at`)
 *
 * Pegar só o `p[2]` do split por `-` funciona no primeiro e devolve lixo no
 * segundo (`06T02:38:38.367193Z`), então a parte de tempo é cortada antes.
 */
fun formatarData(iso: String): String {
    val data = iso.substringBefore('T').trim()
    val p = data.split("-")
    return if (p.size >= 3) "${p[2]}/${p[1]}" else data
}