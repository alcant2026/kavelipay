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
import androidx.compose.material.icons.filled.GroupWork
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kavelipay.app.data.ItemHistorico
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpEtiqueta
import com.kavelipay.app.ui.components.KpEstruturaAba
import com.kavelipay.app.ui.components.KpLinhaResumo
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.components.Money
import com.kavelipay.app.ui.components.formatarData
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.RotuloCaixaAlta
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloSecao

/** Histórico unificado (transações + contas + cobranças + divisões). */
@Composable
fun KpHistoricoScreen(vm: AppViewModel, aoMudarAba: (Int) -> Unit = {}) {
    val estado by vm.estado.collectAsState()
    val sem = KpTheme.semanticas

    LaunchedEffect(Unit) {
        vm.carregarHistorico()
    }

    KpEstruturaAba(
        abaAtual = 2,
        aoMudarAba = aoMudarAba,
        topBar = { CabecalhoAba("Histórico") },
    ) { padding ->
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

            // Filtros
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    KpBotaoSecundario(
                        texto = "Todas",
                        onClick = { vm.carregarHistorico(kind = null) },
                        icone = Icons.Default.SwapHoriz,
                        modifier = Modifier.weight(1f),
                    )
                    KpBotaoSecundario(
                        texto = "Transações",
                        onClick = { vm.carregarHistorico(kind = "TRANSACTION") },
                        modifier = Modifier.weight(1f),
                    )
                    KpBotaoSecundario(
                        texto = "Contas",
                        onClick = { vm.carregarHistorico(kind = "BILL") },
                        modifier = Modifier.weight(1f),
                    )
                    KpBotaoSecundario(
                        texto = "Cobranças",
                        onClick = { vm.carregarHistorico(kind = "CHARGE") },
                        modifier = Modifier.weight(1f),
                    )
                    KpBotaoSecundario(
                        texto = "Divisões",
                        onClick = { vm.carregarHistorico(kind = "SPLIT") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            val historico = estado.historico?.itens.orEmpty()
            val saldo = estado.historico?.balance ?: "0.00"

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    KpLinhaResumo(
                        rotulo = "Saldo no histórico",
                        valor = "R$ ${Money.brl(saldo)}",
                        negrito = true,
                    )
                }
            }

            if (historico.isEmpty()) {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        KpVazio(
                            icone = Icons.Default.SwapHoriz,
                            titulo = "Histórico vazio",
                            descricao = "Registre transações, contas ou cobranças para aparecer aqui.",
                        )
                    }
                }
            } else {
                item { Text("Itens", style = TituloSecao) }
                // Um `item` por linha, e NÃO um `item { Column { forEach } }`:
                // dentro do `Column` o escopo do `LazyListScope` (e o `item { }`
                // que ele oferece) deixa de estar visível para o código que
                // segue, e o compilador acusa "cannot infer type" nos parâmetros.
                items(historico.size) { indice ->
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = Spacing.md)) {
                            val linha = historico[indice]
                            LinhaHistorico(linha)
                            if (indice < historico.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Cor e ícone por tipo de item do histórico.
 *
 * `Pair` associativo NÃO serve aqui: `a to b to c to d` produz
 * `Pair<Pair<...>,...>` e a destructuring de 3 valores pediria um `component3()`
 * que não existe. [Triple] aninhado também não cabe; uma data class é a forma
 * que o compilador entende e que continua legível quando um campo muda.
 */
private data class EstiloHistorico(
    val icone: ImageVector,
    val cor: Color,
    val fundo: Color,
)

@Composable
private fun LinhaHistorico(item: ItemHistorico) {
    val sem = KpTheme.semanticas
    val estilo = when (item.kind) {
        "TRANSACTION" -> EstiloHistorico(
            icone = Icons.Default.SwapHoriz,
            cor = if (item.isSaida) sem.despesa else sem.receita,
            fundo = if (item.isSaida) sem.despesaFundo else sem.receitaFundo,
        )
        "BILL" -> EstiloHistorico(Icons.Default.ReceiptLong, sem.aviso, sem.avisoFundo)
        "CHARGE" -> EstiloHistorico(Icons.Default.QrCode, sem.receita, sem.receitaFundo)
        "SPLIT" -> EstiloHistorico(Icons.Default.GroupWork, sem.aviso, sem.avisoFundo)
        else -> EstiloHistorico(
            Icons.Default.Help,
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surfaceVariant,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(19.dp)).background(estilo.fundo),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = estilo.icone,
                contentDescription = null,
                tint = estilo.cor,
                modifier = Modifier.size(19.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(
                item.kind,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${if (item.isSaida) "−" else "+"} R$ ${Money.brl(item.amount)}",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                color = if (item.isSaida) sem.despesa else sem.receita,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                formatarData(item.criadoEm),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}