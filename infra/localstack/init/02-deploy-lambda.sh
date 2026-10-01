#!/bin/bash
# Publica (ou atualiza) a Lambda de recebimento e liga a notificação do bucket a ela.
# Roda automaticamente quando o LocalStack fica pronto (ready.d) e também pode ser chamado
# depois de um rebuild: scripts/deploy-lambda.ps1
set -euo pipefail

FUNCAO="receber-arquivo-conciliacao"
BUCKET="conciliacao"
HANDLER="org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest"
JAR=$(ls /opt/lambda/conciliacao-lambda-*-aws.jar 2>/dev/null | head -1 || true)

if [ -z "$JAR" ]; then
  echo "JAR da Lambda não encontrado em /opt/lambda. Gere com: .\\mvnw.cmd package e rode scripts/deploy-lambda.ps1"
  exit 0
fi

# Variáveis de ambiente da função. JSON em arquivo evita problemas de escape (espaços no JAVA_TOOL_OPTIONS).
# TieredStopAtLevel=1: a JVM só usa o compilador JIT C1 (rápido de iniciar). Recomendação da AWS
# para reduzir cold start de funções curtas.
cat > /tmp/lambda-env.json <<EOF
{
  "Variables": {
    "KAFKA_BOOTSTRAP_SERVERS": "kafka:9092",
    "DB_URL": "jdbc:postgresql://postgres:5432/${POSTGRES_DB}",
    "DB_USER": "${POSTGRES_USER}",
    "DB_PASSWORD": "${POSTGRES_PASSWORD}",
    "S3_FORCE_PATH_STYLE": "true",
    "JAVA_TOOL_OPTIONS": "-XX:+TieredCompilation -XX:TieredStopAtLevel=1"
  }
}
EOF

if awslocal lambda get-function --function-name "$FUNCAO" > /dev/null 2>&1; then
  echo "Atualizando a função $FUNCAO com $(basename "$JAR")"
  awslocal lambda update-function-code --function-name "$FUNCAO" --zip-file "fileb://$JAR" > /dev/null
  awslocal lambda wait function-updated-v2 --function-name "$FUNCAO"
  awslocal lambda update-function-configuration --function-name "$FUNCAO" \
    --environment file:///tmp/lambda-env.json > /dev/null
  awslocal lambda wait function-updated-v2 --function-name "$FUNCAO"
else
  echo "Criando a função $FUNCAO com $(basename "$JAR")"
  awslocal lambda create-function \
    --function-name "$FUNCAO" \
    --runtime java21 \
    --handler "$HANDLER" \
    --role arn:aws:iam::000000000000:role/lambda-conciliacao \
    --zip-file "fileb://$JAR" \
    --memory-size 1024 \
    --timeout 60 \
    --environment file:///tmp/lambda-env.json > /dev/null
  awslocal lambda wait function-active-v2 --function-name "$FUNCAO"
fi

ARN=$(awslocal lambda get-function --function-name "$FUNCAO" --query 'Configuration.FunctionArn' --output text)

# Só objetos em entrada/ disparam a Lambda. Sem filtro de sufixo: um arquivo com extensão
# errada também precisa chegar à Lambda para ser movido para rejeitados/.
awslocal s3api put-bucket-notification-configuration --bucket "$BUCKET" --notification-configuration "{
  \"LambdaFunctionConfigurations\": [{
    \"LambdaFunctionArn\": \"$ARN\",
    \"Events\": [\"s3:ObjectCreated:*\"],
    \"Filter\": {\"Key\": {\"FilterRules\": [{\"Name\": \"prefix\", \"Value\": \"entrada/\"}]}}
  }]
}"

echo "Lambda $FUNCAO pronta e ligada a s3://$BUCKET/entrada/"
