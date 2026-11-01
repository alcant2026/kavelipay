package com.kavelipay.app.ui

/**
 * Quem responde pelo app — Decreto 7.962/2013 e CDC exigem nome empresarial,
 * CNPJ e canal de atendimento visíveis antes da compra.
 *
 * O CNPJ fica vazio até a formalização (MEI ou ME) e a tela mostra "em
 * formalização". Número inventado aqui seria pior que a ausência: o usuário
 * confere no site da Receita e não acha nada.
 */
object Fornecedor {
    const val NOME = "KaveliPay"

    /** Preencher com o CNPJ real assim que o MEI/ME estiver aberto. */
    const val CNPJ = ""

    /** Canal único de atendimento, privacidade e reembolso. */
    const val EMAIL = "josiassm701@gmail.com"

    val REFERENCIA: String =
        if (CNPJ.isBlank()) "CNPJ em formalização" else "CNPJ $CNPJ"
}
