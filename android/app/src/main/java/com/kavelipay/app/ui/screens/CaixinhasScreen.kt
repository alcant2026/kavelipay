package com.kavelipay.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.kavelipay.app.data.CaixinhaResumo
import com.kavelipay.app.data.formatarTaxa
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.Estrelas
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.components.Money.brl
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloCard
import java.math.RoundingMode
import kotlin.math.roundToInt

/**
 * Minhas caixinhas — a lista que o botão "Caixinha" da Home abre.
 *
 * Duas seções pelo `papel` que o SERVIDOR manda: "Como responsável" (as que
 * eu criei) e "Como membro" (as que eu entrei com convite). Nada de exemplo
 * local: lista vazia é lista vazia, com texto convidando à ação.
 */
@Composable
fun KpCaixinhasScreen(
    vm: AppViewModel,
    aoVoltar: () -> Unit,
    aoNovaCaixinha: () -> Unit,
    aoAbrirDetalhe: (String) -> Unit,
    aoVerPublicas: () -> Unit,
) {
    val estado by vm.estado.collectAsState()

    // Recarrega a cada entrada na composição: voltar do detalhe reflete o
    // que mudou lá (entrou, aportou, confirmou) — o servidor é a fonte.
    LaunchedEffect(Unit) { vm.carregarCaixinhas() }

    var dialogoChave by rememberSaveable { mutableStateOf(false) }
    if (dialogoChave) {
        DialogoEntrarComChave(
            vm = vm,
            titulo = "Entrar com código",
            aoFechar = { dialogoChave = false },
            aoEntrar = { id ->
                dialogoChave = false
                aoAbrirDetalhe(id)
            },
        )
    }

    Scaffold(topBar = { CabecalhoComVoltar("Caixinhas", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    KpBotaoPrimario(
                        texto = "Nova caixinha",
                        onClick = aoNovaCaixinha,
                        icone = Icons.Default.Add,
                        modifier = Modifier.weight(1f),
                    )
                    KpBotaoPrimario(
                        texto = "Entrar com código",
                        onClick = {
                            vm.limparErroAcaoCaixinha()
                            dialogoChave = true
                        },
                        icone = Icons.Default.Key,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                KpBotaoSecundario(
                    texto = "Ver caixinhas públicas",
                    onClick = aoVerPublicas,
                    icone = Icons.Default.Public,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val minhas = estado.caixinhas
            if (estado.carregandoCaixinhas && minhas == null) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            when {
                // Erro de leitura SEM dado nenhum na mão: mensagem + tentar de
                // novo. Com lista já carregada, uma falha de refresh não derruba
                // o que a pessoa está vendo.
                minhas == null && estado.erroCaixinhas != null ->
                    item {
                        KpCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(Spacing.md)) {
                                KpMensagemErro(estado.erroCaixinhas.orEmpty())
                                Spacer(Modifier.height(Spacing.sm))
                                KpBotaoSecundario(
                                    texto = "Tentar de novo",
                                    onClick = { vm.carregarCaixinhas() },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                minhas != null && minhas.isEmpty() ->
                    item {
                        KpCard(Modifier.fillMaxWidth()) {
                            KpVazio(
                                icone = Icons.Default.Inventory2,
                                titulo = "Nenhuma caixinha ainda",
                                descricao = "Crie a sua ou entre com o código de alguém.",
                                acao = {
                                    KpBotaoSecundario(
                                        texto = "Nova caixinha",
                                        onClick = aoNovaCaixinha,
                                    )
                                },
                            )
                        }
                    }

                minhas != null -> {
                    val comoResponsavel = minhas.filter { it.papel == "dono" }
                    val comoMembro = minhas.filter { it.papel == "membro" }

                    if (comoResponsavel.isNotEmpty()) {
                        item { Text("Como responsável", style = TituloCard) }
                        items(comoResponsavel, key = { "dono-${it.id}" }) { caixinha ->
                            CardCaixinhaResumo(caixinha, aoAbrirDetalhe)
                        }
                    }
                    if (comoMembro.isNotEmpty()) {
                        item { Text("Como membro", style = TituloCard) }
                        items(comoMembro, key = { "membro-${it.id}" }) { caixinha ->
                            CardCaixinhaResumo(caixinha, aoAbrirDetalhe)
                        }
                    }
                }
                // `minhas == null` sem erro e sem carregando: é o primeiro frame,
                // antes de o LaunchedEffect disparar — o indicador acima resolve.
            }
        }
    }
}

/** Card da lista: nome, arrecadado de pedido, progresso, taxa e média — tudo do servidor. */
@Composable
private fun CardCaixinhaResumo(caixinha: CaixinhaResumo, aoAbrir: (String) -> Unit) {
    KpCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { aoAbrir(caixinha.id) },
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Text(caixinha.nome, style = TituloCard)
            Spacer(Modifier.height(Spacing.xs))
            LinearProgressIndicator(
                progress = { caixinha.progresso },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "Arrecadado R$ ${brl(caixinha.arrecadado)} de R$ ${brl(caixinha.valorPedido)}" +
                    " (${(caixinha.progresso * 100).roundToInt()}%)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Rendimento de ${formatarTaxa(caixinha.taxaMensal)}% ao mês",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Spacing.xs))
            val media = caixinha.mediaAvaliacoes
            if (media != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Estrelas(
                        nota = media.replace(',', '.').toBigDecimalOrNull()
                            ?.setScale(0, RoundingMode.HALF_UP)?.toInt() ?: 0,
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        "$media · ${caixinha.totalAvaliacoes} " +
                            pluralAvaliacoes(caixinha.totalAvaliacoes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    "Sem avaliação ainda.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun pluralAvaliacoes(total: Int): String =
    if (total == 1) "avaliação" else "avaliações"

/**
 * Dialog "Entrar com código/chave": manda a chave como o usuário digitou (o
 * servidor normaliza maiúscula/trim) e mostra o erro do backend como veio —
 * as strings do contrato já são pt-BR ("Chave inválida.", "Essa chave já
 * foi usada.").
 */
@Composable
private fun DialogoEntrarComChave(
    vm: AppViewModel,
    titulo: String,
    aoFechar: () -> Unit,
    aoEntrar: (String) -> Unit,
) {
    val estado by vm.estado.collectAsState()
    var chave by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = {
            vm.limparErroAcaoCaixinha()
            aoFechar()
        },
        title = { Text(titulo) },
        text = {
            Column {
                Text(
                    "Digite o código que o responsável passou para você.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(Spacing.sm))
                KpCampo(
                    valor = chave,
                    aoMudar = {
                        chave = it
                        vm.limparErroAcaoCaixinha()
                    },
                    rotulo = "Código (ex.: KAV-8F3A2C)",
                    umaLinha = true,
                    erro = estado.erroAcaoCaixinha,
                )
            }
        },
        confirmButton = {
            KpBotaoPrimario(
                texto = "Entrar",
                onClick = { vm.entrarComChaveCaixinha(chave, aoEntrar) },
                habilitado = chave.isNotBlank(),
                carregando = estado.enviandoCaixinha,
            )
        },
        dismissButton = {
            KpBotaoSecundario(
                texto = "Cancelar",
                onClick = {
                    vm.limparErroAcaoCaixinha()
                    aoFechar()
                },
            )
        },
    )
}
