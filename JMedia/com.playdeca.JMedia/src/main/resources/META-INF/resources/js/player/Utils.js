(function(window) {
    'use strict';
    window.PlayerUtils = {
        isIOS() {
            return /iPhone|iPad|iPod|iPadOS/i.test(navigator.userAgent) ||
                   (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1) ||
                   (navigator.platform === 'iPhone' || navigator.platform === 'iPad');
        },

        isMac() {
            return navigator.platform === 'MacIntel' && navigator.maxTouchPoints <= 1;
        },

        formatTime(s) {
            if (isNaN(s) || s < 0 || s === Infinity) return '0:00';
            const h = Math.floor(s / 3600);
            const m = Math.floor((s % 3600) / 60);
            const secs = Math.floor(s % 60);
            return (h > 0 ? h + ':' : '') + (h > 0 ? m.toString().padStart(2, '0') : m) + ':' + secs.toString().padStart(2, '0');
        },

        /* ---------- Screen Wake Lock ---------- *
         * Prevents the device from sleeping while video is loading/playing.
         * Uses the Screen Wake Lock API (iOS Safari 16.4+, Chrome 85+, Firefox 126+).
         * Call requestWakeLock() before initiating video load,
         * call releaseWakeLock() on pause / ended / error.
         */
        _wakeLock: null,
        _wakeLockFailed: false,

        async requestWakeLock() {
            if (this._wakeLock) return;
            if (this._wakeLockFailed) return;
            if (!('wakeLock' in navigator)) {
                this._wakeLockFailed = true;
                console.warn('[PlayerUtils] Screen Wake Lock API not available (requires iOS 16.4+ / modern browser)');
                return;
            }
            try {
                this._wakeLock = await navigator.wakeLock.request('screen');
                this._wakeLock.addEventListener('release', function() {
                    this._wakeLock = null;
                }.bind(this));
            } catch (err) {
                this._wakeLockFailed = true;
                console.warn('[PlayerUtils] Screen Wake Lock request rejected:', err.message);
            }
        },

        releaseWakeLock() {
            if (this._wakeLock) {
                try { this._wakeLock.release(); } catch (_) {}
                this._wakeLock = null;
            }
        },

        resetWakeLock() {
            this._wakeLockFailed = false;
        }
    };
})(window);
