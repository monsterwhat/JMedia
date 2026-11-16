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
            this.songList = document.getElementById('mobileSongList');
            this.player = document.querySelector('.mobile-player');

            if (!this.songList) console.warn('Mobile song list not found');
            if (!this.player) console.warn('Mobile player not found');
        }

        setupMobileEventListeners() {
            document.addEventListener('click', (e) => {
                const btn = e.target.closest('#createPlaylistBtn, #mobileCreatePlaylistBtn');
                if (btn) {
                    this.showMobileCreatePlaylistModal();
                }
            });
            document.body.addEventListener('profileSwitched', () => {
                console.debug('[JMediaMobile] profileSwitched');
            });
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

        showMobileCreatePlaylistModal() {
            const modal = this.createMobilePlaylistModal();
            document.body.appendChild(modal);
            modal.classList.add('is-active');
            const nameInput = modal.querySelector('#mobilePlaylistNameInput');
            if (nameInput) nameInput.focus();
        }

        showMobileEditPlaylistModal(playlist) {
            const modal = this.createMobilePlaylistModal(playlist);
            document.body.appendChild(modal);
            modal.classList.add('is-active');
            const nameInput = modal.querySelector('#mobilePlaylistNameInput');
            if (nameInput) nameInput.focus();
        }

        createMobilePlaylistModal(playlist = null) {
            const modal = document.createElement('div');
            modal.className = 'mobile-modal';
            modal.id = 'mobilePlaylistModal';

            const isEdit = playlist !== null;
            const title = isEdit ? 'Edit Playlist' : 'Create Playlist';
            const buttonText = isEdit ? 'Save Changes' : 'Create Playlist';

            modal.innerHTML = `
                <div class="mobile-modal-overlay" onclick="closeMobilePlaylistModal()"></div>
                <div class="mobile-modal-card">
                    <header class="mobile-modal-header">
                        <h3 class="mobile-modal-title">${title}</h3>
                        <button class="mobile-modal-close" onclick="closeMobilePlaylistModal()">
                            <i class="pi pi-times"></i>
                        </button>
                    </header>
                    <div class="mobile-modal-body">
                        <div class="mobile-form-group">
                            <label class="mobile-label">Playlist Name</label>
                            <input type="text" class="mobile-input" id="mobilePlaylistNameInput"
                                   placeholder="Enter playlist name...">
                        </div>
                        <button class="mobile-btn-block mobile-btn-primary" id="mobileSavePlaylistBtn">
                            <i class="pi pi-${isEdit ? 'save' : 'plus'}"></i> ${buttonText}
                        </button>
                    </div>
                </div>
            `;

            const saveBtn = modal.querySelector('#mobileSavePlaylistBtn');
            const nameInput = modal.querySelector('#mobilePlaylistNameInput');

            // Set the value via the DOM property (never interpolate user-supplied
            // names into the innerHTML template above - that would allow HTML
            // injection through a crafted playlist name).
            if (isEdit && playlist.name) {
                nameInput.value = playlist.name;
            }

            saveBtn.addEventListener('click', () => {
                const playlistName = nameInput.value.trim();
                if (!playlistName) {
                    nameInput.classList.add('error');
                    return;
                }
                if (isEdit) {
                    this.updateMobilePlaylist(playlist.id, playlistName);
                } else {
                    this.createMobilePlaylist(playlistName);
                }
            });

            nameInput.addEventListener('keypress', (e) => {
                if (e.key === 'Enter') {
                    saveBtn.click();
                }
            });

            return modal;
        }

        closeMobilePlaylistModal() {
            const modal = document.getElementById('mobilePlaylistModal');
            if (modal) {
                modal.classList.remove('is-active');
                setTimeout(() => {
                    modal.remove();
                }, 300);
            }
        }

        editMobilePlaylist(playlistId) {
            const profileId = JMedia.Helpers.getActiveProfileId();
            fetch(`/api/music/playlists/${profileId}`)
                .then(res => {
                    if (!res.ok) throw new Error(`HTTP error! status: ${res.status}`);
                    return res.json();
                })
                .then(data => {
                    const playlists = data.data || data;
                    const playlist = playlists.find(p => p.id === playlistId);
                    if (!playlist) throw new Error(`Playlist ${playlistId} not found`);
                    this.showMobileEditPlaylistModal(playlist);
                })
                .catch(err => {
                    console.error('Error loading playlist for edit:', err);
                    if (window.showToast) window.showToast('Failed to load playlist', 'error');
                });
        }

        createMobilePlaylist(name) {
            const profileId = JMedia.Helpers.getActiveProfileId();
            fetch('/api/music/playlists/', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({name: name, profileId: profileId})
            })
                .then(res => {
                    if (!res.ok) throw new Error(`HTTP error! status: ${res.status}`);
                    return res.json();
                })
                .then(() => {
                    this.closeMobilePlaylistModal();
                    this.loadMobilePlaylists();
                    if (window.showToast) window.showToast('Playlist created successfully', 'success');
                })
                .catch(err => {
                    console.error('Error creating playlist:', err);
                    if (window.showToast) window.showToast('Failed to create playlist', 'error');
                });
        }

        updateMobilePlaylist(playlistId, name) {
            fetch(`/api/music/playlists/${playlistId}`, {
                method: 'PUT',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({name: name})
            })
                .then(res => {
                    if (!res.ok) throw new Error(`HTTP error! status: ${res.status}`);
                    return res.json();
                })
                .then(() => {
                    this.closeMobilePlaylistModal();
                    this.loadMobilePlaylists();
                    if (window.showToast) window.showToast('Playlist updated successfully', 'success');
                })
                .catch(err => {
                    console.error('Error updating playlist:', err);
                    if (window.showToast) window.showToast('Failed to update playlist', 'error');
                });
        }

        createPlaylistFromAddDialog() {
            const findModal = () => document.querySelector('.mobile-modal[data-song-id], .mobile-modal[data-song-ids]');
            const modal = findModal();
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
                    const activeModal = findModal();
                    const songId = activeModal?.dataset?.songId;
                    let songIds = null;
                    const rawIds = activeModal?.dataset?.songIds;
                    if (rawIds) {
                        try {
                            songIds = JSON.parse(rawIds);
                        } catch (parseError) {
                            console.warn('[MobileApp] Invalid songIds on add-to-playlist modal', parseError);
                        }
                    }
                    // Album/genre picker: add the whole collection in one request.
                    if (songIds && songIds.length) {
                        return fetch(`/api/music/playlists/${newId}/songs/bulk`, {
                            method: 'POST',
                            headers: {'Content-Type': 'application/json'},
                            body: JSON.stringify({songIds: songIds})
                        }).then(res => {
                            if (!res.ok) throw new Error(`HTTP error! status: ${res.status}`);
                            return res.json().catch(() => ({}));
                        });
                    }
                    if (!songId) throw new Error('Song ID missing from modal when adding new playlist');
                    return this.addSongToPlaylistHandler(newId, songId);
                })
                .then(() => {
                    if (window.showToast) window.showToast('Playlist created and music added', 'success');
                    const activeModal = findModal();
                    if (activeModal) activeModal.remove();
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
                        if (window.showToast) window.showToast('Song added to playlist', 'success');
                    })
                    .catch(error => {
                        console.error('[MobileContextMenu] Error adding song to playlist:', error);
                        if (window.showToast) window.showToast('Failed to add song to playlist', 'error');
                    });
            } else {
                console.error('[MobileContextMenu] addSongToPlaylist function not available');
                if (window.showToast) window.showToast('Add to playlist not available', 'error');
                return Promise.reject('addSongToPlaylist not available');
            }
        }
    }

    JMedia.MobileApp = JMediaMobile;

    // Global helpers referenced by inline onclick handlers in dynamically
    // created modal markup (e.g. the close button / overlay of the playlist modal).
    window.showMobileCreatePlaylistModal = function() {
        const app = window.jmediaMobile;
        if (app && typeof app.showMobileCreatePlaylistModal === 'function') {
            app.showMobileCreatePlaylistModal();
        }
    };
    window.editMobilePlaylist = function(playlistId) {
        const app = window.jmediaMobile;
        if (app && typeof app.editMobilePlaylist === 'function') {
            app.editMobilePlaylist(playlistId);
        }
    };
    window.closeMobilePlaylistModal = function() {
        const app = window.jmediaMobile;
        if (app && typeof app.closeMobilePlaylistModal === 'function') {
            app.closeMobilePlaylistModal();
        } else {
            const modal = document.getElementById('mobilePlaylistModal');
            if (modal) modal.remove();
        }
    };

    (window.__whenDomReady || function (fn) {
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn, { once: true });
        else fn();
    })(() => {
        setTimeout(() => {
            window.jmediaMobile = new JMediaMobile();
            console.log('[JMediaMobile] Mobile enhancements initialized');
        }, 500);
    });

    window.addEventListener('orientationchange', () => {
        setTimeout(() => {
            window.scrollTo(0, 0);
        }, 100);
    });

    window.JMediaMobile = JMediaMobile;

})(window);
