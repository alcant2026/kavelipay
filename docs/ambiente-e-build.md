# Ambiente, build e testes

> Movido de AGENTS.md em 2026-10-12 (enxugamento para reduzir contexto repetido por turno). Conteúdo verbatim.

## App Android

```
cd android
./gradlew :app:assembleRelease      # APK assinado em app/build/outputs/apk/release/
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest    # JUnit das regras puras (36 testes, sem emulador)
```

Saída: `android/app/build/outputs/apk/release/KaveliPay-release.apk`
(~3,4 MB com R8, minSdk 26, targetSdk 34, AGP 8.2.0, Kotlin 1.9.20).

Assinatura: `android/kavelipay-release.jks` + `android/keystore.properties`
(alias `kavelipay`). Verificar com
`~/Android/Sdk/build-tools/36.0.0/apksigner verify --print-certs <apk>`.
O nome do arquivo **sem** `-unsigned` é o sinal de que o signing pegou.

Use `--offline` quando a rede não for confiável — todas as dependências já
estão em `~/.gradle/caches`. Biblioteca nova (ZXing, por exemplo) exige
download.

`ApiClient.BASE_URL` está fixo em `http://192.168.1.8:3000` (IP da máquina na
rede local, para aparelho físico). No emulador use `10.0.2.2`. O backend não
sobe em `:3000` por padrão — ajuste `config.rs`.

Armadilhas de Compose que já custaram build: ver a skill
`kotlin-compose-pitfalls`.

## Dev environment

Postgres de desenvolvimento e de teste roda em Docker na porta **55432** (o
Postgres do sistema não tem role `josias`, então não serve):

```
docker run -d --rm --name kp_pgtest \
  -e POSTGRES_PASSWORD=test -e POSTGRES_USER=kavelipay -e POSTGRES_DB=kavelipay \
  -p 55432:5432 postgres:18-alpine
```

Primeira execução demora (baixa a imagem). Conectar:
`PGHOST=localhost PGPORT=55432 PGUSER=kavelipay PGPASSWORD=test psql -d kavelipay`

**O dev de desenvolvimento usa o volume nomeado `kavelipay-pgdata`** (montado
em `/var/lib/postgresql/data`) — os dados sobrevivem a `docker restart`,
`docker stop`/`rm` e reboot do daemon. Sem volume, um `docker rm` apaga o
banco inteiro (já aconteceu: a conta do usuário sumiu do nada). O container
também é iniciado **sem `--rm`** por esse motivo. Para zerar o banco de
propósito: `docker volume rm kavelipay-pgdata`.

### Rodar o backend (comandos que funcionam)

```
docker run -d --name kavelipay-pg \
  -e POSTGRES_PASSWORD=kavelipay -e POSTGRES_USER=kavelipay -e POSTGRES_DB=kavelipay \
  -v kavelipay-pgdata:/var/lib/postgresql/data \
  -p 55432:5432 postgres:16-alpine

cd backend
DATABASE_URL=postgres://kavelipay:kavelipay@localhost:55432/kavelipay \
JWT_SECRET=$(head -c 48 /dev/urandom | base64 | tr -d '\n') \
BIND_ADDR=0.0.0.0:3000 \
nohup ./target/debug/kavelipay-backend > /tmp/kavelipay-backend.log 2>&1 &
```

Binário é `target/debug/kavelipay-backend` (não `backend`). `JWT_SECRET` precisa
de ≥32 chars, senão o processo morre no boot. Migrations rodam sozinhas no start.
Smoke test mínimo: `POST /auth/register` → token → `POST /wallet/transactions`
com `{"type":"ENTRY","amount":"150.50"}` → `GET /wallet` deve bater.
Obs: `POST /bills` exige campo `description` (não `title`); `type` aceita
`ENTRY|EXPENSE|BILL_PAID` (`INCOME` é 422).

### Testar a UI do app (Waydroid)

O app roda no **Waydroid**, não emulador. `ApiClient.BASE_URL =
http://192.168.240.1:3000` (lado host da bridge `waydroid0`) — funciona com o
backend em `0.0.0.0:3000` sem mexer em nada.

```
nohup waydroid session start > /tmp/waydroid-session.log 2>&1 &   # bloqueia; NÃO rodar em foreground
waydroid app install android/app/build/outputs/apk/debug/KaveliPay-debug.apk
waydroid app launch com.kavelipay.app.debug
```

- **adb funciona — receita:** servidor adb velho deixa o dispositivo
  `offline`. Zera tudo e reconecta:
  ```
  ADB=~/Android/Sdk/platform-tools/adb
  $ADB disconnect; $ADB kill-server; $ADB connect 192.168.240.112:5555
  # após ~10s: $ADB devices → "device"
  ```
  Atenção: o IP do container muda a cada boot da sessão (foi `.112`); pegue o
  atual com `ip -4 addr show waydroid0` + `waydroid status`. Com adb tenho
  `input tap/text`, `exec-out screencap -p` e `uiautomator dump` (árvore de UI).
- **Reboot mata tudo em cascata** (já aconteceu): container `kavelipay-pg`
  exited, backend morto, sessão Waydroid STOPPED e o app sem sessão — backend
  de novo no ar com `JWT_SECRET` novo → **401 em todo request**, a conta
  antiga não volta. Ordem que funciona: `docker start kavelipay-pg` → backend
  `nohup` → `waydroid session start` → adb connect; conta perdida se refaz
  via `POST /auth/register`.
- **adb wedge**: depois de 1–4 invocações o adbd do container trava
  (`adb shell` pendura; `disconnect`/`connect` fica `offline`). Só
  `waydroid session stop/start` revive. Evite: rode o fluxo inteiro de UI numa
  **única** invocação `adb shell sh < script` logo após o restart, com o pull
  dos arquivos numa segunda (`toybox tar`); e `am start -W` explícito antes de
  digitar — sem isso `input text` cai na barra de busca do launcher.
- **Roteiro de UI pronto: `backend/tests_android.sh [--verbose]`** — navega o
  app por TEXTO (`uiautomator dump`), não por coordenada; conta a sessão via
  API e exercita login → telas. Requer Waydroid + adb + backend no ar.
- **Screenshot sem adb:** `import -window root /tmp/shot.png` (DISPLAY=:0).
- **Clicar sem adb: `xdotool`** (instalado via `sudo dnf install -y xdotool`)
  — fallback, por coordenada.
- `waydroid shell` e `waydroid logcat` **exigem root** (sudo com senha).
- Logs do app que bateram no backend: `grep ERROR /tmp/kavelipay-backend.log`
  (401 no launch = sessão antiga expirada, comportamento esperado).

## Build & test

```
cd backend
cargo build                                    # binário do servidor
cargo test                                     # 128 unit/integration, sem banco
PGHOST=localhost PGPORT=55432 PGUSER=kavelipay PGPASSWORD=kavelipay bash tests/schema_test.sh
PGHOST=localhost PGPORT=55432 PGUSER=kavelipay PGPASSWORD=kavelipay bash tests/auth_e2e.sh
PGHOST=localhost PGPORT=55432 PGUSER=kavelipay PGPASSWORD=kavelipay bash tests/ledger_e2e.sh
PGHOST=localhost PGPORT=55432 PGUSER=kavelipay PGPASSWORD=kavelipay bash tests/bills_e2e.sh
PGHOST=localhost PGPORT=55432 PGUSER=kavelipay PGPASSWORD=kavelipay bash tests/splits_e2e.sh
PGHOST=localhost PGPORT=55432 PGUSER=kavelipay PGPASSWORD=kavelipay bash tests/charges_e2e.sh
PGHOST=localhost PGPORT=55432 PGUSER=kavelipay PGPASSWORD=kavelipay bash tests/profile_notif_history_e2e.sh
PGHOST=localhost PGPORT=55432 PGUSER=kavelipay PGPASSWORD=kavelipay bash tests/investments_e2e.sh
PGHOST=localhost PGPORT=55432 PGUSER=kavelipay PGPASSWORD=kavelipay bash tests/transparency_e2e.sh
PGHOST=localhost PGPORT=55432 PGUSER=kavelipay PGPASSWORD=kavelipay bash tests/refresh_e2e.sh
PGHOST=localhost PGPORT=55432 PGUSER=kavelipay PGPASSWORD=kavelipay bash tests/debts_e2e.sh
bash tests/simulacao.sh
```

Senha `kavelipay` = container `kavelipay-pg` (o de volume nomeado, dono de
55432 agora); o recipe `kp_pgtest` mais acima usa `test`. Com a porta ocupada
por um, o outro não sobe.

Doze scripts. `simulacao.sh` é a **jornada completa** de um usuário (cadastro →
entradas → contas → pagar → despesas → divisão → cobrança + QR → lembretes →
histórico → segurança → isolamento entre contas → conferência contábil) e
imprime o dinheiro entrando e saindo a cada passo. Rode para ver o produto
funcionar, não só para passar: `bash tests/simulacao.sh`.

Os dez outros criam e destroem um banco próprio (`*_test_<pid>`) e sobem o
binário real (`:3311` auth, `:3312` ledger, `:3313` bills, `:3314` splits,
`:3315` charges, `:3316` perfil/notif/histórico, `:3317` dívidas, `:3321`
transparência, `:3322` refresh, `:3323` investimentos).
Exigem `cargo build` antes. Não
aplique migrations manualmente nos bancos de teste: quem aplica é o servidor no
boot (`sqlx::migrate!`).

**`sqlx::migrate!` embute as migrations no binário em tempo de compilação.**
Depois de editar qualquer `.sql`, rode `cargo build` — senão o servidor sobe
com a versão antiga e o teste falha com um erro que parece de lógica.

Estado atual dos testes: 128 Rust (inclui 8 de `lookup.rs` com mock da IA e 17
de `cotacao.rs` com mock do brapi/Yahoo — nenhum gasta token ou rede real; os 5
testes novos de `cotacao.rs` cobrem o nome oficial do mercado vencendo o da IA) +
13 schema + 20 auth + 15 ledger + 26 bills + 17 splits + 24 charges + 31 perfil
+ 52 transparência + 28 investimentos (4 e2e a mais cobrindo
`POST /investments/quote` + a data no padrão dd/mm/aaaa) + 14 refresh + 69
dívidas (`debts_e2e.sh`, porta 3317) =
**437, todos passando** (309 deles shell e2e), mais a simulação de jornada
completa, mais 36 JUnit
do app (`./gradlew :app:testDebugUnitTest`, regras puras do prefill por IA, da
sugestão de preço/dividendo, do total de negociação `qtd × preço`, do prefill
de data em `dd/mm/aaaa` e de `DividaRegras` — normalização de valor pt-BR).

`cargo clippy` e `cargo fmt` NÃO estão instalados (`rustfmt`/`clippy` ausentes no
Fedora; instalar exige sudo). Não documente `cargo fmt` como se funcionasse.

Rodar o servidor:

```
DATABASE_URL=postgres://kavelipay:test@localhost:55432/kavelipay \
JWT_SECRET=$(head -c 48 /dev/urandom | base64) \
cargo run --bin kavelipay-backend
```
