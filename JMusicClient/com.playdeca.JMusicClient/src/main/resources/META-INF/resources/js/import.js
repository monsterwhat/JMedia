document.addEventListener('DOMContentLoaded', () => {
    const spotdlUrlInput = document.getElementById('spotdlUrl');
    const downloadFolderInput = document.getElementById('downloadFolder');
    const playlistSelect = document.getElementById('playlistSelect');
    const newPlaylistNameInput = document.getElementById('newPlaylistName');
    const downloadBtn = document.getElementById('downloadBtn');
    const spotdlOutputTextarea = document.getElementById('spotdlOutput');
    const spotdlWarningMessage = document.getElementById('spotdlWarningMessage');

    let importSettings = {};

    const fetchImportSettings = async () => {
        try {
            const response = await fetch('/api/settings');
            if (response.ok) {
                const apiResponse = await response.json();
                if (apiResponse.data) {
                    importSettings = apiResponse.data;
                }
            } else {
                console.error('Failed to fetch settings');
                displayWarning('Failed to load import settings.');
            }
        } catch (error) {
            console.error('Error fetching settings:', error);
            displayWarning('Error fetching import settings.');
        }
    };

    // Helper function to display warning messages
    const displayWarning = (message) => {
        spotdlWarningMessage.textContent = message;
        spotdlWarningMessage.classList.remove('is-hidden');
    };

    // Helper function to clear warning messages
    const clearWarning = () => {
        spotdlWarningMessage.textContent = '';
        spotdlWarningMessage.classList.add('is-hidden');
    };

    // Function to fetch and populate playlists
    const fetchAndPopulatePlaylists = async () => {
        try {
            const response = await fetch('/api/music/playlists');
            if (response.ok) {
                const apiResponse = await response.json();
                if (apiResponse.data) {
                    // Clear existing options except the default one
                    playlistSelect.innerHTML = '<option value="">-- Select existing playlist --</option>';
                    apiResponse.data.forEach(playlist => {
                        const option = document.createElement('option');
                        option.value = playlist.name; // Use playlist name as value
                        option.textContent = playlist.name;
                        playlistSelect.appendChild(option);
                    });
                }
            } else {
                console.error('Failed to fetch playlists:', response.status, response.statusText);
                displayWarning('Failed to load playlists. Please try again later.');
            }
        } catch (error) {
            console.error('Network error fetching playlists:', error);
            displayWarning('Network error fetching playlists.');
        }
    };

    // Fetch playlists on page load
    fetchAndPopulatePlaylists();
    fetchImportSettings();

    // Fetch default music library path and set default download folder
    const setDefaultDownloadFolder = async () => {
        try {
            // Change to the new endpoint
            const response = await fetch('/api/import/default-download-path');
            if (response.ok) {
                const apiResponse = await response.json();
                if (apiResponse.data) {
                    // Directly use the path provided by the backend
                    downloadFolderInput.value = apiResponse.data;
                } else {
                    // This case handles when the music library path is not configured on the backend
                    console.warn('Music library path not configured in settings. Defaulting to empty download folder.');
                    displayWarning('Music library path not configured in settings. Please configure it in the Settings page.');
                    downloadFolderInput.value = '';
                }
            } else {
                // This handles non-2xx status codes, like 404 if the path is not configured
                console.error('Failed to fetch default SpotDL download path:', response.status, response.statusText);
                displayWarning('Failed to fetch default SpotDL download path. Please ensure the music library path is configured in the Settings page.');
                downloadFolderInput.value = '';
            }
        } catch (error) {
            console.error('Network error fetching default SpotDL download path:', error);
            displayWarning('Network error fetching default SpotDL download path.');
            downloadFolderInput.value = '';
        }
    };

    setDefaultDownloadFolder(); // Call on page load

    let importWebSocket;

    // Function to establish WebSocket connection
    const connectWebSocket = () => {
        if (importWebSocket && importWebSocket.readyState === WebSocket.OPEN) {
            return; // Already connected
        }

        // Determine WebSocket URL dynamically
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const host = window.location.host;
        const wsUrl = `${protocol}//${host}/ws/import-status`;

        importWebSocket = new WebSocket(wsUrl);

        importWebSocket.onopen = () => {
            console.log('WebSocket connected for import status');
            // Initial messages (installation status, cached output, import in progress) will be sent by the server
            // No need to set initial text here.
        };

        importWebSocket.onmessage = (event) => {
            // Check if the message is a JSON string (likely installation status)
            if (event.data.startsWith('{') && event.data.endsWith('}')) {
                try {
                    const status = JSON.parse(event.data);
                    if (!status.allInstalled) {
                        let warningText = "External tools not fully installed: ";
                        if (!status.pythonInstalled) warningText += "Python (" + status.pythonMessage + ") ";
                        if (!status.spotdlInstalled) warningText += "SpotDL (" + status.spotdlMessage + ") ";
                        if (!status.ffmpegInstalled) warningText += "FFmpeg (" + status.ffmpegMessage + ") ";
                        displayWarning(warningText);
                    } else {
                        clearWarning();
                    }
                } catch (e) {
                    console.error("Failed to parse WebSocket message as JSON:", e);
                    spotdlOutputTextarea.value += event.data + '\n'; // Append as regular text if not JSON
                }
            } else if (event.data === "[IMPORT_IN_PROGRESS]") {
                downloadBtn.disabled = true;
                spotdlOutputTextarea.value += 'An import is already in progress. Displaying current output...\n';
            } else if (event.data === "[IMPORT_FINISHED]") {
                downloadBtn.disabled = false;
                spotdlOutputTextarea.value += 'Import process finished.\n';
                // Optionally, show a success/failure message based on the last few lines of output
            } else {
                spotdlOutputTextarea.value += event.data; // Append raw data, server sends newlines
            }
            spotdlOutputTextarea.scrollTop = spotdlOutputTextarea.scrollHeight; // Auto-scroll to bottom
        };

        importWebSocket.onclose = (event) => {
            console.log('WebSocket disconnected for import status', event);
            spotdlOutputTextarea.value += '\nWebSocket disconnected.\n';
            downloadBtn.disabled = false; // Re-enable button on close
            if (!event.wasClean) {
                displayWarning('WebSocket connection closed unexpectedly. Please check server logs.');
            }
        };

        importWebSocket.onerror = (error) => {
            console.error('WebSocket error for import status:', error);
            displayWarning('WebSocket error. Check console for details.');
            downloadBtn.disabled = false; // Re-enable button on error
        };
    };

    // Call connectWebSocket on page load
    connectWebSocket();

    if (downloadBtn) {
        downloadBtn.addEventListener('click', () => {
            clearWarning();
            const url = spotdlUrlInput.value;
            const format = importSettings.outputFormat || 'mp3';
            const downloadThreads = importSettings.downloadThreads || 4;
            const searchThreads = importSettings.searchThreads || 4;
            const downloadPath = downloadFolderInput.value;
            const selectedPlaylist = playlistSelect.value;
            const newPlaylistName = newPlaylistNameInput.value.trim();

            if (!url) {
                displayWarning('Please enter a Spotify or YouTube URL.');
                return;
            }
            if (!downloadPath) {
                displayWarning('Please specify a download folder.');
                return;
            }

            let playlistNameToSend = null;
            if (selectedPlaylist && newPlaylistName) {
                displayWarning('Please select an existing playlist OR enter a new playlist name, not both.');
                return;
            } else if (selectedPlaylist) {
                playlistNameToSend = selectedPlaylist;
            } else if (newPlaylistName) {
                playlistNameToSend = newPlaylistName;
            }

            // Clear output and disable button immediately
            spotdlOutputTextarea.value = '';
            downloadBtn.disabled = true;

            if (importWebSocket && importWebSocket.readyState === WebSocket.OPEN) {
                const message = {
                    type: 'start-import',
                    url,
                    format,
                    downloadThreads,
                    searchThreads,
                    downloadPath,
                    playlistName: playlistNameToSend
                };
                importWebSocket.send(JSON.stringify(message));
            } else {
                displayWarning('WebSocket is not connected. Attempting to reconnect...');
                connectWebSocket(); // Try to reconnect
                downloadBtn.disabled = false;
            }
        });
    }

    // External App Warning Logic
    const externalAppWarning = document.getElementById('externalAppWarning');
    const dismissWarningBtn = externalAppWarning ? externalAppWarning.querySelector('.delete') : null;
    const spotdlWarningShownKey = 'spotdlWarningShown';

    if (externalAppWarning) {
        if (localStorage.getItem(spotdlWarningShownKey) === 'true') {
            externalAppWarning.classList.add('is-hidden');
        } else {
            externalAppWarning.classList.remove('is-hidden'); // Ensure visible if not shown before
        }

        if (dismissWarningBtn) {
            dismissWarningBtn.addEventListener('click', () => {
                externalAppWarning.classList.add('is-hidden');
                localStorage.setItem(spotdlWarningShownKey, 'true');
            });
        }
    }
});