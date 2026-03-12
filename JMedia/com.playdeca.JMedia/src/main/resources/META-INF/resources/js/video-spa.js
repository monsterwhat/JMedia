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
            watchlist: '/api/video/ui/watchlist-fragment',
            manage: '/api/video/manage',
            seasons: '/api/video/ui/shows/{encodedTitle}/seasons-fragment',
            episodes: '/api/video/ui/shows/{seriesTitle}/seasons/{seasonNumber}/episodes-fragment',
            details: '/api/video/ui/details-fragment/{videoId}',
            playback: '/api/video/ui/playback-fragment?videoId={videoId}'
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
        this.showLoading();
        
        if (!bypassHistory) {
            this.backDestination = null;
        }
        
        if (section === 'home') {
            this.goHome(bypassHistory);
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
        if (this.backDestination) {
            const dest = this.backDestination;
            this.backDestination = null;
            this.switchSection(dest.section, dest.params || {}, true);
            return;
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
            <div id="hero-section" 
                 hx-get="/api/video/ui/hero-fragment"
                 hx-trigger="load"
                 hx-target="#hero-section"
                 hx-swap="innerHTML">
            </div>
            <div id="carousels-section" 
                 hx-get="/api/video/ui/optimized-carousels"
                 hx-trigger="load delay:100ms"
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
            await fetch('/api/video/playback/play/' + videoId, { method: 'POST' });
            await this.switchSection('playback', {videoId: videoId});
        } catch (error) {
            this.handleError(error);
        }
    }
    
    updateNavState(section) {
        document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));
        
        // Handle specific video IDs
        let navId = 'nav-' + section;
        if (section === 'history') navId = 'nav-video-history';
        if (section === 'watchlist') navId = 'nav-video-watchlist';
        
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
        const layout = document.getElementById('plex-layout');
        if (layout) {
            layout.classList.toggle('collapsed');
            localStorage.setItem('sidebarCollapsed', layout.classList.contains('collapsed'));
        }
    }

    async checkResumePlayback() {
        try {
            const res = await fetch('/api/video/playback/current');
            if (!res.ok) return;
            const data = await res.json();
            
            this.applySidebarPreference();

            if (data.success && data.video && data.video.id && data.video.playing) {
                this.switchSection('playback', {videoId: data.video.id});
            }
        } catch (e) {
            console.error('[VideoSPA] Failed to check resume playback:', e);
        }
    }

    async applySidebarPreference() {
        try {
            const profileId = localStorage.getItem('activeProfileId') || '1';
            const res = await fetch(`/api/settings/${profileId}/sidebar-position`);
            const json = await res.json();
            if (res.ok && json.data) {
                const layout = document.getElementById('plex-layout');
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
    
    init() {
        if (localStorage.getItem('sidebarCollapsed') === 'true') {
             const layout = document.getElementById('plex-layout');
             if(layout) layout.classList.add('collapsed');
        }
        
        this.applySidebarPreference();
        this.initKeyboardNavigation();
        this.initSearchClear();
        
        const urlParams = new URLSearchParams(window.location.search);
        const section = urlParams.get('section');
        if (section) {
            const params = {};
            urlParams.forEach((value, key) => {
                if(key !== 'section') params[key] = value;
            });
            this.switchSection(section, params, true); 
        } else {
             const content = document.getElementById('spa-content');
             if (content && !content.innerHTML.trim()) {
                 this.goHome(true);
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
}

window.videoSPA = new VideoSPA();

window.selectItem = (item, action) => window.videoSPA.selectItem(item, action);
window.switchSection = (section, params) => window.videoSPA.switchSection(section, params);

window.addToWatchlist = async (title, id) => {
    try {
        const response = await fetch(`/api/video/watchlist/toggle/${id}`, { method: 'POST' });
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

window.initVideoView = function() {
    window.videoSPA.init();
};
