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
            this.profileId = localStorage.getItem('activeProfileId') || '1';
            this.volumeKey = 'jmedia_video_volume_' + this.profileId;
            this.muteKey = 'jmedia_video_mute_' + this.profileId;
            this.userActiveTimeout = null;
            
            // Critical: Duration parsing
            const rawDur = parseFloat(this.container.dataset.duration || 0);
            this.totalDuration = rawDur > 5000 ? rawDur / 1000 : rawDur; 
            
            this.state = {
                playing: false,
                volume: parseFloat(localStorage.getItem(this.volumeKey) || '0.7'),
                muted: localStorage.getItem(this.muteKey) === 'true',
                lastSeekTime: 0
            };

            this.markers = {
                introStart: parseFloat(this.container.dataset.introStart || 0),
                introEnd: parseFloat(this.container.dataset.introEnd || 0),
                outroStart: parseFloat(this.container.dataset.outroStart || 0),
                outroEnd: parseFloat(this.container.dataset.outroEnd || 0),
                recapStart: parseFloat(this.container.dataset.recapStart || 0),
                recapEnd: parseFloat(this.container.dataset.recapEnd || 0)
            };
            
            this.storyboard = { metadata: null, loaded: false };
            this.lastSelectedTrackId = localStorage.getItem('jmedia_last_track_' + this.videoId) || null;
            
            window.currentPlayerInstance = this;
            window.player = this;

            console.log(`[SimplePlayer] Initialized for video ${this.videoId}. Resume time from server: ${this.container.dataset.startTime}s`);
            this.initialResumeTime = parseFloat(this.container.dataset.startTime || 0);

            // Save progress on page close/refresh
            window.onbeforeunload = () => {
                this.saveProgressNow();
            };

            this.init();

            // Restore fullscreen if navigation occurred while in fullscreen
            if (sessionStorage.getItem('jmedia_restore_fullscreen') === 'true') {
                sessionStorage.removeItem('jmedia_restore_fullscreen');
                setTimeout(() => {
                    const isFullscreen = document.fullscreenElement || document.webkitFullscreenElement;        
                    if (this.container && !isFullscreen) {
                        if (this.container.requestFullscreen) this.container.requestFullscreen().catch(() => {});
                        else if (this.container.webkitEnterFullscreen) this.container.webkitEnterFullscreen();
                    }
                }, 500);
            }
        }

        async initHlsStream(savedTime) {
            // Session is now per video - no time bucket needed
            // One transcoding serves the entire video
            const sessionId = `vid-${this.videoId}`;
            const playlistUrl = `/api/video/hls/${sessionId}/playlist.m3u8`;
            this.hlsSessionId = sessionId;

            const isIOS = /iPhone|iPad|iPod|iPadOS/i.test(navigator.userAgent) || 
                         (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
            
            const isFirefox = /Firefox/i.test(navigator.userAgent);
            const isSafari = /Safari/i.test(navigator.userAgent) && !/Chrome/i.test(navigator.userAgent);
            
            // hls.js is needed for Firefox - native HLS doesn't work
            // Also use hls.js for Chrome/Edge unless it's a direct stream
            const useHlsJs = !isSafari && typeof Hls !== 'undefined' && Hls.isSupported();
            
            // Only native HLS on Safari (desktop) and iOS
            const supportsNativeHls = (isSafari || isIOS);

            this._showLoading('Preparing video...');

            if (supportsNativeHls) {
                console.log('[SimplePlayer] Using native HLS (Safari/iOS)');
                this.video.src = playlistUrl;
                this.video.addEventListener('loadedmetadata', () => {
                    if (savedTime > 0) this.video.currentTime = savedTime;
                    this.applyInitialState();
                    setTimeout(() => this.loadSubtitles(), 500);
                }, { once: true });
                this.video.play().catch(() => console.log('[SimplePlayer] User gesture required'));
            } else if (useHlsJs) {
                console.log('[SimplePlayer] Using hls.js (Firefox/Chrome)');
                const hls = new Hls({
                    enableWorker: true,
                    lowLatencyMode: false,
                    // Keep polling for new segments while transcoding
                    manifestLoadingMaxRetry: 10,
                    manifestLoadingRetryDelay: 1000,
                    manifestLoadingMaxRetryTimeout: 10000,
                    fragLoadingMaxRetry: 10,
                    fragLoadingMaxRetryTimeout: 10000
                });
                this.hlsInstance = hls;
                hls.loadSource(playlistUrl);
                hls.attachMedia(this.video);
                
                // Wait for at least some segments before playing
                hls.on(Hls.Events.MANIFEST_PARSED, () => {
                    console.log('[SimplePlayer] HLS manifest parsed, waiting for buffer...');
                    // Give FFmpeg time to generate initial segments
                    setTimeout(() => {
                        console.log('[SimplePlayer] Starting playback');
                        if (savedTime > 0) this.video.currentTime = savedTime;
                        this.video.play().catch(console.error);
                        setTimeout(() => this.loadSubtitles(), 500);
                    }, 2000);
                });
                
                hls.on(Hls.Events.ERROR, (event, data) => {
                    console.error('[SimplePlayer] HLS error:', data);
                    
                    // For sequence mismatch errors, fully reset and reload
                    if (data.fatal && data.details && data.details.includes('mismatch')) {
                        console.log('[SimplePlayer] Sequence mismatch - resetting and reloading');
                        data.fatal = false;
                        hls.reset();
                        setTimeout(() => hls.loadSource(playlistUrl), 100);
                        return;
                    }
                    
                    // Don't fail on network or parsing errors - keep retrying
                    if (data.type === Hls.ErrorTypes.NETWORK_ERROR || data.type === Hls.ErrorTypes.MEDIA_ERROR) {
                        console.log('[SimplePlayer] Non-fatal error, will retry...');
                        hls.startLoad();
                    }
                });
                
                // Track buffer health
                hls.on(Hls.Events.FRAG_BUFFERED, () => {
                    console.log('[SimplePlayer] Fragment buffered');
                });
            } else {
                console.error('[SimplePlayer] No HLS support - cannot play video');
                this._showLoading('HLS not supported on this browser');
            }

            this.video.addEventListener('playing', () => this._hideLoading(), { once: true });
        }

        init() {
            this.buildUI();
            this.attachEvents();
            this.applyInitialState();
            
            const savedTime = parseFloat(this.container.dataset.startTime || 0);
            const isIOS = /iPhone|iPad|iPod/i.test(navigator.userAgent) || 
                         (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
            
            // Use HLS for iOS when transcoding is needed (HEVC) - NEVER fall back to MP4
            // All others → Direct remuxed stream
            if (isIOS && this.needsTranscode) {
                console.log('[SimplePlayer] iOS + transcode detected → using HLS');
                this.initHlsStream(savedTime);
            } else {
                // Direct stream - with start param if needsTranscode and resuming
                if (this.needsTranscode && savedTime > 0) {
                    console.log(`[SimplePlayer] Transcode needed, resuming from ${savedTime}s via server-side seek`);
                    this.streamStartOffset = savedTime; // Track where stream actually starts
                    this.video.src = `/api/video/stream/${this.videoId}?start=${savedTime}`;
                } else {
                    this.streamStartOffset = 0;
                    this.video.src = `/api/video/stream/${this.videoId}`;
                }
                this.loadSubtitles();
            }

            this.loadStoryboard();
            this.setMusicSuspended(true);
            this.startProgressReporting();
            this.updateMarkers();
            this.checkMarkers();
            
            if (window.subtitleManager) {
                window.subtitleManager.bindVideo(this.video, this.container);
            }

            // Auto-refresh markers for episodes if they are currently all zero
            const allZero = Object.values(this.markers).every(v => v === 0);
            if (allZero && (this.videoType === 'Episode' || this.videoType === 'episode')) {
                this.refreshMarkers();
            }
            
            this.showControls();
        }

        async refreshMarkers(retries = 3) {
            console.log('[SimplePlayer] Refreshing markers for episode...');
            try {
                const res = await fetch(`/api/video/${this.videoId}`);
                if (res.ok) {
                    const json = await res.json();
                    const data = json.data || json;
                    
                    if (data.introStart !== undefined) {
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
                this._hideLoading();
            }, { once: true });
            
            this.video.addEventListener('error', (e) => {
                console.error('[SimplePlayer] Direct stream error:', this.video.error);
                this._showLoading('Stream error, trying HLS...');
                // Fallback to HLS on error
                setTimeout(() => this.initHlsStream(savedTime), 1000);
            });
            
            this.video.play().catch(e => {
                console.log('[SimplePlayer] Play requires user gesture:', e);
            });
        }

        buildUI() {
            const uiHTML = `
                <div class="video-click-overlay"></div>
                <div class="big-play-btn"><img src="/logo.png" alt="Play"></div>
                <div class="buffering-overlay"><i class="pi pi-spin pi-spinner" style="font-size: 3rem; color: #48c774;"></i></div>
                
                <div class="skip-recap-container" id="skipRecapBtn" style="display: none;"><button class="button is-info is-rounded"><i class="pi pi-history mr-2"></i> Skip Recap</button></div>
                <div class="skip-intro-container" id="skipIntroBtn" style="display: none;"><button class="button is-info is-rounded"><i class="pi pi-fast-forward mr-2"></i> Skip Intro</button></div>
                <div class="skip-outro-container" id="skipOutroBtn" style="display: none;"><button class="button is-info is-rounded"><i class="pi pi-step-forward mr-2"></i> Skip Outro</button></div>

                <div class="media-info">
                    <div class="back-button-container"><button class="back-btn" id="videoBackBtn"><i class="pi pi-arrow-left"></i></button></div>
                    <div class="info-title" id="videoTitleLink">${this.container.dataset.title || 'Video'}</div>
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
                        
                        <div class="volume-container">
                            <button class="control-btn" id="videoMuteBtn"><i class="pi pi-volume-up"></i></button>
                            <input type="range" class="volume-slider" min="0" max="1" step="0.01" value="${this.state.volume}">
                        </div>
                        
                        <button class="control-btn" id="videoSpeedBtn"><span id="speedValue">1.0x</span></button>
                        <button class="control-btn" id="videoSubtitleBtn"><i class="pi pi-comments"></i></button>
                        <button class="control-btn" id="videoFullscreenBtn"><i class="pi pi-expand"></i></button>
                    </div>
                </div>

                <div class="subtitle-menu" id="subtitleMenu">
                    <div class="menu-header">Subtitles</div>
                    <div class="subtitle-list" id="subtitleList">
                        <div class="subtitle-option" id="sub-off" data-id="off">Off</div>
                    </div>
                    
                    <div class="menu-divider"></div>
                    <div class="menu-header" style="font-size: 0.7rem; opacity: 0.6; padding-top: 5px;">Timing Offset</div>
                    <div class="subtitle-correction-row">
                        <button class="correction-btn" id="subMinusBtn">-0.2s</button>
                        <div class="correction-val" id="subCorrectionVal">0.0s</div>
                        <button class="correction-btn" id="subPlusBtn">+0.2s</button>
                    </div>

                    <div class="menu-divider"></div>
                    <div class="subtitle-option" id="manageSubtitlesBtn"><i class="pi pi-cog"></i> Manage Subtitles</div>
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
            this.buffering = this.container.querySelector('.buffering-overlay');
            this.backBtn = this.container.querySelector('#videoBackBtn');
            this.clickOverlay = this.container.querySelector('.video-click-overlay');
            this.prevBtn = this.container.querySelector('#videoPrevBtn');
            this.nextBtn = this.container.querySelector('#videoNextBtn');
            this.preview = this.container.querySelector('#scrollPreview');
            this.previewImg = this.container.querySelector('#storyboardImg');
            this.previewTime = this.container.querySelector('#previewTime');
            this.subtitleList = this.container.querySelector('#subtitleList');
            
            // Load last selected subtitle track
            this.lastSelectedTrackId = localStorage.getItem('jmedia_last_track_' + this.videoId);
            
            // Off button
            const offBtn = this.container.querySelector('#sub-off');
            if (offBtn) {
                offBtn.onclick = (e) => { e.stopPropagation(); this.selectSubtitle('off', offBtn); };
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
                const displayTime = this.video.currentTime + (this.streamStartOffset || 0);
                const pct = (displayTime / dur) * 100;
                this.progressBar.style.width = Math.min(100, pct) + '%';
                this.timeCurrent.innerText = this.formatTime(displayTime);
                this.checkMarkers();
            });

            this.video.addEventListener('loadedmetadata', () => {
                const dur = this.video.duration;
                console.log(`[SimplePlayer] Metadata loaded. Stream duration: ${dur}s, DB duration: ${this.totalDuration}s`);
                
                // CRITICAL: Only trust stream duration if it's larger than what we have
                // HLS often reports only the buffered duration initially.
                if (dur && dur !== Infinity && dur > this.totalDuration) {
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

            this.video.onwaiting = () => this.buffering.style.display = 'block';
            this.video.onplaying = () => this.buffering.style.display = 'none';
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
                        this.video.currentTime = newTime;
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
                    this.video.currentTime = seekTo;
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
            };

            this.container.querySelector('#manageSubtitlesBtn').onclick = (e) => {
                e.stopPropagation();
                this.subtitleMenu.classList.remove('active');
                if (window.subtitleManager) {
                    window.subtitleManager.openModal(this.videoId, this.container.dataset.title, this.container.dataset.path);
                }
            };

            this.container.querySelector('#subMinusBtn').onclick = (e) => {
                e.stopPropagation();
                if (window.subtitleManager) window.subtitleManager.adjustCorrection(-0.2);
            };

            this.container.querySelector('#subPlusBtn').onclick = (e) => {
                e.stopPropagation();
                if (window.subtitleManager) window.subtitleManager.adjustCorrection(0.2);
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
                const isIOS = /iPad|iPhone|iPod|iPadOS/i.test(navigator.userAgent) ||
                              (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1) ||
                              (navigator.platform === 'iPhone' || navigator.platform === 'iPad');

                // Check current fullscreen state
                const isNativeFullscreen = !!(document.fullscreenElement || document.webkitFullscreenElement);
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
                    // iOS: First try native video fullscreen
                    let nativeFullscreenAttempted = false;
                    
                    // Ensure playsinline is set (required for programmatic fullscreen on iOS)
                    if (!this.video.hasAttribute('playsinline')) {
                        this.video.setAttribute('playsinline', 'true');
                        this.video.setAttribute('webkit-playsinline', 'true');
                    }
                    
                    // Try native iOS fullscreen
                    if (this.video.webkitEnterFullscreen) {
                        try {
                            this.video.webkitEnterFullscreen();
                            nativeFullscreenAttempted = true;
                        } catch (err) {
                            console.log('[SimplePlayer] webkitEnterFullscreen failed:', err);
                        }
                    } else if (this.video.webkitSupportsFullscreen && this.video.webkitEnterFullScreen) {
                        try {
                            this.video.webkitEnterFullScreen();
                            nativeFullscreenAttempted = true;
                        } catch (err) {
                            console.log('[SimplePlayer] webkitEnterFullScreen failed:', err);
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

                // Re-trigger subtitle lift update if needed
                if (this.controlsVisible) this.showControls();
            };

            document.addEventListener('fullscreenchange', onFullscreenChange);
            document.addEventListener('webkitfullscreenchange', onFullscreenChange);
            
            // Handle iOS-specific video fullscreen end event
            this.video.addEventListener('webkitendfullscreen', () => {
                console.log('[SimplePlayer] iOS video fullscreen ended');
                this.container.classList.remove('is-fullscreen');
                this.updateFullscreenButtonState(false);
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
            this.container.querySelector('#skipIntroBtn').onclick = () => {
                if (this.needsTranscode && !this.hlsSessionId) {
                    this.performServerSeek(this.markers.introEnd);
                } else {
                    this.video.currentTime = this.markers.introEnd;
                }
            };
            this.container.querySelector('#skipRecapBtn').onclick = () => {
                if (this.needsTranscode && !this.hlsSessionId) {
                    this.performServerSeek(this.markers.recapEnd);
                } else {
                    this.video.currentTime = this.markers.recapEnd;
                }
            };
            this.container.querySelector('#skipOutroBtn').onclick = () => this.goBack();

            // Auto-hide controls
            this.container.onmousemove = () => this.showControls();
            this.container.ontouchstart = () => this.showControls();
            
            // Keyboard shortcuts
            window.addEventListener('keydown', this.handleKeydown.bind(this));
        }

        handleKeydown(e) {
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
                if (el) el.style.display = visible ? 'block' : 'none';
            };
            show('skipIntroBtn', t >= this.markers.introStart && t < this.markers.introEnd);
            show('skipRecapBtn', t >= this.markers.recapStart && t < this.markers.recapEnd);
            show('skipOutroBtn', t >= this.markers.outroStart && this.markers.outroStart > 0);
        }

        turnOffSubtitles() {
            console.log('[SimplePlayer] Turning off subtitles');
            // Disable ALL existing tracks first
            this.video.querySelectorAll('track').forEach(el => {
                el.track.mode = 'hidden';
                el.remove();
            });
            // Also clear textTracks
            if (this.video.textTracks) {
                for (let i = 0; i < this.video.textTracks.length; i++) {
                    this.video.textTracks[i].mode = 'hidden';
                }
            }
        }

        async loadSubtitles() {
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
                        
                        // Disable ALL existing tracks first
                        this.video.querySelectorAll('track').forEach(el => {
                            el.track.mode = 'hidden';
                            el.remove();
                        });
                        // Also clear textTracks
                        if (this.video.textTracks) {
                            for (let i = 0; i < this.video.textTracks.length; i++) {
                                this.video.textTracks[i].mode = 'hidden';
                            }
                        }
                        
                        if (t.id !== 'off') {
                            const track = document.createElement('track');
                            track.kind = 'subtitles';
                            
                            // Get correction from localStorage
                            const correction = localStorage.getItem('jmedia_subtitle_correction') || 0;
                            
                            // Build URL with start offset for server-side seek
                            // When resuming from a saved position via server-side seek, video.currentTime
                            // starts at 0 (beginning of stream), but display time is adjusted via
                            // streamStartOffset. We must shift subtitle timestamps to match.
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
                                
                                // Find by matching label or by finding the newly added track
                                let textTrack = tracks.find(tr => tr.label === (t.displayName || 'Subtitle'));
                                
                                // Also try to find by matching src if available
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
                                                    overlay.textContent = activeCues[0].text;
                                                    overlay.classList.add('active');
                                                } else {
                                                    overlay.classList.remove('active');
                                                }
                                            }
                                        }
                                    };

                                    textTrack.oncuechange = (e) => {
                                        console.log('[SimplePlayer] CUE CHANGE - activeCues:', textTrack.activeCues?.length);
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
                            
                            // Also handle load/error events on the track element
                            track.addEventListener('load', () => {
                                console.log('[SimplePlayer] Track element loaded');
                                setTimeout(setupTrack, 100);
                            });
                            
                            track.addEventListener('error', (e) => {
                                console.error('[SimplePlayer] Track load error:', e);
                            });
                            
                            // Initial attempt after a delay
                            setTimeout(setupTrack, 500);
                        } else {
                            console.log('[SimplePlayer] Subtitles turned OFF');
                        }
this.container.querySelectorAll('.subtitle-option').forEach(el => el.classList.remove('active'));
                        opt.classList.add('active');
                        this.subtitleMenu.classList.remove('active');
                        localStorage.setItem('jmedia_last_track_' + this.videoId, t.id);
                        
                        // Store reference to current track for seeking updates
                        this.currentSubtitleTrackId = t.id;
                    };
                    list.appendChild(opt);
                    if (this.lastSelectedTrackId == t.id) opt.click();
                });
                
                // If no track was auto-clicked, click 'Off' by default
                if (this.lastSelectedTrackId == null && list.querySelector('#sub-off')) {
                    list.querySelector('#sub-off').click();
                }
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
                    const displayTime = this.video.currentTime + (this.streamStartOffset || 0);
                    // Use the more comprehensive playback progress endpoint
                    const url = `/api/video/playback/progress?videoId=${this.videoId}&time=${displayTime}&playing=${!this.video.paused}`;
                    fetch(url, { 
                        method: 'POST',
                        credentials: 'include',
                        headers: { 'X-User-ID': this.profileId }
                    }).catch(err => console.error('[SimplePlayer] Progress report failed:', err));
                }
            }, 5000);

            window.addEventListener('beforeunload', () => this.saveProgressNow());
            document.addEventListener('visibilitychange', () => {
                if (document.visibilityState === 'hidden') this.saveProgressNow();
            });
        }

        saveProgressNow() {
            if (this.video.currentTime > 0 || this.streamStartOffset > 0) {
                const displayTime = this.video.currentTime + (this.streamStartOffset || 0);
                // Use the comprehensive endpoint here too
                const url = `/api/video/playback/progress?videoId=${this.videoId}&time=${displayTime}&playing=${!this.video.paused}`;
                
                // CRITICAL: sendBeacon cannot send custom headers like X-User-ID.
                // We MUST use fetch with keepalive: true to ensure headers are sent even when page is closing.
                fetch(url, { 
                    method: 'POST', 
                    keepalive: true,
                    credentials: 'include',
                    headers: { 'X-User-ID': this.profileId }
                }).catch(() => {});
            }
        }

        performServerSeek(time) {
            console.log(`[SimplePlayer] Performing server-side seek to ${time}s`);
            
            // For HLS streams, seeking is native — just set currentTime
            if (this.hlsSessionId) {
                console.log('[SimplePlayer] HLS stream — using native seek');
                this.video.currentTime = time;
                return;
            }
            
            // Show loading indicator during seek
            if (this.buffering) this.buffering.style.display = 'block';
            
            // Save current progress before seek
            this.saveProgressNow();
            
            // Explicitly pause and clear src to trigger a clean disconnect on the server before restarting
            this.video.pause();
            this.video.src = "";
            this.video.load();

            this.streamStartOffset = time;
            this.video.src = `/api/video/stream/${this.videoId}?start=${time}`;
            
            // Wait for enough buffer before playing (prevents choppiness)
            const playWhenReady = () => {
                // Check if we have enough buffer (readyState >= 3 = HAVE_FUTURE_DATA)
                if (this.video.readyState >= 3) {
                    console.log('[SimplePlayer] Buffer ready, starting playback');
                    this.video.play().catch(e => console.log('[SimplePlayer] Play after seek failed:', e));
                    this.video.removeEventListener('canplay', playWhenReady);
                    this.video.removeEventListener('progress', checkBuffer);
                }
            };
            
            const checkBuffer = () => {
                // Check if we have at least 2 seconds buffered
                if (this.video.buffered.length > 0) {
                    const bufferedEnd = this.video.buffered.end(this.video.buffered.length - 1);
                    const bufferedDuration = bufferedEnd - this.video.currentTime;
                    if (bufferedDuration >= 2 || this.video.readyState >= 3) {
                        playWhenReady();
                    }
                }
            };
            
            // Listen for buffer events
            this.video.addEventListener('canplay', playWhenReady, { once: true });
            this.video.addEventListener('progress', checkBuffer);
            
            // Fallback: start playing after timeout if buffer check doesn't fire
            setTimeout(() => {
                if (this.buffering && this.buffering.style.display !== 'none') {
                    console.log('[SimplePlayer] Fallback: forcing playback after timeout');
                    this.video.play().catch(e => console.log('[SimplePlayer] Fallback play failed:', e));
                }
            }, 3000);
            
            // Refresh subtitles if one is selected to ensure it gets the correct start offset
            if (this.lastSelectedTrackId && this.lastSelectedTrackId !== 'off') {
                const activeOpt = this.subtitleList?.querySelector(`.subtitle-option[data-id="${this.lastSelectedTrackId}"]`);
                if (activeOpt) {
                    activeOpt.click();
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

        async playNextEpisode() {
            // Try to get next episode from series
            try {
                const res = await fetch(`/api/video/playback/next/${this.videoId}`);
                if (res.ok) {
                    const data = await res.json();
                    if (data.nextVideoId) {
                        if (window.videoSPA) {
                            window.videoSPA.switchSection('playback', { videoId: data.nextVideoId });
                        }
                    }
                }
            } catch (e) { console.error('Failed to load next episode', e); }
        }

        async playPreviousEpisode() {
            // Try to get previous episode from series
            try {
                const res = await fetch(`/api/video/playback/previous/${this.videoId}`);
                if (res.ok) {
                    const data = await res.json();
                    if (data.previousVideoId) {
                        if (window.videoSPA) {
                            window.videoSPA.switchSection('playback', { videoId: data.previousVideoId });
                        }
                    }
                }
            } catch (e) { console.error('Failed to load previous episode', e); }
        }

        _showLoading(msg) { if (window.Toast) window.Toast.info(msg); }
        _hideLoading() { 
            const container = document.getElementById('toast-container');
            if (container) container.querySelectorAll('.toast.info').forEach(t => t.remove());
        }

        destroy() {
            clearInterval(this._prog);
            this.setMusicSuspended(false);
            if (this.hlsInstance) this.hlsInstance.destroy();
            this.video.pause();
            this.video.src = "";
            window.removeEventListener('keydown', this.handleKeydown);
        }
    };
}
