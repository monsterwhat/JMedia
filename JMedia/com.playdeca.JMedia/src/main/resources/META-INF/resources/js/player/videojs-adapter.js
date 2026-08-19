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
        /* Connect-time snapshot guard: VideoSocket.sendCurrentState pushes the persisted
         * session position on every WS (re)connect — server memory, not an action. Yanking
         * a fresh/reconnected element to that stale position is the load/reconnect bounce;
         * the element's own reports re-sync the phantom within one broadcast cycle. */
        var _wsConnectedAt = 0;
        var _yankEnabled = false;
        var _lastProgressTime = 0;
        // pid was previously scoped inside _initWebSocket only; _broadcastState referencing it threw a strict-mode ReferenceError
        var pid = profileId;

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
            if (param && url.indexOf('/api/video/stream/') === 0) {
                url = url.indexOf('?') !== -1 ? url + '&' + param : url + '?' + param;
            }
            var av1Param = _nativeAv1Param();
            if (av1Param && url.indexOf('/api/video/stream/') === 0) {
                url = url.indexOf('?') !== -1 ? url + '&' + av1Param : url + '?' + av1Param;
            }
            return url;
        }

        /* Native-AV1 remux override: when the backend flags a server-side transcode
         * (data-needs-transcode or data-needs-conversion, e.g. AV1 on iOS) but the
         * browser can play AV1 natively, request the lightweight remux instead of
         * the transcode. */
        function _nativeAv1Param() {
            var needsTranscode = container.dataset.needsTranscode === 'true';
            var needsConversion = container.dataset.needsConversion === 'true';
            if (!(needsTranscode || needsConversion)) return '';
            var codec = (container.dataset.videoCodec || '').toLowerCase();
            if (codec.indexOf('av1') === -1 && codec.indexOf('av01') === -1) return '';
            if (!(window.PlayerStreamManager && window.PlayerStreamManager.hasNativeAv1Support())) return '';
            return 'nativeAv1=1';
        }

        function _withNativeAv1(url) {
            var param = _nativeAv1Param();
            if (!param || url.indexOf('/api/video/stream/') !== 0) return url;
            return url.indexOf('?') !== -1 ? url + '&' + param : url + '?' + param;
        }

        function _traceId() {
            return Date.now() + '_' + Math.random().toString(36).slice(2, 8);
        }

        /* ---------- Build stream URL ---------- */
        var startTime = parseFloat(container.dataset.startTime || '0');
        /* Direct-streamed files (no transcode AND no conversion) are served raw by the
         * backend with full byte-range support — the server IGNORES ?start= on that
         * path (streamDirectFile has no start param). Baking ?start= for them restarts
         * the element from byte 0 while the offset claims otherwise, so resume must be
         * a native post-load seek instead (mirror SimplePlayer's needsTranscode branch). */
        var _isDirectFile = !container.dataset.externalUrl && container.dataset.needsTranscode !== 'true' && container.dataset.needsConversion !== 'true';
        /* Cache-buster on the initial stream URL: Firefox's session media cache keys
         * on the URL and can resume a STALE response (bytes=<total>- -> 416) when the
         * same URL is loaded again — the page-load bounce. Direct-streamed files are
         * served no-cache with native byte-range resume, so they stay unbusted. */
        var streamUrl = _withNativeHevc(container.dataset.externalUrl || ('/api/video/stream/' + encodeURIComponent(videoId) + '.mp4' + (!_isDirectFile ? '?start=' + Math.max(0, startTime) + '&trace=' + _traceId() : '')));
        var streamType = streamUrl.includes('.m3u8') ? 'application/x-mpegURL' : 'video/mp4';

        /* Absolute stream time base: the server's ?start=N remux emits RELATIVE
         * timestamps (ffmpeg -ss input seek, no -copyts), so element time = 0 at
         * the seek position. All server-facing times (WS seek, broadcasts,
         * drift-sync) are ABSOLUTE = element time + this offset. Every seek is
         * routed through _serverSeekTo(): in-buffer targets seek the element
         * natively; targets past the buffered (not-yet-transcoded) frontier
         * restart the stream with ?start=<abs> instead of issuing a byte-range
         * request into the still-growing transcode cache. */
        var _streamStartOffset = (startTime > 0 && !_isDirectFile && !container.dataset.externalUrl) ? startTime : 0;
        var _currentQuality = 0;

        /* DB duration in seconds (data-duration is milliseconds, native-player
         * convention — same >5000 guard as StateManager) or NaN when unknown
         * (live/never-scanned); callers then fall back to the element value. */
        function _knownDuration() {
            var rawDur = parseFloat(container.dataset.duration || 0);
            var d = rawDur > 5000 ? rawDur / 1000 : rawDur;
            return isFinite(d) && d > 0 ? d : NaN;
        }

        function _buildStreamUrl(startAbs, quality) {
            /* Direct-streamed files ignore ?start= (server has no start param on the
             * direct path), so never bake it — a native seek after load handles resume. */
            var url = '/api/video/stream/' + encodeURIComponent(videoId) + '.mp4' + (_isDirectFile ? '' : '?start=' + Math.max(0, startAbs) + '&trace=' + _traceId());
            if (quality && quality > 0) url += (_isDirectFile ? '?' : '&') + 'quality=' + quality;
            return _withNativeHevc(url);
        }

        /* Route every seek through here. In-buffer targets seek the element natively
         * (relative = absolute - offset); targets past the buffered frontier restart
         * the transcode with ?start=<abs> — the browser cannot byte-range into the
         * still-growing cache, so a native seek there would hang. HLS and external
         * direct-file URLs seek natively (segment engine / byte-range) and never
         * need a transcode restart. Targets BEHIND the current window start
         * (absTarget < _streamStartOffset) also reload: relTarget clamps to 0,
         * which is the window start — a native seek there would land at the window
         * start, not the requested position. */
        function _serverSeekTo(absTarget, forceReload) {
            if (_destroyed || !videoEl) return;
            var relTarget = Math.max(0, absTarget - _streamStartOffset);
            /* Direct-streamed files are byte-range seekable at ANY position (the
             * backend serves them raw and ignores ?start=), so a native element seek
             * is always correct — past-buffer and forceReload included. */
            if (_isDirectFile) {
                try { vjsPlayer.currentTime(relTarget); } catch (e) {}
                if (videoEl.paused) { try { videoEl.play().catch(function() {}); } catch (e) {} }
                return;
            }
            var isHls = (videoEl.currentSrc || '').indexOf('.m3u8') !== -1;
            var bufferedEnd = 0;
            try {
                if (videoEl.buffered.length > 0) bufferedEnd = videoEl.buffered.end(videoEl.buffered.length - 1);
            } catch (e) {}
            if (!forceReload && (isHls || container.dataset.externalUrl || (absTarget >= _streamStartOffset && relTarget <= bufferedEnd + 1))) {
                /* External direct-file URLs (and HLS segment engines) support
                 * byte-range/native seeks, so they never need a ?start= restart.
                 * Backward targets behind the window start (absTarget < offset) fall
                 * through to the ?start= reload — relTarget would clamp to 0, landing
                 * at the window start instead of the requested position. */
                try { vjsPlayer.currentTime(relTarget); } catch (e) {}
                if (videoEl.paused) { try { videoEl.play().catch(function() {}); } catch (e) {} }
                return;
            }

            /* Past buffer: server-side seek. Suppress broadcasts and drift-sync
             * during the reload window so a stale server position cannot seek-yank
             * the fresh element back (the element sits at 0 relative while the
             * server still reports the OLD absolute position). */
            _swapInProgress = true;
            _swapRetries = 0;
            clearTimeout(_swapTimeout);
            if (_swapErrorHandler) { vjsPlayer.off('error', _swapErrorHandler); _swapErrorHandler = null; }

            _streamStartOffset = Math.max(0, absTarget);
            /* Re-fetch the active subtitle at swap-start so the whole-file VTT
             * conversion overlaps the stream restart instead of serializing after
             * `playing` (mirrors the OPlayer adapter's swap-start restore). The
             * playing-time reapply below stays as the safety net for tracks reset
             * by the video.js source swap. */
            if (window.testPlayerFeatures && typeof window.testPlayerFeatures.reapplySubtitle === 'function') {
                window.testPlayerFeatures.reapplySubtitle();
            }
            /* Arm the echo-lock with the seek target. After a ?start= reload the
             * element clock restarts at 0 while the server may keep broadcasting the
             * OLD absolute position — above the new offset on a backward seek, so the
             * one-directional stale guard passes and drift-sync would yank the fresh
             * element back as soon as `playing` ends the swap (the seek bounce). The
             * lock suppresses that until the server echoes the new offset. */
            _localSeekAt = Date.now();
            _localSeekPos = absTarget;
            var url = _buildStreamUrl(absTarget, _currentQuality);
            var newType = url.includes('.m3u8') ? 'application/x-mpegURL' : 'video/mp4';

            /* `playing` sends ONE confirmation broadcast (B6) so the server adopts the
             * new absolute position; without it the server keeps the OLD position and
             * drift-sync yanks the fresh element back every 3s. `loadeddata` does NOT
             * end the swap (it fires before `playing`, and for a BACKWARD seek the
             * stale server position is ABOVE the new offset, so the one-directional
             * guard passes and drift-sync would yank the fresh element forward,
             * undoing the seek). The .one('playing') binding stays armed until it
             * fires; error and the 10s timeout also end the swap. */
            function _endServerSeek(confirmBroadcast) {
                _swapInProgress = false;
                clearTimeout(_swapTimeout);
                if (_swapErrorHandler) { vjsPlayer.off('error', _swapErrorHandler); _swapErrorHandler = null; }
                if (confirmBroadcast) _broadcastState();
            }
            _swapErrorHandler = function() {
                var err = vjsPlayer.error();
                // code 4 is a transient probe false-positive; playback may still start
                if (err && err.code === 4) return;
                _endServerSeek();
            };
            vjsPlayer.one('playing', function() {
                /* The load may have outlasted the 3s lock armed above — re-arm it now
                 * so suppression covers the echo round-trip (stale broadcasts arrive
                 * between `playing` and the server echoing the seek target). */
                _localSeekAt = Date.now();
                _endServerSeek(true);
                /* The ?start= reload restarted the element clock at 0 relative to the
                 * new offset, so the active subtitle must be re-fetched with the new
                 * offset URL — the persisted <track> element still points at the old
                 * window's cues otherwise (mirror the OPlayer adapter's _applySubtitle
                 * restore in _serverSeekTo). */
                if (window.testPlayerFeatures && typeof window.testPlayerFeatures.reapplySubtitle === 'function') {
                    window.testPlayerFeatures.reapplySubtitle();
                }
            });
            vjsPlayer.on('error', _swapErrorHandler);
            _swapTimeout = setTimeout(function() { _endServerSeek(false); }, 10000);

            try { vjsPlayer.pause(); } catch (e) {}
            var _savedRate = vjsPlayer.playbackRate();
            vjsPlayer.src({ src: url, type: newType });
            try { vjsPlayer.playbackRate(_savedRate); } catch (e) {}
            try { vjsPlayer.play().catch(function() {}); } catch (e) {}
        }

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

        /* F4: Shadow currentTime() getter so the control bar displays absolute
         * stream time (element time + offset) instead of raw element time on
         * ?start= streams — mirrors OPlayer's currentTime property shadow. */
        var _vjsOrigCurrentTime = vjsPlayer.currentTime.bind(vjsPlayer);
        vjsPlayer.currentTime = function(val) {
            if (val === undefined || val === null) {
                return _vjsOrigCurrentTime() + _streamStartOffset;
            }
            return _vjsOrigCurrentTime(val);
        };

        /* Restore volume/mute from localStorage with exponential curve (matching JMedia default player) */
        var savedVolume = Math.pow(parseFloat(localStorage.getItem(volumeKey) || '0.7'), 2);
        var savedMuted = localStorage.getItem(muteKey) === 'true';
        vjsPlayer.volume(savedVolume);
        vjsPlayer.muted(savedMuted);

        /* Restore playback position from saved progress. The initial URL already
         * baked ?start=startTime for transcoded streams, so the element clock starts
         * at 0 relative to it (seek is a no-op); external direct-file URLs (offset 0)
         * seek natively via byte-range. */
        if (startTime > 0) {
            vjsPlayer.one('loadedmetadata', function() {
                var target = Math.max(0, startTime - _streamStartOffset);
                /* Mid-segment coverage serves already land at relTarget; only seek
                 * when behind the target, else this would jump BACKWARD to the
                 * segment head. */
                if ((vjsPlayer.currentTime() || 0) < target - 0.1) {
                    vjsPlayer.currentTime(target);
                }
            });
        }

        /* video.js derives duration from the media element, which stays Infinity
         * for empty_moov streams until fully buffered — that puts the player in
         * live mode (timeline hidden). Push the DB total (data-duration) after
         * every source load; re-applied after ?start=/swap reloads because the
         * tech overwrites cache_.duration at each loadedmetadata. */
        vjsPlayer.on('loadedmetadata', function() {
            var d = _knownDuration();
            if (isFinite(d)) vjsPlayer.duration(d);
        });

        /* A mid-segment coverage serve lands the element clock at relTarget (segment
         * timeline restarts at its head), not at the requested seek point — re-derive
         * the true content start at load so seek/broadcast/drift math stays on the
         * real timeline (mirrors the OPlayer adapter's loadeddata sampler). */
        vjsPlayer.on('loadeddata', function() {
            /* F4: read raw element time (not the overridden vjsPlayer.currentTime()
             * which adds _streamStartOffset) to correctly derive the content start. */
            var ct = videoEl.currentTime || 0;
            var base = Math.max(0, _streamStartOffset - ct);
            if (Math.abs(base - _streamStartOffset) > 0.1) {
                _streamStartOffset = base;
                /* Re-fetch subtitles with the corrected offset so cues align
                 * with the actual element clock (fixes forward-seek desync
                 * where subs appeared ahead of audio by the keyframe gap). */
                if (window.testPlayerFeatures && typeof window.testPlayerFeatures.reapplySubtitle === 'function') {
                    window.testPlayerFeatures.reapplySubtitle();
                }
            }
        });

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

                /* Absolute position + forced reload: _serverSeekTo bakes the quality
                 * into ?start=<abs>&quality=<q> so the element restarts at 0 relative
                 * to the position (previously the relative time was baked AND sought
                 * after reload, landing at 2x the position). */
                var currentTime = vjsPlayer.currentTime() || 0;
                _currentQuality = quality;
                _serverSeekTo(currentTime, true);
                return;
            }
        });

        /* ---------- Settings toggle ---------- */
        settingsToggleBtn.addEventListener('click', function(e) {
            e.stopPropagation();
            subtitleMenu.classList.toggle('active');
        });

        /* Close menu when clicking outside */
        var _onDocClick = function(e) {
            if (subtitleMenu.classList.contains('active') &&
                !subtitleMenu.contains(e.target) &&
                e.target !== settingsToggleBtn &&
                !settingsToggleBtn.contains(e.target)) {
                subtitleMenu.classList.remove('active');
            }
        };
        document.addEventListener('click', _onDocClick);

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

        var _onFullscreenChange = function() {
            if (_isCssFS) return;
            var isFS = document.fullscreenElement || document.webkitFullscreenElement;
            container.classList.toggle('is-fullscreen', !!isFS);
        };
        document.addEventListener('fullscreenchange', _onFullscreenChange);
        document.addEventListener('webkitfullscreenchange', _onFullscreenChange);

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
        var _onKeydown = function(e) {
            if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;
            switch (e.key) {
                case ' ':
                case 'k': e.preventDefault(); if (vjsPlayer.paused()) vjsPlayer.play().catch(function() {}); else vjsPlayer.pause(); break;
                case 'f': e.preventDefault(); toggleFullscreen(); break;
                case 'm': e.preventDefault(); vjsPlayer.muted(!vjsPlayer.muted()); break;
                case 'ArrowLeft': e.preventDefault(); _serverSeekTo(Math.max(0, (vjsPlayer.currentTime() || 0) - 15)); break;
                case 'ArrowRight': e.preventDefault(); var absDur = _knownDuration(); if (!isFinite(absDur)) absDur = (vjsPlayer.duration() || 0) + _streamStartOffset; _serverSeekTo(Math.min(absDur, (vjsPlayer.currentTime() || 0) + 15)); break;
                case 'ArrowUp':
                    e.preventDefault();
                    vjsPlayer.volume(Math.min(1, (vjsPlayer.volume() || 0) + 0.1));
                    break;
                case 'ArrowDown':
                    e.preventDefault();
                    vjsPlayer.volume(Math.max(0, (vjsPlayer.volume() || 0) - 0.1));
                    break;
            }
        };
        document.addEventListener('keydown', _onKeydown, true);

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
                onOpen: function() { _wsConnectedAt = Date.now(); },
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
                            var isExternalSwap = !!container.dataset.externalUrl;
                            var newUrl = _withNativeHevc(container.dataset.externalUrl || ('/api/video/stream/' + encodeURIComponent(newId) + '.mp4?trace=' + _traceId()));
                            videoId = newId;  // update the closure variable
                            container.dataset.videoId = newId;
                            /* Refresh data-duration for the new episode so the loadedmetadata
                             * duration push uses the new total (the WS payload does not
                             * carry duration). */
                            try {
                                fetch('/api/video/' + encodeURIComponent(newId))
                                    .then(function(r) { return r.ok ? r.json() : null; })
                                    .then(function(meta) {
                                        if (!meta || _destroyed || videoId !== newId) return;
                                        if (typeof meta.duration === 'number' && meta.duration > 0) container.dataset.duration = String(meta.duration);
                                        if (meta.title) container.dataset.title = meta.title;
                                        if (meta.type) container.dataset.type = meta.type;
                                    })
                                    .catch(function() { /* best-effort metadata refresh */ });
                            } catch (e) {}
                            var newType = newUrl.includes('.m3u8') ? 'application/x-mpegURL' : 'video/mp4';
                            // Resume from the NEW video's server state (B10): advanceVideo -> 0 -> no seek; selectVideo -> resume time -> seek
                            var resumeTime = (typeof state.currentTime === 'number' && state.currentTime > 0) ? state.currentTime : 0;
                            /* Server currentTime is ABSOLUTE: bake it as ?start= for transcoded
                             * streams so the new element clock restarts at 0 relative to it
                             * (offset mirrors the baked param). External direct-file URLs and
                             * internally direct-streamed files ignore ?start= — offset stays
                             * 0 and the post-load native seek (resumeTime) handles them. */
                            var noStartBake = isExternalSwap || _isDirectFile;
                            _streamStartOffset = noStartBake ? 0 : resumeTime;
                            var startParam = (!noStartBake && resumeTime > 0) ? 'start=' + Math.floor(resumeTime) + '&' : '';
                            newUrl = _withNativeHevc(container.dataset.externalUrl || ('/api/video/stream/' + encodeURIComponent(newId) + '.mp4?' + startParam + 'trace=' + _traceId()));
                            newType = newUrl.includes('.m3u8') ? 'application/x-mpegURL' : 'video/mp4';
                            _performSwapLoad(newUrl, newType, noStartBake ? resumeTime : 0, !!state.playing, _streamStartOffset);
                            return;  // still hits the finally block
                        }
                        if (state.playing && videoEl.paused) {
                            videoEl.play().catch(function() {});
                        } else if (!state.playing && !videoEl.paused) {
                            videoEl.pause();
                        }
                        if (typeof state.currentTime === 'number' && !_swapInProgress) {
                            /* Server currentTime is ABSOLUTE; the element clock restarts
                             * at 0 on each ?start= reload, so translate to element time
                             * before clamping and drift-comparing (mirrors _streamStartOffset). */
                            var d = videoEl.duration;
                            var relTarget = Math.max(0, state.currentTime - _streamStartOffset);
                            if (isFinite(d) && d > 0 && relTarget > d - 1) relTarget = d - 1;
                            var drift = Math.abs((videoEl.currentTime || 0) - relTarget);
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
                            /* Drift-yank guard: only follow the phantom once real playback
                             * has started (element reports positions) and the WS has been up
                             * for a grace window — the connect-time snapshot and load echoes
                             * are server memory, not actions, and yanking to them is the
                             * page-load/reconnect bounce. The phantom must also be AT or AHEAD
                             * of the element's real position: a phantom behind it is stale
                             * server memory (e.g. selectVideo reset to 0 while the element is
                             * mid-play), and yanking backward to it is the seek/load bounce. */
                            /* F1: only yank when element is NOT paused and progress is fresh
                             * (progressAge < 8s) — mirrors simple-player.js gate. Clear
                             * _yankEnabled after the check so the flag can't stick across
                             * pause/stall boundaries. */
                            if (_yankEnabled && !videoEl.paused && (Date.now() - _lastProgressTime) < 8000 && (Date.now() - _wsConnectedAt) >= 3000 && drift > 3 && state.currentTime >= (videoEl.currentTime || 0) + _streamStartOffset && (!locked || converged)) {
                                console.warn('[VideojsAdapter] Drift-yank ' + (videoEl.currentTime || 0).toFixed(2) + ' -> ' + relTarget.toFixed(2) + ' (phantom ' + state.currentTime.toFixed(2) + ', offset ' + _streamStartOffset + ')');
                                videoEl.currentTime = relTarget;
                            }
                            _yankEnabled = false;
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
                    // B3/D6: suppress broadcast echo — server-commanded play/pause/seek
                    // must not trigger _broadcastState back to the server.
                    var _prevApplying = _applyingServerState;
                    _applyingServerState = true;
                    if (ctype === 'select-subtitle') {
                        var idx = cmd.payload && cmd.payload.index;
                        if (idx == null) { _applyingServerState = _prevApplying; return; }
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
                        if (idx == null) { _applyingServerState = _prevApplying; return; }
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
                        if (vjsPlayer.paused()) vjsPlayer.play().catch(function() {}); else vjsPlayer.pause();
                    } else if (ctype === 'seek') {
                        if (_destroyed || !vjsPlayer) return;
                        var t = cmd.payload && cmd.payload.value;
                        if (typeof t === 'number' && isFinite(t)) {
                            _serverSeekTo(t);
                        }
                    }
                    _applyingServerState = _prevApplying;
                }
            });
            _wsManager.connect();
        }

        function _broadcastState() {
            if (!_wsManager || !_wsManager.connected || _applyingServerState || _swapInProgress || _destroyed || !videoEl) return;
            _wsManager.send('state', {
                currentVideoId: videoId,
                playing: !videoEl.paused,
                currentTime: (videoEl.currentTime || 0) + _streamStartOffset,
                profileId: (pid ? Number(pid) : null)
            });
        }

        function _performSwapLoad(newUrl, newType, resumeTime, shouldPlay, streamOffset) {
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
                        var retryUrl = _withNativeHevc(container.dataset.externalUrl || ('/api/video/stream/' + encodeURIComponent(videoId) + '.mp4?' + ((streamOffset && streamOffset > 0) ? 'start=' + Math.floor(streamOffset) + '&' : '') + 'trace=' + _traceId()));
                        _performSwapLoad(retryUrl, retryUrl.includes('.m3u8') ? 'application/x-mpegURL' : 'video/mp4', resumeTime, shouldPlay, streamOffset);
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
            _yankEnabled = true;
            _lastProgressTime = Date.now();
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
            getStreamStartOffset: function() { return _streamStartOffset; },
            /* video.js runs with nativeTextTracks:false (emulation mode), so plain
             * <track> elements appended to the element never render. Add through the
             * emulated list instead. manualCleanup:true keeps the track across the
             * ?start= source swaps, so the swap-start reapply (which already carries
             * the fresh offset) survives the reload; removal is owned by clearSubtitles. */
            addSubtitleTrack: function(track, src) {
                var rt = vjsPlayer.addRemoteTextTrack({
                    kind: 'subtitles',
                    src: src,
                    srclang: track.language || 'en',
                    label: track.displayName || 'Subtitles',
                    default: true
                }, true);
                if (rt && rt.track) {
                    try { rt.track.mode = 'showing'; } catch (e) {}
                }
                return rt;
            },
            clearSubtitles: function() {
                if (!vjsPlayer) return;
                var tracks = vjsPlayer.remoteTextTracks();
                for (var i = tracks.length - 1; i >= 0; i--) {
                    try { vjsPlayer.removeRemoteTextTrack(tracks[i]); } catch (e) {}
                }
            },
            getCurrentTime: function() { return vjsPlayer.currentTime() || 0; },
            setCurrentTime: function(t) { _serverSeekTo(t); },
            getDuration: function() {
                var d = vjsPlayer.duration();
                if (isFinite(d) && d > 0) return d;
                var k = _knownDuration();
                return isFinite(k) ? k : d;
            },
            isPaused: function() { return vjsPlayer.paused(); },
            play: function() { return vjsPlayer.play().catch(function() {}); },
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
        var _onVisibilityChange = function() {
            if (document.visibilityState === 'visible' && !vjsPlayer.paused()) {
                PlayerUtils?.requestWakeLock?.();
            }
        };
        document.addEventListener('visibilitychange', _onVisibilityChange);

        window.destroyVideoJsAdapter = function() {
            _destroyed = true;
            document.removeEventListener('click', _onDocClick);
            document.removeEventListener('fullscreenchange', _onFullscreenChange);
            document.removeEventListener('webkitfullscreenchange', _onFullscreenChange);
            document.removeEventListener('keydown', _onKeydown, true);
            document.removeEventListener('visibilitychange', _onVisibilityChange);
            // Send a FINAL state report so the server stops its playback timer when the player is torn down
            if (_wsManager && _wsManager.connected) {
                try {
                    _wsManager.send('state', {
                        currentVideoId: videoId,
                        playing: false,
                        currentTime: (videoEl.currentTime || 0) + _streamStartOffset,
                        profileId: (pid ? Number(pid) : null)
                    });
                } catch (e) {}
            }
            if (_wsManager) { _wsManager.disconnect(); _wsManager = null; }
            if (vjsPlayer && typeof vjsPlayer.dispose === 'function') { vjsPlayer.dispose(); }
            /* B12: video.js dispose() removes the element, but a lingering
             * reference can keep the media fetch (and the server-side ffmpeg
             * remux/transcode) alive. Force-abort it: blank src + load() so the
             * server sees the disconnect and kills the ffmpeg process. */
            if (videoEl) {
                try {
                    videoEl.pause();
                    videoEl.removeAttribute('src');
                    videoEl.load();
                } catch (e) {}
            }
        };
    };
})();
