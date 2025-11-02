window.resetLibrary = async function () {
    const res = await fetch(`/api/settings/resetLibrary`, {method: "POST"});
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
    const res = await fetch(`/api/settings/scanLibrary`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] Scan started");
    } else {
        console.error("[Settings] Failed to scan library:", json.error);
    }
};

window.clearLogs = async function () {
    const res = await fetch(`/api/settings/clearLogs`, {method: "POST"});
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
    const res = await fetch(`/api/settings/clearSongs`, {method: "POST"});
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

    const socket = new WebSocket(`ws://${window.location.host}/api/logs/ws`);

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
    const res = await fetch(`/api/settings`);
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
    const res = await fetch(`/api/settings/reloadMetadata`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] Metadata reload started");
    } else {
        console.error("[Settings] Failed to reload metadata:", json.error);
    }
};

window.deleteDuplicates = async function () {
    const res = await fetch(`/api/settings/deleteDuplicates`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] Duplicate deletion started");
    } else {
        console.error("[Settings] Failed to delete duplicates:", json.error);
    }
};

window.toggleTorrentBrowsing = async function (checkbox) {
    console.log("[Settings] toggleTorrentBrowsing called. Checkbox checked state (initial):", checkbox.checked);
    const browseManagementContent = document.getElementById("browseManagementContent");
    const childToggles = browseManagementContent.querySelectorAll("input[type='checkbox']");

    // Immediate visual feedback: disable/enable content based on checkbox state
    if (checkbox.checked) {
        browseManagementContent.classList.remove("is-disabled-overlay");
        childToggles.forEach(toggle => toggle.disabled = false);
        console.log("[Settings] browseManagementContent enabled immediately.");
    } else {
        browseManagementContent.classList.add("is-disabled-overlay");
        childToggles.forEach(toggle => toggle.disabled = true);
        console.log("[Settings] browseManagementContent disabled immediately.");
    }

    if (checkbox.checked) {
        console.log("[Settings] Attempting to show confirmation dialog.");
        const confirmed = confirm(
                "By enabling torrent browsing, you acknowledge that your IP address may be visible to other peers in the torrent network. This feature is intended solely for lawful and legitimate use. While this application can verify the integrity of torrents to ensure they match the original data shared, it does not verify or guarantee the safety, legality, or quality of the content itself. Torrents may still contain harmful, illegal, or malicious material; proceed at your own risk. We do not host, control, endorse, or assume responsibility for any user activity, shared content, or consequences resulting from the use of this feature. By continuing, you confirm that you will only access and distribute content you have the legal rights to. Do you wish to proceed?"
                );
        console.log("[Settings] Confirmation dialog result:", confirmed);
        if (!confirmed) {
            checkbox.checked = false; // Revert checkbox state
            browseManagementContent.classList.add("is-disabled-overlay"); // Ensure disabled state
            childToggles.forEach(toggle => toggle.disabled = true); // Ensure child toggles are disabled
            console.log("[Settings] Torrent browsing disabled by user. UI updated.");
            return;
        }
    }

    const res = await fetch(`/api/settings/toggleTorrentBrowsing?enabled=${checkbox.checked}`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] Torrent browsing toggled successfully. API response:", json.data);
        // UI state already updated for immediate feedback, no need to re-update here unless API response dictates otherwise
    } else {
        console.error("[Settings] Failed to toggle torrent browsing:", json.error);
        checkbox.checked = !checkbox.checked; // Revert on error
        // Revert UI state as well
        if (checkbox.checked) {
            browseManagementContent.classList.remove("is-disabled-overlay");
            childToggles.forEach(toggle => toggle.disabled = false);
            console.log("[Settings] browseManagementContent enabled (reverted on API error).");
        } else {
            browseManagementContent.classList.add("is-disabled-overlay");
            childToggles.forEach(toggle => toggle.disabled = true);
            console.log("[Settings] browseManagementContent disabled (reverted on API error).");
        }
    }
};

window.toggleTorrentPeerDiscovery = async function (checkbox) {
    console.log("[Settings] toggleTorrentPeerDiscovery called. Checkbox checked state:", checkbox.checked);
    const res = await fetch(`/api/settings/toggleTorrentPeerDiscovery?enabled=${checkbox.checked}`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] Torrent peer discovery toggled successfully. API response:", json.data);
    } else {
        console.error("[Settings] Failed to toggle torrent peer discovery:", json.error);
        checkbox.checked = !checkbox.checked; // Revert on error
        console.log("[Settings] Torrent peer discovery checkbox reverted to:", checkbox.checked);
    }
};

window.toggleTorrentDiscovery = async function (checkbox) {
    console.log("[Settings] toggleTorrentDiscovery called. Checkbox checked state:", checkbox.checked);
    const res = await fetch(`/api/settings/toggleTorrentDiscovery?enabled=${checkbox.checked}`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        console.log("[Settings] Torrent discovery toggled successfully. API response:", json.data);
    } else {
        console.error("[Settings] Failed to toggle torrent discovery:", json.error);
        checkbox.checked = !checkbox.checked; // Revert on error
        console.log("[Settings] Torrent discovery checkbox reverted to:", checkbox.checked);
    }
};

window.refreshSettingsUI = async function () {
    console.log("[Settings] refreshSettingsUI called.");
    const res = await fetch(`/api/settings`);
    const json = await res.json();
    if (res.ok && json.data) {
        const pathInputElem = document.getElementById("musicLibraryPathInput");
        if (pathInputElem && json.data.libraryPath)
            pathInputElem.value = json.data.libraryPath;

        const torrentBrowsingToggle = document.getElementById("torrentBrowsingToggle");
        const browseManagementContent = document.getElementById("browseManagementContent");
        const childToggles = browseManagementContent.querySelectorAll("input[type='checkbox']");

        if (torrentBrowsingToggle) {
            torrentBrowsingToggle.checked = json.data.torrentBrowsingEnabled;
            console.log("[Settings] Initial torrentBrowsingToggle state:", torrentBrowsingToggle.checked);
            if (json.data.torrentBrowsingEnabled) {
                browseManagementContent.classList.remove("is-disabled-overlay");
                childToggles.forEach(toggle => toggle.disabled = false);
                console.log("[Settings] browseManagementContent initially enabled.");
            } else {
                browseManagementContent.classList.add("is-disabled-overlay");
                childToggles.forEach(toggle => toggle.disabled = true);
                console.log("[Settings] browseManagementContent initially disabled.");
            }
        }

        const torrentPeerDiscoveryToggle = document.getElementById("torrentPeerDiscoveryToggle");
        if (torrentPeerDiscoveryToggle)
            torrentPeerDiscoveryToggle.checked = json.data.torrentPeerDiscoveryEnabled;

        const torrentDiscoveryToggle = document.getElementById("torrentDiscoveryToggle");
        if (torrentDiscoveryToggle)
            torrentDiscoveryToggle.checked = json.data.torrentDiscoveryEnabled;

        const runAsServiceToggle = document.getElementById("runAsServiceToggle");
        if (runAsServiceToggle) {
            runAsServiceToggle.checked = json.data.runAsService;
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
        ['libraryManagementContent', 'dataManagementContent', 'logsCardContent', 'browseManagementContent'].forEach(contentId => {
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
        document.getElementById("clearPlaybackHistory").onclick = () => showConfirmationDialog("Are you sure you want to clear the playback history? This action cannot be undone.", () => fetch('/api/settings/clearPlaybackHistory', {method: 'POST'}));
        document.getElementById("reloadMetadata").onclick = () => showConfirmationDialog("Are you sure you want to reload all song metadata? This might take a while.", window.reloadMetadata);
        document.getElementById("deleteDuplicates").onclick = () => showConfirmationDialog("Are you sure you want to delete duplicate songs? This action cannot be undone.", window.deleteDuplicates);

        const browseMusicFolderBtn = document.getElementById("browseMusicFolderBtn");
        if (browseMusicFolderBtn) {
            browseMusicFolderBtn.onclick = async () => {
                const res = await fetch(`/api/settings/browse-folder`);
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

        const toggleTorrentManagementBtn = document.getElementById("toggleTorrentManagementBtn");
        if (toggleTorrentManagementBtn) {
            toggleTorrentManagementBtn.onclick = (event) => window.toggleCardContent(event.currentTarget, "browseManagementContent");
        }

        const torrentBrowsingToggle = document.getElementById("torrentBrowsingToggle");
        if (torrentBrowsingToggle) {
            torrentBrowsingToggle.onchange = (event) => window.toggleTorrentBrowsing(event.target);
        }

        const torrentPeerDiscoveryToggle = document.getElementById("torrentPeerDiscoveryToggle");
        if (torrentPeerDiscoveryToggle) {
            torrentPeerDiscoveryToggle.onchange = (event) => window.toggleTorrentPeerDiscovery(event.target);
        }

        const torrentDiscoveryToggle = document.getElementById("torrentDiscoveryToggle");
        if (torrentDiscoveryToggle) {
            torrentDiscoveryToggle.onchange = (event) => window.toggleTorrentDiscovery(event.target);
        }

        window.refreshSettingsUI?.();
        window.setupLogWebSocket?.();

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