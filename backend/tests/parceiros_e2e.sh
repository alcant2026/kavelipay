#!/usr/bin/env bash
# Vitrine de parceiros: leitura pura, sem preço, sem escrita.
#
# O que este script protege:
#  - 401 sem token (a vitrine não é URL pública de propaganda);
#  - lista vem ordenada e só com ofertas ativas;
#  - TODO link é https (a WebView do app carrega isso; http ou esquema
#    estranho viraria vetor);
#  - NÃO existe campo de preço — a ausência é decisão de produto (ver
#    migrations/0011), não esquecimento, e um dia alguém vai querer "só
#    adicionar o preço"; este check é o que faz a conversa acontecer;
#  - não existe POST: a vitrine é curada por migration, não por API;
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
qtd() { python3 -c "
import sys, json
try:
    v = json.load(sys.stdin)
    print(len(v) if isinstance(v, list) else '')
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
# true se TODOS os itens da lista têm a chave começando por $1.
todos_prefixo() { python3 -c "
import sys, json
try:
    v = json.load(sys.stdin)
    print('true' if v and all(str(i.get('$1','')).startswith('https://') for i in v) else 'false')
except Exception:
    print('false')"; }

echo "criando banco: $DB"
"${PSQL[@]}" -d postgres -c "CREATE DATABASE \"$DB\";"
[ -x "$BIN" ] || { echo "binário ausente: $BIN"; exit 1; }

echo "subindo servidor"
DATABASE_URL="postgres://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${DB}" \
JWT_SECRET="$SECRET" BIND_ADDR="127.0.0.1:3319" RUST_LOG=error "$BIN" &
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
echo "acesso e conteúdo"

C_SEM_TOKEN=$(code "$BASE/parceiros")
check "sem token devolve 401 (recebido $C_SEM_TOKEN)" \
    "$([ "$C_SEM_TOKEN" = "401" ] && echo true || echo false)"

LISTA=$(curl -s "$BASE/parceiros" -H "$AUTH")
TOTAL=$(qtd <<<"$LISTA")
check "vitrine tem 7 ofertas ativas (recebido ${TOTAL:-vazio})" \
    "$([ "$TOTAL" = "7" ] && echo true || echo false)"

DESTINOS_OK=$(todos_prefixo destino_url <<<"$LISTA")
check "todo destino_url é https:// (WebView só aceita https)" \
    "$([ "$DESTINOS_OK" = "true" ] && echo true || echo false)"

IMAGENS_OK=$(todos_prefixo imagem_url <<<"$LISTA")
check "toda imagem_url é https://" \
    "$([ "$IMAGENS_OK" = "true" ] && echo true || echo false)"

PRIMEIRO=$(campo 0.titulo <<<"$LISTA")
check "primeira oferta é a de ordem 0 (recebido ${PRIMEIRO:0:24})" \
    "$([ "${PRIMEIRO:0:12}" = "O Boticário " ] && echo true || echo false)"

TITULO_VAZIO=$(python3 -c "
import sys, json
v = json.load(sys.stdin)
print('true' if any(not i.get('titulo') or not i.get('loja') or not i.get('id') for i in v) else 'false')" <<<"$LISTA")
check "nenhuma oferta vem com id/loja/titulo vazio" \
    "$([ "$TITULO_VAZIO" = "false" ] && echo true || echo false)"

# A AUSÊNCIA de preço é regra de produto. Se alguém adicionar o campo, este
# check falha e a decisão volta para a mesa em vez de vazar no app.
TEM_PRECO=$(python3 -c "
import sys, json
v = json.load(sys.stdin)
chaves = {k for item in v for k in item}
print('true' if chaves & {'price','preco','preco_referencia','amount'} else 'false')" <<<"$LISTA")
check "resposta NÃO tem campo de preço (preço é sempre o da loja)" \
    "$([ "$TEM_PRECO" = "false" ] && echo true || echo false)"

echo
echo "mesma lista para outro usuário (conteúdo, não dado pessoal)"
LISTA_B=$(curl -s "$BASE/parceiros" -H "$AUTH_B")
TOTAL_B=$(qtd <<<"$LISTA_B")
MESMA=$(python3 -c "
import sys, json
a = json.loads(sys.argv[1]); b = json.loads(sys.argv[2])
print('true' if [i['id'] for i in a] == [i['id'] for i in b] else 'false')" "$LISTA" "$LISTA_B")
check "bob vê a mesma lista de alice (${TOTAL_B:-vazio} itens)" \
    "$([ "$MESMA" = "true" ] && echo true || echo false)"

echo
echo "oferta desativada some"

"${PSQL[@]}" -d "$DB" -c "UPDATE parceiros_ofertas SET ativo = FALSE WHERE ordem = 0;" >/dev/null
LISTA_OFF=$(curl -s "$BASE/parceiros" -H "$AUTH")
TOTAL_OFF=$(qtd <<<"$LISTA_OFF")
check "desativar tira da lista (6 restantes, recebido ${TOTAL_OFF:-vazio})" \
    "$([ "$TOTAL_OFF" = "6" ] && echo true || echo false)"

AINDA_TEM=$(python3 -c "
import sys, json
v = json.load(sys.stdin)
print('true' if any(i['titulo'].startswith('O Boticário') for i in v) else 'false')" <<<"$LISTA_OFF")
check "a desativada não aparece pelo título" \
    "$([ "$AINDA_TEM" = "false" ] && echo true || echo false)"

"${PSQL[@]}" -d "$DB" -c "UPDATE parceiros_ofertas SET ativo = TRUE WHERE ordem = 0;" >/dev/null

echo
echo "só leitura"

C_POST=$(code -X POST "$BASE/parceiros" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"titulo":"injetado","loja":"x","imagem_url":"https://x/y.png","destino_url":"https://x"}')
check "POST /parceiros não existe (recebido $C_POST)" \
    "$([ "$C_POST" = "405" ] || [ "$C_POST" = "404" ] && echo true || echo false)"

# Defesa do banco, não do app: nem por SQL direto entra link não-https.
SOLTOU=$( "${PSQL[@]}" -d "$DB" -c \
    "INSERT INTO parceiros_ofertas (loja, titulo, imagem_url, destino_url) \
     VALUES ('x','y','http://x/y.png','https://x');" 2>&1 | grep -c "parceiros_ofertas_imagem_https" || true)
check "CHECK do banco recusa imagem http:// (bloqueada $SOLTOU vez)" \
    "$([ "$SOLTOU" -ge 1 ] && echo true || echo false)"

echo
echo "---------------------------------------------"
echo "passaram: $pass   falharam: $fail"
[ "$fail" -eq 0 ]
