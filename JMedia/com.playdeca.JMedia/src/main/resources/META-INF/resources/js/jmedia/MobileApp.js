(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

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
            console.log('[JMediaMobile] Mobile UI initialized');
        }

        loadInitialContent(search = '', sortBy = 'dateAdded', genres = [], sortDirection = null) {
            if (window.htmx) {
                const direction = sortDirection || this.currentSortDirection;
                const params = new URLSearchParams({
                    search: search,
                    sortBy: sortBy,
                    sortDirection: direction
                });
                if (genres && genres.length > 0) {
                    params.set('genres', genres.join(','));
                }
                const profileId = JMedia.Helpers.getActiveProfileId();
                const url = `/api/music/ui/mobile-tbody/${profileId}/0?${params.toString()}`;
                window.htmx.ajax('GET', url, {
                    target: document.getElementById('mobileSongList'),
                    swap: 'innerHTML'
                });
            }
        }

        setupElements() {
            this.navToggle = document.getElementById('mobileNavToggle');
            this.sidePanel = document.getElementById('mobileSidePanel');
            this.sidePanelOverlay = document.getElementById('mobileSidePanelOverlay');
            this.sidePanelClose = document.getElementById('mobileSidePanelClose');
            this.searchInput = document.getElementById('mobileSearch');
            this.searchClear = document.getElementById('mobileSearchClear');
            this.songList = document.getElementById('mobileSongList');
            this.tabButtons = document.querySelectorAll('.mobile-tab');
            this.expandBtn = document.getElementById('expandPlayerBtn');
            this.player = document.querySelector('.mobile-player');

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
            if (this.navToggle) {
                this.navToggle.addEventListener('click', () => this.openSidePanel());
            }
            if (this.sidePanelClose) {
                this.sidePanelClose.addEventListener('click', () => this.closeSidePanel());
            }
            if (this.sidePanelOverlay) {
                this.sidePanelOverlay.addEventListener('click', () => this.closeSidePanel());
            }
            if (this.tabButtons) {
                this.tabButtons.forEach(tab => {
                    tab.addEventListener('click', () => {
                        const tabName = tab.dataset.tab || tab.id.replace('Tab', '');
                        this.switchMobileTab(tabName);
                    });
                });
            }
            if (this.searchInput) {
                this.searchInput.addEventListener('input', (e) => this.handleMobileSearch(e.target.value));
            }
            if (this.searchClear) {
                this.searchClear.addEventListener('click', () => this.clearMobileSearch());
            }
            if (this.expandBtn) {
                this.expandBtn.addEventListener('click', () => this.togglePlayerExpansion());
            }
            this.setupSortEventListeners();
            document.addEventListener('click', (e) => {
                const btn = e.target.closest('#createPlaylistBtn, #mobileCreatePlaylistBtn');
                if (btn) {
                    if (typeof showMobileCreatePlaylistModal === 'function') {
                        showMobileCreatePlaylistModal();
                    }
                }
            });
            document.body.addEventListener('profileSwitched', () => {});
        }

        setupSortEventListeners() {
            const sortDirectionToggle = document.getElementById('sortDirectionToggle');
            const sortOptions = document.querySelectorAll('input[name="sortBy"]');
            if (sortDirectionToggle) {
                sortDirectionToggle.addEventListener('click', () => {
                    this.toggleSortDirection();
                });
            }
            sortOptions.forEach(option => {
                option.addEventListener('change', () => {
                    if (option.checked) {
                        this.currentSortBy = option.value;
                        this.applySortWithCurrentDirection();
                    }
                });
            });
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
            const currentSearch = this.searchInput ? this.searchInput.value : '';
            let currentGenres = [];
            if (window.mobileFilterSortMenu) {
                const filters = window.mobileFilterSortMenu.getCurrentFilters();
                currentGenres = filters.genres;
            }
            this.loadInitialContent(currentSearch, sortBy, currentGenres, this.currentSortDirection);
        }

        openSidePanel() {
            if (this.sidePanel && this.sidePanelOverlay) {
                this.sidePanel.classList.add('active');
                this.sidePanelOverlay.classList.add('active');
                document.body.style.overflow = 'hidden';
                this.loadMobilePlaylists();
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
            this.tabButtons.forEach(tab => {
                tab.classList.remove('active');
                if (tab.dataset.tab === tabName || tab.id === tabName + 'Tab') {
                    tab.classList.add('active');
                }
            });
            const tabContents = document.querySelectorAll('.mobile-tab-content');
            tabContents.forEach(content => {
                content.classList.remove('active');
            });
            const targetContentId = tabName === 'playlists'
                ? 'mobilePlaylistContent'
                : 'mobile' + tabName.charAt(0).toUpperCase() + tabName.slice(1) + 'Content';
            const targetContent = document.getElementById(targetContentId);
            if (targetContent) {
                targetContent.classList.add('active');
            }
            if (tabName === 'playlists') {
                this.loadMobilePlaylists();
            } else if (tabName === 'queue') {
                this.loadMobileQueue();
            } else if (tabName === 'history') {
                this.loadMobileHistory();
            }
        }

        loadMobilePlaylists() {
            if (window.htmx) {
                const profileId = JMedia.Helpers.getActiveProfileId();
                window.htmx.ajax('GET', `/api/music/ui/mobile-playlists-fragment/${profileId}`, {
                    target: document.getElementById('mobilePlaylistContent'),
                    swap: 'innerHTML'
                });
            }
        }

        loadMobilePlaylistsJSON() {
            const target = document.getElementById('mobilePlaylistList');
            if (!target) return;
            target.innerHTML = `
                <div class="mobile-loading">
                    <div class="mobile-loading-spinner">
                        <i class="pi pi-spin pi-spinner"></i>
                    </div>
                    <p>Loading playlists...</p>
                </div>
            `;
            const profileId = JMedia.Helpers.getActiveProfileId();
            fetch(`/api/music/playlists/${profileId}`)
                .then(response => response.json())
                .then(data => {
                    const playlists = data.data || data;
                    this.renderMobilePlaylistsJSON(playlists, target);
                })
                .catch(error => {
                    console.error('[MOBILE] JSON playlist loading failed:', error);
                    target.innerHTML = `
                        <div class="mobile-empty-state">
                            <i class="pi pi-exclamation-triangle" style="font-size: 48px; color: #ff6b6b;"></i>
                            <p>Error loading playlists</p>
                            <p style="font-size: 12px; color: #999;">Please try again</p>
                        </div>
                    `;
                });
        }

        renderMobilePlaylistsJSON(playlists, target) {
            const profileId = JMedia.Helpers.getActiveProfileId();
            let html = `
                <div class="mobile-playlist-item"
                     hx-get="/api/music/ui/mobile-tbody/${profileId}/0"
                     hx-target="#mobileSongList"
                     hx-swap="innerHTML"
                     data-playlist-id="0">
                    <div class="mobile-playlist-info">
                        <div class="mobile-playlist-name">All Songs</div>
                    </div>
                    <i class="pi pi-chevron-right"></i>
                </div>
            `;
            if (playlists && playlists.length > 0) {
                playlists.forEach(playlist => {
                    html += `
                        <div class="mobile-playlist-item"
                             hx-get="/api/music/ui/mobile-tbody/${profileId}/${playlist.id}"
                             hx-target="#mobileSongList"
                             hx-swap="innerHTML"
                             data-playlist-id="${playlist.id}">
                            <div class="mobile-playlist-info">
                                <div class="mobile-playlist-name">${playlist.name || 'Unnamed Playlist'}</div>
                                ${playlist.isGlobal ? '<span class="tag is-info is-small"><i class="pi pi-globe"></i></span>' : ''}
                            </div>
                            <i class="pi pi-chevron-right"></i>
                        </div>
                    `;
                });
            }
            if (!playlists || playlists.length === 0) {
                html += `
                    <div class="mobile-empty-state">
                        <i class="pi pi-folder-open" style="font-size: 48px; color: #ccc;"></i>
                        <p>No playlists found</p>
                        <p style="font-size: 12px; color: #999;">Create your first playlist to get started</p>
                    </div>
                `;
            }
            target.innerHTML = html;
            console.log('[MOBILE] JSON playlists rendered successfully');
        }

        loadMobileQueue(page = 1) {
            if (window.htmx) {
                const profileId = JMedia.Helpers.getActiveProfileId();
                const url = `/api/music/ui/mobile-queue-fragment/${profileId}?page=${page}`;
                window.htmx.ajax('GET', url, {
                    target: document.getElementById('mobileQueueContent'),
                    swap: 'innerHTML'
                });
            }
        }

        loadMobileHistory(page = 1) {
            if (window.htmx) {
                const profileId = JMedia.Helpers.getActiveProfileId();
                const url = `/api/music/ui/mobile-history-fragment/${profileId}?page=${page}`;
                window.htmx.ajax('GET', url, {
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
                <div class="mobile-song-item${song.flac ? ' is-flac' : ''}" data-song-id="${song.id}">
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
            historyContainer.querySelectorAll('.mobile-song-item').forEach(item => {
                item.addEventListener('click', () => {
                    const songId = item.dataset.songId;
                    const profileId = JMedia.Helpers.getActiveProfileId();
                    if (songId) {
                        JMedia.PlaybackApi.select(songId, profileId);
                    }
                });
            });
        }

        handleMobileSearch(query) {
            if (this.searchClear) {
                this.searchClear.style.display = query ? 'block' : 'none';
            }
            if (window.htmx) {
                clearTimeout(this.searchTimeout);
                this.searchTimeout = setTimeout(() => {
                    let currentSort = 'dateAdded';
                    let currentGenres = [];
                    if (window.mobileFilterSortMenu) {
                        const filters = window.mobileFilterSortMenu.getCurrentFilters();
                        currentSort = filters.sortBy;
                        currentGenres = filters.genres;
                    } else {
                        const checkedSortOption = document.querySelector('input[name="sortBy"]:checked');
                        currentSort = checkedSortOption ? checkedSortOption.value : 'dateAdded';
                    }
                    this.loadInitialContent(query, currentSort, currentGenres, this.currentSortDirection);
                    setTimeout(() => {
                        const songItems = document.querySelectorAll('.mobile-song-item');
                        songItems.forEach(item => {
                            item.addEventListener('click', (e) => {
                                if (window.mobileContextMenu && window.mobileContextMenu.menuJustOpened) {
                                    e.preventDefault();
                                    e.stopPropagation();
                                    return;
                                }
                                const songId = item.dataset.songId;
                                const profileId = JMedia.Helpers.getActiveProfileId();
                                if (songId) {
                                    JMedia.PlaybackApi.select(songId, profileId);
                                }
                            });
                        });
                    }, 100);
                }, 500);
            }
        }

        clearMobileSearch() {
            if (this.searchInput) {
                this.searchInput.value = '';
                this.searchClear.style.display = 'none';
                let currentSort = 'dateAdded';
                let currentGenres = [];
                if (window.mobileFilterSortMenu) {
                    const filters = window.mobileFilterSortMenu.getCurrentFilters();
                    currentSort = filters.sortBy;
                    currentGenres = filters.genres;
                } else {
                    const checkedSortOption = document.querySelector('input[name="sortBy"]:checked');
                    currentSort = checkedSortOption ? checkedSortOption.value : 'dateAdded';
                }
                this.loadInitialContent('', currentSort, currentGenres, this.currentSortDirection);
            }
        }

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
            this.player.classList.add('expanded');
            this.isExpanded = true;
            this.updateExpandButtonIcon('pi-chevron-down');
            document.body.style.overflow = 'hidden';
            this.setupExpandedKeyboardListeners();
            setTimeout(() => {
                this.expanding = false;
            }, 300);
        }

        collapsePlayer() {
            this.expanding = true;
            this.player.classList.add('collapsing');
            this.updateExpandButtonIcon('pi-expand-up');
            setTimeout(() => {
                this.player.classList.remove('expanded', 'collapsing');
                this.isExpanded = false;
                document.body.style.overflow = '';
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
            const profileId = JMedia.Helpers.getActiveProfileId();
            fetch('/api/music/playlists/', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({name: playlistName, profileId})
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

        addSongToPlaylistHandler(playlistId, songId) {
            console.log('[MobileContextMenu] Adding song to playlist:', playlistId, songId);
            if (typeof addSongToPlaylist === 'function') {
                return addSongToPlaylist(playlistId, songId)
                    .then(() => {
                        console.log('[MobileContextMenu] Song added to playlist successfully');
                        if (showToast) showToast('Song added to playlist', 'success');
                    })
                    .catch(error => {
                        console.error('[MobileContextMenu] Error adding song to playlist:', error);
                        if (showToast) showToast('Failed to add song to playlist', 'error');
                    });
            } else {
                console.error('[MobileContextMenu] addSongToPlaylist function not available');
                if (showToast) showToast('Add to playlist not available', 'error');
                return Promise.reject('addSongToPlaylist not available');
            }
        }
    }

    JMedia.MobileApp = JMediaMobile;

    document.addEventListener('DOMContentLoaded', () => {
        if (window.initManager) {
            window.initManager.start().then(() => {
                window.jmediaMobile = new JMediaMobile();
                console.log('[JMediaMobile] Mobile enhancements initialized');
            });
        } else {
            setTimeout(() => {
                window.jmediaMobile = new JMediaMobile();
                console.log('[JMediaMobile] Mobile enhancements initialized');
            }, 500);
        }
    });

    window.addEventListener('orientationchange', () => {
        setTimeout(() => {
            window.scrollTo(0, 0);
        }, 100);
    });

    window.JMediaMobile = JMediaMobile;

})(window);
