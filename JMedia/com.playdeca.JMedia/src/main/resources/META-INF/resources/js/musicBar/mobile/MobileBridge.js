/**
 * Mobile Bridge - Compatibility layer for migrating mobile.js to new architecture
 * Provides backward compatibility while using new responsive modules
 */
(function(window) {
    'use strict';
    
    window.MobileBridge = {
        // Bridge state tracking
        initialized: false,
        legacyMode: false,
        
        // Legacy mobile.js instance (for gradual migration)
        legacyMobile: null,
        
        /**
         * Initialize mobile bridge
         */
        init: function() {
            if (this.initialized) return;
            
            // Initialize in correct order
            this.detectMode();
            this.setupBridge();
            this.initializeResponsiveModules();
            
            this.initialized = true;
            window.Helpers.log(`MobileBridge initialized in ${this.legacyMode ? 'legacy' : 'responsive'} mode`);
        },
        
        /**
         * Detect if we should use legacy mode or responsive mode
         */
        detectMode: function() {
            // Check for flag to force legacy mode
            const forceLegacy = localStorage.getItem('forceLegacyMobile') === 'true';
            
            // Check if responsive modules are available
            const hasResponsiveModules = window.ResponsiveNavigation && 
                                     window.ResponsiveModals && 
                                     window.ResponsivePlayer &&
                                     window.MobileAdapter &&
                                     window.TabletAdapter &&
                                     window.DesktopAdapter;
            
            this.legacyMode = forceLegacy || !hasResponsiveModules;
        },
        
        /**
         * Setup bridge layer
         */
        setupBridge: function() {
            if (this.legacyMode) {
                this.setupLegacyBridge();
            } else {
                this.setupResponsiveBridge();
            }
        },
        
        /**
         * Setup legacy bridge (keep old mobile.js)
         */
        setupLegacyBridge: function() {
            // Keep existing mobile.js behavior
            if (window.jmediaMobile) {
                this.legacyMobile = window.jmediaMobile;
            }
            
            // Add migration提示
            if (!localStorage.getItem('legacyModeNoticeShown')) {
                if (window.showToast) {
                    window.showToast('Using legacy mobile mode. New responsive features are available.', 'info', 5000);
                }
                localStorage.setItem('legacyModeNoticeShown', 'true');
            }
        },
        
        /**
         * Setup responsive bridge (map old API to new modules)
         */
        setupResponsiveBridge: function() {
            // Create compatibility layer that maps old mobile.js API to new responsive modules
            this.createCompatibilityLayer();
            
            // Initialize responsive modules if they exist
            this.initializeResponsiveModules();
        },
        
        /**
         * Create compatibility layer
         */
        createCompatibilityLayer: function() {
            // Create JMediaMobile compatibility object
            window.jmediaMobile = {
                // Navigation methods
                openSidePanel: () => {
                    if (window.ResponsiveNavigation) {
                        window.ResponsiveNavigation.openSidePanel();
                    }
                },
                
                closeSidePanel: () => {
                    if (window.ResponsiveNavigation) {
                        window.ResponsiveNavigation.closeSidePanel();
                    }
                },
                
                switchMobileTab: (tabName) => {
                    if (window.ResponsiveNavigation) {
                        window.ResponsiveNavigation.switchTab(tabName);
                    }
                },
                
                handleMobileSearch: (query) => {
                    if (window.ResponsiveNavigation) {
                        window.ResponsiveNavigation.handleSearch(query);
                    }
                },
                
                clearMobileSearch: () => {
                    if (window.ResponsiveNavigation) {
                        window.ResponsiveNavigation.clearSearch();
                    }
                },
                
                // Player methods
                togglePlayerExpansion: () => {
                    if (window.ResponsivePlayer) {
                        window.ResponsivePlayer.togglePlayerExpansion();
                    }
                },
                
                expandPlayer: () => {
                    if (window.ResponsivePlayer) {
                        window.ResponsivePlayer.expandPlayer();
                    }
                },
                
                collapsePlayer: () => {
                    if (window.ResponsivePlayer) {
                        window.ResponsivePlayer.collapsePlayer();
                    }
                },
                
                // Content loading methods
                loadInitialContent: (search, sortBy, genres, sortDirection) => {
                    // Map to new responsive search system
                    if (window.ResponsiveNavigation) {
                        const filters = {
                            search: search || '',
                            sortBy: sortBy || 'dateAdded',
                            sortDirection: sortDirection || 'desc',
                            genres: genres || []
                        };
                        
                        // Set filters and trigger search
                        window.ResponsiveNavigation.setFilters(filters);
                        window.ResponsiveNavigation.performSearch(search || '');
                    }
                },
                
                loadMobilePlaylists: () => {
                    // Emit playlist load request
                    window.dispatchEvent(new CustomEvent('requestLoadPlaylists', {}));
                },
                
                loadMobileQueue: () => {
                    // Emit queue load request
                    window.dispatchEvent(new CustomEvent('requestLoadQueue', {}));
                },
                
                loadMobileHistory: () => {
                    // Emit history load request
                    window.dispatchEvent(new CustomEvent('requestLoadHistory', {}));
                },
                
                // State methods
                getCurrentTab: () => {
                    if (window.StateManager) {
                        return window.StateManager.getProperty('activeTab') || 'playlists';
                    }
                    return 'playlists';
                },
                
                isExpanded: () => {
                    if (window.StateManager) {
                        return window.StateManager.getProperty('playerExpanded') || false;
                    }
                    return false;
                }
            };
            
            // Add the old JMediaMobile class methods for compatibility
            this.extendCompatibilityObject();
        },
        
        /**
         * Extend compatibility object with additional methods
         */
        extendCompatibilityObject: function() {
            const jmediaMobile = window.jmediaMobile;
            
            // Add methods that weren't in original but might be expected
            jmediaMobile.getState = () => {
                return {
                    isExpanded: jmediaMobile.isExpanded(),
                    currentTab: jmediaMobile.getCurrentTab(),
                    searchQuery: window.StateManager?.getProperty('searchQuery') || '',
                    sortBy: window.StateManager?.getProperty('currentSort') || 'dateAdded',
                    sortDirection: window.StateManager?.getProperty('sortDirection') || 'desc',
                    filteredGenres: window.StateManager?.getProperty('filteredGenres') || [],
                    sidePanelOpen: window.StateManager?.getProperty('sidePanelOpen') || false
                };
            };
            
            jmediaMobile.forceDesktop = () => {
                window.DesktopAdapter?.init();
            };
            
            jmediaMobile.forceMobile = () => {
                window.MobileAdapter?.init();
            };
            
            jmediaMobile.forceTablet = () => {
                window.TabletAdapter?.init();
            };
            
            // Add event compatibility
            this.setupEventCompatibility(jmediaMobile);
        },
        
        /**
         * Setup event compatibility
         */
        setupEventCompatibility: function(jmediaMobile) {
            // Map old-style events to new CustomEvent system
            const originalAddEventListener = jmediaMobile.addEventListener || function() {};
            
            jmediaMobile.addEventListener = function(event, callback) {
                // Try to map old event names to new CustomEvent requests
                switch (event) {
                    case 'tabChanged':
                        // Listen for new tab change events
                        window.addEventListener('tabChanged', (e) => {
                            callback({
                                oldTab: e.detail.oldTab,
                                newTab: e.detail.newTab
                            });
                        });
                        break;
                        
                    case 'playerExpanded':
                        // Listen for player expansion changes
                        window.addEventListener('playerExpansionChanged', (e) => {
                            callback({
                                expanded: e.detail.expanded
                            });
                        });
                        break;
                        
                    case 'searchPerformed':
                        // Listen for search events
                        window.addEventListener('searchPerformed', (e) => {
                            callback({
                                query: e.detail.query,
                                results: e.detail.results
                            });
                        });
                        break;
                        
                    default:
                        // Fall back to original event listener
                        originalAddEventListener.call(this, event, callback);
                }
            };
        },
        
        /**
         * Initialize responsive modules
         */
        initializeResponsiveModules: function() {
            if (this.legacyMode) return;
            
            // Modules should auto-initialize, but ensure they're ready
            const modules = [
                { name: 'ResponsiveNavigation', module: window.ResponsiveNavigation },
                { name: 'ResponsiveModals', module: window.ResponsiveModals },
                { name: 'ResponsivePlayer', module: window.ResponsivePlayer },
                { name: 'MobileAdapter', module: window.MobileAdapter },
                { name: 'TabletAdapter', module: window.TabletAdapter },
                { name: 'DesktopAdapter', module: window.DesktopAdapter }
            ];
            
            modules.forEach(({ name, module }) => {
                if (module) {
                    window.Helpers.log(`MobileBridge: ${name} available`);
                } else {
                    window.Helpers.log(`MobileBridge: ${name} not available`);
                }
            });
            
            // Setup module coordination
            this.setupModuleCoordination();
        },
        
        /**
         * Setup coordination between responsive modules
         */
        setupModuleCoordination: function() {
            // Ensure modules work together properly
            window.addEventListener('deviceCapabilitiesDetected', (e) => {
                // Notify all modules about device changes
                const deviceType = e.detail.deviceType;
                
                // Auto-select appropriate adapter
                switch (deviceType) {
                    case 'mobile':
                        if (window.MobileAdapter) window.MobileAdapter.init();
                        break;
                    case 'tablet':
                        if (window.TabletAdapter) window.TabletAdapter.init();
                        break;
                    case 'desktop':
                        if (window.DesktopAdapter) window.DesktopAdapter.init();
                        break;
                }
            });
            
            // Coordinate state between modules
            this.setupStateCoordination();
            
            // Coordinate events between modules
            this.setupEventCoordination();
        },
        
        /**
         * Setup state coordination between modules
         */
        setupStateCoordination: function() {
            // Sync state changes from StateManager to responsive modules
            if (window.StateManager) {
                window.addEventListener('statePropertyChanged', (e) => {
                    const { property, newValue } = e.detail;
                    
                    // Update relevant responsive modules
                    switch (property) {
                        case 'sidePanelOpen':
                            if (window.ResponsiveNavigation) {
                                if (newValue) {
                                    window.ResponsiveNavigation.openSidePanel();
                                } else {
                                    window.ResponsiveNavigation.closeSidePanel();
                                }
                            }
                            break;
                            
                        case 'activeTab':
                            if (window.ResponsiveNavigation) {
                                window.ResponsiveNavigation.switchTab(newValue);
                            }
                            break;
                            
                        case 'playerExpanded':
                            if (window.ResponsivePlayer) {
                                if (newValue) {
                                    window.ResponsivePlayer.expandPlayer();
                                } else {
                                    window.ResponsivePlayer.collapsePlayer();
                                }
                            }
                            break;
                            
                        case 'searchQuery':
                            if (window.ResponsiveNavigation) {
                                window.ResponsiveNavigation.handleSearch(newValue);
                            }
                            break;
                    }
                });
            }
        },
        
        /**
         * Setup event coordination between modules
         */
        setupEventCoordination: function() {
            // Route CustomEvent requests to appropriate modules
            window.addEventListener('requestNavigation', (e) => {
                if (window.ResponsiveNavigation) {
                    window.ResponsiveNavigation.handleNavigationRequest(e.detail.action, e.detail.params);
                }
            });
            
            window.addEventListener('requestPlaybackControl', (e) => {
                if (window.PlaybackController) {
                    window.PlaybackController.handleControlRequest(e.detail);
                }
            });
            
            window.addEventListener('requestVolumeChange', (e) => {
                if (window.VolumeController) {
                    window.VolumeController.handleVolumeChange(e.detail.volume, e.detail.source);
                }
            });
            
            window.addEventListener('requestContextMenu', (e) => {
                if (window.ResponsiveModals) {
                    window.ResponsiveModals.showContextMenu(
                        e.detail.songId, 
                        e.detail.target, 
                        e.detail.isLongPress, 
                        e.detail.isDesktop
                    );
                }
            });
            
            window.addEventListener('requestFilterSortMenu', (e) => {
                if (window.ResponsiveModals) {
                    window.ResponsiveModals.showFilterMenu(e.detail.currentFilters);
                }
            });
            
            window.addEventListener('requestProfileModal', () => {
                if (window.ResponsiveModals) {
                    window.ResponsiveModals.showProfileModal();
                }
            });
            
            window.addEventListener('requestCreatePlaylistModal', (e) => {
                if (window.ResponsiveModals) {
                    window.ResponsiveModals.showCreatePlaylistModal(e.detail);
                }
            });
            
            // Handle content loading requests
            window.addEventListener('requestLoadPlaylists', () => {
                if (window.htmx) {
                    window.htmx.ajax('GET', `/api/music/ui/playlists-fragment/${window.globalActiveProfileId || '1'}`, {
                        target: document.getElementById('mobilePlaylistContent'),
                        swap: 'innerHTML'
                    });
                }
            });
            
            window.addEventListener('requestLoadQueue', () => {
                if (window.htmx) {
                    window.htmx.ajax('GET', `/api/music/ui/queue-fragment/${window.globalActiveProfileId || '1'}`, {
                        target: document.getElementById('mobileQueueContent'),
                        swap: 'innerHTML'
                    });
                }
            });
            
            window.addEventListener('requestLoadHistory', () => {
                if (window.htmx) {
                    window.htmx.ajax('GET', `/api/music/ui/history-fragment/${window.globalActiveProfileId || '1'}`, {
                        target: document.getElementById('mobileHistoryContent'),
                        swap: 'innerHTML'
                    });
                }
            });
        },
        
        /**
         * Get bridge state
         */
        getBridgeState: function() {
            return {
                initialized: this.initialized,
                legacyMode: this.legacyMode,
                hasResponsiveModules: !!(window.ResponsiveNavigation && 
                                      window.ResponsiveModals && 
                                      window.ResponsivePlayer),
                deviceType: window.DeviceManager?.getDeviceType() || 'unknown',
                screenCategory: window.DeviceManager?.getScreenCategory() || 'unknown'
            };
        },
        
        /**
         * Force responsive mode
         */
        forceResponsiveMode: function() {
            localStorage.removeItem('forceLegacyMobile');
            window.location.reload();
        },
        
        /**
         * Force legacy mode
         */
        forceLegacyMode: function() {
            localStorage.setItem('forceLegacyMobile', 'true');
            window.location.reload();
        },
        
        /**
         * Get available modules
         */
        getAvailableModules: function() {
            return {
                responsiveNavigation: !!window.ResponsiveNavigation,
                responsiveModals: !!window.ResponsiveModals,
                responsivePlayer: !!window.ResponsivePlayer,
                mobileAdapter: !!window.MobileAdapter,
                tabletAdapter: !!window.TabletAdapter,
                desktopAdapter: !!window.DesktopAdapter
            };
        }
    };
    
    // Auto-initialize
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => window.MobileBridge.init());
    } else {
        window.MobileBridge.init();
    }
    
})(window);