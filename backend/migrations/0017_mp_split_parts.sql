-- Cada parte da divisão ganha link próprio, pago no Mercado Pago.
--
-- Antes: quem "quitava" a parte era o dono da carteira clicando — a palavra
-- dele. Agora a parte tem um link público (mesmo token de 128 bits das
-- cobranças, migration 0010) e quem paga no checkout do Mercado Pago é o
-- devedor; o webhook confirma com a API deles e só então a parte é quitada.
--
-- `mp_payment_id` existe para a idempotência: o Mercado Pago reenvia a mesma
-- notificação por horas, e o índice único garantido é o que impede crédito
-- duplo no ledger.

-- Token e colunas numa única instrução, com o default avaliado linha a linha.
-- Um `UPDATE` de backfill seguido de `ALTER` quebra em produção: a tabela tem
-- constraint trigger DEFERRABLE (soma das partes), o UPDATE deixa o evento
-- pendente até o commit e o ALTER seguinte falha com 55006 "cannot ALTER TABLE
-- because it has pending trigger events".
ALTER TABLE expense_split_parts
    ADD COLUMN share_token TEXT NOT NULL DEFAULT replace(gen_random_uuid()::text, '-', ''),
    ADD COLUMN mp_preference_id TEXT,
    ADD COLUMN mp_payment_id TEXT;

ALTER TABLE expense_split_parts ADD CONSTRAINT expense_split_parts_share_token_formato
    CHECK (share_token ~ '^[0-9a-f]{32}$');

CREATE UNIQUE INDEX expense_split_parts_share_token_idx
    ON expense_split_parts (share_token);

CREATE UNIQUE INDEX expense_split_parts_mp_payment_idx
    ON expense_split_parts (mp_payment_id) WHERE mp_payment_id IS NOT NULL;
