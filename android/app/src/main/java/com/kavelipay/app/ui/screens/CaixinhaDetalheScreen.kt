package com.kavelipay.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.kavelipay.app.data.DetalheCaixinha
import com.kavelipay.app.data.MembroCaixinha
import com.kavelipay.app.data.MovimentoCaixinha
import com.kavelipay.app.data.mesLocal
import com.kavelipay.app.data.telefoneDigitos
import com.kavelipay.app.data.telefoneValido
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBadgeNeutra
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.KpLinhaResumo
import com.kavelipay.app.ui.components.Money
import com.kavelipay.app.ui.components.formatarData
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloCard
import com.kavelipay.app.ui.theme.TituloTela
import java.math.BigDecimal
import java.time.YearMonth

/**
 * Detalhe da caixinha — o que o servidor já derivou (nada é recalculado aqui).
 *
 * O mesmo corpo serve aos dois papéis: o dono vê todos os membros e propõe
 * rendimento/devolução; o membro vê os próprios números e propõe aporte. Quem
 * decide de verdade é o backend — a tela só evita mostrar ação impossível.
 */
@Composable
fun KpCaixinhaDetalheScreen(
    vm: AppViewModel,
    id: String,
    aoVoltar: () -> Unit,
) {
    val estado by vm.estado.collectAsState()
    // O mês visível é escolha do usuário: renda de mês passado não some, e o
    // dono precisa escolher a competência do rendimento que está pagando.
    var mes by remember { mutableStateOf(mesLocal()) }
    LaunchedEffect(id, mes) { vm.carregarCaixinha(id, mes) }

    val detalhe = estado.caixinhaDetalhe?.takeIf { it.id == id }

    var aporteAberto by remember { mutableStateOf(false) }
    var devolucaoDe by remember { mutableStateOf<MembroCaixinha?>(null) }
    var telefoneAberto by remember { mutableStateOf(false) }

    Scaffold(topBar = { CabecalhoComVoltar("Caixinha", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md,
                end = Spacing.md,
                top = Spacing.sm,
                bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (detalhe == null) {
                item {
                    if (estado.carregando) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.xl),
                        )
                    } else {
                        Text(
                            estado.erro ?: "Não foi possível abrir a caixinha.",
                            modifier = Modifier.padding(Spacing.md),
                        )
                    }
                }
            } else {
                item { KpResumoCaixinha(detalhe, mes, aoMudarMes = { mes = it }) }

                detalhe.convite?.let { codigo ->
                    item { KpConvite(codigo) }
                }

                estado.erro?.let { mensagem ->
                    item { KpMensagemErro(mensagem) }
                }

                val pendentes = detalhe.movimentos.filter { it.status == "PROPOSTO" }
                if (pendentes.isNotEmpty()) {
                    item { TituloSecao("Para confirmar") }
                    items(pendentes, key = { it.id }) { movimento ->
                        KpMovimentoPendente(
                            movimento = movimento,
                            meuId = estado.perfil?.id,
                            habilitado = !estado.enviando,
                            aoConfirmar = { vm.confirmarMovimentoCaixinha(id, movimento.id, mes) },
                            aoRecusar = { vm.recusarMovimentoCaixinha(id, movimento.id, mes) },
                            aoCancelar = { vm.cancelarMovimentoCaixinha(id, movimento.id, mes) },
                        )
                    }
                }

                item { TituloSecao("Membros") }
                if (detalhe.membros.isEmpty()) {
                    item {
                        Text(
                            "Ninguém entrou ainda. Compartilhe o código de convite acima.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(detalhe.membros, key = { it.userId }) { membro ->
                    KpMembroCard(
                        membro = membro,
                        papel = detalhe.papel,
                        aoRender = { vm.renderCaixinha(id, membro.userId, mes) },
                        aoDevolver = { devolucaoDe = membro },
                    )
                }

                val meuMembro = detalhe.membros.firstOrNull { it.userId == estado.perfil?.id }

                if (detalhe.papel == "membro" && positivo(detalhe.disponivelAporte)) {
                    item {
                        KpBotaoPrimario(
                            texto = "Propor aporte",
                            onClick = { aporteAberto = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (detalhe.papel == "membro" && detalhe.minhaAvaliacao == null &&
                    meuMembro?.let { positivo(it.aportado) } == true
                ) {
                    item {
                        KpAvaliar(
                            aoAvaliar = { estrelas -> vm.avaliarCaixinha(id, estrelas, mes) },
                        )
                    }
                }

                val meuTelefone = if (detalhe.papel == "dono") {
                    detalhe.dono.telefone
                } else {
                    meuMembro?.telefone
                }
                item {
                    KpMeuTelefone(
                        telefone = meuTelefone,
                        aoEditar = { telefoneAberto = true },
                    )
                }

                val resolvidos = detalhe.movimentos.filter { it.status != "PROPOSTO" }
                if (resolvidos.isNotEmpty()) {
                    item { TituloSecao("Histórico") }
                    items(resolvidos, key = { it.id }) { movimento ->
                        KpMovimentoResolvido(movimento)
                    }
                }

                item {
                    Text(
                        "O KaveliPay só registra o combinado entre vocês. Nenhum valor é " +
                            "transferido pelo app e nenhuma cobrança é feita automaticamente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (aporteAberto && detalhe != null) {
        DialogoValor(
            titulo = "Propor aporte",
            rotulo = "Valor do aporte",
            limite = detalhe.disponivelAporte,
            carregando = estado.enviando,
            aoFechar = { aporteAberto = false },
            aoConfirmar = { valor ->
                vm.aportarCaixinha(id, valor, mes)
                aporteAberto = false
            },
        )
    }

    devolucaoDe?.let { membro ->
        DialogoValor(
            titulo = "Registrar devolução",
            rotulo = "Valor devolvido a ${membro.nome}",
            limite = membro.saldoEmAberto,
            carregando = estado.enviando,
            aoFechar = { devolucaoDe = null },
            aoConfirmar = { valor ->
                vm.devolverCaixinha(id, membro.userId, valor, mes)
                devolucaoDe = null
            },
        )
    }

    if (telefoneAberto) {
        var telefone by remember { mutableStateOf("") }
        val digitos = telefoneDigitos(telefone)
        val invalido = digitos.isNotEmpty() && !telefoneValido(digitos)
        AlertDialog(
            onDismissRequest = { telefoneAberto = false },
            title = { Text("Telefone de contato") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        "Só quem participa da caixinha vê este telefone. Use DDD.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    KpCampo(
                        valor = telefone,
                        aoMudar = { telefone = telefoneDigitos(it) },
                        rotulo = "Telefone com DDD",
                        teclado = KeyboardType.Phone,
                        imeAction = ImeAction.Done,
                        umaLinha = true,
                        erro = if (invalido) "Digite um telefone com DDD." else null,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = telefoneValido(digitos) && !estado.enviando,
                    onClick = {
                        vm.salvarTelefoneCaixinha(id, digitos, mes)
                        telefoneAberto = false
                    },
                ) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { telefoneAberto = false }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun TituloSecao(texto: String) {
    Text(
        texto,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun KpResumoCaixinha(
    detalhe: DetalheCaixinha,
    mes: String,
    aoMudarMes: (String) -> Unit,
) {
    KpCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    detalhe.nome,
                    style = TituloTela,
                    modifier = Modifier.weight(1f),
                )
                KpBadgeNeutra(if (detalhe.papel == "dono") "Responsável" else "Membro")
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "R$ ${Money.brl(detalhe.arrecadado)} de R$ ${Money.brl(detalhe.valorPedido)}",
                style = TituloCard,
            )
            Text(
                "${(detalhe.progresso * 100).toInt()}% do pedido",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.sm))
            KpLinhaResumo("Taxa ao mês", "${detalhe.taxaMensal.replace('.', ',')}%")
            detalhe.previsaoQuitacao?.let {
                Spacer(Modifier.height(Spacing.xs))
                KpLinhaResumo("Previsão de quitação", it)
            }
            detalhe.arrecadadoPendente?.let { pendente ->
                if (positivo(pendente)) {
                    Spacer(Modifier.height(Spacing.xs))
                    KpLinhaResumo(
                        "Aportes aguardando confirmação",
                        "R$ ${Money.brl(pendente)}",
                    )
                }
            }
            detalhe.mediaAvaliacoes?.let { media ->
                Spacer(Modifier.height(Spacing.xs))
                KpLinhaResumo(
                    "Avaliação",
                    "★ $media (${detalhe.totalAvaliacoes})",
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    aoMudarMes(YearMonth.parse(mes).minusMonths(1).toString())
                }) { Text("‹ anterior") }
                Text(mes.replace('-', '/'), style = TituloCard)
                TextButton(onClick = {
                    aoMudarMes(YearMonth.parse(mes).plusMonths(1).toString())
                }) { Text("próximo ›") }
            }
            if (detalhe.papel == "dono" && detalhe.totalFaltaMes != null) {
                Spacer(Modifier.height(Spacing.sm))
                KpLinhaResumo(
                    "Falta receber neste mês",
                    "R$ ${Money.brl(detalhe.totalFaltaMes)}",
                    negrito = true,
                )
            }
            if (detalhe.papel == "membro") {
                detalhe.membros.firstOrNull()?.let { eu ->
                    Spacer(Modifier.height(Spacing.sm))
                    KpLinhaResumo(
                        "Falta pagar neste mês",
                        "R$ ${Money.brl(eu.faltaMes)}",
                        negrito = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun KpConvite(codigo: String) {
    val contexto = LocalContext.current
    KpCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md)) {
            Text("Código de convite", style = TituloCard)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "Quem receber este código entra como membro. Cada código vale uma vez.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                codigo,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.sm))
            KpBotaoSecundario(
                texto = "Copiar código",
                onClick = {
                    val area = contexto.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    area.setPrimaryClip(ClipData.newPlainText("Convite da caixinha", codigo))
                    Toast.makeText(contexto, "Código copiado.", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun KpMovimentoPendente(
    movimento: MovimentoCaixinha,
    meuId: String?,
    habilitado: Boolean,
    aoConfirmar: () -> Unit,
    aoRecusar: () -> Unit,
    aoCancelar: () -> Unit,
) {
    val fuiEu = meuId != null && movimento.propostoPor == meuId
    KpCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md)) {
            Text(
                "${rotuloTipo(movimento.tipo)} · ${movimento.membroNome}",
                style = TituloCard,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "R$ ${Money.brl(movimento.valor)}" +
                    (movimento.competencia?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (fuiEu) {
                    KpBotaoSecundario(
                        texto = "Cancelar",
                        onClick = aoCancelar,
                        habilitado = habilitado,
                    )
                } else {
                    KpBotaoSecundario(
                        texto = "Recusar",
                        onClick = aoRecusar,
                        habilitado = habilitado,
                    )
                    KpBotaoPrimario(
                        texto = "Confirmar",
                        onClick = aoConfirmar,
                        habilitado = habilitado,
                    )
                }
            }
        }
    }
}

@Composable
private fun KpMembroCard(
    membro: MembroCaixinha,
    papel: String,
    aoRender: () -> Unit,
    aoDevolver: () -> Unit,
) {
    KpCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(membro.nome, style = TituloCard, modifier = Modifier.weight(1f))
                if (membro.rendimentoPendente) KpBadgeNeutra("Rendimento proposto")
            }
            membro.telefone?.let {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    formatarTelefone(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            KpLinhaResumo("Aportado", "R$ ${Money.brl(membro.aportado)}")
            Spacer(Modifier.height(Spacing.xs))
            KpLinhaResumo("Saldo em aberto", "R$ ${Money.brl(membro.saldoEmAberto)}")
            Spacer(Modifier.height(Spacing.xs))
            KpLinhaResumo("Devido neste mês", "R$ ${Money.brl(membro.devidoMes)}")
            Spacer(Modifier.height(Spacing.xs))
            KpLinhaResumo("Pago neste mês", "R$ ${Money.brl(membro.pagoMes)}")
            Spacer(Modifier.height(Spacing.xs))
            KpLinhaResumo("Falta neste mês", "R$ ${Money.brl(membro.faltaMes)}")
            if (positivo(membro.rendimentoDisponivel)) {
                Spacer(Modifier.height(Spacing.xs))
                KpLinhaResumo(
                    "Rendimento disponível",
                    "R$ ${Money.brl(membro.rendimentoDisponivel)}",
                )
            }
            if (papel == "dono") {
                Spacer(Modifier.height(Spacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (positivo(membro.saldoEmAberto)) {
                        KpBotaoSecundario(
                            texto = "Registrar devolução",
                            onClick = aoDevolver,
                        )
                    }
                    if (positivo(membro.rendimentoDisponivel) && !membro.rendimentoPendente) {
                        KpBotaoSecundario(
                            texto = "Pagar rendimento",
                            onClick = aoRender,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KpAvaliar(aoAvaliar: (Int) -> Unit) {
    KpCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Como foi essa caixinha?", style = TituloCard)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "Sua avaliação ajuda outras pessoas que forem emprestar para alguém.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.sm))
            Row {
                (1..5).forEach { estrela ->
                    IconButton(onClick = { aoAvaliar(estrela) }) {
                        Icon(
                            Icons.Default.StarBorder,
                            contentDescription = "$estrela estrelas",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KpMeuTelefone(telefone: String?, aoEditar: () -> Unit) {
    KpCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md)) {
            KpLinhaResumo(
                "Seu telefone nesta caixinha",
                telefone?.let { formatarTelefone(it) } ?: "não informado",
            )
            Spacer(Modifier.height(Spacing.xs))
            KpBotaoSecundario(
                texto = if (telefone == null) "Informar telefone" else "Trocar telefone",
                onClick = aoEditar,
            )
        }
    }
}

@Composable
private fun KpMovimentoResolvido(movimento: MovimentoCaixinha) {
    KpCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${rotuloTipo(movimento.tipo)} · ${movimento.membroNome}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    buildString {
                        append(formatarData(movimento.criadoEm))
                        movimento.competencia?.let { append(" · $it") }
                        append(" · ${rotuloStatus(movimento.status)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Text("R$ ${Money.brl(movimento.valor)}", style = TituloCard)
        }
    }
}

@Composable
private fun DialogoValor(
    titulo: String,
    rotulo: String,
    limite: String,
    carregando: Boolean,
    aoFechar: () -> Unit,
    aoConfirmar: (String) -> Unit,
) {
    var valor by remember { mutableStateOf("") }
    val numero = valor.toBigDecimalOrNull()
    val limiteNumero = limite.toBigDecimalOrNull()
    val acimaDoLimite = numero != null && limiteNumero != null && numero > limiteNumero
    val valido = numero != null && numero > BigDecimal.ZERO && !acimaDoLimite

    AlertDialog(
        onDismissRequest = { if (!carregando) aoFechar() },
        title = { Text(titulo) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "Máximo: R$ ${Money.brl(limite)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("R\$", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(Spacing.sm))
                    KpCampo(
                        valor = valor,
                        aoMudar = { valor = filtrarValor(it) },
                        rotulo = rotulo,
                        teclado = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                        umaLinha = true,
                        erro = if (acimaDoLimite) "Acima do valor disponível." else null,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valido && !carregando,
                onClick = { aoConfirmar(valor) },
            ) { Text(if (carregando) "Enviando…" else "Propor") }
        },
        dismissButton = {
            TextButton(enabled = !carregando, onClick = aoFechar) { Text("Cancelar") }
        },
    )
}

private fun positivo(valor: String): Boolean =
    valor.toBigDecimalOrNull()?.signum() == 1

private fun rotuloTipo(tipo: String): String = when (tipo) {
    "APORTE" -> "Aporte"
    "RENDIMENTO" -> "Rendimento"
    "DEVOLUCAO" -> "Devolução"
    else -> tipo
}

private fun rotuloStatus(status: String): String = when (status) {
    "CONFIRMADO" -> "confirmado"
    "RECUSADO" -> "recusado"
    "CANCELADO" -> "cancelado"
    "PROPOSTO" -> "aguardando"
    else -> status
}

/** `11987654321` → `(11) 98765-4321`; 10 dígitos → `(11) 8765-4321`. */
internal fun formatarTelefone(digitos: String): String {
    val d = telefoneDigitos(digitos)
    if (!telefoneValido(d)) return digitos
    val ddd = d.take(2)
    val resto = d.drop(2)
    val corte = if (resto.length == 9) 5 else 4
    return "($ddd) ${resto.take(corte)}-${resto.drop(corte)}"
}
