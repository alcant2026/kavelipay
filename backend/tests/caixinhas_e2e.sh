#!/usr/bin/env bash
# Testes da Caixinha (empréstimo registrado entre conhecidos): criação com
# convite de uso único, entrada com chave (case/whitespace, uso único,
# rotação do código na mesma transação), fronteira 404 para não-membro,
# aporte/rendimento/devolução sob "quem paga propõe, quem recebe confirma",
# derivações do mês, avaliação, telefone e vitrine pública.
#
# A prova central do contrato: a caixinha é FORA do ledger — o `GET /wallet`
# do dono e do membro fica `0.00` do começo ao fim, em cada etapa da jornada.
#
# Uso:  tests/caixinhas_e2e.sh
# Env:   PGHOST PGPORT PGUSER PGPASSWORD

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="kavelipay_caixinhas_test_$$"
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
saldo() { curl -s "$BASE/wallet" -H "$1" | get balance; }

# Igual a `get`, mas tolera corpo vazio ou não-JSON (estado RED) e aceita
# caminho com índice de lista: `campo membros.0.devido_mes`.
campo() { python3 -c "
import sys, json
try:
    v = json.load(sys.stdin)
    for p in '$1'.split('.'):
        v = v[int(p)] if isinstance(v, list) else v[p]
    print('' if v is None else v)
except Exception:
    print('')"; }

# Tamanho de uma lista por caminho (ex.: `membros`, `movimentos`);
# caminho vazio conta a própria lista raiz.
qtd_de() { python3 -c "
import sys, json
try:
    v = json.load(sys.stdin)
    for p in (sys.argv[1].split('.') if sys.argv[1] else []):
        v = v[int(p)] if isinstance(v, list) else v[p]
    print(len(v) if isinstance(v, list) else '')
except Exception:
    print('')" "$1"; }

# Campo de um movimento achado por tipo+status+membro no detalhe visível.
mov_campo() { python3 -c "
import sys, json
try:
    det = json.load(sys.stdin)
    for m in det.get('movimentos', []):
        if (m.get('tipo') == sys.argv[1] and m.get('status') == sys.argv[2]
                and m.get('membro_id') == sys.argv[3]):
            v = m.get(sys.argv[4])
            print('' if v is None else v)
            break
    else:
        print('')
except Exception:
    print('')" "$1" "$2" "$3" "${4:-id}"; }

# Campo de um item achado pelo nome em listas (minhas, vitrine).
acha() { python3 -c "
import sys, json
try:
    v = json.load(sys.stdin)
    m = next((x for x in v if x.get('nome') == sys.argv[1]), None)
    r = '' if m is None or m.get(sys.argv[2]) is None else m.get(sys.argv[2])
    print(r)
except Exception:
    print('')" "$1" "$2"; }

# Um request só: corpo e status juntos — duas chamadas criariam movimento
# duplicado assim que a rota existisse (mesmo padrão de debts_e2e).
req() { curl -s -w '\n%{http_code}' "$@"; }

MES_ATUAL="$(date -u +%Y-%m)"
MES_PROX="$(date -u -d "$(date -u +%Y-%m-01) +1 month" +%Y-%m)"

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

# Quatro contas: alice (dono, nome para a vitrine), bob (membro), carol
# (membro que entra depois, sem aporte no começo), dave (nunca entra).
curl -s -o /dev/null -X POST "$BASE/auth/register" -H 'content-type: application/json' \
    -d '{"email":"alice@teste.com","password":"senha-forte-123","display_name":"Alice Silva"}'
for EMAIL in bob@teste.com carol@teste.com dave@teste.com; do
    curl -s -o /dev/null -X POST "$BASE/auth/register" -H 'content-type: application/json' \
        -d "{\"email\":\"$EMAIL\",\"password\":\"senha-forte-123\"}"
done
login() { curl -s -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"senha-forte-123\"}" | get access_token; }
eu() { curl -s "$BASE/auth/me" -H "Authorization: Bearer $1" | get user_id; }

TOKEN_A=$(login alice@teste.com); AUTH_A="Authorization: Bearer $TOKEN_A"
TOKEN_B=$(login bob@teste.com);   AUTH_B="Authorization: Bearer $TOKEN_B"
TOKEN_C=$(login carol@teste.com);  AUTH_C="Authorization: Bearer $TOKEN_C"
TOKEN_D=$(login dave@teste.com);  AUTH_D="Authorization: Bearer $TOKEN_D"
ALICE_ID=$(eu "$TOKEN_A")
BOB_ID=$(eu "$TOKEN_B")
CAROL_ID=$(eu "$TOKEN_C")
DAVE_ID=$(eu "$TOKEN_D")

echo
echo "carteiras começam zeradas (fora do ledger desde o início)"
S0_A=$(saldo "$AUTH_A"); S0_B=$(saldo "$AUTH_B"); S0_C=$(saldo "$AUTH_C"); S0_D=$(saldo "$AUTH_D")
check "saldo da alice é 0.00 (recebido $S0_A)" "$([ "$S0_A" = "0.00" ] && echo true || echo false)"
check "saldo do bob é 0.00 (recebido $S0_B)" "$([ "$S0_B" = "0.00" ] && echo true || echo false)"
check "saldo da carol é 0.00 (recebido $S0_C)" "$([ "$S0_C" = "0.00" ] && echo true || echo false)"
check "saldo do dave é 0.00 (recebido $S0_D)" "$([ "$S0_D" = "0.00" ] && echo true || echo false)"

echo
echo "criar caixinha: 201, papel dono, convite já gerado"

RESPOSTA=$(req -X POST "$BASE/caixinhas" -H "$AUTH_A" -H 'content-type: application/json' \
    -d '{"nome":"Viagem da família","valor_pedido":"1000.00","taxa_mensal":"3","previsao_quitacao":"dezembro","telefone":"11999990000","publica":true}')
C_CRIAR="${RESPOSTA##*$'\n'}"
NOVA="${RESPOSTA%$'\n'*}"

check "criar devolve 201 (recebido $C_CRIAR)" \
    "$([ "$C_CRIAR" = "201" ] && echo true || echo false)"

CAIXINHA=$(campo id <<<"$NOVA")
check "id presente (recebido ${CAIXINHA:-vazio})" \
    "$([ -n "$CAIXINHA" ] && echo true || echo false)"

PAPEL=$(campo papel <<<"$NOVA")
check "papel do criador é dono (recebido ${PAPEL:-vazio})" \
    "$([ "$PAPEL" = "dono" ] && echo true || echo false)"

CONVITE1=$(campo convite <<<"$NOVA")
check "convite nasce no formato KAV-XXXXXX (recebido ${CONVITE1:-vazio})" \
    "$(python3 -c "import re,sys; print('true' if re.match(r'^KAV-[0-9A-F]{6}$', sys.argv[1]) else 'false')" "$CONVITE1")"

TAXA=$(campo taxa_mensal <<<"$NOVA")
check "taxa serializa sem zeros à direita: 3 (recebido ${TAXA:-vazio})" \
    "$([ "$TAXA" = "3" ] && echo true || echo false)"

PEDIDO=$(campo valor_pedido <<<"$NOVA")
check "valor_pedido volta como string 1000.00 (recebido ${PEDIDO:-vazio})" \
    "$([ "$PEDIDO" = "1000.00" ] && echo true || echo false)"

PREVISAO=$(campo previsao_quitacao <<<"$NOVA")
check "previsao_quitacao salva: dezembro (recebido ${PREVISAO:-vazio})" \
    "$([ "$PREVISAO" = "dezembro" ] && echo true || echo false)"

FONE=$(campo dono.telefone <<<"$NOVA")
check "telefone do dono em dígitos: 11999990000 (recebido ${FONE:-vazio})" \
    "$([ "$FONE" = "11999990000" ] && echo true || echo false)"

ARR0=$(campo arrecadado <<<"$NOVA")
check "arrecadado começa 0.00 (recebido ${ARR0:-vazio})" \
    "$([ "$ARR0" = "0.00" ] && echo true || echo false)"

PROG0=$(campo progresso <<<"$NOVA")
check "progresso começa 0.0 (recebido ${PROG0:-vazio})" \
    "$([ "$PROG0" = "0.0" ] && echo true || echo false)"

N_MEMB0=$(qtd_de membros <<<"$NOVA")
check "caixinha nasce sem membros (recebido ${N_MEMB0:-vazio})" \
    "$([ "$N_MEMB0" = "0" ] && echo true || echo false)"

# Segunda caixinha NÃO pública: é a que não pode aparecer na vitrine.
RESPOSTA_R=$(req -X POST "$BASE/caixinhas" -H "$AUTH_A" -H 'content-type: application/json' \
    -d '{"nome":"Caixa reserva","valor_pedido":"500.00","taxa_mensal":"2.5","publica":false}')
C_RESERVA="${RESPOSTA_R##*$'\n'}"
check "criar caixinha não-pública devolve 201 (recebido $C_RESERVA)" \
    "$([ "$C_RESERVA" = "201" ] && echo true || echo false)"

echo
echo "validações da criação: 422 em pt-BR, nada é gravado"

RESPOSTA=$(req -X POST "$BASE/caixinhas" -H "$AUTH_A" -H 'content-type: application/json' \
    -d '{"nome":"   ","valor_pedido":"100.00","taxa_mensal":"3"}')
C_SEM_NOME="${RESPOSTA##*$'\n'}"
check "nome vazio é 422 (recebido $C_SEM_NOME)" \
    "$([ "$C_SEM_NOME" = "422" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'nome é obrigatório' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "nome é obrigatório" ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas" -H "$AUTH_A" -H 'content-type: application/json' \
    -d '{"nome":"Outra","valor_pedido":"0.00","taxa_mensal":"3"}')
C_PEDIDO_ZERO="${RESPOSTA##*$'\n'}"
check "valor_pedido 0.00 é 422 (recebido $C_PEDIDO_ZERO)" \
    "$([ "$C_PEDIDO_ZERO" = "422" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'valor deve ser maior que zero' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "valor deve ser maior que zero" ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas" -H "$AUTH_A" -H 'content-type: application/json' \
    -d '{"nome":"Outra","valor_pedido":"100.00","taxa_mensal":"0"}')
C_TAXA_ZERO="${RESPOSTA##*$'\n'}"
check "taxa 0 é 422 (recebido $C_TAXA_ZERO)" \
    "$([ "$C_TAXA_ZERO" = "422" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'taxa ao mês deve ser maior que zero' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "taxa ao mês deve ser maior que zero" ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas" -H "$AUTH_A" -H 'content-type: application/json' \
    -d '{"nome":"Outra","valor_pedido":"100.00","taxa_mensal":"3","telefone":"123"}')
C_FONE="${RESPOSTA##*$'\n'}"
check "telefone sem DDD é 422 (recebido $C_FONE)" \
    "$([ "$C_FONE" = "422" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'Digite um telefone com DDD.' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "Digite um telefone com DDD." ] && echo true || echo false)"

C_FLOAT_PEDIDO=$(code -X POST "$BASE/caixinhas" -H "$AUTH_A" -H 'content-type: application/json' \
    -d '{"nome":"Outra","valor_pedido":100.50,"taxa_mensal":"3"}')
check "valor_pedido como float é 422 (recebido $C_FLOAT_PEDIDO)" \
    "$([ "$C_FLOAT_PEDIDO" = "422" ] && echo true || echo false)"

C_FLOAT_TAXA=$(code -X POST "$BASE/caixinhas" -H "$AUTH_A" -H 'content-type: application/json' \
    -d '{"nome":"Outra","valor_pedido":"100.00","taxa_mensal":2.5}')
check "taxa como float é 422 (recebido $C_FLOAT_TAXA)" \
    "$([ "$C_FLOAT_TAXA" = "422" ] && echo true || echo false)"

MINHAS_POS_422=$(curl -s "$BASE/caixinhas" -H "$AUTH_A")
N_POS_422=$(qtd_de '' <<<"$MINHAS_POS_422")
check "rejeitadas não gravaram nada (2 esperado, recebido ${N_POS_422:-vazio})" \
    "$([ "$N_POS_422" = "2" ] && echo true || echo false)"

echo
echo "dono não entra na própria caixinha — e a chave não é consumida"

RESPOSTA=$(req -X POST "$BASE/caixinhas/entrar" -H "$AUTH_A" -H 'content-type: application/json' \
    -d "{\"chave\":\"$CONVITE1\"}")
C_DONO_ENTRA="${RESPOSTA##*$'\n'}"
check "dono entra com o próprio código: 409 (recebido $C_DONO_ENTRA)" \
    "$([ "$C_DONO_ENTRA" = "409" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'Você é o responsável desta caixinha.' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "Você é o responsável desta caixinha." ] && echo true || echo false)"

DETALHE_A=$(curl -s "$BASE/caixinhas/$CAIXINHA" -H "$AUTH_A")
CONVITE_APOS=$(campo convite <<<"$DETALHE_A")
check "erro não consumiu a chave (segue $CONVITE1)" \
    "$([ "$CONVITE_APOS" = "$CONVITE1" ] && echo true || echo false)"

echo
echo "segunda conta entra com chave (case/whitespace ok)"

CHAVE_MAIUSC=$(echo "$CONVITE1" | tr 'a-z' 'A-Z')
RESPOSTA=$(req -X POST "$BASE/caixinhas/entrar" -H "$AUTH_B" -H 'content-type: application/json' \
    -d "{\"chave\":\"  $(echo "$CHAVE_MAIUSC" | tr 'A-Z' 'a-z')  \"}")
C_BOB_ENTRA="${RESPOSTA##*$'\n'}"
ENTRADA_B="${RESPOSTA%$'\n'*}"

check "bob entra com chave minúscula e espaçada: 200 (recebido $C_BOB_ENTRA)" \
    "$([ "$C_BOB_ENTRA" = "200" ] && echo true || echo false)"

PAPEL_B=$(campo papel <<<"$ENTRADA_B")
check "papel do bob é membro (recebido ${PAPEL_B:-vazio})" \
    "$([ "$PAPEL_B" = "membro" ] && echo true || echo false)"

CONVITE_B=$(campo convite <<<"$ENTRADA_B")
check "membro não vê o convite (recebido '${CONVITE_B:-vazio}')" \
    "$([ -z "$CONVITE_B" ] && echo true || echo false)"

N_MEMB_B=$(qtd_de membros <<<"$ENTRADA_B")
MEMBRO_0=$(campo membros.0.user_id <<<"$ENTRADA_B")
check "membro vê só ele mesmo na lista (recebido $N_MEMB_B)" \
    "$([ "$N_MEMB_B" = "1" ] && [ "$MEMBRO_0" = "$BOB_ID" ] && echo true || echo false)"

TOT_B=$(campo total_devido_mes <<<"$ENTRADA_B")
check "totais do mês são null pro membro (recebido '${TOT_B:-vazio}')" \
    "$([ -z "$TOT_B" ] && echo true || echo false)"

echo
echo "chave é de uso único"

RESPOSTA=$(req -X POST "$BASE/caixinhas/entrar" -H "$AUTH_C" -H 'content-type: application/json' \
    -d "{\"chave\":\"$CONVITE1\"}")
C_REUSA="${RESPOSTA##*$'\n'}"
check "mesma chave de novo é 409 (recebido $C_REUSA)" \
    "$([ "$C_REUSA" = "409" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'Essa chave já foi usada.' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "Essa chave já foi usada." ] && echo true || echo false)"

DETALHE_A=$(curl -s "$BASE/caixinhas/$CAIXINHA" -H "$AUTH_A")
CONVITE2=$(campo convite <<<"$DETALHE_A")
check "dono tem código NOVO diferente do primeiro (recebido ${CONVITE2:-vazio})" \
    "$([ -n "$CONVITE2" ] && [ "$CONVITE2" != "$CONVITE1" ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/entrar" -H "$AUTH_B" -H 'content-type: application/json' \
    -d '{"chave":"KAV-000000"}')
C_INVALIDA="${RESPOSTA##*$'\n'}"
check "chave desconhecida é 404 (recebido $C_INVALIDA)" \
    "$([ "$C_INVALIDA" = "404" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'Chave inválida.' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "Chave inválida." ] && echo true || echo false)"

echo
echo "quem não é dono nem membro: a caixinha não existe para ele"

RESPOSTA=$(req "$BASE/caixinhas/$CAIXINHA" -H "$AUTH_D")
C_DAVE_GET="${RESPOSTA##*$'\n'}"
check "dave vê detalhe de caixinha alheia: 404 (recebido $C_DAVE_GET)" \
    "$([ "$C_DAVE_GET" = "404" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'Caixinha não encontrada.' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "Caixinha não encontrada." ] && echo true || echo false)"

LISTA_D=$(curl -s "$BASE/caixinhas" -H "$AUTH_D")
N_D=$(qtd_de '' <<<"$LISTA_D")
check "lista do dave não traz caixinha alheia (recebido ${N_D:-vazio})" \
    "$([ "$N_D" = "0" ] && echo true || echo false)"

C_DAVE_APORTE=$(code -X POST "$BASE/caixinhas/$CAIXINHA/aportes" -H "$AUTH_D" \
    -H 'content-type: application/json' -d '{"amount":"10.00"}')
check "não-membro tenta aportar: 404, nunca 403 (recebido $C_DAVE_APORTE)" \
    "$([ "$C_DAVE_APORTE" = "404" ] && echo true || echo false)"

echo
echo "aporte: membro propõe, dono confirma — só depois entra na conta"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/aportes" -H "$AUTH_B" \
    -H 'content-type: application/json' -d '{"amount":"1500.00"}')
C_APO_ACIMA="${RESPOSTA##*$'\n'}"
check "aporte acima do disponível é 422 (recebido $C_APO_ACIMA)" \
    "$([ "$C_APO_ACIMA" = "422" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'Faltam R$ 1000,00 para completar o pedido.' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "Faltam R$ 1000,00 para completar o pedido." ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/aportes" -H "$AUTH_B" \
    -H 'content-type: application/json' -d '{"amount":"0.00"}')
C_APO_ZERO="${RESPOSTA##*$'\n'}"
check "aporte 0.00 é 422 (recebido $C_APO_ZERO)" \
    "$([ "$C_APO_ZERO" = "422" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'valor deve ser maior que zero' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "valor deve ser maior que zero" ] && echo true || echo false)"

C_APO_FLOAT=$(code -X POST "$BASE/caixinhas/$CAIXINHA/aportes" -H "$AUTH_B" \
    -H 'content-type: application/json' -d '{"amount":50.5}')
check "aporte como float é 422 (recebido $C_APO_FLOAT)" \
    "$([ "$C_APO_FLOAT" = "422" ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/aportes" -H "$AUTH_B" \
    -H 'content-type: application/json' -d '{"amount":"600.00"}')
C_APO_OK="${RESPOSTA##*$'\n'}"
APORTE_B="${RESPOSTA%$'\n'*}"
check "aporte de 600.00 devolve 201 (recebido $C_APO_OK)" \
    "$([ "$C_APO_OK" = "201" ] && echo true || echo false)"

ARR=$(campo arrecadado <<<"$APORTE_B")
check "proposto não conta: arrecadado segue 0.00 (recebido ${ARR:-vazio})" \
    "$([ "$ARR" = "0.00" ] && echo true || echo false)"

PEND=$(campo arrecadado_pendente <<<"$APORTE_B")
check "arrecadado_pendente é 600.00 (recebido ${PEND:-vazio})" \
    "$([ "$PEND" = "600.00" ] && echo true || echo false)"

DISP=$(campo disponivel_aporte <<<"$APORTE_B")
check "disponivel_aporte desconta a proposta: 400.00 (recebido ${DISP:-vazio})" \
    "$([ "$DISP" = "400.00" ] && echo true || echo false)"

MOV_APO=$(printf '%s' "$APORTE_B" | mov_campo APORTE PROPOSTO "$BOB_ID" id)
check "movimento APORTE nasce PROPOSTO (recebido ${MOV_APO:-vazio})" \
    "$([ -n "$MOV_APO" ] && echo true || echo false)"

APO_MESMO=$(campo membros.0.aportado <<<"$APORTE_B")
SALDO_MESMO=$(campo membros.0.saldo_em_aberto <<<"$APORTE_B")
check "números do membro ainda zerados (recebido $APO_MESMO/$SALDO_MESMO)" \
    "$([ "$APO_MESMO" = "0.00" ] && [ "$SALDO_MESMO" = "0.00" ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/movimentos/$MOV_APO/confirmar" -H "$AUTH_B")
C_BOB_CONFIRMA="${RESPOSTA##*$'\n'}"
check "membro não confirma o próprio aporte: 409 (recebido $C_BOB_CONFIRMA)" \
    "$([ "$C_BOB_CONFIRMA" = "409" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'Só a outra parte pode confirmar.' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "Só a outra parte pode confirmar." ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/movimentos/$MOV_APO/confirmar" -H "$AUTH_A")
C_CONFIRMA="${RESPOSTA##*$'\n'}"
CONFIRMADO="${RESPOSTA%$'\n'*}"
check "dono confirma o aporte: 200 (recebido $C_CONFIRMA)" \
    "$([ "$C_CONFIRMA" = "200" ] && echo true || echo false)"

ARR=$(campo arrecadado <<<"$CONFIRMADO")
check "arrecadado agora é 600.00 (recebido ${ARR:-vazio})" \
    "$([ "$ARR" = "600.00" ] && echo true || echo false)"

PEND=$(campo arrecadado_pendente <<<"$CONFIRMADO")
check "arrecadado_pendente volta a 0.00 (recebido ${PEND:-vazio})" \
    "$([ "$PEND" = "0.00" ] && echo true || echo false)"

PROG=$(campo progresso <<<"$CONFIRMADO")
check "progresso é 0.6 (recebido ${PROG:-vazio})" \
    "$([ "$PROG" = "0.6" ] && echo true || echo false)"

APO=$(campo membros.0.aportado <<<"$CONFIRMADO")
SALDO=$(campo membros.0.saldo_em_aberto <<<"$CONFIRMADO")
check "aportado 600.00 e saldo_em_aberto 600.00 (recebido $APO/$SALDO)" \
    "$([ "$APO" = "600.00" ] && [ "$SALDO" = "600.00" ] && echo true || echo false)"

CONF_EM=$(printf '%s' "$CONFIRMADO" | mov_campo APORTE CONFIRMADO "$BOB_ID" confirmado_em)
check "movimento confirmado tem confirmado_em (recebido ${CONF_EM:-vazio})" \
    "$([ -n "$CONF_EM" ] && echo true || echo false)"

S1_A=$(saldo "$AUTH_A"); S1_B=$(saldo "$AUTH_B")
check "saldo intacto após aporte confirmado (alice $S1_A, bob $S1_B)" \
    "$([ "$S1_A" = "0.00" ] && [ "$S1_B" = "0.00" ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/aportes" -H "$AUTH_A" \
    -H 'content-type: application/json' -d '{"amount":"100.00"}')
C_DONO_APORTA="${RESPOSTA##*$'\n'}"
check "dono tenta aportar: 409 (recebido $C_DONO_APORTA)" \
    "$([ "$C_DONO_APORTA" = "409" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'O responsável não aporta na própria caixinha.' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "O responsável não aporta na própria caixinha." ] && echo true || echo false)"

echo
echo "rendimento: o servidor calcula o valor — começa no mês seguinte ao aporte"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/rendimentos" -H "$AUTH_A" \
    -H 'content-type: application/json' \
    -d "{\"membro_id\":\"$BOB_ID\",\"competencia\":\"$MES_ATUAL\"}")
C_REND_ATUAL="${RESPOSTA##*$'\n'}"
check "rendimento do mês do aporte é 422 (recebido $C_REND_ATUAL)" \
    "$([ "$C_REND_ATUAL" = "422" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'Não há rendimento em aberto neste mês.' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "Não há rendimento em aberto neste mês." ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/rendimentos" -H "$AUTH_A" \
    -H 'content-type: application/json' \
    -d "{\"membro_id\":\"$BOB_ID\",\"competencia\":\"$MES_PROX\"}")
C_REND="${RESPOSTA##*$'\n'}"
RENDIMENTO="${RESPOSTA%$'\n'*}"
check "rendimento do próximo mês devolve 201 (recebido $C_REND)" \
    "$([ "$C_REND" = "201" ] && echo true || echo false)"

VALOR_REND=$(printf '%s' "$RENDIMENTO" | mov_campo RENDIMENTO PROPOSTO "$BOB_ID" valor)
check "valor do rendimento veio do servidor: 18.00 (recebido ${VALOR_REND:-vazio})" \
    "$([ "$VALOR_REND" = "18.00" ] && echo true || echo false)"

MOV_REND=$(printf '%s' "$RENDIMENTO" | mov_campo RENDIMENTO PROPOSTO "$BOB_ID" id)
COMP_REND=$(printf '%s' "$RENDIMENTO" | mov_campo RENDIMENTO PROPOSTO "$BOB_ID" competencia)
check "competência do rendimento é $MES_PROX (recebido ${COMP_REND:-vazio})" \
    "$([ "$COMP_REND" = "$MES_PROX" ] && echo true || echo false)"

DETALHE_PROX=$(curl -s "$BASE/caixinhas/$CAIXINHA?mes=$MES_PROX" -H "$AUTH_A")
DEVIDO=$(campo membros.0.devido_mes <<<"$DETALHE_PROX")
check "devido do mês seguinte é 18.00 (recebido ${DEVIDO:-vazio})" \
    "$([ "$DEVIDO" = "18.00" ] && echo true || echo false)"
PAGO=$(campo membros.0.pago_mes <<<"$DETALHE_PROX")
check "pago do mês é 0.00 (recebido ${PAGO:-vazio})" \
    "$([ "$PAGO" = "0.00" ] && echo true || echo false)"
FALTA=$(campo membros.0.falta_mes <<<"$DETALHE_PROX")
check "falta do mês é 18.00 (recebido ${FALTA:-vazio})" \
    "$([ "$FALTA" = "18.00" ] && echo true || echo false)"
DISP_REND=$(campo membros.0.rendimento_disponivel <<<"$DETALHE_PROX")
check "rendimento_disponivel desconta a proposta: 0.00 (recebido ${DISP_REND:-vazio})" \
    "$([ "$DISP_REND" = "0.00" ] && echo true || echo false)"
PEND_REND=$(campo membros.0.rendimento_pendente <<<"$DETALHE_PROX")
check "rendimento_pendente é True (recebido ${PEND_REND:-vazio})" \
    "$([ "$PEND_REND" = "True" ] && echo true || echo false)"
T_DEVIDO=$(campo total_devido_mes <<<"$DETALHE_PROX")
T_PAGO=$(campo total_pago_mes <<<"$DETALHE_PROX")
T_FALTA=$(campo total_falta_mes <<<"$DETALHE_PROX")
check "totais do dono no mês: 18.00/0.00/18.00 (recebido $T_DEVIDO/$T_PAGO/$T_FALTA)" \
    "$([ "$T_DEVIDO" = "18.00" ] && [ "$T_PAGO" = "0.00" ] && [ "$T_FALTA" = "18.00" ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/movimentos/$MOV_REND/confirmar" -H "$AUTH_A")
C_DONO_CONF_REND="${RESPOSTA##*$'\n'}"
check "dono não confirma o próprio rendimento: 409 (recebido $C_DONO_CONF_REND)" \
    "$([ "$C_DONO_CONF_REND" = "409" ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/movimentos/$MOV_REND/confirmar" -H "$AUTH_B")
C_MEMBRO_CONF_REND="${RESPOSTA##*$'\n'}"
check "membro confirma o rendimento: 200 (recebido $C_MEMBRO_CONF_REND)" \
    "$([ "$C_MEMBRO_CONF_REND" = "200" ] && echo true || echo false)"

DETALHE_PROX=$(curl -s "$BASE/caixinhas/$CAIXINHA?mes=$MES_PROX" -H "$AUTH_A")
PAGO=$(campo membros.0.pago_mes <<<"$DETALHE_PROX")
check "pago do mês agora é 18.00 (recebido ${PAGO:-vazio})" \
    "$([ "$PAGO" = "18.00" ] && echo true || echo false)"
FALTA=$(campo membros.0.falta_mes <<<"$DETALHE_PROX")
check "falta do mês fecha em 0.00 (recebido ${FALTA:-vazio})" \
    "$([ "$FALTA" = "0.00" ] && echo true || echo false)"
PEND_REND=$(campo membros.0.rendimento_pendente <<<"$DETALHE_PROX")
check "rendimento_pendente volta a False (recebido ${PEND_REND:-vazio})" \
    "$([ "$PEND_REND" = "False" ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/rendimentos" -H "$AUTH_A" \
    -H 'content-type: application/json' \
    -d "{\"membro_id\":\"$BOB_ID\",\"competencia\":\"$MES_PROX\"}")
C_REND_2X="${RESPOSTA##*$'\n'}"
check "rendimento de novo no mês é 422 (recebido $C_REND_2X)" \
    "$([ "$C_REND_2X" = "422" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'Não há rendimento em aberto neste mês.' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "Não há rendimento em aberto neste mês." ] && echo true || echo false)"

echo
echo "devolução: acima do saldo recusa; a total confirmada zera o saldo"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/devolucoes" -H "$AUTH_A" \
    -H 'content-type: application/json' \
    -d "{\"membro_id\":\"$BOB_ID\",\"amount\":\"700.00\"}")
C_DEV_ACIMA="${RESPOSTA##*$'\n'}"
check "devolução acima do saldo é 422 (recebido $C_DEV_ACIMA)" \
    "$([ "$C_DEV_ACIMA" = "422" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'O saldo em aberto é R\$ 600,00.' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "O saldo em aberto é R$ 600,00." ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/devolucoes" -H "$AUTH_A" \
    -H 'content-type: application/json' \
    -d "{\"membro_id\":\"$BOB_ID\",\"amount\":\"600.00\"}")
C_DEV="${RESPOSTA##*$'\n'}"
DEVOLUCAO="${RESPOSTA%$'\n'*}"
check "devolução total devolve 201 (recebido $C_DEV)" \
    "$([ "$C_DEV" = "201" ] && echo true || echo false)"

SALDO_PROP=$(campo membros.0.saldo_em_aberto <<<"$DEVOLUCAO")
check "proposta não muda o saldo: segue 600.00 (recebido ${SALDO_PROP:-vazio})" \
    "$([ "$SALDO_PROP" = "600.00" ] && echo true || echo false)"

MOV_DEV=$(printf '%s' "$DEVOLUCAO" | mov_campo DEVOLUCAO PROPOSTO "$BOB_ID" id)
check "movimento DEVOLUCAO nasce PROPOSTO (recebido ${MOV_DEV:-vazio})" \
    "$([ -n "$MOV_DEV" ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/movimentos/$MOV_DEV/confirmar" -H "$AUTH_B")
C_CONF_DEV="${RESPOSTA##*$'\n'}"
check "membro confirma a devolução: 200 (recebido $C_CONF_DEV)" \
    "$([ "$C_CONF_DEV" = "200" ] && echo true || echo false)"

SALDO_ZERO=$(campo membros.0.saldo_em_aberto <<<"${RESPOSTA%$'\n'*}")
check "devolução total confirmada zera o saldo (recebido ${SALDO_ZERO:-vazio})" \
    "$([ "$SALDO_ZERO" = "0.00" ] && echo true || echo false)"

APO=$(campo membros.0.aportado <<<"${RESPOSTA%$'\n'*}")
check "aportado continua 600.00 (recebido ${APO:-vazio})" \
    "$([ "$APO" = "600.00" ] && echo true || echo false)"

S2_A=$(saldo "$AUTH_A"); S2_B=$(saldo "$AUTH_B")
check "saldo intacto após rendimento e devolução (alice $S2_A, bob $S2_B)" \
    "$([ "$S2_A" = "0.00" ] && [ "$S2_B" = "0.00" ] && echo true || echo false)"

echo
echo "carol entra com o código atual; avalia sem aporte é 422"

RESPOSTA=$(req -X POST "$BASE/caixinhas/entrar" -H "$AUTH_C" -H 'content-type: application/json' \
    -d "{\"chave\":\"$CONVITE2\"}")
C_CAROL_ENTRA="${RESPOSTA##*$'\n'}"
check "carol entra com o código novo: 200 (recebido $C_CAROL_ENTRA)" \
    "$([ "$C_CAROL_ENTRA" = "200" ] && echo true || echo false)"

DETALHE_A=$(curl -s "$BASE/caixinhas/$CAIXINHA" -H "$AUTH_A")
CONVITE3=$(campo convite <<<"$DETALHE_A")
check "código já usado nunca volta: dono tem o terceiro (recebido ${CONVITE3:-vazio})" \
    "$([ -n "$CONVITE3" ] && [ "$CONVITE3" != "$CONVITE2" ] && [ "$CONVITE3" != "$CONVITE1" ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/avaliacao" -H "$AUTH_C" \
    -H 'content-type: application/json' -d '{"estrelas":4}')
C_AV_SEM_APO="${RESPOSTA##*$'\n'}"
check "avaliar sem aporte é 422 (recebido $C_AV_SEM_APO)" \
    "$([ "$C_AV_SEM_APO" = "422" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'Só quem fez aporte pode avaliar.' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "Só quem fez aporte pode avaliar." ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/aportes" -H "$AUTH_C" \
    -H 'content-type: application/json' -d '{"amount":"100.00"}')
C_APO_CAROL="${RESPOSTA##*$'\n'}"
APO_CAROL="${RESPOSTA%$'\n'*}"
check "carol aporta 100.00 (recebido $C_APO_CAROL)" \
    "$([ "$C_APO_CAROL" = "201" ] && echo true || echo false)"
MOV_APO_C=$(printf '%s' "$APO_CAROL" | mov_campo APORTE PROPOSTO "$CAROL_ID" id)

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/movimentos/$MOV_APO_C/confirmar" -H "$AUTH_A")
C_CONF_APO_C="${RESPOSTA##*$'\n'}"
check "dono confirma o aporte da carol: 200 (recebido $C_CONF_APO_C)" \
    "$([ "$C_CONF_APO_C" = "200" ] && echo true || echo false)"

DETALHE_A=$(curl -s "$BASE/caixinhas/$CAIXINHA" -H "$AUTH_A")
ARR=$(campo arrecadado <<<"$DETALHE_A")
check "arrecadado soma os dois membros: 700.00 (recebido ${ARR:-vazio})" \
    "$([ "$ARR" = "700.00" ] && echo true || echo false)"
PROG=$(campo progresso <<<"$DETALHE_A")
check "progresso é 0.7 (recebido ${PROG:-vazio})" \
    "$([ "$PROG" = "0.7" ] && echo true || echo false)"

echo
echo "membro vê só os próprios movimentos; dono vê todos"

DETALHE_B=$(curl -s "$BASE/caixinhas/$CAIXINHA" -H "$AUTH_B")
N_MOV_B=$(qtd_de movimentos <<<"$DETALHE_B")
check "bob vê só os 3 movimentos dele (recebido ${N_MOV_B:-vazio})" \
    "$([ "$N_MOV_B" = "3" ] && echo true || echo false)"
N_MEMB_B=$(qtd_de membros <<<"$DETALHE_B")
check "bob vê só ele na lista de membros (recebido ${N_MEMB_B:-vazio})" \
    "$([ "$N_MEMB_B" = "1" ] && echo true || echo false)"

DETALHE_C=$(curl -s "$BASE/caixinhas/$CAIXINHA" -H "$AUTH_C")
N_MOV_C=$(qtd_de movimentos <<<"$DETALHE_C")
check "carol vê só o movimento dela (recebido ${N_MOV_C:-vazio})" \
    "$([ "$N_MOV_C" = "1" ] && echo true || echo false)"
N_MEMB_C=$(qtd_de membros <<<"$DETALHE_C")
check "carol vê só ela na lista de membros (recebido ${N_MEMB_C:-vazio})" \
    "$([ "$N_MEMB_C" = "1" ] && echo true || echo false)"

N_MOV_A=$(qtd_de movimentos <<<"$DETALHE_A")
check "dono vê os 4 movimentos (recebido ${N_MOV_A:-vazio})" \
    "$([ "$N_MOV_A" = "4" ] && echo true || echo false)"
N_MEMB_A=$(qtd_de membros <<<"$DETALHE_A")
check "dono vê os 2 membros (recebido ${N_MEMB_A:-vazio})" \
    "$([ "$N_MEMB_A" = "2" ] && echo true || echo false)"

echo
echo "avaliação: uma por membro, 1 a 5, média com vírgula"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/avaliacao" -H "$AUTH_C" \
    -H 'content-type: application/json' -d '{"estrelas":6}')
C_AV_NOTA="${RESPOSTA##*$'\n'}"
check "nota 6 é 422 (recebido $C_AV_NOTA)" \
    "$([ "$C_AV_NOTA" = "422" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'Escolha de 1 a 5 estrelas.' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "Escolha de 1 a 5 estrelas." ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/avaliacao" -H "$AUTH_C" \
    -H 'content-type: application/json' -d '{"estrelas":4}')
C_AV_CAROL="${RESPOSTA##*$'\n'}"
check "carol avalia com 4: 200 (recebido $C_AV_CAROL)" \
    "$([ "$C_AV_CAROL" = "200" ] && echo true || echo false)"

MINHA_C=$(campo minha_avaliacao <<<"${RESPOSTA%$'\n'*}")
check "minha_avaliacao da carol é 4 (recebido ${MINHA_C:-vazio})" \
    "$([ "$MINHA_C" = "4" ] && echo true || echo false)"
MEDIA=$(campo media_avaliacoes <<<"${RESPOSTA%$'\n'*}")
check "média com uma casa e vírgula: 4,0 (recebido ${MEDIA:-vazio})" \
    "$([ "$MEDIA" = "4,0" ] && echo true || echo false)"
TOTAL=$(campo total_avaliacoes <<<"${RESPOSTA%$'\n'*}")
check "total de avaliações é 1 (recebido ${TOTAL:-vazio})" \
    "$([ "$TOTAL" = "1" ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/avaliacao" -H "$AUTH_C" \
    -H 'content-type: application/json' -d '{"estrelas":1}')
C_AV_2X="${RESPOSTA##*$'\n'}"
check "carol avalia de novo: 409 (recebido $C_AV_2X)" \
    "$([ "$C_AV_2X" = "409" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'Você já avaliou esta caixinha.' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "Você já avaliou esta caixinha." ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/avaliacao" -H "$AUTH_B" \
    -H 'content-type: application/json' -d '{"estrelas":5}')
C_AV_BOB="${RESPOSTA##*$'\n'}"
check "bob avalia com 5: 200 (recebido $C_AV_BOB)" \
    "$([ "$C_AV_BOB" = "200" ] && echo true || echo false)"
MEDIA=$(campo media_avaliacoes <<<"${RESPOSTA%$'\n'*}")
check "média dos dois é 4,5 (recebido ${MEDIA:-vazio})" \
    "$([ "$MEDIA" = "4,5" ] && echo true || echo false)"
TOTAL=$(campo total_avaliacoes <<<"${RESPOSTA%$'\n'*}")
check "total de avaliações é 2 (recebido ${TOTAL:-vazio})" \
    "$([ "$TOTAL" = "2" ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/avaliacao" -H "$AUTH_B" \
    -H 'content-type: application/json' -d '{"estrelas":4}')
C_AV_BOB_2X="${RESPOSTA##*$'\n'}"
check "bob avalia de novo: 409 (recebido $C_AV_BOB_2X)" \
    "$([ "$C_AV_BOB_2X" = "409" ] && echo true || echo false)"

echo
echo "telefone: só dígitos, com DDD — e aparece para quem pode ver"

RESPOSTA=$(req -X PATCH "$BASE/caixinhas/$CAIXINHA/meu-telefone" -H "$AUTH_C" \
    -H 'content-type: application/json' -d '{"telefone":"123"}')
C_FONE_INVALIDO="${RESPOSTA##*$'\n'}"
check "telefone inválido é 422 (recebido $C_FONE_INVALIDO)" \
    "$([ "$C_FONE_INVALIDO" = "422" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'Digite um telefone com DDD.' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "Digite um telefone com DDD." ] && echo true || echo false)"

RESPOSTA=$(req -X PATCH "$BASE/caixinhas/$CAIXINHA/meu-telefone" -H "$AUTH_C" \
    -H 'content-type: application/json' -d '{"telefone":"(11) 95555-3333"}')
C_FONE_CAROL="${RESPOSTA##*$'\n'}"
check "membro salva telefone formatado: 200 (recebido $C_FONE_CAROL)" \
    "$([ "$C_FONE_CAROL" = "200" ] && echo true || echo false)"

RESPOSTA=$(req -X PATCH "$BASE/caixinhas/$CAIXINHA/meu-telefone" -H "$AUTH_A" \
    -H 'content-type: application/json' -d '{"telefone":"11 94444-2222"}')
C_FONE_DONO="${RESPOSTA##*$'\n'}"
check "dono salva o próprio telefone: 200 (recebido $C_FONE_DONO)" \
    "$([ "$C_FONE_DONO" = "200" ] && echo true || echo false)"

DETALHE_A=$(curl -s "$BASE/caixinhas/$CAIXINHA" -H "$AUTH_A")
FONE_CAROL=$(campo membros.1.telefone <<<"$DETALHE_A")
check "dono vê o telefone da carol em dígitos: 11955553333 (recebido ${FONE_CAROL:-vazio})" \
    "$([ "$FONE_CAROL" = "11955553333" ] && echo true || echo false)"
FONE_DONO=$(campo dono.telefone <<<"$DETALHE_A")
check "telefone do dono atualizado em dígitos: 11944442222 (recebido ${FONE_DONO:-vazio})" \
    "$([ "$FONE_DONO" = "11944442222" ] && echo true || echo false)"

DETALHE_B=$(curl -s "$BASE/caixinhas/$CAIXINHA" -H "$AUTH_B")
FONE_B_VAZIO=$(campo membros.1.telefone <<<"$DETALHE_B")
check "bob não vê a linha da carol nem o telefone dela (recebido '${FONE_B_VAZIO:-vazio}')" \
    "$([ -z "$FONE_B_VAZIO" ] && echo true || echo false)"

echo
echo "movimento de outro não existe; resolver é só da contraparte"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/movimentos/$MOV_APO_C/confirmar" -H "$AUTH_B")
C_MOV_OUTRO="${RESPOSTA##*$'\n'}"
check "bob confirma movimento da carol: 404 (recebido $C_MOV_OUTRO)" \
    "$([ "$C_MOV_OUTRO" = "404" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'Movimento não encontrado.' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "Movimento não encontrado." ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/movimentos/00000000-0000-0000-0000-000000000000/confirmar" -H "$AUTH_A")
C_MOV_SUMIDO="${RESPOSTA##*$'\n'}"
check "movimento inexistente é 404 (recebido $C_MOV_SUMIDO)" \
    "$([ "$C_MOV_SUMIDO" = "404" ] && echo true || echo false)"

echo
echo "cancelar é de quem propôs; recusar é da contraparte"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/aportes" -H "$AUTH_B" \
    -H 'content-type: application/json' -d '{"amount":"50.00"}')
Aporte2="${RESPOSTA%$'\n'*}"
MOV_APO2=$(printf '%s' "$Aporte2" | mov_campo APORTE PROPOSTO "$BOB_ID" id)
check "bob propõe aporte de 50 (recebido ${RESPOSTA##*$'\n'})" \
    "$([ "${RESPOSTA##*$'\n'}" = "201" ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/movimentos/$MOV_APO2/cancelar" -H "$AUTH_A")
C_CANCEL_ERRADO="${RESPOSTA##*$'\n'}"
check "dono cancela proposta do bob: 409 (recebido $C_CANCEL_ERRADO)" \
    "$([ "$C_CANCEL_ERRADO" = "409" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'Só quem propôs pode cancelar.' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "Só quem propôs pode cancelar." ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/movimentos/$MOV_APO2/cancelar" -H "$AUTH_B")
C_CANCEL="${RESPOSTA##*$'\n'}"
check "bob cancela a própria proposta: 200 (recebido $C_CANCEL)" \
    "$([ "$C_CANCEL" = "200" ] && echo true || echo false)"
STATUS_CANCELADO=$(campo movimentos.0.status <<<"${RESPOSTA%$'\n'*}")
check "movimento vira CANCELADO (recebido ${STATUS_CANCELADO:-vazio})" \
    "$([ "$STATUS_CANCELADO" = "CANCELADO" ] && echo true || echo false)"
PEND=$(campo arrecadado_pendente <<<"${RESPOSTA%$'\n'*}")
check "cancelado não fica pendente (recebido ${PEND:-vazio})" \
    "$([ "$PEND" = "0.00" ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/aportes" -H "$AUTH_B" \
    -H 'content-type: application/json' -d '{"amount":"50.00"}')
Aporte3="${RESPOSTA%$'\n'*}"
MOV_APO3=$(printf '%s' "$Aporte3" | mov_campo APORTE PROPOSTO "$BOB_ID" id)

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/movimentos/$MOV_APO3/recusar" -H "$AUTH_A")
C_RECUSA="${RESPOSTA##*$'\n'}"
check "dono recusa o aporte: 200 (recebido $C_RECUSA)" \
    "$([ "$C_RECUSA" = "200" ] && echo true || echo false)"
ARR=$(campo arrecadado <<<"${RESPOSTA%$'\n'*}")
check "recusado não entra na conta: arrecadado segue 700.00 (recebido ${ARR:-vazio})" \
    "$([ "$ARR" = "700.00" ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/movimentos/$MOV_APO3/recusar" -H "$AUTH_B")
C_RECUSA_ERRADA="${RESPOSTA##*$'\n'}"
check "quem propôs não recusa: 409 (recebido $C_RECUSA_ERRADA)" \
    "$([ "$C_RECUSA_ERRADA" = "409" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'Só a outra parte pode recusar.' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "Só a outra parte pode recusar." ] && echo true || echo false)"

echo
echo "movimento resolvido não se resolve de novo"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/movimentos/$MOV_APO3/confirmar" -H "$AUTH_A")
C_CONF_RECUSADO="${RESPOSTA##*$'\n'}"
check "confirmar movimento RECUSADO: 409 (recebido $C_CONF_RECUSADO)" \
    "$([ "$C_CONF_RECUSADO" = "409" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'Este movimento já foi resolvido.' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "Este movimento já foi resolvido." ] && echo true || echo false)"

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/movimentos/$MOV_APO2/cancelar" -H "$AUTH_B")
C_CANCEL_2X="${RESPOSTA##*$'\n'}"
check "cancelar movimento CANCELADO: 409 (recebido $C_CANCEL_2X)" \
    "$([ "$C_CANCEL_2X" = "409" ] && echo true || echo false)"

echo
echo "pedido cheio pelas propostas: novo aporte não cabe mais"

# O espaço restante (1000 − 700 arrecadado) é 300; a proposta da carol ocupa
# tudo e o próximo aporte bate no 422 de pedido alcançado — e não no "Faltam".
# (O 409 de revalidação no confirm é rede de segurança contra concorrência e
# escrita direta: com a proposta já descontando pendentes, ele não ocorre no
# fluxo sequencial da API.)
RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/aportes" -H "$AUTH_C" \
    -H 'content-type: application/json' -d '{"amount":"300.00"}')
C_APO_LOTADO="${RESPOSTA##*$'\n'}"
check "carol ocupa o espaço restante: 201 (recebido $C_APO_LOTADO)" \
    "$([ "$C_APO_LOTADO" = "201" ] && echo true || echo false)"
APO_C2="${RESPOSTA%$'\n'*}"
MOV_C2=$(printf '%s' "$APO_C2" | mov_campo APORTE PROPOSTO "$CAROL_ID" id)

RESPOSTA=$(req -X POST "$BASE/caixinhas/$CAIXINHA/aportes" -H "$AUTH_B" \
    -H 'content-type: application/json' -d '{"amount":"50.00"}')
C_APO_CHEIO="${RESPOSTA##*$'\n'}"
check "aporte com pedido cheio é 422 (recebido $C_APO_CHEIO)" \
    "$([ "$C_APO_CHEIO" = "422" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é 'O valor pedido já foi alcançado.' (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "O valor pedido já foi alcançado." ] && echo true || echo false)"

curl -s -o /dev/null -X POST "$BASE/caixinhas/$CAIXINHA/movimentos/$MOV_C2/cancelar" -H "$AUTH_C"
echo
echo "vitrine: só a pública aparece — com entrou e contato do dono"

VITRINE_D=$(curl -s "$BASE/caixinhas/publicas" -H "$AUTH_D")
N_VITRINE=$(qtd_de '' <<<"$VITRINE_D")
check "dave vê 1 caixinha pública, sem a não-pública (recebido ${N_VITRINE:-vazio})" \
    "$([ "$N_VITRINE" = "1" ] && echo true || echo false)"

DONO_NOME=$(acha "Viagem da família" dono_nome <<<"$VITRINE_D")
check "vitrine mostra o nome do dono: Alice Silva (recebido ${DONO_NOME:-vazio})" \
    "$([ "$DONO_NOME" = "Alice Silva" ] && echo true || echo false)"

TAXA_V=$(acha "Viagem da família" taxa_mensal <<<"$VITRINE_D")
check "taxa da vitrine é 3 (recebido ${TAXA_V:-vazio})" \
    "$([ "$TAXA_V" = "3" ] && echo true || echo false)"

PEDIDO_V=$(acha "Viagem da família" valor_pedido <<<"$VITRINE_D")
check "valor pedido na vitrine: 1000.00 (recebido ${PEDIDO_V:-vazio})" \
    "$([ "$PEDIDO_V" = "1000.00" ] && echo true || echo false)"

ARR_V=$(acha "Viagem da família" arrecadado <<<"$VITRINE_D")
check "arrecadado na vitrine: 700.00 (recebido ${ARR_V:-vazio})" \
    "$([ "$ARR_V" = "700.00" ] && echo true || echo false)"

FALTA_V=$(acha "Viagem da família" falta <<<"$VITRINE_D")
check "falta na vitrine: 300.00 (recebido ${FALTA_V:-vazio})" \
    "$([ "$FALTA_V" = "300.00" ] && echo true || echo false)"

MEDIA_V=$(acha "Viagem da família" media_avaliacoes <<<"$VITRINE_D")
check "média na vitrine: 4,5 (recebido ${MEDIA_V:-vazio})" \
    "$([ "$MEDIA_V" = "4,5" ] && echo true || echo false)"

TOTAL_V=$(acha "Viagem da família" total_avaliacoes <<<"$VITRINE_D")
check "total de avaliações na vitrine: 2 (recebido ${TOTAL_V:-vazio})" \
    "$([ "$TOTAL_V" = "2" ] && echo true || echo false)"

FONE_V=$(acha "Viagem da família" telefone_dono <<<"$VITRINE_D")
check "telefone do dono na vitrine: 11944442222 (recebido ${FONE_V:-vazio})" \
    "$([ "$FONE_V" = "11944442222" ] && echo true || echo false)"

ENTROU_D=$(acha "Viagem da família" entrou <<<"$VITRINE_D")
check "dave ainda não entrou: False (recebido ${ENTROU_D:-vazio})" \
    "$([ "$ENTROU_D" = "False" ] && echo true || echo false)"

VITRINE_A=$(curl -s "$BASE/caixinhas/publicas" -H "$AUTH_A")
ENTROU_A=$(acha "Viagem da família" entrou <<<"$VITRINE_A")
check "para a alice (dono) entrou é True (recebido ${ENTROU_A:-vazio})" \
    "$([ "$ENTROU_A" = "True" ] && echo true || echo false)"

VITRINE_B=$(curl -s "$BASE/caixinhas/publicas" -H "$AUTH_B")
ENTROU_B=$(acha "Viagem da família" entrou <<<"$VITRINE_B")
check "para o bob (membro) entrou é True (recebido ${ENTROU_B:-vazio})" \
    "$([ "$ENTROU_B" = "True" ] && echo true || echo false)"

echo
echo "minhas caixinhas: cada um vê as suas, com o papel certo"

MINHAS_A=$(curl -s "$BASE/caixinhas" -H "$AUTH_A")
N_MINHAS_A=$(qtd_de '' <<<"$MINHAS_A")
check "alice tem 2 caixinhas (recebido ${N_MINHAS_A:-vazio})" \
    "$([ "$N_MINHAS_A" = "2" ] && echo true || echo false)"
PAPEL_VIAGEM=$(acha "Viagem da família" papel <<<"$MINHAS_A")
PAPEL_RESERVA=$(acha "Caixa reserva" papel <<<"$MINHAS_A")
check "alice é dono das duas (recebido $PAPEL_VIAGEM/$PAPEL_RESERVA)" \
    "$([ "$PAPEL_VIAGEM" = "dono" ] && [ "$PAPEL_RESERVA" = "dono" ] && echo true || echo false)"

MINHAS_B=$(curl -s "$BASE/caixinhas" -H "$AUTH_B")
N_MINHAS_B=$(qtd_de '' <<<"$MINHAS_B")
check "bob tem 1 caixinha (recebido ${N_MINHAS_B:-vazio})" \
    "$([ "$N_MINHAS_B" = "1" ] && echo true || echo false)"
PAPEL_B2=$(acha "Viagem da família" papel <<<"$MINHAS_B")
ARR_B2=$(acha "Viagem da família" arrecadado <<<"$MINHAS_B")
PROG_B2=$(acha "Viagem da família" progresso <<<"$MINHAS_B")
MEDIA_B2=$(acha "Viagem da família" media_avaliacoes <<<"$MINHAS_B")
TAXA_B2=$(acha "Viagem da família" taxa_mensal <<<"$MINHAS_B")
check "card do bob: membro, 700.00, 0.7, 4,5, 3 (recebido $PAPEL_B2/$ARR_B2/$PROG_B2/$MEDIA_B2/$TAXA_B2)" \
    "$([ "$PAPEL_B2" = "membro" ] && [ "$ARR_B2" = "700.00" ] && [ "$PROG_B2" = "0.7" ] \
        && [ "$MEDIA_B2" = "4,5" ] && [ "$TAXA_B2" = "3" ] && echo true || echo false)"

MINHAS_D=$(curl -s "$BASE/caixinhas" -H "$AUTH_D")
N_MINHAS_D=$(qtd_de '' <<<"$MINHAS_D")
check "dave não tem caixinha nenhuma (recebido ${N_MINHAS_D:-vazio})" \
    "$([ "$N_MINHAS_D" = "0" ] && echo true || echo false)"

echo
echo "mês inválido na query: 422 com formato esperado"

RESPOSTA=$(req "$BASE/caixinhas/$CAIXINHA?mes=2026-13" -H "$AUTH_A")
C_MES_ERRO="${RESPOSTA##*$'\n'}"
check "mes=2026-13 é 422 (recebido $C_MES_ERRO)" \
    "$([ "$C_MES_ERRO" = "422" ] && echo true || echo false)"
MSG=$(campo message <<<"${RESPOSTA%$'\n'*}")
check "mensagem é \"mês inválido: '2026-13'. Use o formato YYYY-MM.\" (recebido ${MSG:-vazio})" \
    "$([ "$MSG" = "mês inválido: '2026-13'. Use o formato YYYY-MM." ] && echo true || echo false)"

echo
echo "derivações do mês: base começa no mês seguinte; devolução total zera"

DETALHE_A=$(curl -s "$BASE/caixinhas/$CAIXINHA" -H "$AUTH_A")
T_DEVIDO_AGORA=$(campo total_devido_mes <<<"$DETALHE_A")
T_PAGO_AGORA=$(campo total_pago_mes <<<"$DETALHE_A")
T_FALTA_AGORA=$(campo total_falta_mes <<<"$DETALHE_A")
check "mês corrente: nada é devido ainda (recebido $T_DEVIDO_AGORA/$T_PAGO_AGORA/$T_FALTA_AGORA)" \
    "$([ "$T_DEVIDO_AGORA" = "0.00" ] && [ "$T_PAGO_AGORA" = "0.00" ] && [ "$T_FALTA_AGORA" = "0.00" ] && echo true || echo false)"

DETALHE_PROX=$(curl -s "$BASE/caixinhas/$CAIXINHA?mes=$MES_PROX" -H "$AUTH_A")
DEVIDO_B=$(campo membros.0.devido_mes <<<"$DETALHE_PROX")
PAGO_B=$(campo membros.0.pago_mes <<<"$DETALHE_PROX")
FALTA_B=$(campo membros.0.falta_mes <<<"$DETALHE_PROX")
check "bob: devolução total zerou a base (recebido $DEVIDO_B/$PAGO_B/$FALTA_B)" \
    "$([ "$DEVIDO_B" = "0.00" ] && [ "$PAGO_B" = "18.00" ] && [ "$FALTA_B" = "0.00" ] && echo true || echo false)"

DEVIDO_C=$(campo membros.1.devido_mes <<<"$DETALHE_PROX")
PAGO_C=$(campo membros.1.pago_mes <<<"$DETALHE_PROX")
FALTA_C=$(campo membros.1.falta_mes <<<"$DETALHE_PROX")
DISP_C=$(campo membros.1.rendimento_disponivel <<<"$DETALHE_PROX")
PEND_C=$(campo membros.1.rendimento_pendente <<<"$DETALHE_PROX")
check "carol: 100 a 3% → devido 3.00, falta 3.00, disponível 3.00 (recebido $DEVIDO_C/$PAGO_C/$FALTA_C/$DISP_C/$PEND_C)" \
    "$([ "$DEVIDO_C" = "3.00" ] && [ "$PAGO_C" = "0.00" ] && [ "$FALTA_C" = "3.00" ] \
        && [ "$DISP_C" = "3.00" ] && [ "$PEND_C" = "False" ] && echo true || echo false)"

T_DEVIDO=$(campo total_devido_mes <<<"$DETALHE_PROX")
T_PAGO=$(campo total_pago_mes <<<"$DETALHE_PROX")
T_FALTA=$(campo total_falta_mes <<<"$DETALHE_PROX")
check "totais do mês seguinte: 3.00/18.00/0.00 (recebido $T_DEVIDO/$T_PAGO/$T_FALTA)" \
    "$([ "$T_DEVIDO" = "3.00" ] && [ "$T_PAGO" = "18.00" ] && [ "$T_FALTA" = "0.00" ] && echo true || echo false)"

echo
echo "fora do ledger: nenhum saldo mudou do começo ao fim"

S9_A=$(saldo "$AUTH_A"); S9_B=$(saldo "$AUTH_B"); S9_C=$(saldo "$AUTH_C"); S9_D=$(saldo "$AUTH_D")
check "saldo final da alice é 0.00 (recebido $S9_A)" \
    "$([ "$S9_A" = "0.00" ] && echo true || echo false)"
check "saldo final do bob é 0.00 (recebido $S9_B)" \
    "$([ "$S9_B" = "0.00" ] && echo true || echo false)"
check "saldo final da carol é 0.00 (recebido $S9_C)" \
    "$([ "$S9_C" = "0.00" ] && echo true || echo false)"
check "saldo final do dave é 0.00 (recebido $S9_D)" \
    "$([ "$S9_D" = "0.00" ] && echo true || echo false)"

echo
echo "resultado: $pass passaram, $fail falharam"
[ "$fail" -eq 0 ]
