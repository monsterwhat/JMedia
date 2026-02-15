/**
 * ResponsiveModals - Unified modal and context menu system
 * Handles context menus, filter menus, profile modals, and other overlays
 */
(function(window) {
    'use strict';
    
    window.ResponsiveModals = {
        // Modal elements cache
        elements: {
            contextMenu: null,
            contextBackdrop: null,
            filterSortMenu: null,
            filterBackdrop: null,
            profileModal: null,
            playlistModal: null
        },
        
        // Modal state tracking
        state: {
            activeModal: null,
            contextMenuVisible: false,
            filterMenuVisible: false,
            profileModalVisible: false,
            contextMenuSongId: null,
            contextMenuPosition: null,
            isTouchDevice: false
        },
        
        // Gesture tracking
        gestures: {
            longPressTimer: null,
            touchStartX: 0,
            touchStartY: 0,
            isLongPress: false
        },
        
        /**
         * Initialize responsive modals system
         */
        init: function() {
            this.setupElements();
            this.setupEventListeners();
            this.detectTouchSupport();
            window.Helpers.log('ResponsiveModals initialized');
        },
        
        /**
         * Setup modal elements
         */
        setupElements: function() {
            const elements = this.elements;
            
            // Context menu elements
            elements.contextMenu = document.getElementById('mobileContextMenu');
            elements.contextBackdrop = elements.contextMenu?.querySelector('.mobile-context-backdrop');
            
            // Filter menu elements
            elements.filterSortMenu = document.getElementById('mobileFilterSortMenu');
            elements.filterBackdrop = elements.filterSortMenu?.querySelector('.mobile-filter-backdrop');
            
            // Profile modal
            elements.profileModal = document.getElementById('mobileProfileModal');
            
            window.Helpers.log('ResponsiveModals: Elements setup completed');
        },
        
        /**
         * Setup event listeners
         */
        setupEventListeners: function() {
            this.setupContextMenuEvents();
            this.setupFilterMenuEvents();
            this.setupProfileModalEvents();
            this.setupGlobalEvents();
            this.setupCustomEventListeners();
            
            window.Helpers.log('ResponsiveModals: Event listeners configured');
        },
        
        /**
         * Setup context menu events
         */
        setupContextMenuEvents: function() {
            const menu = this.elements.contextMenu;
            if (!menu) return;
            
            // Touch events for mobile
            document.addEventListener('touchstart', this.handleContextMenuTouchStart.bind(this), {passive: false});
            document.addEventListener('touchend', this.handleContextMenuTouchEnd.bind(this), {passive: false});
            document.addEventListener('touchmove', this.handleContextMenuTouchMove.bind(this), {passive: false});
            
            // Mouse events for desktop
            document.addEventListener('contextmenu', this.handleContextMenuRightClick.bind(this));
            
            // Menu item clicks
            const menuList = menu.querySelector('.mobile-context-list');
            if (menuList) {
                menuList.addEventListener('click', this.handleContextMenuAction.bind(this));
                menuList.addEventListener('touchend', this.handleContextMenuAction.bind(this), {passive: true});
            }
            
            // Prevent menu clicks from bubbling
            menu.addEventListener('click', (e) => e.stopPropagation());
            menu.addEventListener('touchend', (e) => e.stopPropagation(), {passive: true});
            
            // Backdrop click to close
            if (this.elements.contextBackdrop) {
                this.elements.contextBackdrop.addEventListener('click', this.hideContextMenu.bind(this));
                this.elements.contextBackdrop.addEventListener('touchend', this.hideContextMenu.bind(this), {passive: true});
            }
        },
        
        /**
         * Setup filter menu events
         */
        setupFilterMenuEvents: function() {
            const menu = this.elements.filterSortMenu;
            if (!menu) return;
            
            // Close button
            const closeBtn = menu.querySelector('.mobile-filter-close');
            if (closeBtn) {
                closeBtn.addEventListener('click', this.hideFilterMenu.bind(this));
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
            
            // Sort direction toggle
            const sortDirectionToggle = document.getElementById('sortDirectionToggle');
            if (sortDirectionToggle) {
                sortDirectionToggle.addEventListener('click', this.toggleSortDirection.bind(this));
            }
            
            // Show more genres
            const showMoreBtn = document.getElementById('showMoreGenres');
            if (showMoreBtn) {
                showMoreBtn.addEventListener('click', this.showMoreGenres.bind(this));
            }
            
            // Backdrop click to close
            if (this.elements.filterBackdrop) {
                this.elements.filterBackdrop.addEventListener('click', this.hideFilterMenu.bind(this));
            }
        },
        
        /**
         * Setup profile modal events
         */
        setupProfileModalEvents: function() {
            const modal = this.elements.profileModal;
            if (!modal) return;
            
            // Close button
            const closeBtn = modal.querySelector('.delete');
            if (closeBtn) {
                closeBtn.addEventListener('click', this.hideProfileModal.bind(this));
            }
            
            // Overlay click to close
            const overlay = modal.querySelector('.modal-background');
            if (overlay) {
                overlay.addEventListener('click', this.hideProfileModal.bind(this));
            }
            
            // Create profile button
            const createBtn = document.getElementById('mobileCreateProfileBtn');
            if (createBtn) {
                createBtn.addEventListener('click', this.handleCreateProfile.bind(this));
            }
            
            // Delete profile button
            const deleteBtn = document.getElementById('mobileDeleteCurrentProfileBtn');
            if (deleteBtn) {
                deleteBtn.addEventListener('click', this.handleDeleteProfile.bind(this));
            }
            
            // Profile input enter key
            const input = document.getElementById('mobileNewProfileNameInput');
            if (input) {
                input.addEventListener('keypress', (e) => {
                    if (e.key === 'Enter') {
                        this.handleCreateProfile();
                    }
                });
            }
        },
        
        /**
         * Setup global events
         */
        setupGlobalEvents: function() {
            // Global click to close context menus
            document.addEventListener('click', (e) => {
                if (this.state.contextMenuVisible && !this.elements.contextMenu.contains(e.target)) {
                    this.hideContextMenu();
                }
            });
            
            // Escape key to close modals
            document.addEventListener('keydown', (e) => {
                if (e.key === 'Escape') {
                    if (this.state.contextMenuVisible) {
                        this.hideContextMenu();
                    } else if (this.state.filterMenuVisible) {
                        this.hideFilterMenu();
                    } else if (this.state.profileModalVisible) {
                        this.hideProfileModal();
                    }
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
            // Listen for context menu requests
            window.addEventListener('requestContextMenu', (e) => {
                if (e.detail) {
                    this.showContextMenu(e.detail.songId, e.detail.target, e.detail.isLongPress);
                }
            });
            
            // Listen for filter menu requests
            window.addEventListener('requestFilterSortMenu', (e) => {
                if (e.detail) {
                    this.showFilterMenu(e.detail.currentFilters);
                }
            });
            
            // Listen for profile modal requests
            window.addEventListener('requestProfileModal', () => {
                this.showProfileModal();
            });
            
            // Listen for create playlist modal requests
            window.addEventListener('requestCreatePlaylistModal', (e) => {
                if (e.detail) {
                    this.showCreatePlaylistModal(e.detail);
                }
            });
            
            // Listen for filter menu requests
            window.addEventListener('requestFilterSortMenu', (e) => {
                this.showFilterMenu(e.detail.currentFilters);
            });
            
            // Listen for profile modal requests
            window.addEventListener('requestProfileModal', () => {
                this.showProfileModal();
            });
            
            // Listen for create playlist modal requests
            window.addEventListener('requestCreatePlaylistModal', (e) => {
                this.showCreatePlaylistModal(e.detail);
            });
        },
        
        /**
         * Detect touch support
         */
        detectTouchSupport: function() {
            this.state.isTouchDevice = window.DeviceManager ? 
                window.DeviceManager.hasTouchSupport() : 
                ('ontouchstart' in window);
        },
        
        /**
         * Handle context menu touch start
         */
        handleContextMenuTouchStart: function(e) {
            // Only handle primary touch
            if (e.touches.length > 1) return;
            
            const target = e.target.closest('.mobile-song-item');
            if (!target) return;
            
            this.gestures.touchStartX = e.touches[0].clientX;
            this.gestures.touchStartY = e.touches[0].clientY;
            this.gestures.isLongPress = false;
            
            // Start long press timer
            this.gestures.longPressTimer = setTimeout(() => {
                this.gestures.isLongPress = true;
                const songId = target.dataset.songId;
                if (songId) {
                    this.showContextMenu(songId, target, true, mousePosition);
                }
            }, 500);
            
            // Add visual feedback
            target.classList.add('long-press-active');
        },
        
        /**
         * Handle context menu touch end
         */
        handleContextMenuTouchEnd: function(e) {
            clearTimeout(this.gestures.longPressTimer);
            
            // Remove visual feedback
            const target = e.target.closest('.mobile-song-item');
            if (target) {
                target.classList.remove('long-press-active');
            }
            
            // If this was a long press, prevent default song click
            if (this.gestures.isLongPress) {
                e.preventDefault();
                e.stopPropagation();
                setTimeout(() => {
                    this.gestures.isLongPress = false;
                }, 100);
            }
        },
        
        /**
         * Handle context menu touch move
         */
        handleContextMenuTouchMove: function(e) {
            // Cancel long press if finger moves too much
            const deltaX = Math.abs(e.touches[0].clientX - this.gestures.touchStartX);
            const deltaY = Math.abs(e.touches[0].clientY - this.gestures.touchStartY);
            
            if (deltaX > 10 || deltaY > 10) {
                clearTimeout(this.gestures.longPressTimer);
                
                // Remove visual feedback
                const target = e.target.closest('.mobile-song-item');
                if (target) {
                    target.classList.remove('long-press-active');
                }
            }
        },
        
        /**
         * Handle context menu right click
         */
        handleContextMenuRightClick: function(e) {
            e.preventDefault();
            
            const target = e.target.closest('.mobile-song-item');
            if (!target) return;
            
            const songId = target.dataset.songId;
            if (!songId) return;
            
            this.state.contextMenuPosition = {
                x: e.clientX,
                y: e.clientY
            };
            
            this.showContextMenu(songId, target, false, true);
        },
        
        /**
         * Handle context menu action
         */
        handleContextMenuAction: function(e) {
            const li = e.target.closest('li');
            if (!li) return;
            
            const action = li.dataset.action;
            const songId = parseInt(this.elements.contextMenu.dataset.songId, 10);
            
            if (!songId || isNaN(songId)) {
                window.Helpers.log('ResponsiveModals: No valid song ID found');
                return;
            }
            
            this.executeContextAction(action, songId);
            this.hideContextMenu();
        },
        
        /**
         * Execute context menu action
         */
        executeContextAction: function(action, songId) {
            window.Helpers.log('ResponsiveModals: Executing action:', action, 'for song:', songId);
            
            switch (action) {
                case 'queue':
                    this.addToQueue(songId);
                    break;
                case 'playlist':
                    this.showPlaylistSelection(songId);
                    break;
                case 'rescan':
                    this.rescanSong(songId);
                    break;
                case 'update':
                    this.updateMetadata(songId);
                    break;
                case 'delete':
                    this.deleteSong(songId);
                    break;
                default:
                    window.Helpers.log('ResponsiveModals: Unknown context action:', action);
            }
        },
        
        /**
         * Show context menu
         */
        showContextMenu: function(songId, target, isLongPress = false, isDesktop = false, mousePosition = null) {
            const menu = this.elements.contextMenu;
            if (!menu) return;
            
            this.state.contextMenuSongId = songId;
            this.state.contextMenuVisible = true;
            
            // Store song ID on menu
            menu.dataset.songId = songId;
            menu.setAttribute('aria-hidden', 'false');
            
            if (isDesktop && this.state.contextMenuPosition) {
                // Desktop positioning
                menu.classList.add('desktop-context');
                this.positionContextMenu(menu);
            } else {
                // Mobile centering
                menu.classList.remove('desktop-context');
            }
            
            // Enable backdrop interactions
            if (this.elements.contextBackdrop) {
                this.elements.contextBackdrop.style.pointerEvents = 'auto';
            }
            
            // Prevent song click for mobile long press
            if (!isDesktop && isLongPress) {
                setTimeout(() => {
                    this.state.menuJustOpened = true;
                    setTimeout(() => {
                        this.state.menuJustOpened = false;
                    }, 100);
                }, 0);
            }
        },
        
        /**
         * Position context menu for desktop
         */
        positionContextMenu: function(menu) {
            if (!this.state.contextMenuPosition) return;
            
            const pos = this.state.contextMenuPosition;
            const menuRect = menu.getBoundingClientRect();
            const viewportWidth = window.innerWidth;
            const viewportHeight = window.innerHeight;
            
            let x = pos.x;
            let y = pos.y;
            
            // Adjust horizontal position
            if (x + menuRect.width > viewportWidth) {
                x = viewportWidth - menuRect.width - 10;
            }
            if (x < 10) {
                x = 10;
            }
            
            // Adjust vertical position
            if (y + menuRect.height > viewportHeight) {
                y = viewportHeight - menuRect.height - 10;
            }
            if (y < 10) {
                y = 10;
            }
            
            menu.style.position = 'fixed';
            menu.style.left = x + 'px';
            menu.style.top = y + 'px';
        },
        
        /**
         * Hide context menu
         */
        hideContextMenu: function() {
            const menu = this.elements.contextMenu;
            if (!menu || !this.state.contextMenuVisible) return;
            
            this.state.contextMenuVisible = false;
            this.state.contextMenuSongId = null;
            this.state.contextMenuPosition = null;
            
            menu.setAttribute('aria-hidden', 'true');
            menu.classList.remove('desktop-context');
            delete menu.dataset.songId;
            
            // Reset positioning
            menu.style.position = '';
            menu.style.left = '';
            menu.style.top = '';
            
            // Disable backdrop
            if (this.elements.contextBackdrop) {
                this.elements.contextBackdrop.style.pointerEvents = 'none';
            }
            
            // Remove any active states
            document.querySelectorAll('.long-press-active').forEach(el => {
                el.classList.remove('long-press-active');
            });
        },
        
        /**
         * Show filter/sort menu
         */
        showFilterMenu: function(currentFilters = {}) {
            const menu = this.elements.filterSortMenu;
            if (!menu) return;
            
            this.state.filterMenuVisible = true;
            this.state.activeModal = 'filter';
            
            menu.setAttribute('aria-hidden', 'false');
            document.body.style.overflow = 'hidden';
            
            // Load current filter state
            this.loadFilterState(currentFilters);
            
            // Load genres if needed
            this.loadGenres();
        },
        
        /**
         * Hide filter/sort menu
         */
        hideFilterMenu: function() {
            const menu = this.elements.filterSortMenu;
            if (!menu || !this.state.filterMenuVisible) return;
            
            this.state.filterMenuVisible = false;
            this.state.activeModal = null;
            
            menu.setAttribute('aria-hidden', 'true');
            document.body.style.overflow = '';
        },
        
        /**
         * Load filter state into UI
         */
        loadFilterState: function(filters) {
            // Load sort selection
            const sortRadios = document.querySelectorAll('input[name="sortBy"]');
            sortRadios.forEach(radio => {
                radio.checked = radio.value === (filters.sortBy || 'dateAdded');
            });
            
            // Load sort direction
            this.updateSortDirectionDisplay(filters.sortDirection || 'desc');
            
            // Load genre selections
            const genreCheckboxes = document.querySelectorAll('input[name="genre"]');
            const selectedGenres = filters.genres || [];
            
            genreCheckboxes.forEach(checkbox => {
                if (checkbox.value === '') {
                    // "All Genres" checkbox
                    checkbox.checked = selectedGenres.length === 0;
                } else {
                    checkbox.checked = selectedGenres.includes(checkbox.value);
                }
            });
        },
        
        /**
         * Load genres for filter menu
         */
        loadGenres: function() {
            const container = document.getElementById('genreFilterOptions');
            if (!container || container.dataset.loaded === 'true') return;
            
            fetch('/api/music/ui/genres')
                .then(response => response.json())
                .then(genres => {
                    this.populateGenreOptions(genres);
                    container.dataset.loaded = 'true';
                })
                .catch(error => {
                    window.Helpers.log('ResponsiveModals: Failed to load genres:', error);
                });
        },
        
        /**
         * Populate genre options
         */
        populateGenreOptions: function(genres) {
            const container = document.getElementById('genreFilterOptions');
            if (!container) return;
            
            // Keep "All Genres" label
            const allGenresLabel = container.querySelector('label');
            container.innerHTML = '';
            if (allGenresLabel) {
                container.appendChild(allGenresLabel);
            }
            
            // Add genre options (first 10 initially)
            const genresToShow = genres.slice(0, 10);
            genresToShow.forEach(genre => {
                const label = document.createElement('label');
                label.innerHTML = `<input type="checkbox" name="genre" value="${genre}"> ${genre}`;
                container.appendChild(label);
            });
            
            // Show "Show More" if there are more genres
            if (genres.length > 10) {
                const showMoreBtn = document.getElementById('showMoreGenres');
                if (showMoreBtn) {
                    showMoreBtn.style.display = 'block';
                    showMoreBtn.textContent = `Show More (${genres.length - 10} more)`;
                    
                    // Store remaining genres for later
                    showMoreBtn.dataset.remainingGenres = JSON.stringify(genres.slice(10));
                }
            }
        },
        
        /**
         * Show more genres
         */
        showMoreGenres: function() {
            const container = document.getElementById('genreFilterOptions');
            const showMoreBtn = document.getElementById('showMoreGenres');
            if (!container || !showMoreBtn) return;
            
            const remainingGenres = JSON.parse(showMoreBtn.dataset.remainingGenres || '[]');
            
            remainingGenres.forEach(genre => {
                const label = document.createElement('label');
                label.innerHTML = `<input type="checkbox" name="genre" value="${genre}"> ${genre}`;
                container.appendChild(label);
            });
            
            showMoreBtn.style.display = 'none';
        },
        
        /**
         * Toggle sort direction
         */
        toggleSortDirection: function() {
            const currentDirection = document.querySelector('input[name="sortBy"]:checked')?.value || 'dateAdded';
            const newDirection = this.state.sortDirection === 'asc' ? 'desc' : 'asc';
            this.state.sortDirection = newDirection;
            
            this.updateSortDirectionDisplay(newDirection);
        },
        
        /**
         * Update sort direction display
         */
        updateSortDirectionDisplay: function(direction) {
            const toggleBtn = document.getElementById('sortDirectionToggle');
            const icon = toggleBtn?.querySelector('.pi');
            const text = toggleBtn?.querySelector('.sort-direction-text');
            
            if (icon && text) {
                if (direction === 'asc') {
                    icon.className = 'pi pi-sort-up';
                    text.textContent = 'Ascending';
                } else {
                    icon.className = 'pi pi-sort-down';
                    text.textContent = 'Descending';
                }
            }
        },
        
        /**
         * Apply filters
         */
        applyFilters: function() {
            // Get selected sort
            const selectedSort = document.querySelector('input[name="sortBy"]:checked');
            const sortBy = selectedSort ? selectedSort.value : 'dateAdded';
            
            // Get selected genres
            const selectedGenres = Array.from(
                document.querySelectorAll('input[name="genre"]:checked')
            )
                .map(cb => cb.value)
                .filter(value => value !== ''); // Remove "All Genres"
            
            const filters = {
                search: '', // Will be filled by navigation
                sortBy: sortBy,
                sortDirection: this.state.sortDirection,
                genres: selectedGenres
            };
            
            // Emit filter applied event
            window.dispatchEvent(new CustomEvent('filtersApplied', {
                detail: { filters }
            }));
            
            this.hideFilterMenu();
        },
        
        /**
         * Reset filters
         */
        resetFilters: function() {
            this.state.sortDirection = 'desc';
            
            // Reset sort selection
            const sortRadios = document.querySelectorAll('input[name="sortBy"]');
            sortRadios.forEach(radio => {
                radio.checked = radio.value === 'dateAdded';
            });
            
            // Reset genre selections
            const genreCheckboxes = document.querySelectorAll('input[name="genre"]');
            genreCheckboxes.forEach(checkbox => {
                if (checkbox.value === '') {
                    checkbox.checked = true; // "All Genres"
                } else {
                    checkbox.checked = false;
                }
            });
            
            this.updateSortDirectionDisplay('desc');
        },
        
        /**
         * Show profile modal
         */
        showProfileModal: function() {
            const modal = this.elements.profileModal;
            if (!modal) return;
            
            this.state.profileModalVisible = true;
            this.state.activeModal = 'profile';
            
            modal.classList.add('is-active');
            document.body.style.overflow = 'hidden';
            
            // Load profile data
            this.loadProfileData();
        },
        
        /**
         * Hide profile modal
         */
        hideProfileModal: function() {
            const modal = this.elements.profileModal;
            if (!modal || !this.state.profileModalVisible) return;
            
            this.state.profileModalVisible = false;
            this.state.activeModal = null;
            
            modal.classList.remove('is-active');
            document.body.style.overflow = '';
        },
        
        /**
         * Load profile data
         */
        loadProfileData: function() {
            // This would load profile list and current profile
            // Implementation depends on existing profile management system
            window.dispatchEvent(new CustomEvent('requestProfileData', {}));
        },
        
        /**
         * Handle create profile
         */
        handleCreateProfile: function() {
            const input = document.getElementById('mobileNewProfileNameInput');
            const name = input?.value?.trim();
            
            if (!name) {
                if (input) input.classList.add('error');
                return;
            }
            
            if (input) {
                input.value = '';
                input.classList.remove('error');
            }
            
            // Emit create profile request
            window.dispatchEvent(new CustomEvent('requestCreateProfile', {
                detail: { name }
            }));
        },
        
        /**
         * Handle delete profile
         */
        handleDeleteProfile: function() {
            // Emit delete profile request
            window.dispatchEvent(new CustomEvent('requestDeleteProfile', {}));
        },
        
        /**
         * Show create playlist modal
         */
        showCreatePlaylistModal: function(options = {}) {
            // Create playlist modal dynamically
            const modal = this.createPlaylistModal(options);
            document.body.appendChild(modal);
            modal.classList.add('is-active');
            
            this.state.activeModal = 'playlist';
            this.elements.playlistModal = modal;
        },
        
        /**
         * Create playlist modal
         */
        createPlaylistModal: function(options = {}) {
            const modal = document.createElement('div');
            modal.className = 'modal';
            modal.id = 'responsivePlaylistModal';
            
            modal.innerHTML = `
                <div class="modal-background" onclick="window.ResponsiveModals.hidePlaylistModal()"></div>
                <div class="modal-card">
                    <header class="modal-card-head">
                        <p class="modal-card-title">Create Playlist</p>
                        <button class="delete" aria-label="close" onclick="window.ResponsiveModals.hidePlaylistModal()"></button>
                    </header>
                    <div class="modal-card-body">
                        <div class="mobile-form-group">
                            <label class="mobile-label">Playlist Name</label>
                            <input type="text" 
                                   class="mobile-input" 
                                   id="responsivePlaylistNameInput" 
                                   placeholder="Enter playlist name..."
                                   value="${options.name || ''}">
                        </div>
                        <button class="mobile-btn-block mobile-btn-primary" id="responsiveCreatePlaylistBtn">
                            <i class="pi pi-plus"></i> Create Playlist
                        </button>
                    </div>
                </div>
            `;
            
            // Setup event listeners
            const createBtn = modal.querySelector('#responsiveCreatePlaylistBtn');
            const nameInput = modal.querySelector('#responsivePlaylistNameInput');
            
            createBtn.addEventListener('click', () => {
                const playlistName = nameInput.value.trim();
                if (!playlistName) {
                    nameInput.classList.add('error');
                    return;
                }
                
                window.dispatchEvent(new CustomEvent('requestCreatePlaylist', {
                    detail: { name: playlistName }
                }));
                
                this.hidePlaylistModal();
            });
            
            nameInput.addEventListener('keypress', (e) => {
                if (e.key === 'Enter') {
                    createBtn.click();
                }
            });
            
            nameInput.focus();
            return modal;
        },
        
        /**
         * Hide playlist modal
         */
        hidePlaylistModal: function() {
            const modal = this.elements.playlistModal;
            if (!modal) return;
            
            modal.classList.remove('is-active');
            setTimeout(() => {
                modal.remove();
            }, 300);
            
            this.state.activeModal = null;
            this.elements.playlistModal = null;
        },
        
        /**
         * Show playlist selection for song
         */
        showPlaylistSelection: function(songId) {
            window.dispatchEvent(new CustomEvent('requestPlaylistSelection', {
                detail: { songId }
            }));
        },
        
        /**
         * Add song to queue
         */
        addToQueue: function(songId) {
            const profileId = window.globalActiveProfileId || '1';
            
            if (window.htmx) {
                window.htmx.ajax('POST', `/api/music/queue/add/${profileId}/${songId}`, {
                    handler: function () {
                        window.Helpers.log(`ResponsiveModals: Song ${songId} added to queue`);
                        if (window.showToast) {
                            window.showToast('Song added to queue', 'success');
                        }
                        
                        // Emit queue change event
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
        },
        
        /**
         * Update song metadata
         */
        updateMetadata: function(songId) {
            fetch(`/api/metadata/enrich/${songId}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' }
            })
                .then(response => {
                    if (!response.ok) {
                        throw new Error(`HTTP error! status: ${response.status}`);
                    }
                    return response.json();
                })
                .then(data => {
                    window.Helpers.log('ResponsiveModals: Metadata updated successfully:', data);
                    if (window.showToast) {
                        window.showToast('Metadata updated successfully', 'success');
                    }
                    
                    // Reload song list
                    window.dispatchEvent(new CustomEvent('requestSongListRefresh', {}));
                })
                .catch(error => {
                    window.Helpers.log('ResponsiveModals: Error updating metadata:', error);
                    if (window.showToast) {
                        window.showToast('Failed to update metadata', 'error');
                    }
                });
        },
        
        /**
         * Rescan song
         */
        rescanSong: function(songId) {
            // Emit rescan request
            window.dispatchEvent(new CustomEvent('requestRescanSong', {
                detail: { songId }
            }));
        },
        
        /**
         * Delete song
         */
        deleteSong: function(songId) {
            const confirmed = confirm('Are you sure you want to delete this song? This action cannot be undone.');
            if (!confirmed) return;
            
            // Emit delete request
            window.dispatchEvent(new CustomEvent('requestDeleteSong', {
                detail: { songId }
            }));
        },
        
        /**
         * Handle window resize
         */
        handleResize: function() {
            // Reposition context menu if visible
            if (this.state.contextMenuVisible && this.state.contextMenuPosition) {
                this.positionContextMenu(this.elements.contextMenu);
            }
        },
        
        /**
         * Get current modal state
         */
        getModalState: function() {
            return {
                activeModal: this.state.activeModal,
                contextMenuVisible: this.state.contextMenuVisible,
                filterMenuVisible: this.state.filterMenuVisible,
                profileModalVisible: this.state.profileModalVisible,
                isTouchDevice: this.state.isTouchDevice
            };
        },
        
        /**
         * Auto-initialize when dependencies are available
         */
        initWhenReady: function() {
            try {
                if (window.Helpers && window.DeviceManager) {
                    window.ResponsiveModals.init();
                } else {
                    setTimeout(() => {
                        if (window.Helpers && window.DeviceManager) {
                            window.ResponsiveModals.init();
                        }
                    }, 100);
                }
            } catch (error) {
                console.error('ResponsiveModals initialization failed:', error);
                setTimeout(() => {
                    if (window.Helpers && window.DeviceManager) {
                        window.ResponsiveModals.init();
                    }
                }, 200);
            }
        },
        
        // Start initialization
        init: function() {
            if (this.state.filterMenuVisible) {
                this.hideFilterMenu();
            }
            if (this.state.profileModalVisible) {
                this.hideProfileModal();
            }
            if (this.state.activeModal === 'playlist') {
                this.hidePlaylistModal();
            }
        }
    };
    
    // Auto-initialize when dependencies are available
    if (window.Helpers && window.DeviceManager) {
        window.ResponsiveModals.init();
    } else {
        // Wait for dependencies
        const checkDeps = () => {
            if (window.Helpers && window.DeviceManager) {
                window.ResponsiveModals.init();
            } else {
                setTimeout(checkDeps, 50);
            }
        };
        setTimeout(checkDeps, 50);
    }
    
})(window);