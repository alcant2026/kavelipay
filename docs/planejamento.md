# Planejamento: orçamento, metas e resumo da carteira

## O que foi adicionado

Sete funcionalidades que **reaproveitam o ledger e as tabelas existentes**, sem guardar dinheiro novo:

### 1. Saldo disponível × comprometido (`GET /wallet/resumo`)

Mostra o que sobra do saldo depois de descontar os boletos PENDING (incluindo vencidos).

```
saldo       = saldo do ledger (derivado)
comprometido = SUM(amount) dos boletos PENDING
disponivel  = saldo - comprometido
previsao_fim_mes = disponivel (as parcelas futuras de séries recorrentes
                   só viram boleto quando `generate-recurring` roda; a
                   previsão honesta é "o que sobra se tudo em aberto for pago")
```

**Onde aparece:** card na Home abaixo do saldo herói.

### 2. Previsão até o fim do mês

Embutida no `/wallet/resumo` como `previsao_fim_mes`. É o disponível menos o que ainda está em aberto — não projeta gastos hipotéticos (seria mentira).

### 3. Quem me deve agregado (`GET /splits/pendentes`)

Agrupa as partes não quitadas de divisões por participante:

```sql
SELECT participant_name, SUM(owed_amount), COUNT(*)
  FROM expense_split_parts p
  JOIN expense_splits s ON s.id = p.split_id
 WHERE s.wallet_id = $1 AND p.settled_at IS NULL
 GROUP BY participant_name
```

"João — R$ 65,00 (2 divisões)" em vez de duas linhas separadas.

**Onde aparece:** tela "Quem me deve" acessível pelo menu "Mais" da Home.

### 4. Orçamento (teto de gasto por categoria no mês) — `orcamentos` CRUD

Tabela nova `orcamentos`:

| Campo | Tipo | Descrição |
|---|---|---|
| `user_id` | UUID | Dono |
| `category_id` | UUID NULL | NULL = teto do mês inteiro |
| `valor` | NUMERIC(19,4) | Teto |
| `mes` | CHAR(7) | `YYYY-MM` ou `'9999-12'` = permanente |

**Sentinela `'9999-12'` em vez de NULL:** em Postgres, `NULL` nunca é igual a `NULL` num índice unique — um `ON CONFLICT` não parearia dois tetos permanentes e duplicaria em vez de atualizar (foi um bug real encontrado durante o desenvolvimento).

**Endpoints:**
- `GET /orcamentos?mes=YYYY-MM` — lista os tetos vigentes no mês (do mês + permanentes) com o gasto derivado do ledger
- `POST /orcamentos` — cria ou atualiza (upsert por `user_id, category_id, mes`)
- `DELETE /orcamentos/{id}`

**Gasto derivado:** soma os débitos de `EXPENSE`/`BILL_PAID` cuja transação tem a categoria (ou todas, quando `category_id IS NULL`) no período.

**Onde aparece:** tela "Orçamento" acessível pelo menu "Mais" da Home. Barra de progresso por teto; fica vermelha se estourou.

### 5. Metas de economia — `metas` CRUD

Tabela nova `metas`:

| Campo | Tipo | Descrição |
|---|---|---|
| `user_id` | UUID | Dono |
| `nome` | TEXT | "Viagem", "Reserva" |
| `valor_alvo` | NUMERIC(19,4) | Quanto quer juntar |
| `valor_inicial` | NUMERIC(19,4) | O que já tinha guardado antes da meta |
| `prazo` | DATE NULL | Opcional |

**Coluna `meta_id` em `ledger_transactions`:** um aporte para a meta é um lançamento `EXPENSE` com `metadata.meta_id` apontando para a meta. O saldo da carteira reflete a saída (o dinheiro "saiu" da disponível e foi reservado), mas a meta soma o aporte. Nada é criado do nada.

**Endpoints:**
- `GET /metas` — lista com progresso derivado (valor_inicial + aportes)
- `POST /metas` — cria
- `DELETE /metas/{id}`
- `POST /metas/{id}/arquivar` — arquiva (sai da lista padrão, aparece com `?incluidas_arquivadas=true`)
- `POST /metas/{id}/aportar` — registra um aporte

**Onde aparece:** tela "Metas" acessível pelo menu "Mais" da Home. Barra de progresso; botão "Guardar" para aportar.

### 6. Repetir transação

Pré-preenche a tela de Nova transação com os dados do lançamento selecionado. Só app, sem backend novo — o usuário escolhe "Repetir" no histórico e a tela abre com valor/descrição/categoria já preenchidos.

### 7. Parcelamento (`POST /bills/parcelar`)

Compra parcelada vira N boletos PENDING (um por parcela) numa transação só. O parcelamento **não posta no ledger** — cada boleto só vira lançamento quando for marcado como pago.

**Arredondamento:** 100,00 em 3x vira 33,33 + 33,33 + 33,34 (a última parcela absorve a diferença, nunca 99,99).

**Onde aparece:** botão "Parcelar esta compra" na tela de Nova conta.

## Decisões de modelagem

### Por que orçamento e metas não são "caixinha"

A spec diz que caixinha é empréstimo registrado entre conhecidos (`valor_pedido`, `taxa_mensal`, score do responsável, fora do ledger). "Guardar dinheiro para uma meta" ali dentro seria enfiar uma carteira na tabela errada. Por isso metas são feature própria.

### Por que o aporte de meta é uma despesa

O dinheiro guardado numa meta não sai da carteira do usuário — continua sendo saldo demonstrativo. O que a meta registra é "deste saldo, tanto está reservado para isto". O lançamento de aporte usa o tipo `EXPENSE` com `metadata.meta_id`: mexe na despesa (reduz o "disponível") sem inventar conta nova no ledger. A meta soma os aportes; o saldo da carteira reflete a saída; nada é criado do nada.

### Por que `'9999-12'` e não NULL para teto permanente

Em Postgres, `NULL` nunca é igual a `NULL` num índice unique. Um `ON CONFLICT (user_id, category_id, mes) WHERE mes IS NULL` não pareia dois tetos permanentes — duplica em vez de atualizar. O sentinel `'9999-12'` usa a constraint `UNIQUE(user_id, category_id, mes)` normal, que funciona para os dois casos.

## O que NÃO foi feito (e por quê)

- **Importar extrato CSV/OFX:** grande, precisa de staging e deduplicação. Fica para depois.
- **Cobrança recorrente entre pessoas:** reusa `recurrence`, mas exige decisão de produto sobre falha/atraso.
- **Conferir saldo (usuário digita o saldo do banco, app mostra a diferença):** conceito novo — precisa decidir se persiste histórico.
- **Multi-carteira, PIX/BR Code real, upload de comprovante, OCR, open finance:** fora do escopo do MVP por decisão da spec.

## Testes

- **Backend:** 112 testes Rust (era 110) + novo `tests/planejamento_e2e.sh` com 36 checks (orçamento, metas, resumo, parcelamento, fronteira de confiança, splits agregados).
- **Android:** 63 JUnit (inalterado).

## Como usar

1. **Orçamento:** Home → "Mais" → "Orçamento" → "Novo teto". Escolha a categoria (ou "Todas as categorias" para o mês inteiro) e o valor.
2. **Metas:** Home → "Mais" → "Metas" → "Nova meta". Defina nome, valor alvo e (opcional) valor inicial e prazo. Para guardar, toque no botão "+" do card.
3. **Quem me deve:** Home → "Mais" → "Quem me deve". Lista agregada por pessoa.
4. **Parcelar:** Contas → "Nova conta" → preencha valor e vencimento → "Parcelar esta compra" → escolha o número de parcelas.

## Gate de UI — APROVADO (21/11/2026)

Verificado em runtime no Waydroid (app release, produção) contra a conta de teste: Home (card Disponível × Comprometido + menu), Orçamento (vazio → criar teto "Alimentação R$ 200,00" → remover), Metas (vazio → criar "Viagem R$ 1000,00" com barra 0%/Falta → remover), Quem me deve (vazio "Ninguém te deve"). Diálogos com formato pt-BR ("Valor (ex.: 200,00)", "Prazo (dd/mm/aaaa)").

Nenhum blocker. Achados:

- **medio** — `OrcamentosScreen.kt:301` (`KpCategoriaChip`): chips de categoria do diálogo "Novo teto" têm ~46dp de altura, abaixo do piso de 48dp. Corrigir com `padding(vertical = 10.dp)` ou `heightIn(min = 48.dp)`.
- **sugestao** — `MetasScreen.kt:25,49` e `DevedoresScreen.kt:29`: imports não usados (`HorizontalDivider`, `KpBotaoSecundario`). Remover.
- **sugestao** — `DevedoresScreen.kt:50-52`: o "Total a receber" é um `fold` BigDecimal local. Está correto (soma de valores já derivados do backend, não saldo), mas convém comentar que a tela não é fonte da verdade.

### Correções aplicadas (21/11/2026)

- **ícones repetidos no menu "Mais"** (relatado pelo usuário): "Orçamento" usava `PieChart` (igual "Relatório do mês") e "Metas" usava `Savings` (igual "Empréstimos"). Trocados para `DonutSmall` e `Flag`; os 7 itens do menu ficaram com ícones distintos. O ícone do estado vazio de Orçamento também passou a `DonutSmall` pra combinar com o item do menu.
- **medio** (chips 48dp): `KpCategoriaChip` agora tem `heightIn(min = 48.dp)`. Verificado em runtime: todos os chips a 48,0dp.
- **sugestao** (imports não usados): removidos `HorizontalDivider`/`KpBotaoSecundario` de `MetasScreen.kt` e `KpBotaoSecundario` de `DevedoresScreen.kt`.
- **extra** — o `heightIn` nos chips fez o diálogo "Novo teto" transbordar (o último chip, "Energia", ficava cortado e os demais inacessíveis, pois o `Column` não tinha scroll). Adicionado `verticalScroll` ao `Column` do diálogo. Verificado: todos os chips alcançáveis.