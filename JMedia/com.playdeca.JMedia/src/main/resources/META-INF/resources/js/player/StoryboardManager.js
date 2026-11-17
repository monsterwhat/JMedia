(function(window) {
    'use strict';

    window.PlayerStoryboardManager = class {
        constructor(player) {
            this.player = player;
        }

        async loadStoryboard() {
            const p = this.player;
            if (p._isAlive && !p._isAlive()) return;
            if (p.container && (p.container.dataset.type === 'live' || p.container.dataset.type === 'external')) return;
            // Pin the video: the retry loop below can outlive a switch, and applying
            // one episode's storyboard to another shows the wrong preview frames.
            const storyboardVideoId = p.videoId;
            if (p._storyboardRetryVideoId !== storyboardVideoId) {
                p._storyboardRetryVideoId = storyboardVideoId;
                p._storyboardRetryCount = 0;
                if (p._storyboardRetryTimer) { clearTimeout(p._storyboardRetryTimer); p._storyboardRetryTimer = null; }
            }
            try {
                const res = await fetch(`/api/video/storyboard/${storyboardVideoId}/metadata`);
                if (p._destroyed || p.videoId !== storyboardVideoId) return;
                if (res.ok) {
                    const json = await res.json();
                    p.storyboard.metadata = json.data || json;
                    const wasReady = p.storyboard.loaded;
                    p.storyboard.loaded = p.storyboard.metadata.isReady;

                    if (p.storyboard.loaded && !wasReady) {
                        console.log('[SimplePlayer] Storyboard generation complete, refreshing preview');
                        p._storyboardUrl = `/api/video/storyboard/${p.videoId}?t=${Date.now()}`;
                        p._storyboardRetryCount = 0;
                        if (p._storyboardRetryTimer) { clearTimeout(p._storyboardRetryTimer); p._storyboardRetryTimer = null; }
                    }

                    if (!p.storyboard.loaded) {
                        // Generation is one ffmpeg pass over the whole file: ~2 minutes
                        // for a feature, and a first hover is what kicks it off. The
                        // original loop polled every 2s forever, which leaked on a
                        // destroyed player; capping it at 30 x 2s (60s) then cut the
                        // poll off before the sprite existed, leaving a grey preview.
                        // Poll fast for a minute, then slowly for 15 more, still bounded
                        // and still tied to the pinned video.
                        const attempts = p._storyboardRetryCount || 0;
                        if (attempts >= 120) {
                            console.warn('[SimplePlayer] Storyboard retry window (16 min) exhausted');
                            return;
                        }
                        p._storyboardRetryCount = attempts + 1;
                        const delay = attempts < 30 ? 2000 : 10000;
                        if (p._storyboardRetryTimer) { clearTimeout(p._storyboardRetryTimer); p._storyboardRetryTimer = null; }
                        p._storyboardRetryTimer = setTimeout(() => {
                            p._storyboardRetryTimer = null;
                            if (p._destroyed || p.videoId !== storyboardVideoId) return;
                            this.loadStoryboard();
                        }, delay);
                    }
                }
            } catch (e) {
                console.warn('[SimplePlayer] Storyboard metadata failed:', e);
            }
        }

        handleMouseMove(e) {
            const p = this.player;
            const rect = p.progressContainer.getBoundingClientRect();

            let dur = p.totalDuration;
            if (!dur || dur === Infinity) {
                dur = p.video.duration;
            }
            if (!dur) return;

            const pct = (e.clientX - rect.left) / rect.width;
            const time = pct * dur;

            p.preview.classList.add('active');
            p.preview.style.left = Math.max(0, Math.min(rect.width - 160, e.clientX - rect.left - 80)) + 'px';
            p.previewTime.innerText = p.utils.formatTime(time);

            if (p.storyboard.loaded && p.storyboard.metadata) {
                const m = p.storyboard.metadata;
                const tileIndex = Math.min(Math.floor(time / m.interval), m.totalTiles - 1);
                const col = tileIndex % m.columns;
                const row = Math.floor(tileIndex / m.columns);
                p.previewImg.style.backgroundImage = `url(${p._storyboardUrl || `/api/video/storyboard/${p.videoId}`})`;
                p.previewImg.style.backgroundPosition = `-${col * m.width}px -${row * m.height}px`;
                p.previewImg.style.backgroundSize = `${m.width * m.columns}px ${m.height * m.rows}px`;
            }
        }
    };
})(window);
