(function() {
    'use strict';

    /* ---------- iOS touch-compatible event binding ---------- *
     * On iOS Safari, click events can fail to fire on elements
     * positioned over a <video> element because iOS consumes
     * touch events for its native video controls.  Using touchend
     * with preventDefault() suppresses the delayed synthetic
     * click, avoiding double-fire.  On desktop only the click
     * fires, so the behaviour is transparent everywhere.           */
    function onTap(el, handler) {
        el.addEventListener('touchend', function(e) {
            e.preventDefault();               /* Prevent the delayed synthetic click on iOS */
            handler(e);
        });
        el.addEventListener('click', handler);
    }

    window.initOPlayerAdapter = function(videoId) {
        if (!videoId) {
            var container = document.getElementById('customPlayer');
            if (container) videoId = container.dataset.videoId;
        }
        if (!videoId) {
            console.error('[OPlayerAdapter] No videoId provided');
            return;
        }

        /* ---------- DOM refs (only custom overlay elements) ---------- */
        var container       = document.getElementById('customPlayer');
        var oplayerContainer= document.getElementById('oplayerContainer');
        var subtitleMenu    = document.getElementById('subtitleMenu');
        var assCanvas       = document.getElementById('assCanvas');
        var settingsToggleBtn = document.getElementById('settingsToggleBtn');

        if (!oplayerContainer) {
            console.warn('[OPlayerAdapter] #oplayerContainer not found; cannot init OPlayer');
            return;
        }

        /* ---------- Build stream URL ---------- */
        var startTime = parseFloat(container.dataset.startTime || '0');
        /* Direct-streamed files (no transcode AND no conversion) are served raw by the
         * backend with full byte-range support — the server IGNORES ?start= on that
         * path (streamDirectFile has no start param). Baking ?start= for them restarts
         * the element from byte 0 while the offset claims otherwise, so resume must be
         * a native post-load seek instead (mirror SimplePlayer's needsTranscode branch). */
        var _isDirectFile = !container.dataset.externalUrl && container.dataset.needsTranscode !== 'true' && container.dataset.needsConversion !== 'true';
        var streamUrl = _withNativeHevc(container.dataset.externalUrl || ('/api/video/stream/' + encodeURIComponent(videoId) + '.mp4' + (startTime > 0 && !_isDirectFile ? '?start=' + startTime : '')));

        /* DB duration in seconds (data-duration is milliseconds, native-player
         * convention — same >5000 guard as StateManager) or NaN when unknown
         * (live/never-scanned); callers then fall back to the element value. */
        function _knownDuration() {
            var rawDur = parseFloat(container.dataset.duration || 0);
            var d = rawDur > 5000 ? rawDur / 1000 : rawDur;
            return isFinite(d) && d > 0 ? d : NaN;
        }

        var profileId = localStorage.getItem('activeProfileId') || '1';
        var volumeKey = 'jmedia_video_volume_' + profileId;
        var muteKey = 'jmedia_video_mute_' + profileId;

        /* Native-HEVC remux override: when the backend flags a server-side transcode
         * (data-needs-transcode, e.g. HEVC on a platform the server would re-encode
         * for) but the browser can play HEVC natively, request the lightweight remux
         * instead of the transcode. */
        function _nativeHevcParam() {
            var needsTranscode = container.dataset.needsTranscode === 'true';
            var needsConversion = container.dataset.needsConversion === 'true';
            if (!(needsTranscode || needsConversion)) return '';
            var codec = (container.dataset.videoCodec || '').toLowerCase();
            if (codec.indexOf('hevc') === -1 && codec.indexOf('h265') === -1) return '';
            if (!(window.PlayerStreamManager && window.PlayerStreamManager.hasNativeHevcSupport())) return '';
            return 'nativeHevc=1';
        }

        function _withNativeHevc(url) {
            var param = _nativeHevcParam();
            if (!param || url.indexOf('/api/video/stream/') !== 0) return url;
            return url.indexOf('?') !== -1 ? url + '&' + param : url + '?' + param;
        }

        var player = null;
        var video = null;
        var _destroyed = false;
        var _wsManager = null;
        var _controlsTimer = null;

        /* ---------- In-place source-swap hardening (per-instance, never global - B9) ----------
         * While a remote source swap is in progress, suppress stale broadcasts and the
         * drift-seek so residual timeupdate/play/pause events from the OLD element cannot
         * feed stale time/playing for the NEW video to the server (D3, F2). */
        var _swapInProgress = false;
        var _swapToken = 0;
        var _swapRetries = 0;
        var _swapTimeout = null;
        var _swapHandlers = null;
        /* Echo guard (mirrors SimplePlayer): set while applying server-commanded
         * play/pause so the resulting 'play'/'pause' events do not broadcast back
         * to the server. Without it, the server's 300ms playing=true broadcast
         * force-plays and the echo re-confirms it, defeating a local pause. */
        var _applyingServerState = false;
        /* Local action is truth: after a local seek the server may not override the
           player until it echoes the position we reported back to us (echo-lock). */
        var _localSeekAt = 0;
        var _localSeekPos = -1;
        /* Absolute stream time base: the server's ?start=N remux emits RELATIVE
         * timestamps (ffmpeg -ss input seek, no -copyts), so element time = 0 at
         * the seek position. All server-facing times (WS seek, broadcasts,
         * drift-sync) and the shadowed player.currentTime are ABSOLUTE =
         * element time + this offset, mirroring SimplePlayer's streamStartOffset.
         * Every seek is routed through _serverSeekTo(): in-buffer targets seek the
         * element natively; targets past the buffered (not-yet-transcoded) frontier
         * restart the stream with ?start=<abs> instead of issuing a byte-range
         * request into the still-growing transcode cache. */
        var _streamStartOffset = (startTime > 0 && !_isDirectFile && !container.dataset.externalUrl) ? startTime : 0;
        var _currentQuality = 0;
        /* Currently selected native subtitle index (-1 = off) and the track list it
         * refers to. Used to re-apply the subtitle source after a ?start= server seek
         * so native subtitles track the new stream offset (mirror SimplePlayer's
         * subtitle reload in StreamManager.performServerSeek). */
        var _subtitleIdx = -1;
        var _subtitleTracks = [];

        function _clearSwapListeners() {
            if (_swapTimeout) { clearTimeout(_swapTimeout); _swapTimeout = null; }
            if (_swapHandlers && video) {
                if (_swapHandlers.playing) video.removeEventListener('playing', _swapHandlers.playing);
                if (_swapHandlers.loadeddata) video.removeEventListener('loadeddata', _swapHandlers.loadeddata);
                if (_swapHandlers.error) video.removeEventListener('error', _swapHandlers.error);
            }
            _swapHandlers = null;
        }

        function _endSwap(confirmBroadcast) {
            _swapToken++;
            _swapInProgress = false;
            _swapRetries = 0;
            _clearSwapListeners();
            /* B6: on `playing` the swap sends ONE confirmation broadcast, which restarts
             * the server playback timer with the new videoId. */
            if (confirmBroadcast) _broadcastState();
        }

        function _buildStreamUrl(startAbs, quality) {
            /* Direct-streamed files ignore ?start= (server has no start param on the
             * direct path), so never bake it — a native seek after load handles resume. */
            var url = '/api/video/stream/' + encodeURIComponent(videoId) + '.mp4' + (_isDirectFile ? '' : '?start=' + Math.max(0, startAbs));
            if (quality && quality > 0) url += (_isDirectFile ? '?' : '&') + 'quality=' + quality;
            return _withNativeHevc(url);
        }

        /* Shared native-subtitle applier: rebuilds the OPlayer subtitle source list
         * for the given index. The track URL carries ?start= so server-shifted cues
         * align with the element clock (element 0 == absolute _streamStartOffset),
         * mirroring SimplePlayer's SubtitleController. Re-invoked after every
         * ?start= server seek with the new offset. */
        function _applySubtitle(idx) {
            if (idx === -1) {
                _subtitleIdx = -1;
                return;
            }
            var oplayer = window.__oplayerPlayer;
            if (!oplayer || !oplayer.context || !oplayer.context.ui || !oplayer.context.ui.subtitle) {
                return false;
            }
            try {
                if (!_subtitleTracks || !_subtitleTracks[idx]) return false;
                var offset = (_streamStartOffset > 0) ? '?start=' + Math.max(0, _streamStartOffset) : '';
                var oplayerSubs = _subtitleTracks.map(function (t) {
                    return {
                        name: t.displayName || t.filename || ('Track ' + t.id),
                        src: '/api/video/subtitles/track/' + t.id + offset,
                        default: (t.id === _subtitleTracks[idx].id)
                    };
                });
                oplayer.context.ui.subtitle.changeSource(oplayerSubs);
                _subtitleIdx = idx;
                return true;
            } catch (e) {
                console.error('[OplayerAdapter] OPlayer subtitle changeSource failed:', e);
                return true;
            }
        }

        /* Route every seek through here. In-buffer targets seek the element natively
         * (relative = absolute - offset); targets past the buffered frontier restart
         * the transcode with ?start=<abs> — the browser cannot byte-range into the
         * still-growing cache, so a native seek there would hang (B13). Targets
         * BEHIND the current window start (absTarget < _streamStartOffset) also
         * reload: relTarget clamps to 0, which is the window start — a native seek
         * there would land at the window start, not the requested position. */
        function _serverSeekTo(absTarget, forceReload) {
            if (_destroyed || !video) return;
            var relTarget = Math.max(0, absTarget - _streamStartOffset);
            /* Direct-streamed files are byte-range seekable at ANY position (the
             * backend serves them raw and ignores ?start=), so a native element seek
             * is always correct — past-buffer and forceReload included. */
            if (_isDirectFile) {
                try { video.currentTime = relTarget; } catch (e) {}
                if (video.paused) { try { video.play().catch(function() {}); } catch (e) {} }
                return;
            }
            var bufferedEnd = 0;
            try {
                if (video.buffered.length > 0) bufferedEnd = video.buffered.end(video.buffered.length - 1);
            } catch (e) {}
            if (!forceReload && (container.dataset.externalUrl || (absTarget >= _streamStartOffset && relTarget <= bufferedEnd + 1))) {
                /* External direct-file URLs support byte-range seeks natively, so they
                 * never need a ?start= transcode restart (their offset is always 0).
                 * Backward targets behind the window start (absTarget < offset) fall
                 * through to the ?start= reload below — relTarget would clamp to 0,
                 * landing at the window start instead of the requested position. */
                try { video.currentTime = relTarget; } catch (e) {}
                if (video.paused) { try { video.play().catch(function() {}); } catch (e) {} }
                return;
            }

            /* Past buffer: server-side seek. Mirror _doServerSeek: suppress broadcasts
             * and drift-sync during the reload window so a stale server position cannot
             * seek-yank the fresh element back (the element sits at 0 relative while
             * the server still reports the OLD absolute position). */
            _swapToken++;
            var swapToken = _swapToken;
            _swapInProgress = true;
            _swapRetries = 0;
            _clearSwapListeners();

            _streamStartOffset = Math.max(0, absTarget);
            var url = _buildStreamUrl(absTarget, _currentQuality);

            try { video.pause(); } catch (e) {}
            video.src = "";
            try { video.load(); } catch (e) {}

            /* Suppression exit: `playing` sends ONE confirmation broadcast (B6) that
             * restarts the server playback timer at the new absolute position.
             * `error` and the 10s timeout end the swap; `loadeddata` does NOT lower
             * the swap guard. It fires before `playing`, and the server may still
             * broadcast the OLD absolute position — for a BACKWARD seek that stale
             * position is ABOVE the new offset, so the one-directional stale guard
             * passes and drift-sync would yank the fresh element forward, undoing
             * the seek. The guard stays up until `playing` confirms the new position
             * (mirror _doServerSeek). */
            var handlers = {
                playing: function() {
                    if (swapToken === _swapToken) {
                        _endSwap(true);
                        /* Native subtitles are aligned to the element clock, which
                         * restarts at 0 relative to the new ?start= — re-apply the
                         * selected track with the new offset so cues stay aligned
                         * (mirror SimplePlayer's subtitle reload after server seek). */
                        if (_subtitleIdx >= 0) _applySubtitle(_subtitleIdx);
                    }
                },
                error: function() { if (swapToken === _swapToken) _swapInProgress = false; }
            };
            video.addEventListener('playing', handlers.playing);
            video.addEventListener('error', handlers.error);
            _swapHandlers = handlers;
            _swapTimeout = setTimeout(function() {
                if (swapToken === _swapToken) _endSwap(false);
            }, 10000);

            video.src = url;
            try { video.load(); } catch (e) {}
            try { video.play().catch(function() {}); } catch (e) {}
        }

        /* ---------- Idle timer: auto-hide custom controls ---------- */
        function showControls() {
            if (_destroyed) return;
            container.classList.remove('controls-hidden');
            if (_controlsTimer) {
                clearTimeout(_controlsTimer);
                _controlsTimer = null;
            }
            /* Only auto-hide when video is playing */
            if (video && !video.paused) {
                _controlsTimer = setTimeout(function() {
                    if (_destroyed) return;
                    container.classList.add('controls-hidden');
                }, 3000);
            }
        }

        function hideControls() {
            if (_destroyed) return;
            if (_controlsTimer) {
                clearTimeout(_controlsTimer);
                _controlsTimer = null;
            }
            container.classList.add('controls-hidden');
        }

        container.addEventListener('mousemove', showControls);
        container.addEventListener('touchstart', showControls);
        container.addEventListener('keydown', showControls);
        container.addEventListener('mouseleave', function() {
            if (video && !video.paused) hideControls();
        });

        /* ---------- Settings Menu Navigation ---------- */
        onTap(subtitleMenu, function(e) {
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
                    var profileId = localStorage.getItem('activeProfileId') || '1';
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
            if (qualityBtn && video) {
                e.stopPropagation();
                var quality = parseInt(qualityBtn.dataset.quality);
                var label = qualityBtn.textContent.trim();
                subtitleMenu.querySelectorAll('.quality-btn').forEach(function(b) { b.classList.remove('active'); });
                qualityBtn.classList.add('active');
                if (window.Toast) window.Toast.info('Quality: ' + label);

                var currentTime = (video.currentTime || 0) + _streamStartOffset;
                _currentQuality = quality;
                _serverSeekTo(currentTime, true);
                return;
            }
        });

        /* ---------- Settings toggle ---------- */
        onTap(settingsToggleBtn, function(e) {
            e.stopPropagation();
            subtitleMenu.classList.toggle('active');
        });

        /* Close menu when tapping/clicking outside */
        function closeMenuIfOpen(e) {
            if (subtitleMenu.classList.contains('active') &&
                !subtitleMenu.contains(e.target) &&
                e.target !== settingsToggleBtn &&
                !settingsToggleBtn.contains(e.target)) {
                subtitleMenu.classList.remove('active');
            }
        }
        document.addEventListener('click', closeMenuIfOpen);
        var _onTouchEnd = function(e) {
            /* Only close, never preventDefault — must not break scrolling */
            closeMenuIfOpen(e);
        };
        document.addEventListener('touchend', _onTouchEnd, { passive: true });

        /* ---------- Subtitle Timing Offset ---------- */
        (function() {
            var subMinus = document.getElementById('subMinusBtn');
            if (subMinus) onTap(subMinus, function(e) {
                e.stopPropagation();
                if (window.subtitleManager) window.subtitleManager.adjustCorrection(-0.2);
                if (window.testPlayerFeatures && window.testPlayerFeatures.loadSubtitles) {
                    window.testPlayerFeatures.loadSubtitles(true);
                }
            });
            var subPlus = document.getElementById('subPlusBtn');
            if (subPlus) onTap(subPlus, function(e) {
                e.stopPropagation();
                if (window.subtitleManager) window.subtitleManager.adjustCorrection(0.2);
                if (window.testPlayerFeatures && window.testPlayerFeatures.loadSubtitles) {
                    window.testPlayerFeatures.loadSubtitles(true);
                }
            });
            var subReset = document.getElementById('subResetBtn');
            if (subReset) onTap(subReset, function(e) {
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
                onTap(correctionVal, function(e) {
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
        })();

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
            if (isIOS && video) {
                try {
                    if (video.requestFullscreen) {
                        video.requestFullscreen();
                    } else if (video.webkitEnterFullscreen) {
                        video.webkitEnterFullscreen();
                    } else {
                        enterCssFullscreen();
                    }
                } catch (err) {
                    console.warn('[OPlayerAdapter] iOS fullscreen failed:', err);
                    enterCssFullscreen();
                }
            } else {
                if (container.requestFullscreen) {
                    container.requestFullscreen().catch(function() {
                        console.warn('[OPlayerAdapter] Fullscreen denied, using CSS fallback');
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

        document.addEventListener('fullscreenchange', syncFSIcon);
        document.addEventListener('webkitfullscreenchange', syncFSIcon);

        function syncFSIcon() {
            if (_isCssFS) return;
            var isFS = document.fullscreenElement || document.webkitFullscreenElement;
            container.classList.toggle('is-fullscreen', !!isFS);
        }

        /* ---------- Back button (if present) ---------- */
        var oBackBtn = document.getElementById('backBtn');
        if (oBackBtn) onTap(oBackBtn, function() { history.back(); });

        /* ---------- Keyboard shortcuts ---------- */
        /* Use capture phase so shortcuts work even when OPlayer UI has focus
         * and would otherwise consume keyboard events during bubbling.         */
        var _onKeydown = function(e) {
            if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;
            switch (e.key) {
                case ' ':
                case 'k': e.preventDefault(); if (video) { if (video.paused) video.play().catch(function() {}); else video.pause(); } break;
                case 'f': e.preventDefault(); toggleFullscreen(); break;
                case 'm': e.preventDefault(); if (video) { video.muted = !video.muted; } break;
                case 'ArrowLeft': e.preventDefault(); if (video) { _serverSeekTo((video.currentTime || 0) + _streamStartOffset - 15); } break;
                case 'ArrowRight': e.preventDefault(); if (video) { var absDur = _knownDuration(); if (!isFinite(absDur)) absDur = (video.duration || 0) + _streamStartOffset; _serverSeekTo(Math.min(absDur, (video.currentTime || 0) + _streamStartOffset + 15)); } break;
                case 'ArrowUp':
                    e.preventDefault();
                    if (video) { video.volume = Math.min(1, (video.volume || 0) + 0.1); }
                    break;
                case 'ArrowDown':
                    e.preventDefault();
                    if (video) { video.volume = Math.max(0, (video.volume || 0) - 0.1); }
                    break;
            }
        };
        document.addEventListener('keydown', _onKeydown, true);

        /* ---------- WebSocket state sync ---------- */
        function _initWebSocket() {
            if (!window.VideoWebSocketManager) return;
            var pid = localStorage.getItem('activeProfileId');
            if (!pid) return;
            _wsManager = new window.VideoWebSocketManager({
                profileId: pid,
                onCommand: function(msg) {
                    console.log('[OplayerAdapter onCommand]', msg.type, msg.payload);
                    var cmd = msg;
                    if (msg && msg.type === 'command' && msg.payload) {
                        cmd = { type: msg.payload.commandType, payload: msg.payload.commandPayload || {} };
                    }
                    var ctype = cmd.type;
                    if (_destroyed) return;

                    if (ctype === 'select-subtitle') {
                        var idx = cmd.payload && cmd.payload.index;
                        if (idx == null) return;

                        if (idx === -1) {
                            _subtitleIdx = -1;
                            _subtitleTracks = [];
                        }
                        var tracks = (window.testPlayerFeatures && window.testPlayerFeatures._subtitleTracks) || [];
                        if (idx !== -1 && tracks && tracks[idx]) _subtitleTracks = tracks;

                        var maxAttempts = 25, attempt = 0;
                        (function tryApply() {
                            if (_destroyed) return;
                            if (_applySubtitle(idx)) return;
                            if (attempt < maxAttempts) {
                                attempt++;
                                setTimeout(tryApply, 200);
                            } else {
                                console.warn('[OplayerAdapter] Subtitle apply timed out (OPlayer/tracks not ready)');
                                if (video && video.textTracks && video.textTracks.length > 0) {
                                    if (idx === -1) {
                                        for (var z = 0; z < video.textTracks.length; z++) video.textTracks[z].mode = 'hidden';
                                        return;
                                    }
                                    for (var i = 0; i < video.textTracks.length; i++) {
                                        if (video.textTracks[i].kind === 'subtitles' || video.textTracks[i].kind === 'captions') {
                                            video.textTracks[i].mode = (i === idx) ? 'showing' : 'hidden';
                                        }
                                    }
                                }
                            }
                        })();

                    } else if (ctype === 'select-audio') {
                        var idx = cmd.payload && cmd.payload.index;
                        if (idx == null) return;

                        var maxAttempts = 25;
                        var attempt = 0;
                        function trySelect() {
                            if (_destroyed) return;

                            if (video && video.audioTracks && video.audioTracks.length > 0) {
                                if (video.audioTracks[idx]) {
                                    for (var i = 0; i < video.audioTracks.length; i++) {
                                        video.audioTracks[i].enabled = (i === idx);
                                    }
                                    return;
                                }
                            }

                            if (attempt < maxAttempts) {
                                attempt++;
                                setTimeout(trySelect, 200);
                            }
                        }
                        trySelect();

                    } else if (ctype === 'toggle-play') {
                        if (_destroyed || !video) return;
                        if (video.paused) { if (video.play) video.play().catch(function() {}); } else { if (video.pause) video.pause(); }
                    } else if (ctype === 'seek') {
                        if (_destroyed || !video) return;
                        var t = cmd.payload && cmd.payload.value;
                        if (typeof t === 'number' && isFinite(t)) {
                            _serverSeekTo(t);
                        }
                    }
                },
                onStateUpdate: function(state) {
                    if (!state || _destroyed || !video) return;
                    _applyingServerState = true;
                    try {
                    if (state.currentVideoId && state.currentVideoId.toString() !== videoId.toString()) {
                        var newId = state.currentVideoId;
                        var vEl = (player && player.$video) ? player.$video : video;

                        /* Open the swap window BEFORE touching the source: residual
                         * timeupdate/play/pause from the old element must not broadcast
                         * stale time under the new id, and the drift-check must not yank
                         * the new element while it still sits at 0. */
                        _swapToken++;
                        var swapToken = _swapToken;
                        _swapInProgress = true;
                        _swapRetries = 0;
                        _clearSwapListeners();

                        /* F5: the NEW episode starts from ITS server state, never from the
                         * old element's position. selectVideo (resume) broadcasts
                         * currentTime > 0 (VideoController.selectVideo); advanceVideo
                         * (auto-next) sets 0 -> omit the start param so it starts at 0. */
                        var startAbs = (typeof state.currentTime === 'number' && state.currentTime > 0) ? Math.floor(state.currentTime) : 0;
                        /* Direct-streamed files ignore ?start=: load without the param and
                         * restore the position with a native seek (loadeddata handler below). */
                        var startParam = (!_isDirectFile && startAbs > 0) ? 'start=' + startAbs + '&' : '';
                        var url = _withNativeHevc('/api/video/stream/' + encodeURIComponent(newId) + '.mp4?' + startParam + 'trace=' + Date.now());
                        /* The new stream's timestamps restart at 0 relative to the baked
                         * ?start= param, so the element clock 0 == absolute startAbs.
                         * Direct-streamed files have no baked param: offset stays 0 and
                         * the element clock is absolute from byte 0. */
                        _streamStartOffset = _isDirectFile ? 0 : startAbs;

                        function loadSwapSource() {
                            vEl.src = url;
                            try { vEl.load(); } catch (e) {}
                            /* Restore volume/mute from localStorage (mirror SimplePlayer swap) */
                            try {
                                vEl.volume = Math.pow(parseFloat(localStorage.getItem(volumeKey) || '0.7'), 2);
                                vEl.muted = localStorage.getItem(muteKey) === 'true';
                            } catch (e) {}
                            if (state.playing) { try { vEl.play().catch(function() {}); } catch (e) {} }
                        }
                        loadSwapSource();

                        videoId = newId;
                        console.log('[OPlayerAdapter] Remote source swap ->', newId);

                        /* F3 post-swap cleanup: keep downstream UI (episode sidebar,
                         * breadcrumbs) reading the new episode. videoId is known here;
                         * title/type/duration are refreshed from the metadata API because
                         * the WS state payload does not carry them. */
                        container.dataset.videoId = newId;
                        try {
                            fetch('/api/video/' + encodeURIComponent(newId))
                                .then(function(r) { return r.ok ? r.json() : null; })
                                .then(function(meta) {
                                    if (!meta || _destroyed || videoId !== newId) return;
                                    if (meta.title) container.dataset.title = meta.title;
                                    if (meta.type) container.dataset.type = meta.type;
                                    if (typeof meta.duration === 'number' && meta.duration > 0) container.dataset.duration = String(meta.duration);
                                    if (meta.seriesTitle) container.dataset.seriesTitle = meta.seriesTitle;
                                    if (typeof meta.seasonNumber === 'number') container.dataset.seasonNumber = String(meta.seasonNumber);
                                    if (typeof meta.episodeNumber === 'number') container.dataset.episodeNumber = String(meta.episodeNumber);
                                })
                                .catch(function() { /* best-effort metadata refresh */ });
                        } catch (e) {}

                        /* Suppression exit: clear the swap window on the new source's
                         * `playing` (with ONE confirmation broadcast that restarts the
                         * server timer). `loadeddata` fires BEFORE `playing`, so it must
                         * only lower the swap guard — ending the swap there would remove
                         * the playing listener and the confirmation broadcast would never
                         * fire (server keeps the OLD position, drift-yanks the new
                         * element back every 3s). A 10s safety timeout always ends it.
                         * Only events from the current swap count. */
                        var handlers = {
                            playing: function() {
                                if (swapToken !== _swapToken) return;
                                _endSwap(true);
                            },
                            loadeddata: function() {
                                if (swapToken !== _swapToken) return;
                                _swapInProgress = false;
                                /* Direct-streamed files have no baked ?start=, so restore
                                 * the resume position with a native byte-range seek. */
                                if (_isDirectFile && startAbs > 0) { try { vEl.currentTime = startAbs; } catch (e) {} }
                            },
                            error: function() {
                                if (swapToken !== _swapToken) return;
                                _swapRetries++;
                                if (_swapRetries <= 3) {
                                    /* Clear the suppression window on error, log, and retry
                                     * the swap load with 1s backoff (max 3). Handlers stay
                                     * bound so a successful retry still fires the
                                     * confirmation broadcast. */
                                    console.warn('[OPlayerAdapter] Swap source error; retry ' + _swapRetries + '/3');
                                    _swapInProgress = false;
                                    setTimeout(loadSwapSource, 1000);
                                } else {
                                    console.error('[OPlayerAdapter] Swap source failed after 3 retries');
                                    _endSwap(false);
                                    showPlayerLoadError();
                                }
                            }
                        };
                        vEl.addEventListener('playing', handlers.playing);
                        vEl.addEventListener('loadeddata', handlers.loadeddata);
                        vEl.addEventListener('error', handlers.error);
                        _swapHandlers = handlers;
                        _swapTimeout = setTimeout(function() {
                            if (swapToken !== _swapToken) return;
                            console.warn('[OPlayerAdapter] Swap window timed out; ending suppression');
                            _endSwap(false);
                        }, 10000);

                        return;
                    }
                        if (typeof state.playing === 'boolean') {
                            if (state.playing && video.paused) { try { video.play().catch(function() {}); } catch (e) {} }
                            else if (!state.playing && !video.paused) { try { video.pause(); } catch (e) {} }
                        }
                        if (typeof state.currentTime === 'number' && !_swapInProgress) {
                            /* Bound the drift-seek to the loaded duration: the server's
                             * 300ms timer inflates currentTime while a slow stream is
                             * still buffering; seeking a data-less element past its end
                             * fires a spurious 'ended', cascading to the next episode. */
                            /* Server currentTime is ABSOLUTE; the element clock restarts
                             * at 0 on each ?start= reload, so translate to element time
                             * before clamping and drift-comparing (mirrors _streamStartOffset). */
                            var d = video.duration;
                            var relTarget = Math.max(0, state.currentTime - _streamStartOffset);
                            if (isFinite(d) && d > 0 && relTarget > d - 1) relTarget = d - 1;
                            var drift = Math.abs((video.currentTime || 0) - relTarget);
                            var lockAge = Date.now() - _localSeekAt;
                            var locked = _localSeekPos >= 0 && lockAge < 3000;
                            var converged = locked && Math.abs(state.currentTime - _localSeekPos) < 5;
                            if (converged) _localSeekPos = -1;
                            /* Stale-server guard: after a ?start= reload the element clock
                             * restarts at 0 while the server may still broadcast the OLD
                             * absolute position (state < offset) -> relTarget clamps to 0
                             * and the fresh element would be seek-yanked back to the start
                             * every 3s. The reloaded element is authoritative until the
                             * server's phantom clock catches up (B6 confirmation or the
                             * next server-side seek). */
                            if (drift > 3 && state.currentTime >= _streamStartOffset && (!locked || converged)) {
                                try { video.currentTime = relTarget; } catch (e) {}
                            }
                        }
                    } catch (e) {
                        console.error('[OplayerAdapter] onStateUpdate error', e);
                    } finally {
                        _applyingServerState = false;
                    }
                }
            });
            _wsManager.connect();
        }

        /* ---------- Initialize OPlayer ---------- */
        var INIT_MAX_ATTEMPTS = 25;      /* 25 × 200ms ≈ 5s bounded wait for local script eval */
        var _initAttempts = 0;

        function removePlayerLoadError() {
            var errEl = document.getElementById('oplayerLoadError');
            if (errEl && errEl.parentNode) errEl.parentNode.removeChild(errEl);
        }

        function requestPlayerFallback() {
            if (_destroyed) return;
            /* Hook for the fallback chain (separate task). Prefer the window
             * hook when B3 registers it, otherwise emit a DOM CustomEvent. */
            if (typeof window.requestPlayerFallback === 'function') {
                window.requestPlayerFallback(videoId);
                return;
            }
            window.dispatchEvent(new CustomEvent('oplayer:fallback-requested', {
                detail: { videoId: videoId }
            }));
        }

        function showPlayerLoadError() {
            if (_destroyed) return;
            if (document.getElementById('oplayerLoadError')) return;
            var errEl = document.createElement('div');
            errEl.id = 'oplayerLoadError';
            errEl.style.cssText = 'position:absolute;inset:0;display:flex;flex-direction:column;' +
                'align-items:center;justify-content:center;gap:14px;background:#111;color:#eee;' +
                'z-index:30;text-align:center;padding:16px;font-family:sans-serif;';
            var msg = document.createElement('div');
            msg.textContent = 'Player failed to load';
            msg.style.cssText = 'font-size:16px;font-weight:600;';
            var retryBtn = document.createElement('button');
            retryBtn.type = 'button';
            retryBtn.textContent = 'Retry';
            retryBtn.style.cssText = 'padding:10px 20px;border:none;border-radius:6px;' +
                'background:#3273dc;color:#fff;cursor:pointer;font-size:14px;';
            onTap(retryBtn, function() {
                if (_destroyed) return;
                removePlayerLoadError();
                _initAttempts = 0;
                initPlayer();
            });
            var altBtn = document.createElement('button');
            altBtn.type = 'button';
            altBtn.textContent = 'Try another player';
            altBtn.style.cssText = 'padding:10px 20px;border:1px solid #555;border-radius:6px;' +
                'background:transparent;color:#ddd;cursor:pointer;font-size:14px;';
            onTap(altBtn, requestPlayerFallback);
            errEl.appendChild(msg);
            errEl.appendChild(retryBtn);
            errEl.appendChild(altBtn);
            (oplayerContainer || container).appendChild(errEl);
        }

        function initPlayer() {
            if (_destroyed) return;
            if (typeof OPlayer === 'undefined' || typeof OUI === 'undefined') {
                _initAttempts++;
                if (_initAttempts < INIT_MAX_ATTEMPTS) {
                    setTimeout(initPlayer, 200);
                } else {
                    console.error('[OPlayerAdapter] OPlayer/OUI unavailable after ' + _initAttempts +
                        ' attempts (~' + (INIT_MAX_ATTEMPTS * 200) + 'ms); showing load error');
                    showPlayerLoadError();
                }
                return;
            }
            removePlayerLoadError();

            PlayerUtils?.requestWakeLock?.();

            try {
                var oplayerOptions = {
                    source: {
                        src: streamUrl,
                        format: 'auto'
                    },
                    autoplay: true,
                    muted: localStorage.getItem(muteKey) === 'true',
                    volume: Math.pow(parseFloat(localStorage.getItem(volumeKey) || '0.7'), 2),
                    playbackRate: 1,
                    playsinline: true,
                    preload: 'auto',
                    videoAttr: { 'crossorigin': 'anonymous' }
                };

                    /* Register @oplayer/ui for native controls */
                    if (typeof OUI !== 'undefined') {
                        if (settingsToggleBtn) settingsToggleBtn.style.display = 'none';

                        var oPlugins = [OUI({
                            icons: {
                                next: '<svg style="transform:scale(0.7)" viewBox="0 0 1024 1024"><path d="M743.36 427.52L173.76 119.04A96 96 0 0 0 32 203.52v616.96a96 96 0 0 0 141.76 84.48l569.6-308.48a96 96 0 0 0 0-168.96zM960 96a32 32 0 0 0-32 32v768a32 32 0 0 0 64 0V128a32 32 0 0 0-32-32z"/></svg>',
                                previous: '<svg style="transform:scale(0.7)" viewBox="0 0 1024 1024"><g transform="translate(1024,0) scale(-1,1)"><path d="M743.36 427.52L173.76 119.04A96 96 0 0 0 32 203.52v616.96a96 96 0 0 0 141.76 84.48l569.6-308.48a96 96 0 0 0 0-168.96zM960 96a32 32 0 0 0-32 32v768a32 32 0 0 0 64 0V128a32 32 0 0 0-32-32z"/></g></svg>'
                            },
                            theme: {
                                controller: {
                                    header: false,
                                    coverButton: true
                                }
                            },
                            settings: [
                                'loop',
                                {
                                    key: 'quality',
                                    type: 'selector',
                                    name: 'Video Quality',
                                    icon: '',
                                    children: [
                                        { name: 'Source', value: '0', default: true },
                                        { name: '480p', value: '480' },
                                        { name: '720p', value: '720' },
                                        { name: '1080p', value: '1080' },
                                        { name: '4K', value: '2160' }
                                    ],
                                    onChange: function onChange(_ref) {
                                        var value = _ref.value;
                                        var vid = player && player.$video;
                                        if (!vid) return;
                                        var currentTime = (vid.currentTime || 0) + _streamStartOffset;
                                        _currentQuality = parseInt(value, 10) || 0;
                                        _serverSeekTo(currentTime, true);
                                    }
                                },
                                {
                                    key: 'player',
                                    type: 'selector',
                                    name: 'Video Player',
                                    icon: '',
                                    children: [
                                        { name: 'JMedia Player', value: 'simple' },
                                        { name: 'Video.js', value: 'videojs' },
                                        { name: 'OPlayer', value: 'oplayer', default: true }
                                    ],
                                    onChange: function onChange(_ref2) {
                                        var value = _ref2.value;
                                        if (window.Toast) window.Toast.info('Switching to ' + value + '...');
                                        var profileId = localStorage.getItem('activeProfileId') || '1';
                                        fetch('/api/settings/' + profileId + '/default-player', {
                                            method: 'POST',
                                            headers: { 'Content-Type': 'application/json' },
                                            body: JSON.stringify({ defaultPlayer: value })
                                        }).then(function () {
                                            location.reload();
                                        }).catch(function () {
                                            if (window.Toast) window.Toast.error('Failed to switch player');
                                        });
                                    }
                                },
                                {
                                    key: 'subtitleOffset',
                                    type: 'selector',
                                    name: 'Subtitle Offset',
                                    icon: '',
                                    children: [
                                        { name: '-2s', value: '-2' },
                                        { name: '-1s', value: '-1' },
                                        { name: '-0.5s', value: '-0.5' },
                                        { name: '0', value: '0', default: true },
                                        { name: '+0.5s', value: '0.5' },
                                        { name: '+1s', value: '1' },
                                        { name: '+2s', value: '2' }
                                    ],
                                    onChange: function onChange(_ref3) {
                                        var value = _ref3.value;
                                        var offset = parseFloat(value) || 0;
                                        localStorage.setItem('jmedia_subtitle_correction', offset);
                                        var valEl = document.getElementById('subCorrectionVal');
                                        if (valEl) valEl.textContent = (offset >= 0 ? '+' : '') + offset.toFixed(1) + 's';
                                        if (window.testPlayerFeatures && window.testPlayerFeatures.loadSubtitles) {
                                            window.testPlayerFeatures.loadSubtitles(true);
                                        }
                                    }
                                },
                                {
                                    key: 'pip',
                                    type: 'switcher',
                                    name: 'Picture in Picture',
                                    onChange: function onChange(_ref4) {
                                        var value = _ref4.value;
                                        var vid = player && player.$video;
                                        if (!vid || typeof vid.requestPictureInPicture !== 'function') return;
                                        if (document.pictureInPictureElement) {
                                            document.exitPictureInPicture().catch(function () {});
                                        } else {
                                            vid.requestPictureInPicture().catch(function (err) {
                                                console.warn('[OPlayerAdapter] PiP failed:', err);
                                            });
                                        }
                                    }
                                }
                            ]
                        })];

                        if (typeof OHls !== 'undefined') {
                            var hlsLibUrl = 'lib/hls.min.js';
                            oPlugins.push(OHls({ library: hlsLibUrl }));
                            console.log('[OPlayerAdapter] HLS plugin registered');
                        } else {
                            console.warn('[OPlayerAdapter] @oplayer/hls not loaded — HLS streams may not play');
                        }

                        player = OPlayer.make('#' + oplayerContainer.id, oplayerOptions)
                            .use(oPlugins)
                            .create();

                    /* Expose OPlayer instance globally for subtitle API integration */
                    window.__oplayerPlayer = player;

                    /* Sync PiP switcher with native PiP events */
                    (function() {
                        var pipSyncTimer = null;
                        function syncPipSwitcher() {
                            if (!player || !player.context || !player.context.ui) return;
                            try {
                                player.context.ui.setting.select('pip', document.pictureInPictureElement ? 1 : 0);
                            } catch(e) { /* setting may not be registered yet */ }
                        }
                        player.on('enterpictureinpicture', function() { syncPipSwitcher(); });
                        player.on('leavepictureinpicture', function() { syncPipSwitcher(); });
                        // Also sync when setting panel opens
                        document.addEventListener('click', function(e) {
                            if (e.target.closest && e.target.closest('.o-setting')) {
                                if (pipSyncTimer) clearTimeout(pipSyncTimer);
                                pipSyncTimer = setTimeout(syncPipSwitcher, 100);
                            }
                        });
                    })();

                    player.on('next', function() {
                        if (window.testPlayerFeatures) {
                            window.testPlayerFeatures._navigate('next');
                        }
                    });
                    player.on('previous', function() {
                        if (window.testPlayerFeatures) {
                            window.testPlayerFeatures._navigate('previous');
                        }
                    });
                } else {
                    console.warn('[OPlayerAdapter] @oplayer/ui not loaded, falling back to headless OPlayer');
                    player = OPlayer.make('#' + oplayerContainer.id, oplayerOptions).create();
                }

                /* OPlayer's player.duration getter reads $video.duration, which stays
                 * Infinity for empty_moov streams until fully buffered — the built-in
                 * timeline then shows "--:--" and a 0% progress bar. Shadow the getter
                 * with the DB total (data-duration) so the time label, progress/buffer
                 * fill and drag-seek all use the true duration; falls back to the
                 * element value for live/unknown. Element-based checks (drift-seek
                 * clamp, ended legitimacy) keep reading $video.duration directly. */
                try {
                    Object.defineProperty(player, 'duration', {
                        configurable: true,
                        get: function() {
                            var d = _knownDuration();
                            if (isFinite(d)) return d;
                            return (player.$video && player.$video.duration) || 0;
                        }
                    });
                } catch (e) { /* accessor non-configurable — keep lib default */ }

                /* OUI reads player.currentTime / calls player.seek() for the timeline
                 * and arrow keys; expose ABSOLUTE time and route seeks through
                 * _serverSeekTo so out-of-buffer targets restart the transcode. */
                try {
                    Object.defineProperty(player, 'currentTime', {
                        configurable: true,
                        get: function() {
                            return ((player.$video && player.$video.currentTime) || 0) + _streamStartOffset;
                        },
                        set: function(t) {
                            if (typeof t === 'number' && isFinite(t)) _serverSeekTo(t);
                        }
                    });
                } catch (e) { /* accessor non-configurable — keep lib default */ }
                try {
                    player.seek = function(t) {
                        if (typeof t === 'number' && isFinite(t)) _serverSeekTo(t);
                    };
                } catch (e) {}

                var waitForVideo = setInterval(function() {
                    if (player && player.$video) {
                        clearInterval(waitForVideo);
                        onVideoReady(player.$video);
                        /* Restore saved subtitle offset in OPlayer settings selector */
                        (function() {
                            var savedOffset = parseFloat(localStorage.getItem('jmedia_subtitle_correction') || '0');
                            if (savedOffset !== 0) {
                                var offsetValues = ['-2', '-1', '-0.5', '0', '0.5', '1', '2'];
                                var idx = offsetValues.indexOf(String(savedOffset));
                                if (idx >= 0 && player.context && player.context.ui) {
                                    try {
                                        player.context.ui.setting.select('subtitleOffset', idx);
                                    } catch(e) {}
                                }
                            }
                        })();

                        /* ---------- Custom Timeline Preview Thumbnails ----------
                         * OPlayer's built-in thumbnail system uses percentage-based
                         * background-position ( -${index}00% ) which is broken for
                         * grid sprite sheets. We implement our own pixel-based approach
                         * matching the working simple-player storyboard logic.          */
                        (function() {
                            if (container.dataset.type === 'live') return;
                            var sbUrl = '/api/video/storyboard/' + encodeURIComponent(videoId);
                            var metaUrl = sbUrl + '/metadata';
                            var sbMeta = null;
                            var $thumbEl = null;
                            var thumbReady = false;

                            function pollMeta() {
                                if (_destroyed) return;
                                fetch(metaUrl)
                                    .then(function(r) { return r.json(); })
                                    .then(function(json) {
                                        if (_destroyed) return;
                                        var d = json.data || json;
                                        if (d && d.isReady && d.totalTiles > 0) {
                                            sbMeta = d;
                                            if (!thumbReady) tryInit();
                                        } else {
                                            setTimeout(pollMeta, 3000);
                                        }
                                    })
                                    .catch(function() { setTimeout(pollMeta, 5000); });
                            }

                            function tryInit() {
                                if (_destroyed) return;
                                if (thumbReady) return;
                                if (!player || !player.context || !player.context.ui) {
                                    return setTimeout(tryInit, 500);
                                }
                                var $prog = player.context.ui.$progress;
                                if (!$prog) return setTimeout(tryInit, 500);

                                thumbReady = true;
                                var m = sbMeta;

                                $thumbEl = document.createElement('div');
                                $thumbEl.style.cssText = [
                                    'position:absolute',
                                    'left:0',
                                    'bottom:14px',
                                    'pointer-events:none',
                                    'transform:translateX(-50%)',
                                    'display:none',
                                    'z-index:10',
                                    'border-radius:2px',
                                    'width:' + m.width + 'px',
                                    'height:' + m.height + 'px',
                                    'background-image:url(' + sbUrl + ')',
                                    'background-size:' + (m.width * m.columns) + 'px ' + (m.height * m.rows) + 'px',
                                    'background-repeat:no-repeat'
                                ].join(';');

                                $prog.appendChild($thumbEl);

                                $prog.addEventListener('mouseenter', function() {
                                    if ($thumbEl) $thumbEl.style.display = 'block';
                                });

                                $prog.addEventListener('mousemove', function(e) {
                                    if (!$thumbEl || !sbMeta) return;
                                    var rect = $prog.getBoundingClientRect();
                                    var rate = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
                                    var dur = player.duration || 0;
                                    if (!dur || dur === Infinity) return;
                                    var time = rate * dur;
                                    var idx = Math.min(Math.floor(time / m.interval), m.totalTiles - 1);
                                    if (idx < 0) return;
                                    var col = idx % m.columns;
                                    var row = Math.floor(idx / m.columns);
                                    $thumbEl.style.backgroundPosition = '-' + (col * m.width) + 'px -' + (row * m.height) + 'px';
                                    var halfThumb = m.width / 2;
                                    var barWidth = rect.width;
                                    var leftPct;
                                    if (rate * barWidth < halfThumb) {
                                        leftPct = (halfThumb / barWidth) * 100;
                                    } else if (rate * barWidth > barWidth - halfThumb) {
                                        leftPct = ((barWidth - halfThumb) / barWidth) * 100;
                                    } else {
                                        leftPct = rate * 100;
                                    }
                                    $thumbEl.style.left = leftPct + '%';
                                });

                                $prog.addEventListener('mouseleave', function() {
                                    if ($thumbEl) $thumbEl.style.display = 'none';
                                });
                            }

                            setTimeout(pollMeta, 1500);
                        })();
                    }
                }, 50);
                setTimeout(function() { clearInterval(waitForVideo); }, 10000);

            } catch (err) {
                console.error('[OPlayerAdapter] Init error:', err);
            }
        }

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
                console.warn('[OPlayerAdapter] Failed to report stream status:', err);
            });
        }

        /* ---------- Suppress transient MEDIA_ERR_SRC_NOT_SUPPORTED ---------- */
        /* fMP4 streaming sends a 2-byte probe response ([0,0]) before real
           data is available.  Safari fires MEDIA_ERR_SRC_NOT_SUPPORTED (code 4)
           during this probe but recovers once actual fMP4 data arrives.
           Intercept at capture phase on the container to prevent OPlayer's
           internal error handler from showing a permanent error overlay. */
        oplayerContainer.addEventListener('error', function(e) {
            var vid = e.target;
            if (vid && vid.error && vid.error.code === MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED) {
                console.warn('[OPlayerAdapter] Suppressed MEDIA_ERR_SRC_NOT_SUPPORTED (transient with fMP4 stream)');
                e.stopImmediatePropagation();
                e.preventDefault();
            }
        }, true);

        function _broadcastState() {
            if (!_wsManager || !_wsManager.connected || _destroyed || !video || _swapInProgress || _applyingServerState) return;
            _wsManager.send('state', {
                currentVideoId: videoId,
                playing: !video.paused,
                currentTime: (video.currentTime || 0) + _streamStartOffset,
                profileId: (localStorage.getItem('activeProfileId') ? Number(localStorage.getItem('activeProfileId')) : null)
            });
        }

        function onVideoReady(vid) {
            video = vid;

            container.dataset.videoId = videoId;

            video.addEventListener('play', function() {
                PlayerUtils?.requestWakeLock?.();
                _reportStreamStatus('working');
                _broadcastState();
                showControls();
            });

            video.addEventListener('pause', function() {
                PlayerUtils?.releaseWakeLock?.();
                _broadcastState();
                showControls();
            });

            /* Keep the server phantom clock locked to client truth: without periodic
             * reports it lags after a seek and the drift-sync yanks back every 3s. */
            video.addEventListener('timeupdate', function() {
                _broadcastState();
            });

            video.addEventListener('seeking', function() {
                showControls();
            });

            video.addEventListener('seeked', function() {
                _localSeekAt = Date.now();
                _localSeekPos = (video.currentTime || 0) + _streamStartOffset;
                _broadcastState();
                /* Retry once shortly after: the _applyingServerState guard drops the
                   immediate report when a broadcast was mid-apply, and the server must
                   learn the seek or it keeps broadcasting the pre-seek position. */
                setTimeout(function() { _broadcastState(); }, 150);
            });

            video.addEventListener('error', function() {
                /* MEDIA_ERR_SRC_NOT_SUPPORTED is suppressed at the container
                   level above.  If we get here it is a real error. */
                PlayerUtils?.releaseWakeLock?.();
                _reportStreamStatus('dead');
            });

            video.addEventListener('ended', function() {
                PlayerUtils?.releaseWakeLock?.();
                /* Only auto-advance on a genuine end-of-playback: a spurious 'ended'
                 * from a slow load (drift-seek past the loaded end, zero-duration blob
                 * swap) must not cascade through the episode list. */
                var d = video.duration || 0;
                var legitEnd = !_swapInProgress && d > 0 && video.currentTime >= d - 1 && video.readyState >= 3 && !video.error;
                if (legitEnd && window.testPlayerFeatures) {
                    window.testPlayerFeatures._navigate('next');
                }
            });

            /* iOS native fullscreen events */
            video.addEventListener('webkitbeginfullscreen', function() {
                _isCssFS = false;
                container.classList.add('is-fullscreen');
            });
            video.addEventListener('webkitendfullscreen', function() {
                container.classList.remove('is-fullscreen');
                if (!video.paused) {
                    video.play().catch(function() {});
                }
            });

            console.log('[OPlayerAdapter] Initialized with videoId:', videoId);

            /* ---------- Restore playback position from saved progress ---------- */
            if (startTime > 0) {
                var _seekDone = false;
                video.addEventListener('loadedmetadata', function() {
                    if (!_seekDone) {
                        _seekDone = true;
                        video.currentTime = Math.max(0, startTime - _streamStartOffset);
                    }
                });
                video.addEventListener('canplay', function() {
                    if (!_seekDone) {
                        _seekDone = true;
                        video.currentTime = Math.max(0, startTime - _streamStartOffset);
                    }
                });
            }

            /* ---------- Volume persistence with exponential curve (matching JMedia default player) ---------- */
            video.addEventListener('volumechange', function() {
                var rawVol = video.volume;
                /* Invert the exponential curve to store the "slider position" —
                 * same as SimplePlayer (EventBinder.js): video.volume = Math.pow(sliderPos, 2)
                 * so sliderPos = Math.pow(video.volume, 1/2) */
                var sliderPos = Math.pow(Math.max(rawVol, 0), 1/2);
                localStorage.setItem(volumeKey, sliderPos);
                localStorage.setItem(muteKey, video.muted);
            });

            /* ---------- Inject seek buttons into OPlayer controller bar ---------- */
            (function injectSeekButtons() {
                if (_destroyed) return;
                if (!player || !player.context || !player.context.ui) {
                    setTimeout(injectSeekButtons, 300);
                    return;
                }
                var controllerBottom = player.context.ui.$controllerBottom;
                if (!controllerBottom) {
                    setTimeout(injectSeekButtons, 300);
                    return;
                }
                /* Find time display to use as insertion anchor */
                var timeEl = controllerBottom.querySelector('[aria-label="time"]');
                if (!timeEl) {
                    setTimeout(injectSeekButtons, 300);
                    return;
                }
                /* Prevent double injection */
                if (controllerBottom.querySelector('.o-seek-btn')) return;

                var videoRef = video;
                var btnStyle = [
                    'background:none',
                    'border:none',
                    'color:#fff',
                    'cursor:pointer',
                    'opacity:0.85',
                    'padding:0 2px',
                    'height:100%',
                    'display:inline-flex',
                    'align-items:center',
                    'justify-content:center',
                    'font-size:0.75rem',
                    'font-family:inherit',
                    'gap:1px',
                    'min-width:28px'
                ].join(';');

                var svgAttrs = 'viewBox="0 0 24 24" width="14" height="14" fill="currentColor"';

                function createSeekBtn(seconds) {
                    var isForward = seconds > 0;
                    var absSec = Math.abs(seconds);
                    /* Single arrow (15s) / double arrows (30s) */
                    var svgPath;
                    if (isForward) {
                        svgPath = absSec >= 30
                            ? '<path d="M5 6l9 6-9 6V6zM14 6l9 6-9 6V6z"/>'
                            : '<path d="M8 6l12 6-12 6V6z"/>';
                    } else {
                        svgPath = absSec >= 30
                            ? '<path d="M19 6l-9 6 9 6V6zM10 6l-9 6 9 6V6z"/>'
                            : '<path d="M16 6L4 12l12 6V6z"/>';
                    }

                    var btn = document.createElement('button');
                    btn.className = 'o-seek-btn';
                    btn.style.cssText = btnStyle;
                    btn.title = (isForward ? '+' : '-') + absSec + 's';
                    btn.setAttribute('aria-label', 'seek ' + (isForward ? '+' : '-') + absSec + 's');
                    var arrowSvg = '<svg ' + svgAttrs + '>' + svgPath + '</svg>';
                    var numSpan = '<span style="font-size:9px;font-weight:700;font-family:sans-serif;line-height:1;color:#fff">' + absSec + '</span>';
                    btn.innerHTML = isForward ? numSpan + arrowSvg : arrowSvg + numSpan;
                    btn.addEventListener('click', function(e) {
                        e.stopPropagation();
                        if (videoRef) {
                            var absDur = _knownDuration();
                            if (!isFinite(absDur)) absDur = (videoRef.duration || 0) + _streamStartOffset;
                            _serverSeekTo(Math.max(0, Math.min(absDur, (videoRef.currentTime || 0) + _streamStartOffset + seconds)));
                        }
                    });
                    return btn;
                }

                /* Insert seek buttons before the time display */
                timeEl.parentNode.insertBefore(createSeekBtn(-30), timeEl);
                timeEl.parentNode.insertBefore(createSeekBtn(-15), timeEl);
                timeEl.parentNode.insertBefore(createSeekBtn(15), timeEl);
                timeEl.parentNode.insertBefore(createSeekBtn(30), timeEl);

                console.log('[OPlayerAdapter] Seek buttons injected');
            })();

            /* ---------- Build adapter for TestPlayerFeatures ---------- */
            var oAdapter = {
                /* OPlayer manages its own stream lifecycle; the byte-tracker's full-file
                 * Blob swap in test-player-common.js races OPlayer's in-flight media fetch
                 * and aborts it (AbortError), leaving playback stuck on "loading". */
                disableBlobSwap: true,
                getVideoElement: function() { return video; },
                getCurrentTime: function() { return (video.currentTime || 0) + _streamStartOffset; },
                setCurrentTime: function(t) { _serverSeekTo(t); },
                getDuration: function() {
                    var d = _knownDuration();
                    if (isFinite(d)) return d;
                    return video.duration;
                },
                isPaused: function() { return video.paused; },
                play: function() { return video.play().catch(function() {}); },
                pause: function() { video.pause(); },
                getVolume: function() { return video.volume; },
                setVolume: function(v) { video.volume = v; },
                isMuted: function() { return video.muted; },
                setMuted: function(m) { video.muted = m; },
                getPlaybackRate: function() { return video.playbackRate; },
                setPlaybackRate: function(r) { video.playbackRate = r; },
                on: function(event, cb) { video.addEventListener(event, cb); },
                off: function(event, cb) { video.removeEventListener(event, cb); },
                getVideoSrc: function() { return video.src; },
                setVideoSrc: function(url) { video.src = url; },
                requestFullscreen: function() {
                    var c = document.getElementById('customPlayer');
                    if (c.requestFullscreen) c.requestFullscreen();
                    else if (c.webkitRequestFullscreen) c.webkitRequestFullscreen();
                }
            };

            if (window.TestPlayerFeatures) {
                window.testPlayerFeatures = new window.TestPlayerFeatures(videoId, oAdapter);
            }

            _initWebSocket();
        }

        if (document.readyState === 'complete' || document.readyState === 'interactive') {
            initPlayer();
        } else {
            document.addEventListener('DOMContentLoaded', initPlayer);
        }

        /* Re-acquire wake lock when page becomes visible again and video is playing */
        var _onVisibilityChange = function() {
            if (document.visibilityState === 'visible' && video && !video.paused) {
                PlayerUtils?.requestWakeLock?.();
            }
        };
        document.addEventListener('visibilitychange', _onVisibilityChange);

        window.destroyOPlayerAdapter = function() {
            _destroyed = true;
            /* Invalidate any in-flight swap window so late events/timeout can't act post-teardown */
            _swapToken++;
            _swapInProgress = false;
            _clearSwapListeners();
            if (_controlsTimer) { clearTimeout(_controlsTimer); _controlsTimer = null; }
            /* B8: final state report (playing:false) stops the server playback timer before
             * the WS is torn down; _wsManager is nulled after disconnect so a second call
             * cannot double-send. */
            if (_wsManager && _wsManager.connected && video) {
                try {
                    _wsManager.send('state', {
                        currentVideoId: videoId,
                        playing: false,
                        currentTime: (video.currentTime || 0) + _streamStartOffset,
                        profileId: (localStorage.getItem('activeProfileId') ? Number(localStorage.getItem('activeProfileId')) : null)
                    });
                } catch (e) {}
            }
            if (_wsManager) { _wsManager.disconnect(); _wsManager = null; }
            if (player && typeof player.destroy === 'function') {
                player.destroy();
            }
            /* B12: OPlayer's destroy() only revokes blob URLs — a direct
             * /api/video/stream URL stays on the element, keeping the fetch (and
             * the server-side ffmpeg remux/transcode) alive. Blank src + load()
             * to force-abort the media fetch so the server sees the disconnect
             * and kills the ffmpeg process. */
            if (video) {
                try {
                    video.pause();
                    video.removeAttribute('src');
                    video.load();
                } catch (e) {}
            }
            document.removeEventListener('click', closeMenuIfOpen);
            document.removeEventListener('touchend', _onTouchEnd);
            document.removeEventListener('fullscreenchange', syncFSIcon);
            document.removeEventListener('webkitfullscreenchange', syncFSIcon);
            document.removeEventListener('keydown', _onKeydown, true);
            document.removeEventListener('visibilitychange', _onVisibilityChange);
        };
    };

})();
