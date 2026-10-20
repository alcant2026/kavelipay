package com.kavelipay.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kavelipay.app.ui.theme.Spacing

/**
 * Linha de menu de configuração: ícone, rótulo (com subtítulo opcional) e
 * chevron.
 *
 * O chevron é o que diz "isto abre algo" sem precisar de texto. Fica em
 * `components` porque Perfil e Sessões usam a mesma linha — duplicar em cada
 * tela faria as duas divergirem no primeiro ajuste de espaçamento.
 *
 * Cor: o padrão é neutro. Vermelho só entra quando a ação é IRREVERSÍVEL
 * (apagar conta) — encerrar sessão e sair são reversíveis, e vermelho em tudo
 * tira o peso do vermelho que importa.
 */
@Composable
fun KpMenuRow(
    icone: ImageVector,
    rotulo: String,
    onClick: () -> Unit,
    subtitulo: String? = null,
    corTexto: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(
            imageVector = icone,
            contentDescription = null,
            tint = corTexto,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = rotulo,
                style = MaterialTheme.typography.bodyLarge,
                color = corTexto,
            )
            subtitulo?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(14.dp),
        )
    }
}
