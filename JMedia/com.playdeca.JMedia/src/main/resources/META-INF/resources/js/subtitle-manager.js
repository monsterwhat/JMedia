class SubtitleManager {
    constructor() {
        this.currentVideoId = null;
        this.currentVideoTitle = '';
        this.baseBottomOffset = 60; // Default subtitle bottom offset
        this.currentStyle = null; // Will store current subtitle style
        this.boundVideo = null;
        this.boundContainer = null;
        
        // Auto-apply saved styles on initialization
        this.applySavedStyle();
        
        // Initialize correction UI
        this.initCorrectionUI();
    }

    // Explicitly bind to a video and its container
    bindVideo(video, container) {
        console.log('[SubtitleManager] Binding to video element', video.id);
        this.boundVideo = video;
        this.boundContainer = container;
        
        this.setupFirefoxSubtitlePositioning();
    }

    applySavedStyle() {
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
        const style = { ...defaults, ...saved };
        this.applyGlobalStyle(style);
    }

    openModal(videoId, videoTitle, videoPath) {
        this.currentVideoId = videoId;
        this.currentVideoTitle = videoTitle;
        
        const input = document.getElementById('subtitleSearchInput');
        if (input) input.value = videoTitle;
        
        const pathEl = document.getElementById('subtitle-modal-path');
        if (pathEl) {
            pathEl.textContent = videoPath || '';
        }
        document.getElementById('subtitleManagementModal').classList.add('is-active');
        
        // Refresh subtitle list from server
        this.refreshSubtitleList();
        this.switchTab('search');
    }
    
    async refreshSubtitleList() {
        if (!this.currentVideoId) return;
        
        try {
            const response = await fetch(`/api/video/subtitles/${this.currentVideoId}`);
            if (!response.ok) return;
            
            const data = await response.json();
            const tracks = data.tracks || data.data || [];
            
            if (window.currentPlayerInstance && typeof window.currentPlayerInstance.loadSubtitles === 'function') {
                await window.currentPlayerInstance.loadSubtitles(tracks);
            }
        } catch (error) {
            console.error('Failed to refresh subtitle list:', error);
        }
    }

    closeModal(e) {
        if (e) e.stopPropagation();
        document.getElementById('subtitleManagementModal').classList.remove('is-active');
    }

    switchTab(tab) {
        const tabs = ['search', 'manual', 'ai', 'style', 'upload'];
        tabs.forEach(t => {
            const btn = document.getElementById(`${t}-tab-btn`);
            const content = document.getElementById(`${t}-tab-content`);
            if (btn) btn.classList.toggle('is-active', tab === t);
            if (content) content.style.display = tab === t ? 'block' : 'none';
        });

        if (tab === 'manual') this.scanLocal();
        else if (tab === 'style') this.loadStyle();
    }

    onFileSelected(event) {
        const file = event.target.files[0];
        const nameEl = document.getElementById('subtitleUploadFileName');
        const btn = document.getElementById('uploadSubtitleBtn');
        if (file) {
            nameEl.textContent = file.name;
            btn.disabled = false;
        } else {
            nameEl.textContent = 'No file selected';
            btn.disabled = true;
        }
    }

    async uploadSubtitle() {
        const fileInput = document.getElementById('subtitleFileInput');
        const file = fileInput.files[0];
        if (!file) return;

        const btn = document.getElementById('uploadSubtitleBtn');
        btn.classList.add('is-loading');
        btn.disabled = true;

        try {
            const content = await this.readFileAsBase64(file);
            const language = document.getElementById('subtitleUploadLanguage').value;
            const displayName = document.getElementById('subtitleUploadName').value.trim();

            const res = await fetch(`/api/video/subtitles/${this.currentVideoId}/upload`, {
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
            if (res.ok) {
                if (window.showToast) window.showToast('Subtitle uploaded!', 'success');
                this.refreshSubtitleList();
                fileInput.value = '';
                document.getElementById('subtitleUploadFileName').textContent = 'No file selected';
                document.getElementById('subtitleUploadName').value = '';
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

        if (document.getElementById('subStyleFont')) document.getElementById('subStyleFont').value = style.font;
        if (document.getElementById('subStyleSize')) document.getElementById('subStyleSize').value = style.size;
        if (document.getElementById('subStyleColor')) document.getElementById('subStyleColor').value = style.color;
        if (document.getElementById('subStyleBgOpacity')) document.getElementById('subStyleBgOpacity').value = style.bgOpacity;
        if (document.getElementById('subStyleLineHeight')) document.getElementById('subStyleLineHeight').value = style.lineHeight;
        if (document.getElementById('subStyleBottom')) document.getElementById('subStyleBottom').value = style.bottom;

        this.updateStyle(false);
    }

    updateStyle(shouldApply = true) {
        const getVal = (id, def) => {
            const el = document.getElementById(id);
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

        // Update UI labels in the modal
        const setLabel = (id, val) => {
            const el = document.getElementById(id);
            if (el) el.textContent = val;
        };

        setLabel('fontSizeVal', style.size);
        setLabel('bgOpacityVal', style.bgOpacity);
        setLabel('bottomDistVal', style.bottom);

        const hexInput = document.getElementById('subStyleColorHex');
        if (hexInput) hexInput.value = style.color;

        // Update live preview in the modal
        const preview = document.getElementById('subPreviewText');
        if (preview) {
            preview.style.fontFamily = style.font;
            preview.style.fontSize = style.size + 'px';
            preview.style.color = style.color;
            preview.style.backgroundColor = `rgba(0, 0, 0, ${style.bgOpacity})`;
            preview.style.lineHeight = style.lineHeight;
        }

        if (shouldApply) {
            this.applyGlobalStyle(style);
        }
    }

    saveStyle() {
        const getVal = (id, def) => {
            const el = document.getElementById(id);
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

        localStorage.setItem('jmedia_subtitle_style', JSON.stringify(style));
        this.applyGlobalStyle(style);
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
            
            video::cue {
                opacity: 0 !important;
                visibility: hidden !important;
            }
        `;

        styleEl.textContent = css;
        if (this.overlayElement) this.applyOverlayStyles();
    }

    setupFirefoxSubtitlePositioning() {
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

            let currentLang = 'spa';
            try {
                const tracks = window.availableAudioTracks || [];
                const currentTrack = tracks.find(t => t.isDefault) || tracks[0];
                if (currentTrack && currentTrack.languageCode) {
                    currentLang = currentTrack.languageCode.toLowerCase();
                }
            } catch (e) {}

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
            body.innerHTML = data.map(file => `
                <tr>
                    <td>${file.filename}</td>
                    <td>${file.languageName || 'Unknown'}</td>
                    <td style="font-size:0.7rem; color:#aaa;">${file.fullPath}</td>
                    <td><button class="button is-small is-info" onclick="subtitleManager.addLocal('${file.fullPath.replace(/\\/g, '\\\\')}', this)">Add</button></td>
                </tr>
            `).join('');
        } catch (e) { body.innerHTML = '<tr><td colspan="4">Error</td></tr>'; }
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
            }
        } catch (e) {} finally { btn.classList.remove('is-loading'); }
    }

    async search() {
        const query = document.getElementById('subtitleSearchInput').value;
        const lang = document.getElementById('subtitleLanguageSelect').value;
        const body = document.getElementById('subtitleSearchResultsBody');
        body.innerHTML = '<tr><td colspan="5" class="has-text-centered">Searching...</td></tr>';
        try {
            const res = await fetch(`/api/video/subtitles/${this.currentVideoId}/search?language=${lang}&query=${encodeURIComponent(query)}`);
            const data = await res.json();
            body.innerHTML = data.map(res => `
                <tr>
                    <td>${res.filename}</td>
                    <td>${res.language}</td>
                    <td>${res.rating}</td>
                    <td>${res.downloadCount}</td>
                    <td><button class="button is-small is-success" onclick="subtitleManager.download('${res.id}', this, '${lang}')">Download</button></td>
                </tr>
            `).join('') || '<tr><td colspan="5">No results</td></tr>';
        } catch (e) { body.innerHTML = '<tr><td colspan="5">Error</td></tr>'; }
    }

    async download(fileId, btn, lang) {
        btn.classList.add('is-loading');
        try {
            const res = await fetch(`/api/video/subtitles/${this.currentVideoId}/download?fileId=${fileId}&language=${lang}`, { method: 'POST' });
            if (res.ok) {
                if (window.showToast) window.showToast('Downloaded!', 'success');
                this.refreshSubtitleList();
            }
        } catch (e) {} finally { btn.classList.remove('is-loading'); }
    }
}

const subtitleManager = new SubtitleManager();
window.subtitleManager = subtitleManager;
