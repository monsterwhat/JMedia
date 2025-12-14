// mediaSession.js

// This file will handle the Media Session API integration.
// It assumes musicState, audio, apiPost, and setPlaybackTime are available from musicBar.js

if ('mediaSession' in navigator) {
    console.log("[mediaSession.js] Media Session API supported.");

    // Function to update Media Session metadata
    window.updateMediaSessionMetadata = (songName, artist, artworkUrl) => {
        navigator.mediaSession.metadata = new MediaMetadata({
            title: songName,
            artist: artist,
            album: 'JMedia', // You might want to get the actual album from musicState
            artwork: [
                {src: artworkUrl, sizes: '96x96', type: 'image/png'},
                {src: artworkUrl, sizes: '128x128', type: 'image/png'},
                {src: artworkUrl, sizes: '192x192', type: 'image/png'},
                {src: artworkUrl, sizes: '256x256', type: 'image/png'},
                {src: artworkUrl, sizes: '384x384', type: 'image/png'},
                {src: artworkUrl, sizes: '512x512', type: 'image/png'}
            ]
        });
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
