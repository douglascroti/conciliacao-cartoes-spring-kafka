-- Acompanhamento do processamento de cada arquivo, atualizado pelo job Batch.
-- Com IDEMPOTENCIA_PROVEDOR=dynamodb a Lambda não grava aqui; o job cria a linha (upsert por id).
ALTER TABLE arquivo_recebido
    ADD COLUMN linhas_processadas INTEGER,
    ADD COLUMN linhas_invalidas   INTEGER,
    ADD COLUMN mensagem_erro      VARCHAR(1000),
    ADD COLUMN iniciado_em        TIMESTAMPTZ,
    ADD COLUMN finalizado_em      TIMESTAMPTZ,
    ADD CONSTRAINT ck_arquivo_recebido_status
        CHECK (status IN ('RECEBIDO', 'PROCESSANDO', 'CONCLUIDO', 'FALHA'));

COMMENT ON COLUMN arquivo_recebido.status IS
    'RECEBIDO (Lambda) -> PROCESSANDO -> CONCLUIDO ou FALHA (job Batch; FALHA pode voltar a PROCESSANDO num restart)';
COMMENT ON COLUMN arquivo_recebido.linhas_processadas IS 'Linhas do arquivo com resultado gravado';
COMMENT ON COLUMN arquivo_recebido.linhas_invalidas IS 'Linhas puladas por erro de layout (publicadas em conciliacao.erro)';
