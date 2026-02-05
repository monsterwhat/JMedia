/**
 * UIUpdater - Music bar UI updates and coordination
 * Handles all UI updates based on state changes
 */
(function(window) {
    'use strict';
    
    window.UIUpdater = {
        // DOM element cache
        domElements: {},
        
        // Throttled UI update function
        throttledUpdate: null,
        
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
                currentTimeEl: document.getElementById('currentTime'),
                totalTimeEl: document.getElementById('totalTime'),
                timeSlider: document.getElementById('playbackProgressBar'),
                volumeSlider: document.getElementById('volumeProgressBar'),
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
            // Listen for UI update requests
            window.addEventListener('requestUIUpdate', (e) => {
                this.performUIUpdate(e.detail.source);
            });
            
            // Listen for image updates
            window.addEventListener('updateImages', (e) => {
                this.updateImages(e.detail.currentSong, e.detail.prevSong, e.detail.nextSong);
            });
            
            window.Helpers.log('UIUpdater: Event listeners configured');
        },
        
        /**
         * Perform the actual UI update
         * @param {string} source - Source of the update
         */
        performUIUpdate: function(source) {
            if (!window.StateManager) {
                window.Helpers.log('UIUpdater: StateManager not available');
                return;
            }
            
            const state = window.StateManager.getState();
            console.log('🎵 UIUpdater.performUIUpdate called with state:', state);
            console.log('🎵 UIUpdater available elements:', this.domElements);
            
            // Update song information
            this.updateSongInfo(state);
            
            // Update play/pause icon
            this.updatePlayPauseIcon(state.playing);
            
            // Update time displays
            this.updateTimeDisplay(state.currentTime, state.duration);
            
            // Update progress bar
            this.updateProgressBar(state.currentTime, state.duration);
            
            // Update volume slider
            this.updateVolumeSlider(state.volume);
            
            // Update shuffle button
            this.updateShuffleButton(state.shuffleMode);
            
            // Update repeat button
            this.updateRepeatButton(state.repeatMode);
            
            // Update Media Session API
            this.updateMediaSession(state);
            
            window.Helpers.log('UIUpdater: Music bar updated from', source);
        },
        
        /**
         * Update song information displays
         * @param {Object} state - Current state
         */
        updateSongInfo: function(state) {
            console.log('🎵 UIUpdater.updateSongInfo called with state:', state);
            const songName = state.songName ?? "Unknown Title";
            const artist = state.artist ?? "Unknown Artist";
            
            console.log('🎵 UIUpdater: songName:', songName, 'artist:', artist);
            
            // Desktop elements
            if (this.domElements.songTitle) {
                this.domElements.songTitle.innerText = songName;
                console.log('🎵 UIUpdater: Updated songTitle element');
            } else {
                console.warn('🎵 UIUpdater: songTitle element not found');
            }
            if (this.domElements.artistEl) {
                this.domElements.artistEl.innerText = artist;
                console.log('🎵 UIUpdater: Updated artistEl element');
            } else {
                console.warn('🎵 UIUpdater: artistEl element not found');
            }
            
            // Mobile elements
            if (this.domElements.titleMobileEl) {
                this.domElements.titleMobileEl.innerText = songName;
            }
            if (this.domElements.artistMobileEl) {
                this.domElements.artistMobileEl.innerText = artist;
            }
            
            // Apply marquee effect
            window.Helpers.applyMarqueeEffect('songTitle', songName);
            window.Helpers.applyMarqueeEffect('songArtist', artist);
            window.Helpers.applyMarqueeEffect('songTitleMobile', songName);
            window.Helpers.applyMarqueeEffect('songArtistMobile', artist);
        },
        
        /**
         * Update play/pause icon
         * @param {boolean} playing - Whether playing
         */
        updatePlayPauseIcon: function(playing) {
            console.log('🎵 UIUpdater.updatePlayPauseIcon called with playing:', playing);
            if (this.domElements.playPauseIcon) {
                this.domElements.playPauseIcon.className = "pi"; // Base class
                if (playing) {
                    this.domElements.playPauseIcon.classList.add("pi-pause", "has-text-warning");
                    console.log('🎵 UIUpdater: Set play button to pause icon');
                } else {
                    this.domElements.playPauseIcon.classList.add("pi-play", "has-text-success");
                    console.log('🎵 UIUpdater: Set play button to play icon');
                }
            } else {
                console.warn('🎵 UIUpdater: playPauseIcon element not found');
            }
        },
        
        /**
         * Update time displays
         * @param {number} currentTime - Current time
         * @param {number} duration - Duration
         */
        updateTimeDisplay: function(currentTime, duration) {
            console.log('🎵 UIUpdater.updateTimeDisplay called:', { currentTime, duration });
            if (this.domElements.currentTimeEl) {
                this.domElements.currentTimeEl.innerText = window.Helpers.formatTime(Math.floor(currentTime));
                console.log('🎵 UIUpdater: Updated currentTime element');
            } else {
                console.warn('🎵 UIUpdater: currentTime element not found');
            }
            if (this.domElements.totalTimeEl) {
                this.domElements.totalTimeEl.innerText = window.Helpers.formatTime(duration);
                console.log('🎵 UIUpdater: Updated totalTime element');
            } else {
                console.warn('🎵 UIUpdater: totalTime element not found');
            }
        },
        
        /**
         * Update progress bar
         * @param {number} currentTime - Current time
         * @param {number} duration - Total duration
         */
        updateProgressBar: function(currentTime, duration) {
            if (this.domElements.timeSlider) {
                this.domElements.timeSlider.max = duration;
                
                // Update the slider's value based on audio.currentTime if not dragging
                if (!SynchronizationManager.getFlag('draggingSeconds')) {
                    this.domElements.timeSlider.value = window.AudioEngine ? window.AudioEngine.getCurrentTime() : currentTime;
                }
                
                // Always update the progress bar's visual fill
                const currentSliderValue = parseFloat(this.domElements.timeSlider.value);
                const progress = duration > 0 ? (currentSliderValue / duration) * 100 : 0;
                this.domElements.timeSlider.style.setProperty('--progress-value', `${progress}%`);
            }
        },
        
        /**
         * Update volume slider
         * @param {number} volume - Current volume (0-1)
         */
        updateVolumeSlider: function(volume) {
            if (this.domElements.volumeSlider) {
                // Update the slider's value based on musicState.volume if not dragging
                if (!SynchronizationManager.getFlag('draggingVolume')) {
                    const sliderValue = window.Helpers.volume.calculateLinearSliderValue(volume);
                    this.domElements.volumeSlider.value = sliderValue;
                }
                
                // Always update the progress bar's visual fill
                const currentSliderValue = parseFloat(this.domElements.volumeSlider.value);
                const progress = (currentSliderValue / 100) * 100;
                this.domElements.volumeSlider.style.setProperty('--progress-value', `${progress}%`);
            }
        },
        
        /**
         * Update shuffle button
         * @param {string} shuffleMode - Current shuffle mode
         */
        updateShuffleButton: function(shuffleMode) {
            console.log('🎵 UIUpdater.updateShuffleButton called with mode:', shuffleMode);
            if (this.domElements.shuffleIcon && this.domElements.shuffleBtn) {
                this.domElements.shuffleIcon.className = "pi"; // Base class
                
                // Clear all state classes from button
                this.domElements.shuffleBtn.classList.remove('shuffle-off', 'shuffle-on', 'shuffle-smart');
                
                switch (shuffleMode) {
                    case "SHUFFLE":
                        this.domElements.shuffleIcon.classList.add("pi-sort-alt-slash");
                        this.domElements.shuffleBtn.classList.add('shuffle-on');
                        console.log('🎵 UIUpdater: Set shuffle to ON');
                        break;
                    case "SMART_SHUFFLE":
                        this.domElements.shuffleIcon.classList.add("pi-sparkles");
                        this.domElements.shuffleBtn.classList.add('shuffle-smart');
                        console.log('🎵 UIUpdater: Set shuffle to SMART');
                        break;
                    case "OFF":
                    default:
                        this.domElements.shuffleIcon.classList.add("pi-sort-alt");
                        this.domElements.shuffleBtn.classList.add('shuffle-off');
                        console.log('🎵 UIUpdater: Set shuffle to OFF');
                        break;
                }
            } else {
                console.warn('🎵 UIUpdater: shuffle elements not found', {
                    shuffleIcon: !!this.domElements.shuffleIcon,
                    shuffleBtn: !!this.domElements.shuffleBtn
                });
            }
        },
        
        /**
         * Update repeat button
         * @param {string} repeatMode - Current repeat mode
         */
        updateRepeatButton: function(repeatMode) {
            console.log('🎵 UIUpdater.updateRepeatButton called with mode:', repeatMode);
            if (this.domElements.repeatIcon && this.domElements.repeatBtn) {
                this.domElements.repeatIcon.className = "pi pi-refresh"; // Base class
                
                // Clear all state classes from button
                this.domElements.repeatBtn.classList.remove('repeat-off', 'repeat-one', 'repeat-all');
                
                if (repeatMode === "ONE") {
                    this.domElements.repeatBtn.classList.add('repeat-one');
                    console.log('🎵 UIUpdater: Set repeat to ONE');
                } else if (repeatMode === "ALL") {
                    this.domElements.repeatBtn.classList.add('repeat-all');
                    console.log('🎵 UIUpdater: Set repeat to ALL');
                } else {
                    // OFF
                    this.domElements.repeatBtn.classList.add('repeat-off');
                    console.log('🎵 UIUpdater: Set repeat to OFF');
                }
            } else {
                console.warn('🎵 UIUpdater: repeat elements not found', {
                    repeatIcon: !!this.domElements.repeatIcon,
                    repeatBtn: !!this.domElements.repeatBtn
                });
            }
        },
        
        /**
         * Update images (album artwork, favicon)
         * @param {Object} currentSong - Current song
         * @param {Object} prevSong - Previous song
         * @param {Object} nextSong - Next song
         */
        updateImages: function(currentSong, prevSong, nextSong) {
            const currentArtwork = currentSong?.artworkBase64 && currentSong.artworkBase64 !== ''
                ? `data:image/jpeg;base64,${currentSong.artworkBase64}`
                : '/logo.png';
            
            // Update current song image and favicon synchronously
            const songCoverImageEl = document.getElementById('songCoverImage');
            const songCoverFallback = document.getElementById('songCoverFallback');
            
            if (songCoverImageEl) {
                songCoverImageEl.src = currentArtwork;
                
                // Handle mobile fallback icon visibility
                if (songCoverFallback) {
                    if (currentArtwork.includes('/logo.png') || !currentArtwork || currentArtwork === '') {
                        songCoverImageEl.style.display = 'none';
                        songCoverFallback.style.display = 'block';
                    } else {
                        songCoverImageEl.style.display = 'block';
                        songCoverFallback.style.display = 'none';
                    }
                }
            }
            
            const faviconEl = document.getElementById('favicon');
            if (faviconEl) {
                faviconEl.href = currentArtwork;
            }
            
            // Update prev/next images asynchronously to avoid blocking
            this.updatePrevNextImages(prevSong, nextSong);
        },
        
        /**
         * Update previous/next song images asynchronously
         * @param {Object} prevSong - Previous song
         * @param {Object} nextSong - Next song
         */
        updatePrevNextImages: function(prevSong, nextSong) {
            requestAnimationFrame(() => {
                const prevSongCoverImageEl = document.getElementById('prevSongCoverImage');
                const nextSongCoverImageEl = document.getElementById('nextSongCoverImage');
                
                // Previous song image
                if (prevSongCoverImageEl) {
                    if (prevSong && prevSong.artworkBase64 && prevSong.artworkBase64 !== '') {
                        prevSongCoverImageEl.src = `data:image/jpeg;base64,${prevSong.artworkBase64}`;
                        prevSongCoverImageEl.style.display = 'block';
                    } else {
                        prevSongCoverImageEl.src = '/logo.png';
                        prevSongCoverImageEl.style.display = 'none';
                    }
                }
                
                // Next song image
                if (nextSongCoverImageEl) {
                    if (nextSong && nextSong.artworkBase64 && nextSong.artworkBase64 !== '') {
                        nextSongCoverImageEl.src = `data:image/jpeg;base64,${nextSong.artworkBase64}`;
                        nextSongCoverImageEl.style.display = 'block';
                    } else {
                        nextSongCoverImageEl.src = '/logo.png';
                        nextSongCoverImageEl.style.display = 'none';
                    }
                }
            });
        },
        
        /**
         * Update Media Session API
         * @param {Object} state - Current state
         */
        updateMediaSession: function(state) {
            if ('mediaSession' in navigator && window.updateMediaSessionMetadata && window.updateMediaSessionPlaybackState) {
                const songCoverImageEl = document.getElementById('songCoverImage');
                const artworkUrl = songCoverImageEl ? songCoverImageEl.src : '/logo.png';
                
                window.updateMediaSessionMetadata(state.songName, state.artist, artworkUrl);
                window.updateMediaSessionPlaybackState(state.playing);
            }
        },
        
        /**
         * Perform throttled UI update
         */
        performUIUpdate: function() {
            this.throttledUpdate();
        },
        
        /**
         * Get DOM element cache status
         * @returns {Object} Cache status
         */
        getDOMCacheStatus: function() {
            return {
                elementsCount: Object.keys(this.domElements).length,
                elements: Object.keys(this.domElements).map(key => ({
                    id: key,
                    element: this.domElements[key] !== null
                }))
            };
        }
    };
    
    // Auto-initialize when dependencies are available
    if (window.Helpers && window.StateManager && window.SynchronizationManager) {
        window.UIUpdater.init();
    } else {
        // Wait for dependencies
        const checkDeps = () => {
            if (window.Helpers && window.StateManager && window.SynchronizationManager) {
                window.UIUpdater.init();
            } else {
                setTimeout(checkDeps, 50);
            }
        };
        setTimeout(checkDeps, 50);
    }
    
})(window);