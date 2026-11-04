#!/usr/bin/env bash
# Testes do Checkout Pro (Mercado Pago).
#
# O foco não é a chamada HTTP: é a fronteira de confiança. O corpo do webhook
# não vale nada — status e valor têm de vir da API do MP e do banco. Um
# webhook forjado com "approved" não pode creditar nada, e reenviar o mesmo
# pagamento não pode creditar duas vezes.
#
# Uso:  tests/mercadopago_e2e.sh
# Env:   PGHOST PGPORT PGUSER PGPASSWORD

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="kavelipay_mp_test_$$"
E2E_PORTA=${BASE_PORT:-}
while [ -z "$E2E_PORTA" ]; do
  E2E_PORTA=$(( (RANDOM % 1000) + 4000 ))
  ss -ltn 2>/dev/null | grep -q ":${E2E_PORTA} " && E2E_PORTA=
done
MP_PORTA=${MP_BASE_PORT:-}
while [ -z "$MP_PORTA" ]; do
  MP_PORTA=$(( (RANDOM % 1000) + 5000 ))
  ss -ltn 2>/dev/null | grep -q ":${MP_PORTA} " && MP_PORTA=
done
BASE="http://127.0.0.1:${E2E_PORTA}"
MOCK="http://127.0.0.1:${MP_PORTA}"
SECRET="$(head -c 48 /dev/urandom | base64 | tr -d '\n')"
BIN="$ROOT/target/debug/kavelipay-backend"
EXT_REF="$(mktemp /tmp/mp-extref.XXXXXX)"
TMP_MOCK="$(mktemp /tmp/mp-mock.XXXXXX.py)"

PSQL=(psql -v ON_ERROR_STOP=1 -q -X)
pass=0
fail=0
SERVER_PID=""
MOCK_PID=""

cleanup() {
    [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
    [ -n "$MOCK_PID" ] && kill "$MOCK_PID" 2>/dev/null || true
    rm -f "$EXT_REF" "$TMP_MOCK"
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

# Mock da API do MP. Dois detalhes de propósito:
#  - o pagamento aprovado devolve transaction_amount 999.00, diferente do valor
#    da cobrança: se o backend creditar 999, o teste falha (valor vem do banco);
#  - external_reference lido de arquivo a cada requisição, porque só existe
#    depois que a cobrança é criada.
cat > "$TMP_MOCK" <<'PY'
import json, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

EXT_REF = sys.argv[1]
PORTA = int(sys.argv[2])

class H(BaseHTTPRequestHandler):
    def _json(self, corpo, status=200):
        dados = json.dumps(corpo).encode()
        self.send_response(status)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(dados)))
        self.end_headers()
        self.wfile.write(dados)

    def do_POST(self):
        tam = int(self.headers.get("content-length") or 0)
        self.rfile.read(tam)
        if self.path == "/checkout/preferences":
            if not (self.headers.get("authorization") or "").startswith("Bearer "):
                self._json({"message": "sem token"}, 401); return
            self._json({"id": "pref-teste-1", "init_point": "https://mp.teste/pref-teste-1"})
        else:
            self._json({"message": "rota"}, 404)

    def do_GET(self):
        if not self.path.startswith("/v1/payments/"):
            self._json({"message": "rota"}, 404); return
        pid = self.path.rsplit("/", 1)[-1]
        try:
            ref = open(EXT_REF).read().strip()
        except Exception:
            ref = ""
        if pid == "pend":
            self._json({"status": "pending", "external_reference": ref})
        elif pid == "outro":
            self._json({"status": "approved", "external_reference": "00000000-0000-0000-0000-000000000000"})
        elif pid == "semstatus":
            self._json({"status": "rejected", "external_reference": ref})
        else:
            self._json({"status": "approved", "external_reference": ref, "transaction_amount": 999.0})

    def log_message(self, *a):
        pass

ThreadingHTTPServer(("127.0.0.1", PORTA), H).serve_forever()
PY

echo "criando banco: $DB"
"${PSQL[@]}" -d postgres -c "CREATE DATABASE \"$DB\";"
[ -x "$BIN" ] || { echo "binário ausente: $BIN"; exit 1; }

echo "subindo mock do MP em $MOCK"
python3 "$TMP_MOCK" "$EXT_REF" "$MP_PORTA" & MOCK_PID=$!

echo "subindo servidor"
DATABASE_URL="postgres://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${DB}" \
JWT_SECRET="$SECRET" BIND_ADDR="127.0.0.1:${E2E_PORTA}" RUST_LOG=error \
MP_ACCESS_TOKEN="TEST-fake-$$" MP_BASE_URL="$MOCK" MP_NOTIFICATION_URL="$BASE/webhooks/mercadopago" \
"$BIN" &
SERVER_PID=$!
for i in $(seq 1 40); do
    curl -s -o /dev/null -X POST "$BASE/auth/login" -H 'content-type: application/json' \
        -d '{"email":"x@x","password":"x"}' 2>/dev/null && break
    sleep 0.25
done

curl -s -o /dev/null -X POST "$BASE/auth/register" -H 'content-type: application/json' \
    -d '{"email":"alice@teste.com","password":"senha-forte-123"}'
AUTH="Authorization: Bearer $(curl -s -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d '{"email":"alice@teste.com","password":"senha-forte-123"}' | get access_token)"

CH=$(curl -s -X POST "$BASE/charges" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"description":"Aluguel do sobrinho","amount":"50.00","debtor_name":"Bruno"}')
ID_CH=$(get id <<<"$CH")
printf '%s' "$ID_CH" > "$EXT_REF"
echo "    cobrança: $ID_CH"

echo
echo "gerar link de pagamento"

SALDO=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "saldo inicial zerado (recebido $SALDO)" \
    "$([ "$SALDO" = "0.00" ] && echo true || echo false)"

LINK=$(curl -s -X POST "$BASE/charges/$ID_CH/pagamento" -H "$AUTH")
check "link responde com preferência do MP (recebido $(get preference_id <<<"$LINK"))" \
    "$([ "$(get preference_id <<<"$LINK")" = "pref-teste-1" ] && echo true || echo false)"
check "link devolve init_point do MP" \
    "$(case "$(get init_point <<<"$LINK")" in https://mp.teste/pref-teste-1) echo true;; *) echo false;; esac)"
PREF_DB=$("${PSQL[@]}" -t -A -d "$DB" -c "SELECT mp_preference_id FROM charges WHERE id='$ID_CH'")
check "preferência gravada na cobrança ($PREF_DB)" \
    "$([ "$PREF_DB" = "pref-teste-1" ] && echo true || echo false)"

echo
echo "página pública: quem recebe o link paga sem ter o app"

TOKEN=$("${PSQL[@]}" -t -A -d "$DB" -c "SELECT share_token FROM charges WHERE id='$ID_CH'")
PAGINA=$(curl -s "$BASE/cobranca/$TOKEN/pagar")
check "página abre sem login e mostra o valor" \
    "$(grep -q 'R\$ 50,00' <<<"$PAGINA" && echo true || echo false)"
check "página mostra a descrição" \
    "$(grep -q 'Aluguel do sobrinho' <<<"$PAGINA" && echo true || echo false)"
check "página tem o botão do Mercado Pago" \
    "$(grep -q 'Pagar com Mercado Pago' <<<"$PAGINA" && echo true || echo false)"
check "token inexistente devolve 404 (recebido $(code "$BASE/cobranca/nao-existe/pagar"))" \
    "$([ "$(code "$BASE/cobranca/nao-existe/pagar")" = "404" ] && echo true || echo false)"

REDIR=$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -X POST "$BASE/cobranca/$TOKEN/pagar")
# Preferência já existia (criada no teste autenticado): a página remonta a URL
# de redirect do MP a partir do pref_id em vez de chamar a API de novo.
check "pagar pela página redireciona pro MP (recebido $REDIR)" \
    "$([ "$REDIR" = "303 https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=pref-teste-1" ] && echo true || echo false)"

# Cobrança nova, ainda sem preferência: a página cria uma e usa o init_point do MP.
CH2=$(curl -s -X POST "$BASE/charges" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"description":"Rateio do churrasco","amount":"12.30","debtor_name":"Carla"}')
TOKEN2=$("${PSQL[@]}" -t -A -d "$DB" -c "SELECT share_token FROM charges WHERE id='$(get id <<<"$CH2")'")
REDIR=$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -X POST "$BASE/cobranca/$TOKEN2/pagar")
check "página cria preferência nova quando não existe (recebido $REDIR)" \
    "$([ "$REDIR" = "303 https://mp.teste/pref-teste-1" ] && echo true || echo false)"

echo
echo "webhook não confia no corpo: só no status devolvido pela API"

# Corpo com "approved" mas o id consultado na API está pendente: nada acontece.
C=$(code -X POST "$BASE/webhooks/mercadopago" -H 'content-type: application/json' \
    -d '{"type":"payment","action":"payment.updated","data":{"id":"pend","status":"approved"}}')
ST=$("${PSQL[@]}" -t -A -d "$DB" -c "SELECT status FROM charges WHERE id='$ID_CH'")
check "webhook de pagamento pendente responde 200 (recebido $C)" \
    "$([ "$C" = "200" ] && echo true || echo false)"
check "cobrança continua PENDING (recebido $ST)" \
    "$([ "$ST" = "PENDING" ] && echo true || echo false)"

C=$(code -X POST "$BASE/webhooks/mercadopago" -H 'content-type: application/json' \
    -d '{"type":"payment","data":{"id":"semstatus"}}')
SALDO=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "pagamento rejeitado não credita nada (saldo $SALDO)" \
    "$([ "$SALDO" = "0.00" ] && echo true || echo false)"

C=$(code -X POST "$BASE/webhooks/mercadopago" -H 'content-type: application/json' \
    -d '{"type":"plan","data":{"id":"123"}}')
check "webhook de outro tipo responde 200 sem efeito (recebido $C)" \
    "$([ "$C" = "200" ] && echo true || echo false)"

echo
echo "pagamento aprovado credita uma vez só"

C=$(code -X POST "$BASE/webhooks/mercadopago" -H 'content-type: application/json' \
    -d '{"type":"payment","data":{"id":"123"}}')
SALDO=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "webhook aprovado responde 200 (recebido $C)" \
    "$([ "$C" = "200" ] && echo true || echo false)"
check "saldo creditado com o valor da COBRANÇA, não do MP 999.00 (recebido $SALDO)" \
    "$([ "$SALDO" = "50.00" ] && echo true || echo false)"

ST=$("${PSQL[@]}" -t -A -d "$DB" -c "SELECT status FROM charges WHERE id='$ID_CH'")
MP_ID=$("${PSQL[@]}" -t -A -d "$DB" -c "SELECT mp_payment_id FROM charges WHERE id='$ID_CH'")
check "cobrança marcada RECEIVED (recebido $ST)" \
    "$([ "$ST" = "RECEIVED" ] && echo true || echo false)"
check "id do pagamento do MP gravado (recebido $MP_ID)" \
    "$([ "$MP_ID" = "123" ] && echo true || echo false)"

TXNS=$("${PSQL[@]}" -t -A -d "$DB" -c "SELECT COUNT(*) FROM ledger_transactions")
check "exatamente 1 transação no ledger (recebido $TXNS)" \
    "$([ "$TXNS" = "1" ] && echo true || echo false)"

C=$(code -X POST "$BASE/webhooks/mercadopago" -H 'content-type: application/json' \
    -d '{"type":"payment","data":{"id":"123"}}')
SALDO=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
TXNS=$("${PSQL[@]}" -t -A -d "$DB" -c "SELECT COUNT(*) FROM ledger_transactions")
check "reenvio do mesmo webhook é idempotente (saldo $SALDO)" \
    "$([ "$SALDO" = "50.00" ] && echo true || echo false)"
check "reenvio não cria transação nova (recebido $TXNS)" \
    "$([ "$TXNS" = "1" ] && echo true || echo false)"

C=$(code -X POST "$BASE/webhooks/mercadopago" -H 'content-type: application/json' \
    -d '{"type":"payment","data":{"id":"outro"}}')
SALDO=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "pagamento de cobrança desconhecida não credita (saldo $SALDO)" \
    "$([ "$SALDO" = "50.00" ] && echo true || echo false)"

echo
echo "link de cobrança já recebida não é gerado de novo"

C=$(code -X POST "$BASE/charges/$ID_CH/pagamento" -H "$AUTH")
check "cobrança RECEIVED recusa novo link com 409 (recebido $C)" \
    "$([ "$C" = "409" ] && echo true || echo false)"

# 400 do extrator de JSON: o MP reenvia e a próxima entrega válida resolve.
C=$(code -X POST "$BASE/webhooks/mercadopago" -H 'content-type: application/json' -d '{')
check "corpo inválido é recusado sem derrubar nada (recebido $C)" \
    "$([ "$C" = "400" ] && echo true || echo false)"

echo
echo "página de cobrança já paga não reapresenta pagamento"

PAGINA=$(curl -s "$BASE/cobranca/$TOKEN/pagar")
check "página de cobrança paga avisa que já foi paga" \
    "$(grep -q 'já foi paga' <<<"$PAGINA" && echo true || echo false)"
check "página de cobrança paga não tem botão de pagar" \
    "$(grep -q 'Pagar com Mercado Pago' <<<"$PAGINA" && echo false || echo true)"
REDIR=$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -X POST "$BASE/cobranca/$TOKEN/pagar")
check "POST em cobrança paga volta pra página, não pro MP (recebido $REDIR)" \
    "$([ "$REDIR" = "303 $BASE/cobranca/$TOKEN/pagar" ] && echo true || echo false)"

echo
echo "--------------------------------------------"
printf 'passaram: %d  falharam: %d\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
