/**
 * DjTransitionManager - Mixing-only executor for DJ Mode transitions.
 *
 * This module does NOT select, reorder, or advance songs. Song selection (Smart
 * Shuffle) is owned by the server. The client only executes the audio mix:
 * 1. Server plans the transition and sends djTransitionPlanned=true + djNextSongId
 *    via state/WebSocket.
 * 2. This module monitors the primary audio element's currentTime.
 * 3. At exitTime, preloads (already buffered) and crossfades to the next track
 *    at entryTime using the server-provided crossfadeDuration.
 * 4. POSTs to /api/music/playback/transition-started so the server advances state.
 * 5. Since beats are aligned, the switch is imperceptible.
 */
(function(window) {
    'use strict';

    /**
     * Fade used only when the server planned a transition but sent no usable
     * duration. Short on purpose: an arbitrary long fade over a beat-aligned
     * plan leaves the outgoing deck audible past its exit point.
     */
    var DJ_FALLBACK_FADE_SECONDS = 2;

    window.DjTransitionManager = {
        // Transition state
        isTransitioning: false,
        transitionPrepared: false,
        transitionData: null,
        monitorTimer: null,
        _pollTimer: null,
        _timeupdateHandler: null,
        _timeupdateTarget: null,
        _completeIndicatorTimer: null,
        
        /**
         * Initialize the DJ transition manager
         */
        init: function() {
            window.Helpers.log('DjTransitionManager initialized (EternalJukebox style)');
            
            // Listen for state changes
            window.addEventListener('musicStateChanged', (e) => {
                this.handleStateChange(e.detail);
            });
            
            // Check if DJ mode is already active on page load (restored state)
            let checkInitialStateAttempts = 0;
            const checkInitialState = () => {
                if (window.StateManager) {
                    const state = window.StateManager.getState();
                    if (state && state.djModeActive) {
                        window.Helpers.log('[DJ] DJ mode already active on init, showing indicator');
                        this.updateDjIndicator('active');
                        // A plan may already exist after a page reload. The
                        // normal change listener never sees it because it did
                        // not transition to true in this page session.
                        if (state.djTransitionPlanned && state.djNextSongId && !this.transitionPrepared) {
                            window.Helpers.log('[DJ] Restored plan found on init, preparing');
                            this.prepareTransition(state);
                        }
                    }
                } else {
                    checkInitialStateAttempts++;
                    if (checkInitialStateAttempts >= 100) {
                        console.warn('[DjTransitionManager] checkInitialState gave up waiting for StateManager');
                        return;
                    }
                    setTimeout(checkInitialState, 50);
                }
            };
            setTimeout(checkInitialState, 100);

            // Self-heal: if a plan arrives while the page is busy, or a state
            // update is missed, pick it up instead of playing to the natural end.
            if (this._healTimer) clearInterval(this._healTimer);
            this._healTimer = setInterval(() => {
                if (window.videoPlaying || this.isTransitioning || this.transitionPrepared) return;
                if (!window.StateManager) return;
                const state = window.StateManager.getState();
                if (state && state.djModeActive && state.djTransitionPlanned && state.djNextSongId) {
                    window.Helpers.log('[DJ] Self-heal found an unmonitored plan, preparing');
                    this.prepareTransition(state);
                }
            }, 2000);
        },
        
        /**
          * Handle state changes from StateManager
          */
        handleStateChange: function(detail) {
            const changes = detail.changes || {};
            const oldState = detail.oldState || {};
            const newState = detail.newState || {};

            // Gate DJ state changes while video is active — music and video contend.
            // The AudioEngine already blocks setSource while video is active; this
            // gate ensures the DJ indicator/monitor path honors the same memo and
            // prevents the false->true->false flap observed during video playback.
            if (window.videoPlaying) {
                if (oldState.djModeActive !== newState.djModeActive) {
                    window.Helpers?.log?.('[DJ] Blocked DJ Mode change while video is active:', oldState.djModeActive, '->', newState.djModeActive);
                    window.Helpers.log('[DJ] Blocked DJ Mode change while video is active: ' + oldState.djModeActive + ' -> ' + newState.djModeActive);
                }
                const djFlapped = oldState.djModeActive !== newState.djModeActive;
                const djTransitionFlapped = (oldState.djTransitionPlanned !== newState.djTransitionPlanned) ||
                    (newState.djTransitionPlanned === true && oldState.djNextSongId !== newState.djNextSongId);
                if (djFlapped || djTransitionFlapped || this.transitionPrepared || this.isTransitioning) {
                    window.Helpers?.log?.('[DJ] Video active — forcing DJ indicator to none and cancelling transition');
                    this.cancelTransition();
                    this.updateDjIndicator('none');
                } else if (newState.djModeActive === true) {
                    // Even without a flap, never show active while video is active
                    window.Helpers?.log?.('[DJ] Video active — suppressing DJ active indicator');
                    this.updateDjIndicator('none');
                }
                // Skip all DJ transition preparation while video is active
                return;
            }
            
            // Check if djTransitionPlanned CHANGED to true, or if the target song changed
            // (user may have skipped, causing re-plan to a different next song)
            const djPlannedChanged = (oldState.djTransitionPlanned !== newState.djTransitionPlanned) ||
                                     (newState.djTransitionPlanned === true && oldState.djNextSongId !== newState.djNextSongId);
            
            // Only process transition if DJ Mode is actually active
            if (djPlannedChanged && newState.djTransitionPlanned === true && newState.djModeActive === true) {
                window.Helpers.log('[DJ] Transition planned, preparing');
                window.Helpers?.log?.('[DJ] >>> Transition planned! Preparing...');
                this.prepareTransition(newState);
            } else if (djPlannedChanged && newState.djTransitionPlanned === true && newState.djModeActive !== true) {
                window.Helpers?.log?.('[DJ] Transition planned but DJ Mode not active, ignoring');
            }
            
            if (djPlannedChanged && newState.djTransitionPlanned === false && this.transitionPrepared) {
                window.Helpers.log('[DJ] Transition cancelled');
                this.cancelTransition();
            }
            
            // DJ Mode changed - debug log
            if (oldState.djModeActive !== newState.djModeActive) {
                window.Helpers?.log?.('[DJ] DJ Mode change:', oldState.djModeActive, '->', newState.djModeActive);
            }
            
            // DJ Mode turned OFF - hide indicator
            if (oldState.djModeActive !== newState.djModeActive && newState.djModeActive === false) {
                window.Helpers?.log?.('[DJ] DJ Mode turned OFF - hiding indicator');
                this.cancelTransition();
                this.updateDjIndicator('none');
            }
            
            // DJ Mode turned ON (including from restored state) - show indicator
            if (oldState.djModeActive !== newState.djModeActive && newState.djModeActive === true) {
                window.Helpers?.log?.('[DJ] DJ Mode turned ON - showing indicator');
                this.updateDjIndicator('active');
            }
        },
        
        /**
         * Prepare for transition - mixing-only executor: store transition data
         * (next song id, entry/exit times, crossfade duration provided by the
         * server) and start monitoring. Does not select or reorder songs.
         */
        prepareTransition: function(state, retryCount) {
            retryCount = retryCount || 0;
            // Don't prepare transitions while video is active
            if (window.videoPlaying) {
                window.Helpers?.log?.('[DJ] Video active, skipping transition preparation');
                return;
            }
            if (!state || !state.djNextSongId) {
                window.Helpers.log('[DJ] No next song ID in transition data');
                return;
            }
            
            // If already prepared for a DIFFERENT song, cancel the old preparation
            // (e.g., user skipped and the target changed)
            if (this.transitionPrepared && this.transitionData && 
                this.transitionData.nextSongId !== state.djNextSongId) {
                window.Helpers.log('[DJ] Target song changed, cancelling old preparation');
                this.cancelTransition();
            }
            
            // Don't re-prepare if already prepared for this song
            if (this.transitionPrepared && this.transitionData && 
                this.transitionData.nextSongId === state.djNextSongId) {
                return;
            }
            
            // Get profile ID - must have a valid one, no defaults
            let profileId = null;
            if (window.StateManager && window.StateManager.getProperty('profileId')) {
                profileId = window.StateManager.getProperty('profileId');
            } else if (window.globalActiveProfileId) {
                profileId = window.globalActiveProfileId;
            } else {
                profileId = localStorage.getItem('activeProfileId');
            }
            
            // Don't proceed if profileId is not valid
            if (!profileId || profileId === 'undefined') {
                if (retryCount >= 25) {
                    console.warn('[DjTransitionManager] prepareTransition gave up waiting for profileId for plan ' + (state && state.djNextSongId));
                    return;
                }
                window.Helpers?.log?.('[DJ] Waiting for valid profileId before preparing transition...');
                const self = this;
                setTimeout(function() {
                    self.prepareTransition(state, retryCount + 1);
                }, 200);
                return;
            }
            
            const nextSongId = state.djNextSongId;
            // The server picks a beat-aligned entry point, which can land deep
            // into the next track (101.5s observed for a long intro). Keep the
            // mix point inside the window rather than discarding it: clamping to
            // 0 threw away the planner's downbeat and turned a mix into a
            // restart from the top of the track.
            const rawEntryTime = state.djEntryTime || 0;
            // Flat 45 s guard. state.duration is the OUTGOING song's length, so
            // a duration-relative cap clamped the next song by the wrong track.
            // The server already limits entry to the first 40 % of the next song.
            const maxEntryTime = 45;
const safeEntry = (typeof rawEntryTime === 'number' && isFinite(rawEntryTime)) ? rawEntryTime : 0;
const entryTime = Math.max(0, Math.min(safeEntry, maxEntryTime));
if (entryTime !== rawEntryTime) {
    window.Helpers.log('[DJ] Clamped entry point from ' + rawEntryTime + 's to ' + entryTime + 's (window max ' + maxEntryTime + 's)');
}
            const exitTime = state.djExitTime || 0;
            
            // Don't prepare transition if we don't have a valid next song ID
            if (!nextSongId || nextSongId === 'null') {
                window.Helpers?.log?.('[DJ] No valid next song ID in transition data, skipping');
                return;
            }
            
            this.transitionData = {
                nextSongId: nextSongId,
                entryTime: entryTime,
                exitTime: exitTime,
                profileId: profileId,
                streamUrl: '/api/music/stream/' + profileId + '/' + nextSongId,
                reason: state.djTransitionReason || '',
                // The planner's chosen fade wins. state.crossfadeDuration is the
                // user's global setting and DJ mode forces it to 8 s, which used
                // to stretch a 1-2 s beat-aligned plan into an 8 s overlap.
                // Never invent 8 s here; 2 s is the documented fallback.
                crossfadeDuration: (typeof state.djTransitionCrossfade === 'number' && state.djTransitionCrossfade > 0)
                    ? state.djTransitionCrossfade
                    : DJ_FALLBACK_FADE_SECONDS,
                // Tempo nudge for the incoming deck, 1.0 when the planner did not
                // choose one.
                rate: (typeof state.djTransitionRate === 'number' && state.djTransitionRate > 0)
                    ? state.djTransitionRate : 1.0
            };
            
            // PRELOAD: Tell AudioEngine to preload the next song in the background player
            if (window.AudioEngine && window.AudioEngine.preload) {
                window.AudioEngine.preload(nextSongId, entryTime);
            }
            
            this.transitionPrepared = true;
            this._preparingShown = false;
            
            window.Helpers.log('[DJ] Transition ready: exit at ' + exitTime + 's â†’ entry at ' + entryTime + 's');
            window.Helpers?.log?.('[DJ] Transition ready: exit=' + exitTime + 's, entry=' + entryTime + 's, nextSong=' + nextSongId);
            
            // Do not announce "Syncing" yet: the plan is created as soon as the
            // next song is known, which can be minutes before the fade. The
            // monitor switches the indicator when the exit is close.
            this.updateDjIndicator('active');
            this.startMonitoring();
        },
        
        /**
         * Monitor primary audio element for exit time
         */
        startMonitoring: function() {
            if (this.monitorTimer) {
                cancelAnimationFrame(this.monitorTimer);
                this.monitorTimer = null;
            }
            if (this._pollTimer) {
                clearInterval(this._pollTimer);
                this._pollTimer = null;
            }
            if (this._timeupdateHandler) {
                var audioEl = this._timeupdateTarget || (window.AudioEngine ? window.AudioEngine.getActivePlayer() : null);
                if (audioEl) {
                    audioEl.removeEventListener('timeupdate', this._timeupdateHandler);
                }
                this._timeupdateHandler = null;
                this._timeupdateTarget = null;
            }
            if (this._visibilityHandler) {
                document.removeEventListener('visibilitychange', this._visibilityHandler);
                this._visibilityHandler = null;
            }
            
            var self = this;
            
            // Shared exit-time check: fires executeTransition() once the primary
            // player passes exitTime and the preload gate is satisfied.
            var checkExitTime = function() {
                if (!self.transitionPrepared || self.isTransitioning) return;
                
                // Use robust getCurrentTime from AudioEngine
                var currentTime = window.AudioEngine ? window.AudioEngine.getCurrentTime() : 0;

                var exitTime = self.transitionData.exitTime;

                // Announce "Syncing" only when the mix is imminent (15s for an
                // 8s fade), not from the moment the next song was planned.
                if (!self._preparingShown && exitTime > 0) {
                    var remaining = exitTime - currentTime;
                    if (remaining > 0 && remaining <= 15) {
                        self._preparingShown = true;
                        self.updateDjIndicator('preparing');
                    }
                }

                // REMOVED: Safety buffer that prevented transitions in the first 30s.
                // This allows transitions to trigger even if the user seeks to the beginning.

                if (exitTime > 0 && currentTime >= exitTime) {
                    // Check if preloaded audio is ready before executing the transition.
                    // This prevents executing a crossfade when buffering hasn't finished.
                    if (window.AudioEngine && !window.AudioEngine._nextPreloadReady) {
                        if (window.AudioEngine._nextPreloadFailed) {
                            // Preload timed out — proceed anyway with a warning
                            window.Helpers.log('[DJ] âš ï¸ Preload timed out, executing transition anyway');
                        } else {
                            // Still loading — wait for the next tick
                            window.Helpers.log('[DJ] Waiting for preload to complete...');
                            return;
                        }
                    }

                    window.Helpers?.log?.('[DJ] >>> EXIT TIME REACHED! Switching to next song at ' + self.transitionData.entryTime + 's');
                    window.Helpers.log('[DJ] Exit time reached, switching to next song');
                    self.executeTransition();
                }
            };
            
            // rAF ticks in sync with the display (no 100ms timer throttling, which on
            // mobile backgrounded tabs can fire at 1Hz+ and miss the exit beat by
            // seconds). rAF pauses automatically when the tab is hidden; the
            // interval + timeupdate listeners below keep monitoring alive while
            // backgrounded, and the visibilitychange handler re-checks on return.
            var tick = function() {
                if (!self.transitionPrepared || self.isTransitioning) return;
                checkExitTime();
                self.monitorTimer = requestAnimationFrame(tick);
            };
            
            // 500ms interval poll — rAF is suspended in backgrounded tabs, so this
            // keeps the exit-time check running even when the tab is hidden.
            this._pollTimer = setInterval(function() {
                if (!self.transitionPrepared || self.isTransitioning) return;
                checkExitTime();
            }, 500);
            
            // timeupdate listener — fires on the active audio element regardless of
            // tab visibility, giving a second, event-driven exit-time check.
            var timeupdateHandler = function() {
                if (!self.transitionPrepared || self.isTransitioning) return;
                checkExitTime();
            };
            this._timeupdateHandler = timeupdateHandler;
            var audioEl = window.AudioEngine ? window.AudioEngine.getActivePlayer() : null;
            this._timeupdateTarget = audioEl;
            if (audioEl) {
                audioEl.addEventListener('timeupdate', timeupdateHandler);
            }
            
            // Catch-up check when the tab becomes visible again — rAF is paused while
            // hidden, so without this the transition would fire late (if at all).
            var visibilityHandler = function() {
                if (!self.transitionPrepared || self.isTransitioning) return;
                if (document.visibilityState !== 'visible') return;
                
                var currentTime = window.AudioEngine ? window.AudioEngine.getCurrentTime() : 0;
                var exitTime = self.transitionData.exitTime;
                if (exitTime > 0 && currentTime >= exitTime) {
                    window.Helpers?.log?.('[DJ] >>> EXIT TIME REACHED (on tab return)! Switching to next song');
                    self.executeTransition();
                }
            };
            this._visibilityHandler = visibilityHandler;
            document.addEventListener('visibilitychange', visibilityHandler);
            
            this.monitorTimer = requestAnimationFrame(tick);
        },
        
        /**
         * Execute the seamless beat-aligned transition
         */
        executeTransition: function() {
            if (this.isTransitioning) return;
            if (!this.transitionData) return;

            // A manual DJ skip (or another fade) may still be running. Never
            // notify the server unless this transition's crossfade actually
            // starts; otherwise the server advances while the old audio is
            // still the active element, and every new plan fires immediately.
            var crossfading = window.SynchronizationManager
                && window.SynchronizationManager.getFlag('isCrossfading');
            if (crossfading) {
                this._executeRetry = (this._executeRetry || 0) + 1;
                if (this._executeRetry <= 20) {
                    var self = this;
                    setTimeout(function () { self.executeTransition(); }, 300);
                } else {
                    window.Helpers.log('[DJ] Crossfade still busy; cancelling deferred transition');
                    this.cancelTransition();
                }
                return;
            }
            this._executeRetry = 0;
            this.isTransitioning = true;
            
            // Set crossfade flag to prevent progress bar jumps
            if (window.SynchronizationManager) {
                window.SynchronizationManager.setFlag('isCrossfading', true);
            }
            
            // NOTE: Song selection/state advancement is owned by the server. This
            // executor does not fetch next-song metadata or update currentSongId.
            // The already-known djNextSongId drives preload/fade below.

            // iOS goes through crossfadeTo too: it probes whether two elements
            // can play at once and falls back to a single-deck fade by itself.
            // A separate direct src swap here gave iOS a hard cut and a gap.
            if (window.AudioEngine && window.AudioEngine.crossfadeTo) {
                var nextSongId = this.transitionData.nextSongId;
                var entryTime = this.transitionData.entryTime;
                var crossfadeDuration = this.transitionData.crossfadeDuration > 0
                    ? this.transitionData.crossfadeDuration
                    : DJ_FALLBACK_FADE_SECONDS;
                
                this.updateDjIndicator('transitioning');
                
                // Execute the actual dual-player crossfade and only advance the
                // server state when the audio engine accepted it.
                var started = window.AudioEngine.crossfadeTo(nextSongId, entryTime, crossfadeDuration, true,
                    this.transitionData.rate || 1.0);
                if (started === false) {
                    this.isTransitioning = false;
                    if (window.SynchronizationManager) {
                        window.SynchronizationManager.setFlag('isCrossfading', false);
                    }
                    this._executeRetry = (this._executeRetry || 0) + 1;
                    if (this._executeRetry <= 20) {
                        var retrySelf = this;
                        setTimeout(function () { retrySelf.executeTransition(); }, 300);
                    } else {
                        window.Helpers.log('[DJ] Crossfade could not start; aborting transition');
                        this.cancelTransition();
                    }
                    return;
                }
                
                // Notify server that transition has started
                if (this.transitionData.profileId) {
                    this.notifyTransitionStarted(this.transitionData.profileId);
                }
                return;
            }
            
            // Fallback for single-player (legacy)
            var primaryAudio = window.AudioEngine ? window.AudioEngine.audio : window.audio;
            if (!primaryAudio) {
                window.Helpers.log('[DJ] No primary audio element found');
                this.cancelTransition();
                return;
            }
            
            var entryTime = this.transitionData.entryTime;
            var streamUrl = this.transitionData.streamUrl;
            
            window.Helpers?.log?.('[DJ] Switching audio source to: ' + streamUrl + ' at ' + entryTime + 's');
            window.Helpers.log('[DJ] Switching to next song at entry=' + entryTime + 's');
            
            this.updateDjIndicator('switching');
            
            // Save current volume
            var savedVolume = primaryAudio.volume;
            
            // Quick fade out (100ms) for smoothness
            primaryAudio.volume = 0;
            
            var self = this;
            setTimeout(function() {
                // Switch source
                primaryAudio.src = streamUrl;
                primaryAudio.load();
                
                // When ready, seek to entry time and play
                primaryAudio.onloadedmetadata = function() {
                    window.Helpers?.log?.('[DJ] Metadata loaded, seeking to ' + entryTime + 's');
                    primaryAudio.currentTime = entryTime;
                    primaryAudio.play().then(function() {
                        window.Helpers?.log?.('[DJ] Next song playing from ' + entryTime + 's');
                        window.Helpers.log('[DJ] Transition complete - now playing next song');
                        
                        // Restore volume
                        primaryAudio.volume = savedVolume;
                        
                        // Update UI indicator
                        self.updateDjIndicator('complete');
                        
                         // Update Media Session API (fallback for single-player mode)
                         if (window.updateMediaSessionMetadata && self.transitionData) {
                              const artworkUrl = self.transitionData.nextSongId 
                                  ? '/api/music/stream/artwork/' + self.transitionData.nextSongId 
                                  : '/logo.png';
                             window.updateMediaSessionMetadata(
                                 self.transitionData.nextSongTitle || 'Unknown',
                                 self.transitionData.nextSongArtist || 'Unknown Artist',
                                 artworkUrl
                             );
                        }
                        
                        // Clean up
                        self.transitionPrepared = false;
                        self.isTransitioning = false;
                        self.transitionData = null;
                        
                        self.stopMonitoring();
                    }).catch(function(e) {
                        console.error('[DJ] Failed to play next song:', e);
                        console.error('[DJ] Failed to play next song: ' + e.message);
                        // Restore volume and cancel
                        primaryAudio.volume = savedVolume;
                        self.cancelTransition();
                    });
                };
                
                primaryAudio.onerror = function(e) {
                    console.error('[DJ] Failed to load next song:', e);
                    console.error('[DJ] Failed to load next song');
                    primaryAudio.volume = savedVolume;
                    self.cancelTransition();
                };
            }, 100);
        },
        
        /**
         * Cancel an in-progress or prepared transition
         */
        cancelTransition: function() {
            if (this._completeIndicatorTimer) {
                clearTimeout(this._completeIndicatorTimer);
                this._completeIndicatorTimer = null;
            }
            this.stopMonitoring();
            
            this.isTransitioning = false;
            this.transitionPrepared = false;
            this.transitionData = null;
            
            // Do NOT clear the shared isCrossfading flag here: AudioEngine owns it
            // and clears it in finalizeCrossfade. This used to run mid-fade (the
            // server clears the plan right after transition-started), unfreezing the
            // slider and re-enabling setSource while the fade was still audible.
            
            this.updateDjIndicator('none');
        },
        
        /** called when a crossfade is aborted so isTransitioning cannot stay true forever */
        onTransitionAborted: function() {
            this.isTransitioning = false;
            this.transitionPrepared = false;
            this.transitionData = null;
            this.updateDjIndicator('none');
        },
        
        /**
         * Stop the rAF monitor loop, interval poll, timeupdate listener,
         * and visibility catch-up handler
         */
        stopMonitoring: function() {
            if (this.monitorTimer) {
                cancelAnimationFrame(this.monitorTimer);
                this.monitorTimer = null;
            }
            if (this._pollTimer) {
                clearInterval(this._pollTimer);
                this._pollTimer = null;
            }
            if (this._timeupdateHandler) {
                var audioEl = this._timeupdateTarget || (window.AudioEngine ? window.AudioEngine.getActivePlayer() : null);
                if (audioEl) {
                    audioEl.removeEventListener('timeupdate', this._timeupdateHandler);
                }
                this._timeupdateHandler = null;
                this._timeupdateTarget = null;
            }
            if (this._visibilityHandler) {
                document.removeEventListener('visibilitychange', this._visibilityHandler);
                this._visibilityHandler = null;
            }
        },
        
        /**
         * Notify server that transition has started with retry logic
         * @param {string} profileId - Profile ID
         * @param {number} attempt - Current attempt number (1-3)
         */
        notifyTransitionStarted: function(profileId, attempt) {
            attempt = attempt || 1;
            var self = this;
            fetch('/api/music/playback/transition-started/' + profileId, { 
                method: 'POST', 
                credentials: 'same-origin' 
            }).then(function(response) {
                if (!response.ok) {
                    throw new Error('HTTP ' + response.status);
                }
            }).catch(function(err) {
                if (attempt < 3) {
                    console.error('[DJ] Transition-started POST failed (attempt ' + attempt + '/3), retrying in 1s: ' + err.message);
                    setTimeout(function() {
                        self.notifyTransitionStarted(profileId, attempt + 1);
                    }, 1000);
                } else {
                    console.error('[DJ] Failed to notify server of transition start after 3 attempts', err);
                }
            });
        },

        /**
         * Suspend DJ activity when video takes over — cancels any in-flight
         * transition and stops the monitor interval. Called automatically
         * by MusicBarInit when entering the video section.
         */
        suspendForVideo: function() {
            if (this.monitorTimer || this.transitionPrepared || this.isTransitioning) {
                window.Helpers?.log?.('[DJ] Suspending for video');
                this.cancelTransition();
            }
        },

        /**
          * Update DJ Mode visual indicator in the UI
          */
        updateDjIndicator: function(state, retryCount) {
            retryCount = retryCount || 0;
            window.Helpers?.log?.('[DJ] updateDjIndicator called with state:', state);
            if (window.videoPlaying && state !== 'none') {
                window.Helpers?.log?.('[DJ] Blocked updateDjIndicator(' + state + ') — video is active, forcing none');
                window.Helpers.log('[DJ] Blocked updateDjIndicator(' + state + ') — video is active');
                state = 'none';
            }
            
            // Try to find the indicator, with caching
            if (!this._indicatorEl || !document.body.contains(this._indicatorEl)) {
                this._indicatorEl = document.getElementById('djModeIndicator') || 
                                   document.querySelector('.dj-indicator');
            }
            
            const indicator = this._indicatorEl;
            
            if (!indicator) {
                if (state !== 'none') {
                    if (retryCount >= 25) {
                        console.warn('[DjTransitionManager] updateDjIndicator gave up waiting for indicator element (state ' + state + ')');
                        return;
                    }
                    window.Helpers?.log?.('[DJ] Indicator not found, scheduling delayed update');
                    setTimeout(() => this.updateDjIndicator(state, retryCount + 1), 200);
                }
                return;
            }

            const panelOpen = !!(window.DjSettingsPanel && window.DjSettingsPanel.isOpen());

            // The Smart Shuffle menu lives inside the indicator. When it is open,
            // never hide the indicator or the menu disappears with it.
            if (state === 'none' && !panelOpen) {
                indicator.style.display = 'none';
                indicator.classList.add('is-hidden');
                return;
            }

            indicator.classList.remove('is-hidden');
            indicator.style.display = 'flex';

            // Keep the panel intact: the old code replaced indicator.innerHTML,
            // which deleted #djSettingsPanel and dropped .expanded every time
            // the DJ state changed. Only update the status icon and text.
            let iconEl = Array.from(indicator.children).find((el) => el.tagName === 'I');
            let textEl = indicator.querySelector('#djModeIndicatorText');
            if (!textEl || !iconEl) {
                const panel = indicator.querySelector('#djSettingsPanel');
                indicator.innerHTML = '<i class="pi pi-compact-disc" style="font-size:0.75rem"></i> <span id="djModeIndicatorText" class="ml-2"></span>';
                if (panel) indicator.appendChild(panel);
                iconEl = Array.from(indicator.children).find((el) => el.tagName === 'I');
                textEl = indicator.querySelector('#djModeIndicatorText');
            }

            indicator.classList.remove('active', 'preparing', 'crossfading', 'complete');

            let stateClass = 'active';
            let iconClass = 'pi pi-sliders-h';
            let label = 'Smart Shuffle';

            switch (state) {
                case 'preparing':
                    stateClass = 'preparing';
                    iconClass = 'pi pi-spin pi-spinner';
                    label = 'Smart Shuffle Syncing...';
                    break;
                case 'switching':
                case 'transitioning':
                    stateClass = 'crossfading';
                    iconClass = 'pi pi-compact-disc pi-spin';
                    label = 'Smart Shuffle Mixing...';
                    break;
                case 'complete':
                    stateClass = 'complete';
                    iconClass = 'pi pi-check-circle';
                    label = 'Seamlessly Mixed!';
                    if (this._completeIndicatorTimer) clearTimeout(this._completeIndicatorTimer);
                this._completeIndicatorTimer = setTimeout(() => {
                    this._completeIndicatorTimer = null;
                    const st = window.StateManager ? window.StateManager.getState() : null;
                    if (!st || st.djModeActive !== true) return;
                    this.updateDjIndicator('active');
                }, 3000);
                    break;
                case 'active':
                default:
                    break;
            }

            indicator.classList.add(stateClass);
            if (iconEl) iconEl.className = iconClass;
            if (textEl) textEl.textContent = label;
        }
    };
})(window);
