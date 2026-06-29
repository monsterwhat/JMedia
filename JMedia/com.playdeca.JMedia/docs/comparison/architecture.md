# JMedia — Architecture Deep-Dive

> **See also:** [feature-parity.md](feature-parity.md) for feature comparison | [gap-analysis.md](gap-analysis.md) for gap/advantage analysis | [marketing-comparison.md](marketing-comparison.md) for narrative overview
>
> This document explains **how** JMedia delivers the features in the parity table — from frontend player to database schema.

---

## Table of Contents

1. [High-Level Architecture](#high-level-architecture)
2. [Backend Stack](#backend-stack)
3. [Frontend Stack](#frontend-stack)
4. [Streaming Pipeline](#streaming-pipeline)
5. [Player Architecture](#player-architecture)
6. [Audio Analysis Pipeline](#audio-analysis-pipeline)
7. [Hardware Acceleration Detection](#hardware-acceleration-detection)
8. [Subtitle Extraction & Processing](#subtitle-extraction--processing)
9. [Download Engine](#download-engine)
10. [Video Conversion Pipeline](#video-conversion-pipeline)
11. [Scan System](#scan-system)
12. [Queue Processing Architecture](#queue-processing-architecture)
13. [Platform Operations](#platform-operations)
14. [Sync Exchange Protocol](#sync-exchange-protocol)
15. [Metadata Writing](#metadata-writing)
16. [Frontend SPA Architecture](#frontend-spa-architecture)
17. [Key Design Decisions](#key-design-decisions)
18. [Directory Structure](#directory-structure)

---

## High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                              Browser (Client)                                      │
│  ┌────────────────────────────────────────────────────────────────────────────┐   │
│  │  Frontend SPA (App.js — dual-subsystem architecture)                       │   │
│  │                                                                             │   │
│  │  ┌─────────────────────────────────────┐  ┌──────────────────────────────┐  │   │
│  │  │  MUSIC SUBSYSTEM (musicBar/)        │  │  VIDEO SUBSYSTEM (player/)   │  │   │
│  │  │                                     │  │                              │  │   │
│  │  │  Core:                              │  │  Core:                       │  │   │
│  │  │    AudioEngine (dual <audio> p1/p2) │  │    StreamManager             │  │   │
│  │  │    StateManager (40+ props)         │  │    SubtitleController        │  │   │
│  │  │    WebSocketManager (MusicSocket)   │  │    EventBinder               │  │   │
│  │  │    SynchronizationManager           │  │    ControlsManager           │  │   │
│  │  │    DjTransitionManager              │  │    SkipController            │  │   │
│  │  │                                     │  │    StoryboardManager         │  │   │
│  │  │  Controls:                          │  │    FullscreenManager         │  │   │
│  │  │    PlaybackController               │  │    NavigationManager         │  │   │
│  │  │    VolumeController                 │  │    ProgressReporter          │  │   │
│  │  │    TimeController                   │  │    KeyboardShortcuts         │  │   │
│  │  │                                     │  │                              │  │   │
│  │  │  Data:                              │  │  Players:                    │  │   │
│  │  │    StatePersistence                 │  │    JMedia Player             │  │   │
│  │  │    SongContextCache (30s TTL)       │  │    Video.js                  │  │   │
│  │  │    QueueManager                     │  │    OPlayer                   │  │   │
│  │  │                                     │  │                              │  │   │
│  │  │  Adapters:                          │  │  Adapter:                    │  │   │
│  │  │    DesktopAdapter (24 shortcuts)    │  │    videojs-adapter.js        │  │   │
│  │  │    MobileAdapter (gestures)         │  │    oplayer-adapter.js        │  │   │
│  │  │                                     │  │                              │  │   │
│  │  │  Cross-subsystem bridge:            │  │                              │  │   │
│  │  │    MusicBarInit (pauses music       │  │                              │  │   │
│  │  │    when video starts, sync loop)    │  │                              │  │   │
│  │  └─────────────────────────────────────┘  └──────────────────────────────┘  │   │
│  │                                                                             │   │
│  │  Views: Music | Video | Settings | Import (via App.js router)               │   │
│  └────────────────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────┬─────────────────────────────────────────────────┘
                                 │ HTTP / WebSocket / SSE
                                 ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                      Quarkus Server (Java 25)                                   │
│                                                                                 │
│  ┌──────────────┐  ┌──────────────────┐  ┌──────────────┐  ┌────────────────┐  │
│  │ REST API     │  │ WebSockets       │  │ HLS Server   │  │ Music REST API │  │
│  │ (JAX-RS)     │  │ (Log/Music/Video │  │ (Vert.x)     │  │ (PlaybackApi)  │  │
│  │              │  │ /ImportStatus)   │  │              │  │                │  │
│  └───────┬──────┘  └──────────────────┘  └──────┬───────┘  └───────┬────────┘  │
│          │                                      │                  │            │
│  ┌───────▼──────────────────────────────────────▼──────────────────▼─────────┐  │
│  │                         Service Layer                                     │  │
│  │  ┌─────────────┐ ┌─────────────┐ ┌───────────────┐ ┌────────────────┐    │  │
│  │  │Transcoding  │ │ FFmpeg      │ │ AudioAnalysis │ │ DjTransition  │    │  │
│  │  │Service      │ │Discovery    │ │ Service       │ │ Service       │    │  │
│  │  ├─────────────┤ ├─────────────┤ ├───────────────┤ ├────────────────┤    │  │
│  │  │Subtitle     │ │ Metadata    │ │ Download      │ │ MusicService   │    │  │
│  │  │Download     │ │WriteService │ │ Service       │ │                │    │  │
│  │  ├─────────────┤ ├─────────────┤ ├───────────────┤ ├────────────────┤    │  │
│  │  │ Parakeet    │ │AI Subtitle │ │ SyncService   │ │ PlaylistService│    │  │
│  │  │Service      │ │Job Service  │ │               │ │                │    │  │
│  │  └─────────────┘ └─────────────┘ └───────────────┘ └────────────────┘    │  │
│  └───────────────────────────────────────────────────────────────────────────┘  │
│                              │                                                   │
│  ┌───────────────────────────▼───────────────────────────────────────────────┐  │
│  │                 Hibernate ORM + H2 Database                              │  │
│  │  Settings / Media / Video / Audio / Album / Playlists / Users / Sessions │  │
│  │  WatchProgress / Sync / Activity / Ratings / AudioAnalysis (cached)      │  │
│  │  Flyway migrations (src/main/resources/db/migration/)                    │  │
│  └──────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────────┘
```

---

## Backend Stack

| Component | Technology | Why This |
|---|---|---|
| Framework | **Quarkus 3.34.1** | Fast boot, low memory, GraalVM native compilation |
| Language | **Java 25** | Latest LTE (Long-Term Support) |
| Database | **H2** | Zero-config, file-based, sufficient for personal libraries |
| ORM | **Hibernate ORM** | JPA standard, Flyway migrations, Panache for repositories |
| REST API | **JAX-RS (RESTEasy Reactive)** | Reactive, non-blocking endpoints |
| WebSocket | **Vert.x** | Event-loop concurrency, integrated with Quarkus |
| Scheduling | **Quartz** | Cron-based scheduled tasks (thumbnail generation, metadata refresh, sync) |
| Audio Analysis | **TarsosDSP** | FFT, onset detection, beat tracking |
| Metadata Writing | **JAudioTagger** | ID3v2, Vorbis, MP4 tag support |
| Media Processing | **FFmpeg/FFprobe** | Transcoding, analysis, subtitle extraction |
| Download | **yt-dlp / SpotDL** | YouTube and Spotify import |
| AI | **Parakeet (Whisper-based)** | Subtitle generation and translation |

---

## Frontend Stack

| Component | Technology |
|---|---|
| UI | **Custom vanilla JS** (no framework) — lightweight, no build step |
| Player 1 | **JMedia Player** (custom HTML5 video wrapper) |
| Player 2 | **Video.js** with plugins (chromecast, quality selector, hotkeys) |
| Player 3 | **OPlayer** (Oleksandr's player, PiP, Chromecast CAF, storyboard) |
| HEVC.js | **WebAssembly** client-side HEVC→H.264 decode |
| JASSUB | **WebAssembly** ASS/SSA subtitle renderer |
| Communication | **Fetch API** (REST) + **WebSocket** (real-time events) |

---

## Streaming Pipeline

```
Request → VideoPlaybackAPI → TranscodingService → FFmpegProcess → HLS Segments
                                                      │
                                                      ├── CPU only (software)
                                                      └── HW accelerated (NVENC/QSV/VAAPI/AMF/VideoToolbox)

Client → StreamManager → HEVC.js check → direct play? → HTML5 <video>
                                    └── hevc.js WASM (if HEVC + no native support)
                                         └── fallback → direct stream → retry

Quality switching at runtime:
  UIBuilder.js:137-141 → StreamManager.switchQuality(resolution) →
    → seeks to keyframe → re-initializes player with new rendition
```

### Key Details

- **HLS (HTTP Live Streaming)** — industry standard, native iOS/macOS support, widely supported in browsers via MediaSource Extensions
- **Segment caching** — `TranscodingService.cacheDir` reduces redundant transcodes
- **Seek modes** — direct play seeks within browser buffer; transcoded content seeks via server-side keyframe re-stream
- **Stream fallback chain** — `StreamManager.js:207-243`: hevc.js → direct stream → retry with exponential backoff
- **Stall detection** — `EventBinder.js:24-54`: 20s initial stall timer, `EventBinder.js:112-145`: 60s mid-playback stall timer, both trigger Toast notification and auto-retry
- **Audio track persistence** — `AudioTrackSelector.js:202-219`: saves per-video track preference to server API; `AudioTrackSelector.js:26-36`: restores from localStorage on reload
- **Server-side seek for transcoded content** — `StreamManager.js:385-458`: re-streams from nearest keyframe, preserves quality level and audio track selection
- **Client-side seek for direct play** — `StreamManager.js:394-399`: checks buffered range, seeks within buffer if available

### HLS Session Management

HLS streaming is managed through session-based resources at `API/Rest/HlsResource.java` with a dedicated `Services/HlsService.java`:

```
Client → POST /api/hls/session/{videoId} → HlsService.createSession → sessionId returned
       → GET  /api/hls/master/{sessionId}.m3u8 → master playlist
       → GET  /api/hls/playlist/{sessionId}/{variant}.m3u8 → variant playlist
       → GET  /api/hls/media/{sessionId}/{variant}/{segment} → video segment
```

**Session Parameters:** videoId, start position (seconds), profileId, audioTrackIndex, qualityHeight, deviceToken — all configurable at session creation.

**Segment Serving:** Polls for segment availability up to 15 seconds (100ms intervals), returns `SERVICE_UNAVAILABLE` if segment not ready. Init segments served via dedicated `/media/{sessionId}/{variant}/init.mp4` endpoint.

**Cleanup:** `DELETE /api/hls/session/{sessionId}` destroys the session and releases resources.

### Transcoding Parameters

FFmpeg invocation (simplified):
```bash
ffmpeg -i <input> -c:v libx264/h264_nvenc -preset fast \
  -c:a aac -ac 2 -ar 48000 \
  -f hls -hls_time 6 -hls_list_size 0 \
  -vf scale=1920:1080 \
  -start_number 0 -hls_segment_filename <segmentdir>/seg_%d.ts <m3u8>
```
- Codec, bitrate, resolution determined by quality selection
- HW encoder chosen from validated pool (NVENC → QSV → AMF → VAAPI → VideoToolbox → CPU)
- Subtitles burned in with `-vf subtitles=<subfile>` when subtitle styling is needed

---

## Player Architecture

The player module is a collection of modular JavaScript files under `src/main/resources/META-INF/resources/js/player/`:

```
js/player/
├── StreamManager.js        # HEVC.js detection, quality switching, stream lifecycle, fallback
├── SubtitleController.js   # ASS (JASSUB) + native subtitle rendering, track management
├── SubtitleSettingsUI.js   # Font/color/opacity/position controls, timing correction
├── AudioTrackSelector.js   # Multi-track audio selection + persistence
├── EventBinder.js          # All event handlers, stall detection, player switching
├── UIBuilder.js            # DOM construction, play/speed/quality controls, debug dialog
├── ControlsManager.js      # Auto-hide (3s), settings menu pages, debug panel
├── KeyboardShortcuts.js    # Space/K/J/L/F/M/D/arrows/0-9/N/P bindings
├── SkipController.js       # Auto-skip with undo, per-section toggles
├── StoryboardManager.js    # Thumbnail preview spritesheet hover
├── FullscreenManager.js    # Native + CSS fullscreen, iOS special handling
├── NavigationManager.js    # Back/details/prev/next episode navigation
├── ProgressReporter.js     # 5s progress reporting, tab-hide save, music suspend
├── player-adapter.js       # JMedia Player adapter
├── videojs-adapter.js      # Video.js integration (chromecast, quality selector)
├── oplayer-adapter.js      # OPlayer integration (PiP, seek buttons)
├── settings-ui.js          # Player settings UI rendering
└── subtitle-manager.js     # Subtitle file upload/management
```

### Key Interactions

| Event | Handler | Action |
|---|---|---|
| Player loaded | StreamManager → SubtitleController | Initialize tracks, restore prefs |
| Quality changed | UIBuilder → StreamManager.switchQuality | Re-stream at new resolution |
| Subtitle timing off | SubtitleSettingsUI | ±0.2s button, click-to-edit, reset, localStorage |
| SKip section hit | SkipController | Auto-skip with state tracking, undo support |
| Stall detected | EventBinder | 20s/60s timer → Toast → retry |
| Tab hidden | ProgressReporter | Save playback position, pause music |
| Keyboard press | KeyboardShortcuts | Map to player actions (Space/K=play, J/L=±10s) |
| Fullscreen toggle | FullscreenManager | Native or CSS fullscreen, iOS handling |
| Subtitle sync | SubtitleController:279-328 | Re-sync ASS/native subs on fullscreen transition |

### Player Types Compared

| Feature | JMedia Player | Video.js | OPlayer |
|---|---|---|---|
| HLS playback | ✅ | ✅ (plugin) | ✅ |
| Quality switching | ✅ | ✅ (plugin) | ✅ |
| Subtitles | ✅ | ✅ | ✅ |
| Chromecast | ❌ | ✅ | ✅ |
| AirPlay | ✅ (native) | ✅ (native) | ✅ (native) |
| Picture-in-Picture | ❌ | ❌ | ✅ |
| Storyboard | ❌ | ❌ | ✅ |
| Custom seek buttons | ❌ | ❌ | ✅ |
| Keyboard shortcuts | ✅ | ✅ (plugin) | ✅ |

---

## MusicBar Subsystem (Dual-Subsystem Architecture)

JMedia has a fundamentally different architecture from Plex/Jellyfin for music: a **fully separate music subsystem (musicBar)** that operates independently from the video player. While Plex has a single player engine and Jellyfin has a unified playback pipeline, JMedia runs two parallel client-side stacks:

### Separation of Concerns

| Aspect | Music Subsystem (musicBar/) | Video Subsystem (player/) |
|---|---|---|
| Audio engine | Dual `<audio>` elements (p1/p2) + Web Audio API gain nodes | Single `<video>` element |
| WebSocket | Dedicated MusicSocket | VideoSocket |
| State machine | StateManager (40+ props, SMART_SHUFFLE, CustomEvent) | Event-driven, no centralized state machine |
| DOM management | UIUpdater + EventBindings (dedicated musicBar DOM layer) | Native `<video>` + UIBuilder |
| Crossfade | Sine-curve crossfade via AudioEngine | N/A (gapless via HLS segments) |
| Conflict resolution | ActionTracker (3s local-action priority) | N/A (single-client assumed) |
| Device awareness | DeviceManager (per-device IDs, volume, clock offset) | N/A |
| State persistence | StatePersistence (pagehide/visibilitychange, 30s max-age) | ProgressReporter (5s interval + tab-hide) |
| Responsive adapters | DesktopAdapter + MobileAdapter + TabletAdapter | Responsive CSS + FullscreenManager |
| Keyboard shortcuts | 24 music-specific shortcuts (DesktopAdapter) | 10 general shortcuts (KeyboardShortcuts.js) |
| OS integration | MediaSession API (lock-screen controls) | N/A |

### musicBar Module Tree

```
js/musicBar/
├── core/
│   ├── AudioEngine.js          # Dual <audio> elements, Web Audio API crossfade, preload tracking
│   ├── StateManager.js          # Centralized state (40+ props), SMART_SHUFFLE mode, CustomEvent coordination
│   ├── WebSocketManager.js      # Auto-reconnect exponential backoff, message queuing, conflict resolution
│   ├── SynchronizationManager.js # Atomic locks, message queue with priority sorting, DOM op queuing via rAF
│   ├── DjTransitionManager.js   # Beat-aligned EternalJukebox transitions, exit/entry monitoring, UI indicators
│   └── DeviceManager.js         # Device ID generation, per-device volume, clock offset for multi-device sync
│
├── controls/
│   ├── PlaybackController.js    # Buffer warm (5s), play/pause/prev/next/shuffle/repeat with state-machine guards
│   ├── VolumeController.js      # Exponential scaling slider, per-device persistence via DeviceManager
│   └── TimeController.js        # Dynamic active-player binding for crossfade-aware seeks
│
├── data/
│   ├── StatePersistence.js      # pagehide + visibilitychange save, 30s max-age restore, periodic 30s auto-save
│   ├── SongContextCache.js       # 30s TTL cache for prev/current/next song
│   └── QueueManager.js          # Queue CRUD, change detection, length tracking
│
├── adapters/
│   ├── DesktopAdapter.js        # 24 keyboard shortcuts, mouse wheel volume, right-click menus, drag-to-playlist
│   ├── MobileAdapter.js         # Swipe/pull-to-refresh/long-press gestures, safe area insets, orientation handling
│   └── TabletAdapter.js         # Hybrid touch+keyboard layouts
│
├── utils/
│   ├── ActionTracker.js         # User-action vs WebSocket conflict resolution (3s timeout window)
│   ├── Helpers.js               # formatTime, throttle, debounce utilities
│   └── ImageManager.js          # Album art lazy loading, fallback, cache
│
└── ui/
    ├── UIUpdater.js             # DOM update orchestration for playback state, queue, metadata
    ├── EventBindings.js         # Bind/unbind DOM events with cleanup
    └── MobileBridge.js          # Mobile-specific UI adapters for song list, search, navigation
```

### Cross-Subsystem Communication

The two subsystems communicate via a bridge in `js/jmedia/MusicBarInit.js`:

```
MusicBarInit.js
     │
     ├── Module verification — confirms all musicBar modules loaded
     ├── Video detection — pauses music when video playback starts
     ├── Sync loop — periodic state sync between subsystems (uiState, queueState)
     ├── UI update loop — scheduled DOM updates for music playback state
     ├── Initialization sequence — AudioEngine → StateManager → WebSocket → adapters
     └── Error recovery — module-level try/catch, reports failures to ToastSystem
```

### State Flow

```
User Action (click/keyboard/gesture)
        │
        ▼
  PlaybackController (guards: alreadyPlaying? hasQueue?)
        │
        ▼
  StateManager (updates state: currentSong, isPlaying, mode)
        │
        ├──→ CustomEvent dispatched (stateChanged)
        │
        ├──→ WebSocketManager → MusicSocket → Server → other clients
        │
        ├──→ ActionTracker (records action, sets 3s timeout)
        │
        ├──→ AudioEngine (crossfade: p1→p2 or p2→p1, sine curve)
        │       │
        │       └──→ MediaSession API (update OS lock-screen)
        │
        ├──→ DJTransitionManager (if DJ Mode: check beat alignment, trigger timing)
        │
        ├──→ StatePersistence (async save to localStorage)
        │
        └──→ UIUpdater → DOM rendering (now playing, queue, metadata)
```

### Key Differentiators from Plex/Jellyfin

| Feature | JMedia musicBar | Plex (Plexamp) | Jellyfin |
|---|---|---|---|
| Audio element architecture | Dual `<audio>` + Web Audio API | Single element | Single element |
| Crossfade curve | Sine-based (non-linear, smooth) | Linear fade | None |
| Smart Shuffle | Algorithmic, persisted state | Basic random | Basic random |
| Multi-client conflict resolution | ActionTracker (3s window) | None | None |
| Per-device volume | Device ID + API persistence | Per-client only | Per-client only |
| State persistence on reload | 30s max-age, full restore | None | None |
| Desktop keyboard shortcuts | 24 music-specific | Basic | Basic |
| Mobile gestures | Swipe, long-press, pull-to-refresh | Native app only | Native app only |
| OS MediaSession controls | Full (play/pause/prev/next/seek) | Native app only | Native app only |

---

## Audio Analysis Pipeline

```
MediaScanner → AudioAnalysisService
                      │
              ┌───────┴────────┐
              ▼                 ▼
        TarsosDSP FFT     BeatRoot
              │                 │
              ▼                 ▼
     Spectral Features     Beat Detection
     (12 chroma buckets)   (median BPM)
              │                 │
              └───────┬─────────┘
                      ▼
            Similarity Graph
     (cross-cycle + relative matching)
                      │
                      ▼
              EternalJukebox Logic
     (non-repeating playback, joins within cycles)
                      │
                      ▼
              QueueManager + DjTransitionService
     (crossfade timing, beat alignment, queue items)
```

### Key Implementation Details

- **BPM Detection** (`AudioAnalysisService.java:234-238`): BeatRoot onset tracking → beat induction → median interval → BPM
- **Spectral Features** (`AudioAnalysisService.java:200-222`): FFT → 12 chroma-like buckets → cosine similarity between songs
- **Similarity Graph** (`AudioAnalysisService.java:352-409`): Same-position cross-cycle matching + relative-position matching within configurable tolerance
- **DJ Mode Proactive Pre-Analysis** (`AudioAnalysisService.java:533-554`): Ensures the next 5 songs are analyzed before they're needed; startup queue of 50 songs
- **BPM Metadata Write** (`MetadataWriteService.java`): Writes BPM to ID3v2 (TXXX:BPM), Vorbis (BPM), MP4 (©bm↵)

### Settings Controls

- `enableBpmExtraction` (Settings.java:78) — toggle
- `bpmTolerance` (Settings.java:66) — BPM matching tolerance
- `bpmToleranceOverrides` (Settings.java:67) — per-genre JSON overrides
- `djTransitionSections` (Settings.java:70) — number of analysis sections
- `djTriggerPercentage` (Settings.java:71) — when to trigger next transition
- `djCrossfadeSeconds` (Settings.java:72) — crossfade duration (0-10s)
- `enableBpmExtractionOnDjMode` (Settings.java:73) — DJ Mode toggle

---

## Hardware Acceleration Detection

```
FFmpegDiscoveryService
        │
        ├── Scan PATH + env.FFMPEG_PATH for ffmpeg.exe
        │
        ├── Invoke ffmpeg -hwaccels → parse output
        │
        ├── For each required codec (H.264/HEVC/VP9/AV1):
        │       │
        │       ├── Build decoder priority list:
        │       │   cuvid → videotoolbox → qsv → amf →
        │       │   d3d11va → dxva2 → mf → vaapi → v4l2m2m
        │       │
        │       ├── Test each decoder: ffmpeg -decoders | findstr <decoder>
        │       │
        │       └── Runtime probe: ffmpeg -v quiet -hwaccel <device> -i <test>
        │
        ├── Track encoder failures (FFmpegDiscoveryService.java:541-553):
        │   5 failures in 5 minutes → auto-invalidate, remove from pool
        │
        └── Expose via FFmpegDiscoveryService:
            getHardwareAccelerationEnabled()
            getEncoderPriorityList()
            getDecoderPriorityList()
```

### Per-Codec Fallback Chain

| Codec | Decoder Priority |
|---|---|
| H.264 | cuvid → videotoolbox → qsv → amf → d3d11va → dxva2 → mf → vaapi → v4l2m2m |
| HEVC | cuvid → videotoolbox → qsv → amf → d3d11va → dxva2 → mf → vaapi → v4l2m2m |
| VP9 | videotoolbox → cuvid → d3d11va → dxva2 → vaapi → v4l2m2m |
| AV1 | cuvid → d3d11va → dxva2 → vaapi → v4l2m2m |

### Encoder Priority

NVENC → VideoToolbox → QSV → AMF → VAAPI → V4L2M2M → CPU software fallback

### OS-Level GPU Detection

Separate from ffmpeg probing, `GpuDetectionService.java` detects GPUs at the operating system level:

```
GpuDetectionService (ApplicationScoped)
     │
     ├── GPU Vendor Detection:
     │   ├── Windows: wmic path win32_VideoController get name
     │   ├── Linux:   lspci | grep VGA + /sys/class/drm/ wildcards
     │   └── macOS:   system_profiler SPDisplaysDataType
     │
     ├── GPU Type Classification:
     │   ├── DISCRETE — NVIDIA GeForce/Quadro, AMD Radeon RX/Radeon Pro
     │   ├── INTEGRATED — Intel HD/UHD/Iris, AMD Radeon Vega (mobile)
     │   └── UNKNOWN — fallback
     │
     └── Returns GpuInfo(vendor, type, name, driverVersion)
```

**GpuVendor enum:** NVIDIA, INTEL, AMD, UNKNOWN
**GpuType enum:** DISCRETE, INTEGRATED, UNKNOWN

The service is auto-started via `@PostConstruct` and caches results to avoid repeated OS command execution. Results feed into hardware encoder/decoder selection logic alongside ffmpeg encoder probing.

---

## Subtitle Extraction & Processing

```
FFprobeSubtitleService
        │
        ├── Probe file with ffprobe → extract all subtitle streams
        │
        ├── Filter: text-based (SRT/ASS/SSA/VTT) vs image-based (PGS/DVD/VOBSUB)
        │   - Text: passed through for streaming, WebVTT conversion
        │   - Image: skipped for streaming (requires burn-in or OCR)
        │
        ├── ASS/SSA raw extraction (FFprobeSubtitleService.java:240-300):
        │   - Temp file → read → cleanup
        │   - Preserves all ASS styling (font, position, effects)
        │   - Delivered to browser for JASSUB rendering
        │
        ├── WebVTT on-the-fly conversion (FFprobeSubtitleService.java:176-234):
        │   - Any text-based format → ffmpeg pipe → WebVTT
        │
        ├── Flag detection: disposition.forced, disposition.hearing_impaired
        │
        └── 78-language mapping (iso639-2 → display name)
```

### Embedded Subtitle Support Matrix

| Format | Extracted | Streamed | Rendered |
|---|---|---|---|
| SRT | ✅ | ✅ (native/VTT) | Native |
| ASS/SSA | ✅ | ✅ (raw) | JASSUB/WASM |
| VTT | ✅ | ✅ | Native |
| PGS | ✅ (detected) | ❌ (image-based) | Burn-in only |
| DVD/VOBSUB | ✅ (detected) | ❌ (image-based) | Burn-in only |

---

## Download Engine

```
DownloadService (WebSocket-enabled)
     │
     ├── Source Selection:
     │   Primary: YouTube (yt-dlp)
     │   Secondary: Spotify (SpotDL)
     │
     ├── Source Switching (DownloadService.java:492-561):
     │   YouTube → SpotDL fallback on rate limit/failure
     │   Tracked: lastSkippedSong, searchHistory Map
     │
     ├── Retry Strategies (Settings.java:56):
     │   IMMEDIATELY — retry right away
     │   AFTER_FAILURES — retry after N subsequent failures
     │   ONLY_ON_RATE_LIMIT — retry only if rate-limited
     │   SMART_ADAPTIVE — dynamic based on failure pattern
     │
     ├── YouTube Advanced Options (DownloadService.java:654-694):
     │   IPv4/IPv6, custom User-Agent, browser impersonation
     │   Player client: android/tv/web_safari/web
     │   Cookies file support (import.html upload)
     │
     ├── Real-Time Monitoring:
     │   WebSocket broadcast (importStatusSocket) every 5s
     │   UI: progress bar, current step, speed, eta
     │
     ├── Cancellation:
     │   Process destroyForcibly + AtomicBoolean cancellation flag
     │
     └── Result Tracking (DownloadService.java:1094-1132):
         Downloaded files list, skipped songs, output cache
         Source attribution per song
```

---

## Video Conversion Pipeline

```
VideoConversionService (911 lines)
     │
     ├── Trigger: VideoManagementApi conversion endpoint
     │
     ├── Queue-based execution:
     │   ├── ConcurrentLinkedQueue + AtomicBoolean processing flag
     │   ├── Batch conversion from video management UI
     │   └── Per-item atomic temp → final file move
     │
     ├── HW Encoder Fallback Chain:
     │   NVENC → QSV → AMF → VAAPI → VideoToolbox → CPU
     │   (picks first working encoder, runtime-tested)
     │
     ├── Per-codec HW Decoder:
     │   H.264/HEVC/VP9/AV1 each probed independently
     │   Decoder-specific init_hw_device before fallback
     │
     ├── Subtitle Processing:
     │   ├── Probe with ffprobe for text vs image subs
     │   ├── Text-based subs (SRT/ASS/VTT) → mov_text (MP4 compatible)
     │   └── Image-based subs (PGS/DVD) → .sup extraction
     │
     ├── Audio Compatibility:
     │   ├── Remux incompatible audio tracks to AAC (libfdk_aac or aac)
     │   └── Preserves compatible audio tracks verbatim
     │
     ├── Safety:
     │   ├── Disk space check: input file size + 1GB buffer minimum
     │   ├── Atomic rename: temp file → original file path
     │   └── Old file deletion with retry on permission/access errors
     │
     └── Settings:
         Settings.java (enableVideoConversion toggle)
```

### Key Details

- **Conversion queue** — sequential per-video, prevents resource contention from parallel conversions
- **Fallback chain** — tries each HW encoder in priority order, falls through on failure, ends at CPU software encode
- **Subtitle awareness** — text subs get converted to mov_text for MP4 compatibility; image subs get extracted for separate use
- **Safety-first** — never deletes original until temp file is confirmed valid; atomic rename prevents partial files

---

## Scan System

JMedia has a 3-tier scan system that separates scanning from analysis and avoids CPU contention:

### 1. VideoScanExecutor

```
VideoScanExecutor
     │
     ├── ThreadPoolExecutor with CPU-aware core pool size
     │   (Runtime.getRuntime().availableProcessors() / 2, min 2)
     │
     ├── Background scanning with periodic progress reporting
     ├── Request-thread isolation — scans run asynchronously
     └── Change-detection: file size + modification date
         (skips files whose size+date unchanged since last scan)
```

### 2. Four Scan Modes (SettingsController.java)

| Mode | Description | Use Case |
|---|---|---|
| **FULL** | Complete re-scan of all libraries | First run, library rebuild |
| **INCREMENTAL** | Only new/changed files (size+date) | Daily refresh — fastest |
| **IMPORT** | Scan only recently imported files | After YouTube/Spotify download |
| **TARGETED** | Scan a specific path or single file | Debug, single-addition |

### 3. AnalysisWorker

```
AnalysisWorker
     │
     ├── Background analysis loop (10-second interval)
     ├── 2 songs per tick — sequential, no flooding
     │
     ├── CPU Contention Avoidance:
     │   ├── Checks if VideoConversionService is processing
     │   ├── If transcoding active → skips this tick
     │   └── Resumes when transcoding finishes
     │
     ├── Retry logic:
     │   └── Failed analyses retried after 5-minute cooldown
     │
     └── Startup: initializes on ApplicationStartup event
         (Quarkus @Observes StartupEvent)
```

### 4. UnifiedVideoEntityCreationService

The `UnifiedVideoEntityCreationService` (`Services/UnifiedVideoEntityCreationService.java`) is the final step in the scan pipeline — it creates or updates Video entities from the combined output of `MediaFile` discovery and `SmartNamingService.NamingResult`:

**Metadata preservation during full scans:** When `preserveMetadata=true` (full scan mode), the service backs up and restores the following fields before overwriting:
- Description, tagline, overview
- IMDb rating, Metacritic rating, MPAA rating
- Genres, cast, directors, writers
- External IDs (IMDb ID, TMDb ID, TMDb rating)
- Artwork paths (thumbnail, poster)

**Manual edit protection:** Respects `titleManuallyEdited` and `seriesTitleManuallyEdited` flags — never overwrites user-corrected titles during re-scans.

**Smart display resolution mapping:** `2160→"4K"`, `1440→"2K"`, `1080→"Full HD"`, `720→"HD"`, others→"SD"

**Container detection:** Extracts file extension to determine container format (mp4, mkv, avi, etc.)

**Season name parsing:** Handles multi-language season patterns — `"Libro 1 Agua"`, `"Season 2"`, `"Book 3 - Change"`, `"Temporada 4"` — all parsed to numeric season numbers.

**Show name normalization:** For merge detection — strips season/year patterns and normalizes for comparison (e.g., `"Archer (2009)"` → `"archer"`).

### Key Settings

- `enableBpmExtraction` (Settings.java:78) — enables BPM analysis during scan
- `scanMode` (Settings.java) — FULL/INCREMENTAL/IMPORT/TARGETED
- Background scan threads auto-sized to CPU cores

---

## Queue Processing Architecture

JMedia uses a centralized queue processor pattern for background work that must not block request threads. All four queue processors share a common architecture:

### Shared Pattern

```
BlockingQueue<Long>                 Thread-safe Video ID queue
       │
       ▼
ExecutorService (daemon thread)     Single or fixed-thread pool
       │
       ▼
Poll loop (5s timeout)              while(isRunning) { queue.poll(5s) }
       │
       ▼
Processing with retry               Up to 2-3 attempts, exponential backoff
       │
       ▼
Graceful shutdown                   awaitTermination(5-10s) → shutdownNow()
```

### Lifecycle

All processors are `@ApplicationScoped` and follow:
- `@PostConstruct init()` → calls `start()` → creates executor → submits poll loop
- `@PreDestroy destroy()` → calls `stop()` → shuts down executor gracefully
- `isRunning` AtomicBoolean ensures clean start/stop transitions

### 1. MetadataQueueProcessor (Services/MetadataQueueProcessor.java)

Background metadata enrichment that prevents API rate limiting from blocking the main thread:

- **Rate limiting:** 500ms delay between TMDb calls, 250ms between OMDb calls
- **Retry:** Up to 2 retries with exponential backoff (1s, 2s)
- **Skip condition:** Skips videos that already have a TMDb ID (already enriched)
- **Poll timeout:** 5 seconds before looping
- **Startup:** Auto-starts on application boot via `@PostConstruct`

### 2. RenameQueueProcessor (Services/RenameQueueProcessor.java)

Background asset standardization service:

- **Queue:** All videos queued on startup for initial standardization pass
- **Processing:** Renames thumbnail and storyboard assets to canonical format via `MediaPathResolver.resolveThumbnailName()`
- **Idempotent:** Skips videos already using canonical names
- **Scheduled task:** `@Scheduled(cron = "0 0 3 * * ?")` — daily 3am re-standardization pass
- **Metrics:** Tracks queuedCount, processedCount, queue size, busy state via AtomicInteger
- **API integration:** `StandardizationApi.java` triggers/via REST

### 3. ThumbnailQueueProcessor (Services/Thumbnail/ThumbnailQueueProcessor.java)

Background thumbnail generation queue:

- Queues video IDs for thumbnail processing
- Integrates with `ThumbnailService` and `VideoStoryboardService`
- Same lifecycle and pattern as other queue processors

### 4. SubtitleDiscoveryQueueProcessor (Services/SubtitleDiscoveryQueueProcessor.java)

Background subtitle discovery for episodes without existing subtitle tracks:

- **Thread pool:** 2 fixed threads (newFixedThreadPool(2))
- **Delay:** 500ms between processing items
- **Retry:** Up to 2 retries with exponential backoff
- **Skip condition:** Skips videos that already have subtitle tracks
- **Subtitle matching:** Uses `EnhancedSubtitleMatcher.discoverSubtitleTracks()` with 20+ language scoring
- **Queue on startup:** `queueAllVideos()` — queues all active episodes without subtitles

---

## Platform Operations

JMedia includes per-operating-system platform operations for dependency management, command execution, and system administration:

### Architecture

```
PlatformOperations (interface)
     │
     ├── WindowsPlatformOperations
     │   ├── Package manager: Chocolatey (choco)
     │   ├── Python: choco install python / manual download
     │   ├── Node.js: choco install nodejs
     │   ├── FFmpeg: choco install ffmpeg
     │   └── SpotDL/yt-dlp/Parakeet: pip install
     │
     ├── LinuxPlatformOperations
     │   ├── Package manager: apt (Debian/Ubuntu), dnf (Fedora/RHEL)
     │   ├── Python: apt install python3 / dnf install python3
     │   ├── Node.js: apt install nodejs / nvm
     │   ├── FFmpeg: apt install ffmpeg / dnf install ffmpeg
     │   └── SpotDL/yt-dlp/Parakeet: pip install
     │
     └── MacOSPlatformOperations
         ├── Package manager: Homebrew (brew), MacPorts (port)
         ├── Python: brew install python / port install python3
         ├── Node.js: brew install node / port install nodejs
         ├── FFmpeg: brew install ffmpeg / port install ffmpeg
         └── SpotDL/yt-dlp/Parakeet: pip install (or brew install yt-dlp)
```

### Capabilities

| Operation | Description |
|---|---|
| **Package manager detection** | Auto-detects available package managers (brew/port/choco/apt/dnf) |
| **Dependency installation** | Installs Python, Node.js, FFmpeg, SpotDL, yt-dlp, Parakeet with OS-appropriate commands |
| **Dependency uninstallation** | Cleanly removes each dependency |
| **Executable detection** | Checks if a command is available on PATH |
| **Admin command execution** | Runs privileged commands with real-time status broadcasting via WebSocket |
| **Python executable resolution** | Tries multiple variants (python3, python, py) to find a working Python |
| **Cookie file management** | Stores and manages cookies for YouTube download authentication |

All platform implementations broadcast installation progress to `ImportStatusSocket` for real-time UI updates (progress bars, step descriptions, completion/failure messages).

---

## Sync Exchange Protocol

JMedia's server-to-server sync uses a request/response exchange protocol with conflict resolution:

### Architecture

```
RemoteJMediaClient (HTTP client)                 SyncExchangeAPI (REST endpoint)
     │                                                    │
     ├── GET /api/sync/ping                               ├── Validates X-JMedia-Sync-Key
     │   (connection test, 5s timeout)                    │   against local Settings.syncApiKey
     │                                                    │
     └── POST /api/sync/exchange                          └── Processes SyncExchangeRequest
         (SyncExchangeRequest JSON)                            Returns SyncExchangeResponse
              │                                                    │
              ├── song metadata (title, artist, album, genre,     ├── updatedIds — MusicBrainz IDs updated
              │    BPM, lyrics, explicit, duration)                │   on local server
              │                                                    │
              ├── beat analysis (beatTimes, segmentFeatures,      ├── songs — local data for songs newer
              │    similarBeats, beatMetadata, averageBpm)         │   than remote versions
              │                                                    │
              └── timestamps (updatedAt for conflict               └── errors — processing errors
                   resolution — newer wins)
```

### Conflict Resolution

| Condition | Action |
|---|---|
| Remote song newer (`remote.updatedAt > local.updatedAt`) | Update local song from remote data |
| Local song newer (`local.updatedAt > remote.updatedAt`) | Return local data in response so remote can update |
| Song not found locally | Skip (sync only shared content) |
| Missing MusicBrainz ID | Skip (unidentified songs cannot be matched) |

### Data Exclusions

- **Artwork:** Explicitly excluded from sync (`artworkBase64 = null`) — receiver regenerates artwork from the audio file
- **Video:** Currently music-only; video sync framework exists but not fully populated

### Client Implementation

`RemoteJMediaClient` (`Services/RemoteJMediaClient.java`):
- **Connect timeout:** 10 seconds
- **Read timeout:** 60 seconds
- **Error handling:** Distinguishes 401 (auth), 404 (version mismatch), and 5xx (server error) — with descriptive exception messages
- **Connection test:** `checkConnection()` returns true/false with detailed logging of failure modes (UnknownHost, timeout, connection refused, SSL error)

---

## Metadata Writing

```
MetadataWriteService
     │
     ├── Supported formats:
     │   MP3 (ID3v2 tags via JAudioTagger)
     │   FLAC (Vorbis comments)
     │   M4A (MP4 tags)
     │   OGG (Vorbis comments)
     │   WAV (INFO chunk)
     │
     ├── Written fields:
     │   Album, Artist, Title, Genre, Year, Track Number
     │   Album Artist, BPM (TXXX:BPM / BPM / ©bm↵)
     │   Artwork (embedded cover art)
     │
     ├── Safety:
     │   Backup original file before writing
     │   Restore on write failure
     │
     └── Custom marker:
         JMedia application marker: "v1.1.0" in file tags
```

---

## Frontend SPA Architecture

```
App.js — Single Page Application Router
     │
     ├── 4 Views:
     │   /music     → MusicView (player, queue, library)
     │   /video     → VideoView (player, library)
     │   /settings  → SettingsView (admin panels)
     │   /import    → ImportView (download progress)
     │
     ├── Routing:
     │   URL-based (hashless via history.pushState/popstate)
     │   View switching with history preservation
     │
     ├── State Management:
     │   QueueManager — in-memory queue + DOM rendering
     │   AudioState — persistent audio playback across views
     │   VideoState — single-instance video player
     │
     ├── Admin UI:
     │   Admin-only elements toggled via App.js:28-40
     │   Non-admins see only music/video views
     │
     └── Sidebar:
         Position preference (left/right) API-backed
         Settings.java + App.js:42-58
```

### Queue Manager

```
QueueManager.js
     ├── Queue CRUD: load, skip-to, remove, clear
     ├── Search/filter within queue
     ├── Current song highlighting
     ├── Mobile-responsive layout
     └── Integration with AudioAnalysisService for DJ Mode sequencing
```

---

## Key Design Decisions

### Why H2 instead of PostgreSQL?

- **Zero-config** — no database server to install
- **File-based** — backup = copy the `.mv.db` file
- **Sufficient scale** — handles personal libraries (10K-50K items) easily
- **Migration path** — Quarkus config: `quarkus.datasource.db-kind=postgresql` + connection URL change

### Why Custom JS instead of React/Vue?

- **Zero build step** — no npm, webpack, TypeScript compilation
- **Lightweight** — `< 500KB` total JS, no framework overhead
- **Direct control** — no virtual DOM diffing overhead for video playback
- **Hot-reload** — save JS file → refresh browser, instant iteration

### Why HLS instead of DASH?

- **Native iOS/macOS** — HLS is built into Safari/WebKit
- **Universal browser support** — MediaSource Extensions handle HLS everywhere
- **Simpler implementation** — FFmpeg outputs HLS natively; DASH requires MP4Box or similar
- **Trade-off accepted** — DASH excluded, but HLS covers all target platforms

### Why Three Players?

- **No single player handles all edge cases**
- **Video.js** — mature, chromecast, broad format support
- **OPlayer** — PiP, storyboards, unique UI features
- **JMedia Player** — custom fallback, fine-grained control
- **Runtime switching** — no restart required (settings.html → reload page)

### Auth Filter Architecture

JMedia's authentication is handled by a single servlet filter (`JMediaAuthFilter.java`) that supports 3-tier access:

```
JMediaAuthFilter (javax.servlet.Filter)
     │
     ├── 3-tier Access Control:
     │   ├── PUBLIC — endpoints accessible without authentication
     │   │   (login, setup, health, any unauthenticated page)
     │   │
     │   ├── SESSION — requires valid JMEDIA_SESSION cookie
     │   │   Validated against Session table (user, expiry, IP)
     │   │
     │   └── STREAMING BYPASS — video/audio segments skip session
     │       Rationale: browser <video> elements cannot attach
     │       auth headers or handle 401 responses gracefully
     │
     ├── Public Endpoint Whitelist:
     │   /login*, /setup*, /api/health
     │   /api/external/embed-player, /api/subtitle*
     │   /api/video/playback*, /stream*, /api/download*
     │
     ├── Sync API Authentication:
     │   X-Sync-Key header validated against SyncServer record
     │   (separate from session cookie — headless server-to-server)
     │
     ├── Rate Limiting:
     │   ├── IP-based tracking via ConcurrentHashMap<String, RateLimitEntry>
     │   ├── Login endpoint: N attempts per minute per IP
     │   └── Returns 429 Too Many Requests with Retry-After header
     │
     ├── Redirect Logic:
     │   ├── Unauthenticated browser requests → 302 redirect to /login
     │   └── Unauthenticated XHR/API requests → 401 Unauthorized
     │
     └── Cookie: JMEDIA_SESSION
         HttpOnly, Secure (if HTTPS), path=/, configurable expiry
```

#### EnhancedAuthAPI (REST Layer)

The `EnhancedAuthAPI` (`API/Rest/EnhancedAuthAPI.java`) provides the REST interface for authentication:

| Endpoint | Method | Description |
|---|---|---|
| `/api/auth/login` | POST | Rate-limited login with IP tracking, local network detection, secure cookie generation |
| `/api/auth/logout` | POST | Session invalidation + Clear-Site-Data header |
| `/api/auth/status` | GET | Session validation — returns session info or "No active session" |
| `/api/auth/current-user` | GET | Returns logged-in status, username, admin flag, group name |
| `/api/auth/is-admin` | GET | Returns admin status |

#### RateLimitService

The `RateLimitService` (`Services/RateLimitService.java`) provides IP-based rate limiting for login attempts:

- **Tracking:** ConcurrentHashMap<String, RateLimitEntry> — keyed by IP address
- **Block condition:** N failed attempts within the window → block until window expires
- **Clear on success:** Recorded successful login clears the attempt counter
- **Response:** HTTP 429 Too Many Requests with empty entity on block

#### Cookie Security

- **JMEDIA_SESSION cookie:** HttpOnly, SameSite=LAX, 7-day max age (604800s)
- **Secure flag:** Applied only for non-local network requests (detected via `isLocalNetwork()` which checks for `192.168.100.*`, `10.50.0.*`, and `127.0.0.1`)
- **Logout:** Sets `Clear-Site-Data: "cache", "cookies", "storage", "executionContexts"` header to wipe client-side state

### Certificate Service

JMedia can generate a self-signed HTTPS certificate automatically — removing the need for a reverse proxy:

```
CertificateService
     │
     ├── Trigger: Setup wizard (first run) or settings page
     │
     ├── Process:
     │   ├── Executes: keytool -genkeypair -alias jmedia
     │   ├── 10-year validity period
     │   ├── SAN: localhost (prevents browser security warnings)
     │   └── Auto-applies keystore path to application.properties
     │
     ├── Purpose:
     │   Single-user local installs get TLS without nginx/caddy
     │
     └── Requirements:
         Java runtime (keytool bundled with JDK/JRE)
         No external dependencies
```

---

## Directory Structure

```
src/main/java/
├── Models/           # JPA entities (Media, Video, Audio, Album, etc.)
├── Services/         # Business logic (Transcoding, FFmpegDiscovery, AudioAnalysis, etc.)
├── Controllers/      # MVC controllers (Admin, Setup)
├── API/
│   ├── Rest/         # REST endpoints (VideoPlayback, Subtitle, Metadata, etc.)
│   └── WS/           # WebSocket endpoints (Log, Music, Video, ImportStatus)
├── Jobs/             # Quartz scheduled jobs (Thumbnail, MetadataRefresh, Sync)
└── Resources/        # i18n, configuration

src/main/resources/
├── META-INF/resources/
│   ├── js/
│   │   ├── musicBar/ # Music subsystem (see MusicBar Subsystem)
│   │   │   ├── core/        # AudioEngine, StateManager, WebSocketManager, etc.
│   │   │   ├── controls/    # PlaybackController, VolumeController, TimeController
│   │   │   ├── data/        # StatePersistence, SongContextCache, QueueManager
│   │   │   ├── adapters/    # DesktopAdapter, MobileAdapter, TabletAdapter
│   │   │   ├── utils/       # ActionTracker, Helpers, ImageManager
│   │   │   └── ui/          # UIUpdater, EventBindings, MobileBridge
│   │   ├── player/   # Video player modules (see Player Architecture)
│   │   └── jmedia/   # SPA router, App.js, bridge files (MusicBarInit, MediaSession, etc.)
│   ├── css/          # Stylesheets
│   └── images/       # UI assets
├── templates/        # Qute templates (video, music, settings, import fragments)
└── db/migration/     # Flyway SQL migrations
```




