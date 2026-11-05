-- Premium pago pelo Mercado Pago, dentro do app (Payment Brick).
--
-- Antes: o dono vendia por fora e entregava um código de ativação. Agora o app
-- cobra de verdade; o dinheiro cai na conta do Mercado Pago do KaveliPay, que
-- é receita da empresa — continua fora do ledger do usuário (ver 0013).
--
-- `premium_checkouts` liga um link de pagamento a uma pessoa. O token é o
-- segredo do link (mesmo padrão das cobranças), `mp_payment_id` é a chave de
-- idempotência (o Mercado Pago reenvia webhook por horas) e `concluido_em`
-- impede o mesmo checkout virar dois períodos de acesso.

CREATE TABLE premium_checkouts (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    token         TEXT          NOT NULL DEFAULT replace(gen_random_uuid()::text, '-', ''),
    user_id       UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    plano         TEXT          NOT NULL,
    valor         NUMERIC(19, 4) NOT NULL,
    mp_payment_id TEXT,
    concluido_em  TIMESTAMPTZ,
    criado_em     TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT premium_checkouts_token_formato CHECK (token ~ '^[0-9a-f]{32}$'),
    CONSTRAINT premium_checkouts_plano_ok      CHECK (plano IN ('mensal', 'anual')),
    CONSTRAINT premium_checkouts_valor_ok      CHECK (valor > 0)
);

CREATE UNIQUE INDEX premium_checkouts_token_uniq ON premium_checkouts (token);

-- Um pagamento aprovado ativa no máximo um checkout.
CREATE UNIQUE INDEX premium_checkouts_pagamento_uniq
    ON premium_checkouts (mp_payment_id) WHERE mp_payment_id IS NOT NULL;

-- `mercadopago` vira provedor de assinatura: recibo de pagamento real, não só
-- código digitado. A tabela de direito é a mesma — muda o provider.
ALTER TABLE assinaturas DROP CONSTRAINT assinaturas_provider_ok;
ALTER TABLE assinaturas ADD CONSTRAINT assinaturas_provider_ok
    CHECK (provider IN ('codigo', 'play', 'manual', 'mercadopago'));
