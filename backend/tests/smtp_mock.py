#!/usr/bin/env python3
"""Mock de SMTP para o e2e de recuperação de senha.

Fala o mínimo do protocolo que o backend usa (EHLO, AUTH LOGIN, MAIL, RCPT,
DATA, QUIT) e grava cada mensagem recebida em `<arquivo>`. O corpo vem em
base64 (é assim que o `mail.rs` manda) e é decodificado aqui, senão o teste
teria de reimplementar o mesmo decode.

Uso:  smtp_mock.py <porta> <arquivo-de-saida>
"""

import base64
import socketserver
import sys

PORTA = int(sys.argv[1])
ARQUIVO = sys.argv[2]


def ler(fluxo):
    return fluxo.readline().decode("utf-8", "replace").rstrip("\r\n")


class Handler(socketserver.StreamRequestHandler):
    def responder(self, texto):
        self.wfile.write(texto.encode() + b"\r\n")
        self.wfile.flush()

    def handle(self):
        self.responder("220 mock ESMTP")

        while True:
            linha = ler(self.rfile)
            if not linha:
                return
            comando = linha.upper()

            if comando.startswith(("EHLO", "HELO")):
                # Multilinha de propósito: exercita o parser de resposta do
                # backend no formato que Gmail/Brevo usam.
                self.responder("250-mock")
                self.responder("250-AUTH LOGIN")
                self.responder("250 OK")
            elif comando.startswith("AUTH LOGIN"):
                self.responder("334 VXNlcm5hbWU6")
                ler(self.rfile)
                self.responder("334 UGFzc3dvcmQ6")
                ler(self.rfile)
                self.responder("235 ok")
            elif comando.startswith(("MAIL FROM", "RCPT TO", "RSET", "NOOP")):
                self.responder("250 ok")
            elif comando == "DATA":
                self.responder("354 manda")
                corpo = []
                while True:
                    linha = ler(self.rfile)
                    if linha == ".":
                        break
                    corpo.append(linha)
                self.gravar("\r\n".join(corpo))
                self.responder("250 enfileirado")
            elif comando == "QUIT":
                self.responder("221 tchau")
                return
            else:
                self.responder("250 ok")

    def gravar(self, mensagem):
        cabecalho, _, b64 = mensagem.partition("\r\n\r\n")
        try:
            corpo = base64.b64decode(b64.replace("\r\n", "")).decode("utf-8", "replace")
        except Exception:
            corpo = mensagem
        with open(ARQUIVO, "a", encoding="utf-8") as saida:
            saida.write(cabecalho + "\n\n" + corpo + "\n=====FIM=====\n")


class Servidor(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    with Servidor(("127.0.0.1", PORTA), Handler) as servidor:
        servidor.serve_forever()
