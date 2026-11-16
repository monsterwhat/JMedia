/**
 * Mobile Bridge - Compatibility layer for migrating mobile.js to new architecture
 * Provides backward compatibility while using new responsive modules
 */
(function(window) {
    'use strict';

    window.MobileBridge = {
        // Bridge state tracking
        initialized: false,
        _initialized: false,
        legacyMode: true,

        // Legacy mobile.js instance (for gradual migration)
        legacyMobile: null,

        /**
         * Initialize mobile bridge
         */
        init: function() {
            if (this._initialized) return;

            this.legacyMode = true;
            this.detectMode();
            this.setupBridge();

            this._initialized = true;
            this.initialized = true;
            window.Helpers.log('MobileBridge initialized in legacy mode');
        },

        /**
         * Detect if we should use legacy mode or responsive mode
         */
        detectMode: function() {
            this.legacyMode = true;
        },

        /**
         * Setup bridge layer
         */
        setupBridge: function() {
            if (this.legacyMode) {
                this.setupLegacyBridge();
            }
        },

        /**
         * Setup legacy bridge (keep old mobile.js)
         */
        setupLegacyBridge: function() {
            // Keep existing mobile.js behavior
            if (window.jmediaMobile) {
                this.legacyMobile = window.jmediaMobile;
            }
        },

        /**
         * Get bridge state
         */
        getBridgeState: function() {
            return {
                initialized: this.initialized,
                legacyMode: this.legacyMode,
                hasResponsiveModules: !!window.ResponsivePlayer,
                deviceType: window.DeviceManager?.getDeviceType() || 'unknown',
                screenCategory: window.DeviceManager?.getScreenCategory() || 'unknown'
            };
        }
    };

    // Auto-initialize
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => window.MobileBridge.init());
    } else {
        window.MobileBridge.init();
    }

})(window);