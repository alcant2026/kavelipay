package com.kavelipay.app.ui.screens

/**
 * Nome do recurso na tela.
 *
 * No banco, na API e no código ele continua `caixinha` — é o nome interno e
 * mudar isso agora custaria uma migration e um retrabalho de endpoint sem
 * ganho para ninguém. O que muda é o que a pessoa lê.
 *
 * "Caixinha" é o nome do Nubank para **dinheiro guardado com rendimento**. Aqui
 * é o oposto: um **empréstimo entre conhecidos** registrado fora do ledger, com
 * credor, taxa e cobrança. Chamar igual ao produto de outro banco é o tipo de
 * erro que a pessoa só descobre quando confia no nome errado — então o rótulo
 * diz o que é.
 *
 * Uma constante só, em vez da mesma string em vinte lugares: o dia em que o
 * nome mudar de novo, muda aqui.
 */
internal object NomeCaixinha {
    /** Nome curto para título de tela e rótulo de menu. */
    const val SINGULAR = "Empréstimo"

    /** Nome no plural para a lista. */
    const val PLURAL = "Empréstimos"

    /** Frase de uma linha que explica o que é, para o cartão de entrada. */
    const val EXPLICACAO =
        "Dinheiro emprestado entre conhecidos, com taxa combinada e registro de quem pagou o quê."

    /** Aviso de que o dinheiro não passa pelo app — a regra inegociável do MVP. */
    const val AVISO =
        "O dinheiro não passa pelo KaveliPay: cada um paga o outro como combinaram. " +
            "Aqui fica o registro, não a transferência."
}