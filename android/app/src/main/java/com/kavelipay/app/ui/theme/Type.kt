package com.kavelipay.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Escala tipográfica.
 *
 * A Wise usa peso 900 e line-height 0.85 no display — no web, a 126px. Num
 * celular, 44sp do saldo já é o número mais grande da tela; o mesmo peso e o
 * mesmo tracking apertado é que carregam a identidade.
 *
 * Inter não vem no Android. Usamos Roboto (que tem Black/900) com o tracking
 * da Wise. Se um dia for possível embutir a Inter, é trocar `FontFamily`.
 */
val KpFont = FontFamily.Default // Roboto no Android

private val apertado = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

val SaldoDisplay = TextStyle(
    fontFamily = KpFont,
    fontWeight = FontWeight.Black,
    fontSize = 44.sp,
    lineHeight = 44.sp,
    letterSpacing = (-1.8).sp,
    lineHeightStyle = apertado,
)

val TituloGrande = TextStyle(
    fontFamily = KpFont,
    fontWeight = FontWeight.Black,
    fontSize = 28.sp,
    lineHeight = 30.sp,
    letterSpacing = (-0.8).sp,
    lineHeightStyle = apertado,
)

val TituloSecao = TextStyle(
    fontFamily = KpFont,
    fontWeight = FontWeight.Bold,
    fontSize = 19.sp,
    lineHeight = 24.sp,
    letterSpacing = (-0.4).sp,
)

val TituloCartao = TextStyle(
    fontFamily = KpFont,
    fontWeight = FontWeight.Bold,
    fontSize = 16.sp,
    lineHeight = 21.sp,
    letterSpacing = (-0.2).sp,
)

val CorpoForte = TextStyle(
    fontFamily = KpFont,
    // Peso 600 como padrão de leitura, como a Wise: confiante, não leve.
    fontWeight = FontWeight.SemiBold,
    fontSize = 15.sp,
    lineHeight = 22.sp,
)

val Corpo = TextStyle(
    fontFamily = KpFont,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 22.sp,
)

val Legenda = TextStyle(
    fontFamily = KpFont,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 18.sp,
)

val Micro = TextStyle(
    fontFamily = KpFont,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 15.sp,
    letterSpacing = 0.4.sp,
)

/** Valor monetário em linha de lista: peso alto e alinhamento à direita. */
val ValorLista = TextStyle(
    fontFamily = KpFont,
    fontWeight = FontWeight.Bold,
    fontSize = 16.sp,
    lineHeight = 20.sp,
    letterSpacing = (-0.3).sp,
)

/** Rótulo ALL-CAPS pequeno, como os da Wise. */
val RotuloCaixaAlta = TextStyle(
    fontFamily = KpFont,
    fontWeight = FontWeight.Bold,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.8.sp,
)
