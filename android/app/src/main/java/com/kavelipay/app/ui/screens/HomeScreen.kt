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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kavelipay.app.data.ItemConta
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpAcaoItem
import com.kavelipay.app.ui.components.KpAvisoSaldoDemonstrativo
import com.kavelipay.app.ui.components.KpBadgeCritico
import com.kavelipay.app.ui.components.KpBadgeNeutra
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpCarrossel
import com.kavelipay.app.ui.components.KpCarrosselItem
import com.kavelipay.app.ui.components.KpEtiqueta
import com.kavelipay.app.ui.components.KpFilaAcoes
import com.kavelipay.app.ui.components.KpHeroCardSaldo
import com.kavelipay.app.ui.components.KpLinhaResumo
import com.kavelipay.app.ui.components.KpTabBar
import com.kavelipay.app.ui.components.KpTopBar
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.components.Money.LinhaLancamento
import com.kavelipay.app.ui.components.Money.brl
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.LimeMarca
import com.kavelipay.app.ui.theme.RaioCard
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloCard
import com.kavelipay.app.ui.theme.TituloTela
import com.kavelipay.app.ui.theme.VerdeEscuro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Home — padrão Nubank:
 * 1. TopBar (saudação + tema + notificações + ocultar saldo)
 * 2. Saldo herói (GET /wallet) com selo Demonstrativo + toggle ocultar
 * 3. Fila de ações (LazyRow horizontal): Entrada, Despesa, Boleto, Cobrança, Divisão, Investir
 * 4. Bloco "Contas a pagar" com badges de vencido
 * 5. Carrossel "Descubra mais"
 * 6. Últimos lançamentos (extrato resumido)
 * 7. TabBar inferior 5 abas
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
    // Defaults para a Home continuar compilável sem quem os injeta (o Nav
    // antigo não passa esses quatro); quem alimenta é a raiz, no commit que
    // traz as rotas novas.
    aoVerNotificacoes: () -> Unit = {},
    aoNovaDespesa: () -> Unit = {},
    temaEscuro: Boolean = false,
    aoAlternarTema: () -> Unit = {},
) {
    val estado by vm.estado.collectAsState()

    // Estado local da UI
    var ocultarSaldo by remember { mutableStateOf(false) }
    var abaAtual by remember { mutableStateOf(0) }

    // TopBar callbacks. O tema NÃO mora aqui: quem persiste e reaplica a
    // escolha é a raiz (MainActivity) — um `dark` local só trocava o ícone e
    // sumia ao fechar o app.
    val aoToggleTema = { aoAlternarTema() }
    val aoToggleOcultarSaldo = { ocultarSaldo = !ocultarSaldo }

    // TabBar navegação
    val aoClicarAba = { index: Int ->
        when (index) {
            0 -> {} // Início - já estamos aqui
            1 -> aoVerContas()
            2 -> aoVerHistorico()
            3 -> aoVerCobrancas()
            4 -> aoVerPerfil()
        }
        abaAtual = index
    }

    Scaffold(
        topBar = {
            KpTopBar(
                saudacao = "Olá",
                aoToggleTema = aoToggleTema,
                aoVerNotificacoes = aoVerNotificacoes,
                aoToggleOcultarSaldo = aoToggleOcultarSaldo,
                ocultarSaldo = ocultarSaldo,
                dark = temaEscuro,
                naoLidas = estado.naoLidas,
            )
        },
        bottomBar = {
            KpTabBar(
                abaAtual = abaAtual,
                aoClicarAba = aoClicarAba,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md,
                end = Spacing.md,
                top = Spacing.sm,
                bottom = Spacing.xxl + 64.dp, // espaço para TabBar
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // 1. Saldo herói
            item {
                KpHeroCardSaldo(
                    saldo = estado.carteira?.saldo ?: "0.00",
                    moeda = estado.carteira?.moeda ?: "BRL",
                    demonstrativa = estado.carteira?.demonstrativa ?: true,
                    aoVerDerivacao = aoVerDerivacao,
                    aoToggleOcultar = { ocultarSaldo = !ocultarSaldo },
                    oculto = ocultarSaldo,
                )
            }

            // Aviso demonstrativo (aparece logo abaixo do herói se demonstrativo)
            if (estado.carteira?.demonstrativa != false && !ocultarSaldo) {
                item {
                    KpAvisoSaldoDemonstrativo(
                        saldoMostrado = brl(estado.carteira?.saldo ?: "0.00")
                    )
                }
            }

            // 2. Fila de ações (LazyRow horizontal)
            item {
                KpFilaAcoes(
                    acoes = listOf(
                        { KpAcaoItem(Icons.Default.Add, "Entrada", aoNovaTransacao) },
                        { KpAcaoItem(Icons.Default.Remove, "Despesa", aoNovaDespesa) },
                        { KpAcaoItem(Icons.Default.ReceiptLong, "Boleto", aoVerContas) },
                        { KpAcaoItem(Icons.Default.QrCode, "Cobrar", aoVerCobrancas) },
                        { KpAcaoItem(Icons.Default.SwapHoriz, "Dividir", aoVerDivisoes) },
                        { KpAcaoItem(Icons.Default.TrendingUp, "Investir", aoVerInvestimentos) },
                    )
                )
            }

            // 3. Contas a pagar (bloco vertical)
            estado.contas?.let { contasResponse ->
                val contas = contasResponse.itens
                val vencidas = contas.count { it.isOverdue }
                val pendentes = contas.filter { it.status == "PENDING" }.take(3)

                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            // Header do bloco
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Contas a pagar", style = TituloCard)
                                if (vencidas > 0) {
                                    KpBadgeCritico(
                                        texto = if (vencidas == 1) "$vencidas vencida" else "$vencidas vencidas",
                                    )
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
                                    LinhaContaHome(conta) {
                                        if (conta.status == "PENDING") {
                                            KpBotaoSecundario(
                                                texto = "Marcar paga",
                                                onClick = { vm.pagarConta(conta.id, null) },
                                                cor = MaterialTheme.colorScheme.primary,
                                            )
                                        } else {
                                            KpBadgeNeutra(conta.status)
                                        }
                                    }
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
            }

            // 4. Carrossel "Descubra mais"
            item {
                KpCarrossel(
                    itens = listOf(
                        { KpCarrosselItem(Icons.Default.Info, "Saldo explicado", "Veja de onde vem cada centavo do seu saldo", aoVerDerivacao) },
                        { KpCarrosselItem(Icons.Default.SwapHoriz, "Divisões", "Divida contas com amigos e acompanhe quem deve", aoVerDivisoes) },
                        { KpCarrosselItem(Icons.Default.PieChart, "Relatório do mês", "Entradas, saídas e para onde foi o dinheiro", aoVerRelatorio) },
                        { KpCarrosselItem(Icons.Default.Label, "Categorias", "Classifique entradas e despesas do seu jeito", aoVerCategorias) },
                    )
                )
            }

            // 5. Últimos lançamentos
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Últimos lançamentos", style = TituloCard)
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
                                if (i < itens.take(5).lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(Spacing.xxl)) }
        }
    }
}

/** Linha de conta para Home (versão simplificada). */
@Composable
fun LinhaContaHome(conta: ItemConta, trailing: @Composable () -> Unit = {}) {
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
                Icons.Default.ReceiptLong,
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
                if (conta.isOverdue) "Vencida em ${com.kavelipay.app.ui.components.formatarData(conta.dueDate)}" else "Vence ${com.kavelipay.app.ui.components.formatarData(conta.dueDate)}",
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