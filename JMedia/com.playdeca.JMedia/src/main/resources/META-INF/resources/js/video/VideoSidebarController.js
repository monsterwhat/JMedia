(function() {
    'use strict';
    const LOG = (...a) => console.log('[VSC]', ...a);

    const VideoSidebarController = class {
        constructor() {
            this.state = null;
            this.pollInterval = null;
            this.subtitleTracks = [];
            this.audioTracks = [];
            this._subSig = null;
            this._audSig = null;
            this._carouselVideoId = null;

            this.startPolling();
            LOG('constructed; poll started');

            window.__npIsRemoteController = true;
            window.npRemoteSwitchVideo = function(videoId) {
                if (!videoId) return;
                LOG('remoteSwitch videoId=' + videoId);
                fetch('/api/video/playback/play/' + encodeURIComponent(videoId), { method: 'POST' })
                    .then(function(r) { return r.ok; })
                    .then(function(ok) { if (ok) { LOG('remoteSwitch sent ok'); } })
                    .catch(function(e) { LOG('remoteSwitch failed', e); });
            };
        }

        startPolling() {
            this.fetchState();
            this.pollInterval = setInterval(() => this.fetchState(), 300);
        }

        async fetchState() {
            function structuralKey(st) {
                if (!st) return '';
                return [st.id, st.seriesTitle, st.episodeTitle, st.title, st.playing,
                        st.selectedSubtitleId, st.selectedSubtitleIndex, st.selectedAudioIndex,
                        st.duration].join('|');
            }
            try {
                const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
                const res = await fetch('/api/video/playback/current?profileId=' + encodeURIComponent(profileId));
                const data = await res.json();
                if (data.success) {
                    const newState = data.video;
                    this.state = newState;
                    this.renderTimeOnly();
                    const prevStructKey = this._lastStructKey || '';
                    const newStructKey = structuralKey(newState);
                    LOG('stateKeyChanged=' + (prevStructKey !== newStructKey) + ' id=' + (newState && newState.id) + ' seriesTitle=' + (newState && newState.seriesTitle));
                    if (prevStructKey !== newStructKey) {
                        this._lastStructKey = newStructKey;
                        this.render();
                        if (newState && newState.id) {
                            this.fetchTracks(newState.id);
                            if (newState.seriesTitle) {
                                this.fetchCarousel(newState.id);
                            } else {
                                var npc = document.getElementById('np-carousel');
                                if (npc) npc.innerHTML = '';
                                this._carouselVideoId = null;
                            }
                        }
                    }
                }
            } catch (e) {
                console.error('[VideoSidebarController] Poll error:', e);
            }
        }

        async fetchTracks(videoId) {
            try {
                const [subRes, audioRes] = await Promise.all([
                    fetch('/api/video/playback/subtitle-tracks?videoId=' + videoId),
                    fetch('/api/video/playback/audio-tracks?videoId=' + videoId)
                ]);
                const subData = await subRes.json();
                const audioData = await audioRes.json();
                this.subtitleTracks = subData.tracks || [];
                this.audioTracks = audioData.tracks || [];
                this.renderTracks();
            } catch (e) {
                console.error('[VideoSidebarController] Track fetch error:', e);
            }
        }

        async fetchCarousel(videoId) {
            try {
                if (this._carouselVideoId === videoId) {
                    var existing = document.getElementById('np-carousel');
                    if (existing && existing.children.length > 0) {
                        LOG('carousel skip (already loaded for ' + videoId + ')');
                        return;
                    }
                }
                this._carouselVideoId = videoId;
                var res = await fetch('/api/video/ui/now-playing-carousel?videoId=' + videoId);
                var html = await res.text();
                var el = document.getElementById('np-carousel');
                LOG('carousel bytes=' + html.length + ' #np-carousel exists=' + (!!el));
                if (el) el.innerHTML = html;
                this.centerNpCarousel();
            } catch (e) {
                LOG('Carousel fetch error:', e);
            }
        }

        centerNpCarousel() {
            var carousel = document.getElementById('npCarousel');
            var current = carousel && carousel.querySelector('.carousel-card-current');
            if (!carousel || !current) return;
            var prevSnap = carousel.style.scrollSnapType;
            carousel.style.scrollSnapType = 'none';
            var scrollTarget = current.offsetLeft - (carousel.clientWidth / 2) + (current.offsetWidth / 2);
            carousel.scrollLeft = Math.max(0, scrollTarget);
            carousel.style.scrollSnapType = prevSnap;
        }

        render() {
            if (this.state && this._carouselVideoId && this.state.id !== this._carouselVideoId) {
                this._carouselVideoId = null;
            }
            // Hero backdrop: (re)populate when the video id changes or a fresh fragment was injected
            var backdropEl = document.getElementById('np-hero-backdrop');
            if (!this.state || !this.state.id) {
                this._lastBackdropVideoId = null;
                if (backdropEl) backdropEl.style.display = 'none';
            } else if (this.state.id !== this._lastBackdropVideoId || !backdropEl || !backdropEl.src) {
                this._lastBackdropVideoId = this.state.id;
                if (backdropEl) {
                    var heroUrl = window.getHeroUrl ? window.getHeroUrl(this.state.id) : '/api/video/hero/' + this.state.id;
                    backdropEl.onload = function() { backdropEl.style.display = 'block'; };
                    backdropEl.onerror = function() { backdropEl.style.display = 'none'; };
                    backdropEl.src = heroUrl;
                }
            }
            var titleEl = document.getElementById('np-title');
            var subtitleEl = document.getElementById('np-subtitle');
            var controlsEl = document.getElementById('np-controls');
            var playPauseEl = document.getElementById('np-play-pause');
            var timeEl = document.getElementById('np-time');

            if (!this.state) {
                if (titleEl) titleEl.textContent = '';
                if (subtitleEl) subtitleEl.textContent = '';
                if (controlsEl) controlsEl.style.display = 'none';
                return;
            }

            var s = this.state;

            // Title and subtitle
            if (titleEl) {
                titleEl.textContent = s.seriesTitle || s.title || 'Unknown';
            }
            if (subtitleEl) {
                if (s.seriesTitle && s.episodeTitle) {
                    subtitleEl.textContent = s.episodeTitle;
                } else {
                    subtitleEl.textContent = '';
                }
            }

            // Controls: show/hide + play/pause icon
            if (controlsEl) {
                controlsEl.style.display = 'block';
            }
            if (playPauseEl) {
                playPauseEl.innerHTML = this.svgIcon(s.playing ? 'pause' : 'play');
            }

            // Seek button SVG icons (override PrimeIcons from HTML)
            var seekIconMap = {
                'np-rewind-30': 'rewind30',
                'np-rewind-15': 'rewind15',
                'np-forward-15': 'forward15',
                'np-forward-30': 'forward30'
            };
            Object.keys(seekIconMap).forEach(function(btnId) {
                var b = document.getElementById(btnId);
                if (b) b.innerHTML = this.svgIcon(seekIconMap[btnId]);
            }.bind(this));

            // Time display
            if (timeEl) {
                timeEl.textContent = this.formatTime(s.currentTime || 0) + ' / ' + this.formatTime(s.duration || 0);
            }

            LOG('render controls.display=' + (controlsEl ? controlsEl.style.display : 'null') + ' playPauseEl=' + (!!playPauseEl));
            this.bindEvents();
            this.renderTracks();
        }

        renderTimeOnly() {
            var s = this.state;
            if (!s) return;
            var timeEl = document.getElementById('np-time');
            if (timeEl) {
                timeEl.textContent = this.formatTime(s.currentTime || 0) + ' / ' + this.formatTime(s.duration || 0);
            }
            var playPauseEl = document.getElementById('np-play-pause');
            if (playPauseEl) {
                playPauseEl.innerHTML = this.svgIcon(s.playing ? 'pause' : 'play');
            }
        }

        renderTracks() {
            var subtitleSectionEl = document.getElementById('np-subtitle-section');
            var subtitleListEl = document.getElementById('np-subtitle-list');
            var audioSectionEl = document.getElementById('np-audio-section');
            var audioListEl = document.getElementById('np-audio-list');

            // Compute signatures — only track list identity matters, not currentTime
            var subSig = this.subtitleTracks.map(function(t) {
                return (t.trackIndex != null ? t.trackIndex : '') + ':' + (t.languageName || t.displayName || '');
            }).join('|');
            var audSig = this.audioTracks.map(function(t) {
                return (t.trackIndex != null ? t.trackIndex : '') + ':' + (t.languageName || t.displayName || '');
            }).join('|');

            LOG('renderTracks sub=' + this.subtitleTracks.length + ' aud=' + this.audioTracks.length + ' subSigChanged=' + (subSig !== this._subSig) + ' audSigChanged=' + (audSig !== this._audSig));

            // Subtitles
            if (subtitleSectionEl && subtitleListEl) {
                if (this.subtitleTracks.length > 0) {
                    subtitleSectionEl.style.display = 'block';
                    if (subSig !== this._subSig) {
                        var prevSelect = subtitleListEl.querySelector('.vsp-subtitle-select');
                        var prevVal = prevSelect ? prevSelect.value : null;
                        var html = '<select class="vsp-select vsp-subtitle-select">';
                        html += '<option value="-1">Off</option>';
                        this.subtitleTracks.forEach(function(t, i) {
                            var name = t.displayName || t.languageName || 'Track ' + (i + 1);
                            html += '<option value="' + i + '">' + escHtmlStatic(name) + '</option>';
                        });
                        html += '</select>';
                        subtitleListEl.innerHTML = html;
                        if (prevVal !== null) {
                            var newSelect = subtitleListEl.querySelector('.vsp-subtitle-select');
                            if (newSelect) {
                                var optExists = false;
                                for (var o = 0; o < newSelect.options.length; o++) {
                                    if (newSelect.options[o].value === prevVal) { optExists = true; break; }
                                }
                                if (optExists) newSelect.value = prevVal;
                            }
                        }
                        // Pre-select the ACTIVE subtitle (from state, else localStorage)
                        var activeIdx = -1;
                        if (this.state) {
                            if (typeof this.state.selectedSubtitleIndex === 'number') {
                                activeIdx = this.state.selectedSubtitleIndex;
                            } else if (this.state.selectedSubtitleId != null) {
                                for (var k = 0; k < this.subtitleTracks.length; k++) {
                                    if (this.subtitleTracks[k].id === this.state.selectedSubtitleId) { activeIdx = k; break; }
                                }
                            }
                        }
                        if (activeIdx === -1) {
                            try {
                                var stored = localStorage.getItem('jmedia_test_subtitle_track');
                                if (stored && stored !== 'off') {
                                    for (var k2 = 0; k2 < this.subtitleTracks.length; k2++) {
                                        if (String(this.subtitleTracks[k2].id) === String(stored)) { activeIdx = k2; break; }
                                    }
                                }
                            } catch (e) {}
                        }
                        if (activeIdx >= 0) {
                            var actSel = subtitleListEl.querySelector('.vsp-subtitle-select');
                            if (actSel) actSel.value = String(activeIdx);
                        }
                        this._subSig = subSig;
                    }
                } else {
                    subtitleSectionEl.style.display = 'none';
                    subtitleListEl.innerHTML = '';
                }
            }

            // Audio
            if (audioSectionEl && audioListEl) {
                if (this.audioTracks.length > 1) {
                    audioSectionEl.style.display = 'block';
                    if (audSig !== this._audSig) {
                        var prevSelect = audioListEl.querySelector('.vsp-audio-select');
                        var prevVal = prevSelect ? prevSelect.value : null;
                        var html = '<select class="vsp-select vsp-audio-select">';
                        this.audioTracks.forEach(function(t, i) {
                            var name = t.displayName || t.languageName || 'Track ' + (i + 1);
                            html += '<option value="' + i + '">' + escHtmlStatic(name) + '</option>';
                        });
                        html += '</select>';
                        audioListEl.innerHTML = html;
                        if (prevVal !== null) {
                            var newSelect = audioListEl.querySelector('.vsp-audio-select');
                            if (newSelect) {
                                var optExists = false;
                                for (var o = 0; o < newSelect.options.length; o++) {
                                    if (newSelect.options[o].value === prevVal) { optExists = true; break; }
                                }
                                if (optExists) newSelect.value = prevVal;
                            }
                        }
                        // Pre-select the ACTIVE audio track (from state only)
                        var activeAudioIdx = -1;
                        if (this.state && typeof this.state.selectedAudioIndex === 'number') {
                            activeAudioIdx = this.state.selectedAudioIndex;
                        }
                        if (activeAudioIdx >= 0) {
                            var actAud = audioListEl.querySelector('.vsp-audio-select');
                            if (actAud) actAud.value = String(activeAudioIdx);
                        }
                        this._audSig = audSig;
                    }
                } else {
                    audioSectionEl.style.display = 'none';
                    audioListEl.innerHTML = '';
                }
            }

            var tracksWrapper = document.getElementById('np-tracks');
            if (tracksWrapper) {
                tracksWrapper.style.display = (this.subtitleTracks.length > 0 || this.audioTracks.length > 1) ? 'block' : 'none';
            }

            this.bindTrackEvents();
        }

        bindEvents() {
            var self = this;
            var playBtn = document.getElementById('np-play-pause');
            if (playBtn) {
                playBtn.onclick = function() {
                    self.sendCommand('/api/video/playback/toggle', 'POST');
                };
            }

            var seekBtns = [
                { id: 'np-rewind-30', offset: -30 },
                { id: 'np-rewind-15', offset: -15 },
                { id: 'np-forward-15', offset: 15 },
                { id: 'np-forward-30', offset: 30 }
            ];
            seekBtns.forEach(function(cfg) {
                var btn = document.getElementById(cfg.id);
                if (btn) {
                    btn.onclick = function() {
                        if (self.state && self.state.id) {
                            var newTime = Math.max(0, (self.state.currentTime || 0) + cfg.offset);
                            self.sendCommand('/api/video/playback/seek/' + newTime, 'POST');
                        }
                    };
                }
            });
            LOG('bindEvents playBtn=' + (!!playBtn) + ' seekBtns=' + seekBtns.length);
        }

        bindTrackEvents() {
            var self = this;
            var subSelect = document.querySelector('.vsp-subtitle-select');
            if (subSelect && this.state) {
                subSelect.onchange = function() {
                    var idx = parseInt(subSelect.value);
                    self.sendCommand('/api/video/playback/select-subtitle?videoId=' + self.state.id, 'POST', { index: idx });
                };
            }

            var audioSelect = document.querySelector('.vsp-audio-select');
            if (audioSelect && this.state) {
                audioSelect.onchange = function() {
                    var idx = parseInt(audioSelect.value);
                    self.sendCommand('/api/video/playback/select-audio?videoId=' + self.state.id, 'POST', { index: idx });
                };
            }
        }

        async sendCommand(url, method, body) {
            try {
                var opts = { method: method };
                if (body) {
                    opts.headers = { 'Content-Type': 'application/json' };
                    opts.body = JSON.stringify(body);
                }
                LOG('sendCommand ' + method + ' ' + url);
                const resp = await fetch(url, opts);
                LOG('sendCommand response ' + (resp.ok ? 'ok' : ('HTTP ' + resp.status)));
            } catch (e) {
                LOG('Command error:', e);
            }
        }

        formatTime(seconds) {
            if (!seconds || seconds < 0) return '0:00';
            var h = Math.floor(seconds / 3600);
            var m = Math.floor((seconds % 3600) / 60);
            var s = Math.floor(seconds % 60);
            if (h > 0) return h + ':' + String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
            return m + ':' + String(s).padStart(2, '0');
        }

        svgIcon(type) {
            var icons = {
                play: '<svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><polygon points="8,4 20,12 8,20"/></svg>',
                pause: '<svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="4" width="4" height="16" rx="1"/><rect x="14" y="4" width="4" height="16" rx="1"/></svg>',
                rewind30: '<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M11 18V6l-8.5 6 8.5 6zm.5-6l8.5 6V6l-8.5 6z"/></svg>',
                rewind15: '<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M12.5 8V4l-7 7 7 7v-4.1c5 0 8.5 1.6 11 5.1-1-5-4-10-11-11z"/></svg>',
                forward15: '<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M11.5 8V4l7 7-7 7v-4.1c-5 0-8.5 1.6-11 5.1 1-5 4-10 11-11z"/></svg>',
                forward30: '<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M4 18l8.5-6L4 6v12zm9-12v12l8.5-6L13 6z"/></svg>'
            };
            return icons[type] || '';
        }

        destroy() {
            if (this.pollInterval) {
                clearInterval(this.pollInterval);
                this.pollInterval = null;
            }
            var ids = ['np-title', 'np-subtitle', 'np-controls', 'np-play-pause', 'np-time', 'np-carousel', 'np-subtitle-section', 'np-subtitle-list', 'np-audio-section', 'np-audio-list', 'np-hero-backdrop'];
            ids.forEach(function(id) {
                var el = document.getElementById(id);
                if (el) {
                    el.innerHTML = '';
                    el.style.display = '';
                }
            });
        }
    };

    // Static HTML-escape helper for use inside forEach callbacks
    function escHtmlStatic(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    window.VideoSidebarController = VideoSidebarController;

    window.scrollNpCarousel = function(direction) {
        var c = document.getElementById('npCarousel');
        if (c) c.scrollBy({ left: direction === 'left' ? -400 : 400, behavior: 'smooth' });
    };

    if (!window.underplayerPlayCard) {
        window.underplayerPlayCard = function(card) {
            var videoId = card.getAttribute('data-video-id');
            if (window.openPlayerModal) {
                window.openPlayerModal(videoId);
                return;
            }
            if (window.__npIsRemoteController && window.npRemoteSwitchVideo) {
                window.npRemoteSwitchVideo(videoId);
            }
        };
    }
})();
