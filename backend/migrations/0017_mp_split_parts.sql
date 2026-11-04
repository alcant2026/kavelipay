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

ALTER TABLE expense_split_parts
    ADD COLUMN share_token TEXT,
    ADD COLUMN mp_preference_id TEXT,
    ADD COLUMN mp_payment_id TEXT;

UPDATE expense_split_parts SET share_token = replace(gen_random_uuid()::text, '-', '');

ALTER TABLE expense_split_parts ALTER COLUMN share_token SET NOT NULL;
-- Nova parte ganha token sozinha: o INSERT de `create_split` não precisa saber
-- que essa coluna existe, e ninguém cria parte sem link.
ALTER TABLE expense_split_parts ALTER COLUMN share_token
    SET DEFAULT replace(gen_random_uuid()::text, '-', '');

ALTER TABLE expense_split_parts ADD CONSTRAINT expense_split_parts_share_token_formato
    CHECK (share_token ~ '^[0-9a-f]{32}$');

CREATE UNIQUE INDEX expense_split_parts_share_token_idx
    ON expense_split_parts (share_token);

CREATE UNIQUE INDEX expense_split_parts_mp_payment_idx
    ON expense_split_parts (mp_payment_id) WHERE mp_payment_id IS NOT NULL;
