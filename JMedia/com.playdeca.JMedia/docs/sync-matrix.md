# Sync Matrix

Metadata exchange between JMedia instances. Paths are relative to
`JMedia/com.playdeca.JMedia/`.

All rows travel through `POST /api/sync/exchange`
(`src/main/java/API/Rest/SyncExchangeAPI.java`), which calls
`SyncExchangeService.exchange` (`src/main/java/Services/SyncExchangeService.java`).
The outbound client is `RemoteJMediaClient.exchange`
(`src/main/java/Services/RemoteJMediaClient.java`). Requests authenticate with the
`X-JMedia-Sync-Key` header; it is checked by `API/Rest/SyncExchangeAPI.java` and
also lets the request past `Filters/JMediaAuthFilter.java`.

| Entity | DTO | Endpoint / code proof | Direction | Match key | Applied on the receiver |
| --- | --- | --- | --- | --- | --- |
| Song metadata and beat analysis | `Models/DTOs/SyncSongData.java` | `SyncExchangeRequest.songs` to `processSongExchange` | Send and receive | `musicbrainzId` | Updates an existing song when the remote `updatedAt` is newer. Unknown MusicBrainz IDs are skipped, not created. Sender sets `lyrics` to null. |
| Video and episode metadata | `Models/DTOs/SyncVideoData.java` | `SyncExchangeRequest.videos` to `processVideoExchange` | Send and receive | `videoId` (Long) | Updates an existing video when the remote `dateModified` is newer. Series-level fields are written to the related `Series` when present. Unknown IDs are skipped. |
| Video collection header | `Models/DTOs/SyncCollectionData.java` | `SyncExchangeRequest.collections` to `processCollectionExchange` | Send; receiver applies | `collectionId` (Long) | Updates name, description, visibility, cover, and sort order of an existing collection. `entries` is serialized by the sender but the receiver never applies it. |
| Subtitle track metadata | `Models/DTOs/SyncSubtitleData.java` | `SyncExchangeRequest.subtitles` to `processSubtitleExchange` | Send; receiver applies | `trackId` (Long) | Creates a `SubtitleTrack` when the ID is unknown, otherwise applies language, display name, format, and flags. `subtitleContent` is not applied. |
| Exchange envelope | `Models/DTOs/SyncExchangeRequest.java`, `SyncExchangeResponse.java` | `/api/sync/exchange`, `/api/sync/ping` | Both | none | Request carries the four lists plus `syncType` and `limit`. Response carries `updatedIds`, `createdIds`, `errors`, and newer local songs/videos. |

The response lists for collections and subtitles are never populated:
`processCollectionExchange` and `processSubtitleExchange` never add returned
items. Only songs and videos are sent back when the local copy is newer.

## Not synchronized

The following are explicitly absent from `SyncExchangeRequest` and from every
sync DTO:

- Playlists. `Services/SyncService.java` has `syncPlaylists` as a placeholder that
  logs "not yet implemented" and sets `playlistsSent = 0`.
- Playback position and playback state (`Services/PlaybackStateService.java`,
  `API/Rest/PlaybackAPI.java`). No DTO field carries a queue index, track, or
  timestamp.
- Watch progress and resume positions (`Services/VideoHistoryService.java`,
  `Services/CollectionWatchProgressService.java`).
- Timelines. `Services/SyncService.java` has `syncTimelines` as a placeholder with
  the same "not yet implemented" log line.
- User accounts, profiles, sessions, permissions, and application settings
  (`Models/Settings/User.java`, `Profile.java`, `Settings.java`).
- Media files, artwork binaries, and lyrics. `SyncSongData.artworkBase64` exists
  but is never populated or applied; lyrics are nulled by the sender.

## Known limits

- **Outbound scheduled sync cannot complete.** `SyncService` writes its log row in
  the settings database and then reads/writes songs in the music database. Narayana
  refuses to enlist a second datasource in one JTA transaction
  (`Failed to enlist. Check if a connection from another datasource is already
  enlisted`), so the content step aborts and the log row stays `IN_PROGRESS`.
  `it-sync-exchange.sh` drives a real two-instance sync and currently skips the two
  assertions that would require a completed run. The inbound `exchange` path is
  unaffected and fully exercised.
- **Subtitle identity is the file path on the receiver.** Rows are created with an
  auto-assigned id, so the remote `trackId` cannot be used as a row id. Matching on
  `fullPath` is what makes a repeated exchange idempotent; before that fix every
  delivery inserted another copy. The exchange response reports the *local* id in
  `updatedIds`.
- There is no create path for songs, videos, or collections. The receiver must
  already have a row with the same ID (videos, collections) or MusicBrainz ID
  (songs), so independently built libraries will not merge by title.
- IDs are not translated between servers. `videoId`, `collectionId`, and
  `trackId` are matched directly against the receiver's own IDs.
- Subtitle sync transfers metadata including `fullPath`, not the subtitle file
  contents, so the path must be meaningful on the receiver.
- Collection membership (`SyncCollectionData.entries`) is built outbound but not
  read inbound.
