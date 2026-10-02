# Carrega no Postgres as transações autorizadas geradas pelo gerador-dados (COPY em massa).
# Uso: .\scripts\carregar-autorizacoes.ps1 massa\transacoes_autorizadas_20261015.csv
param(
    [Parameter(Mandatory)][string]$Arquivo
)
$ErrorActionPreference = 'Stop'

$nome = Split-Path $Arquivo -Leaf
$cronometro = [Diagnostics.Stopwatch]::StartNew()

docker cp $Arquivo "postgres:/tmp/$nome"
if ($LASTEXITCODE -ne 0) { throw "Falha ao copiar $Arquivo para o container" }

# COPY numa tabela temporaria e INSERT ... ON CONFLICT: o COPY direto falharia na chave unica ao
# carregar a mesma massa duas vezes. O ANALYZE atualiza as estatisticas para o planejador de
# consultas usar o indice nas buscas por chunk.
$sql = @"
CREATE TEMP TABLE carga (nsu varchar(12), codigo_autorizacao varchar(12), data_transacao timestamp,
                         valor numeric(15,2), pan_mascarado varchar(19), mcc varchar(4), parcelas smallint);
COPY carga FROM '/tmp/$nome' WITH (FORMAT csv, DELIMITER ';', HEADER true);
INSERT INTO transacao_autorizada (nsu, codigo_autorizacao, data_transacao, valor, pan_mascarado, mcc, parcelas)
SELECT nsu, codigo_autorizacao, data_transacao, valor, pan_mascarado, mcc, parcelas FROM carga
ON CONFLICT (nsu, codigo_autorizacao) DO NOTHING;
ANALYZE transacao_autorizada;
"@
$sql | docker exec -i postgres psql -U conciliacao -d conciliacao -v ON_ERROR_STOP=1 -q
if ($LASTEXITCODE -ne 0) { throw "Falha na carga" }
docker exec postgres rm -f "/tmp/$nome"

$total = docker exec postgres psql -U conciliacao -d conciliacao -tAc "select count(*) from transacao_autorizada"
Write-Host ("Carga de {0} concluida em {1:N1} s; transacao_autorizada tem {2:N0} linhas" -f $nome, $cronometro.Elapsed.TotalSeconds, [long]$total)
