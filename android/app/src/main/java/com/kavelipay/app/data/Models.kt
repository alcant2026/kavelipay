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

fun JSONObject.paraContaMp(): ContaMp = ContaMp(
    conectado = optBoolean("conectado", false),
    mpUserId = if (isNull("mp_user_id")) null else optLong("mp_user_id"),
)
