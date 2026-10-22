# KaveliPay

App Android de organização financeira e pagamentos para **pessoa física**, com backend de ledger contábil interno. MVP **sem custódia de dinheiro real**: saldo é demonstrativo e sempre derivado do ledger. Spec mestra: `ideia.tt` (pt-BR, **incompleta** — corta no meio de uma frase na linha 434; não presuma o resto). Repo em pt-BR.

## Estado atual

Backend Rust (`backend/`) completo: **476 testes passando** (142 Rust + 334 shell e2e) + 75 JUnit do app. App Kotlin + Jetpack Compose (`android/`): APK release assinado e instalável no Waydroid (não emulador). Repo versionado em git.

## Comandos essenciais

```bash
# Postgres dev: Docker porta 55432, user/senha/db = kavelipay (docs/ambiente-e-build.md)
# O dev usa VOLUME NOMEADO kavelipay-pgdata — sem ele, um docker rm apaga o banco (já aconteceu).
cd backend
cargo build && cargo test                        # 142 Rust, sem banco
PGHOST=localhost PGPORT=55432 PGUSER=kavelipay PGPASSWORD=kavelipay bash tests/<nome>_e2e.sh
DATABASE_URL=postgres://kavelipay:kavelipay@localhost:55432/kavelipay \
JWT_SECRET=$(head -c 48 /dev/urandom | base64) BIND_ADDR=0.0.0.0:3000 \
nohup ./target/debug/kavelipay-backend > /tmp/kavelipay-backend.log 2>&1 &

cd android
./gradlew :app:assembleRelease                  # APK assinado em app/build/outputs/apk/release/
./gradlew :app:testDebugUnitTest                # 65 JUnit, sem emulador
```

- **Depois de editar qualquer `.sql`, `cargo build` é obrigatório** — `sqlx::migrate!` embute as migrations no binário; sem rebuild o servidor sobe com a versão antiga e o teste falha parecendo bug de lógica.
- `cargo clippy` e `cargo fmt` **não existem** neste Fedora. Não documente como se existissem.
- Treze scripts e2e (ports, cobertura, `simulacao.sh`), Waydroid + adb (receita, IP muda por boot) e assinatura do APK: `docs/ambiente-e-build.md`.

## Regras inegociáveis (texto completo em `docs/spec.md`)

- Somente pessoa física. Nada de painel de lojista, PDV, CRM, catálogo, estoque, dashboard empresarial.
- KaveliPay **não é banco, não custodia dinheiro, não é provedor de PIX**: o "QR" de cobrança é deep link `kavelipay://cobranca/...`, nunca BR Code/EMV.
- Saldo **sempre** derivado do ledger (Wallet → Ledger Account → Transaction → Entries). Toda transação equilibrada (`total_debits = total_credits`). Nunca `UPDATE`/`INSERT` direto em saldo.
- Dinheiro: `rust_decimal::Decimal` + `NUMERIC(19,4)`. Na API **sempre string** (`"10.50"`); o tipo `Money` recusa JSON **float** com 422 (inteiro é aceito — precisão exata; a API deve mandar string). `f64`/`Float` em dinheiro é bug — inclusive no app Android (nada de `Double` em saldo exibido).
- Nunca fingir pagamento: `POST /bills/{id}/pay` e `/charges/{id}/receive` registram o que o usuário **afirma**; nada gera `PAID` sem POST explícito do dono.
- Uma carteira por usuário; `user_id` vem **do token**, nunca do body/query.

## Fronteira de confiança (o que mais se viola por descuido)

- Toda query de carteira/saldo/transação/extrato filtra por `user_id` dono; recurso alheio na URL = 403.
- Valor nunca vem do app — o backend recalcula do ledger. No cliente, a tela nunca é fonte da verdade; saldo calculado localmente é bug.

## Convenções que já custaram caro (detalhe em `docs/ledger.md`)

- SQL à mão com `query`/`query_scalar`/`query_as` — **nunca** macro `query!` (precisa `DATABASE_URL` em compile time).
- Saldo sempre por `saldo()` a partir dos lançamentos; nunca "saldo anterior + valor".
- `TransactionType::par_de_contas()` decide o sinal: entrada de dinheiro **credita** `USER_AVAILABLE`, saída **debita**. Inverter não viola o equilíbrio — viola o saldo (testes de sinal existem por isso).
- Boleto pago: transação no ledger **ANTES** do UPDATE de status.
- `OVERDUE` nunca é persistido: é `PENDING` + `due_date` vencida, derivado na leitura. Liste com `?overdue=true`, não `?status=OVERDUE`.
- Splits: `SUM(owed_amount) = total_amount` garantido por **dois** constraint triggers deferrable; `owed_amount` nunca muda ao quitar.

## Lacunas abertas da spec — NÃO preencher por conta própria

Fuso/`due_date`, conflito 12B × seção 9 (app lê código de barras × backend recalcula), OCR (fora do MVP). São decisões do usuário. Lista completa em `docs/spec.md`.

## Ferramentas do agente

- **GitNexus** — MCP `gitnexus` (17 tools) + skills `.claude/skills/gitnexus-*`: grafo do código indexado (3.027 nós / 6.991 arestas). Antes de mexer em código, use `impact`/`trace`/`context`/`query` em vez de varrer o repo. Índice em `.gitnexus/` (já no `.gitignore`).
- Reindexar após mudança grande: `gitnexus analyze --skip-agents-md`. **`--skip-agents-md` é obrigatório** — este arquivo é curado à mão e o GitNexus o reescreve sem o flag.
- **Gate de review de UI** (`docs/ui-review.md`) — tela só fecha depois do gate: checklist mecânico (entrada no padrão do cliente, erro pt-BR sem jargão, dinheiro sem float, estados, contraste) + screenshot no Waydroid contra a referência Nubank. O `revisor` roda nos dois níveis.

## Documentação detalhada (busque sob demanda — não vive mais aqui)

`docs/ambiente-e-build.md` (Docker/Waydroid/adb/e2e/assinatura) · `docs/deploy-render-neon.md` (deploy do backend no Render + Neon) · `docs/endpoints.md` · `docs/transparencia.md` (derivation, categorias, relatórios, LGPD, recorrência) · `docs/ia-e-mercado.md` (IA OpenRouter + brapi/Yahoo + fronteira CVM) · `docs/custos.md` (custos, pró-labore, IA de dev, marketing, CAC e break-even) · `docs/notificacoes-perfil.md` · `docs/ledger.md` (sinal, splits, convenções completas, invariantes, seed) · `docs/spec.md` (regras completas, PIX, decisões, stack, lacunas, pitfalls) · `docs/ui-review.md` (gate de review de UI — checklist + referência Nubank)
