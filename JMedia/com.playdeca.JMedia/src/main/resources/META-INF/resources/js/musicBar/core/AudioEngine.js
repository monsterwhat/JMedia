/**
 * AudioEngine - Audio element management with atomic operations
 * Handles audio element initialization, source management, and playback control
 */
(function(window) {
    'use strict';
    
    window.AudioEngine = {
        // Audio elements
        audio: null,
        audioNext: null,
        activePlayer: 1,
        
        // Web Audio API components
        ctx: null,
        nodes: {
            p1: null, // Source node for audio
            p2: null, // Source node for audioNext
            g1: null, // Gain node for audio
            g2: null  // Gain node for audioNext
        },
        
        audioElementReady: false,
        autoplayBlocked: false,
        autoplayAttempted: false,
        
        // Preload readiness tracking for DJ mode transitions
        _nextPreloadReady: false,
        _nextPreloadFailed: false,
        _nextPreloadTimer: null,
        
        // Deferred volume during crossfade
        _pendingVolume: null,

        // True while a dual-player crossfade is running. Only one fade may run
        // at a time: a second one would flip `activePlayer` while the first
        // fade still owns both elements.
        _isCrossfading: false,
        _singlePlaybackTimer: null,

        // Local "the user asked to be silent" flag. A crossfade waits for the
        // incoming element to buffer before it starts playing; without this a
        // pause issued during that wait would be undone when canplay fires.
        _userPaused: false,

        // null = not probed yet, 'on' = two decks can play together,
        // 'off' = device only allows one deck (single-deck fade fallback).
        _dualAudio: null,

        // Crossfade bookkeeping: a monotonic token so a watchdog from an old
        // fade cannot clear a newer one, and a timestamp for bounded deferrals.
        _fadeToken: 0,
        _deferStartedAt: 0,
        
        // iOS autoplay-policy unlock state
        _iosUnlocked: false,
        _iosUnlockArmed: false,
        _iosUnlockFired: false,
        _iosSilentEl: null,

        // Set when the device must stay on the native media path (iOS). Once a
        // media element has been routed through createMediaElementSource there
        // is no way back, so the decision has to be made before the first call.
        _webAudioDisabled: false,

        // Timestamp of the last playback this page started successfully. Used
        // to ignore "paused" state echoes that describe the state from before
        // the play and would otherwise stop it again (see isRecentLocalPlay).
        _lastLocalPlayAt: 0,
        
        init: function() {
            if (this.initialized) return;
            this.initialized = true;

            // Dual-deck capability: non-iOS browsers can run both elements.
            // iOS needs a runtime probe (see crossfadeTo) and remembers a
            // previous failure so we do not break playback twice.
            this._dualAudio = this.isIOS() ? null : 'on';
            try {
                if (this.isIOS() && localStorage.getItem('jmedia_dual_audio') === 'off') {
                    this._dualAudio = 'off';
                }
            } catch (err) {
                console.warn('AudioEngine: could not read dual-audio capability', err);
            }
            
            this.initializeAudioElements();
            this.initWebAudio();
            this._unlockIOSAudio();
            this.setupEventListeners();
            window.Helpers.log('AudioEngine: Web Audio initialized (idempotent)');

            // Single-stream invariant: the engine owns two <audio> elements and
            // exactly one of them may be audible. The point fixes below cover
            // the normal paths; this catches the case where a fade is aborted or
            // a source change lands mid-fade and the outgoing element is never
            // paused — the "two songs at once" symptom.
            if (this._singlePlaybackTimer) clearInterval(this._singlePlaybackTimer);
            this._singlePlaybackTimer = setInterval(() => this._enforceSinglePlayback(), 1000);
            
            // Re-load if state exists
            setTimeout(() => {
                if (!window.StateManager || window.videoPlaying) return;
                // Never fight a restore/DJ transition that is already running.
                if (this._isCrossfading || (window.SynchronizationManager && window.SynchronizationManager.getFlag('isCrossfading'))) {
                    window.Helpers.log('AudioEngine: Skipping delayed source load — crossfade already in progress');
                    return;
                }
                const activePlayer = this.getActivePlayer();
                if (activePlayer && !activePlayer.paused) {
                    window.Helpers.log('AudioEngine: Skipping delayed source load — audio already playing');
                    return;
                }
                const state = window.StateManager.getState();
                if (state && state.currentSongId && state.playing) {
                    this.loadAudioSourceOnly({
                        id: String(state.currentSongId),
                        title: state.songName,
                        artist: state.artist,
                        duration: state.duration
                    }, state.currentTime || 0);
                }
            }, 500);
        },

        initWebAudio: function() {
            if (this.ctx || this._webAudioDisabled) return;

            // Web Audio is required for an audible crossfade on iOS: iOS ignores
            // HTMLMediaElement.volume, so an element-volume fade is a no-op and a
            // GainNode is the only working gain stage. Routing through
            // createMediaElementSource used to be avoided because older WebKit
            // could go silent after a few seconds; that is mitigated by
            // navigator.audioSession.type = 'playback' (set before playback) and
            // by the runtime dual-element probe in crossfadeTo, which falls back
            // to a single-deck Web Audio fade if two elements cannot run at once.
            if (this.isIOS() && window.JMEDIA_DISABLE_WEB_AUDIO_IOS) {
                this._webAudioDisabled = true;
                this.ctx = null;
                window.Helpers.log('AudioEngine: iOS native audio path (Web Audio explicitly disabled)');
                return;
            }

            // Web Audio is enabled on ALL devices, including mobile/iOS. Skipping it on
            // mobile previously left iOS with NO functional volume control (element.volume
            // is ignored on iOS), so crossfades ran both songs at full volume. The gain
            // nodes are required on mobile for real, volume-controlled crossfades.
            //
            // Trade-off: on Android/Chrome an AudioContext can be suspended when the tab
            // is backgrounded. The resume handlers below (click/keydown/touchstart/
            // visibilitychange) stay attached for the app's lifetime to recover it ï¿½ and
            // play() also forces a resume, so returning to the tab restores audio.
            try {
                this.ctx = new (window.AudioContext || window.webkitAudioContext)();
                
                // Ensure elements have crossOrigin set before creating source nodes
                this.audio.crossOrigin = 'anonymous';
                this.audioNext.crossOrigin = 'anonymous';
                
                // Create source nodes from audio elements
                this.nodes.p1 = this.ctx.createMediaElementSource(this.audio);
                this.nodes.p2 = this.ctx.createMediaElementSource(this.audioNext);
                
                // Per-deck low shelf (bass swap): Source -> lowShelf -> fader.
                this.nodes.low1 = this.ctx.createBiquadFilter();
                this.nodes.low1.type = 'lowshelf';
                this.nodes.low1.frequency.value = 220;
                this.nodes.low1.gain.value = 0;

                this.nodes.low2 = this.ctx.createBiquadFilter();
                this.nodes.low2.type = 'lowshelf';
                this.nodes.low2.frequency.value = 220;
                this.nodes.low2.gain.value = 0;

                // Create gain nodes
                this.nodes.g1 = this.ctx.createGain();
                this.nodes.g2 = this.ctx.createGain();

                // Master limiter. Equal-power crossfades preserve power, so two
                // correlated decks sum to about +3 dBFS at the midpoint. This keeps
                // peaks in check without touching normal listening levels.
                this.nodes.master = this.ctx.createDynamicsCompressor();
                this.nodes.master.threshold.value = -3;
                this.nodes.master.knee.value = 3;
                this.nodes.master.ratio.value = 12;
                this.nodes.master.attack.value = 0.003;
                this.nodes.master.release.value = 0.25;

                // Connect: Source -> lowShelf -> Gain -> master -> Destination
                this.nodes.p1.connect(this.nodes.low1);
                this.nodes.low1.connect(this.nodes.g1);
                this.nodes.g1.connect(this.nodes.master);

                this.nodes.p2.connect(this.nodes.low2);
                this.nodes.low2.connect(this.nodes.g2);
                this.nodes.g2.connect(this.nodes.master);

                this.nodes.master.connect(this.ctx.destination);
                
                // Initial volumes - PRIORITIZE DEVICE MANAGER
                let initialVol = 0.8;
                if (window.DeviceManager && window.DeviceManager.hasDeviceVolume()) {
                    initialVol = window.DeviceManager.getDeviceVolume();
                } else if (window.StateManager) {
                    initialVol = window.StateManager.getProperty('volume');
                }
                
                // Gain nodes are the SINGLE volume stage: keep the underlying <audio>
                // elements pinned at 1.0. Setting both element.volume AND gain.gain
                // double-attenuates (vol^2) and ï¿½ on iOS, where element.volume is
                // ignored ï¿½ using the element as a second stage breaks fading entirely.
                this.audio.volume = 1.0;
                this.audioNext.volume = 1.0;
                this.nodes.g1.gain.value = initialVol;
                this.nodes.g2.gain.value = 0.0;
                
                window.Helpers.log('AudioEngine: Web Audio nodes connected successfully with initial volume ' + initialVol);
                
                // Resume on user interaction. Contexts start suspended (autoplay
                // policy) and can be suspended or interrupted again when the page
                // is backgrounded, so keep the handlers attached for the app's
                // lifetime instead of removing them after the first success.
                const resume = () => {
                    this._ensureContextRunning('user-interaction');
                };
                document.addEventListener('click', resume);
                document.addEventListener('keydown', resume);
                document.addEventListener('touchstart', resume, { passive: true });
                document.addEventListener('visibilitychange', () => {
                    this._ensureContextRunning('visibilitychange');
                    if (!document.hidden) {
                        // iOS 18 can silently drop the page back to the "ambient"
                        // category after the GPU process restarts; re-assert the
                        // playback type whenever we come back into the foreground.
                        this._setAudioSessionPlayback();
                    }
                });

                // WebKit exposes a fourth state, "interrupted", when the OS takes
                // the audio away (call, alarm, screen lock, tab backgrounded).
                // The old guards only checked "suspended", so every resume path
                // was a no-op in exactly the case it was meant to recover from.
                this.ctx.onstatechange = () => {
                    if (!this.ctx) return;
                    const state = this.ctx.state;
                    window.Helpers.log('AudioEngine: AudioContext state -> ' + state);
                    if (state === 'interrupted' && !document.hidden) {
                        this._ensureContextRunning('statechange:interrupted');
                    }
                };
            } catch (e) {
                console.error('AudioEngine: Web Audio API initialization failed', e);
                this.ctx = null; // Fall back to element-volume-only paths
            }
        },

        initializeAudioElements: function() {
            this.audio = document.getElementById('audioPlayer');
            this.audioNext = document.getElementById('audioPlayerNext');
            
            if (!this.audioNext) {
                this.audioNext = document.createElement('audio');
                this.audioNext.id = 'audioPlayerNext';
                this.audioNext.crossOrigin = 'anonymous'; // Critical for Web Audio
                // iOS: dynamically-created elements must carry these attributes or
                // playback is silently blocked / forced into fullscreen.
                this.audioNext.preload = 'auto';
                this.audioNext.setAttribute('playsinline', '');
                this.audioNext.setAttribute('webkit-playsinline', '');
                document.body.appendChild(this.audioNext);
            }
            
            this.audio.crossOrigin = 'anonymous';
            // Ensure the primary element also carries the iOS-friendly attributes
            // (the static element in index.html has them, but be defensive).
            this.audio.preload = 'auto';
            this.audio.setAttribute('playsinline', '');
            this.audio.setAttribute('webkit-playsinline', '');
            this.audioElementReady = true;
            this.setupAudioEvents(this.audio);
            this.setupAudioEvents(this.audioNext);
        },

        isIOS: function() {
            if (window.PlayerUtils && typeof window.PlayerUtils.isIOS === 'function') {
                return window.PlayerUtils.isIOS();
            }
            return /iPhone|iPad|iPod|iPadOS/i.test(navigator.userAgent) ||
                   (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1) ||
                   (navigator.platform === 'iPhone' || navigator.platform === 'iPad');
        },

        /**
         * iOS unlock: the audio output has to be activated from inside a user
         * gesture before any programmatic play() produces sound.
         *
         * This used to prime the REAL <audio> elements with play()->pause().
         * That was actively harmful: the prime runs after the row's own click
         * handler (document listeners bubble), and touchend + click both fire,
         * so the second prime could pause a song that had just started. Worse,
         * the pause event feeds the state reconciler, which flips the whole
         * player to "paused".
         *
         * Instead we activate the output with a dedicated silent element that
         * loops forever. It never touches playback state and keeps the media
         * session warm while the app is in the foreground.
         */
        _unlockIOSAudio: function() {
            if (!this.isIOS() || this._iosUnlockArmed) return;
            this._iosUnlockArmed = true;

            const unlock = () => {
                if (this._iosUnlockFired) return;
                this._iosUnlockFired = true;

                try {
                    this._setAudioSessionPlayback();
                    if (!this._iosSilentEl) {
                        this._iosSilentEl = this._createSilentAudioElement();
                    }
                    const p = this._iosSilentEl.play();
                    if (p && typeof p.then === 'function') {
                        p.then(() => {
                            window.Helpers.log('AudioEngine: iOS audio output unlocked');
                        }).catch((err) => {
                            console.warn('AudioEngine: iOS silent unlock failed', err);
                            this._iosUnlockFired = false;
                        });
                    }
                    this._iosUnlocked = true;
                } catch (err) {
                    console.error('AudioEngine: iOS unlock failed', err);
                    this._iosUnlockFired = false;
                }

                this._ensureContextRunning('ios-unlock');

                // The play() above has to happen inside the gesture; give the
                // activation a moment before stepping aside for real playback.
                setTimeout(syncKeepAlive, 500);
            };

            const arm = () => {
                document.addEventListener('touchend', unlock, { once: true, passive: true });
                document.addEventListener('click', unlock, { once: true });
                document.addEventListener('keydown', unlock, { once: true });
            };

            arm();

            // The silent element exists only to activate the audio output and keep
            // the media session warm. While real audio is playing it must step
            // aside, otherwise iOS can promote the silent loop to the lock-screen /
            // Media Session item instead of the actual song.
            const syncKeepAlive = () => {
                const el = this._iosSilentEl;
                if (!el) return;
                const anyPlaying = (this.audio && !this.audio.paused) ||
                                   (this.audioNext && !this.audioNext.paused);
                if (anyPlaying) {
                    if (!el.paused) el.pause();
                    return;
                }
                if (el.paused) {
                    const p = el.play();
                    if (p && typeof p.catch === 'function') {
                        p.catch((err) => {
                            console.warn('AudioEngine: iOS silent keep-alive resume failed', err);
                        });
                    }
                }
            };
            [this.audio, this.audioNext].forEach((el) => {
                if (!el) return;
                el.addEventListener('play', syncKeepAlive);
                el.addEventListener('pause', syncKeepAlive);
            });

            // A single tap fires touchend and click; both are registered, but the
            // shared _iosUnlockFired flag makes the work happen exactly once.
            document.addEventListener('visibilitychange', () => {
                if (document.hidden) return;
                this._setAudioSessionPlayback();
                this._ensureContextRunning('visibilitychange:ios-unlock');
                setTimeout(syncKeepAlive, 0);
            });
        },

        /**
         * Build a looping, genuinely silent WAV element used only to activate
         * iOS audio output inside a gesture. Generated in memory so no asset or
         * data URI is needed.
         */
        _createSilentAudioElement: function() {
            const sampleRate = 8000;
            const frames = 2000; // 0.25s at 8kHz, 8-bit mono
            const buffer = new ArrayBuffer(44 + frames);
            const view = new DataView(buffer);
            const writeString = (offset, text) => {
                for (let i = 0; i < text.length; i++) {
                    view.setUint8(offset + i, text.charCodeAt(i));
                }
            };

            writeString(0, 'RIFF');
            view.setUint32(4, 36 + frames, true);
            writeString(8, 'WAVE');
            writeString(12, 'fmt ');
            view.setUint32(16, 16, true);          // PCM chunk size
            view.setUint16(20, 1, true);           // PCM
            view.setUint16(22, 1, true);           // mono
            view.setUint32(24, sampleRate, true);
            view.setUint32(28, sampleRate, true);  // byte rate (8-bit mono)
            view.setUint16(32, 1, true);           // block align
            view.setUint16(34, 8, true);           // bits per sample
            writeString(36, 'data');
            view.setUint32(40, frames, true);
            new Uint8Array(buffer, 44).fill(0x80); // 8-bit PCM silence

            const el = document.createElement('audio');
            el.id = 'audioPlayerUnlock';
            el.preload = 'auto';
            el.loop = true;
            el.setAttribute('playsinline', '');
            el.setAttribute('webkit-playsinline', '');
            el.src = URL.createObjectURL(new Blob([buffer], { type: 'audio/wav' }));
            document.body.appendChild(el);
            return el;
        },

        /**
         * Ask iOS for the "playback" audio session. Without this the page stays
         * on "ambient", which follows the ringer switch and can be muted even
         * while AudioContext.state reports "running". Feature-detected: the API
         * only exists in some Safari versions, and setting it is a no-op
         * elsewhere.
         */
        _setAudioSessionPlayback: function() {
            try {
                if (!navigator.audioSession) return;
                if (navigator.audioSession.type === 'playback') return;
                navigator.audioSession.type = 'playback';
                window.Helpers.log('AudioEngine: audio session type -> playback');
            } catch (err) {
                console.warn('AudioEngine: failed to set audio session type', err);
            }
        },

        /**
         * Resume the AudioContext from either "suspended" or WebKit's
         * non-standard "interrupted" state. Never rejects: callers are
         * fire-and-forget and resume can legitimately fail outside a gesture.
         */
        _ensureContextRunning: function(reason) {
            if (!this.ctx) return Promise.resolve();
            const state = this.ctx.state;
            if (state !== 'suspended' && state !== 'interrupted') return Promise.resolve();

            const label = reason || 'unknown';
            return this.ctx.resume().then(() => {
                window.Helpers.log('AudioEngine: AudioContext resumed (' + label + ', was ' + state + ')');
            }).catch((err) => {
                console.warn('AudioEngine: AudioContext resume failed (' + label + ', state ' + state + ')', err);
            });
        },

        /**
         * Single play path for every caller: sets the audio session, makes sure
         * the context is running, then starts the element. Swallows failures
         * after logging and arming the tap-to-resume fallback, so no caller can
         * end up with an unhandled rejection on iOS.
         */
        _startPlayback: function(player, reason) {
            const el = player || this.getActivePlayer();
            const label = reason || 'unknown';
            if (!el) {
                console.warn('AudioEngine: play() requested with no audio element (' + label + ')');
                return Promise.resolve();
            }

            this._setAudioSessionPlayback();

            // A play request is an explicit "be audible" intent.
            this._userPaused = false;

            // Record the attempt, not just success: the stale websocket frame
            // that pauses the element usually arrives while play() is still
            // pending, so waiting for the promise to resolve leaves the guard
            // disarmed exactly when it is needed.
            this._lastLocalPlayAt = Date.now();

            return this._ensureContextRunning(label).then(() => {
                // Exactly one element may play. A pending fade or a skipped
                // state update can leave the other element running; starting
                // this one without stopping it is how two songs end up mixed.
                // Never do that during a crossfade: the outgoing deck is
                // supposed to keep playing while it fades out, and a "play"
                // state echo must not silence it.
                const fading = this._isCrossfading ||
                    (window.SynchronizationManager && window.SynchronizationManager.getFlag('isCrossfading'));
                if (!fading) {
                    this._pauseOthers(el, 'start-playback:' + label);
                }
                const p = el.play();
                return (p && typeof p.catch === 'function') ? p : Promise.resolve();
            }).then(() => {
                this._lastLocalPlayAt = Date.now();
                return true;
            }).catch((err) => {
                // A skip replaces the source while an earlier play() is still
                // pending; the browser rejects that old promise with AbortError.
                // It is expected and the newer play() handles playback, so it
                // must not be treated as a failure or retried.
                if (err && err.name === 'AbortError') {
                    window.Helpers.log('AudioEngine: play() superseded by a newer load (' + label + ')');
                    return false;
                }
                console.warn('AudioEngine: play() failed (' + label + ')', err);
                if (err && err.name === 'NotAllowedError') {
                    this.autoplayBlocked = true;
                    this._showAutoplayBlockedToast();
                    this._armAutoplayRetry(el);
                }
                return false;
            });
        },

        /**
         * True when this page started playback very recently. A websocket frame
         * saying "paused" is often a stale echo of the state from just before
         * that play; applying it as a command stops the track and shows up as
         * "AbortError: The play() request was interrupted by a call to pause()".
         */
        isRecentLocalPlay: function(windowMs) {
            if (!this._lastLocalPlayAt) return false;
            return (Date.now() - this._lastLocalPlayAt) < (windowMs || 5000);
        },

        _showAutoplayBlockedToast: function() {
            if (window.showToast) {
                window.showToast('Tap play to resume', 'warning');
            }
        },

        _armAutoplayRetry: function(player) {
            const retry = () => {
                if (!this.autoplayBlocked) return;
                this.autoplayBlocked = false; // prevent double-fire from touchend+click
                this._startPlayback(player, 'autoplay-retry').then((ok) => {
                    if (ok) this.resetAutoplayState();
                });
            };
            document.addEventListener('touchend', retry, { once: true, passive: true });
            document.addEventListener('click', retry, { once: true });
            document.addEventListener('keydown', retry, { once: true });
        },

        setupAudioEvents: function(el) {
            el.onended = () => {
                if (el === this.getActivePlayer()) {
                    // Skip auto-next if this song already transitioned via DJ mode
                    // The song keeps playing after crossfade starts, but we don't want
                    // its natural end to trigger another 'next' call
                    if (el._djTransitioned) {
                        window.Helpers?.log?.('[AudioEngine] Skipping auto-next - song already transitioned via DJ');
                        el._djTransitioned = false; // Reset for reuse
                        return;
                    }
                    
                    // Get profile ID - must have a valid one
                    let profileId = null;
                    if (window.StateManager && window.StateManager.getProperty('profileId')) {
                        profileId = window.StateManager.getProperty('profileId');
                    } else if (window.globalActiveProfileId) {
                        profileId = window.globalActiveProfileId;
                    } else {
                        profileId = localStorage.getItem('activeProfileId');
                    }
                    
                    if (profileId && profileId !== 'undefined') {
                        // Send the id of the song that actually ended so the server can
                        // deduplicate against its own end-of-song advance (avoids the
                        // double-advance that skipped songs after natural endings).
                        const endedSongId = el._currentSongId != null ? String(el._currentSongId) : null;
                        fetch(`/api/music/playback/next/${profileId}`, {
                            method: 'POST',
                            headers: {'Content-Type': 'application/json'},
                            body: JSON.stringify({endedSongId: endedSongId})
                        });
                    }
                }
            };
            
            el.onerror = (e) => {
                console.error('AudioEngine: Element error', el.id, e);
                if (window.SynchronizationManager) {
                    window.SynchronizationManager.setFlag('isUpdatingAudioSource', false);
                }
            };
        },

        getActivePlayer: function() {
            return this.activePlayer === 1 ? this.audio : this.audioNext;
        },

        getActiveGain: function() {
            return this.activePlayer === 1 ? this.nodes.g1 : this.nodes.g2;
        },

        getInactivePlayer: function() {
            return this.activePlayer === 1 ? this.audioNext : this.audio;
        },

        getInactiveGain: function() {
            return this.activePlayer === 1 ? this.nodes.g2 : this.nodes.g1;
        },

        /**
         * Pause one element without unloading it. The source stays buffered,
         * which keeps DJ preloads intact.
         */
        _pausePlayer: function(player, reason) {
            if (!player || player.paused) return;
            try {
                player.pause();
            } catch (err) {
                console.warn('AudioEngine: pause() failed for ' + (player.id || 'audio element') + ' (' + (reason || 'unknown') + ')', err);
            }
        },

        /**
         * Silence every element except `keep`. Passing null pauses both. This
         * is the single-stream invariant: after a mix the outgoing element must
         * never be left audible (or playable) next to the incoming one.
         */
        _pauseOthers: function(keep, reason) {
            [this.audio, this.audioNext].forEach((p) => {
                if (!p || p === keep) return;
                this._pausePlayer(p, reason);
            });
        },

        /**
         * Release an element's media resource. Assigning `src = ''` resolves the
         * empty string against the page URL and makes the element try to decode
         * HTML as media, which is what produced "Invalid URI" and
         * NotSupportedError in the console. Removing the attribute and calling
         * load() is the documented way to unload.
         */
        _unloadPlayer: function(player, reason) {
            if (!player) return;
            this._pausePlayer(player, reason);
            try {
                player.removeAttribute('src');
                player._currentSongId = null;
                player._djTransitioned = false;
                player.load();
            } catch (err) {
                console.warn('AudioEngine: unload failed for ' + (player.id || 'audio element') + ' (' + (reason || 'unknown') + ')', err);
            }
        },

        /**
         * Watchdog for the single-stream invariant. Skipped during a crossfade,
         * where both elements are legitimately playing (one fading out).
         */
        _enforceSinglePlayback: function() {
            if (this._isCrossfading) return;
            if (window.SynchronizationManager && window.SynchronizationManager.getFlag('isCrossfading')) return;
            const active = this.getActivePlayer();
            [this.audio, this.audioNext].forEach((p) => {
                if (!p || p === active || p.paused) return;
                window.Helpers?.log?.('[AudioEngine] Stopping non-active player ' + p.id +
                    ' (single-stream invariant, song ' + (p._currentSongId || '?') + ')');
                this._pausePlayer(p, 'single-stream invariant');
            });
        },

        /**
         * Equal-power (constant-power) fade law: cos for the outgoing deck,
         * sin for the incoming. At the midpoint each is 0.707, so summed power
         * stays flat; a linear 1->0 / 0->1 pair drops ~3 dB there (audible
         * "suck-out"). 64 points keeps the piecewise-linear approximation
         * within ~0.0007 dB of the true curve.
         */
        _equalPowerCurve: function(points, kind, level) {
            const n = Math.max(2, points || 64);
            const gain = (typeof level === 'number') ? level : 1;
            const curve = new Float32Array(n);
            for (let i = 0; i < n; i++) {
                const theta = (i / (n - 1)) * (Math.PI / 2);
                curve[i] = (kind === 'out' ? Math.cos(theta) : Math.sin(theta)) * gain;
            }
            return curve;
        },

        /**
         * Start an equal-power crossfade on the Web Audio graph.
         *
         * Anchoring matters: linearRampToValueAtTime interpolates from the
         * PREVIOUS automation event, so a stale event makes a new ramp jump or
         * collapse to zero length (heard as a hard cut). cancelAndHoldAtTime +
         * setValueAtTime pins the start value first. t0 is computed here, never
         * before an await, with a small lookahead so the curve cannot start in
         * the past.
         */
        _startEqualPowerCrossfade: function(outGain, inGain, outLevel, inLevel, duration, bassSwap) {
            const ctx = this.ctx;
            if (!ctx || !outGain || !inGain) return null;

            const t0 = ctx.currentTime + 0.06;
            const d = Math.max(0.25, duration || 2);

            [outGain, inGain].forEach((g) => {
                if (typeof g.gain.cancelAndHoldAtTime === 'function') {
                    g.gain.cancelAndHoldAtTime(t0);
                } else {
                    // Firefox (still) lacks cancelAndHoldAtTime.
                    const v = g.gain.value;
                    g.gain.cancelScheduledValues(t0);
                    g.gain.setValueAtTime(v, t0);
                }
            });

            outGain.gain.setValueAtTime(outLevel, t0);
            inGain.gain.setValueAtTime(0, t0);
            outGain.gain.setValueCurveAtTime(this._equalPowerCurve(64, 'out', outLevel), t0, d);
            inGain.gain.setValueCurveAtTime(this._equalPowerCurve(64, 'in', inLevel), t0, d);

            if (bassSwap) this._scheduleBassSwap(outGain, inGain, t0, d);

            return { start: t0, end: t0 + d };
        },

        /**
         * One bassline at a time. A full-band equal-power crossfade of two
         * correlated basslines sums to about +3 dB in the middle, which is the
         * classic "mud" of a bad blend. Keep the incoming low end cut while the
         * outgoing still owns the bass, then swap on a short ramp at 60 %.
         */
        _scheduleBassSwap: function(outGain, inGain, t0, d) {
            const outLow = (outGain === this.nodes.g1) ? this.nodes.low1 : this.nodes.low2;
            const inLow = (inGain === this.nodes.g1) ? this.nodes.low1 : this.nodes.low2;
            if (!outLow || !inLow || outLow === inLow) return;

            const swap = t0 + d * 0.60;
            const edge = Math.min(0.30, Math.max(0.05, d * 0.10));

            outLow.gain.cancelScheduledValues(t0);
            outLow.gain.setValueAtTime(0, t0);
            outLow.gain.linearRampToValueAtTime(-24, swap);

            inLow.gain.cancelScheduledValues(t0);
            inLow.gain.setValueAtTime(-24, t0);
            inLow.gain.setValueAtTime(-24, Math.max(t0, swap - edge));
            inLow.gain.linearRampToValueAtTime(0, swap + edge);
        },

        /** Restore both deck filters after a fade or a hard source change. */
        _resetDeckFilters: function() {
            const ctx = this.ctx;
            if (!ctx) return;
            [this.nodes.low1, this.nodes.low2].forEach((f) => {
                if (!f) return;
                f.gain.cancelScheduledValues(ctx.currentTime);
                f.gain.setValueAtTime(0, ctx.currentTime);
            });
        },

        /**
         * Single-deck fallback for devices that cannot run two media elements
         * at once: fade the audible deck out, swap its source, fade it back in.
         * Not an overlap, but still smooth — and it uses the Web Audio fader,
         * because element.volume is ignored on iOS.
         */
        _singleDeckFadeSwap: function(player, songId, seekTime, targetVolume) {
            const gain = (player === this.audio) ? this.nodes.g1 : this.nodes.g2;
            const low = (player === this.audio) ? this.nodes.low1 : this.nodes.low2;
            const outMs = 320;
            const inMs = 700;
            const audio = this;
            const level = (typeof targetVolume === 'number' && targetVolume > 0) ? targetVolume : 0.8;

            if (this.ctx && gain) {
                const now = this.ctx.currentTime;
                if (typeof gain.gain.cancelAndHoldAtTime === 'function') {
                    gain.gain.cancelAndHoldAtTime(now);
                } else {
                    gain.gain.cancelScheduledValues(now);
                    gain.gain.setValueAtTime(gain.gain.value, now);
                }
                gain.gain.linearRampToValueAtTime(0, now + outMs / 1000);
            }

            setTimeout(function() {
                if (!player) return;
                audio._pauseOthers(player, 'single-deck swap');
                player._currentSongId = songId;
                player.src = audio.getStreamUrl(songId);
                player.onloadedmetadata = function() {
                    try {
                        player.currentTime = seekTime || 0;
                    } catch (err) {
                        console.warn('AudioEngine: single-deck seek failed', err);
                    }
                    audio._startPlayback(player, 'single-deck-swap').then(function() {
                        if (audio.ctx && gain) {
                            const t = audio.ctx.currentTime;
                            gain.gain.cancelScheduledValues(t);
                            gain.gain.setValueAtTime(0, t);
                            gain.gain.linearRampToValueAtTime(level, t + inMs / 1000);
                        }
                        if (low && audio.ctx) {
                            low.gain.cancelScheduledValues(audio.ctx.currentTime);
                            low.gain.setValueAtTime(0, audio.ctx.currentTime);
                        }
                        window.Helpers?.log?.('[AudioEngine] Single-deck fade swap to ' + songId + ' complete');
                    });
                };
            }, outMs + 20);
        },

        /** Remember a failed dual-audio probe for this device. */
        _setDualAudioSupport: function(ok) {
            this._dualAudio = ok ? 'on' : 'off';
            try {
                if (ok) {
                    localStorage.removeItem('jmedia_dual_audio');
                } else {
                    localStorage.setItem('jmedia_dual_audio', 'off');
                }
            } catch (err) {
                console.warn('AudioEngine: could not persist dual-audio capability', err);
            }
        },

        preload: function(songId, startTime = 0, retryCount = 0) {
            if (window.videoPlaying) return;
            let profileId = null;
            if (window.StateManager && window.StateManager.getProperty('profileId')) {
                profileId = window.StateManager.getProperty('profileId');
            } else if (window.globalActiveProfileId) {
                profileId = window.globalActiveProfileId;
            } else {
                profileId = localStorage.getItem('activeProfileId');
            }
            
            // Don't attempt to load if profileId is not valid - wait for it
            if (!profileId || profileId === 'undefined') {
                window.Helpers?.log?.('[AudioEngine preload] Waiting for valid profileId...');
                const self = this;
                setTimeout(function() {
                    self.preload(songId, startTime);
                }, 200);
                return;
            }
            
            // Don't attempt to load if songId is not valid
            if (!songId || songId === 'null' || songId === 'undefined') {
                window.Helpers?.log?.('[AudioEngine preload] Invalid songId, skipping preload');
                return;
            }

            // During a crossfade the "inactive" player is the OUTGOING element:
            // it is still audible until the fade finishes and its gain reaches
            // zero. Loading a new source into it cuts the fade and can leave it
            // holding/playing a second song, so defer until the fade is done.
            if (this._isCrossfading || (window.SynchronizationManager && window.SynchronizationManager.getFlag('isCrossfading'))) {
                if (retryCount < 25) {
                    window.Helpers?.log?.('[AudioEngine preload] Deferring ' + songId + ' until the crossfade finishes');
                    const self = this;
                    setTimeout(function() {
                        self.preload(songId, startTime, retryCount + 1);
                    }, 400);
                } else {
                    console.warn('[AudioEngine preload] Dropping preload for ' + songId + ' — crossfade never finished');
                }
                return;
            }
            
            // Reset readiness tracking
            this._nextPreloadReady = false;
            this._nextPreloadFailed = false;
            if (this._nextPreloadTimer) {
                clearTimeout(this._nextPreloadTimer);
                this._nextPreloadTimer = null;
            }
            
            const nextPlayer = this.getInactivePlayer();
            const url = `/api/music/stream/${profileId}/${songId}`;
            
            if (!nextPlayer.src.endsWith(url)) {
                window.Helpers.log('AudioEngine: Preloading ' + songId + ' at ' + startTime + 's');
                
                // Track when preload is ready via canplay event
                const existingCanPlay = nextPlayer.oncanplay;
                nextPlayer.oncanplay = () => {
                    if (existingCanPlay) existingCanPlay.call(nextPlayer);
                    this._nextPreloadReady = true;
                    if (this._nextPreloadTimer) {
                        clearTimeout(this._nextPreloadTimer);
                        this._nextPreloadTimer = null;
                    }
                    window.Helpers.log('[AudioEngine] Preload ready for ' + songId);
                };
                
                // 8-second timeout: if preload hangs, flag as failed so transitions can
                // still proceed with a warning rather than getting stuck
                this._nextPreloadTimer = setTimeout(() => {
                    this._nextPreloadFailed = true;
                    window.Helpers.log('[AudioEngine] âš ï¸ Preload timed out for ' + songId + ' after 8s');
                }, 8000);
                
                nextPlayer._currentSongId = songId;
                nextPlayer.src = url;
                nextPlayer._djTransitioned = false; // Clear DJ transition flag when element is reused for new preload
                nextPlayer.load(); // Start buffering
                
                const existingHandler = nextPlayer.onloadedmetadata;
                nextPlayer.onloadedmetadata = () => {
                    if (existingHandler) existingHandler.call(nextPlayer);
                    nextPlayer.currentTime = startTime;
                };
            } else {
                // Already has the source â€” consider it ready
                this._nextPreloadReady = true;
            }
        },

        crossfadeTo: function(songId, entryTime, duration, djManaged, rate) {
            if (window.videoPlaying) {
                window.Helpers?.log?.('[AudioEngine] Blocked crossfadeTo — video is active');
                this._isCrossfading = false;
                return false;
            }

            // iOS: Web Audio drives a real fade now. A previous probe may have
            // proved that this device cannot run two media elements at once;
            // only then do we use the single-deck fade-and-swap (still an
            // audible fade, just no overlap).
            if (this.isIOS() && this._dualAudio === 'off') {
                const iosProfileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId');
                if (!iosProfileId || iosProfileId === 'undefined') {
                    window.Helpers?.log?.('[AudioEngine crossfadeTo] Waiting for valid profileId...');
                    return false;
                }
                if (!songId || songId === 'null' || songId === 'undefined') {
                    window.Helpers?.log?.('[AudioEngine crossfadeTo] Invalid songId, skipping');
                    return false;
                }
                if (this._isCrossfading) {
                    window.Helpers?.log?.('[AudioEngine crossfadeTo] Crossfade already in progress, ignoring re-entry');
                    return false;
                }
                this._isCrossfading = true;
                if (window.SynchronizationManager) {
                    window.SynchronizationManager.setFlag('isCrossfading', true);
                }

                let singleVol = 0.8;
                if (window.StateManager) {
                    const v = window.StateManager.getProperty('volume');
                    if (typeof v === 'number' && v > 0 && v <= 1) singleVol = v;
                }
                this._singleDeckFadeSwap(this.getActivePlayer(), songId, entryTime || 0, singleVol);
                window.Helpers.log('AudioEngine: iOS single-deck fade to ' + songId + ' at ' + (entryTime || 0) + 's');
                setTimeout(() => {
                    this._isCrossfading = false;
                    if (window.SynchronizationManager) {
                        window.SynchronizationManager.setFlag('isCrossfading', false);
                    }
                }, 1400);
                return true;
            }

            const currentPlayer = this.getActivePlayer();
            const nextPlayer = this.getInactivePlayer();
            const outgoingSongId = currentPlayer ? currentPlayer._currentSongId : null;
            const currentGain = this.getActiveGain();
            const nextGain = this.getInactiveGain();
            // Respect the server-provided crossfade duration, clamped to a safe
            // range. duration <= 0 falls back to 2s. Only warn if the requested
            // duration exceeds the 12s cap.
            let fadeTime;
            if (!duration || duration <= 0) {
                fadeTime = 2;
            } else {
                fadeTime = duration;
                if (fadeTime < 1) fadeTime = 1;
                if (fadeTime > 12) {
                    console.warn('[AudioEngine] Requested crossfade duration ' + duration + 's exceeds 12s cap; clamping');
                    fadeTime = 12;
                }
            }
            
            // Swap the active player only after every guard below has passed.
            // Doing it here (as the code used to) flipped the pointer even when
            // the fade was refused, leaving getActivePlayer() on the outgoing
            // element that was still playing the old song.
            
            // Get profile ID - must have a valid one
            let profileId = null;
            if (window.StateManager && window.StateManager.getProperty('profileId')) {
                profileId = window.StateManager.getProperty('profileId');
            } else if (window.globalActiveProfileId) {
                profileId = window.globalActiveProfileId;
            } else {
                profileId = localStorage.getItem('activeProfileId');
            }
            
            // Don't proceed if profileId is not valid. The caller retries
            // (setSource / DjTransitionManager); retrying inside here as well
            // could start two fades for the same song.
            if (!profileId || profileId === 'undefined') {
                window.Helpers?.log?.('[AudioEngine crossfadeTo] Waiting for valid profileId...');
                return false;
            }
            
            // Don't proceed if songId is not valid
            if (!songId || songId === 'null' || songId === 'undefined') {
                window.Helpers?.log?.('[AudioEngine crossfadeTo] Invalid songId, skipping');
                return false;
            }
            
            // Guard against re-entrant crossfade (Bug E fix)
            if (this._isCrossfading) {
                window.Helpers?.log?.('[AudioEngine crossfadeTo] Crossfade already in progress, ignoring re-entry');
                return false;
            }
            this._isCrossfading = true;
            // Publish the shared flag for every fade, not only DJ-managed ones.
            // setSource and setVolume key off it; without it a normal fade is
            // invisible to them and a second source change can re-enter here.
            if (window.SynchronizationManager) {
                window.SynchronizationManager.setFlag('isCrossfading', true);
            }

            // Save the previous active player so we can restore it on abort.
            const previousActivePlayer = this.activePlayer;

            // Watchdog: a fade that never finalizes (for example play() pending
            // on a stalled incoming element) would leave isCrossfading true
            // forever, and every later setSource would defer on it.
            const fadeToken = ++this._fadeToken;
            setTimeout(() => {
                if (this._fadeToken !== fadeToken || !this._isCrossfading) return;
                console.warn('[AudioEngine] Crossfade watchdog: fade did not finalize, clearing flags');
                this._isCrossfading = false;
                if (window.SynchronizationManager) {
                    window.SynchronizationManager.setFlag('isCrossfading', false);
                }
                // Notify DjTransitionManager so isTransitioning cannot stay true forever
                window.DjTransitionManager?.onTransitionAborted?.();
                this._pauseOthers(this.getActivePlayer(), 'crossfade watchdog');
            }, (fadeTime + 6) * 1000);

            // The fade is definitely starting: now the pointer may move so
            // getActivePlayer() returns the fading-in element.
            // (Flip is deferred to startPlayback success to avoid leaving the
            // pointer on the outgoing element if play() never resolves.)
            
            // Mark current player as DJ-transitioned so its onended doesn't trigger auto-next
            // This prevents the old song's natural end from skipping to next after crossfade
            currentPlayer._djTransitioned = true;
            
            // ENSURE the next player has the correct source if not already preloaded
            if (songId) {
                const url = `/api/music/stream/${profileId}/${songId}`;
                if (!nextPlayer.src.endsWith(url)) {
                    window.Helpers.log('AudioEngine: Loading ' + songId + ' during crossfade');
                    nextPlayer._currentSongId = songId;
                    nextPlayer.src = url;
                }
            }

            // Tempo nudge for the incoming deck (beat matching). Bounded to +/-10 %
            // here as a safety net; the planner never exceeds +/-6 %.
            const incomingRate = (typeof rate === 'number' && isFinite(rate) && rate >= 0.9 && rate <= 1.1)
                ? rate : 1.0;
            nextPlayer.playbackRate = incomingRate;
            if (incomingRate !== 1.0) {
                window.Helpers.log('AudioEngine: tempo nudge ' + incomingRate.toFixed(3) + 'x');
            }
            nextPlayer._djRate = incomingRate;

            // USE USER'S CURRENT VOLUME SETTING directly - not the gain node which might be stale
            // CRITICAL: Use a LOW default to prevent volume spikes. If we can't read the user's
            // actual volume setting, we should fade in quietly, not loudly!
            let targetVolume = 0.3; // Safe low default - user can increase if needed
            if (window.StateManager) {
                const stateVol = window.StateManager.getProperty('volume');
                window.Helpers?.log?.('[AudioEngine crossfade] StateManager.volume =', stateVol, 'type:', typeof stateVol);
                if (typeof stateVol === 'number' && stateVol >= 0 && stateVol <= 1) {
                    targetVolume = stateVol;
                }
            }
            
            window.Helpers?.log?.('[AudioEngine crossfade] targetVolume from user setting:', targetVolume);

            window.Helpers.log('AudioEngine: Starting crossfade (' + fadeTime + 's) to entry ' + entryTime + 's');

            // Store entryTime for later use
            const seekTime = entryTime || 0;
            
            // 1. Prepare next player state - set entry time BEFORE playing
            if (nextPlayer.readyState > 0) {
                nextPlayer.currentTime = seekTime;
            } else {
                const existingHandler = nextPlayer.onloadedmetadata;
                nextPlayer.onloadedmetadata = () => {
                    if (existingHandler) existingHandler.call(nextPlayer);
                    nextPlayer.currentTime = seekTime;
                };
            }

            // 2. Equal-power crossfade on the Web Audio graph.
            // The fade is scheduled inside the scheduler (never before an await)
            // and anchored with cancelAndHoldAtTime + setValueAtTime, so a stale
            // automation event cannot turn the ramp into a zero-length hard cut.
            var self = this;
            var scheduleGainRamps = function() {
                if (self.ctx) {
                    // Web Audio is the single volume stage; keep elements pinned.
                    currentPlayer.volume = 1.0;
                    nextPlayer.volume = 1.0;

                    const currentLevel = Math.max(0.0001, currentGain.gain.value);
                    const fade = self._startEqualPowerCrossfade(
                        currentGain, nextGain, currentLevel, targetVolume, fadeTime, true);

                    if (fade) {
                        window.Helpers.log('AudioEngine: Equal-power crossfade scheduled (' + fadeTime +
                            's, bass swap at 60%) to ' + targetVolume);
                    } else {
                        console.warn('AudioEngine: Could not schedule equal-power crossfade');
                    }
                } else {
                    // No Web Audio at all: element-volume approximation (legacy).
                    const savedCurrentVol = currentPlayer.volume;
                    const steps = 40;
                    const interval = (fadeTime * 1000) / steps;
                    let currentStep = 0;
                    const fadeInterval = setInterval(() => {
                        currentStep++;
                        const progress = currentStep / steps;
                        currentPlayer.volume = savedCurrentVol * (1 - progress);
                        nextPlayer.volume = targetVolume * progress;
                        if (currentStep >= steps) {
                            clearInterval(fadeInterval);
                        }
                    }, interval);
                }
            };

            // Runs when the fade length has elapsed: retire the outgoing deck.
            var finalizeCrossfade = () => {

                        this._pausePlayer(currentPlayer, 'crossfade finalize');
                        // A DJ plan for the following song may have preloaded into
                        // the outgoing player during the fade. Clearing its src
                        // would throw that preload away, so keep a newer source.
                        const hasNewerPreload = currentPlayer._currentSongId
                            && outgoingSongId
                            && currentPlayer._currentSongId !== outgoingSongId;
                        if (!hasNewerPreload) {
                            this._unloadPlayer(currentPlayer, 'crossfade finalize');
                        }
                        
                        if (this.ctx) {
                            // CRITICAL: Cancel any ongoing gain curves before setting new values
                            const now = this.ctx.currentTime;
                            currentGain.gain.cancelScheduledValues(now);
                            currentGain.gain.setValueAtTime(0, now);
                            
                            // Also explicitly cancel nextGain curves
                            nextGain.gain.cancelScheduledValues(now);
                        } else {
                            currentPlayer.volume = 0;
                            nextPlayer.volume = targetVolume;
                        }

                        window.Helpers.log('AudioEngine: Crossfade finalized');
                        
                        const newPlayer = this.getActivePlayer();
                        const newGain = this.getActiveGain();
                        
                        // Clear crossfade flag to re-enable progress bar updates
                        if (window.SynchronizationManager) {
                            window.SynchronizationManager.setFlag('isCrossfading', false);
                        }
                        
                        // Update DJ indicator back to active only for the DJ-managed
                        // transition. A manual skip in DJ Mode fades too, but its
                        // freshly planned next transition must survive the fade.
                        // Even for a DJ-managed fade, a newer plan for the following
                        // song may have been prepared during the fade; keep it.
                        const djData = window.DjTransitionManager && window.DjTransitionManager.transitionData;
                        const canClearDj = !djData || String(djData.nextSongId) === String(songId);
                        if (window.DjTransitionManager && djManaged && canClearDj) {
                            window.DjTransitionManager.transitionPrepared = false;
                            window.DjTransitionManager.isTransitioning = false;
                            window.DjTransitionManager.transitionData = null;
                            // Stop the rAF monitor loop and visibility catch-up
                            if (window.DjTransitionManager.stopMonitoring) {
                                window.DjTransitionManager.stopMonitoring();
                            }
                            window.DjTransitionManager.updateDjIndicator('complete');
                        }
                        
                        // FINAL STATE UPDATE: DJ-managed transitions use the entry
                        // position the server recorded; manual/normal crossfades
                        // use the player's actual position so the progress bar does
                        // not jump back by the fade duration.
                        const finalTime = djManaged ? seekTime : (newPlayer.currentTime || seekTime);
                        if (window.StateManager) {
                            window.StateManager.updateState({ 
                                duration: newPlayer.duration || 0,
                                currentTime: finalTime
                            }, 'audioEngine');
                            window.Helpers.log('AudioEngine: Final state sync complete. Duration: ' + newPlayer.duration + ', time: ' + finalTime);
                        }
                        
                        // Apply final volume â€” use pending volume if user changed it during crossfade
                        var finalVolume = targetVolume;
                        if (this._pendingVolume !== null) {
                            finalVolume = window.Helpers.clamp(this._pendingVolume, 0, 1);
                            this._pendingVolume = null;
                            window.Helpers.log('AudioEngine: Applying pending volume ' + finalVolume + ' after crossfade');
                        }
                        
                        // MUST set the gain node AND keep the HTML5 audio element pinned
                        // at 1.0 ï¿½ the gain node is the single volume stage. Setting the
                        // element volume too double-attenuates (finalVolume^2).
                        if (this.ctx) {
                            // Get current time first to cancel all future events
                            const now = this.ctx.currentTime;
                            
                            // Cancel any remaining curves and set exact target value
                            newGain.gain.cancelScheduledValues(now);
                            newGain.gain.setValueAtTime(finalVolume, now);
                            newGain.gain.value = finalVolume; // Force immediate value as well
                            
                            newPlayer.volume = 1.0; // Pin element; gain controls volume
                            
                            window.Helpers.log('AudioEngine: Set gain to ' + finalVolume + ', element pinned at 1.0');
                        } else {
                            newPlayer.volume = finalVolume;
                        }
                        
                        // Also update StateManager's volume to match what we set (ensure consistency)
                        if (window.StateManager) {
                            window.StateManager.updateState({ volume: finalVolume }, 'audioEngine');
                        }
                        
                        // Force sync the slider to the correct position
                        if (window.TimeController) {
                            window.TimeController.updateSliderFromState(finalTime, newPlayer.duration);
                        }
                        
                        // Request state save after crossfade completes to persist any volume changes
                        window.dispatchEvent(new CustomEvent('requestStateSave', {
                            detail: { source: 'audioEngine', includeCurrentTime: false }
                        }));
                        
                        // The swap is done: restore both filters for the next mix.
                        this._resetDeckFilters();
                        this._isCrossfading = false;
            };

            // Schedule the fade once the incoming deck is confirmed running.
            var beginFade = () => {
                if (this._userPaused) {
                    window.Helpers.log('AudioEngine: Aborting crossfade — playback was paused');
                    this._isCrossfading = false;
                    if (window.SynchronizationManager) {
                        window.SynchronizationManager.setFlag('isCrossfading', false);
                    }
                    // Notify DjTransitionManager so isTransitioning cannot stay true forever
                    window.DjTransitionManager?.onTransitionAborted?.();
                    this._pauseOthers(null, 'crossfade aborted');
                    return;
                }
                if (self.ctx && self.ctx.state === 'suspended') {
                    self.ctx.resume().then(scheduleGainRamps).catch(function(err) {
                        console.warn('AudioEngine: AudioContext resume failed before crossfade ramps', err);
                        scheduleGainRamps();
                    });
                } else {
                    scheduleGainRamps();
                }
                setTimeout(finalizeCrossfade, (fadeTime + 0.1) * 1000);
            };

            // Wait for the next player to be ready before starting the transition.
            // This prevents intermittent audio when the fade starts before the
            // audio element has buffered enough to play (Bug A fix).
            var startPlayback = () => {
                if (this._userPaused) {
                    window.Helpers.log('AudioEngine: Aborting crossfade — playback was paused before the incoming element was ready');
                    this._isCrossfading = false;
                    if (window.SynchronizationManager) {
                        window.SynchronizationManager.setFlag('isCrossfading', false);
                    }
                    // Notify DjTransitionManager so isTransitioning cannot stay true forever
                    window.DjTransitionManager?.onTransitionAborted?.();
                    this._pauseOthers(null, 'crossfade aborted');
                    return;
                }
                // Verify entry time is set correctly before playing
                const actualTime = nextPlayer.currentTime || 0;
                if (Math.abs(actualTime - seekTime) > 1) {
                    window.Helpers?.log?.('[AudioEngine] Correcting entry time: was', actualTime, 's, seeking to', seekTime, 's');
                    nextPlayer.currentTime = seekTime;
                }

                // The incoming deck must be silent until the curve starts, so a
                // readiness probe cannot leak audio.
                if (self.ctx && nextGain) {
                    nextGain.gain.cancelScheduledValues(self.ctx.currentTime);
                    nextGain.gain.setValueAtTime(0, self.ctx.currentTime);
                }

                nextPlayer.play().then(() => {
                    // iOS: prove that two media elements can run at once before
                    // committing to an overlap. If they cannot, use the
                    // single-deck fade instead of a silent or duplicated deck.
                    if (this.isIOS() && this._dualAudio !== 'off') {
                        // Sample twice, after any metadata seek has settled, so a
                        // seek jump cannot masquerade as playback progress.
                        setTimeout(() => {
                            const t1 = nextPlayer.currentTime || 0;
                            setTimeout(() => {
                                const t2 = nextPlayer.currentTime || 0;
                                // What matters is that the incoming element can
                                // actually advance while the other deck exists. A
                                // paused outgoing (already ended, or stopped by a
                                // previous action) is not a concurrency failure.
                                const advanced = !nextPlayer.paused && t2 > t1 + 0.02;
                                window.Helpers?.log?.('[AudioEngine] iOS dual-audio probe: out=' +
                                    currentPlayer.id + ' outPaused=' + currentPlayer.paused +
                                    ' in=' + nextPlayer.id + ' nextPaused=' + nextPlayer.paused +
                                    ' t1=' + t1.toFixed(2) + ' t2=' + t2.toFixed(2) +
                                    ' ctx=' + (self.ctx ? self.ctx.state : 'none') + ' ok=' + advanced);
                                if (!advanced) {
                                    window.Helpers?.log?.('[AudioEngine] iOS dual-audio probe failed — using single-deck fades');
                                    this._setDualAudioSupport(false);
                                    this._isCrossfading = false;
                                    if (window.SynchronizationManager) {
                                        window.SynchronizationManager.setFlag('isCrossfading', false);
                                    }
                                    this._unloadPlayer(nextPlayer, 'dual-audio probe failed');
                                    // Restore the previous active player since the fade never started
                                    this.activePlayer = previousActivePlayer;
                                    this._singleDeckFadeSwap(currentPlayer, songId, seekTime, targetVolume);
                                    return;
                                }
                                this._setDualAudioSupport(true);
                                // Incoming deck is now playing — flip activePlayer so
                                // getActivePlayer() returns the fading-in element.
                                this.activePlayer = previousActivePlayer === 1 ? 2 : 1;
                                beginFade();
                            }, 200);
                        }, 200);
                        return;
                    }
                    // Incoming deck is now playing — flip activePlayer so
                    // getActivePlayer() returns the fading-in element.
                    this.activePlayer = previousActivePlayer === 1 ? 2 : 1;
                    beginFade();
                }).catch(e => {
                    console.error('AudioEngine: Crossfade play failed', e);
                    this._isCrossfading = false;
                    if (window.SynchronizationManager) {
                        window.SynchronizationManager.setFlag('isCrossfading', false);
                    }
                    // Restore the previous active player on play rejection/abort
                    this.activePlayer = previousActivePlayer;
                    if (this._userPaused) {
                        window.Helpers.log('AudioEngine: Crossfade failed while paused; not retrying playback');
                        this._pauseOthers(null, 'crossfade failed paused');
                        return;
                    }
                    nextPlayer.play().then(() => {
                        // The incoming element started; stop the outgoing one so
                        // it cannot keep playing underneath it.
                        this._pauseOthers(nextPlayer, 'crossfade retry');
                    }).catch((err) => {
                        console.warn('AudioEngine: Retry play after crossfade failure failed', err);
                        // The incoming element cannot play: put the pointer back
                        // on the still-audible outgoing element instead of
                        // leaving the engine pointing at silence.
                        this.activePlayer = previousActivePlayer;
                        window.Helpers.log('AudioEngine: Crossfade failed; reverted active player to ' + this.getActivePlayer().id);
                    });
                });
            };

            if (nextPlayer.readyState >= 3) { // HAVE_FUTURE_DATA — enough buffered to run the fade
                startPlayback();
            } else {
                var crossfadeTimeout = setTimeout(() => {
                    console.error('[AudioEngine] Crossfade timeout: nextPlayer never fired canplay after 8s');
                    this._isCrossfading = false;
                    if (window.SynchronizationManager) {
                        window.SynchronizationManager.setFlag('isCrossfading', false);
                    }
                    if (this._userPaused) {
                        window.Helpers.log('AudioEngine: Crossfade timeout while paused; leaving both players silent');
                        this._pauseOthers(null, 'crossfade timeout paused');
                        return;
                    }
                    // The incoming element never became ready. Try it anyway;
                    // if it still cannot play, put the pointer back on the
                    // outgoing element so playback continues instead of ending
                    // in silence.
                    const fallbackGain = this.getActiveGain();
                    if (this.ctx && fallbackGain) {
                        fallbackGain.gain.cancelScheduledValues(this.ctx.currentTime);
                        fallbackGain.gain.setValueAtTime(targetVolume, this.ctx.currentTime);
                    }
                    this._resetDeckFilters();
                    this._lastLocalPlayAt = Date.now();
                    const attempt = nextPlayer.play();
                    if (attempt && typeof attempt.catch === 'function') {
                        attempt.then(() => {
                            this._pauseOthers(nextPlayer, 'crossfade-timeout');
                        }).catch((err) => {
                            console.warn('AudioEngine: Crossfade timeout fallback play failed', err);
                            this.activePlayer = (this.activePlayer === 1 ? 2 : 1);
                            window.Helpers.log('AudioEngine: Crossfade timeout; kept outgoing player ' + this.getActivePlayer().id);
                        });
                    }
                }, 8000);
                
                nextPlayer.addEventListener('canplay', function onReady() {
                    clearTimeout(crossfadeTimeout);
                    nextPlayer.removeEventListener('canplay', onReady);
                    startPlayback();
                });
            }
            return true;
        },

        setVolume: function(volume, source = 'unknown') {
            // During crossfade, don't apply the volume change to gain nodes
            // (they have active curves that would be corrupted).
            // Instead, save it as pending — it will be applied after the crossfade completes.
            if (this._isCrossfading ||
                    (window.SynchronizationManager && window.SynchronizationManager.getFlag('isCrossfading'))) {
                this._pendingVolume = volume;
                // Update UI immediately so the slider responds
                if (source !== 'stateManager' && window.StateManager) {
                    window.dispatchEvent(new CustomEvent('requestStateUpdate', {
                        detail: { changes: { volume: volume }, source: 'audioEngine' }
                    }));
                }
                return;
            }

            const gain = this.getActiveGain();
            
            let vol = volume;
            // Use saved volume if none provided
            if (vol === null || vol === undefined) {
                if (window.DeviceManager && window.DeviceManager.hasDeviceVolume()) {
                    vol = window.DeviceManager.getDeviceVolume();
                } else if (window.StateManager) {
                    vol = window.StateManager.getProperty('volume');
                }
            }
            
            if (typeof vol !== 'number') vol = 0.8;
            vol = window.Helpers.clamp(vol, 0, 1);
            
            // Apply volume to the SINGLE active stage: the gain node when Web Audio
            // exists, else the element volume. Setting both would double-attenuate
            // (vol^2) because the element feeds the gain node.
            const player = this.getActivePlayer();
            if (gain && this.ctx) {
                const safeVol = Math.max(0.0001, vol);
                const now = this.ctx.currentTime;
                // cancelScheduledValues can leave a running setValueCurveAtTime
                // in place; an event inside its window throws NotSupportedError.
                // Only automate when the param is idle (no fade in progress).
                gain.gain.cancelScheduledValues(now);
                gain.gain.setValueAtTime(gain.gain.value, now);
                gain.gain.linearRampToValueAtTime(safeVol, now + 0.1);
                if (player) player.volume = 1.0; // element stays pinned while gain is active
            } else if (player) {
                player.volume = vol;
            }
            
            if (source !== 'stateManager' && window.StateManager) {
                window.dispatchEvent(new CustomEvent('requestStateUpdate', {
                    detail: { changes: { volume: vol }, source: 'audioEngine' }
                }));
            }
        },

        // Rest of the wrapper methods use getActivePlayer()
        play: function() {
            if (window.videoPlaying) {
                window.Helpers?.log?.('[AudioEngine] Blocked play() â€” video is active');
                return Promise.resolve();
            }
            return this._startPlayback(this.getActivePlayer(), 'play');
        },
        
        pause: function() {
            const player = this.getActivePlayer();
            window.Helpers?.log?.('[AudioEngine pause] Pausing active player:', player.id, 'currently playing:', !player.paused);
            // Remember the intent: a crossfade that is still waiting for its
            // incoming element must not start playback after the user paused.
            this._userPaused = true;
            // Pause BOTH elements. After a mix the outgoing element can still be
            // audible, or the pointer can be on the element that is not the one
            // making sound; pausing only the "active" one leaves the other song
            // running — the "I still hear two songs" symptom.
            this._pauseOthers(null, 'pause');
            window.Helpers?.log?.('[AudioEngine pause] After pause, isPaused:', player.paused);
        },

        getCurrentTime: function() { return this.getActivePlayer()?.currentTime || 0; },
        getDuration: function() { return this.getActivePlayer()?.duration || 0; },
        isPlaying: function() { return !this.getActivePlayer()?.paused; },
        isPaused: function() { return !!this.getActivePlayer()?.paused; },
        getAudioElement: function() { return this.getActivePlayer(); },
        
        performSetSource: function(operationId, currentSong, prevSong, nextSong, play, backendTime) {
            SynchronizationManager.setActiveAudioOperation(operationId);
            SynchronizationManager.setFlag('isUpdatingAudioSource', true);
            
            const player = this.getActivePlayer();
            const gain = this.getActiveGain();
            const url = `/api/music/stream/${window.globalActiveProfileId || localStorage.getItem('activeProfileId')}/${currentSong.id}`;
            // A direct source change is authoritative: silence the other
            // element first so it cannot keep playing the previous song under
            // the one we are about to start.
            this._pauseOthers(player, 'performSetSource');
            // Undo any bass cut left by an interrupted crossfade.
            this._resetDeckFilters();
            // A fresh song always starts at normal speed; any tempo nudge belonged
            // to the previous transition.
            player.playbackRate = 1.0;
            player._djRate = 1.0;
            player._currentSongId = currentSong.id;
            
            if (player.src !== url) {
                player.src = url;
                player.load();
                
                // SKIP VOLUME RESTORE DURING CROSSFADE - gain nodes are controlled by crossfade curves
                // Setting volume during an active curve throws "Can't add events during a curve event"
                const fading = this._isCrossfading ||
                    (window.SynchronizationManager && window.SynchronizationManager.getFlag('isCrossfading'));
                if (!fading) {
                    // RESTORE VOLUME ON LOAD — single active volume stage: the gain node
                    // when Web Audio exists, else the element volume.
                    let currentVol = 0.8;
                    if (window.DeviceManager && window.DeviceManager.hasDeviceVolume()) {
                        currentVol = window.DeviceManager.getDeviceVolume();
                    } else if (window.StateManager) {
                        currentVol = window.StateManager.getProperty('volume');
                    }
                    // Guard nodes existence so a failed Web Audio init does not throw
                    // here and abort performSetSource before player.play().
                    if (this.nodes.g1 && this.nodes.g2 && this.ctx) {
                        const now = this.ctx.currentTime;
                        // Anchor instead of assigning .value: assigning is a
                        // setValueAtTime at currentTime and throws if a curve
                        // happens to still be running on this fader.
                        gain.gain.cancelScheduledValues(now);
                        gain.gain.setValueAtTime(currentVol, now);
                        player.volume = 1.0; // element pinned; gain is the volume stage
                    } else {
                        player.volume = currentVol;
                    }
                } else {
                    // A fade owns the fader; the curve already ends at the
                    // user's target volume, so there is nothing to apply here.
                }
            } else if (play && player.paused) {
                this._startPlayback(player, 'performSetSource:same-source');
            }

            player.onloadedmetadata = () => {
                const seekTime = backendTime > 0 ? backendTime : 0;
                player.currentTime = seekTime;
                
                window.dispatchEvent(new CustomEvent('requestStateUpdate', {
                    detail: {
                        changes: {
                            currentSongId: currentSong.id,
                            songName: currentSong.title,
                            artist: currentSong.artist,
                            duration: player.duration,
                            currentTime: seekTime
                        },
                        source: 'audioEngine'
                    }
                }));
                
                SynchronizationManager.clearActiveAudioOperation(operationId);
                SynchronizationManager.setFlag('isUpdatingAudioSource', false);
            };

            if (play) {
                this._startPlayback(player, 'performSetSource');
            }
            
            return true;
        },

        loadAudioSourceOnly: function(currentSong, seekTime) {
            if (window.videoPlaying) return;
            if (window.SynchronizationManager) {
                const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId');
                window.SynchronizationManager.executeAtomic('audio', profileId, (opId) => {
                    return this.performSetSource(opId, currentSong, null, null, true, seekTime);
                });
                return;
            }
            const player = this.getActivePlayer();
            const url = `/api/music/stream/${window.globalActiveProfileId || localStorage.getItem('activeProfileId')}/${currentSong.id}`;
            this._pauseOthers(player, 'loadAudioSourceOnly');
            player._currentSongId = currentSong.id;
            player.src = url;
            player.onloadedmetadata = () => {
                player.currentTime = seekTime;
                this._startPlayback(player, 'loadAudioSourceOnly');
            };
        },

        setupEventListeners: function() {
            window.addEventListener('statePropertyChanged', (e) => {
                if (e.detail.property === 'volume') this.setVolume(e.detail.newValue, 'stateManager');
                if (e.detail.property === 'currentSongId' && e.detail.newValue && window.StateManager) {
                    if (window.videoPlaying) return;
                    const state = window.StateManager.getState();
                    if (String(e.detail.newValue) !== String(e.detail.oldValue)) {
                        this.setSource({
                            id: e.detail.newValue,
                            title: state.songName,
                            artist: state.artist,
                            duration: state.duration
                        }, null, null, state.playing, state.currentTime || 0);
                    }
                }
            });
            window.addEventListener('requestAudioControl', (e) => this.handleControlRequest(e.detail));
        },

        handleControlRequest: function(request) {
            const { action, value, source } = request;
            switch (action) {
                case 'play': this.play(); break;
                case 'pause': this.pause(); break;
                case 'setTime': this.setTime(value); break;
                case 'setVolume': this.setVolume(value, source); break;
                case 'setSource': this.setSource(value.source, value.prevSong, value.nextSong, value.play, value.backendTime); break;
            }
        },

        setSource: function(currentSong, prevSong = null, nextSong = null, play = true, backendTime = 0, retryCount = 0) {
            if (window.videoPlaying) {
                window.Helpers?.log?.('[AudioEngine] Blocked setSource â€” video is active');
                return false;
            }
            const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId');
            if (!window.SynchronizationManager) return false;

            // STATE ECHO GUARD: never start a transition to the song that is
            // already playing on the active deck. Websocket state echoes repeat
            // after a song change; without this the engine faded the same song
            // into the other deck over and over ("two songs fighting").
            const playingNow = this.getActivePlayer();
            if (currentSong && currentSong.id && playingNow &&
                    String(playingNow._currentSongId) === String(currentSong.id) &&
                    !playingNow.paused) {
                const delta = Math.abs((backendTime || 0) - (playingNow.currentTime || 0));
                if (delta < 2.0) {
                    window.Helpers?.log?.('[AudioEngine] Ignoring setSource: song ' +
                        currentSong.id + ' is already playing on the active deck');
                    this._deferStartedAt = 0;
                    return true;
                }
            }

            // CROSSFADE GUARD: while a fade runs, the incoming song is already
            // loaded on the active (fading-in) player. A state echo for that
            // same song is a no-op. A request for a DIFFERENT song must wait:
            // touching either element mid-fade cuts the fade or starts a second
            // stream, which is what produced two songs at once.
            if (window.SynchronizationManager.getFlag('isCrossfading')) {
                const activePlayer = this.getActivePlayer();
                const targetUrl = this.getStreamUrl(currentSong.id);
                const activeSongId = activePlayer && activePlayer._currentSongId;
                
                if ((activePlayer && targetUrl && activePlayer.src.endsWith(targetUrl)) ||
                        (activeSongId && String(activeSongId) === String(currentSong.id))) {
                    window.Helpers.log('AudioEngine: Ignoring setSource during active crossfade for song ' + currentSong.id);
                    this._deferStartedAt = 0;
                    return true;
                }

                // Bound the wait by time, not by a per-call retry counter: each
                // new state echo restarts the counter, which deferred forever
                // when a fade flag got stuck.
                const deferStart = this._deferStartedAt || Date.now();
                this._deferStartedAt = deferStart;
                if (Date.now() - deferStart < 3000) {
                    window.Helpers.log('AudioEngine: Deferring setSource for song ' + currentSong.id + ' until the crossfade finishes');
                    const self = this;
                    setTimeout(() => {
                        self.setSource(currentSong, prevSong, nextSong, play, backendTime, retryCount + 1);
                    }, 250);
                    return true;
                }
                this._deferStartedAt = 0;
                window.Helpers?.log?.('[AudioEngine] Crossfade still running after 3s; applying source directly for ' + currentSong.id);
            }

            // CROSSFADE: apply to normal playback and to manual skips in DJ Mode.
            // The DJ-managed natural transition already sets isCrossfading and
            // isTransitioning, so it is excluded here to avoid a double fade.
            const state = window.StateManager?.getState();
            const isPlaying = state?.playing;
            const djActive = !!state?.djModeActive;
            const djManaged = !!(window.DjTransitionManager && window.DjTransitionManager.isTransitioning);
            const crossfading = !!(window.SynchronizationManager.getFlag && window.SynchronizationManager.getFlag('isCrossfading'));
            const configuredDuration = state?.crossfadeDuration;
            const fadeDuration = configuredDuration > 0 ? configuredDuration : (djActive ? 8 : 0);
            const active = this.getActivePlayer();
            const nearEnd = active && active.duration > 0 && (active.duration - active.currentTime) < 1.5;
            const manualDjChange = djActive && !djManaged && !crossfading && !nearEnd;

            if (currentSong && currentSong.id && fadeDuration > 0 && isPlaying
                    && (!djActive || manualDjChange)) {
                window.Helpers.log('AudioEngine: Crossfade triggered for song ' + currentSong.id +
                    ' (' + fadeDuration + 's, ' + (djActive ? 'manual DJ skip' : 'normal') + ')');
                // A prepared natural transition is now stale. Cancel it only if
                // it targets the song we are manually switching to; a plan for
                // the following song must survive.
                const prepared = window.DjTransitionManager && window.DjTransitionManager.transitionData;
                if (manualDjChange && prepared && String(prepared.nextSongId) === String(currentSong.id)) {
                    window.DjTransitionManager.cancelTransition();
                }
                // Preload the next song in the inactive player
                this.preloadSong(currentSong.id, backendTime || 0);
                // Initiate crossfade
                if (this.crossfadeTo(currentSong.id, backendTime || 0, fadeDuration, false)) {
                    return true;
                }
                // Never report success for a fade that did not start: the state
                // machine would advance while the previous song keeps playing.
                window.Helpers.log('AudioEngine: Crossfade did not start for song ' + currentSong.id + ' — applying source directly');
            }

            const result = SynchronizationManager.executeAtomic('audio', profileId, (opId) => {
                return this.performSetSource(opId, currentSong, prevSong, nextSong, play, backendTime);
            });

            if (result === false && retryCount < 10) {
                // Audio lock was held by another operation â€” retry instead of silently
                // dropping. A dropped setSource left the UI state ahead of the audio
                // element ("skip doesn't work"), then the stale element's 'ended'
                // echoed another next and skipped past the song.
                window.Helpers.log('AudioEngine: Audio lock busy, retrying setSource for song ' + currentSong.id + ' (attempt ' + (retryCount + 1) + ')');
                const self = this;
                setTimeout(() => {
                    self.setSource(currentSong, prevSong, nextSong, play, backendTime, retryCount + 1);
                }, 150);
                return true;
            }
            return result;
        },

        resetAutoplayState: function() { this.autoplayBlocked = false; this.autoplayAttempted = false; },

        setTime: function(time, allowDuringFade) {
            if (this._isCrossfading && !allowDuringFade) {
                window.Helpers?.log?.('[AudioEngine] setTime ignored during crossfade; use setTime(time, true) to override');
                return;
            }
            const player = this.getActivePlayer();
            if (player) player.currentTime = window.Helpers.safeNumber(time, 0);
        },

        getStreamUrl: function(songId) {
            return `/api/music/stream/${window.globalActiveProfileId || localStorage.getItem('activeProfileId')}/${songId}`;
        },

        preloadSong: function(songId, startTime) {
            this.preload(songId, startTime);
            return this.getInactivePlayer();
        },

        isReady: function() { return this.audioElementReady && this.getActivePlayer() !== null; },

        getAudioInfo: function() {
            const p = this.getActivePlayer();
            const gain = this.getActiveGain();
            return {
                ready: this.audioElementReady,
                src: p ? p.src : null,
                currentTime: this.getCurrentTime(),
                duration: this.getDuration(),
                volume: gain ? gain.gain.value : (p ? p.volume : 0),
                paused: this.isPaused(),
                activePlayer: this.activePlayer
            };
        },
        
        /**
         * Create a Float32Array for smooth volume curves
         * @param {number} startVol - Starting volume
         * @param {number} endVol - Ending volume
         * @param {number} duration - Duration in seconds
         * @param {string} type - 'out' for fade out, 'in' for fade in
         * @returns {Float32Array} Audio curve array
         */
        _createFadeCurve: function(startVol, endVol, duration, type) {
            if (!this.ctx) {
                // No AudioContext (iOS native path): fades are not used there, but
                // keep this callable instead of throwing on a null dereference.
                console.warn('AudioEngine: _createFadeCurve called without an AudioContext; assuming 44.1kHz');
            }
            const sampleRate = this.ctx ? this.ctx.sampleRate : 44100;
            const samples = Math.ceil(duration * sampleRate);
            const curve = new Float32Array(samples);
            
            for (let i = 0; i < samples; i++) {
                const t = i / samples;
                // Use sine-based curves for natural constant power crossfade
                // Fade out: cos(Ï€/2 * t) goes from 1 to 0
                // Fade in: sin(Ï€/2 * t) goes from 0 to 1
                if (type === 'out') {
                    curve[i] = startVol * Math.cos((Math.PI / 2) * t);
                } else {
                    curve[i] = endVol * Math.sin((Math.PI / 2) * t);
                }
            }
            return curve;
        }
    };
    
    // Auto-initialize when dependencies are available
    if (window.Helpers && window.SynchronizationManager && window.StateManager) {
        window.AudioEngine.init();
    } else {
        // Wait for dependencies
        const checkDeps = () => {
            if (window.Helpers && window.SynchronizationManager && window.StateManager) {
                window.AudioEngine.init();
            } else {
                setTimeout(checkDeps, 50);
            }
        };
        setTimeout(checkDeps, 50);
    }
    
})(window);
