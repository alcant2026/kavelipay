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

/// Freio de pedidos de redefinição de senha, por IP.
///
/// Sem isso o endpoint vira remetente de spam: cada POST com um e-mail
/// diferente dispara um envio real, e o custo (reputação do domínio) é seu.
/// Cinco por hora cobre quem errou o próprio e-mail e não serve a um atacante.
#[derive(Default, Clone)]
pub struct Cooldown {
    janelas: Arc<Mutex<HashMap<IpAddr, (Instant, u32)>>>,
}

impl Cooldown {
    pub const MAX: u32 = 5;
    pub const JANELA: Duration = Duration::from_secs(60 * 60);

    /// `true` se o pedido pode seguir — e já conta como um.
    pub fn permite(&self, ip: IpAddr) -> bool {
        let mut mapa = self.lock();
        let agora = Instant::now();
        // Sem poda, um mês de IPs distintos vira um mapa que nunca encolhe.
        if mapa.len() > 10_000 {
            mapa.retain(|_, (inicio, _)| agora.duration_since(*inicio) <= Self::JANELA);
        }
        let entrada = mapa.entry(ip).or_insert((agora, 0));
        if agora.duration_since(entrada.0) > Self::JANELA {
            *entrada = (agora, 0);
        }
        entrada.1 += 1;
        entrada.1 <= Self::MAX
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<IpAddr, (Instant, u32)>> {
        self.janelas
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

/// Freio global por IP: teto de requisições por minuto em toda a API.
///
/// Diferente do `LoginGate` (que protege uma conta), este existe para a
/// instância não cair inteira: no plano free são 0,1 vCPU, e um script em
/// paralelo derruba o app de todo mundo. Por IP, não por conta — quem abusa
/// normalmente nem tem conta.
#[derive(Default, Clone)]
pub struct LimiteGlobal {
    /// `0` desliga (só para teste local).
    pub max: u32,
    janelas: Arc<Mutex<HashMap<IpAddr, Janela>>>,
}

struct Janela {
    inicio: Instant,
    contagem: u32,
}

impl LimiteGlobal {
    pub const DURACAO: Duration = Duration::from_secs(60);

    pub fn new(max: u32) -> Self {
        Self {
            max,
            janelas: Arc::default(),
        }
    }

    /// `true` se a requisição pode seguir — e já conta como uma.
    pub fn permite(&self, ip: IpAddr) -> bool {
        if self.max == 0 {
            return true;
        }
        let mut mapa = self.janelas.lock().unwrap_or_else(|p| p.into_inner());
        let agora = Instant::now();
        // Poda: sem isso um mês de IPs distintos nunca encolhe.
        if mapa.len() > 10_000 {
            mapa.retain(|_, j| agora.duration_since(j.inicio) <= Self::DURACAO);
        }
        let janela = mapa.entry(ip).or_insert(Janela {
            inicio: agora,
            contagem: 0,
        });
        if agora.duration_since(janela.inicio) > Self::DURACAO {
            janela.inicio = agora;
            janela.contagem = 0;
        }
        janela.contagem += 1;
        janela.contagem <= self.max
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn limite_global_corta_no_maximo_e_libera_outro_ip() {
        let limite = LimiteGlobal::new(3);
        for _ in 0..3 {
            assert!(limite.permite(ip()));
        }
        assert!(!limite.permite(ip()));
        assert!(limite.permite("10.0.0.2".parse().unwrap()));
    }

    #[test]
    fn limite_global_com_zero_nao_bloqueia() {
        let limite = LimiteGlobal::new(0);
        for _ in 0..1000 {
            assert!(limite.permite(ip()));
        }
    }

    fn ip() -> IpAddr {
        "10.0.0.1".parse().unwrap()
    }

    #[test]
    fn cooldown_corta_no_limite_e_nao_afeta_outro_ip() {
        let cooldown = Cooldown::default();
        for _ in 0..Cooldown::MAX {
            assert!(cooldown.permite(ip()));
        }
        assert!(!cooldown.permite(ip()));
        assert!(cooldown.permite("10.0.0.2".parse().unwrap()));
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
