package com.kavelipay.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Escala de raio da Wise: pílula nos botões, 20–28 nos cartões. */
val Pilula: Dp = 9999.dp
val RaioCard: Dp = 20.dp
val RaioCardGrande: Dp = 28.dp
val RaioInput: Dp = 12.dp
val RaioPequeno: Dp = 8.dp

private val Formas = Shapes(
    extraSmall = RoundedCornerShape(RaioPequeno),
    small = RoundedCornerShape(RaioInput),
    medium = RoundedCornerShape(RaioCard),
    large = RoundedCornerShape(RaioCardGrande),
    extraLarge = RoundedCornerShape(32.dp),
)

private val Claro = lightColorScheme(
    primary = LimeMarca,
    onPrimary = VerdeEscuro,
    primaryContainer = MentaSuave,
    onPrimaryContainer = VerdeEscuro,
    secondary = VerdeEscuro,
    onSecondary = Branco,
    secondaryContainer = MentaSuave,
    onSecondaryContainer = VerdeEscuro,
    tertiary = AvisoPrimaria,
    onTertiary = Branco,
    background = Branco,
    onBackground = PretoNear,
    surface = Branco,
    onSurface = PretoNear,
    surfaceVariant = SuperficieClara,
    onSurfaceVariant = TextoSecundario,
    outline = BordaClara,
    outlineVariant = BordaClara,
    error = VencidoPrimaria,
    onError = Branco,
    errorContainer = VencidoFundo,
    onErrorContainer = VencidoPrimaria,
)

private val Escuro = darkColorScheme(
    primary = LimeMarca,
    onPrimary = VerdeEscuro,
    primaryContainer = Color(0xFF2A3F1C),
    onPrimaryContainer = MentaSuave,
    secondary = LimeMarca,
    onSecondary = VerdeEscuro,
    background = PretoDark,
    onBackground = TextoDarkPrimario,
    surface = PretoDark,
    onSurface = TextoDarkPrimario,
    surfaceVariant = SuperficieDark,
    onSurfaceVariant = TextoDarkSecundario,
    outline = BordaDark,
    outlineVariant = BordaDark,
    error = VencidoDark,
    onError = Color(0xFF3B1512),
    errorContainer = VencidoFundoDark,
    onErrorContainer = VencidoDark,
)

/**
 * Cores semânticas de finanças.
 *
 * Ficam FORA do `ColorScheme` de propósito: `ColorScheme` é contrato do
 * Material 3, e forçar "receita" num `tertiary` quebra qualquer componente que
 * espere `tertiary` para decoração. São cores do domínio, e vão num
 * CompositionLocal próprio.
 */
@Immutable
data class KpSemanticas(
    val receita: Color,
    val receitaFundo: Color,
    val despesa: Color,
    val despesaFundo: Color,
    val vencido: Color,
    val vencidoFundo: Color,
    val aviso: Color,
    val avisoFundo: Color,
    val demo: Color,
    val demoFundo: Color,
)

private val SemanticasLocal = staticCompositionLocalOf {
    KpSemanticas(
        receita = ReceitaPrimaria,
        receitaFundo = ReceitaFundo,
        despesa = PretoNear,
        despesaFundo = DespesaFundo,
        vencido = VencidoPrimaria,
        vencidoFundo = VencidoFundo,
        aviso = AvisoPrimaria,
        avisoFundo = AvisoFundo,
        demo = VerdeEscuro,
        demoFundo = MentaSuave,
    )
}

private val SemanticasDark = KpSemanticas(
    receita = ReceitaDark,
    receitaFundo = Color(0xFF1C2E17),
    despesa = TextoDarkPrimario,
    despesaFundo = SuperficieDarkAlt,
    vencido = VencidoDark,
    vencidoFundo = VencidoFundoDark,
    aviso = AvisoDark,
    avisoFundo = AvisoFundoDark,
    demo = MentaSuave,
    demoFundo = Color(0xFF2A3F1C),
)

object KpTheme {
    val semanticas: KpSemanticas
        @Composable @ReadOnlyComposable get() = SemanticasLocal.current
}

/** Base 8dp, como a Wise. */
object Spacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp
    val xxl: Dp = 48.dp
}

@Composable
fun KaveliPayTheme(
    escuro: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        SemanticasLocal provides if (escuro) SemanticasDark else SemanticasLocal.current,
    ) {
        MaterialTheme(
            colorScheme = if (escuro) Escuro else Claro,
            typography = MaterialTheme.typography.copy(
                displayLarge = SaldoDisplay,
                headlineLarge = TituloGrande,
                headlineSmall = TituloSecao,
                titleMedium = TituloCartao,
                bodyLarge = Corpo,
                bodyMedium = CorpoForte,
                labelSmall = Micro,
            ),
            shapes = Formas,
            content = content,
        )
    }
}
