package com.kavelipay.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP do KaveliPay.
 *
 * OkHttp direto, sem Retrofit e sem kotlinx-serialization: o formato da API é
 * pequeno e `org.json` (do próprio SDK) resolve. Menos dependências é menos
 * superfície de supply chain num app que lida com dado financeiro.
 */
object ApiClient {

    /**
     * Candidatos de host do backend, testados na ordem por uma sonda HTTP
     * (`GET /health`).
     *
     * - `192.168.240.1` = lado host da bridge do Waydroid — o container só tem
     *   rota para `192.168.240.0/24`, então não alcança `192.168.1.8`;
     * - `192.168.1.8` = IP da máquina na rede Wi-Fi (aparelho físico, mesma rede);
     * - `10.0.2.2` = loopback do host no emulador AVD.
     *
     * O Render voltou: agora há deploy (`kavelipay-backend.onrender.com`) e a
     * sonda valida `GET /health` real (200 + corpo `ok`), não só porta aberta.
     * No plano free ele dorme após ~15 min sem tráfego — o cold start passa do
     * timeout da sonda e o app cai pro candidato local da vez.
     *
     * O MESMO APK serve os três ambientes: antes da primeira requisição o
     * [BASE_URL] é resolvido sondando cada candidato, e o host que respondeu é
     * persistido (`SharedPreferences` `kavelipay.servidor`) para virar o
     * primeiro da lista da próxima execução. Sem a sonda, o candidato errado
     * custaria 15s de connect timeout por requisição (foi o que travava o
     * aparelho físico apontando para a bridge do Waydroid).
     */
    private val CANDIDATOS = listOf(
        "https://kavelipay-backend.onrender.com",
        "http://192.168.240.1:3000",
        "http://192.168.1.8:3000",
        "http://10.0.2.2:3000",
    )

    /**
     * Base URL do backend em uso. Só muda pela sonda ([resolverServidor] /
     * [trocarServidor]) — nunca é gravada direto de fora.
     */
    @Volatile
    var BASE_URL: String = CANDIDATOS.first()
        private set

    /** Tempo da sonda HTTP por candidato (conexão): curto de propósito, é blocking em IO. */
    private const val SONDA_MS = 800L

    private const val KEY_HOST = "host"

    private var prefsServidor: SharedPreferences? = null

    /**
     * Serializa sonda e troca de host. A tela dispara ~5 requisições em
     * paralelo; sem o lock, duas falhas simultâneas rodariam a sonda ao mesmo
     * tempo e poderiam gravar hosts diferentes uma por meio da outra.
     */
    private val mutexServidor = Mutex()

    /** `true` quando a sonda inicial já rodou nesta execução. */
    @Volatile
    private var resolvido = false

    /**
     * Injeta o Contexto para persistir o host que respondeu.
     *
     * Mesmo padrão da injeção de `sessao`: chamado no `onCreate` da Activity,
     * antes de qualquer requisição. Sem isso a sonda funciona, mas recomeça do
     * zero a cada abertura do app.
     */
    fun configurar(context: Context) {
        prefsServidor = context.getSharedPreferences("kavelipay.servidor", Context.MODE_PRIVATE)
        // Host salvo vale como BASE inicial; a sonda revalida antes do uso e a
        // ordem de tentativa é a da lista (Render primeiro) — ver [candidatos].
        prefsServidor?.getString(KEY_HOST, null)
            ?.takeIf { it in CANDIDATOS }
            ?.let { BASE_URL = it }
    }

    /**
     * Candidatos: a ordem FIXA da lista (Render primeiro), depois o salvo.
     * Salvo primeiro era um trampo: numa sessão de cold start do Render o app
     * caía pro local e GRAVAVA o local — e a partir daí o Render (sempre
     * depois do salvo) nunca mais era testado. Com a ordem fixa, o Render
     * volta sozinho pra [BASE_URL] assim que responde, sem intervenção.
     */
    private fun candidatos(): List<String> {
        // Host salvo só vale se ainda está na lista: um endereço removido
        // (Render morto) não pode continuar sendo tentado primeiro.
        val salvo = prefsServidor?.getString(KEY_HOST, null)?.takeIf { it in CANDIDATOS }
        return (CANDIDATOS + listOfNotNull(salvo)).distinct()
    }

    /**
     * Sonda HTTP: `GET /health` respondendo 200 com algo contendo "ok" no
     * corpo (`{"status":"ok"}` do backend). Só porta TCP aberta não bastava:
     * o Render respondia conexão com `x-render-routing: no-server` e virava
     * BASE_URL gravado. Não é chamada autenticada nem toca dado nenhum.
     * Endereço malformado vira `false` em vez de estourar na resolução.
     */
    private val sondaClient = OkHttpClient.Builder()
        .connectTimeout(SONDA_MS, TimeUnit.MILLISECONDS)
        .readTimeout(2000L, TimeUnit.MILLISECONDS)
        .build()

    private fun alcancavel(url: String): Boolean = try {
        sondaClient.newCall(Request.Builder().url(url + "/health").build())
            .execute()
            .use { resposta ->
                resposta.isSuccessful &&
                    resposta.body?.string()?.contains("ok", ignoreCase = true) == true
            }
    } catch (_: Exception) {
        false
    }

    /**
     * Resolve o [BASE_URL] uma única vez por execução (sonda nos candidatos).
     *
     * Se NENHUM candidato responder (backend fora do ar), mantém o candidato
     * atual e segue: a requisição vai cair no erro de rede normal, e a
     * tentativa de troca dentro de [executar] se vira depois que o backend
     * voltar. Assim o app não fica refazendo sonda a cada chamada.
     */
    private suspend fun baseUrl(): String {
        if (resolvido) return BASE_URL
        mutexServidor.withLock {
            if (resolvido) return BASE_URL
            candidatos().firstOrNull { alcancavel(it) }?.let {
                BASE_URL = it
                prefsServidor?.edit()?.putString(KEY_HOST, it)?.apply()
            }
            resolvido = true
        }
        return BASE_URL
    }

    /**
     * Procura outro host alcançável quando o [atual] parou de responder.
     *
     * `null` = nenhum alternativo no ar; o chamador mantém o erro original.
     * A gravação do host novo acontece aqui, não na UI: quem sabe qual
     * servidor respondeu é quem tentou falar com ele.
     */
    private suspend fun trocarServidor(atual: String): String? = mutexServidor.withLock {
        // Outra requisição falhada já trocou enquanto esta esperava o lock —
        // repetir a sonda aqui seria 800ms de socket à toa.
        BASE_URL.takeIf { it != atual && alcancavel(it) }?.let { return@withLock it }

        val outro = candidatos().firstOrNull { it != atual && alcancavel(it) }
            ?: return@withLock null
        BASE_URL = outro
        prefsServidor?.edit()?.putString(KEY_HOST, outro)?.apply()
        outro
    }

    private val json = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Sessão usada para renovar o access token.
     *
     * `ApiClient` é um `object` por não ter estado próprio, mas precisa do
     * `SessionStore` para ler o refresh e gravar o par novo. A referência entra
     * por injeção no start do app em vez de um Context global, e precisa ser o
     * MESMO `SessionStore` que a UI usa — senão o token renovado fica num
     * lugar que a tela não lê, e o app continua parecendo expirado.
     */
    var sessao: SessionStore? = null

    /**
     * Chamado quando o refresh também falha (401): o refresh foi revogado ou
     * rotacionado fora daqui. A única saída é refazer login, e o ViewModel
     * limpa o estado para a UI cair na tela de login sozinha.
     */
    var aoRevogarSessao: (() -> Unit)? = null

    /**
     * Serializa as renovações.
     *
     * Não é cuidado exagerado: `POST /auth/refresh` ROTACIONA o refresh token
     * (revoga o usado e emite outro). Se duas requisições levassem 401 ao mesmo
     * tempo e cada uma renovasse, a segunda mandaria o token já revogado — e o
     * backend trata reuso de token revogado como token roubado, matando TODAS
     * as sessões do usuário. A tela carrega 5 endpoints em paralelo, então
     * "401 em paralelo" não é teoria: é o caminho normal.
     */
    private val mutexRenovacao = Mutex()

    /** Erro de API com o corpo já interpretado, para a UI mostrar algo útil. */
    class ErroApi(
        val status: Int,
        val codigo: String,
        val mensagem: String,
    ) : Exception(mensagem) {
        /** Erro de negócio que a UI deve mostrar como está (ex.: "senha fraca"). */
        val tratavel: Boolean get() = status in 400..499
    }

    class ErroRede(cause: Throwable) : IOException(cause.message, cause)

    suspend fun get(caminho: String, token: String?): JSONObject = request("GET", caminho, null, token)

    /**
     * Resposta em texto puro (CSV). `get` tenta parsear como JSON, e um CSV
     * vira `JSONObject()` vazio — o usuário veria um arquivo branco.
     */
    suspend fun getText(caminho: String, token: String?): String =
        executar("GET", caminho, null, token).texto.orEmpty()

    /**
     * Resposta que é um ARRAY JSON puro (`Json<Vec<T>>` no Rust), não um
     * objeto. `/categories`, `/notifications`, `/splits`, `/charges` e
     * `/profile/sessions` são assim — ler `items` delas devolveria null e a
     * tela renderizaria vazia sem erro visível.
     */
    suspend fun getArray(caminho: String, token: String?): JSONArray =
        requestRaw("GET", caminho, null, token) as? JSONArray ?: JSONArray()

    /**
     * Versão de [getArray] que embrulha o resultado em `{items: [...]}`.
     *
     * Existe porque metade dos modelos é `{items, balance}` e a outra metade é
     * array solto. Sem esta adaptadora cada `paraListaX()` precisaria saber
     * qual formato o seu endpoint usa — e errar isso é `JSONException` em
     * tempo de execução, não erro de compilação.
     */
    suspend fun getItens(caminho: String, token: String?): JSONObject =
        JSONObject().put("items", getArray(caminho, token))

    suspend fun post(caminho: String, corpo: JSONObject?, token: String?): JSONObject =
        request("POST", caminho, corpo, token)

    suspend fun patch(caminho: String, corpo: JSONObject?, token: String?): JSONObject =
        request("PATCH", caminho, corpo, token)

    /**
     * DELETE com corpo. `/lgpd/account` exige `password` e `confirm: true` no
     * body, então a variante sem corpo não serve — e mandar o body numa query
     * string colocaria a senha do usuário em log de servidor.
     */
    suspend fun delete(caminho: String, corpo: JSONObject?, token: String?): JSONObject =
        request("DELETE", caminho, corpo, token)

    suspend fun put(caminho: String, corpo: JSONObject?, token: String?): JSONObject =
        request("PUT", caminho, corpo, token)

    private suspend fun request(
        metodo: String,
        caminho: String,
        corpo: JSONObject?,
        token: String?,
    ): JSONObject {
        val bruto = requestRaw(metodo, caminho, corpo, token)
        return bruto as? JSONObject ?: JSONObject()
    }

    private suspend fun requestRaw(
        metodo: String,
        caminho: String,
        corpo: JSONObject?,
        token: String?,
    ): Any {
        val primeira = executar(metodo, caminho, corpo, token)

        if (primeira.falhouCom(401) && token != null && sessao?.refreshToken != null) {
            // A geração é capturada ANTES de esperar o lock: é o que permite
            // saber, depois, se outra corrotina renovou no caminho.
            val geracao = sessao?.geracao ?: 0L

            if (renovarSeUsando(geracaoQuandoFalhou = geracao)) {
                // Repete UMA vez com o token novo. Uma segunda tentativa não
                // distinguiria "token novo também inválido" de "endpoint que
                // recusa este usuário" — e retry em 401 é como um app de banco
                // fica martelando o servidor.
                val repetido = executar(metodo, caminho, corpo, sessao?.accessToken)
                return repetido.valor ?: JSONObject()
            }
        }

        // Erro vira exceção só aqui: a tentativa de renovação aconteceu antes,
        // então o `ErroApi` que a UI vê é sempre o final, não o intermediário.
        if (primeira.falhou) {
            throw ErroApi(
                status = primeira.status,
                codigo = primeira.codigo,
                mensagem = primeira.mensagem,
            )
        }

        return primeira.valor ?: JSONObject()
    }

    /** Uma resposta HTTP. [falhou] separa "erro" de "corpo vazio" — 204 é sucesso. */
    private data class Resposta(
        val valor: Any? = null,
        val status: Int = 0,
        val codigo: String = "",
        val mensagem: String = "",
        val texto: String? = null,
    ) {
        val falhou: Boolean get() = status >= 400
        fun falhouCom(code: Int): Boolean = falhou && status == code
    }

    private suspend fun executar(
        metodo: String,
        caminho: String,
        corpo: JSONObject?,
        token: String?,
    ): Resposta = withContext(Dispatchers.IO) {
        val url = baseUrl()
        val primeira = tentar(url, metodo, caminho, corpo, token)
        val erroPrimeira = primeira.exceptionOrNull()
        if (erroPrimeira == null) return@withContext primeira.getOrThrow()

        // Trocar de host só quando o ATUAL está fora: se a sonda `/health`
        // responde, é seguro assumir que a requisição chegou — repetir em
        // outro host poderia duplicar um POST que o backend já gravou. Com o
        // servidor nem respondendo health, a requisição original não chegou a
        // ser entregue, então o retry é seguro.
        val alternativo = (erroPrimeira as? IOException)
            ?.takeUnless { alcancavel(url) }
            ?.let { trocarServidor(atual = url) }

        if (alternativo != null) {
            val segunda = tentar(alternativo, metodo, caminho, corpo, token)
            return@withContext segunda.getOrElse { throw ErroRede(it) }
        }

        throw ErroRede(erroPrimeira)
    }

    /**
     * Uma tentativa contra [url]. Falha vira `Result`, não exceção: o
     * [executar] precisa distinguir "rede caiu (dá pra trocar de host)" de
     * "resposta HTTP veio" antes de decidir se tenta de novo.
     */
    private fun tentar(
        url: String,
        metodo: String,
        caminho: String,
        corpo: JSONObject?,
        token: String?,
    ): Result<Resposta> = try {
        Result.success(chamar(url, metodo, caminho, corpo, token))
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun chamar(
        url: String,
        metodo: String,
        caminho: String,
        corpo: JSONObject?,
        token: String?,
    ): Resposta {
        val builder = Request.Builder().url(url + caminho)

        when (metodo) {
            "GET" -> builder.get()
            else -> builder.method(
                metodo,
                (corpo?.toString() ?: "{}").toRequestBody(json),
            )
        }

        // Token só quando existe. Nunca em query string: isso vaza em log de
        // servidor e no histórico do proxy.
        token?.let { builder.header("Authorization", "Bearer $it") }

        return client.newCall(builder.build()).execute().use { resposta ->
            val texto = resposta.body?.string().orEmpty()
            val obj = if (texto.isBlank()) {
                JSONObject()
            } else {
                runCatching { JSONObject(texto) }.getOrElse { JSONObject() }
            }

            if (!resposta.isSuccessful) {
                return Resposta(
                    valor = null,
                    texto = texto,
                    status = resposta.code,
                    codigo = obj.optString("error", "erro"),
                    // Erro 5xx não traz detalhe: o backend manda "erro interno".
                    mensagem = obj.optString("message", "Algo deu errado (${resposta.code})"),
                )
            }

            if (texto.isBlank()) return Resposta(valor = JSONObject(), texto = texto)

            // Objeto ou array: quem chamou decide. O backend usa as duas
            // formas e a diferença muda o parse.
            Resposta(
                texto = texto,
                valor = runCatching { JSONObject(texto) }.getOrNull()
                    ?: runCatching { JSONArray(texto) }.getOrNull()
                    ?: JSONObject()
            )
        }
    }

    /**
     * Troca o refresh por um par novo de tokens.
     *
     * @param geracaoQuandoFalhou o valor de [SessionStore.geracao] no momento em
     *   que a requisição com [accessUsado] deu 401.
     *
     * @return `true` se há um access válido para repetir a requisição.
     */
    private suspend fun renovarSeUsando(geracaoQuandoFalhou: Long): Boolean = mutexRenovacao.withLock {
        val s = sessao ?: return@withLock false

        // Outra corrotina renovou enquanto esta esperava o lock: há token novo em
        // mãos, e repetir a requisição com ele é o certo. A comparação é pelo
        // contador de geração e NÃO pelo access: duas emissões no mesmo segundo
        // devolvem o JWT idêntico, e comparar a string diria "ninguém renovou".
        if (s.geracao != geracaoQuandoFalhou) return@withLock true

        val refresh = s.refreshToken ?: return@withLock false

        val resposta = try {
            executar("POST", "/auth/refresh", JSONObject().put("refresh_token", refresh), null)
        } catch (_: Exception) {
            // Rede caiu no meio da renovação. Não limpa a sessão: pode ser só
            // falta de conexão, e o usuário ainda tem um refresh válido.
            return@withLock false
        }

        val corpo = resposta.valor as? JSONObject
        val access = corpo?.optString("access_token").orEmpty()
        val refreshNovo = corpo?.optString("refresh_token").orEmpty()

        // 401 aqui é o caso terminal: refresh revogado, expirado ou rotacionado
        // por outra sessão. Insistir não adianta — o usuário precisa logar de novo.
        if (resposta.falhou || access.isBlank() || refreshNovo.isBlank()) {
            s.limpar()
            // O callback vai atualizar o `StateFlow` da UI, e isso TEM de
            // acontecer na main thread: `StateFlow` notifica os coletores, e o
            // coletor do `AppViewModel` roda em `Dispatchers.Main`. Disparar
            // daqui (que é uma corrotina de I/O) lança no coletor e mata o
            // app com `FATAL EXCEPTION` em vez de mostrar o login.
            withContext(Dispatchers.Main) { aoRevogarSessao?.invoke() }
            return@withLock false
        }

        s.salvar(access, refreshNovo)
        true
    }
}