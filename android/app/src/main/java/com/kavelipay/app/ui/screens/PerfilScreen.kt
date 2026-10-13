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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpEtiqueta
import com.kavelipay.app.ui.components.KpLinhaResumo
import com.kavelipay.app.ui.components.KpVazio
import com.kavelipay.app.ui.components.formatarData
import com.kavelipay.app.ui.theme.KpTheme
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloSecao

/**
 * Perfil do usuário — remodelado com UI estilo Nubank.
 *
 * Referência: Nubank exibe o avatar circular com iniciais em destaque no topo,
 * agrupa itens em cartões com ícone + label + chevron, e usa o roxo da marca
 * como acento. Aqui mantemos o verde do KaveliPay (coerente com o resto do
 * app) mas adotamos o layout: avatar centralizado, seções em cards com
 * ícone à esquerda e chevron à direita, tipografia mais limpa.
 */
@Composable
fun KpPerfilScreen(
    vm: AppViewModel,
    aoVoltar: () -> Unit,
    aoAbrirLgpd: () -> Unit = {},
    aoSair: () -> Unit = {},
) {
    val estado by vm.estado.collectAsState()
    val sem = KpTheme.semanticas

    LaunchedEffect(Unit) {
        vm.carregarPerfilESessoes()
    }

    var mostrarDialogNome by remember { mutableStateOf(false) }
    var novoNome by remember { mutableStateOf(estado.perfil?.displayName.orEmpty()) }
    var showDialogTrocarSenha by remember { mutableStateOf(false) }
    var senhaAtual by remember { mutableStateOf("") }
    var novaSenha by remember { mutableStateOf("") }
    var confirmarNovaSenha by remember { mutableStateOf("") }

    Scaffold(topBar = { CabecalhoComVoltar("Perfil & Segurança", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // ── Avatar + identidade ─────────────────────────────────────────
            item {
                val nome = estado.perfil?.displayName ?: ""
                val email = estado.perfil?.email ?: ""
                val iniciais = nome.split(" ").filter { it.isNotBlank() }
                    .take(2).joinToString("") { it.take(1).uppercase() }
                    .ifBlank { email.take(1).uppercase() }

                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(sem.demo),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            iniciais,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = sem.demoFundo,
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        nome.ifBlank { "—" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        email,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── Perfil ──────────────────────────────────────────────────────
            item { Text("Perfil", style = TituloSecao) }
            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column {
                        MenuRow(
                            icone = Icons.Default.Edit,
                            rotulo = "Editar nome",
                            onClick = {
                                novoNome = estado.perfil?.displayName.orEmpty()
                                mostrarDialogNome = true
                            },
                        )
                    }
                }
            }

            // ── Segurança ────────────────────────────────────────────────────
            item { Text("Segurança", style = TituloSecao) }
            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column {
                        MenuRow(
                            icone = Icons.Default.Key,
                            rotulo = "Trocar senha",
                            onClick = { showDialogTrocarSenha = true },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        MenuRow(
                            icone = Icons.Default.Logout,
                            rotulo = "Sair de todas as outras sessões",
                            onClick = { vm.revogarTodasSessoes() },
                            corTexto = sem.despesa,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        MenuRow(
                            icone = Icons.Default.Logout,
                            rotulo = "Sair da conta",
                            onClick = aoSair,
                            corTexto = sem.despesa,
                            ultimo = true,
                        )
                    }
                }
            }

            // ── Sessões ativas ──────────────────────────────────────────────
            item { Text("Sessões ativas", style = TituloSecao) }
            val sessoes = estado.sessoes?.itens.orEmpty()
            if (sessoes.isEmpty()) {
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        KpVazio(
                            icone = Icons.Default.Security,
                            titulo = "Nenhuma sessão",
                            descricao = "Você não tem sessões registradas.",
                        )
                    }
                }
            } else {
                items(sessoes.size) { indice ->
                    val sessao = sessoes[indice]
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = Spacing.md)) {
                            LinhaSessao(sessao, onRevogar = { vm.revogarSessao(sessao.id) })
                            if (indice < sessoes.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }

            // ── LGPD ────────────────────────────────────────────────────────
            item { Text("Dados & Privacidade", style = TituloSecao) }
            item {
                KpCard(Modifier.fillMaxWidth()) {
                    Column {
                        MenuRow(
                            icone = Icons.Default.Download,
                            rotulo = "Exportar dados (JSON)",
                            onClick = { vm.exportarDadosJson() },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        MenuRow(
                            icone = Icons.Default.TableChart,
                            rotulo = "Exportar CSV",
                            onClick = { vm.exportarDadosCsv() },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        MenuRow(
                            icone = Icons.Default.Delete,
                            rotulo = "Eliminar minha conta",
                            onClick = aoAbrirLgpd,
                            corTexto = sem.despesa,
                            ultimo = true,
                        )
                    }
                }
            }

            // ── Status da exportação ──────────────────────────────────────────
            // O ViewModel escreve o resultado em `estado.erro` — é o mesmo
            // campo de erro, porque é o único canal de feedback da API. Mostrar
            // "Dados salvos em..." aqui, e não deixar o usuário sem resposta.
            estado.erro?.let { mensagem ->
                item {
                    val ehErro = !mensagem.contains("salvo") && !mensagem.contains("CSV")
                    KpCard(
                        Modifier.fillMaxWidth(),
                        cor = if (ehErro) sem.vencidoFundo else sem.receitaFundo,
                    ) {
                        Column(Modifier.padding(Spacing.md)) {
                            Text(
                                if (ehErro) "Erro" else "Exportação concluída",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (ehErro) sem.vencido else sem.receita,
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                mensagem,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            // ── Recuo final para o teclado ────────────────────────────────────
            item { Spacer(Modifier.height(Spacing.xxl)) }
        }
    }

    // ── Dialog editar nome ────────────────────────────────────────────────────
    if (mostrarDialogNome) {
        AlertDialog(
            onDismissRequest = { mostrarDialogNome = false },
            title = { Text("Editar nome") },
            text = {
                Column(Modifier.padding(Spacing.md)) {
                    androidx.compose.material3.TextField(
                        value = novoNome, onValueChange = { novoNome = it },
                        label = { Text("Nome (máx. 120)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "Campo vazio limpa o nome. O e-mail não muda por aqui.",
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
                        mostrarDialogNome = false
                    },
                    habilitado = novoNome.length <= 120,
                )
            },
            dismissButton = {
                KpBotaoSecundario("Cancelar", onClick = { mostrarDialogNome = false })
            },
        )
    }

    // ── Dialog trocar senha ──────────────────────────────────────────────────
    if (showDialogTrocarSenha) {
        AlertDialog(
            onDismissRequest = { showDialogTrocarSenha = false },
            title = { Text("Trocar senha") },
            text = {
                Column(Modifier.padding(Spacing.md)) {
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
                        rotulo = "Confirmar nova senha",
                        aoMudar = { confirmarNovaSenha = it },
                    )
                }
            },
            confirmButton = {
                KpBotaoPrimario(
                    texto = "Salvar",
                    onClick = {
                        if (novaSenha == confirmarNovaSenha && novaSenha.length >= 8) {
                            vm.trocarSenha(senhaAtual, novaSenha)
                            showDialogTrocarSenha = false
                        }
                    },
                )
            },
            dismissButton = {
                KpBotaoSecundario("Cancelar", onClick = { showDialogTrocarSenha = false })
            },
        )
    }
}

/**
 * Linha de menu estilo Nubank: ícone, rótulo e chevron à direita.
 *
 * O chevron dá a dica visual de que a linha é clicável — sem ele, o usuário
 * não sabe se o texto é um label estático ou um item acionável. O `ultimo`
 * suprime o divisor inferior para não emendar com a borda do card.
 */
@Composable
private fun MenuRow(
    icone: androidx.compose.ui.graphics.vector.ImageVector,
    rotulo: String,
    onClick: () -> Unit,
    corTexto: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    ultimo: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(
            imageVector = icone,
            contentDescription = null,
            tint = corTexto,
            modifier = Modifier.size(20.dp),
        )
        Text(
            rotulo,
            style = MaterialTheme.typography.bodyLarge,
            color = corTexto,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(14.dp),
        )
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