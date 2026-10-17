# IA e sugestão de mercado

> Movido de AGENTS.md em 2026-10-12 (enxugamento para reduzir contexto repetido por turno). Conteúdo verbatim.

## IA: prefill do ativo (OpenRouter), com a chave no servidor

O usuário digita só o ticker; o app pergunta ao backend, o backend pergunta à
IA e devolve nome + resumo prontos. Economia de token é requisito, não
otimização — cada chamada sai do bolso do KaveliPay.

- **A chave nunca chega ao APK.** `OPENROUTER_API_KEY` é env var do servidor,
  usada só no header `Authorization`; nem log nem response body. Sem a env var,
  o endpoint é 503 `unavailable` — nunca 500 fingindo bug nem 401 fingindo
  auth. O app trata falha como silêncio: sem mensagem, sem trava no cadastro.
- Símbolo inválido (formato B3, validado ANTES da chamada) é 422 **sem** ir à
  IA: nem roundtrip, nem token. `BOND`/`OTHER` é texto livre ("Tesouro Selic
  2029") — a validação de formato só existe para STOCK/FII.
- Símbolo que não existe devolve `found:false` — resposta, não erro. O app não
  preenche nada e segue o cadastro na mão. Guard client-side (a tela só
  consulta quando o código muda) evita repetir a mesma consulta.
- `sanitizar_resumo` (`ai.rs`) espelha o trigger
  `assert_investment_summary_sem_valor` (migration 0007): se a IA puser valor
  monetário no resumo ("R$ 35,00", "35 reais"), o **resumo** é descartado e o
  **nome** fica. Sem isso o INSERT morreria no banco depois do prefill.
- **Nome vem do mercado, resumo vem da IA — ancorado no nome.** O lookup
  pergunta ao Yahoo (`/v1/finance/search`, grátis e sem token) o `longname`
  oficial da B3 — match EXATO com `.SA`, nunca o parecido — e esse nome
  entra como CONTEXTO no prompt da IA: o modelo que só vê o ticker adivinha
  a empresa errada (caso real 2026-10-13: mistral-nemo resumiu "Cyrela"
  para CPTS11, que é o FII Capitania Securities II). Com a âncora, ele
  resume a empresa certa. Yahoo fora ou renda fixa = a IA trabalha só com
  o ticker e o nome dela vira fallback; `found:false` descarta tudo,
  inclusive o nome oficial.
- O cliente http do backend manda **User-Agent de navegador**: o Yahoo
  responde 429 para cliente sem header (verificado ao vivo em
  2026-10-13 — sem header, chart e search levavam 429 e a sugestão saía
  nula). É o mesmo dado público de sempre; só a identificação muda.
- A IA preenche **só** o que estiver vazio — nunca sobrescreve o digitado.
  Nunca toca `tipo` nem próximo dividendo: modelo barato inventa data de
  dividendo com convicção.
- Modelo default `mistralai/mistral-nemo` (~US$ 0,000003/consulta, 200 tokens,
  temperature 0). `OPENROUTER_MODEL` troca; `OPENROUTER_BASE_URL` é o hook de
  teste — `tests/lookup.rs` sobe um mock server e grava o header Authorization
  pra provar que a chave vai no header e não no corpo.
- Testes: `tests/lookup.rs` (8, com mock — nenhum gasta token real) e seção 10
  do `investments_e2e.sh` (401/422/503 determinística, sem rede e sem chave).

## Sugestão de mercado: preço e provento (brapi + Yahoo + statusinvest)

Mesmo desenho da IA: o backend chama as fontes, a chave (se um dia houver)
fica no servidor, o app só recebe a sugestão pronta e a pessoa confirma.
As fontes atuais são **gratuitas e anônimas** — verificado ao vivo em
2026-10-12 (brapi/Yahoo) e 2026-10-17 (statusinvest), nenhuma tem API key no
fluxo.

- **Ação (STOCK): preço pelo brapi.dev.** `GET {BRAPI}/api/quote/{symbol}` —
  grátis sem token, devolve `regularMarketPrice` (número JSON) e
  `regularMarketTime`. O número vira `Decimal` **pelo texto** (`Number::to_string`
  → `from_str_exact`), nunca por aritmética de `f64`; `round_dp(2)` só limpa a
  sujeira binária que o Yahoo às vezes manda.
- **FII: preço pelo Yahoo Finance**, `GET {YAHOO}/v8/finance/chart/{symbol}.SA`
  (o sufixo `.SA` é obrigatório: PETR4 → PETR4.SA). Motivo: o **brapi responde
  401 em fundo imobiliário sem token** (verificado ao vivo) — ação sim, fundo
  não. Renda fixa (BOND/OTHER) **não tem fonte viva**: a API do Tesouro Direto
  morreu (DNS nem resolve) — fica manual, resposta vazia.
- **Provento (data de pagamento): ação pelo brapi, FII pelo statusinvest.**
  Ação: `GET {BRAPI}/api/quote/{symbol}?dividends=true` →
  `dividendsData.cashDividends[]`, e a regra é pegar o menor `paymentDate`
  **verificado** ainda por vir (`verified:true`, `>= hoje`, convertido pra
  -03:00 antes de cortar a data). FII: `GET
  {STATUSINVEST}/fii/companytickerprovents?ticker={symbol}&chartProventsType=2`
  (ação usa `/acao/...&chartProventsType=1`), campo `assetEarningsModels[].pd`
  em `dd/mm/aaaa` — também data de **pagamento**. O statusinvest é a
  **reserva** da ação: brapi sem provento futuro → pergunta lá antes de
  desistir (é o que salva WEGE3 e qualquer ticker fora da lista grátis do
  brapi). Yahoo só tem data-ex (quando o direito se extingue), não a data do
  pagamento — sugerir data-ex como "próximo dividendo" enganaria a pessoa,
  por isso o Yahoo fica fora desse campo.
- **Sem chave do brapi, só 4 tickers respondem** (PETR4, ITUB4, MGLU3, VALE3
  — o sandbox do provedor) e o endpoint de dividendos de FII é do plano Pro
  (R$ 139,99/mês). Por isso o statusinvest (grátis, keyless) é a fonte do
  fundo e a reserva da ação, em vez de um plano pago.
- **O statusinvest fala por um cliente HTTP próprio (`src/http_cru.rs`).** O
  site está atrás de um Cloudflare que devolve 403 ("Just a moment…") para
  requisição HTTP/1.1 com nome de header em **minúsculo** (verificado ao vivo
  em 2026-10-17: mesmo curl, `User-Agent:` → 200 e `user-agent:` → 403; HTTP/2
  também é recusado), e `reqwest`/`hyper` só sabem mandar minúsculo — o tipo
  `HeaderName` do crate `http` normaliza na construção. Então esse módulo
  escreve o pedido na mão (TCP + TLS rustls + headers capitalizados). É código
  de exceção, com escopo mínimo (GET, sem redirect, sem cookie, sem
  `Accept-Encoding`) e teto de tempo/corpo.
  **Risco declarado:** endpoint não oficial — se o site mudar o formato ou
  apertar o Cloudflare (inclusive por IP de datacenter), o parse devolve nada,
  o log registra o aviso e o app simplesmente não preenche a data. A sugestão
  nunca quebra o formulário.
- `POST /investments/quote` `{symbol, kind}` → `{price, source, as_of}`.
  `price` é `Money` (string 2 casas, `"56.00"`), `source` é `"brapi"`/`"yahoo"`,
  `as_of` é o momento do provedor. **Renda fixa, ticker não listado e provedor
  fora** devolvem tudo nulo — nunca 500, nunca 404. O contrato da tela:
  sugestão nunca derruba o formulário; falha = "sem sugestão".
- `POST /investments/lookup` ganhou `next_dividend` (data `AAAA-MM-DD`, se o
  ativo paga). Vem do brapi/statusinvest **em paralelo** com a IA
  (`tokio::join!`): o custo é o da IA (a lenta), não a soma. Provedor fora = só
  perde a data; `found:false` descarta a data junto com nome e resumo.
- Env vars: `BRAPI_BASE_URL`, `YAHOO_BASE_URL`, `STATUSINVEST_BASE_URL` (hooks
  de teste, default às URLs reais). `tests/cotacao.rs` sobe um mock único
  servindo IA+brapi+Yahoo+statusinvest por path — o `http_cru` fala HTTP/1.1
  simples, então o mesmo mock serve a fonte crua sem TLS; a seção 11 do
  `investments_e2e.sh` roda com as três fontes mortas (`127.0.0.1:1`) pra provar
  o caminho determinístico "provedor fora → 200 vazio".
- **Fronteira CVM:** a sugestão de preço é rótulo de terceiro com origem
  declarada ("brapi"), nunca recomendação — exibir preço com intenção de
  orientar decisão de compra/venda é assessoria de investimento. O texto da
  tela de `PrecoAtualScreen` diz isso sem maquiar, e o valor salvo é sempre o
  que o usuário confirmou (a sugestão preenche só campo vazio; digitou à mão,
  a sugestão não mexe).
