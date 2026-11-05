package com.kavelipay.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
// `navArgument` NÃO vive em `androidx.navigation.compose` — vive em
// `androidx.navigation` (NamedNavArgument.kt). Importar do pacote compose é
// "unresolved reference" e a falha cascateia para `NavType` e `getString`.
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.theme.KaveliPayTheme

private object Rota {
    const val HOME = "home"
    const val DERIVACAO = "derivacao"
    const val NOVA = "nova"
    const val NOVA_DESPESA = "nova_despesa"
    const val NOVA_CONTA = "nova_conta"
    const val ESCANEAR_CONTA = "escanear_conta"
    const val NOTIFICACOES = "notificacoes"
    const val CONTAS = "contas"
    const val RELATORIO = "relatorio"
    const val SPLITS = "splits"
    const val SPLITS_DETALHE = "splits_detalhe"
    const val NOVA_SPLIT = "nova_split"
    const val CHARGES = "charges"
    const val CHARGES_QR = "charges_qr"
    const val NOVA_CHARGE = "nova_charge"
    const val LER_COBRANCA = "ler_cobranca"
    const val COBRANCA_PUBLICA = "cobranca_publica"
    const val CATEGORIAS = "categorias"
    const val NOVA_CATEGORIA = "nova_categoria"
    const val HISTORICO = "historico"
    const val PERFIL = "perfil"
    const val LGPD = "lgpd"
    const val SESSOES = "sessoes"
    const val PLANO = "plano"
}

/**
 * Raiz de navegação.
 *
 * Estado de autenticação é um ternário de duas telas, não uma rota de login:
 * com duas possibilidades (entrado ou não), um `when` é mais barato e mais
 * claro que um grafo de navegação com guarda.
 */
@Composable
fun KpRaiz(
    vm: AppViewModel,
    temaEscuro: Boolean,
    aoAlternarTema: () -> Unit,
    linkCobranca: String? = null,
    aoFecharLink: () -> Unit = {},
) {
    val estado by vm.estado.collectAsState()

    // Deep link de cobrança vem ANTES do gate de login de propósito: quem
    // recebe o link pode não ter conta no app. A tela é só leitura (rota
    // pública, token no link) e não toca carteira de ninguém.
    if (linkCobranca != null) {
        KpCobrancaPublicaScreen(token = linkCobranca, aoVoltar = aoFecharLink)
        return
    }

    if (!estado.autenticado) {
        KpLoginScreen(vm, aoEntrar = {})
        return
    }

    // Categorias só são usadas no formulário de novo lançamento; buscar uma vez
    // no login em vez de a cada abertura da tela.
    LaunchedEffect(Unit) { vm.carregarCategorias() }

    val nav = rememberNavController()

    // As cinco abas dividem UMA pilha: voltar de qualquer aba leva à Home (e,
    // na Home, sai do app) em vez de percorrer o histórico de abas — que é o
    // que acontece quando cada aba é um `navigate` comum. `saveState`/
    // `restoreState` preservam rolagem e conteúdo de cada aba na troca.
    val rotasDasAbas = listOf(Rota.HOME, Rota.CONTAS, Rota.HISTORICO, Rota.CHARGES, Rota.PERFIL)
    val irParaAba: (Int) -> Unit = { indice ->
        rotasDasAbas.getOrNull(indice)?.let { destino ->
            nav.navigate(destino) {
                popUpTo(Rota.HOME) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // Movimento discreto: a tela entra deslizando ~8% da largura com fade. Sem
    // isso a troca de aba era um corte seco, que é o que mais faz um app
    // parecer "formulário" em vez de app de banco. Curto de propósito (200ms):
    // animação longa em uso repetido (trocar de aba dez vezes) cansa.
    NavHost(
        navController = nav,
        startDestination = Rota.HOME,
        enterTransition = {
            fadeIn(tween(200)) + slideInHorizontally(tween(200)) { it / 12 }
        },
        exitTransition = { fadeOut(tween(140)) },
        popEnterTransition = {
            fadeIn(tween(200)) + slideInHorizontally(tween(200)) { -it / 12 }
        },
        popExitTransition = { fadeOut(tween(140)) },
    ) {
        composable(Rota.HOME) {
            KpHomeScreen(
                vm = vm,
                aoVerDerivacao = { nav.navigate(Rota.DERIVACAO) },
                aoNovaTransacao = { nav.navigate(Rota.NOVA) },
                aoVerContas = { irParaAba(1) },
                aoVerRelatorio = { nav.navigate(Rota.RELATORIO) },
                aoVerCobrancas = { irParaAba(3) },
                aoVerDivisoes = { nav.navigate(Rota.SPLITS) },
                aoVerHistorico = { irParaAba(2) },
                aoVerCategorias = { nav.navigate(Rota.CATEGORIAS) },
                aoVerPerfil = { irParaAba(4) },
                aoVerNotificacoes = { nav.navigate(Rota.NOTIFICACOES) },
                aoNovaDespesa = { nav.navigate(Rota.NOVA_DESPESA) },
                aoNovaConta = { nav.navigate(Rota.NOVA_CONTA) },
                aoNovaCharge = { nav.navigate(Rota.NOVA_CHARGE) },
                temaEscuro = temaEscuro,
                aoAlternarTema = aoAlternarTema,
                abaAtual = 0,
                aoMudarAba = irParaAba,
            )
        }
        composable(Rota.NOTIFICACOES) {
            KpNotificacoesScreen(vm = vm, aoVoltar = { nav.popBackStack() })
        }
        composable(Rota.NOVA_DESPESA) {
            // Mesma tela do "Entrada", mas a despesa é uma tela própria: título
            // "Nova despesa", categorias de saída, botão "Registrar despesa".
            KpNovaTransacaoScreen(
                vm = vm,
                aoVoltar = { nav.popBackStack() },
                aoSalvar = { nav.popBackStack() },
                categorias = estado.categorias,
                entrada = false,
            )
        }
        composable(Rota.DERIVACAO) {
            KpDerivacaoScreen(vm, aoVoltar = { nav.popBackStack() })
        }
        composable(Rota.NOVA) {
            KpNovaTransacaoScreen(
                vm = vm,
                aoVoltar = { nav.popBackStack() },
                aoSalvar = { nav.popBackStack() },
                categorias = estado.categorias,
                entrada = true,
            )
        }
        composable(Rota.CONTAS) {
            KpContasScreen(
                vm = vm,
                aoMudarAba = irParaAba,
                aoNovaConta = { nav.navigate(Rota.NOVA_CONTA) },
            )
        }
        composable(Rota.NOVA_CONTA) {
            KpNovaContaScreen(
                vm = vm,
                aoVoltar = { nav.popBackStack() },
                aoSalvar = { nav.popBackStack() },
                aoEscanear = { nav.navigate(Rota.ESCANEAR_CONTA) },
            )
        }
        composable(Rota.ESCANEAR_CONTA) {
            KpLeitorCodigoBarrasScreen(
                contexto = ContextoLeitura.CONTA,
                aoVoltar = { nav.popBackStack() },
                aoLer = { linhaDigitavel ->
                    // Só chega aqui o que a tela reconheceu como boleto (um QR
                    // de cobrança é barrado antes, com o aviso de onde ele
                    // vive). Vira texto na tela de Nova conta e a rota se
                    // descarta em seguida.
                    vm.registrarLinhaLida(linhaDigitavel)
                    nav.popBackStack()
                },
            )
        }
        composable(Rota.RELATORIO) {
            KpRelatorioScreen(
                vm,
                aoVoltar = { nav.popBackStack() },
                aoAbrirPlano = { nav.navigate(Rota.PLANO) },
            )
        }
        composable(Rota.SPLITS) {
            KpSplitsScreen(
                vm = vm,
                aoVoltar = { nav.popBackStack() },
                aoNovaSplit = { nav.navigate(Rota.NOVA_SPLIT) },
                aoAbrirDetalhe = { id -> nav.navigate("${Rota.SPLITS_DETALHE}/$id") },
            )
        }
        composable(Rota.NOVA_SPLIT) {
            KpNovaSplitScreen(
                vm = vm,
                aoVoltar = { nav.popBackStack() },
                aoSalvar = { nav.popBackStack() },
            )
        }
        composable(route = "${Rota.SPLITS_DETALHE}/{splitId}", arguments = listOf(navArgument("splitId") { type = NavType.StringType })) { backStackEntry ->
            // `NavBackStackEntry` não tem `getString(...)`: o argumento vive no
            // Bundle `arguments`. A assinatura do nav é `.../{splitId}` e o nome
            // da chave é o mesmo do placeholder.
            val splitId = backStackEntry.arguments?.getString("splitId").orEmpty()
            KpSplitsDetalheScreen(vm, splitId = splitId, aoVoltar = { nav.popBackStack() })
        }
        composable(Rota.CHARGES) {
            KpChargesScreen(
                vm = vm,
                aoMudarAba = irParaAba,
                aoNovaCharge = { nav.navigate(Rota.NOVA_CHARGE) },
                aoAbrirQr = { id -> nav.navigate("${Rota.CHARGES_QR}/$id") },
                aoEscanearCobranca = { nav.navigate(Rota.LER_COBRANCA) },
            )
        }
        composable(Rota.LER_COBRANCA) {
            // Escanear o QR de OUTRA pessoa é o caminho que dispensa domínio/
            // link clicável: o QR aparece na tela de quem cobra e é lido aqui.
            // Um código de barras de boleto lido por engano não abre nada —
            // o leitor avisa que ele é cadastrado em Nova conta.
            KpLeitorCodigoBarrasScreen(
                contexto = ContextoLeitura.COBRANCA,
                aoVoltar = { nav.popBackStack() },
                aoLer = { token -> nav.navigate("${Rota.COBRANCA_PUBLICA}/$token") },
            )
        }
        composable(
            route = "${Rota.COBRANCA_PUBLICA}/{token}",
            arguments = listOf(navArgument("token") { type = NavType.StringType }),
        ) { backStackEntry ->
            val token = backStackEntry.arguments?.getString("token").orEmpty()
            KpCobrancaPublicaScreen(token = token, aoVoltar = { nav.popBackStack() })
        }
        composable(Rota.NOVA_CHARGE) {
            KpNovaChargeScreen(
                vm = vm,
                aoVoltar = { nav.popBackStack() },
                aoSalvar = { id ->
                    nav.popBackStack()
                    nav.navigate("${Rota.CHARGES_QR}/$id")
                },
            )
        }
        composable(route = "${Rota.CHARGES_QR}/{chargeId}", arguments = listOf(navArgument("chargeId") { type = NavType.StringType })) { backStackEntry ->
            val chargeId = backStackEntry.arguments?.getString("chargeId").orEmpty()
            KpChargeQrScreen(vm, chargeId = chargeId, aoVoltar = { nav.popBackStack() })
        }
        composable(Rota.CATEGORIAS) {
            KpCategoriasScreen(
                vm = vm,
                aoVoltar = { nav.popBackStack() },
                aoNovaCategoria = { nav.navigate(Rota.NOVA_CATEGORIA) },
            )
        }
        composable(Rota.NOVA_CATEGORIA) {
            KpNovaCategoriaScreen(
                vm = vm,
                aoVoltar = { nav.popBackStack() },
                aoSalvar = { nav.popBackStack() },
            )
        }
        composable(Rota.HISTORICO) {
            KpHistoricoScreen(vm, aoMudarAba = irParaAba)
        }
        composable(Rota.PERFIL) {
            KpPerfilScreen(
                vm = vm,
                aoMudarAba = irParaAba,
                aoAbrirLgpd = { nav.navigate(Rota.LGPD) },
                aoAbrirSessoes = { nav.navigate(Rota.SESSOES) },
                aoAbrirPlano = { nav.navigate(Rota.PLANO) },
                aoSair = { vm.sair() },
            )
        }
        composable(Rota.SESSOES) {
            KpSessoesScreen(vm, aoVoltar = { nav.popBackStack() })
        }
        composable(Rota.LGPD) {
            KpLgpdScreen(vm, aoVoltar = { nav.popBackStack() })
        }
        composable(Rota.PLANO) {
            KpPlanoScreen(vm, aoVoltar = { nav.popBackStack() })
        }

    }

    // A conexão da conta do Mercado Pago é uma só, então o WebView mora na raiz:
    // cobranças, divisões e perfil pedem daqui e todos abrem o mesmo diálogo.
    KpDialogoConexaoMp(vm)
}

/**
 * Manda a autorização do Mercado Pago para o navegador do sistema.
 *
 * Não pode ser WebView: a página deles é um interstitial de anti-fraude que
 * fica girando para sempre quando o user-agent diz que é WebView. No navegador
 * a pessoa já está (ou faz) o login, e o callback do backend mostra a
 * confirmação — ao voltar para o app, o perfil recarrega sozinho.
 */
@Composable
private fun KpDialogoConexaoMp(vm: AppViewModel) {
    val estado by vm.estado.collectAsState()
    val url = estado.mpUrl ?: return
    val contexto = LocalContext.current

    LaunchedEffect(url) {
        val abrir = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (runCatching { contexto.startActivity(abrir) }.isFailure) {
            Toast.makeText(contexto, "Nenhum navegador instalado.", Toast.LENGTH_LONG).show()
        }
        vm.fecharConexaoMp()
    }
}
