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
                    if (data.nextVideoId && window.videoSPA) {
                        window.videoSPA.playVideo(data.nextVideoId);
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
                    if (data.previousVideoId && window.videoSPA) {
                        window.videoSPA.playVideo(data.previousVideoId);
                    }
                }
            } catch (e) { console.error('Failed to load previous episode', e); }
        }
    };
})(window);
