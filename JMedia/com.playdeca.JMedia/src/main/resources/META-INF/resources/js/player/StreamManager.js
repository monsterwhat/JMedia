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
         * Check if browser has native HEVC support
         *
         * IMPORTANT: MediaSource.isTypeSupported / canPlayType return a FALSE
         * POSITIVE on Firefox (Gecko) — Firefox reports 'hev1'/'hvc1' as
         * supported but its <video>/MSE H264 decoder cannot actually decode
         * HEVC samples, so serving HEVC via copy yields NS_ERROR_DOM_MEDIA_FATAL
         * ("Invalid H264 content"). Exclude Gecko explicitly; the backend
         * (TranscodingService.isTranscodeNeededForWeb) makes the matching call
         * and transcodes HEVC -> H.264 for Firefox. The probe is only trusted
         * on Safari (VideoToolbox) and Chromium (OS HEVC Video Extension on Windows, built-in on macOS).
         */
        static hasNativeHevcSupport() {
            var ua = (navigator.userAgent || '').toLowerCase();
            if (ua.indexOf('firefox') !== -1 || ua.indexOf('gecko') !== -1) {
                return false;
            }
            const video = document.createElement('video');
            return MediaSource.isTypeSupported('video/mp4; codecs="hev1.1.6.L93.B0"') ||
                   MediaSource.isTypeSupported('video/mp4; codecs="hvc1.1.6.L93.B0"') ||
                   video.canPlayType('video/mp4; codecs="hev1.1.6.L93.B0"') !== '' ||
                   video.canPlayType('video/mp4; codecs="hvc1.1.6.L93.B0"') !== '';
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
            const videoUrl = `/api/video/stream/${p.videoId}.mp4?${savedTime > 0 ? `start=${savedTime}&` : ''}trace=${this._traceId()}`;
            
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
            const _traceId = () => `${Date.now()}_${Math.random().toString(36).slice(2,8)}`;
            /* Clear any previously painted frame (e.g. the previous video's last
             * frame) so a frozen frame from an old movie cannot linger while the
             * new source loads. No-op on initial load (fresh element, no src). */
            try {
                p.video.removeAttribute('src');
                p.video.load();
            } catch (e) {}
            if (p.needsTranscode && savedTime > 0) {
                console.log('[SimplePlayer] Resuming from ' + savedTime + 's via server-side seek');
                p.video.src = `/api/video/stream/${p.videoId}.mp4?start=${savedTime}&trace=${_traceId()}`;
            } else {
                p.video.src = `/api/video/stream/${p.videoId}.mp4?trace=${_traceId()}`;
            }

            p._showLoading('Loading video...');

            p.video.addEventListener('loadedmetadata', () => {
                console.log('[SimplePlayer] Direct stream metadata loaded, duration:', p.video.duration);
                p.applyInitialState();
                p.loadSubtitles();
            }, { once: true });

            p.video.addEventListener('playing', () => {
                p._streamFallbackCount = 0;
                p._hideLoading();
            }, { once: true });

            // Skip if caller (simple-player) manages its own persistent error handler — avoids double-counting the fallback budget.
            if (!p._setupStreamErrorHandler) {
                p._streamErrorHandler = (e) => {
                    p._streamFallbackCount++;
                    console.error('[SimplePlayer] Direct stream error (fallback ' + p._streamFallbackCount + '/' + p._maxStreamFallbacks + '):', p.video.error);
                    if (p._streamFallbackCount < p._maxStreamFallbacks) {
                        p._showLoading('Stream error, retrying...');
                        setTimeout(() => {
                            const currentTime = p.lastKnownGoodPosition + (p.streamStartOffset || 0);
                            p.streamStartOffset = currentTime;
                            p.lastKnownGoodPosition = 0;
                            const qualityParam = p._preferredQuality > 0 ? `&quality=${p._preferredQuality}` : '';
                            p.video.src = `/api/video/stream/${p.videoId}.mp4?start=${currentTime}${qualityParam}&trace=${_traceId()}`;
                            p.video.load();
                            p.video.play().catch(() => {});
                        }, 1000);
                    } else {
                        p._showLoading('Playback failed after ' + p._maxStreamFallbacks + ' attempts');
                    }
                };
                p.video.addEventListener('error', p._streamErrorHandler);
            }

            p.video.play().catch(e => {
                console.log('[SimplePlayer] Play requires user gesture:', e);
            });
        }

        initExternalStream() {
            const p = this.player;
            const url = p.externalUrl;
            const savedTime = parseFloat(p.container.dataset.startTime || 0);
            const isIOS = p.utils.isIOS();

            console.log('[SimplePlayer] External stream:', url);

            // HLS (m3u8) requires hls.js (MSE) in Chromium/Firefox — only Safari/iOS
            // plays it natively in a <video> element. Fall back to native for those.
            const isHls = /\.m3u8(\?|#|$)/i.test(url) || /\.m3u8(\?|#|$)/i.test(p.externalOriginalUrl || '');
            if (isHls && typeof Hls !== 'undefined' && Hls.isSupported() && !isIOS) {
                this._initHlsExternalStream(url, savedTime);
                return;
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

        // Instance is stored on the player so destroy()/cleanupHls() can tear it down.
        _initHlsExternalStream(url, savedTime) {
            const p = this.player;

            if (p._hlsInstance) {
                p._hlsInstance.destroy();
                p._hlsInstance = null;
            }

            const hls = new Hls({
                // Start near the live edge for live channels; harmless for VOD.
                liveSyncDurationCount: 3,
                liveMaxLatencyDurationCount: 6,
                maxLiveSyncPlaybackRate: 1.5
            });
            p._hlsInstance = hls;
            p._hlsNetworkRetries = 0;

            p._showLoading('Loading live stream...');
            hls.loadSource(url);
            hls.attachMedia(p.video);

            hls.on(Hls.Events.MANIFEST_PARSED, () => {
                if (p._destroyed || p._hlsInstance !== hls) return;
                console.log('[SimplePlayer] HLS manifest parsed, starting playback');
                if (savedTime > 0) p.video.currentTime = savedTime;
                p.applyInitialState();
                p.video.play().catch(e => {
                    console.log('[SimplePlayer] Play requires user gesture:', e);
                });
            });

            hls.on(Hls.Events.ERROR, (event, data) => {
                if (!data || p._destroyed || p._hlsInstance !== hls) return;
                if (!data.fatal) return;
                console.error('[SimplePlayer] Fatal HLS error:', data.type, data.details);
                if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
                    p._hlsNetworkRetries = (p._hlsNetworkRetries || 0) + 1;
                    if (p._hlsNetworkRetries < 3) {
                        hls.startLoad();
                    } else {
                        p._showLoading('Playback error');
                        hls.destroy();
                        p._hlsInstance = null;
                    }
                } else {
                    p._showLoading('Playback error');
                }
            });

            p.video.addEventListener('playing', () => p._hideLoading(), { once: true });

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

            const params = [];
            if (absTime > 0) params.push(`start=${absTime}`);
            if (p._preferredQuality > 0) params.push(`quality=${p._preferredQuality}`);
            params.push(`trace=${this._traceId()}`);
            const queryString = params.length ? '?' + params.join('&') : '';
            const setupFallback = () => {
                p.video.src = `/api/video/stream/${p.videoId}.mp4${queryString}`;
                p.video.load();
                p.video.addEventListener('loadedmetadata', () => {
                    p._fallbackInProgress = false;
                    p.applyInitialState();
                    p.loadSubtitles();
                }, { once: true });
                p.video.addEventListener('playing', () => {
                    p._fallbackInProgress = false;
                    p._hideLoading();
                }, { once: true });
                p.video.addEventListener('error', () => {
                    p._fallbackInProgress = false;
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

            if (p._seekErrorHandler) {
                p.video.removeEventListener('error', p._seekErrorHandler);
                p._seekErrorHandler = null;
            }
            if (p._setupStreamErrorHandler) {
                p.video.removeEventListener('error', p._setupStreamErrorHandler);
                p._setupStreamErrorHandler = null;
            }

            p.currentAudioTrackIndex = trackIndex;

            const savedTime = p.video.currentTime + (p.streamStartOffset || 0);

            const audioParam = (trackIndex !== null && trackIndex >= 0) ? `&audioTrack=${trackIndex}` : '';
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

            const qualityParam = p._preferredQuality > 0 ? `&quality=${p._preferredQuality}` : '';
            if (p.needsTranscode) {
                p.streamStartOffset = Math.max(0, savedTime);
                p.video.src = `/api/video/stream/${p.videoId}.mp4?start=${Math.max(0, savedTime)}${audioParam}${qualityParam}&trace=${this._traceId()}`;
            } else {
                p.streamStartOffset = 0;
                const params = [];
                if (trackIndex !== null && trackIndex >= 0) params.push(`audioTrack=${trackIndex}`);
                if (p._preferredQuality > 0) params.push(`quality=${p._preferredQuality}`);
                params.push(`trace=${this._traceId()}`);
                p.video.src = `/api/video/stream/${p.videoId}.mp4${params.length ? '?' + params.join('&') : ''}`;
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

        cleanupHls() {
            const p = this.player;
            if (p._hlsInstance) {
                p._hlsInstance.destroy();
                p._hlsInstance = null;
            }
            p.video.src = "";
            p.video.load();
        }

        performServerSeek(time) {
            const p = this.player;
            console.log(`[SimplePlayer] Performing server-side seek to ${time}s`);

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

        _doServerSeek(time) {
            const p = this.player;
            console.log(`[SimplePlayer] Server-side seek to ${time}s starting new transcode`);

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

            p.video.pause();
            p.video.src = "";
            p.video.load();

            p.streamStartOffset = Math.max(0, time);
            // F7: Reset stall-detection state (mirror switchAudioTrack)
            p._hasPlayedData = false;
            p.lastKnownGoodPosition = 0;
            const audioParam = p.currentAudioTrackIndex !== null ? `&audioTrack=${p.currentAudioTrackIndex}` : '';
            const qualityParam = p._preferredQuality > 0 ? `&quality=${p._preferredQuality}` : '';
            p.video.src = `/api/video/stream/${p.videoId}.mp4?start=${Math.max(0, time)}${audioParam}${qualityParam}&trace=${this._traceId()}`;
            p.video.load();

            // On playing, clear the swap guard and send ONE confirmation broadcast so
            // the server's phantom clock snaps to the new absolute position (element + offset).
            const onSeekPlaying = () => {
                p._clearSwapInProgress();
                if (!p._destroyed) p._broadcastState();
            };
            const onSeekReady = () => p._clearSwapInProgress();
            p.video.addEventListener('playing', onSeekPlaying, { once: true });
            p.video.addEventListener('loadeddata', onSeekReady, { once: true });
            p.video.addEventListener('error', onSeekReady, { once: true });

            // Mid-segment coverage serve lands the element clock at relTarget (segment
            // timeline restarts at its head), not at the requested seek point — re-derive
            // the true content start at load so subtitle ?start= matches the real timeline.
            const onLoadedDataCorrectOffset = () => {
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
