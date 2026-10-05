# SO PARA DESENVOLVIMENTO: apaga os dados de teste e deixa o ambiente como recem-criado, sem
# recriar os containers (volumes, imagens e migrations continuam).
#
#   .\scripts\limpar-ambiente.ps1                      # so mostra o que seria feito
#   .\scripts\limpar-ambiente.ps1 -Executar            # limpa tudo
#   .\scripts\limpar-ambiente.ps1 -Executar -ManterAutorizacoes -ManterMassa
#
# O que limpa: tabelas do Postgres (resultados, linhas invalidas, arquivos, autorizacoes e
# metadados do Spring Batch; o historico do Flyway fica), tabela do DynamoDB, bucket do S3, topicos
# e consumer group do Kafka, logs da Lambda, log do servico no host e a pasta massa/.
param(
    [switch]$Executar,
    [switch]$ManterAutorizacoes,   # mantem transacao_autorizada (evita recarregar a massa)
    [switch]$ManterMassa           # mantem a pasta massa/ (arquivos gerados pelo gerador-dados)
)
# Continue, e nao Stop: no Windows PowerShell 5.1 o stderr de programas nativos redirecionado vira
# erro fatal com Stop (docker compose escreve o progresso no stderr). Os codigos de saida sao checados.
$ErrorActionPreference = 'Continue'
$raiz = Split-Path -Parent $PSScriptRoot

$tabelas = @('resultado_conciliacao', 'linha_invalida', 'arquivo_recebido',
    'batch_step_execution_context', 'batch_step_execution', 'batch_job_execution_context',
    'batch_job_execution_params', 'batch_job_execution', 'batch_job_instance')
if (-not $ManterAutorizacoes) { $tabelas += 'transacao_autorizada' }
$topicos = @('conciliacao.arquivo-recebido', 'conciliacao.resultado', 'conciliacao.erro')

Write-Host 'Limpeza do ambiente de desenvolvimento:'
Write-Host '  1. Para o servico conciliacao-batch (ninguem grava durante a limpeza)'
Write-Host "  2. Postgres: TRUNCATE $($tabelas -join ', ') e reinicia as sequences do Spring Batch"
Write-Host '  3. DynamoDB: apaga a tabela arquivo-recebido (recriada vazia pelo terraform apply)'
Write-Host '  4. S3: esvazia o bucket conciliacao (entrada/ e rejeitados/)'
Write-Host '  5. Lambda: apaga os logs no CloudWatch do LocalStack e roda o terraform apply (recria tabela e log group)'
Write-Host "  6. Kafka: apaga e recria os topicos ($($topicos -join ', ')) e o consumer group conciliacao-batch"
Write-Host ('  7. Apaga conciliacao-batch\logs' + $(if ($ManterMassa) { '' } else { ' e a pasta massa\' }))
Write-Host '  8. Sobe o conciliacao-batch de novo'
if (-not $Executar) {
    Write-Host "`nNada foi feito. Para executar: .\scripts\limpar-ambiente.ps1 -Executar" -ForegroundColor Yellow
    return
}

function Passo([string]$descricao, [scriptblock]$acao) {
    Write-Host "-> $descricao"
    & $acao
    if ($LASTEXITCODE -ne 0) { throw "Falhou: $descricao (codigo $LASTEXITCODE)" }
}

$cronometro = [Diagnostics.Stopwatch]::StartNew()

Passo 'Parando o conciliacao-batch' { docker compose -f "$raiz\docker-compose.yml" stop conciliacao-batch 2>&1 | Out-Null }

Passo 'Limpando o Postgres' {
    $sql = "TRUNCATE $($tabelas -join ', ') RESTART IDENTITY CASCADE;`n" +
        "ALTER SEQUENCE batch_job_instance_seq RESTART; ALTER SEQUENCE batch_job_execution_seq RESTART; ALTER SEQUENCE batch_step_execution_seq RESTART;"
    $sql | docker exec -i postgres psql -U conciliacao -d conciliacao -v ON_ERROR_STOP=1 -q
}

Passo 'Apagando a tabela do DynamoDB (o Terraform recria vazia no fim)' {
    docker exec localstack awslocal dynamodb delete-table --table-name arquivo-recebido 2>&1 | Out-Null
    docker exec localstack awslocal dynamodb wait table-not-exists --table-name arquivo-recebido
}

Passo 'Esvaziando o bucket do S3' { docker exec localstack awslocal s3 rm s3://conciliacao --recursive --only-show-errors }

Passo 'Apagando os logs da Lambda' {
    docker exec localstack awslocal logs delete-log-group --log-group-name /aws/lambda/receber-arquivo-conciliacao 2>&1 | Out-Null
    $global:LASTEXITCODE = 0   # o grupo de logs pode nao existir (nenhuma invocacao ainda)
}

# Tabela e log group sao recursos do Terraform: o apply recria o que foi apagado acima, vazio.
Passo 'Recriando tabela do DynamoDB e logs da Lambda (terraform apply)' {
    & "$PSScriptRoot\terraform.ps1" apply -auto-approve -no-color | Select-String 'Apply complete'
}

Passo 'Apagando o consumer group e os topicos do Kafka' {
    docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --delete --group conciliacao-batch 2>&1 | Out-Null
    docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic ($topicos -join ',') 2>&1 | Out-Null
    # A exclusao e assincrona: espera os topicos sumirem antes de recria-los.
    $limite = (Get-Date).AddSeconds(60)
    do {
        Start-Sleep -Seconds 1
        $restantes = docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list |
            Where-Object { $topicos -contains $_ }
    } while ($restantes -and (Get-Date) -lt $limite)
    if ($restantes) { throw "Topicos ainda existem: $restantes" }
    $global:LASTEXITCODE = 0
}

Passo 'Recriando os topicos (kafka-init)' {
    docker compose -f "$raiz\docker-compose.yml" up -d --force-recreate --wait kafka-init 2>&1 | Out-Null
}

Passo 'Apagando logs locais e massa gerada' {
    Remove-Item -Recurse -Force "$raiz\conciliacao-batch\logs" -ErrorAction SilentlyContinue
    if (-not $ManterMassa) { Remove-Item -Recurse -Force "$raiz\massa" -ErrorAction SilentlyContinue }
    $global:LASTEXITCODE = 0
}

Passo 'Subindo o conciliacao-batch' { docker compose -f "$raiz\docker-compose.yml" up -d --wait conciliacao-batch 2>&1 | Out-Null }

Write-Host ("Ambiente limpo em {0:N0} s" -f $cronometro.Elapsed.TotalSeconds) -ForegroundColor Green
