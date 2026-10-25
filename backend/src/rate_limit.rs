//! Trava de tentativas de login, em memória.
//!
//! Sem isso, um script bate senhas a Argon2 de cadastro sem freio: a única
//! lentidão é o hash local. Chave é (IP, email): um IP não trava a conta de
//! outra pessoa, e um email não trava o resto do IP.

use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Falhas permitidas dentro da janela antes do bloqueio.
const MAX_TENTATIVAS: u32 = 5;
/// Janela que conta falhas consecutivas.
const JANELA: Duration = Duration::from_secs(15 * 60);
/// Cada falha além da quinta alonga o bloqueio; teto de 1 hora.
const BLOQUEIO_MAX: Duration = Duration::from_secs(60 * 60);

#[derive(Default, Clone)]
pub struct LoginGate {
    tentativas: Arc<Mutex<HashMap<(IpAddr, String), Entrada>>>,
}

struct Entrada {
    falhas: u32,
    primeira: Instant,
    /// `Some(t)` enquanto o login de chave estiver recusado.
    ate: Option<Instant>,
}

impl LoginGate {
    /// Consulta antes de tentar o login. `Err(espera)` devolve quanto tempo
    /// falta para liberar (o handler vira 429).
    pub fn aceita(&self, ip: IpAddr, email: &str) -> Result<(), Duration> {
        let mut todas = self.lock();
        let chave = (ip, email.to_lowercase());
        let agora = Instant::now();
        let entrada = todas.entry(chave).or_insert(Entrada {
            falhas: 0,
            primeira: agora,
            ate: None,
        });

        // Janela expirada: a contagem recomeça.
        if agora.duration_since(entrada.primeira) > JANELA {
            entrada.falhas = 0;
            entrada.primeira = agora;
            entrada.ate = None;
        }
        match entrada.ate {
            Some(ate) if ate > agora => Err(ate.duration_since(agora)),
            _ => Ok(()),
        }
    }

    /// Login falhou: conta e, a partir da quinta falha, bloqueia com espera
    /// crescente (60s por falha excedente, teto de 1h), ancorada no agora.
    pub fn registrar_falha(&self, ip: IpAddr, email: &str) {
        let mut todas = self.lock();
        let chave = (ip, email.to_lowercase());
        let agora = Instant::now();
        let entrada = todas.entry(chave).or_insert(Entrada {
            falhas: 0,
            primeira: agora,
            ate: None,
        });
        if agora.duration_since(entrada.primeira) > JANELA {
            entrada.falhas = 0;
            entrada.primeira = agora;
            entrada.ate = None;
        }
        entrada.falhas += 1;
        if entrada.falhas >= MAX_TENTATIVAS {
            let extra = (entrada.falhas - MAX_TENTATIVAS + 1) as u64 * 60;
            let espera = Duration::from_secs(extra).min(BLOQUEIO_MAX);
            entrada.ate = Some(agora + espera);
        }
    }

    /// Login bem-sucedido zera a contagem da chave.
    pub fn registrar_sucesso(&self, ip: IpAddr, email: &str) {
        self.lock().remove(&(ip, email.to_lowercase()));
    }

    /// Um Mutex envenenado aqui significa bug de pânico em outro handler;
    /// recuperar a contagem destravada é melhor que deixar falha de login global.
    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<(IpAddr, String), Entrada>> {
        self.tentativas.lock().unwrap_or_else(|poisoned| {
            tracing::warn!("login gate envenenado; estado de tentativas recuperado");
            poisoned.into_inner()
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ip() -> IpAddr {
        "10.0.0.1".parse().unwrap()
    }

    #[test]
    fn bloqueia_apos_cinco_falhas_e_libera_com_sucesso() {
        let gate = LoginGate::default();
        for _ in 0..4 {
            gate.registrar_falha(ip(), "a@b.c");
            assert!(gate.aceita(ip(), "a@b.c").is_ok());
        }
        gate.registrar_falha(ip(), "a@b.c");
        assert!(gate.aceita(ip(), "a@b.c").is_err());

        // Outra chave não herda o bloqueio.
        assert!(gate.aceita(ip(), "outro@b.c").is_ok());

        gate.registrar_sucesso(ip(), "a@b.c");
        assert!(gate.aceita(ip(), "a@b.c").is_ok());
    }

    #[test]
    fn emails_em_caixa_diferente_sao_a_mesma_chave() {
        let gate = LoginGate::default();
        for _ in 0..5 {
            gate.registrar_falha(ip(), "UsUaRio@Exemplo.com");
        }
        assert!(gate.aceita(ip(), "usuario@exemplo.com").is_err());
    }
}
