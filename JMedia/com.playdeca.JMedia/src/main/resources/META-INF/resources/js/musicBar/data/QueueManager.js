/**
 * QueueManager - Queue management and change detection
 * Handles queue change detection and queue-related events
 */
(function(window) {
    'use strict';
    
    window.QueueManager = {
        // Last known queue for change detection
        lastKnownQueue: [],
        
        /**
         * Initialize queue manager
         */
        init: function() {
            this.setupEventListeners();
            window.Helpers.log('QueueManager initialized');
        },
        
        /**
         * Set up event listeners
         */
        setupEventListeners: function() {
            // Listen for queue updates from state
            window.addEventListener('statePropertyChanged', (e) => {
                if (e.detail.property === 'cue') {
                    this.handleQueueChange(e.detail.oldValue, e.detail.newValue);
                }
            });
            
            window.Helpers.log('QueueManager: Event listeners configured');
        },
        
        /**
         * Handle queue change
         * @param {Array} oldQueue - Previous queue
         * @param {Array} newQueue - New queue
         */
        handleQueueChange: function(oldQueue, newQueue) {
            const queueChanged = this.hasQueueChanged(newQueue, oldQueue);
            const queueLengthChanged = (oldQueue?.length || 0) !== (newQueue?.length || 0);
            
            if (queueChanged || queueLengthChanged) {
                this.lastKnownQueue = [...newQueue];
                
                window.Helpers.log('QueueManager: Queue changed', {
                    queueChanged,
                    queueLengthChanged,
                    oldLength: oldQueue?.length || 0,
                    newLength: newQueue?.length || 0
                });
                
                // Emit queue change event
                window.dispatchEvent(new CustomEvent('queueChanged', {
                    detail: {
                        queueSize: newQueue?.length || 0,
                        queueChanged: queueChanged,
                        queueLengthChanged: queueLengthChanged,
                        oldQueue: oldQueue,
                        newQueue: newQueue
                    }
                }));
            }
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
         * Update queue in state
         * @param {Array} queue - New queue data
         */
        updateQueue: function(queue) {
            if (window.StateManager) {
                window.StateManager.updateState({ cue: queue }, 'queueManager');
            }
        },
        
        /**
         * Get queue change status
         * @returns {Object} Current change tracking status
         */
        getQueueStatus: function() {
            return {
                lastKnownQueue: [...this.lastKnownQueue],
                lastKnownQueueLength: this.lastKnownQueue.length
            };
        },
        
        /**
         * Clear queue tracking
         */
        clearTracking: function() {
            this.lastKnownQueue = [];
            window.Helpers.log('QueueManager: Queue tracking cleared');
        },
        
        /**
         * Get queue summary
         * @returns {Object} Queue summary
         */
        getQueueSummary: function() {
            return {
                hasLastKnown: this.lastKnownQueue.length > 0,
                lastKnownLength: this.lastKnownQueue.length,
                lastKnownQueue: this.lastKnownQueue
            };
        }
    };
    
    // Auto-initialize when dependencies are available
    if (window.Helpers && window.StateManager) {
        window.QueueManager.init();
    } else {
        // Wait for dependencies
        const checkDeps = () => {
            if (window.Helpers && window.StateManager) {
                window.QueueManager.init();
            } else {
                setTimeout(checkDeps, 50);
            }
        };
        setTimeout(checkDeps, 50);
    }
    
})(window);