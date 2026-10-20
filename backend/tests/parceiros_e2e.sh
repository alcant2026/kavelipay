#!/usr/bin/env bash
# Vitrine de parceiros: leitura paginada para o usuário, cadastro só para admin.
#
# O que este script protege:
#  - 401 sem token (a vitrine não é URL pública de propaganda);
#  - lista vem com os MAIS NOVOS primeiro (regra de produto) e só ativas;
#  - paginação de 10 em 10 por padrão, sem repetir nem pular item na emenda;
#  - TODO link é https (a WebView do app carrega isso; http ou esquema
#    estranho viraria vetor) — inclusive imagem, quando existe;
#  - NÃO existe campo de preço — a ausência é decisão de produto (ver
#    migrations/0011), não esquecimento, e um dia alguém vai querer "só
#    adicionar o preço"; este check é o que faz a conversa acontecer;
#  - POST /parceiros continua não existindo: quem cadastra é /admin com
#    X-Admin-Token, e sem ADMIN_TOKEN a rota responde 503 (desligada);
#  - a lista é a mesma para dois usuários diferentes (é conteúdo, não dado
#    pessoal — nada de `user_id` aqui).
#
# Uso:  tests/parceiros_e2e.sh
# Env:   PGHOST PGPORT PGUSER PGPASSWORD

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="kavelipay_parceiros_test_$$"
BASE="http://127.0.0.1:3319"
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
itens() { python3 -c "
import sys, json
try:
    v = json.load(sys.stdin)
    print(len(v.get('itens') or []))
except Exception:
    print('')"; }
total() { python3 -c "
import sys, json
try:
    print(json.load(sys.stdin).get('total', ''))
except Exception:
    print('')"; }
campo() { python3 -c "
import sys, json
try:
    v = json.load(sys.stdin)
    for p in '$1'.split('.'):
        v = v[int(p)] if isinstance(v, list) else v[p]
    print('' if v is None else v)
except Exception:
    print('')"; }
# true se TODOS os itens da página têm a chave começando por https://.
todos_prefixo() { python3 -c "
import sys, json
try:
    v = json.load(sys.stdin).get('itens') or []
    print('true' if v and all(str(i.get('$1') or '').startswith('https://') for i in v) else 'false')
except Exception:
    print('false')"; }
ids() { python3 -c "
import sys, json
print(' '.join(i['id'] for i in (json.load(sys.stdin).get('itens') or [])))"; }

echo "criando banco: $DB"
"${PSQL[@]}" -d postgres -c "CREATE DATABASE \"$DB\";"
[ -x "$BIN" ] || { echo "binário ausente: $BIN"; exit 1; }

echo "subindo servidor"
DATABASE_URL="postgres://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${DB}" \
JWT_SECRET="$SECRET" ADMIN_TOKEN="$ADMIN" BIND_ADDR="127.0.0.1:3319" RUST_LOG=error "$BIN" &
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

echo
echo "acesso e conteúdo"

C_SEM_TOKEN=$(code "$BASE/parceiros")
check "sem token devolve 401 (recebido $C_SEM_TOKEN)" \
    "$([ "$C_SEM_TOKEN" = "401" ] && echo true || echo false)"

LISTA=$(curl -s "$BASE/parceiros" -H "$AUTH")
TOTAL=$(total <<<"$LISTA")
QTD=$(itens <<<"$LISTA")
check "vitrine tem 7 ofertas ativas (total recebido ${TOTAL:-vazio})" \
    "$([ "$TOTAL" = "7" ] && echo true || echo false)"
check "7 cabem na primeira página de 10 (recebido ${QTD:-vazio})" \
    "$([ "$QTD" = "7" ] && echo true || echo false)"

DESTINOS_OK=$(todos_prefixo destino_url <<<"$LISTA")
check "todo destino_url é https:// (WebView só aceita https)" \
    "$([ "$DESTINOS_OK" = "true" ] && echo true || echo false)"

IMAGENS_OK=$(todos_prefixo imagem_url <<<"$LISTA")
check "toda imagem_url é https://" \
    "$([ "$IMAGENS_OK" = "true" ] && echo true || echo false)"

PRIMEIRO=$(campo "itens.0.titulo" <<<"$LISTA")
check "mais novo primeiro: seed preserva O Boticário no topo (recebido ${PRIMEIRO:0:24})" \
    "$([ "${PRIMEIRO:0:12}" = "O Boticário " ] && echo true || echo false)"

VAZIO=$(python3 -c "
import sys, json
v = json.load(sys.stdin).get('itens') or []
print('true' if any(not i.get('titulo') or not i.get('loja') or not i.get('id') for i in v) else 'false')" <<<"$LISTA")
check "nenhuma oferta vem com id/loja/titulo vazio" \
    "$([ "$VAZIO" = "false" ] && echo true || echo false)"

# A AUSÊNCIA de preço é regra de produto. Se alguém adicionar o campo, este
# check falha e a decisão volta para a mesa em vez de vazar no app.
TEM_PRECO=$(python3 -c "
import sys, json
v = json.load(sys.stdin).get('itens') or []
chaves = {k for item in v for k in item}
print('true' if chaves & {'price','preco','preco_referencia','amount'} else 'false')" <<<"$LISTA")
check "resposta NÃO tem campo de preço (preço é sempre o da loja)" \
    "$([ "$TEM_PRECO" = "false" ] && echo true || echo false)"

echo
echo "paginação (10 por página, sem repetir e sem pular)"

OFF0=$(curl -s "$BASE/parceiros?limit=3&offset=0" -H "$AUTH")
OFF3=$(curl -s "$BASE/parceiros?limit=3&offset=3" -H "$AUTH")
OFF6=$(curl -s "$BASE/parceiros?limit=3&offset=6" -H "$AUTH")
check "?limit=3 devolve 3 itens (recebido $(itens <<<"$OFF0"))" \
    "$([ "$(itens <<<"$OFF0")" = "3" ] && echo true || echo false)"
check "?offset=6 devolve o resto (recebido $(itens <<<"$OFF6"))" \
    "$([ "$(itens <<<"$OFF6")" = "1" ] && echo true || echo false)"
check "total continua 7 em toda página (recebido $(total <<<"$OFF3"))" \
    "$([ "$(total <<<"$OFF3")" = "7" ] && echo true || echo false)"

EMENDA=$(python3 -c "
import sys
p1, p2, p3 = (sys.argv[1].split(), sys.argv[2].split(), sys.argv[3].split())
todos = p1 + p2 + p3
print('true' if len(todos) == 7 and len(set(todos)) == 7 else 'false')" \
    "$(ids <<<"$OFF0")" "$(ids <<<"$OFF3")" "$(ids <<<"$OFF6")")
check "as três páginas somam 7 ofertas distintas (sem sobreposição)" \
    "$([ "$EMENDA" = "true" ] && echo true || echo false)"

C_LIMITE=$(code "$BASE/parceiros?limit=999" -H "$AUTH")
check "limit absurdo não estoura (recebido $C_LIMITE, teto 50)" \
    "$([ "$C_LIMITE" = "200" ] && echo true || echo false)"

echo
echo "admin cadastra link e ele aparece primeiro"

C_SEM_ADMIN=$(code -X POST "$BASE/admin/parceiros" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"link":"https://lojateste.com.br/p/1","titulo":"Fone de teste"}')
check "sem X-Admin-Token o cadastro é 401 (recebido $C_SEM_ADMIN)" \
    "$([ "$C_SEM_ADMIN" = "401" ] && echo true || echo false)"

C_TOKEN_ERRADO=$(code -X POST "$BASE/admin/parceiros" -H "$AUTH" -H 'X-Admin-Token: errado' \
    -H 'content-type: application/json' -d '{"link":"https://lojateste.com.br/p/1","titulo":"x"}')
check "token errado é 401 (recebido $C_TOKEN_ERRADO)" \
    "$([ "$C_TOKEN_ERRADO" = "401" ] && echo true || echo false)"

C_HTTP=$(code -X POST "$BASE/admin/parceiros" -H "$ADMIN_H" -H 'content-type: application/json' \
    -d '{"link":"http://lojateste.com.br/p/2","titulo":"x"}')
check "link http:// é recusado com 422 (recebido $C_HTTP)" \
    "$([ "$C_HTTP" = "422" ] && echo true || echo false)"

# Link que não responde: o servidor não consegue ler og:title e tem de PEDIR o
# título em vez de cadastrar card sem título.
C_SEM_TITULO=$(code -X POST "$BASE/admin/parceiros" -H "$ADMIN_H" -H 'content-type: application/json' \
    -d '{"link":"https://dominio-que-nao-existe.invalid/p/1"}')
check "link ilegível sem titulo vira 422 pedindo o titulo (recebido $C_SEM_TITULO)" \
    "$([ "$C_SEM_TITULO" = "422" ] && echo true || echo false)"

NOVA=$(curl -s -X POST "$BASE/admin/parceiros" -H "$ADMIN_H" -H 'content-type: application/json' \
    -d '{"link":"https://www.lojateste.com.br/p/1","titulo":"Fone Bluetooth de teste","imagem":"https://www.lojateste.com.br/fone.png"}')
NOVA_ID=$(get id <<<"$NOVA")
NOVA_LOJA=$(get loja <<<"$NOVA")
check "cadastro com titulo/imagem devolve id (recebido ${NOVA_ID:0:8})" \
    "$([ -n "$NOVA_ID" ] && echo true || echo false)"
check "loja deduzida do domínio vira Lojateste (recebido $NOVA_LOJA)" \
    "$([ "$NOVA_LOJA" = "Lojateste" ] && echo true || echo false)"

DEPOIS=$(curl -s "$BASE/parceiros" -H "$AUTH")
check "a oferta nova aparece PRIMEIRO (recebido $(campo "itens.0.titulo" <<<"$DEPOIS"))" \
    "$([ "$(campo "itens.0.titulo" <<<"$DEPOIS")" = "Fone Bluetooth de teste" ] && echo true || echo false)"
check "total sobe para 8 (recebido $(total <<<"$DEPOIS"))" \
    "$([ "$(total <<<"$DEPOIS")" = "8" ] && echo true || echo false)"

C_DUP=$(code -X POST "$BASE/admin/parceiros" -H "$ADMIN_H" -H 'content-type: application/json' \
    -d '{"link":"https://www.lojateste.com.br/p/1","titulo":"Duplicado"}')
check "mesmo link de novo é 409, não card duplicado (recebido $C_DUP)" \
    "$([ "$C_DUP" = "409" ] && echo true || echo false)"

C_DEL=$(code -X DELETE "$BASE/admin/parceiros/$NOVA_ID" -H "$ADMIN_H")
check "admin desativa a oferta (204, recebido $C_DEL)" \
    "$([ "$C_DEL" = "204" ] && echo true || echo false)"

VOLTOU=$(curl -s "$BASE/parceiros" -H "$AUTH")
check "desativada some da lista (total $TOTAL, recebido $(total <<<"$VOLTOU"))" \
    "$([ "$(total <<<"$VOLTOU")" = "7" ] && echo true || echo false)"

C_DEL_2=$(code -X DELETE "$BASE/admin/parceiros/$NOVA_ID" -H "$ADMIN_H")
check "desativar de novo é 404 (recebido $C_DEL_2)" \
    "$([ "$C_DEL_2" = "404" ] && echo true || echo false)"

# Reenviar o link de uma oferta desativada REATIVA em vez de dar 409: é o que
# o admin espera ao cadastrar de novo o que tirou do ar.
REATIVOU=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/admin/parceiros" -H "$ADMIN_H" \
    -H 'content-type: application/json' \
    -d '{"link":"https://www.lojateste.com.br/p/1","titulo":"Fone de volta"}')
check "reenviar link desativado reativa (201, recebido $REATIVOU)" \
    "$([ "$REATIVOU" = "201" ] && echo true || echo false)"

"${PSQL[@]}" -d "$DB" -c \
    "DELETE FROM parceiros_ofertas WHERE destino_url = 'https://www.lojateste.com.br/p/1';" >/dev/null

echo
echo "mesma lista para outro usuário (conteúdo, não dado pessoal)"
LISTA_B=$(curl -s "$BASE/parceiros" -H "$AUTH_B")
IGUAIS=$(python3 -c "
import sys, json
a = json.loads(sys.argv[1]).get('itens') or []
b = json.loads(sys.argv[2]).get('itens') or []
print('true' if [i['id'] for i in a] == [i['id'] for i in b] else 'false')" "$LISTA" "$LISTA_B")
check "bob vê a mesma lista de alice" \
    "$([ "$IGUAIS" = "true" ] && echo true || echo false)"

echo
echo "oferta desativada some (por SQL, sem passar pela API)"

"${PSQL[@]}" -d "$DB" -c \
    "UPDATE parceiros_ofertas SET ativo = FALSE WHERE titulo LIKE 'O Boticário%';" >/dev/null
LISTA_OFF=$(curl -s "$BASE/parceiros" -H "$AUTH")
check "desativar tira da lista (6 restantes, recebido $(total <<<"$LISTA_OFF"))" \
    "$([ "$(total <<<"$LISTA_OFF")" = "6" ] && echo true || echo false)"

AINDA_TEM=$(python3 -c "
import sys, json
v = json.load(sys.stdin).get('itens') or []
print('true' if any(i['titulo'].startswith('O Boticário') for i in v) else 'false')" <<<"$LISTA_OFF")
check "a desativada não aparece pelo título" \
    "$([ "$AINDA_TEM" = "false" ] && echo true || echo false)"

"${PSQL[@]}" -d "$DB" -c \
    "UPDATE parceiros_ofertas SET ativo = TRUE WHERE titulo LIKE 'O Boticário%';" >/dev/null

echo
echo "usuário não escreve"

C_POST=$(code -X POST "$BASE/parceiros" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"titulo":"injetado","loja":"x","imagem_url":"https://x/y.png","destino_url":"https://x"}')
check "POST /parceiros não existe (recebido $C_POST)" \
    "$([ "$C_POST" = "405" ] || [ "$C_POST" = "404" ] && echo true || echo false)"

C_DEL_USER=$(code -X DELETE "$BASE/parceiros/00000000-0000-0000-0000-000000000000" -H "$AUTH")
check "DELETE /parceiros/{id} também não existe para usuário (recebido $C_DEL_USER)" \
    "$([ "$C_DEL_USER" = "405" ] || [ "$C_DEL_USER" = "404" ] && echo true || echo false)"

# Defesa do banco, não do app: nem por SQL direto entra link não-https.
SOLTOU=$("${PSQL[@]}" -d "$DB" -c \
    "INSERT INTO parceiros_ofertas (loja, titulo, imagem_url, destino_url) \
     VALUES ('x','y','http://x/y.png','https://x/y');" 2>&1 | grep -c "parceiros_ofertas_imagem_https" || true)
check "CHECK do banco recusa imagem http:// (bloqueada $SOLTOU vez)" \
    "$([ "$SOLTOU" -ge 1 ] && echo true || echo false)"

echo
echo "---------------------------------------------"
echo "passaram: $pass   falharam: $fail"
[ "$fail" -eq 0 ]
