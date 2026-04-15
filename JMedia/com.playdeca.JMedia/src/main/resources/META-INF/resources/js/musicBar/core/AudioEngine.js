/**
 * AudioEngine - Audio element management with atomic operations
 * Handles audio element initialization, source management, and playback control
 */
(function(window) {
    'use strict';
    
    window.AudioEngine = {
        // Audio elements
        audio: null,
        audioNext: null,
        activePlayer: 1,
        
        // Web Audio API components
        ctx: null,
        nodes: {
            p1: null, // Source node for audio
            p2: null, // Source node for audioNext
            g1: null, // Gain node for audio
            g2: null  // Gain node for audioNext
        },
        
        audioElementReady: false,
        autoplayBlocked: false,
        autoplayAttempted: false,
        
        init: function() {
            if (this.initialized) return;
            this.initialized = true;
            
            this.initializeAudioElements();
            this.initWebAudio();
            this.setupEventListeners();
            window.Helpers.log('AudioEngine: Web Audio initialized (idempotent)');
            
            // Re-load if state exists
            setTimeout(() => {
                if (window.StateManager) {
                    const state = window.StateManager.getState();
                    if (state && state.currentSongId && state.playing) {
                        this.loadAudioSourceOnly({
                            id: String(state.currentSongId),
                            title: state.songName,
                            artist: state.artist,
                            duration: state.duration
                        }, state.currentTime || 0);
                    }
                }
            }, 500);
        },

        initWebAudio: function() {
            if (this.ctx) return;

            // Skip Web Audio on mobile to ensure reliable background playback (especially on iOS)
            // AudioContext is often suspended when the app goes to the background/phone is locked
            const isMobile = window.DeviceManager ? window.DeviceManager.shouldUseMobileUI() : false;
            if (isMobile) {
                window.Helpers.log('AudioEngine: Skipping Web Audio on mobile for background playback reliability');
                return;
            }
            
            try {
                this.ctx = new (window.AudioContext || window.webkitAudioContext)();
                
                // Ensure elements have crossOrigin set before creating source nodes
                this.audio.crossOrigin = 'anonymous';
                this.audioNext.crossOrigin = 'anonymous';
                
                // Create source nodes from audio elements
                this.nodes.p1 = this.ctx.createMediaElementSource(this.audio);
                this.nodes.p2 = this.ctx.createMediaElementSource(this.audioNext);
                
                // Create gain nodes
                this.nodes.g1 = this.ctx.createGain();
                this.nodes.g2 = this.ctx.createGain();
                
                // Connect: Source -> Gain -> Destination
                this.nodes.p1.connect(this.nodes.g1);
                this.nodes.g1.connect(this.ctx.destination);
                
                this.nodes.p2.connect(this.nodes.g2);
                this.nodes.g2.connect(this.ctx.destination);
                
                // Initial volumes - PRIORITIZE DEVICE MANAGER
                let initialVol = 0.8;
                if (window.DeviceManager && window.DeviceManager.hasDeviceVolume()) {
                    initialVol = window.DeviceManager.getDeviceVolume();
                } else if (window.StateManager) {
                    initialVol = window.StateManager.getProperty('volume');
                }
                
                this.nodes.g1.gain.value = initialVol;
                this.nodes.g2.gain.value = 0.0;
                
                window.Helpers.log('AudioEngine: Web Audio nodes connected successfully with initial volume ' + initialVol);
                
                // Resume on user interaction
                const resume = () => {
                    if (this.ctx.state === 'suspended') {
                        this.ctx.resume().then(() => {
                            window.Helpers.log('AudioEngine: AudioContext resumed by user interaction');
                            document.removeEventListener('click', resume);
                            document.removeEventListener('keydown', resume);
                        });
                    }
                };
                document.addEventListener('click', resume);
                document.addEventListener('keydown', resume);
            } catch (e) {
                console.error('AudioEngine: Web Audio API initialization failed', e);
            }
        },

        initializeAudioElements: function() {
            this.audio = document.getElementById('audioPlayer');
            this.audioNext = document.getElementById('audioPlayerNext');
            
            if (!this.audioNext) {
                this.audioNext = document.createElement('audio');
                this.audioNext.id = 'audioPlayerNext';
                this.audioNext.crossOrigin = 'anonymous'; // Critical for Web Audio
                document.body.appendChild(this.audioNext);
            }
            
            this.audio.crossOrigin = 'anonymous';
            this.audioElementReady = true;
            this.setupAudioEvents(this.audio);
            this.setupAudioEvents(this.audioNext);
        },

        setupAudioEvents: function(el) {
            el.onended = () => {
                if (el === this.getActivePlayer()) {
                    // Get profile ID - must have a valid one
                    let profileId = null;
                    if (window.StateManager && window.StateManager.getProperty('profileId')) {
                        profileId = window.StateManager.getProperty('profileId');
                    } else if (window.globalActiveProfileId) {
                        profileId = window.globalActiveProfileId;
                    } else {
                        profileId = localStorage.getItem('activeProfileId');
                    }
                    
                    if (profileId && profileId !== 'undefined') {
                        fetch(`/api/music/playback/next/${profileId}`, { method: 'POST' });
                    }
                }
            };
            
            el.onerror = (e) => {
                console.error('AudioEngine: Element error', el.id, e);
            };
        },

        getActivePlayer: function() {
            return this.activePlayer === 1 ? this.audio : this.audioNext;
        },

        getActiveGain: function() {
            return this.activePlayer === 1 ? this.nodes.g1 : this.nodes.g2;
        },

        getInactivePlayer: function() {
            return this.activePlayer === 1 ? this.audioNext : this.audio;
        },

        getInactiveGain: function() {
            return this.activePlayer === 1 ? this.nodes.g2 : this.nodes.g1;
        },

        /**
         * Preload next song for crossfade (HTML5 audio fallback)
         * Note: WebAudio preload is preferred - this is fallback
         */
        preload: function(songId, startTime = 0) {
            // Get profile ID - must have a valid one, no defaults
            let profileId = null;
            if (window.StateManager && window.StateManager.getProperty('profileId')) {
                profileId = window.StateManager.getProperty('profileId');
            } else if (window.globalActiveProfileId) {
                profileId = window.globalActiveProfileId;
            } else {
                profileId = localStorage.getItem('activeProfileId');
            }
            
            // Don't attempt to load if profileId is not valid - wait for it
            if (!profileId || profileId === 'undefined') {
                console.log('[AudioEngine preload] Waiting for valid profileId...');
                const self = this;
                setTimeout(function() {
                    self.preload(songId, startTime);
                }, 200);
                return;
            }
            
            // Don't attempt to load if songId is not valid
            if (!songId || songId === 'null' || songId === 'undefined') {
                console.log('[AudioEngine preload] Invalid songId, skipping preload');
                return;
            }
            
            const nextPlayer = this.getInactivePlayer();
            const url = `/api/music/stream/${profileId}/${songId}`;
            
            if (nextPlayer.src.indexOf(url) === -1) {
                window.Helpers.log('AudioEngine: Preloading ' + songId + ' at ' + startTime + 's');
                nextPlayer.src = url;
                nextPlayer.load(); // Start buffering
                
                nextPlayer.onloadedmetadata = () => {
                    nextPlayer.currentTime = startTime;
                };
            }
        },

        crossfadeTo: function(songId, entryTime, duration) {
            if (this.ctx && this.ctx.state === 'suspended') this.ctx.resume();
            
            const currentPlayer = this.getActivePlayer();
            const nextPlayer = this.getInactivePlayer();
            const currentGain = this.getActiveGain();
            const nextGain = this.getInactiveGain();
            const fadeTime = duration || 8;
            const now = this.ctx ? this.ctx.currentTime : 0;
            
            // Get profile ID - must have a valid one
            let profileId = null;
            if (window.StateManager && window.StateManager.getProperty('profileId')) {
                profileId = window.StateManager.getProperty('profileId');
            } else if (window.globalActiveProfileId) {
                profileId = window.globalActiveProfileId;
            } else {
                profileId = localStorage.getItem('activeProfileId');
            }
            
            // Don't proceed if profileId is not valid
            if (!profileId || profileId === 'undefined') {
                console.log('[AudioEngine crossfadeTo] Waiting for valid profileId...');
                const self = this;
                setTimeout(function() {
                    self.crossfadeTo(songId, entryTime, duration);
                }, 200);
                return;
            }
            
            // Don't proceed if songId is not valid
            if (!songId || songId === 'null' || songId === 'undefined') {
                console.log('[AudioEngine crossfadeTo] Invalid songId, skipping');
                return;
            }
            
            // ENSURE the next player has the correct source if not already preloaded
            if (songId) {
                const url = `/api/music/stream/${profileId}/${songId}`;
                if (nextPlayer.src.indexOf(url) === -1) {
                    window.Helpers.log('AudioEngine: Loading ' + songId + ' during crossfade');
                    nextPlayer.src = url;
                }
            }

            // USE ACTUAL CURRENT VOLUME as target
            const targetVolume = window.StateManager ? window.StateManager.getProperty('volume') : 0.8;
            
            console.log('[AudioEngine crossfade] targetVolume:', targetVolume, 'state volume:', window.StateManager ? window.StateManager.getProperty('volume') : 'N/A');

            window.Helpers.log('AudioEngine: Starting WebAudio crossfade (' + fadeTime + 's) to entry ' + entryTime + 's');

            // 1. Prepare next player state
            if (nextPlayer.readyState > 0) {
                nextPlayer.currentTime = entryTime || 0;
            } else {
                nextPlayer.onloadedmetadata = () => {
                    nextPlayer.currentTime = entryTime || 0;
                };
            }

            // 2. Schedule volume ramps
            if (this.ctx) {
                currentGain.gain.setValueAtTime(currentGain.gain.value, now);
                currentGain.gain.exponentialRampToValueAtTime(0.0001, now + fadeTime);
                
                nextGain.gain.setValueAtTime(0.0001, now);
                nextGain.gain.exponentialRampToValueAtTime(targetVolume || 0.0001, now + fadeTime);
            }

            // 3. Execute play and swap
            nextPlayer.play().then(() => {
                setTimeout(() => {
                    currentPlayer.pause();
                    currentPlayer.src = '';
                    if (this.ctx) currentGain.gain.value = 0;
                    this.activePlayer = (this.activePlayer === 1 ? 2 : 1);
                    window.Helpers.log('AudioEngine: Crossfade finalized');
                    
                    // Clear crossfade flag to re-enable progress bar updates
                    if (window.SynchronizationManager) {
                        window.SynchronizationManager.setFlag('isCrossfading', false);
                    }
                    
                    // Update DJ indicator back to active (transition complete)
                    if (window.DjTransitionManager) {
                        window.DjTransitionManager.transitionPrepared = false;
                        window.DjTransitionManager.isTransitioning = false;
                        window.DjTransitionManager.transitionData = null;
                        // Clear the monitor timer
                        if (window.DjTransitionManager.monitorTimer) {
                            clearInterval(window.DjTransitionManager.monitorTimer);
                            window.DjTransitionManager.monitorTimer = null;
                        }
                        window.DjTransitionManager.updateDjIndicator('active');
                    }
                    
                    // FIX: Update state with NEW song's duration after crossfade
                    const newDuration = nextPlayer.duration || 0;
                    if (window.StateManager && newDuration > 0) {
                        window.StateManager.updateState({ duration: newDuration }, 'audioEngine');
                        window.Helpers.log('AudioEngine: Updated duration to ' + newDuration);
                    }
                    
                    // Ensure volume is consistent after crossfade
                    const currentVol = window.StateManager ? window.StateManager.getProperty('volume') : 0.8;
                    if (this.getActiveGain()) {
                        this.getActiveGain().gain.value = currentVol;
                    }
                    
                    // Request state save after crossfade completes to persist any volume changes
                    window.dispatchEvent(new CustomEvent('requestStateSave', {
                        detail: { source: 'audioEngine', includeCurrentTime: false }
                    }));
                }, (fadeTime + 0.1) * 1000);
            }).catch(e => {
                console.error('AudioEngine: Crossfade play failed', e);
                this.activePlayer = (this.activePlayer === 1 ? 2 : 1);
                if (window.SynchronizationManager) {
                    window.SynchronizationManager.setFlag('isCrossfading', false);
                }
                nextPlayer.play().catch(() => {});
            });
        },

        setVolume: function(volume, source = 'unknown') {
            const gain = this.getActiveGain();
            
            let vol = volume;
            // Use saved volume if none provided
            if (vol === null || vol === undefined) {
                if (window.DeviceManager && window.DeviceManager.hasDeviceVolume()) {
                    vol = window.DeviceManager.getDeviceVolume();
                } else if (window.StateManager) {
                    vol = window.StateManager.getProperty('volume');
                }
            }
            
            if (typeof vol !== 'number') vol = 0.8;
            vol = window.Helpers.clamp(vol, 0, 1);
            
            // Apply to Web Audio if available
            if (gain && this.ctx) {
                const safeVol = Math.max(0.0001, vol);
                gain.gain.setValueAtTime(gain.gain.value, this.ctx.currentTime);
                gain.gain.linearRampToValueAtTime(safeVol, this.ctx.currentTime + 0.1);
            }
            
            // ALWAYS apply to element as well for visual/sync purposes
            const player = this.getActivePlayer();
            if (player) {
                player.volume = vol;
            }
            
            if (source !== 'stateManager' && window.StateManager) {
                window.dispatchEvent(new CustomEvent('requestStateUpdate', {
                    detail: { changes: { volume: vol }, source: 'audioEngine' }
                }));
            }
        },

        // Rest of the wrapper methods use getActivePlayer()
        play: function() {
            if (this.ctx && this.ctx.state === 'suspended') this.ctx.resume();
            return this.getActivePlayer().play();
        },
        
        pause: function() {
            this.getActivePlayer().pause();
        },

        getCurrentTime: function() { return this.getActivePlayer()?.currentTime || 0; },
        getDuration: function() { return this.getActivePlayer()?.duration || 0; },
        isPlaying: function() { return !this.getActivePlayer()?.paused; },
        isPaused: function() { return !!this.getActivePlayer()?.paused; },
        getAudioElement: function() { return this.getActivePlayer(); },
        
        performSetSource: function(operationId, currentSong, prevSong, nextSong, play, backendTime) {
            SynchronizationManager.setActiveAudioOperation(operationId);
            SynchronizationManager.setFlag('isUpdatingAudioSource', true);
            
            const player = this.getActivePlayer();
            const gain = this.getActiveGain();
            const url = `/api/music/stream/${window.globalActiveProfileId || localStorage.getItem('activeProfileId') || 1}/${currentSong.id}`;
            
            if (player.src !== url) {
                player.src = url;
                player.load();
                
                // RESTORE VOLUME ON LOAD
                let currentVol = 0.8;
                if (window.DeviceManager && window.DeviceManager.hasDeviceVolume()) {
                    currentVol = window.DeviceManager.getDeviceVolume();
                } else if (window.StateManager) {
                    currentVol = window.StateManager.getProperty('volume');
                }
                gain.gain.value = currentVol;
                player.volume = currentVol;
            } else if (play && player.paused) {
                player.play();
            }

            player.onloadedmetadata = () => {
                const seekTime = backendTime > 0 ? backendTime : 0;
                player.currentTime = seekTime;
                
                window.dispatchEvent(new CustomEvent('requestStateUpdate', {
                    detail: {
                        changes: {
                            currentSongId: currentSong.id,
                            songName: currentSong.title,
                            artist: currentSong.artist,
                            duration: player.duration,
                            currentTime: seekTime
                        },
                        source: 'audioEngine'
                    }
                }));
                
                SynchronizationManager.clearActiveAudioOperation(operationId);
                SynchronizationManager.setFlag('isUpdatingAudioSource', false);
            };

            if (play) {
                player.play().catch(() => { this.autoplayBlocked = true; });
            }
            
            return true;
        },

        loadAudioSourceOnly: function(currentSong, seekTime) {
            const player = this.getActivePlayer();
            const url = `/api/music/stream/${window.globalActiveProfileId || localStorage.getItem('activeProfileId') || 1}/${currentSong.id}`;
            player.src = url;
            player.onloadedmetadata = () => {
                player.currentTime = seekTime;
                player.play().catch(() => {});
            };
        },

        setupEventListeners: function() {
            window.addEventListener('statePropertyChanged', (e) => {
                if (e.detail.property === 'volume') this.setVolume(e.detail.newValue, 'stateManager');
                if (e.detail.property === 'currentSongId' && e.detail.newValue && window.StateManager) {
                    const state = window.StateManager.getState();
                    if (String(e.detail.newValue) !== String(e.detail.oldValue)) {
                        this.setSource({
                            id: e.detail.newValue,
                            title: state.songName,
                            artist: state.artist,
                            duration: state.duration
                        }, null, null, state.playing, state.currentTime || 0);
                    }
                }
            });
            window.addEventListener('requestAudioControl', (e) => this.handleControlRequest(e.detail));
        },

        handleControlRequest: function(request) {
            const { action, value, source } = request;
            switch (action) {
                case 'play': this.play(); break;
                case 'pause': this.pause(); break;
                case 'setTime': this.setTime(value); break;
                case 'setVolume': this.setVolume(value, source); break;
                case 'setSource': this.setSource(value.source, value.prevSong, value.nextSong, value.play, value.backendTime); break;
            }
        },

        setSource: function(currentSong, prevSong = null, nextSong = null, play = true, backendTime = 0) {
            const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
            if (!window.SynchronizationManager) return false;
            return SynchronizationManager.executeAtomic('audio', profileId, (opId) => {
                return this.performSetSource(opId, currentSong, prevSong, nextSong, play, backendTime);
            });
        },

        resetAutoplayState: function() { this.autoplayBlocked = false; this.autoplayAttempted = false; },

        setTime: function(time) {
            const player = this.getActivePlayer();
            if (player) player.currentTime = window.Helpers.safeNumber(time, 0);
        },

        getStreamUrl: function(songId) {
            return `/api/music/stream/${window.globalActiveProfileId || localStorage.getItem('activeProfileId') || 1}/${songId}`;
        },

        preloadSong: function(songId, startTime) {
            this.preload(songId, startTime);
            return this.getInactivePlayer();
        },

        isReady: function() { return this.audioElementReady && this.getActivePlayer() !== null; },

        getAudioInfo: function() {
            const p = this.getActivePlayer();
            const gain = this.getActiveGain();
            return {
                ready: this.audioElementReady,
                src: p ? p.src : null,
                currentTime: this.getCurrentTime(),
                duration: this.getDuration(),
                volume: gain ? gain.gain.value : (p ? p.volume : 0),
                paused: this.isPaused(),
                activePlayer: this.activePlayer
            };
        }
    };
    
    // Auto-initialize when dependencies are available
    if (window.Helpers && window.SynchronizationManager && window.StateManager) {
        window.AudioEngine.init();
    } else {
        // Wait for dependencies
        const checkDeps = () => {
            if (window.Helpers && window.SynchronizationManager && window.StateManager) {
                window.AudioEngine.init();
            } else {
                setTimeout(checkDeps, 50);
            }
        };
        setTimeout(checkDeps, 50);
    }
    
})(window);
