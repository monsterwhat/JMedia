
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
    const modal = document.getElementById('createPlaylistModal');
    const openBtn = document.getElementById('createPlaylistBtn');
    const closeBtn = document.getElementById('closeCreatePlaylistModal');
    const cancelBtn = document.getElementById('cancelPlaylistBtn');
    const saveBtn = document.getElementById('savePlaylistBtn');

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

    // Mobile Sidebar Toggle Logic
    const sidebarToggle = document.getElementById('sidebarToggle');
    const playlistSidebar = document.getElementById('playlistSidebar');
    const body = document.body;

    if (sidebarToggle && playlistSidebar) {
        // Create overlay element
        const sidebarOverlay = document.createElement('div');
        sidebarOverlay.classList.add('sidebar-overlay');
        body.appendChild(sidebarOverlay);

        const toggleSidebar = () => {
            playlistSidebar.classList.toggle('is-active');
            sidebarOverlay.classList.toggle('is-active');
            // Prevent scrolling when sidebar is open
            body.classList.toggle('is-clipped', playlistSidebar.classList.contains('is-active'));
        };

        sidebarToggle.addEventListener('click', toggleSidebar);
        sidebarOverlay.addEventListener('click', toggleSidebar); // Close when overlay is clicked
    }

    const togglePlaylistsBtn = document.getElementById('togglePlaylistsBtn');
    const playlistsContent = document.getElementById('playlistsContent');
    const playlistSidebarCard = document.getElementById('playlistSidebar').querySelector('.card');

    if (togglePlaylistsBtn && playlistsContent && playlistSidebarCard) {
        // Ensure it's expanded on load
        playlistsContent.classList.remove('is-hidden');
        playlistSidebarCard.style.height = 'calc(50vh - 100px)';

        togglePlaylistsBtn.addEventListener('click', () => {
            window.toggleCardContent(togglePlaylistsBtn, 'playlistsContent');
            const isHidden = playlistsContent.classList.contains('is-hidden');
            playlistSidebarCard.style.height = isHidden ? 'auto' : 'calc(50vh - 100px)';
        });
    }

    const toggleQueueBtn = document.getElementById('toggleQueueBtn');
    const queueContent = document.getElementById('queueContent');
    const songQueueCard = document.getElementById('songQueueCard');

    if (toggleQueueBtn && queueContent && songQueueCard) {
        // Ensure it's expanded on load
        queueContent.classList.remove('is-hidden');
        songQueueCard.style.height = 'calc(50vh - 100px)'; // Assuming similar height for queue

        toggleQueueBtn.addEventListener('click', () => {
            window.toggleCardContent(toggleQueueBtn, 'queueContent');
            const isHidden = queueContent.classList.contains('is-hidden');
            songQueueCard.style.height = isHidden ? 'auto' : 'calc(50vh - 100px)'; // Assuming similar height for queue
        });
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
            // The request was successful, so remove the button
            const button = evt.detail.elt;
            button.remove();
        }
    }
});