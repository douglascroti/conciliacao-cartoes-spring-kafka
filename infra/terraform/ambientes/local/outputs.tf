output "bucket" {
  value = module.armazenamento.bucket_id
}

output "tabela_idempotencia" {
  value = module.armazenamento.tabela_idempotencia_nome
}

output "lambda" {
  value = module.lambda_recebimento.funcao_nome
}

output "lambda_log_group" {
  value = module.lambda_recebimento.log_group
}
