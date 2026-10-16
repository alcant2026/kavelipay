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
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpCabecalhoComVoltar
import com.kavelipay.app.ui.components.Money
import com.kavelipay.app.ui.theme.Spacing
import java.time.LocalDate

/**
 * Criar conta a pagar (boleto, conta do mês).
 *
 * A API já tinha `novaConta` e o backend já tinha `POST /bills` — o que
 * faltava era esta tela e a porta de entrada na lista de contas: dava pra
 * PAGAR conta, mas nunca CADASTRAR.
 *
 * Conversões de formato resolvem AQUI, antes do payload:
 * - dinheiro: [filtrarValor] (vírgula → ponto), o mesmo da tela de lançamento;
 * - vencimento: o campo pede `dd/mm/aaaa` (o formato que o cliente digita,
 *   regra do gate de UI) e [dataParaIso] converte para `YYYY-MM-DD` — único
 *   formato que `POST /bills` aceita (`bills.rs` recusa qualquer outro).
 */
@Composable
fun KpNovaContaScreen(
    vm: AppViewModel,
    aoVoltar: () -> Unit,
    aoSalvar: () -> Unit,
) {
    val estado by vm.estado.collectAsState()

    var descricao by rememberSaveable { mutableStateOf("") }
    var valor by rememberSaveable { mutableStateOf("") }
    var beneficiario by rememberSaveable { mutableStateOf("") }
    // Prefill no formato que o cliente digita: o campo nunca abre em ISO.
    var vencimento by rememberSaveable {
        mutableStateOf(Money.dataAno(LocalDate.now().toString()))
    }

    val vencimentoIso = dataParaIso(vencimento)
    val valorNumerico = valor.toBigDecimalOrNull()
    val valorOk = valorNumerico != null && valorNumerico > java.math.BigDecimal.ZERO
    val vencimentoErrado = vencimento.isNotBlank() && vencimentoIso == null
    val podeSalvar = descricao.isNotBlank() && valorOk && vencimentoIso != null

    Scaffold(topBar = { KpCabecalhoComVoltar("Nova conta", aoVoltar) }) { padding ->
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
                            "Valor",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "R\$",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            KpCampo(
                                valor = valor,
                                aoMudar = { valor = filtrarValor(it) },
                                rotulo = "0,00",
                                teclado = KeyboardType.Decimal,
                                imeAction = ImeAction.Next,
                                umaLinha = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "Use vírgula para os centavos: 10,50",
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
                            valor = descricao,
                            aoMudar = { descricao = it },
                            rotulo = "Descrição (ex.: Energia elétrica)",
                            imeAction = ImeAction.Next,
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        KpCampo(
                            valor = vencimento,
                            aoMudar = { vencimento = it.take(10) },
                            rotulo = "Vencimento (dd/mm/aaaa)",
                            teclado = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                            umaLinha = true,
                            // Erro em pt-BR apontando o formato — o gate proíbe
                            // mandar jargão de banco (YYYY-MM-DD) pra tela.
                            erro = if (vencimentoErrado) {
                                "Use o formato dd/mm/aaaa."
                            } else {
                                null
                            },
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        KpCampo(
                            valor = beneficiario,
                            aoMudar = { beneficiario = it },
                            rotulo = "Beneficiário (opcional)",
                            imeAction = ImeAction.Done,
                        )
                    }
                }
            }

            if (estado.erro != null) {
                item { KpMensagemErro(estado.erro.orEmpty()) }
            }

            item {
                KpBotaoPrimario(
                    texto = "Salvar conta",
                    onClick = {
                        vm.novaConta(
                            descricao = descricao,
                            amount = valor,
                            vencimentoIso = vencimentoIso.orEmpty(),
                            beneficiario = beneficiario,
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

/**
 * `13/10/2026` → `2026-10-13`.
 *
 * `POST /bills` é estrito: só `YYYY-MM-DD` (`bills.rs` responde 422 com
 * "due_date deve ser YYYY-MM-DD" fora do formato). A tela, em compensação,
 * pede `dd/mm/aaaa` — o formato em que o cliente digita a data. Converter na
 * borda é o que deixa os dois lados no seu padrão.
 *
 * Aceita ISO colada também (mesmo contrato de "o backend aceita os dois" que
 * o resto do app já usa) e devolve `null` para data que não existe (`31/02`)
 * — `LocalDate.of` valida o calendário de verdade, sem chamar backend pra
 * descobrir que fevereiro tem 28 dias.
 */
internal fun dataParaIso(br: String): String? {
    val s = br.trim()
    val p = s.split('/')
    if (p.size == 3 && p[0].length == 2 && p[1].length == 2 && p[2].length == 4) {
        return try {
            LocalDate.of(p[2].toInt(), p[1].toInt(), p[0].toInt()).toString()
        } catch (_: Exception) {
            null
        }
    }
    return try {
        LocalDate.parse(s).toString()
    } catch (_: Exception) {
        null
    }
}
