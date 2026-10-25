package com.kavelipay.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavelipay.app.data.ApiClient
import com.kavelipay.app.data.Ativo
import com.kavelipay.app.data.CarteiraInvestimentos
import com.kavelipay.app.data.Carteira
import com.kavelipay.app.data.CaixinhaPublica
import com.kavelipay.app.data.CaixinhaResumo
import com.kavelipay.app.data.Categoria
import com.kavelipay.app.data.Derivacao
import com.kavelipay.app.data.DetalheCaixinha
import com.kavelipay.app.data.Extrato
import com.kavelipay.app.data.Historico
import com.kavelipay.app.data.ItemCharge
import com.kavelipay.app.data.ItemHistorico
import com.kavelipay.app.data.ItemSplit
import com.kavelipay.app.data.Exportador
import com.kavelipay.app.data.KaveliPayApi
import com.kavelipay.app.data.ListaContas
import com.kavelipay.app.data.ListaCharges
import com.kavelipay.app.data.ListaDividas
import com.kavelipay.app.data.ListaSessoes
import com.kavelipay.app.data.ListaSplits
import com.kavelipay.app.data.MovimentoInvestimento
import com.kavelipay.app.data.Notificacao
import com.kavelipay.app.data.OfertaParceiro
import com.kavelipay.app.data.Perfil
import com.kavelipay.app.data.PesquisaAtivo
import com.kavelipay.app.data.Plano
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
import kotlinx.coroutines.supervisorScope
import org.json.JSONObject
import java.time.Instant
import java.time.YearMonth

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

    /**
     * Código lido pelo leitor de código de barras, esperando a tela de Nova
     * conta consumir. Vive no estado porque o leitor é uma tela separada e
     * volta por navegação — mais simples que sair passando callback entre
     * destinos do grafo.
     */
    val linhaDigitavelLida: String? = null,

    // Novas features
    val splits: ListaSplits? = null,
    val splitDetalhe: ItemSplit? = null,
    val dividas: ListaDividas? = null,
    val charges: ListaCharges? = null,
    val chargeQr: ItemCharge? = null,
    val historico: Historico? = null,
    val perfil: Perfil? = null,
    val sessoes: ListaSessoes? = null,

    /**
     * Plano vigente (grátis ou premium). `null` = ainda não foi lido.
     *
     * `GET /billing/plano` é grátis e devolve 200 sempre — por isso o estado
     * existe: a tela de plano e o perfil precisam saber se a pessoa já tem
     * acesso sem perguntar de novo a cada abertura.
     */
    val plano: Plano? = null,

    /**
     * `true` enquanto o plano está sendo lido. A tela precisa distinguir
     * "ainda carregando" de "não deu para carregar" — senão a pessoa fica
     * olhando para um indicador que nunca acaba.
     */
    val carregandoPlano: Boolean = false,

    /**
     * O backend respondeu 402 num recurso pago (relatório mensal).
     *
     * A tela de relatório usa isto para mostrar o convite ao plano em vez de
     * uma tela de zeros — que parece "você não gastou nada" e não "isso é
     * pago".
     */
    val planoNecessario: Boolean = false,

    /**
     * Retorno da ativação do plano, como o `avisoPerfil` da exportação.
     *
     * Separado de `erro` de propósito: "Plano ativado" é sucesso e não pode
     * sair em vermelho como se fosse falha.
     */
    val avisoPlano: String? = null,
    val avisoPlanoFalhou: Boolean = false,

    // Notificações (tela dedicada; o contador `naoLidas` existe desde o início)
    val notificacoes: List<Notificacao>? = null,

    /**
     * A geração de lembretes (`POST /notifications/generate`) falhou. Erro
     * discreto de propósito NÃO entra em `erro`: o resto da carga da Home
     * funcionou, e o usuário só precisa saber que o aviso pode não vir.
     */
    val lembretesIndisponiveis: Boolean = false,

    // Investimentos
    val investimentos: CarteiraInvestimentos? = null,
    val resumoInvestimentos: ResumoInvestimentos? = null,
    val ativoSelecionado: Ativo? = null,
    val movimentosAtivo: List<MovimentoInvestimento> = emptyList(),

    /**
     * Vitrine de parceiros. Vive no estado (como as categorias) porque a tela
     * de oferta é um destino de navegação: ela recebe o `id` pela rota e
     * precisa achar a oferta já carregada, sem refazer a chamada. Ficar vazia
     * por falha de rede é aceitável — propaganda fora do ar não pode derrubar
     * a Home.
     */
    val parceiros: List<OfertaParceiro> = emptyList(),

    /** Quantas ofertas ativas existem no total (diz se há próxima página). */
    val parceirosTotal: Int = 0,

    /** "Carregar mais" em andamento — evita pedir a mesma página duas vezes. */
    val carregandoMaisParceiros: Boolean = false,

    /**
     * Resultado da última ação do Perfil (hoje: exportação de dados).
     *
     * Separado de `erro` de propósito. Antes a exportação escrevia "Dados
     * salvos em …" dentro de `erro`, e a tela precisava ADIVINHAR pelo texto
     * (`contains("salvo")`) se aquilo era sucesso ou falha — heurística que
     * quebra na primeira mensagem nova. Agora o tipo diz o que é.
     */
    val avisoPerfil: String? = null,
    val avisoPerfilFalhou: Boolean = false,

    // ── Caixinha ──────────────────────────────────────────────────────────────
    /** `GET /caixinhas` — minhas (de responsável e de membro). `null` = nunca carregou. */
    val caixinhas: List<CaixinhaResumo>? = null,
    val carregandoCaixinhas: Boolean = false,
    /** Erro de LEITURA da lista: a tela mostra com "Tentar de novo". */
    val erroCaixinhas: String? = null,

    /** `GET /caixinhas/publicas` — vitrine. `null` = nunca carregou. */
    val caixinhasPublicas: List<CaixinhaPublica>? = null,
    val carregandoVitrine: Boolean = false,
    val erroVitrine: String? = null,

    /**
     * Detalhe da caixinha aberta — vem do `GET /caixinhas/{id}` e de cada
     * mutação (que devolve o detalhe atualizado; o contrato manda aplicar,
     * não refazer GET).
     */
    val caixinhaDetalhe: DetalheCaixinha? = null,
    val carregandoDetalheCaixinha: Boolean = false,
    val erroDetalheCaixinha: String? = null,

    /** Mutação em andamento (botões das telas de caixinha). */
    val enviandoCaixinha: Boolean = false,
    /**
     * Erro da última MUTAÇÃO (criar, entrar, propor, confirmar…). As strings
     * do contrato já são pt-BR amigáveis — a tela mostra como vieram.
     */
    val erroAcaoCaixinha: String? = null,

    // formulário de novo lançamento
    val enviando: Boolean = false,
    val enviadoComSucesso: Boolean = false,
)

class AppViewModel(
    private val sessao: SessionStore,
    /**
     * Só para gravar o arquivo da LGPD em Downloads (MediaStore precisa de
     * ContentResolver). É o `applicationContext`: guardar uma Activity aqui
     * vazaria a tela inteira enquanto o ViewModel vivesse.
     */
    private val contexto: android.content.Context,
) : ViewModel() {

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
                // `supervisorScope`: sem ele, um `async` que falha cancela o
                // `launch` pai e o erro escapa do `catch` — o app morre com
                // FATAL EXCEPTION em vez de mostrar a mensagem de rede.
                supervisorScope {
                    // Busca em paralelo: são 5 endpoints independentes e a latência
                    // somada tornaria a primeira pintura lenta.
                    val carteira = async { api.carteira() }
                    val extrato = async { api.extrato(limite = 50) }
                    val derivacao = async { api.derivacao(limite = 100) }
                    val contas = async { api.contas() }
                    // Relatório é Premium: sem plano, o backend responde 402. Isso
                    // NÃO pode derrubar a Home — carteira, extrato e contas são
                    // grátis. O relatório simplesmente não vem, e a tela dele
                    // mostra o convite ao plano.
                    val relatorio = async { runCatching { api.relatorio() } }
                    // Vitrine: silenciosa de propósito. Se cair, a Home continua
                    // inteira — propaganda indisponível não é erro do usuário.
                    val parceiros = async {
                        // `getOrNull`: falha aqui não derruba a Home, e `null`
                        // distingue "não carregou" de "não há oferta".
                        runCatching { api.parceiros() }.getOrNull()
                    }

                    // Gerar lembretes antes de ler: senão a lista vem vazia na
                    // primeira abertura e o usuário acha que não tem aviso nenhum.
                    // Falha fica num selo discreto na Home — não derruba a tela.
                    val lembretes = runCatching { api.gerarNotificacoes() }
                    val naoLidas = async { api.notificacoes(somenteNaoLidas = true).size }

                    val resultadoRelatorio = relatorio.await()

                    _estado.update {
                        it.copy(
                            carregando = false,
                            erro = null,
                            autenticado = true,
                            carteira = carteira.await(),
                            extrato = extrato.await(),
                            derivacao = derivacao.await(),
                            contas = contas.await(),
                            relatorio = resultadoRelatorio.getOrNull(),
                            planoNecessario = ePlanoNecessario(resultadoRelatorio.exceptionOrNull()),
                            lembretesIndisponiveis = lembretes.isFailure,
                            naoLidas = naoLidas.await(),
                            parceiros = parceiros.await()?.itens.orEmpty(),
                            parceirosTotal = parceiros.await()?.total ?: 0,
                        )
                    }
                }
            } catch (e: Exception) {
                // Token expirado ou rede caída: uma mensagem e o usuário
                // reautentica. Não mostrar tela branca.
                _estado.update { it.copy(carregando = false, erro = mensagemDe(e)) }
            }
        }
    }

    /**
     * Primeira página da vitrine, sem refazer a Home inteira.
     *
     * O "Tentar de novo" da tela de parceiros chamava `carregarTudo()`, que
     * refaz carteira, extrato, contas e relatório — cinco chamadas para
     * consertar uma lista. Falha continua silenciosa: propaganda fora do ar
     * não é erro do usuário, e o botão já convida a tentar de novo.
     */
    fun carregarParceiros() {
        viewModelScope.launch {
            _estado.update { it.copy(carregandoMaisParceiros = true) }
            val pagina = runCatching { api.parceiros() }
            _estado.update { estado ->
                pagina.fold(
                    onSuccess = { primeira ->
                        estado.copy(
                            parceiros = primeira.itens,
                            parceirosTotal = primeira.total,
                            carregandoMaisParceiros = false,
                        )
                    },
                    onFailure = { estado.copy(carregandoMaisParceiros = false) },
                )
            }
        }
    }

    /**
     * Próxima página da vitrine, anexada ao fim.
     *
     * A página é pedida pelo tamanho da lista já carregada, não por um contador
     * à parte: se o admin desativar uma oferta no meio do caminho, o servidor
     * devolve a lista deslocada e o contador divergiria da tela.
     */
    fun carregarMaisParceiros() {
        val atual = _estado.value
        if (atual.carregandoMaisParceiros) return
        if (atual.parceiros.isNotEmpty() && atual.parceiros.size >= atual.parceirosTotal) return

        viewModelScope.launch {
            _estado.update { it.copy(carregandoMaisParceiros = true) }
            val pagina = runCatching { api.parceiros(offset = atual.parceiros.size) }
            _estado.update { estado ->
                pagina.fold(
                    onSuccess = { proxima ->
                        // Oferta cadastrada entre uma página e outra empurraria
                        // a lista e repetiria item: o filtro por id segura.
                        val novos = proxima.itens.filter { nova ->
                            estado.parceiros.none { it.id == nova.id }
                        }
                        estado.copy(
                            parceiros = estado.parceiros + novos,
                            parceirosTotal = proxima.total,
                            carregandoMaisParceiros = false,
                        )
                    },
                    onFailure = { estado.copy(carregandoMaisParceiros = false) },
                )
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

    /**
     * Cadastra conta a pagar (`POST /bills`, sempre PENDING).
     *
     * [amount] chega normalizado (vírgula→ponto, `filtrarValor`) e
     * [vencimentoIso] em `YYYY-MM-DD` — as duas conversões acontecem na tela,
     * porque a regra de entrada é do cliente (vírgula, dd/mm/aaaa) e a do
     * payload é do backend (ponto, ISO). Aqui só persiste.
     */
    fun novaConta(
        descricao: String,
        amount: String,
        vencimentoIso: String,
        beneficiario: String?,
        linhaDigitavel: String? = null,
        onOk: () -> Unit,
    ) {
        _estado.update { it.copy(enviando = true, erro = null, enviadoComSucesso = false) }
        viewModelScope.launch {
            runCatching {
                api.novaConta(
                    descricao = descricao.trim(),
                    amount = amount,
                    dueDate = vencimentoIso,
                    beneficiario = beneficiario?.trim(),
                    linhaDigitavel = linhaDigitavel,
                )
            }
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

    /** Leitor de código de barras achou uma linha — Nova conta preenche e consome. */
    fun registrarLinhaLida(codigo: String) {
        _estado.update { it.copy(linhaDigitavelLida = codigo) }
    }

    fun consumirLinhaLida() {
        _estado.update { it.copy(linhaDigitavelLida = null) }
    }

    /**
     * Lista completa das notificações (tela Notificações).
     *
     * `naoLidas` sai da MESMA lista em vez de uma segunda chamada com
     * `unread_only`: sino e tela precisam bater, e duas leituras em horários
     * diferentes mostrariam números diferentes para o mesmo usuário.
     */
    fun carregarNotificacoes() {
        viewModelScope.launch {
            runCatching { api.notificacoes() }
                .onSuccess { lista ->
                    _estado.update { atual ->
                        atual.copy(
                            notificacoes = lista,
                            naoLidas = lista.count { n -> n.readAt == null },
                        )
                    }
                }
                .onFailure { e ->
                    _estado.update { atual -> atual.copy(erro = mensagemDe(e)) }
                }
        }
    }

    /**
     * Marca UMA notificação como lida.
     *
     * A atualização local é otimista (some da lista de não lidas na hora) e a
     * recarga em seguida confirma com o servidor — o `runCatching` interno do
     * `KaveliPayApi` engole falha de rede, então a recarga é o único jeito de
     * o estado voltar a refletir a verdade do backend.
     */
    fun marcarNotificacaoLida(id: String) {
        viewModelScope.launch {
            runCatching { api.marcarNotificacaoLida(id) }
                .onSuccess {
                    _estado.update { atual ->
                        atual.copy(
                            notificacoes = atual.notificacoes?.map { n ->
                                if (n.id == id) n.copy(readAt = Instant.now().toString()) else n
                            },
                            naoLidas = (atual.naoLidas - 1).coerceAtLeast(0),
                        )
                    }
                    carregarNotificacoes()
                }
                .onFailure { e ->
                    _estado.update { atual -> atual.copy(erro = mensagemDe(e)) }
                }
        }
    }

    /**
     * Marca TODAS como lidas.
     *
     * O corpo antigo chamava `gerarNotificacoes()` — que SÓ cria lembretes — e
     * depois recontava as não lidas. O botão "marcar todas" existia na API e
     * nunca foi chamado: de fato não fazia nada.
     */
    fun marcarTodasLidas() {
        viewModelScope.launch {
            runCatching { api.marcarTodasNotificacoesLidas() }
                .onSuccess {
                    _estado.update { atual ->
                        atual.copy(
                            notificacoes = atual.notificacoes?.map { n ->
                                n.copy(readAt = n.readAt ?: Instant.now().toString())
                            },
                            naoLidas = 0,
                        )
                    }
                    carregarNotificacoes()
                }
                .onFailure { e ->
                    _estado.update { atual -> atual.copy(erro = mensagemDe(e)) }
                }
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

    // ── Caixinha ─────────────────────────────────────────────────────────────
    // Cada fluxo tem seu par carregando/erro: lista, vitrine, detalhe e mutação
    // não compartilham canal com o `erro` global da Home. Mutação que devolve
    // detalhe APLICA a resposta no estado — o contrato manda, e economiza o
    // GET que a tela faria em seguida.

    /** `GET /caixinhas` — as minhas, para as seções "Como responsável"/"Como membro". */
    fun carregarCaixinhas() {
        viewModelScope.launch {
            _estado.update { it.copy(carregandoCaixinhas = true, erroCaixinhas = null) }
            runCatching { api.caixinhas() }
                .onSuccess { lista ->
                    _estado.update { it.copy(carregandoCaixinhas = false, caixinhas = lista) }
                }
                .onFailure { e ->
                    _estado.update { it.copy(carregandoCaixinhas = false, erroCaixinhas = mensagemDe(e)) }
                }
        }
    }

    /** `GET /caixinhas/publicas` — vitrine de caixinhas marcadas como públicas. */
    fun carregarVitrineCaixinhas() {
        viewModelScope.launch {
            _estado.update { it.copy(carregandoVitrine = true, erroVitrine = null) }
            runCatching { api.caixinhasPublicas() }
                .onSuccess { lista ->
                    _estado.update { it.copy(carregandoVitrine = false, caixinhasPublicas = lista) }
                }
                .onFailure { e ->
                    _estado.update { it.copy(carregandoVitrine = false, erroVitrine = mensagemDe(e)) }
                }
        }
    }

    /** `GET /caixinhas/{id}` — detalhe com o mês local do aparelho. */
    fun carregarDetalheCaixinha(id: String) {
        viewModelScope.launch {
            _estado.update { it.copy(carregandoDetalheCaixinha = true, erroDetalheCaixinha = null) }
            runCatching { api.caixinha(id) }
                .onSuccess { detalhe ->
                    _estado.update {
                        it.copy(carregandoDetalheCaixinha = false, caixinhaDetalhe = detalhe)
                    }
                }
                .onFailure { e ->
                    _estado.update {
                        it.copy(carregandoDetalheCaixinha = false, erroDetalheCaixinha = mensagemDe(e))
                    }
                }
        }
    }

    /** Erro de mutação é da tela que o mostrou; reabrir um diálogo limpa o anterior. */
    fun limparErroAcaoCaixinha() {
        _estado.update { it.copy(erroAcaoCaixinha = null) }
    }

    /**
     * `POST /caixinhas` — cria. A resposta já é o detalhe (papel dono, convite
     * gerado); aplica no estado e entrega o id para a navegação abrir o detalhe.
     */
    fun criarCaixinha(
        nome: String,
        valorPedido: String,
        taxaMensal: String,
        previsaoQuitacao: String?,
        telefone: String?,
        publica: Boolean,
        aoPronto: (String) -> Unit,
    ) {
        viewModelScope.launch {
            _estado.update { it.copy(enviandoCaixinha = true, erroAcaoCaixinha = null) }
            runCatching {
                api.criarCaixinha(nome, valorPedido, taxaMensal, previsaoQuitacao, telefone, publica)
            }
                .onSuccess { detalhe ->
                    _estado.update { it.copy(enviandoCaixinha = false, caixinhaDetalhe = detalhe) }
                    aoPronto(detalhe.id)
                }
                .onFailure { e ->
                    _estado.update { it.copy(enviandoCaixinha = false, erroAcaoCaixinha = mensagemDe(e)) }
                }
        }
    }

    /**
     * `POST /caixinhas/entrar` — a chave como o usuário digitou. Em sucesso o
     * detalhe (papel membro) é aplicado e [aoEntrar] recebe o id; as listas
     * de origem recarregam sozinhas quando a tela volta à composição.
     */
    fun entrarComChaveCaixinha(chave: String, aoEntrar: (String) -> Unit) {
        viewModelScope.launch {
            _estado.update { it.copy(enviandoCaixinha = true, erroAcaoCaixinha = null) }
            runCatching { api.entrarCaixinha(chave) }
                .onSuccess { detalhe ->
                    _estado.update { it.copy(enviandoCaixinha = false, caixinhaDetalhe = detalhe) }
                    aoEntrar(detalhe.id)
                }
                .onFailure { e ->
                    _estado.update { it.copy(enviandoCaixinha = false, erroAcaoCaixinha = mensagemDe(e)) }
                }
        }
    }

    /**
     * Mutação de caixinha: chama a API e aplica o DETALHE que o servidor
     * devolve — nunca recalcula nada no cliente. Erro 4xx (string pt-BR do
     * contrato) vai para `erroAcaoCaixinha` como veio. [aoPronto] roda só em
     * sucesso (limpar campo, fechar diálogo).
     */
    private fun mutarCaixinha(acao: suspend () -> DetalheCaixinha, aoPronto: () -> Unit = {}) {
        viewModelScope.launch {
            _estado.update { it.copy(enviandoCaixinha = true, erroAcaoCaixinha = null) }
            runCatching { acao() }
                .onSuccess { detalhe ->
                    _estado.update { it.copy(enviandoCaixinha = false, caixinhaDetalhe = detalhe) }
                    aoPronto()
                }
                .onFailure { e ->
                    _estado.update { it.copy(enviandoCaixinha = false, erroAcaoCaixinha = mensagemDe(e)) }
                }
        }
    }

    /** Membro propõe aporte (`amount` já normalizado em ponto, 2 casas). */
    fun proporAporteCaixinha(caixinhaId: String, valor: String, aoPronto: () -> Unit = {}) {
        mutarCaixinha({ api.proporAporte(caixinhaId, valor) }, aoPronto)
    }

    /**
     * Responsável propõe o rendimento do mês — sem valor: o servidor calcula
     * (base × taxa) e recusa com 422 quando não há rendimento em aberto.
     */
    fun proporRendimentoCaixinha(caixinhaId: String, membroId: String) {
        // Forma parentesada: trailing lambda ligaria ao `aoPronto` (último
        // parâmetro), não à `acao`.
        mutarCaixinha({ api.proporRendimento(caixinhaId, membroId, YearMonth.now().toString()) })
    }

    /** Responsável propõe devolução (`amount` já normalizado em ponto, 2 casas). */
    fun proporDevolucaoCaixinha(caixinhaId: String, membroId: String, valor: String, aoPronto: () -> Unit = {}) {
        mutarCaixinha({ api.proporDevolucao(caixinhaId, membroId, valor) }, aoPronto)
    }

    fun confirmarMovimentoCaixinha(caixinhaId: String, movimentoId: String) {
        mutarCaixinha({ api.confirmarMovimento(caixinhaId, movimentoId) })
    }

    fun recusarMovimentoCaixinha(caixinhaId: String, movimentoId: String) {
        mutarCaixinha({ api.recusarMovimento(caixinhaId, movimentoId) })
    }

    fun cancelarMovimentoCaixinha(caixinhaId: String, movimentoId: String) {
        mutarCaixinha({ api.cancelarMovimento(caixinhaId, movimentoId) })
    }

    /** Membro avalia 1–5 estrelas; o detalhe volta com `minha_avaliacao` preenchido. */
    fun avaliarCaixinha(caixinhaId: String, estrelas: Int) {
        mutarCaixinha({ api.avaliarCaixinha(caixinhaId, estrelas) })
    }

    /** Dono grava o telefone da caixinha; membro, o próprio. Só dígitos. */
    fun salvarTelefoneCaixinha(caixinhaId: String, telefone: String) {
        mutarCaixinha({ api.salvarTelefoneCaixinha(caixinhaId, telefone) })
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
            // Limpa o aviso da exportação ao entrar: "Arquivo salvo" de uma
            // visita anterior não pode parecer resposta a algo que a pessoa
            // acabou de fazer.
            _estado.update { it.copy(enviando = true, erro = null, avisoPerfil = null) }
            runCatching { api.perfil() }
                .onSuccess { perfil ->
                    _estado.update { it.copy(enviando = false, perfil = perfil) }
                    // Carregar sessões após perfil
                    runCatching { api.sessoes() }
                        .onSuccess { lista -> _estado.update { it.copy(sessoes = lista) } }
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
            // O plano é lido junto: o Perfil mostra a linha "Plano" e precisa
            // do status sem a pessoa esperar a tela de plano abrir.
            carregarPlano()
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
                    // O backend revoga TODAS as sessões, inclusive a atual —
                    // `revoke_all_sessions` não exclui nenhuma (o access token
                    // só carrega `sub`, não tem id de sessão para poupar).
                    // Ficar "logado" aqui seria mentira: o refresh token já
                    // morreu e a próxima renovação falharia calada. Então sai
                    // de verdade, com a pessoa sabendo.
                    sair()
                }
                .onFailure { e -> _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) } }
        }
    }

    // ── Plano premium ──────────────────────────────────────────────────────────

    /**
     * Lê o plano vigente.
     *
     * `GET /billing/plano` é grátis e devolve 200 sempre; falhar aqui é só
     * "não deu para saber agora" e não pode virar erro de tela — por isso
     * falha silenciosa, como a vitrine de parceiros.
     */
    fun carregarPlano() {
        viewModelScope.launch {
            _estado.update { it.copy(carregandoPlano = true) }
            runCatching { api.plano() }
                .onSuccess { p -> _estado.update { it.copy(plano = p, carregandoPlano = false) } }
                .onFailure { _estado.update { it.copy(carregandoPlano = false) } }
        }
    }

    /**
     * Ativa o plano com o código de ativação.
     *
     * Sucesso: o backend devolve o access token novo, já com o claim
     * `premium`, e a API o guardou na sessão — por isso o `carregarTudo` de
     * seguida já traz o relatório, sem esperar renovação nenhuma.
     */
    fun ativarPlano(codigo: String) {
        viewModelScope.launch {
            _estado.update {
                it.copy(enviando = true, avisoPlano = null, avisoPlanoFalhou = false)
            }
            runCatching { api.ativarPlano(codigo) }
                .onSuccess { plano ->
                    _estado.update {
                        it.copy(
                            enviando = false,
                            plano = plano,
                            planoNecessario = false,
                            avisoPlano = "Plano Premium ativado.",
                            avisoPlanoFalhou = false,
                        )
                    }
                    carregarTudo()
                }
                .onFailure { e ->
                    _estado.update {
                        it.copy(
                            enviando = false,
                            avisoPlano = mensagemDe(e),
                            avisoPlanoFalhou = true,
                        )
                    }
                }
        }
    }

    // ── LGPD ──────────────────────────────────────────────────────────────────
    fun exportarDadosCsv() {
        viewModelScope.launch {
            runCatching { api.lgpdExportCsv() }
                .onSuccess { csv ->
                    salvarExportacao(
                        base = "kavelipay_extrato",
                        extensao = "csv",
                        mime = "text/csv",
                        conteudo = csv,
                        confirmacao = "Planilha salva",
                    )
                }
                .onFailure { avisoFalha(it) }
        }
    }

    /**
     * Grava em Downloads e SÓ ENTÃO anuncia sucesso.
     *
     * Antes a gravação devolvia o texto do erro como se fosse caminho de
     * arquivo, e a tela exibia "Arquivo salvo" em verde com "EACCES" no meio.
     * Separar em dois resultados (gravou / não gravou) é o que impede isso de
     * voltar: falha de escrita agora pinta o aviso de vermelho.
     */
    private fun salvarExportacao(
        base: String,
        extensao: String,
        mime: String,
        conteudo: String,
        confirmacao: String,
    ) {
        runCatching {
            val nome = Exportador.nomeComCarimbo(base, extensao)
            Exportador.salvar(contexto, nome, mime, conteudo)
        }
            .onSuccess { arquivo ->
                _estado.update {
                    it.copy(
                        avisoPerfil = "$confirmacao em Downloads: $arquivo",
                        avisoPerfilFalhou = false,
                    )
                }
            }
            .onFailure { avisoFalha(it) }
    }

    private fun avisoFalha(e: Throwable) {
        _estado.update { it.copy(avisoPerfil = mensagemDe(e), avisoPerfilFalhou = true) }
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
                .onFailure { e ->
                    // Falha de rede ou IA fora: silêncio, como sempre. Falta de
                    // plano é outra coisa — a pessoa precisa saber que o
                    // recurso é pago, senão conclui que o ticker estava errado.
                    if (ePlanoNecessario(e)) _estado.update { it.copy(erro = mensagemDe(e)) }
                    aoReceber(null)
                }
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
                .onFailure { e ->
                    // Mesma regra da IA: só a falta de plano merece aviso.
                    if (ePlanoNecessario(e)) _estado.update { it.copy(erro = mensagemDe(e)) }
                    aoReceber(null)
                }
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

/**
 * `true` quando o erro é o "falta plano" do backend (402 `plano_necessario`).
 *
 * O código vem do corpo da resposta (`error`), não do status: 402 significa
 * "pagamento necessário" e o app precisa saber que o recurso — e não a
 * sessão — é o problema.
 */
fun ePlanoNecessario(e: Throwable?): Boolean =
    (e as? ApiClient.ErroApi)?.codigo == "plano_necessario"
