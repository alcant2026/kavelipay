package com.kavelipay.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.RotuloCaixaAlta
import com.kavelipay.app.ui.theme.Spacing

/**
 * Criar cobrança.
 *
 * Criar cobrança **não move dinheiro** — registra um direito a receber. O saldo
 * só entra quando o usuário marca como recebida (`POST /charges/{id}/receive`),
 * que é declaração, não confirmação de pagamento. A tela diz isso na cara.
 */
@Composable
fun KpNovaChargeScreen(vm: AppViewModel, aoVoltar: () -> Unit, aoSalvar: (String) -> Unit) {
    val estado by vm.estado.collectAsState()
    val sem = KpTheme.semanticas

    var descricao by rememberSaveable { mutableStateOf("") }
    var valor by rememberSaveable { mutableStateOf("") }
    var devedor by rememberSaveable { mutableStateOf("") }

    val valorOk = valor.toBigDecimalOrNull()?.let { it > java.math.BigDecimal.ZERO } == true
    val podeCriar = valorOk && devedor.isNotBlank()

    Scaffold(topBar = { CabecalhoComVoltar("Nova cobrança", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text("VALOR A COBRAR", style = RotuloCaixaAlta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(Spacing.xs))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("R\$", style = MaterialTheme.typography.headlineSmall, color = sem.receita)
                            Spacer(Modifier.width(Spacing.sm))
                            KpCampo(
                                valor = valor,
                                aoMudar = { valor = filtrarValor(it) },
                                rotulo = "0,00",
                                teclado = KeyboardType.Decimal,
                                umaLinha = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        KpCampo(
                            valor = devedor,
                            aoMudar = { devedor = it },
                            rotulo = "Quem deve (ex.: Bruno)",
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        KpCampo(
                            valor = descricao,
                            aoMudar = { descricao = it },
                            rotulo = "Ex.: Passeio no parque",
                        )
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text(
                            "Cobrar não move dinheiro. A cobrança é um registro do que você tem a receber; " +
                                "o valor só entra na carteira quando você marcar como recebida.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            "O QR gerado é um link do próprio app (kavelipay://), não um PIX do Banco Central. " +
                                "O KaveliPay não é provedor de PIX.",
                            style = MaterialTheme.typography.labelSmall,
                            color = sem.demo,
                        )
                    }
                }
            }

            if (estado.erro != null) {
                item { KpMensagemErro(estado.erro.orEmpty()) }
            }

            item {
                KpBotaoPrimario(
                    texto = "Criar cobrança",
                    onClick = {
                        vm.criarCharge(
                            descricao = descricao.ifBlank { "Cobrança" },
                            valor = valor,
                            devedor = devedor.trim(),
                            onOk = aoSalvar,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    habilitado = podeCriar,
                    carregando = estado.enviando,
                )
            }
        }
    }
}