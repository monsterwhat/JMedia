window.loadMobilePlaylists = function() {
    fetch(`/api/music/playlists/${window.globalActiveProfileId}`).then(r => r.json()).then(data => {
        const list = document.getElementById('sidebarPlaylistList');
        if (!list) return;
        list.innerHTML = (data.data || data).map(p => `<a href="javascript:void(0)" class="nav-sub-item" id="nav-playlist-${p.id}" onclick="loadMobilePlaylistSongs(${p.id})"><span>${p.name}</span></a>`).join('');
    });
};

window.loadMobilePlaylistSongs = function(id) {
    document.querySelectorAll('.nav-item, .nav-sub-item').forEach(el => el.classList.remove('active'));
    if (id === 0) document.getElementById('nav-music')?.classList.add('active');
    else document.getElementById(`nav-playlist-${id}`)?.classList.add('active');
    
    document.getElementById('mobileSongList')?.classList.remove('is-hidden');
    document.getElementById('mobileQueueContent')?.classList.add('is-hidden');
    document.getElementById('mobileHistoryContent')?.classList.add('is-hidden');
    
    if (window.htmx) window.htmx.ajax('GET', `/api/music/ui/mobile-tbody/${window.globalActiveProfileId}/${id}`, { target: '#mobileSongList', swap: 'innerHTML' });
};

window.switchToTab = function(tab) {
    document.querySelectorAll('.nav-item, .nav-sub-item').forEach(el => el.classList.remove('active'));
    
    // Support both old and new IDs
    const navItem = document.getElementById(`nav-music-${tab}`) || document.getElementById(`nav-${tab}`);
    if (navItem) navItem.classList.add('active');
    
    document.getElementById('mobileSongList')?.classList.add('is-hidden');
    document.getElementById('mobileQueueContent')?.classList.add('is-hidden');
    document.getElementById('mobileHistoryContent')?.classList.add('is-hidden');
    
    const targetId = tab === 'queue' ? 'mobileQueueContent' : 'mobileHistoryContent';
    const endpoint = tab === 'queue' ? 'mobile-queue-fragment' : 'mobile-history-fragment';
    
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
