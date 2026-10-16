package com.kavelipay.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta derivada do design system Wise (lime sobre quase-preto), adaptada a
 * um app de finanças brasileiro.
 *
 * Adaptação principal, e é uma decisão: **despesa NÃO é vermelha.**
 * Num app pessoal, despesa é ~90% das linhas da lista. Colorir tudo de vermelho
 * treina o usuário a sentir ansiedade com o próprio extrato e faz o app parecer
 * um log de erro. Vermelho fica reservado para destrutivo (cancelar, eliminar
 * conta) e para vencido — que é de fato um problema.
 */

// ── Neutros (quase-preto com sub tom verde, como a Wise) ────────────────────
val PretoNear = Color(0xFF0E0F0C)      // texto primário, fundo do dark
val Branco = Color(0xFFFFFFFF)
val SuperficieClara = Color(0xFFF7F9F6) // off-white com tom verde
val SuperficieClaraAlt = Color(0xFFEFF3EC)
val TextoSecundario = Color(0xFF454745)
val TextoTerciario = Color(0xFF868685)
val BordaClara = Color(0x1A0E0F0C)      // rgba(14,15,12,0.12) — o "ring" da Wise

// ── Marca ───────────────────────────────────────────────────────────────────
val LimeMarca = Color(0xFF9FE870)      // CTA principal
val VerdeEscuro = Color(0xFF163300)    // texto sobre lime
val MentaSuave = Color(0xFFE2F6D5)     // superfície verde
val LimaPastel = Color(0xFFCDFFAD)     // hover do CTA

// ── Semântica financeira ─────────────────────────────────────────────────────
val ReceitaPrimaria = Color(0xFF054D28) // verde profundo, texto de entrada
val ReceitaFundo = Color(0xFFE2F6D5)
val DespesaPrimaria = Color(0xFF0E0F0C) // quase-preto: despesa é o normal
val DespesaFundo = Color(0xFFF1F3EF)

// Aviso e destrutivo: os únicos usos de vermelho/laranja.
val VencidoPrimaria = Color(0xFFB42318)
val VencidoFundo = Color(0xFFFEE4E2)
val AvisoPrimaria = Color(0xFFB54708)
val AvisoFundo = Color(0xFFFEF0C7)

// ── Modo escuro ─────────────────────────────────────────────────────────────
val PretoDark = Color(0xFF0E0F0C)
val SuperficieDark = Color(0xFF1A1C18)
val SuperficieDarkAlt = Color(0xFF23261F)
// Fundo do card de saldo herói no dark: #123A1E (contraste ≥3:1 vs #1A1C18)
val HeroCardDark = Color(0xFF123A1E)
val TextoDarkPrimario = Color(0xFFF5F7F2)
val TextoDarkSecundario = Color(0xFFA8ADA6)
val BordaDark = Color(0x24F5F7F2)
// Borda lime ~50% para o card herói dark
val HeroCardBordaDark = Color(0x809FE870)
val ReceitaDark = Color(0xFF7BE495)
val DespesaDark = Color(0xFFF5F7F2)
val VencidoDark = Color(0xFFFEAFA8)
val VencidoFundoDark = Color(0xFF3B1512)
val AvisoDark = Color(0xFFF9D48C)
val AvisoFundoDark = Color(0xFF3A2A0C)

// ── Badge neutra (status padrão: Pendente, Pago, Aguardando, Recebido) ───────
// Light
val BadgeNeutra = Color(0xFF454745)       // TextoSecundario
val BadgeNeutraFundo = Color(0xFFEFF3EC)  // SuperficieClaraAlt
// Dark
val BadgeNeutraDark = Color(0xFFA8ADA6)   // TextoDarkSecundario
val BadgeNeutraFundoDark = Color(0xFF32362C)

/**
 * Converte o hex que o backend manda (`"#22C55E"`, `#RRGGBB`) em [Color].
 *
 * Não existe `Color(String)` no Compose — só `Color(int)`, `Color(long)` e
 * `Color(float, float, float, float)`. Passar a string direto não compila, e
 * trocar por `toInt()` sem o alpha coloca a cor em transparente: `Color(0x22C55E)`
 * interpreta como ARGB, não como RGB, e o resultado é quase invisível.
 *
 * Devolve `null` em hexadecimal inválido em vez de lançar: a cor da categoria é
 * decoração, e uma categoria com cor malformada tem que renderizar na cor
 * padrão, não derrubar a tela inteira.
 */
fun corDeHex(hex: String?): Color? {
    val limpo = hex?.trim()?.removePrefix("#") ?: return null
    if (limpo.length != 6 && limpo.length != 8) return null
    val valorLong = limpo.toLongOrNull(16) ?: return null
    // 6 dígitos = RRGGBB; compõe um alpha opaco. 8 já vem ARGB do backend.
    val argb = if (limpo.length == 6) 0xFF000000L or valorLong else valorLong
    return Color(argb.toInt())
}
