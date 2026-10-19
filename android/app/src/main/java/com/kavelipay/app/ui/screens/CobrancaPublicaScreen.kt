package com.kavelipay.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.kavelipay.app.data.ApiClient
import com.kavelipay.app.data.CobrancaPublica
import com.kavelipay.app.data.buscarCobrancaPublica
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpEtiqueta
import com.kavelipay.app.ui.components.KpLinhaResumo
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.components.Money
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.RotuloCaixaAlta
import com.kavelipay.app.ui.theme.Spacing

/**
 * Cobrança aberta por link (`kavelipay://cobranca/<token>`).
 *
 * Roda SEM sessão de propósito: quem recebe o link pode não ter conta no app
 * (ou estar deslogado). Por isso esta tela não usa o `AppViewModel`: ela não
 * tem carteira, saldo nem histórico e não pode herdar o estado de um usuário.
 * O único dado é o que a rota pública devolve — e o valor vem de lá, nunca do
 * link.
 *
 * A tela também **não paga nada**: o KaveliPay não processa pagamento nem é
 * PIX. O texto de rodapé diz exatamente isso, para quem recebe não confundir
 * o aviso do app com um comprovante.
 */
@Composable
fun KpCobrancaPublicaScreen(token: String, aoVoltar: () -> Unit) {
    // Fora do NavHost (deep link com o app fechado) não existe back stack:
    // sem isto o botão voltar do sistema FECHA o app em vez de fechar a tela.
    // Dentro do NavHost o `aoVoltar` é `popBackStack()` — mesmo efeito.
    BackHandler(onBack = aoVoltar)

    var carga by remember(token) { mutableStateOf<CargaCobranca>(CargaCobranca.Carregando) }

    LaunchedEffect(token) {
        carga = try {
            CargaCobranca.Ok(buscarCobrancaPublica(token))
        } catch (e: ApiClient.ErroApi) {
            if (e.status == 404) {
                CargaCobranca.Erro(
                    titulo = "Cobrança não encontrada",
                    descricao = "O link pode estar incompleto ou a cobrança foi cancelada.",
                )
            } else {
                CargaCobranca.Erro(
                    titulo = "Não deu para abrir a cobrança",
                    descricao = "Tente de novo em instantes.",
                )
            }
        } catch (_: Exception) {
            CargaCobranca.Erro(
                titulo = "Sem conexão com o servidor",
                descricao = "Confira a internet e tente de novo.",
            )
        }
    }

    Scaffold(topBar = { CabecalhoComVoltar("Cobrança", aoVoltar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.md)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val estado = carga) {
                CargaCobranca.Carregando -> Box(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is CargaCobranca.Erro -> KpCard(Modifier.fillMaxWidth()) {
                    KpVazio(
                        icone = Icons.Default.ReceiptLong,
                        titulo = estado.titulo,
                        descricao = estado.descricao,
                    )
                }

                is CargaCobranca.Ok -> Conteudo(estado.cobranca)
            }
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

private sealed class CargaCobranca {
    data object Carregando : CargaCobranca()
    data class Ok(val cobranca: CobrancaPublica) : CargaCobranca()
    data class Erro(val titulo: String, val descricao: String) : CargaCobranca()
}

@Composable
private fun Conteudo(cobranca: CobrancaPublica) {
    val sem = KpTheme.semanticas
    val corStatus = when (cobranca.status) {
        "RECEIVED" -> sem.receita
        "CANCELED" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> sem.aviso
    }
    val fundoStatus = when (cobranca.status) {
        "RECEIVED" -> sem.receitaFundo
        "CANCELED" -> MaterialTheme.colorScheme.surfaceVariant
        else -> sem.avisoFundo
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Cobrança",
            style = RotuloCaixaAlta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            cobranca.descricao,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(Spacing.md))
        Text(
            "R$ ${Money.brl(cobranca.valor)}",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(Spacing.sm))
        KpEtiqueta(
            texto = rotuloStatusCobranca(cobranca.status),
            cor = corStatus,
            fundo = fundoStatus,
        )

        val avisoStatus = when (cobranca.status) {
            "RECEIVED" -> "Esta cobrança já foi marcada como recebida por quem cobrou."
            "CANCELED" -> "Esta cobrança foi cancelada por quem cobrou."
            else -> null
        }
        if (avisoStatus != null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                avisoStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        if (cobranca.cobrador != null || cobranca.vencimento != null) {
            Spacer(Modifier.height(Spacing.lg))
            KpCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Spacing.md)) {
                    cobranca.cobrador?.let {
                        KpLinhaResumo("Cobrado por", it)
                        Spacer(Modifier.height(Spacing.sm))
                    }
                    cobranca.vencimento?.let {
                        KpLinhaResumo("Vencimento", Money.dataAno(it))
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.lg))
        // Mesma fronteira da spec, dita para quem recebe: isto é um registro
        // demonstrativo, não um meio de pagamento.
        Text(
            "Demonstrativo. O KaveliPay não processa o pagamento nem é PIX. " +
                "Combine o pagamento com quem cobrou.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Status em pt-BR, sem jargão do banco.
 *
 * `else` vira travessão em vez do código cru: mostrar "OVERDUE" numa tela
 * pública seria vazamento de vocabulário interno — e status desconhecido não
 * é informação útil para quem recebeu o link.
 */
internal fun rotuloStatusCobranca(status: String): String = when (status) {
    "PENDING" -> "Pendente"
    "RECEIVED" -> "Recebida"
    "CANCELED" -> "Cancelada"
    else -> "—"
}
