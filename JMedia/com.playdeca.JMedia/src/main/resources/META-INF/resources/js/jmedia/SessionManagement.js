(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    console.log('[Sessions] SessionManagement.js loaded');

    function escapeHtml(text) {
        if (JMedia.Helpers && typeof JMedia.Helpers.escapeHtml === 'function') {
            return JMedia.Helpers.escapeHtml(text);
        }
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    function formatTimestamp(timestamp) {
        if (!timestamp) return 'N/A';
        try {
            const date = new Date(timestamp);
            if (isNaN(date.getTime())) return 'Invalid date';
            const now = new Date();
            const diffMs = now - date;
            const diffSecs = Math.floor(diffMs / 1000);
            const diffMins = Math.floor(diffSecs / 60);
            const diffHours = Math.floor(diffMins / 60);
            const diffDays = Math.floor(diffHours / 24);
            if (diffSecs < 60) {
                return 'Just now';
            } else if (diffMins < 60) {
                return diffMins + ' min' + (diffMins > 1 ? 's' : '') + ' ago';
            } else if (diffHours < 24) {
                return diffHours + ' hour' + (diffHours > 1 ? 's' : '') + ' ago';
            } else if (diffDays < 7) {
                return diffDays + ' day' + (diffDays > 1 ? 's' : '') + ' ago';
            } else {
                return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            }
        } catch (e) {
            console.error('[Sessions] Error formatting timestamp:', e);
            return 'Error';
        }
    }

    function formatDuration(seconds) {
        if (seconds === null || seconds === undefined || isNaN(seconds) || seconds <= 0) return '—';
        const total = Math.floor(seconds);
        const h = Math.floor(total / 3600);
        const m = Math.floor((total % 3600) / 60);
        const s = total % 60;
        const parts = [];
        if (h > 0) parts.push(h + 'h');
        if (m > 0) parts.push(m + 'm');
        if (s > 0 || parts.length === 0) parts.push(s + 's');
        return parts.join(' ');
    }

    let currentSessionIdPromise = null;
    async function getCurrentSessionId() {
        if (!currentSessionIdPromise) {
            currentSessionIdPromise = (async function() {
                try {
                    const response = await fetch('/api/auth/status');
                    if (!response.ok) {
                        console.warn('[Sessions] /api/auth/status returned ' + response.status + '; current session id unavailable');
                        return null;
                    }
                    const result = await response.json();
                    const sid = result && result.data ? result.data.sessionId : null;
                    return sid || null;
                } catch (e) {
                    console.error('[Sessions] Error resolving current session id:', e);
                    return null;
                }
            })();
        }
        return currentSessionIdPromise;
    }

    async function waitForElement(id, maxAttempts, delayMs) {
        const attempts = (typeof maxAttempts === 'number') ? maxAttempts : 10;
        const delay = (typeof delayMs === 'number') ? delayMs : 100;
        for (let i = 0; i < attempts; i++) {
            const el = document.getElementById(id);
            if (el) return el;
            await new Promise(function(resolve) { setTimeout(resolve, delay); });
        }
        return document.getElementById(id);
    }

    function showSessionsLoading(show) {
        const loading = document.getElementById('sessionsLoading');
        if (loading) {
            loading.style.display = show ? 'block' : 'none';
            if (show) {
                loading.innerHTML = '<i class="pi pi-spin pi-spinner mr-2"></i> Loading sessions...';
            }
        }
    }

    function showSessionsError(message) {
        const error = document.getElementById('sessionsError');
        const tableBody = document.getElementById('sessionsTableBody');
        if (error) {
            error.textContent = message;
            error.style.display = 'block';
        }
        if (tableBody) {
            tableBody.innerHTML = '<tr><td colspan="5" class="has-text-centered has-text-danger">Error loading sessions</td></tr>';
        }
    }

    function clearSessionsError() {
        const error = document.getElementById('sessionsError');
        if (error) {
            error.style.display = 'none';
        }
    }

    function showXtreamSessionsLoading(show) {
        const loading = document.getElementById('xtreamSessionsLoading');
        if (loading) {
            loading.style.display = show ? 'block' : 'none';
            if (show) {
                loading.innerHTML = '<i class="pi pi-spin pi-spinner mr-2"></i> Loading Xtream sessions...';
            }
        }
    }

    function showXtreamSessionsError(message) {
        const error = document.getElementById('xtreamSessionsError');
        const tableBody = document.getElementById('xtreamSessionsTableBody');
        if (error) {
            error.textContent = message;
            error.style.display = 'block';
        }
        if (tableBody) {
            tableBody.innerHTML = '<tr><td colspan="8" class="has-text-centered has-text-danger">Error loading Xtream sessions</td></tr>';
        }
    }

    function clearXtreamSessionsError() {
        const error = document.getElementById('xtreamSessionsError');
        if (error) {
            error.style.display = 'none';
        }
    }

    JMedia.SessionManagement = {
        _revokeInFlight: false,
        _cleanupInFlight: false,
        _clearXtreamInFlight: false,
        loadSessions: async function() {
            console.log('[Sessions] loadSessions called');
            const tableBody = await waitForElement('sessionsTableBody', 10, 100);

            if (!tableBody) {
                console.error('[Sessions] sessionsTableBody element not found after retries!');
                const debugElements = document.querySelectorAll('[id*="session"]');
                console.log('[Sessions] Debug - elements with "session" in ID:', debugElements.length);
                return;
            }

            console.log('[Sessions] Elements found, starting fetch...');
            clearSessionsError();
            showSessionsLoading(true);
            tableBody.innerHTML = '';

            const currentSessionId = await getCurrentSessionId();
            console.log('[Sessions] Current session ID:', currentSessionId ? 'found' : 'not found');

            try {
                const response = await fetch('/api/users/sessions');
                console.log('[Sessions] Response status:', response.status);

                if (!response.ok) {
                    const result = await response.json().catch(() => ({}));
                    const errorMsg = result.error || `Failed to load sessions (${response.status})`;
                    console.error('[Sessions] API error:', errorMsg);
                    showSessionsError(errorMsg);
                    showSessionsLoading(false);
                    return;
                }

                const result = await response.json();
                const sessions = result.data || [];
                console.log('[Sessions] Received sessions:', sessions.length);

                showSessionsLoading(false);

                if (sessions.length === 0) {
                    tableBody.innerHTML = '<tr><td colspan="5" class="has-text-centered">No active sessions found</td></tr>';
                    return;
                }

                sessions.forEach(session => {
                    const isCurrentSession = currentSessionId && currentSessionId === session.sessionId;
                    const row = document.createElement('tr');
                    row.innerHTML = `
                        <td>
                            <span class="has-text-weight-semibold">${escapeHtml(session.username || 'Unknown')}</span>
                            ${isCurrentSession ? '<span class="tag is-success is-small ml-2">Current</span>' : ''}
                        </td>
                        <td><code>${escapeHtml(session.ipAddress || 'Unknown')}</code></td>
                        <td>${formatTimestamp(session.createdAt)}</td>
                        <td>${formatTimestamp(session.lastActivity)}</td>
                        <td>
                            <button class="button is-danger is-small" 
                                     onclick="window.revokeSession('${escapeHtml(session.sessionId)}')"
                                     ${isCurrentSession ? 'disabled title="Cannot revoke your own session"' : ''}>
                                <span class="icon"><i class="pi pi-ban"></i></span>
                                <span>Revoke</span>
                            </button>
                        </td>
                    `;
                    tableBody.appendChild(row);
                });

                console.log('[Sessions] Sessions rendered successfully');

            } catch (e) {
                console.error('[Sessions] Exception:', e);
                showSessionsError('Connection error: ' + e.message);
                showSessionsLoading(false);
            }
        },

        revokeSession: async function(sessionId) {
            console.log('[Sessions] Revoking session:', sessionId);
            let revokeInFlight = JMedia.SessionManagement._revokeInFlight;
            if (revokeInFlight) return;
            JMedia.SessionManagement._revokeInFlight = true;

            const trigger = (document.activeElement && document.activeElement.tagName === 'BUTTON') ? document.activeElement : null;
            if (trigger) trigger.disabled = true;

            try {
                const currentSessionId = await getCurrentSessionId();
                if (currentSessionId && currentSessionId === sessionId) {
                    Toast.error('Cannot revoke your own session');
                    return;
                }
                if (!currentSessionId) {
                    if (!confirm('Current session could not be determined. Revoke this session anyway?')) {
                        return;
                    }
                }
                if (!confirm('Are you sure you want to revoke this session? The user will be logged out.')) {
                    return;
                }
                const response = await fetch(`/api/users/sessions/${sessionId}`, {
                    method: 'DELETE'
                });
                const result = await response.json().catch(function() { return {}; });
                if (!response.ok) {
                    console.error('[Sessions] revokeSession failed:', response.status);
                    Toast.error((result && result.error) || 'Failed to revoke session');
                    return;
                }
                Toast.success('Session revoked successfully');
                JMedia.SessionManagement.loadSessions();
            } catch (e) {
                console.error('[Sessions] Error revoking session:', e);
                Toast.error('Error revoking session');
            } finally {
                JMedia.SessionManagement._revokeInFlight = false;
                if (trigger) trigger.disabled = false;
            }
        },

        cleanupSessions: async function() {
            console.log('[Sessions] Cleaning up old sessions');
            if (JMedia.SessionManagement._cleanupInFlight) return;
            JMedia.SessionManagement._cleanupInFlight = true;
            const trigger = document.getElementById('cleanupSessionsBtn');
            if (trigger) trigger.disabled = true;
            try {
                if (!confirm('Clean up all expired sessions? Users with expired sessions will stay logged out.')) {
                    return;
                }
                const response = await fetch('/api/users/sessions/cleanup', {
                    method: 'DELETE'
                });
                const result = await response.json().catch(function() { return {}; });
                if (!response.ok) {
                    console.error('[Sessions] cleanupSessions failed:', response.status);
                    Toast.error((result && result.error) || 'Failed to clean up sessions');
                    return;
                }
                Toast.success('Old sessions cleaned up');
                JMedia.SessionManagement.loadSessions();
            } catch (e) {
                console.error('[Sessions] Error cleaning up sessions:', e);
                Toast.error('Error cleaning up sessions');
            } finally {
                JMedia.SessionManagement._cleanupInFlight = false;
                if (trigger) trigger.disabled = false;
            }
        },

        loadXtreamSessions: async function() {
            console.log('[Sessions] loadXtreamSessions called');
            const tableBody = await waitForElement('xtreamSessionsTableBody', 10, 100);

            if (!tableBody) {
                console.error('[Sessions] xtreamSessionsTableBody element not found after retries!');
                return;
            }

            console.log('[Sessions] Xtream elements found, starting fetch...');
            clearXtreamSessionsError();
            showXtreamSessionsLoading(true);
            tableBody.innerHTML = '';

            try {
                const response = await fetch('/api/users/sessions/xtream');
                console.log('[Sessions] Xtream response status:', response.status);

                if (!response.ok) {
                    const result = await response.json().catch(() => ({}));
                    const errorMsg = result.error || `Failed to load Xtream sessions (${response.status})`;
                    console.error('[Sessions] Xtream API error:', errorMsg);
                    showXtreamSessionsError(errorMsg);
                    showXtreamSessionsLoading(false);
                    return;
                }

                const result = await response.json();
                const sessions = result.data || [];
                console.log('[Sessions] Received Xtream sessions:', sessions.length);

                showXtreamSessionsLoading(false);

                if (sessions.length === 0) {
                    tableBody.innerHTML = '<tr><td colspan="8" class="has-text-centered">No Xtream playback sessions yet</td></tr>';
                    return;
                }

                sessions.forEach(session => {
                    const typeTag = `<span class="tag is-light">${escapeHtml(session.type || 'unknown')}</span>`;
                    const stream = `#${escapeHtml(session.streamId)}<code>.${escapeHtml(session.ext || '')}</code>`;
                    let statusTag;
                    if (session.active) {
                        statusTag = '<span class="tag is-success">Active</span>';
                    } else {
                        const reason = session.endReason || 'unknown';
                        let cls = 'is-light';
                        if (reason === 'completed') {
                            cls = 'is-success';
                        } else if (reason === 'disconnected' || reason === 'timeout') {
                            cls = 'is-warning';
                        } else if (reason === 'error') {
                            cls = 'is-danger';
                        }
                        statusTag = `<span class="tag ${cls}">${escapeHtml(reason)}</span>`;
                    }
                    const duration = session.active ? 'In progress' : formatDuration(session.durationSeconds);
                    const row = document.createElement('tr');
                    row.innerHTML = `
                        <td>${typeTag}</td>
                        <td><span class="has-text-weight-semibold">${escapeHtml(session.username || 'Unknown')}</span></td>
                        <td>${stream}</td>
                        <td><code>${escapeHtml(session.ipAddress || 'Unknown')}</code></td>
                        <td>${formatTimestamp(session.startedAt)}</td>
                        <td>${duration}</td>
                        <td>${formatTimestamp(session.endedAt)}</td>
                        <td>${statusTag}</td>
                    `;
                    tableBody.appendChild(row);
                });

                console.log('[Sessions] Xtream sessions rendered successfully');

            } catch (e) {
                console.error('[Sessions] Xtream exception:', e);
                showXtreamSessionsError('Connection error: ' + e.message);
                showXtreamSessionsLoading(false);
            }
        },

        clearXtreamSessions: async function() {
            console.log('[Sessions] Clearing Xtream playback history');
            if (JMedia.SessionManagement._clearXtreamInFlight) return;
            JMedia.SessionManagement._clearXtreamInFlight = true;
            const trigger = document.getElementById('xtreamClearBtn');
            if (trigger) trigger.disabled = true;
            try {
                if (!confirm('Clear all Xtream playback history? This cannot be undone.')) {
                    return;
                }
                const response = await fetch('/api/users/sessions/xtream', {
                    method: 'DELETE'
                });
                const result = await response.json().catch(function() { return {}; });
                if (!response.ok) {
                    console.error('[Sessions] clearXtreamSessions failed:', response.status);
                    Toast.error((result && result.error) || 'Failed to clear Xtream history');
                    return;
                }
                Toast.success('Xtream playback history cleared');
                JMedia.SessionManagement.loadXtreamSessions();
            } catch (e) {
                console.error('[Sessions] Error clearing Xtream history:', e);
                Toast.error('Error clearing Xtream history');
            } finally {
                JMedia.SessionManagement._clearXtreamInFlight = false;
                if (trigger) trigger.disabled = false;
            }
        }
    };

    // Backward-compatible window aliases
    window.loadSessions = JMedia.SessionManagement.loadSessions;
    window.revokeSession = JMedia.SessionManagement.revokeSession;
    window.cleanupSessions = JMedia.SessionManagement.cleanupSessions;
    window.loadXtreamSessions = JMedia.SessionManagement.loadXtreamSessions;
    window.clearXtreamSessions = JMedia.SessionManagement.clearXtreamSessions;

    document.addEventListener('click', function(e) {
        const origin = e.target && e.target.closest ? e.target : null;
        if (!origin) return;
        // refreshSessionsBtn / cleanupSessionsBtn are owned by Settings.initSettingsView,
        // which already assigns their onclick (they call window.loadSessions /
        // window.cleanupSessions). Handling them here too ran each action twice,
        // including a destructive cleanup DELETE. Only the Xtream buttons, which
        // nothing else binds, are delegated here.
        if (origin.closest('#xtreamRefreshBtn')) { JMedia.SessionManagement.loadXtreamSessions(); return; }
        if (origin.closest('#xtreamClearBtn')) { JMedia.SessionManagement.clearXtreamSessions(); return; }
    });

})(window);
