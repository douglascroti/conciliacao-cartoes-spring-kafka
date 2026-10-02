-- Transações autorizadas pelo emissor: o "outro lado" da conciliação. Cada linha do arquivo da
-- adquirente é confrontada com esta tabela (na etapa 4, um gerador cria a massa de dados).
CREATE TABLE transacao_autorizada (
    id                 BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nsu                VARCHAR(12)   NOT NULL,
    codigo_autorizacao VARCHAR(12)   NOT NULL,
    data_transacao     TIMESTAMP     NOT NULL,
    valor              NUMERIC(15,2) NOT NULL,
    pan_mascarado      VARCHAR(19)   NOT NULL,
    mcc                VARCHAR(4)    NOT NULL,
    parcelas           SMALLINT      NOT NULL,
    criado_em          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Chave de negócio da conciliação. O índice também atende a busca por chunk (nsu = ANY(...)),
    -- porque nsu é a primeira coluna.
    CONSTRAINT uk_transacao_autorizada_nsu_codigo UNIQUE (nsu, codigo_autorizacao)
);

-- Step inverso: autorizações do dia que não vieram no arquivo.
CREATE INDEX ix_transacao_autorizada_data ON transacao_autorizada (data_transacao);

COMMENT ON TABLE transacao_autorizada IS 'Transações aprovadas pelo emissor, confrontadas com o arquivo da adquirente';

-- Resultado da conciliação: uma linha por linha do arquivo, mais uma por autorização ausente.
-- Sem PAN: o resultado identifica a transação pelo NSU e pelo código de autorização.
CREATE TABLE resultado_conciliacao (
    id                      BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_arquivo              UUID          NOT NULL,
    numero_linha            INTEGER,                 -- nulo em AUSENTE_NO_ARQUIVO
    nsu                     VARCHAR(12)   NOT NULL,
    codigo_autorizacao      VARCHAR(12)   NOT NULL,
    status                  VARCHAR(20)   NOT NULL,
    campos_divergentes      VARCHAR(100),            -- ex.: VALOR,PARCELAS (só em DIVERGENTE)
    valor_arquivo           NUMERIC(15,2),           -- nulo em AUSENTE_NO_ARQUIVO
    valor_autorizado        NUMERIC(15,2),           -- nulo em NAO_ENCONTRADA
    id_transacao_autorizada BIGINT        REFERENCES transacao_autorizada (id),
    processado_em           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_resultado_conciliacao_status
        CHECK (status IN ('CONCILIADA', 'DIVERGENTE', 'NAO_ENCONTRADA', 'AUSENTE_NO_ARQUIVO')),
    -- Uma linha do arquivo gera um único resultado (protege contra gravação dupla num restart).
    CONSTRAINT uk_resultado_conciliacao_linha UNIQUE (id_arquivo, numero_linha)
);

-- Consultas por arquivo e o NOT EXISTS do step inverso.
CREATE INDEX ix_resultado_conciliacao_arquivo_chave
    ON resultado_conciliacao (id_arquivo, nsu, codigo_autorizacao);

COMMENT ON TABLE resultado_conciliacao IS 'Resultado da conciliação de cada linha do arquivo e das autorizações ausentes nele';
