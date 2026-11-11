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
     * Detect iOS — prefer PlayerUtils.isIOS() when available, else UA + touch heuristics.
     */
    function isIOS() {
        if (window.PlayerUtils && typeof window.PlayerUtils.isIOS === 'function') {
            return window.PlayerUtils.isIOS();
        }
        return /iPhone|iPad|iPod|iPadOS/i.test(navigator.userAgent) ||
               (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1) ||
               (navigator.platform === 'iPhone' || navigator.platform === 'iPad');
    }

    window.DjTransitionManager = {
        // Transition state
        isTransitioning: false,
        transitionPrepared: false,
        transitionData: null,
        monitorTimer: null,
        _pollTimer: null,
        _timeupdateHandler: null,
        
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
            const checkInitialState = () => {
                if (window.StateManager) {
                    const state = window.StateManager.getState();
                    if (state && state.djModeActive) {
                        window.Helpers.log('[DJ] DJ mode already active on init, showing indicator');
                        this.updateDjIndicator('active');
                    }
                } else {
                    setTimeout(checkInitialState, 50);
                }
            };
            setTimeout(checkInitialState, 100);
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
        prepareTransition: function(state) {
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
                window.Helpers?.log?.('[DJ] Waiting for valid profileId before preparing transition...');
                const self = this;
                setTimeout(function() {
                    self.prepareTransition(state);
                }, 200);
                return;
            }
            
            const nextSongId = state.djNextSongId;
            // The server picks a beat-aligned entry point, which can land deep
            // into the next track (53s observed for a 3-4 minute song). Starting
            // that far in means the listener misses most of it, so keep the mix
            // point near the start and fall back to 0 when it is excessive.
            const rawEntryTime = state.djEntryTime || 0;
            const MAX_DJ_ENTRY_SECONDS = 30;
            const entryTime = rawEntryTime > MAX_DJ_ENTRY_SECONDS ? 0 : rawEntryTime;
            if (entryTime !== rawEntryTime) {
                window.Helpers.log('[DJ] Clamped entry point from ' + rawEntryTime + 's to ' + entryTime + 's (max ' +
                    MAX_DJ_ENTRY_SECONDS + 's)');
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
                crossfadeDuration: state.crossfadeDuration || 8
            };
            
            // PRELOAD: Tell AudioEngine to preload the next song in the background player
            if (window.AudioEngine && window.AudioEngine.preload) {
                window.AudioEngine.preload(nextSongId, entryTime);
            }
            
            this.transitionPrepared = true;
            
            window.Helpers.log('[DJ] Transition ready: exit at ' + exitTime + 's â†’ entry at ' + entryTime + 's');
            window.Helpers?.log?.('[DJ] Transition ready: exit=' + exitTime + 's, entry=' + entryTime + 's, nextSong=' + nextSongId);
            
            this.updateDjIndicator('preparing');
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
                var audioEl = window.AudioEngine ? window.AudioEngine.getActivePlayer() : null;
                if (audioEl) {
                    audioEl.removeEventListener('timeupdate', this._timeupdateHandler);
                }
                this._timeupdateHandler = null;
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
            this.isTransitioning = true;
            
            // Set crossfade flag to prevent progress bar jumps
            if (window.SynchronizationManager) {
                window.SynchronizationManager.setFlag('isCrossfading', true);
            }
            
            // NOTE: Song selection/state advancement is owned by the server. This
            // executor does not fetch next-song metadata or update currentSongId.
            // The already-known djNextSongId drives preload/fade below.

            // iOS: single-element direct swap — iOS only allows one audio stream,
            // so the dual-player crossfade would be silent. Swap src on the SAME
            // active element instead. Desktop keeps the crossfadeTo path below.
            if (isIOS()) {
                this.executeIosDirectSwap();
                return;
            }

            if (window.AudioEngine && window.AudioEngine.crossfadeTo) {
                var nextSongId = this.transitionData.nextSongId;
                var entryTime = this.transitionData.entryTime;
                var crossfadeDuration = this.transitionData.crossfadeDuration || 8;
                
                this.updateDjIndicator('transitioning');
                
                // Execute the actual dual-player crossfade
                window.AudioEngine.crossfadeTo(nextSongId, entryTime, crossfadeDuration, true);
                
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
                        window.Helpers.log('[DJ] Failed to play next song: ' + e.message);
                        // Restore volume and cancel
                        primaryAudio.volume = savedVolume;
                        self.cancelTransition();
                    });
                };
                
                primaryAudio.onerror = function(e) {
                    console.error('[DJ] Failed to load next song:', e);
                    window.Helpers.log('[DJ] Failed to load next song');
                    primaryAudio.volume = savedVolume;
                    self.cancelTransition();
                };
            }, 100);
        },
        
        /**
         * iOS direct swap — replace the active player's source with the next song.
         * iOS only allows a single audio stream, so the dual-player crossfade is
         * silent; we swap src on the SAME element instead.
         */
        executeIosDirectSwap: function() {
            var self = this;
            var primaryAudio = window.AudioEngine ? window.AudioEngine.getActivePlayer() : null;
            if (!primaryAudio) {
                console.error('[DJ] iOS direct swap failed: no active audio element');
                window.Helpers.log('[DJ] No active audio element for iOS direct swap');
                this.cancelTransition();
                return;
            }
            
            var entryTime = this.transitionData.entryTime;
            var streamUrl = this.transitionData.streamUrl;
            
            window.Helpers?.log?.('[DJ] iOS direct swap: switching active player to ' + streamUrl + ' at ' + entryTime + 's');
            window.Helpers.log('[DJ] iOS direct swap to next song at entry=' + entryTime + 's');
            
            this.updateDjIndicator('switching');
            
            // Save current volume
            var savedVolume = primaryAudio.volume;
            
            // Switch source on the SAME element (single-stream iOS)
            primaryAudio.src = streamUrl;
            primaryAudio.load();
            
            primaryAudio.onloadedmetadata = function() {
                window.Helpers?.log?.('[DJ] iOS metadata loaded, seeking to ' + entryTime + 's');
                primaryAudio.currentTime = entryTime;
                primaryAudio.play().then(function() {
                    window.Helpers?.log?.('[DJ] iOS next song playing from ' + entryTime + 's');
                    window.Helpers.log('[DJ] iOS transition complete - now playing next song');
                    
                    // Restore volume
                    primaryAudio.volume = savedVolume;
                    
                    // Notify server that transition has started
                    if (self.transitionData && self.transitionData.profileId) {
                        self.notifyTransitionStarted(self.transitionData.profileId);
                    }
                    
                    // Clean up
                    self.transitionPrepared = false;
                    self.isTransitioning = false;
                    self.transitionData = null;
                    
                    self.stopMonitoring();
                    
                    // Update UI indicator
                    self.updateDjIndicator('complete');
                }).catch(function(e) {
                    console.error('[DJ] iOS direct play failed', e);
                    window.Helpers.log('[DJ] iOS direct play failed: ' + (e && e.message ? e.message : e));
                    // Restore volume and cancel
                    primaryAudio.volume = savedVolume;
                    if (e && e.name === 'NotAllowedError') {
                        window.showToast('Tap play to resume');
                    }
                    self.cancelTransition();
                });
            };
            
            primaryAudio.onerror = function(e) {
                console.error('[DJ] iOS failed to load next song:', e);
                window.Helpers.log('[DJ] iOS failed to load next song');
                primaryAudio.volume = savedVolume;
                self.cancelTransition();
            };
        },
        
        /**
         * Cancel an in-progress or prepared transition
         */
        cancelTransition: function() {
            this.stopMonitoring();
            
            this.isTransitioning = false;
            this.transitionPrepared = false;
            this.transitionData = null;
            
            // Clear crossfade flag
            if (window.SynchronizationManager) {
                window.SynchronizationManager.setFlag('isCrossfading', false);
            }
            
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
                var audioEl = window.AudioEngine ? window.AudioEngine.getActivePlayer() : null;
                if (audioEl) {
                    audioEl.removeEventListener('timeupdate', this._timeupdateHandler);
                }
                this._timeupdateHandler = null;
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
                    window.Helpers.log('[DJ] Transition-started POST failed (attempt ' + attempt + '/3), retrying in 1s: ' + err.message);
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
        updateDjIndicator: function(state) {
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
                    window.Helpers?.log?.('[DJ] Indicator not found, scheduling delayed update');
                    setTimeout(() => this.updateDjIndicator(state), 200);
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
                    label = 'DJ Syncing...';
                    break;
                case 'switching':
                case 'transitioning':
                    stateClass = 'crossfading';
                    iconClass = 'pi pi-compact-disc pi-spin';
                    label = 'DJ Mixing' + ((this.transitionData && this.transitionData.reason)
                        ? ': ' + this.transitionData.reason : '...');
                    break;
                case 'complete':
                    stateClass = 'complete';
                    iconClass = 'pi pi-check-circle';
                    label = 'Seamlessly Mixed!';
                    setTimeout(() => this.updateDjIndicator('active'), 3000);
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
