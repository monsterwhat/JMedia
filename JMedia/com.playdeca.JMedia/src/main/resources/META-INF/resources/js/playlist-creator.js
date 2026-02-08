window.createPlaylistFromText = async function() {
    if (!window.globalActiveProfileId) {
        Toast.error("No active profile found");
        return;
    }

    const playlistName = document.getElementById('playlistNameInput')?.value?.trim();
    const description = document.getElementById('playlistDescriptionInput')?.value?.trim();
    const songListText = document.getElementById('songListTextarea')?.value?.trim();

    // Validation
    if (!playlistName) {
        Toast.warning("Please enter a playlist name");
        document.getElementById('playlistNameInput')?.focus();
        return;
    }

    if (!songListText) {
        Toast.warning("Please paste your song list");
        document.getElementById('songListTextarea')?.focus();
        return;
    }

    // Parse text into lines
    const textLines = songListText.split('\n')
        .map(line => line.trim())
        .filter(line => line.length > 0);

    if (textLines.length === 0) {
        Toast.warning("Please enter at least one song");
        return;
    }

    // Disable button and show loading
    const createBtn = document.getElementById('createPlaylistBtn');
    const originalBtnContent = createBtn.innerHTML;
    createBtn.disabled = true;
    createBtn.classList.add('is-loading');
    createBtn.innerHTML = `
        <span class="icon-text">
            <span class="icon"><i class="pi pi-spinner pi-spin"></i></span>
            <span>Creating Playlist...</span>
        </span>
    `;

    try {
        const response = await fetch(`/api/music/playlists/create-from-text/${window.globalActiveProfileId}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                playlistName: playlistName,
                description: description,
                textLines: textLines
            })
        });

        const result = await response.json();

        if (response.ok && result.success) {
            const data = result.data;
            displayPlaylistResults(data);
            Toast.success(`Playlist "${data.playlist.name}" created successfully!`);
            
            // Clear form after successful creation
            clearPlaylistForm();
        } else {
            throw new Error(result.error || 'Failed to create playlist');
        }

    } catch (error) {
        console.error('Error creating playlist from text:', error);
        Toast.error('Failed to create playlist: ' + error.message);
    } finally {
        // Restore button
        createBtn.disabled = false;
        createBtn.classList.remove('is-loading');
        createBtn.innerHTML = originalBtnContent;
    }
};

window.displayPlaylistResults = function(data) {
    const resultsDiv = document.getElementById('playlistResults');
    const resultsMessage = document.getElementById('resultsMessage');
    const unmatchedSection = document.getElementById('unmatchedSection');
    const unmatchedList = document.getElementById('unmatchedList');
    const viewPlaylistBtn = document.getElementById('viewPlaylistBtn');

    if (!resultsDiv || !resultsMessage) return;

    // Show results section
    resultsDiv.style.display = 'block';

    // Set message
    resultsMessage.textContent = data.message;

    // Handle unmatched songs
    if (data.unmatchedLines && data.unmatchedLines.length > 0) {
        unmatchedSection.style.display = 'block';
        unmatchedList.innerHTML = data.unmatchedLines
            .map(line => `<div class="mb-1">• ${escapeHtml(line)}</div>`)
            .join('');
    } else {
        unmatchedSection.style.display = 'none';
    }

    // Store playlist ID for view button
    if (data.playlist && data.playlist.id) {
        viewPlaylistBtn.onclick = () => {
            window.location.href = `/?playlist=${data.playlist.id}`;
        };
        viewPlaylistBtn.style.display = 'inline-flex';
    } else {
        viewPlaylistBtn.style.display = 'none';
    }

    // Scroll to results
    resultsDiv.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
};

window.clearPlaylistForm = function() {
    document.getElementById('playlistNameInput').value = '';
    document.getElementById('playlistDescriptionInput').value = '';
    document.getElementById('songListTextarea').value = '';
    document.getElementById('playlistResults').style.display = 'none';
    
    // Focus on playlist name input
    document.getElementById('playlistNameInput')?.focus();
};

window.escapeHtml = function(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
};

window.validatePlaylistForm = function() {
    const playlistName = document.getElementById('playlistNameInput')?.value?.trim();
    const songListText = document.getElementById('songListTextarea')?.value?.trim();

    const createBtn = document.getElementById('createPlaylistBtn');
    
    // Enable/disable button based on validation
    if (playlistName && songListText) {
        createBtn.disabled = false;
        createBtn.classList.remove('is-disabled');
    } else {
        createBtn.disabled = true;
        createBtn.classList.add('is-disabled');
    }
};

// Initialize event listeners when DOM is loaded
document.addEventListener('DOMContentLoaded', function() {
    const createBtn = document.getElementById('createPlaylistBtn');
    const clearBtn = document.getElementById('clearPlaylistFormBtn');
    const playlistNameInput = document.getElementById('playlistNameInput');
    const songListTextarea = document.getElementById('songListTextarea');

    if (createBtn) {
        createBtn.addEventListener('click', createPlaylistFromText);
    }

    if (clearBtn) {
        clearBtn.addEventListener('click', clearPlaylistForm);
    }

    // Add real-time validation
    if (playlistNameInput) {
        playlistNameInput.addEventListener('input', validatePlaylistForm);
    }

    if (songListTextarea) {
        songListTextarea.addEventListener('input', validatePlaylistForm);
    }

    // Initialize validation state
    validatePlaylistForm();
});