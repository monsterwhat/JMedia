window.resetLibrary = async function () {
    const res = await fetch(`/api/settings/${globalActiveProfileId}/resetLibrary`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] Library reset to:", json.data.libraryPath);
        const pathInputElem = document.getElementById("musicLibraryPathInput");
        if (pathInputElem)
            pathInputElem.value = json.data.libraryPath;
    } else {
        console.error("[Settings] Failed to reset library:", json.error);
    }
};

window.scanLibrary = async function () {
    const res = await fetch(`/api/settings/${globalActiveProfileId}/scanLibrary`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] Scan started");
    } else {
        console.error("[Settings] Failed to scan library:", json.error);
    }
};

window.clearLogs = async function () {
    const res = await fetch(`/api/settings/${globalActiveProfileId}/clearLogs`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        const logsPanel = document.getElementById("logsPanel");
        if (logsPanel) {
            logsPanel.innerHTML = "";
        }
    } else {
        console.error("[Settings] Failed to clear logs:", json.error);
    }
};

window.clearSongsDB = async function () {
    const res = await fetch(`/api/settings/${globalActiveProfileId}/clearSongs`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] All songs deleted");
    } else {
        console.error("[Settings] Failed to clear songs DB:", json.error);
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
    } else {
        console.error("[Settings] Failed to reload metadata:", json.error);
    }
};

window.deleteDuplicates = async function () {
    const res = await fetch(`/api/settings/${globalActiveProfileId}/deleteDuplicates`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] Duplicate deletion started");
    } else {
        console.error("[Settings] Failed to delete duplicates:", json.error);
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
        // You might want to add a user-facing notification here
    } else {
        console.error('[Settings] Failed to save import settings.');
    }
};


window.refreshSettingsUI = async function () {
    console.log("[Settings] refreshSettingsUI called.");
    const res = await fetch(`/api/settings/${globalActiveProfileId}`);
    const json = await res.json();
    if (res.ok && json.data) {
        const pathInputElem = document.getElementById("musicLibraryPathInput");
        if (pathInputElem && json.data.libraryPath)
            pathInputElem.value = json.data.libraryPath;

        const runAsServiceToggle = document.getElementById("runAsServiceToggle");
        if (runAsServiceToggle) {
            runAsServiceToggle.checked = json.data.runAsService;
        }

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
    ['libraryManagementContent', 'dataManagementContent', 'logsCardContent', 'browseManagementContent', 'importSettingsContent'].forEach(contentId => {
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
    document.getElementById("clearLogs").onclick = () => showConfirmationDialog("Are you sure you want to clear all logs?", window.clearLogs);
    document.getElementById("clearSongs").onclick = () => showConfirmationDialog("Are you sure you want to clear all songs from the database? This action cannot be undone.", window.clearSongsDB);
    document.getElementById("clearPlaybackHistory").onclick = () => showConfirmationDialog("Are you sure you want to clear the playback history? This action cannot be undone.", () => fetch(`/api/settings/clearPlaybackHistory/${globalActiveProfileId}`, {method: 'POST'}));
    document.getElementById("reloadMetadata").onclick = () => showConfirmationDialog("Are you sure you want to reload all song metadata? This might take a while.", window.reloadMetadata);
    document.getElementById("deleteDuplicates").onclick = () => showConfirmationDialog("Are you sure you want to delete duplicate songs? This action cannot be undone.", window.deleteDuplicates);
    document.getElementById("saveImportSettingsBtn").onclick = window.saveImportSettings;

    const browseMusicFolderBtn = document.getElementById("browseMusicFolderBtn");
    if (browseMusicFolderBtn) {
        browseMusicFolderBtn.onclick = async () => {
            const res = await fetch(`/api/settings/${globalActiveProfileId}/browse-folder`);
            const json = await res.json();
            if (res.ok && json.data) {
                const pathInputElem = document.getElementById("musicLibraryPathInput");
                if (pathInputElem) {
                    pathInputElem.value = json.data; // Corrected: use json.data directly
                }
            } else {
                console.error("[Settings] Failed to browse folder:", json.error);
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

    window.refreshSettingsUI?.();
    setTimeout(() => window.setupLogWebSocket?.(), 0);

    const runAsServiceToggle = document.getElementById("runAsServiceToggle");
    const runAsServiceModal = document.getElementById("runAsServiceModal");
    const modalCloseButtons = runAsServiceModal ? runAsServiceModal.querySelectorAll('.delete, .button.is-success') : [];

    if (runAsServiceToggle) {
        runAsServiceToggle.addEventListener('change', () => {
            if (runAsServiceToggle.checked) {
                runAsServiceModal?.classList.add('is-active');
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