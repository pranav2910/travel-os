#!/usr/bin/env bash
# Creates the topics declared in contracts/events/topics.yaml on the local broker.
# libs/events TopicsRegistryTest fails the build if this list drifts from topics.yaml.
set -euo pipefail

BOOTSTRAP="${BOOTSTRAP:-localhost:9092}"
PARTITIONS="${PARTITIONS:-3}"
REPLICATION="${REPLICATION:-1}"
KAFKA_TOPICS="${KAFKA_TOPICS:-/opt/kafka/bin/kafka-topics.sh}"

TOPICS=(travel.intent travel.trip travel.search travel.policy travel.optimization travel.approval travel.order travel.disruption travel.expense travel.agent travel.audit)

for topic in "${TOPICS[@]}"; do
  retention=604800000
  if [[ "$topic" == "travel.audit" ]]; then retention=2592000000; fi
  "$KAFKA_TOPICS" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$topic" --partitions "$PARTITIONS" --replication-factor "$REPLICATION" \
    --config "retention.ms=$retention" >/dev/null
  echo "topic ready: $topic"
done

echo "--- topics on $BOOTSTRAP ---"
"$KAFKA_TOPICS" --bootstrap-server "$BOOTSTRAP" --list
