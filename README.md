# 🎵🎬 JMedia v1.3.4
### A Decentralized, Private, and Efficient Media Streaming Application

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0.en.html)
[![Java](https://img.shields.io/badge/Java-25%2B-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![Quarkus](https://img.shields.io/badge/Powered%20by-Quarkus%203.34.1-red.svg)](https://quarkus.io/)
[![Build with Maven](https://img.shields.io/badge/Build-Maven-blue.svg)](https://maven.apache.org/)
[![GitHub Release](https://img.shields.io/github/v/release/monsterwhat/JMedia)](https://github.com/monsterwhat/JMedia/releases)

---

## 🌐 Overview

**JMedia** is a decentralized, privacy-focused media streaming application built with **Java** and **Quarkus**.
It provides a **serverless**, **user-controlled** experience for managing and streaming your **music and video** libraries.

Unlike traditional streaming services, JMedia ensures that your data — from your media files to your playback preferences — **remains fully local and private**. It offers a responsive web interface combined with a high-performance backend for seamless playback, media organization, and comprehensive library management.

---

## ✨ Key Features

### 🛡️ **Decentralized Architecture & Privacy First**
- No central servers or cloud dependencies.
- No telemetry, analytics, or background data collection.

### 💾 **Local Data Management**
- Your entire media library (music + video) and metadata are stored locally.
- Faster access, total privacy, and zero external dependencies.

### 💻 **Modern Web Interface**
- Built with **HTML**, **CSS**, **JavaScript**, **HTMX**, and **Alpine.js**.
- Fully responsive design supporting desktop and mobile devices.
- Dark/light theme support with system preference detection.
- **Qute templating** for dynamic server-rendered HTML fragments.
- **Cinema mode** with a glass navbar and dock navigation.

### 🎶 **Comprehensive Music Library**
- Organize songs, edit metadata, and manage playlists.
- Efficiently handles large music collections.
- Uses [`jaudiotagger`](https://bitbucket.org/ijabz/jaudiotagger) for advanced audio metadata support.
- Playback history and queue persistence across sessions.
- **Genre-based browsing** with auto-seeded genre classification.
- **Lyrics generation** powered by Whisper AI.
- **Album art extraction** and artwork caching with circuit breaker protection.
- **Audio Analysis**: Real BPM detection, beat tracking, onset detection, and spectral analysis via TarsosDSP, with analysis embedded into file tags.
- **DJ Mode**: Beat-aligned crossfade transitions with intelligent song pairing and configurable crossfade duration, BPM range, and genre-skip settings.

### 🎬 **Full Video Management System**
- **Smart Video Import**: Automatic library scanning with metadata extraction using `ffprobe`.
- **Content Detection**: Intelligent detection of movies and TV series with episode/season parsing.
- **Subtitle Support**: Automatic subtitle file matching (.srt, .vtt, .ass, .ssa) with OpenSubtitle integration and preference engine.
- **Subtitle Generation**: AI-powered subtitle generation via NVIDIA Parakeet TDT 0.6B v3.
- **Video Streaming**: HTTP-based streaming with range request support and on-the-fly MKV-to-MP4 transcoding (configurable/disableable).
- **HLS Streaming**: Adaptive bitrate streaming with FFmpeg segmenter, master/variant playlists, and media segments.
- **Playback Controls**: Full video controls including speed adjustment, seeking, fullscreen, and audio-track selection.
- **Queue Management**: Video queue with add, remove, reorder, and persistence.
- **Resume Playback**: Remembers and resumes video position across sessions (per-profile).
- **Thumbnail Generation**: Automatic thumbnail extraction and caching with background queue processing.
- **Storyboard Service**: Video storyboard generation (10x10 tile grid) for preview.
- **Metadata Enrichment**: External metadata fetching via IMDb, TMDb, OMDb, TVMaze, and IntroDB API integration, with background enrichment queue.
- **Smart Naming Service**: Intelligent video naming and organization.
- **Audio Track Selection**: Multi-track audio stream support with per-video preferences.
- **Video Collections**: User-curated media collections with watch progress tracking.
- **Video Suggestions**: User-submitted video suggestion system.

### 📺 **IPTV & Live TV**
- **Xtream Codes API Emulation**: IPTV-compatible API layer for VOD, series, and live channels.
- **M3U Playlist Import**: Import live-channel playlists with groups, favorites, and streaming.
- **EPG / XMLTV**: Electronic Program Guide import and XMLTV output.

### 📥 **Flexible Media Import**
- **Music Import**: Integrates with `Spot-dl` for Spotify downloads and `yt-dlp` for YouTube.
- **Video Import**: Recursive directory scanning with multi-threaded processing.
- **Incremental Scanning**: Only processes new or modified files for faster updates.
- **Setup Wizard**: Guided initial configuration for library paths and requirements.

### 🔄 **Sync & Multi-Server**
- **SyncAPI / SyncExchangeAPI**: Trigger, status, and settings for cross-server media sync.

### ⚡ **Real-Time Interactivity**
- Powered by **WebSockets** for instant updates on playback, queues, and import status.
- Real-time state synchronization across multiple connected clients.
- **Authenticated WebSocket handshakes** with profile-aware routing.
- **Reconnection with exponential backoff** and HTTP polling fallback.

### 👤 **Multi-Profile & User Support**
- Separate playback states, histories, and preferences for different users.
- Profile-specific media libraries and settings.
- User context filtering for multi-client scenarios.

### 🔐 **Authentication & Security**
- **Session-based authentication** with login/logout and cookie-based sessions.
- **Role-based access control** — admin and user roles with appropriate restrictions.
- **Rate limiting** on auth endpoints (IP-based and per-username).
- **jBCrypt password hashing** for secure credential storage.
- **Admin user management** — create, update, delete users, manage roles and sessions.

### 🔧 **Comprehensive REST API**
- Based on **Quarkus REST** and **Jackson**.
- **42 API controller classes** covering music, video, subtitles, genres, collections, live TV, IPTV, sync, and system administration.
- Separate API endpoints for music, video, subtitles, genres, collections, and UI components.
- **HTMX-integrated endpoints** returning HTML fragments for dynamic UI updates.

### 🧠 **Efficient Data Management**
- Uses **Hibernate ORM with Panache** for simplified persistence.
- Local **H2** database ensures fast and lightweight storage.
- Pagination support for large media collections.
- **Caching layer** for rate limiting and performance optimization.

### 🔒 **Security & Reliability**
- **Local-network aware**: requests from outside the configured local network (CIDRs via `jmedia.local-network-cidrs`) get a **`Secure` session cookie**, so **remote/online access requires HTTPS** (TLS terminated by an external reverse proxy).
- Local/LAN requests work over plain HTTP.
- **Rate limiting** to prevent abuse.
- **Circuit breaker** and fault tolerance patterns.
- **Configurable thread pools** (analysis, scan, enrichment, transcoding) with per-pool sizing and disabling.
- **Platform-specific operations** for Windows, macOS, and Linux.

### 🔄 **Update Management**
- Built-in update checker comparing against GitHub releases.
- Version comparator for intelligent update detection.

### 🛠️ **Installation & Requirements Management**
- Automated installation of Python, FFmpeg, SpotDL, yt-dlp, Whisper, Parakeet, Tesseract, Deno, and Node.js.
- Per-component update and uninstall with concurrency guards.
- Installation status tracking and management.
- Platform-aware dependency handling.

---

## ⚙️ Performance & Efficiency

JMedia is engineered for **maximum performance and minimal resource usage**, targeting **at least 50% greater efficiency** than conventional streaming platforms.
This means:
- Reduced CPU and memory footprint
- Faster response times
- Negligible ecological impact
- Background queue processing for thumbnails, subtitles, and metadata enrichment
- Incremental library scanning to minimize reprocessing
- TarsosDSP analysis serialization to prevent heap exhaustion

---

## 🧰 Technology Stack

| Layer | Technology |
|-------|-------------|
| **Backend** | Java 25+, Quarkus 3.34.1 |
| **Frontend** | HTML, CSS, JavaScript, HTMX, Alpine.js |
| **Templating** | Qute |
| **Database** | H2 (file-based, under `~/.jmedia/` — separate `jmedia-settings`, `jmedia-music`, and `jmedia-video` DBs) |
| **ORM** | Hibernate with Panache |
| **Real-Time Communication** | Jakarta WebSockets |
| **Audio Processing** | jaudiotagger (metadata), TarsosDSP (BPM/beat detection) |
| **Video Processing** | ffprobe, FFmpeg (transcoding, HLS segmenting, conversion) |
| **Subtitle Processing** | FFprobe (embedded extraction), OpenSubtitle (search/download), JASSUB (ASS rendering in browser) |
| **AI/ML** | OpenAI Whisper (music lyrics), NVIDIA Parakeet TDT 0.6B v3 (video subtitles) |
| **Video Players** | Video.js, OPlayer (with HLS.js, HEVC WASM decoding, custom adapters) |
| **External APIs** | IMDb, TMDb, OMDb, TVMaze (metadata enrichment), IntroDB (intro/credits detection) |
| **Build Tool** | Maven |
| **CSS Framework** | Bulma CSS |
| **Icons** | Font Awesome 6, PrimeIcons |
| **Security** | Quarkus Security, Elytron, jBCrypt, reverse-proxy TLS |
| **Resilience** | SmallRye Fault Tolerance, Circuit Breaker |
| **Caching** | Quarkus Cache |
| **Scheduling** | Quarkus Scheduler |
| **Async Execution** | Eclipse Microprofile Context Propagation (ManagedExecutor) |

---

## 🎯 Features Overview

JMedia provides comprehensive media management with separate interfaces for music and video content:

### 🎵 **Music Features**
- Full music library management with metadata extraction
- Playlist creation and management (including shared playlists)
- Playback queue with persistence across sessions
- Search and filtering with pagination
- Playback history tracking
- Import from online sources (Spotify, YouTube)
- Genre-based browsing and carousels
- Lyrics viewing and AI-powered generation (Whisper)
- Album art display and extraction with caching
- Audio analysis (BPM, beat detection, spectral analysis) via TarsosDSP, embedded into file tags
- DJ Mode with beat-aligned crossfade transitions
- Metadata write-back to audio files

### 🎬 **Video Features**
- Movie and TV series library management
- Episode/season organization with smart detection
- Video streaming with subtitle support (multiple tracks)
- HLS adaptive bitrate streaming
- On-the-fly MKV-to-MP4 transcoding (configurable/disableable)
- Playback queue and history
- Resume playback functionality (per-profile)
- Advanced video controls (speed, seeking, fullscreen)
- Thumbnail generation and caching (background queue)
- Video storyboard previews (10x10 tile grid)
- External metadata enrichment (IMDb, TMDb, OMDb, TVMaze)
- Intro/credits detection via IntroDB
- Smart video naming and organization
- Audio track selection with per-video preferences
- User-curated collections with watch progress
- Video suggestions system

### 📺 **IPTV & Live TV Features**
- Xtream Codes IPTV API emulation (VOD, series, live)
- M3U playlist import with channel groups and favorites
- EPG / XMLTV program guide

### 🔄 **Sync Features**
- Cross-server media sync with trigger, status, and settings
- API-key protected exchange endpoints

### 🛠️ **System Features**
- Multi-user authentication with role-based access (admin/user)
- Multi-profile support with isolated playback state
- Dark/light theme switching with system preference detection
- Responsive web interface (desktop, tablet, mobile)
- Real-time WebSocket updates (music state, video state, import status)
- Comprehensive REST API (42 endpoint classes)
- Background service mode with tray icon (Windows)
- Library maintenance tools (scan, reload, cleanup, duplicate removal)
- Setup wizard for initial configuration (4-step)
- Automated dependency installation (Python, FFmpeg, SpotDL, yt-dlp, Whisper, Tesseract, Deno, Node)
- Update checking via GitHub Releases API
- Session-based authentication with rate limiting
- Serves plain HTTP on `:8080`; remote/non-local clients require HTTPS via a reverse proxy (session cookie is marked `Secure` off the local network)
- Configurable thread pools (analysis, scan, enrichment, transcoding)
- System logging via SLF4J
- Platform-specific optimizations (Windows, macOS, Linux)
- Admin statistics (system stats, transcoding)

For a detailed breakdown of all features and their implementation status, see the [Features Overview](features.md).

---

## 🚀 Installation

### 🔹 **Prebuilt Executables**
Download the latest release from the [📦 GitHub Releases](https://github.com/monsterwhat/JMedia/releases) page:

- **Windows:**
  Download `JMedia.exe` and run it directly.
  > ⚠️ **Requires Java 25+** - If you get a Java error, see [JAVA_REQUIRED.md](JAVA_REQUIRED.md) for installation instructions.

- **Cross-Platform (JAR):**
  Requires **Java 25+**.
  ```bash
  java -jar JMedia-runner.jar
  ```
  > 💡 Tip: On most systems, you can double-click the `.jar` to launch it.

### 🔹 **System Requirements**
- **Java 25** or newer (required for all versions)
- Modern web browser (Chrome, Firefox, Safari, Edge)
- **FFmpeg/ffprobe** — can be installed automatically via the app's settings

### 🔹 **Native Builds (Coming Soon)**
Native executables for **Linux**, **macOS**, and **Windows** are in development.
These builds will run standalone without needing a separate Java installation.

---

## 🧑‍💻 Developer Setup

### Prerequisites
- **Java 25** or newer
- **Maven 3.8+**

### Steps

1. **Clone the Repository**
   ```bash
   git clone https://github.com/monsterwhat/JMedia.git
   cd JMedia/JMedia/com.playdeca.JMedia
   ```
   > Note: The repository is named `JMusic` locally; the project package is `com.playdeca.JMedia`.

2. **Run in Development Mode**
   ```bash
   mvn quarkus:dev
   ```
   Enables hot reload and live coding support.

3. **Build for Production**
   ```bash
   mvn clean package
   ```
   Then run the self-contained uber jar:
   ```bash
   java -jar target/JMedia-runner.jar
   ```

### 🚢 **Releasing**

The version lives in `JMedia/com.playdeca.JMedia/pom.xml` and releases are fully automatic:

- **Just bump the version (SemVer) and push to main** — the Release workflow detects the unreleased version, tags it `v<version>`, builds the uber jar and publishes a GitHub Release with `JMedia-<version>-runner.jar` attached. Pushes whose version is already released are skipped.
- **Tag push** also works: `git tag v1.3.4 && git push origin v1.3.4` — fails if the tag doesn't match the pom version.
- **Manual**: Actions → *Release* → *Run workflow*.

### 🏗️ **Project Structure**
```
src/main/java/
├── API/                    # REST APIs, WebSockets, and Filters
│   ├── Rest/               # REST endpoint controllers (42 classes)
│   │   ├── Music:          # PlaybackAPI, SongAPI, MusicPlaylistApi, QueueAPI,
│   │   │                   # MusicUiApi, StreamAPI, GenreAPI
│   │   ├── Video:          # VideoAPI, VideoPlaybackAPI, VideoManagementApi,
│   │   │                   # VideoQueueAPI, VideoUiApi, VideoExternalAPI,
│   │   │                   # VideoStreamResource, SeriesAPI, SubtitleAPI,
│   │   │                   # AiSubtitleApi, Storyboard/Thumbnail (via VideoAPI)
│   │   ├── Collections:    # CollectionApi, CollectionUiApi, CollectionPlaybackAPI
│   │   ├── IPTV/Live:      # XtreamCodesAPI, XtreamStreamAPI, GetPhpApi, PlaylistApi,
│   │   │                   # M3uImportApi, EpgApi, XmlTvApi
│   │   ├── Sync:           # SyncAPI, SyncExchangeAPI
│   │   ├── System:         # SettingsApi, SystemAPI, StatsController, ImportApi,
│   │   │                   # InstallationApi, SetupApi, UpdateAPI,
│   │   │                   # MetadataEnrichmentApi, StandardizationApi,
│   │   │                   # EnhancedAuthAPI, UserManagementAPI, ProfileAPI
│   │   └── HLS:            # HlsResource
│   ├── WS/                 # WebSocket endpoints
│   │   ├── MusicSocket     # Music state sync
│   │   ├── VideoSocket     # Video state sync
│   │   ├── ImportStatusSocket # Import progress
│   │   └── (auth)          # WebSocketAuthService, WebSocketAuthConfigurator,
│   │                       # WebSocketManager
│   └── Filter/             # HTTP filters
│       └── UserContextFilter, AuthGateFilter
├── Controllers/            # Application controllers (14 classes)
├── Services/               # Business logic services (97 classes)
│   ├── Platform/           # OS-specific operations (Win/Mac/Linux)
│   └── Thumbnail/          # Thumbnail processing queue
├── Models/                 # Data models and entities (70 classes)
│   ├── Music/              # Song entities
│   ├── Settings/           # Users, profiles, settings
│   ├── Video/              # Video, series, live channels
│   ├── Xtream/             # IPTV models
│   └── DTOs/               # Data transfer objects (30 classes)
├── Detectors/              # Media content detection
├── Migrations/             # Database migrations
├── Filters/                # Application filters
└── Utils/                  # Utility classes

src/main/resources/
├── META-INF/resources/     # Static web assets (HTML, CSS, JS, views/)
│   ├── views/              # SPA view shells (music, video, video-classic, settings, import)
│   ├── js/                 # JavaScript modules (93 files: jmedia/, musicBar/, player/, video/)
│   ├── css/                # Stylesheets
│   └── lib/                # Vendored libs (videojs, oplayer, hls, jassub, hevc, alpine, font-awesome)
├── templates/              # Qute HTML template fragments (69 files)
├── WEB-INF/pages/          # Full page templates
└── db/                     # Database migration SQL
```

---

## 🎧 Usage

Once the application is running, open your browser and visit:

```
http://localhost:8080
```

> 🛡️ **HTTPS / reverse proxy**: JMedia serves plain HTTP on `:8080`. For **remote/online** access, it requires HTTPS: the app marks the session cookie `Secure` for clients **outside** the configured local network, so TLS must be terminated by an external reverse proxy (e.g. Nginx, Caddy, Apache, or a cloud load balancer) forwarding to `:8080`. Local/LAN requests work over plain HTTP. The local network is defined by the `jmedia.local-network-cidrs` config.

### 📱 **Main Interface**
- **Music (`/`)**: Music library, playlists, and playback controls
- **Videos (`/video`)**: Video library with movies and TV series
- **Settings (`/settings`)**: Library configuration, profiles, and system settings
- **Import (`/import`)**: Import media from online sources
- **Setup (`/setup`)**: Initial configuration wizard

### 🎵 **Music Features**
- Import and manage your local music library
- Create and manage playlists (including shared playlists)
- Control playback with queue management
- View playback history and statistics
- Browse by genre with dynamic carousels
- View and generate lyrics with Whisper AI

### 🎬 **Video Features**
- Scan and organize video libraries
- Browse movies and TV series with episode/season organization
- Stream videos with multi-track subtitle support
- Manage video queue and resume playback
- View video thumbnails and storyboard previews

### 📺 **IPTV & Live TV**
- Import M3U playlists and browse live channels
- Stream live channels with EPG program guide
- Use Xtream Codes-compatible client apps

### ⚙️ **Configuration**
- Set up music and video library paths
- Create and manage user profiles
- Configure themes, player, and system behavior
- Run as background service with tray icon
- Install/manage dependencies (Python, FFmpeg, SpotDL, yt-dlp, Whisper, Tesseract, Deno, Node)
- Configure thread pools (analysis, scan, enrichment, transcoding)

---

## 📘 API Documentation

The REST API endpoints are located in `src/main/java/API/Rest`. JMedia exposes **42 API controller classes** covering music, video, subtitles, genres, collections, IPTV/live TV, sync, and system administration.

### 🎵 **Music API Endpoints**
- **PlaybackAPI**: `/api/music/playback/` - Playback control (play/pause/next/previous/seek/volume/shuffle/repeat/DJ mode/crossfade)
- **SongAPI**: `/api/song/` - Song library operations, lyrics viewing, Whisper lyrics generation, metadata write-back
- **MusicPlaylistApi**: `/api/music/playlists/` - Playlist CRUD, song association, shared playlist toggle, create-from-text
- **QueueAPI**: `/api/music/queue/` - Queue management (add/remove/skip-to/clear), history
- **MusicUiApi**: `/api/music/ui/` - HTMX fragments for queue, playlists, search, history, album view, genres
- **StreamAPI**: `/api/music/stream` - Audio streaming with HTTP Range request support and artwork
- **GenreAPI**: `/api/genres` - Genre seeding, auto-assignment, rebuild, statistics, validation

### 🎬 **Video API Endpoints**
- **VideoAPI**: `/api/video/` - Video library queries, streaming, scanning, thumbnail/storyboard generation, genre/carousel browsing, watchlist, progress tracking, metadata reload
- **VideoStreamResource**: `/api/video/stream/{videoId}.mp4` - Direct video streaming
- **VideoPlaybackAPI**: `/api/video/playback/` - Video playback control (play/pause/seek/volume/next/previous/audio & subtitle selection)
- **VideoManagementApi**: `/api/video/manage` - Video library management HTMX fragments (edit, convert, verify, refetch)
- **VideoQueueAPI**: `/api/video/queue/` - Video queue operations
- **VideoUiApi**: `/api/video/ui/` - Video UI HTMX fragments (movies, shows, episodes, carousels, queue, live TV)
- **VideoExternalAPI**: `/api/video/external` - External video sources management
- **SeriesAPI**: `/api/series/` - Series details, episodes, posters, backdrops

### 🎭 **Subtitle API Endpoints**
- **SubtitleAPI**: `/api/video/subtitles` - Subtitle generation (Parakeet), OpenSubtitles search/download, local file management, per-video preferences, track management
- **AiSubtitleApi**: `/api/ai-subtitles` - AI subtitle generation status, tracks, cancellation, languages

### 📺 **IPTV & Live TV API Endpoints**
- **XtreamCodesAPI**: `/player_api.php` - IPTV-compatible VOD/series/live categories, streams, info, and streaming redirects
- **XtreamStreamAPI**: `/movie/...`, `/series/...`, `/live/...` - IPTV-compatible streaming URLs
- **GetPhpApi**: `/get.php` - M3U/M3U8 playlist generation for IPTV clients
- **M3uImportApi**: `/api/video/m3u` - M3U playlist import, channel groups, favorites, streaming
- **EpgApi**: `/api/epg` - EPG import and status
- **XmlTvApi**: `/xmltv.php` - XMLTV program guide output

### 🗂️ **Collections API Endpoints**
- **CollectionApi**: `/api/collections` - Media collection CRUD
- **CollectionUiApi**: `/api/video/ui/collections-*` - Collection UI HTMX fragments
- **CollectionPlaybackAPI**: `/api/collections/*/play` - Collection playback management

### 📺 **HLS Streaming API**
- **HlsResource**: `/api/hls` - HLS session management, master playlist, variant playlists, media segments

### 🔄 **Sync API Endpoints**
- **SyncAPI**: `/api/sync` - Sync trigger, status, settings, servers, logs
- **SyncExchangeAPI**: `/api/sync/ping`, `/api/sync/exchange` - Sync exchange endpoints

### 🔧 **System API Endpoints**
- **SettingsApi**: `/api/settings/` - Library paths, scan, metadata toggles, thread pools, default player, duplicate removal, requirements installation
- **SystemAPI**: `/api/system` - GPU info, codec capabilities
- **StatsController**: `/api/admin/stats` - System statistics, transcoding stats
- **ProfileAPI**: `/api/profiles/` - Profile CRUD, switching, hidden playlists
- **ImportApi**: `/api/import/` - Import status, dependency install/uninstall/update (Python, FFmpeg, SpotDL, yt-dlp, Whisper, Tesseract, Deno, Node)
- **InstallationApi**: `/api/installation` - Dependency installation management
- **SetupApi**: `/api/setup/` - 4-step setup wizard (status, validate paths, complete, reset)
- **UpdateAPI**: `/api/update/` - GitHub release checking, latest version info
- **MetadataEnrichmentApi**: `/api/metadata` - External metadata fetching, album art enrichment
- **StandardizationApi**: `/api/standardize` - Video standardization queue
- **EnhancedAuthAPI**: `/api/auth/` - Login, logout, session status, current user, admin check
- **UserManagementAPI**: `/api/users/` - User CRUD, role management, session management

### 📡 **WebSocket Endpoints**
- **MusicSocket**: `ws://localhost:8080/api/music/ws/{profileId}` - Real-time music state synchronization (playback state, history updates)
- **VideoSocket**: `ws://localhost:8080/api/video/ws/{profileId}` - Real-time video state synchronization (seek, volume, play/pause, next/previous)
- **ImportStatusSocket**: `ws://localhost:8080/ws/import-status/{profileId}` - Import and installation progress tracking

> ℹ️ WebSocket connections are authenticated via the `WebSocketAuthConfigurator`.

For complete API documentation with request/response examples, see [API.md](API.md).

---

## 🤝 Contributing

We welcome all contributions!

To contribute:
1. Fork the repository
2. Create a new branch for your feature or fix
3. Make your changes and test thoroughly
4. Submit a pull request with a clear summary of your changes

> 🧭 Please ensure your code aligns with JMedia's principles of **privacy**, **decentralization**, and **efficiency**.

---

## 📄 License

Licensed under the [**GNU General Public License v3.0**](https://www.gnu.org/licenses/gpl-3.0.en.html).

This license ensures:
- Freedom to use, modify, and distribute the software
- All derivative works must remain open-source
- No proprietary forks of this codebase

---

## ❤️ Acknowledgments

- [Quarkus](https://quarkus.io) — Supersonic Subatomic Java
- [Bulma CSS](https://bulma.io) — Modern CSS framework
- [HTMX](https://htmx.org) — High-power tools for HTML
- [Alpine.js](https://alpinejs.dev) — Rugged JavaScript framework
- [Video.js](https://videojs.com) — Video player
- [OPlayer](https://github.com/ojack/OPlayer) — Video player
- [jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger) — Audio metadata tagging
- [FFmpeg/ffprobe](https://ffmpeg.org/) — Multimedia processing
- [Whisper](https://github.com/openai/whisper) — AI transcription for music lyrics
- [NVIDIA Parakeet](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) — AI transcription for video subtitles
- [OpenSubtitle](https://www.opensubtitles.org/) — Subtitle database
- [IMDb](https://www.imdb.com/) — Movie and TV metadata
- The open-source community and all contributors
