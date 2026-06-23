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
        }
    };
})(window);
