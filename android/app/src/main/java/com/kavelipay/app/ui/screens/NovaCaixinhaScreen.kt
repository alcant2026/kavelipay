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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import com.kavelipay.app.data.CaixinhaRegras
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.theme.RotuloCaixaAlta
import com.kavelipay.app.ui.theme.Spacing

/**
 * Criar caixinha.
 *
 * O responsável pede um valor e paga rendimento ao mês sobre o que cada
 * membro aportar. A criação é um registro — não move dinheiro, não toca o
 * ledger — e o convite (KAV-…) nasce no SERVIDOR, que devolve o detalhe já
 * com ele.
 *
 * Valores aceitam vírgula (padrão do cliente) e vão ao servidor como string
 * com ponto — nunca `Double`. Erro do backend aparece como veio.
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
    var publica by rememberSaveable { mutableStateOf(false) }
    var erroTelefone by rememberSaveable { mutableStateOf<String?>(null) }

    val valorOk = CaixinhaRegras.parseValor(valor) != null
    val taxaOk = CaixinhaRegras.parseValor(taxa) != null
    val podeCriar = nome.isNotBlank() && valorOk && taxaOk

    Scaffold(topBar = { CabecalhoComVoltar("Nova caixinha", aoVoltar) }) { padding ->
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
                        Text(
                            "A SUA CAIXINHA",
                            style = RotuloCaixaAlta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        KpCampo(
                            valor = nome,
                            aoMudar = { nome = it },
                            rotulo = "Nome (ex.: Viagem da família)",
                        )
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text("O PEDIDO", style = RotuloCaixaAlta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(Spacing.xs))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("R\$", style = MaterialTheme.typography.headlineSmall)
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
                        Spacer(Modifier.height(Spacing.sm))
                        KpCampo(
                            valor = taxa,
                            aoMudar = { taxa = filtrarValor(it) },
                            rotulo = "Taxa ao mês (%)",
                            teclado = KeyboardType.Decimal,
                            umaLinha = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Rendimento que você paga por mês sobre o valor aportado.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        KpCampo(
                            valor = previsao,
                            aoMudar = { previsao = it },
                            rotulo = "Previsão de quitação (opcional, ex.: dezembro)",
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        OutlinedTextField(
                            value = telefone,
                            onValueChange = { telefone = it; erroTelefone = null },
                            label = { Text("Seu telefone (DDD + número, opcional)") },
                            placeholder = { Text("(11) 98888-7777") },
                            singleLine = true,
                            isError = erroTelefone != null,
                            supportingText = erroTelefone?.let { { Text(it) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Aparecer na vitrine pública", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Outras pessoas encontram sua caixinha e entram com a chave.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = publica, onCheckedChange = { publica = it })
                        }
                        if (publica) {
                            Spacer(Modifier.height(Spacing.sm))
                            Text(
                                "Ao publicar, ficam visíveis para qualquer pessoa: seu nome, " +
                                    "seu telefone (se preenchido), o nome da caixinha, o valor " +
                                    "pedido, a taxa, quanto já foi arrecadado e as avaliações. " +
                                    "Você pode desligar isso depois, na tela da caixinha.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "A caixinha é um registro entre conhecidos: cada movimento só entra na conta " +
                        "depois que as duas partes confirmam. O app não cobra, não move dinheiro " +
                        "e não é fiador. Nada aqui mexe no seu saldo.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (estado.erroAcaoCaixinha != null) {
                item { KpMensagemErro(estado.erroAcaoCaixinha.orEmpty()) }
            }

            item {
                KpBotaoPrimario(
                    texto = "Criar caixinha",
                    onClick = {
                        val valorApi = CaixinhaRegras.parseValor(valor)
                        val taxaApi = CaixinhaRegras.parseValor(taxa)
                        val digitos = CaixinhaRegras.telefoneParaApi(telefone)
                        when {
                            // Botão desabilitado cobre isto; a guarda é só defesa.
                            valorApi == null || taxaApi == null -> Unit
                            telefone.isNotBlank() && digitos == null ->
                                erroTelefone = "Digite um telefone com DDD."
                            else -> {
                                erroTelefone = null
                                vm.criarCaixinha(
                                    nome = nome.trim(),
                                    valorPedido = valorApi.toPlainString(),
                                    taxaMensal = taxaApi.toPlainString(),
                                    previsaoQuitacao = previsao.trim().takeIf { it.isNotBlank() },
                                    telefone = digitos,
                                    publica = publica,
                                    aoPronto = aoSalvar,
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    habilitado = podeCriar,
                    carregando = estado.enviandoCaixinha,
                )
            }
        }
    }
}
