package com.kavelipay.app.data

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

    /** Base URL do backend.
     *
     * - `10.0.2.2` = loopback do host no emulador AVD.
     * - `192.168.1.8` = IP da máquina na rede Wi-Fi (aparelho físico, mesma rede).
     * - `192.168.240.1` = lado host da bridge do Waydroid — o container só tem
     *   rota para `192.168.240.0/24`, então não alcança `192.168.1.8`.
     */
    const val BASE_URL = "http://192.168.240.1:3000"

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
        val builder = Request.Builder().url(BASE_URL + caminho)

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

        try {
            client.newCall(builder.build()).execute().use { resposta ->
                val texto = resposta.body?.string().orEmpty()
                val obj = if (texto.isBlank()) {
                    JSONObject()
                } else {
                    runCatching { JSONObject(texto) }.getOrElse { JSONObject() }
                }

                if (!resposta.isSuccessful) {
                    return@withContext Resposta(
                        valor = null,
                        texto = texto,
                        status = resposta.code,
                        codigo = obj.optString("error", "erro"),
                        // Erro 5xx não traz detalhe: o backend manda "erro interno".
                        mensagem = obj.optString("message", "Algo deu errado (${resposta.code})"),
                    )
                }

                if (texto.isBlank()) return@withContext Resposta(valor = JSONObject(), texto = texto)

                // Objeto ou array: quem chamou decide. O backend usa as duas
                // formas e a diferença muda o parse.
                Resposta(
                    texto = texto,
                    valor = runCatching { JSONObject(texto) }.getOrNull()
                        ?: runCatching { JSONArray(texto) }.getOrNull()
                        ?: JSONObject()
                )
            }
        } catch (e: Exception) {
            throw ErroRede(e)
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