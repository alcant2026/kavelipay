package com.kavelipay.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR Code renderizado a partir de um payload de texto.
 *
 * O ZXing devolve a matriz em `BitMatrix` (1 bit por módulo). Converter para
 * `Bitmap` escalando por inteiro é o que mantém a leitura possível: um
 * `Bitmap` de 1px por módulo some na tela e some também para a câmera.
 *
 * Cuidado com o ZXing e o ProGuard: `QRCodeWriter` é acessado por reflexão
 * interna dele mesmo. O `-keep class com.google.zxing.**` do
 * `proguard-rules.pro` cobre isso; sem ele o R8 quebra o writer só no APK
 * release — o app funciona em debug e morre em produção, que é o pior tipo de
 * divergência.
 *
 * `format` e `provider` do payload não entram aqui. O backend devolve
 * `format: DEEP_LINK`, e o QR é do deep link `kavelipay://` — **não** é BR
 * Code/EMV e o app não processa pagamento nenhum. Gerar o código não muda isso.
 */
@Composable
fun KpQrCode(
    payload: String,
    modifier: Modifier = Modifier,
    tamanho: Dp = 240.dp,
    corFundo: Color = Color.White,
    corCodigo: Color = Color.Black,
) {
    // `remember` por payload: regenerar a matriz a cada recomposição é CPU
    // jogada fora, e a tela de QR recompõe várias vezes ao carregar.
    val modulos = remember(payload) { gerarModulos(payload) }
    val densidade = LocalDensity.current.density

    Box(
        modifier = modifier
            .size(tamanho)
            .clip(RoundedCornerShape(com.kavelipay.app.ui.theme.RaioPequeno))
            .background(corFundo),
        contentAlignment = Alignment.Center,
    ) {
        if (modulos == null) {
            // Payload vazio ou longo demais: ZXing lança `WriterException` em vez
            // de devolver null. Uma tela de erro é melhor que um crash.
            Text(
                "Não foi possível gerar o QR Code",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            val escala = remember(modulos, tamanho, densidade) {
                escalaPara(modulos, tamanho, densidade)
            }
            Image(
                bitmap = modulos.paraBitmap(escala, corFundo, corCodigo).asImageBitmap(),
                contentDescription = "QR Code da cobrança",
                modifier = Modifier.size(tamanho),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/**
 * Gera a matriz de módulos. `null` quando o ZXing não consegue codificar.
 *
 * `MARGIN` 1 módulo é o mínimo que a spec do QR aceita (o padrão do ZXing é 4
 * e some área útil). Margem de 1 já dá o "respiro" que o leitor precisa.
 */
private fun gerarModulos(payload: String): BitMatrix? {
    if (payload.isBlank()) return null
    return try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
    } catch (e: Exception) {
        // `WriterException` para payload vazio ou maior que o limite do formato.
        null
    }
}

/**
 * Escolhe o maior múltiplo inteiro de pixels por módulo que caiba em [tamanho].
 *
 * Divisão não-inteira produz módulos de larguras diferentes (uns 3px, outros
 * 4px) e o leitor perde a leitura em parte — o sintoma é "às vezes o QR não
 * abre", que é bem pior que um QR um pouco menor.
 */
private fun escalaPara(modulos: BitMatrix, tamanho: Dp, densidade: Float): Int {
    val alvoPx = (tamanho.value * densidade).toInt().coerceAtLeast(1)
    return (alvoPx / modulos.width).coerceAtLeast(1)
}

/**
 * `BitMatrix` → `Bitmap` com cada módulo como um quadrado de [escala] px.
 *
 * `ARGB_8888` e não `ALPHA_8`: um bitmap só com alpha não sabe qual é a cor do
 * módulo ligado, então o QR sairia todo escuro ou todo claro.
 */
private fun BitMatrix.paraBitmap(
    escala: Int,
    corFundo: Color,
    corCodigo: Color,
): Bitmap {
    val lado = width * escala
    val bitmap = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
    val fundo = corFundo.toArgbInt()
    val codigo = corCodigo.toArgbInt()
    val pixels = IntArray(lado * lado) { fundo }
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (!get(x, y)) continue
            val base = y * escala * lado + x * escala
            for (dy in 0 until escala) {
                val linha = base + dy * lado
                for (dx in 0 until escala) {
                    pixels[linha + dx] = codigo
                }
            }
        }
    }
    bitmap.setPixels(pixels, 0, lado, 0, 0, lado, lado)
    return bitmap
}

/**
 * `Color` → int ARGB.
 *
 * `Color.value` é um `ULong` no formato `0xAARRGGBB`. Deslocar com `0xFF` e
 * converter para `Int` direto não compila (`shr` de literal com sinal é
 * proibido), então o shift vai para `Long` e a conversão final é `toInt()`.
 */
private fun Color.toArgbInt(): Int {
    val v = value.toLong() and 0xFFFFFFFFL
    val a = ((v shr 24) and 0xFFL).toInt()
    val r = ((v shr 16) and 0xFFL).toInt()
    val g = ((v shr 8) and 0xFFL).toInt()
    val b = (v and 0xFFL).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}