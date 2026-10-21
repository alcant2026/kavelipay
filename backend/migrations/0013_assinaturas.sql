-- Assinatura premium: o app paga as próprias contas sem virar banco.
--
-- Regra de ouro: nada aqui toca o ledger do usuário. A assinatura é receita
-- do KaveliPay, não dinheiro dele. Registrar a compra como transação na
-- carteira do cliente misturaria as duas contas e quebraria o saldo
-- demonstrativo — por isso são tabelas separadas das de ledger.
--
-- Dois caminhos de ativação, com a MESMA tabela de direito:
--
-- 1. Código de ativação (hoje): o dono vende pelo checkout externo que
--    preferir (Stripe, Mercado Pago, Pix manual) e entrega um código por
--    e-mail. O app troca o código por período de acesso. Funciona em APK
--    sideload, onde Google Play Billing não roda.
-- 2. Play Billing (quando publicar): o app manda o purchase token, o backend
--    valida no Google e grava aqui. A tabela não muda; muda só o provider.
--
-- O direito é DERIVADO: `status = 'ACTIVE' AND periodo_fim > now()`. Não existe
-- coluna booleana "premium" em users — expirar é o tempo passar, não um UPDATE
-- que alguém pode esquecer de rodar (mesma filosofia do saldo).

CREATE TABLE assinaturas (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider         TEXT        NOT NULL,
    -- Id da assinatura no provedor. No código de ativação é o id do código
    -- resgatado; na Play será o purchase token. Serve para idempotência: o
    -- mesmo recibo não pode virar dois períodos.
    provider_sub_id  TEXT        NOT NULL,
    plano            TEXT        NOT NULL,
    status           TEXT        NOT NULL,
    periodo_fim      TIMESTAMPTZ NOT NULL,
    criado_em        TIMESTAMPTZ NOT NULL DEFAULT now(),
    atualizado_em    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT assinaturas_provider_ok CHECK (provider IN ('codigo', 'play', 'manual')),
    CONSTRAINT assinaturas_plano_ok    CHECK (plano IN ('mensal', 'anual')),
    CONSTRAINT assinaturas_status_ok   CHECK (status IN ('ACTIVE', 'GRACE', 'CANCELED', 'EXPIRED'))
);

-- Uma assinatura corrente por pessoa. Histórico de renovação fica no provedor;
-- duplicar linhas aqui só criaria a dúvida de qual período vale.
CREATE UNIQUE INDEX assinaturas_user_uniq ON assinaturas (user_id);

-- Idempotência do recibo: o mesmo token não pode ativar duas vezes.
CREATE UNIQUE INDEX assinaturas_recibo_uniq
    ON assinaturas (provider, provider_sub_id);

-- Consulta do direito: sempre por usuário e com o período na frente.
CREATE INDEX assinaturas_vigentes ON assinaturas (user_id, periodo_fim DESC);

-- ── Códigos de ativação ─────────────────────────────────────────────────────
--
-- O código cru NUNCA é gravado, só o SHA-256 — igual ao refresh token. Quem
-- lê o banco não consegue vender de novo um código já emitido. O código é
-- mostrado UMA vez, na resposta do admin; depois só existe o hash.

CREATE TABLE codigos_ativacao (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo_hash TEXT        NOT NULL,
    plano       TEXT        NOT NULL,
    dias        INTEGER     NOT NULL,
    criado_em   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expira_em   TIMESTAMPTZ NOT NULL DEFAULT now() + INTERVAL '30 days',
    usado_em    TIMESTAMPTZ,
    usado_por   UUID        REFERENCES users(id) ON DELETE SET NULL,

    CONSTRAINT codigos_plano_ok CHECK (plano IN ('mensal', 'anual')),
    CONSTRAINT codigos_dias_ok  CHECK (dias BETWEEN 1 AND 400),
    CONSTRAINT codigos_hash_ok  CHECK (char_length(codigo_hash) = 64)
);

CREATE UNIQUE INDEX codigos_hash_uniq ON codigos_ativacao (codigo_hash);
CREATE INDEX codigos_disponiveis ON codigos_ativacao (expira_em)
    WHERE usado_em IS NULL;
