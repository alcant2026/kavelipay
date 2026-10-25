package com.kavelipay.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Estrelas de avaliação; `nota` é quantas estão preenchidas (0..5). */
@Composable
fun Estrelas(nota: Int, modifier: Modifier = Modifier) {
    Row(modifier) {
        (1..5).forEach { n ->
            Icon(
                imageVector = if (n <= nota) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = null,
                tint = if (n <= nota) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
