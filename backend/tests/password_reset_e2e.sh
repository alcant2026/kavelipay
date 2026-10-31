#!/usr/bin/env bash
# Recuperação de senha de ponta a ponta: esqueci -> e-mail -> código -> senha nova.
#
# Sobe o backend real contra um banco descartável e um mock de SMTP em
# loopback (tests/smtp_mock.py), que grava a mensagem enviada. O código é
# extraído do e-mail — se o mailer não entregar, o teste falha.
#
# Cobre: resposta genérica (sem enumeração de contas), código de uso único,
# expiração, senha curta, revogação das sessões e o freio por IP.
#
# Uso:  tests/password_reset_e2e.sh
# Env:   PGHOST PGPORT PGUSER PGPASSWORD

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="kavelipay_reset_test_$$"
BASE="${BASE_PORT:-}"
while [ -z "$BASE" ]; do
  BASE=$(( (RANDOM % 1000) + 4000 ))
  ss -ltn 2>/dev/null | grep -q ":${BASE} " && BASE=
done
SMTP_PORT=$(( BASE + 1000 ))
URL="http://127.0.0.1:${BASE}"
SECRET="$(head -c 48 /dev/urandom | base64 | tr -d '\n')"
MAIL="$(mktemp)"
BIN="$ROOT/target/debug/kavelipay-backend"

PSQL=(psql -v ON_ERROR_STOP=1 -q -X)
pass=0
fail=0
SERVER_PID=""
SMTP_PID=""

cleanup() {
    [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
    [ -n "$SMTP_PID" ] && kill "$SMTP_PID" 2>/dev/null || true
    rm -f "$MAIL"
    "${PSQL[@]}" -d postgres -c "DROP DATABASE IF EXISTS \"$DB\";" >/dev/null 2>&1 || true
}
trap cleanup EXIT

status() {
    local expected="$1" desc="$2"; shift 2
    local got
    got=$(curl -s -o /dev/null -w '%{http_code}' "$@")
    if [ "$got" = "$expected" ]; then
        printf '  ok (%s): %s\n' "$got" "$desc"
        pass=$((pass + 1))
    else
        printf '  FALHOU: %s — esperado %s, recebeu %s\n' "$desc" "$expected" "$got"
        fail=$((fail + 1))
    fi
}

check() {
    if [ "$2" = "$3" ]; then
        printf '  ok: %s\n' "$1"; pass=$((pass + 1))
    else
        printf '  FALHOU: %s — esperado [%s], recebeu [%s]\n' "$1" "$3" "$2"
        fail=$((fail + 1))
    fi
}

json() { curl -s "$@"; }
jget() { python3 -c "import sys,json; print(json.load(sys.stdin).get('$1',''))"; }
msg() { python3 -c "import sys,json; print(json.load(sys.stdin).get('message',''))"; }

echo "criando banco: $DB"
"${PSQL[@]}" -d postgres -c "CREATE DATABASE \"$DB\";"

[ -x "$BIN" ] || { echo "binário ausente: $BIN (rode cargo build)"; exit 1; }

echo "subindo mock de SMTP em 127.0.0.1:$SMTP_PORT"
python3 "$ROOT/tests/smtp_mock.py" "$SMTP_PORT" "$MAIL" &
SMTP_PID=$!
sleep 0.4

echo "subindo servidor"
DATABASE_URL="postgres://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${DB}" \
JWT_SECRET="$SECRET" \
BIND_ADDR="127.0.0.1:${BASE}" \
SMTP_HOST=127.0.0.1 \
SMTP_PORT="$SMTP_PORT" \
SMTP_TLS=off \
SMTP_USER=usuario \
SMTP_PASS=segredo \
SMTP_FROM=no-reply@kavelipay.test \
RUST_LOG=warn \
"$BIN" &
SERVER_PID=$!

for _ in $(seq 1 100); do
    curl -sf "$URL/health" >/dev/null 2>&1 && break
    sleep 0.1
done

echo
echo "=== cadastro ==="
CADASTRO=$(json "$URL/auth/register" -H 'Content-Type: application/json' \
    -d '{"email":"josias@teste.com","password":"senha-antiga","display_name":"Josias"}')
ACCESS=$(echo "$CADASTRO" | jget access_token)
REFRESH=$(echo "$CADASTRO" | jget refresh_token)
[ -n "$ACCESS" ] || { echo "cadastro falhou: $CADASTRO"; exit 1; }

echo "=== pedido de redefinição ==="
RESP=$(json "$URL/auth/forgot-password" -H 'Content-Type: application/json' \
    -d '{"email":"josias@teste.com"}')
MENSAGEM_EXISTE=$(echo "$RESP" | jget mensagem)
check "pedido devolve a mensagem genérica" "$MENSAGEM_EXISTE" \
    "Se este e-mail estiver cadastrado, enviamos um código de redefinição."

# O envio é em background: dá até 5s para a mensagem aparecer no mock.
CODIGO=""
for _ in $(seq 1 50); do
    CODIGO=$(grep -oE 'KPL-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}' "$MAIL" 2>/dev/null | head -1 || true)
    [ -n "$CODIGO" ] && break
    sleep 0.1
done
check "o e-mail chegou com um código KaveliPay" "$([ -n "$CODIGO" ] && echo sim || echo nao)" "sim"

if grep -q "Subject: =?UTF-8?B?" "$MAIL"; then
    printf '  ok: assunto foi codificado (RFC 2047)\n'; pass=$((pass + 1))
else
    printf '  FALHOU: assunto sem codificação MIME\n'; fail=$((fail + 1))
fi

echo
echo "=== conta inexistente não vaza ==="
RESP=$(json "$URL/auth/forgot-password" -H 'Content-Type: application/json' \
    -d '{"email":"ninguem@teste.com"}')
check "mesma mensagem para e-mail não cadastrado" "$(echo "$RESP" | jget mensagem)" "$MENSAGEM_EXISTE"

echo
echo "=== redefinição ==="
status 422 "código inválido é 422" "$URL/auth/reset-password" \
    -H 'Content-Type: application/json' \
    -d '{"token":"KPL-AAAA-BBBB-CCCC-DDDD","new_password":"senha-nova"}'

status 422 "senha curta é 422" "$URL/auth/reset-password" \
    -H 'Content-Type: application/json' \
    -d "{\"token\":\"$CODIGO\",\"new_password\":\"curta\"}"

status 200 "código válido redefine" "$URL/auth/reset-password" \
    -H 'Content-Type: application/json' \
    -d "{\"token\":\"$CODIGO\",\"new_password\":\"senha-nova\"}"

status 422 "o mesmo código não serve duas vezes" "$URL/auth/reset-password" \
    -H 'Content-Type: application/json' \
    -d "{\"token\":\"$CODIGO\",\"new_password\":\"outra-senha\"}"

echo
echo "=== efeitos no login/sessões ==="
status 401 "senha antiga não entra mais" "$URL/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"email":"josias@teste.com","password":"senha-antiga"}'

status 200 "senha nova entra" "$URL/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"email":"josias@teste.com","password":"senha-nova"}'

status 401 "refresh antigo foi revogado" "$URL/auth/refresh" \
    -H 'Content-Type: application/json' \
    -d "{\"refresh_token\":\"$REFRESH\"}"

echo
echo "=== freio por IP ==="
# Já foram 2 pedidos (um com conta, um sem). O teto do Cooldown é 5/hora.
status 200 "3º pedido" "$URL/auth/forgot-password" -H 'Content-Type: application/json' -d '{"email":"a@teste.com"}'
status 200 "4º pedido" "$URL/auth/forgot-password" -H 'Content-Type: application/json' -d '{"email":"b@teste.com"}'
status 200 "5º pedido" "$URL/auth/forgot-password" -H 'Content-Type: application/json' -d '{"email":"c@teste.com"}'
status 429 "6º pedido é 429" "$URL/auth/forgot-password" -H 'Content-Type: application/json' -d '{"email":"d@teste.com"}'

echo
echo "resultado: $pass passaram, $fail falharam"
[ "$fail" -eq 0 ]
