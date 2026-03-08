// Global state tracking
window.componentStates = { choco: false, python: false, ffmpeg: false, spotdl: false, whisper: false };

// --- Library & Metadata ---
window.resetLibrary = async function () {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    const res = await fetch(`/api/settings/${profileId}/resetLibrary`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        Toast.success("Library reset to default path");
        const pathInputElem = document.getElementById("musicLibraryPathInput");
        if (pathInputElem) pathInputElem.value = json.data.libraryPath;
    } else {
        Toast.error("Failed to reset library: " + (json.error || "Unknown error"));
    }
};

window.scanLibrary = async function () {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    const res = await fetch(`/api/settings/${profileId}/scanLibrary`, {method: "POST"});
    if (res.ok) Toast.success("Library scan started");
    else Toast.error("Failed to scan library");
};

window.clearLogs = async function () {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    const res = await fetch(`/api/settings/${profileId}/clearLogs`, {method: "POST"});
    if (res.ok) {
        Toast.success("Logs cleared successfully");
        const logsPanel = document.getElementById("logsPanel");
        if (logsPanel) logsPanel.innerHTML = "";
    }
};

window.clearSongsDB = async function () {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    const res = await fetch(`/api/settings/${profileId}/clearSongs`, {method: "POST"});
    if (res.ok) Toast.success("All songs cleared from database");
    else Toast.error("Failed to clear songs");
};

window.reloadMetadata = async function () {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    const res = await fetch(`/api/settings/${profileId}/reloadMetadata`, {method: "POST"});
    if (res.ok) Toast.success("Metadata reload started");
    else Toast.error("Failed to reload metadata");
};

window.deleteDuplicates = async function () {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    const res = await fetch(`/api/settings/${profileId}/deleteDuplicates`, {method: "POST"});
    if (res.ok) Toast.success("Duplicate deletion started");
};

// --- Log WebSocket ---
window.setupLogWebSocket = function () {
    const logsPanel = document.getElementById("logsPanel");
    if (!logsPanel) return;

    if (window.logWebSocket && window.logWebSocket.readyState <= 1) return;

    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    const protocol = location.protocol === 'https:' ? 'wss://' : 'ws://';
    const socket = new WebSocket(`${protocol}${window.location.host}/api/logs/ws/${profileId}`);

    socket.onmessage = function (event) {
        try {
            const message = JSON.parse(event.data);
            if (message.type === "log") {
                const p = document.createElement("p");
                p.style.margin = "0";
                p.style.padding = "2px 0";
                p.style.borderBottom = "1px solid rgba(255,255,255,0.05)";
                p.style.color = "#48c774";
                p.textContent = message.payload;
                logsPanel.appendChild(p);
                while (logsPanel.children.length > 100) logsPanel.removeChild(logsPanel.firstChild);
                logsPanel.scrollTop = logsPanel.scrollHeight;
            }
        } catch (e) {}
    };

    socket.onopen = () => console.log("[Logs] WebSocket connected");
    socket.onclose = () => setTimeout(window.setupLogWebSocket, 5000);
    window.logWebSocket = socket;
};

// --- UI & Playback Settings ---
window.saveUiSettings = async function () {
    const select = document.getElementById('sidebarPositionSelect');
    if (!select) return;
    const position = select.value;
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    
    try {
        const res = await fetch(`/api/settings/${profileId}/sidebar-position`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ position: position })
        });

        if (res.ok) {
            Toast.success('UI settings saved successfully');
            localStorage.setItem('sidebarPosition', position);
            const layout = document.getElementById('plex-layout');
            if (layout) {
                if (position === 'right') layout.classList.add('sidebar-right');
                else layout.classList.remove('sidebar-right');
            }
        } else {
            Toast.error('Failed to save UI settings');
        }
    } catch (e) { Toast.error('Error saving UI settings'); }
};

window.loadUiSettings = async function () {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    try {
        const res = await fetch(`/api/settings/${profileId}/sidebar-position`);
        const json = await res.json();
        if (res.ok && json.data) {
            const select = document.getElementById('sidebarPositionSelect');
            if (select) select.value = json.data;
            localStorage.setItem('sidebarPosition', json.data);
            const layout = document.getElementById('plex-layout');
            if (layout) {
                if (json.data === 'right') layout.classList.add('sidebar-right');
                else layout.classList.remove('sidebar-right');
            }
            return json.data;
        }
    } catch (e) {}
    return null;
};

window.saveImportSettings = async function () {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    const outputFormat = document.getElementById('outputFormat').value;
    const downloadThreads = parseInt(document.getElementById('downloadThreads').value);
    const searchThreads = parseInt(document.getElementById('searchThreads').value);
    const settings = { outputFormat, downloadThreads, searchThreads };
    const res = await fetch(`/api/settings/${profileId}/import`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(settings)
    });
    if (res.ok) Toast.success('Import settings saved');
    else Toast.error('Failed to save import settings');
};

window.clearPlaybackHistory = async function () {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    try {
        // Clear Music History
        const resMusic = await fetch(`/api/settings/clearPlaybackHistory/${profileId}`, { method: "POST" });
        // Clear Video History
        const resVideo = await fetch(`/api/video/clear-history`, { method: "POST" });
        
        if (resMusic.ok && resVideo.ok) {
            Toast.success("All playback history cleared");
        } else {
            Toast.error("Failed to clear some history");
        }
    } catch (e) {
        Toast.error("Error clearing history");
    }
};

window.loadPlaybackSettings = async function () {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    try {
        const res = await fetch(`/api/music/playback/crossfade/${profileId}`);
        const json = await res.json();
        if (res.ok && json.data !== undefined) {
            const input = document.getElementById('crossfadeDuration');
            const val = document.getElementById('crossfadeValue');
            if (input) { input.value = json.data; if (val) val.textContent = json.data; }
        }
    } catch (e) {}
};

window.savePlaybackSettings = async function () {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    const input = document.getElementById('crossfadeDuration');
    const val = input ? parseInt(input.value) : 0;
    try {
        await fetch(`/api/music/playback/crossfade/${profileId}/${val}`, { method: 'POST' });
        Toast.success('Playback settings saved');
    } catch (e) { Toast.error('Failed to save playback settings'); }
};

// --- Profile Management ---
window.loadProfiles = async function () {
    const list = document.getElementById('profileList');
    if (!list) return;
    try {
        const res = await fetch('/api/profiles');
        const profiles = await res.json();
        const curRes = await fetch('/api/profiles/current');
        const cur = await curRes.json();
        list.innerHTML = profiles.map(p => `
            <div class="card mb-2">
                <div class="card-content p-3">
                    <div class="is-flex is-justify-content-space-between is-align-items-center">
                        <p class="has-text-weight-semibold">${p.name} ${p.isMainProfile ? '<span class="tag is-warning is-small ml-2">Main</span>' : ''} ${cur.id === p.id ? '<span class="tag is-info is-small ml-2">Current</span>' : ''}</p>
                        ${!p.isMainProfile ? `<button class="button is-danger is-light is-small" onclick="window.deleteProfile(${p.id})"><i class="pi pi-trash"></i></button>` : ''}
                    </div>
                </div>
            </div>
        `).join('');
    } catch (e) {}
};

window.createProfile = async function () {
    const input = document.getElementById('newProfileNameInput');
    const name = input?.value?.trim();
    if (!name) return Toast.error('Enter a name');
    const res = await fetch('/api/profiles', { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: name });
    if (res.ok) { Toast.success('Profile created'); input.value = ''; window.loadProfiles(); }
};

window.deleteProfile = async function (id) {
    if (!confirm('Are you sure?')) return;
    const res = await fetch(`/api/profiles/${id}`, { method: 'DELETE' });
    if (res.ok) { Toast.success('Profile deleted'); window.loadProfiles(); }
};

// --- Installation Manager ---
async function handleComponentAction(comp, btn) {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    const isInstalled = window.componentStates[comp];
    const action = isInstalled ? 'uninstall' : 'install';
    btn.disabled = true;
    btn.classList.add('is-loading');
    try {
        const res = await fetch(`/api/import/${action}/${comp}/${profileId}`, { method: 'POST' });
        if (res.ok) {
            Toast.info(`${comp} ${action}ation started`);
            if (!window.installationWebSocket) setupInstallationWebSocket();
        }
    } catch (e) { 
        btn.disabled = false;
        btn.classList.remove('is-loading');
    }
}

function setupInstallationWebSocket() {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    const protocol = location.protocol === 'https:' ? 'wss://' : 'ws://';
    window.installationWebSocket = new WebSocket(`${protocol}${location.host}/ws/import-status/${profileId}`);
    window.installationWebSocket.onmessage = (e) => {
        const msg = e.data;
        ['CHOCO', 'PYTHON', 'FFMPEG', 'SPOTDL', 'WHISPER'].forEach(c => {
            if (msg.includes(`[${c}_INSTALLATION_FINISHED]`) || msg.includes(`[${c}_UNINSTALLATION_FINISHED]`)) window.loadInstallationStatus();
        });
    };
}

window.loadInstallationStatus = async function () {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    try {
        const res = await fetch(`/api/settings/${profileId}/install-status`);
        const json = await res.json();
        const status = json.data || json;
        if (status) {
            ['choco', 'python', 'ffmpeg', 'spotdl', 'whisper'].forEach(c => {
                const isInst = status[`${c}Installed`];
                window.componentStates[c] = isInst;
                const btn = document.getElementById(`install${c.charAt(0).toUpperCase() + c.slice(1)}Btn`);
                const stat = document.getElementById(`${c}Status`);
                if (btn) {
                    btn.disabled = false;
                    btn.classList.remove('is-loading');
                    btn.innerHTML = isInst ? `<i class="pi pi-trash mr-1"></i>Remove` : `<i class="pi pi-download mr-1"></i>Install`;
                    btn.className = `button is-small is-rounded ${isInst ? 'is-danger' : 'is-success'}`;
                }
                if (stat) {
                    stat.textContent = isInst ? 'Installed' : 'Not installed';
                    stat.className = `help ${isInst ? 'has-text-success' : 'has-text-danger'}`;
                }
            });
        }
    } catch (e) {}
};

window.refreshSettingsUI = async function () {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    const res = await fetch(`/api/settings/${profileId}`);
    const json = await res.json();
    if (res.ok && json.data) {
        const d = json.data;
        const setVal = (id, val) => { const el = document.getElementById(id); if (el) el.value = val || ''; };
        setVal("musicLibraryPathInput", d.libraryPath);
        setVal("videoLibraryPathInput", d.videoLibraryPath);
        setVal("tmdbApiKeyInput", d.tmdbApiKey);
        setVal("outputFormat", d.outputFormat);
        setVal("downloadThreads", d.downloadThreads);
        setVal("searchThreads", d.searchThreads);
        const svc = document.getElementById("runAsServiceToggle");
        if (svc) svc.checked = d.runAsService;
    }
};

window.checkAdminStatus = async function() {
    try {
        const res = await fetch('/api/auth/is-admin');
        const json = await res.json();
        const isAdmin = json.data && json.data.isAdmin;
        document.querySelectorAll('.admin-only').forEach(el => {
            el.style.display = isAdmin ? (el.classList.contains('nav-item') ? 'flex' : 'block') : 'none';
        });
    } catch (e) {}
};

// --- Initialization ---
document.addEventListener("DOMContentLoaded", async () => {
    window.globalActiveProfileId = localStorage.getItem('activeProfileId') || '1';
    await window.checkAdminStatus();
    
    const setupClick = (id, fn, msg) => {
        const el = document.getElementById(id);
        if (el) el.onclick = () => msg ? (confirm(msg) && fn()) : fn();
    };

    setupClick("resetLibrary", window.resetLibrary, "Reset library path?");
    setupClick("scanLibrary", window.scanLibrary);
    setupClick("clearSongs", window.clearSongsDB, "Clear songs?");
    setupClick("clearLogs", window.clearLogs, "Clear logs?");
    setupClick("clearPlaybackHistory", window.clearPlaybackHistory, "Clear all playback history?");
    setupClick("reloadMetadata", window.reloadMetadata, "Reload metadata?");
    setupClick("deleteDuplicates", window.deleteDuplicates, "Delete duplicates?");
    setupClick("saveImportSettingsBtn", window.saveImportSettings);
    setupClick("savePlaybackSettingsBtn", window.savePlaybackSettings);
    setupClick("saveUiSettingsBtn", window.saveUiSettings);
    setupClick("createProfileBtn", window.createProfile);

    ['choco', 'python', 'ffmpeg', 'spotdl', 'whisper'].forEach(c => {
        const btn = document.getElementById(`install${c.charAt(0).toUpperCase() + c.slice(1)}Btn`);
        if (btn) btn.onclick = () => handleComponentAction(c, btn);
    });

    const layout = document.getElementById('plex-layout');
    setupClick("toggleSidebarBtn", () => {
        layout.classList.toggle('collapsed');
        localStorage.setItem('sidebarCollapsed', layout.classList.contains('collapsed'));
    });

    ['Music', 'Video'].forEach(t => {
        const btn = document.getElementById(`browse${t}FolderBtn`);
        if (btn) btn.onclick = async () => {
            const endpoint = t === 'Music' ? 'browse-folder' : 'browse-video-folder';
            try {
                const res = await fetch(`/api/settings/${window.globalActiveProfileId}/${endpoint}`);
                const json = await res.json();
                if (res.ok) {
                    if (json.data) {
                        document.getElementById(`${t.toLowerCase()}LibraryPathInput`).value = json.data;
                        Toast.success(`${t} folder selected`);
                    }
                } else {
                    Toast.error(json.error || `Failed to open ${t.toLowerCase()} folder browser`);
                }
            } catch (e) {
                Toast.error(`Connection error opening folder browser`);
            }
        };
    });
// HTMX Logic
document.body.addEventListener('htmx:configRequest', (evt) => {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    // Ensure profileId is in the URL if it's a template URL
    evt.detail.path = evt.detail.path.replace('{profileId}', profileId);
});

document.body.addEventListener('htmx:afterRequest', (e) => {
    const id = e.detail.elt.id;
    if (id === 'saveMusicLibraryPathBtn' && e.detail.successful) {
        Toast.success("Music library path saved");
    }
    if (id === 'saveVideoLibraryPathBtn' && e.detail.successful) {
        Toast.success("Video library settings saved");
    }
});

    const tabs = document.querySelectorAll('#settingsSideTabs .nav-item');
    tabs.forEach(t => {
        t.onclick = () => {
            const target = t.getAttribute('data-tab');
            if (!target) return;
            tabs.forEach(x => x.classList.remove('active'));
            document.querySelectorAll('.tab-pane').forEach(p => p.classList.remove('is-active'));
            t.classList.add('active');
            document.getElementById(target).classList.add('is-active');
            if (target === 'import-installation') window.loadInstallationStatus();
            if (target === 'user-management' && window.loadUsers) window.loadUsers();
            if (target === 'logs') window.setupLogWebSocket();
        };
    });

    window.loadProfiles();
    window.loadPlaybackSettings();
    window.loadUiSettings();
    window.refreshSettingsUI();
    window.setupLogWebSocket();
});
