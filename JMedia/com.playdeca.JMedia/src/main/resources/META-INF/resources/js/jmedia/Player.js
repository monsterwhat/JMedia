(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    JMedia.Player = {};

    JMedia.Player.isSongListTarget = function(event) {
        const targetId = event.detail.target.id;
        return targetId === 'songTableBody' || targetId === 'mobileSongList';
    };

    JMedia.Player.initHtmxCache = function() {
        document.addEventListener('htmx:beforeRequest', function(event) {
            if (JMedia.Player.isSongListTarget(event) && window.songCache) {
                const url = event.detail.requestConfig.path;
                const cached = window.songCache.loadPage(url);
                if (cached) {
                    event.detail.target.innerHTML = cached.html;
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
                const url = event.detail.requestConfig.path;
                const cachedPage = window.songCache.loadPage(url);
                if (cachedPage) {
                    event.detail.target.innerHTML = cachedPage.html;
                    if (window.Toast) {
                        window.Toast.info('Showing cached content (offline)');
                    }
                }
            }
        });

        document.addEventListener('htmx:timeout', function(event) {
            if (JMedia.Player.isSongListTarget(event) && window.songCache) {
                const url = event.detail.requestConfig.path;
                const cachedPage = window.songCache.loadPage(url);
                if (cachedPage) {
                    event.detail.target.innerHTML = cachedPage.html;
                    if (window.Toast) {
                        window.Toast.info('Showing cached content (timeout)');
                    }
                }
            }
        });
    };

    document.addEventListener('DOMContentLoaded', function() {
        JMedia.Player.initHtmxCache();
    });

    window.isSongListTarget = JMedia.Player.isSongListTarget;

})(window);
