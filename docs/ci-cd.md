# CI/CD

Dois workflows em `.github/workflows/`:

| Arquivo | Quando roda | O que faz |
|---|---|---|
| `ci.yml` | todo push na `main` e todo PR | `cargo test` + e2e em shell (com Postgres real) + testes JUnit + build do APK release |
| `release.yml` | tag `v*` | restaura a chave de assinatura, compila o APK e publica na release do GitHub |

O deploy do backend é do **Render**: o `render.yaml` está no repo com `autoDeploy: true`, então cada push na `main` vira um deploy. O `healthCheckPath` é `/health`, que não toca o banco (o Neon free suspende depois de 5 min).

## Rodar a CI localmente

```bash
cd backend && cargo test                       # 142 testes Rust, sem banco

# e2e: precisa de um Postgres (Docker na porta 55432, user/senha/db = kavelipay)
PGHOST=localhost PGPORT=55432 PGUSER=kavelipay PGPASSWORD=kavelipay \
  bash tests/auth_e2e.sh                       # cada script cria o banco dele

cd ../android && ./gradlew :app:testDebugUnitTest
```

## Secrets do GitHub (só o `release.yml` usa)

Em **Settings → Secrets and variables → Actions**:

| Secret | Valor |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 android/kavelipay-release.jks` |
| `KEYSTORE_PASSWORD` | senha do keystore |
| `KEY_ALIAS` | alias da chave (`kavelipay`) |
| `KEY_PASSWORD` | senha da chave |

O workflow reconstrói `android/keystore.properties` no runner e assina o APK com a chave verdadeira. **A chave e as senhas nunca entram no git.**

Publicar uma versão:

```bash
git tag v0.1.0
git push origin v0.1.0
```

A release sai em https://github.com/alcant2026/kavelipay/releases com o `KaveliPay-release.apk` e o `.sha256`.

## Detalhes que importam

- **A keystore é opcional no build.** `android/app/build.gradle.kts` só cria a `signingConfig` de release se `keystore.properties` existir. Sem ele (CI, outro dev) o build roda e o APK sai assinado com a chave de debug. Antes disso, o projeto nem configurava fora da máquina do dono.
- **O Rust do CI é o mesmo do deploy**: `dtolnay/rust-toolchain@1.88`, igual ao `rust-version` do `Cargo.toml` e à imagem do `Dockerfile`.
- **O e2e usa Postgres de verdade**, não mock: os scripts provam as constraints do ledger (`SUM(owed_amount) = total_amount`, transação equilibrada), e constraint falsa não prova nada.
- **Cada e2e roda em sequência**: cada script sobe o servidor em uma porta própria e cria um banco descartável.
- **O job do Android valida o caminho de release** (`assembleRelease` com R8), não o de debug — é o R8 que quebra, não o debug.

## Se quiser o deploy do backend travado no CI

Hoje o Render deploya assim que o push chega, mesmo se a CI estiver vermelha. Para inverter:

1. No Render: `Auto Deploy` = **No**.
2. Crie um **Deploy Hook** (Render → o serviço → Settings → Deploy hook).
3. Guarde a URL como secret `RENDER_DEPLOY_HOOK` no GitHub e acrescente ao `ci.yml`, no fim do job `backend`:

```yaml
      - name: Dispara o deploy no Render
        if: success() && github.ref == 'refs/heads/main'
        env:
          RENDER_DEPLOY_HOOK: ${{ secrets.RENDER_DEPLOY_HOOK }}
        run: curl -fsS -X POST "$RENDER_DEPLOY_HOOK"
```

Só faça isso se a URL do deploy hook estiver no secret — com `autoDeploy: No` e sem hook, nada é deployado.
