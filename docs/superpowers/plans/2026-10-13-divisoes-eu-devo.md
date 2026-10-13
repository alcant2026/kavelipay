# Divisões "Eu devo" — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adicionar à aba Divisões o sentido "eu devo" — dívida com credor em três estados (`PENDING → PAID → CONFIRMED`), com "paguei" debitando o ledger e "credor confirmou" sendo só status.

**Architecture:** Backend Rust/Axum ganha módulo `routes/debts.rs` + tabela `debts` (migração `0009`), espelhando o shape e as guardas de `routes/splits.rs`; o pagamento posta `TransactionType::Expense` dentro de transação SQL com guarda de status. Android ganha modelos/API/regras puras (`DividaRegras`) e a UI em duas seções na `SplitsScreen` + formulário `NovaDividaScreen`, seguindo os padrões de `SplitsScreen`/`NovaSplitScreen`.

**Tech Stack:** Rust + Axum + sqlx (SQL manual, `NUMERIC(19,4)`), bash e2e com `curl`/`psql`, Kotlin + Jetpack Compose, JUnit4.

**Spec:** `docs/superpowers/specs/2026-10-13-divisoes-eu-devo-design.md` — todo argumento deste plano vem de lá; leia os dois.

## Global Constraints

- Dinheiro: API **sempre string** (`"50.00"`), `rust_decimal::Decimal`/`NUMERIC(19,4)` no backend, `BigDecimal` no Android. **Nunca** `f64`/`Float`/`Double`/JSON number em dinheiro.
- Saldo **sempre** derivado do ledger; nunca `UPDATE`/`INSERT` direto em saldo; toda transação equilibrada.
- `user_id` vem do token; toda query filtra pela carteira ativa do dono — recurso alheio na URL responde **404** (padrão de todos os e2e, ver `splits::settle_part`).
- Nunca fingir pagamento: `PENDING` **não** move dinheiro; `pay` exige POST explícito; `confirm` nunca move dinheiro.
- SQL manual com `query`/`query_scalar`/`query_as` — **nunca** macro `query!`.
- Depois de tocar `.sql`: **`cargo build` obrigatório** (`sqlx::migrate!` embute as migrations no binário).
- Erros 422 em pt-BR amigável, sem jargão; na tela, status nunca aparece como `PENDING`/`PAID`/`CONFIRMED`.
- Repo **sem `.git`** — não existem etapas de commit nesta feature.
- E2E: porta **3317**, banco `kavelipay_debts_test_$$`, env `PGHOST=localhost PGPORT=55432 PGUSER=kavelipay PGPASSWORD=test`.
- Contagem de testes só com número medido; reindexar GitNexus ao fim (`gitnexus analyze --skip-agents-md --skip-git`).

## Review Focus

Falhas que o spec implica mas que nenhum teste cobre sozinho — cada linha tem teste no task dono:

1. **Dívida pendente não pode mexer no saldo** — criar dívida e o saldo continuar `1000.00` (Task 1, check "saldo intacto").
2. **Pagar move dinheiro exatamente uma vez** — duplo `pay` → 409 e saldo único `950.00` (Task 2).
3. **Guardas de estado** — `confirm` sem `pay` → 409; `PATCH`/`DELETE` pós-pagamento → 409 (Tasks  3 e 4).
4. **Dívida alheia não existe** — `GET /debts` de bob vem vazio; bob pagando dívida de alice → 404 (Tasks 1 e 2).
5. **Dinheiro sem float** — campo aceita vírgula `50,50`, regra usa `BigDecimal`, resposta da API é string `50.00` (Task 6, JUnit; Task 8, gate).
6. **Rótulos sem jargão** — chip/botão entendível por cliente leigo (Task 8, checklist do gate).

## Estrutura de arquivos

| Ação | Arquivo | Responsabilidade |
|---|---|---|
| Criar | `backend/migrations/0009_debts.sql` | Tabela `debts` + CHECKs de consistência |
| Criar | `backend/src/routes/debts.rs` | Handlers: create/list/pay/confirm/update/delete |
| Modificar | `backend/src/routes/mod.rs` | `pub mod debts;` + 6 rotas |
| Modificar | `backend/src/routes/lgpd.rs` | Seção `dividas` no export JSON |
| Criar | `backend/tests/debts_e2e.sh` | Seções de teste por task |
| Criar | `android/.../data/DividaRegras.kt` | Regras puras de status/formulário |
| Criar | `android/.../src/test/java/com/kavelipay/app/data/DividaRegrasTest.kt` | JUnit das regras |
| Modificar | `android/.../data/Models.kt` | `ItemDivida`, `ListaDividas`, conversores |
| Modificar | `android/.../data/KaveliPayApi.kt` | 6 métodos `/debts` |
| Modificar | `android/.../ui/AppViewModel.kt` | Estado `dividas` + 6 funções |
| Modificar | `android/.../ui/screens/Nav.kt` | Rotas `NOVA_DIVIDA`, `EDITAR_DIVIDA/{id}` |
| Criar | `android/.../ui/screens/NovaDividaScreen.kt` | Formulário criar/editar |
| Modificar | `android/.../ui/screens/SplitsScreen.kt` | Seções "Divisões" + "Eu devo" |
| Modificar | `docs/ambiente-e-build.md`, `AGENTS.md` | Porta 3317, comando, contagens |

---

### Task 1: Backend — migração + criar/listar dívida

**Files:**
- Create: `backend/migrations/0009_debts.sql`
- Create: `backend/tests/debts_e2e.sh` (boilerplate copiado de `splits_e2e.sh`: helpers `check/get/code`, cleanup, dois usuários alice/bob, seed `ENTRY 1000.00` de alice — porta `3317`)
- Create: `backend/src/routes/debts.rs`
- Modify: `backend/src/routes/mod.rs`

**Interfaces:**
- Produz ( Tasks 2-5 dependem):
  - `NewDebt { creditor_name: String, amount: Money, description: String }`
  - `DebtResponse { id: Uuid, creditor_name: String, description: String, amount: Money, status: String, paid_at: Option<DateTime<Utc>>, confirmed_at: Option<DateTime<Utc>>, created_at: DateTime<Utc> }` (serde snake_case; status ∈ `PENDING`/`PAID`/`CONFIRMED`)
  - `pub async fn wallet_ativa(state: &AppState, user_id: Uuid) -> AppResult<Uuid>` (helper **privado** do módulo, cópia do de `splits.rs`)
  - `pub async fn buscar(state: &AppState, id: Uuid, wallet: Uuid) -> AppResult<DebtResponse>` (helper privado; `NotFound("divida")` se outra carteira)
  - `create_debt(State, AuthUser, Json<NewDebt>) -> AppResult<(StatusCode, Json<DebtResponse>)>`
  - `list_debts(State, AuthUser) -> AppResult<Json<Vec<DebtResponse>>>`

- [ ] **Step 1: Escrever a seção "criação e listagem" (RED)** — append em `debts_e2e.sh`:

  - `POST /debts {"creditor_name":"João","amount":"50.00","description":"Jantar"}` → 201, `status=PENDING`, `amount=50.00` (string), `paid_at`/`confirmed_at` nulos
  - **saldo intacto `1000.00`** após criar (GET `/wallet`)
  - `GET /debts` (alice) → 1 item, contém `João` e `Jantar`
  - `GET /debts` (bob) → lista vazia
  - 422: `creditor_name` vazio; `amount="0.00"`; `amount="-5.00"`; `description` vazia; e nada gravado (recontar lista = 1)
  - POST com `amount` JSON number (`50` em vez de `"50"`) → 422 (tipo `Money` recusa)

- [ ] **Step 2: Rodar e ver falhar**

  Run: `cd backend && PGHOST=localhost PGPORT=55432 PGUSER=kavelipay PGPASSWORD=test bash tests/debts_e2e.sh`
  Expected: **FALHOU** nas checagens (rota `/debts` não existe → 404); exit ≠ 0.

- [ ] **Step 3: Implementar migração + módulo**

  - `0009_debts.sql`: tabela `debts` conforme spec (colunas `id`, `wallet_id UUID NOT NULL REFERENCES wallets(id) ON DELETE RESTRICT`, `creditor_name TEXT NOT NULL`, `description TEXT NOT NULL`, `amount NUMERIC(19,4) NOT NULL`, `status TEXT NOT NULL DEFAULT 'PENDING'`, `paid_at TIMESTAMPTZ`, `paid_transaction_id UUID REFERENCES ledger_transactions(id) ON DELETE RESTRICT`, `confirmed_at TIMESTAMPTZ`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`); CHECKs: `amount > 0`, `status IN (...)`, `(paid_at IS NULL) = (paid_transaction_id IS NULL)`, `status <> 'CONFIRMED' OR paid_at IS NOT NULL`, nome/descrição não vazios após `btrim`; índice `(wallet_id, created_at DESC)`.
  - `debts.rs`: validações em Rust antes do banco (mensagem `AppError::Validation`): `"credor é obrigatório"`, `"descrição é obrigatória"`, `"valor deve ser maior que zero"`. **Nenhuma** transação de ledger na criação. `INSERT` + `buscar` de retorno.
  - `mod.rs`: `pub mod debts;` + `.route("/debts", post(debts::create_debt))` + `.route("/debts", get(debts::list_debts))`.

- [ ] **Step 4: `cargo build` (obrigatório após `.sql`) e rodar seção**

  Run: `cargo build && PGHOST=localhost PGPORT=55432 PGUSER=kavelipay PGPASSWORD=test bash tests/debts_e2e.sh`
  Expected: seção criação/listagem **ok** (demais seções ainda não existem); `cargo test` → 128 verdes.

### Task 2: Backend — pagar dívida (ledger)

**Files:**
- Modify: `backend/src/routes/debts.rs`, `backend/tests/debts_e2e.sh`

**Interfaces:**
- Produz: `pub async fn pay_debt(State, AuthUser, Path<Uuid>) -> AppResult<Json<DebtResponse>>`
- Consome: `wallet_ativa`, `buscar`, `ledger::postar_em_com_categoria(&mut tx, wallet, TransactionType::Expense, amount, Some(&format!("Dívida: {}", creditor_name)), Some(json!({"debt_id": id})), None, Some(user_id))` (mesma família usada por `create_split`).

- [ ] **Step 1: Seção "pagar" (RED)** — append:

  - `POST /debts/{id}/pay` na dívida de 50 → 200, `status=PAID`, `paid_at` preenchido, `confirmed_at` nulo
  - **saldo `950.00`** após pagar
  - `GET /wallet/transactions` contém transação `EXPENSE` de `50.00` com descrição `Dívida: João`
  - segundo `pay` → **409** e saldo segue `950.00`
  - bob paga dívida de alice → **404**; id inexistente → **404**

- [ ] **Step 2: Rodar → FALHOU** (mesmo comando do Task 1 Step 4)

- [ ] **Step 3: Implementar `pay_debt`** — transação SQL: `begin`; `SELECT status ... FOR UPDATE` filtrando `id + wallet_id` (404 se ausente, 409 `"só dívidas pendentes podem ser pagas"` se ≠ `PENDING`); postar `Expense` **dentro** do `tx`; `UPDATE debts SET status='PAID', paid_at=now(), paid_transaction_id=$txn WHERE id=$ AND status='PENDING'` (se `rows_affected == 0` → rollback + 409); `commit`; `buscar`.

- [ ] **Step 4: `cargo build` + rodar seção → ok** (Task 1 continua ok; `cargo test` 128)

### Task 3: Backend — confirmar pelo credor (só status)

**Files:**
- Modify: `backend/src/routes/debts.rs`, `backend/tests/debts_e2e.sh`

**Interfaces:** `pub async fn confirm_debt(State, AuthUser, Path<Uuid>) -> AppResult<Json<DebtResponse>>`

- [ ] **Step 1: Seção "confirmar" (RED)** — append:

  - nova dívida de 30 → `confirm` **sem** pagar → 409 `"só dívidas pagas podem ser confirmadas"`; saldo não muda
  - `pay` da de 30 → saldo `920.00`; `confirm` → 200, `status=CONFIRMED`, `confirmed_at` preenchido e **saldo ainda `920.00`** (confirmação não move dinheiro)
  - duplo `confirm` → 409

- [ ] **Step 2: Rodar → FALHOU**

- [ ] **Step 3: Implementar `confirm_debt`** — mesmo formato de guarda de `pay_debt`, **sem** postar nada: `UPDATE ... SET status='CONFIRMED', confirmed_at=now() WHERE id=$ AND wallet_id=$ AND status='PAID'` (404/409 nos casos; `rows_affected==0` → 409).

- [ ] **Step 4: `cargo build` + rodar → ok**; `cargo test` 128

### Task 4: Backend — editar/apagar só em PENDING

**Files:**
- Modify: `backend/src/routes/debts.rs`, `backend/src/routes/mod.rs`, `backend/tests/debts_e2e.sh`

**Interfaces:**
- `pub async fn update_debt(State, AuthUser, Path<Uuid>, Json<NewDebt>) -> AppResult<Json<DebtResponse>>`
- `pub async fn delete_debt(State, AuthUser, Path<Uuid>) -> AppResult<Json<DebtResponse>>` — devolve o estado da linha apagada (mesmo comportamento de `bills::cancel_bill`, que DELETE devolve `Json`; evita corpo vazio que o `ApiClient.delete` do Android não parseia).

- [ ] **Step 1: Seção "editar e apagar" (RED)** — append:

  - nova dívida 20 → `PATCH` `amount=25.00` → 200, `amount=25.00`; **saldo intacto** (edição não mexe em ledger)
  - `PATCH` com `amount="0.00"` → 422; com credor vazio → 422
  - dívida paga (Task 2) → `PATCH` → **409** `"só dívidas pendentes podem ser alteradas"`; `DELETE` → **409** `"só dívidas pendentes podem ser apagadas"`
  - dívida confirmada (Task 3) → `PATCH` e `DELETE` → 409
  - `DELETE` na pendente de 25 → 200 e ela **some** do `GET /debts`; saldo intacto
  - id alheio → 404 nos dois

- [ ] **Step 2: Rodar → FALHOU**

- [ ] **Step 3: Implementar** — `update_debt`: valida igual a `create_debt`, `UPDATE ... WHERE id + wallet_id + status='PENDING'` (guarda por `rows_affected`, 409/404); `delete_debt`: `buscar` primeiro (devolve o corpo), depois `DELETE ... WHERE id + wallet_id + status='PENDING'` (409 se não pendente). Rotas em `mod.rs`: `.route("/debts/{id}", patch(...))` e `.route("/debts/{id}", delete(...))`.

- [ ] **Step 4: `cargo build` + rodar → ok**; `cargo test` 128

### Task 5: Backend — LGPD export com seção `dividas`

**Files:**
- Modify: `backend/src/routes/lgpd.rs`, `backend/tests/debts_e2e.sh`

**Interfaces:** consuma a tabela/`DebtResponse` do Task 1.

- [ ] **Step 1: Seção "LGPD" (RED)** — append:

  - `GET /lgpd/export` (alice) → JSON tem `"dividas"` com ao menos a dívida de `João` (credor), `valor` string e `status`
  - `GET /lgpd/export` (bob) → `"dividas"` vazio
  - export CSV **inalterado** (não testar — contrato intocado)

- [ ] **Step 2: Rodar → FALHOU**

- [ ] **Step 3: Implementar** — query das dívidas da carteira + chave `"dividas"` no `json!` do `export`, chaves pt-BR em snake_case como seus vizinhos (`credor`, `descricao`, `valor`, `status`, `pago_em`, `confirmado_em`, `criado_em`), `Money.to_string()`.

- [ ] **Step 4: `cargo build` + rodar → ok**; `cargo test` 128

### Task 6: Android — modelos, API e regras puras (JUnit)

**Files:**
- Create: `android/app/src/main/java/com/kavelipay/app/data/DividaRegras.kt`
- Create: `android/app/src/test/java/com/kavelipay/app/data/DividaRegrasTest.kt`
- Modify: `android/app/src/main/java/com/kavelipay/app/data/Models.kt`, `android/app/src/main/java/com/kavelipay/app/data/KaveliPayApi.kt`

**Interfaces:**
- `data class ItemDivida(id: String, creditorName: String, description: String, amount: String, status: String, paidAt: String?, confirmedAt: String?)`
- `data class ListaDividas(val itens: List<ItemDivida>)` + `fun JSONObject.paraListaDividas()` / `paraItemDivida()` (padrão `paraItemSplit`, campos `creditor_name`/`paid_at`/`confirmed_at`)
- `KaveliPayApi`: `dividas()` via `ApiClient.getItens("/debts", token)` (array solto, mesma família de `/splits`), `novaDivida(credor, valor, descricao)`, `editarDivida(id, credor, valor, descricao)` (`ApiClient.patch`), `pagarDivida(id)` / `confirmarDivida(id)` (`post` com corpo nulo, como `quitarParte`), `apagarDivida(id)` (`delete`). Corpo de dinheiro replica o tratamento de `novaSplit`.
- `object DividaRegras`:
  - `fun podePagar(status: String): Boolean` — só `PENDING`
  - `fun podeConfirmar(status: String): Boolean` — só `PAID`
  - `fun podeEditarApagar(status: String): Boolean` — só `PENDING`
  - `fun rotulo(status: String): String` — `"Devendo"` / `"Pago"` / `"Confirmado pelo credor"`
  - `fun podeSalvar(credor: String, valor: String, descricao: String): Boolean` — nome e descrição não vazios (`trim`), valor parseável em `BigDecimal` **aceitando vírgula ou ponto** e `> 0`

- [ ] **Step 1: Escrever `DividaRegrasTest` (RED)** — JUnit4, nomes em backticks pt-BR. Afirmações exatas:
  - `podePagar` true só em `PENDING`; `podeConfirmar` true só em `PAID`; `podeEditarApagar` true só em `PENDING`
  - `rotulo("PENDING")=="Devendo"`, `rotulo("PAID")=="Pago"`, `rotulo("CONFIRMED")=="Confirmado pelo credor"`
  - `podeSalvar("João","50,50","Jantar")==true`; `("João","50.50","Jantar")==true`; valor `""`, `"abc"`, `"0"`, `"-1"` → false; credor `""` → false; descrição `"   "` → false

- [ ] **Step 2: Rodar → FALHOU**

  Run: `cd android && ./gradlew :app:testDebugUnitTest`
  Expected: `Unresolved reference: podePagar` (ou equivalente).

- [ ] **Step 3: Implementar** `DividaRegras`, conversores e métodos da API.

- [ ] **Step 4: Rodar → verdes**

  Run: `./gradlew :app:testDebugUnitTest`
  Expected: BUILD SUCCESSFUL; **32 JUnit** (27 + 5 — contar o XML real e confirmar).

### Task 7: Android — UI da aba Divisões

**Files:**
- Create: `android/app/src/main/java/com/kavelipay/app/ui/screens/NovaDividaScreen.kt`
- Modify: `android/app/src/main/java/com/kavelipay/app/ui/screens/SplitsScreen.kt`, `android/app/src/main/java/com/kavelipay/app/ui/screens/Nav.kt`, `android/app/src/main/java/com/kavelipay/app/ui/AppViewModel.kt`

**Interfaces:**
- `AppViewModel`: `val dividas: ListaDividas?` em `EstadoApp`; `carregarDividas()` (padrão `carregarSplits`); `criarDivida(credor, valor, descricao, aoPronto: () -> Unit)`; `editarDivida(id, credor, valor, descricao, aoPronto)`; `pagarDivida(id)`; `confirmarDivida(id)`; `apagarDivida(id)` — todas `runCatching` + `carregarDividas()` no sucesso, espelhando `criarSplit`/`quitarParte` (inclusive tratamento de erro/enviando).
- `Nav.kt`: `Rota.NOVA_DIVIDA = "nova_divida"`, `Rota.EDITAR_DIVIDA = "editar_divida"`; rotas `composable(Rota.NOVA_DIVIDA)` e `"${Rota.EDITAR_DIVIDA}/{dividaId}"` (padrão `SPLITS_DETALHE` com `navArgument`); `KpSplitsScreen` ganha callbacks `aoNovaDivida` e `aoEditarDivida(id)`.
- `NovaDividaScreen`: `@Composable fun KpNovaDividaScreen(vm, dividaId: String?, aoVoltar, aoSalvar)` — `dividaId == null` cria; senão prefills de `estado.dividas` e PATCH no salvar. Campos **Credor**, **Valor (R$)** (usa `Money` + `filtrarValor`), **Motivo**; salvar habilitado por `DividaRegras.podeSalvar`; header `CabecalhoComVoltar("Nova dívida"/"Editar dívida", ...)`.
- `SplitsScreen`:
  - Row de dois botões: `Nova divisão` (existente) + `Nova dívida` (`aoNovaDivida`)
  - seção `Text("Eu devo", style = TituloSecao)` após as divisões, com `KpVazio` próprio quando vazia
  - card de dívida: credor em destaque, motivo, `R$ ${Money.brl(amount)}`, chip com `DividaRegras.rotulo` (cor via `KpTheme.semanticas`, visível em contraste), ações por estado: `PENDING` → botão primário **"Paguei"** + ícones Editar/Apagar (`AlertDialog` "Apagar esta dívida?" com Apagar/Cancelar); `PAID` → primário **"Credor confirmou"**; `CONFIRMED` → só chip.
  - Sem `Double`/notação flutuante em valor exibido.

- [ ] **Step 1: Implementar** os quatro arquivos acima.
- [ ] **Step 2: Compilar e suíte verde**

  Run: `cd android && ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
  Expected: BUILD SUCCESSFUL, 32 JUnit 0 falhas.

- [ ] **Step 3: Passo manual de sanidade (sem instalar release ainda)** — listar no plano executado o caminho: Home → Divisões → criar dívida → ver chip/corrigir. O proof definitivo é o Task 8.

### Task 8: Gate de UI no Waydroid

**Files:** nenhum código; evidências conforme `docs/ui-review.md`.

- [ ] **Step 1: Build e instalar release**

  Run: `cd android && ./gradlew :app:assembleRelease` → depois `adb install -r app/build/outputs/apk/release/KaveliPay-release.apk` (Waydroid `adb connect` se preciso — receita em `docs/ambiente-e-build.md`).

- [ ] **Step 2: Rodar o checklist de `docs/ui-review.md`** nas telas alteradas (lista Divisões com as duas seções; Nova dívida; estados Devendo/Pago/Confirmado): entrada no padrão do cliente, erro pt-BR sem jargão (testar campo vazio e valor lixo na tela), dinheiro sem float, estados de vazio/carregando/erro, contraste dos chips.
- [ ] **Step 3: Screenshot no Waydroid** da tela com dívida nos 3 estados, contra a referência Nubank; anotar no checklist o resultado. **Nenhuma tela fecha sem isso.**

### Task 9: Suíte completa, contagens e reindex

- [ ] **Step 1: Backend inteiro verde** — os 11 scripts e2e (`auth, ledger, bills, splits, charges, profile_notif_history, investments, transparency, refresh, debts` + `schema_test`) + `cargo test`; esperado **0 falhas**.
- [ ] **Step 2: Contagens medidas** — soma real (seguir a metodologia de `docs/ambiente-e-build.md`) → atualizar `AGENTS.md` e `docs/ambiente-e-build.md` (incluindo porta `3317` e o comando de `debts_e2e.sh` na lista de comandos).
- [ ] **Step 3: GitNexus** — `gitnexus analyze --skip-agents-md --skip-git` (repo ainda sem `.git`).
