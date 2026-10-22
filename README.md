# KaveliPay

App Android de organização financeira e pagamentos para **pessoa física**, com backend de ledger contábil interno.

**MVP sem custódia de dinheiro real**: o saldo é demonstrativo e sempre derivado do ledger.

## O que o app faz

- Saldo, extrato e contas (ledger: `Wallet → Ledger Account → Transaction → Entries`)
- Contas a pagar, cobranças (deep link `kavelipay://cobranca/...` — **não é PIX/EMV**), dívidas e splits
- Relatórios por categoria, recorrência, perfil e notificações
- Assinatura premium (opcional): relatórios mensais, análise de ativos por IA e cotação de mercado

## Stack

| Parte | Stack |
|---|---|
| Backend | Rust + axum + sqlx (Postgres), JWT |
| App | Kotlin + Jetpack Compose (Material 3) |
| Banco | Postgres (local em Docker; Neon em produção) |
| Deploy | Render (Docker) + Neon |

## Rodando local

```bash
# Postgres de dev (porta 55432, user/senha/db = kavelipay)
cd backend
cargo build && cargo test

DATABASE_URL=postgres://kavelipay:kavelipay@localhost:55432/kavelipay \
JWT_SECRET=$(head -c 48 /dev/urandom | base64) BIND_ADDR=0.0.0.0:3000 \
./target/debug/kavelipay-backend
```

```bash
cd android
./gradlew :app:assembleRelease   # APK assinado
./gradlew :app:testDebugUnitTest
```

O `keystore.properties` e o `.jks` ficam **fora do git** — veja `docs/ambiente-e-build.md` para recriá-los.

## Deploy

`render.yaml` (Blueprint) + Postgres no Neon. Guia passo a passo em **[`docs/deploy-render-neon.md`](docs/deploy-render-neon.md)**.

Variáveis obrigatórias no Render: `DATABASE_URL`, `JWT_SECRET` (≥32 chars), `ADMIN_TOKEN` (≥24 chars, opcional). Não defina `BIND_ADDR` — o Render injeta `PORT`.

## Documentação

| Doc | Assunto |
|---|---|
| [`docs/spec.md`](docs/spec.md) | Regras do produto, PIX, decisões, stack, lacunas |
| [`docs/ledger.md`](docs/ledger.md) | Sinal das entradas, splits, invariantes do ledger |
| [`docs/endpoints.md`](docs/endpoints.md) | Contrato da API (inclui assinatura premium) |
| [`docs/ambiente-e-build.md`](docs/ambiente-e-build.md) | Docker, Waydroid, adb, e2e, assinatura do APK |
| [`docs/deploy-render-neon.md`](docs/deploy-render-neon.md) | Deploy do backend |
| [`docs/custos.md`](docs/custos.md) | Custos, pró-labore, marketing, CAC e break-even |
| [`docs/ui-review.md`](docs/ui-review.md) | Gate de review de UI |
| [`docs/transparencia.md`](docs/transparencia.md) | Derivação, categorias, LGPD, recorrência |
| [`docs/ia-e-mercado.md`](docs/ia-e-mercado.md) | IA (OpenRouter) e dados de mercado |

## Regras que não se quebram

- Somente pessoa física — sem painel de lojista, PDV, CRM, estoque.
- **Não é banco, não custodia dinheiro, não é provedor de PIX.**
- Saldo sempre derivado do ledger. Nunca `UPDATE`/`INSERT` direto em saldo.
- Dinheiro em `Decimal` + `NUMERIC(19,4)`; **nunca float** (na API, sempre string).
- `user_id` vem do token, nunca do body/query.
