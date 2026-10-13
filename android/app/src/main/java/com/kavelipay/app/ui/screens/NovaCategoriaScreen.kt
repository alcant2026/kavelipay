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
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpEtiqueta
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.Spacing

/**
 * Criar categoria.
 *
 * A cor é `#RRGGBB` e o backend recusa qualquer outra coisa com 422 — por isso
 * o campo mascara o que o usuário digita e mostra o hex normalizado, em vez de
 * deixar o erro chegar como 422 depois do envio.
 */
@Composable
fun KpNovaCategoriaScreen(vm: AppViewModel, aoVoltar: () -> Unit, aoSalvar: () -> Unit) {
    val estado by vm.estado.collectAsState()
    val sem = KpTheme.semanticas

    var nome by rememberSaveable { mutableStateOf("") }
    var despesa by rememberSaveable { mutableStateOf(true) }
    var cor by rememberSaveable { mutableStateOf("") }

    // Só hex, 6 dígitos. Qualquer outra coisa é rejeitada — filtrar aqui evita
    // o 422 e mostra o usuário o formato enquanto ele digita.
    fun mascaraHex(entrada: String): String =
        "#" + entrada.removePrefix("#").filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(6)

    val hexValido = cor.length == 7

    Scaffold(topBar = { CabecalhoComVoltar("Nova categoria", aoVoltar) }) { padding ->
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
                    KpBotaoPrimario(
                        texto = "Despesa",
                        onClick = { despesa = true },
                        modifier = Modifier.weight(1f),
                    )
                    KpBotaoSecundario(
                        texto = "Receita",
                        onClick = { despesa = false },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        KpCampo(
                            valor = nome,
                            aoMudar = { nome = it },
                            rotulo = "Nome (ex.: Cafeteria)",
                        )
                    }
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        KpCampo(
                            valor = cor,
                            aoMudar = { cor = mascaraHex(it) },
                            rotulo = "Cor #RRGGBB",
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            if (hexValido) {
                                "A cor será aplicada aos lançamentos desta categoria."
                            } else {
                                "Opcional. Se preencher, precisa ter 6 dígitos hexadecimais."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Text(
                    "Categoria de despesa não classifica receita — o backend recusa com 422. " +
                        "Se você registrou o gasto na categoria errada, dá para corrigir depois.",
                    style = MaterialTheme.typography.labelSmall,
                    color = sem.aviso,
                )
            }

            if (estado.erro != null) {
                item { KpMensagemErro(estado.erro.orEmpty()) }
            }

            item {
                KpBotaoPrimario(
                    texto = "Criar categoria",
                    onClick = {
                        vm.criarCategoria(
                            nome = nome.trim(),
                            kind = if (despesa) "EXPENSE" else "INCOME",
                            cor = cor.takeIf { hexValido },
                            onOk = aoSalvar,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    habilitado = nome.isNotBlank() && (!cor.isNotEmpty() || hexValido),
                    carregando = estado.enviando,
                )
            }
        }
    }
}