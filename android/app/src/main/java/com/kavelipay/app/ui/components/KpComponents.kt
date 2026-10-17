package com.kavelipay.app.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kavelipay.app.ui.theme.BadgeNeutra
import com.kavelipay.app.ui.theme.BadgeNeutraFundo
import com.kavelipay.app.ui.theme.BadgeNeutraDark
import com.kavelipay.app.ui.theme.BadgeNeutraFundoDark
import com.kavelipay.app.ui.theme.HeroCardBordaDark
import com.kavelipay.app.ui.theme.HeroCardDark
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.LimeMarca
import com.kavelipay.app.ui.theme.Pilula
import com.kavelipay.app.ui.theme.RaioCard
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloCard
import com.kavelipay.app.ui.theme.TituloTela
import com.kavelipay.app.ui.theme.ValorHeroCard
import com.kavelipay.app.ui.theme.VerdeEscuro
import com.kavelipay.app.ui.theme.temaEscuroApp

// ========================================================================
// KpCard — cartão padrão (raio 16dp, borda fina)
// ========================================================================
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

// ========================================================================
// Botões
// ========================================================================
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

// ========================================================================
// Etiquetas / Badges
// ========================================================================
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

/** Badge neutra (status padrão: Pendente, Pago, Aguardando, Recebido). Cor só no crítico. */
@Composable
fun KpBadgeNeutra(
    texto: String,
    modifier: Modifier = Modifier,
) {
    val dark = temaEscuroApp()
    val corTexto = if (dark) BadgeNeutraDark else BadgeNeutra
    val corFundo = if (dark) BadgeNeutraFundoDark else BadgeNeutraFundo
    KpEtiqueta(texto = texto, cor = corTexto, fundo = corFundo, modifier = modifier)
}

/** Badge de status crítico (Vencido) — usa vermelho semântico. */
@Composable
fun KpBadgeCritico(
    texto: String,
    modifier: Modifier = Modifier,
) {
    val sem = KpTheme.semanticas
    KpEtiqueta(texto = texto, cor = sem.vencido, fundo = sem.vencidoFundo, modifier = modifier)
}

// ========================================================================
// Estados
// ========================================================================
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
        Text(titulo, style = TituloCard, textAlign = TextAlign.Center)
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
            style = if (negrito) TituloCard else MaterialTheme.typography.bodyMedium,
            color = corValor,
        )
    }
}

// ========================================================================
// Aviso de saldo demonstrativo
// ========================================================================
@Composable
fun KpAvisoSaldoDemonstrativo(
    modifier: Modifier = Modifier,
    saldoMostrado: String? = null,
) {
    val sem = KpTheme.semanticas
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RaioCard))
            .background(sem.demoFundo)
            .border(1.dp, sem.demoFundo.copy(alpha = 0.6f), RoundedCornerShape(RaioCard))
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

// ========================================================================
// NOVOS COMPONENTES — Padrão Nubank
// ========================================================================

/** Item da fila de ações (Home): ícone + label, pill clicável. */
@Composable
fun KpAcaoItem(
    icone: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(88.dp)
            .clip(RoundedCornerShape(Pilula))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Fila horizontal de ações (Home) — LazyRow com affordance de scroll. */
@Composable
fun KpFilaAcoes(
    acoes: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        contentPadding = PaddingValues(horizontal = Spacing.md),
    ) {
        items(acoes.size) { index ->
            acoes[index]()
        }
    }
}

/** Card do carrossel "Descubra mais". */
@Composable
fun KpCarrosselItem(
    icone: ImageVector,
    titulo: String,
    descricao: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(280.dp)
            .clip(RoundedCornerShape(RaioCard))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(RaioCard))
            .padding(Spacing.md)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(Spacing.md))
            Text(
                titulo,
                style = TituloCard,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                descricao,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Carrossel horizontal "Descubra mais". */
@Composable
fun KpCarrossel(
    itens: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        contentPadding = PaddingValues(horizontal = Spacing.md),
    ) {
        items(itens.size) { index ->
            itens[index]()
        }
    }
}

/** Card de saldo herói (Home) — estilo Nubank. */
@Composable
fun KpHeroCardSaldo(
    saldo: String,
    moeda: String = "BRL",
    demonstrativa: Boolean = true,
    aoVerDerivacao: () -> Unit,
    aoToggleOcultar: () -> Unit,
    oculto: Boolean,
    modifier: Modifier = Modifier,
) {
    val dark = temaEscuroApp()
    val sem = KpTheme.semanticas

    val (fundo, corBorda) = if (dark) {
        HeroCardDark to HeroCardBordaDark
    } else {
        MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.outline
    }

    KpCard(
        modifier = modifier
            .fillMaxWidth()
            .border(if (dark) 1.5.dp else 1.dp, corBorda, RoundedCornerShape(RaioCard)),
        cor = fundo,
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            // Topo: label + toggle ocultar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Saldo disponível",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = aoToggleOcultar) {
                    Icon(
                        imageVector = if (oculto) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (oculto) "Mostrar saldo" else "Ocultar saldo",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            // Valor
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    if (oculto) "••••••" else "R$ ${com.kavelipay.app.ui.components.Money.brl(saldo)}",
                    style = ValorHeroCard,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    moeda,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            Spacer(Modifier.height(Spacing.md))

            // Selo demonstrativo + botão derivacao
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (demonstrativa) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Pilula))
                            .background(sem.demoFundo)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "Demonstrativo",
                            style = MaterialTheme.typography.labelSmall,
                            color = sem.demo,
                        )
                    }
                }

                KpBotaoSecundario(
                    texto = "De onde vem?",
                    onClick = aoVerDerivacao,
                    icone = Icons.Default.ExpandMore,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }
    }
}

/**
 * TopBar padrão Nubank: saudação + ações (tema, notificações).
 *
 * Sem ícone de olho aqui: o de ocultar saldo é do card da wallet (quem tem a
 * wallet é quem olha o saldo) — duplicar o controle em cima só confundia.
 */
@Composable
fun KpTopBar(
    saudacao: String = "Olá",
    aoToggleTema: () -> Unit = {},
    aoVerNotificacoes: () -> Unit = {},
    dark: Boolean = false, // passado de fora, não usa isSystemInDarkTheme() aqui
    naoLidas: Int = 0, // vem do estado compartilhado — o sino sem contador era mentira
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Abaixo da status bar + piso de 40dp: no Waydroid (janela
            // desktop) a tira de controles da janela cobre o topo sem
            // reportar inset nenhum — sem o piso, o clique no centro do
            // ícone de tema não chegava ao app.
            .windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets(top = 40.dp)),
            )
            .height(56.dp)
            .padding(horizontal = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                saudacao,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "KaveliPay",
                style = TituloTela,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            // Tema
            IconButton(onClick = aoToggleTema) {
                Icon(
                    imageVector = if (dark) Icons.Default.Brightness7 else Icons.Default.Brightness4,
                    contentDescription = if (dark) "Tema claro" else "Tema escuro",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
            // Notificações — badge com a contagem de não lidas. Sem o badge o
            // sino era decoração: o número existia no estado e ninguém via.
            IconButton(onClick = aoVerNotificacoes) {
                BadgedBox(
                    badge = {
                        if (naoLidas > 0) {
                            Badge { Text(if (naoLidas > 99) "99+" else naoLidas.toString()) }
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = if (naoLidas > 0) {
                            "Notificações, $naoLidas não lidas"
                        } else {
                            "Notificações"
                        },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

/** TabBar inferior 5 abas (Início, Contas, Histórico, Cobranças, Perfil). */
@Composable
fun KpTabBar(
    abaAtual: Int,
    aoClicarAba: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val abas = listOf(
        TabItem(Icons.Default.Home, "Início"),
        TabItem(Icons.Default.ReceiptLong, "Contas"),
        TabItem(Icons.Default.History, "Histórico"),
        TabItem(Icons.Default.QrCode, "Cobranças"),
        TabItem(Icons.Default.Person, "Perfil"),
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            // Abaixo da barra de navegação (gesto do Android): sem isso as
            // abas ficam atrás da pílula de gesto no celular.
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(64.dp)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        abas.forEachIndexed { index, item ->
            val selecionado = index == abaAtual
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { aoClicarAba(index) }
                    .padding(vertical = Spacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = item.icone,
                    contentDescription = null,
                    tint = if (selecionado) LimeMarca else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (selecionado) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = if (selecionado) LimeMarca else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class TabItem(val icone: ImageVector, val label: String)