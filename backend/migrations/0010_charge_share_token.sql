-- Token de compartilhamento da cobrança.
--
-- Por que não o `id` no QR: UUID não é segredo. Se o link da cobrança levasse
-- o id e existisse uma rota pública `/cobranca/{id}`, qualquer pessoa poderia
-- varrer cobranças alheias. O token é um segredo de 128 bits sem relação com o
-- id; só quem recebeu o link consegue ler a cobrança.
--
-- Por que o valor não vai no link: `kavelipay://cobranca/<token>?valor=10.50`
-- obrigaria o app de quem recebe a acreditar num número que veio de fora. O
-- link carrega só o token; o valor é lido do servidor em
-- `GET /cobranca/{token}`, que é a fonte da verdade (o ledger).

ALTER TABLE charges ADD COLUMN share_token TEXT;

-- Backfill do que já existe (bancos de dev/teste).
UPDATE charges SET share_token = replace(gen_random_uuid()::text, '-', '');

ALTER TABLE charges ALTER COLUMN share_token SET NOT NULL;
-- Novas linhas ganham token sozinhas: o INSERT de cobrança não precisa saber
-- que essa coluna existe, e ninguém consegue criar cobrança sem link.
ALTER TABLE charges ALTER COLUMN share_token SET DEFAULT replace(gen_random_uuid()::text, '-', '');

ALTER TABLE charges ADD CONSTRAINT charges_share_token_formato
    CHECK (share_token ~ '^[0-9a-f]{32}$');

CREATE UNIQUE INDEX charges_share_token_idx ON charges (share_token);

-- Token imutável: trocá-lo invalidaria em silêncio todo QR já compartilhado.
-- Revogar um link é cancelar a cobrança (CANCELED), não trocar o token.
CREATE OR REPLACE FUNCTION charges_share_token_imutavel()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.share_token IS DISTINCT FROM OLD.share_token THEN
        RAISE EXCEPTION 'share_token é imutável (cobrança %)', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER charges_share_token_imutavel
BEFORE UPDATE OF share_token ON charges
FOR EACH ROW EXECUTE FUNCTION charges_share_token_imutavel();
