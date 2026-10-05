# Lambda de recebimento: disparada pelo upload em entrada/, valida o arquivo, garante a
# idempotência e publica o evento no Kafka (ADR 0004).

locals {
  nome_log_group = "/aws/lambda/${var.nome_funcao}"
  # Spring Cloud Function: um handler genérico que chama a função declarada no Spring.
  handler = "org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest"
}

# ---- Permissões (IAM) ----

data "aws_iam_policy_document" "assumir_papel" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "lambda" {
  name               = "${var.nome_funcao}-papel"
  assume_role_policy = data.aws_iam_policy_document.assumir_papel.json
  tags               = var.tags
}

# Menor privilégio: exatamente as 5 operações que o código faz, cada uma só no prefixo em que ela
# acontece. Nada de s3:* ou dynamodb:*. (No LocalStack o IAM não é imposto por padrão; numa conta
# real, uma operação fora desta lista falha com AccessDenied.)
data "aws_iam_policy_document" "lambda" {
  statement {
    sid       = "Logs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.lambda.arn}:*"]
  }

  # Ler a primeira linha (validação do cabeçalho) e, se inválido, copiar para rejeitados/ e apagar.
  statement {
    sid       = "LerEApagarEntrada"
    actions   = ["s3:GetObject", "s3:DeleteObject"]
    resources = ["${var.bucket_arn}/${var.prefixo_entrada}*"]
  }

  # Destino da cópia (CopyObject) dos arquivos inválidos.
  statement {
    sid       = "GravarRejeitados"
    actions   = ["s3:PutObject"]
    resources = ["${var.bucket_arn}/${var.prefixo_rejeitados}*"]
  }

  # Registro de idempotência: PutItem condicional (attribute_not_exists) e DeleteItem para desfazer.
  statement {
    sid       = "Idempotencia"
    actions   = ["dynamodb:PutItem", "dynamodb:DeleteItem"]
    resources = [var.tabela_idempotencia_arn]
  }
}

resource "aws_iam_role_policy" "lambda" {
  name   = "${var.nome_funcao}-permissoes"
  role   = aws_iam_role.lambda.id
  policy = data.aws_iam_policy_document.lambda.json
}

# ---- Logs ----

# Criado pelo Terraform (e não automaticamente pela Lambda) para ter retenção definida: sem isso,
# os logs ficam para sempre e o custo cresce sem limite.
resource "aws_cloudwatch_log_group" "lambda" {
  name              = local.nome_log_group
  retention_in_days = var.dias_retencao_logs
  tags              = var.tags
}

# ---- Função ----

resource "aws_lambda_function" "recebimento" {
  function_name = var.nome_funcao
  role          = aws_iam_role.lambda.arn
  runtime       = "java21"
  handler       = local.handler
  filename      = var.caminho_jar
  # Hash do JAR: o Terraform só atualiza o código quando o arquivo muda (equivale ao
  # update-function-code do script antigo, mas só quando necessário).
  source_code_hash = filebase64sha256(var.caminho_jar)
  memory_size      = var.memoria_mb
  timeout          = var.timeout_segundos

  environment {
    variables = merge(var.variaveis_ambiente, {
      # Só o compilador JIT C1: inicializa mais rápido (cold start), recomendação da AWS para
      # funções curtas.
      JAVA_TOOL_OPTIONS = "-XX:+TieredCompilation -XX:TieredStopAtLevel=1"
    })
  }

  tags = var.tags

  depends_on = [aws_cloudwatch_log_group.lambda, aws_iam_role_policy.lambda]
}

# ---- Gatilho: upload em entrada/ ----

# Autoriza o S3 (deste bucket apenas) a invocar a função.
resource "aws_lambda_permission" "s3" {
  statement_id  = "PermitirInvocacaoPeloS3"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.recebimento.function_name
  principal     = "s3.amazonaws.com"
  source_arn    = var.bucket_arn
}

# Só objetos em entrada/ disparam a Lambda. Sem filtro de sufixo: um arquivo com extensão errada
# também precisa chegar à Lambda para ser movido para rejeitados/.
resource "aws_s3_bucket_notification" "entrada" {
  bucket = var.bucket_id

  lambda_function {
    lambda_function_arn = aws_lambda_function.recebimento.arn
    events              = ["s3:ObjectCreated:*"]
    filter_prefix       = var.prefixo_entrada
  }

  depends_on = [aws_lambda_permission.s3]
}
