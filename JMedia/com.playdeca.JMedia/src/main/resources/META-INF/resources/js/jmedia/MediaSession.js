(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    if (!('mediaSession' in navigator)) {
        console.log("[MediaSession.js] Media Session API not supported in this browser.");
        return;
    }

    console.log("[MediaSession.js] Media Session API supported.");

    JMedia.MediaSession = {};

    JMedia.MediaSession.updateMetadata = (songName, artist, artworkUrl) => {
        const metadata = {
            title: songName || 'Unknown Title',
            artist: artist || 'Unknown Artist',
            album: 'JMedia'
        };

        const getAbsoluteUrl = (url) => {
            if (!url) return null;
            if (url.startsWith('data:') || url.startsWith('http')) return url;
            return window.location.origin + (url.startsWith('/') ? '' : '/') + url;
        };

        const defaultLogo = getAbsoluteUrl('/logo.png');
        const finalArtwork = (artworkUrl && artworkUrl.trim() !== '' && artworkUrl !== 'logo.png')
            ? artworkUrl
            : defaultLogo;

        metadata.artwork = [
            {src: finalArtwork, sizes: '96x96', type: 'image/png'},
            {src: finalArtwork, sizes: '128x128', type: 'image/png'},
            {src: finalArtwork, sizes: '192x192', type: 'image/png'},
            {src: finalArtwork, sizes: '256x256', type: 'image/png'},
            {src: finalArtwork, sizes: '384x384', type: 'image/png'},
            {src: finalArtwork, sizes: '512x512', type: 'image/png'}
        ];

        try {
            navigator.mediaSession.metadata = new MediaMetadata(metadata);
            JMedia.MediaSession.updatePositionState();
        } catch (error) {
            console.warn('[MediaSession.js] Failed to set MediaMetadata:', error);
            try {
                navigator.mediaSession.metadata = new MediaMetadata({
                    title: songName || 'Unknown Title',
                    artist: artist || 'Unknown Artist',
                    album: 'JMedia'
                });
            } catch (fallbackError) {
                console.warn('[MediaSession.js] Fallback MediaMetadata also failed:', fallbackError);
            }
        }
    };

    JMedia.MediaSession.updatePlaybackState = (isPlaying) => {
        navigator.mediaSession.playbackState = isPlaying ? 'playing' : 'paused';
    };

    JMedia.MediaSession.updatePositionState = () => {
        const audioEl = JMedia.PlaybackApi.getAudioElement();
        if (!navigator.mediaSession.setPositionState || !audioEl) return;

        try {
            const duration = audioEl.duration;
            const currentTime = audioEl.currentTime;

            if (isFinite(duration) && isFinite(currentTime) && duration > 0) {
                navigator.mediaSession.setPositionState({
                    duration: duration,
                    playbackRate: audioEl.playbackRate || 1,
                    position: currentTime
                });
            }
        } catch (error) {
        }
    };

    JMedia.MediaSession.setupHandlers = (apiPost, setPlaybackTime, audio) => {
        var _isVideoBlocked = function() {
            if (window.VideoModeCoordinator && window.VideoModeCoordinator.isVideoActive()) return true;
            if (window.videoPlaying) return true;
            return false;
        };
        const handlePlayPause = () => {
            if (_isVideoBlocked()) { console.log("[MediaSession.js] Blocked play/pause — video is active"); return; }
            console.log("[MediaSession.js] Media Session: 'play/pause' action.");
            window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                detail: { action: 'playPause', profileId: window.globalActiveProfileId }
            }));
        };

        navigator.mediaSession.setActionHandler('play', handlePlayPause);
        navigator.mediaSession.setActionHandler('pause', handlePlayPause);

        navigator.mediaSession.setActionHandler('previoustrack', () => {
            if (_isVideoBlocked()) { console.log("[MediaSession.js] Blocked previoustrack — video is active"); return; }
            console.log("[MediaSession.js] Media Session: 'previoustrack' action.");
            window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                detail: { action: 'previous', profileId: window.globalActiveProfileId }
            }));
        });

        navigator.mediaSession.setActionHandler('nexttrack', () => {
            if (_isVideoBlocked()) { console.log("[MediaSession.js] Blocked nexttrack — video is active"); return; }
            console.log("[MediaSession.js] Media Session: 'nexttrack' action.");
            window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                detail: { action: 'next', profileId: window.globalActiveProfileId }
            }));
        });

        navigator.mediaSession.setActionHandler('seekto', (details) => {
            if (_isVideoBlocked()) { console.log("[MediaSession.js] Blocked seekto — video is active"); return; }
            console.log("[MediaSession.js] Media Session: 'seekto' action to", details.seekTime);
            window.dispatchEvent(new CustomEvent('requestAudioControl', {
                detail: { action: 'setTime', value: details.seekTime, source: 'mediaSession' }
            }));
        });

        navigator.mediaSession.setActionHandler('seekbackward', (details) => {
            if (_isVideoBlocked()) { console.log("[MediaSession.js] Blocked seekbackward — video is active"); return; }
            const skipTime = details.seekOffset || 10;
            console.log("[MediaSession.js] Media Session: 'seekbackward' action by", skipTime, "seconds.");
            const audioEl = JMedia.PlaybackApi.getAudioElement();
            if (audioEl) {
                const newTime = Math.max(0, audioEl.currentTime - skipTime);
                window.dispatchEvent(new CustomEvent('requestAudioControl', {
                    detail: { action: 'setTime', value: newTime, source: 'mediaSession' }
                }));
            }
        });

        navigator.mediaSession.setActionHandler('seekforward', (details) => {
            if (_isVideoBlocked()) { console.log("[MediaSession.js] Blocked seekforward — video is active"); return; }
            const skipTime = details.seekOffset || 10;
            console.log("[MediaSession.js] Media Session: 'seekforward' action by", skipTime, "seconds.");
            const audioEl = JMedia.PlaybackApi.getAudioElement();
            if (audioEl) {
                const newTime = Math.min(audioEl.duration, audioEl.currentTime + skipTime);
                window.dispatchEvent(new CustomEvent('requestAudioControl', {
                    detail: { action: 'setTime', value: newTime, source: 'mediaSession' }
                }));
            }
        });

        navigator.mediaSession.setActionHandler('stop', () => {
            if (_isVideoBlocked()) { console.log("[MediaSession.js] Blocked stop — video is active"); return; }
            console.log("[MediaSession.js] Media Session: 'stop' action.");
            window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                detail: { action: 'pause', profileId: window.globalActiveProfileId }
            }));
        });

        console.log("[MediaSession.js] Media Session handlers initialized.");
    };

    const getPlayerForPosition = () => {
        return JMedia.PlaybackApi.getAudioElement();
    };

    const attachTimeUpdate = (el) => {
        if (el) {
            el.addEventListener('timeupdate', () => {
                JMedia.MediaSession.updatePositionState();
            });
        }
    };

    const player = getPlayerForPosition();
    if (player) {
        attachTimeUpdate(player);
    } else {
        window.addEventListener('audioMetadataLoaded', () => {
            attachTimeUpdate(getPlayerForPosition());
        });
    }

    window.addEventListener('audioPlayerSwapped', () => {
        attachTimeUpdate(getPlayerForPosition());
    });

    window.updateMediaSessionMetadata = JMedia.MediaSession.updateMetadata;
    window.updateMediaSessionPlaybackState = JMedia.MediaSession.updatePlaybackState;
    window.updateMediaSessionPositionState = JMedia.MediaSession.updatePositionState;
    window.setupMediaSessionHandlers = JMedia.MediaSession.setupHandlers;

    // music-bundle-loader.js loads MusicBarInit.js BEFORE this file, so its
    // guarded setupMediaSessionHandlers() call can run while this function is
    // still undefined and no action handlers get registered (metadata still
    // updated, so the OS shows the song but media keys do nothing). Register
    // here as well; setActionHandler simply replaces an existing handler.
    // iOS is skipped on purpose: handlers there suspend background audio.
    const isIOS = /iPhone|iPad|iPod|iPadOS/i.test(navigator.userAgent) ||
        (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1) ||
        /iPhone|iPad/i.test(navigator.platform || '');
    if (!isIOS) {
        JMedia.MediaSession.setupHandlers(null, null, null);
    }

})(window);
