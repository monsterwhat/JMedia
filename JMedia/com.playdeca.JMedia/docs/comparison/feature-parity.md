# JMedia vs Plex vs Jellyfin - Feature Parity Table

> **See also:** [gap-analysis.md](gap-analysis.md) for gaps and advantages | [marketing-comparison.md](marketing-comparison.md) for narrative overview | [architecture.md](architecture.md) for architectural deep-dive
>
> **Legend:** ✅ = Native support | 🔒 = Gated/paid | ⚠️ = Partial / via plugin | ❌ = Absent
> **Sources:** JMedia claims cite specific source files. Plex/Jellyfin claims cite official documentation.

---

## 1. Core Media Management

| Feature | JMedia | Plex | Jellyfin |
|---|---|---|---|
| Library scanning (auto/manual) | ✅ - Services/VideoScanningService.java | ✅ | ✅ |
| Metadata fetching (TMDB) | ✅ - Services/EnhancedFreeMetadataService.java, Services/VideoMetadataService.java | ✅ | ✅ |
| Metadata fetching (IMDb/OMDb) | ✅ - Services/EnhancedFreeMetadataService.java (omdbApiKey in Settings.java:42) | ✅ | ✅ |
| Metadata fetching (TVDB) | ✅ - Services/EnhancedFreeMetadataService.java | ✅ | ✅ |
| Metadata enrichment on reload | ✅ - Settings.java:77 (enableMetadataEnrichment) | ✅ | ✅ |
| Automatic naming/identification | ✅ - Services/SmartNamingService.java | ✅ | ✅ |
| Collections/series grouping | ✅ - Services/CollectionService.java, Models/MediaCollection.java | ✅ | ✅ |
| Genre classification | ✅ - Models/Video.java (genreId field) | ✅ | ✅ |
| Content ratings | ✅ - Models/Video.java (contentRating field) | ✅ | ✅ |
| Multi-library support (music + video) | ✅ - Settings.java:17-18 (libraryPath, videoLibraryPath), settings.html:13-65 | ✅ | ✅ |
| Multiple directories per library | ✅ - settings.html "Add Music/Video Directory" | ✅ | ✅ |
| Manual metadata editing | ✅ - API/Rest/MetadataAPI.java, editVideoFragment.html | ✅ | ✅ |
| Bulk metadata refresh | ✅ - Services/MetadataRefreshService.java, settings.html "Reload Video Metadata" | ✅ | ✅ |
| **Metadata writing to audio files** | ✅ - Services/MetadataWriteService.java (writes back to MP3/FLAC/M4A/OGG/WAV, backup-before-write, restore on failure) | ⚠️ (limited) | ⚠️ (limited) |
| **File fingerprinting (moved file detection)** | ✅ - Services/MediaAnalysisService.java:244-280 (size + first 1MB + last 1MB → MD5 hash) | ✅ | ✅ |
| **Virtual show/season/episode organization** | ✅ - Services/MetadataOrganizerService.java (virtual paths: /shows/{name}/season/{n}/episode/{n}) | ✅ | ✅ |
| **BPM detection** | ✅ - Services/AudioAnalysisService.java (TarsosDSP, BeatRoot, median interval BPM) | 🔒 (Plexamp) | ❌ |
| **BPM metadata writing to files** | ✅ - MetadataWriteService.java (writes BPM to ID3/Vorbis/MP4 tags) | ❌ | ❌ |
| **BPM detection fallback chain** | ✅ - SettingsController.java (JAudioTagger → FFprobe → TarsosDSP → duration-estimation, 4-tier) | 🔒 (Plexamp) | ❌ |
| **Scan modes (full/incremental/import/targeted)** | ✅ - SettingsController.java (4 modes with change-detection, parallel via ExecutorCompletionService) | ✅ | ✅ |
| **Manual metadata override flags** | ✅ - NamingController.java (titleManuallyEdited, seriesTitleManuallyEdited — prevents auto-overwrite) | ✅ (per-field lock icons since 2010) | ⚠️ (lock feature exists but buggy - #11773, #15693) |
| **Metadata verification panel (blind mode)** | ✅ - VideoManagementApi.java (side-by-side comparison, hide-click-to-reveal for unbiased QA) | ❌ | ❌ |
| **GPU detection (OS-level)** | ✅ - Services/GpuDetectionService.java (detects NVIDIA/INTEL/AMD/UNKNOWN, discrete vs integrated, via OS commands) | ✅ | ✅ |

## 2. Video Playback & Transcoding

| Feature | JMedia | Plex | Jellyfin |
|---|---|---|---|
| HLS streaming | ✅ - Services/TranscodingService.java | ✅ | ✅ |
| Direct play | ✅ - Controllers/VideoController.java | ✅ | ✅ |
| On-the-fly transcoding | ✅ - Services/TranscodingService.java | ✅ | ✅ |
| Hardware acceleration (NVENC) | ✅ - FFmpegDiscoveryService.java:290-297 (priority-ordered encoder list, runtime probing) | 🔒 (Plex Pass) | ✅ |
| Hardware acceleration (QSV) | ✅ - FFmpegDiscoveryService.java (Intel QuickSync) | 🔒 (Plex Pass) | ✅ |
| Hardware acceleration (VAAPI) | ✅ - FFmpegDiscoveryService.java (Linux VA-API) | 🔒 (Plex Pass) | ✅ |
| Hardware acceleration (AMF) | ✅ - FFmpegDiscoveryService.java (AMD AMF) | 🔒 (Plex Pass) | ✅ |
| Hardware acceleration (VideoToolbox) | ✅ - FFmpegDiscoveryService.java (Apple VideoToolbox) | 🔒 (Plex Pass) | ✅ |
| **HW decoder per codec (H.264/HEVC/VP9/AV1)** | ✅ - FFmpegDiscoveryService.java:424-483 (per-codec fallback chain: cuvid→videotoolbox→qsv→amf→d3d11va→dxva2→mf→vaapi→v4l2m2m) | 🔒 (Plex Pass) | ✅ |
| **Runtime HW acceleration probing** | ✅ - FFmpegDiscoveryService.java:380-413 (tests if HW device is actually usable) | ✅ | ✅ |
| **Encoder failure tracking + auto-invalidation** | ✅ - FFmpegDiscoveryService.java:541-553 (5 failures in 5 minutes → removed from pool) | ❌ | ❌ |
| **mkvmerge detection** | ✅ - FFmpegDiscoveryService.java:218-273 (auto-detects MKVToolNix) | ❌ | ❌ |
| HDR tone mapping | ✅ - via ffmpeg tonemap filters | 🔒 (Plex Pass) | ✅ |
| Quality selection (multi-bitrate) | ✅ - API/Rest/VideoPlaybackAPI.java, UIBuilder.js:137-141 (720p/1080p/4K/480p/Source at runtime) | ✅ | ✅ |
| Trick play / seeking | ✅ - API/Rest/VideoPlaybackAPI.java | ✅ | ✅ |
| **Server-side seek (transcoded content)** | ✅ - StreamManager.js:385-458 (re-streams from keyframe, preserves quality/audio track) | ✅ | ✅ |
| **Client-side seek (direct play)** | ✅ - StreamManager.js:394-399 (buffered range check, seeks within buffer) | ✅ | ✅ |
| Max concurrent transcodes config | ✅ - Settings.java:97 (maxConcurrentTranscodes) | 🔒 (Plex Pass) | ✅ |
| Multi-device isolation | ✅ - Services/SessionService.java | ✅ | ✅ |
| Segment caching | ✅ - Services/TranscodingService.java (cacheDir) | ✅ | ✅ |
| DASH streaming | ❌ (HLS only) | 🔒 | ✅ |
| Audio codec passthrough (AC3/DTS) | ✅ - Services/TranscodingService.java | ✅ | ✅ |
| Subtitle burn-in during transcoding | ✅ - Services/TranscodingService.java | ✅ | ✅ |
| Picture-in-Picture mode | ✅ - oplayer-adapter.js:414-430 (native PiP API with sync toggle) | ❌ | ❌ |
| **HEVC.js client-side transcoding (WASM)** | ✅ - StreamManager.js:36-129 (WebAssembly-based HEVC→H.264 decode in browser, no server needed) | ❌ | ❌ |
| **Native HEVC support detection** | ✅ - StreamManager.js:23-30 (MediaSource.isTypeSupported + canPlayType checks) | ✅ | ✅ |
| **WebCodecs API capability check** | ✅ - StreamManager.js:13-18 (VideoDecoder/VideoEncoder availability) | ❌ | ❌ |
| **Stream fallback chain** | ✅ - StreamManager.js:207-243 (hevc.js → direct stream → retry with backoff, auto-fallback on error) | ✅ | ✅ |
| **Stall detection + auto-retry** | ✅ - EventBinder.js:24-54 (20s stall timer with Toast), EventBinder.js:112-145 (60s mid-playback stall) | ✅ | ✅ |
| **Batch video conversion** | ✅ - VideoConversionService.java (911 lines, queue-based, HW encoder fallback chain, disk space check, subtitle probe) | ⚠️ (Media Optimizer: conversion queue, pre-transcode to MP4, library-level rules) | ⚠️ (per-item only) |
| **Continue Watching** | ✅ - VideoState.watchProgress, sorted by lastUpdated per profile | ✅ | ✅ |
| **Collection playback tracking with RepeatMode** | ✅ - CollectionWatchProgress.java (REPEAT_ONE/REPEAT_ALL/NONE per collection) | ❌ | ❌ |
| **HLS session management** | ✅ - API/Rest/HlsResource.java, Services/HlsService.java (session-based HLS: create session with videoId/start/profile/audio/quality/device, master playlist → variant → segment pipeline, 15s segment polling) | ✅ | ✅ |

## 3. Subtitle Support

| Feature | JMedia | Plex | Jellyfin |
|---|---|---|---|
| Embedded subtitle extraction (SRT) | ✅ - Models/SubtitleTrack.java, FFprobeSubtitleService.java | ✅ | ✅ |
| Embedded subtitle extraction (ASS/SSA) | ✅ - FFprobeSubtitleService.java:240-300 (raw extraction for browser rendering via JASSUB) | ✅ | ✅ |
| Embedded subtitle extraction (PGS) | ⚠️ - detected but skipped (image-based, needs OCR) | 🔒 | ⚠️ |
| **Text vs image-based subtitle detection** | ✅ - FFprobeSubtitleService.java:44-51 (filters out PGS/DVD/image-based subs from streaming) | ✅ | ✅ |
| **ASS/SSA raw extraction** | ✅ - FFprobeSubtitleService.java:240-300 (temp file→read→cleanup, preserves all styling) | ✅ | ✅ |
| **WebVTT on-the-fly conversion** | ✅ - FFprobeSubtitleService.java:176-234 (ffmpeg pipe→WebVTT for any text-based sub format) | ✅ | ✅ |
| External subtitle files (SRT) | ✅ - API/Rest/SubtitleAPI.java | ✅ | ✅ |
| External subtitle files (VTT) | ✅ - API/Rest/SubtitleAPI.java | ✅ | ✅ |
| External subtitle files (ASS/SSA) | ✅ - API/Rest/SubtitleAPI.java | ✅ | ✅ |
| External subtitle files (SUB) | ✅ - subtitle-manager.js (upload supports SUB) | ✅ | ✅ |
| AI subtitle generation (Parakeet) | ✅ - Services/ParakeetService.java, Services/AiSubtitleJobService.java | ❌ | ❌ |
| AI subtitle translation (27 languages) | ✅ - Services/ParakeetService.java, settings.html AI tab (en/es/fr/de/it/pt/nl/ru/ja/zh/ko/ar/hi/tr/pl/sv/da/no/fi/el/cs/ro/hu/uk/th/vi/id/ms/he) | ❌ | ❌ |
| Subtitle download (OpenSubtitles) | ✅ - Services/SubtitleDownloadService.java (downloadSubtitleWithLang) | 🔒 | ✅ |
| Subtitle upload (local files) | ✅ - SubtitleAPI.uploadSubtitle(), subtitle-manager.js UI (SRT/VTT/ASS/SSA/SUB) | ✅ | ✅ |
| Subtitle scanning/matching | ✅ - Services/EnhancedSubtitleMatcher.java (20+ language auto-detection, filename-based) | ✅ | ✅ |
| Forced subtitle support | ✅ - FFprobeSubtitleService.java:159 (disposition.forced flag) | ✅ | ✅ |
| SDH subtitle support | ✅ - FFprobeSubtitleService.java:160 (disposition.hearing_impaired flag) | ✅ | ✅ |
| Subtitle styling (font size, color, bg opacity, position) | ✅ - SubtitleSettingsUI.js:23-170 (size slider, color picker, opacity, bottom margin, live preview, reset) | ✅ | ✅ |
| **ASS subtitle rendering (JASSUB)** | ✅ - SubtitleController.js:58-101 (WebAssembly renderer, prescaleFactor, timeOffset) | ✅ | ✅ |
| **Subtitle timing correction** | ✅ - SubtitleSettingsUI.js:48-68 (button ±0.2s, click-to-edit manual input, reset, persists in localStorage) | ✅ | 🔒 |
| Subtitle delay adjustment | ✅ - subtitle-manager.js | ✅ | ✅ |
| **Subtitle preload for seamless seeking** | ✅ - StreamManager.js:460-499 (pre-loads subtitle tracks before stream switch) | ✅ | ✅ |
| **Subtitle sync on fullscreen transitions** | ✅ - SubtitleController.js:279-328 (syncs ASS/native subs on enter/exit fullscreen) | ✅ | ✅ |
| Multi-language subtitle management | ✅ - Services/SubtitlePreferenceService.java | ✅ | ✅ |
| Batch AI subtitle generation | ✅ - settings.html AI tab (select all, filter by no-AI/no-subs, batch generate with progress) | ❌ | ❌ |
| AI subtitle progress tracking | ✅ - settings.html AI tab (progress bar, cancel, error display, paginated history) | ❌ | ❌ |
| **On-demand subtitle discovery** | ✅ - SubtitleAPI.java (trigger subtitle scan per video) | ❌ | ❌ |
| **20+ language subtitle scoring engine** | ✅ - EnhancedSubtitleMatcher.java (language preference scoring, filename-based matching) | ❌ | ❌ |
| **Per-video subtitle preference save/restore** | ✅ - SubtitlePreferenceEngine.java (4-tier: per-video > profile language > audio mismatch > video default) | ✅ | ✅ |
| **Background subtitle discovery queue** | ✅ - Services/SubtitleDiscoveryQueueProcessor.java (2-thread queue, auto-discovers subtitles for episodes without existing tracks, 500ms delay, retry with backoff) | ❌ | ❌ |

## 4. Audio & Music

| Feature | JMedia | Plex | Jellyfin |
|---|---|---|---|
| Multi-track audio selection | ✅ - Services/TranscodingService.java (audio stream selection) | ✅ | ✅ |
| FLAC support | ✅ - via ffmpeg decode + output format (settings.html:181) | ✅ | ✅ |
| Multi-channel audio (5.1/7.1) | ✅ - via ffmpeg encode | ✅ | ✅ |
| BPM detection (TarsosDSP) | ✅ - Services/AudioAnalysisService.java, Settings.java:78 (enableBpmExtraction) | 🔒 (Plexamp) | ❌ |
| **Spectral feature extraction (FFT → chroma)** | ✅ - AudioAnalysisService.java:200-222 (12 chroma-like buckets for cosine similarity) | ❌ | ❌ |
| **Beat tracking (BeatRoot)** | ✅ - AudioAnalysisService.java:234-238 (onset tracking→beat induction) | ❌ | ❌ |
| **Similarity graph (EternalJukebox)** | ✅ - AudioAnalysisService.java:352-409 (same-position cross-cycle + relative-position matching) | ❌ | ❌ |
| **DJ Mode pre-analysis (proactive)** | ✅ - AudioAnalysisService.java:533-554 (ensures upcoming 5 songs are analyzed for smooth transitions) | ❌ | ❌ |
| BPM tolerance / genre overrides | ✅ - Settings.java:66-67 (bpmTolerance, bpmToleranceOverrides JSON per-genre) | 🔒 (Plexamp) | ❌ |
| DJ Mode / beat-aligned transitions | ✅ - Services/DjTransitionService.java, Settings.java:70-74 (sections, trigger %, BPM tolerance, crossfade seconds) | ❌ (Plexamp gapless only) | ❌ |
| Audio analysis (waveform, loudness) | ✅ - Services/AudioAnalysisService.java | 🔒 (Sonic Analysis) | ❌ |
| Crossfade playback (configurable 0-10s) | ✅ - Services/DjTransitionService.java, settings.html:97 (crossfade slider) | 🔒 (Plexamp) | ❌ |
| Gapless playback | ✅ - HLS segment-based | ✅ | ✅ |
| EQ / audio filters | ⚠️ - via ffmpeg filter opts | 🔒 | ❌ |
| Music library / album organization | ✅ - Models/Album.java, Services/MusicService.java | ✅ | ✅ |
| Playlist support | ✅ - Services/PlaylistService.java | ✅ | ✅ |
| Playlist creator UI | ✅ - settings.html playlist tab (song name - artist input, batch creation) | ✅ | ✅ |
| **Audio track persistence (per-video)** | ✅ - AudioTrackSelector.js:202-219 (saves track preference to server API) | ✅ | ✅ |
| **Per-video audio track memory** | ✅ - AudioTrackSelector.js:26-36 (restores last track from localStorage on reload) | ✅ | ✅ |
| **Audio track channel display** | ✅ - AudioTrackSelector.js:88-91 (Stereo/5.1/7.1 labels in selector) | ✅ | ✅ |
| **Queue management** | ✅ - QueueManager.js (load, skip-to, remove, clear, search/filter, current-song highlighting) | ✅ | ✅ |
| **Dual-audio-element gapless** | ✅ - musicBar/core/AudioEngine.js (dual \<audio>\ elements p1/p2, preload readiness tracking for true gapless) | ✅ | ✅ |
| **Web Audio API sine-curve crossfade** | ✅ - AudioEngine.js (crossfadeTo with sine curvePoints \[1,0.95,0.85,0.65,0.4,0.15,0]\, independent gain nodes, active player toggle p1↔p2) | 🔒 (Plexamp) | ❌ |
| **Smart Shuffle mode** | ✅ - musicBar/core/StateManager.js (SMART_SHUFFLE state, CustomEvent coordination, mode toggle persisted across reloads) | ❌ | ❌ |
| **Per-device volume** | ✅ - musicBar/core/DeviceManager.js (device ID, per-device volume via API, clock offset for multi-device sync) | ❌ | ❌ |
| **Multi-client action conflict resolution** | ✅ - musicBar/utils/ActionTracker.js (3s timeout: local actions suppress conflicting WebSocket messages from other clients) | ❌ | ❌ |
| **State persistence (page lifecycle)** | ✅ - musicBar/data/StatePersistence.js (pagehide + visibilitychange save, 30s max-age restore, 30s periodic save) | ❌ | ❌ |
| **Song context cache (30s TTL)** | ✅ - musicBar/data/SongContextCache.js (prev/current/next song for fast transitions) | ❌ | ❌ |
| **DJ Transition beat monitoring** | ✅ - musicBar/core/DjTransitionManager.js (beat-aligned transitions, exit/entry time monitoring, UI indicator) | ❌ | ❌ |
| **Native MediaSession OS lock-screen** | ✅ - jmedia/MediaSession.js (native OS controls: play/pause/prev/next/seek/position-state, Windows/iOS/Android/ChromeOS) | ✅ | ✅ |
| **Song page cache (6h localStorage)** | ✅ - jmedia/SongCache.js (song page data cache with expiry cleanup on navigation) | ✅ | ✅ |
| **Offline fallback with Toast** | ✅ - jmedia/Player.js (HTMX cache fallback, error Toast on network failure) | ❌ | ❌ |
| **Play history (paginated + search)** | ✅ - jmedia/HistoryManager.js (paginated history view, search within entries) | ✅ | ✅ |
| **Toast notification system (5 types)** | ✅ - jmedia/ToastSystem.js (success/error/warning/info/progress, animated progress bars, auto-cleanup, max 5 toasts) | ❌ | ❌ |
| **Mobile song list (sort/filter/genre)** | ✅ - jmedia/MobileApp.js (responsive table, column sorting, genre filter dropdown) | ✅ | ✅ |
| **REST music playback API client** | ✅ - jmedia/PlaybackApi.js (full REST: play/pause/next/prev, queue CRUD, playlist management) | ✅ | ✅ |

## 5. Media Import (YouTube/Spotify)

| Feature | JMedia | Plex | Jellyfin |
|---|---|---|---|
| YouTube download (yt-dlp) | ✅ - Settings.java:49 (youtubeEnabled), DownloadService.java | ❌ | ❌ |
| **YouTube advanced options** | ✅ - DownloadService.java:654-694 (IPv4/IPv6, custom User-Agent, extractor-args, browser impersonation, player client android/tv/web_safari/web) | ❌ | ❌ |
| **YouTube cookies file upload** | ✅ - DownloadService.java:268-278, import.html cookies upload | ❌ | ❌ |
| Spotify download (SpotDL) | ✅ - Settings.java:52 (spotdlEnabled), DownloadService.java | ❌ | ❌ |
| Auto-install dependencies | ✅ - InstallationService.java + settings.html Import tab (Chocolatey, Python, FFmpeg, SpotDL, Parakeet installer) | ❌ | ❌ |
| Output format selection | ✅ - settings.html:178-183 (MP3/M4A/FLAC/OPUS) | ❌ | ❌ |
| Download threads config | ✅ - Settings.java:28 (downloadThreads 1-10) | ❌ | ❌ |
| Search threads config | ✅ - Settings.java:29 (searchThreads 1-10) | ❌ | ❌ |
| Smart rate limiting | ✅ - Settings.java:61-63 (enableSmartRateLimitHandling, fallbackOnLongWait, maxAcceptableWaitTimeMs) | ❌ | ❌ |
| Retry strategy (4 modes) | ✅ - Settings.java:56 (switchStrategy: IMMEDIATELY/AFTER_FAILURES/ONLY_ON_RATE_LIMIT/SMART_ADAPTIVE) | ❌ | ❌ |
| Primary/secondary source switching | ✅ - DownloadService.java:492-561 (YouTube⇄SpotDL fallback on rate limit/failure) | ❌ | ❌ |
| **Real-time download progress (WebSocket)** | ✅ - DownloadService.java:1074-1076 (broadcasts to importStatusSocket) | ❌ | ❌ |
| **Cancel download (process kill)** | ✅ - DownloadService.java:157-163 (destroyForcibly + AtomicBoolean flag) | ❌ | ❌ |
| **Download result tracking** | ✅ - DownloadService.java:1094-1132 (tracks downloaded files, skipped songs, output cache, source) | ❌ | ❌ |

## 6. IPTV & External Sources

| Feature | JMedia | Plex | Jellyfin |
|---|---|---|---|
| Xtream Codes API support | ✅ - Models/Xtream/*.java (XtreamCategory, XtreamLoginResponse, XtreamSeries, XtreamVodStream) | ❌ | ⚠️ (plugin) |
| M3U playlist support | ✅ - Services/M3UService.java, Services/XtreamService.java | ⚠️ (via Plex DVR) | ✅ |
| External URL proxy | ✅ - Services/ExternalVideoService.java, API/Rest/VideoExternalAPI.java | ❌ | ❌ |
| HLS URL rewriting | ✅ - API/Rest/VideoExternalAPI.java | ❌ | ❌ |
| EPG data integration | ✅ - Models/Xtream/XtreamEpg.java (where available), Services/EpgService.java | 🔒 (Plex DVR) | ✅ |
| Live channel grouping | ✅ - Models/Xtream/XtreamCategory.java | ❌ | ✅ |
| DVR / time-shift recording | ❌ | 🔒 (Plex DVR) | ✅ |
| Catch-up / replay | ⚠️ - via Xtream catchup where supported | 🔒 | ❌ |

## 7. User Management

| Feature | JMedia | Plex | Jellyfin |
|---|---|---|---|
| Multi-user support | ✅ - Models/User.java, Services/UserService.java | ✅ | ✅ |
| User profiles | ✅ - Models/Profile.java | ✅ | ✅ |
| Form-based authentication | ✅ - Services/AuthService.java | ✅ | ✅ |
| `Admin role/controls | ✅ - Controllers/AdminController.java (admin-only UI sections), App.js:28-40 (admin-only elements toggle) | ✅ | ✅ |
| Session management | ✅ - Models/Session.java, Services/SessionService.java, settings.html session tab (view IP/created/activity, revoke) | ✅ | ✅ |
| Watch history / progress tracking | ✅ - Models/WatchProgress.java, Services/WatchProgressService.java | ✅ | ✅ |
| User ratings | ✅ - Models/Rating.java, API/Rest/RatingAPI.java | ✅ | ✅ |
| User groups | ✅ - settings.html user tab (group column in user table) | ❌ | ⚠️ |
| User CRUD (create/delete) | ✅ - settings.html "Create User" button, users table with actions | ✅ | ✅ |
| Session cleanup | ✅ - settings.html "Clean Old Sessions" button | ✅ | ✅ |
| Activity monitoring | ✅ - Services/ActivityService.java | ✅ | ✅ |
| Parental controls | ⚠️ - basic age restriction via profile | 🔒 | ✅ |
| Password-less / PIN login | ❌ | 🔒 | ❌ |
| LDAP / OAuth / SSO | ❌ (not needed - offline/local app) | 🔒 | ✅ (LDAP, OAuth) |
| **Rate-limited login** | ✅ - API/Rest/EnhancedAuthAPI.java + Services/RateLimitService.java (IP-based tracking, blocks after N failed attempts, clears on success) | ✅ | ✅ |
| **Enhanced auth API (session status/current-user/is-admin)** | ✅ - API/Rest/EnhancedAuthAPI.java (GET /status, GET /current-user, GET /is-admin endpoints, session validation, user group detection) | ✅ | ✅ |
| **Auto-create default admin users** | ✅ - Services/AdminUserService.java (creates admin/changeme1234 + admin2 on first startup with linked profiles) | ✅ | ✅ |

## 8. Client Support

| Feature | JMedia | Plex | Jellyfin |
|---|---|---|---|
| Responsive web UI | ✅ - src/main/resources/META-INF/resources/js/ (custom JS); works on Windows/Linux/macOS/iOS/iPadOS/Android/Apple TV (via AirPlay) | ✅ | ✅ |
| Mobile browser (no app install) | ✅ - responsive design adapts to all screens | ✅ | ✅ |
| Desktop browser | ✅ - responsive design | ✅ | ✅ |
| Tablet | ✅ - responsive design | ✅ | ✅ |
| Player options (3) | ✅ - JMedia Player, Video.js, OPlayer (settings.html:39-41, switchable at runtime) | ❌ (proprietary) | ❌ (native + HLS.js) |
| Video.js integration | ✅ - videojs-adapter.js (full controls, quality switching, subtitles) | ❌ | ❌ |
| OPlayer integration | ✅ - oplayer-adapter.js (PiP, quality, storyboards, subtitle offset, seek buttons) | ❌ | ❌ |
| Third-party player hot-switch | ✅ - settings.html player selector changes at runtime with reload | ❌ | ❌ |
| Chromecast | ✅ - via OPlayer (Chromecast CAF) and Video.js (chromecast plugin) | ✅ | ⚠️ |
| AirPlay | ✅ - via OPlayer, Video.js, native HTML5 Remote Playback API; Apple TV 2nd gen+ | ✅ | ⚠️ |
| Picture-in-Picture | ✅ - oplayer-adapter.js (native PiP API with sync toggle) | ❌ | ❌ |
| Custom seek buttons | ✅ - oplayer-adapter.js (15s/30s forward/backward injected into OPlayer UI) | ❌ | ❌ |
| Storyboard preview thumbnails | ✅ - StoryboardManager.js:9-29 (server-generated spritesheet, tile calculation, hover preview) | 🔒 | ⚠️ (plugin) |
| **Playback speed control (1x-2x)** | ✅ - UIBuilder.js:156-161, EventBinder.js:221-231 (1.0x/1.25x/1.5x/2.0x with active indicator and localStorage) | ✅ | ✅ |
| **Keyboard shortcuts** | ✅ - KeyboardShortcuts.js (Space/K=toggle, J/L=±10s, F=fullscreen, M=mute, Ctrl+Alt+D=debug) | ✅ | ✅ |
| **Music desktop keyboard shortcuts (24)** | ✅ - musicBar/adapters/DesktopAdapter.js (Ctrl+←/→ prev/next, Ctrl+↑/↓ volume, Ctrl+1-9 tabs, Ctrl+F search, Ctrl+M mute, Ctrl+L sidebar, Ctrl+Q queue, Ctrl+P playlists, Ctrl+H help, Ctrl+S shuffle, F11 fullscreen, Space play/pause, Esc close, mouse wheel volume, right-click menus, drag-to-playlist, seek preview, tooltips, sidebar pin) | ❌ | ❌ |
| **Button skip values** | ✅ - EventBinder.js:159-175 (-30s/-15s/+15s/+30s configurable) | ✅ | ✅ |
| **Debug dialog (marker inspector)** | ✅ - ControlsManager.js:107-151, UIBuilder.js:164-204 (series/season/episode override, marker source display, refresh status) | ❌ | ❌ |
| **SPA client-side router** | ✅ - App.js (4 views: music, video, settings, import, with URL-based routing and popstate) | ✅ | ✅ |
| Native Windows/macOS/Linux app | ❌ | ✅ | ⚠️ (Jellyfin Media Player) |
| Android/iOS native app | ❌ | ✅ | ✅ |
| Smart TV app (LG/webOS/Tizen) | ❌ | ✅ | ❌ |
| Console app (PS/Xbox) | ❌ | ✅ | ❌ |
| Roku app | ❌ | ✅ | ✅ |
| Kodi integration | ❌ | ✅ (Plex for Kodi) | ✅ (Jellyfin for Kodi) |

## 9. Advanced Features

| Feature | JMedia | Plex | Jellyfin |
|---|---|---|---|
| Video storyboards / preview | ✅ - Services/VideoStoryboardService.java, Services/ThumbnailService.java, ThumbnailJob.java (priority queue, retry) | 🔒 | ⚠️ (plugin) |
| Thumbnail processing config | ✅ - Settings.java:36-40 (delay, retries, threads, prefer API, regenerate on reload) | ✅ | ✅ |
| Intro/Outro/Recap detection | ✅ - Services/IntroDbService.java (IntroDB API), auto-skip in settings.html:103-117 | 🔒 | ⚠️ (plugin) |
| Auto-skip intro/recap/outro | ✅ - SkipController.js (per-section toggle, undo with position restore, manually-undone section tracking) | 🔒 | ⚠️ (plugin) |
| **Auto-skip undo** | ✅ - SkipController.js:71-108 (restores to skip start, marks section as manual, prevents re-auto-skip) | ❌ | ❌ |
| **Per-section auto-skip toggle** | ✅ - SkipController.js:110-126 (individual checkboxes for intro/recap/outro) | ❌ | ❌ |
| Server-to-server sync | ✅ - Models/SyncServer.java, API/Rest/SyncAPI.java | ❌ | ❌ |
| Sync: music/video/timelines/playlists | ✅ - Settings.java:89-92 (syncMusic/Video/Timelines/Playlists enabled), cron schedule | ❌ | ❌ |
| Auto-update mechanism | ✅ - Services/UpdateService.java, Settings.java:32-33 (currentVersion, lastUpdateCheck, autoUpdateEnabled) | ✅ | ✅ |
| Setup / first-run wizard | ✅ - Controllers/SetupController.java, Settings.java:20 (firstTimeSetup flag) | ✅ | ✅ |
| Smart naming / filename normalization | ✅ - Services/SmartNamingService.java, MetadataOrganizerService.java:96-109 (cleans quality tags, year) | ✅ | ✅ |
| Play statistics / analytics | ✅ - Services/StatisticsService.java | 🔒 | ❌ |
| System logs (live WebSocket) | ✅ - API/WS/LogSocket.java, settings.html log tab (real-time log streaming) | ✅ | ✅ |
| Library clean/reset | ✅ - settings.html (Clear History, Clear Music DB, Reset Video DB) | ✅ | ✅ |
| Search across libraries | ✅ - Services/SearchService.java | ✅ | ✅ |
| Scheduled tasks / automation | ✅ - Services/ScheduledTaskService.java (Quartz-based) | ✅ | ✅ |
| Crossfade slider (music) | ✅ - settings.html:97 (range slider 0-10s) | 🔒 (Plexamp) | ❌ |
| Sidebar position preference | ✅ - settings.html:83 (left/right option), App.js:42-58 (API-backed preference) | ❌ | ❌ |
| **Volume persistence (localStorage)** | ✅ - EventBinder.js:181, 187 (volume level + mute state saved) | ✅ | ✅ |
| **Music suspension during video** | ✅ - ProgressReporter.js:56-60, App.js:116-156 (pauses music when video plays, restores on exit) | ✅ | ✅ |
| **Progress save on tab hide** | ✅ - ProgressReporter.js:18-20 (visibilitychange→playback save) | ✅ | ✅ |
| **Force-refresh series metadata** | ✅ - VideoManagementApi.java (nuke and re-fetch all metadata for a series) | ✅ (per-series "Refresh Metadata" + CLI --force + API force=1) | ✅ (POST /Items/{id}/Refresh with FullRefresh mode, series-level RefreshAllMetadata) |
| **Mass-rename episodes** | ✅ - VideoManagementApi.java (batch rename all episodes to standardized format) | ❌ | ❌ |
| **Self-signed HTTPS certificate** | ✅ - CertificateService.java (keytool-based, 10yr validity, SAN:localhost, auto-applies config) | ✅ (SaaS) | ⚠️ (manual) |
| **DJ Mode secondary queue** | ✅ - PlaybackState.secondaryQueue (queue after current song in DJ Mode) | ❌ | ❌ |
| **Profile cross-episode audio memory** | ✅ - ProfileSessionState.preferredAudioLanguage (remembers audio language across episodes) | ❌ (global per-user preference only, not per-series cross-episode — confirmed by forum feature requests) | ⚠️ (RememberAudioSelections exists but buggy: #12667, #13087, #5873 — UI shows correct track but wrong audio plays) |
| **Background analysis worker** | ✅ - AnalysisWorker.java (10s polling, 2 songs/tick, defers when transcoding, retries failed after 5min) | ⚠️ (Sonic Analysis via Butler scheduled tasks — loudness/sonic features only, no BPM/beat; CPU-contention unaware) | ❌ (scheduled tasks exist for library ops but no music/BPM analysis) |
| **CPU-aware scan executor** | ✅ - VideoScanExecutor.java (pooled executor, thread count auto-sized to available cores) | ✅ | ✅ |
| **Auth filter architecture** | ✅ - JMediaAuthFilter.java (3-tier: public > session cookie > streaming bypass, IP rate limiting, sync API key) | ✅ | ✅ |
| **Metadata enrichment queue processor** | ✅ - Services/MetadataQueueProcessor.java (background enrichment, rate-limited 500ms TMDb/250ms OMDb, 2 retries with exponential backoff, skips already-enriched) | ❌ | ❌ |
| **Asset rename standardization + daily cron** | ✅ - Services/RenameQueueProcessor.java (background asset renaming to canonical format, queues all on startup, daily 3am cron via @Scheduled) | ❌ | ❌ |
| **Thumbnail processing queue** | ✅ - Services/Thumbnail/ThumbnailQueueProcessor.java (background thumbnail generation queue, lifecycle-managed) | ❌ | ❌ |
| **Background subtitle discovery queue** | ✅ - Services/SubtitleDiscoveryQueueProcessor.java (2-thread queue, auto-discovers subs for episodes without tracks, retry with backoff) | ❌ | ❌ |
| **Video suggestion system** | ✅ - Services/VideoSuggestionService.java (per-profile video suggestions with CRUD: add/findAll/findByProfile/delete) | ❌ | ❌ |
| **Trending videos algorithm** | ✅ - Services/VideoHistoryService.java (play count + recency weighting, getPlayCountsForVideos within time window) | ✅ | ✅ |
| **Collection playback API** | ✅ - API/Rest/CollectionPlaybackAPI.java (REST endpoints for collection playback control, queue management per collection) | ❌ | ❌ |

## 10. Platform & Ecosystem

| Feature | JMedia | Plex | Jellyfin |
|---|---|---|---|
| Open source | ✅ (Apache 2.0) | ❌ (proprietary) | ✅ (GPL v2) |
| License | Apache 2.0 | Proprietary | GPL v2 |
| Plugin/extension system | ❌ (open source - fork and modify; no formal plugin API) | ⚠️ (but limited) | ✅ (30+ plugins) |
| RESTful API | ✅ - API/Rest/*.java | ✅ | ✅ |
| WebSocket real-time updates | ✅ - API/WS/*.java (LogSocket, MusicSocket, VideoSocket, WebSocketManager) | ✅ | ✅ |
| Docker support | ⚠️ - single JAR, Dockerfile possible | ✅ | ✅ |
| Native binary | ✅ - Quarkus native build (-Pnative Maven profile) | ❌ (Python/C++ runtime) | ❌ (.NET runtime) |
| Hardware transcoding config | ✅ - application.properties + Settings.java:98 (hardwareAccelerationEnabled toggle) | ✅ | ✅ |
| **Hardware decoder selection** | ✅ - FFmpegDiscoveryService.java (per-codec HW decoder selection with runtime validation) | ✅ | ✅ |
| Database | H2 (application.properties - quarkus.datasource.db-kind=h2) | SQLite | SQLite |
| Cost | Free - no paywall, all features included | Freemium (Plex Pass for HW transcoding, trailers, intro skip, downloads) | Free |
| Language | Java 25 + Quarkus 3.34.1 | Python/C++ | C# (.NET 8) |
| Frontend | Custom JS (responsive, no framework) | React | React |
| Import pipeline | yt-dlp (YouTube) + SpotDL (Spotify) | ❌ | ❌ |
| Dependency auto-installer | ✅ - InstallationService.java + settings.html Import tab (Chocolatey/Python/FFmpeg/SpotDL/Parakeet) | ❌ | ❌ |
| **Platform-specific operations** | ✅ - Services/Platform/{Windows,Linux,MacOS}PlatformOperations.java (per-OS package mgr detection, automated install/uninstall of Python/Node/FFmpeg/SpotDL/yt-dlp/Parakeet, admin command execution, cookies management) | ❌ | ❌ |
| Continuous development | ✅ active - src/main/java/ | ✅ active | ✅ active |

---

> **Note:** This table reflects JMedia v1.2.0. Feature status may change with new releases.
> Plex and Jellyfin feature status is based on latest public releases as of mid-2026.
> Some JMedia features are available via configurable ffmpeg/player options.



