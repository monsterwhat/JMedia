/**
 * MusicDragDrop - mobile-first "press and drag" destinations drawer.
 *
 * A horizontal drag on a song row, album card, or playlist card opens a
 * side drawer with Playlists, Albums, and New playlist / New album targets.
 * Vertical movement is left to the page so normal scrolling still works.
 */
(function (window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};
    const THRESHOLD = 12;
    const HOLD_MS = 350;
    const HOLD_SLOP = 10;
    const SOURCE_SELECTOR = '.mobile-song-item[data-song-id], .mobile-album-card, .mobile-playlist-card';
    const INTERACTIVE_SELECTOR = 'button, a, input, textarea, select, .mobile-queue-remove, .mobile-context-menu';
    const DRAG_BODY_CLASS = 'music-dragging';

    const state = {
        pending: null,
        holdTimer: null,
        lastDragEndAt: 0,
        session: null,
        picker: null,
        playlists: null,
        albums: [],
        albumSearchTimer: null,
        writeTags: false,
        drawer: null,
        overlay: null,
        suppressClick: null,
        autoScrollTimer: null
    };

    try {
        state.writeTags = localStorage.getItem('musicDragWriteTags') === 'true';
    } catch (err) {
        log('Could not read tag preference', err);
    }

    function log(message, error) {
        if (window.Helpers && typeof window.Helpers.log === 'function') {
            window.Helpers.log('[MusicDragDrop] ' + message, error || '');
        } else if (error) {
            console.warn('[MusicDragDrop] ' + message, error);
        }
    }

    function toast(message, type) {
        if (typeof window.showToast === 'function') {
            window.showToast(message, type || 'success');
        }
    }

    function profileId() {
        if (window.JMedia && JMedia.Helpers && typeof JMedia.Helpers.getActiveProfileId === 'function') {
            return JMedia.Helpers.getActiveProfileId();
        }
        return window.globalActiveProfileId || localStorage.getItem('activeProfileId');
    }

    function textOf(el) {
        return el ? (el.textContent || '').trim() : '';
    }

    function fetchJson(url, options) {
        return fetch(url, Object.assign({ headers: { 'Accept': 'application/json' } }, options || {}))
            .then(function (res) {
                if (!res.ok) throw new Error('HTTP ' + res.status + ' for ' + url);
                return res.json();
            });
    }

    // ---------------------------------------------------------------- source

    function sourceFor(el) {
        if (!el || !el.classList) return null;
        if (el.classList.contains('mobile-song-item') && el.dataset.songId) {
            return {
                type: 'song',
                songId: String(el.dataset.songId),
                label: textOf(el.querySelector('.mobile-song-title')) || 'Song',
                count: 1
            };
        }
        if (el.classList.contains('mobile-album-card')) {
            const name = el.dataset.albumName || 'Album';
            return {
                type: 'album',
                albumName: name,
                albumArtist: el.dataset.albumArtist || '',
                label: 'Album · ' + name,
                count: Number(el.dataset.songCount) || null
            };
        }
        if (el.classList.contains('mobile-playlist-card')) {
            const name = el.dataset.playlistName || 'Playlist';
            return {
                type: 'playlist',
                playlistId: String(el.dataset.playlistId || ''),
                label: 'Playlist · ' + name,
                count: Number(el.dataset.songCount) || null
            };
        }
        return null;
    }

    function resolveSongIds(source) {
        if (!source) return Promise.resolve([]);
        if (source.type === 'song') return Promise.resolve([source.songId]);
        if (source._idsPromise) return source._idsPromise;

        const pid = profileId();
        if (source.type === 'album') {
            const url = '/api/music/ui/collection-song-ids/' + pid
                + '?type=album&value=' + encodeURIComponent(source.albumName)
                + '&albumArtist=' + encodeURIComponent(source.albumArtist || '');
            source._idsPromise = fetchJson(url).then(function (data) {
                return data.songIds || (data.data && data.data.songIds) || [];
            });
        } else if (source.type === 'playlist') {
            const url = '/api/music/playlists/' + source.playlistId + '/song-ids';
            source._idsPromise = fetchJson(url).then(function (data) {
                return data.songIds || (data.data && data.data.songIds) || (Array.isArray(data) ? data : []);
            });
        } else {
            source._idsPromise = Promise.resolve([]);
        }
        return source._idsPromise;
    }

    // ---------------------------------------------------------------- gesture

    function onPointerDown(e) {
        if (e.button !== undefined && e.button !== 0) return;
        if (e.isPrimary === false) return;
        const target = e.target;
        if (!target || !target.closest) return;
        if (target.closest(INTERACTIVE_SELECTOR)) return;

        const el = target.closest(SOURCE_SELECTOR);
        if (!el) return;
        if (el.classList.contains('mobile-queue-item')) return;

        const source = sourceFor(el);
        if (!source) return;

        state.pending = {
            pointerId: e.pointerId,
            x: e.clientX,
            y: e.clientY,
            el: el,
            source: source
        };

        // Songs are lifted by HOLDING, not by a quick horizontal swipe: this
        // makes dragging deliberate and leaves the double-tap free for the
        // context menu. Cards keep the movement-based drag.
        if (source.type === 'song') {
            const pointerId = e.pointerId;
            state.holdTimer = setTimeout(function () {
                state.holdTimer = null;
                if (state.session) return;
                const p = state.pending;
                if (!p || p.pointerId !== pointerId) return;
                const menu = document.getElementById('mobileContextMenu');
                if (menu && menu.getAttribute('aria-hidden') === 'false') return;
                state.pending = null;
                startDrag(p, { clientX: p.x, clientY: p.y, pointerId: pointerId });
            }, HOLD_MS);
        }
    }

    function onPointerMove(e) {
        if (state.session) {
            if (e.pointerId !== state.session.pointerId) return;
            e.preventDefault();
            moveGhost(e.clientX, e.clientY);
            updateDropTarget(e.clientX, e.clientY);
            return;
        }

        const pending = state.pending;
        if (!pending || e.pointerId !== pending.pointerId) return;

        const menu = document.getElementById('mobileContextMenu');
        if (menu && menu.getAttribute('aria-hidden') === 'false') {
            cancelHold();
            state.pending = null;
            return;
        }

        const dx = e.clientX - pending.x;
        const dy = e.clientY - pending.y;

        // Waiting for the lift: any real movement means this is a scroll/tap,
        // not a drag intent. Songs do not start dragging on a swipe.
        if (state.holdTimer) {
            if (Math.abs(dx) > HOLD_SLOP || Math.abs(dy) > HOLD_SLOP) {
                cancelHold();
                state.pending = null;
            }
            return;
        }

        if (Math.abs(dy) > THRESHOLD && Math.abs(dy) >= Math.abs(dx)) {
            // Vertical gesture: let the page scroll.
            state.pending = null;
            return;
        }
        if (Math.abs(dx) > THRESHOLD && Math.abs(dx) > Math.abs(dy)) {
            e.preventDefault();
            startDrag(pending, e);
        }
    }

    function cancelHold() {
        if (state.holdTimer) {
            clearTimeout(state.holdTimer);
            state.holdTimer = null;
        }
    }

    function onPointerUp(e) {
        cancelHold();
        if (state.session && e.pointerId === state.session.pointerId) {
            finishDrag(e);
            return;
        }
        state.pending = null;
    }

    function onPointerCancel(e) {
        cancelHold();
        if (state.session && e.pointerId === state.session.pointerId) {
            cleanupDrag();
        }
        state.pending = null;
    }

    function startDrag(pending, e) {
        state.pending = null;
        state.suppressNextClick();

        cancelLongPress();
        const menu = document.getElementById('mobileContextMenu');
        if (menu) menu.setAttribute('aria-hidden', 'true');

        const ghost = buildGhost(pending.el);
        document.body.appendChild(ghost);
        document.body.classList.add(DRAG_BODY_CLASS);
        pending.el.classList.add('drag-source');

        state.session = {
            pointerId: pending.pointerId,
            source: pending.source,
            sourceEl: pending.el,
            ghost: ghost,
            lastOption: null,
            startTime: Date.now()
        };

        ensureDrawer();
        openDrawer(pending.source);
        moveGhost(e.clientX, e.clientY);
        updateDropTarget(e.clientX, e.clientY);

        if (navigator.vibrate) {
            try { navigator.vibrate(8); } catch (err) { log('Vibration failed', err); }
        }

        if (pending.source.type !== 'song') {
            resolveSongIds(pending.source).then(function (ids) {
                pending.source.count = ids.length;
                updateSourceLabel(pending.source);
                refreshPreview();
            }).catch(function (err) {
                log('Could not resolve source songs', err);
            });
        }
    }

    function buildGhost(el) {
        const rect = el.getBoundingClientRect();
        const clone = el.cloneNode(true);
        clone.classList.add('music-drag-ghost');
        clone.classList.remove('drag-source', 'long-press-active');
        clone.style.width = rect.width + 'px';
        clone.style.height = rect.height + 'px';
        clone.style.left = '0';
        clone.style.top = '0';
        clone._dragWidth = rect.width;
        clone._dragHeight = rect.height;
        return clone;
    }

    function moveGhost(x, y) {
        const s = state.session;
        if (!s || !s.ghost) return;
        const w = s.ghost._dragWidth || 240;
        const h = s.ghost._dragHeight || 64;
        s.ghost.style.transform = 'translate3d(' + (x - w / 2) + 'px,' + (y - h / 2) + 'px,0) scale(1.02)';
    }

    function finishDrag(e) {
        const session = state.session;
        if (!session) return;
        const option = optionAt(e.clientX, e.clientY);
        const source = session.source;
        cleanupDrag();
        if (option) {
            performDrop(source, option);
        }
    }

    function cleanupDrag() {
        const session = state.session;
        if (session) {
            if (session.ghost && session.ghost.parentNode) {
                session.ghost.parentNode.removeChild(session.ghost);
            }
            if (session.sourceEl) session.sourceEl.classList.remove('drag-source');
        }
        document.querySelectorAll('.music-drop-item.is-drop-over').forEach(function (el) {
            el.classList.remove('is-drop-over');
        });
        document.body.classList.remove(DRAG_BODY_CLASS);
        closeDrawer();
        state.session = null;
        state.lastDragEndAt = Date.now();
    }

    function cancelLongPress() {
        const menu = window.mobileContextMenu;
        if (!menu) return;
        if (menu.timerId) {
            clearTimeout(menu.timerId);
            menu.timerId = null;
        }
        if (menu.activeElement) {
            menu.activeElement.classList.remove('long-press-active');
            menu.activeElement = null;
        }
    }

    function optionAt(x, y) {
        if (typeof document.elementFromPoint !== 'function') return null;
        const el = document.elementFromPoint(x, y);
        return el && el.closest ? el.closest('[data-drop-kind]') : null;
    }

    function updateDropTarget(x, y) {
        const session = state.session;
        if (!session) return;
        const option = optionAt(x, y);
        if (session.lastOption !== option) {
            if (session.lastOption) session.lastOption.classList.remove('is-drop-over');
            if (option) option.classList.add('is-drop-over');
            session.lastOption = option;
            refreshPreview();
        }
        autoScrollDrawer(y);
    }

    function refreshPreview() {
        const preview = document.getElementById('musicDropPreview');
        if (!preview) return;
        const session = state.session;
        const source = session ? session.source : state.picker;
        if (!source) return;
        const option = session ? session.lastOption : null;
        if (!option) {
            preview.textContent = state.picker
                ? 'Tap a playlist, an album, or New'
                : 'Drop on a playlist, an album, or New';
            return;
        }
        const n = source.count;
        const songs = n ? n + ' song' + (n === 1 ? '' : 's') : 'songs';
        const kind = option.dataset.dropKind;
        if (kind === 'playlist') {
            preview.textContent = 'Add ' + songs + ' to ' + (option.dataset.playlistName || 'this playlist');
        } else if (kind === 'album') {
            preview.textContent = 'Move ' + songs + ' into ' + (option.dataset.albumName || 'this album');
        } else if (kind === 'new-playlist') {
            preview.textContent = 'Create a playlist with ' + songs;
        } else if (kind === 'new-album') {
            preview.textContent = 'Create an album with ' + songs;
        } else {
            preview.textContent = 'Drop here';
        }
    }

    // ---------------------------------------------------------------- drawer

    function ensureDrawer() {
        if (state.drawer) return;
        const overlay = document.createElement('div');
        overlay.className = 'music-drop-overlay';
        overlay.addEventListener('click', closeDrawer);

        const drawer = document.createElement('aside');
        drawer.className = 'music-drop-drawer';
        drawer.setAttribute('aria-hidden', 'true');
        drawer.innerHTML = ''
            + '<div class="music-drop-header">'
            + '  <div><div class="music-drop-kicker">Move</div>'
            + '  <div class="music-drop-source" id="musicDropSource">Song</div></div>'
            + '  <button type="button" class="music-drop-close" aria-label="Cancel">&#215;</button>'
            + '</div>'
            + '<div class="music-drop-preview" id="musicDropPreview">Drop on a playlist, an album, or New</div>'
            + '<div class="music-drop-hint" id="musicDropHint">Tip: long-press a song, album, or playlist, then Move / Add to&hellip; to search albums.</div>'
            + '<div class="music-drop-body">'
            + '  <section class="music-drop-section">'
            + '    <div class="music-drop-section-head"><span>Playlists</span>'
            + '      <button type="button" class="music-drop-new" data-drop-kind="new-playlist">+ New</button></div>'
            + '    <div class="music-drop-list" id="musicDropPlaylists"></div>'
            + '  </section>'
            + '  <section class="music-drop-section">'
            + '    <div class="music-drop-section-head"><span>Albums</span>'
            + '      <button type="button" class="music-drop-new" data-drop-kind="new-album">+ New</button></div>'
            + '    <input type="search" class="music-drop-search" id="musicDropAlbumSearch" placeholder="Search albums...">'
            + '    <div class="music-drop-list" id="musicDropAlbums"></div>'
            + '  </section>'
            + '</div>'
            + '<div class="music-drop-footer"><button type="button" class="music-drop-cancel" id="musicDropCancel">Cancel</button></div>';

        document.body.appendChild(overlay);
        document.body.appendChild(drawer);
        state.drawer = drawer;
        state.overlay = overlay;

        drawer.querySelector('.music-drop-close').addEventListener('click', closeDrawer);
        drawer.querySelector('#musicDropCancel').addEventListener('click', closeDrawer);
        drawer.querySelector('#musicDropAlbumSearch').addEventListener('input', function (e) {
            const value = e.target.value || '';
            clearTimeout(state.albumSearchTimer);
            state.albumSearchTimer = setTimeout(function () {
                loadAlbums(value);
            }, 250);
        });
        // New/Cancel targets are usable with a click too (accessibility), and
        // in picker mode every destination is tap-selectable because there is
        // no pointer capture / active drag.
        drawer.addEventListener('click', function (e) {
            const option = e.target.closest('[data-drop-kind]');
            if (!option) return;
            if (state.picker) {
                e.preventDefault();
                e.stopPropagation();
                const source = state.picker;
                closeDrawer();
                performDrop(source, option);
                return;
            }
            const kind = option.dataset.dropKind;
            if (kind === 'new-playlist' || kind === 'new-album') {
                e.preventDefault();
                e.stopPropagation();
                if (state.session) {
                    const source = state.session.source;
                    cleanupDrag();
                    performDrop(source, option);
                }
            }
        });
    }

    function openDrawer(source, asPicker) {
        ensureDrawer();
        const drawer = state.drawer;
        const overlay = state.overlay;
        if (!drawer || !overlay) return;
        state.picker = asPicker ? source : null;
        drawer.classList.toggle('picker-mode', !!asPicker);
        drawer.classList.add('active');
        overlay.classList.add('active');
        drawer.setAttribute('aria-hidden', 'false');
        updateSourceLabel(source);
        refreshPreview();

        const search = drawer.querySelector('#musicDropAlbumSearch');
        if (search) {
            search.style.display = asPicker ? '' : 'none';
            search.value = '';
        }
        const hint = drawer.querySelector('#musicDropHint');
        if (hint) hint.style.display = asPicker ? 'none' : '';

        loadPlaylists();
        loadAlbums('');
    }

    function openPicker(source) {
        if (!source) return;
        cleanupDrag();
        ensureDrawer();
        openDrawer(source, true);
        if (source.type !== 'song') {
            resolveSongIds(source).then(function (ids) {
                source.count = ids.length;
                updateSourceLabel(source);
                refreshPreview();
            }).catch(function (err) {
                log('Could not resolve picker source', err);
            });
        }
    }

    function openPickerFromTarget(target) {
        if (!target) return;
        let source = null;
        if (target.type === 'song') {
            source = sourceFor(target.element) || {
                type: 'song',
                songId: String(target.songId || target.value || ''),
                label: 'Song',
                count: 1
            };
        } else if (target.type === 'album') {
            const name = target.value || 'Album';
            source = {
                type: 'album',
                albumName: name,
                albumArtist: target.albumArtist || '',
                label: 'Album · ' + name,
                count: null
            };
        } else if (target.type === 'playlist') {
            const name = target.name || 'Playlist';
            source = {
                type: 'playlist',
                playlistId: String(target.playlistId || target.value || ''),
                label: 'Playlist · ' + name,
                count: null
            };
        }
        if (!source) {
            toast('Nothing to move', 'warning');
            return;
        }
        openPicker(source);
    }

    function closeDrawer() {
        const drawer = state.drawer;
        const overlay = state.overlay;
        if (!drawer || !overlay) return;
        state.picker = null;
        drawer.classList.remove('picker-mode');
        drawer.classList.remove('active');
        overlay.classList.remove('active');
        drawer.setAttribute('aria-hidden', 'true');
    }

    function updateSourceLabel(source) {
        const label = document.getElementById('musicDropSource');
        if (!label || !source) return;
        const count = source.count ? ' · ' + source.count + ' song' + (source.count === 1 ? '' : 's') : '';
        label.textContent = source.label + count;
    }

    function skeleton() {
        return '<div class="music-drop-skeleton"></div><div class="music-drop-skeleton"></div><div class="music-drop-skeleton"></div>';
    }

    function loadPlaylists() {
        const list = document.getElementById('musicDropPlaylists');
        if (!list) return;
        if (state.playlists) {
            renderPlaylists(list, state.playlists);
            return;
        }
        list.innerHTML = skeleton();
        fetchJson('/api/music/playlists/' + profileId())
            .then(function (data) {
                const playlists = data.data || data || [];
                state.playlists = Array.isArray(playlists) ? playlists : [];
                renderPlaylists(list, state.playlists);
            })
            .catch(function (err) {
                log('Could not load playlists', err);
                list.innerHTML = '<div class="music-drop-empty">Could not load playlists</div>';
            });
    }

    function renderPlaylists(list, playlists) {
        list.innerHTML = '';
        if (!playlists.length) {
            list.innerHTML = '<div class="music-drop-empty">No playlists yet</div>';
            return;
        }
        playlists.forEach(function (p) {
            const item = document.createElement('button');
            item.type = 'button';
            item.className = 'music-drop-item';
            item.dataset.dropKind = 'playlist';
            item.dataset.playlistId = String(p.id);
            item.dataset.playlistName = p.name || 'Playlist';
            const icon = document.createElement('i');
            icon.className = 'pi pi-list';
            const name = document.createElement('span');
            name.className = 'music-drop-item-name';
            name.textContent = p.name || 'Playlist';
            item.appendChild(icon);
            item.appendChild(name);
            list.appendChild(item);
        });
    }

    function loadAlbums(search) {
        const list = document.getElementById('musicDropAlbums');
        if (!list) return;
        list.innerHTML = skeleton();
        const url = '/api/music/albums?limit=200&search=' + encodeURIComponent(search || '');
        fetchJson(url)
            .then(function (data) {
                const payload = data.data || data;
                state.albums = payload.items || [];
                renderAlbums(list, state.albums);
            })
            .catch(function (err) {
                log('Could not load albums', err);
                list.innerHTML = '<div class="music-drop-empty">Could not load albums</div>';
            });
    }

    function renderAlbums(list, albums) {
        list.innerHTML = '';
        if (!albums.length) {
            list.innerHTML = '<div class="music-drop-empty">No albums found</div>';
            return;
        }
        albums.forEach(function (a) {
            const item = document.createElement('button');
            item.type = 'button';
            item.className = 'music-drop-item music-drop-album';
            item.dataset.dropKind = 'album';
            item.dataset.albumName = a.album || '';
            item.dataset.albumArtist = a.albumArtist || '';
            item.dataset.songCount = a.songCount || '';

            const img = document.createElement('img');
            img.src = '/api/music/stream/artwork/' + a.firstSongId;
            img.alt = '';
            img.loading = 'lazy';

            const info = document.createElement('div');
            info.className = 'music-drop-item-info';
            const name = document.createElement('div');
            name.className = 'music-drop-item-name';
            name.textContent = a.album || 'Album';
            const meta = document.createElement('div');
            meta.className = 'music-drop-item-meta';
            meta.textContent = (a.albumArtist || 'Unknown Artist') + (a.songCount ? ' · ' + a.songCount + ' songs' : '');
            info.appendChild(name);
            info.appendChild(meta);

            item.appendChild(img);
            item.appendChild(info);
            list.appendChild(item);
        });
    }

    // ------------------------------------------------------------- drop logic

    function performDrop(source, option) {
        if (!source || !option) return;
        const kind = option.dataset.dropKind;

        if (kind === 'cancel') {
            return;
        }
        if (kind === 'playlist') {
            dropOnPlaylist(source, option);
        } else if (kind === 'album') {
            dropOnAlbum(source, option);
        } else if (kind === 'new-playlist') {
            createPlaylistFromSource(source);
        } else if (kind === 'new-album') {
            createAlbumFromSource(source);
        }
    }

    function samePlaylist(source, option) {
        return source.type === 'playlist'
            && String(source.playlistId) === String(option.dataset.playlistId);
    }

    function sameAlbum(source, option) {
        return source.type === 'album'
            && (source.albumName || '') === (option.dataset.albumName || '')
            && (source.albumArtist || '') === (option.dataset.albumArtist || '');
    }

    function dropOnPlaylist(source, option) {
        if (samePlaylist(source, option)) {
            toast('Song is already in this playlist', 'info');
            return;
        }
        resolveSongIds(source).then(function (ids) {
            if (!ids.length) {
                toast('No songs to add', 'warning');
                return;
            }
            const name = option.dataset.playlistName || 'this playlist';
            const proceed = source.type === 'song'
                ? Promise.resolve(true)
                : confirmModal('Add ' + ids.length + ' song' + (ids.length === 1 ? '' : 's') + ' to “' + name + '”?');
            proceed.then(function (ok) {
                if (!ok) return;
                postJson('/api/music/playlists/' + option.dataset.playlistId + '/songs/bulk', { songIds: ids })
                    .then(function () {
                        toast('Added ' + ids.length + ' song' + (ids.length === 1 ? '' : 's') + ' to ' + name);
                        refreshAfterDrop('playlist', option.dataset.playlistId);
                    })
                    .catch(function (err) {
                        log('Could not add to playlist', err);
                        toast('Could not add to playlist', 'error');
                    });
            });
        }).catch(function (err) {
            log('Could not resolve songs for playlist', err);
            toast('Could not load songs', 'error');
        });
    }

    function dropOnAlbum(source, option) {
        if (sameAlbum(source, option)) {
            toast('Songs are already in this album', 'info');
            return;
        }
        resolveSongIds(source).then(function (ids) {
            if (!ids.length) {
                toast('No songs to move', 'warning');
                return;
            }
            const album = option.dataset.albumName || '';
            const artist = option.dataset.albumArtist || '';
            const proceed = source.type === 'song'
                ? Promise.resolve(true)
                : confirmModal('Move ' + ids.length + ' song' + (ids.length === 1 ? '' : 's') + ' into “' + album + '”?');
            proceed.then(function (ok) {
                if (!ok) return;
                promptAlbumTags(album, artist, ids.length).then(function (choice) {
                    if (!choice) return;
                    assignAlbum(ids, album, artist, choice.writeTags);
                });
            });
        }).catch(function (err) {
            log('Could not resolve songs for album', err);
            toast('Could not load songs', 'error');
        });
    }

    function createPlaylistFromSource(source) {
        resolveSongIds(source).then(function (ids) {
            if (!ids.length) {
                toast('No songs to add', 'warning');
                return;
            }
            promptModal({
                title: 'New playlist',
                fields: [{ key: 'name', label: 'Playlist name', placeholder: 'My playlist', required: true }],
                confirm: 'Create'
            }).then(function (answer) {
                if (!answer || !answer.name || !answer.name.trim()) return;
                const name = answer.name.trim();
                postJson('/api/music/playlists/', { name: name, profileId: profileId() })
                    .then(function (data) {
                        const playlist = data.data || data;
                        if (!playlist || !playlist.id) throw new Error('Playlist id missing');
                        return postJson('/api/music/playlists/' + playlist.id + '/songs/bulk', { songIds: ids })
                            .then(function () {
                                toast('Created “' + name + '” with ' + ids.length + ' song' + (ids.length === 1 ? '' : 's'));
                                refreshAfterDrop('playlist', playlist.id);
                            });
                    })
                    .catch(function (err) {
                        log('Could not create playlist', err);
                        toast('Could not create playlist', 'error');
                    });
            });
        });
    }

    function createAlbumFromSource(source) {
        resolveSongIds(source).then(function (ids) {
            if (!ids.length) {
                toast('No songs to move', 'warning');
                return;
            }
            const prefillName = source.type === 'album' ? source.albumName : '';
            const prefillArtist = source.type === 'album' ? source.albumArtist : '';
            promptAlbum(prefillName, prefillArtist).then(function (answer) {
                if (!answer || !answer.album || !answer.album.trim()) return;
                assignAlbum(ids, answer.album.trim(), (answer.albumArtist || '').trim(), answer.writeTags);
            });
        });
    }

    function assignAlbum(ids, album, artist, writeTags) {
        postJson('/api/music/albums/assign', {
            songIds: ids,
            album: album,
            albumArtist: artist,
            writeTags: !!writeTags
        }).then(function (data) {
            const result = data.data || data || {};
            let message = 'Moved ' + (result.updated || ids.length) + ' song' + (ids.length === 1 ? '' : 's') + ' to “' + album + '”';
            if (result.tagsWritten) message += ' · tags updated for ' + result.tagsWritten;
            if (result.tagFailures) message += ' · ' + result.tagFailures + ' tag write(s) failed';
            if (result.tagSkipped) message += ' · ' + result.tagSkipped + ' over the tag limit';
            toast(message, result.tagFailures ? 'warning' : 'success');
            refreshAfterDrop('album');
        }).catch(function (err) {
            log('Could not assign album', err);
            toast('Could not move songs to album', 'error');
        });
    }

    function postJson(url, body) {
        return fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
            body: JSON.stringify(body)
        }).then(function (res) {
            if (!res.ok) throw new Error('HTTP ' + res.status + ' for ' + url);
            return res.json().catch(function () { return {}; });
        });
    }

    // --------------------------------------------------------------- modals

    function modalShell(title) {
        const modal = document.createElement('div');
        modal.className = 'mobile-modal is-active music-drop-modal';
        modal.innerHTML = ''
            + '<div class="mobile-modal-overlay"></div>'
            + '<div class="mobile-modal-card">'
            + '  <header class="mobile-modal-header">'
            + '    <h3 class="mobile-modal-title"></h3>'
            + '    <button type="button" class="mobile-modal-close" aria-label="Close">&#215;</button>'
            + '  </header>'
            + '  <div class="mobile-modal-body"></div>'
            + '  <div class="music-drop-modal-actions">'
            + '    <button type="button" class="button is-light is-rounded music-drop-modal-cancel">Cancel</button>'
            + '    <button type="button" class="button is-success is-rounded music-drop-modal-ok"></button>'
            + '  </div>'
            + '</div>';
        modal.querySelector('.mobile-modal-title').textContent = title;
        document.body.appendChild(modal);
        return modal;
    }

    function promptModal(options) {
        return new Promise(function (resolve) {
            const modal = modalShell(options.title || '');
            const body = modal.querySelector('.mobile-modal-body');
            const answers = {};
            (options.fields || []).forEach(function (field) {
                const label = document.createElement('label');
                label.className = 'music-drop-field';
                const caption = document.createElement('span');
                caption.className = 'music-drop-field-label';
                caption.textContent = field.label || field.key;
                const input = document.createElement('input');
                input.type = 'text';
                input.className = 'input is-small';
                input.placeholder = field.placeholder || '';
                input.value = field.value || '';
                input.addEventListener('input', function () { answers[field.key] = input.value; });
                answers[field.key] = input.value;
                label.appendChild(caption);
                label.appendChild(input);
                body.appendChild(label);
            });

            const ok = modal.querySelector('.music-drop-modal-ok');
            ok.textContent = options.confirm || 'Save';
            const cancel = modal.querySelector('.music-drop-modal-cancel');
            const close = modal.querySelector('.mobile-modal-close');
            const overlay = modal.querySelector('.mobile-modal-overlay');

            function finish(value) {
                modal.classList.remove('is-active');
                setTimeout(function () { modal.remove(); }, 200);
                resolve(value);
            }
            ok.addEventListener('click', function () {
                const missing = (options.fields || []).some(function (f) {
                    return f.required && !(answers[f.key] || '').trim();
                });
                if (missing) return;
                finish(answers);
            });
            cancel.addEventListener('click', function () { finish(null); });
            close.addEventListener('click', function () { finish(null); });
            overlay.addEventListener('click', function () { finish(null); });
            modal.addEventListener('keydown', function (e) {
                if (e.key === 'Escape') finish(null);
                if (e.key === 'Enter') ok.click();
            });
            const first = body.querySelector('input');
            if (first) setTimeout(function () { first.focus(); }, 50);
        });
    }

    function confirmModal(message) {
        return new Promise(function (resolve) {
            const modal = modalShell('Confirm');
            const body = modal.querySelector('.mobile-modal-body');
            const p = document.createElement('p');
            p.className = 'music-drop-confirm-text';
            p.textContent = message;
            body.appendChild(p);
            const ok = modal.querySelector('.music-drop-modal-ok');
            ok.textContent = 'Confirm';
            const cancel = modal.querySelector('.music-drop-modal-cancel');
            const close = modal.querySelector('.mobile-modal-close');
            const overlay = modal.querySelector('.mobile-modal-overlay');
            function finish(value) {
                modal.classList.remove('is-active');
                setTimeout(function () { modal.remove(); }, 200);
                resolve(value);
            }
            ok.addEventListener('click', function () { finish(true); });
            cancel.addEventListener('click', function () { finish(false); });
            close.addEventListener('click', function () { finish(false); });
            overlay.addEventListener('click', function () { finish(false); });
        });
    }

    function promptAlbumTags(album, artist, count) {
        return new Promise(function (resolve) {
            const modal = modalShell('Move to “' + album + '”');
            const body = modal.querySelector('.mobile-modal-body');
            const info = document.createElement('p');
            info.className = 'music-drop-confirm-text';
            info.textContent = 'Move ' + count + ' song' + (count === 1 ? '' : 's') + ' into this album.';
            body.appendChild(info);
            const label = document.createElement('label');
            label.className = 'music-drop-check';
            const input = document.createElement('input');
            input.type = 'checkbox';
            input.checked = !!state.writeTags;
            const span = document.createElement('span');
            span.textContent = 'Also update file tags';
            label.appendChild(input);
            label.appendChild(span);
            body.appendChild(label);

            const ok = modal.querySelector('.music-drop-modal-ok');
            ok.textContent = 'Move';
            const cancel = modal.querySelector('.music-drop-modal-cancel');
            const close = modal.querySelector('.mobile-modal-close');
            const overlay = modal.querySelector('.mobile-modal-overlay');
            function finish(value) {
                modal.classList.remove('is-active');
                setTimeout(function () { modal.remove(); }, 200);
                resolve(value);
            }
            ok.addEventListener('click', function () {
                state.writeTags = input.checked;
                try { localStorage.setItem('musicDragWriteTags', String(input.checked)); } catch (err) { log('Could not save tag preference', err); }
                finish({ writeTags: input.checked });
            });
            cancel.addEventListener('click', function () { finish(null); });
            close.addEventListener('click', function () { finish(null); });
            overlay.addEventListener('click', function () { finish(null); });
        });
    }

    function promptAlbum(prefillName, prefillArtist) {
        return new Promise(function (resolve) {
            const modal = modalShell('New album');
            const body = modal.querySelector('.mobile-modal-body');
            const nameLabel = document.createElement('label');
            nameLabel.className = 'music-drop-field';
            nameLabel.innerHTML = '<span class="music-drop-field-label">Album name</span>';
            const name = document.createElement('input');
            name.type = 'text';
            name.className = 'input is-small';
            name.placeholder = 'Album name';
            name.value = prefillName || '';
            nameLabel.appendChild(name);

            const artistLabel = document.createElement('label');
            artistLabel.className = 'music-drop-field';
            artistLabel.innerHTML = '<span class="music-drop-field-label">Album artist</span>';
            const artist = document.createElement('input');
            artist.type = 'text';
            artist.className = 'input is-small';
            artist.placeholder = 'Artist';
            artist.value = prefillArtist || '';
            artistLabel.appendChild(artist);

            const checkLabel = document.createElement('label');
            checkLabel.className = 'music-drop-check';
            const check = document.createElement('input');
            check.type = 'checkbox';
            check.checked = !!state.writeTags;
            const checkText = document.createElement('span');
            checkText.textContent = 'Also update file tags';
            checkLabel.appendChild(check);
            checkLabel.appendChild(checkText);

            body.appendChild(nameLabel);
            body.appendChild(artistLabel);
            body.appendChild(checkLabel);

            const ok = modal.querySelector('.music-drop-modal-ok');
            ok.textContent = 'Create';
            const cancel = modal.querySelector('.music-drop-modal-cancel');
            const close = modal.querySelector('.mobile-modal-close');
            const overlay = modal.querySelector('.mobile-modal-overlay');
            function finish(value) {
                modal.classList.remove('is-active');
                setTimeout(function () { modal.remove(); }, 200);
                resolve(value);
            }
            ok.addEventListener('click', function () {
                if (!name.value.trim()) return;
                state.writeTags = check.checked;
                try { localStorage.setItem('musicDragWriteTags', String(check.checked)); } catch (err) { log('Could not save tag preference', err); }
                finish({ album: name.value.trim(), albumArtist: artist.value.trim(), writeTags: check.checked });
            });
            cancel.addEventListener('click', function () { finish(null); });
            close.addEventListener('click', function () { finish(null); });
            overlay.addEventListener('click', function () { finish(null); });
            setTimeout(function () { name.focus(); }, 50);
        });
    }

    // -------------------------------------------------------------- refresh

    function refreshAfterDrop(kind, targetPlaylistId) {
        state.playlists = null;
        state.albums = [];
        if (window.loadMobilePlaylists) window.loadMobilePlaylists();

        const songList = document.getElementById('mobileSongList');
        if (songList && !songList.classList.contains('is-hidden')) {
            const current = window.mobileSongListState && window.mobileSongListState.playlistId;
            if (kind === 'playlist' && current != null && String(current) === String(targetPlaylistId)) {
                window.loadMobilePlaylistSongs(current);
            }
        }
        const albumContent = document.getElementById('mobileAlbumContent');
        if (albumContent && !albumContent.classList.contains('is-hidden')) {
            if (window.switchToTab) window.switchToTab('albums');
        }
        const playlistContent = document.getElementById('mobilePlaylistContent');
        if (playlistContent && !playlistContent.classList.contains('is-hidden')) {
            if (window.switchToTab) window.switchToTab('playlists');
        }
        const familyContent = document.getElementById('mobileGenreFamilyContent');
        if (familyContent && !familyContent.classList.contains('is-hidden') && window.__currentGenreFamilyKey) {
            if (window.loadGenreFamily) window.loadGenreFamily(window.__currentGenreFamilyKey, window.__currentGenreFamilyName);
        }
    }

    // --------------------------------------------------------------- auto-scroll

    function autoScrollDrawer(y) {
        const body = document.querySelector('.music-drop-body');
        if (!body) return;
        if (y < 110) body.scrollTop -= 16;
        else if (y > window.innerHeight - 150) body.scrollTop += 16;
    }

    // --------------------------------------------------------------- click guard

    state.suppressNextClick = function () {
        if (state.suppressClick) {
            document.removeEventListener('click', state.suppressClick, true);
        }
        const guard = function (e) {
            e.preventDefault();
            e.stopPropagation();
            e.stopImmediatePropagation();
            document.removeEventListener('click', guard, true);
            state.suppressClick = null;
        };
        state.suppressClick = guard;
        document.addEventListener('click', guard, true);
        setTimeout(function () {
            if (state.suppressClick === guard) {
                document.removeEventListener('click', guard, true);
                state.suppressClick = null;
            }
        }, 700);
    };

    // --------------------------------------------------------------- init

    document.addEventListener('pointerdown', onPointerDown, { passive: true });
    document.addEventListener('pointermove', onPointerMove, { passive: false });
    document.addEventListener('pointerup', onPointerUp, { passive: true });
    document.addEventListener('pointercancel', onPointerCancel, { passive: true });
    document.addEventListener('dragstart', function (e) {
        if (e.target && e.target.closest && e.target.closest(SOURCE_SELECTOR)) {
            e.preventDefault();
        }
    });

    window.MusicDragDrop = {
        openFor: function (element) {
            const source = sourceFor(element);
            if (source) openPicker(source);
        },
        openPickerFromTarget: openPickerFromTarget,
        close: closeDrawer,
        lastDragEndAt: function () { return state.lastDragEndAt; }
    };

    log('Initialized');
})(window);
