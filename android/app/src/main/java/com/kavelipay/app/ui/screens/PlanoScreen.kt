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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpEtiqueta
import com.kavelipay.app.ui.components.KpLinhaResumo
import com.kavelipay.app.ui.components.Money.brl
import com.kavelipay.app.ui.components.formatarDataCompleta
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.RotuloCaixaAlta
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloSecao

/**
 * Plano do KaveliPay — tela própria.
 *
 * O app **não processa pagamento**: não existe botão "comprar" aqui. A compra
 * acontece fora e chega um código de ativação; a tela só troca esse código
 * pelo período de acesso. Prometer um checkout que o app não tem seria
 * inventar uma função — e o KaveliPay não é banco nem provedor de pagamento.
 */
@Composable
fun KpPlanoScreen(vm: AppViewModel, aoVoltar: () -> Unit) {
    val estado by vm.estado.collectAsState()
    val sem = KpTheme.semanticas
    var codigo by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.carregarPlano() }

    val plano = estado.plano
    val premium = plano?.premium == true
    val validade = plano?.periodoFim
        ?.takeIf { it.isNotBlank() }
        ?.let { formatarDataCompleta(it) }

    Scaffold(topBar = { CabecalhoComVoltar("Plano", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (plano == null) {
                // Sem plano lido: ou está buscando, ou falhou. A diferença
                // importa — indicador que nunca acaba parece travado.
                item {
                    Column(Modifier.fillMaxWidth()) {
                        if (estado.carregandoPlano) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            Text(
                                "Não deu para carregar seu plano agora.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            KpBotaoSecundario(
                                "Tentar de novo",
                                onClick = { vm.carregarPlano() },
                            )
                        }
                    }
                }
            } else {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            KpEtiqueta(
                                texto = if (premium) "PREMIUM ATIVO" else "PLANO GRÁTIS",
                                cor = if (premium) sem.demo else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            Text(
                                text = when {
                                    premium && validade != null -> "Você tem acesso até $validade"
                                    premium -> "Você tem acesso Premium"
                                    else -> "Você está no plano grátis"
                                },
                                style = TituloSecao,
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                text = if (premium) {
                                    "Relatório do mês, análise por IA e cotação de " +
                                        "mercado estão liberados."
                                } else {
                                    "Saldo, extrato, contas, cobranças e os seus dados " +
                                        "continuam grátis."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                item {
                    Text("O que o Premium libera", style = TituloSecao)
                }

                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            plano.recursosPremium.forEach { recurso ->
                                Row(
                                    modifier = Modifier.padding(vertical = Spacing.xs),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.WorkspacePremium,
                                        contentDescription = null,
                                        tint = sem.demo,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(Spacing.sm))
                                    Text(
                                        recurso,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            Spacer(Modifier.height(Spacing.sm))
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            KpLinhaResumo("Mensal", "R$ ${brl(plano.precoMensal)}")
                            KpLinhaResumo("Anual", "R$ ${brl(plano.precoAnual)}")
                        }
                    }
                }

                item {
                    Text(
                        text = if (premium) "Renovar acesso" else "Ativar acesso",
                        style = TituloSecao,
                    )
                }

                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            OutlinedTextField(
                                value = codigo,
                                onValueChange = { codigo = it.uppercase() },
                                label = { Text("Código de ativação") },
                                // O formato na tela: o código é lido do e-mail
                                // e copiado; sem o exemplo, o padrão de quatro
                                // blocos parece erro de digitação.
                                supportingText = { Text("Ex.: KPL-ABCD-EFGH-JKLM-NPQR") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Characters,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(Spacing.md))
                            KpBotaoPrimario(
                                texto = "Ativar",
                                onClick = { vm.ativarPlano(codigo) },
                                modifier = Modifier.fillMaxWidth(),
                                habilitado = codigo.isNotBlank(),
                                carregando = estado.enviando,
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            Text(
                                text = "O código chega depois da compra. O KaveliPay " +
                                    "não processa pagamento: o app só troca o código " +
                                    "pelo período de acesso.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            estado.avisoPlano?.let { aviso ->
                item {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            aviso,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (estado.avisoPlanoFalhou) sem.vencido else sem.receita,
                        )
                    }
                }
            }
        }
    }
}
