package com.kavelipay.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.components.Money.LinhaLancamento
import com.kavelipay.app.ui.components.Money.brl
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.RaioCard
import com.kavelipay.app.ui.theme.RotuloCaixaAlta
import com.kavelipay.app.ui.theme.SaldoDisplay
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloSecao

/**
 * "De onde vem este saldo?"
 *
 * A tela que substitui a confiança de um banco: cada linha é um lançamento que
 * o backend usou para chegar no número, com o acumulado. O usuário refaz a
 * conta à mão e bate — se não bater, o app está errado.
 */
@Composable
fun KpDerivacaoScreen(vm: AppViewModel, aoVoltar: () -> Unit) {
    val estado by vm.estado.collectAsState()
    val derivacao = estado.derivacao

    Scaffold(
        topBar = {
            CabecalhoComVoltar("De onde vem o saldo", aoVoltar)
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text(
                            "SALDO ATUAL",
                            style = com.kavelipay.app.ui.theme.RotuloCaixaAlta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "R$ ${brl(derivacao?.balance ?: "0.00")}",
                            style = com.kavelipay.app.ui.theme.SaldoDisplay,
                        )
                        Spacer(Modifier.height(Spacing.md))
                        derivacao?.formula?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (derivacao?.demonstrativa != false) {
                item { KpAvisoDemonstrativoCurto() }
            }

            item {
                Text(
                    "Lançamentos, do mais antigo ao mais recente",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val itens = derivacao?.itens.orEmpty()
            if (itens.isEmpty()) {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        KpVazio(
                            icone = Icons.Default.AccountBalanceWallet,
                            titulo = "Nenhum lançamento",
                            descricao = "O saldo é zero porque não há nada registrado.",
                        )
                    }
                }
            } else {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = Spacing.md)) {
                            itens.forEachIndexed { i, item ->
                                LinhaDerivacao(
                                    titulo = item.descricao ?: rotuloTipo(item.tipo),
                                    delta = item.delta,
                                    acumulado = item.saldoAcumulado,
                                    entrada = !item.delta.startsWith("-"),
                                )
                                if (i < itens.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinhaDerivacao(
    titulo: String,
    delta: String,
    acumulado: String,
    entrada: Boolean,
) {
    val sem = KpTheme.semanticas
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(titulo, style = MaterialTheme.typography.bodyLarge)
            Text(
                "acumulado: R$ ${brl(acumulado)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            (if (entrada) "+" else "−") + " R$ ${brl(delta.removePrefix("-"))}",
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = if (entrada) sem.receita else sem.despesa,
        )
    }
}

@Composable
private fun KpAvisoDemonstrativoCurto() {
    val sem = KpTheme.semanticas
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RaioCard))
            .background(sem.demoFundo)
            .padding(Spacing.md),
    ) {
        Text(
            "Estes registros são demonstrativos. O KaveliPay não custodia dinheiro real.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Rótulo legível quando a transação não tem descrição. */
private fun rotuloTipo(tipo: String): String = when (tipo) {
    "ENTRY" -> "Entrada"
    "EXPENSE" -> "Despesa"
    "BILL_PAID" -> "Conta paga"
    "SETTLEMENT" -> "Quitação"
    "CHARGE_RECEIVED" -> "Cobrança recebida"
    else -> tipo.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
fun CabecalhoComVoltar(titulo: String, aoVoltar: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Status bar + piso de 40dp: sem isso o ← fica atrás da barra
            // do sistema (e da tira de controles do Waydroid) e o clique se
            // perde — mesmo motivo do KpTopBar.
            .windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets(top = 40.dp)),
            )
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = aoVoltar) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
        }
        Text(titulo, style = TituloSecao)
    }
}

/**
 * Cabeçalho de ABA: só o título, sem seta de voltar.
 *
 * A seta significa "saí da navegação principal" — e numa aba isso é mentira:
 * a TabBar continua ali embaixo, então não há para onde voltar. Sem a seta o
 * alinhamento do título também fica à esquerda como no resto do app (a seta
 * empurrava o texto 48dp para a direita nas abas e não nas telas de detalhe).
 */
@Composable
fun CabecalhoAba(titulo: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets(top = 40.dp)),
            )
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(titulo, style = TituloSecao)
    }
}
