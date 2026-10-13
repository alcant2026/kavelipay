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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kavelipay.app.data.Ativo
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpEtiqueta
import com.kavelipay.app.ui.components.KpLinhaResumo
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.components.Money
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.RotuloCaixaAlta
import com.kavelipay.app.ui.theme.SaldoDisplay
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloSecao

/**
 * Carteira de investimentos: a lista de ativos com a posição de cada um.
 *
 * O KaveliPay registra o que o usuário afirma ter comprado e vendido. Não é
 * corretora, não custodia e não recomenda — e o texto introdutório diz isso
 * na tela, porque um número de investimento sem essa frase é um número que
 * promete mais do que o app pode cumprir.
 */
@Composable
fun KpInvestimentosScreen(
    vm: AppViewModel,
    aoVoltar: () -> Unit,
    aoNovoAtivo: () -> Unit,
    aoAbrirAtivo: (String) -> Unit,
) {
    val estado by vm.estado.collectAsState()
    val sem = KpTheme.semanticas

    LaunchedEffect(Unit) { vm.carregarInvestimentos() }

    Scaffold(topBar = { CabecalhoComVoltar("Investimentos", aoVoltar) }) { padding ->
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

            item {
                // A fronteira do produto na própria tela. Uma tela de
                // investimento sem isto prometeYield e recomendação que o app
                // não faz e não pode fazer.
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text(
                            "Registro, não recomendação",
                            style = MaterialTheme.typography.labelSmall,
                            color = sem.demo,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "O KaveliPay anota o que você comprou e vendeu e calcula " +
                                "custo médio e resultado. Não é corretora, não guarda seus " +
                                "ativos e não diz o que comprar ou vender.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Consolidado
            estado.resumoInvestimentos?.let { r ->
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            Text("PATRIMÔNIO INVESTIDO", style = RotuloCaixaAlta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(Spacing.xs))
                            Text("R$ ${Money.brl(r.custoTotal)}", style = SaldoDisplay)
                            Spacer(Modifier.height(Spacing.md))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(Spacing.sm))
                            KpLinhaResumo("Ganho realizado", "R$ ${Money.brl(r.ganhoRealizado)}")
                            Spacer(Modifier.height(Spacing.xs))
                            KpLinhaResumo("Dividendos recebidos", "R$ ${Money.brl(r.dividendosTotais)}")
                            Spacer(Modifier.height(Spacing.xs))
                            KpLinhaResumo(
                                rotulo = "Custos (corretagem)",
                                valor = "R$ ${Money.brl(r.custosTotais)}",
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            Text(
                                r.notaGanhoNaoRealizado,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                KpBotaoPrimario(
                    texto = "Adicionar ativo",
                    onClick = aoNovoAtivo,
                    icone = Icons.Default.Add,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val itens = estado.investimentos?.itens.orEmpty()
            if (itens.isEmpty()) {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        KpVazio(
                            icone = Icons.Default.TrendingUp,
                            titulo = "Nenhum ativo registrado",
                            descricao = "Adicione um ativo e registre suas compras, vendas e dividendos.",
                        )
                    }
                }
            } else {
                item { Text("Seus ativos", style = TituloSecao) }
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = Spacing.md)) {
                            itens.forEachIndexed { i, a ->
                                LinhaAtivo(a, aoTocar = { aoAbrirAtivo(a.id) })
                                if (i < itens.lastIndex) {
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

/** Uma linha da lista: ticker, nome, quantidade e preço médio. */
@Composable
private fun LinhaAtivo(a: Ativo, aoTocar: () -> Unit) {
    val sem = KpTheme.semanticas
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp)
            .clickable(onClick = aoTocar),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(sem.demoFundo),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                a.symbol.take(4),
                style = MaterialTheme.typography.labelSmall,
                color = sem.demo,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(a.nome, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                "${Money.quantidade(a.quantidade)} un · média R$ ${Money.brl(a.precoMedio)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "R$ ${Money.brl(a.custoTotal)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (a.proximoDividendo != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "div. ${Money.data(a.proximoDividendo)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = sem.receita,
                )
            }
        }
    }
}