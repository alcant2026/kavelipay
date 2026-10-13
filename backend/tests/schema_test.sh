#!/usr/bin/env bash
# Testes do schema do ledger contra um Postgres real.
#
# Cria um banco descartável, aplica as migrations e verifica os invariantes que
# a spec torna obrigatórios. Roda de forma independente de cargo: o schema é
# a garantia, não a aplicação Rust.
#
# Uso:  tests/schema_test.sh
# Env:   PGHOST PGPORT PGUSER PGPASSWORD (ex.: localhost 55432 kavelipay test)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRATIONS="$ROOT/migrations"
DB="kavelipay_schema_test_$$"

PSQL=(psql -v ON_ERROR_STOP=1 -q -X)

pass=0
fail=0

cleanup() {
    "${PSQL[@]}" -d postgres -c "DROP DATABASE IF EXISTS \"$DB\";" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# expect_fail <descrição> <SQL que deve falhar>
expect_fail() {
    local desc="$1" sql="$2"
    if "${PSQL[@]}" -d "$DB" -c "$sql" >/dev/null 2>&1; then
        printf '  FALHOU (deveria rejeitar): %s\n' "$desc"
        fail=$((fail + 1))
    else
        printf '  ok (rejeitado): %s\n' "$desc"
        pass=$((pass + 1))
    fi
}

# expect_ok <descrição> <SQL que deve passar>
expect_ok() {
    local desc="$1" sql="$2"
    if "${PSQL[@]}" -d "$DB" -c "$sql" >/dev/null 2>&1; then
        printf '  ok (aceito): %s\n' "$desc"
        pass=$((pass + 1))
    else
        printf '  FALHOU (deveria aceitar): %s\n' "$desc"
        "${PSQL[@]}" -d "$DB" -c "$sql" 2>&1 | head -3
        fail=$((fail + 1))
    fi
}

echo "criando banco de teste: $DB"
"${PSQL[@]}" -d postgres -c "CREATE DATABASE \"$DB\";"

echo "aplicando migrations"
for f in "$MIGRATIONS"/*.sql; do
    echo "  $(basename "$f")"
    "${PSQL[@]}" -d "$DB" -f "$f"
done

# ── Fixture ───────────────────────────────────────────────────────────────────
# Dois usuários, uma wallet cada. Cada wallet tem USER_AVAILABLE (saldo do
# usuário) e INCOME (origem do dinheiro): toda entrada debita INCOME e credita
# USER_AVAILABLE. Sem essa conta de origem não existe contrapartida, e aí a
# desequilibragem é o comportamento CORRETO — não um bug.
"${PSQL[@]}" -d "$DB" <<'SQL'
INSERT INTO users (email, password_hash) VALUES ('a@teste', 'x'), ('b@teste', 'x');
INSERT INTO wallets (user_id) SELECT id FROM users;

INSERT INTO ledger_accounts (wallet_id, kind)
SELECT w.id, k.kind
FROM wallets w
CROSS JOIN (VALUES ('USER_AVAILABLE'), ('INCOME')) AS k(kind);
SQL

WALLET_A=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT w.id FROM wallets w JOIN users u ON u.id=w.user_id WHERE u.email='a@teste'")
WALLET_B=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT w.id FROM wallets w JOIN users u ON u.id=w.user_id WHERE u.email='b@teste'")

# acct <wallet_id> <kind>
acct() {
    psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
        "SELECT id FROM ledger_accounts WHERE wallet_id='$1' AND kind='$2'"
}
AVAIL_A=$(acct "$WALLET_A" USER_AVAILABLE)
INCOME_A=$(acct "$WALLET_A" INCOME)
AVAIL_B=$(acct "$WALLET_B" USER_AVAILABLE)

echo
echo "invariantes do ledger"

# Entrada de R$ 500: debita INCOME, credita USER_AVAILABLE. Equilibrada.
expect_ok "transação equilibrada (débito INCOME + crédito USER_AVAILABLE) é aceita" "
BEGIN;
INSERT INTO ledger_transactions (wallet_id, type, amount, description, status)
VALUES ('$WALLET_A', 'ENTRY', 500.00, 'Salário', 'POSTED');
INSERT INTO ledger_entries (transaction_id, account_id, debit, credit)
SELECT t.id, a.account_id, a.debit, a.credit
FROM ledger_transactions t
CROSS JOIN LATERAL (
    VALUES ('$INCOME_A'::uuid, 500.00::numeric, 0::numeric),
           ('$AVAIL_A'::uuid, 0::numeric, 500.00::numeric)
) AS a(account_id, debit, credit)
WHERE t.description = 'Salário';
COMMIT;
"

# Saldo derivado: soma dos lançamentos. Não existe coluna de saldo em lugar nenhum.
BAL=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT COALESCE(SUM(credit - debit), 0) FROM ledger_entries WHERE account_id='$AVAIL_A'")
case "$BAL" in
    500.00|500.0000)
        printf '  ok: saldo derivado = %s (soma dos lançamentos, sem coluna de saldo)\n' "$BAL"
        pass=$((pass + 1)) ;;
    *)
        printf '  FALHOU: saldo derivado = %s (esperado 500.00)\n' "$BAL"
        fail=$((fail + 1)) ;;
esac

# Precisão decimal: R$ 10,50 persiste como valor EXATO em NUMERIC(19,4).
# A escala 4 é o spec (`10.50` vira `10.5000`, que é o MESMO número). O que não
# pode acontecer é drift: 10.4999 ou 10.5000000000000003. Verificamos que
# amount = 10.50 exatamente e que uma subtração não acumula erro de float.
expect_ok "R\$ 10,50 é aceito como valor" "
INSERT INTO ledger_transactions (wallet_id, type, amount, description, status)
VALUES ('$WALLET_A', 'ENTRY', 10.50, 'teste de precisão', 'POSTED');
"
# Compara no domínio numérico, não no texto: aqui '10.5000' e '10.50' são iguais.
EXACT=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT (amount = 10.50)::text FROM ledger_transactions WHERE description='teste de precisão'")
SCALE=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT amount::text FROM ledger_transactions WHERE description='teste de precisão'")
# Soma de dois lançamentos de 0,10 tem de dar exatamente 0,20.
DRIFT=$(psql -v ON_ERROR_STOP=1 -q -X -t -A -d "$DB" -c \
    "SELECT ((0.10::numeric + 0.10::numeric) = 0.20::numeric)::text")
if [ "$EXACT" = "true" ] && [ "$SCALE" = "10.5000" ] && [ "$DRIFT" = "true" ]; then
    printf '  ok: R$ 10,50 exato em %s (escala 4 do spec, sem drift de float)\n' "$SCALE"
    pass=$((pass + 1))
else
    printf '  FALHOU: exact=%s scale=%s drift=%s\n' "$EXACT" "$SCALE" "$DRIFT"
    fail=$((fail + 1))
fi

# ── Invariantes que precisam falhar ───────────────────────────────────────────

expect_fail "transação sem contrapartida é rejeitada" "
BEGIN;
INSERT INTO ledger_transactions (wallet_id, type, amount, description, status)
VALUES ('$WALLET_A', 'ENTRY', 100.00, 'sem contrapartida', 'POSTED');
INSERT INTO ledger_entries (transaction_id, account_id, debit, credit)
SELECT t.id, '$AVAIL_A', 0, 100.00 FROM ledger_transactions t
WHERE t.description='sem contrapartida';
COMMIT;
"

expect_fail "débitos != créditos é rejeitado" "
BEGIN;
INSERT INTO ledger_transactions (wallet_id, type, amount, description, status)
VALUES ('$WALLET_A', 'ENTRY', 100.00, 'desequilibrada', 'POSTED');
INSERT INTO ledger_entries (transaction_id, account_id, debit, credit)
SELECT t.id, '$AVAIL_A', 40.00, 100.00 FROM ledger_transactions t
WHERE t.description='desequilibrada';
COMMIT;
"

expect_fail "débito E crédito simultâneos no mesmo lançamento é rejeitado" "
BEGIN;
INSERT INTO ledger_transactions (wallet_id, type, amount, description, status)
VALUES ('$WALLET_A', 'ENTRY', 10.00, 'ambíguo', 'POSTED');
INSERT INTO ledger_entries (transaction_id, account_id, debit, credit)
SELECT t.id, '$AVAIL_A', 10.00, 10.00 FROM ledger_transactions t
WHERE t.description='ambíguo';
COMMIT;
"

expect_fail "conta de outra wallet é rejeitada" "
BEGIN;
INSERT INTO ledger_transactions (wallet_id, type, amount, description, status)
VALUES ('$WALLET_A', 'ENTRY', 10.00, 'cross-wallet', 'POSTED');
INSERT INTO ledger_entries (transaction_id, account_id, debit, credit)
SELECT t.id, '$AVAIL_B', 10.00, 0 FROM ledger_transactions t
WHERE t.description='cross-wallet';
COMMIT;
"

expect_fail "conta repetida na mesma transação é rejeitada" "
BEGIN;
INSERT INTO ledger_transactions (wallet_id, type, amount, description, status)
VALUES ('$WALLET_A', 'ENTRY', 10.00, 'conta repetida', 'POSTED');
INSERT INTO ledger_entries (transaction_id, account_id, debit, credit)
SELECT t.id, '$AVAIL_A', 10.00, 0 FROM ledger_transactions t
WHERE t.description='conta repetida';
INSERT INTO ledger_entries (transaction_id, account_id, debit, credit)
SELECT t.id, '$AVAIL_A', 0, 10.00 FROM ledger_transactions t
WHERE t.description='conta repetida';
COMMIT;
"

expect_fail "valor negativo na transação é rejeitado" "
INSERT INTO ledger_transactions (wallet_id, type, amount, status)
VALUES ('$WALLET_A', 'ENTRY', -50.00, 'negativo', 'POSTED');
"

expect_fail "segunda carteira para o mesmo usuário é rejeitada" "
INSERT INTO wallets (user_id) SELECT id FROM users WHERE email='a@teste';
"

expect_fail "moeda em caixa baixa é rejeitada" "
INSERT INTO ledger_accounts (wallet_id, kind, currency)
VALUES ('$WALLET_A', 'RECEIVABLE', 'brl');
"

expect_fail "status inválido na transação é rejeitado" "
INSERT INTO ledger_transactions (wallet_id, type, amount, status)
VALUES ('$WALLET_A', 'ENTRY', 10.00, 'QUASE_PAGO');
"

echo
echo "resultado: $pass passaram, $fail falharam"
[ "$fail" -eq 0 ]