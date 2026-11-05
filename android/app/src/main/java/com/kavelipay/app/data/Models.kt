package com.kavelipay.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Modelos de tela.
 *
 * Dinheiro é `String` aqui, nunca `Double`/`Float`. O backend já serializa
 * `"10.50"` como string e recusa float no request; converter para Double no
 * cliente reintroduziria a perda de precisão em exatamente onde a regra do
 * projeto diz para não ter.
 *
 * TODOS os campos usam `optString`/`optBoolean`, nunca `getString`/`getBoolean`.
 * `getString` lança `JSONException` quando a chave falta, e um parse que estoura
 * dentro de `carregarTudo` derruba a tela inteira sem nenhum erro 4xx — o
 * sintoma não parece com "resposta diferente do esperado", parece bug de app.
 * `optString` devolve "" e `optBoolean` devolve false, e a tela renderiza vazio.
 *
 * Os shapes abaixo foram conferidos contra o backend RODANDO (`GET /auth/me`,
 * `/wallet`, `/bills`, `/splits`, `/charges`, `/history`, `/profile/sessions`,
 * `/categories`, `/reports/monthly`, `/charges/{id}/qr`). Divergir do backend é
 * a forma mais fácil de o app crashar em silêncio.
 */

data class Carteira(
    val id: String,
    val moeda: String,
    val status: String,
    val saldo: String,
    val demonstrativa: Boolean,
)

data class ItemExtrato(
    val id: String,
    val tipo: String,
    val valor: String,
    val descricao: String,
    val isSaida: Boolean,
    val criadoEm: String,
    val categoria: String?,
    val corCategoria: String?,
)

data class Extrato(val itens: List<ItemExtrato>, val saldo: String)

data class ItemDerivacao(
    val transactionId: String,
    val tipo: String,
    val descricao: String?,
    val delta: String,
    val saldoAcumulado: String,
)

data class Derivacao(
    val balance: String,
    val openingBalance: String,
    val demonstrativa: Boolean,
    val formula: String,
    val itens: List<ItemDerivacao>,
)

data class ItemConta(
    val id: String,
    val descricao: String,
    val beneficiario: String?,
    val amount: String,
    val dueDate: String,
    val status: String,
    val isOverdue: Boolean,
)

data class ListaContas(val itens: List<ItemConta>, val balance: String)

data class Categoria(
    val id: String,
    val nome: String,
    val kind: String,
    val cor: String?,
    /** `true` para as 14 semeadas no cadastro. O backend sempre manda cor,
     *  então sem este campo a UI não distingue padrão de criada pelo usuário. */
    val padrao: Boolean = false,
)

data class Relatorio(
    val month: String?,
    val demonstrativa: Boolean,
    val income: String,
    val expense: String,
    val net: String,
    val balance: String,
    val fatiasDespesa: List<Fatia>,
)

data class Fatia(
    val nome: String,
    val total: String,
    val share: Double,
    val count: Long,
)

data class Notificacao(
    val id: String,
    val type: String,
    val title: String,
    val body: String?,
    val readAt: String?,
)

// ── Splits ────────────────────────────────────────────────────────────────────
data class ItemSplitParte(
    val id: String,
    val name: String,
    /**
     * O backend chama de `owed_amount`, não `amount`. Ler `amount` dava "" e a
     * tela mostrava "R$ 0,00" ao lado de uma parte que devia ter 50,00.
     */
    val amount: String,
    val settledAt: String?,
    /** Link público para o participante pagar no Mercado Pago. `null` quando quitada. */
    val link: String?,
)

data class ItemSplit(
    val id: String,
    val description: String,
    val totalAmount: String,
    val pendingAmount: String,
    val partes: List<ItemSplitParte>,
)

data class ListaSplits(val itens: List<ItemSplit>)

// ── Charges (Cobranças) ───────────────────────────────────────────────────────
data class ChargeQr(
    val payload: String,
    val format: String,
    val provider: String,
    // Link https para quem NÃO tem o app: abre a página de pagamento no navegador.
    // Vazio quando o backend ainda não devolve (cobrança criada, não o /qr).
    val link: String = "",
)

data class ItemCharge(
    val id: String,
    val description: String,
    val amount: String,
    val debtorName: String,
    val status: String,
    val qr: ChargeQr?,
)

data class ListaCharges(val itens: List<ItemCharge>)

/**
 * Cobrança como quem RECEBEU o link a vê (`GET /cobranca/{token}`).
 *
 * Não é `ItemCharge`: essa é a visão do dono (tem id, devedor, QR). Aqui só
 * existe o que a rota pública devolve — e o valor vem do servidor, nunca do
 * link. `cobrador`/`vencimento` são opcionais no backend.
 */
data class CobrancaPublica(
    val descricao: String,
    val valor: String,
    val moeda: String,
    val status: String,
    val vencimento: String?,
    val cobrador: String?,
)

// ── Dívidas (Eu devo) ─────────────────────────────────────────────────────────
/**
 * Uma dívida do usuário ("eu devo"), lado inverso de `ItemSplit`.
 *
 * `amount` é STRING — dinheiro na API é sempre string (`"50.50"`), nunca
 * número JSON; o formata/mostra aqui não converte para `Double`.
 * `paidAt`/`confirmedAt` só existem a partir do pago/confirmado; em
 * `PENDING` o backend manda `null`.
 */
data class ItemDivida(
    val id: String,
    val creditorName: String,
    val description: String,
    val amount: String,
    val status: String,
    val paidAt: String?,
    val confirmedAt: String?,
)

data class ListaDividas(val itens: List<ItemDivida>)

// ── Histórico ─────────────────────────────────────────────────────────────────
data class ItemHistorico(
    val kind: String,
    val title: String,
    val amount: String,
    val isSaida: Boolean,
    /** `occurred_at` da API — a data do evento, não a de criação da linha. */
    val criadoEm: String,
)

data class Historico(val itens: List<ItemHistorico>, val balance: String)

// ── Perfil / Sessões ──────────────────────────────────────────────────────────
/**
 * O backend devolve `user_id`, não `id` (ver `ProfileResponse` em
 * `routes/profile.rs`). Ler `id` era `getString` numa chave inexistente — ou
 * seja, `JSONException`, ou seja crash.
 */
data class Perfil(
    val id: String,
    val email: String,
    val displayName: String?,
)

/**
 * O backend devolve `expires_at`, e **não** tem `last_used_at` nem `current`.
 * Não inventar `current`: o backend não sabe qual das sessões é a atual, e
 * marcar a errada faria o botão de revogar sumir na sessão errada.
 */
data class Sessao(
    val id: String,
    val createdAt: String,
    val expiresAt: String,
)

data class ListaSessoes(val itens: List<Sessao>)

// ── Plano / Assinatura ────────────────────────────────────────────────────────
/**
 * Espelha o `PlanoResponse` de `routes/billing.rs`.
 *
 * `premium` é derivado no backend (`plano.is_some()`); [plano] é o nome do
 * plano vigente ("mensal"/"anual") e [periodoFim] é a data de término em ISO
 * 8601. Os preços chegam como **string** porque são `Money` no Rust — nunca
 * converter para `Double`.
 *
 * [accessToken] só vem no `POST /billing/ativar`: é o token novo com o claim
 * `premium`. O `GET /billing/plano` devolve o campo ausente.
 */
data class Plano(
    val premium: Boolean,
    val plano: String?,
    val periodoFim: String?,
    val recursosPremium: List<String>,
    val precoMensal: String,
    val precoAnual: String,
    val accessToken: String?,
)

/**
 * Conta do Mercado Pago que recebe as cobranças desta pessoa.
 *
 * `null` em [ContaMp] (estado da tela) = ainda não perguntamos; desconectado
 * de verdade é `conectado = false`.
 */
data class ContaMp(
    val conectado: Boolean,
    val mpUserId: Long?,
)

// ── Investimentos ─────────────────────────────────────────────────────────────

/**
 * Posição de um ativo.
 *
 * [quantity], [averagePrice] e [costBasis] vêm como `String` de propósito: são
 * `Decimal` no Rust com a escala do banco (`NUMERIC(19,4)` na quantidade,
 * `NUMERIC(19,4)` no custo), então o servidor devolve "10.00000000" e
 * "351.5000". Converter para `Double` no cliente reintroduziria a perda de
 * precisão que a regra do projeto proíbe — e o preço médio já vem
 * arredondado em 4 casas, que é o que a corretora mostra.
 */
data class Ativo(
    val id: String,
    val symbol: String,
    val nome: String,
    val kind: String,
    val notas: String?,
    val resumo: String?,
    val proximoDividendo: String?,
    /**
     * Preço unitário atual, digitado pelo usuário.
     *
     * `null` = desconhecido, e a tela diz isso em vez de mostrar R$ 0,00.
     * Não é cotação automática: no Brasil, exibir preço de ativo para ajudar
     * a decidir é assessor de investimento (CVM).
     */
    val precoAtual: String?,
    /** Preço atual × quantidade. `null` quando o preço é desconhecido. */
    val valorAtual: String?,
    /** Valor atual − custo. `null` quando o preço é desconhecido. */
    val ganhoNaoRealizado: String?,
    /** Retorno em fração (0,0810 = 8,10%). `null` quando custo é zero. */
    val retornoNaoRealizado: String?,
    val quantidade: String,
    val precoMedio: String,
    val custoTotal: String,
    val ganhoRealizado: String,
    val custosTotais: String,
    val dividendosTotais: String,
    val movimentosCount: Long,
)

/**
 * Resposta de `POST /investments/lookup`: a IA identificou (ou não) o código.
 *
 * `achou = false` é resposta, não erro — ativo inexistente faz o app não
 * preencher nada. `nome`/`resumo` só vêm preenchidos quando `achou` é true, e
 * `resumo` pode ser null mesmo assim: o backend descarta resumo com valor
 * monetário (a IA pode inventar "vale R$ 35,00"; o trigger da migration 0007
 * recusaria o INSERT na hora de salvar).
 *
 * `proximoDividendo` NÃO vem da IA — vem do mercado (brapi pra ação,
 * statusinvest pra FII e como reserva de ação). A IA barata inventa data de
 * dividendo com convicção; o calendário é fato verificável, e a data devolvida
 * é a de PAGAMENTO (não a data-ex). Sem data futura declarada, vem null.
 */
data class PesquisaAtivo(
    val achou: Boolean,
    val nome: String?,
    val resumo: String?,
    /** Próxima data de pagamento, se o ativo paga. Vai como o cliente
     *  digitou (dd/mm/aaaa ou AAAA-MM-DD) — o backend aceita os dois. */
    val proximoDividendo: String? = null,
)

/**
 * Sugestão de preço de `POST /investments/quote` (brapi/Yahoo).
 *
 * `preco` é STRING (`"56.00"`) — dinheiro na API nunca é número JSON, e aqui
 * também não vira `Double`: a tela só preenche se virar `BigDecimal` limpo.
 * `preco: null` = sem sugestão (renda fixa, ticker não listado, provedor
 * fora) — a tela continua dizendo "não informado".
 */
data class SugestaoPreco(val preco: String?, val fonte: String?)

data class MovimentoInvestimento(
    val id: String,
    val investimentoId: String,
    val tipo: String,
    val quantidade: String,
    val precoUnitario: String?,
    val valor: String,
    val custos: String,
    val data: String,
    val nota: String?,
)

data class CarteiraInvestimentos(
    val itens: List<Ativo>,
    val custoTotal: String,
    val ganhoRealizado: String,
    val dividendosTotais: String,
    val custosTotais: String,
    val posicoesCount: Long,
)

data class ResumoInvestimentos(
    val custoTotal: String,
    val ganhoRealizado: String,
    val dividendosTotais: String,
    val custosTotais: String,
    val posicoesCount: Long,
    /** Por que o app não mostra ganho não realizado — dito na tela, não escondido. */
    val notaGanhoNaoRealizado: String,
)

// ── Conversão ───────────────────────────────────────────────────────────────

fun JSONObject.paraCarteira() = Carteira(
    id = optString("wallet_id"),
    moeda = optString("currency", "BRL"),
    status = optString("status"),
    saldo = optString("balance", "0.00"),
    demonstrativa = optBoolean("is_demonstrative", true),
)

fun JSONObject.paraExtrato() = Extrato(
    itens = optJSONArray("items").paraObjetos { it.paraItemExtrato() },
    saldo = optString("balance", "0.00"),
)

fun JSONObject.paraItemExtrato() = ItemExtrato(
    id = optString("transaction_id"),
    tipo = optString("type"),
    valor = optString("amount", "0.00"),
    descricao = optString("description"),
    isSaida = optBoolean("is_saida"),
    criadoEm = optString("created_at"),
    categoria = anulavel("category"),
    corCategoria = anulavel("category_color"),
)

fun JSONObject.paraDerivacao() = Derivacao(
    balance = optString("balance", "0.00"),
    openingBalance = optString("opening_balance", "0.00"),
    demonstrativa = optBoolean("is_demonstrative", true),
    formula = optString("derivation"),
    itens = optJSONArray("items").paraObjetos { it.paraItemDerivacao() },
)

fun JSONObject.paraItemDerivacao() = ItemDerivacao(
    transactionId = optString("transaction_id"),
    tipo = optString("type"),
    descricao = anulavel("description"),
    delta = optString("delta", "0.00"),
    saldoAcumulado = optString("running_balance", "0.00"),
)

fun JSONObject.paraListaContas() = ListaContas(
    itens = optJSONArray("items").paraObjetos { it.paraItemConta() },
    balance = optString("balance", "0.00"),
)

fun JSONObject.paraItemConta() = ItemConta(
    id = optString("id"),
    descricao = optString("description"),
    beneficiario = anulavel("beneficiario"),
    amount = optString("amount", "0.00"),
    dueDate = optString("due_date"),
    status = optString("status"),
    isOverdue = optBoolean("is_overdue"),
)

fun JSONObject.paraRelatorio() = Relatorio(
    month = anulavel("month"),
    demonstrativa = optBoolean("is_demonstrative", true),
    income = optString("income", "0.00"),
    expense = optString("expense", "0.00"),
    net = optString("net", "0.00"),
    balance = optString("balance", "0.00"),
    fatiasDespesa = optJSONArray("by_expense_category").paraObjetos { it.paraFatia() },
)

fun JSONObject.paraFatia() = Fatia(
    nome = optString("name"),
    total = optString("total", "0.00"),
    share = optDouble("share", 0.0),
    count = optLong("count", 0),
)

fun JSONObject.paraNotificacao() = Notificacao(
    id = optString("id"),
    type = optString("type"),
    title = optString("title"),
    body = anulavel("body"),
    readAt = anulavel("read_at"),
)

// ── Splits ────────────────────────────────────────────────────────────────────
fun JSONObject.paraListaSplits() = ListaSplits(
    itens = optJSONArray("items").paraObjetos { it.paraItemSplit() }
)

fun JSONObject.paraItemSplit() = ItemSplit(
    id = optString("id"),
    description = optString("description"),
    totalAmount = optString("total_amount", "0.00"),
    pendingAmount = optString("pending_amount", "0.00"),
    partes = optJSONArray("parts").paraObjetos { it.paraItemSplitParte() }
)

fun JSONObject.paraItemSplitParte() = ItemSplitParte(
    id = optString("id"),
    name = optString("name"),
    amount = optString("owed_amount", "0.00"),
    settledAt = anulavel("settled_at"),
    link = anulavel("link"),
)

// ── Charges ────────────────────────────────────────────────────────────────────
fun JSONObject.paraListaCharges() = ListaCharges(
    itens = optJSONArray("items").paraObjetos { it.paraItemCharge() }
)

fun JSONObject.paraItemCharge() = ItemCharge(
    id = optString("id"),
    description = optString("description"),
    amount = optString("amount", "0.00"),
    debtorName = optString("debtor_name"),
    status = optString("status"),
    qr = if (isNull("qr")) null else optJSONObject("qr")?.paraChargeQr()
)

fun JSONObject.paraChargeQr() = ChargeQr(
    payload = optString("payload"),
    format = optString("format"),
    provider = optString("provider"),
    link = optString("link"),
)

fun JSONObject.paraCobrancaPublica() = CobrancaPublica(
    descricao = optString("description"),
    valor = optString("amount", "0.00"),
    moeda = optString("currency", "BRL"),
    status = optString("status"),
    // `null` explícito vira `optString` = "null" no org.json — testar `isNull`
    // antes é o que evita "De: null" na tela.
    vencimento = if (isNull("due_date")) null else optString("due_date").takeIf { it.isNotBlank() },
    cobrador = if (isNull("cobrador")) null else optString("cobrador").takeIf { it.isNotBlank() },
)

// ── Dívidas (Eu devo) ─────────────────────────────────────────────────────────
// `GET /debts` devolve array solto (`Json<Vec<DebtResponse>>`), como
// `/splits` — `dividas()` embrulha com `getItens` e lê `items` aqui.
fun JSONObject.paraListaDividas() = ListaDividas(
    itens = optJSONArray("items").paraObjetos { it.paraItemDivida() }
)

// Mesmo formato de `paraItemSplit`: chaves do backend em snake_case
// (`creditor_name`, `paid_at`, `confirmed_at`), opt* em tudo para parse que
// estoura não derrubar a tela inteira.
fun JSONObject.paraItemDivida() = ItemDivida(
    id = optString("id"),
    creditorName = optString("creditor_name"),
    description = optString("description"),
    amount = optString("amount", "0.00"),
    status = optString("status"),
    paidAt = anulavel("paid_at"),
    confirmedAt = anulavel("confirmed_at"),
)

// ── Histórico ──────────────────────────────────────────────────────────────────
fun JSONObject.paraHistorico() = Historico(
    itens = optJSONArray("items").paraObjetos { it.paraItemHistorico() },
    balance = optString("balance", "0.00"),
)

/**
 * `occurred_at`, não `created_at` — o histórico unifica transação, boleto,
 * cobrança e divisão, e o campo é a data do evento de cada um.
 */
fun JSONObject.paraItemHistorico() = ItemHistorico(
    kind = getString("kind"),
    title = getString("title"),
    amount = getString("amount"),
    isSaida = optBoolean("is_saida"),
    // `occurred_at`, não `created_at`: o feed unificado serve eventos que não
    // são linhas do ledger (uma cobrança recebida, um boleto cancelado), e o
    // campo se chama `occurred_at`.
    criadoEm = optString("occurred_at").ifBlank { optString("created_at") },
)

// ── Perfil / Sessões ──────────────────────────────────────────────────────────
fun JSONObject.paraPerfil() = Perfil(
    id = optString("user_id"),
    email = optString("email"),
    displayName = anulavel("display_name"),
)

fun JSONObject.paraListaSessoes() = ListaSessoes(
    itens = optJSONArray("items").paraObjetos { it.paraSessao() }
)

fun JSONObject.paraSessao() = Sessao(
    id = getString("id"),
    createdAt = getString("created_at"),
    expiresAt = getString("expires_at"),
)

/**
 * Parser do `PlanoResponse`. Usa `opt*` em tudo para uma resposta parcial não
 * derrubar a tela: sem plano vira grátis, sem preço vira "0.00" (string, nunca
 * float). `recursos_premium` é um array de strings, não de objetos — por isso
 * não usa `paraObjetos`.
 */
fun JSONObject.paraPlano(): Plano {
    val recursos = optJSONArray("recursos_premium")
    val recursosPremium = if (recursos == null) emptyList() else
        (0 until recursos.length()).mapNotNull {
            recursos.optString(it).takeIf { texto -> texto.isNotBlank() }
        }
    return Plano(
        premium = optBoolean("premium", false),
        plano = anulavel("plano"),
        periodoFim = anulavel("periodo_fim"),
        recursosPremium = recursosPremium,
        precoMensal = optString("preco_mensal", "0.00"),
        precoAnual = optString("preco_anual", "0.00"),
        accessToken = anulavel("access_token"),
    )
}

// ── Investimentos: conversão ──────────────────────────────────────────────────

// `Decimal` no Rust serializa como STRING (ver `money::Money::serialize`).
// Os campos numéricos da posição são `Decimal` direto, não `Money`, então
// chegam com a escala do banco: "10.00000000", "351.5000". `optString`
// converte os dois casos (string e número), então o cliente não quebra se o
// servidor mudar a escala.
fun JSONObject.paraAtivo() = Ativo(
    id = getString("id"),
    symbol = getString("symbol"),
    nome = getString("name"),
    kind = optString("kind", "STOCK"),
    notas = anulavel("notes"),
    resumo = anulavel("summary"),
    proximoDividendo = anulavel("next_dividend_at"),
    // `Decimal` do Rust serializa como string com a escala do banco:
    // "38.0000". `anulavel` devolve null quando o preço não foi preenchido —
    // é essa distinção que impede a tela de mostrar R$ 0,00 para "não sei".
    precoAtual = anulavel("current_price"),
    valorAtual = anulavel("market_value"),
    ganhoNaoRealizado = anulavel("unrealized_pnl"),
    retornoNaoRealizado = anulavel("unrealized_return"),
    quantidade = optString("quantity", "0"),
    precoMedio = optString("average_price", "0"),
    custoTotal = optString("cost_basis", "0"),
    ganhoRealizado = optString("realized_pnl", "0"),
    custosTotais = optString("fees_total", "0"),
    dividendosTotais = optString("dividends_total", "0"),
    movimentosCount = optLong("movements_count", 0),
)

fun JSONObject.paraPesquisaAtivo() = PesquisaAtivo(
    // `found` ausente é false: resposta malformada vira "não achei", que o
    // app ignora — nunca vira prefill errado.
    achou = optBoolean("found", false),
    nome = anulavel("name"),
    resumo = anulavel("summary"),
    // Data do mercado (brapi), não da IA. Ausente/null = não toque no campo.
    proximoDividendo = anulavel("next_dividend"),
)

/**
 * Sugestão de preço de `POST /investments/quote`. `preco` vem como string com
 * 2 casas ("56.00"); `null`/ausente = sem sugestão. A tela só preenche se a
 * string virar `BigDecimal` limpo — `R$ 56,00abc` não preenche nada.
 */
fun JSONObject.paraSugestaoPreco() = SugestaoPreco(
    preco = anulavel("price"),
    fonte = anulavel("source"),
)

fun JSONObject.paraMovimentoInvestimento() = MovimentoInvestimento(
    id = getString("id"),
    investimentoId = getString("investment_id"),
    tipo = getString("kind"),
    quantidade = optString("quantity", "0"),
    precoUnitario = anulavel("price_per_unit"),
    valor = optString("amount", "0.00"),
    custos = optString("fees", "0.00"),
    data = optString("traded_at"),
    nota = anulavel("note"),
)

fun JSONObject.paraCarteiraInvestimentos() = CarteiraInvestimentos(
    itens = optJSONArray("items").paraObjetos { it.paraAtivo() },
    custoTotal = optString("total_cost_basis", "0"),
    ganhoRealizado = optString("total_realized_pnl", "0"),
    dividendosTotais = optString("total_dividends", "0"),
    custosTotais = optString("total_fees", "0"),
    posicoesCount = optLong("positions_count", 0),
)

fun JSONObject.paraResumoInvestimentos() = ResumoInvestimentos(
    custoTotal = optString("total_cost_basis", "0.00"),
    ganhoRealizado = optString("total_realized_pnl", "0.00"),
    dividendosTotais = optString("total_dividends", "0.00"),
    custosTotais = optString("total_fees", "0.00"),
    posicoesCount = optLong("positions_count", 0),
    notaGanhoNaoRealizado = optString("unrealized_note"),
)

/**
 * `optString` numa chave ausente devolve **""**, não null — `isNull` só é true
 * para JSON null explícito. Sem esta distinção, `display_name: null` viraria
 * "" e a tela mostraria "(não definido)" para um nome que existe.
 */
private fun JSONObject.anulavel(chave: String): String? =
    if (isNull(chave)) null else optString(chave).takeIf { it.isNotBlank() }

/**
 * `JSONArray` nulo vira lista vazia, nunca crash: `optJSONArray` devolve null
 * quando a chave não existe, e um NPE em um campo opcional derrubaria a tela
 * inteira em vez de renderizar vazio.
 */
internal fun <T> JSONArray?.paraObjetos(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i ->
        optJSONObject(i)?.let(transform)
    }
}
/**
 * Oferta da vitrine de parceiros (`GET /parceiros`).
 *
 * NÃO TEM PREÇO, e a ausência é deliberada — ver o cabeçalho de
 * `migrations/0011_parceiros.sql`. O Mercado Livre bloqueia leitura
 * automática (403 na API, bot-block na página), então preço só existiria
 * digitado à mão; preço digitado à mão envelhece calado, e num app de
 * dinheiro número velho é pior que número ausente. A tela diz "ver preço na
 * loja" e o valor verdadeiro fica no destino.
 *
 * `destinoUrl` já vem com os parâmetros de rastreio gerados pela loja — o app
 * NÃO mexe neles (reescrever seria adulterar a atribuição de comissão).
 */
data class OfertaParceiro(
    val id: String,
    val loja: String,
    val titulo: String,
    val imagemUrl: String,
    val destinoUrl: String,
)

internal fun JSONObject.paraOfertaParceiro() = OfertaParceiro(
    id = getString("id"),
    loja = optString("loja"),
    titulo = optString("titulo"),
    // `optString` num campo JSON `null` devolve o TEXTO "null" em algumas
    // versões do org.json — e "null" não começa com https, então viraria
    // placeholder. A checagem explícita evita o card com imagem "carregando"
    // para sempre por causa de uma string inventada.
    imagemUrl = if (isNull("imagem_url")) "" else optString("imagem_url"),
    destinoUrl = optString("destino_url"),
)

/**
 * Uma página da vitrine.
 *
 * `total` é o que permite dizer "fim" com honestidade: sem ele, o app só
 * saberia que a última página veio menor que 10 — e uma página cheia de 10
 * seguida de nada pareceria erro de rede.
 */
data class PaginaParceiros(
    val itens: List<OfertaParceiro>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)

internal fun JSONObject.paraPaginaParceiros() = PaginaParceiros(
    itens = optJSONArray("itens").paraObjetos { it.paraOfertaParceiro() },
    total = optInt("total"),
    limit = optInt("limit", 10),
    offset = optInt("offset"),
)

fun JSONObject.paraContaMp(): ContaMp = ContaMp(
    conectado = optBoolean("conectado", false),
    mpUserId = if (isNull("mp_user_id")) null else optLong("mp_user_id"),
)
