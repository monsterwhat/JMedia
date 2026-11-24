(function(window) {
    'use strict';

    window.PlayerProgressReporter = class {
        constructor(player) {
            this.player = player;
        }

        start() {
            const p = this.player;
            if (p._prog) clearInterval(p._prog);
            let restTick = 0;
            // ~1 s cadence: this player's position is the source of truth and the
            // server relays it to the profile's other devices (remote views). The REST
            // DB progress save is throttled to every 5th tick (~5 s) to bound writes.
            p._prog = setInterval(() => {
                if (!p.video.paused && p.video.currentTime > 0) {
                    restTick++;
                    if (typeof p._broadcastState === 'function') {
                        p._broadcastState();
                    }
                    if (restTick % 5 === 1) {
                        const displayTime = Math.min(p.video.currentTime + (p.streamStartOffset || 0), p.totalDuration || Infinity);
                        this._reportProgress(displayTime, !p.video.paused);
                    }
                }
            }, 1000);

            if (!this._onVisibilityChange) {
                this._onVisibilityChange = () => {
                    if (document.visibilityState === 'hidden') this.saveNow();
                };
                document.addEventListener('visibilitychange', this._onVisibilityChange);
            }
        }

        stop() {
            const p = this.player;
            if (p._prog) { clearInterval(p._prog); p._prog = null; }
            if (this._onVisibilityChange) {
                document.removeEventListener('visibilitychange', this._onVisibilityChange);
                this._onVisibilityChange = null;
            }
        }

        _reportProgress(time, playing) {
            const p = this.player;
            if (p._destroyed) return;
            if (p.externalId) {
                const body = { currentTime: time, duration: p.video.duration || 0 };
                fetch(`/api/video/external/${p.externalId}/progress`, {
                    method: 'POST',
                    credentials: 'include',
                    keepalive: true,
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                }).then(r => { if (!r.ok) console.warn('[SimplePlayer] External progress returned', r.status); }).catch((err) => { console.error('[SimplePlayer] External progress report failed:', err); });
            } else {
                const url = `/api/video/playback/progress?videoId=${p.videoId}&time=${time}&playing=${playing}&profileId=${p.profileId}`;
                fetch(url, {
                    method: 'POST',
                    credentials: 'include',
                    keepalive: true,
                    headers: { 'X-User-ID': p.profileId }
                }).then(r => { if (!r.ok) console.warn('[SimplePlayer] Progress returned', r.status); }).catch(err => console.error('[SimplePlayer] Progress report failed:', err));
            }
        }

        saveNow(force) {
            const p = this.player;
            if (p._destroyed) return;
            const now = Date.now();
            var isPaused = p.video ? p.video.paused : false;
            // During a seek rebuild the element is paused after its src is torn down,
            // but `streamStartOffset` still holds the PREVIOUS session's origin. Saving
            // element+offset then records a position from the old timeline — e.g. the
            // 91.9s report while the player was actually moving to 172s, which lands
            // inside the intro and re-triggers the auto-skip. The seek path saves the
            // target itself once the new session is playing.
            if (p._swapInProgress) {
                console.log('[SimplePlayer] saveNow skipped: stream swap in progress');
                return;
            }
            if (!force && p._lastProgressSave && now - p._lastProgressSave < 2000) {
                if (isPaused) {
                    console.log('[SimplePlayer] saveNow throttled but pause report forced through (playing=false)');
                } else {
                    return;
                }
            }
            if (force) {
                console.log('[SimplePlayer] saveNow forced (playing=' + !isPaused + ')');
            }
            var displayTime = (p.video.currentTime || 0) + (p.streamStartOffset || 0);
            // Nothing has played yet and the element sits at 0: the resume seek
            // has not happened (direct file before loadedmetadata, autoplay
            // blocked). Reporting 0 here, on pause/hide/pagehide/teardown,
            // overwrote the saved resume point and dropped the title from
            // Continue Watching when a video was opened and left before it loaded.
            if (!p._hasPlayedData && displayTime < 1) {
                return;
            }
            p._lastProgressSave = now;
            this._reportProgress(displayTime, !p.video.paused);
        }

        setMusicSuspended(s) {
            const p = this.player;
            document.body.setAttribute('data-video-active', s ? 'true' : 'false');
            if (s) document.querySelectorAll('audio').forEach(a => a.pause());
        }
    };
})(window);
