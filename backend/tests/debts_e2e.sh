#!/usr/bin/env bash
# Testes das dívidas ("eu devo"): criação/listagem sem tocar no saldo,
# validações 422 em pt-BR, isolamento entre carteiras, pagamento que debita
# o ledger exatamente uma vez, confirmação que é só status (nunca dinheiro),
# e edição/apagamento restritos a PENDING (sem estorno).
#
# Uso:  tests/debts_e2e.sh
# Env:   PGHOST PGPORT PGUSER PGPASSWORD

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="kavelipay_debts_test_$$"
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
echo "editar e apagar só em PENDING, sem estorno"

# Dívida pendente nova de 20 para editar. Um POST só: corpo e status juntos.
RESPOSTA_20=$(curl -s -w '\n%{http_code}' -X POST "$BASE/debts" -H "$AUTH" \
    -H 'content-type: application/json' \
    -d '{"creditor_name":"Rita","amount":"20.00","description":"Presente"}')
C_20="${RESPOSTA_20##*$'\n'}"
NOVA_20="${RESPOSTA_20%$'\n'*}"

check "criar dívida de 20 devolve 201 (recebido $C_20)" \
    "$([ "$C_20" = "201" ] && echo true || echo false)"

ID_20=$(campo id <<<"$NOVA_20")

# PATCH parcial: manda SÓ o valor. Os campos ausentes têm de ficar intactos.
RESPOSTA_PATCH=$(curl -s -w '\n%{http_code}' -X PATCH "$BASE/debts/$ID_20" -H "$AUTH" \
    -H 'content-type: application/json' -d '{"amount":"25.00"}')
C_PATCH="${RESPOSTA_PATCH##*$'\n'}"
EDITADA="${RESPOSTA_PATCH%$'\n'*}"

check "PATCH amount devolve 200 (recebido $C_PATCH)" \
    "$([ "$C_PATCH" = "200" ] && echo true || echo false)"

VALOR_EDITADO=$(campo amount <<<"$EDITADA")
check "valor vira 25.00 (recebido ${VALOR_EDITADO:-vazio})" \
    "$([ "$VALOR_EDITADO" = "25.00" ] && echo true || echo false)"

CREDOR_INTACTO=$(campo creditor_name <<<"$EDITADA")
MOTIVO_INTACTO=$(campo description <<<"$EDITADA")
check "PATCH parcial preserva credor e descrição (recebido ${CREDOR_INTACTO:-vazio}/${MOTIVO_INTACTO:-vazio})" \
    "$([ "$CREDOR_INTACTO" = "Rita" ] && [ "$MOTIVO_INTACTO" = "Presente" ] && echo true || echo false)"

SALDO_EDICAO=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "edição não mexe no ledger: saldo segue 920.00 (recebido $SALDO_EDICAO)" \
    "$([ "$SALDO_EDICAO" = "920.00" ] && echo true || echo false)"

# Validações do PATCH iguais às do POST: 422 em pt-BR, nada é gravado.
C_P_ZERO=$(code -X PATCH "$BASE/debts/$ID_20" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"amount":"0.00"}')
check "PATCH amount 0.00 é 422 (recebido $C_P_ZERO)" \
    "$([ "$C_P_ZERO" = "422" ] && echo true || echo false)"

C_P_CREDOR=$(code -X PATCH "$BASE/debts/$ID_20" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"creditor_name":""}')
check "PATCH credor vazio é 422 (recebido $C_P_CREDOR)" \
    "$([ "$C_P_CREDOR" = "422" ] && echo true || echo false)"

C_P_DESC=$(code -X PATCH "$BASE/debts/$ID_20" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"description":""}')
check "PATCH descrição vazia é 422 (recebido $C_P_DESC)" \
    "$([ "$C_P_DESC" = "422" ] && echo true || echo false)"

# Os 422 não gravaram nada: a lista segue com a dívida de 25 (a mais recente).
LISTA_POS_422=$(curl -s "$BASE/debts" -H "$AUTH")
VALOR_POS_422=$(campo 0.amount <<<"$LISTA_POS_422")
check "422 não gravou nada: valor segue 25.00 (recebido ${VALOR_POS_422:-vazio})" \
    "$([ "$VALOR_POS_422" = "25.00" ] && echo true || echo false)"

# Paga (Task 2) e confirmada (Task 3): estado final, trava os dois verbos.
RESPOSTA_P_PAGA=$(curl -s -w '\n%{http_code}' -X PATCH "$BASE/debts/$ID_DIVIDA" -H "$AUTH" \
    -H 'content-type: application/json' -d '{"amount":"99.99"}')
C_P_PAGA="${RESPOSTA_P_PAGA##*$'\n'}"
CORPO_P_PAGA="${RESPOSTA_P_PAGA%$'\n'*}"

check "PATCH em dívida PAID é 409 (recebido $C_P_PAGA)" \
    "$([ "$C_P_PAGA" = "409" ] && echo true || echo false)"

MSG_P=$(campo message <<<"$CORPO_P_PAGA")
check "mensagem do PATCH 409 é 'só dívidas pendentes podem ser alteradas' (recebido ${MSG_P:-vazio})" \
    "$([ "$MSG_P" = "só dívidas pendentes podem ser alteradas" ] && echo true || echo false)"

RESPOSTA_D_PAGA=$(curl -s -w '\n%{http_code}' -X DELETE "$BASE/debts/$ID_DIVIDA" -H "$AUTH")
C_D_PAGA="${RESPOSTA_D_PAGA##*$'\n'}"
CORPO_D_PAGA="${RESPOSTA_D_PAGA%$'\n'*}"

check "DELETE em dívida PAID é 409 (recebido $C_D_PAGA)" \
    "$([ "$C_D_PAGA" = "409" ] && echo true || echo false)"

MSG_D=$(campo message <<<"$CORPO_D_PAGA")
check "mensagem do DELETE 409 é 'só dívidas pendentes podem ser apagadas' (recebido ${MSG_D:-vazio})" \
    "$([ "$MSG_D" = "só dívidas pendentes podem ser apagadas" ] && echo true || echo false)"

# Confirmada também: estado final não se edita nem se apaga.
C_P_CONF=$(code -X PATCH "$BASE/debts/$ID_30" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"amount":"1.00"}')
check "PATCH em dívida CONFIRMED é 409 (recebido $C_P_CONF)" \
    "$([ "$C_P_CONF" = "409" ] && echo true || echo false)"

C_D_CONF=$(code -X DELETE "$BASE/debts/$ID_30" -H "$AUTH")
check "DELETE em dívida CONFIRMED é 409 (recebido $C_D_CONF)" \
    "$([ "$C_D_CONF" = "409" ] && echo true || echo false)"

SALDO_TRAVA=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "recusas de editar/apagar não mexeram no saldo (920.00, recebido $SALDO_TRAVA)" \
    "$([ "$SALDO_TRAVA" = "920.00" ] && echo true || echo false)"

# Apagar a pendente: 200 com o corpo da linha apagada (o app Android lê JSON),
# e ela some da listagem. Nada de estorno: pendente nunca tocou o ledger.
RESPOSTA_DEL=$(curl -s -w '\n%{http_code}' -X DELETE "$BASE/debts/$ID_20" -H "$AUTH")
C_DEL="${RESPOSTA_DEL##*$'\n'}"
APAGADA="${RESPOSTA_DEL%$'\n'*}"

check "DELETE da pendente devolve 200 (recebido $C_DEL)" \
    "$([ "$C_DEL" = "200" ] && echo true || echo false)"

VALOR_APAGADA=$(campo amount <<<"$APAGADA")
check "corpo devolvido é a linha apagada, amount 25.00 (recebido ${VALOR_APAGADA:-vazio})" \
    "$([ "$VALOR_APAGADA" = "25.00" ] && echo true || echo false)"

LISTA_FINAL=$(curl -s "$BASE/debts" -H "$AUTH")
N_FINAL=$(qtd <<<"$LISTA_FINAL")
check "apagada some do GET /debts (2 esperado, recebido $N_FINAL)" \
    "$([ "$N_FINAL" = "2" ] && echo true || echo false)"

SALDO_SEM_ESTORNO=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "apagar pendente não move saldo: segue 920.00 (recebido $SALDO_SEM_ESTORNO)" \
    "$([ "$SALDO_SEM_ESTORNO" = "920.00" ] && echo true || echo false)"

# Prova direta de que não há estorno: o extrato segue com as 3 transações
# originais (1 ENTRY de 1000 + as 2 EXPENSE) — apagar PENDING é apagar um
# registro, não reverter dinheiro.
EXTRATO_DEL=$(curl -s "$BASE/wallet/transactions" -H "$AUTH")
N_TRANS_DEL=$(printf '%s' "$EXTRATO_DEL" | python3 -c "
import sys, json
try:
    itens = json.load(sys.stdin)['items']
    print(len(itens))
except Exception:
    print('')")
check "extrato segue com 3 transações — apagar não postou estorno (recebido ${N_TRANS_DEL:-vazio})" \
    "$([ "$N_TRANS_DEL" = "3" ] && echo true || echo false)"

# Id alheio não existe: bob recebe 404 nos dois verbos, nunca 403.
C_P_ALHEIO=$(code -X PATCH "$BASE/debts/$ID_DIVIDA" -H "$AUTH_B" -H 'content-type: application/json' \
    -d '{"amount":"1.00"}')
check "bob edita dívida de alice é 404 (recebido $C_P_ALHEIO)" \
    "$([ "$C_P_ALHEIO" = "404" ] && echo true || echo false)"

C_D_ALHEIO=$(code -X DELETE "$BASE/debts/$ID_DIVIDA" -H "$AUTH_B")
check "bob apaga dívida de alice é 404 (recebido $C_D_ALHEIO)" \
    "$([ "$C_D_ALHEIO" = "404" ] && echo true || echo false)"

# Id inexistente: mesma fronteira, mesmo 404.
C_P_SUMIDA=$(code -X PATCH "$BASE/debts/00000000-0000-0000-0000-000000000000" -H "$AUTH" \
    -H 'content-type: application/json' -d '{"amount":"1.00"}')
check "PATCH de id inexistente é 404 (recebido $C_P_SUMIDA)" \
    "$([ "$C_P_SUMIDA" = "404" ] && echo true || echo false)"

C_D_SUMIDA=$(code -X DELETE "$BASE/debts/00000000-0000-0000-0000-000000000000" -H "$AUTH")
check "DELETE de id inexistente é 404 (recebido $C_D_SUMIDA)" \
    "$([ "$C_D_SUMIDA" = "404" ] && echo true || echo false)"

echo
echo "LGPD: dívidas de todo status ficam registradas (o export JSON saiu)"

# O export JSON (`GET /lgpd/export`) saiu do contrato: exportar é planilha, e a
# planilha é o extrato — dívida não é movimentação de carteira, então não entra
# nela. O direito de acesso continua valendo no banco: as três dívidas, em
# todos os status, ficam gravadas e sem filtro. A prova passou a ser SQL direto,
# não mais uma seção do JSON.
RESPOSTA_EXP=$(curl -s -w '\n%{http_code}' -X POST "$BASE/debts" -H "$AUTH" \
    -H 'content-type: application/json' \
    -d '{"creditor_name":"Ana","amount":"15.00","description":"Cinema"}')
C_EXP="${RESPOSTA_EXP##*$'\n'}"
check "criar dívida PENDING para o export devolve 201 (recebido $C_EXP)" \
    "$([ "$C_EXP" = "201" ] && echo true || echo false)"

N_EXP=$("${PSQL[@]}" -t -A -d "$DB" -c "
SELECT count(*) FROM debts d
  JOIN wallets w ON w.id = d.wallet_id
  JOIN users u   ON u.id = w.user_id
 WHERE u.email = 'alice@teste.com';")
check "alice tem as 3 dívidas gravadas (recebido ${N_EXP:-vazio})" \
    "$([ "$N_EXP" = "3" ] && echo true || echo false)"

# Os três statuses lado a lado: omitir PENDING ou CONFIRMED seria perder dado.
STATUS_EXP=$("${PSQL[@]}" -t -A -d "$DB" -c "
SELECT string_agg(d.status, ',' ORDER BY d.status) FROM debts d
  JOIN wallets w ON w.id = d.wallet_id
  JOIN users u   ON u.id = w.user_id
 WHERE u.email = 'alice@teste.com';")
check "os três status coexistem (recebido ${STATUS_EXP:-vazio})" \
    "$([ "$STATUS_EXP" = "CONFIRMED,PAID,PENDING" ] && echo true || echo false)"

# A dívida do João continua com o valor exato e sem sinal trocado.
JOAO_EXP=$("${PSQL[@]}" -t -A -d "$DB" -c "
SELECT amount FROM debts d
  JOIN wallets w ON w.id = d.wallet_id
  JOIN users u   ON u.id = w.user_id
 WHERE u.email = 'alice@teste.com' AND d.creditor_name = 'João';")
check "dívida do João continua 50.0000 (recebido ${JOAO_EXP:-vazio})" \
    "$([ "$JOAO_EXP" = "50.0000" ] && echo true || echo false)"

# Fronteira de confiança: bob não tem dívida nenhuma — nunca a da alice.
N_EXP_B=$("${PSQL[@]}" -t -A -d "$DB" -c "
SELECT count(*) FROM debts d
  JOIN wallets w ON w.id = d.wallet_id
  JOIN users u   ON u.id = w.user_id
 WHERE u.email = 'bob@teste.com';")
check "bob não tem dívida nenhuma (recebido ${N_EXP_B:-vazio})" \
    "$([ "$N_EXP_B" = "0" ] && echo true || echo false)"

echo
echo "resultado: $pass passaram, $fail falharam"
[ "$fail" -eq 0 ]
