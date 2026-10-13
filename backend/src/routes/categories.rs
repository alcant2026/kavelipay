//! Categorias: CRUD e classificação.
//!
//! `GET /categories?kind=EXPENSE` — o app popula o seletor.
//! `POST /categories` — categoria do usuário.
//! `PATCH /categories/{id}` — renomear/recor (default não some).
//! `DELETE /categories/{id}` — remove. Transações já classificadas ficam sem
//! categoria (`ON DELETE SET NULL`) em vez de perder o lançamento.

use axum::Json;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::categories::{self, CategoryKind};
use crate::error::{AppError, AppResult};
use crate::state::AppState;

#[derive(Serialize)]
pub struct CategoryResponse {
    pub id: Uuid,
    pub name: String,
    pub kind: String,
    pub color: Option<String>,
    pub is_default: bool,
}

#[derive(Deserialize)]
pub struct CategoryQuery {
    pub kind: Option<String>,
}

#[derive(Deserialize)]
pub struct NewCategory {
    pub name: String,
    pub kind: String,
    pub color: Option<String>,
}

#[derive(Deserialize)]
pub struct UpdateCategory {
    pub name: Option<String>,
    pub color: Option<String>,
}

pub async fn list(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Query(q): Query<CategoryQuery>,
) -> AppResult<Json<Vec<CategoryResponse>>> {
    let kind = match q.kind.as_deref().map(CategoryKind::from_str) {
        // Filtro ausente: lista todas.
        None => None,
        // Valor presente e válido.
        Some(Some(k)) => Some(k),
        // Valor presente mas inválido.
        Some(None) => {
            return Err(AppError::Validation(
                "kind deve ser INCOME ou EXPENSE".into(),
            ));
        }
    };

    let linhas = sqlx::query_as::<_, (Uuid, String, String, Option<String>, bool)>(
        "SELECT id, name, kind, color, is_default
           FROM categories
          WHERE user_id = $1 AND ($2::text IS NULL OR kind = $2)
          ORDER BY is_default DESC, lower(name)",
    )
    .bind(auth.user_id())
    .bind(kind.map(|k| k.as_str()))
    .fetch_all(&state.db)
    .await?;

    Ok(Json(
        linhas
            .into_iter()
            .map(|(id, name, kind, color, is_default)| CategoryResponse {
                id,
                name,
                kind,
                color,
                is_default,
            })
            .collect(),
    ))
}

pub async fn create(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Json(req): Json<NewCategory>,
) -> AppResult<(StatusCode, Json<CategoryResponse>)> {
    let nome = req.name.trim();
    if nome.is_empty() || nome.len() > 60 {
        return Err(AppError::Validation(
            "nome deve ter entre 1 e 60 caracteres".into(),
        ));
    }

    let kind = CategoryKind::from_str(req.kind.trim())
        .ok_or_else(|| AppError::Validation("kind deve ser INCOME ou EXPENSE".into()))?;

    // A cor é validada por CHECK no banco; aqui só rejeita o óbvio para dar
    // 422 em vez de 500.
    if let Some(c) = req.color.as_deref()
        && !(c.starts_with('#') && c.len() == 7 && c[1..].chars().all(|x| x.is_ascii_hexdigit()))
    {
        return Err(AppError::Validation("cor deve ser #RRGGBB".into()));
    }

    let linha = sqlx::query_as::<_, (Uuid, String, String, Option<String>, bool)>(
        "INSERT INTO categories (user_id, name, kind, color)
         VALUES ($1, $2, $3, $4)
         ON CONFLICT (user_id, kind, lower(btrim(name))) DO UPDATE
            SET color = COALESCE(EXCLUDED.color, categories.color)
         RETURNING id, name, kind, color, is_default",
    )
    .bind(auth.user_id())
    .bind(nome)
    .bind(kind.as_str())
    .bind(req.color.as_deref())
    .fetch_one(&state.db)
    .await?;

    Ok((StatusCode::CREATED, Json(convert(linha))))
}

pub async fn update(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
    Json(req): Json<UpdateCategory>,
) -> AppResult<Json<CategoryResponse>> {
    let linha = sqlx::query_as::<_, (Uuid, String, String, Option<String>, bool)>(
        "UPDATE categories
            SET name = COALESCE($3, name), color = COALESCE($4, color)
          WHERE id = $1 AND user_id = $2
        RETURNING id, name, kind, color, is_default",
    )
    .bind(id)
    .bind(auth.user_id())
    .bind(req.name.as_deref().map(str::trim))
    .bind(req.color.as_deref())
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NotFound("categoria"))?;

    Ok(Json(convert(linha)))
}

pub async fn delete(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Path(id): Path<Uuid>,
) -> AppResult<()> {
    // Transações que usavam a categoria ficam com category_id NULL (ON DELETE
    // SET NULL): apagar a categoria não pode apagar o histórico financeiro.
    let rows = sqlx::query("DELETE FROM categories WHERE id = $1 AND user_id = $2")
        .bind(id)
        .bind(auth.user_id())
        .execute(&state.db)
        .await?
        .rows_affected();

    if rows == 0 {
        return Err(AppError::NotFound("categoria"));
    }
    Ok(())
}

/// PUT /categories/reorder — o app manda a ordem de exibição.
pub async fn reorder(
    State(state): State<AppState>,
    auth: crate::auth::AuthUser,
    Json(ids): Json<Vec<Uuid>>,
) -> AppResult<Json<Vec<CategoryResponse>>> {
    if ids.is_empty() || ids.len() > 100 {
        return Err(AppError::Validation("liste entre 1 e 100 categorias".into()));
    }

    // Só mexe nas categorias do próprio usuário: o UPDATE filtra por user_id,
    // então um id alheio na lista simplesmente não muda.
    let mut tx = state.db.begin().await?;
    for (posicao, id) in ids.iter().enumerate() {
        sqlx::query("UPDATE categories SET display_order = $3 WHERE id = $1 AND user_id = $2")
            .bind(id)
            .bind(auth.user_id())
            .bind(posicao as i32)
            .execute(&mut *tx)
            .await?;
    }
    tx.commit().await?;

    let linhas = sqlx::query_as::<_, (Uuid, String, String, Option<String>, bool)>(
        "SELECT id, name, kind, color, is_default
           FROM categories WHERE user_id = $1
          ORDER BY display_order, lower(name)",
    )
    .bind(auth.user_id())
    .fetch_all(&state.db)
    .await?;

    Ok(Json(linhas.into_iter().map(convert).collect()))
}

fn convert(l: (Uuid, String, String, Option<String>, bool)) -> CategoryResponse {
    CategoryResponse {
        id: l.0,
        name: l.1,
        kind: l.2,
        color: l.3,
        is_default: l.4,
    }
}

// Reexporta para o router montar a rota sem conhecer o módulo de domínio.
pub use categories::CategoryKind as Kind;

/// Nomes padrão disponíveis, para o app mostrar o que foi semeado.
pub fn padroes() -> Vec<(&'static str, &'static str)> {
    categories::DEFAULTS
        .iter()
        .map(|(k, n, _)| (k.as_str(), *n))
        .collect()
}