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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kavelipay.app.data.Meta
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.components.Money
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloSecao
import java.math.BigDecimal

/**
 * Metas de economia: quanto a pessoa quer juntar e quanto já guardou.
 *
 * O progresso é derivado no backend (valor inicial + aportes confirmados) —
 * a tela só mostra e deixa criar, aportar, arquivar e remover.
 */
@Composable
fun KpMetasScreen(
    vm: AppViewModel,
    aoVoltar: () -> Unit,
) {
    val estado by vm.estado.collectAsState()
    val sem = KpTheme.semanticas
    LaunchedEffect(Unit) { vm.carregarMetas() }

    val metas = estado.metas
    var mostrarNova by remember { mutableStateOf(false) }
    var metaEmAporte by remember { mutableStateOf<Meta?>(null) }

    Scaffold(topBar = { CabecalhoComVoltar("Metas", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                KpBotaoPrimario(
                    texto = "Nova meta",
                    onClick = { mostrarNova = true },
                    modifier = Modifier.fillMaxWidth(),
                    icone = Icons.Default.Add,
                )
            }

            if (metas == null) {
                item {
                    if (estado.carregando) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator()
                        }
                    } else {
                        KpCard(Modifier.fillMaxWidth()) {
                            Text(
                                estado.erro ?: "Não foi possível carregar as metas.",
                                modifier = Modifier.padding(Spacing.md),
                            )
                        }
                    }
                }
            } else if (metas.isEmpty()) {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        KpVazio(
                            icone = Icons.Default.Flag,
                            titulo = "Nenhuma meta ainda",
                            descricao = "Defina quanto quer juntar para uma viagem, reserva ou objetivo.",
                        )
                    }
                }
            } else {
                items(metas, key = { it.id }) { meta ->
                    KpMetaCard(
                        meta = meta,
                        aoAportar = { metaEmAporte = meta },
                        aoArquivar = { vm.arquivarMeta(meta.id, true) },
                        aoRemover = { vm.apagarMeta(meta.id) },
                    )
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

    if (mostrarNova) {
        KpNovaMetaDialog(
            aoVoltar = { mostrarNova = false },
            aoSalvar = { nome, alvo, inicial, prazo ->
                vm.criarMeta(nome, alvo, inicial, prazo) { mostrarNova = false }
            },
        )
    }

    metaEmAporte?.let { meta ->
        KpAporteDialog(
            meta = meta,
            aoVoltar = { metaEmAporte = null },
            aoSalvar = { valor ->
                vm.aportarMeta(meta.id, valor)
                metaEmAporte = null
            },
        )
    }
}

@Composable
private fun KpMetaCard(
    meta: Meta,
    aoAportar: () -> Unit,
    aoArquivar: () -> Unit,
    aoRemover: () -> Unit,
) {
    val sem = KpTheme.semanticas
    KpCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(meta.nome, style = MaterialTheme.typography.bodyLarge)
                Row {
                    IconButton(onClick = aoAportar) {
                        Icon(Icons.Default.Add, contentDescription = "Guardar dinheiro")
                    }
                    IconButton(onClick = aoArquivar) {
                        Icon(Icons.Default.Edit, contentDescription = "Arquivar")
                    }
                    IconButton(onClick = aoRemover) {
                        Icon(Icons.Default.Delete, contentDescription = "Remover", tint = sem.vencido)
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Guardado",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "R$ ${Money.brl(meta.guardado)}",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Alvo",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("R$ ${Money.brl(meta.valorAlvo)}")
            }
            Spacer(Modifier.height(Spacing.sm))
            LinearProgressIndicator(
                progress = { meta.progresso.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = if (meta.progresso >= 1f) sem.receita else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(Spacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${(meta.progresso * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (meta.falta != "0.00") {
                    Text(
                        "Falta R$ ${Money.brl(meta.falta)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Meta atingida!",
                        style = MaterialTheme.typography.labelSmall,
                        color = sem.receita,
                    )
                }
            }
            meta.prazo?.let { prazo ->
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "Prazo: $prazo",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun KpNovaMetaDialog(
    aoVoltar: () -> Unit,
    aoSalvar: (String, String, String?, String?) -> Unit,
) {
    var nome by remember { mutableStateOf("") }
    var valorAlvo by remember { mutableStateOf("") }
    var valorInicial by remember { mutableStateOf("") }
    var prazo by remember { mutableStateOf("") }

    val alvoOk = valorAlvo.toBigDecimalOrNull()?.let { it > BigDecimal.ZERO } ?: false
    val podeSalvar = nome.isNotBlank() && alvoOk

    AlertDialog(
        onDismissRequest = aoVoltar,
        title = { Text("Nova meta") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                KpCampo(
                    valor = nome,
                    aoMudar = { nome = it },
                    rotulo = "Nome (ex.: Viagem)",
                    imeAction = ImeAction.Next,
                    umaLinha = true,
                )
                KpCampo(
                    valor = valorAlvo,
                    aoMudar = { valorAlvo = filtrarValor(it) },
                    rotulo = "Quanto quer juntar",
                    teclado = KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                    umaLinha = true,
                )
                KpCampo(
                    valor = valorInicial,
                    aoMudar = { valorInicial = filtrarValor(it) },
                    rotulo = "Já tem guardado? (opcional)",
                    teclado = KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                    umaLinha = true,
                )
                KpCampo(
                    valor = prazo,
                    aoMudar = { prazo = it },
                    rotulo = "Prazo (dd/mm/aaaa, opcional)",
                    teclado = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                    umaLinha = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val prazoIso = if (prazo.isNotBlank()) dataParaIso(prazo) else null
                    aoSalvar(nome.trim(), valorAlvo, valorInicial.ifBlank { null }, prazoIso)
                },
                enabled = podeSalvar,
            ) { Text("Salvar") }
        },
        dismissButton = {
            TextButton(onClick = aoVoltar) { Text("Cancelar") }
        },
    )
}

@Composable
private fun KpAporteDialog(
    meta: Meta,
    aoVoltar: () -> Unit,
    aoSalvar: (String) -> Unit,
) {
    var valor by remember { mutableStateOf("") }
    val valorOk = valor.toBigDecimalOrNull()?.let { it > BigDecimal.ZERO } ?: false

    AlertDialog(
        onDismissRequest = aoVoltar,
        title = { Text("Guardar para ${meta.nome}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "Isso registra um aporte: o dinheiro sai da carteira e fica reservado para esta meta.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                KpCampo(
                    valor = valor,
                    aoMudar = { valor = filtrarValor(it) },
                    rotulo = "Valor",
                    teclado = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                    umaLinha = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { aoSalvar(valor) },
                enabled = valorOk,
            ) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = aoVoltar) { Text("Cancelar") }
        },
    )
}