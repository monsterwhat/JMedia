(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    JMedia.Helpers = {
        formatTime: function(s) {
            if (s === null || s === undefined || isNaN(s)) {
                return "0:00";
            }
            return `${Math.floor(s / 60)}:${Math.floor(s % 60).toString().padStart(2, '0')}`;
        },

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

        debounce: function(func, delay) {
            let timeoutId;
            return function (...args) {
                clearTimeout(timeoutId);
                timeoutId = setTimeout(() => func.apply(this, args), delay);
            };
        },

        applyMarqueeEffect: function(element) {
            if (typeof element === 'string') {
                const el = document.getElementById(element);
                if (el) {
                    if (el.scrollWidth > el.clientWidth) {
                        el.classList.add('marquee');
                        el.classList.remove('no-scroll');
                    } else {
                        el.classList.remove('marquee');
                        el.classList.add('no-scroll');
                    }
                }
                return;
            }
            const span = element.querySelector('span');
            if (span) {
                const originalOverflow = element.style.overflow;
                element.style.overflow = 'visible';

                if (span.scrollWidth > element.clientWidth) {
                    span.classList.add('marquee');
                    span.classList.remove('no-scroll');
                } else {
                    span.classList.remove('marquee');
                    span.classList.add('no-scroll');
                }

                element.style.overflow = originalOverflow;
            }
        },

        escapeHtml: function(text) {
            if (!text) return '';
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        },

        getActiveProfileId: function() {
            return window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
        },

        buildApiUrl: function(path, params = {}) {
            const profileId = this.getActiveProfileId();
            let url = `/api/music/${path}`.replace('{profileId}', profileId);
            if (params.profileId) {
                url = url.replace('{profileId}', params.profileId);
            }
            const query = Object.keys(params)
                .filter(k => k !== 'profileId')
                .map(k => `${encodeURIComponent(k)}=${encodeURIComponent(params[k])}`)
                .join('&');
            if (query) url += (url.includes('?') ? '&' : '?') + query;
            return url;
        },

        getAuthToken: function() {
            return localStorage.getItem('authToken');
        },

        getStreamUrl: function(songId, profileId) {
            const pid = profileId || this.getActiveProfileId();
            const token = this.getAuthToken();
            let url = `/api/music/stream/${pid}/${songId}`;
            if (token) url += `?token=${encodeURIComponent(token)}`;
            return url;
        },

        log: function(...args) {
            const DEBUG = (typeof window !== 'undefined' && window.localStorage) ?
                (localStorage.getItem('musicbar_debug') === 'true' || localStorage.getItem('musicbar_debug') === '1') : false;
            if (DEBUG) {
                console.log('[JMedia]', ...args);
            }
        },

        volume: {
            exponent: 2,

            calculateExponentialVolume: function(sliderValue) {
                const linearVol = sliderValue / 100;
                return Math.pow(linearVol, JMedia.Helpers.volume.exponent);
            },

            calculateLinearSliderValue: function(exponentialVol) {
                const linearVol = Math.pow(exponentialVol, 1 / JMedia.Helpers.volume.exponent);
                return linearVol * 100;
            }
        },

        safeNumber: function(value, defaultValue = 0) {
            if (typeof value === 'number' && isFinite(value)) {
                return value;
            }
            return defaultValue;
        },

        clamp: function(value, min, max) {
            return Math.max(min, Math.min(max, value));
        },

        generateId: function() {
            return 'id-' + Date.now() + '-' + Math.floor(Math.random() * 1000000);
        },

        deepClone: function(obj) {
            if (obj === null || typeof obj !== 'object') {
                return obj;
            }
            if (obj instanceof Date) {
                return new Date(obj.getTime());
            }
            if (obj instanceof Array) {
                return obj.map(item => JMedia.Helpers.deepClone(item));
            }
            const cloned = {};
            for (const key in obj) {
                if (obj.hasOwnProperty(key)) {
                    cloned[key] = JMedia.Helpers.deepClone(obj[key]);
                }
            }
            return cloned;
        },

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
                if (!keys2.includes(key) || !JMedia.Helpers.deepEqual(obj1[key], obj2[key])) {
                    return false;
                }
            }
            return true;
        },

        init: function() {
            JMedia.Helpers.log('Helpers initialized');
        }
    };

    window.Helpers = JMedia.Helpers;
    window.applyMarqueeEffectToQueue = function(element) {
        JMedia.Helpers.applyMarqueeEffect(element);
    };
    window.applyMarqueeEffectToHistory = function(element) {
        JMedia.Helpers.applyMarqueeEffect(element);
    };

    JMedia.Helpers.init();

})(window);
