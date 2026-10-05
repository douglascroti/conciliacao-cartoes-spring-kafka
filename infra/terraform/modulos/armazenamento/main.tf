# Armazenamento da conciliação: o bucket onde a adquirente entrega os arquivos e a tabela de
# idempotência da Lambda (usada quando IDEMPOTENCIA_PROVEDOR=dynamodb).

resource "aws_s3_bucket" "conciliacao" {
  bucket = var.nome_bucket
  # Só no ambiente local: permite destruir o bucket mesmo com arquivos dentro.
  force_destroy = var.permitir_destruir_com_conteudo
  tags          = var.tags
}

# Arquivos de cartão nunca podem ficar públicos, nem por engano numa política futura.
resource "aws_s3_bucket_public_access_block" "conciliacao" {
  bucket                  = aws_s3_bucket.conciliacao.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Criptografia em repouso. SSE-S3 (chave gerenciada pela AWS); numa conta real, SSE-KMS com chave
# própria dá controle de acesso e auditoria da chave (ver ADR 0014).
resource "aws_s3_bucket_server_side_encryption_configuration" "conciliacao" {
  bucket = aws_s3_bucket.conciliacao.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# Versionamento: um arquivo sobrescrito ou apagado por engano pode ser recuperado. O mesmo nome com
# conteúdo novo gera outro ETag, e a Lambda já trata isso como arquivo novo.
resource "aws_s3_bucket_versioning" "conciliacao" {
  bucket = aws_s3_bucket.conciliacao.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "conciliacao" {
  bucket = aws_s3_bucket.conciliacao.id

  # Rejeitados ficam disponíveis para análise com a adquirente e depois são apagados.
  rule {
    id     = "expirar-rejeitados"
    status = "Enabled"
    filter {
      prefix = var.prefixo_rejeitados
    }
    expiration {
      days = var.dias_retencao_rejeitados
    }
  }

  # Versões antigas (sobrescritas ou apagadas) não acumulam para sempre.
  rule {
    id     = "expirar-versoes-antigas"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = var.dias_retencao_versoes_antigas
    }
  }

  depends_on = [aws_s3_bucket_versioning.conciliacao]
}

# Chave composta nomeArquivo (partição) + etag (ordenação): o mesmo par do UNIQUE no PostgreSQL.
# PAY_PER_REQUEST: sem capacidade para dimensionar; o volume é de alguns arquivos por dia.
resource "aws_dynamodb_table" "idempotencia" {
  name         = var.nome_tabela_idempotencia
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "nomeArquivo"
  range_key    = "etag"

  attribute {
    name = "nomeArquivo"
    type = "S"
  }

  attribute {
    name = "etag"
    type = "S"
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled = true
  }

  tags = var.tags
}
