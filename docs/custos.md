# Custos, pró-labore e ponto de equilíbrio

> Modelo de custo do KaveliPay para o dono decidir preço e salário com número, não com achismo. **Valores de 2026, em faixa** — troque pelos números reais assim que tiver contrato, CAC e churn medidos. Não é consultoria tributária: alíquota, anexo e encargos mudam; confirme com contador.

## Regra de ouro

O app não é banco e não custodia dinheiro. A assinatura é **receita da empresa**, não saldo do usuário. O custo abaixo é o da operação, não o do ledger.

```
Lucro = N × (preço − imposto − taxa de checkout) − custo fixo − marketing de aquisição
```

- `N` = assinantes ativos.
- `custo fixo` = infra + pró-labore + contador + IA de desenvolvimento + marketing fixo.
- `marketing de aquisição` = `N × churn × CAC` (reposição de quem cancela).

## 1. Infra: Render + Neon

Preços públicos em USD; câmbio assumido **US$ 1 = R$ 5,40** e IOF de ~3,5% no cartão.

### Render (backend Rust)

| Peça | Preço | Observação |
|---|---:|---|
| Web service 0,5 CPU / 512 MB | US$ 7/mês | MVP apertado; pode dormir no free |
| Web service 1 CPU / 2 GB | US$ 25/mês | produção pequena |
| Plano Hobby | US$ 0/mês | 25 serviços, domínio, TLS, DDoS |
| Plano Pro | US$ 25/mês | autoscaling, previews, logs HTTP |

### Neon (Postgres)

| Peça | Preço | Observação |
|---|---:|---|
| Free | US$ 0 | 100 CU-hours/projeto, 1 GB, suspende após 5 min |
| Launch | US$ 0,106/CU-hour | 0,25 CU 24/7 ≈ **US$ 19,35/mês** |
| Storage | US$ 0,35/GB-mês | 1 GB ≈ US$ 0,35 |
| Scale | US$ 0,222/CU-hour | quando crescer |

**Cenário enxuto:** Render US$ 7 + Neon ~US$ 20 = **US$ 27/mês ≈ R$ 146**.
**Cenário produção:** Render US$ 25 + Neon ~US$ 20 = **US$ 45/mês ≈ R$ 243**.
Com domínio, backup, e-mail, monitoramento e IOF: **R$ 200–350/mês**.

> Free começa em R$ 0, mas Render free dorme e Neon free suspende. Para app financeiro com usuário ativo, o mínimo pago é ~R$ 150/mês.

## 2. Pró-labore do sócio (CEO + dono + dev)

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

## 3. IA do app (custo variável)

- Modelo padrão `mistralai/mistral-nemo`: ~**US$ 0,000003 por consulta** (200 tokens).
- Em reais: ~**R$ 0,000015 por consulta**.
- 1.000 consultas/mês ≈ **R$ 0,015**; 10.000 ≈ **R$ 0,15**.
- Cotação brapi/Yahoo/statusinvest: **R$ 0** no fluxo atual.
- Plano pago do brapi (R$ 139,99/mês) foi evitado com statusinvest.

**Conclusão:** IA do app é centavo. Não é o problema do negócio.

## 4. IA de desenvolvimento (custo fixo)

Diferente da IA do app: são ferramentas que o dev usa para programar. Custo fixo mensal, não cresce com usuário.

| Uso | Ferramentas típicas | Custo/mês |
|---|---|---:|
| Leve | 1 assinatura (Copilot/Cursor/Claude) | R$ 100–200 |
| Médio | 2 assinaturas + API | R$ 300–600 |
| Pesado | Claude Max/ChatGPT Pro + API | R$ 1.000–1.500 |

**Impacto no equilíbrio:** cada R$ 100/mês de IA de dev custa ~11 assinantes a R$ 9,90. R$ 1.000/mês custa ~114.

## 5. Marketing

Marketing é o custo que mais pesa. Não é só despesa fixa: ele compra assinante, e cada assinante tem prazo de payback.

### CAC x churn

- Margem por assinante ativo: **≈ R$ 8,80/mês** (R$ 9,90 − 6% − taxa de checkout).
- **CAC** = custo para adquirir um assinante.
- **Churn** = % que cancela por mês.
- Custo de marketing por assinante ativo = **churn × CAC**.

Exemplo: churn 10% e CAC R$ 50 → **R$ 5,00/mês** para manter cada ativo. Sobra R$ 3,80. Com CAC R$ 100, o custo vira R$ 10,00 e a conta fica negativa.

### CAC máximo a R$ 9,90

| Churn | LTV aprox. | CAC ideal (LTV/3) | CAC que paga em 12 meses |
|---:|---:|---:|---:|
| 5% | R$ 176 | ~R$ 59 | ~R$ 105 |
| 8% | R$ 110 | ~R$ 37 | ~R$ 105 |
| 10% | R$ 88 | ~R$ 29 | ~R$ 105 |
| 15% | R$ 59 | ~R$ 20 | ~R$ 105 |

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

## 6. Impostos e taxas de pagamento

- **Simples/DAS:** 6% (piso) a 15,5% do faturamento, conforme anexo. Use o piso para cenário otimista e o anexo real para o conservador.
- **Pix:** ~0,5–1% por transação.
- **Cartão:** ~3–5% + R$ 0,39 por cobrança.
- **IOF:** ~3,5% em compras internacionais (Render/Neon/IA).
- **Play Store:** US$ 25 única para publicar; se usar Play Billing, ~15% sobre assinatura. Hoje o fluxo é código de ativação/checkout externo.
- **Contador:** R$ 300–600/mês.
- **Reserva:** 10–20% do faturamento para contingência.

## 7. Ponto de equilíbrio

### Sem marketing de aquisição

Fórmula: `N = custo fixo ÷ margem por assinante`.

Premissas: margem **R$ 8,80/assinante/mês** no plano mensal; custo fixo inclui infra, pró-labore, contador e IA de dev.

| Pró-labore | Infra + IA dev + contador | Custo fixo | Assinantes p/ empatar |
|---:|---:|---:|---:|
| R$ 0 | R$ 1.150 | R$ 1.150 | ~131 |
| R$ 2.000 | R$ 1.150 | R$ 3.150 | ~358 |
| R$ 3.000 | R$ 1.150 | R$ 4.150 | ~472 |
| R$ 5.000 | R$ 1.150 | R$ 6.150 | ~699 |
| R$ 8.000 | R$ 1.150 | R$ 9.150 | ~1.040 |
| R$ 10.000 | R$ 1.150 | R$ 11.150 | ~1.267 |
| R$ 15.000 | R$ 1.150 | R$ 16.150 | ~1.835 |
| R$ 20.000 | R$ 1.150 | R$ 21.150 | ~2.403 |

Cada R$ 1.000 de custo fixo exige **~114 assinantes** a R$ 9,90. O plano anual a R$ 79,90 rende ~R$ 6,22/mês equivalente e **piora** o equilíbrio; use como retenção.

### Com marketing de aquisição

Fórmula: `N = custo fixo ÷ (8,80 − churn × CAC)`. Só funciona se `churn × CAC < 8,80`.

Com custo fixo de R$ 6.150 (pró-labore R$ 5 mil):

| Churn | CAC | Contribuição/ativo | Assinantes p/ empatar |
|---:|---:|---:|---:|
| 5% | R$ 30 | R$ 7,30 | ~843 |
| 5% | R$ 50 | R$ 6,30 | ~976 |
| 5% | R$ 100 | R$ 3,80 | ~1.618 |
| 10% | R$ 30 | R$ 5,80 | ~1.060 |
| 10% | R$ 50 | R$ 3,80 | ~1.618 |
| 10% | R$ 88 | R$ 0 | impossível |
| 10% | R$ 100 | −R$ 1,20 | impossível |

### Preço x volume

| Custo fixo | Assinantes a R$ 9,90 | a R$ 19,90 | a R$ 29,90 |
|---:|---:|---:|---:|
| R$ 6.150 | ~699 | ~338 | ~223 |
| R$ 11.150 | ~1.267 | ~613 | ~404 |
| R$ 21.150 | ~2.403 | ~1.162 | ~766 |

## 8. O que ainda falta medir

Este documento é um **modelo**, não um contrato. Para fechar o número real, meça:

1. **Hospedagem real:** plano Render e consumo Neon.
2. **Pró-labore real:** bruto, INSS, IRPF e anexo do Simples.
3. **CAC real por canal:** quanto custa um cadastro e um assinante.
4. **Churn real:** % que cancela em 30/60/90 dias.
5. **Taxa real do checkout:** Pix x cartão.
6. **Custo de suporte:** tempo gasto por assinante.

Enquanto esses números não existem, trate as faixas acima como teto e piso, não como previsão.

## 9. Recomendação prática

- **Não coloque salário de CEO de mercado no início.** Comece com R$ 2.000–3.000 e suba por marco de assinantes.
- **Mantenha IA de desenvolvimento em R$ 300–500/mês** no começo. Uma assinatura boa + API barata resolve.
- **Marketing inicial: R$ 500–1.500/mês**, priorizando conteúdo, SEO, comunidades e indicação. Anúncio pago só escala com CAC < LTV/3 e payback < 6 meses.
- **A R$ 9,90, o negócio só fecha com volume alto.** Se o custo fixo for R$ 6.150, são ~700 assinantes; com marketing, ~1.000–1.600.
- **Se o CAC real passar de R$ 88 com churn 10%, R$ 9,90 não fecha.** Suba o preço para R$ 19,90–29,90 ou melhore retenção.
- **Retenção é o marketing mais barato.** Reduzir churn de 10% para 5% dobra o LTV e derruba o CAC aceitável pela metade.
