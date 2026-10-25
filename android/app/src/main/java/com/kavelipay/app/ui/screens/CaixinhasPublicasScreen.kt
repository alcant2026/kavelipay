package com.kavelipay.app.ui.screens

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
import androidx.compose.ui.graphics.Color
import com.kavelipay.app.data.CaixinhaPublica
import com.kavelipay.app.data.CaixinhaRegras
import com.kavelipay.app.data.formatarTaxa
import com.kavelipay.app.data.progressoVitrine
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.Estrelas
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpEtiqueta
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.components.Money.brl
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloCard
import java.math.RoundingMode

/**
 * Vitrine de caixinhas públicas — `GET /caixinhas/publicas`.
 *
 * Só aparece aqui o que o responsável marcou como pública. Cada card mostra
 * o que a vitrine promete: quem criou, a nota, a taxa e quanto falta. Quem
 * já entrou vê o badge; quem não entrou entra com a chave que recebeu do
 * responsável — sem pedido, sem aprovação. Nada aqui persiste no aparelho ou
 * mexe no saldo.
 */
@Composable
fun KpCaixinhasPublicasScreen(
    vm: AppViewModel,
    aoVoltar: () -> Unit,
    aoEntrar: (String) -> Unit,
) {
    val estado by vm.estado.collectAsState()
    val sem = KpTheme.semanticas

    // Recarrega a cada entrada na composição: o badge "Você entrou" de uma
    // entrada feita no detalhe aparece quando a pessoa volta pra cá.
    LaunchedEffect(Unit) { vm.carregarVitrineCaixinhas() }

    var chaveAberta by rememberSaveable { mutableStateOf<String?>(null) }
    var chaveDigitada by rememberSaveable { mutableStateOf("") }

    Scaffold(topBar = { CabecalhoComVoltar("Caixinhas públicas", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            val vitrine = estado.caixinhasPublicas
            if (estado.carregandoVitrine && vitrine == null) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            when {
                // Erro de leitura sem dado nenhum: mensagem + tentar de novo.
                vitrine == null && estado.erroVitrine != null ->
                    item {
                        KpCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(Spacing.md)) {
                                KpMensagemErro(estado.erroVitrine.orEmpty())
                                Spacer(Modifier.height(Spacing.sm))
                                KpBotaoSecundario(
                                    texto = "Tentar de novo",
                                    onClick = { vm.carregarVitrineCaixinhas() },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                vitrine != null && vitrine.isEmpty() ->
                    item {
                        KpCard(Modifier.fillMaxWidth()) {
                            KpVazio(
                                icone = Icons.Default.Public,
                                titulo = "Nenhuma caixinha pública",
                                descricao = "Caixinhas marcadas pelo responsável aparecem aqui.",
                            )
                        }
                    }

                vitrine != null ->
                    items(vitrine, key = { it.id }) { caixinha ->
                        CardCaixinhaPublica(
                            caixinha = caixinha,
                            corEntrou = sem.receita,
                            fundoEntrou = sem.receitaFundo,
                            aoEntrarComChave = {
                                vm.limparErroAcaoCaixinha()
                                chaveAberta = caixinha.id
                                chaveDigitada = ""
                            },
                        )
                    }
                // `vitrine == null` sem erro: primeiro frame, antes de o
                // LaunchedEffect disparar — o indicador acima cobre.
            }

            item {
                Text(
                    "Estas caixinhas foram marcadas como públicas por quem as criou. " +
                        "O app não cobra, não move dinheiro e não é fiador. Nada aqui mexe no seu saldo.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // ── Dialog: entrar com a chave do responsável ──────────────────────────
    chaveAberta?.let { id ->
        val caixinha = estado.caixinhasPublicas?.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = {
                vm.limparErroAcaoCaixinha()
                chaveAberta = null
            },
            title = { Text("Entrar na caixinha") },
            text = {
                Column {
                    Text(
                        "Digite a chave que ${caixinha?.donoNome ?: "o responsável"} passou para você.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    KpCampo(
                        valor = chaveDigitada,
                        aoMudar = {
                            chaveDigitada = it
                            vm.limparErroAcaoCaixinha()
                        },
                        rotulo = "Chave do responsável",
                        umaLinha = true,
                        erro = estado.erroAcaoCaixinha,
                    )
                }
            },
            confirmButton = {
                KpBotaoPrimario(
                    texto = "Entrar",
                    onClick = { vm.entrarComChaveCaixinha(chaveDigitada, aoEntrar) },
                    habilitado = chaveDigitada.isNotBlank(),
                    carregando = estado.enviandoCaixinha,
                )
            },
            dismissButton = {
                KpBotaoSecundario(
                    texto = "Cancelar",
                    onClick = {
                        vm.limparErroAcaoCaixinha()
                        chaveAberta = null
                    },
                )
            },
        )
    }
}

/** Card da vitrine: dono, média, taxa, contato, progresso, quanto falta. */
@Composable
private fun CardCaixinhaPublica(
    caixinha: CaixinhaPublica,
    corEntrou: Color,
    fundoEntrou: Color,
    aoEntrarComChave: () -> Unit,
) {
    KpCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md)) {
            Text(caixinha.nome, style = TituloCard)
            Text(
                "por ${caixinha.donoNome}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            caixinha.telefoneDono?.let { telefone ->
                Text(
                    "Contato: ${CaixinhaRegras.formatarTelefone(telefone) ?: telefone}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                            if (caixinha.totalAvaliacoes == 1) "avaliação" else "avaliações",
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

            Spacer(Modifier.height(Spacing.xs))
            Text(
                "${formatarTaxa(caixinha.taxaMensal)}% ao mês sobre o valor aportado.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.sm))
            LinearProgressIndicator(
                progress = { progressoVitrine(caixinha.arrecadado, caixinha.valorPedido) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "R$ ${brl(caixinha.arrecadado)} de R$ ${brl(caixinha.valorPedido)}" +
                    " · faltam R$ ${brl(caixinha.falta)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.md))

            if (caixinha.entrou) {
                KpEtiqueta(texto = "Você entrou", cor = corEntrou, fundo = fundoEntrou)
            } else {
                KpBotaoPrimario(
                    texto = "Entrar com a chave",
                    onClick = aoEntrarComChave,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
