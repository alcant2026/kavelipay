#!/usr/bin/env bash
# Testes de divisão de despesa: o invariante soma-das-partes = total, a
# idempotência da quitação e o sinal contábil do Settlement.
#
# Uso:  tests/splits_e2e.sh
# Env:   PGHOST PGPORT PGUSER PGPASSWORD

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="kavelipay_splits_test_$$"
BASE="http://127.0.0.1:3314"
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

check() {
    if [ "$2" = "true" ]; then
        printf '  ok: %s\n' "$1"; pass=$((pass + 1))
    else
        printf '  FALHOU: %s\n' "$1"; fail=$((fail + 1))
    fi
}
get() { python3 -c "import sys,json; print(json.load(sys.stdin).get('$1',''))"; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
jget() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }

echo "criando banco: $DB"
"${PSQL[@]}" -d postgres -c "CREATE DATABASE \"$DB\";"
[ -x "$BIN" ] || { echo "binário ausente: $BIN"; exit 1; }

echo "subindo servidor"
DATABASE_URL="postgres://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${DB}" \
JWT_SECRET="$SECRET" BIND_ADDR="127.0.0.1:3314" RUST_LOG=error "$BIN" &
SERVER_PID=$!
for i in $(seq 1 40); do
    curl -s -o /dev/null -X POST "$BASE/auth/login" -H 'content-type: application/json' \
        -d '{"email":"x@x","password":"x"}' 2>/dev/null && break
    sleep 0.25
done

for EMAIL in alice@teste.com bob@teste.com; do
    curl -s -o /dev/null -X POST "$BASE/auth/register" -H 'content-type: application/json' \
        -d "{\"email\":\"$EMAIL\",\"password\":\"senha-forte-123\"}"
done
login() { curl -s -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"senha-forte-123\"}" | get access_token; }
AUTH="Authorization: Bearer $(login alice@teste.com)"
AUTH_B="Authorization: Bearer $(login bob@teste.com)"

curl -s -o /dev/null -X POST "$BASE/wallet/transactions" -H "$AUTH" \
    -H 'content-type: application/json' -d '{"type":"ENTRY","amount":"1000.00"}'

echo
echo "divisão fecha quando a soma bate"

SPLIT=$(curl -s -X POST "$BASE/splits" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"description":"Jantar","total_amount":"120.00","parts":[
          {"name":"Ana","amount":"40.00"},
          {"name":"Bruno","amount":"40.00"},
          {"name":"Carla","amount":"40.00"}]}')
ID_SPLIT=$(get id <<<"$SPLIT")
PENDENTE=$(get pending_amount <<<"$SPLIT")
check "divisão 3x40 criada (pendente $PENDENTE)" \
    "$([ "$PENDENTE" = "120.00" ] && echo true || echo false)"

SALDO_APOS=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "saldo caiu o total da despesa (900.00 esperado, $SALDO_APOS)" \
    "$([ "$SALDO_APOS" = "880.00" ] && echo true || echo false)"

echo
echo "soma das partes PRECISA fechar"

C_ERRADA=$(code -X POST "$BASE/splits" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"description":"Mesa","total_amount":"100.00","parts":[
          {"name":"Ana","amount":"40.00"},{"name":"Bruno","amount":"40.00"}]}')
check "partes que somam 80 num total de 100 é 422 (recebido $C_ERRADA)" \
    "$([ "$C_ERRADA" = "422" ] && echo true || echo false)"

# Nada deve ter sido gravado: nem despesa, nem divisão.
QTD_SPLITS=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c "SELECT COUNT(*) FROM expense_splits")
check "divisão rejeitada não deixou registro ($QTD_SPLITS esperado 1)" \
    "$([ "$QTD_SPLITS" = "1" ] && echo true || echo false)"

C_VAZIA=$(code -X POST "$BASE/splits" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"description":"Sozinha","total_amount":"50.00","parts":[]}')
check "divisão sem participantes é 422 (recebido $C_VAZIA)" \
    "$([ "$C_VAZIA" = "422" ] && echo true || echo false)"

echo
echo "o BANCO recusa soma quebrada (bypass da API)"

# Esta é a barreira que importa: alguém rodando SQL direto não consegue criar
# uma divisão em que as partes não somam o total.
WALLET=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT w.id FROM wallets w JOIN users u ON u.id=w.user_id WHERE u.email='alice@teste.com'")
TXN=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT id FROM ledger_transactions WHERE wallet_id='$WALLET' LIMIT 1")

if "${PSQL[@]}" -d "$DB" >/dev/null 2>&1 <<SQL
BEGIN;
INSERT INTO expense_splits (wallet_id, description, total_amount, ledger_transaction_id)
VALUES ('$WALLET', 'Dividida errada', 100.00, '$TXN') RETURNING id \gset
INSERT INTO expense_split_parts (split_id, participant_name, owed_amount)
SELECT :id, 'Fantasma', 90.00;
COMMIT;
SQL
then
    check "inserção direta com partes que não fecham é recusada" false
else
    check "inserção direta com partes que não fecham é recusada" true
fi

QTD_AFTER=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COUNT(*) FROM expense_splits WHERE description='Dividida errada'")
check "nada foi gravado pela tentativa direta ($QTD_AFTER)" \
    "$([ "$QTD_AFTER" = "0" ] && echo true || echo false)"

if "${PSQL[@]}" -d "$DB" >/dev/null 2>&1 <<SQL
BEGIN;
INSERT INTO expense_splits (wallet_id, description, total_amount, ledger_transaction_id)
VALUES ('$WALLET', 'Sem ninguem', 100.00, '$TXN') RETURNING id \gset
COMMIT;
SQL
then
    check "divisão sem nenhuma parte é recusada" false
else
    check "divisão sem nenhuma parte é recusada" true
fi

echo
echo "quitação devolve dinheiro ao dono"

PART_ANA=$(curl -s "$BASE/splits" -H "$AUTH" | jget "[0]['parts'][0]['id']")
NOME_ANA=$(curl -s "$BASE/splits" -H "$AUTH" | jget "[0]['parts'][0]['name']")
check "primeiro participante é Ana ($NOME_ANA)" \
    "$([ "$NOME_ANA" = "Ana" ] && echo true || echo false)"

QUITADA=$(curl -s -X POST "$BASE/splits/$ID_SPLIT/parts/$PART_ANA/settle" -H "$AUTH")
SALDO_QUITADO=$(get balance <<<"$QUITADA")
check "quitar Ana devolve 40.00 (saldo $SALDO_QUITADO, esperado 920.00)" \
    "$([ "$SALDO_QUITADO" = "920.00" ] && echo true || echo false)"

PENDENTE2=$(get pending_amount <<<"$QUITADA")
check "pendente cai para 80.00 (recebido $PENDENTE2)" \
    "$([ "$PENDENTE2" = "80.00" ] && echo true || echo false)"

echo
echo "o invariente continua valendo depois de quitar"

SOMA_PARTES=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT SUM(owed_amount) FROM expense_split_parts WHERE split_id='$ID_SPLIT'")
TOTAL_SPLIT=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT total_amount FROM expense_splits WHERE id='$ID_SPLIT'")
check "soma das partes continua = total após quitação ($SOMA_PARTES vs $TOTAL_SPLIT)" \
    "$([ "$SOMA_PARTES" = "$TOTAL_SPLIT" ] && echo true || echo false)"

echo
echo "quitação é idempotente"

C_2X=$(code -X POST "$BASE/splits/$ID_SPLIT/parts/$PART_ANA/settle" -H "$AUTH")
check "quitar de novo é 409 (recebido $C_2X)" \
    "$([ "$C_2X" = "409" ] && echo true || echo false)"

SALDO_2X=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "saldo não subiu duas vezes ($SALDO_2X esperado 920.00)" \
    "$([ "$SALDO_2X" = "920.00" ] && echo true || echo false)"

echo
echo "isolamento entre carteiras"

C_CROSS=$(code -X POST "$BASE/splits/$ID_SPLIT/parts/$PART_ANA/settle" -H "$AUTH_B")
check "Bob nãoquita parte da Alice (recebido $C_CROSS)" \
    "$([ "$C_CROSS" = "404" ] && echo true || echo false)"

LISTA_BOB=$(curl -s "$BASE/splits" -H "$AUTH_B" | python3 -c \
    "import sys,json; print(len(json.load(sys.stdin)))")
check "Bob não vê divisões da Alice ($LISTA_BOB)" \
    "$([ "$LISTA_BOB" = "0" ] && echo true || echo false)"

echo
echo "nada de transação desequilibrada"

DESBAL=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COUNT(*) FROM (
       SELECT transaction_id FROM ledger_entries GROUP BY transaction_id
        HAVING SUM(debit) <> SUM(credit)) x")
check "nenhuma transação desequilibrada ($DESBAL esperado 0)" \
    "$([ "$DESBAL" = "0" ] && echo true || echo false)"

echo
echo "resultado: $pass passaram, $fail falharam"
[ "$fail" -eq 0 ]