package com.kavelipay.app.ui.components

/** Maior lado da imagem de card decodificada. Card não passa de ~480 px. */
internal const val LADO_MAXIMO_PX = 512

/**
 * Menor potência de 2 que deixa o maior lado em [ladoMaximo] ou menos.
 *
 * `inSampleSize` do `BitmapFactory` só aceita potências de 2: 1000 px vira
 * 500 (fator 2), nunca 512. É arredondamento para baixo proposital — foto de
 * card não precisa mais, e cada halving é 4× menos memória.
 *
 * Vive num arquivo próprio, sem nada de Android, porque é a única parte do
 * carregador de imagem que dá para testar na JVM.
 */
internal fun fatorAmostragem(
    largura: Int,
    altura: Int,
    ladoMaximo: Int = LADO_MAXIMO_PX,
): Int {
    var fator = 1
    while (maxOf(largura, altura) / fator > ladoMaximo) fator *= 2
    return fator
}
