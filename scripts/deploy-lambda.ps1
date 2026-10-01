# Recompila a Lambda e publica no LocalStack (que precisa estar rodando).
# Uso: .\scripts\deploy-lambda.ps1
$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot

# -pl escolhe o módulo; -am ("also make") compila também os módulos de que ele depende.
& "$raiz\mvnw.cmd" -B -q -f "$raiz\pom.xml" -pl conciliacao-lambda -am package
if ($LASTEXITCODE -ne 0) { throw "Build da Lambda falhou" }

docker exec localstack bash /etc/localstack/init/ready.d/02-deploy-lambda.sh
if ($LASTEXITCODE -ne 0) { throw "Deploy da Lambda falhou" }
