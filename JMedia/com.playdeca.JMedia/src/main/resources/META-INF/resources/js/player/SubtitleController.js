(function(window) {
    'use strict';

    // Logging gate: verbose player tracing is off unless localStorage.jmedia_debug
    // is "1". warn/error stay unconditional so real failures are never hidden.
    const JMEDIA_DEBUG = (function () {
        try { return localStorage.getItem('jmedia_debug') === '1'; } catch (e) { return false; }
    })();
    const dbg = JMEDIA_DEBUG ? console.log.bind(console) : function () {};

    // JASSUB.destroy() ends with `await this.ready` and `await renderer[releaseProxy]()`.
    // When the worker never answers (it died, was blocked, or never became ready)
    // both promises stay pending forever. Teardown shares the serialized _assChain
    // with init, so one stuck destroy silently disables every later subtitle
    // selection until the page is reloaded. Bound it and kill the worker ourselves.
    const ASS_DESTROY_TIMEOUT_MS = 800;

    /** JASSUB 2.5.36 as ES modules, see lib/jassub/README.md. */
    const JASSUB_BASE = '/lib/jassub/';

    function warnSubtitleApiOnce() {
        if (!window.__subtitleApiMissingWarned) {
            window.__subtitleApiMissingWarned = true;
            console.warn('[SubtitleApi] window.SubtitleApi not available; falling back to inline fetch');
        }
    }

    window.PlayerSubtitleController = class {
        constructor(player) {
            this.player = player;
        }

        selectSubtitle(trackId, element) {
            const p = this.player;
            dbg('[SimplePlayer] Selecting subtitle track:', trackId);

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
            dbg('[SimplePlayer] Turning off subtitles');
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

        /**
         * Serialized ASS teardown. JASSUB.destroy() is asynchronous: it waits for
         * the worker, terminates it and removes the shared canvas. Starting a new
         * renderer before the previous teardown finishes leaves two workers drawing
         * into one canvas (the previously selected language stays visible) and lets
         * the stale teardown remove the canvas out from under the new renderer (no
         * subtitles at all). Every teardown and every init runs through one chain,
         * so a destroy always fully completes before the next renderer is created.
         */
        destroyAssSubtitle() {
            const p = this.player;
            const run = async () => {
                const stale = p.jassubRenderer;
                p.jassubRenderer = null;
                if (!stale) return;
                dbg('[SimplePlayer] Destroying ASS subtitle renderer');
                await this._destroyRenderer(stale, 'teardown');
            };
            p._assChain = (p._assChain || Promise.resolve()).then(run, run).catch(err => console.error('[Subtitles] ASS chain failed', err));
            return p._assChain;
        }

        /**
         * Whether ASS tracks are drawn by JASSUB (positioning, fonts, signs,
         * karaoke) instead of the server's plain WebVTT conversion. On by default
         * now that lib/jassub is a matching JASSUB 2.5.36 build;
         * localStorage.jmedia_ass_renderer = 'false' switches back to WebVTT.
         */
        assCanvasEnabled() {
            try {
                return localStorage.getItem('jmedia_ass_renderer') !== 'false';
            } catch (e) {
                return true;
            }
        }

        /**
         * Tear down one JASSUB instance without ever blocking the chain: waits for
         * destroy() up to ASS_DESTROY_TIMEOUT_MS, then terminates the worker so a
         * dead/blocked worker cannot leak and the next renderer starts clean.
         */
        async _destroyRenderer(renderer, label) {
            if (!renderer) return;
            let timer = null;
            try {
                await Promise.race([
                    Promise.resolve(renderer.destroy()).catch((e) => {
                        console.warn('[SimplePlayer] ASS destroy failed (' + label + '):', e);
                    }),
                    new Promise((resolve) => {
                        timer = setTimeout(() => {
                            console.warn('[SimplePlayer] ASS destroy timed out after', ASS_DESTROY_TIMEOUT_MS,
                                'ms (' + label + ') - terminating worker and continuing');
                            resolve();
                        }, ASS_DESTROY_TIMEOUT_MS);
                    })
                ]);
            } finally {
                if (timer) clearTimeout(timer);
                try {
                    if (renderer._worker) renderer._worker.terminate();
                } catch (e) {
                    console.warn('[SimplePlayer] ASS worker terminate failed (' + label + '):', e);
                }
            }
        }

        async loadJassub() {
            if (window.JASSUB) return;
            // JASSUB 2 ships as ES modules (see lib/jassub/README.md); the module
            // namespace keeps the `JASSUB.default` shape used below.
            window.JASSUB = await import(JASSUB_BASE + 'jassub.js');
        }

        async initAssSubtitle(trackId) {
            const p = this.player;
            // Atomic with teardown: rapid re-selection (EN -> RU -> EN) chains
            // behind any in-flight destroy instead of interleaving with it.
            // The teardown runs inline (not via destroyAssSubtitle, which would
            // wait on this same chain and deadlock).
            const run = () => this._initAssSubtitleInner(trackId);
            p._assChain = (p._assChain || Promise.resolve()).then(run, run).catch(err => console.error('[Subtitles] ASS chain failed', err));
            return p._assChain;
        }

        async _initAssSubtitleInner(trackId) {
            const p = this.player;
            dbg('[SimplePlayer] Initializing ASS subtitle for track:', trackId);
            // Clear any stale Firefox overlay so a previous native-track cue can't linger.
            try { const ov = document.getElementById('firefox-subtitle-overlay'); if (ov) { ov.innerHTML = ''; ov.classList.remove('active'); } } catch (e) { console.warn('[SimplePlayer] Firefox overlay clear failed:', e); }
            // Inline teardown: this already runs inside the serialized chain, so
            // awaiting the previous renderer here guarantees it is fully gone
            // (worker terminated, canvas released) before the new one starts.
            const stale = p.jassubRenderer;
            p.jassubRenderer = null;
            if (stale) {
                await this._destroyRenderer(stale, 'reinit');
            }
            // A chained destroyAssSubtitle() queued while this init was fetching
            // may have cleared the slot; the chain order guarantees it already ran.
            try {
                await this.loadJassub();
                const res = await fetch(`/api/video/subtitles/track/${trackId}/raw`);
                if (!res.ok) {
                    console.error('[SimplePlayer] Failed to fetch raw subtitle:', res.status);
                    return;
                }
                let content = await res.text();

                // Apply user's saved style preferences (font, size, color) to ASS content
                const savedStyle = JSON.parse(localStorage.getItem('jmedia_subtitle_style') || '{}');
                if (savedStyle.size || savedStyle.color || savedStyle.font) {
                    content = this.applyStyleToAssContent(content, savedStyle);
                }

                // Drop any stale canvas so a previously rendered language can never linger
                // under the new renderer (Firefox in particular kept the old frame).
                const staleCanvas = document.getElementById('assCanvas');
                if (staleCanvas) {
                    try { const ctx = staleCanvas.getContext('2d'); if (ctx) ctx.clearRect(0, 0, staleCanvas.width, staleCanvas.height); } catch (e) { console.warn('[SimplePlayer] Canvas clear failed:', e); }
                    staleCanvas.remove();
                }
                const wrapper = p.container.querySelector('.video-wrapper');
                const canvas = document.createElement('canvas');
                canvas.id = 'assCanvas';
                canvas.className = 'ass-canvas';
                (wrapper || p.container).appendChild(canvas);

                const userCorrection = parseFloat(localStorage.getItem('jmedia_subtitle_correction') || '0');
                const totalOffset = (p.streamStartOffset || 0) + userCorrection;

                // JASSUB 2 has two worker files: workerUrl is its renderer worker,
                // wasm(Worker)Url the libass WebAssembly and its glue. Passing the glue
                // as workerUrl (as before) left `ready` unresolved forever. defaultFont
                // is a font NAME (the file goes in availableFonts) and timeOffset is
                // in seconds - it is added to the video's mediaTime.
                p.jassubRenderer = new JASSUB.default({
                    video: p.video,
                    canvas: canvas,
                    subContent: content,
                    workerUrl: JASSUB_BASE + 'worker/worker.js',
                    wasmUrl: JASSUB_BASE + 'wasm/jassub-worker.wasm',
                    wasmWorkerUrl: JASSUB_BASE + 'wasm/jassub-worker.js',
                    modernWasmUrl: JASSUB_BASE + 'wasm/jassub-worker-modern.wasm',
                    modernWasmWorkerUrl: JASSUB_BASE + 'wasm/jassub-worker-modern.js',
                    availableFonts: { 'liberation sans': JASSUB_BASE + 'default.woff2' },
                    defaultFont: 'liberation sans',
                    // Local fonts only (with permission); never fetch from Google Fonts.
                    queryFonts: 'local',
                    timeOffset: totalOffset,
                    prescaleFactor: 1.0,
                    prescaleHeightLimit: 1080,
                    maxRenderHeight: 2160,
                    debug: false
                });

                dbg('[SimplePlayer] JASSUB renderer initialized');
                // Force an initial render even when the video is paused (autoplay blocked):
                // requestVideoFrameCallback only fires during playback, so without this
                // subtitles stay invisible until the user interacts (seek/play).\
                try {
                    p.jassubRenderer.ready.then(() => {
                        if (p._destroyed || !p.jassubRenderer) return;
                        const data = { mediaTime: p.video.currentTime || 0,
                            width: p.video.videoWidth || 1280,
                            height: p.video.videoHeight || 720 };
                        try { p.jassubRenderer.manualRender(data); } catch (e) {
                            console.warn('[SimplePlayer] initial JASSUB render failed:', e);
                        }
                    });
                } catch (e) {
                    console.warn('[SimplePlayer] JASSUB ready hook failed:', e);
                }
                p.lastSelectedTrackId = trackId;
            } catch (e) {
                console.error('[SimplePlayer] ASS subtitle init failed:', e);
            }
        }

        hexToAssColor(hex) {
            const r = parseInt(hex.slice(1, 3), 16);
            const g = parseInt(hex.slice(3, 5), 16);
            const b = parseInt(hex.slice(5, 7), 16);
            return '&H00' +
                b.toString(16).padStart(2, '0').toUpperCase() +
                g.toString(16).padStart(2, '0').toUpperCase() +
                r.toString(16).padStart(2, '0').toUpperCase();
        }

        applyStyleToAssContent(content, style) {
            const fontSize = style.size || 20;
            const colorHex = style.color || '#ffffff';
            const assColor = this.hexToAssColor(colorHex);
            const fontName = (style.font || "'Segoe UI'").replace(/['"]/g, '').split(',')[0].trim();

            return content.replace(/^(Style:\s*[^,]*),([^,]*),([^,]*),([^,]*),/gm,
                (match, prefix, oldFont, oldSize, oldColor) => {
                    return `${prefix},${fontName},${fontSize},${assColor},`;
                }
            );
        }

        reapplyAssStyles() {
            const p = this.player;
            if (p && p.jassubRenderer && p.lastSelectedTrackId && p.lastSelectedTrackId !== 'off') {
                dbg('[SimplePlayer] Reapplying ASS subtitle styles');
                this.initAssSubtitle(p.lastSelectedTrackId);
            }
        }

        async loadSubtitles(keepMenuOpen = false) {
            const p = this.player;
            // Several callers can overlap (metadata, every seek, the restore retry
            // interval, preload). Without a generation guard an older response can
            // rebuild the menu after a newer one - or after a video switch - leaving the
            // previous video's tracks on screen.
            const gen = (p._subtitleLoadGen = (p._subtitleLoadGen || 0) + 1);
            try {
                dbg('[SimplePlayer] Loading subtitles for video:', p.videoId);
                let tracks;
                if (window.SubtitleApi) {
                    tracks = await window.SubtitleApi.getTracks(p.videoId);
                } else {
                    warnSubtitleApiOnce();
                    const tracksRes = await fetch(`/api/video/subtitles/${p.videoId}`);
                    if (gen !== p._subtitleLoadGen) {
                        dbg('[SimplePlayer] Subtitle response superseded by a newer load');
                        return;
                    }
                    if (!tracksRes.ok) {
                        console.error('[SimplePlayer] Subtitle API error:', tracksRes.status);
                        return;
                    }
                    const tracksData = await tracksRes.json();
                    if (gen !== p._subtitleLoadGen) {
                        dbg('[SimplePlayer] Subtitle payload superseded by a newer load');
                        return;
                    }
                    dbg('[SimplePlayer] Subtitle response:', tracksData);
                    tracks = tracksData.tracks || tracksData.data || [];
                }
                if (gen !== p._subtitleLoadGen) {
                    dbg('[SimplePlayer] Subtitle payload superseded by a newer load');
                    return;
                }
                dbg('[SimplePlayer] Subtitle response:', tracks);
                dbg('[SimplePlayer] Found tracks:', tracks.length);

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
                        dbg('[SimplePlayer] Selecting subtitle:', t);

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
                                    const isAss = t.format === 'ass' || t.format === 'ssa';
                                    // ASS/SSA are drawn by JASSUB (lib/jassub, 2.5.36), which
                                    // keeps positioning, fonts, signs and karaoke. iOS keeps
                                    // the server's WebVTT conversion: its native fullscreen
                                    // shows text tracks but never a canvas overlay. Opt out
                                    // anywhere with localStorage.jmedia_ass_renderer='false'.
                                    const useAssCanvas = isAss && !p.utils.isIOS() && this.assCanvasEnabled();
                                    if (useAssCanvas) {
                                        this.initAssSubtitle(t.id);
                                    } else {
                                        // Drop any previous <track> elements first. They hold the OLD
                                        // ?start= offset, and because the new track reuses the same id
                                        // ("subtitle-track-<id>"), a re-select after a seek matched the
                                        // stale element and the fresh one never showed — which is why
                                        // subtitles disappeared on a forward skip until you toggled them.
                                        p.video.querySelectorAll('track').forEach(el => el.remove());

                                        const track = document.createElement('track');
                                        track.kind = 'subtitles';

                                        const correction = localStorage.getItem('jmedia_subtitle_correction') || 0;

                                        const startOffset = p.streamStartOffset || 0;
                                        let src;
                                        if (window.SubtitleApi) {
                                            src = window.SubtitleApi.trackSrc(p.videoId, t, {
                                                start: startOffset,
                                                exact: !!p._streamOriginAuthoritative,
                                                correction: parseFloat(correction) !== 0 ? correction : undefined
                                            });
                                        } else {
                                            warnSubtitleApiOnce();
                                            src = `/api/video/subtitles/track/${t.id}?start=${startOffset}&v=6`;
                                            // When the offset came from the stream's own
                                            // X-Stream-Origin it is already where the picture
                                            // begins (a fragment boundary). Re-snapping it to a
                                            // keyframe on the server shifts the cues by the gap
                                            // between the two — the few-tenths-of-a-second drift
                                            // that only some titles show.
                                            if (p._streamOriginAuthoritative) {
                                                src += '&exact=1';
                                            }
                                            if (parseFloat(correction) !== 0) {
                                                src += `&correction=${correction}`;
                                            }
                                        }

                                        track.src = src;
                                        track.srclang = t.language || 'en';
                                        track.label = t.displayName || 'Subtitle';
                                        track.default = true;
                                        track.id = 'subtitle-track-' + t.id;
                                        p.video.appendChild(track);

                                        const capturedVideoId = p.videoId;
                                        let setupAttempts = 0;
                                        const MAX_SETUP_ATTEMPTS = 25;
                                        let setupWarned = false;
                                        const scheduleSetup = (delay) => {
                                            if (p._destroyed) return;
                                            if (p.videoId !== capturedVideoId) return;
                                            if (setupAttempts >= MAX_SETUP_ATTEMPTS) {
                                                if (!setupWarned) {
                                                    setupWarned = true;
                                                    console.warn('[SimplePlayer] Subtitle track setup exceeded max attempts (' + MAX_SETUP_ATTEMPTS + '), stopping');
                                                }
                                                return;
                                            }
                                            setupAttempts++;
                                            p._setupTrackTimer = setTimeout(setupTrack, delay);
                                        };
                                        const setupTrack = () => {
                                            if (p._destroyed) return;
                                            if (p.videoId !== capturedVideoId) return;
                                            const tracksArr = Array.from(p.video.textTracks || []);
                                            dbg('[SimplePlayer] Available textTracks:', tracksArr.map(tr => ({ label: tr.label, mode: tr.mode, kind: tr.kind })));

                                            // Match by the appended <track> element's id (unique per subtitle id) so two
                                            // tracks sharing a displayName (e.g. "Español" vs "Español (LATAM)") resolve to
                                            // the exact track the user clicked. Fall back to label matching if the element's
                                            // textTrack isn't exposed yet.
                                            let textTrack = Array.from(p.video.querySelectorAll('track')).find(el => el.id === 'subtitle-track-' + t.id)?.track;

                                            if (!textTrack) {
                                                textTrack = tracksArr.find(tr => tr.label === (t.displayName || 'Subtitle'));
                                            }

                                            if (!textTrack) {
                                                textTrack = tracksArr.find(tr => tr.kind === 'subtitles' && tr.mode !== 'disabled');
                                            }

                                            if (textTrack) {
                                                dbg('[SimplePlayer] Setting textTrack to showing:', textTrack.label);
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
                                                        dbg('[SimplePlayer] Active cues:', Array.from(textTrack.activeCues).map(c => c.text));
                                                    }
                                                };

                                                updateFF();
                                            } else {
                                                dbg('[SimplePlayer] TextTrack not found yet, retrying... Available:', tracksArr.length);
                                                scheduleSetup(200);
                                            }
                                        };

                                        track.addEventListener('load', () => {
                                            dbg('[SimplePlayer] Track element loaded');
                                            scheduleSetup(100);
                                        });

                                        track.addEventListener('error', (err) => {
                                            console.error('[SimplePlayer] Track load error:', err);
                                        });

                                        scheduleSetup(500);
                                    }
                                } else {
                                    dbg('[SimplePlayer] Subtitles turned OFF');
                                }
                            }

                        p.container.querySelectorAll('.subtitle-option').forEach(el => el.classList.remove('active'));
                        opt.classList.add('active');
                        if (!keepMenuOpen) p.subtitleMenu.classList.remove('active');
                        localStorage.setItem('jmedia_last_track_' + p.videoId, t.id);

                        // The canonical "active track" used by loadSubtitles() to
                        // re-select on a rebuild (seek/quality) and by the seek path.
                        // Without this a fresh selection was never restorable.
                        p.lastSelectedTrackId = t.id;
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
                dbg('[SimplePlayer] Hiding ASS overlay for native fullscreen');
                const canvas = document.getElementById('assCanvas');
                if (canvas) canvas.style.display = 'none';
            }

            // Only show the selected subtitle track during native fullscreen
            if (p.video && p.video.textTracks) {
                for (let i = 0; i < p.video.textTracks.length; i++) {
                    const track = p.video.textTracks[i];
                    if (track.mode === 'hidden') continue;
                    const trackEl = p.video.querySelector(`track[id="subtitle-track-${p.lastSelectedTrackId}"]`);
                    const isSelected = trackEl && (track === trackEl.track || (trackEl.track == null && track.label === trackEl.label));
                    track.mode = isSelected ? 'showing' : 'hidden';
                }
            }
        }

        restoreAfterFullscreen() {
            const p = this.player;
            if (p.jassubRenderer) {
                dbg('[SimplePlayer] Restoring ASS overlay after fullscreen');
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
                    const isSelected = trackEl && (track === trackEl.track || (trackEl.track == null && track.label === trackEl.label));
                    track.mode = isSelected ? 'showing' : 'hidden';
                }
            }

            if (p.lastSelectedTrackId && p.lastSelectedTrackId !== 'off' && window.subtitleManager) {
                setTimeout(() => window.subtitleManager.applyGlobalStyle(window.subtitleManager.getStyle()), 300);
            }
        }
    };

    window.reapplyAssSubtitleStyles = function() {
        const p = window.currentPlayerInstance;
        if (p && p.subtitleController && typeof p.subtitleController.reapplyAssStyles === 'function') {
            p.subtitleController.reapplyAssStyles();
        }
    };
})(window);
