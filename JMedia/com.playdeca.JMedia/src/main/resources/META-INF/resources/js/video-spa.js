class VideoSPA {
    constructor() {
        this.currentSection = 'home';
        this.currentParams = {};
        this.backDestination = null; 
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
        this.showLoading();
        
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
        
        if (window.history.length > 1) {
            window.history.back();
        } else {
            this.goHome(true);
        }
    }
    
    goHome(bypassHistory = false) {
        this.backDestination = null;
        this.updateNavState('home');
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

    async selectItem(item, action) {
        const videoId = (typeof item === 'object') ? item.id : item;
        if (!videoId) return;

        switch(action) {
            case 'play':
                await this.playVideo(videoId);
                break;
            case 'details':
                await this.switchSection('details', {videoId: videoId});
                break;
        }
    }
    
    async playVideo(videoId) {
        this.showLoading();
        try {
            // Fetch video details first to get the resume time
            const res = await fetch(`/api/video/${videoId}`);
            const json = await res.json();
            let startTime = 0;
            if (json.success && json.data && json.data.resumeTime) {
                startTime = json.data.resumeTime / 1000;
            }

            await fetch(`/api/video/playback/play/${videoId}?startTime=${startTime}`, { method: 'POST' });
            await this.switchSection('playback', {videoId: videoId});
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
            this.destroyCurrentPlayer();
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
        const proxyUrl = '/api/video/external/proxy/stream?url=' + encodeURIComponent(v.url);
        const isHls = v.sourceType === 'hls' || v.url.includes('.m3u8');
        const alts = v.alternativeUrls && Array.isArray(v.alternativeUrls) && v.alternativeUrls.length > 0
            ? JSON.stringify(v.alternativeUrls).replace(/"/g, '&quot;') : '';
        return `
            <link rel="stylesheet" href="/css/player.css"/>
            <div class="player-container paused" id="customPlayer"
                 data-external-url="${this.escapeAttr(proxyUrl)}"
                 data-external-original-url="${this.escapeAttr(v.url)}"
                 data-external-id="${v.id}"
                 data-external-is-hls="${isHls}"
                 data-title="${this.escapeAttr(v.title)}"
                 data-duration="0"
                 data-start-time="${v.currentTime || 0}"
                 data-type="external">
                <div class="video-wrapper">
                    <video id="videoElement" crossorigin="anonymous" playsinline autoplay></video>
                </div>
            </div>
            <script src="/js/simple-player.js"><\/script>
            <script>
                (function() {
                    const tryInit = () => {
                        if (window.SimplePlayer) {
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

    destroyCurrentPlayer() {
        if (window.currentPlayerInstance && typeof window.currentPlayerInstance.destroy === 'function') {
            window.currentPlayerInstance.destroy();
        }
        if (window.player && typeof window.player.destroy === 'function') {
            window.player.destroy();
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
            if (window.currentPlayerInstance && typeof window.currentPlayerInstance.destroy === 'function') {
                window.currentPlayerInstance.destroy();
            }

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

            contentDiv.innerHTML = html;
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
        if (el) el.style.display = 'flex';
    }
    
    hideLoading() {
        const el = document.getElementById('loading-state');
        if (el) el.style.display = 'none';
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
            // Try auto-resume last playing video first
            const resumed = await this.checkResumePlayback();
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

window.selectItem = (item, action) => window.videoSPA.selectItem(item, action);
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

// ==================== COLLECTIONS ====================

window.showCreateCollectionModal = function() {
    const nameInput = document.getElementById('collectionNameInput');
    const descInput = document.getElementById('collectionDescInput');
    if (nameInput) nameInput.value = '';
    if (descInput) descInput.value = '';
    const modal = document.getElementById('createCollectionModal');
    if (modal) modal.classList.add('is-active');
};

window.submitCreateCollection = async function() {
    const name = document.getElementById('collectionNameInput')?.value?.trim();
    if (!name) { if (window.showToast) window.showToast('Name is required', 'warning'); return; }
    const desc = document.getElementById('collectionDescInput')?.value?.trim() || '';
    try {
        const res = await fetch('/api/collections', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: 'name=' + encodeURIComponent(name) + (desc ? '&description=' + encodeURIComponent(desc) : '')
        });
        const json = await res.json();
        if (json.success) {
            const modal = document.getElementById('createCollectionModal');
            if (modal) modal.classList.remove('is-active');
            if (window.showToast) window.showToast('Collection created', 'success');
            window.switchSection('collections');
        } else {
            if (window.showToast) window.showToast(json.error || 'Failed to create', 'danger');
        }
    } catch (e) {
        if (window.showToast) window.showToast('Error creating collection', 'danger');
    }
};

window.deleteCollection = async function(id, name) {
    if (!confirm('Delete collection "' + name + '"? This cannot be undone.')) return;
    try {
        const res = await fetch('/api/collections/' + id, { method: 'DELETE' });
        const json = await res.json();
        if (json.success) {
            if (window.showToast) window.showToast('Collection deleted', 'success');
            window.switchSection('collections');
        } else {
            if (window.showToast) window.showToast(json.error || 'Failed to delete', 'danger');
        }
    } catch (e) {
        if (window.showToast) window.showToast('Error deleting collection', 'danger');
    }
};

window.toggleGroup = function(header) {
    const body = header.nextElementSibling;
    const icon = header.querySelector('.pi-chevron-down');
    if (body) {
        body.classList.toggle('collapsed');
        if (icon) icon.style.transform = body.classList.contains('collapsed') ? 'rotate(-90deg)' : 'rotate(0deg)';
    }
};

window.tvShowSelectSeries = function(index) {
    document.getElementById('tvSeriesGrid').classList.add('is-hidden');
    document.querySelectorAll('.tv-season-list').forEach(el => el.classList.add('is-hidden'));
    const target = document.querySelector('.tv-season-list[data-series-index="' + index + '"]');
    if (target) target.classList.remove('is-hidden');
};

window.tvShowBackToSeries = function() {
    document.getElementById('tvSeriesGrid').classList.remove('is-hidden');
    document.querySelectorAll('.tv-season-list').forEach(el => el.classList.add('is-hidden'));
    document.querySelectorAll('.tv-episode-grid').forEach(el => el.classList.add('is-hidden'));
};

window.tvShowSelectSeason = function(seriesIndex, seasonIndex) {
    document.querySelectorAll('.tv-season-list').forEach(el => el.classList.add('is-hidden'));
    document.querySelectorAll('.tv-episode-grid').forEach(el => el.classList.add('is-hidden'));
    const target = document.querySelector('.tv-episode-grid[data-series-index="' + seriesIndex + '"][data-season-index="' + seasonIndex + '"]');
    if (target) target.classList.remove('is-hidden');
};

window.tvShowBackToSeasons = function(seriesIndex) {
    document.querySelectorAll('.tv-episode-grid').forEach(el => el.classList.add('is-hidden'));
    const target = document.querySelector('.tv-season-list[data-series-index="' + seriesIndex + '"]');
    if (target) target.classList.remove('is-hidden');
};

window.filterAddVideoList = function(query) {
    const q = query.toLowerCase().trim();
    document.querySelectorAll('.add-card').forEach(el => {
        const searchData = el.getAttribute('data-search') || '';
        el.style.display = (!q || searchData.includes(q)) ? '' : 'none';
    });
    document.querySelectorAll('.tv-episode-grid').forEach(grid => {
        if (!q) { grid.classList.add('is-hidden'); return; }
        const hasVisible = [...grid.querySelectorAll('.add-card')].some(el => el.style.display !== 'none');
        grid.classList.toggle('is-hidden', !hasVisible);
    });
    if (!q) {
        document.querySelectorAll('.tv-season-list').forEach(el => el.classList.add('is-hidden'));
        document.getElementById('tvSeriesGrid').classList.remove('is-hidden');
    }
};

window.showAddEntryModal = function() {
    const input = document.getElementById('addVideoSearchInput');
    if (input) input.value = '';
    document.querySelectorAll('.add-card').forEach(el => el.style.display = '');
    document.querySelectorAll('.tv-episode-grid').forEach(el => el.classList.add('is-hidden'));
    document.querySelectorAll('.tv-season-list').forEach(el => el.classList.add('is-hidden'));
    document.getElementById('tvSeriesGrid').classList.remove('is-hidden');
    document.querySelectorAll('.video-group-body').forEach(body => body.classList.remove('collapsed'));
    const search = document.getElementById('addVideoSearchInput');
    if (search) search.scrollIntoView({ behavior: 'smooth' });
};

window.updateCardToAdded = function(card, entryId) {
    if (!card) return;
    card.setAttribute('data-in-collection', 'true');
    card.setAttribute('data-entry-id', entryId);
    const overlay = card.querySelector('.standard-card-overlay');
    if (overlay) {
        const btn = overlay.querySelector('.standard-play-btn');
        if (btn) {
            btn.style.background = '#e74c3c';
            btn.innerHTML = '<i class="pi pi-times"></i>';
            btn.onclick = function(e) {
                e.stopPropagation();
                window.removeEntry(entryId);
            };
        }
    }
    const info = card.querySelector('.standard-card-info');
    if (info) {
        const titleDiv = info.querySelector('.standard-card-title');
        if (titleDiv && !titleDiv.querySelector('.tag.is-warning')) {
            titleDiv.insertAdjacentHTML('beforeend', ' <span class="tag is-warning is-light is-small">In Collection</span>');
        }
    }
};

window.updateCardToRemoved = function(card) {
    if (!card) return;
    card.setAttribute('data-in-collection', 'false');
    card.removeAttribute('data-entry-id');
    const overlay = card.querySelector('.standard-card-overlay');
    if (overlay) {
        const btn = overlay.querySelector('.standard-play-btn');
        if (btn) {
            const videoId = card.getAttribute('data-video-id');
            btn.style.background = '';
            btn.innerHTML = '<i class="pi pi-plus"></i>';
            btn.onclick = function(e) {
                e.stopPropagation();
                window.addEntry(videoId);
            };
        }
    }
    const info = card.querySelector('.standard-card-info');
    if (info) {
        const titleDiv = info.querySelector('.standard-card-title');
        if (titleDiv) {
            const tag = titleDiv.querySelector('.tag.is-warning');
            if (tag) tag.remove();
        }
    }
};

window.buildEntryHtml = function(videoId, entryId, orderIndex, card) {
    let title = '';
    let metaHtml = '';
    let thumbSrc = '/api/video/thumbnail/' + videoId;
    if (card) {
        const titleEl = card.querySelector('.standard-card-title');
        if (titleEl) {
            const clone = titleEl.cloneNode(true);
            const tag = clone.querySelector('.tag');
            if (tag) tag.remove();
            title = clone.textContent.trim();
        }
        const metaEl = card.querySelector('.standard-card-meta');
        if (metaEl) {
            metaHtml = metaEl.textContent.trim();
        }
        const img = card.querySelector('.poster-img');
        if (img) thumbSrc = img.getAttribute('src') || thumbSrc;
    }
    const escapedTitle = title.replace(/"/g, '&quot;');
    return '<div class="collection-entry" data-entry-id="' + entryId + '" data-video-id="' + videoId + '">' +
        '<div class="entry-order-handle" title="Drag to reorder"><i class="pi pi-bars"></i></div>' +
        '<div class="entry-order-badge">' + orderIndex + '</div>' +
        '<div class="entry-thumbnail" onclick="window.selectItem(' + videoId + ', \'details\')">' +
        '<img src="' + thumbSrc + '" alt="' + escapedTitle + '" loading="lazy">' +
        '</div>' +
        '<div class="entry-info" onclick="window.selectItem(' + videoId + ', \'details\')">' +
        '<div class="entry-title">' + escapedTitle + '</div>' +
        '<div class="entry-meta">' + metaHtml + '</div>' +
        '</div>' +
        '<div class="entry-actions">' +
        '<button class="button is-small is-rounded is-success" onclick="event.stopPropagation(); window.selectItem(' + videoId + ', \'play\')" title="Play"><i class="pi pi-play"></i></button>' +
        '<button class="button is-small is-rounded admin-only" onclick="event.stopPropagation(); showEditEntryModal(' + entryId + ', \'\')" title="Edit"><i class="pi pi-pencil"></i></button>' +
        '<button class="button is-small is-rounded is-danger admin-only" onclick="event.stopPropagation(); window.removeEntry(' + entryId + ')" title="Remove"><i class="pi pi-times"></i></button>' +
        '</div></div>';
};

window.addEntryToCollectionList = function(videoId, entryId) {
    const entriesContainer = document.getElementById('collectionEntriesList');
    if (!entriesContainer) return;
    const existingEntries = entriesContainer.querySelectorAll('.collection-entry');
    let nextOrder = 1;
    if (existingEntries.length > 0) {
        const lastBadge = existingEntries[existingEntries.length - 1]?.querySelector('.entry-order-badge');
        const lastOrder = parseInt(lastBadge?.textContent) || 0;
        nextOrder = lastOrder + 1;
    }
    const card = document.querySelector('.add-card[data-video-id="' + videoId + '"]');
    const html = window.buildEntryHtml(videoId, entryId, nextOrder, card);
    const emptyState = entriesContainer.querySelector('.carousel-empty-state');
    if (emptyState) {
        emptyState.outerHTML = '<div class="collection-entries" id="sortableEntries">' + html + '</div>';
    } else {
        let sortableList = document.getElementById('sortableEntries');
        if (sortableList) {
            sortableList.insertAdjacentHTML('beforeend', html);
        } else {
            entriesContainer.innerHTML = '<div class="collection-entries" id="sortableEntries">' + html + '</div>';
        }
    }
    if (window.initCollectionDragDrop) window.initCollectionDragDrop();
};

window.removeEntryFromCollectionList = function(entryId) {
    const entry = document.querySelector('.collection-entry[data-entry-id="' + entryId + '"]');
    if (entry) {
        entry.remove();
        const entries = document.querySelectorAll('.collection-entry');
        if (entries.length === 0) {
            const container = document.getElementById('collectionEntriesList');
            if (container) {
                container.innerHTML = '<div class="carousel-empty-state">' +
                    '<i class="pi pi-th-large"></i>' +
                    '<h3>Collection is empty</h3>' +
                    '<p>Click "Add Video" to start building your watch order.</p>' +
                    '</div>';
            }
            return;
        }
        entries.forEach((el, idx) => {
            const badge = el.querySelector('.entry-order-badge');
            if (badge) badge.textContent = (idx + 1).toString();
        });
        if (window.initCollectionDragDrop) window.initCollectionDragDrop();
    }
};

window.addEntry = async function(videoId) {
    const collectionId = window.videoSPA?.currentParams?.collectionId;
    if (!collectionId) return;
    const entries = document.querySelectorAll('.collection-entry');
    let nextOrder = 1;
    if (entries.length > 0) {
        const lastBadge = entries[entries.length - 1]?.querySelector('.entry-order-badge');
        const lastOrder = parseInt(lastBadge?.textContent) || 0;
        nextOrder = lastOrder + 1;
    }
    try {
        const res = await fetch(`/api/collections/${collectionId}/entries`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: 'videoId=' + videoId + '&orderIndex=' + nextOrder
        });
        const json = await res.json();
        if (json.success) {
            const entryId = json.data?.id;
            const card = document.querySelector('.add-card[data-video-id="' + videoId + '"]');
            if (card) {
                window.updateCardToAdded(card, entryId);
            }
            window.addEntryToCollectionList(videoId, entryId);
            if (window.showToast) window.showToast('Added to collection', 'success');
        } else {
            if (window.showToast) window.showToast(json.error || 'Failed to add', 'danger');
        }
    } catch (e) {
        if (window.showToast) window.showToast('Error adding entry', 'danger');
    }
};

window.removeEntry = async function(entryId) {
    const collectionId = window.videoSPA?.currentParams?.collectionId;
    if (!collectionId) return;
    try {
        const res = await fetch('/api/collections/entries/' + entryId, { method: 'DELETE' });
        const json = await res.json();
        if (json.success) {
            const card = document.querySelector('.add-card[data-entry-id="' + entryId + '"]');
            if (card) {
                window.updateCardToRemoved(card);
            }
            window.removeEntryFromCollectionList(entryId);
            if (window.showToast) window.showToast('Entry removed', 'success');
        } else {
            if (window.showToast) window.showToast(json.error || 'Failed to remove', 'danger');
        }
    } catch (e) {
        if (window.showToast) window.showToast('Error removing entry', 'danger');
    }
};

window.playCollection = async function(collectionId, startIndex) {
    if (startIndex === undefined) startIndex = 0;
    window.videoSPA.showLoading();
    try {
        const res = await fetch('/api/collections/' + collectionId + '/play', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: 'startIndex=' + startIndex
        });
        const json = await res.json();
        if (json.success && json.data && json.data.videoId) {
            window.videoSPA.playVideo(json.data.videoId);
        } else {
            if (window.showToast) window.showToast(json.error || 'Failed to play collection', 'danger');
            window.videoSPA.hideLoading();
        }
    } catch (e) {
        if (window.showToast) window.showToast('Error playing collection', 'danger');
        window.videoSPA.hideLoading();
    }
};

window.toggleCollectionEntry = function(videoId, entryId, inCollection) {
    if (inCollection && entryId) {
        removeEntry(entryId);
    } else if (!inCollection) {
        addEntry(videoId);
    }
};

window.batchAddEpisodes = async function(seriesIndex, seasonIndex) {
    const collectionId = window.videoSPA?.currentParams?.collectionId;
    if (!collectionId) return;
    const container = document.querySelector('.tv-episode-grid[data-series-index="' + seriesIndex + '"][data-season-index="' + seasonIndex + '"]');
    if (!container) return;
    const entries = document.querySelectorAll('.collection-entry');
    let nextOrder = 1;
    if (entries.length > 0) {
        const lastBadge = entries[entries.length - 1]?.querySelector('.entry-order-badge');
        nextOrder = (parseInt(lastBadge?.textContent) || 0) + 1;
    }
    const cards = container.querySelectorAll('.add-card[data-in-collection="false"]');
    let added = 0;
    for (const card of cards) {
        const videoId = card.getAttribute('data-video-id');
        if (!videoId) continue;
        try {
            const res = await fetch('/api/collections/' + collectionId + '/entries', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: 'videoId=' + videoId + '&orderIndex=' + (nextOrder + added)
            });
            const json = await res.json();
            if (json.success && json.data?.id) {
                window.updateCardToAdded(card, json.data.id);
                window.addEntryToCollectionList(videoId, json.data.id);
                added++;
            }
        } catch (e) {}
    }
    if (added > 0) {
        if (window.showToast) window.showToast('Added ' + added + ' episode' + (added > 1 ? 's' : ''), 'success');
    }
};

window.batchRemoveEpisodes = async function(seriesIndex, seasonIndex) {
    const collectionId = window.videoSPA?.currentParams?.collectionId;
    if (!collectionId) return;
    const container = document.querySelector('.tv-episode-grid[data-series-index="' + seriesIndex + '"][data-season-index="' + seasonIndex + '"]');
    if (!container) return;
    const cards = container.querySelectorAll('.add-card[data-in-collection="true"]');
    let removed = 0;
    for (const card of cards) {
        const entryId = card.getAttribute('data-entry-id');
        if (!entryId) continue;
        try {
            const res = await fetch('/api/collections/entries/' + entryId, { method: 'DELETE' });
            const json = await res.json();
            if (json.success) {
                window.updateCardToRemoved(card);
                window.removeEntryFromCollectionList(entryId);
                removed++;
            }
        } catch (e) {}
    }
    if (removed > 0) {
        if (window.showToast) window.showToast('Removed ' + removed + ' episode' + (removed > 1 ? 's' : ''), 'success');
    }
};

window.showEditEntryModal = function(entryId, notes) {
    const input = document.getElementById('editEntryNotesInput');
    if (input) {
        input.value = notes || '';
        window._editingEntryId = entryId;
    }
    const modal = document.getElementById('editEntryModal');
    if (modal) modal.classList.add('is-active');
};

window.submitEditEntry = async function() {
    const entryId = window._editingEntryId;
    if (!entryId) return;
    const notes = document.getElementById('editEntryNotesInput')?.value?.trim() || '';
    try {
        const res = await fetch('/api/collections/entries/' + entryId, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: notes ? 'notes=' + encodeURIComponent(notes) : ''
        });
        const json = await res.json();
        if (json.success) {
            const modal = document.getElementById('editEntryModal');
            if (modal) modal.classList.remove('is-active');
            const entryEl = document.querySelector('.collection-entry[data-entry-id="' + entryId + '"]');
            if (entryEl) {
                const metaEl = entryEl.querySelector('.entry-meta');
                const existingTag = metaEl ? metaEl.querySelector('.tag.is-info.is-light.is-small') : null;
                if (notes) {
                    if (existingTag) {
                        existingTag.textContent = notes;
                    } else if (metaEl) {
                        const tag = document.createElement('span');
                        tag.className = 'tag is-info is-light is-small ml-2';
                        tag.textContent = notes;
                        metaEl.appendChild(tag);
                    }
                } else if (existingTag) {
                    existingTag.remove();
                }
            }
            if (window.showToast) window.showToast('Entry updated', 'success');
        } else {
            if (window.showToast) window.showToast(json.error || 'Failed to update', 'danger');
        }
    } catch (e) {
        if (window.showToast) window.showToast('Error updating entry', 'danger');
    }
};

window.initCollectionDragDrop = function() {
    const list = document.getElementById('sortableEntries');
    if (!list) return;
    let dragSrcEl = null;

    const onDragStart = function(e) {
        dragSrcEl = this;
        e.dataTransfer.effectAllowed = 'move';
        e.dataTransfer.setData('text/plain', this.getAttribute('data-entry-id'));
        this.classList.add('dragging');
    };
    const onDragEnter = function(e) {
        if (this !== dragSrcEl) this.classList.add('drag-over');
    };
    const onDragLeave = function(e) {
        this.classList.remove('drag-over');
    };
    const onDragOver = function(e) {
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
    };
    const onDrop = function(e) {
        e.preventDefault();
        this.classList.remove('drag-over');
        if (dragSrcEl && this !== dragSrcEl) {
            const parent = document.getElementById('sortableEntries');
            const items = Array.from(parent.querySelectorAll('.collection-entry'));
            const fromIdx = items.indexOf(dragSrcEl);
            const toIdx = items.indexOf(this);
            if (fromIdx < toIdx) {
                parent.insertBefore(dragSrcEl, this.nextSibling);
            } else {
                parent.insertBefore(dragSrcEl, this);
            }
            window.updateEntryOrders();
        }
    };
    const onDragEnd = function(e) {
        this.classList.remove('dragging');
        document.querySelectorAll('.collection-entry').forEach(el => el.classList.remove('drag-over'));
    };

    list.querySelectorAll('.collection-entry').forEach(el => {
        el.setAttribute('draggable', 'true');
        el.addEventListener('dragstart', onDragStart);
        el.addEventListener('dragenter', onDragEnter);
        el.addEventListener('dragleave', onDragLeave);
        el.addEventListener('dragover', onDragOver);
        el.addEventListener('drop', onDrop);
        el.addEventListener('dragend', onDragEnd);
    });
};

window.updateEntryOrders = async function() {
    const collectionId = window.videoSPA?.currentParams?.collectionId;
    if (!collectionId) return;
    const items = document.querySelectorAll('.collection-entry');
    const orderMap = {};
    items.forEach((el, idx) => {
        const entryId = el.getAttribute('data-entry-id');
        const newOrder = idx + 1;
        orderMap[entryId] = newOrder;
        const badge = el.querySelector('.entry-order-badge');
        if (badge) badge.textContent = newOrder;
    });
    try {
        await fetch(`/api/collections/${collectionId}/entries/reorder`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(orderMap)
        });
    } catch (e) {
        console.error('Failed to save reorder', e);
    }
};


