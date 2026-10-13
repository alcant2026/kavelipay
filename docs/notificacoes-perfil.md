# Notificações e perfil

> Movido de AGENTS.md em 2026-10-12 (enxugamento para reduzir contexto repetido por turno). Conteúdo verbatim.

## Notificações e perfil

- `POST /notifications/generate` — deriva avisos de `bills` PENDING. Sem cron:
  o app chama e o que é derivado aparece. **Idempotente** por
  `notifications_dedup_uniq (wallet, type, reference_type, reference_id)` —
  abrir o app 3 vezes não cria 3 avisos do mesmo boleto.
- Janela de aviso: `BILL_DUE` vence em até 3 dias, `BILL_OVERDUE` já venceu.
  Um único INSERT com `CASE` — assim um boleto nunca entra nos dois ramos e
  o usuário não perde o lembrete antes da data.
- `GET /history` — feed unificado (`?kind=TRANSACTION|BILL|CHARGE|SPLIT`).
  É só LEITURA: nunca recalcula saldo. O saldo vem de `ledger::saldo`, a mesma
  fonte de `/wallet` — um teste compara os dois. `?kind` inválido devolve feed
  vazio, não erro.
- `amount` no extrato e no histórico é `CASE WHEN e.debit > 0 THEN e.debit ELSE
  e.credit END`, **nunca `e.debit`**. Numa entrada a carteira é creditada e
  `debit = 0`: usar `debit` direto mostrava R$ 0,00 para todo dinheiro que
  entrava. Achado pela simulação, não por teste.
- `GET /notifications`, `POST /notifications/{id}/read`, `/read-all`
- `PATCH /profile` — campo ausente = não mexe (`COALESCE`). Enviar `null`
  também não mexe; para limpar o nome mande string vazia.
- `POST /profile/password` — **exige a senha atual** (401 sem ela) e revoga
  todas as sessões ao final. Reusa `auth::validar_senha`: duas regras de senha
  divergentes é como uma delas deixa de ser aplicada.
- `GET /profile/sessions`, `DELETE /profile/sessions/{id}`,
  `POST /profile/sessions/revoke-all` — revogar de outra pessoa dá 404.
