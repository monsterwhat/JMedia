window.loadMobilePlaylists = function() {
    fetch(`/api/music/playlists/${window.globalActiveProfileId}`).then(r => r.json()).then(data => {
        const list = document.getElementById('sidebarPlaylistList');
        if (!list) return;
        list.innerHTML = (data.data || data).map(p => `<a href="javascript:void(0)" class="nav-sub-item" id="nav-playlist-${p.id}" onclick="loadMobilePlaylistSongs(${p.id})"><span>${p.name}</span></a>`).join('');
    });
};

// --- Search container visibility helpers ---
function showSearchContainer(id) {
    document.getElementById(id)?.classList.remove('is-hidden');
}
function hideSearchContainer(id) {
    document.getElementById(id)?.classList.add('is-hidden');
}
function hideAllSearchContainers() {
    hideSearchContainer('mobileMusicSearchContainer');
    hideSearchContainer('mobileQueueSearchContainer');
    hideSearchContainer('mobileHistorySearchContainer');
}

// --- Search listener setup ---
let _musicSearchTimeout = null;
let _queueSearchTimeout = null;
let _historySearchTimeout = null;

function setupMusicSearchListeners() {
    // Music search
    const musicInput = document.getElementById('musicSearchInput');
    const musicClear = document.getElementById('musicSearchClearBtn');
    if (musicInput && !musicInput._searchBound) {
        musicInput._searchBound = true;
        musicInput.addEventListener('input', function() {
            clearTimeout(_musicSearchTimeout);
            _musicSearchTimeout = setTimeout(function() {
                const query = musicInput.value.trim();
                const profileId = window.globalActiveProfileId || '1';
                window.htmx.ajax('GET', `/api/music/ui/mobile-tbody/${profileId}/0?search=${encodeURIComponent(query)}`, {
                    target: '#mobileSongList', swap: 'innerHTML'
                });
            }, 500);
        });
    }
    if (musicClear && !musicClear._clearBound) {
        musicClear._clearBound = true;
        musicClear.addEventListener('click', function() {
            if (musicInput) musicInput.value = '';
            const profileId = window.globalActiveProfileId || '1';
            window.htmx.ajax('GET', `/api/music/ui/mobile-tbody/${profileId}/0`, {
                target: '#mobileSongList', swap: 'innerHTML'
            });
        });
    }

    // Queue search
    const queueInput = document.getElementById('queueSearchInput');
    const queueClear = document.getElementById('queueSearchClearBtn');
    if (queueInput && !queueInput._searchBound) {
        queueInput._searchBound = true;
        queueInput.addEventListener('input', function() {
            clearTimeout(_queueSearchTimeout);
            _queueSearchTimeout = setTimeout(function() {
                loadQueuePage(1, undefined, queueInput.value.trim());
            }, 500);
        });
    }
    if (queueClear && !queueClear._clearBound) {
        queueClear._clearBound = true;
        queueClear.addEventListener('click', function() {
            if (queueInput) queueInput.value = '';
            loadQueuePage(1, undefined, '');
        });
    }

    // History search
    const historyInput = document.getElementById('historySearchInput');
    const historyClear = document.getElementById('historySearchClearBtn');
    if (historyInput && !historyInput._searchBound) {
        historyInput._searchBound = true;
        historyInput.addEventListener('input', function() {
            clearTimeout(_historySearchTimeout);
            _historySearchTimeout = setTimeout(function() {
                loadHistoryPage(1, undefined, historyInput.value.trim());
            }, 500);
        });
    }
    if (historyClear && !historyClear._clearBound) {
        historyClear._clearBound = true;
        historyClear.addEventListener('click', function() {
            if (historyInput) historyInput.value = '';
            loadHistoryPage(1, undefined, '');
        });
    }
}

window.loadMobilePlaylistSongs = function(id) {
    setupMusicSearchListeners();

    document.querySelectorAll('.nav-item, .nav-sub-item').forEach(el => el.classList.remove('active'));
    if (id === 0) document.getElementById('nav-music')?.classList.add('active');
    else document.getElementById(`nav-playlist-${id}`)?.classList.add('active');

    document.getElementById('mobileSongList')?.classList.remove('is-hidden');
    document.getElementById('mobileQueueContent')?.classList.add('is-hidden');
    document.getElementById('mobileHistoryContent')?.classList.add('is-hidden');

    hideAllSearchContainers();
    showSearchContainer('mobileMusicSearchContainer');

    // Store state for back navigation and reset view
    window.mobileSongListState.playlistId = id;
    window.mobileSongListState.view = 'list';

    if (window.htmx) window.htmx.ajax('GET', `/api/music/ui/mobile-tbody/${window.globalActiveProfileId}/${id}`, { target: '#mobileSongList', swap: 'innerHTML' });
};

window.switchToTab = function(tab) {
    setupMusicSearchListeners();

    document.querySelectorAll('.nav-item, .nav-sub-item').forEach(el => el.classList.remove('active'));

    const navItem = document.getElementById(`nav-music-${tab}`) || document.getElementById(`nav-${tab}`);
    if (navItem) navItem.classList.add('active');

    document.getElementById('mobileSongList')?.classList.add('is-hidden');
    document.getElementById('mobileQueueContent')?.classList.add('is-hidden');
    document.getElementById('mobileHistoryContent')?.classList.add('is-hidden');

    hideAllSearchContainers();

    const targetId = tab === 'queue' ? 'mobileQueueContent' : 'mobileHistoryContent';
    const endpoint = tab === 'queue' ? 'mobile-queue-fragment' : 'mobile-history-fragment';
    const searchId = tab === 'queue' ? 'mobileQueueSearchContainer' : 'mobileHistorySearchContainer';

    showSearchContainer(searchId);

    const targetEl = document.getElementById(targetId);
    if (targetEl) {
        targetEl.classList.remove('is-hidden');
        if (window.htmx) window.htmx.ajax('GET', `/api/music/ui/${endpoint}/${window.globalActiveProfileId}`, { target: `#${targetId}`, swap: 'innerHTML' });
    }
};

// --- Context Menu Logic ---
document.addEventListener('contextmenu', function(e) {
    const songRow = e.target.closest('tr[data-song-id], .mobile-song-item');
    if (songRow) {
        e.preventDefault();
        const songId = songRow.dataset.songId;
        showCustomContextMenu(e.pageX, e.pageY, songId);
    } else {
        hideCustomContextMenu();
    }
});

// Long press detection for mobile/touch devices
let longPressTimer = null;
const LONG_PRESS_DURATION = 500;

document.addEventListener('touchstart', function(e) {
    const songRow = e.target.closest('tr[data-song-id], .mobile-song-item');
    if (!songRow) return;
    
    songRow.classList.add('long-press-active');
    
    longPressTimer = setTimeout(() => {
        e.preventDefault();
        const songId = songRow.dataset.songId;
        showCustomContextMenu(e.touches[0].pageX, e.touches[0].pageY, songId);
    }, LONG_PRESS_DURATION);
}, { passive: false });

document.addEventListener('touchend', function(e) {
    if (longPressTimer) {
        clearTimeout(longPressTimer);
        longPressTimer = null;
    }
    document.querySelectorAll('.long-press-active').forEach(el => {
        el.classList.remove('long-press-active');
    });
});

document.addEventListener('touchmove', function(e) {
    if (longPressTimer) {
        clearTimeout(longPressTimer);
        longPressTimer = null;
    }
    document.querySelectorAll('.long-press-active').forEach(el => {
        el.classList.remove('long-press-active');
    });
});

document.addEventListener('click', function(e) {
    if (!e.target.closest('#customContextMenu')) {
        hideCustomContextMenu();
    }
});

function showCustomContextMenu(x, y, songId) {
    const menu = document.getElementById('customContextMenu');
    if (!menu) return;

    menu.style.display = 'block';
    menu.style.left = x + 'px';
    menu.style.top = y + 'px';
    menu.dataset.songId = songId;

    // Handle submenu for playlists
    const playlistItem = menu.querySelector('[data-action="playlist"]');
    if (playlistItem) {
        playlistItem.onmouseenter = () => loadPlaylistSubmenu(songId);
    }

    // Bind actions
    menu.querySelectorAll('.context-menu-item').forEach(item => {
        item.onclick = (e) => {
            e.stopPropagation();
            const action = item.dataset.action;
            if (action !== 'playlist') {
                handleContextAction(action, songId);
                hideCustomContextMenu();
            }
        };
    });
}

function hideCustomContextMenu() {
    const menu = document.getElementById('customContextMenu');
    if (menu) menu.style.display = 'none';
}

function handleContextAction(action, songId) {
    const profileId = window.globalActiveProfileId || '1';
    let url = '';
    let method = 'POST';

    switch (action) {
        case 'queue':
            url = `/api/music/queue/add/${profileId}/${songId}`;
            break;
        case 'queue-similar':
            url = `/api/music/queue/similar/${profileId}/${songId}`;
            break;
        case 'rescan':
            url = `/api/music/ui/rescan-song/${songId}`;
            break;
        case 'enrich':
            url = `/api/metadata/enrich/${songId}`;
            break;
        case 'delete':
            if (confirm('Are you sure you want to delete this song?')) {
                url = `/api/music/ui/delete-song/${songId}`;
                method = 'DELETE';
            } else return;
            break;
    }

    if (url) {
        fetch(url, { method: method })
            .then(res => {
                if (res.ok && window.Toast) {
                    window.Toast.success(`${action.charAt(0).toUpperCase() + action.slice(1)} action successful`);
                    if (action === 'queue' || action === 'queue-similar') {
                        window.dispatchEvent(new CustomEvent('queueChanged'));
                    }
                }
            });
    }
}

function loadPlaylistSubmenu(songId) {
    const submenu = document.getElementById('playlistSubMenu');
    if (!submenu) return;

    fetch(`/api/music/playlists/${window.globalActiveProfileId}`)
        .then(res => res.json())
        .then(data => {
            const playlists = data.data || data;
            submenu.innerHTML = playlists.map(p => `
                <div class="context-menu-item" onclick="addToPlaylist(${p.id}, ${songId})">${p.name}</div>
            `).join('');
        });
}

window.addToPlaylist = function(playlistId, songId) {
    fetch(`/api/music/playlists/add/${playlistId}/${songId}`, { method: 'POST', credentials: 'same-origin' })
        .then(res => {
            if (res.ok && window.Toast) {
                window.Toast.success('Added to playlist');
                hideCustomContextMenu();
            }
        });
};

// Store previous state for back navigation
window.mobileSongListState = {
    playlistId: 0,
    search: '',
    page: 1,
    view: 'list', // 'list', 'detail', 'artist', 'album'
    albumName: null
};

// Show song detail when clicking on cover image
window.showSongDetail = function(songId) {
    const songList = document.getElementById('mobileSongList');
    if (!songList) return;

    // Store current state for back button
    window.mobileSongListState.search = document.getElementById('musicSearchInput')?.value || '';
    window.mobileSongListState.view = 'detail';
    
    // Show loading
    songList.innerHTML = '<div class="has-text-centered p-6"><i class="pi pi-spin pi-spinner" style="font-size: 2rem;"></i></div>';

    // Fetch song detail
    if (window.htmx) {
        window.htmx.ajax('GET', `/api/music/ui/song-detail/${window.globalActiveProfileId}/${songId}`, {
            target: '#mobileSongList',
            swap: 'innerHTML'
        });
    }
};

// Go back to song list
window.showMobileSongList = function() {
    const playlistId = window.mobileSongListState.playlistId || 0;
    const search = window.mobileSongListState.search || '';
    
    // Reset view state
    window.mobileSongListState.view = 'list';
    
    if (window.htmx) {
        let url = `/api/music/ui/mobile-tbody/${window.globalActiveProfileId}/${playlistId}`;
        if (search) url += `?search=${encodeURIComponent(search)}`;
        window.htmx.ajax('GET', url, { target: '#mobileSongList', swap: 'innerHTML' });
    }
};

// Play a song from the detail view
window.playSongFromDetail = function(songId) {
    // Use existing play functionality - try to find it
    if (window.PlaybackController && window.PlaybackController.playSong) {
        window.PlaybackController.playSong(songId);
    } else if (window.playSong) {
        window.playSong(songId);
    } else {
        // Fallback: fetch directly using the correct API
        fetch(`/api/music/playback/select/${window.globalActiveProfileId}/${songId}`, { method: 'POST' })
            .then(res => {
                if (res.ok && window.Toast) {
                    window.Toast.success('Playing song');
                }
            });
    }
};

// Fetch metadata for a song from the detail view
window.fetchSongMetadata = function(songId) {
    const btn = document.querySelector('.song-detail-fetch-btn');
    if (btn) {
        btn.disabled = true;
        btn.innerHTML = '<i class="pi pi-spin pi-spinner"></i> Fetching...';
    }
    
    fetch(`/api/metadata/enrich/${songId}`, { method: 'POST' })
        .then(res => res.json())
        .then(data => {
            if (window.Toast) {
                if (data.success || data.status === 'success') {
                    window.Toast.success('Metadata updated');
                } else {
                    window.Toast.info(data.message || 'No metadata found');
                }
            }
            // Refresh the detail view
            window.showSongDetail(songId);
        })
        .catch(err => {
            if (window.Toast) {
                window.Toast.error('Failed to fetch metadata');
            }
        })
        .finally(() => {
            if (btn) {
                btn.disabled = false;
                btn.innerHTML = '<i class="pi pi-refresh"></i> Fetch Metadata';
            }
        });
};

// Show artist page
window.showArtistPage = function(artistName) {
    const songList = document.getElementById('mobileSongList');
    if (!songList || !artistName) return;

    // Store state for back button
    window.mobileSongListState.search = document.getElementById('musicSearchInput')?.value || '';
    window.mobileSongListState.view = 'artist';
    
    // Show loading
    songList.innerHTML = '<div class="has-text-centered p-6"><i class="pi pi-spin pi-spinner" style="font-size: 2rem;"></i></div>';

    // Fetch artist page
    if (window.htmx) {
        const encodedArtist = encodeURIComponent(artistName);
        window.htmx.ajax('GET', `/api/music/ui/album-artist/${window.globalActiveProfileId}/${encodedArtist}`, {
            target: '#mobileSongList',
            swap: 'innerHTML'
        });
    }
};

// Show album page
window.showAlbumPage = function(albumName) {
    const songList = document.getElementById('mobileSongList');
    if (!songList || !albumName) return;

    // Store state for back button
    window.mobileSongListState.search = document.getElementById('musicSearchInput')?.value || '';
    window.mobileSongListState.view = 'album';
    window.mobileSongListState.albumName = albumName;
    
    // Show loading
    songList.innerHTML = '<div class="has-text-centered p-6"><i class="pi pi-spin pi-spinner" style="font-size: 2rem;"></i></div>';

    // Fetch album page
    if (window.htmx) {
        const encodedAlbum = encodeURIComponent(albumName);
        window.htmx.ajax('GET', `/api/music/ui/album/${window.globalActiveProfileId}/${encodedAlbum}`, {
            target: '#mobileSongList',
            swap: 'innerHTML'
        });
    }
};

// Play entire album
window.playAlbum = function(firstSongId) {
    if (!firstSongId) return;
    // Play first song - will trigger queue population
    fetch(`/api/music/playback/select/${window.globalActiveProfileId}/${firstSongId}`, { method: 'POST' })
        .then(res => {
            if (res.ok && window.Toast) {
                window.Toast.success('Playing album');
            }
        });
};

// Shuffle album
window.shuffleAlbum = function(firstSongId) {
    // For now, just play the first song - shuffle functionality would need backend support
    window.playAlbum(firstSongId);
};

// Go back from album - return to artist page
window.goBackFromAlbum = function() {
    const artist = window.albumBackArtist;
    if (artist) {
        window.showArtistPage(artist);
    } else {
        window.showMobileSongList();
    }
};

// Set up cover image click handler
document.addEventListener('DOMContentLoaded', function() {
    const coverContainer = document.getElementById('songCoverImageContainer');
    if (coverContainer) {
        coverContainer.style.cursor = 'pointer';
        coverContainer.title = 'Click to view song details';
        coverContainer.addEventListener('click', function(e) {
            // Only trigger if there's a current song playing
            const songTitleEl = document.getElementById('songTitle');
            if (songTitleEl && songTitleEl.textContent && songTitleEl.textContent !== 'Loading...') {
                // Get current song ID from StateManager
                let currentSongId = null;
                if (window.StateManager && typeof StateManager.getProperty === 'function') {
                    currentSongId = window.StateManager.getProperty('currentSongId');
                } else if (window.currentSongId) {
                    currentSongId = window.currentSongId;
                }
                
                if (currentSongId) {
                    window.showSongDetail(currentSongId);
                }
            }
        });
    }
    });
    
    // Get artist name from player and show artist page
    window.showArtistFromPlayer = function() {
        const artistEl = document.getElementById('songArtist');
        if (artistEl && artistEl.textContent && artistEl.textContent !== 'Unknown Artist') {
            window.showArtistPage(artistEl.textContent);
        }
    };
