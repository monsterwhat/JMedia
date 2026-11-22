#!/usr/bin/env bash
#
# 3.2 Fresh bootstrap and forced password change.
#
# Starts the packaged jar against an empty home directory and proves that:
#   - no shipped default password works;
#   - the generated password is printed once and works;
#   - the account is blocked everywhere except the change-password flow;
#   - the policy rejects short and wrong attempts;
#   - changing the password lifts the block;
#   - after a restart the old password is dead and the new one still works.
#
# Usage: JAR=path/to/JMedia-runner.jar scripts/it/it-bootstrap.sh
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

echo "starting a fresh instance on port $IT_PORT with home $IT_WORK/home"
it_start "$JAR"

PASSWORD="$(bootstrap_password)"
if [ -z "$PASSWORD" ]; then
  echo "FAIL  no bootstrap password in the startup log" >&2
  it_evidence
  exit 1
fi
printf 'PASS  bootstrap admin created with a generated password (%s chars)\n' "${#PASSWORD}"

check "shipped default password is rejected" 401 "$(login_status admin changeme1234)"
check "second legacy admin account does not exist" 401 "$(login_status admin2 'custom password1234')"

check "generated password is accepted" 200 "$(login_status admin "$PASSWORD")"
check_contains "login reports the pending change" '"mustChangePassword":true' "$(body)"

check "API is blocked until the password changes" 403 "$(get_status /api/users)"
check_contains "block is the dedicated code" 'PASSWORD_CHANGE_REQUIRED' "$(body)"

# A page request redirects instead of returning JSON.
PAGE_CODE=$(curl -s -b "$IT_COOKIE" -o /dev/null -w '%{http_code}' "$IT_BASE/")
check "application page redirects to the password page" 307 "$PAGE_CODE"
check "change-password page is reachable" 200 "$(get_status /change-password.html)"

check "short new password is refused" 400 "$(post_json /api/auth/change-password "{\"currentPassword\":\"$PASSWORD\",\"newPassword\":\"short\"}")"
check "wrong current password is refused" 403 "$(post_json /api/auth/change-password "{\"currentPassword\":\"nope\",\"newPassword\":\"a-long-enough-password\"}")"
check "reusing a shipped default is refused" 400 "$(post_json /api/auth/change-password "{\"currentPassword\":\"$PASSWORD\",\"newPassword\":\"changeme1234\"}")"

NEW_PASSWORD='integration-pass-2026'
check "valid change succeeds" 200 "$(post_json /api/auth/change-password "{\"currentPassword\":\"$PASSWORD\",\"newPassword\":\"$NEW_PASSWORD\"}")"
check "API is unblocked after the change" 200 "$(get_status /api/users)"
check "application page loads after the change" 200 "$(get_status /)"

echo "restarting against the same home to prove persistence"
it_stop
it_start "$JAR"

check "generated password no longer works after restart" 401 "$(login_status admin "$PASSWORD")"
check "chosen password still works after restart" 200 "$(login_status admin "$NEW_PASSWORD")"
check_contains "restart does not re-arm the forced change" '"mustChangePassword":false' "$(body)"
check "API stays unblocked after restart" 200 "$(get_status /api/users)"

it_finish
