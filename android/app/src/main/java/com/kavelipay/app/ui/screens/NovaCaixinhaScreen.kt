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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.kavelipay.app.data.taxaParaApi
import com.kavelipay.app.data.telefoneDigitos
import com.kavelipay.app.data.telefoneValido
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.theme.Spacing

/**
 * Criar caixinha (registro de empréstimo entre conhecidos).
 *
 * Conversões de entrada ficam na borda, como em Nova conta: dinheiro com
 * vírgula (`filtrarValor`), taxa com vírgula convertida para ponto e telefone
 * só com dígitos. O servidor valida de novo — aqui só evita 422 previsível.
 */
@Composable
fun KpNovaCaixinhaScreen(
    vm: AppViewModel,
    aoVoltar: () -> Unit,
    aoSalvar: (String) -> Unit,
) {
    val estado by vm.estado.collectAsState()

    var nome by rememberSaveable { mutableStateOf("") }
    var valor by rememberSaveable { mutableStateOf("") }
    var taxa by rememberSaveable { mutableStateOf("") }
    var previsao by rememberSaveable { mutableStateOf("") }
    var telefone by rememberSaveable { mutableStateOf("") }

    val valorNumerico = valor.toBigDecimalOrNull()
    val taxaApi = taxaParaApi(taxa)
    val digitos = telefoneDigitos(telefone)
    val telefoneErrado = digitos.isNotEmpty() && !telefoneValido(digitos)
    val podeSalvar = nome.isNotBlank() &&
        valorNumerico != null && valorNumerico > java.math.BigDecimal.ZERO &&
        taxaApi != null &&
        !telefoneErrado

    Scaffold(topBar = { CabecalhoComVoltar("Nova caixinha", aoVoltar) }) { padding ->
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
                        Text(
                            "Você é o responsável",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "A caixinha registra quanto você emprestou e quanto cada pessoa " +
                                "já pagou. O dinheiro combinado entre vocês não passa pelo app. " +
                                "Depois de criar, mande o código de convite para quem vai participar.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        KpCampo(
                            valor = nome,
                            aoMudar = { nome = it.take(80) },
                            rotulo = "Nome (ex.: Empréstimo do carro)",
                            imeAction = ImeAction.Next,
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "R\$",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            KpCampo(
                                valor = valor,
                                aoMudar = { valor = filtrarValor(it) },
                                rotulo = "Quanto foi emprestado",
                                teclado = KeyboardType.Decimal,
                                imeAction = ImeAction.Next,
                                umaLinha = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            KpCampo(
                                valor = taxa,
                                aoMudar = { entrada ->
                                    taxa = entrada.filter {
                                        it.isDigit() || it == ',' || it == '.'
                                    }.take(6)
                                },
                                rotulo = "Taxa ao mês (%)",
                                teclado = KeyboardType.Decimal,
                                imeAction = ImeAction.Next,
                                umaLinha = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                "% ao mês",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        KpCampo(
                            valor = previsao,
                            aoMudar = { previsao = it.take(60) },
                            rotulo = "Previsão de quitação (opcional)",
                            imeAction = ImeAction.Next,
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        KpCampo(
                            valor = telefone,
                            aoMudar = { telefone = telefoneDigitos(it) },
                            rotulo = "Seu telefone com DDD (opcional)",
                            teclado = KeyboardType.Phone,
                            imeAction = ImeAction.Done,
                            umaLinha = true,
                            erro = if (telefoneErrado) "Digite um telefone com DDD." else null,
                        )
                    }
                }
            }

            estado.erro?.let { mensagem ->
                item { KpMensagemErro(mensagem) }
            }

            item {
                KpBotaoPrimario(
                    texto = "Criar caixinha",
                    onClick = {
                        vm.criarCaixinha(
                            nome = nome,
                            valorPedido = valor,
                            taxaMensal = taxaApi.orEmpty(),
                            previsaoQuitacao = previsao,
                            telefone = digitos.ifBlank { null },
                            onOk = aoSalvar,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    habilitado = podeSalvar,
                    carregando = estado.enviando,
                )
            }
        }
    }
}
