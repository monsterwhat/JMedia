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
        contextMenu.style.display = 'block';
        
        // Position at mouse cursor
        let mouseX = event.clientX + window.scrollX;
        let mouseY = event.clientY + window.scrollY;
        
        // Get menu dimensions after making it visible
        const menuRect = contextMenu.getBoundingClientRect();
        
        // Adjust if menu goes off screen
        if (mouseX + menuRect.width > window.innerWidth + window.scrollX) {
            mouseX = window.innerWidth + window.scrollX - menuRect.width - 5;
        }
        if (mouseY + menuRect.height > window.innerHeight + window.scrollY) {
            mouseY = window.innerHeight + window.scrollY - menuRect.height - 5;
        }
        
        contextMenu.style.position = 'fixed';
        contextMenu.style.top = mouseY + 'px';
        contextMenu.style.left = mouseX + 'px';
        contextMenu.style.zIndex = '9999';
    } else {
        contextMenu.style.display = 'none';
    }
}

function deletePlaylist(playlistId, playlistName) {
    if (confirm(`Are you sure you want to delete playlist '${playlistName}'?`)) {
        fetch(`/api/music/playlists/${globalActiveProfileId}`, {
            method: 'DELETE'
        })
        .then(response => {
            if (response.ok) {
                // Remove the playlist row from the DOM
                const playlistRow = document.querySelector(`[id^="playlist-context-menu-${playlistId}"]`).closest('tr');
                if (playlistRow) {
                    playlistRow.remove();
                }
            } else {
                console.error('Failed to delete playlist');
                alert('Failed to delete playlist');
            }
        })
        .catch(error => {
            console.error('Error deleting playlist:', error);
            alert('Error deleting playlist');
        });
    }
}

function togglePlaylistShared(playlistId, currentSharedStatus) {
    const newSharedStatus = !currentSharedStatus;
    
    fetch(`/api/music/playlists/${globalActiveProfileId}/toggle-shared`, {
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
        } else {
            console.error('Failed to toggle playlist shared status');
        }
    })
    .catch(error => {
        console.error('Error toggling playlist shared status:', error);
    });
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