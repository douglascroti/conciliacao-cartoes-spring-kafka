-- Linhas do arquivo puladas por erro de layout (também publicadas em conciliacao.erro).
-- A chave (id_arquivo, numero_linha) torna a contagem exata mesmo com restart: o chunk desfeito
-- numa falha é relido e suas linhas inválidas são registradas de novo, sem duplicar.
-- Sem o conteúdo da linha (tem o PAN mascarado): só o número e o motivo.
CREATE TABLE linha_invalida (
    id_arquivo    UUID          NOT NULL,
    numero_linha  INTEGER       NOT NULL,
    motivo        VARCHAR(300)  NOT NULL,
    registrada_em TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_linha_invalida PRIMARY KEY (id_arquivo, numero_linha)
);

COMMENT ON TABLE linha_invalida IS 'Linhas puladas do arquivo da adquirente, com o motivo (sem o conteúdo)';
