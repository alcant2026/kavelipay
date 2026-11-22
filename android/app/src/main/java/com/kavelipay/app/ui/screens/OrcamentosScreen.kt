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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DonutSmall
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kavelipay.app.data.Categoria
import com.kavelipay.app.data.Orcamento
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.components.Money
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloCard
import com.kavelipay.app.ui.theme.TituloSecao
import java.math.BigDecimal
import java.time.YearMonth

/**
 * Orçamento: teto de gasto por categoria e para o mês inteiro.
 *
 * O valor gasto é derivado do ledger (nunca recalculado aqui) — a tela só
 * mostra o progresso e deixa criar/remover tetos.
 */
@Composable
fun KpOrcamentosScreen(
    vm: AppViewModel,
    aoVoltar: () -> Unit,
    aoNovaCategoria: () -> Unit = {},
) {
    val estado by vm.estado.collectAsState()
    val sem = KpTheme.semanticas
    var mes by remember { mutableStateOf(YearMonth.now().toString()) }
    LaunchedEffect(mes) { vm.carregarOrcamentos(mes) }

    val orcamentos = estado.orcamentos
    var mostrarNovo by remember { mutableStateOf(false) }

    Scaffold(topBar = { CabecalhoComVoltar("Orçamento", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // Seletor de mês
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        mes = YearMonth.parse(mes).minusMonths(1).toString()
                    }) { Text("‹ anterior") }
                    Text(formatarMes(mes), style = TituloSecao)
                    TextButton(onClick = {
                        mes = YearMonth.parse(mes).plusMonths(1).toString()
                    }) { Text("próximo ›") }
                }
            }

            item {
                KpBotaoPrimario(
                    texto = "Novo teto",
                    onClick = { mostrarNovo = true },
                    modifier = Modifier.fillMaxWidth(),
                    icone = Icons.Default.Add,
                )
            }

            if (orcamentos == null) {
                item {
                    if (estado.carregando) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator()
                        }
                    } else {
                        KpCard(Modifier.fillMaxWidth()) {
                            Text(
                                estado.erro ?: "Não foi possível carregar os tetos.",
                                modifier = Modifier.padding(Spacing.md),
                            )
                        }
                    }
                }
            } else if (orcamentos.isEmpty()) {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        KpVazio(
                            icone = Icons.Default.DonutSmall,
                            titulo = "Nenhum teto definido",
                            descricao = "Defina quanto quer gastar por categoria ou no mês inteiro.",
                        )
                    }
                }
            } else {
                item { Text("Tetos para ${formatarMes(mes)}", style = TituloSecao) }
                items(orcamentos, key = { it.id }) { orcamento ->
                    KpOrcamentoCard(
                        orcamento = orcamento,
                        aoRemover = { vm.apagarOrcamento(orcamento.id, mes) },
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

    if (mostrarNovo) {
        KpNovoOrcamentoDialog(
            categorias = estado.categorias.filter { it.kind == "EXPENSE" },
            mes = mes,
            aoVoltar = { mostrarNovo = false },
            aoSalvar = { catId, valor ->
                vm.criarOrcamento(catId, valor, mes) { mostrarNovo = false }
            },
        )
    }
}

@Composable
private fun KpOrcamentoCard(orcamento: Orcamento, aoRemover: () -> Unit) {
    val sem = KpTheme.semanticas
    KpCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        orcamento.categoryColor?.let { cor ->
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(parseCor(cor)),
                            )
                            Spacer(Modifier.width(Spacing.xs))
                        }
                        Text(orcamento.categoryName, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (orcamento.mes != null) {
                        Text(
                            "Teto para ${formatarMes(orcamento.mes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = aoRemover) {
                    Icon(Icons.Default.Delete, contentDescription = "Remover teto", tint = sem.vencido)
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            KpLinhaResumo(
                rotulo = "Teto",
                valor = "R$ ${Money.brl(orcamento.valor)}",
                negrito = true,
            )
            Spacer(Modifier.height(Spacing.xs))
            KpLinhaResumo(
                rotulo = "Gasto",
                valor = "R$ ${Money.brl(orcamento.gasto)}",
                corValor = if (orcamento.progresso > 1f) sem.vencido else null,
            )
            Spacer(Modifier.height(Spacing.sm))
            // Barra de progresso. `progresso` pode passar de 1 (estourou o teto):
            // limita a 1 para a barra não estourar a largura, mas a cor fica
            // vermelha para o usuário ver que passou.
            LinearProgressIndicator(
                progress = { orcamento.progresso.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = if (orcamento.progresso > 1f) sem.vencido else MaterialTheme.colorScheme.primary,
            )
            if (orcamento.progresso > 1f) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "Ultrapassou o teto em ${((orcamento.progresso - 1f) * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = sem.vencido,
                )
            }
        }
    }
}

@Composable
private fun KpNovoOrcamentoDialog(
    categorias: List<Categoria>,
    mes: String,
    aoVoltar: () -> Unit,
    aoSalvar: (String?, String) -> Unit,
) {
    var valor by remember { mutableStateOf("") }
    var categoriaId by remember { mutableStateOf<String?>(null) }

    val valorOk = valor.toBigDecimalOrNull()?.let { it > BigDecimal.ZERO } ?: false

    AlertDialog(
        onDismissRequest = aoVoltar,
        title = { Text("Novo teto") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    "Defina quanto quer gastar. Sem categoria = teto do mês inteiro.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                KpCampo(
                    valor = valor,
                    aoMudar = { valor = filtrarValor(it) },
                    rotulo = "Valor (ex.: 200,00)",
                    teclado = KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                    umaLinha = true,
                )
                Text("Categoria", style = MaterialTheme.typography.labelSmall)
                // Teto geral (sem categoria) + uma opção por categoria
                KpCategoriaChip(
                    texto = "Todas as categorias",
                    selecionado = categoriaId == null,
                    onClick = { categoriaId = null },
                )
                categorias.forEach { cat ->
                    KpCategoriaChip(
                        texto = cat.nome,
                        cor = cat.cor,
                        selecionado = categoriaId == cat.id,
                        onClick = { categoriaId = cat.id },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { aoSalvar(categoriaId, valor) },
                enabled = valorOk,
            ) { Text("Salvar") }
        },
        dismissButton = {
            TextButton(onClick = aoVoltar) { Text("Cancelar") }
        },
    )
}

@Composable
private fun KpCategoriaChip(texto: String, cor: String? = null, selecionado: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cor != null) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(parseCor(cor)),
            )
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(
            texto,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selecionado) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun parseCor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color.Gray)

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

// Reutiliza o KpLinhaResumo que já existe em ContasScreen — mas como ele é
// `private` lá, esta tela usa a versão local abaixo para não acoplar.
@Composable
private fun KpLinhaResumo(rotulo: String, valor: String, negrito: Boolean = false, corValor: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            rotulo,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            valor,
            style = if (negrito) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            color = corValor ?: MaterialTheme.colorScheme.onSurface,
            fontWeight = if (negrito) androidx.compose.ui.text.font.FontWeight.Bold else null,
        )
    }
}