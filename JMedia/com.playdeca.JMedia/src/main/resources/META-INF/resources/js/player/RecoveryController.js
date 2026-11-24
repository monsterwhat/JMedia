(function (window) {
    'use strict';

    const JMEDIA_DEBUG = (function () {
        try { return localStorage.getItem('jmedia_debug') === '1'; } catch (e) { return false; }
    })();
    const dbg = JMEDIA_DEBUG ? console.log.bind(console) : function () {};

    /** How long a new source may take to start before it counts as failed. */
    const START_DEADLINE_MS = 15000;
    /** Mid-playback buffering before a reconnect. */
    const STALL_DEADLINE_MS = 15000;
    /** Extra windows granted while data is still arriving (slow, not dead). */
    const MAX_EXTENSIONS = 3;
    /** Recovery actions per playback before giving up. */
    const MAX_ATTEMPTS = 2;
    /** Clean playback after which used attempts are given back. */
    const HEALTHY_MS = 30000;
    /** "Server is preparing your video" after this long without a frame. */
    const PREPARING_TOAST_MS = 10000;

    /**
     * One recovery state machine for the video player.
     *
     * Before this, six mechanisms each decided on their own when playback had
     * failed and what to do about it: a start-stall timer, a mid-playback stall
     * timer, per-route playback watchdogs, four `error` handlers, the HLS fatal
     * fallback and the iOS native-HLS watchdog. They shared one counter but not
     * one state, so they could fire on top of each other, double-count or retry
     * at different positions. Now they all report here and this decides.
     *
     * States: idle (no source) -> starting -> playing <-> buffering;
     * any -> recovering (an action is under way, then starting again) -> failed.
     *
     * Every code path that assigns a new source calls begin(); everything that
     * goes wrong calls fail(kind). Kinds:
     *   'start-timeout' - the source did not start in time
     *   'stall'         - playback stopped mid-way and no data is arriving
     *   'error'         - the element raised a media error
     *   'hls'           - hls.js gave up after its own in-place recovery
     *
     * Actions use the player's existing reload paths: reconnect at the current
     * position on the same route (HLS rebuild, ?start= reload, or raw-file reload
     * plus a client seek), or escalate to a forced H.264 transcode
     * (StreamManager.fallbackToDirectStream). Nothing is retried while the
     * viewer has paused.
     */
    window.PlayerRecovery = class {
        constructor(player) {
            this.player = player;
            this.state = 'idle';
            this.attempts = 0;
            this.escalated = false;
            this._timer = null;
            this._toastTimer = null;
            this._healthySince = 0;
            this._label = '';
            this._destroyed = false;

            const v = player.video;
            this._onPlaying = () => this.onPlaying();
            this._onTime = () => this.onProgress();
            this._onWaiting = () => this.onWaiting();
            this._onError = () => this.onError();
            this._onPlay = () => this.onPlay();
            v.addEventListener('play', this._onPlay);
            v.addEventListener('playing', this._onPlaying);
            v.addEventListener('timeupdate', this._onTime);
            v.addEventListener('waiting', this._onWaiting);
            v.addEventListener('error', this._onError);
        }

        // ── inputs ─────────────────────────────────────────────────────

        /**
         * A new source was assigned and should start. Arms the start check.
         * @param {string} label   route, for logs ('direct', 'transcode', 'hls', ...)
         * @param {Object} [opts]  { deadlineMs }
         */
        begin(label, opts) {
            if (this._destroyed) return;
            this._clearTimers();
            this.state = 'starting';
            this._label = label || '';
            const deadline = (opts && opts.deadlineMs) || START_DEADLINE_MS;
            this._armWindow(deadline, () => this._checkStart());
            this._toastTimer = setTimeout(() => {
                this._toastTimer = null;
                if (this.state === 'starting' && !this.player._hasPlayedData && window.Toast) {
                    window.Toast.info('Server is preparing your video, please wait...');
                }
            }, PREPARING_TOAST_MS);
            dbg('[Recovery] begin', label, 'attempts', this.attempts);
        }

        /** A user-initiated new stream (seek, track switch, new video): fresh budget. */
        reset() {
            this.attempts = 0;
            this.escalated = false;
            this._healthySince = 0;
        }

        /** Stop watching (the caller is tearing the source down itself). */
        cancel() {
            this._clearTimers();
            if (this.state !== 'failed') this.state = 'idle';
        }

        /**
         * The viewer pressed play while nothing was being watched (a check that
         * was skipped during a pause, or a start that waited for a gesture).
         */
        onPlay() {
            if (this._destroyed || this.state !== 'idle') return;
            if (!this.player.video.getAttribute('src') && !this.player._hlsInstance) return;
            this.begin(this._label || 'resume');
        }

        onPlaying() {
            if (this._destroyed) return;
            this._clearTimers();
            this.state = 'playing';
            if (!this._healthySince) this._healthySince = Date.now();
            if (typeof this.player._hideLoading === 'function') this.player._hideLoading();
        }

        onProgress() {
            const p = this.player;
            if (this._destroyed || p.video.paused || !(p.video.currentTime > 0)) return;
            if (this.state === 'buffering' || this.state === 'starting') {
                // Frames are flowing again without a `playing` event (some browsers).
                this.onPlaying();
            }
            if (this.attempts > 0 && this._healthySince && Date.now() - this._healthySince > HEALTHY_MS) {
                dbg('[Recovery] healthy for 30 s - attempts given back');
                this.attempts = 0;
                this._healthySince = Date.now();
            }
        }

        onWaiting() {
            if (this._destroyed || this.state !== 'playing') return;
            this.state = 'buffering';
            this._healthySince = 0;
            this._armWindow(STALL_DEADLINE_MS, () => {
                if (this.state !== 'buffering') return;
                const p = this.player;
                if (!p._hasPlayedData || !(p.lastKnownGoodPosition > 0)) return;
                this.fail('stall');
            });
        }

        onError() {
            const p = this.player;
            if (this._destroyed || p._destroyed) return;
            // hls.js owns the element while attached: it repairs decode/append
            // errors in place (recoverMediaError) and reports what it cannot fix
            // through its ERROR event, which calls fail('hls'). Reacting here too
            // would reload the stream underneath that repair.
            if (p._hlsInstance) return;
            // Teardown (removeAttribute('src') / src="") is not a playback failure.
            const src = p.video.getAttribute('src');
            if (!src) return;
            const err = p.video.error;
            console.error('[Recovery] media error on ' + (this._label || 'stream') + ':', err);
            this.fail('error');
        }

        // ── decision ───────────────────────────────────────────────────

        /** Something went wrong; decide what to do. */
        fail(kind) {
            const p = this.player;
            if (this._destroyed || p._destroyed || this.state === 'failed') return;
            this._clearTimers();
            if (p._userPaused) {
                // The viewer paused: never reload under them. Watch again on play.
                dbg('[Recovery] ' + kind + ' while user-paused - not recovering');
                this.state = 'idle';
                return;
            }
            const action = window.PlayerRecovery.decide(kind, {
                attempts: this.attempts,
                maxAttempts: MAX_ATTEMPTS,
                escalated: this.escalated,
                canEscalate: !p.externalUrl && !p._isLive,
                serverTranscoded: !!p.needsTranscode && !p._canNativeHevc && !p._canNativeAv1
            });
            console.warn('[Recovery] ' + kind + ' on ' + (this._label || 'stream') + ' -> ' + action
                + (action === 'fail' ? '' : ' (attempt ' + (this.attempts + 1) + '/' + MAX_ATTEMPTS + ')'));
            if (action === 'fail') {
                this.state = 'failed';
                if (typeof p._showLoading === 'function') {
                    p._showLoading(p.externalUrl ? 'Playback error' : 'Playback failed - please reload');
                }
                return;
            }
            this.attempts++;
            this.state = 'recovering';
            const pos = typeof p._currentAbsolutePosition === 'function' ? p._currentAbsolutePosition() : 0;
            if (action === 'reconnect') {
                if (window.Toast) window.Toast.warning('Playback stalled - reconnecting...');
                this._reconnect(pos);
            } else {
                this.escalated = true;
                if (window.Toast) window.Toast.warning('Playback problem - switching to a compatible stream...');
                this._escalate(pos);
            }
        }

        /**
         * Pure decision table (testable).
         * @returns {'reconnect'|'escalate'|'fail'}
         */
        static decide(kind, s) {
            if (s.attempts >= s.maxAttempts) return 'fail';
            // A source that never started, or HLS that gave up after its own
            // in-place recovery, is a compatibility problem: retrying the same
            // route only repeats it.
            if (kind === 'start-timeout' || kind === 'hls') {
                if (!s.canEscalate || s.escalated) return 'fail';
                return 'escalate';
            }
            // An error on a copied/remuxed stream usually means this browser cannot
            // decode it; a server transcode is retried once at the same place first.
            if (kind === 'error' && !s.serverTranscoded && !s.escalated) {
                return s.canEscalate ? 'escalate' : 'reconnect';
            }
            // 'stall', or an error on a transcoded stream: reconnect first, then escalate.
            if (s.attempts === 0 || s.escalated || !s.canEscalate) return 'reconnect';
            return 'escalate';
        }

        // ── actions ────────────────────────────────────────────────────

        /** Same route, current position. */
        _reconnect(pos) {
            const p = this.player;
            const sm = p.streamMgr;
            if (this.escalated) {
                // Already on the forced transcode: reload that, not the route that failed.
                p._fallbackInProgress = false;
                sm.fallbackToDirectStream(pos, pos);
                return;
            }
            if (p._hlsSessionId || p._hlsSessionPending) {
                sm.recreateLocalHlsSession(pos);
                this.begin('reconnect-hls');
                return;
            }
            if (p._hlsInstance && p.externalUrl) {
                // External/live HLS: let hls.js reload from the playlist.
                try { p._hlsInstance.startLoad(); } catch (e) { /* destroyed */ }
                this.begin('reconnect-external');
                return;
            }
            if (sm.isRawDirect && sm.isRawDirect()) {
                // The raw file ignores ?start=: reload it and seek on loadedmetadata.
                p.streamStartOffset = 0;
                p.initialResumeTime = pos;
                p.video.src = p.video.getAttribute('src');
            } else {
                p.streamStartOffset = pos;
                p.initialResumeTime = 0;
                p.video.src = sm.buildStreamUrl(pos > 0 ? pos : null);
            }
            p.lastKnownGoodPosition = 0;
            p._hasPlayedData = false;
            p.video.load();
            p.video.play().catch((e) => dbg('[Recovery] play after reconnect blocked', e));
            this.begin('reconnect');
        }

        /** Forced H.264 transcode at the current position. */
        _escalate(pos) {
            const p = this.player;
            p._fallbackInProgress = false;
            p.streamMgr.fallbackToDirectStream(pos, pos);
            // fallbackToDirectStream assigns the source and calls begin('fallback').
        }

        // ── timers ─────────────────────────────────────────────────────

        /** One window; extended (up to MAX_EXTENSIONS) while buffered data grows. */
        _armWindow(ms, onExpire) {
            const v = this.player.video;
            let extensions = 0;
            const bufferedEnd = () => {
                try {
                    const b = v.buffered;
                    return b && b.length ? b.end(b.length - 1) : 0;
                } catch (e) {
                    return 0;
                }
            };
            const arm = (bufferedAtStart) => {
                this._timer = setTimeout(() => {
                    this._timer = null;
                    if (this._destroyed) return;
                    const now = bufferedEnd();
                    if (now > bufferedAtStart + 0.05 && extensions < MAX_EXTENSIONS) {
                        extensions++;
                        dbg('[Recovery] data still arriving (' + now.toFixed(1) + 's) - waiting another window');
                        arm(now);
                        return;
                    }
                    onExpire();
                }, ms);
            };
            arm(bufferedEnd());
        }

        _checkStart() {
            const p = this.player;
            const v = p.video;
            if (this.state !== 'starting') return;
            if (!v.paused && v.readyState >= 2 && v.currentTime > 0.1) {
                this.onPlaying();
                return;
            }
            if (v.readyState >= 2) {
                // Decoded fine but paused: usually autoplay blocked. Reloading would
                // abort a healthy stream; wait for the viewer to press play.
                dbg('[Recovery] ready but paused - waiting for play');
                this.state = 'idle';
                if (typeof p._hideLoading === 'function') p._hideLoading();
                return;
            }
            this.fail('start-timeout');
        }

        _clearTimers() {
            if (this._timer) { clearTimeout(this._timer); this._timer = null; }
            if (this._toastTimer) { clearTimeout(this._toastTimer); this._toastTimer = null; }
        }

        destroy() {
            this._destroyed = true;
            this._clearTimers();
            const v = this.player && this.player.video;
            if (v) {
                v.removeEventListener('play', this._onPlay);
                v.removeEventListener('playing', this._onPlaying);
                v.removeEventListener('timeupdate', this._onTime);
                v.removeEventListener('waiting', this._onWaiting);
                v.removeEventListener('error', this._onError);
            }
        }
    };
})(window);
