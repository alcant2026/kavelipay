-- Dívidas ("eu devo"): o usuário registra a quem deve. O lado inverso de
-- expense_splits, onde os outros devem a ele.
--
-- Criar NÃO move dinheiro: PENDING é só registro — a carteira só muda quando
-- o dono declara o pagamento, que posta a transação ANTES de marcar PAID
-- (mesma ordem de bills/{id}/pay). A confirmação do credor é só status:
-- nenhuma transação nasce de CONFIRMED.
--
-- Sem trigger de soma: dívida é linha única, não agregado — a invariante de
-- soma é de splits e não se aplica aqui.

CREATE TABLE debts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id    UUID           NOT NULL REFERENCES wallets (id) ON DELETE RESTRICT,
    -- Quem o usuário deve. Texto livre: o credor pode não ter conta no
    -- KaveliPay — não há convite nem vínculo entre pessoas (single-user).
    creditor_name TEXT          NOT NULL,
    -- Motivo da dívida ("Jantar"). Sem porquê registrado, a pessoa não
    -- reconcilia nada quando o credor cobrar.
    description  TEXT           NOT NULL,
    amount       NUMERIC(19, 4) NOT NULL,
    -- 'PENDING' | 'PAID' | 'CONFIRMED'. Sem 'OVERDUE': não há vencimento.
    status       TEXT           NOT NULL DEFAULT 'PENDING',
    -- Instante e transação do pagamento declarado. Nascem juntos: um sem o
    -- outro seria dinheiro movido sem registro, ou registro sem dinheiro.
    paid_at      TIMESTAMPTZ,
    paid_transaction_id UUID REFERENCES ledger_transactions (id) ON DELETE RESTRICT,
    -- Quando o usuário registrou que o credor confirmou (fora do app, na
    -- conversa). Só status — nenhuma transação nasce daqui.
    confirmed_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT debts_amount_positive CHECK (amount > 0),
    CONSTRAINT debts_status_check
        CHECK (status IN ('PENDING', 'PAID', 'CONFIRMED')),
    CONSTRAINT debts_creditor_not_blank
        CHECK (length(btrim(creditor_name)) > 0),
    CONSTRAINT debts_description_not_blank
        CHECK (length(btrim(description)) > 0),
    CONSTRAINT debts_paid_consistent
        CHECK ((paid_at IS NULL) = (paid_transaction_id IS NULL)),
    -- Confirmado sem pago seria selo de quem nunca pagou.
    CONSTRAINT debts_confirmed_requires_paid
        CHECK (status <> 'CONFIRMED' OR paid_at IS NOT NULL)
);

CREATE INDEX debts_wallet_created_idx ON debts (wallet_id, created_at DESC);
