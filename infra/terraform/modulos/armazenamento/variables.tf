variable "nome_bucket" {
  description = "Nome do bucket que recebe os arquivos da adquirente."
  type        = string
}

variable "nome_tabela_idempotencia" {
  description = "Nome da tabela DynamoDB de idempotência (chave nomeArquivo + etag)."
  type        = string
}

variable "prefixo_rejeitados" {
  description = "Prefixo para onde a Lambda move os arquivos inválidos."
  type        = string
  default     = "rejeitados/"
}

variable "dias_retencao_rejeitados" {
  description = "Dias até apagar um arquivo rejeitado."
  type        = number
  default     = 90
}

variable "dias_retencao_versoes_antigas" {
  description = "Dias até apagar versões sobrescritas ou apagadas de um objeto."
  type        = number
  default     = 30
}

variable "permitir_destruir_com_conteudo" {
  description = "true só em ambientes descartáveis: o terraform destroy apaga o bucket mesmo com arquivos."
  type        = bool
  default     = false
}

variable "tags" {
  description = "Tags aplicadas aos recursos."
  type        = map(string)
  default     = {}
}
