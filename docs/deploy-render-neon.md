# Deploy: Render + Neon

O backend é um binário Rust com Postgres. Não guarda arquivo em disco: tudo que
importa está no Neon. Render roda o container; Neon guarda o banco.

## 1. Neon (Postgres)

1. Crie um projeto no Neon e um banco `kavelipay`.
2. Copie a **connection string direta** (host sem `-pooler`) e acrescente
   `?sslmode=require`. Exemplo:
   `postgresql://usuario:senha@ep-xxx.us-east-2.aws.neon.tech/kavelipay?sslmode=require`
3. Guarde essa URL. Ela é o `DATABASE_URL` do Render.

**Direta x pooled:** para uma instância só e pool de 10 conexões, use a conexão
direta. A pooled (`...-pooler...`) passa por PgBouncer e é útil quando você
escalar várias instâncias; ela pode exigir teste com prepared statements do
sqlx. Comece simples: direta.

**Custo:** o free tier do Neon suspende o compute após 5 minutos sem uso. Por
isso o `/health` do backend **não consulta o banco** — se o Render batesse no
banco a cada health check, o Neon nunca dormiria e os 100 CU-hours do free
acabariam. Use `/health/db` só para diagnóstico manual.

## 2. Render (container)

O arquivo `render.yaml` na raiz define o serviço. Passos:

1. No Render, **New → Blueprint** e aponte para o repositório.
2. Preencha as variáveis marcadas com `sync: false`:
   - `DATABASE_URL` — a URL do Neon com `sslmode=require`.
   - `JWT_SECRET` — mínimo 32 caracteres. Gere com
     `head -c 48 /dev/urandom | base64`.
   - `ADMIN_TOKEN` — mínimo 24 caracteres. É o token que emite código de
     assinatura. Gere com
     `head -c 32 /dev/urandom | base64`.
3. Confirme o deploy. O Render injeta `PORT`; o backend lê `PORT` quando
   `BIND_ADDR` não existe. Não configure `BIND_ADDR` no painel.
4. O health check do Render é `GET /health`. Ele responde `{"status":"ok"}` sem
   tocar no banco.

### Se preferir o formulário manual (New → Web Service)

O Blueprint acima é o atalho. Pelo formulário, preencha:

| Campo | Valor |
|---|---|
| Source | repositório privado `alcant2026/kavelipay` (autorizar o GitHub App e marcar **só** este repositório) |
| Runtime | Docker |
| Root Directory | `backend` (equivale ao Docker Context `./backend` + Dockerfile `./backend/Dockerfile`) |
| Region | Ohio — mesma região do Neon (`aws-us-east-2`), senão cada query paga latência |
| Instance | Starter (US$ 7/mês) |
| Health Check Path | `/health` — o padrão do Render é `/` e nossa API não tem rota `/`; com o padrão o deploy é marcado como falho |
| Auto Deploy | Yes |

Depois, em **Environment**, cadastre as quatro variáveis da tabela da seção 3.
**Não defina `BIND_ADDR`**: no Render a porta vem em `PORT`.

O nome do serviço define a URL. O app Android já aponta para
`https://kavelipay-backend.onrender.com` — se você escolher outro nome, corrija
`CANDIDATOS` em `android/app/src/main/java/com/kavelipay/app/data/ApiClient.kt`
e recompile o APK, ou o app fala com um endereço que não existe.

### O que o container faz no boot

- Conecta no Neon com `sslmode=require`.
- Roda `sqlx::migrate!` — as migrations estão embutidas no binário. **Se você
  editar qualquer `.sql`, rebuild é obrigatório**; sem isso o binário antigo
  sobe com o schema antigo.
- Sobe em `0.0.0.0:$PORT`.
- Encerra com SIGTERM (Render manda esse sinal no deploy/restart).

### Pool

`DB_MAX_CONNECTIONS` (padrão 10). O pool recicla conexão ociosa em 5 minutos e
conexão velha em 30 minutos. Isso evita guardar conexão que o Neon já fechou
quando o compute suspendeu.

O **acquire timeout é de 30s**, e não 5s, por um motivo medido: do cold start
do compute do Neon até o banco aceitar a conexão passa ~5s (testado de dentro
de um container). Com 5s o boot morria com `PoolTimedOut` — o Render marcava o
deploy como falho e reiniciava em loop, toda vez que o serviço ficava mais de
5 minutos sem uso. O mesmo estouro atingia o primeiro request depois de uma
pausa, devolvendo 500 em vez de esperar o banco voltar.

Se o Neon reclamar de limite de conexões, baixe para 5. Se você subir mais de
uma instância no Render, use a connection string pooled do Neon e teste as
migrations: duas instâncias rodando `sqlx::migrate!` ao mesmo tempo disputam o
lock do banco. O sqlx usa advisory lock, mas o deploy fica mais previsível com
uma instância só.

## 3. Variáveis de ambiente

| Variável | Obrigatória | Uso |
|---|---|---|
| `DATABASE_URL` | sim | Postgres do Neon, com `sslmode=require` |
| `JWT_SECRET` | sim | assina access/refresh tokens; mínimo 32 chars |
| `ADMIN_TOKEN` | não | liga rotas `/admin/*`; sem ela devolvem 503 |
| `OPENROUTER_API_KEY` | não | liga a análise por IA do relatório mensal; sem ela o HTML continua sendo gerado, só sem o texto da análise |
| `OPENROUTER_MODEL` | não | padrão `deepseek/deepseek-v4.1-flash-1017:floor` |
| `OPENROUTER_BASE_URL` | não | padrão `https://openrouter.ai/api/v1` (só para mock/teste) |
| `DB_MAX_CONNECTIONS` | não | padrão 10 |
| `LIMITE_POR_MINUTO` | não | padrão 300 req/min por IP (429 acima; 0 desliga) |
| `BREVO_API_KEY` | não | **preferido no plano free do Render**: liga a recuperação de senha pela API HTTPS da Brevo (grátis até 300 e-mails/dia). O free bloqueia saída nas portas 25/465/587, então SMTP só funciona em instância paga |
| `MAIL_FROM` | com `BREVO_API_KEY` | endereço verificado na Brevo que aparece como remetente |
| `SMTP_HOST` | não | alternativa por SMTP (instância paga); sem nenhuma das duas, `/auth/forgot-password` devolve 503 |
| `SMTP_PORT` | não | padrão 465 (TLS implícito; não há STARTTLS/587) |
| `SMTP_USER` / `SMTP_PASS` | não | autenticação; defina as duas juntas (Gmail: senha de app) |
| `SMTP_FROM` | só com `SMTP_HOST` | endereço remetente verificado no provedor |
| `RUST_LOG` | não | `info` é um bom começo |
| `BIND_ADDR` | não | só para rodar fora do Render; no Render use `PORT` |

Nunca commite `DATABASE_URL`, `JWT_SECRET` nem `ADMIN_TOKEN`. O `render.yaml` só declara as chaves; os valores ficam no
painel.

**Cole de uma vez:** no painel do serviço, em *Environment* → *Add from .env*,
cola o conteúdo de `backend/env.render.exemplo` (que é o modelo sem segredo) e
troque os valores. O Render aceita o formato `CHAVE=valor`, uma por linha.

## 4. Depois do deploy

```bash
curl https://SEU-SERVICO.onrender.com/health
# {"status":"ok"}

curl https://SEU-SERVICO.onrender.com/health/db
# {"status":"ok","db":"ok"}  (acorda o Neon; use com moderação)
```

Teste o fluxo real:

1. `POST /auth/register` e `POST /auth/login`.
2. `GET /billing/plano` com o access token.
3. `POST /admin/assinaturas/codigos` com `X-Admin-Token` para emitir um código.
4. `POST /billing/ativar` com o código e confirme o `access_token` novo.
5. `GET /reports/monthly` com o token premium; sem premium deve dar `402` com
   `codigo = "plano_necessario"`.

## 5. Armadilhas

- **Cold start do Render free:** o serviço dorme e a primeira requisição demora.
  Para app com usuário ativo, use o plano `starter` (já no `render.yaml`).
- **Cold start do Neon free:** o compute suspende após 5 min. A primeira query
  depois disso espera o banco acordar (uns 5s, dentro do acquire timeout de 30s)
  e passa. Se a conta for de plano pago, nem suspende.
- **Migrations:** rode uma instância por vez. Se o deploy falhar no meio de uma
  migration, o Render mantém a versão anterior; corrija o SQL e faça rebuild.
- **SSL:** sem `sslmode=require`, o Neon recusa a conexão. Não remova.
- **Senha com caractere especial:** faça URL-encode (`@` vira `%40`, etc.) ou
  copie a URL pronta do painel do Neon.
- **CORS:** o backend está com `CorsLayer::permissive()`. O app Android nativo
  não precisa de CORS; se um dia houver web, restrinja a origem.
- **Segredos:** se `JWT_SECRET` mudar, todos os tokens atuais são invalidados
  (todo mundo precisa logar de novo). Troque só em incidente.

## 6. Rollback

O Render guarda deploys anteriores. Se a versão nova quebrar:

1. **Rollback** no painel do Render para o deploy anterior.
2. Se a migration já rodou, o binário antigo pode não entender o schema novo.
   Nesse caso, reverta a migration com um SQL de correção antes do rollback —
   ou restaure o Neon para o ponto anterior (o Neon tem restore por janela de
   tempo).
3. Confirme `/health` e `/health/db`.

## 7. Checklist de produção

- [ ] `DATABASE_URL` com `sslmode=require` e conexão direta.
- [ ] `JWT_SECRET` aleatório com 32+ caracteres.
- [ ] `ADMIN_TOKEN` aleatório com 24+ caracteres.
- [ ] `OPENROUTER_API_KEY` configurada (sem ela o relatório sai sem análise).
- [ ] `RUST_LOG=info`.
- [ ] Health check `/health` respondendo.
- [ ] `/health/db` respondendo.
- [ ] Migrations aplicadas no boot.
- [ ] `POST /billing/ativar` devolvendo token premium.
- [ ] `GET /reports/monthly` bloqueando sem premium com 402.
- [ ] Backup/restore do Neon testado.
- [ ] Alertas de erro no Render configurados.
