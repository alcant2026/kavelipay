-- Contas a pagar (boletos) do usuário.
--
-- REGRA CENTRAL DESTA TABELA: `status` NÃO aceita 'OVERDUE'. OVERDUE não é um
-- estado persistido, é a apresentação de um PENDING cujo due_date já passou.
-- Se o CHECK aceitasse OVERDUE, bastava alguém marcar o boleto vencido e ele
-- deixaria de ser PENDING — e o backend perderia a informação de que aquilo
-- AINDA NÃO FOI PAGO. O status tem de ser derivado, nunca gravado.

CREATE TABLE bills (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id     UUID           NOT NULL REFERENCES wallets (id) ON DELETE RESTRICT,
    description   TEXT           NOT NULL,
    beneficiario  TEXT,
    amount        NUMERIC(19, 4) NOT NULL,
    currency      CHAR(3)        NOT NULL DEFAULT 'BRL',
    due_date      DATE           NOT NULL,
    -- Linha digitável / código de barras informado pelo usuário. Texto livre:
    -- o backend NÃO interpreta nem valida o conteúdo (ver lacuna 12B × seção 9
    -- da spec: quem valida é o backend, mas o formato do código não foi
    -- especificado).
    linha_digitavel TEXT,
    -- 'PENDING' | 'PAID' | 'CANCELED'. OVERDUE é impossível por CHECK.
    status        TEXT           NOT NULL DEFAULT 'PENDING',
    paid_at       TIMESTAMPTZ,
    -- Transação do ledger gerada ao pagar. Trava a idempotência: um boleto não
    -- pode ser pago duas vezes, e o vínculo prova de onde veio o dinheiro.
    paid_transaction_id UUID REFERENCES ledger_transactions (id) ON DELETE RESTRICT,
    -- Comprovante anexado pelo usuário (upload fica fora do MVP).
    comprovante_url   TEXT,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT bills_status_check CHECK (status IN ('PENDING', 'PAID', 'CANCELED')),
    CONSTRAINT bills_amount_positive CHECK (amount > 0),
    CONSTRAINT bills_currency_check CHECK (currency = upper(currency)),
    CONSTRAINT bills_description_not_blank CHECK (length(btrim(description)) > 0),

    -- Pagar exige instante; pendente/cancelado não têm. Impede um PENDING com
    -- paid_at preenchido por bug.
    CONSTRAINT bills_paid_has_timestamp
        CHECK ((status = 'PAID') = (paid_at IS NOT NULL)),

    -- Pagar exige transação no ledger; os outros estados não. É o que garante
    -- que nenhuma saída de dinheiro acontece sem lançamento correspondente.
    CONSTRAINT bills_paid_has_transaction
        CHECK ((status = 'PAID') = (paid_transaction_id IS NOT NULL))
);

-- Extrato lista as pendências em ordem de vencimento; é a tela principal.
CREATE INDEX bills_wallet_due_idx ON bills (wallet_id, due_date);
-- Pagamentos idempotentes: o UPDATE condicional em mark_paid usa este índice.
CREATE INDEX bills_wallet_status_idx ON bills (wallet_id, status);

-- Um boleto não pode ser pago com uma transação de outra wallet.
CREATE OR REPLACE FUNCTION assert_bill_transaction_wallet()
RETURNS TRIGGER AS $$
DECLARE
    txn_wallet UUID;
BEGIN
    IF NEW.paid_transaction_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT wallet_id INTO txn_wallet
    FROM ledger_transactions WHERE id = NEW.paid_transaction_id;

    IF txn_wallet IS DISTINCT FROM NEW.wallet_id THEN
        RAISE EXCEPTION
            'transação % pertence a outra wallet que a do boleto %',
            NEW.paid_transaction_id, NEW.id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER bills_transaction_wallet_match
BEFORE INSERT OR UPDATE ON bills
FOR EACH ROW EXECUTE FUNCTION assert_bill_transaction_wallet();