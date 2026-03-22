/**
 * musicBar.js - Main orchestrator maintaining exact API compatibility
 * Coordinates all modules and provides the original global API
 */
(function (window) {
    'use strict';

    // Fallback apiPost - defined immediately so inline onclick handlers always work
    // Will be replaced by the full version once modules are ready
    if (!window.apiPost) {
        window.apiPost = async (path, arg2 = null) => {
            const defaultProfileId = window.globalActiveProfileId || '1';
            let url;
            if (typeof path === 'string' && path.startsWith('select/')) {
                const parts = path.split('/');
                if (parts.length === 3) {
                    url = `/api/music/playback/select/${parts[1]}/${parts[2]}`;
                } else {
                    url = `/api/music/playback/select/${defaultProfileId}/${parts[1]}`;
                }
            } else if (path === 'select' && arg2) {
                url = `/api/music/playback/select/${defaultProfileId}/${arg2}`;
            } else {
                url = `/api/music/playback/${path}/${defaultProfileId}`;
            }
            return fetch(url, {method: 'POST'});
        };
    }

    // Delegated click handler for song items - set up immediately
    document.addEventListener('click', (e) => {
        const songItem = e.target.closest('.mobile-song-item, tr[data-song-id]');
        if (songItem && !e.defaultPrevented) {
            const songId = songItem.dataset.songId;
            if (songId && window.apiPost) {
                window.apiPost('select', songId);
            }
        }
    });

    // Module initialization tracking
    const modules = {
        helpers: false,
        deviceManager: false,
        synchronizationManager: false,
        stateManager: false,
        statePersistence: false,
        audioEngine: false,
        webSocketManager: false,
        actionTracker: false,
        playbackController: false,
        volumeController: false,
        timeController: false,
        uiUpdater: false,
        eventBindings: false,
        imageManager: false,
        songContextCache: false,
        queueManager: false
    };

    /**
     * Initialize musicBar system
     */
    function initialize() {
        try {
            // Ensure Helpers is available before using it
            if (!window.Helpers) {
                console.error('[musicBar] Critical error: window.Helpers is not available');
                return;
            }

            window.Helpers.log('[musicBar] Starting initialization...');

            // Check dependencies
            if (!checkDependencies()) {
                return; // Dependencies not ready yet
            }

            // Initialize restored state
            if (modules.statePersistence && window.StatePersistence) {
                const restoredState = window.StatePersistence.initializeWithRestored();
                if (restoredState) {
                    window.Helpers.log('[musicBar] Initialized with restored state:', restoredState);
                }
            }

            // Setup global API compatibility
            setupGlobalAPI();

            // Initialize and start audio engine
            initializeAudioElement();
            startAudioEngine();

            // Bind legacy event listeners
            bindLegacyEvents();

            window.Helpers.log('[musicBar] Initialization complete');
        } catch (error) {
            console.error('[musicBar] Initialization error:', error);
        }
    }

    /**
     * Check if all dependencies are available
     */
    function checkDependencies() {
        // First ensure Helpers is available before using it
        if (!window.Helpers) {
            console.error('[musicBar] window.Helpers is not available. Check script loading order.');
            setTimeout(checkDependencies, 100);
            return false;
        }

        const deps = [
            {name: 'Helpers', obj: window.Helpers, module: modules.helpers},
            {name: 'DeviceManager', obj: window.DeviceManager, module: modules.deviceManager},
            {name: 'SynchronizationManager', obj: window.SynchronizationManager, module: modules.synchronizationManager},
            {name: 'StateManager', obj: window.StateManager, module: modules.stateManager},
            {name: 'StatePersistence', obj: window.StatePersistence, module: modules.statePersistence},
            {name: 'AudioEngine', obj: window.AudioEngine, module: modules.audioEngine},
            {name: 'WebSocketManager', obj: window.WebSocketManager, module: modules.webSocketManager},
            {name: 'ActionTracker', obj: window.ActionTracker, module: modules.actionTracker},
            {name: 'PlaybackController', obj: window.PlaybackController, module: modules.playbackController},
            {name: 'VolumeController', obj: window.VolumeController, module: modules.volumeController},
            {name: 'TimeController', obj: window.TimeController, module: modules.timeController},
            {name: 'UIUpdater', obj: window.UIUpdater, module: modules.uiUpdater},
            {name: 'EventBindings', obj: window.EventBindings, module: modules.eventBindings},
            {name: 'ImageManager', obj: window.ImageManager, module: modules.imageManager},
            {name: 'SongContextCache', obj: window.SongContextCache, module: modules.songContextCache},
            {name: 'QueueManager', obj: window.QueueManager, module: modules.queueManager}
        ];

        const missing = deps.filter(dep => !dep.obj);
        if (missing.length > 0) {
            window.Helpers.log('[musicBar] Missing dependencies:', missing.map(m => m.name));
            setTimeout(checkDependencies, 100);
            return false;
        }

        // Mark all as available
        deps.forEach(dep => {
            modules[dep.name] = true;
        });

        return true;
    }

    /**
     * Set up global API to maintain compatibility
     */
    function setupGlobalAPI() {
        // Expose state globally
        window.musicState = window.StateManager.getState();

        // Expose audio element globally
        window.audio = window.AudioEngine.getAudioElement();

        // Expose API functions
        window.sendWS = function (type, payload) {
            window.WebSocketManager.send(type, payload);
        };

        // Expose UpdateAudioSource (legacy compatibility)
        window.UpdateAudioSource = function (currentSong, prevSong, nextSong, play, backendTime) {
            return window.AudioEngine.setSource(currentSong, prevSong, nextSong, play, backendTime);
        };

        // Expose updateAudioSource (lowercase, legacy compatibility)
        window.updateAudioSource = function (songId, play = true) {
            return window.AudioEngine.setSource({id: songId}, null, null, play, 0);
        };

        // Expose UpdateAudioSourceSequentially (for player.js compatibility)
        window.UpdateAudioSourceSequentially = function (currentSong, prevSong, nextSong, play, backendTime) {
            return window.AudioEngine.setSource(currentSong, prevSong, nextSong, play, backendTime);
        };

        // Expose setPlaybackTime
        window.setPlaybackTime = function (newTime, fromClient = false) {
            if (fromClient) {
                window.TimeController.handleSeek(newTime);
            } else {
                window.TimeController.handleTimeChange(newTime);
        }
        };

        // Expose addSongToPlaylist (for mobile.js compatibility) - FIXED SIGNATURE
        window.addSongToPlaylist = function (playlistId, songId) {
            if (!playlistId || !songId) {
                window.Helpers.log('[musicBar] Both playlistId and songId required', 'error');
                return Promise.reject(new Error('Missing parameters'));
            }

            window.Helpers.log('[musicBar] addSongToPlaylist called:', {playlistId, songId});
            // Use HTTP API for user actions
            return fetch(`/api/music/playlists/${playlistId}/songs/${songId}`, {
                method: 'POST'
            });
        };

        // Expose rescanSong (for mobile.js compatibility)
        window.rescanSong = function (songId) {
            window.Helpers.log('[musicBar] rescanSong called for song: ' + songId);
            // Legacy API - send via WebSocket
            window.WebSocketManager.send('rescanLibrary', {});
        };

        // Expose apiPost function for mobile.js compatibility (HTTP API preferred)
        window.apiPost = async (path, arg2 = null) => {
            const defaultProfileId = window.globalActiveProfileId || '1';
            let url;

            if (typeof path === 'string' && path.startsWith('select/')) {
                const parts = path.split('/');
                if (parts.length === 3) {
                    // Format: select/{profileId}/{songId}
                    url = `/api/music/playback/select/${parts[1]}/${parts[2]}`;
                } else {
                    // Format: select/{songId}
                    const songId = parts[1];
                    url = `/api/music/playback/select/${defaultProfileId}/${songId}`;
                }
            } else if (path === 'select' && arg2) {
                // Template format: apiPost('select', songId)
                url = `/api/music/playback/select/${defaultProfileId}/${arg2}`;
            } else {
                // Actions (toggle, next, previous, shuffle, repeat)
                const profileId = arg2 || defaultProfileId;
                url = `/api/music/playback/${path}/${profileId}`;
            }

            return fetch(url, {method: 'POST'});
        };

        // Expose deleteSong function
        window.deleteSong = function (songId) {
            if (!songId) {
                window.Helpers.log('[musicBar] Song ID required for deletion', 'error');
                return Promise.reject(new Error('Song ID required'));
            }

            window.Helpers.log('[musicBar] deleteSong (HTTP):', songId);
            return fetch(`/api/music/songs/${songId}`, {
                method: 'DELETE'
            });
        };

        // Queue management functions (from original songQueue.js)
        window.updateQueueCount = function (totalSize) {
            window.Helpers.log('[musicBar] updateQueueCount called:', totalSize);
            const queueCountSpan = document.getElementById('queueCount');
            if (queueCountSpan) {
                queueCountSpan.textContent = totalSize;
            }
        };

        window.updateQueueCurrentSong = function (songId) {
            window.Helpers.log('[musicBar] updateQueueCurrentSong:', songId);
            // Use HTTP API to change current song
            return window.apiPost(`select/${songId}`, window.globalActiveProfileId);
        };

        // Playback button binding function
        window.bindPlaybackButtons = function () {
            window.Helpers.log('[musicBar] bindPlaybackButtons called');
            if (window.EventBindings) {
                window.EventBindings.bindPlaybackButtons();
            }
        };

        // Alias for app.js compatibility
        window.initEventBindings = window.bindPlaybackButtons;

        // Keep global variables in sync
        setInterval(() => {
            window.musicState = window.StateManager.getState();
        }, 1000);

        // Expose last refreshed song ID (for queue management)
        window.lastRefreshedSongId = null;

        window.Helpers.log('[musicBar] Global API configured');
    }

    /**
     * Bind legacy event listeners
     */
    function bindLegacyEvents() {
        // Listen for audio metadata loaded
        window.addEventListener('audioMetadataLoaded', (e) => {
            window.Helpers.log('[musicBar] Audio metadata loaded:', e.detail);
        });

        // Listen for WebSocket events
        window.addEventListener('websocketConnected', (e) => {
            window.Helpers.log('[musicBar] WebSocket connected:', e.detail);
        });

        window.addEventListener('websocketDisconnected', (e) => {
            window.Helpers.log('[musicBar] WebSocket disconnected:', e.detail);
        });

        // Listen for song changes - load audio source
        window.addEventListener('songChanged', (e) => {
            window.Helpers.log('[musicBar] Song changed:', e.detail);
            window.lastRefreshedSongId = e.detail.newSongId;
            
            const state = e.detail.state;
            if (state && state.currentSongId && window.AudioEngine) {
                const currentSong = {
                    id: state.currentSongId,
                    title: state.songName,
                    artist: state.artistName,
                    duration: state.duration
                };
                window.AudioEngine.setSource(currentSong, null, null, state.playing, state.currentTime || 0);
            }

            // Update media session metadata
            if (window.updateMediaSessionMetadata && state) {
                let artworkUrl = null;
                const songData = window.musicState?.currentSongData;
                if (songData && songData.artworkBase64) {
                    artworkUrl = `data:image/jpeg;base64,${songData.artworkBase64}`;
                } else if (state.currentSongId) {
                    // Fallback to API endpoint for cover art
                    artworkUrl = `/api/music/cover/${state.currentSongId}`;
                }
                window.updateMediaSessionMetadata(state.songName, state.artistName, artworkUrl);
            }
        });

        // Listen for queue changes
        window.addEventListener('queueChanged', (e) => {
            window.Helpers.log('[musicBar] Queue changed:', e.detail);
            if (window.updateQueueCurrentSong) {
                window.updateQueueCurrentSong(window.StateManager.getProperty('currentSongId'));
            }
        });

        // Listen for playback state changes (playing/paused)
        window.addEventListener('statePropertyChanged', (e) => {
            if (e.detail.property === 'playing' && window.updateMediaSessionPlaybackState) {
                window.updateMediaSessionPlaybackState(e.detail.newValue);
            }
        });

        // Initialize Media Session API handlers
        if (window.setupMediaSessionHandlers) {
            window.setupMediaSessionHandlers(window.apiPost, window.setPlaybackTime, window.audio);
            window.Helpers.log('[musicBar] Media Session handlers initialized');
        }
    }

    /**
     * Initialize audio element with retry logic
     */
    function initializeAudioElement() {
        if (!window.AudioEngine) {
            console.error('[musicBar] AudioEngine not available');
            return;
        }

        window.AudioEngine.init();
    }

    /**
     * Start audio engine and load initial audio
     */
    function startAudioEngine() {
        if (!window.AudioEngine.isReady()) {
            window.Helpers.log('[musicBar] Audio engine not ready, waiting...');
            setTimeout(startAudioEngine, 100);
            return;
        }

        // Initialize with current state, restore playback position
        const currentState = window.StateManager.getState();
        window.Helpers.log('[musicBar] Starting audio engine with state:', {
            songId: currentState.currentSongId,
            playing: currentState.playing,
            currentTime: currentState.currentTime
        });
        window.AudioEngine.setSource({
            id: currentState.currentSongId,
            title: currentState.songName,
            artist: currentState.artist,
            duration: currentState.duration
        }, null, null, currentState.playing, currentState.currentTime || 0);
    }

    /**
     * Main initialization
     */
    function main() {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', initialize);
        } else {
            initialize();
        }
    }

    /**
     * Initialize when modules are ready
     */
    function waitForModules() {
        if (checkDependencies()) {
            main();
        } else {
            // Check for timeout to prevent infinite loop
            if (!waitForModules.startTime) {
                waitForModules.startTime = Date.now();
                waitForModules.timeout = 10000; // 10 seconds timeout
            }

            if (Date.now() - waitForModules.startTime > waitForModules.timeout) {
                console.error('[musicBar] Timeout waiting for modules to load');
                // Try to continue with what we have
                if (window.Helpers) {
                    window.Helpers.log('[musicBar] Continuing with available modules');
                    main();
                } else {
                    console.error('[musicBar] Cannot initialize without Helpers module');
                }
                return;
            }

            setTimeout(waitForModules, 50);
        }
    }

    // Start initialization
    waitForModules();

})(window);