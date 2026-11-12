# DJ Mode — Implementation Plan

Companion to `dj-mode-investigation.md`. That document establishes the defects;
this one is the execution plan: workstreams, tasks, files, tests, gates.

Status: plan only
Date: 2026-11-11
Baseline: 1.35.0 (contract + limiter + volume + flag ownership shipped)

---

## 0. Where we are

Shipped in 1.34.5 / 1.35.0:

- Queue recursion guard that caused the login outage (depth-limited advance).
- `PlaybackState.djTransitionCrossfade`; the client executes the planned fade.
- Master limiter on the audio bus; volume 0 honoured; `isCrossfading` owned by
  the engine alone.

Still open, in the order this plan executes them:

| Workstream | Why it comes here |
|---|---|
| **W0 Stability** | A long transaction already caused an outage-class failure (`ARJUNA012117`) |
| **W2 Scoring** | Cheap, testable, and makes W3's coverage meaningful |
| **W1 Client robustness** | Needs a manual device matrix; can land after scoring |
| **W3 Coverage** | 94.5 % of the library has no beat grid; the long pole in time |
| **W4 Musicality** | Depends on W3 data (key, loudness) |

Guiding rule: **every task ships with a test that fails first**, except the
browser-only ones, which ship with a written manual check.

---

## 1. W0 — Stability (blocking)

### T0.1 Decode outside the transaction *(critical)*

`AudioAnalysisService.analyzeSong` performs the entire TarsosDSP decode inside
the JTA transaction and only persists at the end
(`AudioAnalysisService.java:274-338`). A long track exceeds the transaction
reaper and leaves the transaction rollback-only — the same class of failure
that locked the app at 17:57.

Change:
- Run `performAdvancedTarsosAnalysis` outside any transaction (plain method, no
  `@Transactional` on that path).
- Persist in a short `QuarkusTransaction.requiringNew()` block: create/update
  the row, write JSON, update `Song.bpm`.
- Catch persistence failure without leaving a rollback-only context.

Files: `AudioAnalysisService.java` (`analyzeSong`, `analyzeSongAsync`).
Test: pure helper `AnalysisWriter` decisions (status/queries) unit-tested; no
CDI test possible, so the acceptance is runtime.
Acceptance: a 10-song batch over 5-minute tracks produces **zero**
`ARJUNA012117` warnings.

### T0.2 Recover stuck analyses

`PROCESSING` rows are never selected again
(`AnalysisWorker.java:132-155`, `DjEnrichmentService.java:444-455`).

Change: on startup and every worker tick, requeue `PENDING`/`PROCESSING` rows
whose `analysisTimestamp` is older than 30 min (configurable).
Files: `AnalysisWorker.java`, `AudioAnalysisService.java`.
Acceptance: manually mark a row `PROCESSING` with an old timestamp; it is
re-picked within one tick and completes.

### T0.3 Recursion guard regression test

`advance(...)` depth guard is untested. Make the 6-arg overload
package-private and assert that depth 4 returns `null`, sets `playing=false`,
and touches no dependency.
File: new `src/test/java/Controllers/PlaybackQueueControllerTest.java`.

---

## 2. W2 — Scoring correctness

### T2.1 Extract pure scoring (`DjScoring`)

New `Services/DjScoring.java`, all static, no CDI/DB:

| Function | Contract |
|---|---|
| `phraseBonus(int barNumber)` | 0-based bars; `bar % 4 == 0` → +0.15, `bar % 8 == 0` → +0.1 |
| `exitPositionScore(double exitPos)` | 0.78–0.92 → +0.45; 0.70–0.78 → +0.20; <0.55 → −0.60 |
| `entryPositionScore(double seconds, double pos)` | <2 s → −0.5; <5 s → −0.2; 0.05–0.40 → +0.2 |
| `energyBonus(double exitStrength, double entryStrength)` | `max(0, 0.2 − |Δ|·0.2)` |
| `normalizeConfidence(double raw, double bpmRatioDiff)` | `raw/3.35`, then × `1 − min(diff/0.30, 0.8)`, clamped |
| `clampExit(double exit, double duration, int fade)` | `min(exit, duration − fade − 0.3)` |

Tests (`DjScoringTest`, JUnit 5, offline): bar 0/1/4/8 phrase cases, exit bands,
entry bands, energy equality/opposites, saturation at the true max, clamp at EOF.

### T2.2 Real energy

Replace the synthetic-strength comparison
(`DjTransitionService.java:312-323`) with local energy from `segmentFeatures`
around each beat (±2 s mean of `strength`). The parsed feature lists already
exist at the call site (`:158-159`); pass them in.

Acceptance: mixing a quiet intro against a loud drop scores measurably lower
than two similar-energy beats (unit case with synthetic features).

### T2.3 Exit bounds

- Restrict exit candidates to `[0.70, 0.92] · duration` (currently everything
  after 50 %/60 s, `:128-134`).
- Apply `clampExit` before returning.
- Assert in code (and test) that `exit + fade ≤ duration − 0.3`.

### T2.4 Fallback rewrite

Current: last beat of the current song, raw crossfade, bypasses HIGH strictness
(`:429-482`). Replace with:
- exit = last beat whose position ≤ 0.92, clamped by `clampExit`;
- entry = first downbeat in the entry window, else first beat ≥5 s, clamped;
- fade = same policy as the main path;
- HIGH strictness rejects the fallback (`confidence 0.3 < 0.4`).

Test: fallback exit never exceeds `duration − fade − 0.3`.

### T2.5 Zero-beat songs

- `SongAnalysis.isReady()` requires `beatCount > 0` (`:138-145`).
- `planNextDjTransition` on `null` result keeps `djNextSongId` and sets
  `djTransitionPlanned=false` (not `clearDjTransitionPlan`, which nulls the id).
- The client falls back to a natural-end fade of `min(2 s, remaining)` when no
  plan exists, so DJ mode degrades audibly instead of hard-cutting.

Tests: `isReady()` false for empty grid; controller keeps the next id.

### T2.6 Planner transaction

`calculateTransition` is `@Transactional` while only reading. Mark read-only and
keep it short, or move the JSON parsing out. Acceptance: no reaper warnings
from the planner during a 100-transition synthetic run.

---

## 3. W1 — Client robustness

Each task is browser-verified against a written matrix; no automation.

| Task | Change | File |
|---|---|---|
| T1.1 | Flip `activePlayer` only after `play()` resolves; on failure keep the old deck and clear flags | `AudioEngine.js:1041`, `:1290-1352` |
| T1.2 | Clear `isTransitioning` on every abort/watchdog path | `AudioEngine.js:1029-1037`, `:1243-1274`, `DjTransitionManager.js:377` |
| T1.3 | Watchdog notifies `DjTransitionManager` so the indicator cannot stick | same |
| T1.4 | Seek during a fade targets the audible deck (or is ignored with feedback) | `AudioEngine.js:1734`, `TimeController.js:136-139` |
| T1.5 | iOS: implement a short gain ramp instead of a hard `src` swap, and set `_currentSongId` on the swapped element | `DjTransitionManager.js:533-602` |
| T1.6 | Clear stale `_djTransitioned` when an element is reused for a preload | `AudioEngine.js:869-914` |
| T1.7 | Entry clamp becomes duration-relative (e.g. 0.6·duration) instead of a flat 45 s | `DjTransitionManager.js:212-218` |

Manual matrix (record results in the PR):
desktop Chrome · iPhone Safari · Android Chrome —
mute→fade · skip mid-fade · pause mid-fade · seek mid-fade · network stall ·
8 s preload timeout · rapid double-skip · single-song queue ×10 advances.

---

## 4. W3 — Coverage

### T3.1 Paging bug

`queueAllUnenriched` clears the page before checking its size
(`DjEnrichmentService.java:416-418`) → always stops after 500 songs.
Fix: capture `int size = page.size()` before clearing.

### T3.2 Queue songs that have metadata but no analysis

New pass over `Song` where `bpm > 0` (or metadata complete) and no
`SongAnalysis` row exists, batched, deduped against the queue, logged.
Acceptance: after one run, the queue drain raises coverage from 5.5 % to the
configured target; progress visible on `dj-enrichment-progress`.

### T3.3 `pending` counter

Decrement on analysis completion as well as metadata
(`DjEnrichmentService.java:202` only). Add a per-item decrement in both workers.

### T3.4 Single-flight + duplicate resilience

- In-memory `Set<Long> inFlight` around `analyzeSong`.
- Replace `em...getSingleResult()` with `list().stream().findFirst()` in
  `loadAnalysisSlim`, `queueAnalysis`, `getStatus`
  (`AudioAnalysisService.java:593-602`, `:676-683`, `:712-719`).
- Deferred: unique constraint on `song_id` (needs a data cleanup migration).

### T3.5 Throughput

Expose `analysed / total` and `songs/hour`; keep the existing thread settings.
Target: ≥ 80 % coverage. At the current rate this is a multi-hour background
job; it must be resumable and must never run inside a request.

---

## 5. W4 — Musicality

| Task | Change | Depends on |
|---|---|---|
| T4.1 | `Song.musicalKey` + `Song.energy` columns; persist `key`/`energy` from `AcousticBrainzResult` (`MusicEnrichmentService.java:178-203`, parsed at `:598-603`) | — |
| T4.2 | Camelot distance (`DjScoring.camelotPenalty`), opt-in setting; penalty only, never a hard reject | T2.1, T4.1 |
| T4.3 | Per-track loudness: mean RMS from the TarsosDSP frames → `gainDb`; client multiplies curve levels at load | T0.1 |
| T4.4 | Tempo nudge: for fades > 2 s with `|ΔBPM| ≤ 6 %`, set `playbackRate` on the incoming deck, reset after finalize, report in `reason` | W1 |
| T4.5 | Real downbeat detection (onset accent voting) replacing `i % 4` | optional, largest |

Each is feature-flagged and off by default until A/B listening.

---

## 6. Sequencing and gates

```
Gate A ─ T0.1 + T0.2 + T0.3      (no reaper warnings; outage class closed)
   │
Gate B ─ W2 (T2.1–T2.6)          (scoring tests green; fallback bounded)
   │
Gate C ─ W1 (T1.1–T1.7)          (manual matrix signed off)
   │
Gate D ─ W3 (T3.1–T3.5)          (coverage ≥ 80 %)
   │
Gate E ─ W4 (T4.1–T4.4)          (feature-flagged; listening test)
```

Nothing in W3/W4 ships before Gate A: analysing 13 k songs through a
transaction that can be reaped is how the outage happened.

---

## 7. Test strategy

- Pure Java tests: `DjScoringTest`, `PlaybackQueueControllerTest`,
  `DjTransitionPlanTest` (extracted planner taking plain inputs).
- No `@QuarkusTest` in this project; anything needing CDI is verified at
  runtime with recorded evidence (log excerpt, DB query).
- Browser: manual matrix only; no automation without explicit approval.

---

## 8. Risks and rollback

| Risk | Mitigation |
|---|---|
| Analysis refactor changes stored JSON | Goldens: analyse 3 tracks before/after, diff the JSON |
| Coverage run saturates the CPU | Throttle via existing thread settings; pause by setting −1 |
| Tempo nudge offends | Behind a setting, default off; reset `playbackRate` in finalize |
| Client changes regress playback | Each task is a separate commit; revert is one file |
| DB schema additions | Hibernate `update` adds nullable columns; no destructive migration |

---

## 9. Limits

- **L-A1** `DjScoring` pure: no CDI, DB, HTTP, or framework imports.
- **L-A2** every new Java file ≤ 250 non-blank lines.
- **L-F1** `exit + fade ≤ duration − 0.3` always.
- **L-F2** user volume is never written by playback code.
- **L-F3** W4 features default off.
- **L-S1** analysis never holds a transaction longer than 5 s.
- **L-D1** no new runtime dependencies.

---

## 10. Decisions needed before Gate B

1. Fade policy: keep 1–2 s (documented) or move to 2–6 s with tempo nudge?
2. Tempo nudge: allowed? Range ±4 % or ±6 %?
3. Key matching: penalty only, or reject beyond a Camelot distance?
4. Loudness: playback gain (reversible) or ReplayGain tags (permanent)?
5. Coverage window: run the multi-hour analysis overnight now, or wait for
   Gate A?
