# DJ Mode — Investigation & Plan

Status: investigation complete, no code changed
Date: 2026-11-11
Scope: beat analysis, transition planning, crossfade execution, and the
server↔client contract that joins them.

Evidence is tagged `path:line`. Live figures come from a read-only copy of
`~/.jmedia/jmedia-music.mv.db` on `home-dev`, taken 2026-11-11.

---

## 1. Method

- Read the Java pipeline (`DjTransitionService`, `DjEnrichmentService`,
  `AudioAnalysisService`, `PlaybackController`) and the browser engine
  (`AudioEngine.js`, `DjTransitionManager.js`, `DjSettingsPanel.js`).
- Two independent read-only audits, one per side, cross-checked against each
  other; the load-bearing claims were then verified first-hand:
  crossfade-duration contract, volume handling, audio graph, position scoring.
- Queried the live music database for actual analysis coverage and BPM spread.

---

## 2. Architecture in one page

```
import ─► metadata enrichment (AcousticBrainz: BPM, key, energy)
              │  only BPM is persisted                    MusicEnrichmentService
              ▼
       TarsosDSP beat grid  ──►  SongAnalysis (4 gzip JSON blobs)
              │                   beatTimes, segmentFeatures,
              │                   similarBeats, beatMetadata
              ▼
   DjTransitionService.calculateTransition(current, next, …)
              │  exit beat, entry beat, crossfade(1-2s), confidence, reason
              ▼
   PlaybackState (djExitTime, djEntryTime, djNextSongId, planned, confidence)
              │  WebSocket
              ▼
   DjTransitionManager (preload, trigger at exitTime, duration = state.crossfadeDuration)
              ▼
   AudioEngine (two <audio>, source→lowshelf→gain→destination, equal-power curve)
```

Two independent decision-makers: the server decides *where* to mix, the client
decides *how long*. They currently disagree.

---

## 3. What the live library says (the biggest finding)

| Metric | Value |
|---|---|
| Songs | **13,572** |
| Songs with BPM (from metadata) | 11,530 (85 %) |
| Songs without BPM | 2,042 (15 %) |
| `SongAnalysis` rows | 744 |
| — COMPLETED | 719 |
| — PENDING | 25 |
| Analyses with a usable beat grid | 718 |
| Average beats per analysed song | 594 |
| Average BPM across analyses | 141.7 |
| **Library with a beat grid** | **744 / 13,572 = 5.5 %** |

Implications:

1. **94.5 % of the library cannot produce a DJ plan.** `calculateTransition`
   requires `isReady()` + non-empty beats on both songs
   (`DjTransitionService.java:99-118`). Without a plan the client never fades,
   so DJ mode is, for the overwhelming majority of playlists, a normal player.
2. **BPM in the DB is not a beat grid.** 85 % of songs have a BPM integer from
   metadata enrichment, but only 5.5 % have `SongAnalysis`. Any fix that
   improves scoring will be invisible until coverage improves.
3. **BPM spread is wide** (100–190 with peaks at 120–130 and 170–180). With a
   15 % ratio tolerance, neighbouring songs can still be ~20 BPM apart at the
   top of the range.
4. **25 songs are stuck PENDING** with no worker picking them up.

The coverage gap is the strongest argument for treating this as a pipeline
problem first and a scoring problem second.

---

## 4. Contract mismatches (the audible bugs)

| What the server computes | What the client uses | Consequence |
|---|---|---|
| `crossfadeSeconds` = 1 s (`nextBpm > 120`) or 2 s, hard cap `DJ_MAX_CROSSFADE_SECONDS = 2` (`DjTransitionService.java:223-239`) | `state.crossfadeDuration \|\| 8` (`DjTransitionManager.js:234`), and DJ mode forces 8 (`PlaybackController.java:1030`) | **Every mix runs 4–8× longer than planned**; the 2 s policy is inert because `PlaybackController` never stores `transition.getCrossfadeSeconds()` (`:1646-1665`) |
| Entry beat searched in the first 40 % of the next track (`:161-170`) | Anything `> 45 s` is rewritten to 0 (`DjTransitionManager.js:212-218`) | Server alignment discarded for long intros |
| `djCrossfadeOverride` clamped to 2 s (`:229`) | Client never reads the override at all | UI offers 0/3/5/10 s — all dead |
| `confidence` = score / 1.1 (`:211-216`, `:487-490`) | Displayed/consumed as 0–1 quality | Raw max ≈ 3.35, so any decent pair saturates at 1.0; HIGH strictness only rejects at ≳18 % BPM difference |

Derived drift: at 120 BPM a 15 % mismatch is 2.6 beats/sec difference → **~2.4
beats of drift across an 8 s fade**. With no tempo nudge (`playbackRate` is used
only for clock sync, `MusicBarInit.js:111-118`), a long fade on two percussive
tracks will audibly gallop.

---

## 5. Defect catalogue

### P0 — wrong output or user-visible breakage

| ID | Defect | Evidence | Impact |
|---|---|---|---|
| D1 | Fade duration never reaches the client | `DjTransitionService.java:223-239`, `PlaybackController.java:1646-1665`, `DjTransitionManager.js:234` | Mix timing is 4–8× the plan |
| D2 | `activePlayer` flipped before the incoming deck plays; abort paths leave the pointer on silence | `AudioEngine.js:1041`, `:1243-1274` | Pause/seek/resume target the wrong element |
| D3 | `isTransitioning` cleared only on normal finalize | `AudioEngine.js:1168-1179`, `DjTransitionManager.js:377` | One bad fade disables DJ for the session |
| D4 | Volume 0 → fade target 0.3, and finalize writes 0.3 back | `AudioEngine.js:1060-1067`, `:1195-1224` | Muted user gets unmuted |
| D5 | No limiter; equal-power sum peaks +3 dBFS | `AudioEngine.js:160-186` | Clipping on correlated material |
| D6 | `cancelTransition()` clears a flag it does not own while the fade is running | `DjTransitionManager.js:131-134`, `:608-621` | Slider unfreezes, `setSource` stops deferring mid-fade |

### P1 — musical correctness

| ID | Defect | Evidence |
|---|---|---|
| D7 | Energy term is constant: synthetic strengths (1.0/0.7/0.5) + equal-`beatInBar` filter ⇒ diff always 0 | `AudioAnalysisService.java:496-497`, `DjTransitionService.java:312-323`, `:358-372` |
| D8 | Phrase bonus off by one bar: `barNumber` is 0-based, modulo assumes 1-based; `-1 % 4 == -1` | `AudioAnalysisService.java:492`, `DjTransitionService.java:297-301` |
| D9 | No downbeat detection — beat 1 is simply index 0, assumed 4/4 | `AudioAnalysisService.java:441-442` |
| D10 | Spectral similarity is FFT-bin-mod-12, not chroma; silent null → fixed 0.5 | `AudioAnalysisService.java:366-379`, `DjTransitionService.java:270-279` |
| D11 | Fallback uses the **last beat** of the current song and bypasses the 2 s cap and HIGH strictness | `DjTransitionService.java:429-482` |
| D12 | No bound `exitTime + fade ≤ duration` | same |
| D13 | Zero-beat songs reported as analysed (`isReady()` true with empty arrays) → plan cleared, `djNextSongId` nulled, no re-plan | `SongAnalysis.java:138-145`, `PlaybackController.java:1668-1671` |
| D14 | `queueAllUnenriched` always stops after 500 songs (`page.clear()` before size check) | `DjEnrichmentService.java:416-418` |
| D15 | `pending` never decremented for analysis-only entries | `DjEnrichmentService.java:352`, `:411`, `:424` vs `:202` |
| D16 | Duplicate analysis race; `@OneToOne` without unique constraint; `getSingleResult` throws on duplicates | `AudioAnalysisService.java:593-602`, `SongAnalysis.java:16-18` |
| D17 | `analyzeSongAsync` self-invocation bypasses `@Transactional` | `AudioAnalysisService.java:341-346` etc. |

### P2 — capability gaps

- **No musical key.** Parsed from AcousticBrainz then discarded; no column
  (`MusicEnrichmentService.java:401-416`, `Song.java`).
- **No loudness.** No ReplayGain/R128/LUFS anywhere; level jumps are inherent.
- **No tempo adjustment.** Long fades cannot beatmatch.
- **No sections/intro/outro.** `djModeSections`, `djModeTriggerPercent`,
  `djModeCrossfadeSeconds` are declared and never read (`Settings.java:78-81`).
- **iOS is a hard cut** while the UI says "Mixing…" (`DjTransitionManager.js:533-602`).

### Hygiene

`System.out.println` as the DJ log (`PlaybackController.java:1595` … `:1665`),
`e.printStackTrace()` (`AudioAnalysisService.java:334`), silent catches
(`DjTransitionService.java:259-261`, `SongAnalysis.java:64-66`, `:88-90`,
`:128-135`, `:152-155`), and **zero tests** for the planner, the curve, the
fallback, or the queue counters.

---

## 6. Target behaviour (the spec the plan is measured against)

1. **One fade duration, decided once.** Server chooses it; client executes
   exactly that; the value is visible in the state payload.
2. **No drift.** Either require `|ΔBPM| ≤ 3 %` for fades > 2 s, or nudge the
   incoming deck and report it (`playbackRate`, ±4 %).
3. **Level continuity.** Per-track gain towards a target loudness, plus a
   limiter on the master bus; no write-back of user volume.
4. **Key awareness.** Camelot distance ≤ 2 preferred, > 4 heavily penalised
   (opt-in), never a hard failure.
5. **Explicit fallback chain:** `planned` → `natural-end fade` → `hard cut`,
   each logged with a reason, each visible in the indicator.
6. **Abort-safe:** pause, skip, seek, network error and watchdog leave the
   pointer on an audible deck and clear the shared flags.
7. **Coverage:** DJ planning is possible for ≥ 80 % of played tracks.

---

## 7. Phased plan

### Phase 0 — Instrumentation (no behaviour change)

- Add structured counters/logs: plans attempted, plans produced, transitions
  executed, aborts by cause, average computed vs executed fade length.
- Expose them on `GET /api/metadata/dj-enrichment-progress`.
- Acceptance: one play session yields a log line per transition with
  `plannedFade`, `executedFade`, `exit`, `entry`, `confidence`, `outcome`.

### Phase 1 — Contract + safety (P0)

| Task | File | Acceptance |
|---|---|---|
| Persist computed fade on `PlaybackState` (`djTransitionCrossfade`) and send it | `DjTransitionService`, `PlaybackController`, `PlaybackState` | Client fades the stored value; 1–2 s observed |
| Client prefers `djTransitionCrossfade`, falls back to `crossfadeDuration` | `DjTransitionManager.js` | Manual check: fade length in the log matches the plan |
| Fix volume-0 handling; never write volume back | `AudioEngine.js` | Muted user stays muted through a fade |
| Add master limiter (−1 dB ceiling) | `AudioEngine.js` | No clipping at the midpoint with two hot tracks |
| Move `activePlayer` flip after `play()` resolves; clear `isTransitioning` on every abort | `AudioEngine.js`, `DjTransitionManager.js` | Skip/pause mid-fade leaves audio audible and DJ usable afterwards |
| Single owner for `isCrossfading` | both | No mid-fade unfreeze of the slider |

### Phase 2 — Planning correctness (P1)

- Fix `barNumber`/phrase modulo; make downbeat bonus explicit.
- Use onset strength from `segmentFeatures` for energy instead of synthetic values.
- Bound exit to `[0.70, 0.90] · duration` and assert `exit + fade ≤ duration`.
- Fallback: never use the last beat; clamp to `duration − fade − 0.5 s`.
- Zero-beat songs: `isReady()` false; planning falls back to a natural-end fade
  and keeps `djNextSongId` so analysis can re-plan.
- Fix the paging bug and the `pending` counter.

### Phase 3 — Coverage (the real win)

- Queue analysis for every song that has metadata but no `SongAnalysis`
  (thousands, not hundreds): batched, resumable, rate-limited.
- Recover stuck `PENDING`/`PROCESSING` rows on startup.
- Deduplicate analysis (unique constraint on `song_id`; single-flight).
- Acceptance: coverage ≥ 80 % of the library; a randomised 20-song playlist
  produces plans for ≥ 16 transitions.

### Phase 4 — Musicality (P2)

- Persist `key` from AcousticBrainz; Camelot distance in the score.
- Per-track loudness (EBU R128) → gain offset applied at load.
- Tempo nudge for fades > 2 s when BPM differs by 1–6 %.
- Real downbeat detection (onset-accent voting), replacing `i % 4`.

---

## 8. Test plan

The project has **no `@QuarkusTest` and no JS test harness**, so:

- `DjTransitionService` is nearly pure — add unit tests for: crossfade
  selection per BPM band, HIGH strictness rejection, fallback bounds,
  phrase alignment (bars 0/1/4/8), `beatsCompatible`, `normalizeScore`
  saturation, and confidence penalty.
- Pure helpers extracted from the client (`equalPowerCurve`, `clampEntry`)
  can be tested with a tiny Node harness if we add one; otherwise keep the
  logic in Java and test it there.
- Manual matrix (needs you or a device): desktop Chrome, iPhone Safari,
  Android Chrome; mute→fade, skip mid-fade, pause mid-fade, network stall,
  8 s preload timeout, rapid double-skip.

No browser automation will be used without your say-so.

---

## 9. Limits (proposed)

- **L-A1** every new Java file ≤ 250 non-blank lines; planner stays pure.
- **L-A2** no behaviour change in Phase 0 (logging/counters only).
- **L-A3** Phase 1 touches ≤ 5 files and ≤ 300 lines.
- **L-F1** a transition never fades beyond `duration − 0.5 s`.
- **L-F2** user volume is never written by playback code.
- **L-S1** analysis coverage ≥ 80 % before Phase 4 starts (it would be
  invisible otherwise).
- **L-D1** no new runtime dependencies.

---

## 10. Open decisions

1. **Fade length policy:** 1–2 s as the code documents, or 2–6 s (better for
   non-beatmatched material)? The current 8 s is too long for a 15 % BPM gap.
2. **Tempo nudging:** acceptable to pitch-shift/nudge ±4 %? It makes long
   fades work but changes the sound.
3. **Key matching:** opt-in, and hard-reject or only penalise?
4. **Loudness:** normalise on playback (gain offset) or rewrite files with
   ReplayGain tags?
5. **Coverage work is CPU-bound** (TarsosDSP on ~13 k files). Run it throttled
   in the background over days, or on demand per playlist?

---

## 11. Recommended first slice

Phase 0 + Phase 1: roughly 5 files, no analysis changes, and it fixes the
audible bugs (mismatched fade length, muted-user unmute, clipping, DJ dying
after one bad fade). It also produces the instrumentation needed to judge
Phase 2/3 honestly.
