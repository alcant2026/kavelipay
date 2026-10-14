# Transparência

> Movido de AGENTS.md em 2026-10-12 (enxugamento para reduzir contexto repetido por turno). Conteúdo verbatim.

## Transparência: o que o app mostra além do saldo

A tese do produto é "não somos um banco, e aqui está a prova de que cada centavo
está accounted for". Tudo nesta seção existe para sustentar isso.

- `wallets.is_demonstrative` — **default TRUE por omissão**. Uma carteira nova
  é demonstrativa; quem algum dia lidar com dinheiro real tem de **desligar** o
  flag conscientemente, nunca o contrário por descuido. Aparece em `/wallet`,
  `/wallet/derivation` e `/reports/monthly`.
- `GET /wallet/derivation` — reconstrói o saldo lançamento a lançamento, do mais
  antigo para o mais novo, com saldo acumulado. `opening_balance` tem de ser
  igual a `balance`: é isso que torna a explicação completa. `running_balance`
  do último item é o saldo. O usuário refaz a conta à mão e bate.
- `GET /categories` — 14 padrões semeados no cadastro (5 receita + 9 despesa,
  as listas da spec seção 10/11). Semear é melhor que exigir cadastro.
- Categoria de **despesa não classifica receita** e vice-versa: 422. Sem isso o
  relatório diria "gastou 200 em Alimentação" quando foram 200 de salário.
- `PATCH /wallet/transactions/{id}/category` — classificar **depois**. O app
  sempre precisa: a pessoa registra o gasto agora e classifica amanhã.
- `GET /reports/monthly?month=YYYY-MM` (`&all_time=true`) — receitas,
  despesas, `net` (resultado do período, ≠ `balance`) e fatias por categoria com
  `share`. Filtra por `a.kind='USER_AVAILABLE'`: um lançamento tem DUAS entradas
  no ledger e sem esse filtro a despesa aparece dobrada.
- `GET /lgpd/export.csv` — `;` e vírgula decimal (`50,00`). `Decimal::ZERO`
  vira `0,00` explícito: `"0"` ao lado de `50,00` quebra a coluna no Excel.
- `GET /lgpd/export` — JSON com perfil, carteira, categorias, contas, cobranças,
  dívidas e o ledger completo (cada transação com seus 2 lançamentos, **números como
  texto** — devolver Decimal por JSON viraria float).
- `DELETE /lgpd/account` — **anonimiza, não apaga**. `ledger_entries`
  referencia `ledger_accounts` com `ON DELETE RESTRICT`; apagar em cascata
  quebraria o equilíbrio de transações de anos atrás para remover um e-mail.
  Some o PII, revoga sessões, fecha a wallet, ledger preservado e equilibrado.
  Exige `password` **e** `confirm: true`.
- `POST /bills/generate-recurring` — recorrência não é entidade nova: a próxima
  ocorrência é um `bills` comum. Idempotente por `bills_recurrence_key_uniq`
  (`<bill-fonte>:<YYYY-MM>`). `months_ahead` tem teto de 24.

Armadilhas destas rotas, já pagas:
- `ON CONFLICT (wallet_id, recurrence_key)` precisa repetir o predicado
  `WHERE recurrence_key IS NOT NULL` — o índice é parcial e sem isso o planner
  não casa a inferência.
- Avanço de mês: dia 31 + 1 mês com aritmética cega dá 3 de março. `soma_meses`
  limita ao último dia do mês alvo. Tem teste para ano bissexto.
- Relatório por categoria filtra por `a.kind='USER_AVAILABLE'`: sem isso cada
  lançamento conta duas vezes. Há teste somando as fatias e exigindo 1.0.
