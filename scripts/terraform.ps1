# Roda o Terraform do ambiente local (infra/terraform/ambientes/local) contra o LocalStack.
# O Terraform roda num container na rede do compose: nada para instalar na maquina.
#
# Uso:
#   .\scripts\terraform.ps1 plan                  # mostra o que vai mudar
#   .\scripts\terraform.ps1 apply                 # aplica (pede confirmacao)
#   .\scripts\terraform.ps1 apply -auto-approve   # aplica sem perguntar
#   .\scripts\terraform.ps1 output | destroy | validate | fmt
#
# Pre-requisitos: LocalStack no ar (docker compose up -d --wait) e, para plan/apply, o JAR da
# Lambda gerado (.\mvnw.cmd package).
param(
    [Parameter(Mandatory, Position = 0)]
    [ValidateSet('init', 'plan', 'apply', 'destroy', 'output', 'validate', 'fmt', 'state')]
    [string]$Comando,
    [Parameter(ValueFromRemainingArguments)]
    [string[]]$Argumentos
)
# Continue, e nao Stop: no Windows PowerShell 5.1 o stderr de programas nativos vira erro fatal
# com Stop. Os codigos de saida sao checados.
$ErrorActionPreference = 'Continue'

$raiz = Split-Path -Parent $PSScriptRoot
$imagem = 'hashicorp/terraform:1.16.5'
$pastaAmbiente = 'infra/terraform/ambientes/local'

# Configuracao da Lambda vinda do .env (as mesmas variaveis que o docker-compose usa).
$env_ = @{}
$arquivoEnv = Join-Path $raiz '.env'
if (Test-Path $arquivoEnv) {
    Get-Content $arquivoEnv | Where-Object { $_ -match '^\s*([A-Z_][A-Z0-9_]*)\s*=\s*(.*?)\s*$' } | ForEach-Object {
        $env_[$Matches[1]] = $Matches[2]
    }
}
# Como no docker-compose: a variavel do terminal tem prioridade sobre o .env.
function Valor($nome, $padrao) {
    $doTerminal = [Environment]::GetEnvironmentVariable($nome)
    if ($doTerminal) { $doTerminal }
    elseif ($env_.ContainsKey($nome) -and $env_[$nome]) { $env_[$nome] }
    else { $padrao }
}

$variaveis = @(
    '-e', "TF_VAR_db_nome=$(Valor 'POSTGRES_DB' 'conciliacao')",
    '-e', "TF_VAR_db_usuario=$(Valor 'POSTGRES_USER' 'conciliacao')",
    '-e', "TF_VAR_db_senha=$(Valor 'POSTGRES_PASSWORD' 'conciliacao')",
    '-e', "TF_VAR_idempotencia_provedor=$(Valor 'IDEMPOTENCIA_PROVEDOR' 'postgres')",
    '-e', "TF_VAR_nome_tabela_idempotencia=$(Valor 'IDEMPOTENCIA_TABELA_DYNAMO' 'arquivo-recebido')"
)

# JAR da Lambda: o nome tem a versao do projeto, entao e localizado aqui.
if ($Comando -in 'plan', 'apply', 'destroy') {
    $jar = Get-ChildItem (Join-Path $raiz 'conciliacao-lambda/target') -Filter 'conciliacao-lambda-*-aws.jar' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $jar) {
        Write-Error "JAR da Lambda nao encontrado. Gere com: .\mvnw.cmd package"
        exit 1
    }
    # Relativo a pasta do ambiente, dentro do container.
    $variaveis += '-e', "TF_VAR_caminho_jar_lambda=../../../../conciliacao-lambda/target/$($jar.Name)"
}

function Terraform([string[]]$argumentos) {
    # --network: alcanca o LocalStack pelo nome "localstack". O repositorio inteiro e montado
    # porque o ambiente referencia os modulos (../../modulos) e o JAR da Lambda.
    docker run --rm -i --network conciliacao-net `
        -v "${raiz}:/workspace" -w "/workspace/$pastaAmbiente" `
        @variaveis $imagem @argumentos
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

# Primeira execucao (ou depois de limpar): baixa o provider AWS.
if ($Comando -ne 'init' -and $Comando -ne 'fmt' -and -not (Test-Path (Join-Path $raiz "$pastaAmbiente/.terraform"))) {
    Terraform @('init', '-input=false')
}

if ($Comando -eq 'fmt') {
    # Formata todos os .tf (modulos e ambientes), a partir da pasta infra/terraform.
    docker run --rm -v "${raiz}:/workspace" -w /workspace/infra/terraform $imagem fmt -recursive @Argumentos
    exit $LASTEXITCODE
}

Terraform (@($Comando) + @($Argumentos | Where-Object { $_ }))
