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
            this.initializeSliderFromState();
            this.applyInitialVolume();
            window.Helpers.log('VolumeController initialized');
        },

        /**
         * Make the engine agree with the persisted volume.
         *
         * AudioEngine picks its own default (0.8) when it is constructed, and the
         * drag handlers only exist once this module runs, so a volume chosen before
         * that point was never applied to the audio. DeviceManager wins over
         * StateManager, matching the precedence AudioEngine.initWebAudio uses.
         */
        applyInitialVolume: function() {
            let vol = null;
            if (window.DeviceManager && typeof window.DeviceManager.hasDeviceVolume === 'function'
                    && window.DeviceManager.hasDeviceVolume()) {
                vol = window.DeviceManager.getDeviceVolume();
            } else if (window.StateManager) {
                vol = window.StateManager.getProperty('volume');
            }
            if (typeof vol !== 'number' || isNaN(vol)) return;

            if (window.AudioEngine && typeof window.AudioEngine.setVolume === 'function') {
                window.AudioEngine.setVolume(window.Helpers.clamp(vol, 0, 1), 'volumeController:init');
            }
            this.updateSliderFromState(vol);
        },
        
        /**
         * Initialize slider position from current state
         */
        initializeSliderFromState: function() {
            if (window.StateManager) {
                const state = window.StateManager.getState();
                if (state && typeof state.volume === 'number') {
                    this.updateSliderFromState(state.volume);
                }
            }
        },
        
        /**
         * Bind volume slider events.
         *
         * Bound by DELEGATION on the document, not on the element itself. The music
         * bundle is injected from <head> (music-bundle-loader.js) while
         * #musicVolumeProgressBar sits near the bottom of <body>, so this module
         * regularly executes before the slider exists. Binding the element
         * directly then did nothing, left the control inert for the whole page
         * session and only logged "Volume slider not found" - and nothing calls
         * init() a second time, so it never recovered. Delegation installs the
         * handler once and works whenever the element is inserted.
         */
        bindVolumeSlider: function() {
            if (this._handlersBound) return;
            this._handlersBound = true;

            const sliderFrom = (node) => (node && typeof node.closest === 'function')
                ? node.closest('#musicVolumeProgressBar') : null;

            // Local only during the drag; the server sync happens on release.
            document.addEventListener('input', (e) => {
                const slider = sliderFrom(e.target);
                if (!slider) return;
                this.applySliderValue(parseInt(slider.value, 10));
            }, true);

            const startDrag = (e) => {
                if (sliderFrom(e.target)) SynchronizationManager.setFlag('draggingVolume', true);
            };
            document.addEventListener('mousedown', startDrag, true);
            document.addEventListener('touchstart', startDrag, { capture: true, passive: true });

            const endDrag = (e) => {
                const slider = sliderFrom(e.target);
                if (!slider) return;
                SynchronizationManager.setFlag('draggingVolume', false);
                this.handleVolumeChange(parseInt(slider.value, 10));
            };
            document.addEventListener('mouseup', endDrag, true);
            document.addEventListener('touchend', endDrag, { capture: true, passive: true });

            // Ctrl+wheel and Ctrl+ArrowUp/Down (DesktopAdapter) dispatch this
            // request. Nothing listened, so those volume controls did nothing at all.
            window.addEventListener('requestVolumeChange', (e) => this.handleVolumeRequest(e.detail || {}));

            window.Helpers.log('VolumeController: Volume slider bound (delegated)');
        },

        /**
         * Apply one slider position (0-100) locally: device memory, state, audio.
         */
        applySliderValue: function(sliderValue) {
            if (!isFinite(sliderValue)) {
                console.warn('[VolumeController] ignoring non-numeric slider value', sliderValue);
                return;
            }
            const vol = window.Helpers.volume.calculateExponentialVolume(sliderValue);

            if (window.DeviceManager) {
                window.DeviceManager.saveDeviceVolume(vol);
            }
            if (window.StateManager) {
                window.StateManager.updateState({ volume: vol }, 'volumeController');
            }
            if (window.AudioEngine) {
                window.AudioEngine.setVolume(vol, 'volumeController');
            }

            const slider = document.getElementById('musicVolumeProgressBar');
            if (slider) slider.style.setProperty('--slider-progress', `${sliderValue}%`);

            this._recordDiag('slider', sliderValue, vol);
            window.Helpers.log('VolumeController: Volume changed locally to', sliderValue, '(', vol, ')');
        },

        /**
         * Handle requestVolumeChange: a numeric 0-1 gain, or 'up' / 'down'.
         */
        handleVolumeRequest: function(detail) {
            const step = 5; // percent of slider travel per key press / wheel notch
            const current = window.StateManager ? window.StateManager.getProperty('volume') : 0.8;
            const currentSlider = Math.round(window.Helpers.volume.calculateLinearSliderValue(current));
            let sliderValue;

            if (detail.volume === 'up') {
                sliderValue = currentSlider + step;
            } else if (detail.volume === 'down') {
                sliderValue = currentSlider - step;
            } else if (typeof detail.volume === 'number') {
                sliderValue = Math.round(window.Helpers.volume.calculateLinearSliderValue(
                    window.Helpers.clamp(detail.volume, 0, 1)));
            } else {
                window.Helpers.log('VolumeController: ignoring requestVolumeChange with unknown volume', detail.volume);
                return;
            }

            sliderValue = window.Helpers.clamp(sliderValue, 0, 100);
            const slider = document.getElementById('musicVolumeProgressBar');
            if (slider) slider.value = sliderValue;
            this.handleVolumeChange(sliderValue);
        },

        /**
         * Record one applied volume step so a "the volume did not change" report can
         * be read back instead of guessed at. Read with window.jmediaVolumeDiag().
         */
        _recordDiag: function(source, sliderValue, gain) {
            const engine = window.AudioEngine;
            const gainNode = engine && typeof engine.getActiveGain === 'function' ? engine.getActiveGain() : null;
            const element = engine && typeof engine.getActivePlayer === 'function' ? engine.getActivePlayer() : null;

            this._diag = this._diag || [];
            this._diag.push({
                at: new Date().toISOString(),
                source: source,
                slider: sliderValue,
                gain: Math.round((gain || 0) * 1000) / 1000,
                engineGain: gainNode && gainNode.gain ? Math.round(gainNode.gain.value * 1000) / 1000 : null,
                elementVolume: element ? Math.round(element.volume * 1000) / 1000 : null,
                crossfading: !!(engine && engine._isCrossfading)
            });
            if (this._diag.length > 50) this._diag.shift();
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

            this._recordDiag('handleVolumeChange', sliderValue, vol);
            
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
            
            const slider = document.getElementById('musicVolumeProgressBar');
            if (slider) {
                const sliderValue = window.Helpers.volume.calculateLinearSliderValue(volume);
                slider.value = sliderValue;
                
                // Update progress bar visual
                const progress = (sliderValue / 100) * 100;
                slider.style.setProperty('--slider-progress', `${progress}%`);
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
        let attempts = 0;
        const checkDeps = () => {
            attempts++;
            if (window.Helpers && window.SynchronizationManager && window.StateManager && window.AudioEngine && window.DeviceManager) {
                window.VolumeController.init();
            } else if (attempts >= 25) {
                const missing = [];
                if (!window.Helpers) missing.push('Helpers');
                if (!window.SynchronizationManager) missing.push('SynchronizationManager');
                if (!window.StateManager) missing.push('StateManager');
                if (!window.AudioEngine) missing.push('AudioEngine');
                if (!window.DeviceManager) missing.push('DeviceManager');
                console.error('[VolumeController.js] gave up waiting for dependencies: ' + missing.join(', '));
            } else {
                setTimeout(checkDeps, 50);
            }
        };
        setTimeout(checkDeps, 50);
    }
    
    // Read with window.jmediaVolumeDiag() to see every volume step that was
    // applied, with the gain/element level it produced.
    window.jmediaVolumeDiag = () => (window.VolumeController._diag || []).slice();

})(window);