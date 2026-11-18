// Guarded singleton API client for subtitle tracks. Defined once per page so
// every caller (subtitle manager, player, settings UI) shares one fetch/URL
// implementation instead of copy-pasting it.
window.SubtitleApi = window.SubtitleApi || {
    normalize(json) { return (json && (json.tracks || json.data)) || []; },
    async getTracks(videoId) {
        const r = await fetch('/api/video/subtitles/' + encodeURIComponent(videoId));
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return this.normalize(await r.json());
    },
    trackSrc(videoId, track, opts) {
        opts = opts || {};
        const t = track || {};
        const id = (t && t.id != null) ? t.id : t;
        const start = (opts.start != null) ? opts.start : 0;
        const exact = !!opts.exact;
        const version = (opts.version === undefined) ? 6 : opts.version;
        const correction = opts.correction;
        let url = '/api/video/subtitles/track/' + id + '?start=' + start;
        if (version != null && version !== false) url += '&v=' + version;
        if (exact) url += '&exact=1';
        if (correction != null && parseFloat(correction) !== 0) url += '&correction=' + correction;
        return url;
    }
};

function warnSubtitleApiOnce() {
    if (!window.__subtitleApiMissingWarned) {
        window.__subtitleApiMissingWarned = true;
        console.warn('[SubtitleApi] window.SubtitleApi not available; falling back to inline fetch');
    }
}

class SubtitleManager {
    constructor() {
        this.currentVideoId = null;
        this.currentVideoTitle = '';
        this.baseBottomOffset = 60; // Default subtitle bottom offset
        this.currentStyle = null; // Will store current subtitle style
        this.boundVideo = null;
        this.boundContainer = null;
        this.aiPollInterval = null;
        this.aiCancelRequested = false;
        this.aiPollVideoId = null;
        this.aiPollAttempts = 0;
        
        // Auto-apply saved styles on initialization
        this.applySavedStyle();
        
        // Initialize correction UI
        this.initCorrectionUI();
    }

    escapeHtml(str) {
        return String(str == null ? '' : str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    // Explicitly bind to a video and its container
    bindVideo(video, container) {
        console.log('[SubtitleManager] Binding to video element', video.id);
        this.boundVideo = video;
        this.boundContainer = container;

        // The DOM overlay exists for one reason: Firefox will neither style nor
        // position ::cue the way Chromium does. Enabling it on every browser also
        // put body.firefox-subtitle-mode on the document, whose rule hides the
        // native cues (video::cue { visibility: hidden }) and hands rendering to
        // this div - a div whose position is driven by our own controls
        // (setSubtitleLift 45px while the UI is visible, 0 once it auto-hides), so
        // on Chrome the subtitles moved with the UI and could end up unreadable or
        // out of view. Chromium renders native cues correctly (the runtime CSS
        // positions them through ::-webkit-media-text-track-container); only
        // Firefox needs the overlay.
        if (/Firefox/i.test(navigator.userAgent)) {
            this.setupFirefoxSubtitlePositioning();
        } else if (document.body.classList.contains('firefox-subtitle-mode')) {
            document.body.classList.remove('firefox-subtitle-mode');
            const stale = document.getElementById('firefox-subtitle-overlay');
            if (stale) stale.remove();
            this.overlayElement = null;
        }
    }

    getStyle() {
        const saved = JSON.parse(localStorage.getItem('jmedia_subtitle_style') || '{}');
        const defaults = {
            font: "'Segoe UI', sans-serif",
            size: 20,
            color: '#ffffff',
            bgOpacity: 0.7,
            lineHeight: 1.4,
            bottom: 60,
            correction: 0
        };
        return { ...defaults, ...saved };
    }

    applySavedStyle() {
        const style = this.getStyle();
        this.applyGlobalStyle(style);
    }

    openModal(videoId, videoTitle, videoPath) {
        this.currentVideoId = videoId;
        this.currentVideoTitle = videoTitle;
        
        const pathEl = document.getElementById('subtitle-modal-path');
        if (pathEl) {
            pathEl.textContent = videoPath || '';
        }
        const modal = document.getElementById('subtitleManagementModal');
        if (!modal) { console.warn('[SubtitleManager] subtitleManagementModal not found'); return; }
        modal.classList.add('is-active');
        
        // Refresh subtitle list from server
        this.refreshSubtitleList();
        this.switchTab('style');
    }
    
    async refreshSubtitleList() {
        const requestedVideoId = this.currentVideoId;
        if (!requestedVideoId) return;

        try {
            let tracks;
            if (window.SubtitleApi) {
                tracks = await window.SubtitleApi.getTracks(requestedVideoId);
            } else {
                warnSubtitleApiOnce();
                const response = await fetch(`/api/video/subtitles/${requestedVideoId}`);
                if (!response.ok) return;
                const data = await response.json();
                tracks = data.tracks || data.data || [];
            }

            const cpi = window.currentPlayerInstance;
            if (!cpi || cpi._destroyed || String(cpi.videoId) !== String(requestedVideoId)) {
                console.warn('[SubtitleManager] refreshSubtitleList discarded: video changed');
                return;
            }
            if (typeof cpi.loadSubtitles === 'function') {
                await cpi.loadSubtitles(true);
            }
        } catch (error) {
            console.error('Failed to refresh subtitle list:', error);
        }
    }

    closeModal(e) {
        if (e) e.stopPropagation();
        const modal = document.getElementById('subtitleManagementModal');
        if (!modal) { console.warn('[SubtitleManager] subtitleManagementModal not found'); return; }
        modal.classList.remove('is-active');
    }

    switchTab(tab) {
        const tabs = ['style'];
        tabs.forEach(t => {
            const btn = document.getElementById(`${t}-tab-btn`);
            const content = document.getElementById(`${t}-tab-content`);
            if (btn) btn.classList.toggle('is-active', tab === t);
            if (content) content.style.display = tab === t ? 'block' : 'none';
        });

        if (tab === 'manual') this.scanLocal();
        else if (tab === 'style') this.loadStyle();
        else if (tab === 'ai') {
            this.loadAiAudioTracks();
            this.loadTranslateTracks();
        }
    }

    async loadAiAudioTracks() {
        const sel = document.getElementById('subtitleAiAudioTrack');
        const requestedVideoId = this.currentVideoId;
        if (!sel || !requestedVideoId) return;

        const prev = sel.value;
        sel.innerHTML = '<option value="">Default audio track</option>';
        try {
            const res = await fetch(`/api/video/${encodeURIComponent(requestedVideoId)}/audio-tracks`);
            if (!res.ok) return;
            const json = await res.json();
            const cpi = window.currentPlayerInstance;
            if (!cpi || cpi._destroyed || String(cpi.videoId) !== String(requestedVideoId)) {
                console.warn('[SubtitleManager] loadAiAudioTracks discarded: video changed');
                return;
            }
            const tracks = json.data || [];
            tracks.forEach((track, idx) => {
                const label = track.displayName || track.languageName || track.languageCode || `Audio ${idx + 1}`;
                const opt = document.createElement('option');
                opt.value = track.trackIndex != null ? track.trackIndex : idx;
                opt.textContent = label;
                sel.appendChild(opt);
            });
            if (prev && Array.from(sel.options).some(o => o.value === prev)) sel.value = prev;
        } catch (e) {
            console.warn('Failed to load AI audio tracks:', e);
        }
    }

    onFileSelected(event) {
        const files = event && event.target ? event.target.files : null;
        const file = files && files.length ? files[0] : null;
        const nameEl = document.getElementById('subtitleUploadFileName');
        const btn = document.getElementById('uploadSubtitleBtn');
        if (!nameEl || !btn) {
            console.warn('[SubtitleManager] subtitle upload elements not found');
            return;
        }
        if (file) {
            nameEl.textContent = file.name;
            btn.disabled = false;
        } else {
            nameEl.textContent = 'No file selected';
            btn.disabled = true;
        }
    }

    async uploadSubtitle() {
        const requestedVideoId = this.currentVideoId;
        const fileInput = document.getElementById('subtitleFileInput');
        if (!fileInput) { console.warn('[SubtitleManager] subtitleFileInput not found'); return; }
        const file = fileInput.files && fileInput.files.length ? fileInput.files[0] : null;
        if (!file) return;

        const btn = document.getElementById('uploadSubtitleBtn');
        if (!btn) { console.warn('[SubtitleManager] uploadSubtitleBtn not found'); return; }
        btn.classList.add('is-loading');
        btn.disabled = true;

        try {
            const content = await this.readFileAsBase64(file);
            const langEl = document.getElementById('subtitleUploadLanguage');
            const nameEl = document.getElementById('subtitleUploadName');
            if (!langEl || !nameEl) { console.warn('[SubtitleManager] subtitle upload fields not found'); return; }
            const language = langEl.value;
            const displayName = nameEl.value.trim();

            const res = await fetch(`/api/video/subtitles/${requestedVideoId}/upload`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    content: content,
                    filename: file.name,
                    language: language,
                    languageName: displayName || file.name
                })
            });

            const data = await res.json();
            const cpi = window.currentPlayerInstance;
            if (!cpi || cpi._destroyed || String(cpi.videoId) !== String(requestedVideoId)) {
                console.warn('[SubtitleManager] upload discarded: video changed');
                return;
            }
            if (res.ok) {
                if (window.showToast) window.showToast('Subtitle uploaded!', 'success');
                this.refreshSubtitleList();
                fileInput.value = '';
                const fnEl = document.getElementById('subtitleUploadFileName');
                const nmEl = document.getElementById('subtitleUploadName');
                if (fnEl) fnEl.textContent = 'No file selected';
                if (nmEl) nmEl.value = '';
            } else {
                if (window.showToast) window.showToast(data.error || 'Upload failed', 'error');
            }
        } catch (e) {
            console.error('Upload error:', e);
            if (window.showToast) window.showToast('Upload error: ' + e.message, 'error');
        } finally {
            btn.classList.remove('is-loading');
            btn.disabled = true;
        }
    }

    readFileAsBase64(file) {
        return new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.onload = () => resolve(reader.result);
            reader.onerror = () => reject(new Error('Failed to read file'));
            reader.readAsDataURL(file);
        });
    }

    /**
     * The player's subtitle panel and the Subtitle Style dialog both use the
     * same element ids (subPreviewText, subStyleSize, fontSizeVal, ...). When the
     * dialog is open it must win, otherwise getElementById updates the hidden
     * panel and the dialog preview never changes.
     */
    _styleRoot() {
        const modal = document.getElementById('subtitleManagementModal');
        return (modal && modal.classList.contains('is-active')) ? modal : document;
    }

    loadStyle() {
        const saved = JSON.parse(localStorage.getItem('jmedia_subtitle_style') || '{}');
        const style = {
            font: saved.font || "'Segoe UI', sans-serif",
            size: saved.size || 20,
            color: saved.color || '#ffffff',
            bgOpacity: saved.bgOpacity || 0.7,
            lineHeight: saved.lineHeight || 1.4,
            bottom: saved.bottom || 60
        };

        const root = this._styleRoot();
        const setVal = (id, value) => {
            const el = root.querySelector('#' + id);
            if (el) el.value = value;
        };
        setVal('subStyleFont', style.font);
        setVal('subStyleSize', style.size);
        setVal('subStyleColor', style.color);
        setVal('subStyleBgOpacity', style.bgOpacity);
        setVal('subStyleLineHeight', style.lineHeight);
        setVal('subStyleBottom', style.bottom);

        this.updateStyle(false);
    }

    updateStyle(shouldApply = true) {
        const root = this._styleRoot();
        const getVal = (id, def) => {
            const el = root.querySelector('#' + id);
            return el ? el.value : def;
        };

        const style = {
            font: getVal('subStyleFont', "'Segoe UI', sans-serif"),
            size: parseInt(getVal('subStyleSize', 20)),
            color: getVal('subStyleColor', '#ffffff'),
            bgOpacity: parseFloat(getVal('subStyleBgOpacity', 0.7)),
            lineHeight: parseFloat(getVal('subStyleLineHeight', 1.4)),
            bottom: parseInt(getVal('subStyleBottom', 60))
        };

        // Update UI labels in the dialog (scoped: the player panel has the same ids)
        const setLabel = (id, val) => {
            const el = root.querySelector('#' + id);
            if (el) el.textContent = val;
        };

        setLabel('fontSizeVal', style.size);
        setLabel('bgOpacityVal', style.bgOpacity);
        setLabel('bottomDistVal', style.bottom);

        const hexInput = root.querySelector('#subStyleColorHex');
        if (hexInput) hexInput.value = style.color;

        // Update live preview in the modal
        const preview = root.querySelector('#subPreviewText');
        if (preview) {
            preview.style.fontFamily = style.font;
            preview.style.fontSize = style.size + 'px';
            preview.style.color = style.color;
            preview.style.backgroundColor = `rgba(0, 0, 0, ${style.bgOpacity})`;
            preview.style.lineHeight = style.lineHeight;
            // The real overlay is anchored to the bottom edge; mirror that so the
            // Bottom Distance slider has a visible effect in the preview.
            preview.style.bottom = style.bottom + 'px';
        }

        if (shouldApply) {
            this.applyGlobalStyle(style);
        }
    }

    saveStyle(externalStyle) {
        let style;
        if (externalStyle) {
            const defaults = {
                font: "'Segoe UI', sans-serif",
                size: 20,
                color: '#ffffff',
                bgOpacity: 0.7,
                lineHeight: 1.4,
                bottom: 60
            };
            style = { ...defaults, ...externalStyle };
        } else {
            const root = this._styleRoot();
            const getVal = (id, def) => {
                const el = root.querySelector('#' + id);
                return el ? el.value : def;
            };
            style = {
                font: getVal('subStyleFont', "'Segoe UI', sans-serif"),
                size: parseInt(getVal('subStyleSize', 20)),
                color: getVal('subStyleColor', '#ffffff'),
                bgOpacity: parseFloat(getVal('subStyleBgOpacity', 0.7)),
                lineHeight: parseFloat(getVal('subStyleLineHeight', 1.4)),
                bottom: parseInt(getVal('subStyleBottom', 60))
            };
        }

        localStorage.setItem('jmedia_subtitle_style', JSON.stringify(style));
        this.applyGlobalStyle(style);
        if (typeof window.reapplyAssSubtitleStyles === 'function') {
            window.reapplyAssSubtitleStyles();
        }
        if (window.showToast) window.showToast('Style saved!', 'success');
    }

    resetStyle() {
        const defaults = {
            font: "'Segoe UI', sans-serif",
            size: 20,
            color: '#ffffff',
            bgOpacity: 0.7,
            lineHeight: 1.4,
            bottom: 60
        };
        
        localStorage.setItem('jmedia_subtitle_style', JSON.stringify(defaults));
        this.loadStyle();
        if (typeof window.reapplyAssSubtitleStyles === 'function') {
            window.reapplyAssSubtitleStyles();
        }
        if (window.showToast) window.showToast('Reset to defaults', 'info');
    }

    applyGlobalStyle(style) {
        this.baseBottomOffset = parseInt(style.bottom) || 60;
        this.currentStyle = style;

        const root = document.documentElement;
        root.style.setProperty('--subtitle-font-family', style.font);
        root.style.setProperty('--subtitle-font-size', style.size + 'px');
        root.style.setProperty('--subtitle-text-color', style.color);
        root.style.setProperty('--subtitle-bg-color', `rgba(0, 0, 0, ${style.bgOpacity})`);
        root.style.setProperty('--subtitle-line-height', style.lineHeight);
        root.style.setProperty('--subtitle-bottom-offset', this.baseBottomOffset + 'px');
        
        let styleEl = document.getElementById('jmedia-subtitle-runtime-style');
        if (!styleEl) {
            styleEl = document.createElement('style');
            styleEl.id = 'jmedia-subtitle-runtime-style';
            document.head.appendChild(styleEl);
        }

        let css = `
            video::cue, ::cue {
                background-color: var(--subtitle-bg-color) !important;
                color: var(--subtitle-text-color) !important;
                font-family: var(--subtitle-font-family) !important;
                font-size: var(--subtitle-font-size) !important;
                line-height: var(--subtitle-line-height) !important;
                visibility: visible !important;
                display: block !important;
                white-space: pre-wrap !important;
                text-align: center !important;
                text-shadow: 0 2px 4px rgba(0,0,0,0.8) !important;
            }
            
            /* WebKit specific positioning fix */
            video::-webkit-media-text-track-container {
                position: absolute !important;
                bottom: 0 !important;
                left: 0 !important;
                width: 100% !important;
                height: 100% !important;
                display: flex !important;
                flex-direction: column !important;
                justify-content: flex-end !important;
                align-items: center !important;
                padding-bottom: calc(var(--subtitle-bottom-offset) + var(--sub-lift, 0px)) !important;
                transition: padding-bottom 0.3s ease-in-out !important;
                pointer-events: none !important;
                overflow: visible !important;
                z-index: 2147483647 !important;
            }

            video::-webkit-media-text-track-display {
                position: relative !important;
                width: auto !important;
                max-width: 85% !important;
                margin: 0 auto !important;
                text-align: center !important;
            }

            /* Firefox specific positioning overlay */
            .firefox-subtitle-overlay {
                position: absolute !important;
                bottom: calc(var(--subtitle-bottom-offset) + var(--sub-lift, 0px)) !important;
                left: 50% !important;
                transform: translateX(-50%) !important;
                width: auto !important;
                max-width: 85% !important;
                text-align: center !important;
                pointer-events: none !important;
                z-index: 2147483647 !important;
                transition: bottom 0.3s ease-in-out !important;
                color: var(--subtitle-text-color) !important;
                font-family: var(--subtitle-font-family) !important;
                font-size: var(--subtitle-font-size) !important;
                line-height: var(--subtitle-line-height) !important;
                background-color: var(--subtitle-bg-color) !important;
                padding: 10px 20px !important;
                border-radius: 8px !important;
                display: none;
                white-space: pre-wrap !important;
                flex-direction: column;
                align-items: center;
                justify-content: center;
            }
            .firefox-subtitle-overlay.active { display: flex !important; }
            
            body.firefox-subtitle-mode video::cue {
                opacity: 0 !important;
                visibility: hidden !important;
            }
        `;

        styleEl.textContent = css;
        if (this.overlayElement) this.applyOverlayStyles();
    }

    setupFirefoxSubtitlePositioning() {
        document.body.classList.add('firefox-subtitle-mode');
        let overlay = document.getElementById('firefox-subtitle-overlay');
        if (!overlay) {
            overlay = document.createElement('div');
            overlay.id = 'firefox-subtitle-overlay';
            overlay.className = 'firefox-subtitle-overlay';
        }
        
        // Ensure it's in the current container for fullscreen support
        const container = this.boundContainer || document.querySelector('#customPlayer') || document.querySelector('.player-container') || document.body;
        if (overlay.parentElement !== container) {
            container.appendChild(overlay);
        }
        
        this.overlayElement = overlay;
        this.setupFirefoxSubtitleTextExtraction();
        this.applyOverlayStyles();
    }

    setupFirefoxSubtitleTextExtraction() {
        const video = this.boundVideo || document.querySelector('video');
        if (!video || !video.textTracks) return;

        const extractLanguagePart = (text) => {
            const parts = [];
            let pos = 0;
            let defaultText = '';

            while (pos < text.length) {
                const open = text.indexOf('{', pos);
                if (open === -1) {
                    defaultText += text.substring(pos);
                    break;
                }

                if (open > pos) {
                    defaultText += text.substring(pos, open);
                }

                const colon = text.indexOf(':', open + 1);
                if (colon === -1 || colon - open > 10) {
                    defaultText += text.substring(open);
                    break;
                }

                const close = text.indexOf('}', colon);
                if (close === -1) {
                    defaultText += text.substring(open);
                    break;
                }

                const lang = text.substring(open + 1, colon).trim().toLowerCase();
                const content = text.substring(colon + 1, close).trim();
                parts.push({ lang: lang, content: content });

                pos = close + 1;
            }

            defaultText = defaultText.trim();

            if (parts.length === 0) return text;

            let currentLang = (navigator.language || 'en').split('-')[0];
            try {
                const tracks = window.availableAudioTracks || [];
                const currentTrack = tracks.find(t => t.isDefault) || tracks[0];
                if (currentTrack && currentTrack.languageCode) {
                    currentLang = currentTrack.languageCode.toLowerCase();
                }
            } catch (e) { console.debug('[SubtitleManager] could not read the default audio track language', e); }

            let selectedPart = parts.find(p => p.lang === currentLang);
            if (!selectedPart) {
                const shortLang = currentLang.length >= 2 ? currentLang.substring(0, 2) : currentLang;
                selectedPart = parts.find(p => p.lang.substring(0, 2) === shortLang);
            }
            if (!selectedPart && defaultText.length > 0) {
                selectedPart = { content: defaultText };
            }
            if (!selectedPart && parts.length > 0) {
                selectedPart = parts[0];
            }

            return selectedPart ? selectedPart.content : text;
        };

        const updateOverlay = () => {
            if (!this.overlayElement) return;
            const texts = [];
            for (let i = 0; i < video.textTracks.length; i++) {
                const track = video.textTracks[i];
                if (track.mode === 'showing' && track.activeCues) {
                    for (let j = 0; j < track.activeCues.length; j++) {
                        const text = extractLanguagePart(track.activeCues[j].text);
                        texts.push(text.trim());
                    }
                }
            }
            if (texts.length > 0) {
                this.overlayElement.innerHTML = texts.join('\n');
                this.overlayElement.classList.add('active');
            } else {
                this.overlayElement.classList.remove('active');
            }
        };

        const trackedTracks = new WeakSet();

        const attachTrackListeners = () => {
            for (let i = 0; i < video.textTracks.length; i++) {
                if (!trackedTracks.has(video.textTracks[i])) {
                    trackedTracks.add(video.textTracks[i]);
                    video.textTracks[i].addEventListener('cuechange', updateOverlay);
                }
            }
        };

        video.textTracks.addEventListener('change', () => {
            attachTrackListeners();
            updateOverlay();
        });

        attachTrackListeners();
    }

    applyOverlayStyles() {
        if (!this.currentStyle || !this.overlayElement) return;
        const s = this.currentStyle;
        this.overlayElement.style.color = s.color;
        this.overlayElement.style.fontFamily = s.font;
        this.overlayElement.style.fontSize = `${s.size}px`;
        this.overlayElement.style.lineHeight = s.lineHeight;
        this.overlayElement.style.backgroundColor = `rgba(0, 0, 0, ${s.bgOpacity})`;
    }

    setSubtitleLift(liftHeight) {
        document.documentElement.style.setProperty('--sub-lift', liftHeight + 'px');
    }

    initCorrectionUI() {
        const correction = parseFloat(localStorage.getItem('jmedia_subtitle_correction') || '0');
        const valEl = document.getElementById('subCorrectionVal');
        if (valEl) valEl.textContent = correction.toFixed(1) + 's';
    }

    adjustCorrection(delta) {
        let correction = parseFloat(localStorage.getItem('jmedia_subtitle_correction') || '0');
        correction = Math.round((correction + delta) * 10) / 10;
        localStorage.setItem('jmedia_subtitle_correction', correction);
        
        const valEl = document.getElementById('subCorrectionVal');
        if (valEl) {
            valEl.textContent = (correction >= 0 ? '+' : '') + correction.toFixed(1) + 's';
        }
        
        if (window.currentPlayerInstance) {
            window.currentPlayerInstance.loadSubtitles(true); // true = keep menu open
        }
    }

    async scanLocal() {
        const body = document.getElementById('subtitleManualResultsBody');
        if (!body) return;
        body.innerHTML = '<tr><td colspan="4" class="has-text-centered">Scanning...</td></tr>';
        try {
            const res = await fetch(`/api/video/subtitles/${this.currentVideoId}/local-files`);
            const data = await res.json();
            if (data.length === 0) {
                body.innerHTML = '<tr><td colspan="4" class="has-text-centered">No files found</td></tr>';
                return;
            }
            const esc = (s) => this.escapeHtml(s);
            body.innerHTML = data.map(file => `
                <tr>
                    <td>${esc(file.filename)}</td>
                    <td>${esc(file.languageName || 'Unknown')}</td>
                    <td style="font-size:0.7rem; color:#aaa;">${esc(file.fullPath)}</td>
                    <td><button class="button is-small is-info subtitle-add-local" data-add-local-path="${esc(file.fullPath)}">Add</button></td>
                </tr>
            `).join('');
        } catch (e) {
            console.warn('[SubtitleManager] local subtitle scan failed', e);
            body.innerHTML = '<tr><td colspan="4">Error</td></tr>';
        }
    }

    async addLocal(filePath, btn) {
        btn.classList.add('is-loading');
        try {
            const res = await fetch(`/api/video/subtitles/${this.currentVideoId}/add-local`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ filePath })
            });
            if (res.ok) {
                if (window.showToast) window.showToast('Added!', 'success');
                this.refreshSubtitleList();
            } else {
                // The button used to just stop spinning on failure, so "Add" looked
                // like it did nothing. Surface the reason instead.
                console.warn('[SubtitleManager] add-local failed with HTTP ' + res.status);
                if (window.showToast) window.showToast('Could not add that subtitle', 'error');
            }
        } catch (e) {
            console.error('[SubtitleManager] add-local request failed', e);
            if (window.showToast) window.showToast('Could not add that subtitle', 'error');
        } finally { btn.classList.remove('is-loading'); }
    }

    async generateAiSubtitles() {
        const requestedVideoId = this.currentVideoId;
        if (!requestedVideoId) return;

        const btn = document.getElementById('startAiSubtitleBtn');
        if (!btn) return;

        btn.classList.add('is-loading');
        btn.disabled = true;
        this.aiCancelRequested = false;

        const statusEl = document.getElementById('aiGenerationStatus');
        if (statusEl) statusEl.textContent = 'Starting generation...';

        try {
            const audioSel = document.getElementById('subtitleAiAudioTrack');
            const audioTrack = audioSel && audioSel.value !== '' ? audioSel.value : null;
            const langSel = document.getElementById('subtitleAiLanguage');
            const language = langSel && langSel.value !== '' ? langSel.value : null;
            let url = `/api/video/subtitles/${requestedVideoId}/generate`;
            if (audioTrack != null) url += '?audioTrack=' + encodeURIComponent(audioTrack);
            if (language != null) url += (audioTrack != null ? '&' : '?') + 'language=' + encodeURIComponent(language);
            const res = await fetch(url, { method: 'POST' });

            const cpi = window.currentPlayerInstance;
            if (!cpi || cpi._destroyed || String(cpi.videoId) !== String(requestedVideoId)) {
                console.warn('[SubtitleManager] generateAiSubtitles discarded: video changed');
                btn.classList.remove('is-loading');
                btn.disabled = false;
                return;
            }

            if (res.status === 503) {
                const message = 'Parakeet AI is not available on this server';
                if (window.showToast) window.showToast(message, 'error');
                if (statusEl) statusEl.textContent = message;
                btn.classList.remove('is-loading');
                btn.disabled = false;
                return;
            }

            if (!res.ok) {
                let message = 'Failed to start AI generation';
                try {
                    const errData = await res.json();
                    if (errData && errData.error) message = errData.error;
                } catch (e) { console.debug('[SubtitleManager] error body was not JSON', e); }
                if (window.showToast) window.showToast(message, 'error');
                btn.classList.remove('is-loading');
                btn.disabled = false;
                return;
            }

            const progress = document.getElementById('aiGenerationProgress');
            if (progress) progress.style.display = 'block';
            if (statusEl) statusEl.textContent = 'Initializing...';
            this.startAiPolling(requestedVideoId);
        } catch (e) {
            console.error('Failed to start AI generation:', e);
            if (window.showToast) window.showToast('Failed to start AI generation: ' + e.message, 'error');
            btn.classList.remove('is-loading');
            btn.disabled = false;
        } finally {
            btn.classList.remove('is-loading');
        }
    }

    async loadTranslateTracks() {
        const sel = document.getElementById('subtitleTranslateTrack');
        const btn = document.getElementById('startSubtitleTranslateBtn');
        if (!sel || !this.currentVideoId) return;

        sel.innerHTML = '<option value="">Loading existing subtitles...</option>';
        try {
            const res = await fetch(`/api/video/subtitles/${this.currentVideoId}`);
            if (!res.ok) {
                sel.innerHTML = '<option value="">No existing subtitles found</option>';
                if (btn) btn.disabled = true;
                return;
            }
            const data = await res.json();
            const tracks = data.tracks || data.data || [];
            if (tracks.length === 0) {
                sel.innerHTML = '<option value="">No existing subtitles found</option>';
                if (btn) btn.disabled = true;
                return;
            }
            sel.innerHTML = '';
            tracks.forEach(track => {
                const opt = document.createElement('option');
                opt.value = track.id;
                opt.textContent = (track.displayName || track.languageName || track.languageCode || track.filename || ('Track ' + track.id)) + (track.isEmbedded ? ' (embedded)' : '');
                sel.appendChild(opt);
            });
            if (btn) btn.disabled = false;
        } catch (e) {
            console.error('Failed to load subtitle tracks for translation:', e);
            sel.innerHTML = '<option value="">No existing subtitles found</option>';
            if (btn) btn.disabled = true;
        }
    }

    async translateExistingSubtitles() {
        const requestedVideoId = this.currentVideoId;
        if (!requestedVideoId) return;

        const btn = document.getElementById('startSubtitleTranslateBtn');
        if (!btn) return;

        const trackSel = document.getElementById('subtitleTranslateTrack');
        const langSel = document.getElementById('subtitleTranslateLanguage');
        const trackId = trackSel ? trackSel.value : '';
        const language = langSel ? langSel.value : '';

        if (!trackId) {
            if (window.showToast) window.showToast('Select a subtitle track to translate', 'error');
            console.error('Translate subtitles: no track selected');
            return;
        }
        if (!language) {
            if (window.showToast) window.showToast('Select a target language', 'error');
            console.error('Translate subtitles: no target language selected');
            return;
        }

        btn.classList.add('is-loading');

        const statusEl = document.getElementById('aiGenerationStatus');
        if (statusEl) statusEl.textContent = 'Initializing translation...';

        try {
            const url = `/api/video/subtitles/${requestedVideoId}/translate?trackId=${encodeURIComponent(trackId)}&language=${encodeURIComponent(language)}`;
            const res = await fetch(url, { method: 'POST' });

            const cpi = window.currentPlayerInstance;
            if (!cpi || cpi._destroyed || String(cpi.videoId) !== String(requestedVideoId)) {
                console.warn('[SubtitleManager] translateExistingSubtitles discarded: video changed');
                btn.classList.remove('is-loading');
                return;
            }

            if (res.status === 503) {
                const message = 'Parakeet AI is not available on this server';
                if (window.showToast) window.showToast(message, 'error');
                if (statusEl) statusEl.textContent = message;
                btn.classList.remove('is-loading');
                return;
            }

            if (!res.ok) {
                let message = 'Failed to start translation';
                try {
                    const errData = await res.json();
                    if (errData && errData.error) message = errData.error;
                } catch (e) {
                    console.error('Failed to parse translation error response:', e);
                }
                if (window.showToast) window.showToast(message, 'error');
                btn.classList.remove('is-loading');
                return;
            }

            const progress = document.getElementById('aiGenerationProgress');
            if (progress) progress.style.display = 'block';
            if (statusEl) statusEl.textContent = 'Initializing translation...';
            this.startAiPolling(requestedVideoId);
        } catch (e) {
            console.error('Failed to start subtitle translation:', e);
            if (window.showToast) window.showToast('Failed to start translation: ' + e.message, 'error');
            btn.classList.remove('is-loading');
        } finally {
            btn.classList.remove('is-loading');
        }
    }

    startAiPolling(videoId) {
        this.stopAiPolling();
        this.aiPollVideoId = videoId;
        this.aiPollAttempts = 0;
        this.aiPollInterval = setInterval(() => this.pollAiStatus(videoId), 2000);
    }

    stopAiPolling() {
        if (this.aiPollInterval) {
            clearInterval(this.aiPollInterval);
            this.aiPollInterval = null;
        }
    }

    destroy() {
        this.stopAiPolling();
        this.aiCancelRequested = false;
    }

    async pollAiStatus(videoId) {
        const cpi = window.currentPlayerInstance;
        if (cpi && String(cpi.videoId) !== String(videoId)) {
            console.warn('[SubtitleManager] AI poll discarded: video changed');
            this.stopAiPolling();
            return;
        }

        this.aiPollAttempts++;
        if (this.aiPollAttempts > 150) {
            console.warn('[SubtitleManager] AI polling capped after 150 attempts (5 min)');
            this.stopAiPolling();
            this.resetAiGenerationUI();
            return;
        }

        try {
            const res = await fetch(`/api/video/subtitles/${videoId}/generate/status`);
            if (!res.ok) {
                console.warn('[SubtitleManager] AI status poll returned HTTP ' + res.status);
                return;
            }
            const data = await res.json();

            const cpi2 = window.currentPlayerInstance;
            if (cpi2 && String(cpi2.videoId) !== String(videoId)) {
                console.warn('[SubtitleManager] AI poll response discarded: video changed');
                this.stopAiPolling();
                return;
            }

            const statusEl = document.getElementById('aiGenerationStatus');
            if (statusEl) {
                const stage = data.stage || 'generating';
                const label = stage.charAt(0).toUpperCase() + stage.slice(1);
                statusEl.textContent = data.progress != null
                    ? `${label}... ${Math.round(data.progress)}%`
                    : `${label}...`;
            }

            if (!data.running) {
                this.stopAiPolling();
                if (this.aiCancelRequested) {
                    this.aiCancelRequested = false;
                    return;
                }
                this.resetAiGenerationUI();
                if (data.error) {
                    if (window.showToast) window.showToast(data.error, 'error');
                    if (statusEl) statusEl.textContent = 'Failed: ' + data.error;
                } else {
                    if (window.showToast) window.showToast('AI subtitles generated!', 'success');
                    this.refreshSubtitleList();
                }
            }
        } catch (e) {
            console.error('Failed to poll AI generation status:', e);
        }
    }

    async cancelAiGeneration() {
        this.stopAiPolling();
        this.aiCancelRequested = true;

        const btn = document.getElementById('cancelAiGenerationBtn');
        if (btn) btn.classList.add('is-loading');

        try {
            if (this.currentVideoId) {
                await fetch(`/api/video/subtitles/${this.currentVideoId}/generate/cancel`, { method: 'POST' });
            }
            if (window.showToast) window.showToast('Generation cancelled', 'info');
        } catch (e) {
            console.error('Failed to cancel AI generation:', e);
        } finally {
            if (btn) btn.classList.remove('is-loading');
            this.resetAiGenerationUI();
        }
    }

    resetAiGenerationUI() {
        const progress = document.getElementById('aiGenerationProgress');
        if (progress) progress.style.display = 'none';
        const statusEl = document.getElementById('aiGenerationStatus');
        if (statusEl) statusEl.textContent = 'Generating subtitles...';
        const btn = document.getElementById('startAiSubtitleBtn');
        if (btn) {
            btn.classList.remove('is-loading');
            btn.disabled = false;
        }
    }
}

const subtitleManager = new SubtitleManager();
window.subtitleManager = subtitleManager;

// Delegated handler for local subtitle "Add" buttons (avoids inline onclick quote breakage).
document.addEventListener('click', (e) => {
    const btn = e.target && typeof e.target.closest === 'function' ? e.target.closest('[data-add-local-path]') : null;
    if (!btn) return;
    const path = btn.dataset.addLocalPath;
    if (path) subtitleManager.addLocal(path, btn);
});

window.addEventListener('pagehide', () => {
    try { subtitleManager.stopAiPolling(); } catch (e) { console.warn('[SubtitleManager] stopAiPolling on pagehide failed:', e); }
});
