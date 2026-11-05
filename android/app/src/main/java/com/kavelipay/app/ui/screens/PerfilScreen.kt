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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpEstruturaAba
import com.kavelipay.app.ui.components.KpMenuRow
import com.kavelipay.app.ui.components.formatarDataCompleta
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloSecao

/**
 * Perfil — uma lista de decisões, não um menu de tudo.
 *
 * O desenho anterior tinha quatro problemas que se somavam na cabeça de quem
 * abria a tela:
 *
 *  1. Três saídas com o mesmo ícone de porta e a mesma cor, no mesmo cartão:
 *     "Sair de todas as outras sessões", "Sair da conta" e o "Revogar" de cada
 *     sessão. Ninguém distingue isso de relance.
 *  2. "Sair de todas as outras sessões" era MENTIRA: o backend
 *     (`revoke_all_sessions`) revoga todas, inclusive a atual. O rótulo
 *     prometia manter este aparelho conectado e não mantinha.
 *  3. A ação de encerrar sessões aparecia ANTES da lista de sessões — pedia
 *     para revogar o que a pessoa ainda não tinha visto.
 *  4. "Exportar dados (JSON)" e "Exportar CSV" ficavam no mesmo cartão de
 *     "Eliminar minha conta", os dois com jargão de programador.
 *
 * Agora a ordem é a de quem usa: identidade → segurança (senha, então as
 * sessões) → seus dados (baixar, depois apagar) → sair. Cada cartão tem um
 * assunto só, e o vermelho aparece apenas onde a ação é destrutiva.
 */
@Composable
fun KpPerfilScreen(
    vm: AppViewModel,
    aoMudarAba: (Int) -> Unit = {},
    aoAbrirLgpd: () -> Unit = {},
    aoAbrirSessoes: () -> Unit = {},
    aoAbrirPlano: () -> Unit = {},
    aoSair: () -> Unit = {},
) {
    val estado by vm.estado.collectAsState()
    val sem = KpTheme.semanticas

    LaunchedEffect(Unit) {
        vm.carregarPerfilESessoes()
        vm.carregarContaMp()
    }

    var dialogoNome by remember { mutableStateOf(false) }
    var novoNome by remember { mutableStateOf("") }
    var dialogoSenha by remember { mutableStateOf(false) }
    var senhaAtual by remember { mutableStateOf("") }
    var novaSenha by remember { mutableStateOf("") }
    var confirmarNovaSenha by remember { mutableStateOf("") }

    val nome = estado.perfil?.displayName.orEmpty()
    val email = estado.perfil?.email.orEmpty()
    val sessoes = estado.sessoes?.itens.orEmpty()
    val plano = estado.plano
    val contaMp = estado.contaMp
    var dialogoMp by remember { mutableStateOf(false) }
    val validade = plano?.periodoFim
        ?.takeIf { it.isNotBlank() }
        ?.let { formatarDataCompleta(it) }

    KpEstruturaAba(
        abaAtual = 4,
        aoMudarAba = aoMudarAba,
        topBar = { CabecalhoAba("Perfil") },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // ── Quem é você ─────────────────────────────────────────────────
            // Sem título de seção: o cartão já é a identidade. "Editar" é
            // botão com palavra, não lápis solto — o lápis sozinho não diz o
            // que ele edita.
            item {
                CartaoIdentidade(
                    nome = nome,
                    email = email,
                    aoEditar = {
                        novoNome = nome
                        dialogoNome = true
                    },
                )
            }

            // ── Segurança ───────────────────────────────────────────────────
            item { Text("Segurança", style = TituloSecao) }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column {
                        KpMenuRow(
                            icone = Icons.Default.Key,
                            rotulo = "Trocar senha",
                            onClick = {
                                senhaAtual = ""
                                novaSenha = ""
                                confirmarNovaSenha = ""
                                dialogoSenha = true
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        // A lista de sessões saiu daqui: com quatro ou seis
                        // linhas ela empurrava "Seus dados" e "Sair da conta"
                        // para fora da primeira tela. Aqui fica só o resumo;
                        // a lista (e o "sair de todos") mora na tela própria.
                        KpMenuRow(
                            icone = Icons.Default.Security,
                            rotulo = "Sessões abertas",
                            subtitulo = resumoSessoes(sessoes.size),
                            onClick = aoAbrirSessoes,
                        )
                    }
                }
            }

            // ── Plano ────────────────────────────────────────────────────────
            // Fica entre Segurança e Seus dados: é uma informação da conta,
            // não um dado e não uma sessão. O subtítulo só aparece quando o
            // backend respondeu — chute de status seria mentira.
            item { Text("Plano", style = TituloSecao) }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    KpMenuRow(
                        icone = Icons.Default.WorkspacePremium,
                        rotulo = "Plano Premium",
                        subtitulo = when {
                            plano == null -> null
                            plano.premium && validade != null -> "Premium até $validade"
                            plano.premium -> "Premium ativo"
                            else -> "Você está no plano grátis"
                        },
                        onClick = aoAbrirPlano,
                    )
                }
            }

            // ── Recebimentos ────────────────────────────────────────────────
            // O dinheiro das cobranças vai para a conta do Mercado Pago de quem
            // cobrou. Sem esta conexão não há como receber sem o app virar
            // intermediário — por isso a linha fica aqui, visível, e não
            // escondida numa tela de configuração avançada.
            item { Text("Recebimentos", style = TituloSecao) }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    KpMenuRow(
                        icone = Icons.Default.AccountBalanceWallet,
                        rotulo = "Conta do Mercado Pago",
                        subtitulo = when {
                            contaMp == null -> null
                            contaMp.conectado ->
                                "Conectada: o dinheiro das cobranças cai direto em você"
                            else -> "Não conectada: toque para conectar e poder receber"
                        },
                        onClick = {
                            if (contaMp?.conectado == true) dialogoMp = true
                            else vm.abrirConexaoMp()
                        },
                    )
                }
            }

            // ── Seus dados ──────────────────────────────────────────────────
            item { Text("Seus dados", style = TituloSecao) }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    KpMenuRow(
                        icone = Icons.Default.Download,
                        rotulo = "Baixar meus dados",
                        subtitulo = "Seu extrato em planilha. Grátis, sempre.",
                        onClick = { vm.exportarDadosCsv() },
                    )
                }
            }

            // Retorno da exportação fica AQUI, embaixo da ação que a gerou —
            // não no fim da tela, longe do clique que a pessoa acabou de dar.
            estado.avisoPerfil?.let { mensagem ->
                item {
                    AvisoExportacao(
                        mensagem = mensagem,
                        falhou = estado.avisoPerfilFalhou,
                    )
                }
            }

            item {
                KpCard(Modifier.fillMaxWidth()) {
                    KpMenuRow(
                        icone = Icons.Default.Delete,
                        rotulo = "Eliminar minha conta",
                        subtitulo = "Apaga a conta e todos os registros. Sem volta.",
                        onClick = aoAbrirLgpd,
                        corTexto = sem.despesa,
                    )
                }
            }

            // ── Sair ────────────────────────────────────────────────────────
            // Cor neutra, não vermelha: sair é reversível (é só entrar de
            // novo). Vermelho fica só para "Eliminar minha conta", a única
            // ação que não tem volta.
            item {
                KpCard(Modifier.fillMaxWidth()) {
                    KpMenuRow(
                        icone = Icons.Default.Logout,
                        rotulo = "Sair da conta",
                        subtitulo = "Para voltar, é só entrar de novo.",
                        onClick = aoSair,
                    )
                }
            }

            // ── Rodapé ──────────────────────────────────────────────────────
            item {
                Text(
                    text = "KaveliPay organiza seus registros e não movimenta " +
                        "dinheiro: não é banco e não custodia valores.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.lg, start = Spacing.md, end = Spacing.md),
                )
            }
        }
    }

    // ── Dialog: conta do Mercado Pago conectada ──────────────────────────────
    if (dialogoMp) {
        AlertDialog(
            onDismissRequest = { dialogoMp = false },
            title = { Text("Conta do Mercado Pago") },
            text = {
                Text(
                    "As cobranças que você fizer caem direto na sua conta do " +
                        "Mercado Pago. Se desconectar, os links param de receber " +
                        "até você conectar de novo.",
                )
            },
            confirmButton = {
                KpBotaoSecundario(
                    texto = "Desconectar",
                    onClick = {
                        dialogoMp = false
                        vm.desconectarMp()
                    },
                )
            },
            dismissButton = {
                KpBotaoSecundario("Fechar", onClick = { dialogoMp = false })
            },
        )
    }

    // ── Dialog: editar nome ──────────────────────────────────────────────────
    if (dialogoNome) {
        AlertDialog(
            onDismissRequest = { dialogoNome = false },
            title = { Text("Editar nome") },
            text = {
                Column {
                    androidx.compose.material3.TextField(
                        value = novoNome,
                        onValueChange = { novoNome = it },
                        label = { Text("Como você quer ser chamado") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "Deixe vazio para não usar nome. O e-mail não muda por aqui.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                KpBotaoPrimario(
                    texto = "Salvar",
                    onClick = {
                        vm.atualizarPerfil(novoNome)
                        dialogoNome = false
                    },
                    habilitado = novoNome.length <= 120,
                )
            },
            dismissButton = {
                KpBotaoSecundario("Cancelar", onClick = { dialogoNome = false })
            },
        )
    }

    // ── Dialog: trocar senha ────────────────────────────────────────────────
    if (dialogoSenha) {
        val senhaCurta = novaSenha.isNotEmpty() && novaSenha.length < 8
        val senhasDiferem = confirmarNovaSenha.isNotEmpty() && novaSenha != confirmarNovaSenha
        val podeSalvar = senhaAtual.isNotBlank() &&
            novaSenha.length >= 8 &&
            novaSenha == confirmarNovaSenha

        AlertDialog(
            onDismissRequest = { dialogoSenha = false },
            title = { Text("Trocar senha") },
            text = {
                Column {
                    KpCampoSenha(
                        valor = senhaAtual,
                        rotulo = "Senha atual",
                        aoMudar = { senhaAtual = it },
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    KpCampoSenha(
                        valor = novaSenha,
                        rotulo = "Nova senha (mín. 8 caracteres)",
                        aoMudar = { novaSenha = it },
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    KpCampoSenha(
                        valor = confirmarNovaSenha,
                        rotulo = "Repita a nova senha",
                        aoMudar = { confirmarNovaSenha = it },
                    )
                    // Antes, "Salvar" com senha curta ou diferente simplesmente
                    // não fazia nada: o diálogo ficava aberto e a pessoa não
                    // sabia por quê. Agora o motivo está escrito e o botão só
                    // habilita quando dá para salvar.
                    if (senhaCurta || senhasDiferem) {
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            text = if (senhasDiferem) {
                                "As senhas não conferem."
                            } else {
                                "A nova senha precisa de pelo menos 8 caracteres."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = sem.vencido,
                        )
                    }
                }
            },
            confirmButton = {
                KpBotaoPrimario(
                    texto = "Salvar",
                    onClick = {
                        vm.trocarSenha(senhaAtual, novaSenha)
                        dialogoSenha = false
                    },
                    habilitado = podeSalvar,
                )
            },
            dismissButton = {
                KpBotaoSecundario("Cancelar", onClick = { dialogoSenha = false })
            },
        )
    }

}

/**
 * Resumo do cartão "Sessões abertas".
 *
 * O número é o que a linha precisa dizer — sem ele, "Sessões abertas" não
 * avisa que existem seis. A frase evita "1 sessões".
 */
private fun resumoSessoes(quantidade: Int): String = when {
    quantidade <= 0 -> "Nenhuma listada"
    quantidade == 1 -> "1 conectada"
    else -> "$quantidade conectadas"
}

/** Avatar + nome + e-mail, com o botão de editar ao lado. */
@Composable
private fun CartaoIdentidade(
    nome: String,
    email: String,
    aoEditar: () -> Unit,
) {
    val sem = KpTheme.semanticas
    val iniciais = nome.split(" ").filter { it.isNotBlank() }
        .take(2).joinToString("") { it.take(1).uppercase() }
        .ifBlank { email.take(1).uppercase() }

    KpCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(sem.demo),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    iniciais,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = sem.demoFundo,
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = nome.ifBlank { "Sem nome" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = email.ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            KpBotaoSecundario(texto = "Editar", onClick = aoEditar)
        }
    }
}

/** Retorno da exportação: verde quando salvou, vermelho quando falhou. */
@Composable
private fun AvisoExportacao(mensagem: String, falhou: Boolean) {
    val sem = KpTheme.semanticas
    KpCard(
        Modifier.fillMaxWidth(),
        cor = if (falhou) sem.vencidoFundo else sem.receitaFundo,
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Text(
                text = if (falhou) "Não deu para baixar" else "Arquivo salvo",
                style = MaterialTheme.typography.labelSmall,
                color = if (falhou) sem.vencido else sem.receita,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = mensagem,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun KpCampoSenha(
    valor: String,
    rotulo: String,
    aoMudar: (String) -> Unit,
) {
    androidx.compose.material3.OutlinedTextField(
        value = valor,
        onValueChange = aoMudar,
        label = { Text(rotulo) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}
