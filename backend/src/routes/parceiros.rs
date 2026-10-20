//! Parceiros: vitrine estática de indicações de loja (afiliado).
//!
//! SÓ LEITURA. Não existe POST/DELETE aqui, e a ausência é deliberada: quem
//! controla a vitrine é a migration (`0011_parceiros`), não o usuário. Uma
//! rota de escrita transformaria o KaveliPay num catálogo de terceiro — que é
//! justamente o que o app não é (pessoa física, sem painel de lojista, sem
//! estoque, sem PDV).
//!
//! A compra NÃO passa por aqui. O app abre o destino numa WebView; nada é
//! cobrado, nada é registrado no ledger, e nenhum valor desta tabela vira
//! saldo ou despesa. Se a pessoa comprar, quem sabe disso é a loja — não nós.
//!
//! Autenticado de propósito: a vitrine só existe dentro do app logado, e
//! assim o endpoint não vira uma URL pública de propaganda raspável.

use axum::Json;
use axum::extract::State;
use serde::Serialize;
use uuid::Uuid;

use crate::auth::AuthUser;
use crate::error::AppResult;
use crate::state::AppState;

#[derive(Serialize)]
pub struct ParceiroResponse {
    pub id: Uuid,
    pub loja: String,
    pub titulo: String,
    pub imagem_url: String,
    pub destino_url: String,
}

/// `GET /parceiros` — ativas, na ordem curada.
///
/// Sem preço: ver o cabeçalho de `migrations/0011` para por que o campo não
/// existe. O app mostra "ver preço na loja" e o número verdadeiro fica sempre
/// no destino, onde não envelhece calado.
pub async fn list(
    State(state): State<AppState>,
    _auth: AuthUser,
) -> AppResult<Json<Vec<ParceiroResponse>>> {
    // Sem filtro por `user_id`: esta tabela não tem dono. Ela não guarda dado
    // de ninguém — é a mesma lista para todo mundo, como um banner.
    let linhas = sqlx::query_as::<_, (Uuid, String, String, String, String)>(
        "SELECT id, loja, titulo, imagem_url, destino_url
           FROM parceiros_ofertas
          WHERE ativo
          ORDER BY ordem, criado_em",
    )
    .fetch_all(&state.db)
    .await?;

    Ok(Json(
        linhas
            .into_iter()
            .map(
                |(id, loja, titulo, imagem_url, destino_url)| ParceiroResponse {
                    id,
                    loja,
                    titulo,
                    imagem_url,
                    destino_url,
                },
            )
            .collect(),
    ))
}
