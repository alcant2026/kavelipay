package com.kavelipay.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.kavelipay.app.data.ItemCaixinha
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBadgeNeutra
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpLinhaResumo
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.components.Money
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloCard

/**
 * Lista de caixinhas — empréstimos registrados entre conhecidos.
 *
 * Fora do ledger: o dinheiro combinado entre as pessoas nunca passa pelo app.
 * A tela só mostra o que o servidor já calculou (`arrecadado`, `progresso`,
 * média) e divide a lista por papel, porque as ações do detalhe mudam.
 */
@Composable
fun KpCaixinhasScreen(
    vm: AppViewModel,
    aoVoltar: () -> Unit,
    aoNova: () -> Unit,
    aoAbrirDetalhe: (String) -> Unit,
) {
    val estado by vm.estado.collectAsState()

    LaunchedEffect(Unit) { vm.carregarCaixinhas() }

    var mostrarEntrar by rememberSaveable { mutableStateOf(false) }
    var chave by rememberSaveable { mutableStateOf("") }

    Scaffold(topBar = { CabecalhoComVoltar("Caixinhas", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md,
                end = Spacing.md,
                top = Spacing.sm,
                bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text("Empréstimo entre conhecidos", style = TituloCard)
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "Registre quem emprestou quanto e acompanhe os pagamentos. " +
                                "O dinheiro não passa pelo KaveliPay: cada um paga o outro " +
                                "como combinaram. Só entra quem recebe o código do responsável.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    KpBotaoPrimario(
                        texto = "Nova caixinha",
                        onClick = aoNova,
                        icone = Icons.Default.Add,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    KpBotaoSecundario(
                        texto = "Entrar com código",
                        onClick = { mostrarEntrar = true },
                        icone = Icons.Default.Login,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            estado.erro?.let { mensagem ->
                item { KpMensagemErro(mensagem) }
            }

            val itens = estado.caixinhas?.itens
            when {
                estado.carregando && itens == null -> item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.xl),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                itens.isNullOrEmpty() -> item {
                    KpVazio(
                        icone = Icons.Default.Savings,
                        titulo = "Nenhuma caixinha ainda",
                        descricao = "Crie uma para registrar um empréstimo ou entre com o " +
                            "código que o responsável mandou.",
                    )
                }

                else -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        secao("Como responsável", itens.filter { it.papel == "dono" }) {
                            aoAbrirDetalhe(it)
                        }
                        secao("Como membro", itens.filter { it.papel == "membro" }) {
                            aoAbrirDetalhe(it)
                        }
                    }
                }
            }
        }
    }

    if (mostrarEntrar) {
        AlertDialog(
            onDismissRequest = { if (!estado.enviando) mostrarEntrar = false },
            title = { Text("Entrar com código") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        "Digite o código que o responsável da caixinha mandou (KAV-XXXXXXXXXXXX).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    KpCampo(
                        valor = chave,
                        aoMudar = { chave = it.uppercase().take(20) },
                        rotulo = "KAV-000000000000",
                        imeAction = ImeAction.Done,
                        umaLinha = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = chave.isNotBlank() && !estado.enviando,
                    onClick = {
                        vm.entrarCaixinha(chave) { id ->
                            mostrarEntrar = false
                            chave = ""
                            aoAbrirDetalhe(id)
                        }
                    },
                ) {
                    Text(if (estado.enviando) "Entrando…" else "Entrar")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !estado.enviando,
                    onClick = { mostrarEntrar = false },
                ) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun secao(
    titulo: String,
    itens: List<ItemCaixinha>,
    aoAbrir: (String) -> Unit,
) {
    if (itens.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            titulo,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        itens.forEach { item ->
            KpCardCaixinha(item, onClick = { aoAbrir(item.id) })
        }
    }
}

@Composable
private fun KpCardCaixinha(item: ItemCaixinha, onClick: () -> Unit) {
    KpCard(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.nome,
                    style = TituloCard,
                    modifier = Modifier.weight(1f),
                )
                KpBadgeNeutra(if (item.papel == "dono") "Responsável" else "Membro")
            }
            Spacer(Modifier.height(Spacing.sm))
            KpLinhaResumo(
                rotulo = "Arrecadado",
                valor = "R$ ${Money.brl(item.arrecadado)} de R$ ${Money.brl(item.valorPedido)}",
            )
            Spacer(Modifier.height(Spacing.xs))
            KpLinhaResumo(
                rotulo = "Taxa ao mês",
                valor = "${item.taxaMensal.replace('.', ',')}%",
            )
            Spacer(Modifier.height(Spacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${(item.progresso * 100).toInt()}% do pedido",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Reputação ${item.responsavelScore}/1000",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
