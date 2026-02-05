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

// Race condition mitigation: track active fetch requests
let activeFetchRequests = new Map();

// Fetch player song context (prev, current, next)
async function fetchPlayerSongContext(profileId) {
    // Race condition mitigation: cancel previous outstanding requests
    const requestId = `player_${Date.now()}_${Math.random()}`;
    
    // Cancel any previous requests for this profile
    const previousRequestId = activeFetchRequests.get(profileId);
    if (previousRequestId) {
        console.log(`[Player] Canceling previous request ${previousRequestId} for ${requestId}`);
    }
    activeFetchRequests.set(profileId, requestId);

    try {
        // Race condition mitigation: add sequence tracking to ensure proper ordering
        const [currentSongResponse, prevSongResponse, nextSongResponse] = await Promise.all([
            fetch(`/api/music/playback/current/${profileId}?requestId=${requestId}`).then(r => r.json()),
            fetch(`/api/music/playback/previousSong/${profileId}?requestId=${requestId}`).then(r => r.json()),
            fetch(`/api/music/playback/nextSong/${profileId}?requestId=${requestId}`).then(r => r.json())
        ]);

        // Race condition mitigation: check if this request is still the most recent
        if (activeFetchRequests.get(profileId) !== requestId) {
            console.log(`[Player] Ignoring outdated request ${requestId}`);
            return;
        }

        const currentSong = currentSongResponse.data;
        const prevSong = prevSongResponse.data;
        const nextSong = nextSongResponse.data;

        if (currentSong) {
            updatePlayerImages(currentSong, prevSong, nextSong);
            updatePlayerSongDetails(currentSong);
        }
    } catch (error) {
        console.error('[Player] Failed to fetch song context:', error);
    } finally {
        // Clean up request tracking
        if (activeFetchRequests.get(profileId) === requestId) {
            activeFetchRequests.delete(profileId);
        }
    }
}

// Override UpdateAudioSource for player page
const originalUpdateAudioSource = window.UpdateAudioSource;
window.UpdateAudioSource = function(currentSong, prevSong = null, nextSong = null, play = false, backendTime = 0) {
    // Call original sequential function for core functionality
    if (window.UpdateAudioSourceSequentially) {
        window.UpdateAudioSourceSequentially(currentSong, prevSong, nextSong, play, backendTime);
    } else if (originalUpdateAudioSource) {
        originalUpdateAudioSource(currentSong, prevSong, nextSong, backendTime);
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

// Helper function to identify song list targets
function isSongListTarget(event) {
    const targetId = event.detail.target.id;
    return targetId === 'songTableBody' || targetId === 'mobileSongList';
}

// Song list caching functionality
document.addEventListener('htmx:beforeRequest', function(event) {
    // Show cached content immediately before making request
    if (isSongListTarget(event) && window.songCache) {
        const url = event.detail.requestConfig.path;
        const cached = window.songCache.loadPage(url);
        if (cached) {
            event.detail.target.innerHTML = cached.html;
        }
    }
});

document.addEventListener('htmx:afterRequest', function(event) {
    // Update cache with fresh response and update DOM
    if (isSongListTarget(event) && event.detail.successful && window.songCache) {
        const url = event.detail.requestConfig.path;
        const freshHtml = event.detail.xhr.response;
        
        // Update cache
        window.songCache.savePage(url, freshHtml);
        
        // Update DOM with fresh content
        event.detail.target.innerHTML = freshHtml;
    }
});

document.addEventListener('htmx:responseError', function(event) {
    // Use cached content for failed requests
    if (isSongListTarget(event) && window.songCache) {
        const url = event.detail.requestConfig.path;
        const cachedPage = window.songCache.loadPage(url);
        if (cachedPage) {
            event.detail.target.innerHTML = cachedPage.html;
            if (window.showToast) {
                window.showToast('Showing cached content (offline)', 'info');
            }
        }
    }
});

document.addEventListener('htmx:timeout', function(event) {
    // Use cached content for timeout scenarios
    if (isSongListTarget(event) && window.songCache) {
        const url = event.detail.requestConfig.path;
        const cachedPage = window.songCache.loadPage(url);
        if (cachedPage) {
            event.detail.target.innerHTML = cachedPage.html;
            if (window.showToast) {
                window.showToast('Showing cached content (timeout)', 'info');
            }
        }
    }
});

// Export functions for global access
window.updatePlayerImages = updatePlayerImages;
window.updatePlayerSongDetails = updatePlayerSongDetails;
window.fetchPlayerSongContext = fetchPlayerSongContext;