/**
 * ActionTracker - Local action tracking system
 * Prevents WebSocket messages from overriding local user actions
 */
(function(window) {
    'use strict';
    
    window.ActionTracker = {
        // Recent local actions tracking
        recentLocalActions: {
            playPause: []
        },
        
        // Configuration for action timeouts
        config: {
            playPauseTimeout: 3000
        },
        
        /**
         * Record a local action
         * @param {string} actionType - Type of action (playPause, etc.)
         */
        recordAction: function(actionType) {
            const now = Date.now();
            const actions = this.recentLocalActions[actionType];
            
            if (actions) {
                actions.push(now);
                
                // Keep only recent actions (prevent memory leak)
                if (actions.length > 10) {
                    actions.shift();
                }
                
                window.Helpers.log(`ActionTracker recorded ${actionType} action at ${now}`);
            } else {
                window.Helpers.log(`ActionTracker: Unknown action type: ${actionType}`);
            }
        },
        
        /**
         * Check if WebSocket message should be skipped due to recent local action
         * @param {string} actionType - Type of action to check
         * @param {Object} messagePayload - WebSocket message payload
         * @returns {boolean} True if message should be skipped
         */
        shouldSkipWebSocketMessage: function(actionType, messagePayload) {
            const actions = this.recentLocalActions[actionType];
            
            if (!actions || actions.length === 0) {
                return false;
            }
            
            const now = Date.now();
            const timeout = this.config[actionType.toLowerCase() + 'Timeout'] || 3000;
            
            // Find most recent action within timeout window
            const mostRecentAction = Math.max(...actions);
            const timeSinceAction = now - mostRecentAction;
            
            if (timeSinceAction < timeout) {
                window.Helpers.log(`ActionTracker skipping WebSocket ${actionType} message - ${timeSinceAction}ms since local action`);
                return true;
            }
            
            // Clean up old actions
            this.recentLocalActions[actionType] = actions.filter(timestamp => (now - timestamp) < timeout);
            return false;
        },
        
        /**
         * Add new action type for tracking
         * @param {string} actionType - New action type
         * @param {number} timeout - Timeout in milliseconds
         */
        addActionType: function(actionType, timeout) {
            if (!this.recentLocalActions[actionType]) {
                this.recentLocalActions[actionType] = [];
                this.config[actionType.toLowerCase() + 'Timeout'] = timeout;
                window.Helpers.log(`ActionTracker added action type: ${actionType} with timeout: ${timeout}ms`);
            }
        },
        
        /**
         * Remove action type from tracking
         * @param {string} actionType - Action type to remove
         */
        removeActionType: function(actionType) {
            if (this.recentLocalActions[actionType]) {
                delete this.recentLocalActions[actionType];
                delete this.config[actionType.toLowerCase() + 'Timeout'];
                window.Helpers.log(`ActionTracker removed action type: ${actionType}`);
            }
        },
        
        /**
         * Clear all actions for a specific type
         * @param {string} actionType - Action type to clear
         */
        clearActions: function(actionType) {
            if (this.recentLocalActions[actionType]) {
                this.recentLocalActions[actionType] = [];
                window.Helpers.log(`ActionTracker cleared actions for type: ${actionType}`);
            }
        },
        
        /**
         * Clear all tracked actions
         */
        clearAllActions: function() {
            for (const actionType in this.recentLocalActions) {
                this.recentLocalActions[actionType] = [];
            }
            window.Helpers.log('ActionTracker cleared all actions');
        },
        
        /**
         * Get recent actions for a type
         * @param {string} actionType - Action type
         * @returns {Array} Array of action timestamps
         */
        getRecentActions: function(actionType) {
            return this.recentLocalActions[actionType] ? [...this.recentLocalActions[actionType]] : [];
        },
        
        /**
         * Get all action types being tracked
         * @returns {Array} Array of action type names
         */
        getActionTypes: function() {
            return Object.keys(this.recentLocalActions);
        },
        
        /**
         * Get configuration for action type
         * @param {string} actionType - Action type
         * @returns {Object} Configuration object
         */
        getActionConfig: function(actionType) {
            const timeout = this.config[actionType.toLowerCase() + 'Timeout'];
            return {
                actionType: actionType,
                timeout: timeout || 3000,
                recentActions: this.getRecentActions(actionType)
            };
        },
        
        /**
         * Update timeout for action type
         * @param {string} actionType - Action type
         * @param {number} timeout - New timeout in milliseconds
         */
        updateTimeout: function(actionType, timeout) {
            this.config[actionType.toLowerCase() + 'Timeout'] = timeout;
            window.Helpers.log(`ActionTracker updated timeout for ${actionType} to ${timeout}ms`);
        },
        
        /**
         * Get tracking status for debugging
         * @returns {Object} Current tracking status
         */
        getStatus: function() {
            const status = {
                actionTypes: this.getActionTypes(),
                config: {...this.config},
                recentActions: {}
            };
            
            for (const actionType of status.actionTypes) {
                status.recentActions[actionType] = {
                    count: this.recentLocalActions[actionType].length,
                    actions: [...this.recentLocalActions[actionType]]
                };
            }
            
            return status;
        },
        
        /**
         * Initialize action tracker
         */
        init: function() {
            window.Helpers.log('ActionTracker initialized with action types:', Object.keys(this.recentLocalActions));
            
            // Listen for cleanup events
            window.addEventListener('beforeunload', () => {
                window.Helpers.log('ActionTracker cleaning up before page unload');
                this.clearAllActions();
            });
        }
    };
    
    // Auto-initialize
    window.ActionTracker.init();
    
})(window);