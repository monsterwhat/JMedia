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
        this.lastSelectedTrackId = localStorage.getItem('jmedia_last_track_' + this.videoId) || null;
        this.storyboard = {
            metadata: null,
            loaded: false
        };

        window.currentPlayerInstance = this;
        this.init();
    }

    init() {
        console.log('[SimplePlayer] Init called');
        this.buildUI();
        this.attachEvents();
        
        // Add a small delay before loading storyboard to avoid network rush
        setTimeout(() => this.loadStoryboard(), 1000);
        
        // Restore Audio
        this.video.volume = Math.pow(this.state.volume, 2);
        this.video.muted = this.state.muted;
        this.updateVolumeUI();
        
        // Setup initial source
        const isMKV = (this.container.dataset.path || '').toLowerCase().endsWith('.mkv');
        const savedTime = parseFloat(this.container.dataset.startTime || 0);
        
        if (isMKV && savedTime > 0) {
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

        const isEpisode = (this.container.dataset.type === 'Episode' || this.container.dataset.type === 'episode');
        const hasNext = (this.container.dataset.nextId && this.container.dataset.nextId !== 'null');

        const uiHTML = `
            <div class="big-play-btn"><img src="/logo.png" alt="Play"></div>
            <div class="buffering-overlay"><i class="pi pi-spin pi-spinner" style="font-size: 3rem; color: #48c774;"></i></div>

            <div class="media-info">
                <div class="back-button-container">
                    <button class="back-btn" id="videoBackBtn" title="Go Back">
                        <i class="pi pi-arrow-left"></i>
                    </button>
                </div>
                <div class="info-title" id="videoTitleLink" style="cursor: pointer;" title="View Details">${this.container.dataset.title || 'Video'}</div>
                ${this.container.dataset.meta ? `<div class="info-meta" style="font-size: 0.9rem; color: rgba(255,255,255,0.7); margin-left: 10px; margin-top: 4px;">${this.container.dataset.meta}</div>` : ''}
            </div>

            <div class="controls-container">
                <div class="preview-container" id="scrollPreview">
                    <div class="preview-time" id="previewTime">0:00</div>
                </div>

                <div class="progress-container">
                    <div class="progress-buffered" style="width: 0%;"></div>
                    <div class="progress-filled" style="width: 0%;"></div>
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
        `;

        this.container.insertAdjacentHTML('beforeend', uiHTML);

        this.playBtn = this.container.querySelector('#videoPlayPauseBtn');
        this.playIcon = this.playBtn.querySelector('i');
        this.bigPlay = this.container.querySelector('.big-play-btn');
        this.progressBar = this.container.querySelector('.progress-filled');
        this.progressContainer = this.container.querySelector('.progress-container');
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
        this.buffering = this.container.querySelector('.buffering-overlay');
        this.backBtn = this.container.querySelector('#videoBackBtn');
        this.titleLink = this.container.querySelector('#videoTitleLink');
        this.offsetDisplay = this.container.querySelector('#subOffsetVal');

        this.subOffset = 0;

        const offBtn = this.container.querySelector('#sub-off');
        offBtn.onclick = (e) => { e.stopPropagation(); this.selectSubtitle('off', offBtn); };

        if (this.backBtn) {
            this.backBtn.onclick = (e) => { e.stopPropagation(); this.goBack(); };
        }
        if (this.titleLink) {
            this.titleLink.onclick = (e) => { e.stopPropagation(); this.goToDetails(); };
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
        });

        this.playBtn.onclick = (e) => { e.stopPropagation(); toggle(); };
        this.bigPlay.onclick = (e) => { e.stopPropagation(); toggle(); };
        
        this.container.onclick = (e) => {
            if (e.target.closest('.controls-container') || e.target.closest('.subtitle-menu')) return;
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

        this.muteBtn.onclick = (e) => {
            e.stopPropagation();
            const container = this.muteBtn.closest('.volume-container');
            
            // On mobile/touch, first click opens the slider, second click mutes
            if (window.innerWidth <= 800 && container && !container.classList.contains('active')) {
                container.classList.add('active');
                // Auto-hide after 3 seconds of inactivity
                clearTimeout(this._volTimeout);
                this._volTimeout = setTimeout(() => container.classList.remove('active'), 3000);
                return;
            }

            this.video.muted = !this.video.muted;
            this.state.muted = this.video.muted;
            localStorage.setItem(this.muteKey, this.state.muted);
            this.updateVolumeUI();
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

        this.subtitleBtn.onclick = (e) => {
            e.stopPropagation();
            this.subtitleMenu.classList.toggle('active');
        };

        this.fullscreenBtn.onclick = (e) => {
            e.stopPropagation();
            if (!document.fullscreenElement) {
                this.container.requestFullscreen().catch(err => {
                    console.error(`Error attempting to enable full-screen mode: ${err.message}`);
                });
            } else {
                document.exitFullscreen();
            }
        };

        document.addEventListener('fullscreenchange', () => {
            if (document.fullscreenElement === this.container) {
                this.container.classList.add('is-fullscreen');
                this.fullscreenBtn.querySelector('i').className = 'pi pi-compress';
            } else {
                this.container.classList.remove('is-fullscreen');
                this.fullscreenBtn.querySelector('i').className = 'pi pi-expand';
            }
        });

        this.container.onmousemove = () => this.showControls();
        this.container.ontouchstart = () => this.showControls();

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
            }
        });
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

    showControls() {
        this.container.classList.remove('controls-hidden');
        this.video.style.setProperty('--sub-lift', '80px');
        clearTimeout(this.userActiveTimeout);
        if (!this.video.paused) {
            this.userActiveTimeout = setTimeout(() => {
                this.container.classList.add('controls-hidden');
                this.video.style.setProperty('--sub-lift', '0px');
                this.subtitleMenu.classList.remove('active');
            }, 3000);
        }
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

        const old = this.video.querySelectorAll('track');
        old.forEach(t => t.remove());

        if (trackId === 'off' || !trackId) {
            this.lastSelectedTrackId = 'off';
            localStorage.setItem('jmedia_last_track_' + this.videoId, 'off');
            return;
        }

        this.lastSelectedTrackId = trackId;
        localStorage.setItem('jmedia_last_track_' + this.videoId, trackId);

        const track = document.createElement('track');
        track.kind = 'subtitles';
        track.label = element ? element.innerText : 'Subtitle';
        track.srclang = 'en';
        
        // Correct endpoint is /track/, and we MUST pass the start offset for sync (especially for MKV)
        let src = `/api/video/subtitles/track/${trackId}`;
        if (this.streamStartOffset > 0) {
            src += `?start=${this.streamStartOffset}`;
        }
        
        track.src = src;
        track.default = true;
        this.video.appendChild(track);
        
        // Ensure the track is actually showing
        if (this.video.textTracks && this.video.textTracks.length > 0) {
            this.video.textTracks[0].mode = 'showing';
        }
    }

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
            }
        }
    }

    applySubtitleStyle() {
        console.log('[SimplePlayer] Applying subtitle style updates');
        // Re-apply current sub-lift to ensure the padding is updated
        const isHidden = this.container.classList.contains('controls-hidden');
        this.video.style.setProperty('--sub-lift', isHidden ? '0px' : '80px');
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
        const url = `/api/video/storyboard/${this.videoId}/metadata`;
        fetch(url)
            .then(res => res.ok ? res.json() : null)
            .then(data => {
                if (data && data.success) {
                    this.storyboard.metadata = data.data;
                    this.storyboard.loaded = true;
                    console.log('[SimplePlayer] Storyboard metadata loaded');
                }
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
    }

    reportProgress(playing) {
        if (!playing) return;
        const time = this.video.currentTime + this.streamStartOffset;
        fetch(`/api/video/progress/${this.videoId}?time=${time}`, { method: 'POST' });
    }

    async playNextEpisode() {
        const nextId = this.container.dataset.nextId;
        if (nextId && nextId !== 'null') {
            window.location.href = `/video/play/${nextId}`;
        }
    }

    async playPreviousEpisode() {
        const prevId = this.container.dataset.prevId;
        if (prevId && prevId !== 'null') {
            window.location.href = `/video/play/${prevId}`;
        }
    }

    async goBack() {
        if (window.app) {
            window.app.navigate('/video');
        } else {
            window.location.href = '/video';
        }
    }

    async goToDetails() {
        if (window.app) {
             const type = (this.container.dataset.type || 'Movie').toLowerCase();
             const id = this.videoId;
             window.app.navigate(`/video?section=details&type=${type}&id=${id}`);
        } else {
            const type = (this.container.dataset.type || 'Movie').toLowerCase();
            window.location.href = `/video?section=details&type=${type}&id=${this.videoId}`;
        }
    }

    performServerSeek(time) {
        console.log(`[SimplePlayer] Performing server-side seek to ${time}s`);
        this.streamStartOffset = time;
        this.video.src = `/api/video/stream/${this.videoId}?start=${time}`;
        
        // Refresh subtitles if one is selected to ensure it gets the correct start offset
        if (this.lastSelectedTrackId && this.lastSelectedTrackId !== 'off') {
            const activeOpt = this.subtitleList.querySelector(`.subtitle-option[data-id="${this.lastSelectedTrackId}"]`);
            this.selectSubtitle(this.lastSelectedTrackId, activeOpt);
        }
        
        this.video.play().catch(console.error);
    }
}

window.SimplePlayer = SimplePlayer;
