package com.kavelipay.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kavelipay.app.ui.AppViewModel
import com.kavelipay.app.ui.components.KpBotaoPrimario
import com.kavelipay.app.ui.components.KpBotaoSecundario
import com.kavelipay.app.ui.components.KpCard
import com.kavelipay.app.ui.theme.RaioCard
import com.kavelipay.app.ui.theme.Spacing
import com.kavelipay.app.ui.theme.TituloGrande

/**
 * Entrada: login e cadastro.
 *
 * Uma tela só, alternando modo. Baseado no e-mail, o cadastro quase nunca é
 * necessário depois — um usuário novo se cadastra, um existente faz login.
 */
@Composable
fun KpLoginScreen(vm: AppViewModel, aoEntrar: () -> Unit) {
    var criando by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var senha by rememberSaveable { mutableStateOf("") }
    var nome by rememberSaveable { mutableStateOf("") }
    var mostrarSenha by rememberSaveable { mutableStateOf(false) }

    // Recuperação de senha: pede o e-mail, depois o código que chegou nele.
    var recuperando by rememberSaveable { mutableStateOf(false) }
    var codigoEnviado by rememberSaveable { mutableStateOf(false) }
    var codigo by rememberSaveable { mutableStateOf("") }
    var novaSenha by rememberSaveable { mutableStateOf("") }
    var aviso by rememberSaveable { mutableStateOf<String?>(null) }

    val mascaraSenha = if (mostrarSenha) VisualTransformation.None else PasswordVisualTransformation()
    val botaoOlho: @Composable () -> Unit = {
        IconButton(onClick = { mostrarSenha = !mostrarSenha }) {
            Icon(
                if (mostrarSenha) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = if (mostrarSenha) "Ocultar senha" else "Mostrar senha",
            )
        }
    }

    val estado by vm.estado.collectAsState()

    LaunchedEffect(estado.autenticado) {
        if (estado.autenticado) aoEntrar()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg),
        ) {
            Spacer(Modifier.height(Spacing.xxl))

            Text(
                "KaveliPay",
                style = TituloGrande,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "Sua vida financeira em um só lugar — sem custódia de dinheiro real.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Spacing.xl))

            KpCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Spacing.md)) {
                    when {
                        // "Esqueci minha senha": primeiro o e-mail, para saber
                        // quem recebe o código.
                        recuperando && !codigoEnviado -> {
                            KpCampo(
                                valor = email,
                                aoMudar = { email = it },
                                rotulo = "E-mail da conta",
                                teclado = KeyboardType.Email,
                                imeAction = ImeAction.Done,
                            )
                            ErroLogin(estado.erro)
                            Spacer(Modifier.height(Spacing.md))
                            KpBotaoPrimario(
                                texto = "Enviar código",
                                onClick = {
                                    aviso = null
                                    vm.esquecerSenha(email) { codigoEnviado = true }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                habilitado = email.isNotBlank(),
                                carregando = estado.carregando,
                            )
                        }

                        // …e depois o código que chegou, com a senha nova.
                        recuperando -> {
                            Text(
                                "Enviamos um código para $email. Ele vale por 30 minutos.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            KpCampo(
                                valor = codigo,
                                aoMudar = { codigo = it },
                                rotulo = "Código do e-mail",
                                imeAction = ImeAction.Next,
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            KpCampo(
                                valor = novaSenha,
                                aoMudar = { novaSenha = it },
                                rotulo = "Senha nova",
                                teclado = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                                mascara = mascaraSenha,
                                sufixo = botaoOlho,
                            )
                            ErroLogin(estado.erro)
                            Spacer(Modifier.height(Spacing.md))
                            KpBotaoPrimario(
                                texto = "Salvar senha nova",
                                onClick = {
                                    vm.redefinirSenha(codigo, novaSenha) {
                                        recuperando = false
                                        codigoEnviado = false
                                        codigo = ""
                                        novaSenha = ""
                                        senha = ""
                                        aviso = "Senha alterada. Entre com a senha nova."
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                // 8 é a mesma regra do backend: recusar aqui
                                // evita uma ida ao servidor para ouvir "senha curta".
                                habilitado = codigo.isNotBlank() && novaSenha.length >= 8,
                                carregando = estado.carregando,
                            )
                        }

                        else -> {
                            if (criando) {
                                KpCampo(
                                    valor = nome,
                                    aoMudar = { nome = it },
                                    rotulo = "Nome",
                                    imeAction = ImeAction.Next,
                                )
                                Spacer(Modifier.height(Spacing.sm))
                            }

                            KpCampo(
                                valor = email,
                                aoMudar = { email = it },
                                rotulo = "E-mail",
                                teclado = KeyboardType.Email,
                                imeAction = ImeAction.Next,
                            )
                            Spacer(Modifier.height(Spacing.sm))

                            KpCampo(
                                valor = senha,
                                aoMudar = { senha = it },
                                rotulo = "Senha",
                                teclado = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                                mascara = mascaraSenha,
                                sufixo = botaoOlho,
                            )

                            ErroLogin(estado.erro)

                            Spacer(Modifier.height(Spacing.md))

                            KpBotaoPrimario(
                                texto = if (criando) "Criar conta" else "Entrar",
                                onClick = {
                                    aviso = null
                                    if (criando) vm.registrar(email, senha, nome) else vm.entrar(email, senha)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                // Desabilitar com email vazio evita um 400 inútil.
                                habilitado = email.isNotBlank() && senha.isNotBlank(),
                                carregando = estado.carregando,
                            )
                        }
                    }
                }
            }

            aviso?.let {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(Spacing.md))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (recuperando) {
                    TextButton(onClick = {
                        recuperando = false
                        codigoEnviado = false
                        vm.limparErro()
                    }) {
                        Text("Voltar para o login", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    TextButton(onClick = {
                        criando = !criando
                        vm.limparErro()
                    }) {
                        Text(
                            if (criando) "Já tenho conta" else "Quero criar conta",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (!criando) {
                        TextButton(onClick = {
                            recuperando = true
                            aviso = null
                            vm.limparErro()
                        }) {
                            Text("Esqueci minha senha", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.md))

            Text(
                "Seus registros ficam visíveis e exportáveis. " +
                    "A KaveliPay não recebe, guarda nem movimenta dinheiro real.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

/** Erro da entrada, sempre no mesmo lugar nos três modos (entrar, criar, recuperar). */
@Composable
private fun ErroLogin(erro: String?) {
    AnimatedVisibility(erro != null) {
        Column {
            Spacer(Modifier.height(Spacing.md))
            KpMensagemErro(erro.orEmpty())
        }
    }
}

@Composable
fun KpCampo(
    valor: String,
    aoMudar: (String) -> Unit,
    rotulo: String,
    modifier: Modifier = Modifier,
    teclado: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    mascara: VisualTransformation = VisualTransformation.None,
    sufixo: @Composable (() -> Unit)? = null,
    erro: String? = null,
    umaLinha: Boolean = false,
) {
    OutlinedTextField(
        value = valor,
        onValueChange = aoMudar,
        modifier = modifier.fillMaxWidth(),
        label = { Text(rotulo) },
        singleLine = umaLinha,
        isError = erro != null,
        supportingText = erro?.let { { Text(it, style = MaterialTheme.typography.labelSmall) } },
        visualTransformation = mascara,
        keyboardOptions = KeyboardOptions(keyboardType = teclado, imeAction = imeAction),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onDone = {},
        ),
        trailingIcon = sufixo,
        shape = RoundedCornerShape(com.kavelipay.app.ui.theme.RaioInput),
    )
}

/** Erro de negócio. Fundo avermelhado só aqui — despesa não usa vermelho. */
@Composable
fun KpMensagemErro(mensagem: String) {
    val sem = com.kavelipay.app.ui.theme.KpTheme.semanticas
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RaioCard))
            .background(sem.vencidoFundo)
            .padding(Spacing.md),
    ) {
        Text(
            mensagem,
            style = MaterialTheme.typography.bodyMedium,
            color = sem.vencido,
        )
    }
}
