window.resetLibrary = async function () {
    const res = await fetch(`/api/settings/${globalActiveProfileId}/resetLibrary`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] Library reset to:", json.data.libraryPath);
        showToast("Library reset to default path", 'success');
        const pathInputElem = document.getElementById("musicLibraryPathInput");
        if (pathInputElem)
            pathInputElem.value = json.data.libraryPath;
    } else {
        console.error("[Settings] Failed to reset library:", json.error);
        showToast("Failed to reset library: " + (json.error || "Unknown error"), 'error');
    }
};

window.scanLibrary = async function () {
    const res = await fetch(`/api/settings/${globalActiveProfileId}/scanLibrary`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] Scan started");
        showToast("Library scan started", 'success');
    } else {
        console.error("[Settings] Failed to scan library:", json.error);
        showToast("Failed to scan library: " + (json.error || "Unknown error"), 'error');
    }
};

window.clearLogs = async function () {
    const res = await fetch(`/api/settings/${globalActiveProfileId}/clearLogs`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] Logs cleared");
        showToast("Logs cleared successfully", 'success');
        const logsPanel = document.getElementById("logsPanel");
        if (logsPanel) {
            logsPanel.innerHTML = "";
        }
    } else {
        console.error("[Settings] Failed to clear logs:", json.error);
        showToast("Failed to clear logs: " + (json.error || "Unknown error"), 'error');
    }
};

window.clearSongsDB = async function () {
    const res = await fetch(`/api/settings/${globalActiveProfileId}/clearSongs`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] All songs deleted");
        showToast("All songs cleared from database", 'success');
    } else {
        console.error("[Settings] Failed to clear songs DB:", json.error);
        showToast("Failed to clear songs: " + (json.error || "Unknown error"), 'error');
    }
};

window.setupLogWebSocket = function () {
    const logsPanel = document.getElementById("logsPanel");
    if (!logsPanel)
        return;

    const socket = new WebSocket(`ws://${window.location.host}/api/logs/ws/${globalActiveProfileId}`);

    socket.onmessage = function (event) {
        const message = JSON.parse(event.data);
        if (message.type === "log") {
            const p = document.createElement("p");
            p.textContent = message.payload;
            logsPanel.appendChild(p);
            logsPanel.scrollTop = logsPanel.scrollHeight;
        }
    };

    socket.onopen = function () {
        console.log("Log WebSocket connected.");
    };

    socket.onclose = function () {
        console.log("Log WebSocket disconnected. Attempting to reconnect...");
        setTimeout(window.setupLogWebSocket, 3000); // Reconnect after 3 seconds
    };

    socket.onerror = function (error) {
        console.error("Log WebSocket error:", error);
    };
}

window.refreshSettingsUI = async function () {
    const res = await fetch(`/api/settings/${globalActiveProfileId}`);
    const json = await res.json();
    if (res.ok && json.data) {
        const pathElem = document.getElementById("musicLibraryPath");
        if (pathElem && json.data.libraryPath)
            pathElem.textContent = json.data.libraryPath;
    } else {
        console.error("[Settings] Failed to refresh settings UI:", json.error);
    }
};

window.reloadMetadata = async function () {
    const res = await fetch(`/api/settings/${globalActiveProfileId}/reloadMetadata`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] Metadata reload started");
        showToast("Metadata reload started", 'success');
    } else {
        console.error("[Settings] Failed to reload metadata:", json.error);
        showToast("Failed to reload metadata: " + (json.error || "Unknown error"), 'error');
    }
};

window.deleteDuplicates = async function () {
    const res = await fetch(`/api/settings/${globalActiveProfileId}/deleteDuplicates`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] Duplicate deletion started");
        showToast("Duplicate deletion started", 'success');
    } else {
        console.error("[Settings] Failed to delete duplicates:", json.error);
        showToast("Failed to delete duplicates: " + (json.error || "Unknown error"), 'error');
    }
};

window.saveImportSettings = async function () {
    const outputFormat = document.getElementById('outputFormat').value;
    const downloadThreads = parseInt(document.getElementById('downloadThreads').value);
    const searchThreads = parseInt(document.getElementById('searchThreads').value);

    const settings = {
        outputFormat,
        downloadThreads,
        searchThreads
    };

    const res = await fetch(`/api/settings/${globalActiveProfileId}/import`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(settings)
    });

    if (res.ok) {
        console.log('[Settings] Import settings saved.');
        showToast('Import settings saved successfully', 'success');
    } else {
        console.error('[Settings] Failed to save import settings.');
        showToast('Failed to save import settings', 'error');
    }
};


window.refreshSettingsUI = async function () {
    console.log("[Settings] refreshSettingsUI called.");
    const res = await fetch(`/api/settings/${globalActiveProfileId}`);
    const json = await res.json();
    if (res.ok && json.data) {
        // Music library path
        const pathInputElem = document.getElementById("musicLibraryPathInput");
        if (pathInputElem && json.data.libraryPath)
            pathInputElem.value = json.data.libraryPath;

        // Video library path
        const videoPathInputElem = document.getElementById("videoLibraryPathInput");
        if (videoPathInputElem && json.data.videoLibraryPath)
            videoPathInputElem.value = json.data.videoLibraryPath;

        // Run as service toggle
        const runAsServiceToggle = document.getElementById("runAsServiceToggle");
        if (runAsServiceToggle) {
            runAsServiceToggle.checked = json.data.runAsService;
        }

        // Import settings
        const outputFormatSelect = document.getElementById("outputFormat");
        if (outputFormatSelect && json.data.outputFormat) {
            outputFormatSelect.value = json.data.outputFormat;
        }

        const downloadThreadsInput = document.getElementById("downloadThreads");
        if (downloadThreadsInput && json.data.downloadThreads) {
            downloadThreadsInput.value = json.data.downloadThreads;
        }

        const searchThreadsInput = document.getElementById("searchThreads");
        if (searchThreadsInput && json.data.searchThreads) {
            searchThreadsInput.value = json.data.searchThreads;
        }

    } else {
        console.error("[Settings] Failed to refresh settings UI:", json.error);
    }
};

// Generic function to toggle card content visibility
window.toggleCardContent = function (button, contentId) {
    const content = document.getElementById(contentId);
    const icon = button.querySelector('i');

    if (content && icon) {
        const isHidden = content.classList.toggle('is-hidden');
        icon.classList.toggle('pi-angle-down');
        icon.classList.toggle('pi-angle-up');
        localStorage.setItem(`cardState-${contentId}`, isHidden);
    }
};

// Generic confirmation dialog
function showConfirmationDialog(message, callback) {
    if (confirm(message)) {
        callback();
    }
}

document.addEventListener("DOMContentLoaded", () => {
    // Load saved card states
    ['libraryManagementContent', 'logsCardContent', 'importSettingsContent', 'importInstallationContent'].forEach(contentId => {
        const isHidden = localStorage.getItem(`cardState-${contentId}`);
        const content = document.getElementById(contentId);
        const button = document.getElementById(`toggle${contentId.charAt(0).toUpperCase() + contentId.slice(1)}Btn`);

        if (content && isHidden === 'true') {
            content.classList.add('is-hidden');
            if (button) {
                const icon = button.querySelector('i');
                if (icon) {
                    icon.classList.remove('pi-angle-down');
                    icon.classList.add('pi-angle-up');
                }
            }
        }
    });

    document.getElementById("resetLibrary").onclick = () => showConfirmationDialog("Are you sure you want to reset the library path?", window.resetLibrary);
    document.getElementById("scanLibrary").onclick = () => window.scanLibrary();

    document.getElementById("clearSongs").onclick = () => showConfirmationDialog("Are you sure you want to clear all songs from the database? This action cannot be undone.", window.clearSongsDB);
    document.getElementById("clearLogs").onclick = () => showConfirmationDialog("Are you sure you want to clear all logs?", window.clearLogs);
    document.getElementById("clearPlaybackHistory").onclick = () => showConfirmationDialog("Are you sure you want to clear the playback history? This action cannot be undone.", async () => {
        try {
            const res = await fetch(`/api/settings/clearPlaybackHistory/${globalActiveProfileId}`, {method: 'POST'});
            if (res.ok) {
                showToast("Playback history cleared successfully", 'success');
            } else {
                showToast("Failed to clear playback history", 'error');
            }
        } catch (error) {
            showToast("Error clearing playback history", 'error');
        }
    });
    document.getElementById("reloadMetadata").onclick = () => showConfirmationDialog("Are you sure you want to reload all song metadata? This might take a while.", window.reloadMetadata);
    document.getElementById("deleteDuplicates").onclick = () => showConfirmationDialog("Are you sure you want to delete duplicate songs? This action cannot be undone.", window.deleteDuplicates);
    document.getElementById("saveImportSettingsBtn").onclick = window.saveImportSettings;

    // Save path buttons with toast notifications
    const saveMusicLibraryPathBtn = document.getElementById("saveMusicLibraryPathBtn");
    if (saveMusicLibraryPathBtn) {
        saveMusicLibraryPathBtn.addEventListener('htmx:afterRequest', function(evt) {
            if (evt.detail.successful) {
                showToast("Music library path saved successfully", 'success');
            } else {
                showToast("Failed to save music library path", 'error');
            }
        });
    }

    const browseMusicFolderBtn = document.getElementById("browseMusicFolderBtn");
    if (browseMusicFolderBtn) {
        browseMusicFolderBtn.onclick = async () => {
            try {
                const res = await fetch(`/api/settings/${globalActiveProfileId}/browse-folder`);
                
                // Handle case where user cancels folder selection (NO_CONTENT status)
                if (res.status === 204) {
                    console.log("[Settings] Folder selection cancelled by user");
                    return; // Silently return - no notification needed for cancel
                }
                
                if (!res.ok) {
                    throw new Error(`HTTP ${res.status}: ${res.statusText}`);
                }
                
                const json = await res.json();
                if (json.data) {
                    const pathInputElem = document.getElementById("musicLibraryPathInput");
                    if (pathInputElem) {
                        pathInputElem.value = json.data;
                    }
                } else {
                    console.error("[Settings] No data in response:", json);
                    showToast("No folder selected", 'info');
                }
            } catch (error) {
                console.error("[Settings] Failed to browse folder:", error);
                showToast("Failed to browse folder: " + error.message, 'error');
            }
        };
    }

    // Attach event listeners for the new card header toggles
    const toggleLibraryManagementBtn = document.getElementById("toggleLibraryManagementBtn");
    if (toggleLibraryManagementBtn) {
        toggleLibraryManagementBtn.onclick = (event) => window.toggleCardContent(event.currentTarget, "libraryManagementContent");
    }

    const toggleDataManagementBtn = document.getElementById("toggleDataManagementBtn");
    if (toggleDataManagementBtn) {
        toggleDataManagementBtn.onclick = (event) => window.toggleCardContent(event.currentTarget, "dataManagementContent");
    }

    const toggleLogsBtn = document.getElementById("toggleLogsBtn");
    if (toggleLogsBtn) {
        toggleLogsBtn.onclick = (event) => window.toggleCardContent(event.currentTarget, "logsCardContent");
    }

    const toggleImportSettingsBtn = document.getElementById("toggleImportSettingsBtn");
    if (toggleImportSettingsBtn) {
        toggleImportSettingsBtn.onclick = (event) => window.toggleCardContent(event.currentTarget, "importSettingsContent");
    }

    const toggleImportInstallationBtn = document.getElementById("toggleImportInstallationBtn");
    if (toggleImportInstallationBtn) {
        toggleImportInstallationBtn.onclick = (event) => window.toggleCardContent(event.currentTarget, "importInstallationContent");
    }

    // Individual installation buttons and progress tracking
    const installPythonBtn = document.getElementById("installPythonBtn");
    const installFfmpegBtn = document.getElementById("installFfmpegBtn");
    const installSpotdlBtn = document.getElementById("installSpotdlBtn");
    const installWhisperBtn = document.getElementById("installWhisperBtn");
    
    const pythonInstallProgress = document.getElementById("pythonInstallProgress");
    const ffmpegInstallProgress = document.getElementById("ffmpegInstallProgress");
    const spotdlInstallProgress = document.getElementById("spotdlInstallProgress");
    const whisperInstallProgress = document.getElementById("whisperInstallProgress");
    
    const pythonProgressContainer = document.getElementById("pythonProgressContainer");
    const ffmpegProgressContainer = document.getElementById("ffmpegProgressContainer");
    const spotdlProgressContainer = document.getElementById("spotdlProgressContainer");
    const whisperProgressContainer = document.getElementById("whisperProgressContainer");
    
    const pythonStatus = document.getElementById("pythonStatus");
    const ffmpegStatus = document.getElementById("ffmpegStatus");
    const spotdlStatus = document.getElementById("spotdlStatus");
    const whisperStatus = document.getElementById("whisperStatus");
    
    const pythonInstalledText = document.getElementById("pythonInstalledText");
    const ffmpegInstalledText = document.getElementById("ffmpegInstalledText");
    const spotdlInstalledText = document.getElementById("spotdlInstalledText");
    const whisperInstalledText = document.getElementById("whisperInstalledText");
    
    // Installation WebSocket for progress updates
    let installationWebSocket = null;
    
    // Component state tracking
    const componentStates = {
        python: false,
        ffmpeg: false,
        spotdl: false,
        whisper: false
    };
    
    // Setup individual installation button handlers
    if (installPythonBtn) {
        installPythonBtn.onclick = () => handleComponentAction('python', installPythonBtn, pythonInstallProgress, pythonProgressContainer);
    }
    
    if (installFfmpegBtn) {
        installFfmpegBtn.onclick = () => handleComponentAction('ffmpeg', installFfmpegBtn, ffmpegInstallProgress, ffmpegProgressContainer);
    }
    
    if (installSpotdlBtn) {
        installSpotdlBtn.onclick = () => handleComponentAction('spotdl', installSpotdlBtn, spotdlInstallProgress, spotdlProgressContainer);
    }
    
    if (installWhisperBtn) {
        installWhisperBtn.onclick = () => handleComponentAction('whisper', installWhisperBtn, whisperInstallProgress, whisperProgressContainer);
    }
    
    // Handle component install/uninstall action
    async function handleComponentAction(component, button, progressBar, progressContainer) {
        const isInstalled = componentStates[component];
        const action = isInstalled ? 'uninstall' : 'install';
        
        try {
            // Disable button and show loading state
            button.disabled = true;
            button.classList.add('is-loading');
            
            // Show progress bar
            progressContainer.style.display = 'block';
            progressBar.value = 0;
            
            // Call backend API
            const response = await fetch(`/api/import/${action}/${component}/${globalActiveProfileId}`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                }
            });
            
            if (response.ok) {
                showToast(`${component.charAt(0).toUpperCase() + component.slice(1)} ${action}ation started`, 'info');
                
                // Setup WebSocket for progress updates if not already connected
                if (!installationWebSocket) {
                    setupInstallationWebSocket();
                }
            } else {
                throw new Error(`Failed to start ${component} ${action}ation: ${response.status}`);
            }
            
        } catch (error) {
            console.error(`Error starting ${component} ${action}ation:`, error);
            showToast(`Failed to start ${component} ${action}ation: ${error.message}`, 'error');
            
            // Re-enable button and hide progress bar on error
            button.disabled = false;
            button.classList.remove('is-loading');
            progressContainer.style.display = 'none';
        }
    }
    
    // Setup WebSocket for real-time installation updates
    function setupInstallationWebSocket() {
        installationWebSocket = new WebSocket(`ws://${window.location.host}/ws/import-status/${globalActiveProfileId}`);
        
        installationWebSocket.onmessage = function (event) {
            const message = event.data;
            
            // Handle individual installation completion
            if (message.includes('[PYTHON_INSTALLATION_FINISHED]')) {
                handleActionCompletion('python', installPythonBtn, pythonInstallProgress, pythonProgressContainer, pythonStatus, true);
            } else if (message.includes('[FFMPEG_INSTALLATION_FINISHED]')) {
                handleActionCompletion('ffmpeg', installFfmpegBtn, ffmpegInstallProgress, ffmpegProgressContainer, ffmpegStatus, true);
            } else if (message.includes('[SPOTDL_INSTALLATION_FINISHED]')) {
                handleActionCompletion('spotdl', installSpotdlBtn, spotdlInstallProgress, spotdlProgressContainer, spotdlStatus, true);
            } else if (message.includes('[WHISPER_INSTALLATION_FINISHED]')) {
                handleActionCompletion('whisper', installWhisperBtn, whisperInstallProgress, whisperProgressContainer, whisperStatus, true);
            }
            
            // Handle individual uninstallation completion
            if (message.includes('[PYTHON_UNINSTALLATION_FINISHED]')) {
                handleActionCompletion('python', installPythonBtn, pythonInstallProgress, pythonProgressContainer, pythonStatus, false);
            } else if (message.includes('[FFMPEG_UNINSTALLATION_FINISHED]')) {
                handleActionCompletion('ffmpeg', installFfmpegBtn, ffmpegInstallProgress, ffmpegProgressContainer, ffmpegStatus, false);
            } else if (message.includes('[SPOTDL_UNINSTALLATION_FINISHED]')) {
                handleActionCompletion('spotdl', installSpotdlBtn, spotdlInstallProgress, spotdlProgressContainer, spotdlStatus, false);
            } else if (message.includes('[WHISPER_UNINSTALLATION_FINISHED]')) {
                handleActionCompletion('whisper', installWhisperBtn, whisperInstallProgress, whisperProgressContainer, whisperStatus, false);
            }
            
            // Handle installation/uninstallation progress messages
            try {
                const progressData = JSON.parse(message);
                if (progressData.type === 'installation-progress') {
                    updateActionProgress(progressData.component, progressData.progress, progressData.installing);
                }
            } catch (e) {
                // Not a JSON progress message, ignore
            }
        };
        
        installationWebSocket.onopen = function () {
            console.log("Installation WebSocket connected.");
        };
        
        installationWebSocket.onclose = function () {
            console.log("Installation WebSocket disconnected.");
            installationWebSocket = null;
        };
        
        installationWebSocket.onerror = function (error) {
            console.error("Installation WebSocket error:", error);
            showToast("WebSocket connection failed for installation updates", 'error');
        };
    }
    
    // Handle installation/uninstallation completion
    function handleActionCompletion(component, button, progressBar, progressContainer, statusElement, isInstall) {
        progressBar.value = 100;
        button.disabled = false;
        button.classList.remove('is-loading');
        
        // Update component state
        componentStates[component] = isInstall;
        
        if (isInstall) {
            // Installation completed
            button.classList.remove('is-success');
            button.classList.add('is-danger');
            button.innerHTML = `<i class="pi pi-trash mr-1"></i>Remove`;
            
            if (statusElement) {
                statusElement.textContent = 'Installed';
                statusElement.classList.remove('is-info');
                statusElement.classList.add('is-success');
            }
            
            showToast(`${component.charAt(0).toUpperCase() + component.slice(1)} installed successfully!`, 'success');
        } else {
            // Uninstallation completed
            button.classList.remove('is-danger');
            button.classList.add('is-success');
            button.innerHTML = `<i class="pi pi-download mr-1"></i>Install ${component.charAt(0).toUpperCase() + component.slice(1)}`;
            
            if (statusElement) {
                statusElement.textContent = 'Not installed';
                statusElement.classList.remove('is-success');
                statusElement.classList.remove('is-info');
            }
            
            showToast(`${component.charAt(0).toUpperCase() + component.slice(1)} removed successfully!`, 'info');
        }
        
        // Hide progress bar after completion
        setTimeout(() => {
            progressContainer.style.display = 'none';
        }, 1000);
        
        // Check if all components are installed
        checkAllComponentsInstalled();
    }
    
    // Update installation/uninstallation progress
    function updateActionProgress(component, progress, isInstalling) {
        const progressBar = document.getElementById(`${component}InstallProgress`);
        const statusElement = document.getElementById(`${component}Status`);
        
        if (progressBar) {
            progressBar.value = progress;
        }
        
        if (statusElement) {
            const action = isInstalling ? (componentStates[component] ? 'Uninstalling' : 'Installing') : '';
            if (action) {
                statusElement.textContent = `${action}... ${progress}%`;
                statusElement.classList.add('is-info');
                statusElement.classList.remove('is-success');
            }
        }
    }
    
    // Check if all components are installed and refresh UI
    async function checkAllComponentsInstalled() {
        try {
            const response = await fetch(`/api/import/status`);
            const data = await response.json();
            
            if (response.ok && data.data && data.data.isAllInstalled) {
                showToast("All components installed successfully! Import functionality is now available.", 'success');
                setTimeout(() => {
                    location.reload();
                }, 2000);
            }
        } catch (error) {
            console.error("Error checking installation status:", error);
        }
    }
    
    // Load initial installation status
    async function loadInstallationStatus() {
        try {
            const response = await fetch(`/api/import/status`);
            const data = await response.json();
            
            if (response.ok && data.data) {
                const status = data.data;
                
                // Update Python status
                updateComponentStatus('python', status.pythonInstalled, installPythonBtn, pythonStatus);
                
                // Update FFmpeg status
                updateComponentStatus('ffmpeg', status.ffmpegInstalled, installFfmpegBtn, ffmpegStatus);
                
                // Update SpotDL status
                updateComponentStatus('spotdl', status.spotdlInstalled, installSpotdlBtn, spotdlStatus);
                
                // Update Whisper status
                updateComponentStatus('whisper', status.whisperInstalled, installWhisperBtn, whisperStatus);
                
                // Update missing components list and dialog visibility
                updateMissingComponentsDialog(status);
            }
        } catch (error) {
            console.error("Error loading installation status:", error);
        }
    }
    
    // Update missing components dialog
    function updateMissingComponentsDialog(status) {
        if (!installationRequiredDialog || !missingComponentsList) return;
        
        const missingComponents = [];
        
        if (!status.pythonInstalled) {
            missingComponents.push('<li><strong>Python ≤3.13.9</strong> (Required for audio processing)</li>');
        }
        if (!status.ffmpegInstalled) {
            missingComponents.push('<li><strong>FFmpeg</strong> (For audio processing)</li>');
        }
        if (!status.spotdlInstalled) {
            missingComponents.push('<li><strong>SpotDL</strong> (For music downloading)</li>');
        }
        if (!status.whisperInstalled) {
            missingComponents.push('<li><strong>Whisper</strong> (For audio transcription)</li>');
        }
        
        // Update the missing components list
        missingComponentsList.innerHTML = missingComponents.join('');
        
        // Show/hide the notification based on whether components are missing
        if (missingComponents.length === 0) {
            // All components installed - hide the notification
            installationRequiredDialog.style.display = 'none';
        } else {
            // Some components missing - show the notification
            installationRequiredDialog.style.display = 'block';
            
            // Update the message to reflect how many are missing
            const messageElement = installationRequiredDialog.querySelector('strong');
            if (messageElement) {
                if (missingComponents.length === 1) {
                    messageElement.textContent = 'Import feature requires 1 additional installation.';
                } else {
                    messageElement.textContent = `Import features require ${missingComponents.length} additional installations.`;
                }
            }
        }
    }
    
    // Update individual component status
    function updateComponentStatus(component, isInstalled, button, statusElement) {
        componentStates[component] = isInstalled;
        
        if (isInstalled) {
            button.disabled = false;
            button.classList.remove('is-success');
            button.classList.add('is-danger');
            button.innerHTML = `<i class="pi pi-trash mr-1"></i>Remove`;
            
            if (statusElement) {
                statusElement.textContent = 'Installed';
                statusElement.classList.add('is-success');
            }
        } else {
            button.disabled = false;
            button.classList.remove('is-danger');
            button.classList.add('is-success');
            button.innerHTML = `<i class="pi pi-download mr-1"></i>Install ${component.charAt(0).toUpperCase() + component.slice(1)}`;
            
            if (statusElement) {
                statusElement.textContent = 'Not installed';
                statusElement.classList.remove('is-success');
            }
        }
    }
    
    // Load installation status on page load
    loadInstallationStatus();
    
    function sleep(ms) {
        return new Promise(resolve => setTimeout(resolve, ms));
    }

    // Video library toggle functionality
    const enableVideoLibraryToggle = document.getElementById("enableVideoLibraryToggle");
    const videoLibraryOptions = document.getElementById("videoLibraryOptions");
    
    if (enableVideoLibraryToggle && videoLibraryOptions) {
        // Load saved video library state
        const savedVideoState = localStorage.getItem('videoLibraryEnabled');
        if (savedVideoState === 'true') {
            enableVideoLibraryToggle.checked = true;
            videoLibraryOptions.classList.remove('is-hidden');
        }

        enableVideoLibraryToggle.addEventListener('change', function() {
            if (this.checked) {
                videoLibraryOptions.classList.remove('is-hidden');
                localStorage.setItem('videoLibraryEnabled', 'true');
                showToast("Video library enabled", 'success');
            } else {
                videoLibraryOptions.classList.add('is-hidden');
                localStorage.setItem('videoLibraryEnabled', 'false');
                showToast("Video library disabled", 'info');
            }
        });
    }

    window.refreshSettingsUI?.();
    setTimeout(() => window.setupLogWebSocket?.(), 0);

    const runAsServiceToggle = document.getElementById("runAsServiceToggle");
    const runAsServiceModal = document.getElementById("runAsServiceModal");
    const modalCloseButtons = runAsServiceModal ? runAsServiceModal.querySelectorAll('.delete, .button.is-success') : [];

    if (runAsServiceToggle) {
        runAsServiceToggle.addEventListener('change', async () => {
            try {
                const res = await fetch(`/api/settings/${globalActiveProfileId}/toggle-run-as-service`, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json'
                    }
                });
                
                if (res.ok) {
                    if (runAsServiceToggle.checked) {
                        runAsServiceModal?.classList.add('is-active');
                        showToast("Run as service enabled", 'success');
                    } else {
                        showToast("Run as service disabled", 'info');
                    }
                } else {
                    showToast("Failed to toggle run as service", 'error');
                    // Revert the toggle state if request failed
                    runAsServiceToggle.checked = !runAsServiceToggle.checked;
                }
            } catch (error) {
                console.error("[Settings] Failed to toggle run as service:", error);
                showToast("Failed to toggle run as service", 'error');
                // Revert the toggle state if request failed
                runAsServiceToggle.checked = !runAsServiceToggle.checked;
            }
        });
    }

    modalCloseButtons.forEach(button => {
        button.addEventListener('click', () => {
            runAsServiceModal?.classList.remove('is-active');
        });
    });

    // Navbar burger functionality
    const burger = document.querySelector('.navbar-burger');
    const menu = document.querySelector('.navbar-menu');
    burger.addEventListener('click', () => {
        burger.classList.toggle('is-active');
        menu.classList.toggle('is-active');
    });
});