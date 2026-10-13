package com.kavelipay.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kavelipay.app.data.Categoria
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpEtiqueta
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloSecao
import com.kavelipay.app.ui.theme.corDeHex

/** Título de seção ("Receitas"/"Despesas") dentro da lista de categorias. */
private data class TituloDeGrupo(val kind: String) {
    val texto: String get() = if (kind == "INCOME") "Receitas" else "Despesas"
}

/** Uma categoriarenderizável dentro da lista. */
private data class CategoriaDeLista(val categoria: Categoria)

/** Lista de categorias (padrão + próprias). */
@Composable
fun KpCategoriasScreen(vm: AppViewModel, aoVoltar: () -> Unit, aoNovaCategoria: () -> Unit = {}) {
    val estado by vm.estado.collectAsState()

    LaunchedEffect(Unit) {
        vm.carregarCategorias()
    }

    Scaffold(topBar = { CabecalhoComVoltar("Categorias", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (estado.enviando) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    KpBotaoPrimario(
                        texto = "Nova categoria",
                        onClick = aoNovaCategoria,
                        icone = Icons.Default.Add,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            val categorias = estado.categorias
            if (categorias.isEmpty()) {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        KpVazio(
                            icone = Icons.Default.Edit,
                            titulo = "Nenhuma categoria",
                            descricao = "Crie categorias para classificar lançamentos.",
                        )
                    }
                }
            } else {
                val porKind = categorias.groupBy { it.kind }
                // `items(count)` do LazyListScope, nunca `forEach` com `item { }`
                // dentro: fora do escopo do LazyListScope o `item` some e o
                // Kotlin não consegue inferir os tipos dos parâmetros.
                val grupos = porKind.entries.toList()
                // Duas emissions por grupo, sem `item { }` dentro de `items { }`:
                // `item` é do LazyListScope e não é resolvido pelo receiver
                // implícito do `LazyItemScope` de `items`. Achatar a lista é
                // mais simples do que fighting com o escopo.
                val linhas = grupos.flatMap { (kind, cats) ->
                    buildList {
                        add(TituloDeGrupo(kind))
                        cats.forEach { add(CategoriaDeLista(it)) }
                    }
                }
                items(linhas.size) { i ->
                    when (val linha = linhas[i]) {
                        is TituloDeGrupo -> Text(linha.texto, style = TituloSecao)
                        is CategoriaDeLista -> {
                            KpCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(horizontal = Spacing.md)) {
                                    LinhaCategoria(linha.categoria)
                                    if (i < linhas.lastIndex) HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinhaCategoria(cat: Categoria) {
    val cor = corDeHex(cat.cor) ?: MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).background(cor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                imageVector = if (cat.kind == "INCOME") Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                contentDescription = null,
                tint = cor,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(cat.nome, style = MaterialTheme.typography.bodyLarge)
            Text(
                buildString {
                    append(if (cat.kind == "INCOME") "Receita" else "Despesa")
                    append(if (cat.padrao) " · padrão" else "")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Só o que o usuário criou é "personalizada": as 14 semeadas também
        // têm cor, e rotular todas assim não distingue nada.
        if (!cat.padrao) {
            KpEtiqueta("Personalizada", cor, fundo = cor.copy(alpha = 0.12f))
        }
    }
}

/** Tela para criar categoria — ver `NovaCategoriaScreen.kt`. */
@Deprecated("Substituída por NovaCategoriaScreen.kt", ReplaceWith("KpNovaCategoriaScreen"))
@Composable
fun KpNovaCategoriaScreenPlaceholder() {
    KpVazio(icone = Icons.Default.Add, titulo = "Em construção", descricao = "Tela de nova categoria")
}