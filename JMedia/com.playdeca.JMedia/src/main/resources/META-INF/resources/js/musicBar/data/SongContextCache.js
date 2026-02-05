/**
 * SongContextCache - Next/previous song caching system
 * Manages song context caching and validation
 */
(function(window) {
    'use strict';
    
    window.SongContextCache = {
        // Cache data
        cache: null,
        cacheTimestamp: 0,
        
        // Cache duration (30 seconds)
        CACHE_DURATION: 30000,
        
        /**
         * Initialize song context cache
         */
        init: function() {
            this.setupEventListeners();
            window.Helpers.log('SongContextCache initialized');
        },
        
        /**
         * Set up event listeners
         */
        setupEventListeners: function() {
            // Listen for cache update requests
            window.addEventListener('updateSongContextCache', (e) => {
                this.updateCache(e.detail.current, e.detail.previous, e.detail.nextSong, e.detail.profileId);
            });
            
            window.Helpers.log('SongContextCache: Event listeners configured');
        },
        
        /**
         * Update song context cache
         * @param {Object} current - Current song
         * @param {Object} previous - Previous song
         * @param {Object} next - Next song
         * @param {string} profileId - Profile ID
         */
        updateCache: function(current, previous, next, profileId) {
            this.cache = {
                previousSong: previous,
                currentSong: current,
                nextSong: next,
                lastUpdated: Date.now(),
                profileId: profileId
            };
            this.cacheTimestamp = Date.now();
            
            console.log('[SongContextCache] Updated cache:', this.cache);
            
            // Emit cache update event
            window.dispatchEvent(new CustomEvent('songContextCacheUpdated', {
                detail: { cache: this.cache }
            }));
        },
        
        /**
         * Get cached song context
         * @param {string} direction - Direction ('next', 'previous', 'current')
         * @param {string} profileId - Profile ID
         * @returns {Object|null} Cached song or null if invalid
         */
        getCachedSongContext: function(direction, profileId) {
            // Check if cache is valid for current profile
            if (!this.cache || this.cache.profileId !== profileId ||
                    Date.now() - this.cacheTimestamp > this.CACHE_DURATION) {
                console.log('[SongContextCache] Cache invalid or missing - profile:', profileId, 'cache:', this.cache);
                return null;
            }
            
            const cachedSong = direction === 'next' ? this.cache.nextSong :
                           direction === 'previous' ? this.cache.previousSong :
                           this.cache.currentSong;
            
            console.log('[SongContextCache] getCachedSongContext(', direction, ') →', cachedSong ? JSON.stringify(cachedSong) : 'null');
            return cachedSong || null;
        },
        
        /**
         * Validate cache data
         * @param {Object} current - Current song
         * @param {string} profileId - Profile ID
         * @returns {boolean} True if cache data is valid
         */
        validateCache: function(current, profileId) {
            return current && 
                   typeof current.id === 'string' && 
                   current.id !== null && 
                   current.id !== undefined &&
                   this.cache.profileId === profileId;
        },
        
        /**
         * Clear cache
         */
        clearCache: function() {
            this.cache = null;
            this.cacheTimestamp = 0;
            
            window.Helpers.log('SongContextCache: Cache cleared');
            
            // Emit cache cleared event
            window.dispatchEvent(new CustomEvent('songContextCacheCleared', {
                detail: { timestamp: Date.now() }
            }));
        },
        
        /**
         * Get cache status
         * @returns {Object} Cache status
         */
        getCacheStatus: function() {
            return {
                hasCache: this.cache !== null,
                cacheAge: this.cache ? Date.now() - this.cacheTimestamp : 0,
                isExpired: this.cache ? (Date.now() - this.cacheTimestamp) > this.CACHE_DURATION : false,
                profileId: this.cache?.profileId,
                cacheSize: this.cache ? Object.keys(this.cache).length : 0
            };
        },
        
        /**
         * Get current cache data
         * @returns {Object|null} Current cache
         */
        getCurrentCache: function() {
            return this.cache;
        }
    };
    
    // Auto-initialize when dependencies are available
    if (window.Helpers) {
        window.SongContextCache.init();
    } else {
        // Wait for dependencies
        const checkDeps = () => {
            if (window.Helpers) {
                window.SongContextCache.init();
            } else {
                setTimeout(checkDeps, 50);
            }
        };
        setTimeout(checkDeps, 50);
    }
    
})(window);