# Ambiente local: a infraestrutura AWS da conciliação no LocalStack. Kafka e PostgreSQL continuam
# no docker-compose (numa conta real seriam MSK e RDS; ver ADR 0014).

locals {
  bucket = "conciliacao"
}

module "armazenamento" {
  source = "../../modulos/armazenamento"

  nome_bucket              = local.bucket
  nome_tabela_idempotencia = var.nome_tabela_idempotencia
  # Ambiente descartável: o destroy apaga o bucket mesmo com arquivos.
  permitir_destruir_com_conteudo = true
}

module "lambda_recebimento" {
  source = "../../modulos/lambda-recebimento"

  nome_funcao             = "receber-arquivo-conciliacao"
  caminho_jar             = var.caminho_jar_lambda
  bucket_id               = module.armazenamento.bucket_id
  bucket_arn              = module.armazenamento.bucket_arn
  tabela_idempotencia_arn = module.armazenamento.tabela_idempotencia_arn

  variaveis_ambiente = {
    KAFKA_BOOTSTRAP_SERVERS    = var.kafka_bootstrap_servers
    DB_URL                     = "jdbc:postgresql://postgres:5432/${var.db_nome}"
    DB_USER                    = var.db_usuario
    DB_PASSWORD                = var.db_senha
    IDEMPOTENCIA_PROVEDOR      = var.idempotencia_provedor
    IDEMPOTENCIA_TABELA_DYNAMO = module.armazenamento.tabela_idempotencia_nome
    S3_FORCE_PATH_STYLE        = "true"
  }
}
