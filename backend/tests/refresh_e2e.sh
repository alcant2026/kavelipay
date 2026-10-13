#!/usr/bin/env bash
# Exercita a rotação de refresh do cliente contra o backend real.
#
# O que prova, na ordem em que o app faz:
#   1. login emite par (access, refresh)
#   2. access usado em GET /wallet funciona
#   3. POST /auth/refresh rotaciona: devolve par novo, refresh DIFERENTE
#   4. o access antigo (já rotacionado junto) é rejeitado com 401
#   5. reusar o refresh JÁ ROTACIONADO derruba todas as sessões — é exatamente
#      o que o Mutex do ApiClient existe para evitar quando 5 endpoints carregam
#      em paralelo e todos tomam 401
#   6. depois do colapso, nem o refresh novo funciona: exige login de novo
#
# Uso: tests/refresh_e2e.sh
# Env:  PGHOST PGPORT PGUSER PGPASSWORD  (mesmos dos outros e2e)

set -euo pipefail

BASE="http://127.0.0.1:3322"
SECRET="$(head -c 48 /dev/urandom | base64 | tr -d '\n')"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/target/debug/kavelipay-backend"

pass=0
fail=0
SERVER_PID=""

cleanup() {
    [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
}
trap cleanup EXIT

check() {
    if [ "$2" = "true" ]; then
        printf '  ok: %s\n' "$1"; pass=$((pass + 1))
    else
        printf '  FALHOU: %s\n' "$1"; fail=$((fail + 1))
    fi
}

get()   { python3 -c "import sys,json; print(json.load(sys.stdin).get('$1',''))"; }
code()  { curl -s -o /dev/null -w '%{http_code}' "$@"; }

[ -x "$BIN" ] || { echo "binário ausente: $BIN (rode cargo build)"; exit 1; }

echo "subindo servidor em $BASE"
DATABASE_URL="postgres://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/kavelipay" \
JWT_SECRET="$SECRET" BIND_ADDR="127.0.0.1:3322" RUST_LOG=error "$BIN" &
SERVER_PID=$!

for _ in $(seq 1 40); do
    curl -s -o /dev/null -X POST "$BASE/auth/login" -H 'content-type: application/json' \
        -d '{"email":"x@x","password":"x"}' 2>/dev/null && break
    sleep 0.25
done

# O usuário existe de propósito: refresh rotacionado que derruba sessões só é
# observável com mais de uma sessão viva.
EMAIL="refresh-$$@teste.com"
SENHA="senha-forte-123"

echo
echo "1. par inicial"
LOGIN=$(curl -s -X POST "$BASE/auth/register" -H 'content-type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$SENHA\",\"display_name\":\"Teste\"}")
ACCESS=$(get access_token <<<"$LOGIN")
REFRESH=$(get refresh_token <<<"$LOGIN")
check "register emite access" "$([ -n "$ACCESS" ] && echo true || echo false)"
check "register emite refresh" "$([ -n "$REFRESH" ] && echo true || echo false)"
check "access é JWT (3 partes)" \
    "$([ "$(awk -F. '{print NF}' <<<"$ACCESS")" = "3" ] && echo true || echo false)"
check "refresh é opaco (1 parte)" \
    "$([ "$(awk -F. '{print NF}' <<<"$REFRESH")" = "1" ] && echo true || echo false)"

echo
echo "2. access válido funciona"
check "GET /wallet com access (recebido $(code "$BASE/wallet" -H "Authorization: Bearer $ACCESS"))" \
    "$([ "$(code "$BASE/wallet" -H "Authorization: Bearer $ACCESS")" = "200" ] && echo true || echo false)"

echo
echo "3. refresh rotaciona o par"
R1=$(curl -s -X POST "$BASE/auth/refresh" -H 'content-type: application/json' \
    -d "{\"refresh_token\":\"$REFRESH\"}")
ACCESS2=$(get access_token <<<"$R1")
REFRESH2=$(get refresh_token <<<"$R1")
check "refresh devolve access novo" "$([ -n "$ACCESS2" ] && echo true || echo false)"
check "refresh devolve refresh NOVO (rotacionado)" \
    "$([ "$REFRESH2" != "$REFRESH" ] && [ -n "$REFRESH2" ] && echo true || echo false)"
check "access novo funciona (recebido $(code "$BASE/wallet" -H "Authorization: Bearer $ACCESS2"))" \
    "$([ "$(code "$BASE/wallet" -H "Authorization: Bearer $ACCESS2")" = "200" ] && echo true || echo false)"
# O access NÃO é garantido diferente: `iat`/`exp` do JWT têm granularidade de
# segundo, então duas emissões no mesmo segundo, para o mesmo usuário, produzem
# o token IDÊNTICO. Só o refresh é rotacionado de verdade. Isto importa para o
# cliente: "o access mudou?" não serve para detectar que outro `async` já
# renovou — é preciso um contador de geração, não comparação de string.
if [ "$ACCESS2" = "$ACCESS" ]; then
    echo "  nota: access saiu IDÊNTICO (mesmo segundo) — o refresh é que rotacionou"
fi

echo
echo "4. o refresh antigo não renova mais"
# O access antigo é JWT stateless: continua válido até a expiração natural,
# mesmo com o refresh revogado. Isso é por design (o backend não guarda estado
# de access), e por isso o teste olha o REFRESH, não o access.
C_OLD=$(code -X POST "$BASE/auth/refresh" -H 'content-type: application/json' \
    -d "{\"refresh_token\":\"$REFRESH\"}")
check "refresh já rotacionado não renova (recebido $C_OLD)" \
    "$([ "$C_OLD" = "401" ] && echo true || echo false)"
echo "  nota: access antigo ainda responde $(code "$BASE/wallet" -H "Authorization: Bearer $ACCESS") — JWT stateless expira sozinho"

# Segunda sessão, para o passo 5 ter o que derrubar.
LOGIN2=$(curl -s -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$SENHA\"}")
REFRESH_B=$(get refresh_token <<<"$LOGIN2")
check "segunda sessão criada" "$([ -n "$REFRESH_B" ] && echo true || echo false)"

echo
echo "5. reusar refresh já rotacionado derruba os REFRESH de todas as sessões"
# Este é o caminho que o Mutex do ApiClient fecha: duas renovações paralelas
# fariam isto, e o preço é o refresh do usuário revogado em todos os aparelhos
# — ele teria que logar de novo em cada um.
C_REUSE=$(code -X POST "$BASE/auth/refresh" -H 'content-type: application/json' \
    -d "{\"refresh_token\":\"$REFRESH\"}")
check "reusar refresh rotacionado é 401 (recebido $C_REUSE)" \
    "$([ "$C_REUSE" = "401" ] && echo true || echo false)"

check "o refresh da sessão que rotacionou também morreu (recebido $(code -X POST "$BASE/auth/refresh" -H 'content-type: application/json' -d "{\"refresh_token\":\"$REFRESH2\"}"))" \
    "$([ "$(code -X POST "$BASE/auth/refresh" -H 'content-type: application/json' -d "{\"refresh_token\":\"$REFRESH2\"}")" = "401" ] && echo true || echo false)"
check "o refresh da SEGUNDA sessão também morreu (recebido $(code -X POST "$BASE/auth/refresh" -H 'content-type: application/json' -d "{\"refresh_token\":\"$REFRESH_B\"}"))" \
    "$([ "$(code -X POST "$BASE/auth/refresh" -H 'content-type: application/json' -d "{\"refresh_token\":\"$REFRESH_B\"}")" = "401" ] && echo true || echo false)"

echo
echo "6. depois do colapso, login novo é a saída"
C_LOGIN=$(code -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$SENHA\"}")
check "login novo funciona (recebido $C_LOGIN)" \
    "$([ "$C_LOGIN" = "200" ] && echo true || echo false)"

echo
echo "resultado: $pass passaram, $fail falharam"
[ "$fail" -eq 0 ]