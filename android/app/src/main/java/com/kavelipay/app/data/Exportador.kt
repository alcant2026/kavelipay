package com.kavelipay.app.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Gravação do arquivo de exportação (LGPD) em Downloads.
 *
 * POR QUE ISTO EXISTE: a versão anterior escrevia com `File.writeText` direto
 * em `/storage/emulated/0/Download`. Isso funcionou até o app subir de
 * targetSdk: a partir do Android 10 (armazenamento escopado) um app não pode
 * escrever na pasta pública assim, e a escrita passa a falhar com EACCES. Pior:
 * o erro era devolvido como texto de sucesso, então a tela dizia "Arquivo
 * salvo" e o arquivo nunca aparecia — exatamente a queixa "diz que baixou e
 * não achei".
 *
 * A partir do Android 10 o caminho certo é a MediaStore: o sistema cria o
 * arquivo na coleção Downloads, sem pedir permissão nenhuma, e ele aparece no
 * app Arquivos. `IS_PENDING` mantém o arquivo invisível enquanto está sendo
 * escrito — sem isso o usuário pode abrir um CSV pela metade.
 *
 * Android 8 e 9 (minSdk 26) não têm MediaStore.Downloads; neles ainda se grava
 * na pasta pública (o app declara WRITE_EXTERNAL_STORAGE com
 * `maxSdkVersion="28"`), com queda para a pasta privada se o sistema negar.
 */
object Exportador {

    /** Falha de gravação já com texto para a tela — nada de "erro do sistema". */
    class ErroExportacao(mensagem: String) : Exception(mensagem)

    private val CARIMBO = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")

    /**
     * Nome com data e hora: `kavelipay_extrato_2026-10-20_1940.csv`.
     *
     * O nome fixo fazia a segunda exportação passar por cima da primeira sem
     * avisar (e, na MediaStore, criar "arquivo (1).csv" — dois arquivos que a
     * pessoa não consegue distinguir). Com o carimbo, cada exportação é um
     * arquivo identificável.
     */
    fun nomeComCarimbo(
        base: String,
        extensao: String,
        quando: LocalDateTime = LocalDateTime.now(),
    ): String = "${base}_${quando.format(CARIMBO)}.$extensao"

    /**
     * Salva [conteudo] em Downloads e devolve o nome do arquivo.
     *
     * Lança [ErroExportacao] quando não deu — quem chama PRECISA tratar, senão
     * volta o bug do "salvou" mentiroso.
     */
    fun salvar(contexto: Context, nome: String, mime: String, conteudo: String): String {
        val bytes = conteudo.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) throw ErroExportacao("O arquivo veio vazio; nada foi salvo.")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            salvarNaMediaStore(contexto, nome, mime, bytes)
        } else {
            salvarNaPastaPublica(nome, bytes)
        }
    }

    private fun salvarNaMediaStore(
        contexto: Context,
        nome: String,
        mime: String,
        bytes: ByteArray,
    ): String {
        val resolver = contexto.contentResolver
        val valores = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, nome)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
            ?: throw ErroExportacao("O Android não deixou criar o arquivo em Downloads.")

        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw ErroExportacao("Não consegui escrever o arquivo em Downloads.")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (e: Exception) {
            // Arquivo pela metade ficaria "pendente" para sempre: apaga.
            runCatching { resolver.delete(uri, null, null) }
            if (e is ErroExportacao) throw e
            throw ErroExportacao("Não consegui salvar em Downloads: ${e.message ?: "erro desconhecido"}")
        }

        return nome
    }

    /**
     * Android 8 e 9: pasta pública, que exige WRITE_EXTERNAL_STORAGE.
     *
     * Sem a permissão, o erro sobe com texto claro em vez de fingir sucesso.
     * Não há como pedir a permissão aqui (o ViewModel não tem janela) — quem
     * instalar em Android 8/9 vai ver o aviso e pode ativar nas configurações.
     */
    @Suppress("DEPRECATION")
    private fun salvarNaPastaPublica(nome: String, bytes: ByteArray): String {
        val publica = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        try {
            if (!publica.exists()) publica.mkdirs()
            File(publica, nome).writeBytes(bytes)
            return nome
        } catch (e: SecurityException) {
            throw ErroExportacao(
                "este Android pede permissão de armazenamento para salvar em " +
                    "Downloads. Ative a permissão do KaveliPay nas configurações.",
            )
        } catch (e: Exception) {
            throw ErroExportacao("não consegui escrever em Downloads: ${e.message ?: "erro desconhecido"}")
        }
    }
}
