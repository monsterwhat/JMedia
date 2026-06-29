# JMedia vs Plex vs Jellyfin — Marketing Comparison

> **See also:** [feature-parity.md](feature-parity.md) for full feature table | [gap-analysis.md](gap-analysis.md) for honest gaps | [architecture.md](architecture.md) for system design
>
> TL;DR: JMedia offers the most complete **self-contained** media server — the only one with an import pipeline, AI subtitles, DJ Mode, and HEVC.js transcoding, all free with no paywall.

---

## Comparison at a Glance

| Dimension | JMedia | Plex | Jellyfin |
|---|---|---|---|
| **Server cost** | Free forever, all features | Freemium ($4.99/mo or $119.99 lifetime for Plex Pass) | Free |
| **Client cost** | Free (browser only) | Free apps, Plex Pass for sync/downloads | Free |
| **Import pipeline** | ✅ YouTube + Spotify download | ❌ | ❌ |
| **AI subtitles (generate + translate)** | ✅ 27 languages, batch | ❌ | ❌ |
| **Music DJ Mode** | ✅ Beat-matched transitions, EternalJukebox engine | ⚠️ Plexamp: gapless, BPM display only | ❌ |
| **HEVC.js client-side WASM decode** | ✅ WebAssembly in browser | ❌ | ❌ |
| **Hardware transcoding** | ✅ Full (NVENC/QSV/VAAPI/AMF/VideoToolbox) | 🔒 Plex Pass | ✅ |
| **Native clients (mobile, TV, console)** | ❌ Browser only | ✅ Every platform | ✅ Most platforms |
| **Multi-user** | ✅ Free | ✅ Free | ✅ Free |
| **Live TV / DVR** | ⚠️ IPTV only | 🔒 Plex Pass | ✅ |
| **Plugin ecosystem** | ❌ Fork & modify | ⚠️ Limited | ✅ 30+ |
| **Server-to-server sync** | ✅ Built-in | ❌ | ❌ |
| **Dependency auto-installer** | ✅ Chocolatey/Python/FFmpeg/SpotDL/Parakeet | ❌ | ❌ |
| **Metadata write to audio files** | ✅ JAudioTagger, backup+restore | ❌ | ❌ |
| **Scan modes (4: full/incremental/import/targeted)** | ✅ Change-detection, parallel scanning | ⚠️ (basic scan + periodic + partial folder) | ⚠️ (basic scan + replace modes, real-time monitor with bugs) |
| **Continue Watching** | ✅ Per-profile progress, collection tracking | ✅ | ✅ |
| **Batch video conversion** | ✅ Queue-based, smart encoder fallback | ⚠️ Media Optimizer: queue, library rules, MP4 pre-transcode | ⚠️ per-item only |
| **HTTPS certificate auto-setup** | ✅ Self-signed via CertificateService | ✅ (SaaS) | ⚠️ manual |
| **Metadata verification/blind mode** | ✅ Side-by-side QA, hide-click-to-reveal | ❌ | ❌ |
| **Manual metadata override flags** | ✅ Title/series edits never auto-overwritten | ✅ per-field lock icons | ⚠️ locking exists but buggy |

---

## Where JMedia Wins

### 1. The Only Self-Contained Import Pipeline

Plex and Jellyfin assume you already have media files. JMedia **creates them for you**:

- **YouTube → search, download, auto-name, organize** — full pipeline from URL to playable media
- **Spotify → SpotDL download → metadata enrichment → library-ready**
- **Auto-dependency installer** — Chocolatey, Python, FFmpeg, SpotDL, Parakeet all installed from settings UI
- **Smart rate limiting** — 4 retry strategies, source switching (YouTube⇄SpotDL), cookies support
- **Real-time WebSocket progress** — watch download progress live in the import view

*"But I already have my media"* — JMedia also scans local directories like Plex; the import pipeline is a bonus, not a requirement.

### 2. AI Subtitle Ecosystem (Unmatched)

Neither Plex nor Jellyfin offers AI subtitle generation:

| Capability | JMedia | Plex | Jellyfin |
|---|---|---|---|
| AI subtitle generation (Parakeet) | ✅ | ❌ | ❌ |
| AI subtitle translation (27 languages) | ✅ | ❌ | ❌ |
| Batch generate for all items without subs | ✅ | ❌ | ❌ |
| Filter by "no AI subs" / "no subs at all" | ✅ | ❌ | ❌ |
| Progress tracking with cancel | ✅ | ❌ | ❌ |
| Force subtitles + SDH detection | ✅ | ✅ | ✅ |
| Subtitle styling (size/color/opacity/position) | ✅ | ✅ | ✅ |
| ASS/SSA rendering (JASSUB) | ✅ | ✅ | ✅ |
| Subtitle timing correction | ✅ ±0.2s, click-to-edit | ✅ | 🔒 |

### 3. DJ Mode & EternalJukebox Engine

JMedia is the **only media server with a professional-grade DJ engine**:

- **BPM detection** via TarsosDSP with BeatRoot onset tracking
- **Spectral feature extraction** — FFT → 12-bin chroma vectors
- **EternalJukebox similarity graph** — cross-cycle matching + relative-position matching for infinite non-repeating playback
- **Proactive pre-analysis** — DJ Mode analyzes 5 upcoming songs before they're needed
- **Configurable crossfade** (0-10 seconds), BPM tolerance, per-genre overrides
- **Startup queue** — 50 songs pre-loaded with analysis data

#### Architecture: Dual-Audio-Element Crossfade Engine

The musicBar subsystem uses a **dedicated AudioEngine** with two independent `<audio>` elements (p1/p2), each feeding its own Web Audio API gain node (g1/g2). Crossfades use sine-based curve points (`[1, 0.95, 0.85, 0.65, 0.4, 0.15, 0]`) for smooth, artifact-free transitions — far better than linear fades. Key details:

- **True gapless**: One element preloads the next track while the current one plays — no silence gap between songs
- **Independent gain control**: Each audio element has its own gain node, enabling precise crossfade curves without audio dropout
- **Smart Shuffle mode**: Algorithmic non-repeating shuffle with persisted state — not just random
- **Beat-aligned transitions**: DjTransitionManager monitors exit/entry timing for precise beat-matched cuts
- **Action conflict resolution**: ActionTracker prevents WebSocket messages from overriding local actions (pause/play/skip) for 3 seconds — prevents multi-client race conditions
- **Per-device volume**: DeviceManager assigns unique device IDs and persists per-device volume preferences
- **State persistence**: StatePersistence saves to localStorage on pagehide/visibilitychange, restores on reload with 30s max-age

Plexamp has BPM display and gapless playback. Jellyfin has neither. Neither has beat-matched transitions, dual-audio-element crossfade, Smart Shuffle, or multi-client conflict resolution.

### 4. HEVC.js Client-Side Transcoding

HEVC playback in browsers is historically broken. JMedia's solution is unique:

- **Probes native support** — checks `MediaSource.isTypeSupported` + `canPlayType` + `VideoDecoder.isConfigSupported`
- **HEVC.js WASM fallback** — WebAssembly-based HEVC→H.264 decode runs entirely in the browser
- **No server-side CPU cost** — unlike traditional transcoding, the client does the work
- **Automatic fallback chain** — hevc.js → direct stream → retry with exponential backoff
- **Stall detection** — 20s/60s timers trigger Toast notifications and auto-retry

Plex and Jellyfin either avoid HEVC or server-transcode it (expensive). JMedia's approach saves server resources.

### 5. Hardware Decoder Intelligence

JMedia's FFmpegDiscoveryService is more sophisticated than either competitor:

- **Per-codec decoder selection** — tests H.264/HEVC/VP9/AV1 against every available HW decoder
- **Runtime validation** — actually tests if the device is usable, doesn't assume
- **Encoder failure tracking** — 5 failures in 5 minutes → removed from pool
- **Priority-ordered fallback** — cuvid→videotoolbox→qsv→amf→d3d11va→dxva2→mf→vaapi→v4l2m2m per codec

### 6. Metadata Write-Back

JMedia is the only server that writes metadata (including BPM) back to audio files:

- MP3 (ID3v2), FLAC (Vorbis comments), M4A (MP4 tags), OGG, WAV
- Backup-before-write with restore-on-failure safety
- Custom JMedia application marker in file tags
- Artwork writing support

### 7. Server-to-Server Sync

Unique built-in sync: JMedia servers can mirror music libraries, video libraries, timelines, and playlists to each other — no third-party tool needed.

### 8. Three Player Options + Debug Tools

JMedia gives you three switchable players (JMedia Player, Video.js, OPlayer) at runtime — not locked into one proprietary engine. Plus:

- **Storyboard preview thumbnails** — hover timeline to preview scenes
- **Keyboard shortcuts** — Space/K=play/pause, J/L=±10s, F=fullscreen, M=mute, D=debug
- **Custom seek buttons** — -30s/-15s/+15s/+30s
- **Debug dialog** — Ctrl+Alt+D opens marker inspector with override capabilities
- **Picture-in-Picture** — native API with sync toggle
- **Progress save on tab hide** — visibilitychange triggers playback save

### 9. Scan Intelligence & Metadata QA

JMedia's scanning and metadata management tools go beyond basic file-watch:

- **4 scan modes** — Full (complete rescan), Incremental (changed files only, fastest), Import (recent additions only), Targeted (specific path/file). Plex and Jellyfin have basic scan/refresh with no mode selection.
- **Parallel scanning** — Uses `ExecutorCompletionService` to scan library directories concurrently
- **Change-detection optimization** — Skips files whose size + modification date are unchanged from last scan
- **Manual override flags** — Once you manually edit a title or series name, JMedia sets `titleManuallyEdited`/`seriesTitleManuallyEdited` flags and **never** auto-overwrites them
- **Metadata verification panel** — Admin UI shows side-by-side comparison of current metadata vs fetched metadata, with **blind mode** (hide all fields, click-to-reveal one at a time) for unbiased QA
- **Force-refresh series** — Nuke and re-fetch all metadata for an entire series in one click
- **Mass-rename episodes** — Batch rename all episodes in a series to standardized naming format

### 10. Continue Watching & Progress Tracking

- **Continue Watching** — Per-profile watch progress stored in `VideoState.watchProgress`, sorted by last activity (most recent first). Plex and Jellyfin have this too — JMedia matches them.
- **Collection tracking** — Per-collection watch progress with `REPEAT_ONE`/`REPEAT_ALL`/`NONE` modes for rewatch sessions (binge a series with auto-repeat)
- **Profile audio memory** — `ProfileSessionState.preferredAudioLanguage` remembers each profile's preferred audio language across episodes in a series — no re-selecting every episode

### 11. Batch Video Conversion

JMedia's conversion engine is more sophisticated than Jellyfin's per-item approach:

- **Queue-based batch conversion** — Queue multiple videos from the admin UI, processed one at a time to avoid resource contention
- **Smart encoder fallback** — Tries NVENC → QSV → AMF → VAAPI → VideoToolbox → CPU, picks the first working encoder
- **Per-codec hardware decoder** — H.264/HEVC/VP9/AV1 each probed independently with decoder-specific initialization
- **Subtitle-aware conversion** — Probes subtitles with ffprobe: text subs (SRT/ASS) get converted to mov_text for MP4; image subs (PGS/DVD) get extracted to .sup files
- **Audio compatibility** — Remuxes incompatible audio to AAC; preserves compatible tracks verbatim
- **Safety** — Checks available disk space before starting (input size + 1GB), uses atomic temp → final rename, retries permission errors

### 12. Under-the-Hood Systems

Features that just work without fanfare:

- **Self-signed HTTPS certificate** — Auto-generated on setup via keytool (10-year validity, SAN:localhost). No need for nginx/caddy on single-user installs.
- **Background analysis worker** — Analyzes 2 songs every 10 seconds in the background; automatically pauses when video transcoding is active to avoid CPU contention
- **CPU-aware scan executor** — Scan thread pool auto-sized to `availableProcessors() / 2` (minimum 2)
- **Auth filter intelligence** — 3-tier access (public endpoints > JMEDIA_SESSION cookie > streaming bypass for video elements), IP-based rate limiting on login, sync API key auth for server-to-server
- **Metadata override protection** — Manual edits become permanent — auto-scrapers never overwrite your corrections
- **Queue processor architecture** — 4 background queue processors (metadata enrichment, asset rename, thumbnail generation, subtitle discovery) with rate limiting, retry with exponential backoff, and lifecycle management — no API calls blocked by background work
- **Enhanced authentication** — Rate-limited login (IP-based, blocks on repeated failures), local network detection (bypasses Secure flag on cookies for LAN IPs), Clear-Site-Data on logout
- **Platform-specific dependency management** — Per-OS package manager detection (brew/port/chocolatey/apt), automated install/uninstall of Python/Node/FFmpeg/SpotDL/yt-dlp/Parakeet
- **GPU detection (OS-level)** — Probes NVIDIA/Intel/AMD GPUs at the OS level (separate from ffmpeg encoder probing) for hardware acceleration optimization
- **Video suggestions** — Users can submit content suggestions per profile, stored and served from the server
- **Trending algorithm** — Play count + recency-weighted trending for video discovery

---

## Where JMedia Loses

### 1. Native Apps / TV / Console Support

This is JMedia's biggest gap. Plex and Jellyfin have apps for:

- **Mobile**: Android, iOS native apps with offline sync
- **TV**: LG webOS, Samsung Tizen, Android TV, Apple TV
- **Console**: PlayStation, Xbox
- **Roku**: Both Plex and Jellyfin
- **Desktop**: Plex Desktop, Jellyfin Media Player

JMedia is browser-only. However:
- The responsive web UI works on all mobile/tablet browsers
- AirPlay to Apple TV works via HTML5 Remote Playback API
- Chromecast works via Video.js/OPlayer plugins
- No app store fees, no review process, instant updates

### 2. Plugin Ecosystem

Jellyfin's 30+ plugins cover live TV, anime, manga, eBooks, photos, and more. Plex has limited plugin support. JMedia has none — customization requires forking the repo.

### 3. Polish & Recommendation

Plex's recommendation engine, Watch Together, and curated content are mature. Jellyfin's community polish is also ahead. JMedia focuses on functional completeness over algorithmic discovery.

### 4. Remote Access

Plex Relay makes WAN access trivial. Jellyfin has auto-TLS. JMedia is LAN-focused and needs a reverse proxy for WAN. This is acceptable for its target audience (home users).

---

## Who Should Use What

### Choose JMedia if:
- You want YouTube/Spotify → local library in one click
- AI subtitles matter (generate or translate)
- **You want DJ Mode with beat-matched transitions and EternalJukebox infinite playback**
- You want **all** features without paying a cent
- HEVC playback in browser without server-side transcoding
- You're a developer/enthusiast comfortable with Java/Quarkus
- You want server-to-server sync
- You want metadata written back to audio files

### Choose Plex if:
- You need polished native apps on every device
- Family-friendly UI, Watch Together, shared libraries
- You're willing to pay for Plex Pass
- You want Tidal integration
- Remote access simplicity matters

### Choose Jellyfin if:
- Open source is mandatory (GPL v2 vs Apache 2.0)
- You need a plugin ecosystem
- Hardware transcoding without subscription
- Live TV / DVR is essential
- You want full control over your stack

---

## Pricing Summary

| Feature | Plex | Jellyfin | JMedia |
|---|---|---|---|
| Core server | Free | Free | Free |
| Hardware transcoding | $4.99/mo or $119.99 lifetime | Free | Free |
| Intro/outro skip | $4.99/mo or $119.99 lifetime | Free (plugin) | Free |
| Trailers & extras | $4.99/mo or $119.99 lifetime | Free (plugin) | N/A |
| Downloads/sync | $4.99/mo or $119.99 lifetime | Free | N/A (browser only) |
| Plexamp (BPM) | $4.99/mo or $119.99 lifetime | N/A | Free (full DJ Mode) |
| Music video integration | $4.99/mo or $119.99 lifetime | N/A | Free |
| Server-to-server sync | N/A | N/A | Free |
| AI subtitles (generate + translate) | N/A | N/A | Free |
| Import pipeline (YouTube+Spotify) | N/A | N/A | Free |
| HEVC.js WASM client decode | N/A | N/A | Free |
| Metadata write to audio files | N/A | N/A | Free |

JMedia's model: **Do everything locally, no cloud dependency, no subscription required.**

---

## Verdict

> **Best overall value:** JMedia — zero cost, all features unlocked.
> **Best client ecosystem:** Plex — but requires subscription for advanced features.
> **Best extensibility:** Jellyfin — plugin ecosystem, no paywall.
> **Most innovative:** JMedia — HEVC.js, DJ Mode, EternalJukebox, AI subs, import pipeline are unavailable anywhere else.

For the home user who wants to import + organize + play + DJ their media without subscriptions, JMedia is the only complete solution.

