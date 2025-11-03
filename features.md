# 🎵 JMusic — Feature Overview

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
- [Data & Diagnostics](#data--diagnostics)
- [Discover](#discover)
  - [Torrent Management](#torrent-management)
  - [Networking](#networking-inside-discover)
- [App Behavior & Customization](#app-behavior--customization)
- [Summary](#summary)

---

## 🧭 Navigation & Layout

| Feature | Description | Platform | Version / Planned Phase | Status |
|----------|--------------|-----------|------------------------|---------|
| Home View | Displays playlists, song queue, and songs based on selection. | Desktop / Mobile | Alpha 5 | ✅ |
| Discover | Main hub for finding content and managing P2P features (formerly “Browse”). | Desktop / Mobile | Alpha 5 | ✅ |
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
| Run as Service Toggle | Runs JMusic as a background service (does not auto-start). | Desktop / Mobile | Alpha 5 | ✅ |
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

---

## 🧠 Data & Diagnostics

| Feature | Description | Platform | Version / Planned Phase | Status |
|----------|--------------|-----------|------------------------|---------|
| Clear Songs Database | Deletes all songs in the database. | Desktop / Mobile | Alpha 5 | ✅ |
| Clear Playback History | Deletes playback history. | Desktop / Mobile | Alpha 5 | ✅ |
| View Logs | View system and playback logs. | Desktop / Mobile | Alpha 5 | ✅ |
| Clear Logs | Remove all application logs. | Desktop / Mobile | Alpha 5 | ✅ |

---

## 🌐 Discover

### Torrent Management

| Feature | Description | Platform | Version / Planned Phase | Status |
|----------|--------------|-----------|------------------------|---------|
| Torrent Browsing Toggle | Enables torrent mode with legal/safety warning. | Desktop / Mobile | Alpha 5 | ✅ |
| Create / Update User Key | Generate local private/public key pair for authentication. | Desktop / Mobile | Alpha 5 | 🚧 |
| Share Code / QR Code | Send your public key to peers. | Desktop / Mobile | Alpha 5 | 🚧 |
| Add Code / QR Code | Add peer via public key / QR code. | Desktop / Mobile | Alpha 5 | 🚧 |
| Rescan Network | Refresh peer/torrent network. | Desktop / Mobile | Alpha 5 | 🚧 |
| Auto Peer Sharing | Toggle to auto-share known peers. | Desktop / Mobile | Alpha 5 | ⚙️ |
| Auto Torrent Sharing | Toggle to auto-share torrents with peers. | Desktop / Mobile | Alpha 5 | ⚙️ |
| Scan Torrents | Collect and verify torrents from connected peers. | Desktop / Mobile | Before Beta | 🧩 |
| Torrent Metadata Verification | Check embedded metadata to verify torrents created within network. | Desktop / Mobile | Before Beta | 🧩 |
| Flags System | Users can flag torrents or peers; flags propagate across peers. | Desktop / Mobile | Before Beta | 🧩 |
| Ban Notices | Users can ban peers locally; ban notice propagates across peers. | Desktop / Mobile | Before Beta | 🧩 |
| Likes / Dislikes | Peer-to-peer voting system for torrents. | Desktop / Mobile | Before Beta | 🧩 |
| Automatic Verification of Torrents | Validate torrents against metadata and hash. | Desktop / Mobile | Before Beta | 🧩 |

### Networking (inside Discover)

| Feature | Description | Platform | Planned Phase | Status |
|----------|--------------|-----------|----------------|---------|
| Scan Peers | Actively scan for new peers. | Desktop / Mobile | Before Beta | 🧩 |
| Peer List Visualization | Shows connected peers and status. | Desktop / Mobile | Before Beta | 🧩 |
| Peer Health / Status | Show last online, availability, shared torrent count. | Desktop / Mobile | Before Beta | 🧩 |
| Search Across Peers | Search for songs/torrents across connected peers. | Desktop / Mobile | Before Beta | 🧩 |

---

## ⚙️ App Behavior & Customization

| Feature | Description | Platform | Version / Planned Phase | Status |
|----------|--------------|-----------|------------------------|---------|
| Manual Startup (Service Mode) | App must be manually launched, even in service mode. | Desktop / Mobile | Alpha 5 | ✅ |
| Tray Icon Integration | Visible when running as background service. | Desktop / Mobile | Alpha 5 | ✅ |

---
 