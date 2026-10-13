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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.kavelipay.app.data.DividaRegras
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.Money
import com.kavelipay.app.ui.theme.RotuloCaixaAlta
import com.kavelipay.app.ui.theme.Spacing

/**
 * Criar ou editar dívida ("Eu devo").
 *
 * Uma tela só nos dois casos: `dividaId == null` cria; senão preenche com a
 * dívida de `estado.dividas` e salva via PATCH (só `PENDING` edita — depois
 * disso o backend responde 409, e a guarda de botão da lista já evita chegar
 * aqui).
 *
 * O valor digitado passa por [DividaRegras.valorNormalizado] antes de virar
 * payload: o campo aceita vírgula ("50,50") como o Brasil digita, o backend
 * espera ponto ("50.50"). Dinheiro é string/`BigDecimal` aqui — nunca
 * `Double`.
 */
@Composable
fun KpNovaDividaScreen(
    vm: AppViewModel,
    dividaId: String?,
    aoVoltar: () -> Unit,
    aoSalvar: () -> Unit,
) {
    val estado by vm.estado.collectAsState()

    var credor by rememberSaveable { mutableStateOf("") }
    var valor by rememberSaveable { mutableStateOf("") }
    var descricao by rememberSaveable { mutableStateOf("") }
    // Editar: preenche UMA vez, quando a lista chega; depois disso não toca
    // mais nos campos — senão reescreveria o que a pessoa digitou.
    var preenchido by rememberSaveable { mutableStateOf(dividaId == null) }

    LaunchedEffect(Unit) { vm.carregarDividas() }
    LaunchedEffect(dividaId, estado.dividas) {
        if (dividaId == null || preenchido) return@LaunchedEffect
        estado.dividas?.itens?.firstOrNull { it.id == dividaId }?.let { divida ->
            credor = divida.creditorName
            // "50.50" → "50,50": preenche no formato que se digita, não no do banco.
            valor = Money.brl(divida.amount)
            descricao = divida.description
            preenchido = true
        }
    }

    Scaffold(topBar = {
        CabecalhoComVoltar(if (dividaId == null) "Nova dívida" else "Editar dívida", aoVoltar)
    }) { padding ->
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
                            "A QUEM VOCÊ DEVE",
                            style = RotuloCaixaAlta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        KpCampo(
                            valor = credor,
                            aoMudar = { credor = it },
                            rotulo = "Credor",
                            imeAction = ImeAction.Next,
                        )
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text("VALOR", style = RotuloCaixaAlta, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        KpCampo(
                            valor = descricao,
                            aoMudar = { descricao = it },
                            rotulo = "Motivo (ex.: almoço de sábado)",
                            imeAction = ImeAction.Done,
                        )
                    }
                }
            }

            item {
                Text(
                    "Registrar a dívida não mexe no seu saldo. O valor só sai " +
                        "quando você marcar que pagou.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (estado.erro != null) {
                item { KpMensagemErro(estado.erro.orEmpty()) }
            }

            item {
                KpBotaoPrimario(
                    texto = if (dividaId == null) "Criar dívida" else "Salvar alterações",
                    onClick = {
                        // `valorNormalizado` é o contrato revisado: vírgula vira
                        // ponto aqui, não no servidor (422 evitado antes do POST).
                        val valorApi = DividaRegras.valorNormalizado(valor)
                        if (dividaId == null) {
                            vm.criarDivida(credor.trim(), valorApi, descricao.trim(), aoPronto = aoSalvar)
                        } else {
                            vm.editarDivida(dividaId, credor.trim(), valorApi, descricao.trim(), aoPronto = aoSalvar)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    habilitado = DividaRegras.podeSalvar(credor, valor, descricao),
                    carregando = estado.enviando,
                )
            }
        }
    }
}
