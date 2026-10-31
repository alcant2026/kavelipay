# Endpoints

> Movido de AGENTS.md em 2026-10-12 (enxugamento para reduzir contexto repetido por turno). Conteúdo verbatim.

## Endpoints

- `POST /auth/register` — cria usuário **e wallet com as contas do ledger** na
  mesma transação
- `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`
- `GET /auth/me` — rota protegida de referência; o `user_id` vem do token
- `POST /auth/forgot-password` — `{email}`. A resposta é **sempre a mesma**,
  com conta ou sem (senão o endpoint entrega a lista de e-mails cadastrados), e
  o envio roda em background para o tempo de resposta não denunciar nada. Manda
  um código `KPL-…` por e-mail; um pedido novo invalida o anterior. 429 por IP
  (5/hora), 503 quando o SMTP não está configurado.
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
- `GET /splits`
- `POST /splits/{id}/parts/{part_id}/settle` — quita a parte e devolve o dinheiro
  ao dono via transação `SETTLEMENT`
- `POST /charges` — cria cobrança e devolve o QR. **NÃO gera lançamento**
- `GET /charges`, `GET /charges/{id}/qr` — o QR é
  `kavelipay://cobranca/<token>` (deep link do app, **nunca** BR Code/EMV); o
  payload não carrega valor
- `GET /cobranca/{token}` — **pública, sem sessão**: descrição, valor, status,
  vencimento e nome de quem cobra. É o que abre quando alguém recebe o
  link/QR — inclusive sem conta no app. Token malformado e inexistente dão o
  mesmo 404; a resposta não expõe wallet, id da cobrança nem nome do devedor
- `POST /charges/{id}/receive` — registra o recebimento; único toque na carteira
- `DELETE /charges/{id}` — cancela; só PENDING
- `POST /investments/lookup` — `{symbol, kind}` → `{found, name?, summary?}`;
  prefill por IA do "Adicionar ativo" (ver a seção de IA abaixo)
- `POST /debts` — cria dívida `{creditor_name, amount, description}`. **Nenhuma
  transação de ledger** (como `/charges`): registrar que devo não mexe no saldo.
  `amount` string, float é 422. Vira sempre `PENDING`
- `GET /debts` — carteira ativa, mais recentes primeiro
- `POST /debts/{id}/pay` — declara o pagamento: debita o ledger (`Expense`) e
  marca `PAID` na mesma transação. Idempotente: segunda vez é 409 sem debitar
  de novo
- `POST /debts/{id}/confirm` — credor confirmou: muda **só status**
  (`PAID → CONFIRMED`), nunca dinheiro; confirmar duas vezes é 409
- `PATCH /debts/{id}` — edição parcial `{creditor_name?, amount?, description?}`;
  só `PENDING` (pago/confirmado é 409); 422 com as mesmas mensagens do POST
- `DELETE /debts/{id}` — só `PENDING` (estado final é 409); pendente nunca
  tocou o ledger, então não estorna nada. Id de outra carteira é 404, nunca 403

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

- `POST /caixinhas` — `{nome, valor_pedido, taxa_mensal, previsao_quitacao?,
  telefone?, publica?}`. Cria a caixinha **e o primeiro convite na mesma
  transação**; `201` com o `CaixinhaDetalhe` (papel `dono`).
- `GET /caixinhas` — as minhas (dono OU membro), `criado_em DESC`, com papel,
  arrecadado, progresso, média de avaliações.
- `GET /caixinhas/publicas` — vitrine: só `publica = true`, para todo usuário
  logado, com `dono_nome`, `falta` e `entrou` (sou dono/membro desta).
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
- `POST /caixinhas/{id}/avaliacao` — membro `{estrelas: 1..5}`, uma vez,
  imutável; exige aporte confirmado > 0.
- `PATCH /caixinhas/{id}/meu-telefone` — cada um grava o próprio telefone
  (dono → `caixinhas.telefone_dono`; membro → a linha dele). Só dígitos,
  10 ou 11; a formatação é da tela.
- Erros: 404/409/422 com mensagens **literais em pt-BR** combinadas com o app
  (tabela completa em `docs/caixinha-api.md`). `NaoEncontrado`/`NaoValido` em
  `error.rs` são as variantes de mensagem literal (code `not_found` /
  `invalid_transaction`).
- Convite: `KAV-` + 6 hex maiúsculo, entropia do UUID v4, `ON CONFLICT DO
  NOTHING` + retry; usado é histórico que a API **nunca** devolve — a UI do
  dono mostra só o código atual.

## Parceiros (vitrine de indicação)

- `GET /parceiros?limit=10&offset=0` — ofertas ativas, **da mais nova para a
  mais antiga** (`criado_em DESC`), em página de 10 (teto 50). Resposta
  `{itens, total, limit, offset}`: `total` é quantas ofertas ativas existem, o
  que deixa o app saber que acabou sem adivinhar pela página incompleta. Exige
  login (`AuthUser`), mas não filtra por usuário — a vitrine é a mesma para
  todos e a resposta não tem dado de ninguém.
- **Sem campo de preço**, e a ausência é regra: o Mercado Livre bloqueia leitura
  automática (403 na API anônima, bot-block na página), então preço só existiria
  digitado à mão — e preço digitado à mão envelhece calado. O app mostra "ver
  preço na loja" e o número verdadeiro fica no destino.
- `POST /admin/parceiros` — cadastro pelo dono (não é painel: uma rota, sem
  tela). Header `X-Admin-Token` com o valor de `ADMIN_TOKEN` (mínimo 24
  caracteres, conferido no boot). Corpo `{link, titulo?, imagem?, loja?}`:
  - `link` precisa ser `https://` — 422 se não for.
  - Sem `titulo`/`imagem`, o servidor busca `og:title`/`og:image` da página
    (timeout 6 s, corpo até 512 KB, User-Agent próprio). Se a loja bloquear a
    leitura, é 422 pedindo `titulo` — **nunca** inventa nome de produto.
  - A loja é deduzida do domínio (`mercadolivre.com.br` → Mercado Livre, senão
    o rótulo do domínio capitalizado).
  - Link que responde **404/410** é recusado com 422 ("esse link não existe
    mais na loja"). A página é buscada em **todo** cadastro, não só quando falta
    título ou imagem: é a única chance de pegar link morto antes de ele entrar
    na vitrine. `403`, `429` e `5xx` **não** recusam — são bot-block, limite de
    acesso ou instabilidade da loja, e punir isso travaria justamente as lojas
    que bloqueiam leitura automática.
  - `201` com a oferta criada. Link que já está ativo é `409`; link desativado é
    **reativado** e volta para o topo (`criado_em = now()`), não duplicado —
    há índice único em `destino_url`.
  - `401` com token ausente/errado; `503` quando `ADMIN_TOKEN` não está
    definida (o resto do app funciona normalmente).
- `DELETE /admin/parceiros/{id}` — desativa (`ativo = FALSE`), `204`. É o jeito
  de tirar da vitrine sem apagar o registro; id inexistente ou já desativado é
  `404`. Mesmo `X-Admin-Token`.
- `scripts/verificar_ofertas.sh [--desativar]` — varre a vitrine e testa o
  destino de cada oferta: `404`/`410` viram `MORTA`, sem resposta vira
  `SEM-RESPOSTA` (não conta como morta: pode ser rede). Sem a flag só relata;
  com `--desativar` chama o `DELETE` acima.
- A compra acontece na loja, numa WebView do app. Nada toca o ledger: não há
  pagamento, não há recebível, não há saldo. O KaveliPay recebe comissão de
  afiliado da loja, não do usuário — e isso está escrito na tela e no rodapé
  dos cards.
- Oferta que morre **depois** de cadastrada: a WebView escuta erro HTTP de
  navegação principal e, em `404`/`410`, troca a página por "Essa indicação
  saiu do ar" com "Tentar de novo" e "Abrir no navegador". *Soft 404* (loja
  responde `200` com "produto não encontrado" no HTML) não é detectável sem
  conhecer o HTML de cada loja — é limitação assumida.
- `imagem_url` pode ser nula (oferta sem `og:image`): o app mostra o ícone de
  loja no lugar. Quando existe, é `https://` por `CHECK` no banco — e
  `destino_url` também (defesa em profundidade: a WebView recusa
  `http`/`file`/`javascript` em `classificarDestinoWeb`, testado em
  `NavegacaoWebTest`).

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
  Header `X-Admin-Token` (mesmo `ADMIN_TOKEN` da vitrine; mínimo 24 caracteres).
  Corpo `{plano, dias?}`; `plano` é `mensal` (padrão 30 dias) ou `anual` (365);
  `dias` entre 1 e 400 serve para cortesia/renovação manual. Resposta
  `{codigo, plano, dias, expira_em}` — o código cru (`KPL-XXXX-XXXX-XXXX-XXXX`,
  alfabeto sem `0/O`/`1/I` porque é digitado à mão) aparece **uma vez**; depois
  só existe o hash SHA-256 no banco. Se perder o código, emita outro.
- Gating por claim: relatório mensal, `/investments/lookup` (IA) e
  `/investments/quote` (cotação) chamam `exigir_premium_do_token`, que lê o
  claim do access token **sem consultar o banco**. Sem direito, é `402 Payment
  Required` com `codigo = "plano_necessario"` — não `403`: não é falta de
  permissão nem de login, é falta de plano, e o app abre a tela de assinatura a
  partir desse código. O núcleo (saldo, extrato, contas, cobranças, dívidas) e
  a exportação LGPD continuam **grátis**: portabilidade de dados não pode ficar
  atrás de paywall.

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
