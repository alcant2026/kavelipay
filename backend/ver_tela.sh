#!/usr/bin/env bash
# Vai até uma tela pelo nome do atalho e tira a foto. Utilitário de inspeção
# visual: as correções de layout precisam de screenshot, não de "tem o texto".
#
# Uso: ver_tela.sh <Atalho> <nome-arquivo> [roladas]

ADB="${HOME}/Android/Sdk/platform-tools/adb"
PKG="com.kavelipay.app.debug"
ATalho="${1:?uso: ver_tela.sh <Atalho> <arquivo.png>}"
Nome="${2:?uso: ver_tela.sh <Atalho> <arquivo.png>}"
Roladas="${3:-0}"
API="http://192.168.240.1:3000"
EMAIL="inv2@teste.com"
SENHA="senha-forte-123"

export ANDROID_SERIAL="192.168.240.112:5555"

dump() {
    "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    "$ADB" shell cat /sdcard/ui.xml 2>/dev/null | sed 's/></>\n</g'
}
texto() { dump | grep -o 'text="[^"]\+"' | sed 's/text="//; s/"$//' | grep -v '^$' | tr '\n' ' '; }

tap() {
    local alvo="$1" linha b
    linha=$(dump | grep -E "text=\"[^\"]*${alvo}[^\"]*\"" | head -1)
    [ -z "$linha" ] && return 1
    b=$(echo "$linha" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]\+' | tr '\n' ' ')
    [ -z "$b" ] && return 1
    local x1 y1 x2 y2
    read -r x1 y1 x2 y2 <<<"$b"
    "$ADB" shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 )) >/dev/null 2>&1
    return 0
}

# Igual a `tap`, mas exige o texto EXATO. `tap "Perfil"` casa "Perfil" e também
# qualquer rótulo que o contenha; numa lista de atalhos isso abre a tela errada
# em silêncio.
tap_exato() {
    local alvo="$1" linha b
    linha=$(dump | grep -E "text=\"${alvo}\"" | head -1)
    [ -z "$linha" ] && return 1
    b=$(echo "$linha" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]\+' | tr '\n' ' ')
    [ -z "$b" ] && return 1
    local x1 y1 x2 y2
    read -r x1 y1 x2 y2 <<<"$b"
    "$ADB" shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 )) >/dev/null 2>&1
    return 0
}

campo() {
    local idx="$1" texto="$2" b
    b=$(dump | grep 'class="android.widget.EditText"' | sed -n "${idx}p" \
        | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]\+' | tr '\n' ' ')
    [ -z "$b" ] && return 1
    local x1 y1 x2 y2
    read -r x1 y1 x2 y2 <<<"$b"
    "$ADB" shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 )) >/dev/null 2>&1
    sleep 1
    "$ADB" shell input text "$texto" >/dev/null 2>&1
    sleep 1
    "$ADB" shell input keyevent KEYCODE_BACK >/dev/null 2>&1
    sleep 1
}

curl -s -o /dev/null -X POST "$API/auth/register" -H 'content-type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$SENHA\"}" >/dev/null

"$ADB" shell pm clear "$PKG" >/dev/null 2>&1
sleep 2
"$ADB" shell am start -n "$PKG/com.kavelipay.app.MainActivity" >/dev/null 2>&1
sleep 8

# garante sessão
if texto | grep -q "Quero criar conta"; then
    campo 1 "$EMAIL"
    campo 2 "$SENHA"
    tap "Entrar"
    sleep 8
fi

# a grade de atalhos pode estar abaixo da dobra em tela estreita
for _ in 1 2 3 4; do
    tap_exato "$Atalho" && break
    "$ADB" shell input swipe 680 700 680 400 250 >/dev/null 2>&1
    sleep 2
done
sleep 4

for _ in $(seq 1 "$Roladas"); do
    "$ADB" shell input swipe 680 700 680 300 250 >/dev/null 2>&1
    sleep 2
done

"$ADB" shell screencap -p /sdcard/kp.png >/dev/null 2>&1
"$ADB" pull /sdcard/kp.png "${HOME}/.hermes/cache/scratch/$Nome.png" >/dev/null 2>&1
echo "texto na tela: $(texto | head -c 400)"
echo "screenshot: ~/.hermes/cache/scratch/$Nome.png"