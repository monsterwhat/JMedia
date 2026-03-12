// Global state tracking
window.componentStates = { choco: false, python: false, ffmpeg: false, spotdl: false, whisper: false };

// --- Library & Metadata ---
window.resetLibrary = async function () {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    const res = await fetch(`/api/settings/${profileId}/resetLibrary`, {method: "POST"});
    const json = await res.json();
    if (res.ok && json.data) {
        if(window.showToast) window.showToast("Library reset to default path", "success");
        const pathInputElem = document.getElementById("musicLibraryPathInput");
        if (pathInputElem) pathInputElem.value = json.data.libraryPath;
    } else {
        if(window.showToast) window.showToast("Failed to reset library", "error");
    }
};

window.scanLibrary = async function () {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    const res = await fetch(`/api/settings/${profileId}/scanLibrary`, {method: "POST"});
    if (res.ok) {
        if(window.showToast) window.showToast("Library scan started", "success");
    }
};

window.clearLogs = async function () {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    const res = await fetch(`/api/settings/${profileId}/clearLogs`, {method: "POST"});
    if (res.ok) {
        const logsPanel = document.getElementById("logsPanel");
        if (logsPanel) logsPanel.innerHTML = "";
    }
};

window.clearSongsDB = async function () {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    const res = await fetch(`/api/settings/${profileId}/clearSongs`, {method: "POST"});
    if (res.ok) {
        if(window.showToast) window.showToast("All songs cleared", "success");
    }
};

window.reloadMetadata = async function () {
    const profileId = window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    const res = await fetch(`/api/settings/${profileId}/reloadMetadata`, {method: "POST"});
    if (res.ok) {
        if(window.showToast) window.showToast("Metadata reload started", "success");
    }
};

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
            if(window.showToast) window.showToast('UI settings saved', 'success');
            localStorage.setItem('sidebarPosition', position);
            const layout = document.getElementById('plex-layout');
            if (layout) {
                if (position === 'right') layout.classList.add('sidebar-right');
                else layout.classList.remove('sidebar-right');
            }
        }
    } catch (e) {}
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

window.initSettingsView = async function() {
    console.log("Initializing Settings View");
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

    ['Music', 'Video'].forEach(t => {
        const btn = document.getElementById(`browse${t}FolderBtn`);
        if (btn) btn.onclick = () => window.openFolderBrowser(t);
    });

    const tabs = document.querySelectorAll('#settingsSideTabs .nav-item');
    tabs.forEach(t => {
        t.onclick = () => {
            const target = t.getAttribute('data-tab');
            if (!target) return;
            tabs.forEach(x => x.classList.remove('active'));
            document.querySelectorAll('.tab-pane').forEach(p => p.classList.remove('is-active'));
            t.classList.add('active');
            const targetEl = document.getElementById(target);
            if (targetEl) targetEl.classList.add('is-active');
            
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
    window.checkHttpsStatus();
    window.setupHttpsButton();
};

window.saveImportSettings = async function () {
    const profileId = window.globalActiveProfileId || '1';
    const outputFormat = document.getElementById('outputFormat').value;
    const downloadThreads = parseInt(document.getElementById('downloadThreads').value);
    const searchThreads = parseInt(document.getElementById('searchThreads').value);
    const settings = { outputFormat, downloadThreads, searchThreads };
    const res = await fetch(`/api/settings/${profileId}/import`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(settings)
    });
    if (res.ok) {
        if(window.showToast) window.showToast('Import settings saved', 'success');
    }
};

window.clearPlaybackHistory = async function () {
    const profileId = window.globalActiveProfileId || '1';
    try {
        const resMusic = await fetch(`/api/settings/clearPlaybackHistory/${profileId}`, { method: "POST" });
        const resVideo = await fetch(`/api/video/clear-history`, { method: "POST" });
        if (resMusic.ok && resVideo.ok) {
            if(window.showToast) window.showToast("History cleared", "success");
        }
    } catch (e) {}
};

window.loadPlaybackSettings = async function () {
    const profileId = window.globalActiveProfileId || '1';
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
    const profileId = window.globalActiveProfileId || '1';
    const input = document.getElementById('crossfadeDuration');
    const val = input ? parseInt(input.value) : 0;
    try {
        await fetch(`/api/music/playback/crossfade/${profileId}/${val}`, { method: 'POST' });
        if(window.showToast) window.showToast('Playback settings saved', 'success');
    } catch (e) {}
};

window.loadProfiles = async function () {
    const list = document.getElementById('profileList');
    if (!list) return;
    try {
        const res = await fetch('/api/profiles');
        const profiles = await res.json();
        const curRes = await fetch('/api/profiles/current');
        const cur = await curRes.json();
        list.innerHTML = profiles.map(p => `
            <div class="card mb-2" style="background: rgba(255,255,255,0.05); border: 1px solid rgba(255,255,255,0.1);">
                <div class="card-content p-3">
                    <div class="is-flex is-justify-content-space-between is-align-items-center">
                        <p class="has-text-weight-semibold" style="color: white;">${p.name} ${p.isMainProfile ? '<span class="tag is-warning is-small ml-2">Main</span>' : ''} ${cur.id === p.id ? '<span class="tag is-info is-small ml-2">Current</span>' : ''}</p>
                        ${!p.isMainProfile ? `<button class="button is-danger is-light is-small" onclick="window.deleteProfile(${p.id})"><i class="pi pi-trash"></i></button>` : ''}
                    </div>
                </div>
            </div>
        `).join('');
    } catch (e) {}
};

async function handleComponentAction(comp, btn) {
    const profileId = window.globalActiveProfileId || '1';
    const isInstalled = window.componentStates[comp];
    const action = isInstalled ? 'uninstall' : 'install';
    btn.disabled = true;
    btn.classList.add('is-loading');
    try {
        const res = await fetch(`/api/import/${action}/${comp}/${profileId}`, { method: 'POST' });
        if (res.ok) {
            if(window.showToast) window.showToast(`${comp} ${action}ation started`, 'info');
            if (!window.installationWebSocket) setupInstallationWebSocket();
        }
    } catch (e) { 
        btn.disabled = false;
        btn.classList.remove('is-loading');
    }
}

function setupInstallationWebSocket() {
    const profileId = window.globalActiveProfileId || '1';
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
    const profileId = window.globalActiveProfileId || '1';
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
    const profileId = window.globalActiveProfileId || '1';
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

window.openFolderBrowser = function(target) {
    window.currentBrowserTarget = target;
    const currentInput = document.getElementById(`${target.toLowerCase()}LibraryPathInput`);
    const initialPath = currentInput ? currentInput.value : '';
    
    document.getElementById('folderBrowserModal').classList.add('is-active');
    window.loadFolders(initialPath === '(not set)' ? '' : initialPath);
};

window.closeFolderBrowser = function() {
    document.getElementById('folderBrowserModal').classList.remove('is-active');
};

window.loadFolders = async function(path) {
    const list = document.getElementById('folderBrowserList');
    if(!list) return;
    list.innerHTML = '<div class="p-4 has-text-centered"><i class="pi pi-spin pi-spinner"></i> Listing folders...</div>';
    
    try {
        const res = await fetch(`/api/settings/browse/list-folders?path=${encodeURIComponent(path || '')}`);
        const json = await res.json();
        
        if (res.ok && json.data) {
            window.currentBrowserPath = json.data.currentPath || '';
            const display = document.getElementById('currentFolderPathDisplay');
            if(display) display.value = window.currentBrowserPath || 'System Roots';
            
            window._parentPath = json.data.parentPath;
            
            const folders = json.data.folders || [];
            if (folders.length === 0) {
                list.innerHTML = '<div class="p-4 has-text-centered opacity-50">No subfolders found</div>';
            } else {
                list.innerHTML = folders.map(f => `
                    <div class="p-3 is-clickable folder-item" onclick="window.loadFolders('${f.path.replace(/\\/g, '\\\\')}')" 
                         style="border-bottom: 1px solid rgba(255,255,255,0.05); transition: 0.2s;">
                        <i class="pi pi-folder mr-3" style="color: #48c774;"></i>
                        <span>${f.name}</span>
                    </div>
                `).join('');
            }
        } else {
            list.innerHTML = `<div class="p-4 has-text-danger">Error: ${json.error || 'Access denied'}</div>`;
        }
    } catch (e) {
        list.innerHTML = `<div class="p-4 has-text-danger">Connection error</div>`;
    }
};

window.navigateUpFolder = function() {
    if (window._parentPath !== undefined && window._parentPath !== null) {
        window.loadFolders(window._parentPath);
    }
};

window.confirmFolderSelection = function() {
    if (window.currentBrowserTarget && window.currentBrowserPath) {
        const input = document.getElementById(`${window.currentBrowserTarget.toLowerCase()}LibraryPathInput`);
        if (input) input.value = window.currentBrowserPath;
        if(window.showToast) window.showToast(`${window.currentBrowserTarget} folder selected`, 'success');
        window.closeFolderBrowser();
    }
};

window.checkHttpsStatus = async function() {
    try {
        const res = await fetch('/api/settings/https/status');
        const json = await res.json();
        const badge = document.getElementById('httpsStatusBadge');
        const generateBtn = document.getElementById('generateHttpsBtn');
        
        if (badge) {
            if (json.data) {
                badge.className = 'tag is-success';
                badge.innerText = 'Enabled (Restart Required if just generated)';
                if (generateBtn) generateBtn.style.display = 'none'; 
            } else {
                badge.className = 'tag is-danger';
                badge.innerText = 'Disabled';
                if (generateBtn) generateBtn.style.display = 'inline-flex';
            }
        }
    } catch (e) {}
};

window.setupHttpsButton = function() {
    const generateBtn = document.getElementById('generateHttpsBtn');
    if (generateBtn) {
        generateBtn.onclick = async () => {
            if (!confirm('This will generate a self-signed certificate and update your configuration. You will need to manually restart the app afterward. Continue?')) return;
            
            generateBtn.classList.add('is-loading');
            try {
                const res = await fetch('/api/settings/https/generate', { method: 'POST' });
                if (res.ok) {
                    alert('Certificate generated successfully!\n\n1. Please close the console/app.\n2. Start JMedia again.\n3. Access via https://localhost:8443\n\n(Standard HTTP on 8080 will still work)');
                    window.checkHttpsStatus();
                } else {
                    const json = await res.json();
                    if(window.showToast) window.showToast(json.error || 'Failed to generate certificate', 'error');
                }
            } catch (e) {
                if(window.showToast) window.showToast('Connection error during certificate generation', 'error');
            } finally {
                generateBtn.classList.remove('is-loading');
            }
        };
    }
};
