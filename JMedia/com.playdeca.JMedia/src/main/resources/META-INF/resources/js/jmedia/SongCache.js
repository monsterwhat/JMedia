(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    class SongPageCache {
        constructor() {
            this.CACHE_PREFIX = 'songPage_';
            this.CACHE_DURATION = 6 * 60 * 60 * 1000;
        }

        getCacheKey(url) {
            return this.CACHE_PREFIX + btoa(url).replace(/[^a-zA-Z0-9]/g, '');
        }

        savePage(url, html) {
            const cacheData = {
                url: url,
                html: html,
                timestamp: Date.now(),
                expiresAt: Date.now() + this.CACHE_DURATION
            };
            try {
                localStorage.setItem(this.getCacheKey(url), JSON.stringify(cacheData));
            } catch (err) {
                console.warn('[SongCache] could not save page to localStorage', err);
            }
        }

        loadPage(url) {
            let cached = null;
            try {
                cached = localStorage.getItem(this.getCacheKey(url));
            } catch (err) {
                console.warn('[SongCache] could not read page from localStorage', err);
                return null;
            }
            if (!cached) return null;

            let data;
            try {
                data = JSON.parse(cached);
            } catch (err) {
                console.warn('[SongCache] discarding corrupt cache entry', err);
                try { localStorage.removeItem(this.getCacheKey(url)); } catch (e) { console.warn('[SongCache] could not remove corrupt cache entry', e); }
                return null;
            }
            if (Date.now() > data.expiresAt) {
                try { localStorage.removeItem(this.getCacheKey(url)); } catch (e) { console.warn('[SongCache] could not remove expired cache entry', e); }
                return null;
            }
            return data;
        }

        isCached(url) {
            let cached = null;
            try {
                cached = localStorage.getItem(this.getCacheKey(url));
            } catch (err) {
                console.warn('[SongCache] could not read page from localStorage', err);
                return false;
            }
            if (!cached) return false;

            let data;
            try {
                data = JSON.parse(cached);
            } catch (err) {
                console.warn('[SongCache] discarding corrupt cache entry', err);
                try { localStorage.removeItem(this.getCacheKey(url)); } catch (e) { console.warn('[SongCache] could not remove corrupt cache entry', e); }
                return false;
            }
            if (Date.now() > data.expiresAt) {
                try { localStorage.removeItem(this.getCacheKey(url)); } catch (e) { console.warn('[SongCache] could not remove expired cache entry', e); }
                return false;
            }
            return true;
        }

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
                        console.warn('[SongCache] discarding corrupt cache entry', e);
                        try { localStorage.removeItem(key); } catch (err) { console.warn('[SongCache] could not remove corrupt cache entry', err); }
                    }
                });
        }

        clearAll() {
            Object.keys(localStorage)
                .filter(key => key.startsWith(this.CACHE_PREFIX))
                .forEach(key => localStorage.removeItem(key));
        }

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
                        console.warn('[SongCache] discarding corrupt cache entry', e);
                        try { localStorage.removeItem(key); } catch (err) { console.warn('[SongCache] could not remove corrupt cache entry', err); }
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

    JMedia.SongCache = SongPageCache;
    window.songCache = new SongPageCache();

    window.addEventListener('load', function() {
        window.songCache.cleanupExpired();
    });

})(window);
