# Gate de review de UI

Etapa própria entre "o `senior-frontend` implementou a tela" e "pronto". Não
substitui o designer (Penpot segue dono do visual) nem a revisão de código do
`revisor` — este documento é o checklist que o revisor roda quando a tarefa
mexeu em tela. Piso: o checklist mecânico. Teto: o feeling Nubank.

Veredito único, como toda revisão deste time: **APROVADO** ou **REPROVADO** +
lista de achados por severidade (blocker/medio/sugestao) com `arquivo:linha`.
REPROVADO em UI diz exatamente o que corrigir — não "ficou feio".

## Nível 1 — Mecânico (checklist)

Itens derivados do ui-ux-pro-max. Buscas de apoio (o `--design-system` é
enviesado web e **não** serve para app; usar `--stack` e domínios):

```bash
# guia da stack no contexto do componente/cença consultada
python3 ~/.opencode/skills/ui-ux-pro-max/scripts/search.py "<concern>" --stack jetpack-compose
# UX, cor e ícones: um domínio por preocupação
python3 ~/.opencode/skills/ui-ux-pro-max/scripts/search.py "form error summary" --domain ux
python3 ~/.opencode/skills/ui-ux-pro-max/scripts/search.py "error color contrast" --domain color
python3 ~/.opencode/skills/ui-ux-pro-max/scripts/search.py "icon button label" --domain icons
```

Checklist (todo item de tela nova/modificada):

- [ ] **Entrada no padrão do cliente.** Datas aceitam `dd/mm/aaaa` (o que se
      digita); número aceita vírgula; o rótulo mostra o formato que o cliente
      digita, não o formato do banco/ISO. (Caso real: o campo de dividendo
      pedia `AAAA-MM-DD` e rejeitava `14/10/2026`.)
- [ ] **Erro amigável, sem jargão de backend.** Nada de `YYYY-MM-DD`,
      `invalid_transaction`, nome de campo em inglês ou texto crudo da API na
      tela. O usuário lê pt-BR e é orientado ao próximo passo.
- [ ] **Dinheiro sem `Double`/`Float`.** Valor vem como `Money`/string, saldo
      sempre do backend — a tela nunca é fonte da verdade nem soma saldo
      localmente.
- [ ] **Alvos de toque ≥ 48dp** e contraste legível (WCAG AA) em texto e
      estados de erro/sucesso.
- [ ] **Hierarquia:** o número é o herói; rótulo/secundário menor; cor de
      marca como acento, não como preenchimento de tudo. Ícone real (Material),
      nunca emoji.
- [ ] **Estados completos:** carregando, vazio, erro — os três existem para
      todo fluxo que bate na rede.
- [ ] **Copy pt-BR** consistente com as telas vizinhas (mesmo termo para a
      mesma coisa; ver `docs/spec.md`).

## Nível 2 — Visual (referência Nubank)

Screenshot da tela no Waydroid, comparado contra o padrão de qualidade
acordado:

```bash
adb exec-out screencap -p > /tmp/tela.png
```

- **Dark afiado** (onde houver tema escuro): sem cinza morto, sem brilho
  espúrio.
- **Números hierarquizados:** saldo/valor em destaque real, apoio em
  `onSurfaceVariant`.
- **Cor de marca disciplinada:** o roxo/KaveliPay pontua, não domina.
- **Micro-interações suaves:** transições curtas, sem saltos; nada de animação
  que atrapalhe leitura.
- **Zero emoji-ícone** e zero textão decorativo.

Resultado: ou a tela passa no par Nubank, ou a lista diz o quê ajustar
(ex.: "valor em 20sp competindo com o título — promover a 28sp e reduzir o
rótulo").

## Onde isto roda no fluxo

1. `senior-frontend` implementa a tela.
2. `revisor` recebe diff + este checklist (referenciado no `AGENTS.md` e no
   prompt do revisor), roda os dois níveis e devolve APROVADO/REPROVADO.
3. Só então a tarefa é dada como pronta.

Fora de escopo do gate: redesenhar a tela (isso volta ao designer), gerar
código, mexer em regra de ledger/dinheiro.
