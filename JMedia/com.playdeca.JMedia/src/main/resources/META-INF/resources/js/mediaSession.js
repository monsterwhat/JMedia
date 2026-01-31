// mediaSession.js

// This file will handle the Media Session API integration.
// It assumes musicState, audio, apiPost, and setPlaybackTime are available from musicBar.js



if ('mediaSession' in navigator) {
    console.log("[mediaSession.js] Media Session API supported.");

    // Function to update Media Session metadata
    window.updateMediaSessionMetadata = (songName, artist, artworkUrl) => {
        const metadata = {
            title: songName || 'Unknown Title',
            artist: artist || 'Unknown Artist',
            album: 'JMedia' // You might want to get actual album from musicState
        };
        
        // Only add artwork if we have a valid URL
        if (artworkUrl && artworkUrl.trim() !== '' && artworkUrl !== 'logo.png') {
            metadata.artwork = [
                {src: artworkUrl, sizes: '96x96', type: 'image/png'},
                {src: artworkUrl, sizes: '128x128', type: 'image/png'},
                {src: artworkUrl, sizes: '192x192', type: 'image/png'},
                {src: artworkUrl, sizes: '256x256', type: 'image/png'},
                {src: artworkUrl, sizes: '384x384', type: 'image/png'},
                {src: artworkUrl, sizes: '512x512', type: 'image/png'}
            ];
        } else {
            // Use default logo as fallback
            metadata.artwork = [
                {src: '/logo.png', sizes: '96x96', type: 'image/png'},
                {src: '/logo.png', sizes: '128x128', type: 'image/png'},
                {src: '/logo.png', sizes: '192x192', type: 'image/png'},
                {src: '/logo.png', sizes: '256x256', type: 'image/png'},
                {src: '/logo.png', sizes: '384x384', type: 'image/png'},
                {src: '/logo.png', sizes: '512x512', type: 'image/png'}
            ];
        }
        
        try {
            navigator.mediaSession.metadata = new MediaMetadata(metadata);
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

    // Function to update Media Session playback state
    window.updateMediaSessionPlaybackState = (isPlaying) => {
        navigator.mediaSession.playbackState = isPlaying ? 'playing' : 'paused';
    };

    // Set up action handlers
    window.setupMediaSessionHandlers = (apiPost, setPlaybackTime, audio) => {
        navigator.mediaSession.setActionHandler('play', () => {
            console.log("[mediaSession.js] Media Session: 'play' action.");
            apiPost('toggle');
        });

        navigator.mediaSession.setActionHandler('pause', () => {
            console.log("[mediaSession.js] Media Session: 'pause' action.");
            apiPost('toggle');
        });

        navigator.mediaSession.setActionHandler('previoustrack', () => {
            console.log("[mediaSession.js] Media Session: 'previoustrack' action.");
            apiPost('previous');
        });

        navigator.mediaSession.setActionHandler('nexttrack', () => {
            console.log("[mediaSession.js] Media Session: 'nexttrack' action.");
            apiPost('next');
        });

        // Optional: Seek actions
        navigator.mediaSession.setActionHandler('seekto', (details) => {
            console.log("[mediaSession.js] Media Session: 'seekto' action to", details.seekTime);
            if (details.seekTime !== undefined && setPlaybackTime) {
                setPlaybackTime(details.seekTime, true);
            }
        });

        navigator.mediaSession.setActionHandler('seekbackward', (details) => {
            console.log("[mediaSession.js] Media Session: 'seekbackward' action by", details.seekOffset || 10, "seconds.");
            if (audio && setPlaybackTime) {
                const seekTime = audio.currentTime - (details.seekOffset || 10);
                setPlaybackTime(seekTime, true);
            }
        });

        navigator.mediaSession.setActionHandler('seekforward', (details) => {
            console.log("[mediaSession.js] Media Session: 'seekforward' action by", details.seekOffset || 10, "seconds.");
            if (audio && setPlaybackTime) {
                const seekTime = audio.currentTime + (details.seekOffset || 10);
                setPlaybackTime(seekTime, true);
            }
        });
 
        // navigator.mediaSession.setActionHandler('enterpictureinpicture', () => { /* ... */ });
        // navigator.mediaSession.setActionHandler('leavepictureinpicture', () => { /* ... */ });

        console.log("[mediaSession.js] Media Session action handlers set up.");
    };

} else {
    console.warn("[mediaSession.js] Media Session API is not supported in this browser.");
}
