package com.kavelipay.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.kavelipay.app.data.PesquisaAtivoRegras
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpLinhaResumo
import com.kavelipay.app.ui.components.Money
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.Spacing

/**
 * Informar ou atualizar o preço atual de um ativo.
 *
 * O app SUGERE o preço de mercado (brapi/Yahoo, chamado pelo backend), e a
 * pessoa confirma, edita ou limpa antes de salvar — o valor salvo é sempre o
 * que o usuário confirmou. A sugestão preenche só o campo vazio; se a pessoa
 * já digitou o que viu na corretora, a sugestão não mexe.
 *
 * Fronteira honesta: exibir preço de ativo com intenção de ajudar a decidir
 * comprar/vender é assessoria de investimento (CVM), e o KaveliPay não faz.
 * Por isso a sugestão é rótulo de terceiro com origem declarada ("brapi"),
 * nunca recomendação — e o texto da tela diz isso sem maquiar.
 *
 * O campo vazio limpa o preço — e a tela volta a dizer "não informado", que é
 * diferente de R$ 0,00.
 */
@Composable
fun KpPrecoAtualScreen(
    vm: AppViewModel,
    ativoId: String,
    aoVoltar: () -> Unit,
    aoSalvar: () -> Unit,
) {
    val estado by vm.estado.collectAsState()
    val sem = KpTheme.semanticas
    val ativo = estado.ativoSelecionado

    var preco by rememberSaveable {
        mutableStateOf(ativo?.precoAtual.orEmpty())
    }

    // Origem da sugestão mostrada abaixo do campo ("brapi"/"yahoo") — só
    // preenchida quando a sugestão foi aplicada a um campo vazio.
    var origemSugestao by rememberSaveable { mutableStateOf("") }

    // Busca a sugestão UMA vez, e só se o campo estiver vazio: se a pessoa já
    // digitou o preço, não gasta a chamada. `ativoId` muda entre telas, então
    // é o gatilho natural.
    LaunchedEffect(ativoId) {
        val a = ativo ?: return@LaunchedEffect
        if (preco.isNotBlank()) return@LaunchedEffect
        vm.sugerirPreco(a.symbol, a.kind) { s ->
            // `preco` é MutableState: ler no callback pega o valor ATUAL da
            // tela, então se a pessoa digitou enquanto a resposta chegava, o
            // campo já não está vazio e a sugestão não sobrescreve.
            if (preco.isBlank()) {
                PesquisaAtivoRegras.sugerirPreco("", s)?.let { aplicado ->
                    preco = aplicado
                    origemSugestao = s?.fonte.orEmpty()
                }
            }
        }
    }

    // `toBigDecimal`, nunca `toDouble`: o valor vai como string ao `Decimal` do
    // Rust, e uma vírgula errada aqui viraria número errado em silêncio.
    val precoOk = preco.toBigDecimalOrNull()?.let { it > java.math.BigDecimal.ZERO } == true

    // Prévia: o usuário confere o efeito antes de gravar.
    val qtd = ativo?.quantidade?.toBigDecimalOrNull()
    val custo = ativo?.custoTotal?.toBigDecimalOrNull()
    val p = preco.toBigDecimalOrNull()

    Scaffold(topBar = { CabecalhoComVoltar("Preço atual", aoVoltar) }) { padding ->
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
                            ativo?.symbol ?: "",
                            style = MaterialTheme.typography.titleMedium,
                            color = sem.demo,
                        )
                        Text(ativo?.nome ?: "", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        KpCampo(
                            valor = preco,
                            aoMudar = {
                                preco = filtrarValor(it)
                                // Digitou à mão = a sugestão não explica mais o
                                // número na tela.
                                origemSugestao = ""
                            },
                            rotulo = "Preço por unidade",
                            teclado = KeyboardType.Decimal,
                            umaLinha = true,
                        )
                        if (origemSugestao.isNotBlank()) {
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                "Sugestão de ${origemSugestao} (mercado). Confira e edite se " +
                                    "precisar — o valor salvo é o que você confirmar.",
                                style = MaterialTheme.typography.labelSmall,
                                color = sem.demo,
                            )
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            "O preço que você viu na corretora. Deixe vazio para voltar a " +
                                "\"não informado\".",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (p != null && qtd != null && custo != null && p > java.math.BigDecimal.ZERO) {
                item {
                    val valor = p * qtd
                    val ganho = valor - custo
                    val retorno = if (custo > java.math.BigDecimal.ZERO) {
                        ganho.multiply(java.math.BigDecimal(100)).divide(custo, 2, java.math.RoundingMode.HALF_UP)
                    } else {
                        null
                    }
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            Text("O QUE VAI APARECER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(Spacing.sm))
                            KpLinhaResumo("Valor atual", "R$ ${Money.brlTotal(valor)}", negrito = true)
                            Spacer(Modifier.height(Spacing.xs))
                            KpLinhaResumo(
                                rotulo = if (ganho < java.math.BigDecimal.ZERO) "Ganho não realizado (perda)" else "Ganho não realizado",
                                valor = "R$ ${Money.brlTotal(ganho)}",
                                corValor = if (ganho < java.math.BigDecimal.ZERO) sem.despesa else sem.receita,
                            )
                            retorno?.let {
                                Spacer(Modifier.height(Spacing.xs))
                                KpLinhaResumo(
                                    rotulo = "Retorno sobre o custo",
                                    valor = "${Money.quantidade(it.toPlainString())}%",
                                    corValor = if (ganho < java.math.BigDecimal.ZERO) sem.despesa else sem.receita,
                                )
                            }
                        }
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text(
                            "O KaveliPay sugere o preço de mercado (brapi/Yahoo) só para " +
                                "facilitar, e a sugestão não é recomendação de compra ou venda — " +
                                "exibir preço com intenção de orientar decisão é assessoria de " +
                                "investimento (CVM), e o app não faz isso. O valor que vale é o " +
                                "que você confirmou olhando a sua corretora; se preferir, digite " +
                                "à mão e apague a sugestão.",
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
                    texto = if (preco.isBlank()) "Limpar preço" else "Salvar preço",
                    onClick = {
                        // String vazia limpa o campo no backend (`COALESCE` com
                        // `Some(None)`), e a tela volta ao estado desconhecido.
                        vm.atualizarAtivo(
                            id = ativoId,
                            resumo = null,
                            proximoDividendo = null,
                            precoAtual = preco.trim(),
                        )
                        aoSalvar()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    habilitado = precoOk || preco.isBlank(),
                    carregando = estado.enviando,
                )
            }
        }
    }
}