# Envia um arquivo da adquirente para s3://conciliacao/entrada/, disparando a Lambda.
# Copia para o container antes: passar dezenas de MB pela entrada padrao do PowerShell e lento.
# Uso: .\scripts\enviar-arquivo.ps1 massa\conciliacao_20261015.csv
param(
    [Parameter(Mandatory)][string]$Arquivo
)
$ErrorActionPreference = 'Stop'

$nome = Split-Path $Arquivo -Leaf
docker cp $Arquivo "localstack:/tmp/$nome"
if ($LASTEXITCODE -ne 0) { throw "Falha ao copiar $Arquivo para o container" }
docker exec localstack awslocal s3 cp "/tmp/$nome" "s3://conciliacao/entrada/$nome" --only-show-errors
if ($LASTEXITCODE -ne 0) { throw "Falha no upload" }
docker exec localstack rm -f "/tmp/$nome"
Write-Host ("Enviado s3://conciliacao/entrada/{0} as {1:HH:mm:ss}" -f $nome, (Get-Date))
