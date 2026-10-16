package com.kavelipay.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kavelipay.app.data.ApiClient
import com.kavelipay.app.data.SessionStore
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.screens.KpRaiz
import com.kavelipay.app.ui.theme.KaveliPayTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Antes de qualquer requisição: o `ApiClient` precisa do Contexto para
        // lembrar qual host do backend respondeu (Waydroid, aparelho físico ou
        // AVD). Sem persistência, a sonda de host recomeçaria a cada abertura.
        ApiClient.configurar(applicationContext)
        setContent { AppRaiz() }
    }
}

@Composable
private fun AppRaiz() {
    val contexto = LocalContext.current

    // `remember`: o SessionStore guarda o token e precisa sobreviver a
    // recomposição. Recriá-lo a cada render limparia a sessão.
    val sessao = remember { SessionStore(contexto.applicationContext) }

    val factory = remember(sessao) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(sessao) as T
        }
    }

    val vm: AppViewModel = viewModel(factory = factory)

    KaveliPayTheme(escuro = isSystemInDarkTheme()) {
        KpRaiz(vm)
    }
}
