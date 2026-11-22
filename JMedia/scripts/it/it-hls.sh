#!/usr/bin/env bash
#
# 3.4 HLS session lifecycle against real ffmpeg.
#
# Creates a three-second clip, points the video library at it, scans, then drives
# the HLS endpoints: session creation, master playlist, segment production, and
# destruction (after which the playlist must be gone). This is the packaged
# runtime plus a real encoder, which the unit tests cannot cover.
#
# Usage: JAR=... scripts/it/it-hls.sh
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
if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "FAIL  ffmpeg is required for this test" >&2
  exit 2
fi

it_begin
trap it_cleanup EXIT

MEDIA="$IT_WORK/media"
mkdir -p "$MEDIA"
echo "generating a 3 s clip in $MEDIA"
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc=size=320x240:rate=15:duration=3" \
  -f lavfi -i "sine=frequency=440:duration=3" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -shortest \
  "$MEDIA/it-clip.mp4"
[ -s "$MEDIA/it-clip.mp4" ] || { echo "FAIL  ffmpeg produced no file" >&2; exit 1; }

it_start "$JAR"
PASSWORD="$(bootstrap_password)"
login_status admin "$PASSWORD" >/dev/null
post_json /api/auth/change-password "{\"currentPassword\":\"$PASSWORD\",\"newPassword\":\"hls-test-pass-2026\"}" >/dev/null
login_status admin 'hls-test-pass-2026' >/dev/null

# Point the library at the clip's directory (form encoded).
MEDIA_FORM="$(java_path "$MEDIA")"
check "video library path is accepted" 200 "$(curl -s -b "$IT_COOKIE" -o "$IT_WORK/post.json" -w '%{http_code}' \
  -X POST -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode "videoLibraryPathInput=$MEDIA_FORM" \
  "$IT_BASE/api/settings/1/video-library-path")"

check "video scan starts" 200 "$(curl -s -b "$IT_COOKIE" -o "$IT_WORK/post.json" -w '%{http_code}' \
  -X POST "$IT_BASE/api/video/scan?mode=full")"

VIDEO_ID=""
for _ in $(seq 1 90); do
  curl -s -b "$IT_COOKIE" -o "$IT_WORK/needs.json" "$IT_BASE/api/video/manage/needs-attention"
  VIDEO_ID="$(sed -n 's/.*data-video-id="\([0-9][0-9]*\)".*/\1/p' "$IT_WORK/needs.json" | head -1)"
  [ -n "$VIDEO_ID" ] && break
  sleep 1
done
if [ -z "$VIDEO_ID" ]; then
  echo "FAIL  the scanned clip never appeared in the library" >&2
  it_evidence
  exit 1
fi
printf 'PASS  scan created video id %s\n' "$VIDEO_ID"

check "HLS session is created" 200 "$(post_json "/api/hls/session/$VIDEO_ID" '{}')"
SESSION_ID="$(sed -n 's/.*"sessionId":"\([^"]*\)".*/\1/p' "$IT_WORK/post.json")"
if [ -z "$SESSION_ID" ]; then
  echo "FAIL  no sessionId in the HLS response: $(body)" >&2
  it_evidence
  exit 1
fi
printf 'PASS  HLS session issued (%s)\n' "$SESSION_ID"

check "master playlist is served" 200 "$(get_status "/api/hls/master/$SESSION_ID.m3u8")"
check_contains "master playlist is an m3u8" '#EXTM3U' "$(body)"

# The master points at a variant; wait for it to yield at least one segment.
SEGMENTS=0
for _ in $(seq 1 30); do
  VARIANT="$(sed -n 's#.*\(playlist/[^"]*\.m3u8\).*#\1#p' "$IT_WORK/post.json" | head -1)"
  if [ -z "$VARIANT" ]; then
    VARIANT="$(sed -n 's/^\(.*\.m3u8\).*/\1/p' "$IT_WORK/post.json" | grep -v '^#' | head -1)"
  fi
  if [ -n "$VARIANT" ]; then
    case "$VARIANT" in
      http*) VARIANT_PATH="$VARIANT" ;;
      /*) VARIANT_PATH="$VARIANT" ;;
      *) VARIANT_PATH="/api/hls/$VARIANT" ;;
    esac
    curl -s -b "$IT_COOKIE" -o "$IT_WORK/variant.m3u8" "$IT_BASE$VARIANT_PATH"
    SEGMENTS=$(grep -c '^[^#].*\.\(ts\|m4s\)' "$IT_WORK/variant.m3u8" 2>/dev/null || true)
    [ "$SEGMENTS" -gt 0 ] && break
  fi
  sleep 1
done
if [ "$SEGMENTS" -gt 0 ]; then
  printf 'PASS  %s segment(s) produced by ffmpeg\n' "$SEGMENTS"
else
  printf 'FAIL  no media segment appeared within 30 s\n' >&2
  IT_FAILURES=$((IT_FAILURES + 1))
fi

check "session is destroyed" 200 "$(curl -s -b "$IT_COOKIE" -o "$IT_WORK/post.json" -w '%{http_code}' \
  -X DELETE "$IT_BASE/api/hls/session/$SESSION_ID")"
check "destroyed session no longer serves a playlist" 404 "$(get_status "/api/hls/master/$SESSION_ID.m3u8")"
check "unknown session is rejected" 404 "$(get_status "/api/hls/master/does-not-exist.m3u8")"

it_finish
