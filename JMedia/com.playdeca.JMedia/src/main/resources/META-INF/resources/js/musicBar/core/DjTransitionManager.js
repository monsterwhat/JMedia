/**
 * DjTransitionManager - Handles beat-aligned transitions between songs (EternalJukebox style)
 * 
 * When DJ Mode triggers:
 * 1. Server calculates optimal exit/entry beat pair (matching beat-in-bar position)
 * 2. Server sends djTransitionPlanned=true via WebSocket
 * 3. This module monitors the primary audio element's currentTime
 * 4. At exitTime, seamlessly switches audio source to next song at entryTime
 * 5. Since beats are aligned, the switch is imperceptible
 */
(function(window) {
    'use strict';
    
    window.DjTransitionManager = {
        // Transition state
        isTransitioning: false,
        transitionPrepared: false,
        transitionData: null,
        monitorTimer: null,
        
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
            // Wait for StateManager to be ready
            const checkInitialState = () => {
                if (window.StateManager) {
                    const state = window.StateManager.getState();
                    if (state && state.djModeActive) {
                        window.Helpers.log('[DJ] DJ mode already active on init, showing indicator');
                        this.updateDjIndicator('active');
                        
                        // IMPORTANT: Tell backend DJ Mode is active - restored state doesn't trigger backend
                        // This ensures the server calculates transitions for this profile
                        if (window.PlaybackController) {
                            // Wait for profileId to be available
                            const notifyBackend = () => {
                                let profileId = window.StateManager.getProperty('profileId') || 
                                              window.globalActiveProfileId || 
                                              localStorage.getItem('activeProfileId');
                                if (profileId && profileId !== 'undefined' && profileId !== 'null') {
                                    const isActive = state.djModeActive === true;
                                    console.log('[DJ] Restored DJ Mode - setting to', isActive, 'for profile', profileId);
                                    // Use set endpoint to explicitly set On/Off (not toggle)
                                    fetch(`/api/music/playback/dj-mode-set/${profileId}/${isActive}`, {
                                        method: 'POST',
                                        credentials: 'same-origin'
                                    }).then(() => {
                                        console.log('[DJ] Backend DJ mode set to', isActive);
                                    }).catch(err => {
                                        console.error('[DJ] Failed to set DJ mode:', err);
                                    });
                                } else {
                                    setTimeout(notifyBackend, 100);
                                }
                            };
                            setTimeout(notifyBackend, 200);
                        }
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
            
            // Check if djTransitionPlanned CHANGED to true, or if the target song changed
            // (user may have skipped, causing re-plan to a different next song)
            const djPlannedChanged = (oldState.djTransitionPlanned !== newState.djTransitionPlanned) ||
                                     (newState.djTransitionPlanned === true && oldState.djNextSongId !== newState.djNextSongId);
            
            // Only process transition if DJ Mode is actually active
            if (djPlannedChanged && newState.djTransitionPlanned === true && newState.djModeActive === true) {
                window.Helpers.log('[DJ] Transition planned, preparing');
                console.log('[DJ] >>> Transition planned! Preparing...');
                this.prepareTransition(newState);
            } else if (djPlannedChanged && newState.djTransitionPlanned === true && newState.djModeActive !== true) {
                console.log('[DJ] Transition planned but DJ Mode not active, ignoring');
            }
            
            if (djPlannedChanged && newState.djTransitionPlanned === false && this.transitionPrepared) {
                window.Helpers.log('[DJ] Transition cancelled');
                this.cancelTransition();
            }
            
            // DJ Mode changed - debug log
            if (oldState.djModeActive !== newState.djModeActive) {
                console.log('[DJ] DJ Mode change:', oldState.djModeActive, '->', newState.djModeActive);
            }
            
            // DJ Mode turned OFF - hide indicator
            if (oldState.djModeActive !== newState.djModeActive && newState.djModeActive === false) {
                console.log('[DJ] DJ Mode turned OFF - hiding indicator');
                this.cancelTransition();
                this.updateDjIndicator('none');
            }
            
            // DJ Mode turned ON (including from restored state) - show indicator
            if (oldState.djModeActive !== newState.djModeActive && newState.djModeActive === true) {
                console.log('[DJ] DJ Mode turned ON - showing indicator');
                this.updateDjIndicator('active');
            }
        },
        
        /**
         * Prepare for transition - store transition data and start monitoring
         */
        prepareTransition: function(state) {
            // Don't prepare transitions while video is active
            if (window.videoPlaying) {
                console.log('[DJ] Video active, skipping transition preparation');
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
                console.log('[DJ] Waiting for valid profileId before preparing transition...');
                const self = this;
                setTimeout(function() {
                    self.prepareTransition(state);
                }, 200);
                return;
            }
            
            const nextSongId = state.djNextSongId;
            const entryTime = state.djEntryTime || 0;
            const exitTime = state.djExitTime || 0;
            
            // Don't prepare transition if we don't have a valid next song ID
            if (!nextSongId || nextSongId === 'null') {
                console.log('[DJ] No valid next song ID in transition data, skipping');
                return;
            }
            
            this.transitionData = {
                nextSongId: nextSongId,
                entryTime: entryTime,
                exitTime: exitTime,
                profileId: profileId,
                streamUrl: '/api/music/stream/' + profileId + '/' + nextSongId,
                reason: state.djTransitionReason || '',
                crossfadeDuration: state.djCrossfadeDuration || 8
            };
            
            // PRELOAD: Tell AudioEngine to preload the next song in the background player
            if (window.AudioEngine && window.AudioEngine.preload) {
                window.AudioEngine.preload(nextSongId, entryTime);
            }
            
            this.transitionPrepared = true;
            
            window.Helpers.log('[DJ] Transition ready: exit at ' + exitTime + 's → entry at ' + entryTime + 's');
            console.log('[DJ] Transition ready: exit=' + exitTime + 's, entry=' + entryTime + 's, nextSong=' + nextSongId);
            
            this.updateDjIndicator('preparing');
            this.startMonitoring();
        },
        
        /**
         * Monitor primary audio element for exit time
         */
        startMonitoring: function() {
            if (this.monitorTimer) {
                clearInterval(this.monitorTimer);
            }
            
            var self = this;
            this.monitorTimer = setInterval(function() {
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
                            window.Helpers.log('[DJ] ⚠️ Preload timed out, executing transition anyway');
                        } else {
                            // Still loading — wait for the next poll cycle (100ms)
                            window.Helpers.log('[DJ] Waiting for preload to complete...');
                            return;
                        }
                    }

                    console.log('[DJ] >>> EXIT TIME REACHED! Switching to next song at ' + self.transitionData.entryTime + 's');
                    window.Helpers.log('[DJ] Exit time reached, switching to next song');
                    self.executeTransition();
                }
            }, 100); // Check every 100ms for precise timing
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
            
            // IMMEDIATE UI UPDATE: Update song details in UI immediately so user knows what's coming
            if (window.StateManager && this.transitionData.nextSongId) {
                const nextId = this.transitionData.nextSongId;
                const profileId = this.transitionData.profileId;
                
                // 1. Fetch the next song metadata to update Title/Artist/Cover
                if (profileId) {
                    fetch(`/api/music/playback/nextSong/${profileId}`, { credentials: 'same-origin' })
                        .then(response => response.json())
                        .then(res => {
                            // res.data is the Song object in this API
                            if (res.success && res.data && String(res.data.id) === String(nextId)) {
                                 // Store title/artist and artworkBase64 in transitionData for fallback use
                                 self.transitionData.nextSongTitle = res.data.title;
                                 self.transitionData.nextSongArtist = res.data.artist;
                                 self.transitionData.nextSongArtworkBase64 = res.data.artworkBase64;
                                 
                                 // Update state FIRST
                                 window.StateManager.updateState({
                                     currentSongId: res.data.id,
                                     songName: res.data.title,
                                     artist: res.data.artist,
                                     currentSongData: { ...res.data }
                                 }, 'djTransitionManager');
                                
                                 // 2. Use ImageManager to update cover image, favicon, and page title
                                 if (window.ImageManager) {
                                     window.ImageManager.updateImages(res.data, null, null);
                                 }
                                 
                                 // 3. Update Media Session API (for browser media controls, notifications, etc.)
                                 if (window.updateMediaSessionMetadata) {
                                      const artworkUrl = res.data.artworkBase64 
                                          ? 'data:image/jpeg;base64,' + res.data.artworkBase64 
                                          : '/logo.png';
                                     window.updateMediaSessionMetadata(res.data.title, res.data.artist, artworkUrl);
                                 }
                            }
                        }).catch(err => console.error('[DJ] Failed to pre-update song metadata', err));
                }
            }

            if (window.AudioEngine && window.AudioEngine.crossfadeTo) {
                var nextSongId = this.transitionData.nextSongId;
                var entryTime = this.transitionData.entryTime;
                var crossfadeDuration = this.transitionData.crossfadeDuration || 8;
                
                this.updateDjIndicator('transitioning');
                
                // Execute the actual dual-player crossfade
                window.AudioEngine.crossfadeTo(nextSongId, entryTime, crossfadeDuration);
                
                // Notify server that transition has started
                if (this.transitionData.profileId) {
                    fetch(`/api/music/playback/transition-started/${this.transitionData.profileId}`, { 
                        method: 'POST', 
                        credentials: 'same-origin' 
                    });
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
            
            console.log('[DJ] Switching audio source to: ' + streamUrl + ' at ' + entryTime + 's');
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
                    console.log('[DJ] Metadata loaded, seeking to ' + entryTime + 's');
                    primaryAudio.currentTime = entryTime;
                    primaryAudio.play().then(function() {
                        console.log('[DJ] Next song playing from ' + entryTime + 's');
                        window.Helpers.log('[DJ] Transition complete - now playing next song');
                        
                        // Restore volume
                        primaryAudio.volume = savedVolume;
                        
                        // Update UI indicator
                        self.updateDjIndicator('complete');
                        
                         // Update Media Session API (fallback for single-player mode)
                         if (window.updateMediaSessionMetadata && self.transitionData) {
                              const artworkUrl = self.transitionData.nextSongArtworkBase64 
                                  ? 'data:image/jpeg;base64,' + self.transitionData.nextSongArtworkBase64 
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
                        
                        if (self.monitorTimer) {
                            clearInterval(self.monitorTimer);
                            self.monitorTimer = null;
                        }
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
         * Cancel an in-progress or prepared transition
         */
        cancelTransition: function() {
            if (this.monitorTimer) {
                clearInterval(this.monitorTimer);
                this.monitorTimer = null;
            }
            
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
         * Suspend DJ activity when video takes over — cancels any in-flight
         * transition and stops the monitor interval. Called automatically
         * by MusicBarInit when entering the video section.
         */
        suspendForVideo: function() {
            if (this.monitorTimer || this.transitionPrepared || this.isTransitioning) {
                console.log('[DJ] Suspending for video');
                this.cancelTransition();
            }
        },

        /**
         * Update DJ Mode visual indicator in the UI
         */
        updateDjIndicator: function(state) {
            console.log('[DJ] updateDjIndicator called with state:', state);
            
            // Try to find the indicator, with caching
            if (!this._indicatorEl || !document.body.contains(this._indicatorEl)) {
                this._indicatorEl = document.getElementById('djModeIndicator') || 
                                   document.querySelector('.dj-indicator');
            }
            
            const indicator = this._indicatorEl;
            
            if (!indicator) {
                if (state !== 'none') {
                    console.log('[DJ] Indicator not found, scheduling delayed update');
                    setTimeout(() => this.updateDjIndicator(state), 200);
                }
                return;
            }

            // Remove hidden class and ensure visible
            indicator.classList.remove('is-hidden');
            indicator.style.display = (state === 'none') ? 'none' : 'flex';
            
            if (state === 'none') {
                indicator.classList.add('is-hidden');
                return;
            }

            // Update content based on state
            switch (state) {
                case 'active':
                    indicator.className = 'dj-indicator active';
                    indicator.innerHTML = '<i class="pi pi-headphones" style="font-size:0.75rem"></i> <span class="ml-2">DJ Mode</span>';
                    break;
                case 'preparing':
                    indicator.className = 'dj-indicator preparing animate-pulse';
                    indicator.innerHTML = '<i class="pi pi-spin pi-spinner" style="font-size:0.75rem"></i> <span class="ml-2">DJ Syncing...</span>';
                    break;
                case 'switching':
                case 'transitioning':
                    indicator.className = 'dj-indicator crossfading';
                    var reason = (this.transitionData && this.transitionData.reason) ? ': ' + this.transitionData.reason : '...';
                    indicator.innerHTML = '<i class="pi pi-compact-disc pi-spin" style="font-size:0.75rem"></i> <span class="ml-2"><b>DJ Mixing</b>' + reason + '</span>';
                    break;
                case 'complete':
                    indicator.className = 'dj-indicator complete';
                    indicator.innerHTML = '<i class="pi pi-check-circle" style="font-size:0.75rem"></i> <span class="ml-2">Seamlessly Mixed!</span>';
                    setTimeout(() => this.updateDjIndicator('active'), 3000);
                    break;
            }
        }
    };
})(window);
