-- Notificações, sessões e perfil.
--
-- Notificações:提醒 de vencimento são DERIVADOS de `bills`, nunca gravados por
-- alguém. O índice único (wallet, tipo, referência) é o que impede o mesmo
-- boleto gerar o mesmo aviso todo dia — sem ele, abrir o app 3 vezes cria 3
-- notificações idênticas.

CREATE TABLE notifications (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id       UUID        NOT NULL REFERENCES wallets (id) ON DELETE CASCADE,
    type            TEXT        NOT NULL,
    title           TEXT        NOT NULL,
    body            TEXT,
    -- O que gerou o aviso (ex.: 'bill', 'charge', 'security').
    reference_type  TEXT,
    reference_id    UUID,
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT notifications_type_check
        CHECK (type IN ('BILL_DUE', 'BILL_OVERDUE', 'CHARGE_RECEIVED', 'SECURITY')),
    CONSTRAINT notifications_title_not_blank CHECK (length(btrim(title)) > 0),
    -- Aviso tem de dizer de que entidade veio; sem referência não dá para
    -- deduplicar nem para o app navegar até a origem.
    CONSTRAINT notifications_has_reference
        CHECK (reference_type IS NOT NULL AND reference_id IS NOT NULL)
);

CREATE INDEX notifications_wallet_created_idx
    ON notifications (wallet_id, created_at DESC);
CREATE INDEX notifications_wallet_unread_idx
    ON notifications (wallet_id) WHERE read_at IS NULL;

-- Um aviso por entidade por tipo. `ON CONFLICT DO NOTHING` na geração depende
-- deste índice: é ele que torna a geração idempotente.
CREATE UNIQUE INDEX notifications_dedup_uniq
    ON notifications (wallet_id, type, reference_type, reference_id);

-- ── Sessões ──────────────────────────────────────────────────────────────────
-- `refresh_tokens` já guarda os tokens; a sessão é a visão do usuário sobre
-- "dispositivos conectados". Este índice permite revogar todas as menos uma.

CREATE INDEX refresh_tokens_active_idx
    ON refresh_tokens (user_id) WHERE revoked_at IS NULL;

-- ── Perfil ───────────────────────────────────────────────────────────────────
-- Colunas de perfil moram em `users` (display_name já existe em 0001). Nada
-- novo aqui; a tabela existe só para deixar o agrupamento explícito.