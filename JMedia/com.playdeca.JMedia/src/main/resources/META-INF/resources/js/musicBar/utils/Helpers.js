/**
 * Helpers - Utility functions for musicBar
 * Pure functions with no dependencies
 */
(function(window) {
    'use strict';
    
    window.Helpers = {
        /**
         * Format time in seconds to MM:SS format
         * @param {number} s - Time in seconds
         * @returns {string} Formatted time string
         */
        formatTime: function(s) {
            if (s === null || s === undefined || isNaN(s)) {
                return "0:00";
            }
            return `${Math.floor(s / 60)}:${Math.floor(s % 60).toString().padStart(2, '0')}`;
        },
        
        /**
         * Throttle function execution
         * @param {Function} func - Function to throttle
         * @param {number} delay - Delay in milliseconds
         * @returns {Function} Throttled function
         */
        throttle: function(func, delay) {
            let lastCall = 0;
            return function (...args) {
                const now = new Date().getTime();
                if (now - lastCall < delay) {
                    return;
                }
                lastCall = now;
                return func.apply(this, args);
            };
        },
        
        /**
         * Debounce function execution
         * @param {Function} func - Function to debounce
         * @param {number} delay - Delay in milliseconds
         * @returns {Function} Debounced function
         */
        debounce: function(func, delay) {
            let timeoutId;
            return function (...args) {
                clearTimeout(timeoutId);
                timeoutId = setTimeout(() => func.apply(this, args), delay);
            };
        },
        
        /**
         * Apply marquee effect to text element
         * @param {string} elementId - ID of the element
         * @param {string} text - Text to set and check for overflow
         */
        applyMarqueeEffect: function(elementId, text) {
            const element = document.getElementById(elementId);
            if (element) {
                element.innerText = text; // Set text first
                // Check if text overflows
                if (element.scrollWidth > element.clientWidth) {
                    element.classList.add('marquee');
                    element.classList.remove('no-scroll');
                } else {
                    element.classList.remove('marquee');
                    element.classList.add('no-scroll');
                }
            }
        },
        
        /**
         * Debug logging helper (enabled via localStorage flag 'musicbar_debug')
         */
        log: function(...args) {
            const DEBUG_MUSICBAR = (typeof window !== 'undefined' && window.localStorage) ? 
                (localStorage.getItem('musicbar_debug') === 'true' || localStorage.getItem('musicbar_debug') === '1') : false;
            
            if (DEBUG_MUSICBAR) {
                console.log('[musicBar]', ...args);
            }
        },
        
        /**
         * Volume calculation utilities
         */
        volume: {
            exponent: 2,
            
            /**
             * Convert slider value (0-100) to exponential volume
             * @param {number} sliderValue - Slider value (0-100)
             * @returns {number} Exponential volume (0-1)
             */
            calculateExponentialVolume: function(sliderValue) {
                const linearVol = sliderValue / 100;
                return Math.pow(linearVol, window.Helpers.volume.exponent);
            },
            
            /**
             * Convert exponential volume back to linear slider value
             * @param {number} exponentialVol - Exponential volume (0-1)
             * @returns {number} Linear slider value (0-100)
             */
            calculateLinearSliderValue: function(exponentialVol) {
                const linearVol = Math.pow(exponentialVol, 1 / window.Helpers.volume.exponent);
                return linearVol * 100;
            }
        },
        
        /**
         * Safe number validation
         * @param {*} value - Value to validate
         * @param {number} defaultValue - Default value if invalid
         * @returns {number} Validated number
         */
        safeNumber: function(value, defaultValue = 0) {
            if (typeof value === 'number' && isFinite(value)) {
                return value;
            }
            return defaultValue;
        },
        
        /**
         * Clamp number between min and max
         * @param {number} value - Value to clamp
         * @param {number} min - Minimum value
         * @param {number} max - Maximum value
         * @returns {number} Clamped value
         */
        clamp: function(value, min, max) {
            return Math.max(min, Math.min(max, value));
        },
        
        /**
         * Generate a unique ID
         * @returns {string} Unique ID
         */
        generateId: function() {
            return 'id-' + Date.now() + '-' + Math.floor(Math.random() * 1000000);
        },
        
        /**
         * Deep clone an object
         * @param {Object} obj - Object to clone
         * @returns {Object} Cloned object
         */
        deepClone: function(obj) {
            if (obj === null || typeof obj !== 'object') {
                return obj;
            }
            
            if (obj instanceof Date) {
                return new Date(obj.getTime());
            }
            
            if (obj instanceof Array) {
                return obj.map(item => window.Helpers.deepClone(item));
            }
            
            const cloned = {};
            for (const key in obj) {
                if (obj.hasOwnProperty(key)) {
                    cloned[key] = window.Helpers.deepClone(obj[key]);
                }
            }
            
            return cloned;
        },
        
        /**
         * Compare two objects for deep equality
         * @param {Object} obj1 - First object
         * @param {Object} obj2 - Second object
         * @returns {boolean} True if objects are deeply equal
         */
        deepEqual: function(obj1, obj2) {
            if (obj1 === obj2) {
                return true;
            }
            
            if (obj1 === null || obj2 === null || typeof obj1 !== 'object' || typeof obj2 !== 'object') {
                return false;
            }
            
            const keys1 = Object.keys(obj1);
            const keys2 = Object.keys(obj2);
            
            if (keys1.length !== keys2.length) {
                return false;
            }
            
            for (const key of keys1) {
                if (!keys2.includes(key) || !window.Helpers.deepEqual(obj1[key], obj2[key])) {
                    return false;
                }
            }
            
            return true;
        },
        
        /**
         * Initialize helpers
         */
        init: function() {
            window.Helpers.log('Helpers initialized');
        }
    };
    
    // Auto-initialize
    window.Helpers.init();
    
})(window);