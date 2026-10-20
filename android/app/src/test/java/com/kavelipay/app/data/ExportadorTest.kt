package com.kavelipay.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Nome do arquivo exportado.
 *
 * O nome fixo (`kavelipay_extrato.csv`) fazia a segunda exportação sobrescrever
 * a primeira sem aviso — e, quando o arquivo foi para a MediaStore, virava
 * "kavelipay_extrato (1).csv", dois arquivos indistinguíveis. O carimbo é o que
 * deixa cada exportação identificável na pasta Downloads.
 */
class ExportadorTest {

    @Test
    fun `nome leva data e hora`() {
        assertEquals(
            "kavelipay_extrato_2026-10-20_1940.csv",
            Exportador.nomeComCarimbo(
                "kavelipay_extrato",
                "csv",
                LocalDateTime.of(2026, 10, 20, 19, 40),
            ),
        )
    }

    @Test
    fun `zero a esquerda nao some`() {
        assertEquals(
            "kavelipay_dados_2026-01-05_0907.json",
            Exportador.nomeComCarimbo(
                "kavelipay_dados",
                "json",
                LocalDateTime.of(2026, 1, 5, 9, 7),
            ),
        )
    }

    @Test
    fun `duas exportacoes no mesmo minuto teriam o mesmo nome`() {
        // Limite conhecido e aceito: o carimbo tem minuto, não segundo. Duas
        // exportações dentro do mesmo minuto geram nomes iguais (a MediaStore
        // resolve com "(1)"); o teste existe para que a decisão seja explícita.
        val agora = LocalDateTime.of(2026, 10, 20, 19, 40, 10)
        val depois = LocalDateTime.of(2026, 10, 20, 19, 40, 50)
        assertEquals(
            Exportador.nomeComCarimbo("kavelipay_dados", "json", agora),
            Exportador.nomeComCarimbo("kavelipay_dados", "json", depois),
        )
    }

    @Test
    fun `nome padrao tem o carimbo do momento`() {
        val nome = Exportador.nomeComCarimbo("kavelipay_extrato", "csv")
        assertTrue(
            "esperava o formato kavelipay_extrato_AAAA-MM-DD_HHmm.csv, veio $nome",
            Regex("""kavelipay_extrato_\d{4}-\d{2}-\d{2}_\d{4}\.csv""").matches(nome),
        )
    }
}
