(function(window) {
    'use strict';

    window.PlayerStreamManager = class {
        constructor(player) {
            this.player = player;
        }

        /* Fresh per-request query param so Firefox's session media cache can't
         * resume a stale transcode (immutable Cache-Control + deterministic ETag
         * make completed segments resumable) — a fresh trace= forces a real
         * fetch against the server's current phantom clock. */
        _traceId() {
            return `${Date.now()}_${Math.random().toString(36).slice(2,8)}`;
        }

        /**
         * Build a `/api/video/stream/{videoId}.mp4` URL with the full query set:
         * optional server-side ?start= (transcode resume/seek), the currently
         * selected audio track (preserved across every reload path), preferred
         * quality, and a fresh trace= cache-buster. Params are collected into an
         * array so the `?`/`&` separator is always correct regardless of which
         * params are present (fixes the direct-file branch that previously
         * produced `...mp4&trace=` when quality was unset).
         *
         * @param {number|null} start  absolute position for a server-side seek,
         *                             or null/0 when the URL must not carry ?start=
         *                             (direct files use client-side seek instead)
         * @param {Object} [opts]
         * @param {boolean} [opts.includeStart]  set false to force-omit ?start=
         * @param {string[]} [opts.extraParams]   additional query params to append
         * @returns {string} full stream URL
         */
        buildStreamUrl(start, opts) {
            const p = this.player;
            const includeStart = !opts || opts.includeStart !== false;
            const params = [];
            if (includeStart && start > 0) params.push(`start=${start}`);
            if (p.currentAudioTrackIndex !== null && p.currentAudioTrackIndex >= 0) {
                params.push(`audioTrack=${p.currentAudioTrackIndex}`);
            }
            if (p._preferredQuality > 0) params.push(`quality=${p._preferredQuality}`);
            const clientCodecs = this.clientCodecList();
            if (clientCodecs.length) params.push(`codecs=${clientCodecs.join(',')}`);
            // Always declare audio capabilities (even an empty list) so the
            // server transcodes AC-3/E-AC-3 to AAC on machines that lack the
            // OS Dolby codec instead of assuming Chrome-on-Windows can play it.
            // Escalation retries pass an empty override so a false-positive
            // probe cannot keep a bad audio codec on the retry.
            const audioCodecs = opts && opts.audioCodecs != null
                ? opts.audioCodecs
                : this.clientAudioCodecList().join(',');
            params.push(`audioCodecs=${audioCodecs}`);
            if (opts && Array.isArray(opts.extraParams)) {
                opts.extraParams.forEach(ep => params.push(ep));
            }
            params.push(`trace=${this._traceId()}`);
            return `/api/video/stream/${p.videoId}.mp4${params.length ? '?' + params.join('&') : ''}`;
        }

        /**
         * Stop advertising client codec support after a failed stream. Every later
         * request then resolves to a server transcode instead of another copy that
         * the browser cannot decode (the HEVC black-player case).
         */
        disableClientCodecs() {
            this._clientCodecs = [];
        }

        /**
         * Capability payload for the server's output-codec selection
         * (`?codecs=hevc,av1`). Probed once per page and cached: the server trusts
         * a non-empty list over its User-Agent heuristics, so a browser that can
         * decode HEVC gets an HEVC stream instead of a wasted H.264 transcode.
         *
         * An empty array is meaningful: it tells the server "no declared
         * capabilities", which is exactly the legacy behaviour.
         *
         * @returns {string[]} supported codec tags, e.g. ['hevc','av1']
         */
        clientCodecList() {
            if (this._clientCodecs) return this._clientCodecs;
            const codecs = [];
            try {
                const Cls = this.constructor;
                if (Cls.hasNativeHevcSupport()) codecs.push('hevc');
                if (Cls.hasNativeAv1Support()) codecs.push('av1');
            } catch (err) {
                console.warn('[SimplePlayer] codec capability probe failed:', err);
            }
            this._clientCodecs = codecs;
            return codecs;
        }

        /**
         * Probe native AC-3 (Dolby Digital) decode support.
         *
         * Chrome/Edge on Windows only decode AC-3 when the OS Dolby/Media
         * Feature Pack is installed, so a machine that can play H.264 may still
         * reject a copied AC-3 track. Safari/iOS decode it natively. The result
         * is sent to the server as ?audioCodecs= so it only copies AC-3 when
         * this client can actually decode it.
         */
        static hasNativeAc3Support() {
            var ua = (navigator.userAgent || '').toLowerCase();
            if (ua.indexOf('safari') !== -1 && ua.indexOf('chrome') === -1 && ua.indexOf('chromium') === -1) {
                return true;
            }
            try {
                if (typeof MediaSource !== 'undefined' && typeof MediaSource.isTypeSupported === 'function'
                        && MediaSource.isTypeSupported('audio/mp4; codecs="ac-3"')) {
                    return true;
                }
                var v = document.createElement('video');
                return v.canPlayType('audio/mp4; codecs="ac-3"') === 'probably';
            } catch (err) {
                console.warn('[SimplePlayer] AC-3 capability probe failed:', err);
                return false;
            }
        }

        /** Probe native E-AC-3 (Dolby Digital Plus) decode support. */
        static hasNativeEac3Support() {
            var ua = (navigator.userAgent || '').toLowerCase();
            if (ua.indexOf('safari') !== -1 && ua.indexOf('chrome') === -1 && ua.indexOf('chromium') === -1) {
                return true;
            }
            try {
                if (typeof MediaSource !== 'undefined' && typeof MediaSource.isTypeSupported === 'function'
                        && MediaSource.isTypeSupported('audio/mp4; codecs="ec-3"')) {
                    return true;
                }
                var v = document.createElement('video');
                return v.canPlayType('audio/mp4; codecs="ec-3"') === 'probably';
            } catch (err) {
                console.warn('[SimplePlayer] E-AC-3 capability probe failed:', err);
                return false;
            }
        }

        /**
         * Audio codecs this client proved it can decode. The server uses this
         * to decide whether AC-3/E-AC-3 can be copied or must be transcoded to
         * AAC. Always returns an array (possibly empty) so the query parameter
         * is present and authoritative even when nothing is supported.
         *
         * @returns {string[]} e.g. ['ac3'] or []
         */
        clientAudioCodecList() {
            if (this._clientAudioCodecs) return this._clientAudioCodecs;
            const codecs = [];
            try {
                const Cls = this.constructor;
                if (Cls.hasNativeAc3Support()) codecs.push('ac3');
                if (Cls.hasNativeEac3Support()) codecs.push('eac3');
            } catch (err) {
                console.warn('[SimplePlayer] audio codec capability probe failed:', err);
            }
            this._clientAudioCodecs = codecs;
            return codecs;
        }

        /**
         * Check if browser supports WebCodecs API (VideoDecoder + VideoEncoder)
         * Required for hevc.js HEVC to H.264 transcoding
         */
        static hasWebCodecsSupport() {
            return typeof VideoDecoder !== 'undefined' && 
                   typeof VideoEncoder !== 'undefined' &&
                   typeof VideoDecoder.isConfigSupported === 'function' &&
                   typeof VideoEncoder.isConfigSupported === 'function';
        }

        /**
         * Check if the browser has native HEVC Main 10 (10-bit) MSE playback.
         *
         * A Main-profile-only probe is NOT sufficient: this library is
         * overwhelmingly Main 10 (yuv420p10le), and a Main-only pass is a
         * false positive that leads the server to serve unplayable copy
         * streams. Probe the Main 10 profiles explicitly (hev1.2.x / hvc1.2.x).
         *
         * Exclude real Gecko (Firefox) via 'firefox', 'fxios', or 'gecko/'
         * WITH the slash: every Chromium/WebKit UA contains the string
         * "like Gecko" (no slash), so a bare indexOf('gecko') check would
         * false-negative everywhere and the probe could never report support.
         * Firefox reports hvc1 as supported but cannot decode HEVC in MSE
         * (NS_ERROR_DOM_MEDIA_FATAL), so the exclusion stays.
         *
         * A "Yes" here means nativeHevc=1 (skip server transcode); anything
         * else means the server must transcode.
         */
        static hasNativeHevcSupport() {
            var ua = (navigator.userAgent || '').toLowerCase();
            if (ua.indexOf('firefox') !== -1 || ua.indexOf('fxios') !== -1 || ua.indexOf('gecko/') !== -1) {
                return false;
            }
            // canPlayType is the only reliable signal for a progressive <video> src.
            // MediaSource.isTypeSupported() was previously OR'd in here and reports
            // HEVC as supported in Chromium builds that cannot actually decode it
            // (no OS codec / no HEVC extension). The server then copied HEVC instead
            // of transcoding and Chrome showed a black player. Require an explicit
            // "probably" (Chrome says that only when the decoder really exists).
            // Chromium's canPlayType is optimistic: it reports "probably" for HEVC
            // even when no decoder is installed (no OS codec / HEVC extension), and
            // the element then renders a black frame. MSE.isTypeSupported lies the
            // same way. So a Chromium UA never "proves" HEVC here; it gets the
            // server transcode instead. Safari/WebKit genuinely decodes HEVC.
            if (ua.indexOf('chrome') !== -1 || ua.indexOf('chromium') !== -1 || ua.indexOf('crios') !== -1) {
                return false;
            }
            try {
                var video = document.createElement('video');
                return video.canPlayType('video/mp4; codecs="hvc1.2.4.L123.B0"') === 'probably'
                    || video.canPlayType('video/mp4; codecs="hev1.2.4.L123.B0"') === 'probably';
            } catch (err) {
                console.warn('[SimplePlayer] HEVC capability probe failed:', err);
                return false;
            }
        }

        /**
         * Check if browser has native AV1 support.
         *
         * Unlike HEVC, AV1 is supported by all major desktop browsers:
         * Chrome 70+, Edge 79+, Firefox 67+, and Brave (Chromium-based).
         * No browser exclusion is needed — a simple probe suffices.
         * iOS Safari does NOT support AV1 (except iPhone 15 Pro+ for hardware decode).
         */
        static hasNativeAv1Support() {
            var ua = (navigator.userAgent || '').toLowerCase();
            if (ua.indexOf('iphone') !== -1 || ua.indexOf('ipad') !== -1 || ua.indexOf('ipod') !== -1) {
                return false;
            }
            const video = document.createElement('video');
            return MediaSource.isTypeSupported('video/mp4; codecs="av01.0.08M.08"') ||
                   MediaSource.isTypeSupported('video/mp4; codecs="av01.0.04M.08"') ||
                   video.canPlayType('video/mp4; codecs="av01.0.08M.08"') !== '' ||
                   video.canPlayType('video/mp4; codecs="av01.0.04M.08"') !== '';
        }

        /**
         * Check if browser has native Opus audio support.
         *
         * Opus is supported in all modern browsers for both WebM and MP4 containers.
         * Chrome 33+, Firefox 22+, Edge 17+, Safari 17.1+.
         * This is used by COPYABLE_AUDIO_CODECS on the backend to decide whether
         * to copy the Opus stream or transcode to AAC.
         */
        static hasNativeOpusSupport() {
            var ua = (navigator.userAgent || '').toLowerCase();
            if (ua.indexOf('iphone') !== -1 || ua.indexOf('ipad') !== -1 || ua.indexOf('ipod') !== -1) {
                return false;
            }
            return MediaSource.isTypeSupported('audio/webm; codecs="opus"') ||
                   MediaSource.isTypeSupported('audio/mp4; codecs="opus"');
        }

        /**
         * Initialize HEVC playback using hevc.js (WASM decoder + WebCodecs H.264 encoder)
         * This intercepts the HLS path and uses direct MP4 with client-side transcoding
         */
        async initHevcJsStream(savedTime) {
            const p = this.player;
            if (p._destroyed || !document.body.contains(p.container)) return;

            console.log('[SimplePlayer] Initializing hevc.js stream for HEVC playback');

            // Get the direct MP4 URL
            const videoUrl = this.buildStreamUrl(savedTime);
            
            p.streamStartOffset = savedTime || 0;
            p._showLoading('Loading HEVC video (client-side transcoding)...');

            // Check if hevc.js is loaded
            if (typeof installMSEIntercept === 'undefined') {
                console.error('[SimplePlayer] hevc.js not loaded, falling back to direct stream');
                this.initDirectStream(savedTime);
                return;
            }

            try {
                // Install MSE intercept from hevc.js core
                // This patches MediaSource.addSourceBuffer to transcode HEVC to H.264
                const cleanup = installMSEIntercept({
                    workerUrl: '/lib/hevc/transcode-worker.js',
                    wasmUrl: '/lib/hevc/hevc.js',
                    wasmBinaryUrl: '/lib/hevc/hevc-decoder.wasm'
                });

                // Store cleanup function for later removal
                p._hevcJsCleanup = cleanup;

                // Set up video source - the MSE intercept will handle transcoding transparently
                const mediaSource = new MediaSource();
                p.video.src = URL.createObjectURL(mediaSource);

                mediaSource.addEventListener('sourceopen', async () => {
                    if (p._destroyed) return;
                    
                    try {
                        // Create source buffer for H.264 (hevc.js intercepts HEVC and feeds H.264)
                        const sourceBuffer = mediaSource.addSourceBuffer('video/mp4; codecs="avc1.42E01E"');
                        
                        // Fetch and append the init segment first
                        const initResponse = await fetch(videoUrl.replace('.mp4', '_init.mp4').replace('?start=', '_init?start='));
                        if (initResponse.ok) {
                            const initData = await initResponse.arrayBuffer();
                            sourceBuffer.appendBuffer(initData);
                        }

                        // For now, use a simpler approach: fetch the whole video and let hevc.js transcode
                        // In practice, hevc.js works with segmented content. For MP4, we may need to segment it.
                        // Let's try the direct approach first - hevc.js may handle full MP4
                        
                        p.video.addEventListener('loadedmetadata', () => {
                            console.log('[SimplePlayer] hevc.js stream metadata loaded');
                            p.applyInitialState();
                            p.loadSubtitles();
                        }, { once: true });

                        p.video.addEventListener('playing', () => {
                            p._hideLoading();
                        }, { once: true });

                        p.video.addEventListener('error', (e) => {
                            console.error('[SimplePlayer] hevc.js playback error:', p.video.error);
                            if (p._hevcJsCleanup) {
                                p._hevcJsCleanup();
                                p._hevcJsCleanup = null;
                            }
                            URL.revokeObjectURL(p.video.src);
                            this.initDirectStream(savedTime);
                        }, { once: true });

                        p.video.play().catch(e => console.log('[SimplePlayer] Play requires user gesture:', e));
                    } catch (err) {
                        console.error('[SimplePlayer] hevc.js sourceopen error:', err);
                        if (p._hevcJsCleanup) {
                            p._hevcJsCleanup();
                            p._hevcJsCleanup = null;
                        }
                        URL.revokeObjectURL(p.video.src);
                        this.initDirectStream(savedTime);
                    }
                });

            } catch (err) {
                console.error('[SimplePlayer] hevc.js initialization failed:', err);
                if (p._hevcJsCleanup) {
                    p._hevcJsCleanup();
                    p._hevcJsCleanup = null;
                }
                this.initDirectStream(savedTime);
            }
        }

        

        initDirectStream(savedTime) {
            const p = this.player;
            // Defensive: a WS source-reload or fallback into the direct path must
            // never leave a server-side HLS session running (encoder leak).
            this.destroyLocalHlsSession();
            /* Clear any previously painted frame (e.g. the previous video's last
             * frame) so a frozen frame from an old movie cannot linger while the
             * new source loads. No-op on initial load (fresh element, no src). */
            try {
                p.video.removeAttribute('src');
                p.video.load();
            } catch (e) {}
            if (p.needsTranscode && savedTime > 0) {
                console.log('[SimplePlayer] Resuming from ' + savedTime + 's via server-side seek');
                p.video.src = this.buildStreamUrl(savedTime);
            } else {
                p.video.src = this.buildStreamUrl(null);
            }

            p._showLoading('Loading video...');

            // A HEVC copy that the client cannot actually decode can stall
            // without ever firing a `video` error. Escalate to a forced H.264
            // transcode when playback has not started in time.
            this._armPlaybackWatchdog('direct', () => {
                const resumeAt = p.lastKnownGoodPosition > 0 ? p.lastKnownGoodPosition : 0;
                if (p._destroyed || p._userPaused) return;
                this.disableClientCodecs();
                p.video.src = this.buildStreamUrl(resumeAt, { extraParams: ['forceTranscode=true'], audioCodecs: '' });
                p.video.load();
                p.video.play().catch(function(e) { console.error('[SimplePlayer] Watchdog retry play failed', e); });
            }, 15000);

            p.video.addEventListener('loadedmetadata', () => {
                console.log('[SimplePlayer] Direct stream metadata loaded, duration:', p.video.duration);
                p.applyInitialState();
                p.loadSubtitles();
            }, { once: true });

            p.video.addEventListener('playing', () => {
                p._streamFallbackCount = 0;
                this._clearPlaybackWatchdog();
                p._hideLoading();
            }, { once: true });

            // Skip if caller (simple-player) manages its own persistent error handler — avoids double-counting the fallback budget.
            if (!p._setupStreamErrorHandler) {
                p._streamErrorHandler = (e) => {
                    if (p._userPaused) {
                        console.log('[SimplePlayer] Direct stream error suppressed — user pause active, not retrying');
                        return;
                    }
                    p._streamFallbackCount++;
                    console.error('[SimplePlayer] Direct stream error (fallback ' + p._streamFallbackCount + '/' + p._maxStreamFallbacks + '):', p.video.error);
                    if (p._streamFallbackCount < p._maxStreamFallbacks) {
                        p._showLoading('Stream error, retrying...');
                        setTimeout(() => {
                            if (p._userPaused) {
                                console.log('[SimplePlayer] Blocked stream-error retry play — user pause active');
                                return;
                            }
                            const currentTime = p.lastKnownGoodPosition + (p.streamStartOffset || 0);
                            p.streamStartOffset = currentTime;
                            p.lastKnownGoodPosition = 0;
                            p.video.src = this.buildStreamUrl(currentTime);
                            p.video.load();
                            console.log('[SimplePlayer] Stream-error retry play()');
                            p.video.play().catch(function(e) { console.error('[SimplePlayer] Stream-error retry play failed', e); });
                        }, 1000);
                    } else {
                        p._showLoading('Playback failed after ' + p._maxStreamFallbacks + ' attempts');
                    }
                };
                p.video.addEventListener('error', p._streamErrorHandler);
            }

            if (p._userPaused) {
                console.log('[SimplePlayer] Initial auto-play suppressed — user pause active');
            } else {
                console.log('[SimplePlayer] Initial auto-play()');
                p.video.play().catch(function(e) { console.log('[SimplePlayer] Play requires user gesture:', e); });
            }
        }

        async initExternalStream() {
            const p = this.player;
            const url = p.externalUrl;
            const savedTime = parseFloat(p.container.dataset.startTime || 0);
            const isIOS = p.utils.isIOS();

            console.log('[SimplePlayer] External stream:', url);

            // HLS (m3u8) requires hls.js (MSE) in Chromium/Firefox — only Safari/iOS
            // plays it natively in a <video> element. Fall back to native for those.
            const isHls = /\.m3u8(\?|#|$)/i.test(url) || /\.m3u8(\?|#|$)/i.test(p.externalOriginalUrl || '');
            if (isHls && !isIOS) {
                if (typeof window.ensureHlsLoaded === 'function' && !window.Hls) {
                    try {
                        await window.ensureHlsLoaded();
                    } catch (e) {
                        // Logged in hls-loader. Fall through to the native path: a
                        // browser without MSE may still handle the URL itself.
                        console.error('[SimplePlayer] hls.js unavailable for external HLS stream', e);
                    }
                }
                if (typeof Hls !== 'undefined' && Hls.isSupported()) {
                    this._initHlsExternalStream(url, savedTime);
                    return;
                }
            }

            p.video.src = url;

            p.video.addEventListener('loadedmetadata', () => {
                if (savedTime > 0) p.video.currentTime = savedTime;
                p.applyInitialState();
            }, { once: true });

            p.video.addEventListener('playing', () => p._hideLoading(), { once: true });
            p.video.addEventListener('error', (e) => {
                console.error('[SimplePlayer] External stream error:', p.video.error);
                p._showLoading('Playback error');
            });

            p.video.play().catch(e => {
                console.log('[SimplePlayer] Play requires user gesture:', e);
            });

            p.setMusicSuspended(true);
            p.startProgressReporting();
        }

        /**
         * Start a server-side HLS session for a local library video (profile
         * hlsStreaming enabled). POSTs /api/hls/session/{videoId} to create the
         * session, then hands the returned master playlist to hls.js. A
         * generation counter serializes concurrent creations (initial load
         * racing the audio-preference switch): only the newest call may attach
         * its session; older in-flight responses are discarded.
         *
         * @param {number} savedTime  absolute resume position (server ?start=)
         */
        async initLocalHlsStream(savedTime) {
            const p = this.player;
            if (p._destroyed) return;
            if (!document.body.contains(p.container)) {
                // Detached during a view transition. Returning here silently left the
                // new video with no stream at all until a page refresh; wait for the
                // container to be attached and continue, then give up to direct play.
                if (!p._hlsAttachRetry) {
                    p._hlsAttachRetry = true;
                    console.warn('[SimplePlayer] HLS start deferred: container not attached yet');
                    setTimeout(() => {
                        if (p._destroyed) return;
                        p._hlsAttachRetry = false;
                        this.initLocalHlsStream(savedTime);
                    }, 250);
                    return;
                }
                console.warn('[SimplePlayer] HLS container still detached - falling back to direct stream');
                p._hlsAttachRetry = false;
                this.fallbackToDirectStream(savedTime);
                return;
            }

            // A new session supersedes any previous one — tear the old one down
            // first (also invalidates any in-flight creation via the gen bump).
            this.destroyLocalHlsSession();

            const gen = (p._hlsSessionGen = (p._hlsSessionGen || 0) + 1);
            p._hlsSessionPending = true;

            const params = [];
            if (savedTime > 0) params.push(`start=${savedTime}`);
            if (p.profileId) params.push(`profileId=${encodeURIComponent(p.profileId)}`);
            if (p.currentAudioTrackIndex !== null && p.currentAudioTrackIndex >= 0) {
                params.push(`audioTrack=${p.currentAudioTrackIndex}`);
            }
            if (p._preferredQuality > 0) params.push(`quality=${p._preferredQuality}`);
            const clientCodecs = this.clientCodecList();
            if (clientCodecs.length) params.push(`codecs=${clientCodecs.join(',')}`);
            params.push(`audioCodecs=${this.clientAudioCodecList().join(',')}`);
            params.push(`device=web-${p.videoId}-${p.profileId || 'default'}`);

            try {
                const resp = await fetch(`/api/hls/session/${p.videoId}${params.length ? '?' + params.join('&') : ''}`, {
                    method: 'POST',
                    credentials: 'include'
                });
                if (p._destroyed || gen !== p._hlsSessionGen) return;
                if (!resp.ok) {
                    console.error('[SimplePlayer] HLS session creation failed:', resp.status);
                    p._hlsSessionPending = false;
                    this.fallbackToDirectStream(savedTime);
                    return;
                }
                const data = await resp.json();
                if (p._destroyed || gen !== p._hlsSessionGen) return;
                if (!data || !data.sessionId || !data.playlistUrl) {
                    console.error('[SimplePlayer] HLS session response missing sessionId/playlistUrl:', data);
                    p._hlsSessionPending = false;
                    this.fallbackToDirectStream(savedTime);
                    return;
                }
                p._hlsSessionId = data.sessionId;
                p._hlsSessionPending = false;
                // Seed the fallback position: fallbackToDirectStream ignores its
                // savedTime param and derives absTime from lastKnownGoodPosition.
                if (p.lastKnownGoodPosition <= 0) p.lastKnownGoodPosition = savedTime;
                this._initHlsExternalStream(data.playlistUrl, savedTime, {
                    fallbackToDirect: true,
                    loadingMessage: 'Loading HLS stream...',
                    // Local library sessions are growing VOD, not live TV: without
                    // this hls.js applies liveSyncDurationCount and starts at the
                    // encoder's latest segment instead of 0:00.
                    live: false
                });
            } catch (err) {
                console.error('[SimplePlayer] HLS session creation failed:', err);
                if (p._destroyed || gen !== p._hlsSessionGen) return;
                p._hlsSessionPending = false;
                this.fallbackToDirectStream(savedTime);
            }
        }

        /**
         * Cancel a pending playback-start watchdog. Safe to call repeatedly.
         * The watchdog exists because some HEVC/MSE failures on Windows
         * Chromium never surface as a `video` error or a fatal hls.js error �?"
         * the element simply never leaves the buffering state, so the UI
         * spinner would otherwise stay forever with no fallback.
         */
        _clearPlaybackWatchdog() {
            const p = this.player;
            if (p._playbackWatchdog) {
                clearTimeout(p._playbackWatchdog);
                p._playbackWatchdog = null;
            }
        }

        /**
         * Seconds of media buffered at the end of the element's ranges. Used to
         * tell "the stream is dead" apart from "the encoder is still producing
         * in real time": the latter keeps this number growing even while
         * readyState sits below HAVE_CURRENT_DATA.
         */
        _bufferedEnd(video) {
            try {
                const b = video && video.buffered;
                if (!b || !b.length) return 0;
                return b.end(b.length - 1) || 0;
            } catch (e) {
                return 0;
            }
        }

        /**
         * Escalate to an H.264 transcode when playback has not actually
         * started within `ms`. `onTimeout` receives no arguments and must be
         * idempotent; the watchdog is cleared before it runs.
         *
         * A slow-but-healthy stream is given more time instead of being
         * restarted: re-opening the source aborts the in-flight play() and
         * launches a second transcode from scratch, which is how a 1080p
         * movie that simply needed another 10 seconds ended up never playing.
         */
        _armPlaybackWatchdog(label, onTimeout, ms) {
            const p = this.player;
            this._clearPlaybackWatchdog();
            const deadline = ms || 15000;
            const MAX_EXTENSIONS = 3;
            let extensions = 0;

            const arm = (bufferedAtWindowStart) => {
                p._playbackWatchdog = setTimeout(() => {
                    p._playbackWatchdog = null;
                    if (p._destroyed) return;
                    const v = p.video;
                    const started = v && !v.paused && v.readyState >= 2 && v.currentTime > 0.1;
                    if (started) return;
                    if (v && v.readyState >= 2) {
                        // The stream decoded fine; the element is simply paused, usually
                        // because a deep link opened it without a user gesture and Chrome
                        // blocked autoplay. Reloading here aborts a healthy stream and
                        // produces "play() interrupted by a new load request" in a loop.
                        console.log('[SimplePlayer] Watchdog: stream ready (readyState ' + v.readyState + ') but paused - waiting for the user to press play');
                        if (typeof p._hideLoading === 'function') p._hideLoading();
                        return;
                    }
                    const bufferedNow = this._bufferedEnd(v);
                    if (bufferedNow > bufferedAtWindowStart + 0.05 && extensions < MAX_EXTENSIONS) {
                        extensions++;
                        console.log('[SimplePlayer] Watchdog: no playback yet but the stream is still arriving (' +
                            bufferedNow.toFixed(1) + 's buffered, window ' + extensions + '/' + MAX_EXTENSIONS +
                            ') - waiting instead of restarting');
                        arm(bufferedNow);
                        return;
                    }
                    console.warn('[SimplePlayer] Playback did not start within ' + deadline * (extensions + 1) + 'ms (' + label + '); escalating to H.264');
                    onTimeout();
                }, deadline);
            };

            arm(this._bufferedEnd(p.video));
        }

        // Instance is stored on the player so destroy()/cleanupHls() can tear it down.
        // opts.fallbackToDirect: fall back to the direct stream on fatal error
        // (local HLS sessions) instead of the external-stream 'Playback error'
        // dead-end. opts.loadingMessage overrides the toast text.
        _initHlsExternalStream(url, savedTime, opts) {
            const p = this.player;
            const fallbackToDirect = !!(opts && opts.fallbackToDirect);
            const loadingMessage = (opts && opts.loadingMessage) || 'Loading live stream...';

            if (p._hlsInstance) {
                p._hlsInstance.destroy();
                p._hlsInstance = null;
            }

            // Live-edge sync is only for live channels. A local library session is
            // a growing VOD EVENT playlist; applying live sync made it start at the
            // latest encoded segment and appear to skip.
            const hlsConfig = (opts && opts.live === false)
                ? {}
                : {
                    liveSyncDurationCount: 3,
                    liveMaxLatencyDurationCount: 6,
                    maxLiveSyncPlaybackRate: 1.5
                };
            const hls = new Hls(hlsConfig);
            p._hlsInstance = hls;
            p._hlsNetworkRetries = 0;

            p._showLoading(loadingMessage);
            hls.loadSource(url);
            hls.attachMedia(p.video);

            this._armPlaybackWatchdog('hls', () => {
                if (p._userPaused) return;
                if (p._hlsInstance === hls) {
                    hls.destroy();
                    p._hlsInstance = null;
                }
                if (fallbackToDirect) {
                    this.fallbackToDirectStream(savedTime);
                } else {
                    p._showLoading('Playback error');
                }
            }, 15000);

            hls.on(Hls.Events.MANIFEST_PARSED, () => {
                if (p._destroyed || p._hlsInstance !== hls) return;
                if (p._userPaused) {
                    console.log('[SimplePlayer] HLS manifest parsed — user pause active, skipping auto-play');
                    if (savedTime > 0) p.video.currentTime = savedTime;
                    p.applyInitialState();
                    return;
                }
                console.log('[SimplePlayer] HLS manifest parsed, starting playback');
                if (savedTime > 0) p.video.currentTime = savedTime;
                p.applyInitialState();
                console.log('[SimplePlayer] HLS auto-play()');
                p.video.play().catch(function(e) { console.error('[SimplePlayer] HLS play failed', e); });
            });

            hls.on(Hls.Events.ERROR, (event, data) => {
                if (!data || p._destroyed || p._hlsInstance !== hls) return;
                if (!data.fatal) return;
                console.error('[SimplePlayer] Fatal HLS error:', data.type, data.details);
                this._clearPlaybackWatchdog();
                if (p._userPaused) {
                    console.log('[SimplePlayer] HLS fatal error suppressed — user pause active, not retrying');
                    return;
                }
                if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
                    p._hlsNetworkRetries = (p._hlsNetworkRetries || 0) + 1;
                    if (p._hlsNetworkRetries < 3) {
                        console.log('[SimplePlayer] HLS network retry ' + p._hlsNetworkRetries + '/3 — startLoad()');
                        hls.startLoad();
                    } else {
                        hls.destroy();
                        p._hlsInstance = null;
                        if (fallbackToDirect) {
                            this.fallbackToDirectStream(savedTime);
                        } else {
                            p._showLoading('Playback error');
                        }
                    }
                } else {
                    if (fallbackToDirect) {
                        hls.destroy();
                        p._hlsInstance = null;
                        this.fallbackToDirectStream(savedTime);
                    } else {
                        p._showLoading('Playback error');
                    }
                }
            });

            p.video.addEventListener('playing', () => {
                this._clearPlaybackWatchdog();
                p._hideLoading();
            }, { once: true });

            p.setMusicSuspended(true);
            p.startProgressReporting();
        }

        fallbackToDirectStream(savedTime) {
            const p = this.player;
            if (p._fallbackInProgress || p._destroyed) return;
            p._fallbackInProgress = true;
            console.log('[SimplePlayer] Falling back to direct stream');

            this.clearStreamErrorHandlers();

            const absTime = Math.max(0, p.streamStartOffset > 0 ? p.lastKnownGoodPosition + p.streamStartOffset : p.lastKnownGoodPosition);
            p.streamStartOffset = absTime;

            const setupFallback = () => {
                // Escalate, don't repeat: a fallback means the copy/HLS path already
                // failed to play, so force a server transcode instead of re-serving
                // the same unplayable bytes (previously an infinite fail loop).
                this.disableClientCodecs();
                p.video.src = this.buildStreamUrl(absTime, { extraParams: ['forceTranscode=true'], audioCodecs: '' });
                p.video.load();
                this._armPlaybackWatchdog('fallback', () => {
                    if (p._userPaused) return;
                    p._fallbackInProgress = false;
                    p._showLoading('Playback failed - try reloading');
                }, 25000);
                p.video.addEventListener('loadedmetadata', () => {
                    p._fallbackInProgress = false;
                    p.applyInitialState();
                    p.loadSubtitles();
                }, { once: true });
                p.video.addEventListener('playing', () => {
                    p._fallbackInProgress = false;
                    this._clearPlaybackWatchdog();
                    p._hideLoading();
                }, { once: true });
                p.video.addEventListener('error', () => {
                    p._fallbackInProgress = false;
                    this._clearPlaybackWatchdog();
                }, { once: true });
                p.video.play().catch(() => {});
            };

            if (p.utils.isIOS()) {
                this._preloadSubtitleTracks().then(setupFallback);
            } else {
                setupFallback();
            }
        }

        async loadAudioTrackSelector() {
            const p = this.player;
            const selector = document.getElementById('audioTrackSelector');
            if (!selector) return;

            try {
                const resp = await fetch(`/api/video/${p.videoId}/audio-tracks`);
                const json = await resp.json();
                const tracks = json.data || [];

                if (tracks.length <= 1) {
                    selector.style.display = 'none';
                    return;
                }

                const select = selector.querySelector('select') || document.createElement('select');
                if (!select.parentElement) {
                    select.className = 'audio-track-select';
                    select.style.cssText = 'background: #333; color: white; border: 1px solid #48c774; border-radius: 4px; padding: 4px 8px; font-size: 0.9rem;';
                    selector.innerHTML = '';
                    selector.appendChild(select);
                }

                select.innerHTML = '';
                tracks.forEach((track, index) => {
                    const option = document.createElement('option');
                    option.value = track.trackIndex ?? index;
                    option.textContent = track.displayName || `Audio ${index + 1}`;
                    if (track.isDefault) option.selected = true;
                    select.appendChild(option);
                });

                select.onchange = (e) => {
                    const trackIndex = parseInt(e.target.value);
                    this.switchAudioTrack(trackIndex);
                };

                selector.style.display = 'block';
                console.log(`[SimplePlayer] Audio track selector loaded with ${tracks.length} tracks`);
            } catch (e) {
                console.error('[SimplePlayer] Failed to load audio tracks:', e);
            }
        }

        switchAudioTrack(trackIndex) {
            const p = this.player;
            console.log('[SimplePlayer] Switching audio track to:', trackIndex);

            // A reload is about to happen: every handler from the previous load
            // (including the initial-open one, which retries at its closure-captured
            // resume time) must be gone or it will fight the new request.
            this.clearStreamErrorHandlers();

            p.currentAudioTrackIndex = trackIndex;

            // Nothing is open yet: the player applies the saved preference before opening
            // the stream. Record the track and let that single request carry it —
            // reloading here would open a second stream and supersede the first one's
            // transcode mid-write. Once open, a switch is a real switch (the HLS path
            // never sets video.src, so an explicit flag is the only reliable test).
            if (!p._streamOpened) {
                console.log('[SimplePlayer] Audio track recorded before the stream opens:', trackIndex);
                return;
            }

            const savedTime = p.video.currentTime + (p.streamStartOffset || 0);

            // HLS local session: recreate the session at the current position so the
            // server transcodes the newly selected audio track. During the initial
            // audio-preference switch the stream hasn't started yet (currentTime 0),
            // so fall back to the resume time.
            if (p._hlsSessionId || p._hlsSessionPending) {
                const hlsSavedTime = savedTime > 0 ? savedTime : (p.initialResumeTime || 0);
                this.recreateLocalHlsSession(hlsSavedTime);
                // The session restarts at a new position and streamStartOffset changes,
                // so the active <track> must be rebuilt with the matching ?start=. This
                // branch returns before the progressive reload below, which is why
                // subtitles drifted after an audio switch on HLS playback.
                setTimeout(() => {
                    if (!p._destroyed) p.loadSubtitles();
                }, 500);
                return;
            }

            // Transcode: reload with a server-side seek (?start=) so the new audio track's
            // fresh transcode starts AT the current position. The old approach loaded without
            // ?start= and attempted a client-side seek after metadata - but the new audio
            // track has no segment coverage yet, so the seek lands beyond the growing segment,
            // the server falls back to a fresh transcode from 0:00, and the movie restarts.
            // Direct streams are the opposite: server-side ?start= is unreliable for them
            // (simple-player resume uses client-side seek for the same reason), so load from
            // 0 (absolute timeline) and seek client-side once metadata is ready.
            p._swapInProgress = true;
            if (p._swapSafetyTimer) clearTimeout(p._swapSafetyTimer);
            p._swapSafetyTimer = setTimeout(() => {
                p._swapInProgress = false;
                p._swapSafetyTimer = null;
            }, 10000);

            p.video.pause();
            p.video.src = "";
            p.video.load();

            if (p.needsTranscode) {
                p.streamStartOffset = Math.max(0, savedTime);
                p.video.src = this.buildStreamUrl(Math.max(0, savedTime));
            } else {
                p.streamStartOffset = 0;
                p.video.src = this.buildStreamUrl(null);
                p.video.addEventListener('loadedmetadata', () => {
                    if (!p._destroyed && savedTime > 0) {
                        const target = Math.min(savedTime, (isFinite(p.video.duration) ? p.video.duration : savedTime) || savedTime);
                        try { p.video.currentTime = target; } catch (e) {}
                    }
                }, { once: true });
            }
            p.video.load();

            // On playing, clear the swap guard and send ONE confirmation broadcast so
            // the server's phantom clock snaps to the new absolute position.
            const onSeekPlaying = () => {
                p._clearSwapInProgress();
                if (!p._destroyed) p._broadcastState();
            };
            const onSeekReady = () => p._clearSwapInProgress();
            p.video.addEventListener('playing', onSeekPlaying, { once: true });
            p.video.addEventListener('loadeddata', onSeekReady, { once: true });
            p.video.addEventListener('error', onSeekReady, { once: true });

            // The streamStartOffset changed (0 → savedTime for a 0-based stream):
            // reload subtitles so the active track's ?start= matches the new offset.
            // loadSubtitles re-clicks the saved track itself.
            p.video.addEventListener('loadedmetadata', () => {
                if (!p._destroyed) p.loadSubtitles();
                // F6: re-apply playbackRate after src+load reset
                if (p.state.playbackRate && p.state.playbackRate !== 1) p.video.playbackRate = p.state.playbackRate;
            }, { once: true });

            p._hasPlayedData = false;
            p.lastKnownGoodPosition = 0;
            p._streamFallbackCount = 0;
            if (p._stallTimer) {
                clearTimeout(p._stallTimer);
                p._stallTimer = null;
            }
        }

        setAudioTrack(trackId) {
            const p = this.player;
            console.log('[SimplePlayer] Setting audio track:', trackId);

            if (trackId === 'default') {
                this.switchAudioTrack(-1);
                return;
            }
            let trackIndex = parseInt(trackId);
            if (isNaN(trackIndex)) {
                const track = window.availableAudioTracks?.find(t => t.id == trackId);
                trackIndex = track ? (track.trackIndex ?? 0) : 0;
            }
            this.switchAudioTrack(trackIndex);
        }

        getAudioTracks() {
            const p = this.player;
            if (p.video.audioTracks) {
                const tracks = [];
                for (let i = 0; i < p.video.audioTracks.length; i++) {
                    const t = p.video.audioTracks[i];
                    tracks.push({
                        id: i.toString(),
                        languageCode: t.languageCode,
                        languageName: t.displayName || t.languageName || t.languageCode,
                        displayName: t.displayName || t.languageName || t.languageCode,
                        isDefault: t.isDefault,
                        channels: t.channels,
                        title: t.title,
                        isActive: true
                    });
                }
                return tracks;
            }
            return [];
        }

        clearStreamErrorHandlers() {
            const p = this.player;
            [p._seekErrorHandler, p._setupStreamErrorHandler, p._streamErrorHandler].forEach(h => {
                if (h) p.video.removeEventListener('error', h);
            });
            p._seekErrorHandler = p._setupStreamErrorHandler = p._streamErrorHandler = null;
        }

        /**
         * Destroy the active server-side HLS session (DELETE /api/hls/session/{id}).
         * No-op when no session is active. keepalive:true lets the request survive
         * page teardown so the encoder is released even on unload.
         */
        destroyLocalHlsSession() {
            const p = this.player;
            // Invalidate any in-flight session creation (generation bump) so a
            // stale response can never attach after teardown.
            p._hlsSessionGen = (p._hlsSessionGen || 0) + 1;
            p._hlsSessionPending = false;
            const sessionId = p._hlsSessionId;
            if (!sessionId) return;
            p._hlsSessionId = null;
            console.log('[SimplePlayer] Destroying HLS session:', sessionId);
            fetch(`/api/hls/session/${sessionId}`, {
                method: 'DELETE',
                credentials: 'include',
                keepalive: true
            }).catch(err => {
                console.warn('[SimplePlayer] Failed to destroy HLS session:', err);
            });
        }

        /**
         * Recreate the server-side HLS session at an absolute position (audio
         * track switch, quality switch, or past-buffer seek). Mirrors the
         * _doServerSeek swap-guard: suppress broadcasts while the new session
         * loads, then clear on playing/loadeddata/error.
         *
         * @param {number} time  absolute position for the new session
         */
        recreateLocalHlsSession(time) {
            const p = this.player;
            if (p._destroyed || !document.body.contains(p.container)) return;

            p._swapInProgress = true;
            if (p._swapSafetyTimer) clearTimeout(p._swapSafetyTimer);
            p._swapSafetyTimer = setTimeout(() => {
                p._swapInProgress = false;
                p._swapSafetyTimer = null;
            }, 10000);

            p.video.pause();
            p.video.src = "";
            p.video.load();

            // HLS keeps a 0-based element timeline (streamStartOffset stays 0 so
            // subtitle ?start= and the absolute clock both match the master
            // playlist); reset the stall/fallback bookkeeping like _doServerSeek.
            p.streamStartOffset = 0;
            p._hasPlayedData = false;
            p.lastKnownGoodPosition = 0;
            if (p._stallTimer) {
                clearTimeout(p._stallTimer);
                p._stallTimer = null;
            }

            this.initLocalHlsStream(Math.max(0, time));

            // On playing, clear the swap guard and send ONE confirmation broadcast
            // so the server's phantom clock snaps to the new absolute position.
            const onSeekPlaying = () => {
                p._clearSwapInProgress();
                if (!p._destroyed) p._broadcastState();
            };
            const onSeekReady = () => p._clearSwapInProgress();
            p.video.addEventListener('playing', onSeekPlaying, { once: true });
            p.video.addEventListener('loadeddata', onSeekReady, { once: true });
            p.video.addEventListener('error', onSeekReady, { once: true });
        }

        cleanupHls() {
            const p = this.player;
            this._clearPlaybackWatchdog();
            if (p._hlsInstance) {
                p._hlsInstance.destroy();
                p._hlsInstance = null;
            }
            this.destroyLocalHlsSession();
            p.video.src = "";
            p.video.load();
        }

        performServerSeek(time) {
            const p = this.player;
            console.log(`[SimplePlayer] Performing server-side seek to ${time}s`);

            // Local HLS session: recreate the session at the target position (the
            // server encoder starts at 0; resume is a client-side seek after
            // MANIFEST_PARSED, so a fresh session at ?start=time is the seek).
            if (p._hlsSessionId || p._hlsSessionPending) {
                this.recreateLocalHlsSession(time);
                return;
            }

            // F5: External/HLS streams — client-side seek only, never replace src with local transcode
            if (p.externalUrl || p._hlsInstance) {
                var relTime = Math.max(0, time - (p.streamStartOffset || 0));
                if (p._hlsInstance) {
                    p._hlsInstance.startLoad(time);
                }
                p.video.currentTime = relTime;
                p.video.play().catch(function() {});
                return;
            }

            if (time >= p.streamStartOffset && p.video.src) {
                const relativeTime = time - p.streamStartOffset;
                const bufLen = p.video.buffered.length;
                const bufferedEnd = bufLen > 0 ? p.video.buffered.end(bufLen - 1) : 0;
                const inBuffer = p.video.readyState > 0 && relativeTime <= bufferedEnd;
                // F1b: Quality-switching needs a real reload (client seek won't change quality)
                if (inBuffer && !p._qualitySwitching) {
                    console.log(`[SimplePlayer] Client-side seek to ${time}s (relative ${relativeTime}s in buffer)`);
                    p.video.currentTime = relativeTime;
                    p.video.play().catch(e => console.log('[SimplePlayer] Play after seek requires gesture:', e));
                    return;
                }
                console.log(`[SimplePlayer] Past buffer (buffered to ${bufferedEnd}s), using server-side seek`);
            }

            this._doServerSeek(time);

            if (p.lastSelectedTrackId && p.lastSelectedTrackId !== 'off') {
                const reloadSubtitles = async () => {
                    console.log('[SimplePlayer] Reloading subtitles after seek, track:', p.lastSelectedTrackId);
                    await p.loadSubtitles(true);

                    const checkAndRestore = () => {
                        const activeOpt = p.subtitleList?.querySelector(`.subtitle-option[data-id="${p.lastSelectedTrackId}"]`);
                        if (activeOpt) {
                            console.log('[SimplePlayer] Restoring subtitle track after seek:', p.lastSelectedTrackId);
                            activeOpt.click();
                            return true;
                        }
                        return false;
                    };

                    if (!checkAndRestore()) {
                        let attempts = 0;
                        const retryInterval = setInterval(() => {
                            if (checkAndRestore() || attempts++ > 20) {
                                clearInterval(retryInterval);
                                if (attempts > 20) {
                                    console.warn('[SimplePlayer] Failed to restore subtitle track after seek:', p.lastSelectedTrackId);
                                }
                            }
                        }, 300);
                    }
                };

                if (p.video.readyState >= 1) {
                    setTimeout(() => reloadSubtitles(), 500);
                } else {
                    p.video.addEventListener('loadedmetadata', () => {
                        setTimeout(() => reloadSubtitles(), 500);
                    }, { once: true });
                }
            }
        }

        /**
         * Reads X-Stream-Origin from the stream: the content time of element time 0.
         * A seek served from the middle of a segment starts the browser deep into the
         * file, so `requested` is not where the picture begins. Returns null when the
         * header is absent (direct/local playback, older server) so callers keep the
         * requested position.
         */
        async resolveStreamOrigin(url) {
            // The probe must never hold the player hostage: it is served through the
            // same transcode/segment path as the real stream, so on a busy or
            // still-starting encoder it can take tens of seconds. Cap it and let the
            // caller fall back to the requested position instead of leaving the
            // element with no src at all (black player).
            const controller = (typeof AbortController !== 'undefined') ? new AbortController() : null;
            const timeoutMs = 5000;
            const timer = controller ? setTimeout(() => controller.abort(), timeoutMs) : null;
            try {
                // 4096 bytes, not 0-0: tiny ranges are answered by a special empty-206
                // branch that carries no headers, so the origin would always be missing.
                const res = await fetch(url, {
                    headers: { 'Range': 'bytes=0-4095' },
                    cache: 'no-store',
                    credentials: 'include',
                    signal: controller ? controller.signal : undefined
                });
                const raw = res.headers.get('X-Stream-Origin');
                try {
                    if (res.body && res.body.cancel) res.body.cancel();
                } catch (e) {
                    // Body cancel is best-effort; the range is only 4 KB.
                }
                if (raw != null && raw !== '') {
                    const origin = parseFloat(raw);
                    if (!isNaN(origin) && origin >= 0) {
                        return origin;
                    }
                }
            } catch (e) {
                if (e && e.name === 'AbortError') {
                    console.warn('[SimplePlayer] Stream origin probe timed out after ' + timeoutMs + 'ms; using the requested position');
                } else {
                    console.warn('[SimplePlayer] Stream origin probe failed:', e);
                }
            } finally {
                if (timer) clearTimeout(timer);
            }
            return null;
        }

        _doServerSeek(time) {
            const p = this.player;
            console.log(`[SimplePlayer] Server-side seek to ${time}s starting new transcode`);

            // Local HLS session: recreate the session at the target position.
            if (p._hlsSessionId || p._hlsSessionPending) {
                this.recreateLocalHlsSession(time);
                return;
            }

            // Invalidate any seek still in flight. A probe or `once` handler from an
            // older seek must never overwrite this seek's origin or reassign src.
            p._seekGen = (p._seekGen || 0) + 1;
            const seekGen = p._seekGen;

            // The handler installed by the initial open retries with its
            // closure-captured resume time (normally 0). The empty-src below fires a
            // media error, and one second later that handler would reload the stream
            // WITHOUT ?start= - restarting the movie from the beginning. Drop every
            // stale handler and the transcode watchdog before reloading, then install
            // a seek-scoped handler that retries at the seek target instead.
            this.clearStreamErrorHandlers();
            this._clearPlaybackWatchdog();
            if (p._seekRetryTimer) { clearTimeout(p._seekRetryTimer); p._seekRetryTimer = null; }
            p._streamFallbackCount = 0;

            // Seek-scoped error handler. The rest of the player is now unprotected
            // (the initial-open handler was removed above), so without this a real
            // failure would leave a dead player. Retries always reload at the seek
            // target, never at the original resume position. `seekSrcAssigned` makes
            // the handler ignore the empty-src error raised while the old resource is
            // torn down, so it cannot mistake teardown for a failed seek.
            let seekSrcAssigned = false;
            const onSeekError = () => {
                if (!seekSrcAssigned) return;
                if (p._destroyed || seekGen !== p._seekGen) return;
                p._streamFallbackCount = (p._streamFallbackCount || 0) + 1;
                const maxFallbacks = p._maxStreamFallbacks || 3;
                console.error('[SimplePlayer] Seek stream error (fallback ' + p._streamFallbackCount + '/' + maxFallbacks + '):', p.video.error);
                if (p._streamFallbackCount >= maxFallbacks) {
                    p._showLoading('Playback failed after ' + p._streamFallbackCount + ' attempts');
                    return;
                }
                p._showLoading('Stream error, retrying...');
                if (p._seekRetryTimer) clearTimeout(p._seekRetryTimer);
                p._seekRetryTimer = setTimeout(() => {
                    if (p._destroyed || seekGen !== p._seekGen) return;
                    // streamStartOffset may have been corrected down to the real
                    // keyframe/segment origin: that is the safe place to resume.
                    const retryTarget = Math.max(0, p.streamStartOffset || time);
                    console.log(`[SimplePlayer] Retrying seek at ${retryTarget}s (attempt ${p._streamFallbackCount}/${maxFallbacks})`);
                    p.video.src = this.buildStreamUrl(retryTarget);
                    p.video.load();
                    p.video.play().catch((e) => console.log('[SimplePlayer] Seek retry play blocked, awaiting gesture:', e));
                }, Math.min(8000, 2000 * p._streamFallbackCount));
            };
            p._seekErrorHandler = onSeekError;
            p.video.addEventListener('error', onSeekError);

            if (p.buffering) p.buffering.style.display = 'block';

            // The new element sits at 0 (relative) while the server timer may still
            // broadcast the OLD absolute position — drift-sync would seek-yank it into
            // a snap-back loop (B11). Suppress broadcasts/drift-sync during the reload
            // window, exactly like the remote-swap path; cleared on playing/loadeddata/error.
            p._swapInProgress = true;
            if (p._swapSafetyTimer) clearTimeout(p._swapSafetyTimer);
            p._swapSafetyTimer = setTimeout(() => {
                p._swapInProgress = false;
                p._swapSafetyTimer = null;
            }, 10000);

            const resumeAfterSeek = !p.video.paused && !p.video.ended;
            p.video.pause();
            p.video.src = "";
            p.video.load();

            p.streamStartOffset = Math.max(0, time);
            // F7: Reset stall-detection state (mirror switchAudioTrack)
            p._hasPlayedData = false;
            p.lastKnownGoodPosition = 0;
            const seekUrl = this.buildStreamUrl(Math.max(0, time));
            // Learn where the picture actually begins before assigning src: a seek into
            // an existing segment starts the browser deep into that file, and using the
            // requested time as the origin puts the clock (and the subtitle offset) out
            // by the segment start.
            this.resolveStreamOrigin(seekUrl).then((origin) => {
                if (p._destroyed || seekGen !== p._seekGen) return;
                if (origin !== null) {
                    p.streamStartOffset = origin;
                    console.log('[SimplePlayer] Seek origin from stream:', origin);
                    if (p.loadSubtitles) p.loadSubtitles();
                }

                // The seek-scoped error handler is already attached before teardown;
                // mark the real request as live so it starts counting failures here.
                seekSrcAssigned = true;

                p.video.src = seekUrl;
                p.video.load();
                // Autoplay attribute alone is unreliable here: the reload happens
                // outside the original user gesture (remote/keyboard/auto seeks),
                // so the browser may block it silently with no pause event — leaving
                // the playing icon over a frozen frame. Resume explicitly instead.
                if (resumeAfterSeek) {
                    p.video.play().catch((e) => console.log('[SimplePlayer] Resume after seek blocked, awaiting gesture:', e));
                }
            });

            // On playing, clear the swap guard and send ONE confirmation broadcast so
            // the server's phantom clock snaps to the new absolute position (element + offset).
            const onSeekPlaying = () => {
                if (seekGen !== p._seekGen) return;
                p._clearSwapInProgress();
                if (!p._destroyed) p._broadcastState();
            };
            const onSeekReady = () => {
                if (seekGen !== p._seekGen) return;
                p._clearSwapInProgress();
            };
            p.video.addEventListener('playing', onSeekPlaying, { once: true });
            p.video.addEventListener('loadeddata', onSeekReady, { once: true });
            p.video.addEventListener('error', onSeekReady, { once: true });

            // Mid-segment coverage serve lands the element clock at relTarget (segment
            // timeline restarts at its head), not at the requested seek point — re-derive
            // the true content start at load so subtitle ?start= matches the real timeline.
            const onLoadedDataCorrectOffset = () => {
                if (seekGen !== p._seekGen) return;
                const ct = p.video.currentTime || 0;
                const base = Math.max(0, p.streamStartOffset - ct);
                if (Math.abs(base - p.streamStartOffset) > 0.1) {
                    console.log(`[SimplePlayer] Correcting streamStartOffset from ${p.streamStartOffset} to ${base} (keyframe gap: ${ct}s)`);
                    p.streamStartOffset = base;
                    if (p.lastSelectedTrackId && p.lastSelectedTrackId !== 'off') {
                        p.loadSubtitles(true).then(() => {
                            const activeOpt = p.subtitleList?.querySelector(`.subtitle-option[data-id="${p.lastSelectedTrackId}"]`);
                            if (activeOpt) activeOpt.click();
                        });
                    }
                }
            };
            p.video.addEventListener('loadeddata', onLoadedDataCorrectOffset, { once: true });
            // F6: src+load resets playbackRate to 1.0 per HTML spec — re-apply stored value
            p.video.addEventListener('loadedmetadata', function() {
                if (p.state.playbackRate && p.state.playbackRate !== 1) p.video.playbackRate = p.state.playbackRate;
            }, { once: true });
        }

        async _preloadSubtitleTracks() {
            const p = this.player;
            try {
                const tracksRes = await fetch(`/api/video/subtitles/${p.videoId}`);
                if (!tracksRes.ok) return;
                const tracksData = await tracksRes.json();
                const tracks = tracksData.tracks || tracksData.data || [];
                console.log('[SimplePlayer] Pre-loaded', tracks.length, 'subtitle tracks for direct stream');
                p._subtitleTracksData = tracks;

                p.video.querySelectorAll('track').forEach(el => el.remove());
                let activeFound = false;
                const userCorrection = localStorage.getItem('jmedia_subtitle_correction') || 0;
                tracks.forEach(t => {
                    const track = document.createElement('track');
                    track.kind = 'subtitles';
                    const startOffset = p.streamStartOffset || 0;
                    let src = `/api/video/subtitles/track/${t.id}?start=${startOffset}`;
                    if (parseFloat(userCorrection) !== 0) {
                        src += `&correction=${userCorrection}`;
                    }
                    track.src = src;
                    track.srclang = t.language || 'en';
                    track.label = t.displayName || 'Subtitle';
                    track.id = 'subtitle-track-' + t.id;
                    const isActive = p.lastSelectedTrackId == t.id;
                    if (isActive) {
                        track.default = true;
                        activeFound = true;
                    }
                    p.video.appendChild(track);
                });
                if (!activeFound && tracks.length > 0) {
                    const first = p.video.querySelector('track');
                    if (first) first.default = true;
                }
            } catch (e) {
                console.warn('[SimplePlayer] Failed to pre-load subtitles:', e);
            }
        }
    };
})(window);
