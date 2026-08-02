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
            extras: '/api/video/ui/shows/{seriesTitle}/extras/{contentType}/episodes-fragment',
            details: '/api/video/ui/details-fragment/{videoId}',
            playback: '/api/video/ui/playback-fragment?videoId={videoId}',
            external: '/api/video/external/fragment',
            liveTv: '/api/video/ui/live-tv-fragment',
            collections: '/api/video/ui/collections-fragment',
            collectionEntries: '/api/video/ui/collections/{collectionId}/entries-fragment',
            liveTvPlayback: '/api/video/ui/live-channel-playback-fragment?channelId={channelId}',
            nowPlaying: '/api/video/ui/now-playing-fragment'
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
        this.resetNowPlayingController();
        // Destroy current player to cleanup FFmpeg processes
        if (section !== 'nowPlaying') {
            await this.destroyCurrentPlayer();
        }
        this.showLoading(section);
        
        if (!bypassHistory) {
            if (section === 'playback') {
                this.transitionType = 'crossfade';
            } else if (section !== 'home') {
                this.transitionType = 'slide-forward';
            }
        }
        
        if (!bypassHistory) {
            if (section === 'playback' || section === 'details' || section === 'episodes' || section === 'seasons' || section === 'folder-episodes' || section === 'extras' || section === 'collectionEntries') {
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

        // Handle live channel playback
        if (section === 'playback' && params.liveChannelId) {
            await this.playbackLiveChannel(params.liveChannelId, bypassHistory);
            return;
        }

        this.updateNavState(section);
        const apiUrl = this.buildApiUrl(section, params);
        try {
            const html = await this.fetchContent(apiUrl);
            this.updateContent(html);
            if (section === 'nowPlaying') {
                this.ensureNowPlayingController();
            }
            this.hideLoading();
            
            if (!bypassHistory) {
                const spaUrl = this.buildSpaUrl(section, params);
                history.pushState({ section, params, view: 'video' }, '', spaUrl);
            }

            this.currentSection = section;
            this.currentParams = params;
            this.updateBreadcrumbs();

            // Keep the browser tab on the video home branding while browsing
            // (an active player overrides this with the video title)
            if (section !== 'playback' && section !== 'nowPlaying') {
                if (window.JMedia && window.JMedia.PageChrome) {
                    if (section === 'details') {
                        this.updatePageChromeForDetails(params);
                    } else {
                        window.JMedia.PageChrome.setVideoHome();
                    }
                }
            }
        } catch (error) {
            this.handleError(error);
        }
    }

    /**
     * Tab chrome for the details view: title + logo favicon.
     * Episodes show the show name (data-series-title) instead of the episode title.
     */
    updatePageChromeForDetails(params) {
        const pc = window.JMedia && window.JMedia.PageChrome;
        if (!pc) return;
        const container = document.querySelector('.detail-container');
        const seriesTitle = container ? container.getAttribute('data-series-title') : '';
        const type = container ? container.getAttribute('data-type') : '';
        const isEpisode = type && String(type).toLowerCase() === 'episode';
        const titleEl = document.querySelector('.title-section h1');
        let title = titleEl ? titleEl.textContent.trim() : (params.videoTitle || '');
        if (isEpisode && seriesTitle) title = seriesTitle;
        if (!title) title = 'JMedia';
        pc.setForVideoDetails(title);
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
        this.updateBreadcrumbs();
        this.hideLoading();
        if (window.JMedia && window.JMedia.PageChrome) window.JMedia.PageChrome.setVideoHome();
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

    async playbackLiveChannel(channelId, bypassHistory = false) {
        this.showLoading();
        try {
            const res = await fetch(`/api/video/ui/live-channel-playback-fragment?channelId=${channelId}`);
            if (!res.ok) throw new Error('Channel not found');
            const html = await res.text();

            await this.destroyCurrentPlayer();
            this.updateContent(html);
            this.hideLoading();

            if (!bypassHistory) {
                history.pushState({ section: 'playback', params: { liveChannelId: channelId }, view: 'video' }, '', `/video?section=playback&liveChannelId=${channelId}`);
            }
            this.currentSection = 'playback';
            this.currentParams = { liveChannelId: channelId };
        } catch (error) {
            this.handleError(error);
        }
    }

    buildExternalPlayerHtml(v) {
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
            <script src="/js/player/UIBuilder.js?v=5"><\/script>
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

    async destroyCurrentPlayer() {
        // Clean up OPlayer adapter (WebSocket + OPlayer instance) if active
        if (typeof window.destroyOPlayerAdapter === 'function') {
            window.destroyOPlayerAdapter();
        }
        if (window.currentPlayerInstance && typeof window.currentPlayerInstance.destroy === 'function') {
            await window.currentPlayerInstance.destroy();
        }
        if (window.player && typeof window.player.destroy === 'function') {
            await window.player.destroy();
        }
        window.currentPlayerInstance = null;
        window.player = null;

        if (window.videoSidebarController) {
            window.videoSidebarController.destroy();
            window.videoSidebarController = null;
        }
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
    
    _decodeBcParam(str) {
        if (!str) return '';
        try { return decodeURIComponent(String(str).replace(/\+/g, ' ')); }
        catch (e) { return String(str).replace(/\+/g, ' '); }
    }

    _ensureBreadcrumbInSpa() {
        var spa = document.getElementById('spa-content');
        if (!spa) return;
        // Remove ALL existing breadcrumb-nav elements (stale ones in old animation clones, etc.)
        document.querySelectorAll('#breadcrumb-nav').forEach(function(n) { n.remove(); });
        // Determine target: carousels-section (home), library-header (sub-sections), or #spa-content
        var target;
        var carousels = document.getElementById('carousels-section');
        if (carousels && spa.contains(carousels)) {
            target = carousels;
        } else {
            var libHdr = spa.querySelector('.library-header');
            if (libHdr) {
                target = libHdr;
                // Allow breadcrumb to sit above as a full row when shown
                libHdr.style.flexWrap = 'wrap';
            } else {
                target = spa;
            }
        }
        var nav = document.createElement('nav');
        nav.id = 'breadcrumb-nav';
        nav.className = 'breadcrumb is-small is-centered has-arrow-separator';
        nav.setAttribute('aria-label', 'breadcrumbs');
        nav.style.display = 'none';
        nav.style.marginBottom = '0';
        nav.style.alignItems = 'center';
        nav.style.width = '100%';
        nav.innerHTML = '<ul id="breadcrumb-list"></ul>';
        target.insertBefore(nav, target.firstChild);
    }

    updateBreadcrumbs() {
        if (!window.Breadcrumbs) return;
        this._ensureBreadcrumbInSpa();
        const section = this.currentSection;
        const params = this.currentParams || {};
        const self = this;
        const bcHome = { label: 'Video', navigate: function() { self.goHome(); } };
        const bcShows = { label: 'TV Shows', navigate: function() { self.switchSection('shows', {}); } };
        const bcMovies = { label: 'Movies', navigate: function() { self.switchSection('movies', {}); } };
        const labelMap = {
            movies: 'Movies', shows: 'TV Shows',
            history: 'History', watchlist: 'Watchlist', manage: 'Manage Library',
            needsAttention: 'Needs Attention', verification: 'Verification',
            details: 'Details', external: 'External Link',
            collections: 'Collections', adminHistory: 'All History',
            suggestion: 'Suggestions', adminSuggestions: 'All Suggestions',
            manageSeries: 'Manage Series',
            extras: 'Extras',
            liveTv: 'Live TV',
            liveTvPlayback: 'Live TV',
            nowPlaying: 'Now Playing'
        };

        if (section === 'seasons' && params.encodedTitle) {
            window.Breadcrumbs.set([
                bcHome, bcShows,
                this._decodeBcParam(params.encodedTitle)
            ]);
        } else if (section === 'episodes' && params.seriesTitle && params.seasonNumber) {
            var decoded = this._decodeBcParam(params.seriesTitle);
            var epBack = { label: decoded, navigate: function() {
                self.switchSection('seasons', { encodedTitle: encodeURIComponent(decoded) });
            }};
            window.Breadcrumbs.set([
                bcHome, bcShows, epBack,
                'Season ' + params.seasonNumber
            ]);
        } else if (section === 'folder-episodes' && params.seriesTitle && params.seasonNumber && params.folderName) {
            var decoded = this._decodeBcParam(params.seriesTitle);
            var fepBack = { label: decoded, navigate: function() {
                self.switchSection('seasons', { encodedTitle: encodeURIComponent(decoded) });
            }};
            window.Breadcrumbs.set([
                bcHome, bcShows, fepBack,
                'Season ' + params.seasonNumber,
                this._decodeBcParam(params.folderName)
            ]);
        } else if (section === 'extras' && params.seriesTitle && params.contentType) {
            var decoded = this._decodeBcParam(params.seriesTitle);
            var exBack = { label: decoded, navigate: function() {
                self.switchSection('seasons', { encodedTitle: encodeURIComponent(decoded) });
            }};
            window.Breadcrumbs.set([
                bcHome, bcShows, exBack,
                this._decodeBcParam(params.contentType)
            ]);
        } else if (section === 'playback' && params.videoId) {
            var player = document.getElementById('customPlayer');
            if (player) {
                var pType = (player.getAttribute('data-type') || '').toLowerCase();
                var pSeries = player.getAttribute('data-series-title') || '';
                var pSeason = player.getAttribute('data-season-number') || '';
                var pTitle = player.getAttribute('data-title') || 'Now Playing';
                if (pType === 'episode' && pSeries) {
                    var pDecoded = self._decodeBcParam(pSeries);
                    var pBack = { label: pDecoded, navigate: function() {
                        self.switchSection('seasons', { encodedTitle: encodeURIComponent(pDecoded) });
                    }};
                    var pSeasonNav = { label: 'Season ' + pSeason, navigate: function() {
                        self.switchSection('episodes', { seriesTitle: encodeURIComponent(pSeries), seasonNumber: parseInt(pSeason, 10) || 1 });
                    }};
                    window.Breadcrumbs.set([
                        bcHome, bcShows, pBack,
                        pSeasonNav,
                        pTitle
                    ]);
                } else {
                    window.Breadcrumbs.set([bcHome, pTitle]);
                }
            } else {
                window.Breadcrumbs.set([bcHome, 'Now Playing']);
            }
        } else if (section === 'collectionEntries') {
            var name = document.querySelector('.series-hero-title')?.textContent?.trim()
                    || 'Collection';
            window.Breadcrumbs.set([bcHome, name]);
        } else if (section === 'details') {
            var titleEl = document.querySelector('.title-section h1');
            var movieTitle = titleEl ? titleEl.textContent.trim() : (params.videoTitle || 'Details');
            window.Breadcrumbs.set([bcHome, movieTitle]);
        } else if (section === 'home' || !labelMap[section]) {
            window.Breadcrumbs.set(['Video']);
        } else {
            window.Breadcrumbs.set([bcHome, labelMap[section]]);
        }
    }

    getSectionSkeleton(section) {
        var cardGrid = '';
        for (var i = 0; i < 8; i++) {
            cardGrid += '<div class="standard-card"><div class="standard-card-poster"><div class="skeleton-block is-skeleton" style="width:100%;height:100%;border-radius:0;"></div></div><div class="standard-card-info" style="padding:0.75rem;"><div class="skeleton-lines"><div></div><div style="width:60%;"></div></div></div></div>';
        }

        var wideCardGrid = '';
        for (var j = 0; j < 8; j++) {
            wideCardGrid += '<div class="column is-12-mobile is-6-tablet is-4-desktop is-3-widescreen"><div class="manage-grid-card" style="background:rgba(255,255,255,0.03);border:1px solid rgba(255,255,255,0.05);border-radius:12px;overflow:hidden;"><div style="aspect-ratio:16/9;"><div class="skeleton-block is-skeleton" style="width:100%;height:100%;border-radius:0;"></div></div><div class="p-3"><div class="skeleton-lines"><div></div><div style="width:50%;"></div></div></div></div></div>';
        }

        var headerSkel = '<div class="library-header is-flex is-justify-content-between is-align-items-center" style="flex-wrap:wrap;gap:1rem;"><div><div class="skeleton-block is-skeleton" style="height:1.8rem;width:140px;margin-bottom:0.5rem;"></div><div class="skeleton-block is-skeleton" style="height:1rem;width:80px;"></div></div><div class="is-flex is-align-items-center" style="gap:0.75rem;"><div class="skeleton-block is-skeleton" style="height:2.2em;width:250px;border-radius:999px;"></div><div class="skeleton-block is-skeleton" style="height:2.2em;width:130px;border-radius:999px;"></div></div></div>';

        var heroSkel = '<div style="margin:0 2rem 2rem;border-radius:16px;height:45vh;background:#1a1a1a;display:flex;align-items:flex-end;overflow:hidden;"><div style="width:100%;padding:4rem 3rem;background:linear-gradient(to top,rgba(10,10,10,1),rgba(10,10,10,0.4),transparent);"><div class="skeleton-block is-skeleton mb-3" style="height:1rem;width:120px;border-radius:4px;"></div><div class="skeleton-block is-skeleton mb-4" style="height:4rem;width:450px;border-radius:8px;"></div><div class="skeleton-block is-skeleton" style="height:1.6rem;width:160px;border-radius:4px;"></div></div></div>';

        var seasonCardGrid = '';
        for (var si = 0; si < 6; si++) {
            seasonCardGrid += '<div class="standard-card"><div class="standard-card-poster"><div class="skeleton-block is-skeleton" style="width:100%;height:100%;border-radius:0;"></div></div><div class="standard-card-info" style="padding:0.75rem;"><div class="skeleton-lines"><div></div><div style="width:50%;"></div></div><div class="skeleton-block is-skeleton" style="height:4px;width:100%;margin-top:8px;border-radius:2px;"></div></div></div>';
        }

        var episodeCardGrid = '';
        for (var ei = 0; ei < 6; ei++) {
            episodeCardGrid += '<div class="episode-entry" style="background:rgba(255,255,255,0.03);border:1px solid rgba(255,255,255,0.05);border-radius:12px;overflow:hidden;"><div style="aspect-ratio:16/9;"><div class="skeleton-block is-skeleton" style="width:100%;height:100%;border-radius:0;"></div></div><div style="padding:1.25rem;"><div class="is-flex is-align-items-start mb-2" style="gap:0.75rem;"><div class="skeleton-block is-skeleton" style="width:32px;height:20px;border-radius:4px;flex-shrink:0;"></div><div style="flex:1;"><div class="skeleton-lines"><div></div><div style="width:60%;"></div></div></div></div></div></div>';
        }

        var skeletons = {
            movies: headerSkel + '<div class="standard-grid">' + cardGrid + '</div>',
            shows: headerSkel + '<div class="standard-grid">' + cardGrid + '</div>',
            history: '<div class="standard-grid">' + cardGrid + '</div>',
            watchlist: '<div class="standard-grid">' + cardGrid + '</div>',
            collections: '<div class="standard-grid">' + cardGrid + '</div>',
            manage: '<div class="library-header is-flex is-justify-content-between is-align-items-center" style="flex-wrap:wrap;gap:1rem;"><div><div class="skeleton-block is-skeleton" style="height:1.8rem;width:160px;margin-bottom:0.5rem;"></div><div class="skeleton-block is-skeleton" style="height:1rem;width:80px;"></div></div><div class="is-flex is-align-items-center" style="gap:0.75rem;"><div class="skeleton-block is-skeleton" style="height:2.2em;width:250px;border-radius:999px;"></div><div class="skeleton-block is-skeleton" style="height:2.2em;width:90px;border-radius:999px;"></div></div></div><div class="columns is-multiline mt-4">' + wideCardGrid + '</div>',
            needsAttention: '<div class="library-header is-flex is-justify-content-between is-align-items-center"><div><div class="skeleton-block is-skeleton" style="height:1.8rem;width:180px;margin-bottom:0.5rem;"></div></div><div class="skeleton-block is-skeleton" style="height:2.2em;width:250px;border-radius:999px;"></div></div><div class="columns is-multiline mt-4">' + wideCardGrid + '</div>',
            collectionEntries: '<div style="margin:0 2rem 2rem;border-radius:16px;height:40vh;background:#1a1a1a;display:flex;align-items:flex-end;padding:3rem;"><div><div class="skeleton-block is-skeleton mb-3" style="height:1rem;width:120px;border-radius:4px;"></div><div class="skeleton-block is-skeleton" style="height:3.5rem;width:350px;border-radius:6px;margin-bottom:0.75rem;"></div><div class="skeleton-block is-skeleton" style="height:1.2rem;width:200px;border-radius:4px;"></div></div></div><div style="padding:0 2rem;"><div class="skeleton-block is-skeleton" style="height:2em;width:300px;border-radius:999px;margin-bottom:1.5rem;"></div>' + Array(5).fill('<div class="is-flex is-align-items-center mb-3" style="gap:1rem;"><div class="skeleton-block is-skeleton" style="width:100px;height:56px;border-radius:8px;flex-shrink:0;"></div><div style="flex:1;"><div class="skeleton-lines"><div style="width:60%;"></div><div style="width:40%;"></div></div></div><div class="skeleton-block is-skeleton" style="width:32px;height:32px;border-radius:50%;"></div></div>').join('') + '</div>',
            seasons: heroSkel + '<div class="standard-grid" style="padding:0 1rem;">' + seasonCardGrid + '</div>',
            episodes: heroSkel + '<div class="episodes-list" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:1.5rem;padding:1rem;">' + episodeCardGrid + '</div>',
            'folder-episodes': heroSkel + '<div class="episodes-list" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:1.5rem;padding:1rem;">' + episodeCardGrid + '</div>',
            extras: heroSkel + '<div class="episodes-list" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:1.5rem;padding:1rem;">' + episodeCardGrid + '</div>',
            details: '<div class="detail-container" style="display:flex;gap:4rem;padding:3rem;margin:1rem;min-height:calc(100vh - 200px);"><div class="poster-section" style="flex:0 0 400px;"><div class="skeleton-block is-skeleton" style="width:100%;aspect-ratio:2/3;border-radius:16px;"></div></div><div class="info-section" style="flex:1;display:flex;flex-direction:column;gap:2rem;"><div class="skeleton-block is-skeleton" style="height:2.5rem;width:120px;border-radius:8px;"></div><div><div class="skeleton-block is-skeleton" style="height:4.5rem;width:550px;border-radius:8px;margin-bottom:0.75rem;"></div><div class="skeleton-block is-skeleton" style="height:1.2rem;width:350px;border-radius:4px;"></div></div><div class="is-flex" style="gap:1rem;"><div class="skeleton-block is-skeleton" style="width:90px;height:60px;border-radius:8px;"></div><div class="skeleton-block is-skeleton" style="width:90px;height:60px;border-radius:8px;"></div></div><div class="is-flex" style="gap:1rem;"><div class="skeleton-block is-skeleton" style="height:3rem;width:160px;border-radius:999px;"></div><div class="skeleton-block is-skeleton" style="height:3rem;width:160px;border-radius:999px;"></div><div class="skeleton-block is-skeleton" style="height:3rem;width:160px;border-radius:999px;"></div></div><div class="skeleton-lines" style="max-width:800px;"><div></div><div></div><div style="width:70%;"></div></div><div class="is-flex mt-2" style="gap:0.5rem;"><div class="skeleton-block is-skeleton" style="height:2rem;width:90px;border-radius:999px;"></div><div class="skeleton-block is-skeleton" style="height:2rem;width:110px;border-radius:999px;"></div><div class="skeleton-block is-skeleton" style="height:2rem;width:70px;border-radius:999px;"></div></div></div></div>',
            adminHistory: headerSkel + '<div class="standard-grid">' + cardGrid + '</div>',
            external: headerSkel + '<div class="standard-grid">' + cardGrid + '</div>',
            liveTv: headerSkel + '<div class="standard-grid">' + cardGrid + '</div>'
        };
        return skeletons[section] || null;
    }

    showLoading(section) {
        this._loadingStartTime = Date.now();
        if (this._loadingTimer) {
            clearTimeout(this._loadingTimer);
            this._loadingTimer = null;
        }
        var contentDiv = document.getElementById('spa-content');
        var skeleton = section && this.getSectionSkeleton(section);
        if (contentDiv && skeleton) {
            contentDiv.innerHTML = skeleton;
            return;
        }
        var el = document.getElementById('loading-state');
        if (el) el.classList.add('active');
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
        const layout = document.getElementById('standard-layout');
        const cached = localStorage.getItem('sidebarPosition');
        if (layout) {
            if (cached === 'right') layout.classList.add('sidebar-right');
            else if (cached === 'left') layout.classList.remove('sidebar-right');
        }

        try {
            const profileId = localStorage.getItem('activeProfileId') || '1';
            const res = await fetch(`/api/settings/${profileId}/sidebar-position`);
            const json = await res.json();
            if (res.ok && json.data && layout) {
                if (json.data === 'right') {
                    layout.classList.add('sidebar-right');
                } else {
                    layout.classList.remove('sidebar-right');
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

    ensureNowPlayingController() {
        // Belt-and-suspenders: guarantee a live VideoSidebarController exists for the Now Playing
        // view, even if the template's inline script was skipped or failed to load.
        if (window.videoSidebarController) return;
        if (window.VideoSidebarController) {
            window.videoSidebarController = new window.VideoSidebarController();
            return;
        }
        if (window.__npControllerScriptLoading) return;
        window.__npControllerScriptLoading = true;
        const s = document.createElement('script');
        s.src = '/js/video/VideoSidebarController.js';
        document.head.appendChild(s);
        let attempts = 0;
        const pollId = setInterval(() => {
            if (window.VideoSidebarController) {
                clearInterval(pollId);
                if (!window.videoSidebarController) {
                    window.videoSidebarController = new window.VideoSidebarController();
                }
                window.__npControllerScriptLoading = false;
            } else if (++attempts >= 20) {
                clearInterval(pollId);
                window.__npControllerScriptLoading = false;
                console.warn('[VideoSPA] ensureNowPlayingController: VideoSidebarController failed to load after 20 attempts');
            }
        }, 50);
    }

    resetNowPlayingController() {
        if (window.videoSidebarController) {
            try { window.videoSidebarController.destroy(); } catch (e) { /* ignore */ }
            window.videoSidebarController = null;
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
    if (window.__npIsRemoteController) {
        window.npRemoteSwitchVideo(card.getAttribute('data-video-id'));
        return;
    }
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


