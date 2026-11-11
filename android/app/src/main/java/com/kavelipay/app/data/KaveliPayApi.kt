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
        val r = try {
            ApiClient.post(
                "/auth/login",
                JSONObject().put("email", email).put("password", senha),
                null,
            )
        } catch (e: ApiClient.ErroApi) {
            // O backend responde 401 com "não autorizado" — jargão de HTTP que
            // não diz nada a quem está na tela de login. A mesma mensagem serve
            // para token vencido em qualquer outra chamada, então a tradução
            // fica aqui, no único ponto onde 401 significa senha errada.
            if (e.status != 401) throw e
            throw ApiClient.ErroApi(401, e.codigo, "E-mail ou senha inválidos")
        }
        return guardar(r)
    }

    suspend fun registrar(email: String, senha: String, nome: String?): String {
        val corpo = JSONObject().put("email", email).put("password", senha)
        nome?.takeIf { it.isNotBlank() }?.let { corpo.put("display_name", it) }
        val r = ApiClient.post("/auth/register", corpo, null)
        return guardar(r)
    }

    /**
     * `POST /auth/forgot-password`.
     *
     * A resposta é sempre a mesma, com conta ou sem: o app não pode dizer "e-mail
     * não cadastrado" — isso entregaria a lista de usuários para quem tentasse.
     */
    suspend fun esquecerSenha(email: String) {
        ApiClient.post("/auth/forgot-password", JSONObject().put("email", email), null)
    }

    /** `POST /auth/reset-password` — troca a senha com o código que chegou por e-mail. */
    suspend fun redefinirSenha(codigo: String, novaSenha: String) {
        ApiClient.post(
            "/auth/reset-password",
            JSONObject().put("token", codigo.trim()).put("new_password", novaSenha),
            null,
        )
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

    /** HTML do relatório (Premium), para salvar em Downloads e imprimir em PDF. */
    suspend fun relatorioHtml(mes: String? = null): String {
        val caminho = if (mes == null) {
            "/reports/monthly/relatorio"
        } else {
            "/reports/monthly/relatorio?month=$mes"
        }
        return ApiClient.getText(caminho, token)
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

    /**
     * `POST /charges/{id}/pagamento` — cria a preferência do Checkout Pro e
     * devolve o `init_point` (página de pagamento do Mercado Pago).
     *
     * Gerar o link NÃO marca a cobrança como recebida: quem confirma é o
     * webhook do MP, no backend. Um botão que marcasse aqui seria fingir
     * pagamento.
     */
    suspend fun linkPagamento(chargeId: String): String =
        ApiClient.post("/charges/$chargeId/pagamento", null, token)
            .optString("init_point")

    suspend fun cancelarCharge(chargeId: String) =
        runCatching { ApiClient.delete("/charges/$chargeId", null, token) }

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

    // ── Assinatura premium ──────────────────────────────────────────────────────

    /**
     * `GET /billing/plano` — o que a pessoa tem hoje e quanto custa.
     *
     * Grátis e sempre 200: quem pode ver é quem está no token (`user_id`).
     */
    suspend fun plano(): Plano = ApiClient.get("/billing/plano", token).paraPlano()

    /**
     * `POST /billing/ativar` — troca o código de ativação por período.
     *
     * O backend devolve um access token NOVO, já com o claim `premium`. Sem
     * trocar o token guardado, o app continuaria sem direito até o próximo
     * refresh — e o relatório, que é justamente o que a pessoa pagou para ver,
     * voltaria a dizer "plano Premium".
     */
    suspend fun ativarPlano(codigo: String): Plano {
        val resposta = ApiClient.post("/billing/ativar", JSONObject().put("codigo", codigo.trim()), token)
        val plano = resposta.paraPlano()
        val refresh = sessao.refreshToken
        if (!plano.accessToken.isNullOrBlank() && refresh != null) {
            sessao.salvar(plano.accessToken, refresh)
        }
        return plano
    }

    /**
     * `POST /billing/checkout` — devolve a URL da página de pagamento.
     *
     * Quem decide o preço é o backend (o app só diz qual plano). O pagamento
     * em si acontece na página do Mercado Pago, aberta num WebView.
     */
    suspend fun checkoutPlano(plano: String): String {
        val resposta = ApiClient.post("/billing/checkout", JSONObject().put("plano", plano), token)
        return resposta.getString("url")
    }

    // ── Conta do Mercado Pago (recebe as cobranças) ───────────────────────────

    /** `GET /mercadopago/status` — se o dinheiro das cobranças cai nesta conta. */
    suspend fun contaMp(): ContaMp = ApiClient.get("/mercadopago/status", token).paraContaMp()

    /**
     * `GET /mercadopago/link` — URL de autorização do Mercado Pago.
     *
     * Buscada com o token no header porque num WebView não dá para mandar
     * header na navegação; o app abre só o domínio do Mercado Pago.
     */
    suspend fun linkMp(): String = ApiClient.get("/mercadopago/link", token).getString("url")

    suspend fun desconectarMp() {
        ApiClient.delete("/mercadopago/conta", null, token)
    }

    // ── LGPD ──────────────────────────────────────────────────────────────────
    suspend fun lgpdExportCsv(): String =
        ApiClient.getText("/lgpd/export.csv", token)

    suspend fun lgpdEliminarConta(senha: String) =
        runCatching {
            ApiClient.delete("/lgpd/account",
                JSONObject().put("password", senha).put("confirm", true), token)
        }

    // ── Categorias: classificar transação ──────────────────────────────────────
    suspend fun classificarTransacao(transactionId: String, categoryId: String) =
        ApiClient.patch("/wallet/transactions/$transactionId/category",
            JSONObject().put("category_id", categoryId), token)

    // ── Caixinhas (empréstimo entre conhecidos, fora do ledger) ────────────────
    // `GET /caixinhas` é array solto, como `/splits`. Toda mutação devolve o
    // detalhe completo; `mes` é o mês local do aparelho (o backend defaulta UTC).
    suspend fun caixinhas(): ListaCaixinhas =
        ListaCaixinhas(ApiClient.getArray("/caixinhas", token).paraObjetos { it.paraItemCaixinha() })

    suspend fun caixinha(id: String, mes: String): DetalheCaixinha =
        ApiClient.get("/caixinhas/$id?mes=$mes", token).paraDetalheCaixinha()

    suspend fun criarCaixinha(
        nome: String,
        valorPedido: String,
        taxaMensal: String,
        previsaoQuitacao: String?,
        telefone: String?,
        mes: String,
    ): DetalheCaixinha {
        val corpo = JSONObject()
            .put("nome", nome)
            .put("valor_pedido", valorPedido)
            .put("taxa_mensal", taxaMensal)
        previsaoQuitacao?.let { corpo.put("previsao_quitacao", it) }
        telefone?.let { corpo.put("telefone", it) }
        return ApiClient.post("/caixinhas?mes=$mes", corpo, token).paraDetalheCaixinha()
    }

    suspend fun entrarCaixinha(chave: String, mes: String): DetalheCaixinha =
        ApiClient.post("/caixinhas/entrar?mes=$mes", JSONObject().put("chave", chave), token)
            .paraDetalheCaixinha()

    suspend fun proporAporte(id: String, amount: String, mes: String): DetalheCaixinha =
        ApiClient.post("/caixinhas/$id/aportes?mes=$mes",
            JSONObject().put("amount", amount), token).paraDetalheCaixinha()

    suspend fun proporRendimento(
        id: String,
        membroId: String,
        competencia: String,
        mes: String,
    ): DetalheCaixinha =
        ApiClient.post("/caixinhas/$id/rendimentos?mes=$mes",
            JSONObject().put("membro_id", membroId).put("competencia", competencia), token)
            .paraDetalheCaixinha()

    suspend fun proporDevolucao(
        id: String,
        membroId: String,
        amount: String,
        mes: String,
    ): DetalheCaixinha =
        ApiClient.post("/caixinhas/$id/devolucoes?mes=$mes",
            JSONObject().put("membro_id", membroId).put("amount", amount), token)
            .paraDetalheCaixinha()

    suspend fun confirmarMovimento(id: String, movimentoId: String, mes: String): DetalheCaixinha =
        ApiClient.post("/caixinhas/$id/movimentos/$movimentoId/confirmar?mes=$mes", null, token)
            .paraDetalheCaixinha()

    suspend fun recusarMovimento(id: String, movimentoId: String, mes: String): DetalheCaixinha =
        ApiClient.post("/caixinhas/$id/movimentos/$movimentoId/recusar?mes=$mes", null, token)
            .paraDetalheCaixinha()

    suspend fun cancelarMovimento(id: String, movimentoId: String, mes: String): DetalheCaixinha =
        ApiClient.post("/caixinhas/$id/movimentos/$movimentoId/cancelar?mes=$mes", null, token)
            .paraDetalheCaixinha()

    suspend fun salvarTelefoneCaixinha(id: String, telefone: String, mes: String): DetalheCaixinha =
        ApiClient.patch("/caixinhas/$id/meu-telefone?mes=$mes",
            JSONObject().put("telefone", telefone), token).paraDetalheCaixinha()
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
