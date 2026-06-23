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
            localStorage.setItem(this.getCacheKey(url), JSON.stringify(cacheData));
        }

        loadPage(url) {
            const cached = localStorage.getItem(this.getCacheKey(url));
            if (!cached) return null;

            const data = JSON.parse(cached);
            if (Date.now() > data.expiresAt) {
                localStorage.removeItem(this.getCacheKey(url));
                return data;
            }
            return data;
        }

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
