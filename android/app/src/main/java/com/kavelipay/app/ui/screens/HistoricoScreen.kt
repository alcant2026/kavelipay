package com.kavelipay.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
        // Filtros locais (texto + mês) sobre o que o servidor já mandou. `kind`
        // continua no servidor — é o único filtro que ele entende.
        var busca by rememberSaveable { mutableStateOf("") }
        var mes by rememberSaveable { mutableStateOf<String?>(null) }

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
                KpCampo(
                    valor = busca,
                    aoMudar = { busca = it },
                    rotulo = "Buscar no histórico",
                    umaLinha = true,
                )
            }

            // Mês: só aparece se houver mais de um mês na lista. Com um mês
            // só, o seletor seria uma escolha sem escolha.
            val meses = FiltroHistorico.meses(estado.historico?.itens.orEmpty())
            if (meses.size > 1) {
                item {
                    KpFiltroMes(
                        meses = meses,
                        escolhido = mes,
                        aoEscolher = { mes = it },
                    )
                }
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

            val todos = estado.historico?.itens.orEmpty()
            val historico = FiltroHistorico.aplicar(todos, busca, mes)
            val filtrando = busca.isNotBlank() || mes != null
            val saldo = estado.historico?.balance ?: "0.00"

            if (filtrando) {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            KpLinhaResumo(
                                rotulo = if (historico.size == 1) {
                                    "1 lançamento encontrado"
                                } else {
                                    "${historico.size} lançamentos encontrados"
                                },
                                valor = "de ${todos.size}",
                                negrito = true,
                            )
                            KpBotaoSecundario(
                                texto = "Limpar filtros",
                                onClick = { busca = ""; mes = null },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

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
                            titulo = if (filtrando) "Nada encontrado" else "Histórico vazio",
                            descricao = if (filtrando) {
                                "Nenhum lançamento casa com esses filtros."
                            } else {
                                "Registre transações, contas ou cobranças para aparecer aqui."
                            },
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
 * Seletor de mês: `Todos` + um item por mês presente no histórico.
 *
 * Uma linha que rola na horizontal (o mesmo padrão da fila de filtros por
 * tipo, logo acima): com um toque a pessoa vê os meses que existem em vez de
 * abrir uma lista e procurar. O rótulo é pt-BR (`novembro`), derivado do
 * `YYYY-MM`.
 */
@Composable
private fun KpFiltroMes(
    meses: List<String>,
    escolhido: String?,
    aoEscolher: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        KpBotaoSecundario(
            texto = "Todos",
            onClick = { aoEscolher(null) },
            cor = if (escolhido == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        meses.forEach { m ->
            KpBotaoSecundario(
                texto = rotuloMes(m),
                onClick = { aoEscolher(m) },
                cor = if (escolhido == m) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** `2026-11` → `novembro` (pt-BR). Mês inválido volta cru, nunca quebra a tela. */
internal fun rotuloMes(mes: String): String = runCatching {
    val fmt = java.text.SimpleDateFormat("MMMM", java.util.Locale("pt", "BR"))
    fmt.format(fmt.parse("${mes.trim()}-01") ?: return mes)
}.getOrDefault(mes)

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