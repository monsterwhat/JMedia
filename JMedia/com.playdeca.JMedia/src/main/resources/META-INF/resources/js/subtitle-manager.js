class SubtitleManager {
    constructor() {
        this.currentVideoId = null;
        this.currentVideoTitle = '';
        this.baseBottomOffset = 60; // Default subtitle bottom offset
        this.currentStyle = null; // Will store current subtitle style
        
        // Auto-apply saved styles on initialization
        this.applySavedStyle();
    }

    applySavedStyle() {
        const saved = JSON.parse(localStorage.getItem('jmedia_subtitle_style') || '{}');
        const defaults = {
            font: "'Segoe UI', sans-serif",
            size: 20,
            color: '#ffffff',
            bgOpacity: 0.7,
            lineHeight: 1.4,
            bottom: 60
        };
        const style = { ...defaults, ...saved };
        this.applyGlobalStyle(style);
    }

    openModal(videoId, videoTitle, videoPath) {
        this.currentVideoId = videoId;
        this.currentVideoTitle = videoTitle;
        
        document.getElementById('subtitleSearchInput').value = videoTitle;
        const pathEl = document.getElementById('subtitle-modal-path');
        if (pathEl) {
            pathEl.textContent = videoPath || '';
        }
        document.getElementById('subtitleManagementModal').classList.add('is-active');
        
        // Clear previous results
        document.getElementById('subtitleSearchResultsBody').innerHTML = `
            <tr>
                <td colspan="5" class="has-text-centered has-text-grey">
                    Click search to find subtitles for "${videoTitle}"
                </td>
            </tr>
        `;
        
        this.switchTab('search');
    }

    closeModal(e) {
        if (e) e.stopPropagation();
        document.getElementById('subtitleManagementModal').classList.remove('is-active');
    }

    switchTab(tab) {
        const searchBtn = document.getElementById('search-tab-btn');
        const manualBtn = document.getElementById('manual-tab-btn');
        const aiBtn = document.getElementById('ai-tab-btn');
        const styleBtn = document.getElementById('style-tab-btn');
        
        const searchContent = document.getElementById('search-tab-content');
        const manualContent = document.getElementById('manual-tab-content');
        const aiContent = document.getElementById('ai-tab-content');
        const styleContent = document.getElementById('style-tab-content');

        searchBtn.classList.toggle('is-active', tab === 'search');
        manualBtn.classList.toggle('is-active', tab === 'manual');
        aiBtn.classList.toggle('is-active', tab === 'ai');
        if (styleBtn) styleBtn.classList.toggle('is-active', tab === 'style');

        searchContent.style.display = tab === 'search' ? 'block' : 'none';
        manualContent.style.display = tab === 'manual' ? 'block' : 'none';
        aiContent.style.display = tab === 'ai' ? 'block' : 'none';
        if (styleContent) styleContent.style.display = tab === 'style' ? 'block' : 'none';

        if (tab === 'manual') {
            this.scanLocal();
        } else if (tab === 'style') {
            this.loadStyle();
        }
    }

    loadStyle() {
        const saved = JSON.parse(localStorage.getItem('jmedia_subtitle_style') || '{}');
        const defaults = {
            font: "'Segoe UI', sans-serif",
            size: 20,
            color: '#ffffff',
            bgOpacity: 0.7,
            lineHeight: 1.4,
            bottom: 60
        };
        const style = { ...defaults, ...saved };

        document.getElementById('subStyleFont').value = style.font;
        document.getElementById('subStyleSize').value = style.size;
        document.getElementById('subStyleColor').value = style.color;
        document.getElementById('subStyleColorHex').value = style.color;
        document.getElementById('subStyleBgOpacity').value = style.bgOpacity;
        document.getElementById('subStyleLineHeight').value = style.lineHeight;
        document.getElementById('subStyleBottom').value = style.bottom;

        this.updateStyle(false);
    }

    updateStyle(shouldApply = true) {
        const font = document.getElementById('subStyleFont').value;
        const size = document.getElementById('subStyleSize').value;
        const color = document.getElementById('subStyleColor').value;
        const bgOpacity = document.getElementById('subStyleBgOpacity').value;
        const lineHeight = document.getElementById('subStyleLineHeight').value;
        const bottom = document.getElementById('subStyleBottom').value;

        // Update labels
        document.getElementById('fontSizeVal').innerText = size;
        document.getElementById('bgOpacityVal').innerText = bgOpacity;
        document.getElementById('bottomDistVal').innerText = bottom;
        document.getElementById('subStyleColorHex').value = color;

        // Update preview
        const preview = document.getElementById('subPreviewText');
        preview.style.fontFamily = font;
        preview.style.fontSize = (size * 0.8) + 'px'; // Scale down for modal
        preview.style.color = color;
        preview.style.backgroundColor = `rgba(0,0,0,${bgOpacity})`;
        preview.style.lineHeight = lineHeight;

        if (shouldApply) {
            this.applyGlobalStyle({ font, size, color, bgOpacity, lineHeight, bottom });
        }
    }

    applyGlobalStyle(style) {
        let styleEl = document.getElementById('jmedia-subtitle-runtime-style');
        if (!styleEl) {
            styleEl = document.createElement('style');
            styleEl.id = 'jmedia-subtitle-runtime-style';
            document.head.appendChild(styleEl);
        }

        // We use margin-bottom on the container to push subtitles up.
        // SimplePlayer sets --sub-lift to 80px when controls are visible.
        const baseBottom = parseInt(style.bottom);
        this.baseBottomOffset = baseBottom;
        this.currentStyle = style;

        // Check if we're in Firefox
        const isFirefox = /Firefox/i.test(navigator.userAgent);

        let css = `
            video::cue, ::cue {
                background-color: rgba(0, 0, 0, ${style.bgOpacity}) !important;
                color: ${style.color} !important;
                font-family: ${style.font} !important;
                font-size: ${style.size}px !important;
                line-height: ${style.lineHeight} !important;
                visibility: visible !important;
                display: block !important;
                white-space: pre-wrap !important;
            }
        `;

        if (!isFirefox) {
            // WebKit/Chrome/Safari/Edge
            css += `
                video::-webkit-media-text-track-container {
                    position: absolute !important;
                    bottom: 0 !important;
                    left: 0 !important;
                    width: 100% !important;
                    height: 100% !important;
                    display: flex !important;
                    flex-direction: column !important;
                    justify-content: flex-end !important;
                    margin-bottom: calc(${baseBottom}px + var(--sub-lift, 0px)) !important;
                    transition: margin-bottom 0.3s ease-in-out !important;
                    pointer-events: none !important;
                    overflow: visible !important;
                    z-index: 2147483647 !important;
                }
            `;
        } else {
            // Firefox - CSS variable based positioning
            console.log('[SubtitleManager] Firefox detected - using CSS variable positioning');
            
            // Add CSS for Firefox overlay (positioning done via JavaScript)
            css += `
                .firefox-subtitle-overlay {
                    position: absolute;
                    bottom: 0;
                    left: 50%;
                    transform: translateX(-50%);
                    width: auto;
                    max-width: 100%;
                    box-sizing: border-box;
                    text-align: center;
                    pointer-events: none;
                    z-index: 2147483647;
                    transition: bottom 0.3s ease-in-out;
                    color: ${style.color} !important;
                    font-family: ${style.font} !important;
                    font-size: ${style.size}px !important;
                    line-height: ${style.lineHeight} !important;
                    background-color: rgba(0, 0, 0, ${style.bgOpacity}) !important;
                    padding: 8px 16px;
                    border-radius: 8px;
                    display: none;
                }
                
                .firefox-subtitle-overlay.active {
                    display: block;
                }
                
                /* Hide native subtitles in Firefox using Firefox-specific CSS hack */
                @-moz-document url-prefix() {
                    video::cue {
                        opacity: 0 !important;
                        visibility: hidden !important;
                        color: transparent !important;
                        background-color: transparent !important;
                        font-size: 0 !important;
                        line-height: 0 !important;
                    }
                }
            `;
            

        }

        styleEl.textContent = css;
        console.log('[SubtitleManager] CSS injected, length:', css.length);
        if (isFirefox) {
            console.log('[SubtitleManager] Firefox CSS includes @-moz-document rule:', css.includes('@-moz-document'));
        }
        // For Firefox, overlay creation is delayed until player is ready
        if (isFirefox) {
            console.log('[SubtitleManager] Firefox overlay creation delayed until player initializes');
            // Apply styles to overlay if it already exists
            if (this.overlayElement) {
                this.applyOverlayStyles();
            }
        }
    }

    setupFirefoxSubtitlePositioning(baseBottom) {
        console.log('[SubtitleManager] Setting up Firefox subtitle positioning, baseBottom:', baseBottom);
        
        // Check if overlay already exists
        let overlay = document.getElementById('firefox-subtitle-overlay');
        console.log('[SubtitleManager] Existing overlay found:', !!overlay);
        
        if (!overlay) {
            overlay = document.createElement('div');
            overlay.id = 'firefox-subtitle-overlay';
            overlay.className = 'firefox-subtitle-overlay';
            console.log('[SubtitleManager] Created new overlay element');
            
            // Find the video container to append overlay to
            // Priority: #customPlayer > currentPlayerInstance.container > .player-container > video parent > body
            const selectors = [
                '#customPlayer',
                window.currentPlayerInstance?.container,
                '.player-container',
                'video'
            ];
            
            let videoContainer = null;
            let usedSelector = 'none';
            
            for (const selector of selectors) {
                if (!selector) continue;
                
                if (selector instanceof HTMLElement) {
                    videoContainer = selector;
                    usedSelector = 'currentPlayerInstance.container';
                    console.log('[SubtitleManager] Using container from currentPlayerInstance');
                    break;
                } else if (typeof selector === 'string') {
                    const element = document.querySelector(selector);
                    if (element) {
                        videoContainer = element;
                        usedSelector = selector;
                        console.log(`[SubtitleManager] Found container with selector: ${selector}`);
                        break;
                    }
                }
            }
            
            if (videoContainer) {
                videoContainer.style.position = 'relative';
                videoContainer.appendChild(overlay);
                console.log(`[SubtitleManager] Appended overlay to container using ${usedSelector}`);
            } else {
                // Fallback: append to body and position relative to viewport
                document.body.appendChild(overlay);
                overlay.style.position = 'fixed';
                overlay.style.bottom = `${baseBottom}px`;
                console.log('[SubtitleManager] No container found, appended to body');
            }
        }
        
        // Store reference for later
        this.overlayElement = overlay;
        
        // Set up subtitle text extraction from native cues
        this.setupFirefoxSubtitleTextExtraction(overlay);
        // Apply current subtitle styles to overlay
        this.applyOverlayStyles();
        // Initial update
        this.updateFirefoxOverlayWithActiveCue();
    }

    ensureFirefoxOverlay() {
        // Ensure Firefox overlay exists, create if needed
        const isFirefox = /Firefox/i.test(navigator.userAgent);
        if (!isFirefox) return;
        
        // Try to create overlay now
        this.createFirefoxOverlayIfNeeded();
        
        // If no container found, schedule retries
        if (!this.overlayElement || !document.body.contains(this.overlayElement)) {
            console.log('[SubtitleManager] Overlay not created yet, scheduling retries...');
            this.retryOverlayCreation(5, 500); // 5 retries, 500ms apart
        }
    }

    createFirefoxOverlayIfNeeded() {
        if (!this.overlayElement || !document.body.contains(this.overlayElement)) {
            const baseBottom = this.baseBottomOffset || 60;
            this.setupFirefoxSubtitlePositioning(baseBottom);
        }
    }

    retryOverlayCreation(maxRetries, delay) {
        let attempts = 0;
        const tryCreate = () => {
            attempts++;
            console.log(`[SubtitleManager] Retry attempt ${attempts}/${maxRetries}`);
            this.createFirefoxOverlayIfNeeded();
            
            if ((!this.overlayElement || !document.body.contains(this.overlayElement)) && attempts < maxRetries) {
                setTimeout(tryCreate, delay);
            } else if (attempts >= maxRetries) {
                console.log('[SubtitleManager] Max retries reached, overlay creation failed');
            }
        };
        tryCreate();
    }

    setupFirefoxSubtitleTextExtraction(overlay) {
        // This method intercepts native subtitle cues
        // and displays them in our custom overlay
        console.log('[SubtitleManager] Firefox subtitle text extraction set up');
        
        const video = document.querySelector('video');
        console.log('[SubtitleManager] Video element found:', video ? 'yes' : 'no', 'src:', video?.src || 'none');
        if (!video || !video.textTracks) {
            console.log('[SubtitleManager] No video or textTracks found');
            return;
        }
        // Add Firefox-specific class to video element for CSS targeting
        video.classList.add('firefox-subtitle-fallback');

        // Hide native subtitles in Firefox - we'll use our custom overlay instead
        console.log('[SubtitleManager] Total tracks found:', video.textTracks.length);
        const trackKinds = [];
        for (let i = 0; i < video.textTracks.length; i++) {
            const track = video.textTracks[i];
            trackKinds.push(track.kind);
            console.log(`[SubtitleManager] Track ${i}: kind=${track.kind}, mode=${track.mode}, label=${track.label || 'none'}`);
            if (track.kind === 'subtitles' || track.kind === 'captions') {
                console.log(`[SubtitleManager] Found subtitle/caption track ${i}, will rely on CSS hiding`);
            }
        }
        console.log('[SubtitleManager] All track kinds:', trackKinds.join(', '));
        
        // Verify no tracks are still showing
        let stillShowing = false;
        for (let i = 0; i < video.textTracks.length; i++) {
            if (video.textTracks[i].mode === 'showing') {
                stillShowing = true;
                console.log(`[SubtitleManager] WARNING: Track ${i} is still showing!`);
            }
        }
        if (!stillShowing) {
            console.log('[SubtitleManager] All tracks are hidden or disabled.');
        }

        // Helper function to update overlay with active cue
        const updateOverlayWithActiveCue = () => {
            let activeCue = null;
            for (let i = 0; i < video.textTracks.length; i++) {
                const track = video.textTracks[i];
                if (track.mode === 'showing' && track.activeCues && track.activeCues.length > 0) {
                    activeCue = track.activeCues[0];
                    break;
                }
            }
            
            if (activeCue) {
                overlay.textContent = activeCue.text;
                overlay.classList.add('active');
                console.log('[SubtitleManager] Active cue found:', activeCue.text);
            } else {
                overlay.classList.remove('active');
                console.log('[SubtitleManager] No active cue');
            }
        };

        // Initial check
        updateOverlayWithActiveCue();

        // Listen for changes to textTracks collection (e.g., tracks added/removed/mode changed)
        video.textTracks.addEventListener('change', () => {
            console.log('[SubtitleManager] textTracks change event');
            // Re-attach cuechange listeners for showing tracks
            for (let i = 0; i < video.textTracks.length; i++) {
                const track = video.textTracks[i];
                if (track.mode === 'showing') {
                    // Remove existing listener to avoid duplicates
                    track.removeEventListener('cuechange', updateOverlayWithActiveCue);
                    track.addEventListener('cuechange', updateOverlayWithActiveCue);
                }
            }
            updateOverlayWithActiveCue();
        });

        // Also listen for new tracks being added
        video.textTracks.addEventListener('addtrack', (evt) => {
            const track = evt.track;
            if (track && (track.kind === 'subtitles' || track.kind === 'captions')) {
                console.log(`[SubtitleManager] New track added: kind=${track.kind}, mode=${track.mode}`);
                console.log(`[SubtitleManager] Relying on CSS to hide native subtitles`);
            }
        });

        // Attach cuechange listeners to initially showing tracks
        for (let i = 0; i < video.textTracks.length; i++) {
            const track = video.textTracks[i];
            if (track.mode === 'showing') {
                track.addEventListener('cuechange', updateOverlayWithActiveCue);
            }
        }
    }

    updateFirefoxOverlayWithActiveCue() {
        // Manually update overlay with active cue (call when subtitles are first loaded)
        const video = document.querySelector('video');
        if (!video || !video.textTracks) return;
        
        let activeCue = null;
        for (let i = 0; i < video.textTracks.length; i++) {
            const track = video.textTracks[i];
            if (track.mode === 'showing' && track.activeCues && track.activeCues.length > 0) {
                activeCue = track.activeCues[0];
                break;
            }
        }
        
        const overlay = document.getElementById('firefox-subtitle-overlay');
        if (overlay) {
            if (activeCue) {
                overlay.textContent = activeCue.text;
                overlay.classList.add('active');
                console.log('[SubtitleManager] Manual update - active cue found:', activeCue.text);
            } else {
                overlay.classList.remove('active');
                console.log('[SubtitleManager] Manual update - no active cue');
            }
        }
    }

    applyOverlayStyles() {
        // Apply current subtitle styles to the overlay
        if (!this.currentStyle) return;
        const overlay = document.getElementById('firefox-subtitle-overlay');
        if (!overlay) return;
        
        overlay.style.color = this.currentStyle.color;
        overlay.style.fontFamily = this.currentStyle.font;
        overlay.style.fontSize = `${this.currentStyle.size}px`;
        overlay.style.lineHeight = this.currentStyle.lineHeight;
        overlay.style.backgroundColor = `rgba(0, 0, 0, ${this.currentStyle.bgOpacity})`;
    }

    setSubtitleLift(liftHeight) {
        console.log('[SubtitleManager] setSubtitleLift called with liftHeight:', liftHeight, 'baseBottomOffset:', this.baseBottomOffset);
        const overlay = document.getElementById('firefox-subtitle-overlay');
        if (overlay) {
            overlay.style.bottom = `${this.baseBottomOffset + liftHeight}px`;
            console.log('[SubtitleManager] Updated overlay bottom to:', overlay.style.bottom);
        } else {
            console.log('[SubtitleManager] No overlay found to update');
        }
    }

    saveStyle() {
        const style = {
            font: document.getElementById('subStyleFont').value,
            size: document.getElementById('subStyleSize').value,
            color: document.getElementById('subStyleColor').value,
            bgOpacity: document.getElementById('subStyleBgOpacity').value,
            lineHeight: document.getElementById('subStyleLineHeight').value,
            bottom: document.getElementById('subStyleBottom').value
        };

        localStorage.setItem('jmedia_subtitle_style', JSON.stringify(style));
        this.applyGlobalStyle(style);
        
        // Also apply to active player cues
        if (window.currentPlayerInstance && typeof window.currentPlayerInstance.applySubtitleStyle === 'function') {
            window.currentPlayerInstance.applySubtitleStyle();
        }
        
        if (window.showToast) {
            window.showToast('Subtitle style saved!', 'success');
        }
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
        
        document.getElementById('subStyleFont').value = defaults.font;
        document.getElementById('subStyleSize').value = defaults.size;
        document.getElementById('subStyleColor').value = defaults.color;
        document.getElementById('subStyleBgOpacity').value = defaults.bgOpacity;
        document.getElementById('subStyleLineHeight').value = defaults.lineHeight;
        document.getElementById('subStyleBottom').value = defaults.bottom;

        this.updateStyle();
        this.saveStyle();
    }

    async scanLocal() {
        const resultsBody = document.getElementById('subtitleManualResultsBody');
        resultsBody.innerHTML = `
            <tr>
                <td colspan="4" class="has-text-centered py-6">
                    <i class="pi pi-spin pi-spinner" style="font-size: 2rem;"></i>
                    <p class="mt-2">Scanning local directory...</p>
                </td>
            </tr>
        `;

        try {
            const response = await fetch(`/api/video/subtitles/${this.currentVideoId}/local-files`);
            if (!response.ok) throw new Error('Scan failed');
            
            const results = await response.json();
            
            if (results.length === 0) {
                resultsBody.innerHTML = `
                    <tr>
                        <td colspan="4" class="has-text-centered has-text-warning py-6">
                            <i class="pi pi-exclamation-triangle" style="font-size: 2rem;"></i>
                            <p class="mt-2">No subtitle files found in the directory or its sub-folders.</p>
                        </td>
                    </tr>
                `;
                return;
            }

            resultsBody.innerHTML = '';
            results.forEach(res => {
                const tr = document.createElement('tr');
                tr.innerHTML = `
                    <td class="is-vcentered" style="max-width: 250px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" title="${res.filename}">
                        ${res.filename}
                    </td>
                    <td class="is-vcentered">${res.languageName || 'Unknown'}</td>
                    <td class="is-vcentered" style="max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 0.75rem; color: #aaa;" title="${res.fullPath}">
                        ${res.fullPath}
                    </td>
                    <td class="is-vcentered">
                        <button class="button is-small is-info is-rounded add-local-btn" data-path="${res.fullPath.replace(/"/g, '&quot;')}">
                            <i class="pi pi-plus"></i>&nbsp;Add
                        </button>
                    </td>
                `;
                resultsBody.appendChild(tr);
            });

            // Add event listeners to the buttons instead of using inline onclick
            resultsBody.querySelectorAll('.add-local-btn').forEach(btn => {
                btn.onclick = (e) => {
                    const filePath = e.currentTarget.getAttribute('data-path');
                    this.addLocal(filePath, e.currentTarget);
                };
            });

        } catch (error) {
            console.error('Local scan error:', error);
            resultsBody.innerHTML = `
                <tr>
                    <td colspan="4" class="has-text-centered has-text-danger py-6">
                        <i class="pi pi-times-circle" style="font-size: 2rem;"></i>
                        <p class="mt-2">Error scanning local files: ${error.message}</p>
                    </td>
                </tr>
            `;
        }
    }

    async addLocal(filePath, button) {
        const originalContent = button.innerHTML;
        button.classList.add('is-loading');
        button.disabled = true;

        try {
            const userId = localStorage.getItem('activeProfileId') || '1';
            const response = await fetch(`/api/video/subtitles/${this.currentVideoId}/add-local`, {
                method: 'POST',
                headers: { 
                    'Content-Type': 'application/json',
                    'X-User-ID': userId
                },
                body: JSON.stringify({ filePath: filePath })
            });
            
            const result = await response.json();
            if (!response.ok) {
                const msg = result.error || 'Add failed (Server Error)';
                throw new Error(msg);
            }
            
            if (window.showToast) {
                window.showToast('Subtitle added successfully!', 'success');
            }
            
            // Mark as added in UI
            button.classList.remove('is-info');
            button.classList.add('is-success');
            button.innerHTML = '<i class="pi pi-check"></i>&nbsp;Added';
            button.disabled = true;

            // Refresh tracks in the active player
            if (window.currentPlayerInstance && typeof window.currentPlayerInstance.loadSubtitles === 'function') {
                await window.currentPlayerInstance.loadSubtitles(result.tracks);
            }
            
        } catch (error) {
            console.error('Add local error:', error);
            const displayMsg = error.message.includes('Failed to fetch') ? 'Network error or server down' : error.message;
            if (window.showToast) {
                window.showToast('Error: ' + displayMsg, 'danger');
            } else {
                alert('Error adding local subtitle: ' + displayMsg);
            }
            button.classList.remove('is-loading');
            button.disabled = false;
            button.innerHTML = originalContent;
        } finally {
            button.classList.remove('is-loading');
        }
    }

    async search() {
        const query = document.getElementById('subtitleSearchInput').value;
        const lang = document.getElementById('subtitleLanguageSelect').value;
        const resultsBody = document.getElementById('subtitleSearchResultsBody');

        resultsBody.innerHTML = `
            <tr>
                <td colspan="5" class="has-text-centered py-6">
                    <i class="pi pi-spin pi-spinner" style="font-size: 2rem;"></i>
                    <p class="mt-2">Searching OpenSubtitles...</p>
                </td>
            </tr>
        `;

        try {
            const response = await fetch(`/api/video/subtitles/${this.currentVideoId}/search?language=${lang}`);
            if (!response.ok) throw new Error('Search failed');
            
            const results = await response.json();
            
            if (results.length === 0) {
                resultsBody.innerHTML = `
                    <tr>
                        <td colspan="5" class="has-text-centered has-text-warning py-6">
                            <i class="pi pi-exclamation-triangle" style="font-size: 2rem;"></i>
                            <p class="mt-2">No subtitles found. Try a different search term or language.</p>
                        </td>
                    </tr>
                `;
                return;
            }

            resultsBody.innerHTML = '';
            results.forEach(res => {
                const tr = document.createElement('tr');
                tr.innerHTML = `
                    <td class="is-vcentered" style="max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" title="${res.filename}">
                        ${res.filename}
                    </td>
                    <td class="is-vcentered">${res.language}</td>
                    <td class="is-vcentered">⭐ ${res.rating}</td>
                    <td class="is-vcentered">${res.downloadCount}</td>
                    <td class="is-vcentered">
                        <button class="button is-small is-success is-rounded" onclick="subtitleManager.download('${res.id}', this, '${lang}')">
                            <i class="pi pi-download"></i>&nbsp;Download
                        </button>
                    </td>
                `;
                resultsBody.appendChild(tr);
            });
        } catch (error) {
            console.error('Subtitle search error:', error);
            resultsBody.innerHTML = `
                <tr>
                    <td colspan="5" class="has-text-centered has-text-danger py-6">
                        <i class="pi pi-times-circle" style="font-size: 2rem;"></i>
                        <p class="mt-2">Error searching subtitles: ${error.message}</p>
                    </td>
                </tr>
            `;
        }
    }

    async download(fileId, button, language) {
        const originalContent = button.innerHTML;
        button.classList.add('is-loading');
        button.disabled = true;

        try {
            const response = await fetch(`/api/video/subtitles/${this.currentVideoId}/download?fileId=${fileId}&language=${language}`, {
                method: 'POST'
            });
            
            const result = await response.json();
            if (!response.ok) throw new Error(result.error || 'Download failed');
            
            if (window.showToast) {
                window.showToast('Subtitle downloaded: ' + result.message, 'success');
            }
            
            // Mark as downloaded in UI
            button.classList.remove('is-success');
            button.classList.add('is-info');
            button.innerHTML = '<i class="pi pi-check"></i>&nbsp;Downloaded';
            button.disabled = true;

            // Refresh tracks in the active player
            if (window.currentPlayerInstance && typeof window.currentPlayerInstance.loadSubtitles === 'function') {
                console.log('[SubtitleManager] Refreshing player subtitles with new tracks:', result.tracks);
                await window.currentPlayerInstance.loadSubtitles(result.tracks);
            }
            
        } catch (error) {
            console.error('Download error:', error);
            if (window.showToast) {
                window.showToast('Error: ' + error.message, 'danger');
            }
            // Only reset if it failed
            button.classList.remove('is-loading');
            button.disabled = false;
            button.innerHTML = originalContent;
        } finally {
            // Remove loading even if disabled (success case)
            button.classList.remove('is-loading');
        }
    }

    async generateAiSubtitles() {
        const btn = document.getElementById('startAiSubtitleBtn');
        const originalContent = btn.innerHTML;
        btn.classList.add('is-loading');
        btn.disabled = true;

        try {
            const response = await fetch(`/api/video/subtitles/${this.currentVideoId}/generate`, {
                method: 'POST'
            });
            
            if (!response.ok) {
                const text = await response.text();
                throw new Error(text || 'Generation failed');
            }
            
            if (window.showToast) {
                window.showToast('Whisper generation started in background!', 'info');
            } else {
                alert('Whisper generation started in background!');
            }
            
            this.closeModal();
            
        } catch (error) {
            console.error('AI Generation error:', error);
            if (window.showToast) {
                window.showToast('Error: ' + error.message, 'danger');
            } else {
                alert('Error starting AI generation: ' + error.message);
            }
        } finally {
            btn.classList.remove('is-loading');
            btn.disabled = false;
            btn.innerHTML = originalContent;
        }
    }
}

const subtitleManager = new SubtitleManager();
window.subtitleManager = subtitleManager;
