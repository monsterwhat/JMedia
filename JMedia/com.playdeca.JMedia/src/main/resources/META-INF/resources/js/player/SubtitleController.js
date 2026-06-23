(function(window) {
    'use strict';

    window.PlayerSubtitleController = class {
        constructor(player) {
            this.player = player;
        }

        selectSubtitle(trackId, element) {
            const p = this.player;
            console.log('[SimplePlayer] Selecting subtitle track:', trackId);

            p.container.querySelectorAll('.subtitle-option').forEach(el => el.classList.remove('active'));
            if (element) element.classList.add('active');
            p.subtitleMenu.classList.remove('active');

            if (trackId === 'off') {
                this.turnOffSubtitles();
            }

            localStorage.setItem('jmedia_last_track_' + p.videoId, trackId);
            p.lastSelectedTrackId = trackId;
        }

        turnOffSubtitles() {
            const p = this.player;
            console.log('[SimplePlayer] Turning off subtitles');
            this.destroyAssSubtitle();
            const isAppleSafari = p.utils.isIOS() || (/Safari/i.test(navigator.userAgent) && !/Chrome/i.test(navigator.userAgent));
            if (isAppleSafari) {
                if (p.video.textTracks) {
                    for (let i = 0; i < p.video.textTracks.length; i++) {
                        p.video.textTracks[i].mode = 'hidden';
                    }
                }
            } else {
                p.video.querySelectorAll('track').forEach(el => {
                    if (el.track) el.track.mode = 'hidden';
                    el.remove();
                });
                if (p.video.textTracks) {
                    for (let i = 0; i < p.video.textTracks.length; i++) {
                        p.video.textTracks[i].mode = 'hidden';
                    }
                }
            }
        }

        destroyAssSubtitle() {
            const p = this.player;
            if (p.jassubRenderer) {
                console.log('[SimplePlayer] Destroying ASS subtitle renderer');
                p.jassubRenderer.destroy();
                p.jassubRenderer = null;
            }
        }

        async initAssSubtitle(trackId) {
            const p = this.player;
            console.log('[SimplePlayer] Initializing ASS subtitle for track:', trackId);
            this.destroyAssSubtitle();
            try {
                const res = await fetch(`/api/video/subtitles/track/${trackId}/raw`);
                if (!res.ok) {
                    console.error('[SimplePlayer] Failed to fetch raw subtitle:', res.status);
                    return;
                }
                const content = await res.text();

                let canvas = document.getElementById('assCanvas');
                if (!canvas) {
                    const wrapper = p.container.querySelector('.video-wrapper');
                    canvas = document.createElement('canvas');
                    canvas.id = 'assCanvas';
                    canvas.className = 'ass-canvas';
                    (wrapper || p.container).appendChild(canvas);
                }

                const userCorrection = parseFloat(localStorage.getItem('jmedia_subtitle_correction') || '0');
                const totalOffset = (p.streamStartOffset || 0) + userCorrection;

                p.jassubRenderer = new JASSUB.default({
                    video: p.video,
                    canvas: canvas,
                    subContent: content,
                    workerUrl: '/lib/jassub/jassub-worker.js',
                    wasmUrl: '/lib/jassub/jassub-worker.wasm',
                    modernWasmUrl: '/lib/jassub/jassub-worker-modern.wasm',
                    defaultFont: '/lib/jassub/default.woff2',
                    timeOffset: totalOffset * 1000,
                    prescaleFactor: 1.0,
                    prescaleHeightLimit: 1080,
                    maxRenderHeight: 0,
                    debug: false
                });

                console.log('[SimplePlayer] JASSUB renderer initialized');
            } catch (e) {
                console.error('[SimplePlayer] ASS subtitle init failed:', e);
            }
        }

        async loadSubtitles(keepMenuOpen = false) {
            const p = this.player;
            try {
                console.log('[SimplePlayer] Loading subtitles for video:', p.videoId);
                const tracksRes = await fetch(`/api/video/subtitles/${p.videoId}`);
                if (!tracksRes.ok) {
                    console.error('[SimplePlayer] Subtitle API error:', tracksRes.status);
                    return;
                }
                const tracksData = await tracksRes.json();
                console.log('[SimplePlayer] Subtitle response:', tracksData);
                const tracks = tracksData.tracks || tracksData.data || [];
                console.log('[SimplePlayer] Found tracks:', tracks.length);

                const list = p.container.querySelector('#subtitleList');
                if (!list) {
                    console.error('[SimplePlayer] Subtitle list element not found');
                    return;
                }

                const offOption = list.querySelector('#sub-off');
                if (offOption) {
                    list.querySelectorAll('.subtitle-option:not(#sub-off)').forEach(el => el.remove());
                    offOption.onclick = (e) => {
                        e.stopPropagation();
                        this.turnOffSubtitles();
                        p.container.querySelectorAll('.subtitle-option').forEach(el => el.classList.remove('active'));
                        offOption.classList.add('active');
                        p.subtitleMenu.classList.remove('active');
                        localStorage.setItem('jmedia_last_track_' + p.videoId, 'off');
                        p.lastSelectedTrackId = 'off';
                    };
                    if (!p.lastSelectedTrackId || p.lastSelectedTrackId === 'off') {
                        offOption.classList.add('active');
                    }
                }

                tracks.forEach(t => {
                    const opt = document.createElement('div');
                    opt.className = 'subtitle-option';
                    opt.setAttribute('data-id', t.id);
                    opt.innerText = `${t.displayName || t.filename} (${t.isEmbedded ? 'Embedded' : 'External'})`;
                    opt.onclick = (e) => {
                        e.stopPropagation();
                        console.log('[SimplePlayer] Selecting subtitle:', t);

                        this.destroyAssSubtitle();

                        const isAppleSafari = p.utils.isIOS() || (/Safari/i.test(navigator.userAgent) && !/Chrome/i.test(navigator.userAgent));
                            if (isAppleSafari) {
                                if (t.id !== 'off' && p.video.textTracks) {
                                    for (let i = 0; i < p.video.textTracks.length; i++) {
                                        const tr = p.video.textTracks[i];
                                        const trackEl = p.video.querySelector(`track[id="subtitle-track-${t.id}"]`);
                                        const isSelected = trackEl && (tr.label === trackEl.label);
                                        tr.mode = isSelected ? 'showing' : 'hidden';
                                    }
                                } else if (p.video.textTracks) {
                                    for (let i = 0; i < p.video.textTracks.length; i++) {
                                        p.video.textTracks[i].mode = 'hidden';
                                    }
                                }
                            } else {
                                p.video.querySelectorAll('track').forEach(el => {
                                    if (el.track) el.track.mode = 'hidden';
                                    el.remove();
                                });
                                if (p.video.textTracks) {
                                    for (let i = 0; i < p.video.textTracks.length; i++) {
                                        p.video.textTracks[i].mode = 'hidden';
                                    }
                                }

                                if (t.id !== 'off') {
                                    const track = document.createElement('track');
                                    track.kind = 'subtitles';

                                    const correction = localStorage.getItem('jmedia_subtitle_correction') || 0;

                                    const startOffset = p.streamStartOffset || 0;
                                    let src = `/api/video/subtitles/track/${t.id}?start=${startOffset}`;
                                    if (parseFloat(correction) !== 0) {
                                        src += `&correction=${correction}`;
                                    }

                                    track.src = src;
                                    track.srclang = t.language || 'en';
                                    track.label = t.displayName || 'Subtitle';
                                    track.default = true;
                                    track.id = 'subtitle-track-' + t.id;
                                    p.video.appendChild(track);

                                    const setupTrack = () => {
                                        const tracksArr = Array.from(p.video.textTracks || []);
                                        console.log('[SimplePlayer] Available textTracks:', tracksArr.map(tr => ({ label: tr.label, mode: tr.mode, kind: tr.kind })));

                                        let textTrack = tracksArr.find(tr => tr.label === (t.displayName || 'Subtitle'));

                                        if (!textTrack) {
                                            textTrack = tracksArr.find(tr => tr.kind === 'subtitles' && tr.mode !== 'disabled');
                                        }

                                        if (textTrack) {
                                            console.log('[SimplePlayer] Setting textTrack to showing:', textTrack.label);
                                            textTrack.mode = 'showing';

                                            const updateFF = () => {
                                                if (window.subtitleManager && /Firefox/i.test(navigator.userAgent)) {
                                                    const activeCues = textTrack.activeCues;
                                                    const overlay = document.getElementById('firefox-subtitle-overlay');
                                                    if (overlay) {
                                                        if (activeCues && activeCues.length > 0) {
                                                            overlay.innerHTML = Array.from(activeCues).map(c => c.text).join('\n');
                                                            overlay.classList.add('active');
                                                        } else {
                                                            overlay.classList.remove('active');
                                                        }
                                                    }
                                                }
                                            };

                                            textTrack.oncuechange = (e) => {
                                                updateFF();
                                                if (textTrack.activeCues && textTrack.activeCues.length > 0) {
                                                    console.log('[SimplePlayer] Active cues:', Array.from(textTrack.activeCues).map(c => c.text));
                                                }
                                            };

                                            updateFF();
                                        } else {
                                            console.log('[SimplePlayer] TextTrack not found yet, retrying... Available:', tracksArr.length);
                                            setTimeout(setupTrack, 200);
                                        }
                                    };

                                    track.addEventListener('load', () => {
                                        console.log('[SimplePlayer] Track element loaded');
                                        setTimeout(setupTrack, 100);
                                    });

                                    track.addEventListener('error', (err) => {
                                        console.error('[SimplePlayer] Track load error:', err);
                                    });

                                    setTimeout(setupTrack, 500);
                                } else {
                                    console.log('[SimplePlayer] Subtitles turned OFF');
                                }
                            }

                        p.container.querySelectorAll('.subtitle-option').forEach(el => el.classList.remove('active'));
                        opt.classList.add('active');
                        if (!keepMenuOpen) p.subtitleMenu.classList.remove('active');
                        localStorage.setItem('jmedia_last_track_' + p.videoId, t.id);

                        p.currentSubtitleTrackId = t.id;
                    };
                    list.appendChild(opt);
                    if (p.lastSelectedTrackId == t.id) opt.click();
                });

                if (p.lastSelectedTrackId !== null && p.lastSelectedTrackId !== 'off') {
                    const matched = tracks.some(t => t.id == p.lastSelectedTrackId);
                    if (!matched && tracks.length > 0) {
                        list.querySelector('.subtitle-option:not(#sub-off)')?.click();
                    }
                }

                if (p.lastSelectedTrackId == null && list.querySelector('#sub-off')) {
                    list.querySelector('#sub-off').click();
                }

                sessionStorage.removeItem('jmedia_global_subtitle_track');
            } catch (e) { console.error('Subtitle load failed', e); }
        }

        syncForNativeFullscreen() {
            const p = this.player;
            if (p.jassubRenderer) {
                console.log('[SimplePlayer] Hiding ASS overlay for native fullscreen');
                const canvas = document.getElementById('assCanvas');
                if (canvas) canvas.style.display = 'none';
            }

            // Only show the selected subtitle track during native fullscreen
            if (p.video && p.video.textTracks) {
                for (let i = 0; i < p.video.textTracks.length; i++) {
                    const track = p.video.textTracks[i];
                    if (track.mode === 'hidden') continue;
                    const trackEl = p.video.querySelector(`track[id="subtitle-track-${p.lastSelectedTrackId}"]`);
                    const isSelected = trackEl && (track.label === trackEl.label);
                    track.mode = isSelected ? 'showing' : 'hidden';
                }
            }
        }

        restoreAfterFullscreen() {
            const p = this.player;
            if (p.jassubRenderer) {
                console.log('[SimplePlayer] Restoring ASS overlay after fullscreen');
                const canvas = document.getElementById('assCanvas');
                if (canvas) {
                    canvas.style.display = '';
                    const parent = canvas.parentNode;
                    const videoWrapper = p.container.querySelector('.video-wrapper');
                    if (videoWrapper && parent !== videoWrapper) {
                        videoWrapper.appendChild(canvas);
                    }
                }
                p.jassubRenderer.resize();
            }

            // Restore subtitle track selection after fullscreen
            if (p.video && p.video.textTracks && p.lastSelectedTrackId && p.lastSelectedTrackId !== 'off') {
                for (let i = 0; i < p.video.textTracks.length; i++) {
                    const track = p.video.textTracks[i];
                    const trackEl = p.video.querySelector(`track[id="subtitle-track-${p.lastSelectedTrackId}"]`);
                    const isSelected = trackEl && (track.label === trackEl.label);
                    track.mode = isSelected ? 'showing' : 'hidden';
                }
            }

            if (p.lastSelectedTrackId && p.lastSelectedTrackId !== 'off' && window.subtitleManager) {
                setTimeout(() => window.subtitleManager.applyGlobalStyle(window.subtitleManager.getStyle()), 300);
            }
        }
    };
})(window);
