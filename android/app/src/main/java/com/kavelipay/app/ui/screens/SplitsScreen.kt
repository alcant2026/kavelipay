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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kavelipay.app.data.ItemSplit
import com.kavelipay.app.data.ItemSplitParte
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpAvisoSaldoDemonstrativo
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpEtiqueta
import com.kavelipay.app.ui.components.KpLinhaResumo
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.components.Money
import com.kavelipay.app.ui.components.formatarData
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.RotuloCaixaAlta
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloCard
import com.kavelipay.app.ui.theme.TituloSecao

/**
 * Lista de divisões de despesa: o usuário adiantou e as partes devem a ele.
 */
@Composable
fun KpSplitsScreen(
    vm: AppViewModel,
    aoVoltar: () -> Unit,
    aoNovaSplit: () -> Unit = {},
    aoAbrirDetalhe: (String) -> Unit = {},
) {
    val estado by vm.estado.collectAsState()

    LaunchedEffect(Unit) {
        vm.carregarSplits()
    }

    Scaffold(topBar = { CabecalhoComVoltar("Divisão de despesa", aoVoltar) }) { padding ->
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

            // Erro de ação (409/422 do backend) precisa aparecer: sem isto,
            // "Paguei" repetido falharia em silêncio.
            if (estado.erro != null) {
                item { KpMensagemErro(estado.erro.orEmpty()) }
            }

            item {
                KpBotaoPrimario(
                    texto = "Nova divisão",
                    onClick = aoNovaSplit,
                    icone = Icons.Default.Add,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val splits = estado.splits?.itens.orEmpty()
            if (splits.isEmpty()) {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        KpVazio(
                            icone = Icons.Default.Payments,
                            titulo = "Nenhuma divisão",
                            descricao = "Crie uma divisão para repartir uma despesa.",
                        )
                    }
                }
            } else {
                item { Text("Divisões", style = TituloSecao) }
                // `items(count)` e não `forEachIndexed` com `item { }` aninhado:
                // fora do escopo do LazyListScope, o `item` não resolve.
                items(splits.size) { indice ->
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = Spacing.md)) {
                            val linha = splits[indice]
                            LinhaSplit(linha, aoAbrirDetalhe)
                            if (indice < splits.lastIndex) HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun LinhaSplit(split: ItemSplit, aoAbrirDetalhe: (String) -> Unit) {
    val sem = KpTheme.semanticas
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
            .clickable { aoAbrirDetalhe(split.id) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(split.description, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(
                "${split.partes.size} pessoas · a receber R$ ${Money.brl(split.pendingAmount)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "R$ ${Money.brl(split.totalAmount)}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (split.pendingAmount != "0.00") {
            Spacer(Modifier.width(Spacing.sm))
            KpEtiqueta("PENDENTE", sem.aviso, fundo = sem.avisoFundo)
        } else {
            Spacer(Modifier.width(Spacing.sm))
            KpEtiqueta("QUITADO", sem.receita, fundo = sem.receitaFundo)
        }
    }
}

/** Detalhe de uma divisão: partes, quem já pagou, botão de quitar. */
@Composable
fun KpSplitsDetalheScreen(vm: AppViewModel, splitId: String, aoVoltar: () -> Unit) {
    val estado by vm.estado.collectAsState()
    val split = estado.splitDetalhe
    val sem = KpTheme.semanticas

    LaunchedEffect(splitId) {
        vm.carregarSplitDetalhe(splitId)
    }

    Scaffold(topBar = { CabecalhoComVoltar(split?.description ?: "Detalhe", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            split?.let { s ->
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            Text("Total", style = RotuloCaixaAlta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(Spacing.xs))
                            Text("R$ ${Money.brl(s.totalAmount)}", style = com.kavelipay.app.ui.theme.SaldoDisplay)
                            Spacer(Modifier.height(Spacing.md))
                            KpLinhaResumo(
                                rotulo = "A receber",
                                valor = "R$ ${Money.brl(s.pendingAmount)}",
                                corValor = sem.aviso,
                                negrito = true,
                            )
                        }
                    }
                }

                item { Text("Participantes", style = TituloSecao) }
                // `items(count)`: um `item` por parte, em vez de `item { Column
                // { forEach } }` — fora do escopo do LazyListScope o `item` some.
                items(s.partes.size) { indice ->
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = Spacing.md)) {
                            val parte = s.partes[indice]
                            LinhaParte(parte, onQuitar = { vm.quitarParte(s.id, parte.id) })
                            if (indice < s.partes.lastIndex) HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinhaParte(parte: ItemSplitParte, onQuitar: () -> Unit) {
    val sem = KpTheme.semanticas
    val quitado = parte.settledAt != null
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp))
                .background(if (quitado) sem.receitaFundo else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (quitado) Icons.Default.CheckCircle else Icons.Default.Person,
                contentDescription = null,
                tint = if (quitado) sem.receita else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(parte.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (quitado) "Quitado em ${formatarData(parte.settledAt!!)}" else "Aguardando pagamento",
                style = MaterialTheme.typography.labelSmall,
                color = if (quitado) sem.receita else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("R$ ${Money.brl(parte.amount)}", style = MaterialTheme.typography.bodyLarge)
        if (!quitado) {
            Spacer(Modifier.width(Spacing.sm))
            KpBotaoSecundario("Quitar", onClick = onQuitar, cor = sem.aviso)
        } else {
            KpEtiqueta("QUITADO", sem.receita, fundo = sem.receitaFundo)
        }
    }
}

/** Linha para formulário de nova divisão. */
@Composable
fun LinhaNovaParte(
    name: String,
    amount: String,
    onNameChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onRemove: () -> Unit,
    isFirst: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        androidx.compose.material3.TextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            placeholder = { Text("Nome") },
            singleLine = true,
            label = { Text("Nome") },
        )
        androidx.compose.material3.TextField(
            value = amount,
            onValueChange = onAmountChange,
            modifier = Modifier.width(120.dp),
            placeholder = { Text("0,00") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            ),
            label = { Text("Valor") },
        )
        if (!isFirst) {
            androidx.compose.material3.IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Remover")
            }
        }
    }
}

/** Linha reutilizável de participante no formulário — ver `NovaSplitScreen.kt`. */
@Composable
fun LinhaNovaParte(
    nome: String,
    valor: String,
    onNomeMudar: (String) -> Unit,
    onValorMudar: (String) -> Unit,
    onRemover: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        KpCampo(
            valor = nome,
            aoMudar = onNomeMudar,
            rotulo = "Nome",
            modifier = Modifier.weight(1.4f),
        )
        KpCampo(
            valor = valor,
            aoMudar = onValorMudar,
            rotulo = "0,00",
            teclado = androidx.compose.ui.text.input.KeyboardType.Decimal,
            umaLinha = true,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.IconButton(onClick = onRemover) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Default.Close,
                contentDescription = "Remover participante",
            )
        }
    }
}