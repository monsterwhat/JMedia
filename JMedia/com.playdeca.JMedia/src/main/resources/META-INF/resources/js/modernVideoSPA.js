/**
 * Modern Video SPA Controller
 * Enhanced with ES6+ features, better state management, and performance optimizations
 */

class ModernVideoSPA {
    constructor() {
        this.state = {
            currentSection: 'carousels',
            currentVideo: null,
            isLoading: false,
            error: null,
            loadingMessage: 'Loading content...',
            searchQuery: '',
            searchResults: [],
            notifications: [],
            userPreferences: this.loadUserPreferences()
        };

        this.sections = {
            carousels: '/api/video/ui/carousels-fragment',
            details: '/api/video/ui/details-fragment/{videoId}',
            playback: '/api/video/ui/playback-fragment',
            movies: '/api/video/ui/movies-fragment',
            shows: '/api/video/ui/shows-fragment'
        };

        this.loadingMessages = {
            carousels: 'Loading featured content...',
            details: 'Loading video details...',
            playback: 'Starting playback...',
            movies: 'Loading movies...',
            shows: 'Loading TV shows...'
        };

        this.cache = new Map();
        this.observers = new Set();
        this.performanceMetrics = {
            pageLoads: [],
            searchQueries: [],
            userActions: []
        };

        this.init();
    }

    // Initialize the SPA
    async init() {
        try {
            // Set up global profile ID
            window.globalActiveProfileId = localStorage.getItem('activeProfileId') || '1';
            
            // Initialize service worker if available
            await this.setupServiceWorker();
            
            // Set up performance monitoring
            this.setupPerformanceMonitoring();
            
            // Set up keyboard shortcuts
            this.setupKeyboardShortcuts();
            
            // Set up search functionality
            this.setupSearch();
            
            // Set up error handling
            this.setupErrorHandling();
            
            // Set up state observers
            this.setupStateObservers();
            
            // Initialize additional components
            this.setupAdditionalComponents();
            
            // Load initial content
            await this.switchSection('carousels');
            
            // Hide initial loading overlay
            this.hideInitialLoader();
            
            console.log('ModernVideoSPA initialized successfully');
        } catch (error) {
            console.error('Failed to initialize ModernVideoSPA:', error);
            this.handleError(error);
        }
    }

    // State Management
    setState(updates, notify = true) {
        const prevState = { ...this.state };
        this.state = { ...this.state, ...updates };
        
        if (notify) {
            this.notifyStateChange(prevState, this.state, updates);
        }
        
        // Persist important state changes
        this.persistState(updates);
    }

    getState() {
        return { ...this.state };
    }

    subscribe(callback) {
        this.observers.add(callback);
        return () => this.observers.delete(callback);
    }

    notifyStateChange(prevState, newState, updates) {
        this.observers.forEach(callback => {
            try {
                callback(newState, prevState, updates);
            } catch (error) {
                console.error('Error in state observer:', error);
            }
        });
    }

    // Section Navigation with History Management
    async switchSection(section, params = {}) {
        if (this.state.isLoading) return;

        const startTime = performance.now();
        this.setState({
            isLoading: true,
            error: null,
            loadingMessage: this.loadingMessages[section] || 'Loading...'
        });

        try {
            const url = this.buildUrl(section, params);
            const html = await this.fetchContent(url);
            
            await this.updateContent(html);
            
            this.setState({
                currentSection: section,
                isLoading: false
            });

            // Track performance
            this.trackPageLoad(section, performance.now() - startTime);
            
            // Update URL and history
            this.updateBrowserHistory(section, params);
            
        } catch (error) {
            this.handleError(error);
        }
    }

    // Enhanced Content Fetching with Caching
    async fetchContent(url) {
        const cacheKey = url + (window.globalActiveProfileId || '');
        
        // Check cache first
        if (this.cache.has(cacheKey)) {
            const cached = this.cache.get(cacheKey);
            if (Date.now() - cached.timestamp < 300000) { // 5 minutes cache
                return cached.data;
            }
        }

        const response = await fetch(url, {
            headers: {
                'X-Profile-ID': window.globalActiveProfileId,
                'Accept': 'text/html',
                'Cache-Control': 'no-cache'
            }
        });

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        const data = await response.text();
        
        // Cache the response
        this.cache.set(cacheKey, {
            data,
            timestamp: Date.now()
        });

        return data;
    }

    // Enhanced Content Update with Script Execution
    async updateContent(html) {
        const contentDiv = document.getElementById('dynamic-content');
        if (!contentDiv) return;

        // Create a temporary div to parse HTML
        const tempDiv = document.createElement('div');
        tempDiv.innerHTML = html;

        // Extract and execute scripts
        const scripts = tempDiv.querySelectorAll('script');
        for (const script of scripts) {
            try {
                if (script.src) {
                    await this.loadExternalScript(script.src);
                } else {
                    this.executeInlineScript(script.textContent);
                }
            } catch (error) {
                console.error('Error executing script:', error);
            }
        }

        // Update content
        contentDiv.innerHTML = html;

        // Re-initialize components for new content
        this.reinitializeComponents();
    }

    // Enhanced Item Selection with Analytics
    async selectItem(item, action) {
        try {
            // Track user action
            this.trackUserAction('item_select', {
                action,
                itemType: item.type || 'unknown',
                itemId: item.id
            });

            switch (action) {
                case 'play':
                    await this.playVideo(item);
                    break;
                case 'details':
                    await this.switchSection('details', {videoId: item.id});
                    break;
                case 'queue-add':
                    await this.addToQueue(item.id);
                    this.showToast('Added to queue', 'success');
                    break;
                case 'watchlist-add':
                    await this.addToWatchlist(item);
                    this.showToast('Added to watchlist', 'success');
                    break;
                default:
                    console.warn('Unknown action:', action);
            }
        } catch (error) {
            console.error(`Failed to ${action}:`, error);
            this.showToast(`Failed to ${action}: ${error.message}`, 'error');
        }
    }

    // Enhanced Video Playback
    async playVideo(item) {
        try {
            // Update playback state via API
            const response = await fetch(`/api/video/playback/play/${item.id}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' }
            });

            if (!response.ok) {
                throw new Error(`Failed to start playback: ${response.statusText}`);
            }

            // Switch to playback view
            await this.switchSection('playback', {videoId: item.id});
            
            // Track playback start
            this.trackUserAction('playback_start', {
                videoId: item.id,
                videoType: item.type,
                source: 'carousel'
            });

        } catch (error) {
            throw new Error(`Failed to play video: ${error.message}`);
        }
    }

    // Enhanced Search with Debouncing and Caching
    setupSearch() {
        const searchInput = document.querySelector('.input.is-search');
        if (!searchInput) return;

        let searchTimeout;
        
        const handleSearch = (query) => {
            clearTimeout(searchTimeout);
            
            if (query.length < 2) {
                this.setState({ searchResults: [] });
                return;
            }

            searchTimeout = setTimeout(async () => {
                await this.performSearch(query);
            }, 300);
        };

        // Input event with debouncing
        searchInput.addEventListener('input', (e) => {
            const query = e.target.value.trim();
            this.setState({ searchQuery: query });
            handleSearch(query);
        });

        // Keyboard navigation for search results
        searchInput.addEventListener('keydown', (e) => {
            this.handleSearchKeyboard(e);
        });
    }

    async performSearch(query) {
        const startTime = performance.now();
        
        try {
            this.setState({ isLoading: true });
            
            const response = await fetch(`/api/video/search?q=${encodeURIComponent(query)}&limit=8`, {
                headers: { 'X-Profile-ID': window.globalActiveProfileId }
            });

            if (!response.ok) {
                throw new Error(`Search failed: ${response.statusText}`);
            }

            const results = await response.json();
            const processedResults = results.map(item => ({
                ...item,
                thumbnail: item.thumbnailPath || `/api/video/thumbnail/${item.id}`,
                displayTitle: item.title || item.seriesTitle || 'Unknown Title',
                displayMeta: this.formatSearchMeta(item)
            }));

            this.setState({ 
                searchResults: processedResults,
                isLoading: false
            });

            // Track search performance
            this.trackSearchQuery(query, processedResults.length, performance.now() - startTime);

        } catch (error) {
            console.error('Search error:', error);
            this.setState({ 
                searchResults: [],
                isLoading: false
            });
            this.showToast('Search failed. Please try again.', 'error');
        }
    }

    // Enhanced Keyboard Shortcuts
    setupKeyboardShortcuts() {
        document.addEventListener('keydown', (e) => {
            // Ignore if user is typing in input
            if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;

            const key = e.key.toLowerCase();
            const modifiers = {
                ctrl: e.ctrlKey,
                alt: e.altKey,
                shift: e.shiftKey,
                meta: e.metaKey
            };

            const shortcuts = this.state.userPreferences.keyboardShortcuts || {};
            
            // Check for configured shortcuts
            for (const [action, shortcut] of Object.entries(shortcuts)) {
                if (this.matchesShortcut(e, shortcut)) {
                    e.preventDefault();
                    this.handleShortcut(action, modifiers);
                    return;
                }
            }

            // Default shortcuts
            this.handleDefaultShortcuts(key, modifiers, e);
        });
    }

    // Add missing methods
    matchesShortcut(e, shortcut) {
        if (typeof shortcut === 'string') {
            return e.key.toLowerCase() === shortcut.toLowerCase();
        }
        
        if (typeof shortcut === 'object') {
            return e.key.toLowerCase() === (shortcut.key || '').toLowerCase() &&
                   e.ctrlKey === (shortcut.ctrl || false) &&
                   e.altKey === (shortcut.alt || false) &&
                   e.shiftKey === (shortcut.shift || false) &&
                   e.metaKey === (shortcut.meta || false);
        }
        
        return false;
    }

    handleShortcut(action, modifiers) {
        switch (action) {
            case 'play':
                // Handle play/pause
                break;
            case 'pause':
                // Handle pause
                break;
            case 'fullscreen':
                // Handle fullscreen
                if (document.fullscreenElement) {
                    document.exitFullscreen();
                } else {
                    document.documentElement.requestFullscreen();
                }
                break;
            case 'mute':
                // Handle mute/unmute
                break;
            case 'seekForward':
                // Handle seek forward
                break;
            case 'seekBackward':
                // Handle seek backward
                break;
            case 'volumeUp':
                // Handle volume up
                break;
            case 'volumeDown':
                // Handle volume down
                break;
            case 'search':
                this.focusSearch();
                break;
        }
    }

    handleDefaultShortcuts(key, modifiers, e) {
        switch (key) {
            case '/':
                e.preventDefault();
                this.focusSearch();
                break;
            case 'escape':
                this.handleEscape();
                break;
            case 'backspace':
                if (modifiers.ctrl || modifiers.meta) {
                    e.preventDefault();
                    this.goBack();
                }
                break;
        }
    }

    focusSearch() {
        const searchInput = document.querySelector('.input.is-search');
        if (searchInput) {
            searchInput.focus();
        }
    }

    handleEscape() {
        // Close any open modals
        const modals = document.querySelectorAll('.modal.is-active');
        modals.forEach(modal => {
            modal.classList.remove('is-active');
        });
        
        // Exit fullscreen if in playback mode
        if (document.fullscreenElement) {
            document.exitFullscreen();
        }
    }

    goBack() {
        if (window.history.length > 1) {
            window.history.back();
        }
    }

    // Enhanced Error Handling
    setupErrorHandling() {
        window.addEventListener('error', (e) => {
            this.handleError(new Error(e.message), 'javascript');
        });

        window.addEventListener('unhandledrejection', (e) => {
            this.handleError(new Error(e.reason), 'promise');
        });

        // Network status monitoring
        window.addEventListener('online', () => {
            this.showToast('Connection restored', 'success');
        });

        window.addEventListener('offline', () => {
            this.showToast('Connection lost. Some features may be unavailable.', 'warning');
        });
    }

    // Add missing methods
    addToQueue(videoId) {
        return fetch(`/api/video/queue/add/${videoId}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' }
        });
    }

    addToWatchlist(item) {
        console.log('Add to watchlist:', item);
        // Implementation depends on your watchlist system
    }

    // Enhanced Performance Monitoring
    setupPerformanceMonitoring() {
        // Page visibility API
        document.addEventListener('visibilitychange', () => {
            if (document.hidden) {
                this.trackUserAction('page_hide');
            } else {
                this.trackUserAction('page_show');
            }
        });

        // Memory monitoring
        if (performance.memory) {
            setInterval(() => {
                const memory = performance.memory;
                if (memory.usedJSHeapSize > memory.totalJSHeapSize * 0.9) {
                    console.warn('High memory usage detected:', memory);
                    this.performMemoryCleanup();
                }
            }, 30000);
        }
    }

    // Service Worker Setup for Offline Support
    async setupServiceWorker() {
        if ('serviceWorker' in navigator) {
            try {
                const registration = await navigator.serviceWorker.register('/sw.js');
                console.log('Service Worker registered:', registration);
            } catch (error) {
                console.log('Service Worker registration failed:', error);
            }
        }
    }

    // Enhanced Toast Notifications
    showToast(message, type = 'info', duration = 5000) {
        // Use global Toast system for display
        const toastId = window.showToast ? window.showToast(message, type, duration) : null;
        
        const toast = {
            id: toastId || Date.now(),
            message,
            type,
            timestamp: Date.now()
        };

        // Maintain state management for tracking notifications
        this.setState(prevState => ({
            notifications: [...prevState.notifications, toast].slice(-5) // Keep only last 5
        }));

        // Auto-remove from state after duration
        setTimeout(() => {
            this.setState(prevState => ({
                notifications: prevState.notifications.filter(n => n.id !== toast.id)
            }));
        }, duration);

        // Track notification
        this.trackUserAction('notification_show', { type, message });
    }

    // Enhanced Utility Methods
    buildUrl(section, params) {
        let url = this.sections[section] || section;
        for (const [key, value] of Object.entries(params)) {
            url = url.replace('{' + key + '}', encodeURIComponent(value));
        }
        return url;
    }

    formatDuration(seconds) {
        if (!seconds || seconds < 0) return '0m';
        
        const hours = Math.floor(seconds / 3600);
        const minutes = Math.floor((seconds % 3600) / 60);
        const remainingSeconds = seconds % 60;
        
        if (hours > 0) {
            return `${hours}h ${minutes}m`;
        } else if (minutes > 0) {
            return `${minutes}m ${remainingSeconds > 0 ? remainingSeconds + 's' : ''}`;
        } else {
            return `${remainingSeconds}s`;
        }
    }

    formatSearchMeta(item) {
        const parts = [];
        
        if (item.type) parts.push(item.type);
        if (item.releaseYear) parts.push(item.releaseYear);
        if (item.durationSeconds) parts.push(this.formatDuration(item.durationSeconds));
        if (item.type === 'Episode' && item.seasonNumber && item.episodeNumber) {
            parts.push(`S${item.seasonNumber}E${item.episodeNumber}`);
        }
        
        return parts.join(' • ');
    }

    // Analytics and Tracking
    trackUserAction(action, data = {}) {
        const event = {
            action,
            timestamp: Date.now(),
            section: this.state.currentSection,
            profileId: window.globalActiveProfileId,
            ...data
        };

        this.performanceMetrics.userActions.push(event);
        
        // Send to analytics if available
        if (window.gtag) {
            window.gtag('event', action, {
                custom_parameter_1: this.state.currentSection,
                custom_parameter_2: window.globalActiveProfileId,
                ...data
            });
        }
    }

    trackPageLoad(section, duration) {
        this.performanceMetrics.pageLoads.push({
            section,
            duration,
            timestamp: Date.now()
        });
    }

    trackSearchQuery(query, resultCount, duration) {
        this.performanceMetrics.searchQueries.push({
            query,
            resultCount,
            duration,
            timestamp: Date.now()
        });
    }

    // Performance Optimization
    performMemoryCleanup() {
        // Clear old cache entries
        const now = Date.now();
        for (const [key, value] of this.cache.entries()) {
            if (now - value.timestamp > 600000) { // 10 minutes
                this.cache.delete(key);
            }
        }

        // Force garbage collection if available
        if (window.gc) {
            window.gc();
        }
    }

    // User Preferences Management
    loadUserPreferences() {
        try {
            const stored = localStorage.getItem('videoSPA_preferences');
            return stored ? JSON.parse(stored) : this.getDefaultPreferences();
        } catch (error) {
            console.error('Failed to load preferences:', error);
            return this.getDefaultPreferences();
        }
    }

    saveUserPreferences(preferences) {
        try {
            localStorage.setItem('videoSPA_preferences', JSON.stringify(preferences));
            this.setState({ userPreferences: preferences });
        } catch (error) {
            console.error('Failed to save preferences:', error);
        }
    }

    getDefaultPreferences() {
        return {
            autoPlay: true,
            theme: 'dark',
            language: 'en',
            subtitles: true,
            quality: 'auto',
            keyboardShortcuts: {
                play: ' ',
                pause: ' ',
                fullscreen: 'f',
                mute: 'm',
                seekForward: 'ArrowRight',
                seekBackward: 'ArrowLeft',
                volumeUp: 'ArrowUp',
                volumeDown: 'ArrowDown',
                search: '/'
            }
        };
    }

    // Additional helper methods
    hideInitialLoader() {
        setTimeout(() => {
            const loader = document.getElementById('initial-loading');
            if (loader) {
                loader.style.opacity = '0';
                setTimeout(() => loader.style.display = 'none', 300);
            }
        }, 1000);
    }

    // Setup additional components
    setupAdditionalComponents() {
        // Setup profile manager integration
        if (window.profileManager) {
            this.profileManager = window.profileManager;
        }
    }

    // Fix for missing methods
    setupStateObservers() {
        // Initialize state observers if needed
        this.observers = new Set();
    }

    updateBrowserHistory(section, params) {
        const url = this.buildSectionUrl(section, params);
        window.history.pushState({ section, params }, '', url);
    }

    buildSectionUrl(section, params) {
        // Build proper URL based on section
        switch (section) {
            case 'details':
                return `/video/details/${params.videoId}`;
            case 'playback':
                return `/video/play/${params.videoId}`;
            case 'movies':
                return '/video/movies';
            case 'shows':
                return '/video/shows';
            default:
                return '/video';
        }
    }

    // Export for global access
    static getInstance() {
        if (!window.videoSPA) {
            window.videoSPA = new ModernVideoSPA();
        }
        return window.videoSPA;
    }
}

    // Simple fallback SPA for when Alpine.js isn't available
    class SimpleVideoSPA {
        constructor() {
            this.state = {
                currentSection: 'carousels',
                isLoading: false,
                error: null
            };
        }
        
        async switchSection(section, params = {}) {
            if (this.state.isLoading) return;
            
            this.state.isLoading = true;
            try {
                const url = this.buildUrl(section, params);
                const response = await fetch(url);
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                
                const html = await response.text();
                const contentDiv = document.getElementById('dynamic-content');
                if (contentDiv) contentDiv.innerHTML = html;
                
                this.state.currentSection = section;
            } catch (error) {
                console.error('Simple SPA error:', error);
            } finally {
                this.state.isLoading = false;
            }
        }
        
        buildUrl(section, params) {
            const sections = {
                carousels: '/api/video/ui/carousels-fragment',
                details: '/api/video/ui/details-fragment/{videoId}',
                playback: '/api/video/ui/playback-fragment'
            };
            
            let url = sections[section] || section;
            for (const [key, value] of Object.entries(params)) {
                url = url.replace('{' + key + '}', encodeURIComponent(value));
            }
            return url;
        }
    }

    // Initialize on DOM load
document.addEventListener('DOMContentLoaded', () => {
    // Check if Alpine.js is available
    if (typeof Alpine !== 'undefined') {
        console.log('Alpine.js detected, using enhanced SPA');
        
        // Try enhanced SPA initialization with timeout
        setTimeout(() => {
            try {
                ModernVideoSPA.getInstance();
                console.log('ModernVideoSPA initialized successfully');
            } catch (error) {
                console.error('Failed to initialize ModernVideoSPA:', error);
                // Fallback to simple SPA
                window.simpleSPA = new SimpleVideoSPA();
                window.switchSection = (section, params) => window.simpleSPA.switchSection(section, params);
                console.log('Using simple SPA fallback');
            }
        }, 100); // Short timeout to avoid blocking
        
    } else {
        console.log('Alpine.js not detected, using simple SPA');
        window.simpleSPA = new SimpleVideoSPA();
        window.switchSection = (section, params) => window.simpleSPA.switchSection(section, params);
    }
});

// Export for module usage
if (typeof module !== 'undefined' && module.exports) {
    module.exports = ModernVideoSPA;
}