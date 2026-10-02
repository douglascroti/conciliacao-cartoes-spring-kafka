#!/bin/bash
# Executado pelo LocalStack quando fica pronto (ready.d). Cria a tabela de idempotência no
# DynamoDB, usada quando IDEMPOTENCIA_PROVEDOR=dynamodb. É criada sempre (é barata), para
# que trocar o provedor exija só um novo deploy da Lambda.
set -euo pipefail

TABELA="${IDEMPOTENCIA_TABELA_DYNAMO:-arquivo-recebido}"

if awslocal dynamodb describe-table --table-name "$TABELA" > /dev/null 2>&1; then
  echo "Tabela $TABELA já existe"
  exit 0
fi

# Chave composta nomeArquivo (partição) + etag (ordenação): o mesmo par do UNIQUE no Postgres.
# PAY_PER_REQUEST (on-demand): sem capacidade provisionada para dimensionar.
awslocal dynamodb create-table \
  --table-name "$TABELA" \
  --attribute-definitions AttributeName=nomeArquivo,AttributeType=S AttributeName=etag,AttributeType=S \
  --key-schema AttributeName=nomeArquivo,KeyType=HASH AttributeName=etag,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST > /dev/null
awslocal dynamodb wait table-exists --table-name "$TABELA"
echo "Tabela $TABELA criada no DynamoDB"
