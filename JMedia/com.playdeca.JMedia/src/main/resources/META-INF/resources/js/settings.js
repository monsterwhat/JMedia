window.resetLibrary = async function () {
    if (!window.globalActiveProfileId)
        return;
    const res = await fetch(`/api/settings/${window.globalActiveProfileId}/resetLibrary`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] Library reset to:", json.data.libraryPath);
        Toast.success("Library reset to default path");
        const pathInputElem = document.getElementById("musicLibraryPathInput");
        if (pathInputElem)
            pathInputElem.value = json.data.libraryPath;
    } else {
        console.error("[Settings] Failed to reset library:", json.error);
        Toast.error("Failed to reset library: " + (json.error || "Unknown error"));
    }
};

window.scanLibrary = async function () {
    if (!window.globalActiveProfileId)
        return;
    const res = await fetch(`/api/settings/${window.globalActiveProfileId}/scanLibrary`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] Scan started");
        Toast.success("Library scan started");
    } else {
        console.error("[Settings] Failed to scan library:", json.error);
        Toast.error("Failed to scan library: " + (json.error || "Unknown error"));
    }
};

window.clearLogs = async function () {
    const res = await fetch(`/api/settings/${window.globalActiveProfileId}/clearLogs`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] Logs cleared");
        Toast.success("Logs cleared successfully");
        const logsPanel = document.getElementById("logsPanel");
        if (logsPanel) {
            logsPanel.innerHTML = "";
        }
    } else {
        console.error("[Settings] Failed to clear logs:", json.error);
        Toast.error("Failed to clear logs: " + (json.error || "Unknown error"));
    }
};

window.clearSongsDB = async function () {
    const res = await fetch(`/api/settings/${window.globalActiveProfileId}/clearSongs`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] All songs deleted");
        Toast.success("All songs cleared from database");
    } else {
        console.error("[Settings] Failed to clear songs DB:", json.error);
        Toast.error("Failed to clear songs: " + (json.error || "Unknown error"));
    }
};

window.setupLogWebSocket = function () {
    const logsPanel = document.getElementById("logsPanel");
    if (!logsPanel)
        return;

    const socket = new WebSocket(`ws://${window.location.host}/api/logs/ws/${window.globalActiveProfileId}`);

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

    // Store the socket reference globally
    window.logWebSocket = socket;
}



window.reloadMetadata = async function () {
    const res = await fetch(`/api/settings/${window.globalActiveProfileId}/reloadMetadata`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] Metadata reload started");
        Toast.success("Metadata reload started");
    } else {
        console.error("[Settings] Failed to reload metadata:", json.error);
        Toast.error("Failed to reload metadata: " + (json.error || "Unknown error"));
    }
};

window.deleteDuplicates = async function () {
    const res = await fetch(`/api/settings/${window.globalActiveProfileId}/deleteDuplicates`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] Duplicate deletion started");
        Toast.success("Duplicate deletion started");
    } else {
        console.error("[Settings] Failed to delete duplicates:", json.error);
        Toast.error("Failed to delete duplicates: " + (json.error || "Unknown error"));
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

    const res = await fetch(`/api/settings/${window.globalActiveProfileId}/import`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(settings)
    });

    if (res.ok) {
        console.log('[Settings] Import settings saved.');
        Toast.success('Import settings saved successfully');
    } else {
        console.error('[Settings] Failed to save import settings.');
        Toast.error('Failed to save import settings');
    }
};

window.loadPlaybackSettings = async function () {
    if (!window.globalActiveProfileId) return;

    // Load crossfade from PlaybackState
    try {
        const crossfadeRes = await fetch(`/api/music/playback/crossfade/${window.globalActiveProfileId}`);
        const crossfadeJson = await crossfadeRes.json();
        if (crossfadeRes.ok && crossfadeJson.data !== undefined) {
            const crossfadeInput = document.getElementById('crossfadeDuration');
            const crossfadeValue = document.getElementById('crossfadeValue');
            if (crossfadeInput) {
                crossfadeInput.value = crossfadeJson.data;
                if (crossfadeValue) crossfadeValue.textContent = crossfadeJson.data;
            }
        }
    } catch (e) {
        console.error('Failed to load crossfade settings:', e);
    }

    // Load BPM tolerance from Settings
    try {
        const bpmRes = await fetch(`/api/settings/${window.globalActiveProfileId}/bpm-tolerance`);
        const bpmJson = await bpmRes.json();
        if (bpmRes.ok && bpmJson.data) {
            const bpmInput = document.getElementById('bpmTolerance');
            if (bpmInput && bpmJson.data.default) {
                bpmInput.value = bpmJson.data.default;
            }
            
            // Load BPM overrides
            const overridesContainer = document.getElementById('bpmOverridesContainer');
            if (overridesContainer && bpmJson.data.overrides) {
                try {
                    const overrides = JSON.parse(bpmJson.data.overrides);
                    overridesContainer.innerHTML = '';
                    for (const [genre, tolerance] of Object.entries(overrides)) {
                        addBpmOverrideRow(genre, tolerance);
                    }
                } catch (e) {
                    console.error('Failed to parse BPM overrides:', e);
                }
            }
        }
    } catch (e) {
        console.error('Failed to load BPM tolerance settings:', e);
    }
};

window.savePlaybackSettings = async function () {
    if (!window.globalActiveProfileId) return;

    // Save crossfade
    const crossfadeInput = document.getElementById('crossfadeDuration');
    const crossfadeValue = crossfadeInput ? parseInt(crossfadeInput.value) : 0;
    
    try {
        await fetch(`/api/music/playback/crossfade/${window.globalActiveProfileId}/${crossfadeValue}`, {
            method: 'POST'
        });
    } catch (e) {
        console.error('Failed to save crossfade:', e);
    }

    // Save BPM tolerance
    const bpmInput = document.getElementById('bpmTolerance');
    const bpmValue = bpmInput ? parseInt(bpmInput.value) : 10;

    // Collect overrides
    const overrides = {};
    const overrideRows = document.querySelectorAll('.bpm-override-row');
    overrideRows.forEach(row => {
        const genreInput = row.querySelector('.bpm-override-genre');
        const toleranceInput = row.querySelector('.bpm-override-tolerance');
        if (genreInput && toleranceInput && genreInput.value.trim()) {
            overrides[genreInput.value.trim().toLowerCase()] = parseInt(toleranceInput.value) || 10;
        }
    });

    try {
        await fetch(`/api/settings/${window.globalActiveProfileId}/bpm-tolerance`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                default: bpmValue,
                overrides: JSON.stringify(overrides)
            })
        });
        Toast.success('Playback settings saved successfully');
    } catch (e) {
        console.error('Failed to save BPM tolerance:', e);
        Toast.error('Failed to save playback settings');
    }
};

window.addBpmOverrideRow = function (genre = '', tolerance = '') {
    const container = document.getElementById('bpmOverridesContainer');
    if (!container) return;

    const row = document.createElement('div');
    row.className = 'bpm-override-row is-flex is-align-items-center mb-2';
    row.innerHTML = `
        <input type="text" class="input bpm-override-genre is-small mr-2" placeholder="Genre (e.g., electronic)" value="${genre}" style="max-width: 180px;">
        <span class="mr-2">±</span>
        <input type="number" class="input bpm-override-tolerance is-small mr-2" placeholder="BPM" value="${tolerance}" min="1" max="50" style="max-width: 80px;">
        <button type="button" class="button is-danger is-small" onclick="this.closest('.bpm-override-row').remove()">
            <span class="icon"><i class="pi pi-times"></i></span>
        </button>
    `;
    container.appendChild(row);
};

window.loadProfiles = async function () {
    const profileList = document.getElementById('profileList');
    if (!profileList) return;

    try {
        const res = await fetch('/api/profiles');
        const profiles = await res.json();
        
        if (!res.ok || !profiles.length) {
            profileList.innerHTML = '<p class="has-text-grey">No profiles found</p>';
            return;
        }

        const currentProfileRes = await fetch('/api/profiles/current');
        const currentProfile = await currentProfileRes.json();

        profileList.innerHTML = profiles.map(profile => {
            const isMain = profile.isMainProfile;
            const isCurrent = currentProfile.id === profile.id;
            return `
                <div class="card mb-2">
                    <div class="card-content p-3">
                        <div class="is-flex is-justify-content-space-between is-align-items-center">
                            <div>
                                <p class="has-text-weight-semibold">${profile.name} ${isMain ? '<span class="tag is-warning is-small ml-2">Main</span>' : ''} ${isCurrent ? '<span class="tag is-info is-small ml-2">Current</span>' : ''}</p>
                            </div>
                            <div class="buttons are-small">
                                ${!isMain ? `
                                    <button class="button is-danger is-light is-small" onclick="deleteProfile(${profile.id})">
                                        <span class="icon"><i class="pi pi-trash"></i></span>
                                    </button>
                                ` : ''}
                            </div>
                        </div>
                    </div>
                </div>
            `;
        }).join('');
    } catch (e) {
        console.error('Error loading profiles:', e);
        profileList.innerHTML = '<p class="has-text-danger">Error loading profiles</p>';
    }
};

window.createProfile = async function () {
    const nameInput = document.getElementById('newProfileName');
    const name = nameInput?.value?.trim();
    
    if (!name) {
        Toast.error('Please enter a profile name');
        return;
    }

    try {
        const res = await fetch('/api/profiles', {
            method: 'POST',
            headers: { 'Content-Type': 'text/plain' },
            body: name
        });

        if (res.ok) {
            Toast.success('Profile created successfully');
            if (nameInput) nameInput.value = '';
            window.loadProfiles();
        } else {
            const json = await res.json();
            Toast.error(json.error || 'Failed to create profile');
        }
    } catch (e) {
        console.error('Error creating profile:', e);
        Toast.error('Error creating profile');
    }
};

window.deleteProfile = async function (profileId) {
    if (!confirm('Are you sure you want to delete this profile? All associated data will be moved to the main profile.')) {
        return;
    }

    try {
        const res = await fetch(`/api/profiles/${profileId}`, {
            method: 'DELETE'
        });

        if (res.ok) {
            Toast.success('Profile deleted successfully');
            window.loadProfiles();
        } else {
            const json = await res.json();
            Toast.error(json.error || 'Failed to delete profile');
        }
    } catch (e) {
        console.error('Error deleting profile:', e);
        Toast.error('Error deleting profile');
    }
};


window.refreshSettingsUI = async function () {

    // Check if globalActiveProfileId is available
    if (!window.globalActiveProfileId) {
        console.warn("[Settings] globalActiveProfileId not available, skipping settings refresh");
        return;
    }

    const res = await fetch(`/api/settings/${window.globalActiveProfileId}`);
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

// Race condition mitigation: localStorage operation queue
let localStorageQueue = [];
let isProcessingLocalStorage = false;

function processLocalStorageQueue() {
    if (isProcessingLocalStorage || localStorageQueue.length === 0) {
        return;
    }
    
    isProcessingLocalStorage = true;
    
    const processNext = () => {
        if (localStorageQueue.length === 0) {
            isProcessingLocalStorage = false;
            return;
        }
        
        const operation = localStorageQueue.shift();
        try {
            operation();
        } catch (error) {
            console.error('[Settings] localStorage operation failed:', error);
        }
        
        // Process next operation with minimal delay
        setTimeout(processNext, 0);
    };
    
    processNext();
}

// Race condition mitigation: safe localStorage set function
function safeLocalStorageSet(key, value) {
    localStorageQueue.push(() => {
        localStorage.setItem(key, value);
    });
    processLocalStorageQueue();
}

// Generic function to toggle card content visibility with race condition mitigation
window.toggleCardContent = function (button, contentId) {
    const content = document.getElementById(contentId);
    const icon = button.querySelector('i');

    if (content && icon) {
        const isHidden = content.classList.toggle('is-hidden');
        icon.classList.toggle('pi-angle-down');
        icon.classList.toggle('pi-angle-up');
        // Race condition mitigation: use safe localStorage operation
        safeLocalStorageSet(`cardState-${contentId}`, isHidden);
    }
};

// Generic confirmation dialog
function showConfirmationDialog(message, callback) {
    if (confirm(message)) {
        callback();
    }
}

// Helper functions - defined outside DOMContentLoaded to be accessible
function updateMissingComponentsDialog(status) {
    if (!window.installationRequiredDialog || !window.missingComponentsList)
        return;
    const missingComponents = [];
    if (!status.chocoInstalled) {
        missingComponents.push('<li><strong>Chocolatey</strong> (Package manager for Windows)</li>');
    }
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

    // Update missing components list
    window.missingComponentsList.innerHTML = missingComponents.join('');
    // Show/hide notification based on whether components are missing
    if (missingComponents.length === 0) {
        // All components installed - hide notification
        window.installationRequiredDialog.style.display = 'none';
    } else {
        // Some components missing - show notification
        window.installationRequiredDialog.style.display = 'block';
        // Update message to reflect how many are missing
        const messageElement = window.installationRequiredDialog.querySelector('strong');
        if (messageElement) {
            if (missingComponents.length === 1) {
                messageElement.textContent = 'Import feature requires 1 additional installation.';
            } else {
                messageElement.textContent = `Import features require ${missingComponents.length} additional installations.`;
            }
        }
    }
}

function updateComponentStatus(component, isInstalled, button, statusElement) {
    // Update component state
    window.componentStates[component] = isInstalled;
    if (button) {
        button.disabled = false;
        if (isInstalled) {
            button.classList.remove('is-success');
            button.classList.add('is-danger');
            button.innerHTML = `<i class="pi pi-trash mr-1"></i>Remove`;
        } else {
            button.classList.remove('is-danger');
            button.classList.add('is-success');
            button.innerHTML = `<i class="pi pi-download mr-1"></i>Install ${component.charAt(0).toUpperCase() + component.slice(1)}`;
        }
    } else {
        console.warn(`[Settings] Button element not found for component: ${component}`);
    }

    if (statusElement) {
        if (isInstalled) {
            statusElement.textContent = 'Installed';
            statusElement.classList.remove('is-info', 'is-warning', 'is-danger');
            statusElement.classList.add('is-success');
        } else {
            statusElement.textContent = 'Not installed';
            statusElement.classList.remove('is-info', 'is-success', 'is-danger');
            statusElement.classList.add('is-warning');
        }
    } else {
        console.warn(`[Settings] Status element not found for component: ${component}`);
    }
}

function updateInstallationStatusElements(status) {
    // Update individual status elements
    const chocoStatusEl = document.getElementById('chocoStatus');
    const pythonStatusEl = document.getElementById('pythonStatus');
    const ffmpegStatusEl = document.getElementById('ffmpegStatus');
    const spotdlStatusEl = document.getElementById('spotdlStatus');
    const whisperStatusEl = document.getElementById('whisperStatus');
    
    // If any elements are missing, don't proceed
    if (!chocoStatusEl || !pythonStatusEl || !ffmpegStatusEl || !spotdlStatusEl || !whisperStatusEl) {
        return;
    }

    // Get button elements
    const installChocoBtn = document.getElementById("installChocoBtn");
    const installPythonBtn = document.getElementById("installPythonBtn");
    const installFfmpegBtn = document.getElementById("installFfmpegBtn");
    const installSpotdlBtn = document.getElementById("installSpotdlBtn");
    const installWhisperBtn = document.getElementById("installWhisperBtn");

    if (chocoStatusEl) {
        const isInstalled = Boolean(status.chocoInstalled);
        const newText = isInstalled ? '✅ Installed' : '❌ Not installed';
        const newClass = isInstalled ? 'help is-size-7 mt-2 has-text-success' : 'help is-size-7 mt-2 has-text-danger';
        chocoStatusEl.textContent = newText;
        chocoStatusEl.className = newClass;
    }

    if (pythonStatusEl) {
        const isInstalled = Boolean(status.pythonInstalled);
        const newText = isInstalled ? '✅ Installed' : '❌ Not installed';
        const newClass = isInstalled ? 'help is-size-7 mt-2 has-text-success' : 'help is-size-7 mt-2 has-text-danger';
        pythonStatusEl.textContent = newText;
        pythonStatusEl.className = newClass;
    }

    if (ffmpegStatusEl) {
        const isInstalled = Boolean(status.ffmpegInstalled);
        const newText = isInstalled ? '✅ Installed' : '❌ Not installed';
        const newClass = isInstalled ? 'help is-size-7 mt-2 has-text-success' : 'help is-size-7 mt-2 has-text-danger';
        ffmpegStatusEl.textContent = newText;
        ffmpegStatusEl.className = newClass;
    }

    if (spotdlStatusEl) {
        const isInstalled = Boolean(status.spotdlInstalled);
        const newText = isInstalled ? '✅ Installed' : '❌ Not installed';
        const newClass = isInstalled ? 'help is-size-7 mt-2 has-text-success' : 'help is-size-7 mt-2 has-text-danger';
        spotdlStatusEl.textContent = newText;
        spotdlStatusEl.className = newClass;
    }

    if (whisperStatusEl) {
        const isInstalled = Boolean(status.whisperInstalled);
        const newText = isInstalled ? '✅ Installed' : '❌ Not installed';
        const newClass = isInstalled ? 'help is-size-7 mt-2 has-text-success' : 'help is-size-7 mt-2 has-text-danger';
        whisperStatusEl.textContent = newText;
        whisperStatusEl.className = newClass;
    }

    // Update button states using existing function
    updateComponentStatus('choco', status.chocoInstalled, installChocoBtn, chocoStatusEl);
    updateComponentStatus('python', status.pythonInstalled, installPythonBtn, pythonStatusEl);
    updateComponentStatus('ffmpeg', status.ffmpegInstalled, installFfmpegBtn, ffmpegStatusEl);
    updateComponentStatus('spotdl', status.spotdlInstalled, installSpotdlBtn, spotdlStatusEl);
    updateComponentStatus('whisper', status.whisperInstalled, installWhisperBtn, whisperStatusEl);
    // Update missing components list and dialog visibility
    updateMissingComponentsDialog(status);
}

document.addEventListener("DOMContentLoaded", async () => {
    // Check if user is admin and hide/show admin-only sections
    let isAdmin = false;
    try {
        const adminRes = await fetch('/api/auth/is-admin');
        const adminJson = await adminRes.json();
        
        if (adminJson.data && adminJson.data.isAdmin) {
            isAdmin = true;
            console.log('User is admin, showing all sections');
        } else {
            console.log('User is NOT admin, hiding admin sections');
        }
        
        // Hide admin-only sections for non-admins
        if (!isAdmin) {
            // Hide profile management tab
            const profileTab = document.querySelector('li[data-tab="profile-management"]');
            if (profileTab) {
                profileTab.style.display = 'none';
            }
            
            // Hide admin-only elements in settings
            document.querySelectorAll('.admin-only').forEach(el => {
                el.style.display = 'none';
            });
        }
    } catch (e) {
        console.error('Error checking admin status:', e);
        // Hide admin-only sections on error
        const profileTab = document.querySelector('li[data-tab="profile-management"]');
        if (profileTab) {
            profileTab.style.display = 'none';
        }
        document.querySelectorAll('.admin-only').forEach(el => {
            el.style.display = 'none';
        });
    }

    // Get DOM elements that are referenced throughout the code
    window.installationRequiredDialog = document.getElementById("installationRequiredDialog");
    window.missingComponentsList = document.getElementById("missingComponentsList");
    window.componentStates = {
        python: false,
        ffmpeg: false,
        spotdl: false,
        whisper: false
    };

    // Load saved card states
    ['libraryManagementContent', 'logsCardContent', 'importInstallationContent'].forEach(contentId => {
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
                const res = await fetch(`/api/settings/clearPlaybackHistory/${window.globalActiveProfileId}`, {method: 'POST'});
                if (res.ok) {
                    Toast.success("Playback history cleared successfully");
                } else {
                    Toast.error("Failed to clear playback history");
                }
            } catch (error) {
                Toast.error("Error clearing playback history");
            }
        });
    document.getElementById("reloadMetadata").onclick = () => showConfirmationDialog("Are you sure you want to reload all song metadata? This might take a while.", window.reloadMetadata);
    document.getElementById("deleteDuplicates").onclick = () => showConfirmationDialog("Are you sure you want to delete duplicate songs? This action cannot be undone.", window.deleteDuplicates);
    document.getElementById("saveImportSettingsBtn").onclick = window.saveImportSettings;
    document.getElementById("savePlaybackSettingsBtn").onclick = window.savePlaybackSettings;
    
    // Profile management
    const createProfileBtn = document.getElementById('createProfileBtn');
    if (createProfileBtn) {
        createProfileBtn.onclick = window.createProfile;
    }
    const newProfileName = document.getElementById('newProfileName');
    if (newProfileName) {
        newProfileName.addEventListener('keypress', (e) => {
            if (e.key === 'Enter') {
                window.createProfile();
            }
        });
    }
    window.loadProfiles();
    
    // Load playback settings on page load
    window.loadPlaybackSettings();
    
    // Update button handlers
    const checkForUpdatesBtn = document.getElementById("checkForUpdatesBtn");
    if (checkForUpdatesBtn) {
        checkForUpdatesBtn.onclick = async () => {
            try {
                checkForUpdatesBtn.disabled = true;
                checkForUpdatesBtn.classList.add('is-loading');
                Toast.info("Checking for updates...");
                
                const response = await fetch('/api/update/check', {
                    method: 'GET',
                    headers: {
                        'Content-Type': 'application/json'
                    }
                });
                
                const result = await response.json();
                
                if (response.ok) {
                    if (result.data && result.data.updateAvailable) {
                        Toast.success(`Update available: ${result.data.latestVersion}`);
                        // Update status div with update info
                        const updateStatus = document.getElementById("updateStatus");
                        if (updateStatus) {
                            updateStatus.style.display = 'block';
                            updateStatus.innerHTML = `
                                <div class="notification is-success is-light">
                                    <p class="has-text-weight-semibold">Update Available!</p>
                                    <p class="is-size-7">Current: ${result.data.currentVersion} → Latest: ${result.data.latestVersion}</p>
                                    <p class="is-size-7">${result.data.releaseNotes || 'Check GitHub for release notes'}</p>
                                </div>
                            `;
                        }
                    } else {
                        Toast.success("You're using the latest version!");
                        const updateStatus = document.getElementById("updateStatus");
                        if (updateStatus) {
                            updateStatus.style.display = 'block';
                            updateStatus.innerHTML = `
                                <div class="notification is-info is-light">
                                    <p class="has-text-weight-semibold">Up to Date</p>
                                    <p class="is-size-7">You're using the latest version of JMedia</p>
                                </div>
                            `;
                        }
                    }
                } else {
                    throw new Error(result.error || 'Failed to check for updates');
                }
            } catch (error) {
                console.error('[Settings] Error checking for updates:', error);
                Toast.error("Failed to check for updates: " + error.message);
            } finally {
                checkForUpdatesBtn.disabled = false;
                checkForUpdatesBtn.classList.remove('is-loading');
            }
        };
    }
    
    const viewUpdateDialogBtn = document.getElementById("viewUpdateDialogBtn");
    if (viewUpdateDialogBtn) {
        viewUpdateDialogBtn.onclick = () => {
            // Show update info in the status div
            const updateStatus = document.getElementById("updateStatus");
            if (updateStatus) {
                if (updateStatus.style.display === 'none' || updateStatus.style.display === '') {
                    updateStatus.style.display = 'block';
                    updateStatus.innerHTML = `
                        <div class="notification is-info is-light">
                            <p class="has-text-weight-semibold mb-2">Update Information</p>
                            <p class="is-size-7">JMedia checks for updates automatically on startup and daily.</p>
                            <p class="is-size-7">You can also check manually using the "Check for Updates" button.</p>
                            <p class="is-size-7 mt-2">Updates are downloaded from GitHub releases.</p>
                            <p class="is-size-7">You'll need to manually download and install updates.</p>
                        </div>
                    `;
                    viewUpdateDialogBtn.innerHTML = `
                        <span class="icon-text">
                            <span class="icon"><i class="pi pi-times"></i></span>
                            <span>Hide Update Info</span>
                        </span>
                    `;
                } else {
                    updateStatus.style.display = 'none';
                    viewUpdateDialogBtn.innerHTML = `
                        <span class="icon-text">
                            <span class="icon"><i class="pi pi-external-link"></i></span>
                            <span>View Update Info</span>
                        </span>
                    `;
                }
            }
        };
    }
    // Save path buttons with toast notifications
    const saveMusicLibraryPathBtn = document.getElementById("saveMusicLibraryPathBtn");
    if (saveMusicLibraryPathBtn) {
        saveMusicLibraryPathBtn.addEventListener('htmx:afterRequest', function (evt) {
            if (evt.detail.successful) {
                Toast.success("Music library path saved successfully");
            } else {
                Toast.error("Failed to save music library path");
            }
        });
    }

    const browseMusicFolderBtn = document.getElementById("browseMusicFolderBtn");
    if (browseMusicFolderBtn) {
        browseMusicFolderBtn.onclick = async () => {
            try {
                const res = await fetch(`/api/settings/${window.globalActiveProfileId}/browse-folder`);
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
                    Toast.info("No folder selected");
                }
            } catch (error) {
                console.error("[Settings] Failed to browse folder:", error);
                Toast.error("Failed to browse folder: " + error.message);
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



    const toggleImportInstallationBtn = document.getElementById("toggleImportInstallationBtn");
    if (toggleImportInstallationBtn) {
        toggleImportInstallationBtn.onclick = (event) => window.toggleCardContent(event.currentTarget, "importInstallationContent");
    }

// Individual installation buttons and progress tracking
    const installChocoBtn = document.getElementById("installChocoBtn");
    const installPythonBtn = document.getElementById("installPythonBtn");
    const installFfmpegBtn = document.getElementById("installFfmpegBtn");
    const installSpotdlBtn = document.getElementById("installSpotdlBtn");
    const installWhisperBtn = document.getElementById("installWhisperBtn");
    const chocoInstallProgress = document.getElementById("chocoInstallProgress");
    const pythonInstallProgress = document.getElementById("pythonInstallProgress");
    const ffmpegInstallProgress = document.getElementById("ffmpegInstallProgress");
    const spotdlInstallProgress = document.getElementById("spotdlInstallProgress");
    const whisperInstallProgress = document.getElementById("whisperInstallProgress");
    const chocoProgressContainer = document.getElementById("chocoProgressContainer");
    const pythonProgressContainer = document.getElementById("pythonProgressContainer");
    const ffmpegProgressContainer = document.getElementById("ffmpegProgressContainer");
    const spotdlProgressContainer = document.getElementById("spotdlProgressContainer");
    const whisperProgressContainer = document.getElementById("whisperProgressContainer");
    const chocoStatus = document.getElementById("chocoStatus");
    const pythonStatus = document.getElementById("pythonStatus");
    const ffmpegStatus = document.getElementById("ffmpegStatus");
    const spotdlStatus = document.getElementById("spotdlStatus");
    const whisperStatus = document.getElementById("whisperStatus");
    const pythonInstalledText = document.getElementById("pythonInstalledText");
    const ffmpegInstalledText = document.getElementById("ffmpegInstalledText");
    const spotdlInstalledText = document.getElementById("spotdlInstalledText");
    const whisperInstalledText = document.getElementById("whisperInstalledText");
    // Installation WebSocket for progress updates is now global

    // Component state tracking - use global object to ensure consistency
    if (!window.componentStates) {
        window.componentStates = {
            choco: false,
            python: false,
            ffmpeg: false,
            spotdl: false,
            whisper: false
        };
    }
    // Setup individual installation button handlers
    if (installChocoBtn) {
        installChocoBtn.onclick = () => handleComponentAction('choco', installChocoBtn, chocoInstallProgress, chocoProgressContainer);
    }
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
        const isInstalled = window.componentStates[component];
        const action = isInstalled ? 'uninstall' : 'install';
        try {
            // Disable button and show loading state
            button.disabled = true;
            button.classList.add('is-loading');
            // Show progress bar
            progressContainer.style.display = 'block';
            progressBar.value = 0;
            // Call backend API
            const response = await fetch(`/api/import/${action}/${component}/${window.globalActiveProfileId}`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                }
            });
            if (response.ok) {
                Toast.info(`${component.charAt(0).toUpperCase() + component.slice(1)} ${action}ation started`);
                // Setup WebSocket for progress updates if not already connected
                if (!window.installationWebSocket) {
                    setupInstallationWebSocket();
                }
            } else {
                throw new Error(`Failed to start ${component} ${action}ation: ${response.status}`);
            }

        } catch (error) {
            console.error(`Error starting ${component} ${action}ation:`, error);
            Toast.error(`Failed to start ${component} ${action}ation: ${error.message}`);
            // Re-enable button and hide progress bar on error
            button.disabled = false;
            button.classList.remove('is-loading');
            progressContainer.style.display = 'none';
        }
    }

// Setup WebSocket for real-time installation updates
    function setupInstallationWebSocket() {
        const protocol = location.protocol === 'https:' ? 'wss://' : 'ws://';
        window.installationWebSocket = new WebSocket(protocol + location.host + `/ws/import-status/${window.globalActiveProfileId}`);
        window.installationWebSocket.onmessage = function (event) {
            const message = event.data;
            // Handle individual installation completion
            if (message.includes('[CHOCO_INSTALLATION_FINISHED]')) {
                handleActionCompletion('choco', installChocoBtn, chocoInstallProgress, chocoProgressContainer, chocoStatus, true);
            } else if (message.includes('[PYTHON_INSTALLATION_FINISHED]')) {
                handleActionCompletion('python', installPythonBtn, pythonInstallProgress, pythonProgressContainer, pythonStatus, true);
            } else if (message.includes('[FFMPEG_INSTALLATION_FINISHED]')) {
                handleActionCompletion('ffmpeg', installFfmpegBtn, ffmpegInstallProgress, ffmpegProgressContainer, ffmpegStatus, true);
            } else if (message.includes('[SPOTDL_INSTALLATION_FINISHED]')) {
                handleActionCompletion('spotdl', installSpotdlBtn, spotdlInstallProgress, spotdlProgressContainer, spotdlStatus, true);
            } else if (message.includes('[WHISPER_INSTALLATION_FINISHED]')) {
                handleActionCompletion('whisper', installWhisperBtn, whisperInstallProgress, whisperProgressContainer, whisperStatus, true);
            }

            // Handle individual uninstallation completion
            if (message.includes('[CHOCO_UNINSTALLATION_FINISHED]')) {
                handleActionCompletion('choco', installChocoBtn, chocoInstallProgress, chocoProgressContainer, chocoStatus, false);
            } else if (message.includes('[PYTHON_UNINSTALLATION_FINISHED]')) {
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
        installationWebSocket.onclose = function (event) {
            console.log("Installation WebSocket disconnected.", event.code, event.reason);
            installationWebSocket = null;
            // Don't reconnect if the connection was closed cleanly (code 1000) or if page is unloading
            if (event.code === 1000 || document.hidden) {
                console.log("Installation WebSocket closed cleanly, not reconnecting");
                return;
            }

            // Reconnect after 3 seconds for abnormal closures
            setTimeout(() => {
                if (!document.hidden && !installationWebSocket) {
                    console.log("Installation WebSocket attempting to reconnect...");
                    setupInstallationWebSocket();
                }
            }, 3000);
        };
        installationWebSocket.onerror = function (error) {
            console.error("Installation WebSocket error:", error);
            Toast.error("WebSocket connection failed for installation updates");
        };
    }

// Handle installation/uninstallation completion
    function handleActionCompletion(component, button, progressBar, progressContainer, statusElement, isInstall) {
        progressBar.value = 100;
        button.disabled = false;
        button.classList.remove('is-loading');
        // Update component state
        window.componentStates[component] = isInstall;
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

            Toast.success(`${component.charAt(0).toUpperCase() + component.slice(1)} installed successfully!`);
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

            Toast.info(`${component.charAt(0).toUpperCase() + component.slice(1)} removed successfully!`);
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
            const action = isInstalling ? (window.componentStates[component] ? 'Uninstalling' : 'Installing') : '';
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
            const response = await fetch(`/api/settings/${window.globalActiveProfileId}/install-status`);
            const result = await response.json();
            // Handle response structure
            let status = null;
            if (result.success && result.data) {
                status = result.data;
            } else if (result.data) {
                status = result.data;
            } else if (result.allInstalled !== undefined) {
                status = result;
            }

            if (response.ok && status && (status.allInstalled || status.isAllInstalled)) {
                Toast.success("All components installed successfully! Import functionality is now available.");
                setTimeout(() => {
                    location.reload();
                }, 2000);
            }
        } catch (error) {
            console.error("Error checking installation status:", error);
        }
    }

// Test if settings.js is loading properly
    // Load initial installation status with retry logic (same as setup.js)
    window.loadInstallationStatus = async function (retryCount = 0) {
        const maxRetries = 20;
        const baseDelay = 100; // 100ms
        // Check if globalActiveProfileId is available
        if (!window.globalActiveProfileId) {
            console.warn("[Settings] globalActiveProfileId not available, retrying...");
            if (retryCount < maxRetries - 1) {
                setTimeout(() => loadInstallationStatus(retryCount + 1), baseDelay);
            }
            return;
        }

        try {
            // Check if all required DOM elements are available
            const requiredElements = {
                installChocoBtn, installPythonBtn, installFfmpegBtn, installSpotdlBtn, installWhisperBtn,
                chocoStatus, pythonStatus, ffmpegStatus, spotdlStatus, whisperStatus
            };
            const missingElements = Object.entries(requiredElements)
                    .filter(([key, element]) => !element)
                    .map(([key]) => key);
            if (missingElements.length >0) {
                if (retryCount < maxRetries) {
                    setTimeout(() => loadInstallationStatus(retryCount +1), baseDelay);
                    return;
                } else {
                    console.error('[Settings] Failed to find DOM elements after maximum retries');
                    throw new Error(`Required UI elements not found after ${maxRetries} attempts: ${missingElements.join(', ')}`);
                }
            }

            const response = await fetch(`/api/settings/${window.globalActiveProfileId}/install-status`, {
                method: 'GET',
                headers: {
                    'Content-Type': 'application/json',
                    'Cache-Control': 'no-cache'
                },
                credentials: 'same-origin'
            });
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }

            const result = await response.json();
            // Handle different response structures (same as setup.js)
            let status = null;
            if (result.success && result.data) {
                // Standard API response format
                status = result.data;
            } else if (result.data) {
                // Direct data response (no success wrapper)
                status = result.data;
            } else if (result.pythonInstalled !== undefined) {
                // Direct status response (no wrapper)
                status = result;
            } else {
                console.error('[Settings] Unexpected response structure:', result);
                throw new Error('Unexpected response structure from installation status API');
            }

            // Ensure we have the expected properties
            if (status.pythonInstalled === undefined) {
                console.error('[Settings] Invalid status object - missing pythonInstalled property');
                throw new Error('Invalid status object returned from API');
            }

            
            // Update installation status using the same approach as setup.js
            updateInstallationStatusElements(status);
            // Use the already parsed 'result' instead of calling response.json() again
            if (result.data) {
                const status = result.data;
                // Update Chocolatey status
                updateComponentStatus('choco', status.chocoInstalled, installChocoBtn, chocoStatus);
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
            } else {
                if (retryCount < maxRetries - 1) {
                    setTimeout(() => loadInstallationStatus(retryCount + 1), baseDelay);
                    return;
                } else {
                    throw new Error(`Required UI elements not found after ${maxRetries} attempts: ${missingElements.join(', ')}`);
                }
            }
        } catch (error) {
            console.error('[Settings] Error loading installation status:', error);
            // Retry logic with linear backoff (same as setup.js)
            if (retryCount < maxRetries - 1) {
                setTimeout(() => loadInstallationStatus(retryCount + 1), baseDelay);
            } else {
                console.error('[Settings] Failed to load installation status after maximum retries');
                // Show error to user if showToast function is available
                if (typeof showToast === 'function') {
                    Toast.error('Failed to load installation status. Please refresh the page.', { duration: 5000 });
                }
                // Set default status to show appropriate UI
                const defaultStatus = {
                    chocoInstalled: false,
                    pythonInstalled: false,
                    ffmpegInstalled: false, 
                    spotdlInstalled: false,
                    whisperInstalled: false
                };
                updateInstallationStatusElements(defaultStatus);
                updateMissingComponentsDialog(defaultStatus);
            }
        }
    }

    // Manual refresh function for installation status
    window.refreshInstallationStatus = function () {
        console.log('[Settings] Manual refresh of installation status requested');
        window.loadInstallationStatus();
    };


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

        enableVideoLibraryToggle.addEventListener('change', function () {
            if (this.checked) {
                videoLibraryOptions.classList.remove('is-hidden');
                localStorage.setItem('videoLibraryEnabled', 'true');
                Toast.success("Video library enabled");
            } else {
                videoLibraryOptions.classList.add('is-hidden');
                localStorage.setItem('videoLibraryEnabled', 'false');
                Toast.info("Video library disabled");
            }
        });
    }

    window.refreshSettingsUI?.();
    setTimeout(() => window.setupLogWebSocket?.(), 0);
    
    // Initialize playlist creator if on playlist-creator tab
    if (typeof validatePlaylistForm === 'function') {
        validatePlaylistForm();
    }
    const runAsServiceToggle = document.getElementById("runAsServiceToggle");
    const runAsServiceModal = document.getElementById("runAsServiceModal");
    const modalCloseButtons = runAsServiceModal ? runAsServiceModal.querySelectorAll('.delete, .button.is-success') : [];
    
    // Auto-update toggle functionality
    const autoUpdateToggle = document.getElementById("autoUpdateToggle");
    if (autoUpdateToggle) {
        autoUpdateToggle.addEventListener('change', async () => {
            try {
                const res = await fetch(`/api/settings/${window.globalActiveProfileId}/auto-update`, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json'
                    },
                    body: JSON.stringify({ enabled: autoUpdateToggle.checked })
                });
                
                if (res.ok) {
                    if (autoUpdateToggle.checked) {
                        Toast.success("Auto-update enabled");
                    } else {
                        Toast.info("Auto-update disabled");
                    }
                } else {
                    throw new Error('Failed to update auto-update setting');
                }
            } catch (error) {
                console.error("[Settings] Failed to toggle auto-update:", error);
                Toast.error("Failed to update auto-update setting");
                // Revert toggle state if request failed
                autoUpdateToggle.checked = !autoUpdateToggle.checked;
            }
        });
    }
    
    if (runAsServiceToggle) {
        runAsServiceToggle.addEventListener('change', async () => {
            try {
                const res = await fetch(`/api/settings/${window.globalActiveProfileId}/toggle-run-as-service`, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json'
                    }
                });
                if (res.ok) {
                    if (runAsServiceToggle.checked) {
                        runAsServiceModal?.classList.add('is-active');
                        Toast.success("Run as service enabled");
                    } else {
                        Toast.info("Run as service disabled");
                    }
                } else {
Toast.error("Failed to toggle run as service");
                    // Revert the toggle state if request failed
                    runAsServiceToggle.checked = !runAsServiceToggle.checked;
                }
            } catch (error) {
                console.error("[Settings] Failed to toggle run as service:", error);
                Toast.error("Failed to toggle run as service");
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
    // Handle page visibility to clean up WebSocket connections
    document.addEventListener('visibilitychange', () => {
        if (document.hidden) {
            // Page is hidden, close WebSocket connections
            if (window.logWebSocket) {
                window.logWebSocket.close(1000, 'Page hidden');
                window.logWebSocket = null;
            }
            if (window.installationWebSocket) {
                window.installationWebSocket.close(1000, 'Page hidden');
                window.installationWebSocket = null;
            }
        } else {
            // Page is visible again, reconnect if needed
            setTimeout(() => {
                if (!window.logWebSocket) {
                    window.setupLogWebSocket();
                }
            }, 1000);
        }
    });
    // Clean up on page unload
    window.addEventListener('beforeunload', () => {
        if (window.logWebSocket) {
            window.logWebSocket.close(1000, 'Page unloading');
        }
        if (window.installationWebSocket) {
            window.installationWebSocket.close(1000, 'Page unloading');
        }
    });
});