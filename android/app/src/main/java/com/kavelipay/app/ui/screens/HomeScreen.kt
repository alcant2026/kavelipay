package com.kavelipay.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kavelipay.app.data.ItemConta
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpAvisoSaldoDemonstrativo
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpEtiqueta
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.components.Money.LinhaLancamento
import com.kavelipay.app.ui.components.Money.brl
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.RotuloCaixaAlta
import com.kavelipay.app.ui.theme.SaldoDisplay
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloSecao

/**
 * Home: saldo, avisos, ações rápidas, próximas contas e extrato resumido.
 *
 * Ordem deliberada — saldo primeiro (a pergunta que o usuário tem ao abrir),
 * depois o aviso de "não é dinheiro real" (a verdade que precisa estar visível
 * antes de qualquer número), depois o que fazer.
 */
@Composable
fun KpHomeScreen(
    vm: AppViewModel,
    aoVerDerivacao: () -> Unit,
    aoNovaTransacao: () -> Unit,
    aoVerContas: () -> Unit,
    aoVerRelatorio: () -> Unit,
    aoVerCobrancas: () -> Unit,
    aoVerDivisoes: () -> Unit,
    aoVerHistorico: () -> Unit,
    aoVerCategorias: () -> Unit,
    aoVerPerfil: () -> Unit,
    aoVerInvestimentos: () -> Unit,
    aoSair: () -> Unit = {},
) {
    val estado by vm.estado.collectAsState()
    val sem = KpTheme.semanticas

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md,
                end = Spacing.md,
                top = Spacing.md,
                bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item { CabecalhoHome(naoLidas = estado.naoLidas, aoVerLembretes = vm::marcarTodasLidas) }

            item {
                CartaoSaldo(
                    saldo = estado.carteira?.saldo ?: "0.00",
                    moeda = estado.carteira?.moeda ?: "BRL",
                    demonstrativa = estado.carteira?.demonstrativa ?: true,
                    aoVerDerivacao = aoVerDerivacao,
                )
            }

            // O aviso vem logo depois do número, não no rodapé: se a pessoa lê
            // "R$ 4.606,30" e só rola a tela para descobrir que não é dinheiro
            // dela, a honestidade chegou tarde.
            if (estado.carteira?.demonstrativa != false) {
                item {
                    KpAvisoSaldoDemonstrativo(saldoMostrado = brl(estado.carteira?.saldo ?: "0.00"))
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    KpBotaoPrimario(
                        texto = "Novo lançamento",
                        onClick = aoNovaTransacao,
                        modifier = Modifier.weight(1f),
                        icone = Icons.Default.Add,
                    )
                }
            }

            // Grade de atalhos. Sem ela, `CHARGES`, `SPLITS`, `HISTORICO`,
            // `CATEGORIAS`, `RELATORIO` e `PERFIL` existem no grafo de navegação
            // e nenhuma tela chega neles: rota registrada e inalcançável é o
            // mesmo bug de um botão que não responde.
            item {
                Atalhos(
                    itens = listOf(
                        Atalho(Icons.AutoMirrored.Filled.ReceiptLong, "Contas", aoVerContas),
                        Atalho(Icons.Default.PieChart, "Relatório", aoVerRelatorio),
                        Atalho(Icons.Default.QrCode, "Cobranças", aoVerCobrancas),
                        Atalho(Icons.Default.Payments, "Divisões", aoVerDivisoes),
                        Atalho(Icons.Default.History, "Histórico", aoVerHistorico),
                        Atalho(Icons.Default.Label, "Categorias", aoVerCategorias),
                        Atalho(Icons.Default.TrendingUp, "Investir", aoVerInvestimentos),
                        Atalho(Icons.Default.Person, "Perfil", aoVerPerfil),
                    ),
                )
            }

            estado.contas?.let { contas ->
                val vencidas = contas.itens.count { it.isOverdue }
                item {
                    CartaoProximasContas(
                        contas = contas.itens,
                        vencidas = vencidas,
                        aoVerContas = aoVerContas,
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Últimos lançamentos", style = TituloSecao)
                    KpBotaoSecundario("Ver tudo", onClick = aoVerContas)
                }
            }

            val itens = estado.extrato?.itens.orEmpty()
            if (itens.isEmpty()) {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        KpVazio(
                            icone = Icons.Default.SwapHoriz,
                            titulo = "Nada por aqui ainda",
                            descricao = "Registre uma entrada ou despesa para começar.",
                        )
                    }
                }
            } else {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = Spacing.md)) {
                            itens.take(5).forEachIndexed { i, item ->
                                LinhaLancamento(
                                    titulo = item.descricao,
                                    valor = item.valor,
                                    entrada = !item.isSaida,
                                    categoria = item.categoria,
                                )
                                if (i < itens.take(5).lastIndex) HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }

            item { EspacoRodape() }
        }
    }
}

@Composable
private fun CabecalhoHome(naoLidas: Int, aoVerLembretes: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("KaveliPay", style = MaterialTheme.typography.titleMedium)
            Text(
                "Visão geral",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box {
            KpBotaoSecundario(
                texto = if (naoLidas > 0) "$naoLidas" else "",
                onClick = aoVerLembretes,
                icone = Icons.Default.Notifications,
            )
        }
    }
}

/**
 * Cartão do saldo.
 *
 * O saldo usa `SaldoDisplay` (44sp Black) e a paleta lime é reservada para o
 * botão — um número gigante em verde-limão sobre branco falha contraste e
 * roubaria o destaque do CTA.
 */
@Composable
private fun CartaoSaldo(
    saldo: String,
    moeda: String,
    demonstrativa: Boolean,
    aoVerDerivacao: () -> Unit,
) {
    KpCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md)) {
            Text("Saldo disponível", style = RotuloCaixaAlta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.xs))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "R$ ${brl(saldo)}",
                    style = SaldoDisplay,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    moeda,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            Spacer(Modifier.height(Spacing.md))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(Spacing.sm))

            // O botão "ver de onde veio" é a promessa do produto em um toque.
            KpBotaoSecundario(
                texto = "De onde vem este saldo?",
                onClick = aoVerDerivacao,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Um atalho da grade da Home: ícone, rótulo e destino. */
private data class Atalho(
    val icone: ImageVector,
    val rotulo: String,
    val aoTocar: () -> Unit,
)

/**
 * Grade de destinos. `FlowRow` distribute os atalhos em quantas linhas forem
 * necessárias; fixar 3 por linha deixaria o último sozinho e desalinhado.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Atalhos(itens: List<Atalho>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        itens.forEach { atalho ->
            Column(
                modifier = Modifier
                    .width(96.dp)
                    .clip(RoundedCornerShape(com.kavelipay.app.ui.theme.RaioCard))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = atalho.aoTocar)
                    .padding(vertical = Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = atalho.icone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    atalho.rotulo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun CartaoProximasContas(
    contas: List<ItemConta>,
    vencidas: Int,
    aoVerContas: () -> Unit,
) {
    val sem = KpTheme.semanticas
    val pendentes = contas.filter { it.status == "PENDING" }.take(3)

    KpCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Contas a pagar", style = TituloSecao)
                if (vencidas > 0) {
                    // Plural fora do template: aspas aninhadas dentro de ${}
                    // em literal confodem o parser do Kotlin.
                    val rotulo = if (vencidas > 1) "$vencidas vencidas" else "$vencidas vencida"
                    KpEtiqueta(rotulo, sem.vencido, fundo = sem.vencidoFundo)
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            if (pendentes.isEmpty()) {
                Text(
                    "Nenhuma conta em aberto.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                pendentes.forEachIndexed { i, conta ->
                    LinhaConta(conta)
                    if (i < pendentes.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            Spacer(Modifier.height(Spacing.sm))
            KpBotaoSecundario("Ver todas", onClick = aoVerContas, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Linha de conta: ícone, descrição, vencimento e valor.
 *
 * `trailing` renderiza ao lado do valor (badge de status, botão de pagar).
 * Existe como slot e não como sobreposição em `Box`: o valor é desenhado no
 * fim da Row e um elemento alinhado por cima cobriria o número.
 */
@Composable
fun LinhaConta(conta: ItemConta, trailing: @Composable () -> Unit = {}) {
    val sem = KpTheme.semanticas
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(if (conta.isOverdue) sem.vencidoFundo else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ReceiptLong,
                contentDescription = null,
                tint = if (conta.isOverdue) sem.vencido else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
        }

        Column(Modifier.weight(1f)) {
            Text(
                conta.descricao,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (conta.isOverdue) "Vencida em ${formatarData(conta.dueDate)}" else "Vence ${formatarData(conta.dueDate)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (conta.isOverdue) sem.vencido else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            "R$ ${brl(conta.amount)}",
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )

        trailing()
    }
}

@Composable
private fun EspacoRodape() {
    Text(
        "KaveliPay não é banco, instituição de pagamento ou provedor de PIX. " +
            "O saldo é sempre derivado dos seus lançamentos, e você pode exportar tudo.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg),
    )
}

/**
 * `2026-10-08` → `08/10`.
 *
 * Cópia de [formatarData] que existia antes dela ser movida para
 * `ui.components`. Esta versão local só recebia data pura; a de lá também
 * trata o timestamp ISO-8601 que `settled_at` devolve.
 */
private fun formatarData(iso: String): String =
    com.kavelipay.app.ui.components.formatarData(iso)
