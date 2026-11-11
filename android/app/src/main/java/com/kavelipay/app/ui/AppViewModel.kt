package com.kavelipay.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavelipay.app.data.ApiClient
import com.kavelipay.app.data.Carteira
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
import com.kavelipay.app.data.ListaCaixinhas
import com.kavelipay.app.data.ListaCharges
import com.kavelipay.app.data.ListaSessoes
import com.kavelipay.app.data.ListaSplits
import com.kavelipay.app.data.Notificacao
import com.kavelipay.app.data.Perfil
import com.kavelipay.app.data.ContaMp
import com.kavelipay.app.data.Plano
import com.kavelipay.app.data.Relatorio
import com.kavelipay.app.data.SessionStore
import com.kavelipay.app.data.mesLocal
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.json.JSONObject
import java.time.Instant

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
    val charges: ListaCharges? = null,
    val chargeQr: ItemCharge? = null,
    val historico: Historico? = null,
    val perfil: Perfil? = null,
    val sessoes: ListaSessoes? = null,

    // Caixinhas: lista (dono ou membro) e o detalhe aberto. `null` na lista =
    // ainda não carregou; o detalhe é recarregado a cada mutação porque o
    // servidor devolve os números derivados já recalculados.
    val caixinhas: ListaCaixinhas? = null,
    val caixinhaDetalhe: DetalheCaixinha? = null,

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

    /**
     * URL do checkout do Mercado Pago aberto num WebView. Fica no estado para
     * sobreviver à recomposição da tela; `null` = nenhum checkout aberto.
     */
    val checkoutUrl: String? = null,

    /** Conta do Mercado Pago que recebe as cobranças; `null` = não carregada. */
    val contaMp: ContaMp? = null,

    /** URL da autorização do Mercado Pago aberta num WebView. */
    val mpUrl: String? = null,

    // Notificações (tela dedicada; o contador `naoLidas` existe desde o início)
    val notificacoes: List<Notificacao>? = null,

    /**
     * A geração de lembretes (`POST /notifications/generate`) falhou. Erro
     * discreto de propósito NÃO entra em `erro`: o resto da carga da Home
     * funcionou, e o usuário só precisa saber que o aviso pode não vir.
     */
    val lembretesIndisponiveis: Boolean = false,

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

    /**
     * Pede o código de redefinição. Não entra na sessão: quem esqueceu a senha
     * está justamente fora dela. Erro vai para `estado.erro` como os demais.
     */
    fun esquecerSenha(email: String, onOk: () -> Unit) {
        _estado.update { it.copy(carregando = true, erro = null) }
        viewModelScope.launch {
            runCatching { api.esquecerSenha(email.trim()) }
                .onSuccess {
                    _estado.update { it.copy(carregando = false, erro = null) }
                    onOk()
                }
                .onFailure { e ->
                    _estado.update { it.copy(carregando = false, erro = mensagemDe(e)) }
                }
        }
    }

    /** Troca a senha com o código do e-mail. As sessões antigas morrem no servidor. */
    fun redefinirSenha(codigo: String, novaSenha: String, onOk: () -> Unit) {
        _estado.update { it.copy(carregando = true, erro = null) }
        viewModelScope.launch {
            runCatching { api.redefinirSenha(codigo, novaSenha) }
                .onSuccess {
                    _estado.update { it.copy(carregando = false, erro = null) }
                    onOk()
                }
                .onFailure { e ->
                    _estado.update { it.copy(carregando = false, erro = mensagemDe(e)) }
                }
        }
    }

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
                    // Nome do usuário para a saudação da Home. Silencioso de
                    // propósito: perfil que não carregou deixa o topo com o
                    // nome do app — não é erro de tela.
                    val perfil = async { runCatching { api.perfil() }.getOrNull() }
                    val derivacao = async { api.derivacao(limite = 100) }
                    val contas = async { api.contas() }
                    // Relatório é Premium: sem plano, o backend responde 402. Isso
                    // NÃO pode derrubar a Home — carteira, extrato e contas são
                    // grátis. O relatório simplesmente não vem, e a tela dele
                    // mostra o convite ao plano.
                    val relatorio = async { runCatching { api.relatorio() } }
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
                            perfil = perfil.await(),
                            relatorio = resultadoRelatorio.getOrNull(),
                            planoNecessario = ePlanoNecessario(resultadoRelatorio.exceptionOrNull()),
                            lembretesIndisponiveis = lembretes.isFailure,
                            naoLidas = naoLidas.await(),
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

    /**
     * Gera o link do Checkout Pro (`POST /charges/{id}/pagamento`).
     * Nada é marcado como recebido aqui: quem confirma é o webhook do MP.
     */
    fun gerarLinkPagamento(chargeId: String, aoAbrir: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { api.linkPagamento(chargeId) }
                .onSuccess(aoAbrir)
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

    fun atualizarPerfil(displayName: String?, pixChave: String? = null) {
        viewModelScope.launch {
            _estado.update { it.copy(enviando = true, erro = null) }
            runCatching { api.atualizarPerfil(displayName, pixChave) }
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
     * falha silenciosa.
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

    /**
     * Abre o checkout do Mercado Pago para o plano escolhido.
     *
     * A página é servida pelo backend (`/assinar/<token>`), que é quem manda
     * o preço para o Mercado Pago — o app nunca decide quanto custa o plano.
     */
    fun iniciarCheckout(plano: String) {
        viewModelScope.launch {
            _estado.update { it.copy(enviando = true, avisoPlano = null, avisoPlanoFalhou = false) }
            runCatching { api.checkoutPlano(plano) }
                .onSuccess { url ->
                    _estado.update { it.copy(enviando = false, checkoutUrl = url) }
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

    /** Fecha o checkout e relê o plano: o pagamento pode ter confirmado. */
    fun fecharCheckout() {
        _estado.update { it.copy(checkoutUrl = null) }
        carregarPlano()
    }

    // ── Conta do Mercado Pago ─────────────────────────────────────────────────

    /**
     * Quem recebe as cobranças é a conta do Mercado Pago de quem cobrou: sem
     * conexão, o dinheiro cairia na conta do app — que é justamente custódia.
     */
    fun carregarContaMp() {
        viewModelScope.launch {
            runCatching { api.contaMp() }.onSuccess { c ->
                _estado.update { it.copy(contaMp = c) }
            }
        }
    }

    fun abrirConexaoMp() {
        viewModelScope.launch {
            runCatching { api.linkMp() }
                .onSuccess { url -> _estado.update { it.copy(mpUrl = url) } }
                .onFailure { e ->
                    _estado.update {
                        it.copy(avisoPerfil = mensagemDe(e), avisoPerfilFalhou = true)
                    }
                }
        }
    }

    /** O WebView voltou de `kavelipay://mercadopago/conectado` (ou foi fechado). */
    fun fecharConexaoMp() {
        _estado.update { it.copy(mpUrl = null) }
        carregarContaMp()
    }

    fun desconectarMp() {
        viewModelScope.launch {
            runCatching { api.desconectarMp() }
                .onSuccess { carregarContaMp() }
                .onFailure { e ->
                    _estado.update {
                        it.copy(avisoPerfil = mensagemDe(e), avisoPerfilFalhou = true)
                    }
                }
        }
    }

    // ── Relatório ─────────────────────────────────────────────────────────────
    /** Carrega o relatório de um mês (`YYYY-MM`); `null` = mês atual. */
    fun carregarRelatorio(mes: String? = null) {
        viewModelScope.launch {
            _estado.update { it.copy(carregando = true, erro = null) }
            runCatching { api.relatorio(mes) }
                .onSuccess { r ->
                    _estado.update {
                        it.copy(carregando = false, relatorio = r, planoNecessario = false)
                    }
                }
                .onFailure { e ->
                    val semPlano = ePlanoNecessario(e)
                    _estado.update {
                        it.copy(
                            carregando = false,
                            relatorio = null,
                            planoNecessario = semPlano,
                            erro = if (semPlano) null else mensagemDe(e),
                        )
                    }
                }
        }
    }

    /** Baixa o HTML do relatório em Downloads; o Android imprime em PDF. */
    fun baixarRelatorio(mes: String? = null) {
        viewModelScope.launch {
            runCatching { api.relatorioHtml(mes) }
                .onSuccess { html ->
                    salvarExportacao(
                        base = "kavelipay_relatorio",
                        extensao = "html",
                        mime = "text/html",
                        conteudo = html,
                        confirmacao = "Relatório salvo",
                    )
                }
                .onFailure { avisoFalha(it) }
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

    // ── Caixinhas (empréstimo entre conhecidos) ───────────────────────────────
    fun carregarCaixinhas() {
        _estado.update { it.copy(carregando = true, erro = null) }
        viewModelScope.launch {
            runCatching { api.caixinhas() }
                .onSuccess { lista ->
                    _estado.update { it.copy(carregando = false, caixinhas = lista) }
                }
                .onFailure { e ->
                    _estado.update { it.copy(carregando = false, erro = mensagemDe(e)) }
                }
        }
    }

    fun carregarCaixinha(id: String, mes: String = mesLocal()) {
        _estado.update { it.copy(carregando = true, erro = null) }
        viewModelScope.launch {
            runCatching { api.caixinha(id, mes) }
                .onSuccess { detalhe ->
                    _estado.update { it.copy(carregando = false, caixinhaDetalhe = detalhe) }
                }
                .onFailure { e ->
                    _estado.update { it.copy(carregando = false, erro = mensagemDe(e)) }
                }
        }
    }

    fun criarCaixinha(
        nome: String,
        valorPedido: String,
        taxaMensal: String,
        previsaoQuitacao: String?,
        telefone: String?,
        onOk: (String) -> Unit,
    ) {
        _estado.update { it.copy(enviando = true, erro = null) }
        viewModelScope.launch {
            runCatching {
                api.criarCaixinha(
                    nome = nome.trim(),
                    valorPedido = valorPedido,
                    taxaMensal = taxaMensal,
                    previsaoQuitacao = previsaoQuitacao?.trim()?.ifBlank { null },
                    telefone = telefone,
                    mes = mesLocal(),
                )
            }
                .onSuccess { detalhe ->
                    _estado.update {
                        it.copy(enviando = false, caixinhaDetalhe = detalhe)
                    }
                    carregarCaixinhas()
                    onOk(detalhe.id)
                }
                .onFailure { e ->
                    _estado.update {
                        it.copy(
                            enviando = false,
                            erro = mensagemDe(e),
                            // 402 na criação = a 2ª caixinha é Premium.
                            planoNecessario = ePlanoNecessario(e),
                        )
                    }
                }
        }
    }

    fun entrarCaixinha(chave: String, onOk: (String) -> Unit) {
        _estado.update { it.copy(enviando = true, erro = null) }
        viewModelScope.launch {
            runCatching { api.entrarCaixinha(chave, mesLocal()) }
                .onSuccess { detalhe ->
                    _estado.update {
                        it.copy(enviando = false, caixinhaDetalhe = detalhe)
                    }
                    carregarCaixinhas()
                    onOk(detalhe.id)
                }
                .onFailure { e ->
                    _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) }
                }
        }
    }

    /** Mutação que devolve o detalhe: guarda a resposta e atualiza a lista. */
    private fun mutarCaixinha(bloco: suspend () -> DetalheCaixinha) {
        _estado.update { it.copy(enviando = true, erro = null) }
        viewModelScope.launch {
            runCatching { bloco() }
                .onSuccess { detalhe ->
                    _estado.update { it.copy(enviando = false, caixinhaDetalhe = detalhe) }
                    runCatching { api.caixinhas() }.onSuccess { lista ->
                        _estado.update { it.copy(caixinhas = lista) }
                    }
                }
                .onFailure { e ->
                    _estado.update { it.copy(enviando = false, erro = mensagemDe(e)) }
                }
        }
    }

    fun aportarCaixinha(id: String, amount: String, mes: String = mesLocal()) =
        mutarCaixinha { api.proporAporte(id, amount, mes) }

    fun renderCaixinha(id: String, membroId: String, competencia: String, mes: String = mesLocal()) =
        mutarCaixinha { api.proporRendimento(id, membroId, competencia, mes) }

    fun devolverCaixinha(id: String, membroId: String, amount: String, mes: String = mesLocal()) =
        mutarCaixinha { api.proporDevolucao(id, membroId, amount, mes) }

    fun confirmarMovimentoCaixinha(id: String, movimentoId: String, mes: String = mesLocal()) =
        mutarCaixinha { api.confirmarMovimento(id, movimentoId, mes) }

    fun recusarMovimentoCaixinha(id: String, movimentoId: String, mes: String = mesLocal()) =
        mutarCaixinha { api.recusarMovimento(id, movimentoId, mes) }

    fun cancelarMovimentoCaixinha(id: String, movimentoId: String, mes: String = mesLocal()) =
        mutarCaixinha { api.cancelarMovimento(id, movimentoId, mes) }

    fun salvarTelefoneCaixinha(id: String, telefone: String, mes: String = mesLocal()) =
        mutarCaixinha { api.salvarTelefoneCaixinha(id, telefone, mes) }

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
