console.log('[Import] Script loading');
let importWebSocket;
let importSettings = {};
let advancedConfig = {
    primarySource: 'YOUTUBE',
    secondarySource: 'SPOTDL',
    youtubeEnabled: true,
    spotdlEnabled: true,
    maxRetryAttempts: 3,
    retryWaitTime: 90,
    switchStrategy: 'AFTER_FAILURES',
    switchThreshold: 3,
    enableSmartRateLimitHandling: true,
    fallbackOnLongWait: true,
    maxAcceptableWaitTime: 60
};

document.addEventListener('DOMContentLoaded', () => {
    console.log('[Import] DOMContentLoaded fired');
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

    // Load advanced configuration
    const loadAdvancedConfiguration = async () => {
        try {
            const response = await fetch(`/api/settings/${globalActiveProfileId}`);
            if (response.ok) {
                const apiResponse = await response.json();
                if (apiResponse.data) {
                    // Update advanced config with settings
                    Object.assign(advancedConfig, {
                        primarySource: apiResponse.data.primarySource || 'YOUTUBE',
                        secondarySource: apiResponse.data.secondarySource || 'SPOTDL',
                        youtubeEnabled: apiResponse.data.youtubeEnabled !== false,
                        spotdlEnabled: apiResponse.data.spotdlEnabled !== false,
                        maxRetryAttempts: apiResponse.data.maxRetryAttempts || 3,
                        retryWaitTime: (apiResponse.data.retryWaitTimeMs || 90000) / 1000,
                        switchStrategy: apiResponse.data.switchStrategy || 'AFTER_FAILURES',
                        switchThreshold: apiResponse.data.switchThreshold || 3,
                        enableSmartRateLimitHandling: apiResponse.data.enableSmartRateLimitHandling !== false,
                        fallbackOnLongWait: apiResponse.data.fallbackOnLongWait !== false,
                        maxAcceptableWaitTime: (apiResponse.data.maxAcceptableWaitTimeMs || 3600000) / 60000
                    });
                    
                    // Update UI elements
                    updateAdvancedConfigUI();
                }
            }
        } catch (error) {
            console.error('Error loading advanced configuration:', error);
            displayWarning('Failed to load advanced configuration.');
        }
    };

    // Update UI with loaded configuration
    const updateAdvancedConfigUI = () => {
        document.getElementById('youtubeEnabled').checked = advancedConfig.youtubeEnabled;
        document.getElementById('spotdlEnabled').checked = advancedConfig.spotdlEnabled;
        document.getElementById('enableSmartRateLimitHandling').checked = advancedConfig.enableSmartRateLimitHandling;
        document.getElementById('fallbackOnLongWait').checked = advancedConfig.fallbackOnLongWait;
        
        document.getElementById('primarySource').value = advancedConfig.primarySource;
        document.getElementById('secondarySource').value = advancedConfig.secondarySource;
        document.getElementById('switchStrategy').value = advancedConfig.switchStrategy;
        
        document.getElementById('maxRetryAttempts').value = advancedConfig.maxRetryAttempts;
        document.getElementById('retryWaitTime').value = advancedConfig.retryWaitTime;
        document.getElementById('switchThreshold').value = advancedConfig.switchThreshold;
        document.getElementById('maxAcceptableWaitTime').value = advancedConfig.maxAcceptableWaitTime;
        
        updateSecondarySourceOptions();
    };

    // Update secondary source based on primary selection
    const updateSecondarySourceOptions = () => {
        const primarySource = document.getElementById('primarySource').value;
        const secondarySelect = document.getElementById('secondarySource');
        
        secondarySelect.innerHTML = '<option value="NONE">None</option>';
        
        if (primarySource === 'YOUTUBE') {
            secondarySelect.innerHTML += '<option value="SPOTDL">SpotDL</option>';
        } else if (primarySource === 'SPOTDL') {
            secondarySelect.innerHTML += '<option value="YOUTUBE">YouTube (yt-dlp)</option>';
        } else {
            // Primary is NONE - show both options
            secondarySelect.innerHTML += '<option value="SPOTDL">SpotDL</option>';
            secondarySelect.innerHTML += '<option value="YOUTUBE">YouTube (yt-dlp)</option>';
        }
    };

    // Save advanced configuration
    window.saveAdvancedConfiguration = async () => {
        console.log('[Import] saveAdvancedConfiguration called');
        console.log('[Import] globalActiveProfileId:', globalActiveProfileId);
        
        try {
            // Validate configuration
            if (!validateAdvancedConfig()) {
                console.log('[Import] Validation failed');
                return;
            }
            
            // Prepare data for API
            const configData = {
                primarySource: document.getElementById('primarySource').value,
                secondarySource: document.getElementById('secondarySource').value,
                youtubeEnabled: document.getElementById('youtubeEnabled').checked,
                spotdlEnabled: document.getElementById('spotdlEnabled').checked,
                enableSmartRateLimitHandling: document.getElementById('enableSmartRateLimitHandling').checked,
                maxRetryAttempts: parseInt(document.getElementById('maxRetryAttempts').value),
                retryWaitTimeMs: parseInt(document.getElementById('retryWaitTime').value) * 1000,
                switchStrategy: document.getElementById('switchStrategy').value,
                switchThreshold: parseInt(document.getElementById('switchThreshold').value),
                fallbackOnLongWait: document.getElementById('fallbackOnLongWait').checked,
                maxAcceptableWaitTimeMs: parseInt(document.getElementById('maxAcceptableWaitTime').value) * 60000
            };
            
            console.log('[Import] Sending config:', configData);
            
            const response = await fetch(`/api/settings/${globalActiveProfileId}/import-sources`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(configData)
            });
            
            console.log('[Import] Response status:', response.status);
            
            if (response.ok) {
                showToast('Advanced configuration saved successfully!', 'success');
                // Update local config
                Object.assign(advancedConfig, configData);
            } else {
                const errorResponse = await response.json();
                console.error('[Import] Save failed:', errorResponse);
                showToast('Failed to save configuration: ' + (errorResponse.error || 'Unknown error'), 'error');
            }
        } catch (error) {
            console.error('Error saving advanced configuration:', error);
            showToast('Error saving advanced configuration', 'error');
        }
    };

    // Validate advanced configuration
    const validateAdvancedConfig = () => {
        const youtubeEnabled = document.getElementById('youtubeEnabled').checked;
        const spotdlEnabled = document.getElementById('spotdlEnabled').checked;
        const primarySource = document.getElementById('primarySource').value;
        const secondarySource = document.getElementById('secondarySource').value;
        const maxRetryAttempts = parseInt(document.getElementById('maxRetryAttempts').value);
        const switchThreshold = parseInt(document.getElementById('switchThreshold').value);
        
        console.log('[Import] Validating - youtubeEnabled:', youtubeEnabled, 'spotdlEnabled:', spotdlEnabled);
        console.log('[Import] Validating - primarySource:', primarySource, 'secondarySource:', secondarySource);
        
        if (!youtubeEnabled && !spotdlEnabled) {
            displayError('At least one download source must be enabled');
            return false;
        }
        
        if (primarySource === secondarySource) {
            displayError('Primary and secondary sources must be different');
            return false;
        }
        
        if (!youtubeEnabled && primarySource === 'YOUTUBE') {
            displayError('YouTube selected as primary but not enabled');
            return false;
        }
        
        if (!spotdlEnabled && secondarySource === 'SPOTDL') {
            displayError('SpotDL selected as secondary but not enabled');
            return false;
        }
        
        if (switchThreshold > maxRetryAttempts) {
            displayError('Failure threshold cannot be greater than max retry attempts');
            return false;
        }
        
        console.log('[Import] Validation passed');
        return true;
    };

    // Test configuration with a sample download
    const testConfiguration = async () => {
        if (!validateAdvancedConfig()) {
            return;
        }
        
        showToast('Testing configuration with sample search...', 'info');
        
        try {
            // Use a simple search query for testing
            const testQuery = 'test song';
            
            if (importWebSocket && importWebSocket.readyState === WebSocket.OPEN) {
                const message = {
                    type: 'start-import',
                    urls: [testQuery],
                    format: importSettings.outputFormat || 'mp3',
                    downloadThreads: 1, // Use minimal threads for testing
                    searchThreads: 1,
                    downloadPath: downloadFolderInput.value,
                    playlistName: null,
                    testMode: true // Flag this as a test
                };
                importWebSocket.send(JSON.stringify(message));
            } else {
                displayError('WebSocket not connected for testing');
            }
        } catch (error) {
            console.error('Error testing configuration:', error);
            displayError('Failed to test configuration');
        }
    };

    // Error display functions
    const displayError = (message) => {
        const statusDiv = document.getElementById('configStatus');
        statusDiv.className = 'notification is-danger';
        statusDiv.textContent = message;
        statusDiv.classList.remove('is-hidden');
    };

    const showConfigSuccess = (message) => {
        const statusDiv = document.getElementById('configStatus');
        statusDiv.className = 'notification is-success';
        statusDiv.textContent = message;
        statusDiv.classList.remove('is-hidden');
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
        loadAdvancedConfiguration();

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

        // Event listeners for advanced configuration
        // Primary source change handler
        const primarySourceSelect = document.getElementById('primarySource');
        if (primarySourceSelect) {
            primarySourceSelect.addEventListener('change', updateSecondarySourceOptions);
        }
        
        // Save configuration handler
        const saveBtn = document.getElementById('saveAdvancedConfigBtn');
        if (saveBtn) {
            saveBtn.addEventListener('click', saveAdvancedConfiguration);
        }
        
        // Reset configuration handler
        const resetBtn = document.getElementById('resetAdvancedConfigBtn');
        if (resetBtn) {
            resetBtn.addEventListener('click', () => {
                if (confirm('Reset all advanced settings to defaults?')) {
                    // Reset to defaults and save
                    const defaults = {
                        primarySource: 'YOUTUBE',
                        secondarySource: 'SPOTDL',
                        youtubeEnabled: true,
                        spotdlEnabled: true,
                        maxRetryAttempts: 3,
                        retryWaitTime: 90,
                        switchStrategy: 'AFTER_FAILURES',
                        switchThreshold: 3,
                        enableSmartRateLimitHandling: true,
                        fallbackOnLongWait: true,
                        maxAcceptableWaitTime: 60
                    };
                    
                    Object.assign(advancedConfig, defaults);
                    updateAdvancedConfigUI();
                    saveAdvancedConfiguration();
                }
            });
        }
            
        // Test configuration handler
        const testBtn = document.getElementById('testConfigurationBtn');
        if (testBtn) {
            testBtn.addEventListener('click', testConfiguration);
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