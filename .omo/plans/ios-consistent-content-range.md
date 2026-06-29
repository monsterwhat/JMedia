# Plan: Fix iOS fMP4 Content-Range Consistency

## Status
- **2026-06-21**: Initial plan drafted — consistent estimated total using `videoFile.length() * 1.10`
- **2026-06-23 21:40**: Deployed and tested. iOS starts playing now ✅ but freezes at ~10 min ❌
- **2026-06-23 21:47**: Root cause identified: `start > end` reset at line 490-493

## Goal
Fix Safari Content-Range consistency bug so fMP4 video playback works fully on iOS (no 10-min freeze).

## Constraints
- Only VideoAPI.java changes (no JS, no TranscodingService)
- Build must compile with zero errors
- Must not break Chrome/Firefox playback

## Diagnosis of the 10-minute freeze

### What the fix achieved
- Content-Range total is stable at `973652423` across all responses ✅
- Safari probe (bytes=0-1) matches first data response → **playback starts** ✅

### What still fails
- Safari requests data up to `973652422` (the estimated total)
- The actual transcode output is ~811MB (smaller than 973MB estimate)
- When Safari requests `bytes=820000000-973652422`:
  1. Line 489 clamps end to `fileLength - 1` (810999999)
  2. Line 490 detects `start > end` → resets `start = 0` ← **THE BUG**
  3. Safari receives duplicate data from byte 0 (already buffered)
  4. Safari gets confused → stops fetching → buffer runs out at ~10 min → freeze

### Root cause
The `start > end` reset at lines 490-493 was designed for static files (streamDirectFile) where the file size never changes. For streaming/transcoding, it corrupts the playback by sending Safari data from the wrong offset.

## Fix: Three edits in VideoAPI.java

### Edit A — Lines 488-493: Never reset `start = 0`

```java
// OLD (broken):
if (end >= fileLength) end = fileLength - 1;
if (start > end) {
    start = 0;
    end = fileLength - 1;
}

// NEW:
// Save original end from the range header before clamping
long originalEnd = end;

// For temp/streaming files, do NOT reset start=0 when start > end.
// That would send Safari duplicate data from byte 0 and confuse it.
if (end >= fileLength && start < fileLength) {
    end = fileLength - 1;
}
if (start > end) {
    // Requested range starts past current EOF — keep original end
    // as the wait target so waitForFile blocks until data is produced.
    end = originalEnd;
}
```

### Edit B — Lines 596-597: Use actual file size when transcode finished

```java
// OLD: always uses inflated estimate (causes Safari to request past real EOF)
long finishedTotal = Math.max(estimatedFinalSize, currentFileSize);

// NEW: after transcode finishes, the actual file size IS the correct total
long finishedTotal = transcodeFinished ? currentFileSize : Math.max(estimatedFinalSize, currentFileSize);
```

### Edit C — Line 523: Handle contentLength when start > currentFileSize

```java
// OLD: single expression assumes start < currentFileSize
long contentLength = end - start + 1;

// NEW: guard against negative contentLength when start > currentFileSize
long contentLength = start < currentFileSize ? Math.min(end, currentFileSize - 1) - start + 1 : 0;
```

## Why this works
- **During transcode (file growing):** total stays at 973MB estimate → Safari probe matches first data response → playback starts ✅
- **Safari requests data past current EOF while transcode running:** original end preserved as wait target → `waitForFile` blocks until file grows → data served from correct offset ✅
- **After transcode finishes (file stops growing):** total switches from 973MB to actual file size (e.g. 811MB) → Safari sees smaller total in next response → never requests non-existent data → no freeze ✅
- The total change (973M→811M) happens **mid-playback**, not between probe and first data → Safari is already committed to playing, handles it gracefully ✅

## Verification
1. Build: `mvn compile` (zero errors)
2. iOS test: seek to mid-episode, play for >12 minutes, verify no freeze
3. Regression: ensure Chrome/Firefox still play start-to-finish
