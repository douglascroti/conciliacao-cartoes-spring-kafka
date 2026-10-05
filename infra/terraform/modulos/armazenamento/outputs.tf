output "bucket_id" {
  value = aws_s3_bucket.conciliacao.id
}

output "bucket_arn" {
  value = aws_s3_bucket.conciliacao.arn
}

output "tabela_idempotencia_nome" {
  value = aws_dynamodb_table.idempotencia.name
}

output "tabela_idempotencia_arn" {
  value = aws_dynamodb_table.idempotencia.arn
}
