(function(window) {
    'use strict';

    window.PlayerKeyboardShortcuts = class {
        constructor(player) {
            this.player = player;
        }

        handleKeydown(e) {
            const p = this.player;
            if (document.activeElement.tagName === 'INPUT' || document.activeElement.tagName === 'TEXTAREA') return;
            switch(e.code) {
                case 'Space':
                case 'KeyK': e.preventDefault(); p.bigPlay.click(); break;
                case 'ArrowLeft': case 'KeyJ':
                    {
                        const currentDisplayTime = p.video.currentTime + (p.streamStartOffset || 0);
                        const newTime = Math.max(0, currentDisplayTime - 10);

                        if (p.needsTranscode) {
                            p.streamMgr.performServerSeek(newTime);
                        } else {
                            p.video.currentTime = Math.max(0, p.video.currentTime - 10);
                        }
                        p.controlsManager.showControls();
                    }
                    break;
                case 'ArrowRight': case 'KeyL':
                    {
                        const maxDur = p.totalDuration || p.video.duration;
                        const currentDisplayTime = p.video.currentTime + (p.streamStartOffset || 0);
                        const newTime = Math.min(maxDur || p.video.duration, currentDisplayTime + 10);

                        if (p.needsTranscode) {
                            p.streamMgr.performServerSeek(newTime);
                        } else {
                            p.video.currentTime = Math.min(maxDur || p.video.duration, p.video.currentTime + 10);
                        }
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
