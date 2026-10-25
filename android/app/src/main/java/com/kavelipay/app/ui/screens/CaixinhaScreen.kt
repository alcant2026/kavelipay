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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kavelipay.app.data.CaixinhaRegras
import com.kavelipay.app.data.DetalheCaixinha
import com.kavelipay.app.data.MembroDetalhe
import com.kavelipay.app.data.MovimentoCaixinha
import com.kavelipay.app.data.formatarTaxa
import com.kavelipay.app.data.mesCurto
import com.kavelipay.app.data.pendenciasCaixinha
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.Estrelas
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.components.Money.brl
import com.kavelipay.app.ui.theme.RotuloCaixaAlta
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloCard
import com.kavelipay.app.ui.theme.ValorHeroCard
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt

private val ZERO = BigDecimal.ZERO

private fun maiorQueZero(valor: String): Boolean = BigDecimal(valor) > ZERO

/**
 * Detalhe da caixinha — agora conversa com o servidor (`GET /caixinhas/{id}`
 * e as mutações, que devolvem o detalhe atualizado).
 *
 * O papel (responsável/membro) vem do SERVIDOR, do token — sem chips de
 * troca de identidade. Todo número é derivado lá (movimentos confirmados);
 * a tela só mostra. Regra central: quem paga propõe, quem recebe confirma —
 * o card "Para confirmar" nasce dos movimentos `PROPOSTO` da resposta.
 *
 * A caixinha é um registro entre conhecidos, FORA do ledger: nada aqui mexe
 * no saldo da carteira — e o rodapé continua prometendo isso.
 */
@Composable
fun KpCaixinhaScreen(
    vm: AppViewModel,
    caixinhaId: String,
    aoVoltar: () -> Unit,
) {
    val contexto = LocalContext.current
    val estado by vm.estado.collectAsState()

    // Só mostra o detalhe se for da caixinha aberta: o estado guarda o
    // detalhe da última mutação, e voltar de outra caixinha não pode renderizar
    // número de uma pela outra.
    val detalhe = estado.caixinhaDetalhe?.takeIf { it.id == caixinhaId }
    val ehDono = detalhe?.ehDono == true

    var valorAporte by rememberSaveable { mutableStateOf("") }
    var telefone by rememberSaveable { mutableStateOf("") }
    var preenchidoTelefone by rememberSaveable { mutableStateOf(false) }
    var erroTelefone by rememberSaveable { mutableStateOf<String?>(null) }
    var devolverPara by rememberSaveable { mutableStateOf<String?>(null) }
    var valorDevolucao by rememberSaveable { mutableStateOf("") }
    var erroDevolucao by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(caixinhaId) { vm.carregarDetalheCaixinha(caixinhaId) }

    // Preenche o telefone UMA vez, quando o detalhe chega; depois não toca
    // mais no campo — reescreveria o que a pessoa digitou.
    LaunchedEffect(detalhe) {
        if (detalhe != null && !preenchidoTelefone) {
            telefone = meuTelefone(detalhe) ?: ""
            preenchidoTelefone = true
        }
    }

    Scaffold(topBar = { CabecalhoComVoltar("Caixinha", aoVoltar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.md, end = Spacing.md,
                top = Spacing.sm, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (detalhe == null) {
                if (estado.carregandoDetalheCaixinha || estado.erroDetalheCaixinha == null) {
                    item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                } else {
                    item {
                        KpCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(Spacing.md)) {
                                KpMensagemErro(estado.erroDetalheCaixinha.orEmpty())
                                Spacer(Modifier.height(Spacing.sm))
                                KpBotaoSecundario(
                                    texto = "Tentar de novo",
                                    onClick = { vm.carregarDetalheCaixinha(caixinhaId) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            } else {
                val d = detalhe

                // Erro da última mutação (4xx como veio — as strings do contrato
                // já são pt-BR amigáveis).
                if (estado.erroAcaoCaixinha != null) {
                    item { KpMensagemErro(estado.erroAcaoCaixinha.orEmpty()) }
                }

                // ── Para confirmar: derivado dos movimentos PROPOSTO ──────
                val pendencias = pendenciasCaixinha(d)
                if (pendencias.isNotEmpty()) {
                    item {
                        KpCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(Spacing.md)) {
                                Text("Para confirmar", style = TituloCard)
                                Text(
                                    "Só entra na conta depois que as duas pessoas confirmam.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                pendencias.forEach { p ->
                                    val mov = p.movimento
                                    Spacer(Modifier.height(Spacing.md))
                                    Text(
                                        descricaoMovimento(mov, ehDono),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (p.confirmavelPorMim) {
                                        Spacer(Modifier.height(Spacing.sm))
                                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                            KpBotaoPrimario(
                                                texto = "Confirmar",
                                                onClick = {
                                                    vm.confirmarMovimentoCaixinha(caixinhaId, mov.id)
                                                },
                                                modifier = Modifier.weight(1f),
                                                carregando = estado.enviandoCaixinha,
                                            )
                                            KpBotaoSecundario(
                                                texto = "Recusar",
                                                onClick = {
                                                    vm.recusarMovimentoCaixinha(caixinhaId, mov.id)
                                                },
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    } else {
                                        Text(
                                            if (ehDono) {
                                                "Aguardando ${mov.membroNome} confirmar."
                                            } else {
                                                "Aguardando o responsável confirmar."
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        KpBotaoSecundario(
                                            texto = "Cancelar",
                                            onClick = {
                                                vm.cancelarMovimentoCaixinha(caixinhaId, mov.id)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Pedido (com convite copiável — só o dono vê) ───────────
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            Text(d.nome, style = TituloCard)
                            Text(
                                if (ehDono) {
                                    "Criada por você · você é o responsável"
                                } else {
                                    "Você entrou com o código de convite"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            val media = d.mediaAvaliacoes
                            if (media != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Estrelas(
                                        nota = media.replace(',', '.').toBigDecimalOrNull()
                                            ?.setScale(0, RoundingMode.HALF_UP)?.toInt() ?: 0,
                                    )
                                    Spacer(Modifier.width(Spacing.xs))
                                    Text(
                                        "$media · ${d.totalAvaliacoes} " +
                                            if (d.totalAvaliacoes == 1) "avaliação" else "avaliações",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                Text(
                                    "Sem avaliação ainda.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(Spacing.md))
                            Text(
                                "PEDIDO",
                                style = RotuloCaixaAlta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            Text("R$ ${brl(d.valorPedido)}", style = ValorHeroCard)
                            Text(
                                "Rendimento de ${formatarTaxa(d.taxaMensal)}% ao mês " +
                                    "sobre o valor aportado.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(Spacing.md))
                            LinearProgressIndicator(
                                progress = { d.progresso },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                "Arrecadado R$ ${brl(d.arrecadado)} de R$ ${brl(d.valorPedido)} " +
                                    "(${(d.progresso * 100).roundToInt()}%)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (maiorQueZero(d.arrecadadoPendente)) {
                                Text(
                                    "R$ ${brl(d.arrecadadoPendente)} aguardando confirmação.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            d.previsaoQuitacao?.let {
                                Text(
                                    "Previsão de quitação: $it.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            // Convite ATIVO do servidor — nunca hardcoded, nunca
                            // histórico: o servidor gera o próximo quando este
                            // é consumido.
                            if (ehDono && !d.convite.isNullOrBlank()) {
                                val convite = d.convite
                                Spacer(Modifier.height(Spacing.md))
                                Text(
                                    "CONVITE",
                                    style = RotuloCaixaAlta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(Spacing.xs))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        convite,
                                        style = TituloCard,
                                        modifier = Modifier.weight(1f),
                                    )
                                    KpBotaoSecundario(
                                        texto = "Copiar",
                                        onClick = {
                                            copiarCodigo(contexto, convite)
                                            Toast.makeText(contexto, "Código copiado.", Toast.LENGTH_SHORT).show()
                                        },
                                    )
                                }
                                Text(
                                    "Quem tiver o código entra e passa a ver os próprios números. " +
                                        "Cada código serve para uma entrada; usado, o servidor gera outro.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // ── Quem está dentro (dono vê todos; membro só ele — o
                //    servidor já filtra) ────────────────────────────────────
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            Text("Quem está dentro", style = TituloCard)
                            Text(
                                if (ehDono) {
                                    "O que cada pessoa emprestou e recebe por mês."
                                } else {
                                    "Você vê só os seus números."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            d.membros.forEach { membro ->
                                Spacer(Modifier.height(Spacing.md))
                                LinhaMembro(
                                    membro = membro,
                                    ehDono = ehDono,
                                    carregando = estado.enviandoCaixinha,
                                    aoPagar = {
                                        vm.proporRendimentoCaixinha(caixinhaId, membro.userId)
                                    },
                                    aoDevolver = {
                                        vm.limparErroAcaoCaixinha()
                                        devolverPara = membro.userId
                                        valorDevolucao = ""
                                        erroDevolucao = null
                                    },
                                )
                            }
                            if (ehDono) {
                                d.totalDevidoMes?.let {
                                    Spacer(Modifier.height(Spacing.md))
                                    Text(
                                        "Você paga R$ ${brl(it)} este mês.",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                if (d.totalPagoMes != null && d.totalFaltaMes != null) {
                                    Text(
                                        "Pago R$ ${brl(d.totalPagoMes)} · falta R$ ${brl(d.totalFaltaMes)}.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Avaliação (membro) ─────────────────────────────────────
                if (!ehDono) {
                    item {
                        KpCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(Spacing.md)) {
                                Text("Avaliação", style = TituloCard)
                                Text(
                                    "Só o credor avalia, uma vez por caixinha.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val minha = d.minhaAvaliacao
                                val meuAporte = d.membros.firstOrNull()?.aportado ?: "0.00"
                                Spacer(Modifier.height(Spacing.sm))
                                if (minha != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "Sua avaliação:",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.width(Spacing.sm))
                                        Estrelas(minha)
                                    }
                                } else if (!maiorQueZero(meuAporte)) {
                                    Text(
                                        "Só quem fez aporte pode avaliar.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    Text(
                                        "Toque nas estrelas para avaliar.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row {
                                        (1..5).forEach { nota ->
                                            IconButton(onClick = {
                                                vm.avaliarCaixinha(caixinhaId, nota)
                                            }) {
                                                Icon(
                                                    Icons.Filled.StarBorder,
                                                    contentDescription = "$nota " +
                                                        if (nota == 1) "estrela" else "estrelas",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Contato + editar meu telefone ──────────────────────────
                item {
                    KpCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            Text("Contato", style = TituloCard)
                            Text(
                                "Para falar direto sobre os pagamentos.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            LinhaContato("${d.dono.nome} · responsável", exibirTelefone(d.dono.telefone))
                            d.membros.forEach { membro ->
                                LinhaContato(membro.nome, exibirTelefone(membro.telefone))
                            }
                            Spacer(Modifier.height(Spacing.md))
                            OutlinedTextField(
                                value = telefone,
                                onValueChange = { telefone = it; erroTelefone = null },
                                label = { Text("Seu telefone (DDD + número)") },
                                placeholder = { Text("(11) 98888-7777") },
                                singleLine = true,
                                isError = erroTelefone != null,
                                supportingText = erroTelefone?.let { { Text(it) } },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            KpBotaoPrimario(
                                texto = "Salvar telefone",
                                onClick = {
                                    val digitos = CaixinhaRegras.telefoneParaApi(telefone)
                                    if (digitos == null) {
                                        erroTelefone = "Digite um telefone com DDD."
                                    } else {
                                        vm.salvarTelefoneCaixinha(caixinhaId, digitos)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                carregando = estado.enviandoCaixinha,
                            )
                        }
                    }
                }

                // ── Aportar (membro) ──────────────────────────────────────
                if (!ehDono) {
                    item {
                        KpCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(Spacing.md)) {
                                Text("Aportar", style = TituloCard)
                                Text(
                                    "Quem paga propõe; o responsável confirma que recebeu.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(Spacing.sm))
                                OutlinedTextField(
                                    value = valorAporte,
                                    onValueChange = { valorAporte = it },
                                    label = { Text("Valor (R$)") },
                                    placeholder = { Text("0,00") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(Spacing.sm))
                                KpBotaoPrimario(
                                    texto = "Aportar",
                                    onClick = {
                                        val valorApi = CaixinhaRegras.parseValor(valorAporte)
                                        if (valorApi != null) {
                                            vm.proporAporteCaixinha(
                                                caixinhaId,
                                                valorApi.toPlainString(),
                                            ) { valorAporte = "" }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    habilitado = CaixinhaRegras.parseValor(valorAporte) != null &&
                                        maiorQueZero(d.disponivelAporte),
                                    carregando = estado.enviandoCaixinha,
                                )
                                Text(
                                    if (maiorQueZero(d.disponivelAporte)) {
                                        "Disponível para aportar: R$ ${brl(d.disponivelAporte)}."
                                    } else {
                                        "Pedido completo ou aguardando confirmação."
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // ── Histórico (o servidor manda PROPOSTO ∪ últimos 10 resolvidos) ──
                if (d.movimentos.isNotEmpty()) {
                    item {
                        KpCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(Spacing.md)) {
                                Text("Histórico", style = TituloCard)
                                d.movimentos.forEach { mov ->
                                    Spacer(Modifier.height(Spacing.sm))
                                    Text(
                                        descricaoMovimento(mov, ehDono),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        rotuloStatus(mov.status),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        "Cada movimento só entra na conta depois que as duas partes confirmam. " +
                            "O app não cobra, não move dinheiro e não é fiador. Nada aqui mexe no seu saldo.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // ── Dialog: devolver aporte (responsável, por membro) ─────────────────
    devolverPara?.let { alvo ->
        val membro = detalhe?.membros?.firstOrNull { it.userId == alvo }
        val erro = erroDevolucao ?: estado.erroAcaoCaixinha
        AlertDialog(
            onDismissRequest = {
                vm.limparErroAcaoCaixinha()
                devolverPara = null
                erroDevolucao = null
            },
            title = { Text("Devolver aporte") },
            text = {
                Column {
                    Text(
                        "Saldo em aberto de ${membro?.nome.orEmpty()}: " +
                            "R$ ${brl(membro?.saldoEmAberto ?: "0.00")}.",
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = valorDevolucao,
                        onValueChange = {
                            valorDevolucao = it
                            erroDevolucao = null
                            vm.limparErroAcaoCaixinha()
                        },
                        label = { Text("Valor (R$)") },
                        singleLine = true,
                        isError = erro != null,
                        supportingText = erro?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "O membro confirma o recebimento no app dele.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                KpBotaoPrimario(
                    texto = "Registrar",
                    onClick = {
                        val valorApi = CaixinhaRegras.parseValor(valorDevolucao)
                        if (valorApi == null) {
                            erroDevolucao = "Digite um valor maior que zero."
                        } else {
                            vm.proporDevolucaoCaixinha(
                                caixinhaId,
                                alvo,
                                valorApi.toPlainString(),
                            ) {
                                devolverPara = null
                                valorDevolucao = ""
                                erroDevolucao = null
                            }
                        }
                    },
                    habilitado = valorDevolucao.isNotBlank(),
                    carregando = estado.enviandoCaixinha,
                )
            },
            dismissButton = {
                KpBotaoSecundario(
                    texto = "Cancelar",
                    onClick = {
                        vm.limparErroAcaoCaixinha()
                        devolverPara = null
                        erroDevolucao = null
                    },
                )
            },
        )
    }
}

/** Linha de um membro: saldo em aberto, rendimento do mês e as ações do dono. */
@Composable
private fun LinhaMembro(
    membro: MembroDetalhe,
    ehDono: Boolean,
    carregando: Boolean,
    aoPagar: () -> Unit,
    aoDevolver: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(membro.nome, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "R$ ${brl(membro.saldoEmAberto)} em aberto" +
                        if (BigDecimal(membro.aportado) > BigDecimal(membro.saldoEmAberto)) {
                            " · juro sobre R$ ${brl(membro.aportado)}"
                        } else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("R$ ${brl(membro.devidoMes)}/mês", style = TituloCard)
        }
        Spacer(Modifier.height(Spacing.xs))
        if (ehDono) {
            Text(
                when {
                    membro.rendimentoPendente -> "Rendimento aguardando confirmação."
                    maiorQueZero(membro.faltaMes) -> "Falta R$ ${brl(membro.faltaMes)} neste mês."
                    maiorQueZero(membro.devidoMes) -> "Mês pago."
                    else -> "Sem rendimento neste mês."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val podePagar = !membro.rendimentoPendente && maiorQueZero(membro.rendimentoDisponivel)
            val podeDevolver = maiorQueZero(membro.saldoEmAberto)
            if (podePagar || podeDevolver) {
                Spacer(Modifier.height(Spacing.xs))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (podePagar) {
                        KpBotaoPrimario(
                            texto = "Pagar R$ ${brl(membro.rendimentoDisponivel)}",
                            onClick = aoPagar,
                            carregando = carregando,
                        )
                    }
                    if (podeDevolver) {
                        KpBotaoSecundario(texto = "Devolver", onClick = aoDevolver)
                    }
                }
            }
        } else {
            Text(
                when {
                    maiorQueZero(membro.faltaMes) -> "A receber neste mês: R$ ${brl(membro.faltaMes)}."
                    maiorQueZero(membro.pagoMes) -> "Recebido neste mês: R$ ${brl(membro.pagoMes)}."
                    else -> "Sem rendimento neste mês."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LinhaContato(nome: String, telefone: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(nome, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            telefone,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Movimento em texto. O membro só vê os próprios movimentos, então dele se
 * fala em "você"; o dono fala dos outros pelo nome.
 */
private fun descricaoMovimento(mov: MovimentoCaixinha, ehDono: Boolean): String {
    val quem = if (ehDono) mov.membroNome else "você"
    return when (mov.tipo) {
        "APORTE" ->
            if (ehDono) "$quem declarou aporte de R$ ${brl(mov.valor)}"
            else "Você declarou aporte de R$ ${brl(mov.valor)}"

        "RENDIMENTO" ->
            "Rendimento de ${mov.competencia?.let(::mesCurto) ?: "—"} para $quem: R$ ${brl(mov.valor)}"

        else -> "Devolução de R$ ${brl(mov.valor)} para $quem"
    }
}

private fun rotuloStatus(status: String): String = when (status) {
    "PROPOSTO" -> "aguardando confirmação"
    "CONFIRMADO" -> "confirmado pelas duas partes"
    "RECUSADO" -> "recusado"
    "CANCELADO" -> "cancelado"
    // Status desconhecido não vira rótulo inventado: mostrar o que o servidor
    // mandou é o que dá para depurar.
    else -> status.lowercase().replace('_', ' ')
}

/** Telefone do servidor (só dígitos) no formato que se lê — ou "não informado". */
private fun exibirTelefone(telefone: String?): String =
    when {
        telefone.isNullOrBlank() -> "não informado"
        else -> CaixinhaRegras.formatarTelefone(telefone) ?: telefone
    }

/** Meu telefone dentro da caixinha: dono grava o da caixinha, membro o próprio. */
private fun meuTelefone(d: DetalheCaixinha): String? =
    if (d.ehDono) d.dono.telefone else d.membros.firstOrNull()?.telefone

private fun copiarCodigo(contexto: Context, codigo: String) {
    val clipboard = contexto.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("KaveliPay", codigo))
}
