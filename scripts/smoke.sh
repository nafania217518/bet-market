#!/usr/bin/env bash
# End-to-end smoke check. Run after `docker compose up -d --wait`. Grows every milestone.
# Talks only to the API gateway.
# M1: register -> login -> wallet shows the starting balance.
# M2: gateway auth (JWT), identity headers, correlation id, seeded admin.
set -euo pipefail

API="${API:-http://localhost:8080}"
STARTING_TOKENS="${STARTING_TOKENS:-1000}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@bet-market.local}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin-password}"
ADMIN_ID="00000000-0000-0000-0000-000000000001"

fail() { echo "FAIL: $*" >&2; exit 1; }
json_field() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p"; }
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

# Print the JSON payload of a JWT (base64url, unpadded).
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

echo "OK: user $USER_ID has $BALANCE tokens; gateway auth works"
