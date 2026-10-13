# Divisões — "Eu devo" (dívidas com credor) · design

Data: 2026-10-13 · Aprovado em conversa (chat) pelo usuário; este documento
formaliza para planejamento.

## Intenção

A aba **Divisões** hoje só anda num sentido: o usuário pagou a conta e os
participantes devem a ele (`POST /splits` debita a carteira do dono na hora;
`settle` quita cada parte e devolve o dinheiro quando o usuário informa que
recebeu). Falta o sentido inverso — **registramos quem devemos** — e o estado
intermediário "paguei, mas o credor ainda não confirmou".

Este design cria dívidas do usuário com terceiros na mesma aba, com fluxo de
três estados.

## Decisões de produto (aprovadas no chat)

1. **Três estados**: `Devendo` → `Pago (você)` → `Confirmado pelo credor`.
2. **Só quem recebe confirma.** Quem deve não decreta que pagou; o selo final
   só existe porque o credor informou (fora do app, na conversa) e o usuário
   registra. É o mesmo princípio de todo o sistema: ninguém confirma nada por
   conta própria — o dono POSTa o que aconteceu.
3. **Single-user.** As duas pessoas não conversam entre contas; não há
   convite, vínculo ou conta do credor. A confirmação chega por conversa
   real e vira um toque no app.
4. **WhatsApp fora desta rodada.** Se depois quiser "compartilhar cobrança",
   é pedido novo.
5. **"Me devem" não muda** — o lado existente (splits + settle) fica como está.
6. **Cada dívida é só a minha parte.** O usuário não lança as partes dos
   outros participantes (isso é conta do credor no lado dele).
7. **Dívida pendente não mexe no saldo.** O saldo só cai quando o usuário
   aperta "paguei" (simetria com parte de divisão pendente, que não mexe).

## Goals

- Registrar "devo R$ X a Nome — motivo" na aba Divisões.
- Fluxo de três estados com a regra "só o recebedor confirma".
- No "paguei", o valor sai da carteira de verdade (transação de ledger).
- Validações e mensagens em pt-BR amigável, sem jargão de backend.
- Testes TDD (e2e shell RED primeiro + JUnit) e gate de review de UI.

## Non-goals

- Multi-user, notificação, WhatsApp/qualquer integração externa.
- Vencimento/data de pagamento, parcelamento, pagamento parcial.
- Categoria na dívida (diferente de splits, que aceitam `category_id`).
- Mudanças no lado "Me devem".
- Revisitar status "cobrado" do lado existente.

## Anti-goals

- Mexer no saldo sem POST explícito do dono ("nunca fingir pagamento").
- `f64`/`Double`/JSON number em dinheiro — string na API, `Decimal`/`BigDecimal` no código.
- Mensagem de erro técnica (ISO, SQL, inglesa) na tela do cliente.
- `query!` macro (exige `DATABASE_URL` em compile time — proibido no repo).

## Restrições inegociáveis do projeto

- Saldo sempre derivado do ledger; transação equilibrada; nada de `UPDATE` direto em saldo.
- `user_id` vem do token; query filtra por `wallet_id` da carteira ativa do dono — recurso alheio na URL não existe para quem pergunta (mesmo padrão de `splits::settle_part`).
- Money: `rust_decimal::Decimal` + `NUMERIC(19,4)`; API sempre string (`"10.50"`).
- Depois de editar `.sql`, `cargo build` obrigatório (`sqlx::migrate!` embute as migrations).

## Comportamento

### Estados e transições

| De | Para | Quem | Efeito |
|----|------|------|--------|
| (criar) | `PENDING` | usuário | **Nenhuma** transação de ledger. Só registro. |
| `PENDING` | `PAID` | usuário ("paguei") | Posta `TransactionType::Expense` debitando `USER_AVAILABLE` pelo valor da dívida. Dinheiro saiu — o usuário declara, como em `bills/{id}/pay`. |
| `PAID` | `CONFIRMED` | usuário ("credor confirmou") | **Só status.** Nenhum movimento de dinheiro. |

- Transições são idempotentes por guarda de status (`UPDATE ... WHERE status = ...`
  + checagem de `rows_affected` → 409), mesmo padrão de `settle_part`.
- `CONFIRMED` é estado final, imutável.

### Endpoints (novo módulo `routes/debts.rs`, prefixo `/debts`)

- `POST /debts` — cria (`credor`, `valor`, `descrição`) → 201.
- `GET /debts` — lista da carteira ativa, ordenada por `created_at DESC`.
- `POST /debts/{id}/pay` — `PENDING → PAID` (postagem no ledger).
- `POST /debts/{id}/confirm` — `PAID → CONFIRMED`.
- `PATCH /debts/{id}` / `DELETE /debts/{id}` — ver "Edição e apagar" abaixo.

Resposta (`DebtResponse`): `id`, `creditor_name`, `description`, `amount`
(string Money), `status` (`PENDING`/`PAID`/`CONFIRMED`), `paid_at`,
`confirmed_at`, `created_at`.

### Edição e apagar — decidido: (B) pago trava

Resposta do usuário em 2026-10-13: **(B)**. Enquanto `PENDING`, edita os
campos e apaga livremente (nenhuma transação de ledger existe). De `PAID` em
diante: **não há edição nem delete** — só `CONFIRM`. `CONFIRMED` é imutável.
Alinhado à convenção existente (`bills` cancela só antes de pagar;
transações de carteira não têm delete) e **sem nenhuma maquininha de
estorno no ledger**. `PATCH`/`DELETE` existem apenas para `PENDING`.

### Validações (422, pt-BR, sem jargão)

Espelham as de `splits`: `"credor é obrigatório"`, `"descrição é obrigatória"`,
`"valor deve ser maior que zero"`, `"máximo de N caracteres"` (se houver
limite — decidir no build). 409: `"dívida já está paga"`,
`"dívida ainda não foi paga"`, `"só dívidas pendentes podem ser apagadas"`.

### Banco — migração `0009_debts.sql`

Tabela `debts`: `id`, `wallet_id` (FK RESTRICT), `creditor_name`,
`description`, `amount NUMERIC(19,4) CHECK > 0`, `status TEXT CHECK IN
('PENDING','PAID','CONFIRMED')`, `paid_at TIMESTAMPTZ`,
`paid_transaction_id UUID REFERENCES ledger_transactions ON DELETE RESTRICT`,
`confirmed_at TIMESTAMPTZ`, `created_at`. CHECKs de consistência: `paid_at`
e `paid_transaction_id` nascem juntos (nulos os dois ou presentes os dois);
`CONFIRMED ⇒ paid_at NOT NULL`. Índice `(wallet_id, created_at DESC)`.
Sem trigger de soma (registro de linha única — a invariant de soma é de
splits, não se aplica). Sem `participant_user_id`: não há segundo usuário.

### App Android

- `SplitsScreen.kt` ganha dois blocos: **"Me devem"** (existente, sem
  mudança de comportamento) e **"Eu devo"** (lista nova com chip de status
  e ações).
- Formulário novo (padrão `NovaSplitScreen`/`CabecalhoComVoltar`): campos
  **Credor**, **Valor (R$)**, **Motivo**; usa `Money`, `filtrarValor`,
  estado pt-BR. Botões por estado: `Paguei…` (PENDING), `Confirmar recebimento
  pelo credor` (PAID) — wording final pelo builder dentro do gate.
- Chips de status com wording que qualquer cliente entende sem ler código:
  pendente/pago faltando confirmação/confirmado — **sem jargão** (`PENDING`
  nunca aparece na tela).
- Modelos novos em `Models.kt` + métodos em `KaveliPayApi.kt` (padrão
  `getItens`/`post` já usado para `/splits`).
- Saldo na tela nunca recalculado localmente — só o que o backend devolve.

### LGPD (chamadas minhas, após checagem do revisor)

- **Export JSON** (`GET /lgpd/export`): ganha seção **nova** `dividas` com
  todas as dívidas (todos os status, Money como string). Fato apurado: o
  comentário do código promete "divisões" mas o JSON **não** as exporta
  hoje — gap pré-existente, **fora do escopo** desta feature.
- **Export CSV** (`GET /lgpd/export.csv`): **inalterado** — é contrato de
  planilha documentado, colunas fixas vêm só do ledger. Dívida `PAID` já
  aparece lá automaticamente (é transação `EXPENSE`); `PENDING` não é
  transação e não cabe no formato.
- **Eliminação de conta** (`DELETE /lgpd/account`): dívida segue exatamente
  o tratamento de divisões — **nada é apagado** (a doutrina do repo é
  anonimizar, ledger preservado; `bills`/`charges`/`splits` sobrevivem
  intocados).

### Testes e contagens

- **TDD**: escrever primeiro `backend/tests/debts_e2e.sh` (RED), depois
  implementar (GREEN). Cobrir: validações 422, criação, listagem scoping
  (carteira alheia não aparece), `pay` debita saldo (prova via
  `/wallet/derivation` ou saldo), duplo `pay` 409, `confirm` exige `PAID`,
  delete/patch apenas em `PENDING` (decisão (B)), export JSON contém a
  seção `dividas`.
- `cargo test` (128) continua verde; JUnit do app ganha testes puros das
  regras novas (transições/validação) — hoje 27.
- Após implementar: atualizar contagens em `AGENTS.md` e
  `docs/ambiente-e-build.md` (número real medido, não chute).
- Reindexar GitNexus: `gitnexus analyze --skip-agents-md --skip-git`.

### Gate de UI

`docs/ui-review.md` vale para as telas alteradas: checklist mecânico
(entrada no padrão do cliente, erro pt-BR, dinheiro sem float, estados,
contraste) + screenshot no Waydroid contra a referência Nubank. Nenhuma tela
fecha sem o gate.

## O que fica para o builder

- SQL exato da migração 0009 e ordem das rotas no `router()`.
- Estrutura/nome dos composables novos e do formulário.
- Wording exato dos chips e botões (satisfazendo o gate).
- Estrutura do `debts_e2e.sh` (padrão dos scripts existentes).
- Se `PATCH` separa campos congelados por status (sob decisão (B): na
  prática PATCH só existe em `PENDING`).
- Qualquer detalhe de implementação não coberto aqui — desde que dentro das
  restrições acima.
