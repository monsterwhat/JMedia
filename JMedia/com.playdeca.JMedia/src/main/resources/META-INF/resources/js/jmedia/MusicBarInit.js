(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    JMedia.MusicBarInit = {
        _moduleWaitStart: null,
        _moduleWaitTimeout: 10000,

        init: function() {
            this.verifyModules();
            this.initVideoDetection();
            this.initSyncLoop();
            this.initUIUpdateLoop();
            if (window.WebSocketManager && typeof window.WebSocketManager.connect === 'function') {
                window.WebSocketManager.connect();
            }

            this.initMusicBar();
        },

        verifyModules: function() {
            const modules = [
                'DeviceManager', 'StateManager', 'AudioEngine', 'WebSocketManager',
                'PlaybackController', 'TimeController', 'VolumeController',
                'UIUpdater', 'EventBindings', 'ImageManager', 'MobileBridge',
                'ResponsivePlayer', 'DesktopAdapter', 'MobileAdapter'
            ];
            const missing = modules.filter(m => !window[m]);
            if (missing.length > 0) {
                console.warn('[MusicBarInit] Missing modules:', missing.join(', '));
            } else {
                console.log('[MusicBarInit] All musicBar modules loaded');
            }
        },

        initVideoDetection: function() {
            setTimeout(this.checkVideoPageState, 500);

            document.body.addEventListener('htmx:afterSwap', () => {
                setTimeout(this.checkVideoPageState, 100);
            });
            document.body.addEventListener('htmx:afterSettle', () => {
                setTimeout(this.checkVideoPageState, 100);
            });
            setInterval(this.checkVideoPageState, 1000);
        },

        checkVideoPageState: function() {
            const playerContainer = document.querySelector('.player-container');
            const videoElement = document.getElementById('videoElement');
            const customPlayer = document.getElementById('customPlayer');
            const isActivePlayer = playerContainer || videoElement || customPlayer;
            const isVideoPath = window.location.pathname.startsWith('/video');
            const musicPlayer = document.querySelector('.persistent-music-player') ||
                               document.querySelector('.mobile-player') ||
                               document.getElementById('musicPlayerContainer');

            if (isActivePlayer || isVideoPath) {
                // Suspend DJ activity when entering video section
                if (!window.videoPlaying && window.DjTransitionManager) {
                    window.DjTransitionManager.suspendForVideo();
                }
                window.videoPlaying = true;
                document.body.classList.add('video-active');
                if (musicPlayer) {
                    musicPlayer.classList.add('video-active');
                    musicPlayer.style.setProperty('display', 'none', 'important');
                }
                // Only pause audio when an actual player is present (not just browsing)
                const audio = JMedia.PlaybackApi.getAudioElement();
                if (audio && !audio.paused) {
                    audio.pause();
                    JMedia.PlaybackApi.pause();
                }
            } else if (window.videoPlaying === true) {
                window.videoPlaying = false;
                document.body.classList.remove('video-active');
                document.body.setAttribute('data-video-active', 'false');
                if (musicPlayer) {
                    musicPlayer.classList.remove('video-active');
                    musicPlayer.style.removeProperty('display');
                }
            }
        },

        initSyncLoop: function() {
            setInterval(function() {
                if (document.hidden) return;
                const audio = JMedia.PlaybackApi.getAudioElement();
                if (!audio || audio.paused) return;
                if (window.StateManager && typeof window.StateManager.getState === 'function') {
                    const state = window.StateManager.getState();
                    if (state && state.currentTime !== undefined && state.lastUpdate) {
                        const elapsed = (Date.now() - state.lastUpdate) / 1000;
                        const estimatedServerNow = state.currentTime + elapsed;
                        const drift = estimatedServerNow - audio.currentTime;
                        if (Math.abs(drift) > 3.0) {
                            audio.currentTime = estimatedServerNow;
                        } else if (Math.abs(drift) > 0.3) {
                            audio.playbackRate = drift > 0 ? 1.02 : 0.98;
                        } else {
                            audio.playbackRate = 1.0;
                        }
                    }
                }
            }, 1000);
        },

        initUIUpdateLoop: function() {
            setInterval(function() {
                if (window.videoPlaying === true) {
                    const player = document.getElementById('musicPlayerContainer');
                    if (player && player.style.display !== 'none') {
                        player.style.setProperty('display', 'none', 'important');
                    }
                    const audio = JMedia.PlaybackApi.getAudioElement();
                    if (audio && !audio.paused) {
                        audio.pause();
                    }
                }
            }, 250);
        },

        initMusicBar: function() {
            const deps = [
                'Helpers', 'DeviceManager', 'SynchronizationManager', 'StateManager',
                'StatePersistence', 'AudioEngine', 'DjTransitionManager', 'WebSocketManager',
                'ActionTracker', 'PlaybackController', 'VolumeController', 'TimeController',
                'UIUpdater', 'EventBindings', 'ImageManager', 'SongContextCache', 'QueueManager'
            ];

            const missing = deps.filter(m => !window[m]);
            if (missing.length > 0) {
                if (!this._moduleWaitStart) {
                    this._moduleWaitStart = Date.now();
                    console.log('[MusicBarInit] Waiting for modules:', missing.join(', '));
                }
                if (Date.now() - this._moduleWaitStart > this._moduleWaitTimeout) {
                    console.error('[MusicBarInit] Timeout waiting for modules, continuing');
                } else {
                    setTimeout(() => this.initMusicBar(), 50);
                    return;
                }
            }

            if (window.StatePersistence) {
                window.StatePersistence.initializeWithRestored();
            }

            this.syncDjSettingsFromServer();

            this.setupGlobalAPI();
            this.initializeAudioElement();
            this.startAudioEngine();
            this.bindLegacyEvents();
        },

        /**
         * Re-fetch DJ settings from the server for the active profile and apply
         * them to StateManager. The WebSocket 'state' payload deliberately omits
         * DJ settings (they are @JsonIgnore'd server-side), so without this the
         * UI can keep another profile's settings (or stale defaults) after a
         * profile switch / page reload. The settings panel refreshes on open,
         * but StateManager-driven UI needs them from startup.
         */
        syncDjSettingsFromServer: function() {
            if (!window.StateManager) return;
            const pid = (window.StatePersistence && typeof window.StatePersistence.getProfileId === 'function')
                ? window.StatePersistence.getProfileId()
                : (window.globalActiveProfileId || localStorage.getItem('activeProfileId'));
            if (!pid) return;

            fetch(`/api/music/playback/dj-settings/${pid}`, { credentials: 'include' })
                .then((r) => (r.ok ? r.json() : null))
                .then((raw) => {
                    if (!raw || !window.StateManager) return;
                    const payload = raw.data && typeof raw.data === 'object' ? raw.data : raw;
                    const changes = {};
                    if (Array.isArray(payload.genrePool)) changes.djGenrePool = payload.genrePool.slice();
                    if (typeof payload.songsPerGenre === 'number' && isFinite(payload.songsPerGenre)) changes.djSongsPerGenre = payload.songsPerGenre;
                    if (typeof payload.crossfade === 'number' && isFinite(payload.crossfade)) changes.djCrossfadeOverride = payload.crossfade;
                    if (typeof payload.strictness === 'string' && payload.strictness) changes.djStrictness = payload.strictness;
                    if (typeof payload.bpmMin === 'number' && isFinite(payload.bpmMin)) changes.djBpmMin = payload.bpmMin;
                    if (typeof payload.bpmMax === 'number' && isFinite(payload.bpmMax)) changes.djBpmMax = payload.bpmMax;
                    if (typeof payload.maxConsecutiveByArtist === 'number' && isFinite(payload.maxConsecutiveByArtist)) changes.djMaxConsecutiveByArtist = payload.maxConsecutiveByArtist;
                    if (typeof payload.skipsBeforeGenreChange === 'number' && isFinite(payload.skipsBeforeGenreChange)) changes.djSkipsBeforeGenreChange = payload.skipsBeforeGenreChange;
                    if (typeof payload.yearMin === 'number' && isFinite(payload.yearMin)) changes.djYearMin = payload.yearMin;
                    if (typeof payload.yearMax === 'number' && isFinite(payload.yearMax)) changes.djYearMax = payload.yearMax;
                    // djModeActive is intentionally NOT set here: the WebSocket
                    // 'state' message is the authoritative channel for it and is
                    // delivered on connect; this sync only covers the settings
                    // that WS never carries.
                    window.StateManager.updateState(changes, 'djSettingsServerSync');
                    window.Helpers.log('MusicBarInit: DJ settings synced from server for profile', pid);
                })
                .catch(() => {
                    // Non-fatal: WS still delivers djModeActive; the panel
                    // re-fetches settings when opened.
                });
        },

        setupGlobalAPI: function() {
            window.initEventBindings = function() {
                if (window.EventBindings) {
                    window.EventBindings.bindPlaybackButtons();
                }
            };
        },

        initializeAudioElement: function() {
            if (window.AudioEngine) {
                window.AudioEngine.init();
            }
            if (window.DjTransitionManager) {
                window.DjTransitionManager.init();
            }
        },

        startAudioEngine: function() {
            if (!window.AudioEngine || !window.AudioEngine.isReady || !window.AudioEngine.isReady()) {
                setTimeout(() => this.startAudioEngine(), 100);
                return;
            }

            const currentState = window.StateManager ? window.StateManager.getState() : null;
            if (currentState && currentState.currentSongId) {
                window.AudioEngine.setSource({
                    id: currentState.currentSongId,
                    title: currentState.songName,
                    artist: currentState.artist,
                    duration: currentState.duration
                }, null, null, currentState.playing, currentState.currentTime || 0);
                
                // Initialize Media Session metadata for the restored song so media keys
                // and OS lock-screen controls have valid metadata immediately.
                // Normally this is set on 'songChanged', which doesn't fire on page load.
                if (window.updateMediaSessionMetadata) {
                    const artworkUrl = currentState.currentSongData && currentState.currentSongData.artworkBase64
                        ? 'data:image/jpeg;base64,' + currentState.currentSongData.artworkBase64
                        : '/logo.png';
                    window.updateMediaSessionMetadata(currentState.songName, currentState.artist, artworkUrl);
                }
            }
        },

        bindLegacyEvents: function() {
            window.addEventListener('songChanged', (e) => {
                const state = e.detail.state;
                if (state && state.currentSongId && window.AudioEngine) {
                    window.AudioEngine.setSource({
                        id: state.currentSongId,
                        title: state.songName,
                        artist: state.artistName,
                        duration: state.duration
                    }, null, null, state.playing, state.currentTime || 0);
                }

                if (window.updateMediaSessionMetadata && state && state.currentSongId) {
                    const artworkUrl = state.currentSongData && state.currentSongData.artworkBase64
                        ? 'data:image/jpeg;base64,' + state.currentSongData.artworkBase64
                        : '/logo.png';
                    window.updateMediaSessionMetadata(state.songName, state.artistName, artworkUrl);
                }
            });

            window.addEventListener('queueChanged', () => {
                if (window.StateManager && window.updateQueueCurrentSong) {
                    window.updateQueueCurrentSong(window.StateManager.getProperty('currentSongId'));
                }
            });

            window.addEventListener('statePropertyChanged', (e) => {
                if (e.detail.property === 'playing' && window.updateMediaSessionPlaybackState) {
                    window.updateMediaSessionPlaybackState(e.detail.newValue);
                }
            });

            // iOS-specific: registering Media Session play/pause action handlers
            // causes the page to be suspended ~30s after screen lock. Without
            // handlers iOS manages the <audio> element natively for continuous
            // background playback, which is the behavior we want.
            if (window.setupMediaSessionHandlers) {
                const isIOS = window.PlayerUtils
                    ? window.PlayerUtils.isIOS()
                    : /iPhone|iPad|iPod|iPadOS/i.test(navigator.userAgent) ||
                      (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1) ||
                      (navigator.platform === 'iPhone' || navigator.platform === 'iPad');
                if (!isIOS) {
                    window.setupMediaSessionHandlers(null, null, null);
                }
            }
        }
    };

    window.setVideoPlaying = function(active) {
        window.videoPlaying = active;
        const player = document.getElementById('musicPlayerContainer');
        if (active) {
            if (player) player.style.setProperty('display', 'none', 'important');
            if (JMedia.PlaybackApi.isPlaying()) {
                const audio = JMedia.PlaybackApi.getAudioElement();
                if (audio && !audio.paused) audio.pause();
                JMedia.PlaybackApi.pause();
            }
        } else {
            if (player) player.style.setProperty('display', 'flex', 'important');
        }
    };

    window.checkVideoPageState = JMedia.MusicBarInit.checkVideoPageState;

    document.addEventListener('DOMContentLoaded', function() {
        JMedia.MusicBarInit.init();
    });

})(window);
