(function(window) {
    'use strict';

    window.PlayerEventBinder = class {
        constructor(player) {
            this.player = player;
        }

        bind() {
            const p = this.player;
            const toggle = (e) => {
                if (e) e.stopPropagation();
                if (p.video.paused) p.video.play().catch(() => {});
                else p.video.pause();
                p.controlsManager.showControls();
            };

            p.video.addEventListener('play', () => {
                if (p.utils.isIOS()) console.debug('[iOS-DEBUG] Play event fired, setting 20s stall timer');
                p.playIcon.className = 'pi pi-pause';
                p.bigPlay.style.display = 'none';
                p.container.classList.remove('paused');
                p.controlsManager.showControls();
                if (p._stallTimer) clearTimeout(p._stallTimer);
                p._stallTimer = setTimeout(() => {
                    if (p._destroyed) return;
                    if (p.video.currentTime === 0 && !p.video.paused && !p._hasPlayedData) {
                        if (p.utils.isIOS()) console.debug('[iOS-DEBUG] Stall timer fired: currentTime=' + p.video.currentTime + ' paused=' + p.video.paused + ' _hasPlayedData=' + p._hasPlayedData + ' _destroyed=' + p._destroyed + ' _streamFallbackCount=' + (p._streamFallbackCount || 0) + ' _maxStreamFallbacks=' + p._maxStreamFallbacks);
                        console.warn('[SimplePlayer] Playback stalled - no data received in 20s');
                        if (window.Toast) window.Toast.warning('Playback stuck - retrying...');

                        p.streamMgr.clearStreamErrorHandlers();

                        if (p._qualitySwitching) return;

                        p._streamFallbackCount = (p._streamFallbackCount || 0) + 1;
                        if (p._streamFallbackCount < p._maxStreamFallbacks) {
                            const saved = p.streamStartOffset || p.lastKnownGoodPosition || p.initialResumeTime || 0;
                            const qualityParam = p._preferredQuality > 0 ? `&quality=${p._preferredQuality}` : '';
                            const traceId = `${Date.now()}_${Math.random().toString(36).slice(2,8)}`;
                            p.video.src = `/api/video/stream/${p.videoId}.mp4?start=${saved}${qualityParam}&trace=${traceId}`;
                            if (p.utils.isIOS()) console.debug('[iOS-DEBUG] Stall timer reloading src:', p.video.src);
                            p.video.load();
                            p.video.play().catch(() => {});
                        } else {
                            if (window.Toast) window.Toast.error('Playback failed after retries - please reload');
                        }
                    }
                }, 20000);
                setTimeout(() => {
                    if (p._stallTimer && p.video.currentTime === 0 && !p._hasPlayedData) {
                        if (window.Toast) window.Toast.info('Server is preparing your video, please wait...');
                    }
                }, 10000);
            });

            p.video.addEventListener('pause', () => {
                p.playIcon.className = 'pi pi-play';
                p.bigPlay.style.display = 'flex';
                p.container.classList.add('paused');
                p.controlsManager.showControls();
                p.progressReporter.saveNow();
            });

            p.video.addEventListener('timeupdate', () => {
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
                    if (p._stallTimer) {
                        clearTimeout(p._stallTimer);
                        p._stallTimer = null;
                    }
                }
            });

            p.video.addEventListener('loadedmetadata', () => {
                const dur = p.video.duration;
                console.log(`[SimplePlayer] Metadata loaded. Stream duration: ${dur}s, DB duration: ${p.totalDuration}s`);

                if (dur && dur !== Infinity && dur > p.totalDuration && !p.streamStartOffset) {
                    p.totalDuration = dur;
                }

                p.controlsManager.applyInitialState();
                p.controlsManager.updateMarkers();

                if (p.initialResumeTime > 0 && (!p.streamStartOffset || p.streamStartOffset === 0)) {
                    console.log(`[SimplePlayer] Performing client-side seek to ${p.initialResumeTime}s`);
                    p.video.currentTime = p.initialResumeTime;
                    p.initialResumeTime = 0;
                }
            });

            p._waitingStart = 0;
            p._waitingTimer = null;
            p.video.onwaiting = () => {
                p.buffering.style.display = 'block';
                if (p._waitingStart === 0) p._waitingStart = Date.now();
                if (p._waitingTimer) return;
                p._waitingTimer = setTimeout(() => {
                    p._waitingTimer = null;
                    p._waitingStart = 0;
                    if (p._destroyed) return;
                    if (p._hasPlayedData && p.lastKnownGoodPosition > 0 && p.video.networkState !== HTMLMediaElement.NETWORK_LOADING) {
                        if (p.utils.isIOS()) console.debug('[iOS-DEBUG] 60s waiting stall: currentTime=' + p.video.currentTime + ' _hasPlayedData=' + p._hasPlayedData + ' lastKnownGoodPosition=' + p.lastKnownGoodPosition + ' networkState=' + p.video.networkState + ' _streamFallbackCount=' + (p._streamFallbackCount || 0));
                        console.warn('[SimplePlayer] Mid-playback stall detected (>60s), retrying at position', p.lastKnownGoodPosition);
                        p.streamMgr.clearStreamErrorHandlers();
                        p._streamFallbackCount = (p._streamFallbackCount || 0) + 1;
                        if (p._streamFallbackCount < p._maxStreamFallbacks) {
                            if (window.Toast) window.Toast.warning('Playback stalled - reconnecting... (' + p._streamFallbackCount + '/' + p._maxStreamFallbacks + ')');
                            const pos = p.lastKnownGoodPosition + (p.streamStartOffset || 0);
                            const qualityParam = p._preferredQuality > 0 ? `&quality=${p._preferredQuality}` : '';
                            p.video.src = `/api/video/stream/${p.videoId}.mp4?start=${pos}${qualityParam}`;
                            p.video.load();
                            p.video.play().catch(() => {});
                        } else {
                            if (window.Toast) window.Toast.error('Playback failed - please reload');
                        }
                    }
                }, 60000);
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
                console.log('[SimplePlayer] Video ended, playing next episode...');
                p.navMgr.playNextEpisode();
            };

            p.clickOverlay.onclick = toggle;
            p.bigPlay.onclick = toggle;
            p.playBtn.onclick = toggle;
            p.backBtn.onclick = (e) => { e.stopPropagation(); p.navMgr.goBack(); };
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

                if (p.needsTranscode) {
                    p.streamMgr.performServerSeek(seekTo);
                } else {
                    p.video.currentTime = seekTo - (p.streamStartOffset || 0);
                }
                p.video.dispatchEvent(new Event('timeupdate'));
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
                const playerOpt = e.target.closest('.player-option');
                if (playerOpt) {
                    e.stopPropagation();
                    var playerName = playerOpt.dataset.player;
                    if (playerName) {
                        p.container.querySelectorAll('.player-option').forEach(function(b) { b.style.borderColor = ''; b.style.color = ''; });
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

                const qualityBtn = e.target.closest('.quality-btn');
                if (qualityBtn) {
                    e.stopPropagation();
                    if (p._qualitySwitching) return;
                    p._qualitySwitching = true;

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

            document.addEventListener('fullscreenchange', () => p.fullscreenMgr.onFullscreenChange());
            document.addEventListener('webkitfullscreenchange', () => p.fullscreenMgr.onFullscreenChange());

            p.video.addEventListener('webkitbeginfullscreen', () => p.fullscreenMgr.onIOSVideoFullscreenStart());
            p.video.addEventListener('webkitendfullscreen', () => p.fullscreenMgr.onIOSVideoFullscreenEnd());

            if (screen.orientation) {
                screen.orientation.addEventListener('change', () => {
                    if (p.container.classList.contains('is-css-fullscreen')) {
                        window.scrollTo(0, 0);
                    }
                });
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

            p.controlsManager.showControls();
            p.controlsManager.updatePageTitle();
        }
    };
})(window);
