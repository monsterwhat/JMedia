(function(window) {
    'use strict';

    // Logging gate: verbose player tracing is off unless localStorage.jmedia_debug
    // is "1". warn/error stay unconditional so real failures are never hidden.
    const JMEDIA_DEBUG = (function () {
        try { return localStorage.getItem('jmedia_debug') === '1'; } catch (e) { return false; }
    })();
    const dbg = JMEDIA_DEBUG ? console.log.bind(console) : function () {};

    /** Clean playback after which a used retry is given back. */
    const HEALTHY_PLAYBACK_MS = 30000;
    /** Mid-playback buffering before a reconnect (was 60 s). */
    const MID_STALL_MS = 15000;
    /** Extra 15 s windows granted while data is still arriving (slow but alive). */
    const MID_STALL_MAX_EXTENSIONS = 3;

    window.PlayerEventBinder = class {
        constructor(player) {
            this.player = player;
        }

        bind() {
            if (this._bound) return; // F9: prevent double-registration on repeated calls
            this._bound = true;
            const p = this.player;

            var _liveChannelId = p.container.dataset.liveChannelId;
            var _statusReported = false;
            function _reportStreamStatus(status) {
                if (_statusReported || !_liveChannelId) return;
                _statusReported = true;
                fetch('/api/video/m3u/channels/' + _liveChannelId + '/status', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ status: status })
                }).catch(function(err) {
                    console.warn('[SimplePlayer] Failed to report stream status:', err);
                });
            }

            p.video.addEventListener('error', function() {
                var err = p.video.error;
                if (err && err.code === MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED) return;
                _reportStreamStatus('dead');
            });
            const touchToggle = (e) => {
                e.preventDefault();
                p._touchToggled = true;
                if (p.video.paused) {
                    dbg('[SimplePlayer] User play (touch) — clearing user-pause gate');
                    p._userPaused = false; p._userPausedAt = 0;
                    p.video.play().catch(function(err) { console.error('[SimplePlayer] User play (touch) failed', err); });
                    try { if (typeof p._broadcastState === 'function') p._broadcastState(); } catch (e2) { console.error('[SimplePlayer] _broadcastState after touch play failed', e2); }
                    try { p.progressReporter.saveNow(true); } catch (e3) { console.error('[SimplePlayer] saveNow after touch play failed', e3); }
                } else {
                    dbg('[SimplePlayer] User pause (touch) — engaging user-pause gate');
                    p._userPaused = true; p._userPausedAt = Date.now();
                    try { p.video.pause(); dbg('[SimplePlayer] video.pause() issued (user touch gesture)'); } catch (e2) { console.error('[SimplePlayer] User pause (touch) failed', e2); }
                    try { if (typeof p._broadcastState === 'function') p._broadcastState(); } catch (e2) { console.error('[SimplePlayer] _broadcastState after touch pause failed', e2); }
                    try { p.progressReporter.saveNow(true); } catch (e3) { console.error('[SimplePlayer] saveNow after touch pause failed', e3); }
                }
                p.controlsManager.showControls();
                p._touchToggleTimer = setTimeout(function() { p._touchToggled = false; p._touchToggleTimer = null; }, 500);
            };

            p.clickOverlay.addEventListener('touchstart', touchToggle, { passive: false });
            p.bigPlay.addEventListener('touchstart', touchToggle, { passive: false });
            p.playBtn.addEventListener('touchstart', touchToggle, { passive: false });

            const toggle = (e) => {
                if (p._touchToggled) {
                    p._touchToggled = false;
                    return;
                }
                if (e) e.stopPropagation();
                if (p.video.paused) {
                    dbg('[SimplePlayer] User play (click) — clearing user-pause gate');
                    p._userPaused = false; p._userPausedAt = 0;
                    p.video.play().catch(function(err) { console.error('[SimplePlayer] User play (click) failed', err); });
                    try { if (typeof p._broadcastState === 'function') p._broadcastState(); } catch (e2) { console.error('[SimplePlayer] _broadcastState after user play failed', e2); }
                    try { p.progressReporter.saveNow(true); } catch (e3) { console.error('[SimplePlayer] saveNow after user play failed', e3); }
                } else {
                    dbg('[SimplePlayer] User pause (click) — engaging user-pause gate');
                    p._userPaused = true; p._userPausedAt = Date.now();
                    try { p.video.pause(); dbg('[SimplePlayer] video.pause() issued (user click gesture)'); } catch (err) { console.error('[SimplePlayer] User pause (click) failed', err); }
                    try { if (typeof p._broadcastState === 'function') p._broadcastState(); } catch (e2) { console.error('[SimplePlayer] _broadcastState after user pause failed', e2); }
                    try { p.progressReporter.saveNow(true); } catch (e3) { console.error('[SimplePlayer] saveNow after user pause failed', e3); }
                }
                p.controlsManager.showControls();
            };

            p.video.addEventListener('play', () => {
                if (p.utils.isIOS()) console.debug('[iOS-DEBUG] Play event fired, setting 20s stall timer');
                p.playIcon.className = 'pi pi-pause';
                p.bigPlay.style.display = 'none';
                if (p.tapToPlayOverlay) p.tapToPlayOverlay.classList.remove('visible');
                p.container.classList.remove('paused');
                p.controlsManager.showControls();
                _reportStreamStatus('working');
                if (p._stallTimer) clearTimeout(p._stallTimer);
                // Buffered ranges keep growing while a server-side transcode is
                // still producing in real time. Reloading the source in that
                // state aborts a play() that was about to succeed, so treat
                // "no data received" literally: only reload when the buffer has
                // not advanced since the timer was armed.
                let stallExtensions = 0;
                const armStallTimer = (bufferedAtWindowStart) => {
                    p._stallTimer = setTimeout(() => {
                        if (p._destroyed) return;
                        if (p._userPaused) {
                            dbg('[SimplePlayer] Stall timer suppressed — user pause active');
                            return;
                        }
                        if (p.video.currentTime === 0 && !p.video.paused && !p._hasPlayedData) {
                            const bufferedNow = (p.streamMgr && typeof p.streamMgr._bufferedEnd === 'function')
                                ? p.streamMgr._bufferedEnd(p.video) : 0;
                            if (bufferedNow > bufferedAtWindowStart + 0.05 && stallExtensions < 3) {
                                stallExtensions++;
                                dbg('[SimplePlayer] Playback has not started yet but data is still arriving (' +
                                    bufferedNow.toFixed(1) + 's buffered) - waiting instead of reloading');
                                armStallTimer(bufferedNow);
                                return;
                            }
                            if (p.utils.isIOS()) console.debug('[iOS-DEBUG] Stall timer fired: currentTime=' + p.video.currentTime + ' paused=' + p.video.paused + ' _hasPlayedData=' + p._hasPlayedData + ' _destroyed=' + p._destroyed + ' _streamFallbackCount=' + (p._streamFallbackCount || 0) + ' _maxStreamFallbacks=' + p._maxStreamFallbacks);
                            console.warn('[SimplePlayer] Playback stalled - no data received in 20s');
                            if (window.Toast) window.Toast.warning('Playback stuck - retrying...');

                            p.streamMgr.clearStreamErrorHandlers();

                            if (p._qualitySwitching) return;

                            p._streamFallbackCount = (p._streamFallbackCount || 0) + 1;
                            if (p._streamFallbackCount < p._maxStreamFallbacks) {
                                const saved = p.streamStartOffset || p.lastKnownGoodPosition || p.initialResumeTime || 0;
                                p.video.src = p.streamMgr.buildStreamUrl(saved);
                                if (p.utils.isIOS()) console.debug('[iOS-DEBUG] Stall timer reloading src:', p.video.src);
                                p.video.load();
                                if (p._userPaused) {
                                    dbg('[SimplePlayer] Blocked stall-recovery play — user pause active');
                                } else {
                                    dbg('[SimplePlayer] Stall-recovery play()');
                                    p.video.play().catch(function(e) { console.error('[SimplePlayer] Stall-recovery play failed', e); });
                                }
                            } else {
                                if (window.Toast) window.Toast.error('Playback failed after retries - please reload');
                            }
                        }
                    }, 20000);
                };
                armStallTimer((p.streamMgr && typeof p.streamMgr._bufferedEnd === 'function')
                    ? p.streamMgr._bufferedEnd(p.video) : 0);
                p._toastTimer = setTimeout(() => {
                    p._toastTimer = null;
                    if (p._stallTimer && p.video.currentTime === 0 && !p._hasPlayedData) {
                        if (window.Toast) window.Toast.info('Server is preparing your video, please wait...');
                    }
                }, 10000);
            });

            p.video.addEventListener('pause', () => {
                if (!p._isAlive()) return;
                p.playIcon.className = 'pi pi-play';
                p.bigPlay.style.display = 'flex';
                if (p.tapToPlayOverlay) p.tapToPlayOverlay.classList.add('visible');
                p.container.classList.add('paused');
                p.controlsManager.showControls();
                p.progressReporter.saveNow();
            });

            p.video.addEventListener('timeupdate', () => {
                if (!p._isAlive()) return;
                p._touchToggled = false;
                p._lastProgressAt = Date.now();

                let dur = p.totalDuration;
                if (!dur || dur === Infinity) {
                    dur = p.video.duration;
                }
                if (!dur || dur === Infinity) return;

                const displayTime = Math.min(p.video.currentTime + (p.streamStartOffset || 0), dur);
                const pct = (displayTime / dur) * 100;
                p.progressBar.style.width = Math.min(100, pct) + '%';
                p.timeCurrent.innerText = p.utils.formatTime(displayTime);

                p.skipController.checkAutoSkip(displayTime);

                p.controlsManager.checkMarkers();

                if (p.video.currentTime > 0) {
                    if (!p._hasPlayedData && p.utils.isIOS()) console.debug('[iOS-DEBUG] _hasPlayedData set to true at currentTime=' + p.video.currentTime);
                    p.lastKnownGoodPosition = p.video.currentTime;
                    p._hasPlayedData = true;
                    // A recovery that has played cleanly for 30 s is over: give the
                    // retry budget back. It was shared by every error and stall path
                    // and never refilled, so one hiccup left no retry for the rest
                    // of a two-hour film.
                    if (p._streamFallbackCount > 0) {
                        const now = Date.now();
                        if (!p._healthySince) {
                            p._healthySince = now;
                        } else if (now - p._healthySince > HEALTHY_PLAYBACK_MS) {
                            dbg('[SimplePlayer] Playback healthy for 30s - retry budget restored');
                            p._streamFallbackCount = 0;
                            p._healthySince = 0;
                        }
                    }
                    if (p._stallTimer) {
                        clearTimeout(p._stallTimer);
                        p._stallTimer = null;
                    }
                }
            });

            p.video.addEventListener('seeked', () => {
                p._localSeekAt = Date.now();
                p._localSeekPos = p.video.currentTime || 0;
                p._broadcastState();
                p._broadcastTimer = setTimeout(() => { p._broadcastTimer = null; p._broadcastState(); }, 150);
            });

            p.video.addEventListener('loadedmetadata', () => {
                if (!p._isAlive()) return;
                const dur = p.video.duration;
                dbg(`[SimplePlayer] Metadata loaded. Stream duration: ${dur}s, DB duration: ${p.totalDuration}s`);

                if (dur && dur !== Infinity && dur > p.totalDuration && !p.streamStartOffset) {
                    p.totalDuration = dur;
                }

                // A truncated stream (an HLS session that only produced a few seconds) is
                // not a short episode: letting it play means it "ends" seconds later, the
                // onended handler advances to the next episode and the stream the viewer
                // asked for is torn down. Fall back to the direct stream instead.
                //
                // HLS is excluded: its playlist duration reflects the segments encoded so
                // far and grows while you watch. Treating that snapshot as the media
                // length abandoned healthy streams and fell back to a direct transcode
                // that then stalled (ERR_CONTENT_LENGTH_MISMATCH).
                const dbDuration = p.totalDuration || 0;
                const absoluteEnd = (p.streamStartOffset || 0) + (isFinite(dur) && dur > 0 ? dur : 0);
                if (!p._hlsInstance && !p._truncatedStream && isFinite(dur) && dur > 0 && dbDuration > 0 && absoluteEnd < dbDuration * 0.9) {
                    console.warn('[SimplePlayer] Stream duration ' + Math.round(dur)
                        + 's is far shorter than the episode (' + Math.round(dbDuration)
                        + 's) — reloading as a direct stream');
                    p._truncatedStream = true;
                    if (p.streamMgr && p.streamMgr.fallbackToDirectStream) {
                        try {
                            p.streamMgr.fallbackToDirectStream(p.streamStartOffset || 0);
                        } catch (e) {
                            console.error('[SimplePlayer] Direct-stream fallback failed', e);
                        }
                    }
                    return;
                }

                p.controlsManager.applyInitialState();
                p.controlsManager.updateMarkers();

                // Resuming inside the outro (a saved position past outroStart) must not
                // auto-advance: the skip fires on the first timeupdate, sees no tail left
                // and jumps to the next episode before the viewer has seen anything.
                const startPos = p.streamStartOffset || 0;
                if (p.markers && p.markers.outroStart > 0 && startPos >= p.markers.outroStart - 2) {
                    p._outroSkipDisabled = true;
                    dbg('[SimplePlayer] Resumed inside the outro (' + Math.round(startPos)
                        + 's >= ' + Math.round(p.markers.outroStart) + 's) — auto-skip disabled for this playback');
                }

                if (p.initialResumeTime > 0 && (!p.streamStartOffset || p.streamStartOffset === 0)) {
                    dbg(`[SimplePlayer] Performing client-side seek to ${p.initialResumeTime}s`);
                    p.video.currentTime = p.initialResumeTime;
                    p.initialResumeTime = 0;
                }
            });

            p._waitingStart = 0;
            p._waitingTimer = null;
            p.video.onwaiting = () => {
                p.buffering.style.display = 'block';
                // Buffering ends the healthy streak that refills the retry budget.
                p._healthySince = 0;
                if (p._waitingStart === 0) p._waitingStart = Date.now();
                if (p._waitingTimer) return;
                const bufferedEnd = () => (p.streamMgr && typeof p.streamMgr._bufferedEnd === 'function')
                    ? p.streamMgr._bufferedEnd(p.video) : 0;
                // Reconnect after 15 s of buffering (was 60 s of spinner). While data
                // is still arriving the stream is slow, not dead - a reload would only
                // restart the transcode - so allow up to three more windows for it.
                let extensions = 0;
                const arm = (bufferedAtWindowStart) => {
                    p._waitingTimer = setTimeout(() => {
                        p._waitingTimer = null;
                        if (p._destroyed) return;
                        if (p._userPaused) {
                            dbg('[SimplePlayer] Mid-playback stall check suppressed — user pause active');
                            p._waitingStart = 0;
                            return;
                        }
                        const bufferedNow = bufferedEnd();
                        if (bufferedNow > bufferedAtWindowStart + 0.5 && extensions < MID_STALL_MAX_EXTENSIONS) {
                            extensions++;
                            dbg('[SimplePlayer] Still buffering but data is arriving (' + bufferedNow.toFixed(1)
                                + 's buffered) - waiting another ' + (MID_STALL_MS / 1000) + 's');
                            arm(bufferedNow);
                            return;
                        }
                        p._waitingStart = 0;
                        if (!p._hasPlayedData || !(p.lastKnownGoodPosition > 0)) return;
                        if (p.utils.isIOS()) console.debug('[iOS-DEBUG] mid-playback stall: currentTime=' + p.video.currentTime + ' lastKnownGoodPosition=' + p.lastKnownGoodPosition + ' networkState=' + p.video.networkState + ' _streamFallbackCount=' + (p._streamFallbackCount || 0));
                        const pos = p._currentAbsolutePosition();
                        console.warn('[SimplePlayer] Mid-playback stall detected, reconnecting at', pos);
                        p.streamMgr.clearStreamErrorHandlers();
                        p._streamFallbackCount = (p._streamFallbackCount || 0) + 1;
                        if (p._streamFallbackCount >= p._maxStreamFallbacks) {
                            if (window.Toast) window.Toast.error('Playback failed - please reload');
                            return;
                        }
                        if (window.Toast) window.Toast.warning('Playback stalled - reconnecting... (' + p._streamFallbackCount + '/' + p._maxStreamFallbacks + ')');
                        // HLS: rebuild the session at the position. Assigning a plain
                        // src here (as before) replaced hls.js with a progressive stream.
                        if (p._hlsSessionId || p._hlsSessionPending) {
                            p.streamMgr.recreateLocalHlsSession(pos);
                            return;
                        }
                        if (p.needsTranscode) {
                            p.streamStartOffset = pos;
                            p.initialResumeTime = 0;
                            p.video.src = p.streamMgr.buildStreamUrl(pos);
                        } else {
                            // F2b: direct files ignore ?start=: load from 0 and let the
                            // loadedmetadata resume seek put the viewer back at pos
                            // (resetting the offset alone restarted the film at 0:00).
                            p.streamStartOffset = 0;
                            p.initialResumeTime = pos;
                            p.video.src = p.streamMgr.buildStreamUrl(null);
                        }
                        p.lastKnownGoodPosition = 0;
                        p.video.load();
                        if (p._userPaused) {
                            dbg('[SimplePlayer] Blocked stall-recovery play — user pause active');
                        } else {
                            dbg('[SimplePlayer] Stall-recovery play()');
                            p.video.play().catch(function(e) { console.error('[SimplePlayer] Stall-recovery play failed', e); });
                        }
                    }, MID_STALL_MS);
                };
                arm(bufferedEnd());
            };
            p.video.onplaying = () => {
                p.buffering.style.display = 'none';
                p._waitingStart = 0;
                if (p._waitingTimer) {
                    clearTimeout(p._waitingTimer);
                    p._waitingTimer = null;
                }
            };
            p.video.onended = () => {
                if (!p._isAlive()) return;
                if (p._applyingServerState || !p._hasPlayedData) return;
                const d = p.video.duration;
                if (!(d > 0) || p.video.currentTime < d - 1) return;
                if (p.video.readyState < 3 || p.video.error) return;
                // A truncated stream ends far before the episode does. Advancing here is
                // what tore down the stream the viewer asked for and jumped episodes.
                const dbDuration = p.totalDuration || 0;
                const absoluteEnd = (p.streamStartOffset || 0) + d;
                if (dbDuration > 0 && absoluteEnd < dbDuration * 0.9) {
                    console.warn('[SimplePlayer] Stream ended at ' + Math.round(absoluteEnd)
                        + 's of a ' + Math.round(dbDuration) + 's episode — truncated stream, not an episode end');
                    p._truncatedStream = true;
                    if (p.streamMgr && p.streamMgr.fallbackToDirectStream) {
                        p.streamMgr.fallbackToDirectStream(p.streamStartOffset || 0);
                    }
                    return;
                }
                dbg('[SimplePlayer] Video ended, playing next episode...');
                p.navMgr.playNextEpisode();
            };

            p.clickOverlay.onclick = toggle;
            p.bigPlay.onclick = toggle;
            p.playBtn.onclick = toggle;
            if (p.backBtn) p.backBtn.onclick = (e) => { e.stopPropagation(); p.navMgr.goBack(); };
            p.prevBtn.onclick = (e) => { e.stopPropagation(); p.navMgr.playPreviousEpisode(); };
            p.nextBtn.onclick = (e) => { e.stopPropagation(); p.navMgr.playNextEpisode(); };
            p.container.querySelector('#videoTitleLink').onclick = (e) => { e.stopPropagation(); p.navMgr.goToDetails(); };

            p.container.querySelectorAll('.skip-btn').forEach(btn => {
                btn.onclick = (e) => {
                    e.stopPropagation();
                    const skipAmount = parseFloat(btn.dataset.skip);

                    const currentDisplayTime = p.video.currentTime + (p.streamStartOffset || 0);
                    const newTime = Math.max(0, currentDisplayTime + skipAmount);

                    if (p.needsTranscode) {
                        p.streamMgr.performServerSeek(newTime);
                    } else {
                        p.video.currentTime = newTime - (p.streamStartOffset || 0);
                    }

                    p.controlsManager.showControls();
                };
            });

            p.volSlider.oninput = (e) => {
                const val = parseFloat(e.target.value);
                p.state.volume = val;
                p.video.volume = Math.pow(val, 2);
                localStorage.setItem(p.volumeKey, val);
                p.controlsManager.updateVolumeUI();
            };

            p.muteBtn.onclick = (e) => {
                e.stopPropagation();
                p.video.muted = !p.video.muted;
                // Keep state in sync: applyInitialState() (loadedmetadata) re-applies
                // p.state.muted, which would otherwise undo the toggle on the next load.
                p.state.muted = p.video.muted;
                localStorage.setItem(p.muteKey, p.video.muted);
                p.controlsManager.updateVolumeUI();
            };

            p.progressContainer.onclick = (e) => {
                e.stopPropagation();
                const rect = p.progressContainer.getBoundingClientRect();

                let dur = p.totalDuration;
                if (!dur || dur === Infinity) {
                    dur = p.video.duration;
                }

                const seekTo = ((e.clientX - rect.left) / rect.width) * dur;

                p.streamMgr.performServerSeek(seekTo);
                p.controlsManager.showControls();
            };

            p.progressContainer.onmousemove = (e) => p.storyboardMgr.handleMouseMove(e);
            p.progressContainer.onmouseleave = () => p.preview.classList.remove('active');

            p.speedBtn.onclick = (e) => {
                e.stopPropagation();
                p.speedMenu.classList.toggle('active');
                p.subtitleMenu.classList.remove('active');
            };

            p.container.querySelectorAll('.speed-option').forEach(opt => {
                opt.onclick = (e) => {
                    e.stopPropagation();
                    const speed = parseFloat(opt.dataset.speed);
                    p.video.playbackRate = speed;
                    p.state.playbackRate = speed;
                    p.speedValue.innerText = speed.toFixed(1) + 'x';
                    p.container.querySelectorAll('.speed-option').forEach(o => o.classList.remove('active'));
                    opt.classList.add('active');
                    p.speedMenu.classList.remove('active');
                };
            });

            p.subtitleBtn.onclick = (e) => {
                e.stopPropagation();
                p.subtitleMenu.classList.toggle('active');
                p.speedMenu.classList.remove('active');
                if (p.subtitleMenu.classList.contains('active')) {
                    p.controlsManager.switchSettingsPage('main');
                }
            };

            p.subtitleMenu.addEventListener('click', (e) => {
                const item = e.target.closest('.settings-item');
                if (item) {
                    e.stopPropagation();
                    if (item.dataset.page === 'quality') {
                        p.container.querySelectorAll('.quality-btn').forEach(b => {
                            b.classList.toggle('active', parseInt(b.dataset.quality) === p._preferredQuality);
                        });
                    }
                    p.controlsManager.switchSettingsPage(item.dataset.page);
                    return;
                }
                const back = e.target.closest('.settings-back');
                if (back) {
                    e.stopPropagation();
                    p.controlsManager.switchSettingsPage('main');
                    return;
                }
                const manageBtn = e.target.closest('#manageSubtitlesBtn');
                if (manageBtn) {
                    e.stopPropagation();
                    p.subtitleMenu.classList.remove('active');
                    if (window.subtitleManager) {
                        window.subtitleManager.openModal(p.videoId, p.container.dataset.title, p.container.dataset.path);
                    }
                    return;
                }

                const qualityBtn = e.target.closest('.quality-btn');
                if (qualityBtn) {
                    e.stopPropagation();
                    if (p._qualitySwitching) return;
                    p._qualitySwitching = true;
                    // F1: Safety — reset the flag if the reload stalls or errors out
                    var _resetQualityFlag = function() { p._qualitySwitching = false; };
                    p.video.addEventListener('playing', _resetQualityFlag, { once: true });
                    p.video.addEventListener('error', _resetQualityFlag, { once: true });
                    p._qualityResetTimer = setTimeout(() => { p._qualityResetTimer = null; _resetQualityFlag(); }, 10000);

                    (async () => {
                        try {
                            const quality = parseInt(qualityBtn.dataset.quality);
                            const label = qualityBtn.textContent.trim();
                            p._previousQuality = p._preferredQuality;
                            p._preferredQuality = quality;
                            p.container.querySelectorAll('.quality-btn').forEach(b => b.classList.remove('active'));
                            qualityBtn.classList.add('active');
                            if (window.Toast) window.Toast.info('Quality: ' + label);

                            const absTime = p.video.currentTime + (p.streamStartOffset || 0);
                            // Local HLS session: recreate the session at the new quality
                            // (the server transcodes the new quality) instead of a transcode seek.
                            if (p._hlsSessionId || p._hlsSessionPending) {
                                p.streamMgr.recreateLocalHlsSession(absTime);
                                return;
                            }
                            p.streamMgr.performServerSeek(absTime);
                        } catch (err) {
                            console.error('[SimplePlayer] Quality switch failed:', err);
                            if (window.Toast) window.Toast.error('Quality switch failed, reverting...');
                            p._preferredQuality = p._previousQuality;
                            p.container.querySelectorAll('.quality-btn').forEach(b => b.classList.remove('active'));
                            const fallbackBtn = p.container.querySelector(`.quality-btn[data-quality="${p._preferredQuality}"]`);
                            if (fallbackBtn) fallbackBtn.classList.add('active');
                        }
                    })();
                    return;
                }
            });

            p.container.querySelector('#subMinusBtn').onclick = (e) => {
                e.stopPropagation();
                if (window.subtitleManager) window.subtitleManager.adjustCorrection(-0.2);
            };

            p.container.querySelector('#subPlusBtn').onclick = (e) => {
                e.stopPropagation();
                if (window.subtitleManager) window.subtitleManager.adjustCorrection(0.2);
            };

            const correctionVal = p.container.querySelector('#subCorrectionVal');
            if (correctionVal) {
                correctionVal.onclick = (e) => {
                    e.stopPropagation();
                    const currentVal = parseFloat(localStorage.getItem('jmedia_subtitle_correction') || '0');
                    const input = document.createElement('input');
                    input.type = 'number';
                    input.step = '0.1';
                    input.value = currentVal.toFixed(1);
                    input.className = 'correction-input';
                    input.style.cssText = 'width: 70px; text-align: center; background: #333; color: white; border: 1px solid #48c774; border-radius: 4px; padding: 4px; font-family: monospace; font-size: 0.9rem;';

                    const saveValue = () => {
                        let val = parseFloat(input.value) || 0;
                        val = Math.round(val * 10) / 10;
                        localStorage.setItem('jmedia_subtitle_correction', val);
                        correctionVal.textContent = (val >= 0 ? '+' : '') + val.toFixed(1) + 's';
                        if (window.currentPlayerInstance) {
                            window.currentPlayerInstance.loadSubtitles(true);
                        }
                    };

                    input.onblur = () => {
                        saveValue();
                        if (correctionVal.parentNode) {
                            correctionVal.parentNode.replaceChild(correctionVal, input);
                        }
                    };

                    input.onkeydown = (ke) => {
                        if (ke.key === 'Enter') {
                            ke.preventDefault();
                            input.blur();
                        } else if (ke.key === 'Escape') {
                            ke.preventDefault();
                            input.value = currentVal.toFixed(1);
                            input.blur();
                        }
                    };

                    correctionVal.parentNode.replaceChild(input, correctionVal);
                    input.focus();
                    input.select();
                };
            }

            p.container.querySelector('#subResetBtn').onclick = (e) => {
                e.stopPropagation();
                localStorage.setItem('jmedia_subtitle_correction', 0);
                const valEl = document.getElementById('subCorrectionVal');
                if (valEl) valEl.textContent = '0.0s';
                if (window.currentPlayerInstance) {
                    window.currentPlayerInstance.loadSubtitles(true);
                }
            };

            p.fullscreenBtn.onclick = (e) => {
                if (p.utils.isIOS()) console.debug('[iOS-DEBUG] Fullscreen requested via button click');
                p.fullscreenMgr.requestFullscreen(e);
            };

            // Stored on the player and removed in destroy(): these are document/window
            // level listeners, so without teardown every episode switch left another
            // player reacting to fullscreen and orientation changes.
            p._boundFsChange = () => {
                if (p._isAlive && !p._isAlive()) return;
                p.fullscreenMgr.onFullscreenChange();
            };
            document.addEventListener('fullscreenchange', p._boundFsChange);
            document.addEventListener('webkitfullscreenchange', p._boundFsChange);

            p.video.addEventListener('webkitbeginfullscreen', () => p.fullscreenMgr.onIOSVideoFullscreenStart());
            p.video.addEventListener('webkitendfullscreen', () => p.fullscreenMgr.onIOSVideoFullscreenEnd());

            if (screen.orientation) {
                p._boundOrientationChange = () => {
                    if (p._isAlive && !p._isAlive()) return;
                    if (p.container.classList.contains('is-css-fullscreen')) {
                        window.scrollTo(0, 0);
                    }
                };
                screen.orientation.addEventListener('change', p._boundOrientationChange);
            }

            const handleSkipClick = (e, section) => p.skipController.handleSkipClick(e, section);
            p.container.querySelector('#skipIntroBtn').onclick = (e) => handleSkipClick(e, 'intro');
            p.container.querySelector('#skipRecapBtn').onclick = (e) => handleSkipClick(e, 'recap');
            p.container.querySelector('#skipOutroBtn').onclick = (e) => handleSkipClick(e, 'outro');

            p.container.querySelectorAll('.skip-auto-toggle').forEach(toggle => {
                toggle.onclick = (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    const section = toggle.dataset.section;
                    const newState = !toggle.classList.contains('active');
                    toggle.classList.toggle('active', newState);
                    p['autoSkip' + section.charAt(0).toUpperCase() + section.slice(1)] = newState;
                    p.stateMgr._postAutoSkipSetting(section, newState);
                };
            });

            p.autoSkipUndoBtn = document.getElementById('autoSkipUndoBtn');
            p.autoSkipToggleBtn = document.getElementById('autoSkipToggleBtn');
            p.autoSkipNotice = document.getElementById('autoSkipNotice');
            p.autoSkipNoticeText = document.getElementById('autoSkipNoticeText');

            if (p.autoSkipUndoBtn) {
                p.autoSkipUndoBtn.onclick = () => p.skipController._undoAutoSkip();
            }
            if (p.autoSkipToggleBtn) {
                p.autoSkipToggleBtn.onclick = () => {
                    if (p._autoSkipSection) {
                        p.skipController._disableAutoSkip(p._autoSkipSection);
                    }
                };
            }

            p.container.onmousemove = () => p.controlsManager.showControls();
            p.container.ontouchstart = () => p.controlsManager.showControls();

            p.debugInfo = {
                seriesTitle: p.container.dataset.seriesTitle || '',
                seasonNumber: parseInt(p.container.dataset.seasonNumber || '1'),
                episodeNumber: parseInt(p.container.dataset.episodeNumber || '0'),
                seriesImdbId: p.container.dataset.seriesImdbId || ''
            };

            p._bigPlayGuardTimer = setInterval(function() {
                if (p._destroyed) { clearInterval(p._bigPlayGuardTimer); return; }
                if (p.bigPlay.style.display !== 'none' && p.video.readyState >= 2 && !p.video.paused) {
                    p.bigPlay.style.display = 'none';
                }
            }, 2000);

            p.controlsManager.showControls();
            p.controlsManager.updatePageTitle();
        }
    };
})(window);
