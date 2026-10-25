package com.kavelipay.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Guarda access e refresh token em `EncryptedSharedPreferences`
 * (`androidx.security:security-crypto`): as chaves e os valores ficam
 * cifrados por chave da Android Keystore. Necessário num app que lida com
 * dado financeiro — prefs em texto plano eram legíveis em rooted device.
 *
 * Migração do prefs legado: na inicialização, se os prefs encriptados ainda
 * estiverem vazios e o prefs plano (`kavelipay.session`) tiver dados, os
 * valores são copiados, e o arquivo legado é limpo e apagado. O arquivo legado
 * nunca volta a ser usado para escrita.
 */
class SessionStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val encriptado = EncryptedSharedPreferences.create(
            context,
            "kavelipay.session.secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        migrar(context, encriptado)
        encriptado
    }

    /** Migra o prefs plano para o encriptado (uma vez; depois só apaga resto). */
    private fun migrar(context: Context, encriptado: SharedPreferences) {
        val legado = context.getSharedPreferences("kavelipay.session", Context.MODE_PRIVATE)
        val temLegado = TODAS_AS_CHAVES.any { legado.contains(it) }
        if (!temLegado) return

        if (TODAS_AS_CHAVES.none { encriptado.contains(it) }) {
            encriptado.edit().apply {
                TODAS_AS_CHAVES.forEach { key ->
                    legado.getString(key, null)?.let { putString(key, it) }
                }
            }.apply()
        }
        legado.edit().clear().apply()
        context.deleteSharedPreferences("kavelipay.session")
    }

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
        val TODAS_AS_CHAVES = listOf(KEY_ACCESS, KEY_REFRESH, KEY_USER_ID, KEY_NOME)
    }
}
