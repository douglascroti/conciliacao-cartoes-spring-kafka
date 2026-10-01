-- Registro dos arquivos de conciliação recebidos. A UNIQUE (nome_arquivo, etag) é a base da
-- idempotência da Lambda: o mesmo arquivo (mesmo nome e mesmo conteúdo) só é aceito uma vez.
CREATE TABLE arquivo_recebido (
    id              UUID          PRIMARY KEY DEFAULT uuidv7(),  -- UUID ordenável por tempo (Postgres 18+)
    bucket          VARCHAR(63)   NOT NULL,
    chave           VARCHAR(1024) NOT NULL,
    nome_arquivo    VARCHAR(255)  NOT NULL,
    etag            VARCHAR(64)   NOT NULL,
    tamanho_bytes   BIGINT        NOT NULL,
    data_referencia DATE          NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'RECEBIDO',
    recebido_em     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_arquivo_recebido_nome_etag UNIQUE (nome_arquivo, etag)
);

COMMENT ON TABLE arquivo_recebido IS 'Arquivos de conciliação recebidos via S3 (idempotência por nome + ETag)';
COMMENT ON COLUMN arquivo_recebido.status IS 'RECEBIDO (Lambda); os próximos status são definidos pelo job Batch';
