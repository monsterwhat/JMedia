# ios-streaming-investigation - Work Plan

## TL;DR (For humans)

**What you'll get:** Console.debug logging added to the JS player (gated on iOS) at every critical decision point — stall timer firing, play() success/failure, fullscreen transitions — plus trace IDs linking client requests to server logs. You run the test on your own device and read the logs.

**Why this approach:** Read-only instrumentation — zero risk of introducing new bugs. The trace IDs connect client events to server log lines so you can see exactly what sequence leads to the stream dropping.

**What it will NOT do:** No build, no deploy, no simulator, no fix implementation. Just the logging code.

**Effort:** Short (3 files to instrument)
**Risk:** Low — instrumentation is read-only, gated behind isIOS()
**Decisions to sanity-check:** None needed — just adds logging behind existing isIOS() check

Your next move: Approve, and I'll execute the instrumentation.

---

> TL;DR (machine): Short effort, Low risk. 3 todos: instrument JS (3 files) + add trace IDs + enhance server logging.

## Scope
### Must have
- Add iOS-detecting console.debug() instrumentation at every critical decision point in simple-player.js, EventBinder.js, and FullscreenManager.js
- Add a per-request trace ID (query parameter from JS to server) to correlate client-side events with server-side logs
- Add server-side request logging enhancement in VideoAPI.java to log every incoming request with its trace ID

### Must NOT have
- No build, deploy, or simulator setup
- No log capture or analysis
- No fix implementation
- No changes to production logic (read-only instrumentation)
- No refactoring of the player codebase
- No FFmpeg or transcoding parameter changes
- No UI changes
- No changes to non-iOS code paths
- No new dependencies

## Verification strategy
- Test decision: manual inspection — no automated tests needed for logging-only changes
- Evidence: grep output confirming all instrumented points are gated behind iOS

## Execution strategy
### Parallel execution waves
All three todos can run in parallel.

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| 1. Client instrumentation | none | none | 2, 3 |
| 2. Server trace ID plumbing | none | none | 1, 3 |
| 3. Server request logging | none | none | 1, 2 |

## Todos

- [ ] 1. Instrument client-side JS with iOS-autodetected console.debug logging
  What to do / Must NOT do:
  - In simple-player.js: Add console.debug('[iOS-DEBUG]', ...) calls gated behind `if (PlayerUtils.isIOS())` at:
    - After `this.video.src = ...` is set (lines 106, 109, 140): log the exact src URL + start offset
    - Before `this.video.play()` (lines 115, 145): log "Calling play()"
    - On play rejection .catch(): log the rejection error
    - Add a one-shot 'playing' event listener before play(): log "Playback started"
  - In EventBinder.js:
    - In 'play' event handler (line 18): log "Play event fired, setting 20s stall timer"
    - Inside stall timer callback (line 24): log all condition values (currentTime, paused, _hasPlayedData, _destroyed, _streamFallbackCount, _maxStreamFallbacks). If reloading, log the new src URL
    - In 'timeupdate' handler (line 77): log "_hasPlayedData set to true at currentTime=X" (only first time)
    - In 60s waiting-stall handler (line 111): log same condition values
    - On fullscreen button click (line 360): log "Fullscreen requested"
  - In FullscreenManager.js:
    - In requestFullscreen() (line 52): log "requestFullscreen: iOS path taken" or "CSS path" or "native path"
    - In doEnterFullscreen() (line 79): log each attempt (requestFullscreen, webkitEnterFullscreen) and whether it succeeded or failed
    - In onIOSVideoFullscreenStart() (line 141): log the event
    - In onIOSVideoFullscreenEnd() (line 151): log the event and whether resume play() will be called
  - Must NOT do: change any production logic, alter any condition, modify any timer, change any UI, affect non-iOS behavior
  References:
  - simple-player.js:81-175 (init), 105-117 (transcoded src+play), 138-148 (direct)
  - EventBinder.js:18-51 (play handler + stall timer), 61-85 (timeupdate), 107-131 (60s waiting stall), 360 (fullscreen)
  - FullscreenManager.js:52-123 (requestFullscreen), 141-163 (iOS native fullscreen events)
  - Utils.js:4-8 (isIOS)
  Acceptance criteria:
  - grep -n 'console.debug.*iOS-DEBUG' in simple-player.js EventBinder.js FullscreenManager.js returns at least 10 unique lines
  - Each debug line is inside an `if (PlayerUtils.isIOS() || p.utils.isIOS())` block or equivalent guard
  QA:
  - Happy: On iOS, JS console shows [iOS-DEBUG] lines chronologically matching the playback flow
  - Failure: On Windows/Firefox, console has zero [iOS-DEBUG] lines
  Commit: N (instrumentation only)

- [ ] 2. Add per-request trace ID from client to server
  What to do / Must NOT do:
  - In simple-player.js lines 104-109 and 138-140: When building the stream URL, append `&trace=${Date.now()}_${Math.random().toString(36).slice(2,8)}`
  - In EventBinder.js line 38-40 (stall timer reload): Append fresh `&trace=` to the reload URL
  - In StreamManager.js lines 137, 139, 163 (initDirectStream): Append `&trace=` to the src URL
  - In StreamManager.js lines 306, 313 (switchAudioTrack, _doServerSeek): Append `&trace=`
  - Must NOT do: Alter existing query params, remove start/quality/audioTrack params, add trace to non-stream URLs
  References:
  - simple-player.js:104-109, 138-140
  - EventBinder.js:38-40
  - StreamManager.js:137, 139, 163, 306, 313
  Acceptance criteria:
  - grep -n 'trace=' in simple-player.js EventBinder.js StreamManager.js returns matches at every stream URL construction point
  - No non-stream URLs (subtitle, storyboard, API calls) have trace param
  QA:
  - Happy: Network tab shows /api/video/stream/...&trace=1687520400000_abc123
  - Failure: trace param not present or malformed
  Commit: N

- [ ] 3. Add server-side request logging with trace ID
  What to do / Must NOT do:
  - In VideoAPI.java streamVideo() method (around line 300): After extracting query params, extract 'trace'. Prepend "[TRACE={trace}]" to the existing LOG.info call that logs the stream request
  - In VideoAPI.java streamRemuxedMKV() (line 346): In the probe handler (lines 352-372), add trace ID to the existing LOG.info calls
  - In VideoAPI.java streamRemuxedMKVDirect() (line 404): Add trace ID to error logging
  - In VideoAPI.java streamFromTempFile() (line 426): Add trace ID to all existing LOG calls
  - Must NOT do: Change any streaming logic, alter response headers, modify cache behavior
  References:
  - VideoAPI.java:300-344 (streamVideo), 346-372 (probe handler), 389-401 (temp file), 404-424 (direct pipe), 426-460+ (streamFromTempFile)
  Acceptance criteria:
  - grep -n 'TRACE=' in VideoAPI.java confirms trace param is extracted and logged in streamVideo() and streamRemuxedMKV()
  QA:
  - Happy: Request with &trace=test123 produces server log containing [TRACE=test123]
  - Failure: Request without trace param logs same lines without [TRACE=] prefix
  Commit: N

## Final verification wave
- [ ] F1. Plan compliance audit — only instrumentation code was added
- [ ] F2. Code quality review — all debug lines gated behind isIOS(), no production logic changed
- [ ] F3. Scope fidelity — no build/deploy/fix/simulator work done

## Commit strategy
No commit — instrumentation-only changes; user can squash if they choose.

## Success criteria
1. grep confirms console.debug lines exist in all three JS files, gated behind iOS
2. grep confirms trace= param appended to every stream URL construction
3. grep confirms TRACE= logging added to VideoAPI.java stream methods
<!-- OMO_INTERNAL_INITIATOR -->
