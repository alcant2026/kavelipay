package com.kavelipay.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavelipay.app.data.ApiClient
import com.kavelipay.app.data.Ativo
import com.kavelipay.app.data.CarteiraInvestimentos
import com.kavelipay.app.data.Carteira
import com.kavelipay.app.data.Categoria
import com.kavelipay.app.data.Derivacao
import com.kavelipay.app.data.Extrato
import com.kavelipay.app.data.Historico
import com.kavelipay.app.data.ItemCharge
import com.kavelipay.app.data.ItemHistorico
import com.kavelipay.app.data.ItemSplit
import com.kavelipay.app.data.KaveliPayApi
import com.kavelipay.app.data.ListaContas
import com.kavelipay.app.data.ListaCharges
import com.kavelipay.app.data.ListaDividas
import com.kavelipay.app.data.ListaSessoes
import com.kavelipay.app.data.ListaSplits
import com.kavelipay.app.data.MovimentoInvestimento
import com.kavelipay.app.data.Perfil
import com.kavelipay.app.data.PesquisaAtivo
import com.kavelipay.app.data.Relatorio
import com.kavelipay.app.data.ResumoInvestimentos
import com.kavelipay.app.data.SessionStore
import com.kavelipay.app.data.SugestaoPreco
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Estado único da tela principal.
 *
 * Um `StateFlow` só, com tudo que a Home precisa. Telas separadas com estado
 * próprio seriam mais código sem ganho aqui: são ~5 telas e os dados se
 * sobrepõem (extrato e derivação vêm do mesmo lugar).
 */
data class EstadoApp(
    val autenticado: Boolean = false,
    val carregando: Boolean = false,
    val erro: String? = null,

    val carteira: Carteira? = null,
    val extrato: Extrato? = null,
    val derivacao: Derivacao? = null,
    val contas: ListaContas? = null,
    val relatorio: Relatorio? = null,
    val categorias: List<Categoria> = emptyList(),
    val naoLidas: Int = 0,

    // Novas features
    val splits: ListaSplits? = null,
    val splitDetalhe: ItemSplit? = null,
    val dividas: ListaDividas? = null,
    val charges: ListaCharges? = null,
    val chargeQr: ItemCharge? = null,
    val historico: Historico? = null,
    val perfil: Perfil? = null,
    val sessoes: ListaSessoes? = null,

    // Investimentos
    val investimentos: CarteiraInvestimentos? = null,
    val resumoInvestimentos: ResumoInvestimentos? = null,
    val ativoSelecionado: Ativo? = null,
    val movimentosAtivo: List<MovimentoInvestimento> = emptyList(),

    // formulário de novo lançamento
    val enviando: Boolean = false,
    val enviadoComSucesso: Boolean = false,
)

class AppViewModel(private val sessao: SessionStore) : ViewModel() {

    private val api = KaveliPayApi(sessao)

    private val _estado = MutableStateFlow(EstadoApp(autenticado = sessao.autenticado))
    val estado: StateFlow<EstadoApp> = _estado.asStateFlow()

    init {
        // O `ApiClient` é um `object`: sem esta injeção ele não sabe de onde
        // ler o refresh nem onde gravar o par novo, e um 401 levaria o app a
        // dizer "faça login" mesmo com sessão válida.
        ApiClient.sessao = sessao
        // Refresh revogado de verdade: o estado local já foi limpo pelo
        // `ApiClient`, aqui só é derrubar a tela para o login.
        ApiClient.aoRevogarSessao = { _estado.value = EstadoApp(autenticado = false) }

        if (sessao.autenticado) carregarTudo()
    }

    // ── Auth ─────────────────────────────────────────────────────────────────

    fun entrar(email: String, senha: String) = autenticar { api.login(email.trim(), senha) }

    fun registrar(email: String, senha: String, nome: String) =
        autenticar { api.registrar(email.trim(), senha, nome.trim()) }

    private fun autenticar(acao: suspend () -> String) {
        _estado.update { it.copy(carregando = true, erro = null) }
        viewModelScope.launch {
            runCatching { acao() }
                .onSuccess {
                    _estado.update { it.copy(carregando = false, erro = null) }
                    carregarTudo()
                }
                .onFailure { e ->
                    _estado.update { it.copy(carregando = false, erro = mensagemDe(e)) }
                }
        }
    }

    fun sair() {
        viewModelScope.launch {
            runCatching { api.logout() }
            _estado.value = EstadoApp(autenticado = false)
        }
    }

    fun limparErro() = _estado.update { it.copy(erro = null) }

    // ── Carga ────────────────────────────────────────────────────────────────

    fun carregarTudo() {
        _estado.update { it.copy(carregando = true, erro = null) }
        viewModelScope.launch {
            try {
                // Busca em paralelo: são 5 endpoints independentes e a latência
                // somada tornaria a primeira pintura lenta.
                val carteira = async { api.carteira() }
                val extrato = async { api.extrato(limite = 50) }
                val derivacao = async { api.derivacao(limite = 100) }
                val contas = async { api.contas() }
                val relatorio = async { api.relatorio() }

                // Gerar lembretes antes de ler: senão a lista vem vazia na
                // primeira abertura e o usuário acha que não tem aviso nenhum.
                runCatching { api.gerarNotificacoes() }
                val naoLidas = async { api.notificacoes(somenteNaoLidas = true).size }

                _estado.update {
                    it.copy(
                        carregando = false,
                        erro = null,
                        autenticado = true,
                        carteira = carteira.await(),
                        extrato = extrato.await(),
                        derivacao = derivacao.await(),
                        contas = contas.await(),
                        relatorio = relatorio.await(),
                        naoLidas = naoLidas.await(),
                    )
                }
            } catch (e: Exception) {
                // Token expirado ou rede caída: uma mensagem e o usuário
                // reautentica. Não mostrar tela branca.
                _estado.update { it.copy(carregando = false, erro = mensagemDe(e)) }
            }
        }
    }

    // ── Ações ───────────────────────────────────────────────────────────────

    /**
     * Categorias para o seletor. Falha silenciosa de propósito: sem lista, o
     * formulário continua utilizável (classificar é opcional) e um erro de
     * rede não pode impedir a pessoa de registrar o gasto.
     */
    fun carregarCategorias() {
        viewModelScope.launch {
            val lista = runCatching {
                api.categorias("INCOME") + api.categorias("EXPENSE")
            }.getOrDefault(emptyList())
            _estado.update { it.copy(categorias = lista) }
        }
    }

    fun registrarLancamento(
        tipo: String,
        amount: String,
        descricao: String,
        categoriaId: String?,
        onOk: () -> Unit,
    ) {
        _estado.update { it.copy(enviando = true, erro = null, enviadoComSucesso = false) }
        viewModelScope.launch {
            runCatching { api.novaTransacao(tipo, amount, descricao, categoriaId) }
                .onSuccess {
                    _estado.update { it.copy(enviando = false, enviadoComSucesso = true) }
                    carregarTudo()
                    onOk()
                }
                .onFailure { e ->
                    _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) }
                }
        }
    }

    fun pagarConta(id: String, comprovanteUrl: String?, onOk: () -> Unit = {}) {
        _estado.update { it.copy(enviando = true, erro = null) }
        viewModelScope.launch {
            runCatching { api.pagarConta(id, comprovanteUrl) }
                .onSuccess {
                    _estado.update { it.copy(enviando = false) }
                    carregarTudo()
                    onOk()
                }
                .onFailure { e ->
                    _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) }
                }
        }
    }

    fun marcarTodasLidas() {
        viewModelScope.launch {
            api.gerarNotificacoes()
            runCatching { api.notificacoes(somenteNaoLidas = true) }
                .onSuccess { restantes -> _estado.update { it.copy(naoLidas = restantes.size) } }
        }
    }

    // ── Splits ────────────────────────────────────────────────────────────────
    // Em `onSuccess { lista -> _estado.update { it.copy(splits = lista) } }` o
    // `it` de dentro é o EstadoApp; se a resposta também se chamar `it`, o
    // shadowing faz `copy(splits = it)` receber o EstadoApp e o Kotlin acusa
    // "inferred type is EstadoApp but ListaSplits? was expected". Nome explícito
    // na resposta é o que mantém os dois `it` separados.
    fun carregarSplits() {
        viewModelScope.launch {
            runCatching { api.splits() }
                .onSuccess { lista -> _estado.update { it.copy(splits = lista) } }
                .onFailure { e -> _estado.update { it.copy(erro = mensagemDe(e)) } }
        }
    }

    fun carregarSplitDetalhe(splitId: String) {
        viewModelScope.launch {
            // O backend devolve o split na lista; filtramos localmente.
            // Idealmente teria endpoint dedicado /splits/{id}.
            runCatching { api.splits() }
                .onSuccess { lista ->
                    lista.itens.firstOrNull { it.id == splitId }
                        ?.let { split -> _estado.update { it.copy(splitDetalhe = split) } }
                }
                .onFailure { e -> _estado.update { it.copy(erro = mensagemDe(e)) } }
        }
    }

    fun quitarParte(splitId: String, partId: String) {
        viewModelScope.launch {
            _estado.update { it.copy(enviando = true, erro = null) }
            runCatching { api.quitarParte(splitId, partId) }
                .onSuccess {
                    _estado.update { it.copy(enviando = false) }
                    carregarTudo()
                    carregarSplitDetalhe(splitId)
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    // ── Dívidas (Eu devo) ───────────────────────────────────────────────────
    // Espelham `carregarSplits`/`criarSplit`/`quitarParte`: runCatching na
    // camada do ViewModel, `enviando` para o botão, erro pt-BR do backend
    // (ApiClient já extrai a mensagem) virando `estado.erro` para a tela.

    fun carregarDividas() {
        viewModelScope.launch {
            runCatching { api.dividas() }
                .onSuccess { lista -> _estado.update { it.copy(dividas = lista) } }
                .onFailure { e -> _estado.update { it.copy(erro = mensagemDe(e)) } }
        }
    }

    /**
     * Cria dívida ("eu devo"). Não toca o ledger — pendente é só registro —
     * mas `carregarTudo` mantém a Home coerente caso volte para lá.
     */
    fun criarDivida(credor: String, valor: String, descricao: String, aoPronto: () -> Unit) {
        _estado.update { it.copy(enviando = true, erro = null) }
        viewModelScope.launch {
            runCatching { api.novaDivida(credor, valor, descricao) }
                .onSuccess {
                    _estado.update { it.copy(enviando = false) }
                    carregarTudo()
                    carregarDividas()
                    aoPronto()
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    /** Edita dívida pendente (PATCH); pago/confirmado é 409 do backend. */
    fun editarDivida(id: String, credor: String, valor: String, descricao: String, aoPronto: () -> Unit) {
        _estado.update { it.copy(enviando = true, erro = null) }
        viewModelScope.launch {
            runCatching { api.editarDivida(id, credor, valor, descricao) }
                .onSuccess {
                    _estado.update { it.copy(enviando = false) }
                    carregarDividas()
                    aoPronto()
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    /** Declara o pagamento: debita o ledger no servidor, então recarrega o saldo. */
    fun pagarDivida(id: String) {
        _estado.update { it.copy(enviando = true, erro = null) }
        viewModelScope.launch {
            runCatching { api.pagarDivida(id) }
                .onSuccess {
                    _estado.update { it.copy(enviando = false) }
                    carregarTudo()
                    carregarDividas()
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    /** O credor confirmou o recebimento: muda só status, sem dinheiro. */
    fun confirmarDivida(id: String) {
        _estado.update { it.copy(enviando = true, erro = null) }
        viewModelScope.launch {
            runCatching { api.confirmarDivida(id) }
                .onSuccess {
                    _estado.update { it.copy(enviando = false) }
                    carregarDividas()
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    /**
     * Apaga dívida pendente. `api.apagarDivida` é o único método sem
     * `runCatching` na camada de dados — o `runCatching` que importa é este,
     * senão um 409 ("só dívidas pendentes podem ser apagadas") derrubaria a
     * coroutine em vez de virar mensagem na tela.
     */
    fun apagarDivida(id: String) {
        _estado.update { it.copy(enviando = true, erro = null) }
        viewModelScope.launch {
            runCatching { api.apagarDivida(id) }
                .onSuccess {
                    _estado.update { it.copy(enviando = false) }
                    carregarDividas()
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    // ── Charges ───────────────────────────────────────────────────────────────
    fun carregarCharges() {
        viewModelScope.launch {
            runCatching { api.charges() }
                .onSuccess { lista -> _estado.update { it.copy(charges = lista) } }
                .onFailure { e -> _estado.update { it.copy(erro = mensagemDe(e)) } }
        }
    }

    /**
 * Carrega o QR de uma cobrança e combina com o item da lista.
 *
 * `GET /charges/{id}/qr` devolve só o payload, sem valor e sem devedor. Sem
 * combinar com o `ItemCharge` das `GET /charges`, a tela mostraria um QR sem
 * valor nem nome — o usuário não saberia o que está cobrando de quem.
 */
fun carregarChargeQr(chargeId: String) {
        viewModelScope.launch {
            runCatching { api.chargeQr(chargeId) to api.charges() }
                .onSuccess { (qr, lista) ->
                    val cobranca = lista.itens.firstOrNull { it.id == chargeId } ?: return@onSuccess
                    _estado.update {
                        it.copy(chargeQr = if (qr == null) cobranca else cobranca.copy(qr = qr))
                    }
                }
                .onFailure { e -> _estado.update { it.copy(erro = mensagemDe(e)) } }
        }
    }

    fun receberCharge(chargeId: String) {
        viewModelScope.launch {
            _estado.update { it.copy(enviando = true, erro = null) }
            runCatching { api.receberCharge(chargeId) }
                .onSuccess {
                    _estado.update { it.copy(enviando = false) }
                    carregarTudo()
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    fun cancelarCharge(chargeId: String) {
        viewModelScope.launch {
            _estado.update { it.copy(enviando = true, erro = null) }
            runCatching { api.cancelarCharge(chargeId) }
                .onSuccess {
                    _estado.update { it.copy(enviando = false) }
                    carregarTudo()
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    // ── Histórico ─────────────────────────────────────────────────────────────
    fun carregarHistorico(limite: Int = 50, kind: String? = null) {
        viewModelScope.launch {
            _estado.update { it.copy(enviando = true, erro = null) }
            runCatching { api.historico(limite, kind) }
                .onSuccess { h -> _estado.update { it.copy(enviando = false, historico = h) } }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    // ── Perfil / Sessões ──────────────────────────────────────────────────────
    fun carregarPerfilESessoes() {
        viewModelScope.launch {
            _estado.update { it.copy(enviando = true, erro = null) }
            runCatching { api.perfil() }
                .onSuccess { perfil ->
                    _estado.update { it.copy(enviando = false, perfil = perfil) }
                    // Carregar sessões após perfil
                    runCatching { api.sessoes() }
                        .onSuccess { lista -> _estado.update { it.copy(sessoes = lista) } }
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    fun atualizarPerfil(displayName: String?) {
        viewModelScope.launch {
            _estado.update { it.copy(enviando = true, erro = null) }
            runCatching { api.atualizarPerfil(displayName) }
                .onSuccess { perfil -> _estado.update { it.copy(enviando = false, perfil = perfil) } }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    fun trocarSenha(senhaAtual: String, novaSenha: String) {
        viewModelScope.launch {
            _estado.update { it.copy(enviando = true, erro = null) }
            runCatching { api.trocarSenha(senhaAtual, novaSenha) }
                .onSuccess { _estado.update { it.copy(enviando = false) } }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    fun revogarSessao(id: String) {
        viewModelScope.launch {
            _estado.update { it.copy(enviando = true, erro = null) }
            runCatching { api.revogarSessao(id) }
                .onSuccess {
                    _estado.update { it.copy(enviando = false) }
                    carregarPerfilESessoes()
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    fun revogarTodasSessoes() {
        viewModelScope.launch {
            _estado.update { it.copy(enviando = true, erro = null) }
            runCatching { api.revogarTodasSessoes() }
                .onSuccess {
                    _estado.update { it.copy(enviando = false) }
                    carregarPerfilESessoes()
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    // ── LGPD ──────────────────────────────────────────────────────────────────
    fun exportarDadosJson() {
        viewModelScope.launch {
            runCatching { api.lgpdExportJson() }
                .onSuccess { dados ->
                    val arquivo = salvarEmArquivo("kavelipay_dados.json", dados.toString(2))
                    _estado.update { it.copy(enviadoComSucesso = true, erro = "Dados salvos em $arquivo") }
                }
                .onFailure { e -> _estado.update { it.copy(erro = mensagemDe(e)) } }
        }
    }

    fun exportarDadosCsv() {
        viewModelScope.launch {
            runCatching { api.lgpdExportCsv() }
                .onSuccess { csv ->
                    val arquivo = salvarEmArquivo("kavelipay_extrato.csv", csv)
                    _estado.update { it.copy(enviadoComSucesso = true, erro = "CSV salvo em $arquivo") }
                }
                .onFailure { e -> _estado.update { it.copy(erro = mensagemDe(e)) } }
        }
    }

    fun eliminarConta(senha: String) {
        viewModelScope.launch {
            _estado.update { it.copy(enviando = true, erro = null) }
            runCatching { api.lgpdEliminarConta(senha) }
                .onSuccess {
                    _estado.update { it.copy(enviando = false) }
                    sair()
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    // ── Contas recorrentes ────────────────────────────────────────────────────
    fun gerarRecorrentes(monthsAhead: Int = 12) {
        viewModelScope.launch {
            _estado.update { it.copy(enviando = true, erro = null) }
            runCatching { api.gerarRecorrentes(monthsAhead) }
                .onSuccess {
                    _estado.update { it.copy(enviando = false) }
                    carregarTudo()
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    // ── Categorias: classificar transação ─────────────────────────────────────
    fun classificarTransacao(transactionId: String, categoryId: String) {
        viewModelScope.launch {
            runCatching { api.classificarTransacao(transactionId, categoryId) }
                .onSuccess { carregarTudo() }
                .onFailure { e -> _estado.update { it.copy(erro = mensagemDe(e)) } }
        }
    }

    // ── Criar split ───────────────────────────────────────────────────────────
    fun criarSplit(
        descricao: String,
        total: String,
        partes: List<Pair<String, String>>,
        onOk: () -> Unit,
    ) {
        _estado.update { it.copy(enviando = true, erro = null) }
        viewModelScope.launch {
            runCatching {
                api.novaSplit(
                    description = descricao,
                    totalAmount = total,
                    parts = partes.map { (nome, valor) ->
                        JSONObject().put("name", nome).put("amount", valor)
                    },
                )
            }
                .onSuccess {
                    _estado.update { it.copy(enviando = false) }
                    carregarTudo()
                    carregarSplits()
                    onOk()
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    // ── Criar cobrança ────────────────────────────────────────────────────────
    fun criarCharge(
        descricao: String,
        valor: String,
        devedor: String,
        onOk: (String) -> Unit,
    ) {
        _estado.update { it.copy(enviando = true, erro = null) }
        viewModelScope.launch {
            runCatching { api.novaCharge(descricao, valor, devedor) }
                .onSuccess { criada ->
                    _estado.update { it.copy(enviando = false) }
                    carregarTudo()
                    carregarCharges()
                    onOk(criada.id)
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    // ── Criar categoria ───────────────────────────────────────────────────────
    fun criarCategoria(
        nome: String,
        kind: String,
        cor: String?,
        onOk: () -> Unit,
    ) {
        _estado.update { it.copy(enviando = true, erro = null) }
        viewModelScope.launch {
            runCatching { api.novaCategoria(nome, kind, cor) }
                .onSuccess {
                    _estado.update { it.copy(enviando = false) }
                    carregarCategorias()
                    onOk()
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    // ── Investimentos ─────────────────────────────────────────────────────────
    fun carregarInvestimentos() {
        viewModelScope.launch {
            _estado.update { it.copy(enviando = true, erro = null) }
            runCatching { api.investimentos() }
                .onSuccess { c ->
                    _estado.update { it.copy(enviando = false, investimentos = c) }
                    // O resumo é uma segunda chamada: dá os totais já formatados em
                    // `Money` (2 casas), enquanto a lista vem com `Decimal` cru.
                    // Falhar aqui não pode esconder a lista.
                    runCatching { api.resumoInvestimentos() }
                        .onSuccess { r -> _estado.update { s -> s.copy(resumoInvestimentos = r) } }
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    fun carregarAtivo(id: String) {
        viewModelScope.launch {
            _estado.update { it.copy(enviando = true, erro = null) }
            runCatching { api.ativo(id) to api.movimentosInvestimento(id) }
                .onSuccess { (a, ms) ->
                    _estado.update {
                        it.copy(enviando = false, ativoSelecionado = a, movimentosAtivo = ms)
                    }
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    /**
     * Consulta a IA pra preencher nome/resumo do formulário novo.
     *
     * Silenciosa de propósito: é conveniência, não ação do formulário. Qualquer
     * falha (rede, 422, 503 da feature desligada) devolve `null` e a tela não
     * preenche nada — sem mensagem de erro, porque um problema aqui não pode
     * travar o cadastro que a pessoa veio fazer.
     */
    fun pesquisarAtivo(symbol: String, kind: String, aoReceber: (PesquisaAtivo?) -> Unit) {
        viewModelScope.launch {
            runCatching { api.pesquisarAtivo(symbol, kind) }
                .onSuccess { aoReceber(it) }
                .onFailure { aoReceber(null) }
        }
    }

    /**
     * Sugere preço de mercado para o campo "Preço atual". Silenciosa como a
     * IA: falha (rede, provedor fora) devolve `null` e a tela não preenche —
     * sugestão é conforto, nunca trava o que a pessoa veio fazer.
     */
    fun sugerirPreco(symbol: String, kind: String, aoReceber: (SugestaoPreco?) -> Unit) {
        viewModelScope.launch {
            runCatching { api.sugerirPreco(symbol, kind) }
                .onSuccess { aoReceber(it) }
                .onFailure { aoReceber(null) }
        }
    }

    fun criarInvestimento(
        symbol: String,
        nome: String,
        kind: String,
        resumo: String?,
        proximoDividendo: String?,
        onOk: () -> Unit,
    ) {
        _estado.update { it.copy(enviando = true, erro = null) }
        viewModelScope.launch {
            runCatching {
                api.novoInvestimento(
                    symbol = symbol,
                    nome = nome,
                    kind = kind,
                    resumo = resumo,
                    proximoDividendo = proximoDividendo,
                )
            }
                .onSuccess {
                    _estado.update { it.copy(enviando = false) }
                    carregarInvestimentos()
                    onOk()
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    fun atualizarAtivo(
        id: String,
        resumo: String?,
        proximoDividendo: String?,
        precoAtual: String? = null,
    ) {
        _estado.update { it.copy(enviando = true, erro = null) }
        viewModelScope.launch {
            runCatching {
                api.atualizarInvestimento(
                    id,
                    resumo = resumo,
                    proximoDividendo = proximoDividendo,
                    precoAtual = precoAtual,
                )
            }
                .onSuccess { atualizado ->
                    _estado.update { it.copy(enviando = false, ativoSelecionado = atualizado) }
                    carregarInvestimentos()
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    fun registrarMovimento(
        investimentoId: String,
        tipo: String,
        quantidade: String,
        precoUnitario: String?,
        valor: String,
        custos: String?,
        data: String,
        nota: String?,
        onOk: () -> Unit,
    ) {
        _estado.update { it.copy(enviando = true, erro = null) }
        viewModelScope.launch {
            runCatching {
                api.registrarMovimento(
                    investimentoId = investimentoId,
                    tipo = tipo,
                    quantidade = quantidade,
                    precoUnitario = precoUnitario,
                    valor = valor,
                    custos = custos,
                    data = data,
                    nota = nota,
                )
            }
                .onSuccess {
                    _estado.update { it.copy(enviando = false) }
                    // Recarrega TUDO: um movimento muda o saldo da carteira E o
                    // preço médio do ativo, e a Home mostra o primeiro. Deixar um
                    // dos dois desatualizado é mostrar um número que não
                    // corresponde ao outro.
                    carregarTudo()
                    carregarInvestimentos()
                    carregarAtivo(investimentoId)
                    onOk()
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    /** Salva o conteúdo em um arquivo na pasta Download e retorna o caminho. */
    private fun salvarEmArquivo(nome: String, conteudo: String): String {
        return try {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val arquivo = java.io.File(dir, nome)
            arquivo.writeText(conteudo)
            arquivo.absolutePath
        } catch (e: Exception) {
            "erro ao salvar: ${e.message}"
        }
    }
}

/**
 * Mensagem para a tela.
 *
 * Erro de 4xx é de negócio e o texto do backend é acionável ("senha deve ter ao
 * menos 8 caracteres"). Erro de rede é opaco no cliente e precisa virar uma
 * instrução, não um stack trace.
 */
fun mensagemDe(e: Throwable): String = when (e) {
    is ApiClient.ErroApi -> e.mensagem
    is ApiClient.ErroRede ->
        "Não consegui falar com o servidor. Confira se ele está rodando e sua conexão."
    else -> e.message ?: "Algo deu errado."
}
