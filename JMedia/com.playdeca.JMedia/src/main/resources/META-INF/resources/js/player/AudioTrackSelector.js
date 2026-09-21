(function(window) {
    'use strict';

    window.PlayerAudioTrackSelector = class {
        constructor(player) {
            this.player = player;
            this.currentTrackId = 'default';
            this.selector = null;
            this.button = null;
            this.menu = null;
            this.trackList = null;
            this.display = null;
            this._repositionHandler = null;
            this._preferenceInFlight = false;
            this._pendingPreference = undefined;
            window.availableAudioTracks = [];
            window.__audioTrackSelectorInstance = this;

            window.initializeAudioTrackSelector = () => this.init();
            window.toggleAudioTrackMenu = () => this.toggle();
            window.selectAudioTrack = (trackId) => this.selectTrack(trackId);
            window.loadAvailableAudioTracks = () => this.loadTracks();
            window.populateAudioTrackMenu = () => this.populateMenu();
            window.updateAudioTrackSelection = () => this.updateSelection();
            window.updateCurrentAudioTrackDisplay = () => this.updateCurrentDisplay();
            window.saveAudioTrackPreference = (trackId) => this.savePreference(trackId);
        }

        async init() {
            const p = this.player;
            if (!p) return;

            // Remove a dropdown a previous player instance left orphaned on
            // <body> (SPA navigation destroyed the player while it was open).
            const orphan = document.getElementById('audioTrackMenu');
            if (orphan && orphan.parentElement === document.body) {
                orphan.remove();
            }

            // Scope every lookup to this player's container instead of
            // document.getElementById so the player's own clone is always
            // targeted (the static page layout can hold another copy).
            this.selector = p.container ? p.container.querySelector('#audioTrackSelector') : null;
            this.button = this.selector ? this.selector.querySelector('.audio-track-button') : null;
            this.menu = p.container ? p.container.querySelector('#audioTrackMenu') : null;
            this.trackList = this.menu ? this.menu.querySelector('#audioTrackList') : null;
            this.display = p.container ? p.container.querySelector('#currentAudioTrackDisplay') : null;

            this.updateCurrentDisplay();

            // Load the DB track list (source of truth for ffprobe index
            // resolution), then apply the saved/default audio preference
            // deterministically once the tracks are available — no blind
            // setTimeout race that could resolve against stale/empty tracks.
            await this.loadTracks();
            if (p.applyAudioPreference) {
                p.applyAudioPreference();
            }
        }

        async loadTracks() {
            const p = this.player;
            if (!p) return;
            const videoId = p.videoId;
            if (!videoId) return;

            // Deduplicate concurrent loads (init + menu open can overlap).
            if (this._tracksLoadingPromise) return this._tracksLoadingPromise;

            this._tracksLoadingPromise = this._fetchDbTracks(videoId);
            try {
                await this._tracksLoadingPromise;
            } finally {
                this._tracksLoadingPromise = null;
            }
        }

        async _fetchDbTracks(videoId) {
            // DB tracks (with ffprobe trackIndex) are the source of truth for
            // ?audioTrack= resolution. Element tracks are display-only fallback.
            try {
                const response = await fetch('/api/video/' + videoId + '/audio-tracks');
                if (response.ok) {
                    const data = await response.json();
                    const payload = data.data || data;
                    if (payload && payload.length > 0) {
                        window.availableAudioTracks = payload;
                        this.populateMenu();
                        return;
                    }
                    console.warn('[AudioSelector] No DB audio tracks for video', videoId);
                } else {
                    console.warn('[AudioSelector] Audio tracks request failed with status', response.status);
                }
            } catch (error) {
                console.error('Error loading audio tracks:', error);
            }

            // Fallback: element tracks for display only. They carry no ffprobe
            // trackIndex (ids are array indices), so mark the list display-only
            // and never use it for ?audioTrack= resolution.
            const p = this.player;
            if (p && p.getAudioTracks) {
                const playerTracks = p.getAudioTracks();
                if (playerTracks && playerTracks.length > 0) {
                    window.availableAudioTracks = playerTracks;
                    window.availableAudioTracks._displayOnly = true;
                    this.populateMenu();
                }
            }
        }

        _channelLayout(channels) {
            if (channels === 2) return 'Stereo';
            if (channels === 6) return '5.1';
            if (channels === 8) return '7.1';
            if (channels === 1) return 'Mono';
            if (channels && channels > 0) return channels + 'ch';
            return null;
        }

        _escapeHtml(str) {
            if (!str) return '';
            return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
        }

        _isUnknownLang(track) {
            const code = (track.languageCode || '').trim().toLowerCase();
            const name = (track.languageName || '').trim().toLowerCase();
            return code === 'und' || code === '' || code === 'unknown' || name === 'und' || name === 'unknown';
        }

        _formatAudioLabel(track) {
            let lang = track.languageName;
            if (lang) {
                const l = lang.trim().toLowerCase();
                if (l === 'und' || l === 'unknown' || l === 'unknown language') lang = null;
            }
            if (!lang && !this._isUnknownLang(track) && track.languageName) {
                lang = track.languageName;
            }
            const layout = this._channelLayout(track.channels);
            let base;
            if (lang && layout) base = lang + ' ' + layout;
            else if (lang) base = lang;
            else if (layout) base = layout;
            else if (track.title) base = track.title;
            else if (track.isDefault) base = 'Default';
            else base = 'Audio';
            if (track.title && track.title !== base && track.title !== lang) {
                if (base.indexOf(track.title) === -1) base += ' (' + track.title + ')';
            }
            if (track.displayName && track.displayName !== 'Audio' && track.displayName !== 'Unknown' && track.displayName !== 'UND') {
                const disp = track.displayName.trim();
                const looksRawUnd = disp.toUpperCase() === 'UND' || disp.toLowerCase() === 'unknown';
                if (!looksRawUnd && disp !== base && disp.length < 60) {
                    const dispLayout = layout ? disp.indexOf(layout) !== -1 : false;
                    const dispLang = lang ? disp.toLowerCase().indexOf(lang.toLowerCase()) !== -1 : false;
                    if (dispLang || dispLayout || track.title) {
                        base = disp;
                        if (layout && disp.indexOf(layout) === -1 && !disp.includes(track.title || '')) {
                            if (lang && disp.toLowerCase().indexOf(lang.toLowerCase()) !== -1) base += ' ' + layout;
                        }
                    }
                }
            }
            return base;
        }

        populateMenu() {
            const trackList = this.trackList;
            if (!trackList) return;

            const selector = this.selector || trackList.closest('.audio-track-selector');
            if (!window.availableAudioTracks || window.availableAudioTracks.length === 0) {
                if (selector) selector.style.display = 'none';
                return;
            }
            if (selector) selector.style.display = '';

            while (trackList.children.length > 1) {
                trackList.removeChild(trackList.lastChild);
            }

            const counts = {};
            const rawLabels = window.availableAudioTracks.map(t => this._formatAudioLabel(t));
            rawLabels.forEach(l => { counts[l] = (counts[l] || 0) + 1; });
            const seen = {};
            window.availableAudioTracks.forEach((track, idx) => {
                let label = rawLabels[idx];
                if (counts[label] > 1) {
                    seen[label] = (seen[label] || 0) + 1;
                    if (seen[label] > 1) label += ' (' + seen[label] + ')';
                }
                track._derivedLabel = label;
                const trackItem = document.createElement('div');
                trackItem.className = 'track-item';
                trackItem.dataset.track = String(track.id);
                trackItem.innerHTML = '<span class="track-name">' + this._escapeHtml(label) + '</span>' +
                    '<i class="pi pi-check track-selected" style="display: none;"></i>';
                trackItem.onclick = () => this.selectTrack(track.id);
                trackList.appendChild(trackItem);
            });

            this.updateSelection();
            this.updateCurrentDisplay();
        }

        toggle() {
            const menu = this.menu;
            if (!menu) return;

            const isVisible = menu.style.display !== 'none';

            document.querySelectorAll('.audio-track-menu, .subtitle-menu, .speed-menu').forEach(m => {
                if (m !== menu) m.style.display = 'none';
            });

            if (!isVisible) {
                this._open(menu);
            } else {
                this._close(menu);
            }
        }

        _open(menu) {
            // Hoist the dropdown onto <body> anchored to the button with
            // viewport coordinates. Inside the player it was clipped by
            // .controls-row overflow AND trapped in the player's stacking
            // contexts (.controls-container z-index:1000 / player z-index:1),
            // so the episode sidebar (z-index:20000) hit-tested above it and
            // clicks fell through to the episode list.
            const rect = this.button ? this.button.getBoundingClientRect() : null;
            if (rect) {
                if (menu.parentElement !== document.body) {
                    document.body.appendChild(menu);
                }
                menu.style.position = 'fixed';
                menu.style.zIndex = '30000';
                menu.style.display = 'block';
                this._positionMenu(menu, rect);
            } else {
                menu.style.display = 'block';
            }
            this.loadTracks();

            if (!this._repositionHandler) {
                this._repositionHandler = () => this._reposition();
                window.addEventListener('resize', this._repositionHandler);
            }
        }

        _close(menu) {
            menu.style.display = 'none';
            if (this._repositionHandler) {
                window.removeEventListener('resize', this._repositionHandler);
                this._repositionHandler = null;
            }
            // Return the menu to its home inside the selector so a later SPA
            // navigation destroys it with the player instead of orphaning it
            // on <body>.
            const selector = this.selector;
            if (selector && menu.parentElement !== selector) {
                selector.appendChild(menu);
            }
            menu.style.position = '';
            menu.style.top = '';
            menu.style.left = '';
            menu.style.zIndex = '';
        }

        _reposition() {
            const menu = this.menu;
            if (!menu || menu.style.display === 'none') return;
            const rect = this.button ? this.button.getBoundingClientRect() : null;
            if (!rect) return;
            this._positionMenu(menu, rect);
        }

        // Anchor below the button, but flip above it when the menu would
        // otherwise extend past the viewport bottom (e.g. player docked low
        // on short screens). Left edge is clamped to stay on-screen.
        _positionMenu(menu, rect) {
            const height = menu.offsetHeight || 0;
            const spaceBelow = window.innerHeight - rect.bottom - 4;
            const top = (height > 0 && spaceBelow < height && rect.top - height - 4 >= 0)
                ? rect.top - height - 4
                : rect.bottom + 4;
            menu.style.top = top + 'px';
            menu.style.left = Math.max(4, Math.min(rect.left, window.innerWidth - menu.offsetWidth - 4)) + 'px';
        }

        // The DB id is not the ffprobe stream index: resolve the track object
        // and prefer its trackIndex, falling back to a raw int for legacy ids.
        // Element-track fallbacks (id = array index, no trackIndex) are
        // display-only and never used for index resolution.
        _resolveTrackIndex(trackId) {
            if (trackId === 'default' || trackId === null || trackId === undefined) return null;
            const list = window.availableAudioTracks || [];
            if (list._displayOnly) return null;
            const track = list.find(t => t.id == trackId);
            if (track && track.trackIndex !== undefined && track.trackIndex !== null) {
                return track.trackIndex;
            }
            const parsed = parseInt(trackId);
            return isNaN(parsed) ? (track ? (track.id ?? 0) : 0) : parsed;
        }

        selectTrack(trackId) {
            const p = this.player;
            console.log('[AudioSelector] Selecting track:', trackId);

            if (trackId === 'default') {
                if (p && p.setAudioTrack) {
                    p.setAudioTrack('default');
                }
                this.currentTrackId = 'default';
                this.updateSelection();
                this.updateCurrentDisplay();
                this.savePreference(trackId);

                const videoId = p ? p.videoId : null;
                if (videoId) {
                    localStorage.setItem('jmedia_audio_track_' + videoId, trackId);
                }

                if (this.menu) this._close(this.menu);
                return;
            }

            // trackId is the DB id; the server's ?audioTrack= expects the ffprobe
            // stream index (trackIndex), so resolve the track object first.
            const trackIndex = this._resolveTrackIndex(trackId);
            if (trackIndex === null || trackIndex === undefined) {
                console.warn('[AudioSelector] Cannot resolve track to ffprobe index (display-only fallback?):', trackId);
            } else if (p && p.switchAudioTrack) {
                p.switchAudioTrack(trackIndex);
            }

            this.currentTrackId = trackId;
            this.updateSelection();
            this.updateCurrentDisplay();
            this.savePreference(trackId);

            const videoId = p ? p.videoId : null;
            if (videoId) {
                localStorage.setItem('jmedia_audio_track_' + videoId, trackId);
            }

            if (this.menu) this._close(this.menu);
        }

        updateSelection() {
            const trackList = this.trackList;
            if (!trackList) return;
            const currentStr = String(this.currentTrackId);
            trackList.querySelectorAll('.track-item').forEach(item => {
                const trackId = String(item.dataset.track);
                const checkIcon = item.querySelector('.track-selected');
                const isSelected = trackId === currentStr;
                if (isSelected) {
                    if (checkIcon) checkIcon.style.display = 'inline';
                    item.classList.add('selected');
                } else {
                    if (checkIcon) checkIcon.style.display = 'none';
                    item.classList.remove('selected');
                }
            });
            if (currentStr !== 'default') {
                const found = window.availableAudioTracks.find(t => String(t.id) === currentStr);
                if (!found) {
                    const byIndex = window.availableAudioTracks.find(t => String(t.trackIndex) === currentStr);
                    if (byIndex) {
                        console.warn('[AudioSelector] currentTrackId was trackIndex ' + currentStr + ', correcting to DB id ' + byIndex.id);
                        this.currentTrackId = String(byIndex.id);
                        trackList.querySelectorAll('.track-item').forEach(item => {
                            const isSel = String(item.dataset.track) === String(this.currentTrackId);
                            const ci = item.querySelector('.track-selected');
                            if (isSel) { if (ci) ci.style.display = 'inline'; item.classList.add('selected'); }
                            else { if (ci) ci.style.display = 'none'; item.classList.remove('selected'); }
                        });
                    }
                }
            }
        }

        updateCurrentDisplay() {
            const display = this.display;
            if (!display) return;

            if (String(this.currentTrackId) === 'default') {
                display.textContent = 'Default';
                return;
            }
            const currentStr = String(this.currentTrackId);
            let track = window.availableAudioTracks.find(t => String(t.id) === currentStr);
            if (track) {
                display.textContent = track._derivedLabel || this._formatAudioLabel(track);
                return;
            }
            const byIndex = window.availableAudioTracks.find(t => String(t.trackIndex) === currentStr);
            if (byIndex) {
                console.warn('[AudioSelector] Display lookup fell back from DB id to trackIndex for ' + currentStr);
                this.currentTrackId = String(byIndex.id);
                display.textContent = byIndex._derivedLabel || this._formatAudioLabel(byIndex);
                this.updateSelection();
                return;
            }
            console.warn('[AudioSelector] No matching audio track for id ' + currentStr + ', showing generic label');
            display.textContent = 'Audio';
        }

        // Last-write-wins: rapid switching fires overlapping POSTs that can land
        // out of order on the server, leaving the OLDER choice persisted. Record
        // the newest request; if a save chain is already in flight it will flush
        // this value when the current POST finishes, so the final choice always
        // reaches the server last.
        async savePreference(trackId) {
            this._pendingPreference = trackId;
            if (this._preferenceInFlight) return; // active chain will flush it

            this._preferenceInFlight = true;
            try {
                while (this._pendingPreference !== undefined) {
                    const toSend = this._pendingPreference;
                    this._pendingPreference = undefined;
                    await this._sendPreference(toSend);
                }
            } finally {
                this._preferenceInFlight = false;
            }
        }

        async _sendPreference(trackId) {
            const p = this.player;
            if (!p) return;
            const videoId = p.videoId;
            if (!videoId) return;

            const trackInfo = window.availableAudioTracks.find(t => t.id == trackId);

            const url = '/api/video/playback/audio-preference?videoId=' + videoId +
                '&trackId=' + (trackId !== 'default' ? trackId : '') +
                '&language=' + (trackInfo ? encodeURIComponent(trackInfo.languageCode || '') : '');

            try {
                await fetch(url, { method: 'POST' });
            } catch (error) {
                console.error('Error saving audio track preference:', error);
            }
        }
    };

    document.addEventListener('click', function(e) {
        const inst = window.__audioTrackSelectorInstance;
        const selector = inst ? inst.selector : document.getElementById('audioTrackSelector');
        const menu = inst ? inst.menu : document.getElementById('audioTrackMenu');
        if (!selector || !menu || menu.style.display === 'none') return;
        if (selector.contains(e.target) || menu.contains(e.target)) return;
        if (inst) {
            inst._close(menu);
        } else {
            menu.style.display = 'none';
        }
    });
})(window);
