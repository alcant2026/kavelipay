-- Vitrine de parceiros: cadastro por API administrativa e ordem por recência.
--
-- O que muda aqui e por quê:
--
-- 1. A ordem deixa de ser a coluna `ordem` (curada à mão em migration) e passa
--    a ser `criado_em DESC`. Motivo: agora o admin cadastra por API, e oferta
--    nova tem de aparecer primeiro sem ninguém renumerar nada. "Sempre os
--    novos primeiro" é regra de produto, não conveniência.
--    O seed é preservado: cada oferta antiga ganha um instante decrescente
--    (`now() - ordem minutos`), então a curadoria original continua na mesma
--    ordem que estava na tela.
--
-- 2. `imagem_url` passa a ser OPCIONAL. Nem toda loja entrega `og:image`, e
--    obrigar uma imagem inventaria URL quebrada ou obrigaria o admin a caçar
--    arquivo à mão. Sem imagem, o app desenha o ícone de loja — honesto.
--
-- 3. `destino_url` passa a ser ÚNICA. Link repetido é card repetido, e a
--    vitrine é propaganda: duplicata parece descuido e ocupa espaço de outra
--    oferta. O índice também serve de trava contra reenvio do mesmo link.
--
-- Nada disto toca o ledger: continua sendo conteúdo, sem preço e sem dinheiro.

-- ── 1. Ordem por recência ───────────────────────────────────────────────────
UPDATE parceiros_ofertas SET criado_em = now() - make_interval(mins => ordem);

ALTER TABLE parceiros_ofertas DROP COLUMN ordem;

DROP INDEX IF EXISTS parceiros_ofertas_ativas;

-- Parcial porque oferta desativada não é lida por ninguém. `id` no fim só
-- desempata: dois cadastros no mesmo milissegundo não podem trocar de lugar
-- entre uma página e outra (senão a paginação repete ou pula item).
CREATE INDEX parceiros_ofertas_recentes
    ON parceiros_ofertas (criado_em DESC, id DESC)
    WHERE ativo;

-- ── 2. Imagem opcional ──────────────────────────────────────────────────────
ALTER TABLE parceiros_ofertas ALTER COLUMN imagem_url DROP NOT NULL;

ALTER TABLE parceiros_ofertas DROP CONSTRAINT parceiros_ofertas_imagem_https;
ALTER TABLE parceiros_ofertas ADD CONSTRAINT parceiros_ofertas_imagem_https
    CHECK (imagem_url IS NULL OR imagem_url LIKE 'https://%');

-- ── 3. Link único ───────────────────────────────────────────────────────────
CREATE UNIQUE INDEX parceiros_ofertas_destino_unico
    ON parceiros_ofertas (destino_url);
