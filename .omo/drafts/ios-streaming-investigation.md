---
slug: ios-streaming-investigation
status: awaiting-approval
intent: clear
pending-action: write .omo/plans/ios-streaming-investigation.md
approach: add console.debug instrumentation gated on iOS in the JS player files + trace ID plumbing + server-side request logging
---

# Draft: ios-streaming-investigation

## Components (topology ledger)
| id | outcome | status | evidence path |
|----|---------|--------|---------------|
| A: Stall timer reload | Instrument the 20s stall timer callback to log conditions when it fires on iOS | active | EventBinder.js:24-36 |
| B: iOS never requests real video after probe | Add trace IDs from JS to all stream URLs; enhance server-side logging to log every request with trace ID | active | VideoAPI.java:352-372 + client trace param |
| C: WebKit fullscreen pipeline reset | Instrument fullscreen entry/exit in FullscreenManager.js to log events on iOS | active | FullscreenManager.js:76-123, 141-163 |
| D: SPA fragment timing | Deferred — not instrumenting | deferred | — |

## Decisions (with rationale)
- Only add instrumentation code (console.debug + trace ID + server logging)
- No build, no deploy, no simulator — user tests on own device
- All three hypotheses (A, B, C) instrumented simultaneously

## Scope IN
- Add iOS-only console.debug logging to simple-player.js, EventBinder.js, FullscreenManager.js
- Add per-request trace ID (query parameter from JS to server)
- Add server-side request logging with trace ID in VideoAPI.java

## Scope OUT
- No build/deploy/simulator setup
- No log capture or analysis (user handles that)
- No fix implementation
- No production logic changes
- No refactoring
- No FFmpeg changes
- No non-iOS code changes

## Approval gate
status: awaiting-approval
