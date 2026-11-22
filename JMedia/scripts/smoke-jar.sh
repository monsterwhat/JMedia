#!/usr/bin/env bash
#
# Uber-jar smoke test. Starts the packaged jar against a throwaway home
# directory, checks that it serves the login page and that the API rejects
# unauthenticated calls, then shuts it down.
#
# It never touches the real ~/.jmedia: user.home and the log path are
# redirected into a temp directory.
#
# Usage: scripts/smoke-jar.sh path/to/JMedia-runner.jar
#
set -euo pipefail

JAR=${1:-}
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
    echo "FAIL: usage: $0 path/to/JMedia-runner.jar" >&2
    exit 2
fi

PORT=${SMOKE_PORT:-8099}
WORK=$(mktemp -d)
FAKE_HOME="$WORK/home"
mkdir -p "$FAKE_HOME"
STDOUT_LOG="$WORK/stdout.log"
FILE_LOG="$WORK/jmedia.log"
PID=""

cleanup() {
    if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
        kill "$PID" 2>/dev/null || true
        for _ in $(seq 1 20); do
            kill -0 "$PID" 2>/dev/null || break
            sleep 0.5
        done
        kill -9 "$PID" 2>/dev/null || true
    fi
    rm -rf "$WORK"
}
trap cleanup EXIT

fail() {
    echo "FAIL: $*" >&2
    echo "---- last 60 lines of stdout ----" >&2
    tail -60 "$STDOUT_LOG" >&2 || true
    if [ -f "$FILE_LOG" ]; then
        echo "---- ERROR lines from the app log ----" >&2
        grep -i "error" "$FILE_LOG" | tail -20 >&2 || true
    fi
    exit 1
}

echo "Starting $JAR on port $PORT with user.home=$FAKE_HOME"
java \
    -Duser.home="$FAKE_HOME" \
    -Dquarkus.http.port="$PORT" \
    -Dquarkus.log.file.path="$FILE_LOG" \
    -jar "$JAR" >"$STDOUT_LOG" 2>&1 &
PID=$!

READY=""
for _ in $(seq 1 120); do
    if ! kill -0 "$PID" 2>/dev/null; then
        fail "the process exited before it started listening"
    fi
    CODE=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/login.html" || true)
    if [ "$CODE" = "200" ]; then
        READY="yes"
        break
    fi
    sleep 1
done
[ -n "$READY" ] || fail "login page was not served within 120 s"

echo "PASS: login page served"

CODE=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/api/music/playback/state/1")
[ "$CODE" = "401" ] || fail "unauthenticated API call returned $CODE, expected 401"
echo "PASS: API refuses unauthenticated access"

CODE=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/change-password.html")
[ "$CODE" = "307" ] || [ "$CODE" = "302" ] || fail "change-password page returned $CODE for an anonymous visitor"
echo "PASS: password page requires a session"

if [ -f "$FILE_LOG" ]; then
    if grep -q "Created bootstrap admin" "$FILE_LOG"; then
        echo "PASS: bootstrap admin created on a fresh home"
    else
        fail "no bootstrap admin line in the fresh-install log"
    fi
fi

if grep -qE "Exception in thread|Failed to start quarkus|Uncaught" "$STDOUT_LOG"; then
    fail "startup threw an unhandled error"
fi
if grep -qE "\[ERROR\]| ERROR " "$STDOUT_LOG"; then
    echo "WARN: startup logged ERROR lines (inspect before trusting this run):"
    grep -E "\[ERROR\]| ERROR " "$STDOUT_LOG" | tail -10
else
    echo "PASS: no ERROR during startup"
fi
echo "smoke test passed"
