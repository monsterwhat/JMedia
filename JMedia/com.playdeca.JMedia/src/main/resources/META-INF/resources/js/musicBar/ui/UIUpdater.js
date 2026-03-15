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
                songTitle: document.getElementById('songTitle') || document.getElementById('songTitleMobile'),
                artistEl: document.getElementById('songArtist') || document.getElementById('songArtistMobile'),
                titleMobileEl: document.getElementById('songTitle') || document.getElementById('songTitleMobile'),
                artistMobileEl: document.getElementById('songArtist') || document.getElementById('songArtistMobile'),
                playPauseIcon: document.getElementById('playPauseIcon'),
                currentTimeEl: document.getElementById('musicCurrentTime'),
                totalTimeEl: document.getElementById('musicTotalTime'),
                timeSlider: document.getElementById('musicProgressBar'),
                volumeSlider: document.getElementById('musicVolumeProgressBar'),
                shuffleIcon: document.getElementById('shuffleIcon'),
                shuffleBtn: document.getElementById('shuffleBtn'),
                repeatIcon: document.getElementById('repeatIcon'),
                repeatBtn: document.getElementById('repeatBtn')
            };

            window.Helpers.log('UIUpdater: DOM elements cached');
        },
        
        /**
         * Set up event listeners
         */
        setupEventListeners: function() {
            // Listen for state changes to trigger UI updates
            window.addEventListener('stateChanged', () => {
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
            if (els.songTitle) els.songTitle.innerText = state.songName || 'Unknown Title';
            if (els.artistEl) els.artistEl.innerText = state.artistName || 'Unknown Artist';
            if (els.titleMobileEl) els.titleMobileEl.innerText = state.songName || 'Unknown Title';
            if (els.artistMobileEl) els.artistMobileEl.innerText = state.artistName || 'Unknown Artist';
            
            // 2. Play/Pause state
            if (els.playPauseIcon) {
                els.playPauseIcon.className = state.playing ? 
                    'pi pi-pause has-text-warning' : 'pi pi-play has-text-success';
            }
            
            // 3. Time and Slider (only if not dragging)
            if (!window.SynchronizationManager.getFlag('draggingSeconds')) {
                if (els.currentTimeEl) {
                    els.currentTimeEl.innerText = window.Helpers.formatTime(Math.floor(state.currentTime));
                }
                
                if (els.totalTimeEl) {
                    els.totalTimeEl.innerText = window.Helpers.formatTime(Math.floor(state.duration));
                }
                
                if (els.timeSlider) {
                    els.timeSlider.value = state.currentTime;
                    els.timeSlider.max = state.duration || 0;
                    const progress = state.duration > 0 ? (state.currentTime / state.duration) * 100 : 0;
                    els.timeSlider.style.setProperty('--slider-progress', `${progress}%`);
                }
            }
            
            // 4. Volume Slider (only if not dragging)
            if (!window.SynchronizationManager.getFlag('draggingVolume') && els.volumeSlider) {
                const volValue = Math.sqrt(state.volume) * 100; // Square root
                els.volumeSlider.value = volValue;
                els.volumeSlider.style.setProperty('--slider-progress', `${volValue}%`);
            }
            
            // 5. Shuffle and Repeat icons
            if (els.shuffleIcon) {
                if (state.shuffleMode === 'SMART_SHUFFLE') {
                    els.shuffleIcon.className = 'pi pi-sparkles has-text-success';
                } else if (state.shuffleMode === 'SHUFFLE') {
                    els.shuffleIcon.className = 'pi pi-sort-alt has-text-info';
                } else {
                    els.shuffleIcon.className = 'pi pi-sort-alt';
                }
            }
            
            if (els.repeatIcon) {
                if (state.repeatMode === 'ALL') {
                    els.repeatIcon.className = 'pi pi-refresh has-text-success';
                } else if (state.repeatMode === 'ONE') {
                    els.repeatIcon.className = 'pi pi-refresh has-text-info';
                } else {
                    els.repeatIcon.className = 'pi pi-refresh';
                }
            }
            
            // 6. Cover Artwork - use stored artworkBase64 if available
            const coverImg = document.getElementById('songCoverImage');
            const coverFallback = document.getElementById('songCoverFallback');
            if (coverImg && state.currentSongId) {
                // Try to get artwork from musicState (which stores full song data)
                const musicStateData = window.musicState?.currentSongData;
                if (musicStateData && musicStateData.artworkBase64) {
                    coverImg.src = `data:image/jpeg;base64,${musicStateData.artworkBase64}`;
                } else {
                    coverImg.src = `/api/music/cover/${state.currentSongId}`;
                }
                coverImg.style.display = 'block';
                if (coverFallback) coverFallback.style.display = 'none';
            }
        }
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
