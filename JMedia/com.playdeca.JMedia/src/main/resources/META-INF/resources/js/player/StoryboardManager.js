(function(window) {
    'use strict';

    window.PlayerStoryboardManager = class {
        constructor(player) {
            this.player = player;
        }

        async loadStoryboard() {
            const p = this.player;
            try {
                const res = await fetch(`/api/video/storyboard/${p.videoId}/metadata`);
                if (res.ok) {
                    const json = await res.json();
                    p.storyboard.metadata = json.data || json;
                    const wasReady = p.storyboard.loaded;
                    p.storyboard.loaded = p.storyboard.metadata.isReady;

                    if (p.storyboard.loaded && !wasReady) {
                        console.log('[SimplePlayer] Storyboard generation complete, refreshing preview');
                        p._storyboardUrl = `/api/video/storyboard/${p.videoId}?t=${Date.now()}`;
                    }

                    if (!p.storyboard.loaded) {
                        setTimeout(() => this.loadStoryboard(), 2000);
                    }
                }
            } catch (e) {}
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
