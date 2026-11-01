# Segurança

## Como reportar

Vulnerabilidade, vazamento de dado ou falha de autorização: **não abra issue pública**.

Envie e-mail para **josiassm701@gmail.com** com:

- o que você conseguiu fazer (ex.: "li o extrato de outra conta com o token X");
- passos para reproduzir;
- versão do app ou data/hora, se for produção.

Resposta em até 7 dias. Se o problema expõe dado de usuário, a correção vem antes de qualquer divulgação — peço que você não publique os detalhes até o deploy.

## O que está no escopo

- `https://kavelipay-backend.onrender.com` (backend em produção)
- o APK publicado nas releases
- qualquer forma de acessar carteira, extrato, transação, caixinha ou cobrança de outro usuário

## O que não é vulnerabilidade

- saldo ser demonstrativo: o app **não custodia dinheiro** e declara isso na tela;
- ler o código-fonte público ou os endpoints (não há segredo no protocolo);
- ausência de recurso, erro de português, sugestão de produto — use issue normal.

## Segredos

Se você encontrar credencial, chave ou token em qualquer lugar deste repositório ou das releases, trate como incidente e reporte pelo e-mail acima. Chaves de assinatura e senhas de banco **não** ficam no repositório.
