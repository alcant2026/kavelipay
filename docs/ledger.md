# Ledger: sinal, invariantes e convenções

> Movido de AGENTS.md em 2026-10-12 (enxugamento para reduzir contexto repetido por turno). Conteúdo verbatim.

## Sinal contábil: o erro que os testes de equilíbrio não pegam

Inverter débito e crédito numa transação **não** viola
`total_debits = total_credits` — a transação continua equilibrada, com o saldo
do usuário errado ao contrário. Por isso existem dois testes de sinal em
`ledger.rs`:
- toda entrada de dinheiro **credita** `USER_AVAILABLE` (`ENTRY`,
  `SETTLEMENT`, `CHARGE_RECEIVED`)
- toda saída **debita** `USER_AVAILABLE` (`EXPENSE`, `BILL_PAID`)

Já aconteceu: `CHARGE_RECEIVED` estava como `(UserAvailable, Receivable)` e o
recebimento DIMINUÍA a carteira. Só o teste de saldo na API pegou.

- **Cobrar não gera lançamento.** Registrar um direito a receber não move
  dinheiro; debitá-lo de `INCOME` seria receita negativa. O valor a receber
  autoritativo é `SUM(charges WHERE status='PENDING')` — a tabela, não o ledger.
- `RECEIVABLE` é conta de contrapartida: acumula o débito de cada recebimento
  e **não zera**. Não a use para responder "quanto devo receber".

## Divisão de despesa: o invariante soma = total

- `SUM(expense_split_parts.owed_amount)` **tem** de ser igual a
  `expense_splits.total_amount`. Sem isso, R$ 100 divididos somam R$ 90 e o
  dinheiro evapora — invisível para as garantias contábeis, porque não há
  transação de ledger envolvida na soma.
- Aplicado por DOIS constraint triggers deferrable (um em `expense_splits`, um
  em `expense_split_parts`) que só rodam no COMMIT. INSERT direto no banco com
  partes que não fecham é recusado — coberto por teste.
- **Não unifique os dois triggers numa função só com `CASE WHEN
  TG_TABLE_NAME = ...`**: o Postgres planeja a expressão inteira contra o tipo
  da linha, então referenciar `NEW.split_id` numa tabela sem essa coluna falha
  mesmo no ramo nunca tomado. Foram duas funções por isso.
- `owed_amount` **nunca** muda ao quitar (só `settled_at`). Se o valor fosse
  zerado na quitação, o invariante se desfaria com o tempo.
- `SETTLEMENT` é a inversão contábil de `BILL_PAID`: debita `EXPENSE`, credita
  `USER_AVAILABLE`. Inverter os lados produz transação equilibrada com o saldo
  errado — o erro que "funciona" e não aparece em nenhum teste de equilíbrio.

## OVERDUE nunca é persistido

- `bills.status` só aceita `PENDING | PAID | CANCELED` — o CHECK recusa
  `OVERDUE`. Um UPDATE direto no banco também é recusado (coberto por teste).
- `is_overdue` no JSON é **derivado na leitura** (`PENDING AND due_date < hoje`).
- Para listar vencidos use `?overdue=true`, não `?status=OVERDUE`.
- Se alguém adicionar OVERDUE ao CHECK, o motivo some: um boleto marcado vencido
  deixa de ser PENDING e o backend perde a informação de que NÃO foi pago.

## Convenções

- Valores monetários são `rust_decimal::Decimal` + `NUMERIC(19,4)`. `f64` em
  qualquer lugar que toque dinheiro é bug.
- **Dinheiro na API é sempre string** (`"10.50"`), nunca número JSON. O tipo
  `Money` recusa `visit_f64` na desserialização com 422 — cliente que manda
  `10.50` é rejeitado em vez de convertido com perda.
- `Money` serializa sempre com 2 casas (`"700.00"`), mesmo vindo de inteiro.
- Erro de domínio (`AppError`) tem três partes: `status()` decide 4xx vs 5xx,
  `code()` é o identificador estável para o cliente, `client_message()` NUNCA
  revela detalhe de infraestrutura em 5xx.
- Handlers aceitam `AuthUser` para saber de quem é a requisição. Nunca adicione
  `user_id` ao body/query de um handler — os e2e cobrem exatamente essa
  tentativa e falham se alguém ceder.
- SQL é escrito à mão com `query`/`query_scalar`/`query_as`, NÃO `query!` de
  macro: sem `DATABASE_URL` em tempo de compilação, as macros não compilam.
- Saldo NUNCA é calculado como "saldo anterior + valor". Sempre `saldo()` a
  partir dos lançamentos. Um teste do ledger_e2e compara a API com a soma SQL.
- `TransactionType::par_de_contas()` decide o sinal contábil (débito/crédito).
  Todo tipo toca `USER_AVAILABLE`; um teste garante isso.
- Boleto pago gera a transação do ledger ANTES do UPDATE de status: se o
  ledger recusar, o boleto continua PENDING. O inverso marcaria pago sem dinheiro.
- `POST /bills/{id}/pay` não é pagamento real — é registro do que o usuário
  afirma. Não existe integração, não há confirmação automática, e nada no código
  pode gerar `PAID` sem um `POST` explícito do dono da conta.

## Fronteira de confiança (o que mais se viola por descuido)

- `user_id` vem **do token**, nunca do corpo da requisição.
- Toda query de carteira, saldo, transação ou extrato filtra por `user_id` do dono. Um id de recurso vindo da URL só vale para o próprio dono; caso contrário 403.
- Valor nunca vem do app: o backend recalcula e valida a partir do ledger, sempre.
- Consequência no cliente: a tela nunca é fonte da verdade — reflete o que o backend devolveu. Saldo calculado localmente é bug, não otimização.

## Invariantes que a spec obriga (viram testes)

- Transação com `total_debits != total_credits` é rejeitada; a transação inteira falha, não compensa entry.
- `R$ 10,50` persiste e exibe exatamente `10.50` — sem drift, sem arredondamento implícito.
- Soma dos lançamentos de uma wallet == saldo da carteira, sempre.
- Segundo usuário recebe 403 em carteira, saldo, extrato e transações alheias.
- `OVERDUE` nunca é gravado: é apresentação de `PENDING` com `due_date` vencida.
- Nenhuma tela de pagamento marca boleto como `PAID` sem fonte confiável.

## Seed e dados de demonstração

- O saldo demonstrativo de R$ 500,00 do exemplo da spec entra como **transação equilibrada no ledger**. Nunca `INSERT`/`UPDATE` direto em saldo — é a regra que o primeiro script de seed vai quebrar.
- Carteira nasce zerada e derivative; nada de saldo pré-calculado em coluna.
