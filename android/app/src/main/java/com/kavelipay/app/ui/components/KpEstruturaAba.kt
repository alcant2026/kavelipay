package com.kavelipay.app.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable

/**
 * Estrutura das telas que são ABA (Início, Contas, Histórico, Cobranças,
 * Perfil): topo próprio + TabBar fixa no rodapé.
 *
 * Por que existe: a TabBar morava só na Home. As outras quatro abas abriam
 * como rota comum, com seta de voltar e sem barra — quem tocava em "Contas"
 * saía da navegação principal e precisava voltar para trocar de aba. Num app
 * de banco a barra é permanente; a pessoa troca de aba, não "entra" e "sai"
 * de uma subpágina.
 *
 * A tela continua dona do seu topBar (a Home tem saudação e tema; as outras
 * têm só o título) porque o conteúdo do topo muda de aba para aba.
 */
@Composable
fun KpEstruturaAba(
    abaAtual: Int,
    aoMudarAba: (Int) -> Unit,
    topBar: @Composable () -> Unit,
    conteudo: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = topBar,
        bottomBar = { KpTabBar(abaAtual = abaAtual, aoClicarAba = aoMudarAba) },
        content = conteudo,
    )
}
