(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    class MobileContextMenu {
        constructor() {
            this.activeElement = null;
            this.timerId = null;
            this.duration = 500;
            this.currentSongId = null;
            this.currentTarget = null;
            this.menuJustOpened = false;
            this.mousePosition = null;
            this._suppressCardClickUntil = 0;
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
            document.body.addEventListener('click', (e) => {
                if (this._suppressCardClickUntil && this._suppressCardClickUntil > Date.now()) {
                    const card = e.target && e.target.closest ? e.target.closest('.mobile-album-card, .mobile-genre-card, .mobile-playlist-card') : null;
                    if (card) {
                        e.preventDefault();
                        e.stopPropagation();
                        e.stopImmediatePropagation();
                    }
                }
            }, true);

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

        buildTarget(el) {
            if (!el) return null;
            if (el.classList && el.classList.contains('mobile-album-card')) {
                return {
                    type: 'album',
                    value: el.dataset.albumName,
                    albumArtist: (el.dataset.albumArtist && el.dataset.albumArtist !== 'null' && el.dataset.albumArtist !== 'undefined') ? el.dataset.albumArtist : '',
                    firstSongId: (el.dataset.firstSongId && el.dataset.firstSongId !== 'null' && el.dataset.firstSongId !== 'undefined') ? el.dataset.firstSongId : null,
                    element: el
                };
            }
            if (el.classList && el.classList.contains('mobile-genre-card')) {
                return {
                    type: 'genre',
                    value: el.dataset.genreName,
                    element: el
                };
            }
            if (el.classList && el.classList.contains('mobile-playlist-card')) {
                const playlistId = el.dataset.playlistId;
                if (!playlistId) return null;
                return {
                    type: 'playlist',
                    value: playlistId,
                    playlistId: playlistId,
                    name: el.dataset.playlistName || '',
                    element: el
                };
            }
            const songId = el.dataset ? el.dataset.songId : null;
            if (!songId) return null;
            return {
                type: 'song',
                value: songId,
                songId: songId,
                element: el
            };
        }

        normalizeTarget(target) {
            if (target == null) return null;
            if (typeof target === 'string' || typeof target === 'number') {
                const s = String(target);
                return { type: 'song', value: s, songId: s, element: null };
            }
            if (target.type) return target;
            if (target.dataset) {
                return this.buildTarget(target);
            }
            if (target.songId) {
                return { type: 'song', value: target.songId, songId: target.songId, element: target.element || null };
            }
            if (target.songIds) {
                return { type: target.type || 'bulk', value: target.value, songIds: target.songIds, label: target.label, element: target.element || null };
            }
            return target;
        }

        handleTouchStart(e) {
            if (e.touches && e.touches.length > 1) return;
            const target = e.target.closest('.mobile-song-item, tr[data-song-id], .mobile-album-card, .mobile-genre-card, .mobile-playlist-card');
            if (!target) return;
            this.activeElement = target;
            target.classList.add('long-press-active');
            this.timerId = setTimeout(() => {
                try { e.preventDefault(); } catch (err) { console.warn('[MobileContextMenu] preventDefault failed', err); }
                try { e.stopPropagation(); } catch (err) { console.warn('[MobileContextMenu] stopPropagation failed', err); }
                const t = this.buildTarget(target);
                if (!t) return;
                this._suppressCardClickUntil = Date.now() + 800;
                this.showContextMenu(t);
            }, this.duration);
        }

        handleContextMenu(e) {
            e.preventDefault();
            const target = e.target.closest('.mobile-song-item, tr[data-song-id], .mobile-album-card, .mobile-genre-card, .mobile-playlist-card');
            if (!target) return;
            const t = this.buildTarget(target);
            if (!t) return;
            if (t.type === 'song' && !t.songId) return;
            this.mousePosition = { x: e.clientX, y: e.clientY };
            this._suppressCardClickUntil = Date.now() + 800;
            this.showContextMenu(t, true);
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
                this.activeElement = null;
            }
            if (this.menuJustOpened && e) {
                try { e.preventDefault(); } catch (err) { console.warn('[MobileContextMenu] preventDefault failed', err); }
                try { e.stopPropagation(); } catch (err) { console.warn('[MobileContextMenu] stopPropagation failed', err); }
                try { e.stopImmediatePropagation(); } catch (err) { console.warn('[MobileContextMenu] stopImmediatePropagation failed', err); }
            }
        }

        showContextMenu(target, isDesktop = false) {
            const menu = document.getElementById('mobileContextMenu');
            if (!menu) return;
            const t = this.normalizeTarget(target);
            if (!t) {
                console.warn('[MobileContextMenu] No valid target');
                return;
            }
            if (window.getSelection) {
                const selection = window.getSelection();
                if (selection && selection.rangeCount) selection.removeAllRanges();
            }
            const legacy = document.getElementById('customContextMenu');
            if (legacy) legacy.style.display = 'none';

            this.currentTarget = t;
            if (t.type === 'song') {
                this.currentSongId = t.songId || t.value;
                menu.dataset.songId = this.currentSongId;
            } else {
                this.currentSongId = null;
                delete menu.dataset.songId;
            }

            menu.querySelectorAll('li[data-targets]').forEach(li => {
                const targets = (li.dataset.targets || '').split(/\s+/).filter(Boolean);
                li.hidden = !targets.includes(t.type);
            });

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
            this.currentTarget = null;
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

        fetchCollectionSongIds(type, value, albumArtist) {
            const profileId = JMedia.Helpers.getActiveProfileId();
            const params = new URLSearchParams();
            params.set('type', type);
            params.set('value', value == null ? '' : String(value));
            if (albumArtist) params.set('albumArtist', albumArtist);
            const url = `/api/music/ui/collection-song-ids/${profileId}?${params.toString()}`;
            return window.fetch(url)
                .then(response => {
                    if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
                    return response.json();
                })
                .then(data => {
                    if (data && Array.isArray(data.songIds)) return data.songIds;
                    return [];
                });
        }

        handleMenuClick(e) {
            const li = e.target.closest('li');
            if (!li) return;
            const action = li.dataset.action;
            const t = this.currentTarget || (this.currentSongId ? { type: 'song', value: this.currentSongId, songId: this.currentSongId, element: null } : null);
            if (!t) {
                console.warn('[MobileContextMenu] No valid target found');
                return;
            }
            this.currentTarget = t;

            const isSong = t.type === 'song';
            const songId = isSong ? (t.songId || t.value) : null;

            switch (action) {
                case 'queue':
                    if (!songId) { console.warn('[MobileContextMenu] No valid song ID found'); break; }
                    if (window.htmx) {
                        const profileId = JMedia.Helpers.getActiveProfileId();
                        htmx.ajax('POST', `/api/music/queue/add/${profileId}/${songId}`, {
                            handler: function () {
                                console.log(`Song ${songId} added to queue.`);
                                if (window.showToast) window.showToast('Song added to queue', 'success');
                                window.dispatchEvent(new CustomEvent('queueChanged', {
                                    detail: { queueSize: (JMedia.PlaybackApi && JMedia.PlaybackApi.getQueueSize ? JMedia.PlaybackApi.getQueueSize() : 0), queueChanged: true, queueLengthChanged: true }
                                }));
                            }
                        });
                    }
                    break;
                case 'playlist':
                    this.openPlaylistSubmenu({ songId: songId });
                    break;
                case 'rescan':
                    if (!songId) { console.warn('[MobileContextMenu] No valid song ID found'); break; }
                    JMedia.PlaybackApi.rescanSong(songId);
                    break;
                case 'queue-similar':
                    if (!songId) { console.warn('[MobileContextMenu] No valid song ID found'); break; }
                    JMedia.PlaybackApi.queueSimilar(songId);
                    break;
                case 'enrich':
                    if (!songId) { console.warn('[MobileContextMenu] No valid song ID found'); break; }
                    JMedia.PlaybackApi.enrichMetadata(songId);
                    break;
                case 'delete':
                    if (!songId) { console.warn('[MobileContextMenu] No valid song ID found'); break; }
                    JMedia.PlaybackApi.deleteSong(songId);
                    break;
                case 'play-album': {
                    if (t.firstSongId) {
                        if (window.playAlbum) window.playAlbum(t.firstSongId);
                    } else {
                        const profileId = JMedia.Helpers.getActiveProfileId();
                        this.fetchCollectionSongIds('album', t.value, t.albumArtist)
                            .then(ids => {
                                if (ids && ids.length) {
                                    JMedia.PlaybackApi.select(ids[0], profileId);
                                } else {
                                    console.warn('[MobileContextMenu] No songs found for album');
                                }
                            })
                            .catch(err => {
                                console.error('[MobileContextMenu] Failed to play album:', err);
                                if (window.showToast) window.showToast('Failed to play album', 'error');
                            });
                    }
                    break;
                }
                case 'play-genre': {
                    const profileId = JMedia.Helpers.getActiveProfileId();
                    this.fetchCollectionSongIds('genre', t.value)
                        .then(ids => {
                            if (ids && ids.length) {
                                JMedia.PlaybackApi.select(ids[0], profileId);
                            } else {
                                console.warn('[MobileContextMenu] No songs found for genre');
                            }
                        })
                        .catch(err => {
                            console.error('[MobileContextMenu] Failed to play genre:', err);
                            if (window.showToast) window.showToast('Failed to play genre', 'error');
                        });
                    break;
                }
                case 'queue-album':
                case 'queue-genre': {
                    const type = action === 'queue-album' ? 'album' : 'genre';
                    const albumArtist = type === 'album' ? t.albumArtist : null;
                    const profileId = JMedia.Helpers.getActiveProfileId();
                    this.fetchCollectionSongIds(type, t.value, albumArtist)
                        .then(ids => {
                            if (!ids || !ids.length) {
                                console.warn('[MobileContextMenu] No songs found to queue');
                                return null;
                            }
                            return window.fetch(`/api/music/queue/add-many/${profileId}`, {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ songIds: ids })
                            }).then(res => {
                                if (!res.ok) throw new Error(`HTTP error! status: ${res.status}`);
                                return res;
                            });
                        })
                        .then(res => {
                            if (!res) return;
                            if (window.showToast) window.showToast('Added to queue', 'success');
                            window.dispatchEvent(new CustomEvent('queueChanged', {
                                detail: { queueSize: (JMedia.PlaybackApi && JMedia.PlaybackApi.getQueueSize ? JMedia.PlaybackApi.getQueueSize() : 0), queueChanged: true, queueLengthChanged: true }
                            }));
                        })
                        .catch(err => {
                            console.error('[MobileContextMenu] Failed to queue collection:', err);
                            if (window.showToast) window.showToast('Failed to queue', 'error');
                        });
                    break;
                }
                case 'playlist-album':
                case 'playlist-genre': {
                    const type = action === 'playlist-album' ? 'album' : 'genre';
                    const albumArtist = type === 'album' ? t.albumArtist : null;
                    this.fetchCollectionSongIds(type, t.value, albumArtist)
                        .then(ids => {
                            if (!ids || !ids.length) {
                                console.warn('[MobileContextMenu] No songs found for playlist');
                                return;
                            }
                            this.openPlaylistSubmenu({ songIds: ids, label: type });
                        })
                        .catch(err => {
                            console.error('[MobileContextMenu] Failed to load collection songs:', err);
                            if (window.showToast) window.showToast('Failed to load songs', 'error');
                        });
                    break;
                }
                case 'play-playlist': {
                    const profileId = JMedia.Helpers.getActiveProfileId();
                    const playlistId = t.playlistId || t.value;
                    if (!playlistId) {
                        console.warn('[MobileContextMenu] No playlist id to play');
                        break;
                    }
                    window.fetch(`/api/music/playback/queue-all/${profileId}/${playlistId}`, { method: 'POST' })
                        .then(res => {
                            if (!res.ok) throw new Error(`HTTP error! status: ${res.status}`);
                            return res.json().catch(() => ({}));
                        })
                        .then(() => {
                            if (window.showToast) window.showToast('Playing playlist', 'success');
                            window.dispatchEvent(new CustomEvent('queueChanged', {
                                detail: { queueSize: (JMedia.PlaybackApi && JMedia.PlaybackApi.getQueueSize ? JMedia.PlaybackApi.getQueueSize() : 0), queueChanged: true, queueLengthChanged: true }
                            }));
                        })
                        .catch(err => {
                            console.error('[MobileContextMenu] Failed to play playlist:', err);
                            if (window.showToast) window.showToast('Failed to play playlist', 'error');
                        });
                    break;
                }
                case 'queue-playlist': {
                    const profileId = JMedia.Helpers.getActiveProfileId();
                    const playlistId = t.playlistId || t.value;
                    if (!playlistId) {
                        console.warn('[MobileContextMenu] No playlist id to queue');
                        break;
                    }
                    window.fetch(`/api/music/playlists/${playlistId}/song-ids`)
                        .then(res => {
                            if (!res.ok) throw new Error(`HTTP error! status: ${res.status}`);
                            return res.json();
                        })
                        .then(data => {
                            const ids = data && Array.isArray(data.songIds) ? data.songIds : [];
                            if (!ids.length) {
                                console.warn('[MobileContextMenu] Playlist has no songs to queue');
                                return null;
                            }
                            return window.fetch(`/api/music/queue/add-many/${profileId}`, {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ songIds: ids })
                            }).then(res => {
                                if (!res.ok) throw new Error(`HTTP error! status: ${res.status}`);
                                return res;
                            });
                        })
                        .then(res => {
                            if (!res) return;
                            if (window.showToast) window.showToast('Playlist added to queue', 'success');
                            window.dispatchEvent(new CustomEvent('queueChanged', {
                                detail: { queueSize: (JMedia.PlaybackApi && JMedia.PlaybackApi.getQueueSize ? JMedia.PlaybackApi.getQueueSize() : 0), queueChanged: true, queueLengthChanged: true }
                            }));
                        })
                        .catch(err => {
                            console.error('[MobileContextMenu] Failed to queue playlist:', err);
                            if (window.showToast) window.showToast('Failed to queue playlist', 'error');
                        });
                    break;
                }
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
                    if (window.showToast) window.showToast('Metadata updated successfully', 'success');
                    if (window.jmediaMobile && window.jmediaMobile.loadInitialContent) {
                        window.jmediaMobile.loadInitialContent();
                    }
                })
                .catch(error => {
                    console.error('[MobileContextMenu] Error updating metadata:', error);
                    if (window.showToast) window.showToast('Failed to update metadata', 'error');
                });
        }

        openPlaylistSubmenu(target) {
            if (typeof target === 'string' || typeof target === 'number') {
                target = { songId: String(target) };
            }
            target = target || {};
            console.log('[MobileContextMenu] Opening playlist submenu for target:', target);
            const profileId = JMedia.Helpers.getActiveProfileId();
            window.fetch(`/api/music/playlists/${profileId}`)
                .then(response => response.json())
                .then(data => {
                    const playlists = data.data || data;
                    this.showPlaylistSelection(playlists, target);
                })
                .catch(error => {
                    console.error('[MobileContextMenu] Error fetching playlists:', error);
                    if (window.showToast) window.showToast('Failed to load playlists', 'error');
                });
        }

        showPlaylistSelection(playlists, target) {
            if (typeof target === 'string' || typeof target === 'number') {
                target = { songId: String(target) };
            }
            target = target || {};
            const modal = document.createElement('div');
            if (target.songId) {
                modal.dataset.songId = target.songId;
            }
            if (target.songIds && target.songIds.length) {
                modal.dataset.songIds = JSON.stringify(target.songIds);
            }
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
                    this.addTargetToPlaylist(playlistId, target);
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

        addTargetToPlaylist(playlistId, target) {
            if (typeof target === 'string' || typeof target === 'number') {
                target = { songId: String(target) };
            }
            target = target || {};
            if (target.songIds && Array.isArray(target.songIds) && target.songIds.length) {
                console.log('[MobileContextMenu] Adding songs to playlist:', playlistId, target.songIds.length);
                window.fetch(`/api/music/playlists/${playlistId}/songs/bulk`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ songIds: target.songIds })
                })
                    .then(response => {
                        if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
                        return response.json().catch(() => ({}));
                    })
                    .then(() => {
                        console.log('[MobileContextMenu] Songs added to playlist successfully');
                        if (window.showToast) window.showToast('Songs added to playlist', 'success');
                    })
                    .catch(error => {
                        console.error('[MobileContextMenu] Error adding songs to playlist:', error);
                        if (window.showToast) window.showToast('Failed to add to playlist', 'error');
                    });
            } else {
                const songId = target.songId || target.value;
                this.addSongToPlaylistHandler(playlistId, songId);
            }
        }

        addSongToPlaylistHandler(playlistId, songId) {
            console.log('[MobileContextMenu] Adding song to playlist:', playlistId, songId);
            if (typeof addSongToPlaylist === 'function') {
                addSongToPlaylist(playlistId, songId)
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
                coverContainer.addEventListener('touchstart', this.handleTouchStart.bind(this), {passive: false});
                ['touchend', 'touchmove', 'touchcancel'].forEach(event => {
                    coverContainer.addEventListener(event, this.cancelTouch.bind(this), {passive: false});
                });
                coverContainer.addEventListener('contextmenu', this.handleContextMenu.bind(this));
                console.log('[CoverImageContextMenu] Cover image long press and right-click initialized');
            }
        }

        handleTouchStart(e) {
            const coverContainer = document.getElementById('songCoverImageContainer');
            if (coverContainer) {
                coverContainer.classList.add('long-press-active');
            }
            this.timerId = setTimeout(() => {
                const songId = JMedia.PlaybackApi.getCurrentSongId();
                if (songId) {
                    this.contextMenu.showContextMenu(songId);
                } else {
                    if (window.showToast) window.showToast('No song currently playing', 'info');
                }
            }, this.duration);
        }

        handleContextMenu(e) {
            e.preventDefault();
            const songId = JMedia.PlaybackApi.getCurrentSongId();
            if (!songId) {
                if (window.showToast) window.showToast('No song currently playing', 'info');
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

    function tryInitContextMenu(retries = 30) {
        if (document.getElementById('mobileContextMenu')) {
            window.mobileContextMenu = new MobileContextMenu();
            window.coverImageContextMenu = new CoverImageContextMenu(window.mobileContextMenu);
            console.log('[MobileContextMenu] All context menu features initialized');
        } else if (retries > 0) {
            setTimeout(() => tryInitContextMenu(retries - 1), 200);
        } else {
            console.warn('[MobileContextMenu] Context menu element not found after retries');
        }
    }

    if (document.readyState === 'complete' || document.readyState === 'interactive') {
        tryInitContextMenu();
    } else {
        document.addEventListener('DOMContentLoaded', () => tryInitContextMenu());
    }

})(window);
