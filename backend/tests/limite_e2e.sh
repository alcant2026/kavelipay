#!/usr/bin/env bash
# Testes de segurança: o freio global por IP e o IP real atrás do proxy.
#
# Sobe o servidor real contra um banco descartável com LIMITE_POR_MINUTO=5 —
# teto baixo de propósito, para o teste ser rápido e óbvio.
#
# Cobre: corte no sexto pedido do mesmo IP, isolamento entre IPs, e
# X-Forwarded-For (é ele que o Render injeta; sem ler o header, todos os
# usuários cairiam no mesmo balde e o limite derrubaria o app inteiro).
#
# Uso:  tests/limite_e2e.sh
# Env:   PGHOST PGPORT PGUSER PGPASSWORD

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="kavelipay_limite_test_$$"
E2E_PORTA=${BASE_PORT:-}
while [ -z "$E2E_PORTA" ]; do
  E2E_PORTA=$(( (RANDOM % 1000) + 4000 ))
  ss -ltn 2>/dev/null | grep -q ":${E2E_PORTA} " && E2E_PORTA=
done
BASE="http://127.0.0.1:${E2E_PORTA}"
SECRET="$(head -c 48 /dev/urandom | base64 | tr -d '\n')"
TETO=5

PSQL=(psql -v ON_ERROR_STOP=1 -q -X)
pass=0
fail=0
SERVER_PID=""
BIN="$ROOT/target/debug/kavelipay-backend"

cleanup() {
    [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
    "${PSQL[@]}" -d postgres -c "DROP DATABASE IF EXISTS \"$DB\";" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# status <expected> <descrição> <ip> [curl args extras...]
status() {
    local expected="$1" desc="$2" ip="$3"; shift 3
    local got
    got=$(curl -s -o /tmp/kp_limite_corpo -w '%{http_code}' \
        -H "X-Forwarded-For: $ip" "$@" "$BASE/health")
    if [ "$got" = "$expected" ]; then
        printf '  ok (%s): %s\n' "$got" "$desc"
        pass=$((pass + 1))
    else
        printf '  FALHOU: %s — esperado %s, recebeu %s\n' "$desc" "$expected" "$got"
        fail=$((fail + 1))
    fi
}

"${PSQL[@]}" -d postgres -c "CREATE DATABASE \"$DB\";"
[ -x "$BIN" ] || { echo "binário ausente: $BIN (rode cargo build)"; exit 1; }

echo "subindo servidor (teto ${TETO}/min)"
DATABASE_URL="postgres://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${DB}" \
JWT_SECRET="$SECRET" \
BIND_ADDR="127.0.0.1:${E2E_PORTA}" \
LIMITE_POR_MINUTO="$TETO" \
RUST_LOG=warn \
"$BIN" &
SERVER_PID=$!

# A espera também passa pelo limitador, então usa um IP próprio do teto.
for _ in $(seq 1 40); do
    curl -s -o /dev/null -H 'X-Forwarded-For: 203.0.113.250' "$BASE/health" 2>/dev/null && break
    sleep 0.25
done

echo
echo "freio global por IP"
for n in $(seq 1 "$TETO"); do
    status 200 "pedido $n de $TETO do IP A é aceito" 198.51.100.10
done
status 429 "pedido $((TETO + 1)) do IP A é cortado" 198.51.100.10
grep -q 'aguarde um minuto' /tmp/kp_limite_corpo \
    && { echo "  ok: a recusa explica em português"; pass=$((pass + 1)); } \
    || { echo "  FALHOU: corpo da recusa sem explicação"; fail=$((fail + 1)); }
grep -q 'too_many_requests' /tmp/kp_limite_corpo \
    && { echo "  ok: a recusa traz o código too_many_requests"; pass=$((pass + 1)); } \
    || { echo "  FALHOU: recusa sem código"; fail=$((fail + 1)); }

echo
echo "isolamento e IP do proxy"
status 200 "IP B não herda o bloqueio do IP A" 198.51.100.20
status 429 'cadeia "<IP A>, 6.6.6.6" usa a PRIMEIRA entrada' "198.51.100.10, 6.6.6.6"
status 200 'cadeia "<IP C>, 6.6.6.6" abre balde novo para o IP C' "198.51.100.30, 6.6.6.6"

# O balde do IP C já gastou 1; mais quatro fecham o teto.
for n in $(seq 2 "$TETO"); do
    status 200 "pedido $n de $TETO do IP C é aceito" 198.51.100.30
done
status 429 "pedido $((TETO + 1)) do IP C é cortado" 198.51.100.30

rm -f /tmp/kp_limite_corpo

echo
printf 'passaram: %d · falharam: %d\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
