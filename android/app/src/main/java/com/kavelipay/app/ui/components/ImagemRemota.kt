package com.kavelipay.app.ui.components

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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Carregador de imagem da vitrine de parceiros.
 *
 * Não há Coil no projeto e não vale puxar uma dependência inteira para sete
 * fotos de card: aqui é um GET, um `BitmapFactory` e um cache LRU em memória.
 * O conteúdo é sempre imagem pública de CDN — nada de sessão, nada de cookie,
 * nada de dado do usuário vai junto no pedido.
 *
 * SÓ HTTPS. O banco também só aceita `https://` (`CHECK` na migration 0011),
 * e esta checagem existe porque defesa em profundidade aqui custa uma linha:
 * uma URL `http://` ou `file://` vinda de qualquer lugar não vira imagem.
 */
private const val TAMANHO_MAXIMO_BYTES = 4L * 1024 * 1024

/** 24 entradas cabem folgado em memória e cobrem a vitrine inteira. */
private val cacheImagens = LruCache<String, ImageBitmap>(24)

private val clienteImagens: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}

suspend fun carregarImagem(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    if (!url.startsWith("https://")) return@withContext null
    cacheImagens.get(url)?.let { return@withContext it }

    runCatching {
        val pedido = Request.Builder()
            .url(url)
            // WebP primeiro: o CDN do Mercado Livre serve WebP, que decodifica
            // em bem menos bytes que o JPG equivalente.
            .header("Accept", "image/webp,image/*;q=0.8")
            .build()

        clienteImagens.newCall(pedido).execute().use { resposta ->
            if (!resposta.isSuccessful) return@use null
            val corpo = resposta.body ?: return@use null
            // Sem este teto, uma resposta errada (HTML gigante, por exemplo)
            // seria decodificada à toa e comeria memória do aparelho.
            if (corpo.contentLength() > TAMANHO_MAXIMO_BYTES) return@use null
            corpo.byteStream().use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        }
    }.getOrNull()?.also { cacheImagens.put(url, it) }
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
    var imagem by remember(url) { mutableStateOf(cacheImagens.get(url)) }

    LaunchedEffect(url) {
        if (imagem == null) imagem = carregarImagem(url)
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
