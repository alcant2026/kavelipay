-- Cada usuário conecta a própria conta do Mercado Pago (OAuth).
--
-- Antes: uma credencial só, a do dono do app. Todo pagamento de cobrança e de
-- divisão caía nessa conta — ou seja, o KaveliPay virava custódia do dinheiro
-- de terceiro, exatamente o que a spec proíbe. Com o token do próprio dono da
-- cobrança, o dinheiro cai direto na conta de quem cobrou e a plataforma fica
-- de fora.
--
-- Os tokens são segredo de terceiro: ficam cifrados com pgp_sym_encrypt e a
-- chave mora em env var (`MP_TOKEN_KEY`), nunca no banco. Sem a chave, um dump
-- do banco não entrega a conta do Mercado Pago de ninguém.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE mp_contas (
    user_id       UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    mp_user_id    BIGINT      NOT NULL,
    -- ciphertext em base64: pgp_sym_encrypt/decode no lugar de bytea, que é
    -- chato de ler em psql e de passar por serde.
    access_token  TEXT        NOT NULL,
    refresh_token TEXT        NOT NULL,
    expira_em     TIMESTAMPTZ NOT NULL,
    criado_em     TIMESTAMPTZ NOT NULL DEFAULT now(),
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX mp_contas_mp_user_uniq ON mp_contas (mp_user_id);

-- `state` do OAuth: um por tentativa, de uso único, guardado no servidor.
-- Sem isso, qualquer código de autorização poderia ser colado na conta errada.
CREATE TABLE mp_oauth_states (
    state     TEXT PRIMARY KEY,
    user_id   UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);
