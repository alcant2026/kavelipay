-- Integração com o Mercado Pago (Checkout Pro).
--
-- `mp_preference_id` guarda a preferência criada para a cobrança, para reabrir
-- o mesmo link em vez de criar outro a cada toque no botão.
--
-- `mp_payment_id` é o id do pagamento no Mercado Pago. A unique é o que garante
-- idempotência: o webhook é reenviado várias vezes pelo Mercado Pago, e duas
-- notificações do MESMO pagamento não podem creditar o ledger duas vezes. O
-- índice é parcial porque a maioria das cobranças é recebida no braço
-- (`receive_charge`), sem pagamento nenhum do outro lado.
ALTER TABLE charges
    ADD COLUMN mp_preference_id TEXT,
    ADD COLUMN mp_payment_id    TEXT;

CREATE UNIQUE INDEX charges_mp_payment_uniq
    ON charges (mp_payment_id)
    WHERE mp_payment_id IS NOT NULL;
