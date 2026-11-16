(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    JMedia.Player = {};

    JMedia.Player.isSongListTarget = function(event) {
        const target = event && event.detail && event.detail.target;
        if (!target) return false;
        const targetId = target.id;
        return targetId === 'songTableBody' || targetId === 'mobileSongList';
    };

    JMedia.Player.initHtmxCache = function() {
        document.addEventListener('htmx:beforeRequest', function(event) {
            if (JMedia.Player.isSongListTarget(event) && window.songCache) {
                const target = event.detail.target;
                if (!target) return;
                const url = event.detail.requestConfig.path;
                const cached = window.songCache.loadPage(url);
                if (cached) {
                    target.innerHTML = cached.html;
                }
            }
        });

        document.addEventListener('htmx:afterRequest', function(event) {
            if (JMedia.Player.isSongListTarget(event) && event.detail.successful && window.songCache) {
                const url = event.detail.requestConfig.path;
                const freshHtml = event.detail.xhr.response;
                window.songCache.savePage(url, freshHtml);
            }
        });

        document.addEventListener('htmx:responseError', function(event) {
            if (JMedia.Player.isSongListTarget(event) && window.songCache) {
                const target = event.detail.target;
                const targetId = target ? target.id : undefined;
                const status = event.detail.xhr ? event.detail.xhr.status : undefined;
                console.warn('[Player] request failed; serving cached page', { status: status, targetId: targetId });
                const url = event.detail.requestConfig.path;
                const cachedPage = window.songCache.loadPage(url);
                if (cachedPage && target) {
                    target.innerHTML = cachedPage.html;
                    if (window.Toast) {
                        window.Toast.info('Showing cached content (offline)');
                    }
                }
            }
        });

        document.addEventListener('htmx:timeout', function(event) {
            if (JMedia.Player.isSongListTarget(event) && window.songCache) {
                const target = event.detail.target;
                const targetId = target ? target.id : undefined;
                const status = event.detail.xhr ? event.detail.xhr.status : undefined;
                console.warn('[Player] request failed; serving cached page', { status: status, targetId: targetId });
                const url = event.detail.requestConfig.path;
                const cachedPage = window.songCache.loadPage(url);
                if (cachedPage && target) {
                    target.innerHTML = cachedPage.html;
                    if (window.Toast) {
                        window.Toast.info('Showing cached content (timeout)');
                    }
                }
            }
        });
    };

    (window.__whenDomReady || function (fn) {
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn, { once: true });
        else fn();
    })(function() {
        JMedia.Player.initHtmxCache();
    });

    window.isSongListTarget = JMedia.Player.isSongListTarget;

})(window);
