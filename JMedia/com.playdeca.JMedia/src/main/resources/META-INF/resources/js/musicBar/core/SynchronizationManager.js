/**
 * SynchronizationManager - Centralized race condition protection
 * Handles all locking, sequencing, queuing, and coordination mechanisms
 */
(function(window) {
    'use strict';
    
    window.SynchronizationManager = {
        // Lock mechanisms to prevent overlapping operations
        locks: {},
        
        // Sequence tracking for operation ordering
        sequences: {
            audio: 0,
            websocket: 0,
            dom: 0
        },
        
        // Queues for various operations
        queues: {
            messages: [],
            dom: []
        },
        
        // Operation flags for UI coordination
        flags: {
            isProcessingMessage: false,
            isProcessingDOMOperations: false,
            isUpdatingAudioSource: false,
            draggingSeconds: false,
            draggingVolume: false
        },
        
        // Active operation tracking
        activeAudioOperation: null,
        
        /**
         * Execute an atomic operation with locking and sequencing
         * @param {string} operationType - Type of operation (audio, websocket, etc.)
         * @param {string} profileId - Profile ID for lock scoping
         * @param {Function} callback - Function to execute atomically
         * @returns {*} Result of callback execution
         */
        executeAtomic: function(operationType, profileId, callback) {
            const lockKey = `${operationType}_${profileId}`;
            
            // Check if operation is already in progress
            if (this.locks[lockKey]) {
                console.log(`[SyncManager] ${operationType} already in progress for ${profileId}`);
                return false;
            }
            
            // Acquire lock
            this.locks[lockKey] = true;
            const operationId = `${operationType}_${++this.sequences[operationType]}`;
            
            console.log(`[SyncManager] Starting ${operationType} operation ${operationId} for ${profileId}`);
            
            try {
                const result = callback(operationId);
                return result;
            } catch (error) {
                console.error(`[SyncManager] Error in ${operationType} operation ${operationId}:`, error);
                throw error;
            } finally {
                // Always release lock
                delete this.locks[lockKey];
                console.log(`[SyncManager] Completed ${operationType} operation ${operationId}`);
            }
        },
        
        /**
         * Cancel and track active audio operations
         * @param {string} newOperationId - ID of the new operation
         * @returns {string|null} Previous operation ID that was cancelled
         */
        setActiveAudioOperation: function(newOperationId) {
            const previousOperation = this.activeAudioOperation;
            if (previousOperation) {
                console.log(`[SyncManager] Canceling previous audio operation ${previousOperation} for ${newOperationId}`);
            }
            this.activeAudioOperation = newOperationId;
            return previousOperation;
        },
        
        /**
         * Clear active audio operation
         * @param {string} expectedOperationId - Operation ID that should be active
         */
        clearActiveAudioOperation: function(expectedOperationId) {
            if (this.activeAudioOperation === expectedOperationId) {
                this.activeAudioOperation = null;
                console.log(`[SyncManager] Cleared active audio operation ${expectedOperationId}`);
            } else {
                console.log(`[SyncManager] Ignoring clear request for ${expectedOperationId}, active is ${this.activeAudioOperation}`);
            }
        },
        
        /**
         * Enqueue WebSocket message with priority processing
         * @param {Object} message - WebSocket message to enqueue
         */
        enqueueMessage: function(message) {
            message.sequenceNumber = ++this.sequences.websocket;
            this.queues.messages.push(message);
             
            this.processMessageQueue();
        },
        
        /**
         * Process message queue with priority ordering
         */
        processMessageQueue: function() {
            if (this.flags.isProcessingMessage || this.queues.messages.length === 0) {
                return;
            }
            
            this.flags.isProcessingMessage = true;
            
            // Sort messages with priority: state messages first, then others by sequence
            this.queues.messages.sort((a, b) => {
                if (a.type === 'state' && b.type !== 'state') {
                    return -1;
                }
                if (b.type === 'state' && a.type !== 'state') {
                    return 1;
                }
                return a.sequenceNumber - b.sequenceNumber;
            });
            
            const processNextMessage = () => {
                if (this.queues.messages.length === 0) {
                    this.flags.isProcessingMessage = false;
                    return;
                }
                
                const message = this.queues.messages.shift();
                
                try {
                    // Emit event for message processing
                    window.dispatchEvent(new CustomEvent('processWebSocketMessage', {
                        detail: { message: message }
                    }));
                } catch (error) {
                    console.error('[SyncManager] Error processing queued message:', error);
                }
                
                // Process next message with minimal delay
                setTimeout(processNextMessage, 0);
            };
            
            processNextMessage();
        },
        
        /**
         * Enqueue DOM operation with priority processing
         * @param {Object} operation - DOM operation to enqueue
         */
        enqueueDOMOperation: function(operation) {
            operation.sequenceNumber = ++this.sequences.dom;
            this.queues.dom.push(operation);
            
            console.log(`[SyncManager] Enqueued DOM operation ${operation.sequenceNumber} of type ${operation.type}`);
            this.processDOMQueue();
        },
        
        /**
         * Process DOM queue with requestAnimationFrame
         */
        processDOMQueue: function() {
            if (this.flags.isProcessingDOMOperations || this.queues.dom.length === 0) {
                return;
            }
            
            this.flags.isProcessingDOMOperations = true;
            
            const processOperations = () => {
                if (this.queues.dom.length === 0) {
                    this.flags.isProcessingDOMOperations = false;
                    return;
                }
                
                const operation = this.queues.dom.shift();
                
                try {
                    // Execute DOM operation
                    if (operation.callback && typeof operation.callback === 'function') {
                        operation.callback();
                    }
                } catch (error) {
                    console.error('[SyncManager] Error executing DOM operation:', error);
                }
                
                // Schedule next operation in next animation frame
                requestAnimationFrame(processOperations);
            };
            
            requestAnimationFrame(processOperations);
        },
        
        /**
         * Set operation flag
         * @param {string} flagName - Name of the flag
         * @param {boolean} value - Flag value
         */
        setFlag: function(flagName, value) {
            if (this.flags.hasOwnProperty(flagName)) {
                this.flags[flagName] = value;
                console.log(`[SyncManager] Set flag ${flagName} to ${value}`);
            } else {
                console.warn(`[SyncManager] Unknown flag: ${flagName}`);
            }
        },
        
        /**
         * Get operation flag value
         * @param {string} flagName - Name of the flag
         * @returns {boolean} Flag value
         */
        getFlag: function(flagName) {
            if (this.flags.hasOwnProperty(flagName)) {
                return this.flags[flagName];
            }
            return false;
        },
        
        /**
         * Clear stuck locks (emergency cleanup)
         * @param {string} operationType - Type of operation to clear
         * @param {string} profileId - Profile ID (optional)
         */
        clearStuckLocks: function(operationType, profileId) {
            if (profileId) {
                const lockKey = `${operationType}_${profileId}`;
                if (this.locks[lockKey]) {
                    console.warn(`[SyncManager] Clearing stuck lock ${lockKey}`);
                    delete this.locks[lockKey];
                }
            } else {
                // Clear all locks for this operation type
                const keysToDelete = Object.keys(this.locks).filter(key => key.startsWith(operationType + '_'));
                keysToDelete.forEach(key => {
                    console.warn(`[SyncManager] Clearing stuck lock ${key}`);
                    delete this.locks[key];
                });
            }
        },
        
        /**
         * Get current synchronization status (for debugging)
         * @returns {Object} Current status
         */
        getStatus: function() {
            return {
                locks: {...this.locks},
                sequences: {...this.sequences},
                queueLengths: {
                    messages: this.queues.messages.length,
                    dom: this.queues.dom.length
                },
                flags: {...this.flags},
                activeAudioOperation: this.activeAudioOperation
            };
        },
        
        /**
         * Initialize synchronization manager
         */
        init: function() {
            console.log('[SyncManager] Initialized');
            
            // Listen for cleanup events
            window.addEventListener('beforeunload', () => {
                console.log('[SyncManager] Cleaning up before page unload');
                this.flags.isProcessingMessage = false;
                this.flags.isProcessingDOMOperations = false;
                this.activeAudioOperation = null;
            });
        }
    };
    
    // Auto-initialize
    SynchronizationManager.init();
    
})(window);