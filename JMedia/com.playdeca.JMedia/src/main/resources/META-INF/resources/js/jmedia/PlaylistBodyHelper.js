(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    JMedia.PlaylistBodyHelper = {};

    JMedia.PlaylistBodyHelper.toggleCtxMenu = function(event, playlistId, isGlobal) {
        event.preventDefault();
        event.stopPropagation();
        if (event.type === 'click' && event.button !== 2) return;

        const contextMenu = document.getElementById('playlist-context-menu-' + playlistId);
        const allContextMenus = document.querySelectorAll('[id^="playlist-context-menu-"]');
        allContextMenus.forEach(menu => {
            if (menu !== contextMenu) menu.style.display = 'none';
        });

        if (contextMenu.style.display === 'none') {
            if (contextMenu.parentNode !== document.body) {
                document.body.appendChild(contextMenu);
            }
            contextMenu.style.display = 'block';

            let mouseX = event.clientX;
            let mouseY = event.clientY;
            const menuRect = contextMenu.getBoundingClientRect();

            if (mouseX + menuRect.width > window.innerWidth) mouseX = window.innerWidth - menuRect.width - 5;
            if (mouseY + menuRect.height > window.innerHeight) mouseY = window.innerHeight - menuRect.height - 5;

            contextMenu.style.position = 'fixed';
            contextMenu.style.top = mouseY + 'px';
            contextMenu.style.left = mouseX + 'px';
            contextMenu.style.zIndex = '99999';
        } else {
            contextMenu.style.display = 'none';
        }
    };

    JMedia.PlaylistBodyHelper.deletePlaylist = function(playlistId, playlistName) {
        if (confirm(`Are you sure you want to delete playlist '${playlistName}'?`)) {
            fetch(`/api/music/playlists/${playlistId}`, { method: 'DELETE' })
                .then(response => {
                    if (response.ok) {
                        location.reload();
                        if (window.Toast) Toast.success(`Playlist '${playlistName}' deleted successfully`);
                    } else {
                        if (window.Toast) Toast.error('Failed to delete playlist');
                    }
                })
                .catch(error => {
                    console.error('Error deleting playlist:', error);
                    if (window.Toast) Toast.error('Error deleting playlist');
                });
        }
    };

    JMedia.PlaylistBodyHelper.shareModal = function(playlistId) {
        const profileId = JMedia.Helpers.getActiveProfileId();
        fetch(`/api/music/playlists/${profileId}/share-url`, { method: 'POST' })
            .catch(() => {});
    };

    JMedia.PlaylistBodyHelper.renamePlaylist = function(playlistId) {
        const newName = prompt('Enter new playlist name:');
        if (newName && newName.trim()) {
            fetch(`/api/music/playlists/${playlistId}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: newName.trim() })
            })
                .then(res => {
                    if (res.ok && window.Toast) Toast.success('Playlist renamed');
                    location.reload();
                });
        }
    };

    JMedia.PlaylistBodyHelper.followPlaylist = function(playlistId) {
        const profileId = JMedia.Helpers.getActiveProfileId();
        fetch(`/api/music/playlists/${playlistId}/follow/${profileId}`, { method: 'POST' })
            .then(res => {
                if (res.ok && window.Toast) Toast.success('Now following playlist');
                location.reload();
            });
    };

    JMedia.PlaylistBodyHelper.editCollaborators = function(playlistId) {
        fetch(`/api/music/playlists/${playlistId}/collaborators`, { method: 'GET' })
            .catch(() => {});
    };

    window.togglePlaylistContextMenu = JMedia.PlaylistBodyHelper.toggleCtxMenu;
    window.deletePlaylist = function(playlistId, playlistName) {
        JMedia.PlaylistBodyHelper.deletePlaylist(playlistId, playlistName);
    };

    document.addEventListener('click', function(event) {
        const contextMenus = document.querySelectorAll('[id^="playlist-context-menu-"]');
        contextMenus.forEach(menu => {
            if (!menu.contains(event.target)) menu.style.display = 'none';
        });
    });

})(window);
