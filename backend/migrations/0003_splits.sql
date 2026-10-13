-- Divisão de despesa: uma despesa paga por uma pessoa e repartida entre N.
--
-- INVARIANTE CENTRAL: SUM(expense_split_parts.owed_amount) DEVE ser igual a
-- expense_splits.total_amount. Se não for, R$ 100 divididos "em partes" somam
-- R$ 90 e o dinheiro evapora — sem nenhuma transação de ledger envolvida, o
-- que a torna invisível para as garantias contábeis já existentes.
--
-- Por isso a soma é verificada por constraint trigger deferrable, exatamente
-- como o equilíbrio das transações. O CHECK de linha única não serve: o
-- problema aqui é justamente o agregado.

CREATE TABLE expense_splits (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id    UUID           NOT NULL REFERENCES wallets (id) ON DELETE RESTRICT,
    -- Despesa paga por quem é o dono da wallet. Os lançamentos saem daqui.
    description  TEXT           NOT NULL,
    total_amount NUMERIC(19, 4) NOT NULL,
    currency     CHAR(3)        NOT NULL DEFAULT 'BRL',
    -- Transação que tirou o total da carteira do dono (débito integral).
    ledger_transaction_id UUID NOT NULL REFERENCES ledger_transactions (id) ON DELETE RESTRICT,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT expense_splits_total_positive CHECK (total_amount > 0),
    CONSTRAINT expense_splits_description_not_blank CHECK (length(btrim(description)) > 0),
    CONSTRAINT expense_splits_currency_check CHECK (currency = upper(currency))
);

CREATE INDEX expense_splits_wallet_idx ON expense_splits (wallet_id, created_at DESC);

-- Cada participante deve uma parte. `participant_user_id` é opcional: a
-- divisão é com quem o usuário quiser (irmão, amigo sem conta), não
-- necessariamente outra pessoa do KaveliPay.
CREATE TABLE expense_split_parts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    split_id     UUID           NOT NULL REFERENCES expense_splits (id) ON DELETE CASCADE,
    participant_name TEXT       NOT NULL,
    participant_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    owed_amount  NUMERIC(19, 4) NOT NULL,
    -- Fica preenchido quando a parte é quitada. O valor NÃO é alterado: a soma
    -- das partes tem que continuar batendo com o total mesmo depois de tudo
    --iquidado, senão o invariante se desfaria com o tempo.
    settled_at   TIMESTAMPTZ,
    -- Transação que devolveu o dinheiro ao dono da wallet.
    settled_transaction_id UUID REFERENCES ledger_transactions (id) ON DELETE RESTRICT,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT expense_split_parts_owed_positive CHECK (owed_amount > 0),
    CONSTRAINT expense_split_parts_name_not_blank CHECK (length(btrim(participant_name)) > 0),
    -- Quitado exige instante e transação; não quitado, nenhum dos dois.
    CONSTRAINT expense_split_parts_settled_consistent
        CHECK ((settled_at IS NULL) = (settled_transaction_id IS NULL))
);

CREATE INDEX expense_split_parts_split_idx ON expense_split_parts (split_id);
-- Um participante não entra duas vezes na mesma divisão.
CREATE UNIQUE INDEX expense_split_parts_split_name_uniq
    ON expense_split_parts (split_id, lower(btrim(participant_name)));

-- ── Invariante: a soma das partes é o total ──────────────────────────────────
-- DEFERRABLE INITIALLY DEFERRED: as partes são inseridas em statements
-- separados, e o total só fecha no COMMIT. A verificação roda lá.

-- A verificação é feita em DUAS funções, não uma só com CASE.
--
-- Uma função única com `CASE WHEN TG_TABLE_NAME = 'expense_splits' THEN NEW.id
-- ELSE NEW.split_id END` NÃO funciona: o Postgres planeja a expressão inteira
-- contra o tipo da linha do trigger, então referenciar `NEW.split_id` numa
-- tabela sem essa coluna é erro mesmo no ramo que nunca é tomado —
-- "record new has no field split_id". Duas funções evitam a referência
-- cruzada.
CREATE OR REPLACE FUNCTION assert_split_parts_sum_on_parts()
RETURNS TRIGGER AS $$
DECLARE
    alvo UUID := COALESCE(NEW.split_id, OLD.split_id);
    soma_partes NUMERIC(19, 4);
    total NUMERIC(19, 4);
    qtd INTEGER;
BEGIN
    SELECT s.total_amount INTO total
    FROM expense_splits s WHERE s.id = alvo;

    SELECT COALESCE(SUM(p.owed_amount), 0), COUNT(*)
    INTO soma_partes, qtd
    FROM expense_split_parts p WHERE p.split_id = alvo;

    IF qtd = 0 THEN
        RAISE EXCEPTION 'divisão % não tem participantes', alvo
            USING ERRCODE = 'check_violation';
    END IF;

    IF soma_partes <> total THEN
        RAISE EXCEPTION
            'divisão % não fecha: soma das partes % != total %',
            alvo, soma_partes, total
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION assert_split_parts_sum_on_split()
RETURNS TRIGGER AS $$
DECLARE
    soma_partes NUMERIC(19, 4);
    qtd INTEGER;
BEGIN
    SELECT COALESCE(SUM(p.owed_amount), 0), COUNT(*)
    INTO soma_partes, qtd
    FROM expense_split_parts p WHERE p.split_id = NEW.id;

    IF qtd = 0 THEN
        RAISE EXCEPTION 'divisão % não tem participantes', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;

    IF soma_partes <> NEW.total_amount THEN
        RAISE EXCEPTION
            'divisão % não fecha: soma das partes % != total %',
            NEW.id, soma_partes, NEW.total_amount
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER expense_split_parts_sum_check
AFTER INSERT OR UPDATE OR DELETE ON expense_split_parts
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION assert_split_parts_sum_on_parts();

-- A mesma verificação dispara pela própria divisão. Sem isto, uma divisão
-- inserida sem NENHUMA parte passaria: nenhum trigger de `parts` dispararia
-- e o COMMIT seria aceito, deixando uma divisão sem devedor nenhum.
CREATE CONSTRAINT TRIGGER expense_splits_sum_check
AFTER INSERT OR UPDATE ON expense_splits
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION assert_split_parts_sum_on_split();

-- Uma divisão não pode apontar para transação de outra wallet.
CREATE OR REPLACE FUNCTION assert_split_transaction_wallet()
RETURNS TRIGGER AS $$
DECLARE
    txn_wallet UUID;
BEGIN
    SELECT wallet_id INTO txn_wallet
    FROM ledger_transactions WHERE id = NEW.ledger_transaction_id;

    IF txn_wallet IS DISTINCT FROM NEW.wallet_id THEN
        RAISE EXCEPTION
            'transação % pertence a outra wallet que a da divisão %',
            NEW.ledger_transaction_id, NEW.id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER expense_splits_transaction_wallet_match
BEFORE INSERT OR UPDATE ON expense_splits
FOR EACH ROW EXECUTE FUNCTION assert_split_transaction_wallet();