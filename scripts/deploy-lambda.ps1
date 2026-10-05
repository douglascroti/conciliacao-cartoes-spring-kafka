# Recompila a Lambda e publica no LocalStack (que precisa estar rodando), via Terraform.
# Uso: .\scripts\deploy-lambda.ps1
# O Terraform compara o hash do JAR. Cada build gera um JAR diferente (o spring.factories mesclado
# pelo shade leva a data do build), entao este script sempre reenvia a Lambda; um "terraform.ps1
# apply" sem rebuild nao reenvia nada.
$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot

# -pl escolhe o modulo; -am ("also make") compila tambem os modulos de que ele depende.
& "$raiz\mvnw.cmd" -B -q -f "$raiz\pom.xml" -pl conciliacao-lambda -am package
if ($LASTEXITCODE -ne 0) { throw "Build da Lambda falhou" }

& "$PSScriptRoot\terraform.ps1" apply -auto-approve
if ($LASTEXITCODE -ne 0) { throw "Deploy da Lambda falhou" }
