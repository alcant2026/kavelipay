-- Redefinição de senha ("esqueci minha senha").
--
-- O token cru NUNCA é gravado, só o SHA-256 — igual ao refresh token e ao
-- código de ativação. Quem lê o banco não consegue trocar a senha de ninguém.
--
-- Um pedido novo APAGA os pendentes do usuário: dois links válidos ao mesmo
-- tempo só aumentam a superfície de ataque sem servir a ninguém.
--
-- `expira_em` é 30 minutos; `usado_em` torna o token de uso único. O par
-- (hash único + uso único) é o que permite o handler consumir com um UPDATE
-- só, sem leitura-antes-de-escrita e sem corrida.

CREATE TABLE password_resets (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash TEXT        NOT NULL,
    expira_em  TIMESTAMPTZ NOT NULL,
    usado_em   TIMESTAMPTZ,
    criado_em  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT password_resets_hash_ok CHECK (char_length(token_hash) = 64)
);

CREATE UNIQUE INDEX password_resets_hash_uniq ON password_resets (token_hash);
CREATE INDEX password_resets_pendentes ON password_resets (user_id)
    WHERE usado_em IS NULL;
