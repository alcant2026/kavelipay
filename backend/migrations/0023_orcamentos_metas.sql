-- Orçamento (teto de gasto por categoria no mês) e metas de economia.
--
-- NENHUMA das duas tabelas guarda dinheiro: elas guardam INTENÇÃO. O valor
-- gasto e o valor guardado continuam derivados do ledger (regra inegociável da
-- spec). Aqui só existe o teto e a meta — quem calcula o progresso é a
-- leitura, juntando o teto com o que o ledger já registrou.

-- Teto de gasto por categoria e mês. `mes = '9999-12'` = teto permanente
-- (vale todo mês). Não usamos NULL para "permanente" porque em Postgres
-- `NULL` nunca é igual a `NULL` num índice unique — um ON CONFLICT não
-- parearia dois tetos permanentes e duplicaria em vez de atualizar (foi
-- exatamente o bug que este sentinel evita).
CREATE TABLE orcamentos (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- NULL = "todas as categorias juntas" (teto do mês inteiro). Categoria
    -- apagada deixa o teto órfão de nome, então SET NULL cai no teto geral —
    -- o que é melhor que sumir com o planejamento do usuário.
    category_id  UUID REFERENCES categories (id) ON DELETE SET NULL,
    valor        NUMERIC(19,4) NOT NULL,
    -- Mês de vigência (YYYY-MM) ou '9999-12' para recorrente.
    mes          CHAR(7) NOT NULL DEFAULT '9999-12',
    criado_em    TIMESTAMPTZ NOT NULL DEFAULT now(),
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT orcamento_valor_positivo CHECK (valor > 0),
    CONSTRAINT orcamento_mes_formato CHECK (mes ~ '^\d{4}-\d{2}$'),

    -- Um teto por (categoria, mês). `mes = '9999-12'` é o teto permanente.
    CONSTRAINT orcamento_unico UNIQUE (user_id, category_id, mes)
);

-- Meta de economia: quanto a pessoa quer juntar e até quando.
CREATE TABLE metas (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    nome          TEXT NOT NULL,
    -- Valor alvo. `valor_inicial` é o que já estava guardado quando a meta
    -- nasceu (economia que já existia antes do app).
    valor_alvo    NUMERIC(19,4) NOT NULL,
    valor_inicial NUMERIC(19,4) NOT NULL DEFAULT 0,
    -- Prazo é opcional: "quero juntar R$ 3.000" é uma meta tão válida quanto
    -- "quero juntar R$ 3.000 até dezembro".
    prazo         DATE,
    criado_em     TIMESTAMPTZ NOT NULL DEFAULT now(),
    arquetivada   BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT meta_nome_not_blank CHECK (length(btrim(nome)) > 0),
    CONSTRAINT meta_valor_positivo CHECK (valor_alvo > 0),
    CONSTRAINT meta_inicial_nao_negativo CHECK (valor_inicial >= 0)
);

CREATE INDEX metas_user_idx ON metas (user_id) WHERE NOT arquetivada;

-- Vínculo opcional entre um lançamento do ledger e uma meta. É o que permite
-- dizer "deste mês, R$ 250 foram para a viagem": sem isto, a meta só teria o
-- saldo total e nunca saberia quanto foi guardado POR ela.
ALTER TABLE ledger_transactions
    ADD COLUMN meta_id UUID REFERENCES metas (id) ON DELETE SET NULL;

CREATE INDEX ledger_transacoes_meta_idx
    ON ledger_transactions (meta_id) WHERE meta_id IS NOT NULL;