package com.kavelipay.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.LocalRippleTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material.ripple.RippleTheme
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Escala de raio estilo Nubank: pílula 9999, cards 16dp, inputs 12dp. */
val Pilula: Dp = 9999.dp
val RaioCard: Dp = 16.dp
val RaioCardGrande: Dp = 24.dp
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

/**
 * Tema ATUAL do app (a escolha do botão da TopBar) — não o do sistema.
 *
 * Componentes não podem usar `isSystemInDarkTheme()` para decidir cor: o app
 * tem botão de tema próprio, e o sistema pode estar em dark com o app em
 * light (ou o contrário). Aí o card escuro saía com texto do tema claro por
 * cima — o "dark/light não funciona" que o usuário viu.
 */
@Composable
@ReadOnlyComposable
fun temaEscuroApp(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * Alfa do estado de toque, sem os estados que só existem em computador.
 *
 * O Material desenha um véu sobre o componente quando o ponteiro está por
 * cima (`hoveredAlpha`) e quando ele recebe foco de teclado (`focusedAlpha`).
 * No celular nada disso acontece — o dedo não "pousa" no botão. Mas aqui o
 * teste é no Waydroid, que injeta mouse: depois de um toque o ponteiro fica
 * parado sobre o componente e o véu permanece, parecendo sombra/sujeira.
 * Zerar os dois mantém só o retorno de toque, que é o do celular.
 */
private object RippleSoDeToque : RippleTheme {
    @Composable
    @ReadOnlyComposable
    override fun defaultColor(): Color = LocalContentColor.current

    @Composable
    @ReadOnlyComposable
    override fun rippleAlpha(): RippleAlpha = RippleAlpha(
        pressedAlpha = 0.12f,
        draggedAlpha = 0.16f,
        focusedAlpha = 0f,
        hoveredAlpha = 0f,
    )
}

@Composable
fun KaveliPayTheme(
    escuro: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        SemanticasLocal provides if (escuro) SemanticasDark else SemanticasLocal.current,
        LocalRippleTheme provides RippleSoDeToque,
    ) {
        MaterialTheme(
            colorScheme = if (escuro) Escuro else Claro,
            typography = MaterialTheme.typography.copy(
                displayLarge = SaldoHeroi,
                headlineLarge = TituloTela,
                headlineSmall = TituloCard,
                titleMedium = TituloCard,
                bodyLarge = Corpo,
                bodyMedium = CorpoForte,
                labelSmall = Micro,
            ),
            shapes = Formas,
            content = content,
        )
    }
}
