-- A vitrine pública da caixinha foi removida. Captar de credores desconhecidos
-- com rendimento mensal fixo é oferta pública de Contrato de Investimento
-- Coletivo (valor mobiliário, Lei 6.385/76) e exigiria registro/dispensa CVM.
-- O empréstimo continua registrável entre conhecidos, por convite (chave).
--
-- A coluna fica (migração é append-only), mas travada em FALSE: nem código
-- futuro consegue religar a vitrine sem uma migração nova e consciente.
UPDATE caixinhas SET publica = FALSE WHERE publica;

ALTER TABLE caixinhas
    ADD CONSTRAINT caixinha_sem_vitrine CHECK (publica = FALSE);
