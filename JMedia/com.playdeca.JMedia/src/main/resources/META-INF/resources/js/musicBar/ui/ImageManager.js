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
          * Get artwork URL for a song
          * @param {Object} song - Song data (must include id)
          * @returns {string} Artwork URL or fallback
          */
         getArtworkUrl: function(song) {
             if (song && song.id) {
                 return '/api/music/stream/artwork/' + song.id;
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
            if (song && song.id) {
                element.src = this.getArtworkUrl(song);
                element.style.display = 'block';
            } else {
                element.src = '/logo.png';
                element.style.display = 'none';
            }
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
                if (song && song.id) {
                    const img = new Image();
                    img.src = this.getArtworkUrl(song);
                    // Preload without blocking
                }
            });
            
            window.Helpers.log('ImageManager preloaded', songs.length, 'images');
        },
        
        /**
         * Clear image cache to free memory
         */
        clearCache: function() {
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