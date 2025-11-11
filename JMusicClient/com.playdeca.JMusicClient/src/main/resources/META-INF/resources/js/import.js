document.addEventListener('DOMContentLoaded', () => {
    const spotdlUrlInput = document.getElementById('spotdlUrl');
    const outputFormatSelect = document.getElementById('outputFormat');
    const downloadThreadsInput = document.getElementById('downloadThreads');
    const searchThreadsInput = document.getElementById('searchThreads');
    const downloadFolderInput = document.getElementById('downloadFolder');
    console.log('downloadFolderInput:', downloadFolderInput); // Diagnostic log
    const downloadBtn = document.getElementById('downloadBtn');
    const spotdlOutputTextarea = document.getElementById('spotdlOutput');
    const spotdlWarningMessage = document.getElementById('spotdlWarningMessage');

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
            spotdlOutputTextarea.value = 'WebSocket connected. Ready to start import...\n';
        };

        importWebSocket.onmessage = (event) => {
            spotdlOutputTextarea.value += event.data + '\n';
            spotdlOutputTextarea.scrollTop = spotdlOutputTextarea.scrollHeight; // Auto-scroll to bottom

            // Check for completion messages
            if (event.data.includes("download completed successfully")) {
                alert("Import completed successfully!");
            }
        };

        importWebSocket.onclose = (event) => {
            console.log('WebSocket disconnected for import status', event);
            spotdlOutputTextarea.value += '\nImport process finished or WebSocket disconnected.\n';
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
            const format = outputFormatSelect.value;
            const downloadThreads = parseInt(downloadThreadsInput.value);
            const searchThreads = parseInt(searchThreadsInput.value);
            const downloadPath = downloadFolderInput.value;

            if (!url) {
                displayWarning('Please enter a Spotify or YouTube URL.');
                return;
            }
            if (!downloadPath) {
                displayWarning('Please specify a download folder.');
                return;
            }

            spotdlOutputTextarea.value = 'Starting download via WebSocket...\n';
            downloadBtn.disabled = true;

            if (importWebSocket && importWebSocket.readyState === WebSocket.OPEN) {
                const message = {
                    type: 'start-import',
                    url,
                    format,
                    downloadThreads,
                    searchThreads,
                    downloadPath
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