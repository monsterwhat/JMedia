# 🎵🎬 JMedia  
### A Decentralized, Private, and Efficient Media Streaming Application  

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0.en.html)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/projects/jdk/17/)
[![Quarkus](https://img.shields.io/badge/Powered%20by-Quarkus%203.34-red.svg)](https://quarkus.io/)
[![Build with Maven](https://img.shields.io/badge/Build-Maven-blue.svg)](https://maven.apache.org/)

---

## 🌐 Overview

**JMedia** is a decentralized, privacy-focused media streaming application built with **Java** and **Quarkus**.  
It provides a **serverless**, and **user-controlled** experience for managing and streaming your **music and video** libraries.  

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

### 🎶 **Comprehensive Music Library**
- Organize songs, edit metadata, and manage playlists.  
- Efficiently handles large music collections.  
- Uses [`jaudiotagger`](https://bitbucket.org/ijabz/jaudiotagger) for advanced audio metadata support.
- Playback history and queue persistence across sessions.
- **Genre-based browsing** with auto-seeded genre classification.
- **Lyrics generation** powered by Whisper AI.
- **Album art extraction** and artwork caching.

### 🎬 **Full Video Management System**
- **Smart Video Import**: Automatic library scanning with metadata extraction using `ffprobe`.
- **Content Detection**: Intelligent detection of movies and TV series with episode/season parsing.
- **Subtitle Support**: Automatic subtitle file matching (.srt, .vtt, .ass, .ssa) with OpenSubtitle integration and preference engine.
- **Video Streaming**: HTTP-based streaming with range request support and transcoding.
- **Playback Controls**: Full video controls including speed adjustment, seeking, and fullscreen mode.
- **Queue Management**: Video queue with add, remove, reorder, and persistence.
- **Resume Playback**: Remembers and resumes video position across sessions.
- **Thumbnail Generation**: Automatic thumbnail extraction and caching with background queue processing.
- **Storyboard Service**: Video storyboard generation for preview.
- **IMDb Metadata Enrichment**: External metadata fetching via IMDb API integration.
- **Smart Naming Service**: Intelligent video naming and organization.

### 📥 **Flexible Media Import**
- **Music Import**: Integrates with `Spot-dl` for Spotify downloads and `yt-dlp` for YouTube.
- **Video Import**: Recursive directory scanning with multi-threaded processing.
- **Incremental Scanning**: Only processes new or modified files for faster updates.
- **Setup Wizard**: Guided initial configuration for library paths and requirements.

### ⚡ **Real-Time Interactivity**
- Powered by **WebSockets** for instant updates on playback, queues, and import status.
- Real-time state synchronization across multiple connected clients.
- WebSocket connection management with profile-aware routing.

### 👤 **Multi-Profile & User Support**
- Separate playback states, histories, and preferences for different users.
- Profile-specific media libraries and settings.
- User context filtering for multi-client scenarios.
- Enhanced authentication support.

### 🔧 **Comprehensive REST API**
- Based on **Quarkus REST** and **Jackson**.  
- Full programmatic control of music and video libraries, playback, and settings.  
- Separate API endpoints for music, video, subtitles, genres, and UI components.
- **HTMX-integrated endpoints** returning HTML fragments for dynamic UI updates.

### 🧠 **Efficient Data Management**
- Uses **Hibernate ORM with Panache** for simplified persistence.  
- Local **H2** database ensures fast and lightweight storage.
- Pagination support for large media collections.
- **Caching layer** for rate limiting and performance optimization.

### 🔒 **Security & Reliability**
- **HTTPS support** with automatic certificate management.
- **Rate limiting** to prevent abuse.
- **Circuit breaker** and fault tolerance patterns.
- **Platform-specific operations** for Windows, macOS, and Linux.

### 🔄 **Update Management**
- Built-in update checker comparing against GitHub releases.
- Version comparator for intelligent update detection.

### 🛠️ **Installation & Requirements Management**
- Automated installation of Python, FFmpeg, SpotDL, and Whisper.
- Installation status tracking and management.
- Platform-aware dependency handling.

---

## ⚙️ Performance & Efficiency

JMedia is engineered for **maximum performance and minimal resource usage**, targeting **at least 50% greater efficiency** than conventional streaming platforms.  
This means:
- Reduced CPU and memory footprint  
- Faster response times  
- Negligible ecological impact  
- Background queue processing for thumbnails and subtitles
- Incremental library scanning to minimize reprocessing

---

## 🧰 Technology Stack

| Layer | Technology |
|-------|-------------|
| **Backend** | Java 17+, Quarkus 3.34 |
| **Frontend** | HTML, CSS, JavaScript, HTMX, Alpine.js |
| **Templating** | Qute |
| **Database** | H2 |
| **ORM** | Hibernate with Panache |
| **Real-Time Communication** | WebSockets |
| **Audio Processing** | jaudiotagger |
| **Video Processing** | ffprobe, FFmpeg (transcoding) |
| **Subtitle Processing** | FFprobe, OpenSubtitle integration |
| **AI/ML** | Whisper (lyrics generation) |
| **External APIs** | IMDb (metadata enrichment) |
| **Build Tool** | Maven |
| **CSS Framework** | Bulma CSS |
| **Security** | Quarkus Security, Elytron, HTTPS/Certificates |
| **Resilience** | SmallRye Fault Tolerance, Circuit Breaker |
| **Caching** | Quarkus Cache |
| **Scheduling** | Quarkus Scheduler |
| **Password Hashing** | jBCrypt |

---

## 🎯 Features Overview

JMedia provides comprehensive media management with separate interfaces for music and video content:

### 🎵 **Music Features**
- Full music library management with metadata extraction
- Playlist creation and management (including shared playlists)
- Playback queue with persistence
- Search and filtering capabilities
- Playback history tracking
- Import from online sources (Spotify, YouTube)
- Genre-based browsing and carousels
- Lyrics viewing and AI-powered generation
- Album art display and extraction

### 🎬 **Video Features**
- Movie and TV series library management
- Episode/season organization with smart detection
- Video streaming with subtitle support (multiple tracks)
- Playback queue and history
- Resume playback functionality
- Advanced video controls (speed, seeking, fullscreen)
- Thumbnail generation and caching
- Video storyboard previews
- IMDb metadata enrichment
- Smart video naming and organization
- Video editing metadata (title, description, etc.)

### 🛠️ **System Features**
- Multi-user profile support
- Dark/light theme switching
- Responsive web interface
- Real-time WebSocket updates
- Comprehensive REST API
- Background service mode with tray icon
- Library maintenance tools (scan, reload, cleanup, duplicate removal)
- Setup wizard for initial configuration
- Automated dependency installation (Python, FFmpeg, SpotDL, Whisper)
- Update checking and version management
- HTTPS with certificate management
- System logging and log streaming
- Platform-specific optimizations (Windows, macOS, Linux)

For a detailed breakdown of all features and their implementation status, see the [Features Overview](features.md).

---

## 🚀 Installation

### 🔹 **Prebuilt Executables**
Download the latest release from the [📦 GitHub Releases](https://github.com/monsterwhat/JMedia/releases) page:

- **Windows:**  
  Download `JMedia.exe` and run it directly.  
  > ⚠️ **Requires Java 17+** - If you get a Java error, see [JAVA_REQUIRED.md](JAVA_REQUIRED.md) for installation instructions.

- **Cross-Platform (JAR):**  
  Requires **Java 17+**.  
  ```bash
  java -jar JMedia-runner.jar
  ```
  > 💡 Tip: On most systems, you can double-click the `.jar` to launch it.

### 🔹 **System Requirements**
- **Java 17** or newer (required for all versions)
- Modern web browser (Chrome, Firefox, Safari, Edge)
- **FFmpeg/ffprobe** — can be installed automatically via the app's settings

### 🔹 **Native Builds (Coming Soon)**
Native executables for **Linux**, **macOS**, and **Windows** are in development.  
These builds will run standalone without needing a separate Java installation.

---

## 🧑‍💻 Developer Setup

### Prerequisites
- **Java 17** or newer  
- **Maven 3.8+**

### Steps

1. **Clone the Repository**
   ```bash
   git clone https://github.com/monsterwhat/JMedia.git
   cd JMedia/JMedia/com.playdeca.JMedia
   ```

2. **Run in Development Mode**
   ```bash
   mvn quarkus:dev
   ```
   Enables hot reload and live coding support.

3. **Build for Production**
   ```bash
   mvn clean package
   ```
   Then run:
   ```bash
   java -jar target/quarkus-app/quarkus-run.jar
   ```

### 🏗️ **Project Structure**
```
src/main/java/
├── API/                    # REST APIs, WebSockets, and Filters
│   ├── Rest/               # REST endpoint controllers
│   │   ├── PlaybackAPI     # Music playback control
│   │   ├── SongAPI         # Song operations & lyrics
│   │   ├── PlaylistAPI     # Playlist management
│   │   ├── QueueAPI        # Music queue operations
│   │   ├── MusicUiApi      # Music UI fragments (HTMX)
│   │   ├── VideoAPI        # Video library & streaming
│   │   ├── VideoPlaybackAPI # Video playback control
│   │   ├── VideoManagementApi # Video library management
│   │   ├── VideoQueueAPI   # Video queue operations
│   │   ├── VideoUiApi      # Video UI fragments (HTMX)
│   │   ├── SubtitleAPI     # Subtitle management
│   │   ├── StreamAPI       # Media streaming
│   │   ├── GenreAPI        # Genre browsing
│   │   ├── SettingsApi     # Configuration
│   │   ├── ProfileAPI      # User profiles
│   │   ├── ImportApi       # Media import
│   │   ├── InstallationApi # Dependency installation
│   │   ├── SetupApi        # Setup wizard
│   │   ├── UpdateAPI       # Update checking
│   │   ├── MetadataEnrichmentApi # External metadata
│   │   ├── EnhancedAuthAPI # Authentication
│   │   └── UserManagementAPI # User management
│   ├── WS/                 # WebSocket endpoints
│   │   ├── MusicSocket     # Music state sync
│   │   ├── VideoSocket     # Video state sync
│   │   ├── LogSocket       # Log streaming
│   │   └── ImportStatusSocket # Import progress
│   └── Filter/             # HTTP filters
│       └── UserContextFilter
├── Controllers/            # Application controllers
├── Services/               # Business logic services
│   ├── Platform/           # OS-specific operations
│   └── Thumbnail/          # Thumbnail processing
├── Models/                 # Data models and entities
│   └── DTOs/               # Data transfer objects
├── Detectors/              # Media content detection
└── Utils/                  # Utility classes

src/main/resources/
├── META-INF/resources/     # Static web assets (HTML, CSS, JS)
├── templates/              # Qute HTML template fragments
└── WEB-INF/pages/          # Full page templates
```

---

## 🎧 Usage

Once the application is running, open your browser and visit:

```
http://localhost:8080
```

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

### ⚙️ **Configuration**
- Set up music and video library paths
- Create and manage user profiles
- Configure themes and system behavior
- Run as background service with tray icon
- Install/manage dependencies (Python, FFmpeg, SpotDL, Whisper)
  
---

## 📘 API Documentation

The REST API endpoints are located in:

```
src/main/java/API/Rest
```

### 🎵 **Music API Endpoints**
- **PlaybackAPI**: `/api/music/playback/` - Playback control and state management
- **SongAPI**: `/api/song/` - Song library operations, lyrics, and lyrics generation
- **PlaylistAPI**: `/api/music/playlists/` - Playlist management
- **QueueAPI**: `/api/music/queue/` - Playback queue operations
- **MusicUiApi**: `/api/music/ui/` - Music UI components (HTMX fragments)

### 🎬 **Video API Endpoints**
- **VideoAPI**: `/api/video/` - Video library and streaming
- **VideoPlaybackAPI**: `/api/video/playback/` - Video playback control
- **VideoManagementApi**: `/api/video/manage` - Video library management (HTMX)
- **VideoQueueAPI**: `/api/video/queue/` - Video queue operations
- **VideoUiApi**: `/api/video/ui/` - Video UI components (HTMX fragments)

### 🎭 **Subtitle API Endpoints**
- **SubtitleAPI**: `/api/video/subtitles` - Subtitle track management, preferences, and Whisper generation
- **StreamAPI**: `/api/music/stream` - Audio streaming with HTTP range request support

### 🎭 **Genre API Endpoints**
- **GenreAPI**: `/api/genres` - Genre seeding, auto-assignment, and statistics

### 🔧 **System API Endpoints**
- **SettingsApi**: `/api/settings/` - Configuration and system settings
- **ProfileAPI**: `/api/profiles/` - User profile management
- **ImportApi**: `/api/import/` - Media import operations
- **InstallationApi**: `/api/installation` - Dependency installation management
- **SetupApi**: `/api/setup/` - Setup wizard
- **UpdateAPI**: `/api/update/` - Update checking
- **MetadataEnrichmentApi**: `/api/metadata` - External metadata fetching and album art enrichment
- **EnhancedAuthAPI**: `/api/auth/` - Authentication endpoints
- **UserManagementAPI**: `/api/users/` - User management

### 📡 **WebSocket Endpoints**
- **MusicSocket**: `ws://localhost:8080/api/music/ws/{profileId}` - Real-time music state synchronization
- **VideoSocket**: `ws://localhost:8080/api/video/ws` - Real-time video state synchronization
- **LogSocket**: `ws://localhost:8080/api/logs/ws/{profileId}` - System logging and import status
- **ImportStatusSocket**: `ws://localhost:8080/ws/import-status/{profileId}` - Import progress tracking

These endpoints support operations for:
- Playback control (music and video)  
- Library management (music and video)  
- Metadata operations  
- Subtitle management  
- Genre browsing
- User profile management  
- System configuration
- Dependency installation
- Update checking

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
- [jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger) — Audio metadata tagging
- [FFmpeg/ffprobe](https://ffmpeg.org/) — Multimedia processing
- [Whisper](https://github.com/openai/whisper) — AI-powered transcription
- [OpenSubtitle](https://www.opensubtitles.org/) — Subtitle database
- [IMDb](https://www.imdb.com/) — Movie and TV metadata
- The open-source community and all contributors
