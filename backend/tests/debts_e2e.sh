#!/usr/bin/env bash
# Testes das dívidas ("eu devo"): criação/listagem sem tocar no saldo,
# validações 422 em pt-BR, isolamento entre carteiras, pagamento que debita
# o ledger exatamente uma vez, e confirmação que é só status (nunca dinheiro).
#
# Uso:  tests/debts_e2e.sh
# Env:   PGHOST PGPORT PGUSER PGPASSWORD

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="kavelipay_debts_test_$$"
BASE="http://127.0.0.1:3317"
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

# Igual a `get`, mas tolera corpo vazio ou não-JSON — é o que o axum devolve
# quando a rota ainda não existe (estado RED). Sem isto, `set -e` encerra o
# script no primeiro parse e nenhum check acusa a falha de verdade.
# Caminho suporta índice de lista: `campo 0.creditor_name`.
campo() { python3 -c "
import sys, json
try:
    v = json.load(sys.stdin)
    for p in '$1'.split('.'):
        v = v[int(p)] if isinstance(v, list) else v[p]
    print('' if v is None else v)
except Exception:
    print('')"; }

# Conta itens de uma resposta em lista; tolerância idêntica à do `campo`.
qtd() { python3 -c "
import sys, json
try:
    v = json.load(sys.stdin)
    print(len(v) if isinstance(v, list) else '')
except Exception:
    print('')"; }

echo "criando banco: $DB"
"${PSQL[@]}" -d postgres -c "CREATE DATABASE \"$DB\";"
[ -x "$BIN" ] || { echo "binário ausente: $BIN"; exit 1; }

echo "subindo servidor"
DATABASE_URL="postgres://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${DB}" \
JWT_SECRET="$SECRET" BIND_ADDR="127.0.0.1:3317" RUST_LOG=error "$BIN" &
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
echo "criar dívida: 201, PENDING, saldo intacto"

# Um POST só: corpo e status juntos — duas chamadas criariam dívida duplicada
# assim que a rota existir.
RESPOSTA=$(curl -s -w '\n%{http_code}' -X POST "$BASE/debts" -H "$AUTH" \
    -H 'content-type: application/json' \
    -d '{"creditor_name":"João","amount":"50.00","description":"Jantar"}')
C_CRIAR="${RESPOSTA##*$'\n'}"
NOVA="${RESPOSTA%$'\n'*}"

check "criar dívida devolve 201 (recebido $C_CRIAR)" \
    "$([ "$C_CRIAR" = "201" ] && echo true || echo false)"

STATUS=$(campo status <<<"$NOVA")
check "criação nasce PENDING (recebido ${STATUS:-vazio})" \
    "$([ "$STATUS" = "PENDING" ] && echo true || echo false)"

VALOR=$(campo amount <<<"$NOVA")
check "valor volta como string 50.00 (recebido ${VALOR:-vazio})" \
    "$([ "$VALOR" = "50.00" ] && echo true || echo false)"

PAGO=$(campo paid_at <<<"$NOVA")
CONFIRMADO=$(campo confirmed_at <<<"$NOVA")
check "paid_at e confirmed_at nulos ao criar" \
    "$([ -z "$PAGO" ] && [ -z "$CONFIRMADO" ] && echo true || echo false)"

SALDO=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "saldo intacto após criar (1000.00 esperado, recebido $SALDO)" \
    "$([ "$SALDO" = "1000.00" ] && echo true || echo false)"

echo
echo "listagem isola carteira"

LISTA_A=$(curl -s "$BASE/debts" -H "$AUTH")
N_A=$(qtd <<<"$LISTA_A")
check "alice vê 1 dívida (recebido $N_A)" \
    "$([ "$N_A" = "1" ] && echo true || echo false)"

CREDOR=$(campo 0.creditor_name <<<"$LISTA_A")
check "dívida é do credor João (recebido ${CREDOR:-vazio})" \
    "$([ "$CREDOR" = "João" ] && echo true || echo false)"

MOTIVO=$(campo 0.description <<<"$LISTA_A")
check "dívida é pelo motivo Jantar (recebido ${MOTIVO:-vazio})" \
    "$([ "$MOTIVO" = "Jantar" ] && echo true || echo false)"

LISTA_B=$(curl -s "$BASE/debts" -H "$AUTH_B")
N_B=$(qtd <<<"$LISTA_B")
check "bob não vê dívidas da alice (recebido $N_B)" \
    "$([ "$N_B" = "0" ] && echo true || echo false)"

echo
echo "validações respondem 422 e nada é gravado"

C_CREDOR=$(code -X POST "$BASE/debts" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"creditor_name":"","amount":"50.00","description":"Sobra"}')
check "credor vazio é 422 (recebido $C_CREDOR)" \
    "$([ "$C_CREDOR" = "422" ] && echo true || echo false)"

C_ZERO=$(code -X POST "$BASE/debts" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"creditor_name":"Ana","amount":"0.00","description":"Sobra"}')
check "valor 0.00 é 422 (recebido $C_ZERO)" \
    "$([ "$C_ZERO" = "422" ] && echo true || echo false)"

C_NEG=$(code -X POST "$BASE/debts" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"creditor_name":"Ana","amount":"-5.00","description":"Sobra"}')
check "valor negativo é 422 (recebido $C_NEG)" \
    "$([ "$C_NEG" = "422" ] && echo true || echo false)"

C_DESC=$(code -X POST "$BASE/debts" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"creditor_name":"Ana","amount":"50.00","description":""}')
check "descrição vazia é 422 (recebido $C_DESC)" \
    "$([ "$C_DESC" = "422" ] && echo true || echo false)"

# Float JSON: dinheiro na API é sempre string e o tipo Money recusa float
# (f64 perde precisão). Inteiro é aceito por ser exato — ver money::tests.
C_FLOAT=$(code -X POST "$BASE/debts" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"creditor_name":"João","amount":50.50,"description":"Jantar"}')
check "amount como float é 422 (recebido $C_FLOAT)" \
    "$([ "$C_FLOAT" = "422" ] && echo true || echo false)"

LISTA_DEPOIS=$(curl -s "$BASE/debts" -H "$AUTH")
N_DEPOIS=$(qtd <<<"$LISTA_DEPOIS")
check "rejeitadas não gravaram nada (1 esperado, recebido $N_DEPOIS)" \
    "$([ "$N_DEPOIS" = "1" ] && echo true || echo false)"

echo
echo "pagar dívida debita o ledger exatamente uma vez"

ID_DIVIDA=$(campo 0.id <<<"$LISTA_DEPOIS")

# Um POST só: corpo e status juntos, como na criação.
RESPOSTA_PAGAR=$(curl -s -w '\n%{http_code}' -X POST "$BASE/debts/$ID_DIVIDA/pay" -H "$AUTH")
C_PAGAR="${RESPOSTA_PAGAR##*$'\n'}"
PAGA="${RESPOSTA_PAGAR%$'\n'*}"

check "pagar dívida devolve 200 (recebido $C_PAGAR)" \
    "$([ "$C_PAGAR" = "200" ] && echo true || echo false)"

STATUS_PAGO=$(campo status <<<"$PAGA")
check "dívida paga fica PAID (recebido ${STATUS_PAGO:-vazio})" \
    "$([ "$STATUS_PAGO" = "PAID" ] && echo true || echo false)"

PAGO_EM=$(campo paid_at <<<"$PAGA")
CONFIRMADO=$(campo confirmed_at <<<"$PAGA")
check "paid_at preenchido e confirmed_at nulo ao pagar" \
    "$([ -n "$PAGO_EM" ] && [ -z "$CONFIRMADO" ] && echo true || echo false)"

SALDO_PAGO=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "saldo caiu para 950.00 (esperado, recebido $SALDO_PAGO)" \
    "$([ "$SALDO_PAGO" = "950.00" ] && echo true || echo false)"

# O extrato tem a transação que a API promete: EXPENSE de 50.00, descrita
# pelo credor. Contagem = 1, para provar que não há duplicata.
EXTRATO=$(curl -s "$BASE/wallet/transactions" -H "$AUTH")
N_DESPESA=$(printf '%s' "$EXTRATO" | python3 -c "
import sys, json
try:
    itens = json.load(sys.stdin)['items']
    print(sum(1 for i in itens
              if i.get('type') == 'EXPENSE' and i.get('amount') == '50.00'
              and i.get('description') == 'Dívida: João'))
except Exception:
    print('')")
check "extrato tem 1 EXPENSE de 50.00 'Dívida: João' (recebido ${N_DESPESA:-vazio})" \
    "$([ "$N_DESPESA" = "1" ] && echo true || echo false)"

echo
echo "segundo pagamento é recusado e não debita de novo"

C_2X=$(code -X POST "$BASE/debts/$ID_DIVIDA/pay" -H "$AUTH")
check "pagar de novo é 409 (recebido $C_2X)" \
    "$([ "$C_2X" = "409" ] && echo true || echo false)"

SALDO_2X=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "saldo segue 950.00 após 2º pay (recebido $SALDO_2X)" \
    "$([ "$SALDO_2X" = "950.00" ] && echo true || echo false)"

echo
echo "pagar dívida alheia ou inexistente é 404"

C_BOB=$(code -X POST "$BASE/debts/$ID_DIVIDA/pay" -H "$AUTH_B")
check "bob paga dívida de alice é 404 (recebido $C_BOB)" \
    "$([ "$C_BOB" = "404" ] && echo true || echo false)"

C_SUMIDA=$(code -X POST "$BASE/debts/00000000-0000-0000-0000-000000000000/pay" -H "$AUTH")
check "id inexistente é 404 (recebido $C_SUMIDA)" \
    "$([ "$C_SUMIDA" = "404" ] && echo true || echo false)"

SALDO_FINAL=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "tentativas recusadas não mexeram no saldo (950.00, recebido $SALDO_FINAL)" \
    "$([ "$SALDO_FINAL" = "950.00" ] && echo true || echo false)"

echo
echo "confirmar dívida muda só o status, sem tocar no saldo"

# Dívida nova de 30. Um POST só: corpo e status juntos, como nas criações acima.
RESPOSTA_30=$(curl -s -w '\n%{http_code}' -X POST "$BASE/debts" -H "$AUTH" \
    -H 'content-type: application/json' \
    -d '{"creditor_name":"Maria","amount":"30.00","description":"Uber"}')
C_30="${RESPOSTA_30##*$'\n'}"
NOVA_30="${RESPOSTA_30%$'\n'*}"

check "criar dívida de 30 devolve 201 (recebido $C_30)" \
    "$([ "$C_30" = "201" ] && echo true || echo false)"

ID_30=$(campo id <<<"$NOVA_30")

# Confirmar SEM pagar: recusa com a mensagem exata do contrato, e o saldo não
# muda — quem recebe não decreta pagamento que não foi feito.
RESPOSTA_C_ERRADA=$(curl -s -w '\n%{http_code}' -X POST "$BASE/debts/$ID_30/confirm" -H "$AUTH")
C_C_ERRADA="${RESPOSTA_C_ERRADA##*$'\n'}"
CORPO_C_ERRADA="${RESPOSTA_C_ERRADA%$'\n'*}"

check "confirm sem pagar é 409 (recebido $C_C_ERRADA)" \
    "$([ "$C_C_ERRADA" = "409" ] && echo true || echo false)"

MSG=$(campo message <<<"$CORPO_C_ERRADA")
check "mensagem do 409 é 'só dívidas pagas podem ser confirmadas' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "só dívidas pagas podem ser confirmadas" ] && echo true || echo false)"

SALDO_RECUSADO=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "confirm indevido não mexeu no saldo (950.00, recebido $SALDO_RECUSADO)" \
    "$([ "$SALDO_RECUSADO" = "950.00" ] && echo true || echo false)"

# Agora paga: 950.00 - 30.00 = 920.00. Só `pay` mexe em dinheiro.
curl -s -o /dev/null -X POST "$BASE/debts/$ID_30/pay" -H "$AUTH"

SALDO_PAGA30=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "pagar a de 30 deixa o saldo em 920.00 (recebido $SALDO_PAGA30)" \
    "$([ "$SALDO_PAGA30" = "920.00" ] && echo true || echo false)"

# Confirmar o pagamento recebido: 200, CONFIRMED, confirmed_at preenchido —
# e o saldo INTACTO (confirmação não move dinheiro).
RESPOSTA_CONF=$(curl -s -w '\n%{http_code}' -X POST "$BASE/debts/$ID_30/confirm" -H "$AUTH")
C_CONF="${RESPOSTA_CONF##*$'\n'}"
CONFIRMADA="${RESPOSTA_CONF%$'\n'*}"

check "confirm devolve 200 (recebido $C_CONF)" \
    "$([ "$C_CONF" = "200" ] && echo true || echo false)"

STATUS_CONF=$(campo status <<<"$CONFIRMADA")
check "status vira CONFIRMED (recebido ${STATUS_CONF:-vazio})" \
    "$([ "$STATUS_CONF" = "CONFIRMED" ] && echo true || echo false)"

CONF_EM=$(campo confirmed_at <<<"$CONFIRMADA")
check "confirmed_at preenchido ao confirmar (recebido ${CONF_EM:-vazio})" \
    "$([ -n "$CONF_EM" ] && echo true || echo false)"

SALDO_CONF=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "confirm não move dinheiro: saldo segue 920.00 (recebido $SALDO_CONF)" \
    "$([ "$SALDO_CONF" = "920.00" ] && echo true || echo false)"

# Prova extra de que confirm não posta no ledger: o extrato segue com exatamente
# 2 EXPENSE (os 50.00 da dívida de João + os 30.00 da de Maria).
EXTRATO_CONF=$(curl -s "$BASE/wallet/transactions" -H "$AUTH")
N_EXPENSE=$(printf '%s' "$EXTRATO_CONF" | python3 -c "
import sys, json
try:
    itens = json.load(sys.stdin)['items']
    print(sum(1 for i in itens if i.get('type') == 'EXPENSE'))
except Exception:
    print('')")
check "extrato segue com 2 EXPENSE — confirm não postou no ledger (recebido ${N_EXPENSE:-vazio})" \
    "$([ "$N_EXPENSE" = "2" ] && echo true || echo false)"

# CONFIRMED é estado final: confirmar de novo é 409, e o saldo não muda.
C_2CONF=$(code -X POST "$BASE/debts/$ID_30/confirm" -H "$AUTH")
check "confirm de novo é 409 (recebido $C_2CONF)" \
    "$([ "$C_2CONF" = "409" ] && echo true || echo false)"

SALDO_2CONF=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "saldo segue 920.00 após duplo confirm (recebido $SALDO_2CONF)" \
    "$([ "$SALDO_2CONF" = "920.00" ] && echo true || echo false)"

# Confirmar dívida alheia ou inexistente é 404 — mesma regra de pay.
C_C_BOB=$(code -X POST "$BASE/debts/$ID_30/confirm" -H "$AUTH_B")
check "bob confirma dívida de alice é 404 (recebido $C_C_BOB)" \
    "$([ "$C_C_BOB" = "404" ] && echo true || echo false)"

C_C_SUMIDA=$(code -X POST "$BASE/debts/00000000-0000-0000-0000-000000000000/confirm" -H "$AUTH")
check "confirm de id inexistente é 404 (recebido $C_C_SUMIDA)" \
    "$([ "$C_C_SUMIDA" = "404" ] && echo true || echo false)"

echo
echo "resultado: $pass passaram, $fail falharam"
[ "$fail" -eq 0 ]
