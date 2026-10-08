#!/usr/bin/env bash
# End-to-end smoke check. Run after `docker compose up -d`. Grows every milestone.
# M1: register -> login -> wallet shows the starting balance.
set -euo pipefail

USER_URL="${USER_URL:-http://localhost:8081}"
WALLET_URL="${WALLET_URL:-http://localhost:8082}"
STARTING_TOKENS="${STARTING_TOKENS:-1000}"

fail() { echo "FAIL: $*" >&2; exit 1; }

# Wait until a URL answers 200 (services start after infra).
wait_up() {
  for _ in $(seq 1 60); do
    curl -fs "$1" > /dev/null && return 0
    sleep 2
  done
  fail "$1 is not up"
}

json_field() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p"; }

wait_up "$USER_URL/actuator/health"
wait_up "$WALLET_URL/actuator/health"

EMAIL="smoke-$(date +%s)-$RANDOM@example.com"
PASSWORD="smoke-password-1"

echo "1. register $EMAIL"
USER_ID=$(curl -fs -X POST "$USER_URL/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"displayName\":\"Smoke\"}" | json_field userId)
[ -n "$USER_ID" ] || fail "register returned no userId"

echo "2. login"
TOKEN=$(curl -fs -X POST "$USER_URL/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" | json_field accessToken)
[ -n "$TOKEN" ] || fail "login returned no accessToken"
curl -fs "$USER_URL/.well-known/jwks.json" | grep -q '"kty":"RSA"' || fail "JWKS has no RSA key"

# M1 has no gateway yet: X-User-Id stands in for the header the gateway injects in M2.
echo "3. wallet (async via user.registered)"
for _ in $(seq 1 30); do
  BALANCE=$(curl -fs "$WALLET_URL/api/wallets/me" -H "X-User-Id: $USER_ID" | json_field balance || true)
  [ -n "${BALANCE:-}" ] && break
  sleep 1
done
[ "${BALANCE:-}" = "$STARTING_TOKENS" ] || fail "balance is '${BALANCE:-none}', expected $STARTING_TOKENS"

echo "OK: user $USER_ID has $BALANCE tokens"
