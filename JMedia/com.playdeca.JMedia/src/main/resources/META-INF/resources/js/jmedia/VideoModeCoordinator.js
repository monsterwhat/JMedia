/**
 * VideoModeCoordinator — SINGLE choke point for music ↔ video isolation
 *
 * DJ/music and video are disjoint worlds. When ANY video view is active, the
 * entire music engine is suspended; when the video section is left, control is
 * returned without auto-resuming audio.
 *
 * Where it lives:  js/jmedia/VideoModeCoordinator.js  (loaded before App.js
 *                  and MusicBarInit.js via index.html defer order).
 * Sole writer of window.videoPlaying — per-file early-returns remain as
 * defence-in-depth but are NO LONGER the coordination mechanism.
 *
 * What setVideoMode(true) suspends — atomically, in this order:
 *   1. window.videoPlaying, body[data-video-active], body.video-active
 *   2. Pauses AudioEngine active + next elements (and any stray <audio>)
 *   3. Cancels DJ transition (DjTransitionManager.suspendForVideo / cancel)
 *   4. Locks DJ toggle (isDjLocked → PlaybackController.toggleDjMode blocked)
 *   5. Forces local StateManager.playing=false (no server broadcast)
 *   6. Hides .persistent-music-player (#musicPlayerContainer) via !important
 *   7. Music WS inbound filtering, UIUpdater/ResponsivePlayer churn, and
 *      DesktopAdapter/MediaSession shortcuts all gate on isVideoActive()
 *      and become inert while active.
 *
 * What setVideoMode(false) does:
 *   - Clears window.videoPlaying, body flags, DJ lock, re-shows player shell
 *   - Dispatches 'videoModeChanged' — listeners re-arm without touching audio
 *   - NEVER auto-resumes audio; user must press play.
 *
 * Entry hook points that call setVideoMode:
 *   - App.js:handleRoute / App.js:loadView (route-level guard, canonical)
 *   - MusicBarInit.checkVideoPageState (poll/observer fallback)
 *   - window.setVideoPlaying(active) legacy helper (delegates here)
 *   - DOM observer for overlay-hidden-but-in-DOM players (customPlayer,
 *     videoElement, player-modal.active, .player-container)
 *   - Direct-URL loads (initial location check on DOMContentLoaded)
 *   - popstate / beforeunload / videoSPA destroyCurrentPlayer
 *
 * Exit hook points: same — handleRoute when leaving video, popstate returning
 * to music, App.loadView non-video branch.
 *
 * Logging: every suspend/resume/lock transition logs at INFO level via
 * console.log + Helpers.log; failures log via console.error.
 */
(function(window) {
    'use strict';

    var JMedia = window.JMedia = window.JMedia || {};

    var Coordinator = {
        _active: false,
        _djLocked: false,
        _observer: null,
        _pollTimer: null,
        _resumeTimer: null,
        _resumePendingSource: null,
        _RESUME_DEBOUNCE_MS: 750,
        _initialized: false,

        /**
         * True while ANY video view is active (classic, cinema, overlay).
         * Preferred over raw window.videoPlaying — that flag is kept in sync
         * for backwards compat but this is the authoritative getter.
         */
        isVideoActive: function() {
            return this._active === true;
        },

        /**
         * True while DJ toggle must be rejected. Mirrors _active while video
         * is shown; cleared on resume.
         */
        isDjLocked: function() {
            return this._djLocked === true;
        },

        /**
         * Whether DJ mode may be toggled right now.
         */
        canToggleDj: function() {
            return !this._active && !this._djLocked;
        },

        /**
         * Canonical entry/exit. Idempotent — repeated calls with the same
         * active value are no-ops (logs once at debug level).
         * @param {boolean} active  true = enter video, false = leave
         * @param {string}  source  caller tag for logging (e.g. 'App.loadView:video')
         */
        setVideoMode: function(active, source) {
            active = !!active;
            source = source || 'unknown';

            if (active) {
                if (this._resumeTimer) {
                    this._cancelPendingResume(source);
                }
                if (this._active === true) {
                    if (window.JMedia && JMedia.Helpers) {
                        JMedia.Helpers.log('[VideoModeCoordinator] setVideoMode(true) no-op (' + source + ')');
                    }
                    if (!window.videoPlaying) window.videoPlaying = true;
                    return;
                }
                this._enterVideo(source);
                return;
            }

            // resume (active=false) path — debounced to close DOM-gap flap
            if (this._active === false) {
                if (window.JMedia && JMedia.Helpers) {
                    JMedia.Helpers.log('[VideoModeCoordinator] setVideoMode(false) no-op already inactive (' + source + ')');
                }
                if (window.videoPlaying) window.videoPlaying = false;
                return;
            }
            this._scheduleResume(source);
        },

        _scheduleResume: function(source) {
            if (this._resumeTimer) {
                if (window.JMedia && JMedia.Helpers) {
                    JMedia.Helpers.log('[VideoModeCoordinator] Resume already pending (pendingSource=' + this._resumePendingSource + ') — duplicate request (' + source + ') ignored, awaiting debounce');
                }
                console.log('[VideoModeCoordinator] Resume pending already (pendingSource=' + this._resumePendingSource + ') — duplicate request source=' + source + ' ignored');
                return;
            }
            this._resumePendingSource = source;
            console.log('[VideoModeCoordinator] RESUME requested (source=' + source + ') — debouncing ' + this._RESUME_DEBOUNCE_MS + 'ms sustained inactive required');
            if (JMedia.Helpers && JMedia.Helpers.log) JMedia.Helpers.log('[VideoModeCoordinator] RESUME requested (source=' + source + ') — debouncing ' + this._RESUME_DEBOUNCE_MS + 'ms');
            var self = this;
            this._resumeTimer = setTimeout(function() {
                self._resumeTimer = null;
                var committedSource = self._resumePendingSource;
                self._resumePendingSource = null;
                // Re-check before committing: if DOM/route flipped back to active during debounce, abort
                var stillInactive = false;
                try {
                    stillInactive = !(self.isVideoRoute() || self.hasActiveVideoPlayer());
                } catch (e) {
                    console.error('[VideoModeCoordinator] debounce re-check failed', e);
                    stillInactive = true;
                }
                if (!stillInactive) {
                    console.log('[VideoModeCoordinator] Resume debounce fired but active signal returned — aborting (source=' + committedSource + ')');
                    if (JMedia.Helpers && JMedia.Helpers.log) JMedia.Helpers.log('[VideoModeCoordinator] Resume debounce aborted — active returned (source=' + committedSource + ')');
                    return;
                }
                console.log('[VideoModeCoordinator] Resume debounce committed (source=' + committedSource + ' -> debounce-fired)');
                if (JMedia.Helpers && JMedia.Helpers.log) JMedia.Helpers.log('[VideoModeCoordinator] Resume debounce committed (source=' + committedSource + ')');
                self._leaveVideo(committedSource + ' (debounce-fired)');
            }, this._RESUME_DEBOUNCE_MS);
        },

        _cancelPendingResume: function(cancelSource) {
            if (!this._resumeTimer) return;
            clearTimeout(this._resumeTimer);
            var pending = this._resumePendingSource;
            this._resumeTimer = null;
            this._resumePendingSource = null;
            console.log('[VideoModeCoordinator] Pending resume cancelled — active signal returned (cancelledSource=' + pending + ', newSource=' + cancelSource + ')');
            if (JMedia.Helpers && JMedia.Helpers.log) JMedia.Helpers.log('[VideoModeCoordinator] Pending resume cancelled (cancelledSource=' + pending + ', newSource=' + cancelSource + ')');
        },

        // ------------------------------------------------------------------
        // private: enter
        // ------------------------------------------------------------------
        _enterVideo: function(source) {
            this._active = true;
            this._djLocked = true;

            // Legacy flag — sole writer. Per-file guards remain but now check
            // the coordinator; window.videoPlaying stays for defence-in-depth.
            window.videoPlaying = true;
            try { document.body.classList.add('video-active'); } catch (e) { console.error('[VideoModeCoordinator] body class add failed', e); }
            try { document.body.setAttribute('data-video-active', 'true'); } catch (e) { console.error('[VideoModeCoordinator] setAttribute failed', e); }

            // Hide the persistent music shell immediately (before async view fetch)
            var musicPlayer = document.querySelector('.persistent-music-player') ||
                              document.querySelector('.mobile-player') ||
                              document.getElementById('musicPlayerContainer');
            if (musicPlayer) {
                try {
                    musicPlayer.style.setProperty('display', 'none', 'important');
                    musicPlayer.classList.add('video-active');
                } catch (e) { console.error('[VideoModeCoordinator] hide music player failed', e); }
            }

            // Pause music audio locally — NEVER call PlaybackApi.pause() here
            // (that broadcasts to the server and pauses other tabs).
            try {
                if (window.AudioEngine) {
                    try { window.AudioEngine.pause(); } catch (e) { console.error('[VideoModeCoordinator] AudioEngine.pause failed', e); }
                    var players = [window.AudioEngine.audio, window.AudioEngine.audioNext];
                    for (var i = 0; i < players.length; i++) {
                        var a = players[i];
                        if (!a) continue;
                        try { a.pause(); } catch (e2) { console.error('[VideoModeCoordinator] audio pause failed', e2); }
                        // Do not nuke src on the primary element too aggressively:
                        // App.js also clears src+load; we keep pause-only here so
                        // the coordinator stays safe to call before AudioEngine init.
                    }
                    if (window.AudioEngine._isCrossfading) {
                        window.AudioEngine._isCrossfading = false;
                    }
                }
                // Kill any stray <audio> not owned by AudioEngine (but never <video>)
                var audios = document.querySelectorAll('audio');
                for (var j = 0; j < audios.length; j++) {
                    try { audios[j].pause(); } catch (e3) { console.error('[VideoModeCoordinator] stray audio pause failed', e3); }
                }
            } catch (e) {
                console.error('[VideoModeCoordinator] audio suspend failed', e);
            }

            // Cancel DJ transition & force indicator to none
            try {
                if (window.DjTransitionManager) {
                    if (typeof window.DjTransitionManager.suspendForVideo === 'function') {
                        window.DjTransitionManager.suspendForVideo();
                    } else if (typeof window.DjTransitionManager.cancelTransition === 'function') {
                        window.DjTransitionManager.cancelTransition();
                    }
                    if (typeof window.DjTransitionManager.updateDjIndicator === 'function') {
                        window.DjTransitionManager.updateDjIndicator('none');
                    }
                }
            } catch (e) { console.error('[VideoModeCoordinator] DJ suspend failed', e); }

            // Force local playing=false so WS re-broadcasts don't resurrect audio
            try {
                if (window.StateManager && typeof window.StateManager.updateState === 'function') {
                    var st = window.StateManager.getState ? window.StateManager.getState() : null;
                    if (st && st.playing) {
                        window.StateManager.updateState({ playing: false }, 'videoCoordinator.suspend');
                    }
                }
            } catch (e) { console.error('[VideoModeCoordinator] StateManager playing=false failed', e); }

            // Lock DJ button UI (visual disabled state)
            try {
                var djBtn = document.getElementById('djModeBtn');
                if (djBtn) {
                    djBtn.setAttribute('disabled', 'true');
                    djBtn.setAttribute('aria-disabled', 'true');
                    djBtn.title = 'DJ Mode locked while video is active';
                    djBtn.style.opacity = '0.45';
                    djBtn.style.pointerEvents = 'none';
                }
                var djIcon = document.getElementById('djModeIcon');
                if (djIcon) {
                    djIcon.className = 'pi pi-headphones';
                }
            } catch (e) { console.error('[VideoModeCoordinator] DJ lock UI failed', e); }

            console.log('[VideoModeCoordinator] SUSPEND music engine — video active (source=' + source + ')');
            if (JMedia.Helpers && JMedia.Helpers.log) JMedia.Helpers.log('[VideoModeCoordinator] SUSPEND (source=' + source + ')');
            console.log('[VideoModeCoordinator] DJ locked (source=' + source + ')');

            this._emitChanged(true, source);
        },

        // ------------------------------------------------------------------
        // private: leave
        // ------------------------------------------------------------------
        _leaveVideo: function(source) {
            this._active = false;
            this._djLocked = false;

            window.videoPlaying = false;
            window.musicWasPlayingBeforeVideo = false;
            try { document.body.classList.remove('video-active'); } catch (e) { console.error('[VideoModeCoordinator] body class remove failed', e); }
            try { document.body.setAttribute('data-video-active', 'false'); } catch (e) { console.error('[VideoModeCoordinator] setAttribute false failed', e); }

            var musicPlayer = document.querySelector('.persistent-music-player') ||
                              document.querySelector('.mobile-player') ||
                              document.getElementById('musicPlayerContainer');
            if (musicPlayer) {
                try {
                    musicPlayer.style.removeProperty('display');
                    musicPlayer.classList.remove('video-playing', 'video-active');
                } catch (e) { console.error('[VideoModeCoordinator] show music player failed', e); }
            }

            // Re-arm DJ button UI — NO auto-resume of audio
            try {
                var djBtn = document.getElementById('djModeBtn');
                if (djBtn) {
                    djBtn.removeAttribute('disabled');
                    djBtn.removeAttribute('aria-disabled');
                    djBtn.title = 'Toggle DJ Mode (Seamless Transitions)';
                    djBtn.style.opacity = '';
                    djBtn.style.pointerEvents = '';
                }
            } catch (e) { console.error('[VideoModeCoordinator] DJ unlock UI failed', e); }

            // Let UIUpdater re-apply artwork/title on next music view entry
            // (App.js calls forceRefresh when returning to music). No audio resume.

            console.log('[VideoModeCoordinator] RESUME music engine — video inactive (source=' + source + ')');
            if (JMedia.Helpers && JMedia.Helpers.log) JMedia.Helpers.log('[VideoModeCoordinator] RESUME (source=' + source + ')');
            console.log('[VideoModeCoordinator] DJ unlocked (source=' + source + ')');

            this._emitChanged(false, source);
        },

        _emitChanged: function(active, source) {
            try {
                window.dispatchEvent(new CustomEvent('videoModeChanged', {
                    detail: { active: active, source: source, timestamp: Date.now() }
                }));
            } catch (e) { console.error('[VideoModeCoordinator] dispatch videoModeChanged failed', e); }
        },

        // ------------------------------------------------------------------
        // Route / DOM sync helpers
        // ------------------------------------------------------------------
        /**
         * Returns true if the current SPA location is inside the video section.
         * Covers /video, /video?section=..., /video-classic, /video-test.
         */
        isVideoRoute: function() {
            var p = window.location.pathname || '';
            return p === '/video' || p === '/video-classic' || p.indexOf('/video') === 0;
        },

        /**
         * Overlay-hidden-but-in-DOM detection. Returns true if an active video
         * player is present in the DOM even when not on a /video route.
         */
        hasActiveVideoPlayer: function() {
            // SPA player
            if (document.getElementById('customPlayer')) return true;
            var ve = document.getElementById('videoElement');
            if (ve && document.body.contains(ve)) return true;
            // Player modal overlay (cinema / classic)
            var modal = document.getElementById('player-modal');
            if (modal && modal.classList.contains('active')) return true;
            var backdrop = document.getElementById('player-modal-backdrop');
            if (backdrop && backdrop.classList.contains('active')) return true;
            // Legacy containers
            if (document.querySelector('.player-container')) return true;
            var cinemaModal = document.getElementById('cinema-modal');
            if (cinemaModal && cinemaModal.classList.contains('active')) return true;
            return false;
        },

        /**
         * Sync coordinator state from the current route + DOM. Used by the
         * MutationObserver / interval fallback and by direct-URL loads.
         */
        syncFromDom: function(source) {
            source = source || 'syncFromDom';
            var shouldBeActive = this.isVideoRoute() || this.hasActiveVideoPlayer();
            this.setVideoMode(shouldBeActive, source);
        },

        // ------------------------------------------------------------------
        // Init: location check, observers, popstate hook
        // ------------------------------------------------------------------
        init: function() {
            if (this._initialized) return;
            this._initialized = true;

            // Seed window.videoPlaying for modules that load before us
            if (typeof window.videoPlaying === 'undefined') window.videoPlaying = false;

            // Immediate sync for direct-URL video loads (before App.js fetches)
            try {
                this.syncFromDom('init.location');
            } catch (e) { console.error('[VideoModeCoordinator] init sync failed', e); }

            // Back/forward navigation
            window.addEventListener('popstate', function() {
                try { Coordinator.syncFromDom('popstate'); } catch (e) { console.error('[VideoModeCoordinator] popstate sync failed', e); }
            });

            // HTMX afterSwap may inject a player without a route change
            try {
                document.body.addEventListener('htmx:afterSwap', function() {
                    setTimeout(function() { try { Coordinator.syncFromDom('htmx:afterSwap'); } catch (e) { console.error('[VideoModeCoordinator] htmx sync failed', e); } }, 100);
                });
            } catch (e) { /* body not yet parser-available — observer will cover */ }

            // MutationObserver for overlay players hidden-but-in-DOM
            try {
                var obsTarget = document.body || document.documentElement;
                this._observer = new MutationObserver(function() {
                    try { Coordinator.syncFromDom('mutation'); } catch (e) { console.error('[VideoModeCoordinator] mutation sync failed', e); }
                });
                this._observer.observe(obsTarget, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
            } catch (e) { console.error('[VideoModeCoordinator] MutationObserver failed', e); }

            // Interval fallback (1s) — cheap, keeps in sync if observer misses
            this._pollTimer = setInterval(function() {
                try { Coordinator.syncFromDom('poll'); } catch (e) { console.error('[VideoModeCoordinator] poll sync failed', e); }
            }, 1000);

            // Cleanup on unload
            window.addEventListener('beforeunload', function() {
                try { if (Coordinator._observer) Coordinator._observer.disconnect(); } catch (e) { console.error('[VideoModeCoordinator] beforeunload observer disconnect failed', e); }
                try { if (Coordinator._pollTimer) clearInterval(Coordinator._pollTimer); } catch (e) { console.error('[VideoModeCoordinator] beforeunload poll clear failed', e); }
            });
            window.addEventListener('pagehide', function() {
                try { if (Coordinator._observer) Coordinator._observer.disconnect(); } catch (e) { console.error('[VideoModeCoordinator] pagehide observer disconnect failed', e); }
                try { if (Coordinator._pollTimer) clearInterval(Coordinator._pollTimer); } catch (e) { console.error('[VideoModeCoordinator] pagehide poll clear failed', e); }
            });

            console.log('[VideoModeCoordinator] Initialized (videoActive=' + this._active + ')');
        }
    };

    JMedia.VideoModeCoordinator = Coordinator;
    window.VideoModeCoordinator = Coordinator;

    // Auto-init once DOM is interactive — App.js also syncs on handleRoute,
    // so even if this races, the route guard is the canonical writer.
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function() { Coordinator.init(); });
        // Defer-order race fallback — init is idempotent
        setTimeout(function() { Coordinator.init(); }, 0);
    } else {
        Coordinator.init();
    }

})(window);
