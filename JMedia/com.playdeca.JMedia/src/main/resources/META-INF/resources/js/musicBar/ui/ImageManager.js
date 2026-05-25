/**
 * ImageManager - Album artwork and image management
 * Handles album artwork updates, favicon changes, and image caching
 */
(function(window) {
    'use strict';
    
     window.ImageManager = {
         // DOM element cache
         elements: {},
         
         /**
          * Convert base64 artwork to data URL
          * @param {string} artworkBase64 - Base64 encoded artwork
          * @returns {string} Data URL or fallback
          */
         getArtworkDataUrl: function(artworkBase64) {
             if (artworkBase64 && artworkBase64 !== '') {
                 return 'data:image/jpeg;base64,' + artworkBase64;
             }
             return '/logo.png';
         },
        
        /**
         * Initialize image manager
         */
        init: function() {
            this.initializeElements();
            this.setupEventListeners();
            window.Helpers.log('ImageManager initialized');
        },
        
        /**
         * Initialize DOM elements
         */
        initializeElements: function() {
            this.elements = {
                songCoverImage: document.getElementById('songCoverImage'),
                prevSongCoverImage: document.getElementById('prevSongCoverImage'),
                nextSongCoverImage: document.getElementById('nextSongCoverImage'),
                favicon: document.getElementById('favicon'),
                pageTitle: document.getElementById('pageTitle')
            };
            
            window.Helpers.log('ImageManager: DOM elements cached');
        },
        
        /**
         * Set up event listeners
         */
        setupEventListeners: function() {
            // Listen for image update requests
            window.addEventListener('updateImages', (e) => {
                this.updateImages(e.detail.currentSong, e.detail.prevSong, e.detail.nextSong);
            });
            
            window.Helpers.log('ImageManager: Event listeners configured');
        },
        
        /**
         * Update images (album artwork, favicon, page title)
         * @param {Object} currentSong - Current song data
         * @param {Object} prevSong - Previous song data
         * @param {Object} nextSong - Next song data
         */
        updateImages: function(currentSong, prevSong, nextSong) {
            const currentArtwork = this.getArtworkUrl(currentSong);
            
            // Re-query elements in case they weren't ready during init
            this.elements.songCoverImage = this.elements.songCoverImage || document.getElementById('songCoverImage');
            this.elements.favicon = this.elements.favicon || document.getElementById('favicon');
            this.elements.pageTitle = this.elements.pageTitle || document.getElementById('pageTitle');
            
            // Update current song image and favicon synchronously
            if (this.elements.songCoverImage) {
                this.elements.songCoverImage.src = currentArtwork;
            }
            
            if (this.elements.favicon) {
                this.elements.favicon.href = currentArtwork;
            }
            
            // Update page title (skip when video is active)
            if (!window.videoPlaying && this.elements.pageTitle) {
                const title = currentSong ? `${currentSong.title} - ${currentSong.artist}` : 'JMedia';
                this.elements.pageTitle.innerText = title;
                this.elements.pageTitle.title = title;
            }
            
            // Update prev/next images asynchronously to avoid blocking
            this.updatePrevNextImages(prevSong, nextSong);
        },
        
        /**
         * Update previous/next song images asynchronously
         * @param {Object} prevSong - Previous song data
         * @param {Object} nextSong - Next song data
         */
        updatePrevNextImages: function(prevSong, nextSong) {
            // Defer non-critical updates to prevent blocking
            requestAnimationFrame(() => {
                // Previous song image
                if (this.elements.prevSongCoverImage) {
                    this.updateSongImage(this.elements.prevSongCoverImage, prevSong);
                }
                
                // Next song image
                if (this.elements.nextSongCoverImage) {
                    this.updateSongImage(this.elements.nextSongCoverImage, nextSong);
                }
            });
        },
        
        /**
         * Update individual song image
         * @param {HTMLElement} element - Image element
         * @param {Object} song - Song data
         */
        updateSongImage: function(element, song) {
            if (song && song.artworkBase64) {
                element.src = this.getArtworkDataUrl(song.artworkBase64);
                element.style.display = 'block';
            } else {
                element.src = '/logo.png';
                element.style.display = 'none';
            }
        },
        
        /**
         * Get artwork URL for song
         * @param {Object} song - Song data
         * @returns {string} Artwork URL
         */
        getArtworkUrl: function(song) {
            if (!song || !song.artworkBase64) {
                return '/logo.png';
            }
            return this.getArtworkDataUrl(song.artworkBase64);
        },
        
        /**
         * Preload images for smooth transitions
         * @param {Array} songs - Songs to preload
         */
        preloadImages: function(songs) {
            if (!songs || !Array.isArray(songs)) {
                return;
            }
            
            songs.forEach(song => {
                if (song && song.artworkBase64) {
                    const img = new Image();
                    img.src = this.getArtworkDataUrl(song.artworkBase64);
                    // Preload without blocking
                }
            });
            
            window.Helpers.log('ImageManager preloaded', songs.length, 'images');
        },
        
        /**
         * Clear image cache to free memory
         */
        clearCache: function() {
            // Clear previous song data to free memory
            if (window.previousSongData) {
                Object.values(window.previousSongData).forEach(song => {
                    if (song && song.artworkBase64) {
                        song.artworkBase64 = null;
                    }
                });
            }
            
            window.Helpers.log('ImageManager: Cache cleared');
        },
        
        /**
         * Get element status
         * @returns {Object} Element status
         */
        getElementStatus: function() {
            return {
                elementsCount: Object.keys(this.elements).length,
                elements: Object.keys(this.elements).map(key => ({
                    id: key,
                    element: this.elements[key] !== null
                }))
            };
        }
    };
    
    // Auto-initialize when dependencies are available
    if (window.Helpers) {
        window.ImageManager.init();
    } else {
        // Wait for dependencies
        const checkDeps = () => {
            if (window.Helpers) {
                window.ImageManager.init();
            } else {
                setTimeout(checkDeps, 50);
            }
        };
        setTimeout(checkDeps, 50);
    }
    
})(window);