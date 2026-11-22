#!/usr/bin/env bash
#
# Shared helpers for the packaged-runtime integration tests.
#
# Every test starts a real uber-jar against a throwaway home directory and a
# non-default port, so the developer's ~/.jmedia and port 8080 are untouched.
#
set -euo pipefail

IT_PORT="${IT_PORT:-8099}"
IT_BASE="http://127.0.0.1:${IT_PORT}"
IT_WORK=""
IT_PID=""
IT_LOG=""
IT_STDOUT=""
IT_COOKIE=""
IT_FAILURES=0
# CI puts the right JDK on PATH; locally JAVA_BIN can point at it explicitly.
JAVA_BIN="${JAVA_BIN:-java}"

it_begin() {
  IT_WORK="$(mktemp -d)"
  IT_LOG="$IT_WORK/jmedia.log"
  IT_STDOUT="$IT_WORK/stdout.log"
  IT_COOKIE="$IT_WORK/cookies.txt"
  mkdir -p "$IT_WORK/home"
}

# Java is a native Windows binary under Git Bash, so it needs a Windows path.
java_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$1"
  else
    printf '%s' "$1"
  fi
}

it_start() {
  local jar="$1"
  IT_PID=""
  "$JAVA_BIN" \
    -Duser.home="$(java_path "$IT_WORK/home")" \
    -Dquarkus.http.port="$IT_PORT" \
    -Dquarkus.log.file.path="$(java_path "$IT_LOG")" \
    -jar "$(java_path "$jar")" >"$IT_STDOUT" 2>&1 &
  IT_PID=$!
  it_wait_ready || { it_evidence; return 1; }
}

it_wait_ready() {
  local code
  for _ in $(seq 1 120); do
    if [ -n "$IT_PID" ] && ! kill -0 "$IT_PID" 2>/dev/null; then
      echo "FAIL  the application exited before it started listening" >&2
      return 1
    fi
    code=$(curl -s -o /dev/null -w '%{http_code}' "$IT_BASE/login.html" || true)
    [ "$code" = "200" ] && return 0
    sleep 1
  done
  echo "FAIL  the application did not serve /login.html within 120 s" >&2
  return 1
}

it_stop() {
  if [ -n "$IT_PID" ] && kill -0 "$IT_PID" 2>/dev/null; then
    kill "$IT_PID" 2>/dev/null || true
    for _ in $(seq 1 40); do
      kill -0 "$IT_PID" 2>/dev/null || break
      sleep 0.5
    done
    kill -9 "$IT_PID" 2>/dev/null || true
  fi
  IT_PID=""
}

it_cleanup() {
  it_stop
  [ -n "$IT_WORK" ] && rm -rf "$IT_WORK"
}

it_evidence() {
  echo "---- stdout (last 40 lines) ----" >&2
  tail -40 "$IT_STDOUT" 2>/dev/null >&2 || true
  if [ -f "$IT_LOG" ]; then
    echo "---- application log: ERROR lines ----" >&2
    grep -i "error" "$IT_LOG" | tail -15 >&2 || true
  fi
}

# check <name> <expected> <actual>
check() {
  if [ "$2" = "$3" ]; then
    printf 'PASS  %s\n' "$1"
  else
    printf 'FAIL  %s (expected %s, got %s)\n' "$1" "$2" "$3" >&2
    IT_FAILURES=$((IT_FAILURES + 1))
  fi
}

check_contains() {
  case "$3" in
    *"$2"*) printf 'PASS  %s\n' "$1" ;;
    *)
      printf 'FAIL  %s (expected to contain %s, got %.200s)\n' "$1" "$2" "$3" >&2
      IT_FAILURES=$((IT_FAILURES + 1))
      ;;
  esac
}

it_finish() {
  if [ "$IT_FAILURES" -ne 0 ]; then
    echo "$IT_FAILURES check(s) failed" >&2
    it_evidence
    exit 1
  fi
  echo "all checks passed"
}

# login_status <user> <password> -> HTTP code; stores cookies and the body
login_status() {
  curl -s -c "$IT_COOKIE" -o "$IT_WORK/post.json" -w '%{http_code}' \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" \
    "$IT_BASE/api/auth/login"
}

# get_status <path> -> HTTP code, stores the body for assertions
get_status() {
  curl -s -b "$IT_COOKIE" -o "$IT_WORK/post.json" -w '%{http_code}' "$IT_BASE$1"
}

post_json() {
  local path="$1" body="$2"
  curl -s -b "$IT_COOKIE" -o "$IT_WORK/post.json" -w '%{http_code}' \
    -H 'Content-Type: application/json' -d "$body" "$IT_BASE$path"
}

# body -> contents of the last response
body() {
  cat "$IT_WORK/post.json" 2>/dev/null || true
}

# Generated bootstrap password from the startup log. The creation path and the
# legacy-heal path phrase it differently; both end with the password.
bootstrap_password() {
  grep -oE '(One-time password \(shown once\)|a change is required at next login): [A-Za-z0-9_-]+' "$IT_LOG" 2>/dev/null \
    | tail -1 | awk '{print $NF}'
}
