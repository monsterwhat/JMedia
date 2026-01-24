// JMedia Mobile JavaScript - Mobile UI that uses existing musicBar.js functionality
class JMediaMobile {
    constructor() {
        this.isExpanded = false;
        this.expanding = false;
        this.init();
    }
    
    init() {
        this.setupElements();
        this.setupMobileEventListeners();
        this.loadInitialContent();
        console.log('[JMediaMobile] Mobile UI initialized');
    }
    
    loadInitialContent() {
        // Load initial songs - profile ID resolved globally in apiPost()
        if (window.htmx) {
            window.htmx.ajax('GET', `/api/music/ui/mobile-tbody/${window.globalActiveProfileId || '1'}/0`, {
                target: document.getElementById('mobileSongList'),
                swap: 'innerHTML'
            });
        }
    }
    
    setupElements() {
        // Mobile navigation elements
        this.navToggle = document.getElementById('mobileNavToggle');
        this.sidePanel = document.getElementById('mobileSidePanel');
        this.sidePanelOverlay = document.getElementById('mobileSidePanelOverlay');
        this.sidePanelClose = document.getElementById('mobileSidePanelClose');
        
        // Mobile search elements
        this.searchInput = document.getElementById('mobileSearch');
        this.searchClear = document.getElementById('mobileSearchClear');
        
        // Mobile content areas
        this.songList = document.getElementById('mobileSongList');
        this.tabButtons = document.querySelectorAll('.mobile-tab');
        
        // Expand button
        this.expandBtn = document.getElementById('expandPlayerBtn');
        this.player = document.querySelector('.mobile-player');
        
        // Add null checks for safety
        if (!this.navToggle) console.warn('Mobile nav toggle not found');
        if (!this.sidePanel) console.warn('Mobile side panel not found');
        if (!this.sidePanelOverlay) console.warn('Mobile side panel overlay not found');
        if (!this.sidePanelClose) console.warn('Mobile side panel close not found');
        if (!this.searchInput) console.warn('Mobile search input not found');
        if (!this.searchClear) console.warn('Mobile search clear not found');
        if (!this.songList) console.warn('Mobile song list not found');
        if (!this.tabButtons) console.warn('Mobile tab buttons not found');
        if (!this.expandBtn) console.warn('Expand button not found');
        if (!this.player) console.warn('Mobile player not found');
    }
    
    setupMobileEventListeners() {
        // Navigation - mobile specific
        if (this.navToggle) {
            this.navToggle.addEventListener('click', () => this.openSidePanel());
        }
        
        if (this.sidePanelClose) {
            this.sidePanelClose.addEventListener('click', () => this.closeSidePanel());
        }
        
        if (this.sidePanelOverlay) {
            this.sidePanelOverlay.addEventListener('click', () => this.closeSidePanel());
        }
        
        // Tab switching - mobile specific
        if (this.tabButtons) {
            this.tabButtons.forEach(tab => {
                tab.addEventListener('click', () => {
                    const tabName = tab.dataset.tab || tab.id.replace('Tab', '');
                    this.switchMobileTab(tabName);
                });
            });
        }
        
        // Search - mobile specific
        if (this.searchInput) {
            this.searchInput.addEventListener('input', (e) => this.handleMobileSearch(e.target.value));
        }
        
        if (this.searchClear) {
            this.searchClear.addEventListener('click', () => this.clearMobileSearch());
        }
        
        // Expand button
        if (this.expandBtn) {
            this.expandBtn.addEventListener('click', () => this.togglePlayerExpansion());
        }
        
        // Mobile player controls are handled by global bindPlaybackButtons() in musicBar.js
        
        // Profile switch event - handled by global musicBar.js
        document.body.addEventListener('profileSwitched', () => {
            // Profile management handled globally
        });
    }
    
    // Navigation methods
    openSidePanel() {
        if (this.sidePanel && this.sidePanelOverlay) {
            this.sidePanel.classList.add('active');
            this.sidePanelOverlay.classList.add('active');
            document.body.style.overflow = 'hidden';
        }
    }
    
    closeSidePanel() {
        if (this.sidePanel && this.sidePanelOverlay) {
            this.sidePanel.classList.remove('active');
            this.sidePanelOverlay.classList.remove('active');
            document.body.style.overflow = '';
        }
    }
    
    switchMobileTab(tabName) {
        // Update tab buttons
        this.tabButtons.forEach(tab => {
            tab.classList.remove('active');
            if (tab.dataset.tab === tabName || tab.id === tabName + 'Tab') {
                tab.classList.add('active');
            }
        });
        
        // Update tab contents
        const tabContents = document.querySelectorAll('.mobile-tab-content');
        tabContents.forEach(content => {
            content.classList.remove('active');
        });
        
        const targetContent = document.getElementById('mobile' + tabName.charAt(0).toUpperCase() + tabName.slice(1) + 'Content');
        if (targetContent) {
            targetContent.classList.add('active');
        }
        
        // Load content using existing HTMX endpoints
        if (tabName === 'playlists') {
            this.loadMobilePlaylists();
        } else if (tabName === 'queue') {
            this.loadMobileQueue();
        } else if (tabName === 'history') {
            this.loadMobileHistory();
        }
    }
    
    // Content loading methods using mobile-specific HTMX endpoints
    loadMobilePlaylists() {
        if (window.htmx) {
            window.htmx.ajax('GET', `/api/music/ui/mobile-playlists-fragment/${window.globalActiveProfileId || '1'}`, {
                target: document.getElementById('mobilePlaylistContent'),
                swap: 'innerHTML'
            });
        }
    }
    
    loadMobileQueue() {
        if (window.htmx) {
            window.htmx.ajax('GET', `/api/music/ui/mobile-queue-fragment/${window.globalActiveProfileId || '1'}`, {
                target: document.getElementById('mobileQueueContent'),
                swap: 'innerHTML'
            });
        }
    }
    
    loadMobileHistory() {
        if (window.htmx) {
            window.htmx.ajax('GET', `/api/music/ui/mobile-history-fragment/${window.globalActiveProfileId || '1'}`, {
                target: document.getElementById('mobileHistoryContent'),
                swap: 'innerHTML'
            });
        }
    }
    
    renderMobileHistory(history) {
        const historyContainer = document.getElementById('mobileHistoryContent');
        if (!historyContainer) return;
        
        if (history.length === 0) {
            historyContainer.innerHTML = `
                <div class="mobile-empty">
                    <div class="mobile-empty-icon">🕐</div>
                    <div class="mobile-empty-title">No history</div>
                    <div class="mobile-empty-text">Start playing music to build your history</div>
                </div>
            `;
            return;
        }
        
        const historyHTML = history.map(song => `
            <div class="mobile-song-item" data-song-id="${song.id}">
                <div class="mobile-song-artwork">
                    ${song.coverArt ? `<img src="${song.coverArt}" alt="${song.title}" style="width: 100%; height: 100%; object-fit: cover; border-radius: 4px;">` : '🎵'}
                </div>
                <div class="mobile-song-info">
                    <div class="mobile-song-title">${song.title || 'Unknown Title'}</div>
                    <div class="mobile-song-artist">${song.artist || 'Unknown Artist'}</div>
                </div>
            </div>
        `).join('');
        
        historyContainer.innerHTML = historyHTML;
        
        // Add click handlers to history items
        historyContainer.querySelectorAll('.mobile-song-item').forEach(item => {
            item.addEventListener('click', () => {
                const songId = item.dataset.songId;
                if (songId && window.apiPost) {
                    window.apiPost(`select/${window.globalActiveProfileId || '1'}/${songId}`);
                }
            });
        });
    }
    
    // Search methods using existing search functionality
    handleMobileSearch(query) {
        if (this.searchClear) {
            this.searchClear.style.display = query ? 'block' : 'none';
        }
        
        // Use existing search endpoint via HTMX
        if (window.htmx) {
            clearTimeout(this.searchTimeout);
            this.searchTimeout = setTimeout(() => {
                const profileId = window.globalActiveProfileId || '1';
                const url = query 
                    ? `/api/music/ui/mobile-tbody/${profileId}/0?search=${encodeURIComponent(query)}`
                    : `/api/music/ui/mobile-tbody/${profileId}/0`;
                
                window.htmx.ajax('GET', url, {
                    target: document.getElementById('mobileSongList'),
                    swap: 'innerHTML',
                    values: query ? { search: query } : {}
                }).then(() => {
                        // Add click handlers to loaded songs
                        const songItems = document.querySelectorAll('.mobile-song-item');
                        songItems.forEach(item => {
                            item.addEventListener('click', () => {
                                const songId = item.dataset.songId;
                                if (songId && window.apiPost) {
                                    window.apiPost(`select/${profileId}/${songId}`);
                                }
                            });
                        });
                    });
                }
            , 500); // Same delay as desktop version
        }
    }
    
    clearMobileSearch() {
        if (this.searchInput) {
            this.searchInput.value = '';
            this.searchClear.style.display = 'none';
            if (window.htmx) {
                const profileId = window.globalActiveProfileId || '1';
                window.htmx.ajax('GET', `/api/music/ui/mobile-tbody/${profileId}/0`, {
                    target: document.getElementById('mobileSongList'),
                    swap: 'innerHTML'
                });
            }
        }
    }
    
    // Player expansion methods
    togglePlayerExpansion() {
        if (this.expanding) return;
        
        if (this.isExpanded) {
            this.collapsePlayer();
        } else {
            this.expandPlayer();
        }
    }
    
    expandPlayer() {
        this.expanding = true;
        
        // Add expanded class to player
        this.player.classList.add('expanded');
        this.isExpanded = true;
        
        // Update expand button icon
        this.updateExpandButtonIcon('pi-chevron-down');
        
        // Disable scrolling on body
        document.body.style.overflow = 'hidden';
        
        // Add keyboard support
        this.setupExpandedKeyboardListeners();
        
        setTimeout(() => {
            this.expanding = false;
        }, 300);
    }
    
    collapsePlayer() {
        this.expanding = true;
        
        // Add collapsing class for animation
        this.player.classList.add('collapsing');
        
        // Update expand button icon
        this.updateExpandButtonIcon('pi-expand-up');
        
        // Remove expanded class after animation
        setTimeout(() => {
            this.player.classList.remove('expanded', 'collapsing');
            this.isExpanded = false;
            
            // Re-enable scrolling
            document.body.style.overflow = '';
            
            // Remove keyboard listeners
            this.removeExpandedKeyboardListeners();
            
            this.expanding = false;
        }, 200);
    }
    
    updateExpandButtonIcon(iconClass) {
        const icon = this.expandBtn.querySelector('i');
        if (icon) {
            icon.className = iconClass;
        }
    }
    
    setupExpandedKeyboardListeners() {
        document.addEventListener('keydown', this.handleExpandedKeydown);
    }
    
    removeExpandedKeyboardListeners() {
        document.removeEventListener('keydown', this.handleExpandedKeydown);
    }
    
    handleExpandedKeydown = (event) => {
        if (event.key === 'Escape') {
            this.collapsePlayer();
        }
    }


}

// Initialize mobile enhancements using global initialization system
document.addEventListener('DOMContentLoaded', () => {
    // Wait for global initialization to complete
    if (window.initManager) {
        window.initManager.start().then(() => {
            window.jmediaMobile = new JMediaMobile();
            console.log('[JMediaMobile] Mobile enhancements initialized');
        });
    } else {
        // Fallback for older initialization
        setTimeout(() => {
            window.jmediaMobile = new JMediaMobile();
            console.log('[JMediaMobile] Mobile enhancements initialized');
        }, 500);
    }
});

// Handle device orientation changes
window.addEventListener('orientationchange', () => {
    setTimeout(() => {
        window.scrollTo(0, 0);
    }, 100);
});