variable "regiao" {
  type    = string
  default = "us-east-1"
}

variable "endpoint_localstack" {
  description = "Endpoint do LocalStack visto de onde o Terraform roda (container na rede do compose)."
  type        = string
  default     = "http://localstack:4566"
}

variable "caminho_jar_lambda" {
  description = "JAR da Lambda, relativo a esta pasta. O script terraform.ps1 informa o arquivo gerado pelo Maven."
  type        = string
}

# ---- Configuração da Lambda (vem do .env, pelo script terraform.ps1, como TF_VAR_*) ----

variable "idempotencia_provedor" {
  description = "Onde a Lambda guarda a idempotência: postgres ou dynamodb."
  type        = string
  default     = "postgres"

  validation {
    condition     = contains(["postgres", "dynamodb"], var.idempotencia_provedor)
    error_message = "idempotencia_provedor deve ser postgres ou dynamodb."
  }
}

variable "nome_tabela_idempotencia" {
  type    = string
  default = "arquivo-recebido"
}

variable "kafka_bootstrap_servers" {
  description = "Kafka visto de dentro do container da Lambda (rede do compose)."
  type        = string
  default     = "kafka:9092"
}

variable "db_nome" {
  type    = string
  default = "conciliacao"
}

variable "db_usuario" {
  type    = string
  default = "conciliacao"
}

variable "db_senha" {
  type      = string
  default   = "conciliacao"
  sensitive = true
}
