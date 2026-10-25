#!/usr/bin/env bash
# Prova que compra de ativo NÃO aparece como despesa no relatório mensal.
#
# O bug: o relatório filtrava por `a.kind = 'USER_AVAILABLE'`, e uma compra de
# ativo DEBITA essa conta. A soma ia para `total_expense` e o relatório dizia
# que o usuário "gastou" R$ 350 ao comprar 10 ações — que foi justamente o que
# ele não fez.
#
# Uso: tests/investments_e2e.sh
# Env:  PGHOST PGPORT PGUSER PGPASSWORD

set -euo pipefail

# Porta por execução: dois scripts em paralelo (ou servidor zumbi) não brigam
# por 3311-3324. BASE_PORT fixa a porta explicitamente quando precisar.
E2E_PORTA=${BASE_PORT:-}
while [ -z "$E2E_PORTA" ]; do
  E2E_PORTA=$(( (RANDOM % 1000) + 4000 ))
  ss -ltn 2>/dev/null | grep -q ":${E2E_PORTA} " && E2E_PORTA=
done
BASE="http://127.0.0.1:${E2E_PORTA}"
SECRET="$(head -c 48 /dev/urandom | base64 | tr -d '\n')"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/target/debug/kavelipay-backend"

pass=0
fail=0
SERVER_PID=""

cleanup() { [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true; }
trap cleanup EXIT

check() {
    if [ "$2" = "true" ]; then printf '  ok: %s\n' "$1"; pass=$((pass + 1))
    else printf '  FALHOU: %s\n' "$1"; fail=$((fail + 1)); fi
}
get()  { python3 -c "import sys,json; print(json.load(sys.stdin).get('$1',''))"; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

[ -x "$BIN" ] || { echo "binário ausente: $BIN (rode cargo build)"; exit 1; }

echo "subindo servidor em $BASE"
# Fontes de cotação apontam para porta morta de propósito: o caminho
# determinístico é "provedor fora → 200 vazio". Depender da rede (brapi de
# verdade) tornaria este teste não-determinístico e lento.
DATABASE_URL="postgres://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/kavelipay" \
JWT_SECRET="$SECRET" BIND_ADDR="127.0.0.1:${E2E_PORTA}" RUST_LOG=error \
BRAPI_BASE_URL="http://127.0.0.1:1" YAHOO_BASE_URL="http://127.0.0.1:1" \
    STATUSINVEST_BASE_URL="http://127.0.0.1:1" "$BIN" &
SERVER_PID=$!
for _ in $(seq 1 40); do
    curl -s -o /dev/null -X POST "$BASE/auth/login" -H 'content-type: application/json' \
        -d '{"email":"x@x","password":"x"}' 2>/dev/null && break
    sleep 0.25
done

EMAIL="inv-$$@teste.com"
SENHA="senha-forte-123"
curl -s -o /dev/null -X POST "$BASE/auth/register" -H 'content-type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$SENHA\"}"

# Relatório é Premium: a assinatura precisa existir ANTES do login, porque o
# claim `premium` é congelado no access token no momento da emissão.
PSQL=(psql -v ON_ERROR_STOP=1 -q -X)
"${PSQL[@]}" -d kavelipay -c "INSERT INTO assinaturas (user_id, provider, provider_sub_id, plano, status, periodo_fim)
  SELECT id, 'manual', 'e2e-'||id::text, 'mensal', 'ACTIVE', now() + INTERVAL '30 days'
  FROM users WHERE email = '$EMAIL';" >/dev/null

AUTH="Authorization: Bearer $(curl -s -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$SENHA\"}" | get access_token)"

MES=$(date +%Y-%m)

echo
echo "1. registrar ativo e movimento de compra"

curl -s -o /dev/null -X POST "$BASE/wallet/transactions" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"type":"ENTRY","amount":"5000.00","description":"Salário"}'

INV=$(curl -s -X POST "$BASE/investments" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"symbol":"PETR4","name":"Petrobras PN","kind":"STOCK"}' | get id)
check "ativo criado" "$([ -n "$INV" ] && echo true || echo false)"

curl -s -o /dev/null -X POST "$BASE/investments/$INV/movements" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"kind":"BUY","quantity":"10","price_per_unit":"35.00","amount":"350.00","fees":"1.50","traded_at":"2026-10-01"}'

R=$(curl -s "$BASE/reports/monthly?month=$MES" -H "$AUTH")
check "relatório foi gerado" "$([ -n "$(get income <<<"$R")" ] && echo true || echo false)"

echo
echo "2. compra de ativo NÃO é despesa (o bug)"
# Se a compra contasse como despesa, expense = 350,00 + 1,50 = 351,50.
check "despesa do mês é 0,00 (obtido $(get expense <<<"$R"))" \
    "$([ "$(get expense <<<"$R")" = "0.00" ] && echo true || echo false)"
check "receita do mês é 5000,00 (obtido $(get income <<<"$R"))" \
    "$([ "$(get income <<<"$R")" = "5000.00" ] && echo true || echo false)"
check "resultado do mês é 5000,00 (obtido $(get net <<<"$R"))" \
    "$([ "$(get net <<<"$R")" = "5000.00" ] && echo true || echo false)"

echo
echo "3. a compra SAI da carteira (o dinheiro foi aplicado, não guardado)"
SALDO=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "saldo é 4648,50 = 5000 − 350 − 1,50 de corretagem (obtido $SALDO)" \
    "$([ "$SALDO" = "4648.50" ] && echo true || echo false)"

echo
echo "4. posição do ativo"
POS=$(curl -s "$BASE/investments/$INV" -H "$AUTH")
# `Decimal` serializa com a escala do banco (NUMERIC(19,4)), então vem
# "351.5000" e não "351,50". Comparar string direto exigiria saber a escala
# exata, que muda com a operação — então a comparação é numérica.
num() { python3 -c "import sys; print(float('$1'))"; }
check "quantidade é 10 (obtido $(get quantity <<<"$POS"))" \
    "$([ "$(num "$(get quantity <<<"$POS")")" = "10.0" ] && echo true || echo false)"
check "custo é 351,50 com corretagem (obtido $(get cost_basis <<<"$POS"))" \
    "$([ "$(num "$(get cost_basis <<<"$POS")")" = "351.5" ] && echo true || echo false)"
check "preço médio é 35,15 (obtido $(get average_price <<<"$POS"))" \
    "$([ "$(num "$(get average_price <<<"$POS")")" = "35.15" ] && echo true || echo false)"

echo
echo "5. dividendo é receita (e não despesa)"
curl -s -o /dev/null -X POST "$BASE/investments/$INV/movements" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"kind":"DIVIDEND","quantity":"10","amount":"45.00","traded_at":"2026-10-05"}'
R2=$(curl -s "$BASE/reports/monthly?month=$MES" -H "$AUTH")
check "receita subiu para 5045,00 com o dividendo (obtido $(get income <<<"$R2"))" \
    "$([ "$(get income <<<"$R2")" = "5045.00" ] && echo true || echo false)"
check "despesa continua 0,00 (obtido $(get expense <<<"$R2"))" \
    "$([ "$(get expense <<<"$R2")" = "0.00" ] && echo true || echo false)"

echo
echo "6. venda a descoberto é recusada"
C=$(code -X POST "$BASE/investments/$INV/movements" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"kind":"SELL","quantity":"50","price_per_unit":"40.00","amount":"2000.00","traded_at":"2026-10-06"}')
check "vender 50 tendo 10 é recusado (recebido $C)" \
    "$([ "$C" = "422" ] && echo true || echo false)"

SALDO2=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "saldo não mudou com a venda recusada (obtido $SALDO2)" \
    "$([ "$SALDO2" = "4693.50" ] && echo true || echo false)"

echo
echo "7. venda válida credita a carteira e calcula ganho"
curl -s -o /dev/null -X POST "$BASE/investments/$INV/movements" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"kind":"SELL","quantity":"10","price_per_unit":"40.00","amount":"400.00","traded_at":"2026-10-07"}'

SALDO3=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
# 4693,50 + 400,00 = 5093,50
check "saldo recebeu a venda (obtido $SALDO3)" \
    "$([ "$SALDO3" = "5093.50" ] && echo true || echo false)"

POS2=$(curl -s "$BASE/investments/$INV" -H "$AUTH")
check "posição zerada após vender tudo (obtido $(get quantity <<<"$POS2"))" \
    "$([ "$(num "$(get quantity <<<"$POS2")")" = "0.0" ] && echo true || echo false)"
# ganho = 400,00 − 351,50 = 48,50; mais dividendo 45,00 = 93,50
check "ganho realizado inclui dividendo (obtido $(get realized_pnl <<<"$POS2"))" \
    "$([ "$(num "$(get realized_pnl <<<"$POS2")")" = "93.5" ] && echo true || echo false)"

echo
echo "8. resumo da carteira"
SUM=$(curl -s "$BASE/investments/summary" -H "$AUTH")
check "resumo traz o total (obtido $(get total_cost_basis <<<"$SUM"))" \
    "$([ -n "$(get total_cost_basis <<<"$SUM")" ] && echo true || echo false)"
check "resumo diz que não calcula ganho não realizado" \
    "$(echo "$SUM" | grep -q "não realizado" && echo true || echo false)"

echo
echo "9. ledger continua equilibrado (soma do extrato = saldo)"
# Antes este check lia `GET /lgpd/export`, o JSON do banco inteiro. A rota saiu
# do contrato (decisão do dono: exportar é planilha, não JSON), então a prova
# passou a ser a identidade que o usuário vê: a soma dos valores do extrato tem
# de bater com o saldo da carteira. Se uma transação ficasse desequilibrada, o
# saldo (derivado de USER_AVAILABLE) divergiria da soma das linhas.
CSV_INV=$(curl -s "$BASE/lgpd/export.csv" -H "$AUTH")
SOMA_CSV=$(python3 -c "
import sys, csv, io
from decimal import Decimal
linhas = list(csv.reader(io.StringIO(sys.stdin.read().lstrip('\ufeff')), delimiter=';'))
i_v = linhas[0].index('valor')
total = sum((Decimal(l[i_v].replace(',', '.')) for l in linhas[1:] if len(l) > i_v), Decimal('0'))
print(f'{total:.2f}')
" <<<"$CSV_INV")
SALDO_INV=$(curl -s "$BASE/wallet" -H "$AUTH" | python3 -c "
import sys, json
print(json.load(sys.stdin)['balance'])")
check "soma do extrato ($SOMA_CSV) bate com o saldo ($SALDO_INV)" \
    "$([ "$SOMA_CSV" = "$SALDO_INV" ] && echo true || echo false)"

echo
echo "10. lookup por IA (sem OPENROUTER_API_KEY — feature desligada)"
# O servidor deste teste sobe SEM a chave: os dois caminhos determinísticos
# são 401 (sem login) e 503 (recurso desligado). O caminho com IA de verdade
# vive nos testes de integração (tests/lookup.rs), que usam um mock — rodar
# IA real num teste custa dinheiro e é não-determinístico.
C=$(code -X POST "$BASE/investments/lookup" -H 'content-type: application/json' \
    -d '{"symbol":"PETR4","kind":"STOCK"}')
check "lookup sem token é 401 (recebido $C)" \
    "$([ "$C" = "401" ] && echo true || echo false)"
# Símbolo inválido devolve 422 ANTES de checar a feature: validação de
# formato é barata e roda sempre; cada chamada evitada é token não gasto.
C=$(code -X POST "$BASE/investments/lookup" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"symbol":"max renda","kind":"STOCK"}')
check "lookup com símbolo inválido é 422 (valida antes da IA, recebido $C)" \
    "$([ "$C" = "422" ] && echo true || echo false)"
# Símbolo válido sem chave é 503 "unavailable" — o servidor diz a verdade
# (recurso desligado), não finge "ativo não existe" nem erra 500.
LK=$(curl -s -X POST "$BASE/investments/lookup" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"symbol":"PETR4","kind":"STOCK"}')
check "lookup válido sem chave é 503 unavailable (obtido $(get error <<<"$LK"))" \
    "$([ "$(get error <<<"$LK")" = "unavailable" ] && echo true || echo false)"

echo
echo "11. sugestão de preço (brapi/Yahoo mortos — o caminho determinístico)"
# As fontes estão mortas (127.0.0.1:1). O contrato da tela: sugestão nunca
# derruba a tela — provedor fora devolve 200 vazio, e o usuário digita o
# preço à mão. O caminho com fonte viva vive em tests/cotacao.rs (mock).
C=$(code -X POST "$BASE/investments/quote" -H 'content-type: application/json' \
    -d '{"symbol":"PETR4","kind":"STOCK"}')
check "quote sem token é 401 (recebido $C)" \
    "$([ "$C" = "401" ] && echo true || echo false)"
C=$(code -X POST "$BASE/investments/quote" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"symbol":"max renda","kind":"STOCK"}')
check "quote com símbolo inválido é 422 antes da rede (recebido $C)" \
    "$([ "$C" = "422" ] && echo true || echo false)"
Q=$(curl -s -X POST "$BASE/investments/quote" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"symbol":"PETR4","kind":"STOCK"}')
check "quote ação com fonte morta é 200 com price nulo (obtido $(get price <<<"$Q"))" \
    "$([ "$(get price <<<"$Q")" = "None" ] && echo true || echo false)"
Q=$(curl -s -X POST "$BASE/investments/quote" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"symbol":"Tesouro Selic 2029","kind":"BOND"}')
check "quote renda fixa é 200 com price nulo sem tocar fonte (obtido $(get price <<<"$Q"))" \
    "$([ "$(get price <<<"$Q")" = "None" ] && echo true || echo false)"

echo
echo "12. data no padrão que o cliente digita (dd/mm/aaaa)"
# O cliente digita 14/10/2026, não 2026-10-14 — e o app não deve traduzir:
# o backend já normaliza pt-BR no parse_decimal (vírgula) e o parse_data
# segue o mesmo racional. Mensagem de erro amigável, sem jargão de formato:
# formato nenhum é exigido do usuário, logo não há formato a sugerir.
C=$(code -X POST "$BASE/investments" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"symbol":"DATA11","kind":"FII","name":"Data Teste","next_dividend_at":"14/10/2026"}')
check "criar ativo com next_dividend dd/mm/aaaa é 201 (recebido $C)" \
    "$([ "$C" = "201" ] && echo true || echo false)"
E=$(curl -s -X POST "$BASE/investments" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"symbol":"DATA12","kind":"FII","name":"Data Teste","next_dividend_at":"31/02/2026"}')
MSG=$(get message <<<"$E")
check "data inexistente é 422 com mensagem amigável (obtido: $MSG)" \
    "$([ "$MSG" = "dados inválidos: data inválida" ] && echo true || echo false)"

echo
echo "resultado: $pass passaram, $fail falharam"
[ "$fail" -eq 0 ]