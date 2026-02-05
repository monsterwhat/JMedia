/**
 * ResponsiveNavigation - Unified navigation handling across device types
 * Manages side panels, tabs, search, and navigation gestures
 */
(function(window) {
    'use strict';
    
    window.ResponsiveNavigation = {
        // Navigation elements cache
        elements: {
            navToggle: null,
            sidePanel: null,
            sidePanelOverlay: null,
            sidePanelClose: null,
            searchInput: null,
            searchClear: null,
            tabButtons: null,
            filterSortBtn: null,
            createPlaylistBtn: null
        },
        
        // Navigation state
        state: {
            sidePanelOpen: false,
            activeTab: 'playlists',
            searchQuery: '',
            searchTimeout: null,
            currentSort: 'dateAdded',
            sortDirection: 'desc',
            filteredGenres: []
        },
        
        // Gesture tracking
        gestures: {
            touchStartX: 0,
            touchStartY: 0,
            touchStartTime: 0,
            isGesturing: false
        },
        
        /**
         * Initialize responsive navigation
         */
        init: function() {
            this.setupElements();
            this.setupEventListeners();
            this.loadInitialState();
            window.Helpers.log('ResponsiveNavigation initialized');
        },
        
        /**
         * Setup navigation elements
         */
        setupElements: function() {
            const elements = this.elements;
            
            // Navigation elements
            elements.navToggle = document.getElementById('mobileNavToggle');
            elements.sidePanel = document.getElementById('mobileSidePanel');
            elements.sidePanelOverlay = document.getElementById('mobileSidePanelOverlay');
            elements.sidePanelClose = document.getElementById('mobileSidePanelClose');
            
            // Search elements
            elements.searchInput = document.getElementById('mobileSearch');
            elements.searchClear = document.getElementById('mobileSearchClear');
            
            // Tab elements
            elements.tabButtons = document.querySelectorAll('.mobile-tab');
            
            // Other navigation elements
            elements.filterSortBtn = document.getElementById('mobileFilterSort');
            elements.createPlaylistBtn = document.getElementById('createPlaylistBtn');
            
            // Validate elements
            this.validateElements();
        },
        
        /**
         * Validate required elements exist
         */
        validateElements: function() {
            const missing = [];
            const elements = this.elements;
            
            Object.keys(elements).forEach(key => {
                if (elements[key] && 
                    (elements[key].length === undefined || elements[key].length > 0)) {
                    return; // Element exists
                }
                
                // Check if it's a collection (like NodeList)
                if (elements[key] && elements[key].length === 0) {
                    return; // Empty collection is valid
                }
                
                missing.push(key);
            });
            
            if (missing.length > 0) {
                window.Helpers.log('ResponsiveNavigation: Missing elements:', missing);
            }
        },
        
        /**
         * Setup event listeners
         */
        setupEventListeners: function() {
            // Side panel controls
            this.setupSidePanelEvents();
            
            // Tab switching
            this.setupTabEvents();
            
            // Search functionality
            this.setupSearchEvents();
            
            // Filter/sort
            this.setupFilterSortEvents();
            
            // Device-specific gestures
            this.setupGestureEvents();
            
            // Window resize handling
            this.setupResponsiveEvents();
            
            // Custom event listeners
            this.setupCustomEventListeners();
            
            window.Helpers.log('ResponsiveNavigation: Event listeners configured');
        },
        
        /**
         * Setup side panel event listeners
         */
        setupSidePanelEvents: function() {
            const elements = this.elements;
            
            // Toggle button
            if (elements.navToggle) {
                elements.navToggle.addEventListener('click', () => this.toggleSidePanel());
            }
            
            // Close button
            if (elements.sidePanelClose) {
                elements.sidePanelClose.addEventListener('click', () => this.closeSidePanel());
            }
            
            // Overlay click
            if (elements.sidePanelOverlay) {
                elements.sidePanelOverlay.addEventListener('click', () => this.closeSidePanel());
            }
            
            // Escape key
            document.addEventListener('keydown', (e) => {
                if (e.key === 'Escape' && this.state.sidePanelOpen) {
                    this.closeSidePanel();
                }
            });
        },
        
        /**
         * Setup tab switching events
         */
        setupTabEvents: function() {
            const elements = this.elements.tabButtons;
            
            if (elements && elements.length > 0) {
                elements.forEach(tab => {
                    tab.addEventListener('click', () => {
                        const tabName = tab.dataset.tab || tab.id.replace('Tab', '');
                        this.switchTab(tabName);
                    });
                });
            }
        },
        
        /**
         * Setup search event listeners
         */
        setupSearchEvents: function() {
            const elements = this.elements;
            
            // Search input
            if (elements.searchInput) {
                elements.searchInput.addEventListener('input', (e) => {
                    this.handleSearch(e.target.value);
                });
                
                elements.searchInput.addEventListener('keydown', (e) => {
                    if (e.key === 'Escape') {
                        this.clearSearch();
                    }
                });
            }
            
            // Clear button
            if (elements.searchClear) {
                elements.searchClear.addEventListener('click', () => {
                    this.clearSearch();
                });
            }
        },
        
        /**
         * Setup filter/sort event listeners
         */
        setupFilterSortEvents: function() {
            if (this.elements.filterSortBtn) {
                this.elements.filterSortBtn.addEventListener('click', () => {
                    // Emit request to show filter/sort menu
                    window.dispatchEvent(new CustomEvent('requestFilterSortMenu', {
                        detail: {
                            currentFilters: this.getCurrentFilters()
                        }
                    }));
                });
            }
            
            if (this.elements.createPlaylistBtn) {
                this.elements.createPlaylistBtn.addEventListener('click', () => {
                    // Emit request to show create playlist modal
                    window.dispatchEvent(new CustomEvent('requestCreatePlaylistModal', {
                        detail: {}
                    }));
                });
            }
        },
        
        /**
         * Setup gesture events for mobile/tablet
         */
        setupGestureEvents: function() {
            if (!window.DeviceManager) {
                setTimeout(() => this.setupGestureEvents(), 100);
                return;
            }
            
            if (!window.DeviceManager.hasTouchSupport()) {
                return;
            }
            
            // Swipe gestures for side panel
            this.setupSwipeGestures();
            
            // Long press gestures for context menus
            this.setupLongPressGestures();
        },
        
        /**
         * Setup swipe gestures for side panel
         */
        setupSwipeGestures: function() {
            let touchStartX = 0;
            let touchStartY = 0;
            
            document.addEventListener('touchstart', (e) => {
                if (e.touches.length === 1) {
                    touchStartX = e.touches[0].clientX;
                    touchStartY = e.touches[0].clientY;
                    this.gestures.touchStartX = touchStartX;
                    this.gestures.touchStartY = touchStartY;
                    this.gestures.touchStartTime = Date.now();
                }
            });
            
            document.addEventListener('touchend', (e) => {
                if (this.gestures.touchStartTime === 0) return;
                
                const touchEndX = e.changedTouches[0].clientX;
                const touchEndY = e.changedTouches[0].clientY;
                const deltaX = touchEndX - this.gestures.touchStartX;
                const deltaY = touchEndY - this.gestures.touchStartY;
                const deltaTime = Date.now() - this.gestures.touchStartTime;
                
                // Reset gesture tracking
                this.gestures.touchStartTime = 0;
                
                // Check for horizontal swipe
                if (Math.abs(deltaX) > 50 && Math.abs(deltaY) < 100 && deltaTime < 300) {
                    if (deltaX > 0 && touchStartX < 50) {
                        // Swipe right from left edge - open panel
                        this.openSidePanel();
                    } else if (deltaX < 0 && this.state.sidePanelOpen) {
                        // Swipe left when panel open - close panel
                        this.closeSidePanel();
                    }
                }
            });
        },
        
        /**
         * Setup long press gestures for context menus
         */
        setupLongPressGestures: function() {
            // This will be handled by ResponsiveModals module
            // But we can set up the basic gesture detection here
            document.addEventListener('touchstart', (e) => {
                const target = e.target.closest('.mobile-song-item');
                if (target) {
                    this.gestures.isGesturing = true;
                    
                    setTimeout(() => {
                        if (this.gestures.isGesturing && this.gestures.touchStartTime > 0) {
                            // Emit context menu request
                            const songId = target.dataset.songId;
                            if (songId) {
                                window.dispatchEvent(new CustomEvent('requestContextMenu', {
                                    detail: {
                                        songId: songId,
                                        target: target,
                                        isLongPress: true
                                    }
                                }));
                            }
                        }
                    }, 500);
                }
            });
            
            document.addEventListener('touchend', () => {
                this.gestures.isGesturing = false;
            });
        },
        
        /**
         * Setup responsive event listeners
         */
        setupResponsiveEvents: function() {
            // Listen for screen category changes
            window.addEventListener('screenCategoryChanged', (e) => {
                if (e.detail && e.detail.newCategory) {
                    this.adaptToScreenCategory(e.detail.newCategory);
                }
            });
            
            // Listen for device capability changes
            window.addEventListener('deviceCapabilitiesDetected', (e) => {
                if (e.detail && e.detail.deviceType) {
                    this.adaptToDeviceType(e.detail.deviceType);
                }
            });
            
            // Window resize handling
            const debouncedResize = window.Helpers.debounce(() => {
                this.handleResize();
            }, 250);
            
            window.addEventListener('resize', debouncedResize);
        },
        
        /**
         * Setup custom event listeners
         */
        setupCustomEventListeners: function() {
        // Listen for navigation requests
            window.addEventListener('requestNavigation', (e) => {
                if (e.detail) {
                    this.handleNavigationRequest(e.detail.action, e.detail.params);
                }
            });
            
            // Listen for state changes from StateManager
            window.addEventListener('musicStateChanged', (e) => {
                this.handleStateChange(e.detail.oldState, e.detail.newState);
            });
        },
        
        /**
         * Load initial navigation state
         */
        loadInitialState: function() {
            // Load from localStorage if available
            const savedState = localStorage.getItem('responsiveNavigationState');
            if (savedState) {
                try {
                    const parsedState = JSON.parse(savedState);
                    this.state = { ...this.state, ...parsedState };
                } catch (e) {
                    window.Helpers.log('ResponsiveNavigation: Failed to load saved state:', e);
                }
            }
            
            // Apply initial state
            this.updateUIFromState();
        },
        
        /**
         * Save current navigation state
         */
        saveState: function() {
            try {
                const stateToSave = {
                    activeTab: this.state.activeTab,
                    currentSort: this.state.currentSort,
                    sortDirection: this.state.sortDirection,
                    filteredGenres: this.state.filteredGenres
                };
                localStorage.setItem('responsiveNavigationState', JSON.stringify(stateToSave));
            } catch (e) {
                // Ignore storage errors
            }
        },
        
        /**
         * Toggle side panel
         */
        toggleSidePanel: function() {
            if (this.state.sidePanelOpen) {
                this.closeSidePanel();
            } else {
                this.openSidePanel();
            }
        },
        
        /**
         * Open side panel
         */
        openSidePanel: function() {
            if (this.state.sidePanelOpen) return;
            
            this.state.sidePanelOpen = true;
            
            // Update UI
            if (this.elements.sidePanel) {
                this.elements.sidePanel.classList.add('active');
            }
            if (this.elements.sidePanelOverlay) {
                this.elements.sidePanelOverlay.classList.add('active');
            }
            
            // Prevent body scroll on mobile
            if (window.DeviceManager && window.DeviceManager.shouldUseMobileUI()) {
                document.body.style.overflow = 'hidden';
            }
            
            // Emit event
            window.dispatchEvent(new CustomEvent('sidePanelStateChanged', {
                detail: {
                    open: true,
                    source: 'navigation'
                }
            }));
            
            window.Helpers.log('ResponsiveNavigation: Side panel opened');
        },
        
        /**
         * Close side panel
         */
        closeSidePanel: function() {
            if (!this.state.sidePanelOpen) return;
            
            this.state.sidePanelOpen = false;
            
            // Update UI
            if (this.elements.sidePanel) {
                this.elements.sidePanel.classList.remove('active');
            }
            if (this.elements.sidePanelOverlay) {
                this.elements.sidePanelOverlay.classList.remove('active');
            }
            
            // Restore body scroll
            document.body.style.overflow = '';
            
            // Emit event
            window.dispatchEvent(new CustomEvent('sidePanelStateChanged', {
                detail: {
                    open: false,
                    source: 'navigation'
                }
            }));
            
            window.Helpers.log('ResponsiveNavigation: Side panel closed');
        },
        
        /**
         * Switch to specific tab
         * @param {string} tabName - Tab name to switch to
         */
        switchTab: function(tabName) {
            if (this.state.activeTab === tabName) return;
            
            const oldTab = this.state.activeTab;
            this.state.activeTab = tabName;
            
            // Update tab button states
            if (this.elements.tabButtons) {
                this.elements.tabButtons.forEach(tab => {
                    tab.classList.remove('active');
                    if (tab.dataset.tab === tabName || tab.id === tabName + 'Tab') {
                        tab.classList.add('active');
                    }
                });
            }
            
            // Update tab content visibility
            this.updateTabContent(tabName);
            
            // Emit event for content loading
            window.dispatchEvent(new CustomEvent('tabChanged', {
                detail: {
                    oldTab: oldTab,
                    newTab: tabName,
                    source: 'navigation'
                }
            }));
            
            // Save state
            this.saveState();
            
            window.Helpers.log('ResponsiveNavigation: Switched to tab:', tabName);
        },
        
        /**
         * Update tab content visibility
         * @param {string} activeTab - Active tab name
         */
        updateTabContent: function(activeTab) {
            const contents = document.querySelectorAll('.mobile-tab-content');
            contents.forEach(content => {
                content.classList.remove('active');
            });
            
            const targetContent = document.getElementById('mobile' + 
                activeTab.charAt(0).toUpperCase() + activeTab.slice(1) + 'Content');
            if (targetContent) {
                targetContent.classList.add('active');
            }
        },
        
        /**
         * Handle search input
         * @param {string} query - Search query
         */
        handleSearch: function(query) {
            this.state.searchQuery = query;
            
            // Show/hide clear button
            if (this.elements.searchClear) {
                this.elements.searchClear.style.display = query ? 'block' : 'none';
            }
            
            // Debounced search
            clearTimeout(this.state.searchTimeout);
            this.state.searchTimeout = setTimeout(() => {
                this.performSearch(query);
            }, 500);
        },
        
        /**
         * Perform search with current filters
         * @param {string} query - Search query
         */
        performSearch: function(query) {
            // Emit search request
            window.dispatchEvent(new CustomEvent('requestSearch', {
                detail: {
                    query: query,
                    filters: this.getCurrentFilters(),
                    source: 'navigation'
                }
            }));
        },
        
        /**
         * Clear search
         */
        clearSearch: function() {
            this.state.searchQuery = '';
            
            if (this.elements.searchInput) {
                this.elements.searchInput.value = '';
            }
            if (this.elements.searchClear) {
                this.elements.searchClear.style.display = 'none';
            }
            
            clearTimeout(this.state.searchTimeout);
            this.performSearch('');
        },
        
        /**
         * Handle navigation requests
         * @param {string} action - Navigation action
         * @param {Object} params - Action parameters
         */
        handleNavigationRequest: function(action, params) {
            switch (action) {
                case 'openSidePanel':
                    this.openSidePanel();
                    break;
                case 'closeSidePanel':
                    this.closeSidePanel();
                    break;
                case 'toggleSidePanel':
                    this.toggleSidePanel();
                    break;
                case 'switchTab':
                    this.switchTab(params.tabName);
                    break;
                case 'search':
                    this.handleSearch(params.query);
                    break;
                case 'clearSearch':
                    this.clearSearch();
                    break;
                default:
                    window.Helpers.log('ResponsiveNavigation: Unknown navigation request:', action);
            }
        },
        
        /**
         * Handle state changes from StateManager
         * @param {Object} oldState - Previous state
         * @param {Object} newState - New state
         */
        handleStateChange: function(oldState, newState) {
            // React to relevant state changes
            if (oldState.currentSongId !== newState.currentSongId) {
                // Song changed - might need to update active tab content
                if (this.state.activeTab === 'history') {
                    this.switchTab('history'); // Refresh history
                }
            }
        },
        
        /**
         * Adapt to screen category changes
         * @param {string} screenCategory - New screen category
         */
        adaptToScreenCategory: function(screenCategory) {
            // Adjust navigation behavior based on screen size
            if (screenCategory === 'small') {
                // Mobile adjustments
                this.applyMobileAdjustments();
            } else if (screenCategory === 'large') {
                // Desktop adjustments
                this.applyDesktopAdjustments();
            } else {
                // Tablet adjustments
                this.applyTabletAdjustments();
            }
        },
        
        /**
         * Adapt to device type
         * @param {string} deviceType - Device type
         */
        adaptToDeviceType: function(deviceType) {
            if (deviceType === 'mobile') {
                this.applyMobileAdjustments();
            } else if (deviceType === 'desktop') {
                this.applyDesktopAdjustments();
            } else {
                this.applyTabletAdjustments();
            }
        },
        
        /**
         * Apply mobile-specific adjustments
         */
        applyMobileAdjustments: function() {
            // Close side panel on song selection (for better UX)
            window.addEventListener('songSelected', () => {
                if (this.state.sidePanelOpen) {
                    this.closeSidePanel();
                }
            });
        },
        
        /**
         * Apply desktop-specific adjustments
         */
        applyDesktopAdjustments: function() {
            // Enable hover states and keyboard navigation
            // May allow side panel to stay open on desktop
        },
        
        /**
         * Apply tablet-specific adjustments
         */
        applyTabletAdjustments: function() {
            // Hybrid approach between mobile and desktop
        },
        
        /**
         * Handle window resize
         */
        handleResize: function() {
            // Adjust navigation behavior if needed
            if (window.DeviceManager) {
                const newScreenCategory = window.DeviceManager.getScreenCategory();
                if (newScreenCategory !== this.state.lastScreenCategory) {
                    this.state.lastScreenCategory = newScreenCategory;
                    this.adaptToScreenCategory(newScreenCategory);
                }
            }
        },
        
        /**
         * Update UI from current state
         */
        updateUIFromState: function() {
            // Update tab buttons
            this.switchTab(this.state.activeTab);
            
            // Update search input
            if (this.elements.searchInput) {
                this.elements.searchInput.value = this.state.searchQuery;
            }
            
            // Update clear button
            if (this.elements.searchClear) {
                this.elements.searchClear.style.display = this.state.searchQuery ? 'block' : 'none';
            }
            
            // Update side panel state
            if (this.state.sidePanelOpen) {
                this.openSidePanel();
            }
        },
        
        /**
         * Get current navigation filters
         * @returns {Object} Current filters object
         */
        getCurrentFilters: function() {
            return {
                search: this.state.searchQuery,
                sortBy: this.state.currentSort,
                sortDirection: this.state.sortDirection,
                genres: [...this.state.filteredGenres]
            };
        },
        
        /**
         * Set navigation filters
         * @param {Object} filters - Filters object
         */
        setFilters: function(filters) {
            if (filters.search !== undefined) {
                this.state.searchQuery = filters.search;
            }
            if (filters.sortBy !== undefined) {
                this.state.currentSort = filters.sortBy;
            }
            if (filters.sortDirection !== undefined) {
                this.state.sortDirection = filters.sortDirection;
            }
            if (filters.genres !== undefined) {
                this.state.filteredGenres = [...filters.genres];
            }
            
            this.updateUIFromState();
            this.saveState();
        },
        
        /**
         * Get navigation state for debugging
         * @returns {Object} Current navigation state
         */
        getNavigationState: function() {
            return {
                ...this.state,
                hasTouchSupport: window.DeviceManager ? window.DeviceManager.hasTouchSupport() : false,
                deviceType: window.DeviceManager ? window.DeviceManager.getDeviceType() : 'unknown',
                screenCategory: window.DeviceManager ? window.DeviceManager.getScreenCategory() : 'unknown'
            };
        },
        
        /**
         * Reset navigation to default state
         */
        reset: function() {
            this.state = {
                sidePanelOpen: false,
                activeTab: 'playlists',
                searchQuery: '',
                searchTimeout: null,
                currentSort: 'dateAdded',
                sortDirection: 'desc',
                filteredGenres: []
            };
            
            this.updateUIFromState();
            this.saveState();
            
            window.Helpers.log('ResponsiveNavigation: Reset to default state');
        }
    };
    
    // Auto-initialize when dependencies are available
    if (window.Helpers && window.DeviceManager) {
        window.ResponsiveNavigation.init();
    } else {
        // Wait for dependencies
        const checkDeps = () => {
            if (window.Helpers && window.DeviceManager) {
                window.ResponsiveNavigation.init();
            } else {
                setTimeout(checkDeps, 50);
            }
        };
        setTimeout(checkDeps, 50);
    }
    
})(window);