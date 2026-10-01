#!/bin/bash
# Cria os tópicos da conciliação (idempotente: --if-not-exists).
set -euo pipefail

BOOTSTRAP="kafka:9092"
TOPICOS=(
  "conciliacao.arquivo-recebido"
  "conciliacao.resultado"
  "conciliacao.erro"
)

for topico in "${TOPICOS[@]}"; do
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" \
    --create --if-not-exists --topic "$topico" \
    --partitions 3 --replication-factor 1
done

echo "Tópicos existentes:"
/opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --list
