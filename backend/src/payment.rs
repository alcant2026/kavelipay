//! Providers de pagamento — a abstração que a spec exige antes de qualquer
//! integração real.
//!
//! **O KaveliPay não é provedor de PIX nem processador de pagamentos.** Essa
//! fronteira éLevada a sério aqui, e não é uma Questão de documentação:
//!
//! - `DemoPaymentProvider` gera um deep link do próprio app
//!   (`kavelipay://cobranca/<token>`). NÃO gera payload BR Code / EMV.
//! - O pagamento só é registrado por ação explícita do usuário. Nada é
//!   confirmado por webhook, polling de terceiro ou status que o app se
//!   auto-atribua.
//!
//! Um `PixProvider` real só entra com fornecedor legítimo e regulado, e não
//! altera regra de negócio nenhuma: a regra já mora no ledger, não no provider.

use serde::{Deserialize, Serialize};

/// Payload que o app exibe como QR Code.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct PaymentPayload {
    /// Conteúdo a codificar no QR.
    pub payload: String,
    /// Formato do conteúdo. `DEEP_LINK` aqui; nunca `BR_CODE` sem provider real.
    pub format: PayloadFormat,
    /// Qual provider gerou. Aparece na resposta para o app não Assume que
    /// qualquer QR é pagável por qualquer app.
    pub provider: &'static str,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PayloadFormat {
    /// Link para o próprio app resolver. É o formato do MVP.
    DeepLink,
    /// QR de uso único.
    OneTimeQr,
}

/// Contrato de um meio de pagamento. Provider real implementa isto sem tocar
/// nas regras de negócio.
pub trait PaymentProvider: Send + Sync {
    /// Identificador do provider, para log e para o app decidir o que exibir.
    fn name(&self) -> &'static str;

    /// Gera o conteúdo do QR Code de uma cobrança.
    ///
    /// Recebe o **token de compartilhamento**, não o id: o QR é público e UUID
    /// não é segredo (`migrations/0010`). O valor também não entra no payload —
    /// quem recebe lê a cobrança no servidor, que é a fonte da verdade; um
    /// `?valor=` no link seria um número que o app de fora teria que acreditar.
    fn qr_payload(&self, share_token: &str) -> PaymentPayload;

    /// Este provider movimenta dinheiro de verdade?
    ///
    /// `false` significa que nenhuma confirmação pode ser automática: só o
    /// usuário declara que pagou. O código de negócio usa isto para decidir se
    /// pode marcar algo como pago sozinho.
    fn moves_real_money(&self) -> bool {
        false
    }
}

/// Provider de demonstração: QR que abre o app. Não é PIX, não processa nada.
pub struct DemoPaymentProvider;

impl PaymentProvider for DemoPaymentProvider {
    fn name(&self) -> &'static str {
        "demo"
    }

    fn qr_payload(&self, share_token: &str) -> PaymentPayload {
        // Deliberadamente NÃO é BR Code/EMV. Um payload EMV (começando em
        // "00020126BR.GOV.BCB.PIX...") faria o KaveliPay parecer provedor de
        // PIX — exatamente o que a spec proíbe. Aqui o QR só identifica a
        // cobrança dentro do app, e o token é o único dado que ele carrega.
        PaymentPayload {
            payload: format!("kavelipay://cobranca/{share_token}"),
            format: PayloadFormat::DeepLink,
            provider: "demo",
        }
    }
}

impl PaymentPayload {
    /// O payload é um BR Code/EMV do PIX?
    ///
    /// Existe como método (e é testado) para deixar a regra explícita: se um
    /// provider real for plugado e gerar EMV, o app precisa poder avisar que
    /// aquilo deixou de ser um QR do KaveliPay. Um teste garante que o provider
    /// demo NUNCA retorna true.
    pub fn is_br_code(&self) -> bool {
        self.payload.starts_with("0002")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TOKEN: &str = "0123456789abcdef0123456789abcdef";

    #[test]
    fn demo_provider_gera_deep_link_com_token() {
        let p = DemoPaymentProvider.qr_payload(TOKEN);

        assert_eq!(p.payload, format!("kavelipay://cobranca/{TOKEN}"));
        assert_eq!(p.format, PayloadFormat::DeepLink);
        assert_eq!(p.provider, "demo");
    }

    #[test]
    fn payload_nao_carrega_valor() {
        // O valor tem que vir do servidor (`GET /cobranca/{token}`). Um
        // `?valor=` no link seria um número que o app de quem recebe teria
        // que acreditar sem conferir — e é justamente o app de fora que não
        // pode ser fonte da verdade.
        let p = DemoPaymentProvider.qr_payload(TOKEN);
        assert!(!p.payload.contains("valor="), "payload carrega valor: {}", p.payload);
        assert!(!p.payload.contains('?'), "payload carrega query: {}", p.payload);
    }

    #[test]
    fn demo_provider_nunca_gera_br_code() {
        // Este é o teste da fronteira "não somos provedor de PIX". Payload
        // EMV/BR Code do PIX começa com "0002" e carrega BR.GOV.BCB.PIX.
        let p = DemoPaymentProvider.qr_payload(TOKEN);
        assert!(!p.is_br_code(), "payload do demo virou BR Code: {}", p.payload);
        assert!(!p.payload.contains("BR.GOV.BCB.PIX"));
    }

    #[test]
    fn demo_provider_nao_move_dinheiro_real() {
        // Se isto virar true, o código que marca pagamento automático teria
        // queSumir: sem fonte confiável, PAID é proibido.
        assert!(!DemoPaymentProvider.moves_real_money());
    }

    #[test]
    fn provider_real_tera_de_mudar_so_o_payload() {
        // Um PixProvider mudaria `payload` e `format`, nunca o resto. Este
        // teste documenta a fronteira da abstração.
        let demo = DemoPaymentProvider.qr_payload(TOKEN);
        assert_eq!(demo.format, PayloadFormat::DeepLink);
        assert_eq!(demo.provider, "demo");
        assert!(!demo.is_br_code());
    }
}