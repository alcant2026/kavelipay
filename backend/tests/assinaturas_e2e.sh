#!/usr/bin/env bash
# Assinatura premium: código de ativação, direito derivado e corte de rota.
#
# O que este script protege:
#  - o direito é DERIVADO (`status='ACTIVE' AND periodo_fim > now()`), não um
#    booleano que alguém esquece de virar: expirar por tempo derruba o acesso;
#  - código de uso único e hash em disco — reusar devolve 422;
#  - renovação antecipada SOMA dias, não reinicia o período;
#  - recurso pago responde 402 com código `plano_necessario` (o app abre a
#    assinatura), e 401 sem token;
#  - emissão de código exige X-Admin-Token;
#  - assinatura de alice não vaza para bob;
#  - a receita NÃO entra no ledger: a carteira de alice continua com o saldo
#    que tinha antes de assinar.
#
# Uso:  tests/assinaturas_e2e.sh
# Env:   PGHOST PGPORT PGUSER PGPASSWORD

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="kavelipay_assinaturas_test_$$"
BASE="http://127.0.0.1:3321"
SECRET="$(head -c 48 /dev/urandom | base64 | tr -d '\n')"
ADMIN="$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
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
errcode() { python3 -c "
import sys,json
try: print(json.load(sys.stdin).get('error',''))
except Exception: print('')"; }

echo "criando banco: $DB"
"${PSQL[@]}" -d postgres -c "CREATE DATABASE \"$DB\";"
[ -x "$BIN" ] || { echo "binário ausente: $BIN"; exit 1; }

echo "subindo servidor"
DATABASE_URL="postgres://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${DB}" \
JWT_SECRET="$SECRET" ADMIN_TOKEN="$ADMIN" BIND_ADDR="127.0.0.1:3321" RUST_LOG=error "$BIN" &
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
ADMIN_H="X-Admin-Token: $ADMIN"
JSON='content-type: application/json'

echo
echo "1. estado inicial: sem plano, recurso pago cortado"
P=$(curl -s "$BASE/billing/plano" -H "$AUTH")
check "plano inicial é grátis (premium=$(get premium <<<"$P"))" \
    "$([ "$(get premium <<<"$P")" = "False" ] && echo true || echo false)"
check "preço mensal é texto \"9.90\" (recebido $(get preco_mensal <<<"$P"))" \
    "$([ "$(get preco_mensal <<<"$P")" = "9.90" ] && echo true || echo false)"

C_REL=$(code "$BASE/reports/monthly" -H "$AUTH")
check "relatório sem plano é 402 (recebido $C_REL)" \
    "$([ "$C_REL" = "402" ] && echo true || echo false)"
ERR=$(curl -s "$BASE/reports/monthly" -H "$AUTH" | errcode)
check "erro do relatório é plano_necessario (recebido ${ERR:-vazio})" \
    "$([ "$ERR" = "plano_necessario" ] && echo true || echo false)"

C_LOOK=$(code -X POST "$BASE/investments/lookup" -H "$AUTH" -H "$JSON" -d '{"symbol":"PETR4"}')
check "lookup por IA sem plano é 402 (recebido $C_LOOK)" \
    "$([ "$C_LOOK" = "402" ] && echo true || echo false)"

C_QUOTE=$(code -X POST "$BASE/investments/quote" -H "$AUTH" -H "$JSON" -d '{"symbol":"PETR4"}')
check "cotação sem plano é 402 (recebido $C_QUOTE)" \
    "$([ "$C_QUOTE" = "402" ] && echo true || echo false)"

echo
echo "2. código inválido não ativa nada"
C_BAD=$(code -X POST "$BASE/billing/ativar" -H "$AUTH" -H "$JSON" -d '{"codigo":"KPL-AAAA-AAAA-AAAA-AAAA"}')
check "código inexistente é 422 (recebido $C_BAD)" \
    "$([ "$C_BAD" = "422" ] && echo true || echo false)"
check "plano continua grátis depois do erro" \
    "$([ "$(curl -s "$BASE/billing/plano" -H "$AUTH" | get premium)" = "False" ] && echo true || echo false)"

echo
echo "3. emissão de código é só do admin"
C_SEM=$(code -X POST "$BASE/admin/assinaturas/codigos" -H "$JSON" -d '{"plano":"mensal"}')
check "sem admin token é 401 (recebido $C_SEM)" \
    "$([ "$C_SEM" = "401" ] && echo true || echo false)"
C_ERRADO=$(code -X POST "$BASE/admin/assinaturas/codigos" -H "X-Admin-Token: errado" -H "$JSON" -d '{"plano":"mensal"}')
check "admin token errado é 401 (recebido $C_ERRADO)" \
    "$([ "$C_ERRADO" = "401" ] && echo true || echo false)"
C_PLANO=$(code -X POST "$BASE/admin/assinaturas/codigos" -H "$ADMIN_H" -H "$JSON" -d '{"plano":"vitalicio"}')
check "plano fora do contrato é 422 (recebido $C_PLANO)" \
    "$([ "$C_PLANO" = "422" ] && echo true || echo false)"

COD1=$(curl -s -X POST "$BASE/admin/assinaturas/codigos" -H "$ADMIN_H" -H "$JSON" -d '{"plano":"mensal"}' | get codigo)
check "admin emite código no formato KPL (recebido ${COD1:-vazio})" \
    "$([ "$(printf '%s' "$COD1" | grep -c '^KPL-')" = "1" ] && echo true || echo false)"
check "código tem 23 caracteres (recebido ${#COD1})" \
    "$([ "${#COD1}" = "23" ] && echo true || echo false)"

echo
echo "4. ativação e liberação"
ATIV=$(curl -s -X POST "$BASE/billing/ativar" -H "$AUTH" -H "$JSON" -d "{\"codigo\":\"$COD1\"}")
# A ativação devolve um access token novo com `premium=true`. O token antigo
# foi emitido antes de assinar e não conhece o direito novo — usá-lo aqui
# provaria o contrário do que o teste quer provar.
AUTH="Authorization: Bearer $(get access_token <<<"$ATIV")"
check "código válido ativa o plano (premium=$(get premium <<<"$ATIV"))" \
    "$([ "$(get premium <<<"$ATIV")" = "True" ] && echo true || echo false)"
check "plano mensal registrado (recebido $(get plano <<<"$ATIV"))" \
    "$([ "$(get plano <<<"$ATIV")" = "mensal" ] && echo true || echo false)"
FIM1=$(get periodo_fim <<<"$ATIV")
check "período futuro registrado (recebido ${FIM1:-vazio})" \
    "$([ -n "$FIM1" ] && echo true || echo false)"

C_REL2=$(code "$BASE/reports/monthly" -H "$AUTH")
check "relatório liberado com plano (recebido $C_REL2)" \
    "$([ "$C_REL2" = "200" ] && echo true || echo false)"

echo
echo "5. código é de uso único"
C_REUSE=$(code -X POST "$BASE/billing/ativar" -H "$AUTH" -H "$JSON" -d "{\"codigo\":\"$COD1\"}")
check "reusar o mesmo código é 422 (recebido $C_REUSE)" \
    "$([ "$C_REUSE" = "422" ] && echo true || echo false)"
ERR_REUSE=$(curl -s -X POST "$BASE/billing/ativar" -H "$AUTH" -H "$JSON" -d "{\"codigo\":\"$COD1\"}" | get message)
check "mensagem diz que o código já foi usado (recebido ${ERR_REUSE:-vazio})" \
    "$(printf '%s' "$ERR_REUSE" | grep -qi 'já foi usado' && echo true || echo false)"

echo
echo "6. renovação antecipada soma dias"
COD2=$(curl -s -X POST "$BASE/admin/assinaturas/codigos" -H "$ADMIN_H" -H "$JSON" -d '{"plano":"anual"}' | get codigo)
ATIV2=$(curl -s -X POST "$BASE/billing/ativar" -H "$AUTH" -H "$JSON" -d "{\"codigo\":\"$COD2\"}")
AUTH="Authorization: Bearer $(get access_token <<<"$ATIV2")"
FIM2=$(get periodo_fim <<<"$ATIV2")
check "segundo código estende o período (antes $FIM1, depois ${FIM2:-vazio})" \
    "$([ -n "$FIM2" ] && [ "$FIM2" \> "$FIM1" ] && echo true || echo false)"
check "plano passa a anual (recebido $(get plano <<<"$ATIV2"))" \
    "$([ "$(get plano <<<"$ATIV2")" = "anual" ] && echo true || echo false)"

echo
echo "7. expirar por tempo derruba o acesso (direito derivado)"
"${PSQL[@]}" -d "$DB" -c "UPDATE assinaturas SET periodo_fim = now() - INTERVAL '1 day'
  WHERE user_id = (SELECT id FROM users WHERE email = 'alice@teste.com');" >/dev/null
# O token anterior carrega `premium=true` por até 15 min. Para provar que o
# direito é derivado do banco, entra de novo: o token novo nasce grátis.
AUTH="Authorization: Bearer $(login alice@teste.com)"
check "plano volta a grátis após expirar" \
    "$([ "$(curl -s "$BASE/billing/plano" -H "$AUTH" | get premium)" = "False" ] && echo true || echo false)"
C_EXP=$(code "$BASE/reports/monthly" -H "$AUTH")
check "relatório volta a 402 após expirar (recebido $C_EXP)" \
    "$([ "$C_EXP" = "402" ] && echo true || echo false)"

echo
echo "8. isolamento entre usuários"
COD3=$(curl -s -X POST "$BASE/admin/assinaturas/codigos" -H "$ADMIN_H" -H "$JSON" -d '{"plano":"mensal"}' | get codigo)
curl -s -o /dev/null -X POST "$BASE/billing/ativar" -H "$AUTH" -H "$JSON" -d "{\"codigo\":\"$COD3\"}"
check "bob continua grátis enquanto alice é premium" \
    "$([ "$(curl -s "$BASE/billing/plano" -H "$AUTH_B" | get premium)" = "False" ] && echo true || echo false)"
C_BOB=$(code "$BASE/reports/monthly" -H "$AUTH_B")
check "relatório de bob continua 402 (recebido $C_BOB)" \
    "$([ "$C_BOB" = "402" ] && echo true || echo false)"

echo
echo "9. a receita não toca o ledger do usuário"
SALDO=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "saldo da carteira não muda com a assinatura (recebido ${SALDO:-vazio})" \
    "$([ "$SALDO" = "0.00" ] && echo true || echo false)"
N_LEDGER=$("${PSQL[@]}" -t -A -d "$DB" -c "
SELECT count(*) FROM ledger_transactions t
  JOIN wallets w ON w.id = t.wallet_id
  JOIN users u   ON u.id = w.user_id
 WHERE u.email = 'alice@teste.com';")
check "assinatura não criou transação no ledger (recebido ${N_LEDGER:-vazio})" \
    "$([ "$N_LEDGER" = "0" ] && echo true || echo false)"

echo
echo "resultado: $pass passaram, $fail falharam"
[ "$fail" -eq 0 ]
