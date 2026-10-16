package com.kavelipay.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferência de tema (claro/escuro) persistida entre sessões.
 *
 * `null` = o usuário nunca escolheu → o app segue o sistema
 * (`isSystemInDarkTheme()`). Depois do primeiro clique no botão de tema da
 * TopBar, a escolha vale até ele trocar de novo.
 *
 * Existe porque o estado antigo vivia dentro da Home (`remember { dark }`):
 * o clique só trocava o ícone, o tema real não mudava e a escolha sumia ao
 * fechar o app.
 */
class TemaStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("kavelipay.tema", Context.MODE_PRIVATE)

    /** `true` = escuro, `false` = claro, `null` = sem escolha (seguir o sistema). */
    fun ler(): Boolean? = when (prefs.getString(KEY_TEMA, null)) {
        "escuro" -> true
        "claro" -> false
        else -> null
    }

    fun salvar(escuro: Boolean) {
        prefs.edit().putString(KEY_TEMA, if (escuro) "escuro" else "claro").apply()
    }

    private companion object {
        const val KEY_TEMA = "tema"
    }
}
