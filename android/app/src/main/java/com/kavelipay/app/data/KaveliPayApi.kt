package com.kavelipay.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Falas da tela para a API. Um método por operação, sem abstração genérica:
 * com ~10 chamadas, isso é menos indireção e mais legível que um client
 * genérico.
 */
class KaveliPayApi(private val sessao: SessionStore) {

    private val token: String? get() = sessao.accessToken

    // ── Auth ─────────────────────────────────────────────────────────────────

    suspend fun login(email: String, senha: String): String {
        val r = ApiClient.post(
            "/auth/login",
            JSONObject().put("email", email).put("password", senha),
            null,
        )
        return guardar(r)
    }

    suspend fun registrar(email: String, senha: String, nome: String?): String {
        val corpo = JSONObject().put("email", email).put("password", senha)
        nome?.takeIf { it.isNotBlank() }?.let { corpo.put("display_name", it) }
        val r = ApiClient.post("/auth/register", corpo, null)
        return guardar(r)
    }

    suspend fun logout() {
        val refresh = sessao.refreshToken
        if (refresh != null) {
            // Revogar no servidor é o que importa; falhar aqui não deve
            // impedir o logout local.
            runCatching {
                ApiClient.post("/auth/logout", JSONObject().put("refresh_token", refresh), token)
            }
        }
        sessao.limpar()
    }

    /**
     * `POST /auth/refresh` chamado fora do fluxo automático.
     *
     * Não é usado pelo caminho comum: quando o `ApiClient`-renova-e-repete já
     * cuida de um 401, esta função seria uma porta de entrada para rotacionar o
     * refresh duas vezes. Fica disponível para depuração.
     */
    suspend fun renovarUmaVez(): Boolean {
        val refresh = sessao.refreshToken ?: return false
        return runCatching {
            val r = ApiClient.post(
                "/auth/refresh",
                JSONObject().put("refresh_token", refresh),
                null,
            )
            val access = r.getString("access_token")
            val novoRefresh = r.getString("refresh_token")
            sessao.salvar(access, novoRefresh)
            true
        }.getOrDefault(false)
    }

    private fun guardar(r: JSONObject): String {
        // `optString` aqui também: se o login devolver 200 sem token (bug de
        // backend), `getString` estoura e o app crasha em vez de mostrar erro.
        val access = r.optString("access_token")
        if (access.isBlank()) {
            throw ApiClient.ErroApi(200, "sem_token", "O servidor não devolveu token de acesso.")
        }
        sessao.salvar(access, r.optString("refresh_token"))
        return access
    }

    // ── Carteira e ledger ────────────────────────────────────────────────────

    suspend fun carteira(): Carteira = ApiClient.get("/wallet", token).paraCarteira()

    suspend fun extrato(limite: Int = 50, offset: Int = 0): Extrato =
        ApiClient.get("/wallet/transactions?limit=$limite&offset=$offset", token).paraExtrato()

    suspend fun derivacao(limite: Int = 100): Derivacao =
        ApiClient.get("/wallet/derivation?limit=$limite", token).paraDerivacao()

    /** `amount` vai como STRING. Float é recusado com 422 pelo backend. */
    suspend fun novaTransacao(
        tipo: String,
        amount: String,
        descricao: String?,
        categoryId: String? = null,
    ): String {
        val corpo = JSONObject().put("type", tipo).put("amount", amount)
        descricao?.takeIf { it.isNotBlank() }?.let { corpo.put("description", it) }
        categoryId?.let { corpo.put("category_id", it) }
        return ApiClient.post("/wallet/transactions", corpo, token).optString("balance", "0.00")
    }

    // ── Contas a pagar ───────────────────────────────────────────────────────

    suspend fun contas(
        status: String? = null,
        overdue: Boolean? = null,
    ): ListaContas {
        val q = buildList {
            status?.let { add("status=$it") }
            overdue?.let { add("overdue=$it") }
        }.joinToString("&")
        val caminho = if (q.isEmpty()) "/bills" else "/bills?$q"
        return ApiClient.get(caminho, token).paraListaContas()
    }

    suspend fun pagarConta(id: String, comprovanteUrl: String? = null): ItemConta {
        val corpo = JSONObject()
        comprovanteUrl?.takeIf { it.isNotBlank() }?.let { corpo.put("comprovante_url", it) }
        return ApiClient.post("/bills/$id/pay", corpo, token).paraItemConta()
    }

    suspend fun novaConta(
        descricao: String,
        amount: String,
        dueDate: String,
        beneficiario: String? = null,
        recurrence: String? = null,
        linhaDigitavel: String? = null,
    ): ItemConta {
        val corpo = JSONObject()
            .put("description", descricao)
            .put("amount", amount)
            .put("due_date", dueDate)
        beneficiario?.takeIf { it.isNotBlank() }?.let { corpo.put("beneficiario", it) }
        recurrence?.let { corpo.put("recurrence", it) }
        // Texto livre, sem interpretação — o backend guarda como está
        // (`docs/spec.md`, lacuna 12B × seção 9 resolvida: o app só lê).
        linhaDigitavel?.takeIf { it.isNotBlank() }?.let { corpo.put("linha_digitavel", it) }
        return ApiClient.post("/bills", corpo, token).paraItemConta()
    }

    // ── Categorias e relatórios ──────────────────────────────────────────────

    /**
     * `GET /parceiros?limit=&offset=` — vitrine de indicações de loja.
     *
     * Lista vazia quando não há oferta ativa: o app NÃO inventa card. Inventar
     * seria mostrar produto que não existe, com link que não leva a lugar
     * nenhum — e a vitrine é propaganda, então ela precisa ser verdadeira.
     *
     * A ordem vem do servidor (`criado_em DESC`): o que o admin cadastrou por
     * último aparece primeiro. Ordenar no app por `id` ou por título seria
     * outra ordem, e a vitrine passaria a discordar do que foi cadastrado.
     */
    suspend fun parceiros(limit: Int = 10, offset: Int = 0): PaginaParceiros =
        ApiClient.get("/parceiros?limit=$limit&offset=$offset", token).paraPaginaParceiros()

    suspend fun categorias(kind: String? = null): List<Categoria> {
            val caminho = if (kind == null) "/categories" else "/categories?kind=$kind"
            val arr = ApiClient.getArray(caminho, token)
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    Categoria(
                        id = it.getString("id"),
                        nome = it.getString("name"),
                        kind = it.getString("kind"),
                        cor = if (it.isNull("color")) null else it.optString("color"),
                        // Sem isto as 14 padrão aparecem como "Personalizada",
                        // porque o backend também dá cor a elas.
                        padrao = it.optBoolean("is_default", false),
                    )
                }
            }
        }

        /** `POST /categories` — cor tem que vir `#RRGGBB`, senão o backend é 422. */
        suspend fun novaCategoria(nome: String, kind: String, cor: String?): Categoria {
            val corpo = JSONObject().put("name", nome).put("kind", kind)
            cor?.takeIf { it.isNotBlank() }?.let { corpo.put("color", it) }
            val r = ApiClient.post("/categories", corpo, token)
            return Categoria(
                id = r.getString("id"),
                nome = r.getString("name"),
                kind = r.getString("kind"),
                cor = if (r.isNull("color")) null else r.optString("color"),
            )
        }

        /** `PUT /categories/reorder` — só o `display_order` muda por aqui. */
        suspend fun reordenarCategorias(ids: List<String>) {
            runCatching {
                ApiClient.put("/categories/reorder", JSONObject().put("ids", JSONArray(ids)), token)
            }
        }

    suspend fun relatorio(mes: String? = null): Relatorio {
        val caminho = if (mes == null) "/reports/monthly" else "/reports/monthly?month=$mes"
        return ApiClient.get(caminho, token).paraRelatorio()
    }

    // ── Notificações ─────────────────────────────────────────────────────────

    suspend fun notificacoes(somenteNaoLidas: Boolean = false): List<Notificacao> {
        val caminho = if (somenteNaoLidas) "/notifications?unread_only=true" else "/notifications"
        val arr = ApiClient.getArray(caminho, token)
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Notificacao(
                    id = it.optString("id"),
                    type = it.optString("type"),
                    title = it.optString("title"),
                    body = if (it.isNull("body")) null else it.optString("body"),
                    readAt = if (it.isNull("read_at")) null else it.optString("read_at"),
                )
            }
        }
    }

    suspend fun gerarNotificacoes() {
        // 204 sem corpo: `ApiClient` devolve JSONObject() e a tela ignora.
        runCatching { ApiClient.post("/notifications/generate", null, token) }
    }

    suspend fun marcarNotificacaoLida(id: String) {
        runCatching { ApiClient.post("/notifications/$id/read", null, token) }
    }

    suspend fun marcarTodasNotificacoesLidas() {
        runCatching { ApiClient.post("/notifications/read-all", null, token) }
    }

    // ── Splits (Divisão de despesa) ────────────────────────────────────────────
    // `GET /splits` devolve `Json<Vec<SplitResponse>>` — array solto, não
    // `{items: [...]}`. `getItens` embrulha para o modelo.
    suspend fun splits(): ListaSplits =
        ApiClient.getItens("/splits", token).paraListaSplits()

    suspend fun novaSplit(
        description: String,
        totalAmount: String,
        parts: List<JSONObject>,
    ): ItemSplit {
        val corpo = JSONObject()
            .put("description", description)
            .put("total_amount", totalAmount)
            .put("parts", JSONArray(parts))
        return ApiClient.post("/splits", corpo, token).paraItemSplit()
    }

    suspend fun quitarParte(splitId: String, partId: String): String =
        ApiClient.post("/splits/$splitId/parts/$partId/settle", null, token)
            .optString("balance", "0.00")

    // ── Charges (Cobranças) ───────────────────────────────────────────────────
    // `GET /charges` devolve array solto, como `/splits`.
    suspend fun charges(): ListaCharges =
        ApiClient.getItens("/charges", token).paraListaCharges()

    suspend fun novaCharge(
        description: String,
        amount: String,
        debtorName: String,
    ): ItemCharge {
        val corpo = JSONObject()
            .put("description", description)
            .put("amount", amount)
            .put("debtor_name", debtorName)
        return ApiClient.post("/charges", corpo, token).paraItemCharge()
    }

    /**
 * `GET /charges/{id}/qr` devolve o `PaymentPayload` na RAIZ (`{payload, format,
 * provider}`), não embrulhado em `{qr: ...}`. Ler `optJSONObject("qr")` daria
 * null sempre e a tela de QR ficaria vazia sem erro visível — que é a falha
 * mais difícil de ver num app.
 *
 * A resposta traz só o payload, sem valor nem devedor: a tela precisa do
 * `ItemCharge` da lista para mostrar "R$ 0,00 / Fulano". Por isso quem chama
 * combina os dois (ver `AppViewModel.carregarChargeQr`).
 */
suspend fun chargeQr(chargeId: String): ChargeQr? =
        ApiClient.get("/charges/$chargeId/qr", token)
            .takeIf { it.has("payload") }
            ?.paraChargeQr()

    suspend fun receberCharge(chargeId: String): String =
        ApiClient.post("/charges/$chargeId/receive", null, token)
            .optString("balance", "0.00")

    suspend fun cancelarCharge(chargeId: String) =
        runCatching { ApiClient.delete("/charges/$chargeId", null, token) }

    // ── Dívidas (Eu devo) ─────────────────────────────────────────────────────
    // `GET /debts` devolve array solto, como `/splits`. `user_id` não vai em
    // corpo nenhum: o backend lê do token.
    suspend fun dividas(): ListaDividas =
        ApiClient.getItens("/debts", token).paraListaDividas()

    /** `amount` vai como STRING (igual `novaSplit`); float é 422 no backend. */
    suspend fun novaDivida(credor: String, valor: String, descricao: String): ItemDivida {
        val corpo = JSONObject()
            .put("creditor_name", credor)
            .put("amount", valor)
            .put("description", descricao)
        return ApiClient.post("/debts", corpo, token).paraItemDivida()
    }

    /**
     * `PATCH /debts/{id}` — edição parcial no backend, mas a tela manda os
     * três campos juntos (os que mudaram); campos ausentes ficam como estão.
     * Só `PENDING`: pago é histórico e o backend responde 409.
     */
    suspend fun editarDivida(id: String, credor: String, valor: String, descricao: String): ItemDivida {
        val corpo = JSONObject()
            .put("creditor_name", credor)
            .put("amount", valor)
            .put("description", descricao)
        return ApiClient.patch("/debts/$id", corpo, token).paraItemDivida()
    }

    /** Declara o pagamento — debita o ledger no servidor. Corpo nulo, como `quitarParte`. */
    suspend fun pagarDivida(id: String): ItemDivida =
        ApiClient.post("/debts/$id/pay", null, token).paraItemDivida()

    /** O credor confirmou o recebimento: muda só status, sem ledger. */
    suspend fun confirmarDivida(id: String): ItemDivida =
        ApiClient.post("/debts/$id/confirm", null, token).paraItemDivida()

    /** Apaga só `PENDING` (sem estorno: pendente nunca tocou o ledger). Devolve a linha apagada. */
    suspend fun apagarDivida(id: String): ItemDivida =
        ApiClient.delete("/debts/$id", null, token).paraItemDivida()

    // ── Histórico unificado ───────────────────────────────────────────────────
    suspend fun historico(limite: Int = 50, kind: String? = null): Historico {
        // `?` e `&` decide se isto monta `/history?kind=BILL&limit=50` ou
        // `/history&limit=50` — a segunda não tem query string, e o servidor
        // devolve a lista inteira ignorando o filtro. Sem `kind` não entra
        // nenhum `?`, senão vira `?limit=50` com `&` pendurado.
        val q = buildList {
            kind?.let { add("kind=$it") }
            add("limit=$limite")
        }.joinToString("&")
        return ApiClient.get("/history?$q", token).paraHistorico()
    }

    /**
     * Não existe `GET /profile` no backend — a rota registrada é só `PATCH`
     * (ver `routes/mod.rs`). Chamar `GET` devolve **405**, não 404, e o
     * `ErroApi` com status 405 cai no `catch` genérico como "Algo deu errado".
     *
     * A leitura do perfil vem de `GET /auth/me`, que devolve
     * `{user_id, email, display_name}`.
     */
    suspend fun perfil(): Perfil =
        ApiClient.get("/auth/me", token).paraPerfil()

    // ── Perfil / Segurança ──────────────────────────────────────────────────────
    suspend fun atualizarPerfil(displayName: String?): Perfil {
        val corpo = JSONObject()
        displayName?.takeIf { it.isNotBlank() }?.let { corpo.put("display_name", it) }
        return ApiClient.patch("/profile", corpo, token).paraPerfil()
    }

    suspend fun trocarSenha(senhaAtual: String, novaSenha: String) =
        ApiClient.post("/profile/password",
            JSONObject().put("current_password", senhaAtual).put("new_password", novaSenha),
            token
        )

    // `GET /profile/sessions` devolve `Json<Vec<SessionResponse>>` — array solto.
    suspend fun sessoes(): ListaSessoes =
        ApiClient.getItens("/profile/sessions", token).paraListaSessoes()

    suspend fun revogarSessao(id: String) =
        runCatching { ApiClient.delete("/profile/sessions/$id", null, token) }

    suspend fun revogarTodasSessoes() =
        runCatching { ApiClient.post("/profile/sessions/revoke-all", null, token) }

    // ── Investimentos ─────────────────────────────────────────────────────────

    suspend fun investimentos(): CarteiraInvestimentos =
        ApiClient.get("/investments", token).paraCarteiraInvestimentos()

    suspend fun resumoInvestimentos(): ResumoInvestimentos =
        ApiClient.get("/investments/summary", token).paraResumoInvestimentos()

    /**
     * Pergunta à IA sobre o código antes de criar o ativo. A chave da
     * OpenRouter é do servidor — ela nunca passa pelo app.
     *
     * `achou = false` (ativo não existe) é resposta válida, não erro: o app
     * não preenche nada e a pessoa segue o cadastro na mão. Falha de verdade
     * (rede, 503 da feature desligada) o caller trata como "não preencha".
     */
    suspend fun pesquisarAtivo(symbol: String, kind: String): PesquisaAtivo {
        val corpo = JSONObject()
            .put("symbol", symbol)
            .put("kind", kind)
        return ApiClient.post("/investments/lookup", corpo, token).paraPesquisaAtivo()
    }

    /**
     * Sugestão de preço de `POST /investments/quote` (brapi para ação, Yahoo
     * para FII). Silenciosa como a IA: provedor fora, 503 ou rede morta
     * devolvem `SugestaoPreco(null, null)` e a tela não preenche nada.
     */
    suspend fun sugerirPreco(symbol: String, kind: String): SugestaoPreco {
        val corpo = JSONObject()
            .put("symbol", symbol)
            .put("kind", kind)
        return ApiClient.post("/investments/quote", corpo, token).paraSugestaoPreco()
    }

    suspend fun ativo(id: String): Ativo =
        ApiClient.get("/investments/$id", token).paraAtivo()

    suspend fun movimentosInvestimento(id: String): List<MovimentoInvestimento> {
        // Uma chamada só: chamar `getArray` dentro do `mapNotNull` faria N+1
        // requisições, e o `length()` viria de outra resposta.
        val arr = ApiClient.getArray("/investments/$id/movements", token)
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.paraMovimentoInvestimento()
        }
    }

    /**
     * [quantidade], [precoUnitario], [valor] e [custos] vão como STRING.
     *
     * O `Money` do backend recusa float com 422, e `quantity` é um `Decimal`
     * com 8 casas (ação fracionária). Converter para `Double` no cliente
     * reintroduziria a perda que a regra do projeto existe para impedir.
     */
    suspend fun novoInvestimento(
        symbol: String,
        nome: String,
        kind: String,
        notas: String? = null,
        resumo: String? = null,
        proximoDividendo: String? = null,
    ): Ativo {
        val corpo = JSONObject()
            .put("symbol", symbol)
            .put("name", nome)
            .put("kind", kind)
        notas?.takeIf { it.isNotBlank() }?.let { corpo.put("notes", it) }
        resumo?.takeIf { it.isNotBlank() }?.let { corpo.put("summary", it) }
        proximoDividendo?.takeIf { it.isNotBlank() }?.let { corpo.put("next_dividend_at", it) }
        return ApiClient.post("/investments", corpo, token).paraAtivo()
    }

    suspend fun atualizarInvestimento(
        id: String,
        nome: String? = null,
        resumo: String? = null,
        proximoDividendo: String? = null,
        precoAtual: String? = null,
    ): Ativo {
        val corpo = JSONObject()
        nome?.takeIf { it.isNotBlank() }?.let { corpo.put("name", it) }
        resumo?.takeIf { it.isNotBlank() }?.let { corpo.put("summary", it) }
        // String vazia LIMPA o campo: é assim que o usuário apaga a data que
        // digitou, ou o preço que não vale mais. Ausente = não mexe.
        proximoDividendo?.let { corpo.put("next_dividend_at", it) }
        precoAtual?.let { corpo.put("current_price", it) }
        return ApiClient.patch("/investments/$id", corpo, token).paraAtivo()
    }

    /**
     * Registra compra, venda ou dividendo.
     *
     * `precoUnitario` é obrigatório em BUY e SELL e proibido em DIVIDEND — o
     * backend recusa com 422, então a tela precisa mandar o certo.
     */
    suspend fun registrarMovimento(
        investimentoId: String,
        tipo: String,
        quantidade: String,
        precoUnitario: String?,
        valor: String,
        custos: String?,
        data: String,
        nota: String? = null,
    ): MovimentoInvestimento {
        val corpo = JSONObject()
            .put("kind", tipo)
            .put("quantity", quantidade)
            .put("amount", valor)
            .put("traded_at", data)
        precoUnitario?.takeIf { it.isNotBlank() }?.let { corpo.put("price_per_unit", it) }
        custos?.takeIf { it.isNotBlank() }?.let { corpo.put("fees", it) }
        nota?.takeIf { it.isNotBlank() }?.let { corpo.put("note", it) }
        return ApiClient
            .post("/investments/$investimentoId/movements", corpo, token)
            .paraMovimentoInvestimento()
    }

    // ── LGPD ──────────────────────────────────────────────────────────────────
    suspend fun lgpdExportCsv(): String =
        ApiClient.getText("/lgpd/export.csv", token)

    suspend fun lgpdExportJson(): JSONObject =
        ApiClient.get("/lgpd/export", token)

    suspend fun lgpdEliminarConta(senha: String) =
        runCatching {
            ApiClient.delete("/lgpd/account",
                JSONObject().put("password", senha).put("confirm", true), token)
        }

    // ── Contas recorrentes ────────────────────────────────────────────────────
    suspend fun gerarRecorrentes(monthsAhead: Int = 12): Int =
        ApiClient.post("/bills/generate-recurring",
            JSONObject().put("months_ahead", monthsAhead), token)
            .optInt("created", 0)

    // ── Categorias: classificar transação ──────────────────────────────────────
    suspend fun classificarTransacao(transactionId: String, categoryId: String) =
        ApiClient.patch("/wallet/transactions/$transactionId/category",
            JSONObject().put("category_id", categoryId), token)
}

/**
 * `GET /cobranca/{token}` — a cobrança vista por quem recebeu o link.
 *
 * Fora da classe de propósito: é a ÚNICA chamada que não usa sessão. Quem
 * recebe uma cobrança pode não ter conta (ou estar deslogado), então a tela
 * pública não pode depender de `SessionStore` — e o token do link, não o
 * login, é o que autoriza a leitura.
 *
 * O valor que aparece na tela vem daqui, do servidor. O link carrega só o
 * token: nada no QR é fonte da verdade.
 */
suspend fun buscarCobrancaPublica(shareToken: String): CobrancaPublica =
    ApiClient.get("/cobranca/$shareToken", null).paraCobrancaPublica()
