#!/usr/bin/env bash
#
# Varre a vitrine de parceiros e aponta as ofertas cujo link morreu.
#
# Link de loja morre sem avisar: produto esgotado, anúncio removido, vendedor
# banido. O app avisa o usuário quando ele abre a oferta ("essa indicação saiu
# do ar"), mas isso é tarde — ele já clicou. Esta varredura é o outro lado:
# o admin descobre antes e tira a oferta da vitrine.
#
# Só 404 e 410 contam como morto. 403 é bot block (Mercado Livre faz isso com
# qualquer leitor automático), 429 é rate limit e 5xx é instabilidade da loja —
# desativar por causa deles derrubaria oferta boa.
#
# Uso:
#   ./scripts/verificar_ofertas.sh                # relatório, não muda nada
#   ./scripts/verificar_ofertas.sh --desativar    # tira as mortas da vitrine
#
# Ambiente (exporte antes ou passe na linha de comando):
#   BASE=http://localhost:3000
#   EMAIL=... PASSWORD=...        conta usada só para LER a lista
#   ADMIN_TOKEN=...               para desativar; se ausente, lê do script do
#                                 serviço (~/.local/bin/kavelipay-backend.sh)
#
# A lista exige login porque /parceiros é rota autenticada; o DELETE exige o
# token de admin no header X-Admin-Token. São credenciais diferentes de
# propósito: quem lê é usuário, quem cadastra é admin.

set -uo pipefail

BASE="${BASE:-http://localhost:3000}"
EMAIL="${EMAIL:-qr-final@teste.com}"
PASSWORD="${PASSWORD:-senha-forte-123}"
DESATIVAR=0

for arg in "$@"; do
    case "$arg" in
        --desativar) DESATIVAR=1 ;;
        -h|--help) sed -n '2,28p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "argumento desconhecido: $arg" >&2; exit 2 ;;
    esac
done

if [ -z "${ADMIN_TOKEN:-}" ] && [ -f "$HOME/.local/bin/kavelipay-backend.sh" ]; then
    # `sed` e não `cut -d=`: o token é base64 e pode terminar em '=', o que
    # faria o cut cortar o próprio token pela metade (401 sem explicação).
    ADMIN_TOKEN=$(sed -n 's/^export ADMIN_TOKEN=//p' "$HOME/.local/bin/kavelipay-backend.sh" | head -1)
fi
if [ "$DESATIVAR" = 1 ] && [ -z "${ADMIN_TOKEN:-}" ]; then
    echo "faltando ADMIN_TOKEN para desativar (exporte ou use sem --desativar)" >&2
    exit 2
fi

json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

acesso=$(curl -sS -m 10 -X POST "$BASE/auth/login" \
    -H 'Content-Type: application/json' \
    -d "$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"password":sys.argv[2]}))' "$EMAIL" "$PASSWORD")" \
    | json 'd.get("access_token","")')

if [ -z "$acesso" ]; then
    echo "não consegui entrar como $EMAIL em $BASE" >&2
    exit 1
fi

pagina=$(curl -sS -m 10 "$BASE/parceiros?limit=50" -H "Authorization: Bearer $acesso")
total=$(printf '%s' "$pagina" | json 'd.get("total",0)')
echo "vitrine: $total oferta(s) ativa(s) em $BASE"
echo

# id<TAB>titulo<TAB>destino, uma por linha
printf '%s' "$pagina" | python3 -c '
import json, sys
d = json.load(sys.stdin)
for o in d.get("itens", []):
    print(o["id"], o["titulo"], o["destino_url"], sep="\t")
' | while IFS=$'\t' read -r id titulo destino; do
    codigo=$(curl -s -o /dev/null -L -m 12 -A "Mozilla/5.0 (Linux; Android 13)" \
        -w '%{http_code}' "$destino" 2>/dev/null || echo "000")

    case "$codigo" in
        404|410)
            echo "MORTA  $codigo  $titulo"
            echo "       $destino"
            if [ "$DESATIVAR" = 1 ]; then
                resposta=$(curl -sS -o /dev/null -w '%{http_code}' -m 10 -X DELETE \
                    "$BASE/admin/parceiros/$id" -H "X-Admin-Token: $ADMIN_TOKEN")
                echo "       desativada -> $resposta"
            else
                echo "       (rode com --desativar para tirar da vitrine)"
            fi
            ;;
        000) echo "SEM-RESPOSTA  $titulo (timeout/rede — não é link morto)" ;;
        *)   printf 'ok     %s  %s\n' "$codigo" "$titulo" ;;
    esac
done

echo
if [ "$DESATIVAR" = 1 ]; then
    echo "pronto. a próxima abertura do app já não mostra as desativadas."
else
    echo "relatório apenas — nada foi alterado."
fi
