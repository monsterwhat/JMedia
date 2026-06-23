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
        const handlePlayPause = () => {
            console.log("[MediaSession.js] Media Session: 'play/pause' action.");
            window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                detail: { action: 'playPause', profileId: window.globalActiveProfileId }
            }));
        };

        navigator.mediaSession.setActionHandler('play', handlePlayPause);
        navigator.mediaSession.setActionHandler('pause', handlePlayPause);

        navigator.mediaSession.setActionHandler('previoustrack', () => {
            console.log("[MediaSession.js] Media Session: 'previoustrack' action.");
            window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                detail: { action: 'previous', profileId: window.globalActiveProfileId }
            }));
        });

        navigator.mediaSession.setActionHandler('nexttrack', () => {
            console.log("[MediaSession.js] Media Session: 'nexttrack' action.");
            window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                detail: { action: 'next', profileId: window.globalActiveProfileId }
            }));
        });

        navigator.mediaSession.setActionHandler('seekto', (details) => {
            console.log("[MediaSession.js] Media Session: 'seekto' action to", details.seekTime);
            window.dispatchEvent(new CustomEvent('requestAudioControl', {
                detail: { action: 'setTime', value: details.seekTime, source: 'mediaSession' }
            }));
        });

        navigator.mediaSession.setActionHandler('seekbackward', (details) => {
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

})(window);
