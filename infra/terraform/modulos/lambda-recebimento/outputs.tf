output "funcao_nome" {
  value = aws_lambda_function.recebimento.function_name
}

output "funcao_arn" {
  value = aws_lambda_function.recebimento.arn
}

output "papel_arn" {
  value = aws_iam_role.lambda.arn
}

output "log_group" {
  value = aws_cloudwatch_log_group.lambda.name
}
