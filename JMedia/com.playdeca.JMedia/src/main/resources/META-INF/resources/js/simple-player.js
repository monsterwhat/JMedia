class SimplePlayer {
    constructor(config) {
        console.log('[SimplePlayer] Initializing...', config);
        this.container = document.getElementById(config.containerId);
        this.video = document.getElementById(config.videoId);
        this.videoId = config.currentVideoId;
        
        if (!this.container || !this.video) {
            console.error('[SimplePlayer] Container or Video element not found!');
            return;
        }

        this.profileId = localStorage.getItem('activeProfileId') || '1';
        this.volumeKey = 'jmedia_video_volume_' + this.profileId;
        this.muteKey = 'jmedia_video_mute_' + this.profileId;
        this.userActiveTimeout = null;
        
        const savedVol = localStorage.getItem(this.volumeKey);
        const savedMute = localStorage.getItem(this.muteKey) === 'true';
        
        this.state = {
            playing: false,
            volume: savedVol !== null ? parseFloat(savedVol) : 0.7,
            muted: savedMute,
            duration: 0,
            currentTime: 0
        };

        this.streamStartOffset = 0;
        this.currentSpeed = 1.0;
        this.controlBarHeight = 80; // Default, will be measured dynamically
        this.lastSelectedTrackId = localStorage.getItem('jmedia_last_track_' + this.videoId) || null;
        this.storyboard = {
            metadata: null,
            loaded: false
        };

        // Intro/Outro markers
        this.markers = {
            introStart: parseFloat(this.container.dataset.introStart || 0),
            introEnd: parseFloat(this.container.dataset.introEnd || 0),
            outroStart: parseFloat(this.container.dataset.outroStart || 0),
            outroEnd: parseFloat(this.container.dataset.outroEnd || 0),
            recapStart: parseFloat(this.container.dataset.recapStart || 0),
            recapEnd: parseFloat(this.container.dataset.recapEnd || 0)
        };
        
        console.log('[SimplePlayer] Loaded Markers:', this.markers);

        // If markers are missing, try to refresh them once after a short delay (for on-demand enrichment)
        if (this.markers.introEnd === 0 && this.markers.outroStart === 0 && this.markers.recapEnd === 0) {
            console.log('[SimplePlayer] Markers missing, scheduling a refresh in 5 seconds...');
            setTimeout(() => this.refreshMarkers(), 5000);
        }
        
        window.currentPlayerInstance = this;
        // Compatibility with existing subtitle components (subtitleTrackSelector, etc)
        window.player = this;

        this.init();

        // Restore fullscreen if it was set before episode transition
        if (sessionStorage.getItem('jmedia_restore_fullscreen') === 'true') {
            sessionStorage.removeItem('jmedia_restore_fullscreen');
            setTimeout(() => {
                const isFullscreen = document.fullscreenElement || document.webkitFullscreenElement;
                if (this.container && !isFullscreen) {
                    if (this.container.requestFullscreen) {
                        this.container.requestFullscreen().catch(err => {
                            console.log('[SimplePlayer] Could not restore fullscreen:', err.message);
                        });
                    } else if (this.container.webkitEnterFullscreen) {
                        this.container.webkitEnterFullscreen();
                    }
                }
            }, 500);
        }
    }

    // Compatibility methods for other components (subtitleTrackSelector, etc)
    textTracks() {
        return this.video.textTracks;
    }

    updateSubtitleUI() {
        console.log('[SimplePlayer] updateSubtitleUI called');
        // This is a placeholder for components that expect this method
    }

    addRemoteTextTrack(trackObj, manualActivation = true) {
        console.log('[SimplePlayer] Adding remote text track:', trackObj);

        const trackEl = document.createElement('track');
        trackEl.kind = trackObj.kind || 'subtitles';
        trackEl.label = trackObj.label;
        trackEl.srclang = trackObj.language;
        trackEl.src = trackObj.src;
        trackEl.default = trackObj.default || false;

        this.video.appendChild(trackEl);

        if (manualActivation) {
            trackEl.onload = () => {
                // Give some browsers more time to register the track object
                setTimeout(() => {
                    if (trackEl.track) trackEl.track.mode = 'showing';
                    this.updateSubtitleUI(); // Refresh UI to show the new track
                }, 100);
            };
            // Fallback for immediate activation attempt
            if (trackEl.track) {
                setTimeout(() => { trackEl.track.mode = 'showing'; }, 100);
            }
        }

        return trackEl;
    }

    async refreshMarkers() {
        console.log('[SimplePlayer] Refreshing markers from server...');
        try {
            const res = await fetch(`/api/video/${this.videoId}`);
            const json = await res.json();
            // VideoAPI returns ApiResponse { success: true, data: video }
            if (json && json.success && json.data) {
                const video = json.data;
                this.markers.introStart = parseFloat(video.introStart || 0);
                this.markers.introEnd = parseFloat(video.introEnd || 0);
                this.markers.outroStart = parseFloat(video.outroStart || 0);
                this.markers.outroEnd = parseFloat(video.outroEnd || 0);
                this.markers.recapStart = parseFloat(video.recapStart || 0);
                this.markers.recapEnd = parseFloat(video.recapEnd || 0);
                console.log('[SimplePlayer] Markers updated from server:', this.markers);

                // Update progress markers after refresh
                const isMKV = (this.container.dataset.path || '').toLowerCase().endsWith('.mkv');
                const manualDuration = parseFloat(this.container.dataset.duration || 0) / 1000;
                const duration = (isMKV && manualDuration > 0) ? manualDuration : 
                               (this.video.duration && isFinite(this.video.duration) && this.video.duration > 0 ? this.video.duration : manualDuration);
                this.updateProgressMarkers(duration);
            }
        } catch (e) {
            console.error('[SimplePlayer] Failed to refresh markers:', e);
        }
    }

    updateProgressMarkers(duration) {
        if (!this.progressIntroMarker || !this.progressOutroMarker || !duration || duration <= 0) return;

        // Intro marker (from introStart to introEnd)
        if (this.markers.introStart > 0 && this.markers.introEnd > 0) {
            const introStartPct = (this.markers.introStart / duration) * 100;
            const introEndPct = (this.markers.introEnd / duration) * 100;
            this.progressIntroMarker.style.left = introStartPct + '%';
            this.progressIntroMarker.style.width = (introEndPct - introStartPct) + '%';
            this.progressIntroMarker.style.display = 'block';
        } else {
            this.progressIntroMarker.style.display = 'none';
        }

        // Outro marker (from outroStart to end)
        if (this.markers.outroStart > 0) {
            const outroStartPct = (this.markers.outroStart / duration) * 100;
            this.progressOutroMarker.style.left = outroStartPct + '%';
            this.progressOutroMarker.style.width = (100 - outroStartPct) + '%';
            this.progressOutroMarker.style.display = 'block';
        } else {
            this.progressOutroMarker.style.display = 'none';
        }
    }

    init() {
        console.log('[SimplePlayer] Init called');

        // Move the global subtitle modal into this container so it shows in fullscreen
        const globalModal = document.getElementById('subtitleManagementModal');
        if (globalModal && this.container) {
            this.container.appendChild(globalModal);
        }

        this.buildUI();
        this.attachEvents();
        this.measureControlBarHeight(); // Measure initial control bar height
        this.setPlaybackSpeed(this.currentSpeed);
        
        // Update control bar height on resize
        window.addEventListener('resize', () => this.measureControlBarHeight());
        
        // Add a small delay before loading storyboard to avoid network rush
        setTimeout(() => this.loadStoryboard(), 1000);
        
        // Restore Audio
        this.video.volume = Math.pow(this.state.volume, 2);
        this.video.muted = this.state.muted;
        this.updateVolumeUI();
        
        // Setup initial source
        const isMKV = (this.container.dataset.path || '').toLowerCase().endsWith('.mkv');
        const savedTime = parseFloat(this.container.dataset.startTime || 0);
        const isIOS = /iPhone|iPad|iPod/i.test(navigator.userAgent);
        
        if (isMKV && savedTime > 0 && !isIOS) {
            console.log(`[SimplePlayer] MKV detected, resuming from ${savedTime}s via server-side seek`);
            this.streamStartOffset = savedTime;
            this.video.src = `/api/video/stream/${this.videoId}?start=${savedTime}`;
        } else {
            this.streamStartOffset = 0;
            this.video.src = `/api/video/stream/${this.videoId}`;
            if (savedTime > 0) {
                this.video.currentTime = savedTime;
            }
        }

        // Load subtitles AFTER setting streamStartOffset for proper sync
        this.loadSubtitles();

        // Ensure Firefox subtitle overlay exists
        if (window.subtitleManager?.ensureFirefoxOverlay) {
            window.subtitleManager.ensureFirefoxOverlay();
        }

        this.showControls();

        // Initial check for autoplay or already playing state
        if (!this.video.paused) {
            console.log('[SimplePlayer] Video playing on init (autoplay), suspending music');
            this.setMusicSuspended(true);
            this.startProgressReporting();
        }
    }

    startProgressReporting() {
        clearInterval(this._progressInterval);
        this._progressInterval = setInterval(() => this.reportProgress(true), 3000);
    }

    stopProgressReporting() {
        clearInterval(this._progressInterval);
        // Final report when stopping
        this.reportProgress(true);
    }

    setMusicSuspended(suspended) {
        if (typeof window.setVideoPlaying === 'function') {
            window.setVideoPlaying(suspended);
        } else {
            // Fallback if musicBar isn't ready
            window.videoPlaying = suspended;
            const player = document.getElementById('musicPlayerContainer');
            if (player) {
                player.style.display = suspended ? 'none' : 'flex';
            }
        }
    }

    buildUI() {
        const old = this.container.querySelectorAll('.controls-container, .media-info, .big-play-btn, .subtitle-menu, .buffering-overlay');
        old.forEach(el => el.remove());

        const type = (this.container.dataset.type || '').toLowerCase();
        const isEpisode = type === 'episode';
        const nextId = this.container.dataset.nextId;
        const hasNext = (nextId && nextId !== 'null' && nextId !== '');

        console.log('[SimplePlayer] buildUI Metadata:', { type, isEpisode, nextId, hasNext });

        const uiHTML = `
            <div class="big-play-btn"><img src="/logo.png" alt="Play"></div>
            <div class="buffering-overlay"><i class="pi pi-spin pi-spinner" style="font-size: 3rem; color: #48c774;"></i></div>

            <div class="skip-recap-container" id="skipRecapBtn" style="display: none;">
                <button class="button is-info is-rounded">
                    <i class="pi pi-history mr-2"></i> Skip Recap
                </button>
            </div>

            <div class="skip-intro-container" id="skipIntroBtn" style="display: none;">
                <button class="button is-info is-rounded">
                    <i class="pi pi-fast-forward mr-2"></i> Skip Intro
                </button>
            </div>
            
            <div class="skip-outro-container" id="skipOutroBtn" style="display: none;">
                <button class="button is-info is-rounded">
                    <i class="pi pi-step-forward mr-2"></i> Skip Outro
                </button>
            </div>

            <div class="media-info">
                <div class="back-button-container">
                    <button class="back-btn" id="videoBackBtn" title="Go Back" onclick="if(window.currentPlayerInstance) window.currentPlayerInstance.goBack();"
                            data-type="${this.container.dataset.type || ''}"
                            data-video-id="${this.videoId}"
                            data-series-title="${this.container.dataset.seriesTitle || ''}"
                            data-season-number="${this.container.dataset.seasonNumber || 1}">
                        <i class="pi pi-arrow-left"></i>
                    </button>
                </div>
                <div class="info-title" id="videoTitleLink" style="cursor: pointer;" title="View Details" onclick="if(window.currentPlayerInstance) window.currentPlayerInstance.goToDetails();"
                     data-type="${this.container.dataset.type || ''}"
                     data-video-id="${this.videoId}"
                     data-series-title="${this.container.dataset.seriesTitle || ''}"
                     data-season-number="${this.container.dataset.seasonNumber || 1}">${this.container.dataset.title || 'Video'}</div>
                ${this.container.dataset.meta ? `<div class="info-meta" style="font-size: 0.9rem; color: rgba(255,255,255,0.7); margin-left: 10px; margin-top: 4px;">${this.container.dataset.meta}</div>` : ''}
            </div>

            <div class="controls-container">
                <div class="preview-container" id="scrollPreview">
                    <div class="preview-time" id="previewTime">0:00</div>
                </div>

                <div class="progress-container">
                    <div class="progress-buffered" style="width: 0%;"></div>
                    <div class="progress-filled" style="width: 0%;"></div>
                    <div class="progress-intro-marker" style="display: none;"></div>
                    <div class="progress-outro-marker" style="display: none;"></div>
                </div>
                
                <div class="controls-row">
                    <button class="control-btn" id="videoPlayPauseBtn"><i class="pi pi-play"></i></button>
                    
                    <div id="episodeNavControls" class="is-flex" style="${isEpisode || hasNext ? 'display: flex !important;' : 'display: none !important;'}">
                        <button class="control-btn" id="videoPrevEpBtn" title="Previous Episode" style="${isEpisode ? '' : 'display:none;'}"><i class="pi pi-step-backward"></i></button>
                        <button class="control-btn" id="videoNextEpBtn" title="Next Episode" style="${hasNext ? '' : 'visibility: hidden;'}"><i class="pi pi-step-forward"></i></button>
                    </div>

                    <div class="is-flex">
                        <button class="control-btn skip-btn" data-skip="-30" title="Skip back 30s"><i class="pi pi-angle-double-left"></i><span class="skip-val">-30</span></button>
                        <button class="control-btn skip-btn" data-skip="-15" title="Skip back 15s"><i class="pi pi-angle-left"></i><span class="skip-val">-15</span></button>
                        <button class="control-btn skip-btn" data-skip="15" title="Skip forward 15s"><i class="pi pi-angle-right"></i><span class="skip-val">+15</span></button>
                        <button class="control-btn skip-btn" data-skip="30" title="Skip forward 30s"><i class="pi pi-angle-double-right"></i><span class="skip-val">+30</span></button>
                    </div>

                    <div class="time-display">
                        <span id="videoCurrentTime">0:00</span> / <span id="videoTotalTime">0:00</span>
                    </div>

                    <div class="spacer"></div>

                    <div class="volume-container">
                        <button class="control-btn" id="videoMuteBtn"><i class="pi pi-volume-up"></i></button>
                        <input type="range" class="volume-slider" min="0" max="1" step="0.01" value="${this.state.volume}">
                    </div>

                    <button class="control-btn" id="videoSpeedBtn" title="Playback Speed"><i class="pi pi-speedometer"></i> <span id="speedValue">1.0x</span></button>

                    <button class="control-btn" id="videoSubtitleBtn"><i class="pi pi-comments"></i></button>
                    <button class="control-btn" id="videoFullscreenBtn"><i class="pi pi-expand"></i></button>
                </div>
            </div>

            <div class="subtitle-menu" id="subtitleMenu">
                <div class="menu-header">Subtitles</div>
                <div class="subtitle-list" id="subtitleList">
                    <div class="subtitle-option" id="sub-off">Off</div>
                </div>
                
                <div class="menu-divider"></div>
                <div class="subtitle-sync-section p-3">
                    <div class="is-flex is-justify-content-between is-align-items-center mb-2">
                        <span class="is-size-7">Sync Offset</span>
                        <span id="subOffsetVal" class="tag is-dark is-small">0.0s</span>
                    </div>
                    <div class="is-flex gap-2">
                        <button class="button is-small is-dark is-fullwidth" onclick="event.stopPropagation(); currentPlayerInstance.adjustSubtitleOffset(-0.5)">
                            <i class="pi pi-minus mr-1"></i> 0.5s
                        </button>
                        <button class="button is-small is-dark is-fullwidth" onclick="event.stopPropagation(); currentPlayerInstance.adjustSubtitleOffset(0.5)">
                            <i class="pi pi-plus mr-1"></i> 0.5s
                        </button>
                    </div>
                    <button class="button is-ghost is-small is-fullwidth mt-1" style="color: rgba(255,255,255,0.5)" onclick="event.stopPropagation(); currentPlayerInstance.adjustSubtitleOffset(0, true)">Reset</button>
                </div>

                <div class="menu-divider"></div>
                <div class="subtitle-actions">
                    <div class="subtitle-option action-opt" id="sub-generate"><i class="pi pi-bolt"></i> Generate (AI)</div>
                    <div class="subtitle-option action-opt" id="sub-download"><i class="pi pi-search"></i> Search (Web)</div>
                </div>
            </div>
            <div class="speed-menu" id="speedMenu">
                <div class="menu-header">Playback Speed</div>
                <div class="speed-list" id="speedList">
                    <div class="speed-option" data-speed="0.2">0.2x</div>
                    <div class="speed-option" data-speed="0.5">0.5x</div>
                    <div class="speed-option active" data-speed="1.0">1.0x</div>
                    <div class="speed-option" data-speed="1.5">1.5x</div>
                    <div class="speed-option" data-speed="2.0">2.0x</div>
                    <div class="speed-option" data-speed="3.0">3.0x</div>
                </div>
                <div class="menu-divider"></div>
                <div class="speed-actions">
                    <div class="speed-option action-opt" data-speed="1.0" id="speed-reset"><i class="pi pi-refresh mr-1"></i> Reset</div>
                </div>
            </div>
        `;

        this.container.insertAdjacentHTML('beforeend', uiHTML);

        this.playBtn = this.container.querySelector('#videoPlayPauseBtn');
        this.playIcon = this.playBtn.querySelector('i');
        this.bigPlay = this.container.querySelector('.big-play-btn');
        this.progressBar = this.container.querySelector('.progress-filled');
        this.progressContainer = this.container.querySelector('.progress-container');
        this.progressIntroMarker = this.container.querySelector('.progress-intro-marker');
        this.progressOutroMarker = this.container.querySelector('.progress-outro-marker');
        this.previewContainer = this.container.querySelector('#scrollPreview');
        this.previewTime = this.container.querySelector('#previewTime');
        this.timeCurrent = this.container.querySelector('#videoCurrentTime');
        this.timeTotal = this.container.querySelector('#videoTotalTime');
        this.volSlider = this.container.querySelector('.volume-slider');
        this.muteBtn = this.container.querySelector('#videoMuteBtn');
        this.subtitleBtn = this.container.querySelector('#videoSubtitleBtn');
        this.subtitleMenu = this.container.querySelector('#subtitleMenu');
        this.subtitleList = this.container.querySelector('#subtitleList');
        this.fullscreenBtn = this.container.querySelector('#videoFullscreenBtn');
        this.speedBtn = this.container.querySelector('#videoSpeedBtn');
        console.log('[SimplePlayer] Speed button found:', this.speedBtn);
        this.speedMenu = this.container.querySelector('#speedMenu');
        this.speedValue = this.container.querySelector('#speedValue');
        this.buffering = this.container.querySelector('.buffering-overlay');
        this.backBtn = this.container.querySelector('#videoBackBtn');
        this.titleLink = this.container.querySelector('#videoTitleLink');
        this.offsetDisplay = this.container.querySelector('#subOffsetVal');

        this.skipIntroBtn = this.container.querySelector('#skipIntroBtn');
        this.skipOutroBtn = this.container.querySelector('#skipOutroBtn');
        this.skipRecapBtn = this.container.querySelector('#skipRecapBtn');

        this.subOffset = 0;

        const offBtn = this.container.querySelector('#sub-off');
        offBtn.onclick = (e) => { e.stopPropagation(); this.selectSubtitle('off', offBtn); };

        if (this.backBtn) {
            this.backBtn.onclick = (e) => { e.stopPropagation(); this.goBack(); };
        }
        if (this.titleLink) {
            this.titleLink.onclick = (e) => { e.stopPropagation(); this.goToDetails(); };
        }

        if (this.skipIntroBtn) {
            this.skipIntroBtn.onclick = (e) => {
                e.stopPropagation();
                if (this.markers.introEnd > 0) {
                    const isMKV = (this.container.dataset.path || '').toLowerCase().endsWith('.mkv');
                    if (isMKV) this.performServerSeek(this.markers.introEnd);
                    else this.video.currentTime = this.markers.introEnd;
                    this.skipIntroBtn.style.display = 'none';
                }
            };
        }

        if (this.skipRecapBtn) {
            this.skipRecapBtn.onclick = (e) => {
                e.stopPropagation();
                if (this.markers.recapEnd > 0) {
                    const isMKV = (this.container.dataset.path || '').toLowerCase().endsWith('.mkv');
                    if (isMKV) this.performServerSeek(this.markers.recapEnd);
                    else this.video.currentTime = this.markers.recapEnd;
                    this.skipRecapBtn.style.display = 'none';
                }
            };
        }

        if (this.skipOutroBtn) {
            this.skipOutroBtn.onclick = (e) => {
                e.stopPropagation();
                if (this.markers.outroEnd > 0) {
                    const isMKV = (this.container.dataset.path || '').toLowerCase().endsWith('.mkv');
                    if (isMKV) this.performServerSeek(this.markers.outroEnd);
                    else this.video.currentTime = this.markers.outroEnd;
                    this.skipOutroBtn.style.display = 'none';
                } else {
                    // If no outro end, just go to next episode or end
                    this.playNextEpisode();
                }
            };
        }

        this.container.querySelector('#sub-generate').onclick = (e) => { e.stopPropagation(); this.triggerSubtitleAction('generate', e.target); };
        this.container.querySelector('#sub-download').onclick = (e) => { e.stopPropagation(); this.triggerSubtitleAction('download', e.target); };
    }

    attachEvents() {
        console.log('[SimplePlayer] Attaching events');
        const toggle = () => {
            if (this.video.paused) this.video.play().catch(() => {});
            else this.video.pause();
        };

        this.video.addEventListener('play', () => {
            console.log('[SimplePlayer] Video Play Event');
            this.container.classList.remove('paused');
            this.playIcon.className = 'pi pi-pause';
            this.bigPlay.style.display = 'none';
            this.reportProgress(true);
            this.setMusicSuspended(true);

            // Start granular periodic progress reporting (every 3 seconds)
            clearInterval(this._progressInterval);
            this._progressInterval = setInterval(() => this.reportProgress(true), 3000);
        });

        this.video.addEventListener('pause', () => {
            console.log('[SimplePlayer] Video Pause Event');
            this.container.classList.add('paused');
            this.playIcon.className = 'pi pi-play';
            this.bigPlay.style.display = 'flex';
            this.reportProgress(false);
            this.setMusicSuspended(false);
            
            // Stop periodic reporting
            clearInterval(this._progressInterval);
            // Report one last time on pause to be precise
            this.reportProgress(true);
        });

        // Ensure we save progress when the user leaves the page
        window.addEventListener('beforeunload', () => {
            if (!this.video.paused) {
                this.reportProgress(true);
            }
        });

        this.video.onwaiting = () => this.buffering.style.display = 'block';
        this.video.onplaying = () => this.buffering.style.display = 'none';

        this.video.addEventListener('ended', () => {
            console.log('[SimplePlayer] Video ended');
            this.playNextEpisode();
        });
        
        this.video.ontimeupdate = () => {
            const isMKV = (this.container.dataset.path || '').toLowerCase().endsWith('.mkv');
            const manualDuration = parseFloat(this.container.dataset.duration || 0) / 1000;
            const duration = (isMKV && manualDuration > 0) ? manualDuration : 
                           (this.video.duration && isFinite(this.video.duration) && this.video.duration > 0 ? this.video.duration : manualDuration);
            
            if (!duration || duration <= 0) return;
            
            const displayTime = this.video.currentTime + this.streamStartOffset;
            const pct = (displayTime / duration) * 100;
            this.progressBar.style.width = Math.min(100, pct) + '%';
            this.timeCurrent.innerText = this.formatTime(displayTime);

            // Recap Skip logic
            if (this.markers.recapEnd > 0 && displayTime >= this.markers.recapStart && displayTime < this.markers.recapEnd) {
                if (this.skipRecapBtn.style.display !== 'block') {
                    console.log('[SimplePlayer] Showing Skip Recap');
                    this.skipRecapBtn.style.display = 'block';
                }
            } else {
                this.skipRecapBtn.style.display = 'none';
            }

            // Intro Skip logic: show if within intro markers
            if (this.markers.introEnd > 0 && displayTime >= this.markers.introStart && displayTime < this.markers.introEnd) {
                if (this.skipIntroBtn.style.display !== 'block') {
                    console.log('[SimplePlayer] Showing Skip Intro');
                    this.skipIntroBtn.style.display = 'block';
                }
            } else {
                this.skipIntroBtn.style.display = 'none';
            }

            // Outro Skip logic: show if during credits (outroStart onwards)
            if (this.markers.outroStart > 0 && displayTime >= this.markers.outroStart) {
                // If there's an outroEnd, only show until then. Otherwise show until end.
                if (this.markers.outroEnd > 0 && displayTime >= this.markers.outroEnd) {
                    if (this.skipOutroBtn.style.display !== 'none') {
                        this.skipOutroBtn.style.display = 'none';
                    }
                } else {
                    if (this.skipOutroBtn.style.display !== 'block') {
                        console.log('[SimplePlayer] Showing Skip Outro');
                        this.skipOutroBtn.style.display = 'block';
                    }
                }
            } else {
                if (this.skipOutroBtn.style.display !== 'none') {
                    this.skipOutroBtn.style.display = 'none';
                }
            }
        };

        this.video.onprogress = () => {
            const isMKV = (this.container.dataset.path || '').toLowerCase().endsWith('.mkv');
            const manualDuration = parseFloat(this.container.dataset.duration || 0) / 1000;
            const duration = (isMKV && manualDuration > 0) ? manualDuration : 
                           (this.video.duration && isFinite(this.video.duration) && this.video.duration > 0 ? this.video.duration : manualDuration);
            
            if (this.video.buffered.length > 0 && duration > 0) {
                const bufferedEnd = this.video.buffered.end(this.video.buffered.length - 1) + this.streamStartOffset;
                const pct = (bufferedEnd / duration) * 100;
                const bufferedBar = this.container.querySelector('.progress-buffered');
                if (bufferedBar) bufferedBar.style.width = Math.min(100, pct) + '%';
            }
        };

        this.video.addEventListener('loadedmetadata', () => {
            const isMKV = (this.container.dataset.path || '').toLowerCase().endsWith('.mkv');
            const manualDuration = parseFloat(this.container.dataset.duration || 0) / 1000;
            const duration = (isMKV && manualDuration > 0) ? manualDuration : 
                           (this.video.duration && isFinite(this.video.duration) && this.video.duration > 0 ? this.video.duration : manualDuration);
            
            this.timeTotal.innerText = this.formatTime(duration);
            this.updateProgressMarkers(duration);
        });

        this.playBtn.onclick = (e) => { e.stopPropagation(); toggle(); };
        this.bigPlay.onclick = (e) => { e.stopPropagation(); toggle(); };
        
        this.container.onclick = (e) => {
            if (e.target.closest('.controls-container') || e.target.closest('.subtitle-menu') || e.target.closest('.speed-menu') || e.target.closest('.modal')) return;
            toggle();
        };

        this.container.querySelectorAll('.skip-btn').forEach(btn => {
            btn.onclick = (e) => {
                e.stopPropagation();
                const skipAmount = parseFloat(btn.dataset.skip);
                const isMKV = (this.container.dataset.path || '').toLowerCase().endsWith('.mkv');
                
                const currentDisplayTime = this.video.currentTime + this.streamStartOffset;
                const newTime = Math.max(0, currentDisplayTime + skipAmount);

                if (isMKV) {
                    this.performServerSeek(newTime);
                } else {
                    this.video.currentTime = newTime;
                }
                
                this.showControls();
                this.reportProgress(!this.video.paused);
            };
        });

        const nextBtn = this.container.querySelector('#videoNextEpBtn');
        const prevBtn = this.container.querySelector('#videoPrevEpBtn');
        
        if (nextBtn) {
            nextBtn.onclick = (e) => {
                e.stopPropagation();
                this.playNextEpisode();
            };
        }
        
        if (prevBtn) {
            prevBtn.onclick = async (e) => {
                e.stopPropagation();
                
                const currentDisplayTime = this.video.currentTime + this.streamStartOffset;
                if (currentDisplayTime > 5) {
                    if ((this.container.dataset.path || '').toLowerCase().endsWith('.mkv')) {
                        this.performServerSeek(0);
                    } else {
                        this.video.currentTime = 0;
                    }
                } else {
                    this.playPreviousEpisode();
                }
            };
        }

        // Detect touch device more reliably
        const isTouchDevice = 'ontouchstart' in window || navigator.maxTouchPoints > 0;

        this.muteBtn.onclick = (e) => {
            e.stopPropagation();
            // Desktop: click to mute
            if (!isTouchDevice || window.innerWidth > 800) {
                this.toggleMute();
                return;
            }
            
            // Mobile: tap shows slider, hold mutes
            const container = this.muteBtn.closest('.volume-container');
            if (container && !container.classList.contains('active')) {
                container.classList.add('active');
                clearTimeout(this._volTimeout);
                this._volTimeout = setTimeout(() => container.classList.remove('active'), 3000);
            }
        };

        this.muteBtn.ontouchstart = (e) => {
            if (isTouchDevice && window.innerWidth <= 800) {
                clearTimeout(this._muteHoldTimer);
                this._muteHoldTimer = setTimeout(() => {
                    this.toggleMute();
                }, 350);
            }
        };

        this.muteBtn.ontouchend = (e) => {
            clearTimeout(this._muteHoldTimer);
        };

        this.volSlider.oninput = (e) => {
            e.stopPropagation();
            this.state.volume = parseFloat(e.target.value);
            this.video.volume = Math.pow(this.state.volume, 2);
            this.video.muted = false;
            this.state.muted = false;
            localStorage.setItem(this.volumeKey, this.state.volume);
            localStorage.setItem(this.muteKey, 'false');
            this.updateVolumeUI();
            
            // Reset hide timer on slider interaction
            const container = this.muteBtn.closest('.volume-container');
            if (container && container.classList.contains('active')) {
                clearTimeout(this._volTimeout);
                this._volTimeout = setTimeout(() => container.classList.remove('active'), 3000);
            }
        };

        this.progressContainer.onclick = (e) => {
            e.stopPropagation();
            const rect = this.progressContainer.getBoundingClientRect();
            const pos = (e.clientX - rect.left) / rect.width;
            
            const isMKV = (this.container.dataset.path || '').toLowerCase().endsWith('.mkv');
            const manualDuration = parseFloat(this.container.dataset.duration || 0) / 1000;
            const duration = (isMKV && manualDuration > 0) ? manualDuration : 
                           (this.video.duration && isFinite(this.video.duration) && this.video.duration > 0 ? this.video.duration : manualDuration);
            
            const newTime = pos * duration;

            if (isMKV) {
                this.performServerSeek(newTime);
            } else {
                this.video.currentTime = newTime;
            }
            
            this.showControls();
            this.reportProgress(!this.video.paused);
        };

        this.progressContainer.onmousemove = (e) => {
            if (!this.storyboard.loaded) return;
            const rect = this.progressContainer.getBoundingClientRect();
            const pos = (e.clientX - rect.left) / rect.width;
            
            const isMKV = (this.container.dataset.path || '').toLowerCase().endsWith('.mkv');
            const manualDuration = parseFloat(this.container.dataset.duration || 0) / 1000;
            const duration = (isMKV && manualDuration > 0) ? manualDuration : 
                           (this.video.duration && isFinite(this.video.duration) && this.video.duration > 0 ? this.video.duration : manualDuration);
            
            const previewTime = pos * duration;
            
            this.previewContainer.style.display = 'block';
            this.previewContainer.style.left = (pos * 100) + '%';
            this.previewTime.innerText = this.formatTime(previewTime);
            
            this.updateStoryboardPreview(previewTime);
        };

        this.progressContainer.onmouseleave = () => {
            this.previewContainer.style.display = 'none';
        };
        this.progressContainer.ontouchend = () => {
            this.previewContainer.style.display = 'none';
        };

        this.subtitleBtn.onclick = (e) => {
            e.stopPropagation();
            this.subtitleMenu.classList.toggle('active');
            this.speedMenu.classList.remove('active');
        };

        this.speedBtn.onclick = (e) => {
            e.stopPropagation();
            console.log('[SimplePlayer] Speed button clicked');
            this.speedMenu.classList.toggle('active');
            console.log('[SimplePlayer] Speed menu active:', this.speedMenu.classList.contains('active'));
            this.subtitleMenu.classList.remove('active');
        };
        this.speedBtn.onmouseover = (e) => {
            console.log('[SimplePlayer] Speed button mouseover');
        };

        // Speed option click handlers
        this.container.querySelectorAll('.speed-option').forEach(option => {
            option.onclick = (e) => {
                e.stopPropagation();
                console.log('[SimplePlayer] Speed option clicked:', option.dataset.speed);
                const speed = parseFloat(option.dataset.speed);
                this.setPlaybackSpeed(speed);
                this.speedMenu.classList.remove('active');
            };
        });

        this.fullscreenBtn.onclick = (e) => {
            e.stopPropagation();
            
            // Check if already in any type of fullscreen
            const inStandardFullscreen = document.fullscreenElement === this.container;
            const inWebkitFullscreen = document.webkitFullscreenElement === this.container;
            const inCssFullscreen = this.container.classList.contains('is-css-fullscreen');
            
            if (!inStandardFullscreen && !inWebkitFullscreen && !inCssFullscreen) {
                // Try standard API first
                if (this.container.requestFullscreen) {
                    this.container.requestFullscreen().catch(err => {
                        console.log('[SimplePlayer] Standard fullscreen failed, trying CSS fallback');
                        this.enableCssFullscreen();
                    });
                } else if (this.container.webkitEnterFullscreen) {
                    // iOS Safari video element fullscreen (less ideal)
                    this.container.webkitEnterFullscreen();
                } else {
                    // Fallback: CSS-based fullscreen for mobile
                    this.enableCssFullscreen();
                }
            } else {
                // Exit fullscreen
                if (inStandardFullscreen) {
                    document.exitFullscreen();
                } else if (inWebkitFullscreen) {
                    document.webkitExitFullscreen();
                } else if (inCssFullscreen) {
                    this.disableCssFullscreen();
                }
            }
        };
        
        document.addEventListener('fullscreenchange', () => this.updateFullscreenState());
        document.addEventListener('webkitfullscreenchange', () => this.updateFullscreenState());

        // CSS Fullscreen fallback for mobile
        window.addEventListener('resize', () => {
            if (this.container.classList.contains('is-css-fullscreen')) {
                this.adjustCssFullscreen();
            }
        });

        // Autohide controls - works on desktop and iPad
        this.container.onmousemove = () => this.showControls();
        this.container.ontouchstart = () => this.showControls();
        this.container.ontouchend = () => this.showControls(); // iPad touch interaction
        this.container.onpointermove = () => this.showControls(); // Pointer events for both mouse and touch
        
        // Use pointerleave instead of mouseleave for better cross-device support
        this.container.onpointerleave = () => {
            if (!this.video.paused) {
                this.hideControls();
            }
        };
        
        // Also ensure moving over UI elements keeps them visible
        const uiElements = this.container.querySelectorAll('.controls-container, .media-info, .subtitle-menu');
        uiElements.forEach(el => {
            el.onpointermove = (e) => {
                e.stopPropagation();
                this.showControls();
            };
        });

        // Handle visibility change - hide controls when switching apps/tabs on iPad
        document.addEventListener('visibilitychange', () => {
            if (document.hidden && !this.video.paused) {
                this.hideControls();
            }
        });

        window.addEventListener('keydown', (e) => {
            if (document.activeElement.tagName === 'INPUT' || document.activeElement.tagName === 'TEXTAREA') return;
            if (this.container.style.display === 'none') return;

            switch(e.code) {
                case 'Space':
                case 'KeyK':
                    e.preventDefault();
                    toggle();
                    this.showControls();
                    break;
                case 'ArrowLeft':
                case 'KeyJ':
                    this.video.currentTime = Math.max(0, this.video.currentTime - 10);
                    this.showControls();
                    break;
                case 'ArrowRight':
                case 'KeyL':
                    this.video.currentTime = Math.min(this.video.duration, this.video.currentTime + 10);
                    this.showControls();
                    break;
                case 'KeyF':
                    this.fullscreenBtn.click();
                    break;
                case 'KeyM':
                    this.muteBtn.click();
                    break;
                case 'Escape':
                    if (this.container.classList.contains('is-css-fullscreen')) {
                        this.disableCssFullscreen();
                    }
                    break;
            }
        });
    }

    enableCssFullscreen() {
        console.log('[SimplePlayer] Enabling CSS fullscreen (mobile fallback)');
        this.container.classList.add('is-css-fullscreen');
        this.container.classList.add('is-fullscreen');
        this.adjustCssFullscreen();
        this.updateFullscreenIcon();
    }

    disableCssFullscreen() {
        console.log('[SimplePlayer] Disabling CSS fullscreen');
        this.container.classList.remove('is-css-fullscreen');
        this.container.classList.remove('is-fullscreen');
        // Reset container styles
        this.container.style.position = '';
        this.container.style.top = '';
        this.container.style.left = '';
        this.container.style.width = '';
        this.container.style.height = '';
        this.container.style.zIndex = '';
        document.body.style.overflow = '';
        this.updateFullscreenIcon();
    }

    adjustCssFullscreen() {
        // Ensure the container fills the viewport
        this.container.style.position = 'fixed';
        this.container.style.top = '0';
        this.container.style.left = '0';
        this.container.style.width = '100vw';
        this.container.style.height = '100vh';
        this.container.style.zIndex = '9999';
        document.body.style.overflow = 'hidden';
    }

    updateFullscreenState() {
        const inStandardFullscreen = document.fullscreenElement === this.container;
        const inWebkitFullscreen = document.webkitFullscreenElement === this.container;
        
        if (inStandardFullscreen || inWebkitFullscreen) {
            this.container.classList.add('is-fullscreen');
        } else if (!this.container.classList.contains('is-css-fullscreen')) {
            this.container.classList.remove('is-fullscreen');
        }
        this.updateFullscreenIcon();
    }

    updateFullscreenIcon() {
        const icon = this.fullscreenBtn ? this.fullscreenBtn.querySelector('i') : null;
        if (icon) {
            const isFullscreen = document.fullscreenElement === this.container || 
                               document.webkitFullscreenElement === this.container ||
                               this.container.classList.contains('is-css-fullscreen');
            icon.className = isFullscreen ? 'pi pi-expand' : 'pi pi-expand';
        }
    }

    formatTime(seconds) {
        if (!seconds || isNaN(seconds)) return '0:00';
        const h = Math.floor(seconds / 3600);
        const m = Math.floor((seconds % 3600) / 60);
        const s = Math.floor(seconds % 60);
        if (h > 0) {
            return `${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
        }
        return `${m}:${s.toString().padStart(2, '0')}`;
    }

    updateVolumeUI() {
        const icon = this.muteBtn.querySelector('i');
        if (this.state.muted || this.state.volume === 0) {
            icon.className = 'pi pi-volume-off';
            this.volSlider.value = 0;
        } else if (this.state.volume < 0.5) {
            icon.className = 'pi pi-volume-down';
            this.volSlider.value = this.state.volume;
        } else {
            icon.className = 'pi pi-volume-up';
            this.volSlider.value = this.state.volume;
        }
    }

    toggleMute() {
        this.video.muted = !this.video.muted;
        this.state.muted = this.video.muted;
        localStorage.setItem(this.muteKey, this.state.muted);
        this.updateVolumeUI();
    }

    showControls() {
        this.container.classList.remove('controls-hidden');
        const liftHeight = this.measureControlBarHeight();
        document.documentElement.style.setProperty('--sub-lift', `${liftHeight}px`);
        // Update Firefox subtitle overlay if available
        if (window.subtitleManager?.setSubtitleLift) {
            window.subtitleManager.setSubtitleLift(liftHeight);
        }
        clearTimeout(this.userActiveTimeout);
        
        // Always set a timeout if we are NOT paused
        if (!this.video.paused) {
            this.userActiveTimeout = setTimeout(() => {
                this.hideControls();
            }, 3000);
        }
    }

    hideControls() {
        this.container.classList.add('controls-hidden');
        document.documentElement.style.setProperty('--sub-lift', '0px');
        // Update Firefox subtitle overlay if available
        if (window.subtitleManager?.setSubtitleLift) {
            window.subtitleManager.setSubtitleLift(0);
        }
        this.subtitleMenu.classList.remove('active');
        if (this.previewContainer) {
            this.previewContainer.style.display = 'none';
        }
        clearTimeout(this.userActiveTimeout);
    }

    async loadSubtitles(providedTracks) {
        let tracks = providedTracks;
        
        if (!tracks) {
            console.log(`[SimplePlayer] Fetching subtitles for video ${this.videoId}`);
            const url = `/api/video/subtitles/${this.videoId}`;
            try {
                const res = await fetch(url).then(r => r.json());
                // Handle different possible response structures
                tracks = res.tracks || res.data || (Array.isArray(res) ? res : []);
                console.log(`[SimplePlayer] Loaded ${tracks.length} tracks from server`);
            } catch (e) {
                console.error('[SimplePlayer] Failed to load subtitles:', e);
                tracks = [];
            }
        } else {
            console.log(`[SimplePlayer] Using ${tracks.length} provided tracks`);
        }
        
        this.renderSubtitleList(tracks);
    }

    renderSubtitleList(tracks) {
        if (!this.subtitleList) return;

        // Clear existing options except the "Off" option
        this.subtitleList.innerHTML = '<div class="subtitle-option" id="sub-off" data-id="off">Off</div>';
        
        const offBtn = this.subtitleList.querySelector('#sub-off');
        offBtn.onclick = (e) => { e.stopPropagation(); this.selectSubtitle('off', offBtn); };

        if (this.lastSelectedTrackId === 'off' || !this.lastSelectedTrackId) {
            offBtn.classList.add('active');
        }

        tracks.forEach(track => {
            const div = document.createElement('div');
            div.className = 'subtitle-option';
            div.dataset.id = track.id;
            
            // Use correct fields from SubtitleTrackDTO
            const label = track.displayName || track.languageName || track.filename || 'Unknown';
            const typeInfo = track.isEmbedded ? '[Embedded]' : '[External]';
            div.innerText = `${label} (${track.format || 'SRT'}) ${typeInfo}`;
            
            div.onclick = (e) => {
                e.stopPropagation();
                this.selectSubtitle(track.id, div);
            };
            
            this.subtitleList.appendChild(div);
            
            // Auto-restore last selected track
            if (this.lastSelectedTrackId && String(track.id) === String(this.lastSelectedTrackId)) {
                this.selectSubtitle(track.id, div);
            }
        });
    }

    selectSubtitle(trackId, element) {
        console.log(`[SimplePlayer] Selecting subtitle track: ${trackId}`);
        
        this.container.querySelectorAll('.subtitle-option').forEach(el => el.classList.remove('active'));
        if (element) {
            element.classList.add('active');
        }
        this.subtitleMenu.classList.remove('active');

        // Clear any existing tracks from the DOM and ensure they are disabled in the video element
        const oldTracks = this.video.querySelectorAll('track');
        oldTracks.forEach(t => t.remove());
        
        // Also clear from the textTracks list (some browsers keep them around)
        if (this.video.textTracks) {
            for (let i = 0; i < this.video.textTracks.length; i++) {
                this.video.textTracks[i].mode = 'disabled';
            }
        }

        if (trackId === 'off' || !trackId) {
            // Extra clearing for Firefox - try to force cues to disappear
            if (navigator.userAgent.includes('Firefox')) {
                console.log('[SimplePlayer] Firefox detected, attempting to clear frozen subtitles');
                // Try setting to hidden first, then disabled
                if (this.video.textTracks) {
                    for (let i = 0; i < this.video.textTracks.length; i++) {
                        const track = this.video.textTracks[i];
                        if (track.mode === 'disabled') {
                            track.mode = 'hidden';
                            setTimeout(() => {
                                track.mode = 'disabled';
                            }, 10);
                        }
                    }
                }
                // Also try removing all track elements again
                const moreTracks = this.video.querySelectorAll('track');
                moreTracks.forEach(t => t.remove());
                // Force a refresh by slightly changing playback rate
                const originalRate = this.video.playbackRate;
                this.video.playbackRate = 1.0001;
                setTimeout(() => {
                    this.video.playbackRate = originalRate;
                }, 50);
            }
            this.lastSelectedTrackId = 'off';
            localStorage.setItem('jmedia_last_track_' + this.videoId, 'off');
            return;
        }

        this.lastSelectedTrackId = trackId;
        localStorage.setItem('jmedia_last_track_' + this.videoId, trackId);

        const trackEl = document.createElement('track');
        trackEl.kind = 'subtitles';
        trackEl.label = element ? element.innerText : 'Subtitle';
        trackEl.srclang = 'en';
        
        // Correct endpoint is /track/, and we MUST pass the start offset for sync (especially for MKV)
        let src = `/api/video/subtitles/track/${trackId}`;
        if (this.streamStartOffset > 0) {
            src += `?start=${this.streamStartOffset}`;
        }
        
        trackEl.src = src;
        trackEl.default = true;

        console.log(`[SimplePlayer] Appending track: ${trackEl.label} with src: ${src}`);
        this.video.appendChild(trackEl);

        // Force browser to recognize the new track
        trackEl.onload = () => {
            console.log(`[SimplePlayer] Track loaded: ${trackEl.label}`);
            setTimeout(() => {
                if (trackEl.track) {
                    trackEl.track.mode = 'showing';
                    console.log(`[SimplePlayer] Track mode set to showing (onload)`);
                }
            }, 100);
        };

        trackEl.onerror = (e) => {
            console.error(`[SimplePlayer] Failed to load subtitle track: ${src}`, e);
        };

        // Fallback for browsers that don't trigger onload for tracks reliably
        setTimeout(() => {
            if (trackEl.track && trackEl.track.mode !== 'showing') {
                trackEl.track.mode = 'showing';
                console.log(`[SimplePlayer] Track mode set to showing (timeout fallback)`);
            }
        }, 500);

        // Also check all textTracks to be sure
        setTimeout(() => {
            const tracks = this.video.textTracks;
            for (let i = 0; i < tracks.length; i++) {
                if (tracks[i].label === trackEl.label) {
                    tracks[i].mode = 'showing';
                } else {
                    tracks[i].mode = 'disabled';
                }
            }
        }, 1000);    }

    adjustSubtitleOffset(seconds, reset = false) {
        if (reset) this.subOffset = 0;
        else this.subOffset += seconds;
        
        if (this.offsetDisplay) {
            this.offsetDisplay.innerText = (this.subOffset > 0 ? '+' : '') + this.subOffset.toFixed(1) + 's';
        }

        // Apply to active track
        const tracks = this.video.textTracks;
        if (tracks && tracks.length > 0) {
            const track = tracks[0];
            if (track.cues) {
                for (let i = 0; i < track.cues.length; i++) {
                    const cue = track.cues[i];
                    if (!cue._originalStart) {
                        cue._originalStart = cue.startTime;
                        cue._originalEnd = cue.endTime;
                    }
                    cue.startTime = cue._originalStart + this.subOffset;
                    cue.endTime = cue._originalEnd + this.subOffset;
                }
                // Force browser to re-evaluate active cues after modifying cue times
                this.video.currentTime = this.video.currentTime;
            }
        }
    }

    setPlaybackSpeed(speed) {
        console.log('[SimplePlayer] Setting playback speed to:', speed);
        if (speed <= 0) speed = 1.0;
        this.video.playbackRate = speed;
        this.currentSpeed = speed;
        this.speedValue.innerText = speed.toFixed(1) + 'x';
        // Update active state in speed menu
        this.container.querySelectorAll('.speed-option').forEach(option => {
            const optSpeed = parseFloat(option.dataset.speed);
            if (Math.abs(optSpeed - speed) < 0.01) {
                option.classList.add('active');
            } else {
                option.classList.remove('active');
            }
        });
    }

    applySubtitleStyle() {
        console.log('[SimplePlayer] Applying subtitle style updates');
        // Re-apply current sub-lift to ensure the padding is updated
        const isHidden = this.container.classList.contains('controls-hidden');
        const liftHeight = isHidden ? 0 : this.measureControlBarHeight();
        document.documentElement.style.setProperty('--sub-lift', `${liftHeight}px`);
    }

    measureControlBarHeight() {
        // Measure the actual height of the controls container
        const controlsContainer = this.container.querySelector('.controls-container');
        if (controlsContainer) {
            const height = controlsContainer.offsetHeight;
            // Add some padding (10px) for safety
            this.controlBarHeight = height + 10;
        }
        // Fallback to 80px if we can't measure
        return this.controlBarHeight;
    }

    triggerSubtitleAction(action, element) {
        this.subtitleMenu.classList.remove('active');
        if (action === 'generate') {
            if (window.subtitleManager) {
                window.subtitleManager.currentVideoId = this.videoId;
                window.subtitleManager.currentVideoTitle = this.container.dataset.title || 'Video';
                window.subtitleManager.generateAiSubtitles();
            }
        } else if (action === 'download') {
            if (window.subtitleManager) window.subtitleManager.openModal(this.videoId, this.container.dataset.title || 'Video', this.container.dataset.path || '');
        }
    }

    loadStoryboard() {
        if (!this.videoId) return;
        const url = `/api/video/storyboard/${this.videoId}/metadata`;
        fetch(url)
            .then(res => res.ok ? res.json() : null)
            .then(data => {
                if (data && data.success) {
                    this.storyboard.metadata = data.data;
                    if (data.data.isReady) {
                        this.storyboard.loaded = true;
                        console.log('[SimplePlayer] Storyboard metadata and image ready');
                    } else {
                        console.log('[SimplePlayer] Storyboard still generating, retrying in 5s...');
                        setTimeout(() => this.loadStoryboard(), 5000);
                    }
                }
            })
            .catch(err => {
                console.error('[SimplePlayer] Error loading storyboard metadata:', err);
                // Retry on network error too
                setTimeout(() => this.loadStoryboard(), 10000);
            });
    }

    updateStoryboardPreview(time) {
        if (!this.storyboard.metadata) return;
        const meta = this.storyboard.metadata;
        const index = Math.floor(time / meta.interval);
        const col = index % meta.columns;
        const row = Math.floor(index / meta.columns);

        const x = col * meta.width;
        const y = row * meta.height;

        const previewImg = this.previewContainer.querySelector('.storyboard-img') || document.createElement('div');
        if (!previewImg.classList.contains('storyboard-img')) {
            previewImg.className = 'storyboard-img';
            this.previewContainer.prepend(previewImg);
        }

        previewImg.style.width = meta.width + 'px';
        previewImg.style.height = meta.height + 'px';
        previewImg.style.backgroundImage = `url(/api/video/storyboard/${this.videoId}/tiles)`;
        previewImg.style.backgroundPosition = `-${x}px -${y}px`;
        previewImg.style.backgroundSize = (meta.columns * meta.width) + 'px auto';
    }

    reportProgress(playing) {
        if (!playing) return;
        const time = this.video.currentTime + this.streamStartOffset;
        fetch(`/api/video/progress/${this.videoId}?time=${time}`, { method: 'POST', credentials: 'same-origin' });
    }

    async reportProgressInternal(time) {
        fetch(`/api/video/progress/${this.videoId}?time=${time}`, { method: 'POST', credentials: 'same-origin' });
    }

    async playNextEpisode() {
        const nextId = this.container.dataset.nextId;
        if (nextId && nextId !== 'null') {
            // Store fullscreen state before navigation
            const isFullscreen = document.fullscreenElement === this.container || document.webkitFullscreenElement === this.container;
            if (isFullscreen) {
                sessionStorage.setItem('jmedia_restore_fullscreen', 'true');
            }
            if (window.videoSPA) {
                window.videoSPA.playVideo(nextId);
            } else {
                window.location.href = `/video?section=playback&videoId=${nextId}`;
            }
        }
    }

    async playPreviousEpisode() {
        const prevId = this.container.dataset.prevId;
        if (prevId && prevId !== 'null') {
            // Store fullscreen state before navigation
            const isFullscreen = document.fullscreenElement === this.container || document.webkitFullscreenElement === this.container;
            if (isFullscreen) {
                sessionStorage.setItem('jmedia_restore_fullscreen', 'true');
            }
            if (window.videoSPA) {
                window.videoSPA.playVideo(prevId);
            } else {
                window.location.href = `/video?section=playback&videoId=${prevId}`;
            }
        }
    }

    async goBack() {
        if (window.videoSPA) {
            window.videoSPA.goBack();
        } else if (window.app) {
            window.app.navigate('/video');
        } else {
            window.location.href = '/video';
        }
    }

    async goToDetails() {
        if (window.videoSPA) {
            const player = this.container || document.getElementById('customPlayer');
            const videoId = player ? (player.getAttribute('data-video-id') || this.videoId) : this.videoId;
            window.videoSPA.switchSection('details', { videoId: videoId });
        } else if (window.app) {
            window.app.navigate('/video');
        } else {
            window.location.href = '/video';
        }
    }

    performServerSeek(time) {
        console.log(`[SimplePlayer] Performing server-side seek to ${time}s`);
        const isIOS = /iPhone|iPad|iPod/i.test(navigator.userAgent);
        
        if (isIOS) {
            this.streamStartOffset = 0;
            this.video.currentTime = time;
        } else {
            this.streamStartOffset = time;
            this.video.src = `/api/video/stream/${this.videoId}?start=${time}`;
        }
        
        // Refresh subtitles if one is selected to ensure it gets the correct start offset
        if (this.lastSelectedTrackId && this.lastSelectedTrackId !== 'off') {
            const activeOpt = this.subtitleList.querySelector(`.subtitle-option[data-id="${this.lastSelectedTrackId}"]`);
            this.selectSubtitle(this.lastSelectedTrackId, activeOpt);
        }
        
        this.video.play().catch(console.error);
    }
}

window.SimplePlayer = SimplePlayer;
