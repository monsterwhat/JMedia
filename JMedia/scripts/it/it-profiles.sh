#!/usr/bin/env bash
#
# Multi-profile isolation.
#
# Two accounts, two sessions. Proves that playback state, per-profile settings
# and playlists cannot cross accounts, and that the profile id in the URL is not
# a way to select somebody else's playback session.
#
# Usage: JAR=... scripts/it/it-profiles.sh
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
ADMIN_COOKIE="$IT_WORK/admin.cookies"
USER_COOKIE="$IT_WORK/user.cookies"
IT_COOKIE="$ADMIN_COOKIE"

it_start "$JAR"
PASSWORD="$(bootstrap_password)"
login_status admin "$PASSWORD" >/dev/null
post_json /api/auth/change-password "{\"currentPassword\":\"$PASSWORD\",\"newPassword\":\"isolation-admin-2026\"}" >/dev/null
login_status admin 'isolation-admin-2026' >/dev/null
cp "$ADMIN_COOKIE" "$IT_COOKIE" 2>/dev/null || true

# Admin profile id (the bootstrap account owns exactly one).
get_status /api/profiles >/dev/null
ADMIN_PROFILE="$(sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p' "$IT_WORK/post.json" | head -1)"
if [ -z "$ADMIN_PROFILE" ]; then
  echo "FAIL  could not read the admin profile id: $(body)" >&2
  it_evidence
  exit 1
fi
printf 'PASS  admin profile is %s\n' "$ADMIN_PROFILE"

check "admin can create a second account" 200 "$(post_json /api/users \
  '{"username":"isolation","password":"isolation-user-2026","groupName":"user"}')"

# Switch to the second account with a clean cookie jar.
rm -f "$USER_COOKIE"
IT_COOKIE="$USER_COOKIE"
check "second account can sign in" 200 "$(login_status isolation 'isolation-user-2026')"

get_status /api/profiles >/dev/null
USER_PROFILE="$(sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p' "$IT_WORK/post.json" | head -1)"
PROFILE_COUNT="$(grep -o '"id":' "$IT_WORK/post.json" | wc -l | tr -d ' ')"
check "second account sees only its own profile" 1 "$PROFILE_COUNT"
if [ "$USER_PROFILE" = "$ADMIN_PROFILE" ]; then
  echo "FAIL  the second account resolved to the admin profile" >&2
  it_evidence
  exit 1
fi
printf 'PASS  second account profile is %s (distinct from admin)\n' "$USER_PROFILE"

# Playback endpoints take the profile from the session, not the path.
get_status "/api/music/playback/state/$ADMIN_PROFILE" >/dev/null
SERVED_PROFILE="$(sed -n 's/.*"profileId":\([0-9][0-9]*\).*/\1/p' "$IT_WORK/post.json" | head -1)"
check "a foreign profile id in the path is ignored" "$USER_PROFILE" "$SERVED_PROFILE"

# Volume is per profile; writing through the wrong path must not move the admin.
check "volume call on a foreign profile id is accepted for the caller" 200 "$(post_json "/api/music/playback/volume/$ADMIN_PROFILE/0.25" '{}')"
get_status "/api/music/playback/state/$USER_PROFILE" >/dev/null
USER_VOLUME="$(sed -n 's/.*"volume":\([0-9.]*\).*/\1/p' "$IT_WORK/post.json" | head -1)"
IT_COOKIE="$ADMIN_COOKIE"
get_status "/api/music/playback/state/$ADMIN_PROFILE" >/dev/null
ADMIN_VOLUME="$(sed -n 's/.*"volume":\([0-9.]*\).*/\1/p' "$IT_WORK/post.json" | head -1)"
check "the caller's own volume changed" "0.25" "$USER_VOLUME"
if [ "$ADMIN_VOLUME" = "0.25" ]; then
  echo "FAIL  the admin volume followed another profile's write ($ADMIN_VOLUME)" >&2
  it_evidence
  exit 1
fi
printf 'PASS  admin volume unaffected (%s)\n' "$ADMIN_VOLUME"

# Per-profile settings are owner-checked.
IT_COOKIE="$USER_COOKIE"
check "second account cannot write the admin sidebar setting" 403 "$(post_json "/api/settings/$ADMIN_PROFILE/sidebar-position" '{"position":"right"}')"
check "second account can write its own sidebar setting" 200 "$(post_json "/api/settings/$USER_PROFILE/sidebar-position" '{"position":"right"}')"
check "second account cannot list users" 403 "$(get_status /api/users)"

# Playlists: the path id must not be a way into another account's data.
IT_COOKIE="$ADMIN_COOKIE"
check "admin creates a playlist" 201 "$(post_json /api/music/playlists/ "{\"name\":\"admin-only\",\"profileId\":$ADMIN_PROFILE}")"
ADMIN_PLAYLIST="$(sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p' "$IT_WORK/post.json" | head -1)"
if [ -z "$ADMIN_PLAYLIST" ]; then
  echo "FAIL  no playlist id in the create response: $(body)" >&2
  it_evidence
  exit 1
fi
printf 'PASS  admin playlist id is %s\n' "$ADMIN_PLAYLIST"

IT_COOKIE="$USER_COOKIE"
check "second account cannot list the admin playlists" 403 "$(get_status "/api/music/playlists/$ADMIN_PROFILE")"
check "second account cannot create into the admin profile" 403 "$(post_json /api/music/playlists/ "{\"name\":\"stolen\",\"profileId\":$ADMIN_PROFILE}")"
check "second account cannot rename the admin playlist" 403 "$(curl -s -b "$IT_COOKIE" -o /dev/null -w '%{http_code}' \
  -X PUT -H 'Content-Type: application/json' -d '{"name":"renamed"}' "$IT_BASE/api/music/playlists/$ADMIN_PLAYLIST")"
check "second account cannot delete the admin playlist" 403 "$(curl -s -b "$IT_COOKIE" -o /dev/null -w '%{http_code}' \
  -X DELETE "$IT_BASE/api/music/playlists/$ADMIN_PLAYLIST")"
check "second account can list its own playlists" 200 "$(get_status "/api/music/playlists/$USER_PROFILE")"
check "second account can create its own playlist" 201 "$(post_json /api/music/playlists/ "{\"name\":\"mine\",\"profileId\":$USER_PROFILE}")"
USER_PLAYLIST="$(sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p' "$IT_WORK/post.json" | head -1)"
if [ -z "$USER_PLAYLIST" ]; then
  echo "FAIL  no playlist id in the create response: $(body)" >&2
  it_evidence
  exit 1
fi
check "second account can rename its own playlist" 200 "$(curl -s -b "$IT_COOKIE" -o "$IT_WORK/post.json" -w '%{http_code}' \
  -X PUT -H 'Content-Type: application/json' -d '{"name":"mine-renamed"}' "$IT_BASE/api/music/playlists/$USER_PLAYLIST")"
check_contains "rename applied to the right playlist" 'mine-renamed' "$(body)"

IT_COOKIE="$ADMIN_COOKIE"
check "admin can still read its own playlists" 200 "$(get_status "/api/music/playlists/$ADMIN_PROFILE")"

# Deletion of a global playlist is decided by the *globally active profile*
# (PlaylistService.isMainProfileActive), not by the caller, so whether an owner's
# delete removes the row depends on unrelated state. The test therefore does not
# assert either outcome; the behaviour is documented in docs/access-control.md.

IT_COOKIE="$ADMIN_COOKIE"
check "admin can still delete its own playlist" 200 "$(curl -s -b "$IT_COOKIE" -o /dev/null -w '%{http_code}' \
  -X DELETE "$IT_BASE/api/music/playlists/$ADMIN_PLAYLIST")"

it_finish
