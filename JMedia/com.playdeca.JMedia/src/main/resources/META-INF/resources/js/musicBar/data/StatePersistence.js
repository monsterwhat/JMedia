/**
 * StatePersistence - localStorage operations for state persistence
 * Handles saving and restoring playback state across page reloads
 */
(function(window) {
    'use strict';
    
    window.StatePersistence = {
        // Storage key
        STORAGE_KEY: 'playbackState',
        
        // Maximum age for restored state (30 seconds)
        MAX_AGE_MS: 30000,
        
        /**
         * Initialize state persistence
         */
        init: function() {
            this.setupEventListeners();
            window.Helpers.log('StatePersistence initialized');
        },
        
        /**
         * Set up event listeners for save requests
         */
        setupEventListeners: function() {
            // Listen for state save requests
            window.addEventListener('requestStateSave', (e) => {
                this.saveState(e.detail);
            });
            
            window.Helpers.log('StatePersistence event listeners configured');
        },
        
        /**
         * Save current playback state to localStorage
         * @param {Object} options - Save options
         * @param {boolean} options.includeCurrentTime - Whether to include current time
         */
        saveState: function(options = {}) {
            if (!window.localStorage) {
                window.Helpers.log('StatePersistence: localStorage not available');
                return false;
            }
            
            try {
                // Get current state from StateManager
                let currentState;
                if (window.StateManager) {
                    currentState = window.StateManager.getState();
                } else {
                    window.Helpers.log('StatePersistence: StateManager not available');
                    return false;
                }
                
                // Build state to save
                const stateToSave = {
                    currentSongId: currentState.currentSongId,
                    songName: currentState.songName,
                    artist: currentState.artist,
                    playing: currentState.playing,
                    duration: currentState.duration,
                    volume: currentState.volume,
                    shuffleMode: currentState.shuffleMode,
                    repeatMode: currentState.repeatMode,
                    timestamp: Date.now(),
                    deviceId: window.DeviceManager ? window.DeviceManager.getDeviceId() : null
                };
                
                // Only save time if offline or specifically requested
                const isOffline = window.StateManager ? window.StateManager.isCurrentlyOffline() : false;
                if (options.includeCurrentTime || isOffline) {
                    stateToSave.currentTime = currentState.currentTime;
                    stateToSave.savedOffline = isOffline;
                } else {
                    stateToSave.currentTime = null;
                    stateToSave.savedOffline = false;
                }
                
                localStorage.setItem(this.STORAGE_KEY, JSON.stringify(stateToSave));
                window.Helpers.log('StatePersistence saved state:', {
                    currentSongId: stateToSave.currentSongId,
                    playing: stateToSave.playing,
                    currentTime: stateToSave.currentTime,
                    timestamp: stateToSave.timestamp
                });
                
                return true;
                
            } catch (e) {
                window.Helpers.log('StatePersistence: Error saving state:', e);
                return false;
            }
        },
        
        /**
         * Restore playback state from localStorage
         * @returns {Object|null} Restored state or null if not available
         */
        restoreState: function() {
            if (!window.localStorage) {
                window.Helpers.log('StatePersistence: localStorage not available');
                return null;
            }
            
            try {
                const saved = localStorage.getItem(this.STORAGE_KEY);
                if (!saved) {
                    window.Helpers.log('StatePersistence: No saved state found');
                    return null;
                }
                
                const state = JSON.parse(saved);
                const age = Date.now() - state.timestamp;
                
                // Check if state is recent and for same device
                const currentDeviceId = window.DeviceManager ? window.DeviceManager.getDeviceId() : null;
                if (age < this.MAX_AGE_MS && (!currentDeviceId || state.deviceId === currentDeviceId)) {
                    window.Helpers.log('StatePersistence restored state:', {
                        currentSongId: state.currentSongId,
                        playing: state.playing,
                        age: age + 'ms',
                        timestamp: state.timestamp
                    });
                    
                    return state;
                } else {
                    window.Helpers.log('StatePersistence: State too old or wrong device:', {
                        age: age + 'ms',
                        maxAge: this.MAX_AGE_MS + 'ms',
                        sameDevice: state.deviceId === currentDeviceId
                    });
                    return null;
                }
                
            } catch (e) {
                window.Helpers.log('StatePersistence: Error restoring state:', e);
                return null;
            }
        },
        
        /**
         * Clear saved state from localStorage
         */
        clearState: function() {
            if (!window.localStorage) {
                window.Helpers.log('StatePersistence: localStorage not available');
                return false;
            }
            
            try {
                localStorage.removeItem(this.STORAGE_KEY);
                window.Helpers.log('StatePersistence cleared saved state');
                return true;
            } catch (e) {
                window.Helpers.log('StatePersistence: Error clearing state:', e);
                return false;
            }
        },
        
        /**
         * Check if saved state exists
         * @returns {boolean} True if saved state exists
         */
        hasSavedState: function() {
            if (!window.localStorage) {
                return false;
            }
            
            try {
                const saved = localStorage.getItem(this.STORAGE_KEY);
                return saved !== null && saved !== undefined;
            } catch (e) {
                window.Helpers.log('StatePersistence: Error checking saved state:', e);
                return false;
            }
        },
        
        /**
         * Get saved state metadata without parsing full state
         * @returns {Object|null} Metadata or null if not available
         */
        getSavedStateMetadata: function() {
            if (!window.localStorage) {
                return null;
            }
            
            try {
                const saved = localStorage.getItem(this.STORAGE_KEY);
                if (!saved) {
                    return null;
                }
                
                const state = JSON.parse(saved);
                const age = Date.now() - state.timestamp;
                
                return {
                    timestamp: state.timestamp,
                    age: age,
                    currentSongId: state.currentSongId,
                    deviceId: state.deviceId,
                    isRecent: age < this.MAX_AGE_MS,
                    sameDevice: state.deviceId === (window.DeviceManager ? window.DeviceManager.getDeviceId() : null)
                };
            } catch (e) {
                window.Helpers.log('StatePersistence: Error getting metadata:', e);
                return null;
            }
        },
        
        /**
         * Save state with current time (for offline usage)
         * @param {number} currentTime - Current playback time
         */
        saveCurrentTime: function(currentTime) {
            if (!window.StateManager) {
                return false;
            }
            
            return this.saveState({ includeCurrentTime: true, currentTime: currentTime });
        },
        
        /**
         * Get storage usage information
         * @returns {Object} Storage usage info
         */
        getStorageInfo: function() {
            if (!window.localStorage) {
                return { available: false };
            }
            
            try {
                const saved = localStorage.getItem(this.STORAGE_KEY);
                const size = saved ? new Blob([saved]).size : 0;
                const metadata = this.getSavedStateMetadata();
                
                return {
                    available: true,
                    hasData: this.hasSavedState(),
                    size: size,
                    sizeFormatted: size + ' bytes',
                    metadata: metadata
                };
            } catch (e) {
                window.Helpers.log('StatePersistence: Error getting storage info:', e);
                return { available: true, error: e.message };
            }
        },
        
        /**
         * Initialize with restored state
         * @returns {Object|null} Restored state or null
         */
        initializeWithRestored: function() {
            const restoredState = this.restoreState();
            
            if (restoredState && window.StateManager) {
                window.StateManager.initializeFromRestored(restoredState);
                return restoredState;
            } else if (!restoredState) {
                window.Helpers.log('StatePersistence: No state to restore');
            } else {
                window.Helpers.log('StatePersistence: StateManager not available');
            }
            
            return null;
        }
    };
    
    // Auto-initialize when dependencies are available
    if (window.Helpers && window.StateManager && window.DeviceManager) {
        window.StatePersistence.init();
    } else {
        // Wait for dependencies
        const checkDeps = () => {
            if (window.Helpers && window.StateManager && window.DeviceManager) {
                window.StatePersistence.init();
            } else {
                setTimeout(checkDeps, 50);
            }
        };
        setTimeout(checkDeps, 50);
    }
    
})(window);