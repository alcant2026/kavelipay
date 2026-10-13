#!/usr/bin/env bash
# Testes das features que respondem "e o que mais a gente mostra?": saldo
# demonstrativo explícito, derivação do saldo, categorias, relatórios, CSV,
# recorrência e LGPD.
#
# Uso:  tests/transparency_e2e.sh
# Env:   PGHOST PGPORT PGUSER PGPASSWORD

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="kavelipay_transparency_test_$$"
BASE="http://127.0.0.1:3321"
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
booleano() { python3 -c "import sys; print('true' if sys.argv[1] in ('true','True') else 'false')" "$1"; }
contem() { # contem <texto> <agulha>
    case "$1" in *"$2"*) echo true;; *) echo false;; esac
}

echo "criando banco: $DB"
"${PSQL[@]}" -d postgres -c "CREATE DATABASE \"$DB\";"
[ -x "$BIN" ] || { echo "binário ausente: $BIN"; exit 1; }

DATABASE_URL="postgres://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${DB}" \
JWT_SECRET="$SECRET" BIND_ADDR="127.0.0.1:3321" RUST_LOG=error "$BIN" &
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

MES=$(date +%Y-%m)

echo
echo "1. saldo demonstrativo é explícito na API"

W=$(curl -s "$BASE/wallet" -H "$AUTH")
DEMO=$(get is_demonstrative <<<"$W")
check "GET /wallet traz is_demonstrative (valor bruto: $DEMO)" \
    "$(booleano "$DEMO")"

FLAG_BD=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT is_demonstrative FROM wallets w JOIN users u ON u.id=w.user_id
      WHERE u.email='alice@teste.com'")
check "coluna existe no banco e é TRUE por padrão ($FLAG_BD)" \
    "$([ "$FLAG_BD" = "t" ] && echo true || echo false)"

# A regra da spec (seção 4): nova carteira é demonstrativa POR OMISSÃO.
NOVA_DEMO=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT is_demonstrative FROM wallets w JOIN users u ON u.id=w.user_id
      WHERE u.email='bob@teste.com'")
check "carteira nova nasce demonstrativa ($NOVA_DEMO)" \
    "$([ "$NOVA_DEMO" = "t" ] && echo true || echo false)"

echo
echo "2. a derivação do saldo bate com o saldo"

curl -s -o /dev/null -X POST "$BASE/wallet/transactions" -H "$AUTH" \
    -H 'content-type: application/json' -d '{"type":"ENTRY","amount":"1000.00","description":"Salário"}'
curl -s -o /dev/null -X POST "$BASE/wallet/transactions" -H "$AUTH" \
    -H 'content-type: application/json' -d '{"type":"EXPENSE","amount":"300.00","description":"Mercado"}'
curl -s -o /dev/null -X POST "$BASE/wallet/transactions" -H "$AUTH" \
    -H 'content-type: application/json' -d '{"type":"ENTRY","amount":"200.50","description":"Freela"}'

D=$(curl -s "$BASE/wallet/derivation" -H "$AUTH")
BAL=$(curl -s "$BASE/wallet" -H "$AUTH" | get balance)
OPEN=$(get opening_balance <<<"$D")

check "opening_balance = saldo da carteira (derivado $OPEN, /wallet $BAL)" \
    "$([ "$OPEN" = "$BAL" ] && echo true || echo false)"
check "derivação declara que é demonstrativa" \
    "$(booleano "$(get is_demonstrative <<<"$D")")"
check "derivação explica a fórmula" \
    "$(contem "$(get derivation <<<"$D")" "SUM")"

# A prova real: refazer a conta à mão. Soma dos deltas tem que fechar.
SOMA_DELTAS=$(printf '%s' "$D" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(round(sum(float(i['delta']) for i in d['items']), 2))")
check "soma dos deltas da tela = saldo ($SOMA_DELTAS vs $BAL)" \
    "$(python3 -c "print('true' if abs(float('$SOMA_DELTAS') - float('$BAL')) < 0.005 else 'false')")"

# O running_balance do último item tem de ser o saldo.
ULTIMO=$(printf '%s' "$D" | jget "['items'][-1]['running_balance']")
check "running_balance final = saldo da carteira ($ULTIMO vs $BAL)" \
    "$([ "$ULTIMO" = "$BAL" ] && echo true || echo false)"

ITENS=$(printf '%s' "$D" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['items']))")
check "derivação traz os 3 lançamentos ($ITENS)" \
    "$([ "$ITENS" = "3" ] && echo true || echo false)"

ZERO_DELTA=$(printf '%s' "$D" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(sum(1 for i in d['items'] if float(i['delta']) == 0))")
check "nenhum lançamento aparece com delta 0.00" \
    "$([ "$ZERO_DELTA" = "0" ] && echo true || echo false)"

echo
echo "3. categorias foram semeadas"

CAT_ALUGUEL=$(curl -s "$BASE/categories?kind=EXPENSE" -H "$AUTH" | \
    python3 -c "
import sys, json
for c in json.load(sys.stdin):
    if c['name'] == 'Aluguel': print(c['id'])")

QTD_CAT=$(curl -s "$BASE/categories" -H "$AUTH" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))")
check "categorias padrão foram semeadas ($QTD_CAT, esperado 14)" \
    "$([ "$QTD_CAT" = "14" ] && echo true || echo false)"

CAT_MERCADO=$(curl -s "$BASE/categories?kind=EXPENSE" -H "$AUTH" | \
    python3 -c "
import sys, json
for c in json.load(sys.stdin):
    if c['name'] == 'Alimentação': print(c['id'])")

CAT_SALARIO=$(curl -s "$BASE/categories?kind=INCOME" -H "$AUTH" | \
    python3 -c "
import sys, json
for c in json.load(sys.stdin):
    if c['name'] == 'Salário': print(c['id'])")

echo
echo "4. classificar lançamentos"

# A resposta tem `balance`, não `amount` — a transação não repete o valor, ela
# devolve o saldo recalculado. (O `amount` só existe no extrato e no relatório.)
BAL_PAD=$(curl -s -X POST "$BASE/wallet/transactions" -H "$AUTH" -H 'content-type: application/json' \
    -d "{\"type\":\"EXPENSE\",\"amount\":\"50.00\",\"description\":\"Padaria\",\"category_id\":\"$CAT_MERCADO\"}" | get balance)
check "despesa classificada em Alimentação é aceita (saldo $BAL_PAD)" \
    "$([ -n "$BAL_PAD" ] && echo true || echo false)"

# Classifica retroativamente a despesa de 300: o app sempre precisa disso
# (registra o gasto agora, classifica depois). Sem esse endpoint, o lançamento
# ficaria eternamente "(sem categoria)" e dominaria o relatório.
TXN_MERCADO=$(curl -s "$BASE/wallet/transactions" -H "$AUTH" | python3 -c "
import sys, json
for i in json.load(sys.stdin)['items']:
    if i['description'] == 'Mercado': print(i['transaction_id'])")
C_CLASSIFY=$(code -X PATCH "$BASE/wallet/transactions/$TXN_MERCADO/category" -H "$AUTH" \
    -H 'content-type: application/json' -d "{\"category_id\":\"$CAT_MERCADO\"}")
check "classificar transação já existente (recebido $C_CLASSIFY)" \
    "$([ "$C_CLASSIFY" = "200" ] && echo true || echo false)"

C_DESCLASS=$(code -X PATCH "$BASE/wallet/transactions/$TXN_MERCADO/category" -H "$AUTH" \
    -H 'content-type: application/json' -d "{\"category_id\":\"$CAT_SALARIO\"}")
check "reclassificar para categoria incompatível é 422 (recebido $C_DESCLASS)" \
    "$([ "$C_DESCLASS" = "422" ] && echo true || echo false)"

curl -s -o /dev/null -X POST "$BASE/wallet/transactions" -H "$AUTH" -H 'content-type: application/json' \
    -d "{\"type\":\"ENTRY\",\"amount\":\"7000.00\",\"description\":\"Salário do mês\",\"category_id\":\"$CAT_SALARIO\"}"

# Regra de negócio: categoria de despesa não classifica receita.
C_ERRADA=$(code -X POST "$BASE/wallet/transactions" -H "$AUTH" -H 'content-type: application/json' \
    -d "{\"type\":\"ENTRY\",\"amount\":\"10.00\",\"category_id\":\"$CAT_MERCADO\"}")
check "categoria de despesa em receita é 422 (recebido $C_ERRADA)" \
    "$([ "$C_ERRADA" = "422" ] && echo true || echo false)"

# Categoria de outra pessoa: não pode escrever o nome dela no histórico.
C_ALHEIA=$(code -X POST "$BASE/wallet/transactions" -H "$AUTH" -H 'content-type: application/json' \
    -d "{\"type\":\"EXPENSE\",\"amount\":\"10.00\",\"category_id\":\"$CAT_SALARIO\"}")
check "categoria de receita em despesa é 422 (recebido $C_ALHEIA)" \
    "$([ "$C_ALHEIA" = "422" ] && echo true || echo false)"

C_BOB_CAT=$(curl -s "$BASE/categories" -H "$AUTH_B" | jget "[0]['id']")
C_ROUBO=$(code -X POST "$BASE/wallet/transactions" -H "$AUTH" -H 'content-type: application/json' \
    -d "{\"type\":\"EXPENSE\",\"amount\":\"10.00\",\"category_id\":\"$C_BOB_CAT\"}")
check "categoria de outro usuário é 422 (recebido $C_ROUBO)" \
    "$([ "$C_ROUBO" = "422" ] && echo true || echo false)"

NOVA_CAT=$(curl -s -X POST "$BASE/categories" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"name":"Cafeteria","kind":"EXPENSE","color":"#8B5A2B"}' | get name)
check "cria categoria própria ($NOVA_CAT)" \
    "$([ "$NOVA_CAT" = "Cafeteria" ] && echo true || echo false)"

C_COR=$(code -X POST "$BASE/categories" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"name":"Ruim","kind":"EXPENSE","color":"vermelho"}')
check "cor fora de #RRGGBB é 422 (recebido $C_COR)" \
    "$([ "$C_COR" = "422" ] && echo true || echo false)"

echo
echo "5. relatório mensal por categoria"

R=$(curl -s "$BASE/reports/monthly?month=$MES" -H "$AUTH")
check "relatório declara saldo demonstrativo" \
    "$(booleano "$(get is_demonstrative <<<"$R")")"

TOTAL_ENTRADA=$(python3 -c "print(round(float('$(get income <<<"$R")'),2))")
TOTAL_SAIDA=$(python3 -c "print(round(float('$(get expense <<<"$R")'),2))")
check "receita = 7000.00 + 200.50 + 1000.00 = 8200.50 (obtido $TOTAL_ENTRADA)" \
    "$([ "$TOTAL_ENTRADA" = "8200.5" ] && echo true || echo false)"
check "despesa = 300.00 + 50.00 = 350.00 (obtido $TOTAL_SAIDA)" \
    "$([ "$TOTAL_SAIDA" = "350.0" ] && echo true || echo false)"

NET=$(python3 -c "print(round(float('$(get net <<<"$R")'),2))")
check "net = receitas - despesas = 7850.50 (obtido $NET)" \
    "$([ "$NET" = "7850.5" ] && echo true || echo false)"

TOP=$(printf '%s' "$R" | jget "['by_expense_category'][0]['name']")
check "maior despesa é Alimentação, já classificada retroativa (obtido $TOP)" \
    "$([ "$TOP" = "Alimentação" ] && echo true || echo false)"

# A armadilha: um lançamento tem DUAS entradas no ledger. Se o relatório não
# filtra pela conta da carteira, a despesa aparece dobrada.
SUM_SHARES=$(printf '%s' "$R" | python3 -c "
import sys, json
r = json.load(sys.stdin)
print(round(sum(f['share'] for f in r['by_expense_category']), 4))")
check "soma das fatias = total de despesas (não dobra; soma=$SUM_SHARES)" \
    "$([ "$SUM_SHARES" = "1.0" ] && echo true || echo false)"

R_BOB=$(curl -s "$BASE/reports/monthly" -H "$AUTH_B" | get expense)
check "relatório do Bob não vê gastos da Alice (obtido $R_BOB)" \
    "$([ "$R_BOB" = "0.00" ] && echo true || echo false)"

echo
echo "6. extrato mostra a categoria"

CAT_EXTRATO=$(curl -s "$BASE/wallet/transactions" -H "$AUTH" | \
    python3 -c "
import sys, json
for i in json.load(sys.stdin)['items']:
    if i['description'] == 'Padaria': print(i.get('category'))")
check "extrato devolve o nome da categoria (obtido $CAT_EXTRATO)" \
    "$([ "$CAT_EXTRATO" = "Alimentação" ] && echo true || echo false)"

echo
echo "7. export CSV"

CSV=$(curl -s "$BASE/lgpd/export.csv" -H "$AUTH")

check "CSV tem cabeçalho" \
    "$(contem "$CSV" "data;transacao_id;tipo;categoria;descricao;conta;debito;credito")"
check "CSV usa ponto e vírgula (Excel pt-BR)" \
    "$(contem "$CSV" ";")"
check "CSV usa vírgula como decimal (ex.: 50,00)" \
    "$(contem "$CSV" "50,00")"
check "CSV não usa ponto como decimal em NENHUMA coluna" \
    "$(python3 -c "
import sys, csv, io
linhas = list(csv.reader(io.StringIO(open('/dev/stdin').read()), delimiter=';'))
cabec = linhas[0]
i_d, i_c = cabec.index('debito'), cabec.index('credito')
problemas = []
for l in linhas[1:]:
    if len(l) <= max(i_d, i_c):
        continue
    for idx, nome in ((i_d, 'debito'), (i_c, 'credito')):
        v = l[idx]
        if '.' in v:
            problemas.append(f'{nome}={v}')
        if not v or v == '0':
            problemas.append(f'{nome} vazio/zero cru')
print('false' if problemas else 'true')
" <<<"$CSV")"

QTD_LINHAS=$(printf '%s\n' "$CSV" | wc -l)
check "CSV tem linhas de dados + cabeçalho ($QTD_LINHAS)" \
    "$([ "$QTD_LINHAS" -ge 6 ] && echo true || echo false)"

echo
echo "8. LGPD: export de dados"

EXPORT=$(curl -s "$BASE/lgpd/export" -H "$AUTH")
QTD_SECTIONS=$(printf '%s' "$EXPORT" | python3 -c "
import sys, json
print(len(json.load(sys.stdin)['data']))")
check "export traz as 6 seções de dados (obtido $QTD_SECTIONS)" \
    "$([ "$QTD_SECTIONS" = "6" ] && echo true || echo false)"
EMAIL_EXP=$(printf '%s' "$EXPORT" | jget "['data']['perfil']['email']")
check "export contém o e-mail do titular ($EMAIL_EXP)" \
    "$([ "$EMAIL_EXP" = "alice@teste.com" ] && echo true || echo false)"
TXNS_EXPORT=$(printf '%s' "$EXPORT" | python3 -c \
    "import sys,json; print(len(json.load(sys.stdin)['data']['transacoes']))")
check "export inclui o ledger completo ($TXNS_EXPORT transações)" \
    "$([ "$TXNS_EXPORT" -ge 5 ] && echo true || echo false)"
ENTRIES=$(printf '%s' "$EXPORT" | python3 -c \
    "import sys,json; print(len(json.load(sys.stdin)['data']['transacoes'][0]['lancamentos']))")
check "cada transação vem com seus 2 lançamentos (obtido $ENTRIES)" \
    "$([ "$ENTRIES" = "2" ] && echo true || echo false)"

echo
echo "9. LGPD: eliminação de conta exige senha e confirmação"

C_SEM_CONFIRM=$(code -X DELETE "$BASE/lgpd/account" -H "$AUTH" \
    -H 'content-type: application/json' -d '{"password":"senha-forte-123","confirm":false}')
check "sem confirm é 422 (recebido $C_SEM_CONFIRM)" \
    "$([ "$C_SEM_CONFIRM" = "422" ] && echo true || echo false)"

C_SEM_SENHA=$(code -X DELETE "$BASE/lgpd/account" -H "$AUTH" \
    -H 'content-type: application/json' -d '{"password":"errada","confirm":true}')
check "senha errada é 401 (recebido $C_SEM_SENHA)" \
    "$([ "$C_SEM_SENHA" = "401" ] && echo true || echo false)"

# Este endpoint NÃO tem id de usuário: o `user_id` vem do token. Bob chamando
# ele apaga a conta DO BOB, nunca a da Alice. É a propriedade a checar — não um
# "não pode apagar o outro", que não chega a ser tentado.
C_BOB=$(code -X DELETE "$BASE/lgpd/account" -H "$AUTH_B" \
    -H 'content-type: application/json' -d '{"password":"senha-forte-123","confirm":true,"reason":"não uso"}')
check "Bob elimina a própria conta (recebido $C_BOB)" \
    "$([ "$C_BOB" = "200" ] && echo true || echo false)"

C_LOGIN_BOB=$(code -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d '{"email":"bob@teste.com","password":"senha-forte-123"}')
check "login da conta eliminada é 401 (recebido $C_LOGIN_BOB)" \
    "$([ "$C_LOGIN_BOB" = "401" ] && echo true || echo false)"

# E a conta da Alice tem que continuar intacta.
C_ALICE_VIVA=$(code -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d '{"email":"alice@teste.com","password":"senha-forte-123"}')
check "conta da Alice intacta após Bob eliminar a dele (recebido $C_ALICE_VIVA)" \
    "$([ "$C_ALICE_VIVA" = "200" ] && echo true || echo false)"

C_ALICE_TOKEN=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/wallet" -H "$AUTH")
check "token da Alice continua funcionando (recebido $C_ALICE_TOKEN)" \
    "$([ "$C_ALICE_TOKEN" = "200" ] && echo true || echo false)"

# O e-mail original tem que ter sumido.
ANON=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COUNT(*) FROM users WHERE email LIKE 'anonimo+%@excluido.invalid'")
check "e-mail foi anonimizado ($ANON conta(s))" \
    "$([ "$ANON" = "1" ] && echo true || echo false)"
check "e-mail original não existe mais" \
    "$([ "$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c "SELECT COUNT(*) FROM users WHERE email='bob@teste.com'")" = "0" ] && echo true || echo false)"
check "e-mail da Alice NÃO foi tocado" \
    "$([ "$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c "SELECT COUNT(*) FROM users WHERE email='alice@teste.com'")" = "1" ] && echo true || echo false)"

# E o ledger continua equilibrado: a anonimização NÃO pode quebrar contabilidade.
DESBAL=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COUNT(*) FROM (SELECT transaction_id FROM ledger_entries
      GROUP BY transaction_id HAVING SUM(debit) <> SUM(credit)) x")
check "ledger continua equilibrado após anonimização ($DESBAL esperado 0)" \
    "$([ "$DESBAL" = "0" ] && echo true || echo false)"

TXNS_PRESERVADAS=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COUNT(*) FROM ledger_transactions")
check "transações foram preservadas ($TXNS_PRESERVADAS >= 5)" \
    "$([ "$TXNS_PRESERVADAS" -ge 5 ] 2>/dev/null && echo true || echo false)"

echo
echo "10. contas recorrentes"

PROX_MES=$(date -d "+1 month" +%F)
REC=$(curl -s -X POST "$BASE/bills" -H "$AUTH" -H 'content-type: application/json' \
    -d "{\"description\":\"Aluguel mensal\",\"amount\":\"1200.00\",\"due_date\":\"$PROX_MES\",\"recurrence\":\"MONTHLY\"}" | get id)
check "cria boleto recorrente" \
    "$([ -n "$REC" ] && echo true || echo false)"

G1=$(curl -s -X POST "$BASE/bills/generate-recurring" -H "$AUTH" \
    -H 'content-type: application/json' -d '{"months_ahead":3}')
N1=$(get created <<<"$G1")
check "gera 3 ocorrências ($N1)" \
    "$([ "$N1" = "3" ] && echo true || echo false)"

G2=$(curl -s -X POST "$BASE/bills/generate-recurring" -H "$AUTH" \
    -H 'content-type: application/json' -d '{"months_ahead":3}')
N2=$(get created <<<"$G2")
check "gerar de novo cria 0 (idempotente, obtido $N2)" \
    "$([ "$N2" = "0" ] && echo true || echo false)"

QTD_REC=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COUNT(*) FROM bills WHERE recurrence_key IS NOT NULL")
check "3 ocorrências geradas, sem duplicar ($QTD_REC)" \
    "$([ "$QTD_REC" = "3" ] && echo true || echo false)"

C_MESES=$(code -X POST "$BASE/bills/generate-recurring" -H "$AUTH" \
    -H 'content-type: application/json' -d '{"months_ahead":999}')
check "months_ahead gigante é limitado, não desbota ($C_MESES)" \
    "$([ "$C_MESES" = "201" ] && echo true || echo false)"

echo
echo "resultado: $pass passaram, $fail falharam"
[ "$fail" -eq 0 ]