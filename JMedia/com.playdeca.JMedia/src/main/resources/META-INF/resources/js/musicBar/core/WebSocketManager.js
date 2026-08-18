/**
 * WebSocketManager - WebSocket communication with message queuing
 * Handles WebSocket connection, message processing, and server communication
 */
(function(window) {
    'use strict';
    
    window.WebSocketManager = {
        // WebSocket connection
        ws: null,
        
        // Connection state
        connected: false,
        wasConnected: false,
        
        // Reconnection timer
        reconnectTimer: null,
        
        reconnectAttempts: 0,
        maxReconnectAttempts: 10,
        baseReconnectDelay: 1000,
        maxReconnectDelay: 30000,
        pollTimer: null,
        pollInterval: 5000,
        
        // Profile ID promise
        profileIdPromise: null,
        
        /**
         * Initialize WebSocket manager
         */
        init: function() {
            this.setupEventListeners();
            this.connect();
            setTimeout(() => {
                if (!this.connected) {
                    this.performResync();
                    this.startPolling();
                }
            }, 3000);
            window.Helpers.log('WebSocketManager initialized');
        },
        
        /**
         * Set up event listeners
         */
        setupEventListeners: function() {
            // Listen for profile changes to reconnect. ProfileManager dispatches
            // 'profileSwitched' on document.body; also accept the generic
            // 'profileChanged' on window for other emitters.
            window.addEventListener('profileChanged', () => {
                this.reconnect();
            });
            if (document.body) {
                document.body.addEventListener('profileSwitched', () => {
                    this.reconnect();
                });
            } else {
                window.addEventListener('DOMContentLoaded', () => {
                    if (document.body) {
                        document.body.addEventListener('profileSwitched', () => {
                            this.reconnect();
                        });
                    }
                }, { once: true });
            }
            
            // Listen for manual send requests
            window.addEventListener('sendWebSocketMessage', (e) => {
                this.send(e.detail.type, e.detail.payload);
            });
            
            // Listen for process WebSocket message events from sync manager
            window.addEventListener('processWebSocketMessage', (e) => {
                this.processMessage(e.detail.message);
            });
            
            window.Helpers.log('WebSocketManager event listeners configured');
        },
        
        /**
         * Connect WebSocket for current profile
         */
        connect: async function() {
            try {
                const profileId = await this.waitForProfileId();
                window.Helpers.log(`WebSocketManager connecting WebSocket for profile: ${profileId}`);
                
                const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
                const wsUrl = `${protocol}//${location.host}/api/music/ws/${profileId}`;
                
                this.ws = new WebSocket(wsUrl);
                
                this.ws.onopen = () => {
                    this.onOpen();
                };
                
                this.ws.onclose = (event) => {
                    this.onClose(event);
                };
                
                this.ws.onerror = (error) => {
                    this.onError(error);
                };
                
                this.ws.onmessage = (event) => {
                    this.onMessage(event);
                };
                
            } catch (error) {
                console.error('[WebSocketManager] Failed to connect WebSocket:', error);
                this.scheduleReconnect();
            }
        },
        
        /**
         * Wait for profile ID to be available
         * @returns {Promise} Profile ID
         */
        waitForProfileId: function() {
            if (this.profileIdPromise) {
                return this.profileIdPromise;
            }
            
            this.profileIdPromise = new Promise((resolve) => {
                const checkProfile = () => {
                    if (window.globalActiveProfileId && window.globalActiveProfileId !== 'undefined') {
                        resolve(window.globalActiveProfileId);
                    } else {
                        setTimeout(checkProfile, 50);
                    }
                };
                checkProfile();
            });
            
            return this.profileIdPromise;
        },
        
        /**
         * Handle WebSocket open
         */
        onOpen: function() {
            this.connected = true;
            this.reconnectAttempts = 0;
            this.stopPolling();
            window.Helpers.log('WebSocketManager connected');
            
            if (this.reconnectTimer) {
                clearTimeout(this.reconnectTimer);
                this.reconnectTimer = null;
            }
            
            // Set offline status to false
            if (window.StateManager) {
                window.StateManager.setOffline(false);
            }
            
            // Show reconnect toast ONLY on reconnection, not first connect
            if (this.wasConnected && window.showToast) {
                window.showToast('🔗 Network connection restored', 'success', 3000);
            }
            this.wasConnected = true;
            
            // Emit connection event
            window.dispatchEvent(new CustomEvent('websocketConnected', {
                detail: { timestamp: Date.now() }
            }));
            
            // Lightweight resync on reconnect
            this.performResync();
        },
        
        onClose: function(event) {
            this.connected = false;
            const code = event ? event.code : null;
            const reason = event ? event.reason : '';
            window.Helpers.log(`WebSocketManager disconnected (code: ${code}, reason: ${reason})`);
            
            if (window.StateManager) {
                window.StateManager.setOffline(true);
            }
            
            const authFailureCodes = [1008, 1003, 1012, 1013, 1014];
            if (code && authFailureCodes.includes(code)) {
                console.warn(`[WebSocketManager] Auth/policy failure (code ${code}): ${reason}. Falling back to HTTP polling.`);
                this.startPolling();
                window.dispatchEvent(new CustomEvent('websocketDisconnected', {
                    detail: { timestamp: Date.now(), code, reason, permanent: true }
                }));
                return;
            }
            
            this.scheduleReconnect();
            
            window.dispatchEvent(new CustomEvent('websocketDisconnected', {
                detail: { timestamp: Date.now(), code, reason }
            }));
        },
        
        /**
         * Handle WebSocket error
         * @param {Event} error - WebSocket error
         */
        onError: function(error) {
            this.connected = false;
            console.error('[WebSocketManager] WebSocket error:', error);
            
            // Set offline status
            if (window.StateManager) {
                window.StateManager.setOffline(true);
            }
            
            // Emit error event
            window.dispatchEvent(new CustomEvent('websocketError', {
                detail: { error: error, timestamp: Date.now() }
            }));
        },
        
        /**
         * Handle WebSocket message
         * @param {MessageEvent} event - WebSocket message event
         */
        onMessage: function(event) {
            let message;
            try {
                message = JSON.parse(event.data);
            } catch (e) {
                console.error("[WebSocketManager] Error parsing message:", e);
                return;
            }
            
            // Use synchronization manager for message queuing
            if (window.SynchronizationManager) {
                SynchronizationManager.enqueueMessage(message);
            } else {
                // Fallback: process directly
                this.processMessage(message);
            }
        },
        
        /**
         * Process WebSocket message (internal method)
         * @param {Object} message - Parsed message
         */
        processMessage: function(message) {
            window.Helpers.log('WebSocketManager processing message:', message.type, message.payload);
            
            switch (message.type) {
                case 'state':
                    this.processStateMessage(message);
                    break;
                case 'history-update':
                    this.processHistoryUpdate(message);
                    break;
                default:
                    window.Helpers.log('WebSocketManager unknown message type:', message.type);
            }
            
            // Emit generic message processed event
            window.dispatchEvent(new CustomEvent('websocketMessageProcessed', {
                detail: { message: message, timestamp: Date.now() }
            }));
        },
        
        /**
         * Process state message
         * @param {Object} message - State message
         */
        processStateMessage: function(message) {
            const state = message.payload;
            
            // Check for play/pause conflicts with local actions
            const currentState = window.StateManager?.getState();
            const playChanged = state.playing !== currentState?.playing;
            
            if (playChanged && window.ActionTracker) {
                if (window.ActionTracker.shouldSkipWebSocketMessage('playPause', state)) {
                    window.Helpers.log('WebSocketManager skipping WebSocket state update due to recent local play/pause action');
                    
                    // Still update other state fields but don't override play/pause
                    if (state.currentSongId !== currentState?.currentSongId) {
                        window.dispatchEvent(new CustomEvent('requestStateUpdate', {
                            detail: {
                                changes: {
                                    currentSongId: state.currentSongId,
                                    artist: state.artistName || state.artist,
                                    duration: state.duration
                                },
                                source: 'websocket'
                            }
                        }));
                    } else {
                        // Just update UI for play/pause changes
                        window.dispatchEvent(new CustomEvent('requestUIUpdate', { detail: { source: 'websocket' } }));
                    }
                    return;
                }
            }
            
            // Update all state properties (excluding currentTime - let audio element be the visual source)
            // When video is active, NEVER let WebSocket override the local playing:false
            const stateUpdates = {
                currentSongId: state.currentSongId,
                artist: state.artistName || state.artist,
                songName: state.songName,
                duration: state.duration,
                shuffleMode: state.shuffleMode,
                repeatMode: state.repeatMode,
                cue: state.cue,
                djModeActive: state.djModeActive === true,
                djNextSongId: state.djNextSongId,
                djEntryTime: state.djEntryTime,
                djExitTime: state.djExitTime,
                djTransitionPlanned: state.djTransitionPlanned,
                djTransitionConfidence: state.djTransitionConfidence,
                djTransitionReason: state.djTransitionReason,
                crossfadeDuration: state.crossfadeDuration
            };

            if (!window.videoPlaying) {
                stateUpdates.playing = state.playing;
            }

            window.dispatchEvent(new CustomEvent('requestStateUpdate', {
                detail: {
                    changes: stateUpdates,
                    source: 'websocket'
                }
            }));
            
            // Handle queue changes
            const queueChanged = this.hasQueueChanged(state.cue, currentState?.cue);
            const queueLengthChanged = (currentState?.cue?.length || 0) !== (state.cue?.length || 0);
            
            if (queueChanged || queueLengthChanged) {
                window.dispatchEvent(new CustomEvent('queueChanged', {
                    detail: {
                        queueSize: state.cue?.length || 0,
                        queueChanged: queueChanged,
                        queueLengthChanged: queueLengthChanged
                    }
                }));
            }
            
            // Handle song changes
            if (state.currentSongId !== currentState?.currentSongId) {
                window.dispatchEvent(new CustomEvent('songChanged', {
                    detail: {
                        oldSongId: currentState?.currentSongId,
                        newSongId: state.currentSongId,
                        state: state
                    }
                }));
            }
        },
        
        /**
         * Process history update message
         * @param {Object} message - History update message
         */
        processHistoryUpdate: function(message) {
            window.Helpers.log('WebSocketManager processing history update');
            
            // Trigger history refresh
            if (window.refreshHistory) {
                window.refreshHistory();
            } else {
                // Wait a bit and try again in case history.js hasn't loaded yet
                setTimeout(() => {
                    if (window.refreshHistory) {
                        window.refreshHistory();
                    }
                }, 100);
            }
            
            // Emit history update event
            window.dispatchEvent(new CustomEvent('historyUpdate', {
                detail: { message: message, timestamp: Date.now() }
            }));
        },
        
        /**
         * Check if queue has changed
         * @param {Array} newCue - New queue
         * @param {Array} oldCue - Old queue
         * @returns {boolean} True if changed
         */
        hasQueueChanged: function(newCue, oldCue) {
            if (!newCue && !oldCue) {
                return false;
            }
            if (!newCue || !oldCue) {
                return true;
            }
            if (newCue.length !== oldCue.length) {
                return true;
            }
            
            // Quick check first and last items
            if (newCue[0] !== oldCue[0] || newCue[newCue.length - 1] !== oldCue[oldCue.length - 1]) {
                return true;
            }
            
            // Only do full comparison if quick checks pass
            return JSON.stringify(newCue) !== JSON.stringify(oldCue);
        },
        
        /**
         * Perform lightweight resync on reconnect
         */
        performResync: function() {
            try {
                const pid = window.globalActiveProfileId || null;
                if (pid) {
                    window.Helpers.log('WebSocketManager: performResync fetching state for profile', pid);
                    fetch(`/api/music/playback/state/${pid}`)
                        .then(r => {
                            if (!r.ok) {
                                window.Helpers.log('WebSocketManager: resync fetch failed with status', r.status);
                                return null;
                            }
                            return r.json();
                        })
                        .then(data => {
                            // Normalize to a state payload if possible
                            let payload = null;
                            if (data !== null && data !== undefined) {
                                payload = data.payload ?? data.state ?? data;
                            }
                            
                            if (!payload || typeof payload !== 'object') {
                                window.Helpers.log('WebSocketManager: resync got empty/null payload');
                                return;
                            }
                            
                            // Java PlaybackState uses 'serverTime' (not 'timestamp')
                            const serverTimestamp = payload.serverTime || payload.timestamp;
                            window.Helpers.log('WebSocketManager: resync payload received, serverTime:', payload.serverTime, 'songName:', payload.songName, 'currentSongId:', payload.currentSongId);
                            
                            if (serverTimestamp) {
                                // Get localStorage state for age comparison (per-profile key)
                                const savedState = (window.StatePersistence && typeof window.StatePersistence.getSavedState === 'function')
                                    ? (window.StatePersistence.getSavedState() || {})
                                    : {};
                                
                                if (savedState.timestamp) {
                                    const localStorageAge = Date.now() - savedState.timestamp;
                                    const serverAge = Date.now() - serverTimestamp;
                                    
                                    // Only use server state if it's newer than localStorage
                                    if (serverAge < localStorageAge) {
                                        window.Helpers.log('WebSocketManager: server state is newer - overriding localStorage');
                                        this.processStateMessage({ type: 'state', payload: payload });
                                    } else {
                                        window.Helpers.log('WebSocketManager: server state is older - using localStorage state');
                                    }
                                } else {
                                    // No localStorage state - use server state
                                    window.Helpers.log('WebSocketManager: no localStorage state - applying server state');
                                    this.processStateMessage({ type: 'state', payload: payload });
                                }
                            } else {
                                // No timestamp available - apply state anyway (first load / fresh state)
                                window.Helpers.log('WebSocketManager: no timestamp on payload - applying server state directly');
                                this.processStateMessage({ type: 'state', payload: payload });
                            }
                        })
                        .catch((err) => {
                            window.Helpers.log('WebSocketManager: resync fetch error:', err.message || err);
                        });
                }
            } catch (e) {
                window.Helpers.log('WebSocketManager: resync error:', e.message || e);
            }
        },
        
        /**
         * Send WebSocket message
         * @param {string} type - Message type
         * @param {Object} payload - Message payload
         */
        send: function(type, payload) {
            if (this.ws && this.ws.readyState === WebSocket.OPEN) {
                const message = JSON.stringify({ type, payload });
                this.ws.send(message);
                window.Helpers.log('WebSocketManager sent message:', type, payload);
                
                // Emit send event
                window.dispatchEvent(new CustomEvent('websocketMessageSent', {
                    detail: { type, payload, timestamp: Date.now() }
                }));
            } else {
                window.Helpers.log('WebSocketManager: Cannot send message - WebSocket not connected:', type);
            }
        },
        
        /**
         * Reconnect WebSocket
         */
        reconnect: function() {
            window.Helpers.log('WebSocketManager reconnecting...');
            this.reconnectAttempts = 0;
            
            if (this.reconnectTimer) {
                clearTimeout(this.reconnectTimer);
                this.reconnectTimer = null;
            }
            
            if (this.ws) {
                this.ws.close();
                this.ws = null;
            }
            
            this.connected = false;
            this.connect();
        },
        
        /**
         * Schedule reconnection attempt
         * @param {number} delay - Delay in milliseconds
         */
        scheduleReconnect: function() {
            if (this.reconnectTimer) {
                clearTimeout(this.reconnectTimer);
            }
            
            if (this.reconnectAttempts >= this.maxReconnectAttempts) {
                console.warn(`[WebSocketManager] Max reconnect attempts (${this.maxReconnectAttempts}) reached. Giving up.`);
                return;
            }
            
            const delay = Math.min(
                this.baseReconnectDelay * Math.pow(2, this.reconnectAttempts),
                this.maxReconnectDelay
            );
            this.reconnectAttempts++;
            
            window.Helpers.log(`WebSocketManager reconnecting in ${delay}ms (attempt ${this.reconnectAttempts}/${this.maxReconnectAttempts})`);
            
            this.reconnectTimer = setTimeout(() => {
                this.connect();
            }, delay);
        },
        
        /**
         * Get connection status
         * @returns {Object} Connection status
         */
        getConnectionStatus: function() {
            return {
                connected: this.connected,
                websocket: this.ws,
                readyState: this.ws ? this.ws.readyState : WebSocket.CLOSED,
                hasReconnectTimer: this.reconnectTimer !== null
            };
        },
        
        /**
         * Disconnect WebSocket
         */
        disconnect: function() {
            if (this.reconnectTimer) {
                clearTimeout(this.reconnectTimer);
                this.reconnectTimer = null;
            }
            
            if (this.ws) {
                this.ws.close();
                this.ws = null;
            }
            
            this.connected = false;
            window.Helpers.log('WebSocketManager disconnected');
        },
        
        startPolling: function() {
            if (this.pollTimer) return;
            window.Helpers.log('WebSocketManager: starting HTTP state polling fallback');
            this.pollTimer = setInterval(() => {
                this.performResync();
            }, this.pollInterval);
        },
        
        stopPolling: function() {
            if (this.pollTimer) {
                clearInterval(this.pollTimer);
                this.pollTimer = null;
                window.Helpers.log('WebSocketManager: stopped HTTP state polling');
            }
        }
    };
    
    // Auto-initialize when dependencies are available
    if (window.Helpers && window.StateManager && window.SynchronizationManager) {
        window.WebSocketManager.init();
    } else {
        // Wait for dependencies
        const checkDeps = () => {
            if (window.Helpers && window.StateManager && window.SynchronizationManager) {
                window.WebSocketManager.init();
            } else {
                setTimeout(checkDeps, 50);
            }
        };
        setTimeout(checkDeps, 50);
    }
    
})(window);