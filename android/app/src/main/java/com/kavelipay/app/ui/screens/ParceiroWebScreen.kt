package com.kavelipay.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import com.kavelipay.app.data.OfertaParceiro
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.theme.Spacing

/**
 * Página da loja parceira DENTRO do app.
 *
 * WebView nativa porque o pedido é não mandar a pessoa para o navegador: o
 * card abre a oferta aqui, com a barra de cima do KaveliPay e o voltar do
 * sistema voltando no site antes de fechar a tela.
 *
 * Superfície mínima, porque é página de terceiro rodando no nosso processo:
 *  - nenhuma `addJavascriptInterface` (ponte JS→app é execução de código
 *    remoto dentro do app);
 *  - acesso a arquivo e a content:// desligado;
 *  - mixed content nunca (sem http no meio de página https);
 *  - `shouldOverrideUrlLoading` passa por `classificarDestinoWeb`: só
 *    http/https fica aqui, esquema de outro app vai para o Android, e
 *    `javascript:`/`data:`/`file:` são bloqueados;
 *  - SSL inválido cancela — não existe handler "aceitar tudo".
 *
 * Cookies de terceiro ficam LIGADOS de propósito: a atribuição de comissão da
 * loja depende do cookie dela, e sem isso o clique do usuário não conta para
 * quem indicou. Não há cookie nosso aqui — esta WebView nunca vê o token do
 * KaveliPay, que vive só no `ApiClient`.
 */
@Composable
fun KpParceiroWebScreen(oferta: OfertaParceiro?, aoVoltar: () -> Unit) {
    if (oferta == null) {
        // Rota com id que não está na lista (vitrine vazia por falha de rede,
        // por exemplo). Tela branca aqui esconderia o motivo.
        KpVazio(
            icone = Icons.Default.Storefront,
            titulo = "Oferta indisponível",
            descricao = "Ela pode ter saído do ar. Volte e tente de novo.",
            modifier = Modifier.fillMaxSize(),
            acao = { KpBotaoPrimario("Voltar", aoVoltar) },
        )
        return
    }

    val contexto = LocalContext.current
    var carregando by remember(oferta.id) { mutableStateOf(true) }
    var erro by remember(oferta.id) { mutableStateOf<String?>(null) }

    val webView = remember(oferta.id) {
        WebView(contexto).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                // Página de loja abre pop-up de login/parcela; sem janela
                // múltipla o clique bolha para o link de cima, que é pior.
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setGeolocationEnabled(false)
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webChromeClient = WebChromeClient()

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = when (classificarDestinoWeb(request.url.toString())) {
                    DestinoWeb.DENTRO -> false
                    // Bloqueado: o `true` engole o clique e nada acontece.
                    DestinoWeb.BLOQUEADO -> true
                    DestinoWeb.FORA -> {
                        abrirForaDoApp(contexto, request.url.toString())
                        true
                    }
                }

                /**
                 * 404/410 é produto que saiu do ar: a loja respondeu "não
                 * existe", e insistir não resolve. Sem isto o usuário leria a
                 * página de erro da própria loja, em inglês, sem saber se o
                 * problema é o app.
                 *
                 * Limite honesto: loja que devolve 200 com "produto não
                 * encontrado" escrito na página (soft 404) passa batido — não
                 * há como distinguir isso de uma página normal sem conhecer o
                 * HTML de cada loja.
                 */
                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame != true) return
                    val codigo = errorResponse?.statusCode ?: return
                    if (codigo == 404 || codigo == 410) {
                        carregando = false
                        erro = "Essa indicação saiu do ar: a loja respondeu $codigo " +
                            "para este link."
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    carregando = true
                    // NÃO limpa `erro` aqui: em algumas versões do WebView a
                    // ordem é onReceivedHttpError -> onPageStarted (recarga da
                    // página de erro) -> onPageFinished, e limpar apagava a
                    // mensagem de 404 recém-recebida. Quem limpa é o botão
                    // "Tentar de novo", que é uma ação explícita do usuário.
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    carregando = false
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    // Só erro da página principal vira tela de erro: imagem ou
                    // anúncio que não carregou não pode esconder a loja.
                    if (request?.isForMainFrame == true) {
                        carregando = false
                        erro = "Não foi possível abrir a loja. Verifique a conexão."
                    }
                }
            }
        }
    }

    LaunchedEffect(oferta.id) { webView.loadUrl(oferta.destinoUrl) }

    // Voltar do sistema volta NO SITE primeiro. Sem isto, um clique errado
    // fecha a loja e a pessoa perde o que estava vendo.
    BackHandler {
        if (webView.canGoBack()) webView.goBack() else aoVoltar()
    }

    DisposableEffect(oferta.id) {
        onDispose {
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
    }

    Scaffold(
        topBar = {
            CabecalhoComVoltar(oferta.loja.ifBlank { "Loja parceira" }, aoVoltar)
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (carregando) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            Box(Modifier.weight(1f)) {
                // A WebView é nativa e é desenhada DEPOIS do conteúdo Compose:
                // um aviso sobreposto a ela ficaria escondido atrás da página.
                // Por isso o erro substitui a WebView em vez de cobri-la.
                if (erro == null) {
                    AndroidView(
                        factory = { webView },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(Spacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val mensagem = erro!!
                        Text(
                            text = mensagem,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(Spacing.md))
                        KpBotaoPrimario(
                            texto = "Tentar de novo",
                            onClick = {
                                erro = null
                                webView.loadUrl(oferta.destinoUrl)
                            },
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        KpBotaoSecundario(
                            texto = "Abrir no navegador",
                            onClick = { abrirForaDoApp(contexto, oferta.destinoUrl) },
                        )
                    }
                }
            }

            // Rodapé curto: o escape hatch fica visível mesmo com a página
            // aberta, porque loja que exige o app dela (login, pagamento) não
            // funciona em WebView e sem esta saída a pessoa trava.
            Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs)) {
                Text(
                    text = "Compra e pagamento acontecem na loja.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Entrega o link a outro app (loja nativa, telefone, e-mail).
 *
 * `try/catch` porque aparelho sem o app de destino lança
 * `ActivityNotFoundException` — e derrubar a tela por causa de um link é pior
 * do que o link simplesmente não abrir.
 */
private fun abrirForaDoApp(contexto: android.content.Context, url: String) {
    runCatching {
        contexto.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
