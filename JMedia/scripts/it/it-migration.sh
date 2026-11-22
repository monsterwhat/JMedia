#!/usr/bin/env bash
#
# 3.3 Migration from the previous schema.
#
# Starts the last release that predates the must-change column (1.46.4) against
# a fresh home, lets it create its schema and its hardcoded admin, stops it, then
# starts the current jar against the same home. Proves that:
#   - Hibernate adds the new nullable column to a populated table;
#   - startup does not fail or log an exception;
#   - the legacy admin is healed (old password dead, generated one works,
#     a change is required);
#   - the healed account survives another restart with its new password.
#
# Usage: OLD_JAR=... JAR=... scripts/it/it-migration.sh
#
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

OLD_JAR="${OLD_JAR:-}"
JAR="${JAR:-}"
if [ -z "$OLD_JAR" ] || [ ! -f "$OLD_JAR" ]; then
  echo "FAIL  set OLD_JAR to the previous release jar (e.g. 1.46.4)" >&2
  exit 2
fi
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  echo "FAIL  set JAR to the current packaged uber-jar" >&2
  exit 2
fi

it_begin
trap it_cleanup EXIT

echo "step 1/3: creating a database with the previous release"
it_start "$OLD_JAR"
check "previous release starts" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$IT_BASE/login.html")"
check "previous release accepts its shipped admin password" 200 "$(login_status admin changeme1234)"
it_stop

# The old jar leaves its databases in the shared home; the new jar opens them.
echo "step 2/3: opening that database with the current release"
it_start "$JAR"

check "current release starts against the old database" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$IT_BASE/login.html")"
check "the old admin password is no longer accepted" 401 "$(login_status admin changeme1234)"

PASSWORD="$(bootstrap_password)"
if [ -z "$PASSWORD" ]; then
  echo "FAIL  the current release did not heal the legacy admin credential" >&2
  it_evidence
  exit 1
fi
printf 'PASS  legacy admin healed with a generated password (%s chars)\n' "${#PASSWORD}"

check "healed password is accepted" 200 "$(login_status admin "$PASSWORD")"
check_contains "healed account must change its password" '"mustChangePassword":true' "$(body)"
check "healed account is blocked until then" 403 "$(get_status /api/users)"

NEW_PASSWORD='migrated-pass-2026'
check "password change works on the migrated schema" 200 "$(post_json /api/auth/change-password "{\"currentPassword\":\"$PASSWORD\",\"newPassword\":\"$NEW_PASSWORD\"}")"
check "API is usable after the change" 200 "$(get_status /api/users)"

# No exception may have been swallowed during the schema upgrade.
if [ -f "$IT_LOG" ]; then
  ERRORS=$(grep -cE ' (ERROR|FATAL) ' "$IT_LOG" || true)
  check "migration logged no ERROR line" 0 "$ERRORS"
fi

echo "step 3/3: restarting on the migrated database"
it_stop
it_start "$JAR"
check "chosen password survives a restart" 200 "$(login_status admin "$NEW_PASSWORD")"
check "API stays usable after the restart" 200 "$(get_status /api/users)"

it_finish
