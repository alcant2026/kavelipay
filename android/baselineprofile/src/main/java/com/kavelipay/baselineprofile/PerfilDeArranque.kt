package com.kavelipay.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mede o arranque e as primeiras interações do app e transforma isso num
 * Baseline Profile.
 *
 * O que entra no perfil é só o que este bloco EXECUTA: abrir o app, esperar a
 * Home desenhar e rolar a lista. Não adianta listar tela que a pessoa talvez
 * abra — perfil grande demais engorda o dex e atrasa justamente o arranque
 * que ele deveria acelerar.
 *
 * Rodar (com aparelho/emulador conectado):
 *
 *     ./gradlew :app:generateBaselineProfile
 *
 * O resultado é gravado em `app/src/main/baseline-prof.txt` (configurado com
 * `saveInSrc` no módulo do app), então builds seguintes já o embutem e não
 * precisam de aparelho.
 */
@RunWith(AndroidJUnit4::class)
class PerfilDeArranque {

    @get:Rule
    val regra = BaselineProfileRule()

    @Test
    fun geraPerfil() = regra.collect(
        packageName = "com.kavelipay.app",
        profileBlock = {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()

            // A Home desenha o cartão de saldo e os atalhos; rolar exercita a
            // composição das listas, que é o custo de primeira abertura.
            repeat(2) {
                device.swipe(540, 1200, 540, 500, 20)
                device.waitForIdle()
            }
        },
    )
}
