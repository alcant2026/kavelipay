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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kavelipay.app.data.Notificacao
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.theme.LimeMarca
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloCard

/**
 * Lista de notificações — o sino da Home finalmente tem para onde ir.
 *
 * Tudo aqui é em cima de API que JÁ EXISTIA dos dois lados (backend
 * `/notifications`, `/{id}/read`, `/read-all` e o `KaveliPayApi` no app); o
 * que faltava era esta tela e a porta de entrada.
 */
@Composable
fun KpNotificacoesScreen(
    vm: AppViewModel,
    aoVoltar: () -> Unit,
) {
    val estado by vm.estado.collectAsState()

    // Recarrega a cada abertura: notificações podem ter chegado em outro
    // momento (geradas no carregamento da Home ou por outra sessão).
    LaunchedEffect(Unit) { vm.carregarNotificacoes() }

    Scaffold(topBar = { CabecalhoComVoltar("Notificações", aoVoltar) }) { padding ->
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
            if (estado.erro != null) {
                item { KpMensagemErro(estado.erro.orEmpty()) }
            }

            val lista = estado.notificacoes
            when {
                // `null` = ainda não carregou (o estado começa assim de propósito)
                lista == null -> item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.xl),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                lista.isEmpty() -> item {
                    KpCard(Modifier.fillMaxWidth()) {
                        KpVazio(
                            icone = Icons.Default.NotificationsOff,
                            titulo = "Tudo em dia",
                            descricao = "Nenhuma notificação. Avisos de vencimento, cobranças e divisões aparecem aqui.",
                        )
                    }
                }

                else -> {
                    if (estado.naoLidas > 0) {
                        item {
                            KpBotaoSecundario(
                                texto = "Marcar todas como lidas (${estado.naoLidas})",
                                onClick = { vm.marcarTodasLidas() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    items(lista, key = { it.id }) { notificacao ->
                        ItemNotificacao(
                            notificacao = notificacao,
                            aoMarcarLida = { vm.marcarNotificacaoLida(notificacao.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Cartão de uma notificação. Não lida = título em negrito + ponto de destaque
 * e o card inteiro é a ação "marcar como lida" (com `onClickLabel` para o
 * leitor de tela anunciar o que o toque faz).
 */
@Composable
private fun ItemNotificacao(
    notificacao: Notificacao,
    aoMarcarLida: () -> Unit,
) {
    val naoLida = notificacao.readAt == null
    val corpo = notificacao.body

    KpCard(
        Modifier
            .fillMaxWidth()
            .clickable(
                enabled = naoLida,
                onClickLabel = "Marcar como lida",
                onClick = aoMarcarLida,
            ),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    notificacao.title,
                    style = TituloCard.copy(
                        fontWeight = if (naoLida) FontWeight.Bold else FontWeight.Normal,
                    ),
                )
                if (!corpo.isNullOrBlank()) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        corpo,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (naoLida) {
                Spacer(Modifier.width(Spacing.sm))
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(LimeMarca, CircleShape),
                )
            }
        }
    }
}
