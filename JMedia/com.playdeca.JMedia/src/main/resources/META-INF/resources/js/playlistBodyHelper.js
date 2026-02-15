// Context menu functions for playlist sharing
function togglePlaylistContextMenu(event, playlistId, isGlobal) {
    event.preventDefault();
    event.stopPropagation();
    
    // Only show on right-click
    if (event.type === 'click' && event.button !== 2) {
        return;
    }
    
    const contextMenu = document.getElementById('playlist-context-menu-' + playlistId);
    const allContextMenus = document.querySelectorAll('[id^="playlist-context-menu-"]');
    
    // Close all other context menus
    allContextMenus.forEach(menu => {
        if (menu !== contextMenu) {
            menu.style.display = 'none';
        }
    });
    
    // Toggle current context menu
    if (contextMenu.style.display === 'none') {
        // Move the context menu to body level to escape stacking context
        if (contextMenu.parentNode !== document.body) {
            document.body.appendChild(contextMenu);
        }
        
        contextMenu.style.display = 'block';
        
        // Position at mouse cursor
        let mouseX = event.clientX;
        let mouseY = event.clientY;
        
        // Get menu dimensions after making it visible
        const menuRect = contextMenu.getBoundingClientRect();
        
        // Adjust if menu goes off screen
        if (mouseX + menuRect.width > window.innerWidth) {
            mouseX = window.innerWidth - menuRect.width - 5;
        }
        if (mouseY + menuRect.height > window.innerHeight) {
            mouseY = window.innerHeight - menuRect.height - 5;
        }
        
        contextMenu.style.position = 'fixed';
        contextMenu.style.top = mouseY + 'px';
        contextMenu.style.left = mouseX + 'px';
        contextMenu.style.zIndex = '99999';
    } else {
        contextMenu.style.display = 'none';
    }
}

function deletePlaylist(playlistId, playlistName) {
    if (confirm(`Are you sure you want to delete playlist '${playlistName}'?`)) {
        fetch(`/api/music/playlists/${playlistId}`, {
            method: 'DELETE'
        })
        .then(response => {
            if (response.ok) {
                // Reload the playlist list to show updated state
                location.reload();
                Toast.success(`Playlist '${playlistName}' deleted successfully`);
            } else {
                console.error('Failed to delete playlist');
                Toast.error('Failed to delete playlist');
            }
        })
        .catch(error => {
            console.error('Error deleting playlist:', error);
            Toast.error('Error deleting playlist');
        });
    }
}

function togglePlaylistShared(playlistId, currentSharedStatus) {
    const newSharedStatus = !currentSharedStatus;
    
    fetch(`/api/music/playlists/${playlistId}/toggle-shared`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({ isShared: newSharedStatus })
    })
    .then(response => {
        if (response.ok) {
            // Update UI to reflect the change
            location.reload(); // Simple reload to show updated status
            Toast.success(`Playlist ${newSharedStatus ? 'shared' : 'unshared'} successfully`);
        } else {
            console.error('Failed to toggle playlist shared status');
            Toast.error('Failed to toggle playlist shared status');
        }
    })
    .catch(error => {
        console.error('Error toggling playlist shared status:', error);
        Toast.error('Error toggling playlist shared status');
    });
}

function hidePlaylist(playlistId, playlistName) {
    if (!confirm(`Are you sure you want to hide "${playlistName}" from your library? You can unhide it later.`)) {
        return;
    }
    
    fetch(`/api/profiles/hidden-playlists/${playlistId}`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        }
    })
    .then(response => {
        if (response.ok) {
            Toast.success(`Playlist "${playlistName}" hidden from your library`);
            location.reload();
        } else {
            return response.json().then(err => {
                throw new Error(err.error || 'Failed to hide playlist');
            });
        }
    })
    .catch(error => {
        console.error('Error hiding playlist:', error);
        Toast.error(error.message || 'Error hiding playlist');
    });
}

function unhidePlaylist(playlistId, playlistName) {
    fetch(`/api/profiles/hidden-playlists/${playlistId}`, {
        method: 'DELETE',
        headers: {
            'Content-Type': 'application/json'
        }
    })
    .then(response => {
        if (response.ok) {
            Toast.success(`Playlist "${playlistName}" unhidden`);
            // Remove from hidden playlists list
            const element = document.getElementById(`hidden-playlist-${playlistId}`);
            if (element) {
                element.remove();
            }
            // Check if list is empty
            const container = document.getElementById('hiddenPlaylistsContainer');
            if (container && container.children.length === 0) {
                const emptyMsg = document.getElementById('hiddenPlaylistsEmpty');
                if (emptyMsg) {
                    emptyMsg.style.display = 'block';
                }
            }
        } else {
            return response.json().then(err => {
                throw new Error(err.error || 'Failed to unhide playlist');
            });
        }
    })
    .catch(error => {
        console.error('Error unhiding playlist:', error);
        Toast.error(error.message || 'Error unhiding playlist');
    });
}

async function loadHiddenPlaylists() {
    const container = document.getElementById('hiddenPlaylistsContainer');
    if (!container) return;
    
    try {
        const response = await fetch('/api/profiles/hidden-playlists');
        if (!response.ok) {
            throw new Error('Failed to load hidden playlists');
        }
        
        const hiddenIds = await response.json();
        
        if (!hiddenIds || hiddenIds.length === 0) {
            container.innerHTML = '<p class="has-text-centered has-text-grey">No hidden playlists</p>';
            return;
        }
        
        // Fetch playlist details for each hidden ID
        const promises = hiddenIds.map(id => 
            fetch(`/api/music/playlists/${id}`).then(r => r.json())
        );
        
        const playlists = await Promise.all(promises);
        
        container.innerHTML = playlists.map(p => {
            const data = p.data || p;
            return `
                <div id="hidden-playlist-${data.id}" class="is-flex is-justify-content-space-between is-align-items-center mb-2 p-2" style="background: rgba(0,0,0,0.1); border-radius: 4px;">
                    <span>${data.name || 'Unnamed Playlist'}</span>
                    <button class="button is-small is-info" onclick="unhidePlaylist(${data.id}, '${data.name || 'Unnamed Playlist'}')">
                        <i class="pi pi-eye"></i>&nbsp;Unhide
                    </button>
                </div>
            `;
        }).join('');
        
    } catch (error) {
        console.error('Error loading hidden playlists:', error);
        container.innerHTML = '<p class="has-text-centered has-text-danger">Error loading hidden playlists</p>';
    }
}

// Close context menus when clicking outside
document.addEventListener('click', function(event) {
    const contextMenus = document.querySelectorAll('[id^="playlist-context-menu-"]');
    contextMenus.forEach(menu => {
        if (!menu.contains(event.target)) {
            menu.style.display = 'none';
        }
    });
});