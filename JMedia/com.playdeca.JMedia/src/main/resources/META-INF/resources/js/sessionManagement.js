// Session Management JavaScript

console.log('[Sessions] SessionManagement.js loaded');

function escapeHtml(text) {
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

function getCurrentSessionId() {
    try {
        const cookies = document.cookie.split(';');
        for (let cookie of cookies) {
            const [name, value] = cookie.trim().split('=');
            if (name === 'JMEDIA_SESSION') {
                return value;
            }
        }
    } catch (e) {
        console.error('[Sessions] Error getting session cookie:', e);
    }
    return null;
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

window.loadSessions = async function() {
    console.log('[Sessions] loadSessions called');
    
    let attempts = 0;
    const maxAttempts = 10;
    
    const waitForElement = () => {
        const tableBody = document.getElementById('sessionsTableBody');
        if (tableBody || attempts >= maxAttempts) {
            return tableBody;
        }
        attempts++;
        console.log('[Sessions] Waiting for element, attempt:', attempts);
        return null;
    };
    
    let tableBody = waitForElement();
    
    // If not found, wait a bit and retry
    if (!tableBody) {
        await new Promise(resolve => setTimeout(resolve, 100));
        tableBody = document.getElementById('sessionsTableBody');
    }
    
    if (!tableBody) {
        console.error('[Sessions] sessionsTableBody element not found after retries!');
        // Try to find any element with sessions in ID
        const debugElements = document.querySelectorAll('[id*="session"]');
        console.log('[Sessions] Debug - elements with "session" in ID:', debugElements.length);
        return;
    }
    
    console.log('[Sessions] Elements found, starting fetch...');
    clearSessionsError();
    showSessionsLoading(true);
    tableBody.innerHTML = '';
    
    const currentSessionId = getCurrentSessionId();
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
}

window.revokeSession = async function(sessionId) {
    console.log('[Sessions] Revoking session:', sessionId);
    
    if (!confirm('Are you sure you want to revoke this session? The user will be logged out.')) {
        return;
    }
    
    try {
        const response = await fetch(`/api/users/sessions/${sessionId}`, {
            method: 'DELETE'
        });
        
        const result = await response.json();
        
        if (!response.ok) {
            Toast.error(result.error || 'Failed to revoke session');
            return;
        }
        
        Toast.success('Session revoked successfully');
        window.loadSessions();
        
    } catch (e) {
        console.error('[Sessions] Error revoking session:', e);
        Toast.error('Error revoking session');
    }
}

// Initialize when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    console.log('[Sessions] DOM ready, setting up refresh button');
    
    const refreshBtn = document.getElementById('refreshSessionsBtn');
    if (refreshBtn) {
        refreshBtn.addEventListener('click', () => {
            console.log('[Sessions] Refresh button clicked');
            window.loadSessions();
        });
    } else {
        console.warn('[Sessions] Refresh button not found');
    }
});

// Also set up immediately in case DOMContentLoaded already fired
const refreshBtn = document.getElementById('refreshSessionsBtn');
if (refreshBtn) {
    refreshBtn.addEventListener('click', () => {
        console.log('[Sessions] Refresh button clicked (immediate)');
        window.loadSessions();
    });
}
