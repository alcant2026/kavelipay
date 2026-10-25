-- Caixinha: empréstimo registrado entre conhecidos. FORA do ledger: nada
-- aqui posta transação — a caixinha registra afirmações (quem aportou,
-- quem pagou rendimento, quem devolveu). O saldo da carteira não muda.

CREATE TABLE caixinhas (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dono_user_id      UUID        NOT NULL REFERENCES users (id),
    nome              TEXT        NOT NULL,
    valor_pedido      NUMERIC(19,4) NOT NULL,
    taxa_mensal       NUMERIC(5,2)  NOT NULL,
    previsao_quitacao TEXT,
    telefone_dono     TEXT,       -- só dígitos, 10 ou 11
    publica           BOOLEAN     NOT NULL DEFAULT false,
    criado_em         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT caixinhas_nome_not_blank   CHECK (length(btrim(nome)) > 0),
    CONSTRAINT caixinhas_pedido_positivo  CHECK (valor_pedido > 0),
    CONSTRAINT caixinhas_taxa_positiva   CHECK (taxa_mensal > 0),
    CONSTRAINT caixinhas_telefone_fmt    CHECK (telefone_dono IS NULL OR char_length(telefone_dono) IN (10, 11))
);

-- Convites: 1 código ativo por caixinha; usado = history que a API nunca devolve.
CREATE TABLE caixinha_convites (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    caixinha_id UUID NOT NULL REFERENCES caixinhas (id) ON DELETE CASCADE,
    chave       TEXT NOT NULL,
    criado_em   TIMESTAMPTZ NOT NULL DEFAULT now(),
    usado_em    TIMESTAMPTZ,
    usado_por   UUID REFERENCES users (id)
);
CREATE UNIQUE INDEX caixinha_convite_ativo_uniq
    ON caixinha_convites (caixinha_id) WHERE usado_em IS NULL;
CREATE UNIQUE INDEX caixinha_convite_chave_ativa_uniq
    ON caixinha_convites (chave) WHERE usado_em IS NULL;
CREATE INDEX caixinha_convite_chave_idx ON caixinha_convites (chave);

CREATE TABLE caixinha_membros (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    caixinha_id UUID NOT NULL REFERENCES caixinhas (id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users (id),
    telefone    TEXT,               -- só dígitos, 10 ou 11
    entrou_em   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT caixinha_membro_unico UNIQUE (caixinha_id, user_id),
    CONSTRAINT caixinha_membro_telefone_fmt CHECK (telefone IS NULL OR char_length(telefone) IN (10, 11))
);

CREATE TABLE caixinha_movimentos (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    caixinha_id    UUID NOT NULL REFERENCES caixinhas (id) ON DELETE CASCADE,
    tipo           TEXT NOT NULL,      -- 'APORTE' | 'RENDIMENTO' | 'DEVOLUCAO'
    membro_user_id UUID NOT NULL REFERENCES users (id),
    valor          NUMERIC(19,4) NOT NULL,
    competencia    DATE,               -- dia 1 do mês; SÓ em RENDIMENTO
    status         TEXT NOT NULL DEFAULT 'PROPOSTO',
    proposto_por   UUID NOT NULL REFERENCES users (id),
    criado_em      TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmado_em  TIMESTAMPTZ,

    CONSTRAINT caixinha_mov_tipo_check  CHECK (tipo IN ('APORTE','RENDIMENTO','DEVOLUCAO')),
    CONSTRAINT caixinha_mov_status_check CHECK (status IN ('PROPOSTO','CONFIRMADO','RECUSADO','CANCELADO')),
    CONSTRAINT caixinha_mov_valor_positivo CHECK (valor > 0),
    CONSTRAINT caixinha_mov_competencia_check CHECK ((tipo = 'RENDIMENTO') = (competencia IS NOT NULL)),
    CONSTRAINT caixinha_mov_confirmado_check CHECK (confirmado_em IS NULL OR status = 'CONFIRMADO')
);
CREATE INDEX caixinha_mov_caixinha_idx ON caixinha_movimentos (caixinha_id, criado_em DESC);

-- Nota de 1 a 5 do credor sobre a caixinha; uma por membro, imutável.
CREATE TABLE caixinha_avaliacoes (
    caixinha_id   UUID NOT NULL REFERENCES caixinhas (id) ON DELETE CASCADE,
    membro_user_id UUID NOT NULL REFERENCES users (id),
    estrelas      SMALLINT NOT NULL,
    criado_em     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (caixinha_id, membro_user_id),
    CONSTRAINT caixinha_avaliacao_estrelas CHECK (estrelas BETWEEN 1 AND 5)
);
