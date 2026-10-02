-- Autorizações de teste para conciliar com infra/exemplos/conciliacao_20261001.csv.
-- Cobrem todos os status:
--   000123456  igual ao arquivo (horário 2 s antes)  -> CONCILIADA
--   000123457  valor 98,00 (arquivo: 89,00)          -> DIVERGENTE (VALOR)
--   000123458  12 parcelas (arquivo: 10)             -> DIVERGENTE (PARCELAS)
--   000123459  sem autorização                       -> NAO_ENCONTRADA
--   000123460  autorizada em 01/10, fora do arquivo  -> AUSENTE_NO_ARQUIVO
--   000123461  autorizada em 02/10                   -> ignorada (outro dia)
-- Uso: Get-Content infra\exemplos\transacoes_autorizadas_20261001.sql | docker exec -i postgres psql -U conciliacao -d conciliacao
INSERT INTO transacao_autorizada (nsu, codigo_autorizacao, data_transacao, valor, pan_mascarado, mcc, parcelas) VALUES
    ('000123456', 'A1B2C3', '2026-10-01 14:32:08',  150.90, '411111******1111', '5411', 1),
    ('000123457', 'D4E5F6', '2026-10-01 15:01:44',   98.00, '550000******0004', '5812', 1),
    ('000123458', 'G7H8I9', '2026-10-01 16:20:05', 1299.99, '401200******1881', '5732', 12),
    ('000123460', 'M4N5O6', '2026-10-01 20:10:00',   45.00, '411111******1111', '5411', 1),
    ('000123461', 'P7Q8R9', '2026-10-02 09:00:00',   60.00, '550000******0004', '5812', 1)
ON CONFLICT (nsu, codigo_autorizacao) DO NOTHING;
