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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpEtiqueta
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.RotuloCaixaAlta
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloSecao

/** LGPD: exportar dados, eliminar conta. */
@Composable
fun KpLgpdScreen(vm: AppViewModel, aoVoltar: () -> Unit) {
    val estado by vm.estado.collectAsState()
    val sem = KpTheme.semanticas

    var showConfirmEliminar by remember { mutableStateOf(false) }
    var senhaEliminar by remember { mutableStateOf("") }

    Scaffold(topBar = { CabecalhoComVoltar("Dados & Privacidade", aoVoltar) }) { padding ->
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
                        Text("O que o KaveliPay guarda", style = TituloSecao)
                        Spacer(Modifier.height(Spacing.md))
                        Text(
                            "• Perfil (e-mail, nome)\n" +
                            "• Carteira (saldo, moeda, status)\n" +
                            "• Transações completas (entradas/saídas, categorias, datas)\n" +
                            "• Contas a pagar (criadas, pagas, canceladas)\n" +
                            "• Cobranças (criadas, QR, status)\n" +
                            "• Divisões de despesa (partes, quitações)\n" +
                            "• Notificações e histórico\n" +
                            "• Sessões de login\n" +
                            "• Ledger completo (cada transação com seus 2 lançamentos — débito e crédito)",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            item { Text("Exportar", style = TituloSecao) }
            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        KpBotaoSecundario(
                            texto = "Exportar tudo (JSON)",
                            onClick = { vm.exportarDadosJson() },
                            icone = Icons.Default.Download,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        KpBotaoSecundario(
                            texto = "Exportar ledger (CSV, pt-BR)",
                            onClick = { vm.exportarDadosCsv() },
                            icone = Icons.Default.TableChart,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item { Text("Eliminar conta", style = TituloSecao) }
            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text(
                            "A eliminação anonimiza seu e-mail, revoga todas as sessões, " +
                            "fecha a carteira e remove dados pessoais. O ledger (transações e " +
                            "lançamentos) é preservado para manter a integridade contábil — " +
                            "saldo e equilíbrio não quebram.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.md))
                        KpBotaoPrimario(
                            texto = "Eliminar minha conta",
                            onClick = { showConfirmEliminar = true },
                            cor = sem.despesa,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text("Seus direitos (LGPD)", style = TituloSecao)
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            "• Acesso: exportar tudo acima\n" +
                            "• Retificação: edite nome no perfil\n" +
                            "• Eliminação: botão acima (anônimo, ledger preservado)\n" +
                            "• Portabilidade: JSON/CSV são formatos abertos\n" +
                            "• Oposição: não há processamento automatizado de decisões\n" +
                            "• DPO: não se aplica (não é controlador de dados sensíveis)\n\n" +
                            "O KaveliPay NÃO é banco, instituição de pagamento, custodiante, " +
                            "emissor de moeda eletrônica ou provedor de PIX. O saldo é " +
                            "sempre derivado dos SEUS lançamentos — você pode conferir.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    // Dialog confirmar eliminação
    if (showConfirmEliminar) {
        AlertDialog(
            onDismissRequest = { showConfirmEliminar = false },
            title = { Text("Eliminar conta — confirme") },
            text = {
                Column(Modifier.padding(Spacing.md)) {
                    Text(
                        "Isso é irreversível. Seu e-mail será anonimizado, sessões revogadas, " +
                        "carteira fechada. O ledger fica para auditoria contábil.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(Spacing.md))
                    androidx.compose.material3.TextField(
                        value = senhaEliminar, onValueChange = { senhaEliminar = it },
                        label = { Text("Digite sua senha para confirmar") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                KpBotaoPrimario(
                    texto = "Eliminar",
                    onClick = {
                        if (senhaEliminar.length >= 8) {
                            vm.eliminarConta(senhaEliminar)
                            showConfirmEliminar = false
                        }
                    },
                    cor = sem.despesa,
                )
            },
            dismissButton = {
                KpBotaoSecundario("Cancelar", onClick = { showConfirmEliminar = false })
            },
        )
    }
}