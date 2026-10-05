# O provider AWS de verdade, apontado para o LocalStack. Numa conta real, este bloco perde os
# endpoints e as opções skip_* e as credenciais vêm do ambiente (perfil, SSO ou OIDC no CI).
provider "aws" {
  region = var.regiao

  # Credenciais fictícias: o LocalStack aceita qualquer valor.
  access_key = "test"
  secret_key = "test"

  # Sem chamadas à AWS real para validar credenciais ou descobrir a conta.
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true

  # http://localstack:4566/bucket/chave em vez de http://bucket.localstack:4566/chave.
  s3_use_path_style = true

  endpoints {
    s3       = var.endpoint_localstack
    dynamodb = var.endpoint_localstack
    lambda   = var.endpoint_localstack
    iam      = var.endpoint_localstack
    sts      = var.endpoint_localstack
    logs     = var.endpoint_localstack
  }

  default_tags {
    tags = {
      projeto        = "conciliacao-cartoes"
      ambiente       = "local"
      gerenciado-por = "terraform"
    }
  }
}
