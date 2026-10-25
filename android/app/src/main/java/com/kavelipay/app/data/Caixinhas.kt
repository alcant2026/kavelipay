package com.kavelipay.app.data

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * DTOs da Caixinha — `docs/caixinha-api.md` é a fonte da verdade.
 *
 * Dinheiro é `String`: o backend serializa `Money` como "600.00" e recusa
 * float no request. A única exceção numérica é [CaixinhaResumo.progresso] /
 * [DetalheCaixinha.progresso], que é uma FRAÇÃO 0..1 derivada no servidor —
 * não é dinheiro.
 *
 * Nenhum número aqui é calculado: arrecadado, saldo em aberto, rendimento
 * devido — tudo vem pronto do servidor, que deriva dos movimentos
 * CONFIRMADO. O app nunca soma, nunca subtrai e nunca é fonte da verdade.
 *
 * Todos os parseadores usam `opt*` (mesma regra de `Models.kt`): chave
 * ausente vira campo vazio, não `JSONException` derrubando a tela inteira.
 */

// ── DTOs ─────────────────────────────────────────────────────────────────────

/** Linha de `GET /caixinhas` (minhas — de responsável ou de membro). */
data class CaixinhaResumo(
    val id: String,
    val nome: String,
    /** "dono" ou "membro" — decide a seção da lista ("Como responsável" / "Como membro"). */
    val papel: String,
    val publica: Boolean,
    val valorPedido: String,
    val taxaMensal: String,
    val arrecadado: String,
    /** Fração 0..1 do pedido — Float aqui é o contrato do servidor, não dinheiro. */
    val progresso: Float,
    /** Já formatada pelo servidor com vírgula ("4,5") ou `null` sem avaliações. */
    val mediaAvaliacoes: String?,
    val totalAvaliacoes: Int,
    val criadoEm: String,
)

/** Linha de `GET /caixinhas/publicas` (vitrine). */
data class CaixinhaPublica(
    val id: String,
    val nome: String,
    val donoNome: String,
    val taxaMensal: String,
    val valorPedido: String,
    val arrecadado: String,
    /** Quanto falta pro pedido — o servidor já subtraiu. */
    val falta: String,
    /** Já formatada pelo servidor com vírgula ("4,5") ou `null` sem avaliações. */
    val mediaAvaliacoes: String?,
    val totalAvaliacoes: Int,
    /** Só dígitos, ou `null` quando o responsável não informou. */
    val telefoneDono: String?,
    /** `true` quando quem pergunta é responsável ou membro desta caixinha. */
    val entrou: Boolean,
)

/** O responsável da caixinha, como o detalhe devolve. */
data class DonoCaixinha(
    val userId: String,
    val nome: String,
    val telefone: String?,
)

/**
 * Um membro dentro da caixinha com os números do mês — tudo derivado no
 * servidor. O membro vê só a própria linha; o responsável vê todos.
 */
data class MembroDetalhe(
    val userId: String,
    val nome: String,
    val telefone: String?,
    val aportado: String,
    val saldoEmAberto: String,
    val devidoMes: String,
    val pagoMes: String,
    val faltaMes: String,
    val rendimentoDisponivel: String,
    /** `true` quando há RENDIMENTO PROPOSTO deste membro neste mês. */
    val rendimentoPendente: Boolean,
)

/** Um movimento: aporte, rendimento ou devolução, proposto/confirmado/recusado/cancelado. */
data class MovimentoCaixinha(
    val id: String,
    /** "APORTE" | "RENDIMENTO" | "DEVOLUCAO". */
    val tipo: String,
    val membroId: String,
    val membroNome: String,
    val valor: String,
    /** "2026-10" (só em RENDIMENTO) ou `null`. */
    val competencia: String?,
    /** "PROPOSTO" | "CONFIRMADO" | "RECUSADO" | "CANCELADO". */
    val status: String,
    val propostoPor: String,
    val criadoEm: String,
    val confirmadoEm: String?,
)

/**
 * `CaixinhaDetalhe` do contrato: resposta de `GET /caixinhas/{id}` e de TODA
 * mutação — criar, entrar, propor, confirmar, avaliar. Quem muta aplica esta
 * resposta no estado em vez de reabrir o GET.
 *
 * `papel` vem do SERVIDOR (é o token que diz quem é você): "dono" ou
 * "membro". [convite] só existe para o dono (o convite ativo, nunca
 * histórico); os `total*Mes` só para o dono; `membros`/`movimentos` vêm
 * filtrados por papel.
 */
data class DetalheCaixinha(
    val id: String,
    val nome: String,
    val papel: String,
    val dono: DonoCaixinha,
    val publica: Boolean,
    val valorPedido: String,
    val taxaMensal: String,
    val previsaoQuitacao: String?,
    val arrecadado: String,
    val arrecadadoPendente: String,
    val disponivelAporte: String,
    /** Fração 0..1 do pedido — Float aqui é o contrato do servidor, não dinheiro. */
    val progresso: Float,
    /** Já formatada pelo servidor com vírgula ("4,5") ou `null` sem avaliações. */
    val mediaAvaliacoes: String?,
    val totalAvaliacoes: Int,
    /** Convite ativo — só o dono recebe. */
    val convite: String?,
    /** Nota que EU dei (membro) ou `null`. */
    val minhaAvaliacao: Int?,
    val membros: List<MembroDetalhe>,
    /** Totais do mês `?mes=` — só o dono recebe. */
    val totalDevidoMes: String?,
    val totalPagoMes: String?,
    val totalFaltaMes: String?,
    /** Movimentos PROPOSTO visíveis ∪ últimos 10 resolvidos, `criado_em DESC`. */
    val movimentos: List<MovimentoCaixinha>,
) {
    val ehDono: Boolean get() = papel == "dono"
}

// ── Funções puras (testáveis em JUnit sem org.json) ──────────────────────────

/**
 * Uma pendência do card "Para confirmar": um movimento PROPOSTO e de que
 * lado da mesa eu estou.
 *
 * [confirmavelPorMim] é `true` quando EU sou a contraparte — a tela mostra
 * Confirmar/Recusar. Quando é `false`, o movimento é meu (eu quem propôs):
 * a tela mostra "aguardando" e o botão Cancelar.
 */
data class PendenciaCaixinha(
    val movimento: MovimentoCaixinha,
    val confirmavelPorMim: Boolean,
) {
    /** Eu propus este movimento (a outra parte confirma): posso cancelar. */
    val cancelavelPorMim: Boolean get() = !confirmavelPorMim
}

/**
 * Deriva o card "Para confirmar" dos movimentos `PROPOSTO` do detalhe.
 *
 * Regra central do contrato — "quem paga propõe, quem recebe confirma":
 * - APORTE: proposto pelo membro, confirmado pelo RESPONSÁVEL;
 * - RENDIMENTO/DEVOLUCAO: propostos pelo responsável, confirmados pelo
 *   MEMBRO da linha.
 *
 * O servidor já filtra o que cada papel vê (responsável vê tudo, membro só
 * o próprio), então [DetalheCaixinha.papel] + tipo bastam para saber de que
 * lado eu estou — sem saber nenhum `user_id`.
 */
fun pendenciasCaixinha(detalhe: DetalheCaixinha): List<PendenciaCaixinha> =
    detalhe.movimentos
        .filter { it.status == "PROPOSTO" }
        .map { mov ->
            PendenciaCaixinha(
                movimento = mov,
                confirmavelPorMim = when (mov.tipo) {
                    // Aporte: quem confirma é o responsável.
                    "APORTE" -> detalhe.ehDono
                    // Rendimento e devolução: quem confirma é o membro da linha.
                    else -> !detalhe.ehDono
                },
            )
        }

/** Taxa do servidor ("3", "2.5" — sem zeros à direita) como o cliente lê ("3", "2,5"). */
fun formatarTaxa(taxa: String): String = taxa.trim().replace('.', ',')

/** Competência "2026-10" como o cliente lê: "10/2026". Texto inesperado volta como veio. */
fun mesCurto(competencia: String): String {
    val partes = competencia.trim().split("-")
    return if (partes.size == 2) "${partes[1]}/${partes[0]}" else competencia
}

/**
 * Fração 0..1 da VITRINE para o progress bar.
 *
 * O contrato da vitrine não manda `progresso` (só a lista e o detalhe mandam),
 * e o layout aprovado tem barra de progresso no card — então a fração é a
 * razão de DOIS números do servidor (`arrecadado`/`valor_pedido`). Não é
 * dinheiro e não é saldo: nenhum valor monetário é calculado aqui, e o
 * cálculo é `BigDecimal`, nunca `Double`.
 */
fun progressoVitrine(arrecadado: String, valorPedido: String): Float {
    val pedido = valorPedido.trim().replace(',', '.').toBigDecimalOrNull() ?: return 0f
    if (pedido.signum() != 1) return 0f
    val total = arrecadado.trim().replace(',', '.').toBigDecimalOrNull() ?: return 0f
    return total
        .divide(pedido, 4, RoundingMode.HALF_UP)
        .coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
        .toFloat()
}

// ── Parse dos DTOs ───────────────────────────────────────────────────────────

fun JSONObject.paraCaixinhaResumo() = CaixinhaResumo(
    id = optString("id"),
    nome = optString("nome"),
    papel = optString("papel"),
    publica = optBoolean("publica", false),
    valorPedido = optString("valor_pedido", "0.00"),
    taxaMensal = optString("taxa_mensal", "0"),
    arrecadado = optString("arrecadado", "0.00"),
    progresso = optDouble("progresso", 0.0).toFloat(),
    mediaAvaliacoes = anulavel("media_avaliacoes"),
    totalAvaliacoes = optInt("total_avaliacoes", 0),
    criadoEm = optString("criado_em"),
)

fun JSONObject.paraCaixinhaPublica() = CaixinhaPublica(
    id = optString("id"),
    nome = optString("nome"),
    donoNome = optString("dono_nome"),
    taxaMensal = optString("taxa_mensal", "0"),
    valorPedido = optString("valor_pedido", "0.00"),
    arrecadado = optString("arrecadado", "0.00"),
    falta = optString("falta", "0.00"),
    mediaAvaliacoes = anulavel("media_avaliacoes"),
    totalAvaliacoes = optInt("total_avaliacoes", 0),
    telefoneDono = anulavel("telefone_dono"),
    entrou = optBoolean("entrou", false),
)

private fun JSONObject.paraDonoCaixinha() = DonoCaixinha(
    userId = optString("user_id"),
    nome = optString("nome"),
    telefone = anulavel("telefone"),
)

fun JSONObject.paraMembroDetalhe() = MembroDetalhe(
    userId = optString("user_id"),
    nome = optString("nome"),
    telefone = anulavel("telefone"),
    aportado = optString("aportado", "0.00"),
    saldoEmAberto = optString("saldo_em_aberto", "0.00"),
    devidoMes = optString("devido_mes", "0.00"),
    pagoMes = optString("pago_mes", "0.00"),
    faltaMes = optString("falta_mes", "0.00"),
    rendimentoDisponivel = optString("rendimento_disponivel", "0.00"),
    rendimentoPendente = optBoolean("rendimento_pendente", false),
)

fun JSONObject.paraMovimentoCaixinha() = MovimentoCaixinha(
    id = optString("id"),
    tipo = optString("tipo"),
    membroId = optString("membro_id"),
    membroNome = optString("membro_nome"),
    valor = optString("valor", "0.00"),
    competencia = anulavel("competencia"),
    status = optString("status"),
    propostoPor = optString("proposto_por"),
    criadoEm = optString("criado_em"),
    confirmadoEm = anulavel("confirmado_em"),
)

fun JSONObject.paraDetalheCaixinha() = DetalheCaixinha(
    id = optString("id"),
    nome = optString("nome"),
    papel = optString("papel"),
    dono = optJSONObject("dono")?.paraDonoCaixinha() ?: DonoCaixinha("", "", null),
    publica = optBoolean("publica", false),
    valorPedido = optString("valor_pedido", "0.00"),
    taxaMensal = optString("taxa_mensal", "0"),
    previsaoQuitacao = anulavel("previsao_quitacao"),
    arrecadado = optString("arrecadado", "0.00"),
    arrecadadoPendente = optString("arrecadado_pendente", "0.00"),
    disponivelAporte = optString("disponivel_aporte", "0.00"),
    progresso = optDouble("progresso", 0.0).toFloat(),
    mediaAvaliacoes = anulavel("media_avaliacoes"),
    totalAvaliacoes = optInt("total_avaliacoes", 0),
    convite = anulavel("convite"),
    // `optInt` num JSON null devolve 0, não null: `isNull` primeiro é o que
    // separa "ainda não avaliei" de "avaliei com zero estrela" (que não existe).
    minhaAvaliacao = if (isNull("minha_avaliacao")) null else optInt("minha_avaliacao"),
    membros = optJSONArray("membros").paraObjetos { it.paraMembroDetalhe() },
    totalDevidoMes = anulavel("total_devido_mes"),
    totalPagoMes = anulavel("total_pago_mes"),
    totalFaltaMes = anulavel("total_falta_mes"),
    movimentos = optJSONArray("movimentos").paraObjetos { it.paraMovimentoCaixinha() },
)

/**
 * `optString` numa chave ausente devolve "", não null — `isNull` só é true
 * para JSON null explícito. Mesma regra de `Models.kt` (lá o helper é
 * private do arquivo; aqui também).
 *
 * `paraObjetos` (array → lista sem crash) é o `internal` de `Models.kt`,
 * reutilizado aqui.
 */
private fun JSONObject.anulavel(chave: String): String? =
    if (isNull(chave)) null else optString(chave).takeIf { it.isNotBlank() }
