# Confere o resultado da ultima execucao concluida de um arquivo com o gabarito do gerador-dados.
# Uso: .\scripts\conferir-gabarito.ps1 massa\gabarito_20261015.json
# Sai com codigo 1 se algum numero nao bater.
param(
    [Parameter(Mandatory)][string]$Gabarito
)
$ErrorActionPreference = 'Stop'

$g = Get-Content $Gabarito -Raw | ConvertFrom-Json
$nomeArquivo = 'conciliacao_{0}.csv' -f ($g.dataReferencia -replace '-', '')

function Consultar([string]$sql) {
    $saida = docker exec postgres psql -U conciliacao -d conciliacao -tA -F '|' -c $sql
    if ($LASTEXITCODE -ne 0) { throw "Falha na consulta: $sql" }
    return $saida
}

# Ultima JobInstance concluida do arquivo (pelo nome nos parametros do job: funciona com os dois
# provedores de idempotencia).
$instancia = Consultar @"
select e.job_instance_id, p.parameter_value
  from batch_job_execution e
  join batch_job_execution_params p on p.job_execution_id = e.job_execution_id and p.parameter_name = 'idArquivo'
 where e.status = 'COMPLETED'
   and exists (select 1 from batch_job_execution_params n
                where n.job_execution_id = e.job_execution_id and n.parameter_name = 'nomeArquivo'
                  and n.parameter_value = '$nomeArquivo')
 order by e.job_execution_id desc limit 1
"@
if (-not $instancia) { throw "Nenhuma execucao concluida de $nomeArquivo" }
$idInstancia, $idArquivo = $instancia -split '\|'

$porStatus = @{}
Consultar "select status, count(*) from resultado_conciliacao where id_arquivo = '$idArquivo' group by status" |
    ForEach-Object { $s, $n = $_ -split '\|'; $porStatus[$s] = [long]$n }
$porCampo = @{}
Consultar "select unnest(string_to_array(campos_divergentes, ',')), count(*) from resultado_conciliacao where id_arquivo = '$idArquivo' and status = 'DIVERGENTE' group by 1" |
    ForEach-Object { $c, $n = $_ -split '\|'; $porCampo[$c] = [long]$n }
$invalidas = [long](Consultar "select count(*) from linha_invalida where id_arquivo = '$idArquivo'")
# Soma da duracao de cada execucao: o intervalo entre uma falha e o restart nao conta.
$execucoes, $segundos = (Consultar "select count(*), extract(epoch from sum(end_time - start_time)) from batch_job_execution where job_instance_id = $idInstancia") -split '\|'

function Linha([string]$item, $esperado, $obtido) {
    [pscustomobject]@{ Item = $item; Esperado = [long]$esperado; Obtido = [long]$obtido; Ok = ([long]$esperado -eq [long]$obtido) }
}
$linhas = @(
    Linha 'CONCILIADA' $g.esperado.CONCILIADA $porStatus['CONCILIADA']
    Linha 'DIVERGENTE' $g.esperado.DIVERGENTE $porStatus['DIVERGENTE']
    Linha '  VALOR' $g.divergentesPorCampo.VALOR $porCampo['VALOR']
    Linha '  PARCELAS' $g.divergentesPorCampo.PARCELAS $porCampo['PARCELAS']
    Linha '  DATA' $g.divergentesPorCampo.DATA $porCampo['DATA']
    Linha 'NAO_ENCONTRADA' $g.esperado.NAO_ENCONTRADA $porStatus['NAO_ENCONTRADA']
    Linha 'AUSENTE_NO_ARQUIVO' $g.esperado.AUSENTE_NO_ARQUIVO $porStatus['AUSENTE_NO_ARQUIVO']
    Linha 'linhas invalidas' $g.esperado.linhasInvalidas $invalidas
)

Write-Host "Arquivo $nomeArquivo (id $idArquivo), $execucoes execucao(oes) do job"
$linhas | Format-Table -AutoSize | Out-String | Write-Host
$taxa = [long]$g.linhasArquivo / [double]$segundos
Write-Host ("Duracao do job: {0:N1} s ({1:N0} linhas/s)" -f [double]$segundos, $taxa)

if ($linhas.Ok -contains $false) {
    Write-Host 'GABARITO NAO CONFERE' -ForegroundColor Red
    exit 1
}
Write-Host 'Gabarito confere' -ForegroundColor Green
