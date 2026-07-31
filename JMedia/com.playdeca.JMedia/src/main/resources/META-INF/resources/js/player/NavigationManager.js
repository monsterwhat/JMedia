(function(window) {
    'use strict';

    window.PlayerNavigationManager = class {
        constructor(player) {
            this.player = player;
        }

        goBack() {
            const p = this.player;
            if (window.videoSPA) window.videoSPA.goBack();
            else window.history.back();
        }

        goToDetails() {
            const p = this.player;
            if (window.videoSPA) window.videoSPA.switchSection('details', { videoId: p.videoId });
        }

        _navigateToVideo(videoId) {
            console.log('[NavigationManager] Navigating to video:', videoId);
            if (window.videoSPA) {
                window.videoSPA.playVideo(videoId);
                return;
            }

            // Self-contained navigation: destroy the current player, command the
            // server to switch to the target video (same-id guard avoids the
            // selectVideo toggle), then reload via the autoplay param. On the
            // cinema page handleAutoplay opens the modal for the target; its
            // same-id guard then skips the redundant play command.
            // window.destroyCurrentPlayer is not a global; only currentPlayerInstance.
            if (window.currentPlayerInstance) {
                try {
                    window.currentPlayerInstance.destroy();
                } catch (e) {
                    console.warn('[NavigationManager] Failed to destroy current player:', e);
                }
                window.currentPlayerInstance = null;
            }

            (async () => {
                try {
                    const cur = await fetch('/api/video/playback/current').then(r => r.json());
                    if (!cur.video || cur.video.id !== videoId) {
                        await fetch('/api/video/playback/play/' + videoId, { method: 'POST' });
                    }
                } catch (err) {
                    console.warn('[NavigationManager] Failed to command playback switch:', err);
                }
                window.location.href = `/video?autoplay=${videoId}`;
            })();
        }

        async playNextEpisode() {
            const p = this.player;
            const isFullscreen = document.fullscreenElement || document.webkitFullscreenElement ||
                                p.container.classList.contains('is-css-fullscreen') || p.isIOSNativeFullscreen;
            if (isFullscreen) {
                sessionStorage.setItem('jmedia_restore_fullscreen', 'true');
            }
            if (p.lastSelectedTrackId && p.lastSelectedTrackId !== 'off') {
                sessionStorage.setItem('jmedia_global_subtitle_track', p.lastSelectedTrackId);
            } else {
                sessionStorage.setItem('jmedia_global_subtitle_track', 'off');
            }
            try {
                const res = await fetch(`/api/video/playback/next/${p.videoId}`);
                if (res.ok) {
                    const data = await res.json();
                    if (data.nextVideoId) {
                        this._navigateToVideo(data.nextVideoId);
                    }
                }
            } catch (e) { console.error('Failed to load next episode', e); }
        }

        async playPreviousEpisode() {
            const p = this.player;
            const isFullscreen = document.fullscreenElement || document.webkitFullscreenElement ||
                                p.container.classList.contains('is-css-fullscreen') || p.isIOSNativeFullscreen;
            if (isFullscreen) {
                sessionStorage.setItem('jmedia_restore_fullscreen', 'true');
            }
            if (p.lastSelectedTrackId && p.lastSelectedTrackId !== 'off') {
                sessionStorage.setItem('jmedia_global_subtitle_track', p.lastSelectedTrackId);
            } else {
                sessionStorage.setItem('jmedia_global_subtitle_track', 'off');
            }
            try {
                const res = await fetch(`/api/video/playback/previous/${p.videoId}`);
                if (res.ok) {
                    const data = await res.json();
                    if (data.previousVideoId) {
                        this._navigateToVideo(data.previousVideoId);
                    }
                }
            } catch (e) { console.error('Failed to load previous episode', e); }
        }
    };
})(window);
