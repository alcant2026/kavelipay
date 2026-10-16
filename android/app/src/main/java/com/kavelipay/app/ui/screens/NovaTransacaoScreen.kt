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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpEtiqueta
import com.kavelipay.app.ui.components.KpLinhaResumo
import com.kavelipay.app.ui.components.Money.brl
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.RaioCard
import com.kavelipay.app.ui.theme.RaioInput
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloSecao

/**
 * Novo lançamento: entrada ou despesa.
 *
 * O campo de valor aceita SOMENTE string. Um `TextField` de número real com
 * keypad decimal gera `Double` no estado, e é exatamente aí que a regra de
 * dinheiro quebraria — `10.10` viraria `10.1`. Aqui o texto vai cru pro
 * backend, que valida.
 */
@Composable
fun KpNovaTransacaoScreen(
    vm: AppViewModel,
    aoVoltar: () -> Unit,
    aoSalvar: () -> Unit,
    categorias: List<com.kavelipay.app.data.Categoria>,
    // O botão "Despesa" da Home abre a tela já com o tipo certo; sem isto a
    // pessoa clicava em Despesa, caía em "Entrada" e ainda tinha que trocar.
    entradaInicial: Boolean = true,
) {
    val estado by vm.estado.collectAsState()
    val sem = KpTheme.semanticas

    var entrada by rememberSaveable(entradaInicial) { mutableStateOf(entradaInicial) }
    var valor by rememberSaveable { mutableStateOf("") }
    var descricao by rememberSaveable { mutableStateOf("") }
    var categoriaId by rememberSaveable { mutableStateOf<String?>(null) }

    val categoriasDoTipo = categorias.filter {
        it.kind == if (entrada) "INCOME" else "EXPENSE"
    }

    Scaffold(topBar = { CabecalhoComVoltar("Novo lançamento", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                // Seletor Entrada/Despesa. Pills grandes em vez de switch: o
                // usuário decide o tipo uma vez e erra menos. O estilo VERDE é
                // do SELECIONADO — antes era fixo (Entrada sempre verde) e a
                // pessoa abrindo por "Despesa" via a tela toda errada.
                val aoEscolherEntrada = { entrada = true; categoriaId = null }
                val aoEscolherDespesa = { entrada = false; categoriaId = null }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (entrada) {
                        KpBotaoPrimario(
                            texto = "Entrada",
                            onClick = aoEscolherEntrada,
                            modifier = Modifier.weight(1f),
                        )
                        KpBotaoSecundario(
                            texto = "Despesa",
                            onClick = aoEscolherDespesa,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        KpBotaoSecundario(
                            texto = "Entrada",
                            onClick = aoEscolherEntrada,
                            modifier = Modifier.weight(1f),
                        )
                        KpBotaoPrimario(
                            texto = "Despesa",
                            onClick = aoEscolherDespesa,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text(
                            "Valor",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "R\$",
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (entrada) sem.receita else sem.despesa,
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            KpCampo(
                                valor = valor,
                                aoMudar = { valor = filtrarValor(it) },
                                rotulo = "0,00",
                                teclado = KeyboardType.Decimal,
                                imeAction = ImeAction.Next,
                                umaLinha = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "Use vírgula para os centavos: 10,50",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        KpCampo(
                            valor = descricao,
                            aoMudar = { descricao = it },
                            rotulo = if (entrada) "Ex.: Salário de outubro" else "Ex.: Mercado",
                            imeAction = ImeAction.Done,
                        )
                    }
                }
            }

            if (categoriasDoTipo.isNotEmpty()) {
                item {
                    Column {
                        Text("Categoria", style = TituloSecao)
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "Opcional — dá para classificar depois.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            items(categoriasDoTipo, key = { it.id }) { cat ->
                                val selecionada = categoriaId == cat.id
                                FilterChip(
                                    selected = selecionada,
                                    onClick = {
                                        categoriaId = if (selecionada) null else cat.id
                                    },
                                    label = { Text(cat.nome) },
                                    leadingIcon = if (selecionada) {
                                        { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                                    } else null,
                                    shape = RoundedCornerShape(com.kavelipay.app.ui.theme.Pilula),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            if (estado.erro != null) {
                item { KpMensagemErro(estado.erro.orEmpty()) }
            }

            item {
                KpBotaoPrimario(
                    texto = if (entrada) "Registrar entrada" else "Registrar despesa",
                    onClick = {
                        vm.registrarLancamento(
                            tipo = if (entrada) "ENTRY" else "EXPENSE",
                            amount = valor,
                            descricao = descricao,
                            categoriaId = categoriaId,
                            onOk = aoSalvar,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    // Backend exige valor > 0; habilitar com texto vazio ou
                    // só com "." geraria um 422 evitável.
                    habilitado = valor.toDoubleOrNull()?.let { it > 0.0 } == true,
                    carregando = estado.enviando,
                )
            }

            // Sem "saldo previsto" aqui de propósito: o app não sabe o saldo
            // atual, então mostraria um número local — e número de saldo que o
            // cliente calcula é exatamente o que a spec proíbe. Quem mostra o
            // saldo depois do save é o backend, recalculando do ledger.
        }
    }
}

/**
 * Mantém apenas dígitos e um separador decimal, e normaliza para ponto — que é
 * o que o backend espera (`NUMERIC` não aceita vírgula).
 */
internal fun filtrarValor(entrada: String): String {
    val limpo = entrada.filter { it.isDigit() || it == ',' || it == '.' }
    val normalizado = limpo.replace(',', '.')
    // Uma vírgula/ponto só: "1.2.3" não é número.
    val partes = normalizado.split('.')
    return if (partes.size > 2) {
        partes[0] + "." + partes.drop(1).joinToString("")
    } else normalizado
}
