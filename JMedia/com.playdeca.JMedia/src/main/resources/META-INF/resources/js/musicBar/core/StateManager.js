/**
 * StateManager - Centralized state management with CustomEvent coordination
 * Maintains the musicBar state and coordinates updates across modules
 */
(function(window) {
    'use strict';
    
    window.StateManager = {
        // Core state object (mirrors original musicState)
        state: {
            currentSongId: null,
            songName: "Loading...",
            artist: "Unknown Artist",
            playing: false,
            currentTime: 0,
            duration: 0,
            volume: 0.8,
            shuffleMode: "OFF",
            repeatMode: "OFF",
            cue: [],
            hasLyrics: false,
            
            // Responsive/UI state properties
            playerExpanded: false,
            playerVisible: true,
            sidePanelOpen: false,
            activeTab: 'playlists',
            searchQuery: '',
            currentSort: 'dateAdded',
            sortDirection: 'desc',
            filteredGenres: [],
            activeModal: null,
            contextMenuVisible: false,
            filterMenuVisible: false,
            profileModalVisible: false,
            volumeSliderVisible: false
        },
        
        // Offline flag management
        isOffline: false,
        
        // Last state save time for periodic saves
        lastStateSave: null,
        
        // State change tracking
        changeListeners: [],
        
        /**
         * Initialize state manager
         */
        init: function() {
            // Set up event listeners for state coordination
            this.setupEventListeners();
            
            // Set initial volume from device manager if available
            if (window.DeviceManager && window.DeviceManager.hasDeviceVolume()) {
                this.state.volume = window.DeviceManager.getDeviceVolume();
            }
            
            window.Helpers.log('StateManager initialized');
        },
        
        /**
         * Set up event listeners for CustomEvent coordination
         */
        setupEventListeners: function() {
            // Listen for state update requests from other modules
            window.addEventListener('requestStateUpdate', (e) => {
                this.updateState(e.detail.changes, e.detail.source);
            });
            
            // Listen for state get requests
            window.addEventListener('requestState', (e) => {
                if (e.detail.callback && typeof e.detail.callback === 'function') {
                    e.detail.callback(this.getState());
                }
            });
            
            // Listen for complete state replacement
            window.addEventListener('replaceState', (e) => {
                this.replaceState(e.detail.newState, e.detail.source);
            });
            
            // Listen for audio time updates
            window.addEventListener('audioTimeUpdate', (e) => {
                this.updateState({
                    currentTime: e.detail.currentTime
                }, 'audioEngine');
            });
            
            window.Helpers.log('StateManager event listeners configured');
        },
        
        /**
         * Get current state
         * @returns {Object} Current state (deep clone)
         */
        getState: function() {
            return window.Helpers.deepClone(this.state);
        },
        
        /**
         * Get specific state property
         * @param {string} property - Property name
         * @returns {*} Property value
         */
        getProperty: function(property) {
            return this.state[property];
        },
        
        /**
         * Update state with changes
         * @param {Object} changes - Changes to apply
         * @param {string} source - Source of changes (for logging)
         */
        updateState: function(changes, source = 'unknown') {
            if (!changes || typeof changes !== 'object') {
                window.Helpers.log('StateManager: Invalid changes object', changes);
                return;
            }
            
            const oldState = window.Helpers.deepClone(this.state);
            let hasChanges = false;
            
            // Apply changes and detect what actually changed
            for (const key in changes) {
                if (changes.hasOwnProperty(key) && this.state.hasOwnProperty(key)) {
                    if (this.state[key] !== changes[key]) {
                        this.state[key] = changes[key];
                        hasChanges = true;
                        window.Helpers.log(`StateManager updated ${key} from ${oldState[key]} to ${changes[key]} (${source})`);
                    }
                }
            }
            
            if (hasChanges) {
                this.notifyStateChange(oldState, this.state, changes, source);
            }
        },
        
        /**
         * Replace entire state
         * @param {Object} newState - New state object
         * @param {string} source - Source of change
         */
        replaceState: function(newState, source = 'unknown') {
            if (!newState || typeof newState !== 'object') {
                window.Helpers.log('StateManager: Invalid new state object', newState);
                return;
            }
            
            const oldState = window.Helpers.deepClone(this.state);
            const hasChanges = !window.Helpers.deepEqual(oldState, newState);
            
            if (hasChanges) {
                // Preserve important properties if not in new state
                const preservedState = {};
                const importantProps = ['volume']; // Properties to preserve from current state
                
                for (const prop of importantProps) {
                    if (this.state.hasOwnProperty(prop) && !newState.hasOwnProperty(prop)) {
                        preservedState[prop] = this.state[prop];
                    }
                }
                
                this.state = {...newState, ...preservedState};
                this.notifyStateChange(oldState, this.state, newState, source);
                
                window.Helpers.log('StateManager replaced entire state from', source);
            }
        },
        
        /**
         * Notify listeners of state change
         * @param {Object} oldState - Previous state
         * @param {Object} newState - Current state
         * @param {Object} changes - Applied changes
         * @param {string} source - Source of changes
         */
        notifyStateChange: function(oldState, newState, changes, source) {
            // Emit main state change event
            window.dispatchEvent(new CustomEvent('musicStateChanged', {
                detail: {
                    oldState: oldState,
                    newState: newState,
                    changes: changes,
                    source: source,
                    timestamp: Date.now()
                }
            }));
            
            // Emit specific change events for easier listening
            for (const key in changes) {
                if (changes.hasOwnProperty(key)) {
                    window.dispatchEvent(new CustomEvent('statePropertyChanged', {
                        detail: {
                            property: key,
                            oldValue: oldState[key],
                            newValue: newState[key],
                            source: source
                        }
                    }));
                }
            }
            
            // Notify manual listeners
            this.changeListeners.forEach(listener => {
                try {
                    listener(oldState, newState, changes, source);
                } catch (error) {
                    window.Helpers.log('StateManager: Error in change listener:', error);
                }
            });
            
            // Trigger periodic save
            this.triggerPeriodicSave();
        },
        
        /**
         * Set offline flag
         * @param {boolean} isOffline - Offline status
         */
        setOffline: function(isOffline) {
            const wasOffline = this.isOffline;
            this.isOffline = !!isOffline;
            
            if (!wasOffline && this.isOffline) {
                // Going offline - trigger state save with current time
                window.dispatchEvent(new CustomEvent('requestStateSave', {
                    detail: { includeCurrentTime: true }
                }));
            }
            
            window.Helpers.log('StateManager offline status changed to', this.isOffline);
            
            // Emit offline status change event
            window.dispatchEvent(new CustomEvent('offlineStatusChanged', {
                detail: {
                    isOffline: this.isOffline,
                    wasOffline: wasOffline
                }
            }));
        },
        
        /**
         * Get offline status
         * @returns {boolean} Current offline status
         */
        isCurrentlyOffline: function() {
            return this.isOffline;
        },
        
        /**
         * Trigger periodic state save (every 5 seconds)
         */
        triggerPeriodicSave: function() {
            const now = Date.now();
            if (!this.lastStateSave || (now - this.lastStateSave) > 5000) {
                this.lastStateSave = now;
                
                window.dispatchEvent(new CustomEvent('requestStateSave', {
                    detail: { includeCurrentTime: false }
                }));
                
                window.Helpers.log('StateManager triggered periodic state save');
            }
        },
        
        /**
         * Add state change listener
         * @param {Function} listener - Listener function
         */
        addChangeListener: function(listener) {
            if (typeof listener === 'function') {
                this.changeListeners.push(listener);
                window.Helpers.log('StateManager added change listener');
            }
        },
        
        /**
         * Remove state change listener
         * @param {Function} listener - Listener function to remove
         */
        removeChangeListener: function(listener) {
            const index = this.changeListeners.indexOf(listener);
            if (index > -1) {
                this.changeListeners.splice(index, 1);
                window.Helpers.log('StateManager removed change listener');
            }
        },
        
        /**
         * Reset state to initial values
         */
        reset: function() {
            const oldState = window.Helpers.deepClone(this.state);
            
            this.state = {
                currentSongId: null,
                songName: "Loading...",
                artist: "Unknown Artist",
                playing: false,
                currentTime: 0,
                duration: 0,
                volume: 0.8,
                shuffleMode: "OFF",
                repeatMode: "OFF",
                cue: [],
                hasLyrics: false,
                
                // Responsive/UI state properties
                playerExpanded: false,
                playerVisible: true,
                sidePanelOpen: false,
                activeTab: 'playlists',
                searchQuery: '',
                currentSort: 'dateAdded',
                sortDirection: 'desc',
                filteredGenres: [],
                activeModal: null,
                contextMenuVisible: false,
                filterMenuVisible: false,
                profileModalVisible: false,
                volumeSliderVisible: false
            };
            
            this.notifyStateChange(oldState, this.state, this.state, 'reset');
            window.Helpers.log('StateManager reset to initial state');
        },
        
        /**
         * Get state summary for debugging
         * @returns {Object} State summary
         */
        getStateSummary: function() {
            return {
                currentSongId: this.state.currentSongId,
                songName: this.state.songName,
                artist: this.state.artist,
                playing: this.state.playing,
                currentTime: this.state.currentTime,
                duration: this.state.duration,
                volume: this.state.volume,
                shuffleMode: this.state.shuffleMode,
                repeatMode: this.state.repeatMode,
                queueLength: this.state.cue.length,
                hasLyrics: this.state.hasLyrics,
                isOffline: this.isOffline,
                listenerCount: this.changeListeners.length,
                
                // Responsive/UI state
                playerExpanded: this.state.playerExpanded,
                playerVisible: this.state.playerVisible,
                sidePanelOpen: this.state.sidePanelOpen,
                activeTab: this.state.activeTab,
                searchQuery: this.state.searchQuery,
                currentSort: this.state.currentSort,
                sortDirection: this.state.sortDirection,
                filteredGenresCount: this.state.filteredGenres.length,
                activeModal: this.state.activeModal,
                contextMenuVisible: this.state.contextMenuVisible,
                filterMenuVisible: this.state.filterMenuVisible,
                profileModalVisible: this.state.profileModalVisible,
                volumeSliderVisible: this.state.volumeSliderVisible
            };
        },
        
        /**
         * Initialize state with restored data (from persistence)
         * @param {Object} restoredState - Previously saved state
         */
        initializeFromRestored: function(restoredState) {
            if (restoredState && typeof restoredState === 'object') {
                // Merge restored state with defaults to handle new properties
                const mergedState = {
                    currentSongId: null,
                    songName: "Loading...",
                    artist: "Unknown Artist",
                    playing: false,
                    currentTime: 0,
                    duration: 0,
                    volume: 0.8,
                    shuffleMode: "OFF",
                    repeatMode: "OFF",
                    cue: [],
                    hasLyrics: false,
                    
                    // Responsive/UI state defaults
                    playerExpanded: false,
                    playerVisible: true,
                    sidePanelOpen: false,
                    activeTab: 'playlists',
                    searchQuery: '',
                    currentSort: 'dateAdded',
                    sortDirection: 'desc',
                    filteredGenres: [],
                    activeModal: null,
                    contextMenuVisible: false,
                    filterMenuVisible: false,
                    profileModalVisible: false,
                    volumeSliderVisible: false,
                    
                    // Override with restored values
                    ...restoredState
                };
                
                this.replaceState(mergedState, 'restored');
                window.Helpers.log('StateManager initialized from restored state:', mergedState);
            } else {
                window.Helpers.log('StateManager no restored state available');
            }
        },
        
        /**
         * Update responsive UI state
         * @param {Object} uiState - UI state properties to update
         * @param {string} source - Source of changes
         */
        updateUIState: function(uiState, source = 'unknown') {
            if (!uiState || typeof uiState !== 'object') {
                window.Helpers.log('StateManager: Invalid UI state object', uiState);
                return;
            }
            
            // Filter to only UI-related properties
            const uiProperties = [
                'playerExpanded', 'playerVisible', 'sidePanelOpen', 'activeTab',
                'searchQuery', 'currentSort', 'sortDirection', 'filteredGenres',
                'activeModal', 'contextMenuVisible', 'filterMenuVisible',
                'profileModalVisible', 'volumeSliderVisible'
            ];
            
            const filteredChanges = {};
            uiProperties.forEach(prop => {
                if (uiState.hasOwnProperty(prop)) {
                    filteredChanges[prop] = uiState[prop];
                }
            });
            
            this.updateState(filteredChanges, source);
        },
        
        /**
         * Get current UI state
         * @returns {Object} Current UI state
         */
        getUIState: function() {
            return {
                playerExpanded: this.state.playerExpanded,
                playerVisible: this.state.playerVisible,
                sidePanelOpen: this.state.sidePanelOpen,
                activeTab: this.state.activeTab,
                searchQuery: this.state.searchQuery,
                currentSort: this.state.currentSort,
                sortDirection: this.state.sortDirection,
                filteredGenres: [...this.state.filteredGenres],
                activeModal: this.state.activeModal,
                contextMenuVisible: this.state.contextMenuVisible,
                filterMenuVisible: this.state.filterMenuVisible,
                profileModalVisible: this.state.profileModalVisible,
                volumeSliderVisible: this.state.volumeSliderVisible
            };
        }
    };
    
    // Auto-initialize when dependencies are available
    if (window.Helpers && window.DeviceManager) {
        window.StateManager.init();
    } else {
        // Wait for dependencies
        const checkDeps = () => {
            if (window.Helpers && window.DeviceManager) {
                window.StateManager.init();
            } else {
                setTimeout(checkDeps, 50);
            }
        };
        setTimeout(checkDeps, 50);
    }
    
})(window);