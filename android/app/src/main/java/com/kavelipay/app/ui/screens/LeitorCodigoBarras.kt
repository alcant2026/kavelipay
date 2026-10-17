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
 * Leitor de código de barras (linha digitável do boleto).
 *
 * A spec pedia "app lê código de barras" (12B) e "backend sempre valida"
 * (seção 9) — a decisão registrada em `docs/spec.md` foi: o app SÓ LÊ. O
 * código vira texto salvo em `linha_digitavel` (texto livre, o backend não
 * interpreta); valor e vencimento continuam vindo dos campos do formulário,
 * que o backend valida normalmente. Nada do que o código "diz" é usado como
 * dado.
 *
 * Sem câmera ou sem permissão a tela degrada: mostra o motivo e o caminho de
 * digitação manual — nunca trava o cadastro da conta.
 */
@Composable
fun KpLeitorCodigoBarrasScreen(
    aoVoltar: () -> Unit,
    aoLer: (String) -> Unit,
) {
    val contexto = LocalContext.current
    val ciclo = LocalLifecycleOwner.current
    // null = ainda pedindo; depois disso, concedida ou não.
    var permissao by remember { mutableStateOf<Boolean?>(null) }
    var erroCamera by remember { mutableStateOf(false) }
    var codigoInvalido by remember { mutableStateOf(false) }
    // Depois de um código que não é de boleto, o leitor pausa — sem isso ele
    // leria o mesmo frame inválido em loop e a mensagem piscaria.
    val pausado = remember { booleanArrayOf(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    val pedido = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { concedido -> permissao = concedido }

    LaunchedEffect(Unit) {
        permissao = ContextCompat.checkSelfPermission(
            contexto, Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (permissao != true) pedido.launch(Manifest.permission.CAMERA)
    }

    Scaffold(topBar = { CabecalhoComVoltar("Escanear código de barras", aoVoltar) }) { padding ->
        // Erro de câmera tem de desmontar o preview: senão o bind falho fica
        // preto na tela e a mensagem de degradação nunca aparece.
        if (permissao == true && !erroCamera) {
            LeitorPreview(
                modifier = Modifier.padding(padding),
                ciclo = ciclo,
                executor = executor,
                pausado = pausado,
                aoDetectar = { bruto ->
                    val codigo = normalizarLinhaDigitavel(bruto)
                    if (codigo != null) {
                        pausado[0] = true
                        aoLer(codigo)
                    } else {
                        pausado[0] = true
                        codigoInvalido = true
                    }
                },
                aoErroCamera = { erroCamera = true },
            )
        } else {
            // Sem permissão, sem câmera ou câmera falhou: mesmo destino —
            // digitar a linha na mão.
            SemCamera(
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
            if (codigoInvalido) {
                Aviso("Esse código não é de boleto. Tente de novo.")
                Spacer(Modifier.height(Spacing.sm))
                KpBotaoSecundario(texto = "Tentar de novo", onClick = {
                    codigoInvalido = false
                    pausado[0] = false
                })
            } else if (previewAtivo) {
                Aviso("Aponte para o código de barras do boleto.")
                Spacer(Modifier.height(Spacing.sm))
            }
            if (previewAtivo) {
                KpBotaoSecundario(texto = "Digitar manualmente", onClick = aoVoltar)
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
            "Sem problema: a linha digitável pode ser digitada no formulário.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Spacing.xl),
        )
        Spacer(Modifier.height(Spacing.md))
        KpBotaoSecundario(texto = "Digitar manualmente", onClick = aoVoltar)
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
            .setTargetResolution(Size(1280, 720))
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
                // Boleto brasileiro é CODE_128 (CBB) ou ITF; restringir formats
                // deixa o por-frame mais leve que MultiFormatReader solto.
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    com.google.zxing.BarcodeFormat.CODE_128,
                    com.google.zxing.BarcodeFormat.ITF,
                    com.google.zxing.BarcodeFormat.CODE_39,
                    com.google.zxing.BarcodeFormat.EAN_13,
                ),
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
