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

        init() {
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

            this.loadTracks();
            this.updateCurrentDisplay();

            const videoId = p.videoId;
            if (videoId) {
                const savedTrack = localStorage.getItem('jmedia_audio_track_' + videoId);
                if (savedTrack) {
                    setTimeout(() => {
                        if (p.switchAudioTrack) {
                            p.switchAudioTrack(this._resolveTrackIndex(savedTrack));
                        }
                    }, 1000);
                }
            }
        }

        async loadTracks() {
            const p = this.player;
            if (!p) return;
            const videoId = p.videoId;
            if (!videoId) return;

            if (p.getAudioTracks) {
                const playerTracks = p.getAudioTracks();
                if (playerTracks && playerTracks.length > 0) {
                    window.availableAudioTracks = playerTracks;
                    this.populateMenu();
                    return;
                }
            }

            try {
                const response = await fetch('/api/video/' + videoId + '/audio-tracks');
                if (response.ok) {
                    const data = await response.json();
                    const payload = data.data || data;
                    window.availableAudioTracks = payload || [];
                    this.populateMenu();
                }
            } catch (error) {
                console.error('Error loading audio tracks:', error);
            }
        }

        populateMenu() {
            const trackList = this.trackList;
            if (!trackList) return;

            // Hide the entire selector when there are no additional audio tracks
            const selector = this.selector || trackList.closest('.audio-track-selector');
            if (!window.availableAudioTracks || window.availableAudioTracks.length === 0) {
                if (selector) selector.style.display = 'none';
                return;
            }
            if (selector) selector.style.display = '';

            while (trackList.children.length > 1) {
                trackList.removeChild(trackList.lastChild);
            }

            window.availableAudioTracks.forEach(track => {
                const trackItem = document.createElement('div');
                trackItem.className = 'track-item';
                trackItem.dataset.track = track.id;

                let label = track.displayName || track.languageName || track.languageCode || 'Unknown';
                if (track.channels) {
                    const channelLabel = track.channels === 6 ? ' 5.1' : track.channels === 8 ? ' 7.1' : track.channels === 2 ? ' Stereo' : '';
                    label += channelLabel;
                }
                if (track.title && track.title !== label) {
                    label += ' (' + track.title + ')';
                }

                trackItem.innerHTML = '<span class="track-name">' + label + '</span>' +
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
                menu.style.top = (rect.bottom + 4) + 'px';
                menu.style.left = rect.left + 'px';
                menu.style.zIndex = '30000';
            }
            menu.style.display = 'block';
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
            menu.style.top = (rect.bottom + 4) + 'px';
            menu.style.left = rect.left + 'px';
        }

        // The DB id is not the ffprobe stream index: resolve the track object
        // and prefer its trackIndex, falling back to a raw int for legacy ids.
        _resolveTrackIndex(trackId) {
            const track = window.availableAudioTracks.find(t => t.id == trackId);
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

            if (p && p.switchAudioTrack) {
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
            trackList.querySelectorAll('.track-item').forEach(item => {
                const trackId = item.dataset.track;
                const checkIcon = item.querySelector('.track-selected');

                if (trackId === this.currentTrackId.toString()) {
                    if (checkIcon) checkIcon.style.display = 'inline';
                    item.classList.add('selected');
                } else {
                    if (checkIcon) checkIcon.style.display = 'none';
                    item.classList.remove('selected');
                }
            });
        }

        updateCurrentDisplay() {
            const display = this.display;
            if (!display) return;

            if (this.currentTrackId === 'default') {
                display.textContent = 'Default';
            } else {
                const track = window.availableAudioTracks.find(t => t.id == this.currentTrackId);
                if (track) {
                    display.textContent = track.languageName || track.displayName || track.languageCode || 'Audio';
                } else {
                    display.textContent = 'Track ' + this.currentTrackId;
                }
            }
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
