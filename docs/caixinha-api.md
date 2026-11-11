# Caixinha — contrato da integração (backend + app)

> Fonte única da verdade desta feature. Backend e app Android implementam a
> partir daqui; divergência deste documento é bug. Decisões já fechadas com o
> usuário em 2026-10-23:

1. **Fora do ledger.** A caixinha registra o que cada pessoa **afirma**;
   nada aqui posta transação, move saldo ou é custodiado. O texto da tela
   continua prometendo "nada aqui mexe no seu saldo" — e o e2e prova.
2. **Sem vitrine pública.** Entrada só por convite: mostrar a caixinha para
   desconhecidos com rendimento mensal fixo é oferta pública de Contrato de
   Investimento Coletivo (valor mobiliário, Lei 6.385/76) e exigiria CVM. Por
   isso o endpoint da vitrine não existe e o banco recusa `publica = true`.
3. **Código de convite é gerado pelo servidor, automaticamente, e é de uso
   único.** Formato `KAV-` + 12 hex maiúsculo (ex.: `KAV-8F3A2C91D4E7`). Sempre
   existe **exatamente um** convite ativo por caixinha: quando o código é
   consumido, o próximo nasce na mesma transação. A API **nunca** devolve
   código já usado — a UI do dono mostra sempre só o atual, sem histórico
   poluindo a tela.

Conceito (herdado do protótipo `CaixinhaModelo.kt`): o responsável (tomador)
pede um valor, cada membro (credor) aporta, e o responsável paga rendimento
mensal sobre o aportado. Regra central: **quem paga propõe, quem recebe
confirma** — só movimento `CONFIRMADO` entra em qualquer número. Todo número
é derivado dos movimentos; nada de saldo guardado.

## Fronteira de confiança (regras inegociáveis)

- Identidade **sempre** do token (`AuthUser`). `user_id` nunca vem do body.
- Quem não é dono nem membro: a caixinha **não existe** para ele → 404
  `Caixinha não encontrada.` (nunca 403 — mesmo padrão das outras rotas).
- Membro vê **só os próprios números** e os **próprios movimentos**; o dono
  vê todos. O convite ativo é visível **só ao dono**.
- Valor de **rendimento nunca vem do cliente**: o servidor calcula (base ×
  taxa). Aporte e devolução vêm do cliente (são afirmações), mas validados.
- Dinheiro: tipo `Money` (string, 2 casas na API), `NUMERIC(19,4)` no banco.
  Taxa ao mês: `NUMERIC(5,2)`, serializada **sem zeros à direita** (`"3"`,
  `"2.5"`). `f64` em dinheiro é bug.
- SQL à mão (`query`/`query_scalar`/`query_as`), **nunca** macro `query!`.
- Arredondamento: **HALF_UP** (`MidpointAwayFromZero` do rust_decimal, 2
  casas) — é o que o protótipo Kotlin faz e o que o Postgres faz com
  `numeric`.

## Migration `backend/migrations/0014_caixinhas.sql`

```sql
-- Caixinha: empréstimo registrado entre conhecidos. FORA do ledger: nada
-- aqui posta transação — a caixinha registra afirmações (quem aportou,
-- quem pagou rendimento, quem devolveu). O saldo da carteira não muda.

CREATE TABLE caixinhas (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dono_user_id      UUID        NOT NULL REFERENCES users (id),
    nome              TEXT        NOT NULL,
    valor_pedido      NUMERIC(19,4) NOT NULL,
    taxa_mensal       NUMERIC(5,2)  NOT NULL,
    previsao_quitacao TEXT,
    telefone_dono     TEXT,       -- só dígitos, 10 ou 11
    publica           BOOLEAN     NOT NULL DEFAULT false,
    criado_em         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT caixinhas_nome_not_blank   CHECK (length(btrim(nome)) > 0),
    CONSTRAINT caixinhas_pedido_positivo  CHECK (valor_pedido > 0),
    CONSTRAINT caixinhas_taxa_positiva   CHECK (taxa_mensal > 0),
    CONSTRAINT caixinhas_telefone_fmt    CHECK (telefone_dono IS NULL OR char_length(telefone_dono) IN (10, 11))
);

-- Convites: 1 código ativo por caixinha; usado = history que a API nunca devolve.
CREATE TABLE caixinha_convites (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    caixinha_id UUID NOT NULL REFERENCES caixinhas (id) ON DELETE CASCADE,
    chave       TEXT NOT NULL,
    criado_em   TIMESTAMPTZ NOT NULL DEFAULT now(),
    usado_em    TIMESTAMPTZ,
    usado_por   UUID REFERENCES users (id)
);
CREATE UNIQUE INDEX caixinha_convite_ativo_uniq
    ON caixinha_convites (caixinha_id) WHERE usado_em IS NULL;
CREATE UNIQUE INDEX caixinha_convite_chave_ativa_uniq
    ON caixinha_convites (chave) WHERE usado_em IS NULL;
CREATE INDEX caixinha_convite_chave_idx ON caixinha_convites (chave);

CREATE TABLE caixinha_membros (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    caixinha_id UUID NOT NULL REFERENCES caixinhas (id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users (id),
    telefone    TEXT,               -- só dígitos, 10 ou 11
    entrou_em   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT caixinha_membro_unico UNIQUE (caixinha_id, user_id),
    CONSTRAINT caixinha_membro_telefone_fmt CHECK (telefone IS NULL OR char_length(telefone) IN (10, 11))
);

CREATE TABLE caixinha_movimentos (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    caixinha_id    UUID NOT NULL REFERENCES caixinhas (id) ON DELETE CASCADE,
    tipo           TEXT NOT NULL,      -- 'APORTE' | 'RENDIMENTO' | 'DEVOLUCAO'
    membro_user_id UUID NOT NULL REFERENCES users (id),
    valor          NUMERIC(19,4) NOT NULL,
    competencia    DATE,               -- dia 1 do mês; SÓ em RENDIMENTO
    status         TEXT NOT NULL DEFAULT 'PROPOSTO',
    proposto_por   UUID NOT NULL REFERENCES users (id),
    criado_em      TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmado_em  TIMESTAMPTZ,

    CONSTRAINT caixinha_mov_tipo_check  CHECK (tipo IN ('APORTE','RENDIMENTO','DEVOLUCAO')),
    CONSTRAINT caixinha_mov_status_check CHECK (status IN ('PROPOSTO','CONFIRMADO','RECUSADO','CANCELADO')),
    CONSTRAINT caixinha_mov_valor_positivo CHECK (valor > 0),
    CONSTRAINT caixinha_mov_competencia_check CHECK ((tipo = 'RENDIMENTO') = (competencia IS NOT NULL)),
    CONSTRAINT caixinha_mov_confirmado_check CHECK (confirmado_em IS NULL OR status = 'CONFIRMADO')
);
CREATE INDEX caixinha_mov_caixinha_idx ON caixinha_movimentos (caixinha_id, criado_em DESC);

-- Evento do score do responsável: append-only, nunca UPDATE/DELETE.
CREATE TABLE caixinha_score_eventos (
    id            UUID PRIMARY KEY,
    dono_user_id  UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    caixinha_id   UUID NOT NULL REFERENCES caixinhas (id) ON DELETE CASCADE,
    tipo          TEXT NOT NULL,
    pontos        INTEGER NOT NULL,
    movimento_id  UUID UNIQUE REFERENCES caixinha_movimentos (id),
    criado_em     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT caixinha_score_tipo CHECK (tipo IN ('RENDIMENTO','DEVOLUCAO','AJUSTE'))
);
```

## Geração de código

`KAV-` + 12 hex maiúsculo, a partir de `Uuid::new_v4().simple()` (sem
dependência nova, entropia do gerador de UUID v4):

```rust
fn gerar_chave() -> String {
    let hex = Uuid::new_v4().simple().to_string(); // 32 hex minúsculo
    format!("KAV-{}", hex[..12].to_uppercase())
}
```

Inserção com `ON CONFLICT DO NOTHING` contra o índice parcial da chave ativa
+ retry (máx. 5). Com 48 bits (12 hex), colisão exige ~2,8×10¹⁴ códigos ativos —
o retry é defensivo.

## Endpoints

Rotas no `routes/mod.rs` — literais **antes** de `{id}`:

```
/caixinhas                     POST   criar
/caixinhas                     GET    minhas (dono ou membro)
/caixinhas/entrar             POST   entrar com chave
/caixinhas/{id}                GET    detalhe
/caixinhas/{id}/aportes       POST   propor aporte          (membro)
/caixinhas/{id}/rendimentos   POST   propor rendimento      (dono)
/caixinhas/{id}/devolucoes    POST   propor devolução       (dono)
/caixinhas/{id}/movimentos/{mov_id}/confirmar   POST       (contraparte)
/caixinhas/{id}/movimentos/{mov_id}/recusar      POST       (contraparte)
/caixinhas/{id}/movimentos/{mov_id}/cancelar     POST       (quem propôs)
/caixinhas/{id}/meu-telefone  PATCH  salvar meu telefone
```

Todo endpoint que devolve detalhe aceita `?mes=YYYY-MM` (default: mês atual
UTC). **O app sempre manda o mês local dele** (`YearMonth.now()`), porque o
ano-mês é coisa de gente, não de servidor.

### Erros (strings exatas — o app mostra como vem)

Nova variante em `error.rs` (aditiva): `NaoEncontrado(String)` → 404, code
`not_found`, mensagem literal (para "Caixinha não encontrada." e "Chave
inválida.", que não casam no formato "{0} não encontrado").

| Situação | Status | Mensagem |
|---|---|---|
| Recurso alheio/inexistente (`{id}`) | 404 | `Caixinha não encontrada.` |
| Movimento inexistente ou de outro | 404 | `Movimento não encontrado.` |
| Chave desconhecida | 404 | `Chave inválida.` |
| Chave correta, já usada | 409 | `Essa chave já foi usada.` |
| Quem entra já é membro | 409 | `Você já está nesta caixinha.` |
| Dono tenta entrar com o próprio código | 409 | `Você é o responsável desta caixinha.` |
| Movimento já resolvido | 409 | `Este movimento já foi resolvido.` |
| Não é a contraparte (confirmar/recusar) | 409 | `Só a outra parte pode confirmar.` / `Só a outra parte pode recusar.` |
| Não é quem propôs (cancelar) | 409 | `Só quem propôs pode cancelar.` |
| Dono tenta aportar | 409 | `O responsável não aporta na própria caixinha.` |
| Revalidação no confirm: pedido cheio / rendimento pago / devolvido | 409 | `O valor pedido já foi alcançado.` / `O rendimento deste mês já foi pago.` / `O saldo em aberto já foi quitado.` |
| Avaliação repetida | 409 | `Você já avaliou esta caixinha.` |
| Valor inválido/zero (aporte, devolução, criação) | 422 | `valor deve ser maior que zero` / `Digite um valor maior que zero.` |
| Aporte acima do disponível (com espaço) | 422 | `Faltam R$ 100,00 para completar o pedido.` (vírgula pt-BR) |
| Aporte com pedido completo | 422 | `O valor pedido já foi alcançado.` |
| Rendimento sem saldo em aberto no mês | 422 | `Não há rendimento em aberto neste mês.` |
| Devolução sem saldo | 422 | `Não há saldo em aberto.` |
| Devolução acima do saldo | 422 | `O saldo em aberto é R$ 600,00.` |
| Telefone sem DDD completo | 422 | `Digite um telefone com DDD.` |
| Criação: nome vazio / taxa inválida | 422 | `nome é obrigatório` / `taxa ao mês deve ser maior que zero` |

### POST /caixinhas (criar)

```json
// request
{ "nome": "Viagem da família", "valor_pedido": "1000.00",
  "taxa_mensal": "3", "previsao_quitacao": "dezembro",
  "telefone": "11999990000" }
// valor_pedido: Money (string). taxa_mensal: string decimal > 0.
// previsao_quitacao/telefone opcionais.
// telefone: servidor guarda SÓ DÍGITOS (10 ou 11), recusa o resto.
```
201 → `CaixinhaDetalhe` (papel `dono`, com `convite` já gerado).

### GET /caixinhas (minhas)

Array solto (como `/splits`), `criado_em DESC`:

```json
[{ "id": "uuid", "nome": "Viagem da família", "papel": "dono" ,
    "valor_pedido": "1000.00", "taxa_mensal": "3",
    "arrecadado": "600.00", "progresso": 0.6,
    "responsavel_score": 595, "responsavel_evidencias": 2, "criado_em": "..." }]
```

### POST /caixinhas/entrar

```json
{ "chave": "  kav-8f3a2c " }
```
Normaliza `trim().to_uppercase()`. Transação: trava o convite com `FOR
UPDATE` → valida dono/membro (sem consumir a chave em erro) → marca
`usado_em`/`usado_por` → insere membro → gera o próximo código da caixinha →
commit. 200 → `CaixinhaDetalhe` (papel `membro`).

### CaixinhaDetalhe (GET /caixinhas/{id} e retorno das mutações)

Dono vê tudo; membro vê `membros` e `movimentos` só dele. `convite` só para
dono, **sempre o ativo**. `devido_mes`/`pago_mes`/`falta_mes` referem-se ao
`?mes=`. `responsavel_score` é o mesmo número para dono e membros.

```json
{
  "id": "uuid", "nome": "Viagem da família",
  "papel": "dono",
  "dono": { "user_id": "uuid", "nome": "João", "telefone": "11999990000" },
  "valor_pedido": "1000.00", "taxa_mensal": "3", "previsao_quitacao": null,
  "arrecadado": "600.00", "arrecadado_pendente": "200.00",
  "disponivel_aporte": "200.00", "progresso": 0.6,
  "responsavel_score": 595, "responsavel_evidencias": 2,
  "convite": "KAV-8F3A2C91D4E7",
  "membros": [{
      "user_id": "uuid", "nome": "Ana", "telefone": "11988881111",
      "aportado": "600.00", "saldo_em_aberto": "600.00",
      "devido_mes": "18.00", "pago_mes": "18.00", "falta_mes": "0.00",
      "rendimento_disponivel": "0.00", "rendimento_pendente": false }],
  "total_devido_mes": "43.50", "total_pago_mes": "18.00", "total_falta_mes": "25.50",
  "movimentos": [{
      "id": "uuid", "tipo": "APORTE", "membro_id": "uuid", "membro_nome": "Ana",
      "valor": "600.00", "competencia": null, "status": "CONFIRMADO",
      "proposto_por": "uuid", "criado_em": "...", "confirmado_em": "..." }]
}
```

`movimentos`: **todos** os `PROPOSTO` visíveis (é daqui que a tela "Para
confirmar" deriva) ∪ últimos 10 resolvidos visíveis, `criado_em DESC`, sem
duplicar. `competencia` como `"2026-10"` ou `null`. Os `total_*_mes` só
fazem sentido pro dono (membro recebe `null`).

### POST /caixinhas/{id}/aportes — membro propõe

```json
{ "amount": "300.00" }
```
Valida `amount ≤ disponivel_aporte`. 201 → detalhe.

### POST /caixinhas/{id}/rendimentos — dono propõe

```json
{ "membro_id": "uuid", "competencia": "2026-10" }
```
**Sem valor no body**: servidor calcula `rendimento_disponivel` do membro no
mês (ver derivação) e propõe esse valor inteiro; ≤ 0 → 422. 201 → detalhe.

### POST /caixinhas/{id}/devolucoes — dono propõe

```json
{ "membro_id": "uuid", "amount": "200.00" }
```
Valida `amount ≤ disponivel_devolucao` (saldo em aberto − devoluções
propostas). 201 → detalhe.

### confirmar / recusar / cancelar — ver regras de quem pode na tabela de erros

Confirmar revalida o estado (aporte não estoura `valor_pedido`; rendimento
não excede o devido do mês; devolução não excede o saldo em aberto), com as
mensagens da tabela. 200 → detalhe.

### Score do responsável (não é endpoint separado)

Vem em `CaixinhaDetalhe` e no card de `GET /caixinhas`, visível para dono e
membros. **Sem vitrine pública**: quem tem o código e ainda não entrou não vê
o score.

Base 500, faixa 0–1000, derivado — não existe coluna de score:

```
rendimento confirmado em dia            +25
rendimento confirmado com atraso        +25 − 10 × meses_de_atraso (piso 0)
devolução total confirmada (1ª vez)     +70
mês vencido sem rendimento pago         −10 por mês (máx. −60 por caixinha)
```

Vencimento do rendimento: **dia 10 do mês seguinte à competência**, 00:00 UTC
(`2026-11` vence em `2026-12-10`). Atraso conta em meses inteiros.

Freios anti-fraude (colusão entre conhecidos):

- soma por `(caixinha, membro)` limitada a ±100 — um amigo não infla sozinho;
- soma por caixinha limitada a ±120;
- acima de 600 exige **2 membros distintos** com aporte confirmado
  (`responsavel_evidencias`); sem isso o score para em 600.

O título honesto: **reputação declarada e confirmada entre as partes**, não
score de crédito. Sem custódia, a confirmação é dos membros; conluio continua
possível. `previsao_quitacao` é texto livre, então atraso de devolução não é
mensurável — só rendimento vencido derruba o score.

### PATCH /caixinhas/{id}/meu-telefone

```json
{ "telefone": "11988887777" }
```
Dono grava `telefone_dono`; membro grava o próprio `caixinha_membros.telefone`.
Filtrar só dígitos; 10 ou 11, senão 422. 200 → detalhe.

## Derivações (funções puras, unidade-testáveis sem banco)

Entrada: lista de movimentos do membro + `mes`. Espelham `CaixinhaModelo.kt`
(protótipo) — o Kotlin que existia é a especificação:

```
arrecadado            = Σ APORTE CONFIRMADO
arrecadado_pendente   = Σ APORTE PROPOSTO
disponivel_aporte     = max(valor_pedido − arrecadado − arrecadado_pendente, 0)
progresso             = clamp(arrecadado / valor_pedido, 0, 1)   // 4 casas, f32
aportado(m)           = Σ APORTE CONFIRMADO de m
saldo_em_aberto(m)    = max(Σ APORTE CONF de m − Σ DEVOLUCAO CONF de m, 0)
base(m, mes)          = a = Σ APORTE CONF de m com confirmado_em < dia 1 do mes
                        d = Σ DEVOLUCAO CONF de m com confirmado_em < dia 1 do mes
                        se d ≥ a então 0 senão a
                        // devolução PARCIAL não reduz a base; total zera. Sem pro rata.
devido_mes(m, mes)    = round_half_up(base × taxa_mensal / 100, 2)
pago_mes(m, mes)      = Σ RENDIMENTO CONF de m no mes
falta_mes(m, mes)     = max(devido_mes − pago_mes, 0)
rendimento_disponivel = max(falta_mes − Σ RENDIMENTO PROPOSTO de m no mes, 0)
rendimento_pendente   = ∃ RENDIMENTO PROPOSTO de m no mes
disponivel_devolucao  = max(saldo_em_aberto − Σ DEVOLUCAO PROPOSTO de m, 0)

score(responsável)    = clamp(500 + Σ eventos + Σ penalidades, 0, 1000)
  eventos por (caixinha,membro) clampados a ±100; por caixinha a ±120
  sem 2 membros com aporte confirmado, teto 600
```

## Testes

**Rust (sem banco)** — derivações puras, espelhando `CaixinhaModeloTest`:
base com devolução parcial vs. total; HALF_UP do rendimento; falta
descontando proposta pendente; código no formato `^KAV-[0-9A-F]{12}$`.

**e2e `backend/tests/caixinhas_e2e.sh`** (porta **3324**, banco próprio `*_test_<pid>`, `cargo build` antes — o
`sqlx::migrate!` embute a migration nova):
criação com convite → dono não entra na própria → segunda conta entra
(case/whitespace ok) → mesma chave de novo `409` → dono tem código NOVO ≠
primeiro → chave inválida `404` → aporte acima do disponível `422` → aporte
ok `PROPOSTO` → membro não confirma o próprio aporte `409` → dono confirma →
rendimento proposto pelo dono com valor do servidor → membro confirma →
rendimento de novo no mês `422` → devolução total confirmada zera saldo →
score 500 + 25 + 70 = 595; membro vê o mesmo; aporte antigo sem rendimento
pago derruba para 575 → rota antiga `/avaliacao` devolve `404` → telefone
inválido `422` e válido aparece pro dono → não-membro vê `404` e a lista
não traz alheia → `GET /caixinhas/publicas` devolve `404` (vitrine removida) →
**`GET /wallet` do dono e do membro com saldo `0.00` do começo ao fim**
(prova do "fora do ledger").

**JUnit (app)** — parse dos DTOs (detalhe, movimento), regras que
sobrarem em `CaixinhaRegras` (`parseValor`, `formatarTelefone`).

## App Android — escopo

- **Remover**: `CaixinhaModelo.exemplo()`, `DONO_CAIXINHA`, chips de troca de identidade ("Responsável"/"Ana",
  "Carla"/"Dono"), `"KAV-8F3A2C91D4E7"` fixo, aviso "PRÉVIA DO PROTÓTIPO".
- **Manter**: `CaixinhaRegras.parseValor`/`formatarTelefone` (validação de
  entrada), componentes `KpCard`/`KpBotao*`/`Estrelas`, `Money.brl`,
  `copiarCodigo` + Toast, textos de transparência (atualizar "protótipo
  local" → fala com o servidor, mas continua sem mover dinheiro).
- **Telas**:
  1. **Minhas caixinhas** (nova; Home "Caixinha" aponta pra cá): seções
     "Como responsável" / "Como membro", card com nome, arrecadado de
     pedido, progresso, taxa, score do responsável; botões "Nova caixinha" e "Entrar com
     código" (dialog). Estados carregando/vazio/erro completos.
  2. **Nova caixinha** (nova): nome, valor pedido, taxa ao mês (%),
     previsão de quitação (texto) e seu telefone. Validação local + mensagem
     do backend.
  3. **Detalhe** (rewrite de `KpCaixinhaScreen`): papel vem do servidor;
     "Para confirmar" derivado dos movimentos `PROPOSTO`; pedido com
     convite copiável (dono); quem está dentro (dono: todos, membro: só
     ele); score do responsável; contato + editar meu telefone; aportar
     (membro); pagar rendimento/devolver (dono, por membro); histórico.
- **Regra de ouro da UI**: o servidor é a fonte da verdade — nenhum número
  é calculado/somado no app; dinheiro nunca `Double`/`Float` (string do
  servidor + `Money.brl`); toda ação que bate na rede tem carregando/vazio/
  erro; erro 4xx do backend aparece como está (strings acima são pt-BR).

## Docs que a implementação atualiza

- `docs/endpoints.md`: seção Caixinha com todos os endpoints.
- `docs/ambiente-e-build.md`: `caixinhas_e2e.sh` na lista (porta 3324) e
  contadores de teste.
- `AGENTS.md`: só contadores (estado atual) — o Lead atualiza ao final.
