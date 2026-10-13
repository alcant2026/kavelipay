# Gate de UI — dívidas "Eu devo" (2026-10-13)

**Veredito: APROVADO** (com itens deferred abaixo). Nenhum blocker; nenhum
code change. Relatório detalhado: `.superpowers/sdd/2026-10-13-divisoes-eu-devo/task-8-report.md`.

Fluxo coberto: lista vazia → nova dívida (form vazio/preenchido) → card
Devendo → editar (prefill) → Paguei → Pago → Credor confirmou →
Confirmado pelo credor, mais o caminho de conflito (409).

## Screenshots

| # | Prova |
|---|---|
| 01 | Lista vazia: "Nenhuma divisão" + "Nenhuma dívida" (sem título de seção quando vazio — padrão do componente) |
| 02 | Form "Nova dívida" vazio; rótulo "0,00" pt-BR; aviso de que não mexe no saldo |
| 03 | Form preenchido (ponto digitado via adb aceito e normalizado) |
| 04 | Card Devendo: chip âmbar, R$ 45,50, Paguei + editar + apagar |
| 05 | Estado Pago consolidado (**não** prova o 409 — artefato original; ver 08) |
| 05a | Pago: chip cinza, "Credor confirmou" |
| 06 | Confirmado pelo credor: chip verde, sem ações; chip longo não transborda |
| 07 | **Editar dívida** (`EDITAR_DIVIDA/{id}`): form prefilled, valor em vírgula pt-BR, "Salvar alterações" |
| 08 | **409 real**: "só dívidas pendentes podem ser pagas" em pt-BR no topo da lista |

## Checklist

- Entrada no padrão do cliente ✓ (placeholder/prefill "0,00"/"45,50"; `DividaRegras` normaliza vírgula; sem campo de data no fluxo)
- Erro sem jargão ✓ ("só dívidas pendentes podem ser pagas")
- Dinheiro sem float ✓ (`Money`/string; saldo sempre do backend — Home recalculou após pagar)
- Alvos ≥48dp + contraste ✓ (botões full-width, IconButtons Material, chips/banner legíveis no dark)
- Hierarquia ✓ (valor herói, ícones Material reais, zero emoji)
- Estados: vazio ✓, erro ✓, **carregando DEFERRED** (só existe em voo de request)
- Copy pt-BR consistente ✓
- Nível 2 (Nubank): dark afiado, números hierarquizados, marca disciplinada ✓; **micro-animações DEFERRED** (still não julga)

## Deferred

Carregando (não capturado) · micro-animações (stills) · roundtrip de save do
edit (só o form prefilled foi exercitado) · dd/mm/aaaa (N/A — sem data no fluxo).
