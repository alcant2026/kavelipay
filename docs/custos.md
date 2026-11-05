# Custos, receita e ponto de equilíbrio

> Modelo de custo do KaveliPay para o dono decidir preço e salário com número, não com achismo. **Valores de 2026, em faixa** — troque pelos números reais assim que tiver contrato, CAC e churn medidos. Não é consultoria tributária: alíquota, anexo e encargos mudam; confirme com contador.

## Regra de ouro

O app não é banco e não custodia dinheiro. A assinatura é **receita da empresa**, não saldo do usuário. O custo abaixo é o da operação, não o do ledger.

```
Lucro = N × (preço − imposto − taxa do Mercado Pago) − custo fixo − marketing de aquisição
```

- `N` = assinantes ativos.
- `custo fixo` = infra + pró-labore + contador + IA de desenvolvimento + marketing fixo.
- `marketing de aquisição` = `N × churn × CAC` (reposição de quem cancela).

## 0. De onde vem (e de onde não vem) a receita

Esta é a conta que mudou com o Mercado Pago — e a que mais engana.

| Fluxo | Para onde vai o dinheiro | Taxa do MP paga por | Receita do KaveliPay |
|---|---|---|---|
| **Premium** (R$ 9,90/mês ou R$ 79,90/ano) | conta do KaveliPay | KaveliPay | **100% da receita própria** |
| **Cobrança** (`/cobranca/{token}`) | conta do usuário que cobrou | usuário | 1% (piso R$ 0,49, teto R$ 4,90) |
| **Divisão** (parte da cota) | conta do usuário que dividiu | usuário | 1% (piso R$ 0,49, teto R$ 4,90) |

Cobrança e divisão são P2P: a preferência é criada com o token OAuth **do dono**, então o dinheiro cai na conta dele e a taxa do MP sai do bolso dele. O KaveliPay não custodia nem intermedeia: só desconta a comissão de 1% via `marketplace_fee` (ver "Se quiser cobrar pelo P2P").

Consequência prática: a assinatura é a receita previsível (recorrente, sem depender de volume) e a comissão é a receita que cresce com o uso — mas só com volume alto: 1.000 cobranças de R$ 100 dão R$ 1.000/mês; 1.000 de R$ 20 dão R$ 490. Sem usuário ativo, comissão é zero, então divulgar cobrança e divisão continua valendo como aquisição (quem usa para cobrar abre o app toda semana).

### Se quiser cobrar pelo P2P

O Mercado Pago permite uma comissão do marketplace sobre a venda do usuário:

- **Checkout Pro**: campo `marketplace_fee` na preferência (valor fixo em R$).
- **API de pagamentos**: `application_fee`.

Modelo de referência: **1% por cobrança recebida**, com piso de R$ 0,49 e teto de R$ 4,90.

| Ticket da cobrança | Comissão (1%) | 1.000 cobranças/mês |
|---:|---:|---:|
| R$ 20 | R$ 0,49 (piso) | R$ 490 |
| R$ 100 | R$ 1,00 | R$ 1.000 |
| R$ 500 | R$ 4,90 (teto) | R$ 4.900 |

Esse 1% só vale a pena depois de: (a) o usuário já usar o app de graça e ver o valor; (b) a taxa ser anunciada antes do pagamento; (c) o MP aprovar a aplicação como marketplace. Antes disso é atrito para quem ainda nem confia no produto.

**Implementado** (`MercadoPago::taxa_da_transacao`, `marketplace_fee` em toda preferência de cobrança e de parte de divisão): 1% com piso de R$ 0,49 e teto de R$ 4,90, calculado no servidor a partir do valor do banco — nunca do app. Cobrança de valor igual ou menor que a comissão sai sem taxa. O valor é descontado do vendedor e o Mercado Pago deposita na conta da aplicação; não existe etapa manual.

Ressalvas: (a) sem a conta do Mercado Pago conectada não há vendedor de quem descontar, então o link nem aceita pagamento; (b) o premium passa sem comissão — seria cobrar de nós mesmos; (c) a taxa é cobrada no pagamento, não por assinatura, então o Pix pequeno é o que paga a operação.

## 1. Infra: Render + Neon (estado real, hoje)

Preços públicos em USD; câmbio assumido **US$ 1 = R$ 5,40** e IOF de ~3,5% no cartão.

### O que está rodando agora (custo R$ 0)

| Peça | Plano | Limite | O que dói |
|---|---|---|---|
| Render web service (Docker, Ohio, 1 instância) | **Free** | 512 MB, 0,5 CPU, 750 h/mês | **dorme após ~15 min sem acesso**; o primeiro request depois disso leva de 13 a 50 s |
| Neon Postgres 18 (us-east-2, 0,25 CU) | **Free** | 0,5 GB, ~192 CU-h/mês, 6 h de histórico | suspende por inatividade; sem point-in-time restore de verdade |

Consumo medido do Neon nos primeiros 14 dias (22/10 a 05/11, projeto `kavelipay`): **4.831 s de compute** (~1,3 h de relógio) e **5 MB** trafegados. Ou seja: em tráfego de desenvolvimento, o Neon free sobra — o que aperta primeiro é o **storage de 0,5 GB**, não o compute.

### Próximo degrau pago

| Peça | Plano | Preço | Quando subir |
|---|---|---:|---|
| Render | Starter | US$ 7/mês por serviço | **no primeiro assinante**: ninguém paga R$ 9,90 para esperar 50 s de cold start |
| Render | Standard (2 GB / 1 CPU) | US$ 25/mês | quando a API passar de ~50 req/s ou o build do Rust competir com o runtime |
| Neon | Launch | **US$ 19/mês** (mínimo, inclui ~300 CU-h e 10 GB) | quando passar de 0,5 GB ou precisar de restore além de 6 h |
| Neon | Scale | US$ 69/mês | leitura pesada, réplica de leitura, muitos branches |

**Degrau 1 (Render Starter + Neon Launch):** US$ 26/mês = **R$ 140,40 + IOF ≈ R$ 145/mês**.

**Degrau 2 (Render Standard + Neon Launch):** US$ 44/mês = **R$ 237,60 + IOF ≈ R$ 246/mês**.

Fora da infra, some: domínio (~R$ 40/ano), e-mail transacional (~R$ 0 no free do provedor), monitoramento (R$ 0 até doer).

> **Número que importa:** a infra do degrau 1 custa **~17 assinantes** no plano mensal. Abaixo disso, pagar hospedagem é prejuízo — o free existe justamente para atravessar essa fase.

## 2. Mercado Pago: quanto custa receber

Tabela pública do MP para Brasil (checkout online). **Confirme no painel antes de fechar preço** — taxa muda por conta, volume e negociação.

| Meio de pagamento | Taxa MP | Prazo |
|---|---:|---|
| Pix | **0,99%** | na hora |
| Cartão de crédito à vista (recebimento imediato) | **~4,98% + R$ 0,39** | na hora |
| Cartão de crédito à vista (30 dias) | **~3,03% + R$ 0,39** | 30 dias |
| Cartão parcelado (12x) | **~19,31% + R$ 0,39** | conforme parcelas |
| Boleto | **R$ 3,49** por boleto pago | 1–3 dias úteis |

### Impacto no preço do premium

Premissas: Simples Nacional no piso (6%) e taxa do cartão à vista imediato.

| Plano | Meio | Preço | Taxa MP | Simples 6% | **Líquido** |
|---|---|---:|---:|---:|---:|
| Mensal | Pix | R$ 9,90 | R$ 0,10 | R$ 0,59 | **R$ 9,21** |
| Mensal | Cartão à vista | R$ 9,90 | R$ 0,88 | R$ 0,59 | **R$ 8,43** |
| Mensal | Cartão 12x | R$ 9,90 | R$ 2,30 | R$ 0,59 | **R$ 7,01** |
| Mensal | Boleto | R$ 9,90 | R$ 3,49 | R$ 0,59 | **R$ 5,82** |
| Anual | Pix | R$ 79,90 | R$ 0,79 | R$ 4,79 | **R$ 74,32** (R$ 6,19/mês) |
| Anual | Cartão à vista | R$ 79,90 | R$ 4,37 | R$ 4,79 | **R$ 70,74** (R$ 5,90/mês) |

Três leituras que mudam decisão:

1. **Boleto em plano de R$ 9,90 é suicídio** — come 59% do valor. Se aceitar boleto, aceite só no anual.
2. **Pix é o melhor meio de longe** (R$ 9,21 contra R$ 8,43). Cada assinante que migra de cartão para Pix vale R$ 0,78/mês — em 1.000 assinantes, R$ 780/mês.
3. **O anual rende ~30% menos por mês** (R$ 5,90–6,19 contra R$ 8,43–9,21), mas trava 12 meses sem churn. Só empurre o anual se a retenção mensal for pior que isso — e ela é: churn de 10% ao mês destrói 72% da base em 12 meses.

**Premissa usada nas contas abaixo:** mix 50% Pix / 50% cartão → **R$ 8,82 líquido por assinante/mês**.

## 3. Pró-labore do sócio (CEO + dono + dev)

Você é **uma pessoa** fazendo três papéis. Não triplique salário: é **um pró-labore**. O ideal é o menor valor que cobre custo de vida, mantém INSS em nível aceitável e não quebra o caixa.

### Faixas práticas

| Fase | Pró-labore bruto | Racional |
|---|---:|---|
| Pré-receita / validação | R$ 1.500–3.000 | mínimo para INSS, sem pesar no caixa |
| Primeiros 100 assinantes | R$ 3.000–5.000 | ainda não justifica salário de mercado |
| 300–500 assinantes | R$ 6.000–8.000 | cobre custo de vida com folga |
| 1.000+ assinantes | R$ 10.000–15.000 | salário de fundador, não de CEO corporativo |
| Acima | pró-labore + distribuição de lucros | dividendos mais eficientes que salário alto |

**Regra de bolso:** nos primeiros 12 meses, não passe de **30% do faturamento mensal** com pró-labore. Faturou R$ 10 mil, tire até R$ 3 mil; faturou R$ 30 mil, até R$ 9 mil. O resto é reserva e reinvestimento.

### Tributação do pró-labore (aproximada)

- **INSS:** 11% descontados do bruto, com teto de contribuição (~R$ 8.500 de base em 2026; confirmar).
- **IRPF:** progressivo, retido na fonte. **Não é custo extra da empresa** — sai do bruto.
- **Simples:** incide sobre o **faturamento**, não sobre o salário. Piso ~6%; Anexo V pode começar em 15,5%.
- **Contador:** R$ 300–600/mês, fora do pró-labore.

| Pró-labore bruto | INSS aprox. | IRPF aprox. | Líquido aprox. |
|---:|---:|---:|---:|
| R$ 2.000 | R$ 220 | R$ 0 | R$ 1.780 |
| R$ 3.000 | R$ 330 | R$ 31 | R$ 2.639 |
| R$ 5.000 | R$ 550 | R$ 365 | R$ 4.085 |
| R$ 8.000 | R$ 880 | R$ 1.062 | R$ 6.058 |
| R$ 10.000 | R$ 935 | R$ 1.597 | R$ 7.468 |
| R$ 15.000 | R$ 935 | R$ 2.972 | R$ 11.093 |
| R$ 20.000 | R$ 935 | R$ 4.347 | R$ 14.718 |

**Para receber líquido R$ 8.000**, o bruto fica em torno de **R$ 10.500–11.000**. Para líquido R$ 5.000, bruto ~R$ 6.500–7.000. Confirme com contador.

## 4. IA do app (custo variável)

- Modelo padrão `mistralai/mistral-nemo`: ~**US$ 0,000003 por consulta** (200 tokens).
- Em reais: ~**R$ 0,000015 por consulta**.
- 1.000 consultas/mês ≈ **R$ 0,015**; 10.000 ≈ **R$ 0,15**.
- Cotação brapi/Yahoo/statusinvest: **R$ 0** no fluxo atual.
- Plano pago do brapi (R$ 139,99/mês) foi evitado com statusinvest.

**Conclusão:** IA do app é centavo. Não é o problema do negócio.

## 5. IA de desenvolvimento (custo fixo)

Diferente da IA do app: são ferramentas que o dev usa para programar. Custo fixo mensal, não cresce com usuário.

| Uso | Ferramentas típicas | Custo/mês |
|---|---|---:|
| Leve | 1 assinatura (Copilot/Cursor/Claude) | R$ 100–200 |
| Médio | 2 assinaturas + API | R$ 300–600 |
| Pesado | Claude Max/ChatGPT Pro + API | R$ 1.000–1.500 |

**Impacto no equilíbrio:** cada R$ 100/mês de IA de dev custa ~11 assinantes a R$ 9,90. R$ 1.000/mês custa ~113.

## 6. Marketing

Marketing é o custo que mais pesa. Não é só despesa fixa: ele compra assinante, e cada assinante tem prazo de payback.

### CAC x churn

- Margem por assinante ativo: **≈ R$ 8,82/mês** (R$ 9,90 − 6% − taxa do MP no mix Pix/cartão).
- **CAC** = custo para adquirir um assinante.
- **Churn** = % que cancela por mês.
- Custo de marketing por assinante ativo = **churn × CAC**.

Exemplo: churn 10% e CAC R$ 50 → **R$ 5,00/mês** para manter cada ativo. Sobra R$ 3,82. Com CAC R$ 88, o custo vira R$ 8,80 e a conta zera.

### CAC máximo a R$ 9,90

| Churn | LTV aprox. | CAC ideal (LTV/3) | CAC que paga em 12 meses |
|---:|---:|---:|---:|
| 5% | R$ 176 | ~R$ 59 | ~R$ 106 |
| 8% | R$ 110 | ~R$ 37 | ~R$ 106 |
| 10% | R$ 88 | ~R$ 29 | ~R$ 106 |
| 15% | R$ 59 | ~R$ 20 | ~R$ 106 |

**Leitura:** a R$ 9,90, anúncio pago só fecha com CAC abaixo de ~R$ 30–50. Acima de ~R$ 88 com churn 10%, não existe equilíbrio: cada assinante novo nasce devendo.

### Canais (faixas de mercado)

| Canal | Investimento/mês | CAC típico | Observação |
|---|---:|---:|---|
| Orgânico (conteúdo, SEO, comunidades) | R$ 0–500 + tempo | baixo, lento | melhor margem, escala devagar |
| Meta Ads | R$ 1.000–5.000 | R$ 40–150/cadastro; R$ 100–300/assinante | volume rápido, CAC sobe com escala |
| Google Ads | R$ 2.000–10.000 | mais alto | intenção de compra, pouca busca |
| TikTok/Reels | R$ 500–3.000 | variável | awareness barato, conversão baixa |
| Influenciadores | R$ 500–5.000/ação | R$ 30–200 | depende de nicho e audiência |
| Indicação/afiliados | comissão 20–40% | R$ 5–30 | menor CAC, exige base ativa |

Para app financeiro, o gargalo é **confiança**, não alcance. Anúncio frio converte pior que conteúdo, parceria e indicação.

## 7. Impostos e outras taxas

- **Simples/DAS:** 6% (piso) a 15,5% do faturamento, conforme anexo. Use o piso para cenário otimista e o anexo real para o conservador.
- **IOF:** ~3,5% em compras internacionais (Render/Neon/IA).
- **Play Store:** US$ 25 única para publicar; se usar Play Billing, ~15% sobre assinatura. Hoje a compra acontece no Mercado Pago dentro do app, então **não há taxa de loja** — mas publicar na Play exige cumprir a política de pagamentos, que muda a cada versão da política.
- **Contador:** R$ 300–600/mês.
- **Reserva:** 10–20% do faturamento para contingência.
- **Contestação (chargeback):** no premium, quem paga a taxa e o valor é o KaveliPay. Um chargeback de R$ 9,90 custa a taxa (~R$ 15–35) mais o valor — ou seja, **~5 assinantes** para cobrir um. Guarde prova de entrega (o próprio registro de acesso premium com data).

## 8. Ponto de equilíbrio

Fórmula: `N = custo fixo ÷ margem por assinante`. Margem = **R$ 8,82** (mix Pix/cartão).

### Sem marketing de aquisição

| Cenário | Infra | Contador | IA dev | Pró-labore | Custo fixo | Assinantes p/ empatar |
|---|---:|---:|---:|---:|---:|---:|
| Hoje (tudo free, informal) | R$ 0 | R$ 0 | R$ 0 | R$ 0 | **R$ 0** | 0 |
| Free + ferramentas pagas | R$ 0 | R$ 400 | R$ 300 | R$ 0 | **R$ 700** | ~80 |
| Primeiro degrau pago | R$ 145 | R$ 400 | R$ 300 | R$ 0 | **R$ 845** | **~96** |
| + pró-labore R$ 2.000 | R$ 145 | R$ 400 | R$ 300 | R$ 2.000 | **R$ 2.845** | ~323 |
| + pró-labore R$ 3.000 | R$ 145 | R$ 400 | R$ 300 | R$ 3.000 | **R$ 3.845** | ~436 |
| + pró-labore R$ 5.000 | R$ 145 | R$ 400 | R$ 300 | R$ 5.000 | **R$ 5.845** | ~663 |
| + pró-labore R$ 8.000 | R$ 246 | R$ 400 | R$ 300 | R$ 8.000 | **R$ 8.946** | ~1.014 |
| + pró-labore R$ 15.000 | R$ 246 | R$ 400 | R$ 500 | R$ 15.000 | **R$ 16.146** | ~1.831 |

Cada R$ 1.000 de custo fixo exige **~113 assinantes** a R$ 9,90 (mix Pix/cartão). Tudo em cartão: ~119. Tudo em Pix: ~109.

### Com marketing de aquisição

Fórmula: `N = custo fixo ÷ (8,82 − churn × CAC)`. Só funciona se `churn × CAC < 8,82`.

Custo fixo de R$ 3.845 (pró-labore R$ 3 mil) + marketing R$ 1.000:

| Churn | CAC | Contribuição/ativo | Assinantes p/ empatar |
|---:|---:|---:|---:|
| 5% | R$ 30 | R$ 7,32 | ~662 |
| 5% | R$ 50 | R$ 6,32 | ~767 |
| 10% | R$ 30 | R$ 5,82 | ~832 |
| 10% | R$ 50 | R$ 3,82 | ~1.268 |
| 10% | R$ 88 | R$ 0 | impossível |
| 10% | R$ 100 | −R$ 1,18 | impossível |

### Preço x volume (custo fixo R$ 3.845)

| Preço | Líquido/assinante | Assinantes p/ empatar |
|---:|---:|---:|
| R$ 9,90 | R$ 8,82 | ~436 |
| R$ 14,90 | R$ 13,41 | ~287 |
| R$ 19,90 | R$ 18,00 | ~214 |
| R$ 29,90 | R$ 27,18 | ~142 |

**Leitura dura:** a R$ 9,90 o negócio exige volume. A R$ 19,90 o mesmo pró-labore pede **metade** dos assinantes. O preço é a alavanca mais barata que existe — mais barata que infra, que marketing, que código.

## 9. O que ainda falta medir

Este documento é um **modelo**, não um contrato. Para fechar o número real, meça:

1. **Taxa real do MP na sua conta:** a tabela acima é pública; a sua pode ser diferente por volume. Puxe de `/v1/payments` (campo `fee_details`) depois do primeiro pagamento real.
2. **Mix de meios:** quantos por Pix, cartão, boleto. É o que define a margem.
3. **Pró-labore real:** bruto, INSS, IRPF e anexo do Simples.
4. **CAC real por canal:** quanto custa um cadastro e um assinante.
5. **Churn real:** % que cancela em 30/60/90 dias.
6. **Consumo real de infra:** CU-horas do Neon e horas de Render por mês (painel de cada um).
7. **Custo de suporte:** tempo gasto por assinante — o item mais esquecido e o que mais come margem em app financeiro.

## 10. Recomendação prática

- **Fique no free até o primeiro assinante.** A infra do degrau 1 custa ~17 assinantes; antes disso, pagar hospedagem é prejuízo. Mas **no dia em que o primeiro assinante pagar, suba o Render** — cold start de 50 s em app de dinheiro queima a confiança que o produto vende.
- **Não coloque salário de CEO de mercado no início.** Comece com R$ 2.000–3.000 e suba por marco de assinantes.
- **Mantenha IA de desenvolvimento em R$ 300–500/mês** no começo. Uma assinatura boa + API barata resolve.
- **Marketing inicial: R$ 500–1.500/mês**, priorizando conteúdo, SEO, comunidades e indicação. Anúncio pago só escala com CAC < LTV/3 e payback < 6 meses.
- **Pix é o canal de menor atrito e maior margem.** Ofereça Pix antes de cartão e empurre o anual para quem já paga por Pix.
- **Cobrança e divisão não dão receita hoje** — são aquisição e retenção. Se um dia cobrar 1%, anunciar antes, e só depois de o usuário já usar de graça.
- **Se o CAC real passar de R$ 88 com churn 10%, R$ 9,90 não fecha.** Suba o preço para R$ 19,90–29,90 ou melhore retenção.
- **Retenção é o marketing mais barato.** Reduzir churn de 10% para 5% dobra o LTV e derruba o CAC aceitável pela metade.
