// Player-specific JavaScript for handling prev/current/next songs

// Initialize player-specific DOM elements
let playerDOMElements = {};

function initializePlayerDOMElements() {
    playerDOMElements = {
        prevSongCoverImage: document.getElementById('prevSongCoverImage'),
        songCoverImage: document.getElementById('songCoverImage'),
        nextSongCoverImage: document.getElementById('nextSongCoverImage'),
        songTitle: document.getElementById('songTitle'),
        songArtist: document.getElementById('songArtist'),
        favicon: document.getElementById('favicon'),
        pageTitle: document.getElementById('pageTitle')
    };
}

// Update player images for prev/current/next songs
function updatePlayerImages(currentSong, prevSong, nextSong) {
    if (!playerDOMElements.songCoverImage) {
        initializePlayerDOMElements();
    }

    const currentArtwork = currentSong && currentSong.artworkBase64 && currentSong.artworkBase64 !== ''
            ? `data:image/jpeg;base64,${currentSong.artworkBase64}`
            : '/logo.png';

    // Update current song image and favicon synchronously
    if (playerDOMElements.songCoverImage) {
        playerDOMElements.songCoverImage.src = currentArtwork;
    }
    if (playerDOMElements.favicon) {
        playerDOMElements.favicon.href = currentArtwork;
    }

    // Update prev/next images asynchronously to avoid blocking
    requestAnimationFrame(() => {
        if (playerDOMElements.prevSongCoverImage) {
            if (prevSong && prevSong.artworkBase64 && prevSong.artworkBase64 !== '') {
                playerDOMElements.prevSongCoverImage.src = `data:image/jpeg;base64,${prevSong.artworkBase64}`;
                playerDOMElements.prevSongCoverImage.style.display = 'block';
            } else {
                playerDOMElements.prevSongCoverImage.src = '/logo.png';
                playerDOMElements.prevSongCoverImage.style.display = 'none';
            }
        }

        if (playerDOMElements.nextSongCoverImage) {
            if (nextSong && nextSong.artworkBase64 && nextSong.artworkBase64 !== '') {
                playerDOMElements.nextSongCoverImage.src = `data:image/jpeg;base64,${nextSong.artworkBase64}`;
                playerDOMElements.nextSongCoverImage.style.display = 'block';
            } else {
                playerDOMElements.nextSongCoverImage.src = '/logo.png';
                playerDOMElements.nextSongCoverImage.style.display = 'none';
            }
        }
    });
}

// Update player song details
function updatePlayerSongDetails(song) {
    if (!playerDOMElements.songTitle || !playerDOMElements.songArtist) {
        initializePlayerDOMElements();
    }

    if (playerDOMElements.songTitle) {
        playerDOMElements.songTitle.textContent = song ? (song.title || "Unknown Title") : "Loading...";
    }
    if (playerDOMElements.songArtist) {
        playerDOMElements.songArtist.textContent = song ? (song.artist || "Unknown Artist") : "Unknown Artist";
    }
    
    // Update page title
    if (song && playerDOMElements.pageTitle) {
        playerDOMElements.pageTitle.textContent = `${song.title || "Unknown Title"} - ${song.artist || "Unknown Artist"}`;
        document.title = `${song.title || "Unknown Title"} : ${song.artist || "Unknown Artist"}`;
    }
}

// Fetch player song context (prev, current, next)
async function fetchPlayerSongContext(profileId) {
    try {
        const [currentSongResponse, prevSongResponse, nextSongResponse] = await Promise.all([
            fetch(`/api/music/playback/current/${profileId}`).then(r => r.json()),
            fetch(`/api/music/playback/previousSong/${profileId}`).then(r => r.json()),
            fetch(`/api/music/playback/nextSong/${profileId}`).then(r => r.json())
        ]);

        const currentSong = currentSongResponse.data;
        const prevSong = prevSongResponse.data;
        const nextSong = nextSongResponse.data;

        if (currentSong) {
            updatePlayerImages(currentSong, prevSong, nextSong);
            updatePlayerSongDetails(currentSong);
        }
    } catch (error) {
        console.error('[Player] Failed to fetch song context:', error);
    }
}

// Override UpdateAudioSource for player page
const originalUpdateAudioSource = window.UpdateAudioSource;
window.UpdateAudioSource = function(currentSong, prevSong = null, nextSong = null, play = false, backendTime = 0) {
    // Call original function for core functionality
    if (originalUpdateAudioSource) {
        originalUpdateAudioSource(currentSong, prevSong, nextSong, play, backendTime);
    }
    
    // Update player-specific elements
    updatePlayerImages(currentSong, prevSong, nextSong);
    updatePlayerSongDetails(currentSong);
};

// Initialize player when DOM is ready
document.addEventListener('DOMContentLoaded', function() {
    initializePlayerDOMElements();
    
    // Fetch initial song context
    fetchPlayerSongContext(window.globalActiveProfileId);
    
    // Listen for WebSocket updates
    if (window.musicSocket) {
        window.musicSocket.addEventListener('message', function(event) {
            const data = JSON.parse(event.data);
            if (data.type === 'playback-state' && data.currentSongId) {
                fetchPlayerSongContext(window.globalActiveProfileId);
            }
        });
    }
});

// Export functions for global access
window.updatePlayerImages = updatePlayerImages;
window.updatePlayerSongDetails = updatePlayerSongDetails;
window.fetchPlayerSongContext = fetchPlayerSongContext;