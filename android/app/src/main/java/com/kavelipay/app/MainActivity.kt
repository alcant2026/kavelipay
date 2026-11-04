package com.kavelipay.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kavelipay.app.data.ApiClient
import com.kavelipay.app.data.SessionStore
import com.kavelipay.app.data.TemaStore
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.screens.KpRaiz
import com.kavelipay.app.ui.screens.tokenDeCobranca
import com.kavelipay.app.ui.theme.KaveliPayTheme

/**
 * `FragmentActivity` e não `ComponentActivity`: o `BiometricPrompt` da
 * AndroidX pede um `FragmentActivity` (ele usa o fragment manager para o
 * diálogo nas versões antigas do Android).
 */
class MainActivity : FragmentActivity() {

    /**
     * Token do deep link `kavelipay://cobranca/<token>`, se veio um.
     *
     * Fica na Activity (e não no `KpRaiz`) porque o Intent chega antes do
     * Compose e pode chegar de novo com o app já aberto — `onNewIntent`, que
     * só é chamado por causa do `launchMode="singleTop"` do manifesto.
     */
    private val linkCobranca = mutableStateOf<String?>(null)

    /** `true` mostra a tela de bloqueio no lugar do app. */
    private val travado = mutableStateOf(false)

    /**
     * `true` enquanto o diálogo de biometria está aberto.
     *
     * Em algumas versões do Android o diálogo do sistema tira a Activity de
     * `RESUMED`, e sem esta trava o `onStop` re-travaria o app logo depois do
     * desbloqueio — em loop.
     */
    internal var confirmando = false

    private lateinit var sessao: SessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Antes de qualquer requisição: o `ApiClient` precisa do Contexto para
        // lembrar qual host do backend respondeu (Waydroid, aparelho físico ou
        // AVD). Sem persistência, a sonda de host recomeçaria a cada abertura.
        ApiClient.configurar(applicationContext)
        sessao = SessionStore(applicationContext)
        // Quem já tem sessão abre travado; quem não tem vê o login.
        travado.value = sessao.accessToken != null
        linkCobranca.value = tokenDoIntent(intent)
        setContent {
            if (travado.value && sessao.accessToken != null) {
                KaveliPayTheme(escuro = isSystemInDarkTheme()) {
                    TelaBloqueio(aoDesbloquear = { travado.value = false })
                }
            } else {
                AppRaiz(
                    sessao = sessao,
                    linkCobranca = linkCobranca.value,
                    aoFecharLink = { linkCobranca.value = null },
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Sair do app trava de novo. `isChangingConfigurations` cobre a
        // rotação de tela, que também passa por `onStop`.
        if (!isChangingConfigurations && !confirmando && sessao.accessToken != null) {
            travado.value = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // `?.let`: abrir o app pelo launcher (ou qualquer Intent sem link) não
        // pode apagar uma cobrança que já está aberta.
        tokenDoIntent(intent)?.let { linkCobranca.value = it }
    }

    /**
     * Lê o token do link. `null` para qualquer outra entrada (launcher, share
     * comum). A validação é a mesma do leitor de QR — `tokenDeCobranca` — para
     * link torto virar tela de "não encontrada", não erro de programação.
     */
    private fun tokenDoIntent(intent: Intent?): String? =
        intent?.dataString?.let(::tokenDeCobranca)
}

/**
 * Tela de bloqueio: biometria com queda para o PIN/padrão/senha do aparelho
 * (`DEVICE_CREDENTIAL`) — o app não guarda senha própria nenhuma.
 */
@Composable
private fun TelaBloqueio(aoDesbloquear: () -> Unit) {
    val contexto = LocalContext.current
    val atividade = contexto as? MainActivity
    val aceitos = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val disponivel = remember(contexto) {
        BiometricManager.from(contexto).canAuthenticate(aceitos) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }
    var erro by remember { mutableStateOf<String?>(null) }

    // Aparelho sem digital/rosto e sem PIN cadastrado: travar aqui seria
    // trancar o dono fora do próprio app.
    if (!disponivel || atividade == null) {
        LaunchedEffect(Unit) { aoDesbloquear() }
        return
    }

    val pedir: () -> Unit = {
        atividade.confirmando = true
        BiometricPrompt(
            atividade,
            ContextCompat.getMainExecutor(contexto),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    atividade.confirmando = false
                    aoDesbloquear()
                }

                override fun onAuthenticationError(codigo: Int, mensagem: CharSequence) {
                    // Cancelou ou errou: fica travado, com o botão à mão.
                    atividade.confirmando = false
                    erro = "Não deu para confirmar. Toque em Desbloquear."
                }
            },
        ).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Desbloquear o KaveliPay")
                .setSubtitle("Use sua digital, rosto ou o PIN do aparelho")
                .setAllowedAuthenticators(aceitos)
                .build(),
        )
    }

    LaunchedEffect(Unit) { pedir() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("KaveliPay bloqueado", style = MaterialTheme.typography.headlineSmall)
        Text(
            erro ?: "Confirme sua identidade para ver seus dados.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = pedir) { Text("Desbloquear") }
    }
}

@Composable
private fun AppRaiz(
    sessao: SessionStore,
    linkCobranca: String?,
    aoFecharLink: () -> Unit,
) {
    val contexto = LocalContext.current

    // Tema na RAIZ, não na Home: só aqui a escolha alcança o `KaveliPayTheme`
    // de verdade e é persistida (`null` = ainda sem escolha → segue o sistema).
    val temaStore = remember { TemaStore(contexto.applicationContext) }
    var preferenciaTema by remember { mutableStateOf(temaStore.ler()) }
    val escuro = preferenciaTema ?: isSystemInDarkTheme()

    val factory = remember(sessao) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(sessao, contexto.applicationContext) as T
        }
    }

    val vm: AppViewModel = viewModel(factory = factory)

    KaveliPayTheme(escuro = escuro) {
        KpRaiz(
            vm = vm,
            temaEscuro = escuro,
            aoAlternarTema = {
                val novo = !escuro
                preferenciaTema = novo
                temaStore.salvar(novo)
            },
            linkCobranca = linkCobranca,
            aoFecharLink = aoFecharLink,
        )
    }
}
