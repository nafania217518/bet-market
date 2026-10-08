#!/bin/sh
# Creates every Kafka topic from architecture.md §9, plus its .DLT counterpart. Idempotent.
set -eu

BOOTSTRAP="${BOOTSTRAP:-kafka:29092}"
KT=/opt/kafka/bin/kafka-topics.sh
DAY=86400000

# name partitions retention_days
TOPICS="
user.registered 3 7
user.updated 3 7
wallet.created 3 7
wallet.reserved 6 3
wallet.debited 6 7
wallet.credited 6 30
wallet.released 6 3
wallet.debit_failed 6 7
market.created 6 30
market.updated 6 30
market.frozen 6 30
market.ended 6 90
market.resolved 6 90
market.cancelled 6 90
bet.placed 6 90
bet.cancelled 6 90
bet.failed 6 3
settlement.started 3 30
payout.calculated 6 90
settlement.completed 3 90
"

echo "$TOPICS" | while read -r name parts days; do
  [ -z "$name" ] && continue
  for t in "$name" "$name.DLT"; do
    $KT --bootstrap-server "$BOOTSTRAP" --create --if-not-exists --topic "$t" \
      --partitions "$parts" --replication-factor 1 --config "retention.ms=$((days * DAY))"
  done
done

$KT --bootstrap-server "$BOOTSTRAP" --list
