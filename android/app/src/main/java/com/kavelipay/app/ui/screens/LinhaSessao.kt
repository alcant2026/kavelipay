package com.kavelipay.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kavelipay.app.data.Sessao
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.formatarData
import com.kavelipay.app.ui.components.formatarDataHora
import com.kavelipay.app.ui.theme.Spacing

/**
 * Uma sessão aberta.
 *
 * SEM o rótulo "Sessão" repetido em cada linha: numa lista de três, dizer
 * "Sessão" três vezes não informa nada — o que distingue uma da outra são as
 * datas. O backend devolve só `created_at` e `expires_at`: não há aparelho,
 * não há "último uso" e não há como saber qual é a atual. Então a linha não
 * inventa nenhum dos três (marcar a errada como "esta" esconderia o botão da
 * sessão certa).
 *
 * Cada informação na sua linha e `maxLines = 1`: com os dois textos juntos, a
 * coluna perdia largura para o botão e o Compose quebrava palavra letra por
 * letra em 320dp.
 */
@Composable
internal fun LinhaSessao(sessao: Sessao, onRevogar: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Aberta em ${formatarDataHora(sessao.createdAt)}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                text = "Vence em ${formatarData(sessao.expiresAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        KpBotaoSecundario(
            texto = "Encerrar",
            onClick = onRevogar,
            // Cor NEUTRA, não vermelha: encerrar sessão é proteção, não perda —
            // e quatro botões vermelhos numa tela tiram o peso do vermelho que
            // importa ("Eliminar minha conta").
            // Piso de largura: sem ele o botão encolhe até "Encer…" quando a
            // data ocupa a linha inteira.
            modifier = Modifier.widthIn(min = 88.dp),
        )
    }
}
