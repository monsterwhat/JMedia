(function(window) {
    'use strict';

    class VideoRemoteController {
        constructor({ containerId, profileId }) {
            this.container = document.getElementById(containerId);
            if (!this.container) return;

            this.profileId = profileId;
            this.deviceToken = sessionStorage.getItem('jmedia_device_token') || '';
            this.state = {};
            this._pollInterval = null;

            this._lastServerTime = 0;
            this._lastRenderTime = 0;
            this._localTime = 0;
            this._playing = false;
            this._duration = 0;
            this._animFrameId = null;

            this._dragging = false;
            this._progressHandlers = null;

            this._episodesCache = {};
            this._currentSeriesKey = null;

            this._buttonHandlers = {};
            this._subtitleOffset = 0;

            this.init();
        }

        init() {
            this._subtitleOffset = parseFloat(localStorage.getItem('jmedia_subtitle_correction') || '0');
            this.buildUI();
            this._initProgressDrag();
            this._startAnimationLoop();

            this._pollInterval = setInterval(async () => {
                try {
                    const resp = await fetch('/api/video/playback/current' + (this.profileId ? '?profileId=' + this.profileId : ''));
                    if (!resp.ok) throw new Error('HTTP ' + resp.status);
                    const data = await resp.json();
                    this.updateState(data.video != null ? data.video : null);
                    this._hideDisconnected();
                } catch (err) {
                    this._showDisconnected();
                }
            }, 300);
        }

        buildUI() {
            this.container.innerHTML = `
                <div class="remote-panel">
                    <div class="remote-header">
                        <div class="remote-series-label"></div>
                        <div class="remote-title-label"></div>
                    </div>

                    <div class="remote-progress">
                        <span class="remote-time-current">0:00</span>
                        <div class="remote-progress-bar">
                            <div class="remote-progress-fill"></div>
                        </div>
                        <span class="remote-time-duration">0:00</span>
                    </div>

                    <div class="remote-controls">
                        <button class="remote-btn remote-btn-skip remote-rw30" title="Rewind 30s">
                            <svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor">
                                <path d="M11 18V6l-8.5 6 8.5 6zm.5-6l8.5 6V6l-8.5 6z"/>
                            </svg>
                            <span class="remote-btn-label">30s</span>
                        </button>
                        <button class="remote-btn remote-btn-skip remote-rw15" title="Rewind 15s">
                            <svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor">
                                <path d="M12.5 8V4l-7 7 7 7v-4.1c5 0 8.5 1.6 11 5.1-1-5-4-10-11-11z"/>
                            </svg>
                            <span class="remote-btn-label">15s</span>
                        </button>
                        <button class="remote-btn remote-play" title="Play/Pause">
                            <svg class="remote-play-svg" viewBox="0 0 24 24" width="28" height="28">
                                <polygon class="play-icon" points="8,4 20,12 8,20" fill="currentColor"/>
                                <g class="pause-icon" style="display:none">
                                    <rect x="6" y="4" width="4" height="16" rx="1" fill="currentColor"/>
                                    <rect x="14" y="4" width="4" height="16" rx="1" fill="currentColor"/>
                                </g>
                            </svg>
                        </button>
                        <button class="remote-btn remote-btn-skip remote-ff15" title="Forward 15s">
                            <svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor">
                                <path d="M11.5 8V4l7 7-7 7v-4.1c-5 0-8.5 1.6-11 5.1 1-5 4-10 11-11z"/>
                            </svg>
                            <span class="remote-btn-label">15s</span>
                        </button>
                        <button class="remote-btn remote-btn-skip remote-ff30" title="Forward 30s">
                            <svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor">
                                <path d="M4 18l8.5-6L4 6v12zm9-12v12l8.5-6L13 6z"/>
                            </svg>
                            <span class="remote-btn-label">30s</span>
                        </button>
                    </div>

                    <div class="remote-selectors">
                        <div class="remote-selector">
                            <button class="remote-selector-toggle">
                                <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor">
                                    <path d="M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H5.17L4 17.17V4h16v12z"/>
                                </svg>
                                Subtitles ▾
                            </button>
                            <div class="remote-selector-list remote-subtitle-list"></div>
                        </div>
                        <div class="remote-subtitle-offset" style="display:flex;align-items:center;gap:8px;padding:4px 0;">
                            <label style="font-size:12px;color:#aaa;white-space:nowrap;">Offset</label>
                            <div class="remote-offset-controls" style="display:flex;align-items:center;gap:6px;">
                                <button class="remote-btn remote-offset-minus" title="Subtitle earlier" style="width:28px;height:28px;border-radius:50%;border:1px solid #555;background:#333;color:#ccc;font-size:16px;line-height:1;cursor:pointer;">−</button>
                                <span class="remote-offset-value" style="font-size:12px;color:#ccc;min-width:40px;text-align:center;font-family:monospace;">0.0s</span>
                                <button class="remote-btn remote-offset-plus" title="Subtitle later" style="width:28px;height:28px;border-radius:50%;border:1px solid #555;background:#333;color:#ccc;font-size:16px;line-height:1;cursor:pointer;">+</button>
                            </div>
                        </div>
                        <div class="remote-selector">
                            <button class="remote-selector-toggle">
                                <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor">
                                    <path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"/>
                                </svg>
                                Audio ▾
                            </button>
                            <div class="remote-selector-list remote-audio-list"></div>
                        </div>
                    </div>

                    <div class="remote-episodes-section" style="display:none;">
                        <button class="remote-episodes-toggle">Episodes ▾</button>
                        <div class="remote-episodes-list"></div>
                    </div>
                </div>

                <div class="remote-disconnected" style="display:none;">
                    <div class="remote-disconnected-content">
                        <div class="remote-disconnected-icon">
                            <svg viewBox="0 0 48 48" width="48" height="48" fill="currentColor">
                                <circle cx="24" cy="36" r="3"/>
                                <path d="M13 28a15 15 0 0 1 22 0" stroke="currentColor" stroke-width="2.5" fill="none" stroke-linecap="round"/>
                                <path d="M7 22a22 22 0 0 1 34 0" stroke="currentColor" stroke-width="2.5" fill="none" stroke-linecap="round"/>
                            </svg>
                        </div>
                        <div class="remote-disconnected-text">Connection lost</div>
                        <div class="remote-disconnected-sub">Reconnecting...</div>
                    </div>
                </div>
            `;

            this.seriesLabel = this.container.querySelector('.remote-series-label');
            this.titleLabel = this.container.querySelector('.remote-title-label');
            this.timeCurrent = this.container.querySelector('.remote-time-current');
            this.timeDuration = this.container.querySelector('.remote-time-duration');
            this.progressFill = this.container.querySelector('.remote-progress-fill');
            this.progressBar = this.container.querySelector('.remote-progress-bar');
            this.playBtn = this.container.querySelector('.remote-play');
            this.playIcon = this.container.querySelector('.play-icon');
            this.pauseIcon = this.container.querySelector('.pause-icon');
            this.subtitleList = this.container.querySelector('.remote-subtitle-list');
            this.audioList = this.container.querySelector('.remote-audio-list');
            this.disconnectedOverlay = this.container.querySelector('.remote-disconnected');
            this.episodesSection = this.container.querySelector('.remote-episodes-section');
            this.episodesToggle = this.container.querySelector('.remote-episodes-toggle');
            this.episodesList = this.container.querySelector('.remote-episodes-list');
            this.offsetValue = this.container.querySelector('.remote-offset-value');

            this._buttonHandlers = {
                rw30: () => this.seekRelative(-30),
                rw15: () => this.seekRelative(-15),
                ff15: () => this.seekRelative(15),
                ff30: () => this.seekRelative(30),
                play: () => this.sendCommand('toggle-play', {}),
                episodesToggle: () => {
                    const isOpen = this.episodesList.classList.toggle('open');
                    this.episodesToggle.textContent = isOpen ? 'Episodes \u25B4' : 'Episodes \u25BE';
                },
                selectorToggle: (e) => {
                    const list = e.currentTarget.nextElementSibling;
                    list.classList.toggle('open');
                }
            };

            this.container.querySelector('.remote-rw30').addEventListener('click', this._buttonHandlers.rw30);
            this.container.querySelector('.remote-rw15').addEventListener('click', this._buttonHandlers.rw15);
            this.container.querySelector('.remote-ff15').addEventListener('click', this._buttonHandlers.ff15);
            this.container.querySelector('.remote-ff30').addEventListener('click', this._buttonHandlers.ff30);
            this.playBtn.addEventListener('click', this._buttonHandlers.play);
            this.episodesToggle.addEventListener('click', this._buttonHandlers.episodesToggle);

            this.container.querySelectorAll('.remote-selector-toggle').forEach(btn => {
                btn.addEventListener('click', this._buttonHandlers.selectorToggle);
            });

            this.container.querySelector('.remote-offset-minus').addEventListener('click', () => this.adjustSubtitleOffset(-0.5));
            this.container.querySelector('.remote-offset-plus').addEventListener('click', () => this.adjustSubtitleOffset(0.5));

            if (this.offsetValue) {
                this.offsetValue.textContent = (this._subtitleOffset >= 0 ? '+' : '') + this._subtitleOffset.toFixed(1) + 's';
            }
        }

        _initProgressDrag() {
            const bar = this.progressBar;

            const getTimeFromEvent = (e) => {
                const rect = bar.getBoundingClientRect();
                const clientX = e.touches ? e.touches[0].clientX : e.clientX;
                const pct = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
                return pct * (this._duration || 0);
            };

            const onStart = (e) => {
                if (!this._duration) return;
                this._dragging = true;
                bar.classList.add('dragging');
                this._updateProgressDisplay(getTimeFromEvent(e));
                e.preventDefault();
            };

            const onMove = (e) => {
                if (!this._dragging) return;
                this._updateProgressDisplay(getTimeFromEvent(e));
                e.preventDefault();
            };

            const onEnd = (e) => {
                if (!this._dragging) return;
                this._dragging = false;
                bar.classList.remove('dragging');
                let time;
                if (e.changedTouches && e.changedTouches.length) {
                    const rect = bar.getBoundingClientRect();
                    const pct = Math.max(0, Math.min(1, (e.changedTouches[0].clientX - rect.left) / rect.width));
                    time = pct * (this._duration || 0);
                } else {
                    time = getTimeFromEvent(e);
                }
                this.sendCommand('seek', { value: time });
                this.state.currentTime = time;
                this._lastServerTime = time;
                this._localTime = time;
                this._lastRenderTime = performance.now();
                this._updateProgressDisplay(time);
            };

            bar.addEventListener('touchstart', onStart, { passive: false });
            bar.addEventListener('mousedown', onStart);
            window.addEventListener('touchmove', onMove, { passive: false });
            window.addEventListener('mousemove', onMove);
            window.addEventListener('touchend', onEnd);
            window.addEventListener('mouseup', onEnd);

            this._progressHandlers = { start: onStart, move: onMove, end: onEnd };
        }

        _startAnimationLoop() {
            const tick = () => {
                if (this._playing && !this._dragging) {
                    const now = performance.now();
                    this._localTime = this._lastServerTime + (now - this._lastRenderTime) / 1000;
                    if (this._duration > 0) {
                        this._localTime = Math.min(this._localTime, this._duration);
                    }
                    this._updateProgressDisplay(this._localTime);
                }
                this._animFrameId = requestAnimationFrame(tick);
            };
            this._animFrameId = requestAnimationFrame(tick);
        }

        _stopAnimationLoop() {
            if (this._animFrameId != null) {
                cancelAnimationFrame(this._animFrameId);
                this._animFrameId = null;
            }
        }

        updateState(incoming) {
            if (!incoming) return;

            const prevId = this.state.currentVideoId;
            let videoChanged = false;

            if (incoming.currentVideo) {
                const v = incoming.currentVideo;
                if (v.id != null && v.id !== this.state.currentVideoId) {
                    this.state.currentVideoId = v.id;
                    videoChanged = true;
                }
                if (v.title != null) this.state.title = v.title;
                if (v.duration != null) this.state.duration = this._normalizeDuration(v.duration);
                if (v.seriesTitle != null) this.state.seriesTitle = v.seriesTitle;
                if (v.episodeTitle != null) this.state.episodeTitle = v.episodeTitle;
                if (v.seasonNumber != null) this.state.seasonNumber = v.seasonNumber;
                if (v.episodeNumber != null) this.state.episodeNumber = v.episodeNumber;
                if (incoming.playing != null) this.state.playing = incoming.playing;
                if (incoming.currentTime != null) this.state.currentTime = incoming.currentTime;
                if (incoming.availableSubtitleTracks && incoming.availableSubtitleTracks.length > 0) {
                    this.state.availableSubtitleTracks = incoming.availableSubtitleTracks;
                }
                if (incoming.availableAudioTracks && incoming.availableAudioTracks.length > 0) {
                    this.state.availableAudioTracks = incoming.availableAudioTracks;
                }
                if (incoming.activeSubtitleTrackIndex != null) this.state.activeSubtitleTrackIndex = incoming.activeSubtitleTrackIndex;
                if (incoming.activeAudioTrackIndex != null) this.state.activeAudioTrackIndex = incoming.activeAudioTrackIndex;
            } else if (incoming.currentVideoId != null) {
                if (incoming.currentVideoId !== this.state.currentVideoId) {
                    this.state.currentVideoId = incoming.currentVideoId;
                    videoChanged = true;
                }
                if (incoming.playing != null) this.state.playing = incoming.playing;
                if (incoming.currentTime != null) this.state.currentTime = incoming.currentTime;
                if (incoming.title != null) this.state.title = incoming.title;
                if (incoming.duration != null) this.state.duration = this._normalizeDuration(incoming.duration);
                if (incoming.seriesTitle != null) this.state.seriesTitle = incoming.seriesTitle;
                if (incoming.episodeTitle != null) this.state.episodeTitle = incoming.episodeTitle;
                if (incoming.seasonNumber != null) this.state.seasonNumber = incoming.seasonNumber;
                if (incoming.episodeNumber != null) this.state.episodeNumber = incoming.episodeNumber;
                if (incoming.availableSubtitleTracks && incoming.availableSubtitleTracks.length > 0) {
                    this.state.availableSubtitleTracks = incoming.availableSubtitleTracks;
                }
                if (incoming.availableAudioTracks && incoming.availableAudioTracks.length > 0) {
                    this.state.availableAudioTracks = incoming.availableAudioTracks;
                }
                if (incoming.activeSubtitleTrackIndex != null) this.state.activeSubtitleTrackIndex = incoming.activeSubtitleTrackIndex;
                if (incoming.activeAudioTrackIndex != null) this.state.activeAudioTrackIndex = incoming.activeAudioTrackIndex;
            } else {
                return;
            }

            this._lastServerTime = this.state.currentTime || 0;
            this._lastRenderTime = performance.now();
            this._localTime = this._lastServerTime;
            this._playing = !!this.state.playing;
            this._duration = this.state.duration || 0;

            this._updateHeader();
            this._updatePlayIcon();
            this._updateProgressDisplay(this._localTime);
            this._renderTrackSelectors();

            if (videoChanged) {
                if (this.state.seriesTitle && this.state.seasonNumber) {
                    this._fetchEpisodes(this.state.seriesTitle, this.state.seasonNumber);
                } else {
                    this.episodesSection.style.display = 'none';
                }
            }
        }

        _normalizeDuration(raw) {
            if (typeof raw !== 'number' || isNaN(raw)) return 0;
            return raw;
        }

        _updateHeader() {
            const s = this.state;
            if (s.seriesTitle && s.seasonNumber > 0 && s.episodeNumber) {
                this.seriesLabel.textContent = s.seriesTitle;
                this.titleLabel.textContent = 'S' + s.seasonNumber + 'E' + s.episodeNumber +
                    (s.episodeTitle ? ' \u2014 ' + s.episodeTitle : '');
            } else {
                this.seriesLabel.textContent = '';
                this.titleLabel.textContent = s.title || '';
            }
        }

        _updatePlayIcon() {
            if (this._playing) {
                this.playIcon.style.display = 'none';
                this.pauseIcon.style.display = '';
            } else {
                this.playIcon.style.display = '';
                this.pauseIcon.style.display = 'none';
            }
        }

        _updateProgressDisplay(time) {
            const dur = this._duration || 0;
            this.timeCurrent.textContent = this.formatTime(time);
            this.timeDuration.textContent = this.formatTime(dur);
            this.progressFill.style.width = (dur > 0 ? Math.min(100, (time / dur) * 100) : 0) + '%';
        }

        _renderTrackSelectors() {
            const s = this.state;
            if (s.availableSubtitleTracks) {
                this.renderTrackList(this.subtitleList, s.availableSubtitleTracks, s.activeSubtitleTrackIndex, 'select-subtitle');
            }
            if (s.availableAudioTracks) {
                this.renderTrackList(this.audioList, s.availableAudioTracks, s.activeAudioTrackIndex, 'select-audio');
            }
        }

        renderTrackList(container, tracks, activeIndex, commandType) {
            container.innerHTML = '';
            tracks.forEach((track, i) => {
                const item = document.createElement('div');
                item.className = 'remote-selector-item' + (i === activeIndex ? ' active' : '');
                item.textContent = track.label || track.language || 'Track ' + (i + 1);
                item.addEventListener('click', () => {
                    this.sendCommand(commandType, { index: i });
                    container.querySelectorAll('.remote-selector-item').forEach(el => el.classList.remove('active'));
                    item.classList.add('active');
                    container.classList.remove('open');
                });
                container.appendChild(item);
            });
        }

        async _fetchEpisodes(seriesTitle, seasonNumber) {
            const cacheKey = seriesTitle + ':' + seasonNumber;
            if (this._currentSeriesKey === cacheKey && this._episodesCache[cacheKey]) {
                this._renderEpisodes(this._episodesCache[cacheKey]);
                this.episodesSection.style.display = '';
                return;
            }

            try {
                const encoded = encodeURIComponent(seriesTitle);
                const resp = await fetch('/api/video/shows/' + encoded + '/seasons/' + seasonNumber + '/episodes');
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                const data = await resp.json();
                const episodes = data.episodes || [];
                this._episodesCache[cacheKey] = episodes;
                this._currentSeriesKey = cacheKey;
                this._renderEpisodes(episodes);
                this.episodesSection.style.display = episodes.length > 0 ? '' : 'none';
            } catch (err) {
                console.error('[VideoRemoteController] Failed to fetch episodes:', err);
                this.episodesSection.style.display = 'none';
            }
        }

        _renderEpisodes(episodes) {
            this.episodesList.innerHTML = '';
            const currentId = this.state.currentVideoId;
            episodes.forEach(ep => {
                const item = document.createElement('div');
                item.className = 'remote-episode-card' + (ep.id === currentId ? ' active' : '');

                const epNum = ep.episodeNumber || 0;
                const epTitle = ep.episodeTitle || ep.title || '';
                const dur = ep.duration ? Math.floor(ep.duration / 1000) : 0;
                const progress = ep.watchProgressPercent || 0;

                item.innerHTML =
                    '<div class="remote-episode-thumb">' +
                        '<img src="/api/video/thumbnail/' + ep.id + '" alt="" loading="lazy">' +
                        (progress > 0 && progress < 95 ?
                            '<div class="remote-episode-progress"><div class="remote-episode-progress-bar" style="width:' + progress + '%"></div></div>' : '') +
                        '<div class="remote-episode-play-overlay"><svg viewBox="0 0 24 24" width="28" height="28" fill="white"><polygon points="8,4 20,12 8,20"/></svg></div>' +
                    '</div>' +
                    '<div class="remote-episode-info">' +
                        '<span class="remote-episode-badge">E' + epNum + '</span>' +
                        '<span class="remote-episode-title">' + epTitle + '</span>' +
                        (dur > 0 ? '<span class="remote-episode-duration">' + this.formatTime(dur) + '</span>' : '') +
                    '</div>';

                item.addEventListener('click', () => this._selectEpisode(ep.id));
                this.episodesList.appendChild(item);
            });
        }

        _selectEpisode(videoId) {
            this.sendCommand('play-video', { videoId: videoId });
        }

        sendCommand(type, payload) {
            const opts = { method: 'POST' };
            let url = '/api/video/playback/';

            switch (type) {
                case 'toggle-play':
                    url += 'toggle';
                    break;
                case 'seek':
                    url += 'seek/' + encodeURIComponent(payload.value);
                    break;
                case 'next':
                    url += 'next';
                    break;
                case 'previous':
                    url += 'previous';
                    break;
                case 'play-video':
                    url += 'play/' + encodeURIComponent(payload.videoId);
                    break;
                case 'select-subtitle':
                    opts.headers = { 'Content-Type': 'application/json' };
                    opts.body = JSON.stringify({ index: payload.index });
                    url += 'select-subtitle';
                    break;
                case 'select-audio':
                    opts.headers = { 'Content-Type': 'application/json' };
                    opts.body = JSON.stringify({ index: payload.index });
                    url += 'select-audio';
                    break;
                case 'set-subtitle-offset':
                    opts.headers = { 'Content-Type': 'application/json' };
                    opts.body = JSON.stringify({ offset: payload.offset });
                    url += 'set-subtitle-offset';
                    break;
                default:
                    console.warn('[VideoRemoteController] Unknown command:', type);
                    return;
            }

            fetch(url, opts).catch(err =>
                console.error('[VideoRemoteController] Command failed:', type, err)
            );
        }

        seekRelative(seconds) {
            const currentTime = this.state.currentTime || 0;
            const dur = this.state.duration || 0;
            const newTime = Math.max(0, Math.min(dur, currentTime + seconds));
            this.sendCommand('seek', { value: newTime });
            // Optimistically update local state so UI reflects seek immediately
            this.state.currentTime = newTime;
            this._lastServerTime = newTime;
            this._localTime = newTime;
            this._lastRenderTime = performance.now();
            this._updateProgressDisplay(newTime);
        }

        adjustSubtitleOffset(delta) {
            this._subtitleOffset = Math.round((this._subtitleOffset + delta) * 10) / 10;
            this._subtitleOffset = Math.max(-5, Math.min(5, this._subtitleOffset));
            localStorage.setItem('jmedia_subtitle_correction', this._subtitleOffset);
            if (this.offsetValue) {
                this.offsetValue.textContent = (this._subtitleOffset >= 0 ? '+' : '') + this._subtitleOffset.toFixed(1) + 's';
            }
            this.sendCommand('set-subtitle-offset', { offset: this._subtitleOffset });
        }

        formatTime(seconds) {
            if (!seconds || seconds < 0) return '0:00';
            const h = Math.floor(seconds / 3600);
            const m = Math.floor((seconds % 3600) / 60);
            const s = Math.floor(seconds % 60);
            if (h > 0) return h + ':' + String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
            return m + ':' + String(s).padStart(2, '0');
        }

        _showDisconnected() {
            if (this.disconnectedOverlay) this.disconnectedOverlay.style.display = '';
        }

        _hideDisconnected() {
            if (this.disconnectedOverlay) this.disconnectedOverlay.style.display = 'none';
        }

        destroy() {
            this._stopAnimationLoop();

            if (this._pollInterval) {
                clearInterval(this._pollInterval);
                this._pollInterval = null;
            }

            if (this._progressHandlers) {
                this.progressBar.removeEventListener('touchstart', this._progressHandlers.start);
                this.progressBar.removeEventListener('mousedown', this._progressHandlers.start);
                window.removeEventListener('touchmove', this._progressHandlers.move);
                window.removeEventListener('mousemove', this._progressHandlers.move);
                window.removeEventListener('touchend', this._progressHandlers.end);
                window.removeEventListener('mouseup', this._progressHandlers.end);
            }

            if (this.container) this.container.innerHTML = '';
        }
    }

    window.VideoRemoteController = VideoRemoteController;
})(window);
