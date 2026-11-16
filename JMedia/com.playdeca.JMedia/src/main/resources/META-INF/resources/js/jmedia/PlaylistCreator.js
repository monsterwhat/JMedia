(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    JMedia.PlaylistCreator = {};

    let playlistResultsWarned = false;

    JMedia.PlaylistCreator.createFromText = async function() {
        const profileId = JMedia.Helpers.getActiveProfileId();
        if (!profileId) {
            if (window.Toast) Toast.error("No active profile found");
            return;
        }

        const playlistName = document.getElementById('playlistNameInput')?.value?.trim();
        const description = document.getElementById('playlistDescriptionInput')?.value?.trim();
        const songListText = document.getElementById('songListTextarea')?.value?.trim();

        if (!playlistName) {
            if (window.Toast) Toast.warning("Please enter a playlist name");
            document.getElementById('playlistNameInput')?.focus();
            return;
        }
        if (!songListText) {
            if (window.Toast) Toast.warning("Please paste your song list");
            document.getElementById('songListTextarea')?.focus();
            return;
        }

        const textLines = songListText.split('\n').map(l => l.trim()).filter(l => l.length > 0);
        if (textLines.length === 0) {
            if (window.Toast) Toast.warning("Please enter at least one song");
            return;
        }

        const createBtn = document.getElementById('createPlaylistBtn');
        const originalBtnContent = createBtn.innerHTML;
        createBtn.disabled = true;
        createBtn.classList.add('is-loading');
        createBtn.innerHTML = '<span class="icon-text"><span class="icon"><i class="pi pi-spinner pi-spin"></i></span><span>Creating Playlist...</span></span>';

        try {
            const response = await fetch(`/api/music/playlists/create-from-text/${profileId}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ playlistName, description, textLines })
            });
            const result = await response.json();
            if (response.ok && result.success) {
                const data = result.data;
                JMedia.PlaylistCreator.displayResults(data);
                if (window.Toast) Toast.success(`Playlist "${data.playlist.name}" created successfully!`);
                JMedia.PlaylistCreator.clearForm();
            } else {
                throw new Error(result.error || 'Failed to create playlist');
            }
        } catch (error) {
            console.error('Error creating playlist from text:', error);
            if (window.Toast) Toast.error('Failed to create playlist: ' + error.message);
        } finally {
            createBtn.disabled = false;
            createBtn.classList.remove('is-loading');
            createBtn.innerHTML = originalBtnContent;
        }
    };

    JMedia.PlaylistCreator.displayResults = function(data) {
        const resultsDiv = document.getElementById('playlistResults');
        const resultsMessage = document.getElementById('resultsMessage');
        const unmatchedSection = document.getElementById('unmatchedSection');
        const unmatchedList = document.getElementById('unmatchedList');
        const viewPlaylistBtn = document.getElementById('viewPlaylistBtn');
        if (!resultsDiv || !resultsMessage) {
            if (!playlistResultsWarned) {
                console.warn('[PlaylistCreator] results container missing');
                playlistResultsWarned = true;
            }
            const fallback = resultsDiv || document.querySelector('[data-playlist-results]');
            if (fallback) {
                fallback.style.display = 'block';
                if (data && data.message) fallback.textContent = data.message;
            }
            return;
        }

        resultsDiv.style.display = 'block';
        resultsMessage.textContent = data.message;

        if (unmatchedSection) {
            if (data.unmatchedLines && data.unmatchedLines.length > 0) {
                unmatchedSection.style.display = 'block';
                if (unmatchedList) {
                    unmatchedList.innerHTML = data.unmatchedLines.map(line =>
                        `<div class="mb-1">• ${JMedia.Helpers.escapeHtml(line)}</div>`
                    ).join('');
                }
            } else {
                unmatchedSection.style.display = 'none';
            }
        }

        if (viewPlaylistBtn) {
            if (data.playlist && data.playlist.id) {
                viewPlaylistBtn.onclick = () => { window.location.href = `/?playlist=${data.playlist.id}`; };
                viewPlaylistBtn.style.display = 'inline-flex';
            } else {
                viewPlaylistBtn.style.display = 'none';
            }
        }
        resultsDiv.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    };

    JMedia.PlaylistCreator.clearForm = function() {
        const nameInput = document.getElementById('playlistNameInput');
        const descInput = document.getElementById('playlistDescriptionInput');
        const textArea = document.getElementById('songListTextarea');
        const results = document.getElementById('playlistResults');
        if (nameInput) nameInput.value = '';
        if (descInput) descInput.value = '';
        if (textArea) textArea.value = '';
        if (results) results.style.display = 'none';
        if (nameInput) nameInput.focus();
    };

    JMedia.PlaylistCreator.validateForm = function() {
        const playlistName = document.getElementById('playlistNameInput')?.value?.trim();
        const songListText = document.getElementById('songListTextarea')?.value?.trim();
        const createBtn = document.getElementById('createPlaylistBtn');
        if (!createBtn) return;
        if (playlistName && songListText) {
            createBtn.disabled = false;
            createBtn.classList.remove('is-disabled');
        } else {
            createBtn.disabled = true;
            createBtn.classList.add('is-disabled');
        }
    };

    window.createPlaylistFromText = JMedia.PlaylistCreator.createFromText;
    window.displayPlaylistResults = JMedia.PlaylistCreator.displayResults;
    window.clearPlaylistForm = JMedia.PlaylistCreator.clearForm;
    window.validatePlaylistForm = JMedia.PlaylistCreator.validateForm;

    document.addEventListener('click', function(e) {
        const origin = e.target && e.target.closest ? e.target : null;
        if (!origin) return;
        if (origin.closest('#createPlaylistBtn')) {
            JMedia.PlaylistCreator.createFromText();
            return;
        }
        if (origin.closest('#clearPlaylistFormBtn')) {
            JMedia.PlaylistCreator.clearForm();
            return;
        }
    });

    document.addEventListener('DOMContentLoaded', function() {
        const playlistNameInput = document.getElementById('playlistNameInput');
        const songListTextarea = document.getElementById('songListTextarea');

        if (playlistNameInput) playlistNameInput.addEventListener('input', JMedia.PlaylistCreator.validateForm);
        if (songListTextarea) songListTextarea.addEventListener('input', JMedia.PlaylistCreator.validateForm);
        JMedia.PlaylistCreator.validateForm();
    });

})(window);
