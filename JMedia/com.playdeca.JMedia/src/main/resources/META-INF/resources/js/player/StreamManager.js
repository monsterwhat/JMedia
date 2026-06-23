(function(window) {
    'use strict';

    window.PlayerStreamManager = class {
        constructor(player) {
            this.player = player;
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
         */
        static hasNativeHevcSupport() {
            const video = document.createElement('video');
            // Check for HEVC support via MediaSource or video element
            return MediaSource.isTypeSupported('video/mp4; codecs="hev1.1.6.L93.B0"') ||
                   MediaSource.isTypeSupported('video/mp4; codecs="hvc1.1.6.L93.B0"') ||
                   video.canPlayType('video/mp4; codecs="hev1.1.6.L93.B0"') !== '' ||
                   video.canPlayType('video/mp4; codecs="hvc1.1.6.L93.B0"') !== '';
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
            const videoUrl = `/api/video/stream/${p.videoId}.mp4${savedTime > 0 ? `?start=${savedTime}` : ''}`;
            
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
            if (p.needsTranscode && savedTime > 0) {
                console.log('[SimplePlayer] Resuming from ' + savedTime + 's via server-side seek');
                p.video.src = `/api/video/stream/${p.videoId}.mp4?start=${savedTime}`;
            } else {
                p.video.src = `/api/video/stream/${p.videoId}.mp4`;
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

            p.video.addEventListener('error', (e) => {
                p._streamFallbackCount++;
                console.error('[SimplePlayer] Direct stream error (fallback ' + p._streamFallbackCount + '/' + p._maxStreamFallbacks + '):', p.video.error);
                if (p._streamFallbackCount < p._maxStreamFallbacks) {
                    p._showLoading('Stream error, retrying...');
                    setTimeout(() => {
                        const currentTime = p.lastKnownGoodPosition + (p.streamStartOffset || 0);
                        const qualityParam = p._preferredQuality > 0 ? `&quality=${p._preferredQuality}` : '';
                        p.video.src = `/api/video/stream/${p.videoId}.mp4?start=${currentTime}${qualityParam}`;
                        p.video.load();
                        p.video.play().catch(() => {});
                    }, 1000);
                } else {
                    p._showLoading('Playback failed after ' + p._maxStreamFallbacks + ' attempts');
                }
            });

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

        fallbackToDirectStream(savedTime) {
            const p = this.player;
            if (p._fallbackInProgress || p._destroyed) return;
            p._fallbackInProgress = true;
            console.log('[SimplePlayer] Falling back to direct stream');

            this.clearStreamErrorHandlers();

            const absTime = Math.max(0, p.streamStartOffset > 0 ? p.lastKnownGoodPosition + p.streamStartOffset : p.lastKnownGoodPosition);
            p.streamStartOffset = absTime;

            const qualityParam = p._preferredQuality > 0 ? `&quality=${p._preferredQuality}` : '';
            const startParam = absTime > 0 ? `?start=${absTime}` : '';
            const setupFallback = () => {
                p.video.src = `/api/video/stream/${p.videoId}.mp4${startParam}${qualityParam}`;
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

            const currentTime = p.video.currentTime + (p.streamStartOffset || 0);

            const audioParam = (trackIndex !== null && trackIndex >= 0) ? `&audioTrack=${trackIndex}` : '';
            p.video.src = `/api/video/stream/${p.videoId}.mp4?start=${currentTime}${audioParam}`;
            p.video.load();
            p._seekErrorHandler = (e) => {
                console.error('[SimplePlayer] Audio-switched stream error, retrying');
                if (p._destroyed) return;
                p._seekErrorHandler = null;
                const qualityParam = p._preferredQuality > 0 ? `&quality=${p._preferredQuality}` : '';
                p.video.src = `/api/video/stream/${p.videoId}.mp4?start=${currentTime}${qualityParam}`;
                p.video.load();
                p.video.play().catch(() => {});
            };
            p.video.addEventListener('error', p._seekErrorHandler, { once: true });
            p.video.play().catch(() => {});

            p._hasPlayedData = false;
            p.lastKnownGoodPosition = 0;
            p._streamFallbackCount = 0;
            if (p._stallTimer) {
                clearTimeout(p._stallTimer);
                p._stallTimer = null;
            }

            p.streamStartOffset = currentTime;
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
            [p._seekErrorHandler, p._setupStreamErrorHandler].forEach(h => {
                if (h) p.video.removeEventListener('error', h);
            });
            p._seekErrorHandler = p._setupStreamErrorHandler = null;
        }

        cleanupHls() {
            const p = this.player;
            p.video.src = "";
            p.video.load();
        }

        performServerSeek(time) {
            const p = this.player;
            console.log(`[SimplePlayer] Performing server-side seek to ${time}s`);

            if (time >= p.streamStartOffset && p.video.src) {
                const relativeTime = time - p.streamStartOffset;
                const bufLen = p.video.buffered.length;
                const bufferedEnd = bufLen > 0 ? p.video.buffered.end(bufLen - 1) : 0;
                const inBuffer = p.video.readyState > 0 && relativeTime <= bufferedEnd;
                if (inBuffer) {
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

            p.video.pause();
            p.video.src = "";
            p.video.load();

            p.streamStartOffset = Math.max(0, time);
            const audioParam = p.currentAudioTrackIndex !== null ? `&audioTrack=${p.currentAudioTrackIndex}` : '';
            const qualityParam = p._preferredQuality > 0 ? `&quality=${p._preferredQuality}` : '';
            p.video.src = `/api/video/stream/${p.videoId}.mp4?start=${Math.max(0, time)}${audioParam}${qualityParam}`;
            p.video.load();
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
