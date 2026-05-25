# JMedia API Documentation

## Overview

JMedia is a comprehensive media management and streaming application built with Quarkus. This API provides endpoints for music and video playback, library management, user profiles, playlists, settings, and real-time WebSocket communication.

## Base URL

```
http://localhost:8080
```

## Authentication

Authentication is **required** for most API endpoints. JMedia uses a **session-based** auth system with cookie-based sessions.

### Auth Flow

1. **Login**: POST to `/api/auth/login` with `username` and `password` (form-encoded)
2. **Session Cookie**: A session cookie (`JSESSIONID`) is set on successful login
3. **Authenticated Requests**: Include the session cookie in subsequent requests
4. **Logout**: POST to `/api/auth/logout` to invalidate the session

### Rate Limiting

Auth endpoints are rate-limited:
- **IP-based**: Max 5 attempts per minute per IP
- **Per-username**: Max 3 attempts per minute per username
- Returns `429 Too Many Requests` when exceeded

### Access Control

| Role | Description |
|------|-------------|
| `admin` | Full access to all endpoints, user management |
| `user` | Standard access to music/video playback and library |

Protected endpoints return `401 Unauthorized` if no valid session, or `403 Forbidden` if the user role lacks permission.

## Response Format

All REST API responses follow the standard `ApiResponse` format:

```json
{
  "data": {}, // Success data or null
  "error": "" // Error message or null
}
```

## REST API Endpoints

### Music API

#### Playback Control

**Base Path:** `/api/music/playback`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| GET | `/current/{profileId}` | Get currently playing song | `profileId` (path) |
| GET | `/previousSong/{profileId}` | Get previous song | `profileId` (path) |
| GET | `/nextSong/{profileId}` | Get next song | `profileId` (path) |
| GET | `/state/{profileId}` | Get full playback state | `profileId` (path) |
| POST | `/toggle/{profileId}` | Toggle play/pause | `profileId` (path) |
| POST | `/play/{profileId}` | Start playback | `profileId` (path) |
| POST | `/pause/{profileId}` | Pause playback | `profileId` (path) |
| POST | `/next/{profileId}` | Skip to next song | `profileId` (path) |
| POST | `/previous/{profileId}` | Skip to previous song | `profileId` (path) |
| POST | `/select/{profileId}/{id}` | Select specific song | `profileId`, `id` (path) |
| POST | `/shuffle/{profileId}` | Toggle shuffle mode | `profileId` (path) |
| POST | `/dj-mode/{profileId}` | Toggle DJ mode | `profileId` (path) |
| POST | `/dj-mode-set/{profileId}/{active}` | Set DJ mode state | `profileId`, `active` (path) |
| POST | `/repeat/{profileId}` | Cycle repeat mode (OFF/ONE/ALL) | `profileId` (path) |
| POST | `/volume/{profileId}/{level}` | Set volume level (0-100) | `profileId`, `level` (path) |
| POST | `/position/{profileId}/{seconds}` | Set playback position | `profileId`, `seconds` (path) |
| GET | `/crossfade/{profileId}` | Get crossfade duration | `profileId` (path) |
| POST | `/crossfade/{profileId}/{seconds}` | Set crossfade duration | `profileId`, `seconds` (path) |
| POST | `/transition-started/{profileId}` | Signal transition started | `profileId` (path) |

#### Queue Management

**Base Path:** `/api/music`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| GET | `/queue/{profileId}` | Get current queue | `profileId` (path) |
| POST | `/playback/queue-all/{profileId}/{id}` | Queue all songs from playlist | `profileId`, `id` (path) |
| POST | `/queue/add/{profileId}/{songId}` | Add song to queue | `profileId`, `songId` (path) |
| POST | `/queue/skip-to/{profileId}/{index}` | Skip to queue index | `profileId`, `index` (path) |
| POST | `/queue/remove/{profileId}/{index}` | Remove song from queue | `profileId`, `index` (path) |
| POST | `/queue/clear/{profileId}` | Clear queue | `profileId` (path) |
| GET | `/history/{profileId}` | Get playback history | `profileId` (path), `page`, `limit` (query) |

#### Song Management

**Base Path:** `/api/song`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| GET | `/{id}/lyrics` | Get song lyrics | `id` (path) |
| POST | `/{id}/generate-lyrics` | Generate lyrics using Whisper | `id` (path), `model` (query) |
| POST | `/{id}/write-metadata` | Write metadata back to audio file | `id` (path), SongMetadata (body) |
| POST | `/write-all-metadata` | Write metadata for all songs | None |

#### Playlist Management

**Base Path:** `/api/music/playlists`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| GET | `/{profileId}` | List playlists for profile | `profileId` (path) |
| GET | `/{id}` | Get specific playlist | `id` (path) |
| POST | `/` | Create new playlist | Playlist object (body) |
| PUT | `/{id}` | Update playlist | `id` (path), Playlist object (body) |
| DELETE | `/{id}` | Delete playlist | `id` (path) |
| POST | `/{playlistId}/songs/{songId}/{profileId}` | Add song to playlist | `playlistId`, `songId`, `profileId` (path) |
| DELETE | `/{playlistId}/songs/{songId}` | Remove song from playlist | `playlistId`, `songId` (path) |
| POST | `/{playlistId}/songs/{songId}/toggle/{profileId}` | Toggle song in playlist | `playlistId`, `songId`, `profileId` (path) |
| POST | `/{playlistId}/toggle-shared` | Toggle playlist sharing | `playlistId` (path), JSON body with `isShared` |

#### Music Streaming

**Base Path:** `/api/music/stream`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| GET | `/{profileId}/{id}` | Stream audio file | `profileId`, `id` (path), `Range` (header) |

#### Music UI Fragments (HTMX)

**Base Path:** `/api/music/ui`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| POST | `/playback/queue-all/{profileId}/{id}` | Queue all songs and return HTML fragment | `profileId`, `id` (path) |
| POST | `/queue/skip-to/{profileId}/{index}` | Skip to queue index and return HTML fragment | `profileId`, `index` (path) |
| POST | `/queue/remove/{profileId}/{index}` | Remove from queue and return HTML fragment | `profileId`, `index` (path) |
| POST | `/queue/clear/{profileId}` | Clear queue and return HTML fragment | `profileId` (path) |
| GET | `/playlists-fragment/{profileId}` | Get playlists HTML fragment | `profileId` (path) |
| GET | `/playlist-view/{profileId}/{id}` | Get playlist view HTML | `profileId`, `id` (path), pagination params (query) |
| GET | `/tbody/{profileId}/{id}` | Get playlist table body HTML | `profileId`, `id` (path), pagination params (query) |
| GET | `/queue-fragment/{profileId}` | Get queue HTML fragment | `profileId` (path), pagination params (query) |
| GET | `/add-to-playlist-dialog/{profileId}/{songId}` | Get add to playlist dialog HTML | `profileId`, `songId` (path) |
| POST | `/search-suggestions/{profileId}` | Get search suggestions HTML | `profileId` (path), `searchQuery` (form) |
| GET | `/search-results/{profileId}` | Get search results HTML | `profileId` (path), `search` (query) |
| GET | `/songs-fragment/{profileId}` | Get all songs HTML fragment | `profileId` (path), pagination params (query) |
| GET | `/history-fragment/{profileId}` | Get history HTML fragment | `profileId` (path), pagination params (query) |

### Video API

#### Video Library & Streaming

**Base Path:** `/api/video`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| GET | `/{videoId}` | Get single video details | `videoId` (path) |
| GET | `/stream/{videoId}` | Stream video file | `videoId` (path), `Range` (header) |
| GET | `/videos` | Get all videos | `mediaType` (query: movie/episode) |
| GET | `/shows` | Get all series titles | None |
| GET | `/shows/{seriesTitle}/seasons` | Get seasons for series | `seriesTitle` (path) |
| GET | `/shows/{seriesTitle}/seasons/{seasonNumber}/episodes` | Get episodes for season | `seriesTitle`, `seasonNumber` (path) |
| GET | `/movies` | Get paginated movies | `page`, `limit` (query) |
| GET | `/genres` | Get all video genres | None |
| GET | `/genre/{slug}` | Get videos by genre slug | `slug` (path) |
| GET | `/genres/multiple` | Get videos matching multiple genres | Genre slugs (query) |
| GET | `/carousels/genre` | Get genre-based carousel data | None |
| POST | `/watchlist/toggle/{videoId}` | Toggle video in watchlist | `videoId` (path) |
| GET | `/thumbnail/{videoId}` | Get video thumbnail | `videoId` (path) |
| GET | `/thumbnail/batch` | Batch get thumbnails | Video IDs (query) |
| POST | `/thumbnail/{videoId}/extract` | Extract thumbnail via FFmpeg | `videoId` (path) |
| POST | `/thumbnail/{videoId}/fetch` | Fetch thumbnail from external source | `videoId` (path) |
| POST | `/regenerate-thumbnails` | Regenerate all video thumbnails | None |
| GET | `/thumbnail-status` | Get thumbnail generation status | None |
| GET | `/storyboard/{videoId}/tiles` | Get storyboard tile images | `videoId` (path) |
| GET | `/storyboard/{videoId}` | Get storyboard data | `videoId` (path) |
| GET | `/storyboard/{videoId}/metadata` | Get storyboard metadata | `videoId` (path) |
| POST | `/progress/{videoId}` | Update watch progress | `videoId` (path), progress (body) |
| GET | `/{videoId}/audio-tracks` | List audio tracks for video | `videoId` (path) |
| POST | `/scan` | Scan video library (full) | None |
| POST | `/scan-incremental` | Incremental video scan | None |
| GET | `/scan-status` | Get scan progress status | None |
| POST | `/reload-metadata` | Reload all video metadata | None |
| POST | `/metadata/{videoId}/reload` | Reload metadata for single video | `videoId` (path) |
| POST | `/metadata/series/{seriesTitle}/reload` | Reload metadata for entire series | `seriesTitle` (path) |
| POST | `/reset-database` | Reset video database | None |
| POST | `/clear-history` | Clear video playback history | None |
| POST | `/clear-all` | Clear all video data | None |

#### Video Playback Control

**Base Path:** `/api/video/playback`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| GET | `/current` | Get current video playback state | None |
| GET | `/next/{videoId}` | Get next video in series | `videoId` (path) |
| GET | `/previous/{videoId}` | Get previous video in series | `videoId` (path) |
| POST | `/toggle` | Toggle video play/pause | None |
| POST | `/play/{videoId}` | Start playing specific video | `videoId` (path) |
| POST | `/play` | Resume current video | None |
| POST | `/pause` | Pause video playback | None |
| POST | `/next` | Skip to next video | None |
| POST | `/previous` | Go to previous video | None |
| POST | `/seek/{seconds}` | Seek to position | `seconds` (path) |
| POST | `/volume/{level}` | Set volume level (0-100) | `level` (path) |
| POST | `/progress` | Update and persist playback progress | Progress data (body) |
| POST | `/audio-preference` | Set preferred audio track | Audio preference (body) |

#### Video Queue Management

**Base Path:** `/api/video/queue`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| GET | `/` | Get current video queue | `page`, `limit` (query) |
| POST | `/add` | Add video to queue | Video ID (body) |
| POST | `/remove/{videoId}` | Remove video from queue | `videoId` (path) |
| POST | `/reorder` | Reorder queue items | Reorder data (body) |
| POST | `/clear` | Clear video queue | None |

#### Video UI Fragments (HTMX)

**Base Path:** `/api/video/ui`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| GET | `/movies-fragment` | Get movies HTML fragment | `page`, `limit` (query) |
| GET | `/shows-fragment` | Get shows HTML fragment | None |
| GET | `/shows/{seriesTitle}/seasons-fragment` | Get seasons HTML fragment | `seriesTitle` (path) |
| GET | `/shows/{seriesTitle}/seasons/{seasonNumber}/episodes-fragment` | Get episodes HTML fragment | `seriesTitle`, `seasonNumber` (path) |
| GET | `/queue-fragment` | Get video queue HTML fragment | `page`, `limit` (query) |
| GET | `/carousels-fragment` | Get genre carousel HTML fragments | None |

#### Video Management (HTMX)

**Base Path:** `/api/video/manage`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| Various | `/` | HTMX fragments for video management UI | Varies |

#### External Video Sources

**Base Path:** `/api/video/external`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| Various | `/` | Management of externally-sourced videos | Varies |

### Subtitle Management

**Base Path:** `/api/video/subtitles`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| GET | `/{videoId}` | List all subtitle tracks for video | `videoId` (path) |
| GET | `/track/{trackId}` | Get subtitle track metadata | `trackId` (path) |
| GET | `/track/{trackId}/raw` | Get raw subtitle content (WebVTT) | `trackId` (path) |
| POST | `/{videoId}/generate` | Generate subtitles via Whisper AI | `videoId` (path), `language` (query) |
| GET | `/{videoId}/search` | Search OpenSubtitles for video | `videoId` (path), `languages` (query) |
| POST | `/{videoId}/download` | Download subtitle from OpenSubtitles | `videoId` (path), subtitle file ID (body) |
| GET | `/{videoId}/local-files` | List local subtitle files matching video | `videoId` (path) |
| POST | `/{videoId}/add-local` | Add local subtitle file to video | `videoId` (path), file path (body) |
| POST | `/preference` | Set global subtitle preference | Language, style preferences (body) |
| POST | `/per-video-preference` | Set subtitle preference for specific video | Video ID, track ID (body) |

### Genre Management

**Base Path:** `/api/genres`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| POST | `/seed` | Seed default genre hierarchy | None |
| POST | `/auto-assign` | Auto-assign genres to videos | None |
| POST | `/rebuild` | Rebuild genre database | None |
| GET | `/stats` | Get genre statistics | None |
| GET | `/validate` | Validate genre data integrity | None |

### Collections

#### Collection Management

**Base Path:** `/api/collections`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| GET | `/` | List all collections | None |
| GET | `/{id}` | Get collection details | `id` (path) |
| POST | `/` | Create new collection | Collection data (body) |
| PUT | `/{id}` | Update collection | `id` (path), Collection data (body) |
| DELETE | `/{id}` | Delete collection | `id` (path) |
| POST | `/{collectionId}/add/{videoId}` | Add video to collection | `collectionId`, `videoId` (path) |
| DELETE | `/{collectionId}/remove/{videoId}` | Remove video from collection | `collectionId`, `videoId` (path) |

#### Collection UI Fragments (HTMX)

**Base Path:** `/api/collections/ui`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| Various | `/` | HTMX fragments for collection management UI | Varies |

#### Collection Playback

**Base Path:** `/api/collections/playback`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| Various | `/` | Collection playback control | Varies |

### HLS Streaming

**Base Path:** `/hls`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| POST | `/session/{videoId}` | Create HLS streaming session | `videoId` (path) |
| GET | `/master/{sessionId}` | Get master playlist | `sessionId` (path) |
| GET | `/playlist/{sessionId}/{variant}` | Get variant playlist | `sessionId`, `variant` (path) |
| GET | `/media/{sessionId}/{variant}/{segment}` | Get media segment | `sessionId`, `variant`, `segment` (path) |

### Xtream Codes API (IPTV Emulation)

**Base Path:** `/player_api.php`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| GET | `/player_api.php` | List VOD categories | `username`, `password`, `action=get_vod_categories` |
| GET | `/player_api.php` | List VOD streams | `username`, `password`, `action=get_vod_streams`, `category_id` |
| GET | `/player_api.php` | List series categories | `username`, `password`, `action=get_series_categories` |
| GET | `/player_api.php` | List series | `username`, `password`, `action=get_series` |
| GET | `/player_api.php` | Get series info | `username`, `password`, `action=get_series_info`, `series_id` |
| GET | `/player_api.php` | Get VOD info | `username`, `password`, `action=get_vod_info`, `vod_id` |

### Authentication

**Base Path:** `/api/auth`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| POST | `/login` | Authenticate and create session | `username`, `password` (form) |
| POST | `/logout` | Invalidate current session | None |
| GET | `/status` | Check if authenticated | None |
| GET | `/current-user` | Get current authenticated user | None |
| GET | `/is-admin` | Check if current user is admin | None |

### User Management

**Base Path:** `/api/users`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| GET | `/` | List all users (admin only) | None |
| GET | `/{id}` | Get user details (admin only) | `id` (path) |
| POST | `/` | Create new user (admin only) | User data (body) |
| PUT | `/{id}` | Update user (admin only) | `id` (path), User data (body) |
| DELETE | `/{id}` | Delete user (admin only) | `id` (path) |

### Profile Management

**Base Path:** `/api/profiles`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| GET | `/` | Get all profiles | None |
| GET | `/current` | Get current active profile | None |
| GET | `/{id}` | Get specific profile | `id` (path) |
| POST | `/` | Create new profile | Profile name (text/plain body) |
| PUT | `/{id}` | Update profile | `id` (path), Profile data (body) |
| POST | `/switch/{id}` | Switch to profile | `id` (path) |
| DELETE | `/{id}` | Delete profile | `id` (path) |
| GET | `/hidden-playlists` | Get hidden playlists for current profile | None |
| POST | `/hidden-playlists/{id}` | Hide a playlist | `id` (path) |
| DELETE | `/hidden-playlists/{id}` | Unhide a playlist | `id` (path) |

### Settings Management

**Base Path:** `/api/settings`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| GET | `/{profileId}/browse-folder` | Browse music folder | `profileId` (path) |
| GET | `/{profileId}/browse-video-folder` | Browse video folder | `profileId` (path) |
| POST | `/{profileId}/video-library-path` | Set video library path | `profileId` (path), `videoLibraryPathInput` (form) |
| POST | `/{profileId}/validate-paths` | Validate library paths | `profileId` (path), JSON body with paths |
| GET | `/{profileId}` | Get settings | `profileId` (path) |
| GET | `/{profileId}/music-library-path` | Get music library path | `profileId` (path) |
| POST | `/{profileId}/import` | Update import settings | `profileId` (path), ImportSettingsDTO (body) |
| POST | `/{profileId}/toggle-run-as-service` | Toggle run as service | `profileId` (path) |
| POST | `/{profileId}/music-library-path` | Set music library path | `profileId` (path), `musicLibraryPathInput` (form) |
| POST | `/{profileId}/resetLibrary` | Reset library | `profileId` (path) |
| POST | `/{profileId}/scanLibrary` | Scan library | `profileId` (path) |
| POST | `/{profileId}/scanLibraryIncremental` | Incremental library scan | `profileId` (path) |
| POST | `/{profileId}/clearLogs` | Clear logs | `profileId` (path) |
| GET | `/{profileId}/logs` | Get logs | `profileId` (path) |
| POST | `/clearPlaybackHistory/{profileId}` | Clear playback history | `profileId` (path) |
| POST | `/{profileId}/clearSongs` | Clear all songs | `profileId` (path) |
| POST | `/{profileId}/reloadMetadata` | Reload metadata | `profileId` (path) |
| POST | `/{profileId}/rescan-song/{id}` | Rescan specific song | `profileId`, `id` (path) |
| DELETE | `/{profileId}/songs/{id}` | Delete specific song | `profileId`, `id` (path) |
| POST | `/{profileId}/deleteDuplicates` | Delete duplicate songs | `profileId` (path) |
| POST | `/{profileId}/install-requirements` | Install requirements | `profileId` (path) |
| GET | `/{profileId}/install-status` | Get installation status | `profileId` (path) |
| GET | `/{profileId}/import-capability` | Check import capability | `profileId` (path) |

### Import Management

**Base Path:** `/api/import`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| GET | `/status` | Get import status | None |
| GET | `/{profileId}/default-download-path` | Get default download path | `profileId` (path) |
| POST | `/install/python/{profileId}` | Install Python | `profileId` (path) |
| POST | `/install/ffmpeg/{profileId}` | Install FFmpeg | `profileId` (path) |
| POST | `/install/spotdl/{profileId}` | Install SpotDL | `profileId` (path) |
| POST | `/install/whisper/{profileId}` | Install Whisper | `profileId` (path) |
| POST | `/uninstall/python/{profileId}` | Uninstall Python | `profileId` (path) |
| POST | `/uninstall/ffmpeg/{profileId}` | Uninstall FFmpeg | `profileId` (path) |
| POST | `/uninstall/spotdl/{profileId}` | Uninstall SpotDL | `profileId` (path) |
| POST | `/uninstall/whisper/{profileId}` | Uninstall Whisper | `profileId` (path) |

### Setup Management

**Base Path:** `/api/setup`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| GET | `/status` | Get setup status | None |
| POST | `/validate-paths` | Validate setup paths | JSON body with paths |
| POST | `/complete` | Complete setup | Form parameters for all settings |
| POST | `/install-requirements` | Install requirements | None |
| POST | `/reset` | Reset setup | None |

### Update Management

**Base Path:** `/api/update`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| GET | `/check` | Check for updates | None |
| GET | `/latest` | Get latest release info | None |

## WebSocket Endpoints

### Music WebSocket

**Endpoint:** `ws://localhost:8080/api/music/ws/{profileId}`

#### Messages from Server:
| Type | Payload | Description |
|------|---------|-------------|
| `state` | PlaybackState object | Current playback state (song, position, volume, shuffle, repeat, DJ mode) |
| `history-update` | HistoryEntry object | Playback history update |

#### Messages to Server:
| Type | Payload | Description |
|------|---------|-------------|
| `setProfile` | `{ "profileId": 1 }` | Set active profile |
| `seek` | `{ "value": 123.45 }` | Seek to position in seconds |
| `volume` | `{ "value": 0.8 }` | Set volume (0.0-1.0) |
| `next` | `{}` | Skip to next song |
| `previous` | `{}` | Go to previous song |

### Video WebSocket

**Endpoint:** `ws://localhost:8080/api/video/ws`

#### Messages from Server:
| Type | Payload | Description |
|------|---------|-------------|
| `state` | VideoState object | Current video playback state (video, position, volume, speed) |

#### Messages to Server:
| Type | Payload | Description |
|------|---------|-------------|
| `seek` | `{ "value": 123.45 }` | Seek to position in seconds |
| `volume` | `{ "value": 0.8 }` | Set volume (0.0-1.0) |
| `next` | `{}` | Skip to next video |
| `previous` | `{}` | Go to previous video |
| `toggle-play` | `{}` | Toggle play/pause |

### Import Status WebSocket

**Endpoint:** `ws://localhost:8080/ws/import-status/{profileId}`

#### Messages from Server:
| Type | Payload | Description |
|------|---------|-------------|
| `installation-status` | Status object | Installation progress update |
| `import-progress` | Progress object | Import progress update |
| `error` | Error message | Error notification |

#### Messages to Server:
```json
{
  "type": "start-import",
  "url": "https://example.com/playlist",
  "format": "mp3",
  "downloadThreads": 4,
  "searchThreads": 4,
  "downloadPath": "/path/to/downloads",
  "playlistName": "My Playlist",
  "queueAfterDownload": true
}
```

### Log WebSocket

**Endpoint:** `ws://localhost:8080/api/logs/ws/{profileId}`

#### Messages from Server:
```json
{
  "type": "log",
  "payload": "Log message here"
}
```
```json
{
  "type": "seek",
  "payload": {
    "value": 123.45
  }
}
```
```json
{
  "type": "volume",
  "payload": {
    "value": 0.8
  }
}
```
```json
{
  "type": "next",
  "payload": {}
}
```

### Video WebSocket

**Endpoint:** `ws://localhost:8080/api/video/ws`

#### Messages from Server:
- `state`: Current video playback state

#### Messages to Server:
```json
{
  "type": "seek",
  "payload": {
    "value": 123.45
  }
}
```
```json
{
  "type": "volume",
  "payload": {
    "value": 0.8
  }
}
```
```json
{
  "type": "next",
  "payload": {}
}
```
```json
{
  "type": "toggle-play",
  "payload": {}
}
```
```json
{
  "type": "previous",
  "payload": {}
}
```

### Import Status WebSocket

**Endpoint:** `ws://localhost:8080/ws/import-status/{profileId}`

#### Messages from Server:
- Installation status updates
- Import progress updates
- Error messages

#### Messages to Server:
```json
{
  "type": "start-import",
  "url": "https://example.com/playlist",
  "format": "mp3",
  "downloadThreads": 4,
  "searchThreads": 4,
  "downloadPath": "/path/to/downloads",
  "playlistName": "My Playlist",
  "queueAfterDownload": true
}
```

### Log WebSocket

**Endpoint:** `ws://localhost:8080/api/logs/ws/{profileId}`

#### Messages from Server:
```json
{
  "type": "log",
  "payload": "Log message here"
}
```

## Data Models

### Song
```json
{
  "id": 1,
  "title": "Song Title",
  "artist": "Artist Name",
  "album": "Album Name",
  "duration": 240,
  "path": "/path/to/song.mp3",
  "lyrics": "Song lyrics...",
  "artwork": "base64-encoded-artwork",
  "trackNumber": 1,
  "discNumber": 1,
  "year": 2024,
  "genre": "Rock",
  "artistId": "external-artist-id",
  "albumId": "external-album-id",
  "songAnalysis": { }
}
```

### SongAnalysis
```json
{
  "id": 1,
  "bpm": 120.5,
  "beats": [0.5, 1.0, 1.5, ...],
  "onsets": [0.3, 0.8, 1.2, ...],
  "spectralData": { },
  "analysisDate": "2025-01-15T10:30:00Z"
}
```

### Playlist
```json
{
  "id": 1,
  "name": "Playlist Name",
  "description": "Playlist Description",
  "isGlobal": false,
  "isShared": false,
  "songs": [Song objects],
  "profile": Profile object
}
```

### Profile
```json
{
  "id": 1,
  "name": "Profile Name",
  "isMainProfile": false
}
```

### PlaybackState
```json
{
  "currentSongId": 1,
  "isPlaying": true,
  "position": 123.45,
  "volume": 0.8,
  "shuffle": "OFF",
  "shuffleMode": "OFF",
  "repeat": "OFF",
  "repeatMode": "OFF",
  "djMode": false,
  "crossfadeDuration": 5,
  "queue": [1, 2, 3],
  "queueIndex": 0
}
```

### Video
```json
{
  "id": 1,
  "title": "Video Title",
  "mediaType": "Movie",
  "path": "/path/to/video.mp4",
  "duration": 7200,
  "episodeNumber": 1,
  "episodeTitle": "Episode Title",
  "seriesTitle": "Series Title",
  "seasonNumber": 1,
  "description": "Video description",
  "year": 2024,
  "rating": 8.5,
  "imdbId": "tt1234567",
  "tmdbId": 12345,
  "thumbnail": "base64-thumbnail",
  "genres": ["Action", "Drama"],
  "codec": "h264",
  "container": "mp4",
  "hasEmbeddedSubtitles": true,
  "watchCount": 10,
  "isWatchlisted": false
}
```

### VideoState
```json
{
  "currentVideoId": 1,
  "isPlaying": true,
  "position": 123.45,
  "volume": 0.8,
  "speed": 1.0
}
```

### SubtitleTrack
```json
{
  "id": 1,
  "videoId": 1,
  "language": "en",
  "languageName": "English",
  "source": "EMBEDDED",
  "format": "webvtt",
  "title": "English (Forced)",
  "isDefault": false,
  "isForced": true,
  "codec": "subrip",
  "index": 0,
  "content": "WEBVTT\n\n00:00:01.000 --> 00:00:05.000\nHello world"
}
```

### User
```json
{
  "id": 1,
  "username": "admin",
  "role": "admin",
  "group": "admin-group",
  "createdAt": "2025-01-01T00:00:00Z"
}
```

### AudioTrack
```json
{
  "index": 0,
  "codec": "aac",
  "language": "en",
  "languageName": "English",
  "channels": 2,
  "sampleRate": 48000,
  "bitRate": 320000,
  "isDefault": true
}
```

### MediaCollection
```json
{
  "id": 1,
  "name": "My Collection",
  "description": "My favorite videos",
  "entries": [CollectionEntry objects],
  "createdAt": "2025-01-01T00:00:00Z",
  "updatedAt": "2025-01-15T00:00:00Z"
}
```

### CollectionEntry
```json
{
  "id": 1,
  "collectionId": 1,
  "videoId": 1,
  "position": 0,
  "watchProgress": 120.5,
  "addedAt": "2025-01-01T00:00:00Z"
}
```

### Settings
```json
{
  "libraryPath": "/path/to/music",
  "videoLibraryPath": "/path/to/videos",
  "outputFormat": "mp3",
  "downloadThreads": 4,
  "searchThreads": 4,
  "runAsService": false,
  "activeProfileId": 1
}
```

## Error Handling

The API returns appropriate HTTP status codes:

- `200 OK`: Successful request
- `201 Created`: Resource created successfully
- `204 No Content`: Successful request with no content
- `400 Bad Request`: Invalid request parameters
- `404 Not Found`: Resource not found
- `409 Conflict`: Resource conflict (e.g., duplicate profile name)
- `500 Internal Server Error`: Server error

All error responses include an error message in the `ApiResponse` format.

## Streaming

### Audio Streaming
- Supports HTTP Range requests for seeking
- Returns `audio/mpeg` content type
- Supports partial content (206) responses

### Video Streaming
- Supports HTTP Range requests for seeking
- Returns `video/mp4` or `video/x-matroska` content type (auto-detected)
- Supports partial content (206) responses

### On-the-Fly Transcoding
- MKV containers are automatically remuxed to MP4 via FFmpeg pipe
- Codec detection determines whether transcoding is required
- Transcoding streams use `video/mp4` content type

### HLS Streaming
- Adaptive bitrate streaming via FFmpeg segmenter
- Master playlist with multiple variant streams
- Segmented media delivery with session management
- Browser playback via HLS.js client library
- Endpoints: `/hls/session/{videoId}`, `/hls/master/{sessionId}`, `/hls/playlist/{sessionId}/{variant}`, `/hls/media/{sessionId}/{variant}/{segment}`

## HTMX Integration

Many UI endpoints return HTML fragments specifically designed for HTMX updates:
- Queue management fragments
- Playlist fragments
- Search suggestions
- History fragments
- Video library fragments

These endpoints typically return JSON with an `html` field containing the rendered HTML fragment.

## Real-time Features

The application uses WebSockets for real-time updates:
- **Music playback state synchronization** — Play/pause, seek, volume, song changes broadcast to all connected clients per profile
- **Video playback state synchronization** — Seek, volume, play/pause, next/previous broadcast globally
- **Import progress updates** — Real-time import and installation progress per profile
- **Log streaming** — Live system log output streaming per profile

## Xtream Codes API

JMedia emulates the **Xtream Codes API** (`/player_api.php`) for IPTV compatibility. This allows IPTV client applications to browse and stream JMedia's video library as if it were an IPTV service:

- **VOD Categories**: `?action=get_vod_categories`
- **VOD Streams**: `?action=get_vod_streams` (with category filtering and pagination)
- **Series Categories**: `?action=get_series_categories`
- **Series**: `?action=get_series` (with category filtering)
- **Series Info**: `?action=get_series_info` (with season/episode listing)
- **VOD Info**: `?action=get_vod_info`

All Xtream Codes endpoints require `username` and `password` query parameters for authentication.

## Notes

- All profile-specific endpoints require a valid `profileId` parameter
- The API supports both JSON and form-encoded requests depending on the endpoint
- File streaming endpoints support byte range requests for proper seeking functionality
- WebSocket connections are managed per profile for music and logs, globally for video
- **Authentication** is required for most endpoints; include session cookie from `/api/auth/login`
- **Rate limiting** applies to auth endpoints (429 Too Many Requests when exceeded)
- The application includes comprehensive logging and error handling
- Response format varies: some endpoints return `ApiResponse` wrappers, others return raw JSON or HTMX HTML fragments