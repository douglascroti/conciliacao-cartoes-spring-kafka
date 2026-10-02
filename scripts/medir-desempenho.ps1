# Mede o processamento de um arquivo com um tamanho de chunk: recria o servico com o chunk, reenvia
# o arquivo, amostra memoria e CPU durante o job e confere o resultado com o gabarito.
# Uso: .\scripts\medir-desempenho.ps1 -Chunk 5000 -Data 2026-10-15
# Pre-requisito: massa gerada e autorizacoes carregadas (ver docs/comandos.md, etapa 4).
param(
    [Parameter(Mandatory)][int]$Chunk,
    [string]$Data = '2026-10-15',
    [string]$Pasta = 'massa'
)
# Continue, e nao Stop: no Windows PowerShell 5.1 o stderr de programas nativos redirecionado com 2>&1
# (o docker compose escreve o progresso ali) vira erro fatal com Stop. Os codigos de saida sao checados.
$ErrorActionPreference = 'Continue'

$dia = $Data -replace '-', ''
$nomeArquivo = "conciliacao_$dia.csv"

# Recria o container com o chunk pedido (a variavel do terminal tem prioridade sobre o .env).
$env:JOB_TAMANHO_CHUNK = "$Chunk"
docker compose up -d --wait conciliacao-batch 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Falha ao recriar o conciliacao-batch' }
Remove-Item Env:JOB_TAMANHO_CHUNK
$efetivo = docker exec conciliacao-batch printenv JOB_TAMANHO_CHUNK
if ($efetivo -ne "$Chunk") { throw "O container subiu com chunk $efetivo, nao $Chunk" }

& "$PSScriptRoot\liberar-reenvio.ps1" $nomeArquivo | Out-Null
$inicioLog = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
& "$PSScriptRoot\enviar-arquivo.ps1" "$Pasta\$nomeArquivo" | Out-Null

$memoria = @(); $cpu = @()
$limite = (Get-Date).AddMinutes(20)
while ((Get-Date) -lt $limite -and -not (docker logs --since $inicioLog conciliacao-batch 2>&1 | Select-String "Resumo do arquivo $nomeArquivo" -Quiet)) {
    $amostra = docker stats --no-stream --format '{{.CPUPerc}}|{{.MemUsage}}' conciliacao-batch
    $c, $m = $amostra -split '\|'
    $cpu += [double]($c -replace '%', '')
    $memoria += [double]($m -replace 'MiB.*', '')
    Start-Sleep -Seconds 3
}
$retomadas = (docker logs --since $inicioLog conciliacao-batch 2>&1 | Select-String 'retomando' | Measure-Object).Count

& "$PSScriptRoot\conferir-gabarito.ps1" "$Pasta\gabarito_$dia.json"
$confere = $LASTEXITCODE -eq 0

Write-Host ("RESULTADO chunk={0} memoria_max={1:N0}MiB cpu_media={2:N0}% retomadas_s3={3} gabarito={4}" -f `
    $Chunk, ($memoria | Measure-Object -Maximum).Maximum, ($cpu | Measure-Object -Average).Average, $retomadas,
    $(if ($confere) { 'confere' } else { 'NAO CONFERE' }))
