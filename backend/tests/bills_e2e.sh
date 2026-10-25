#!/usr/bin/env bash
# Testes de contas/boletos: status, OVERDUE derivado, idempotência de pagamento
# e a garantia de que OVERDUE nunca é gravado.
#
# Uso:  tests/bills_e2e.sh
# Env:   PGHOST PGPORT PGUSER PGPASSWORD

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="kavelipay_bills_test_$$"
# Porta por execução: dois scripts em paralelo (ou servidor zumbi) não brigam
# por 3311-3324. BASE_PORT fixa a porta explicitamente quando precisar.
E2E_PORTA=${BASE_PORT:-}
while [ -z "$E2E_PORTA" ]; do
  E2E_PORTA=$(( (RANDOM % 1000) + 4000 ))
  ss -ltn 2>/dev/null | grep -q ":${E2E_PORTA} " && E2E_PORTA=
done
BASE="http://127.0.0.1:${E2E_PORTA}"
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
    local desc="$1" ok="$2"
    if [ "$ok" = "true" ]; then
        printf '  ok: %s\n' "$desc"; pass=$((pass + 1))
    else
        printf '  FALHOU: %s\n' "$desc"; fail=$((fail + 1))
    fi
}

get() { python3 -c "import sys,json; print(json.load(sys.stdin).get('$1',''))"; }

code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

echo "criando banco: $DB"
"${PSQL[@]}" -d postgres -c "CREATE DATABASE \"$DB\";"
[ -x "$BIN" ] || { echo "binário ausente: $BIN"; exit 1; }

echo "subindo servidor"
DATABASE_URL="postgres://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${DB}" \
JWT_SECRET="$SECRET" BIND_ADDR="127.0.0.1:${E2E_PORTA}" RUST_LOG=error "$BIN" &
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

# Datas relativas a hoje: o teste de OVERDUE depende do relógio, não de
# datas fixas que envelheceriam.
HOJE=$(date +%F)
ONTEM=$(date -d '-1 day' +%F)
AMANHA=$(date -d '+1 day' +%F)

echo
echo "criação de boleto"

B_ONTEM=$(curl -s -X POST "$BASE/bills" -H "$AUTH" -H 'content-type: application/json' \
    -d "{\"description\":\"Conta de luz\",\"beneficiario\":\"Concessionária\",\"amount\":\"89.90\",\"due_date\":\"$ONTEM\"}")
ID_VENCIDO=$(get id <<<"$B_ONTEM")
ST_VENCIDO=$(get status <<<"$B_ONTEM")
OVER_VENCIDO=$(get is_overdue <<<"$B_ONTEM")
check "boleto vencido é criado como PENDING (recebido $ST_VENCIDO)" \
    "$([ "$ST_VENCIDO" = "PENDING" ] && echo true || echo false)"
check "boleto vencido tem is_overdue=true" \
    "$([ "$OVER_VENCIDO" = "True" ] || [ "$OVER_VENCIDO" = "true" ] && echo true || echo false)"

B_FUTURO=$(curl -s -X POST "$BASE/bills" -H "$AUTH" -H 'content-type: application/json' \
    -d "{\"description\":\"Internet\",\"amount\":\"99.90\",\"due_date\":\"$AMANHA\"}")
ID_FUTURO=$(get id <<<"$B_FUTURO")
OVER_FUTURO=$(get is_overdue <<<"$B_FUTURO")
check "boleto futuro tem is_overdue=false" \
    "$([ "$OVER_FUTURO" = "False" ] || [ "$OVER_FUTURO" = "false" ] && echo true || echo false)"

check "valor como float é 422 (recebido $(code -X POST "$BASE/bills" -H "$AUTH" \
    -H 'content-type: application/json' -d '{"description":"x","amount":10.50,"due_date":"2030-01-01"}'))" \
    "$([ "$(code -X POST "$BASE/bills" -H "$AUTH" -H 'content-type: application/json' \
        -d '{"description":"x","amount":10.50,"due_date":"2030-01-01"}')" = "422" ] && echo true || echo false)"

check "due_date malformada é 422 (recebido $(code -X POST "$BASE/bills" -H "$AUTH" \
    -H 'content-type: application/json' -d '{"description":"x","amount":"10.00","due_date":"01/01/2030"}'))" \
    "$([ "$(code -X POST "$BASE/bills" -H "$AUTH" -H 'content-type: application/json' \
        -d '{"description":"x","amount":"10.00","due_date":"01/01/2030"}')" = "422" ] && echo true || echo false)"

echo
echo "OVERDUE nunca é gravado"

# A barreira real é o CHECK do banco. Se alguém rodar um UPDATE direto com
# OVERDUE, o Postgres recusa — não existe caminho que persista esse valor.
if "${PSQL[@]}" -d "$DB" -c \
    "UPDATE bills SET status='OVERDUE' WHERE id='$ID_FUTURO'" >/dev/null 2>&1; then
    check "UPDATE bills SET status='OVERDUE' é recusado pelo banco" false
else
    check "UPDATE bills SET status='OVERDUE' é recusado pelo banco" true
fi

ST_AFTER=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT status FROM bills WHERE id='$ID_FUTURO'")
check "status no banco continua PENDING depois da tentativa ($ST_AFTER)" \
    "$([ "$ST_AFTER" = "PENDING" ] && echo true || echo false)"

OVER_NO_JSON=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COUNT(*) FROM bills WHERE status='OVERDUE'")
check "nenhum registro com status OVERDUE existe" \
    "$([ "$OVER_NO_JSON" = "0" ] && echo true || echo false)"

# Filtrar por OVERDUE como se fosse status é erro, com mensagem que ensina.
CODE_OVERDUE=$(code "$BASE/bills?status=OVERDUE" -H "$AUTH")
check "?status=OVERDUE é 422 com dica (recebido $CODE_OVERDUE)" \
    "$([ "$CODE_OVERDUE" = "422" ] && echo true || echo false)"

VENCIDOS=$(curl -s "$BASE/bills?overdue=true" -H "$AUTH" | python3 -c \
    "import sys,json; print(len(json.load(sys.stdin)['items']))")
check "?overdue=true lista 1 boleto (recebido $VENCIDOS)" \
    "$([ "$VENCIDOS" = "1" ] && echo true || echo false)"

echo
echo "pagamento manual gera transação no ledger"

curl -s -o /dev/null -X POST "$BASE/wallet/transactions" -H "$AUTH" \
    -H 'content-type: application/json' -d '{"type":"ENTRY","amount":"500.00"}'
SALDO_ANTES=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)

PAGO=$(curl -s -X POST "$BASE/bills/$ID_FUTURO/pay" -H "$AUTH" \
    -H 'content-type: application/json' -d '{"comprovante_url":"https://exemplo/comprovante.pdf"}')
check "boleto pago fica PAID" "$([ "$(get status <<<"$PAGO")" = "PAID" ] && echo true || echo false)"
check "boleto pago tem paid_transaction_id" \
    "$([ -n "$(get paid_transaction_id <<<"$PAGO")" ] && echo true || echo false)"
check "boleto pago não está mais overdue" \
    "$([ "$(get is_overdue <<<"$PAGO")" = "False" ] && echo true || echo false)"
check "comprovante foi anexado" \
    "$([ "$(get comprovante_url <<<"$PAGO")" = "https://exemplo/comprovante.pdf" ] && echo true || echo false)"

SALDO_DEPOIS=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "saldo caiu exatamente 99.90 ($SALDO_ANTES -> $SALDO_DEPOIS)" \
    "$([ "$SALDO_DEPOIS" = "400.10" ] && echo true || echo false)"

TXN=$(get paid_transaction_id <<<"$PAGO")
BALANCEADO=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT (SUM(debit)=SUM(credit))::text FROM ledger_entries WHERE transaction_id='$TXN'")
check "transação do pagamento está equilibrada" \
    "$([ "$BALANCEADO" = "true" ] && echo true || echo false)"

echo
echo "pagamento é idempotente"

COD_2X=$(code -X POST "$BASE/bills/$ID_FUTURO/pay" -H "$AUTH" \
    -H 'content-type: application/json' -d '{}')
check "pagar de novo é 409 (recebido $COD_2X)" \
    "$([ "$COD_2X" = "409" ] && echo true || echo false)"

SALDO_3X=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "saldo não foi debitado duas vezes ($SALDO_3X)" \
    "$([ "$SALDO_3X" = "400.10" ] && echo true || echo false)"

QTD_TXN=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COUNT(*) FROM ledger_entries WHERE transaction_id='$TXN'")
check "transação do pagamento tem exatamente 2 lançamentos" \
    "$([ "$QTD_TXN" = "2" ] && echo true || echo false)"

COD_CANCEL=$(code -X DELETE "$BASE/bills/$ID_FUTURO" -H "$AUTH")
check "cancelar boleto já pago é 409 (recebido $COD_CANCEL)" \
    "$([ "$COD_CANCEL" = "409" ] && echo true || echo false)"

echo
echo "isolamento entre carteiras"

CODE_CROSS=$(code -X POST "$BASE/bills/$ID_VENCIDO/pay" -H "$AUTH_B" \
    -H 'content-type: application/json' -d '{}')
check "Bob não paga boleto da Alice (recebido $CODE_CROSS)" \
    "$([ "$CODE_CROSS" = "404" ] && echo true || echo false)"

ITENS_BOB=$(curl -s "$BASE/bills" -H "$AUTH_B" | python3 -c \
    "import sys,json; print(len(json.load(sys.stdin)['items']))")
check "Bob não vê bills da Alice" \
    "$([ "$ITENS_BOB" = "0" ] && echo true || echo false)"

SALDO_BOB=$(curl -s "$BASE/wallet" -H "$AUTH_B" | get balance)
check "saldo do Bob intacto ($SALDO_BOB)" \
    "$([ "$SALDO_BOB" = "0.00" ] && echo true || echo false)"

echo
echo "cancelamento"

CANCELADO=$(curl -s -X POST "$BASE/bills" -H "$AUTH" -H 'content-type: application/json' \
    -d "{\"description\":\"Cinema\",\"amount\":\"40.00\",\"due_date\":\"$AMANHA\"}")
ID_CANCEL=$(get id <<<"$CANCELADO")
COD_C=$(code -X DELETE "$BASE/bills/$ID_CANCEL" -H "$AUTH")
check "DELETE cancela boleto pendente (recebido $COD_C)" \
    "$([ "$COD_C" = "200" ] && echo true || echo false)"

ST_CANCEL=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT status FROM bills WHERE id='$ID_CANCEL'")
check "status no banco é CANCELED ($ST_CANCEL)" \
    "$([ "$ST_CANCEL" = "CANCELED" ] && echo true || echo false)"

SALDO_FINAL=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "cancelar não mexe no saldo (recebido $SALDO_FINAL)" \
    "$([ "$SALDO_FINAL" = "400.10" ] && echo true || echo false)"

echo
echo "resultado: $pass passaram, $fail falharam"
[ "$fail" -eq 0 ]