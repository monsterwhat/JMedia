# Library Auto-Repair — Investigation & Design

Status: investigation complete, no code written
Date: 2026-11-11
Scope: video library identification, season/episode placement, and the
feasibility of an automatic repair pass.

Evidence is tagged `path:line`. Server evidence was taken read-only from
`home-dev` (192.168.1.72) on 2026-11-11.

---

## 1. Verdict

An automatic repair pass is feasible. The failures observed on the live
server are not random: they are reproducible consequences of four missing
concepts, each of which can be added independently and verified offline:

| # | Missing concept | Consequence on the live library |
|---|---|---|
| 1 | Multi-episode ranges (`S01E01-02`) | Half of Steven Universe is absent |
| 2 | Any match scoring for TMDb search | Bleach TYBW binds to Bleach (2004) |
| 3 | A "this folder is season N" mapping | TYBW is split across 4 broken seasons |
| 4 | Extras are numbered like episodes | Featurettes occupy episode 1, 7, 12, 13, 34, 36, 38, 150 |

Two of these (1 and 4) are parser-level and need no file movement. Two
(2 and 3) are identity-level and can be solved deterministically with a
folder-hint plus a persisted mapping, without fuzzy logic.

**Correction to an earlier claim in this session:** a normal rescan does
*not* wipe the `video` table. `Video.deleteAll()` exists only in
`resetVideoDatabase()` — `Services/VideoImportService.java:394-474` —
reached exclusively from `POST /api/video/reset-database`
(`API/Rest/VideoAPI.java:813-821`) and `POST /api/video/clear-all`
(`:833-841`). Scans upsert by path and skip unchanged files. This changes
the repair design: database-only fixes survive an incremental scan, but a
**full** scan re-derives the parser fields and reverts them (see §5.3).

---

## 2. Observed failures (live server)

### 2.1 Bleach: Thousand-Year Blood War

Database row:

```
SERIES id=35  title='Bleach Thousand Year Blood War'
              imdbId=tt0434665  tmdbId=30984  releaseYear=2004
              seasonEpisodeCounts=null  forceAbsoluteNumbering=null
```

`tt0434665` is **Bleach (2004)**. TMDb has no separate entry for TYBW —
a live `/search/tv` for "Bleach Thousand-Year Blood War" returns exactly
one result, `30984 Bleach`. The show is modelled as a season of Bleach:

```
Bleach (tmdb 30984)
  Season 0  Specials                     4 eps
  Season 1  Bleach                     366 eps   (2004-10-05 → 2012-03-27)
  Season 2  "Thousand-Year Blood War"   50 eps   (2022-10-11 → 2026-10-27)
```

Local rows under one series, four season buckets:

| Local season | Episode numbers present | TMDb expectation | Card shown |
|---|---|---|---|
| 1 | 1–13, plus 48, 49, 50 | 366 | "13 of 366" |
| 2 | 14–26 | 50 | "13 of 50" |
| 3 | 27–40 | *no season 3* | unmatched |
| 4 | 41–47 | *no season 4* | unmatched |

Episode numbers are a continuous 1–50 and line up with TMDb Season 2
exactly (verified against the cached payload):

```
S02E01 The Blood Warfare    S02E27 A
S02E14 The Last 9 Days      S02E40 MY LAST WORDS
S02E26 BLACK                S02E41 GOD OF THUNDER
                            S02E48 THE END TWO WORLD
                            S02E49 THE BLADE
                            S02E50 DEATH & STRAWBERRY
```

The three files named `S01E48/49/50` sit physically in the `S04` folder —
they are the right *numbers* with the wrong season token.

All 13 rows for `S01E01`–`S01E13` are:

```
enrichmentStatus = NOT_FOUND   episodeTitle = null   tmdbId = null
```

They were looked up as Bleach (2004) S01E01–13, which are different
episodes. Because `NOT_FOUND` is terminal (§4.2), they will never retry.

Extras are also numbered as episodes: `1, 7, 12, 13, 34, 36, 38, 150`.
`contentType` is correctly `extra`, but `episodeNumber` was never cleared,
so they collide with real episodes in the season card.

### 2.2 Steven Universe

84 episodes under `SERIES id=2604`, plus one orphan row under a duplicate
`SERIES id=2605` whose path still points at a retired root
(`/mnt/media/TV Shows/...` instead of `/mnt/storage/...`).

Every file is a pair: `Steven Universe - S01E01-02 - [x265.AAC].mp4`.
Stored rows (excerpt):

```
S1: 1,3,5,...,45,47,48        (25 rows)
S2: 1,2,4,6,8,9,...,26,28     (17 rows)
S3: 1,3,5,6,...,20,21,23      (12 rows)
S4: 1,3,5,7,...,22,24         (14 rows)
S5: 1,3,...,21,23,24,...,28   (21 rows)
```

TMDb expects `49 / 29 / 24 / 24 / 28`. Every even episode of a pair is
simply missing — `S01E02 = Laser Light Cannon` has no row because it lives
inside the `S01E01-02` file. Parser log evidence:

```
Smart naming completed for Steven Universe - S01E09-10 - [x265.AAC].mp4:
    episode -> 10 (confidence: 1.00)
```

The `10` is the *title hint*, i.e. the second half of the range was
captured as the episode title and then discarded as a name.

Separately, the disk has a genuine gap: `S03E21` is followed by
`S03E23-24`; episode 22 has no file at all.

---

## 3. Root causes

### 3.1 The episode regex cannot express a range

`Services/SmartNamingService.java:46-47`:

```java
private static final Pattern EPISODE_SXXEXX =
    Pattern.compile("(?i)(.*?)[\\s\\._-]*[sS](\\d{1,2})[\\s\\._-]*[eE](\\d{1,3})(.*)");
```

Group 3 is a single `\d{1,3}`; group 4 swallows the rest, so `-02` becomes
`titleHint` and is then cleaned into a title. There is no `episodeEnd`
field anywhere in the codebase, and no range handling in
`detectEpisodeInfo` (`:713-…`).

### 3.2 Show matching takes TMDb's first result, unconditionally

`Services/VideoMetadataService.java:1281-1283`:

```java
JsonNode searchRoot = fetchJson(searchUrl, authHeaders);
if (searchRoot != null && searchRoot.path("results").size() > 0) {
    showTmdbId = searchRoot.path("results").get(0).path("id").asText();
}
```

The query is `video.seriesTitle` plus an optional `first_air_date_year`
(`:1274-1280`). There is **no** title similarity, year check, vote-count
floor, or rejection path. The same `results.get(0)` shape repeats at eight
call sites (`:615`, `:1283`, `:2127`, `:2347`, `:2501`, `:2891`, `:2923`,
`:326`).

### 3.3 A wrong match is self-locking

| Mechanism | Reference | Effect |
|---|---|---|
| `ENRICHED` set on `tmdbId != null` alone | `VideoMetadataService.java:526-527` | wrong ID counts as success |
| `needsEnrichment` returns false for `NOT_FOUND` | `:193` | failure is permanent |
| `EnrichmentNegativeCache` 30-day TTL | `EnrichmentNegativeCache.java:34` | failures remembered |
| `seriesImdbIdCache` — permanent `Map`, no eviction | `VideoMetadataService.java:126` | wrong IMDb id until restart |
| `MetadataSeasonLengthProvider.cache` — no eviction | `MetadataSeasonLengthProvider.java:41` | stale season lengths |

Any repair must reset `tmdbId` / `showImdbId` / `enrichmentStatus`, call
`EnrichmentNegativeCache.evictContaining(...)` (`:90-95`), and provide
eviction for the two permanent maps (they currently have no API).

### 3.4 There is no way to say "these folders are one season"

`Series` carries only *lengths* — `seasonEpisodeCounts`, `absoluteSeasonLengths`,
`forceAbsoluteNumbering` (`Models/Video/Series.java:91-106`). There is no
mapping, offset, or folder column, and `SeasonLengthProvider` takes a bare
show-key string with no folder context (`Services/SeasonLengthProvider.java:17-25`).

`resolveAbsoluteNumber` (`SmartNamingService.java:678-711`) can only remap
a *bare* number inside a season folder; it refuses when:
- seasons are not contiguous from 1 (`:686-691`),
- the number falls outside the folder's own season range (`:697-700`),
- the number is already relative (`:701-704`).

It is also gated to bare-number detection methods (`ABSOLUTE_BARE_METHODS`
`:39-41`, predicate `:638-649`), so `S02E14` is never eligible.

### 3.5 Extras keep an episode number

`EXTRAS_FOLDER_PATTERN` recognises `featurette` (`:79`) and
`detectSmartNames` sets `contentType = "extra"` (`:356-359`), but nothing
clears `episodeNumber`. `UnifiedVideoEntityCreationService.java:88-90`
writes it unless `episodeManuallyEdited`. Jellyfin's equivalent convention
(`featurettes/`, `extras/`) treats those files as extras, not numbered
episodes.

### 3.6 Series identity is an exact-title join

`UnifiedVideoEntityCreationService.java:157`:

```java
Series series = Series.find("title", result.showName).firstResult();
```

No normalisation, no ID, no folder path. `normalizeForMerge` (`:276-290`)
exists but is never called. Duplicate `Series` rows are an acknowledged
problem (`API/Rest/XtreamCodesAPI.java:1343-1351`), which is why 2604/2605
both exist.

---

## 4. What the two ecosystems do

Sources: Plex "Naming and Organizing Your TV Show Files" (last modified
2026-09-28); Jellyfin "TV Shows" docs.

| Concern | Plex | Jellyfin |
|---|---|---|
| Multi-episode file | `s02e18-e19` — listed as separate episodes; playing any plays the whole file; "we recommend you split" | `S01E01-E02` — shown as **one** entry with merged metadata; "split into individual episodes" |
| Folder ID hint | `Show (2020) {tmdb-123456}` / `{tvdb-…}` / `{imdb-tt…}` | `Show (2020) [imdbid-tt…]` / `[tmdbid-…]` |
| Season folders | must use the English word `Season NN` | must be `Season NN`; explicitly "do not abbreviate to S01" |
| Specials | `Season 00` | `Season 00` |
| Extras folders | separate conventions | `behind the scenes`, `deleted scenes`, `interviews`, `scenes`, `samples`, `shorts`, `featurettes`, `clips`, `other`, `extras`, `trailers` |
| Absolute numbering | alternate ordering (`TheTVDB (Absolute)`) selectable per show | not a first-class ordering |

Two conclusions that matter here:

1. **`Featurettes` is the canonical extras folder name in both.** JMedia's
   folder detection already agrees; only the numbering is wrong.
2. **A provider-ID hint in the folder name is the standard escape hatch**
   for exactly this class of ambiguity. It is deterministic, needs no
   fuzzy matching, and solves Bleach without a single heuristic:
   `Bleach (2004) [tmdbid-30984]` pins both the show and (with a rule) the
   season. JMedia currently ignores these tokens.

TMDb is the outlier: it models TYBW as season 2 of Bleach, which is why
"match the folder to a season" must be expressible.

---

## 5. How the scan pipeline constrains the fix

### 5.1 Triggers (all HTTP, admin-gated; no watcher, no scheduler)

`POST /api/video/scan?mode=full|update` (`VideoAPI.java:656-703`) is the
main entry; per-type variants at `:705-741` and `:743-779`; per-series
reloads at `:977-1031` and `:1033-1085`; per-directory at
`SettingsApi.java:728-760`; management rescan at
`VideoManagementApi.java:304-342`. There is no `WatchService` and no
scheduled video scan.

### 5.2 Incremental vs full

- Unchanged file (`lastModified` equal, `!forceFullScan`) → **skipped
  entirely**, no DB write (`VideoImportService.java:757-780`).
- `forceFullScan` → re-parse every file and rewrite the entity
  (`:785`), with `preserveMetadata = true`.
- `preserveMetadata` is currently a **no-op round-trip** — 21 fields are
  backed up (`UnifiedVideoEntityCreationService.java:46-69`) and restored
  (`:173-197`), but none of them is assigned in between
  (`:71-171`).

### 5.3 Consequence for repair

`Video.type`, `contentType`, `seasonName`, `series` are overwritten
unconditionally on every re-parse (`:73`, `:87`, `:91`, `:148`,
`:152-170`). `seasonNumber`, `episodeNumber`, `title`, `seriesTitle` are
protected **only** by the manual-edit flags (`:76-90`).

Therefore a database-only remap survives incremental scans but is
**reverted by the next full scan**. A repair must choose one:

- set `seasonManuallyEdited` / `episodeManuallyEdited` (makes the row
  permanently manual — changes UI semantics), or
- persist the rule so the parser reproduces it (the
  `forceAbsoluteNumbering` precedent), or
- change the files on disk.

### 5.4 Moves are already tolerated

`VideoImportService.java:793-816` fingerprints new files and, on a hash
match with an existing row, rewrites `Video.path` and `MediaFile.path`
instead of inserting. A renamer therefore preserves watch state and
metadata as long as the media hash is stable. This is the strongest
argument for doing the file reorganisation: the hard part already works.

### 5.5 Structural hazards

| Hazard | Reference |
|---|---|
| `MediaFile` ↔ `Video` have **no FK**; joined by the `path` string | `UnifiedVideoEntityCreationService.java:38`, `Models/Video/MediaFile.java:6-14` |
| `MediaFile.path` is 1000 chars, `Video.path` is 500 | `MediaFile.java:9`, `Models/Video/Video.java:29` |
| No unique index on either path | same |
| Two `Video` rows sharing one path breaks the path-keyed upsert (`firstResult()`) | `UnifiedVideoEntityCreationService.java:38` |
| `isActive` is written **once** and never flipped to false; prune hard-deletes | repo-wide grep, `VideoImportService.java:481-526` |
| Self-invocation defeats `@Transactional`/`@ActivateRequestContext` inside `VideoImportService` | declarations `:742-750`, calls `:258` |
| `rootPath.relativize` throws for a different root and is swallowed | `:782`, `:831`, catch `:838-840` |
| `VideoScanExecutor` is unbounded virtual threads; `videoScanThreads` only toggles on/off | `Services/VideoScanExecutor.java:39-50` |
| `ScanState` writes `"COMPLETED"` while the entity documents `completed` | `VideoImportService.java:304` vs `Models/Video/ScanState.java:16` |
| `resetVideoDatabase` has 5 silent `catch (Exception ignored)` blocks | `:417`, `:420`, `:423`, `:427`, `:434` |

### 5.6 Where a post-scan step belongs

The only place with the full picture is inside `scan()`, after the
completion loop and before `return`, at `VideoImportService.java:307`.
There you already have the `ScanContext` maps (`:210`), the `ScanState`
row (`:240`), and the guarantee that every file task finished (`:268-300`).

It must be gated: `scan()` is also used for single-series (`VideoAPI.java:1004`),
single-season (`:1061`), per-directory (`SettingsApi.java:745`) and
per-parent rescans (`VideoManagementApi.java:337/424/468`). A
library-wide "not seen = missing" pass may only run when `directory`
equals the configured root (`VideoImportService.java:214-215`).

Because of self-invocation (§5.5), the repair work itself must live in an
**injected bean** or use `QuarkusTransaction.requiringNew()` (pattern at
`Migrations/DatabaseMigration.java:142`).

---

## 6. Proposed design

### 6.1 Rules

Each rule is a pure planner function plus an applier. Planning never
writes; applying is journaled.

| ID | Rule | Detects | Action | Writes |
|---|---|---|---|---|
| R1 | `MULTI_EPISODE_RANGE` | `SxxEaa-bb` | split into two files | disk |
| R2 | `EXTRA_WITH_NUMBER` | `contentType=extra` and `episodeNumber != null` | clear the number | DB |
| R3 | `DUPLICATE_SERIES` | equal `(tmdbId, imdbId)` or normalised title | merge, repoint episodes | DB |
| R4 | `STALE_ROOT` | `path` under a retired root | remap via `mediaHash` | DB |
| R5 | `FOLDER_SEASON_MAP` | folder is a named arc of a known show | map folders → one season | DB + override |
| R6 | `ABSOLUTE_MISMATCH` | `episodeNumber > expected[season]` | reuse `resolveAbsoluteNumber` | DB + override |
| R7 | `WRONG_SHOW` | hint/score disagrees with stored IDs | re-resolve, reset enrichment | DB |
| R8 | `ORPHAN_ROW` | file gone | prune via existing semantics | DB |

R1 is the only rule that touches media bytes. It is opt-in.

### 6.2 Identity: folder hints first, scoring second

1. **Parse provider tokens** in the show folder name — `{tmdb-123}`,
   `{imdb-tt123}`, `[tmdbid-123]`, `[imdbid-tt123]` — in
   `analyzePathStructure`, strip them from the display title, and carry
   them on `NamingResult`. `UnifiedVideoEntityCreationService` then sets
   `Series.tmdbId` / `imdbId` directly and skips the search entirely.
2. **Scored search** as the fallback: normalised title similarity
   (token Dice/Jaccard), year proximity, `vote_count` floor; accept the
   best candidate only above a threshold, log rejected candidates at
   `DEBUG`. Replace all eight `results.get(0)` sites.
3. **Season-name fallback**: `TmdbCatalogService.getShow` currently reads
   only `season_number` and `episode_count` (`:97-102`); also capture
   `name`. When a folder name matches a season name
   ("Thousand-Year Blood War"), that is a strong, explainable signal.

### 6.3 Multi-episode files

Two options; the tradeoff is real and should be decided explicitly.

**Option A — split on disk (recommended first).**
`ffmpeg -c copy` at the chapter boundary if chapters exist, else the
midpoint, verifying the two durations. Matches the advice of both Plex and
Jellyfin, needs no schema, no upsert change, no player change. Costs disk
space and is not perfectly frame-accurate with `-c copy`.

**Option B — virtual episodes (no re-encode).**
Two `Video` rows, same `path`, second row carrying
`startOffsetSeconds`. This preserves the exact bytes but requires:
- a `startOffsetSeconds` column and, realistically, a `Video.episodeEnd`;
- changing the upsert key at `UnifiedVideoEntityCreationService.java:38`
  from `find("path", …)` to `(path, season, episode)`, because today the
  second row would never be updated — `firstResult()` always returns the
  first;
- player/HLS support for an initial offset and for mapping progress.

Option B is the better long-term model but is a cross-cutting change.

### 6.4 Season mapping

Add to `Series`:

```
folderSeasonMap   TEXT   -- JSON: {"Bleach-... S01-...": 2, "… S02-…": 2, …}
```

Consulted in `VideoImportService.maybeApplySiblingInference`
(`:601-655`, where the folder and `folderSeason` are already resolved) and
in any reconcile step. It is persisted, so a full scan reproduces the
result — the same durability trick as `forceAbsoluteNumbering`.

### 6.5 Cache invalidation (mandatory)

A repair that changes a show's IDs or seasons must, in order:

1. reset `Video.tmdbId` / `showImdbId` / `enrichmentStatus = NOT_ATTEMPTED`;
2. `EnrichmentNegativeCache.evictContaining(String.valueOf(tmdbId))` and
   the encoded title;
3. add and call eviction for `VideoMetadataService.seriesImdbIdCache`
   (`:126`) and `MetadataSeasonLengthProvider.cache` (`:41`);
4. `TmdbCatalogService.invalidate(tmdbId, null)` (`:334-347`).

Without step 3 the UI keeps the old identity until a restart.

### 6.6 Safety model

- **Plan / apply separation.** Plan is read-only and cheap; apply requires
  an explicit call.
- **Journal.** `repair_action(id, kind, video_id, before, after,
  applied_at, reverted_at)`; "undo last repair" is a query.
- **Never delete media.** Moves go to a staging dir under the library
  root; splits write new files and leave originals until confirmed.
- **Root guard.** Refuse any path outside `Settings.videoLibraryPath`;
  use the defensive relativize pattern at `VideoImportService.java:371-381`.
- **Manual edits win.** Never touch rows with `seasonManuallyEdited`,
  `episodeManuallyEdited`, `titleManuallyEdited`, `seriesTitleManuallyEdited`.
- **Idempotent.** A second run over a repaired library produces an empty
  plan.
- **Respect `EXCLUDED_SCAN_DIRS = {"hls","mp4"}`** (`:45`) and temp/partial
  files (`:542-553`).

### 6.7 Hook points

| Work | Where |
|---|---|
| Range parsing | `SmartNamingService.detectEpisodeInfo` `:713` + regex `:46` |
| Provider tokens | `analyzePathStructure` → `NamingResult` → `UnifiedVideoEntityCreationService` `:152-170` |
| Scored search | `VideoMetadataService` (8 sites) |
| Season map | `VideoImportService.maybeApplySiblingInference` `:601-655` |
| Post-scan reconcile | `VideoImportService.scan` after `:307`, root-only gate |
| Endpoints | `VideoManagementApi` (new `/api/video/manage/repair/*`) |
| UI | existing Needs Attention page + `VideoUiApi` seasons fragment |
| Schema | `Series` / new `REPAIR_ACTION` + idempotent SQL in `src/main/resources/db/`, registered in `DatabaseMigration.onStart` `:43-49` |

---

## 7. Limits manifest (proposed, append to LIMITS.md as a new section)

Following the existing style: measurable, enforceable.

### Architecture
- **L-A1**: every new Java file ≤ 250 non-blank, non-comment lines.
- **L-A2**: the planner is pure — no CDI, DB, FS, or HTTP imports. Enforced
  by a source-grep test (same technique as `PanacheQueryBindingTest`).
- **L-A3**: ≤ 2 new tables (`repair_plan`, `repair_action`); ≤ 4 new columns.
- **L-A4**: ≤ 4 new endpoints, all under `/api/video/manage/repair`.

### Feature
- **L-F1**: plan is read-only; zero mutations before an explicit apply.
- **L-F2**: no media file is ever deleted. Moves are journaled and undoable.
- **L-F3**: every rule is idempotent; a second plan on a repaired library is empty.
- **L-F4**: rows with any manual-edit flag are never modified.
- **L-F5**: no fuzzy merge of series and no automatic deletion of Series rows
  without a merge journal entry.
- **L-F6**: provider-ID tokens in folder names are authoritative and bypass search.

### Data
- **L-D1**: `Video.path` and `MediaFile.path` are only ever rewritten to an
  existing file, verified before flush.
- **L-D2**: path writes preserve the absolute form; relative paths are upgraded, never introduced.

### Performance
- **L-S1**: audit ≤ 2 s per 1 000 episodes, no network calls.
- **L-S2**: apply batches ≤ 200 rows per transaction.

### Scope
- **L-C1**: phase 1 (parser + extras + audit) ≤ 6 files, ≤ 500 changed lines.
- **L-C2**: existing tests stay green; new tests are offline (`@TempDir`, pure statics).
- **L-C3**: no changes to transcoding, HLS, or playback in phase 1.

### Dependencies
- **L-Dep1**: no new runtime dependencies.

---

## 8. Test plan (matching the existing suite)

The project has **no `@QuarkusTest` and no mocking library**; tests target
package-private pure functions with `@TempDir` fixtures
(`SmartNamingServiceTest`, `SiblingInferenceTest`, `AbsoluteBackfillTest`,
`SeasonEpisodeCountsTest`).

Planned offline seams:

- `parseEpisodeRange(filename)` → `(start, end)`; cases: `E01-02`,
  `E01-E02`, `E01–02` (en dash), `E01-02v2`, `E01E02`, `E01` (no range),
  `S01E01-02 - Title` (title must not absorb `-02`).
- `parseFolderProviderHint(name)` → `(kind, id)`; cases: `{tmdb-30984}`,
  `[tmdbid-30984]`, `{imdb-tt0434665}`, `[imdbid-tt…]`, none, colliding
  tokens, year-only folder.
- `scoreCandidate(local, candidate, year)` → double; cases: exact, prefix,
  subtitle-only, wrong year, low vote count, tie.
- `planRepair(snapshot)` → list of actions; fed from a plain record, no DB.
- `nextEpisodeUpsertKey(path, season, episode)` — regression guard for
  the Option B upsert change.
- Idempotency test: `planRepair(apply(plan))` is empty.

---

## 9. Phasing

| Phase | Content | Touches media? | Risk |
|---|---|---|---|
| 0 | Read-only auditor + Needs Attention surface | no | none |
| 1 | Range parsing, extras numbering, duplicate-series merge, stale-root remap | no (DB only) | low |
| 2 | Provider-ID hints + scored search + cache eviction | no | low |
| 3 | Season mapping column + single-season collapse | no | medium |
| 4 | File reorganisation (rename/move) with journal + undo | yes | medium |
| 5 | Multi-episode resolution (split, then optionally virtual rows) | yes | high |

Phases 0–3 never touch a byte on disk.

---

## 10. Decisions

Resolved 2026-11-11:

1. **Seeding** — not applicable. Nothing on `/mnt/storage` is being seeded, so
   phase 4 (file reorganisation) is unblocked. *(BitTorrent "seeding" means
   keeping a torrent's files and uploading to peers; renaming/moving breaks
   the client's file mapping. Not a concern here.)*
2. **FreeFileSync** — dropped from scope per owner.
3. **Empty season cards** — accepted. Treating TYBW as Bleach S2 will show
   "Season 1 — 0 of 366"; no hide-season flag will be added.
4. **Durability** — persisted folder→season map only. The repair pass will
   **not** set `seasonManuallyEdited` / `episodeManuallyEdited`, so those
   flags keep meaning "the user did this".

Still open:

5. **Multi-episode strategy** — split on disk vs virtual rows. See §6.3 for
   the full tradeoff; pending owner choice.

---

## 11. Decision log (superseded text)

The following questions were resolved above and are kept only for context:

1. **Seeding.** ~~Renaming media breaks torrents. Is anything on
   `/mnt/storage` actively seeding? If yes, phase 4 must be opt-in per show.~~
2. **FreeFileSync.** ~~`/mnt/storage/sync.ffs_db` exists; a mass rename will
   make the next mirror run treat files as new/deleted and could propagate
   deletions. Pause the job, or feed it the repair journal.~~
3. **Multi-episode strategy**: split (simple, standard) vs virtual rows
   (no re-encode, cross-cutting). Recommend split first.
4. **Empty season cards**: ~~treating TYBW as Bleach S2 will always render a
   "Season 1 — 0 of 366" card. Accept, or add a per-series "hide seasons"
   flag?~~
5. **Manual flags**: ~~may the reconciler set `seasonManuallyEdited` to make a
   remap survive full scans, or should durability come from the persisted
   map only?~~ Recommendation adopted: map only.

---

## Appendix A — Server evidence

Read-only queries against a copy of `~/.jmedia/jmedia-video.mv.db`
(taken while the app was running; no writes):

```sql
SELECT ID, TITLE, IMDBID, TMDBID, RELEASEYEAR FROM SERIES
 WHERE ID IN (2604,2605,35);

SELECT SEASONNUMBER, EPISODENUMBER, CONTENTTYPE, EPISODETITLE, FILENAME
  FROM VIDEO WHERE SERIES_ID = 35 ORDER BY SEASONNUMBER, EPISODENUMBER;

SELECT SEASONNUMBER, EPISODECOUNT, LASTAIRDATE
  FROM SEASON_METADATA_CACHE WHERE TMDBID = 30984 ORDER BY SEASONNUMBER;

SELECT SEASONNUMBER, EPISODECOUNT
  FROM SEASON_METADATA_CACHE WHERE TMDBID = 61175 ORDER BY SEASONNUMBER;
```

Episode ordering cross-checked against the cached TMDb payload for
`30984 / season 2` (`PAYLOADJSON`), and against a live
`GET https://api.themoviedb.org/3/tv/30984`.

## Appendix B — Naming examples (canonical output)

```
/TV Shows/Bleach (2004) [tmdbid-30984]/
  Season 2/
    Bleach - S02E01 - The Blood Warfare.mkv
    ...
    Bleach - S02E50 - Death & Strawberry.mkv
  Featurettes/
    Textless OP1.mkv
```

```
/TV Shows/Steven Universe (2013) [tmdbid-61175]/
  Season 1/
    Steven Universe - S01E01 - Gem Glow.mkv
    Steven Universe - S01E02 - Laser Light Cannon.mkv
```

Both are valid input for Plex and Jellyfin as well as JMedia.
