(function(window) {
    'use strict';

    window.PlayerProgressReporter = class {
        constructor(player) {
            this.player = player;
        }

        start() {
            const p = this.player;
            p._prog = setInterval(() => {
                if (!p.video.paused && p.video.currentTime > 0) {
                    const displayTime = Math.min(p.video.currentTime + (p.streamStartOffset || 0), p.totalDuration || Infinity);
                    this._reportProgress(displayTime, !p.video.paused);
                }
            }, 5000);

            document.addEventListener('visibilitychange', () => {
                if (document.visibilityState === 'hidden') this.saveNow();
            });
        }

        _reportProgress(time, playing) {
            const p = this.player;
            if (p.externalId) {
                const body = { currentTime: time, duration: p.video.duration || 0 };
                fetch(`/api/video/external/${p.externalId}/progress`, {
                    method: 'POST',
                    credentials: 'include',
                    keepalive: true,
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                }).then(r => { if (!r.ok) console.warn('[SimplePlayer] External progress returned', r.status); }).catch(() => {});
            } else {
                const url = `/api/video/playback/progress?videoId=${p.videoId}&time=${time}&playing=${playing}&device=${p.deviceToken}`;
                fetch(url, {
                    method: 'POST',
                    credentials: 'include',
                    keepalive: true,
                    headers: { 'X-User-ID': p.profileId }
                }).then(r => { if (!r.ok) console.warn('[SimplePlayer] Progress returned', r.status); }).catch(err => console.error('[SimplePlayer] Progress report failed:', err));
            }
        }

        saveNow() {
            const p = this.player;
            const now = Date.now();
            if (p._lastProgressSave && now - p._lastProgressSave < 2000) return;
            p._lastProgressSave = now;
            if (p.video.currentTime > 0 || p.streamStartOffset > 0) {
                const displayTime = p.video.currentTime + (p.streamStartOffset || 0);
                this._reportProgress(displayTime, !p.video.paused);
            }
        }

        setMusicSuspended(s) {
            const p = this.player;
            window.videoPlaying = s;
            document.body.setAttribute('data-video-active', s ? 'true' : 'false');
            if (s) document.querySelectorAll('audio').forEach(a => a.pause());
        }
    };
})(window);
