#!/bin/sh
# Registers one outbox connector per database (PUT is idempotent) and waits until each runs.
# Needs only curl and sed; the Debezium Connect image has both.
set -eu
CONNECT_URL=${CONNECT_URL:-http://connect:8083}
DBS=${DBS:-"user_db wallet_db market_db bet_db settlement_db"}
TEMPLATE="$(dirname "$0")/outbox-connector.json"

i=0
until curl -fsS "$CONNECT_URL/connectors" > /dev/null 2>&1; do
  i=$((i + 1)); [ "$i" -ge 60 ] && { echo "Connect REST at $CONNECT_URL did not answer in 120 s"; exit 1; }
  sleep 2
done

for db in $DBS; do
  sed "s/__DB__/$db/g" "$TEMPLATE" \
    | curl -fsS -X PUT -H 'Content-Type: application/json' --data @- "$CONNECT_URL/connectors/outbox-$db/config" > /dev/null
  echo "registered outbox-$db"
done

for db in $DBS; do
  i=0
  # Two RUNNING states: the connector and its single task.
  until [ "$(curl -fsS "$CONNECT_URL/connectors/outbox-$db/status" 2>/dev/null | grep -o '"state":"RUNNING"' | wc -l)" -ge 2 ]; do
    i=$((i + 1)); [ "$i" -ge 30 ] && { echo "outbox-$db not RUNNING after 60 s:"; curl -sS "$CONNECT_URL/connectors/outbox-$db/status"; echo; exit 1; }
    sleep 2
  done
  echo "outbox-$db RUNNING"
done
