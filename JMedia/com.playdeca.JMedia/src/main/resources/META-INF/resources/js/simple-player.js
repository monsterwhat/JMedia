// Logging gate: verbose player tracing is off unless localStorage.jmedia_debug
// is "1". warn/error stay unconditional so real failures are never hidden.
(function () {
    var on = false;
    try { on = localStorage.getItem('jmedia_debug') === '1'; } catch (e) { on = false; }
    window.__jmediaDbg = on ? console.log.bind(console) : function () {};
})();

/*
 * Playback recorder for the <video> element.
 *
 * "Choppy" playback has several unrelated causes, and the player kept no record
 * of which one fired, so a report could not be told apart from a decoder that is
 * starving on data, a decoder that is dropping frames, or a main thread that
 * stopped servicing the element. Every entry captures the media state at that
 * moment; dropped-frame counters separate decoder/CPU stalls from network/disk
 * starvation, and the timeupdate gap catches freezes that fire no media event.
 *
 * Read from the console with window.jmediaVideoDiag() after the stutter.
 */
(function () {
    'use strict';

    var LOG = window.__jmediaVideoDiagLog = window.__jmediaVideoDiagLog || [];
    var MAX = 120;

    function snapshot(video, type, extra) {
        var buf = video.buffered;
        var bufferedEnd = buf && buf.length ? buf.end(buf.length - 1) : 0;
        var quality = typeof video.getVideoPlaybackQuality === 'function'
                ? video.getVideoPlaybackQuality() : null;
        var entry = {
            at: new Date().toISOString(),
            type: type,
            t: Math.round((video.currentTime || 0) * 100) / 100,
            readyState: video.readyState,
            networkState: video.networkState,
            bufferedEnd: Math.round(bufferedEnd * 100) / 100,
            paused: video.paused,
            seeking: video.seeking,
            width: video.videoWidth,
            height: video.videoHeight,
            volume: Math.round(video.volume * 100) / 100,
            muted: video.muted,
            droppedFrames: quality ? quality.droppedVideoFrames : null,
            totalFrames: quality ? quality.totalVideoFrames : null
        };
        if (extra) Object.assign(entry, extra);
        return entry;
    }

    function push(video, type, extra) {
        var entry = snapshot(video, type, extra);
        LOG.push(entry);
        if (LOG.length > MAX) LOG.shift();
        return entry;
    }

    function attach(video) {
        if (!video || video.__videoDiagBound) return;
        video.__videoDiagBound = true;

        ['waiting', 'stalled', 'suspend', 'abort', 'error'].forEach(function (type) {
            video.addEventListener(type, function () {
                var entry = push(video, type);
                if ((type === 'waiting' || type === 'stalled') && !video.paused && video.currentTime > 1) {
                    console.warn('[VideoPlayer] playback stalled (' + type + ')', entry);
                }
            });
        });

        ['playing', 'seeked'].forEach(function (type) {
            video.addEventListener(type, function () { push(video, type); });
        });

        // A frozen element fires no waiting event when the main thread is the
        // problem: timeupdate simply stops arriving. Measure the gap.
        var lastTick = 0;
        video.addEventListener('timeupdate', function () {
            var now = performance.now();
            if (lastTick && !video.paused && !video.seeking && now - lastTick > 1500) {
                var entry = push(video, 'judder', { gapMs: Math.round(now - lastTick) });
                console.warn('[VideoPlayer] playback froze for ' + entry.gapMs + ' ms', entry);
            }
            lastTick = now;
        });
    }

    window.__attachVideoPlaybackDiag = attach;

    window.jmediaVideoStats = function (video) {
        var v = video || document.getElementById('videoElement');
        return v ? snapshot(v, 'snapshot') : null;
    };

    window.jmediaVideoDiag = function () {
        return LOG.slice();
    };
})();

if (typeof window.SimplePlayer === 'undefined') {
    window.SimplePlayer = class SimplePlayer {
        constructor(config) {
            window.__jmediaDbg('[SimplePlayer] Initializing...', config);
            this.container = document.getElementById(config.containerId);
            this.video = document.getElementById(config.videoId);
            // Mount race: the SPA injects the fragment and runs this inline script in
            // the same tick as a view transition, so the elements can still be missing
            // here. Retrying is the difference between "the new video never loads until
            // I refresh" and it just working.
            if (!this.container || !this.video) {
                const key = config.containerId + '|' + config.videoId;
                window.__playerMountAttempts = window.__playerMountAttempts || {};
                const attempt = window.__playerMountAttempts[key] =
                        (window.__playerMountAttempts[key] || 0) + 1;
                if (attempt <= 20) {
                    console.warn('[SimplePlayer] Player elements not ready (attempt ' + attempt + '), retrying');
                    setTimeout(function() { new window.SimplePlayer(config); }, 50);
                } else {
                    delete window.__playerMountAttempts[key];
                    console.error('[SimplePlayer] Gave up mounting: player elements never appeared', config);
                }
                return;
            }
            window.__playerMountAttempts = window.__playerMountAttempts || {};
            delete window.__playerMountAttempts[config.containerId + '|' + config.videoId];
            this.videoId = config.currentVideoId;
            this.videoType = this.container.dataset.type || 'movie';
            // Live channels are not backed by a Video row, so server state sync
            // has nothing correct to say about them (see _initWebSocket).
            this._isLive = String(this.videoType).toLowerCase() === 'live';
            this.utils = window.PlayerUtils;

            if (!this.container || !this.video) return;

            this.video.setAttribute('playsinline', 'true');
            this.video.setAttribute('webkit-playsinline', 'true');
            this.video.controls = false;
            this.video.preload = 'auto';
            // Records stalls/dropped frames so "choppy" can be diagnosed from the
            // console (window.jmediaVideoDiag) instead of guessed at.
            if (typeof window.__attachVideoPlaybackDiag === 'function') {
                window.__attachVideoPlaybackDiag(this.video);
            }

            this._applyRoutingFlags();
            this.streamStartOffset = 0;
            this.lastKnownGoodPosition = 0;
            // Per-instance suppression window blocking stale broadcasts during an in-place WS source swap (B9).
            this._swapInProgress = false;
            this._swapSafetyTimer = null;
            this._localSeekAt = 0;
            this._localSeekPos = -1;
            // Timestamp of the last timeupdate: proves the element is actually
            // advancing. The drift-seek below refuses to follow the server state
            // while this is stale (stalled/errored element — phantom clock guard).
            this._lastProgressAt = 0;
            // User-gesture pause gate: once the user explicitly pauses, server
            // state sync must NOT re-assert play() until the user explicitly
            // resumes. This fixes the video analog of the earlier music-WS bug
            // where server playing:true instantly undid a local pause.
            this._userPaused = false;
            this._userPausedAt = 0;
            // Set when the media stream is opened; before that, an audio-track
            // preference is recorded rather than reloaded (see switchAudioTrack).
            this._streamOpened = false;

            this.stateMgr = new window.PlayerStateManager(this);
            this.stateMgr.initState();

            this.streamMgr = new window.PlayerStreamManager(this);
            this.uiBuilder = new window.PlayerUIBuilder(this);
            this.controlsManager = new window.PlayerControlsManager(this);
            this.fullscreenMgr = new window.PlayerFullscreenManager(this);
            this.subtitleController = new window.PlayerSubtitleController(this);
            this.storyboardMgr = new window.PlayerStoryboardManager(this);
            this.skipController = new window.PlayerSkipController(this);
            this.progressReporter = new window.PlayerProgressReporter(this);
            this.keyboardShortcuts = new window.PlayerKeyboardShortcuts(this);
            this.navMgr = new window.PlayerNavigationManager(this);
            this.eventBinder = new window.PlayerEventBinder(this);
            this.audioTrackSelector = new window.PlayerAudioTrackSelector(this);
            this.subtitleSettingsUI = new window.PlayerSubtitleSettingsUI(this);

            window.currentPlayerInstance = this;
            window.player = this;

            this._cleanupOnUnload = () => {
                this.progressReporter.saveNow();
                this.progressReporter.stop();
                if (this._hlsInstance) {
                    this._hlsInstance.destroy();
                    this._hlsInstance = null;
                }
                this.streamMgr.destroyLocalHlsSession();
                this.video.pause();
                this.video.src = "";
                this.progressReporter.setMusicSuspended(false);
            };
            window.addEventListener('pagehide', this._cleanupOnUnload);
            window.addEventListener('beforeunload', this._cleanupOnUnload);

            this.init();

            if (sessionStorage.getItem('jmedia_restore_fullscreen') === 'true') {
                sessionStorage.removeItem('jmedia_restore_fullscreen');
                const restoreFs = () => {
                    const isFullscreen = document.fullscreenElement || document.webkitFullscreenElement;
                    if (this.container && !isFullscreen) {
                        const fsPromise = this.container.requestFullscreen ?
                            this.container.requestFullscreen() :
                            this.video.webkitEnterFullscreen ? Promise.resolve(this.video.webkitEnterFullscreen()) :
                            Promise.resolve();
                        fsPromise.catch(() => {
                            setTimeout(() => {
                                this.container?.requestFullscreen?.()?.catch?.(() => {});
                            }, 1000);
                        });
                    }
                };
                if (this.video.readyState >= 1) {
                    setTimeout(restoreFs, 300);
                } else {
                    this.video.addEventListener('loadedmetadata', () => setTimeout(restoreFs, 300), { once: true });
                }
            }
        }

        /**
         * Per-video routing facts from the container's data attributes (rendered
         * by the server for this video). Re-run whenever the player is pointed at
         * a different video, or the previous video's codec decides the route.
         */
        _applyRoutingFlags() {
            this.needsTranscode = this.container.dataset.needsTranscode === 'true';
            this.needsConversion = this.container.dataset.needsConversion === 'true';
            this.dataHlsEnabled = this.container.dataset.hlsEnabled === 'true';
            this._canNativeHevc = false;
            // HEVC is intentionally NOT overridden client-side. MediaSource's
            // isTypeSupported() reports true on Windows Chromium whenever the OS
            // codec is installed, even when the GPU/driver cannot decode the
            // stream, which produced black players. The server decides HEVC vs
            // H.264 (Windows Chromium is always transcoded to H.264).
            const codec = (this.container.dataset.videoCodec || '').toLowerCase();
            this._canNativeAv1 = false;
            // Browser-native AV1 override: if the server flagged transcode but the
            // browser can play AV1 natively (Chrome 70+, Edge 79+, Firefox 67+),
            // skip the server-side FFmpeg transcode and request the lightweight remux.
            if ((this.needsTranscode || this.needsConversion) && (codec.includes('av1') || codec.includes('av01'))) {
                if (window.PlayerStreamManager && window.PlayerStreamManager.hasNativeAv1Support()) {
                    this.needsTranscode = false;
                    this.needsConversion = false;
                    this._canNativeAv1 = true;
                    window.__jmediaDbg('[SimplePlayer] Browser supports AV1 natively — skipping server transcode');
                }
            }
        }

        /**
         * Re-points the player at another video chosen by the server (another
         * device, auto-advance) without navigating. The previous video's codec,
         * transcode and HLS flags must not decide how the new one is opened, so
         * the server-rendered player page for the new video supplies its data
         * attributes, and the normal open path (_openStreamWhenAudioResolved ->
         * _openStream) picks the route - HLS included, which the old in-place
         * initDirectStream swap never used.
         */
        async _reopenForVideo(newId, startAt) {
            // Stop the old video now; the new page data arrives asynchronously.
            if (this._hlsInstance) {
                try { this._hlsInstance.destroy(); } catch (e) { /* already gone */ }
                this._hlsInstance = null;
            }
            this.streamMgr._teardownSource({ keepHlsSession: false });

            let fresh = null;
            try {
                const res = await fetch('/api/video/ui/playback-fragment?videoId=' + encodeURIComponent(newId),
                        { credentials: 'include' });
                if (res.ok) {
                    const doc = new DOMParser().parseFromString(await res.text(), 'text/html');
                    fresh = doc.querySelector('[data-needs-transcode]');
                } else {
                    console.warn('[SimplePlayer] Player data for video ' + newId + ' returned ' + res.status);
                }
            } catch (e) {
                console.warn('[SimplePlayer] Could not load player data for video ' + newId + ':', e);
            }
            // Superseded by another switch or torn down while fetching.
            if (this._destroyed || String(this.videoId) !== String(newId)) return;

            if (fresh) {
                // Everything the server rendered for the new video, except identity
                // the player already owns.
                Object.keys(fresh.dataset).forEach((key) => {
                    if (key !== 'profileId' && key !== 'startTime') {
                        this.container.dataset[key] = fresh.dataset[key];
                    }
                });
            } else {
                console.warn('[SimplePlayer] Opening video ' + newId + ' with the previous video\'s routing flags');
            }
            const start = startAt > 0 ? startAt : 0;
            this.container.dataset.startTime = String(start);

            // Per-video state the old swap kept from the previous episode.
            const rawDur = parseFloat(this.container.dataset.duration || 0);
            this.totalDuration = rawDur > 5000 ? rawDur / 1000 : rawDur;
            this.markers = {
                introStart: parseFloat(this.container.dataset.introStart || 0),
                introEnd: parseFloat(this.container.dataset.introEnd || 0),
                outroStart: parseFloat(this.container.dataset.outroStart || 0),
                outroEnd: parseFloat(this.container.dataset.outroEnd || 0),
                recapStart: parseFloat(this.container.dataset.recapStart || 0),
                recapEnd: parseFloat(this.container.dataset.recapEnd || 0)
            };
            this.originalMarkers = { ...this.markers };
            this._autoSkippedSections = new Set();
            this._outroSkipDisabled = false;
            this.currentAudioTrackIndex = null;
            this.storyboard = { metadata: null, loaded: false };
            this._storyboardLoadScheduled = false;
            this._storyboardLoaded = false;
            this.streamStartOffset = 0;
            this.lastKnownGoodPosition = 0;
            this._hasPlayedData = false;
            this._streamFallbackCount = 0;
            this._healthySince = 0;
            // Direct files resume with a client-side seek on loadedmetadata; the
            // transcode and HLS routes start the stream at dataset.startTime.
            this.initialResumeTime = start;
            this._applyRoutingFlags();

            // Not open yet: the saved audio preference for the new video is then
            // recorded and carried by the single open request, instead of
            // switchAudioTrack reloading a stream that is about to be opened.
            this._streamOpened = false;
            this._openStreamWhenAudioResolved();
        }

        _isIOS() { return this.utils.isIOS(); }
        _isMac() { return this.utils.isMac(); }
        formatTime(s) { return this.utils.formatTime(s); }

        init() {
            this.uiBuilder.build();
            this.eventBinder.bind();
            this.controlsManager.applyInitialState();
            this.controlsManager.updateSubtitle();

            this._boundKeydown = (e) => this.keyboardShortcuts.handleKeydown(e);
            window.addEventListener('keydown', this._boundKeydown);

            // Defer the WebSocket connection until the first `playing` event so
            // init stays off the critical path. One-shot listener; the _isLive
            // guard and onStateUpdate/onCommand handlers live inside _initWebSocket.
            const deferWsInit = () => { if (!this._destroyed) this._initWebSocket(); };
            this.video.addEventListener('playing', deferWsInit, { once: true });

            this.externalUrl = this.container.dataset.externalUrl || null;
            this.externalOriginalUrl = this.container.dataset.externalOriginalUrl || null;
            this.externalId = this.container.dataset.externalId || null;
            if (this.externalUrl) {
                this.streamMgr.initExternalStream();
                return;
            }

            // Resolve the saved audio preference BEFORE the first stream request.
            // Opening the stream first and switching tracks ~250 ms later sends a
            // second request with a different audio index; the server keys transcode
            // cache files by that index, so the second request supersedes (kills) the
            // first one's writer mid-write and leaves a dead partial segment behind.
            this._openStreamWhenAudioResolved();
        }

        /**
         * Waits (bounded) for the audio track list and the saved preference, then
         * opens the stream once, already carrying the resolved track index.
         * Degrades to opening immediately if the tracks never resolve, which is the
         * previous behaviour — a late preference then switches tracks normally.
         */
        _openStreamWhenAudioResolved() {
            let opened = false;
            const open = () => {
                if (opened || this._destroyed) return;
                opened = true;
                this._openStream();
            };
            // Start hls.js in parallel with audio-track resolution for the HLS
            // route. It is ~542 KB and used to load serially right before the
            // session POST, adding ~0.5-0.7s to time-to-first-frame. Firefox plays
            // the direct fMP4 path, so skip the download there. ensureHlsLoaded()
            // is cached, so the later call inside _openStream reuses this request.
            const audioCodec = (this.container.dataset.audioCodec || '').toLowerCase();
            const audioNeedsHls = audioCodec !== '' && !/(aac|mp3|opus|flac|vorbis)/.test(audioCodec);
            const isFirefox = /Firefox\//i.test(navigator.userAgent || '');
            if (!isFirefox && !window.Hls && typeof window.ensureHlsLoaded === 'function'
                && (this.needsTranscode || audioNeedsHls
                    || this.dataHlsEnabled || this.container.dataset.hlsForced === 'true')) {
                window.ensureHlsLoaded().catch(function () {
                    // hls-loader already logs the failure; _openStream falls back.
                });
            }
            const selector = this.audioTrackSelector;
            if (!selector || typeof selector.init !== 'function') {
                open();
                return;
            }
            const cap = setTimeout(() => {
                console.warn('[SimplePlayer] Audio tracks slow to resolve; opening stream with the current selection');
                open();
            }, 1500);
            Promise.resolve()
                .then(() => selector.init())
                .catch(e => console.warn('[SimplePlayer] Audio track resolution failed; opening stream anyway:', e))
                .then(() => { clearTimeout(cap); open(); });
        }

        /** Opens the media stream. Run once, after the audio preference is known. */
        async _openStream() {
            this._streamOpened = true;
            // New video/open: drop stale per-stream flags from any previous load.
            this._truncatedStream = false;
            this._streamOriginAuthoritative = false;
            const savedTime = parseFloat(this.container.dataset.startTime || 0);
            // Hybrid routing:
            //  - faststart H.264/AAC MP4 -> progressive <video src> (works everywhere)
            //  - anything that must be remuxed/transcoded -> HLS + hls.js (MSE),
            //    because Chrome's progressive demuxer does not start on the
            //    empty_moov fragmented MP4 the server produces for live transcodes.
            // An audio track that needs transcoding also produces fMP4, so it
            // takes the HLS path too. The profile flag still forces HLS on.
            const audioCodec = (this.container.dataset.audioCodec || '').toLowerCase();
            const audioNeedsHls = audioCodec !== '' && !/(aac|mp3|opus|flac|vorbis)/.test(audioCodec);
            // Firefox plays the server's fragmented-MP4 transcode progressively and
            // seeks accurately through ?start= (X-Stream-Origin), which starts nearly
            // instantly and does not rebuild an HLS session per seek. Chromium's
            // progressive demuxer stalls on our empty_moov fMP4, so it keeps HLS.
            // Explicit profile HLS (?hls=1) still wins everywhere.
            const isFirefox = /Firefox\//i.test(navigator.userAgent || '');
            let wantsHls = this.dataHlsEnabled
                || this.container.dataset.hlsForced === 'true'
                || ((this.needsTranscode || audioNeedsHls) && !isFirefox);
            // hls.js is ~542 KB and is only needed for the HLS route. Load it here,
            // at the first point where the answer is known, instead of shipping it
            // in every playback fragment.
            window.__jmediaDbg('[SimplePlayer] Route ' + (wantsHls ? 'HLS/MSE' : 'direct') +
                ' (needsTranscode=' + this.needsTranscode + ', audioCodec=' + (audioCodec || 'unknown') +
                ', firefox=' + isFirefox +
                ', hlsForced=' + (this.container.dataset.hlsForced === 'true') +
                ', profileHlsFlag=' + this.dataHlsEnabled + ')');
            // Some browser/OS combinations (Windows N without the Media Feature
            // Pack, or Chromium builds without proprietary codecs) cannot decode
            // H.264 in MSE at all — only AV1/Opus. In that case every HLS
            // variant fails with manifestIncompatibleCodecsError and the direct
            // fallback also stalls, producing an infinite retry loop. Detect it
            // up front and tell the user how to fix their environment instead.
            const hasH264Mse = (typeof MediaSource !== 'undefined') &&
                MediaSource.isTypeSupported('video/mp4; codecs="avc1.42E01E"');
            // MSE support is not the same as decode support. A browser can lack a
            // working MSE H.264 path (iOS WebKit, Windows N, some Chromium builds)
            // and still decode H.264 in a plain <video>. Ask the element too, and
            // only give up when NEITHER path exists - otherwise route around MSE
            // instead of aborting with a false "cannot decode H.264".
            let canNativeH264 = false;
            try {
                canNativeH264 = document.createElement('video')
                    .canPlayType('video/mp4; codecs="avc1.42E01E"') !== '';
            } catch (e) {
                console.warn('[SimplePlayer] native H.264 probe failed:', e);
            }
            const isIOSRuntime = this.utils.isIOS();
            window.__jmediaDbg('[SimplePlayer] codec probe: mseH264=' + hasH264Mse + ' nativeH264=' + canNativeH264 +
                ' isIOS=' + isIOSRuntime + ' hls=' + wantsHls);
            if (wantsHls && !hasH264Mse && !isIOSRuntime && !canNativeH264) {
                console.error('[SimplePlayer] H.264 MSE unsupported on this browser/OS; aborting playback');
                this._showLoading('Playback unavailable: this browser/OS cannot decode H.264. ' +
                    'On Windows N, install the Media Feature Pack; otherwise use Chrome stable.');
                return;
            }
            if (wantsHls && !hasH264Mse && !isIOSRuntime && canNativeH264) {
                // No MSE for our fMP4, but the element decodes H.264 itself: use the
                // progressive/transcode route instead of declaring the device broken.
                console.warn('[SimplePlayer] no H.264 MSE support; using progressive playback instead of HLS');
                wantsHls = false;
            }
            if (wantsHls && !isIOSRuntime && typeof window.ensureHlsLoaded === 'function' && !window.Hls) {
                try {
                    await window.ensureHlsLoaded();
                } catch (e) {
                    // Logged in hls-loader; fall back to the progressive/transcode path.
                    console.error('[SimplePlayer] hls.js unavailable, falling back to non-HLS playback', e);
                }
            }
            // iOS plays HLS natively (HEVC included) and never through hls.js: it
            // has no usable MSE for our fMP4, so Hls.isSupported() is false there.
            if (wantsHls && (isIOSRuntime || (typeof Hls !== 'undefined' && Hls.isSupported()))) {
                // Local HLS session: the server starts the encoder at `savedTime`, so
                // element time 0 is that content position. streamStartOffset carries the
                // offset so the clock and subtitle ?start= stay absolute; the element is
                // seeked to 0 after MANIFEST_PARSED (see StreamManager contentOffset).
                this.streamStartOffset = savedTime > 0 ? savedTime : 0;
                this.streamMgr.initLocalHlsStream(savedTime);
                this.subtitleController.loadSubtitles();
            } else if (this.needsTranscode || audioNeedsHls) {
                const setupStream = (startAt) => {
                    const target = startAt > 0 ? startAt : null;
                    const streamUrl = this.streamMgr.buildStreamUrl(target);
                    this.streamStartOffset = startAt > 0 ? startAt : 0;
                    // The element may begin earlier than the requested position (a copy
                    // seek starts at the previous keyframe, and a seek into an existing
                    // segment starts deep inside that segment's own timeline). Use the
                    // origin the server reports so the clock and subtitle offset match
                    // the picture instead of the request.
                    const probeVideoId = this.videoId;
                    this.streamMgr.resolveStreamOrigin(streamUrl).then((origin) => {
                        if (this._destroyed || this.videoId !== probeVideoId) return;
                        if (origin !== null) {
                            this.streamStartOffset = origin;
                            this._streamOriginAuthoritative = true;
                        }
                        // Assign src first (with the origin probe as today), then
                        // load subtitles. Subtitle preload is NOT awaited on the
                        // critical path; kick it off (unawaited) after src is set.
                        this.video.src = streamUrl;
                        this.streamMgr._preloadSubtitleTracks()
                            .catch(e => console.warn('[SimplePlayer] Subtitle preload failed:', e));
                        this.subtitleController.loadSubtitles();
                        this.video.addEventListener('playing', () => this.streamMgr._clearPlaybackWatchdog(), { once: true });
                        // Some HEVC transcode failures never fire a `video` error;
                        // escalate to H.264 if playback has not started in time.
                        this.streamMgr._armPlaybackWatchdog('transcode', () => {
                            if (this._destroyed || this._userPaused) return;
                            this.streamMgr.disableClientCodecs();
                            this.video.src = this.streamMgr.buildStreamUrl(target, { extraParams: ['forceTranscode=true'], audioCodecs: '' });
                            this.video.load();
                            this.video.play().catch(function(e) {
                                console.error('[SimplePlayer] Transcode watchdog retry play failed', e);
                            });
                        }, 15000);
                        this.video.play().then(() => {
                        }).catch(e => {
                            console.warn('[SimplePlayer] Autoplay blocked, waiting for a user gesture:', e);
                        });
                    });
                };
                const setupStreamErrorHandler = () => {
                    if (this._destroyed || !document.body.contains(this.container)) return;
                    this._streamFallbackCount = (this._streamFallbackCount || 0) + 1;
                    console.error('[SimplePlayer] Direct stream error (fallback ' + this._streamFallbackCount + '/' + this._maxStreamFallbacks + '):', this.video.error);
                    if (this._streamFallbackCount < this._maxStreamFallbacks) {
                        this._showLoading('Stream error, retrying...');
                        // Retry where the viewer is, not where the stream was opened:
                        // an error 40 minutes in used to jump back to the resume point.
                        const retryAt = this._hasPlayedData ? this._currentAbsolutePosition() : savedTime;
                        setTimeout(() => {
                            if (this._destroyed || !document.body.contains(this.container)) return;
                            setupStream(retryAt);
                        }, 1000);
                    } else {
                        this._showLoading('Playback failed - try reloading');
                    }
                };
                this._setupStreamErrorHandler = setupStreamErrorHandler;
                this.video.addEventListener('error', this._setupStreamErrorHandler);
                setupStream(savedTime);
            } else {
                /* Load from beginning; client-side seek in loadedmetadata handles resume.
                   Server-side ?start= can fail for direct streams, causing the progress bar
                   to show the resume time while the video is actually at 0:00. */
                this.streamStartOffset = 0;
                const extraParams = [];
                if (this._canNativeHevc) extraParams.push('nativeHevc=1');
                if (this._canNativeAv1) extraParams.push('nativeAv1=1');
                this.video.src = this.streamMgr.buildStreamUrl(null, { extraParams });
                this.subtitleController.loadSubtitles();
                this.video.addEventListener('playing', () => this.streamMgr._clearPlaybackWatchdog(), { once: true });
                // Guards the copy path against a silent HEVC decode stall that
                // never raises a `video` error on some Windows Chromium builds.
                this.streamMgr._armPlaybackWatchdog('copy', () => {
                    if (this._destroyed || this._userPaused) return;
                    // The copy attempt just failed to decode; never advertise the
                    // codec again or the server will copy the same unplayable stream.
                    this.streamMgr.disableClientCodecs();
                    this.video.src = this.streamMgr.buildStreamUrl(null, { extraParams: ['forceTranscode=true'], audioCodecs: '' });
                    this.video.load();
                    this.video.play().catch(function(e) {
                        console.error('[SimplePlayer] Copy watchdog retry play failed', e);
                    });
                }, 15000);
                // F2a: Attach error handler for plain direct stream (was only on transcode path)
                var self = this;
                this._setupStreamErrorHandler = function() {
                    if (self._destroyed || !document.body.contains(self.container)) return;
                    self._streamFallbackCount = (self._streamFallbackCount || 0) + 1;
                    console.error('[SimplePlayer] Direct stream error (fallback ' + self._streamFallbackCount + '/' + self._maxStreamFallbacks + '):', self.video.error);
                    if (self._streamFallbackCount < self._maxStreamFallbacks) {
                        self._showLoading('Stream error, retrying...');
                        // Where to resume: the current position once playback has run,
                        // otherwise the pending resume point. Restarting at 0 (as before)
                        // threw the viewer back to the start of the movie.
                        const retryAt = self._hasPlayedData
                            ? self._currentAbsolutePosition()
                            : (self.initialResumeTime > 0 ? self.initialResumeTime : savedTime);
                        setTimeout(function() {
                            if (self._destroyed || !document.body.contains(self.container)) return;
                            // A forced transcode honours ?start=, so the element's 0 is
                            // retryAt; no client-side resume seek is needed afterwards.
                            self.streamStartOffset = retryAt > 0 ? retryAt : 0;
                            self.initialResumeTime = 0;
                            // Escalate, don't repeat: the copy/remux already failed once,
                            // so drop any native-codec bypass and force a server transcode.
                            self.streamMgr.disableClientCodecs();
                            self.video.src = self.streamMgr.buildStreamUrl(retryAt > 0 ? retryAt : null,
                                { extraParams: ['forceTranscode=true'], audioCodecs: '' });
                            self.video.load();
                            self.video.play().catch(function (e) {
                                console.warn('[SimplePlayer] play() after transcode escalation was blocked:', e);
                            });
                        }, 1000);
                    } else {
                        self._showLoading('Playback failed - try reloading');
                    }
                };
                this.video.addEventListener('error', this._setupStreamErrorHandler);
                this.video.play().then(() => {
                }).catch(e => {
                    console.warn('[SimplePlayer] Autoplay blocked, waiting for a user gesture:', e);
                });
            }

            // Defer storyboard loading off the critical path: run after the first
            // `playing` event, or after 2s idle, whichever comes first.
            if (!this._storyboardLoadScheduled) {
                this._storyboardLoadScheduled = true;
                const loadSb = () => {
                    if (this._destroyed || this._storyboardLoaded) return;
                    this._storyboardLoaded = true;
                    try { this.storyboardMgr.loadStoryboard(); } catch (e) { console.warn('[SimplePlayer] Storyboard load failed:', e); }
                };
                this.video.addEventListener('playing', loadSb, { once: true });
                setTimeout(loadSb, 2000);
            }
            this.progressReporter.setMusicSuspended(true);
            this.progressReporter.start();
            this.controlsManager.updateMarkers();
            this.controlsManager.checkMarkers();

            if (window.subtitleManager) {
                window.subtitleManager.bindVideo(this.video, this.container);
            }

            this.debugInfo = {
                seriesTitle: this.container.dataset.seriesTitle || '',
                seasonNumber: parseInt(this.container.dataset.seasonNumber || '1'),
                episodeNumber: parseInt(this.container.dataset.episodeNumber || '0'),
                seriesImdbId: this.container.dataset.seriesImdbId || ''
            };

            const allZero = Object.values(this.markers).every(v => v === 0);
            if (allZero && (this.videoType === 'Episode' || this.videoType === 'episode')) {
                this.stateMgr.refreshMarkers();
            }

            this.controlsManager.showControls();
            this.controlsManager.updatePageTitle();
        }

        updatePageTitle() { this.controlsManager.updatePageTitle(); }
        updateSubtitle() { this.controlsManager.updateSubtitle(); }
        refreshMarkers(retries) { this.stateMgr.refreshMarkers(retries); }
        forceRefreshEpisode() { this.stateMgr.forceRefreshEpisode(); }
        forceRefreshSeason() { this.stateMgr.forceRefreshSeason(); }
        forceRefreshShow() { this.stateMgr.forceRefreshShow(); }
        _triggerDebugRefresh(type, apiCall) { this.stateMgr._triggerDebugRefresh(type, apiCall); }

        initDirectStream(savedTime) { this.streamMgr.initDirectStream(savedTime); }
        initExternalStream() { this.streamMgr.initExternalStream(); }
        fallbackToDirectStream(savedTime) { this.streamMgr.fallbackToDirectStream(savedTime); }

        loadAudioTrackSelector() { this.streamMgr.loadAudioTrackSelector(); }
        switchAudioTrack(trackIndex) { this.streamMgr.switchAudioTrack(trackIndex); }
        setAudioTrack(trackId) { this.streamMgr.setAudioTrack(trackId); }
        getAudioTracks() { return this.streamMgr.getAudioTracks(); }
        applyAudioPreference() { this.stateMgr.applyAudioPreference(); }

        buildUI() { this.uiBuilder.build(); }

        selectSubtitle(trackId, element) { this.subtitleController.selectSubtitle(trackId, element); }
        attachEvents() { this.eventBinder.bind(); }
        handleKeydown(e) { this.keyboardShortcuts.handleKeydown(e); }

        handleMouseMove(e) { this.storyboardMgr.handleMouseMove(e); }
        showControls() { this.controlsManager.showControls(); }
        applyInitialState() { this.controlsManager.applyInitialState(); }
        updateVolumeUI() { this.controlsManager.updateVolumeUI(); }
        updateMarkers() { this.controlsManager.updateMarkers(); }
        checkMarkers() { this.controlsManager.checkMarkers(); }
        _updateDebugDialog() { this.controlsManager._updateDebugDialog(); }
        toggleDebugDialog() { this.controlsManager.toggleDebugDialog(); }
        closeDebugDialog() { this.controlsManager.closeDebugDialog(); }
        switchSettingsPage(page) { this.controlsManager.switchSettingsPage(page); }

        saveAndReload() { this.stateMgr.saveAndReload(); }

        _checkAutoSkip(t) { this.skipController.checkAutoSkip(t); }
        _performAutoSkip(section, start, end) { this.skipController._performAutoSkip(section, start, end); }
        _showAutoSkipNotice(section) { this.skipController._showAutoSkipNotice(section); }
        _undoAutoSkip() { this.skipController._undoAutoSkip(); }
        _disableAutoSkip(section) { this.skipController._disableAutoSkip(section); }
        _postAutoSkipSetting(section, enabled) { this.stateMgr._postAutoSkipSetting(section, enabled); }

        turnOffSubtitles() { this.subtitleController.turnOffSubtitles(); }
        destroyAssSubtitle() { this.subtitleController.destroyAssSubtitle(); }
        initAssSubtitle(trackId) { return this.subtitleController.initAssSubtitle(trackId); }
        loadSubtitles(keepMenuOpen) { return this.subtitleController.loadSubtitles(keepMenuOpen); }

        loadStoryboard() { this.storyboardMgr.loadStoryboard(); }

        startProgressReporting() { this.progressReporter.start(); }
        saveProgressNow() { this.progressReporter.saveNow(); }
        _reportProgress(time, playing) { this.progressReporter._reportProgress(time, playing); }
        performServerSeek(time) { this.streamMgr.performServerSeek(time); }
        setMusicSuspended(s) { this.progressReporter.setMusicSuspended(s); }

        goBack() { this.navMgr.goBack(); }
        goToDetails() { this.navMgr.goToDetails(); }
        playNextEpisode() { return this.navMgr.playNextEpisode(); }
        playPreviousEpisode() { return this.navMgr.playPreviousEpisode(); }

        _doServerSeek(time) { this.streamMgr._doServerSeek(time); }
        _showLoading(msg) { if (window.Toast) window.Toast.info(msg); }
        _hideLoading() {
            const container = document.getElementById('toast-container');
            if (container) container.querySelectorAll('.toast.info').forEach(t => t.remove());
        }

        clearStreamErrorHandlers() { this.streamMgr.clearStreamErrorHandlers(); }
        _preloadSubtitleTracks() { return this.streamMgr._preloadSubtitleTracks(); }
        _syncSubtitleForNativeFullscreen() { this.subtitleController.syncForNativeFullscreen(); }
        _restoreSubtitlesAfterFullscreen() { this.subtitleController.restoreAfterFullscreen(); }

        _initWebSocket() {
            if (this._destroyed) return;
            if (this._wsInitialized) return;
            if (!window.VideoWebSocketManager) return;
            // A live channel has no Video row, so the server's "currentVideoId"
            // is whatever movie or episode was watched last. The source-swap in
            // onStateUpdate below treats that as "the server selected a new
            // video" and calls initDirectStream(), which replaces the channel
            // with that recording — exactly the "I clicked a channel and it
            // played the film I was watching" bug. Live also has no position to
            // restore and no progress to report, so it stays out of state sync.
            if (this._isLive) {
                window.__jmediaDbg('[SimplePlayer] Live channel — server state sync disabled');
                this._wsInitialized = true;
                return;
            }
            const profileId = this.container.dataset.profileId || window.globalActiveProfileId || localStorage.getItem('activeProfileId');
            if (!profileId) {
                if (!window.profileInitialized) {
                    document.body.addEventListener('profileReady', () => this._initWebSocket(), { once: true });
                }
                return;
            }

            this._wsManager = new window.VideoWebSocketManager({
                profileId: profileId,
                onStateUpdate: (state) => {
                    if (!state || this._destroyed) return;
                    this._applyingServerState = true;
                    try {
                        // Source-reload: if server selected a different video, reinit player for the new source.
                        // Never for a live channel — see the _isLive guard in _initWebSocket().
                        if (!this._isLive && state.currentVideoId && state.currentVideoId.toString() !== this.videoId.toString()) {
                            const newId = state.currentVideoId;
                            const newStateTime = typeof state.currentTime === 'number' ? state.currentTime : 0;

                            // Suppress stale broadcasts (timeupdate/pause/play) from the old element
                            // while the new source settles; cleared on playing/loadeddata/error or 10s timeout.
                            this._swapInProgress = true;
                            if (this._swapSafetyTimer) clearTimeout(this._swapSafetyTimer);
                            this._swapSafetyTimer = setTimeout(() => this._clearSwapInProgress(), 10000);

                            // Drop the old element's error/retry handlers so the new source gets a
                            // fresh retry budget via the StreamManager machinery (initDirectStream).
                            this.streamMgr.clearStreamErrorHandlers();
                            this._streamFallbackCount = 0;

                            // New episode starts fresh — never inherit the old episode's position.
                            // Transcode resumes via server-side ?start= (streamStartOffset carries
                            // the offset); direct files start at 0 and seek client-side below.
                            this.videoId = newId;
                            // New video: drop stale per-stream flags inherited from the
                            // previous episode (truncated-stream latch and origin authority).
                            this._truncatedStream = false;
                            this._streamOriginAuthoritative = false;
                            // The pause intent belonged to the previous video. Carrying it
                            // over means the new episode starts "user paused" and every
                            // server play is ignored ("Blocked server play — user pause
                            // active"), so the switch looks like a dead player.
                            this._userPaused = false;
                            this._userPausedAt = 0;
                            // Track ids are per video: carrying the previous episode's id
                            // made loadSubtitles() auto-select a track that belongs to a
                            // different video.
                            this.lastSelectedTrackId =
                                localStorage.getItem('jmedia_last_track_' + newId)
                                || sessionStorage.getItem('jmedia_global_subtitle_track')
                                || null;
                            // New video, new markers: let its own intro/outro skip fire.
                            this._autoSkipGuard = null;
                            // Keep the page chrome in sync: the player re-points itself at
                            // the next episode without navigating, so anything reading the
                            // container dataset (episode sidebar highlight, subtitle panel
                            // video id) still pointed at the previous episode.
                            this.container.dataset.videoId = String(newId);
                            if (typeof window.syncEpisodeSidebarForVideo === 'function') {
                                window.syncEpisodeSidebarForVideo(newId);
                            }
                            // The player re-points itself at a new video, so its header must
                            // follow: the title and logo were rendered for the previous
                            // episode and stayed there (switching from Now Playing changed
                            // the stream but not the chrome).
                            try {
                                const titleEl = document.getElementById('player-modal-title');
                                if (titleEl) {
                                    const label = (state.seriesTitle && state.seasonNumber && state.episodeNumber)
                                        ? state.seriesTitle + ' S' + state.seasonNumber + 'E' + state.episodeNumber
                                          + (state.episodeTitle ? ' \u2014 ' + state.episodeTitle : '')
                                        : (state.title || '');
                                    if (label) titleEl.textContent = label;
                                }
                                const logoEl = document.getElementById('modal-logo-hero-img');
                                if (logoEl) {
                                    logoEl.onerror = function () { logoEl.style.display = 'none'; };
                                    logoEl.onload = function () { logoEl.style.display = ''; };
                                    logoEl.src = '/api/video/logo/' + newId;
                                }
                            } catch (e) {
                                console.warn('[SimplePlayer] Could not refresh the player header:', e);
                            }
                            // Offset, resume point and the rest of the per-video state are
                            // reset in _reopenForVideo, from the NEW video's data.

                            // Restore volume/mute from localStorage
                            const vol = parseFloat(localStorage.getItem('jmedia_video_volume_' + profileId) || '0.7');
                            const mute = localStorage.getItem('jmedia_video_mute_' + profileId) === 'true';
                            // The slider stores the perceptual value and the element takes
                            // its square (ControlsManager.applyInitialState). Applying the
                            // raw value here made every episode swap play louder than the
                            // slider said until the slider was touched again.
                            this.video.volume = Math.pow(vol, 2);
                            this.video.muted = mute;
                            this.state.volume = vol;
                            this.state.muted = mute;
                            if (this.controlsManager) this.controlsManager.updateVolumeUI();

                            // Suppression exit: playing/loadeddata/error, or the 10s safety timeout.
                            // On playing, send ONE confirmation broadcast so the server playback
                            // timer restarts with the new id (B6) — it now passes the swap guard.
                            this.video.addEventListener('playing', () => {
                                this._clearSwapInProgress();
                                if (!this._destroyed) this._broadcastState();
                            }, { once: true });
                            this.video.addEventListener('loadeddata', () => this._clearSwapInProgress(), { once: true });
                            this.video.addEventListener('error', () => this._clearSwapInProgress(), { once: true });

                            // Open the new video through the normal routing (HLS, transcode or
                            // direct) using ITS flags; resumes at newStateTime (selectVideo) or
                            // 0 (advanceVideo). The old initDirectStream swap used the previous
                            // video's needsTranscode/codec flags and never took the HLS route.
                            this._reopenForVideo(newId, newStateTime);
                            if (!this._swapInProgress && !state.playing) {
                                try { this.video.pause(); } catch (e) {
                                    console.warn('[SimplePlayer] pause during source swap failed', e);
                                }
                            }
                            return;  // still hits the finally block
                        }
                        if (!this._swapInProgress) {
                        if (state.playing && this.video.paused) {
                            if (this._userPaused) {
                                window.__jmediaDbg('[SimplePlayer] Blocked server play — user pause active (stateSync playing=true ignored, _userPausedAt=' + this._userPausedAt + ')');
                            } else {
                                window.__jmediaDbg('[SimplePlayer] Resuming playback from server state (playing=true)');
                                this.video.play().catch(function(e) { console.error('[SimplePlayer] Server-triggered play failed', e); });
                            }
                        } else if (!state.playing && !this.video.paused) {
                            window.__jmediaDbg('[SimplePlayer] Pausing playback from server state (playing=false)');
                            try { this.video.pause(); window.__jmediaDbg('[SimplePlayer] video.pause() issued (server state)'); } catch (e) { console.error('[SimplePlayer] Server-triggered pause failed', e); }
                        }
                        }
                        // Drift protection skipped during a swap window: the new element sits at 0
                        // while the server timer broadcasts growing time, which would seek-yank it.
                        if (!this._swapInProgress && typeof state.currentTime === 'number') {
                            // Only follow server truth while this player is genuinely
                            // progressing. A stalled element (autoplay rejection, failed
                            // load, endless buffering) emits no timeupdate; yanking it to
                            // the server's phantom clock (which advances whenever a client
                            // is silent >1500ms) causes seek storms and repeated
                            // server-side transcodes. Remote seeks still sync: a healthy
                            // player fires timeupdate ~4x/sec, keeping the gate open.
                            const progressAge = Date.now() - this._lastProgressAt;
                            if (!this.video.paused && progressAge < 8000) {
                                // Server time is absolute; this element is relative (0-based) for ?start= streams.
                                const target = state.currentTime - (this.streamStartOffset || 0);
                                // S4: phantom-behind guard — when the server absolute
                                // clock is behind this element's stream window start,
                                // target goes negative; yanking currentTime backwards
                                // past the buffer frontier stalls playback.
                                if (target < 0) return;
                                const dur = this.video.duration;
                                const drift = Math.abs(this.video.currentTime - target);
                                const lockAge = Date.now() - this._localSeekAt;
                                const locked = this._localSeekPos >= 0 && lockAge < 3000;
                                const converged = locked && Math.abs(target - this._localSeekPos) < 5;
                                if (converged) this._localSeekPos = -1;
                                // S5: cap forward yanks so the server phantom clock (or a stale
                                // broadcast) can't jump playback ahead of real elapsed time — a
                                // forward yank is what inflated the resume point / force-plays.
                                // Backward corrections and genuine remote (locked) seeks still apply.
                                const MAX_FORWARD_YANK = 5;
                                const yankIsForward = (target - this.video.currentTime) > MAX_FORWARD_YANK;
                                if (drift > 3 && (!yankIsForward || locked) && (!locked || converged)) {
                                    // Bound to loaded duration: seeking a
                                    // data-less element past its end fires spurious 'ended'.
                                    this.video.currentTime = (isFinite(dur) && dur > 0 && target > dur - 1) ? dur - 1 : target;
                                }
                            }
                        }
                    } finally {
                        this._applyingServerState = false;
                    }
                },
                onCommand: (msg) => {
                    window.__jmediaDbg('[SimplePlayer onCommand]', msg.type, msg.payload);
                    var cmd = msg;
                    if (msg && msg.type === 'command' && msg.payload) {
                        cmd = { type: msg.payload.commandType, payload: msg.payload.commandPayload || {} };
                    }
                    var ctype = cmd.type;
                    if (this._destroyed) return;
                    // B3: suppress local play/pause/seek event echo-back to the
                    // server while handling a server-originated command (plain
                    // save/restore — no try/finally spanning async select helpers).
                    var prevAppState = this._applyingServerState;
                    this._applyingServerState = true;
                    try {
                    if (ctype === 'select-subtitle') {
                        const idx = cmd.payload && cmd.payload.index;
                        if (idx == null) return;
                        const maxAttempts = 25;
                        let attempt = 0;
                        const trySelect = () => {
                            if (this._destroyed) return;
                            if (idx === -1) { this.turnOffSubtitles && this.turnOffSubtitles(); return; }
                            const options = this.container.querySelectorAll('.subtitle-option:not(#sub-off)');
                            if (options.length > 0 && options[idx]) {
                                options[idx].click();
                                return;
                            }
                            if (attempt < maxAttempts) {
                                attempt++;
                                setTimeout(trySelect, 200);
                            }
                        };
                        trySelect();
                    } else if (ctype === 'select-audio') {
                        const idx = cmd.payload && cmd.payload.index;
                        if (idx == null) return;
                        const maxAttempts = 25;
                        let attempt = 0;
                        const trySelect = () => {
                            if (this._destroyed) return;
                            const tracks = this.streamMgr?.getAudioTracks?.() || [];
                            if (tracks.length > 0 && tracks[idx]) {
                                const track = tracks[idx];
                                // Same path as picking it in the menu: resolves the DB id
                                // to the ffprobe stream index, updates the menu and saves
                                // the preference. setAudioTrack(id) took the DB id itself
                                // as the stream index and switched to the wrong track.
                                if (track.trackIndex !== undefined && this.audioTrackSelector
                                        && typeof this.audioTrackSelector.selectTrack === 'function') {
                                    this.audioTrackSelector.selectTrack(track.id);
                                } else {
                                    console.warn('[SimplePlayer] Remote audio selection: track ' + idx
                                        + ' has no server stream index; ignoring');
                                }
                                return;
                            }
                            if (attempt < maxAttempts) {
                                attempt++;
                                setTimeout(trySelect, 200);
                            }
                        };
                        trySelect();
                    } else if (ctype === 'toggle-play') {
                        if (this._destroyed) return;
                        if (this.video.paused) {
                            window.__jmediaDbg('[SimplePlayer] Remote toggle-play -> play (clearing user-pause gate)');
                            this._userPaused = false; this._userPausedAt = 0;
                            this.video.play().catch(function(e) { console.error('[SimplePlayer] Remote play failed', e); });
                        } else {
                            window.__jmediaDbg('[SimplePlayer] Remote toggle-play -> pause (engaging user-pause gate)');
                            this._userPaused = true; this._userPausedAt = Date.now();
                            try { this.video.pause(); window.__jmediaDbg('[SimplePlayer] video.pause() issued (remote toggle)'); } catch (e) { console.error('[SimplePlayer] Remote pause failed', e); }
                        }
                    } else if (ctype === 'seek') {
                        if (this._destroyed) return;
                        const t = cmd.payload && cmd.payload.value;
                        if (typeof t === 'number' && isFinite(t)) {
                            // S3: route through frontier-aware server-seek instead of
                            // raw element seek — native-seeks in-buffer, reloads
                            // via ?start= past-buffer (prevents stall on past-buffer targets).
                            this.performServerSeek(t);
                        }
                    }
                    } finally {
                        this._applyingServerState = prevAppState;
                    }
                }
            });

            this._wsManager.connect();
            this._wsInitialized = true;
        }

        /**
         * True while this player can still be acted on. Any async continuation that
         * touches the element (play, src, seek) must check this: a torn-down player's
         * element is detached but still able to make sound.
         */
        _isAlive() {
            return !this._destroyed && !!this.container && document.body.contains(this.container);
        }

        /**
         * Absolute content position the viewer is at. The element's clock is
         * relative for a ?start= stream, so add the stream origin. Uses the last
         * position that actually played: after an error, currentTime can already
         * be reset.
         */
        _currentAbsolutePosition() {
            const rel = this.lastKnownGoodPosition > 0 ? this.lastKnownGoodPosition : (this.video.currentTime || 0);
            return Math.max(0, rel + (this.streamStartOffset || 0));
        }

        _broadcastState() {
            if (!this._wsManager || !this._wsManager.connected || this._applyingServerState || this._swapInProgress) return;

            const profileId = this.container.dataset.profileId || window.globalActiveProfileId || localStorage.getItem('activeProfileId');
            const playing = !this.video.paused;

            const subtitleEls = this.container.querySelectorAll('.subtitle-option:not(#sub-off)');
            const subtitleTracks = Array.from(subtitleEls).map(el => ({
                id: el.getAttribute('data-id'),
                label: el.textContent.trim()
            }));
            const activeSubIdx = Array.from(subtitleEls).findIndex(el => el.classList.contains('active'));
            const audioTracks = this.streamMgr?.getAudioTracks?.() || [];

            this._wsManager.send('state', {
                currentVideo: {
                    id: this.videoId,
                    title: this.container.dataset.title || '',
                    seriesTitle: this.container.dataset.seriesTitle || '',
                    seasonNumber: parseInt(this.container.dataset.seasonNumber || '0'),
                    episodeNumber: parseInt(this.container.dataset.episodeNumber || '0'),
                    duration: parseFloat(this.container.dataset.duration || 0),
                    thumbnailPath: this.videoId ? `/api/video/thumbnail/${this.videoId}` : ''
                },
                playing: playing,
                // Absolute timeline: the element is relative (0-based) for a ?start= stream,
                // so the server clock must receive the offset-added position (B11).
                currentTime: this.video.currentTime + (this.streamStartOffset || 0),
                profileId: (profileId ? Number(profileId) : null),
                availableSubtitleTracks: subtitleTracks,
                activeSubtitleTrackIndex: activeSubIdx >= 0 ? activeSubIdx : -1,
                availableAudioTracks: audioTracks,
                activeAudioTrackIndex: audioTracks.findIndex(t => t.isActive)
            });
        }

        _clearSwapInProgress() {
            this._swapInProgress = false;
            if (this._swapSafetyTimer) {
                clearTimeout(this._swapSafetyTimer);
                this._swapSafetyTimer = null;
            }
        }

        destroy() {
            if (this._destroyed) return;
            this._destroyed = true;
            this._clearSwapInProgress();
            // Final state report so the server stops its playback timer when this
            // player is torn down (B8). Guarded: only sent once, before WS teardown.
            if (this._wsManager && this._wsManager.connected && !this._finalStateSent) {
                this._finalStateSent = true;
                this._wsManager.send('state', {
                    currentVideo: { id: this.videoId },
                    playing: false,
                    // Same absolute timeline as _broadcastState (B11).
                    currentTime: (this.video.currentTime || 0) + (this.streamStartOffset || 0)
                });
            }
            if (this._wsManager) this._wsManager.disconnect();
            this.subtitleController.destroyAssSubtitle();
            if (this._hlsInstance) {
                this._hlsInstance.destroy();
                this._hlsInstance = null;
            }
            // Central teardown: clears the watchdog, seek-retry, swap-safety, stall and
            // native-HLS timers plus any HLS session, so nothing can fire later and
            // start audio on a detached element.
            this.streamMgr.destroy();
            this.progressReporter.stop();
            this.progressReporter.setMusicSuspended(false);
            // The controls auto-hide timer calls a *global* subtitle lift reset, so
            // left running it would drop the next player's subtitles mid-episode.
            if (this.userActiveTimeout) {
                clearTimeout(this.userActiveTimeout);
                this.userActiveTimeout = null;
            }
            // Document/window listeners bound by this player (fullscreen, orientation,
            // unload, audio menu reposition) must go with it.
            if (this._boundFsChange) {
                document.removeEventListener('fullscreenchange', this._boundFsChange);
                document.removeEventListener('webkitfullscreenchange', this._boundFsChange);
                this._boundFsChange = null;
            }
            if (this._boundOrientationChange && window.screen && screen.orientation) {
                screen.orientation.removeEventListener('change', this._boundOrientationChange);
                this._boundOrientationChange = null;
            }
            if (this._cleanupOnUnload) {
                window.removeEventListener('pagehide', this._cleanupOnUnload);
                window.removeEventListener('beforeunload', this._cleanupOnUnload);
                this._cleanupOnUnload = null;
            }
            if (this.audioTrackSelector && typeof this.audioTrackSelector.destroy === 'function') {
                this.audioTrackSelector.destroy();
            }
            // Clear every timer that can outlive teardown (double-clear is harmless).
            const _timerKeys = ['_waitingTimer', '_toastTimer', '_broadcastTimer', '_qualityResetTimer',
                '_touchToggleTimer', '_fsBufferTimer', '_fsBufferTimeout', '_fsRestoreTimer',
                '_storyboardRetryTimer', '_swapSafetyTimer', '_bigPlayGuardTimer'];
            _timerKeys.forEach((k) => {
                const h = this[k];
                if (h) {
                    try { clearTimeout(h); } catch (e) { console.warn('[SimplePlayer] clearTimeout failed for ' + k + ':', e); }
                    try { clearInterval(h); } catch (e) { console.warn('[SimplePlayer] clearInterval failed for ' + k + ':', e); }
                    this[k] = null;
                }
            });
            this.video.pause();
            this.video.src = "";
            this.video.load();
            window.removeEventListener('keydown', this._boundKeydown);
        }
    };
}
