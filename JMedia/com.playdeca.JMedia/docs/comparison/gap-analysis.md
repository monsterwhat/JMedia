# JMedia — Gap Analysis vs Plex / Jellyfin

> **See also:** [feature-parity.md](feature-parity.md) for full feature table | [marketing-comparison.md](marketing-comparison.md) for narrative | [architecture.md](architecture.md) for system design
>
> **Goal:** Honest self-assessment — what JMedia lacks, what it does better, and where it breaks even.

---

## Table of Contents

1. [Gaps — Plex/Jellyfin features JMedia lacks](#gaps--plexjellyfin-features-jmedia-lacks)
2. [Non-Gaps — Features JMedia has that are often assumed missing](#non-gaps--features-jmedia-has-that-are-often-assumed-missing)
3. [Unique Advantages — What JMedia does that Plex/Jellyfin cannot](#unique-advantages--what-jmedia-does-that-plexjellyfin-cannot)
4. [Risk Factors](#risk-factors)

---

## Gaps — Plex/Jellyfin features JMedia lacks

### Structural Gaps

| Gap | Impact | Context |
|---|---|---|
| **No native mobile apps** | Medium | Plex/Jellyfin have Android/iOS apps; JMedia is browser-only. However, the responsive web UI works on mobile browsers and supports AirPlay for Apple TV. |
| **No Smart TV / console apps** | Medium | Plex targets LG webOS, Tizen (Samsung), Roku, PS, Xbox. Jellyfin has Roku app. JMedia is browser-only, which works on TV browsers but lacks native remote control. |
| **No native desktop app** | Low | Plex has desktop app; Jellyfin has Jellyfin Media Player (Electron). JMedia is browser-only, but the web UI is fully responsive. |
| **No formal plugin/extension system** | Medium | Jellyfin has 30+ plugins; Plex has limited plugins. JMedia has no plugin API — customization requires forking the open-source repo. |
| **No DVR / time-shift recording** | Medium | Plex DVR (Plex Pass) and Jellyfin Live TV both support recording. JMedia has no DVR capability. |
| **No LDAP / OAuth / SSO** | Low | JMedia targets offline/local use where user management is simple. Plex and Jellyfin support enterprise auth. |
| **No DASH streaming** | Low | HLS-only for adaptive streaming, which covers all modern browsers. DASH is useful for some smart TVs. |
| **No password-less / PIN login** | Low | Plex has PIN login for kids. JMedia uses form-based auth only. |

### Polish & Ecosystem Gaps

| Gap | Impact | Context |
|---|---|---|
| **No native intro detection** | Medium | JMedia relies on external IntroDB API (internet required). Plex (Plex Pass) and Jellyfin (plugin) have local skip-intro detection. |
| **No HDR metadata passthrough** | Low | HDR tone mapping works via ffmpeg tonemap, but metadata stripping may occur. Plex/Jellyfin have refined HDR handling. |
| **No remote access / tunneling** | Low | JMedia is designed for LAN; Plex Relay, Jellyfin auto-TLS, and Zerotier provide WAN access. JMedia would need reverse proxy for WAN. |
| **No Tidal/Qobuz integration** | Low | Plex integrates Tidal. JMedia downloads from YouTube/Spotify instead. |
| **No recommendation engine** | Low | Plex has "Watch Together" and recommendations. JMedia has no ML-based recommendations. |
| **No live TV transcoding** | Low | JMedia handles IPTV streams but lacks real-time TV transcoding. |
| **Smaller community** | Low | Plex forums: 500K+; Jellyfin: 20K+ GitHub stars. JMedia is a single-developer project. |

---

## Non-Gaps — Features JMedia has that are often assumed missing

Many assume a small project like JMedia lacks advanced features. Here is what **is already built**:

| Assumed Missing | ✅ Actually Present | Source |
|---|---|---|
| Hardware transcoding (NVENC, QSV, VAAPI, AMF, VideoToolbox) | ✅ Full HW acceleration support | FFmpegDiscoveryService.java |
| **Per-codec HW decoder detection (H.264, HEVC, VP9, AV1)** | ✅ Runtime probe per codec | FFmpegDiscoveryService.java:424-483 |
| **Runtime HW acceleration validation** | ✅ Tests if HW device is actually usable | FFmpegDiscoveryService.java:380-413 |
| **Encoder failure tracking + auto-invalidation** | ✅ 5 failures in 5 min → removed from pool | FFmpegDiscoveryService.java:541-553 |
| Subtitle styling | ✅ Font size, color, opacity, position | SubtitleSettingsUI.js |
| ASS subtitle rendering | ✅ Via JASSUB WebAssembly | SubtitleController.js |
| **Subtitle timing correction** | ✅ ±0.2s button, click-to-edit, reset, localStorage | SubtitleSettingsUI.js:48-68 |
| AI subtitle generation | ✅ Parakeet service, 27 languages | ParakeetService.java |
| AI subtitle translation | ✅ 27 target languages | settings.html AI tab |
| Batch AI subtitle generation | ✅ Select all, filter by no-AI/no-subs | settings.html AI tab |
| Multi-user + groups | ✅ Users, profiles, groups, sessions | UserService.java |
| Session management with revoke | ✅ View IP, created date, activity, revoke | settings.html session tab |
| Storyboard/preview thumbnails | ✅ Server-generated spritesheets | StoryboardManager.js, ThumbnailJob.java |
| Playback speed control | ✅ 1x-2x with localStorage | UIBuilder.js:156-161 |
| HEVC.js client-side WASM transcoding | ✅ WebAssembly HEVC→H.264 in browser | StreamManager.js:36-129 |
| **Native HEVC support detection** | ✅ MediaSource + canPlayType checks | StreamManager.js:23-30 |
| **WebCodecs API check** | ✅ VideoDecoder/VideoEncoder availability | StreamManager.js:13-18 |
| **Stream fallback chain** | ✅ hevc.js→direct→retry | StreamManager.js:207-243 |
| **Stall detection + auto-retry** | ✅ 20s/60s stall timers with Toast | EventBinder.js |
| Picture-in-Picture | ✅ Native API | oplayer-adapter.js |
| Keyboard shortcuts | ✅ Space/K/J/L/F/M/Ctrl+Alt+D | KeyboardShortcuts.js |
| Debug/marker inspector dialog | ✅ Override markers, refresh status | ControlsManager.js:107-151 |
| **Volume + mute persistence (localStorage)** | ✅ Saves and restores | EventBinder.js:181, 187 |
| **Progress save on tab hide** | ✅ visibilitychange → save | ProgressReporter.js:18-20 |
| **Music suspension during video** | ✅ Pauses music when video starts | ProgressReporter.js:56-60 |
| Server-to-server sync | ✅ Sync servers, music/video/timelines/playlists | SyncAPI.java |
| External video proxying | ✅ URL rewriting, HLS proxy | VideoExternalAPI.java |
| Crossfade playback | ✅ Configurable 0-10s crossfade | DjTransitionService.java |
| DJ Mode (beat-matched transitions) | ✅ BPM tolerance, sections, trigger %, crossfade | DjTransitionService.java |
| **BPM detection** | ✅ TarsosDSP, BeatRoot | AudioAnalysisService.java |
| **Spectral feature extraction** | ✅ Chroma-like FFT buckets | AudioAnalysisService.java:200-222 |
| **Similarity graph (EternalJukebox)** | ✅ Cross-cycle + relative matching | AudioAnalysisService.java:352-409 |
| **DJ Mode proactive pre-analysis** | ✅ Ensures 5 upcoming songs pre-analyzed | AudioAnalysisService.java:533-554 |
| **BPM metadata writing to files** | ✅ Writes to ID3/Vorbis/MP4 tags | MetadataWriteService.java |
| **Dual-audio-element gapless** | ✅ Dual `<audio>` elements with preload tracking | musicBar/core/AudioEngine.js |
| **Web Audio API sine-curve crossfade** | ✅ Independent gain nodes, sine fade curves | AudioEngine.js |
| **Smart Shuffle mode** | ✅ Algorithmic non-repeating shuffle, persisted | StateManager.js |
| **Per-device volume** | ✅ Device ID + per-device volume via API | DeviceManager.js |
| **Multi-client action conflict resolution** | ✅ 3s timeout blocks WebSocket override of local actions | ActionTracker.js |
| **State persistence (page lifecycle)** | ✅ pagehide/visibilitychange save, 30s max-age | StatePersistence.js |
| **Song context cache** | ✅ 30s TTL for prev/current/next | SongContextCache.js |
| **DJ Transition beat monitoring** | ✅ Exit/entry time monitoring with UI indicators | DjTransitionManager.js |
| **Native MediaSession OS lock-screen** | ✅ Native OS media controls (play/pause/prev/next/seek) | jmedia/MediaSession.js |
| **Song page cache (6h)** | ✅ localStorage cache with expiry | jmedia/SongCache.js |
| **Offline fallback with Toast** | ✅ HTMX cache fallback on network failure | jmedia/Player.js |
| **Play history (paginated + search)** | ✅ Paginated history with search | jmedia/HistoryManager.js |
| **Toast notification system (5 types)** | ✅ Success/error/warning/info/progress, progress bars | jmedia/ToastSystem.js |
| **Mobile song list (sort/filter/genre)** | ✅ Column sorting, genre filter, touch-friendly | jmedia/MobileApp.js |
| **REST music playback API client** | ✅ Full REST CRUD for playback/queue/playlists | jmedia/PlaybackApi.js |
| **Music desktop keyboard shortcuts (24)** | ✅ Ctrl+arrows/numbers/letters, mouse wheel, right-click, drag-to-playlist, seek preview | DesktopAdapter.js |
| **Mobile music gestures** | ✅ Swipe, pull-to-refresh, long-press, safe area insets, orientation handling | MobileAdapter.js |
| Hardware encoder priority | ✅ Configurable priority list per codec | FFmpegDiscoveryService.java |
| Auto-install dependencies | ✅ Chocolatey/Python/FFmpeg/SpotDL/Parakeet | InstallationService.java |
| Multi-library directories | ✅ Add/remove multiple dirs per music/video | settings.html |
| Download source switching | ✅ YouTube⇄SpotDL on rate limit | DownloadService.java:492-561 |
| **YouTube advanced options** | ✅ IPv4/IPv6, UA, impersonate, player client | DownloadService.java:654-694 |
| **Retry strategies (4 modes)** | ✅ Immediate/AfterFailure/OnlyRateLimit/SmartAdaptive | Settings.java:56 |
| **Real-time download progress via WebSocket** | ✅ Live import status | DownloadService.java:1074-1076 |
| **Cancel download (process kill)** | ✅ destroyForcibly + AtomicBoolean | DownloadService.java:157-163 |
| **SPA client-side router** | ✅ 4 views with URL-based routing | App.js |
| **Sidebar position preference** | ✅ Left/right option, API-backed | settings.html:83 |
| **Admin-only UI toggle** | ✅ Hides admin elements from non-admins | App.js:28-40 |
| **Auto-skip undo** | ✅ Restores to skip start, prevents re-auto-skip | SkipController.js:71-108 |
| **Per-section auto-skip toggle** | ✅ Individual checkboxes for intro/recap/outro | SkipController.js:110-126 |
| **4 scan modes (full/incremental/import/targeted)** | ✅ Change-detection optimization, parallel via ExecutorCompletionService | SettingsController.java |
| **Manual metadata override flags** | ✅ titleManuallyEdited prevents auto-overwrite | NamingController.java |
| **Verification panel with blind mode** | ✅ Side-by-side comparison, hide-click-to-reveal for unbiased QA | VideoManagementApi.java |
| **Batch video conversion** | ✅ Queue-based, HW encoder fallback chain, disk check, subtitle probe | VideoConversionService.java |
| **Continue Watching** | ✅ Per-profile watchProgress, sorted by lastUpdated | VideoState.java |
| **Collection playback tracking** | ✅ Per-collection progress + REPEAT_ONE/REPEAT_ALL/NONE | CollectionWatchProgress.java |
| **DJ Mode secondary queue** | ✅ Queue slot after current song in DJ Mode | PlaybackState.secondaryQueue |
| **Profile audio language memory** | ✅ Preferred language persists across episodes | ProfileSessionState.java |
| **Subtitle preference engine (4-tier)** | ✅ Per-video > profile language > audio mismatch > video default | SubtitlePreferenceEngine.java |
| **On-demand subtitle discovery** | ✅ Trigger subtitle scan per video from UI | SubtitleAPI.java |
| **Self-signed HTTPS certificate** | ✅ keytool-based, 10yr, SAN:localhost, auto-applies config | CertificateService.java |
| **Background analysis worker** | ✅ 10s polling, 2 songs/tick, defers when transcoding active | AnalysisWorker.java |
| **CPU-aware scan executor** | ✅ Pooled executor sized to available cores | VideoScanExecutor.java |
| **Auth filter (3-tier + rate limiting)** | ✅ Public > session > streaming bypass, IP rate limiting, sync key | JMediaAuthFilter.java |
| **Metadata enrichment queue processor** | ✅ Background enrichment, rate-limited TMDb/OMDb, retry with exponential backoff | MetadataQueueProcessor.java |
| **Asset rename standardization + daily cron** | ✅ Background canonical rename, 3am daily cron | RenameQueueProcessor.java |
| **Subtitle discovery queue processor** | ✅ 2-thread background subtitle discovery for episodes without subs | SubtitleDiscoveryQueueProcessor.java |
| **GPU detection (OS-level)** | ✅ NVIDIA/Intel/AMD, discrete/integrated detection | GpuDetectionService.java |
| **HLS session management** | ✅ Session-based with quality/audio/device params | HlsResource.java |
| **Enhanced auth API** | ✅ Rate-limited login, local network cookie bypass, Clear-Site-Data, session status | EnhancedAuthAPI.java |
| **Rate-limited login** | ✅ IP-based tracking, blocks after N failed attempts | RateLimitService.java |
| **Admin auto-creation** | ✅ Creates default admin users + profiles on first startup | AdminUserService.java |
| **Video suggestions** | ✅ Per-profile user-submitted content suggestions | VideoSuggestionService.java |
| **Trending algorithm** | ✅ Play count + recency-weighted trending | VideoHistoryService.java |
| **Collection playback API** | ✅ REST endpoints for collection playback control | CollectionPlaybackAPI.java |
| **Platform-specific operations** | ✅ Per-OS package mgr detection, automated install/uninstall of 6+ dependencies | PlatformOperations.java |
| **AlbumArtService circuit breaker** | ✅ MicroProfile Fault Tolerance (CircuitBreaker, Retry, Timeout, Fallback, Bulkhead) | AlbumArtService.java |
| **Unified video entity creation** | ✅ Combines MediaFile + NamingResult, preserves metadata during full scans | UnifiedVideoEntityCreationService.java |

---

## Unique Advantages — What JMedia does that Plex/Jellyfin cannot

| Advantage | JMedia Implementation | Why Plex/Jellyfin Cannot |
|---|---|---|
| **YouTube & Spotify import pipeline** | yt-dlp + SpotDL, auto-download from URLs | Neither integrates download managers |
| **AI subtitle generation (27 languages)** | Parakeet ASR API (local or cloud) | Neither offers AI subtitle generation |
| **AI subtitle translation (27 output languages)** | Parakeet API | Neither offers AI subtitle translation |
| **Batch AI subtitle operations** | Select-all, filter, batch generate with progress | Neither offers batch subtitle processing |
| **DJ Mode (beat-matched transitions)** | TarsosDSP BPM + BeatRoot onset + EternalJukebox graph | Plexamp has BPM display but no beat-matched DJ transitions; Jellyfin has no BPM at all |
| **EternalJukebox similarity engine** | On-the-fly cosine similarity between chroma vectors | Neither has anything similar |
| **Proactive DJ Mode pre-analysis** | Analyzes 5 upcoming songs in background | Neither pre-analyzes for transitions |
| **BPM + spectral + metadata write** | Writes BPM and analysis data back to file tags | Plexamp reads BPM; Jellyfin stores BPM in DB only |
| **HEVC.js client-side transcoding** | WebAssembly-based HEVC→H.264 in browser | Jellyfin works around HEVC browser gap (no fix); Plex no client-side decode |
| **Server-to-server synchronization** | Sync servers can mirror music/video/timelines/playlists | Plex has no sync; Jellyfin has no multi-server sync |
| **External video URL proxy** | Rewrite+HLS for IPTV/external streams | Neither proxies external URLs |
| **Dependency auto-installer** | Chocolatey/Python/FFmpeg/SpotDL/Parakeet | Neither installs dependencies; Jellyfin requires manual ffmpeg setup |
| **Three player options** | JMedia Player, Video.js, OPlayer — switchable at runtime | Plex: proprietary player only. Jellyfin: native + HLS.js only |
| **Debug/marker inspector dialog** | Ctrl+Alt+D opens marker override dialog | Neither has a debug dialog for markers |
| **Per-section auto-skip toggles + undo** | Individual intro/recap/outro toggles with undo | Plex: all-or-nothing skip. Jellyfin: per-section but no undo |
| **File fingerprinting for moved files** | Size+head+tail MD5 hash | Plex matches by path only; Jellyfin uses path + basic metadata |
| **Metadata writing back to audio files** | JAudioTagger writes to MP3/FLAC/M4A/OGG, backup+restore | Neither writes metadata back to files |
| **Virtual show/season/episode organization** | Normalized names, cleans quality tags | Both require files in traditional TV structure |
| **Text vs image subtitle filtering** | Filters out PGS/DVD/image subs from streaming | Both support image subs but don't explicitly filter them for streaming |
| **Full playback speed control (1x-2x)** | 1.0x/1.25x/1.5x/2.0x with localStorage persistence | Both support speed control (not unique) |
| **Encoder failure tracking + auto-invalidation** | 5-failure window removes encoder from pool | Neither auto-handles encoder failures this way |
| **Smart rate limiting for downloads** | Configurable wait time, fallback on long waits | No download feature to rate-limit |
| **Dual architecture (Quarkus native + JVM)** | Native binary (GraalVM) or traditional JAR | Plex: Python/C++ runtime. Jellyfin: .NET runtime |
| **Dual-audio-element crossfade architecture** | Two independent gain nodes with sine-curve fade — avoids audio dropout during transitions | Plexamp: single audio element, basic fade. Jellyfin: no crossfade at all |
| **Smart Shuffle (algorithmic non-repeating)** | SMART_SHUFFLE mode with state persistence, beats basic random | Plex: basic random. Jellyfin: basic random |
| **Multi-client action conflict resolution** | ActionTracker prevents WebSocket race conditions — local actions take priority for 3s window | Neither Plex nor Jellyfin has multi-client action conflict resolution |
| **24 music desktop keyboard shortcuts** | Full keyboard-driven music workflow (Ctrl+arrows/numbers/letters, wheel, right-click, drag-to-playlist) | Plex: basic shortcuts. Jellyfin: basic shortcuts. Neither has music-specific keyboard workflow |
| **Mobile music gestures** | Swipe to queue, pull-to-refresh, long-press context menus, safe area insets for notched devices | Neither has dedicated music gestures for web UI |
| **Native OS MediaSession integration** | Full lock-screen controls (play/pause/prev/next/seek/position-state) via MediaSession API | Both support MediaSession API (not unique) |
| **Toast notification system with progress bars** | 5 toast types with animated progress indicators, auto-cleanup | Neither has built-in toast system for web UI |
| **Scan intelligence (4 modes + parallel)** | Full/incremental/import/targeted with change-detection, parallel scanning | Neither scans with this granularity or mode selection |
| **Verification panel / blind mode** | Side-by-side metadata QA with hide-to-reveal | Neither has a built-in metadata QA workflow |
| **Batch conversion with smart fallback** | HW encoder chain fallback, disk check, subtitle probe, atomic temp→final move | Jellyfin has per-item conversion; Plex has none |
| **Self-signed HTTPS certificate** | Auto-generated via keytool, 10yr, SAN:localhost | Jellyfin requires manual cert setup; Plex is SaaS |
| **DJ Mode secondary queue** | Separate queue slot for upcoming tracks in DJ Mode | Neither has DJ Mode at all |
| **Profile cross-episode audio memory** | PreferredAudioLanguage persists between episodes in series | Plex remembers track per video; Jellyfin does not |
| **Background analysis with CPU contention avoidance** | Defers analysis when video transcoding active — no CPU fights | Neither has auto-contention avoidance |
| **Manual metadata override flags** | titleManuallyEdited prevents auto-overwrite | Neither has permanent manual-overwrite flags |
| **Queue processor architecture** | Centralized background processing system with BlockingQueue + ExecutorService, rate limiting, retry with exponential backoff, lifecycle management | Neither has a dedicated queue processor system — Plex uses Butler tasks, Jellyfin uses scheduled tasks |
| **Platform-specific operations (per-OS dependency management)** | Automated detection and install/uninstall of Python, Node.js, FFmpeg, SpotDL, yt-dlp, Parakeet per Windows/Linux/macOS | Plex bundles its own runtime; Jellyfin requires manual dependency management |
| **Enhanced auth with rate limiting** | IP-based login rate limiting with local network detection for cookie security bypass, Clear-Site-Data on logout | Plex uses cloud auth; Jellyfin has basic brute-force protection |

---

## Risk Factors

### Technical Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| **Single developer bus factor** | High | Apache 2.0 license enables fork; Java+Quarkus standard stack is widely maintainable |
| **H2 database scaling** | Low-Medium | H2 works well for personal libraries (10K-50K items). For 100K+, migration path to PostgreSQL exists via Quarkus config change |
| **No formal test suite** | Medium | Lack of automated testing increases regression risk. Current approach: manual testing + compile-time checks |
| **Browser-only client** | Low | Works on all major browsers including mobile Safari/Chrome. No native app store distribution needed |
| **yt-dlp/SpotDL breaking changes** | Medium | External tools may break with YouTube API changes. Mitigation: Configuration options for yt-dlp args, version pinning possible |
| **HEVC.js performance** | Low | WASM decode is slower than native but falls back automatically. Not used for >1080p content |

### Feature Risks

| Risk | Impact | Notes |
|---|---|---|
| **No plugin system** | Extensibility limited | All changes require source modification. Acceptable for a focused personal media server |
| **No offline sync / download to device** | Offline viewing impossible | All clients must be online to the server. Acceptable for home use |
| **No recommendation engine** | Discovery limited | No "because you watched" or ML suggestions. Search + library browsing used instead |
| **No remote access tunneling** | LAN-only out of box | Reverse proxy (nginx/caddy) needed for WAN access. Acceptable for home media |

---

> **Summary:** JMedia trades native clients, plugin ecosystem, and polished discovery for unique import pipeline, AI subtitle capabilities, DJ Mode, HEVC.js transcoding, and zero-cost unlock of all features. It excels as a self-contained media server for tech-savvy home users who want Plex-class features without subscription costs.



