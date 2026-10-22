# Build multi-stage: compila com a toolchain Rust e leva só o binário para a
# imagem final. Render roda este Dockerfile; Neon entra pela DATABASE_URL.
FROM rust:1.88-bookworm AS builder

WORKDIR /app

# Cargo.lock garante build reprodutível. Migrations entram no binário via
# sqlx::migrate! (embutidas em tempo de compilação).
COPY Cargo.toml Cargo.lock ./
COPY migrations ./migrations
COPY src ./src

RUN cargo build --release --locked

FROM debian:bookworm-slim

# rustls não usa OpenSSL, mas reqwest precisa de certificados para falar com
# OpenRouter/brapi/Yahoo. Nada de toolchain na imagem final.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --uid 10001 --no-create-home app

WORKDIR /app
COPY --from=builder /app/target/release/kavelipay-backend /usr/local/bin/kavelipay-backend

USER app
EXPOSE 3000

# Sem BIND_ADDR fixo: no Render a porta vem em PORT; localmente cai em 3000.
CMD ["kavelipay-backend"]
