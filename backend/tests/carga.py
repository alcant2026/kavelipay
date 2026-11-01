#!/usr/bin/env python3
"""Teste de carga mínimo, sem dependência externa (só stdlib).

Mede latência e vazão de rotas de leitura do backend. Serve para responder
"aguenta quanta gente?" com número em vez de achismo.

    # contra o backend local (com banco de dev), exercitando SQL:
    python3 tests/carga.py --url http://localhost:3000 --auth -n 400 -c 20

    # contra produção, só liveness (seguro: não cria usuário nem acorda o Neon):
    python3 tests/carga.py --url https://kavelipay-backend.onrender.com -n 100 -c 5

Com `--auth` ele cria (ou reaproveita) a conta de teste carga@kavelipay.dev e
percente o pool de conexões com consultas de verdade. **Não** use `--auth`
contra produção sem querer apagar a conta depois.

Sai com código 1 se mais de 5% das respostas não forem 2xx — vira tentativa
de teste, não só relatório.
"""

import argparse
import http.client
import json
import queue
import ssl
import statistics
import sys
import threading
import time
from urllib.parse import urlsplit

EMAIL = "carga@kavelipay.dev"
SENHA = "senha-de-carga-123"


def requisicao(conn, metodo, caminho, corpo=None, token=None):
    cabecalhos = {"Content-Type": "application/json"}
    if token:
        cabecalhos["Authorization"] = f"Bearer {token}"
    dados = json.dumps(corpo).encode() if corpo is not None else None
    inicio = time.perf_counter()
    conn.request(metodo, caminho, body=dados, headers=cabecalhos)
    resposta = conn.getresponse()
    payload = resposta.read()
    duracao = time.perf_counter() - inicio
    return resposta.status, payload, duracao


def conexao(host, porta, tls):
    if tls:
        return http.client.HTTPSConnection(
            host, porta, timeout=90, context=ssl.create_default_context()
        )
    return http.client.HTTPConnection(host, porta, timeout=90)


def obter_token(host, porta, tls):
    """Cadastra a conta de carga ou entra nela, e devolve o access token."""
    conn = conexao(host, porta, tls)
    status, corpo, _ = requisicao(
        conn,
        "POST",
        "/auth/register",
        {"email": EMAIL, "password": SENHA, "display_name": "Carga"},
    )
    if status not in (200, 201):
        status, corpo, _ = requisicao(
            conn, "POST", "/auth/login", {"email": EMAIL, "password": SENHA}
        )
    if status not in (200, 201):
        sys.exit(f"não consegui autenticar a conta de carga: HTTP {status}")
    dados = json.loads(corpo)
    token = dados.get("access_token") or dados.get("token")
    if not token:
        sys.exit(f"resposta sem access_token: {list(dados)}")
    return token


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--url", default="http://localhost:3000")
    p.add_argument("-n", "--requisicoes", type=int, default=300)
    p.add_argument("-c", "--concorrencia", type=int, default=10)
    p.add_argument(
        "--auth",
        action="store_true",
        help="usa /wallet e /wallet/transactions (toca banco); sem isso só /health",
    )
    args = p.parse_args()

    partes = urlsplit(args.url)
    host = partes.hostname
    tls = partes.scheme == "https"
    porta = partes.port or (443 if tls else 80)

    token = obter_token(host, porta, tls) if args.auth else None
    caminhos = (
        ["/wallet", "/wallet", "/wallet", "/wallet/transactions?limit=20"]
        if args.auth
        else ["/health"]
    )

    fila = queue.Queue()
    for i in range(args.requisicoes):
        fila.put(caminhos[i % len(caminhos)])

    latencias, status_todos, erros = [], [], []
    trava = threading.Lock()

    def trabalhador():
        conn = conexao(host, porta, tls)
        local_lat, local_st, local_err = [], [], []
        while True:
            try:
                caminho = fila.get_nowait()
            except queue.Empty:
                break
            try:
                status, _, duracao = requisicao(conn, "GET", caminho, token=token)
                local_lat.append(duracao)
                local_st.append(status)
            except Exception as e:  # rede caiu, timeout, conexão fechada
                local_err.append(f"{caminho}: {type(e).__name__}")
                conn.close()
                conn = conexao(host, porta, tls)
        with trava:
            latencias.extend(local_lat)
            status_todos.extend(local_st)
            erros.extend(local_err)

    inicio = time.perf_counter()
    threads = [
        threading.Thread(target=trabalhador)
        for _ in range(min(args.concorrencia, args.requisicoes))
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    total_s = time.perf_counter() - inicio

    if not latencias:
        sys.exit(f"nenhuma resposta: {erros[:3]}")

    ms = sorted(x * 1000 for x in latencias)

    def pct(p):
        return ms[min(len(ms) - 1, int(len(ms) * p))]

    bons = sum(1 for s in status_todos if 200 <= s < 300)
    print(f"url            {args.url}{' (com auth)' if args.auth else ' (só /health)'}")
    print(f"requisições    {len(status_todos)} em {total_s:.1f}s")
    print(f"vazão          {len(status_todos) / total_s:.1f} req/s")
    print(f"latência       p50 {pct(0.50):.0f} ms · p95 {pct(0.95):.0f} ms · "
          f"p99 {pct(0.99):.0f} ms · max {ms[-1]:.0f} ms")
    print(f"status         {dict(sorted((s, status_todos.count(s)) for s in set(status_todos)))}")
    if erros:
        print(f"erros de rede  {len(erros)} (ex.: {erros[0]})")

    if bons / len(status_todos) < 0.95:
        sys.exit(1)


if __name__ == "__main__":
    main()
