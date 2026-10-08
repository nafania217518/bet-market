#!/usr/bin/env bash
# Rebuilds the read model from Kafka and proves the result is the same (architecture §15 #4).
# 1. snapshot read_db + leaderboard  2. stop the projection  3. drop read_db, delete its Redis keys
# 4. reset the projection consumer group to the earliest offsets  5. start it, wait for lag 0
# 6. snapshot again and diff. Run it on a quiet system: new events during the run change the result.
# Needs the topic retention from infra/kafka/create-topics.sh (replayed topics keep events forever).
set -euo pipefail

COMPOSE=(docker compose -f "$(dirname "$0")/../docker-compose.yml")
GROUP=projection-service
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
mongo() { "${COMPOSE[@]}" exec -T mongodb mongosh --quiet read_db --eval "$1"; }
redis() { "${COMPOSE[@]}" exec -T redis redis-cli "$@"; }
groups() { "${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:29092 --group "$GROUP" "$@"; }

# Read model without updatedAt (a rebuild sets new timestamps), sorted for a stable diff.
# Keys are sorted too: field order depends on which event created the document first.
snapshot() {
  mongo 'const sorted = v => Array.isArray(v) ? v.map(sorted) : v && typeof v === "object"
           ? Object.fromEntries(Object.keys(v).sort().map(k => [k, sorted(v[k])])) : v;
         for (const c of ["market_view", "bet_view", "user_view"])
           db[c].find({}, {updatedAt: 0}).sort({_id: 1})
             .forEach(d => print(c, JSON.stringify(sorted(JSON.parse(EJSON.stringify(d))))))'
  for key in $(redis --scan --pattern 'leaderboard:day:*' | sort) leaderboard:all; do
    echo "$key $(redis ZRANGE "$key" 0 -1 WITHSCORES | tr '\n' ' ')"
  done
}

# Sum of the LAG column. "-" means no committed offset: done only if the partition is empty.
lag() {
  groups --describe 2>/dev/null | awk '$1 == "'$GROUP'" { if ($6 == "-") { if ($5 != 0) busy = 1 } else { sum += $6 } }
    END { if (busy || NR == 0) print "busy"; else print sum + 0 }'
}

echo "1. snapshot before"
snapshot > "$TMP/before"
[ -s "$TMP/before" ] || fail "read model is empty; run scripts/smoke.sh first"

echo "2. stop the projection"
"${COMPOSE[@]}" stop projection-service > /dev/null

echo "3. drop read_db and the projection's Redis keys"
mongo 'db.dropDatabase()' > /dev/null
# One exec per pattern: a `while read` loop around `docker compose exec` lets exec eat the key list.
for pattern in 'leaderboard:*' 'projection:*' 'cache:*'; do
  "${COMPOSE[@]}" exec -T redis sh -c "redis-cli --scan --pattern '$pattern' | xargs -r redis-cli DEL" > /dev/null
done

echo "4. reset $GROUP to the earliest offsets"
groups --reset-offsets --to-earliest --all-topics --execute > /dev/null

echo "5. replay"
"${COMPOSE[@]}" start projection-service > /dev/null
for _ in $(seq 1 60); do
  [ "$(lag)" = 0 ] && break
  sleep 2
done
[ "$(lag)" = 0 ] || fail "projection did not catch up"

echo "6. compare"
snapshot > "$TMP/after"
diff "$TMP/before" "$TMP/after" || fail "rebuilt read model differs"
echo "OK: rebuilt read model is identical ($(wc -l < "$TMP/after" | tr -d ' ') documents and keys)"
