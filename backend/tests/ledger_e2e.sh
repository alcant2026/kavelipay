#!/usr/bin/env bash
# Testes do ledger ponta a ponta: saldo derivado, entradas/despesas, recusa de
# float e isolamento entre carteiras.
#
# Uso:  tests/ledger_e2e.sh
# Env:   PGHOST PGPORT PGUSER PGPASSWORD

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="kavelipay_ledger_test_$$"
BASE="http://127.0.0.1:3312"
SECRET="$(head -c 48 /dev/urandom | base64 | tr -d '\n')"
BIN="$ROOT/target/debug/kavelipay-backend"

PSQL=(psql -v ON_ERROR_STOP=1 -q -X)
pass=0
fail=0
SERVER_PID=""

cleanup() {
    [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
    "${PSQL[@]}" -d postgres -c "DROP DATABASE IF EXISTS \"$DB\";" >/dev/null 2>&1 || true
}
trap cleanup EXIT

check() { # check <descrição> <condição verdadeira/falsa>
    local desc="$1" ok="$2"
    if [ "$ok" = "true" ]; then
        printf '  ok: %s\n' "$desc"; pass=$((pass + 1))
    else
        printf '  FALHOU: %s\n' "$desc"; fail=$((fail + 1))
    fi
}

get() { python3 -c "import sys,json; print(json.load(sys.stdin).get('$1',''))"; }

echo "criando banco: $DB"
"${PSQL[@]}" -d postgres -c "CREATE DATABASE \"$DB\";"

[ -x "$BIN" ] || { echo "binário ausente: $BIN (rode cargo build)"; exit 1; }

echo "subindo servidor"
DATABASE_URL="postgres://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${DB}" \
JWT_SECRET="$SECRET" BIND_ADDR="127.0.0.1:3312" RUST_LOG=error \
"$BIN" &
SERVER_PID=$!

for i in $(seq 1 40); do
    curl -s -o /dev/null -X POST "$BASE/auth/login" -H 'content-type: application/json' \
        -d '{"email":"x@x","password":"x"}' 2>/dev/null && break
    sleep 0.25
done

# Dois usuários: sem o segundo, "saldo isolado" não é testável.
for EMAIL in alice@teste.com bob@teste.com; do
    curl -s -o /dev/null -X POST "$BASE/auth/register" -H 'content-type: application/json' \
        -d "{\"email\":\"$EMAIL\",\"password\":\"senha-forte-123\"}"
done

login() {
    curl -s -X POST "$BASE/auth/login" -H 'content-type: application/json' \
        -d "{\"email\":\"$1\",\"password\":\"senha-forte-123\"}" | get access_token
}

ALICE=$(login alice@teste.com)
BOB=$(login bob@teste.com)
AUTH_A="Authorization: Bearer $ALICE"
AUTH_B="Authorization: Bearer $BOB"

echo
echo "carteira nasce no cadastro"

WALLET=$(curl -s "$BASE/wallet" -H "$AUTH_A" | get wallet_id)
check "cadastro cria wallet automaticamente" "$([ -n "$WALLET" ] && echo true || echo false)"

SALDO0=$(curl -s "$BASE/wallet" -H "$AUTH_A" | get balance)
check "saldo inicial é 0.00" "$([ "$SALDO0" = "0.00" ] && echo true || echo false)"

echo
echo "saldo é derivado do ledger"

# Entrada de 1000, depois despesa de 300 => 700.00
curl -s -o /dev/null -X POST "$BASE/wallet/transactions" -H "$AUTH_A" \
    -H 'content-type: application/json' \
    -d '{"type":"ENTRY","amount":"1000.00","description":"Salário"}'

SALDO1=$(curl -s "$BASE/wallet" -H "$AUTH_A" | get balance)
check "entrada de 1000.00 zera-para 1000.00" "$([ "$SALDO1" = "1000.00" ] && echo true || echo false)"

RESPOSTA=$(curl -s -X POST "$BASE/wallet/transactions" -H "$AUTH_A" \
    -H 'content-type: application/json' \
    -d '{"type":"EXPENSE","amount":"300.00","description":"Aluguel"}')
SALDO2=$(get balance <<<"$RESPOSTA")
check "despesa de 300.00 leva a 700.00" "$([ "$SALDO2" = "700.00" ] && echo true || echo false)"

# Este é o teste que a coluna de saldo quebraria: o valor devolvido precisa vir
# da soma dos lançamentos.
SOMA=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COALESCE(SUM(e.credit - e.debit),0) FROM ledger_entries e
     JOIN ledger_accounts a ON a.id=e.account_id
     JOIN wallets w ON w.id=a.wallet_id
     JOIN users u ON u.id=w.user_id
     WHERE u.email='alice@teste.com' AND a.kind='USER_AVAILABLE'")
check "saldo na API = soma dos lançamentos no banco (700.0000 vs $SALDO2)" \
    "$([ "$SOMA" = "700.0000" ] && echo true || echo false)"

echo
echo "saldo precisa de precisão, não de float"

curl -s -o /dev/null -X POST "$BASE/wallet/transactions" -H "$AUTH_A" \
    -H 'content-type: application/json' -d '{"type":"EXPENSE","amount":"0.10"}'
curl -s -o /dev/null -X POST "$BASE/wallet/transactions" -H "$AUTH_A" \
    -H 'content-type: application/json' -d '{"type":"EXPENSE","amount":"0.20"}'
SALDO3=$(curl -s "$BASE/wallet" -H "$AUTH_A" | get balance)
check "0.10 + 0.20 dá exatamente 699.70 (sem drift de float)" \
    "$([ "$SALDO3" = "699.70" ] && echo true || echo false)"

echo
echo "fronteira: float é recusado"

CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/wallet/transactions" -H "$AUTH_A" \
    -H 'content-type: application/json' -d '{"type":"ENTRY","amount":10.50}')
check "amount como float JSON é 422 (esperado 422, recebeu $CODE)" \
    "$([ "$CODE" = "422" ] && echo true || echo false)"

SALDO_APOS=$(curl -s "$BASE/wallet" -H "$AUTH_A" | get balance)
check "saldo não muda após float recusado" \
    "$([ "$SALDO_APOS" = "$SALDO3" ] && echo true || echo false)"

CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/wallet/transactions" -H "$AUTH_A" \
    -H 'content-type: application/json' -d '{"type":"ENTRY","amount":"-50.00"}')
check "valor negativo é 422 (recebido $CODE)" \
    "$([ "$CODE" = "422" ] && echo true || echo false)"

CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/wallet/transactions" -H "$AUTH_A" \
    -H 'content-type: application/json' -d '{"type":"TRANSFERENCIA","amount":"10.00"}')
check "type desconhecido é 422 (recebido $CODE)" \
    "$([ "$CODE" = "422" ] && echo true || echo false)"

echo
echo "isolamento entre carteiras"

SALDO_BOB=$(curl -s "$BASE/wallet" -H "$AUTH_B" | get balance)
check "saldo do Bob é 0.00 apesar das transações da Alice" \
    "$([ "$SALDO_BOB" = "0.00" ] && echo true || echo false)"

ITENS_BOB=$(curl -s "$BASE/wallet/transactions" -H "$AUTH_B" | python3 -c \
    "import sys,json; print(len(json.load(sys.stdin)['items']))")
check "extrato do Bob não vê transações da Alice" \
    "$([ "$ITENS_BOB" = "0" ] && echo true || echo false)"

ITENS_ALICE=$(curl -s "$BASE/wallet/transactions" -H "$AUTH_A" | python3 -c \
    "import sys,json; print(len(json.load(sys.stdin)['items']))")
check "extrato da Alice tem as 4 transações dela" \
    "$([ "$ITENS_ALICE" = "4" ] && echo true || echo false)"

CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/wallet" \
    -H "Authorization: Bearer $ALICE" -H "X-User-Id: $WALLET")
check "header X-User-Id é ignorado, token manda (200)" \
    "$([ "$CODE" = "200" ] && echo true || echo false)"

echo
echo "ledger equilibrado no banco"

DESBAL=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COUNT(*) FROM (
       SELECT transaction_id FROM ledger_entries GROUP BY transaction_id
        HAVING SUM(debit) <> SUM(credit)
     ) x")
check "nenhuma transação desequilibrada no banco" \
    "$([ "$DESBAL" = "0" ] && echo true || echo false)"

echo
echo "resultado: $pass passaram, $fail falharam"
[ "$fail" -eq 0 ]