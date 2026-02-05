/**
 * PlaybackController - Main playback control logic
 * Handles play/pause, previous/next, and shuffle/repeat
 */
(function(window) {
    'use strict';
    
    window.PlaybackController = {
        // Pause start time for buffer management
        pauseStartTime: null,
        
        // Buffer warm duration
        BUFFER_WARM_DURATION: 5000,
        
        /**
         * Initialize playback controller
         */
        init: function() {
            this.setupEventListeners();
            window.Helpers.log('PlaybackController initialized');
        },
        
        /**
         * Set up event listeners
         */
        setupEventListeners: function() {
            // Listen for playback control requests
            window.addEventListener('requestPlaybackControl', (e) => {
                this.handleControlRequest(e.detail);
            });
            
            window.Helpers.log('PlaybackController event listeners configured');
        },
        
        /**
         * Handle playback control requests
         * @param {Object} request - Control request
         */
        handleControlRequest: function(request) {
            const { action, profileId } = request;
            
            switch (action) {
                case 'playPause':
                    this.togglePlayPause(profileId);
                    break;
                case 'previous':
                    this.previousSong(profileId);
                    break;
                case 'next':
                    this.nextSong(profileId);
                    break;
                case 'shuffle':
                    this.cycleShuffleMode(profileId);
                    break;
                case 'repeat':
                    this.cycleRepeatMode(profileId);
                    break;
                default:
                    window.Helpers.log('PlaybackController: Unknown control request:', action);
            }
        },
        
        /**
         * Toggle play/pause with optimistic updates
         * @param {string} profileId - Profile ID
         */
        togglePlayPause: function(profileId) {
            if (!window.ActionTracker || !window.StateManager || !window.AudioEngine) {
                window.Helpers.log('PlaybackController: Dependencies not available');
                return false;
            }
            
            // Record local action BEFORE optimistic update
            window.ActionTracker.recordAction('playPause');
            
            // Race condition mitigation: store previous state for rollback
            const previousPlayingState = window.StateManager.getProperty('playing');
            
            // Optimistic UI update - toggle immediately
            window.StateManager.updateState({ playing: !previousPlayingState }, 'playbackController');
            
            // Request UI update
            window.dispatchEvent(new CustomEvent('requestUIUpdate', { detail: { source: 'playbackController' } }));
            
            // Immediate local audio control for instant user feedback
            const audioPlaying = window.AudioEngine.isPlaying();
            
            if (previousPlayingState && !audioPlaying) {
                // User clicked pause - pause audio immediately
                window.AudioEngine.pause();
                this.pauseStartTime = Date.now();
            } else if (!previousPlayingState && audioPlaying) {
                // User clicked play - play audio immediately
                window.AudioEngine.play().catch(console.error);
            }
            
            // Send to backend for synchronization
            this.apiCall('toggle', profileId)
                .then(response => {
                    if (!response.ok) {
                        throw new Error('Toggle request failed');
                    }
                })
                .catch(error => {
                    // Race condition mitigation: rollback optimistic update on failure
                    window.Helpers.log('PlaybackController: Play/pause toggle failed, rolling back:', error);
                    
                    window.StateManager.updateState({ playing: previousPlayingState }, 'playbackController');
                    
                    if (window.showToast) {
                        window.showToast('Failed to toggle playback', 'error');
                    }
                });
        },
        
        /**
         * Play previous song
         * @param {string} profileId - Profile ID
         */
        previousSong: function(profileId) {
            // Immediate optimistic feedback
            if (window.showToast) {
                window.showToast('Previous song!', 'success', 2000);
            }
            
            // Background API call with error handling
            this.apiCall('previous', profileId)
                .catch(error => {
                    if (window.showToast) {
                        window.showToast('Failed to go to previous song', 'error');
                    }
                });
        },
        
        /**
         * Play next song
         * @param {string} profileId - Profile ID
         */
        nextSong: function(profileId) {
            // Immediate optimistic feedback
            if (window.showToast) {
                window.showToast('Next song!', 'success', 2000);
            }
            
            // Background API call with error handling
            this.apiCall('next', profileId)
                .catch(error => {
                    if (window.showToast) {
                        window.showToast('Failed to go to next song', 'error');
                    }
                });
        },
        
        /**
         * Cycle shuffle mode
         * @param {string} profileId - Profile ID
         */
        cycleShuffleMode: function(profileId) {
            const currentState = window.StateManager.getState();
            let newMode;
            
            switch (currentState.shuffleMode) {
                case 'OFF':
                    newMode = 'SHUFFLE';
                    break;
                case 'SHUFFLE':
                    newMode = 'SMART_SHUFFLE';
                    break;
                case 'SMART_SHUFFLE':
                    newMode = 'OFF';
                    break;
                default:
                    newMode = 'OFF';
            }
            
            window.StateManager.updateState({ shuffleMode: newMode }, 'playbackController');
            
            // Send to backend
            this.apiCall('shuffleMode', profileId, { mode: newMode })
                .catch(error => {
                    window.Helpers.log('PlaybackController: Failed to update shuffle mode:', error);
                });
        },
        
        /**
         * Cycle repeat mode
         * @param {string} profileId - Profile ID
         */
        cycleRepeatMode: function(profileId) {
            const currentState = window.StateManager.getState();
            let newMode;
            
            switch (currentState.repeatMode) {
                case 'OFF':
                    newMode = 'ONE';
                    break;
                case 'ONE':
                    newMode = 'ALL';
                    break;
                case 'ALL':
                    newMode = 'OFF';
                    break;
                default:
                    newMode = 'OFF';
            }
            
            window.StateManager.updateState({ repeatMode: newMode }, 'playbackController');
            
            // Send to backend
            this.apiCall('repeatMode', profileId, { mode: newMode })
                .catch(error => {
                    window.Helpers.log('PlaybackController: Failed to update repeat mode:', error);
                });
        },
        
        /**
         * Handle audio state changes for buffer management
         * @param {Object} oldState - Previous state
         * @param {Object} newState - Current state
         */
        handleAudioStateChange: function(oldState, newState) {
            const playingChanged = newState.playing !== oldState.playing;
            
            if (playingChanged) {
                if (newState.playing && window.AudioEngine.isPaused()) {
                    const pauseDuration = this.pauseStartTime ? (Date.now() - this.pauseStartTime) : Infinity;
                    
                    if (pauseDuration < this.BUFFER_WARM_DURATION) {
                        // Fast resume - buffer should still be warm
                        if (window.AudioEngine.isPaused()) {
                            window.AudioEngine.play().catch(console.error);
                        }
                    } else {
                        // Buffer might be cold, go through normal resume
                        if (window.AudioEngine.isPaused()) {
                            window.AudioEngine.play().catch(console.error);
                        }
                    }
                    this.pauseStartTime = null;
                } else if (!newState.playing && !window.AudioEngine.isPaused()) {
                    this.pauseStartTime = Date.now();
                    window.AudioEngine.pause();
                }
            }
        },
        
        /**
         * Make API call with profile validation
         * @param {string} path - API path
         * @param {string} profileId - Profile ID
         * @param {Object} payload - Request payload
         * @returns {Promise} API response
         */
        apiCall: function(path, profileId, payload = {}) {
            let profileIdValue = profileId;
            
            if (!profileIdValue) {
                // Use async profile resolution like original
                return new Promise((resolve, reject) => {
                    const checkProfile = () => {
                        if (window.globalActiveProfileId && window.globalActiveProfileId !== 'undefined') {
                            profileIdValue = window.globalActiveProfileId;
                            resolve(fetch(`/api/music/playback/${path}/${profileIdValue}`, {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify(payload)
                            }));
                        } else {
                            setTimeout(checkProfile, 50);
                        }
                    };
                    checkProfile();
                });
            } else {
                return fetch(`/api/music/playback/${path}/${profileIdValue}`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
            }
        },
        
        /**
         * Fast resume for same song (no source change needed)
         * @param {string} profileId - Profile ID
         */
        fastResume: function(profileId) {
            if (window.AudioEngine.isPaused()) {
                window.AudioEngine.play().catch(console.error);
            }
        },
        
        /**
         * Get pause start time
         * @returns {number|null} Pause start time
         */
        getPauseStartTime: function() {
            return this.pauseStartTime;
        },
        
        /**
         * Get buffer warm duration
         * @returns {number} Buffer warm duration in milliseconds
         */
        getBufferWarmDuration: function() {
            return this.BUFFER_WARM_DURATION;
        }
    };
    
    // Auto-initialize when dependencies are available
    if (window.Helpers && window.StateManager && window.AudioEngine && window.ActionTracker) {
        window.PlaybackController.init();
        
        // Listen for audio state changes
        window.addEventListener('statePropertyChanged', (e) => {
            if (e.detail.property === 'playing') {
                window.PlaybackController.handleAudioStateChange(
                    { playing: !e.detail.newValue },
                    { playing: e.detail.newValue }
                );
            }
        });
    } else {
        // Wait for dependencies
        const checkDeps = () => {
            if (window.Helpers && window.StateManager && window.AudioEngine && window.ActionTracker) {
                window.PlaybackController.init();
            } else {
                setTimeout(checkDeps, 50);
            }
        };
        setTimeout(checkDeps, 50);
    }
    
})(window);