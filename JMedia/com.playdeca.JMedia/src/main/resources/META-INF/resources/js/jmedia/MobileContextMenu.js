(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    class MobileContextMenu {
        constructor() {
            this.activeElement = null;
            this.timerId = null;
            this.duration = 500;
            this.currentSongId = null;
            this.menuJustOpened = false;
            this.mousePosition = null;
            this.init();
        }

        init() {
            this.setupEventListeners();
            console.log('[MobileContextMenu] Context menu initialized');
        }

        setupEventListeners() {
            document.body.addEventListener('touchstart', this.handleTouchStart.bind(this), {passive: false});
            ['touchend', 'touchmove', 'touchcancel'].forEach(event => {
                document.body.addEventListener(event, this.cancelTouch.bind(this), {passive: false});
            });
            document.body.addEventListener('contextmenu', this.handleContextMenu.bind(this));
            document.body.addEventListener('click', this.handleOutsideClick.bind(this));

            const backdrop = document.querySelector('.mobile-context-backdrop');
            if (backdrop) {
                backdrop.addEventListener('touchend', this.hideMenu.bind(this), {passive: true});
            }

            const menu = document.getElementById('mobileContextMenu');
            if (menu) {
                const menuList = menu.querySelector('.mobile-context-list');
                if (menuList) {
                    menuList.addEventListener('click', this.handleMenuClick.bind(this));
                    menuList.addEventListener('touchend', this.handleMenuClick.bind(this), {passive: true});
                }
                menu.addEventListener('click', (e) => { e.stopPropagation(); });
                menu.addEventListener('touchend', (e) => { e.stopPropagation(); }, {passive: true});

                const backdrop = menu.querySelector('.mobile-context-backdrop');
                if (backdrop) {
                    backdrop.addEventListener('click', this.hideMenu.bind(this));
                    backdrop.addEventListener('touchend', this.hideMenu.bind(this), {passive: true});
                }
            }

            document.addEventListener('keydown', (e) => {
                if (e.key === 'Escape') {
                    this.hideMenu();
                }
            });
        }

        handleTouchStart(e) {
            if (e.touches && e.touches.length > 1) return;
            const target = e.target.closest('.mobile-song-item');
            if (!target) return;
            this.activeElement = target;
            const songId = target.dataset.songId;
            target.classList.add('long-press-active');
            this.timerId = setTimeout(() => {
                e.preventDefault();
                e.stopPropagation();
                this.showContextMenu(songId);
            }, this.duration);
        }

        handleContextMenu(e) {
            e.preventDefault();
            const target = e.target.closest('.mobile-song-item');
            if (!target) return;
            const songId = target.dataset.songId;
            if (!songId) return;
            this.mousePosition = { x: e.clientX, y: e.clientY };
            this.showContextMenu(songId, true);
        }

        handleOutsideClick(e) {
            const menu = document.getElementById('mobileContextMenu');
            if (!menu || menu.getAttribute('aria-hidden') === 'true') return;
            if (!menu.contains(e.target)) {
                this.hideMenu();
            }
        }

        cancelTouch(e) {
            if (this.timerId) {
                clearTimeout(this.timerId);
                this.timerId = null;
            }
            if (this.activeElement) {
                this.activeElement.classList.remove('long-press-active');
            }
            if (this.menuJustOpened && e) {
                e.preventDefault();
                e.stopPropagation();
                e.stopImmediatePropagation();
            }
        }

        showContextMenu(songId, isDesktop = false) {
            const menu = document.getElementById('mobileContextMenu');
            if (!menu) return;
            this.currentSongId = songId;
            menu.dataset.songId = songId;
            menu.setAttribute('aria-hidden', 'false');

            if (isDesktop) {
                menu.classList.add('desktop-context');
                this.positionMenuAtCursor(menu);
            } else {
                menu.classList.remove('desktop-context');
            }

            const backdrop = menu.querySelector('.mobile-context-backdrop');
            if (backdrop) {
                backdrop.style.pointerEvents = 'auto';
            }

            if (!isDesktop && this.activeElement) {
                this.menuJustOpened = true;
                setTimeout(() => { this.menuJustOpened = false; }, 300);
            }
        }

        positionMenuAtCursor(menu) {
            if (!this.mousePosition) return;
            menu.style.position = 'fixed';
            menu.style.left = 'auto';
            menu.style.top = 'auto';
            menu.style.transform = 'none';

            const menuRect = menu.getBoundingClientRect();
            const menuWidth = menuRect.width || 200;
            const menuHeight = menuRect.height || 250;

            let x = this.mousePosition.x;
            let y = this.mousePosition.y;

            if (x + menuWidth > window.innerWidth) {
                x = window.innerWidth - menuWidth - 10;
            }
            if (y + menuHeight > window.innerHeight) {
                y = window.innerHeight - menuHeight - 10;
            }
            if (x < 10) x = 10;
            if (y < 10) y = 10;

            menu.style.left = x + 'px';
            menu.style.top = y + 'px';
        }

        hideMenu() {
            const menu = document.getElementById('mobileContextMenu');
            if (!menu) return;
            menu.setAttribute('aria-hidden', 'true');
            menu.classList.remove('desktop-context');
            delete menu.dataset.songId;
            this.currentSongId = null;
            menu.style.position = '';
            menu.style.left = '';
            menu.style.top = '';
            menu.style.transform = '';

            const backdrop = menu.querySelector('.mobile-context-backdrop');
            if (backdrop) {
                backdrop.style.pointerEvents = 'none';
            }
            if (this.activeElement) {
                this.activeElement.classList.remove('long-press-active');
                this.activeElement = null;
            }
            this.mousePosition = null;
        }

        handleMenuClick(e) {
            const li = e.target.closest('li');
            if (!li) return;
            const action = li.dataset.action;
            const songId = this.currentSongId;
            if (!songId) {
                console.warn('[MobileContextMenu] No valid song ID found');
                return;
            }
            switch (action) {
                case 'queue':
                    if (window.htmx) {
                        const profileId = JMedia.Helpers.getActiveProfileId();
                        htmx.ajax('POST', `/api/music/queue/add/${profileId}/${songId}`, {
                            handler: function () {
                                console.log(`Song ${songId} added to queue.`);
                                if (window.showToast) window.showToast('Song added to queue', 'success');
                                window.dispatchEvent(new CustomEvent('queueChanged', {
                                    detail: { queueSize: JMedia.PlaybackApi.getQueueSize(), queueChanged: true, queueLengthChanged: true }
                                }));
                            }
                        });
                    }
                    break;
                case 'playlist':
                    this.openPlaylistSubmenu(songId);
                    break;
                case 'rescan':
                    JMedia.PlaybackApi.rescanSong(songId);
                    break;
                case 'queue-similar':
                    JMedia.PlaybackApi.queueSimilar(songId);
                    break;
                case 'enrich':
                    JMedia.PlaybackApi.enrichMetadata(songId);
                    break;
                case 'delete':
                    JMedia.PlaybackApi.deleteSong(songId);
                    break;
                default:
                    console.warn('[MobileContextMenu] Unknown action:', action);
                    break;
            }
            this.hideMenu();
        }

        updateMetadata(songId) {
            console.log('[MobileContextMenu] Updating metadata for song ID:', songId);
            fetch(`/api/metadata/enrich/${songId}`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'}
            })
                .then(response => {
                    if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
                    return response.json();
                })
                .then(data => {
                    console.log('[MobileContextMenu] Metadata updated successfully:', data);
                    if (showToast) showToast('Metadata updated successfully', 'success');
                    if (window.jmediaMobile && window.jmediaMobile.loadInitialContent) {
                        window.jmediaMobile.loadInitialContent();
                    }
                })
                .catch(error => {
                    console.error('[MobileContextMenu] Error updating metadata:', error);
                    if (showToast) showToast('Failed to update metadata', 'error');
                });
        }

        openPlaylistSubmenu(songId) {
            console.log('[MobileContextMenu] Opening playlist submenu for song ID:', songId);
            const profileId = JMedia.Helpers.getActiveProfileId();
            fetch(`/api/music/playlists/${profileId}`)
                .then(response => response.json())
                .then(data => {
                    const playlists = data.data || data;
                    this.showPlaylistSelection(playlists, songId);
                })
                .catch(error => {
                    console.error('[MobileContextMenu] Error fetching playlists:', error);
                    if (showToast) showToast('Failed to load playlists', 'error');
                });
        }

        showPlaylistSelection(playlists, songId) {
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
                        ${playlists.length === 0 ? `
                        <div class="mobile-empty-state" style="text-align:center; padding:20px; margin-bottom:15px;">
                            <i class="pi pi-folder-open" style="font-size:48px;color:#ccc;"></i>
                            <p>No playlists found</p>
                            <p style="font-size:12px;color:#999;">Create your first playlist to get started</p>
                        </div>
                        ` : `
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

            if (!document.querySelector('#playlist-selection-styles')) {
                const style = document.createElement('style');
                style.id = 'playlist-selection-styles';
                style.textContent = `
                    .mobile-playlist-selection { max-height: 300px; overflow-y: auto; }
                    .mobile-playlist-option { display: flex; align-items: center; justify-content: space-between; padding: 12px 16px; border-radius: 8px; cursor: pointer; transition: background-color 0.2s ease; }
                    .mobile-playlist-option:hover { background: var(--mobile-primary); color: white; }
                `;
                document.head.appendChild(style);
            }

            document.body.appendChild(modal);
            modal.classList.add('mobile-modal', 'is-active');
        }

        addSongToPlaylistHandler(playlistId, songId) {
            console.log('[MobileContextMenu] Adding song to playlist:', playlistId, songId);
            if (typeof addSongToPlaylist === 'function') {
                addSongToPlaylist(playlistId, songId)
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
            }
        }
    }

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
                coverContainer.addEventListener('contextmenu', this.handleContextMenu.bind(this));
                console.log('[CoverImageContextMenu] Cover image long press and right-click initialized');
            }
        }

        handleTouchStart(e) {
            e.preventDefault();
            const coverContainer = document.getElementById('songCoverImageContainer');
            if (coverContainer) {
                coverContainer.classList.add('long-press-active');
            }
            this.timerId = setTimeout(() => {
                const songId = JMedia.PlaybackApi.getCurrentSongId();
                if (songId) {
                    this.contextMenu.showContextMenu(songId);
                } else {
                    if (showToast) showToast('No song currently playing', 'info');
                }
            }, this.duration);
        }

        handleContextMenu(e) {
            e.preventDefault();
            const songId = JMedia.PlaybackApi.getCurrentSongId();
            if (!songId) {
                if (showToast) showToast('No song currently playing', 'info');
                return;
            }
            this.contextMenu.mousePosition = { x: e.clientX, y: e.clientY };
            this.contextMenu.showContextMenu(songId, true);
        }

        cancelTouch() {
            if (this.timerId) {
                clearTimeout(this.timerId);
                this.timerId = null;
            }
            const coverContainer = document.getElementById('songCoverImageContainer');
            if (coverContainer) {
                coverContainer.classList.remove('long-press-active');
            }
        }
    }

    JMedia.MobileContextMenu = MobileContextMenu;
    JMedia.CoverImageContextMenu = CoverImageContextMenu;

    document.addEventListener('DOMContentLoaded', () => {
        setTimeout(() => {
            if (document.getElementById('mobileContextMenu')) {
                window.mobileContextMenu = new MobileContextMenu();
                window.coverImageContextMenu = new CoverImageContextMenu(window.mobileContextMenu);
                console.log('[MobileContextMenu] All context menu features initialized');
            } else {
                console.warn('[MobileContextMenu] Context menu element not found');
            }
        }, 1000);
    });

})(window);
