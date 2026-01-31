// JMedia Mobile JavaScript - Mobile UI that uses existing musicBar.js functionality
class JMediaMobile {
    constructor() {
        this.isExpanded = false;
        this.expanding = false;
        this.currentSortBy = 'dateAdded';
        this.currentSortDirection = 'desc';
        this.init();
    }

    init() {
        this.setupElements();
        this.setupMobileEventListeners();
        this.loadInitialContent('', 'dateAdded', []);
        console.log('[JMediaMobile] Mobile UI initialized');
    }

    loadInitialContent(search = '', sortBy = 'dateAdded', genres = [], sortDirection = null) {
        // Load initial songs - profile ID resolved globally in apiPost()
        if (window.htmx) {
            // Use current sort direction if not provided
            const direction = sortDirection || this.currentSortDirection;
            
            // Build query parameters
            const params = new URLSearchParams({
                search: search,
                sortBy: sortBy,
                sortDirection: direction
            });
            
            // Add genre filters if any
            if (genres && genres.length > 0) {
                params.set('genres', genres.join(','));
            }
            
            const url = `/api/music/ui/mobile-tbody/${window.globalActiveProfileId || '1'}/0?${params.toString()}`;
            
            window.htmx.ajax('GET', url, {
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
        if (!this.navToggle)
            console.warn('Mobile nav toggle not found');
        if (!this.sidePanel)
            console.warn('Mobile side panel not found');
        if (!this.sidePanelOverlay)
            console.warn('Mobile side panel overlay not found');
        if (!this.sidePanelClose)
            console.warn('Mobile side panel close not found');
        if (!this.searchInput)
            console.warn('Mobile search input not found');
        if (!this.searchClear)
            console.warn('Mobile search clear not found');
        if (!this.songList)
            console.warn('Mobile song list not found');
        if (!this.tabButtons)
            console.warn('Mobile tab buttons not found');
        if (!this.expandBtn)
            console.warn('Expand button not found');
        if (!this.player)
            console.warn('Mobile player not found');
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

        // Sort direction toggle
        this.setupSortEventListeners();
 
        // Profile switch event - handled by global musicBar.js
        document.body.addEventListener('profileSwitched', () => {
            // Profile management handled globally
        });
    }

    setupSortEventListeners() {
        const sortDirectionToggle = document.getElementById('sortDirectionToggle');
        const sortOptions = document.querySelectorAll('input[name="sortBy"]');

        // Sort direction toggle
        if (sortDirectionToggle) {
            sortDirectionToggle.addEventListener('click', () => {
                this.toggleSortDirection();
            });
        }

        // Sort option changes
        sortOptions.forEach(option => {
            option.addEventListener('change', () => {
                if (option.checked) {
                    this.currentSortBy = option.value;
                    this.applySortWithCurrentDirection();
                }
            });
        });

        // Initialize sort direction display
        this.updateSortDirectionDisplay();
    }

    toggleSortDirection() {
        this.currentSortDirection = this.currentSortDirection === 'asc' ? 'desc' : 'asc';
        this.updateSortDirectionDisplay();
        this.applySortWithCurrentDirection();
    }

    updateSortDirectionDisplay() {
        const toggleBtn = document.getElementById('sortDirectionToggle');
        const icon = toggleBtn?.querySelector('.pi');
        const text = toggleBtn?.querySelector('.sort-direction-text');

        if (icon && text) {
            if (this.currentSortDirection === 'asc') {
                icon.className = 'pi pi-sort-up';
                text.textContent = 'Ascending';
            } else {
                icon.className = 'pi pi-sort-down';
                text.textContent = 'Descending';
            }
        }
    }

    applySortWithCurrentDirection() {
        const checkedSortOption = document.querySelector('input[name="sortBy"]:checked');
        const sortBy = checkedSortOption ? checkedSortOption.value : 'dateAdded';
        
        // Get current search term and genres
        const currentSearch = this.searchInput ? this.searchInput.value : '';
        let currentGenres = [];
        
        if (window.mobileFilterSortMenu) {
            const filters = window.mobileFilterSortMenu.getCurrentFilters();
            currentGenres = filters.genres;
        }

        this.loadInitialContent(currentSearch, sortBy, currentGenres, this.currentSortDirection);
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
        if (!historyContainer)
            return;

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

        // Use existing search endpoint via HTMX with current filters
        if (window.htmx) {
            clearTimeout(this.searchTimeout);
            this.searchTimeout = setTimeout(() => {
                // Get current filters from filter menu if available
                let currentSort = 'dateAdded';
                let currentGenres = [];
                
                if (window.mobileFilterSortMenu) {
                    const filters = window.mobileFilterSortMenu.getCurrentFilters();
                    currentSort = filters.sortBy;
                    currentGenres = filters.genres;
                } else {
                    // Fallback to selected radio button
                    const checkedSortOption = document.querySelector('input[name="sortBy"]:checked');
                    currentSort = checkedSortOption ? checkedSortOption.value : 'dateAdded';
                }

                this.loadInitialContent(query, currentSort, currentGenres, this.currentSortDirection);
                
                // Add click handlers to loaded songs (delayed to wait for HTMX completion)
                setTimeout(() => {
                    const songItems = document.querySelectorAll('.mobile-song-item');
                    songItems.forEach(item => {
                        item.addEventListener('click', (e) => {
                            // Check if context menu was just opened (prevent song selection)
                            if (window.mobileContextMenu && window.mobileContextMenu.menuJustOpened) {
                                e.preventDefault();
                                e.stopPropagation();
                                return;
                            }
                            
                            const songId = item.dataset.songId;
                            const profileId = window.globalActiveProfileId || '1';
                            if (songId && window.apiPost) {
                                window.apiPost(`select/${profileId}/${songId}`);
                            }
                        });
                    });
                }, 100);
            }, 500); // Same delay as desktop version
        }
    }

    clearMobileSearch() {
        if (this.searchInput) {
            this.searchInput.value = '';
            this.searchClear.style.display = 'none';
            
            // Get current filters and reload content with empty search
            let currentSort = 'dateAdded';
            let currentGenres = [];
            
            if (window.mobileFilterSortMenu) {
                const filters = window.mobileFilterSortMenu.getCurrentFilters();
                currentSort = filters.sortBy;
                currentGenres = filters.genres;
            } else {
                // Fallback to selected radio button
                const checkedSortOption = document.querySelector('input[name="sortBy"]:checked');
                currentSort = checkedSortOption ? checkedSortOption.value : 'dateAdded';
            }
            
            this.loadInitialContent('', currentSort, currentGenres, this.currentSortDirection);
        }
    }

    // Player expansion methods
    togglePlayerExpansion() {
        if (this.expanding)
            return;

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

    createPlaylistFromAddDialog() {
        const modal = document.querySelector('.mobile-modal[data-song-id]');
        const nameInput = modal?.querySelector('#newPlaylistName');
        const playlistName = nameInput?.value.trim();
        if (!playlistName) {
            if (window.showToast) window.showToast('Playlist name cannot be empty', 'error');
            return;
        }
        const profileId = window.globalActiveProfileId || '1';

        fetch('/api/music/playlists/', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: playlistName, profileId })
        })
        .then(res => {
            if (!res.ok) throw new Error(`HTTP error! status: ${res.status}`);
            return res.json();
        })
        .then(data => {
            const newPlaylist = data.data || data;
            const newId = newPlaylist.id;
            if (!newId) throw new Error('Playlist creation succeeded but no ID returned');
            const modal = document.querySelector('.mobile-modal[data-song-id]');
            const songId = modal?.dataset?.songId;
            if (!songId) throw new Error('Song ID missing from modal when adding new playlist');
            return this.addSongToPlaylistHandler(newId, songId);
        })
        .then(() => {
            if (window.showToast) window.showToast('Playlist created and song added', 'success');
            const modal = document.querySelector('.mobile-modal[data-song-id]');
            if (modal) modal.remove();
            const nameInput = document.getElementById('newPlaylistName');
            if (nameInput) nameInput.value = '';
        })
        .catch(err => {
            console.error(err);
            if (err.message.includes('Song ID missing')) {
                if (window.showToast) window.showToast('UI error: Please try again', 'error');
            } else {
                if (window.showToast) window.showToast('Failed to create playlist', 'error');
            }
        });
    }

}

// Initialize mobile enhancements using global initialization system
document.addEventListener('DOMContentLoaded', () => {
    // Wait for global initialization and musicBar.js to complete
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

// Mobile Context Menu Implementation
class MobileContextMenu {
    constructor() {
        this.activeElement = null;
        this.timerId = null;
        this.duration = 500; // 500ms long press
        this.init();
    }

    init() {
        this.setupEventListeners();
        console.log('[MobileContextMenu] Context menu initialized');
    }

    setupEventListeners() {
        // Touch events for song items
        document.body.addEventListener('touchstart', this.handleTouchStart.bind(this), {passive: false});
        ['touchend', 'touchmove', 'touchcancel'].forEach(event => {
            document.body.addEventListener(event, this.cancelTouch.bind(this), {passive: false});
        });

        // Mouse events for desktop right-click support
        document.body.addEventListener('contextmenu', this.handleContextMenu.bind(this));

        // Global click to close (desktop)
        document.body.addEventListener('click', this.handleOutsideClick.bind(this));
        
        // Only handle touch events on backdrop for mobile (not global)
        const backdrop = document.querySelector('.mobile-context-backdrop');
        if (backdrop) {
            backdrop.addEventListener('touchend', this.hideMenu.bind(this), {passive: true});
        }

        // Menu item clicks - use direct binding to menu list
        const menu = document.getElementById('mobileContextMenu');
        if (menu) {
            const menuList = menu.querySelector('.mobile-context-list');
            if (menuList) {
                // Direct binding to menu list for better event capture
                menuList.addEventListener('click', this.handleMenuClick.bind(this));
                menuList.addEventListener('touchend', this.handleMenuClick.bind(this), {passive: true});
            }

            // Prevent menu clicks from bubbling to document
            menu.addEventListener('click', (e) => {
                e.stopPropagation();
            });
            menu.addEventListener('touchend', (e) => {
                e.stopPropagation();
            }, {passive: true});

            // Backdrop click to close
            const backdrop = menu.querySelector('.mobile-context-backdrop');
            if (backdrop) {
                backdrop.addEventListener('click', this.hideMenu.bind(this));
                backdrop.addEventListener('touchend', this.hideMenu.bind(this), {passive: true});
            }
        }

        // Escape key to close
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                this.hideMenu();
            }
        });
    }

    handleTouchStart(e) {
        // Only consider primary touch
        if (e.touches && e.touches.length > 1)
            return;

        const target = e.target.closest('.mobile-song-item');
        if (!target)
            return;

        this.activeElement = target;
        const songId = target.dataset.songId;

        // Add visual feedback
        target.classList.add('long-press-active');

        // Start timer for long press
        this.timerId = setTimeout(() => {
            // Prevent the touch event from bubbling to song click handlers
            e.preventDefault();
            e.stopPropagation();
            this.showContextMenu(songId);
        }, this.duration);
    }

    handleContextMenu(e) {
        // Prevent default browser context menu
        e.preventDefault();

        const target = e.target.closest('.mobile-song-item');
        if (!target)
            return;

        const songId = target.dataset.songId;
        if (!songId)
            return;

        // Store mouse position for desktop positioning
        this.mousePosition = {
            x: e.clientX,
            y: e.clientY
        };

        // Show context menu at mouse position
        this.showContextMenu(songId, true);
    }

    handleOutsideClick(e) {
        const menu = document.getElementById('mobileContextMenu');
        if (!menu || menu.getAttribute('aria-hidden') === 'true') {
            return;
        }

        // Check if click is outside the menu
        if (!menu.contains(e.target)) {
            this.hideMenu();
        }
    }

    

    cancelTouch(e) {
        if (this.timerId) {
            clearTimeout(this.timerId);
            this.timerId = null;
        }

        // Remove visual feedback
        if (this.activeElement) {
            this.activeElement.classList.remove('long-press-active');
        }

        // If menu was just opened, prevent the touchend from triggering song click
        if (this.menuJustOpened && e) {
            e.preventDefault();
            e.stopPropagation();
        }
    }

    showContextMenu(songId, isDesktop = false) {
        const menu = document.getElementById('mobileContextMenu');
        if (!menu)
            return;

        // Store song ID on menu
        menu.dataset.songId = songId;
        menu.setAttribute('aria-hidden', 'false');

        // Add desktop class for positioning if needed
        if (isDesktop) {
            menu.classList.add('desktop-context');
            this.positionMenuAtCursor(menu);
        } else {
            menu.classList.remove('desktop-context');
        }

        // Ensure backdrop is ready for interaction
        const backdrop = menu.querySelector('.mobile-context-backdrop');
        if (backdrop) {
            backdrop.style.pointerEvents = 'auto';
        }

        // For mobile, prevent the touch from propagating to underlying elements
        if (!isDesktop && this.activeElement) {
            // Add a flag to prevent song click handler
            this.menuJustOpened = true;
            setTimeout(() => {
                this.menuJustOpened = false;
            }, 100);
        }

        console.log('[MobileContextMenu] Menu shown for song ID:', songId, 'Desktop:', isDesktop);
    }

    positionMenuAtCursor(menu) {
        if (!this.mousePosition) return;

        // Reset any positioning
        menu.style.position = 'fixed';
        menu.style.left = 'auto';
        menu.style.top = 'auto';
        menu.style.transform = 'none';

        // Get menu dimensions
        const menuRect = menu.getBoundingClientRect();
        const menuWidth = menuRect.width || 200; // fallback width
        const menuHeight = menuRect.height || 250; // fallback height

        // Calculate position
        let x = this.mousePosition.x;
        let y = this.mousePosition.y;

        // Adjust if menu would go off screen
        if (x + menuWidth > window.innerWidth) {
            x = window.innerWidth - menuWidth - 10;
        }
        if (y + menuHeight > window.innerHeight) {
            y = window.innerHeight - menuHeight - 10;
        }
        if (x < 10) x = 10;
        if (y < 10) y = 10;

        // Apply positioning
        menu.style.left = x + 'px';
        menu.style.top = y + 'px';
    }

    hideMenu() {
        const menu = document.getElementById('mobileContextMenu');
        if (!menu)
            return;

        menu.setAttribute('aria-hidden', 'true');
        menu.classList.remove('desktop-context');
        delete menu.dataset.songId;

        // Reset desktop positioning
        menu.style.position = '';
        menu.style.left = '';
        menu.style.top = '';
        menu.style.transform = '';

        // Disable backdrop pointer events when hidden
        const backdrop = menu.querySelector('.mobile-context-backdrop');
        if (backdrop) {
            backdrop.style.pointerEvents = 'none';
        }

        // Remove visual feedback
        if (this.activeElement) {
            this.activeElement.classList.remove('long-press-active');
            this.activeElement = null;
        }

        // Clear mouse position
        this.mousePosition = null;

        console.log('[MobileContextMenu] Menu hidden');
    }

    handleMenuClick(e) {
        const li = e.target.closest('li');
        if (!li)
            return;

        const action = li.dataset.action;
        const songId = parseInt(document.getElementById('mobileContextMenu').dataset.songId, 10);

        if (!songId || isNaN(songId)) {
            console.warn('[MobileContextMenu] No valid song ID found');
            return;
        }

        console.log('[MobileContextMenu] Action:', action, 'Song ID:', songId);

        // Dispatch to existing musicBar functions
        switch (action) {
            case 'queue':
                // Use REST API to match desktop behavior
if (window.htmx) {
                    htmx.ajax('POST', `/api/music/queue/add/${window.globalActiveProfileId}/${songId}`, {
                        handler: function() {
                            console.log(`Song ${songId} added to queue.`);
                            if (window.showToast) {
                                window.showToast('Song added to queue', 'success');
                            }
                            // Emit queue change event for UI updates
                            window.dispatchEvent(new CustomEvent('queueChanged', {
                                detail: {
                                    queueSize: window.musicState?.cue?.length || 0,
                                    queueChanged: true,
                                    queueLengthChanged: true
                                }
                            }));
                        }
                    });
                }
                break;
            case 'playlist':
                this.openPlaylistSubmenu(songId);
                break;
            case 'rescan':
                if (rescanSong) {
                    rescanSong(songId);
                } else {
                    console.warn('[MobileContextMenu] rescanSong function not available');
                }
                break;
            case 'update':
                this.updateMetadata(songId);
                break;
            case 'delete':
                if (deleteSong) {
                    deleteSong(songId);
                } else {
                    console.warn('[MobileContextMenu] deleteSong function not available');
                }
                break;
            default:
                console.warn('[MobileContextMenu] Unknown action:', action);
                break;
        }

        this.hideMenu();
    }

    updateMetadata(songId) {
        // Update metadata functionality - trigger metadata refresh
        console.log('[MobileContextMenu] Updating metadata for song ID:', songId);

        // Send a request to refresh metadata for this song
        fetch(`/api/metadata/enrich/${songId}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        })
                .then(response => {
                    if (!response.ok) {
                        throw new Error(`HTTP error! status: ${response.status}`);
                    }
                    return response.json();
                })
                .then(data => {
                    console.log('[MobileContextMenu] Metadata updated successfully:', data);
                    if (showToast) {
                        showToast('Metadata updated successfully', 'success');
                    }
                    // Reload the song list to reflect changes
                    if (window.jmediaMobile && window.jmediaMobile.loadInitialContent) {
                        window.jmediaMobile.loadInitialContent();
                    }
                })
                .catch(error => {
                    console.error('[MobileContextMenu] Error updating metadata:', error);
                    if (showToast) {
                        showToast('Failed to update metadata', 'error');
                    }
                });
    }

    openPlaylistSubmenu(songId) {
        // Open mobile-friendly playlist selection
        console.log('[MobileContextMenu] Opening playlist submenu for song ID:', songId);

        // Fetch playlists for the current profile
        fetch(`/api/music/playlists/${window.globalActiveProfileId}`)
                .then(response => response.json())
                .then(data => {
                    const playlists = data.data || data;
                    this.showPlaylistSelection(playlists, songId);
                })
                .catch(error => {
                    console.error('[MobileContextMenu] Error fetching playlists:', error);
                    if (showToast) {
                        showToast('Failed to load playlists', 'error');
                    }
                });
    }

    showPlaylistSelection(playlists, songId) {
        // Create a simple modal for playlist selection
        const modal = document.createElement('div');
        modal.dataset.songId = songId;
        modal.innerHTML = `
            <div class="mobile-modal-overlay" onclick="this.parentElement.remove()"></div>
            <div class="mobile-modal-card">
                <header class="mobile-modal-header">
                    <h3 class="mobile-modal-title">Add to Playlist</h3>
                    <button class="mobile-modal-close" onclick="this.closest('.mobile-modal').remove()">
                        <i class="pi pi-times"></i>
                    </button>
                </header>
                <div class="mobile-modal-body">
                    ${playlists.length===0?`
                    <div class="mobile-empty-state" style="text-align:center; padding:20px; margin-bottom:15px;">
                        <i class="pi pi-folder-open" style="font-size:48px;color:#ccc;"></i>
                        <p>No playlists found</p>
                        <p style="font-size:12px;color:#999;">Create your first playlist to get started</p>
                    </div>
                    `:`
                    <div class="mobile-playlist-selection" style="margin-bottom:15px;">
                        ${playlists.map(playlist => `
                            <div class="mobile-playlist-option" data-playlist-id="${playlist.id}">
                                <div class="mobile-playlist-info">
                                    <div class="mobile-playlist-name">${playlist.name || 'Untitled Playlist'}</div>
                                    <div class="mobile-playlist-details">${playlist.songCount || 0} songs</div>
                                </div>
                                <i class="pi pi-plus"></i>
                            </div>
                        `).join('')}
                    </div>
                    `}
                    <div class="create-playlist-form" style="margin-top:10px;">
                        <div class="mobile-form-group">
                            <label class="mobile-label">Create New Playlist</label>
                            <div style="display: flex; gap: 8px;">
                                <input type="text" class="mobile-input" id="newPlaylistName" placeholder="Enter playlist name..." style="flex: 1;">
                                <button class="mobile-btn-primary" onclick="window.jmediaMobile.createPlaylistFromAddDialog()" style="white-space: nowrap;">
                                    <i class="pi pi-plus"></i> Create
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        `;

                modal.querySelectorAll('.mobile-playlist-option').forEach(option => {
            option.addEventListener('click', () => {
                const playlistId = option.dataset.playlistId;
                this.addSongToPlaylistHandler(playlistId, songId);
                modal.remove();
            });
        });

        // Add CSS for playlist selection if not already present
        if (!document.querySelector('#playlist-selection-styles')) {
            const style = document.createElement('style');
            style.id = 'playlist-selection-styles';
            style.textContent = `
                .mobile-playlist-selection {
                    max-height: 300px;
                    overflow-y: auto;
                }
                .mobile-playlist-option {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    padding: 12px 16px;
                    border-radius: 8px;
                    cursor: pointer;
                    transition: background-color 0.2s ease;
                }
                .mobile-playlist-option:hover {
                    background: var(--mobile-primary);
                    color: white;
                }
            `;
            document.head.appendChild(style);
        }

        document.body.appendChild(modal);
        modal.classList.add('mobile-modal', 'is-active');
    }

    addSongToPlaylistHandler(playlistId, songId) {
        console.log('[MobileContextMenu] Adding song to playlist:', playlistId, songId);

        // Use existing addSongToPlaylist function from musicBar.js
        if (typeof addSongToPlaylist === 'function') {
            addSongToPlaylist(playlistId, songId)
                    .then(() => {
                        console.log('[MobileContextMenu] Song added to playlist successfully');
                        if (showToast) {
                            showToast('Song added to playlist', 'success');
                        }
                    })
                    .catch(error => {
                        console.error('[MobileContextMenu] Error adding song to playlist:', error);
                        if (showToast) {
                            showToast('Failed to add song to playlist', 'error');
                        }
                    });
        } else {
            console.error('[MobileContextMenu] addSongToPlaylist function not available');
            if (showToast) {
                showToast('Add to playlist not available', 'error');
            }
        }
    }
}

// Cover Image Long Press Extension
class CoverImageContextMenu {
    constructor(contextMenu) {
        this.contextMenu = contextMenu;
        this.timerId = null;
        this.duration = 500;
        this.init();
    }

    init() {
        const coverContainer = document.getElementById('songCoverImageContainer');
        if (coverContainer) {
            coverContainer.addEventListener('touchstart', this.handleTouchStart.bind(this), {passive: true});
            ['touchend', 'touchmove', 'touchcancel'].forEach(event => {
                coverContainer.addEventListener(event, this.cancelTouch.bind(this), {passive: true});
            });
            // Add right-click support for desktop
            coverContainer.addEventListener('contextmenu', this.handleContextMenu.bind(this));
            console.log('[CoverImageContextMenu] Cover image long press and right-click initialized');
        }
    }

    handleTouchStart(e) {
        e.preventDefault();

        // Add visual feedback
        const coverContainer = document.getElementById('songCoverImageContainer');
        if (coverContainer) {
            coverContainer.classList.add('long-press-active');
        }

        this.timerId = setTimeout(() => {
            const songId = musicState?.currentSongId;
            if (songId) {
                this.contextMenu.showContextMenu(songId);
            } else {
                if (showToast) {
                    showToast('No song currently playing', 'info');
                }
            }
        }, this.duration);
    }

    handleContextMenu(e) {
        e.preventDefault();

        const songId = musicState?.currentSongId;
        if (!songId) {
            if (showToast) {
                showToast('No song currently playing', 'info');
            }
            return;
        }

        // Store mouse position for desktop positioning
        this.contextMenu.mousePosition = {
            x: e.clientX,
            y: e.clientY
        };

        // Show context menu at mouse position
        this.contextMenu.showContextMenu(songId, true);
    }

    cancelTouch() {
        if (this.timerId) {
            clearTimeout(this.timerId);
            this.timerId = null;
        }

        // Remove visual feedback
        const coverContainer = document.getElementById('songCoverImageContainer');
        if (coverContainer) {
            coverContainer.classList.remove('long-press-active');
        }
    }
}

// Mobile Filter/Sort Menu Implementation
class MobileFilterSortMenu {
    constructor() {
        this.isVisible = false;
        this.genres = [];
        this.filteredGenres = [];
        this.currentSort = 'dateAdded';
        this.init();
    }

    init() {
        this.setupEventListeners();
        this.loadGenres();
        console.log('[MobileFilterSortMenu] Filter/sort menu initialized');
    }

    setupEventListeners() {
        // Filter button click
        const filterBtn = document.getElementById('mobileFilterSort');
        if (filterBtn) {
            filterBtn.addEventListener('click', this.showMenu.bind(this));
        }

        // Close button click
        const closeBtn = document.querySelector('.mobile-filter-close');
        if (closeBtn) {
            closeBtn.addEventListener('click', this.hideMenu.bind(this));
        }

        // Backdrop click
        const backdrop = document.querySelector('.mobile-filter-backdrop');
        if (backdrop) {
            backdrop.addEventListener('click', this.hideMenu.bind(this));
        }

        // Apply filters button
        const applyBtn = document.getElementById('applyFilters');
        if (applyBtn) {
            applyBtn.addEventListener('click', this.applyFilters.bind(this));
        }

        // Reset filters button
        const resetBtn = document.getElementById('resetFilters');
        if (resetBtn) {
            resetBtn.addEventListener('click', this.resetFilters.bind(this));
        }

        // Show more genres button
        const showMoreBtn = document.getElementById('showMoreGenres');
        if (showMoreBtn) {
            showMoreBtn.addEventListener('click', this.showMoreGenres.bind(this));
        }

        // Escape key to close
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape' && this.isVisible) {
                this.hideMenu();
            }
        });
    }

    async loadGenres() {
        try {
            const response = await fetch('/api/music/ui/genres');
            if (response.ok) {
                this.genres = await response.json();
                this.populateGenreOptions();
            } else {
                console.warn('[MobileFilterSortMenu] Failed to load genres');
            }
        } catch (error) {
            console.error('[MobileFilterSortMenu] Error loading genres:', error);
        }
    }

    populateGenreOptions() {
        const container = document.getElementById('genreFilterOptions');
        if (!container) return;

        // Clear existing genre options (keep "All Genres")
        const allGenresLabel = container.querySelector('label');
        container.innerHTML = '';
        if (allGenresLabel) {
            container.appendChild(allGenresLabel);
        }

        // Add genre options (show first 10 initially)
        const genresToShow = this.genres.slice(0, 10);
        genresToShow.forEach(genre => {
            const label = document.createElement('label');
            label.innerHTML = `
                <input type="checkbox" name="genre" value="${genre}"> ${genre}
            `;
            container.appendChild(label);
        });

        // Show "Show More" button if there are more genres
        if (this.genres.length > 10) {
            const showMoreBtn = document.getElementById('showMoreGenres');
            if (showMoreBtn) {
                showMoreBtn.style.display = 'block';
                showMoreBtn.textContent = `Show More (${this.genres.length - 10} more)`;
            }
        }
    }

    showMoreGenres() {
        const container = document.getElementById('genreFilterOptions');
        const showMoreBtn = document.getElementById('showMoreGenres');
        if (!container || !showMoreBtn) return;

        // Add remaining genres
        const remainingGenres = this.genres.slice(10);
        remainingGenres.forEach(genre => {
            const label = document.createElement('label');
            label.innerHTML = `
                <input type="checkbox" name="genre" value="${genre}"> ${genre}
            `;
            container.appendChild(label);
        });

        // Hide show more button
        showMoreBtn.style.display = 'none';
    }

    showMenu() {
        const menu = document.getElementById('mobileFilterSortMenu');
        if (!menu) return;

        menu.setAttribute('aria-hidden', 'false');
        this.isVisible = true;
        document.body.style.overflow = 'hidden';

        // Load current filter state
        this.loadCurrentState();
    }

    hideMenu() {
        const menu = document.getElementById('mobileFilterSortMenu');
        if (!menu) return;

        menu.setAttribute('aria-hidden', 'true');
        this.isVisible = false;
        document.body.style.overflow = '';
    }

    loadCurrentState() {
        // Load current sort selection
        const sortRadios = document.querySelectorAll('input[name="sortBy"]');
        sortRadios.forEach(radio => {
            radio.checked = radio.value === this.currentSort;
        });

        // Load current genre selections
        const genreCheckboxes = document.querySelectorAll('input[name="genre"]');
        genreCheckboxes.forEach(checkbox => {
            if (checkbox.value === '') {
                // "All Genres" checkbox
                checkbox.checked = this.filteredGenres.length === 0;
            } else {
                checkbox.checked = this.filteredGenres.includes(checkbox.value);
            }
        });
    }

    applyFilters() {
        // Get selected sort
        const selectedSort = document.querySelector('input[name="sortBy"]:checked');
        if (selectedSort) {
            this.currentSort = selectedSort.value;
        }

        // Get selected genres
        const selectedGenreCheckboxes = document.querySelectorAll('input[name="genre"]:checked');
        this.filteredGenres = Array.from(selectedGenreCheckboxes)
            .map(cb => cb.value)
            .filter(value => value !== ''); // Remove "All Genres"

        // Update filter button appearance
        this.updateFilterButton();

        // Apply filters to song list
        this.applyToSongList();

        this.hideMenu();
    }

    resetFilters() {
        this.currentSort = 'dateAdded';
        this.filteredGenres = [];
        
        // Update UI
        this.loadCurrentState();
        this.updateFilterButton();
        this.applyToSongList();
        
        this.hideMenu();
    }

    updateFilterButton() {
        const filterBtn = document.getElementById('mobileFilterSort');
        if (!filterBtn) return;

        const hasActiveFilters = this.filteredGenres.length > 0 || this.currentSort !== 'dateAdded';
        
        if (hasActiveFilters) {
            filterBtn.classList.add('active');
            // Add a small indicator badge
            if (!filterBtn.querySelector('.filter-badge')) {
                const badge = document.createElement('span');
                badge.className = 'filter-badge';
                badge.textContent = '●';
                badge.style.cssText = `
                    position: absolute;
                    top: -2px;
                    right: -2px;
                    width: 8px;
                    height: 8px;
                    background: var(--mobile-primary);
                    border-radius: 50%;
                    font-size: 8px;
                `;
                filterBtn.style.position = 'relative';
                filterBtn.appendChild(badge);
            }
        } else {
            filterBtn.classList.remove('active');
            const badge = filterBtn.querySelector('.filter-badge');
            if (badge) {
                badge.remove();
            }
        }
    }

    applyToSongList() {
        // Trigger song list reload with new filters
        if (window.jmediaMobile && window.jmediaMobile.loadInitialContent) {
            // Update the search parameters
            const searchInput = document.getElementById('mobileSearch');
            const searchValue = searchInput ? searchInput.value : '';
            
            window.jmediaMobile.loadInitialContent(searchValue, this.currentSort, this.filteredGenres);
        }
    }

    

    getCurrentFilters() {
        return {
            sortBy: this.currentSort,
            genres: [...this.filteredGenres]
        };
    }
}

// Initialize context menu after DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    // Wait a bit for other components to initialize
    setTimeout(() => {
        if (document.getElementById('mobileContextMenu')) {
            window.mobileContextMenu = new MobileContextMenu();
            window.coverImageContextMenu = new CoverImageContextMenu(window.mobileContextMenu);
            console.log('[MobileContextMenu] All context menu features initialized');
        } else {
            console.warn('[MobileContextMenu] Context menu element not found');
        }

        // Initialize filter/sort menu
        if (document.getElementById('mobileFilterSortMenu')) {
            window.mobileFilterSortMenu = new MobileFilterSortMenu();
            console.log('[MobileFilterSortMenu] Filter/sort menu initialized');
        } else {
            console.warn('[MobileFilterSortMenu] Filter/sort menu element not found');
        }
    }, 1000);
});