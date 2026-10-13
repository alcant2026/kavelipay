package com.kavelipay.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.Pilula
import com.kavelipay.app.ui.theme.RaioCard
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloCartao

/**
 * Cartão com borda fina e zero sombra — a Wise tira profundidade do brilho da
 * cor, não de sombra. `Box` com `border` em vez de `Surface(tonalElevation)`
 * porque a tonal elevation do Material grey fica cinza e briga com o tom verde.
 */
@Composable
fun KpCard(
    modifier: Modifier = Modifier,
    cor: Color = MaterialTheme.colorScheme.surface,
    conteudo: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(RaioCard))
            .background(cor)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(RaioCard)),
    ) {
        conteudo()
    }
}

/**
 * Botão primário: pílula lime com texto verde-escuro, como a Wise.
 *
 * `cor` existe para a ação destrutiva (`/lgpd/account`): um botão primário
 * que elimina conta tem que ser vermelho, e trocar o `primary` do tema inteiro
 * por causa disso mudaria a cor de todos os outros CTAs da tela.
 */
@Composable
fun KpBotaoPrimario(
    texto: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true,
    carregando: Boolean = false,
    icone: ImageVector? = null,
    cor: Color = MaterialTheme.colorScheme.primary,
) {
    Button(
        onClick = onClick,
        enabled = habilitado && !carregando,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(Pilula),
        contentPadding = PaddingValues(horizontal = Spacing.lg),
        colors = ButtonDefaults.buttonColors(
            containerColor = cor,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        if (carregando) {
            Text(texto, style = MaterialTheme.typography.bodyLarge)
        } else {
            icone?.let {
                Icon(it, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(texto, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Botão secundário: fundo verde translúcido, como a Wise. */
@Composable
fun KpBotaoSecundario(
    texto: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icone: ImageVector? = null,
    cor: Color = MaterialTheme.colorScheme.onSurface,
    habilitado: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = habilitado,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(Pilula),
        contentPadding = PaddingValues(horizontal = Spacing.md),
        colors = ButtonDefaults.textButtonColors(
            contentColor = cor,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        icone?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(texto, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Etiqueta pequena e colorida — categoria, status, tipo.
 *
 * Recebe a cor como cor de TEXTO e usa um fundo derivado com alpha baixo, em
 * vez de um segundo parâmetro de cor: quem chama informa a intenção
 * ("receita", "vencido"), não a paleta.
 */
@Composable
fun KpEtiqueta(
    texto: String,
    cor: Color,
    modifier: Modifier = Modifier,
    fundo: Color = cor.copy(alpha = 0.12f),
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Pilula))
            .background(fundo)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            texto,
            style = MaterialTheme.typography.labelSmall,
            color = cor,
        )
    }
}

/** Estado vazio: ícone, título, explicação. Nunca só texto solto. */
@Composable
fun KpVazio(
    icone: ImageVector,
    titulo: String,
    descricao: String,
    modifier: Modifier = Modifier,
    acao: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(Spacing.md))
        Text(titulo, style = TituloCartao, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            descricao,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        acao?.let {
            Spacer(Modifier.height(Spacing.md))
            it()
        }
    }
}

/** Linha rótulo/valor, usada nos resumos. */
@Composable
fun KpLinhaResumo(
    rotulo: String,
    valor: String,
    modifier: Modifier = Modifier,
    corValor: Color = MaterialTheme.colorScheme.onSurface,
    negrito: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            rotulo,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            valor,
            style = if (negrito) TituloCartao else MaterialTheme.typography.bodyMedium,
            color = corValor,
        )
    }
}

/**
 * Aviso persistente de "saldo demonstrativo".
 *
 * Este é o componente mais importante do app em termos de honestidade. A spec
 * exige que o backend marque a carteira como demonstrativa, e o app tem que
 * deixar isso visível — não num rodapé. Não é um toast e não tem X: some
 * quando o saldo deixa de ser demonstrativo, não quando o usuário descarta.
 */
@Composable
fun KpAvisoSaldoDemonstrativo(
    modifier: Modifier = Modifier,
    saldoMostrado: String? = null,
) {
    val sem = KpTheme.semanticas
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(com.kavelipay.app.ui.theme.RaioCard))
            .background(sem.demoFundo)
            .border(1.dp, sem.demoFundo.copy(alpha = 0.6f), RoundedCornerShape(com.kavelipay.app.ui.theme.RaioCard))
            .padding(Spacing.md),
    ) {
        Text(
            "Saldo demonstrativo",
            style = MaterialTheme.typography.labelSmall,
            color = sem.demo,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            buildString {
                append("Este valor não é dinheiro real. O KaveliPay organiza e mostra ")
                append("seus registros, mas não custodia nem movimenta dinheiro.")
                if (saldoMostrado != null) append("\nSaldo de hoje: R$ $saldoMostrado")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
