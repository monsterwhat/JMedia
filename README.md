# 🎵🎬 JMedia  
### A Decentralized, Private, and Efficient Media Streaming Application  

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0.en.html)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Quarkus](https://img.shields.io/badge/Powered%20by-Quarkus-red.svg)](https://quarkus.io/)
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

### 🎶 **Comprehensive Music Library**
- Organize songs, edit metadata, and manage playlists.  
- Efficiently handles large music collections.  
- Uses [`jaudiotagger`](https://bitbucket.org/ijabz/jaudiotagger) for advanced audio metadata support.
- Playback history and queue persistence across sessions.

### 🎬 **Full Video Management System**
- **Smart Video Import**: Automatic library scanning with metadata extraction using `ffprobe`.
- **Content Detection**: Intelligent detection of movies and TV series with episode/season parsing.
- **Subtitle Support**: Automatic subtitle file matching (.srt, .vtt, .ass, .ssa).
- **Video Streaming**: HTTP-based streaming with range request support.
- **Playback Controls**: Full video controls including speed adjustment, seeking, and fullscreen mode.
- **Queue Management**: Video queue with add, remove, reorder, and persistence.
- **Resume Playback**: Remembers and resumes video position across sessions.

### 📥 **Flexible Media Import**
- **Music Import**: Integrates with `Spot-dl` for Spotify downloads and `yt-dlp` for YouTube.
- **Video Import**: Recursive directory scanning with multi-threaded processing.
- **Incremental Scanning**: Only processes new or modified files for faster updates.

### ⚡ **Real-Time Interactivity**
- Powered by **WebSockets** for instant updates on playback, queues, and import status.
- Real-time state synchronization across multiple connected clients.

### 👤 **Multi-Profile Support**
- Separate playback states, histories, and preferences for different users.
- Profile-specific media libraries and settings.

### 🔧 **Comprehensive REST API**
- Based on **Quarkus REST** and **Jackson**.  
- Full programmatic control of music and video libraries, playback, and settings.  
- Separate API endpoints for music, video, and UI components.

### 🧠 **Efficient Data Management**
- Uses **Hibernate ORM with Panache** for simplified persistence.  
- Local **H2** database ensures fast and lightweight storage.
- Pagination support for large media collections.

---

## ⚙️ Performance & Efficiency

JMedia is engineered for **maximum performance and minimal resource usage**, targeting **at least 50% greater efficiency** than conventional streaming platforms.  
This means:
- Reduced CPU and memory footprint  
- Faster response times  
- Negligible ecological impact  

---

## 🧰 Technology Stack

| Layer | Technology |
|-------|-------------|
| **Backend** | Java 21+, Quarkus |
| **Frontend** | HTML, CSS, JavaScript, HTMX, Alpine.js |
| **Database** | H2 |
| **Real-Time Communication** | WebSockets |
| **ORM** | Hibernate with Panache |
| **Audio Processing** | jaudiotagger |
| **Video Processing** | ffprobe |
| **Build Tool** | Maven |
| **CSS Framework** | Bulma CSS |

---

## 🎯 Features Overview

JMedia provides comprehensive media management with separate interfaces for music and video content:

### 🎵 **Music Features**
- Full music library management with metadata extraction
- Playlist creation and management
- Playback queue with persistence
- Search and filtering capabilities
- Playback history tracking
- Import from online sources (Spotify, YouTube)

### 🎬 **Video Features**
- Movie and TV series library management
- Episode/season organization with smart detection
- Video streaming with subtitle support
- Playback queue and history
- Resume playback functionality
- Advanced video controls (speed, seeking, fullscreen)

### 🛠️ **System Features**
- Multi-user profile support
- Dark/light theme switching
- Responsive web interface
- Real-time WebSocket updates
- Comprehensive REST API
- Background service mode with tray icon
- Library maintenance tools (scan, reload, cleanup)

For a detailed breakdown of all features and their implementation status, see the [Features Overview](https://github.com/monsterwhat/JMedia/blob/main/features.md).

---

## 🚀 Installation

### 🔹 **Prebuilt Executables**
Download the latest release from the [📦 GitHub Releases](https://github.com/monsterwhat/JMedia/releases) page:

- **Windows:**  
  Download `JMedia.exe` and run it directly.  
  > ⚠️ **Requires Java 21+** - If you get a Java error, see [JAVA_REQUIRED.md](JAVA_REQUIRED.md) for installation instructions.

- **Cross-Platform (JAR):**  
  Requires **Java 21+**.  
  ```bash
  java -jar JMedia-runner.jar
  ```
  > 💡 Tip: On most systems, you can double-click the `.jar` to launch it.

### 🔹 **System Requirements**
- **Java 21** or newer (required for all versions)
- **ffprobe** (for video metadata extraction - included with most ffmpeg installations)
- Modern web browser (Chrome, Firefox, Safari, Edge)

### 🔹 **Native Builds (Coming Soon)**
Native executables for **Linux**, **macOS**, and **Windows** are in development.  
These builds will run standalone without needing a separate Java installation.

---

## 🧑‍💻 Developer Setup

### Prerequisites
- **Java 21** or newer  
- **Maven 3.8+**
- **ffprobe** (for video metadata extraction)

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
├── API/           # REST APIs and WebSocket handlers
├── Controllers/   # Application controllers
├── Services/      # Business logic services
├── Models/        # Data models and entities
└── Detectors/     # Media content detection utilities

src/main/resources/
├── META-INF/resources/  # Static web assets (HTML, CSS, JS)
└── templates/           # HTML template fragments
```

---

## 🎧 Usage

Once the application is running, open your browser and visit:

```
http://localhost
```

### 📱 **Main Interface**
- **Home (`/`)**: Music library, playlists, and playback controls
- **Videos (`/video`)**: Video library with movies and TV series
- **Settings (`/settings`)**: Library configuration, profiles, and system settings
- **Import (`/import`)**: Import media from online sources

### 🎵 **Music Features**
- Import and manage your local music library  
- Create and manage playlists  
- Control playback with queue management
- View playback history and statistics

### 🎬 **Video Features**
- Scan and organize video libraries
- Browse movies and TV series with episode/season organization
- Stream videos with subtitle support
- Manage video queue and resume playback

### ⚙️ **Configuration**
- Set up music and video library paths
- Create and manage user profiles
- Configure themes and system behavior
- Run as background service with tray icon
  

---

## 📘 API Documentation

The REST API endpoints are located in:

```
src/main/java/API/Rest
```

### 🎵 **Music API Endpoints**
- **PlaybackAPI**: `/api/playback/` - Playback control and state management
- **SongAPI**: `/api/songs/` - Song library operations
- **PlaylistAPI**: `/api/playlists/` - Playlist management
- **QueueAPI**: `/api/queue/` - Playback queue operations
- **MusicUiApi**: `/api/music/ui/` - Music UI components

### 🎬 **Video API Endpoints**
- **VideoAPI**: `/api/video/` - Video library and streaming
- **VideoUiApi**: `/api/video/ui/` - Video UI components

### 🔧 **System API Endpoints**
- **SettingsApi**: `/api/settings/` - Configuration and system settings
- **ProfileAPI**: `/api/profiles/` - User profile management
- **ImportApi**: `/api/import/` - Media import operations

### 📡 **WebSocket Endpoints**
- **MusicSocket**: Real-time music state synchronization
- **VideoSocket**: Real-time video state synchronization
- **LogSocket**: System logging and import status
- **ImportStatusSocket**: Import progress tracking

These endpoints support operations for:
- Playback control (music and video)  
- Library management (music and video)  
- Metadata operations  
- User profile management  
- System configuration

---

## 🤝 Contributing

We welcome all contributions!  

To contribute:
1. Fork the repository  
2. Create a new branch for your feature or fix  
3. Make your changes and test thoroughly  
4. Submit a pull request with a clear summary of your changes  

> 🧭 Please ensure your code aligns with JMedia’s principles of **privacy**, **decentralization**, and **efficiency**.

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
- [ffprobe](https://ffmpeg.org/ffprobe.html) — Multimedia analysis tool
- The open-source community and all contributors
