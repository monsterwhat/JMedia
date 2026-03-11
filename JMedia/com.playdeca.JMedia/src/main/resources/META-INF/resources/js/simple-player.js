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

        this.storyboard = {
            metadata: null,
            loaded: false
        };

        this.init();
    }

    init() {
        this.buildUI();
        this.attachEvents();
        this.loadSubtitles();
        
        // Add a small delay before loading storyboard to avoid network rush
        setTimeout(() => this.loadStoryboard(), 1000);
        
        // Restore Audio
        this.video.volume = Math.pow(this.state.volume, 2);
        this.video.muted = this.state.muted;
        this.updateVolumeUI();
        
        // Restore Time
        const savedTime = this.container.dataset.startTime;
        if (savedTime && parseFloat(savedTime) > 0) {
            this.video.currentTime = parseFloat(savedTime);
        }

        this.showControls();
    }

    buildUI() {
        const old = this.container.querySelectorAll('.controls-container, .media-info, .big-play-btn, .subtitle-menu, .buffering-overlay');
        old.forEach(el => el.remove());

        const uiHTML = `
            <div class="big-play-btn"><img src="/logo.png" alt="Play"></div>
            <div class="buffering-overlay"><i class="pi pi-spin pi-spinner" style="font-size: 3rem; color: #48c774;"></i></div>

            <div class="media-info">
                <div class="back-button-container">
                    <button class="back-btn" id="backBtn" title="Go Back">
                        <i class="pi pi-arrow-left"></i>
                    </button>
                </div>
                <div class="info-title" id="videoTitleLink" style="cursor: pointer;" title="View Details">${this.container.dataset.title || 'Video'}</div>
                <div class="info-meta">${this.container.dataset.meta || ''}</div>
            </div>

            <div class="controls-container">
                <div class="preview-container" id="scrollPreview">
                    <div class="preview-time" id="previewTime">0:00</div>
                </div>

                <div class="progress-container">
                    <div class="progress-filled" style="width: 0%;"></div>
                </div>
                
                <div class="controls-row">
                    <button class="control-btn" id="playPauseBtn"><i class="pi pi-play"></i></button>
                    
                    <button class="control-btn skip-btn" data-skip="-30"><i class="pi pi-angle-double-left"></i><span class="skip-val">-30</span></button>
                    <button class="control-btn skip-btn" data-skip="-15"><i class="pi pi-angle-double-left"></i><span class="skip-val">-15</span></button>
                    <button class="control-btn skip-btn" data-skip="15"><i class="pi pi-angle-double-right"></i><span class="skip-val">+15</span></button>
                    <button class="control-btn skip-btn" data-skip="30"><i class="pi pi-angle-double-right"></i><span class="skip-val">+30</span></button>

                    <div class="volume-container">
                        <button class="control-btn" id="muteBtn"><i class="pi pi-volume-up"></i></button>
                        <input type="range" class="volume-slider" min="0" max="1" step="0.01" value="${this.state.volume}">
                    </div>

                    <div class="time-display">
                        <span id="currentTime">0:00</span> / <span id="totalTime">0:00</span>
                    </div>

                    <div class="spacer"></div>

                    <button class="control-btn" id="subtitleBtn"><i class="pi pi-comments"></i></button>
                    <button class="control-btn" id="fullscreenBtn"><i class="pi pi-expand"></i></button>
                </div>
            </div>

            <div class="subtitle-menu" id="subtitleMenu">
                <div class="menu-header">Subtitles</div>
                <div class="subtitle-list" id="subtitleList">
                    <div class="subtitle-option selected" id="sub-off">Off</div>
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
                    <button class="button is-ghost is-small is-fullwidth mt-1" style="color: rgba(255,255,255,0.5)" onclick="event.stopPropagation(); currentPlayerInstance.resetSubtitleOffset()">Reset</button>
                </div>

                <div class="menu-divider"></div>
                <div class="subtitle-actions">
                    <div class="subtitle-option action-opt" id="sub-generate"><i class="pi pi-bolt"></i> Generate (AI)</div>
                    <div class="subtitle-option action-opt" id="sub-download"><i class="pi pi-search"></i> Search (Web)</div>
                </div>
            </div>
        `;

        this.container.insertAdjacentHTML('beforeend', uiHTML);

        this.playBtn = this.container.querySelector('#playPauseBtn');
        this.playIcon = this.playBtn.querySelector('i');
        this.bigPlay = this.container.querySelector('.big-play-btn');
        this.progressBar = this.container.querySelector('.progress-filled');
        this.progressContainer = this.container.querySelector('.progress-container');
        this.previewContainer = this.container.querySelector('#scrollPreview');
        this.previewTime = this.container.querySelector('#previewTime');
        this.timeCurrent = this.container.querySelector('#currentTime');
        this.timeTotal = this.container.querySelector('#totalTime');
        this.volSlider = this.container.querySelector('.volume-slider');
        this.muteBtn = this.container.querySelector('#muteBtn');
        this.subtitleBtn = this.container.querySelector('#subtitleBtn');
        this.subtitleMenu = this.container.querySelector('#subtitleMenu');
        this.subtitleList = this.container.querySelector('#subtitleList');
        this.fullscreenBtn = this.container.querySelector('#fullscreenBtn');
        this.buffering = this.container.querySelector('.buffering-overlay');
        this.backBtn = this.container.querySelector('#backBtn');
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
        const toggle = () => {
            if (this.video.paused) this.video.play().catch(() => {});
            else this.video.pause();
        };

        this.video.onplay = () => {
            this.container.classList.remove('paused');
            this.playIcon.className = 'pi pi-pause';
            this.bigPlay.style.display = 'none';
            this.reportProgress(true);
        };
        this.video.onpause = () => {
            this.container.classList.add('paused');
            this.playIcon.className = 'pi pi-play';
            this.bigPlay.style.display = 'flex';
            this.reportProgress(false);
        };
        this.video.onwaiting = () => this.buffering.style.display = 'block';
        this.video.onplaying = () => this.buffering.style.display = 'none';
        
        this.video.ontimeupdate = () => {
            if (!this.video.duration) return;
            const pct = (this.video.currentTime / this.video.duration) * 100;
            this.progressBar.style.width = pct + '%';
            this.timeCurrent.innerText = this.formatTime(this.video.currentTime);
        };

        this.video.onloadedmetadata = () => {
            this.timeTotal.innerText = this.formatTime(this.video.duration);
        };

        this.playBtn.onclick = (e) => { e.stopPropagation(); toggle(); };
        this.bigPlay.onclick = (e) => { e.stopPropagation(); toggle(); };
        
        this.container.onclick = (e) => {
            if (e.target.closest('.controls-container') || e.target.closest('.subtitle-menu')) return;
            toggle();
        };

        this.container.querySelectorAll('.skip-btn').forEach(btn => {
            btn.onclick = (e) => {
                e.stopPropagation();
                this.video.currentTime += parseFloat(btn.dataset.skip);
                this.showControls();
                this.reportProgress(!this.video.paused);
            };
        });

        this.progressContainer.onclick = (e) => {
            e.stopPropagation();
            const rect = this.progressContainer.getBoundingClientRect();
            const pos = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
            this.video.currentTime = pos * this.video.duration;
            this.reportProgress(!this.video.paused);
        };

        this.progressContainer.onmousemove = (e) => {
            const rect = this.progressContainer.getBoundingClientRect();
            const pos = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
            const time = pos * this.video.duration;
            
            this.previewContainer.style.setProperty('display', 'block', 'important');
            this.previewContainer.style.setProperty('left', `${(pos * 100)}%`, 'important');
            this.previewContainer.style.setProperty('transform', 'translateX(-50%)', 'important');
            this.previewTime.innerText = this.formatTime(time);

            if (this.storyboard.loaded && this.storyboard.metadata) {
                const meta = this.storyboard.metadata;
                const tileIndex = Math.floor(time / meta.interval);
                const clampedIndex = Math.max(0, Math.min(meta.totalTiles - 1, tileIndex));
                
                const col = clampedIndex % meta.columns;
                const row = Math.floor(clampedIndex / meta.columns);
                
                const posX = col * meta.tileWidth;
                const posY = row * meta.tileHeight;
                
                this.previewContainer.style.setProperty('background-image', `url(/api/video/storyboard/${this.videoId})`, 'important');
                this.previewContainer.style.setProperty('background-position', `-${posX}px -${posY}px`, 'important');
                this.previewContainer.style.setProperty('background-size', `${meta.columns * 100}% auto`, 'important');
            } else {
                this.previewContainer.style.setProperty('background-image', 'none', 'important');
                this.previewContainer.style.setProperty('background-color', 'rgba(0,0,0,0.8)', 'important');
            }
        };

        this.progressContainer.onmouseleave = () => {
            this.previewContainer.style.setProperty('display', 'none', 'important');
        };

        this.volSlider.oninput = (e) => {
            const val = parseFloat(e.target.value);
            this.state.volume = val;
            this.video.volume = Math.pow(val, 2);
            this.video.muted = false;
            localStorage.setItem(this.volumeKey, val);
            localStorage.setItem(this.muteKey, 'false');
            this.updateVolumeUI();
        };

        this.muteBtn.onclick = (e) => {
            e.stopPropagation();
            this.video.muted = !this.video.muted;
            localStorage.setItem(this.muteKey, this.video.muted);
            this.updateVolumeUI();
        };

        this.subtitleBtn.onclick = (e) => { e.stopPropagation(); this.subtitleMenu.classList.toggle('active'); };
        this.fullscreenBtn.onclick = (e) => {
            e.stopPropagation();
            if (!document.fullscreenElement) this.container.requestFullscreen().catch(() => {});
            else document.exitFullscreen();
        };

        this.container.onmousemove = () => this.showControls();
        
        this._keyHandler = (e) => {
            if (!document.getElementById(this.container.id)) {
                document.removeEventListener('keydown', this._keyHandler);
                return;
            }
            if (e.target.tagName === 'INPUT') return;
            const key = e.key.toLowerCase();
            if (key === ' ') { e.preventDefault(); toggle(); }
            else if (key === 'arrowleft') { this.video.currentTime -= 10; this.showControls(); this.reportProgress(!this.video.paused); }
            else if (key === 'arrowright') { this.video.currentTime += 10; this.showControls(); this.reportProgress(!this.video.paused); }
            else if (key === 'f') this.fullscreenBtn.click();
            else if (key === 'm') this.muteBtn.click();
        };
        document.addEventListener('keydown', this._keyHandler);

        this._heartbeat = setInterval(() => {
            if (this.video && !this.video.paused) {
                this.reportProgress(true);
            }
        }, 3000);

        window.currentPlayerInstance = this;
    }

    async loadStoryboard(retryCount = 0) {
        if (!this.videoId) return;
        try {
            console.log(`[SimplePlayer] Fetching storyboard metadata for ${this.videoId} (Attempt ${retryCount + 1})`);
            const res = await fetch(`/api/video/storyboard/${this.videoId}/metadata`);
            
            if (res.ok) {
                const data = await res.json();
                if (data.success && data.data) {
                    this.storyboard.metadata = data.data;
                    this.storyboard.loaded = true;
                    // Preload image
                    const img = new Image();
                    img.src = `/api/video/storyboard/${this.videoId}`;
                    console.log('[SimplePlayer] Storyboard metadata loaded successfully');
                    return;
                }
            }
            
            if ((res.status === 202 || res.status === 404) && retryCount < 15) {
                const delay = Math.min(2000 * Math.pow(1.3, retryCount), 20000);
                console.log(`[SimplePlayer] Storyboard still generating or not found (Status ${res.status}). Retrying in ${Math.round(delay/1000)}s...`);
                setTimeout(() => this.loadStoryboard(retryCount + 1), delay);
            } else if (res.status !== 200) {
                console.warn(`[SimplePlayer] Stopped retrying storyboard. Final status: ${res.status}`);
            }
        } catch (e) {
            console.warn('[SimplePlayer] Storyboard metadata fetch error:', e);
            if (retryCount < 5) {
                setTimeout(() => this.loadStoryboard(retryCount + 1), 5000);
            }
        }
    }

    async reportProgress(isPlaying) {
        try {
            await fetch(`/api/video/playback/progress?videoId=${this.videoId}&time=${this.video.currentTime}&playing=${isPlaying}`, { method: 'POST' });
        } catch (e) {}
    }

    showControls() {
        this.container.classList.add('user-active');
        clearTimeout(this.userActiveTimeout);
        this.userActiveTimeout = setTimeout(() => {
            if (!this.video.paused) this.container.classList.remove('user-active');
        }, 3000);
    }

    updateVolumeUI() {
        const isMuted = this.video.muted || this.state.volume === 0;
        if (isMuted) { this.muteBtn.innerHTML = '<i class="pi pi-volume-off"></i>'; this.volSlider.value = 0; }
        else { this.muteBtn.innerHTML = this.state.volume < 0.5 ? '<i class="pi pi-volume-down"></i>' : '<i class="pi pi-volume-up"></i>'; this.volSlider.value = this.state.volume; }
    }

    formatTime(s) {
        if (!s || isNaN(s)) return "0:00";
        const h = Math.floor(s / 3600); const m = Math.floor((s % 3600) / 60); const sec = Math.floor(s % 60);
        return (h > 0 ? h + ":" : "") + (h > 0 ? m.toString().padStart(2, '0') : m) + ":" + sec.toString().padStart(2, '0');
    }

    goBack() {
        if (window.videoSPA) {
            window.videoSPA.goBack();
        } else if (window.switchSection) {
            window.switchSection('details', {videoId: this.videoId});
        }
    }

    goToDetails() {
        if (window.videoSPA) {
            window.videoSPA.switchSection('details', {videoId: this.videoId});
        } else if (window.switchSection) {
            window.switchSection('details', {videoId: this.videoId});
        }
    }

    async loadSubtitles(providedTracks = null) {
        if (!this.videoId) return;
        try {
            let tracksData = providedTracks;
            
            if (!tracksData) {
                const res = await fetch(`/api/video/subtitles/${this.videoId}`);
                const data = await res.json();
                tracksData = data.tracks;
            }
            
            // Clear current tracks from the menu (keep "Off")
            while (this.subtitleList.children.length > 1) this.subtitleList.removeChild(this.subtitleList.lastChild);
            
            // Clear current tracks from the video element
            const tracks = this.video.querySelectorAll('track');
            tracks.forEach(t => t.remove());

            if (tracksData) {
                tracksData.forEach(t => {
                    const track = document.createElement('track');
                    const label = t.displayName || t.languageName || t.languageCode || 'Unknown';
                    Object.assign(track, { 
                        kind: 'subtitles', 
                        label: label, 
                        srclang: t.languageCode, 
                        src: `/api/video/subtitles/track/${t.id}`,
                        default: t.isDefault
                    });
                    this.video.appendChild(track);
                    
                    const opt = document.createElement('div');
                    opt.className = 'subtitle-option' + (t.isDefault ? ' selected' : '');
                    opt.innerText = label;
                    opt.onclick = (e) => { e.stopPropagation(); this.selectSubtitle(t.languageCode, opt); };
                    this.subtitleList.appendChild(opt);
                });
            }
        } catch (e) {
            console.error('[SimplePlayer] Error loading subtitles:', e);
        }
    }

    async triggerSubtitleAction(type, el) {
        this.subtitleMenu.classList.remove('active');
        
        if (type === 'download') {
            if (window.subtitleManager) {
                window.subtitleManager.openModal(this.videoId, this.container.dataset.title || 'Video', this.container.dataset.path || '');
            }
            return;
        }

        if (type === 'generate') {
            if (window.subtitleManager) {
                // We reuse the AI tab logic but initialize with current video
                window.subtitleManager.currentVideoId = this.videoId;
                window.subtitleManager.currentVideoTitle = this.container.dataset.title || 'Video';
                window.subtitleManager.generateAiSubtitles();
            }
            return;
        }
        
        // Legacy fallback
        const originalText = el.innerText;
        el.innerText = type === 'generate' ? 'Generating...' : 'Searching...';
        el.style.pointerEvents = 'none';
        el.style.opacity = '0.5';
        try {
            const url = `/api/video/subtitles/${this.videoId}/${type}`;
            const res = await fetch(url, { method: 'POST' });
            if (res.ok) {
                if (window.showToast) window.showToast(`${type === 'generate' ? 'Transcription' : 'Search'} started...`, 'info');
                setTimeout(() => { this.loadSubtitles(); el.innerText = originalText; el.style.pointerEvents = 'auto'; el.style.opacity = '1'; }, 10000);
            }
        } catch (e) { el.innerText = originalText; el.style.pointerEvents = 'auto'; el.style.opacity = '1'; }
    }

    selectSubtitle(lang, optEl) {
        this.subtitleMenu.querySelectorAll('.subtitle-option').forEach(el => el.classList.remove('selected'));
        optEl.classList.add('selected');
        this.subtitleMenu.classList.remove('active');
        for (let i = 0; i < this.video.textTracks.length; i++) {
            const t = this.video.textTracks[i];
            t.mode = (lang !== 'off' && (t.language === lang || t.label === lang)) ? 'showing' : 'disabled';
        }
    }

    adjustSubtitleOffset(seconds) {
        this.subOffset += seconds;
        if (this.offsetDisplay) {
            this.offsetDisplay.innerText = (this.subOffset > 0 ? '+' : '') + this.subOffset.toFixed(1) + 's';
        }

        // Real-time offset adjustment for active tracks
        for (let i = 0; i < this.video.textTracks.length; i++) {
            const track = this.video.textTracks[i];
            if (track.cues) {
                for (let j = 0; j < track.cues.length; j++) {
                    const cue = track.cues[j];
                    // We need to track original times to prevent cumulative drift errors if possible, 
                    // but for a simple implementation, we shift the start/end.
                    // Note: Browser support for modifying cues varies; some require re-adding.
                    cue.startTime += seconds;
                    cue.endTime += seconds;
                }
            }
        }
        
        console.log(`[SimplePlayer] Subtitle offset adjusted to: ${this.subOffset}s`);
    }

    resetSubtitleOffset() {
        // To properly reset without complex state, we reload the tracks
        this.subOffset = 0;
        if (this.offsetDisplay) this.offsetDisplay.innerText = '0.0s';
        this.loadSubtitles();
        console.log('[SimplePlayer] Subtitle offset reset');
    }
}

window.SimplePlayer = SimplePlayer;
