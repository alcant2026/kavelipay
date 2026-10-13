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
import com.kavelipay.app.ui.components.Money
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.RotuloCaixaAlta
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloSecao
import java.time.LocalDate

/**
 * Registrar compra, venda ou dividendo de um ativo.
 *
 * Os valores vão como STRING e nunca como Double. O `Money` do backend recusa
 * float com 422, e quantidade é `Decimal` de 8 casas porque ação fracionária
 * existe. `filtrarValor` normaliza vírgula para ponto, que é o que o
 * `Decimal::from_str` entende.
 */
@Composable
fun KpMovimentoInvestimentoScreen(
    vm: AppViewModel,
    ativoId: String,
    aoVoltar: () -> Unit,
    aoSalvar: () -> Unit,
) {
    val estado by vm.estado.collectAsState()
    val sem = KpTheme.semanticas
    val ativo = estado.ativoSelecionado

    var tipo by rememberSaveable { mutableStateOf("BUY") }
    var quantidade by rememberSaveable { mutableStateOf("") }
    var precoUnitario by rememberSaveable { mutableStateOf("") }
    var valor by rememberSaveable { mutableStateOf("") }
    var custos by rememberSaveable { mutableStateOf("") }
    var data by rememberSaveable { mutableStateOf(Money.dataAno(LocalDate.now().toString())) }
    var nota by rememberSaveable { mutableStateOf("") }

    // O backend exige preço unitário em compra e venda, e proíbe em dividendo.
    // A tela acompanha para não mandar um 422 evitável.
    val ehDividendo = tipo == "DIVIDEND"
    // Padrão corretagem: em compra/venda o total NÃO é digitável — ele sai de
    // quantidade × preço (Money.totalPago, testado em MoneyTotalPagoTest).
    // Só o dividendo tem valor digitável, porque não tem multiplicação.
    val totalCalculado = if (ehDividendo) null else Money.totalPago(quantidade, precoUnitario)
    val valorFinal = if (ehDividendo) valor else totalCalculado.orEmpty()
    val qtdOk = quantidade.toBigDecimalOrNull()?.let { it > java.math.BigDecimal.ZERO } == true
    val precoOk = ehDividendo || precoUnitario.toBigDecimalOrNull()?.let { it > java.math.BigDecimal.ZERO } == true
    val totalOk = totalCalculado != null || (ehDividendo && valor.toBigDecimalOrNull()?.let { it > java.math.BigDecimal.ZERO } == true)
    val podeSalvar = qtdOk && precoOk && totalOk && data.isNotBlank()

    Scaffold(topBar = { CabecalhoComVoltar("Registrar ${if (ehDividendo) "dividendo" else "movimento"}", aoVoltar) }) { padding ->
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
                        Text(ativo?.symbol ?: "", style = RotuloCaixaAlta, color = sem.demo)
                        Text(ativo?.nome ?: "", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    KpBotaoPrimario("Compra", { tipo = "BUY" }, Modifier.weight(1f))
                    if (tipo == "BUY") {
                        Spacer(Modifier.width(Spacing.xs))
                    } else {
                        KpBotaoSecundario("Venda", { tipo = "SELL" }, Modifier.weight(1f))
                        Spacer(Modifier.width(Spacing.xs))
                    }
                    if (tipo == "SELL") {
                        Spacer(Modifier.width(Spacing.xs))
                    } else {
                        KpBotaoSecundario("Dividendo", { tipo = "DIVIDEND" }, Modifier.weight(1f))
                    }
                }
            }

            if (!ehDividendo) {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            KpCampo(
                                valor = quantidade,
                                aoMudar = { quantidade = filtrarValor(it) },
                                rotulo = "Quantidade (aceita fração)",
                                teclado = KeyboardType.Decimal,
                                umaLinha = true,
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            KpCampo(
                                valor = precoUnitario,
                                aoMudar = { precoUnitario = filtrarValor(it) },
                                rotulo = "Preço unitário",
                                teclado = KeyboardType.Decimal,
                                umaLinha = true,
                            )
                        }
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        if (ehDividendo) {
                            KpCampo(
                                valor = valor,
                                aoMudar = { valor = filtrarValor(it) },
                                rotulo = "Valor do dividendo",
                                teclado = KeyboardType.Decimal,
                                umaLinha = true,
                            )
                        } else {
                            // Como na corretora: total derivado, não digitado.
                            // Digitar quantidade e preço em dois lugares é a
                            // porta do "preenchi 210,90 na mão, gravou 210,00".
                            KpLinhaResumo(
                                if (tipo == "SELL") "Valor recebido" else "Valor total pago",
                                if (totalCalculado != null) "R$ ${Money.brlTotal(totalCalculado.toBigDecimal())}" else "—",
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            KpCampo(
                                valor = custos,
                                aoMudar = { custos = filtrarValor(it) },
                                rotulo = "Corretagem e custos (opcional)",
                                teclado = KeyboardType.Decimal,
                                umaLinha = true,
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                "Corretagem entra no preço médio: é o que faz o ganho calculado bater com o real.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        KpCampo(
                            valor = data,
                            aoMudar = { data = it.take(10) },
                            rotulo = "Data (dd/mm/aaaa)",
                            imeAction = ImeAction.Next,
                            umaLinha = true,
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        KpCampo(valor = nota, aoMudar = { nota = it }, rotulo = "Nota (opcional)")
                    }
                }
            }

            // Prévia: o usuário confere o cálculo antes de gravar, e o valor
            // mostrado é o que o backend vai recalcular.
            item {
                val valorNum = totalCalculado?.toBigDecimalOrNull()
                val qtdNum = quantidade.toBigDecimalOrNull()
                if (valorNum != null && qtdNum != null && !ehDividendo) {
                    val total = valorNum + (custos.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            KpLinhaResumo("Custo total (com custos)", "R$ ${Money.brlTotal(total)}")
                            if (ativo != null && ativo.quantidade.toBigDecimalOrNull()?.let { it > java.math.BigDecimal.ZERO } == true && tipo == "BUY") {
                                val qAtual = ativo.quantidade.toBigDecimal()
                                val custoAtual = ativo.custoTotal.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                                val novaMedia = (custoAtual + total) / (qAtual + qtdNum)
                                Spacer(Modifier.height(Spacing.xs))
                                KpLinhaResumo("Novo preço médio", "R$ ${Money.brlTotal(novaMedia)}")
                            }
                        }
                    }
                }
            }

            if (tipo == "SELL" && ativo != null) {
                item {
                    val qAtual = ativo.quantidade.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                    val venda = quantidade.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            if (venda > qAtual) {
                                Text(
                                    "Você tem ${Money.quantidade(qAtual.toPlainString())} e tentou vender mais. " +
                                        "Vender mais do que se tem é recusado pelo app.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = sem.vencido,
                                )
                            } else {
                                val recebido = totalCalculado?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                                val ganho = recebido -
                                    (ativo.precoMedio.toBigDecimalOrNull()?.times(venda) ?: java.math.BigDecimal.ZERO) -
                                    (custos.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
                                KpLinhaResumo(
                                    "Ganho desta venda",
                                    "R$ ${Money.brlTotal(ganho)}",
                                    corValor = if (ganho >= java.math.BigDecimal.ZERO) sem.receita else sem.vencido,
                                )
                            }
                        }
                    }
                }
            }

            if (estado.erro != null) {
                item { KpMensagemErro(estado.erro.orEmpty()) }
            }

            item {
                KpBotaoPrimario(
                    texto = "Registrar",
                    onClick = {
                        vm.registrarMovimento(
                            investimentoId = ativoId,
                            tipo = tipo,
                            quantidade = quantidade,
                            precoUnitario = if (ehDividendo) null else precoUnitario,
                            valor = valorFinal,
                            custos = if (ehDividendo) null else custos.ifBlank { null },
                            data = data,
                            nota = nota.ifBlank { null },
                            onOk = aoSalvar,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    habilitado = podeSalvar,
                    carregando = estado.enviando,
                )
            }
        }
    }
}