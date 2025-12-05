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

    // Install requirements button
    const installRequirementsBtn = document.getElementById("installRequirementsBtn");
    const installDialog = document.getElementById("installDialog");
    const closeInstallDialogBtn = document.getElementById("closeInstallDialog");
    const cancelInstallBtn = document.getElementById("cancelInstallBtn");
    const installOutput = document.getElementById("installOutput");
    const installProgress = document.getElementById("installProgress");
    
    if (installRequirementsBtn) {
        installRequirementsBtn.onclick = () => {
            // Show dialog
            installDialog.classList.add('is-active');
            installOutput.value = "Starting installation process...\r\n";
            installProgress.value = 0;
            
            // Start installation process
            startInstallation();
        };
    }
    
    // Close dialog handlers
    if (closeInstallDialogBtn) {
        closeInstallDialogBtn.onclick = () => {
            installDialog.classList.remove('is-active');
        };
    }
    
    if (cancelInstallBtn) {
        cancelInstallBtn.onclick = () => {
            installDialog.classList.remove('is-active');
        };
    }
    
    // Installation process
    async function startInstallation() {
        try {
            installOutput.value += "Starting installation process...\r\n";
            installProgress.value = 5;
            
            // Call backend installation API
            const response = await fetch(`/api/settings/${globalActiveProfileId}/install-requirements`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                }
            });
            
            if (response.ok) {
                installOutput.value += "Installation process started on server...\r\n";
                installProgress.value = 20;
                
                // Start WebSocket for real-time updates
                setupInstallationWebSocket();
            } else {
                throw new Error(`Failed to start installation: ${response.status}`);
            }
            
        } catch (error) {
            installOutput.value += `ERROR: ${error.message}\r\n`;
            installProgress.value = 0;
        }
    }
    
    // Setup WebSocket for real-time installation updates
    function setupInstallationWebSocket() {
        const socket = new WebSocket(`ws://${window.location.host}/ws/import-status/${globalActiveProfileId}`);
        
        socket.onmessage = function (event) {
            const message = event.data;
            
            // Handle installation completion
            if (message === '[INSTALLATION_FINISHED]') {
                installProgress.value = 100;
                installOutput.value += "\r\nInstallation completed successfully!\r\n";
                
                setTimeout(() => {
                    installDialog.classList.remove('is-active');
                    showToast("Installation completed successfully!", 'success');
                    // Refresh the page to update UI state
                    location.reload();
                }, 2000);
                socket.close();
                return;
            }
            
            // Handle installation progress/output
            if (message && message.trim()) {
                installOutput.value += message;
                installOutput.scrollTop = installOutput.scrollHeight;
                
                // Update progress based on installation stage
                if (message.includes('Installing Python')) {
                    installProgress.value = 30;
                } else if (message.includes('Installing FFmpeg')) {
                    installProgress.value = 60;
                } else if (message.includes('Installing SpotDL')) {
                    installProgress.value = 80;
                } else if (message.includes('Installing Whisper')) {
                    installProgress.value = 90;
                }
            }
        };
        
        socket.onopen = function () {
            console.log("Installation WebSocket connected.");
        };
        
        socket.onclose = function () {
            console.log("Installation WebSocket disconnected.");
        };
        
        socket.onerror = function (error) {
            console.error("Installation WebSocket error:", error);
            installOutput.value += `ERROR: WebSocket connection failed\r\n`;
        };
    }
    
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