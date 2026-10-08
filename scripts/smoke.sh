#!/usr/bin/env bash
# End-to-end smoke check. Run after `docker compose up -d --wait`. Grows every milestone.
# Talks only to the API gateway.
# M1: register -> login -> wallet shows the starting balance.
# M2: gateway auth (JWT), identity headers, correlation id, seeded admin.
# M3: admin creates a market; db-scheduler freezes it at freezeAt; market.frozen reaches Kafka.
# M4: bet saga: reserve -> bet.placed -> debit; idempotent replay; cancel refunds; no bets after freeze.
# M5: second user bets the other side; admin resolves after endAt; settlement pays the winner.
# M6: the read side (Query Service via the gateway) shows the market, the bets and the leaderboard.
# The Kafka check uses `docker compose exec`: Kafka has no route through the gateway.
set -euo pipefail

API="${API:-http://localhost:8080}"
STARTING_TOKENS="${STARTING_TOKENS:-1000}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@bet-market.local}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin-password}"
ADMIN_ID="00000000-0000-0000-0000-000000000001"
FREEZE_IN="${FREEZE_IN:-30}"
HOUSE_EDGE_BPS="${HOUSE_EDGE_BPS:-500}"
COMPOSE=(docker compose -f "$(dirname "$0")/../docker-compose.yml")

fail() { echo "FAIL: $*" >&2; exit 1; }
json_field() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p"; }
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

# Print the JSON payload of a JWT (base64url, unpadded).
# ISO-8601 UTC time N seconds from now (BSD and GNU date).
iso_in() {
  local t=$(( $(date +%s) + $1 ))
  date -u -r "$t" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d "@$t" +%Y-%m-%dT%H:%M:%SZ
}

jwt_payload() {
  local p
  p=$(cut -d. -f2 <<< "$1" | tr '_-' '/+')
  while (( ${#p} % 4 )); do p="$p="; done
  base64 -d <<< "$p"
}

for _ in $(seq 1 60); do curl -fs "$API/actuator/health" > /dev/null && break; sleep 2; done
curl -fs "$API/actuator/health" > /dev/null || fail "gateway is not up"

EMAIL="smoke-$(date +%s)-$RANDOM@example.com"
PASSWORD="smoke-password-1"

echo "1. register $EMAIL"
USER_ID=$(curl -fs -X POST "$API/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"displayName\":\"Smoke\"}" | json_field userId)
[ -n "$USER_ID" ] || fail "register returned no userId"

echo "2. login"
TOKEN=$(curl -fs -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" | json_field accessToken)
[ -n "$TOKEN" ] || fail "login returned no accessToken"

echo "3. wallet needs a token"
[ "$(status "$API/api/wallets/me")" = 401 ] || fail "wallet without token is not 401"
[ "$(status "$API/api/wallets/me" -H "X-User-Id: $USER_ID")" = 401 ] || fail "X-User-Id alone must not authenticate"

echo "4. wallet (async via user.registered)"
BALANCE=""
for _ in $(seq 1 30); do
  BALANCE=$(curl -fs "$API/api/wallets/me" -H "Authorization: Bearer $TOKEN" | json_field balance || true)
  [ -n "$BALANCE" ] && break
  sleep 1
done
[ "$BALANCE" = "$STARTING_TOKENS" ] || fail "balance is '${BALANCE:-none}', expected $STARTING_TOKENS"

echo "5. gateway overrides a spoofed X-User-Id"
# The admin has no wallet: if the spoofed id reached the wallet service, this would be 404.
WALLET_USER=$(curl -fs "$API/api/wallets/me" -H "Authorization: Bearer $TOKEN" -H "X-User-Id: $ADMIN_ID" | json_field userId)
[ "$WALLET_USER" = "$USER_ID" ] || fail "spoofed X-User-Id reached the wallet service ($WALLET_USER)"

echo "6. correlation id is echoed"
CID="smoke-$RANDOM"
curl -fs -D - -o /dev/null "$API/api/wallets/me" -H "Authorization: Bearer $TOKEN" -H "X-Correlation-Id: $CID" \
  | grep -qi "^X-Correlation-Id: $CID" || fail "X-Correlation-Id not echoed"

echo "7. seeded admin has the ADMIN role"
ADMIN_TOKEN=$(curl -fs -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" | json_field accessToken)
[ -n "$ADMIN_TOKEN" ] || fail "admin login failed"
jwt_payload "$ADMIN_TOKEN" | grep -q '"ADMIN"' || fail "admin token has no ADMIN role"

echo "8. only an admin can create a market"
MARKET="{\"title\":\"Smoke market\",\"description\":\"\",\"freezeAt\":\"$(iso_in "$FREEZE_IN")\",\"endAt\":\"$(iso_in $((FREEZE_IN + 5)))\",\"minBet\":1,\"maxBet\":500}"
[ "$(status -X POST "$API/api/markets" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$MARKET")" = 403 ] \
  || fail "a USER token must not create a market"
MARKET_ID=$(curl -fs -X POST "$API/api/markets" -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d "$MARKET" | json_field marketId)
[ -n "$MARKET_ID" ] || fail "admin could not create a market"

wallet_is() { # wait until GET /wallets/me shows balance $1 and reserved $2
  local w=""
  for _ in $(seq 1 30); do
    w=$(curl -fs "$API/api/wallets/me" -H "Authorization: Bearer $TOKEN" || true)
    [ "$(json_field balance <<< "$w")" = "$1" ] && [ "$(json_field reserved <<< "$w")" = "$2" ] && return 0
    sleep 1
  done
  fail "wallet is $w, expected balance $1 and reserved $2"
}
bet() { # bet <idempotency-key> <amount> [side] -> prints the response body; retries while the market snapshot is missing
  local body code
  for _ in $(seq 1 20); do
    body=$(curl -s -w '\n%{http_code}' -X POST "$API/api/bets" -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $1" \
      -H 'Content-Type: application/json' -d "{\"marketId\":\"$MARKET_ID\",\"side\":\"${3:-TRUE}\",\"amount\":$2}")
    code=$(tail -n1 <<< "$body")
    [ "$code" = 202 ] && { sed '$d' <<< "$body"; return 0; }
    [ "$code" = 404 ] || fail "bet returned $code: $(sed '$d' <<< "$body")"
    sleep 1
  done
  fail "market $MARKET_ID never reached the bet service"
}

echo "9. place a bet of 100: the wallet debits it after bet.placed"
KEY="smoke-$RANDOM-$RANDOM"
BET_ID=$(bet "$KEY" 100 | json_field betId)
[ -n "$BET_ID" ] || fail "bet returned no betId"
wallet_is $((STARTING_TOKENS - 100)) 0

echo "10. replaying the Idempotency-Key returns the same bet and does not charge again"
[ "$(bet "$KEY" 100 | json_field betId)" = "$BET_ID" ] || fail "replay returned another bet"
wallet_is $((STARTING_TOKENS - 100)) 0

echo "11. cancel a second bet: its stake comes back"
BET2=$(bet "smoke-$RANDOM-$RANDOM" 50 | json_field betId)
curl -fs -X DELETE "$API/api/bets/$BET2" -H "Authorization: Bearer $TOKEN" | grep -q CANCELLED || fail "cancel failed"
wallet_is $((STARTING_TOKENS - 100)) 0

echo "11b. a second user bets 300 on FALSE"
A_TOKEN=$TOKEN
B_EMAIL="smoke-b-$(date +%s)-$RANDOM@example.com"
B_ID=$(curl -fs -X POST "$API/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$B_EMAIL\",\"password\":\"$PASSWORD\",\"displayName\":\"Smoke B\"}" | json_field userId)
[ -n "$B_ID" ] || fail "register B failed"
TOKEN=$(curl -fs -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$B_EMAIL\",\"password\":\"$PASSWORD\"}" | json_field accessToken)
[ -n "$TOKEN" ] || fail "login B failed"
B_TOKEN=$TOKEN
wallet_is "$STARTING_TOKENS" 0
bet "smoke-b-$RANDOM" 300 FALSE > /dev/null
wallet_is $((STARTING_TOKENS - 300)) 0
TOKEN=$A_TOKEN

echo "12. market.frozen for $MARKET_ID reaches Kafka (freezeAt = now + ${FREEZE_IN}s)"
FROZEN=""
for _ in $(seq 1 $(( FREEZE_IN / 5 + 12 ))); do
  if "${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 \
      --topic market.frozen --from-beginning --timeout-ms 5000 2>/dev/null | grep -q "$MARKET_ID"; then
    FROZEN=yes; break
  fi
done
[ -n "$FROZEN" ] || fail "market.frozen for $MARKET_ID not found in Kafka"

echo "13. no bets after freeze"
[ "$(status -X POST "$API/api/bets" -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: late-$RANDOM" \
  -H 'Content-Type: application/json' -d "{\"marketId\":\"$MARKET_ID\",\"side\":\"TRUE\",\"amount\":10}")" = 409 ] \
  || fail "a bet after freeze was not rejected with 409"

echo "14. admin resolves TRUE once the end task has ended the market"
RESOLVED=""
for _ in $(seq 1 30); do
  code=$(status -X POST "$API/api/markets/$MARKET_ID/resolve" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H 'Content-Type: application/json' -d '{"winningSide":"TRUE"}')
  [ "$code" = 200 ] && { RESOLVED=yes; break; }
  [ "$code" = 409 ] || fail "resolve returned $code"
  sleep 2
done
[ -n "$RESOLVED" ] || fail "market never reached ENDED"

echo "15. settlement pays the winner; the loser keeps nothing"
POOL=400 # A: 100 on TRUE (the 50 bet was cancelled), B: 300 on FALSE
PAYOUT=$(( POOL * (10000 - HOUSE_EDGE_BPS) / 10000 ))
TOKEN=$A_TOKEN; wallet_is $((STARTING_TOKENS - 100 + PAYOUT)) 0
TOKEN=$B_TOKEN; wallet_is $((STARTING_TOKENS - 300)) 0
TOKEN=$A_TOKEN

# eventually <description> <command...>: retry a check for up to 30 s (the read side is async).
eventually() {
  local what=$1; shift
  for _ in $(seq 1 30); do "$@" > /dev/null 2>&1 && return 0; sleep 1; done
  fail "$what"
}
market_view_ok() {
  local m; m=$(curl -fs "$API/api/markets/$MARKET_ID")
  [ "$(json_field status <<< "$m")" = RESOLVED ] && [ "$(json_field totalPool <<< "$m")" = "$POOL" ]
}
my_bet_is() { curl -fs "$API/api/me/bets?status=$1" -H "Authorization: Bearer $TOKEN" | grep -q "\"marketId\":\"$MARKET_ID\""; }
net_profit_is() { # net_profit_is <userId> <amount>
  [ "$(curl -fs "$API/api/leaderboard?window=all" | grep -o "\"userId\":\"$1\"[^}]*" | json_field netProfit)" = "$2" ]
}

echo "16. the public market view shows RESOLVED and the pool without a token"
eventually "market view never showed RESOLVED with pool $POOL" market_view_ok

echo "17. my bets: A won, B lost"
TOKEN=$A_TOKEN; eventually "A's bet is not WON" my_bet_is WON
TOKEN=$B_TOKEN; eventually "B's bet is not LOST" my_bet_is LOST
TOKEN=$A_TOKEN

echo "18. leaderboard: A +$((PAYOUT - 100)), B -300"
eventually "A's net profit is not $((PAYOUT - 100))" net_profit_is "$USER_ID" $((PAYOUT - 100))
eventually "B's net profit is not -300" net_profit_is "$B_ID" -300

echo "OK: user $USER_ID has $BALANCE tokens; gateway auth works; market $MARKET_ID took bets, froze on schedule and paid $PAYOUT to the winner"
