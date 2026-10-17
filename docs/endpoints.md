# Endpoints

> Movido de AGENTS.md em 2026-10-12 (enxugamento para reduzir contexto repetido por turno). Conteúdo verbatim.

## Endpoints

- `POST /auth/register` — cria usuário **e wallet com as contas do ledger** na
  mesma transação
- `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`
- `GET /auth/me` — rota protegida de referência; o `user_id` vem do token
- `GET /wallet` — saldo derivado, moeda, status
- `POST /wallet/transactions` — `{type, amount, description?}`; `amount` é
  **string** (`"10.50"`). Float é 422.
- `GET /wallet/transactions?limit=&offset=` — extrato paginado (máx 200)
- `POST /bills` — cria como **sempre PENDING**; `amount` string, `due_date`
  `YYYY-MM-DD`, `linha_digitavel` opcional (texto livre — o app só lê/digita,
  nunca interpreta; ver lacuna 12B × seção 9 em `docs/spec.md`)
- `GET /bills?status=&overdue=&limit=&offset=` — `?status=OVERDUE` é 422 de propósito
- `POST /bills/{id}/pay` — marca pago o que o **usuário afirma** ter pago; gera
  transação `BILL_PAID`. Idempotente: segunda tentativa é 409 sem debitar de novo
- `DELETE /bills/{id}` — cancela; só PENDING (pago é 409)
- `POST /splits` — `{description, total_amount, parts:[{name, amount}]}`. A soma
  das partes **tem** de fechar com o total; a despesa inteira sai da carteira
- `GET /splits`
- `POST /splits/{id}/parts/{part_id}/settle` — quita a parte e devolve o dinheiro
  ao dono via transação `SETTLEMENT`
- `POST /charges` — cria cobrança e devolve o QR. **NÃO gera lançamento**
- `GET /charges`, `GET /charges/{id}/qr`
- `POST /charges/{id}/receive` — registra o recebimento; único toque na carteira
- `DELETE /charges/{id}` — cancela; só PENDING
- `POST /investments/lookup` — `{symbol, kind}` → `{found, name?, summary?}`;
  prefill por IA do "Adicionar ativo" (ver a seção de IA abaixo)
- `POST /debts` — cria dívida `{creditor_name, amount, description}`. **Nenhuma
  transação de ledger** (como `/charges`): registrar que devo não mexe no saldo.
  `amount` string, float é 422. Vira sempre `PENDING`
- `GET /debts` — carteira ativa, mais recentes primeiro
- `POST /debts/{id}/pay` — declara o pagamento: debita o ledger (`Expense`) e
  marca `PAID` na mesma transação. Idempotente: segunda vez é 409 sem debitar
  de novo
- `POST /debts/{id}/confirm` — credor confirmou: muda **só status**
  (`PAID → CONFIRMED`), nunca dinheiro; confirmar duas vezes é 409
- `PATCH /debts/{id}` — edição parcial `{creditor_name?, amount?, description?}`;
  só `PENDING` (pago/confirmado é 409); 422 com as mesmas mensagens do POST
- `DELETE /debts/{id}` — só `PENDING` (estado final é 409); pendente nunca
  tocou o ledger, então não estorna nada. Id de outra carteira é 404, nunca 403
