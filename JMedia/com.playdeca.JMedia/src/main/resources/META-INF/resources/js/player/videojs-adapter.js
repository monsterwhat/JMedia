(function() {
    'use strict';

    window.initVideoJsAdapter = function(videoId) {
        if (!videoId) {
            var container = document.getElementById('customPlayer');
            if (container) videoId = container.dataset.videoId;
        }
        if (!videoId) {
            console.error('[VideoJsAdapter] No videoId provided');
            return;
        }

        /* ---------- DOM refs (only custom overlay elements) ---------- */
        var container   = document.getElementById('customPlayer');
        var videoEl     = document.getElementById('videoElement');
        var subtitleMenu= document.getElementById('subtitleMenu');
        var assCanvas   = document.getElementById('assCanvas');
        var settingsToggleBtn = document.getElementById('settingsToggleBtn');

        var profileId = localStorage.getItem('activeProfileId');
        var volumeKey = 'jmedia_video_volume_' + profileId;
        var muteKey = 'jmedia_video_mute_' + profileId;

        var _applyingServerState = false;
        var _localSeekAt = 0;
        var _localSeekPos = -1;
        var _destroyed = false;
        var _wsManager = null;
        var _swapInProgress = false;
        var _swapRetries = 0;
        var _swapErrorHandler = null;
        var _swapTimeout = null;
        // pid was previously scoped inside _initWebSocket only; _broadcastState referencing it threw a strict-mode ReferenceError
        var pid = profileId;

        /* ---------- Build stream URL ---------- */
        var streamUrl = document.getElementById('customPlayer').dataset.externalUrl || ('/api/video/stream/' + encodeURIComponent(videoId) + '.mp4');
        var streamType = streamUrl.includes('.m3u8') ? 'application/x-mpegURL' : 'video/mp4';

        /* ---------- Initialize Video.js with native controls ---------- */
        var vjsPlayer = videojs(videoEl, {
            controls: true,
            autoplay: true,
            preload: 'auto',
            html5: {
                nativeTextTracks: false,
                nativeAudioTracks: false,
                nativeVideoTracks: false
            },
            sources: [{ src: streamUrl, type: streamType }]
        });

        /* Restore volume/mute from localStorage with exponential curve (matching JMedia default player) */
        var savedVolume = Math.pow(parseFloat(localStorage.getItem(volumeKey) || '0.7'), 2);
        var savedMuted = localStorage.getItem(muteKey) === 'true';
        vjsPlayer.volume(savedVolume);
        vjsPlayer.muted(savedMuted);

        /* Restore playback position from saved progress */
        var savedTime = parseFloat(container.dataset.startTime || '0');
        if (savedTime > 0) {
            vjsPlayer.one('loadedmetadata', function() {
                vjsPlayer.currentTime(savedTime);
            });
        }

        /* ---------- WebSocket state sync ---------- */
        _initWebSocket();

        /* ---------- Settings Menu Navigation ---------- */
        subtitleMenu.addEventListener('click', function(e) {
            var item = e.target.closest('.settings-item');
            if (item) {
                e.stopPropagation();
                var page = item.dataset.page;
                var target = subtitleMenu.querySelector('.settings-page[data-page="' + page + '"]');
                if (target) {
                    subtitleMenu.querySelectorAll('.settings-page').forEach(function(p) { p.classList.remove('active'); });
                    target.classList.add('active');
                }
                return;
            }
            var back = e.target.closest('.settings-back');
            if (back) {
                e.stopPropagation();
                subtitleMenu.querySelectorAll('.settings-page').forEach(function(p) { p.classList.remove('active'); });
                var mainPage = subtitleMenu.querySelector('.settings-page[data-page="main"]');
                if (mainPage) mainPage.classList.add('active');
                return;
            }
            var manageBtn = e.target.closest('#manageSubtitlesBtn');
            if (manageBtn) {
                e.stopPropagation();
                subtitleMenu.classList.remove('active');
                if (window.subtitleManager) {
                    window.subtitleManager.openModal(videoId, container.dataset.title, container.dataset.path);
                }
                return;
            }
            var playerOpt = e.target.closest('.player-option');
            if (playerOpt) {
                e.stopPropagation();
                var playerName = playerOpt.dataset.player;
                if (playerName) {
                    subtitleMenu.querySelectorAll('.player-option').forEach(function(b) { b.style.borderColor = ''; b.style.color = ''; });
                    playerOpt.style.borderColor = '#48c774';
                    playerOpt.style.color = '#48c774';
                    if (window.Toast) window.Toast.info('Switching to ' + playerName + '...');
                    var profileId = localStorage.getItem('activeProfileId');
                    fetch('/api/settings/' + profileId + '/default-player', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ defaultPlayer: playerName })
                    }).then(function() {
                        location.reload();
                    }).catch(function() {
                        if (window.Toast) window.Toast.error('Failed to switch player');
                    });
                }
                return;
            }

            var qualityBtn = e.target.closest('.quality-btn');
            if (qualityBtn) {
                e.stopPropagation();
                var quality = parseInt(qualityBtn.dataset.quality);
                var label = qualityBtn.textContent.trim();
                subtitleMenu.querySelectorAll('.quality-btn').forEach(function(b) { b.classList.remove('active'); });
                qualityBtn.classList.add('active');
                if (window.Toast) window.Toast.info('Quality: ' + label);

                var currentTime = vjsPlayer.currentTime() || 0;
                var url = '/api/video/stream/' + encodeURIComponent(videoId) + '.mp4?start=' + currentTime + '&quality=' + quality;
                vjsPlayer.ready(function() {
                    vjsPlayer.src({ src: url, type: 'video/mp4' });
                    vjsPlayer.currentTime(currentTime);
                    vjsPlayer.play().catch(function() {});
                });
                return;
            }
        });

        /* ---------- Settings toggle ---------- */
        settingsToggleBtn.addEventListener('click', function(e) {
            e.stopPropagation();
            subtitleMenu.classList.toggle('active');
        });

        /* Close menu when clicking outside */
        document.addEventListener('click', function(e) {
            if (subtitleMenu.classList.contains('active') &&
                !subtitleMenu.contains(e.target) &&
                e.target !== settingsToggleBtn &&
                !settingsToggleBtn.contains(e.target)) {
                subtitleMenu.classList.remove('active');
            }
        });

        /* ---------- Subtitle Timing Offset ---------- */
        var subMinus = document.getElementById('subMinusBtn');
        if (subMinus) subMinus.addEventListener('click', function(e) {
            e.stopPropagation();
            if (window.subtitleManager) window.subtitleManager.adjustCorrection(-0.2);
            if (window.testPlayerFeatures && window.testPlayerFeatures.loadSubtitles) {
                window.testPlayerFeatures.loadSubtitles(true);
            }
        });
        var subPlus = document.getElementById('subPlusBtn');
        if (subPlus) subPlus.addEventListener('click', function(e) {
            e.stopPropagation();
            if (window.subtitleManager) window.subtitleManager.adjustCorrection(0.2);
            if (window.testPlayerFeatures && window.testPlayerFeatures.loadSubtitles) {
                window.testPlayerFeatures.loadSubtitles(true);
            }
        });
        var subReset = document.getElementById('subResetBtn');
        if (subReset) subReset.addEventListener('click', function(e) {
            e.stopPropagation();
            localStorage.setItem('jmedia_subtitle_correction', 0);
            var valEl = document.getElementById('subCorrectionVal');
            if (valEl) valEl.textContent = '0.0s';
            if (window.testPlayerFeatures && window.testPlayerFeatures.loadSubtitles) {
                window.testPlayerFeatures.loadSubtitles(true);
            }
        });
        var correctionVal = document.getElementById('subCorrectionVal');
        if (correctionVal) {
            var initialCorrection = parseFloat(localStorage.getItem('jmedia_subtitle_correction') || '0');
            correctionVal.textContent = (initialCorrection >= 0 ? '+' : '') + initialCorrection.toFixed(1) + 's';
            correctionVal.addEventListener('click', function(e) {
                e.stopPropagation();
                var currentVal = parseFloat(localStorage.getItem('jmedia_subtitle_correction') || '0');
                var input = document.createElement('input');
                input.type = 'number';
                input.step = '0.1';
                input.value = currentVal.toFixed(1);
                input.className = 'correction-input';
                input.style.cssText = 'width:70px;text-align:center;background:#333;color:white;border:1px solid #48c774;border-radius:4px;padding:4px;font-family:monospace;font-size:0.9rem;';

                function saveValue() {
                    var val = parseFloat(input.value) || 0;
                    val = Math.round(val * 10) / 10;
                    localStorage.setItem('jmedia_subtitle_correction', val);
                    correctionVal.textContent = (val >= 0 ? '+' : '') + val.toFixed(1) + 's';
                    if (window.testPlayerFeatures && window.testPlayerFeatures.loadSubtitles) {
                        window.testPlayerFeatures.loadSubtitles(true);
                    }
                }
                input.onblur = function() {
                    saveValue();
                    if (correctionVal.parentNode) correctionVal.parentNode.replaceChild(correctionVal, input);
                };
                input.onkeydown = function(ke) {
                    if (ke.key === 'Enter') { ke.preventDefault(); input.blur(); }
                    else if (ke.key === 'Escape') { ke.preventDefault(); input.value = currentVal.toFixed(1); input.blur(); }
                };
                correctionVal.parentNode.replaceChild(input, correctionVal);
                input.focus();
                input.select();
            });
        }

        /* ---------- Fullscreen (iOS-aware) ---------- */
        var _isCssFS = false;

        function enterCssFullscreen() {
            _isCssFS = true;
            container.classList.add('is-css-fullscreen', 'is-fullscreen');
            document.body.style.overflow = 'hidden';
        }

        function exitCssFullscreen() {
            _isCssFS = false;
            container.classList.remove('is-css-fullscreen', 'is-fullscreen');
            document.body.style.overflow = '';
        }

        function toggleFullscreen() {
            var isNativeFS = !!(document.fullscreenElement || document.webkitFullscreenElement);

            if (isNativeFS) {
                if (document.exitFullscreen) document.exitFullscreen();
                else if (document.webkitExitFullscreen) document.webkitExitFullscreen();
                return;
            }

            if (_isCssFS) {
                exitCssFullscreen();
                return;
            }

            var isIOS = window.PlayerUtils && window.PlayerUtils.isIOS();
            if (isIOS && videoEl) {
                try {
                    if (videoEl.requestFullscreen) {
                        videoEl.requestFullscreen();
                    } else if (videoEl.webkitEnterFullscreen) {
                        videoEl.webkitEnterFullscreen();
                    } else {
                        enterCssFullscreen();
                    }
                } catch (err) {
                    console.warn('[VideoJsAdapter] iOS fullscreen failed:', err);
                    enterCssFullscreen();
                }
            } else {
                if (container.requestFullscreen) {
                    container.requestFullscreen().catch(function() {
                        console.warn('[VideoJsAdapter] Fullscreen denied, using CSS fallback');
                        enterCssFullscreen();
                    });
                } else if (container.webkitRequestFullscreen) {
                    container.webkitRequestFullscreen();
                } else {
                    enterCssFullscreen();
                    return;
                }
                container.classList.add('is-fullscreen');
            }
        }

        document.addEventListener('fullscreenchange', function() {
            if (_isCssFS) return;
            var isFS = document.fullscreenElement || document.webkitFullscreenElement;
            container.classList.toggle('is-fullscreen', !!isFS);
        });
        document.addEventListener('webkitfullscreenchange', function() {
            if (_isCssFS) return;
            var isFS = document.fullscreenElement || document.webkitFullscreenElement;
            container.classList.toggle('is-fullscreen', !!isFS);
        });

        /* iOS native fullscreen events on the video element */
        videoEl.addEventListener('webkitbeginfullscreen', function() {
            _isCssFS = false;
            container.classList.add('is-fullscreen');
        });
        videoEl.addEventListener('webkitendfullscreen', function() {
            container.classList.remove('is-fullscreen');
            if (!vjsPlayer.paused()) {
                vjsPlayer.play().catch(function() {});
            }
        });

        /* ---------- Back button (if present) ---------- */
        var backBtn = document.getElementById('backBtn');
        if (backBtn) backBtn.addEventListener('click', function() { history.back(); });

        /* ---------- Keyboard shortcuts (capture phase to bypass Video.js internal handlers) ---------- */
        document.addEventListener('keydown', function(e) {
            if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;
            switch (e.key) {
                case ' ':
                case 'k': e.preventDefault(); if (vjsPlayer.paused()) vjsPlayer.play(); else vjsPlayer.pause(); break;
                case 'f': e.preventDefault(); toggleFullscreen(); break;
                case 'm': e.preventDefault(); vjsPlayer.muted(!vjsPlayer.muted()); break;
                case 'ArrowLeft': e.preventDefault(); vjsPlayer.currentTime(Math.max(0, (vjsPlayer.currentTime() || 0) - 15)); break;
                case 'ArrowRight': e.preventDefault(); vjsPlayer.currentTime(Math.min(vjsPlayer.duration() || 0, (vjsPlayer.currentTime() || 0) + 15)); break;
                case 'ArrowUp':
                    e.preventDefault();
                    vjsPlayer.volume(Math.min(1, (vjsPlayer.volume() || 0) + 0.1));
                    break;
                case 'ArrowDown':
                    e.preventDefault();
                    vjsPlayer.volume(Math.max(0, (vjsPlayer.volume() || 0) - 0.1));
                    break;
            }
        }, true);

        /* ---------- Stream status reporting for live channels ---------- */
        var _liveChannelId = container.dataset.liveChannelId;
        var _statusReported = false;

        function _reportStreamStatus(status) {
            if (_statusReported || !_liveChannelId) return;
            _statusReported = true;
            fetch('/api/video/m3u/channels/' + _liveChannelId + '/status', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ status: status })
            }).catch(function(err) {
                console.warn('[VideoJsAdapter] Failed to report stream status:', err);
            });
        }

        function _initWebSocket() {
            if (!window.VideoWebSocketManager) return;
            if (!pid) return;
            _wsManager = new window.VideoWebSocketManager({
                profileId: pid,
                onStateUpdate: function(state) {
                    if (!state || _destroyed || !videoEl) return;
                    _applyingServerState = true;
                    try {
                        // Source-reload: if server selected a different video, reinit player for the new source
                        if (state.currentVideoId && state.currentVideoId.toString() !== videoId.toString()) {
                            var newId = state.currentVideoId;
                            _swapInProgress = true;
                            _swapRetries = 0;
                            clearTimeout(_swapTimeout);
                            // Cache-bust the new source URL so the browser cannot serve the old episode from cache
                            var newUrl = container.dataset.externalUrl || ('/api/video/stream/' + encodeURIComponent(newId) + '.mp4?trace=' + Date.now());
                            videoId = newId;  // update the closure variable
                            var newType = newUrl.includes('.m3u8') ? 'application/x-mpegURL' : 'video/mp4';
                            // Resume from the NEW video's server state (B10): advanceVideo -> 0 -> no seek; selectVideo -> resume time -> seek
                            var resumeTime = (typeof state.currentTime === 'number' && state.currentTime > 0) ? state.currentTime : 0;
                            _performSwapLoad(newUrl, newType, resumeTime, !!state.playing);
                            return;  // still hits the finally block
                        }
                        if (state.playing && videoEl.paused) {
                            videoEl.play().catch(function() {});
                        } else if (!state.playing && !videoEl.paused) {
                            videoEl.pause();
                        }
                        if (typeof state.currentTime === 'number' && !_swapInProgress) {
                            var drift = Math.abs(videoEl.currentTime - state.currentTime);
                            var lockAge = Date.now() - _localSeekAt;
                            var locked = _localSeekPos >= 0 && lockAge < 3000;
                            var converged = locked && Math.abs(state.currentTime - _localSeekPos) < 5;
                            if (converged) _localSeekPos = -1;
                            if (drift > 3 && (!locked || converged)) {
                                videoEl.currentTime = state.currentTime;
                            }
                        }
                    } finally {
                        _applyingServerState = false;
                    }
                },
                onCommand: function(msg) {
                    console.log('[VideojsAdapter onCommand]', msg.type, msg.payload);
                    var cmd = msg;
                    if (msg && msg.type === 'command' && msg.payload) {
                        cmd = { type: msg.payload.commandType, payload: msg.payload.commandPayload || {} };
                    }
                    var ctype = cmd.type;
                    if (!vjsPlayer || _destroyed) return;
                    if (ctype === 'select-subtitle') {
                        var idx = cmd.payload && cmd.payload.index;
                        if (idx == null) return;
                        var maxAttempts = 25;
                        var attempt = 0;
                        function trySelect() {
                            if (_destroyed) return;
                            var tracks = vjsPlayer.textTracks();
                            if (tracks && tracks.length > 0) {
                                if (idx === -1) { for (var i=0;i<tracks.length;i++){ tracks[i].mode='hidden'; } return; }
                                for (var i = 0; i < tracks.length; i++) {
                                    if (i === idx) {
                                        tracks[i].mode = 'showing';
                                    } else {
                                        tracks[i].mode = 'hidden';
                                    }
                                }
                                return;
                            }
                            if (attempt < maxAttempts) {
                                attempt++;
                                setTimeout(trySelect, 200);
                            }
                        }
                        trySelect();
                    } else if (ctype === 'select-audio') {
                        var idx = cmd.payload && cmd.payload.index;
                        if (idx == null) return;
                        var maxAttempts = 25;
                        var attempt = 0;
                        function trySelect() {
                            if (_destroyed) return;
                            var audioTracks = vjsPlayer.audioTracks();
                            if (audioTracks && audioTracks.length > 0) {
                                for (var i = 0; i < audioTracks.length; i++) {
                                    audioTracks[i].enabled = (i === idx);
                                }
                                return;
                            }
                            if (attempt < maxAttempts) {
                                attempt++;
                                setTimeout(trySelect, 200);
                            }
                        }
                        trySelect();
                    } else if (ctype === 'toggle-play') {
                        if (_destroyed || !vjsPlayer) return;
                        if (vjsPlayer.paused()) vjsPlayer.play(); else vjsPlayer.pause();
                    } else if (ctype === 'seek') {
                        if (_destroyed || !vjsPlayer) return;
                        var t = cmd.payload && cmd.payload.value;
                        if (typeof t === 'number' && isFinite(t)) {
                            try { vjsPlayer.currentTime(t); } catch (e) {}
                        }
                    }
                }
            });
            _wsManager.connect();
        }

        function _broadcastState() {
            if (!_wsManager || !_wsManager.connected || _applyingServerState || _swapInProgress || _destroyed || !videoEl) return;
            _wsManager.send('state', {
                currentVideoId: videoId,
                playing: !videoEl.paused,
                currentTime: videoEl.currentTime || 0,
                profileId: (pid ? Number(pid) : null)
            });
        }

        function _performSwapLoad(newUrl, newType, resumeTime, shouldPlay) {
            // Bind one-time swap listeners BEFORE changing the source so no broadcast window is left open
            if (_swapErrorHandler) { vjsPlayer.off('error', _swapErrorHandler); _swapErrorHandler = null; }
            _swapErrorHandler = function() {
                var err = vjsPlayer.error();
                // MEDIA_ERR_SRC_NOT_SUPPORTED (code 4) is a transient false-positive during probing — playback may still start
                if (err && err.code === 4) return;
                _swapInProgress = false;
                _swapRetries++;
                if (_swapRetries <= 3) {
                    console.warn('[VideoJsAdapter] Swap load failed (' + _swapRetries + '/3), retrying in 1s...');
                    setTimeout(function() {
                        if (_destroyed) return;
                        _swapInProgress = true;
                        var retryUrl = container.dataset.externalUrl || ('/api/video/stream/' + encodeURIComponent(videoId) + '.mp4?trace=' + Date.now());
                        _performSwapLoad(retryUrl, retryUrl.includes('.m3u8') ? 'application/x-mpegURL' : 'video/mp4', resumeTime, shouldPlay);
                    }, 1000);
                } else {
                    console.error('[VideoJsAdapter] Swap load failed after 3 retries');
                    if (window.Toast) window.Toast.error('Failed to load video');
                }
            };
            vjsPlayer.on('error', _swapErrorHandler);
            vjsPlayer.one('loadedmetadata', function() {
                if (resumeTime > 0) vjsPlayer.currentTime(resumeTime);
            });
            vjsPlayer.one('playing', function() {
                _swapInProgress = false;
                if (_swapErrorHandler) { vjsPlayer.off('error', _swapErrorHandler); _swapErrorHandler = null; }
                _broadcastState();  // one confirmation: restarts the server playback timer with the new id
            });
            vjsPlayer.one('loadeddata', function() {
                _swapInProgress = false;
                if (_swapErrorHandler) { vjsPlayer.off('error', _swapErrorHandler); _swapErrorHandler = null; }
            });
            _swapTimeout = setTimeout(function() {
                _swapInProgress = false;
                if (_swapErrorHandler) { vjsPlayer.off('error', _swapErrorHandler); _swapErrorHandler = null; }
            }, 10000);
            vjsPlayer.src({ src: newUrl, type: newType });
            // Restore volume/mute
            var savedVol = Math.pow(parseFloat(localStorage.getItem(volumeKey) || '0.7'), 2);
            var savedMute = localStorage.getItem(muteKey) === 'true';
            vjsPlayer.volume(savedVol);
            vjsPlayer.muted(savedMute);
            if (shouldPlay) {
                vjsPlayer.play().catch(function() {});
            }
        }

        /* ---------- Video.js Events ---------- */
        vjsPlayer.on('play', function() {
            _broadcastState();
            PlayerUtils?.requestWakeLock?.();
        });

        vjsPlayer.on('playing', function() {
            _reportStreamStatus('working');
        });

        vjsPlayer.on('pause', function() {
            PlayerUtils?.releaseWakeLock?.();
            _broadcastState();
        });

        vjsPlayer.on('timeupdate', function() {
            _broadcastState();
        });

        vjsPlayer.on('seeked', function() {
            _localSeekAt = Date.now();
            _localSeekPos = vjsPlayer.currentTime() || 0;
            _broadcastState();
            setTimeout(function() { _broadcastState(); }, 150);
        });

        vjsPlayer.on('ended', function() {
            PlayerUtils?.releaseWakeLock?.();
        });

        vjsPlayer.on('error', function() {
            var err = vjsPlayer.error();
            // MEDIA_ERR_SRC_NOT_SUPPORTED (code 4) is a transient false-positive
            // with streaming/fragmented MP4 sources. The backend responds to
            // byte-0 probes with placeholder bytes ([0,0]) before real data is
            // available. The native <video> element fires this error during
            // initial probing but recovers once actual data flows. Don't treat
            // it as fatal — playback will likely start moments later.
            if (err && err.code === 4) {
                console.warn('[VideoJsAdapter] Source not supported warning (playback may start after stream initializes):', err.message);
                return; // keep wake lock — playback may still start
            }
            PlayerUtils?.releaseWakeLock?.();
            console.error('[VideoJsAdapter] Player error:', err);
            _reportStreamStatus('dead');
        });

        /* ---------- Volume persistence with exponential curve (matching JMedia default player) ---------- */
        vjsPlayer.on('volumechange', function() {
            var rawVol = vjsPlayer.volume();
            var sliderPos = Math.pow(Math.max(rawVol, 0), 1/2);
            localStorage.setItem(volumeKey, sliderPos);
            localStorage.setItem(muteKey, vjsPlayer.muted());
        });

        /* ---------- Build adapter for TestPlayerFeatures ---------- */
        var nativeVideo = videoEl;
        var vjsAdapter = {
            getVideoElement: function() { return nativeVideo; },
            getCurrentTime: function() { return vjsPlayer.currentTime(); },
            setCurrentTime: function(t) { vjsPlayer.currentTime(t); },
            getDuration: function() { return vjsPlayer.duration(); },
            isPaused: function() { return vjsPlayer.paused(); },
            play: function() { vjsPlayer.play(); },
            pause: function() { vjsPlayer.pause(); },
            getVolume: function() { return vjsPlayer.volume(); },
            setVolume: function(v) { vjsPlayer.volume(v); },
            isMuted: function() { return vjsPlayer.muted(); },
            setMuted: function(m) { vjsPlayer.muted(m); },
            getPlaybackRate: function() { return vjsPlayer.playbackRate(); },
            setPlaybackRate: function(r) { vjsPlayer.playbackRate(r); },
            on: function(event, cb) { vjsPlayer.on(event, cb); },
            off: function(event, cb) { vjsPlayer.off(event, cb); },
            getVideoSrc: function() { return vjsPlayer.currentSrc() || nativeVideo.src; },
            setVideoSrc: function(url) { vjsPlayer.src(url); },
            requestFullscreen: function() {
                var c = document.getElementById('customPlayer');
                if (c.requestFullscreen) c.requestFullscreen();
                else if (c.webkitRequestFullscreen) c.webkitRequestFullscreen();
            }
        };

        /* ---------- Initialize backend features ---------- */
        if (window.TestPlayerFeatures) {
            window.testPlayerFeatures = new window.TestPlayerFeatures(videoId, vjsAdapter);
        }

        /* ---------- Initial state ---------- */
        container.dataset.videoId = videoId;
        PlayerUtils?.requestWakeLock?.();

        console.log('[VideoJsAdapter] Initialized with videoId:', videoId);

        /* Re-acquire wake lock when page becomes visible again and video is playing */
        document.addEventListener('visibilitychange', function() {
            if (document.visibilityState === 'visible' && !vjsPlayer.paused()) {
                PlayerUtils?.requestWakeLock?.();
            }
        });

        window.destroyVideoJsAdapter = function() {
            _destroyed = true;
            // Send a FINAL state report so the server stops its playback timer when the player is torn down
            if (_wsManager && _wsManager.connected) {
                try {
                    _wsManager.send('state', {
                        currentVideoId: videoId,
                        playing: false,
                        currentTime: videoEl.currentTime || 0,
                        profileId: (pid ? Number(pid) : null)
                    });
                } catch (e) {}
            }
            if (_wsManager) { _wsManager.disconnect(); _wsManager = null; }
            if (vjsPlayer && typeof vjsPlayer.dispose === 'function') { vjsPlayer.dispose(); }
        };
    };
})();
