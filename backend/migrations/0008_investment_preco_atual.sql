-- Preço atual digitado pelo usuário.
--
-- POR QUE EXISTE E POR QUE NÃO É COTAÇÃO AUTOMÁTICA
--
-- Exibir "PETR4: R$ 38,20 (+2,1%)" na tela é, no Brasil, atividade de
-- assessor de investimento (CVM): preço de ativo exibido com intenção de
-- ajudar na decisão de comprar ou vender. O KaveliPay não faz isso e não
-- vai fazer — é uma fronteira de produto, não uma limitação técnica.
--
-- O que este campo FAZ é dar ao usuário o preço QUE ELE VIU NA CORRETORA.
-- A partir dele o app calcula valor atual e ganho não realizado sobre o custo
-- médio, que é aritmética sobre números que o próprio usuário digitou. Isso é
-- registro, do mesmo jeito que "comprei a R$ 35,00".
--
-- NULL significa "preço atual desconhecido" — e a tela diz isso, em vez de
-- mostrar zero, que seria um número errado com aparência de número certo.

ALTER TABLE investments
    ADD COLUMN current_price NUMERIC(19, 4);

COMMENT ON COLUMN investments.current_price IS
    'Preço unitário atual digitado pelo usuário. NÃO é cotação automática: exibir preço de ativo para ajudar a decidir é assessor (CVM). NULL = desconhecido.';

-- ── contas de ledger que faltam em carteiras antigas ────────────────────────

-- Carteiras criadas ENTRE a migration 0007 (que criou a conta só para as
-- wallets existentes naquele momento) e a inclusão de `InvestmentAssets` em
-- `ledger::garantir_contas` ficaram sem a conta, e toda compra dava 404
-- "conta de ledger não encontrado".
--
-- Este INSERT é idempotente (`NOT EXISTS`) e roda em toda aplicação de
-- migration. É a rede de segurança para wallets criadas fora do ciclo
-- cadastro — e o `garantir_contas` no login cobre o resto.
INSERT INTO ledger_accounts (wallet_id, kind)
SELECT w.id, 'INVESTMENT_ASSETS'
  FROM wallets w
 WHERE NOT EXISTS (
       SELECT 1 FROM ledger_accounts a
        WHERE a.wallet_id = w.id AND a.kind = 'INVESTMENT_ASSETS'
 );