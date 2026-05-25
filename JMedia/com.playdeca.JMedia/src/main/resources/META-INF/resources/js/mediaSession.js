// mediaSession.js

/**
 * Media Session API Integration
 * Handles remote playback controls (lock screen, headphones, Alexa, CarPlay)
 * Integrates with the new modular musicBar system via CustomEvents.
 */

if ('mediaSession' in navigator) {
    console.log("[mediaSession.js] Media Session API supported.");

    /**
     * Update Media Session metadata
     * @param {string} songName - Name of the song
     * @param {string} artist - Name of the artist
     * @param {string} artworkUrl - URL to the artwork (data URI or absolute URL)
     */
    window.updateMediaSessionMetadata = (songName, artist, artworkUrl) => {
        const metadata = {
            title: songName || 'Unknown Title',
            artist: artist || 'Unknown Artist',
            album: 'JMedia'
        };
        
        // Ensure absolute URLs for better iOS/Remote compatibility
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
            // Also update position state when metadata changes
            window.updateMediaSessionPositionState();
        } catch (error) {
            console.warn('[mediaSession.js] Failed to set MediaMetadata:', error);
            // Fallback without artwork
            try {
                navigator.mediaSession.metadata = new MediaMetadata({
                    title: songName || 'Unknown Title',
                    artist: artist || 'Unknown Artist',
                    album: 'JMedia'
                });
            } catch (fallbackError) {
                console.warn('[mediaSession.js] Fallback MediaMetadata also failed:', fallbackError);
            }
        }
    };

    /**
     * Update Media Session playback state
     * @param {boolean} isPlaying - Current playback state
     */
    window.updateMediaSessionPlaybackState = (isPlaying) => {
        navigator.mediaSession.playbackState = isPlaying ? 'playing' : 'paused';
    };

    /**
     * Update Media Session position state
     * Critical for iOS lock screen progress bar and remote controls
     */
    window.updateMediaSessionPositionState = () => {
        const audioEl = window.AudioEngine ? window.AudioEngine.getAudioElement() : window.audio;
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
            // Silently fail as position updates happen frequently
        }
    };

    /**
     * Set up action handlers
     * Maps Media Session actions to JMedia internal events
     * @param {Function} apiPost - Legacy apiPost function (kept for signature compatibility)
     * @param {Function} setPlaybackTime - Legacy setPlaybackTime function
     * @param {HTMLAudioElement} audio - Audio element
     */
    window.setupMediaSessionHandlers = (apiPost, setPlaybackTime, audio) => {
        // Shared play/pause handler
        const handlePlayPause = () => {
            console.log("[mediaSession.js] Media Session: 'play/pause' action.");
            window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                detail: { action: 'playPause', profileId: window.globalActiveProfileId }
            }));
        };

        navigator.mediaSession.setActionHandler('play', handlePlayPause);
        navigator.mediaSession.setActionHandler('pause', handlePlayPause);

        // Previous Track
        navigator.mediaSession.setActionHandler('previoustrack', () => {
            console.log("[mediaSession.js] Media Session: 'previoustrack' action.");
            window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                detail: { action: 'previous', profileId: window.globalActiveProfileId }
            }));
        });

        // Next Track
        navigator.mediaSession.setActionHandler('nexttrack', () => {
            console.log("[mediaSession.js] Media Session: 'nexttrack' action.");
            window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                detail: { action: 'next', profileId: window.globalActiveProfileId }
            }));
        });

        // Seek Actions
        navigator.mediaSession.setActionHandler('seekto', (details) => {
            console.log("[mediaSession.js] Media Session: 'seekto' action to", details.seekTime);
            window.dispatchEvent(new CustomEvent('requestAudioControl', {
                detail: { action: 'setTime', value: details.seekTime, source: 'mediaSession' }
            }));
        });

        navigator.mediaSession.setActionHandler('seekbackward', (details) => {
            const skipTime = details.seekOffset || 10;
            console.log("[mediaSession.js] Media Session: 'seekbackward' action by", skipTime, "seconds.");
            if (window.audio) {
                const newTime = Math.max(0, window.audio.currentTime - skipTime);
                window.dispatchEvent(new CustomEvent('requestAudioControl', {
                    detail: { action: 'setTime', value: newTime, source: 'mediaSession' }
                }));
            }
        });

        navigator.mediaSession.setActionHandler('seekforward', (details) => {
            const skipTime = details.seekOffset || 10;
            console.log("[mediaSession.js] Media Session: 'seekforward' action by", skipTime, "seconds.");
            if (window.audio) {
                const newTime = Math.min(window.audio.duration, window.audio.currentTime + skipTime);
                window.dispatchEvent(new CustomEvent('requestAudioControl', {
                    detail: { action: 'setTime', value: newTime, source: 'mediaSession' }
                }));
            }
        });

        // Stop handler
        navigator.mediaSession.setActionHandler('stop', () => {
            console.log("[mediaSession.js] Media Session: 'stop' action.");
            window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                detail: { action: 'pause', profileId: window.globalActiveProfileId }
            }));
        });

        console.log("[mediaSession.js] Media Session handlers initialized.");
    };

    // Initialize position state updates - use AudioEngine when available
    const getPlayerForPosition = () => {
        return window.AudioEngine ? window.AudioEngine.getAudioElement() : window.audio;
    };

    const attachTimeUpdate = (el) => {
        if (el) {
            el.addEventListener('timeupdate', () => {
                window.updateMediaSessionPositionState();
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

    // Re-attach when audio player changes (crossfade swap)
    window.addEventListener('audioPlayerSwapped', () => {
        attachTimeUpdate(getPlayerForPosition());
    });

} else {
    console.log("[mediaSession.js] Media Session API not supported in this browser.");
}
