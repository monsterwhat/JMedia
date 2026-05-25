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
                    // Skip auto-next if this song already transitioned via DJ mode
                    // The song keeps playing after crossfade starts, but we don't want
                    // its natural end to trigger another 'next' call
                    if (el._djTransitioned) {
                        console.log('[AudioEngine] Skipping auto-next - song already transitioned via DJ');
                        el._djTransitioned = false; // Reset for reuse
                        return;
                    }
                    
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
                
                const existingHandler = nextPlayer.onloadedmetadata;
                nextPlayer.onloadedmetadata = () => {
                    if (existingHandler) existingHandler.call(nextPlayer);
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
            
            // Mark current player as DJ-transitioned so its onended doesn't trigger auto-next
            // This prevents the old song's natural end from skipping to next after crossfade
            currentPlayer._djTransitioned = true;
            
            // ENSURE the next player has the correct source if not already preloaded
            if (songId) {
                const url = `/api/music/stream/${profileId}/${songId}`;
                if (nextPlayer.src.indexOf(url) === -1) {
                    window.Helpers.log('AudioEngine: Loading ' + songId + ' during crossfade');
                    nextPlayer.src = url;
                }
            }

            // USE USER'S CURRENT VOLUME SETTING directly - not the gain node which might be stale
            // CRITICAL: Use a LOW default to prevent volume spikes. If we can't read the user's
            // actual volume setting, we should fade in quietly, not loudly!
            let targetVolume = 0.3; // Safe low default - user can increase if needed
            if (window.StateManager) {
                const stateVol = window.StateManager.getProperty('volume');
                console.log('[AudioEngine crossfade] StateManager.volume =', stateVol, 'type:', typeof stateVol);
                if (typeof stateVol === 'number' && stateVol > 0 && stateVol <= 1) {
                    targetVolume = stateVol;
                }
            }
            
            console.log('[AudioEngine crossfade] targetVolume from user setting:', targetVolume);

            window.Helpers.log('AudioEngine: Starting crossfade (' + fadeTime + 's) to entry ' + entryTime + 's');

            // Store entryTime for later use
            const seekTime = entryTime || 0;
            
            // 1. Prepare next player state - set entry time BEFORE playing
            if (nextPlayer.readyState > 0) {
                nextPlayer.currentTime = seekTime;
            } else {
                const existingHandler = nextPlayer.onloadedmetadata;
                nextPlayer.onloadedmetadata = () => {
                    if (existingHandler) existingHandler.call(nextPlayer);
                    nextPlayer.currentTime = seekTime;
                };
            }

            // 2. Schedule volume ramps - PREVENT ANY VOLUME SPIKE
            // CRITICAL: The combined volume must NEVER exceed targetVolume
            // Both players must start at 0 to guarantee this, otherwise currentVol (which can be
            // higher than user's current targetVolume) causes momentary volume spike
            if (this.ctx) {
                const now = this.ctx.currentTime;
                
                // CRITICAL: Also reset the HTML5 audio element volumes to prevent them from
                // contributing independently to the output
                console.log('[AudioEngine crossfade] BEFORE - currentPlayer.volume:', currentPlayer.volume, 'nextPlayer.volume:', nextPlayer.volume);
                console.log('[AudioEngine crossfade] BEFORE - currentGain.gain:', currentGain.gain.value, 'nextGain.gain:', nextGain.gain.value);
                
                // Set element volumes - current player muted (cut), next player at targetVolume
                // Web Audio gain nodes will fade from 0 to targetVolume during transition
                currentPlayer.volume = 0;
                nextPlayer.volume = targetVolume;  // Use user's actual volume level
                
                // Cancel any pending events
                currentGain.gain.cancelScheduledValues(now);
                nextGain.gain.cancelScheduledValues(now);
                
                // CRITICAL FIX: Start BOTH at 0, not the old currentVol
                // This guarantees combined volume never exceeds targetVolume at any point
                currentGain.gain.setValueAtTime(0, now);
                nextGain.gain.setValueAtTime(0, now);
                
                // Fade out: 0 -> 0 (no change to fade out track)
                // Fade in: 0 -> targetVolume
                currentGain.gain.linearRampToValueAtTime(0, now + fadeTime);
                nextGain.gain.linearRampToValueAtTime(targetVolume, now + fadeTime);
                
                console.log('[AudioEngine crossfade] AFTER - targetVolume=' + targetVolume + ', using zero-start crossfade');
                console.log('[AudioEngine crossfade] Schedule: currentGain 0 -> 0, nextGain 0 -> ' + targetVolume + ' over ' + fadeTime + 's');
            } else {
                // Fallback: HTML5 Volume Fading (Mobile/Safari)
                // CRITICAL: Both must start at 0 to prevent volume spike
                currentPlayer.volume = 0;
                nextPlayer.volume = 0;
                
                const steps = 40;
                const interval = (fadeTime * 1000) / steps;
                
                let currentStep = 0;
                const fadeInterval = setInterval(() => {
                    currentStep++;
                    
                    // Simple linear crossfade to targetVolume
                    const progress = currentStep / steps;
                    
                    // Both start at 0 - old player stays at 0, new fades in to targetVolume
                    currentPlayer.volume = 0;
                    nextPlayer.volume = targetVolume * progress;
                    
                    if (currentStep >= steps) {
                        clearInterval(fadeInterval);
                    }
                }, interval);
            }

            // 3. Execute play and swap
            // Verify entry time is set correctly before playing
            const actualTime = nextPlayer.currentTime || 0;
            if (Math.abs(actualTime - seekTime) > 1) {
                console.log('[AudioEngine] Correcting entry time: was', actualTime, 's, seeking to', seekTime, 's');
                nextPlayer.currentTime = seekTime;
            }
            
            nextPlayer.play().then(() => {
                setTimeout(() => {
                    currentPlayer.pause();
                    currentPlayer.src = '';
                    
                    if (this.ctx) {
                        // CRITICAL: Cancel any ongoing gain curves before setting new values
                        const now = this.ctx.currentTime;
                        currentGain.gain.cancelScheduledValues(now);
                        currentGain.gain.setValueAtTime(0, now);
                        
                        // Also explicitly cancel nextGain curves
                        nextGain.gain.cancelScheduledValues(now);
                    } else {
                        currentPlayer.volume = 0;
                        nextPlayer.volume = targetVolume;
                    }

                    this.activePlayer = (this.activePlayer === 1 ? 2 : 1);
                    window.Helpers.log('AudioEngine: Crossfade finalized');
                    
                    const newPlayer = this.getActivePlayer();
                    const newGain = this.getActiveGain();
                    
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
                        window.DjTransitionManager.updateDjIndicator('complete');
                    }
                    
                    // FINAL STATE UPDATE: Use seekTime (entry position), not currentTime after playing
                    // The currentTime has been advancing during the fade, so we must use the original entry position
                    if (window.StateManager) {
                        window.StateManager.updateState({ 
                            duration: newPlayer.duration || 0,
                            currentTime: seekTime  // Use entry time, not currentTime after fade
                        }, 'audioEngine');
                        window.Helpers.log('AudioEngine: Final state sync complete. Duration: ' + newPlayer.duration + ', time: ' + seekTime);
                    }
                    
                    // FIX: Ensure volume is consistent after crossfade using the targetVolume we mixed into
                    // MUST set both the gain node AND the HTML5 audio element volume
                    if (this.ctx) {
                        // Get current time first to cancel all future events
                        const now = this.ctx.currentTime;
                        
                        // Cancel any remaining curves and set exact target value
                        newGain.gain.cancelScheduledValues(now);
                        newGain.gain.setValueAtTime(targetVolume, now);
                        newGain.gain.value = targetVolume; // Force immediate value as well
                        
                        // Also set HTML5 element volume
                        newPlayer.volume = targetVolume;
                        
                        window.Helpers.log('AudioEngine: Set gain to ' + targetVolume + ', player volume to ' + targetVolume);
                    } else {
                        newPlayer.volume = targetVolume;
                    }
                    
                    // Also update StateManager's volume to match what we set (ensure consistency)
                    if (window.StateManager) {
                        window.StateManager.updateState({ volume: targetVolume }, 'audioEngine');
                    }
                    
                    // Force sync the slider to the correct position
                    if (window.TimeController) {
                        window.TimeController.updateSliderFromState(seekTime, newPlayer.duration);
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
            // CROSSFADE GUARD: Don't allow volume updates to interrupt a DJ mix
            if (window.SynchronizationManager && window.SynchronizationManager.getFlag('isCrossfading')) {
                window.Helpers.log('AudioEngine: Ignoring setVolume during active crossfade');
                return;
            }

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
            const player = this.getActivePlayer();
            console.log('[AudioEngine pause] Calling pause on player:', player.id, 'currently playing:', !player.paused);
            player.pause();
            console.log('[AudioEngine pause] After pause, isPaused:', player.paused);
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
                
                // SKIP VOLUME RESTORE DURING CROSSFADE - gain nodes are controlled by crossfade curves
                // Setting volume during an active curve throws "Can't add events during a curve event"
                if (!window.SynchronizationManager || !window.SynchronizationManager.getFlag('isCrossfading')) {
                    // RESTORE VOLUME ON LOAD
                    let currentVol = 0.8;
                    if (window.DeviceManager && window.DeviceManager.hasDeviceVolume()) {
                        currentVol = window.DeviceManager.getDeviceVolume();
                    } else if (window.StateManager) {
                        currentVol = window.StateManager.getProperty('volume');
                    }
                    gain.gain.value = currentVol;
                    player.volume = currentVol;
                }
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
            if (window.SynchronizationManager) {
                const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
                window.SynchronizationManager.executeAtomic('audio', profileId, (opId) => {
                    return this.performSetSource(opId, currentSong, null, null, true, seekTime);
                });
                return;
            }
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

            // CROSSFADE GUARD: Check BOTH players. During a crossfade, the next song 
            // is likely already loaded in the inactive player.
            if (window.SynchronizationManager.getFlag('isCrossfading')) {
                const activePlayer = this.getActivePlayer();
                const inactivePlayer = this.getInactivePlayer();
                const targetUrl = this.getStreamUrl(currentSong.id);
                
                if ((activePlayer && activePlayer.src.indexOf(targetUrl) !== -1) ||
                    (inactivePlayer && inactivePlayer.src.indexOf(targetUrl) !== -1)) {
                    window.Helpers.log('AudioEngine: Ignoring setSource during active crossfade for song ' + currentSong.id);
                    return true;
                }
            }

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
        },
        
        /**
         * Create a Float32Array for smooth volume curves
         * @param {number} startVol - Starting volume
         * @param {number} endVol - Ending volume
         * @param {number} duration - Duration in seconds
         * @param {string} type - 'out' for fade out, 'in' for fade in
         * @returns {Float32Array} Audio curve array
         */
        _createFadeCurve: function(startVol, endVol, duration, type) {
            const sampleRate = this.ctx.sampleRate;
            const samples = Math.ceil(duration * sampleRate);
            const curve = new Float32Array(samples);
            
            for (let i = 0; i < samples; i++) {
                const t = i / samples;
                // Use sine-based curves for natural constant power crossfade
                // Fade out: cos(π/2 * t) goes from 1 to 0
                // Fade in: sin(π/2 * t) goes from 0 to 1
                if (type === 'out') {
                    curve[i] = startVol * Math.cos((Math.PI / 2) * t);
                } else {
                    curve[i] = endVol * Math.sin((Math.PI / 2) * t);
                }
            }
            return curve;
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
