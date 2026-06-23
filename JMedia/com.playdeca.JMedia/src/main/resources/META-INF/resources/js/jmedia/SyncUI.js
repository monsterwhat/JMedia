(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    function escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    JMedia.Sync = {
        loadAll: async function() {
            await Promise.all([
                JMedia.Sync.loadSyncSettings(),
                JMedia.Sync.loadServers(),
                JMedia.Sync.loadStatus(),
                JMedia.Sync.loadLogs()
            ]);
        },

        loadSyncSettings: async function() {
            const statusEl = document.getElementById('syncSettingsStatus');
            try {
                const res = await fetch('/api/sync/settings');
                const json = await res.json();
                if (res.ok && json.data) {
                    const s = json.data;
                    const setChecked = (id, val) => { const el = document.getElementById(id); if (el) el.checked = val === true; };
                    setChecked('syncEnabled', s.syncEnabled);
                    setChecked('syncMusicEnabled', s.syncMusicEnabled);
                    setChecked('syncVideoEnabled', s.syncVideoEnabled);
                    setChecked('syncTimelinesEnabled', s.syncTimelinesEnabled);
                    setChecked('syncPlaylistsEnabled', s.syncPlaylistsEnabled);
                    const scheduleEl = document.getElementById('syncSchedule');
                    if (scheduleEl && s.syncSchedule) scheduleEl.value = s.syncSchedule;
                    const apiKeyEl = document.getElementById('syncApiKey');
                    if (apiKeyEl && s.syncApiKey) apiKeyEl.value = s.syncApiKey;
                }
            } catch (e) {
                console.error('[Sync] Error loading settings:', e);
                if (statusEl) {
                    statusEl.textContent = 'Failed to load settings';
                    statusEl.className = 'ml-3 help has-text-danger';
                    statusEl.style.display = '';
                }
            }
        },

        saveSyncSettings: async function() {
            const statusEl = document.getElementById('syncSettingsStatus');
            const btn = document.getElementById('syncSaveSettingsBtn');
            if (!btn) return;
            btn.disabled = true;
            btn.classList.add('is-loading');
            try {
                const data = {
                    syncEnabled: document.getElementById('syncEnabled')?.checked || false,
                    syncSchedule: document.getElementById('syncSchedule')?.value || 'manual',
                    syncMusicEnabled: document.getElementById('syncMusicEnabled')?.checked || false,
                    syncVideoEnabled: document.getElementById('syncVideoEnabled')?.checked || false,
                    syncTimelinesEnabled: document.getElementById('syncTimelinesEnabled')?.checked || false,
                    syncPlaylistsEnabled: document.getElementById('syncPlaylistsEnabled')?.checked || false,
                    syncApiKey: document.getElementById('syncApiKey')?.value || ''
                };
                const res = await fetch('/api/sync/settings', {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });
                const json = await res.json();
                if (res.ok) {
                    if (statusEl) {
                        statusEl.textContent = 'Settings saved successfully';
                        statusEl.className = 'ml-3 help has-text-success';
                        statusEl.style.display = '';
                        setTimeout(() => { statusEl.style.display = 'none'; }, 3000);
                    }
                    if (window.showToast) window.showToast('Sync settings saved', 'success');
                } else {
                    if (statusEl) {
                        statusEl.textContent = json.error || 'Failed to save settings';
                        statusEl.className = 'ml-3 help has-text-danger';
                        statusEl.style.display = '';
                    }
                }
            } catch (e) {
                console.error('[Sync] Error saving settings:', e);
                if (statusEl) {
                    statusEl.textContent = 'Error saving settings';
                    statusEl.className = 'ml-3 help has-text-danger';
                    statusEl.style.display = '';
                }
            } finally {
                btn.disabled = false;
                btn.classList.remove('is-loading');
            }
        },

        loadServers: async function() {
            const container = document.getElementById('syncServersList');
            const loading = document.getElementById('syncServersLoading');
            const error = document.getElementById('syncServersError');
            if (!container) return;
            try {
                if (loading) loading.style.display = 'block';
                container.innerHTML = '';
                if (error) error.style.display = 'none';
                const res = await fetch('/api/sync/servers');
                const json = await res.json();
                if (loading) loading.style.display = 'none';
                if (!res.ok) {
                    if (error) {
                        error.textContent = json.error || 'Failed to load servers';
                        error.style.display = 'block';
                    }
                    return;
                }
                const servers = json.data || [];
                if (servers.length === 0) {
                    container.innerHTML = '<div class="has-text-centered p-4 has-text-grey">No servers configured. Add a server to get started.</div>';
                    return;
                }
                container.innerHTML = servers.map(s => `
                    <div class="card mb-2" style="background: rgba(255,255,255,0.05); border: 1px solid rgba(255,255,255,0.1);">
                        <div class="card-content p-3">
                            <div class="level is-mobile">
                                <div class="level-left" style="flex-wrap: wrap; gap: 4px;">
                                    <span class="has-text-weight-semibold">${escapeHtml(s.name)}</span>
                                    <span class="tag ${s.enabled ? 'is-success' : 'is-dark'}">${s.enabled ? 'Enabled' : 'Disabled'}</span>
                                    <span class="tag is-info is-light">${escapeHtml(s.url)}</span>
                                </div>
                                <div class="level-right">
                                    <div class="buttons are-small">
                                        <button class="button is-small is-info is-light" onclick="JMedia.Sync.testServerConnection(${s.id})" title="Test Connection"><i class="pi pi-plug"></i></button>
                                        <button class="button is-small is-warning is-light" onclick="JMedia.Sync.toggleServer(${s.id}, ${!s.enabled})" title="${s.enabled ? 'Disable' : 'Enable'}"><i class="pi pi-${s.enabled ? 'pause' : 'play'}"></i></button>
                                        <button class="button is-small is-danger" onclick="JMedia.Sync.deleteServer(${s.id})" title="Remove"><i class="pi pi-trash"></i></button>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                `).join('');
            } catch (e) {
                console.error('[Sync] Error loading servers:', e);
                if (loading) loading.style.display = 'none';
                if (error) {
                    error.textContent = 'Connection error loading servers';
                    error.style.display = 'block';
                }
            }
        },

        showAddServerDialog: function() {
            const existing = document.getElementById('syncServerModal');
            if (existing) existing.remove();
            const div = document.createElement('div');
            div.id = 'syncServerModal';
            div.className = 'modal is-active';
            div.innerHTML = `
                <div class="modal-background" onclick="JMedia.Sync.closeAddServerDialog()"></div>
                <div class="modal-card glass-modal">
                    <header class="modal-card-head">
                        <p class="modal-card-title">Add Sync Server</p>
                        <button class="delete" aria-label="close" onclick="JMedia.Sync.closeAddServerDialog()"></button>
                    </header>
                    <section class="modal-card-body">
                        <div id="syncServerModalError" class="notification is-danger is-light" style="display: none;"></div>
                        <div class="field">
                            <label class="label">Name</label>
                            <div class="control">
                                <input id="syncServerName" class="input is-small" type="text" placeholder="My Server">
                            </div>
                        </div>
                        <div class="field">
                            <label class="label">URL</label>
                            <div class="control">
                                <input id="syncServerUrl" class="input is-small" type="text" placeholder="http://192.168.1.100:8080">
                            </div>
                            <p class="help">The full URL of the remote JMedia instance.</p>
                        </div>
                        <div class="field">
                            <label class="label">API Key</label>
                            <div class="control">
                                <input id="syncServerApiKey" class="input is-small" type="password" placeholder="API key for authentication">
                            </div>
                        </div>
                        <div class="field">
                            <label class="checkbox">
                                <input type="checkbox" id="syncServerEnabled" checked> Enable after adding
                            </label>
                        </div>
                    </section>
                    <footer class="modal-card-foot" style="justify-content: flex-end;">
                        <button id="syncServerTestBtn" class="button is-info is-small" onclick="JMedia.Sync.testNewConnection()"><i class="pi pi-plug mr-1"></i> Test Connection</button>
                        <button id="syncServerSaveBtn" class="button is-success is-small" onclick="JMedia.Sync.saveNewServer()"><i class="pi pi-save mr-1"></i> Add Server</button>
                        <button class="button is-small" onclick="JMedia.Sync.closeAddServerDialog()">Cancel</button>
                    </footer>
                </div>
            `;
            document.body.appendChild(div);
        },

        closeAddServerDialog: function() {
            const modal = document.getElementById('syncServerModal');
            if (modal) modal.remove();
        },

        testNewConnection: async function() {
            const btn = document.getElementById('syncServerTestBtn');
            const errorEl = document.getElementById('syncServerModalError');
            const url = document.getElementById('syncServerUrl')?.value?.trim();
            const apiKey = document.getElementById('syncServerApiKey')?.value?.trim();
            if (!url) {
                if (errorEl) { errorEl.textContent = 'URL is required'; errorEl.style.display = 'block'; }
                return;
            }
            if (!apiKey) {
                if (errorEl) { errorEl.textContent = 'API key is required'; errorEl.style.display = 'block'; }
                return;
            }
            if (errorEl) errorEl.style.display = 'none';
            btn.disabled = true;
            btn.classList.add('is-loading');
            try {
                const res = await fetch('/api/sync/servers/test-connection', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ url, apiKey })
                });
                const json = await res.json();
                if (json.data && json.data.reachable) {
                    if (window.showToast) window.showToast('Connection successful!', 'success');
                    btn.classList.remove('is-loading');
                    btn.innerHTML = '<i class="pi pi-check mr-1"></i> Connected';
                    btn.className = 'button is-small is-success';
                } else {
                    if (errorEl) { errorEl.textContent = json.data?.message || 'Server unreachable'; errorEl.style.display = 'block'; }
                }
            } catch (e) {
                if (errorEl) { errorEl.textContent = 'Connection error: ' + e.message; errorEl.style.display = 'block'; }
            } finally {
                btn.disabled = false;
                btn.classList.remove('is-loading');
            }
        },

        saveNewServer: async function() {
            const btn = document.getElementById('syncServerSaveBtn');
            const errorEl = document.getElementById('syncServerModalError');
            const name = document.getElementById('syncServerName')?.value?.trim();
            const url = document.getElementById('syncServerUrl')?.value?.trim();
            const apiKey = document.getElementById('syncServerApiKey')?.value?.trim();
            const enabled = document.getElementById('syncServerEnabled')?.checked ?? true;
            if (errorEl) errorEl.style.display = 'none';
            if (!name) { if (errorEl) { errorEl.textContent = 'Name is required'; errorEl.style.display = 'block'; } return; }
            if (!url) { if (errorEl) { errorEl.textContent = 'URL is required'; errorEl.style.display = 'block'; } return; }
            if (!apiKey) { if (errorEl) { errorEl.textContent = 'API key is required'; errorEl.style.display = 'block'; } return; }
            btn.disabled = true;
            btn.classList.add('is-loading');
            try {
                const res = await fetch('/api/sync/servers', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ name, url, apiKey, enabled })
                });
                const json = await res.json();
                if (res.ok) {
                    JMedia.Sync.closeAddServerDialog();
                    if (window.showToast) window.showToast('Server added', 'success');
                    await JMedia.Sync.loadServers();
                } else {
                    if (errorEl) { errorEl.textContent = json.error || 'Failed to add server'; errorEl.style.display = 'block'; }
                }
            } catch (e) {
                if (errorEl) { errorEl.textContent = 'Error: ' + e.message; errorEl.style.display = 'block'; }
            } finally {
                btn.disabled = false;
                btn.classList.remove('is-loading');
            }
        },

        deleteServer: async function(id) {
            if (!confirm('Remove this server?')) return;
            try {
                const res = await fetch(`/api/sync/servers/${id}`, { method: 'DELETE' });
                if (res.ok) {
                    if (window.showToast) window.showToast('Server removed', 'success');
                    await JMedia.Sync.loadServers();
                }
            } catch (e) {
                console.error('[Sync] Error deleting server:', e);
            }
        },

        toggleServer: async function(id, enabled) {
            try {
                const res = await fetch(`/api/sync/servers/${id}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ enabled })
                });
                if (res.ok) {
                    if (window.showToast) window.showToast(enabled ? 'Server enabled' : 'Server disabled', 'success');
                    await JMedia.Sync.loadServers();
                }
            } catch (e) {
                console.error('[Sync] Error toggling server:', e);
            }
        },

        testServerConnection: async function(id) {
            try {
                const serversRes = await fetch('/api/sync/servers');
                const serversJson = await serversRes.json();
                const servers = serversJson.data || [];
                const server = servers.find(s => s.id === id);
                if (!server) {
                    if (window.showToast) window.showToast('Server not found', 'error');
                    return;
                }
                const res = await fetch('/api/sync/servers/test-connection', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ url: server.url, apiKey: server.apiKey })
                });
                const json = await res.json();
                if (json.data && json.data.reachable) {
                    if (window.showToast) window.showToast('Connection to ' + server.name + ' successful', 'success');
                } else {
                    if (window.showToast) window.showToast('Connection to ' + server.name + ' failed: ' + (json.data?.message || 'Unreachable'), 'error');
                }
            } catch (e) {
                if (window.showToast) window.showToast('Connection test error', 'error');
            }
        },

        triggerSync: async function() {
            const btn = document.getElementById('syncTriggerBtn');
            if (!btn) return;
            btn.disabled = true;
            btn.classList.add('is-loading');
            try {
                const res = await fetch('/api/sync/trigger', { method: 'POST' });
                const json = await res.json();
                if (res.ok) {
                    if (window.showToast) window.showToast(json.data?.message || 'Sync started', 'success');
                    setTimeout(() => JMedia.Sync.loadStatus(), 2000);
                }
            } catch (e) {
                if (window.showToast) window.showToast('Failed to trigger sync', 'error');
            } finally {
                btn.disabled = false;
                btn.classList.remove('is-loading');
            }
        },

        loadStatus: async function() {
            const panel = document.getElementById('syncStatusPanel');
            if (!panel) return;
            try {
                const res = await fetch('/api/sync/status');
                const json = await res.json();
                if (res.ok && json.data) {
                    const s = json.data;
                    if (s.inProgress) {
                        panel.innerHTML = '<div class="notification is-info is-light"><i class="pi pi-spin pi-spinner mr-2"></i> Sync in progress...</div>';
                        return;
                    }
                    if (s.lastSync) {
                        const ls = s.lastSync;
                        const statusColor = ls.status === 'completed' ? 'is-success' : ls.status === 'failed' ? 'is-danger' : 'is-warning';
                        panel.innerHTML = `
                            <div class="card" style="background: rgba(255,255,255,0.05);">
                                <div class="card-content p-3">
                                    <div class="level is-mobile mb-2">
                                        <div class="level-left">
                                            <span class="tag ${statusColor}">${escapeHtml(ls.status || 'unknown')}</span>
                                        </div>
                                        <div class="level-right is-size-7 has-text-grey">
                                            ${ls.completedAt ? 'Completed: ' + new Date(ls.completedAt).toLocaleString() : ''}
                                        </div>
                                    </div>
                                    <div class="is-size-7" style="display: grid; grid-template-columns: repeat(auto-fill, minmax(120px, 1fr)); gap: 4px;">
                                        ${ls.songsSent !== undefined ? '<div>Sent: ' + ls.songsSent + '</div>' : ''}
                                        ${ls.songsReceived !== undefined ? '<div>Received: ' + ls.songsReceived + '</div>' : ''}
                                        ${ls.songsUpdated !== undefined ? '<div>Updated: ' + ls.songsUpdated + '</div>' : ''}
                                        ${ls.songsCreated !== undefined ? '<div>Created: ' + ls.songsCreated + '</div>' : ''}
                                    </div>
                                    ${ls.error ? '<div class="mt-2 has-text-danger is-size-7">Error: ' + escapeHtml(ls.error) + '</div>' : ''}
                                </div>
                            </div>
                        `;
                    } else {
                        panel.innerHTML = '<div class="has-text-centered p-4 has-text-grey">No sync status available.</div>';
                    }
                }
            } catch (e) {
                console.error('[Sync] Error loading status:', e);
                panel.innerHTML = '<div class="has-text-centered p-4 has-text-danger">Failed to load sync status</div>';
            }
        },

        loadLogs: async function() {
            const container = document.getElementById('syncLogsContainer');
            if (!container) return;
            try {
                const res = await fetch('/api/sync/logs?limit=50');
                const json = await res.json();
                if (res.ok && json.data) {
                    const logs = json.data;
                    if (logs.length === 0) {
                        container.innerHTML = '<div class="has-text-centered p-4 has-text-grey">No sync logs yet.</div>';
                        return;
                    }
                    container.innerHTML = logs.map(log => {
                        const time = log.startedAt ? new Date(log.startedAt).toLocaleString() : '';
                        const statusColor = log.status === 'completed' ? '#48c774' : log.status === 'failed' ? '#f14668' : '#ffdd57';
                        return `<div style="padding: 2px 0; border-bottom: 1px solid rgba(255,255,255,0.05); font-size: 0.8rem;">
                            <span style="color: rgba(255,255,255,0.5);">${escapeHtml(time)}</span>
                            <span style="color: ${statusColor}; margin: 0 8px;">[${escapeHtml(log.status || '?')}]</span>
                            <span>${escapeHtml(log.errorMessage || 'Sync completed')}</span>
                        </div>`;
                    }).join('');
                    container.scrollTop = container.scrollHeight;
                }
            } catch (e) {
                console.error('[Sync] Error loading logs:', e);
                container.innerHTML = '<div class="has-text-centered p-4 has-text-danger">Failed to load logs</div>';
            }
        }
    };

    window.JMedia = JMedia;

})(window);
