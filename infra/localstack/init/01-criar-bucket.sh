#!/bin/bash
# Executado pelo LocalStack quando fica pronto (ready.d). Cria o bucket de conciliação.
set -euo pipefail

BUCKET="conciliacao"

if awslocal s3api head-bucket --bucket "$BUCKET" 2>/dev/null; then
  echo "Bucket $BUCKET já existe"
else
  awslocal s3 mb "s3://$BUCKET"
fi
