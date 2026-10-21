package com.kavelipay.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.theme.Pilula
import com.kavelipay.app.ui.theme.Spacing
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * De onde a leitura foi pedida. Não é o formato do código: é o **destino** do
 * que for lido.
 *
 * `CONTA` é o "Escanear" da Nova conta — ali o que interessa é a linha
 * digitável do boleto. `COBRANCA` é o "Ler QR de cobrança" — ali o que
 * interessa é o token de uma cobrança do app. Quem escolhe o destino é a tela
 * onde o usuário está; o formato quem descobre é o [classificarLeitura], e não
 * o usuário antes de apontar a câmera.
 */
enum class ContextoLeitura { CONTA, COBRANCA }

/** Veredito de [classificarLeitura] para o [ContextoLeitura] da tela. */
sealed interface ResultadoLeitura {
    /** Serve para esta tela. [valor] é a linha do boleto ou o token do QR. */
    data class Aceito(val valor: String) : ResultadoLeitura

    /** É boleto, mas a tela esperava cobrança. */
    data object BoletoForaDeContexto : ResultadoLeitura

    /** É QR de cobrança, mas a tela esperava boleto. */
    data object CobrancaForaDeContexto : ResultadoLeitura

    /** Não é nenhum dos dois. */
    data object NaoReconhecido : ResultadoLeitura
}

/**
 * Reconhece o texto lido: boleto (44/47 dígitos) ou QR de cobrança do app.
 *
 * Um leitor só, dois formatos — e é o app que diz de qual se trata. Sem isso,
 * o usuário teria de saber de antemão "isto é um boleto" ou "isto é um QR"
 * antes de apontar a câmera, o que é exatamente o trabalho que o app deveria
 * fazer.
 *
 * Quando o código é válido mas pertence à outra tela, o resultado não é
 * "inválido": é [ResultadoLeitura.BoletoForaDeContexto] /
 * [ResultadoLeitura.CobrancaForaDeContexto], porque a mensagem certa é "isto
 * vai em tal lugar", não "não consegui ler".
 */
internal fun classificarLeitura(bruto: String, contexto: ContextoLeitura): ResultadoLeitura {
    tokenDeCobranca(bruto)?.let { token ->
        return if (contexto == ContextoLeitura.COBRANCA) {
            ResultadoLeitura.Aceito(token)
        } else {
            ResultadoLeitura.CobrancaForaDeContexto
        }
    }
    val linha = normalizarLinhaDigitavel(bruto) ?: return ResultadoLeitura.NaoReconhecido
    return if (contexto == ContextoLeitura.CONTA) {
        ResultadoLeitura.Aceito(linha)
    } else {
        ResultadoLeitura.BoletoForaDeContexto
    }
}

/**
 * Mensagem para o que foi lido e não serve nesta tela.
 *
 * O tom é o do gate de review: pt-BR, sem jargão e dizendo o próximo passo. "O
 * código é inválido" seria falso e inútil — o código está certo, só não é
 * daqui.
 */
internal fun mensagemLeitura(resultado: ResultadoLeitura, contexto: ContextoLeitura): String =
    when (resultado) {
        ResultadoLeitura.BoletoForaDeContexto ->
            "Esse é o código de barras de um boleto, não uma cobrança. " +
                "Ele é cadastrado em Contas → Nova conta."
        ResultadoLeitura.CobrancaForaDeContexto ->
            "Esse é o QR de uma cobrança do KaveliPay, não um boleto. " +
                "Ele abre em Cobranças → Ler QR de cobrança."
        else -> if (contexto == ContextoLeitura.COBRANCA) {
            "Não reconheci este QR como uma cobrança do KaveliPay."
        } else {
            "Não reconheci este código como um boleto."
        }
    }

/**
 * Leitor único: lê o código de barras do boleto **ou** o QR de uma cobrança do
 * app, e o app reconhece qual dos dois veio.
 *
 * A spec pedia "app lê código de barras" (12B) e "backend sempre valida"
 * (seção 9) — a decisão registrada em `docs/spec.md` foi: o app SÓ LÊ. O
 * código vira texto salvo em `linha_digitavel` (texto livre, o backend não
 * interpreta); valor e vencimento continuam vindo dos campos do formulário,
 * que o backend valida normalmente. Nada do que o código "diz" é usado como
 * dado.
 *
 * Na cobrança o leitor devolve o TOKEN do link, e a tela pública busca
 * descrição e valor no servidor: o QR não carrega valor, então não há nada
 * nele para acreditar.
 *
 * Sem câmera ou sem permissão a tela degrada: mostra o motivo e o caminho de
 * digitação manual — nunca trava o cadastro da conta nem a leitura do QR.
 */
@Composable
fun KpLeitorCodigoBarrasScreen(
    aoVoltar: () -> Unit,
    aoLer: (String) -> Unit,
    contexto: ContextoLeitura = ContextoLeitura.CONTA,
) {
    val contextoApp = LocalContext.current
    val ciclo = LocalLifecycleOwner.current
    // null = ainda pedindo; depois disso, concedida ou não.
    var permissao by remember { mutableStateOf<Boolean?>(null) }
    var erroCamera by remember { mutableStateOf(false) }
    // Guarda o veredito ruim para mostrar a mensagem certa (e não a mesma
    // frase para "não é boleto" e "é boleto da outra tela").
    var leituraRuim by remember { mutableStateOf<ResultadoLeitura?>(null) }
    // Depois de um código que não serve, o leitor pausa — sem isso ele leria o
    // mesmo frame em loop e a mensagem piscaria.
    val pausado = remember { booleanArrayOf(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    val pedido = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { concedido -> permissao = concedido }

    LaunchedEffect(Unit) {
        permissao = ContextCompat.checkSelfPermission(
            contextoApp, Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (permissao != true) pedido.launch(Manifest.permission.CAMERA)
    }

    Scaffold(topBar = {
        CabecalhoComVoltar(
            if (contexto == ContextoLeitura.COBRANCA) "Ler QR de cobrança" else "Escanear boleto",
            aoVoltar,
        )
    }) { padding ->
        // Erro de câmera tem de desmontar o preview: senão o bind falho fica
        // preto na tela e a mensagem de degradação nunca aparece.
        if (permissao == true && !erroCamera) {
            LeitorPreview(
                modifier = Modifier.padding(padding),
                ciclo = ciclo,
                executor = executor,
                pausado = pausado,
                aoDetectar = { bruto ->
                    when (val resultado = classificarLeitura(bruto, contexto)) {
                        is ResultadoLeitura.Aceito -> {
                            pausado[0] = true
                            aoLer(resultado.valor)
                        }
                        else -> {
                            pausado[0] = true
                            leituraRuim = resultado
                        }
                    }
                },
                aoErroCamera = { erroCamera = true },
            )
        } else {
            // Sem permissão, sem câmera ou câmera falhou: mesmo destino —
            // digitar a linha na mão (boleto) ou fechar e pedir o link (cobrança).
            SemCamera(
                contexto = contexto,
                motivo = when {
                    erroCamera -> "Não deu pra abrir a câmera neste aparelho."
                    permissao == false -> "Sem acesso à câmera."
                    else -> "Preparando a câmera…"
                },
                aoVoltar = aoVoltar,
                modifier = Modifier.padding(padding),
            )
        }

        // Sobreposto ao preview: aviso + saída. No estado sem câmera não
        // entra nada — o SemCamera já traz o próprio botão, e duplicar
        // "Digitar manualmente" só confunde.
        val previewAtivo = permissao == true && !erroCamera
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val ruim = leituraRuim
            if (ruim != null) {
                Aviso(mensagemLeitura(ruim, contexto))
                Spacer(Modifier.height(Spacing.sm))
                KpBotaoSecundario(texto = "Tentar de novo", onClick = {
                    leituraRuim = null
                    pausado[0] = false
                })
            } else if (previewAtivo) {
                Aviso(
                    if (contexto == ContextoLeitura.COBRANCA) {
                        "Aponte para o QR Code da cobrança."
                    } else {
                        "Aponte para o código de barras do boleto."
                    },
                )
                Spacer(Modifier.height(Spacing.sm))
            }
            if (previewAtivo) {
                KpBotaoSecundario(
                    texto = if (contexto == ContextoLeitura.COBRANCA) "Fechar" else "Digitar manualmente",
                    onClick = aoVoltar,
                )
            }
        }
    }
}

/** Fundo escuro + texto claro, legível por cima da câmera (contraste AA). */
@Composable
private fun Aviso(texto: String) {
    Text(
        texto,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .background(
                Color.Black.copy(alpha = 0.6f),
                RoundedCornerShape(Pilula),
            )
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    )
}

@Composable
private fun SemCamera(
    contexto: ContextoLeitura,
    motivo: String,
    aoVoltar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.CameraAlt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(Spacing.md))
        Text(motivo, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            if (contexto == ContextoLeitura.COBRANCA) {
                "Sem problema: peça o link da cobrança a quem cobrou."
            } else {
                "Sem problema: a linha digitável pode ser digitada no formulário."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Spacing.xl),
        )
        Spacer(Modifier.height(Spacing.md))
        KpBotaoSecundario(
            texto = if (contexto == ContextoLeitura.COBRANCA) "Fechar" else "Digitar manualmente",
            onClick = aoVoltar,
        )
    }
}

@Composable
private fun LeitorPreview(
    modifier: Modifier,
    ciclo: androidx.lifecycle.LifecycleOwner,
    executor: java.util.concurrent.ExecutorService,
    pausado: BooleanArray,
    aoDetectar: (String) -> Unit,
    aoErroCamera: () -> Unit,
) {
    val contexto = LocalContext.current
    val futuro = remember { ProcessCameraProvider.getInstance(contexto) }
    var provedor by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val preview = remember { Preview.Builder().build() }
    val analise = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // 640x480, não 1280x720: o código de boleto ocupa a tela inteira e
            // é lido com folga nessa resolução, enquanto a 720p cada frame
            // custa 4x mais — a rotação da matriz e o decode do ZXing rodam a
            // cada quadro, numa thread só, e em aparelho fraco isso esquenta e
            // trava a prévia.
            .setTargetResolution(Size(640, 480))
            .build()
            .apply { setAnalyzer(executor, LeitorYuv(pausado, aoDetectar)) }
    }
    val previewView = remember { PreviewView(contexto) }

    LaunchedEffect(Unit) {
        futuro.addListener({ provedor = futuro.get() }, ContextCompat.getMainExecutor(contexto))
    }

    DisposableEffect(provedor, ciclo) {
        val p = provedor
        var vinculado = false
        if (p != null) {
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val seletor = if (p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            try {
                p.bindToLifecycle(ciclo, seletor, preview, analise)
                vinculado = true
            } catch (_: Exception) {
                // Sem câmera nenhuma (emulador, Waydroid): degrada.
                aoErroCamera()
            }
        }
        onDispose {
            if (vinculado) provedor?.unbindAll()
        }
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.ui.viewinterop.AndroidView(factory = { previewView })
        // Mira: retângulo central, o código costuma caber aí.
        Box(
            Modifier
                .size(300.dp, 170.dp)
                .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(16.dp)),
        )
    }
}

/**
 * Zebra/YUV → decodificação zxing, um frame por vez.
 *
 * A matriz chega na orientação do sensor (paisagem na maioria dos aparelhos);
 * sem girar pelos [ImageProxy.getImageInfo] graus, o zxing não lê código
 * deitado — [rotacionarMatriz] resolve antes de entregar ao leitor.
 */
private class LeitorYuv(
    private val pausado: BooleanArray,
    private val aoDetectar: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val leitor = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to formatosDeLeitura(),
            ),
        )
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imagem: ImageProxy) {
        try {
            if (pausado[0]) return
            val media = imagem.image ?: return
            val plano = media.planes[0]
            val bruto = ByteArray(plano.buffer.remaining()).also { plano.buffer.get(it) }
            val larg = imagem.width
            val alt = imagem.height
            // Plane Y costuma ter padding de linha (rowStride > width): o zxing
            // quer a matriz contígua, então as linhas são reempacotadas.
            val contiguo = if (plano.rowStride == larg) {
                bruto
            } else {
                val saida = ByteArray(larg * alt)
                var origem = 0
                for (linha in 0 until alt) {
                    System.arraycopy(bruto, origem, saida, linha * larg, larg)
                    origem += plano.rowStride
                }
                saida
            }
            val (y, wl, ha) = rotacionarMatriz(
                contiguo, larg, alt, imagem.imageInfo.rotationDegrees,
            )
            val fonte = PlanarYUVLuminanceSource(y, wl, ha, 0, 0, wl, ha, false)
            val texto = leitor.decodeWithState(BinaryBitmap(HybridBinarizer(fonte))).text
            aoDetectar(texto)
        } catch (_: NotFoundException) {
            // Frame sem código — o caso normal de câmera apontada pra nada.
        } catch (_: Exception) {
            // Frame ruim: descarta e segue lendo.
        } finally {
            imagem.close()
        }
    }
}

/**
 * Gira a matriz de luminância pelos graus que a câmera reportou.
 *
 * `rotationDegrees` vem em múltiplos de 90; sem esta rotação o leitor só
 * enxerga código na orientação crua do sensor. Devolve a matriz e as novas
 * dimensões (largura e altura trocam em 90/270).
 */
internal fun rotacionarMatriz(
    y: ByteArray,
    larg: Int,
    alt: Int,
    graus: Int,
): Triple<ByteArray, Int, Int> = when (((graus % 360) + 360) % 360) {
    90 -> {
        val saida = ByteArray(y.size)
        for (linha in 0 until alt) for (col in 0 until larg) {
            saida[col * alt + (alt - 1 - linha)] = y[linha * larg + col]
        }
        Triple(saida, alt, larg)
    }

    180 -> {
        val saida = ByteArray(y.size)
        for (linha in 0 until alt) for (col in 0 until larg) {
            saida[(alt - 1 - linha) * larg + (larg - 1 - col)] = y[linha * larg + col]
        }
        Triple(saida, larg, alt)
    }

    270 -> {
        val saida = ByteArray(y.size)
        for (linha in 0 until alt) for (col in 0 until larg) {
            saida[(larg - 1 - col) * alt + linha] = y[linha * larg + col]
        }
        Triple(saida, alt, larg)
    }

    else -> Triple(y, larg, alt)
}

/**
 * Texto lido da imagem → código salvo, ou `null` se não parece boleto.
 *
 * O código impresso codifica as 44 posições do código de barras bancário; a
 * "linha digitável" digitada tem 47 (leva DV por campo). Os dois formatos são
 * aceitos — `linha_digitavel` é texto livre no backend, sem interpretação.
 * Sobra não numérica (espaços do leitor) é descartada.
 */
internal fun normalizarLinhaDigitavel(bruto: String): String? {
    val digitos = bruto.filter { it.isDigit() }
    return if (digitos.length == 44 || digitos.length == 47) digitos else null
}

/**
 * Extrai o token de um QR/link de cobrança: `kavelipay://cobranca/<token>`.
 *
 * O token é o segredo de 32 hex que o backend gera (migration 0010); o QR não
 * carrega valor, então não há nada aqui para "decodificar" além do endereço.
 * Formato fora do contrato devolve `null` — aceitar um link "quase certo" e
 * abrir a tela com token inválido daria um 404 que parece bug do app.
 *
 * Query e fragmento são ignorados: o backend já não os manda, mas um QR
 * impresso por uma versão anterior (que tinha `?valor=`) continua abrindo.
 */
internal fun tokenDeCobranca(bruto: String): String? {
    val prefixo = "kavelipay://cobranca/"
    val texto = bruto.trim()
    if (texto.length <= prefixo.length) return null
    if (!texto.regionMatches(0, prefixo, 0, prefixo.length, ignoreCase = true)) return null
    val token = texto.substring(prefixo.length)
        .substringBefore('?')
        .substringBefore('#')
        .trim()
        .lowercase()
    return token.takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
}

/**
 * Formatos que o decodificador procura — lista única, com os dois mundos.
 *
 * A câmera é uma só (CameraX + zxing) e o app reconhece o que leu
 * ([classificarLeitura]); portanto a lista não pode ser de um só tipo. Sem
 * `QR_CODE` aqui o leitor varre o frame atrás apenas de código de barras 1D e
 * nunca acha o QR — mesmo com a imagem perfeita, a tela fica "procurando" e
 * parece câmera quebrada. Sem os 1D, o caminho do boleto (12B) morre.
 *
 * Um QR qualquer que entre como texto não é mais "código inválido":
 * [classificarLeitura] devolve [ResultadoLeitura.NaoReconhecido] se não for
 * cobrança do app, e a tela diz isso em pt-BR.
 *
 * Lista curta não é só estética: `MultiFormatReader` tenta formato por
 * formato, por frame — esta é a ordem em que ele tenta.
 */
internal fun formatosDeLeitura(): List<com.google.zxing.BarcodeFormat> = listOf(
    // Boleto brasileiro é CODE_128 (CBB) ou ITF; CODE_39/EAN_13 cobrem
    // boletos e carnês antigos que aparecem na prática.
    com.google.zxing.BarcodeFormat.CODE_128,
    com.google.zxing.BarcodeFormat.ITF,
    com.google.zxing.BarcodeFormat.CODE_39,
    com.google.zxing.BarcodeFormat.EAN_13,
    // QR do próprio app (`kavelipay://cobranca/<token>`).
    com.google.zxing.BarcodeFormat.QR_CODE,
)
