-- Categorias, recorrência, saldo demonstrativo e bases para LGPD.
--
-- Este arquivo reúne o que responde à pergunta "e o que mais a gente mostra?".
-- As decisões não são óbvias e estão comentadas onde importam.

-- ── Saldo demonstrativo explícito ────────────────────────────────────────────
--
-- A spec (seção 4) exige que o backend deixe explícito, NO MODELO DE DADOS,
-- que o saldo não é dinheiro real. Sem esta coluna o backend devolve
-- {"balance": "4606.30"} — indistinguível de saldo real, e o app não tem como
-- avisar o usuário de nada.
--
-- Default TRUE de propósito: uma carteira nova é demonstrativa por omissão.
-- Alguém que algum dia lidar com dinheiro de verdade tem de TURAR o flag
-- conscientemente, nunca o contrário por descuido.
ALTER TABLE wallets
    ADD COLUMN is_demonstrative BOOLEAN NOT NULL DEFAULT true;

-- Comment explícito no banco: quem abre o schema vê a regra.
COMMENT ON COLUMN wallets.is_demonstrative IS
    'MVP sem custódia de dinheiro real. TRUE = saldo demonstrativo, não há dinheiro real envolvido. O backend deve surfar isso na API.';

-- ── Categorias ───────────────────────────────────────────────────────────────
--
-- A spec pede categoria em entradas (seção 10) e despesas (seção 11), e nada
-- existia. `kind` separa receita de despesa: uma categoria de despesa não pode
-- ser usada numa entrada, senão o relatório por categoria mente.
--
-- `user_id` e não `wallet_id`: a categoria pertence à pessoa, não à carteira.
-- Uma carteira destruída e recriada não pode perder o histórico de classificação.
CREATE TABLE categories (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        TEXT        NOT NULL,
    kind        TEXT        NOT NULL,
    -- Hex para a UI. NULL = o app escolhe. Não é informação financeira.
    color       TEXT,
    -- Categoria semeada pela spec. O app pode renomear, não apagar.
    is_default  BOOLEAN     NOT NULL DEFAULT false,
    -- Ordem de exibição; o app manda a lista inteira. NULL = fim da lista.
    display_order INTEGER,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT categories_kind_check CHECK (kind IN ('INCOME', 'EXPENSE')),
    CONSTRAINT categories_name_not_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT categories_color_check CHECK (color IS NULL OR color ~ '^#[0-9A-Fa-f]{6}$')
);

-- Case-insensitive: "Aluguel" e "aluguel" não podem ser duas categorias.
CREATE UNIQUE INDEX categories_user_name_kind_uniq
    ON categories (user_id, kind, lower(btrim(name)));

CREATE INDEX categories_user_kind_idx ON categories (user_id, kind);

-- Classificação opcional no lançamento. NULL = "sem categoria", que é
-- legítimo: o app sempre pode pedir a classificação depois.
ALTER TABLE ledger_transactions
    ADD COLUMN category_id UUID REFERENCES categories (id) ON DELETE SET NULL;

-- ── Contas recorrentes ───────────────────────────────────────────────────────
--
-- Aluguel, luz, streaming: o mundo real é isso. `recurrence` descreve o
-- PADRÃO; a ocorrência é um `bills` normal. Assim "conta recorrente" não é
-- uma entidade paralela que pode divergir de um boleto — é a próxima ocorrência
-- de um boleto, e pagar/cancelar segue exatamente o mesmo caminho de hoje.
ALTER TABLE bills
    ADD COLUMN category_id UUID REFERENCES categories (id) ON DELETE SET NULL,
    ADD COLUMN recurrence TEXT,
    ADD COLUMN recurrence_until DATE,
    -- Identidade da ocorrência gerada: "<bill-fonte>:<YYYY-MM>".
    -- O UNIQUE abaixo é o que torna `generate-recurring` idempotente: sem ele,
    -- dois requests simultâneos geram o boleto do mesmo mês duas vezes.
    ADD COLUMN recurrence_key TEXT;

ALTER TABLE bills ADD CONSTRAINT bills_recurrence_check
    CHECK (recurrence IS NULL OR recurrence IN ('WEEKLY', 'MONTHLY', 'YEARLY'));

CREATE UNIQUE INDEX bills_recurrence_key_uniq
    ON bills (wallet_id, recurrence_key)
    WHERE recurrence_key IS NOT NULL;

-- Recorrência sem data final é permitido (é o caso comum), mas com
-- `recurrence_until` no passado faria a série estar encerrada antes de gerar
-- nada. O CHECK deixa passar; a lógica de geração respeita a data.

-- ── LGPD ─────────────────────────────────────────────────────────────────────
--
-- Apagar conta NÃO pode ser DELETE em cascata: `ledger_entries` referencia
-- `ledger_accounts` com ON DELETE RESTRICT, e o ledger é registro histórico.
-- Quebrar o equilíbrio de uma transação de anos atrás para apagar um e-mail
-- seria trocar privacidade por integridade contábil, que é a pior das duas.
--
-- A saída é anonimização: some o PII, o histórico financeiro permanece
-- equilibrado e inacessível (sem login, é inócuo).

-- Coluna PRIMEIRO: o CHECK abaixo a referencia.
ALTER TABLE users
    ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deletion_reason TEXT,
    ADD COLUMN data_export_requested_at TIMESTAMPTZ;

ALTER TABLE users ADD CONSTRAINT users_deleted_status_check
    CHECK (deleted_at IS NULL OR status IN ('DELETING', 'DELETED'));

ALTER TABLE users ADD CONSTRAINT users_status_check
    CHECK (status IN ('ACTIVE', 'DELETING', 'DELETED'));

-- Refresh tokens de conta deletada não podem servir. Índice parcial para a
-- revogação em massa ser barata.
CREATE INDEX users_active_idx ON users (id) WHERE deleted_at IS NULL;