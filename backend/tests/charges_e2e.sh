#!/usr/bin/env bash
# Testes de cobrança + QR Code.
#
# O foco não é a CRUD: é provar que (a) cobrar não move a carteira, (b) receber
# move, e (c) o QR NÃO é BR Code/EMV do PIX — a fronteira "não somos provedor
# de PIX" da spec.
#
# Uso:  tests/charges_e2e.sh
# Env:   PGHOST PGPORT PGUSER PGPASSWORD

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="kavelipay_charges_test_$$"
BASE="http://127.0.0.1:3315"
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

echo "criando banco: $DB"
"${PSQL[@]}" -d postgres -c "CREATE DATABASE \"$DB\";"
[ -x "$BIN" ] || { echo "binário ausente: $BIN"; exit 1; }

echo "subindo servidor"
DATABASE_URL="postgres://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${DB}" \
JWT_SECRET="$SECRET" BIND_ADDR="127.0.0.1:3315" RUST_LOG=error "$BIN" &
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
    -H 'content-type: application/json' -d '{"type":"ENTRY","amount":"100.00"}'

echo
echo "cobrar NÃO move a carteira"

CH=$(curl -s -X POST "$BASE/charges" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"description":"Aluguel do sobrinho","amount":"50.00","debtor_name":"Bruno"}')
ID_CH=$(get id <<<"$CH")
SALDO_APOS=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "saldo intacto após cobrar 50.00 (recebido $SALDO_APOS, esperado 100.00)" \
    "$([ "$SALDO_APOS" = "100.00" ] && echo true || echo false)"

# Cobrar não pode gerar lançamento nenhum: é o que garante que pedir dinheiro
# nunca pareça receber dinheiro.
TXNS_APOS_COBRA=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COUNT(*) FROM ledger_transactions")
check "criar cobrança não gera transação no ledger ($TXNS_APOS_COBRA esperado 1: só a entrada)" \
    "$([ "$TXNS_APOS_COBRA" = "1" ] && echo true || echo false)"

echo
echo "o QR NÃO é BR Code / PIX (fronteira da spec)"

QR=$(curl -s "$BASE/charges/$ID_CH/qr" -H "$AUTH")
PAYLOAD=$(get payload <<<"$QR")
FORMAT=$(get format <<<"$QR")
PROVIDER=$(get provider <<<"$QR")

echo "    payload: $PAYLOAD"
check "payload não começa com 0002 (assinatura BR Code/EMV)" \
    "$(case "$PAYLOAD" in 0002*) echo false;; *) echo true;; esac)"
check "payload não menciona BR.GOV.BCB.PIX" \
    "$(case "$PAYLOAD" in *BR.GOV.BCB.PIX*) echo false;; *) echo true;; esac)"
check "payload não contém 'br.gov.bcb' em nenhuma caixa" \
    "$(case "$(printf '%s' "$PAYLOAD" | tr 'A-Z' 'a-z')" in *br.gov.bcb*) echo false;; *) echo true;; esac)"
check "format é DEEP_LINK (recebido $FORMAT)" \
    "$([ "$FORMAT" = "DEEP_LINK" ] && echo true || echo false)"
check "provider é 'demo' (recebido $PROVIDER)" \
    "$([ "$PROVIDER" = "demo" ] && echo true || echo false)"

TOKEN="${PAYLOAD#kavelipay://cobranca/}"
echo "    token: $TOKEN"
check "payload aponta para /cobranca/<token> de 32 hex" \
    "$(case "$PAYLOAD" in kavelipay://cobranca/*) [[ "$TOKEN" =~ ^[0-9a-f]{32}$ ]] && echo true || echo false;; *) echo false;; esac)"
check "payload NÃO expõe o id da cobrança" \
    "$(case "$PAYLOAD" in *"$ID_CH"*) echo false;; *) echo true;; esac)"
check "payload NÃO carrega valor (o valor vem do servidor)" \
    "$(case "$PAYLOAD" in *valor=*|*\?*) echo false;; *) echo true;; esac)"

TOKEN_DB=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT share_token FROM charges WHERE id='$ID_CH'")
check "token do QR é o token gravado no banco" \
    "$([ "$TOKEN" = "$TOKEN_DB" ] && echo true || echo false)"

echo
echo "rota pública /cobranca/{token}: o que quem recebe o link vê"

# Nome de quem cobra existe para o devedor saber quem pediu.
curl -s -o /dev/null -X PATCH "$BASE/profile" -H "$AUTH" \
    -H 'content-type: application/json' -d '{"display_name":"Alice"}'

PUB=$(curl -s "$BASE/cobranca/$TOKEN")
C_PUB=$(code "$BASE/cobranca/$TOKEN")
check "GET público responde 200 sem sessão (recebido $C_PUB)" \
    "$([ "$C_PUB" = "200" ] && echo true || echo false)"
check "devedor vê a descrição (recebido $(get description <<<"$PUB"))" \
    "$([ "$(get description <<<"$PUB")" = "Aluguel do sobrinho" ] && echo true || echo false)"
check "devedor vê o valor vindo do servidor (recebido $(get amount <<<"$PUB"))" \
    "$([ "$(get amount <<<"$PUB")" = "50.00" ] && echo true || echo false)"
check "devedor vê o status PENDING (recebido $(get status <<<"$PUB"))" \
    "$([ "$(get status <<<"$PUB")" = "PENDING" ] && echo true || echo false)"
check "devedor vê quem cobra (recebido $(get cobrador <<<"$PUB"))" \
    "$([ "$(get cobrador <<<"$PUB")" = "Alice" ] && echo true || echo false)"

# A resposta pública é mínima de propósito: o link roda fora da sessão e não
# pode levar wallet, id interno ou o nome de outra pessoa junto.
check "resposta pública não expõe wallet nem id da cobrança" \
    "$(case "$PUB" in *"$ID_CH"*|*wallet*) echo false;; *) echo true;; esac)"
check "resposta pública não expõe o nome do devedor" \
    "$(case "$PUB" in *Bruno*) echo false;; *) echo true;; esac)"

C_PUB_CURTO=$(code "$BASE/cobranca/abc")
C_PUB_INEXISTENTE=$(code "$BASE/cobranca/0123456789abcdef0123456789abcdef")
check "token malformado é 404 (recebido $C_PUB_CURTO)" \
    "$([ "$C_PUB_CURTO" = "404" ] && echo true || echo false)"
check "token inexistente é o MESMO 404 (recebido $C_PUB_INEXISTENTE)" \
    "$([ "$C_PUB_INEXISTENTE" = "404" ] && echo true || echo false)"

echo
echo "receber move o dinheiro"

REC=$(curl -s -X POST "$BASE/charges/$ID_CH/receive" -H "$AUTH")
check "cobrança vira RECEIVED" "$([ "$(get status <<<"$REC")" = "RECEIVED" ] && echo true || echo false)"

PUB_REC=$(curl -s "$BASE/cobranca/$TOKEN")
check "rota pública passa a mostrar RECEIVED (recebido $(get status <<<"$PUB_REC"))" \
    "$([ "$(get status <<<"$PUB_REC")" = "RECEIVED" ] && echo true || echo false)"

SALDO_RECEBIDO=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "saldo subiu para 150.00 ao receber (recebido $SALDO_RECEBIDO)" \
    "$([ "$SALDO_RECEBIDO" = "150.00" ] && echo true || echo false)"

# RECEIVABLE é conta de contrapartida (recebe o débito do recebimento) e não
# zera — o valor a receber autoritativo é a soma de cobranças PENDING, não o
# saldo dessa conta. Aqui verificamos justamente a ausência de PENDING.
PENDENTES=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COUNT(*) FROM charges WHERE status='PENDING'")
check "nenhuma cobrança fica PENDING depois de recebida (recebido $PENDENTES)" \
    "$([ "$PENDENTES" = "0" ] && echo true || echo false)"

A_RECEBER=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COALESCE(SUM(amount), 0) FROM charges WHERE status = 'PENDING'")
check "valor a receber (PENDING) é 0 (recebido $A_RECEBER)" \
    "$([ "$A_RECEBER" = "0" ] || [ "$A_RECEBER" = "0.0000" ] && echo true || echo false)"

TIPO_TXN=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT type FROM ledger_transactions ORDER BY created_at DESC LIMIT 1")
check "lançamento do recebimento é CHARGE_RECEIVED (recebido $TIPO_TXN)" \
    "$([ "$TIPO_TXN" = "CHARGE_RECEIVED" ] && echo true || echo false)"

# O débito do recebimento tem que cair em RECEIVABLE, nunca em USER_AVAILABLE:
# creditar a carteira e debitar a carteira na mesma transação daria saldo zero.
RECEIVABLE_DEB=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COALESCE(SUM(e.debit - e.credit), 0) FROM ledger_entries e
     JOIN ledger_accounts a ON a.id = e.account_id
     JOIN wallets w ON w.id = a.wallet_id
     JOIN users u ON u.id = w.user_id
     WHERE u.email='alice@teste.com' AND a.kind='RECEIVABLE'")
check "RECEIVABLE acumula o débito do recebimento ($RECEIVABLE_DEB esperado 50.0000)" \
    "$([ "$RECEIVABLE_DEB" = "50.0000" ] && echo true || echo false)"

echo
echo "QR de cobrança recebida é bloqueado"

C_QR=$(code "$BASE/charges/$ID_CH/qr" -H "$AUTH")
check "GET qr de cobrança recebida é 409 (recebido $C_QR)" \
    "$([ "$C_QR" = "409" ] && echo true || echo false)"

echo
echo "idempotência"

C_2X=$(code -X POST "$BASE/charges/$ID_CH/receive" -H "$AUTH")
check "receber de novo é 409 (recebido $C_2X)" \
    "$([ "$C_2X" = "409" ] && echo true || echo false)"

SALDO_2X=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "saldo não subiu duas vezes (recebido $SALDO_2X)" \
    "$([ "$SALDO_2X" = "150.00" ] && echo true || echo false)"

echo
echo "cancelamento e isolamento"

CH2=$(curl -s -X POST "$BASE/charges" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"description":"Empréstimo","amount":"30.00"}')
ID_CH2=$(get id <<<"$CH2")
C_CANCEL=$(code -X DELETE "$BASE/charges/$ID_CH2" -H "$AUTH")
check "DELETE cancela cobrança pendente (recebido $C_CANCEL)" \
    "$([ "$C_CANCEL" = "200" ] && echo true || echo false)"

TOKEN_CH2=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT share_token FROM charges WHERE id='$ID_CH2'")
PUB_CANCEL=$(curl -s "$BASE/cobranca/$TOKEN_CH2")
check "rota pública mostra CANCELED para cobrança cancelada (recebido $(get status <<<"$PUB_CANCEL"))" \
    "$([ "$(get status <<<"$PUB_CANCEL")" = "CANCELED" ] && echo true || echo false)"

C_CANCEL_RECEBIDA=$(code -X DELETE "$BASE/charges/$ID_CH" -H "$AUTH")
check "cancelar cobrança recebida é 409 (recebido $C_CANCEL_RECEBIDA)" \
    "$([ "$C_CANCEL_RECEBIDA" = "409" ] && echo true || echo false)"

C_CROSS=$(code -X POST "$BASE/charges/$ID_CH2/receive" -H "$AUTH_B")
check "Bob não recebe cobrança da Alice (recebido $C_CROSS)" \
    "$([ "$C_CROSS" = "404" ] && echo true || echo false)"

LISTA_BOB=$(curl -s "$BASE/charges" -H "$AUTH_B" | python3 -c \
    "import sys,json; print(len(json.load(sys.stdin)))")
check "Bob não vê cobranças da Alice (recebeu $LISTA_BOB)" \
    "$([ "$LISTA_BOB" = "0" ] && echo true || echo false)"

echo
echo "contabilidade"

DESBAL=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COUNT(*) FROM (
       SELECT transaction_id FROM ledger_entries GROUP BY transaction_id
        HAVING SUM(debit) <> SUM(credit)) x")
check "nenhuma transação desequilibrada ($DESBAL esperado 0)" \
    "$([ "$DESBAL" = "0" ] && echo true || echo false)"

# Soma de toda a carteira de contas do usuário tem que ser zero: é o
# fato contábil que "total_debits = total_credits" implica para o conjunto.
SOMA_TOTAL=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COALESCE(SUM(e.debit - e.credit), 0) FROM ledger_entries e
     JOIN ledger_accounts a ON a.id = e.account_id
     JOIN wallets w ON w.id = a.wallet_id
     JOIN users u ON u.id = w.user_id WHERE u.email='alice@teste.com'")
check "soma de débitos-créditos de todas as contas do usuário é 0 (recebido $SOMA_TOTAL)" \
    "$([ "$SOMA_TOTAL" = "0.0000" ] && echo true || echo false)"

echo
echo "resultado: $pass passaram, $fail falharam"
[ "$fail" -eq 0 ]