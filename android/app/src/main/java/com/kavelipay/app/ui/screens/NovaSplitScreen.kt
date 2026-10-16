package com.kavelipay.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpLinhaResumo
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.RotuloCaixaAlta
import com.kavelipay.app.ui.theme.Spacing

/**
 * Criar divisão de despesa.
 *
 * O invariante do backend é `SUM(partes.owed_amount) == total_amount`, senão
 * R$ 100 divididos viram R$ 90 e o dinheiro evapora. Mostramos a soma das
 * partes **enquanto** o usuário digita, com o resto em aberto: o erro aparece
 * antes do 422, não depois.
 */
@Composable
fun KpNovaSplitScreen(vm: AppViewModel, aoVoltar: () -> Unit, aoSalvar: () -> Unit) {
    val estado by vm.estado.collectAsState()
    val sem = KpTheme.semanticas

    var descricao by rememberSaveable { mutableStateOf("") }
    var total by rememberSaveable { mutableStateOf("") }
    var partes by remember {
        mutableStateOf(listOf(Parede("", ""), Parede("", "")))
    }

    // Soma por string: nunca `Double` em dinheiro (a regra do projeto).
    val somaPartes = somaDecimal(partes.map { it.valor })
    val totalNum = total.toBigDecimalOrNull()
    val fecha = totalNum != null && somaPartes != null && somaPartes == totalNum && partes.size >= 1 &&
        partes.all { it.nome.isNotBlank() && it.valor.toBigDecimalOrNull()?.let { v -> v > java.math.BigDecimal.ZERO } == true }

    Scaffold(topBar = { CabecalhoComVoltar("Nova divisão", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text("TOTAL DA DESPESA", style = RotuloCaixaAlta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(Spacing.xs))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("R\$", style = MaterialTheme.typography.headlineSmall, color = sem.despesa)
                            Spacer(Modifier.width(Spacing.sm))
                            KpCampo(
                                valor = total,
                                aoMudar = { total = filtrarValor(it) },
                                rotulo = "0,00",
                                teclado = KeyboardType.Decimal,
                                umaLinha = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        KpCampo(
                            valor = descricao,
                            aoMudar = { descricao = it },
                            rotulo = "Ex.: Jantar de sábado",
                            imeAction = ImeAction.Done,
                        )
                    }
                }
            }

            item { Text("Participantes", style = com.kavelipay.app.ui.theme.TituloSecao) }

            items@ for ((i, parte) in partes.withIndex()) {
                item(key = "p$i") {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                KpCampo(
                                    valor = parte.nome,
                                    aoMudar = { novo ->
                                        partes = partes.toMutableList().also { it[i] = parte.copy(nome = novo) }
                                    },
                                    rotulo = "Nome",
                                    modifier = Modifier.weight(1.4f),
                                )
                                KpCampo(
                                    valor = parte.valor,
                                    aoMudar = { novo ->
                                        partes = partes.toMutableList().also {
                                            it[i] = parte.copy(valor = filtrarValor(novo))
                                        }
                                    },
                                    rotulo = "0,00",
                                    teclado = KeyboardType.Decimal,
                                    umaLinha = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (partes.size > 1) {
                                KpBotaoSecundario(
                                    texto = "Remover ${parte.nome.ifBlank { "participante" }}",
                                    onClick = {
                                        partes = partes.toMutableList().also { it.removeAt(i) }
                                    },
                                    cor = sem.despesa,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }

            item {
                KpBotaoSecundario(
                    texto = "Adicionar participante",
                    onClick = { partes = partes + Parede("", "") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                val falta = totalNum != null && somaPartes != null && somaPartes != totalNum
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        KpLinhaResumo("Total", "R\$ ${somaPartes?.toPlainString() ?: "0,00"}", negrito = true)
                        Spacer(Modifier.height(Spacing.xs))
                        if (falta) {
                            val delta = totalNum!! - somaPartes!!
                            KpLinhaResumo(
                                rotulo = if (delta > java.math.BigDecimal.ZERO) "Falta repartir" else "Passou do total",
                                valor = "R\$ ${delta.abs().toPlainString()}",
                                corValor = sem.vencido,
                            )
                        } else if (totalNum != null) {
                            Text(
                                "A soma fecha com o total. O backend exige isso: divisão que não fecha é recusada.",
                                style = MaterialTheme.typography.labelSmall,
                                color = sem.receita,
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "A despesa inteira sai da sua carteira agora. Cada quitação devolve o valor da parte.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (estado.erro != null) {
                item { KpMensagemErro(estado.erro.orEmpty()) }
            }

            item {
                KpBotaoPrimario(
                    texto = "Criar divisão",
                    onClick = {
                        vm.criarSplit(
                            descricao = descricao.ifBlank { "Divisão de despesa" },
                            total = total,
                            partes = partes.map { it.nome to it.valor },
                            onOk = aoSalvar,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    habilitado = fecha,
                    carregando = estado.enviando,
                )
            }
        }
    }
}

private data class Parede(val nome: String, val valor: String)

/**
 * Soma exata de decimais em string.
 *
 * `BigDecimal` é o único tipo aqui que não perde precisão: `0.1 + 0.2` em
 * `Double` dá `0.30000000000000004` e a tela mostraria um valor que não bate
 * com o total — exatamente o bug que o invariante do servidor proíbe.
 */
internal fun somaDecimal(valores: List<String>): java.math.BigDecimal? {
    val limpo = valores.map { it.trim() }.filter { it.isNotEmpty() }
    if (limpo.isEmpty()) return java.math.BigDecimal.ZERO
    return limpo.fold(java.math.BigDecimal.ZERO) { acc, s ->
        val v = s.toBigDecimalOrNull() ?: return null
        acc.add(v)
    }
}