/**
 * TimeController - Time slider control and seek functionality
 * Handles time slider events and seek operations
 */
(function(window) {
    'use strict';
    
    window.TimeController = {
        /**
         * Initialize time controller
         */
        init: function() {
            this.bindTimeSlider();
            window.Helpers.log('TimeController initialized');
        },
        
        /**
         * Bind time slider events
         */
        bindTimeSlider: function() {
            const slider = document.querySelector('input[name="seconds"]');
            if (!slider) {
                window.Helpers.log('TimeController: Time slider not found');
                return;
            }
            
            // Mouse down/touch start
            slider.onmousedown = slider.ontouchstart = () => {
                SynchronizationManager.setFlag('draggingSeconds', true);
                window.Helpers.log('TimeController: Time drag started');
            };
            
            // Mouse up/touch end
            slider.onmouseup = slider.ontouchend = () => {
                SynchronizationManager.setFlag('draggingSeconds', false);
                this.handleSeek(parseInt(slider.value, 10));
                window.Helpers.log('TimeController: Time drag ended');
            };
            
            // Input change
            slider.oninput = (e) => {
                this.handleTimeChange(parseInt(e.target.value));
            };
            
            window.Helpers.log('TimeController: Time slider bound');
        },
        
        /**
         * Handle time change
         * @param {number} newTime - New time in seconds
         * @param {number} timeout - Timeout for server sync (0 for immediate)
         */
        handleTimeChange: function(newTime, timeout = 0) {
            // Update state immediately for UI responsiveness
            if (window.StateManager) {
                window.StateManager.updateState({ currentTime: newTime }, 'timeController');
            }
            
            // Request audio time update
            if (window.AudioEngine) {
                window.AudioEngine.setTime(newTime);
            }
            
            // Request UI update
            window.dispatchEvent(new CustomEvent('requestUIUpdate', { detail: { source: 'timeController' } }));
            
            // Send to server with debounce
            if (timeout > 0) {
                setTimeout(() => {
                    this.sendSeekToServer(newTime);
                }, timeout);
            } else {
                this.sendSeekToServer(newTime);
            }
            
            window.Helpers.log('TimeController: Time changed to', newTime, 'seconds');
        },
        
        /**
         * Handle seek operation
         * @param {number} newTime - New time in seconds
         * @param {number} timeout - Timeout for server sync
         */
        handleSeek: function(newTime, timeout = 0) {
            // Immediate UI update
            if (window.StateManager) {
                window.StateManager.updateState({ currentTime: newTime }, 'timeController');
            }
            
            // Request audio seek
            if (window.AudioEngine) {
                window.AudioEngine.setTime(newTime);
            }
            
            // Request UI update
            window.dispatchEvent(new CustomEvent('requestUIUpdate', { detail: { source: 'timeController' } }));
            
            // Send to server
            setTimeout(() => {
                this.sendSeekToServer(newTime);
            }, timeout);
            
            window.Helpers.log('TimeController: Seeked to', newTime, 'seconds');
        },
        
        /**
         * Send seek to server
         * @param {number} time - Time in seconds
         */
        sendSeekToServer: function(time) {
            // Send WebSocket message
            window.dispatchEvent(new CustomEvent('sendWebSocketMessage', {
                detail: {
                    type: 'seek',
                    payload: { value: time }
                }
            }));
            
            window.Helpers.log('TimeController: Seek sent to server:', time, 'seconds');
        },
        
        /**
         * Update slider from state
         * @param {number} currentTime - Current time in seconds
         * @param {number} duration - Total duration in seconds
         */
        updateSliderFromState: function(currentTime, duration) {
            if (SynchronizationManager.getFlag('draggingSeconds')) {
                // Don't update slider while user is dragging
                return;
            }
            
            const slider = document.querySelector('input[name="seconds"]');
            if (slider) {
                // Update slider value
                slider.value = currentTime;
                
                // Update max and progress
                slider.max = duration || 0;
                const progress = duration > 0 ? (currentTime / duration) * 100 : 0;
                slider.style.setProperty('--progress-value', `${progress}%`);
            }
        },
        
        /**
         * Format time for display
         * @param {number} seconds - Time in seconds
         * @returns {string} Formatted time string
         */
        formatTime: function(seconds) {
            return window.Helpers.formatTime(seconds);
        },
        
        /**
         * Get current time from audio
         * @returns {number} Current time in seconds
         */
        getCurrentTime: function() {
            return window.AudioEngine ? window.AudioEngine.getCurrentTime() : 0;
        },
        
        /**
         * Get duration from audio
         * @returns {number} Duration in seconds
         */
        getDuration: function() {
            return window.AudioEngine ? window.AudioEngine.getDuration() : 0;
        }
    };
    
    // Auto-initialize when dependencies are available
    if (window.Helpers && window.SynchronizationManager && window.StateManager && window.AudioEngine) {
        window.TimeController.init();
        
        // Listen for state changes to update slider
        window.addEventListener('statePropertyChanged', (e) => {
            if (e.detail.property === 'currentTime' || e.detail.property === 'duration') {
                const state = window.StateManager.getState();
                window.TimeController.updateSliderFromState(state.currentTime, state.duration);
            }
        });
    } else {
        // Wait for dependencies
        const checkDeps = () => {
            if (window.Helpers && window.SynchronizationManager && window.StateManager && window.AudioEngine) {
                window.TimeController.init();
            } else {
                setTimeout(checkDeps, 50);
            }
        };
        setTimeout(checkDeps, 50);
    }
    
})(window);