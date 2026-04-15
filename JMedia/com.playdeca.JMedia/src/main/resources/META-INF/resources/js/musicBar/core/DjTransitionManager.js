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
            
            // Check if djTransitionPlanned CHANGED to true
            const djPlannedChanged = (oldState.djTransitionPlanned !== newState.djTransitionPlanned);
            
            if (djPlannedChanged && newState.djTransitionPlanned === true) {
                window.Helpers.log('[DJ] Transition planned, preparing');
                console.log('[DJ] >>> Transition planned! Preparing...');
                this.prepareTransition(newState);
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
            if (!state || !state.djNextSongId) {
                window.Helpers.log('[DJ] No next song ID in transition data');
                return;
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
                        
                        // Clean up
                        self.transitionPrepared = false;
                        self.isTransitioning = false;
                        self.transitionData = null;
                        
                        if (self.monitorTimer) {
                            clearInterval(self.monitorTimer);
                            self.monitorTimer = null;
                        }
                        
                        // Reset indicator after 2 seconds
                        setTimeout(function() {
                            self.updateDjIndicator('none');
                        }, 2000);
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
         * Update DJ Mode visual indicator in the UI
         */
        updateDjIndicator: function(state) {
            console.log('[DJ] updateDjIndicator called with state:', state);
            
            var indicator = null;
            var musicContent = document.getElementById('musicContentArea');
            
            // Debug: log what we find
            console.log('[DJ] musicContent exists:', !!musicContent);
            
            // PRIORITY 1: Player container indicator (in persistent player - this is the main one)
            indicator = document.getElementById('djModeIndicator');
            console.log('[DJ] Found player indicator:', !!indicator);
            
            // PRIORITY 2: If not found, try inside musicContentArea (music view indicator)
            if (!indicator && musicContent) {
                indicator = musicContent.querySelector('#djModeIndicatorMusicView');
                console.log('[DJ] Found music view indicator:', !!indicator);
            }
            
            // Last resort: any dj-indicator
            if (!indicator) {
                indicator = document.querySelector('.dj-indicator');
                console.log('[DJ] Found any dj-indicator:', !!indicator);
            }
            
            // If we found indicator in musicContentArea but not in player, hide the music view one
            if (musicContent) {
                var musicViewIndicator = musicContent.querySelector('#djModeIndicatorMusicView');
                if (musicViewIndicator) {
                    console.log('[DJ] Hiding music view indicator, using player container');
                    musicViewIndicator.style.display = 'none';
                    musicViewIndicator.classList.add('is-hidden');
                }
            }
            
            // Still no indicator found - handle hiding or showing
            if (!indicator) {
                if (state === 'none') {
                    // Trying to hide but no indicator found - find ALL indicators and hide them
                    console.log('[DJ] No indicator found, hiding all dj-indicators');
                    var allIndicators = document.querySelectorAll('.dj-indicator');
                    console.log('[DJ] Found', allIndicators.length, 'indicators to hide');
                    allIndicators.forEach(function(el) {
                        console.log('[DJ] Hiding indicator:', el.id || 'no id');
                        el.style.display = 'none';
                        el.classList.add('is-hidden');
                    });
                    return;
                } else {
                    // Trying to show but indicator not ready yet - schedule delayed update
                    console.log('[DJ] Indicator not found, scheduling delayed update for state:', state);
                    var self = this;
                    setTimeout(function() {
                        self.updateDjIndicatorDelayed(state);
                    }, 100);
                    return;
                }
            }

            console.log('[DJ] Using indicator:', indicator.id, 'with state:', state);
            
            // Remove hidden class first
            indicator.classList.remove('is-hidden');
            
            switch (state) {
                case 'active':
                    indicator.style.display = 'flex';
                    indicator.className = 'dj-indicator active';
                    indicator.style.background = 'rgba(0,0,0,0.75)';
                    indicator.style.borderRadius = '4px';
                    indicator.style.padding = '4px 8px';
                    indicator.innerHTML = '<i class="pi pi-headphones" style="font-size:0.75rem"></i> <span class="ml-2">DJ Mode</span>';
                    break;
                case 'preparing':
                    indicator.style.display = 'flex';
                    indicator.className = 'dj-indicator preparing animate-pulse';
                    indicator.style.background = 'rgba(0,0,0,0.75)';
                    indicator.style.borderRadius = '4px';
                    indicator.style.padding = '4px 8px';
                    indicator.innerHTML = '<i class="pi pi-spin pi-spinner" style="font-size:0.75rem"></i> <span class="ml-2">DJ Syncing...</span>';
                    break;
                case 'switching':
                case 'transitioning':
                    indicator.style.display = 'flex';
                    indicator.className = 'dj-indicator crossfading';
                    indicator.style.background = 'rgba(0,0,0,0.75)';
                    indicator.style.borderRadius = '4px';
                    indicator.style.padding = '4px 8px';
                    var reason = (this.transitionData && this.transitionData.reason) ? ': ' + this.transitionData.reason : '...';
                    indicator.innerHTML = '<i class="pi pi-compact-disc pi-spin" style="font-size:0.75rem"></i> <span class="ml-2"><b>DJ Mixing</b>' + reason + '</span>';
                    break;
                case 'complete':
                    indicator.style.display = 'flex';
                    indicator.className = 'dj-indicator complete';
                    indicator.style.background = 'rgba(0,0,0,0.75)';
                    indicator.style.borderRadius = '4px';
                    indicator.style.padding = '4px 8px';
                    indicator.innerHTML = '<i class="pi pi-check-circle" style="font-size:0.75rem"></i> <span class="ml-2">Seamlessly Mixed!</span>';
                    setTimeout(() => {
                        this.updateDjIndicator('none');
                    }, 3000);
                    break;
                default:
                    console.log('[DJ] Hiding indicator via updateDjIndicator (state:', state, ')');
                    indicator.style.display = 'none';
                    indicator.classList.add('is-hidden');
                    indicator.className = 'dj-indicator';
                    indicator.innerHTML = '';
                    break;
            }
        },
        
        /**
         * Delayed update - try to find indicator after HTMX loads
         * Uses same logic as updateDjIndicator for consistency
         */
        updateDjIndicatorDelayed: function(state) {
            var indicator = null;
            var musicContent = document.getElementById('musicContentArea');
            
            // PRIORITY 1: Player container indicator
            indicator = document.getElementById('djModeIndicator');
            
            // PRIORITY 2: If not found, try inside musicContentArea
            if (!indicator && musicContent) {
                indicator = musicContent.querySelector('#djModeIndicatorMusicView');
            }
            
            // Last resort: any dj-indicator
            if (!indicator) {
                indicator = document.querySelector('.dj-indicator');
            }
            
            // If we found indicator in musicContentArea but not in player, hide the music view one
            if (musicContent) {
                var musicViewIndicator = musicContent.querySelector('#djModeIndicatorMusicView');
                if (musicViewIndicator) {
                    musicViewIndicator.style.display = 'none';
                    musicViewIndicator.classList.add('is-hidden');
                }
            }
            
            if (!indicator) {
                // Still not found - try again if not trying to hide
                if (state !== 'none') {
                    var self = this;
                    setTimeout(function() {
                        self.updateDjIndicatorDelayed(state);
                    }, 200);
                }
                return;
            }
            
            console.log('[DJ] Delayed update using indicator:', indicator.id, 'with state:', state);
            
            // Remove hidden class first
            indicator.classList.remove('is-hidden');
            
            switch (state) {
                case 'active':
                    indicator.style.display = 'flex';
                    indicator.className = 'dj-indicator active';
                    indicator.style.background = 'rgba(0,0,0,0.75)';
                    indicator.style.borderRadius = '4px';
                    indicator.style.padding = '4px 8px';
                    indicator.innerHTML = '<i class="pi pi-headphones" style="font-size:0.75rem"></i> <span class="ml-2">DJ Mode</span>';
                    break;
                case 'preparing':
                    indicator.style.display = 'flex';
                    indicator.className = 'dj-indicator preparing animate-pulse';
                    indicator.style.background = 'rgba(0,0,0,0.75)';
                    indicator.style.borderRadius = '4px';
                    indicator.style.padding = '4px 8px';
                    indicator.innerHTML = '<i class="pi pi-spin pi-spinner" style="font-size:0.75rem"></i> <span class="ml-2">DJ Syncing...</span>';
                    break;
                case 'switching':
                case 'transitioning':
                    indicator.style.display = 'flex';
                    indicator.className = 'dj-indicator crossfading';
                    indicator.style.background = 'rgba(0,0,0,0.75)';
                    indicator.style.borderRadius = '4px';
                    indicator.style.padding = '4px 8px';
                    indicator.innerHTML = '<i class="pi pi-compact-disc pi-spin" style="font-size:0.75rem"></i> <span class="ml-2"><b>DJ Mixing</b></span>';
                    break;
                case 'complete':
                    indicator.style.display = 'flex';
                    indicator.className = 'dj-indicator complete';
                    indicator.style.background = 'rgba(0,0,0,0.75)';
                    indicator.style.borderRadius = '4px';
                    indicator.style.padding = '4px 8px';
                    indicator.innerHTML = '<i class="pi pi-check-circle" style="font-size:0.75rem"></i> <span class="ml-2">Seamlessly Mixed!</span>';
                    setTimeout(() => {
                        this.updateDjIndicatorDelayed('none');
                    }, 3000);
                    break;
                default:
                    indicator.style.display = 'none';
                    indicator.classList.add('is-hidden');
                    indicator.className = 'dj-indicator';
                    indicator.innerHTML = '';
                    break;
            }
        }
    };
})(window);
