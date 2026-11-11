-- Chave Pix de quem recebe. Fica no usuário, não na cobrança: quem cobra
-- costuma ter uma chave só, e repetir por cobrança é erro de digitação
-- esperando acontecer. Texto livre (CPF, e-mail, telefone, aleatória) — quem
-- valida o formato é a tela, não o banco.
ALTER TABLE users ADD COLUMN pix_chave TEXT;
