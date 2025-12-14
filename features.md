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
- [Interface & Playback](#interface--playback)
- [Library & Metadata](#library--metadata)
  - [Library Configuration](#library-configuration)
  - [Library Maintenance](#library-maintenance)
  - [Music Import](#music-import)
- [Data & Diagnostics](#data--diagnostics)
- [App Behavior & Customization](#app-behavior--customization)
- [Summary](#summary)

---

## 🧭 Navigation & Layout

| Feature | Description | Platform | Version / Planned Phase | Status |
|----------|--------------|-----------|------------------------|---------|
| Home View | Displays playlists, song queue, and songs based on selection. | Desktop / Mobile | Alpha 5 | ✅ |
| Discover | Main hub for finding content. | Desktop / Mobile | Alpha 5 | ✅ |
| Settings Tab | Central hub for configuration, library, and app behavior. | Desktop / Mobile | Alpha 5 | ✅ |
| Responsive Layout | Fully responsive design supporting desktop and mobile UI. | Desktop / Mobile | Alpha 5 | ✅ |
| Light & Dark Mode | Toggle between light and dark themes. | Desktop / Mobile | Alpha 5 | ✅ |

---

## 🎛 Interface & Playback

| Feature | Description | Platform | Version / Planned Phase | Status |
|----------|--------------|-----------|------------------------|---------|
| Playback Bar | Displays song info, artist, album art, duration. | Desktop / Mobile | Alpha 5 | ✅ |
| Playback Controls | Play, pause, next, previous, shuffle, repeat. | Desktop / Mobile | Alpha 5 | ✅ |
| Song Queue | View current queue, skip to song, remove, or clear queue. | Desktop / Mobile | Alpha 5 | ✅ |
| Song List Actions | Play or add to playlist; remove from playlist if viewing a playlist. | Desktop / Mobile | Alpha 5 | ✅ |
| Play Queue Persistence | Queue is saved between sessions. | Desktop / Mobile | Alpha 5 | ✅ |
| Now Playing / Expanded Player | Fullscreen or focused playback view. | Desktop / Mobile | Before Beta | 🧩 |
| Mini Player Mode | Floating or compact view of playback controls. | Desktop / Mobile | Before Beta | 🧩 |
| Search & Filter | Search songs by title, artist, album, or metadata. | Desktop / Mobile | Before Beta | 🧩 |
| Sort Options | Sort by artist, album, duration, or play count. | Desktop / Mobile | Before Beta | 🧩 |
| Smart Playlists | Auto-generate playlists like “Most Played” or “Recently Added.” | Desktop / Mobile | Before Main Release | 🧩 |
| Playback History View | View playback history directly in the UI. | Desktop / Mobile | Before Beta | 🧩 |
| Recently Added / Recently Played | Dynamic playlists for convenience. | Desktop / Mobile | Before Beta | 🧩 |
| Favorites / Liked Songs | Users can mark songs as favorites. | Desktop / Mobile | Before Beta | 🧩 |

---

## 🎶 Library & Metadata

### Library Configuration

| Feature | Description | Platform | Version / Planned Phase | Status |
|----------|--------------|-----------|------------------------|---------|
| Music Folder Path | Display and change music library path. | Desktop / Mobile | Alpha 5 | ✅ |
| Save Path Button | Save a new library path and clear old one. | Desktop / Mobile | Alpha 5 | ✅ |
| Reset to Default Path | Reset library to default folder. | Desktop / Mobile | Alpha 5 | ✅ |
| Run as Service Toggle | Runs JMedia as a background service (does not auto-start). | Desktop / Mobile | Alpha 5 | ✅ |
| Multiple Library Support | Allow user to add/manage multiple music folders. | Desktop / Mobile | Before Beta | 🧩 |

### Library Maintenance

| Feature | Description | Platform | Version / Planned Phase | Status |
|----------|--------------|-----------|------------------------|---------|
| Scan Library | Scans current folder for music files. | Desktop / Mobile | Alpha 5 | ✅ |
| Reload Metadata | Reload all metadata for existing songs. | Desktop / Mobile | Alpha 5 | ✅ |
| Delete Duplicates | Detect and remove duplicate songs. | Desktop / Mobile | Alpha 5 | ✅ |
| Metadata Extraction | Extracts title, artist, album art, and duration from file metadata. | Desktop / Mobile | Alpha 5 | ✅ |
| Backup Library | Export music library database and settings. | Desktop / Mobile | Before Beta | 🧩 |
| Restore Library | Import a previously exported library backup. | Desktop / Mobile | Before Beta | 🧩 |

### Music Import

| Feature | Description | Platform | Version / Planned Phase | Status |
|----------|--------------|-----------|------------------------|---------|
| Spot-dl Integration | Import music directly from Spotify using Spot-dl. | Desktop / Mobile | Before Beta | ⚙️ |
| yt-dlp Integration | Import music from YouTube and other video platforms using yt-dlp. | Desktop / Mobile | Before Beta | ⚙️ |

---

## 🧠 Data & Diagnostics

| Feature | Description | Platform | Version / Planned Phase | Status |
|----------|--------------|-----------|------------------------|---------|
| Clear Songs Database | Deletes all songs in the database. | Desktop / Mobile | Alpha 5 | ✅ |
| Clear Playback History | Deletes playback history. | Desktop / Mobile | Alpha 5 | ✅ |
| View Logs | View system and playback logs. | Desktop / Mobile | Alpha 5 | ✅ |
| Clear Logs | Remove all application logs. | Desktop / Mobile | Alpha 5 | ✅ |



---

## ⚙️ App Behavior & Customization

| Feature | Description | Platform | Version / Planned Phase | Status |
|----------|--------------|-----------|------------------------|---------|
| Manual Startup (Service Mode) | App must be manually launched, even in service mode. | Desktop / Mobile | Alpha 5 | ✅ |
| Tray Icon Integration | Visible when running as background service. | Desktop / Mobile | Alpha 5 | ✅ |

---
 