package com.kavelipay.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Escala tipográfica estilo Nubank.
 *
 * - 32sp: saldo herói (Home)
 * - 24sp: valores em cards hero (QR, Ativo, Derivação)
 * - 17sp: títulos de tela (topbar / section headers)
 * - 14sp: corpo padrão
 * - 12sp: metadados, chips, labels (mínimo absoluto)
 *
 * Números monetários **sempre** com `tabularNums` para não "dançar".
 * Nota: FontFeature cv11 requer Compose 1.7+; omitido para compatibilidade.
 */
val KpFont = FontFamily.Default // Roboto no Android

private val trackingApertado = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/** Saldo herói: 32sp, bold, tracking -2%. */
val SaldoHeroi = TextStyle(
    fontFamily = KpFont,
    fontWeight = FontWeight.Bold,
    fontSize = 32.sp,
    lineHeight = 40.sp,
    letterSpacing = (-0.64).sp, // -2% de 32
    lineHeightStyle = trackingApertado,
)

/** Valor hero card: 24sp, semibold. */
val ValorHeroCard = TextStyle(
    fontFamily = KpFont,
    fontWeight = FontWeight.SemiBold,
    fontSize = 24.sp,
    lineHeight = 32.sp,
    letterSpacing = (-0.24).sp,
    lineHeightStyle = trackingApertado,
)

/** Título de tela / seção principal: 17sp, semibold. */
val TituloTela = TextStyle(
    fontFamily = KpFont,
    fontWeight = FontWeight.SemiBold,
    fontSize = 17.sp,
    lineHeight = 24.sp,
    letterSpacing = (-0.17).sp,
)

/** Título de card / seção secundária: 16sp, semibold. */
val TituloCard = TextStyle(
    fontFamily = KpFont,
    fontWeight = FontWeight.SemiBold,
    fontSize = 16.sp,
    lineHeight = 22.sp,
    letterSpacing = (-0.16).sp,
)

/** Corpo padrão: 14sp, regular. */
val Corpo = TextStyle(
    fontFamily = KpFont,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 20.sp,
)

/** Corpo forte (ênfase): 14sp, semibold. */
val CorpoForte = TextStyle(
    fontFamily = KpFont,
    fontWeight = FontWeight.SemiBold,
    fontSize = 14.sp,
    lineHeight = 20.sp,
)

/** Subtexto / metadado / chip: 12sp, medium. Mínimo absoluto. */
val Metadado = TextStyle(
    fontFamily = KpFont,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.2.sp,
)

/** Label ALL-CAPS pequeno (ex.: "SALDO DISPONÍVEL"): 11sp, bold, tracking +. */
val RotuloCaixaAlta = TextStyle(
    fontFamily = KpFont,
    fontWeight = FontWeight.Bold,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.8.sp,
)

/** Valor monetário em linha de lista: 16sp, bold, alinhado à direita. */
val ValorLista = TextStyle(
    fontFamily = KpFont,
    fontWeight = FontWeight.Bold,
    fontSize = 16.sp,
    lineHeight = 20.sp,
    letterSpacing = (-0.16).sp,
)

/** Micro / legal / rodapé: 11sp, regular. */
val Micro = TextStyle(
    fontFamily = KpFont,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 15.sp,
    letterSpacing = 0.3.sp,
)

// ==== Aliases de compatibilidade (para não quebrar telas existentes) ====
/** @Deprecated Use SaldoHeroi */ @Deprecated("Use SaldoHeroi", ReplaceWith("SaldoHeroi"))
val SaldoDisplay = SaldoHeroi
/** @Deprecated Use TituloTela */ @Deprecated("Use TituloTela", ReplaceWith("TituloTela"))
val TituloGrande = TituloTela
/** @Deprecated Use TituloCard */ @Deprecated("Use TituloCard", ReplaceWith("TituloCard"))
val TituloSecao = TituloCard
/** @Deprecated Use CorpoForte */ @Deprecated("Use CorpoForte", ReplaceWith("CorpoForte"))
val Legenda = CorpoForte