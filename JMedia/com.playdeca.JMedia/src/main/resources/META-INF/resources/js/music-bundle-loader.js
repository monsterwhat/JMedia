/**
 * music-bundle-loader.js ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Â¦Ãƒâ€šÃ‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â route-split loader for the music experience.
 *
 * The music bar engine (/js/musicBar/*) and the jmedia audio/music modules are
 * only used outside /video: on /video the music engine is fully suspended by
 * VideoModeCoordinator, so downloading ~1 MB of music JS there is pointless.
 *
 * Bootstrap rules:
 *   - pathname starting with /video (cinema views, /video-classic, deep links)
 *       ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¾ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ bundle is skipped entirely. window.__musicBundleSkipped = true.
 *   - any other route (/music, /settings, /import, ...)
 *       ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¾ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ bundle is injected immediately, one <script> at a time, in the exact
 *         order index.html used to declare them, so module construction order
 *         inside the bundle is unchanged.
 *
 * window.__musicBundleLoaded is always set:
 *   resolves true once every script in the bundle executed,
 *   resolves false when the bundle was skipped for a video route.
 */
(function (window) {
    'use strict';

    // Original document order from index.html preserved, including the
    // cache-busting query strings.
    var MUSIC_SCRIPTS = [
        '/js/musicBar/utils/Helpers.js?v=2',
        '/js/musicBar/utils/ActionTracker.js?v=2',
        '/js/musicBar/core/DeviceManager.js?v=2',
        '/js/musicBar/core/SynchronizationManager.js?v=2',
        '/js/musicBar/core/StateManager.js?v=2',
        '/js/musicBar/data/StatePersistence.js?v=2',
        '/js/musicBar/data/SongContextCache.js?v=2',
        '/js/musicBar/data/QueueManager.js?v=2',
        '/lib/howler.min.js',
        '/js/musicBar/core/AudioEngine.js?v=11',
        '/js/musicBar/core/DjTransitionManager.js?v=6',
        '/js/musicBar/core/WebSocketManager.js?v=4',
        '/js/musicBar/controls/PlaybackController.js?v=7',
        '/js/musicBar/controls/TimeController.js?v=2',
        '/js/musicBar/controls/VolumeController.js?v=2',
        '/js/musicBar/ui/UIUpdater.js?v=2',
        '/js/musicBar/ui/EventBindings.js?v=2',
        '/js/musicBar/ui/DjSettingsPanel.js?v=1',
        '/js/musicBar/ui/ImageManager.js?v=2',
        '/js/musicBar/mobile/MobileBridge.js?v=2',
        '/js/musicBar/responsive/ResponsivePlayer.js?v=3',
        '/js/musicBar/adapters/DesktopAdapter.js?v=2',
        '/js/musicBar/adapters/MobileAdapter.js?v=2',
        '/js/jmedia/Player.js',
        '/js/jmedia/PlaybackApi.js',
        '/js/jmedia/QueueManager.js',
        '/js/jmedia/SongCache.js',
        '/js/jmedia/MusicIndex.js?v=2',
        '/js/jmedia/MobileContextMenu.js?v=2',
        '/js/jmedia/MusicDragDrop.js?v=2',
        '/js/jmedia/MusicBarInit.js?v=5',
        '/js/jmedia/MediaSession.js',
        '/js/jmedia/HistoryManager.js',
        '/js/jmedia/PlaylistBar.js',
        '/js/jmedia/PlaylistBodyHelper.js',
        '/js/jmedia/MobileApp.js'
    ];

    // These modules are injected dynamically, so they can finish downloading
    // AFTER DOMContentLoaded has already fired. Their original init pattern
    // (addEventListener('DOMContentLoaded', init)) would then never run and the
    // music bar would silently stay dead. __whenDomReady handles both cases.
    window.__whenDomReady = function (fn) {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', fn, { once: true });
        } else {
            fn();
        }
    };

    // Video routes: /video, /video-classic, /video/watch/..., /video/info/...
    // No deep link on a video route can ever start music playback, so the
    // bundle is never needed there.
    if (window.location.pathname.indexOf('/video') === 0) {
        window.__musicBundleSkipped = true;
        window.__musicBundleLoaded = Promise.resolve(false);
        return;
    }

    window.__musicBundleSkipped = false;
    window.__musicBundleLoaded = new Promise(function (resolve) {
        // Fetch the whole bundle in parallel: dynamically inserted scripts with
        // async=false download concurrently but still EXECUTE in insertion
        // order, which is exactly the ordering guarantee the old onload chain
        // provided. Serialising the downloads cost one round trip per module
        // (~235 ms each, ~8 s before the last module ran) and is what made the
        // music view miss its init on Chromium.
        var remaining = MUSIC_SCRIPTS.length;

        function oneDone() {
            if (--remaining === 0) {
                resolve(true);
            }
        }

        MUSIC_SCRIPTS.forEach(function (src) {
            try {
                var el = document.createElement('script');
                el.src = src;
                // async=false keeps dynamic scripts in insertion order even if
                // the loader pipes them faster than they resolve.
                el.async = false;
                el.onload = oneDone;
                el.onerror = function () {
                    // A failed music script must not kill the bundle: every
                    // module that follows re-checks its dependencies itself
                    // (MusicBarInit.verifyModules logs missing modules) and
                    // all shell call sites are guarded with existence checks.
                    console.error('[music-bundle-loader] failed to load ' + src);
                    oneDone();
                };
                (document.head || document.documentElement).appendChild(el);
            } catch (err) {
                console.error('[music-bundle-loader] injection threw for ' + src, err);
                oneDone();
            }
        });
    });
})(window);
