package com.kavelipay.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kavelipay.app.data.Sessao
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.formatarData
import com.kavelipay.app.ui.theme.KaveliPayTheme
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.Spacing

/**
 * Linha de sessão.
 *
 * Cada informação ocupa a sua própria linha. Com "Criada … · Vence …" numa
 * linha só, a coluna perdia largura para o botão "Revogar" e o Compose quebrava
 * "Sessão" em "Se / ss / ão" — uma letra por linha. `maxLines = 1` com ellipsis
 * garante que nada suma, mesmo em 320dp.
 */
@Composable
internal fun LinhaSessao(sessao: Sessao, onRevogar: () -> Unit) {
    val sem = KpTheme.semanticas
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }

        Column(Modifier.weight(1f)) {
            Text(
                "Sessão",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // O backend devolve `created_at` e `expires_at`; NÃO devolve
            // `last_used_at` nem qual sessão é a atual. Mostrar "último uso"
            // exigiria inventar dado — e marcar a sessão errada como atual
            // esconderia o botão de revogar da sessão que está em uso.
            Text(
                "Criada: ${formatarData(sessao.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Vence: ${formatarData(sessao.expiresAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        KpBotaoSecundario(
            texto = "Revogar",
            onClick = onRevogar,
            cor = sem.despesa,
            // Sem teto de largura: o `weight(1f)` da coluna é quem distribui o
            // espaço, e um `max` aqui fixava o botão em ~104dp e deixava o texto
            // com tão pouca largura que "Sessão" virava "Ses…".
            modifier = Modifier.widthIn(min = 76.dp),
        )
    }
}

@Preview(name = "Sessão — 360dp", widthDp = 360, showBackground = true)
@Composable
private fun PreviewLinhaSessao360() {
    KaveliPayTheme(escuro = true) {
        Column(Modifier.padding(Spacing.md)) {
            LinhaSessao(
                Sessao(
                    id = "s1",
                    createdAt = "2026-10-06T15:12:38.367193Z",
                    expiresAt = "2026-11-05T15:12:38.367193Z",
                ),
                onRevogar = {},
            )
        }
    }
}

@Preview(name = "Sessão — 320dp", widthDp = 320, showBackground = true)
@Composable
private fun PreviewLinhaSessao320() {
    KaveliPayTheme(escuro = true) {
        Column(Modifier.padding(Spacing.md)) {
            LinhaSessao(
                Sessao(
                    id = "s1",
                    createdAt = "2026-10-06T15:12:38.367193Z",
                    expiresAt = "2026-11-05T15:12:38.367193Z",
                ),
                onRevogar = {},
            )
        }
    }
}