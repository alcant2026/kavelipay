-- Schema inicial do KaveliPay: identidade + ledger contábil.
--
-- REGRA FUNDAMENTAL: não existe coluna de saldo em lugar nenhum. Saldo é sempre
-- SUM(créditos) - SUM(débitos) dos lançamentos da conta do ledger.
-- Todos os valores monetários são NUMERIC(19,4) — nunca float/double.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ── Identidade ───────────────────────────────────────────────────────────────

CREATE TABLE users (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email        TEXT        NOT NULL,
    -- argon2id, PHC string format. Nunca senha em claro.
    password_hash TEXT       NOT NULL,
    display_name TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Case-insensitive via lower() para impedir duplicate com caixa diferente.
CREATE UNIQUE INDEX users_email_lower_uniq ON users (lower(email));

-- Refresh tokens: hash em disco, o token cru só existe no cliente.
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  TEXT        NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX refresh_tokens_hash_uniq ON refresh_tokens (token_hash);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);

-- ── Carteira ─────────────────────────────────────────────────────────────────

-- Uma carteira por usuário: o UNIQUE em user_id é a garantia no banco,
-- não apenas na aplicação. Status: ACTIVE | BLOCKED | CLOSED.
CREATE TABLE wallets (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    currency   CHAR(3)     NOT NULL DEFAULT 'BRL',
    status     TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT wallets_status_check CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),
    CONSTRAINT wallets_currency_check CHECK (currency = upper(currency))
);

-- ── Ledger ───────────────────────────────────────────────────────────────────

CREATE TABLE ledger_accounts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id    UUID        NOT NULL REFERENCES wallets (id) ON DELETE RESTRICT,
    -- kind: USER_AVAILABLE (carteira do usuário), INCOME, EXPENSE, etc.
    kind         TEXT        NOT NULL,
    currency     CHAR(3)     NOT NULL DEFAULT 'BRL',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ledger_accounts_currency_check CHECK (currency = upper(currency)),
    CONSTRAINT ledger_accounts_wallet_kind_uniq UNIQUE (wallet_id, kind)
);

CREATE INDEX ledger_accounts_wallet_idx ON ledger_accounts (wallet_id);

CREATE TABLE ledger_transactions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id    UUID        NOT NULL REFERENCES wallets (id) ON DELETE RESTRICT,
    -- Tipo de negócio: ENTRY, EXPENSE, BILL_PAID, TRANSFER, etc.
    type         TEXT        NOT NULL,
    amount       NUMERIC(19,4) NOT NULL,
    currency     CHAR(3)     NOT NULL DEFAULT 'BRL',
    description  TEXT,
    -- status: POSTED | PENDING | REVERSED
    status       TEXT        NOT NULL DEFAULT 'POSTED',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT ledger_transactions_status_check
        CHECK (status IN ('POSTED', 'PENDING', 'REVERSED')),
    CONSTRAINT ledger_transactions_currency_check CHECK (currency = upper(currency)),
    CONSTRAINT ledger_transactions_amount_positive CHECK (amount > 0)
);

CREATE INDEX ledger_transactions_wallet_created_idx
    ON ledger_transactions (wallet_id, created_at DESC);

-- Lançamentos. `debit` e `credit` são SEMPRE mutuamente exclusivos:
-- CHECK garante que não exista lançamento com os dois preenchidos, e
-- a constraint do trigger abaixo garante o equilíbrio por transação.
CREATE TABLE ledger_entries (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID          NOT NULL REFERENCES ledger_transactions (id) ON DELETE CASCADE,
    account_id     UUID          NOT NULL REFERENCES ledger_accounts (id) ON DELETE RESTRICT,
    debit          NUMERIC(19,4) NOT NULL DEFAULT 0,
    credit         NUMERIC(19,4) NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ledger_entries_debit_or_credit
        CHECK ((debit > 0 AND credit = 0) OR (credit > 0 AND debit = 0)),
    CONSTRAINT ledger_entries_debit_non_negative CHECK (debit >= 0),
    CONSTRAINT ledger_entries_credit_non_negative CHECK (credit >= 0)
);

CREATE INDEX ledger_entries_transaction_idx ON ledger_entries (transaction_id);
CREATE INDEX ledger_entries_account_idx ON ledger_entries (account_id);

-- Uma conta não pode aparecer duas vezes na mesma transação — sem isso um
-- lançamento de débito e crédito na mesma conta passaria o CHECK acima.
CREATE UNIQUE INDEX ledger_entries_txn_account_uniq
    ON ledger_entries (transaction_id, account_id);

-- ── Equilíbrio por transação ─────────────────────────────────────────────────
-- O equilíbrio é aplicado no BANCO, não só na aplicação: nenhum caminho
-- (seed, SQL manual, bug) consegue gravar uma transação desequilibrada.
-- O trigger roda em cada statement; a validação real é o COMMIT, via
-- constraint deferrable que faz a soma no fim da transação SQL.

CREATE OR REPLACE FUNCTION assert_transaction_balanced()
RETURNS TRIGGER AS $$
DECLARE
    target_txn UUID := COALESCE(NEW.transaction_id, OLD.transaction_id);
    total_debit  NUMERIC(19,4);
    total_credit NUMERIC(19,4);
    entry_count  INTEGER;
BEGIN
    SELECT
        COALESCE(SUM(debit), 0),
        COALESCE(SUM(credit), 0),
        COUNT(*)
    INTO total_debit, total_credit, entry_count
    FROM ledger_entries
    WHERE transaction_id = target_txn;

    -- Ignora estados intermediários de uma transação multi-statement: só exige
    -- equilíbrio quando não há lançamento em inserção (rotas normais têm os
    -- lançamentos já gravados no mesmo statement/CTE).
    IF entry_count = 0 THEN
        RETURN NULL;
    END IF;

    IF total_debit <> total_credit THEN
        RAISE EXCEPTION
            'transação % não equilibrada: débitos % != créditos %',
            target_txn, total_debit, total_credit
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- INITIALLY DEFERRED: a verificação roda no COMMIT, não a cada statement.
-- Isso permite gravar os lançamentos da transação em statements separados
-- (cada insert isolado deixaria o total momentaneamente desequilibrado) e ainda
-- assim rejeitar o COMMIT inteiro se o resultado final não fechar.
CREATE CONSTRAINT TRIGGER ledger_entries_balanced
AFTER INSERT OR UPDATE OR DELETE ON ledger_entries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION assert_transaction_balanced();

-- Um lançamento nunca pode referenciar conta de outra wallet.
CREATE OR REPLACE FUNCTION assert_entry_account_matches_txn_wallet()
RETURNS TRIGGER AS $$
DECLARE
    entry_wallet UUID;
    txn_wallet   UUID;
BEGIN
    SELECT wallet_id INTO txn_wallet
    FROM ledger_transactions WHERE id = NEW.transaction_id;

    SELECT wallet_id INTO entry_wallet
    FROM ledger_accounts WHERE id = NEW.account_id;

    IF entry_wallet IS DISTINCT FROM txn_wallet THEN
        RAISE EXCEPTION
            'conta % pertence à wallet %, transação % é da wallet %',
            NEW.account_id, entry_wallet, NEW.transaction_id, txn_wallet
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entries_wallet_match
BEFORE INSERT OR UPDATE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION assert_entry_account_matches_txn_wallet();