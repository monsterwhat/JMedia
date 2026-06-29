if (typeof window.SimplePlayer === 'undefined') {
    window.SimplePlayer = class SimplePlayer {
        constructor(config) {
            console.log('[SimplePlayer] Initializing...', config);
            this.container = document.getElementById(config.containerId);
            this.video = document.getElementById(config.videoId);
            this.videoId = config.currentVideoId;
            this.videoType = this.container.dataset.type || 'movie';
            
            if (!this.container || !this.video) return;

            // Basic setup
            this.video.setAttribute('playsinline', 'true');
            this.video.setAttribute('webkit-playsinline', 'true');
            this.video.controls = false;

            this.needsTranscode = this.container.dataset.needsTranscode === 'true';
            this.streamStartOffset = 0; // Track where stream started (for server-side seek)
            this.lastKnownGoodPosition = 0; // Last safe position from timeupdate
            this.profileId = localStorage.getItem('activeProfileId') || '1';
            // Unique per-tab device token for isolating HLS sessions, transcode keys, and progress
            this.deviceToken = sessionStorage.getItem('jmedia_device_token');
            if (!this.deviceToken) {
                this.deviceToken = crypto.randomUUID();
                sessionStorage.setItem('jmedia_device_token', this.deviceToken);
            }
            this.volumeKey = 'jmedia_video_volume_' + this.profileId;
            this.muteKey = 'jmedia_video_mute_' + this.profileId;
            this.userActiveTimeout = null;
            this.isIOSNativeFullscreen = false;
            this._streamFallbackCount = 0;
            this._maxStreamFallbacks = 2;
            this._preferredQuality = 0; // 0 = source, 480/720/1080/2160
            this._previousQuality = 0;
            this._qualitySwitching = false;
            this._hasPlayedData = false;
            this._stallTimer = null;
            this._destroyed = false;
            
            // Critical: Duration parsing
            const rawDur = parseFloat(this.container.dataset.duration || 0);
            this.totalDuration = rawDur > 5000 ? rawDur / 1000 : rawDur; 
            
            this.state = {
                playing: false,
                volume: parseFloat(localStorage.getItem(this.volumeKey) || '0.7'),
                muted: localStorage.getItem(this.muteKey) === 'true',
                lastSeekTime: 0
            };
            
            // Track currently selected audio track index for non-HLS streams
            this.currentAudioTrackIndex = null;
 
             this.markers = {
                 introStart: parseFloat(this.container.dataset.introStart || 0),
                 introEnd: parseFloat(this.container.dataset.introEnd || 0),
                 outroStart: parseFloat(this.container.dataset.outroStart || 0),
                 outroEnd: parseFloat(this.container.dataset.outroEnd || 0),
                 recapStart: parseFloat(this.container.dataset.recapStart || 0),
                 recapEnd: parseFloat(this.container.dataset.recapEnd || 0)
             };
             
             // Store original marker values for debug comparison
             this.originalMarkers = {...this.markers};
             
             // Track marker sources for debugging
             this.markerSources = {
                 introStart: 'FILE',
                 introEnd: 'FILE',
                 outroStart: 'FILE',
                 outroEnd: 'FILE',
                 recapStart: 'FILE',
                 recapEnd: 'FILE'
             };
            
            // Auto-skip settings
            this.autoSkipIntro = this.container.dataset.autoSkipIntro === 'true';
            this.autoSkipRecap = this.container.dataset.autoSkipRecap === 'true';
            this.autoSkipOutro = this.container.dataset.autoSkipOutro === 'true';
            this._autoSkipUndoTime = 0;
            this._autoSkipSection = null;
            this._autoSkipTimer = null;
            this._isUndoing = false;
            
            this.storyboard = { metadata: null, loaded: false };
            const videoTrack = localStorage.getItem('jmedia_last_track_' + this.videoId);
            const globalTrack = sessionStorage.getItem('jmedia_global_subtitle_track');
            this.lastSelectedTrackId = videoTrack || globalTrack;

            // Read audio preference from container data attributes (set from VideoState)
            this.preferredAudioLanguage = this.container.dataset.preferredAudioLanguage || null;
            this.defaultAudioTrackId = this.container.dataset.defaultAudioTrackId || null;
            console.log('[SimplePlayer] Audio preferences - Language:', this.preferredAudioLanguage, 'TrackId:', this.defaultAudioTrackId);
            
            window.currentPlayerInstance = this;
            window.player = this;

            console.log(`[SimplePlayer] Initialized for video ${this.videoId}. Resume time from server: ${this.container.dataset.startTime}s`);
            this.initialResumeTime = parseFloat(this.container.dataset.startTime || 0);

            // Save progress on page hide/close (more reliable than onbeforeunload on iOS)
            // Also pause video to prevent background audio after navigation
            // Cleanup HLS session to stop FFmpeg processes on server
            this._cleanupOnUnload = () => {
                this.saveProgressNow();
                // Use sendBeacon for reliable HLS session cleanup on navigation
                if (this.hlsSessionId) {
                    navigator.sendBeacon(`/api/hls/session/${this.hlsSessionId}?_method=DELETE`, '');
                    this.hlsSessionId = null;
                }
                this.video.pause();
                this.video.src = "";
                this.setMusicSuspended(false);
            };
            window.addEventListener('pagehide', this._cleanupOnUnload);
            window.addEventListener('beforeunload', this._cleanupOnUnload);

            if (this._isIOS()) {
                const videoPath = (this.container.dataset.path || '').toLowerCase();
                if (videoPath.endsWith('.mkv')) {
                    this.video.addEventListener('error', () => {
                        if (this._iosMkvToastShown) return;
                        this._iosMkvToastShown = true;
                        const toastContainer = document.getElementById('toast-container');
                        if (toastContainer) {
                            const vid = this.videoId;
                            const toast = document.createElement('div');
                            toast.className = 'toast warning show';
                            toast.id = 'ios-mkv-toast';
                            toast.style.minWidth = '320px';
                            toast.innerHTML =
                                '<div class="toast-content" style="flex-direction:column;align-items:stretch;">' +
                                '<div style="display:flex;align-items:center;gap:10px;margin-bottom:8px;">' +
                                '<i class="pi pi-exclamation-triangle toast-icon"></i>' +
                                '<span>MKV may not play on iOS. Convert to MP4?</span></div>' +
                                '<button class="button is-small is-warning is-fullwidth" ' +
                                'onclick="this.disabled=true;this.textContent=\'Starting...\';' +
                                "fetch('/api/video/manage/convert/" + vid + "',{method:'POST'})" +
                                '.then(r=>r.json()).then(d=>{' +
                                "if(d.jobId){window.Toast.success('Conversion started!');" +
                                "document.getElementById('ios-mkv-toast')?.remove();" +
                                "setTimeout(function(){location.reload()},5000)" +
                                '}else{window.Toast.error(\'Failed to start conversion\')}' +
                                '}).catch(function(){window.Toast.error(\'Conversion failed\')})' +
                                '" style="white-space:nowrap;">Convert to MP4</button></div>';
                            toastContainer.appendChild(toast);
                        }
                    });
                }
            }

            this.init();

            // Restore fullscreen if navigation occurred while in fullscreen
            if (sessionStorage.getItem('jmedia_restore_fullscreen') === 'true') {
                sessionStorage.removeItem('jmedia_restore_fullscreen');
                // Wait for video to be ready before restoring fullscreen
                const restoreFs = () => {
                    const isFullscreen = document.fullscreenElement || document.webkitFullscreenElement;
                    if (this.container && !isFullscreen) {
                        const fsPromise = this.container.requestFullscreen ?
                            this.container.requestFullscreen() :
                            this.video.webkitEnterFullscreen ? Promise.resolve(this.video.webkitEnterFullscreen()) :
                            Promise.resolve();
                        fsPromise.catch(() => {
                            // Retry once after delay
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

        _isIOS() {
            return /iPhone|iPad|iPod|iPadOS/i.test(navigator.userAgent) || 
                   (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1) ||
                   (navigator.platform === 'iPhone' || navigator.platform === 'iPad');
        }

        _isMac() {
            return navigator.platform === 'MacIntel' && navigator.maxTouchPoints <= 1;
        }

        applyAudioPreference() {
            // Apply saved audio track preference from container data attributes or localStorage
            const videoId = this.videoId;
            let trackToApply = null;
            let isDefault = false;

            // Priority 1: localStorage (most recent user selection for this video)
            const savedTrack = localStorage.getItem('jmedia_audio_track_' + videoId);
            if (savedTrack) {
                if (savedTrack === 'default') {
                    isDefault = true;
                    trackToApply = -1;
                } else {
                    trackToApply = parseInt(savedTrack);
                }
                console.log('[SimplePlayer] Found saved track in localStorage:', savedTrack);
            }

            // Priority 2: defaultAudioTrackId from Video entity (first time watching this video)
            if (trackToApply === null && this.defaultAudioTrackId) {
                trackToApply = parseInt(this.defaultAudioTrackId);
                console.log('[SimplePlayer] Using defaultAudioTrackId:', trackToApply);
            }

            // Apply the track if valid
            if (isDefault || (trackToApply !== null && !isNaN(trackToApply))) {
                setTimeout(() => {
                    if (isDefault && this.setAudioTrack) {
                        this.setAudioTrack('default');
                    } else {
                        // For hls.js
                        if (this.hlsInstance) {
                            this.hlsInstance.audioTrack = trackToApply;
                        }
                        // For non-HLS streams
                        if (window.player && window.player.switchAudioTrack) {
                            window.player.switchAudioTrack(trackToApply);
                        }
                        // For native HLS (Safari/iOS)
                        if (this.video && this.video.audioTracks && this.video.audioTracks.length > 0) {
                            this.video.audioTracks.forEach((track, idx) => {
                                track.enabled = (idx === trackToApply);
                            });
                        }
                    }
                    this.currentAudioTrackIndex = isDefault ? null : trackToApply;
                    console.log('[SimplePlayer] Applied audio preference:', isDefault ? 'default' : trackToApply);
                }, 2500); // Wait for player to be ready
            }
        }
 
        async _preloadSubtitleTracks() {
            try {
                const tracksRes = await fetch(`/api/video/subtitles/${this.videoId}`);
                if (!tracksRes.ok) return;
                const tracksData = await tracksRes.json();
                const tracks = tracksData.tracks || tracksData.data || [];
                console.log('[SimplePlayer] Pre-loaded', tracks.length, 'subtitle tracks for direct stream');
                this._subtitleTracksData = tracks;

                this.video.querySelectorAll('track').forEach(el => el.remove());
                let activeFound = false;
                const userCorrection = localStorage.getItem('jmedia_subtitle_correction') || 0;
                tracks.forEach(t => {
                    const track = document.createElement('track');
                    track.kind = 'subtitles';
                    const startOffset = this.streamStartOffset || 0;
                    let src = `/api/video/subtitles/track/${t.id}?start=${startOffset}`;
                    if (parseFloat(userCorrection) !== 0) {
                        src += `&correction=${userCorrection}`;
                    }
                    track.src = src;
                    track.srclang = t.language || 'en';
                    track.label = t.displayName || 'Subtitle';
                    track.id = 'subtitle-track-' + t.id;
                    const isActive = this.lastSelectedTrackId == t.id;
                    if (isActive) {
                        track.default = true;
                        activeFound = true;
                    }
                    this.video.appendChild(track);
                });
                if (!activeFound && tracks.length > 0) {
                    const first = this.video.querySelector('track');
                    if (first) first.default = true;
                }
            } catch (e) {
                console.warn('[SimplePlayer] Failed to pre-load subtitles:', e);
            }
        }

        _syncSubtitleForNativeFullscreen() {
            this.video.querySelectorAll('track').forEach(el => el.remove());

            const selectedId = this.lastSelectedTrackId;
            if (!selectedId || selectedId === 'off') {
                console.log('[SimplePlayer] Subtitles off, no track to sync for fullscreen');
                return;
            }

            const track = document.createElement('track');
            track.kind = 'subtitles';
            const startOffset = this.streamStartOffset || 0;
            const userCorrection = localStorage.getItem('jmedia_subtitle_correction') || 0;
            let src = `/api/video/subtitles/track/${selectedId}?start=${startOffset}`;
            if (parseFloat(userCorrection) !== 0) {
                src += `&correction=${userCorrection}`;
            }
            track.src = src;
            track.default = true;
            let displayName = 'Subtitle';
            if (this._subtitleTracksData) {
                const found = this._subtitleTracksData.find(t => t.id == selectedId);
                if (found) displayName = found.displayName || found.filename || 'Subtitle';
            }
            track.label = displayName;
            this.video.appendChild(track);
            console.log('[SimplePlayer] Synced subtitle track for native fullscreen:', selectedId);
        }

        _restoreSubtitlesAfterFullscreen() {
            this.video.querySelectorAll('track').forEach(el => el.remove());

            if (this._subtitleTracksData) {
                const userCorrection = localStorage.getItem('jmedia_subtitle_correction') || 0;
                this._subtitleTracksData.forEach(t => {
                    const track = document.createElement('track');
                    track.kind = 'subtitles';
                    const startOffset = this.streamStartOffset || 0;
                    let src = `/api/video/subtitles/track/${t.id}?start=${startOffset}`;
                    if (parseFloat(userCorrection) !== 0) {
                        src += `&correction=${userCorrection}`;
                    }
                    track.src = src;
                    track.srclang = t.language || 'en';
                    track.label = t.displayName || 'Subtitle';
                    track.id = 'subtitle-track-' + t.id;
                    if (this.lastSelectedTrackId == t.id) track.default = true;
                    this.video.appendChild(track);
                });
                if (this.lastSelectedTrackId && this.lastSelectedTrackId !== 'off') {
                    const restoreMode = () => {
                        if (!this.video.textTracks) return;
                        const target = Array.from(this.video.textTracks).find(tr => {
                            const found = this._subtitleTracksData.find(t => t.id == this.lastSelectedTrackId);
                            return found && (tr.label === (found.displayName || 'Subtitle'));
                        });
                        if (target) {
                            target.mode = 'showing';
                        } else {
                            setTimeout(restoreMode, 200);
                        }
                    };
                    setTimeout(restoreMode, 300);
                }
            }
        }

        async initHlsStream(savedTime) {
            if (this._destroyed || !document.body.contains(this.container)) return;
            // HLS server-side seek starts from savedTime — set streamStartOffset so
            // timeupdate computes displayTime = video.currentTime + savedTime correctly.
            this.streamStartOffset = savedTime;
            // Create HLS session with device-scoped ID so devices/profiles don't collide
            const sessionId = `vid-${this.videoId}-${this.deviceToken}`;
            this.hlsSessionId = sessionId;

            const isIOS = this._isIOS();
            const isFirefox = /Firefox/i.test(navigator.userAgent);
            const isSafari = /Safari/i.test(navigator.userAgent) && !/Chrome/i.test(navigator.userAgent);
            
            // Create session on server
            try {
                const quality = this._preferredQuality || 0;
                const sessionResp = await fetch(`/api/hls/session/${this.videoId}?start=${savedTime || 0}&quality=${quality}&device=${this.deviceToken}`, {
                    method: 'POST'
                });
                if (!sessionResp.ok) {
                    const errText = await sessionResp.text().catch(() => 'no body');
                    throw new Error(`Failed to create HLS session (HTTP ${sessionResp.status}): ${errText}`);
                }
                const sessionData = await sessionResp.json();
                console.log('[SimplePlayer] HLS session created:', sessionData);
            } catch (e) {
                console.error('[SimplePlayer] Session creation failed:', e);
                this._showLoading('HLS setup failed, trying direct stream...');
                this.fallbackToDirectStream();
                return;
            }

            // Use master playlist URL with multi-audio support
            const masterPlaylistUrl = `/api/hls/master/${sessionId}.m3u8`;
            const videoPlaylistUrl = `/api/hls/playlist/${sessionId}/video_stream.m3u8`;

            // Use hls.js on any platform where it's supported (falls back to native HLS otherwise)
            const hlsAvailable = typeof Hls !== 'undefined' && Hls.isSupported();
            const useHlsJs = hlsAvailable;
            
            // Native HLS only for desktop Safari or iOS where hls.js is unavailable
            const supportsNativeHls = !useHlsJs && (isSafari || isIOS);
            this._isNativeHls = supportsNativeHls;

            this._showLoading('Preparing video...');

            // Pre-load all subtitle tracks before attaching HLS source,
            // so Safari picks up the <track> elements (must exist before source is set)
            let subtitleTracks = [];
            try {
                const tracksRes = await fetch(`/api/video/subtitles/${this.videoId}`);
                if (tracksRes.ok) {
                    const tracksData = await tracksRes.json();
                    subtitleTracks = tracksData.tracks || tracksData.data || [];
                    this._subtitleTracksData = subtitleTracks;
                    console.log('[SimplePlayer] Pre-loaded', subtitleTracks.length, 'subtitle tracks for HLS');
                }
            } catch (e) {
                console.warn('[SimplePlayer] Failed to pre-load subtitles:', e);
            }

            const loadSubtitles = tracks => {
                this.video.querySelectorAll('track').forEach(el => el.remove());
                let activeFound = false;
                tracks.forEach(t => {
                    const track = document.createElement('track');
                    track.kind = 'subtitles';
                    const startOffset = 0;
                    let src = `/api/video/subtitles/track/${t.id}?start=${startOffset}`;
                    track.src = src;
                    track.srclang = t.language || 'en';
                    track.label = t.displayName || 'Subtitle';
                    track.id = 'subtitle-track-' + t.id;
                    const isActive = this.lastSelectedTrackId == t.id;
                    if (isActive) {
                        track.default = true;
                        activeFound = true;
                    }
                    this.video.appendChild(track);
                });
                if (!activeFound && tracks.length > 0) {
                    const first = this.video.querySelector('track');
                    if (first) first.default = true;
                }
            };
            if (subtitleTracks.length > 0) {
                loadSubtitles(subtitleTracks);
            }

            if (supportsNativeHls) {
                console.log('[SimplePlayer] Using native HLS (Safari/iOS) with master playlist');
                this._hlsRetryCount = 0;
                this._hlsMaxRetries = 5;
                this.video.src = masterPlaylistUrl;
                this.video.addEventListener('loadedmetadata', () => {
                    this._hlsRetryCount = 0;
                    this.applyInitialState();
                    
                    // Apply saved audio track preference for native HLS
                    this.applyAudioPreference();
                }, { once: true });
                this._hlsErrorHandler = () => {
                    this._hlsRetryCount = (this._hlsRetryCount || 0) + 1;
                    console.error('[SimplePlayer] Native HLS error (attempt ' + this._hlsRetryCount + '/' + this._hlsMaxRetries + '):', this.video.error);
                    if (this._hlsRetryCount >= this._hlsMaxRetries) {
                        console.log('[SimplePlayer] HLS failed after ' + this._hlsMaxRetries + ' retries, falling back to direct stream');
                        this._showLoading('Switching to direct playback...');
                        this.fallbackToDirectStream();
                        return;
                    }
                    this._showLoading('Reconnecting... (' + this._hlsRetryCount + '/' + this._hlsMaxRetries + ')');
                    setTimeout(() => {
                        this.video.src = masterPlaylistUrl;
                        this.video.load();
                        this.video.play().catch(() => {});
                    }, 2000);
                };
                this.video.addEventListener('error', this._hlsErrorHandler);
                this.video.play().catch(() => console.log('[SimplePlayer] User gesture required'));
            } else if (useHlsJs) {
                console.log('[SimplePlayer] Using hls.js with multi-audio support');
                const hls = new Hls({
                    enableWorker: true,
                    lowLatencyMode: false,
                    manifestLoadingMaxRetry: 10,
                    manifestLoadingRetryDelay: 1000,
                    manifestLoadingMaxRetryTimeout: 10000,
                    fragLoadingMaxRetry: 10,
                    fragLoadingMaxRetryTimeout: 10000
                });
                this.hlsInstance = hls;
                
                // Load master playlist for multi-audio support
                hls.loadSource(masterPlaylistUrl);
                hls.attachMedia(this.video);
                
                // Handle audio track selection
                hls.on(Hls.Events.MANIFEST_PARSED, (event, data) => {
                    console.log('[SimplePlayer] HLS manifest parsed, audio tracks:', hls.audioTracks);
                    this._hlsJsRetryCount = 0;
                    this.updateAudioTrackSelector(hls);
                    
                    setTimeout(() => {
                        this.video.play().catch(console.error);
                        
                        // Apply saved audio track preference
                        this.applyAudioPreference();
                    }, 2000);
                });
                
                // Update audio tracks when they change
                hls.on(Hls.Events.AUDIO_TRACKS_UPDATED, (event, data) => {
                    console.log('[SimplePlayer] Audio tracks updated:', data.audioTracks);
                    this.updateAudioTrackSelector(hls);
                });
                
                hls.on(Hls.Events.ERROR, (event, data) => {
                    console.error('[SimplePlayer] HLS error:', data);
                    if (data.fatal && data.details && data.details.includes('mismatch')) {
                        data.fatal = false;
                        hls.reset();
                        setTimeout(() => hls.loadSource(masterPlaylistUrl), 100);
                        return;
                    }
                    if (data.fatal) {
                        this._hlsJsRetryCount = (this._hlsJsRetryCount || 0) + 1;
                        if (this._hlsJsRetryCount >= 5) {
                            console.log('[SimplePlayer] HLS fatal error after 5 retries, falling back to direct stream at last known position');
                            hls.destroy();
                            this.hlsInstance = null;
                            const pos = this.lastKnownGoodPosition + (this.streamStartOffset || 0);
                            this._showLoading('Switching to direct playback...');
                            this.fallbackToDirectStream(pos);
                            return;
                        }
                        this._showLoading('Reconnecting... (' + this._hlsJsRetryCount + '/5)');
                        hls.destroy();
                        const newHls = new Hls({
                            enableWorker: true,
                            lowLatencyMode: false,
                            manifestLoadingMaxRetry: 10,
                            manifestLoadingRetryDelay: 1000,
                            manifestLoadingMaxRetryTimeout: 10000,
                            fragLoadingMaxRetry: 10,
                            fragLoadingMaxRetryTimeout: 10000
                        });
                        this.hlsInstance = newHls;
                        newHls.loadSource(masterPlaylistUrl);
                        newHls.attachMedia(this.video);
                        newHls.on(Hls.Events.MANIFEST_PARSED, (event, data) => {
                            this._hlsJsRetryCount = 0;
                            this.updateAudioTrackSelector(newHls);
                            setTimeout(() => this.video.play().catch(console.error), 2000);
                        });
                        newHls.on(Hls.Events.AUDIO_TRACKS_UPDATED, (event, data) => {
                            this.updateAudioTrackSelector(newHls);
                        });
                        return;
                    }
                    if (data.type === Hls.ErrorTypes.NETWORK_ERROR || data.type === Hls.ErrorTypes.MEDIA_ERROR) {
                        hls.startLoad();
                    }
                });
            } else {
                console.error('[SimplePlayer] No HLS support - cannot play video');
                this._showLoading('HLS not supported on this browser');
            }

            // Populate subtitle menu (tracks already pre-created for native HLS)
            this.loadSubtitles().catch(e => console.warn('[SimplePlayer] Subtitle menu failed:', e));

            this.video.addEventListener('playing', () => this._hideLoading(), { once: true });
        }

        updateAudioTrackSelector(hls) {
            if (!hls || !hls.audioTracks || hls.audioTracks.length <= 1) return;
            
            const selector = document.getElementById('audioTrackSelector');
            if (!selector) return;
            
            const select = selector.querySelector('select') || document.createElement('select');
            if (!select.parentElement) {
                select.className = 'audio-track-select';
                select.style.cssText = 'background: #333; color: white; border: 1px solid #48c774; border-radius: 4px; padding: 4px 8px; font-size: 0.9rem;';
                selector.innerHTML = '';
                selector.appendChild(select);
            }
            
            // Clear and repopulate options
            select.innerHTML = '';
            hls.audioTracks.forEach((track, index) => {
                const option = document.createElement('option');
                option.value = index;
                option.textContent = track.name || `Audio ${index + 1}`;
                if (track.default) option.selected = true;
                select.appendChild(option);
            });
            
            select.onchange = (e) => {
                const trackId = parseInt(e.target.value);
                console.log('[SimplePlayer] Switching audio track to:', trackId);
                hls.audioTrack = trackId;
            };
            
            selector.style.display = 'block';
            console.log('[SimplePlayer] Audio track selector updated with', hls.audioTracks.length, 'tracks');
        }

        init() {
            this.buildUI();
            this.attachEvents();
            this.applyInitialState();
            this.updateSubtitle();
            
            // Bind and attach keydown event listener for debugging shortcuts
            this._boundKeydown = this.handleKeydown.bind(this);
            window.addEventListener('keydown', this._boundKeydown);
            
            // Check for external URL playback
            this.externalUrl = this.container.dataset.externalUrl || null;
            this.externalOriginalUrl = this.container.dataset.externalOriginalUrl || null;
            this.externalId = this.container.dataset.externalId || null;
            this.externalIsHls = this.container.dataset.externalIsHls === 'true';
            
            if (this.externalUrl) {
                this.initExternalStream();
                return;
            }

            const savedTime = parseFloat(this.container.dataset.startTime || 0);
            
            // HLS is available as a fallback if the direct stream fails.
            const isIOS = this._isIOS();
            const isMac = this._isMac();
            const isSafari = /Safari/i.test(navigator.userAgent) && !/Chrome/i.test(navigator.userAgent);
            const hasMultipleAudio = this.container.dataset.hasMultipleAudio === 'true';
            if (this.needsTranscode) {
                // Safari requires <track> elements before video.src or subtitles won't render
                const qualityParam = this._preferredQuality > 0 ? `&quality=${this._preferredQuality}` : '';
                const setupStream = () => {
                    if (savedTime > 0) {
                        this.streamStartOffset = savedTime;
                        this.video.src = `/api/video/stream/${this.videoId}?start=${savedTime}${qualityParam}`;
                    } else {
                        this.streamStartOffset = 0;
                        this.video.src = `/api/video/stream/${this.videoId}${qualityParam}`;
                    }
                    this.loadSubtitles();
                    if (hasMultipleAudio) {
                        this.loadAudioTrackSelector();
                    }
                    this.video.play().catch(e => {
                        console.log('[SimplePlayer] Play requires user gesture:', e);
                    });
                };
                const setupStreamErrorHandler = () => {
                    if (this._destroyed || !document.body.contains(this.container)) return;
                    this._streamFallbackCount = (this._streamFallbackCount || 0) + 1;
                    console.error('[SimplePlayer] Direct stream error (fallback ' + this._streamFallbackCount + '/' + this._maxStreamFallbacks + '):', this.video.error);
                    if (this._streamFallbackCount < this._maxStreamFallbacks) {
                        this._showLoading('Stream error, trying HLS...');
                        setTimeout(() => {
                            if (this._destroyed || !document.body.contains(this.container)) return;
                            const currentTime = this.lastKnownGoodPosition + (this.streamStartOffset || 0);
                            this.initHlsStream(currentTime);
                        }, 1000);
                    } else {
                        this._showLoading('Playback failed - try reloading');
                    }
                };
                this._setupStreamErrorHandler = setupStreamErrorHandler;
                this._preloadSubtitleTracks().then(() => {
                    this.video.addEventListener('error', this._setupStreamErrorHandler, { once: true });
                    setupStream();
                });
            } else {
                this.streamStartOffset = 0;
                this.video.src = `/api/video/stream/${this.videoId}`;
                this.loadSubtitles();
                if (hasMultipleAudio) {
                    this.loadAudioTrackSelector();
                }
                this.video.play().catch(e => {
                    console.log('[SimplePlayer] Play requires user gesture:', e);
                });
            }

            this.loadStoryboard();
            this.setMusicSuspended(true);
            this.startProgressReporting();
            this.updateMarkers();
            this.checkMarkers();
            
            if (window.subtitleManager) {
                window.subtitleManager.bindVideo(this.video, this.container);
            }
            
            // Store debugging info for manual refresh operations
            this.debugInfo = {
                seriesTitle: this.container.dataset.seriesTitle || '',
                seasonNumber: parseInt(this.container.dataset.seasonNumber || '1'),
                episodeNumber: parseInt(this.container.dataset.episodeNumber || '0'),
                seriesImdbId: this.container.dataset.seriesImdbId || ''
            };
            
            // Auto-refresh markers for episodes if they are currently all zero
            const allZero = Object.values(this.markers).every(v => v === 0);
            if (allZero && (this.videoType === 'Episode' || this.videoType === 'episode')) {
                this.refreshMarkers();
            }
            
            this.showControls();
            this.updatePageTitle();
        }

        updatePageTitle() {
            const title = this.container.dataset.title || 'JMedia';
            const pageTitleEl = document.getElementById('pageTitle');
            if (pageTitleEl) {
                pageTitleEl.textContent = title;
                pageTitleEl.title = title;
            }
            document.title = title;
        }

        updateSubtitle() {
            const subtitleEl = this.container.querySelector('#videoSubtitle');
            if (!subtitleEl) return;
            if (this.videoType === 'episode' || this.videoType === 'Episode') {
                const series = this.container.dataset.seriesTitle || '';
                const season = this.container.dataset.seasonNumber || '';
                const episode = this.container.dataset.episodeNumber || '';
                subtitleEl.textContent = `${series} • S${season}E${episode}`;
            }
        }

async refreshMarkers(retries = 3) {
              console.log('[SimplePlayer] Refreshing markers for episode...');
              try {
                  const res = await fetch(`/api/video/${this.videoId}`);
                  if (res.ok) {
                      const json = await res.json();
                      const data = json.data || json;
                      
                      if (data.introStart !== undefined) {
                          // Update marker sources to SERVER-REFRESHED when we get new data from server
                          this.markerSources = {
                              introStart: 'SERVER-REFRESHED',
                              introEnd: 'SERVER-REFRESHED',
                              outroStart: 'SERVER-REFRESHED',
                              outroEnd: 'SERVER-REFRESHED',
                              recapStart: 'SERVER-REFRESHED',
                              recapEnd: 'SERVER-REFRESHED'
                          };
                          
                          this.markers = {
                              introStart: data.introStart || 0,
                              introEnd: data.introEnd || 0,
                              outroStart: data.outroStart || 0,
                              outroEnd: data.outroEnd || 0,
                              recapStart: data.recapStart || 0,
                              recapEnd: data.recapEnd || 0
                          };
                          console.log('[SimplePlayer] Markers refreshed:', this.markers);
                          this.updateMarkers();
                          this.checkMarkers();
                          
                          // If still all zero and we have retries, try again after a delay
                          // (Backend enrichment might be in progress)
                          const allZeroNow = Object.values(this.markers).every(v => v === 0);
                          if (allZeroNow && retries > 0) {
                              console.log(`[SimplePlayer] Markers still zero, retrying in 2s... (${retries} left)`);
                              setTimeout(() => this.refreshMarkers(retries - 1), 2000);
                          }
                      }
                  }
              } catch (e) {
                  console.error('[SimplePlayer] Failed to refresh markers', e);
              }
          }

         // Debugging methods for manual metadata refresh with persistence
         forceRefreshEpisode() {
             this._triggerDebugRefresh('episode', () => {
                 // Update client-side immediately for instant feedback
                 this.refreshMarkers(3);
                 // Trigger server-side persistence
                 return fetch(`/api/video/metadata/${this.videoId}/reload`, {method: 'POST'});
             });
         }

         forceRefreshSeason() {
             this._triggerDebugRefresh('season', () => 
                 fetch(`/api/video/metadata/series/${encodeURIComponent(this.debugInfo.seriesTitle)}/season/${this.debugInfo.seasonNumber}/reload`, {method: 'POST'})
             );
         }

         forceRefreshShow() {
             this._triggerDebugRefresh('show', () => 
                 fetch(`/api/video/metadata/series/${encodeURIComponent(this.debugInfo.seriesTitle)}/reload`, {method: 'POST'})
             );
         }

          _triggerDebugRefresh(type, apiCall) {
              console.log(`[SimplePlayer] Manual ${type} refresh:`, 
                  this.container.dataset.title,
                  `S${this.debugInfo.seasonNumber}E${this.debugInfo.episodeNumber}`,
                  `IMDb ID: ${this.debugInfo.seriesImdbId}`
              );
              
              // Reset refresh status
              this.refreshStatus = `Refreshing ${type}...`;
              this.refreshError = null;
              this._updateDebugDialog();
              
              if (window.Toast) window.Toast.info(`Refreshing ${type} metadata...`);
              
              apiCall()
                  .then(res => {
                      if (res.ok) {
                          console.log(`[SimplePlayer] ${type} refresh initiated`);
                          this.refreshStatus = `${type.charAt(0).toUpperCase() + type.slice(1)} refresh started`;
                          this.refreshError = null;
                          if (window.Toast) window.Toast.success(`${type.charAt(0).toUpperCase() + type.slice(1)} metadata refresh started`);
                          
                          // For episode refresh, also update markers after server call
                          if (type === 'episode') {
                              // Optional: re-fetch markers after brief delay to show updated values
                              setTimeout(() => this.refreshMarkers(3), 1500);
                          }
                      } else {
                          throw new Error(`HTTP ${res.status}`);
                      }
                  })
                  .catch(err => {
                      console.error(`[SimplePlayer] ${type} refresh failed:`, err);
                      this.refreshStatus = `Failed`;
                      this.refreshError = err.message || 'Unknown error';
                      if (window.Toast) window.Toast.error(`Failed to refresh ${type} metadata`);
                  })
                  .finally(() => {
                      // Update dialog after refresh attempt
                      this._updateDebugDialog();
                  });
          }

        initDirectStream(savedTime) {
            // Use direct remuxed stream via /api/video/stream
            // If needsTranscode and resuming, pass start param for server-side seek
            if (this.needsTranscode && savedTime > 0) {
                console.log('[SimplePlayer] Resuming from ' + savedTime + 's via server-side seek');
                this.video.src = `/api/video/stream/${this.videoId}?start=${savedTime}`;
            } else {
                this.video.src = `/api/video/stream/${this.videoId}`;
            }
            
            this._showLoading('Loading video...');
            
            this.video.addEventListener('loadedmetadata', () => {
                console.log('[SimplePlayer] Direct stream metadata loaded, duration:', this.video.duration);
                this.applyInitialState();
                setTimeout(() => this.loadSubtitles(), 500);
            }, { once: true });
            
            this.video.addEventListener('playing', () => {
                this._streamFallbackCount = 0;
                this._hideLoading();
            }, { once: true });
            
            this.video.addEventListener('error', (e) => {
                this._streamFallbackCount++;
                console.error('[SimplePlayer] Direct stream error (fallback ' + this._streamFallbackCount + '/' + this._maxStreamFallbacks + '):', this.video.error);
                if (this._streamFallbackCount < this._maxStreamFallbacks) {
                    this._showLoading('Stream error, trying HLS...');
                    setTimeout(() => {
                        const currentTime = this.lastKnownGoodPosition + (this.streamStartOffset || 0);
                        this.initHlsStream(currentTime);
                    }, 1000);
                } else {
                    this._showLoading('Playback failed after ' + this._maxStreamFallbacks + ' attempts');
                }
            });
            
            this.video.play().catch(e => {
                console.log('[SimplePlayer] Play requires user gesture:', e);
            });
        }

        initExternalStream() {
            const url = this.externalUrl;
            const savedTime = parseFloat(this.container.dataset.startTime || 0);
            const isHls = this.externalIsHls;
            const isIOS = this._isIOS();

            console.log('[SimplePlayer] External stream:', url, 'HLS:', isHls);

            if (isHls) {
                const useHlsJs = !isIOS && typeof Hls !== 'undefined' && Hls.isSupported();
                if (useHlsJs) {
                    const hls = new Hls({
                        enableWorker: true,
                        lowLatencyMode: false
                    });
                    this.hlsInstance = hls;
                    hls.loadSource(url);
                    hls.attachMedia(this.video);
                    hls.on(Hls.Events.MANIFEST_PARSED, () => {
                        if (savedTime > 0) this.video.currentTime = savedTime;
                        this.video.play().catch(() => {});
                    });
                } else {
                    this.video.src = url;
                }
            } else {
                this.video.src = url;
            }

            this.video.addEventListener('loadedmetadata', () => {
                if (savedTime > 0) this.video.currentTime = savedTime;
                this.applyInitialState();
            }, { once: true });

            this.video.addEventListener('playing', () => this._hideLoading(), { once: true });
            this.video.addEventListener('error', (e) => {
                console.error('[SimplePlayer] External stream error:', this.video.error);
                this._showLoading('Playback error');
            });

            this.video.play().catch(e => {
                console.log('[SimplePlayer] Play requires user gesture:', e);
            });

            this.setMusicSuspended(true);
            this.startProgressReporting();
        }

        fallbackToDirectStream(savedTime) {
            if (this._fallbackInProgress || this._destroyed) return;
            this._fallbackInProgress = true;
            this._isNativeHls = false;
            console.log('[SimplePlayer] Falling back to direct stream, removing HLS error handler');

            this.clearStreamErrorHandlers();

            const absTime = this.lastKnownGoodPosition + (this.streamStartOffset || 0);
            this.streamStartOffset = absTime;

            const qualityParam = this._preferredQuality > 0 ? `&quality=${this._preferredQuality}` : '';
            const startParam = absTime > 0 ? `?start=${absTime}` : '';
            const setupFallback = () => {
                this.video.src = `/api/video/stream/${this.videoId}${startParam}${qualityParam}`;
                this.video.load();
                this.video.addEventListener('loadedmetadata', () => {
                    this._fallbackInProgress = false;
                    this.applyInitialState();
                    setTimeout(() => this.loadSubtitles(), 500);
                }, { once: true });
                this.video.addEventListener('playing', () => {
                    this._fallbackInProgress = false;
                    this._hideLoading();
                }, { once: true });
                this.video.addEventListener('error', () => {
                    this._fallbackInProgress = false;
                }, { once: true });
                this.video.play().catch(() => {});
            };

            // iOS/Safari: pre-create <track> elements before changing video.src
            if (this._isIOS()) {
                this._preloadSubtitleTracks().then(setupFallback);
            } else {
                setupFallback();
            }
        }

        async loadAudioTrackSelector() {
            const selector = document.getElementById('audioTrackSelector');
            if (!selector) return;

            try {
                const resp = await fetch(`/api/video/${this.videoId}/audio-tracks`);
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
            console.log('[SimplePlayer] Switching audio track to:', trackIndex);

            // Remove error handlers that capture stale savedTime before replacing video.src
            if (this._seekErrorHandler) {
                this.video.removeEventListener('error', this._seekErrorHandler);
                this._seekErrorHandler = null;
            }
            if (this._setupStreamErrorHandler) {
                this.video.removeEventListener('error', this._setupStreamErrorHandler);
                this._setupStreamErrorHandler = null;
            }

            // Store current track selection for persistence across seeks
            this.currentAudioTrackIndex = trackIndex;

            // Calculate current display time
            const currentTime = this.video.currentTime + (this.streamStartOffset || 0);

            // Restart stream with new audio track
            // Note: trackIndex 0 is a valid FFprobe stream index - only append if >= 0
            // Use -1 to indicate "default" to the backend (matches @DefaultValue("-1"))
            const audioParam = (trackIndex !== null && trackIndex >= 0) ? `&audioTrack=${trackIndex}` : '';
            this.video.src = `/api/video/stream/${this.videoId}?start=${currentTime}${audioParam}`;
            this.video.load();
            this._seekErrorHandler = (e) => {
                console.error('[SimplePlayer] Audio-switched stream error, falling back to HLS');
                if (this._destroyed) return;
                this._seekErrorHandler = null;
                this.initHlsStream(currentTime);
            };
            this.video.addEventListener('error', this._seekErrorHandler, { once: true });
            this.video.play().catch(() => {});

            // Reset playback state flags so recovery handlers compute correct positions
            this._hasPlayedData = false;
            this.lastKnownGoodPosition = 0;
            this._streamFallbackCount = 0;
            if (this._stallTimer) {
                clearTimeout(this._stallTimer);
                this._stallTimer = null;
            }

            // Update stream offset for time display
            this.streamStartOffset = currentTime;
        }

buildUI() {
             const uiHTML = `
                 <div class="video-click-overlay"></div>
                 <div class="big-play-btn"><img src="/logo.png" alt="Play"></div>
                 <div class="buffering-overlay"><i class="pi pi-spin pi-spinner" style="font-size: 3rem; color: #48c774;"></i></div>

                 
                 <div class="skip-recap-container" id="skipRecapBtn" style="display: none;">
                     <button class="button is-info is-rounded"><i class="pi pi-history mr-2"></i> Skip Recap</button>
                     <label class="skip-auto-toggle ${this.autoSkipRecap ? 'active' : ''}" data-section="recap">
                         <input type="checkbox" ${this.autoSkipRecap ? 'checked' : ''}> Auto
                     </label>
                 </div>
                 <div class="skip-intro-container" id="skipIntroBtn" style="display: none;">
                     <button class="button is-info is-rounded"><i class="pi pi-fast-forward mr-2"></i> Skip Intro</button>
                     <label class="skip-auto-toggle ${this.autoSkipIntro ? 'active' : ''}" data-section="intro">
                         <input type="checkbox" ${this.autoSkipIntro ? 'checked' : ''}> Auto
                     </label>
                 </div>
                 <div class="skip-outro-container" id="skipOutroBtn" style="display: none;">
                     <button class="button is-info is-rounded"><i class="pi pi-step-forward mr-2"></i> Skip Outro</button>
                     <label class="skip-auto-toggle ${this.autoSkipOutro ? 'active' : ''}" data-section="outro">
                         <input type="checkbox" ${this.autoSkipOutro ? 'checked' : ''}> Auto
                     </label>
                  </div>

                 <div class="auto-skip-notice" id="autoSkipNotice" style="display: none;">
                     <span id="autoSkipNoticeText">Intro skipped</span>
                     <button class="button is-small is-light" id="autoSkipUndoBtn"><i class="pi pi-undo mr-1"></i>Undo</button>
                     <button class="button is-small is-light" id="autoSkipToggleBtn"><i class="pi pi-times mr-1"></i>Auto</button>
                 </div>

                <div class="media-info">
                    <div class="back-button-container"><button class="back-btn" id="videoBackBtn"><i class="pi pi-arrow-left"></i></button></div>
                    <div class="info-text">
                        <div class="info-title" id="videoTitleLink">${this.container.dataset.title || 'Video'}</div>
                        <div class="info-subtitle" id="videoSubtitle"></div>
                    </div>
                </div>

                <div class="controls-container">
                    <div class="preview-container" id="scrollPreview">
                        <div class="storyboard-img" id="storyboardImg"></div>
                        <div class="preview-time" id="previewTime">0:00</div>
                    </div>
                    <div class="progress-container">
                        <div class="progress-filled" style="width: 0%;"></div>
                        <div class="progress-intro-marker" style="display: none;"></div>
                        <div class="progress-outro-marker" style="display: none;"></div>
                    </div>
                    <div class="controls-row">
                        <button class="control-btn" id="videoPrevBtn"><i class="pi pi-step-backward"></i></button>
                        <button class="control-btn" id="videoPlayPauseBtn"><i class="pi pi-play"></i></button>
                        <button class="control-btn" id="videoNextBtn"><i class="pi pi-step-forward"></i></button>
                        
                        <div class="skip-buttons-row">
                            <button class="control-btn skip-btn" data-skip="-30"><i class="pi pi-angle-double-left"></i><span class="skip-val">30</span></button>
                            <button class="control-btn skip-btn" data-skip="-15"><i class="pi pi-angle-left"></i><span class="skip-val">15</span></button>
                            <button class="control-btn skip-btn" data-skip="15"><i class="pi pi-angle-right"></i><span class="skip-val">15</span></button>
                            <button class="control-btn skip-btn" data-skip="30"><i class="pi pi-angle-double-right"></i><span class="skip-val">30</span></button>
                        </div>

                        <div class="time-display"><span id="videoCurrentTime">0:00</span> / <span id="videoTotalTime">0:00</span></div>
                        <div class="spacer"></div>
                        
                        <div id="audioSelectorPlaceholder"></div>

                        <div class="volume-container">
                            <button class="control-btn" id="videoMuteBtn"><i class="pi pi-volume-up"></i></button>
                            <input type="range" class="volume-slider" min="0" max="1" step="0.01" value="${this.state.volume}">
                        </div>
                        
                        <button class="control-btn" id="videoSpeedBtn"><span id="speedValue">1.0x</span></button>
                        <button class="control-btn" id="videoSubtitleBtn"><i class="pi pi-cog"></i></button>
                        <button class="control-btn" id="videoFullscreenBtn"><i class="pi pi-expand"></i></button>
                    </div>
                </div>

                <div class="subtitle-menu settings-menu" id="subtitleMenu">
                    <!-- MAIN PAGE -->
                    <div class="settings-page active" data-page="main">
                        <div class="menu-header">Settings</div>
                        <div class="settings-item" data-page="subtitles">
                            <span>Subtitles</span>
                            <i class="pi pi-chevron-right"></i>
                        </div>
                        <div class="settings-item" data-page="offset">
                            <span>Timing Offset</span>
                            <i class="pi pi-chevron-right"></i>
                        </div>
                        <div class="settings-item" data-page="style">
                            <span>Style</span>
                            <i class="pi pi-chevron-right"></i>
                        </div>
                        <div class="settings-item" data-page="quality">
                            <span>Video Quality</span>
                            <i class="pi pi-chevron-right"></i>
                        </div>
                    </div>

                    <!-- SUBTITLES PAGE -->
                    <div class="settings-page" data-page="subtitles">
                        <div class="settings-back"><i class="pi pi-chevron-left"></i> Subtitles</div>
                        <div class="subtitle-list" id="subtitleList">
                            <div class="subtitle-option" id="sub-off" data-id="off">Off</div>
                        </div>
                    </div>

                    <!-- TIMING OFFSET PAGE -->
                    <div class="settings-page" data-page="offset">
                        <div class="settings-back"><i class="pi pi-chevron-left"></i> Timing Offset</div>
                        <div class="subtitle-correction-row">
                            <button class="correction-btn" id="subMinusBtn">-0.2s</button>
                            <div class="correction-val" id="subCorrectionVal" title="Click to edit manually">0.0s</div>
                            <button class="correction-btn" id="subPlusBtn">+0.2s</button>
                            <button class="correction-btn correction-reset" id="subResetBtn" title="Reset to 0">↺</button>
                        </div>
                    </div>

                    <!-- STYLE PAGE -->
                    <div class="settings-page" data-page="style">
                        <div class="settings-back"><i class="pi pi-chevron-left"></i> Style</div>
                        <div class="subtitle-option" id="manageSubtitlesBtn"><i class="pi pi-palette"></i> Subtitle Settings</div>
                    </div>

                    <!-- QUALITY PAGE -->
                    <div class="settings-page" data-page="quality">
                        <div class="settings-back"><i class="pi pi-chevron-left"></i> Video Quality</div>
                        <div class="quality-options" id="qualityOptions">
                            <button class="quality-btn" data-quality="0">Source</button>
                            <button class="quality-btn" data-quality="480">480p</button>
                            <button class="quality-btn" data-quality="720">720p</button>
                            <button class="quality-btn" data-quality="1080">1080p</button>
                            <button class="quality-btn" data-quality="2160">4K</button>
                        </div>
                    </div>
                </div>
                
                <div class="speed-menu" id="speedMenu">
                    <div class="menu-header">Speed</div>
                    <div class="speed-list">
                        <div class="speed-option active" data-speed="1.0">1.0x</div>
                        <div class="speed-option" data-speed="1.25">1.25x</div>
                        <div class="speed-option" data-speed="1.5">1.5x</div>
                        <div class="speed-option" data-speed="2.0">2.0x</div>
                    </div>
                 </div>
                 
                  // Debug Dialog (hidden by default)
                  <div class="debug-dialog" id="debugDialog" style="display: none;">
                      <div class="debug-dialog-content">
                          <div class="debug-dialog-header">
                              <h3>Debug Controls</h3>
                              <button class="debug-dialog-close" id="debugDialogClose">&times;</button>
                          </div>
                          <div class="debug-dialog-body">
                           <div class="debug-info">
                                   <div><strong>Show:</strong> <input type="text" id="dialog-series-input" class="debug-input" /></div>
                                   <div><strong>Season:</strong> <input type="number" id="dialog-season-input" class="debug-input debug-input-sm" /></div>
                                   <div><strong>Episode:</strong> <input type="number" id="dialog-episode-input" class="debug-input debug-input-sm" /></div>
                                   <div><strong>IMDb ID:</strong> <input type="text" id="dialog-imdb-input" class="debug-input" /></div>
                                   <div><button class="debug-save-btn" id="debugSaveBtn">Save Overrides &amp; Reload</button></div>
                               </div>
                              <div class="debug-marker-info">
                                  <h4>Marker Values:</h4>
                                  <div><strong>Intro Start:</strong> <span id="dialog-intro-start">0 (UNKNOWN)</span></div>
                                  <div><strong>Intro End:</strong> <span id="dialog-intro-end">0 (UNKNOWN)</span></div>
                                  <div><strong>Outro Start:</strong> <span id="dialog-outro-start">0 (UNKNOWN)</span></div>
                                  <div><strong>Outro End:</strong> <span id="dialog-outro-end">0 (UNKNOWN)</span></div>
                                  <div><strong>Recap Start:</strong> <span id="dialog-recap-start">0 (UNKNOWN)</span></div>
                                  <div><strong>Recap End:</strong> <span id="dialog-recap-end">0 (UNKNOWN)</span></div>
                                  <div class="debug-marker-original">
                                      <h4>Original Values (at load):</h4>
                                      <div><strong>Intro Start:</strong> <span id="dialog-original-intro-start">0</span></div>
                                      <div><strong>Intro End:</strong> <span id="dialog-original-intro-end">0</span></div>
                                      <div><strong>Outro Start:</strong> <span id="dialog-original-outro-start">0</span></div>
                                      <div><strong>Outro End:</strong> <span id="dialog-original-outro-end">0</span></div>
                                      <div><strong>Recap Start:</strong> <span id="dialog-original-recap-start">0</span></div>
                                      <div><strong>Recap End:</strong> <span id="dialog-original-recap-end">0</span></div>
                                  </div>
                              </div>
                           <div class="debug-refresh-status">
                                   <h4>Refresh Status:</h4>
                                   <div><strong>Status:</strong> <span id="dialog-refresh-status">No refresh attempted</span></div>
                                   <div><strong>Error:</strong> <span id="dialog-refresh-error">None</span></div>
                               </div>
                           </div>
                          </div>
                      </div>
                  </div>
              `;
             this.container.insertAdjacentHTML('beforeend', uiHTML);
            
              // Elements
              this.playBtn = this.container.querySelector('#videoPlayPauseBtn');
              this.playIcon = this.playBtn.querySelector('i');
              this.bigPlay = this.container.querySelector('.big-play-btn');
              this.progressBar = this.container.querySelector('.progress-filled');
              this.progressContainer = this.container.querySelector('.progress-container');
              this.timeCurrent = this.container.querySelector('#videoCurrentTime');
              this.timeTotal = this.container.querySelector('#videoTotalTime');
              this.volSlider = this.container.querySelector('.volume-slider');
              this.muteBtn = this.container.querySelector('#videoMuteBtn');
              this.fullscreenBtn = this.container.querySelector('#videoFullscreenBtn');
              this.speedBtn = this.container.querySelector('#videoSpeedBtn');
              this.speedValue = this.container.querySelector('#speedValue');
              this.subtitleBtn = this.container.querySelector('#videoSubtitleBtn');
              this.subtitleMenu = this.container.querySelector('#subtitleMenu');
              this.speedMenu = this.container.querySelector('#speedMenu');
              this.debugDialog = this.container.querySelector('#debugDialog');
              this.debugDialogClose = this.container.querySelector('#debugDialogClose');
              this.dialogSeriesInput = this.container.querySelector('#dialog-series-input');
              this.dialogSeasonInput = this.container.querySelector('#dialog-season-input');
              this.dialogEpisodeInput = this.container.querySelector('#dialog-episode-input');
              this.dialogImdbInput = this.container.querySelector('#dialog-imdb-input');
              this.debugSaveBtn = this.container.querySelector('#debugSaveBtn');
              this.dialogIntroStart = this.container.querySelector('#dialog-intro-start');
              this.dialogIntroEnd = this.container.querySelector('#dialog-intro-end');
              this.dialogOutroStart = this.container.querySelector('#dialog-outro-start');
              this.dialogOutroEnd = this.container.querySelector('#dialog-outro-end');
              this.dialogRecapStart = this.container.querySelector('#dialog-recap-start');
              this.dialogRecapEnd = this.container.querySelector('#dialog-recap-end');
              this.dialogOriginalIntroStart = this.container.querySelector('#dialog-original-intro-start');
              this.dialogOriginalIntroEnd = this.container.querySelector('#dialog-original-intro-end');
              this.dialogOriginalOutroStart = this.container.querySelector('#dialog-original-outro-start');
              this.dialogOriginalOutroEnd = this.container.querySelector('#dialog-original-outro-end');
              this.dialogOriginalRecapStart = this.container.querySelector('#dialog-original-recap-start');
              this.dialogOriginalRecapEnd = this.container.querySelector('#dialog-original-recap-end');
              this.dialogRefreshStatus = this.container.querySelector('#dialog-refresh-status');
              this.dialogRefreshError = this.container.querySelector('#dialog-refresh-error');
             this.buffering = this.container.querySelector('.buffering-overlay');
             this.backBtn = this.container.querySelector('#videoBackBtn');
             this.clickOverlay = this.container.querySelector('.video-click-overlay');
             this.prevBtn = this.container.querySelector('#videoPrevBtn');
             this.nextBtn = this.container.querySelector('#videoNextBtn');
             this.preview = this.container.querySelector('#scrollPreview');
             this.previewImg = this.container.querySelector('#storyboardImg');
             this.previewTime = this.container.querySelector('#previewTime');
             this.subtitleList = this.container.querySelector('#subtitleList');
            
            // Load last selected subtitle track (video-specific first, then global)
            const videoTrack = localStorage.getItem('jmedia_last_track_' + this.videoId);
            const globalTrack = sessionStorage.getItem('jmedia_global_subtitle_track');
            this.lastSelectedTrackId = videoTrack || globalTrack;
            
            // Off button
            const offBtn = this.container.querySelector('#sub-off');
            if (offBtn) {
                offBtn.onclick = (e) => { e.stopPropagation(); this.selectSubtitle('off', offBtn); };
            }

             // Move audio track selector into its placeholder if it exists (it's a separate include)
             const audioSelector = document.getElementById('audioTrackSelector');
             const audioPlaceholder = this.container.querySelector('#audioSelectorPlaceholder');
             if (audioSelector && audioPlaceholder) {
                 audioPlaceholder.appendChild(audioSelector);
                 console.log('[SimplePlayer] Moved audioTrackSelector into player UI');
             }
             
              if (this.debugDialogClose) {
                 this.debugDialogClose.onclick = (e) => {
                     e.stopPropagation();
                     this.closeDebugDialog();
                 };
             }
             
               // Close dialog when clicking outside
              this.debugDialog.onclick = (e) => {
                  if (e.target === this.debugDialog) {
                      this.closeDebugDialog();
                  }
              };
              
              // Save overrides button
              if (this.debugSaveBtn) {
                  this.debugSaveBtn.onclick = (e) => {
                      e.stopPropagation();
                      this.saveAndReload();
                  };
              }
          }

        selectSubtitle(trackId, element) {
            console.log('[SimplePlayer] Selecting subtitle track:', trackId);
            
            // UI Update
            this.container.querySelectorAll('.subtitle-option').forEach(el => el.classList.remove('active'));
            if (element) element.classList.add('active');
            this.subtitleMenu.classList.remove('active');
            
            // Logic
            if (trackId === 'off') {
                this.turnOffSubtitles();
            } else {
                // For other tracks, the click handler in loadSubtitles handles the actual loading
                // but we need this for the initial load and when manually triggered
            }
            
            localStorage.setItem('jmedia_last_track_' + this.videoId, trackId);
            this.lastSelectedTrackId = trackId;
        }

        attachEvents() {
            const toggle = (e) => {
                if (e) e.stopPropagation();
                if (this.video.paused) this.video.play().catch(() => {});
                else this.video.pause();
                this.showControls();
            };

            this.video.addEventListener('play', () => {
                this.playIcon.className = 'pi pi-pause';
                this.bigPlay.style.display = 'none';
                this.container.classList.remove('paused');
                this.showControls();
                // Stall detection: if no timeupdate with data arrives within 90s, attempt recovery
                if (this._stallTimer) clearTimeout(this._stallTimer);
                this._stallTimer = setTimeout(() => {
                    if (this._destroyed) return;
                    if (this.video.currentTime === 0 && !this.video.paused && !this._hasPlayedData) {
                        console.warn('[SimplePlayer] Playback stalled - no data received in 90s');
                        if (window.Toast) window.Toast.warning('Playback stuck - retrying...');

                        // Remove error handlers that capture stale savedTime before recovery
                        this.clearStreamErrorHandlers();

                        if (this._qualitySwitching) return;

                        if (this.hlsSessionId || this.hlsInstance) {
                            this.fallbackToDirectStream();
                        } else {
                            this._streamFallbackCount = (this._streamFallbackCount || 0) + 1;
                            if (this._streamFallbackCount < this._maxStreamFallbacks) {
                                const saved = this.streamStartOffset || this.lastKnownGoodPosition || this.initialResumeTime || 0;
                                const qualityParam = this._preferredQuality > 0 ? `&quality=${this._preferredQuality}` : '';
                                this.video.src = `/api/video/stream/${this.videoId}?start=${saved}${qualityParam}`;
                                this.video.load();
                                this.video.play().catch(() => {});
                            } else {
                                if (window.Toast) window.Toast.error('Playback failed after retries - please reload');
                            }
                        }
                    }
                }, 120000);
                // Show informational toast at 60s
                setTimeout(() => {
                    if (this._stallTimer && this.video.currentTime === 0 && !this._hasPlayedData) {
                        if (window.Toast) window.Toast.info('Server is preparing your video, please wait...');
                    }
                }, 60000);
            });

            this.video.addEventListener('pause', () => {
                this.playIcon.className = 'pi pi-play';
                this.bigPlay.style.display = 'flex';
                this.container.classList.add('paused');
                this.showControls();
            });

            this.video.addEventListener('timeupdate', () => {
                // For fragmented/metadata-incomplete streams, video.duration may be wrong.
                // Always use totalDuration from DB for timeline accuracy.
                let dur = this.totalDuration;
                if (!dur || dur === Infinity) {
                    dur = this.video.duration;
                }
                if (!dur || dur === Infinity) return;
                
                // When stream started at an offset (server-side seek), add offset to current time
                // Cap displayTime at total duration to prevent impossible timestamps.
                const displayTime = Math.min(this.video.currentTime + (this.streamStartOffset || 0), dur);
                const pct = (displayTime / dur) * 100;
                this.progressBar.style.width = Math.min(100, pct) + '%';
                this.timeCurrent.innerText = this.formatTime(displayTime);

                // Auto-skip checks
                this._checkAutoSkip(displayTime);

                this.checkMarkers();

                // Clear stall detection on first data receipt
                if (this.video.currentTime > 0) {
                    this.lastKnownGoodPosition = this.video.currentTime;
                    this._hasPlayedData = true;
                    if (this._stallTimer) {
                        clearTimeout(this._stallTimer);
                        this._stallTimer = null;
                    }
                }
            });

            this.video.addEventListener('loadedmetadata', () => {
                const dur = this.video.duration;
                console.log(`[SimplePlayer] Metadata loaded. Stream duration: ${dur}s, DB duration: ${this.totalDuration}s`);
                
                // CRITICAL: Only trust stream duration if it's larger than what we have
                // HLS often reports only the buffered duration initially.
                // Never overwrite when playing from a seek offset (the stream is partial).
                if (dur && dur !== Infinity && dur > this.totalDuration && !this.streamStartOffset) {
                    this.totalDuration = dur;
                }
                
                this.applyInitialState();
                this.updateMarkers();

                // Perform client-side seek for direct streams (where streamStartOffset is 0)
                if (this.initialResumeTime > 0 && (!this.streamStartOffset || this.streamStartOffset === 0)) {
                    console.log(`[SimplePlayer] Performing client-side seek to ${this.initialResumeTime}s`);
                    this.video.currentTime = this.initialResumeTime;
                    // Reset initial resume time to prevent repeated seeks on metadata reload
                    this.initialResumeTime = 0;
                }
            });

            // Mid-playback stall detection: if waiting > 60s after having played data, trigger recovery
            this._waitingStart = 0;
            this._waitingTimer = null;
            this.video.onwaiting = () => {
                this.buffering.style.display = 'block';
                if (this._waitingStart === 0) this._waitingStart = Date.now();
                if (this._waitingTimer) return;
                this._waitingTimer = setTimeout(() => {
                    this._waitingTimer = null;
                    this._waitingStart = 0;
                    if (this._destroyed) return;
                    // Only trigger recovery if browser has given up on loading, not during normal buffering
                    if (this._hasPlayedData && this.lastKnownGoodPosition > 0 && this.video.networkState !== HTMLMediaElement.NETWORK_LOADING) {
                        console.warn('[SimplePlayer] Mid-playback stall detected (>60s), retrying at position', this.lastKnownGoodPosition);
                        this.clearStreamErrorHandlers();
                        this._streamFallbackCount = (this._streamFallbackCount || 0) + 1;
                        if (this._streamFallbackCount < this._maxStreamFallbacks) {
                            if (window.Toast) window.Toast.warning('Playback stalled - reconnecting... (' + this._streamFallbackCount + '/' + this._maxStreamFallbacks + ')');
                            const pos = this.lastKnownGoodPosition + (this.streamStartOffset || 0);
                            if (this.hlsSessionId || this.hlsInstance) {
                                this.fallbackToDirectStream(pos);
                            } else {
                                const qualityParam = this._preferredQuality > 0 ? `&quality=${this._preferredQuality}` : '';
                                this.video.src = `/api/video/stream/${this.videoId}?start=${pos}${qualityParam}`;
                                this.video.load();
                                this.video.play().catch(() => {});
                            }
                        } else {
                            if (window.Toast) window.Toast.error('Playback failed - please reload');
                        }
                    }
                }, 60000);
            };
            this.video.onplaying = () => {
                this.buffering.style.display = 'none';
                this._waitingStart = 0;
                if (this._waitingTimer) {
                    clearTimeout(this._waitingTimer);
                    this._waitingTimer = null;
                }
            };
            this.video.onended = () => {
                console.log('[SimplePlayer] Video ended, playing next episode...');
                this.playNextEpisode();
            };

            // Interaction
            this.clickOverlay.onclick = toggle;
            this.bigPlay.onclick = toggle;
            this.playBtn.onclick = toggle;
            this.backBtn.onclick = (e) => { e.stopPropagation(); this.goBack(); };
            this.prevBtn.onclick = (e) => { e.stopPropagation(); this.playPreviousEpisode(); };
            this.nextBtn.onclick = (e) => { e.stopPropagation(); this.playNextEpisode(); };
            this.container.querySelector('#videoTitleLink').onclick = (e) => { e.stopPropagation(); this.goToDetails(); };

            this.container.querySelectorAll('.skip-btn').forEach(btn => {
                btn.onclick = (e) => {
                    e.stopPropagation();
                    const skipAmount = parseFloat(btn.dataset.skip);
                    
                    // Calculate current display time (accounting for stream start offset)
                    const currentDisplayTime = this.video.currentTime + (this.streamStartOffset || 0);
                    const newTime = Math.max(0, currentDisplayTime + skipAmount);

                    // For transcoded streams, use server-side seek
                    if (this.needsTranscode && !this.hlsSessionId) {
                        this.performServerSeek(newTime);
                    } else {
                        this.video.currentTime = newTime - (this.streamStartOffset || 0);
                    }
                    
                    this.showControls();
                };
            });

            this.volSlider.oninput = (e) => {
                const val = parseFloat(e.target.value);
                this.state.volume = val;
                this.video.volume = Math.pow(val, 2);
                localStorage.setItem(this.volumeKey, val);
                this.updateVolumeUI();
            };

            this.muteBtn.onclick = (e) => {
                e.stopPropagation();
                this.video.muted = !this.video.muted;
                localStorage.setItem(this.muteKey, this.video.muted);
                this.updateVolumeUI();
            };

            this.progressContainer.onclick = (e) => {
                e.stopPropagation();
                const rect = this.progressContainer.getBoundingClientRect();
                
                // Use DB duration for seeking (stream may report wrong duration)
                let dur = this.totalDuration;
                if (!dur || dur === Infinity) {
                    dur = this.video.duration;
                }
                
                const seekTo = ((e.clientX - rect.left) / rect.width) * dur;
                
                // For transcoded streams, use server-side seek to allow seeking forward past loaded content
                if (this.needsTranscode && !this.hlsSessionId) {
                    this.performServerSeek(seekTo);
                } else {
                    this.video.currentTime = seekTo - (this.streamStartOffset || 0);
                }
                this.video.dispatchEvent(new Event('timeupdate'));
                this.showControls();
            };

            this.progressContainer.onmousemove = (e) => this.handleMouseMove(e);
            this.progressContainer.onmouseleave = () => this.preview.classList.remove('active');

             this.speedBtn.onclick = (e) => {
                 e.stopPropagation();
                 this.speedMenu.classList.toggle('active');
                 this.subtitleMenu.classList.remove('active');
             };
             
             this.container.querySelectorAll('.speed-option').forEach(opt => {
                opt.onclick = (e) => {
                    e.stopPropagation();
                    const speed = parseFloat(opt.dataset.speed);
                    this.video.playbackRate = speed;
                    this.speedValue.innerText = speed.toFixed(1) + 'x';
                    this.container.querySelectorAll('.speed-option').forEach(o => o.classList.remove('active'));
                    opt.classList.add('active');
                    this.speedMenu.classList.remove('active');
                };
            });

            this.subtitleBtn.onclick = (e) => {
                e.stopPropagation();
                this.subtitleMenu.classList.toggle('active');
                this.speedMenu.classList.remove('active');
                if (this.subtitleMenu.classList.contains('active')) {
                    this.switchSettingsPage('main');
                }
            };

            // Settings page navigation
            this.subtitleMenu.addEventListener('click', (e) => {
                const item = e.target.closest('.settings-item');
                if (item) {
                    e.stopPropagation();
                    this.switchSettingsPage(item.dataset.page);
                    if (item.dataset.page === 'quality') {
                        this.container.querySelectorAll('.quality-btn').forEach(b => {
                            b.classList.toggle('active', parseInt(b.dataset.quality) === this._preferredQuality);
                        });
                    }
                    return;
                }
                const back = e.target.closest('.settings-back');
                if (back) {
                    e.stopPropagation();
                    this.switchSettingsPage('main');
                    return;
                }
                const manageBtn = e.target.closest('#manageSubtitlesBtn');
                if (manageBtn) {
                    e.stopPropagation();
                    this.subtitleMenu.classList.remove('active');
                    if (window.subtitleManager) {
                        window.subtitleManager.openModal(this.videoId, this.container.dataset.title, this.container.dataset.path);
                    }
                    return;
                }
                const qualityBtn = e.target.closest('.quality-btn');
                if (qualityBtn) {
                    e.stopPropagation();
                    if (this._qualitySwitching) return;
                    this._qualitySwitching = true;

                    (async () => {
                        try {
                        const quality = parseInt(qualityBtn.dataset.quality);
                        const label = qualityBtn.textContent.trim();
                        this._previousQuality = this._preferredQuality;
                        this._preferredQuality = quality;
                        this.container.querySelectorAll('.quality-btn').forEach(b => b.classList.remove('active'));
                        qualityBtn.classList.add('active');
                        if (window.Toast) window.Toast.info('Quality: ' + label);
                        
                        if (this.hlsSessionId) {
                            const absTime = this.video.currentTime + (this.streamStartOffset || 0);
                            this.streamStartOffset = absTime; // new HLS session starts at this offset
                            await this.cleanupHlsAsync();
                            this._showLoading('Switching to ' + label + '…');
                            await this.initHlsStream(absTime);
                            this._qualitySwitching = false;
                            // Reload subtitles after quality switch
                            if (this.lastSelectedTrackId && this.lastSelectedTrackId !== 'off') {
                                this.loadSubtitles(true);
                            }
                        } else {
                            const absTime = this.video.currentTime + (this.streamStartOffset || 0);
                            this.performServerSeek(absTime, true);
                        }
                    } catch (err) {
                        console.error('[SimplePlayer] Quality switch failed:', err);
                        if (window.Toast) window.Toast.error('Quality switch failed, reverting...');
                        // Auto-fallback: restore previous quality
                        this._preferredQuality = this._previousQuality;
                        this.container.querySelectorAll('.quality-btn').forEach(b => b.classList.remove('active'));
                        const fallbackBtn = this.container.querySelector(`.quality-btn[data-quality="${this._preferredQuality}"]`);
                        if (fallbackBtn) fallbackBtn.classList.add('active');
                    }
                })();
                return;
            }
            });

        this.container.querySelector('#subMinusBtn').onclick = (e) => {
            e.stopPropagation();
            if (window.subtitleManager) window.subtitleManager.adjustCorrection(-0.2);
        };

        this.container.querySelector('#subPlusBtn').onclick = (e) => {
            e.stopPropagation();
            if (window.subtitleManager) window.subtitleManager.adjustCorrection(0.2);
        };

        // Manual offset input handling
        const correctionVal = this.container.querySelector('#subCorrectionVal');
        if (correctionVal) {
            correctionVal.onclick = (e) => {
                e.stopPropagation();
                const currentVal = parseFloat(localStorage.getItem('jmedia_subtitle_correction') || '0');
                const input = document.createElement('input');
                input.type = 'number';
                input.step = '0.1';
                input.value = currentVal.toFixed(1);
                input.className = 'correction-input';
                input.style.cssText = 'width: 70px; text-align: center; background: #333; color: white; border: 1px solid #48c774; border-radius: 4px; padding: 4px; font-family: monospace; font-size: 0.9rem;';
                
                const saveValue = () => {
                    let val = parseFloat(input.value) || 0;
                    val = Math.round(val * 10) / 10;
                    localStorage.setItem('jmedia_subtitle_correction', val);
                    correctionVal.textContent = (val >= 0 ? '+' : '') + val.toFixed(1) + 's';
                    if (window.currentPlayerInstance) {
                        window.currentPlayerInstance.loadSubtitles(true); // true = keep menu open
                    }
                };
                
                input.onblur = () => {
                    saveValue();
                    if (correctionVal.parentNode) {
                        correctionVal.parentNode.replaceChild(correctionVal, input);
                    }
                };
                
                input.onkeydown = (ke) => {
                    if (ke.key === 'Enter') {
                        ke.preventDefault();
                        input.blur();
                    } else if (ke.key === 'Escape') {
                        ke.preventDefault();
                        input.value = currentVal.toFixed(1);
                        input.blur();
                    }
                };
                
                correctionVal.parentNode.replaceChild(input, correctionVal);
                input.focus();
                input.select();
            };
        }

        // Reset offset button
        this.container.querySelector('#subResetBtn').onclick = (e) => {
            e.stopPropagation();
            localStorage.setItem('jmedia_subtitle_correction', 0);
            const valEl = document.getElementById('subCorrectionVal');
            if (valEl) valEl.textContent = '0.0s';
            if (window.currentPlayerInstance) {
                window.currentPlayerInstance.loadSubtitles(true); // true = keep menu open
            }
        };

            // CSS-based fullscreen toggle method
            this.toggleCssFullscreen = () => {
                const isCssFullscreen = this.container.classList.contains('is-css-fullscreen');
                
                if (isCssFullscreen) {
                    // Exit CSS fullscreen
                    this.container.classList.remove('is-css-fullscreen');
                    document.body.style.overflow = '';
                    this.isCssFullscreen = false;
                    
                    // Unlock orientation if it was locked
                    if (screen.orientation && screen.orientation.unlock) {
                        screen.orientation.unlock().catch(() => {});
                    }
                    
                    console.log('[SimplePlayer] CSS fullscreen exited');
                } else {
                    // Enter CSS fullscreen
                    this.container.classList.add('is-css-fullscreen');
                    document.body.style.overflow = 'hidden';
                    this.isCssFullscreen = true;
                    
                    // Scroll to top to ensure player fills viewport
                    window.scrollTo(0, 0);
                    
                    // Try to lock to landscape on mobile
                    if (screen.orientation && screen.orientation.lock) {
                        screen.orientation.lock('landscape').catch(() => {});
                    }
                    
                    console.log('[SimplePlayer] CSS fullscreen entered');
                }
                
                // Update UI state
                this.updateFullscreenButtonState(this.isCssFullscreen);
            };
            
            // Update fullscreen button icon
            this.updateFullscreenButtonState = (isFullscreen) => {
                const fsIcon = this.fullscreenBtn.querySelector('i');
                if (fsIcon) {
                    fsIcon.className = isFullscreen ? 'pi pi-compress' : 'pi pi-expand';
                }
                
                // Update container classes
                this.container.classList.toggle('is-fullscreen', isFullscreen);
                
                // Re-trigger subtitle lift update if needed
                if (this.controlsVisible) this.showControls();
            };

            this.fullscreenBtn.onclick = (e) => {
                e.stopPropagation();
                e.preventDefault();

                // Check if we're on iOS (including iPadOS which reports as MacIntel with touch)
                const isIOS = this._isIOS();

                // Check current fullscreen state (including iOS native fullscreen)
                const isNativeFullscreen = !!(document.fullscreenElement || document.webkitFullscreenElement || this.isIOSNativeFullscreen);
                const isCssFullscreen = this.container.classList.contains('is-css-fullscreen');
                
                // If already in fullscreen, exit it
                if (isNativeFullscreen || isCssFullscreen) {
                    if (isNativeFullscreen) {
                        // Exit native fullscreen
                        if (document.exitFullscreen) {
                            document.exitFullscreen().catch(() => {});
                        } else if (document.webkitExitFullscreen) {
                            document.webkitExitFullscreen();
                        }
                    }
                    if (isCssFullscreen) {
                        this.toggleCssFullscreen();
                    }
                    return;
                }

                if (isIOS) {
                    // Save current position before fullscreen transition
                    // iOS may re-fetch the video source during native fullscreen,
                    // resetting currentTime to 0 — we'll restore it after the transition.
                    this._preFullscreenTime = this.video.currentTime;
                    
                    let nativeFullscreenAttempted = false;
                    
                    if (!this.video.hasAttribute('playsinline')) {
                        this.video.setAttribute('playsinline', 'true');
                        this.video.setAttribute('webkit-playsinline', 'true');
                    }
                    
                    // iOS 16+ supports standard Fullscreen API
                    if (this.video.requestFullscreen) {
                        try {
                            this.video.requestFullscreen();
                            nativeFullscreenAttempted = true;
                        } catch (err) {
                            console.log('[SimplePlayer] requestFullscreen failed:', err);
                        }
                    }
                    
                    // Fallback: legacy webkitEnterFullscreen (iOS < 16)
                    if (!nativeFullscreenAttempted && this.video.webkitEnterFullscreen) {
                        try {
                            this.video.webkitEnterFullscreen();
                            nativeFullscreenAttempted = true;
                        } catch (err) {
                            console.log('[SimplePlayer] webkitEnterFullscreen failed:', err);
                        }
                    }
                    
                    // If native fullscreen failed, use CSS fallback
                    if (!nativeFullscreenAttempted) {
                        console.log('[SimplePlayer] Using CSS fullscreen fallback for iOS');
                        this.toggleCssFullscreen();
                    }
                } else {
                    // Standard fullscreen API for other browsers
                    if (this.container.requestFullscreen) {
                        this.container.requestFullscreen().catch(err => {
                            console.log('[SimplePlayer] Fullscreen error, using CSS fallback:', err);
                            this.toggleCssFullscreen();
                        });
                    } else if (this.container.webkitRequestFullscreen) {
                        // Safari on macOS
                        this.container.webkitRequestFullscreen();
                    } else {
                        // Fallback to CSS fullscreen
                        this.toggleCssFullscreen();
                    }
                }
            };

            // Listen for fullscreen changes to update UI classes
            const onFullscreenChange = () => {
                const isNativeFullscreen = !!(document.fullscreenElement || document.webkitFullscreenElement);
                
                // Only update if we're in native fullscreen (not CSS fullscreen)
                if (!this.container.classList.contains('is-css-fullscreen')) {
                    this.container.classList.toggle('is-fullscreen', isNativeFullscreen);
                    this.updateFullscreenButtonState(isNativeFullscreen);
                    console.log('[SimplePlayer] Native fullscreen changed:', isNativeFullscreen);
                } else {
                    console.log('[SimplePlayer] Native fullscreen changed, but CSS fullscreen is active');
                }

                // On iOS 16+, native fullscreen may re-fetch the video source,
                // resetting currentTime to 0. Restore the position once playback resumes.
                if (isNativeFullscreen && this._preFullscreenTime != null && this._preFullscreenTime > 0) {
                    const restoreTime = this._preFullscreenTime;
                    const doRestore = () => {
                        const ct = this.video.currentTime;
                        if (ct === 0 || Math.abs(ct - restoreTime) > 0.5) {
                            this.video.currentTime = restoreTime;
                        }
                    };
                    doRestore();
                    this.video.addEventListener('canplay', () => doRestore(), { once: true });
                    this.video.addEventListener('seeking', () => doRestore(), { once: true });
                    this.video.addEventListener('timeupdate', () => doRestore(), { once: true });
                    setTimeout(() => { this._preFullscreenTime = null; }, 3000);
                }

                // Re-trigger subtitle lift update if needed
                if (this.controlsVisible) this.showControls();
            };

            document.addEventListener('fullscreenchange', onFullscreenChange);
            document.addEventListener('webkitfullscreenchange', onFullscreenChange);
            
            // Handle iOS-specific video fullscreen events (legacy iOS < 16)
            this.video.addEventListener('webkitbeginfullscreen', () => {
                console.log('[SimplePlayer] iOS video fullscreen started (legacy event)');
                this.isIOSNativeFullscreen = true;
                this.container.classList.add('is-fullscreen');
                this.updateFullscreenButtonState(true);
                this._syncSubtitleForNativeFullscreen();
            });
            this.video.addEventListener('webkitendfullscreen', () => {
                console.log('[SimplePlayer] iOS video fullscreen ended');
                this.isIOSNativeFullscreen = false;
                this.container.classList.remove('is-fullscreen');
                this.updateFullscreenButtonState(false);
                this._restoreSubtitlesAfterFullscreen();
            });
            
            // Handle orientation changes for CSS fullscreen
            if (screen.orientation) {
                screen.orientation.addEventListener('change', () => {
                    if (this.container.classList.contains('is-css-fullscreen')) {
                        // Re-scroll to ensure player fills screen after orientation change
                        window.scrollTo(0, 0);
                    }
                });
            }
            // Markers - use server-side seek for transcoded streams
            const handleSkipClick = (e, section) => {
                if (e.target.closest('.skip-auto-toggle')) return;
                if (section === 'outro') {
                    this.playNextEpisode();
                } else {
                    const endKey = section + 'End';
                    const end = this.markers[endKey];
                    if (this.needsTranscode && !this.hlsSessionId) {
                        this.performServerSeek(end);
                    } else {
                        this.video.currentTime = end;
                    }
                }
            };
            this.container.querySelector('#skipIntroBtn').onclick = (e) => handleSkipClick(e, 'intro');
            this.container.querySelector('#skipRecapBtn').onclick = (e) => handleSkipClick(e, 'recap');
            this.container.querySelector('#skipOutroBtn').onclick = (e) => handleSkipClick(e, 'outro');

            // Auto-skip toggle click handlers
            this.container.querySelectorAll('.skip-auto-toggle').forEach(toggle => {
                toggle.onclick = (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    const section = toggle.dataset.section;
                    const newState = !toggle.classList.contains('active');
                    toggle.classList.toggle('active', newState);
                    this['autoSkip' + section.charAt(0).toUpperCase() + section.slice(1)] = newState;
                    this._postAutoSkipSetting(section, newState);
                };
            });

            // Auto-skip notice button handlers
            this.autoSkipUndoBtn = document.getElementById('autoSkipUndoBtn');
            this.autoSkipToggleBtn = document.getElementById('autoSkipToggleBtn');
            this.autoSkipNotice = document.getElementById('autoSkipNotice');
            this.autoSkipNoticeText = document.getElementById('autoSkipNoticeText');

            if (this.autoSkipUndoBtn) {
                this.autoSkipUndoBtn.onclick = () => this._undoAutoSkip();
            }
            if (this.autoSkipToggleBtn) {
                this.autoSkipToggleBtn.onclick = () => {
                    if (this._autoSkipSection) {
                        this._disableAutoSkip(this._autoSkipSection);
                    }
                };
            }

            // Auto-hide controls
            this.container.onmousemove = () => this.showControls();
this.container.ontouchstart = () => this.showControls();
            
            // Store debugging info for manual refresh operations
            this.debugInfo = {
                seriesTitle: this.container.dataset.seriesTitle || '',
                seasonNumber: parseInt(this.container.dataset.seasonNumber || '1'),
                episodeNumber: parseInt(this.container.dataset.episodeNumber || '0'),
                seriesImdbId: this.container.dataset.seriesImdbId || ''
            };
            
            this.showControls();
            this.updatePageTitle();
        }

        handleKeydown(e) {
               // Debug: Log all keydown events to see if listener is working
               // console.log('[SimplePlayer] Keydown event:', e.code, 'shiftKey:', e.shiftKey, 'altKey:', e.altKey, 'ctrlKey:', e.ctrlKey);
               
               if (document.activeElement.tagName === 'INPUT' || document.activeElement.tagName === 'TEXTAREA') return;
               switch(e.code) {
                   case 'Space':
                   case 'KeyK': e.preventDefault(); this.bigPlay.click(); break;
                   case 'ArrowLeft': case 'KeyJ': 
                       {
                           // Calculate current display time (accounting for stream start offset)
                           const currentDisplayTime = this.video.currentTime + (this.streamStartOffset || 0);
                           const newTime = Math.max(0, currentDisplayTime - 10);
                           
                           // For transcoded streams, use server-side seek
                           if (this.needsTranscode && !this.hlsSessionId) {
                               this.performServerSeek(newTime);
                           } else {
                               this.video.currentTime = Math.max(0, this.video.currentTime - 10);
                           }
                           this.showControls();
                       }
                       break;
                   case 'ArrowRight': case 'KeyL': 
                       {
                           const maxDur = this.totalDuration || this.video.duration;
                           // Calculate current display time (accounting for stream start offset)
                           const currentDisplayTime = this.video.currentTime + (this.streamStartOffset || 0);
                           const newTime = Math.min(maxDur || this.video.duration, currentDisplayTime + 10);
                           
                           // For transcoded streams, use server-side seek
                           if (this.needsTranscode && !this.hlsSessionId) {
                               this.performServerSeek(newTime);
                           } else {
                               this.video.currentTime = Math.min(maxDur || this.video.duration, this.video.currentTime + 10);
                           }
                           this.showControls();
                       }
                       break;
                    case 'KeyF': this.fullscreenBtn.click(); break;
                    case 'KeyM': this.muteBtn.click(); break;
                    // Debug dialog toggle shortcut
                    case 'KeyD': 
                        if (e.ctrlKey && e.altKey) {
                            e.preventDefault();
                            console.log('[SimplePlayer] Ctrl+Alt+D pressed, toggling debug dialog');
                            this.toggleDebugDialog();
                        }
                        break;
                    case 'Escape':
                        e.preventDefault();
                        this.closeDebugDialog();
                        break;
               }
           }

        handleMouseMove(e) {
            const rect = this.progressContainer.getBoundingClientRect();
            
            // Use DB duration for preview/hover (stream may report wrong)
            let dur = this.totalDuration;
            if (!dur || dur === Infinity) {
                dur = this.video.duration;
            }
            if (!dur) return;
            
            const pct = (e.clientX - rect.left) / rect.width;
            const time = pct * dur;
            
            this.preview.classList.add('active');
            this.preview.style.left = Math.max(0, Math.min(rect.width - 160, e.clientX - rect.left - 80)) + 'px';
            this.previewTime.innerText = this.formatTime(time);

            if (this.storyboard.loaded && this.storyboard.metadata) {
                const m = this.storyboard.metadata;
                const tileIndex = Math.min(Math.floor(time / m.interval), m.totalTiles - 1);
                const col = tileIndex % m.columns;
                const row = Math.floor(tileIndex / m.columns);
                this.previewImg.style.backgroundImage = `url(${this._storyboardUrl || `/api/video/storyboard/${this.videoId}`})`;
                this.previewImg.style.backgroundPosition = `-${col * m.width}px -${row * m.height}px`;
                this.previewImg.style.backgroundSize = `${m.width * m.columns}px ${m.height * m.rows}px`;
            }
        }

        showControls() {
            this.container.classList.remove('controls-hidden');
            if (window.subtitleManager) window.subtitleManager.setSubtitleLift(45);

            clearTimeout(this.userActiveTimeout);
            if (!this.video.paused) {
                this.userActiveTimeout = setTimeout(() => {
                    this.container.classList.add('controls-hidden');
                    if (window.subtitleManager) window.subtitleManager.setSubtitleLift(0);
                }, 3000);
            }
        }

        applyInitialState() {
            this.video.volume = Math.pow(this.state.volume, 2);
            this.video.muted = this.state.muted;
            this.updateVolumeUI();
            if (this.totalDuration > 0) this.timeTotal.innerText = this.formatTime(this.totalDuration);
            
            // Update current time display to account for server-side seek offset
            // This ensures the UI shows the correct resume time on initial load
            if (this.streamStartOffset > 0) {
                this.timeCurrent.innerText = this.formatTime(this.streamStartOffset);
                // Also update progress bar to show correct position
                const pct = (this.streamStartOffset / this.totalDuration) * 100;
                this.progressBar.style.width = Math.min(100, pct) + '%';
            }
        }

        updateVolumeUI() {
            const icon = this.muteBtn.querySelector('i');
            if (this.video.muted || this.state.volume === 0) icon.className = 'pi pi-volume-off';
            else icon.className = this.state.volume < 0.5 ? 'pi pi-volume-down' : 'pi pi-volume-up';
            this.volSlider.value = this.state.volume;
        }

formatTime(s) {
             if (isNaN(s) || s < 0 || s === Infinity) return '0:00';
             const h = Math.floor(s / 3600);
             const m = Math.floor((s % 3600) / 60);
             const secs = Math.floor(s % 60);
             return (h > 0 ? h + ':' : '') + (h > 0 ? m.toString().padStart(2, '0') : m) + ':' + secs.toString().padStart(2, '0');
         }

          // Update debug info panel with current video information
            _updateDebugDialog() {
                // Populate editable fields with current values
                if (this.dialogSeriesInput) this.dialogSeriesInput.value = this.debugInfo.seriesTitle;
                if (this.dialogSeasonInput) this.dialogSeasonInput.value = this.debugInfo.seasonNumber;
                if (this.dialogEpisodeInput) this.dialogEpisodeInput.value = this.debugInfo.episodeNumber;
                if (this.dialogImdbInput) this.dialogImdbInput.value = this.debugInfo.seriesImdbId;
                
                // Update detailed marker information
                const updateElement = (id, value) => {
                    const el = document.getElementById(id);
                    if (el) el.textContent = value;
                };
                
                // Marker values with sources
                updateElement('dialog-intro-start', `${this.markers.introStart} (${this.markerSources.introStart || 'UNKNOWN'})`);
                updateElement('dialog-intro-end', `${this.markers.introEnd} (${this.markerSources.introEnd || 'UNKNOWN'})`);
                updateElement('dialog-outro-start', `${this.markers.outroStart} (${this.markerSources.outroStart || 'UNKNOWN'})`);
                updateElement('dialog-outro-end', `${this.markers.outroEnd} (${this.markerSources.outroEnd || 'UNKNOWN'})`);
                updateElement('dialog-recap-start', `${this.markers.recapStart} (${this.markerSources.recapStart || 'UNKNOWN'})`);
                updateElement('dialog-recap-end', `${this.markers.recapEnd} (${this.markerSources.recapEnd || 'UNKNOWN'})`);
                
                // Original marker values for comparison
                updateElement('dialog-original-intro-start', `${this.originalMarkers?.introStart ?? 0}`);
                updateElement('dialog-original-intro-end', `${this.originalMarkers?.introEnd ?? 0}`);
                updateElement('dialog-original-outro-start', `${this.originalMarkers?.outroStart ?? 0}`);
                updateElement('dialog-original-outro-end', `${this.originalMarkers?.outroEnd ?? 0}`);
                updateElement('dialog-original-recap-start', `${this.originalMarkers?.recapStart ?? 0}`);
                updateElement('dialog-original-recap-end', `${this.originalMarkers?.recapEnd ?? 0}`);
                
                // Refresh status (this would be set during refresh operations)
                updateElement('dialog-refresh-status', this.refreshStatus || 'No refresh attempted');
                updateElement('dialog-refresh-error', this.refreshError || 'None');
            }

            async saveAndReload() {
                const seriesTitle = this.dialogSeriesInput?.value?.trim() || '';
                const seasonNumber = this.dialogSeasonInput?.value?.trim() || '';
                const episodeNumber = this.dialogEpisodeInput?.value?.trim() || '';
                const showImdbId = this.dialogImdbInput?.value?.trim() || '';
                
                const formData = new FormData();
                if (seriesTitle) formData.append('seriesTitle', seriesTitle);
                if (seasonNumber) formData.append('seasonNumber', seasonNumber);
                if (episodeNumber) formData.append('episodeNumber', episodeNumber);
                if (showImdbId) formData.append('showImdbId', showImdbId);
                
                this.refreshStatus = 'Saving overrides...';
                this._updateDebugDialog();
                
                try {
                    const saveRes = await fetch(`/api/video/manage/update/${this.videoId}`, {
                        method: 'POST',
                        body: new URLSearchParams(formData)
                    });
                    
                    if (!saveRes.ok) throw new Error(`Save failed: HTTP ${saveRes.status}`);
                    
                    this.refreshStatus = 'Overrides saved, re-enriching...';
                    this._updateDebugDialog();
                    
                    // Trigger re-enrichment
                    const reloadRes = await fetch(`/api/video/metadata/${this.videoId}/reload`, {method: 'POST'});
                    if (!reloadRes.ok) throw new Error(`Reload failed: HTTP ${reloadRes.status}`);
                    
                    this.refreshStatus = 'Re-enrichment triggered, fetching fresh data...';
                    
                    // Wait a moment then fetch fresh video data
                    await new Promise(r => setTimeout(r, 2000));
                    
                    const videoRes = await fetch(`/api/video/${this.videoId}`);
                    if (videoRes.ok) {
                        const json = await videoRes.json();
                        const data = json.data || json;
                        
                        // Update debugInfo with fresh server values
                        this.debugInfo.seriesTitle = data.seriesTitle || this.debugInfo.seriesTitle;
                        this.debugInfo.seasonNumber = data.seasonNumber || this.debugInfo.seasonNumber;
                        this.debugInfo.episodeNumber = data.episodeNumber || this.debugInfo.episodeNumber;
                        this.debugInfo.seriesImdbId = data.showImdbId || data.seriesImdbId || this.debugInfo.seriesImdbId;
                        
                        // Refresh markers from fresh data
                        if (data.introStart !== undefined) {
                            this.markers = {
                                introStart: data.introStart || 0,
                                introEnd: data.introEnd || 0,
                                outroStart: data.outroStart || 0,
                                outroEnd: data.outroEnd || 0,
                                recapStart: data.recapStart || 0,
                                recapEnd: data.recapEnd || 0
                            };
                            this.markerSources = {
                                introStart: 'SERVER-REFRESHED',
                                introEnd: 'SERVER-REFRESHED',
                                outroStart: 'SERVER-REFRESHED',
                                outroEnd: 'SERVER-REFRESHED',
                                recapStart: 'SERVER-REFRESHED',
                                recapEnd: 'SERVER-REFRESHED'
                            };
                        }
                        
                        this.refreshStatus = 'Overrides saved and data refreshed';
                        this.refreshError = null;
                    } else {
                        this.refreshStatus = 'Overrides saved, but failed to fetch fresh data';
                    }
                } catch (err) {
                    console.error('[SimplePlayer] Save & reload failed:', err);
                    this.refreshStatus = 'Save failed';
                    this.refreshError = err.message || 'Unknown error';
                }
                
                this._updateDebugDialog();
            }
          
           toggleDebugDialog() {
               if (this.debugDialog) {
                   this._updateDebugDialog();
                   const isHidden = this.debugDialog.style.display === 'none' || !this.debugDialog.style.display;
                   this.debugDialog.style.display = isHidden ? 'flex' : 'none';
               }
           }
          
          closeDebugDialog() {
              if (this.debugDialog) {
                  this.debugDialog.style.display = 'none';
              }
          }

         updateMarkers() {
            // Use DB duration for markers (stream may report wrong)
            let dur = this.totalDuration;
            if (!dur || dur === Infinity) {
                dur = this.video.duration;
            }
            if (!dur || dur === Infinity) return;
            const showMarker = (m, start, end) => {
                const el = this.container.querySelector(m);
                if (el && start > 0 && end > 0) {
                    el.style.left = (start / dur * 100) + '%';
                    el.style.width = ((end - start) / dur * 100) + '%';
                    el.style.display = 'block';
                }
            };
            showMarker('.progress-intro-marker', this.markers.introStart, this.markers.introEnd);
            showMarker('.progress-outro-marker', this.markers.outroStart, dur);
        }

        checkMarkers() {
            // Use display time (accounting for stream start offset)
            const t = this.video.currentTime + (this.streamStartOffset || 0);
            const show = (id, visible) => {
                const el = document.getElementById(id);
                if (el) el.style.display = visible ? 'flex' : 'none';
            };
            show('skipIntroBtn', t >= this.markers.introStart && t < this.markers.introEnd);
            show('skipRecapBtn', t >= this.markers.recapStart && t < this.markers.recapEnd);
            show('skipOutroBtn', t >= this.markers.outroStart && this.markers.outroStart > 0);
        }

        _checkAutoSkip(t) {
            if (this._isUndoing) return;

            if (this.autoSkipIntro && t >= this.markers.introStart && t < this.markers.introEnd) {
                this._performAutoSkip('intro', this.markers.introStart, this.markers.introEnd);
                return;
            }
            if (this.autoSkipRecap && t >= this.markers.recapStart && t < this.markers.recapEnd) {
                this._performAutoSkip('recap', this.markers.recapStart, this.markers.recapEnd);
                return;
            }
            if (this.autoSkipOutro && t >= this.markers.outroStart && this.markers.outroStart > 0) {
                this._performAutoSkip('outro', this.markers.outroStart, this.markers.outroEnd);
                return;
            }
        }

        _performAutoSkip(section, start, end) {
            this._autoSkipUndoTime = start;
            this._autoSkipSection = section;

            if (section === 'outro') {
                this.playNextEpisode();
                return;
            }

            if (this.needsTranscode && !this.hlsSessionId) {
                this.performServerSeek(end);
            } else {
                this.video.currentTime = end;
            }

            this._showAutoSkipNotice(section);
        }

        _showAutoSkipNotice(section) {
            if (!this.autoSkipNotice || !this.autoSkipNoticeText) return;

            const labels = { intro: 'Intro skipped', recap: 'Recap skipped', outro: 'Outro skipped' };
            this.autoSkipNoticeText.textContent = labels[section] || 'Section skipped';
            this.autoSkipNotice.style.display = 'flex';

            if (this._autoSkipTimer) {
                clearTimeout(this._autoSkipTimer);
            }
            this._autoSkipTimer = setTimeout(() => {
                if (this.autoSkipNotice) {
                    this.autoSkipNotice.style.display = 'none';
                }
            }, 5000);
        }

        _undoAutoSkip() {
            if (this._autoSkipUndoTime > 0) {
                this._isUndoing = true;
                if (this.needsTranscode && !this.hlsSessionId) {
                    this.performServerSeek(this._autoSkipUndoTime);
                } else {
                    this.video.currentTime = this._autoSkipUndoTime;
                }
                if (this.autoSkipNotice) {
                    this.autoSkipNotice.style.display = 'none';
                }
                if (this._autoSkipTimer) {
                    clearTimeout(this._autoSkipTimer);
                    this._autoSkipTimer = null;
                }
                setTimeout(() => { this._isUndoing = false; }, 1000);
            }
        }

        _disableAutoSkip(section) {
            this['autoSkip' + section.charAt(0).toUpperCase() + section.slice(1)] = false;
            this._postAutoSkipSetting(section, false);
            if (this.autoSkipNotice) {
                this.autoSkipNotice.style.display = 'none';
            }
            if (this._autoSkipTimer) {
                clearTimeout(this._autoSkipTimer);
                this._autoSkipTimer = null;
            }
            const toggle = this.container.querySelector(`.skip-auto-toggle[data-section="${section}"]`);
            if (toggle) {
                toggle.classList.remove('active');
                toggle.querySelector('input[type="checkbox"]').checked = false;
            }
        }

        _postAutoSkipSetting(section, enabled) {
            const profileId = localStorage.getItem('activeProfileId') || '1';
            const key = 'autoSkip' + section.charAt(0).toUpperCase() + section.slice(1);
            const body = {};
            body[key] = enabled;
            fetch(`/api/settings/${profileId}/auto-skip`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            }).catch(err => console.error('[SimplePlayer] Failed to save auto-skip setting:', err));
        }

        turnOffSubtitles() {
            console.log('[SimplePlayer] Turning off subtitles');
            this.destroyAssSubtitle();
            const isAppleSafari = this._isIOS() || (/Safari/i.test(navigator.userAgent) && !/Chrome/i.test(navigator.userAgent));
            if (this._isNativeHls || isAppleSafari) {
                // Apple browsers: <track> elements can't be re-added after source loads.
                // Keep DOM elements, just hide via mode.
                if (this.video.textTracks) {
                    for (let i = 0; i < this.video.textTracks.length; i++) {
                        this.video.textTracks[i].mode = 'hidden';
                    }
                }
            } else {
                this.video.querySelectorAll('track').forEach(el => {
                    if (el.track) el.track.mode = 'hidden';
                    el.remove();
                });
                if (this.video.textTracks) {
                    for (let i = 0; i < this.video.textTracks.length; i++) {
                        this.video.textTracks[i].mode = 'hidden';
                    }
                }
            }
        }

        destroyAssSubtitle() {
            if (this.jassubRenderer) {
                console.log('[SimplePlayer] Destroying ASS subtitle renderer');
                this.jassubRenderer.destroy();
                this.jassubRenderer = null;
            }
        }

        async initAssSubtitle(trackId) {
            console.log('[SimplePlayer] Initializing ASS subtitle for track:', trackId);
            this.destroyAssSubtitle();
            try {
                const res = await fetch(`/api/video/subtitles/track/${trackId}/raw`);
                if (!res.ok) {
                    console.error('[SimplePlayer] Failed to fetch raw subtitle:', res.status);
                    return;
                }
                const content = await res.text();

                let canvas = document.getElementById('assCanvas');
                if (!canvas) {
                    const wrapper = this.container.querySelector('.video-wrapper');
                    canvas = document.createElement('canvas');
                    canvas.id = 'assCanvas';
                    canvas.className = 'ass-canvas';
                    (wrapper || this.container).appendChild(canvas);
                }

                const userCorrection = parseFloat(localStorage.getItem('jmedia_subtitle_correction') || '0');
                const totalOffset = (this.streamStartOffset || 0) + userCorrection;

                this.jassubRenderer = new JASSUB.default({
                    video: this.video,
                    canvas: canvas,
                    subContent: content,
                    workerUrl: '/lib/jassub/jassub-worker.js',
                    wasmUrl: '/lib/jassub/jassub-worker.wasm',
                    modernWasmUrl: '/lib/jassub/jassub-worker-modern.wasm',
                    defaultFont: '/lib/jassub/default.woff2',
                    timeOffset: totalOffset * 1000,
                    prescaleFactor: 1.0,
                    prescaleHeightLimit: 1080,
                    maxRenderHeight: 0,
                    debug: false
                });

                console.log('[SimplePlayer] JASSUB renderer initialized');
            } catch (e) {
                console.error('[SimplePlayer] ASS subtitle init failed:', e);
            }
        }

        async loadSubtitles(keepMenuOpen = false) {
            try {
                console.log('[SimplePlayer] Loading subtitles for video:', this.videoId);
                const tracksRes = await fetch(`/api/video/subtitles/${this.videoId}`);
                if (!tracksRes.ok) {
                    console.error('[SimplePlayer] Subtitle API error:', tracksRes.status);
                    return;
                }
                const tracksData = await tracksRes.json();
                console.log('[SimplePlayer] Subtitle response:', tracksData);
                const tracks = tracksData.tracks || tracksData.data || [];
                console.log('[SimplePlayer] Found tracks:', tracks.length);
                
                const list = this.container.querySelector('#subtitleList');
                if (!list) {
                    console.error('[SimplePlayer] Subtitle list element not found');
                    return;
                }
                
                // Clear existing options except "Off" which should always be there
                const offOption = list.querySelector('#sub-off');
                if (offOption) {
                    // Remove all other options
                    list.querySelectorAll('.subtitle-option:not(#sub-off)').forEach(el => el.remove());
                    // Setup Off button handler
                    offOption.onclick = (e) => {
                        e.stopPropagation();
                        this.turnOffSubtitles();
                        // Update UI
                        this.container.querySelectorAll('.subtitle-option').forEach(el => el.classList.remove('active'));
                        offOption.classList.add('active');
                        this.subtitleMenu.classList.remove('active');
                        localStorage.setItem('jmedia_last_track_' + this.videoId, 'off');
                        this.lastSelectedTrackId = 'off';
                    };
                    // Set active state based on last selection
                    if (!this.lastSelectedTrackId || this.lastSelectedTrackId === 'off') {
                        offOption.classList.add('active');
                    }
                }
                
                tracks.forEach(t => {
                    const opt = document.createElement('div');
                    opt.className = 'subtitle-option';
                    opt.setAttribute('data-id', t.id);  // Ensure data-id is set for all options
                    opt.innerText = `${t.displayName || t.filename} (${t.isEmbedded ? 'Embedded' : 'External'})`;
                    opt.onclick = (e) => {
                        e.stopPropagation();
                        console.log('[SimplePlayer] Selecting subtitle:', t);
                        
                        this.destroyAssSubtitle();
                        
                        if (this._isNativeHls) {
                            // Native HLS: can't add/remove <track> elements dynamically.
                            // Toggle modes on pre-created textTracks instead.
                            if (t.id !== 'off' && this.video.textTracks) {
                                for (let i = 0; i < this.video.textTracks.length; i++) {
                                    const tr = this.video.textTracks[i];
                                    const trackEl = this.video.querySelector(`track[id="subtitle-track-${t.id}"]`);
                                    const isSelected = trackEl && (tr.label === trackEl.label);
                                    tr.mode = isSelected ? 'showing' : 'hidden';
                                }
                            } else if (this.video.textTracks) {
                                for (let i = 0; i < this.video.textTracks.length; i++) {
                                    this.video.textTracks[i].mode = 'hidden';
                                }
                            }
                        } else {
                            const isAppleSafari = this._isIOS() || (/Safari/i.test(navigator.userAgent) && !/Chrome/i.test(navigator.userAgent));
                            if (isAppleSafari) {
                                // iOS/Safari non-HLS: tracks were pre-created before source.
                                // Toggle modes instead of recreating DOM elements.
                                if (t.id !== 'off' && this.video.textTracks) {
                                    for (let i = 0; i < this.video.textTracks.length; i++) {
                                        const tr = this.video.textTracks[i];
                                        const trackEl = this.video.querySelector(`track[id="subtitle-track-${t.id}"]`);
                                        const isSelected = trackEl && (tr.label === trackEl.label);
                                        tr.mode = isSelected ? 'showing' : 'hidden';
                                    }
                                } else if (this.video.textTracks) {
                                    for (let i = 0; i < this.video.textTracks.length; i++) {
                                        this.video.textTracks[i].mode = 'hidden';
                                    }
                                }
                            } else {
                                // Non-Apple: freely create/destroy <track> elements
                                this.video.querySelectorAll('track').forEach(el => {
                                    if (el.track) el.track.mode = 'hidden';
                                    el.remove();
                                });
                                if (this.video.textTracks) {
                                    for (let i = 0; i < this.video.textTracks.length; i++) {
                                        this.video.textTracks[i].mode = 'hidden';
                                    }
                                }
                                
                                if (t.id !== 'off') {
                                    // Subtitle via native <track> element
                                    const track = document.createElement('track');
                                track.kind = 'subtitles';
                                
                                // Get correction from localStorage
                                const correction = localStorage.getItem('jmedia_subtitle_correction') || 0;
                                
                                // Build URL with start offset for server-side seek
                                const startOffset = this.streamStartOffset || 0;
                                let src = `/api/video/subtitles/track/${t.id}?start=${startOffset}`;
                                if (parseFloat(correction) !== 0) {
                                    src += `&correction=${correction}`;
                                }
                                
                                track.src = src;
                                track.srclang = t.language || 'en';
                                track.label = t.displayName || 'Subtitle';
                                track.default = true;
                                track.id = 'subtitle-track-' + t.id;
                                this.video.appendChild(track);
                            
                                // Wait for track to be ready in textTracks
                                const setupTrack = () => {
                                    const tracks = Array.from(this.video.textTracks || []);
                                    console.log('[SimplePlayer] Available textTracks:', tracks.map(t => ({ label: t.label, mode: t.mode, kind: t.kind })));
                                    
                                    let textTrack = tracks.find(tr => tr.label === (t.displayName || 'Subtitle'));
                                    
                                    if (!textTrack) {
                                        textTrack = tracks.find(tr => tr.kind === 'subtitles' && tr.mode !== 'disabled');
                                    }
                                    
                                    if (textTrack) {
                                        console.log('[SimplePlayer] Setting textTrack to showing:', textTrack.label);
                                        textTrack.mode = 'showing';
                                        
                                        // Firefox specific overlay update
                                        const updateFF = () => {
                                            if (window.subtitleManager && /Firefox/i.test(navigator.userAgent)) {
                                                const activeCues = textTrack.activeCues;
                                                const overlay = document.getElementById('firefox-subtitle-overlay');
                                                if (overlay) {
                                                    if (activeCues && activeCues.length > 0) {
                                                        overlay.innerHTML = Array.from(activeCues).map(c => c.text).join('\n');
                                                        overlay.classList.add('active');
                                                    } else {
                                                        overlay.classList.remove('active');
                                                    }
                                                }
                                            }
                                        };

                                        textTrack.oncuechange = (e) => {
                                            updateFF();
                                            if (textTrack.activeCues && textTrack.activeCues.length > 0) {
                                                console.log('[SimplePlayer] Active cues:', Array.from(textTrack.activeCues).map(c => c.text));
                                            }
                                        };
                                        
                                        // Initial update
                                        updateFF();
                                    } else {
                                        console.log('[SimplePlayer] TextTrack not found yet, retrying... Available:', tracks.length);
                                        setTimeout(setupTrack, 200);
                                    }
                                };
                                
                                track.addEventListener('load', () => {
                                    console.log('[SimplePlayer] Track element loaded');
                                    setTimeout(setupTrack, 100);
                                });
                                
                                track.addEventListener('error', (e) => {
                                    console.error('[SimplePlayer] Track load error:', e);
                                });
                                
                                setTimeout(setupTrack, 500);
                            } else {
                                console.log('[SimplePlayer] Subtitles turned OFF');
                            }
                        }
                        }
                        
                        this.container.querySelectorAll('.subtitle-option').forEach(el => el.classList.remove('active'));
                        opt.classList.add('active');
                        if (!keepMenuOpen) this.subtitleMenu.classList.remove('active');
                        localStorage.setItem('jmedia_last_track_' + this.videoId, t.id);
                        
                        // Store reference to current track for seeking updates
                        this.currentSubtitleTrackId = t.id;
                    };
                    list.appendChild(opt);
                    if (this.lastSelectedTrackId == t.id) opt.click();
                });
                
                // If global track didn't match any available track, handle it
                if (this.lastSelectedTrackId !== null && this.lastSelectedTrackId !== 'off') {
                    const matched = tracks.some(t => t.id == this.lastSelectedTrackId);
                    if (!matched && tracks.length > 0) {
                        // Global preference was ON but this track not available, use first available
                        list.querySelector('.subtitle-option:not(#sub-off)')?.click();
                    }
                }
                
                // If no track was auto-clicked, click 'Off' by default
                if (this.lastSelectedTrackId == null && list.querySelector('#sub-off')) {
                    list.querySelector('#sub-off').click();
                }
                
                // Clear global subtitle preference so it doesn't affect unrelated navigation
                sessionStorage.removeItem('jmedia_global_subtitle_track');
            } catch (e) { console.error('Subtitle load failed', e); }
        }

        async loadStoryboard() {
            try {
                const res = await fetch(`/api/video/storyboard/${this.videoId}/metadata`);
                if (res.ok) {
                    const json = await res.json();
                    this.storyboard.metadata = json.data || json;
                    const wasReady = this.storyboard.loaded;
                    this.storyboard.loaded = this.storyboard.metadata.isReady;
                    
                    // If storyboard just became ready, refresh the preview image
                    if (this.storyboard.loaded && !wasReady) {
                        console.log('[SimplePlayer] Storyboard generation complete, refreshing preview');
                        // Force background image refresh by adding a cache-busting query
                        // Store a stable cache-busting timestamp that updates when storyboard becomes ready
                        this._storyboardUrl = `/api/video/storyboard/${this.videoId}?t=${Date.now()}`;
                    }
                    
                    // If not ready yet, poll again in 2 seconds
                    if (!this.storyboard.loaded) {
                        setTimeout(() => this.loadStoryboard(), 2000);
                    }
                }
            } catch (e) {}
        }

        startProgressReporting() {
            this._prog = setInterval(() => {
                if (!this.video.paused && this.video.currentTime > 0) {
                    const displayTime = Math.min(this.video.currentTime + (this.streamStartOffset || 0), this.totalDuration || Infinity);
                    this._reportProgress(displayTime, !this.video.paused);
                }
            }, 5000);

            // pagehide already added in constructor, visibilitychange remains for tab hide
            document.addEventListener('visibilitychange', () => {
                if (document.visibilityState === 'hidden') this.saveProgressNow();
            });
        }

        _reportProgress(time, playing) {
            if (this.externalId) {
                const body = { currentTime: time, duration: this.video.duration || 0 };
                fetch(`/api/video/external/${this.externalId}/progress`, {
                    method: 'POST',
                    credentials: 'include',
                    keepalive: true,
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                }).catch(() => {});
            } else {
                const url = `/api/video/playback/progress?videoId=${this.videoId}&time=${time}&playing=${playing}&device=${this.deviceToken}`;
                fetch(url, { 
                    method: 'POST',
                    credentials: 'include',
                    keepalive: true,
                    headers: { 'X-User-ID': this.profileId }
                }).catch(err => console.error('[SimplePlayer] Progress report failed:', err));
            }
        }

        saveProgressNow() {
            if (this.video.currentTime > 0 || this.streamStartOffset > 0) {
                const displayTime = Math.min(this.video.currentTime + (this.streamStartOffset || 0), this.totalDuration || Infinity);
                this._reportProgress(displayTime, !this.video.paused);
            }
        }

        performServerSeek(time, forceServerSeek = false) {
            if (this._qualitySwitching && !forceServerSeek) {
                console.log('[SimplePlayer] Deferring seek during quality switch');
                setTimeout(() => this.performServerSeek(time, forceServerSeek), 200);
                return;
            }
            console.log(`[SimplePlayer] Performing server-side seek to ${time}s (force=${forceServerSeek})`);
            
            // For HLS streams, seeking is native — just set currentTime
            if (this.hlsSessionId) {
                console.log('[SimplePlayer] HLS stream — using native seek');
                this.video.currentTime = time - (this.streamStartOffset || 0);
                return;
            }
            
            // If seeking within the current stream's seekable range, do a client-side seek
            // UNLESS forceServerSeek is true (e.g., quality change requires a new transcode).
            if (!forceServerSeek && this.video.src) {
                const relativeTime = time - (this.streamStartOffset || 0);
                // Check browser's seekable ranges — this accurately reflects what's actually
                // available in the stream, unlike video.duration which may be partial for fMP4.
                let canClientSeek = false;
                if (this.video.seekable && this.video.seekable.length > 0) {
                    for (let i = 0; i < this.video.seekable.length; i++) {
                        if (relativeTime >= this.video.seekable.start(i) && relativeTime <= this.video.seekable.end(i)) {
                            canClientSeek = true;
                            break;
                        }
                    }
                }
                // Fallback: if seekable is empty (e.g. metadata not loaded), check via duration
                if (!canClientSeek && this.video.duration && relativeTime >= 0 && relativeTime <= this.video.duration) {
                    canClientSeek = true;
                }
                
                if (canClientSeek) {
                    console.log(`[SimplePlayer] Client-side seek to ${time}s (relative ${relativeTime}s)`);
                    this.video.currentTime = relativeTime;
                    this.video.play().catch(e => console.log('[SimplePlayer] Play after seek requires gesture:', e));
                    return;
                }
                console.log(`[SimplePlayer] Target ${time}s not in seekable range, falling back to server-side seek`);
            }
            
            this._doServerSeek(time);

            // Refresh subtitles if one is selected to ensure it gets the correct start offset
            if (this.lastSelectedTrackId && this.lastSelectedTrackId !== 'off') {
                const reloadSubtitles = async () => {
                    console.log('[SimplePlayer] Reloading subtitles after seek, track:', this.lastSelectedTrackId);
                    
                    // Reload subtitle list first, then restore selection
                    await this.loadSubtitles(true); // true = keep menu open (don't close)
                    
                    // Wait for subtitle list to be populated, then restore selection
                    const checkAndRestore = () => {
                        const activeOpt = this.subtitleList?.querySelector(`.subtitle-option[data-id="${this.lastSelectedTrackId}"]`);
                        if (activeOpt) {
                            console.log('[SimplePlayer] Restoring subtitle track after seek:', this.lastSelectedTrackId);
                            activeOpt.click();
                            return true;
                        }
                        return false;
                    };
                    
                    // Try immediately, then retry with intervals
                    if (!checkAndRestore()) {
                        let attempts = 0;
                        const retryInterval = setInterval(() => {
                            if (checkAndRestore() || attempts++ > 20) {
                                clearInterval(retryInterval);
                                if (attempts > 20) {
                                    console.warn('[SimplePlayer] Failed to restore subtitle track after seek:', this.lastSelectedTrackId);
                                }
                            }
                        }, 300);
                    }
                };
                
                // Wait for video metadata to load before reloading subtitles
                if (this.video.readyState >= 1) {
                    setTimeout(() => reloadSubtitles(), 500);
                } else {
                    this.video.addEventListener('loadedmetadata', () => {
                        setTimeout(() => reloadSubtitles(), 500);
                    }, { once: true });
                }
            }
        }

        setMusicSuspended(s) {
            window.videoPlaying = s;
            document.body.setAttribute('data-video-active', s ? 'true' : 'false');
            if (s) document.querySelectorAll('audio').forEach(a => a.pause());
        }

        goBack() { if (window.videoSPA) window.videoSPA.goBack(); else window.history.back(); }
        goToDetails() { if (window.videoSPA) window.videoSPA.switchSection('details', { videoId: this.videoId }); }

        _doServerSeek(time) {
            console.log(`[SimplePlayer] Server-side seek to ${time}s — starting new transcode`);

            if (this.buffering) this.buffering.style.display = 'block';

            // Clear stall timer so it doesn't create a competing stream
            this._hasPlayedData = false;
            this.lastKnownGoodPosition = 0;
            if (this._stallTimer) {
                clearTimeout(this._stallTimer);
                this._stallTimer = null;
            }

            // Keep quality-switching flag set until the stream actually plays
            if (this._qualitySwitching) {
                this.video.addEventListener('playing', () => {
                    this._qualitySwitching = false;
                }, { once: true });
            }

            // Remove any previous orphaned seek error handler
            if (this._seekErrorHandler) {
                this.video.removeEventListener('error', this._seekErrorHandler);
                this._seekErrorHandler = null;
            }

            // Remove the initial stream error handler — it captures savedTime from init()
            // and would fall back with the wrong position during a quality switch
            if (this._setupStreamErrorHandler) {
                this.video.removeEventListener('error', this._setupStreamErrorHandler);
                this._setupStreamErrorHandler = null;
            }

            this.video.pause();
            this.video.src = "";
            this.video.load();

            this.streamStartOffset = time;
            const audioParam = this.currentAudioTrackIndex !== null ? `&audioTrack=${this.currentAudioTrackIndex}` : '';
            const qualityParam = this._preferredQuality > 0 ? `&quality=${this._preferredQuality}` : '';
            this.video.src = `/api/video/stream/${this.videoId}?start=${time}${audioParam}${qualityParam}`;
            this.video.load();
            this._seekErrorHandler = (e) => {
                console.error('[SimplePlayer] Quality-switched stream error, falling back to HLS');
                if (this._destroyed) return;
                this._seekErrorHandler = null;
                this._qualitySwitching = false;
                this.initHlsStream(time);
            };
            this.video.addEventListener('error', this._seekErrorHandler, { once: true });
            this.video.play().catch(e => {
                if (this._destroyed) return;
                console.warn('[SimplePlayer] Could not auto-play after seek:', e);
            });
        }

        async playNextEpisode() {
            // Remember fullscreen state to restore on next episode (including iOS native fullscreen)
            const isFullscreen = document.fullscreenElement || document.webkitFullscreenElement || 
                                this.container.classList.contains('is-css-fullscreen') || this.isIOSNativeFullscreen;
            if (isFullscreen) {
                sessionStorage.setItem('jmedia_restore_fullscreen', 'true');
            }
            // Remember subtitle state - save if subtitles were on
            if (this.lastSelectedTrackId && this.lastSelectedTrackId !== 'off') {
                sessionStorage.setItem('jmedia_global_subtitle_track', this.lastSelectedTrackId);
            } else {
                sessionStorage.setItem('jmedia_global_subtitle_track', 'off');
            }
            // Try to get next episode from series
            try {
                const res = await fetch(`/api/video/playback/next/${this.videoId}`);
                if (res.ok) {
                    const data = await res.json();
                    if (data.nextVideoId && window.videoSPA) {
                        window.videoSPA.playVideo(data.nextVideoId);
                    }
                }
            } catch (e) { console.error('Failed to load next episode', e); }
        }

        async playPreviousEpisode() {
            // Remember fullscreen state to restore on previous episode (including iOS native fullscreen)
            const isFullscreen = document.fullscreenElement || document.webkitFullscreenElement || 
                                this.container.classList.contains('is-css-fullscreen') || this.isIOSNativeFullscreen;
            if (isFullscreen) {
                sessionStorage.setItem('jmedia_restore_fullscreen', 'true');
            }
            // Remember subtitle state - save if subtitles were on
            if (this.lastSelectedTrackId && this.lastSelectedTrackId !== 'off') {
                sessionStorage.setItem('jmedia_global_subtitle_track', this.lastSelectedTrackId);
            } else {
                sessionStorage.setItem('jmedia_global_subtitle_track', 'off');
            }
            // Try to get previous episode from series
            try {
                const res = await fetch(`/api/video/playback/previous/${this.videoId}`);
                if (res.ok) {
                    const data = await res.json();
                    if (data.previousVideoId && window.videoSPA) {
                        window.videoSPA.playVideo(data.previousVideoId);
                    }
                }
            } catch (e) { console.error('Failed to load previous episode', e); }
        }

        _showLoading(msg) { if (window.Toast) window.Toast.info(msg); }
        _hideLoading() { 
            const container = document.getElementById('toast-container');
            if (container) container.querySelectorAll('.toast.info').forEach(t => t.remove());
        }

        setAudioTrack(trackId) {
            console.log('[SimplePlayer] Setting audio track:', trackId);

            // For direct stream mode, restart stream with new audio track
            // "default" means don't send audioTrack param (or send -1)
            if (!this.hlsInstance) {
                if (trackId === 'default') {
                    this.switchAudioTrack(-1); // -1 = default, no specific track
                    return;
                }
                let trackIndex = parseInt(trackId);
                if (isNaN(trackIndex)) {
                    // Try to find track by id
                    const track = window.availableAudioTracks?.find(t => t.id == trackId);
                    trackIndex = track ? (track.trackIndex ?? 0) : 0;
                }
                this.switchAudioTrack(trackIndex);
                return;
            }

            if (this.hlsInstance) {
                if (trackId === 'default') {
                    this.hlsInstance.audioTrack = -1; // HLS default
                } else {
                    const tracks = this.hlsInstance.audioTracks;
                    const index = parseInt(trackId);
                    if (!isNaN(index) && index >= 0 && index < tracks.length) {
                        this.hlsInstance.audioTrack = index;
                    }
                }
            } else if (this.video.audioTracks) {
                // Native audio tracks support (Safari, some others)
                for (let i = 0; i < this.video.audioTracks.length; i++) {
                    this.video.audioTracks[i].enabled = (trackId === 'default' ? (i === 0) : (i.toString() === trackId));
                }
            }
        }

        getAudioTracks() {
            if (this.hlsInstance) {
                return this.hlsInstance.audioTracks.map((t, index) => ({
                    id: index.toString(),
                    languageCode: t.lang,
                    languageName: t.name,
                    displayName: t.name,
                    isActive: true
                }));
            } else if (this.video.audioTracks) {
                const tracks = [];
                for (let i = 0; i < this.video.audioTracks.length; i++) {
                    const t = this.video.audioTracks[i];
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

        switchSettingsPage(page) {
            this.subtitleMenu.querySelectorAll('.settings-page').forEach(p => p.classList.remove('active'));
            const target = this.subtitleMenu.querySelector(`.settings-page[data-page="${page}"]`);
            if (target) target.classList.add('active');
        }

        clearStreamErrorHandlers() {
            [this._seekErrorHandler, this._setupStreamErrorHandler, this._hlsErrorHandler].forEach(h => {
                if (h) this.video.removeEventListener('error', h);
            });
            this._seekErrorHandler = this._setupStreamErrorHandler = this._hlsErrorHandler = null;
        }

        async cleanupHlsAsync() {
            if (this.hlsSessionId) {
                await fetch(`/api/hls/session/${this.hlsSessionId}`, { method: 'DELETE', keepalive: true }).catch(() => {});
                this.hlsSessionId = null;
            }
            if (this.hlsInstance) {
                this.hlsInstance.destroy();
                this.hlsInstance = null;
            }
            this._isNativeHls = false;
            if (this._hlsErrorHandler) {
                this.video.removeEventListener('error', this._hlsErrorHandler);
                this._hlsErrorHandler = null;
            }
            this.video.src = "";
            this.video.load();
        }

        cleanupHls() {
            this.cleanupHlsAsync(); // fire-and-forget for backward compat
        }

        async destroy() {
            this._destroyed = true;
            
            // Remove pagehide/beforeunload listeners to prevent double-cleanup
            if (this._cleanupOnUnload) {
                window.removeEventListener('pagehide', this._cleanupOnUnload);
                window.removeEventListener('beforeunload', this._cleanupOnUnload);
                this._cleanupOnUnload = null;
            }
            
            this.clearStreamErrorHandlers();
            this.destroyAssSubtitle();
            clearInterval(this._prog);
            this.setMusicSuspended(false);
            await this.cleanupHlsAsync();
            this.video.pause();
            this.video.src = "";
            window.removeEventListener('keydown', this._boundKeydown);
            if (window.player === this) delete window.player;
            if (window.currentPlayerInstance === this) delete window.currentPlayerInstance;
        }
    };
}
