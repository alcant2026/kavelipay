//! Serviço do ledger: grava transações e deriva saldo.
//!
//! Invariante: `total_debits = total_credits` por transação. A verificação
//! roda no BANCO (constraint trigger deferrable), não aqui — o service monta a
//! transação, e se alguém errar a contabilidade o COMMIT falha. A checagem em
//! Rust existe só para dar erro de negócio em vez de violação de constraint.

use rust_decimal::Decimal;
use sqlx::{PgExecutor, Postgres, Transaction};
use uuid::Uuid;

use crate::error::{AppError, AppResult};
use crate::money::Money;

/// Contas de ledger. `USER_AVAILABLE` é o saldo do usuário; as demais são as
/// origens/destinos que dão sentido contábil ao movimento.
///
/// `TRANSFER_IN`/`TRANSFER_OUT` existem para que uma transferência entre duas
/// wallets do MESMO usuário não crie nem destrua dinheiro: sai de uma conta de
/// origem e entra em outra, e o total do usuário continua o mesmo.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AccountKind {
    UserAvailable,
    Income,
    Expense,
    TransferIn,
    TransferOut,
    /// Direito a receber de cobranças. Ativo: aumenta quando se cobra, diminui
    /// quando se recebe. Nunca toca a carteira — é o que separa "cobrar" de
    /// "receber".
    Receivable,
    /// Valor aplicado em ativos de investimento.
    ///
    /// Ativo, como `RECEIVABLE`: quando o usuário registra que comprou um ativo,
    /// o dinheiro não sumiu da economia dele — mudou de forma. Uma compra que
    /// fosse debitada de `EXPENSE` faria o saldo cair como se fosse despesa, e
    /// o resultado da carteira de investimento viraria um número sem sentido.
    InvestmentAssets,
}

impl AccountKind {
    pub fn as_str(self) -> &'static str {
        match self {
            AccountKind::UserAvailable => "USER_AVAILABLE",
            AccountKind::Income => "INCOME",
            AccountKind::Expense => "EXPENSE",
            AccountKind::TransferIn => "TRANSFER_IN",
            AccountKind::TransferOut => "TRANSFER_OUT",
            AccountKind::Receivable => "RECEIVABLE",
            AccountKind::InvestmentAssets => "INVESTMENT_ASSETS",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "USER_AVAILABLE" => Some(AccountKind::UserAvailable),
            "INCOME" => Some(AccountKind::Income),
            "EXPENSE" => Some(AccountKind::Expense),
            "TRANSFER_IN" => Some(AccountKind::TransferIn),
            "TRANSFER_OUT" => Some(AccountKind::TransferOut),
            "RECEIVABLE" => Some(AccountKind::Receivable),
            "INVESTMENT_ASSETS" => Some(AccountKind::InvestmentAssets),
            _ => None,
        }
    }
}

/// Tipo de transação de negócio. Distingue entrada de despesa na camada de
/// regra; o sinal contábil vem do par de contas, não deste enum.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransactionType {
    /// Salário, benefício, dinheiro recebido.
    Entry,
    /// Despesa do dia a dia.
    Expense,
    /// Marcação manual de conta paga (sem pagamento real no MVP).
    BillPaid,
    /// Recebimento de uma parte de divisão de despesa.
    ///
    /// Inversão contábil de `BillPaid`: o dinheiro volta para a carteira e a
    /// despesa reconhecida é reduzida na mesma medida. Sem isto, quem pagou o
    /// jantar continuaria com a despesa inteira no extrato mesmo depois de
    /// todos quitarem.
    Settlement,
    /// Recebimento do que uma cobrança devia: o dinheiro entra na carteira
    /// (crédito em `USER_AVAILABLE`) e o direito a receber é extinto (débito
    /// em `RECEIVABLE`).
    ///
    /// Atenção ao sinal: em `par_de_contas` o primeiro elemento é o DÉBITO.
    /// Inverter os dois lados aqui faz o recebimento DIMINUIR a carteira — e a
    /// transação continua equilibrada, então nenhum teste de equilíbrio acusa.
    ChargeReceived,
    /// Compra de ativo de investimento.
    ///
    /// O dinheiro sai da carteira e vira ativo: o usuário não gastou, aplicou.
    /// Debitar `EXPENSE` aqui faria o saldo cair como se fosse despesa — e o
    /// resultado da carteira de investimento viraria um número sem sentido.
    /// Atenção ao sinal: em `par_de_contas` o primeiro elemento é o DÉBITO.
    InvestmentBuy,
    /// Venda de ativo de investimento: o dinheiro volta para a carteira e o
    /// ativo sai do patrimônio (débito `INVESTMENT_ASSETS`, crédito da carteira).
    InvestmentSell,
    /// Dividendo ou rendimento recebido de um ativo.
    ///
    /// Diferente de `Entry`: aqui não é renda de trabalho, é retorno do próprio
    /// capital investido. Registrar como `ENTRY` faria o relatório mensal dizer
    /// que o salário subiu.
    InvestmentDividend,
}

impl TransactionType {
    pub fn as_str(self) -> &'static str {
        match self {
            TransactionType::Entry => "ENTRY",
            TransactionType::Expense => "EXPENSE",
            TransactionType::BillPaid => "BILL_PAID",
            TransactionType::Settlement => "SETTLEMENT",
            TransactionType::ChargeReceived => "CHARGE_RECEIVED",
            TransactionType::InvestmentBuy => "INVESTMENT_BUY",
            TransactionType::InvestmentSell => "INVESTMENT_SELL",
            TransactionType::InvestmentDividend => "INVESTMENT_DIVIDEND",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "ENTRY" => Some(TransactionType::Entry),
            "EXPENSE" => Some(TransactionType::Expense),
            "BILL_PAID" => Some(TransactionType::BillPaid),
            "SETTLEMENT" => Some(TransactionType::Settlement),
            "CHARGE_RECEIVED" => Some(TransactionType::ChargeReceived),
            "INVESTMENT_BUY" => Some(TransactionType::InvestmentBuy),
            "INVESTMENT_SELL" => Some(TransactionType::InvestmentSell),
            "INVESTMENT_DIVIDEND" => Some(TransactionType::InvestmentDividend),
            _ => None,
        }
    }

    /// Conta de origem e conta de destino para este tipo de operação.
    /// (débito, crédito).
    fn par_de_contas(self) -> (AccountKind, AccountKind) {
        match self {
            // Dinheiro entra: debitamos a origem (receita), creditamos a carteira.
            TransactionType::Entry => (AccountKind::Income, AccountKind::UserAvailable),
            // Dinheiro sai: debitamos a carteira, creditamos o destino (despesa).
            TransactionType::Expense | TransactionType::BillPaid => {
                (AccountKind::UserAvailable, AccountKind::Expense)
            }
            // Dinheiro volta: debitamos a despesa (reduz o que foi gasto),
            // creditamos a carteira.
            TransactionType::Settlement => (AccountKind::Expense, AccountKind::UserAvailable),
            // Cobrar NÃO gera lançamento: registrar um direito a receber não move dinheiro,
// e debitá-lo de `INCOME` seria receita negativa (sinal errado). O que está a
            // receber é o somatório de `charges` em PENDING, não o ledger.
            //
            // Receber: debita RECEIVABLE (extingue o direito) e credita a
            // carteira. Este é o ÚNICO toque na carteira em toda a cobrança.
            TransactionType::ChargeReceived => {
                (AccountKind::Receivable, AccountKind::UserAvailable)
            }
            // Comprar ativo: dinheiro sai da carteira (débito) e vira ativo
            // (crédito). O usuário não gastou — aplicou. Se isto fosse
            // `EXPENSE`, o saldo cairia como se fosse despesa e o resultado da
            // carteira de investimento seria um número sem significado.
            TransactionType::InvestmentBuy => {
                (AccountKind::UserAvailable, AccountKind::InvestmentAssets)
            }
            // Vender é o inverso: o ativo sai (débito) e o dinheiro volta para a
            // carteira (crédito).
            TransactionType::InvestmentSell => {
                (AccountKind::InvestmentAssets, AccountKind::UserAvailable)
            }
            // Dividendo entra na carteira como `INCOME`: é receita, mas não é
            // salário. Sem uma conta própria, `par_de_contas` cairia no braço de
            // `Entry` e o relatório mensal diria que a renda subiu.
            TransactionType::InvestmentDividend => (AccountKind::Income, AccountKind::UserAvailable),
        }
    }
}

/// Garante que as contas do ledger existam para a wallet. Idempotente
/// (`ON CONFLICT DO NOTHING`). Uma carteira sem `USER_AVAILABLE` é carteira
/// cujo saldo não existe.
///
/// NÃO cria a wallet: `wallets.user_id` é NOT NULL e só o cadastro sabe o
/// dono. A wallet é criada em `routes::auth::register`; aqui são só as contas.
///
/// Recebe `&mut PgConnection` e não um executor genérico porque a conexão é
/// usada em vários statements seguidos — reemprestar um executor genérico
/// exigiria `Copy`, que `&mut` não implementa.
pub async fn garantir_contas(
    conn: &mut sqlx::PgConnection,
    wallet_id: Uuid,
) -> AppResult<()> {
    for kind in [
        AccountKind::UserAvailable,
        AccountKind::Income,
        AccountKind::Expense,
        AccountKind::TransferIn,
        AccountKind::TransferOut,
        AccountKind::Receivable,
        // Sem esta conta, `INVESTMENT_BUY` falha com "conta de ledger não
        // encontrado" — a carteira registra-compra-vender e nada aparece.
        AccountKind::InvestmentAssets,
    ] {
        sqlx::query(
            "INSERT INTO ledger_accounts (wallet_id, kind)
             VALUES ($1, $2)
             ON CONFLICT (wallet_id, kind) DO NOTHING",
        )
        .bind(wallet_id)
        .bind(kind.as_str())
        .execute(&mut *conn)
        .await?;
    }

    Ok(())
}

async fn id_da_conta<'e, E: PgExecutor<'e>>(
    executor: E,
    wallet_id: Uuid,
    kind: AccountKind,
) -> AppResult<Uuid> {
    sqlx::query_scalar::<_, Uuid>(
        "SELECT id FROM ledger_accounts WHERE wallet_id = $1 AND kind = $2",
    )
    .bind(wallet_id)
    .bind(kind.as_str())
    .fetch_optional(executor)
    .await?
    .ok_or(AppError::NotFound("conta de ledger"))
}

/// Grava uma transação equilibrada na wallet, abrindo a própria transação SQL.
pub async fn postar(
    db: &sqlx::PgPool,
    wallet_id: Uuid,
    tipo: TransactionType,
    valor: Money,
    descricao: Option<&str>,
    metadata: Option<serde_json::Value>,
) -> AppResult<Uuid> {
    postar_com_categoria(db, wallet_id, tipo, valor, descricao, metadata, None, None).await
}

/// Como `postar`, mas classificando o lançamento.
///
/// `category_id` passa por `categories::validar_e_resolver`: categoria de
/// despesa em receita (e vice-versa), ou categoria de outra pessoa, são
/// recusadas com 422. É validação de negócio, não de FK — o banco aceitaria
/// o id, e o relatório por categoria é que mentiria.
pub async fn postar_com_categoria(
    db: &sqlx::PgPool,
    wallet_id: Uuid,
    tipo: TransactionType,
    valor: Money,
    descricao: Option<&str>,
    metadata: Option<serde_json::Value>,
    category_id: Option<Uuid>,
    owner_id: Option<Uuid>,
) -> AppResult<Uuid> {
    let mut tx = db.begin().await?;
    let id = postar_em_com_categoria(
        &mut tx, wallet_id, tipo, valor, descricao, metadata, category_id, owner_id,
    )
    .await?;
    tx.commit().await?;
    Ok(id)
}

pub async fn postar_em_com_categoria(
    tx: &mut Transaction<'_, Postgres>,
    wallet_id: Uuid,
    tipo: TransactionType,
    valor: Money,
    descricao: Option<&str>,
    metadata: Option<serde_json::Value>,
    category_id: Option<Uuid>,
    owner_id: Option<Uuid>,
) -> AppResult<Uuid> {
    if !valor.is_positive() {
        return Err(AppError::InvalidAmount(
            "valor deve ser maior que zero; o sinal vem do tipo de transação".into(),
        ));
    }

    // Só valida categoria se o dono for conhecido. Sem `owner_id` não dá para
    // checar propriedade, e aceitar às cegas seria abrir brecha de escrita.
    let categoria = match (category_id, owner_id) {
        (Some(cid), Some(owner)) => crate::categories::validar_e_resolver(
            tx,
            owner,
            Some(cid),
            crate::categories::kind_para_tipo(tipo),
        )
        .await?,
        (Some(_), None) => {
            return Err(AppError::Internal(anyhow::anyhow!(
                "categoria informada sem owner: impossível validar propriedade"
            )));
        }
        _ => None,
    };

    let (debit_kind, credit_kind) = tipo.par_de_contas();
    let conta_debito = id_da_conta(&mut **tx, wallet_id, debit_kind).await?;
    let conta_credito = id_da_conta(&mut **tx, wallet_id, credit_kind).await?;

    let amount = valor.amount();

    let transaction_id = sqlx::query_scalar::<_, Uuid>(
        "INSERT INTO ledger_transactions
             (wallet_id, type, amount, currency, description, status, metadata, category_id)
         SELECT w.id, $2, $3, w.currency, $4, 'POSTED', COALESCE($5, '{}'::jsonb), $6
         FROM wallets w WHERE w.id = $1
         RETURNING id",
    )
    .bind(wallet_id)
    .bind(tipo.as_str())
    .bind(amount)
    .bind(descricao)
    .bind(metadata)
    .bind(categoria)
    .fetch_optional(&mut **tx)
    .await?
    .ok_or(AppError::NotFound("wallet"))?;

    // Dois lançamentos, um statement. Se fossem dois INSERTs separados, o
    // gatilho diferido ainda passaria (roda no COMMIT), mas um statement
    // elimina a janela em que existe lançamento sem contrapartida.
    sqlx::query(
        "INSERT INTO ledger_entries (transaction_id, account_id, debit, credit)
         VALUES
             ($1, $2, $3, 0),
             ($1, $4, 0, $3)",
    )
    .bind(transaction_id)
    .bind(conta_debito)
    .bind(amount)
    .bind(conta_credito)
    .execute(&mut **tx)
    .await?;

    // Validação explícita antes do commit: se algo entrou torto, o erro é de
    // domínio e a transação volta inteira.
    let (total_debit, total_credit) = sqlx::query_as::<_, (Decimal, Decimal)>(
        "SELECT COALESCE(SUM(debit), 0), COALESCE(SUM(credit), 0)
           FROM ledger_entries WHERE transaction_id = $1",
    )
    .bind(transaction_id)
    .fetch_one(&mut **tx)
    .await?;

    if total_debit != total_credit {
        return Err(AppError::UnbalancedTransaction {
            debits: total_debit.to_string(),
            credits: total_credit.to_string(),
        });
    }

    Ok(transaction_id)
}

/// Saldo da carteira: SEMPRE derivado, `SUM(credit) - SUM(debit)` da conta
/// `USER_AVAILABLE`. Não existe coluna de saldo para ler, e não deve passar a
/// existir.
pub async fn saldo<'e, E: PgExecutor<'e>>(
    executor: E,
    wallet_id: Uuid,
) -> AppResult<(Decimal, String)> {
    let row = sqlx::query_as::<_, (Decimal, String)>(
        "SELECT COALESCE(SUM(e.credit - e.debit), 0), w.currency
           FROM wallets w
           LEFT JOIN ledger_accounts a
                  ON a.wallet_id = w.id AND a.kind = 'USER_AVAILABLE'
           LEFT JOIN ledger_entries e ON e.account_id = a.id
          WHERE w.id = $1
          GROUP BY w.currency",
    )
    .bind(wallet_id)
    .fetch_optional(executor)
    .await?
    .ok_or(AppError::NotFound("wallet"))?;

    Ok(row)
}

/// Extrato da carteira: transações com o débito/crédito efetivo na conta do
/// usuário. `total_pages`/`page` para o app não carregar o histórico inteiro.
pub async fn extrato<'e, E: PgExecutor<'e>>(
    executor: E,
    wallet_id: Uuid,
    limite: i64,
    offset: i64,
) -> AppResult<Vec<(Uuid, String, Decimal, String, bool, chrono::DateTime<chrono::Utc>, Option<String>, Option<String>)>> {
    let linhas = sqlx::query_as::<_, (
        Uuid,
        String,
        Decimal,
        String,
        bool,
        chrono::DateTime<chrono::Utc>,
        Option<String>,
        Option<String>,
    )>(
        // `amount` é o VALOR DO MOVIMENTO, não a coluna `debit`. Numa
        // entrada de dinheiro a carteira é creditada e `debit` vale 0 — usar
        // `e.debit` direto mostrava R$ 0,00 para todo dinheiro que entrava.
        "SELECT t.id,
                t.type,
                CASE WHEN e.debit > 0 THEN e.debit ELSE e.credit END,
                COALESCE(t.description, ''),
                (e.debit > 0) AS is_saida,
                t.created_at,
                c.name,
                c.color
           FROM ledger_transactions t
           JOIN ledger_entries e ON e.transaction_id = t.id
           JOIN ledger_accounts a ON a.id = e.account_id
           LEFT JOIN categories c ON c.id = t.category_id
          WHERE t.wallet_id = $1
            AND a.kind = 'USER_AVAILABLE'
            AND t.status = 'POSTED'
          ORDER BY t.created_at DESC, t.id DESC
          LIMIT $2 OFFSET $3",
    )
    .bind(wallet_id)
    .bind(limite.clamp(1, 200))
    .bind(offset.max(0))
    .fetch_all(executor)
    .await?;

    Ok(linhas)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn entrada_debita_receita_e_credita_carteira() {
        // Sinal contábil é o par de contas, não o enum.
        assert_eq!(
            TransactionType::Entry.par_de_contas(),
            (AccountKind::Income, AccountKind::UserAvailable)
        );
    }

    #[test]
    fn despesa_debita_carteira_e_credita_despesa() {
        assert_eq!(
            TransactionType::Expense.par_de_contas(),
            (AccountKind::UserAvailable, AccountKind::Expense)
        );
    }

    #[test]
    fn conta_paga_move_como_despesa() {
        assert_eq!(
            TransactionType::BillPaid.par_de_contas(),
            (AccountKind::UserAvailable, AccountKind::Expense)
        );
    }

    #[test]
    fn toda_operacao_envolve_a_carteira() {
        // Toda saída de dinheiro passa por USER_AVAILABLE como débito; toda
        // entrada por ela como crédito. Sem exceção, senão saldo mente.
        for t in [
            TransactionType::Entry,
            TransactionType::Expense,
            TransactionType::BillPaid,
            TransactionType::Settlement,
            TransactionType::ChargeReceived,
            TransactionType::InvestmentBuy,
            TransactionType::InvestmentSell,
            TransactionType::InvestmentDividend,
        ] {
            let (d, c) = t.par_de_contas();
            assert!(
                d == AccountKind::UserAvailable || c == AccountKind::UserAvailable,
                "{:?} não toca a carteira do usuário",
                t
            );
        }
    }

    #[test]
    fn receber_cobranca_entra_na_carteira() {
        // `par_de_contas` devolve (DÉBITO, CRÉDITO). A carteira é creditada em
        // toda entrada de dinheiro — se alguém colocar UserAvailable na
        // posição de débito, o recebimento DIMINUI o saldo e a transação
        // continua equilibrada (ninguém percebe pelo equilíbrio).
        let (deb, cred) = TransactionType::ChargeReceived.par_de_contas();

        assert_eq!(
            cred,
            AccountKind::UserAvailable,
            "a carteira tem de ser CREDITADA no recebimento"
        );
        assert_eq!(deb, AccountKind::Receivable, "o direito a receber é extinto");
    }

    #[test]
    fn toda_entrada_de_dinheiro_credita_a_carteira() {
        // Inversão de sinal: oposto disto é dinheiro que sai quando devia entrar.
        for t in [
            TransactionType::Entry,
            TransactionType::Settlement,
            TransactionType::ChargeReceived,
            TransactionType::InvestmentSell,
            TransactionType::InvestmentDividend,
        ] {
            let (_, cred) = t.par_de_contas();
            assert_eq!(
                cred,
                AccountKind::UserAvailable,
                "{:?} creditou algo além da carteira: dinheiro não entrou",
                t
            );
        }
    }

    #[test]
    fn toda_saida_de_dinheiro_debita_a_carteira() {
        for t in [
            TransactionType::Expense,
            TransactionType::BillPaid,
            TransactionType::InvestmentBuy,
        ] {
            let (deb, _) = t.par_de_contas();
            assert_eq!(
                deb,
                AccountKind::UserAvailable,
                "{:?} não debitou a carteira: dinheiro não saiu",
                t
            );
        }
    }

    #[test]
    fn comprar_ativo_move_dinheiro_para_o_ativo() {
        // O erro que este teste existe para pegar: tratar compra de ativo como
        // despesa. A transação continua EQUILIBRADA (o dinheiro sai de um lado e
        // entra em outro), então nenhum teste de equilíbrio acusa — e o saldo do
        // usuário fica errado, com a compra parecendo dinheiro gasto.
        let (deb, cred) = TransactionType::InvestmentBuy.par_de_contas();

        assert_eq!(deb, AccountKind::UserAvailable, "o dinheiro sai da carteira");
        assert_eq!(
            cred,
            AccountKind::InvestmentAssets,
            "e vira ATIVO, não despesa: o usuário aplicou, não gastou"
        );
        assert_ne!(
            cred,
            AccountKind::Expense,
            "comprar ativo debitando EXPENSE faria o resultado da carteira de investimento ser um número sem sentido"
        );
    }

    #[test]
    fn vender_ativo_e_o_inverso_de_comprar() {
        let (deb_compra, cred_compra) = TransactionType::InvestmentBuy.par_de_contas();
        let (deb_venda, cred_venda) = TransactionType::InvestmentSell.par_de_contas();

        assert_eq!(deb_venda, cred_compra);
        assert_eq!(cred_venda, deb_compra);
    }

    #[test]
    fn dividendo_entra_na_carteira_sem_ser_salario() {
        // É receita, então o dinheiro entra. Mas o par de contas é o mesmo de
        // `Entry` de propósito: o que o distingue de salário é o TIPO da
        // transação no extrato, não a conta. Inverter o sinal aqui daria
        // dividendo que tira dinheiro da carteira — e ninguém veria pelo
        // equilíbrio.
        let (deb, cred) = TransactionType::InvestmentDividend.par_de_contas();

        assert_eq!(deb, AccountKind::Income);
        assert_eq!(cred, AccountKind::UserAvailable);
    }

    #[test]
    fn settlement_e_o_inverso_de_bill_paid() {
        // Pagar: carteira -> despesa. Receber de volta: despesa -> carteira.
        // Inverter os dois lados é o erro clássico, e ele "funciona" (a
        // transação fica equilibrada) enquanto deixa o saldo do usuário errado
        // ao contrário.
        let (deb_pag, cred_pag) = TransactionType::BillPaid.par_de_contas();
        let (deb_set, cred_set) = TransactionType::Settlement.par_de_contas();

        assert_eq!(deb_pag, cred_set);
        assert_eq!(cred_pag, deb_set);
    }

    #[test]
    fn round_trip_do_enum_de_conta() {
        for k in [
            AccountKind::UserAvailable,
            AccountKind::Income,
            AccountKind::Expense,
            AccountKind::TransferIn,
            AccountKind::TransferOut,
            AccountKind::Receivable,
            AccountKind::InvestmentAssets,
        ] {
            assert_eq!(AccountKind::from_str(k.as_str()), Some(k));
        }
        assert_eq!(AccountKind::from_str("DESCONHECIDO"), None);
    }

    #[test]
    fn round_trip_do_tipo_de_transacao() {
        for t in [
            TransactionType::Entry,
            TransactionType::Expense,
            TransactionType::BillPaid,
            TransactionType::Settlement,
            TransactionType::ChargeReceived,
            TransactionType::InvestmentBuy,
            TransactionType::InvestmentSell,
            TransactionType::InvestmentDividend,
        ] {
            assert_eq!(TransactionType::from_str(t.as_str()), Some(t));
        }
        assert_eq!(TransactionType::from_str("TRANSFERENCIA"), None);
    }

    #[test]
    fn kinds_de_conta_sao_estaveis_para_o_schema() {
        // Estes strings são gravados no banco e lidos de volta; mudar um sem
        // migration quebraria wallets já criadas.
        assert_eq!(AccountKind::UserAvailable.as_str(), "USER_AVAILABLE");
        assert_eq!(AccountKind::Income.as_str(), "INCOME");
        assert_eq!(AccountKind::Expense.as_str(), "EXPENSE");
        assert_eq!(
            AccountKind::InvestmentAssets.as_str(),
            "INVESTMENT_ASSETS",
            "a string é gravada no banco: mudar sem migration quebra as contas já criadas"
        );
    }
}