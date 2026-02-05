/**
 * DeviceManager - Device identification and per-device settings
 * Handles device ID generation and per-device volume management
 */
(function(window) {
    'use strict';
    
    window.DeviceManager = {
        // Device identification
        deviceId: null,
        
        // Per-device settings
        deviceVolumes: {},
        
        // Clock offsets for time synchronization
        clockOffsets: {},
        
        // Responsive device detection
        deviceType: null, // 'mobile', 'tablet', 'desktop'
        touchSupport: false,
        screenCategory: null, // 'small', 'medium', 'large'
        
        // Adaptive behavior settings
        adaptiveSettings: {},
        
    /**
     * Initialize device manager
     */
    init: function() {
        try {
            this.initializeDeviceId();
            this.loadDeviceVolume();
            this.detectDeviceCapabilities();
            window.Helpers.log('DeviceManager initialized with device ID:', this.deviceId);
        } catch (error) {
            window.Helpers.log('DeviceManager initialization error:', error);
            setTimeout(() => this.init(), 100); // Retry after delay
        }
    },
        
        /**
         * Initialize or generate device ID
         */
        initializeDeviceId: function() {
            this.deviceId = (typeof window !== 'undefined' && window.localStorage) ? 
                localStorage.getItem('musicDeviceId') : null;
            
            if (!this.deviceId) {
                this.deviceId = 'dev-' + Date.now() + '-' + Math.floor(Math.random() * 1000000);
                try {
                    if (window.localStorage) {
                        localStorage.setItem('musicDeviceId', this.deviceId);
                    }
                } catch (e) { 
                    // Ignore storage errors
                }
                window.Helpers.log('DeviceManager generated new device ID:', this.deviceId);
            } else {
                window.Helpers.log('DeviceManager using existing device ID:', this.deviceId);
            }
        },
        
        /**
         * Get current device ID
         * @returns {string} Device ID
         */
        getDeviceId: function() {
            return this.deviceId;
        },
        
        /**
         * Load per-device volume from localStorage
         */
        loadDeviceVolume: function() {
            if (!this.deviceId) {
                return;
            }
            
            try {
                const savedVol = window.localStorage ? 
                    window.localStorage.getItem(`musicDeviceVolume:${this.deviceId}`) : null;
                
                if (savedVol !== null && !isNaN(parseFloat(savedVol))) {
                    const v = parseFloat(savedVol);
                    if (isFinite(v)) {
                        this.deviceVolumes[this.deviceId] = v;
                        window.Helpers.log('DeviceManager loaded device volume:', v);
                    }
                }
            } catch (e) {
                // Ignore storage errors
            }
        },
        
        /**
         * Save per-device volume to localStorage
         * @param {number} volume - Volume level (0-1)
         */
        saveDeviceVolume: function(volume) {
            if (!this.deviceId) {
                return;
            }
            
            // Validate volume
            volume = window.Helpers.clamp(volume, 0, 1);
            
            this.deviceVolumes[this.deviceId] = volume;
            
            try {
                if (window.localStorage) {
                    window.localStorage.setItem(`musicDeviceVolume:${this.deviceId}`, String(volume));
                    window.Helpers.log('DeviceManager saved device volume:', volume);
                }
            } catch (e) {
                // Ignore write errors
            }
        },
        
        /**
         * Get per-device volume
         * @returns {number} Volume level (0-1) or null if not set
         */
        getDeviceVolume: function() {
            if (!this.deviceId) {
                return null;
            }
            return this.deviceVolumes[this.deviceId] || null;
        },
        
        /**
         * Check if device has a saved volume
         * @returns {boolean} True if device volume exists
         */
        hasDeviceVolume: function() {
            return this.deviceId && this.deviceVolumes.hasOwnProperty(this.deviceId);
        },
        
        /**
         * Set clock offset for a profile
         * @param {string} profileId - Profile ID
         * @param {number} offset - Clock offset in milliseconds
         */
        setClockOffset: function(profileId, offset) {
            this.clockOffsets[profileId] = offset;
            window.Helpers.log('DeviceManager set clock offset for profile', profileId, ':', offset);
        },
        
        /**
         * Get clock offset for a profile
         * @param {string} profileId - Profile ID
         * @returns {number} Clock offset in milliseconds
         */
        getClockOffset: function(profileId) {
            return this.clockOffsets[profileId] || 0;
        },
        
        /**
         * Clear clock offset for a profile
         * @param {string} profileId - Profile ID
         */
        clearClockOffset: function(profileId) {
            delete this.clockOffsets[profileId];
            window.Helpers.log('DeviceManager cleared clock offset for profile:', profileId);
        },
        
        /**
         * Get all clock offsets
         * @returns {Object} All clock offsets
         */
        getAllClockOffsets: function() {
            return {...this.clockOffsets};
        },
        
        /**
         * Reset all device data (for testing/debugging)
         */
        reset: function() {
            this.deviceVolumes = {};
            this.clockOffsets = {};
            window.Helpers.log('DeviceManager reset all device data');
        },
        
        /**
         * Enhanced device detection for responsive UI
         */
        detectDeviceCapabilities: function() {
            this.touchSupport = 'ontouchstart' in window || navigator.maxTouchPoints > 0;
            this.deviceType = this.calculateDeviceType();
            this.screenCategory = this.calculateScreenCategory();
            
            window.Helpers.log('Device capabilities detected:', {
                type: this.deviceType,
                touch: this.touchSupport,
                screen: this.screenCategory,
                userAgent: navigator.userAgent.substring(0, 50) + '...'
            });
            
            // Emit device detection event with delay to allow other modules to initialize
            setTimeout(() => {
                window.dispatchEvent(new CustomEvent('deviceCapabilitiesDetected', {
                    detail: {
                        deviceType: this.deviceType,
                        touchSupport: this.touchSupport,
                        screenCategory: this.screenCategory
                    }
                }));
            }, 100); // Allow other modules to initialize first
        },
        
        /**
         * Calculate device type based on user agent and screen size
         * @returns {string} Device type: 'mobile', 'tablet', or 'desktop'
         */
        calculateDeviceType: function() {
            const userAgent = navigator.userAgent.toLowerCase();
            const screenWidth = window.screen.width;
            const screenHeight = window.screen.height;
            const maxDimension = Math.max(screenWidth, screenHeight);
            const minDimension = Math.min(screenWidth, screenHeight);
            
            // Check for tablets first (they often have mobile user agents)
            if (this.isTablet(userAgent, maxDimension, minDimension)) {
                return 'tablet';
            }
            
            // Check for mobile
            if (this.isMobile(userAgent, maxDimension)) {
                return 'mobile';
            }
            
            // Default to desktop
            return 'desktop';
        },
        
        /**
         * Determine if device is a tablet
         * @param {string} userAgent - User agent string
         * @param {number} maxDimension - Maximum screen dimension
         * @param {number} minDimension - Minimum screen dimension
         * @returns {boolean} True if tablet
         */
        isTablet: function(userAgent, maxDimension, minDimension) {
            // Common tablet indicators
            const tabletKeywords = [
                'ipad', 'tablet', 'kindle', 'silk', 'playbook', 
                'nexus 10', 'nexus 9', 'nexus 7', 'xoom'
            ];
            
            const hasTabletKeyword = tabletKeywords.some(keyword => 
                userAgent.includes(keyword)
            );
            
            // iPad detection (even with desktop mode)
            const isIpad = /macintosh/.test(userAgent) && 'ontouchend' in document;
            
            // Size-based detection (7-13 inches typically)
            const isTabletSize = maxDimension >= 768 && maxDimension <= 1366 && 
                                 minDimension >= 600;
            
            return hasTabletKeyword || isIpad || isTabletSize;
        },
        
        /**
         * Determine if device is mobile
         * @param {string} userAgent - User agent string
         * @param {number} maxDimension - Maximum screen dimension
         * @returns {boolean} True if mobile
         */
        isMobile: function(userAgent, maxDimension) {
            // Common mobile keywords
            const mobileKeywords = [
                'mobile', 'android', 'iphone', 'ipod', 'blackberry', 
                'windows phone', 'opera mini', 'iemobile'
            ];
            
            const hasMobileKeyword = mobileKeywords.some(keyword => 
                userAgent.includes(keyword)
            );
            
            // Size-based detection (smaller screens)
            const isMobileSize = maxDimension < 768;
            
            return hasMobileKeyword || isMobileSize;
        },
        
        /**
         * Calculate screen category based on available dimensions
         * @returns {string} Screen category: 'small', 'medium', or 'large'
         */
        calculateScreenCategory: function() {
            const width = window.innerWidth || document.documentElement.clientWidth;
            const height = window.innerHeight || document.documentElement.clientHeight;
            const minDimension = Math.min(width, height);
            
            if (minDimension < 600) {
                return 'small';
            } else if (minDimension < 900) {
                return 'medium';
            } else {
                return 'large';
            }
        },
        
        /**
         * Setup responsive event listeners
         */
        setupResponsiveListeners: function() {
            // Debounced resize handler
            const debouncedResize = window.Helpers.debounce(() => {
                const newScreenCategory = this.calculateScreenCategory();
                if (newScreenCategory !== this.screenCategory) {
                    const oldCategory = this.screenCategory;
                    this.screenCategory = newScreenCategory;
                    
                    window.Helpers.log('Screen category changed:', oldCategory, '→', newScreenCategory);
                    
                    // Emit screen category change event
                    window.dispatchEvent(new CustomEvent('screenCategoryChanged', {
                        detail: {
                            oldCategory: oldCategory,
                            newCategory: newScreenCategory,
                            deviceType: this.deviceType
                        }
                    }));
                }
            }, 250);
            
            // Listen for window resize
            window.addEventListener('resize', debouncedResize);
            
            // Listen for orientation change (mobile/tablet)
            window.addEventListener('orientationchange', () => {
                setTimeout(() => {
                    debouncedResize();
                    window.Helpers.log('Orientation change detected');
                }, 100);
            });
        },
        
        /**
         * Get current device type
         * @returns {string} Device type
         */
        getDeviceType: function() {
            return this.deviceType || 'desktop';
        },
        
        /**
         * Get touch support status
         * @returns {boolean} True if touch supported
         */
        hasTouchSupport: function() {
            return this.touchSupport;
        },
        
        /**
         * Get current screen category
         * @returns {string} Screen category
         */
        getScreenCategory: function() {
            return this.screenCategory || 'medium';
        },
        
        /**
         * Check if device should use mobile UI patterns
         * @returns {boolean} True if mobile UI should be used
         */
        shouldUseMobileUI: function() {
            return this.deviceType === 'mobile' || 
                   (this.deviceType === 'tablet' && this.screenCategory === 'small');
        },
        
        /**
         * Get adaptive setting for current device
         * @param {string} settingName - Setting name
         * @returns {*} Setting value or default
         */
        getAdaptiveSetting: function(settingName, defaultValue = null) {
            const key = `${this.deviceType}_${this.screenCategory}_${settingName}`;
            return this.adaptiveSettings[key] || defaultValue;
        },
        
        /**
         * Set adaptive setting for current device
         * @param {string} settingName - Setting name
         * @param {*} value - Setting value
         */
        setAdaptiveSetting: function(settingName, value) {
            const key = `${this.deviceType}_${this.screenCategory}_${settingName}`;
            this.adaptiveSettings[key] = value;
        },
        
        /**
         * Get device information for debugging
         * @returns {Object} Device information
         */
        getDeviceInfo: function() {
            return {
                deviceId: this.deviceId,
                deviceVolume: this.getDeviceVolume(),
                clockOffsets: this.clockOffsets,
                hasDeviceVolume: this.hasDeviceVolume(),
                // Responsive properties
                deviceType: this.deviceType,
                touchSupport: this.touchSupport,
                screenCategory: this.screenCategory,
                shouldUseMobileUI: this.shouldUseMobileUI(),
                screenDimensions: {
                    width: window.innerWidth,
                    height: window.innerHeight,
                    availWidth: window.screen.width,
                    availHeight: window.screen.height
                }
            };
        }
    };
    
    // Auto-initialize
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => window.DeviceManager.init());
    } else {
        window.DeviceManager.init();
    }
    
})(window);