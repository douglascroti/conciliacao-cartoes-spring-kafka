variable "nome_funcao" {
  description = "Nome da função Lambda."
  type        = string
}

variable "caminho_jar" {
  description = "Caminho do JAR da Lambda (conciliacao-lambda-*-aws.jar, gerado pelo Maven)."
  type        = string
}

variable "bucket_id" {
  description = "Bucket cujo upload em prefixo_entrada dispara a função."
  type        = string
}

variable "bucket_arn" {
  type = string
}

variable "prefixo_entrada" {
  type    = string
  default = "entrada/"
}

variable "prefixo_rejeitados" {
  type    = string
  default = "rejeitados/"
}

variable "tabela_idempotencia_arn" {
  description = "ARN da tabela DynamoDB de idempotência."
  type        = string
}

variable "variaveis_ambiente" {
  description = "Variáveis de ambiente da função (Kafka, banco, provedor de idempotência)."
  type        = map(string)
  # Contém a senha do banco: o Terraform não a mostra no plan nem nos logs.
  sensitive = true
}

variable "memoria_mb" {
  description = "Memória da função. Na Lambda, a CPU cresce junto com a memória."
  type        = number
  default     = 1024
}

variable "timeout_segundos" {
  type    = number
  default = 60
}

variable "dias_retencao_logs" {
  type    = number
  default = 14
}

variable "tags" {
  type    = map(string)
  default = {}
}
