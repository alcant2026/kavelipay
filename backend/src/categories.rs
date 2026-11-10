//! Categorias de receita e despesa.
//!
//! A spec pede categoria em entradas (seção 10) e despesas (seção 11) e não
//! existia implementação. Este módulo cuida disso, mais duas garantias que
//! viram do ledger:
//!
//!  1. Categoria de despesa não pode classificar uma entrada. Se pudesse, o
//!     relatório por categoria misturaria signos e diria "gastou 200 em
//!     Alimentação" quando foram 200 de salário.
//!  2. A categoria tem que ser do DONO da transação. Sem isso, o id de uma
//!     categoria alheia escreveria o nome de outra pessoa no histórico.

use sqlx::{Postgres, Transaction};
use uuid::Uuid;

use crate::error::{AppError, AppResult};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CategoryKind {
    Income,
    Expense,
    /// Movimentação de investimento: compra, venda. Não é receita nem despesa —
    /// o dinheiro muda de FORMA (carteira ↔ ativo), não de natureza.
    ///
    /// Sem este terceiro braço, o Buy tinha que ser mapeado para `Expense`
    /// (por ser débito da carteira) e o relatório mensal dizia que o usuário
    /// "gastou" R$ 350 ao comprar 10 ações. O relatório filtra por tipo
    /// (`routes::reports::RECEITA_E_DESPESA`) e não chama isto para
    /// movimentações de investimento — mas a função é pública e pode ser
    /// chamada por outra rota no futuro, então o caso explícito é mais seguro
    /// que um comentário.
    Investment,
}

impl CategoryKind {
    pub fn as_str(self) -> &'static str {
        match self {
            CategoryKind::Income => "INCOME",
            CategoryKind::Expense => "EXPENSE",
            CategoryKind::Investment => "INVESTMENT",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "INCOME" => Some(CategoryKind::Income),
            "EXPENSE" => Some(CategoryKind::Expense),
            "INVESTMENT" => Some(CategoryKind::Investment),
            _ => None,
        }
    }
}

/// Categorias semeadas no cadastro, exatamente as listas da spec. Semear é
/// melhor que cobrar do usuário: ele renomeia o que quiser e apaga o que não
/// usa.
pub const DEFAULTS: &[(&CategoryKind, &str, &str)] = &[
    // Seção 10 da spec — entradas.
    (&CategoryKind::Income, "Salário", "#22C55E"),
    (&CategoryKind::Income, "Benefício", "#4ADE80"),
    (&CategoryKind::Income, "Transferência recebida", "#86EFAC"),
    (&CategoryKind::Income, "Dinheiro recebido", "#BBF7D0"),
    (&CategoryKind::Income, "Outros", "#A7F3D0"),
    // Seção 11 da spec — despesas.
    (&CategoryKind::Expense, "Aluguel", "#EF4444"),
    (&CategoryKind::Expense, "Alimentação", "#F97316"),
    (&CategoryKind::Expense, "Energia", "#EAB308"),
    (&CategoryKind::Expense, "Internet", "#3B82F6"),
    (&CategoryKind::Expense, "Transporte", "#06B6D4"),
    (&CategoryKind::Expense, "Compras", "#A855F7"),
    (&CategoryKind::Expense, "Saúde", "#EC4899"),
    (&CategoryKind::Expense, "Educação", "#8B5CF6"),
    (&CategoryKind::Expense, "Outros", "#94A3B8"),
];

/// Semeia as categorias padrão. Idempotente e sem duplicar: `ON CONFLICT DO
/// NOTHING` sobre o índice (user, kind, name).
/// Recebe `&mut PgConnection` e não um executor genérico: a conexão é reusada
/// em ~14 statements, e reemprestar um executor genérico exigiria `Copy`, que
/// `&mut` não implementa (mesmo problema de `ledger::garantir_contas`).
pub async fn semear(conn: &mut sqlx::PgConnection, user_id: Uuid) -> AppResult<()> {
    for (kind, nome, cor) in DEFAULTS {
        sqlx::query(
            "INSERT INTO categories (user_id, name, kind, color, is_default)
             VALUES ($1, $2, $3, $4, true)
             ON CONFLICT (user_id, kind, lower(btrim(name))) DO NOTHING",
        )
        .bind(user_id)
        .bind(*nome)
        .bind(kind.as_str())
        .bind(*cor)
        .execute(&mut *conn)
        .await?;
    }
    Ok(())
}

/// Valida que a categoria existe, é do dono e casa com o tipo de transação.
///
/// `tx` é a mesma transação da gravação: se a categoria for inválida, a
/// transação inteira volta e nada fica gravado.
pub async fn validar_e_resolver(
    tx: &mut Transaction<'_, Postgres>,
    user_id: Uuid,
    category_id: Option<Uuid>,
    kind_esperado: CategoryKind,
) -> AppResult<Option<Uuid>> {
    let Some(id) = category_id else {
        return Ok(None);
    };

    let row = sqlx::query_as::<_, (String,)>(
        "SELECT kind FROM categories WHERE id = $1 AND user_id = $2",
    )
    .bind(id)
    .bind(user_id)
    .fetch_optional(&mut **tx)
    .await?
    .ok_or_else(|| {
        AppError::Validation("categoria não existe ou não pertence a esta conta".into())
    })?;

    // `kind` vem como TEXT do banco; converter aqui mantém `CategoryKind` livre
    // de dependência de sqlx (ele não implementa Decode/Type).
    let kind_real = CategoryKind::from_str(&row.0).ok_or_else(|| {
        AppError::Validation(format!("categoria com tipo desconhecido: {}", row.0))
    })?;

    if kind_real != kind_esperado {
        return Err(AppError::Validation(format!(
            "categoria é de {} e não pode classificar uma operação de {}",
            rotulo(kind_real),
            rotulo(kind_esperado)
        )));
    }

    Ok(Some(id))
}

fn rotulo(k: CategoryKind) -> &'static str {
    match k {
        CategoryKind::Income => "receita",
        CategoryKind::Expense => "despesa",
        CategoryKind::Investment => "investimento",
    }
}

/// Tipo de categoria compatível com um tipo de transação do ledger. Permite
/// que `create_transaction` e `mark_paid` compartilhem a validação.
pub fn kind_para_tipo(tipo: crate::ledger::TransactionType) -> CategoryKind {
    use crate::ledger::TransactionType as T;
    match tipo {
        T::Entry | T::ChargeReceived | T::InvestmentDividend => CategoryKind::Income,
        T::Expense | T::BillPaid | T::Settlement => CategoryKind::Expense,
        // Compra e venda são aplicação, não consumo. Mapeá-las para
        // `Expense` aqui seria o bug que o relatório mensal revela: "gastou R$
        // 350" ao comprar 10 ações. Uma categoria de investimento (ex.:
        // "Ações") aceita este tipo, e não uma de despesa.
        T::InvestmentBuy | T::InvestmentSell => CategoryKind::Investment,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tipo_da_transacao_mapeia_para_o_tipo_de_categoria() {
        use crate::ledger::TransactionType as T;
        assert_eq!(kind_para_tipo(T::Entry), CategoryKind::Income);
        assert_eq!(kind_para_tipo(T::ChargeReceived), CategoryKind::Income);
        assert_eq!(kind_para_tipo(T::Expense), CategoryKind::Expense);
        assert_eq!(kind_para_tipo(T::BillPaid), CategoryKind::Expense);
    }

    #[test]
    fn compra_e_venda_de_ativo_nao_sao_despesa() {
        // Este é o teste que trava o bug. Compra de ativo DEBITA a carteira
        // (o dinheiro sai), então o mapeamento "por lado do débito" colocaria
        // `InvestmentBuy` em `Expense` — e o relatório mensal diria que o
        // usuário "gastou" R$ 350 ao comprar 10 ações, que foi justamente o que
        // ele não fez.
        //
        // A propriedade é a distinção: aplicação não é consumo.
        use crate::ledger::TransactionType as T;
        assert_eq!(kind_para_tipo(T::InvestmentBuy), CategoryKind::Investment);
        assert_eq!(kind_para_tipo(T::InvestmentSell), CategoryKind::Investment);

        assert_ne!(
            kind_para_tipo(T::InvestmentBuy),
            CategoryKind::Expense,
            "comprar ativo mapeado como despesa faz o relatório dizer 'gasto' onde foi 'aplicação'"
        );
    }

    #[test]
    fn dividendo_e_receita_mas_nao_e_salario() {
        // Dividendo é receita de verdade: entra no relatório como entrada. Se
        // fosse mapeado como `Investment`, o dinheiro que caiu não apareceria
        // em nenhuma coluna do relatório mensal.
        use crate::ledger::TransactionType as T;
        assert_eq!(kind_para_tipo(T::InvestmentDividend), CategoryKind::Income);
    }

    #[test]
    fn investment_nao_entra_nos_defaults() {
        // As listas da spec (5 receitas + 9 despesas) não têm "investimento",
        // e não devem: a categoria existe para os tipos do ledger
        // (`InvestmentBuy`/`InvestmentSell`), não para o usuário escolher.
        // Semear aqui criaria uma categoria "Investimentos" que ele nunca
        // escolhe para compra — viraria lixo no seletor.
        assert_eq!(
            DEFAULTS.iter().filter(|(k, ..)| **k == CategoryKind::Investment).count(),
            0,
            "categoria de investimento não é semeada: o ativo é a categoria"
        );
    }

    #[test]
    fn defaults_tem_as_duas_metades() {
        let incomes = DEFAULTS.iter().filter(|(k, ..)| **k == CategoryKind::Income).count();
        let expenses = DEFAULTS.iter().filter(|(k, ..)| **k == CategoryKind::Expense).count();

        assert!(incomes >= 5, "a spec lista 5 tipos de entrada");
        assert!(expenses >= 9, "a spec lista 9 tipos de despesa");
    }

    #[test]
    fn nenhum_default_aparece_nas_duas_metades() {
        // "Outros" existe nos dois lados, e é o ÚNICO nome repetido de
        // propósito. Se alguém copiar "Aluguel" para receita, o relatório mente.
        let nomes: Vec<_> = DEFAULTS.iter().map(|(_, n, _)| *n).collect();
        for nome in nomes.iter().filter(|n| **n != "Outros") {
            assert_eq!(
                nomes.iter().filter(|o| *o == nome).count(),
                1,
                "\"{nome}\" aparece mais de uma vez nos defaults"
            );
        }
    }

    #[test]
    fn toda_cor_default_e_hex_valido() {
        // O CHECK do banco exige `^#[0-9A-Fa-f]{6}$`; um default inválido
        // quebraria o cadastro inteiro na hora.
        for (_, nome, cor) in DEFAULTS {
            assert!(
                cor.starts_with('#') && cor.len() == 7,
                "cor de \"{nome}\" não é #RRGGBB: {cor}"
            );
            assert!(
                cor[1..].chars().all(|c| c.is_ascii_hexdigit()),
                "cor de \"{nome}\" tem caractere inválido: {cor}"
            );
        }
    }

    #[test]
    fn round_trip_do_kind() {
        assert_eq!(CategoryKind::from_str("INCOME"), Some(CategoryKind::Income));
        assert_eq!(CategoryKind::from_str("EXPENSE"), Some(CategoryKind::Expense));
        assert_eq!(
            CategoryKind::from_str("INVESTMENT"),
            Some(CategoryKind::Investment)
        );
        assert_eq!(CategoryKind::from_str("OUTRA"), None);
    }
}