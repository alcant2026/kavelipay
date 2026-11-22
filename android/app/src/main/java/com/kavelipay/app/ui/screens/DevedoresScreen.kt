package com.kavelipay.app.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
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
import com.kavelipay.app.data.Devedor
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.components.Money
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloSecao

/**
 * Quem me deve: partes não quitadas de divisões, agregadas por nome.
 *
 * Derivado do servidor (`GET /splits/pendentes`) — a tela só mostra.
 */
@Composable
fun KpDevedoresScreen(
    vm: AppViewModel,
    aoVoltar: () -> Unit,
) {
    val estado by vm.estado.collectAsState()
    LaunchedEffect(Unit) { vm.carregarDevedores() }

    val devedores = estado.devedores
    val total = devedores?.fold(java.math.BigDecimal.ZERO) { acc, d ->
        acc + (d.total.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
    } ?: java.math.BigDecimal.ZERO

    Scaffold(topBar = { CabecalhoComVoltar("Quem me deve", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (devedores == null) {
                item {
                    if (estado.carregando) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator()
                        }
                    } else {
                        KpCard(Modifier.fillMaxWidth()) {
                            Text(
                                estado.erro ?: "Não foi possível carregar.",
                                modifier = Modifier.padding(Spacing.md),
                            )
                        }
                    }
                }
            } else if (devedores.isEmpty()) {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        KpVazio(
                            icone = Icons.Default.Person,
                            titulo = "Ninguém te deve",
                            descricao = "Divisões de despesa pendentes aparecem aqui, agregadas por pessoa.",
                        )
                    }
                }
            } else {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            Text(
                                "Total a receber",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                "R$ ${Money.brlTotal(total)}",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                item { Text("Por pessoa", style = TituloSecao) }
                items(devedores, key = { it.nome }) { devedor ->
                    KpDevedorRow(devedor)
                }
            }
        }
    }
}

@Composable
private fun KpDevedorRow(devedor: Devedor) {
    KpCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(devedor.nome, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${devedor.partes} ${if (devedor.partes == 1) "divisão" else "divisões"} em aberto",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "R$ ${Money.brl(devedor.total)}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}