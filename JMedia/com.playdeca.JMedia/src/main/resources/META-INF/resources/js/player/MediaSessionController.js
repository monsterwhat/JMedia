(function (window) {
    'use strict';

    /**
     * Media Session for the video player: the browser's media hub, the
     * keyboard/headset media keys and the lock screen control the video.
     *
     * The music player registers page-wide handlers that return early while a
     * video plays. Because handlers exist, Chrome sends the media keys to them
     * instead of the video element, so play/pause did nothing during a movie and
     * the hub kept showing the last song. This takes the session over while the
     * video plays and gives it back to the music player on teardown.
     */
    window.PlayerMediaSession = class {
        constructor(player) {
            this.player = player;
            this._active = false;
            this._lastPositionAt = 0;
            if (!('mediaSession' in navigator) || !player || !player.video) return;

            const v = player.video;
            this._onPlaying = () => this.activate();
            this._onPlayState = () => this.updateState(true);
            this._onTime = () => this.updateState(false);
            v.addEventListener('playing', this._onPlaying);
            v.addEventListener('play', this._onPlayState);
            v.addEventListener('pause', this._onPlayState);
            v.addEventListener('ratechange', this._onPlayState);
            v.addEventListener('timeupdate', this._onTime);
        }

        _set(action, handler) {
            try {
                navigator.mediaSession.setActionHandler(action, handler);
            } catch (e) {
                // Older browsers reject actions they do not know (e.g. seekto).
            }
        }

        /** Title, artwork and handlers for the current video. Re-run on every `playing`. */
        activate() {
            const p = this.player;
            if (!('mediaSession' in navigator) || p._destroyed) return;
            const ds = p.container ? p.container.dataset : {};
            const episode = Number.parseInt(ds.episodeNumber || '0', 10);
            const subtitle = ds.seriesTitle && episode > 0
                ? ds.seriesTitle + ' · S' + (ds.seasonNumber || '1') + 'E' + episode
                : (ds.seriesTitle || '');
            try {
                navigator.mediaSession.metadata = new MediaMetadata({
                    title: ds.title || 'Video',
                    artist: subtitle,
                    artwork: p.videoId ? [{ src: '/api/video/thumbnail/' + p.videoId, type: 'image/jpeg' }] : []
                });
            } catch (e) {
                console.warn('[PlayerMediaSession] Could not set metadata:', e);
            }

            // Same user-pause gate as the on-screen play button, so server sync
            // does not undo a pause made from the keyboard or the media hub.
            this._set('play', () => {
                p._userPaused = false;
                p._userPausedAt = 0;
                p.video.play().catch((e) => console.warn('[PlayerMediaSession] play failed', e));
                if (typeof p._broadcastState === 'function') p._broadcastState();
            });
            this._set('pause', () => {
                p._userPaused = true;
                p._userPausedAt = Date.now();
                p.video.pause();
                if (typeof p._broadcastState === 'function') p._broadcastState();
                if (p.progressReporter) p.progressReporter.saveNow(true);
            });
            this._set('seekbackward', (d) => this._seekBy(-((d && d.seekOffset) || 10)));
            this._set('seekforward', (d) => this._seekBy((d && d.seekOffset) || 10));
            this._set('seekto', (d) => {
                if (d && typeof d.seekTime === 'number' && isFinite(d.seekTime)) {
                    p.performServerSeek(Math.max(0, d.seekTime));
                }
            });
            const hasId = (id) => id && id !== 'null' && id !== 'undefined' && id !== '0';
            this._set('nexttrack', hasId(ds.nextId) ? () => p.playNextEpisode() : null);
            this._set('previoustrack', hasId(ds.prevId) ? () => p.playPreviousEpisode() : null);

            this._active = true;
            this.updateState(true);
        }

        _seekBy(delta) {
            const p = this.player;
            const now = (p.video.currentTime || 0) + (p.streamStartOffset || 0);
            const end = p.totalDuration > 0 ? p.totalDuration : Infinity;
            p.performServerSeek(Math.min(end, Math.max(0, now + delta)));
        }

        /** Playback state and position for the hub's progress bar (position at most once a second). */
        updateState(force) {
            if (!this._active || !('mediaSession' in navigator)) return;
            const p = this.player;
            try {
                navigator.mediaSession.playbackState = p.video.paused ? 'paused' : 'playing';
            } catch (e) { /* not supported */ }
            const now = Date.now();
            if (!force && now - this._lastPositionAt < 1000) return;
            this._lastPositionAt = now;
            if (typeof navigator.mediaSession.setPositionState !== 'function') return;
            const duration = p.totalDuration > 0 ? p.totalDuration : p.video.duration;
            if (!(duration > 0) || !isFinite(duration)) return;
            const position = Math.min(duration, Math.max(0, (p.video.currentTime || 0) + (p.streamStartOffset || 0)));
            try {
                navigator.mediaSession.setPositionState({
                    duration: duration,
                    playbackRate: p.video.playbackRate || 1,
                    position: position
                });
            } catch (e) { /* invalid state while a source is being swapped */ }
        }

        /** Gives the session back to the music player. Called from the player's destroy(). */
        release() {
            const p = this.player;
            if (p && p.video && this._onPlaying) {
                const v = p.video;
                v.removeEventListener('playing', this._onPlaying);
                v.removeEventListener('play', this._onPlayState);
                v.removeEventListener('pause', this._onPlayState);
                v.removeEventListener('ratechange', this._onPlayState);
                v.removeEventListener('timeupdate', this._onTime);
            }
            if (!this._active || !('mediaSession' in navigator)) return;
            this._active = false;
            ['play', 'pause', 'seekbackward', 'seekforward', 'seekto', 'nexttrack', 'previoustrack']
                .forEach((a) => this._set(a, null));
            try {
                navigator.mediaSession.metadata = null;
                navigator.mediaSession.playbackState = 'none';
            } catch (e) { /* not supported */ }
            // Re-register the music player's handlers (they stay inert until music plays).
            if (typeof window.setupMediaSessionHandlers === 'function') {
                try {
                    window.setupMediaSessionHandlers(null, null, null);
                } catch (e) {
                    console.warn('[PlayerMediaSession] Could not restore music media keys:', e);
                }
            }
        }
    };
})(window);
