class VideoSPA {
    constructor() {
        this.currentSection = 'home';
        this.currentParams = {};
        this.backDestination = null;
        this.transitionType = 'slide-forward';
        this.sections = {
            home: 'home',
            movies: '/api/video/ui/movies-fragment',
            shows: '/api/video/ui/shows-fragment',
            history: '/api/video/ui/history-fragment',
            adminHistory: '/api/video/ui/admin-history-fragment',
            watchlist: '/api/video/ui/watchlist-fragment',
            suggestion: '/api/video/ui/suggestion-fragment',
            adminSuggestions: '/api/video/ui/admin-suggestions-fragment',
            manage: '/api/video/manage',
            manageSeries: '/api/video/manage/series/{seriesTitle}',
            needsAttention: '/api/video/manage/needs-attention',
            verification: '/api/video/manage/verification',
            seasons: '/api/video/ui/shows/{encodedTitle}/seasons-fragment',
            episodes: '/api/video/ui/shows/{seriesTitle}/seasons/{seasonNumber}/episodes-fragment',
            'folder-episodes': '/api/video/ui/shows/{seriesTitle}/seasons/{seasonNumber}/folders/{folderName}/episodes-fragment',
            details: '/api/video/ui/details-fragment/{videoId}',
            playback: '/api/video/ui/playback-fragment?videoId={videoId}',
            external: '/api/video/external/fragment',
            collections: '/api/video/ui/collections-fragment',
            collectionEntries: '/api/video/ui/collections/{collectionId}/entries-fragment'
            };
    }
    
    buildSpaUrl(section, params = {}) {
        const queryParams = new URLSearchParams();
        if (section !== 'home') {
            queryParams.set('section', section);
        }
        for (const [key, value] of Object.entries(params)) {
            queryParams.set(key, value);
        }
        const queryString = queryParams.toString();
        return queryString ? `/video?${queryString}` : '/video';
    }

    async switchSection(section, params = {}, bypassHistory = false) {
        console.log(`[VideoSPA] Switching to section: ${section}`, params);
        // Destroy current player to cleanup FFmpeg processes
        await this.destroyCurrentPlayer();
        this.showLoading();
        
        if (!bypassHistory) {
            if (section === 'playback') {
                this.transitionType = 'crossfade';
            } else if (section !== 'home') {
                this.transitionType = 'slide-forward';
            }
        }
        
        if (!bypassHistory) {
            if (section === 'playback' || section === 'details' || section === 'episodes' || section === 'seasons' || section === 'folder-episodes' || section === 'collectionEntries') {
                 if (this.currentSection !== section) {
                     this.backDestination = { section: this.currentSection, params: { ...this.currentParams } };
                     console.log('[VideoSPA] Saved back destination:', this.backDestination);
                 }
            } else {
                this.backDestination = null;
            }
        }
        
        if (section === 'home') {
            this.goHome(bypassHistory);
            return;
        }

        // Handle external video playback
        if (section === 'playback' && params.externalVideoId) {
            await this.playbackExternal(params.externalVideoId, bypassHistory);
            return;
        }

        this.updateNavState(section);
        const apiUrl = this.buildApiUrl(section, params);
        try {
            const html = await this.fetchContent(apiUrl);
            this.updateContent(html);
            this.hideLoading();
            
            if (!bypassHistory) {
                const spaUrl = this.buildSpaUrl(section, params);
                history.pushState({ section, params, view: 'video' }, '', spaUrl);
            }

            this.currentSection = section;
            this.currentParams = params;
        } catch (error) {
            this.handleError(error);
        }
    }
    
    goBack() {
        console.log('[VideoSPA] goBack called. Saved Destination:', this.backDestination);
        
        if (this.currentSection === 'playback') {
            this.transitionType = 'crossfade';
        } else {
            this.transitionType = 'slide-backward';
        }
        
        if (this.backDestination) {
            const dest = this.backDestination;
            this.backDestination = null;
            this.switchSection(dest.section, dest.params || {}, true);
            const spaUrl = this.buildSpaUrl(dest.section, dest.params || {});
            history.replaceState({ section: dest.section, params: dest.params, view: 'video' }, '', spaUrl);
            return;
        }
        
        // Fallback: Check if we are in the player and can infer destination from metadata
        const player = document.getElementById('customPlayer');
        if (player) {
            const type = (player.getAttribute('data-type') || '').toLowerCase();
            const seriesTitle = player.getAttribute('data-series-title');
            const seasonNumber = player.getAttribute('data-season-number');
            const videoId = player.getAttribute('data-video-id');

            if (type === 'episode' && seriesTitle) {
                console.log('[VideoSPA] Inferring back to episodes list');
                const params = { seriesTitle: seriesTitle, seasonNumber: seasonNumber || 1 };
                this.switchSection('episodes', params, true);
                history.replaceState({ section: 'episodes', params, view: 'video' }, '', this.buildSpaUrl('episodes', params));
                return;
            } else if (videoId) {
                console.log('[VideoSPA] Inferring back to details page');
                const params = { videoId: videoId };
                this.switchSection('details', params, true);
                history.replaceState({ section: 'details', params, view: 'video' }, '', this.buildSpaUrl('details', params));
                return;
            }
        }
        
        this.goHome(true);
    }
    
    goHome(bypassHistory = false) {
        this.backDestination = null;
        this.updateNavState('home');
        this.transitionType = 'slide-backward';
        this.updateContent(`
            <div id="carousels-section" 
                 hx-get="/api/video/ui/optimized-carousels"
                 hx-trigger="load"
                 hx-target="#carousels-section"
                 hx-swap="innerHTML">
            </div>
        `);
        if (window.htmx) {
            htmx.process(document.getElementById('spa-content'));
        }

        if (!bypassHistory) {
            history.pushState({ section: 'home', params: {}, view: 'video' }, '', '/video');
        }

        this.currentSection = 'home';
        this.currentParams = {};
        this.hideLoading();
    }

    async selectItem(item, action, extraParams = {}) {
        const videoId = (typeof item === 'object') ? item.id : item;
        if (!videoId) return;

        switch(action) {
            case 'play':
                await this.playVideo(videoId, extraParams);
                break;
            case 'details':
                await this.switchSection('details', {videoId: videoId});
                break;
        }
    }
    
    async playVideo(videoId, extraParams = {}) {
        this.showLoading();
        try {
            // Fetch video details first to get the resume time
            const res = await fetch(`/api/video/${videoId}`);
            const json = await res.json();
            let startTime = 0;
            if (json.success && json.data && json.data.resumeTime) {
                startTime = json.data.resumeTime;
            }

            await fetch(`/api/video/playback/play/${videoId}?startTime=${startTime}`, { method: 'POST' });
            await this.switchSection('playback', {videoId: videoId, ...extraParams});
        } catch (error) {
            this.handleError(error);
        }
    }

    async playbackExternal(externalId, bypassHistory = false) {
        this.showLoading();
        try {
            const res = await fetch(`/api/video/external/${externalId}`);
            const json = await res.json();
            if (!json.success || !json.data) throw new Error('External video not found');

            const v = json.data;
            const html = this.buildExternalPlayerHtml(v);
            await this.destroyCurrentPlayer();
            this.updateContent(html);
            this.hideLoading();

            if (!bypassHistory) {
                history.pushState({ section: 'playback', params: { externalVideoId: externalId }, view: 'video' }, '', `/video?section=playback&externalVideoId=${externalId}`);
            }
            this.currentSection = 'playback';
            this.currentParams = { externalVideoId: externalId };
        } catch (error) {
            this.handleError(error);
        }
    }

    buildExternalPlayerHtml(v) {
        /* Torrent/magnet sources: use OPlayer + @oplayer/torrent plugin */
        if (v.sourceType === 'torrent') {
            /* HTML attributes use escapeAttr; inline JS strings use escapeJs (no entity decoding in <script>) */
            const attrUrl = this.escapeAttr(v.url);
            const attrTitle = this.escapeAttr(v.title || 'Torrent Video');
            const jsUrl = this.escapeJs(v.url);
            const jsTitle = this.escapeJs(v.title || 'Torrent Video');
            return `
                <link rel="stylesheet" href="/css/player.css"/>
                <div class="player-container" id="customPlayer"
                     data-external-id="${v.id}"
                     data-title="${attrTitle}"
                     data-duration="0"
                     data-start-time="${v.currentTime || 0}"
                     data-type="external"
                     data-source-type="torrent"
                     data-external-original-url="${attrUrl}">
                    <div class="video-wrapper">
                        <div id="oplayerContainer" class="oplayer-wrapper"></div>
                    </div>
                </div>

                <script src="https://cdn.jsdelivr.net/npm/webtorrent@0.98.18/webtorrent.min.js"><\/script>
                <script src="https://cdn.jsdelivr.net/npm/@oplayer/core@latest/dist/index.min.js"><\/script>
                <script src="https://cdn.jsdelivr.net/npm/@oplayer/ui@latest/dist/index.min.js"><\/script>
                <script src="https://cdn.jsdelivr.net/npm/@oplayer/torrent@latest/dist/index.min.js"><\/script>
                <script src="/js/player/Utils.js?v=3"><\/script>
                <script src="/js/player/oplayer-adapter.js?v=5"><\/script>
                <script>
                    (function() {
                        var cId = 'customPlayer';
                        var src = '${jsUrl}';
                        var title = '${jsTitle}';
                        var extId = '${v.id}';
                        var retry = function() {
                            if (typeof window.initExternalOPlayerTorrent === 'function') {
                                window.initExternalOPlayerTorrent(cId, src, title, extId);
                            } else {
                                setTimeout(retry, 200);
                            }
                        };
                        retry();
                    })();
                <\/script>
            `;
        }

        /* Default: proxy-stream via SimplePlayer */
        const proxyUrl = '/api/video/external/proxy/stream?url=' + encodeURIComponent(v.url);
        const alts = v.alternativeUrls && Array.isArray(v.alternativeUrls) && v.alternativeUrls.length > 0
            ? JSON.stringify(v.alternativeUrls).replace(/"/g, '&quot;') : '';
        return `
            <link rel="stylesheet" href="/css/player.css"/>
            <div class="player-container paused" id="customPlayer"
                 data-external-url="${this.escapeAttr(proxyUrl)}"
                 data-external-original-url="${this.escapeAttr(v.url)}"
                 data-external-id="${v.id}"
                 data-title="${this.escapeAttr(v.title)}"
                 data-duration="0"
                 data-start-time="${v.currentTime || 0}"
                 data-type="external">
                <div class="video-wrapper">
                    <video id="videoElement" crossorigin="anonymous" playsinline autoplay></video>
                </div>
            </div>

            <script src="/js/player/Utils.js?v=3"><\/script>
            <script src="/js/player/StateManager.js"><\/script>
            <script src="/js/player/StreamManager.js"><\/script>
            <script src="/js/player/UIBuilder.js?v=4"><\/script>
            <script src="/js/player/ControlsManager.js"><\/script>
            <script src="/js/player/FullscreenManager.js"><\/script>
            <script src="/js/player/SubtitleController.js"><\/script>
            <script src="/js/player/AudioTrackSelector.js"><\/script>
            <script src="/js/player/SubtitleSettingsUI.js"><\/script>
            <script src="/js/player/StoryboardManager.js"><\/script>
            <script src="/js/player/EventBinder.js?v=4"><\/script>
            <script src="/js/player/KeyboardShortcuts.js"><\/script>
            <script src="/js/player/SkipController.js"><\/script>
            <script src="/js/player/ProgressReporter.js"><\/script>
            <script src="/js/player/NavigationManager.js"><\/script>
            <script src="/js/simple-player.js"><\/script>
            <script>
                (function() {
                    var requiredModules = [
                        'SimplePlayer',
                        'PlayerStateManager',
                        'PlayerStreamManager',
                        'PlayerUIBuilder',
                        'PlayerControlsManager',
                        'PlayerFullscreenManager',
                        'PlayerSubtitleController',
                        'PlayerAudioTrackSelector',
                        'PlayerSubtitleSettingsUI',
                        'PlayerStoryboardManager',
                        'PlayerEventBinder',
                        'PlayerKeyboardShortcuts',
                        'PlayerSkipController',
                        'PlayerProgressReporter',
                        'PlayerNavigationManager'
                    ];
                    var tryInit = function() {
                        var allReady = requiredModules.every(function(m) {
                            return typeof window[m] !== 'undefined';
                        });
                        if (allReady) {
                            new window.SimplePlayer({
                                containerId: 'customPlayer',
                                videoId: 'videoElement',
                                currentVideoId: 'ext-${v.id}'
                            });
                        } else {
                            setTimeout(tryInit, 50);
                        }
                    };
                    tryInit();
                })();
            <\/script>
        `;
    }

    escapeAttr(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    escapeJs(str) {
        if (!str) return '';
        return str.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/\n/g, '\\n').replace(/\r/g, '\\r');
    }

    async destroyCurrentPlayer() {
        if (window.currentPlayerInstance && typeof window.currentPlayerInstance.destroy === 'function') {
            await window.currentPlayerInstance.destroy();
        }
        if (window.player && typeof window.player.destroy === 'function') {
            await window.player.destroy();
        }
        window.currentPlayerInstance = null;
        window.player = null;
    }
    
    updateNavState(section) {
        document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));
        
        // Handle specific video IDs
        let navId = 'nav-' + section;
        if (section === 'history') navId = 'nav-video-history';
        if (section === 'watchlist') navId = 'nav-video-watchlist';
        if (section === 'collections' || section === 'collectionEntries') navId = 'nav-collections';
        
        const activeNav = document.getElementById(navId) || document.getElementById('nav-' + section);
        if (activeNav) activeNav.classList.add('active');
        
        document.querySelectorAll('.mobile-nav-item').forEach(el => el.classList.remove('active'));
        const activeMobileNav = document.getElementById('mobile-nav-' + section);
        if (activeMobileNav) activeMobileNav.classList.add('active');
    }

    buildApiUrl(section, params) {
        let url = this.sections[section] || section;
        const usedParams = new Set();
        
        for (const [key, value] of Object.entries(params)) {
            const placeholder = '{' + key + '}';
            if (url.includes(placeholder)) {
                url = url.replace(placeholder, encodeURIComponent(value));
                usedParams.add(key);
            }
        }
        
        const queryParams = new URLSearchParams();
        for (const [key, value] of Object.entries(params)) {
            if (!usedParams.has(key)) {
                queryParams.append(key, value);
            }
        }
        
        const queryString = queryParams.toString();
        if (queryString) {
            url += (url.includes('?') ? '&' : '?') + queryString;
        }
        
        return url;
    }
    
    async fetchContent(url) {
        const response = await fetch(url);
        if (!response.ok) throw new Error('HTTP ' + response.status);
        return await response.text();
    }
    
    updateContent(html) {
        const contentDiv = document.getElementById('spa-content');
        if (contentDiv) {
            // Destroy existing player instance if it exists to clean up event listeners and intervals
            // Fire-and-forget since this may be called from non-async contexts (goHome)
            this.destroyCurrentPlayer();

            // Preservation of global modals that might have been moved into the content (e.g. by SimplePlayer for fullscreen)
            ['subtitleManagementModal', 'editVideoModal'].forEach(id => {
                const modal = document.getElementById(id);
                if (modal) {
                    // Close modal when switching sections
                    modal.classList.remove('is-active');
                    
                    // Move it to body to ensure it's outside of any content being replaced
                    // This is a safety measure in case it was appended to the player or another temporary container
                    if (document.body !== modal.parentElement) {
                        console.log(`[VideoSPA] Moving global modal back to body: ${id}`);
                        document.body.appendChild(modal);
                    }
                }
            });

            contentDiv.classList.remove('entering-forward', 'entering-backward', 'crossfade-enter');
            const isSlide = this.transitionType === 'slide-forward' || this.transitionType === 'slide-backward';

            if (this.transitionType === 'crossfade') {
                contentDiv.innerHTML = html;
                contentDiv.classList.add('crossfade-enter');
                contentDiv.addEventListener('animationend', () => {
                    contentDiv.classList.remove('crossfade-enter');
                }, { once: true });
            } else if (isSlide) {
                const oldHtml = contentDiv.innerHTML;
                const parent = contentDiv.parentElement;
                const overlay = document.getElementById('loading-state');

                parent.querySelectorAll('.spa-content-exit').forEach(el => el.remove());
                parent.style.removeProperty('overflow');

                if (overlay) {
                    overlay.style.transition = 'opacity 0s';
                    overlay.classList.remove('active');
                    overlay.style.opacity = '0';
                }

                contentDiv.innerHTML = html;

                let oldClone = null;
                if (oldHtml && oldHtml.trim().length > 0) {
                    oldClone = document.createElement('div');
                    oldClone.className = 'spa-content spa-content-exit';
                    oldClone.innerHTML = oldHtml.replace(/\s+hx-\w+(=(["']).*?\2)?/gi, '');
                    oldClone.style.position = 'absolute';
                    oldClone.style.top = '0';
                    oldClone.style.left = '0';
                    oldClone.style.width = '100%';
                    oldClone.style.height = '100%';
                    parent.appendChild(oldClone);
                }

                parent.style.setProperty('overflow', 'visible', 'important');

                requestAnimationFrame(() => {
                    if (overlay) {
                        overlay.style.transition = '';
                        overlay.style.opacity = '';
                    }

                    if (oldClone) {
                        oldClone.classList.add(this.transitionType === 'slide-forward' ? 'exiting-forward' : 'exiting-backward');
                        oldClone.addEventListener('animationend', () => {
                            if (oldClone.parentNode) oldClone.parentNode.removeChild(oldClone);
                        }, { once: true });
                    }

                    contentDiv.classList.add(this.transitionType === 'slide-forward' ? 'entering-forward' : 'entering-backward');
                    contentDiv.addEventListener('animationend', () => {
                        contentDiv.classList.remove('entering-forward', 'entering-backward');
                        parent.style.removeProperty('overflow');
                    }, { once: true });
                });
            }
            if (window.htmx) {
                htmx.process(contentDiv);
            }
            this.executeScripts(contentDiv);
        }
    }
    
    executeScripts(container) {
        const scripts = container.querySelectorAll('script');
        scripts.forEach(script => {
            const newScript = document.createElement('script');
            if (script.src) newScript.src = script.src;
            else newScript.textContent = script.textContent;
            document.head.appendChild(newScript).parentNode.removeChild(newScript);
        });
    }
    
    showLoading() {
        const el = document.getElementById('loading-state');
        if (el) el.classList.add('active');
        this._loadingStartTime = Date.now();
        if (this._loadingTimer) {
            clearTimeout(this._loadingTimer);
            this._loadingTimer = null;
        }
    }
    
    hideLoading() {
        const el = document.getElementById('loading-state');
        if (!el) return;
        const elapsed = Date.now() - (this._loadingStartTime || 0);
        const minTime = 500;
        if (elapsed < minTime) {
            if (this._loadingTimer) {
                clearTimeout(this._loadingTimer);
            }
            this._loadingTimer = setTimeout(() => {
                el.classList.remove('active');
                this._loadingTimer = null;
            }, minTime - elapsed);
        } else {
            if (this._loadingTimer) {
                clearTimeout(this._loadingTimer);
                this._loadingTimer = null;
            }
            el.classList.remove('active');
        }
    }
    
    handleError(error) {
        console.error('SPA Error:', error);
        this.hideLoading();
        const contentDiv = document.getElementById('spa-content');
        if (contentDiv) {
            contentDiv.innerHTML = '<div class="notification is-danger"><strong>Error:</strong> ' + error.message + '</div>';
        }
    }

    toggleSidebar() {
        const layout = document.getElementById('standard-layout');
        if (layout) {
            layout.classList.toggle('collapsed');
            localStorage.setItem('sidebarCollapsed', layout.classList.contains('collapsed'));
        }
    }

    async checkResumePlayback() {
        try {
            const res = await fetch('/api/video/playback/current');
            if (!res.ok) return false;
            const data = await res.json();
            
            if (data.success && data.video && data.video.id && data.video.playing) {
                await this.switchSection('playback', {videoId: data.video.id}, true);
                return true;
            }
            return false;
        } catch (e) {
            console.error('[VideoSPA] Failed to check resume playback:', e);
            return false;
        }
    }

    async applySidebarPreference() {
        try {
            const profileId = localStorage.getItem('activeProfileId') || '1';
            const res = await fetch(`/api/settings/${profileId}/sidebar-position`);
            const json = await res.json();
            if (res.ok && json.data) {
                const layout = document.getElementById('standard-layout');
                if (layout) {
                    if (json.data === 'right') {
                        layout.classList.add('sidebar-right');
                    } else {
                        layout.classList.remove('sidebar-right');
                    }
                }
            }
        } catch (e) {
            console.error('[VideoSPA] Failed to apply sidebar preference:', e);
        }
    }
    
    async init() {
        if (localStorage.getItem('sidebarCollapsed') === 'true') {
             const layout = document.getElementById('standard-layout');
             if(layout) layout.classList.add('collapsed');
        }
        
        this.applySidebarPreference();
        this.initKeyboardNavigation();
        this.initSearchClear();
        this.initWatchedToggleDelegate();
        
        const urlParams = new URLSearchParams(window.location.search);
        const section = urlParams.get('section');
        if (section) {
            const params = {};
            urlParams.forEach((value, key) => {
                if(key !== 'section') params[key] = value;
            });
            this.switchSection(section, params, true); 
        } else {
            // Only auto-resume if user didn't explicitly leave the video section
            const suppressResume = sessionStorage.getItem('videoSuppressAutoResume') === 'true';
            sessionStorage.removeItem('videoSuppressAutoResume');
            let resumed = false;
            if (!suppressResume) {
                resumed = await this.checkResumePlayback();
            }
            if (!resumed) {
                const content = document.getElementById('spa-content');
                if (content && !content.innerHTML.trim()) {
                    this.goHome(true);
                }
            }
        }
    }
    
    initKeyboardNavigation() {
        this.handleKeydown = (e) => {
             const searchInput = document.getElementById('globalSearchInput');
             if (e.key === '/' && document.activeElement.tagName !== 'INPUT' && searchInput) {
                e.preventDefault();
                searchInput.focus();
            }
        };
        document.addEventListener('keydown', this.handleKeydown);
    }
    
    initSearchClear() {
        this.handleClick = (e) => {
            const suggestions = document.getElementById('searchSuggestions');
            if (suggestions && !e.target.closest('.search-container')) {
                suggestions.innerHTML = '';
            }
        };
        document.addEventListener('click', this.handleClick);
    }

    initWatchedToggleDelegate() {
        document.addEventListener('click', (e) => {
            const toggle = e.target.closest('.standard-watched-toggle');
            if (!toggle) return;
            e.stopPropagation();
            const card = toggle.closest('[data-video-id]');
            if (!card) return;
            const videoId = parseInt(card.getAttribute('data-video-id'));
            if (!videoId) return;
            window.toggleWatched(videoId, toggle);
        }, true);
    }
}

window.videoSPA = new VideoSPA();

window.toggleWatched = function(videoId, el) {
    fetch('/api/video/progress/' + videoId + '/toggle-watched', { method: 'POST' })
        .then(r => r.json())
        .then(data => {
            if (data.success) {
                const icon = el.querySelector('i');
                const entry = el.closest('[data-video-id]');
                if (data.data.watched) {
                    if (icon) icon.className = 'pi pi-check-circle';
                    if (entry) entry.classList.add('is-watched');
                    if (window.showToast) window.showToast('Marked as watched', 'success');
                } else {
                    if (icon) icon.className = 'pi pi-circle';
                    if (entry) entry.classList.remove('is-watched');
                    if (window.showToast) window.showToast('Marked as unwatched', 'info');
                }
            } else {
                if (window.showToast) window.showToast(data.error || 'Failed to toggle', 'danger');
            }
        })
        .catch(err => {
            console.error('Error toggling watched:', err);
            if (window.showToast) window.showToast('Error toggling watched status', 'danger');
        });
};

window.videoSearchTimeout = null;
window.handleVideoSearch = function(section, query) {
    clearTimeout(window.videoSearchTimeout);
    window.videoSearchTimeout = setTimeout(() => {
        const params = { ...window.videoSPA.currentParams, page: 1, search: query };
        window.videoSPA.switchSection(section, params);
    }, 500);
};

window.clearVideoSearch = function(section) {
    const input = document.getElementById('videoSearchInput');
    if (input) input.value = '';
    const params = { ...window.videoSPA.currentParams, page: 1, search: '' };
    window.videoSPA.switchSection(section, params);
};

window.handleAdminVideoSearch = function(query) {
    clearTimeout(window.videoSearchTimeout);
    window.videoSearchTimeout = setTimeout(() => {
        const params = { ...window.videoSPA.currentParams, page: 1, search: query };
        window.videoSPA.switchSection('adminHistory', params);
    }, 500);
};

window.clearAdminVideoSearch = function() {
    const input = document.getElementById('adminVideoSearchInput');
    if (input) input.value = '';
    const params = { ...window.videoSPA.currentParams, page: 1, search: '' };
    window.videoSPA.switchSection('adminHistory', params);
};

window.selectItem = (item, action, extraParams) => window.videoSPA.selectItem(item, action, extraParams);
window.switchSection = (section, params) => window.videoSPA.switchSection(section, params);

window.addToWatchlist = async (title, id) => {
    try {
        const response = await fetch(`/api/video/watchlist/toggle/${id}`, { method: 'POST', credentials: 'same-origin' });
        const result = await response.json();
        
        if (result.success) {
            const isFavorite = result.data;
            const message = isFavorite ? `${title} added to watchlist` : `${title} removed from watchlist`;
            if (window.showToast) window.showToast(message, 'success');
            
            if (window.videoSPA.currentSection === 'watchlist') {
                window.videoSPA.switchSection('watchlist', {}, true);
            }
        } else {
            if (window.showToast) window.showToast('Failed to update watchlist', 'danger');
        }
    } catch (error) {
        console.error('Watchlist Error:', error);
        if (window.showToast) window.showToast('Error updating watchlist', 'danger');
    }
};

window.scrollCarousel = (carouselId, direction) => {
    const carousel = document.getElementById(carouselId);
    if (carousel) {
        const amount = 400;
        carousel.scrollBy({ left: direction === 'left' ? -amount : amount, behavior: 'smooth' });
    }
};

window.playExternalEntry = function(externalId) {
    if (window.videoSPA) {
        window.videoSPA.playbackExternal(externalId);
    }
};

window.collectionMgr = new window.CollectionManager(window.videoSPA);

window.underplayerPlayCard = function(card) {
    var videoId = card.getAttribute('data-video-id');
    var entryId = card.getAttribute('data-entry-id');
    var collectionId = card.getAttribute('data-collection-id');
    var mediaType = card.getAttribute('data-media-type');
    if (mediaType === 'external') {
        if (window.selectExternalVideo) window.selectExternalVideo(videoId);
    } else {
        var params = {};
        if (collectionId) { params.collectionId = collectionId; }
        if (entryId) { params.entryId = entryId; }
        if (window.selectItem) window.selectItem(videoId, 'play', params);
    }
};


