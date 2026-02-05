/**
 * VolumeController - Volume slider control with exponential scaling
 * Handles volume slider events and per-device volume management
 */
(function(window) {
    'use strict';
    
    window.VolumeController = {
        /**
         * Initialize volume controller
         */
        init: function() {
            this.bindVolumeSlider();
            window.Helpers.log('VolumeController initialized');
        },
        
        /**
         * Bind volume slider events
         */
        bindVolumeSlider: function() {
            const slider = document.querySelector('input[name="level"]');
            if (!slider) {
                window.Helpers.log('VolumeController: Volume slider not found');
                return;
            }
            
            // Mouse down/touch start
            slider.onmousedown = slider.ontouchstart = () => {
                SynchronizationManager.setFlag('draggingVolume', true);
                window.Helpers.log('VolumeController: Volume drag started');
            };
            
            // Mouse up/touch end
            slider.onmouseup = slider.ontouchend = () => {
                SynchronizationManager.setFlag('draggingVolume', false);
                this.handleVolumeChange(parseInt(slider.value, 10));
                window.Helpers.log('VolumeController: Volume drag ended');
            };
            
            // Input change
            slider.oninput = (e) => {
                this.handleVolumeChange(parseInt(e.target.value, 10));
            };
            
            window.Helpers.log('VolumeController: Volume slider bound');
        },
        
        /**
         * Handle volume change
         * @param {number} sliderValue - Slider value (0-100)
         * @param {number} timeout - Timeout for server sync (0 for immediate)
         */
        handleVolumeChange: function(sliderValue, timeout = 0) {
            const vol = window.Helpers.volume.calculateExponentialVolume(sliderValue);
            
            // Per-device volume override and remember in localStorage
            if (window.DeviceManager) {
                window.DeviceManager.saveDeviceVolume(vol);
            }
            
            // Update state
            if (window.StateManager) {
                window.StateManager.updateState({ volume: vol }, 'volumeController');
            }
            
            // Apply to audio
            if (window.AudioEngine) {
                window.AudioEngine.setVolume(vol, 'volumeController');
            }
            
            // Send to server with debounce
            if (timeout > 0) {
                setTimeout(() => {
                    this.sendVolumeToServer(vol);
                }, timeout);
            } else {
                this.sendVolumeToServer(vol);
            }
            
            window.Helpers.log('VolumeController: Volume changed to', sliderValue, '(', vol, ')');
        },
        
        /**
         * Send volume to server
         * @param {number} volume - Volume level (0-1)
         */
        sendVolumeToServer: function(volume) {
            // Send WebSocket message
            window.dispatchEvent(new CustomEvent('sendWebSocketMessage', {
                detail: {
                    type: 'volume',
                    payload: { value: volume }
                }
            }));
            
            window.Helpers.log('VolumeController: Volume sent to server:', volume);
        },
        
        /**
         * Update slider from state
         * @param {number} volume - Current volume (0-1)
         */
        updateSliderFromState: function(volume) {
            if (SynchronizationManager.getFlag('draggingVolume')) {
                // Don't update slider while user is dragging
                return;
            }
            
            const slider = document.querySelector('input[name="level"]');
            if (slider) {
                const sliderValue = window.Helpers.volume.calculateLinearSliderValue(volume);
                slider.value = sliderValue;
                
                // Update progress bar visual
                const progress = (sliderValue / 100) * 100;
                slider.style.setProperty('--progress-value', `${progress}%`);
            }
        },
        
        /**
         * Get exponential volume from slider
         * @param {number} sliderValue - Slider value (0-100)
         * @returns {number} Exponential volume (0-1)
         */
        getExponentialVolume: function(sliderValue) {
            return window.Helpers.volume.calculateExponentialVolume(sliderValue);
        },
        
        /**
         * Get linear slider value from exponential volume
         * @param {number} exponentialVol - Exponential volume (0-1)
         * @returns {number} Linear slider value (0-100)
         */
        getLinearSliderValue: function(exponentialVol) {
            return window.Helpers.volume.calculateLinearSliderValue(exponentialVol);
        }
    };
    
    // Auto-initialize when dependencies are available
    if (window.Helpers && window.SynchronizationManager && window.StateManager && window.AudioEngine && window.DeviceManager) {
        window.VolumeController.init();
        
        // Listen for state changes to update slider
        window.addEventListener('statePropertyChanged', (e) => {
            if (e.detail.property === 'volume') {
                window.VolumeController.updateSliderFromState(e.detail.newValue);
            }
        });
    } else {
        // Wait for dependencies
        const checkDeps = () => {
            if (window.Helpers && window.SynchronizationManager && window.StateManager && window.AudioEngine && window.DeviceManager) {
                window.VolumeController.init();
            } else {
                setTimeout(checkDeps, 50);
            }
        };
        setTimeout(checkDeps, 50);
    }
    
})(window);