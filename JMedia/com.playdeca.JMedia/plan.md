# Web Audio DJ Transition Scheduling Plan

## Objective

Make DJ Mode transitions use the Web Audio clock for the audible crossfade while preserving the existing queue contract:

- SmartShuffle or the user queue selects the next song.
- DJ Mode only mixes the already-selected next song.
- A failed mix must fall back to normal playback without cutting the current song early.
- The server must receive one idempotent transition-start notification.
- iOS and single-deck environments must retain a safe fallback.

## Current problem

`DjTransitionManager` detects the exit point using `requestAnimationFrame`, a 500 ms poll, and `timeupdate`. It then calls `AudioEngine.crossfadeTo()`, which starts media playback and gain automation immediately. Browser timers and media-element start latency can therefore move the audible transition away from the analyzed beat.

## Design invariants

1. Never change queue order or select a song from the browser.
2. Never schedule audio against a stale DJ plan.
3. Never start the incoming deck audibly before the scheduled transition time.
4. Gain automation is scheduled on `AudioContext.currentTime`.
5. Every transition has a token; cancellation invalidates the token.
6. A transition is reported to the server only after the audio engine accepts it.
7. If Web Audio scheduling is unavailable, use the existing crossfade fallback.
8. If no DJ transition is available, let the current song reach its natural end.

## Implementation steps

### Phase 1 — Plan and scheduling API

- [x] Create this implementation plan.
- [x] Add a scheduling option to `AudioEngine.crossfadeTo()`.
- [x] Add a helper that converts the remaining media time into an AudioContext start time.
- [x] Schedule outgoing/incoming gain ramps at the calculated AudioContext time.
- [x] Start the incoming media element muted shortly before the scheduled point so its media clock is ready without leaking audio.
- [x] Keep the existing immediate crossfade path for single-deck and unsupported environments.

### Phase 2 — DJ transition integration

- [x] Let `DjTransitionManager` schedule shortly before the analyzed exit point instead of waiting until after it has passed.
- [x] Keep the existing preload gate and retry behavior.
- [x] Make cancellation invalidate pending schedules and stop monitoring.
- [x] Keep `/transition-started` notification tied to an accepted transition token.

### Phase 3 — State and failure handling

- [x] Ensure a missed or cancelled schedule never advances the server queue.
- [x] Ensure duplicate notifications for one token are ignored.
- [x] Reconcile the client position from the incoming media element after the fade.
- [x] Preserve the normal end-of-song fallback when a plan is unavailable.

### Phase 4 — Verification

- [ ] Add pure tests for schedule-time calculation and cancellation decisions.
- [ ] Add browser-side tests with fake `AudioContext`, `AudioParam`, and media elements where the project test setup permits it.
- [x] Run JavaScript syntax checks.
- [x] Run the complete Maven test suite.
- [ ] Manually verify foreground playback, background tabs, seek during a plan, rapid next/previous, preload failure, and iOS/single-deck fallback.

## Proposed timing model

When the active media element reports:

```text
remaining = plannedExitTime - currentMediaTime
```

and the incoming deck is ready, the browser schedules the audible transition at:

```text
audioStart = audioContext.currentTime + max(remaining, 0)
```

The incoming media element is started silently just before `audioStart`; its gain remains zero until the Web Audio automation begins. This improves audible timing without requiring the entire song to be decoded into an `AudioBuffer`.

## Known limitation

An `HTMLMediaElement` cannot itself be started sample-accurately with `AudioContext.currentTime`. The design therefore schedules the audible gain transition accurately and starts the muted media element just-in-time. Fully sample-accurate source start would require fetching and decoding the entire track into an `AudioBufferSourceNode`, which is not suitable for the current streaming architecture.

## Rollout and rollback

- Keep the scheduler behind a small feature flag or capability check during implementation.
- If scheduling fails, call the existing `crossfadeTo()` path immediately.
- Log the planned AudioContext time, actual media start, fallback reason, and transition token.
- Remove the flag only after manual device verification.
