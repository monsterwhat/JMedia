window.toggleCardContent = function (button, contentId) {
    const content = document.getElementById(contentId);
    const icon = button.querySelector('i');

    if (content && icon) {
        const isHidden = content.classList.toggle('is-hidden');
        icon.classList.toggle('pi-angle-down', isHidden);
        icon.classList.toggle('pi-angle-up', !isHidden);
    }
};

document.addEventListener('DOMContentLoaded', () => {
    // Restore playlist view on page load
    const playlistId = sessionStorage.getItem('currentPlaylistId');
    if (playlistId && playlistId !== '0') {
        console.log(`[playlistBar.js] Restoring playlist view for ID: ${playlistId}`);
        htmx.ajax('GET', `/api/music/ui/playlist-view/${playlistId}`, {
            target: '#playlistView',
            swap: 'outerHTML'
        });
    }

    const modal = document.getElementById('createPlaylistModal');
    const openBtn = document.getElementById('createPlaylistBtn');
    const closeBtn = document.getElementById('closeCreatePlaylistModal');
    const cancelBtn = document.getElementById('cancelPlaylistBtn');
    const saveBtn = document.getElementById('savePlaylistBtn');

    if (modal && openBtn && closeBtn && cancelBtn && saveBtn) {
        const openModal = () => modal.classList.add('is-active');
        const closeModal = () => modal.classList.remove('is-active');

        openBtn.addEventListener('click', openModal);
        closeBtn.addEventListener('click', closeModal);
        cancelBtn.addEventListener('click', closeModal);

        saveBtn.addEventListener('click', async () => {
            const name = document.getElementById('newPlaylistName').value.trim();
            const description = document.getElementById('newPlaylistDescription').value.trim();
            if (!name)
                return alert('Please enter a name');
            const response = await fetch('/api/music/playlists', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({name, description})
            });
            const json = await response.json();
            if (response.ok && json.data) {
                closeModal();
                htmx.ajax('GET', '/api/music/ui/playlists-fragment', {target: '#sidebarPlaylistList'});
            } else {
                alert('Failed to create playlist: ' + json.error);
            }
        });
    }

    // Mobile Sidebar Toggle Logic
    const sidebarToggle = document.getElementById('sidebarToggle');
    const playlistSidebar = document.getElementById('playlistSidebar');
    const body = document.body;

    if (sidebarToggle && playlistSidebar) {
        const sidebarOverlay = document.createElement('div');
        sidebarOverlay.classList.add('sidebar-overlay');
        body.appendChild(sidebarOverlay);

        const toggleSidebar = () => {
            playlistSidebar.classList.toggle('is-active');
            sidebarOverlay.classList.toggle('is-active');
            body.classList.toggle('is-clipped', playlistSidebar.classList.contains('is-active'));
        };

        sidebarToggle.addEventListener('click', toggleSidebar);
        sidebarOverlay.addEventListener('click', toggleSidebar);
    }

    const toggleAllSongsBtn = document.getElementById('toggleAllSongsBtn');
    if (toggleAllSongsBtn) {
        toggleAllSongsBtn.onclick = (event) => window.toggleCardContent(event.currentTarget, "allSongsContent");
    }

    // Bulma Tabs Logic for Playlists and Queue
    const playlistsTab = document.getElementById('playlistsTab');
    const queueTab = document.getElementById('queueTab');
    const playlistsTabContent = document.getElementById('playlistsTabContent');
    const queueTabContent = document.getElementById('queueTabContent');

    if (playlistsTab && queueTab && playlistsTabContent && queueTabContent) {
        playlistsTab.addEventListener('click', () => {
            playlistsTab.classList.add('is-active');
            queueTab.classList.remove('is-active');
            playlistsTabContent.classList.remove('is-hidden');
            queueTabContent.classList.add('is-hidden');
        });

        queueTab.addEventListener('click', () => {
            queueTab.classList.add('is-active');
            playlistsTab.classList.remove('is-active');
            queueTabContent.classList.remove('is-hidden');
            playlistsTabContent.classList.add('is-hidden');
            if (window.refreshQueue) {
                window.refreshQueue();
                console.log("[playlistBar.js] Called window.refreshQueue() on queue tab click.");
            } else {
                console.error("[playlistBar.js] window.refreshQueue is not defined.");
            }
        });
    }
});

// Listen for HTMX swaps to save the current playlist ID and decide how to load the tbody
document.body.addEventListener('htmx:afterSwap', function(event) {
    if (event.detail.target.id === 'playlistView') {
        const playlistId = event.detail.elt.dataset.playlistId;
        if (playlistId) {
            console.log('[playlistBar.js] Storing current playlist ID:', playlistId);
            sessionStorage.setItem('currentPlaylistId', playlistId);
        }

        // Now, decide how to load the tbody content
        const tbody = event.detail.elt.querySelector('tbody');
        if (tbody) {
            const paginationState = JSON.parse(sessionStorage.getItem('playlistPaginationState'));
            console.log('[playlistBar.js] afterSwap for playlistView. Read paginationState:', paginationState); // DEBUG LOG
            let url = tbody.getAttribute('hx-get');

            if (paginationState && paginationState.playlistId === parseInt(playlistId, 10) && paginationState.allLoaded) {
                // If allLoaded flag is set for this playlist, load all songs
                const totalSongs = paginationState.totalSongs || 1000; // Fallback
                url += `?limit=${totalSongs}`;
                console.log(`[playlistBar.js] 'allLoaded' is true. Loading all ${totalSongs} songs.`);
            } else {
                 console.log(`[playlistBar.js] 'allLoaded' is false or not set. Loading first page.`);
            }
            
            htmx.ajax('GET', url, {
                target: tbody,
                swap: 'innerHTML'
            });
        }
    }
});

document.body.addEventListener('htmx:afterRequest', function(evt) {
    const requestPath = evt.detail.requestConfig.path;
    // Handle "Add to playlist" button removal
    if (evt.detail.requestConfig.verb === 'post' && requestPath.includes('/api/music/playlists/') && requestPath.includes('/songs/')) {
        if (evt.detail.successful) {
            const button = evt.detail.elt;
            button.remove();
        }
    }
});
