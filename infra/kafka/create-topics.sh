#!/bin/sh
# Creates every Kafka topic from architecture.md §9, plus its .DLT counterpart. Idempotent.
# Also re-applies retention on existing topics, so a config change reaches old local volumes.
set -eu

BOOTSTRAP="${BOOTSTRAP:-kafka:29092}"
KT=/opt/kafka/bin/kafka-topics.sh
KC=/opt/kafka/bin/kafka-configs.sh
DAY=86400000

# name partitions retention_days ("inf" = keep forever)
# The Projection Service rebuilds the read model by replaying every topic it consumes
# (scripts/rebuild-read-model.sh). Those topics keep their events forever; otherwise a replay
# after the retention period loses data. DLT copies keep 30 days.
TOPICS="
user.registered 3 inf
user.updated 3 7
wallet.created 3 7
wallet.reserved 6 3
wallet.debited 6 inf
wallet.credited 6 inf
wallet.released 6 3
wallet.debit_failed 6 7
market.created 6 inf
market.updated 6 inf
market.frozen 6 inf
market.ended 6 inf
market.resolved 6 inf
market.cancelled 6 inf
bet.placed 6 inf
bet.cancelled 6 inf
bet.failed 6 3
settlement.started 3 30
payout.calculated 6 90
settlement.completed 3 inf
"

retention_ms() { [ "$1" = inf ] && echo -1 || echo $(($1 * DAY)); }

echo "$TOPICS" | while read -r name parts days; do
  [ -z "$name" ] && continue
  for t in "$name" "$name.DLT"; do
    r=$(retention_ms "$days")
    [ "$t" != "$name" ] && [ "$days" = inf ] && r=$((30 * DAY))
    $KT --bootstrap-server "$BOOTSTRAP" --create --if-not-exists --topic "$t" \
      --partitions "$parts" --replication-factor 1 --config "retention.ms=$r"
    $KC --bootstrap-server "$BOOTSTRAP" --alter --entity-type topics --entity-name "$t" \
      --add-config "retention.ms=$r" > /dev/null
  done
done

$KT --bootstrap-server "$BOOTSTRAP" --list
