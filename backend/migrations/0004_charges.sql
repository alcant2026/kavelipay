-- Cobranças: o usuário pede dinheiro a outra pessoa.
--
-- CONTABILIDADE (duas pernas, porque a cobrança não é pagamento):
--  1. Criar cobrança:  débito RECEIVABLE, crédito INCOME  (ativo e receita)
--  2. Receber cobrança: débito USER_AVAILABLE, crédito RECEIVABLE
--
-- Criar a cobrança NÃO move dinheiro — só registra o direito a receber. Por isso
-- a carteira não muda no passo 1, e sim no 2. Sem essa separação, "criar
-- cobrança" pareceria entrada de dinheiro.

CREATE TABLE charges (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id    UUID           NOT NULL REFERENCES wallets (id) ON DELETE RESTRICT,
    description  TEXT           NOT NULL,
    amount       NUMERIC(19, 4) NOT NULL,
    currency     CHAR(3)        NOT NULL DEFAULT 'BRL',
    -- 'PENDING' | 'RECEIVED' | 'CANCELED'. Sem 'OVERDUE': cobrança vencida
    -- continua PENDING, igual ao boleto.
    status       TEXT           NOT NULL DEFAULT 'PENDING',
    due_date     DATE,
    -- Nome de quem foi cobrado. Texto livre: a cobrança pode ser para alguém
    -- sem conta no KaveliPay.
    debtor_name  TEXT,
    -- Criar cobrança NÃO gera lançamento no ledger: registrar um direito a
    -- receber não move dinheiro. O valor a receber é o somatório de `charges`
    -- em PENDING. O lançamento só acontece no recebimento, e é ele que exige
    -- carteira e transação — daí a coluna ser só do recebimento.
    received_at  TIMESTAMPTZ,
    received_transaction_id UUID REFERENCES ledger_transactions (id) ON DELETE RESTRICT,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT charges_status_check CHECK (status IN ('PENDING', 'RECEIVED', 'CANCELED')),
    CONSTRAINT charges_amount_positive CHECK (amount > 0),
    CONSTRAINT charges_currency_check CHECK (currency = upper(currency)),
    CONSTRAINT charges_description_not_blank CHECK (length(btrim(description)) > 0),
    CONSTRAINT charges_received_consistent
        CHECK ((status = 'RECEIVED') = (received_at IS NOT NULL)),
    -- PENDING e CANCELED nunca podem ter transação: se tivessem, haveria
    -- lançamento de dinheiro para uma cobrança que não entrou.
    CONSTRAINT charges_pending_has_no_transaction
        CHECK ((status = 'RECEIVED') = (received_transaction_id IS NOT NULL))
);

CREATE INDEX charges_wallet_status_idx ON charges (wallet_id, status);
CREATE INDEX charges_wallet_due_idx ON charges (wallet_id, due_date);

-- Uma transação de recebimento tem que ser da mesma wallet da cobrança.
CREATE OR REPLACE FUNCTION assert_charge_transaction_wallet()
RETURNS TRIGGER AS $$
DECLARE
    txn_wallet UUID;
BEGIN
    IF NEW.received_transaction_id IS NOT NULL THEN
        SELECT wallet_id INTO txn_wallet
        FROM ledger_transactions WHERE id = NEW.received_transaction_id;

        IF txn_wallet IS DISTINCT FROM NEW.wallet_id THEN
            RAISE EXCEPTION 'transação de recebimento % é de outra wallet que %',
                NEW.received_transaction_id, NEW.id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER charges_transaction_wallet_match
BEFORE INSERT OR UPDATE ON charges
FOR EACH ROW EXECUTE FUNCTION assert_charge_transaction_wallet();