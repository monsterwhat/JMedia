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
            
            // Update current song image and favicon synchronously
            if (this.elements.songCoverImage) {
                this.elements.songCoverImage.src = currentArtwork;
            }
            
            if (this.elements.favicon) {
                this.elements.favicon.href = currentArtwork;
            }
            
            // Update page title
            if (this.elements.pageTitle) {
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
            if (song && song.artworkBase64 && song.artworkBase64 !== '') {
                element.src = `data:image/jpeg;base64,${song.artworkBase64}`;
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
            if (!song || !song.artworkBase64 || song.artworkBase64 === '') {
                return '/logo.png';
            }
            return `data:image/jpeg;base64,${song.artworkBase64}`;
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
                if (song && song.artworkBase64 && song.artworkBase64 !== '') {
                    const img = new Image();
                    img.src = `data:image/jpeg;base64,${song.artworkBase64}`;
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