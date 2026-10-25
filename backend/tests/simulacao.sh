#!/usr/bin/env bash
# Simulação de uma jornada real do usuário, contra o servidor de verdade e um
# banco descartável. Cada passo imprime o que aconteceu, incluindo os
# movimentos de saldo — o ponto é ver o dinheiro entrar e sair.
#
# Uso:  tests/simulacao.sh
# Env:   PGHOST PGPORT PGUSER PGPASSWORD

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="kavelipay_simulacao_$$"
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

PSQL=(psql -v ON_ERROR_STOP=1 -q -X -h "${PGHOST:-localhost}" -p "${PGPORT:-55432}" -U "${PGUSER:-kavelipay}")
SERVER_PID=""
falhas=0

cleanup() {
    [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
    "${PSQL[@]}" -d postgres -c "DROP DATABASE IF EXISTS \"$DB\";" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ── apresentação ─────────────────────────────────────────────────────────────
passou() { printf '   \033[32m✓\033[0m %s\n' "$1"; }
falhou() { printf '   \033[31m✗ %s\033[0m\n' "$1"; falhas=$((falhas + 1)); }
titulo() { printf '\n\033[1m%s\033[0m\n' "$1"; }
linha()  { printf '   %s\n' "$1"; }

get()   { python3 -c "import sys,json; print(json.load(sys.stdin).get('$1',''))"; }
code()  { curl -s -o /dev/null -w '%{http_code}' "$@"; }
jget()  { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }
hoje()  { date +%F; }
em()    { date -d "$1 day" +%F; }

confere() { # confere <descrição> <esperado> <obtido>
    if [ "$2" = "$3" ]; then passou "$1"; else falhou "$1 (esperado $2, obtido $3)"; fi
}

echo "════════════════════════════════════════════════════════════════"
echo " SIMULAÇÃO KAVELIPAY — servidor real, banco descartável"
echo "════════════════════════════════════════════════════════════════"

"${PSQL[@]}" -d postgres -c "CREATE DATABASE \"$DB\";"
[ -x "$BIN" ] || { echo "binário ausente: $BIN (rode cargo build)"; exit 1; }

DATABASE_URL="postgres://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${DB}" \
JWT_SECRET="$SECRET" BIND_ADDR="127.0.0.1:${E2E_PORTA}" RUST_LOG=error "$BIN" &
SERVER_PID=$!
for i in $(seq 1 40); do
    curl -s -o /dev/null -X POST "$BASE/auth/login" -H 'content-type: application/json' \
        -d '{"email":"x@x","password":"x"}' 2>/dev/null && break
    sleep 0.25
done
echo "servidor no ar em $BASE"

# ═══ 1. CADASTRO ═════════════════════════════════════════════════════════════
titulo "1. Josias cria a conta"

REG=$(curl -s -X POST "$BASE/auth/register" -H 'content-type: application/json' \
    -d '{"email":"josias@kavelipay.app","password":"minha-senha-forte","display_name":"Josias"}')
ACCESS=$(get access_token <<<"$REG")
AUTH="Authorization: Bearer $ACCESS"

passou "conta criada, tokens emitidos"
confere "senha nunca aparece na resposta" "" "$(grep -o 'minha-senha-forte' <<<"$REG")"

C_DUP=$(code -X POST "$BASE/auth/register" -H 'content-type: application/json' \
    -d '{"email":"JOSIAS@kavelipay.app","password":"outra-senha-123"}')
confere "e-mail duplicado (caixa diferente) é rejeitado" "409" "$C_DUP"

CARTEIRA=$(curl -s "$BASE/wallet" -H "$AUTH")
confere "carteira criada automaticamente no cadastro" "0.00" "$(get balance <<<"$CARTEIRA")"
confere "moeda BRL" "BRL" "$(get currency <<<"$CARTEIRA")"
linha "saldo inicial: R\$ $(get balance <<<"$CARTEIRA")"

# ═══ 2. ENTRADAS ════════════════════════════════════════════════════════════
titulo "2. Entradas de dinheiro"

for E in "Salário|3200.00" "Freelance|850.50" "Benefício|1200.00"; do
    DESC="${E%|*}"; VAL="${E#*|}"
    R=$(curl -s -X POST "$BASE/wallet/transactions" -H "$AUTH" \
        -H 'content-type: application/json' \
        -d "{\"type\":\"ENTRY\",\"amount\":\"$VAL\",\"description\":\"$DESC\"}")
    linha "$(printf '%-12s + R\$ %-10s → saldo R\$ %s' "$DESC" "$VAL" "$(get balance <<<"$R")")"
done

C_FLOAT=$(code -X POST "$BASE/wallet/transactions" -H "$AUTH" \
    -H 'content-type: application/json' -d '{"type":"ENTRY","amount":3200.00}')
confere "valor como float JSON é recusado" "422" "$C_FLOAT"

# ═══ 3. CONTAS A PAGAR ══════════════════════════════════════════════════════
titulo "3. Contas a pagar cadastradas"

declare -A BOLETO
cadastra_boleto() { # cadastra_boleto <desc> <valor> <data>
    local r id
    r=$(curl -s -X POST "$BASE/bills" -H "$AUTH" -H 'content-type: application/json' \
        -d "{\"description\":\"$1\",\"amount\":\"$2\",\"due_date\":\"$3\"}")
    id=$(get id <<<"$r")
    BOLETO["$1"]=$id
    printf '   %-16s R\$ %-9s vence %s  [%s]\n' "$1" "$2" "$3" "$(get status <<<"$r")"
}

cadastra_boleto "Aluguel"    "1850.00" "$(em 12)"
cadastra_boleto "Energia"     "247.80" "$(em 4)"
cadastra_boleto "Internet"     "99.90" "$(em -6)"   # já venceu
cadastra_boleto "Celular"      "49.90" "$(em 20)"

titulo "4. O que o usuário vê das contas"

VEN=$(curl -s "$BASE/bills?overdue=true" -H "$AUTH")
# /bills devolve {items:[...], balance}, então o caminho é ['items'][0].
linha "vencidas (derivadas, nunca gravadas): $(printf '%s' "$VEN" | jget "['items'][0]['description']")"
PEND_TOTAL=$("${PSQL[@]}" -t -A -d "$DB" -c "SELECT SUM(amount) FROM bills WHERE status='PENDING'")
linha "total a pagar pendente: R\$ $PEND_TOTAL"

C_STATUS=$(code "$BASE/bills?status=OVERDUE" -H "$AUTH")
confere "OVERDUE não é um status consultável" "422" "$C_STATUS"

# ═══ 5. PAGAR CONTA ═════════════════════════════════════════════════════════
titulo "5. Josias paga a conta de energia"

ANTES=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
linha "saldo antes:  R\$ $ANTES"

PAGO=$(curl -s -X POST "$BASE/bills/${BOLETO[Energia]}/pay" -H "$AUTH" \
    -H 'content-type: application/json' \
    -d '{"comprovante_url":"https://exemplo/comprovante.pdf"}')
linha "conta:        $(get status <<<"$PAGO")"
DEPOIS=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
linha "saldo depois: R\$ $DEPOIS"

python3 -c "
a, b = float('$ANTES'), float('$DEPOIS')
print(f'   \033[33m→ caiu exatamente R\$ {a - b:.2f} (e não R\$ 0.00)\033[0m')" 2>/dev/null || linha "→ saldo alterado"

C_2X=$(code -X POST "$BASE/bills/${BOLETO[Energia]}/pay" -H "$AUTH" \
    -H 'content-type: application/json' -d '{}')
confere "pagar duas vezes não debita de novo" "409" "$C_2X"

# ═══ 6. DESPESAS ════════════════════════════════════════════════════════════
titulo "6. Despesas do dia a dia"

for D in "Mercado|186.40" "Combustível|120.00" "Cinema|45.00"; do
    DESC="${D%|*}"; VAL="${D#*|}"
    R=$(curl -s -X POST "$BASE/wallet/transactions" -H "$AUTH" \
        -H 'content-type: application/json' \
        -d "{\"type\":\"EXPENSE\",\"amount\":\"$VAL\",\"description\":\"$DESC\"}")
    linha "$(printf '%-14s - R\$ %-9s → saldo R\$ %s' "$DESC" "$VAL" "$(get balance <<<"$R")")"
done

# ═══ 7. DIVISÃO ═════════════════════════════════════════════════════════════
titulo "7. Jantar de fim de semana, dividido entre 3"

DIV=$(curl -s -X POST "$BASE/splits" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"description":"Jantar pizzaria","total_amount":"180.00","parts":[
          {"name":"Ana","amount":"60.00"},
          {"name":"Bruno","amount":"60.00"},
          {"name":"Carla","amount":"60.00"}]}')
SPLIT=$(get id <<<"$DIV")
linha "José pagou R\$ 180.00 → saldo R\$ $(curl -s "$BASE/wallet" -H "$AUTH" | get balance)"
linha "a receber de 3 pessoas: R\$ $(get pending_amount <<<"$DIV")"

C_QUEBRADO=$(code -X POST "$BASE/splits" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"description":"ERRO","total_amount":"100.00","parts":[
          {"name":"Ana","amount":"40.00"},{"name":"Bruno","amount":"40.00"}]}')
confere "divisão que não fecha é recusada" "422" "$C_QUEBRADO"

PARTE_ANA=$(curl -s "$BASE/splits" -H "$AUTH" | jget "[0]['parts'][0]['id']")
QUIT=$(curl -s -X POST "$BASE/splits/$SPLIT/parts/$PARTE_ANA/settle" -H "$AUTH")
linha "Ana quitou R\$ 60.00 → saldo R\$ $(get balance <<<"$QUIT")"
linha "ainda a receber: R\$ $(get pending_amount <<<"$QUIT")"

C_2X_QUIT=$(code -X POST "$BASE/splits/$SPLIT/parts/$PARTE_ANA/settle" -H "$AUTH")
confere "quitar duas vezes não credita de novo" "409" "$C_2X_QUIT"

# ═══ 8. COBRANÇA + QR ═══════════════════════════════════════════════════════
titulo "8. Josias cobra do Bruno"

CH=$(curl -s -X POST "$BASE/charges" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"description":"Passeio do flea market","amount":"75.00","debtor_name":"Bruno"}')
CH_ID=$(get id <<<"$CH")
# `qr` é um objeto aninhado: `get` só pega chave de topo, então desce com jget.
QR=$(printf '%s' "$CH" | jget "['qr']['payload']")

SALDO_APOS_COBRAR=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
linha "cobrança criada de R\$ 75.00"
linha "saldo NÃO mudou ao cobrar: R\$ $SALDO_APOS_COBRAR"
linha "QR Code: $QR"
linha "formato: $(printf '%s' "$CH" | jget "['qr']['format']") · provider: $(printf '%s' "$CH" | jget "['qr']['provider']")"

case "$QR" in
    0002*|*BR.GOV.BCB.PIX*) falhou "QR não pode ser BR Code / PIX do Banco Central" ;;
    kavelipay://*)         passou "QR é deep link do app — NÃO é PIX (como a spec exige)" ;;
    *)                     falhou "formato inesperado de QR: $QR" ;;
esac

REC=$(curl -s -X POST "$BASE/charges/$CH_ID/receive" -H "$AUTH")
linha "Bruno pagou → status $(get status <<<"$REC") · saldo R\$ $(curl -s "$BASE/wallet" -H "$AUTH" | get balance)"

# ═══ 9. NOTIFICAÇÕES ════════════════════════════════════════════════════════
titulo "9. Lembretes"

G1=$(curl -s -X POST "$BASE/notifications/generate" -H "$AUTH")
G2=$(curl -s -X POST "$BASE/notifications/generate" -H "$AUTH")
linha "1ª verificação gerou $(get criadas <<<"$G1") avisos"
linha "2ª verificação gerou $(get criadas <<<"$G2") avisos (nada duplicado)"
linha "não lidas: $(get unread <<<"$G1")"

curl -s "$BASE/notifications" -H "$AUTH" | python3 -c "
import sys, json
for n in json.load(sys.stdin):
    print(f'   • {n[\"title\"]:14} {n[\"body\"]}')"

# ═══ 10. HISTÓRICO ══════════════════════════════════════════════════════════
titulo "10. Histórico unificado"

curl -s "$BASE/history?limit=6" -H "$AUTH" | python3 -c "
import sys, json
h = json.load(sys.stdin)
for i in h['items']:
    sinal = '−' if i['is_saida'] else '+'
    print(f'   {sinal} R\$ {i[\"amount\"]:>8}  {i[\"kind\"]:11} {i[\"title\"]}')"

# Regressão do extrato: dinheiro que ENTRA precisa aparecer com valor. Usar a
# coluna `debit` como amount fazia toda entrada valer R$ 0,00 — o extrato
# ficava bonito e errado.
ENTRADAS_ZERO=$(curl -s "$BASE/wallet/transactions?limit=100" -H "$AUTH" | python3 -c "
import sys, json
itens = json.load(sys.stdin)['items']
print(sum(1 for i in itens if not i['is_saida'] and float(i['amount']) == 0))")
confere "entradas com valor R\$ 0,00 no extrato" "0" "$ENTRADAS_ZERO"

SAIDAS_ZERO=$(curl -s "$BASE/wallet/transactions?limit=100" -H "$AUTH" | python3 -c "
import sys, json
itens = json.load(sys.stdin)['items']
print(sum(1 for i in itens if i['is_saida'] and float(i['amount']) == 0))")
confere "saídas com valor R\$ 0,00 no extrato" "0" "$SAIDAS_ZERO"

HIST_ZERO=$(curl -s "$BASE/history?limit=100" -H "$AUTH" | python3 -c "
import sys, json
print(sum(1 for i in json.load(sys.stdin)['items'] if float(i['amount']) == 0))")
confere "itens com valor R\$ 0,00 no histórico" "0" "$HIST_ZERO"

# ═══ 11. SEGURANÇA ═════════════════════════════════════════════════════════
titulo "11. Segurança"

linha "sessões ativas: $(curl -s "$BASE/profile/sessions" -H "$AUTH" | python3 -c 'import sys,json; print(len(json.load(sys.stdin)))')"

C_SEM_SENHA=$(code -X POST "$BASE/profile/password" -H "$AUTH" \
    -H 'content-type: application/json' \
    -d '{"current_password":"errada","new_password":"nova-senha-123"}')
confere "trocar senha sem a senha atual é bloqueado" "401" "$C_SEM_SENHA"

# ═══ 12. O OUTRO USUÁRIO ═══════════════════════════════════════════════════
titulo "12. Isolamento entre contas"

curl -s -o /dev/null -X POST "$BASE/auth/register" -H 'content-type: application/json' \
    -d '{"email":"intruso@teste.com","password":"outra-senha-123"}'
INTRUSO=$(curl -s -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d '{"email":"intruso@teste.com","password":"outra-senha-123"}' | get access_token)
AUTH_I="Authorization: Bearer $INTRUSO"

confere "saldo do intruso" "0.00" "$(curl -s "$BASE/wallet" -H "$AUTH_I" | get balance)"
confere "extrato do intruso" "0" "$(curl -s "$BASE/wallet/transactions" -H "$AUTH_I" | python3 -c 'import sys,json; print(len(json.load(sys.stdin)["items"]))')"
confere "histórico do intruso" "0" "$(curl -s "$BASE/history" -H "$AUTH_I" | python3 -c 'import sys,json; print(len(json.load(sys.stdin)["items"]))')"
confere "contas do intruso" "0" "$(curl -s "$BASE/bills" -H "$AUTH_I" | python3 -c 'import sys,json; print(len(json.load(sys.stdin)["items"]))')"

C_CROSS=$(code -X POST "$BASE/bills/${BOLETO[Aluguel]}/pay" -H "$AUTH_I" \
    -H 'content-type: application/json' -d '{}')
confere "não consegue pagar a conta do Josias" "404" "$C_CROSS"

C_QUERY=$(code "$BASE/wallet?user_id=$(curl -s "$BASE/auth/me" -H "$AUTH" | get user_id)" -H "$AUTH_I")
linha "injetar user_id na query também não funciona (retorna $C_QUERY — o token manda)"

# ═══ 13. CONFERÊNCIA CONTÁBIL ═══════════════════════════════════════════════
titulo "13. Conferência final no banco"

linha "saldo da carteira (derivado dos lançamentos):"
"${PSQL[@]}" -d "$DB" -c "
SELECT COALESCE(SUM(e.credit - e.debit), 0) AS saldo_derivado
  FROM ledger_entries e
  JOIN ledger_accounts a ON a.id = e.account_id
  JOIN wallets w ON w.id = a.wallet_id
  JOIN users u ON u.id = w.user_id
 WHERE u.email = 'josias@kavelipay.app' AND a.kind = 'USER_AVAILABLE';" \
    | sed 's/^/   /' | head -4

DESBAL=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COUNT(*) FROM (SELECT transaction_id FROM ledger_entries
      GROUP BY transaction_id HAVING SUM(debit) <> SUM(credit)) x")
confere "transações desequilibradas" "0" "$DESBAL"

SEM_SOMA=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COUNT(*) FROM (
        SELECT s.id FROM expense_splits s
        LEFT JOIN expense_split_parts p ON p.split_id = s.id
        GROUP BY s.id, s.total_amount
        HAVING COALESCE(SUM(p.owed_amount), 0) <> s.total_amount) y")
confere "divisões de despesa que não fecham" "0" "$SEM_SOMA"

OVERDUE_GRAVADO=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COUNT(*) FROM bills WHERE status = 'OVERDUE'")
confere "boletos com OVERDUE gravado no banco" "0" "$OVERDUE_GRAVADO"

TIPOS=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT string_agg(DISTINCT type, ', ') FROM ledger_transactions")
linha "tipos de lançamento registrados: $TIPOS"

SALDO_API=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
SALDO_BANCO=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COALESCE(SUM(e.credit - e.debit), 0) FROM ledger_entries e
     JOIN ledger_accounts a ON a.id = e.account_id
     JOIN wallets w ON w.id = a.wallet_id
     JOIN users u ON u.id = w.user_id
     WHERE u.email='josias@kavelipay.app' AND a.kind='USER_AVAILABLE'")
# O banco guarda com escala 4 (4606.3000) e a API formata com 2 casas
# (4606.30) — é o MESMO número, então a comparação é numérica.
IGUAIS=$(python3 -c "print('sim' if float('$SALDO_API') == float('$SALDO_BANCO') else 'nao')")
confere "saldo da API = saldo do banco" "sim" "$IGUAIS"
linha ""
linha "saldo final de Josias: R\$ $SALDO_API"

echo
echo "════════════════════════════════════════════════════════════════"
if [ "$falhas" -eq 0 ]; then
    printf ' \033[32mSIMULAÇÃO CONCLUÍDA — nenhuma inconsistência\033[0m\n'
else
    printf ' \033[31m%d FALHA(S)\033[0m\n' "$falhas"
fi
echo "════════════════════════════════════════════════════════════════"
[ "$falhas" -eq 0 ]