# Limits Manifest — Music Multi-Genre

Version: 1
Status: active
Owner: JMedia
Last reviewed: 2026-11-05

## Scope

Resolve each song's raw genre string into **all** canonical genres it belongs to, store the full set, and show the song under every one of those genres. Unknown/blank/sentinel values are grouped under a single **Unidentified** genre.

Phase 1 only: resolver, link table, backfill, genre list/count/drill-down. No admin UI, no fuzzy merge, no DJ-pool rewrite.

## Architecture Limits

- L-A1: Each new Java source file ≤ 250 non-blank, non-comment lines. Measure: `awk` line count. Enforce: review.
- L-A2: `GenreNormalizer` is pure — no CDI, DB, HTTP, or framework imports. Measure: import grep. Enforce: unit test + review.
- L-A3: At most 2 new music tables (`song_genres`, optional `music_genre_alias`); zero changes to the video persistence unit.
- L-A4: At most 3 new REST endpoints, all under `/api/music/genres`.

## Feature Limits

- L-F1: A song resolves to **all** distinct canonical genres in its raw string; there is no cap. Deduplicate by canonical key; preserve first-seen order.
- L-F2: `null`, blank, `Unknown`, `Unknown Genre`, `N/A`, `None`, `Other`, `Misc`, `Various` (case/whitespace-insensitive) all map to the single canonical genre **Unidentified**.
- L-F3: `Unidentified` appears in the genre list with a real count; it is never hidden or dropped.
- L-F4: A song appears under every resolved genre (e.g. `Rock/Pop` appears under Rock and Pop).
- L-F5: Explicit list separators (`/ \ , ; | • · +`) split; whole-string aliases win first so `R&B`, `Drum & Bass`, `Hip-Hop` are not broken.
- L-F6: No automatic fuzzy/near-match merging; only exact alias/taxonomy mappings.

## Data Limits

- L-D1: `Song.genre` (the raw tag) is never mutated. Measure: test + review.
- L-D2: Backfill is idempotent and batched ≤ 200 rows per transaction.
- L-D3: `song_genres` has `UNIQUE(song_id, genre_key)` and `INDEX(genre_key)`.

## Performance Limits

- L-S1: Genre list/count/drill-down use indexed columns; no `REPLACE()` group-by or full-table scan.
- L-S2: Genre page p95 ≤ 300 ms for a 10k-song library (manual benchmark).

## Scope Limits

- L-C1: Phase 1 changes ≤ 8 files, ≤ 400 changed lines.
- L-C2: Existing tests stay green; new resolver tests cover ≥ 10 variant classes (case, separators, `R&B`, `Drum & Bass`, `Hip-Hop/Rap`, tabs/NBSP, accents, numeric ID3, Unknown, Turkish locale).
- L-C3: No mixed refactors; video code untouched.

## Dependency Limits

- L-Dep1: No new runtime dependencies.

## Exceptions

- None.

## Changelog

- v1 (2026-11-05): initial manifest. User-directed: no 4-genre cap (L-F1), unknown/blank grouped under `Unidentified` (L-F2/L-F3).
