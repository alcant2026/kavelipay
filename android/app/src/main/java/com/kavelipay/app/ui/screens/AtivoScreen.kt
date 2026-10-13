package com.kavelipay.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kavelipay.app.data.MovimentoInvestimento
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpEtiqueta
import com.kavelipay.app.ui.components.KpLinhaResumo
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.components.Money
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.RotuloCaixaAlta
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloSecao

/**
 * Detalhe de um ativo: posição, custos, resumo e extrato de movimentos.
 *
 * Mostra o que o usuário PRECISA para decidir e nada do que ele não pode ter:
 * custo médio, custos acumulados, próximo dividendo (sugestão do brapi que
 * ele confirmou, ou registro manual), e o extrato. Não há "preço justo" —
 * essa é uma decisão dele, e o app mostrar um número que recomenda compra é
 * território de assessor de investimento.
 */
@Composable
fun KpAtivoScreen(
    vm: AppViewModel,
    ativoId: String,
    aoVoltar: () -> Unit,
    aoRegistrarMovimento: () -> Unit,
    aoEditarPreco: () -> Unit = {},
) {
    val estado by vm.estado.collectAsState()
    val sem = KpTheme.semanticas
    val a = estado.ativoSelecionado

    LaunchedEffect(ativoId) { vm.carregarAtivo(ativoId) }

    Scaffold(topBar = { CabecalhoComVoltar(a?.nome ?: "Ativo", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (estado.enviando) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }

            if (a == null) {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        KpVazio(
                            icone = Icons.Default.TrendingUp,
                            titulo = "Ativo não encontrado",
                            descricao = "Volte e tente novamente.",
                        )
                    }
                }
                return@LazyColumn
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(a.symbol, style = MaterialTheme.typography.titleMedium, color = sem.demo)
                            KpEtiqueta(a.kind.uppercase(), sem.demo, fundo = sem.demoFundo)
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        Text(a.nome, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text("POSIÇÃO ATUAL", style = RotuloCaixaAlta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "${Money.quantidade(a.quantidade)} unidade(s)",
                            style = com.kavelipay.app.ui.theme.SaldoDisplay,
                        )
                        Spacer(Modifier.height(Spacing.md))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(Spacing.sm))
                        KpLinhaResumo("Preço médio", "R$ ${Money.brl(a.precoMedio)}")
                        Spacer(Modifier.height(Spacing.xs))
                        KpLinhaResumo("Custo total", "R$ ${Money.brl(a.custoTotal)}", negrito = true)
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text("RESULTADO", style = RotuloCaixaAlta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(Spacing.sm))
                        KpLinhaResumo("Ganho realizado", "R$ ${Money.brl(a.ganhoRealizado)}")
                        Spacer(Modifier.height(Spacing.xs))
                        KpLinhaResumo("Dividendos recebidos", "R$ ${Money.brl(a.dividendosTotais)}")
                        Spacer(Modifier.height(Spacing.xs))
                        KpLinhaResumo("Custos acumulados", "R$ ${Money.brl(a.custosTotais)}")
                    }
                }
            }

            // Próximo dividendo: a data pode ter vindo da sugestão do brapi
            // (confirmada pela pessoa) ou do próprio registro manual. Uma data
            // inventada seria pior que nenhuma — por isso a sugestão só entra
            // como preenchimento editável, e a tela diz a origem.
            if (a.proximoDividendo != null) {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            Text("PRÓXIMO DIVIDENDO", style = RotuloCaixaAlta, color = sem.receita)
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                Money.data(a.proximoDividendo),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                "Data sugerida pelo brapi e confirmada por você, ou digitada à " +
                                    "mão. Confira na sua corretora antes de decidir qualquer coisa.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Preço atual: a origem é o registro do usuário. O app SUGERE o
            // preço de mercado na hora de informar, e a fronteira CVM é dita
            // NA TELA — sem ela, o número parece cotação oficial do app.
            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text("PREÇO ATUAL", style = RotuloCaixaAlta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            a.precoAtual?.let { "R$ ${Money.brl(it)}" } ?: "Não informado",
                            style = com.kavelipay.app.ui.theme.SaldoDisplay,
                            color = if (a.precoAtual != null) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            "Você confirma o preço que viu na corretora. O app pode sugerir o " +
                                "de mercado (brapi/Yahoo) ao informar, mas não é cotação oficial " +
                                "nem recomendação: no Brasil, orientar decisão é assessor de " +
                                "investimento (CVM).",
                            style = MaterialTheme.typography.labelSmall,
                            color = sem.demo,
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        KpBotaoSecundario(
                            texto = if (a.precoAtual != null) "Atualizar preço" else "Informar preço",
                            onClick = { aoEditarPreco() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // Derivados do preço atual. Cada linha só aparece quando o dado
            // existe — mostrar "R$ 0,00" para "não sei" seria um número
            // aparentemente verdadeiro e falso.
            if (a.valorAtual != null || a.ganhoNaoRealizado != null) {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            Text("ONDE VOCÊ ESTÁ", style = RotuloCaixaAlta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(Spacing.sm))
                            a.valorAtual?.let {
                                KpLinhaResumo("Valor atual", "R$ ${Money.brl(it)}", negrito = true)
                                Spacer(Modifier.height(Spacing.xs))
                            }
                            a.ganhoNaoRealizado?.let { g ->
                                val n = g.toBigDecimalOrNull()
                                KpLinhaResumo(
                                    rotulo = if (n != null && n < java.math.BigDecimal.ZERO) {
                                        "Ganho não realizado (perda)"
                                    } else {
                                        "Ganho não realizado"
                                    },
                                    valor = "R$ ${Money.brl(g)}",
                                    corValor = if (n != null && n < java.math.BigDecimal.ZERO) {
                                        sem.despesa
                                    } else {
                                        sem.receita
                                    },
                                )
                                Spacer(Modifier.height(Spacing.xs))
                            }
                            a.retornoNaoRealizado?.let { r ->
                                val pct = r.toBigDecimalOrNull()?.multiply(java.math.BigDecimal(100))
                                pct?.let {
                                    val txt = Money.quantidade(it.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
                                    val n = it
                                    KpLinhaResumo(
                                        rotulo = "Retorno sobre o custo",
                                        valor = "$txt%",
                                        corValor = if (n < java.math.BigDecimal.ZERO) sem.despesa else sem.receita,
                                    )
                                }
                            }
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                "Calculado com o preço que você informou. Não é recomendação de compra " +
                                    "ou venda.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item { KpBotaoPrimario(texto = "Registrar movimento", onClick = aoRegistrarMovimento, modifier = Modifier.fillMaxWidth()) }

            a.resumo?.let { resumo ->
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            Text("RESUMO DO ATIVO", style = RotuloCaixaAlta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(Spacing.xs))
                            Text(resumo, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            val movimentos = estado.movimentosAtivo
            if (movimentos.isEmpty()) {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        KpVazio(
                            icone = Icons.Default.Info,
                            titulo = "Nenhum movimento",
                            descricao = "Registre a primeira compra deste ativo.",
                        )
                    }
                }
            } else {
                item { Text("Movimentos", style = TituloSecao) }
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = Spacing.md)) {
                            movimentos.forEachIndexed { i, m ->
                                LinhaMovimento(m)
                                if (i < movimentos.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinhaMovimento(m: MovimentoInvestimento) {
    val sem = KpTheme.semanticas
    val (rotulo, cor, fundo) = when (m.tipo) {
        "BUY" -> Triple("Compra", sem.despesa, sem.despesaFundo)
        "SELL" -> Triple("Venda", sem.receita, sem.receitaFundo)
        "DIVIDEND" -> Triple("Dividendo", sem.receita, sem.receitaFundo)
        else -> Triple(m.tipo, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).background(fundo),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when (m.tipo) {
                    "SELL", "DIVIDEND" -> Icons.Default.ArrowDownward
                    else -> Icons.Default.ArrowUpward
                },
                contentDescription = null,
                tint = cor,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(rotulo, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${Money.quantidade(m.quantidade)} un · ${Money.data(m.data)}" +
                    if (m.precoUnitario != null) " @ R$ ${Money.brl(m.precoUnitario)}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "R$ ${Money.brl(m.valor)}",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = cor,
            )
            if (m.custos != "0.00" && m.custos.toDoubleOrNull() != 0.0) {
                Text(
                    "custos R$ ${Money.brl(m.custos)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}