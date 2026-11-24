(function(window) {
    'use strict';

    window.PlayerKeyboardShortcuts = class {
        constructor(player) {
            this.player = player;
        }

        handleKeydown(e) {
            const p = this.player;
            const el = document.activeElement;
            if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT'
                    || el.isContentEditable)) return;
            // Leave browser and OS shortcuts alone: Ctrl+F (find) toggled fullscreen,
            // Ctrl+L / Alt+Arrow (address bar, back/forward) seeked, Ctrl+K/M played
            // and muted. Only Ctrl+Alt+D (debug dialog) uses modifiers here.
            const debugCombo = e.code === 'KeyD' && e.ctrlKey && e.altKey;
            if ((e.ctrlKey || e.metaKey || e.altKey) && !debugCombo) return;
            switch(e.code) {
                case 'Space':
                case 'KeyK': e.preventDefault(); p.bigPlay.click(); break;
                case 'ArrowLeft': case 'KeyJ':
                    {
                        const currentDisplayTime = p.video.currentTime + (p.streamStartOffset || 0);
                        const newTime = Math.max(0, currentDisplayTime - 10);
                        // One seek path for every route (raw file, remux/transcode, HLS).
                        p.streamMgr.performServerSeek(newTime);
                        p.controlsManager.showControls();
                    }
                    break;
                case 'ArrowRight': case 'KeyL':
                    {
                        const maxDur = p.totalDuration || p.video.duration;
                        const currentDisplayTime = p.video.currentTime + (p.streamStartOffset || 0);
                        const newTime = Math.min(maxDur || p.video.duration, currentDisplayTime + 10);
                        p.streamMgr.performServerSeek(newTime);
                        p.controlsManager.showControls();
                    }
                    break;
                case 'KeyF': p.fullscreenBtn.click(); break;
                case 'KeyM': p.muteBtn.click(); break;
                case 'KeyD':
                    if (e.ctrlKey && e.altKey) {
                        e.preventDefault();
                        console.log('[SimplePlayer] Ctrl+Alt+D pressed, toggling debug dialog');
                        p.controlsManager.toggleDebugDialog();
                    }
                    break;
                case 'Escape':
                    e.preventDefault();
                    p.controlsManager.closeDebugDialog();
                    break;
            }
        }
    };
})(window);
