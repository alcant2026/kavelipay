package com.kavelipay.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kavelipay.app.data.OfertaParceiro
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpImagemRemota
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.theme.Pilula
import com.kavelipay.app.ui.theme.Spacing

/**
 * Vitrine de lojas parceiras (indicação de afiliado) em grade de dois.
 *
 * A fronteira é a mesma de sempre: o KaveliPay não vende, não cobra e não
 * entrega. O card é propaganda verdadeira — mostra o que a loja tem, sem
 * preço inventado e sem prometer desconto. Quem compra, compra na loja.
 *
 * Por que grade e não lista: a lista era uma linha de texto por oferta — três
 * palavras e uma foto de 64dp. Vitrine se lê pela imagem, e duas colunas
 * mostram o dobro no mesmo dedo de rolagem sem esconder o título.
 *
 * Por que não tem preço: ver o comentário de `OfertaParceiro` e o cabeçalho de
 * `migrations/0011`. Resumo: preço teria que ser digitado à mão e envelheceria
 * sem avisar; o card diz "ver preço na loja" e a verdade fica no destino.
 */
@Composable
fun KpParceirosScreen(
    vm: AppViewModel,
    aoVoltar: () -> Unit,
    aoAbrir: (OfertaParceiro) -> Unit,
) {
    val estado by vm.estado.collectAsState()
    val ofertas = estado.parceiros

    // A Home já carrega a primeira página. Isto só cobre o caso de ela ter
    // falhado (ou de a vitrine ter sido aberta antes da Home terminar): sem a
    // chamada, a tela ficaria "vazia" para sempre esperando um toque.
    LaunchedEffect(Unit) {
        if (ofertas.isEmpty()) vm.carregarParceiros()
    }

    Scaffold(topBar = { CabecalhoComVoltar("Parceiros", aoVoltar) }) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md,
                end = Spacing.md,
                bottom = Spacing.xl,
            ),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            larguraTotal {
                AvisoIndicacao()
            }

            if (ofertas.isEmpty()) {
                larguraTotal {
                    if (estado.carregandoMaisParceiros) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(160.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        KpVazio(
                            icone = Icons.Default.Storefront,
                            titulo = "Nenhuma indicação agora",
                            // Sem "não há nada" taxativo: a lista também fica
                            // vazia quando a chamada falha, e afirmar ausência de
                            // oferta nesse caso seria mentira. O botão resolve os
                            // dois casos sem precisar saber qual foi.
                            descricao = "Quando houver oferta de loja parceira, ela aparece aqui.",
                            modifier = Modifier.padding(top = Spacing.lg),
                            acao = {
                                KpBotaoSecundario(
                                    texto = "Tentar de novo",
                                    onClick = { vm.carregarParceiros() },
                                )
                            },
                        )
                    }
                }
            } else {
                larguraTotal {
                    // O servidor entrega do mais novo para o mais antigo. Dizer
                    // isso transforma a ordem em informação, em vez de deixar a
                    // pessoa achando que a lista é aleatória.
                    Text(
                        text = "Mais recentes primeiro",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }

                items(ofertas, key = { it.id }) { oferta ->
                    CartaoOferta(oferta) { aoAbrir(oferta) }
                }

                larguraTotal {
                    RodapeVitrine(
                        carregadas = ofertas.size,
                        total = estado.parceirosTotal,
                        carregando = estado.carregandoMaisParceiros,
                        aoCarregarMais = { vm.carregarMaisParceiros() },
                    )
                }
            }

            larguraTotal {
                Text(
                    text = "Podemos receber comissão por essas indicações. " +
                        "O preço é sempre o da loja, na página dela.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
        }
    }
}

/** Item que ocupa as duas colunas (aviso, contagem, rodapé). */
private fun androidx.compose.foundation.lazy.grid.LazyGridScope.larguraTotal(
    conteudo: @Composable () -> Unit,
) {
    item(span = { GridItemSpan(maxLineSpan) }) { conteudo() }
}

/**
 * Explica a fronteira ANTES do primeiro card, não em letra miúda no rodapé.
 *
 * Quem chega aqui vindo de "Parceiro" pode achar que é uma loja do app. Não é:
 * dizer isso no topo evita a leitura errada de que comprar aqui move o saldo
 * ou que o produto é entregue pelo KaveliPay.
 */
@Composable
private fun AvisoIndicacao() {
    KpCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md)) {
            Text(
                text = "Publicidade: indicação, não venda",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "A compra acontece no site da loja, aqui dentro do app. " +
                    "O KaveliPay não processa o pagamento, não entrega o produto " +
                    "e não move o seu saldo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

@Composable
private fun CartaoOferta(oferta: OfertaParceiro, aoAbrir: () -> Unit) {
    KpCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // O clique fica DENTRO do card (e do clip dele), senão o ripple
                // sai quadrado por cima dos cantos arredondados.
                .clickable(onClick = aoAbrir, onClickLabel = "Abrir oferta na loja")
                .padding(bottom = Spacing.sm),
        ) {
            KpImagemRemota(
                url = oferta.imagemUrl,
                // Quadrada e ocupando a coluna inteira: foto de tamanhos
                // diferentes (a loja manda 300x300, 600x600, 1200x900…) vira
                // grade alinhada em vez de escada.
                //
                // Sem descrição: o título está escrito logo abaixo e repetir os
                // dois faz o leitor de tela falar a mesma coisa duas vezes.
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            Column(Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)) {
                Text(
                    text = oferta.loja.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = oferta.titulo,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    // Duas linhas SEMPRE (minLines) para os cards da mesma
                    // fileira terminarem na mesma altura — título de uma linha
                    // ao lado de título de duas deixa a grade torta.
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
                Text(
                    text = "Ver na loja",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }
    }
}

/**
 * Fim da lista e próxima página.
 *
 * "10 de 23" é mais honesto que um botão solto: diz quanto já veio e quanto
 * falta, sem a pessoa precisar rolar de volta para contar.
 */
@Composable
private fun RodapeVitrine(
    carregadas: Int,
    total: Int,
    carregando: Boolean,
    aoCarregarMais: () -> Unit,
) {
    val temMais = total > carregadas
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (temMais) {
                "$carregadas de $total"
            } else {
                "Todas as $total indicações"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (temMais) {
            // Contorno em vez de texto solto: no fim de uma grade o botão é o
            // único alvo da tela, e sem borda ele some no fundo — parece um
            // rodapé, não um controle.
            KpBotaoSecundario(
                texto = if (carregando) "Carregando…" else "Carregar mais",
                onClick = aoCarregarMais,
                habilitado = !carregando,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(Pilula),
                    ),
            )
        }
    }
}
