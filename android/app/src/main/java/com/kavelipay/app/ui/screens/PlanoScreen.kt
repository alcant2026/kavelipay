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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.Fornecedor
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpEtiqueta
import com.kavelipay.app.ui.components.KpLinhaResumo
import com.kavelipay.app.ui.components.Money.brl
import com.kavelipay.app.ui.components.formatarDataCompleta
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.RotuloCaixaAlta
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloSecao

/**
 * Plano do KaveliPay — tela própria.
 *
 * A compra acontece no Mercado Pago, dentro de um WebView: o app pede a URL
 * ao backend (`POST /billing/checkout`) e abre a página de pagamento. O app
 * nunca vê nem guarda número de cartão, e o preço vem do backend — o aparelho
 * não decide quanto custa o plano.
 *
 * O código de ativação continua disponível, escondido, para quem comprou por
 * fora.
 */
@Composable
fun KpPlanoScreen(vm: AppViewModel, aoVoltar: () -> Unit) {
    val estado by vm.estado.collectAsState()
    val sem = KpTheme.semanticas
    var codigo by remember { mutableStateOf("") }
    var comCodigo by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.carregarPlano() }

    val plano = estado.plano
    val premium = plano?.premium == true
    val validade = plano?.periodoFim
        ?.takeIf { it.isNotBlank() }
        ?.let { formatarDataCompleta(it) }

    Scaffold(topBar = { CabecalhoComVoltar("Plano", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (plano == null) {
                // Sem plano lido: ou está buscando, ou falhou. A diferença
                // importa — indicador que nunca acaba parece travado.
                item {
                    Column(Modifier.fillMaxWidth()) {
                        if (estado.carregandoPlano) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            Text(
                                "Não deu para carregar seu plano agora.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            KpBotaoSecundario(
                                "Tentar de novo",
                                onClick = { vm.carregarPlano() },
                            )
                        }
                    }
                }
            } else {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            KpEtiqueta(
                                texto = if (premium) "PREMIUM ATIVO" else "PLANO GRÁTIS",
                                cor = if (premium) sem.demo else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            Text(
                                text = when {
                                    premium && validade != null -> "Você tem acesso até $validade"
                                    premium -> "Você tem acesso Premium"
                                    else -> "Você está no plano grátis"
                                },
                                style = TituloSecao,
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                text = if (premium) {
                                    "O relatório mensal de gastos por categoria está " +
                                        "liberado."
                                } else {
                                    "Saldo, extrato, contas, cobranças, caixinhas e os " +
                                        "seus dados continuam grátis."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                item {
                    Text("O que o Premium libera", style = TituloSecao)
                }

                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            plano.recursosPremium.forEach { recurso ->
                                Row(
                                    modifier = Modifier.padding(vertical = Spacing.xs),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.WorkspacePremium,
                                        contentDescription = null,
                                        tint = sem.demo,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(Spacing.sm))
                                    Text(
                                        recurso,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            Spacer(Modifier.height(Spacing.sm))
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            KpLinhaResumo("Mensal", "R$ ${brl(plano.precoMensal)}")
                            KpLinhaResumo("Anual", "R$ ${brl(plano.precoAnual)}")
                        }
                    }
                }

                item {
                    Text(
                        text = if (premium) "Renovar acesso" else "Assinar o Premium",
                        style = TituloSecao,
                    )
                }

                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            KpBotaoPrimario(
                                texto = "Assinar mensal · R$ ${brl(plano.precoMensal)}",
                                onClick = { vm.iniciarCheckout("mensal") },
                                modifier = Modifier.fillMaxWidth(),
                                carregando = estado.enviando,
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            KpBotaoSecundario(
                                texto = "Assinar anual · R$ ${brl(plano.precoAnual)}",
                                onClick = { vm.iniciarCheckout("anual") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            Text(
                                text = "Pix ou cartão, na tela do Mercado Pago. Os dados do " +
                                    "cartão não passam pelo KaveliPay. Nada renova sozinho: " +
                                    "quando o período acabar, a conta volta ao plano grátis.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Código de ativação continua existindo, mas escondido: é o
                // caminho de quem comprou por fora, não o normal.
                item {
                    KpBotaoSecundario(
                        texto = if (comCodigo) "Ocultar código" else "Tenho um código de ativação",
                        onClick = { comCodigo = !comCodigo },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (comCodigo) {
                    item {
                        KpCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(Spacing.md)) {
                                OutlinedTextField(
                                    value = codigo,
                                    onValueChange = { codigo = it.uppercase() },
                                    label = { Text("Código de ativação") },
                                    // O formato na tela: o código é lido do e-mail
                                    // e copiado; sem o exemplo, o padrão de quatro
                                    // blocos parece erro de digitação.
                                    supportingText = { Text("Ex.: KPL-ABCD-EFGH-JKLM-NPQR") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Characters,
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(Spacing.md))
                                KpBotaoPrimario(
                                    texto = "Ativar",
                                    onClick = { vm.ativarPlano(codigo) },
                                    modifier = Modifier.fillMaxWidth(),
                                    habilitado = codigo.isNotBlank(),
                                    carregando = estado.enviando,
                                )
                            }
                        }
                    }
                }
                item { Text("Reembolso e cancelamento", style = TituloSecao) }

                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            Text(
                                "Você pode desistir da compra em até 7 dias corridos e receber " +
                                    "tudo de volta, sem precisar justificar (art. 49 do CDC). " +
                                    "Escreva para ${Fornecedor.EMAIL} com o e-mail da sua conta.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            Text(
                                "Não existe cobrança automática: nenhum cartão fica guardado e " +
                                    "o acesso não renova sozinho. Quando o período acabar, a conta " +
                                    "volta ao plano grátis e seus dados continuam lá.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            Text(
                                "${Fornecedor.NOME} · ${Fornecedor.REFERENCIA}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            estado.avisoPlano?.let { aviso ->
                item {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            aviso,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (estado.avisoPlanoFalhou) sem.vencido else sem.receita,
                        )
                    }
                }
            }
        }
    }

    // Checkout aberto: o pagamento acontece na página do Mercado Pago, mas
    // dentro do app — ninguém precisa sair para o navegador.
    estado.checkoutUrl?.let { url ->
        Dialog(
            onDismissRequest = { vm.fecharCheckout() },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            // O botão "Voltar ao app" da página de confirmação
                            // é um link `kavelipay://`; sem tratar, o WebView
                            // mostra erro de página não encontrada.
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                if (request.url.scheme == "kavelipay") {
                                    vm.fecharCheckout()
                                    return true
                                }
                                return false
                            }
                        }
                        loadUrl(url)
                    }
                },
            )
        }
    }
}
