(function(window) {
    'use strict';

    window.PlayerFullscreenManager = class {
        constructor(player) {
            this.player = player;
        }

        toggleCssFullscreen() {
            const p = this.player;
            const isCssFullscreen = p.container.classList.contains('is-css-fullscreen');

            if (isCssFullscreen) {
                p.container.classList.remove('is-css-fullscreen');
                document.body.style.overflow = '';
                p.isCssFullscreen = false;

                if (screen.orientation && screen.orientation.unlock) {
                    screen.orientation.unlock().catch(() => {});
                }

                console.log('[SimplePlayer] CSS fullscreen exited');
            } else {
                p.container.classList.add('is-css-fullscreen');
                document.body.style.overflow = 'hidden';
                p.isCssFullscreen = true;

                window.scrollTo(0, 0);

                if (screen.orientation && screen.orientation.lock) {
                    screen.orientation.lock('landscape').catch(() => {});
                }

                console.log('[SimplePlayer] CSS fullscreen entered');
            }

            this.updateFullscreenButtonState(p.isCssFullscreen);
        }

        updateFullscreenButtonState(isFullscreen) {
            const p = this.player;
            const fsIcon = p.fullscreenBtn.querySelector('i');
            if (fsIcon) {
                fsIcon.className = isFullscreen ? 'pi pi-compress' : 'pi pi-expand';
            }

            p.container.classList.toggle('is-fullscreen', isFullscreen);

            if (p.controlsVisible) p.controlsManager.showControls();
        }

        requestFullscreen(e) {
            const p = this.player;
            e.stopPropagation();
            e.preventDefault();

            const isIOS = p.utils.isIOS();

            const isNativeFullscreen = !!(document.fullscreenElement || document.webkitFullscreenElement || p.isIOSNativeFullscreen);
            const isCssFullscreen = p.container.classList.contains('is-css-fullscreen');

            if (isNativeFullscreen || isCssFullscreen) {
                if (isNativeFullscreen) {
                    if (document.exitFullscreen) {
                        document.exitFullscreen().catch(() => {});
                    } else if (document.webkitExitFullscreen) {
                        document.webkitExitFullscreen();
                    }
                }
                if (isCssFullscreen) {
                    this.toggleCssFullscreen();
                }
                return;
            }

            if (isIOS) {
                p.video.preload = "auto";
                p._preFullscreenTime = p.video.currentTime;

                const doEnterFullscreen = () => {
                    let nativeFullscreenAttempted = false;

                    if (!p.video.hasAttribute('playsinline')) {
                        p.video.setAttribute('playsinline', 'true');
                        p.video.setAttribute('webkit-playsinline', 'true');
                    }

                    if (p.video.requestFullscreen) {
                        try {
                            p.video.requestFullscreen();
                            nativeFullscreenAttempted = true;
                        } catch (err) {
                            console.log('[SimplePlayer] requestFullscreen failed:', err);
                        }
                    }

                    if (!nativeFullscreenAttempted && p.video.webkitEnterFullscreen) {
                        try {
                            p.video.webkitEnterFullscreen();
                            nativeFullscreenAttempted = true;
                        } catch (err) {
                            console.log('[SimplePlayer] webkitEnterFullscreen failed:', err);
                        }
                    }

                    if (!nativeFullscreenAttempted) {
                        console.log('[SimplePlayer] Using CSS fullscreen fallback for iOS');
                        this.toggleCssFullscreen();
                    }
                };

                const bufferedEnd = p.video.buffered.length > 0 ? p.video.buffered.end(p.video.buffered.length - 1) : 0;
                if (bufferedEnd - p.video.currentTime < 30) {
                    const bufferTimer = setInterval(() => {
                        try {
                            const end = p.video.buffered.length > 0 ? p.video.buffered.end(p.video.buffered.length - 1) : 0;
                            if (end - p.video.currentTime >= 30 || p.video.paused || p.video.ended) {
                                clearInterval(bufferTimer);
                                doEnterFullscreen();
                            }
                        } catch (err) {
                            clearInterval(bufferTimer);
                            doEnterFullscreen();
                        }
                    }, 200);
                    setTimeout(() => { clearInterval(bufferTimer); doEnterFullscreen(); }, 10000);
                } else {
                    doEnterFullscreen();
                }
            } else {
                if (p.container.requestFullscreen) {
                    p.container.requestFullscreen().catch(err => {
                        console.log('[SimplePlayer] Fullscreen error, using CSS fallback:', err);
                        this.toggleCssFullscreen();
                    });
                } else if (p.container.webkitRequestFullscreen) {
                    p.container.webkitRequestFullscreen();
                } else {
                    this.toggleCssFullscreen();
                }
            }
        }

        onFullscreenChange() {
            const p = this.player;
            const isNativeFullscreen = !!(document.fullscreenElement || document.webkitFullscreenElement);

            if (!p.container.classList.contains('is-css-fullscreen')) {
                p.container.classList.toggle('is-fullscreen', isNativeFullscreen);
                this.updateFullscreenButtonState(isNativeFullscreen);
                console.log('[SimplePlayer] Native fullscreen changed:', isNativeFullscreen);
            } else {
                console.log('[SimplePlayer] Native fullscreen changed, but CSS fullscreen is active');
            }

            if (isNativeFullscreen && p._preFullscreenTime != null && p._preFullscreenTime > 0) {
                const restoreTime = p._preFullscreenTime;

                let restored = false;
                const doRestore = () => {
                    if (restored) return;
                    const ct = p.video.currentTime;
                    if (ct === 0 || Math.abs(ct - restoreTime) > 0.5) {
                        p.video.currentTime = restoreTime;
                        restored = true;
                    }
                };

                let canplayFired = false;
                let loadeddataFired = false;
                const onReady = () => {
                    if (!canplayFired || !loadeddataFired) return;
                    doRestore();
                };
                p.video.addEventListener('canplay', () => { canplayFired = true; onReady(); }, { once: true });
                p.video.addEventListener('loadeddata', () => { loadeddataFired = true; onReady(); }, { once: true });
                setTimeout(() => { if (!restored) doRestore(); }, 5000);
                setTimeout(() => { p._preFullscreenTime = null; }, 3000);
            }

            if (p.controlsVisible) p.controlsManager.showControls();
        }

        onIOSVideoFullscreenStart() {
            const p = this.player;
            console.log('[SimplePlayer] iOS video fullscreen started (legacy event)');
            p.isIOSNativeFullscreen = true;
            p._wasPlayingBeforeFullscreen = !p.video.paused;
            p.container.classList.add('is-fullscreen');
            this.updateFullscreenButtonState(true);
            if (p.subtitleController) p.subtitleController.syncForNativeFullscreen();
        }

        onIOSVideoFullscreenEnd() {
            const p = this.player;
            console.log('[SimplePlayer] iOS video fullscreen ended');
            p.isIOSNativeFullscreen = false;
            p.container.classList.remove('is-fullscreen');
            this.updateFullscreenButtonState(false);
            if (p.subtitleController) p.subtitleController.restoreAfterFullscreen();

            // iOS natively pauses on fullscreen exit; resume if it was playing
            if (p._wasPlayingBeforeFullscreen) {
                setTimeout(() => p.video.play().catch(() => {}), 300);
            }
            p._wasPlayingBeforeFullscreen = false;
        }
    };
})(window);
