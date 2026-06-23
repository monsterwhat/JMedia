(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    function getProfileId() {
        return window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    }

    window.componentStates = window.componentStates || { choco: false, python: false, ffmpeg: false, spotdl: false, parakeet: false };

    document.body.addEventListener('htmx:configRequest', function(evt) {
        const profileId = getProfileId();
        const path = evt.detail.path;
        if ((path.includes('/api/settings/music-library-path') || path.includes('/api/settings/video-library-path')) && !path.includes(profileId)) {
            evt.detail.path = path.replace('/api/settings/', `/api/settings/${profileId}/`);
        }
    });

    async function handleComponentAction(comp, btn) {
        const profileId = getProfileId();
        const isInstalled = window.componentStates[comp];
        const action = isInstalled ? 'uninstall' : 'install';
        btn.disabled = true;
        btn.classList.add('is-loading');
        try {
            const res = await fetch(`/api/import/${action}/${comp}/${profileId}`, { method: 'POST' });
            if (res.ok) {
                if(window.showToast) window.showToast(`${comp} ${action}ation started`, 'info');
                if (!window.installationWebSocket || window.installationWebSocket.readyState > 1) {
                    if (window.installationWebSocket) window.installationWebSocket.close();
                    setupInstallationWebSocket();
                }
            } else {
                if(window.showToast) window.showToast(`Failed to ${action} ${comp}`, 'error');
            }
        } catch (e) {
            if(window.showToast) window.showToast(`Error: ${e.message}`, 'error');
        } finally {
            btn.disabled = false;
            btn.classList.remove('is-loading');
        }
    }

    function setupInstallationWebSocket() {
        const profileId = getProfileId();
        const protocol = location.protocol === 'https:' ? 'wss://' : 'ws://';
        window.installationWebSocket = new WebSocket(`${protocol}${location.host}/ws/import-status/${profileId}`);
        window.installationWebSocket.onmessage = (e) => {
            const msg = e.data;
            ['CHOCO', 'PYTHON', 'FFMPEG', 'SPOTDL', 'PARAKEET'].forEach(c => {
                if (msg.includes(`[${c}_INSTALLATION_FINISHED]`) || msg.includes(`[${c}_UNINSTALLATION_FINISHED]`)) window.loadInstallationStatus();
            });
        };
    }

    JMedia.Settings = {
        getProfileId,

        resetLibrary: async function () {
            const profileId = getProfileId();
            const res = await fetch(`/api/settings/${profileId}/resetLibrary`, {method: "POST"});
            const json = await res.json();
            if (res.ok && json.data) {
                if(window.showToast) window.showToast("Library reset to default path", "success");
                const pathInputElem = document.getElementById("musicLibraryPathInput");
                if (pathInputElem) pathInputElem.value = json.data.libraryPath;
            } else {
                if(window.showToast) window.showToast("Failed to reset library", "error");
            }
        },

        scanLibrary: async function () {
            const profileId = getProfileId();
            const res = await fetch(`/api/settings/${profileId}/scanLibrary`, {method: "POST"});
            if (res.ok) {
                if(window.showToast) window.showToast("Library scan started", "success");
            }
        },

        clearLogs: async function () {
            const profileId = getProfileId();
            const res = await fetch(`/api/settings/${profileId}/clearLogs`, {method: "POST"});
            if (res.ok) {
                const logsPanel = document.getElementById("logsPanel");
                if (logsPanel) logsPanel.innerHTML = "";
            }
        },

        clearSongsDB: async function () {
            const profileId = getProfileId();
            const res = await fetch(`/api/settings/${profileId}/clearSongs`, {method: "POST"});
            if (res.ok) {
                if(window.showToast) window.showToast("All songs cleared", "success");
            }
        },

        showScanVideoDialog: function() {
            const dialogHtml = `
                <div class="modal is-active">
                    <div class="modal-background" onclick="window.closeScanVideoDialog()"></div>
                    <div class="modal-card">
                        <header class="modal-card-head">
                            <p class="modal-card-title">Scan Videos</p>
                            <button class="delete" aria-label="close" onclick="window.closeScanVideoDialog()"></button>
                        </header>
                        <section class="modal-card-body">
                            <p class="mb-4">Choose scan mode:</p>
                            <div class="content">
                                <p><strong>Update Scan</strong> - Finds only new videos (keeps existing metadata)</p>
                                <p class="has-text-grey is-size-7">Quick scan - only processes new files</p>
                            </div>
                            <div class="content mt-4">
                                <p><strong>Full Scan</strong> - Reloads all videos (may update metadata)</p>
                                <p class="has-text-grey is-size-7">Slower - re-processes all files, may update titles/descriptions</p>
                            </div>
                        </section>
                        <footer class="modal-card-foot">
                            <button class="button is-info" onclick="window.scanVideos('update')">Update Scan</button>
                            <button class="button is-warning" onclick="window.scanVideos('full')">Full Scan</button>
                            <button class="button" onclick="window.closeScanVideoDialog()">Cancel</button>
                        </footer>
                    </div>
                </div>
            `;
            const existing = document.getElementById('videoScanModal');
            if (existing) existing.remove();
            const div = document.createElement('div');
            div.id = 'videoScanModal';
            div.innerHTML = dialogHtml;
            document.body.appendChild(div);
        },

        closeScanVideoDialog: function() {
            const dialog = document.getElementById('videoScanModal');
            if (dialog) dialog.remove();
        },

        scanVideos: async function(mode) {
            JMedia.Settings.closeScanVideoDialog();
            const btn = document.getElementById('scanVideoLibrary');
            if (btn) btn.disabled = true;
            try {
                const res = await fetch(`/api/video/scan?mode=${mode}`, {method: "POST"});
                if (res.ok) {
                    if(window.showToast) window.showToast(`Video ${mode} scan started`, "success");
                } else {
                    if(window.showToast) window.showToast("Failed to start scan", "error");
                }
            } catch (e) {
                if(window.showToast) window.showToast("Error: " + e.message, "error");
            } finally {
                if (btn) btn.disabled = false;
            }
        },

        reloadMetadata: async function () {
            const profileId = getProfileId();
            const res = await fetch(`/api/settings/${profileId}/reloadMetadata`, {method: "POST"});
            if (res.ok) {
                if(window.showToast) window.showToast("Metadata reload started", "success");
            }
        },

        fixAlbums: async function () {
            const profileId = getProfileId();
            const res = await fetch(`/api/settings/${profileId}/fixAlbums`, {method: "POST"});
            if (res.ok) {
                if(window.showToast) window.showToast("Album fix started", "success");
            }
        },

        writeMetadata: async function () {
            if (!confirm("This will write all stored metadata to your music files. A backup will be created before each file is modified. Continue?")) {
                return;
            }
            const profileId = getProfileId();
            const res = await fetch(`/api/song/write-all-metadata`, {method: "POST"});
            if (res.ok) {
                const json = await res.json();
                if(window.showToast) window.showToast(json.message || "Metadata write completed", "success");
            } else {
                if(window.showToast) window.showToast("Failed to write metadata", "error");
            }
        },

        saveMusicLibraryPath: async function () {
            const profileId = getProfileId();
            const input = document.getElementById('musicLibraryPathInput');
            const path = input ? input.value : '';
            if (!path || path === '(not set)') {
                if(window.showToast) window.showToast("Please enter a valid path", "error");
                return;
            }
            try {
                const formData = new URLSearchParams();
                formData.append('musicLibraryPathInput', path);
                const res = await fetch(`/api/settings/${profileId}/music-library-path`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: formData
                });
                if (res.ok) {
                    if(window.showToast) window.showToast("Music library path saved", "success");
                } else {
                    const json = await res.json();
                    if(window.showToast) window.showToast(json.error || "Failed to save", "error");
                }
            } catch (e) {
                if(window.showToast) window.showToast("Error saving path", "error");
            }
        },

        saveVideoLibraryPath: async function () {
            const profileId = getProfileId();
            const input = document.getElementById('videoLibraryPathInput');
            const path = input ? input.value : '';
            if (!path || path === '(not set)') {
                if(window.showToast) window.showToast("Please enter a valid path", "error");
                return;
            }
            try {
                const formData = new URLSearchParams();
                formData.append('videoLibraryPathInput', path);
                const res = await fetch(`/api/settings/${profileId}/video-library-path`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: formData
                });
                if (res.ok) {
                    if(window.showToast) window.showToast("Video library path saved", "success");
                } else {
                    const json = await res.json();
                    if(window.showToast) window.showToast(json.error || "Failed to save", "error");
                }
            } catch (e) {
                if(window.showToast) window.showToast("Error saving path", "error");
            }
        },

        saveUiSettings: async function () {
            const select = document.getElementById('sidebarPositionSelect');
            if (!select) return;
            const position = select.value;
            const profileId = getProfileId();
            try {
                const res = await fetch(`/api/settings/${profileId}/sidebar-position`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ position: position })
                });
                if (res.ok) {
                    if(window.showToast) window.showToast('UI settings saved', 'success');
                    localStorage.setItem('sidebarPosition', position);
                    const layout = document.getElementById('standard-layout');
                    if (layout) {
                        if (position === 'right') layout.classList.add('sidebar-right');
                        else layout.classList.remove('sidebar-right');
                    }
                }
            } catch (e) {}
        },

        loadUiSettings: async function () {
            const profileId = getProfileId();
            try {
                const res = await fetch(`/api/settings/${profileId}/sidebar-position`);
                const json = await res.json();
                if (res.ok && json.data) {
                    const select = document.getElementById('sidebarPositionSelect');
                    if (select) select.value = json.data;
                    localStorage.setItem('sidebarPosition', json.data);
                    const layout = document.getElementById('standard-layout');
                    if (layout) {
                        if (json.data === 'right') layout.classList.add('sidebar-right');
                        else layout.classList.remove('sidebar-right');
                    }
                    return json.data;
                }
            } catch (e) {}
            return null;
        },

        setupLogWebSocket: function () {
            const logsPanel = document.getElementById("logsPanel");
            if (!logsPanel) return;
            if (window.logWebSocket && window.logWebSocket.readyState <= 1) return;
            if (!window.logWebSocketRetries) window.logWebSocketRetries = 0;
            if (window.logWebSocketRetries >= 3) {
                console.warn("[Logs] WebSocket connection failed after 3 attempts, disabling retries");
                return;
            }
            const profileId = getProfileId();
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
            socket.onopen = () => {
                console.log("[Logs] WebSocket connected");
                window.logWebSocketRetries = 0;
            };
            socket.onerror = () => {
                window.logWebSocketRetries++;
                console.warn(`[Logs] WebSocket error (attempt ${window.logWebSocketRetries}/3)`);
            };
            socket.onclose = () => {
                if (window.logWebSocketRetries < 3) {
                    setTimeout(window.setupLogWebSocket, 5000);
                }
            };
            window.logWebSocket = socket;
        },

        fixHtmxSettingsEndpoints: function() {
            const profileId = getProfileId();
            const buttons = [
                { id: 'saveMusicLibraryPathBtn', endpoint: 'music-library-path' },
                { id: 'saveVideoLibraryPathBtn', endpoint: 'video-library-path' }
            ];
            buttons.forEach(({ id, endpoint }) => {
                const btn = document.getElementById(id);
                if (btn) {
                    btn.setAttribute('hx-post', `/api/settings/${profileId}/${endpoint}`);
                }
            });
        },

        initSettingsView: async function() {
            console.log("Initializing Settings View");
            window.globalActiveProfileId = localStorage.getItem('activeProfileId') || '1';
            JMedia.Settings.fixHtmxSettingsEndpoints();
            await JMedia.Settings.checkAdminStatus();

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
            setupClick("fixAlbums", window.fixAlbums, "Fix missing album names?");
            setupClick("writeMetadata", window.writeMetadata);
            setupClick("deleteDuplicates", window.deleteDuplicates, "Delete duplicates?");
            setupClick("saveImportSettingsBtn", window.saveImportSettings);
            setupClick("savePlaybackSettingsBtn", window.savePlaybackSettings);
            setupClick("saveUiSettingsBtn", window.saveUiSettings);
            setupClick("createProfileBtn", window.createProfile);

            const refreshSessionsBtn = document.getElementById('refreshSessionsBtn');
            if (refreshSessionsBtn) {
                refreshSessionsBtn.onclick = () => {
                    console.log('[Settings] Refresh sessions clicked');
                    if (window.loadSessions) {
                        window.loadSessions();
                    } else {
                        console.error('[Settings] window.loadSessions not found!');
                    }
                };
            }

            ['choco', 'python', 'ffmpeg', 'spotdl', 'parakeet'].forEach(c => {
                const btn = document.getElementById(`install${c.charAt(0).toUpperCase() + c.slice(1)}Btn`);
                if (btn) btn.onclick = () => handleComponentAction(c, btn);
            });

            ['Music', 'Video'].forEach(t => {
                const btn = document.getElementById(`browse${t}FolderBtn`);
                if (btn) btn.onclick = () => JMedia.Settings.openFolderBrowser(t);
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

                    console.log('[Settings] Tab clicked:', target);

                    if (target === 'import-installation') JMedia.Settings.loadInstallationStatus();
                    if (target === 'user-management' && window.loadUsers) window.loadUsers();
                    if (target === 'session-management') {
                        if (window.loadSessions) {
                            requestAnimationFrame(() => {
                                requestAnimationFrame(() => {
                                    console.log('[Settings] Calling loadSessions after DOM ready');
                                    window.loadSessions();
                                });
                            });
                        } else {
                            console.error('[Settings] window.loadSessions is not defined!');
                        }
                    }
                    if (target === 'logs') JMedia.Settings.setupLogWebSocket();
                    if (target === 'sync-configuration') {
                        if (JMedia.Sync && JMedia.Sync.loadAll) {
                            JMedia.Sync.loadAll();
                        }
                    }
                    if (target === 'ai-subtitle-generator') {
                        if (window.loadAiSubtitleVideos) window.loadAiSubtitleVideos(0);
                        if (window.loadCompletedAiSubtitles) window.loadCompletedAiSubtitles(0);
                        if (window.resumeIfJobRunning) window.resumeIfJobRunning();
                    }
                };
            });

            JMedia.Settings.loadProfiles();
            JMedia.Settings.loadPlaybackSettings();
            JMedia.Settings.loadUiSettings();
            JMedia.Settings.loadAutoSkipSettings();
            JMedia.Settings.refreshSettingsUI();
            JMedia.Settings.setupLogWebSocket();
        },

        saveImportSettings: async function () {
            const profileId = getProfileId();
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
        },

        clearPlaybackHistory: async function () {
            const profileId = getProfileId();
            try {
                const resMusic = await fetch(`/api/settings/clearPlaybackHistory/${profileId}`, { method: "POST" });
                const resVideo = await fetch(`/api/video/clear-history`, { method: "POST" });
                if (resMusic.ok && resVideo.ok) {
                    if(window.showToast) window.showToast("History cleared", "success");
                }
            } catch (e) {}
        },

        loadPlaybackSettings: async function () {
            const profileId = getProfileId();
            try {
                const res = await fetch(`/api/music/playback/crossfade/${profileId}`);
                const json = await res.json();
                if (res.ok && json.data !== undefined) {
                    const input = document.getElementById('crossfadeDuration');
                    const val = document.getElementById('crossfadeValue');
                    if (input) { input.value = json.data; if (val) val.textContent = json.data; }
                }
            } catch (e) {}
        },

        savePlaybackSettings: async function () {
            const profileId = getProfileId();
            const input = document.getElementById('crossfadeDuration');
            const val = input ? parseInt(input.value) : 0;
            try {
                await fetch(`/api/music/playback/crossfade/${profileId}/${val}`, { method: 'POST' });
                if(window.showToast) window.showToast('Playback settings saved', 'success');
            } catch (e) {}
        },

        loadAutoSkipSettings: async function () {
            const profileId = getProfileId();
            try {
                const res = await fetch(`/api/settings/${profileId}`);
                const json = await res.json();
                if (res.ok && json.data) {
                    const d = json.data;
                    const setCheck = (id, val) => { const el = document.getElementById(id); if (el) el.checked = val === true; };
                    setCheck('autoSkipIntro', d.autoSkipIntro);
                    setCheck('autoSkipRecap', d.autoSkipRecap);
                    setCheck('autoSkipOutro', d.autoSkipOutro);
                }
            } catch (e) {}
        },

        saveAutoSkipSettings: async function () {
            const profileId = getProfileId();
            const data = {
                autoSkipIntro: document.getElementById('autoSkipIntro')?.checked || false,
                autoSkipRecap: document.getElementById('autoSkipRecap')?.checked || false,
                autoSkipOutro: document.getElementById('autoSkipOutro')?.checked || false
            };
            try {
                const res = await fetch(`/api/settings/${profileId}/auto-skip`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });
                if (res.ok && window.showToast) {
                    window.showToast('Auto-skip settings saved', 'success');
                }
            } catch (e) {
                if (window.showToast) window.showToast('Failed to save auto-skip settings', 'error');
            }
        },

        loadProfiles: async function () {
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
        },

        loadInstallationStatus: async function () {
            const profileId = getProfileId();
            try {
                const res = await fetch(`/api/settings/${profileId}/install-status`);
                const json = await res.json();
                const status = json.data || json;
                if (status) {
                    ['choco', 'python', 'ffmpeg', 'spotdl', 'parakeet'].forEach(c => {
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
        },

        refreshSettingsUI: async function () {
            const profileId = getProfileId();
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
            }
        },

        checkAdminStatus: async function() {
            try {
                const res = await fetch('/api/auth/is-admin');
                const json = await res.json();
                const isAdmin = json.data && json.data.isAdmin;
                document.querySelectorAll('.admin-only').forEach(el => {
                    el.style.display = isAdmin ? (el.classList.contains('nav-item') ? 'flex' : 'block') : 'none';
                });
            } catch (e) {}
        },

        openFolderBrowser: function(target) {
            window.currentBrowserTarget = target;
            const currentInput = document.getElementById(`${target.toLowerCase()}LibraryPathInput`);
            const initialPath = currentInput ? currentInput.value : '';
            document.getElementById('folderBrowserModal').classList.add('is-active');
            JMedia.Settings.loadFolders(initialPath === '(not set)' ? '' : initialPath);
        },

        closeFolderBrowser: function() {
            document.getElementById('folderBrowserModal').classList.remove('is-active');
        },

        loadFolders: async function(path) {
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
        },

        navigateUpFolder: function() {
            if (window._parentPath !== undefined && window._parentPath !== null) {
                JMedia.Settings.loadFolders(window._parentPath);
            }
        },

        confirmFolderSelection: function() {
            if (window.currentBrowserTarget && window.currentBrowserPath) {
                const input = document.getElementById(`${window.currentBrowserTarget.toLowerCase()}LibraryPathInput`);
                if (input) input.value = window.currentBrowserPath;
                if(window.showToast) window.showToast(`${window.currentBrowserTarget} folder selected`, 'success');
                JMedia.Settings.closeFolderBrowser();
            }
        },

        loadDirectories: async function() {
            const profileId = getProfileId();
            try {
                const res = await fetch(`/api/settings/${profileId}/directories`);
                const json = await res.json();
                if (res.ok && json.data) {
                    JMedia.Settings.renderDirectoryList(json.data);
                }
            } catch (e) { console.error("Failed to load directories", e); }
        },

        renderDirectoryList: function(dirs) {
            const musicContainer = document.getElementById('musicDirectoriesList');
            const videoContainer = document.getElementById('videoDirectoriesList');
            const musicDirs = dirs.filter(d => d.mediaType === 'MUSIC');
            const videoDirs = dirs.filter(d => d.mediaType === 'VIDEO');
            if (musicContainer) {
                musicContainer.innerHTML = musicDirs.map(dir => `
                    <div class="directory-item box mb-2 p-3" data-id="${dir.id}">
                        <div class="level is-mobile">
                            <div class="level-left">
                                <span>${dir.path}</span>
                            </div>
                            <div class="level-right">
                                <button class="button is-small is-danger" onclick="window.deleteDirectory(${dir.id})">Remove</button>
                            </div>
                        </div>
                        <div class="buttons mt-2">
                            <button class="button is-small is-primary" onclick="window.scanDirectory(${dir.id})">Scan</button>
                            <button class="button is-small is-info" onclick="window.reloadDirectoryMetadata(${dir.id})">Reload</button>
                            <button class="button is-small is-danger is-outlined" onclick="window.clearDirectorySongs(${dir.id})">Clear</button>
                        </div>
                    </div>
                `).join('') || '<p class="has-text-grey">No music directories added.</p>';
            }
            if (videoContainer) {
                videoContainer.innerHTML = videoDirs.map(dir => `
                    <div class="directory-item box mb-2 p-3" data-id="${dir.id}">
                        <div class="level is-mobile">
                            <div class="level-left">
                                <span>${dir.path}</span>
                            </div>
                            <div class="level-right">
                                <button class="button is-small is-danger" onclick="window.deleteDirectory(${dir.id})">Remove</button>
                            </div>
                        </div>
                        <div class="buttons mt-2">
                            <button class="button is-small is-primary" onclick="window.scanVideoDirectory(${dir.id})">Scan</button>
                            <button class="button is-small is-info" onclick="window.reloadVideoDirectoryMetadata(${dir.id})">Reload</button>
                            <button class="button is-small is-danger is-outlined" onclick="window.clearDirectoryVideos(${dir.id})">Clear</button>
                        </div>
                    </div>
                `).join('') || '<p class="has-text-grey">No video directories added.</p>';
            }
        },

        addDirectory: async function(type) {
            const path = prompt(`Enter ${type} directory path:`);
            if (!path) return;
            const profileId = getProfileId();
            try {
                const res = await fetch(`/api/settings/${profileId}/directories`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ path: path, type: type })
                });
                const json = await res.json();
                if (res.ok) {
                    if(window.showToast) window.showToast("Directory added", "success");
                    JMedia.Settings.loadDirectories();
                } else {
                    if(window.showToast) window.showToast(json.error || "Failed to add directory", "error");
                }
            } catch (e) {
                if(window.showToast) window.showToast("Error adding directory", "error");
            }
        },

        deleteDirectory: async function(id) {
            if (!confirm("Remove this directory?")) return;
            const profileId = getProfileId();
            try {
                const res = await fetch(`/api/settings/${profileId}/directories/${id}`, { method: 'DELETE' });
                if (res.ok) {
                    if(window.showToast) window.showToast("Directory removed", "success");
                    JMedia.Settings.loadDirectories();
                }
            } catch (e) { console.error(e); }
        },

        scanDirectory: async function(id) {
            const profileId = getProfileId();
            const res = await fetch(`/api/settings/${profileId}/scanLibrary?directoryId=${id}`, { method: 'POST' });
            if (res.ok && window.showToast) window.showToast("Scan started", "success");
        },

        reloadDirectoryMetadata: async function(id) {
            const profileId = getProfileId();
            const res = await fetch(`/api/settings/${profileId}/reloadMetadata?directoryId=${id}`, { method: 'POST' });
            if (res.ok && window.showToast) window.showToast("Metadata reload started", "success");
        },

        clearDirectorySongs: async function(id) {
            if (!confirm("Clear songs from this directory?")) return;
            const profileId = getProfileId();
            const res = await fetch(`/api/settings/${profileId}/clearSongs?directoryId=${id}`, { method: 'POST' });
            if (res.ok && window.showToast) window.showToast("Songs cleared", "success");
        },

        scanVideoDirectory: async function(id) {
            const profileId = getProfileId();
            const res = await fetch(`/api/settings/${profileId}/scanVideo?directoryId=${id}`, { method: 'POST' });
            if (res.ok && window.showToast) window.showToast("Video scan started", "success");
        },

        reloadVideoDirectoryMetadata: async function(id) {
            if(window.showToast) window.showToast("Video metadata reload not yet implemented", "info");
        },

        clearDirectoryVideos: async function(id) {
            if(window.showToast) window.showToast("Video clear not yet implemented", "info");
        }
    };

    // Backward-compatible window aliases
    window.resetLibrary = JMedia.Settings.resetLibrary;
    window.scanLibrary = JMedia.Settings.scanLibrary;
    window.clearLogs = JMedia.Settings.clearLogs;
    window.clearSongsDB = JMedia.Settings.clearSongsDB;
    window.showScanVideoDialog = JMedia.Settings.showScanVideoDialog;
    window.closeScanVideoDialog = JMedia.Settings.closeScanVideoDialog;
    window.scanVideos = JMedia.Settings.scanVideos;
    window.reloadMetadata = JMedia.Settings.reloadMetadata;
    window.fixAlbums = JMedia.Settings.fixAlbums;
    window.writeMetadata = JMedia.Settings.writeMetadata;
    window.saveMusicLibraryPath = JMedia.Settings.saveMusicLibraryPath;
    window.saveVideoLibraryPath = JMedia.Settings.saveVideoLibraryPath;
    window.saveUiSettings = JMedia.Settings.saveUiSettings;
    window.loadUiSettings = JMedia.Settings.loadUiSettings;
    window.setupLogWebSocket = JMedia.Settings.setupLogWebSocket;
    window.fixHtmxSettingsEndpoints = JMedia.Settings.fixHtmxSettingsEndpoints;
    window.initSettingsView = JMedia.Settings.initSettingsView;
    window.saveImportSettings = JMedia.Settings.saveImportSettings;
    window.clearPlaybackHistory = JMedia.Settings.clearPlaybackHistory;
    window.loadPlaybackSettings = JMedia.Settings.loadPlaybackSettings;
    window.savePlaybackSettings = JMedia.Settings.savePlaybackSettings;
    window.loadAutoSkipSettings = JMedia.Settings.loadAutoSkipSettings;
    window.saveAutoSkipSettings = JMedia.Settings.saveAutoSkipSettings;
    window.loadProfiles = JMedia.Settings.loadProfiles;
    window.loadInstallationStatus = JMedia.Settings.loadInstallationStatus;
    window.refreshSettingsUI = JMedia.Settings.refreshSettingsUI;
    window.checkAdminStatus = JMedia.Settings.checkAdminStatus;
    window.openFolderBrowser = JMedia.Settings.openFolderBrowser;
    window.closeFolderBrowser = JMedia.Settings.closeFolderBrowser;
    window.loadFolders = JMedia.Settings.loadFolders;
    window.navigateUpFolder = JMedia.Settings.navigateUpFolder;
    window.confirmFolderSelection = JMedia.Settings.confirmFolderSelection;
    window.loadDirectories = JMedia.Settings.loadDirectories;
    window.renderDirectoryList = JMedia.Settings.renderDirectoryList;
    window.addDirectory = JMedia.Settings.addDirectory;
    window.deleteDirectory = JMedia.Settings.deleteDirectory;
    window.scanDirectory = JMedia.Settings.scanDirectory;
    window.reloadDirectoryMetadata = JMedia.Settings.reloadDirectoryMetadata;
    window.clearDirectorySongs = JMedia.Settings.clearDirectorySongs;
    window.scanVideoDirectory = JMedia.Settings.scanVideoDirectory;
    window.reloadVideoDirectoryMetadata = JMedia.Settings.reloadVideoDirectoryMetadata;
    window.clearDirectoryVideos = JMedia.Settings.clearDirectoryVideos;

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', JMedia.Settings.loadDirectories);
    } else {
        JMedia.Settings.loadDirectories();
    }

    document.addEventListener('DOMContentLoaded', function() {
        const scanLibBtn = document.getElementById('scanLibrary');
        if (scanLibBtn) scanLibBtn.addEventListener('click', () => JMedia.Settings.scanLibrary());
        const reloadMetaBtn = document.getElementById('reloadMetadata');
        if (reloadMetaBtn) reloadMetaBtn.addEventListener('click', () => JMedia.Settings.reloadMetadata());
        const fixAlbumsBtn = document.getElementById('fixAlbums');
        if (fixAlbumsBtn) fixAlbumsBtn.addEventListener('click', () => {
            if (confirm('Fix missing album names?')) JMedia.Settings.fixAlbums();
        });
        const clearSongsBtn = document.getElementById('clearSongs');
        if (clearSongsBtn) clearSongsBtn.addEventListener('click', () => JMedia.Settings.clearSongsDB());
    });

})(window);
