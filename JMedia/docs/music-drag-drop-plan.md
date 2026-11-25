# Mobile drag-and-drop for songs, albums, and playlists

Status: **plan only — nothing implemented yet.**

## 1. What the app has today

### Data model
- There is **no Album entity**. An album is the group
  `(Song.album, Song.albumArtist)`, built by `SongService.findAlbums()`.
  - “Create album” / “add to album” therefore means **writing `album` + `albumArtist` on songs**.
  - A song moving to another album leaves its old album; if it was the last song, that album card disappears.
- Playlists are real entities (`Playlist`), and the list JSON excludes songs (`@JsonIgnore`).
- Playlist adds are deduped by `PlaylistService.addSongsToPlaylist()`.
- Album songs are ordered by `discNumber, trackNumber, title` (`findSongIdsByAlbum`).

### UI building blocks
- Song rows: `.mobile-song-item[data-song-id]` in `mobileSongItemsFragment.html` and `mobilePlaylistTableBodyFragment.html`.
- Album cards: `.mobile-album-card` with `data-album-name`, `data-album-artist`, `data-first-song-id`, and a visible `.mobile-album-meta` count.
- Playlist cards: `.mobile-playlist-card` with `data-playlist-id`, `data-playlist-name`, and a visible `.mobile-playlist-count`.
- Lists are **HTMX fragments**; there is no single “refresh current view” helper.
- Long-press is already taken by `MobileContextMenu`: touchstart + timer, cancelled on touchmove.
- There is no touch drag/drop. Desktop uses HTML5 DnD only for collection reordering (`CollectionManager.js`).
- A `.mobile-side-panel` style exists, but no current markup uses it.
- `.mobile-modal` styles exist and are used for the “Add to Playlist” flow.
- Music modules are injected by `music-bundle-loader.js`; a new file must be added to `MUSIC_SCRIPTS`.

### APIs that already exist
- `GET /api/music/playlists/{profileId}` — lightweight playlist list.
- `POST /api/music/playlists/` — create playlist.
- `POST /api/music/playlists/{id}/songs/bulk` — bulk add, deduped.
- `GET /api/music/playlists/{id}/song-ids` — ordered playlist song IDs.
- `GET /api/music/ui/collection-song-ids/{profileId}?type=album&value=...&albumArtist=...` — album song IDs.
- `MetadataWriteService.writeMetadataToFile(song, absolutePath)` — writes tags.

### API gaps
- No JSON album-list endpoint.
- No album-metadata assignment endpoint.
- No bulk music operation/progress endpoint.

---

## 2. Interaction design (mobile-first)

### Gesture
1. `pointerdown` on a draggable card records the start point. Nothing is blocked yet.
2. **Horizontal move > 12 px** and `|dx| > |dy|` → start drag.
   - **Vertical move > 12 px** → cancel; native scroll continues.
   - **No movement for ~500 ms** → existing long-press menu opens.
3. On drag start:
   - Cancel the context-menu timer and remove `.long-press-active`.
   - `setPointerCapture` on the source.
   - Add a floating clone (“ghost”) under the finger, `pointer-events: none`.
   - Dim the source (`.drag-source`).
   - Slide the **right-side drawer** in immediately.
   - Haptic tick where supported.
4. During drag:
   - Move the ghost with `transform`.
   - `document.elementFromPoint()` finds the drawer item under the finger.
   - Highlight it and show an action preview (“Add 1 song to Chill”, “Move 12 songs into Album X”).
   - Auto-scroll the drawer near its top/bottom edges.
5. Release:
   - Over a destination → show confirmation if bulky/destructive, then execute.
   - Outside → cancel.
   - Suppress the click that follows a drag.

### Drawer
- Slides from the right, `width: min(380px, 88vw)`, above the player (`z-index` > 2000).
- Header: source summary (`Song · Title`, `Album · Name (12 songs)`, `Playlist · Name (34 songs)`) and a Cancel button.
- Sections:
  - **Playlists** — list/chips + **New playlist**.
  - **Albums** — list/chips with artwork, artist, count + **New album**.
  - Search fields for long lists.
- Footer: **Update file tags** switch (album actions only; default off, remembered locally).
- Tapping a destination also works (accessibility fallback).

### Naming/confirmation
- Reuse `.mobile-modal`:
  - New album: name, album artist, “Also update file tags”.
  - New playlist: name.
  - Bulk/destructive confirm: source, destination, count, and what will change.

---

## 3. What happens with albums and playlists as sources

Everything is dropped **on the drawer**, never on another song.

| Source | Destination | Behavior |
|---|---|---|
| Song (1) | Playlist | Add the song; duplicate is skipped. |
| Song (1) | New playlist | Create playlist, then add the song. |
| Song (1) | Album | Set `album`/`albumArtist` to the target album. |
| Song (1) | New album | Prompt name/artist; set both fields. |
| Album A (N) | Playlist | Add all N songs in disc/track order; duplicates skipped. |
| Album A | New playlist | Create playlist and add all N. |
| Album A | Album B | **Merge**: assign all A songs to B. A may disappear. Confirm with counts. |
| Album A | New album | **Rename/re-album**: assign all A songs to the new name/artist (prefilled). Confirm. |
| Album A | Album A | No-op. |
| Playlist P (N) | Playlist Q | **Merge**: add all P songs to Q; duplicates skipped. P=Q is a no-op. |
| Playlist P | New playlist | **Duplicate** P (name prompt prefilled “P copy”). |
| Playlist P | Album B | **Bulk metadata change**: assign all N songs to B. Strong confirmation with count. |
| Playlist P | New album | Assign all N songs to a new album. Strong confirmation. |

Rules:
- Album changes are **moves**, not copies. The target album artist is always used.
- Playlist adds preserve source order and dedupe.
- Track numbers are left unchanged (album grouping does not use them).
- Artwork is unchanged; a new album shows the first song’s existing art.
- Warn when a playlist contains thousands of songs (e.g. the 5,266-song playlist). Offer a count and require confirmation.

---

## 4. Implementation plan

### Client
**New: `js/jmedia/MusicDragDrop.js`** (add to `music-bundle-loader.js` after `MobileContextMenu.js`)
- `DragSource` descriptor: `{type: 'song'|'album'|'playlist', label, count, songId?, albumName?, albumArtist?, playlistId?, resolveSongIds()}`.
- Pointer gesture recognizer with thresholds, pointer capture, cancel-on-scroll, long-press cancellation.
- Ghost + source classes.
- Drawer DOM creation, open/close, skeleton loading, search, auto-scroll.
- Destination detection via `elementFromPoint`.
- Action execution + confirmation modals.
- Drag session cache for playlists/albums; invalidate after changes.

**CSS**
- `jmedia-unified.css`:
  - `.music-drag-ghost`, `.drag-source`, `.drag-over`, `.drag-preview`.
  - `.music-drop-drawer`, overlay, sections, item cards, search, footer.
  - `touch-action: pan-y` on draggable cards so vertical scroll stays native.
  - Mobile sizes; desktop drawer layout.

**Templates**
- Add `data-song-count` to album and playlist cards (avoid parsing text).
- Optionally add a visible grip affordance? Not needed for press-and-drag.

**Refresh**
- Add `window.refreshMusicAfterDrop(sourceType)` in `MusicIndex.js`:
  - always refresh the sidebar playlists;
  - if the Songs view is open: `loadMobilePlaylistSongs(window.mobileSongListState.playlistId)`;
  - if Albums is open: `switchToTab('albums')`;
  - if Playlists is open: `switchToTab('playlists')`;
  - if an album detail page is open: reload that album page.
- Cancel any drag on `htmx:beforeSwap` if the dragged element is replaced.

### Server
**New `API/Rest/AlbumAPI.java` at `/api/music/albums`**
- `GET /api/music/albums?offset=0&limit=200&search=`
  - Auth via active profile headers.
  - Uses `SongService.findAlbums`.
  - JSON: `{items:[{album, albumArtist, firstSongId, songCount, year}], total}`.
- `POST /api/music/albums/assign`
  - Body: `{songIds:[...], album, albumArtist, writeTags:false}`.
  - Validate album non-empty and IDs non-empty; cap request size.
  - DB transaction: set fields, persist; log INFO with count and target.
  - If `writeTags`: resolve each file from `Settings.getMusicFolder()` and call
    `MetadataWriteService.writeMetadataToFile`; report per-song failures.
  - For large `writeTags` batches, return after DB update and run tags in a bounded
    background executor with a toast/progress hook (or reject above a threshold).
- Reuse existing playlist endpoints for playlist actions.

**Logging**
- SLF4J INFO for successful bulk actions, WARN for skipped/missing songs, ERROR with cause for failed tag writes. No silent catches.

---

## 5. Edge cases and risks

- **Long-press conflict**: drag start must clear the context-menu timer. Vertical movement must not trigger drag.
- **iOS scroll/pointer behavior**: `touch-action: pan-y`, pointer capture, and `preventDefault` after the threshold; test on real iOS Safari.
- **HTMX swaps while dragging**: cancel the session cleanly.
- **Click after drag**: suppress the synthetic click.
- **Enormous playlists**: confirm counts; batch DB writes; do not run 5,000 file-tag writes synchronously.
- **Same source/destination**: no-op toast.
- **Unknown Album/blank album artist**: ask for a name; do not create silently.
- **File-tag failures**: per-song report, keep the DB change, log the cause.
- **Rescan**: DB-only album changes can be reverted by a library rescan; the tag switch is the permanent option.
- **No undo**: confirmations are the safety net.

---

## 6. Phasing

- **Phase 1 (core request)** — gesture, drawer, **song source**, playlist destinations, album destinations, new album/playlist prompts, album assign API, DB-only with optional tags.
- **Phase 2** — **album source**: add to playlist, merge into album, rename via New album.
- **Phase 3** — **playlist source**: merge/duplicate, bulk album assignment, strong confirmations and batching.
- **Phase 4** — polish: search, recent destinations, auto-scroll tuning, desktop parity, haptics.

Rationale: the gesture and drawer are the risky parts; shipping them with one song source validates the interaction before bulk operations.

---

## 7. Test plan

- Horizontal drag starts; vertical swipe scrolls; long-press still opens the menu; tap still plays.
- Drawer loads playlists/albums; search works; new buttons open prompts.
- Song → playlist, duplicate song, new playlist.
- Song → existing album, new album, with tags off and on.
- Album → playlist/new playlist/merge/rename; merge confirmation; old album disappears.
- Playlist → playlist/new playlist/bulk album; large-playlist warning.
- Same source/destination no-op.
- Current view refreshes correctly after each action.
- Files: tag write success/failure logged; DB unchanged on validation errors.
- Devices: iPhone Safari, Android Chrome, desktop Chrome.
- Server log audit: no unexpected ERROR/Exception; bulk actions logged.

---

## 8. Decisions still open

1. Do you want Phase 1 only at first, or all three source types in one release?
2. Album merge should be a move (recommended) or a copy (duplicate each song under the new album)?
3. For a playlist → album bulk move, should there be a hard cap (e.g. 200 songs) or just a confirmation?
4. Should “Update file tags” be a per-drop switch (recommended) or a global setting?
