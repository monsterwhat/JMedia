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
