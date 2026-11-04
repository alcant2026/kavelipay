package com.kavelipay.app.ui.screens

import android.content.ClipData
import android.widget.Toast
import android.net.Uri
import android.content.Intent
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kavelipay.app.data.ItemCharge
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpEtiqueta
import com.kavelipay.app.ui.components.KpEstruturaAba
import com.kavelipay.app.ui.components.KpLinhaResumo
import com.kavelipay.app.ui.components.KpQrCode
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.components.Money
import com.kavelipay.app.ui.theme.KpSemanticas
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.RotuloCaixaAlta
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloSecao

/** Lista de cobranças. */
@Composable
fun KpChargesScreen(
    vm: AppViewModel,
    aoMudarAba: (Int) -> Unit = {},
    aoNovaCharge: () -> Unit = {},
    aoAbrirQr: (String) -> Unit = {},
    aoEscanearCobranca: () -> Unit = {},
) {
    val estado by vm.estado.collectAsState()

    LaunchedEffect(Unit) {
        vm.carregarCharges()
    }

    KpEstruturaAba(
        abaAtual = 3,
        aoMudarAba = aoMudarAba,
        topBar = { CabecalhoAba("Cobranças") },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (estado.enviando) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    KpBotaoPrimario(
                        texto = "Nova cobrança",
                        onClick = aoNovaCharge,
                        icone = Icons.Default.Add,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    KpBotaoSecundario(
                        texto = "Ler QR de cobrança",
                        onClick = aoEscanearCobranca,
                        icone = Icons.Default.QrCodeScanner,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            val charges = estado.charges?.itens.orEmpty()
            if (charges.isEmpty()) {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        KpVazio(
                            icone = Icons.Default.ReceiptLong,
                            titulo = "Nenhuma cobrança",
                            descricao = "Crie uma cobrança para gerar QR e cobrar alguém.",
                        )
                    }
                }
            } else {
                item { Text("Cobranças", style = TituloSecao) }
                // `items(count)` e não `forEachIndexed` com `item { }` aninhado:
                // fora do escopo do LazyListScope, o `item` não resolve.
                items(charges.size) { indice ->
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = Spacing.md)) {
                            val linha = charges[indice]
                            LinhaCharge(linha, aoAbrirQr)
                            if (indice < charges.lastIndex) HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinhaCharge(charge: ItemCharge, aoAbrirQr: (String) -> Unit) {
    val sem = KpTheme.semanticas
    val statusColor = when (charge.status) {
        "PENDING" -> sem.aviso
        "RECEIVED" -> sem.receita
        "CANCELED" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusFundo = when (charge.status) {
        "PENDING" -> sem.avisoFundo
        "RECEIVED" -> sem.receitaFundo
        "CANCELED" -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    // QR só de PENDING: o backend responde 409 em RECEIVED de propósito (QR
    // vivo levaria alguém a pagar de novo). Sem esta guarda a tela abriria um
    // detalhe que só volta com erro.
    val podeVerQr = charge.status == "PENDING"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
            .clickable(enabled = podeVerQr) { aoAbrirQr(charge.id) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(charge.description, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(
                "Devedor: ${charge.debtorName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "R$ ${Money.brl(charge.amount)}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(Spacing.sm))
        KpEtiqueta(charge.status, statusColor, fundo = statusFundo)
    }
}

/** Tela do QR Code da cobrança. */
@Composable
fun KpChargeQrScreen(vm: AppViewModel, chargeId: String, aoVoltar: () -> Unit) {
    val estado by vm.estado.collectAsState()
    val charge = estado.chargeQr
    val sem = KpTheme.semanticas
    val contexto = LocalContext.current

    LaunchedEffect(chargeId) {
        vm.carregarChargeQr(chargeId)
    }

    Scaffold(topBar = { CabecalhoComVoltar("QR Code", aoVoltar) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (estado.enviando) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            val cobranca = charge
            if (cobranca == null) {
                if (!estado.enviando) {
                    KpCard(Modifier.fillMaxWidth()) {
                        KpVazio(
                            icone = Icons.Default.QrCode,
                            titulo = "Cobrança não encontrada",
                            descricao = estado.erro
                                ?: "Não consegui carregar esta cobrança.",
                        )
                    }
                }
            } else {
                val qr = cobranca.qr
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = Spacing.md),
                ) {
                    Text("Cobrança", style = RotuloCaixaAlta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Spacing.xs))
                    Text(cobranca.description, style = MaterialTheme.typography.titleMedium)

                    Spacer(Modifier.height(Spacing.md))

                    if (qr != null) {
                        // QR do deep link `kavelipay://cobranca/<token>`.
                        // **Não** é BR Code/EMV e não é pagamento: o app não
                        // processa PIX, e o código só abre outra tela do próprio
                        // app. O texto abaixo deixa isso explícito para quem
                        // digitaliza, em vez de deixar parecer cobrança real.
                        KpQrCode(payload = qr.payload, tamanho = 260.dp)

                        Spacer(Modifier.height(Spacing.md))
                        Text(
                            "QR Code do próprio app. Quem receber abre em KaveliPay, " +
                                "em Cobranças → Ler QR de cobrança. Não é PIX nem boleto.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )

                        Spacer(Modifier.height(Spacing.sm))
                        KpBotaoSecundario(
                            texto = "Copiar link",
                            onClick = {
                                copiarParaAreaTransferencia(contexto, qr.payload)
                            },
                            icone = Icons.Default.ContentCopy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(260.dp)
                                .clip(RoundedCornerShape(com.kavelipay.app.ui.theme.RaioCard))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "O backend não devolveu payload para esta cobrança.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(Spacing.lg),
                            )
                        }
                    }

                    Spacer(Modifier.height(Spacing.md))
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            KpLinhaResumo("Valor", "R$ ${Money.brl(cobranca.amount)}", negrito = true)
                            Spacer(Modifier.height(Spacing.sm))
                            KpLinhaResumo("Devedor", cobranca.debtorName)
                            Spacer(Modifier.height(Spacing.sm))
                            KpLinhaResumo("Status", cobranca.status, corValor = statusColorDe(cobranca.status, sem))
                        }
                    }

                    Spacer(Modifier.height(Spacing.md))
                    // Checkout Pro: o link nasce no backend e abre a página do
                    // Mercado Pago no navegador (o app não é provedor de
                    // pagamento). Quem confirma é o webhook, não este botão.
                    KpBotaoSecundario(
                        texto = "Pagar com Mercado Pago",
                        onClick = {
                            vm.gerarLinkPagamento(cobranca.id) { url ->
                                runCatching {
                                    contexto.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }.onFailure {
                                    // Sem navegador (ou URL recusada): o link
                                    // ainda serve para mandar para quem paga.
                                    copiarParaAreaTransferencia(contexto, url)
                                    Toast.makeText(contexto, "Link copiado.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        habilitado = cobranca.status == "PENDING" && !estado.enviando,
                        icone = Icons.Default.OpenInNew,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "Abre o pagamento no Mercado Pago. A cobrança só vira recebida " +
                            "quando o Mercado Pago confirmar.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(Spacing.md))
                    KpBotaoPrimario(
                        texto = "Marcar como recebida",
                        onClick = { vm.receberCharge(cobranca.id) },
                        // `POST /charges/{id}/receive` é 409 em não-PENDING:
                        // desabilitar aqui evita um erro previsível.
                        habilitado = cobranca.status == "PENDING" && !estado.enviando,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "Isto registra que você recebeu. Não confirma pagamento nenhum — " +
                            "o KaveliPay não processa PIX.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

@Composable
private fun statusColorDe(status: String, sem: KpSemanticas) = when (status) {
    "RECEIVED" -> sem.receita
    "CANCELED" -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> sem.aviso
}

/**
 * Copia para a área de transferência do sistema.
 *
 * `ClipboardManager` sem `ClipData` foi depreciado no Android 13 e em alguns
 * aparelhos faz a cópia falhar em silêncio — por isso o `ClipData` explícito.
 */
private fun copiarParaAreaTransferencia(contexto: Context, texto: String) {
    val clipboard = contexto.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("KaveliPay", texto))
}
