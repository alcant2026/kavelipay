package com.kavelipay.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kavelipay.app.data.OfertaParceiro
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpImagemRemota
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.theme.Spacing

/**
 * Vitrine de lojas parceiras (indicação de afiliado).
 *
 * A fronteira é a mesma de sempre: o KaveliPay não vende, não cobra e não
 * entrega. O card é propaganda verdadeira — mostra o que a loja tem, sem
 * preço inventado e sem prometer desconto. Quem compra, compra na loja.
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

    Scaffold(topBar = { CabecalhoComVoltar("Parceiros", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md,
                end = Spacing.md,
                bottom = Spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item {
                AvisoIndicacao()
            }

            if (estado.parceiros.isEmpty()) {
                item {
                    KpVazio(
                        icone = Icons.Default.Storefront,
                        titulo = "Nenhuma indicação agora",
                        // Sem "não há nada" taxativo: a lista também fica vazia
                        // quando a chamada falha, e afirmar ausência de oferta
                        // nesse caso seria mentira. O botão resolve os dois
                        // casos sem precisar saber qual foi.
                        descricao = "Quando houver oferta de loja parceira, ela aparece aqui.",
                        modifier = Modifier.padding(top = Spacing.lg),
                        acao = {
                            KpBotaoSecundario(
                                texto = "Tentar de novo",
                                onClick = { vm.carregarTudo() },
                            )
                        },
                    )
                }
            } else {
                items(estado.parceiros, key = { it.id }) { oferta ->
                    CartaoOferta(oferta) { aoAbrir(oferta) }
                }
            }

            item {
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
                text = "Indicação, não venda",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "A compra acontece no site da loja, aqui dentro do app. " +
                    "O KaveliPay não processa o pagamento, não entrega o produto " +
                    "e não move o seu saldo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CartaoOferta(oferta: OfertaParceiro, aoAbrir: () -> Unit) {
    KpCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // O clique fica DENTRO do card (e do clip dele), senão o ripple
                // sai quadrado por cima dos cantos arredondados.
                .clickable(onClick = aoAbrir, onClickLabel = "Abrir oferta na loja")
                .padding(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KpImagemRemota(
                url = oferta.imagemUrl,
                // A foto fica sem descrição: o título está escrito ao lado, e
                // repetir os dois faz o leitor de tela falar a mesma coisa duas
                // vezes.
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = oferta.titulo,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "${oferta.loja} · ver preço na loja",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
