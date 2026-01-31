// Race condition mitigation: profile initialization helper
async function waitForProfileId() {
    return new Promise((resolve) => {
        const checkProfile = () => {
            if (window.globalActiveProfileId && window.globalActiveProfileId !== 'undefined') {
                resolve(window.globalActiveProfileId);
            } else {
                setTimeout(checkProfile, 50);
            }
        };
        checkProfile();
    });
}

// Device Sync JavaScript functionality
class DeviceSyncManager {
    constructor() {
        this.currentQrCode = null;
        this.activeSessions = [];
        this.refreshInterval = null;
        this.isInitializing = false;
        this.init();
    }

    init() {
        this.bindEvents();
        // Don't auto-load sessions - wait for user interaction or profile ready event
        this.setupProfileListener();
        this.startAutoRefresh();
    }

    setupProfileListener() {
        // Only load sessions when profile is ready AND user is on settings page
        document.body.addEventListener('profileReady', (event) => {
            console.log('[DeviceSync] Profile ready event received:', event.detail.profileId);
            // Only load if we're on the settings page and device sync section is visible
            if (document.getElementById('deviceSyncContent')) {
                this.loadActiveSessions().catch(error => {
                    console.error('[DeviceSync] Failed to load sessions on profile ready:', error);
                });
            }
        });
        
        // Also check if profile is already available
        if (window.globalActiveProfileId && window.globalActiveProfileId !== 'undefined' && document.getElementById('deviceSyncContent')) {
            setTimeout(() => {
                this.loadActiveSessions().catch(error => {
                    console.error('[DeviceSync] Failed to load sessions on delayed load:', error);
                });
            }, 500);
        }
    }

    bindEvents() {
        // Generate QR Code button
        document.getElementById('generateQrCodeBtn')?.addEventListener('click', () => {
            this.generateQrCode();
        });

        // Refresh QR Code button
        document.getElementById('refreshQrCodeBtn')?.addEventListener('click', () => {
            this.generateQrCode();
        });

        // Refresh Sessions button
        document.getElementById('refreshSessionsBtn')?.addEventListener('click', () => {
            if (window.globalActiveProfileId && window.globalActiveProfileId !== 'undefined') {
                this.loadActiveSessions();
            } else {
                Toast.warning('Profile not ready. Please wait a moment.');
            }
        });

        // Cleanup Expired button
        document.getElementById('cleanupExpiredBtn')?.addEventListener('click', () => {
            this.cleanupExpiredSessions();
        });

        // Toggle Device Sync card
        document.getElementById('toggleDeviceSyncBtn')?.addEventListener('click', () => {
            this.toggleCard('deviceSyncContent', 'toggleDeviceSyncBtn');
        });

        // Sync settings toggles
        document.getElementById('syncMusicToggle')?.addEventListener('change', (e) => {
            this.updateSyncSettings();
        });

        document.getElementById('syncVideosToggle')?.addEventListener('change', (e) => {
            this.updateSyncSettings();
        });

        document.getElementById('syncPlaylistsToggle')?.addEventListener('change', (e) => {
            this.updateSyncSettings();
        });
    }

    async generateQrCode() {
        try {
            // Race condition mitigation: wait for profile ID to be available
            const profileId = await waitForProfileId();
            if (!profileId) {
                Toast.error('Profile not ready. Please wait a moment and try again.');
                return;
            }

            const generateBtn = document.getElementById('generateQrCodeBtn');
            const refreshBtn = document.getElementById('refreshQrCodeBtn');
            const qrContainer = document.getElementById('qrCodeContainer');

            // Show loading state
            generateBtn.disabled = true;
            generateBtn.innerHTML = '<span class="icon-text"><span class="icon"><i class="pi pi-spin pi-spinner"></i></span><span>Generating...</span></span>';

            const response = await fetch(`/api/device-sync/${profileId}/generate-qr`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                }
            });

            const result = await response.json();

            if (result.error) {
                throw new Error(result.error);
            } else {
                this.currentQrCode = result.data;
                this.displayQrCode(result.data);
                
                // Show refresh button, hide generate button
                generateBtn.style.display = 'none';
                refreshBtn.style.display = 'inline-flex';

                Toast.success('QR code generated successfully!');
            }

        } catch (error) {
            console.error('Failed to generate QR code:', error);
            Toast.error('Failed to generate QR code: ' + error.message);
        } finally {
            const generateBtn = document.getElementById('generateQrCodeBtn');
            generateBtn.disabled = false;
            generateBtn.innerHTML = '<span class="icon-text"><span class="icon"><i class="pi pi-qrcode"></i></span><span>Generate QR Code</span></span>';
        }
    }

    displayQrCode(qrData) {
        const qrContainer = document.getElementById('qrCodeContainer');
        
        qrContainer.innerHTML = `
            <div class="has-text-centered">
                <div class="mb-3">
                    <img src="${qrData.qrImage}" alt="QR Code" style="max-width: 200px; border: 2px solid #dbdbdb; border-radius: 8px;" />
                </div>
                <div class="notification is-success is-light">
                    <p class="is-size-7 mb-2"><strong>Connection URL:</strong></p>
                    <p class="is-size-7 has-text-weight-mono word-break">${qrData.connectionUrl}</p>
                </div>
                <div class="notification is-info is-light">
                    <p class="is-size-7">Scan this QR code with your mobile device to connect to your JMedia library</p>
                </div>
            </div>
        `;
    }

    async loadActiveSessions() {
        try {
            // Race condition mitigation: prevent concurrent initialization
            if (this.isInitializing) {
                console.warn('[DeviceSync] Already initializing, skipping duplicate request');
                return;
            }

            // Race condition mitigation: wait for profile ID
            const profileId = await waitForProfileId();
            if (!profileId) {
                console.warn('[DeviceSync] Profile ID not available, skipping session load');
                return;
            }

            this.isInitializing = true;
            const url = `/api/device-sync/${profileId}/sessions`;
            console.log('[DeviceSync] Loading sessions from URL:', url);
            console.log('[DeviceSync] Full URL:', window.location.origin + url);
            const response = await fetch(url);
            
            console.log('[DeviceSync] Response status:', response.status, response.statusText);
            
            if (!response.ok) {
                const errorText = await response.text();
                console.error('[DeviceSync] Error response body:', errorText);
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            
            const result = await response.json();
            console.log('[DeviceSync] Response data:', result);

            // ApiResponse format: { data: [...] } for success, { error: "message" } for error
            if (result.error) {
                throw new Error(result.error);
            } else {
                this.activeSessions = result.data || [];
                this.displayActiveSessions();
                console.log('[DeviceSync] Sessions loaded successfully:', this.activeSessions.length);
            }

        } catch (error) {
            console.error('Failed to load active sessions:', error);
            console.error('Profile ID used:', window.globalActiveProfileId);
            console.error('Error details:', error);
            
            // Show user-friendly error message
            if (error.message.includes('Failed to fetch')) {
                Toast.error('Unable to connect to server. Please check if the application is running.');
            } else {
                Toast.error('Failed to load active sessions: ' + error.message);
            }
            
            // Show empty state with helpful message
            this.activeSessions = [];
            this.displayActiveSessions();
            
            // Show device sync section as not available
            const qrContainer = document.getElementById('qrCodeContainer');
            if (qrContainer) {
                qrContainer.innerHTML = `
                    <div class="notification is-warning is-light">
                        <p class="is-size-7">
                            <strong>Device Sync temporarily unavailable</strong><br>
                            The device sync service is not responding. This may be due to:
                        </p>
                        <ul class="is-size-7 mt-2">
                            <li>Server starting up</li>
                            <li>Database initialization in progress</li>
                            <li>Service maintenance</li>
                        </ul>
                        <p class="is-size-7 mt-2">
                            Please refresh the page in a few moments.
                        </p>
                    </div>
                `;
            }
    } finally {
            // Race condition mitigation: always reset initialization flag
            this.isInitializing = false;
        }
    }

    displayActiveSessions() {
        const sessionsList = document.getElementById('deviceSessionsList');

        if (this.activeSessions.length === 0) {
            sessionsList.innerHTML = `
                <div class="notification is-warning is-light">
                    <p class="is-size-7">No devices currently connected</p>
                </div>
            `;
            return;
        }

        const sessionsHtml = this.activeSessions.map(session => `
            <div class="box is-small mb-3" style="background-color: rgba(255,255,255,0.05);">
                <div class="media">
                    <div class="media-left">
                        <span class="icon is-medium">
                            <i class="pi pi-${this.getDeviceIcon(session.deviceType)}" style="font-size: 1.5rem;"></i>
                        </span>
                    </div>
                    <div class="media-content">
                        <p class="title is-7 has-text-white">${session.deviceName || 'Unknown Device'}</p>
                        <p class="subtitle is-7 has-text-grey">
                            ${session.deviceType || 'Unknown'} • ${session.currentIpAddress || 'Unknown IP'}
                        </p>
                        <p class="is-size-7 has-text-grey">
                            Connected: ${this.formatDate(session.createdAt)} • 
                            Last seen: ${this.formatDate(session.lastAccessedAt)}
                        </p>
                        <div class="tags are-small mt-2">
                            ${session.syncMusic ? '<span class="tag is-success is-light">Music</span>' : ''}
                            ${session.syncVideos ? '<span class="tag is-info is-light">Videos</span>' : ''}
                            ${session.syncPlaylists ? '<span class="tag is-warning is-light">Playlists</span>' : ''}
                        </div>
                    </div>
                    <div class="media-right">
                        <button class="button is-danger is-small" onclick="deviceSyncManager.revokeSession(${session.id})">
                            <span class="icon-text">
                                <span class="icon"><i class="pi pi-times"></i></span>
                                <span>Remove</span>
                            </span>
                        </button>
                    </div>
                </div>
            </div>
        `).join('');

        sessionsList.innerHTML = sessionsHtml;
    }

    getDeviceIcon(deviceType) {
        const iconMap = {
            'phone': 'mobile',
            'tablet': 'tablet',
            'computer': 'desktop',
            'laptop': 'laptop',
            'tv': 'tv',
            'default': 'mobile'
        };
        return iconMap[deviceType?.toLowerCase()] || iconMap.default;
    }

    formatDate(dateString) {
        if (!dateString) return 'Unknown';
        
        const date = new Date(dateString);
        const now = new Date();
        const diffMs = now - date;
        const diffMins = Math.floor(diffMs / 60000);
        const diffHours = Math.floor(diffMs / 3600000);
        const diffDays = Math.floor(diffMs / 86400000);

        if (diffMins < 1) return 'Just now';
        if (diffMins < 60) return `${diffMins}m ago`;
        if (diffHours < 24) return `${diffHours}h ago`;
        if (diffDays < 7) return `${diffDays}d ago`;
        
        return date.toLocaleDateString();
    }

    async revokeSession(sessionId) {
        if (!confirm('Are you sure you want to revoke this device\'s access?')) {
            return;
        }

        try {
            const response = await fetch(`/api/device-sync/${window.globalActiveProfileId}/sessions/${sessionId}`, {
                method: 'DELETE'
            });

            const result = await response.json();

            if (result.error) {
                throw new Error(result.error);
            } else {
                Toast.success('Device access revoked successfully');
                this.loadActiveSessions(); // Refresh the list
            }

        } catch (error) {
            console.error('Failed to revoke session:', error);
            Toast.error('Failed to revoke session: ' + error.message);
        }
    }

    async cleanupExpiredSessions() {
        try {
            const response = await fetch(`/api/device-sync/${window.globalActiveProfileId}/cleanup-expired`, {
                method: 'POST'
            });

            const result = await response.json();

            if (result.error) {
                throw new Error(result.error);
            } else {
                const cleanedCount = result.data?.cleanedUpCount || 0;
                Toast.success(`Cleaned up ${cleanedCount} expired sessions`);
                this.loadActiveSessions(); // Refresh the list
            }

        } catch (error) {
            console.error('Failed to cleanup expired sessions:', error);
            Toast.error('Failed to cleanup expired sessions: ' + error.message);
        }
    }

    updateSyncSettings() {
        const syncMusic = document.getElementById('syncMusicToggle').checked;
        const syncVideos = document.getElementById('syncVideosToggle').checked;
        const syncPlaylists = document.getElementById('syncPlaylistsToggle').checked;

        // Store these settings locally for future QR code generations
        localStorage.setItem('deviceSyncSettings', JSON.stringify({
            syncMusic,
            syncVideos,
            syncPlaylists
        }));

        Toast.info('Sync settings updated');
    }

    loadSyncSettings() {
        try {
            const settings = JSON.parse(localStorage.getItem('deviceSyncSettings') || '{}');
            
            document.getElementById('syncMusicToggle').checked = settings.syncMusic !== false;
            document.getElementById('syncVideosToggle').checked = settings.syncVideos === true;
            document.getElementById('syncPlaylistsToggle').checked = settings.syncPlaylists !== false;
        } catch (error) {
            console.error('Failed to load sync settings:', error);
        }
    }

    toggleCard(contentId, buttonId) {
        const content = document.getElementById(contentId);
        const button = document.getElementById(buttonId);
        const icon = button.querySelector('i');

        if (content.style.display === 'none') {
            content.style.display = 'block';
            icon.className = 'pi pi-angle-down';
        } else {
            content.style.display = 'none';
            icon.className = 'pi pi-angle-right';
        }
    }

    startAutoRefresh() {
        // Refresh sessions every 30 seconds, but only if profile is ready and we're on settings page
        this.refreshInterval = setInterval(() => {
            if (window.globalActiveProfileId && 
                window.globalActiveProfileId !== 'undefined' && 
                document.getElementById('deviceSyncContent') &&
                document.getElementById('deviceSyncContent').style.display !== 'none') {
                this.loadActiveSessions();
            }
        }, 30000);
    }

    stopAutoRefresh() {
        if (this.refreshInterval) {
            clearInterval(this.refreshInterval);
            this.refreshInterval = null;
        }
    }

    destroy() {
        this.stopAutoRefresh();
    }
}

// Initialize Device Sync Manager when DOM is loaded
let deviceSyncManager;

document.addEventListener('DOMContentLoaded', () => {
    // Delay initialization to ensure profileManager has time to set up
    setTimeout(() => {
        deviceSyncManager = new DeviceSyncManager();
        deviceSyncManager.loadSyncSettings();
    }, 1000);
});

// Clean up on page unload
window.addEventListener('beforeunload', () => {
    if (deviceSyncManager) {
        deviceSyncManager.destroy();
    }
});