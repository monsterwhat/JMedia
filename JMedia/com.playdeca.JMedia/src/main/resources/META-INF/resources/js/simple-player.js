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
            this.needsConversion = this.container.dataset.needsConversion === 'true';
            this._canNativeHevc = false;
            // Browser-native HEVC override: if the server flagged transcode but the
            // browser can play HEVC natively (e.g. Chrome with HEVC Video Extensions),
            // skip the server-side FFmpeg transcode and request the lightweight remux.
            const codec = (this.container.dataset.videoCodec || '').toLowerCase();
            if ((this.needsTranscode || this.needsConversion) && (codec.includes('hevc') || codec.includes('h265') || codec.includes('hvc1') || codec.includes('hev1'))) {
                if (window.PlayerStreamManager && window.PlayerStreamManager.hasNativeHevcSupport()) {
                    this.needsTranscode = false;
                    this.needsConversion = false;
                    this._canNativeHevc = true;
                    console.log('[SimplePlayer] Browser supports HEVC natively — skipping server transcode');
                }
            }
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

            this._initWebSocket();

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
                    this.subtitleController.loadSubtitles();
                    this.video.play().then(() => {
                    }).catch(e => {
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
                    this.video.addEventListener('error', this._setupStreamErrorHandler);
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
                this.subtitleController.loadSubtitles();
                // F2a: Attach error handler for plain direct stream (was only on transcode path)
                var self = this;
                var _directStreamUrl = this.video.src;
                this._setupStreamErrorHandler = function() {
                    if (self._destroyed || !document.body.contains(self.container)) return;
                    self._streamFallbackCount = (self._streamFallbackCount || 0) + 1;
                    console.error('[SimplePlayer] Direct stream error (fallback ' + self._streamFallbackCount + '/' + self._maxStreamFallbacks + '):', self.video.error);
                    if (self._streamFallbackCount < self._maxStreamFallbacks) {
                        self._showLoading('Stream error, retrying...');
                        setTimeout(function() {
                            if (self._destroyed || !document.body.contains(self.container)) return;
                            self.streamStartOffset = 0;
                            self.video.src = _directStreamUrl;
                            self.video.load();
                            self.video.play().catch(function() {});
                        }, 1000);
                    } else {
                        self._showLoading('Playback failed - try reloading');
                    }
                };
                this.video.addEventListener('error', this._setupStreamErrorHandler);
                this.video.play().then(() => {
                }).catch(e => {
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

        _initWebSocket() {
            if (!window.VideoWebSocketManager) return;
            const profileId = this.container.dataset.profileId || localStorage.getItem('activeProfileId');
            if (!profileId) return;

            this._wsManager = new window.VideoWebSocketManager({
                profileId: profileId,
                onStateUpdate: (state) => {
                    if (!state || this._destroyed) return;
                    this._applyingServerState = true;
                    try {
                        // Source-reload: if server selected a different video, reinit player for the new source
                        if (state.currentVideoId && state.currentVideoId.toString() !== this.videoId.toString()) {
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
                            this.streamStartOffset = (this.needsTranscode && newStateTime > 0) ? Math.max(0, newStateTime) : 0;
                            this.lastKnownGoodPosition = 0;
                            this.initialResumeTime = 0;

                            // Restore volume/mute from localStorage
                            const vol = parseFloat(localStorage.getItem('jmedia_video_volume_' + profileId) || '0.7');
                            const mute = localStorage.getItem('jmedia_video_mute_' + profileId) === 'true';
                            this.video.volume = vol;
                            this.video.muted = mute;

                            // Resume: selectVideo → state.currentTime > 0 → seek the new source there;
                            // advanceVideo → server sets 0 → no seek. Direct files seek client-side;
                            // transcode was positioned by ?start= (streamStartOffset > 0) (B10).
                            this.video.addEventListener('loadedmetadata', () => {
                                if (newStateTime > 0 && this.streamStartOffset === 0 && this.video && !this._destroyed) {
                                    try { this.video.currentTime = newStateTime; } catch (e) {}
                                }
                            }, { once: true });

                            // Suppression exit: playing/loadeddata/error, or the 10s safety timeout.
                            // On playing, send ONE confirmation broadcast so the server playback
                            // timer restarts with the new id (B6) — it now passes the swap guard.
                            this.video.addEventListener('playing', () => {
                                this._clearSwapInProgress();
                                if (!this._destroyed) this._broadcastState();
                            }, { once: true });
                            this.video.addEventListener('loadeddata', () => this._clearSwapInProgress(), { once: true });
                            this.video.addEventListener('error', () => this._clearSwapInProgress(), { once: true });

                            // Load the new source through the StreamManager retry machinery
                            // (cache-busted ?trace=, bounded error retry as on initial load).
                            // Pass newStateTime so transcode resumes via server-side ?start=.
                            this.streamMgr.initDirectStream(newStateTime);
                            if (!state.playing) {
                                try { this.video.pause(); } catch (e) {}
                            }
                            return;  // still hits the finally block
                        }
                        if (state.playing && this.video.paused) {
                            this.video.play().catch(() => {});
                        } else if (!state.playing && !this.video.paused) {
                            this.video.pause();
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
                                if (drift > 3 && (!locked || converged)) {
                                    // Bound to loaded duration (OPlayer parity): seeking a
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
                    console.log('[SimplePlayer onCommand]', msg.type, msg.payload);
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
                                this.streamMgr.setAudioTrack(tracks[idx].id);
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
                        if (this.video.paused) this.video.play().catch(() => {});
                        else this.video.pause();
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
        }

        _broadcastState() {
            if (!this._wsManager || !this._wsManager.connected || this._applyingServerState || this._swapInProgress) return;

            const profileId = this.container.dataset.profileId || localStorage.getItem('activeProfileId');
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
            this.progressReporter.stop();
            this.progressReporter.setMusicSuspended(false);
            this.video.pause();
            this.video.src = "";
            this.video.load();
            window.removeEventListener('keydown', this._boundKeydown);
        }
    };
}
