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
        
        // Reconnection timer
        reconnectTimer: null,
        
        // Profile ID promise
        profileIdPromise: null,
        
        /**
         * Initialize WebSocket manager
         */
        init: function() {
            this.setupEventListeners();
            this.connect();
            window.Helpers.log('WebSocketManager initialized');
        },
        
        /**
         * Set up event listeners
         */
        setupEventListeners: function() {
            // Listen for profile changes to reconnect
            window.addEventListener('profileChanged', () => {
                this.reconnect();
            });
            
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
                
                this.ws.onclose = () => {
                    this.onClose();
                };
                
                this.ws.onerror = (error) => {
                    this.onError(error);
                };
                
                this.ws.onmessage = (event) => {
                    this.onMessage(event);
                };
                
            } catch (error) {
                console.error('[WebSocketManager] Failed to connect WebSocket:', error);
                this.scheduleReconnect(2000);
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
            window.Helpers.log('WebSocketManager connected');
            
            // Clear any reconnection timer
            if (this.reconnectTimer) {
                clearTimeout(this.reconnectTimer);
                this.reconnectTimer = null;
            }
            
            // Set offline status to false
            if (window.StateManager) {
                window.StateManager.setOffline(false);
            }
            
            // Show reconnect toast
            if (window.showToast) {
                window.showToast('🔗 Network connection restored', 'success', 3000);
            }
            
            // Emit connection event
            window.dispatchEvent(new CustomEvent('websocketConnected', {
                detail: { timestamp: Date.now() }
            }));
            
            // Lightweight resync on reconnect
            this.performResync();
        },
        
        /**
         * Handle WebSocket close
         */
        onClose: function() {
            this.connected = false;
            window.Helpers.log('WebSocketManager disconnected');
            
            // Set offline status to true
            if (window.StateManager) {
                window.StateManager.setOffline(true);
            }
            
            // Schedule reconnection
            this.scheduleReconnect(1000);
            
            // Emit disconnection event
            window.dispatchEvent(new CustomEvent('websocketDisconnected', {
                detail: { timestamp: Date.now() }
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
                                    currentTime: state.currentTime,
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
            
        // Debounce state updates to prevent infinite loops
        const now = Date.now();
        if (window.WebSocketManager.lastStateUpdate && now - window.WebSocketManager.lastStateUpdate < 500) {
            console.log('🚫 WebSocket: Skipping update due to debounce - time since last:', now - window.WebSocketManager.lastStateUpdate);
            return; // Skip updates that come too quickly
        }
        window.WebSocketManager.lastStateUpdate = now;
        console.log('🚀 WebSocket: Processing state update after debounce');
            
            console.log('🎵 WebSocket: Received FULL state update:', state);
            console.log('🎵 WebSocket: Key properties:', {
                currentSongId: state.currentSongId,
                songName: state.songName,
                artist: state.artist,
                artistName: state.artistName, // Alternative field name
                playing: state.playing,
                currentTime: state.currentTime,
                duration: state.duration,
                shuffleMode: state.shuffleMode,
                repeatMode: state.repeatMode
            });
            
            // Update all state properties
            window.dispatchEvent(new CustomEvent('requestStateUpdate', {
                detail: {
                    changes: {
                        currentSongId: state.currentSongId,
                        artist: state.artistName || state.artist, // Use artistName from server, fallback to artist
                        songName: state.songName, // Add songName
                        playing: state.playing,
                        currentTime: state.currentTime,
                        duration: state.duration,
                        shuffleMode: state.shuffleMode,
                        repeatMode: state.repeatMode,
                        cue: state.cue
                    },
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
                    fetch(`/api/music/playback/state/${pid}`)
                        .then(r => r.ok ? r.json() : null)
                        .then(data => {
                            // Normalize to a state payload if possible
                            let payload = null;
                            if (data !== null && data !== undefined) {
                                payload = data.payload ?? data.state ?? data;
                            }
                            
                            if (payload && typeof payload === 'object' && payload.timestamp) {
                                // Get localStorage state for age comparison
                                const savedState = JSON.parse(localStorage.getItem('playbackState') || '{}');
                                
                                if (savedState.timestamp) {
                                    const localStorageAge = Date.now() - savedState.timestamp;
                                    const websocketAge = Date.now() - payload.timestamp;
                                    
                                    // Only use WebSocket state if it's newer than localStorage
                                    if (websocketAge < localStorageAge) {
                                        window.Helpers.log('WebSocketManager: WebSocket state is newer - overriding localStorage');
                                        this.processStateMessage({ type: 'state', payload: payload });
                                    } else {
                                        window.Helpers.log('WebSocketManager: WebSocket state is older - ignoring, using localStorage state');
                                    }
                                } else {
                                    // No localStorage state - use WebSocket state
                                    this.processStateMessage({ type: 'state', payload: payload });
                                }
                            }
                        })
                        .catch(() => {
                            // Ignore fetch errors on resync
                        });
                }
            } catch (e) {
                // Ignore sync errors on reconnect
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
        scheduleReconnect: function(delay) {
            if (this.reconnectTimer) {
                clearTimeout(this.reconnectTimer);
            }
            
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