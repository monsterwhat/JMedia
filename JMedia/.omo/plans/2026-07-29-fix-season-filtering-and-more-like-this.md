# Plan: Fix Season Filtering in Cinema Modal + More Like This Enhancement

## Context
The `video-test.html` page has a systemic bug where `seasonNumber` values of `0` (Specials) or negative (additional specials) are coerced to `1` by the `|| 1` pattern. This causes special-season episodes to be mixed into Season 1's listing.

The SPA backend (`VideoUiApi.java`) handles this correctly:
- `sn == 0` → `seasonName` if set, else "Specials"
- `sn > 0` → `seasonName` if set, else "Season {sn}"
- Null-season episodes → grouped by `contentType` as "Extras"

## Bug Locations (6 sites, all in `video-test.html`)

| # | Line | Current Code | Problem | Fix |
|---|------|-------------|---------|-----|
| 1 | 2154 | `a.seasonNumber || 1` (sort) | `0` → `1` | `a.seasonNumber ?? 1` |
| 2 | 2163 | `ep.seasonNumber || 1` (group) | `0` → `1` | `ep.seasonNumber ?? 1` |
| 3 | 2612 | `(v.seasonNumber || 1) == seasonNumber` (filter) | `0` never matches `seasonNumber=0` | `(v.seasonNumber ?? (seasonNumber === 1 ? 1 : null)) == seasonNumber` — so nulls match season 1 only, 0 matches 0 |
| 4 | 3244 | `ep.seasonNumber = data.seasonNumber || 1` (merge) | Persistently overwrites `0` | `data.seasonNumber ?? 1` |
| 5 | 3281 | `a.seasonNumber || 1` (sidebar sort) | Same as #1 | `a.seasonNumber ?? 1` |
| 6 | 3291 | `ep.seasonNumber || 1` (sidebar group) | Same as #2 | `ep.seasonNumber ?? 1` |
| 7 | 3274 | `parseInt(container.dataset.seasonNumber) || 1` | Same | `?? 1` |

## Label Fixes (season display in dropdown)

The label for the season dropdown and dropdown items must match the SPA convention:

| # | Line | Current | Fix |
|---|------|---------|-----|
| 8 | 2355 | ``Season ${seasonNumbers[0]}`` | Create helper `getSeasonLabel(sn)` that returns `"Specials"` for `sn===0`, `"Specials N"` for `sn<0`, `"Season {sn}"` for `sn>0` |
| 9 | 2360 | ``Season ${sn}`` | Same helper; also use `data-sn` attribute for active-state matching instead of text-content comparison |
| 10 | 2601 | ``Season ${seasonNumber}`` | Same helper |

### Label Heuristic (since `seasonName` is not in the API response)
```
function getSeasonLabel(sn):
  sn === 0     → "Specials"
  sn < 0       → "Specials " + (Math.abs(sn) + 1)   // -1→"Specials 2", -2→"Specials 3"
  sn > 0       → "Season " + sn
  null/undefined → "Season 1"
```

This matches the SPA backend convention for season 0 ("Specials") while providing sensible defaults for negative/unexpected values.

## Filter Fix for `switchSeason` (critical)

Current (line 2612):
```js
.filter(v => v.type === 'episode' && v.seriesTitle === seriesTitle && (v.seasonNumber || 1) == seasonNumber)
```

Fixed:
```js
.filter(v => v.type === 'episode' && v.seriesTitle === seriesTitle 
    && (v.seasonNumber != null ? v.seasonNumber : 1) == seasonNumber)
```

This way:
- `seasonNumber=0` matches only episodes with `v.seasonNumber === 0`
- `seasonNumber=1` matches episodes with `v.seasonNumber === 1 || v.seasonNumber === null`
- `seasonNumber=2+` matches exactly

## More Like This Enhancement (after season fix)

Ref. `buildMoreLikeThis()` at line 2483. Current issues:
1. For TV shows, cards use **episode-level** data (thumbnail, title from single episode) instead of **series-level** data
2. No related movies from same franchise/collection are included

### Changes:

**A. TV show recommendations — series-level cards**
- Build from `seriesLookup` entries instead of episode objects
- Card image: `series.posterPath` or `series.backdropPath` → need an image URL. Since series has `posterPath` which is a local path, fall back to `getThumbnailUrl(sampleEpisode.id)` for now, but use series title for display
- Card title: `series.title`
- Card metadata: `series.genres`, `series.imdbRating → matchPct`, `series.releaseYear`
- Click: `closeModal(); openSeriesDetail(series.title)` — already works

**B. Franchise/collection movies**
- When current video belongs to a series with `franchiseName` or `collectionName`, scan `allVideos` for movies (`type !== 'episode'`) with same `franchiseName` or `collectionName`
- Include these as recommendation cards (movie cards, using movie's own thumbnail/title)
- Click: `closeModal(); openDetails(videoId)`

**C. Fallback order**
1. Genre-matched TV shows (from seriesLookup, excluding current series)
2. Plus franchise/collection movies
3. Cap at 12 total
4. If still empty, fall back to genre-matched movies (existing behavior for non-episode types)

## Order of Implementation
1. Fix the 7 `|| 1` bugs (items 1-7 in table)
2. Fix season labels (items 8-10)
3. Verify season switching works correctly for Specials (0), Season 1, and negative specials
4. Rebuild `buildMoreLikeThis()` for series-level recommendations + franchise movies
