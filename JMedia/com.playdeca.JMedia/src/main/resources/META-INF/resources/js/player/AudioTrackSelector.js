(function(window) {
    'use strict';

    window.PlayerAudioTrackSelector = class {
        constructor(player) {
            this.player = player;
            this.currentTrackId = 'default';
            window.availableAudioTracks = [];

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
            this.loadTracks();
            this.updateCurrentDisplay();

            const videoId = p.videoId;
            if (videoId) {
                const savedTrack = localStorage.getItem('jmedia_audio_track_' + videoId);
                if (savedTrack) {
                    setTimeout(() => {
                        if (p.switchAudioTrack) {
                            p.switchAudioTrack(parseInt(savedTrack));
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
            const trackList = document.getElementById('audioTrackList');
            if (!trackList) return;

            // Hide the entire selector when there are no additional audio tracks
            const selector = trackList.closest('.audio-track-selector');
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
            const menu = document.getElementById('audioTrackMenu');
            if (!menu) return;

            const isVisible = menu.style.display !== 'none';

            document.querySelectorAll('.audio-track-menu, .subtitle-menu, .speed-menu').forEach(m => {
                if (m !== menu) m.style.display = 'none';
            });

            menu.style.display = isVisible ? 'none' : 'block';

            if (!isVisible) {
                this.loadTracks();
            }
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

                const menu = document.getElementById('audioTrackMenu');
                if (menu) menu.style.display = 'none';
                return;
            }

            let trackIndex = parseInt(trackId);
            if (isNaN(trackIndex)) {
                const track = window.availableAudioTracks.find(t => t.id == trackId);
                trackIndex = track ? (track.trackIndex ?? track.id ?? 0) : 0;
            }

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

            const menu = document.getElementById('audioTrackMenu');
            if (menu) menu.style.display = 'none';
        }

        updateSelection() {
            document.querySelectorAll('#audioTrackList .track-item').forEach(item => {
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
            const display = document.getElementById('currentAudioTrackDisplay');
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

        async savePreference(trackId) {
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
        const selector = document.getElementById('audioTrackSelector');
        const menu = document.getElementById('audioTrackMenu');
        if (selector && menu && !selector.contains(e.target) && menu.style.display !== 'none') {
            menu.style.display = 'none';
        }
    });
})(window);
