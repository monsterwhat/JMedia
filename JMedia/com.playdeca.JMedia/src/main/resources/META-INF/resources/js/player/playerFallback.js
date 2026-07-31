/**
 * playerFallback.js — cross-engine fallback dispatcher (B3).
 *
 * Chain: OPlayer -> videojs -> simple. Triggered ONLY when an adapter's init
 * fails or times out (B2's error path in oplayer-adapter.js, or the
 * TestPlayerFeatures silent bail in test-player-common.js). Never fires
 * during normal playback and never changes the user's default player
 * setting.
 *
 * The failed engine is detected from the DOM (which element the current
 * fragment branch rendered) and the chain resumes from the NEXT engine,
 * stopping at the first successful init. Each fragment only ships its own
 * branch's scripts, so the target engine's scripts are lazy-loaded on
 * demand.
 */
(function() {
    'use strict';

    /* On in-page fragment navigation (e.g. test-player-common
     * _loadVideoFragment) this file is re-injected with the fragment's other
     * scripts. Reset the one-shot guard so the new fragment's engines can
     * fall back again, but never re-register the window hook or the event
     * listener. */
    if (window.__playerFallback) {
        if (typeof window.__playerFallback.reset === 'function') {
            window.__playerFallback.reset();
        }
        return;
    }

    var ENGINE_CHAIN = ['oplayer', 'videojs', 'simple'];

    var SCRIPT_TIMEOUT_MS = 10000;

    var _running = false;    /* a fallback cycle is in progress */
    var _completed = false;  /* a fallback already succeeded for this fragment */
    var _generation = 0;     /* bumped by reset() to invalidate in-flight fallback cycles */

    /* ----------------------------- script loading ----------------------------- */

    function loadScripts(specs, done) {
        var i = 0;
        function next() {
            while (i < specs.length) {
                var spec = specs[i];
                i++;
                if (spec.global && window[spec.global] !== undefined) {
                    continue; /* already loaded by the fragment or a previous fallback */
                }
                loadOne(spec, function(ok) {
                    if (ok) {
                        next();
                        return;
                    }
                    if (spec.optional) {
                        console.warn('[PlayerFallback] optional script failed, continuing: ' + spec.src);
                        next();
                        return;
                    }
                    done(false);
                });
                return;
            }
            done(true);
        }
        next();
    }

    function loadOne(spec, cb) {
        var src = spec.src;
        var el = document.createElement('script');
        el.src = src;
        el.async = false;
        var settled = false;
        var timer = setTimeout(function() {
            if (settled) return;
            settled = true;
            console.warn('[PlayerFallback] script load timed out: ' + src);
            cb(false);
        }, SCRIPT_TIMEOUT_MS);
        el.onload = function() {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            if (spec.global && window[spec.global] === undefined) {
                console.warn('[PlayerFallback] script loaded but global missing: ' + spec.global + ' (' + src + ')');
                cb(false);
                return;
            }
            cb(true);
        };
        el.onerror = function() {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            console.warn('[PlayerFallback] script load failed: ' + src);
            cb(false);
        };
        document.head.appendChild(el);
    }

    /* --------------------------- DOM helpers --------------------------- */

    function removeOPlayerChrome() {
        var errEl = document.getElementById('oplayerLoadError');
        if (errEl && errEl.parentNode) errEl.parentNode.removeChild(errEl);
        var oContainer = document.getElementById('oplayerContainer');
        if (oContainer && oContainer.parentNode) oContainer.parentNode.removeChild(oContainer);
        if (typeof window.destroyOPlayerAdapter === 'function') {
            try { window.destroyOPlayerAdapter(); } catch (e) {
                console.warn('[PlayerFallback] destroyOPlayerAdapter failed', e);
            }
        }
    }

    function ensureVideoElement(withVjsClass) {
        var videoEl = document.getElementById('videoElement');
        if (videoEl) return videoEl;
        videoEl = document.createElement('video');
        videoEl.id = 'videoElement';
        videoEl.setAttribute('crossorigin', 'anonymous');
        videoEl.setAttribute('playsinline', '');
        videoEl.setAttribute('autoplay', '');
        if (withVjsClass) videoEl.className = 'video-js vjs-default-skin';
        var wrapper = document.querySelector('#customPlayer .video-wrapper');
        var canvas = document.getElementById('assCanvas');
        if (wrapper && canvas && canvas.parentNode === wrapper) {
            wrapper.insertBefore(videoEl, canvas);
        } else if (wrapper) {
            wrapper.appendChild(videoEl);
        } else {
            var container = document.getElementById('customPlayer');
            if (container) container.appendChild(videoEl);
        }
        return videoEl;
    }

    /* --------------------------- per-engine definitions --------------------------- */

    var ENGINES = {
        oplayer: {
            scripts: [
                { src: '/lib/oplayer/core.min.js', global: 'OPlayer' },
                { src: '/lib/oplayer/ui.min.js', global: 'OUI' },
                { src: '/lib/hls.min.js', global: 'Hls', optional: true },
                { src: '/lib/oplayer/hls.min.js', global: 'OHls', optional: true },
                { src: '/js/player/Utils.js?v=3', global: 'PlayerUtils' },
                { src: '/js/player/oplayer-adapter.js?v=4', global: 'initOPlayerAdapter' }
            ],
            ready: function() {
                return typeof window.OPlayer !== 'undefined' &&
                       typeof window.initOPlayerAdapter === 'function';
            },
            prepare: function() {
                var wrapper = document.querySelector('#customPlayer .video-wrapper');
                if (!document.getElementById('oplayerContainer') && wrapper) {
                    var el = document.createElement('div');
                    el.id = 'oplayerContainer';
                    el.className = 'oplayer-wrapper';
                    var canvas = document.getElementById('assCanvas');
                    if (canvas && canvas.parentNode === wrapper) wrapper.insertBefore(el, canvas);
                    else wrapper.appendChild(el);
                }
            },
            init: function(videoId) {
                this.prepare();
                try {
                    window.initOPlayerAdapter(videoId);
                    return true;
                } catch (e) {
                    console.error('[PlayerFallback] oplayer init threw:', e);
                    return false;
                }
            }
        },
        videojs: {
            scripts: [
                { src: '/lib/videojs/video.min.js', global: 'videojs' },
                { src: '/js/video/VideoWebSocketManager.js', global: 'VideoWebSocketManager', optional: true },
                { src: '/js/player/Utils.js?v=3', global: 'PlayerUtils' },
                { src: '/js/player/videojs-adapter.js?v=4', global: 'initVideoJsAdapter' },
                { src: '/js/test-player-common.js?v=2', global: 'TestPlayerFeatures', optional: true }
            ],
            ready: function() {
                return typeof window.videojs === 'function' &&
                       typeof window.initVideoJsAdapter === 'function';
            },
            prepare: function() {
                removeOPlayerChrome();
                /* The oplayer adapter hides the settings toggle when OUI is
                 * active; videojs needs it visible. */
                var toggle = document.getElementById('settingsToggleBtn');
                if (toggle) toggle.style.display = '';
                ensureVideoElement(true);
            },
            init: function(videoId) {
                this.prepare();
                try {
                    window.initVideoJsAdapter(videoId);
                    return true;
                } catch (e) {
                    console.error('[PlayerFallback] videojs init threw:', e);
                    return false;
                }
            }
        },
        simple: {
            scripts: [
                { src: '/js/player/Utils.js?v=3', global: 'PlayerUtils' },
                { src: '/js/player/StateManager.js', global: 'PlayerStateManager' },
                { src: '/js/player/StreamManager.js', global: 'PlayerStreamManager' },
                { src: '/js/player/UIBuilder.js?v=5', global: 'PlayerUIBuilder' },
                { src: '/js/player/ControlsManager.js', global: 'PlayerControlsManager' },
                { src: '/js/player/FullscreenManager.js', global: 'PlayerFullscreenManager' },
                { src: '/js/player/SubtitleController.js', global: 'PlayerSubtitleController' },
                { src: '/js/player/AudioTrackSelector.js', global: 'PlayerAudioTrackSelector' },
                { src: '/js/player/SubtitleSettingsUI.js', global: 'PlayerSubtitleSettingsUI' },
                { src: '/js/player/StoryboardManager.js', global: 'PlayerStoryboardManager' },
                { src: '/js/player/EventBinder.js?v=4', global: 'PlayerEventBinder' },
                { src: '/js/player/KeyboardShortcuts.js', global: 'PlayerKeyboardShortcuts' },
                { src: '/js/player/SkipController.js', global: 'PlayerSkipController' },
                { src: '/js/player/ProgressReporter.js', global: 'PlayerProgressReporter' },
                { src: '/js/player/NavigationManager.js?v=2', global: 'PlayerNavigationManager' },
                { src: '/js/video/VideoWebSocketManager.js', global: 'VideoWebSocketManager', optional: true },
                { src: '/js/simple-player.js', global: 'SimplePlayer' }
            ],
            ready: function() {
                return typeof window.SimplePlayer === 'function' &&
                       typeof window.PlayerUtils !== 'undefined' &&
                       typeof window.PlayerStateManager !== 'undefined' &&
                       typeof window.PlayerStreamManager !== 'undefined' &&
                       typeof window.PlayerUIBuilder !== 'undefined' &&
                       typeof window.PlayerControlsManager !== 'undefined' &&
                       typeof window.PlayerFullscreenManager !== 'undefined' &&
                       typeof window.PlayerSubtitleController !== 'undefined' &&
                       typeof window.PlayerAudioTrackSelector !== 'undefined' &&
                       typeof window.PlayerSubtitleSettingsUI !== 'undefined' &&
                       typeof window.PlayerStoryboardManager !== 'undefined' &&
                       typeof window.PlayerEventBinder !== 'undefined' &&
                       typeof window.PlayerKeyboardShortcuts !== 'undefined' &&
                       typeof window.PlayerSkipController !== 'undefined' &&
                       typeof window.PlayerProgressReporter !== 'undefined' &&
                       typeof window.PlayerNavigationManager !== 'undefined';
            },
            prepare: function() {
                /* Dispose of a partially-built videojs player when coming from
                 * the videojs engine. */
                if (typeof window.destroyVideoJsAdapter === 'function') {
                    try { window.destroyVideoJsAdapter(); } catch (e) {
                        console.warn('[PlayerFallback] destroyVideoJsAdapter failed', e);
                    }
                }
                removeOPlayerChrome();
                /* SimplePlayer's UIBuilder injects its own controls + settings
                 * menu; drop the template chrome that would duplicate ids. */
                ['subtitleMenu', 'settingsToggleBtn'].forEach(function(id) {
                    var el = document.getElementById(id);
                    if (el && el.parentNode) el.parentNode.removeChild(el);
                });
                var back = document.querySelector('#customPlayer .back-button-container');
                if (back && back.parentNode) back.parentNode.removeChild(back);
                var container = document.getElementById('customPlayer');
                if (container) container.classList.add('paused');
                var videoEl = ensureVideoElement(false);
                if (videoEl) videoEl.classList.remove('video-js', 'vjs-default-skin');
            },
            init: function(videoId) {
                this.prepare();
                try {
                    new window.SimplePlayer({
                        containerId: 'customPlayer',
                        videoId: 'videoElement',
                        currentVideoId: videoId
                    });
                    return true;
                } catch (e) {
                    console.error('[PlayerFallback] simple init threw:', e);
                    return false;
                }
            }
        }
    };

    /* --------------------------- chain runner --------------------------- */

    function detectFailedEngine() {
        if (document.getElementById('oplayerContainer')) return 'oplayer';
        var videoEl = document.getElementById('videoElement');
        if (videoEl) return videoEl.classList.contains('video-js') ? 'videojs' : 'simple';
        return 'oplayer'; /* the default player setting */
    }

    function requestPlayerFallback(videoId) {
        if (_running) {
            console.log('[PlayerFallback] fallback already in progress, ignoring request');
            return;
        }
        if (_completed) {
            console.log('[PlayerFallback] fallback already completed for this fragment, ignoring request');
            return;
        }
        if (!videoId) {
            var container = document.getElementById('customPlayer');
            if (container && container.dataset) {
                videoId = container.dataset.videoId || container.dataset.liveChannelId;
            }
        }
        if (!videoId) {
            console.warn('[PlayerFallback] no videoId available for fallback');
            return;
        }
        _running = true;
        var gen = _generation;
        var failed = detectFailedEngine();
        var startIdx = ENGINE_CHAIN.indexOf(failed);
        if (startIdx < 0) startIdx = 0;
        var nextIdx = startIdx + 1;
        if (nextIdx < ENGINE_CHAIN.length) {
            console.log('[PlayerFallback] ' + failed + ' init failed — trying: ' + ENGINE_CHAIN[nextIdx]);
        } else {
            console.warn('[PlayerFallback] ' + failed + ' init failed — no engines left');
        }
        tryNext(nextIdx, videoId, gen);
    }

    function tryNext(idx, videoId, gen) {
        if (gen !== _generation) { console.warn('[PlayerFallback] cycle superseded (fragment changed); aborting stale fallback'); return; }
        if (idx >= ENGINE_CHAIN.length) {
            _running = false;
            console.error('[PlayerFallback] all engines failed for videoId', videoId);
            return;
        }
        var name = ENGINE_CHAIN[idx];
        var engine = ENGINES[name];
        ensureEngineReady(engine, function(ok) {
            if (!ok) {
                console.warn('[PlayerFallback] engine unavailable: ' + name);
                tryNext(idx + 1, videoId, gen);
                return;
            }
            if (gen !== _generation) { console.warn('[PlayerFallback] cycle superseded during engine load; aborting'); return; }
            var initOk = engine.init.call(engine, videoId);
            if (initOk) {
                _running = false;
                _completed = true;
                console.log('[PlayerFallback] active engine: ' + name);
            } else {
                console.warn('[PlayerFallback] engine init failed: ' + name);
                tryNext(idx + 1, videoId, gen);
            }
        });
    }

    function ensureEngineReady(engine, done) {
        if (engine.ready()) {
            done(true);
            return;
        }
        loadScripts(engine.scripts, function(ok) {
            if (!ok) {
                done(false);
                return;
            }
            done(engine.ready());
        });
    }

    /* --------------------------- public surface --------------------------- */

    window.__playerFallback = {
        reset: function() {
            _generation++;
            _running = false;
            _completed = false;
            console.log('[PlayerFallback] state reset for new fragment');
        }
    };

    if (typeof window.requestPlayerFallback !== 'function') {
        window.requestPlayerFallback = requestPlayerFallback;
    }

    window.addEventListener('oplayer:fallback-requested', function(e) {
        var detail = e && e.detail;
        requestPlayerFallback(detail && detail.videoId);
    });
})();
