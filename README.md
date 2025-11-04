# 🎵 JMusic  
### A Decentralized, Private, and Efficient Music Streaming Application  

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0.en.html)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Quarkus](https://img.shields.io/badge/Powered%20by-Quarkus-red.svg)](https://quarkus.io/)
[![Build with Maven](https://img.shields.io/badge/Build-Maven-blue.svg)](https://maven.apache.org/)

---

## 🌐 Overview

**JMusic** is a decentralized, privacy-focused music streaming application built with **Java** and **Quarkus**.  
It provides a **serverless**, **peer-to-peer**, and **user-controlled** experience for managing and streaming your music.  

Unlike traditional streaming services, JMusic ensures that your data — from your music files to your listening preferences — **remains fully local and private**. It offers a responsive web interface combined with a high-performance backend for seamless playback, music organization, and peer-based sharing.

---

## ✨ Key Features

### 🛡️ **Decentralized Architecture & Privacy First**
- No central servers or cloud dependencies.  
- No telemetry, analytics, or background data collection.  
- Optional P2P sharing only between explicitly approved peers.  
- Shared data (if enabled) is strictly limited to torrent metadata and peer information.

### 💾 **Local Data Management**
- Your entire music library and metadata are stored locally.  
- Faster access, total privacy, and zero external dependencies.

### 💻 **Web-Based User Interface**
- Built with **HTML**, **CSS**, and **JavaScript**.  
- Fully responsive and accessible from any device with a modern web browser.

### 🎶 **Comprehensive Music Library**
- Organize your songs, edit metadata, and manage playlists.  
- Efficiently handles large music collections.  
- Uses [`jaudiotagger`](https://bitbucket.org/ijabz/jaudiotagger) for advanced audio metadata support.

### ⚡ **Real-Time Interactivity**
- Powered by **WebSockets** for instant updates on playback, playlists, and peer connections.

### 🔗 **Peer-to-Peer Music Sharing**
- Built around the [`bt-core`](https://github.com/atomashpolskiy/bt) BitTorrent engine.  
- Enables decentralized music discovery and distribution between users.  
- No intermediary servers or cloud platforms involved.

### 🔧 **REST API for Integrations**
- Based on **Quarkus REST** and **Jackson**.  
- Full programmatic control of your music library, playback, and peers.  
- Endpoints can be found under `src/main/java/API/Rest`.

### 🧠 **Efficient Data Management**
- Uses **Hibernate ORM with Panache** for simplified persistence.  
- Local **H2** database ensures fast and lightweight storage.

---

## ⚙️ Performance & Efficiency

JMusic is engineered for **maximum performance and minimal resource usage**, targeting **at least 50% greater efficiency** than conventional streaming platforms.  
This means:
- Reduced CPU and memory footprint  
- Faster response times  
- Negligible ecological impact  

---

## 🧰 Technology Stack

| Layer | Technology |
|-------|-------------|
| **Backend** | Java, Quarkus |
| **Frontend** | HTML, CSS, JavaScript |
| **Database** | H2 |
| **P2P Engine** | bt-core |
| **Real-Time Communication** | WebSockets |
| **ORM** | Hibernate with Panache |

---

## Features

JMusic is packed with music playback, library management, and peer-to-peer sharing features.  
For a full list of features, see the [Features Overview](https://github.com/monsterwhat/JMusic/blob/main/features.md).

---

## 🚀 Installation

### 🔹 **Prebuilt Executables**
Download the latest release from the [📦 GitHub Releases](https://github.com/monsterwhat/JMusic/releases) page:

- **Windows:**  
  Download `JMusic.exe` and run it directly.

- **Cross-Platform (JAR):**  
  Requires **Java 21+**.  
  ```bash
  java -jar JMusic-runner.jar
  ```
  > 💡 Tip: On most systems, you can double-click the `.jar` to launch it.

### 🔹 **Native Builds (Coming Soon)**
Native executables for **Linux**, **macOS**, and **Windows** are in development.  
These builds will run standalone without needing a separate Java installation.

---

## 🧑‍💻 Developer Setup

### Prerequisites
- **Java 21** or newer  
- **Maven 3.8+**

### Steps

1. **Clone the Repository**
   ```bash
   git clone https://github.com/monsterwhat/JMusic.git
   cd JMusic/com.playdeca.JMusicClient
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

---

## 🎧 Usage

Once the application is running, open your browser and visit:

```
http://localhost
```

From here, you can:
- Import and manage your local music library  
- Create and manage playlists  
- Stream and control playback  
- Optionally share music with approved peers  

---

## 📘 API Documentation

The REST API endpoints are located in:

```
src/main/java/API/Rest
```

These endpoints support operations for:
- Playback control  
- Library management  
- Peer discovery and sharing  
- Metadata operations  

---

## 🤝 Contributing

We welcome all contributions!  

To contribute:
1. Fork the repository  
2. Create a new branch for your feature or fix  
3. Make your changes and test thoroughly  
4. Submit a pull request with a clear summary of your changes  

> 🧭 Please ensure your code aligns with JMusic’s principles of **privacy**, **decentralization**, and **efficiency**.

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
- [bt-core](https://github.com/atomashpolskiy/bt) — Modern BitTorrent engine  
- [jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger) — Audio metadata tagging  
- The open-source community and all contributors  
