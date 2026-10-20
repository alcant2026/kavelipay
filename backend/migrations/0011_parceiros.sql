-- Vitrine de lojas parceiras (indicação de afiliado).
--
-- O KaveliPay NÃO vende, NÃO faz checkout e NÃO processa pagamento: o card é
-- uma indicação, a compra inteira acontece no site da loja, dentro de uma
-- WebView do próprio app. NENHUMA linha desta tabela toca o ledger — não há
-- preço, não há pagamento, não há recebível aqui. É conteúdo, não dinheiro.
--
-- SEM PREÇO, de propósito. O Mercado Livre bloqueia leitura automática: a API
-- pública (api.mercadolibre.com) devolve 403 para acesso anônimo e a página de
-- produto responde bot-blocked. Preço, então, só existiria digitado à mão — e
-- preço digitado à mão envelhece calado. Num app de dinheiro, número
-- desatualizado é pior que número ausente: o card diz "ver preço na loja" e a
-- verdade fica sempre no destino, onde ela não pode ficar velha.
--
-- Título, imagem e URL são CURADOS no seed (extraídos da vitrine de afiliado
-- do próprio dono). Nada é raspado em runtime: a vitrine é estática até
-- alguém rodar uma migration nova. Raspar a página a cada abertura seria
-- frágil (quebra a cada mudança de layout), lento e desrespeitoso com o site.
--
-- `destino_url` carrega os parâmetros de rastreio que o Mercado Livre gerou
-- (matt_tool_id etc.). Reescrever esses parâmetros à mão seria adulterar a
-- atribuição de comissão — então eles são copiados como vieram.

CREATE TABLE parceiros_ofertas (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Loja de destino. Texto livre, mas curto: é rótulo de card, não
    -- cadastro de lojista (o app é de pessoa física, não tem painel de loja).
    loja        TEXT        NOT NULL,
    titulo      TEXT        NOT NULL,
    imagem_url  TEXT        NOT NULL,
    destino_url TEXT        NOT NULL,
    ordem       INTEGER     NOT NULL DEFAULT 0,
    ativo       BOOLEAN     NOT NULL DEFAULT TRUE,
    criado_em   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT parceiros_ofertas_loja_ok CHECK (char_length(loja) BETWEEN 1 AND 40),
    -- 120 é o que cabe em duas linhas de card sem virar parede de texto.
    CONSTRAINT parceiros_ofertas_titulo_ok CHECK (char_length(titulo) BETWEEN 1 AND 120),
    -- Só https, dos dois lados. A WebView carrega página de terceiro: http puro
    -- fica fora (mixed content nunca), e é o CHECK que impede alguém de
    -- cadastrar `javascript:` ou `intent://` e transformar o card num vetor.
    CONSTRAINT parceiros_ofertas_imagem_https CHECK (imagem_url LIKE 'https://%'),
    CONSTRAINT parceiros_ofertas_destino_https CHECK (destino_url LIKE 'https://%')
);

-- A lista é sempre "ativas, na ordem". Índice parcial porque oferta desativada
-- não é lida por ninguém.
CREATE INDEX parceiros_ofertas_ativas
    ON parceiros_ofertas (ordem, criado_em)
    WHERE ativo;

INSERT INTO parceiros_ofertas (loja, titulo, imagem_url, destino_url, ordem) VALUES
    ('Mercado Livre',
     'O Boticário Malbec Club — creme hidratante facial',
     'https://http2.mlstatic.com/D_Q_NP_2X_841435-MLA116515771472_092026-T.webp',
     'https://www.mercadolivre.com.br/o-boticario-malbec-club-creme-hidratante-facial-anti-idade-5/p/MLB28279015?matt_event_ts=1792466022379&matt_d2id=64aa393d-dd6f-45ab-9894-eff2fd06d52b&matt_tracing_id=399ee839-d2d5-422e-a1f7-ec58b446709c#polycard_client=recommendations_home_affiliate_profile_v2p-recommendations&reco_backend=ranker_retrieval_system_vpp_v2p&reco_model=fallback_organicos_deduplication&reco_client=home_affiliate_profile_v2p-recommendations&matt_tool_id=41444935&reco_item_pos=0&source=affiliate-profile&reco_backend_type=low_level&reco_id=21bc452b-b55f-4a6a-9cf6-f667e09622e1&tracking_id=aa889710-b880-4830-95b4-42e97b137f5c&wid=MLB4093062169&sid=recos&c_id=/home/affiliate-profile-recommendations/element&c_uid=f2357d8b-1dfd-4095-88d8-e9825bb504b9',
     0),
    ('Mercado Livre',
     'Medicube Zero Pore Pad 2.0 — 70 pads',
     'https://http2.mlstatic.com/D_Q_NP_2X_945395-CBT80858682164_122024-T.webp',
     'https://www.mercadolivre.com.br/medicube-zero-pore-pad-20-70-pads-para-esfoliacao-e-cuidado-com-os-poros/p/MLB42592335?matt_event_ts=1792466022379&matt_d2id=64aa393d-dd6f-45ab-9894-eff2fd06d52b&matt_tracing_id=399ee839-d2d5-422e-a1f7-ec58b446709c#polycard_client=recommendations_home_affiliate_profile_v2p-recommendations&reco_backend=ranker_retrieval_system_vpp_v2p&reco_model=fallback_organicos_deduplication&reco_client=home_affiliate_profile_v2p-recommendations&matt_tool_id=41444935&reco_item_pos=1&source=affiliate-profile&reco_backend_type=low_level&reco_id=21bc452b-b55f-4a6a-9cf6-f667e09622e1&tracking_id=aa889710-b880-4830-95b4-42e97b137f5c&wid=MLB7511176420&sid=recos&c_id=/home/affiliate-profile-recommendations/element&c_uid=40724662-1cc0-4fc5-9c8f-9f84b4a3a3e5',
     1),
    ('Mercado Livre',
     'Principia — kit cremes hidratantes CH-01 + CH-02',
     'https://http2.mlstatic.com/D_Q_NP_2X_636403-MLA99022118074_112025-T.webp',
     'https://www.mercadolivre.com.br/principia-kit-cremes-hidratantes-rosto-e-corpo-ch-01-ch-02-seca-dianoite/p/MLB67209218?matt_event_ts=1792466022379&matt_d2id=64aa393d-dd6f-45ab-9894-eff2fd06d52b&matt_tracing_id=399ee839-d2d5-422e-a1f7-ec58b446709c#polycard_client=recommendations_home_affiliate_profile_v2p-recommendations&reco_backend=ranker_retrieval_system_vpp_v2p&reco_model=fallback_organicos_deduplication&reco_client=home_affiliate_profile_v2p-recommendations&matt_tool_id=41444935&reco_item_pos=6&source=affiliate-profile&reco_backend_type=low_level&reco_id=21bc452b-b55f-4a6a-9cf6-f667e09622e1&tracking_id=aa889710-b880-4830-95b4-42e97b137f5c&wid=MLB6530934370&sid=recos&c_id=/home/affiliate-profile-recommendations/element&c_uid=6412bbda-a937-4590-a7e9-e0ecaa0286b1',
     2),
    ('Mercado Livre',
     'Principia — kit GL-02 + GH-01 + PS-01',
     'https://http2.mlstatic.com/D_Q_NP_2X_792011-MLA96077651979_102025-T.webp',
     'https://www.mercadolivre.com.br/principia-kit-gl-02-gh-01-ps-01-todo-tipo-de-pele-dianoite/p/MLB52069535?matt_event_ts=1792466022379&matt_d2id=64aa393d-dd6f-45ab-9894-eff2fd06d52b&matt_tracing_id=399ee839-d2d5-422e-a1f7-ec58b446709c#polycard_client=recommendations_home_affiliate_profile_v2p-recommendations&reco_backend=ranker_retrieval_system_vpp_v2p&reco_model=fallback_organicos_deduplication&reco_client=home_affiliate_profile_v2p-recommendations&matt_tool_id=41444935&reco_item_pos=7&source=affiliate-profile&reco_backend_type=low_level&reco_id=21bc452b-b55f-4a6a-9cf6-f667e09622e1&tracking_id=aa889710-b880-4830-95b4-42e97b137f5c&wid=MLB4116433667&sid=recos&c_id=/home/affiliate-profile-recommendations/element&c_uid=8521dc8d-f2a2-4681-8f08-98c9e2822351',
     3),
    ('Mercado Livre',
     'Bermuda cargo masculina skate larga elástico',
     'https://http2.mlstatic.com/D_Q_NP_2X_725759-MLB113384426120_072026-T-kit-2-bermuda-cargo-mauricinho-brim-sarja-bolso-lateral.webp',
     'https://produto.mercadolivre.com.br/MLB-4663885243-bermuda-cargo-masculina-skate-larga-elastico-short-adulto-_JM?searchVariation=194332328092&matt_event_ts=1792466085227&matt_d2id=c5810455-9ada-4817-a8af-d57ce6984f6f&matt_tracing_id=d3e1398d-b159-41f4-beaf-19c5c5885e7a#polycard_client=recommendations_home_affiliate_profile_v2p-recommendations&reco_backend=ranker_retrieval_system_vpp_v2p&reco_model=fallback_organicos_deduplication&reco_client=home_affiliate_profile_v2p-recommendations&matt_tool_id=41444935&reco_item_pos=7&source=affiliate-profile&reco_backend_type=low_level&reco_id=70ad6a38-11d4-4713-b9d2-8cab89f269d0&tracking_id=5936bf1a-d934-46a1-ba72-7912c16d28dc&sid=recos&c_id=/home/affiliate-profile-recommendations/element&c_uid=42933896-30d8-4b24-93b4-bcce8fe0fa90',
     4),
    ('Mercado Livre',
     'Mesa de escritório 120x60 com duas gavetas',
     'https://http2.mlstatic.com/D_Q_NP_2X_762343-CBT117042136046_102026-T.webp',
     'https://produto.mercadolivre.com.br/MLB-4086338487-mesa-de-escritorio-escrivaninha-120x60-duas-gavetas-c-chave-_JM?searchVariation=188256411291&matt_event_ts=1792466084220&matt_d2id=6e9f851c-cb43-4f50-ab05-692de517e717&matt_tracing_id=967887ed-bb63-4b1e-83e3-539c2faf2968#polycard_client=recommendations_home_affiliate_profile_v2p-recommendations&reco_backend=ranker_retrieval_system_vpp_v2p&reco_model=coldstart_high_exposition&reco_client=home_affiliate_profile_v2p-recommendations&matt_tool_id=41444935&reco_item_pos=1&source=affiliate-profile&reco_backend_type=low_level&reco_id=978b6361-5c88-45bc-bcc5-7331e5143e90&tracking_id=0c66c5d9-1c48-4bf2-a912-8af76f524a89&sid=recos&c_id=/home/affiliate-profile-recommendations/element&c_uid=41d1271e-efb2-476d-9d30-db7c4dbbb1e9',
     5),
    ('Mercado Livre',
     'Mesa escrivaninha 1,50 home office com 2 gavetas',
     'https://http2.mlstatic.com/D_Q_NP_2X_751365-MLB117365484144_102026-T-mesa-de-escritorio-escrivaninha-120x60-duas-gavetas-c-chave.webp',
     'https://produto.mercadolivre.com.br/MLB-5276846294-mesa-escrivaninha-150-escritorio-home-office-com-2-gavetas-_JM?searchVariation=182831922866&matt_event_ts=1792466084220&matt_d2id=6e9f851c-cb43-4f50-ab05-692de517e717&matt_tracing_id=967887ed-bb63-4b1e-83e3-539c2faf2968#polycard_client=recommendations_home_affiliate_profile_v2p-recommendations&reco_backend=ranker_retrieval_system_vpp_v2p&reco_model=coldstart_high_exposition&reco_client=home_affiliate_profile_v2p-recommendations&matt_tool_id=41444935&reco_item_pos=0&source=affiliate-profile&reco_backend_type=low_level&reco_id=978b6361-5c88-45bc-bcc5-7331e5143e90&tracking_id=0c66c5d9-1c48-4bf2-a912-8af76f524a89&sid=recos&c_id=/home/affiliate-profile-recommendations/element&c_uid=bc4abf27-aec3-4a9f-bd02-3cb06ac1e120',
     6);
