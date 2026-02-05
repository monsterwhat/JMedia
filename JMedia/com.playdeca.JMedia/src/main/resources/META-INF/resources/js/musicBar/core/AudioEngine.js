/**
 * AudioEngine - Audio element management with atomic operations
 * Handles audio element initialization, source management, and playback control
 */
(function(window) {
    'use strict';
    
    window.AudioEngine = {
        // Audio element reference
        audio: null,
        
        // Element ready flag
        audioElementReady: false,
        
        // Event listeners storage
        eventListeners: {},
        
        /**
         * Initialize audio engine
         */
        init: function() {
            this.initializeAudioElement();
            this.setupEventListeners();
            window.Helpers.log('AudioEngine initialized');
        },
        
        /**
         * Initialize audio element with retry logic
         */
        initializeAudioElement: function() {
            this.audio = document.getElementById('audioPlayer');
            
            // SAFETY: Verify audio element exists
            if (!this.audio) {
                console.error('[AudioEngine] audioPlayer element not found in DOM!');
                
                // Try to find it again after DOM is loaded
                setTimeout(() => {
                    const audioRetry = document.getElementById('audioPlayer');
                    if (audioRetry) {
                        window.Helpers.log('AudioEngine audioPlayer element found after delay');
                        this.audio = audioRetry;
                        this.audioElementReady = true;
                        this.setupAudioEvents();
                    } else {
                        console.error('[AudioEngine] audioPlayer element still not found after delay!');
                    }
                }, 1000);
            } else {
                window.Helpers.log('AudioEngine audioPlayer element found successfully');
                this.audioElementReady = true;
                this.setupAudioEvents();
            }
        },
        
        /**
         * Set up audio element event listeners
         */
        setupAudioEvents: function() {
            if (!this.audio) {
                return;
            }
            
            // Time update event
            this.audio.ontimeupdate = () => {
                if (!SynchronizationManager.getFlag('draggingSeconds') && 
                    !SynchronizationManager.getFlag('isUpdatingAudioSource')) {
                    
                    // Emit time update event instead of direct state update
                    window.dispatchEvent(new CustomEvent('audioTimeUpdate', {
                        detail: {
                            currentTime: this.audio.currentTime,
                            duration: this.audio.duration
                        }
                    }));
                    
                    // Request periodic state save
                    window.dispatchEvent(new CustomEvent('requestStateSave', {
                        detail: { includeCurrentTime: false }
                    }));
                }
            };
            
            // Audio ended event
            this.audio.onended = () => {
                window.Helpers.log('AudioEngine: Audio playback ended');
                
                // Trigger next song
                if (window.globalActiveProfileId) {
                    fetch(`/api/music/playback/next/${window.globalActiveProfileId}`, { method: 'POST' });
                }
            };
            
            // Metadata loaded event
            this.audio.onloadedmetadata = () => {
                window.Helpers.log('AudioEngine: Audio metadata loaded', {
                    duration: this.audio.duration,
                    readyState: this.audio.readyState
                });
                
                // Emit metadata loaded event
                window.dispatchEvent(new CustomEvent('audioMetadataLoaded', {
                    detail: {
                        duration: this.audio.duration,
                        readyState: this.audio.readyState
                    }
                }));
            };
            
            // Error event
            this.audio.onerror = (error) => {
                console.error('[AudioEngine] Audio error:', error);
                
                window.dispatchEvent(new CustomEvent('audioError', {
                    detail: { error: error }
                }));
            };
            
            window.Helpers.log('AudioEngine: Audio events configured');
        },
        
        /**
         * Set up event listeners for engine coordination
         */
        setupEventListeners: function() {
            // Listen for state changes to update audio volume
            window.addEventListener('statePropertyChanged', (e) => {
                if (e.detail.property === 'volume') {
                    this.setVolume(e.detail.newValue, 'stateManager');
                }
            });
            
            // Listen for audio control requests
            window.addEventListener('requestAudioControl', (e) => {
                this.handleControlRequest(e.detail);
            });
            
            window.Helpers.log('AudioEngine: Event listeners configured');
        },
        
        /**
         * Handle audio control requests
         * @param {Object} request - Control request details
         */
        handleControlRequest: function(request) {
            const { action, value, source } = request;
            
            switch (action) {
                case 'play':
                    this.play();
                    break;
                case 'pause':
                    this.pause();
                    break;
                case 'setTime':
                    this.setTime(value);
                    break;
                case 'setVolume':
                    this.setVolume(value, source);
                    break;
                case 'setSource':
                    this.setSource(value.source, value.prevSong, value.nextSong, value.play, value.backendTime);
                    break;
                default:
                    window.Helpers.log('AudioEngine: Unknown control request:', action);
            }
        },
        
        /**
         * Set audio source with atomic operation
         * @param {Object} currentSong - Current song data
         * @param {Object} prevSong - Previous song data
         * @param {Object} nextSong - Next song data
         * @param {boolean} play - Whether to start playback
         * @param {number} backendTime - Time to seek to
         * @returns {boolean} Success status
         */
        setSource: function(currentSong, prevSong = null, nextSong = null, play = true, backendTime = 0) {
            if (!window.SynchronizationManager) {
                window.Helpers.log('AudioEngine: SynchronizationManager not available');
                return false;
            }
            
            if (!window.globalActiveProfileId) {
                window.Helpers.log('AudioEngine: No active profile ID');
                return false;
            }
            
            // Use atomic operation for song switching
            return SynchronizationManager.executeAtomic('audio', window.globalActiveProfileId, (operationId) => {
                return this.performSetSource(operationId, currentSong, prevSong, nextSong, play, backendTime);
            });
        },
        
        /**
         * Perform the actual source setting operation
         * @param {string} operationId - Operation ID
         * @param {Object} currentSong - Current song data
         * @param {Object} prevSong - Previous song data
         * @param {Object} nextSong - Next song data
         * @param {boolean} play - Whether to start playback
         * @param {number} backendTime - Time to seek to
         */
        performSetSource: function(operationId, currentSong, prevSong, nextSong, play, backendTime) {
            // Set active operation and cancel any previous operation
            SynchronizationManager.setActiveAudioOperation(operationId);
            SynchronizationManager.setFlag('isUpdatingAudioSource', true);
            
            window.Helpers.log('AudioEngine performing setSource', {
                operationId: operationId,
                currentSongId: currentSong?.id,
                songTitle: currentSong?.title,
                play: play,
                backendTime: backendTime,
                profileId: window.globalActiveProfileId
            });
            
            // Validate current song
            if (!currentSong || !currentSong.id) {
                SynchronizationManager.clearActiveAudioOperation(operationId);
                SynchronizationManager.setFlag('isUpdatingAudioSource', false);
                return false;
            }
            
            // Ensure audio element is ready
            if (!this.audio || !this.audioElementReady) {
                window.Helpers.log('AudioEngine: Audio element not ready, reinitializing...');
                this.initializeAudioElement();
                
                // Retry after short delay
                setTimeout(() => {
                    if (this.audio && this.audioElementReady) {
                        this.performSetSource(operationId, currentSong, prevSong, nextSong, play, backendTime);
                    } else {
                        SynchronizationManager.clearActiveAudioOperation(operationId);
                        SynchronizationManager.setFlag('isUpdatingAudioSource', false);
                    }
                }, 500);
                return true; // Async operation started
            }
            
            const sameSong = String(window.StateManager?.getProperty('currentSongId')) === String(currentSong.id);
            const newAudioSrc = `/api/music/stream/${window.globalActiveProfileId}/${currentSong.id}`;
            
            console.log('🎵 AudioEngine: Setting source to:', newAudioSrc);
            console.log('🎵 AudioEngine: Song data:', {
                id: currentSong.id,
                title: currentSong.title,
                artist: currentSong.artist,
                profileId: window.globalActiveProfileId
            });
            
            // Update state
            window.dispatchEvent(new CustomEvent('requestStateUpdate', {
                detail: {
                    changes: {
                        currentSongId: currentSong.id,
                        songName: currentSong.title ?? "Unknown Title",
                        artist: currentSong.artist ?? window.StateManager?.getProperty('artist'),
                        currentTime: (sameSong || backendTime !== 0) ? (backendTime ?? 0) : 0,
                        duration: currentSong.durationSeconds ?? 0,
                        hasLyrics: currentSong.lyrics !== null && currentSong.lyrics !== undefined && currentSong.lyrics !== ''
                    },
                    source: 'audioEngine'
                }
            }));
            
            // Handle audio source change
            if (this.audio.src !== newAudioSrc) {
                // Clear audio buffer only when changing songs
                try {
                    this.audio.src = '';
                    this.audio.load(); // Clear buffer for song change
                } catch (error) {
                    // Silent cleanup failure - non-critical
                }
                
                window.Helpers.log('AudioEngine setting audio src to:', newAudioSrc);
                this.audio.src = newAudioSrc;
                this.audio.load();
            } else if (sameSong && play) {
                // Fast resume for same song - no source change needed
                if (this.audio.paused) {
                    this.audio.play().catch(console.error);
                }
                
                SynchronizationManager.clearActiveAudioOperation(operationId);
                SynchronizationManager.setFlag('isUpdatingAudioSource', false);
                return true;
            }
            
            // Set volume (use device volume)
            this.setVolume(null, 'deviceManager');
            
            // Set up metadata handling
            this.audio.onloadedmetadata = () => {
                window.Helpers.log('AudioEngine: Audio metadata loaded for setSource', {
                    duration: this.audio.duration,
                    readyState: this.audio.readyState
                });
                
                // Update duration if valid
                if (typeof this.audio.duration === 'number' && !isNaN(this.audio.duration) && this.audio.duration > 0) {
                    window.dispatchEvent(new CustomEvent('requestStateUpdate', {
                        detail: {
                            changes: { duration: this.audio.duration },
                            source: 'audioEngine'
                        }
                    }));
                }
                
                // Validate and set current time
                const currentState = window.StateManager?.getState();
                if (currentState && currentState.currentTime >= 0 && currentState.currentTime <= this.audio.duration) {
                    this.audio.currentTime = currentState.currentTime;
                }
                
                // Clear flags after successful metadata load
                SynchronizationManager.clearActiveAudioOperation(operationId);
                SynchronizationManager.setFlag('isUpdatingAudioSource', false);
            };
            
            // Set up canplay event for actual playback
            this.audio.oncanplay = () => {
                console.log('🎵 AudioEngine: Audio can play, readyState:', this.audio.readyState, 'src:', this.audio.src);
                
                // Handle playback when audio is ready to play
                if (play) {
                    console.log('🎵 AudioEngine: Attempting to play audio...');
                    this.audio.play().then(() => {
                        console.log('🎵 AudioEngine: Audio play successful');
                    }).catch(error => {
                        console.error('[AudioEngine] Audio play failed:', error);
                    });
                } else {
                    console.log('🎵 AudioEngine: Pausing audio...');
                    this.audio.pause();
                }
            };
            
            // Set up error handling
            this.audio.onerror = (error) => {
                console.error('[AudioEngine] Audio error:', {
                    error: error,
                    src: this.audio.src,
                    networkState: this.audio.networkState,
                    readyState: this.audio.readyState
                });
                
                SynchronizationManager.clearActiveAudioOperation(operationId);
                SynchronizationManager.setFlag('isUpdatingAudioSource', false);
            };
            
            return true;
        },
        
        /**
         * Play audio
         * @returns {Promise} Play promise
         */
        play: function() {
            if (this.audio) {
                return this.audio.play().catch(error => {
                    console.error('[AudioEngine] Play failed:', error);
                    throw error;
                });
            }
            return Promise.reject(new Error('Audio element not available'));
        },
        
        /**
         * Pause audio
         */
        pause: function() {
            if (this.audio) {
                this.audio.pause();
                window.Helpers.log('AudioEngine: Audio paused');
            }
        },
        
        /**
         * Set playback time
         * @param {number} time - Time in seconds
         */
        setTime: function(time) {
            if (this.audio) {
                const validTime = window.Helpers.safeNumber(time, 0);
                this.audio.currentTime = validTime;
                window.Helpers.log('AudioEngine: Time set to', validTime);
            }
        },
        
        /**
         * Set audio volume
         * @param {number} volume - Volume level (0-1)
         * @param {string} source - Source of volume change
         */
        setVolume: function(volume, source = 'unknown') {
            if (!this.audio) {
                return;
            }
            
            let vol = volume;
            
            // Use device volume if no volume provided
            if (vol === null && window.DeviceManager) {
                vol = window.DeviceManager.getDeviceVolume();
            }
            
            // Use fallback if no volume exists
            if (typeof vol !== 'number' || !isFinite(vol)) {
                vol = 0.8;
            }
            
            vol = window.Helpers.clamp(vol, 0, 1);
            
            // Update device volume if from user interaction
            if (source === 'user' && window.DeviceManager) {
                window.DeviceManager.saveDeviceVolume(vol);
            }
            
            // Apply to audio element
            this.audio.volume = vol;
            
            // Update state if from external source
            if (source !== 'stateManager' && window.StateManager) {
                window.dispatchEvent(new CustomEvent('requestStateUpdate', {
                    detail: {
                        changes: { volume: vol },
                        source: 'audioEngine'
                    }
                }));
            }
            
            window.Helpers.log('AudioEngine: Volume set to', vol, 'from', source);
        },
        
        /**
         * Get current playback time
         * @returns {number} Current time in seconds
         */
        getCurrentTime: function() {
            return this.audio ? this.audio.currentTime : 0;
        },
        
        /**
         * Get audio duration
         * @returns {number} Duration in seconds
         */
        getDuration: function() {
            return this.audio ? this.audio.duration : 0;
        },
        
        /**
         * Check if audio is playing
         * @returns {boolean} True if playing
         */
        isPlaying: function() {
            return this.audio ? !this.audio.paused : false;
        },
        
        /**
         * Check if audio is paused
         * @returns {boolean} True if paused
         */
        isPaused: function() {
            return this.audio ? this.audio.paused : true;
        },
        
        /**
         * Get audio element reference
         * @returns {HTMLAudioElement|null} Audio element
         */
        getAudioElement: function() {
            return this.audio;
        },
        
        /**
         * Get audio ready status
         * @returns {boolean} True if ready
         */
        isReady: function() {
            return this.audioElementReady && this.audio !== null;
        },
        
        /**
         * Get audio element information
         * @returns {Object} Audio element info
         */
        getAudioInfo: function() {
            return {
                element: this.audio,
                ready: this.audioElementReady,
                src: this.audio ? this.audio.src : null,
                currentTime: this.getCurrentTime(),
                duration: this.getDuration(),
                volume: this.audio ? this.audio.volume : 0,
                paused: this.isPaused(),
                readyState: this.audio ? this.audio.readyState : 0
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