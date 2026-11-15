/**
 * hls-loader.js — on-demand loader for hls.js (~542 KB).
 *
 * hls.min.js used to be a <script> tag in every playback fragment, so every
 * playback (including plain direct MP4) paid for it. Only the two HLS branches
 * need it, and both can await ensureHlsLoaded() before touching `Hls`.
 *
 * The promise is cached: concurrent callers share one request, repeat callers
 * resolve immediately once window.Hls exists, and a failed load clears the cache
 * so the next call can retry instead of permanently poisoning HLS playback.
 */
(function (window) {
    'use strict';

    var loadPromise = null;

    window.ensureHlsLoaded = function ensureHlsLoaded() {
        if (window.Hls) return Promise.resolve();
        if (loadPromise) return loadPromise;

        loadPromise = new Promise(function (resolve, reject) {
            var script = document.createElement('script');
            script.src = '/lib/hls.min.js?v=1.6.15';
            script.async = true;
            var settled = false;
            var timer = setTimeout(function () {
                if (settled) return;
                settled = true;
                loadPromise = null;
                try { script.remove(); } catch (e) { console.warn('[hls-loader] failed to remove stalled script', e); }
                var err = new Error('Timed out loading /lib/hls.min.js?v=1.6.15');
                console.error('[hls-loader] hls.js load timed out', err);
                reject(err);
            }, 20000);
            script.onload = function () {
                if (settled) return;
                settled = true;
                clearTimeout(timer);
                if (!window.Hls) {
                    // The file loaded but did not define the global: treat it as a
                    // failure so callers fall back instead of throwing on `new Hls`.
                    loadPromise = null;
                    var missing = new Error('hls.min.js loaded but window.Hls is undefined');
                    console.error('[hls-loader] ' + missing.message);
                    reject(missing);
                    return;
                }
                resolve();
            };
            script.onerror = function () {
                if (settled) return;
                settled = true;
                clearTimeout(timer);
                loadPromise = null;
                var err = new Error('Failed to load /lib/hls.min.js?v=1.6.15');
                console.error('[hls-loader] hls.js failed to load', err);
                reject(err);
            };
            (document.head || document.documentElement).appendChild(script);
        });

        return loadPromise;
    };
})(window);
