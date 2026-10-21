package com.kavelipay.app.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Carregador de imagem da vitrine de parceiros.
 *
 * Não há Coil no projeto e não vale puxar uma dependência inteira para sete
 * fotos de card: aqui é um GET, um `BitmapFactory` e um cache LRU em memória.
 * O conteúdo é sempre imagem pública de CDN — nada de sessão, nada de cookie,
 * nada de dado do usuário vai junto no pedido.
 *
 * ## Por que a imagem é reduzida na hora de decodificar
 *
 * A primeira versão decodificava no tamanho original e guardava 48 bitmaps.
 * Uma foto de marketplace de 1000x1000 ocupa 4 MB em ARGB_8888 — 48 delas
 * passariam de 190 MB. Num celular de 1 GB de RAM o sistema mata o app antes
 * de o usuário chegar ao fim da vitrine. O card desenha a foto a ~480 px no
 * aparelho mais denso que existe, então qualquer coisa acima de 512 px é
 * memória jogada fora: `inSampleSize` corta em potências de 2 durante a
 * decodificação, sem nunca alocar o bitmap grande.
 *
 * ## Por que o cache é medido em BYTES
 *
 * `LruCache(48)` conta entradas, não peso: 48 fotos pequenas e 48 fotos
 * gigantes contam igual. Medindo `byteCount` o teto vira memória de verdade,
 * dimensionada pela heap que o aparelho realmente tem.
 */

/** Teto do ARQUIVO baixado — protege de uma resposta errada (HTML enorme). */
private const val TAMANHO_MAXIMO_BYTES = 4L * 1024 * 1024

/** Cache HTTP em disco: reabrir a vitrine não rebaixa as mesmas fotos. */
private const val CACHE_DISCO_BYTES = 20L * 1024 * 1024

private val cacheImagens = object : LruCache<String, ImageBitmap>(memoriaDoCache()) {
    override fun sizeOf(chave: String, valor: ImageBitmap): Int =
        valor.asAndroidBitmap().byteCount
}

/**
 * Um oitavo da heap máxima do aparelho, entre 4 e 32 MB.
 *
 * Um oitavo é o que sobra com folga para o resto do app: num aparelho de
 * heap 192 MB dá 24 MB (~24 fotos de 500 px); num de 512 MB bate no teto de
 * 32 MB em vez de comer 64 MB.
 */
private fun memoriaDoCache(): Int {
    val oitavo = Runtime.getRuntime().maxMemory() / 8
    return oitavo.coerceIn(4L * 1024 * 1024, 32L * 1024 * 1024).toInt()
}

/**
 * Cliente HTTP só de imagem, criado uma vez por processo.
 *
 * O cache em disco precisa de um diretório, e o diretório precisa de
 * `Context` — por isso o `lazy` é manual: guarda o primeiro que aparecer.
 */
@Volatile
private var clienteImagens: OkHttpClient? = null

/** Tranca da criação acima: função de topo não tem `this` para sincronizar. */
private val trancaDoCliente = Any()

private fun cliente(contexto: Context): OkHttpClient =
    clienteImagens ?: synchronized(trancaDoCliente) {
        clienteImagens ?: OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .cache(Cache(File(contexto.cacheDir, "imagens"), CACHE_DISCO_BYTES))
            .build()
            .also { clienteImagens = it }
    }

suspend fun carregarImagem(contexto: Context, url: String): ImageBitmap? =
    withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) return@withContext null
        cacheImagens.get(url)?.let { return@withContext it }

        runCatching {
            val pedido = Request.Builder()
                .url(url)
                // WebP primeiro: o CDN do Mercado Livre serve WebP, que
                // decodifica em bem menos bytes que o JPG equivalente.
                .header("Accept", "image/webp,image/*;q=0.8")
                .build()

            cliente(contexto).newCall(pedido).execute().use { resposta ->
                if (!resposta.isSuccessful) return@use null
                val corpo = resposta.body ?: return@use null
                if (corpo.contentLength() > TAMANHO_MAXIMO_BYTES) return@use null
                // `contentLength` é -1 quando a resposta vem em chunks; o teto
                // tem de valer na leitura, não só no cabeçalho.
                val bytes = lerLimitado(corpo.byteStream()) ?: return@use null
                decodificarReduzido(bytes)
            }
        }.getOrNull()?.also { cacheImagens.put(url, it) }
    }

/** Lê até [TAMANHO_MAXIMO_BYTES]; acima disso desiste em vez de acumular. */
private fun lerLimitado(entrada: InputStream): ByteArray? {
    val saida = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val lidos = entrada.read(buffer)
        if (lidos < 0) break
        total += lidos
        if (total > TAMANHO_MAXIMO_BYTES) return null
        saida.write(buffer, 0, lidos)
    }
    return saida.toByteArray()
}

/**
 * Decodifica já no tamanho aproximado de exibição.
 *
 * O primeiro passo só LÊ as dimensões (`inJustDecodeBounds`) — nenhum pixel
 * é alocado. O segundo aplica `inSampleSize`, que o decodificador usa para
 * pular pixels na origem em vez de decodificar tudo e encolher depois.
 */
private fun decodificarReduzido(bytes: ByteArray): ImageBitmap? {
    val medida = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, medida)
    if (medida.outWidth <= 0 || medida.outHeight <= 0) return null

    val opcoes = BitmapFactory.Options().apply {
        inSampleSize = fatorAmostragem(medida.outWidth, medida.outHeight)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opcoes)?.asImageBitmap()
}

/**
 * Imagem remota com placeholder.
 *
 * Falha de imagem NUNCA vira erro de tela: o card continua clicável com o
 * ícone de loja no lugar. Foto que não carregou não pode impedir a pessoa de
 * chegar ao produto — e a foto é o elemento menos importante do card.
 */
@Composable
fun KpImagemRemota(
    url: String,
    modifier: Modifier = Modifier,
    descricao: String? = null,
) {
    val contexto = LocalContext.current
    var imagem by remember(url) { mutableStateOf(cacheImagens.get(url)) }

    LaunchedEffect(url) {
        if (imagem == null) imagem = carregarImagem(contexto, url)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val pronta = imagem
        if (pronta != null) {
            Image(
                bitmap = pronta,
                contentDescription = descricao,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Default.Storefront,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
