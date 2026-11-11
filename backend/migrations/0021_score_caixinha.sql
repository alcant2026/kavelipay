-- Reputação do responsável pela caixinha: eventos append-only, nunca um
-- saldo guardado. O score é DERIVADO (base + eventos + atrasos), igual ao
-- saldo da carteira. As estrelas saem: nota sem lastro virava vaidade.
CREATE TABLE caixinha_score_eventos (
    id            UUID PRIMARY KEY,
    dono_user_id  UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    caixinha_id   UUID NOT NULL REFERENCES caixinhas (id) ON DELETE CASCADE,
    tipo          TEXT NOT NULL,
    pontos        INTEGER NOT NULL,
    -- Um evento por movimento: reprocessar a confirmação não pontua de novo.
    movimento_id  UUID UNIQUE REFERENCES caixinha_movimentos (id),
    criado_em     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT caixinha_score_tipo CHECK (tipo IN ('RENDIMENTO', 'DEVOLUCAO', 'AJUSTE'))
);

CREATE INDEX caixinha_score_eventos_dono_idx
    ON caixinha_score_eventos (dono_user_id);

DROP TABLE caixinha_avaliacoes;
