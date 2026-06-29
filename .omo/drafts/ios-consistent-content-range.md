---
slug: ios-consistent-content-range
status: drafting
intent: clear
pending-action: write .omo/plans/ios-consistent-content-range.md
approach: Fix Safari Content-Range consistency bug in VideoAPI.java by computing a stable estimatedFinalSize once at stream start and using it as the total in every Content-Range response header, never Files.size() which changes as the transcode temp file grows.
---

# Draft: ios-consistent-content-range

## Components (topology ledger)
| id | outcome (one line) | status | evidence path |
|----|---------|--------|---------------|
| A: Content-Range consistency | Safari rejects video when Content-Range total changes between probe (bytes=0-1) and subsequent range requests because the transcode temp file grows | active | VideoAPI.java:349-376 (probe handler), 432-601 (streamFromTempFile) |
| B: Estimated size computation | Compute estimatedFinalSize = (long)(videoFile.length() * 1.10) once in streamRemuxedMKV() since -c:v copy means output ≈ input | active | TranscodingService.java:287-334 (streamRemuxedMKV) |
| C: Probe response fix | Use estimatedFinalSize instead of Files.size() for Content-Range total in probe response (line 366) | active | VideoAPI.java:366 |
| D: streamFromTempFile fix | Use Math.max(estimatedFinalSize, currentFileSize) for Content-Range total in streamFromTempFile (lines 588, 592) | active | VideoAPI.java:588, 592 |
| E: Parameter plumbing | Pass estimatedFinalSize as new parameter through streamRemuxedMKV -> streamFromTempFile chain | active | VideoAPI.java:349-407 (streamRemuxedMKV calls streamFromTempFile) |

## Open assumptions (announced defaults)
| assumption | adopted default | rationale | reversible? |
|---|---|---|---|
| 10% overhead is safe | 1.10 multiplier on videoFile.length() | -c:v copy means video stream is identical; audio re-encode from DTS/FLAC to AAC 192k is often smaller than original audio, so 10% is generous | Yes, can adjust |
| Safari stops at real EOF | Browsers detect real video size from fMP4 moov metadata, stop requesting at correct byte | Confirmed by ZengXu blog research; browsers do not request past the real moov-declared duration | Yes, test must confirm |
| Single fix point | Only VideoAPI.java needs changes | No other files set Content-Range headers for temp-streamed videos | No other Content-Range headers found |

## Findings (cited - path:lines)
- **Bug mechanism**: VideoAPI.java:360 `currentSize = Files.size(tempFile)` for probe total → VideoAPI.java:517-518 `currentFileSize = Files.size(tempFile)` for range total. Temp file grows between these calls → different totals → Safari rejects.
- **Probe handler**: VideoAPI.java:356-376 — waits for 5MB, returns `Content-Range: bytes 0-1/<currentSize>`
- **streamFromTempFile range response**: VideoAPI.java:585-593 — two branches: transcodeFinished uses `currentFileSize - 1` as end, not-finished uses `Math.max(currentFileSize, end + 1)` as total. Both use `Files.size()` which changes.
- **FFmpeg copy mode**: TranscodingService.java:633-635 confirms `-c:v copy` when video codec is H.264 — video stream is pass-through identical.
- **Source file size**: Video file length is known upfront; output file will be very close to input.

## Decisions (with rationale)
- **Stable total via estimatedFinalSize**: Compute once upfront, pass down, use as floor for every Content-Range total. Eliminates the inconsistency at root.
- **10% overhead factor**: videoFile.length() * 1.10. Video is bit-identical (-c:v copy), audio is re-encoded to AAC 192k which is usually smaller than DTS/FLAC. 10% is generous but safe.
- **No iOS-specific gating**: The bug affects any client that validates Content-Range total consistency, not just Safari. Fix applies universally.
- **Single file change**: Only VideoAPI.java needs modification. No changes to TranscodingService.java.

## Scope IN
- VideoAPI.java: Add estimatedFinalSize parameter to streamFromTempFile
- VideoAPI.java: Compute estimatedFinalSize in streamRemuxedMKV() before probe handler
- VideoAPI.java: Fix probe response (line 366) to use estimatedFinalSize
- VideoAPI.java: Fix Content-Range in streamFromTempFile (lines 588, 592) to use estimatedFinalSize
- Verify build compiles and tests pass

## Scope OUT (Must NOT have)
- No changes to TranscodingService.java or any other file
- No iOS-specific checks
- No changes to streamRemuxedMKVDirect (it doesn't set Content-Range)
- No changes to streamDirectFile (not affected)
- No JS changes, no frontend changes
- No test file creation (no existing test infrastructure for these methods)
- No build/deploy configuration changes

## Open questions
None — all resolved by research and codebase exploration.

## Approval gate
status: approved-by-start-work
