document.addEventListener('DOMContentLoaded', () => {
    const spotdlUrlInput = document.getElementById('spotdlUrl');
    const downloadBtn = document.getElementById('downloadBtn');
    const spotdlWarningMessage = document.getElementById('spotdlWarningMessage');

    let importSettings = {};
    let defaultDownloadPath = '';

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

    const setDefaultDownloadFolder = async () => {
        try {
            const response = await fetch('/api/import/default-download-path');
            if (response.ok) {
                const apiResponse = await response.json();
                if (apiResponse.data) {
                    defaultDownloadPath = apiResponse.data;
                } else {
                    displayWarning('Music library path not configured in settings. Please configure it in the Settings page.');
                }
            } else {
                displayWarning('Failed to fetch default download path. Please ensure the music library path is configured in the Settings page.');
            }
        } catch (error) {
            console.error('Network error fetching default download path:', error);
            displayWarning('Network error fetching default download path.');
        }
    };

    const displayWarning = (message) => {
        spotdlWarningMessage.textContent = message;
        spotdlWarningMessage.classList.remove('is-hidden');
    };

    const clearWarning = () => {
        spotdlWarningMessage.textContent = '';
        spotdlWarningMessage.classList.add('is-hidden');
    };

    fetchImportSettings();
    setDefaultDownloadFolder();

    let importWebSocket;

    const connectWebSocket = () => {
        if (importWebSocket && importWebSocket.readyState === WebSocket.OPEN) {
            return;
        }

        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const host = window.location.host;
        const wsUrl = `${protocol}//${host}/ws/import-status`;

        importWebSocket = new WebSocket(wsUrl);

        importWebSocket.onopen = () => {
            console.log('WebSocket connected for Find and Play');
        };

        importWebSocket.onmessage = (event) => {
            console.log("Received from server:", event.data);
            if (event.data.includes("song-queued")) {
                // In the future, we can parse this and show a notification
                alert("Song has been downloaded and added to the queue!");
            } else if (event.data === "[IMPORT_IN_PROGRESS]") {
                downloadBtn.disabled = true;
                displayWarning('An import is already in progress. Please wait.');
            } else if (event.data === "[IMPORT_FINISHED]") {
                downloadBtn.disabled = false;
                clearWarning();
            }
        };

        importWebSocket.onclose = (event) => {
            console.log('WebSocket disconnected', event);
            downloadBtn.disabled = false;
            if (!event.wasClean) {
                displayWarning('WebSocket connection closed unexpectedly.');
            }
        };

        importWebSocket.onerror = (error) => {
            console.error('WebSocket error:', error);
            displayWarning('WebSocket error. Check console for details.');
            downloadBtn.disabled = false;
        };
    };

    connectWebSocket();

    if (downloadBtn) {
        downloadBtn.addEventListener('click', () => {
            clearWarning();
            const url = spotdlUrlInput.value;
            const format = importSettings.outputFormat || 'mp3';
            const downloadThreads = importSettings.downloadThreads || 4;
            const searchThreads = importSettings.searchThreads || 4;

            if (!url) {
                displayWarning('Please enter a song name or a Spotify or YouTube URL.');
                return;
            }
            if (!defaultDownloadPath) {
                displayWarning('Default download path is not set. Please configure it in Settings.');
                return;
            }

            downloadBtn.disabled = true;
            displayWarning('Finding and downloading song... please wait.');

            if (importWebSocket && importWebSocket.readyState === WebSocket.OPEN) {
                const message = {
                    type: 'start-import',
                    url,
                    format,
                    downloadThreads,
                    searchThreads,
                    downloadPath: defaultDownloadPath,
                    playlistName: null, // No playlist for Find and Play
                    queueAfterDownload: true // New flag
                };
                importWebSocket.send(JSON.stringify(message));
            } else {
                displayWarning('WebSocket is not connected. Attempting to reconnect...');
                connectWebSocket();
                downloadBtn.disabled = false;
            }
        });
    }
    
    // External App Warning Logic
    const externalAppWarning = document.getElementById('externalAppWarning');
    const dismissWarningBtn = externalAppWarning ? externalAppWarning.querySelector('.delete') : null;
    const playWarningShownKey = 'playWarningShown';

    if (externalAppWarning) {
        if (localStorage.getItem(playWarningShownKey) === 'true') {
            externalAppWarning.classList.add('is-hidden');
        } else {
            externalAppWarning.classList.remove('is-hidden');
        }

        if (dismissWarningBtn) {
            dismissWarningBtn.addEventListener('click', () => {
                externalAppWarning.classList.add('is-hidden');
                localStorage.setItem(playWarningShownKey, 'true');
            });
        }
    }
});
