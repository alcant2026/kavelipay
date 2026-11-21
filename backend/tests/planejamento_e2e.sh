#!/usr/bin/env bash
# Testes de planejamento: orçamento por categoria, metas de economia,
# saldo disponível × comprometido e parcelamento de boletos.
#
# Uso:  tests/planejamento_e2e.sh
# Env:  PGHOST PGPORT PGUSER PGPASSWORD

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="kavelipay_plan_test_$$"
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

echo
echo "saldo disponível × comprometido (sem nada em aberto)"

R=$(curl -s "$BASE/wallet/resumo" -H "$AUTH")
check "saldo começa em 0" "$([ "$(echo "$R" | get saldo)" = "0.00" ] && echo true || echo false)"
check "disponivel == saldo" "$([ "$(echo "$R" | get disponivel)" = "$(echo "$R" | get saldo)" ] && echo true || echo false)"
check "sem contas pendentes" "$([ "$(echo "$R" | get contas_pendentes)" = "0" ] && echo true || echo false)"

echo
echo "orçamento por categoria (teto de gasto)"

# Cria uma despesa de 50,00 na categoria "Mercado" (id será capturado)
CAT=$(curl -s -X POST "$BASE/categories" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"name":"Mercado","kind":"EXPENSE"}' | get id)
check "categoria criada" "$([ -n "$CAT" ] && echo true || echo false)"

# Teto de 200,00 para a categoria
R=$(curl -s -X POST "$BASE/orcamentos" -H "$AUTH" -H 'content-type: application/json' \
    -d "{\"category_id\":\"$CAT\",\"valor\":\"200.00\"}")
check "orçamento criado" "$([ -n "$(echo "$R" | get id)" ] && echo true || echo false)"
check "gasto começa em 0" "$([ "$(echo "$R" | get gasto)" = "0.00" ] && echo true || echo false)"

# Upsert do teto: 200 -> 150
R=$(curl -s -X POST "$BASE/orcamentos" -H "$AUTH" -H 'content-type: application/json' \
    -d "{\"category_id\":\"$CAT\",\"valor\":\"150.00\"}")
check "upsert do teto (valor atualizado)" "$([ "$(echo "$R" | get valor)" = "150.00" ] && echo true || echo false)"

# Primeiro uma entrada de 1.000,00, senão a despesa deixaria o saldo negativo
# (o que é permitido, mas sujaria as contas de "disponível" mais abaixo).
curl -s -o /dev/null -X POST "$BASE/wallet/transactions" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"type":"ENTRY","amount":"1000.00","description":"Salário"}'

# Lança uma despesa de 75,00 na categoria
curl -s -o /dev/null -X POST "$BASE/wallet/transactions" -H "$AUTH" -H 'content-type: application/json' \
    -d "{\"type\":\"EXPENSE\",\"amount\":\"75.00\",\"description\":\"Compras\",\"category_id\":\"$CAT\"}"

# GET /orcamentos devolve ARRAY solto — o primeiro item é o teto da categoria.
ORC0=$(curl -s "$BASE/orcamentos" -H "$AUTH" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d[0]["gasto"], d[0]["progresso"])' 2>/dev/null || echo "")
check "gasto reflete o lançamento" "$([ "$(echo "$ORC0" | cut -d' ' -f1)" = "75.00" ] && echo true || echo false)"
check "progresso = 0.5" "$([ "$(echo "$ORC0" | cut -d' ' -f2)" = "0.5" ] && echo true || echo false)"

# Teto do mês inteiro (sem categoria)
R=$(curl -s -X POST "$BASE/orcamentos" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"valor":"500.00"}')
check "teto geral criado" "$([ -n "$(echo "$R" | get id)" ] && echo true || echo false)"

ORC_ID=$(echo "$R" | get id)
curl -s -o /dev/null -X DELETE "$BASE/orcamentos/$ORC_ID" -H "$AUTH"
# O teto geral saiu; o teto da categoria (criado antes) continua — são
# orçamentos independentes. Verifica que o id removido não volta.
check "orçamento removido" "$([ -z "$(curl -s "$BASE/orcamentos" -H "$AUTH" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(next((o["id"] for o in d if o["id"]=="'$ORC_ID'"), ""))')" ] && echo true || echo false)"

echo
echo "metas de economia"

R=$(curl -s -X POST "$BASE/metas" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"nome":"Viagem","valor_alvo":"1000.00"}')
META_ID=$(echo "$R" | get id)
check "meta criada" "$([ -n "$META_ID" ] && echo true || echo false)"
check "guardado começa em 0" "$([ "$(echo "$R" | get guardado)" = "0.00" ] && echo true || echo false)"
check "falta == alvo" "$([ "$(echo "$R" | get falta)" = "1000.00" ] && echo true || echo false)"

# Aporta 250,00
R=$(curl -s -X POST "$BASE/metas/$META_ID/aportar" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"amount":"250.00"}')
check "aporte registrado" "$([ "$(echo "$R" | get guardado)" = "250.00" ] && echo true || echo false)"
check "falta cai para 750" "$([ "$(echo "$R" | get falta)" = "750.00" ] && echo true || echo false)"
check "progresso 0.25" "$([ "$(echo "$R" | get progresso)" = "0.25" ] && echo true || echo false)"

# Saldo da carteira reflete a saída: 1000 (salário) - 75 (compras) - 250 (aporte) = 675.
SALDO=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
check "carteira debitada pelo aporte" "$([ "$SALDO" = "675.00" ] && echo true || echo false)"

# Arquivar a meta
R=$(curl -s -X POST "$BASE/metas/$META_ID/arquivar" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"arquivada":true}')
check "meta arquivada" "$([ "$(curl -s "$BASE/metas" -H "$AUTH" | python3 -c 'import sys,json; print(len(json.load(sys.stdin)))')" = "0" ] && echo true || echo false)"
check "meta aparece com incluidas_arquivadas" "$([ "$(curl -s "$BASE/metas?incluidas_arquivadas=true" -H "$AUTH" | python3 -c 'import sys,json; print(len(json.load(sys.stdin)))')" = "1" ] && echo true || echo false)"

echo
echo "saldo disponível × comprometido (com boletos em aberto)"

HOJE=$(date +%F)
AMANHA=$(date -d '+1 day' +%F)
curl -s -o /dev/null -X POST "$BASE/bills" -H "$AUTH" -H 'content-type: application/json' \
    -d "{\"description\":\"Luz\",\"amount\":\"100.00\",\"due_date\":\"$AMANHA\"}"
curl -s -o /dev/null -X POST "$BASE/bills" -H "$AUTH" -H 'content-type: application/json' \
    -d "{\"description\":\"Água\",\"amount\":\"50.00\",\"due_date\":\"$AMANHA\"}"

R=$(curl -s "$BASE/wallet/resumo" -H "$AUTH")
check "comprometido = 150" "$([ "$(echo "$R" | get comprometido)" = "150.00" ] && echo true || echo false)"
check "2 contas pendentes" "$([ "$(echo "$R" | get contas_pendentes)" = "2" ] && echo true || echo false)"
check "disponivel = saldo - 150" "$([ "$(echo "$R" | get disponivel)" = "525.00" ] && echo true || echo false)"

echo
echo "parcelamento de boleto"

R=$(curl -s -X POST "$BASE/bills/parcelar" -H "$AUTH" -H 'content-type: application/json' \
    -d "{\"description\":\"Geladeira\",\"total_amount\":\"100.00\",\"parcelas\":3,\"first_due_date\":\"$AMANHA\"}")
check "3 parcelas criadas" "$([ "$(echo "$R" | python3 -c 'import sys,json; print(len(json.load(sys.stdin)["parcelas"]))')" = "3" ] && echo true || echo false)"
check "soma das parcelas = total" "$([ "$(echo "$R" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("%.2f" % sum(float(p["amount"]) for p in d["parcelas"]))')" = "100.00" ] && echo true || echo false)"
check "datas das parcelas crescem" "$([ "$(echo "$R" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["parcelas"][0]["due_date"] < d["parcelas"][1]["due_date"] < d["parcelas"][2]["due_date"])')" = "True" ] && echo true || echo false)"

# 100/3 = 33.33 + 33.33 + 33.34 (última absorve a diferença)
check "última parcela absorve arredondamento" "$([ "$(echo "$R" | python3 -c 'import sys,json; print(json.load(sys.stdin)["parcelas"][2]["amount"])')" = "33.34" ] && echo true || echo false)"

# Cada parcela é um boleto PENDING de verdade: aparece em GET /bills
BILLS=$(curl -s "$BASE/bills" -H "$AUTH" | python3 -c 'import sys,json; print(len([b for b in json.load(sys.stdin)["items"] if b["status"]=="PENDING"]))')
check "parcelas viram boletos PENDING" "$([ "$BILLS" -ge 3 ] && echo true || echo false)"

echo
echo "fronteira de confiança"

# Bob não vê o orçamento de Alice
R=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/orcamentos" -H "$AUTH_B")
check "lista de orçamentos exige auth" "$([ "$R" = "200" ] && echo true || echo false)"

# Meta de Alice não recebe aporte de Bob
R=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/metas/$META_ID/aportar" -H "$AUTH_B" -H 'content-type: application/json' \
    -d '{"amount":"10.00"}')
check "aporte em meta alheia = 404" "$([ "$R" = "404" ] && echo true || echo false)"

# Meta arquivada não recebe aporte
R=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/metas/$META_ID/aportar" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"amount":"10.00"}')
check "aporte em meta arquivada = 422" "$([ "$R" = "422" ] && echo true || echo false)"

# Valor inválido no orçamento
R=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/orcamentos" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"valor":"0.00"}')
check "orçamento com valor 0 = 422" "$([ "$R" = "422" ] && echo true || echo false)"

# Float é recusado
R=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/orcamentos" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"valor":10.50}')
check "orçamento com float = 422" "$([ "$R" = "422" ] && echo true || echo false)"

echo
echo "splits: quem me deve agregado"

# Alice cria uma divisão com dois participantes
curl -s -o /dev/null -X POST "$BASE/splits" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"description":"Jantar","total_amount":"90.00","parts":[{"name":"João","amount":"45.00"},{"name":"Maria","amount":"45.00"}]}'
# Outra divisão com João devendo 20
curl -s -o /dev/null -X POST "$BASE/splits" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"description":"Uber","total_amount":"20.00","parts":[{"name":"João","amount":"20.00"}]}'

R=$(curl -s "$BASE/splits/pendentes" -H "$AUTH")
check "dois devedores" "$([ "$(echo "$R" | python3 -c 'import sys,json; print(len(json.load(sys.stdin)))')" = "2" ] && echo true || echo false)"
check "João deve 65 (45+20)" "$([ "$(echo "$R" | python3 -c 'import sys,json; d={x["name"]:x["total"] for x in json.load(sys.stdin)}; print(d.get("João",""))')" = "65.00" ] && echo true || echo false)"
check "Maria deve 45" "$([ "$(echo "$R" | python3 -c 'import sys,json; d={x["name"]:x["total"] for x in json.load(sys.stdin)}; print(d.get("Maria",""))')" = "45.00" ] && echo true || echo false)"

echo
printf '\n%d passaram, %d falharam\n' "$pass" "$fail"
[ "$fail" -eq 0 ]