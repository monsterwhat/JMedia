/**
 * UIUpdater - DOM updates and visual state management
 * Handles all direct DOM manipulations for the music bar
 */
(function(window) {
    'use strict';
    
    window.UIUpdater = {
        // DOM element cache
        domElements: {},
        
        /**
         * Initialize UI updater
         */
        init: function() {
            this.initializeDOMElements();
            this.setupEventListeners();
            this.throttledUpdate = window.Helpers.throttle(this.performUIUpdate.bind(this), 100);
            window.Helpers.log('UIUpdater initialized');
        },
        
        /**
         * Initialize DOM element cache
         */
        initializeDOMElements: function() {
            this.domElements = {
                songTitle: document.getElementById('songTitle'),
                artistEl: document.getElementById('songArtist'),
                playPauseIcon: document.getElementById('playPauseIcon'),
                currentTimeEl: document.getElementById('musicCurrentTime'),
                totalTimeEl: document.getElementById('musicTotalTime'),
                timeSlider: document.getElementById('musicProgressBar'),
                volumeSlider: document.getElementById('musicVolumeProgressBar'),
                shuffleIcon: document.getElementById('shuffleIcon'),
                repeatIcon: document.getElementById('repeatIcon')
            };

            window.Helpers.log('UIUpdater: DOM elements cached');
        },
        
        /**
         * Set up event listeners
         */
        setupEventListeners: function() {
            // Listen for state changes to trigger UI updates
            window.addEventListener('musicStateChanged', () => {
                this.throttledUpdate();
            });
            
            // Re-cache elements on request (e.g., after navigation)
            window.addEventListener('refreshDOMCache', () => {
                this.initializeDOMElements();
                this.performUIUpdate();
            });
        },
        
        /**
         * Perform UI update based on current state
         */
        performUIUpdate: function() {
            if (!window.StateManager) return;
            
            const state = window.StateManager.getState();
            const els = this.domElements;
            
            // 1. Text elements
            const songName = state.songName || 'Nothing Playing';
            const artistName = state.artistName || state.artist || 'Unknown Artist';
            
            if (els.songTitle) els.songTitle.innerText = songName;
            if (els.artistEl) els.artistEl.innerText = artistName;
            
            // 2. Play/Pause state - query fresh since EventBindings clones the button
            const playPauseIcon = document.getElementById('playPauseIcon');
            if (playPauseIcon) {
                playPauseIcon.className = state.playing ? 'pi pi-pause' : 'pi pi-play';
            }
            
            // 3. Time and Slider
            if (!window.SynchronizationManager.getFlag('draggingSeconds')) {
                if (els.currentTimeEl) {
                    els.currentTimeEl.innerText = window.Helpers.formatTime(state.currentTime || 0);
                }
                
                if (els.totalTimeEl) {
                    els.totalTimeEl.innerText = window.Helpers.formatTime(state.duration || 0);
                }
                
                if (els.timeSlider) {
                    els.timeSlider.value = state.currentTime || 0;
                    els.timeSlider.max = state.duration || 0;
                    const progress = state.duration > 0 ? (state.currentTime / state.duration) * 100 : 0;
                    els.timeSlider.style.setProperty('--slider-progress', `${progress}%`);
                }
            }
            
            // 4. Shuffle/Repeat state - query fresh each time since EventBindings clones/replaces buttons
            const shuffleIcon = document.getElementById('shuffleIcon');
            if (shuffleIcon) {
                if (state.shuffleMode === 'SMART_SHUFFLE') {
                    shuffleIcon.className = 'pi pi-sparkles has-text-success';
                } else if (state.shuffleMode === 'SHUFFLE') {
                    shuffleIcon.className = 'pi pi-sort-alt has-text-info';
                } else {
                    shuffleIcon.className = 'pi pi-sort-alt';
                }
            }
            const repeatIcon = document.getElementById('repeatIcon');
            if (repeatIcon) {
                if (state.repeatMode === 'ALL') {
                    repeatIcon.className = 'pi pi-refresh has-text-success';
                } else if (state.repeatMode === 'ONE') {
                    repeatIcon.className = 'pi pi-refresh has-text-info';
                } else {
                    repeatIcon.className = 'pi pi-refresh';
                }
            }
        },
        
        /**
         * Update music bar UI (legacy compatibility)
         */
        updateMusicBar: function() {
            this.performUIUpdate();
        }
    };
    
    // Legacy compatibility
    window.updateMusicBar = function() {
        if (window.UIUpdater) window.UIUpdater.updateMusicBar();
    };
    
    // Auto-initialize when dependencies are available
    if (window.Helpers && window.SynchronizationManager && window.StateManager) {
        window.UIUpdater.init();
    } else {
        // Wait for dependencies
        const checkDeps = () => {
            if (window.Helpers && window.SynchronizationManager && window.StateManager) {
                window.UIUpdater.init();
            } else {
                setTimeout(checkDeps, 50);
            }
        };
        setTimeout(checkDeps, 50);
    }
    
})(window);
