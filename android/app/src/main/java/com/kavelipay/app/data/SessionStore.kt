package com.kavelipay.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Guarda access e refresh token.
 *
 * LACUNA CONHECIDA DE PRODUÇÃO: isso é `SharedPreferences` em texto plano.
 * `androidx.security` (EncryptedSharedPreferences) não estava disponível no
 * ambiente; sem ele, o token fica legível em rooted device ou backup. Num app
 * que lida com dado financeiro isso não é aceitável para produção — trocar por
 * EncryptedSharedPreferences ou uma chave no Android Keystore é bloqueante de
 * release.
 *
 * `allowBackup=false` no manifesto já reduz uma superfície disso.
 */
class SessionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("kavelipay.session", Context.MODE_PRIVATE)

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)
        private set(value) = prefs.edit().putString(KEY_ACCESS, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)
        private set(value) = prefs.edit().putString(KEY_REFRESH, value).apply()

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        private set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var displayName: String?
        get() = prefs.getString(KEY_NOME, null)
        set(value) = prefs.edit().putString(KEY_NOME, value).apply()

    /**
     * Quantas vezes os tokens foram trocados nesta execução do app.
     *
     * Existe porque o access token NÃO serve para dizer "já renovaram?": `iat`
     * e `exp` do JWT têm granularidade de segundo, então duas emissões no
     * mesmo segundo, para o mesmo usuário, devolvem a string IDÊNTICA. Uma
     * corrotina que comparasse `accessToken` antes e depois de esperar o lock
     * concluiria "ninguém renovou" e partiria para rotacionar de novo — que é
     * exatamente o caminho que derruba as sessões do usuário no servidor.
     *
     * Não é persistido de propósito: só importa dentro de uma mesma execução,
     * que é onde a concorrência acontece.
     */
    @Volatile
    var geracao: Long = 0L
        private set

    val autenticado: Boolean get() = !accessToken.isNullOrBlank()

    fun salvar(access: String, refresh: String) {
        accessToken = access
        refreshToken = refresh
        geracao++
    }

    fun limpar() {
        prefs.edit().clear().apply()
        geracao++
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_NOME = "display_name"
    }
}