# 🎵 JMedia — Feature Overview

| Symbol | Meaning |
|:--|:--|
| ✅ | Fully Implemented |
| ⚙️ | UI Implemented / Logic Pending |
| 🚧 | Placeholder (UI/Stub Only) |
| 🧩 | Planned / In Design |

---

## 📌 Table of Contents

- [Navigation & Layout](#navigation--layout)
- [Music Interface & Playback](#music-interface--playback)
- [Music Library & Metadata](#music-library--metadata)
- [Audio Analysis & DJ Mode](#audio-analysis--dj-mode)
- [Video Interface & Playback](#video-interface--playback)
- [Video Library & Metadata](#video-library--metadata)
- [Video Streaming & Transcoding](#video-streaming--transcoding)
- [Subtitle Management](#subtitle-management)
- [Video Collections](#video-collections)
- [Video Metadata Enrichment](#video-metadata-enrichment)
- [Xtream Codes IPTV Emulation](#xtream-codes-iptv-emulation)
- [Authentication & Security](#authentication--security)
- [User & Profile Management](#user--profile-management)
- [System Administration](#system-administration)
- [Media Import](#media-import)
- [Data & Diagnostics](#data--diagnostics)
- [App Behavior & Customization](#app-behavior--customization)
- [Summary](#summary)

---

## 🧭 Navigation & Layout

| Feature | Description | Platform | Status |
|----------|--------------|-----------|---------|
| Home View | Displays playlists, song queue, and songs based on selection. | Desktop / Mobile | ✅ |
| Discover | Main hub for finding content. | Desktop / Mobile | ✅ |
| Settings Tab | Central hub for configuration, library, and app behavior. | Desktop / Mobile | ✅ |
| Video Library | Dedicated video section for movies and TV series. | Desktop / Mobile | ✅ |
| Import View | Media import interface for online sources. | Desktop / Mobile | ✅ |
| Responsive Layout | Fully responsive design supporting desktop and mobile UI. | Desktop / Mobile | ✅ |
| Light & Dark Mode | Toggle between light and dark themes with system preference detection. | Desktop / Mobile | ✅ |

---

## 🎛 Music Interface & Playback

| Feature | Description | Platform | Status |
|----------|--------------|-----------|---------|
| Playback Bar | Displays song info, artist, album art, duration. | Desktop / Mobile | ✅ |
| Playback Controls | Play, pause, next, previous, shuffle, repeat, volume. | Desktop / Mobile | ✅ |
| Shuffle Modes | OFF, SHUFFLE, SMART_SHUFFLE with intelligent song ordering. | Desktop / Mobile | ✅ |
| Repeat Modes | OFF, ONE, ALL with proper loop behavior. | Desktop / Mobile | ✅ |
| Song Queue | View current queue, skip to song, remove, or clear queue. | Desktop / Mobile | ✅ |
| Song List Actions | Play or add to playlist; remove from playlist if viewing a playlist. | Desktop / Mobile | ✅ |
| Play Queue Persistence | Queue is saved between sessions. | Desktop / Mobile | ✅ |
| Seek & Position Control | Jump to any position in the current song. | Desktop / Mobile | ✅ |
| Crossfade | Configurable crossfade duration between songs. | Desktop / Mobile | ✅ |
| Genre Carousels | Dynamic genre-based browsing carousels on home page. | Desktop / Mobile | ✅ |
| Search & Filter | Search songs by title, artist, album, or metadata with paginated results. | Desktop / Mobile | ✅ |
| Search Suggestions | Live search suggestions as you type. | Desktop / Mobile | ✅ |
| Now Playing / Expanded Player | Fullscreen or focused playback view. | Desktop / Mobile | 🧩 |
| Mini Player Mode | Floating or compact view of playback controls. | Desktop / Mobile | 🧩 |
| Sort Options | Sort by artist, album, duration, or play count. | Desktop / Mobile | 🧩 |
| Smart Playlists | Auto-generate playlists like "Most Played" or "Recently Added." | Desktop / Mobile | 🧩 |
| Playback History View | View playback history directly in the UI. | Desktop / Mobile | 🧩 |
| Recently Added / Recently Played | Dynamic playlists for convenience. | Desktop / Mobile | 🧩 |
| Favorites / Liked Songs | Users can mark songs as favorites (backend exists, frontend pending). | Desktop / Mobile | 🧩 |

---

## 🎶 Music Library & Metadata

### Library Configuration

| Feature | Description | Platform | Status |
|----------|--------------|-----------|---------|
| Music Folder Path | Display and change music library path. | Desktop / Mobile | ✅ |
| Save Path Button | Save a new library path and clear old one. | Desktop / Mobile | ✅ |
| Reset to Default Path | Reset library to default folder. | Desktop / Mobile | ✅ |
| Video Library Path | Display and change video library path. | Desktop / Mobile | ✅ |
| Run as Service Toggle | Runs JMedia as a background service (does not auto-start). | Desktop / Mobile | ✅ |
| Import Settings | Configure output format, download/search threads. | Desktop / Mobile | ✅ |
| Multiple Library Support | Allow user to add/manage multiple music folders. | Desktop / Mobile | 🧩 |

### Library Maintenance

| Feature | Description | Platform | Status |
|----------|--------------|-----------|---------|
| Scan Library | Scans current folder for music files. | Desktop / Mobile | ✅ |
| Incremental Scan | Only processes new or modified files for faster updates. | Desktop / Mobile | ✅ |
| Reload Metadata | Reload all metadata for existing songs. | Desktop / Mobile | ✅ |
| Delete Duplicates | Detect and remove duplicate songs. | Desktop / Mobile | ✅ |
| Metadata Extraction | Extracts title, artist, album art, and duration from file metadata via jaudiotagger. | Desktop / Mobile | ✅ |
| Metadata Write-back | Write edited metadata back to audio files. | Desktop / Mobile | ✅ |
| Rescan Individual Song | Re-scan and update a single song's metadata. | Desktop / Mobile | ✅ |
| Delete Individual Song | Remove a single song from the library. | Desktop / Mobile | ✅ |
| Album Art Extraction | Extract album art from audio files with base64 caching. | Desktop / Mobile | ✅ |
| Album Art Download | Fetch album art from external sources with caching and circuit breaker. | Desktop / Mobile | ✅ |
| Backup Library | Export music library database and settings. | Desktop / Mobile | 🧩 |
| Restore Library | Import a previously exported library backup. | Desktop / Mobile | 🧩 |

---

## 🎚 Audio Analysis & DJ Mode

| Feature | Description | Platform | Status |
|----------|--------------|-----------|---------|
| BPM Detection | Real beats-per-minute detection via TarsosDSP. | Desktop / Mobile | ✅ |
| Beat Tracking | Onset detection and beat positions within audio files. | Desktop / Mobile | ✅ |
| Spectral Analysis | Frequency spectral analysis for audio fingerprinting. | Desktop / Mobile | ✅ |
| DJ Mode | Beat-aligned crossfade transitions between songs. | Desktop / Mobile | ✅ |
| Crossfade Planning | Intelligent song pairing with configurable crossfade duration. | Desktop / Mobile | ✅ |
| Independent from Shuffle | DJ Mode operates independently of shuffle/repeat modes. | Desktop / Mobile | ✅ |

---

## 🎬 Video Interface & Playback

| Feature | Description | Platform | Status |
|----------|--------------|-----------|---------|
| Movie Library | Browse movies with poster thumbnails and metadata. | Desktop / Mobile | ✅ |
| TV Series Library | Browse series with season/episode organization. | Desktop / Mobile | ✅ |
| Genre Carousels | Dynamic genre-based video carousels. | Desktop / Mobile | ✅ |
| Video Queue | Add, remove, reorder, and persist video queue. | Desktop / Mobile | ✅ |
| Watchlist | Toggle videos as favorites/watchlist. | Desktop / Mobile | ✅ |
| Playback History | Per-profile video watch history with pagination. | Desktop / Mobile | ✅ |
| Resume Playback | Remembers and resumes video position across sessions. | Desktop / Mobile | ✅ |
| Full Playback Controls | Play, pause, seek, volume, speed adjustment, fullscreen. | Desktop / Mobile | ✅ |
| Audio Track Selection | Multi-track audio stream selection with per-video preference persistence. | Desktop / Mobile | ✅ |
| Video Suggestions | User-submitted video suggestion system. | Desktop / Mobile | ✅ |

---

## 🎞 Video Library & Metadata

| Feature | Description | Platform | Status |
|----------|--------------|-----------|---------|
| Smart Video Import | Automatic library scanning with ffprobe metadata extraction. | Desktop / Mobile | ✅ |
| Incremental Scanning | Only processes new or modified files for faster updates. | Desktop / Mobile | ✅ |
| Multi-threaded Scanning | Background queue processing with scan progress tracking. | Desktop / Mobile | ✅ |
| Content Detection | Intelligent movie vs TV series detection with episode/season parsing. | Desktop / Mobile | ✅ |
| Smart Naming | Intelligent video file naming and organization. | Desktop / Mobile | ✅ |
| Thumbnail Generation | Automatic thumbnail extraction via FFmpeg with background queue processing. | Desktop / Mobile | ✅ |
| Thumbnail Caching | Cached thumbnails with batch retrieval endpoint. | Desktop / Mobile | ✅ |
| Storyboard Generation | 10x10 tile grid video storyboard previews. | Desktop / Mobile | ✅ |
| Scan Status Tracking | Real-time scan progress via dedicated status endpoints. | Desktop / Mobile | ✅ |
| Database Reset | Clear and rebuild video database. | Desktop / Mobile | ✅ |
| Metadata Reload | Reload metadata for individual video or entire series. | Desktop / Mobile | ✅ |

### Video Metadata Enrichment

| Feature | Description | Platform | Status |
|----------|--------------|-----------|---------|
| IMDb Integration | Fetch movie/TV metadata from IMDb API. | Desktop / Mobile | ✅ |
| TMDb Integration | The Movie Database API for posters, descriptions, ratings. | Desktop / Mobile | ✅ |
| OMDb Integration | Open Movie Database API for supplementary metadata. | Desktop / Mobile | ✅ |
| TVMaze Integration | Free TV series metadata with episode details. | Desktop / Mobile | ✅ |
| IntroDB Integration | Intro, recap, and outro timestamp detection. | Desktop / Mobile | ✅ |
| Background Queue Processing | Metadata enrichment runs asynchronously in background queue. | Desktop / Mobile | ✅ |

---

## 📺 Video Streaming & Transcoding

| Feature | Description | Platform | Status |
|----------|--------------|-----------|---------|
| HTTP Range Streaming | Standard video streaming with byte range request support. | Desktop / Mobile | ✅ |
| MKV-to-MP4 Transcoding | On-the-fly remux via FFmpeg pipe for browser compatibility. | Desktop / Mobile | ✅ |
| Codec Detection | Automatic codec detection to determine transcoding requirements. | Desktop / Mobile | ✅ |
| HLS Streaming | Adaptive bitrate streaming with FFmpeg segmenter. | Desktop / Mobile | ✅ |
| HLS Master Playlist | Multi-variant master playlist generation. | Desktop / Mobile | ✅ |
| HLS Media Segments | Segmented media delivery with session management. | Desktop / Mobile | ✅ |
| HLS.js Client | Browser HLS playback via HLS.js library. | Desktop / Mobile | ✅ |

---

## 📝 Subtitle Management

| Feature | Description | Platform | Status |
|----------|--------------|-----------|---------|
| Embedded Track Extraction | Extract embedded subtitle tracks from video files via FFprobe. | Desktop / Mobile | ✅ |
| External File Matching | Automatic .srt/.vtt/.ass/.ssa file matching to videos. | Desktop / Mobile | ✅ |
| OpenSubtitles Integration | Search and download subtitles from OpenSubtitles.org. | Desktop / Mobile | ✅ |
| Whisper AI Generation | AI-powered subtitle generation via OpenAI Whisper. | Desktop / Mobile | ✅ |
| Format Conversion | Automatic SRT/ASS/SSA to WebVTT conversion with timestamp offset. | Desktop / Mobile | ✅ |
| Subtitle Preference Engine | Intelligent auto-selection by language, style, and user preference. | Desktop / Mobile | ✅ |
| Per-Video Preferences | Subtitle preferences stored per video. | Desktop / Mobile | ✅ |
| JASSUB Rendering | ASS/SSA subtitle rendering in browser via WebAssembly. | Desktop / Mobile | ✅ |
| Background Discovery | Background subtitle track discovery on library scan. | Desktop / Mobile | ✅ |
| Multiple Track Support | Multiple simultaneous subtitle tracks per video. | Desktop / Mobile | ✅ |

---

## 🗂️ Video Collections

| Feature | Description | Platform | Status |
|----------|--------------|-----------|---------|
| Create Collections | User-curated media collections. | Desktop / Mobile | ✅ |
| Collection CRUD | Full create, read, update, delete for collections. | Desktop / Mobile | ✅ |
| Add/Remove Videos | Add and remove videos from collections. | Desktop / Mobile | ✅ |
| Watch Progress Tracking | Per-collection playback progress. | Desktop / Mobile | ✅ |
| Collection Playback | Play all videos in a collection sequentially. | Desktop / Mobile | ✅ |
| HTMX Collection UI | Dynamic collection management UI fragments. | Desktop / Mobile | ✅ |

---

## 📡 Xtream Codes IPTV Emulation

| Feature | Description | Platform | Status |
|----------|--------------|-----------|---------|
| VOD Categories | Xtream Codes-compatible VOD category listing. | Desktop / Mobile | ✅ |
| VOD Streams | Xtream Codes-compatible VOD stream listing with pagination. | Desktop / Mobile | ✅ |
| Series Categories | Xtream Codes-compatible series category listing. | Desktop / Mobile | ✅ |
| Series Info | Detailed series information with episode listing. | Desktop / Mobile | ✅ |
| VOD Info | Detailed video-on-demand information. | Desktop / Mobile | ✅ |
| Streaming Redirects | Xtream Codes-compatible streaming URL redirects. | Desktop / Mobile | ✅ |

---

## 🔐 Authentication & Security

| Feature | Description | Platform | Status |
|----------|--------------|-----------|---------|
| User Login | Session-based login with username and password. | Desktop / Mobile | ✅ |
| User Logout | Session invalidation and logout. | Desktop / Mobile | ✅ |
| Session Management | Cookie-based sessions with configurable expiry. | Desktop / Mobile | ✅ |
| Role-Based Access | Admin and user roles with endpoint-level restrictions. | Desktop / Mobile | ✅ |
| Rate Limiting | IP-based and per-username rate limiting on auth endpoints. | Desktop / Mobile | ✅ |
| Password Hashing | Secure credential storage via jBCrypt. | Desktop / Mobile | ✅ |
| Auth Filter | Automatic authentication check on protected endpoints. | Desktop / Mobile | ✅ |
| Admin User Management | Create, update, delete users and manage roles. | Desktop / Mobile | ✅ |

---

## 👤 User & Profile Management

| Feature | Description | Platform | Status |
|----------|--------------|-----------|---------|
| Multi-User Support | Multiple user accounts with isolated state. | Desktop / Mobile | ✅ |
| Profile CRUD | Create, read, update, delete profiles. | Desktop / Mobile | ✅ |
| Profile Switching | Switch active profile with isolated playback state. | Desktop / Mobile | ✅ |
| Per-Profile Queues | Separate music and video queues per profile. | Desktop / Mobile | ✅ |
| Per-Profile History | Separate playback history per profile. | Desktop / Mobile | ✅ |
| Per-Profile Settings | Profile-specific library paths and configurations. | Desktop / Mobile | ✅ |
| Hidden Playlists | Per-profile playlist visibility management. | Desktop / Mobile | ✅ |
| Main Profile Support | Designation of main/default profile. | Desktop / Mobile | ✅ |

---

## 🛠️ System Administration

| Feature | Description | Platform | Status |
|----------|--------------|-----------|---------|
| Update Checking | GitHub Releases API integration for version comparison. | Desktop / Mobile | ✅ |
| Dependency Installation | Automated installation of Python, FFmpeg, SpotDL, Whisper. | Desktop / Mobile | ✅ |
| Installation Status | Track installation progress per dependency. | Desktop / Mobile | ✅ |
| Setup Wizard | 4-step guided initial configuration wizard. | Desktop / Mobile | ✅ |
| HTTPS Certificate Management | Automatic HTTPS certificate setup. | Desktop / Mobile | ✅ |
| System Logging | Comprehensive system logging with log levels. | Desktop / Mobile | ✅ |
| Live Log Streaming | Real-time log viewing via WebSocket. | Desktop / Mobile | ✅ |
| Library Maintenance | Scan, reload, clear songs, clear history, delete duplicates. | Desktop / Mobile | ✅ |
| Cross-Platform Support | Windows, macOS, and Linux platform-specific operations. | Desktop / Mobile | ✅ |
| Background Service Mode | Run as system tray application (Windows). | Desktop | ✅ |

---

## 📥 Media Import

| Feature | Description | Platform | Status |
|----------|--------------|-----------|---------|
| Spot-dl Integration | Import music directly from Spotify using Spot-dl. | Desktop / Mobile | ✅ |
| yt-dlp Integration | Import music from YouTube and other platforms using yt-dlp. | Desktop / Mobile | ✅ |
| Default Download Path | Configurable download directory per profile. | Desktop / Mobile | ✅ |
| Import Capability Check | Verify required dependencies are installed. | Desktop / Mobile | ✅ |
| Real-Time Import Status | WebSocket-based import progress tracking. | Desktop / Mobile | ✅ |

---

## 🧠 Data & Diagnostics

| Feature | Description | Platform | Status |
|----------|--------------|-----------|---------|
| Clear Songs Database | Deletes all songs in the database. | Desktop / Mobile | ✅ |
| Clear Playback History | Deletes playback history. | Desktop / Mobile | ✅ |
| Clear Video Database | Resets the entire video library database. | Desktop / Mobile | ✅ |
| Clear Video History | Deletes video playback history. | Desktop / Mobile | ✅ |
| View Logs | View system and playback logs. | Desktop / Mobile | ✅ |
| Clear Logs | Remove all application logs. | Desktop / Mobile | ✅ |

---

## ⚙️ App Behavior & Customization

| Feature | Description | Platform | Status |
|----------|--------------|-----------|---------|
| Manual Startup (Service Mode) | App must be manually launched, even in service mode. | Desktop / Mobile | ✅ |
| Tray Icon Integration | Visible when running as background service. | Desktop / Mobile | ✅ |
| Dark/Light Theme | Toggle between themes with system preference auto-detection. | Desktop / Mobile | ✅ |
| Responsive Design | Works on desktop, tablet, and mobile devices. | Desktop / Mobile | ✅ |

---

## 📊 Summary

| Area | Completion |
|------|-----------|
| **Music Playback & Library** | ~90% — Core playback, queue, playlists, DJ mode, audio analysis complete |
| **Video Playback & Library** | ~95% — Full streaming, HLS, subtitles, metadata enrichment, thumbnails, storyboards |
| **Subtitle Management** | ~95% — Extraction, matching, download, generation, conversion, preferences |
| **Video Collections** | ~90% — Full CRUD, watch progress, HTMX UI |
| **Xtream Codes Emulation** | ~90% — Full VOD/series API emulation |
| **Authentication & Security** | ~85% — Login, sessions, rate limiting, roles complete |
| **System Features** | ~80% — Updates, dependencies, setup wizard, logging, cross-platform |
| **Frontend** | ~80% — SPA with HTMX, responsive, theming; some UI polish items remain |
| **Native Builds** | ~50% — GraalVM native configuration exists; builds pending |
| **Testing** | 0% — No test suite yet |
