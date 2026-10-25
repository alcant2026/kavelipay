#!/usr/bin/env bash
# Testes de notificações, histórico e perfil/segurança (objetivos 19, 20, 21).
#
# Uso:  tests/profile_notif_history_e2e.sh
# Env:   PGHOST PGPORT PGUSER PGPASSWORD

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="kavelipay_profile_test_$$"
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
jget() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }

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
        -d "{\"email\":\"$EMAIL\",\"password\":\"senha-forte-123\",\"display_name\":\"Antigo\"}"
done
login() { curl -s -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"senha-forte-123\"}" | get access_token; }
AUTH="Authorization: Bearer $(login alice@teste.com)"
AUTH_B="Authorization: Bearer $(login bob@teste.com)"

HOJE=$(date +%F)
AMANHA=$(date -d '+1 day' +%F)
ONTEM=$(date -d '-1 day' +%F)

echo
echo "perfil"

NOME=$(curl -s -X PATCH "$BASE/profile" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"display_name":"Josias"}' | get display_name)
check "PATCH /profile altera display_name (recebido $NOME)" \
    "$([ "$NOME" = "Josias" ] && echo true || echo false)"

# Ausente = não mexe; string vazia esvazia. Confundir os dois faria o nome
# sumir sozinho quando o app mandasse o campo como null.
MAIS_BRANCO=$(curl -s -X PATCH "$BASE/profile" -H "$AUTH" -H 'content-type: application/json' \
    -d '{}' | get display_name)
check "PATCH sem campo preserva o nome (recebido $MAIS_BRANCO)" \
    "$([ "$MAIS_BRANCO" = "Josias" ] && echo true || echo false)"

C_LONG=$(code -X PATCH "$BASE/profile" -H "$AUTH" -H 'content-type: application/json' \
    -d "{\"display_name\":\"$(python3 -c 'print("x"*200)')\"}")
check "nome > 120 chars é 422 (recebido $C_LONG)" \
    "$([ "$C_LONG" = "422" ] && echo true || echo false)"

C_BOB=$(code -X PATCH "$BASE/profile" -H "$AUTH_B" \
    -H 'content-type: application/json' -d '{"display_name":"Hackeado"}')
check "Bob edita a própria perfil ($C_BOB)" \
    "$([ "$C_BOB" = "200" ] && echo true || echo false)"

# O ponto é que editar o perfil de Bob não toca no de Alice.
NOME_ALICE=$(curl -s "$BASE/auth/me" -H "$AUTH" | get display_name)
check "display_name da Alice intacto após Bob editar (recebido $NOME_ALICE)" \
    "$([ "$NOME_ALICE" = "Josias" ] && echo true || echo false)"

echo
echo "troca de senha exige a senha atual"

C_ERRADA=$(code -X POST "$BASE/profile/password" -H "$AUTH" \
    -H 'content-type: application/json' \
    -d '{"current_password":"senha-errada-123","new_password":"nova-senha-forte"}')
check "senha atual errada é 401 (recebido $C_ERRADA)" \
    "$([ "$C_ERRADA" = "401" ] && echo true || echo false)"

C_CURTA=$(code -X POST "$BASE/profile/password" -H "$AUTH" \
    -H 'content-type: application/json' \
    -d '{"current_password":"senha-forte-123","new_password":"123"}')
check "nova senha curta é 422 (recebido $C_CURTA)" \
    "$([ "$C_CURTA" = "422" ] && echo true || echo false)"

# Token stolen-only scenario: sem a senha atual, quem tiver o access token não
# pode tomar a conta. Verificado por 401 acima.

SESSOES_ANTES=$(curl -s "$BASE/profile/sessions" -H "$AUTH" | python3 -c \
    "import sys,json; print(len(json.load(sys.stdin)))")
check "Alice tem ao menos 1 sessão ativa ($SESSOES_ANTES)" \
    "$([ "$SESSOES_ANTES" -ge 1 ] 2>/dev/null && echo true || echo false)"

C_TROCA=$(curl -s -X POST "$BASE/profile/password" -H "$AUTH" \
    -H 'content-type: application/json' \
    -d '{"current_password":"senha-forte-123","new_password":"nova-senha-forte-456"}' | get sessions_revoked)
check "troca de senha revoga as sessões (revogadas: $C_TROCA)" \
    "$([ "$C_TROCA" -ge 1 ] 2>/dev/null && echo true || echo false)"

C_ANTIGA=$(code -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d '{"email":"alice@teste.com","password":"senha-forte-123"}')
check "login com a senha antiga é 401 (recebido $C_ANTIGA)" \
    "$([ "$C_ANTIGA" = "401" ] && echo true || echo false)"

C_NOVA=$(code -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d '{"email":"alice@teste.com","password":"nova-senha-forte-456"}')
check "login com a senha nova funciona (recebido $C_NOVA)" \
    "$([ "$C_NOVA" = "200" ] && echo true || echo false)"

AUTH=$(curl -s -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d '{"email":"alice@teste.com","password":"nova-senha-forte-456"}' | get access_token)
AUTH="Authorization: Bearer $AUTH"

echo
echo "isolamento no perfil e nas sessões"

# Bob trocar a PRÓPRIA senha com a senha atual dele deve funcionar (200) — e o
# que importa é que a senha da Alice continue valendo depois.
C_BOB_TROCA=$(code -X POST "$BASE/profile/password" -H "$AUTH_B" \
    -H 'content-type: application/json' \
    -d '{"current_password":"senha-forte-123","new_password":"bob-nova-senha-789"}')
check "Bob troca a própria senha ($C_BOB_TROCA)" \
    "$([ "$C_BOB_TROCA" = "200" ] && echo true || echo false)"

C_ALICE_APOS=$(code -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d '{"email":"alice@teste.com","password":"nova-senha-forte-456"}')
check "senha da Alice continua válida depois (recebido $C_ALICE_APOS)" \
    "$([ "$C_ALICE_APOS" = "200" ] && echo true || echo false)"

# E a senha antiga do Bob não pode mais servir.
C_BOB_ANTIGA=$(code -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d '{"email":"bob@teste.com","password":"senha-forte-123"}')
check "senha antiga do Bob é 401 depois da troca (recebido $C_BOB_ANTIGA)" \
    "$([ "$C_BOB_ANTIGA" = "401" ] && echo true || echo false)"

SESSAO_ALICE=$(curl -s "$BASE/profile/sessions" -H "$AUTH" | jget "[0]['id']")
C_REVOKE_CROSS=$(code -X DELETE "$BASE/profile/sessions/$SESSAO_ALICE" -H "$AUTH_B")
check "Bob não revoga sessão da Alice (recebido $C_REVOKE_CROSS)" \
    "$([ "$C_REVOKE_CROSS" = "404" ] && echo true || echo false)"

echo
echo "notificações são derivadas e idempotentes"

curl -s -o /dev/null -X POST "$BASE/bills" -H "$AUTH" -H 'content-type: application/json' \
    -d "{\"description\":\"Luz\",\"amount\":\"80.00\",\"due_date\":\"$AMANHA\"}"
curl -s -o /dev/null -X POST "$BASE/bills" -H "$AUTH" -H 'content-type: application/json' \
    -d "{\"description\":\"Água vencida\",\"amount\":\"40.00\",\"due_date\":\"$ONTEM\"}"
# Boleto distante: fora da janela de 3 dias, NÃO deve gerar aviso.
curl -s -o /dev/null -X POST "$BASE/bills" -H "$AUTH" -H 'content-type: application/json' \
    -d "{\"description\":\"IPTU 2027\",\"amount\":\"300.00\",\"due_date\":\"2027-03-10\"}"

G1=$(curl -s -X POST "$BASE/notifications/generate" -H "$AUTH")
check "1ª geração cria 2 avisos (recebido $(get criadas <<<"$G1"))" \
    "$([ "$(get criadas <<<"$G1")" = "2" ] && echo true || echo false)"

G2=$(curl -s -X POST "$BASE/notifications/generate" -H "$AUTH")
check "2ª geração cria 0 (idempotente, recebido $(get criadas <<<"$G2"))" \
    "$([ "$(get criadas <<<"$G2")" = "0" ] && echo true || echo false)"

QTD_TOTAL=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COUNT(*) FROM notifications")
check "só 2 notificações no total (recebido $QTD_TOTAL, IPTU fora da janela)" \
    "$([ "$QTD_TOTAL" = "2" ] && echo true || echo false)"

TIPOS=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT string_agg(type, ',' ORDER BY type) FROM notifications")
check "tipos são BILL_OVERDUE e BILL_DUE (recebido $TIPOS)" \
    "$([ "$TIPOS" = "BILL_DUE,BILL_OVERDUE" ] && echo true || echo false)"

N_BOB=$(curl -s "$BASE/notifications" -H "$AUTH_B" | python3 -c \
    "import sys,json; print(len(json.load(sys.stdin)))")
check "Bob não vê notificações da Alice ($N_BOB)" \
    "$([ "$N_BOB" = "0" ] && echo true || echo false)"

echo
echo "marcar como lida"

N1=$(curl -s "$BASE/notifications?unread_only=true" -H "$AUTH" | jget "[0]['id']")
C_READ=$(code -X POST "$BASE/notifications/$N1/read" -H "$AUTH")
check "POST /notifications/{id}/read é 200 (recebido $C_READ)" \
    "$([ "$C_READ" = "200" ] && echo true || echo false)"

UNREAD=$(curl -s "$BASE/notifications?unread_only=true" -H "$AUTH" | python3 -c \
    "import sys,json; print(len(json.load(sys.stdin)))")
check "resta 1 não lida (recebido $UNREAD)" \
    "$([ "$UNREAD" = "1" ] && echo true || echo false)"

C_READ_CROSS=$(code -X POST "$BASE/notifications/$N1/read" -H "$AUTH_B")
check "Bob não marca notificação da Alice ($C_READ_CROSS)" \
    "$([ "$C_READ_CROSS" = "404" ] && echo true || echo false)"

curl -s -o /dev/null -X POST "$BASE/notifications/read-all" -H "$AUTH"
UNREAD_FIM=$(curl -s "$BASE/notifications?unread_only=true" -H "$AUTH" | python3 -c \
    "import sys,json; print(len(json.load(sys.stdin)))")
check "read-all zera as não lidas (recebido $UNREAD_FIM)" \
    "$([ "$UNREAD_FIM" = "0" ] && echo true || echo false)"

# Pagando o boleto, o aviso deixa de fazer sentido: regenerar não ressuscita.
BOLETO_VENCIDO=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT id FROM bills WHERE description LIKE 'gua vencida%'")
curl -s -o /dev/null -X POST "$BASE/bills/$BOLETO_VENCIDO/pay" -H "$AUTH" \
    -H 'content-type: application/json' -d '{}'
G3=$(curl -s -X POST "$BASE/notifications/generate" -H "$AUTH")
check "gerar de novo ainda cria 0 (recebido $(get criadas <<<"$G3"))" \
    "$([ "$(get criadas <<<"$G3")" = "0" ] && echo true || echo false)"

echo
echo "histórico unificado"

curl -s -o /dev/null -X POST "$BASE/wallet/transactions" -H "$AUTH" \
    -H 'content-type: application/json' -d '{"type":"ENTRY","amount":"500.00","description":"Salário"}'
curl -s -o /dev/null -X POST "$BASE/wallet/transactions" -H "$AUTH" \
    -H 'content-type: application/json' -d '{"type":"EXPENSE","amount":"50.00","description":"Mercado"}'
curl -s -o /dev/null -X POST "$BASE/charges" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"description":"Aluguel cobrado","amount":"70.00"}'
curl -s -o /dev/null -X POST "$BASE/splits" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"description":"Churrasco","total_amount":"60.00","parts":[{"name":"Dani","amount":"60.00"}]}'

HIST=$(curl -s "$BASE/history" -H "$AUTH")
KIND=$(printf '%s' "$HIST" | python3 -c \
    "import sys,json; print(','.join(sorted({i['kind'] for i in json.load(sys.stdin)['items']})))")
check "histórico tem as 4 origens (recebido $KIND)" \
    "$([ "$KIND" = "BILL,CHARGE,SPLIT,TRANSACTION" ] && echo true || echo false)"

BAL_HIST=$(get balance <<<"$HIST")
check "histórico traz o saldo derivado (recebido $BAL_HIST)" \
    "$([ -n "$BAL_HIST" ] && echo true || echo false)"

# O saldo do histórico tem de bater com o de /wallet — mesma fonte de verdade.
BAL_WALLET=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "saldo do histórico = saldo de /wallet ($BAL_HIST vs $BAL_WALLET)" \
    "$([ "$BAL_HIST" = "$BAL_WALLET" ] && echo true || echo false)"

SO_BILLS=$(curl -s "$BASE/history?kind=BILL" -H "$AUTH" | python3 -c \
    "import sys,json; print(','.join({i['kind'] for i in json.load(sys.stdin)['items']}))")
check "filtro kind=BILL só devolve BILL (recebido $SO_BILLS)" \
    "$([ "$SO_BILLS" = "BILL" ] && echo true || echo false)"

C_KIND_BAD=$(code "$BASE/history?kind=INVENTADO" -H "$AUTH")
check "?kind=inventado devolve feed vazio, não erro ($C_KIND_BAD)" \
    "$([ "$C_KIND_BAD" = "200" ] && echo true || echo false)"

HIST_BOB=$(curl -s "$BASE/history" -H "$AUTH_B" | python3 -c \
    "import sys,json; print(len(json.load(sys.stdin)['items']))")
check "histórico do Bob não tem itens da Alice ($HIST_BOB)" \
    "$([ "$HIST_BOB" = "0" ] && echo true || echo false)"

echo
echo "resultado: $pass passaram, $fail falharam"
[ "$fail" -eq 0 ]
# ── Teto de sessões ativas ──────────────────────────────────────────────────
# Cada login abre uma sessão de 30 dias. Sem teto, a tela "Sessões abertas"
# vira uma parede de linhas quase idênticas (foi a queixa do usuário). O login
# mantém no máximo 5 ativas, revogando as mais antigas — e o access token da
# sessão revogada continua valendo até expirar, então dá para conferir com ele.
# Bob trocou a senha no meio do teste (bloco de segurança), então login() — que
# usa a senha original — daria 401 aqui.
login_bob() { curl -s -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d '{"email":"bob@teste.com","password":"bob-nova-senha-789"}' | get access_token; }
for _ in $(seq 1 7); do login_bob >/dev/null; done
SESSOES_BOB=$(curl -s "$BASE/profile/sessions" -H "$AUTH_B" | python3 -c \
    "import sys,json; print(len(json.load(sys.stdin)))")
check "login acima do teto deixa exatamente 5 sessões ativas ($SESSOES_BOB)" \
    "$([ "$SESSOES_BOB" = "5" ] && echo true || echo false)"

# A sessão mais nova é a que acabou de nascer: o refresh dela precisa funcionar
# (prova de que a poda revogou as antigas, não a recém-criada).
ULTIMO_REFRESH=$(curl -s -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d '{"email":"bob@teste.com","password":"bob-nova-senha-789"}' | get refresh_token)
C_REFRESH_NOVO=$(code -X POST "$BASE/auth/refresh" -H 'content-type: application/json' \
    -d "{\"refresh_token\":\"$ULTIMO_REFRESH\"}")
check "refresh da sessão mais nova funciona depois da poda ($C_REFRESH_NOVO)" \
    "$([ "$C_REFRESH_NOVO" = "200" ] && echo true || echo false)"
