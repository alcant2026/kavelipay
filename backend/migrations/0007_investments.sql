-- Investimentos: registro pessoal de carteira de ativos.
--
-- CONTABILIDADE (duas pernas, porque comprar ativo NÃO é o mesmo que gastar):
--  1. Registrar compra: débito INVESTMENT_ASSETS, crédito INCOME
--  2. Registrar venda:  débito USER_AVAILABLE, crédito INVESTMENT_ASSETS
--
-- Registrar compra NÃO é despesa da carteira — o dinheiro não sumiu, mudou de
-- lugar (virou ativo). Sem essa separação, "comprei 10 ações" apareceria como
-- despesa de R$ 350 e o saldo cairia errado. É a mesma lógica de `charges`:
-- o que registra é um direito/obrigação, e o lançamento contábil vem no ato.
--
-- A carteira do KaveliPay NÃO custodia o ativo: isso é registro do que o
-- usuário afirma ter comprado e vendido. `investments` é bookkeeping, não
-- custódia — e por isso o app não se apresenta como corretora.

-- ──ativos─────────────────────────────────────────────────────────────────────

CREATE TABLE investments (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id   UUID           NOT NULL REFERENCES wallets (id) ON DELETE RESTRICT,
    -- Código do ativo como o usuário digita: 'PETR4', 'HGLG11', 'SELIC2029'.
    -- Texto livre e em caixa ALTA (normalizado na aplicação): o mesmo ativo
    -- escrito de três maneiras diferentes criaria três posições, e o
    -- consolidado do usuário estaria errado sem nenhum erro visível.
    symbol      TEXT           NOT NULL,
    name        TEXT           NOT NULL,
    -- Tipo do ativo, porque dividendendo e como operar muda por categoria:
    -- 'STOCK' (ação), 'FII' (fundo imobiliário), 'BOND' (tesouro/renda fixa),
    -- 'FUND' (fundo), 'OTHER'. Texto livre acima de um CHECK: novos tipos
    -- entram sem migration.
    kind        TEXT           NOT NULL DEFAULT 'STOCK',
    notes       TEXT,
    -- Resumo do ativo. Texto gerado/editedo pelo usuário ou por IA, e que é
    -- DESCRITIVO: nunca um número. Preço e quantidade vivem em
    -- `investment_transactions` e nos cálculos; se a IA escrevesse um valor
    -- aqui, haveria duas fontes de verdade para o mesmo dado e elas divergiriam.
    summary     TEXT,
    -- Data do próximo dividendo/rendimento, DIGITADA pelo usuário. Não há
    -- fonte gratuita confiável de calendário, e data inventada é pior que
    -- ausência de data: a pessoa usa isso para decidir.
    next_dividend_at DATE,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT investments_symbol_upper CHECK (symbol = upper(symbol)),
    CONSTRAINT investments_symbol_not_blank CHECK (length(btrim(symbol)) > 0),
    CONSTRAINT investments_name_not_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT investments_symbol_unique UNIQUE (wallet_id, symbol)
);

CREATE INDEX investments_wallet_idx ON investments (wallet_id);

-- ── conta de ledger para ativos ──────────────────────────────────────────────

-- A compra de ativo debita USER_AVAILABLE e credita INVESTMENT_ASSETS. Sem a
-- segunda conta, `POST /investments/{id}/movements` falha com "conta de ledger
-- não encontrado" em TODA carteira — inclusive nas criadas depois desta
-- migration, porque `garantir_contas` só roda no cadastro.
--
-- O `ON CONFLICT DO NOTHING` é a mesma garantia de idempotência de
-- `ledger::garantir_contas`: rodar a migration duas vezes não cria duplicata.
INSERT INTO ledger_accounts (wallet_id, kind)
SELECT w.id, 'INVESTMENT_ASSETS'
  FROM wallets w
 WHERE NOT EXISTS (
       SELECT 1 FROM ledger_accounts a
        WHERE a.wallet_id = w.id AND a.kind = 'INVESTMENT_ASSETS'
 );

-- ── movimentos ───────────────────────────────────────────────────────────────

CREATE TABLE investment_transactions (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    investment_id  UUID           NOT NULL REFERENCES investments (id) ON DELETE CASCADE,
    -- 'BUY' | 'SELL' | 'DIVIDEND'. Compra e venda mexem na posição; o
    -- dividendo é entrada de dinheiro sem mexer na quantidade.
    type           TEXT           NOT NULL,
    -- Quantidade. Fracionário de propósito: corretoras vendem 0,005 ação, e
    -- `NUMERIC` mantém a fração exata onde `f64` erraria a terceira casa.
    quantity       NUMERIC(19, 8) NOT NULL,
    -- Preço unitário pago ou recebido. NULL só para dividendo, que não tem
    -- preço unitário — o valor chega em `amount`.
    price_per_unit NUMERIC(19, 4),
    -- Valor total do movimento, sempre derivado em vez de confiado:
    -- dividendo não tem preço unitário, e em compra o app manda o total que
    -- viu na corretora (com corretagem embutida) em vez de o produto.
    amount         NUMERIC(19, 4) NOT NULL,
    -- Custo total acumulado após este movimento. Guardado além de ser
    -- calculável porque é o que permite validar a posição sem reexecutar
    -- toda a série a cada leitura, e para detectar divergência se um cálculo
    -- mudar no futuro.
    cost_basis_after NUMERIC(19, 4) NOT NULL DEFAULT 0,
    -- Custos que o app NÃO inventaria: corretagem e imposto sobre o
    -- rendimento. Quase nunca visível na corretora, e é o que mais destrói o
    -- cálculo de ganho se ignorado.
    fees           NUMERIC(19, 4) NOT NULL DEFAULT 0,
    traded_at      DATE           NOT NULL,
    note           TEXT,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT investment_txn_type_check CHECK (type IN ('BUY', 'SELL', 'DIVIDEND')),
    CONSTRAINT investment_txn_quantity_positive CHECK (quantity > 0),
    CONSTRAINT investment_txn_amount_positive CHECK (amount > 0),
    CONSTRAINT investment_txn_fees_non_negative CHECK (fees >= 0),
    -- Compra e venda exigem preço unitário; dividendo não tem.
    CONSTRAINT investment_txn_price_required
        CHECK (type = 'DIVIDEND' OR price_per_unit IS NOT NULL),
    CONSTRAINT investment_txn_price_positive
        CHECK (price_per_unit IS NULL OR price_per_unit > 0)
);

CREATE INDEX investment_txn_asset_idx ON investment_transactions (investment_id, traded_at DESC);

-- ── invariante: não vender mais do que se tem ─────────────────────────────────

-- O trigger é deferrable e só roda no COMMIT, para que uma venda seguida de
-- compra no mesmo statement passe: a ordem real é "vendi e comprei no mesmo
-- dia". O que NÃO pode passar é vender 5 tendo 2 e fechar o commit.
--
-- Sem este invariante, o app mostraria posição negativa e preço médio sem
-- sentido — e a diferença apareceria só no relatório, meses depois.
CREATE OR REPLACE FUNCTION assert_investment_no_venda_a_descoberto()
RETURNS TRIGGER AS $$
DECLARE
    posicao NUMERIC(19, 8);
BEGIN
    -- Posição depois de aplicar TODOS os movimentos deste ativo até o
    -- commit, na ordem: é a única forma de o efeito de "vendi e comprei no
    -- mesmo dia" aparecer corretamente.
    SELECT COALESCE(SUM(
        CASE type
            WHEN 'BUY'  THEN  quantity
            WHEN 'SELL' THEN -quantity
            ELSE 0
        END
    ), 0)
    INTO posicao
    FROM investment_transactions
    WHERE investment_id = NEW.investment_id;

    IF posicao < 0 THEN
        RAISE EXCEPTION
            'venda a descoberto: posição do ativo % ficaria em % (negativa)',
            NEW.investment_id, posicao
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER investment_no_venda_a_descoberto
AFTER INSERT OR UPDATE ON investment_transactions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION assert_investment_no_venda_a_descoberto();

-- ── invariante: resumo não é número ──────────────────────────────────────────

-- Se alguém colar um valor no campo de resumo, o app passa a ter duas fontes
-- de verdade para o mesmo dado. O trigger não sabe o que é um número em texto
-- livre, mas sabe o padrão de um valor monetário — e recusar é mais barato
-- que explicar depois por que a tela mostra duas coisas diferentes.
CREATE OR REPLACE FUNCTION assert_investment_summary_sem_valor()
RETURNS TRIGGER AS $$
DECLARE
    sem_numero TEXT;
BEGIN
    IF NEW.summary IS NOT NULL THEN
        -- Remove o que é data e o que é percentagem/ticker, que são legítimos
        -- num resumo ("PETR4 caiu 3,2% em 2026-10"), e olha o resto.
        sem_numero := regexp_replace(NEW.summary, '\d+([.,]\d+)?\s*%', '', 'g');
        sem_numero := regexp_replace(sem_numero, '\d{4}-\d{2}-\d{2}', '', 'g');
        sem_numero := regexp_replace(sem_numero, '[A-Z]{4}\d{1,2}', '', 'g');

        -- Sobrou algo no formato R$ 35,00 / 35,00 reais?
        IF sem_numero ~ 'R\$\s*[0-9]' OR sem_numero ~ '[0-9]+,[0-9]{2}\s*(reais|por)' THEN
            RAISE EXCEPTION 'resumo do ativo não deve conter valor: %', left(NEW.summary, 120)
            USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER investment_summary_sem_valor
BEFORE INSERT OR UPDATE ON investments
FOR EACH ROW EXECUTE FUNCTION assert_investment_summary_sem_valor();