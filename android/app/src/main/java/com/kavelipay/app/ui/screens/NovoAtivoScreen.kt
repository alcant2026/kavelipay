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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import com.kavelipay.app.data.PesquisaAtivoRegras
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.Money
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.Spacing

/**
 * Adicionar um ativo.
 *
 * O ticker é normalizado pelo servidor para caixa alta (PETR4, petr4 e " PETR4 "
 * são o mesmo ativo) — sem isso o usuário criaria três posições do mesmo papel
 * e o consolidado ficaria errado sem nenhum erro visível.
 */
@Composable
fun KpNovoAtivoScreen(
    vm: AppViewModel,
    aoVoltar: () -> Unit,
    aoSalvar: () -> Unit,
) {
    val estado by vm.estado.collectAsState()
    val sem = KpTheme.semanticas

    var symbol by rememberSaveable { mutableStateOf("") }
    var nome by rememberSaveable { mutableStateOf("") }
    var tipo by rememberSaveable { mutableStateOf("STOCK") }
    var resumo by rememberSaveable { mutableStateOf("") }
    var proximoDividendo by rememberSaveable { mutableStateOf("") }

    // Último código que já foi à IA. Guard de economia: cada consulta custa
    // token — tabular pro campo Nome e voltar sem mudar nada não pode virar
    // chamada nova.
    var symbolPesquisado by rememberSaveable { mutableStateOf("") }

    val podeSalvar = symbol.isNotBlank() && nome.isNotBlank()

    Scaffold(topBar = { CabecalhoComVoltar("Adicionar ativo", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    // Seletor de tipo: o escolhido fica verde (primário), os
                    // outros secundários. Antes o verde era fixo no "Ação" e
                    // os outros botões somem da linha quando ficavam
                    // selecionados — dava a sensação de travar.
                    listOf(
                        "STOCK" to "Ação",
                        "FII" to "Fundo",
                        "BOND" to "Renda fixa",
                    ).forEach { (valor, rotulo) ->
                        if (tipo == valor) {
                            KpBotaoPrimario(rotulo, { tipo = valor }, Modifier.weight(1f))
                        } else {
                            KpBotaoSecundario(rotulo, { tipo = valor }, Modifier.weight(1f))
                        }
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        // Renda fixa não tem ticker de bolsa: o backend aceita
                        // texto livre de até 20 (normalizar_simbolo) e a IA
                        // busca pelo NOME — o rótulo muda por tipo pra não
                        // mandar a pessoa procurar código PETR4 em Tesouro.
                        KpCampo(
                            valor = symbol,
                            aoMudar = { symbol = it.uppercase().take(20) },
                            rotulo = when (tipo) {
                                "FII" -> "Código (ex.: MXRF11)"
                                "BOND" -> "Nome do papel (ex.: Tesouro Selic 2029)"
                                else -> "Código (ex.: PETR4)"
                            },
                            umaLinha = true,
                            // Saiu do campo = momento de perguntar à IA (a
                            // resposta preenche só o que estiver vazio). As
                            // proteções — formato válido, símbolo novo —
                            // moram em PesquisaAtivoRegras: a consulta gasta
                            // token de verdade.
                            modifier = Modifier.onFocusChanged { foco ->
                                if (foco.isFocused) return@onFocusChanged
                                val limpo = symbol.trim()
                                if (!PesquisaAtivoRegras.deveConsultar(limpo, tipo, symbolPesquisado)) {
                                    return@onFocusChanged
                                }
                                // Marcado ANTES da chamada: se falhar (rede,
                                // feature desligada), a próxima saída do campo
                                // não repete — tentativa é paga.
                                symbolPesquisado = limpo
                                vm.pesquisarAtivo(limpo, tipo) { r ->
                                    // Silencioso: falha não preenche nada.
                                    if (r == null) return@pesquisarAtivo
                                    // O campo pode ter mudado enquanto a IA
                                    // respondia: resposta velha não preenche
                                    // formulário novo (PETR4 não escreve
                                    // "Petrobras" em VALE3).
                                    if (symbol.trim() != limpo) return@pesquisarAtivo
                                    PesquisaAtivoRegras.aplicar(nome, resumo, proximoDividendo, r)?.let { s ->
                                        s.nome?.let { nome = it }
                                        s.resumo?.let { resumo = it }
                                        // Data do mercado (brapi/statusinvest),
                                        // só se o campo estiver vazio — a
                                        // pessoa pode já ter digitado a que
                                        // viu. A API devolve ISO e o rótulo do
                                        // campo diz dd/mm/aaaa: converter na
                                        // exibição; o backend aceita os dois
                                        // formatos na gravação (`parse_data`).
                                        s.proximoDividendo?.let {
                                            proximoDividendo = Money.dataAno(it)
                                        }
                                    }
                                }
                            },
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        KpCampo(
                            valor = nome,
                            aoMudar = { nome = it },
                            rotulo = "Nome do ativo",
                            umaLinha = true,
                        )
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        KpCampo(
                            valor = resumo,
                            aoMudar = { resumo = it },
                            rotulo = "Resumo do ativo (opcional)",
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "Descrição, não valores. O app rejeita resumo com número para que " +
                                "preço e quantidade tenham uma fonte só.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Renda fixa não paga dividendo com data de mercado (o backend
            // sugere pra ação e FII — brapi/statusinvest —, não pra BOND) —
            // campo escondido em BOND.
            if (tipo != "BOND") {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            KpCampo(
                                valor = proximoDividendo,
                                aoMudar = { proximoDividendo = it.take(10) },
                                rotulo = "Próximo dividendo (dd/mm/aaaa)",
                                umaLinha = true,
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                "Opcional. Preenchido automaticamente quando o ativo paga " +
                                    "dividendos e você informa o código — é uma sugestão: confira " +
                                    "na corretora e edite se estiver diferente.",
                                style = MaterialTheme.typography.labelSmall,
                                color = sem.demo,
                            )
                        }
                    }
                }
            }

            if (estado.erro != null) {
                item { KpMensagemErro(estado.erro.orEmpty()) }
            }

            item {
                KpBotaoPrimario(
                    texto = "Adicionar",
                    onClick = {
                        vm.criarInvestimento(
                            symbol = symbol,
                            nome = nome,
                            kind = tipo,
                            resumo = resumo.ifBlank { null },
                            proximoDividendo = proximoDividendo.ifBlank { null },
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