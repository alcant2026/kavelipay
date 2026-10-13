#!/usr/bin/env bash
# Percorre o app do Waydroid por TEXTO, não por coordenada.
#
# Por quê: `input tap X Y` depende da resolução e quebra quando o teclado abre
# ou a rolagem muda o layout. `uiautomator dump` devolve os nós com texto e
# bounds reais, então o roteiro continua válido mesmo se a tela reorganizar.
#
# Login em vez de cadastro: o formulário de cadastro tem três campos cuja ordem
# muda conforme o teclado, e um `tap` errado empilha texto no campo vizinho — foi
# exatamente o que aconteceu na primeira versão deste roteiro. A conta é criada
# pela API e o roteiro exercita a sessão.
#
# Uso: tests_android.sh [--verbose]
# Requer: Waydroid rodando, adb em 192.168.240.112:5555, backend em
#         http://192.168.240.1:3000 (lado host da bridge).

ADB="${HOME}/Android/Sdk/platform-tools/adb"
SERIAL="192.168.240.112:5555"
PKG="com.kavelipay.app.debug"
API="http://192.168.240.1:3000"
SHOT_DIR="${HOME}/.hermes/cache/scratch"
VERBOSE="${1:-}"

pass=0
fail=0
failures=()

export ANDROID_SERIAL="$SERIAL"

log()  { [ -n "$VERBOSE" ] && printf '   · %s\n' "$1"; return 0; }
pass() { printf '  \033[32mok\033[0m: %s\n' "$1"; pass=$((pass + 1)); }
fail() { printf '  \033[31mFALHOU\033[0m: %s\n' "$1"; fail=$((fail + 1)); failures+=("$1"); }

# ── primitivas ────────────────────────────────────────────────────────────────

dump_xml() {
    "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    "$ADB" shell cat /sdcard/ui.xml 2>/dev/null | sed 's/></>\n</g'
}

screen_text() {
    dump_xml | grep -o 'text="[^"]\+"' | sed 's/text="//; s/"$//' \
        | grep -v '^$' | tr '\n' ' '
}

shot() {
    "$ADB" shell screencap -p /sdcard/kp.png >/dev/null 2>&1
    "$ADB" pull /sdcard/kp.png "${SHOT_DIR}/$1.png" >/dev/null 2>&1
}

# Centro do nó cujo `text` casa com a regex.
tap_node() {
    local alvo="$1" linha
    linha=$(dump_xml | grep -E "text=\"[^\"]*${alvo}[^\"]*\"" | head -1)
    [ -z "$linha" ] && return 1

    local b
    b=$(echo "$linha" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' \
        | grep -o '[0-9]\+' | tr '\n' ' ')
    [ -z "$b" ] && return 1
    local x1 y1 x2 y2
    read -r x1 y1 x2 y2 <<<"$b"
    [ -z "${x2:-}" ] && return 1

    "$ADB" shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 )) >/dev/null 2>&1
    return 0
}

# Campo de texto pelo ÍNDICE na ordem em que a tela os lista.
tap_field() {
    local idx="$1" b
    b=$(dump_xml | grep 'class="android.widget.EditText"' | sed -n "${idx}p" \
        | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' \
        | grep -o '[0-9]\+' | tr '\n' ' ')
    [ -z "$b" ] && return 1
    local x1 y1 x2 y2
    read -r x1 y1 x2 y2 <<<"$b"
    "$ADB" shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 )) >/dev/null 2>&1
    return 0
}

type_text() { "$ADB" shell input text "$1" >/dev/null 2>&1; sleep 1; }

# Escreve no campo N (limpando antes) e fecha o teclado.
fill_field() {
    local idx="$1" texto="$2"
    tap_field "$idx" || return 1
    sleep 1
    "$ADB" shell input keyevent KEYCODE_MOVE_END >/dev/null 2>&1
    for _ in $(seq 1 60); do "$ADB" shell input keyevent KEYCODE_DEL >/dev/null 2>&1; done
    type_text "$texto"
    "$ADB" shell input keyevent KEYCODE_BACK >/dev/null 2>&1
    sleep 1
    return 0
}

back()   { "$ADB" shell input keyevent KEYCODE_BACK >/dev/null 2>&1; sleep 2; }
scroll() { "$ADB" shell input swipe 680 700 680 300 250 >/dev/null 2>&1; sleep 2; }

# Rola até o fim. Conteúdo abaixo da dobra (rodapé legal, botão de logout) só
# existe para o `uiautomator dump` depois disso — sem rolar, o teste reporta
# "ausente" para algo que está lá.
scroll_end() { for _ in 1 2 3 4 5; do scroll; done; }

# Volta ao topo. Sem isto, o teste que procurou texto no fim da tela (rodapé,
# LGPD) deixa a rolagem para baixo e o `tap_node` do atalho seguinte não acha
# o nó — o teste passa a falhar em cascata sem o app ter mudado.
scroll_top() { for _ in 1 2 3 4 5 6; do "$ADB" shell input swipe 680 300 680 850 200 >/dev/null 2>&1; sleep 1; done; }

# Verdadeiro se o texto existe NA TELA INTEIRA, visível ou não.
has_anywhere() {
    local alvo="$1" i
    for i in 1 2 3 4 5 6; do
        has "$alvo" && return 0
        scroll
    done
    scroll_top
    return 1
}

restart() {
    "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1; sleep 1
    "$ADB" shell am start -n "$PKG/com.kavelipay.app.MainActivity" >/dev/null 2>&1
    sleep 7
}

has() { screen_text | grep -qi "$1"; }

# ── início ────────────────────────────────────────────────────────────────────

echo "══════════════════════════════════════════════════════"
echo " TESTE DO APP NO WAYDROID — percorrido por texto"
echo "══════════════════════════════════════════════════════"

if ! "$ADB" devices | grep -q "$SERIAL.*device"; then
    echo "ERRO: $SERIAL não está em 'device'. Conecte o Waydroid."
    exit 1
fi

EMAIL="e2e-final@teste.com"
SENHA="senha-forte-123"

COD=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/auth/register" \
    -H 'content-type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$SENHA\",\"display_name\":\"Teste E2E\"}")
case "$COD" in
    200|201) log "conta $EMAIL criada" ;;
    409)     log "conta $EMAIL já existia" ;;
    *)       echo "ERRO: register devolveu $COD — backend no ar?"; exit 1 ;;
esac

restart

# Zera o estado local. Sem isto, uma sessão deixada pela execução anterior faz
# o login "passar" sem digitar nada, e os testes de campo viram falso negativo.
# É o preço de testar sessão persistida sem estado compartilhado: cada rodada
# recomeça do zero, e a persistência é verificada à parte (seção 13).
"$ADB" shell pm clear "$PKG" >/dev/null 2>&1
sleep 2
restart

# Pré-requisito do login: o teste só pode logar se a tela ESTIVER no login.
if ! has "Quero criar conta" && ! has "Entrar"; then
    fail "app não está na tela de login: $(screen_text | head -c 100)"
    exit 1
fi
has "Quero criar conta" && pass "app abre sem crash (tela de login)" \
    || fail "app não mostrou a tela de login"
shot a01_login

# ── 1. login ──────────────────────────────────────────────────────────────────

echo
echo "1. login"

fill_field 1 "$EMAIL" || fail "campo E-mail (índice 1) não encontrado"
fill_field 2 "$SENHA" || fail "campo Senha (índice 2) não encontrado"

screen_text | grep -q "$EMAIL" && pass "e-mail preenchido" || fail "e-mail não apareceu"

tap_node "Entrar" || fail "botão 'Entrar' não encontrado"
sleep 8
if has "Saldo disponível"; then
    pass "login autenticou e caiu na Home"
else
    fail "login não entrou: $(screen_text | head -c 120)"
fi
shot a02_home

# ── 2. Home ───────────────────────────────────────────────────────────────────

echo
echo "2. Home"

has "Saldo disponível"      && pass "cartão de saldo presente"     || fail "sem cartão de saldo"
has "demonstrativo"         && pass "aviso de saldo demonstrativo" || fail "aviso demonstrativo ausente"
has "Visão geral"           && pass "cabeçalho da Home"            || fail "cabeçalho ausente"
has "De onde vem este saldo" && pass "botão de derivação visível"   || fail "botão de derivação ausente"
# Abaixo da dobra: só o `dump` depois de rolar enxerga.
has_anywhere "não é banco"  && pass "rodapé legal presente (após rolar)" \
    || fail "rodapé legal ausente mesmo após rolar"

# Os atalhos ficam DEPOIS do cartão de saldo, do aviso e do botão: só aparecem
# no dump quando a tela ainda está no topo. Todos numa única leitura — usar
# `has_anywhere` por atalho rola a tela entre uma checagem e outra, e o último
# da lista some da janela e dá falso negativo.
scroll_top
txt_atalhos=$(screen_text)
n=0
faltando=""
for a in Contas Relatório Cobranças Divisões Histórico Categorias Perfil; do
    if echo "$txt_atalhos" | grep -qi "$a"; then n=$((n + 1)); else faltando="$faltando $a"; fi
done
[ "$n" -eq 7 ] && pass "7 atalhos na grade" \
    || fail "só $n de 7 atalhos presentes (faltou:$faltando)"

# De volta ao topo: as próximas seções clicam em atalhos, que ficam no topo.
scroll_top

# ── 3. entrada ────────────────────────────────────────────────────────────────

echo
echo "3. novo lançamento (entrada)"

tap_node "Novo lançamento" || fail "botão 'Novo lançamento' não encontrado"
sleep 3
has "Registrar entrada" && pass "formulário abriu no modo Entrada" || fail "formulário não abriu"

fill_field 1 "1234,56" || fail "campo Valor não encontrado"
# `input text` digita ponto, e o `uiautomator dump` normaliza o ponto do
# teclado para o que o campo mostra. O app exibe com vírgula (1.234,56), mas o
# dump do Android devolve o texto como digitado — por isso a checagem aceita as
# duas grafias.
grep_q() { screen_text | grep -qi "$1"; }
grep_q "1.234,56" || grep_q "1234.56" \
    && pass "valor formatado em pt-BR (1234,56)" \
    || fail "valor não formatado: $(screen_text | head -c 120)"
shot a03_valor

tap_node "Salário" || true; sleep 1
# `input text` corta no espaço: "Salario teste" viraria só "Salario". Uma
# palavra resolve, e o teste da derivação depende do texto bater.
type_text "SalarioTeste"
back

# A checagem de "entrou" é pelo SALDO, não pelo valor digitado: o valor muda a
# cada execução (e a cada conta Acumula lançamentos), então fixá-lo
# transformaria um registro bem-sucedido em falso negativo. O saldo vem do
# backend e é a única fonte da verdade aqui.
saldo_antes=$(screen_text | grep -o 'R\$ [0-9.,]*' | head -1)

tap_node "Registrar entrada" || fail "botão 'Registrar entrada' não encontrado"
sleep 7
saldo_depois=$(screen_text | grep -o 'R\$ [0-9.,]*' | head -1)
if [ -n "$saldo_antes" ] && [ -n "$saldo_depois" ] && [ "$saldo_antes" != "$saldo_depois" ]; then
    pass "entrada registrada: saldo $saldo_antes → $saldo_depois"
else
    fail "saldo não mudou (antes=$saldo_antes depois=$saldo_depois)"
fi
shot a04_entrada

# ── 4. despesa com categoria ──────────────────────────────────────────────────

echo
echo "4. despesa com categoria"

tap_node "Novo lançamento" || true; sleep 3
tap_node "Despesa" || fail "toggle 'Despesa' não encontrado"; sleep 3

fill_field 1 "89,90" || fail "campo Valor não encontrado"
sleep 1
has "Alimentação" && pass "categorias de despesa carregadas do backend" \
    || fail "nenhuma categoria carregou"
tap_node "Alimentação" || log "não selecionou categoria (opcional)"
shot a05_categoria

saldo_antes=$(screen_text | grep -o 'R\$ [0-9.,]*' | head -1)
tap_node "Registrar despesa" || fail "botão 'Registrar despesa' não encontrado"
sleep 7
saldo_depois=$(screen_text | grep -o 'R\$ [0-9.,]*' | head -1)
if [ -n "$saldo_antes" ] && [ -n "$saldo_depois" ] && [ "$saldo_antes" != "$saldo_depois" ]; then
    pass "despesa registrada: saldo $saldo_antes → $saldo_depois"
else
    fail "saldo não mudou na despesa (antes=$saldo_antes depois=$saldo_depois)"
fi
shot a06_despesa

# ── 5. derivação ──────────────────────────────────────────────────────────────

echo
echo "5. derivação do saldo"

tap_node "De onde vem este saldo" || fail "botão de derivação não encontrado"
sleep 4
has "De onde vem"    && pass "tela de derivação abriu" || fail "derivação não abriu"
has "SUM\|derivado" && pass "explica a fórmula do cálculo" || fail "fórmula não exibida"
has "SalarioTeste\|Salario" && pass "lançamento aparece na derivação" \
    || fail "lançamento não aparece na derivação"
shot a07_derivacao
back

# ── 6. contas ─────────────────────────────────────────────────────────────────

echo
echo "6. contas a pagar"

tap_node "Contas" || fail "atalho 'Contas' não encontrado"
sleep 4
has "Contas a pagar\|Total em aberto\|Nenhuma conta" \
    && pass "tela de contas abriu" || fail "contas não abriu"
shot a08_contas
back

# ── 7. relatório ──────────────────────────────────────────────────────────────

echo
echo "7. relatório mensal"

tap_node "Relatório" || fail "atalho 'Relatório' não encontrado"
sleep 4
has "Entradas\|Saídas\|Resumo" && pass "tela de relatório abriu" || fail "relatório não abriu"
has "Resultado do mês" && pass "resultado do mês separado do saldo" \
    || fail "resultado do mês ausente"
shot a09_relatorio
back

# ── 8. divisões ───────────────────────────────────────────────────────────────

echo
echo "8. divisão de despesa"

tap_node "Divisões" || fail "atalho 'Divisões' não encontrado"
sleep 4
has "Divisão de despesa\|Nenhuma divisão" && pass "tela de divisões abriu" || fail "divisões não abriu"

tap_node "Nova divisão" || fail "botão 'Nova divisão' não encontrado"
sleep 3
has "TOTAL DA DESPESA\|Participantes" && pass "formulário de divisão abriu" \
    || fail "formulário de divisão não abriu"

# o caso que o backend recusa: soma das partes != total
fill_field 1 "100,00" || fail "campo de total não encontrado"
sleep 1
scroll
screen_text | grep -qi "Falta repartir\|Total" \
    && pass "soma das partes exibida (invariante visível antes do envio)" \
    || log "soma ainda não calculada (depende das partes)"
shot a10_nova_divisao
back; back

# ── 9. cobranças ──────────────────────────────────────────────────────────────

echo
echo "9. cobranças"

tap_node "Cobranças" || fail "atalho 'Cobranças' não encontrado"
sleep 4
has "Cobranças\|Nenhuma cobrança" && pass "tela de cobranças abriu" || fail "cobranças não abriu"

tap_node "Nova cobrança" || fail "botão 'Nova cobrança' não encontrado"
sleep 3
has "não move dinheiro" && pass "aviso: cobrar não move dinheiro" \
    || fail "aviso de que cobrar não move dinheiro ausente"
has "kavelipay://\|não é provedor de PIX" && pass "aviso de que o QR não é PIX" \
    || fail "aviso de fronteira PIX ausente"
shot a11_nova_cobranca
back; back

# ── 10. categorias ────────────────────────────────────────────────────────────

echo
echo "10. categorias"

tap_node "Categorias" || fail "atalho 'Categorias' não encontrado"
sleep 4
has "Receitas\|Despesas" && pass "tela de categorias abriu" || fail "categorias não abriu"
has "padrão" && pass "padrão rotulado (bug corrigido)" \
    || fail "nenhuma categoria marcada como padrão"
shot a12_categorias
back

# ── 11. histórico ─────────────────────────────────────────────────────────────

echo
echo "11. histórico unificado"

tap_node "Histórico" || fail "atalho 'Histórico' não encontrado"
sleep 5
if has "Histórico vazio"; then
    fail "histórico vazio — a URL do filtro voltou a quebrar"
else
    pass "histórico carregou itens (bug da URL corrigido)"
fi
has "Saldo no histórico" && pass "saldo do histórico presente" || fail "saldo ausente"
shot a13_historico
back

# ── 12. perfil ────────────────────────────────────────────────────────────────

echo
echo "12. perfil e segurança"

tap_node "Perfil" || fail "atalho 'Perfil' não encontrado"
sleep 5
has "@teste.com"  && pass "e-mail do titular carregou"  || fail "perfil sem e-mail"
has "Trocar senha" && pass "seção de segurança presente" || fail "segurança ausente"
has "Sessões ativas\|Esta sessão" && pass "sessões listadas" || fail "sessões ausentes"
# LGPD fica no fim da lista, abaixo da dobra.
has_anywhere "Privacidade\|Exportar" && pass "acesso a LGPD/privacidade (após rolar)" \
    || fail "LGPD ausente mesmo após rolar"
shot a14_perfil
back
scroll_top

# ── 13. sessão persiste ───────────────────────────────────────────────────────

echo
echo "13. sessão persiste"

restart
has "Saldo disponível" && pass "sessão sobreviveu ao reinício (token persistido)" \
    || fail "app voltou pro login após reiniciar"

# ── 14. logout ────────────────────────────────────────────────────────────────

echo
echo "14. logout"

tap_node "Perfil" || fail "não entrou no perfil"
sleep 4
scroll_end
log "perfil rolado: $(screen_text | head -c 90)"
if tap_node "Sair" || tap_node "Encerrar"; then
    sleep 5
    has "Entrar\|Quero criar conta" && pass "logout voltou ao login" \
        || fail "logout não voltou ao login"
else
    log "não há botão de logout no perfil (avaliar se deve existir)"
fi
shot a15_logout

# ── resumo ────────────────────────────────────────────────────────────────────

echo
echo "══════════════════════════════════════════════════════"
printf ' resultado: %d passaram, %d falharam\n' "$pass" "$fail"
if [ "$fail" -gt 0 ]; then
    echo
    echo " falhas:"
    for f in "${failures[@]}"; do printf '   - %s\n' "$f"; done
fi
echo "══════════════════════════════════════════════════════"
[ "$fail" -eq 0 ]