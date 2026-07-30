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

            // Self-contained player navigation: fetch the playback fragment and swap in-content,
            // without relying on global openPlayerModal / closePlayerModal which may not exist
            // in all contexts (e.g., minified builds, bundled modules).
            const backdrop = document.getElementById('player-modal-backdrop');
            const modal = document.getElementById('player-modal');
            const content = document.getElementById('player-modal-content');

            if (backdrop && modal && content) {
                console.log('[NavigationManager] Direct fragment swap for video:', videoId);
                // Show modal
                backdrop.classList.add('active');
                modal.classList.add('active');
                document.body.style.overflow = 'hidden';

                // Show loading indicator
                content.innerHTML = '<div style="display:flex;align-items:center;justify-content:center;height:100%;color:rgba(255,255,255,0.5);"><i class="fa-solid fa-spinner fa-spin" style="font-size:2rem;"></i></div>';

                // Fetch new playback fragment
                fetch(`/api/video/ui/playback-fragment?videoId=${videoId}`)
                    .then(r => {
                        if (!r.ok) throw new Error('HTTP ' + r.status);
                        return r.text();
                    })
                    .then(html => {
                        const tmp = document.createElement('div');
                        tmp.innerHTML = html;
                        const scripts = Array.from(tmp.querySelectorAll('script'));
                        scripts.forEach(s => s.remove());
                        content.innerHTML = tmp.innerHTML;
                        for (const old of scripts) {
                            const el = document.createElement('script');
                            if (old.src) {
                                for (const attr of old.attributes) el.setAttribute(attr.name, attr.value);
                                el.async = false;
                            } else {
                                el.textContent = old.textContent;
                            }
                            content.appendChild(el);
                        }
                        // Init episode sidebar after player fragment loads
                        if (typeof window.initEpisodeSidebar === 'function') {
                            window.initEpisodeSidebar();
                        }
                    })
                    .catch(err => {
                        console.error('[NavigationManager] Failed to load playback fragment:', err);
                        content.innerHTML = '<div style="display:flex;align-items:center;justify-content:center;height:100%;color:rgba(255,255,255,0.5);">Failed to load player</div>';
                    });
            } else {
                // No modal found — last-resort: navigate to video-test page
                console.log('[NavigationManager] No player modal found, navigating to URL');
                window.location.href = `/video?autoplay=${videoId}`;
            }
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
