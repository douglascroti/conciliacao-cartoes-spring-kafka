#!/bin/bash
# Cria os tópicos da conciliação (idempotente: --if-not-exists) e ajusta a retenção.
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

# Retenção dos tópicos de saída, que recebem uma mensagem por linha (1 milhão por arquivo grande).
# Ambiente local: 24 h ou 512 MB por partição, o que vier primeiro. A limpeza só apaga segmentos
# fechados; com o segmento padrão de 1 GB o limite de 512 MB nunca seria atingido, daí os 128 MB.
# --alter também vale para tópicos que já existiam (o script roda a cada "docker compose up").
for topico in "conciliacao.resultado" "conciliacao.erro"; do
  /opt/kafka/bin/kafka-configs.sh --bootstrap-server "$BOOTSTRAP" \
    --alter --entity-type topics --entity-name "$topico" \
    --add-config retention.ms=86400000,retention.bytes=536870912,segment.bytes=134217728
done

echo "Tópicos existentes:"
/opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --list
