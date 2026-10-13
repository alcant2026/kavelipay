package com.kavelipay.app.ui.screens

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
    const val CONTAS = "contas"
    const val RELATORIO = "relatorio"
    const val SPLITS = "splits"
    const val SPLITS_DETALHE = "splits_detalhe"
    const val NOVA_SPLIT = "nova_split"
    const val CHARGES = "charges"
    const val CHARGES_QR = "charges_qr"
    const val NOVA_CHARGE = "nova_charge"
    const val CATEGORIAS = "categorias"
    const val NOVA_CATEGORIA = "nova_categoria"
    const val INVESTIMENTOS = "investimentos"
    const val NOVO_ATIVO = "novo_ativo"
    const val ATIVO = "ativo"
    const val MOVIMENTO_INVESTIMENTO = "movimento_investimento"
    const val PRECO_ATUAL = "preco_atual"
    const val HISTORICO = "historico"
    const val PERFIL = "perfil"
    const val LGPD = "lgpd"
}

/**
 * Raiz de navegação.
 *
 * Estado de autenticação é um ternário de duas telas, não uma rota de login:
 * com duas possibilidades (entrado ou não), um `when` é mais barato e mais
 * claro que um grafo de navegação com guarda.
 */
@Composable
fun KpRaiz(vm: AppViewModel) {
    val estado by vm.estado.collectAsState()

    if (!estado.autenticado) {
        KpLoginScreen(vm, aoEntrar = {})
        return
    }

    // Categorias só são usadas no formulário de novo lançamento; buscar uma vez
    // no login em vez de a cada abertura da tela.
    LaunchedEffect(Unit) { vm.carregarCategorias() }

    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Rota.HOME) {
        composable(Rota.HOME) {
            KpHomeScreen(
                vm = vm,
                aoVerDerivacao = { nav.navigate(Rota.DERIVACAO) },
                aoNovaTransacao = { nav.navigate(Rota.NOVA) },
                aoVerContas = { nav.navigate(Rota.CONTAS) },
                aoVerRelatorio = { nav.navigate(Rota.RELATORIO) },
                aoVerCobrancas = { nav.navigate(Rota.CHARGES) },
                aoVerDivisoes = { nav.navigate(Rota.SPLITS) },
                aoVerHistorico = { nav.navigate(Rota.HISTORICO) },
                aoVerCategorias = { nav.navigate(Rota.CATEGORIAS) },
                aoVerPerfil = { nav.navigate(Rota.PERFIL) },
                aoVerInvestimentos = { nav.navigate(Rota.INVESTIMENTOS) },
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
            )
        }
        composable(Rota.CONTAS) {
            KpContasScreen(vm, aoVoltar = { nav.popBackStack() })
        }
        composable(Rota.RELATORIO) {
            KpRelatorioScreen(vm, aoVoltar = { nav.popBackStack() })
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
                aoVoltar = { nav.popBackStack() },
                aoNovaCharge = { nav.navigate(Rota.NOVA_CHARGE) },
                aoAbrirQr = { id -> nav.navigate("${Rota.CHARGES_QR}/$id") },
            )
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
            KpHistoricoScreen(vm, aoVoltar = { nav.popBackStack() })
        }
        composable(Rota.PERFIL) {
            KpPerfilScreen(
                vm = vm,
                aoVoltar = { nav.popBackStack() },
                aoAbrirLgpd = { nav.navigate(Rota.LGPD) },
                aoSair = { vm.sair() },
            )
        }
        composable(Rota.LGPD) {
            KpLgpdScreen(vm, aoVoltar = { nav.popBackStack() })
        }

        // Investimentos
        composable(Rota.INVESTIMENTOS) {
            KpInvestimentosScreen(
                vm = vm,
                aoVoltar = { nav.popBackStack() },
                aoNovoAtivo = { nav.navigate(Rota.NOVO_ATIVO) },
                aoAbrirAtivo = { id -> nav.navigate("${Rota.ATIVO}/$id") },
            )
        }
        composable(Rota.NOVO_ATIVO) {
            KpNovoAtivoScreen(
                vm = vm,
                aoVoltar = { nav.popBackStack() },
                aoSalvar = { nav.popBackStack() },
            )
        }
        composable(
            route = "${Rota.ATIVO}/{ativoId}",
            arguments = listOf(navArgument("ativoId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val ativoId = backStackEntry.arguments?.getString("ativoId").orEmpty()
            KpAtivoScreen(
                vm = vm,
                ativoId = ativoId,
                aoVoltar = { nav.popBackStack() },
                aoRegistrarMovimento = { nav.navigate("${Rota.MOVIMENTO_INVESTIMENTO}/$ativoId") },
                aoEditarPreco = { nav.navigate("${Rota.PRECO_ATUAL}/$ativoId") },
            )
        }
        composable(
            route = "${Rota.PRECO_ATUAL}/{ativoId}",
            arguments = listOf(navArgument("ativoId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val ativoId = backStackEntry.arguments?.getString("ativoId").orEmpty()
            KpPrecoAtualScreen(
                vm = vm,
                ativoId = ativoId,
                aoVoltar = { nav.popBackStack() },
                aoSalvar = { nav.popBackStack() },
            )
        }
        composable(
            route = "${Rota.MOVIMENTO_INVESTIMENTO}/{ativoId}",
            arguments = listOf(navArgument("ativoId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val ativoId = backStackEntry.arguments?.getString("ativoId").orEmpty()
            KpMovimentoInvestimentoScreen(
                vm = vm,
                ativoId = ativoId,
                aoVoltar = { nav.popBackStack() },
                aoSalvar = { nav.popBackStack() },
            )
        }
    }
}
