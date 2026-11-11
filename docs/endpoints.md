# Endpoints

> Movido de AGENTS.md em 2026-10-12 (enxugamento para reduzir contexto repetido por turno). Conteúdo verbatim.

## Endpoints

**Freios comuns a todas as rotas**: 300 requisições por minuto por IP
(`LIMITE_POR_MINUTO`, 429 `too_many_requests`) e 30 s de tempo máximo por
requisição (408). O IP é lido de `X-Forwarded-For` (primeira entrada) — em produção o socket é o
proxy do Render. Rota nova herda os dois por serem camadas, não código de
handler.

- `POST /auth/register` — cria usuário **e wallet com as contas do ledger** na
  mesma transação
- `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`
- `GET /auth/me` — rota protegida de referência; o `user_id` vem do token
- `POST /auth/forgot-password` — `{email}`. A resposta é **sempre a mesma**,
  com conta ou sem (senão o endpoint entrega a lista de e-mails cadastrados), e
  o envio roda em background para o tempo de resposta não denunciar nada. Manda
  um código `KPL-…` por e-mail; um pedido novo invalida o anterior. 429 por IP
  (5/hora), 503 quando nenhum provedor de e-mail está configurado (`BREVO_API_KEY`
  ou `SMTP_HOST`).
- `POST /auth/reset-password` — `{token, new_password}`. Código de uso único,
  vale 30 min, e a troca derruba **todas** as sessões (`sessions_revoked`).
  Código inválido/expirado/reusado é 422 com texto próprio.
- `GET /wallet` — saldo derivado, moeda, status
- `POST /wallet/transactions` — `{type, amount, description?}`; `amount` é
  **string** (`"10.50"`). Float é 422.
- `GET /wallet/transactions?limit=&offset=` — extrato paginado (máx 200)
- `POST /bills` — cria como **sempre PENDING**; `amount` string, `due_date`
  `YYYY-MM-DD`, `linha_digitavel` opcional (texto livre — o app só lê/digita,
  nunca interpreta; ver lacuna 12B × seção 9 em `docs/spec.md`)
- `GET /bills?status=&overdue=&limit=&offset=` — `?status=OVERDUE` é 422 de propósito
- `POST /bills/{id}/pay` — marca pago o que o **usuário afirma** ter pago; gera
  transação `BILL_PAID`. Idempotente: segunda tentativa é 409 sem debitar de novo
- `DELETE /bills/{id}` — cancela; só PENDING (pago é 409)
- `POST /splits` — `{description, total_amount, parts:[{name, amount}]}`. A soma
  das partes **tem** de fechar com o total; a despesa inteira sai da carteira
- `GET /splits` — cada parte em aberto traz `link`: a URL `https` abaixo, para o
  dono mandar no WhatsApp (parte quitada vem sem link)
- `POST /splits/{id}/parts/{part_id}/settle` — quita a parte **por declaração do
  dono** (quem pagou por fora) e devolve o dinheiro via `SETTLEMENT`
- `GET /cobranca/parte/{token}/pagar` — **pública, sem sessão**: a cota de uma
  divisão em HTML. Em aberto mostra o botão do Mercado Pago; paga mostra
  "Pagamento confirmado." e não oferece pagamento de novo
- `POST /cobranca/parte/{token}/pagar` — **pública**: cria (ou reaproveita
  `mp_preference_id`) a preferência da parte e responde 303 para o checkout. A
  preferência leva `external_reference = parte:<id>`; o webhook do MP consulta o
  pagamento na API deles e só então quita a parte, gravando `mp_payment_id`. Quem
  confirma o pagamento é o MP, não o clique do dono. Também leva
  `marketplace_fee` de 1% (piso R$ 0,49, teto R$ 4,90)
- `POST /charges` — cria cobrança e devolve o QR. **NÃO gera lançamento**
- `GET /charges`, `GET /charges/{id}/qr` — o QR é
  `kavelipay://cobranca/<token>` (deep link do app, **nunca** BR Code/EMV); o
  payload não carrega valor
- `GET /cobranca/{token}` — **pública, sem sessão**: descrição, valor, status,
  vencimento e nome de quem cobra. É o que abre quando alguém recebe o
  link/QR — inclusive sem conta no app. Token malformado e inexistente dão o
  mesmo 404; a resposta não expõe wallet, id da cobrança nem nome do devedor
- `GET /cobranca/{token}/pagar` — **pública, sem sessão**: a página de pagamento
  em HTML (mobile-first, sem JS). Cobrança `PENDING` mostra a **chave Pix de
  quem cobra** (`users.pix_chave`, copia-e-cola em texto) e, se a conta do
  Mercado Pago estiver conectada, o botão do MP; recebida ou cancelada diz o que
  aconteceu e **não mostra mais a chave** (nada de pagar duas vezes). É o link
  que se manda para quem **não tem o app**
- `POST /cobranca/{token}/pagar` — **pública**: cria (ou reaproveita
  `mp_preference_id`) a preferência e responde 303 para o checkout do MP.
  Cobrança já resolvida volta para a própria página em vez de abrir pagamento
- `GET /charges/{id}/qr` também devolve `link` — a URL `https` acima, para o app
  copiar o link que funciona fora do app (`PUBLIC_BASE_URL`)
- `POST /charges/{id}/receive` — registra o recebimento; único toque na carteira.
  Pix direto pela chave **não** é confirmado pelo servidor: quem recebeu declara
- `POST /charges/{id}/pagamento` — **Mercado Pago**: gera a preferência do
  Checkout Pro e devolve `{preference_id, init_point}`. Só cobrança `PENDING` do
  dono (409 caso contrário). Sem `MP_ACCESS_TOKEN` é 503. A cobrança NÃO muda de
  status aqui. A preferência leva `marketplace_fee` = 1% do valor (piso R$ 0,49,
  teto R$ 4,90, calculado do banco): o Mercado Pago desconta do vendedor e
  deposita na conta do KaveliPay
- `POST /webhooks/mercadopago` — público. O corpo **não é fonte de verdade**:
  o status vem da consulta autenticada a `/v1/payments/{id}` e o valor vem do
  banco. Só `approved` credita; o crédito entra como `ChargeReceived` com
  `UPDATE charges ... WHERE status='PENDING'` na mesma transação, então
  reenvio é idempotente (segunda vez não cria transação) e sempre responde 200
  para o MP não reenfileirar
- `GET /mercadopago/link` — devolve `{url}` da autorização do Mercado Pago para
  este usuário. Existe porque o app abre a página num WebView e WebView não manda
  header de sessão; o token nunca sai para o Mercado Pago
- `GET /mercadopago/conectar` — mesmo link, como redirect 303 (navegador)
- `GET /mercadopago/callback` — público, o Mercado Pago volta aqui com `code` e
  `state`. Troca o código por `access_token`/`refresh_token` e guarda cifrado
  (`pgp_sym_encrypt`, chave em `MP_TOKEN_KEY`), depois redireciona para
  `kavelipay://mercadopago/conectado`. `state` é de uso único e vale 10 minutos;
  repetido devolve 422. Sem `MP_CLIENT_ID`/`MP_CLIENT_SECRET`/`MP_TOKEN_KEY` é 503
- `GET /mercadopago/status` — `{conectado, mp_user_id}`
- `DELETE /mercadopago/conta` — desconecta (204)

Com a conta conectada, cobrança e parte de divisão criam a preferência com o
token **do dono**: o dinheiro cai na conta dele, não na do app. Sem conta
conectada a página pública avisa e não oferece pagar.
- `DELETE /charges/{id}` — cancela; só PENDING
## Caixinha (empréstimo registrado entre conhecidos)

> **FORA do ledger** — decisão fechada (`docs/caixinha-api.md` é o contrato):
> a caixinha registra o que cada pessoa **afirma** (aportou, pagou rendimento,
> devolveu) e nada posta transação. O `GET /wallet` de todos os envolvidos fica
> exatamente onde estava — o e2e (`caixinhas_e2e.sh`) prova saldo `0.00` do
> começo ao fim. Regra central: **quem paga propõe, quem recebe confirma** — só
> movimento `CONFIRMADO` entra em qualquer número, e todo número é derivado dos
> movimentos (nada de saldo guardado). Identidade vem do token; quem não é dono
> nem membro recebe **404** `Caixinha não encontrada.`, nunca 403. Dinheiro é
> **string** (float é 422); toda resposta de detalhe deriva o mês do
> `?mes=YYYY-MM` (default: mês atual UTC; o app manda o mês LOCAL dele).
>
> **Sem vitrine pública.** Entrada só por convite (`KAV-XXXXXXXXXXXX`): captar de
> credores desconhecidos com rendimento fixo é oferta pública de CIC (valor
> mobiliário, Lei 6.385/76) — exigiria CVM. `/caixinhas/publicas` e
> `/caixinhas/{id}/publica` não existem (404); o banco recusa `publica = true`.

- `POST /caixinhas` — `{nome, valor_pedido, taxa_mensal, previsao_quitacao?,
  telefone?}`. **A primeira é grátis; a segunda em diante exige Premium**
  (`402 plano_necessario`). Teto de 20 por conta. Cria a caixinha **e o primeiro convite na mesma
  transação**; `201` com o `CaixinhaDetalhe` (papel `dono`).
- `GET /caixinhas` — as minhas (dono OU membro), `criado_em DESC`, com papel,
  arrecadado, progresso e score do responsável.
- `POST /caixinhas/entrar` — `{chave}`; normaliza `trim().to_uppercase()`.
  Transação única: trava o convite (`FOR UPDATE`), não o consome em erro,
  marca `usado_em`, insere o membro e **gera o próximo código** na mesma
  transação — sempre existe exatamente um convite ativo por caixinha. Chave
  desconhecida é 404 `Chave inválida.`; já usada, 409; dono da própria,
  409 `Você é o responsável desta caixinha.`
- `GET /caixinhas/{id}` — `CaixinhaDetalhe`: dono vê tudo (incluindo
  `convite` ativo e `total_*_mes`); membro vê só os próprios números,
  movimentos e `null` no resto. `movimentos` = todos os `PROPOSTO` visíveis ∪
  últimos 10 resolvidos visíveis, `criado_em DESC`.
- `POST /caixinhas/{id}/aportes` — membro propõe `{amount}`; validado contra o
  espaço que falta no pedido (propostas pendentes também reservam espaço).
- `POST /caixinhas/{id}/rendimentos` — **dono** propõe
  `{membro_id, competencia}`; **sem valor no body**: o servidor calcula o
  rendimento devido do mês (base × taxa) e propõe esse valor inteiro.
- `POST /caixinhas/{id}/devolucoes` — dono propõe `{membro_id, amount}`,
  validado contra o saldo em aberto menos devoluções propostas.
- `POST /caixinhas/{id}/movimentos/{mov_id}/confirmar|recusar` — **contraparte**
  (aporte → dono confirma; rendimento/devolução → membro confirma). Confirmar
  revalida o estado com as mensagens do contrato; permissão é checada antes do
  estado (quem nem podia resolver recebe `Só a outra parte...`).
- `POST /caixinhas/{id}/movimentos/{mov_id}/cancelar` — só quem propôs.
- Score do responsável — não tem rota própria: `responsavel_score` (0–1000,
  base 500) e `responsavel_evidencias` vêm no detalhe e na lista. Sobe com
  rendimento pago em dia e quitação confirmada; cai por mês vencido. A rota
  antiga `/avaliacao` (estrelas) não existe mais (404).
- `PATCH /caixinhas/{id}/meu-telefone` — cada um grava o próprio telefone
  (dono → `caixinhas.telefone_dono`; membro → a linha dele). Só dígitos,
  10 ou 11; a formatação é da tela.
- Erros: 404/409/422 com mensagens **literais em pt-BR** combinadas com o app
  (tabela completa em `docs/caixinha-api.md`). `NaoEncontrado`/`NaoValido` em
  `error.rs` são as variantes de mensagem literal (code `not_found` /
  `invalid_transaction`).
- Convite: `KAV-` + 12 hex maiúsculo, entropia do UUID v4, `ON CONFLICT DO
  NOTHING` + retry; usado é histórico que a API **nunca** devolve — a UI do
  dono mostra só o código atual.

## Assinatura premium (billing)

> A assinatura é **receita da empresa**, não dinheiro do usuário: vive em
> `assinaturas`/`codigos_ativacao`, nunca no ledger da carteira. Registrar a
> compra como transação do cliente misturaria as contas e sujaria o saldo
> demonstrativo. O direito é sempre derivado de
> `status = 'ACTIVE' AND periodo_fim > now()` — não existe coluna `premium` em
> `users`.

- `GET /billing/plano` — o que o usuário tem hoje e quanto custa. Exige login.
  Resposta `{premium, plano?, periodo_fim?, recursos_premium, preco_mensal,
  preco_anual}`; `plano`/`periodo_fim` são `null` sem assinatura vigente. Os
  preços são **string** (`"9.90"` mensal, `"79.90"` anual): dinheiro nunca é
  float, nem na resposta. `access_token` **não** vem neste GET — só em `ativar`.
- `POST /billing/checkout` — abre a compra do Premium. Corpo `{plano}` (`mensal`
  ou `anual`). Exige login. Resposta `{url}`: a página de pagamento
  (`/assinar/<token>`) que o app abre num WebView. O preço **não** vem do app —
  a URL guarda o valor lido de `preco_mensal`/`preco_anual` no banco. `503`
  quando o Mercado Pago não está configurado (`MP_PUBLIC_KEY` ausente).
  Com `MP_SANDBOX=true` a resposta é o link do **Checkout Pro** no sandbox, não
  a página do Brick: a API de pagamentos recusa credencial de teste
  (`401 Unauthorized use of live credentials`), mas o Checkout Pro aceita
  cartão de teste. O webhook `plano:<id>` libera o acesso do mesmo jeito.
- `GET /assinar/{token}` — página pública do Payment Brick (cartão e Pix), com
  o valor e o e-mail de quem vai pagar. Token de 32 hex; malformado e
  inexistente dão o mesmo `404`. Já paga, mostra "pagamento confirmado" e não
  oferece pagar de novo.
- `POST /assinar/{token}/pagar` — o Brick manda o `formData` para cá; o backend
  **sobrescreve** `transaction_amount` com o valor do banco, manda
  `external_reference = plano:<id>` e cria o pagamento no Mercado Pago.
  `approved` libera o período na hora; Pix (`pending`) só libera quando o
  webhook confirmar. `409` se o checkout já foi concluído.
- `POST /billing/ativar` — troca o código de ativação por período de acesso.
  Corpo `{codigo}` (8–40 caracteres após normalizar espaços e maiúsculas).
  Exige login. O código é **uso único**, gerado com validade de 30 dias a
  partir da emissão, e é travado com `FOR UPDATE` — dois cliques não ativam
  duas vezes. Renovar com assinatura ainda vigente **soma** ao período restante
  (quem renova no meio do mês não perde os dias pagos); sem assinatura vigente,
  a base é `now()`. Resposta é o mesmo `PlanoResponse` do GET, **mais
  `access_token`**: um token novo com o claim `premium = true`, porque o token
  antigo ficaria grátis por até 15 minutos e o app reabriria a tela de
  assinatura logo depois de pagar. Código inválido/expirado ou já usado é `422`
  com mensagem em pt-BR.
- `POST /admin/assinaturas/codigos` — emissão de código de uso único pelo dono.
  Header `X-Admin-Token` (o `ADMIN_TOKEN`; mínimo 24 caracteres).
  Corpo `{plano, dias?}`; `plano` é `mensal` (padrão 30 dias) ou `anual` (365);
  `dias` entre 1 e 400 serve para cortesia/renovação manual. Resposta
  `{codigo, plano, dias, expira_em}` — o código cru (`KPL-XXXX-XXXX-XXXX-XXXX`,
  alfabeto sem `0/O`/`1/I` porque é digitado à mão) aparece **uma vez**; depois
  só existe o hash SHA-256 no banco. Se perder o código, emita outro.
- Gating por claim: `GET /reports/monthly` e `GET /reports/monthly/relatorio`
  chamam `exigir_premium_do_token`, que lê o
  claim do access token **sem consultar o banco**. Sem direito, é `402 Payment
  Required` com `codigo = "plano_necessario"` — não `403`: não é falta de
  permissão nem de login, é falta de plano, e o app abre a tela de assinatura a
  partir desse código. O núcleo (saldo, extrato, contas, cobranças, caixinhas) e
  a exportação LGPD continuam **grátis**: portabilidade de dados não pode ficar
  atrás de paywall.
- `GET /reports/monthly/relatorio?month=YYYY-MM` — o mesmo relatório em **HTML
  autocontido**, com `Content-Disposition: attachment` (o app salva em
  Downloads e o Android imprime em PDF). Premium, mesmo gating. Os números vêm
  do ledger; a seção "Análise" é escrita pela IA a partir **só dos agregados**
  (nunca do extrato linha a linha) e é descartada se citar qualquer dígito —
  valor inventado num relatório financeiro é pior que relatório sem análise.
  Sem `OPENROUTER_API_KEY` o arquivo continua sendo gerado, com o aviso de
  análise indisponível. Teto de 5 gerações por usuário/hora.

### Como cobrar na prática

O backend **não processa pagamento**. Ele reconhece o direito: grava o período
em `assinaturas` e emite o claim `premium`. O dinheiro entra por um checkout
externo (PSP) e o backend só registra o que o PSP confirmou.

**MVP / sideload (o que existe hoje):**

1. O app mostra o plano e abre o link de checkout do dono (Mercado Pago, Asaas,
   PagBank etc.).
2. O pagamento é confirmado no PSP.
3. O dono emite um código em `POST /admin/assinaturas/codigos` e entrega por
   e-mail/WhatsApp.
4. O usuário cola o código em `POST /billing/ativar` e recebe um access token
   com `premium = true`.

Prós: zero integração de cartão, sem PCI, funciona no APK sideload hoje.
Contras: manual, não renova sozinho e não escala além de algumas dezenas de
assinantes.

**Produção / recorrente (recomendado):**

1. O app pede para criar a assinatura; o backend cria uma cobrança recorrente no
   PSP e devolve a URL do checkout.
2. O usuário paga no checkout hospedado do PSP (Pix ou cartão).
3. O PSP chama o webhook do backend com um evento assinado.
4. O backend valida a assinatura do webhook, aplica **idempotência** pelo id do
   evento e grava/renova `assinaturas` com o `provider` do PSP e o
   `provider_sub_id`.
5. O app, ao voltar do checkout, faz `POST /auth/refresh` (ou consulta
   `GET /billing/plano`) para pegar o token com `premium = true`.
6. Cancelamento, reembolso ou chargeback viram webhook e marcam `CANCELED`; o
   acesso cai no fim do período pago ou na hora, conforme a política escolhida.

Prós: renova sozinho, concilia receita e reduz suporte. Contras: integração,
taxa do PSP e webhook público que precisa ser idempotente e validado.

**Play Store:** se publicar na Play, assinatura digital deve usar **Play
Billing**. O backend valida o purchase token com a Google Play Developer API e
grava `provider = 'play'`; a tabela `assinaturas` não muda. O Google retém
~15%.

**Preço:** R$ 9,90/mês e R$ 79,90/ano (análise em `docs/custos.md`). O mensal é
a porta de entrada; o anual é retenção. Se o CAC real passar de ~R$ 88 com
churn de 10%, R$ 9,90 não fecha e o preço precisa subir para R$ 19,90–29,90.

**Meios:** Pix (taxa baixa e confirmação rápida), cartão (recorrência) e boleto
(evite: compensação lenta e inadimplência). Use checkout hospedado; **nunca**
armazene cartão no backend.

**Regras de ouro:** nunca ative por redirect do cliente — só por webhook
assinado ou confirmação server-side. Webhook idempotente por `event_id`. O
código de ativação atual continua válido para cortesia e venda manual; não o
remova ao integrar o PSP.
