# Design Doc — Remodelação visual estilo Nubank (2026-10-14)

**Status:** aprovado pelo usuário via mockups interativos (claro/escuro), 2026-10-14.
**Mockups de referência:** `/tmp/brainstorm-341442-1791946657/content/telas-backend-real.html` (13 telas desenhadas; servido no visual companion). Os mockups são a referência visual; quando divergirem deste doc, prevalece este doc.

## 1. Objetivo

Remodelar **todas as telas** do app Android (`android/app/.../ui/screens/`, 20 telas + `Nav.kt`) no layout/estrutura do Nubank (referência Figma do usuário), **sem mudar as cores do KaveliPay** e sem violar nenhuma regra inegociável de `AGENTS.md`/`docs/spec.md`.

## 2. Design System

### 2.1 Cores (existentes em `ui/theme/Color.kt` — NÃO mudam)

| Papel | Valor |
|---|---|
| Acento (lime) | `#9FE870` |
| Verde escuro (marca/herói) | `#163300` |
| Fundo claro | `#F7F9F6` |
| Fundo escuro | `#1A1C18` |
| Card escuro | `#23261F` |

Zero roxo Nubank. Tokens semânticos por tema (light/dark) — nada de hex hardcoded por tela.

### 2.2 Correções de contraste (aprovadas no mockup)

- **Herói dark:** no tema escuro o card de saldo usa fundo `#123A1E` + borda `1.5dp` lime a ~50% — contraste mensurável contra `#1A1C18` (padrão: ≥3:1 em superfície/borda não-texto).
- **Badges neutras:** status padrão (Pendente, Pago, Aguardando, Recebido) em cinza-neutro sobre `seg`/`#32362C`. **Cor só no estado crítico:** `Vencido` = vermelho (`#FEE4E2`/`#B42318` light; `#4A1B18`/`#FDA29B` dark). Semáforo colorido é proibido.

### 2.3 Tipografia (fechar em `Type.kt`)

- Escala: **32** (saldo herói) / **24** (valores hero card) / **17** (títulos de tela) / **14** (corpo) / **12** (sub/metadado). Mínimo absoluto 12sp.
- Saldo e todos os valores monetários: **`tabularNums`** (números dançando = bug visual).
- Saldo herói: bold com tracking negativo leve (−2%), estilo Nubank.

### 2.4 Espaçamento

Ritmo **8dp**; gutter horizontal 16dp; cartes com raio 16dp; pills 999dp.

## 3. Padrões de tela (4)

1. **Lista + abas** — Home, Contas, Histórico, Cobranças, Perfil (topbar + conteúdo + `TabBar` de 5 abas).
2. **Formulário** — NovaTransacao, NovaConta/Bill, NovaCharge, NovaSplit, NovaDivida, NovaCategoria, NovoAtivo, MovimentoInvestimento (campo grande de valor primeiro, label 12, input, CTA pill, secundário ghost).
3. **Detalhe** — AtivoScreen, DerivationScreen, QR da cobrança (herói + lista de dados + ações).
4. **Acesso** — Login (logo centrado + campos + CTA).

## 4. Anatomia da Home (padrão Nubank)

1. Topbar: saudação + ícones (tema sol/lua, sino de notificações, olho ocultar saldo).
2. Saldo herói (`GET /wallet`) com selo **"Demonstrativo"** (`is_demonstrative`).
3. **Fila de ações em `LazyRow`** (scroll horizontal): Entrada, Despesa, Boleto, Cobrança, Divisão, **Investir** (ícone que abre investimentos). Último item pode ficar parcialmente cortado na borda — affordance de scroll.
4. Blocos verticais (Contas a pagar com `is_overdue`).
5. **Carrossel "Descubra mais" em `LazyRow`** (Saldo explicado, Divisão, Relatório).
6. `TabBar` 5 abas: Início, Contas, Histórico, Cobranças, Perfil.

## 5. Regra de SCROLL (pedido explícito do usuário — não esquecer)

- **Toda tela com conteúdo maior que a viewport rola verticalmente** (`Column + verticalScroll` ou `LazyColumn` para listas grandes/paginadas — extrato usa paginação `limit/offset`).
- **Filas horizontais obrigatórias:** ações da Home e carrossel "Descubra mais" são `LazyRow`; chips do Histórico também.
- **Nada escondido atrás da barra:** `contentPadding`/`imePadding` para o conteúdo não passar por baixo da `TabBar` nem do teclado (formulários com `Modifier.imePadding()`).
- Nenhum scroll aninhado quebrado: telas com herói + lista usam `LazyColumn` com `item {}` para o herói (um único scroller por tela).

## 6. Mapeamento tela → fonte de dados (shapes do backend)

| Tela | Endpoint(s) e campos-chave |
|---|---|
| HomeScreen | `GET /wallet` (balance, is_demonstrative), `/bills` (is_overdue), `/notifications` (não-lidas) |
| ContasScreen | `GET /bills` (PENDING/PAID/CANCELED, beneficiario, due_date) + resumo `/reports/monthly`; pagas/categorias via "ver tudo" (Relatório) |
| HistoricoScreen | `GET /history?kind=` (TRANSACTION/BILL/CHARGE/SPLIT; balance, is_saida) |
| ChargesScreen | `GET /charges` (PENDING/RECEIVED/CANCELED, debtor_name); QR `GET /charges/{id}/qr` (payload `kavelipay://…`, **não é PIX** — aviso na tela) |
| SplitsScreen | `/splits` (parts is_settled, pending_amount) + `/debts` (PENDING/PAID/CONFIRMED) "Eu devo" |
| InvestimentosScreen | `GET /investments` + `/summary` (cost_basis, realized, dividends; `unrealized_note` visível — ganho não realizado **não** é calculado) |
| AtivoScreen | `GET /investments/{id}` (quantity derivada dos movimentos; market_value/unrealized `None` = linha omitida, nunca R$ 0,00) |
| NovoAtivoScreen | `POST /investments` + prefill IA `POST /investments/lookup`; tipo STOCK/FII/BOND/FUND |
| MovimentoInvestimentoScreen | `POST /{id}/movements` (BUY/SELL/DIVIDEND; quantity, price_per_unit, amount, fees, traded_at — tudo string) |
| PrecoAtualScreen | sugestão `POST /investments/quote` (brapi/Yahoo) é **palpite**; valor gravado é o digitado (migração 0008). Vazio = "Preço desconhecido" |
| NovaTransacaoScreen | `POST /wallet/transactions` (ENTRY/EXPENSE; amount **string**, float = 422; dica "use vírgula") |
| NovaConta/Bill | `POST /bills` (nasce PENDING; due_date aceita dd/mm/aaaa, envia YYYY-MM-DD) |
| NovaCharge/NovaSplit/NovaDivida | contratos `/charges`, `/splits` (soma das parts = total), `/debts` |
| DerivationScreen | `GET /wallet/derivation` (lançamentos + running balance; "refaça a conta à mão") |
| CategoriasScreen/NovaCategoria | `/categories` (14 defaults, reorder, color) |
| PerfilScreen | `/profile` (campo ausente = não mexe), senha (revoga sessões), `/profile/sessions`, LGPD export JSON/CSV, apagar conta |
| LoginScreen | `POST /auth/register` (name/email/password; cria wallet+ledger numa transação) / `/auth/login` |
| Tema claro/escuro | ícone sol/lua na topbar (ou Perfil → Tema), persiste a escolha e respeita o tema do sistema |

## 7. Inegociáveis (recorte — texto completo em `docs/spec.md`)

Saldo sempre derivado do backend; dinheiro sem `float` em lugar nenhum; QR nunca é BR Code/PIX; nunca fingir pagamento; `user_id` do token; ícone = vetor real (nunca emoji/glyph).

## 8. Fora do escopo / lacunas — NÃO decidir por conta própria

Fuso/`due_date`, conflito 12B × seção 9 (barcode × recálculo), OCR. Lista completa em `docs/spec.md`.

## 9. Gate de qualidade

Nenhuma tela fecha sem `docs/ui-review.md`: checklist mecânico + screenshot no Waydroid comparado a esta referência. `cargo test`/`assembleRelease`/`testDebugUnitTest` continuam passando (36 JUnit).
