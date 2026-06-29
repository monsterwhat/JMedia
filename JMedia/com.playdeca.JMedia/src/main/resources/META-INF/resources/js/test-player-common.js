(function() {
    'use strict';

    var LS_PREFIX = 'jmedia_test_';

    /**
     * TestPlayerFeatures - Wires all backend-powered features to a test player page.
     *
     * The adapter must implement the TestPlayerAdapter interface:
     *   getVideoElement()      -> native <video> element
     *   getCurrentTime()       -> seconds
     *   setCurrentTime(t)      -> void
     *   getDuration()          -> seconds
     *   isPaused()             -> boolean
     *   play()                 -> void
     *   pause()                -> void
     *   getVolume()            -> 0-1
     *   setVolume(v)           -> void
     *   isMuted()              -> boolean
     *   setMuted(m)            -> void
     *   getPlaybackRate()      -> number
     *   setPlaybackRate(r)     -> void
     *   on(event, cb)          -> void
     *   off(event, cb)         -> void
     *   getVideoSrc()          -> string
     *   setVideoSrc(url)       -> void
     *   requestFullscreen()    -> void
     *
     * @param {string} videoId
     * @param {Object} adapter  TestPlayerAdapter implementation
     */
    window.TestPlayerFeatures = class {
        constructor(videoId, adapter) {
            this.videoId = videoId;
            this.adapter = adapter;
            this.video = adapter ? adapter.getVideoElement() : null;
            this.container = this._findContainer();
            this.intervalId = null;
            this.markers = { introStart: 0, introEnd: 0, outroStart: 0, outroEnd: 0 };
            this.storyboardData = null;
            this.quality = 'auto';
            this.totalFileSize = null; // bytes, populated from metadata API
            this._byteTrackController = null; // AbortController for byte-level fetch
            this._progressContainer = null;
            this._skipNoticeTimer = null;
            this._destroyed = false;

            if (!this.videoId || !this.adapter || !this.video) {
                console.warn('[TestPlayerFeatures] Missing videoId, adapter, or video element');
                return;
            }

            this._init();
        }

        _findContainer() {
            if (this.video) {
                var c = this.video.closest('.player-container');
                if (c) return c;
            }
            return document.getElementById('customPlayer');
        }

        // ====================================================================
        // INIT
        // ====================================================================
        async _init() {
            this._createMissingElements();
            this._setupSubtitleNavigation();
            await this.loadMetadata();
            this.startProgressReporting();
            this.setupNavigation();
            await this.loadSubtitles();
            await this.loadStoryboard();
            await this.loadAudioTracks();
            this.loadMarkers();
            this.setupServerSeek();
            this.setupQualitySelector();
            this.setupPip();
            this.setupSubtitleStyles();
            this.setupBufferIndicator();
        }

        _createMissingElements() {
            var controls = document.getElementById('controlsContainer');
            var fsBtn = document.getElementById('fullscreenBtn');

            // ---- scrollPreview (only if custom controls exist for positioning) ----
            var container = this.container || document.getElementById('customPlayer');
            if (!document.getElementById('scrollPreview') && controls) {
                var el = document.createElement('div');
                el.id = 'scrollPreview';
                el.className = 'preview-container';
                el.innerHTML = '<div class="storyboard-img"></div><div class="preview-time">0:00</div>';
                el.style.cssText = 'position:absolute;bottom:80px;pointer-events:none;display:none;';
                controls.insertBefore(el, controls.firstChild);
            }

            // ---- subtitleList inside subtitleMenu ----
            var subMenu = document.getElementById('subtitleMenu');
            if (subMenu && !document.getElementById('subtitleList')) {
                var list = document.createElement('div');
                list.id = 'subtitleList';
                list.className = 'subtitle-list';

                var mainPage = subMenu.querySelector('.settings-page[data-page="main"]');
                if (mainPage) {
                    var placeholder = mainPage.querySelector('.settings-item[style*="opacity"]');
                    if (placeholder) placeholder.style.display = 'none';

                    mainPage.appendChild(list);

                    // Subtitle style nav item
                    var styleNav = document.createElement('div');
                    styleNav.className = 'settings-item';
                    styleNav.style.cssText = 'cursor:pointer;display:flex;justify-content:space-between;align-items:center;padding:8px 12px;';
                    styleNav.innerHTML = '<span>Subtitle Style</span><span class="pi pi-chevron-right" style="font-size:0.75rem;"></span>';
                    styleNav.onclick = function () {
                        var sp = document.getElementById('subtitleStylePage');
                        if (sp) sp.classList.add('active');
                    };
                    mainPage.appendChild(styleNav);
                }

                // Off option
                var off = document.createElement('div');
                off.id = 'sub-off';
                off.className = 'subtitle-option active';
                off.textContent = 'Off';
                off.onclick = (function (self) {
                    return function (e) {
                        e.stopPropagation();
                        self._turnOffSubtitles();
                        list.querySelectorAll('.subtitle-option').forEach(function (el) { el.classList.remove('active'); });
                        off.classList.add('active');
                        if (subMenu) subMenu.classList.remove('active');
                        localStorage.setItem(LS_PREFIX + 'subtitle_track', 'off');
                    };
                })(this);
                list.appendChild(off);
            }

            // ---- subtitle style page ----
            if (subMenu && !document.getElementById('subtitleStylePage')) {
                var sp = document.createElement('div');
                sp.id = 'subtitleStylePage';
                sp.className = 'settings-page';
                sp.innerHTML =
                    '<div class="menu-header" style="display:flex;align-items:center;gap:8px;">' +
                    '<span class="pi pi-chevron-left" style="cursor:pointer;font-size:1rem;" id="subStyleBack"></span>' +
                    '<span>Subtitle Style</span>' +
                    '</div>' +
                    '<div class="settings-content" style="padding:12px;display:flex;flex-direction:column;gap:12px;">' +
                    '<label style="display:flex;flex-direction:column;gap:4px;font-size:0.85rem;">' +
                    '<span>Font Size: <span id="subFontSizeVal">100</span>%</span>' +
                    '<input type="range" id="subFontSize" min="50" max="200" value="100" step="5">' +
                    '</label>' +
                    '<label style="display:flex;flex-direction:column;gap:4px;font-size:0.85rem;">' +
                    '<span>Color</span>' +
                    '<input type="color" id="subFontColor" value="#ffffff">' +
                    '</label>' +
                    '<label style="display:flex;flex-direction:column;gap:4px;font-size:0.85rem;">' +
                    '<span>Position: <span id="subPositionVal">90</span>% from bottom</span>' +
                    '<input type="range" id="subPosition" min="0" max="100" value="90">' +
                    '</label>' +
                    '</div>';
                subMenu.appendChild(sp);
                var backBtn = document.getElementById('subStyleBack');
                if (backBtn) {
                    backBtn.onclick = function () { sp.classList.remove('active'); };
                }
            }

            // Ensure fullscreen button exists — create fallback if missing
            if (!document.getElementById('fullscreenBtn')) {
                console.warn('[TestPlayerFeatures] #fullscreenBtn not found in HTML, creating fallback');
                var fsFallback = document.createElement('button');
                fsFallback.id = 'fullscreenBtn';
                fsFallback.className = 'control-btn';
                fsFallback.title = 'Fullscreen';
                fsFallback.innerHTML = '<i class="pi pi-expand"></i>';
                var controlsRow = controls ? controls.querySelector('.controls-row') : null;
                var fsParent = controlsRow || controls || document.getElementById('customPlayer');
                if (fsParent) {
                    fsParent.appendChild(fsFallback);
                }
                // Wire fallback click handler (page inline script won't see it)
                fsFallback.onclick = function (e) {
                    e.stopPropagation();
                    var c = document.getElementById('customPlayer');
                    if (document.fullscreenElement || document.webkitFullscreenElement) {
                        if (document.exitFullscreen) document.exitFullscreen();
                        else if (document.webkitExitFullscreen) document.webkitExitFullscreen();
                        c.classList.remove('is-fullscreen');
                        fsFallback.innerHTML = '<i class="pi pi-expand"></i>';
                    } else {
                        if (c.requestFullscreen) c.requestFullscreen();
                        else if (c.webkitRequestFullscreen) c.webkitRequestFullscreen();
                        c.classList.add('is-fullscreen');
                        fsFallback.innerHTML = '<i class="pi pi-compress"></i>';
                    }
                };
            }

            // ---- PiP button ----
            if (!document.getElementById('pipBtn') && typeof document.pictureInPictureEnabled !== 'undefined') {
                var pip = document.createElement('button');
                pip.id = 'pipBtn';
                pip.className = 'control-btn';
                pip.title = 'Picture-in-Picture';
                pip.innerHTML = '<i class="pi pi-external-link"></i>';
                pip.style.display = document.pictureInPictureEnabled ? '' : 'none';
                var fsBtnEl = document.getElementById('fullscreenBtn') || fsBtn;
                if (fsBtnEl && fsBtnEl.parentElement) {
                    fsBtnEl.parentElement.insertBefore(pip, fsBtnEl);
                } else {
                    var cnt = document.getElementById('customPlayer');
                    if (cnt) cnt.appendChild(pip);
                }
            }

            // ---- audio track selector container ----
            if (!document.getElementById('audioTrackSelector')) {
                var ats = document.createElement('div');
                ats.id = 'audioTrackSelector';
                ats.style.cssText = 'display:none;align-items:center;gap:4px;';
                var fsBtnEl = document.getElementById('fullscreenBtn') || fsBtn;
                if (fsBtnEl && fsBtnEl.parentElement) {
                    fsBtnEl.parentElement.insertBefore(ats, fsBtnEl);
                } else {
                    var cnt = document.getElementById('customPlayer');
                    if (cnt) cnt.appendChild(ats);
                }
            }

            // buffer indicator (sibling of loadingOverlay, positioned absolutely)
            var container = this.container || document.getElementById('customPlayer');
            if (container && !document.getElementById('bufferInfo')) {
                var info = document.createElement('div');
                info.id = 'bufferInfo';
                info.style.cssText =
                    'display:none;flex-direction:column;align-items:center;gap:8px;text-align:center;' +
                    'position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);z-index:601;' +
                    'pointer-events:none;';
                info.innerHTML =
                    '<div style="color:#ccc;font-size:0.85rem;letter-spacing:0.3px;">Loading video... <span id="bufferPct">0</span>%</div>' +
                    '<div style="width:200px;height:4px;background:rgba(255,255,255,0.15);border-radius:2px;overflow:hidden;">' +
                    '<div id="bufferBar" style="height:100%;width:0%;background:#48c774;border-radius:2px;transition:width 0.3s ease;"></div>' +
                    '</div>';
                container.appendChild(info);
            }
        }

        // ====================================================================
        // 0b. Subtitle Menu Navigation
        // ====================================================================
        _setupSubtitleNavigation() {
            var subMenu = document.getElementById('subtitleMenu');
            if (!subMenu) return;

            var self = this;

            // Wire nav items: .settings-item[data-page] click → show that sub-page
            subMenu.querySelectorAll('.settings-item[data-page]').forEach(function (item) {
                item.onclick = function (e) {
                    e.stopPropagation();
                    var page = item.getAttribute('data-page');
                    subMenu.querySelectorAll('.settings-page').forEach(function (p) { p.classList.remove('active'); });
                    var target = subMenu.querySelector('.settings-page[data-page="' + page + '"]');
                    if (target) target.classList.add('active');
                };
            });

            // Wire back buttons: .settings-back click → return to main page
            subMenu.querySelectorAll('.settings-back').forEach(function (btn) {
                btn.onclick = function (e) {
                    e.stopPropagation();
                    subMenu.querySelectorAll('.settings-page').forEach(function (p) { p.classList.remove('active'); });
                    var main = subMenu.querySelector('.settings-page[data-page="main"]');
                    if (main) main.classList.add('active');
                };
            });

            // Wire #sub-off (Off subtitle option) — created in HTML, needs handler
            var off = document.getElementById('sub-off');
            if (off && !off._wired) {
                off._wired = true;
                off.onclick = function (e) {
                    e.stopPropagation();
                    self._turnOffSubtitles();
                    var list = document.getElementById('subtitleList');
                    if (list) {
                        list.querySelectorAll('.subtitle-option').forEach(function (el) { el.classList.remove('active'); });
                    }
                    off.classList.add('active');
                    if (subMenu) subMenu.classList.remove('active');
                    localStorage.setItem(LS_PREFIX + 'subtitle_track', 'off');
                };
            }
        }

        // ====================================================================
        // 1. Video Metadata
        // ====================================================================
        async loadMetadata() {
            try {
                var res = await fetch('/api/video/' + encodeURIComponent(this.videoId));
                if (!res.ok) { console.warn('[TestPlayerFeatures] Metadata API returned', res.status); return; }
                var json = await res.json();
                var data = json.data || json;

                var titleEl = document.getElementById('videoTitle');
                var subEl = document.getElementById('videoSubtitle');

                if (titleEl && data.title) titleEl.textContent = data.title;
                if (subEl) {
                    var durStr = this._formatTime(data.duration || 0);
                    subEl.textContent = (data.seriesName ? data.seriesName + ' • ' : '') + durStr + ' • MP4';
                }
                if (this.container && data.title) {
                    this.container.dataset.title = data.title;
                }

                // Store file size for byte-level buffer tracking
                if (data.fileSize != null && data.fileSize > 0) {
                    this.totalFileSize = data.fileSize;
                }

                // Store markers from metadata
                if (data.introStart != null) this.markers.introStart = parseFloat(data.introStart);
                if (data.introEnd != null) this.markers.introEnd = parseFloat(data.introEnd);
                if (data.outroStart != null) this.markers.outroStart = parseFloat(data.outroStart);
                if (data.outroEnd != null) this.markers.outroEnd = parseFloat(data.outroEnd);
            } catch (err) {
                console.warn('[TestPlayerFeatures] Failed to load metadata:', err);
            }
        }

        _formatTime(sec) {
            if (!sec || isNaN(sec) || sec === Infinity) return '0:00';
            var m = Math.floor(sec / 60);
            var s = Math.floor(sec % 60);
            return m + ':' + (s < 10 ? '0' : '') + s;
        }

        // ====================================================================
        // 2. Progress Reporting
        // ====================================================================
        startProgressReporting() {
            var self = this;

            this._reportProgress();

            this.intervalId = setInterval(function () {
                if (self._destroyed) return;
                if (self.video && !self.video.paused && self.video.currentTime > 0) {
                    self._reportProgress();
                }
            }, 5000);

            document.addEventListener('visibilitychange', function () {
                if (document.visibilityState === 'hidden') {
                    self._reportProgress();
                }
            });

            window.addEventListener('beforeunload', function () {
                if (self.video && self.video.currentTime > 0) {
                    var url = '/api/video/playback/progress?videoId=' + encodeURIComponent(self.videoId) +
                        '&time=' + self.video.currentTime + '&playing=false';
                    try {
                        navigator.sendBeacon(url, '');
                    } catch (e) {
                        fetch(url, { method: 'POST', credentials: 'include', keepalive: true }).catch(function () { });
                    }
                }
            });
        }

        _reportProgress() {
            if (!this.video || this.video.currentTime <= 0) return;
            var url = '/api/video/playback/progress?videoId=' + encodeURIComponent(this.videoId) +
                '&time=' + this.video.currentTime + '&playing=' + !this.video.paused;
            fetch(url, {
                method: 'POST',
                credentials: 'include',
                keepalive: true
            }).then(function (r) {
                if (!r.ok) console.warn('[TestPlayerFeatures] Progress returned', r.status);
            }).catch(function (err) {
                console.warn('[TestPlayerFeatures] Progress report failed:', err);
            });
        }

        // ====================================================================
        // 3. Prev/Next Navigation
        // ====================================================================
        setupNavigation() {
            var self = this;
            var prevBtn = document.getElementById('prevBtn');
            var nextBtn = document.getElementById('nextBtn');

            if (prevBtn) {
                prevBtn.addEventListener('click', function (e) {
                    e.stopPropagation();
                    self._navigate('previous');
                });
            }
            if (nextBtn) {
                nextBtn.addEventListener('click', function (e) {
                    e.stopPropagation();
                    self._navigate('next');
                });
            }
        }

        async _navigate(direction) {
            var endpoint = direction === 'next'
                ? '/api/video/playback/next/'
                : '/api/video/playback/previous/';
            try {
                var res = await fetch(endpoint + encodeURIComponent(this.videoId));
                if (!res.ok) { console.warn('[TestPlayerFeatures] Navigation returned', res.status); return; }
                var data = await res.json();
                var targetId = direction === 'next' ? data.nextVideoId : data.previousVideoId;
                if (targetId) {
                    window.location.href = '?videoId=' + encodeURIComponent(targetId);
                }
            } catch (err) {
                console.warn('[TestPlayerFeatures] Navigation failed:', err);
            }
        }

        // ====================================================================
        // 4. Subtitle Tracks
        // ====================================================================
        async loadSubtitles(keepMenuOpen) {
            try {
                var res = await fetch('/api/video/subtitles/' + encodeURIComponent(this.videoId));
                if (!res.ok) { console.warn('[TestPlayerFeatures] Subtitle API:', res.status); return; }
                var json = await res.json();
                var tracks = json.tracks || json.data || [];

                // Store for OPlayer API integration
                this._subtitleTracks = tracks;

                var list = document.getElementById('subtitleList');
                if (!list) return;

                // Remove dynamic options (keep Off)
                list.querySelectorAll('.subtitle-option:not(#sub-off)').forEach(function (el) { el.remove(); });

                if (tracks.length === 0) return;

                var self = this;
                tracks.forEach(function (t) {
                    var opt = document.createElement('div');
                    opt.className = 'subtitle-option';
                    opt.setAttribute('data-id', t.id);
                    opt.textContent = t.displayName || t.filename || ('Track ' + t.id);
                    opt.onclick = function (e) {
                        e.stopPropagation();
                        self._selectSubtitleTrack(t);
                        list.querySelectorAll('.subtitle-option').forEach(function (el) { el.classList.remove('active'); });
                        opt.classList.add('active');
                        var menu = document.getElementById('subtitleMenu');
                        if (menu && !keepMenuOpen) menu.classList.remove('active');
                    };
                    list.appendChild(opt);
                });

                // Restore last selection
                var lastTrack = localStorage.getItem(LS_PREFIX + 'subtitle_track');
                if (lastTrack && lastTrack !== 'off') {
                    var match = list.querySelector('.subtitle-option[data-id="' + lastTrack + '"]');
                    if (match) match.click();
                }

                /* Push tracks to OPlayer native subtitle API if active */
                if (window.__oplayerPlayer && window.__oplayerPlayer.context && window.__oplayerPlayer.context.ui) {
                    var oplayerSubs = tracks.map(function (t) {
                        return {
                            name: t.displayName || t.filename || ('Track ' + t.id),
                            src: '/api/video/subtitles/track/' + t.id,
                            default: t.id === lastTrack
                        };
                    });
                    try {
                        window.__oplayerPlayer.context.ui.subtitle.changeSource(oplayerSubs);
                    } catch (e) {
                        console.warn('[TestPlayerFeatures] Failed to push subtitles to OPlayer:', e);
                    }
                }
            } catch (err) {
                console.warn('[TestPlayerFeatures] Failed to load subtitles:', err);
            }
        }

        _selectSubtitleTrack(track) {
            this._turnOffSubtitles();

            if (track.id === 'off') return;

            /* Use OPlayer native subtitle API when OPlayer is active */
            if (window.__oplayerPlayer && window.__oplayerPlayer.context && window.__oplayerPlayer.context.ui) {
                var oplayerSubs = (this._subtitleTracks || []).map(function (t) {
                    return {
                        name: t.displayName || t.filename || ('Track ' + t.id),
                        src: '/api/video/subtitles/track/' + t.id,
                        default: t.id === track.id
                    };
                });
                try {
                    window.__oplayerPlayer.context.ui.subtitle.changeSource(oplayerSubs);
                } catch (e) {
                    console.warn('[TestPlayerFeatures] OPlayer subtitle changeSource failed:', e);
                }
                localStorage.setItem(LS_PREFIX + 'subtitle_track', track.id);
                return;
            }

            /* Fallback: native <track> element approach for video.js / simple player */
            var self = this;
            var trackEl = document.createElement('track');
            trackEl.kind = 'subtitles';
            trackEl.src = '/api/video/subtitles/track/' + track.id;
            trackEl.srclang = track.language || 'en';
            trackEl.label = track.displayName || 'Subtitles';
            trackEl.default = true;
            trackEl.id = 'subtitle-track-' + track.id;
            this.video.appendChild(trackEl);

            var enableFn = function () {
                if (!self.video || !self.video.textTracks) return;
                for (var j = 0; j < self.video.textTracks.length; j++) {
                    var tt = self.video.textTracks[j];
                    if (tt.label === (track.displayName || 'Subtitles') || (tt.kind === 'subtitles' && tt.mode === 'disabled')) {
                        tt.mode = 'showing';
                        break;
                    }
                }
            };
            trackEl.addEventListener('load', function () { setTimeout(enableFn, 100); });
            setTimeout(enableFn, 500);

            localStorage.setItem(LS_PREFIX + 'subtitle_track', track.id);
        }

        _turnOffSubtitles() {
            /* Clear OPlayer native subtitles if active */
            if (window.__oplayerPlayer && window.__oplayerPlayer.context && window.__oplayerPlayer.context.ui) {
                try {
                    window.__oplayerPlayer.context.ui.subtitle.changeSource([]);
                } catch (e) {
                    console.warn('[TestPlayerFeatures] Failed to clear OPlayer subtitles:', e);
                }
            }

            if (!this.video) return;
            this.video.querySelectorAll('track').forEach(function (el) {
                if (el.track) el.track.mode = 'hidden';
                el.remove();
            });
            if (this.video.textTracks) {
                for (var i = 0; i < this.video.textTracks.length; i++) {
                    this.video.textTracks[i].mode = 'hidden';
                }
            }
        }

        // ====================================================================
        // 5. Storyboard Thumbnails
        // ====================================================================
        async loadStoryboard() {
            try {
                var res = await fetch('/api/video/storyboard/' + encodeURIComponent(this.videoId) + '/metadata');
                if (!res.ok) { return; }
                var json = await res.json();
                this.storyboardData = json.data || json;

                if (!this.storyboardData.isReady) {
                    var self = this;
                    setTimeout(function () { self.loadStoryboard(); }, 2000);
                    return;
                }

                this._progressContainer = document.getElementById('progressContainer');
                if (this._progressContainer && this.storyboardData) {
                    var self = this;
                    this._progressContainer.addEventListener('mousemove', function (e) {
                        self._updateStoryboard(e);
                    });
                }
            } catch (err) {
                console.warn('[TestPlayerFeatures] Storyboard load failed:', err);
            }
        }

        _updateStoryboard(e) {
            if (!this.storyboardData || !this.storyboardData.isReady) return;
            var preview = document.getElementById('scrollPreview');
            var imgEl = preview ? preview.querySelector('.storyboard-img') : null;
            if (!preview || !imgEl) return;

            var pc = this._progressContainer;
            if (!pc) return;
            var r = pc.getBoundingClientRect();
            var dur = this.adapter.getDuration() || this.video.duration || 0;
            if (!dur || dur === Infinity) return;

            var pct = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
            var time = pct * dur;
            var m = this.storyboardData;
            var tileIndex = Math.min(Math.floor(time / m.interval), m.totalTiles - 1);
            if (tileIndex < 0) return;
            var col = tileIndex % m.columns;
            var row = Math.floor(tileIndex / m.columns);

            imgEl.style.backgroundImage = 'url(/api/video/storyboard/' + encodeURIComponent(this.videoId) + ')';
            imgEl.style.backgroundPosition = '-' + (col * m.width) + 'px -' + (row * m.height) + 'px';
            imgEl.style.backgroundSize = (m.width * m.columns) + 'px ' + (m.height * m.rows) + 'px';
            preview.classList.add('active');
        }

        // ====================================================================
        // 6. Audio Track Selector
        // ====================================================================
        async loadAudioTracks() {
            var selector = document.getElementById('audioTrackSelector');
            if (!selector) return;
            try {
                var res = await fetch('/api/video/' + encodeURIComponent(this.videoId) + '/audio-tracks');
                if (!res.ok) return;
                var json = await res.json();
                var tracks = json.data || [];

                if (tracks.length <= 1) {
                    selector.style.display = 'none';
                    return;
                }

                var select = selector.querySelector('select');
                if (!select) {
                    select = document.createElement('select');
                    select.style.cssText = 'background:#333;color:#fff;border:1px solid #48c774;border-radius:4px;padding:4px 8px;font-size:0.85rem;';
                    selector.innerHTML = '<span style="color:#aaa;font-size:0.8rem;margin-right:4px;">Audio</span>';
                    selector.appendChild(select);
                }
                select.innerHTML = '';

                var self = this;
                tracks.forEach(function (track, index) {
                    var opt = document.createElement('option');
                    opt.value = track.trackIndex != null ? track.trackIndex : index;
                    opt.textContent = track.displayName || 'Audio ' + (index + 1);
                    if (track.isDefault) opt.selected = true;
                    select.appendChild(opt);
                });

                select.onchange = function (e) {
                    var trackIndex = parseInt(e.target.value);
                    self._switchAudioTrack(trackIndex);
                };

                selector.style.display = 'inline-flex';
            } catch (err) {
                console.warn('[TestPlayerFeatures] Audio tracks failed:', err);
            }
        }

        _switchAudioTrack(trackIndex) {
            var currentTime = this.video.currentTime;
            var url = '/api/video/stream/' + encodeURIComponent(this.videoId) + '.mp4?start=' + currentTime + '&audioTrack=' + trackIndex;
            this.adapter.setVideoSrc(url);
            this.video.load();
            this.video.play().catch(function () { });
        }

        // ====================================================================
        // 7. Intro/Outro Markers
        // ====================================================================
        loadMarkers() {
            var introMarker = document.getElementById('introMarker');
            var outroMarker = document.getElementById('outroMarker');

            var self = this;
            var check = function () {
                var d = self.adapter.getDuration() || self.video.duration || 0;
                if (d <= 0 || d === Infinity) {
                    setTimeout(check, 500);
                    return;
                }

                if (introMarker && self.markers.introEnd > 0 && self.markers.introEnd <= d) {
                    var pct = (self.markers.introStart / d) * 100;
                    var w = ((self.markers.introEnd - self.markers.introStart) / d) * 100;
                    introMarker.style.cssText = 'left:' + pct + '%;width:' + w + '%;display:block;position:absolute;background:rgba(72,199,116,0.3);cursor:pointer;';
                    introMarker.onclick = function (e) {
                        e.stopPropagation();
                        self.adapter.setCurrentTime(self.markers.introEnd);
                    };
                }

                if (outroMarker && self.markers.outroStart > 0 && self.markers.outroStart < d) {
                    var pct2 = (self.markers.outroStart / d) * 100;
                    var w2 = ((self.markers.outroEnd - self.markers.outroStart) / d) * 100;
                    outroMarker.style.cssText = 'left:' + pct2 + '%;width:' + w2 + '%;display:block;position:absolute;background:rgba(255,152,0,0.3);cursor:pointer;';
                    outroMarker.onclick = function (e) {
                        e.stopPropagation();
                        var end = self.markers.outroEnd;
                        if (end < d - 5) {
                            self.adapter.setCurrentTime(end);
                        } else {
                            self._navigate('next');
                        }
                    };
                }

                // Auto-skip intro on timeupdate
                self.video.addEventListener('timeupdate', function () {
                    var t = self.video.currentTime;
                    if (self.markers.introStart > 0 && t >= self.markers.introStart && t < self.markers.introEnd) {
                        var skipTo = self.markers.introEnd;
                        if (skipTo > d) return;
                        self._showSkipNotice('Intro skipped', function () {
                            self.adapter.setCurrentTime(self.markers.introStart);
                        });
                        self.adapter.setCurrentTime(skipTo);
                    }
                });
            };
            check();
        }

        _showSkipNotice(text, undoFn) {
            var container = this.container || document.getElementById('customPlayer');
            if (!container) return;

            var notice = document.getElementById('skipNotice');
            if (!notice) {
                notice = document.createElement('div');
                notice.id = 'skipNotice';
                notice.style.cssText =
                    'position:absolute;bottom:100px;left:50%;transform:translateX(-50%);' +
                    'background:rgba(0,0,0,0.85);color:#fff;padding:8px 16px;border-radius:8px;' +
                    'display:none;align-items:center;gap:12px;z-index:2000;font-size:0.9rem;' +
                    'white-space:nowrap;';
                notice.innerHTML =
                    '<span id="skipNoticeText"></span>' +
                    '<button id="skipUndoBtn" style="background:#48c774;border:none;color:#fff;' +
                    'padding:4px 12px;border-radius:4px;cursor:pointer;font-size:0.85rem;">Undo</button>';
                container.appendChild(notice);
            }

            document.getElementById('skipNoticeText').textContent = text;
            notice.style.display = 'flex';

            var undoBtn = document.getElementById('skipUndoBtn');
            var newHandler = function () {
                if (undoFn) undoFn();
                notice.style.display = 'none';
            };
            undoBtn.onclick = newHandler;

            if (this._skipNoticeTimer) clearTimeout(this._skipNoticeTimer);
            this._skipNoticeTimer = setTimeout(function () { notice.style.display = 'none'; }, 5000);
        }

        // ====================================================================
        // 8. Server-side Seek
        // ====================================================================
        setupServerSeek() {
            var pc = document.getElementById('progressContainer');
            if (!pc) return;

            var self = this;
            pc.addEventListener('click', function (e) {
                var rect = pc.getBoundingClientRect();
                var pct = (e.clientX - rect.left) / rect.width;
                var dur = self.adapter.getDuration() || self.video.duration || 0;
                if (!dur || dur === Infinity) return;
                var targetTime = pct * dur;

                var bufLen = self.video.buffered.length;
                var bufferedEnd = bufLen > 0 ? self.video.buffered.end(bufLen - 1) : 0;

                if (targetTime > bufferedEnd + 1 && bufferedEnd > 0) {
                    var url = '/api/video/stream/' + encodeURIComponent(self.videoId) + '.mp4?start=' + targetTime;
                    if (self.quality && self.quality !== 'auto') {
                        url += '&quality=' + self.quality;
                    }
                    self.adapter.setVideoSrc(url);
                    self.video.load();
                    self.video.play().catch(function () { });
                }
            });
        }

        // ====================================================================
        // 9. Quality Selector
        // ====================================================================
        setupQualitySelector() {
            var select = document.getElementById('qualitySelect');
            if (!select) return;

            var self = this;
            select.onchange = function () {
                self.quality = this.value;
                var currentTime = self.video.currentTime;
                var url;
                if (self.quality === 'auto') {
                    url = '/api/video/stream/' + encodeURIComponent(self.videoId) + '.mp4';
                } else {
                    url = '/api/video/stream/' + encodeURIComponent(self.videoId) + '.mp4?start=' + currentTime + '&quality=' + self.quality;
                }
                self.adapter.setVideoSrc(url);
                self.video.load();
                self.video.play().catch(function () { });
            };
        }

        // ====================================================================
        // 10. Picture-in-Picture
        // ====================================================================
        setupPip() {
            var pipBtn = document.getElementById('pipBtn');
            if (!pipBtn) return;

            if (!document.pictureInPictureEnabled || !this.video || typeof this.video.requestPictureInPicture !== 'function') {
                pipBtn.style.display = 'none';
                return;
            }

            var self = this;
            pipBtn.onclick = function () {
                if (document.pictureInPictureElement) {
                    document.exitPictureInPicture().catch(function () { });
                } else if (self.video && self.video.requestPictureInPicture) {
                    self.video.requestPictureInPicture().catch(function (err) {
                        console.warn('[TestPlayerFeatures] PiP failed:', err);
                    });
                }
            };

            this.video.addEventListener('enterpictureinpicture', function () {
                pipBtn.innerHTML = '<i class="pi pi-window-minimize"></i>';
            });
            this.video.addEventListener('leavepictureinpicture', function () {
                pipBtn.innerHTML = '<i class="pi pi-external-link"></i>';
            });
        }

        // ====================================================================
        // 11. Subtitle Style Settings
        // ====================================================================
        setupSubtitleStyles() {
            var fontSizeInput = document.getElementById('subFontSize');
            var fontColorInput = document.getElementById('subFontColor');
            var positionInput = document.getElementById('subPosition');

            var sizeVal = document.getElementById('subFontSizeVal');
            var posVal = document.getElementById('subPositionVal');

            var self = this;

            // Font size
            if (fontSizeInput) {
                var savedSize = localStorage.getItem(LS_PREFIX + 'subtitle_fontsize') || '100';
                fontSizeInput.value = savedSize;
                if (sizeVal) sizeVal.textContent = savedSize;
                this._updateSubtitleStyleEl();

                fontSizeInput.oninput = function () {
                    var val = this.value;
                    if (sizeVal) sizeVal.textContent = val;
                    localStorage.setItem(LS_PREFIX + 'subtitle_fontsize', val);
                    self._updateSubtitleStyleEl();
                };
            }

            // Font color
            if (fontColorInput) {
                var savedColor = localStorage.getItem(LS_PREFIX + 'subtitle_color') || '#ffffff';
                fontColorInput.value = savedColor;
                this._updateSubtitleStyleEl();

                fontColorInput.oninput = function () {
                    localStorage.setItem(LS_PREFIX + 'subtitle_color', this.value);
                    self._updateSubtitleStyleEl();
                };
            }

            // Position
            if (positionInput) {
                var savedPos = localStorage.getItem(LS_PREFIX + 'subtitle_position') || '90';
                positionInput.value = savedPos;
                if (posVal) posVal.textContent = savedPos;
                this._updateSubtitleStyleEl();

                positionInput.oninput = function () {
                    var val = this.value;
                    if (posVal) posVal.textContent = val;
                    localStorage.setItem(LS_PREFIX + 'subtitle_position', val);
                    self._updateSubtitleStyleEl();
                };
            }

            // Apply saved values as CSS custom properties
            document.documentElement.style.setProperty('--jmedia-subtitle-font-size', (localStorage.getItem(LS_PREFIX + 'subtitle_fontsize') || '100') + '%');
            document.documentElement.style.setProperty('--jmedia-subtitle-color', localStorage.getItem(LS_PREFIX + 'subtitle_color') || '#ffffff');
            document.documentElement.style.setProperty('--jmedia-subtitle-bottom', (localStorage.getItem(LS_PREFIX + 'subtitle_position') || '90') + '%');
        }

        _updateSubtitleStyleEl() {
            var fontSize = localStorage.getItem(LS_PREFIX + 'subtitle_fontsize') || '100';
            var color = localStorage.getItem(LS_PREFIX + 'subtitle_color') || '#ffffff';
            var bottom = localStorage.getItem(LS_PREFIX + 'subtitle_position') || '90';

            // CSS custom properties
            document.documentElement.style.setProperty('--jmedia-subtitle-font-size', fontSize + '%');
            document.documentElement.style.setProperty('--jmedia-subtitle-color', color);
            document.documentElement.style.setProperty('--jmedia-subtitle-bottom', bottom + '%');

            // ::cue style element
            var styleId = 'jmedia-cue-styles';
            var styleEl = document.getElementById(styleId);
            if (!styleEl) {
                styleEl = document.createElement('style');
                styleEl.id = styleId;
                document.head.appendChild(styleEl);
            }
            styleEl.textContent =
                'video::cue { font-size: ' + fontSize + '% !important; color: ' + color + ' !important; line-height: normal !important; }' +
                'video::-webkit-media-text-track-container { bottom: ' + bottom + '% !important; }';
        }

        // ====================================================================
        // 12. Buffer Loading Indicator (iOS: shows actual download progress %)
        // ====================================================================
        setupBufferIndicator() {
            var bufferInfo = document.getElementById('bufferInfo');
            var bufferBar = document.getElementById('bufferBar');
            var bufferPct = document.getElementById('bufferPct');
            if (!bufferInfo || !bufferBar || !bufferPct || !this.video) return;

            var self = this;
            var hideTimer = null;

            // Start byte-level tracking when streaming (duration unknown + file size known).
            // Runs as a separate fetch so progress is byte-accurate even when video.duration === Infinity.
            function startByteTracking() {
                if (self._byteTrackController) return;
                if (!self.totalFileSize || self.totalFileSize <= 0) return;

                self._byteTrackController = new AbortController();
                var signal = self._byteTrackController.signal;
                var url = '/api/video/stream/' + encodeURIComponent(self.videoId) + '.mp4';

                fetch(url, { signal: signal, cache: 'no-store' })
                    .then(function (response) {
                        if (!response.ok) throw new Error('HTTP ' + response.status);
                        var total = self.totalFileSize;
                        var reader = response.body.getReader();
                        var received = 0;
                        var chunks = [];

                        function pump() {
                            reader.read().then(function (result) {
                                if (result.done) {
                                    self._byteTrackController = null;
                                    if (self.video.readyState <= 1) {
                                        var mime = response.headers.get('Content-Type') || 'video/mp4';
                                        var blob = new Blob(chunks, { type: mime });
                                        var blobUrl = URL.createObjectURL(blob);
                                        self.adapter.setVideoSrc(blobUrl);
                                        self.video.load();
                                        self.video.play().catch(function () {});
                                    }
                                    return;
                                }
                                chunks.push(result.value);
                                received += result.value.length;
                                var pct = Math.min(100, Math.round((received / total) * 100));
                                bufferBar.style.width = pct + '%';
                                bufferPct.textContent = pct;
                                pump();
                            }).catch(function (err) {
                                if (err.name !== 'AbortError') {
                                    console.warn('[TestPlayerFeatures] Byte tracking error:', err);
                                }
                            });
                        }
                        pump();
                    })
                    .catch(function (err) {
                        self._byteTrackController = null;
                        if (err.name !== 'AbortError') {
                            console.warn('[TestPlayerFeatures] Byte tracking fetch failed:', err);
                        }
                    });
            }

            function abortByteTracking() {
                if (self._byteTrackController) {
                    self._byteTrackController.abort();
                    self._byteTrackController = null;
                }
            }

            // Time-based progress — skip entirely when byte tracking manages the UI.
            function updateBuffer() {
                if (self._byteTrackController) return;
                var v = self.video;
                bufferBar.style.width = '0%';
                bufferPct.textContent = '0';
                if (!v || !v.buffered || v.buffered.length === 0) return;
                var dur = self.adapter.getDuration() || v.duration || 0;
                if (dur > 0 && dur !== Infinity) {
                    var loaded = v.buffered.end(v.buffered.length - 1);
                    var pct = Math.min(100, Math.round((loaded / dur) * 100));
                    bufferBar.style.width = pct + '%';
                    bufferPct.textContent = pct;
                }
            }

            // Decide whether to use byte tracking or fall through to time-based updateBuffer.
            function syncDisplay() {
                var v = self.video;
                var dur = self.adapter.getDuration() || v.duration || 0;
                if ((dur <= 0 || dur === Infinity) && self.totalFileSize > 0 && !self._byteTrackController) {
                    startByteTracking();
                    bufferPct.textContent = '...';
                } else {
                    updateBuffer();
                }
            }

            function showBuffer() {
                if (hideTimer) { clearTimeout(hideTimer); hideTimer = null; }
                syncDisplay();
                bufferInfo.style.display = 'flex';
            }

            function hideBuffer() {
                bufferInfo.style.display = 'none';
                abortByteTracking();
            }

            this.video.addEventListener('progress', function () {
                if (bufferInfo.style.display !== 'none' || self.video.readyState < 2) {
                    updateBuffer();
                }
            });

            this.video.addEventListener('waiting', showBuffer);
            this.video.addEventListener('stalled', showBuffer);
            this.video.addEventListener('loadstart', function () {
                bufferInfo.style.display = 'flex';
                syncDisplay();
            });

            this.video.addEventListener('loadedmetadata', function () {
                abortByteTracking();
            });

            this.video.addEventListener('emptied', function () {
                abortByteTracking();
            });

            this.video.addEventListener('playing', function () {
                hideBuffer();
            });
            this.video.addEventListener('canplay', function () {
                hideBuffer();
            });
            this.video.addEventListener('canplaythrough', hideBuffer);

            this.video.addEventListener('play', function () {
                if (self.video.readyState < 3) {
                    showBuffer();
                    var poll = setInterval(function () {
                        if (self._destroyed) { clearInterval(poll); return; }
                        if (self.video.readyState >= 3 || !self.video.paused) {
                            clearInterval(poll);
                            hideBuffer();
                            return;
                        }
                        updateBuffer();
                    }, 500);
                }
            });

            if (this.video.readyState < 3) {
                showBuffer();
            }
        }

        // ====================================================================
        // CLEANUP
        // ====================================================================
        destroy() {
            this._destroyed = true;
            if (this.intervalId) {
                clearInterval(this.intervalId);
                this.intervalId = null;
            }
            if (this._byteTrackController) {
                this._byteTrackController.abort();
                this._byteTrackController = null;
            }
        }
    };
})();
