package com.kavelipay.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpMenuRow
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.theme.Spacing

/**
 * Sessões abertas — tela própria, fora do Perfil.
 *
 * A lista ficava DENTRO do Perfil e, com quatro ou seis sessões, empurrava
 * "Seus dados" e "Sair da conta" para fora da primeira tela: a pessoa abria o
 * Perfil e via uma parede de datas idênticas antes de chegar no que queria.
 * Aqui a lista tem espaço, e o Perfil volta a ser um menu curto.
 *
 * Por que não tem "este aparelho": o backend devolve só `created_at` e
 * `expires_at` — não há identificador de dispositivo. Marcar uma linha como
 * "esta" seria chute, e um chute errado esconde o botão de encerrar da sessão
 * certa. Por isso a ação em massa diz a verdade: encerra todas, inclusive esta.
 */
@Composable
fun KpSessoesScreen(vm: AppViewModel, aoVoltar: () -> Unit) {
    val estado by vm.estado.collectAsState()

    LaunchedEffect(Unit) { vm.carregarPerfilESessoes() }

    var confirmarTodas by remember { mutableStateOf(false) }
    val sessoes = estado.sessoes?.itens.orEmpty()

    Scaffold(topBar = { CabecalhoComVoltar("Sessões abertas", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item {
                Text(
                    text = "Cada vez que você entra, uma sessão é aberta. " +
                        "Encerre as que você não reconhece.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = Spacing.xs)) {
                        if (sessoes.isEmpty()) {
                            // Vazio pode ser "nenhuma" ou "não carregou" — o
                            // botão cobre os dois sem afirmar qual foi.
                            KpVazio(
                                icone = Icons.Default.Security,
                                titulo = "Nada para mostrar",
                                descricao = "Não foi possível listar as sessões agora.",
                                acao = {
                                    KpBotaoSecundario(
                                        "Tentar de novo",
                                        onClick = { vm.carregarPerfilESessoes() },
                                    )
                                },
                            )
                        } else {
                            sessoes.forEachIndexed { indice, sessao ->
                                if (indice > 0) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                        modifier = Modifier.padding(horizontal = Spacing.md),
                                    )
                                }
                                LinhaSessao(sessao, onRevogar = { vm.revogarSessao(sessao.id) })
                            }
                        }
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    KpMenuRow(
                        icone = Icons.Default.Logout,
                        rotulo = "Sair de todos os aparelhos",
                        subtitulo = "Inclusive este. Você vai precisar entrar de novo.",
                        onClick = { confirmarTodas = true },
                    )
                }
            }

            item {
                Text(
                    text = "O KaveliPay não guarda o modelo do aparelho — só o " +
                        "horário em que cada sessão foi aberta.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm, start = Spacing.xs, end = Spacing.xs),
                )
            }
        }
    }

    if (confirmarTodas) {
        AlertDialog(
            onDismissRequest = { confirmarTodas = false },
            title = { Text("Sair de todos os aparelhos?") },
            text = {
                Text(
                    "Todas as sessões serão encerradas, inclusive a deste " +
                        "aparelho. Para voltar, faça login de novo.",
                )
            },
            confirmButton = {
                KpBotaoPrimario(
                    texto = "Sair de todos",
                    onClick = {
                        confirmarTodas = false
                        vm.revogarTodasSessoes()
                    },
                )
            },
            dismissButton = {
                KpBotaoSecundario("Cancelar", onClick = { confirmarTodas = false })
            },
        )
    }
}
