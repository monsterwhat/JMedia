if (typeof window.SimplePlayer === 'undefined') {
    window.SimplePlayer = class SimplePlayer {
        constructor(config) {
            console.log('[SimplePlayer] Initializing...', config);
            this.container = document.getElementById(config.containerId);
            this.video = document.getElementById(config.videoId);
            this.videoId = config.currentVideoId;
            this.videoType = this.container.dataset.type || 'movie';
            this.utils = window.PlayerUtils;

            if (!this.container || !this.video) return;

            this.video.setAttribute('playsinline', 'true');
            this.video.setAttribute('webkit-playsinline', 'true');
            this.video.controls = false;
            this.video.preload = 'auto';

            this.needsTranscode = this.container.dataset.needsTranscode === 'true';
            this._canNativeHevc = false;
            // Browser-native HEVC override: if the server flagged transcode but the
            // browser can play HEVC natively (e.g. Chrome with HEVC Video Extensions),
            // skip the server-side FFmpeg transcode and request the lightweight remux.
            const codec = (this.container.dataset.videoCodec || '').toLowerCase();
            if (this.needsTranscode && (codec.includes('hevc') || codec.includes('h265'))) {
                if (window.PlayerStreamManager && window.PlayerStreamManager.hasNativeHevcSupport()) {
                    this.needsTranscode = false;
                    this._canNativeHevc = true;
                    console.log('[SimplePlayer] Browser supports HEVC natively — skipping server transcode');
                }
            }
            this.streamStartOffset = 0;
            this.lastKnownGoodPosition = 0;

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

            this.externalUrl = this.container.dataset.externalUrl || null;
            this.externalOriginalUrl = this.container.dataset.externalOriginalUrl || null;
            this.externalId = this.container.dataset.externalId || null;
            if (this.externalUrl) {
                this.streamMgr.initExternalStream();
                return;
            }

            const savedTime = parseFloat(this.container.dataset.startTime || 0);
            const _traceId = () => `${Date.now()}_${Math.random().toString(36).slice(2,8)}`;
            if (this.needsTranscode) {
                const qualityParam = this._preferredQuality > 0 ? `&quality=${this._preferredQuality}` : '';
                const setupStream = () => {
                    if (savedTime > 0) {
                        this.streamStartOffset = savedTime;
                        this.video.src = `/api/video/stream/${this.videoId}.mp4?start=${savedTime}${qualityParam}&trace=${_traceId()}`;
                    } else {
                        this.streamStartOffset = 0;
                        this.video.src = `/api/video/stream/${this.videoId}.mp4?trace=${_traceId()}`;
                    }
                    if (PlayerUtils.isIOS()) console.debug('[iOS-DEBUG] Setting video src:', this.video.src);
                    this.subtitleController.loadSubtitles();
                    if (PlayerUtils.isIOS()) console.debug('[iOS-DEBUG] Calling play()');
                    this.video.play().then(() => {
                        if (PlayerUtils.isIOS()) console.debug('[iOS-DEBUG] play() resolved successfully');
                    }).catch(e => {
                        if (PlayerUtils.isIOS()) console.debug('[iOS-DEBUG] play() rejected:', e.message);
                        console.log('[SimplePlayer] Play requires user gesture:', e);
                    });
                };
                const setupStreamErrorHandler = () => {
                    if (this._destroyed || !document.body.contains(this.container)) return;
                    this._streamFallbackCount = (this._streamFallbackCount || 0) + 1;
                    console.error('[SimplePlayer] Direct stream error (fallback ' + this._streamFallbackCount + '/' + this._maxStreamFallbacks + '):', this.video.error);
                    if (this._streamFallbackCount < this._maxStreamFallbacks) {
                        this._showLoading('Stream error, retrying...');
                        setTimeout(() => {
                            if (this._destroyed || !document.body.contains(this.container)) return;
                            setupStream();
                        }, 1000);
                    } else {
                        this._showLoading('Playback failed - try reloading');
                    }
                };
                this._setupStreamErrorHandler = setupStreamErrorHandler;
                this.streamMgr._preloadSubtitleTracks().then(() => {
                    this.video.addEventListener('error', this._setupStreamErrorHandler, { once: true });
                    setupStream();
                });
            } else {
                /* Load from beginning; client-side seek in loadedmetadata handles resume.
                   Server-side ?start= can fail for direct streams, causing the progress bar
                   to show the resume time while the video is actually at 0:00. */
                this.streamStartOffset = 0;
                const params = [];
                if (this._canNativeHevc) params.push('nativeHevc=1');
                params.push(`trace=${_traceId()}`);
                this.video.src = `/api/video/stream/${this.videoId}.mp4?${params.join('&')}`;
                if (PlayerUtils.isIOS()) console.debug('[iOS-DEBUG] Setting video src:', this.video.src);
                this.subtitleController.loadSubtitles();
                if (PlayerUtils.isIOS()) console.debug('[iOS-DEBUG] Calling play()');
                this.video.play().then(() => {
                    if (PlayerUtils.isIOS()) console.debug('[iOS-DEBUG] play() resolved successfully');
                }).catch(e => {
                    if (PlayerUtils.isIOS()) console.debug('[iOS-DEBUG] play() rejected:', e.message);
                    console.log('[SimplePlayer] Play requires user gesture:', e);
                });
            }

            this.storyboardMgr.loadStoryboard();
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
            this.audioTrackSelector.init();
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

        destroy() {
            this._destroyed = true;
            this.subtitleController.destroyAssSubtitle();
            clearInterval(this._prog);
            this.progressReporter.setMusicSuspended(false);
            this.video.pause();
            this.video.src = "";
            this.video.load();
            window.removeEventListener('keydown', this._boundKeydown);
        }
    };
}
