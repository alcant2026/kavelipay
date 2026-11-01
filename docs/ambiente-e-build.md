# Ambiente, build e testes

> Movido de AGENTS.md em 2026-10-12 (enxugamento para reduzir contexto repetido por turno). Conteúdo verbatim.

## App Android

```
cd android
./gradlew :app:assembleRelease      # APK assinado em app/build/outputs/apk/release/
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest    # JUnit das regras puras (103 testes, sem emulador)
```

Saída: `android/app/build/outputs/apk/release/KaveliPay-release.apk`
(~3,4 MB com R8, minSdk 26, targetSdk 34, AGP 8.2.0, Kotlin 1.9.20).

Assinatura: `android/kavelipay-release.jks` + `android/keystore.properties`
(alias `kavelipay`, PKCS12, RSA 4096, validade 10.000 dias). Nenhum dos dois
é versionado — nem agora, nem no histórico (foram removidos com
`git filter-repo` em 2026-11-01). Verificar com
`~/Android/Sdk/build-tools/37.0.0/apksigner verify --print-certs <apk>`;
o SHA-256 da chave atual é
`e4:2b:86:5a:bf:1b:ab:d7:0b:8c:86:8a:e5:47:59:71:b5:a8:4b:3a:b2:11:90:01:0b:ef:7d:12:53:74:a0:70`.
O nome do arquivo **sem** `-unsigned` é o sinal de que o signing pegou.

**A chave de 2026-10 vazou** (estava no histórico do repo junto com a senha,
que era o nome do app seguido de três dígitos) e foi substituída. Consequência para quem tinha o APK antigo:
o Android recusa atualização assinada por outra chave — é preciso
desinstalar e instalar de novo, perdendo o dado local. A chave nova está
guardada fora do git e nos secrets do GitHub (`KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). **Perder a keystore nova
inviabiliza publicar atualização para quem já instalou** — mantenha uma cópia
em local seguro, fora do repositório.

Use `--offline` quando a rede não for confiável — todas as dependências já
estão em `~/.gradle/caches`. Biblioteca nova (ZXing, por exemplo) exige
download.

`ApiClient` resolve o `BASE_URL` por sonda TCP entre candidatos fixos:
`http://192.168.240.1:3000` (lado host da bridge do Waydroid),
`http://192.168.1.8:3000` (IP da máquina na rede local, aparelho físico) e
`http://10.0.2.2:3000` (emulador AVD). Isso vale **só em build debug**
(`permiteLocal` checa `FLAG_DEBUGGABLE`): o APK release fala exclusivamente
com `https://kavelipay-backend.onrender.com`, para não aceitar host local em
aparelho de usuário. O host que respondeu é persistido em `SharedPreferences`
(`kavelipay.servidor`) e reassumido na próxima abertura. **Se a IP da máquina
mudar (DHCP), atualize `CAMINHOS_LOCAIS` em `ApiClient.kt`** — ou reserve a
IP no roteador. O backend não sobe em `:3000` por padrão — ajuste `config.rs`.

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
`PGHOST=localhost PGPORT=55432 PGUSER=kavelipay PGPASSWORD=kavelipay psql -d kavelipay`

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

### Backend como serviço (systemd de usuário)

Além do `nohup` acima, nesta máquina o backend sobe por um serviço de usuário:

```
~/.config/systemd/user/kavelipay-backend.service   # Restart=on-failure, RestartSec=5
└─ executa ~/.local/bin/kavelipay-backend.sh       # exporta DATABASE_URL/JWT_SECRET/BIND_ADDR e faz exec no binário
```

```
systemctl --user status kavelipay-backend
journalctl --user -u kavelipay-backend -n 30 --no-pager   # por que morreu
systemctl --user restart kavelipay-backend
```

O serviço **não** sobe o Postgres junto, e é aí que ele se perde de duas formas:

- **Banco parado** (container `kavelipay-pg` caiu ou a máquina reiniciou): o
  serviço fica em `activating (auto-restart)`, tentando de novo a cada 5s,
  indefinidamente. Conserto: `docker start kavelipay-pg`.
- **Senha divergente** entre o script e o container: o log mostra
  `28P01: password authentication failed for user "kavelipay"`. A senha válida é
  a do `POSTGRES_PASSWORD` usado na criação do container (`kavelipay`), não a que
  estiver escrita no script. Conserto: acertar o script e reiniciar o serviço —
  não recriar o container (recriar sem o volume `kavelipay-pgdata` apaga o banco).

Nos dois casos o sintoma é o mesmo: `ss -ltnp | grep :3000` vazio, curl sem
resposta e o app com erro de rede — o serviço "existe", mas não está escutando.
Quando a porta responde, `/health` dá 404 (não existe rota de health): use
`/profile` (405 sem método) ou um login real para saber se está de pé.

O script também exporta `ADMIN_TOKEN` (a config recusa subir com menos de 24
caracteres). Sem ela, `POST /admin/parceiros` responde 503 e a vitrine só pode
ser cadastrada por migration. Ao ler o token de volta, cuidado com o `=`: ele é
base64 e termina em `=`, então `cut -d= -f2` trunca e a API devolve 401 — use
`sed -n 's/^export ADMIN_TOKEN=//p' ~/.local/bin/kavelipay-backend.sh`.

### Testar a UI do app (Waydroid)

O app roda no **Waydroid**, não emulador. O `ApiClient` sonda os candidatos
(`192.168.240.1` do lado host da bridge `waydroid0`, `192.168.1.8` da LAN e
`10.0.2.2` do AVD) e usa o que responder — no Waydroid cai no `.240.1` na
primeira requisição, e o mesmo APK no aparelho físico cai no IP da máquina
(mesma rede Wi-Fi), sem rebuild. Sonda é só TCP na porta 3000, 800ms por
candidato, uma vez por execução.

```
nohup waydroid session start > /tmp/waydroid-session.log 2>&1 &   # bloqueia; NÃO rodar em foreground
waydroid app install android/app/build/outputs/apk/debug/KaveliPay-debug.apk
waydroid app launch com.kavelipay.app.debug
```

- **adb funciona — receita:** servidor adb velho deixa o dispositivo
  `offline`. Zera tudo e reconecta:
  ```
  ADB=~/Android/Sdk/platform-tools/adb
  waydroid app launch com.kavelipay.app    # acorda o container (FROZEN → RUNNING)
  $ADB disconnect; $ADB kill-server; $ADB connect 192.168.240.112:5555
  # após ~10s: $ADB devices → "device"
  ```
  **O connect só pega com o container RUNNING** (tela ligada): com sessão
  FROZEN/STOPPED o adb conecta e fica `offline` pra sempre — acorde com
  `waydroid app launch` antes. Se mesmo assim ficar offline, é wedge (abaixo).
  Atenção: o IP do container muda a cada boot da sessão (foi `.112`); pegue o
  atual com `ip -4 addr show waydroid0` + `waydroid status`. Com adb tenho
  `input tap/text`, `exec-out screencap -p` e `uiautomator dump` (árvore de UI).
- **Atualizar APK: `adb install -r <apk>`**, não `waydroid app install` — o
  segundo às vezes sai com exit 0 e NÃO aplica a atualização (o app velho
  continua rodando e você "conserta" um build que nem está instalado).
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
PGHOST=localhost PGPORT=55432 PGUSER=kavelipay PGPASSWORD=kavelipay bash tests/caixinhas_e2e.sh
bash tests/simulacao.sh
```

Senha `kavelipay` = container `kavelipay-pg` (o de volume nomeado, dono de
55432 agora); o recipe `kp_pgtest` mais acima usa `test`. Com a porta ocupada
por um, o outro não sobe.

Quinze scripts. `password_reset_e2e.sh` sobe um SMTP falso (`tests/smtp_mock.py`)
em `127.0.0.1` e lê o código do e-mail entregue — o fluxo inteiro de "esqueci a
senha" roda sem internet e sem provedor. `caixinhas_e2e.sh` (porta dinâmica — antes fixa em 3324) é a jornada da Caixinha
(criação → convite de uso único com rotação → fronteira 404 → aporte /
rendimento / devolução sob "quem paga propõe, quem recebe confirma" →
avaliação → telefone → vitrine → derivações do mês) e prova a decisão
central: `GET /wallet` dos quatro envolvidos fica `0.00` do começo ao fim —
a caixinha é FORA do ledger. `simulacao.sh` é a **jornada completa** de um usuário (cadastro →
entradas → contas → pagar → despesas → divisão → cobrança + QR → lembretes →
histórico → segurança → isolamento entre contas → conferência contábil) e
imprime o dinheiro entrando e saindo a cada passo. Rode para ver o produto
funcionar, não só para passar: `bash tests/simulacao.sh`.

### Teste de carga

`tests/carga.py` (só stdlib — não precisa instalar `hey`/`ab`) mede latência e
vazão:

```bash
python3 tests/carga.py --url http://127.0.0.1:3100 --auth -n 600 -c 20   # toca o banco
python3 tests/carga.py --url https://kavelipay-backend.onrender.com -n 100 -c 5  # só /health
```

`--auth` cadastra ou reaproveita `carga@kavelipay.dev` — só em dev ou banco
descartável. Sem `--auth` ele bate em `/health`, que **não** consulta o banco
(portanto não estoura a cota de compute do Neon).

Medição de 2026-11-01, como referência: build **debug** local, 600 requisições
autenticadas a 20 conexões = 364 req/s (p50 41 ms, p95 162 ms); produção no
plano free do Render, `/health` a 20 conexões = 100 req/s (p50 178 ms). Rota
que consulta o banco fica bem abaixo disso: cada requisição faz 2 a 5 idas ao
Neon (Ohio → us-east-2), e o pool é de 10 conexões.

Os doze outros criam e destroem um banco próprio (`*_test_<pid>`) e sobem o
binário real (porta dinâmica por execução: `BASE_PORT` fixa explicitamente; antes fixas em :3311 auth, :3312 ledger, :3313 bills, :3314 splits,
`:3315` charges, `:3316` perfil/notif/histórico, `:3317` dívidas, `:3321`
transparência, `:3319` parceiros, `:3322` refresh, `:3323` investimentos).
Exigem `cargo build` antes. Não
aplique migrations manualmente nos bancos de teste: quem aplica é o servidor no
boot (`sqlx::migrate!`).

**`sqlx::migrate!` embute as migrations no binário em tempo de compilação.**
Depois de editar qualquer `.sql`, rode `cargo build` — senão o servidor sobe
com a versão antiga e o teste falha com um erro que parece de lógica.

Estado atual dos testes: 142 Rust (inclui 8 de `lookup.rs` com mock da IA, 17
de `cotacao.rs` com mock do brapi/Yahoo/statusinvest — nenhum gasta token ou
rede real — e 7 de `http_cru.rs`, o cliente HTTP/1.1 que fala com o
statusinvest; entre os de `cotacao.rs`, o nome oficial do mercado vence o da IA,
a ação usa brapi com reserva no statusinvest, e o FII usa só o statusinvest) +
13 schema + 20 auth + 15 ledger + 26 bills + 17 splits + 37 charges + 31 perfil
+ 52 transparência + 28 investimentos (4 e2e a mais cobrindo
`POST /investments/quote` + a data no padrão dd/mm/aaaa) + 14 refresh + 69
dívidas (`debts_e2e.sh`, porta 3317) + 12 parceiros
(`parceiros_e2e.sh`, porta 3319) =
**476, todos passando** (334 deles shell e2e), mais a simulação de jornada
completa, mais 75 JUnit
do app (`./gradlew :app:testDebugUnitTest`, regras puras do prefill por IA, da
sugestão de preço/dividendo, do total de negociação `qtd × preço`, do prefill
de data em `dd/mm/aaaa`, de `DividaRegras` — normalização de valor pt-BR — e do
QR/pixel (`QrCodeTest`: a cor tem de virar ARGB opaco, senão o QR sai
transparente), do link de cobrança (`tokenDeCobranca`) e do reconhecimento do
que foi lido (`classificarLeitura`: boleto × cobrança e a tela certa para cada
um, sobre a lista única de `formatosDeLeitura`)).

`cargo clippy` e `cargo fmt` NÃO estão instalados (`rustfmt`/`clippy` ausentes no
Fedora; instalar exige sudo). Não documente `cargo fmt` como se funcionasse.

Rodar o servidor:

```
DATABASE_URL=postgres://kavelipay:kavelipay@localhost:55432/kavelipay \
JWT_SECRET=$(head -c 48 /dev/urandom | base64) \
cargo run --bin kavelipay-backend
```
