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
    maxAcceptableWaitTime: 60,
    // YouTube advanced options
    youtubeForceIpv4: false,
    youtubeForceIpv6: false,
    youtubeUserAgent: '',
    youtubeExtractorArgs: '',
    youtubeImpersonate: '',
    youtubeUpdateChannel: 'STABLE',
    youtubePlayerClient: ''
};

// Function to establish WebSocket connection
const connectWebSocket = () => {
    if (importWebSocket && importWebSocket.readyState === WebSocket.OPEN) {
        return; // Already connected
    }

    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    
    // Determine WebSocket URL dynamically
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const host = window.location.host;
    const wsUrl = `${protocol}//${host}/ws/import-status/${profileId}`;

    console.log('[Import] Connecting to WebSocket:', wsUrl);
    importWebSocket = new WebSocket(wsUrl);

    importWebSocket.onopen = () => {
        console.log('WebSocket connected for import status');
    };

    importWebSocket.onmessage = (event) => {
        const spotdlOutputTextarea = document.getElementById('spotdlOutput');
        const downloadBtn = document.getElementById('downloadBtn');
        const cancelDownloadBtn = document.getElementById('cancelDownloadBtn');
        
        // Check if the message is a JSON string (likely installation status)
        if (event.data.startsWith('{') && event.data.endsWith('}')) {
            try {
                const status = JSON.parse(event.data);
                window.parakeetInstalled = status.parakeetInstalled;
                if (!status.allInstalled) {
                    let warningText = "External tools not fully installed: ";
                    if (!status.pythonInstalled) warningText += "Python (" + status.pythonMessage + ") ";
                    if (!status.spotdlInstalled) warningText += "SpotDL (" + status.spotdlMessage + ") ";
                    if (!status.ffmpegInstalled) warningText += "FFmpeg (" + status.ffmpegMessage + ") ";
                    if (!status.parakeetInstalled) warningText += "Parakeet (" + status.parakeetMessage + ") ";
                    displayWarning(warningText);
                } else {
                    clearWarning();
                }
            } catch (e) {
                console.error("Failed to parse WebSocket message as JSON:", e);
                if (spotdlOutputTextarea) {
                    spotdlOutputTextarea.value += event.data + '\n';
                }
            }
        } else if (event.data === "[IMPORT_IN_PROGRESS]") {
            if (downloadBtn) downloadBtn.disabled = true;
            if (cancelDownloadBtn) cancelDownloadBtn.disabled = false;
            if (spotdlOutputTextarea) spotdlOutputTextarea.value += 'An import is already in progress. Displaying current output...\n';
        } else if (event.data === "[IMPORT_FINISHED]") {
            if (downloadBtn) downloadBtn.disabled = false;
            if (cancelDownloadBtn) cancelDownloadBtn.disabled = true;
            resetImportForm();
            if (spotdlOutputTextarea) spotdlOutputTextarea.value += 'Import process finished.\n';
        } else if (event.data && event.data.includes("cancelled")) {
            if (downloadBtn) downloadBtn.disabled = false;
            if (cancelDownloadBtn) cancelDownloadBtn.disabled = true;
            resetImportForm();
        } else {
            if (spotdlOutputTextarea) {
                spotdlOutputTextarea.value += event.data;
            }
        }
        if (spotdlOutputTextarea) {
            spotdlOutputTextarea.scrollTop = spotdlOutputTextarea.scrollHeight;
        }
    };

    importWebSocket.onclose = (event) => {
        console.log('WebSocket disconnected for import status', event);
        const spotdlOutputTextarea = document.getElementById('spotdlOutput');
        if (spotdlOutputTextarea) {
            spotdlOutputTextarea.value += '\nWebSocket disconnected.\n';
        }
        const downloadBtn = document.getElementById('downloadBtn');
        const cancelDownloadBtn = document.getElementById('cancelDownloadBtn');
        if (downloadBtn) downloadBtn.disabled = false;
        if (cancelDownloadBtn) cancelDownloadBtn.disabled = true;
        if (!event.wasClean) {
            displayWarning('WebSocket connection closed unexpectedly. Please check server logs.');
        }
    };

    importWebSocket.onerror = (error) => {
        console.error('WebSocket error for import status:', error);
        displayWarning('WebSocket error. Check console for details.');
        const downloadBtn = document.getElementById('downloadBtn');
        const cancelDownloadBtn = document.getElementById('cancelDownloadBtn');
        if (downloadBtn) downloadBtn.disabled = false;
        if (cancelDownloadBtn) cancelDownloadBtn.disabled = true;
    };
};

// Helper function to display warning messages
const displayWarning = (message) => {
    const spotdlWarningMessage = document.getElementById('spotdlWarningMessage');
    if (spotdlWarningMessage) {
        spotdlWarningMessage.textContent = message;
        spotdlWarningMessage.classList.remove('is-hidden');
    } else {
        console.warn("spotdlWarningMessage element not found. Message:", message);
        if (window.showToast) window.showToast(message, 'warning');
    }
};

// Helper function to clear warning messages
const clearWarning = () => {
    const spotdlWarningMessage = document.getElementById('spotdlWarningMessage');
    if (spotdlWarningMessage) {
        spotdlWarningMessage.textContent = '';
        spotdlWarningMessage.classList.add('is-hidden');
    }
};

// Function to reset the import form to a clean state
const resetImportForm = () => {
    const spotdlUrlInput = document.getElementById('spotdlUrl');
    const playlistSelect = document.getElementById('playlistSelect');
    const newPlaylistNameInput = document.getElementById('newPlaylistName');
    if (spotdlUrlInput) spotdlUrlInput.value = '';
    if (playlistSelect) playlistSelect.value = '';
    if (newPlaylistNameInput) newPlaylistNameInput.value = '';
};

// Expose a global function for starting import from search
window.startImportFromSearch = async (searchQuery) => {
    console.log('[Import] startImportFromSearch called with query:', searchQuery);
    clearWarning();
    
    if (!searchQuery) {
        displayWarning('Please enter a search query for Auto Find.');
        return;
    }

    const downloadFolderInput = document.getElementById('downloadFolder');
    let currentDownloadPath = downloadFolderInput ? downloadFolderInput.value : '';

    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';

    // If we are not on the import page, we need to fetch the default download path
    if (!currentDownloadPath) {
        try {
            console.log('[Import] Fetching default download path for Auto Find...');
            const response = await fetch(`/api/import/${profileId}/default-download-path`);
            if (response.ok) {
                const apiResponse = await response.json();
                if (apiResponse.data) {
                    currentDownloadPath = apiResponse.data;
                }
            }
        } catch (error) {
            console.error('[Import] Error fetching default download path:', error);
        }
    }

    if (!currentDownloadPath) {
        displayWarning('Please specify a download folder in the Import page settings.');
        // If we can't find the path, we might need to navigate to the import page
        if (window.app && window.app.navigate) {
            if (confirm('Music library path not configured. Would you like to go to the Import page to configure it?')) {
                window.app.navigate('/import');
            }
        }
        return;
    }

    const urls = [searchQuery];
    const format = importSettings.outputFormat || 'mp3';
    const downloadThreads = importSettings.downloadThreads || 4;
    const searchThreads = importSettings.searchThreads || 4;

    // Show feedback that something is happening
    if (window.showToast) window.showToast('Starting Auto Find for: ' + searchQuery, 'info');

    if (!importWebSocket || importWebSocket.readyState !== WebSocket.OPEN) {
        console.log('[Import] WebSocket not connected, connecting now...');
        connectWebSocket();
        // Wait a bit for connection
        setTimeout(() => sendStartImport(), 1000);
    } else {
        sendStartImport();
    }

    function sendStartImport() {
        if (importWebSocket && importWebSocket.readyState === WebSocket.OPEN) {
            const message = {
                type: 'start-import',
                urls,
                format,
                downloadThreads,
                searchThreads,
                downloadPath: currentDownloadPath,
                playlistName: null
            };
            importWebSocket.send(JSON.stringify(message));
            
            // If we are on the import page, clear output and disable button
            const spotdlOutputTextarea = document.getElementById('spotdlOutput');
            const downloadBtn = document.getElementById('downloadBtn');
            if (spotdlOutputTextarea) spotdlOutputTextarea.value = 'Auto Find started for: ' + searchQuery + '\n';
            if (downloadBtn) downloadBtn.disabled = true;
        } else {
            displayWarning('WebSocket connection failed. Cannot start Auto Find.');
        }
    }
};

window.initImportLogic = () => {
    console.log('[Import] Initializing Import Logic');
    const spotdlUrlInput = document.getElementById('spotdlUrl');
    const downloadFolderInput = document.getElementById('downloadFolder');
    const playlistSelect = document.getElementById('playlistSelect');
    const newPlaylistNameInput = document.getElementById('newPlaylistName');
    const downloadBtn = document.getElementById('downloadBtn');
    const spotdlOutputTextarea = document.getElementById('spotdlOutput');
    const spotdlWarningMessage = document.getElementById('spotdlWarningMessage');
    const cancelDownloadBtn = document.getElementById('cancelDownloadBtn');

    if (!spotdlUrlInput || !downloadBtn) {
        console.log('[Import] Key elements not found, skipping initialization');
        return;
    }

    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';

    const fetchImportSettings = async () => {
        try {
            const response = await fetch(`/api/settings/${profileId}`);
            if (response.ok) {
                const apiResponse = await response.json();
                if (apiResponse.data) {
                    importSettings = apiResponse.data;
                }
            }
        } catch (error) {
            console.error('Error fetching settings:', error);
        }
    };

    const fetchAndPopulatePlaylists = async () => {
        try {
            const response = await fetch(`/api/music/playlists/${profileId}`);
            if (response.ok) {
                const apiResponse = await response.json();
                if (apiResponse.data && playlistSelect) {
                    playlistSelect.innerHTML = '<option value="">-- Select existing playlist --</option>';
                    apiResponse.data.forEach(playlist => {
                        const option = document.createElement('option');
                        option.value = playlist.name;
                        option.textContent = playlist.name;
                        playlistSelect.appendChild(option);
                    });
                }
            }
        } catch (error) {
            console.error('Network error fetching playlists:', error);
        }
    };

    const setDefaultDownloadFolder = async () => {
        if (!downloadFolderInput) return;
        try {
            const response = await fetch(`/api/import/${profileId}/default-download-path`);
            if (response.ok) {
                const apiResponse = await response.json();
                if (apiResponse.data) {
                    downloadFolderInput.value = apiResponse.data;
                }
            }
        } catch (error) {
            console.error('Error fetching default download path:', error);
        }
    };

    // Initialize UI
    fetchAndPopulatePlaylists();
    fetchImportSettings();
    setDefaultDownloadFolder();
    
    // Advanced Configuration functions are global in this file scope if not careful
    // but they are assigned to window. so they should be fine.
    loadAdvancedConfiguration();
    setupUpdateButton();

    if (cancelDownloadBtn) {
        cancelDownloadBtn.disabled = true;
    }

    downloadBtn.onclick = () => {
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

        const urls = parseSongInput(rawInput);
        if (spotdlOutputTextarea) spotdlOutputTextarea.value = '';
        downloadBtn.disabled = true;
        
        if (cancelDownloadBtn) cancelDownloadBtn.disabled = false;

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
            connectWebSocket();
            downloadBtn.disabled = false;
        }
    };

    if (cancelDownloadBtn) {
        cancelDownloadBtn.onclick = () => {
            if (importWebSocket && importWebSocket.readyState === WebSocket.OPEN) {
                const message = { type: 'cancel-import' };
                importWebSocket.send(JSON.stringify(message));
                cancelDownloadBtn.disabled = true;
            }
        };
    }

    // Connect WebSocket
    connectWebSocket();
};

// Advanced configuration logic
const loadAdvancedConfiguration = async () => {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    try {
        const response = await fetch(`/api/settings/${profileId}`);
        if (response.ok) {
            const apiResponse = await response.json();
            if (apiResponse.data) {
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
                    maxAcceptableWaitTime: (apiResponse.data.maxAcceptableWaitTimeMs || 3600000) / 60000,
                    youtubeForceIpv4: apiResponse.data.youtubeForceIpv4 || false,
                    youtubeForceIpv6: apiResponse.data.youtubeForceIpv6 || false,
                    youtubeUserAgent: apiResponse.data.youtubeUserAgent || '',
                    youtubeExtractorArgs: apiResponse.data.youtubeExtractorArgs || '',
                    youtubeImpersonate: apiResponse.data.youtubeImpersonate || '',
                    youtubeUpdateChannel: apiResponse.data.youtubeUpdateChannel || 'STABLE',
                    youtubePlayerClient: apiResponse.data.youtubePlayerClient || ''
                });
                
                updateAdvancedConfigUI();
                loadYtDlpVersion();
            }
        }
    } catch (error) {
        console.error('Error loading advanced configuration:', error);
    }
};

const updateAdvancedConfigUI = () => {
    const youtubeEnabled = document.getElementById('youtubeEnabled');
    if (!youtubeEnabled) return;
    
    youtubeEnabled.checked = advancedConfig.youtubeEnabled;
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
    
    document.getElementById('youtubeForceIpv4').checked = advancedConfig.youtubeForceIpv4;
    document.getElementById('youtubeForceIpv6').checked = advancedConfig.youtubeForceIpv6;
    document.getElementById('youtubeUserAgent').value = advancedConfig.youtubeUserAgent || '';
    document.getElementById('youtubeExtractorArgs').value = advancedConfig.youtubeExtractorArgs || '';
    document.getElementById('youtubeImpersonate').value = advancedConfig.youtubeImpersonate || '';
    document.getElementById('youtubeUpdateChannel').value = advancedConfig.youtubeUpdateChannel || 'STABLE';
    document.getElementById('youtubePlayerClient').value = advancedConfig.youtubePlayerClient || '';
    
    updateSecondarySourceOptions();
};

const updateSecondarySourceOptions = () => {
    const primarySourceSelect = document.getElementById('primarySource');
    if (!primarySourceSelect) return;
    const primarySource = primarySourceSelect.value;
    const secondarySelect = document.getElementById('secondarySource');
    
    secondarySelect.innerHTML = '<option value="NONE">None</option>';
    if (primarySource === 'YOUTUBE') {
        secondarySelect.innerHTML += '<option value="SPOTDL">SpotDL</option>';
    } else if (primarySource === 'SPOTDL') {
        secondarySelect.innerHTML += '<option value="YOUTUBE">YouTube (yt-dlp)</option>';
    } else {
        secondarySelect.innerHTML += '<option value="SPOTDL">SpotDL</option>';
        secondarySelect.innerHTML += '<option value="YOUTUBE">YouTube (yt-dlp)</option>';
    }
};

const loadYtDlpVersion = async () => {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    try {
        const response = await fetch(`/api/settings/${profileId}/install-status`);
        if (response.ok) {
            const data = await response.json();
            if (data.data && data.data.ytdlpMessage) {
                const msg = data.data.ytdlpMessage;
                const versionMatch = msg.match(/yt-dlp[^\d]*(\d+\.\d+(\.\d+)?)/i);
                const versionElement = document.getElementById('ytdlpVersion');
                if (versionElement) {
                    versionElement.textContent = versionMatch ? versionMatch[1] : (msg.includes('found') ? 'Installed' : msg);
                }
            }
        }
    } catch (error) {
        const versionElement = document.getElementById('ytdlpVersion');
        if (versionElement) versionElement.textContent = 'Unknown';
    }
};

const setupUpdateButton = () => {
    const updateBtn = document.getElementById('updateYtDlpBtn');
    if (updateBtn) {
        updateBtn.onclick = window.updateYtDlp;
    }
    const primarySourceSelect = document.getElementById('primarySource');
    if (primarySourceSelect) {
        primarySourceSelect.onchange = updateSecondarySourceOptions;
    }
};

window.updateYtDlp = async () => {
    const updateBtn = document.getElementById('updateYtDlpBtn');
    const statusElement = document.getElementById('ytdlpUpdateStatus');
    if (!updateBtn || !statusElement) return;
    
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    const channel = document.getElementById('youtubeUpdateChannel').value;
    
    updateBtn.disabled = true;
    updateBtn.classList.add('is-loading');
    statusElement.textContent = 'Updating...';
    
    try {
        const response = await fetch(`/api/settings/${profileId}/update-yt-dlp?channel=${channel}`, { method: 'POST' });
        const data = await response.json();
        if (response.ok) {
            statusElement.textContent = 'Update initiated! Reloading in 5s...';
            setTimeout(() => {
                loadYtDlpVersion();
                updateBtn.disabled = false;
                updateBtn.classList.remove('is-loading');
            }, 5000);
        } else {
            statusElement.textContent = 'Update failed: ' + (data.error || 'Unknown error');
            updateBtn.disabled = false;
            updateBtn.classList.remove('is-loading');
        }
    } catch (error) {
        statusElement.textContent = 'Error: ' + error.message;
        updateBtn.disabled = false;
        updateBtn.classList.remove('is-loading');
    }
};

window.saveAdvancedConfiguration = async () => {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    try {
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
            maxAcceptableWaitTimeMs: parseInt(document.getElementById('maxAcceptableWaitTime').value) * 60000,
            youtubeForceIpv4: document.getElementById('youtubeForceIpv4').checked,
            youtubeForceIpv6: document.getElementById('youtubeForceIpv6').checked,
            youtubeUserAgent: document.getElementById('youtubeUserAgent').value,
            youtubeExtractorArgs: document.getElementById('youtubeExtractorArgs').value,
            youtubeImpersonate: document.getElementById('youtubeImpersonate').value,
            youtubeUpdateChannel: document.getElementById('youtubeUpdateChannel').value,
            youtubePlayerClient: document.getElementById('youtubePlayerClient').value
        };
        
        const response = await fetch(`/api/settings/${profileId}/import-sources`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(configData)
        });
        
        if (response.ok) {
            if (window.showToast) window.showToast('Advanced configuration saved successfully!', 'success');
            Object.assign(advancedConfig, configData);
        } else {
            const err = await response.json();
            if (window.showToast) window.showToast('Failed to save configuration: ' + (err.error || 'Unknown'), 'error');
        }
    } catch (error) {
        console.error('Error saving advanced configuration:', error);
    }
};

function parseSongInput(input) {
    if (!input || !input.trim()) return [];
    return input.split('\n').map(line => line.trim()).filter(line => line.length > 0);
}

// Initial connection for Auto Find support from other pages
document.addEventListener('DOMContentLoaded', () => {
    console.log('[Import] Initial main-page connection');
    connectWebSocket();
});
