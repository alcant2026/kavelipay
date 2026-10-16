package com.kavelipay.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBadgeCritico
import com.kavelipay.app.ui.components.KpBadgeNeutra
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCabecalhoComVoltar
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpEtiqueta
import com.kavelipay.app.ui.components.KpLinhaResumo
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.components.Money.brl
import com.kavelipay.app.ui.screens.LinhaContaHome
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.RaioCard
import com.kavelipay.app.ui.theme.RotuloCaixaAlta
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloCard
import com.kavelipay.app.ui.theme.TituloSecao
import kotlin.math.roundToInt

/** Contas a pagar: pendentes, vencidas e o botão de pagar. */
@Composable
fun KpContasScreen(vm: AppViewModel, aoVoltar: () -> Unit, aoNovaConta: () -> Unit = {}) {
    val estado by vm.estado.collectAsState()
    val sem = KpTheme.semanticas
    val contas = estado.contas?.itens.orEmpty()
    val vencidas = contas.count { it.isOverdue }
    val pendentes = contas.count { it.status == "PENDING" }

    Scaffold(topBar = { KpCabecalhoComVoltar("Contas a pagar", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // Cadastrar conta — o `POST /bills` e a API já existiam, mas não
            // tinha UM botão no app inteiro que chamasse; dava pra pagar
            // conta que ninguém conseguia criar.
            item {
                KpBotaoPrimario(
                    texto = "Nova conta",
                    onClick = aoNovaConta,
                    modifier = Modifier.fillMaxWidth(),
                    icone = Icons.Default.Add,
                )
            }

            if (vencidas > 0) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(RaioCard))
                            .background(sem.vencidoFundo)
                            .padding(Spacing.md),
                    ) {
                        Text(
                            "$vencidas ${if (vencidas == 1) "conta vencida" else "contas vencidas"}. " +
                                "O KaveliPay não paga boleto por você: marque como paga quando você pagar.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = sem.vencido,
                        )
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        KpLinhaResumo(
                            rotulo = "Total em aberto",
                            valor = "R$ ${brl(somarComoString(contas.filter { it.status == "PENDING" }.map { it.amount }))}",
                            negrito = true,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        KpLinhaResumo(rotulo = "Pendentes", valor = pendentes.toString())
                    }
                }
            }

            if (contas.isEmpty()) {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        KpVazio(
                            icone = Icons.Default.ReceiptLong,
                            titulo = "Nenhuma conta cadastrada",
                            descricao = "Cadastre uma conta para acompanhar o vencimento.",
                            acao = {
                                KpBotaoPrimario(
                                    texto = "Cadastrar conta",
                                    onClick = aoNovaConta,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                        )
                    }
                }
            } else {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = Spacing.md)) {
                            contas.forEachIndexed { i, conta ->
                                LinhaContaHome(conta) {
                                    if (conta.status == "PENDING") {
                                        KpBotaoSecundario(
                                            texto = "Marcar como paga",
                                            onClick = { vm.pagarConta(conta.id, null) },
                                            cor = MaterialTheme.colorScheme.primary,
                                        )
                                    } else {
                                        KpBadgeNeutra(conta.status)
                                    }
                                }
                                if (i < contas.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                }
            }

            if (estado.enviando) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            if (estado.erro != null) {
                item { KpMensagemErro(estado.erro.orEmpty()) }
            }
        }
    }
}

/** Relatório do mês: receitas, despesas e para onde foi o dinheiro. */
@Composable
fun KpRelatorioScreen(vm: AppViewModel, aoVoltar: () -> Unit) {
    val estado by vm.estado.collectAsState()
    val sem = KpTheme.semanticas
    val r = estado.relatorio

    Scaffold(topBar = { CabecalhoComVoltar("Este mês", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text(
                            r?.month?.let { formatarMes(it) } ?: "Resumo",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        KpLinhaResumo(
                            rotulo = "Entradas",
                            valor = "R$ ${brl(r?.income ?: "0.00")}",
                            corValor = sem.receita,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        KpLinhaResumo(
                            rotulo = "Saídas",
                            valor = "R$ ${brl(r?.expense ?: "0.00")}",
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(Spacing.sm))
                        KpLinhaResumo(
                            rotulo = "Resultado do mês",
                            valor = "R$ ${brl(r?.net ?: "0.00")}",
                            negrito = true,
                            corValor = if ((r?.net?.startsWith("-")) == true) sem.despesa else sem.receita,
                        )
                    }
                }
            }

            val fatias = r?.fatiasDespesa.orEmpty()
            if (fatias.isEmpty()) {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        KpVazio(
                            icone = Icons.Default.PieChart,
                            titulo = "Sem despesas classificadas",
                            descricao = "Classifique lançamentos para ver para onde o dinheiro foi.",
                        )
                    }
                }
            } else {
                item { Text("Para onde foi", style = TituloSecao) }
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            fatias.forEachIndexed { i, fatia ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(fatia.nome, style = MaterialTheme.typography.bodyLarge)
                                        Spacer(Modifier.height(6.dp))
                                        // Barra proporcional ao share. Sem eixo:
                                        // o número exato está do lado, a barra é
                                        // comparação rápida.
                                        LinearProgressIndicator(
                                            progress = { fatia.share.toFloat().coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(
                                                RoundedCornerShape(3.dp),
                                            ),
                                        )
                                    }
                                    Spacer(Modifier.width(Spacing.md))
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            "R$ ${brl(fatia.total)}",
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        Text(
                                            "${(fatia.share * 100).roundToInt()}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (i < fatias.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                }
            }

            // `balance` e `net` são números diferentes: um é o saldo da
            // carteira, o outro o resultado do período. Exibir os dois lado a
            // lado sem rótulo seria a forma mais rápida de confundir o usuário.
            item {
                Text(
                    "Resultado do mês não é o saldo da carteira: é a diferença entre " +
                        "entradas e saídas no período. Saldo agora: R$ ${brl(r?.balance ?: "0.00")}.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun somar(valores: List<String>): Double = valores.sumOf { it.toDoubleOrNull() ?: 0.0 }

private fun somarComoString(valores: List<String>): String {
    val soma = valores.sumOf { it.toDoubleOrNull() ?: 0.0 }
    return String.format("%.2f", soma)
}

private fun formatarMes(ym: String): String {
    val meses = listOf(
        "janeiro", "fevereiro", "março", "abril", "maio", "junho",
        "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
    )
    val p = ym.split("-")
    val m = p.getOrNull(1)?.toIntOrNull() ?: return ym
    val nome = meses.getOrNull(m - 1) ?: return ym
    return nome.replaceFirstChar { it.uppercase() } + "/" + p.getOrElse(0) { "" }
}