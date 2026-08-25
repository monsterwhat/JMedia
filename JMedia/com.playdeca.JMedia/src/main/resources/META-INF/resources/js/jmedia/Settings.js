(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    function getProfileId() {
        return window.globalActiveProfileId || localStorage.getItem('activeProfileId');
    }

    window.componentStates = window.componentStates || { choco: false, python: false, ffmpeg: false, spotdl: false, ytdlp: false, parakeet: false, tesseract: false, deno: false };

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
        if (action === 'uninstall' && comp === 'choco') {
            // There is no uninstall for the system package manager (apt/brew/choco)
            if(window.showToast) window.showToast('The system package manager cannot be removed from here', 'warning');
            return;
        }
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

    async function handleUpdateAction(comp, btn) {
        const profileId = getProfileId();
        btn.disabled = true;
        btn.classList.add('is-loading');
        try {
            const res = await fetch(`/api/import/update/${comp}/${profileId}`, { method: 'POST' });
            if (res.status === 409) { if(window.showToast) window.showToast('Another update is already running', 'warning'); }
            else if (res.ok) {
                if(window.showToast) window.showToast(`${comp} updated successfully`, 'success');
                if (window.loadInstallationStatus) window.loadInstallationStatus();   // PRIMARY refresh — do not rely on WS sentinels alone
            } else {
                let msg = `Failed to update ${comp}`;
                try { const j = await res.json(); if (j && j.message) msg = j.message; } catch (e) {}
                if(window.showToast) window.showToast(msg, 'error');
            }
        } catch (e) {
            console.error(`Error updating ${comp}:`, e);
            if(window.showToast) window.showToast(`Error updating ${comp}`, 'error');
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
            ['CHOCO', 'PYTHON', 'FFMPEG', 'SPOTDL', 'YTDLP', 'PARAKEET', 'TESSERACT', 'DENO'].forEach(c => {
                if (msg.includes(`[${c}_INSTALLATION_FINISHED]`) || msg.includes(`[${c}_UNINSTALLATION_FINISHED]`)) window.loadInstallationStatus();
                if (msg.includes(`[${c}_UPDATE_FINISHED]`)) window.loadInstallationStatus();
            });
            if (msg.includes('[DENO_INSTALL_FINISHED]') || msg.includes('[DENO_UNINSTALLATION_FINISHED]')) window.loadInstallationStatus();
            if (msg.includes('[DENO_UPDATE_FINISHED]')) window.loadInstallationStatus();
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
                            <hr>
                            <p class="mb-4"><strong>Targeted Scans</strong> - Scan and clean a specific media type:</p>
                            <div class="content">
                                <p><strong>Re-scan TV Shows</strong> - Scans all video files, prunes missing episodes, finds new ones</p>
                                <p class="has-text-grey is-size-7">Removes DB entries for episodes whose files no longer exist</p>
                            </div>
                            <div class="content mt-4">
                                <p><strong>Re-scan Movies</strong> - Scans all video files, prunes missing movies, finds new ones</p>
                                <p class="has-text-grey is-size-7">Checks file existence - removes missing, adds newly detected movies</p>
                            </div>
                        </section>
                        <footer class="modal-card-foot" style="flex-wrap: wrap; gap: 0.5rem;">
                            <button class="button is-info" onclick="window.scanVideos('update')">Update Scan</button>
                            <button class="button is-warning" onclick="window.scanVideos('full')">Full Scan</button>
                            <button class="button is-success" onclick="window.scanTvShows()">Re-scan TV Shows</button>
                            <button class="button is-danger" onclick="window.scanMovies()">Re-scan Movies</button>
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

        scanTvShows: async function() {
            JMedia.Settings.closeScanVideoDialog();
            const btn = document.getElementById('scanVideoLibrary');
            if (btn) btn.disabled = true;
            try {
                const res = await fetch('/api/video/scan/tvshows', {method: "POST"});
                if (res.ok) {
                    if(window.showToast) window.showToast("TV shows scan started - pruning missing, finding new", "success");
                } else {
                    if(window.showToast) window.showToast("Failed to start TV shows scan", "error");
                }
            } catch (e) {
                if(window.showToast) window.showToast("Error: " + e.message, "error");
            } finally {
                if (btn) btn.disabled = false;
            }
        },

        scanMovies: async function() {
            JMedia.Settings.closeScanVideoDialog();
            const btn = document.getElementById('scanVideoLibrary');
            if (btn) btn.disabled = true;
            try {
                const res = await fetch('/api/video/scan/movies', {method: "POST"});
                if (res.ok) {
                    if(window.showToast) window.showToast("Movies scan started - pruning missing, finding new", "success");
                } else {
                    if(window.showToast) window.showToast("Failed to start movies scan", "error");
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
            window.globalActiveProfileId = localStorage.getItem('activeProfileId');
            JMedia.Settings.fixHtmxSettingsEndpoints();
            await JMedia.Settings.checkAdminStatus();

            const setupClick = (id, fn, msg) => {
                const el = document.getElementById(id);
                if (el) el.onclick = () => msg ? (confirm(msg) && fn()) : fn();
            };

            setupClick("resetLibrary", window.resetLibrary, "Reset library path?");
            setupClick("scanLibrary", window.scanLibrary);
            setupClick("clearSongs", window.clearSongsDB, "Clear songs?");
            setupClick("clearPlaybackHistory", window.clearPlaybackHistory, "Clear all playback history?");
            setupClick("reloadMetadata", window.reloadMetadata, "Reload metadata?");
            setupClick("fixAlbums", window.fixAlbums, "Fix missing album names?");
            setupClick("writeMetadata", window.writeMetadata);
            setupClick("deleteDuplicates", window.deleteDuplicates, "Delete duplicates?");
            setupClick("saveImportSettingsBtn", window.saveImportSettings);
            setupClick("savePlaybackSettingsBtn", window.savePlaybackSettings);
            setupClick("saveUiSettingsBtn", window.saveUiSettings);
            setupClick("createProfileBtn", window.createProfile);
            setupClick("saveOpenSubtitlesBtn", window.saveOpenSubtitlesSettings);

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

            const cleanupSessionsBtn = document.getElementById('cleanupSessionsBtn');
            if (cleanupSessionsBtn) {
                cleanupSessionsBtn.onclick = () => {
                    console.log('[Settings] Cleanup sessions clicked');
                    if (window.cleanupSessions) {
                        window.cleanupSessions();
                    } else {
                        console.error('[Settings] window.cleanupSessions not found!');
                    }
                };
            }

            ['choco', 'python', 'ffmpeg', 'spotdl', 'ytdlp', 'parakeet', 'tesseract', 'deno'].forEach(c => {
                const btn = document.getElementById(`install${c.charAt(0).toUpperCase() + c.slice(1)}Btn`);
                if (btn) btn.onclick = () => handleComponentAction(c, btn);
                const ubtn = document.getElementById(`update${c.charAt(0).toUpperCase() + c.slice(1)}Btn`);
                if (ubtn) ubtn.onclick = () => handleUpdateAction(c, ubtn);
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

                    if (window.Breadcrumbs) {
                        var tabNames = {
                            'library-management': 'Library', 'import-installation': 'Import Setup',
                            'playlist-creator': 'Playlists',
                            'user-management': 'Users', 'session-management': 'Sessions',
                            'ai-subtitle-generator': 'AI Subtitles', 'sync-configuration': 'Sync'
                        };
                        window.Breadcrumbs.set([
                            { label: 'Settings', navigate: function() { window.app.navigate('/settings'); } },
                            tabNames[target] || target
                        ]);
                    }

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

        saveMaxConcurrentTranscodes: async function () {
            const profileId = getProfileId();
            const value = readTriState('maxConcurrentTranscodes');
            try {
                const res = await fetch(`/api/settings/${profileId}/max-concurrent-transcodes`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ maxConcurrentTranscodes: value })
                });
                if (res.ok && window.showToast) {
                    window.showToast('Max concurrent transcodes updated (applied immediately)', 'success');
                } else if (window.showToast) {
                    window.showToast('Failed to save max concurrent transcodes', 'error');
                }
            } catch (e) {}
        },

        saveMaxCompleteCacheFiles: async function () {
            const profileId = getProfileId();
            const value = parseInt(document.getElementById('maxCompleteCacheFilesInput').value) || 15;
            try {
                const res = await fetch(`/api/settings/${profileId}/max-complete-cache-files`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ maxCompleteCacheFiles: value })
                });
                if (res.ok && window.showToast) {
                    window.showToast('Max complete cache files updated', 'success');
                } else if (window.showToast) {
                    window.showToast('Failed to save max complete cache files', 'error');
                }
            } catch (e) {}
        },

        saveHardwareAcceleration: async function () {
            const profileId = getProfileId();
            try {
                const res = await fetch(`/api/settings/${profileId}/hardware-acceleration`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ enabled: document.getElementById('hardwareAccelerationToggle').checked })
                });
                if (res.ok && window.showToast) {
                    window.showToast('Hardware acceleration updated', 'success');
                } else if (window.showToast) {
                    window.showToast('Failed to save hardware acceleration', 'error');
                }
            } catch (e) {}
        },

        saveSystemPerformance: async function () {
            const profileId = getProfileId();
            const payload = {
                audioAnalysisThreads: readTriState('audioAnalysisThreads'),
                djEnrichmentAnalysisThreads: readTriState('djEnrichmentAnalysisThreads'),
                djEnrichmentMetadataThreads: readTriState('djEnrichmentMetadataThreads'),
                musicScanThreads: readTriState('musicScanThreads'),
                videoScanThreads: readTriState('videoScanThreads'),
                streamCheckThreads: readTriState('streamCheckThreads'),
                videoEnrichmentThreads: readTriState('videoEnrichmentThreads'),
                maxConcurrentTranscodes: readTriState('maxConcurrentTranscodes')
            };
            const batchRaw = parseInt(document.getElementById('analysisWorkerBatchSizeInput')?.value, 10);
            if (!isNaN(batchRaw)) payload.analysisWorkerBatchSize = batchRaw;
            const status = document.getElementById('systemPerformanceStatus');
            try {
                const res = await fetch(`/api/settings/${profileId}/system-performance`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                const json = await res.json();
                if (res.ok && json.success !== false) {
                    if (status) status.textContent = 'Applied.';
                    if (window.showToast) window.showToast('Thread pool settings applied live', 'success');
                } else {
                    const msg = json.error || 'Failed to apply thread pool settings';
                    if (status) status.textContent = msg;
                    if (window.showToast) window.showToast(msg, 'error');
                }
            } catch (e) {
                if (status) status.textContent = 'Connection error';
                if (window.showToast) window.showToast('Error applying thread pool settings', 'error');
            }
        },

        saveOpenSubtitlesSettings: async function () {
            const apiKey = document.getElementById('openSubtitlesApiKeyInput')?.value?.trim() || '';
            const username = document.getElementById('openSubtitlesUsernameInput')?.value?.trim() || '';
            const password = document.getElementById('openSubtitlesPasswordInput')?.value?.trim() || '';
            try {
                const body = new URLSearchParams({
                    openSubtitlesApiKey: apiKey,
                    openSubtitlesUsername: username,
                    openSubtitlesPassword: password
                });
                const res = await fetch('/api/settings/opensubtitles', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: body
                });
                const json = await res.json();
                if (res.ok && json.success !== false) {
                    if(window.showToast) window.showToast('OpenSubtitles credentials saved', 'success');
                } else {
                    if(window.showToast) window.showToast(json.error || 'Failed to save OpenSubtitles credentials', 'error');
                }
            } catch (e) {
                if(window.showToast) window.showToast('Error saving OpenSubtitles credentials', 'error');
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
                    ['choco', 'python', 'ffmpeg', 'spotdl', 'ytdlp', 'parakeet', 'tesseract'].forEach(c => {
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
                        const ub = document.getElementById(`update${c.charAt(0).toUpperCase() + c.slice(1)}Btn`);
                        if (ub) ub.hidden = !isInst;
                    });
                    const chocoLabel = document.getElementById('chocoLabel');
                    if (chocoLabel) chocoLabel.textContent = (status.osName || '').toLowerCase().includes('win') ? 'Chocolatey' : 'Package Manager';
                    const denoInst = status.denoInstalled === true;
                    window.componentStates.deno = denoInst;
                    const denoBtn = document.getElementById('installDenoBtn');
                    const denoStat = document.getElementById('denoStatus');
                    if (denoBtn) {
                        denoBtn.disabled = status.denoInstalling === true;
                        denoBtn.classList.remove('is-loading');
                        if (status.denoInstalling === true) {
                            denoBtn.innerHTML = `<i class="pi pi-spin pi-spinner mr-1"></i>Installing...`;
                            denoBtn.className = 'button is-small is-rounded is-warning';
                        } else {
                            denoBtn.innerHTML = denoInst ? `<i class="pi pi-trash mr-1"></i>Remove` : `<i class="pi pi-download mr-1"></i>Install`;
                            denoBtn.className = `button is-small is-rounded ${denoInst ? 'is-danger' : 'is-success'}`;
                        }
                    }
                    if (denoStat) {
                        if (status.denoInstalling === true) {
                            const pct = (typeof status.denoInstallProgress === 'number') ? ` (${status.denoInstallProgress}%)` : '';
                            denoStat.textContent = `Installing${pct}...`;
                            denoStat.className = 'help has-text-warning';
                        } else {
                            denoStat.textContent = status.denoMessage || (denoInst ? 'Installed' : 'Not installed');
                            denoStat.className = `help ${denoInst ? 'has-text-success' : 'has-text-danger'}`;
                        }
                    }
                    const denoUpdateBtn = document.getElementById('updateDenoBtn');
                    if (denoUpdateBtn) denoUpdateBtn.hidden = !denoInst || status.denoInstalling === true;
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
                setVal("openSubtitlesApiKeyInput", d.openSubtitlesApiKey);
                setVal("openSubtitlesUsernameInput", d.openSubtitlesUsername);
                setVal("openSubtitlesPasswordInput", d.openSubtitlesPassword);
                setVal("outputFormat", d.outputFormat);
                setVal("downloadThreads", d.downloadThreads);
                setVal("searchThreads", d.searchThreads);
                ['audioAnalysisThreads', 'djEnrichmentAnalysisThreads', 'djEnrichmentMetadataThreads',
                 'musicScanThreads', 'videoScanThreads', 'streamCheckThreads', 'videoEnrichmentThreads']
                    .forEach(f => applyTriState(f, d[f]));
                applyTriState('maxConcurrentTranscodes', d.maxConcurrentTranscodes);
                setVal("analysisWorkerBatchSizeInput", d.analysisWorkerBatchSize);
                setVal("maxCompleteCacheFilesInput", d.maxCompleteCacheFiles);
                const hwEl = document.getElementById('hardwareAccelerationToggle');
                if (hwEl) hwEl.checked = d.hardwareAccelerationEnabled === true;
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

    // Tri-state pool settings: -1 = off, 0 = auto, N = manual worker count
    function applyTriState(field, value) {
        const modeEl = document.getElementById(field + 'Mode');
        const inputEl = document.getElementById(field + 'Input');
        if (!modeEl || !inputEl) return;
        const v = (value === null || value === undefined || isNaN(Number(value))) ? 0 : Number(value);
        if (v < 0) {
            modeEl.value = 'off';
            inputEl.value = '';
        } else if (v === 0) {
            modeEl.value = 'auto';
            inputEl.value = '';
        } else {
            modeEl.value = 'manual';
            inputEl.value = String(v);
        }
        inputEl.disabled = modeEl.value !== 'manual';
    }

    function readTriState(field) {
        const modeEl = document.getElementById(field + 'Mode');
        const inputEl = document.getElementById(field + 'Input');
        if (!modeEl || !inputEl) return 0;
        if (modeEl.value === 'off') return -1;
        if (modeEl.value === 'auto') return 0;
        const n = parseInt(inputEl.value, 10);
        return isNaN(n) ? 0 : n;
    }

    // Backward-compatible window aliases
    window.resetLibrary = JMedia.Settings.resetLibrary;
    window.scanLibrary = JMedia.Settings.scanLibrary;
    window.clearSongsDB = JMedia.Settings.clearSongsDB;
    window.showScanVideoDialog = JMedia.Settings.showScanVideoDialog;
    window.closeScanVideoDialog = JMedia.Settings.closeScanVideoDialog;
    window.scanVideos = JMedia.Settings.scanVideos;
    window.scanTvShows = JMedia.Settings.scanTvShows;
    window.scanMovies = JMedia.Settings.scanMovies;
    window.reloadMetadata = JMedia.Settings.reloadMetadata;
    window.fixAlbums = JMedia.Settings.fixAlbums;
    window.writeMetadata = JMedia.Settings.writeMetadata;
    window.saveMusicLibraryPath = JMedia.Settings.saveMusicLibraryPath;
    window.saveVideoLibraryPath = JMedia.Settings.saveVideoLibraryPath;
    window.saveUiSettings = JMedia.Settings.saveUiSettings;
    window.loadUiSettings = JMedia.Settings.loadUiSettings;
    window.fixHtmxSettingsEndpoints = JMedia.Settings.fixHtmxSettingsEndpoints;
    window.initSettingsView = JMedia.Settings.initSettingsView;
    window.saveImportSettings = JMedia.Settings.saveImportSettings;
    window.clearPlaybackHistory = JMedia.Settings.clearPlaybackHistory;
    window.loadPlaybackSettings = JMedia.Settings.loadPlaybackSettings;
    window.savePlaybackSettings = JMedia.Settings.savePlaybackSettings;
    window.loadAutoSkipSettings = JMedia.Settings.loadAutoSkipSettings;
    window.saveAutoSkipSettings = JMedia.Settings.saveAutoSkipSettings;
    window.saveMaxConcurrentTranscodes = JMedia.Settings.saveMaxConcurrentTranscodes;
    window.saveMaxCompleteCacheFiles = JMedia.Settings.saveMaxCompleteCacheFiles;
    window.saveHardwareAcceleration = JMedia.Settings.saveHardwareAcceleration;
    window.saveSystemPerformance = JMedia.Settings.saveSystemPerformance;
    window.saveOpenSubtitlesSettings = JMedia.Settings.saveOpenSubtitlesSettings;
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
