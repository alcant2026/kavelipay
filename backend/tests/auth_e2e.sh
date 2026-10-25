#!/usr/bin/env bash
# Testes de auth de ponta a ponta: sobe o servidor real contra um banco
# descartável e exercita os fluxos por HTTP.
#
# Cobre: cadastro, login, refresh com rotação, detecção de reuso de token,
# logout, e as barreiras de email duplicado e senha curta.
#
# Uso:  tests/auth_e2e.sh
# Env:   PGHOST PGPORT PGUSER PGPASSWORD

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="kavelipay_auth_test_$$"
# Porta por execução: dois scripts em paralelo (ou servidor zumbi) não brigam
# por 3311-3324. BASE_PORT fixa a porta explicitamente quando precisar.
E2E_PORTA=${BASE_PORT:-}
while [ -z "$E2E_PORTA" ]; do
  E2E_PORTA=$(( (RANDOM % 1000) + 4000 ))
  ss -ltn 2>/dev/null | grep -q ":${E2E_PORTA} " && E2E_PORTA=
done
BASE="http://127.0.0.1:${E2E_PORTA}"
SECRET="$(head -c 48 /dev/urandom | base64 | tr -d '\n')"

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

# status <expected> <descrição> <curl args...>
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

# json <descrição> <curl args...> — imprime o corpo
json() {
    curl -s "$@"
}

jq_get() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }

echo "criando banco: $DB"
# Banco vazio de propósito: quem aplica as migrations é o servidor, no boot
# (sqlx::migrate!). Aplicar aqui também causaria "relation already exists".
"${PSQL[@]}" -d postgres -c "CREATE DATABASE \"$DB\";"

[ -x "$BIN" ] || { echo "binário ausente: $BIN (rode cargo build)"; exit 1; }

echo "subindo servidor"
DATABASE_URL="postgres://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${DB}" \
JWT_SECRET="$SECRET" \
BIND_ADDR="127.0.0.1:${E2E_PORTA}" \
RUST_LOG=warn \
"$BIN" &
SERVER_PID=$!

# Espera o servidor responder em vez de dormir no chute.
for i in $(seq 1 40); do
    curl -s -o /dev/null "$BASE/auth/login" -X POST -H 'content-type: application/json' \
        -d '{"email":"x@x","password":"x"}' 2>/dev/null && break
    sleep 0.25
done

echo
echo "cadastro e login"

status 200 "register cria conta e devolve tokens" \
    -X POST "$BASE/auth/register" -H 'content-type: application/json' \
    -d '{"email":"Josias@Teste.COM","password":"senha-forte-123","display_name":"Josias"}'

status 409 "register com email duplicado é rejeitado" \
    -X POST "$BASE/auth/register" -H 'content-type: application/json' \
    -d '{"email":"josias@teste.com","password":"outra-senha-123"}'

status 422 "register com senha curta é rejeitado" \
    -X POST "$BASE/auth/register" -H 'content-type: application/json' \
    -d '{"email":"curta@teste.com","password":"123"}'

status 422 "register com email inválido é rejeitado" \
    -X POST "$BASE/auth/register" -H 'content-type: application/json' \
    -d '{"email":"nao-e-email","password":"senha-forte-123"}'

status 200 "login aceita email em caixa diferente (normalizado)" \
    -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d '{"email":"JOSIAS@teste.com","password":"senha-forte-123"}'

status 401 "login com senha errada é 401" \
    -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d '{"email":"josias@teste.com","password":"senha-errada-123"}'

status 401 "login com email inexistente é 401 (não revela existência)" \
    -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d '{"email":"ninguem@teste.com","password":"senha-forte-123"}'

echo
echo "refresh e rotação"

LOGIN=$(json -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d '{"email":"josias@teste.com","password":"senha-forte-123"}')
ACCESS=$(printf '%s' "$LOGIN" | jq_get "['access_token']")
REFRESH=$(printf '%s' "$LOGIN" | jq_get "['refresh_token']")

if [ -n "$ACCESS" ] && [ -n "$REFRESH" ]; then
    printf '  ok: login devolve access e refresh\n'
    pass=$((pass + 1))
else
    printf '  FALHOU: login não devolveu tokens (%s)\n' "$LOGIN"
    fail=$((fail + 1))
fi

# O access token precisa ser um JWT de 3 partes; o refresh NÃO pode ser JWT
# (precisa poder ser revogado no banco).
PARTES_ACCESS=$(printf '%s' "$ACCESS" | python3 -c "import sys; print(len(sys.stdin.read().strip().split('.')))")
PARTES_REFRESH=$(printf '%s' "$REFRESH" | python3 -c "import sys; print(len(sys.stdin.read().strip().split('.')))")
if [ "$PARTES_ACCESS" -eq 3 ] && [ "$PARTES_REFRESH" -eq 1 ]; then
    printf '  ok: access é JWT (%s partes) e refresh é opaco (%s parte)\n' \
        "$PARTES_ACCESS" "$PARTES_REFRESH"
    pass=$((pass + 1))
else
    printf '  FALHOU: access=%s partes, refresh=%s partes\n' "$PARTES_ACCESS" "$PARTES_REFRESH"
    fail=$((fail + 1))
fi

status 200 "refresh com token válido emite novo par" \
    -X POST "$BASE/auth/refresh" -H 'content-type: application/json' \
    -d "{\"refresh_token\":\"$REFRESH\"}"

status 401 "refresh com token já rotacionado é rejeitado" \
    -X POST "$BASE/auth/refresh" -H 'content-type: application/json' \
    -d "{\"refresh_token\":\"$REFRESH\"}"

status 401 "refresh com token inventado é rejeitado" \
    -X POST "$BASE/auth/refresh" -H 'content-type: application/json' \
    -d '{"refresh_token":"token-que-nunca-existiu"}'

status 200 "logout revoga o refresh informado" \
    -X POST "$BASE/auth/logout" -H 'content-type: application/json' \
    -d "{\"refresh_token\":\"$REFRESH\"}"

echo
echo "rota protegida e fronteira de confiança"

status 404 "rota inexistente devolve 404" "$BASE/nao-existe"

status 401 "/auth/me sem header é 401" "$BASE/auth/me"

status 401 "/auth/me com header malformado é 401" \
    "$BASE/auth/me" -H 'Authorization: Basic abc123'

status 401 "/auth/me sem prefixo Bearer é 401" \
    "$BASE/auth/me" -H "Authorization: $ACCESS"

status 401 "/auth/me com token forjado é 401" \
    "$BASE/auth/me" -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDAifQ.fake"

# user_id na query string NÃO pode sobrepor o token: é a tentativa mais óbvia
# de burlar a fronteira, e precisa continuar ignorada.
status 200 "/auth/me ignora user_id na query string e usa o token" \
    "$BASE/auth/me?user_id=00000000-0000-0000-0000-000000000000" \
    -H "Authorization: Bearer $ACCESS"

ME_EMAIL=$(json "$BASE/auth/me" -H "Authorization: Bearer $ACCESS" | jq_get "['email']")
if [ "$ME_EMAIL" = "josias@teste.com" ]; then
    printf '  ok: /auth/me devolve o email do dono do token\n'
    pass=$((pass + 1))
else
    printf '  FALHOU: /auth/me devolveu %s\n' "$ME_EMAIL"
    fail=$((fail + 1))
fi

echo
echo "resultado: $pass passaram, $fail falharam"
[ "$fail" -eq 0 ]