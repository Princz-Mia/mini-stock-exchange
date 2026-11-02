#!/bin/bash
# Idempotent topic setup: three partitions per topic, replication factor 1 locally.
set -euo pipefail

TOPICS=/opt/kafka/bin/kafka-topics.sh
SEVEN_DAYS_MS=604800000

create() {
  "$TOPICS" --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" --create --if-not-exists \
    --partitions 3 --replication-factor 1 "$@"
}

create --topic mse.trades.v1 --config retention.ms="$SEVEN_DAYS_MS"
create --topic mse.market.v1 --config cleanup.policy=compact
create --topic mse.candles.v1 --config retention.ms="$SEVEN_DAYS_MS"

"$TOPICS" --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" --describe --topic 'mse\..*'
