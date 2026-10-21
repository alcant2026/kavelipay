# Spec: regras, decisões e lacunas

> Movido de AGENTS.md em 2026-10-12 (enxugamento para reduzir contexto repetido por turno). Conteúdo verbatim.

## Regras inegociáveis (da spec)

Produto:
- Somente pessoa física. NÃO criar: painel de comerciantes, cadastro de empresas, gestão de clientes, catálogo, estoque, vendas, PDV, CRM, dashboard empresarial.
- Vitrine de parceiros é INDICAÇÃO, não loja: card com produto e link de afiliado, **sem preço no app**, sem carrinho, sem checkout, sem pedido, sem estoque. A compra acontece no site da loja, numa WebView. Nada disso toca o ledger — não há pagamento, recebível nem saldo. A comissão é paga pela loja ao KaveliPay, nunca pelo usuário, e a tela diz isso em texto.
- KaveliPay NÃO se apresenta como banco, instituição de pagamento, custodiante, emissor de moeda eletrônica, provedor de PIX próprio ou processador próprio. Não implementar mecanismos para contornar regulamentação financeira.

Ledger:
- Saldo é SEMPRE derivado do ledger: Wallet → Ledger Account → Ledger Transaction → Ledger Entries.
- Nunca alterar saldo diretamente no banco; toda movimentação gera transação no ledger.
- Toda transação deve ser equilibrada: `total_debits = total_credits` — senão a transação falha.
- Backend recalcula e valida; nunca confiar em valor enviado pelo app Android.

Dinheiro:
- NUNCA `float`/`double`/`f32`/`f64` para valores. PostgreSQL: `NUMERIC(19,4)`. Rust (se escolhido): representação decimal apropriada.
- Moeda única no MVP: `BRL` — mas modele `currency` como campo para suportar moedas futuras.

Boletos/pagamentos:
- MVP NÃO paga boletos reais. Nunca fingir pagamento, gerar confirmação falsa ou marcar `PAID` sem fonte confiável.
- Status de conta: `PENDING`, `PAID`, `OVERDUE`, `CANCELED`.
- OCR pode errar: dados extraídos de imagem/PDF sempre passam por revisão do usuário antes de salvar.
- Abstração `PaymentProvider`, implementação inicial `DemoPaymentProvider`; providers reais (`PixProvider`, `BoletoProvider`) só com fornecedor legítimo e regulado, sem alterar a regra de negócio.

Carteira:
- Uma carteira por usuário; um usuário nunca acessa a carteira de outro.
- Status: `ACTIVE`, `BLOCKED`, `CLOSED`.

## Fronteira: não somos provedor de PIX

Esta é a regra que mais fácil de violar sem querer, e ela está em código:
- `payment::DemoPaymentProvider` gera `kavelipay://cobranca/<token>`
  — um **deep link do app**, não BR Code/EMV. `PaymentPayload::is_br_code()`
  é testado: o payload do demo nunca começa com `0002` nem menciona
  `BR.GOV.BCB.PIX`. O token (32 hex, gerado pelo banco) é o segredo de
  compartilhamento; o id da cobrança **não** vai no QR.
- O payload não carrega valor. `GET /cobranca/{token}` (pública, sem sessão) é
  quem devolve descrição/valor/status para quem recebeu o link; o app do
  devedor nunca é fonte da verdade.
- `POST /charges/{id}/receive` **não confirma pagamento**. É o usuário
  declarando que recebeu, como `bills/{id}/pay`. Sem webhook, sem polling.
- `PaymentProvider::moves_real_money()` é `false` para o demo. Um provider
  real só entra com fornecedor legítimo e regulado, e muda só `payload` e
  `format` — a regra de negócio já mora no ledger.
- QR de cobrança já RECEIVED é 409: um QR vivo levaria alguém a pagar de novo.

## Decisões (fora da spec — vêm do usuário, não do `ideia.tt`)

- **Frontend: Android nativo em Kotlin + Jetpack Compose.**
- **Backend: Rust + axum + sqlx** (PostgreSQL). JWT próprio com access + refresh, senhas em argon2id.
- Ordem de construção: schema de migrations → ledger com testes de equilíbrio → auth → endpoint de saldo.

## Stack

- Frontend: Kotlin + Jetpack Compose. Dinheiro também é decimal exato no cliente — nada de `Double`/`Float` em valor monetário nem em saldo exibido; forme a partir de string/decimal, nunca convertendo de `Double`.
- Backend: PostgreSQL. A spec menciona Rust **apenas** para representação decimal — a spec não decide a linguagem; Rust + axum + sqlx foi escolha do usuário (ver "Decisões").
- Escopo do MVP (21 objetivos, seção 3 da spec): conta → login → carteira → saldo → entradas/despesas → extrato → contas/boletos → cobranças com QR Code → divisão de despesa → histórico → notificações → perfil/segurança.

## Monetização (freemium)

- **Núcleo grátis, sempre**: cadastro, login, carteira, saldo, entradas/despesas,
  extrato, contas/boletos, cobranças com QR, divisão de despesa, dívidas,
  notificações, perfil/segurança e **exportação LGPD**. Portabilidade de dados é
  direito do titular; não pode ficar atrás de paywall.
- **Premium pago**: relatório mensal, análise por IA (`/investments/lookup`) e
  cotação de ativos (brapi/Yahoo). O que é grátis continua grátis — o paywall
  nunca tira uma função que já existia.
- **Receita da empresa ≠ dinheiro do usuário.** A assinatura é cobrada do dono
  da conta pelo KaveliPay e vive em `assinaturas`/`codigos_ativacao`. Nunca
  entra no ledger da carteira: lançar a compra como transação do cliente
  misturaria as contas da empresa com as do usuário e sujaria o saldo
  demonstrativo. Do mesmo jeito, a comissão de afiliado da vitrine é paga pela
  loja, não pelo usuário.
- **Como se paga hoje**: código de ativação de uso único (`KPL-XXXX-...`,
  validade 30 dias, um código por compra/renovação), entregue ao usuário por
  fora do app (sideload / atendimento). É o caminho que não exige loja de
  aplicativos nem fornecedor de pagamento. **Play Billing fica para depois**:
  quando entrar, muda só a origem do código/recibo — a tabela `assinaturas` e o
  direito derivado continuam iguais.
- **Direito derivado, não booleano**: `premium` é
  `status = 'ACTIVE' AND periodo_fim > now()`, calculado de `assinaturas`. Não
  existe coluna `premium` em `users`: expirar é o tempo passar, não uma rotina
  que esquece de virar a flag. Renovação soma ao período restante.
- **Claim no access token, validade de 15 minutos**: o token carrega
  `premium: bool` no momento da emissão (login/refresh leem `assinaturas`).
  Rotas pagas leem o claim, sem `SELECT` por requisição. Consequência aceita:
  cancelar ou expirar o plano pode levar até 15 min para refletir no token em
  uso. `POST /billing/ativar` devolve um access token novo já premium — sem
  isso o app reabriria a tela de assinatura logo depois de pagar.
- **Sem plano = `402 Payment Required`** com `codigo = "plano_necessario"`
  (não `403`): não é falta de login nem de permissão, é falta de plano. O app
  abre a tela de assinatura a partir desse código.
- **O que o backend não faz**: não guarda cartão, não processa pagamento, não
  finge cobrança. O código de ativação é o comprovante de uma compra feita
  fora; o backend só troca código por período.

## Lacunas conhecidas

- Não há upload de comprovante: `comprovante_url` é uma referência externa.
- Não há OCR nem lembretes de vencimento (a spec deixa OCR como
  "posteriormente").
- Leitura de código EXISTE na Nova conta (escaneia com a câmera ou digita),
  mas o app só **lê**: o código vira texto em `linha_digitavel`, sem
  interpretar. Valor e vencimento continuam vindo dos campos do formulário e o
  backend valida como sempre — ver a lacuna 12B × seção 9, resolvida abaixo.
  É **um leitor só** para boleto (1D) e cobrança (QR): quem reconhece o tipo é
  o app, não o usuário antes de apontar a câmera. Código válido na tela errada
  não vira "inválido" — a tela diz onde ele vive (boleto → Contas → Nova conta;
  QR → Cobranças → Ler QR de cobrança).
- Fuso de `due_date` usa `chrono::Local`; a spec não define.
- Saldo negativo é permitido (a spec não define bloqueio de saque).
- `participant_user_id` existe na tabela mas nenhuma rota o preenche: a divisão
  é com quem o usuário quiser, não obrigatoriamente usuário do KaveliPay.
- Notificações não têm cron nem push: o app precisa chamar
  `/notifications/generate`. Uma notificação é dado persistido, então sobrevive
  ao pagamento do boleto — o aviso antigo continua na lista (com `read_at`).
- Não há upload de arquivo, recuperação de senha, 2FA ou verificação de e-mail.
- `ledger_accounts.kind` não tem CHECK no banco: kinds novos entram livremente.
- `AccountKind::TransferIn/TransferOut` existem mas não há endpoint de
  transferência entre carteiras.
- **Item 8 da lista de sugestões (PIN/biometria) NÃO foi implementado: é
  cliente** (app Android). Não depende do backend.
- **O QR de cobrança é renderizado no app** (ZXing) e aponta para
  `kavelipay://cobranca/<token>`, **não** BR Code/EMV. Quem recebe abre
  `GET /cobranca/{token}` — rota pública, sem sessão — pela tela do link ou
  lendo o QR em Cobranças → "Ler QR de cobrança". O QR carrega só o token,
  nunca o valor. **Não há domínio https nem página web pública**: o link
  `kavelipay://` abre só em quem tem o app e não vira link clicável no
  WhatsApp — o caminho completo hoje é o QR lido dentro do app. Página web
  pública exigiria domínio + hospedagem (decisão do usuário).
- `categories.display_order` só muda via `PUT /categories/reorder`; o `NULL`
  dos padrões cai no fim da ordenação, não no começo.

## Lacunas abertas da spec

Não preencha estas por conta própria — são decisões do usuário.
- ~~Linguagem do backend~~ **resolvida: Rust + axum + sqlx.**
- ~~Conflito 12B × seção 9~~ **resolvida: usuário pediu escanear/digitar.**
  O app lê o código de barras (câmera ou teclado) e salva a linha digitável
  como **texto livre, sem interpretar**; a seção 9 prevalece nos dados — valor
  e vencimento vêm dos campos do formulário e o backend continua validando.
- **OCR está fora do MVP** — a spec escreve "o sistema poderá posteriormente utilizar OCR". Não implementar agora.
- Fuso horário de `due_date` e critério de virada do dia não especificados.

## Pitfalls

- `ideia.tt` termina no meio de uma frase (linha 434, "...fornecedor legítimo e ade") — a spec está incompleta; não presuma o conteúdo que viria depois.
- Idioma do repositório: pt-BR (spec, nomes de produto e comunicação).
- A spec não define convenções de nomeação, estrutura de pastas nem estratégia de migração. Proponha e registre aqui — não trate silêncio como padrão existente.