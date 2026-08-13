/**
 * VideoWebSocketManager - WebSocket connection for video remote control.
 *
 * Connects to ws(s)://<host>/api/video/ws/{profileId} and dispatches incoming command messages
 * to the registered onCommand callback. The server broadcasts commands as
 * { "type": "command", "payload": { "commandType": "...", "commandPayload": { ... } } }.
 * Player adapters normalize that shape themselves, so this manager forwards the raw message.
 */
(function (window) {
    'use strict';

    function VideoWebSocketManager(options) {
        options = options || {};
        this.profileId = options.profileId || null;
        this.onCommand = typeof options.onCommand === 'function' ? options.onCommand : null;
        this.onState = typeof options.onState === 'function' ? options.onState : null;
        this.onStateUpdate = typeof options.onStateUpdate === 'function' ? options.onStateUpdate : null;
        this.onOpen = typeof options.onOpen === 'function' ? options.onOpen : null;
        this.onClose = typeof options.onClose === 'function' ? options.onClose : null;
        this.ws = null;
        this.connected = false;
        this.reconnectTimer = null;
        this.closedByUser = false;
    }

    VideoWebSocketManager.prototype.connect = function () {
        var self = this;
        try {
            var protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            // Path param required by the server endpoint; default '1' for legacy callers.
            var pid = this.profileId || '1';
            var wsUrl = protocol + '//' + window.location.host + '/api/video/ws/' + encodeURIComponent(pid);
            console.log('[VideoWebSocketManager] Connecting to', wsUrl, 'profileId=', this.profileId);
            this.ws = new WebSocket(wsUrl);
            this.ws.onopen = function () {
                self.connected = true;
                console.log('[VideoWebSocketManager] WebSocket connected (video)');
                if (self.reconnectTimer) { clearTimeout(self.reconnectTimer); self.reconnectTimer = null; }
                if (self.onOpen) { try { self.onOpen(); } catch (e) { console.error('[VideoWebSocketManager] onOpen error', e); } }
            };
            this.ws.onclose = function () {
                self.connected = false;
                console.log('[VideoWebSocketManager] WebSocket disconnected (video)');
                if (self.onClose) { try { self.onClose(); } catch (e) { console.error('[VideoWebSocketManager] onClose error', e); } }
                if (!self.closedByUser) { self.scheduleReconnect(1500); }
            };
            this.ws.onerror = function (error) {
                console.error('[VideoWebSocketManager] WebSocket error (video)', error);
            };
            this.ws.onmessage = function (event) {
                self.handleMessage(event);
            };
        } catch (error) {
            console.error('[VideoWebSocketManager] Failed to connect:', error);
            this.scheduleReconnect(2000);
        }
    };

    VideoWebSocketManager.prototype.handleMessage = function (event) {
        var message;
        try { message = JSON.parse(event.data); } catch (e) { console.error('[VideoWebSocketManager] Error parsing message:', e); return; }
        if (!message || typeof message.type !== 'string') return;
        if (message.type === 'command') {
            if (this.onCommand) { try { this.onCommand(message); } catch (e) { console.error('[VideoWebSocketManager] onCommand error', e); } }
        } else if (message.type === 'state') {
            var cb = this.onStateUpdate || this.onState;
            // Server sends {type:'state', payload:<ProfileSessionState>}. Adapters expect the
            // ProfileSessionState object directly (state.currentVideoId at top level), so pass payload.
            if (cb) { try { cb(message.payload); } catch (e) { console.error('[VideoWebSocketManager] onStateUpdate error', e); } }
        }
    };

    VideoWebSocketManager.prototype.send = function (type, payload) {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify({ type: type, payload: payload || {} }));
        }
    };

    VideoWebSocketManager.prototype.scheduleReconnect = function (delay) {
        var self = this;
        if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
        this.reconnectTimer = setTimeout(function () { self.connect(); }, delay);
    };

    VideoWebSocketManager.prototype.disconnect = function () {
        this.closedByUser = true;
        if (this.reconnectTimer) { clearTimeout(this.reconnectTimer); this.reconnectTimer = null; }
        if (this.ws) { try { this.ws.close(); } catch (e) {} this.ws = null; }
        this.connected = false;
    };

    window.VideoWebSocketManager = VideoWebSocketManager;
    console.log('[VideoWebSocketManager] Class registered');
})(window);
