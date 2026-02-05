class SongPageCache {
    constructor() {
        this.CACHE_PREFIX = 'songPage_';
        this.CACHE_DURATION = 6 * 60 * 60 * 1000; // 6 hours
    }

    // Generate cache key from URL and parameters
    getCacheKey(url) {
        return this.CACHE_PREFIX + btoa(url).replace(/[^a-zA-Z0-9]/g, '');
    }

    // Save page to cache
    savePage(url, html) {
        const cacheData = {
            url: url,
            html: html,
            timestamp: Date.now(),
            expiresAt: Date.now() + this.CACHE_DURATION
        };
        localStorage.setItem(this.getCacheKey(url), JSON.stringify(cacheData));
    }

    // Load page from cache if valid (show even if expired)
    loadPage(url) {
        const cached = localStorage.getItem(this.getCacheKey(url));
        if (!cached) return null;

        const data = JSON.parse(cached);
        if (Date.now() > data.expiresAt) {
            // Remove expired entry but return it for display
            localStorage.removeItem(this.getCacheKey(url));
            return data; // Return expired content anyway
        }
        return data;
    }

    // Check if page is cached and valid
    isCached(url) {
        const cached = localStorage.getItem(this.getCacheKey(url));
        if (!cached) return false;

        const data = JSON.parse(cached);
        if (Date.now() > data.expiresAt) {
            localStorage.removeItem(this.getCacheKey(url));
            return false;
        }
        return true;
    }

    // Clean up expired entries
    cleanupExpired() {
        Object.keys(localStorage)
            .filter(key => key.startsWith(this.CACHE_PREFIX))
            .forEach(key => {
                try {
                    const data = JSON.parse(localStorage.getItem(key));
                    if (Date.now() > data.expiresAt) {
                        localStorage.removeItem(key);
                    }
                } catch (e) {
                    localStorage.removeItem(key);
                }
            });
    }

    // Clear all song page cache entries
    clearAll() {
        Object.keys(localStorage)
            .filter(key => key.startsWith(this.CACHE_PREFIX))
            .forEach(key => localStorage.removeItem(key));
    }

    // Get cache statistics
    getStats() {
        const entries = Object.keys(localStorage)
            .filter(key => key.startsWith(this.CACHE_PREFIX))
            .map(key => {
                try {
                    const data = JSON.parse(localStorage.getItem(key));
                    return {
                        key: key,
                        url: data.url,
                        timestamp: data.timestamp,
                        expiresAt: data.expiresAt,
                        isExpired: Date.now() > data.expiresAt
                    };
                } catch (e) {
                    return { key, isInvalid: true };
                }
            });

        return {
            total: entries.length,
            valid: entries.filter(e => !e.isExpired && !e.isInvalid).length,
            expired: entries.filter(e => e.isExpired).length,
            entries: entries
        };
    }
}

// Initialize global cache instance
window.songCache = new SongPageCache();

// Cleanup expired entries on load
window.addEventListener('load', function() {
    window.songCache.cleanupExpired();
});