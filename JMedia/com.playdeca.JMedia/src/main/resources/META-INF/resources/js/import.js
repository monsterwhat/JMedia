let importWebSocket;
let importSettings = {};

document.addEventListener('DOMContentLoaded', () => {
    const spotdlUrlInput = document.getElementById('spotdlUrl');
    const spotdlOutputTextarea = document.getElementById('spotdlOutput');
    const spotdlWarningMessage = document.getElementById('spotdlWarningMessage');
    const downloadBtn = document.getElementById('downloadBtn');
    const downloadFolderInput = document.getElementById('downloadFolder');
    const playlistSelect = document.getElementById('playlistSelect');
    const newPlaylistNameInput = document.getElementById('newPlaylistName');

    // Helper function to display warning messages
    const displayWarning = (message) => {
        if (spotdlWarningMessage) {
            spotdlWarningMessage.textContent = message;
            spotdlWarningMessage.classList.remove('is-hidden');
        } else {
            console.warn("spotdlWarningMessage element not found. Message:", message);
        }
    };

    // Helper function to clear warning messages
    const clearWarning = () => {
        if (spotdlWarningMessage) {
            spotdlWarningMessage.textContent = '';
            spotdlWarningMessage.classList.add('is-hidden');
        }
    };

    // Function to establish WebSocket connection
    const connectWebSocket = () => {
        if (importWebSocket && importWebSocket.readyState === WebSocket.OPEN) {
            return; // Already connected
        }

        // Determine WebSocket URL dynamically
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const host = window.location.host;
        const wsUrl = `${protocol}//${host}/ws/import-status/${globalActiveProfileId}`;

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
                    window.whisperInstalled = status.whisperInstalled;
                    if (!status.allInstalled) {
                        let warningText = "External tools not fully installed: ";
                        if (!status.pythonInstalled) warningText += "Python (" + status.pythonMessage + ") ";
                        if (!status.spotdlInstalled) warningText += "SpotDL (" + status.spotdlMessage + ") ";
                        if (!status.ffmpegInstalled) warningText += "FFmpeg (" + status.ffmpegMessage + ") ";
                        if (!status.whisperInstalled) warningText += "Whisper (" + status.whisperMessage + ") ";
                        displayWarning(warningText);
                    } else {
                        clearWarning();
                    }
                } catch (e) {
                    console.error("Failed to parse WebSocket message as JSON:", e);
                    if (spotdlOutputTextarea) {
                        spotdlOutputTextarea.value += event.data + '\n'; // Append as regular text if not JSON
                    }
                }
            } else if (event.data === "[IMPORT_IN_PROGRESS]") {
                if (downloadBtn) downloadBtn.disabled = true;
                if (spotdlOutputTextarea) spotdlOutputTextarea.value += 'An import is already in progress. Displaying current output...\n';
            } else if (event.data === "[IMPORT_FINISHED]") {
                if (downloadBtn) downloadBtn.disabled = false;
                if (spotdlOutputTextarea) spotdlOutputTextarea.value += 'Import process finished.\n';
                // Optionally, show a success/failure message based on the last few lines of output
            } else {
                if (spotdlOutputTextarea) {
                    spotdlOutputTextarea.value += event.data; // Append raw data, server sends newlines
                }
            }
            if (spotdlOutputTextarea) {
                spotdlOutputTextarea.scrollTop = spotdlOutputTextarea.scrollHeight; // Auto-scroll to bottom
            }
        };

        importWebSocket.onclose = (event) => {
            console.log('WebSocket disconnected for import status', event);
            if (spotdlOutputTextarea) {
                spotdlOutputTextarea.value += '\nWebSocket disconnected.\n';
            }
            if (downloadBtn) downloadBtn.disabled = false; // Re-enable button on close
            if (!event.wasClean) {
                displayWarning('WebSocket connection closed unexpectedly. Please check server logs.');
            }
        };

        importWebSocket.onerror = (error) => {
            console.error('WebSocket error for import status:', error);
            displayWarning('WebSocket error. Check console for details.');
            if (downloadBtn) downloadBtn.disabled = false; // Re-enable button on error
        };
    };

    // Expose a global function for starting import from search
    window.startImportFromSearch = (searchQuery) => {
        clearWarning();
        const urls = [searchQuery]; // Use the search query as a single URL for import
        const format = importSettings.outputFormat || 'mp3';
        const downloadThreads = importSettings.downloadThreads || 4;
        const searchThreads = importSettings.searchThreads || 4;
        const currentDownloadPath = downloadFolderInput ? downloadFolderInput.value : ''; // Get from input if exists

        if (!searchQuery) {
            displayWarning('Please enter a search query for Auto Find.');
            return;
        }
        if (!currentDownloadPath) {
            displayWarning('Please specify a download folder in the Import page settings.');
            return;
        }

        // Clear output and disable button immediately
        if (spotdlOutputTextarea) spotdlOutputTextarea.value = '';
        if (downloadBtn) { // Check if downloadBtn exists on the current page
            downloadBtn.disabled = true;
        }

        if (importWebSocket && importWebSocket.readyState === WebSocket.OPEN) {
            const message = {
                type: 'start-import',
                urls,
                format,
                downloadThreads,
                searchThreads,
                downloadPath: currentDownloadPath,
                playlistName: null // No specific playlist for auto find, user can add later
            };
            importWebSocket.send(JSON.stringify(message));
        } else {
            displayWarning('WebSocket is not connected. Attempting to reconnect...');
            connectWebSocket(); // Try to reconnect
            if (downloadBtn) {
                downloadBtn.disabled = false;
            }
        }
    };

    // Only run import.html specific logic if a key element is present
    if (spotdlUrlInput && downloadFolderInput && playlistSelect && newPlaylistNameInput && downloadBtn && spotdlOutputTextarea && spotdlWarningMessage) {
        const fetchImportSettings = async () => {
            try {
                const response = await fetch(`/api/settings/${globalActiveProfileId}`);
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

        // Function to fetch and populate playlists
        const fetchAndPopulatePlaylists = async () => {
            try {
                const response = await fetch(`/api/music/playlists/${globalActiveProfileId}`);
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
                const response = await fetch(`/api/import/${globalActiveProfileId}/default-download-path`);
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
        connectWebSocket(); // Connect WebSocket only if import elements are present

        if (downloadBtn) {
            downloadBtn.addEventListener('click', () => {
                clearWarning();
                const rawInput = spotdlUrlInput.value;
                const format = importSettings.outputFormat || 'mp3';
                const downloadThreads = importSettings.downloadThreads || 4;
                const searchThreads = importSettings.searchThreads || 4;
                const downloadPath = downloadFolderInput.value;
                const selectedPlaylist = playlistSelect.value;
                const newPlaylistName = newPlaylistNameInput.value.trim();

                if (!rawInput || !rawInput.trim()) {
                    displayWarning('Please enter song(s), URLs, or a song list.');
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

                // Parse the input to determine if it's a song list or single item
                const urls = parseSongInput(rawInput);

                // Clear output and disable button immediately
                spotdlOutputTextarea.value = '';
                downloadBtn.disabled = true;

                if (importWebSocket && importWebSocket.readyState === WebSocket.OPEN) {
                    const message = {
                        type: 'start-import',
                        urls,
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

        // Helper function to parse song input
        function parseSongInput(input) {
            if (!input || !input.trim()) {
                return [];
            }

            // Split by lines and remove empty lines
            const lines = input.split('\n')
                .map(line => line.trim())
                .filter(line => line.length > 0);

            // If only one line, treat as single item (could be a URL, search query, or single song)
            if (lines.length === 1) {
                return [lines[0]];
            }

            // Multiple lines - check if first line is a URL and others are metadata
            // For now, treat any multi-line input as a song list
            // In the future, we could detect playlist formats
            return lines;
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
    }
});