#!/usr/bin/env bash
#
# 3.5 Sync exchange and authentication matrix.
#
# Proves the boundary that protects /api/sync/*: a session cookie alone is not
# enough, the key must exist, must match, and must survive a restart. Also checks
# that an exchange with a valid key is accepted and the empty payload round-trips.
#
# Usage: JAR=... scripts/it/it-sync.sh
#
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

JAR="${JAR:-}"
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  echo "FAIL  set JAR to the packaged uber-jar" >&2
  exit 2
fi

it_begin
trap it_cleanup EXIT

it_start "$JAR"
PASSWORD="$(bootstrap_password)"
login_status admin "$PASSWORD" >/dev/null
post_json /api/auth/change-password "{\"currentPassword\":\"$PASSWORD\",\"newPassword\":\"sync-test-pass-2026\"}" >/dev/null
ADMIN_PASS='sync-test-pass-2026'
login_status admin "$ADMIN_PASS" >/dev/null

# sync_call <method> <path> [key] [body] -> HTTP code, body in post.json.
# Deliberately sends no cookie: this is what a peer instance looks like.
sync_call() {
  local method="$1" path="$2" key="${3:-}" body="${4:-}"
  local args=(-s -o "$IT_WORK/post.json" -w '%{http_code}' -X "$method")
  [ -n "$key" ] && args+=(-H "X-JMedia-Sync-Key: $key")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  curl "${args[@]}" "$IT_BASE$path"
}

check "ping without a key is refused" 401 "$(sync_call GET /api/sync/ping)"
check "ping with a guessed key is refused" 401 "$(sync_call GET /api/sync/ping jm_deadbeef)"
check "a session cookie alone does not authorise sync" 401 "$(curl -s -b "$IT_COOKIE" -o "$IT_WORK/post.json" -w '%{http_code}' "$IT_BASE/api/sync/ping")"

# No key has been generated yet, so even the right shape must fail.
check "sync is closed until a key is generated" 401 "$(sync_call GET /api/sync/ping jm_0000)"

check "admin can generate a sync key" 200 "$(post_json /api/sync/settings/generate-api-key '{}')"
KEY="$(sed -n 's/.*"apiKey":"\(jm_[a-f0-9]*\)".*/\1/p' "$IT_WORK/post.json")"
if [ -z "$KEY" ]; then
  echo "FAIL  no apiKey in the generate response: $(body)" >&2
  it_evidence
  exit 1
fi
printf 'PASS  generated sync key has the expected shape (%s chars)\n' "${#KEY}"

check "ping with the generated key succeeds" 200 "$(sync_call GET /api/sync/ping "$KEY")"
check_contains "ping returns pong" 'pong' "$(body)"
check "a sync key cannot read sync settings" 401 "$(sync_call GET /api/sync/settings "$KEY")"
check "a sync key cannot trigger a sync" 401 "$(sync_call POST /api/sync/trigger "$KEY" '{}')"
check "ping with a wrong key is still refused" 401 "$(sync_call GET /api/sync/ping "${KEY}x")"
check "exchange without a key is refused" 401 "$(sync_call POST /api/sync/exchange)"
check "exchange with a wrong key is refused" 401 "$(sync_call POST /api/sync/exchange jm_wrong '{}')"
check "exchange with the key is accepted" 200 "$(sync_call POST /api/sync/exchange "$KEY" '{}')"
check_contains "empty exchange returns an empty result set" '"updatedIds":[]' "$(body)"
check_contains "empty exchange reports no errors" '"errors":[]' "$(body)"

echo "restarting to prove the key is persisted"
it_stop
it_start "$JAR"
check "key still works after a restart" 200 "$(sync_call GET /api/sync/ping "$KEY")"
check "wrong key still fails after a restart" 401 "$(sync_call GET /api/sync/ping jm_still_wrong)"

it_finish
