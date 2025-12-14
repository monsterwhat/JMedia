# JMedia API Documentation

## Overview

JMedia is a comprehensive media management and streaming application built with Quarkus. This API provides endpoints for music and video playback, library management, user profiles, playlists, settings, and real-time WebSocket communication.

## Base URL

```
http://localhost:8080
```

## Authentication

Currently, the API does not implement authentication. All endpoints are accessible without authentication tokens.

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
| POST | `/toggle/{profileId}` | Toggle play/pause | `profileId` (path) |
| POST | `/play/{profileId}` | Start playback | `profileId` (path) |
| POST | `/pause/{profileId}` | Pause playback | `profileId` (path) |
| POST | `/next/{profileId}` | Skip to next song | `profileId` (path) |
| POST | `/previous/{profileId}` | Skip to previous song | `profileId` (path) |
| POST | `/select/{profileId}/{id}` | Select specific song | `profileId`, `id` (path) |
| POST | `/shuffle/{profileId}` | Toggle shuffle mode | `profileId` (path) |
| POST | `/repeat/{profileId}` | Toggle repeat mode | `profileId` (path) |
| POST | `/volume/{profileId}/{level}` | Set volume level | `profileId`, `level` (path) |
| POST | `/position/{profileId}/{seconds}` | Set playback position | `profileId`, `seconds` (path) |

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

#### Video Management

**Base Path:** `/api/video`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| GET | `/stream/{videoId}` | Stream video file | `videoId` (path), `Range` (header) |
| POST | `/scan` | Scan video library | None |
| POST | `/reload-metadata` | Reload video metadata | None |
| POST | `/reset-database` | Reset video database | None |
| GET | `/videos` | Get all videos | `mediaType` (query) |
| GET | `/shows` | Get all series titles | None |
| GET | `/shows/{seriesTitle}/seasons` | Get seasons for series | `seriesTitle` (path) |
| GET | `/shows/{seriesTitle}/seasons/{seasonNumber}/episodes` | Get episodes for season | `seriesTitle`, `seasonNumber` (path) |
| GET | `/movies` | Get paginated movies | `page`, `limit` (query) |

#### Video UI Fragments (HTMX)

**Base Path:** `/api/video/ui`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| GET | `/movies-fragment` | Get movies HTML fragment | `page`, `limit` (query) |
| GET | `/shows-fragment` | Get shows HTML fragment | None |
| GET | `/shows/{seriesTitle}/seasons-fragment` | Get seasons HTML fragment | `seriesTitle` (path) |
| GET | `/shows/{seriesTitle}/seasons/{seasonNumber}/episodes-fragment` | Get episodes HTML fragment | `seriesTitle`, `seasonNumber` (path) |
| GET | `/queue-fragment` | Get video queue HTML fragment | `page`, `limit` (query) |

### Profile Management

**Base Path:** `/api/profiles`

| Method | Endpoint | Description | Parameters |
|--------|----------|-------------|------------|
| GET | `/` | Get all profiles | None |
| GET | `/current` | Get current active profile | None |
| GET | `/{id}` | Get specific profile | `id` (path) |
| POST | `/` | Create new profile | Profile name (text/plain body) |
| POST | `/switch/{id}` | Switch to profile | `id` (path) |
| DELETE | `/{id}` | Delete profile | `id` (path) |

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
- `state`: Current playback state
- `history-update`: Playback history update

#### Messages to Server:
```json
{
  "type": "setProfile",
  "payload": {
    "profileId": 1
  }
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
  "artwork": "base64-encoded-artwork"
}
```

### Playlist
```json
{
  "id": 1,
  "name": "Playlist Name",
  "description": "Playlist Description",
  "isGlobal": false,
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
  "shuffle": false,
  "repeat": false
}
```

### VideoState
```json
{
  "currentVideoId": 1,
  "isPlaying": true,
  "position": 123.45,
  "volume": 0.8
}
```

### VideoDTO
```json
{
  "id": 1,
  "title": "Video Title",
  "mediaType": "Movie",
  "path": "/path/to/video.mp4",
  "duration": 7200,
  "episodeNumber": 1,
  "episodeTitle": "Episode Title",
  "seriesTitle": "Series Title"
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
  "runAsService": false
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
- Returns `video/mp4` content type
- Supports partial content (206) responses

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
- Playback state synchronization
- Import progress updates
- Log streaming
- Queue updates

## Notes

- All profile-specific endpoints require a valid `profileId` parameter
- The API supports both JSON and form-encoded requests depending on the endpoint
- File streaming endpoints support byte range requests for proper seeking functionality
- WebSocket connections are managed per profile for music and globally for video
- The application includes comprehensive logging and error handling