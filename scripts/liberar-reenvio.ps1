# SO PARA TESTES: permite reenviar um arquivo ja processado (ex.: repetir uma medicao de desempenho).
# Apaga o registro de idempotencia (Postgres e DynamoDB) e os resultados anteriores do arquivo.
# Em producao, reenviar o mesmo arquivo e descartado como duplicado, que e o comportamento correto.
# Uso: .\scripts\liberar-reenvio.ps1 conciliacao_20261015.csv
param(
    [Parameter(Mandatory)][string]$NomeArquivo
)
$ErrorActionPreference = 'Stop'

$sql = @"
DELETE FROM resultado_conciliacao WHERE id_arquivo IN (SELECT id FROM arquivo_recebido WHERE nome_arquivo = '$NomeArquivo');
DELETE FROM linha_invalida WHERE id_arquivo IN (SELECT id FROM arquivo_recebido WHERE nome_arquivo = '$NomeArquivo');
DELETE FROM arquivo_recebido WHERE nome_arquivo = '$NomeArquivo';
"@
$sql | docker exec -i postgres psql -U conciliacao -d conciliacao -v ON_ERROR_STOP=1 -q
if ($LASTEXITCODE -ne 0) { throw "Falha ao limpar o Postgres" }

# No DynamoDB a chave e nomeArquivo + etag: apaga todas as versoes do arquivo.
$etags = docker exec localstack awslocal dynamodb query --table-name arquivo-recebido `
    --key-condition-expression 'nomeArquivo = :n' `
    --expression-attribute-values "{\`":n\`":{\`"S\`":\`"$NomeArquivo\`"}}" `
    --query 'Items[].etag.S' --output text
foreach ($etag in ($etags -split '\s+' | Where-Object { $_ -and $_ -ne 'None' })) {
    docker exec localstack awslocal dynamodb delete-item --table-name arquivo-recebido `
        --key "{\`"nomeArquivo\`":{\`"S\`":\`"$NomeArquivo\`"},\`"etag\`":{\`"S\`":\`"$etag\`"}}"
}
Write-Host "Reenvio de $NomeArquivo liberado (Postgres e DynamoDB)"
