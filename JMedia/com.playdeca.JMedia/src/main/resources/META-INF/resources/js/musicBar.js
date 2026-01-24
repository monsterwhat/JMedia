const audio = document.getElementById('audioPlayer');
// ---------------- State ----------------
const musicState = {
    currentSongId: null,
    songName: "Loading...",
    artist: "Unknown Artist",
    playing: false,
    currentTime: 0,
    duration: 0,
    volume: 0.8,
    shuffleMode: "OFF", // Changed from shuffleEnabled
    repeatMode: "OFF",
    cue: [],
    hasLyrics: false// New property to track the current queue for change detection
};
window.musicState = musicState; // Expose musicState globally
let draggingSeconds = false;
let draggingVolume = false;
let mySessionId = null; // New global variable to store this client's session ID // New flag
let isUpdatingAudioSource = false; // New flag to prevent ontimeupdate during audio source changes
// Race condition mitigation: track active audio operations
let activeAudioOperation = null;
let audioOperationSequence = 0;

const volumeExponent = 2; // Moved to global scope

const calculateExponentialVolume = (sliderValue) => { // Moved to global scope
    const linearVol = sliderValue / 100;
    return Math.pow(linearVol, volumeExponent);
};
const calculateLinearSliderValue = (exponentialVol) => { // Moved to global scope
    const linearVol = Math.pow(exponentialVol, 1 / volumeExponent);
    return linearVol * 100;
};
audio.volume = musicState.volume;
// Race condition mitigation: track profile initialization
let profileInitializationPromise = null;

function waitForProfileId() {
    if (profileInitializationPromise) {
        return profileInitializationPromise;
    }
    
    profileInitializationPromise = new Promise((resolve) => {
        const checkProfile = () => {
            if (window.globalActiveProfileId && window.globalActiveProfileId !== 'undefined') {
                resolve(window.globalActiveProfileId);
            } else {
                setTimeout(checkProfile, 50);
            }
        };
        checkProfile();
    });
    
    return profileInitializationPromise;
}

// Race condition mitigation: async API Post function with profile validation
const apiPost = async (path, profileId) => {
    // Race condition mitigation: ensure we have a valid profile ID
    let profileIdValue = profileId;
    if (!profileIdValue) {
        profileIdValue = await waitForProfileId();
    }
    return fetch(`/api/music/playback/${path}/${profileIdValue}`, {method: 'POST'});
};
// ---------------- Helpers ----------------
const formatTime = s => {
    if (s === null || s === undefined || isNaN(s)) {
        return "0:00";
    }
    return `${Math.floor(s / 60)}:${Math.floor(s % 60).toString().padStart(2, '0')}`;
};
function throttle(func, delay) {
    let lastCall = 0;
    return function (...args) {
        const now = new Date().getTime();
        if (now - lastCall < delay) {
            return;
        }
        lastCall = now;
        return func(...args);
    };
}

function debounce(func, delay) {
    let timeoutId;
    return function (...args) {
        clearTimeout(timeoutId);
        timeoutId = setTimeout(() => func.apply(this, args), delay);
    };
}

function applyMarqueeEffect(elementId, text) {
    const element = document.getElementById(elementId);
    if (element) {
        element.innerText = text; // Set text first
        // Check if text overflows
        if (element.scrollWidth > element.clientWidth) {
            element.classList.add('marquee');
            element.classList.remove('no-scroll');
        } else {
            element.classList.remove('marquee');
            element.classList.add('no-scroll');
        }
    }
}

function updateMusicBar() {
    const {songName, artist, playing, currentTime, duration, volume, shuffleMode, repeatMode} = musicState;
    const titleEl = document.getElementById('songTitle');
    const artistEl = document.getElementById('songArtist');
    if (titleEl)
        titleEl.innerText = songName ?? "Unknown Title";
    if (artistEl)
        artistEl.innerText = artist ?? "Unknown Artist";
    // Apply marquee effect to main song title and artist
    applyMarqueeEffect('songTitle', songName ?? "Unknown Title");
    applyMarqueeEffect('songArtist', artist ?? "Unknown Artist");

    // Update mobile song info
    const titleMobileEl = document.getElementById('songTitleMobile');
    const artistMobileEl = document.getElementById('songArtistMobile');
    if (titleMobileEl)
        titleMobileEl.innerText = songName ?? "Unknown Title";
    if (artistMobileEl)
        artistMobileEl.innerText = artist ?? "Unknown Artist";
    applyMarqueeEffect('songTitleMobile', songName ?? "Unknown Title");
    applyMarqueeEffect('songArtistMobile', artist ?? "Unknown Artist");
    const playPauseIcon = document.getElementById('playPauseIcon');
    if (playPauseIcon) {
        playPauseIcon.className = "pi"; // Base class
        if (playing) {
            playPauseIcon.classList.add("pi-pause", "has-text-warning");
        } else {
            playPauseIcon.classList.add("pi-play", "has-text-success");
        }
    }
    document.getElementById('currentTime').innerText = formatTime(Math.floor(currentTime));
    document.getElementById('totalTime').innerText = formatTime(duration);
    const timeSlider = document.getElementById('playbackProgressBar'); // Use getElementById for direct access
    if (timeSlider) {
        timeSlider.max = duration;
        // Update the slider's value based on audio.currentTime if not dragging
        // If dragging, the slider's value is already updated by the browser
        if (!draggingSeconds) {
            timeSlider.value = audio.currentTime;
        }

// Always update the progress bar's visual fill, regardless of dragging state
// Use the current value of the slider for calculation, which will be
// either currentTime (if not dragging) or the dragged value (if dragging)
        const currentSliderValue = parseFloat(timeSlider.value);
        const progress = (currentSliderValue / duration) * 100;
        timeSlider.style.setProperty('--progress-value', `${progress}%`);
    }

    const volumeSlider = document.getElementById('volumeProgressBar'); // Use getElementById
    if (volumeSlider) {
// Update the slider's value based on musicState.volume if not dragging
        if (!draggingVolume) {
            volumeSlider.value = calculateLinearSliderValue(volume);
        }

// Always update the progress bar's visual fill, regardless of dragging state
        const currentSliderValue = parseFloat(volumeSlider.value);
        const progress = (currentSliderValue / 100) * 100; // Volume max is 100
        volumeSlider.style.setProperty('--progress-value', `${progress}%`);
    }

    const shuffleIcon = document.getElementById('shuffleIcon');
    if (shuffleIcon) {
        shuffleIcon.className = "pi"; // Base class
        switch (musicState.shuffleMode) {
            case "SHUFFLE":
                shuffleIcon.classList.add("pi-sort-alt-slash", "has-text-success");
                break;
            case "SMART_SHUFFLE":
                shuffleIcon.classList.add("pi-sparkles", "has-text-info"); // Using pi-sitemap for smart shuffle, has-text-info for blue
                break;
            case "OFF":
            default:
                shuffleIcon.classList.add("pi-sort-alt");
                break;
        }
    }

    const repeatIcon = document.getElementById('repeatIcon');
    if (repeatIcon) {
        repeatIcon.className = "pi pi-refresh"; // Base class
        if (musicState.repeatMode === "ONE") {
            repeatIcon.classList.add("has-text-info");
        } else if (musicState.repeatMode === "ALL") {
            repeatIcon.classList.add("has-text-success");
            repeatIcon.classList.add("pi-spin");
        }
    }

// Update Media Session API
    if ('mediaSession' in navigator && window.updateMediaSessionMetadata && window.updateMediaSessionPlaybackState) {
        const songCoverImageEl = document.getElementById('songCoverImage');
        const artworkUrl = songCoverImageEl ? songCoverImageEl.src : '/logo.png';
        window.updateMediaSessionMetadata(musicState.songName, musicState.artist, artworkUrl);
        window.updateMediaSessionPlaybackState(musicState.playing);
    }
}

// ---------------- Audio Events ----------------
const throttledSendWS = throttle(sendWS, 100); // Send a message at most every 300ms

audio.ontimeupdate = () => {
    if (!draggingSeconds && !isUpdatingAudioSource) { // Add isUpdatingAudioSource check
        musicState.currentTime = audio.currentTime;
        updateMusicBar(); // Update UI more frequently for smooth playback bar
    }
};
audio.onended = () => {
    fetch(`/api/music/playback/next/${window.globalActiveProfileId}`, {method: 'POST'});
};
// Cache DOM elements to avoid repeated queries
const domElements = {
    songCoverImage: null,
    prevSongCoverImage: null,
    nextSongCoverImage: null,
    favicon: null,
    pageTitle: null,
    songTitle: null,
    songArtist: null
};

// Initialize DOM element cache
function initializeDOMElements() {
    domElements.songCoverImage = document.getElementById('songCoverImage');
    domElements.prevSongCoverImage = document.getElementById('prevSongCoverImage');
    domElements.nextSongCoverImage = document.getElementById('nextSongCoverImage');
    domElements.favicon = document.getElementById('favicon');
    domElements.pageTitle = document.getElementById('pageTitle');
    domElements.songTitle = document.getElementById('songTitle');
    domElements.songArtist = document.getElementById('songArtist');
}

// Optimized image update function
function updateImages(currentSong, prevSong, nextSong) {
    const currentArtwork = currentSong.artworkBase64 && currentSong.artworkBase64 !== ''
            ? `data:image/jpeg;base64,${currentSong.artworkBase64}`
            : '/logo.png';

    // Update current song image and favicon synchronously
    if (domElements.songCoverImage) {
        domElements.songCoverImage.src = currentArtwork;
    }
    if (domElements.favicon) {
        domElements.favicon.href = currentArtwork;
    }

    // Update prev/next images asynchronously to avoid blocking
    requestAnimationFrame(() => {
        if (domElements.prevSongCoverImage) {
            if (prevSong && prevSong.artworkBase64 && prevSong.artworkBase64 !== '') {
                domElements.prevSongCoverImage.src = `data:image/jpeg;base64,${prevSong.artworkBase64}`;
                domElements.prevSongCoverImage.style.display = 'block';
            } else {
                domElements.prevSongCoverImage.src = '/logo.png';
                domElements.prevSongCoverImage.style.display = 'none';
            }
        }

        if (domElements.nextSongCoverImage) {
            if (nextSong && nextSong.artworkBase64 && nextSong.artworkBase64 !== '') {
                domElements.nextSongCoverImage.src = `data:image/jpeg;base64,${nextSong.artworkBase64}`;
                domElements.nextSongCoverImage.style.display = 'block';
            } else {
                domElements.nextSongCoverImage.src = '/logo.png';
                domElements.nextSongCoverImage.style.display = 'none';
            }
        }
    });
}

// Deferred memory cleanup to prevent blocking
function deferCleanup() {
    setTimeout(() => {
        try {
            if (window.previousSongData) {
                Object.values(window.previousSongData).forEach(song => {
                    if (song && song.artworkBase64) {
                        song.artworkBase64 = null;
                    }
                });
            }
        } catch (error) {
            // Silent cleanup failure - non-critical
        }
    }, 100); // Defer by 100ms
}

// ---------------- Update Audio Source ----------------
function UpdateAudioSource(currentSong, prevSong = null, nextSong = null, play = false, backendTime = 0) {
    // Race condition mitigation: cancel any previous audio operations
    const operationId = `audio_${++audioOperationSequence}`;
    
    if (activeAudioOperation) {
        console.log(`[musicBar] Canceling previous audio operation ${activeAudioOperation} for ${operationId}`);
    }
    activeAudioOperation = operationId;
    
    isUpdatingAudioSource = true; // Set flag to true at the beginning

    if (!currentSong || !currentSong.id) {
        isUpdatingAudioSource = false; // Reset flag if no current song
        return;
    }

    // Initialize DOM elements cache if not already done
    if (!domElements.songCoverImage) {
        initializeDOMElements();
    }

    const sameSong = String(musicState.currentSongId) === String(currentSong.id);
    const newAudioSrc = `/api/music/stream/${window.globalActiveProfileId}/${currentSong.id}`;

    // Update state
    musicState.currentSongId = currentSong.id;
    musicState.songName = currentSong.title ?? "Unknown Title";
    if (currentSong.artist !== null && currentSong.artist !== undefined) {
        musicState.artist = currentSong.artist;
    }
    musicState.currentTime = (sameSong || backendTime !== 0) ? (backendTime ?? 0) : 0;
    musicState.duration = currentSong.durationSeconds ?? 0;
    musicState.hasLyrics = currentSong.lyrics !== null && currentSong.lyrics !== undefined && currentSong.lyrics !== '';

    // Only update audio source if it actually changed
    if (audio.src !== newAudioSrc) {

        // Clear audio buffer only when necessary
        try {
            audio.src = '';
            audio.load(); // Clear buffer
        } catch (error) {
            // Silent cleanup failure - non-critical
        }

        audio.src = newAudioSrc;
        audio.load();
    }

    // Only update volume if it changed
    if (audio.volume !== musicState.volume) {
        audio.volume = musicState.volume;
    }

    // Update images asynchronously to avoid blocking
    updateImages(currentSong, prevSong, nextSong);

    // Update page title
    updatePageTitle({name: musicState.songName, artist: musicState.artist});

    // Optimized metadata handling
    audio.onloadedmetadata = () => {

        // Update duration if valid
        if (typeof audio.duration === 'number' && !isNaN(audio.duration) && audio.duration > 0) {
            musicState.duration = audio.duration;
        }

        // Validate and set current time
        if (musicState.currentTime >= 0 && musicState.currentTime <= audio.duration) {
            audio.currentTime = musicState.currentTime;
        }

        // Handle playback
        if (play) {
            audio.play().catch(console.error);
        } else {
            audio.pause();
        }

        // Defer non-critical updates to prevent blocking
        requestAnimationFrame(() => {
            // Race condition mitigation: check if this operation is still active
            if (activeAudioOperation === operationId) {
                updateMusicBar();
                deferCleanup(); // Move cleanup here
                isUpdatingAudioSource = false; // Reset flag after UI update
                activeAudioOperation = null; // Clear active operation
            } else {
                console.log(`[musicBar] Ignoring updates for outdated audio operation ${operationId}`);
                isUpdatingAudioSource = false; // Still reset flag
            }
        });

        
    };
}


// ---------------- WebSocket ----------------
let ws;
let songContextCache = null;
let cacheTimestamp = 0;
const CACHE_DURATION = 1000; // 1 second cache
// Race condition mitigation: message queuing system
let messageQueue = [];
let isProcessingMessage = false;
let messageSequenceNumber = 0;

// Debounced functions to prevent rapid updates
const debouncedUpdateQueueCount = debounce((count) => {
    if (window.updateQueueCount) {
        window.updateQueueCount(count);
    }
}, 100);

const debouncedUpdateSelectedSong = debounce((songId) => {
    updateSelectedSongRow(songId);
    if (window.updateQueueCurrentSong) {
        window.updateQueueCurrentSong(songId);
    }
}, 50);

// Race condition mitigation: track active song context fetch requests
let activeSongContextRequests = new Map();

// Optimized song context fetching with caching
async function fetchSongContext(profileId) {
    const now = Date.now();
    if (songContextCache && (now - cacheTimestamp) < CACHE_DURATION) {
        return songContextCache;
    }
    
    // Race condition mitigation: unique request ID for tracking
    const requestId = `songContext_${Date.now()}_${Math.random()}`;
    
    // Cancel any previous requests for this profile
    const previousRequestId = activeSongContextRequests.get(profileId);
    if (previousRequestId) {
        console.log(`[musicBar] Canceling previous song context request ${previousRequestId} for ${requestId}`);
    }
    activeSongContextRequests.set(profileId, requestId);

    try {
        // Race condition mitigation: add request tracking to prevent stale responses
        const response = await Promise.all([
            fetch(`/api/music/playback/current/${profileId}?requestId=${requestId}`).then(r => r.json()),
            fetch(`/api/music/playback/previousSong/${profileId}?requestId=${requestId}`).then(r => r.json()),
            fetch(`/api/music/playback/nextSong/${profileId}?requestId=${requestId}`).then(r => r.json())
        ]);

        // Race condition mitigation: check if this response is still valid
        if (activeSongContextRequests.get(profileId) !== requestId) {
            console.log(`[musicBar] Ignoring outdated song context response ${requestId}`);
            return songContextCache; // Return cached data if available
        }

        songContextCache = response;
        cacheTimestamp = now;
        return response;
    } catch (error) {
        console.error("[musicBar] Failed to fetch song context:", error);
        // Return cached data even on error if available
        return songContextCache || [null, null, null];
    } finally {
        // Clean up request tracking
        if (activeSongContextRequests.get(profileId) === requestId) {
            activeSongContextRequests.delete(profileId);
        }
    }
}

// Optimized queue change detection
function hasQueueChanged(newCue, oldCue) {
    if (!newCue && !oldCue)
        return false;
    if (!newCue || !oldCue)
        return true;
    if (newCue.length !== oldCue.length)
        return true;

    // Quick check first and last items
    if (newCue[0] !== oldCue[0] || newCue[newCue.length - 1] !== oldCue[oldCue.length - 1]) {
        return true;
    }

    // Only do full comparison if quick checks pass
    return JSON.stringify(newCue) !== JSON.stringify(oldCue);
}

async function connectWS() {
    try {
        // Race condition mitigation: wait for profile ID to be properly initialized
        const profileId = await waitForProfileId();
        console.log(`[WS] Connecting WebSocket for profile: ${profileId}`);
        ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + `/api/music/ws/${profileId}`);
    ws.onopen = () => {
        // Clear cache on new connection to ensure fresh data
        songContextCache = null;
        cacheTimestamp = 0;
    };
    ws.onclose = () => {
        setTimeout(connectWS, 1000); // Reconnect after 1 second
    };
    ws.onerror = e => console.error('[WS] Error', e);
    ws.onmessage = handleWSMessage;
    } catch (error) {
        console.error('[WS] Failed to connect WebSocket:', error);
        // Retry connection after delay on error
        setTimeout(connectWS, 2000);
    }
}

function handleWSMessage(msg) {
    let message;
    try {
        message = JSON.parse(msg.data);
    } catch (e) {
        console.error("[musicBar.js] handleWSMessage: Error parsing message:", e);
        return console.error(e);
    }
    
    // Race condition mitigation: add sequence number and queue message
    message.sequenceNumber = messageSequenceNumber++;
    messageQueue.push(message);
    processMessageQueue();
}

// Race condition mitigation: message queue processor
function processMessageQueue() {
    if (isProcessingMessage || messageQueue.length === 0) {
        return;
    }
    
    isProcessingMessage = true;
    
    // Process messages in order (FIFO)
    messageQueue.sort((a, b) => a.sequenceNumber - b.sequenceNumber);
    
    const processNextMessage = () => {
        if (messageQueue.length === 0) {
            isProcessingMessage = false;
            return;
        }
        
        const message = messageQueue.shift();
        
        try {
            processWSMessageInternal(message);
        } catch (error) {
            console.error("[musicBar.js] Error processing queued message:", error);
        }
        
        // Process next message with minimal delay to prevent UI blocking
        setTimeout(processNextMessage, 0);
    };
    
    processNextMessage();
}

// Race condition mitigation: actual message processing logic
function processWSMessageInternal(message) {
    if (message.type === 'state') {
        const state = message.payload;
        const songChanged = String(state.currentSongId) !== String(musicState.currentSongId);
        const playChanged = state.playing !== musicState.playing;

        // Update local state immediately
        musicState.currentSongId = state.currentSongId;
        if (state.artist !== null && state.artist !== undefined) {
            musicState.artist = state.artist;
        }
        musicState.playing = state.playing;
        musicState.currentTime = state.currentTime;
        musicState.duration = state.duration;
        musicState.volume = state.volume;
        audio.volume = state.volume; // Synchronize actual audio volume
        
        // Control audio playback based on state
        if (state.playing && audio.paused) {
            audio.play().catch(console.error);
        } else if (!state.playing && !audio.paused) {
            audio.pause();
        }
        musicState.shuffleMode = state.shuffleMode;
        musicState.repeatMode = state.repeatMode;

        // Optimized queue change detection
        const queueChanged = hasQueueChanged(state.cue, musicState.cue);
        const queueLengthChanged = (musicState.cue?.length || 0) !== (state.cue?.length || 0);

        // Update musicState.cue for future comparisons
        musicState.cue = state.cue;

        // Consolidated state update logic
        const needsSongContext = songChanged || playChanged || queueChanged;

        if (needsSongContext && state.currentSongId !== window.lastRefreshedSongId) {
            window.lastRefreshedSongId = state.currentSongId;

            fetchSongContext(window.globalActiveProfileId)
                    .then(([currentSongResponse, prevSongResponse, nextSongResponse]) => {
                        const currentSong = currentSongResponse.data;
                        const prevSong = prevSongResponse.data;
                        const nextSong = nextSongResponse.data;

                        if (currentSong) {
                            UpdateAudioSource(currentSong, prevSong, nextSong, state.playing, state.currentTime ?? 0);

                            // Batch DOM updates using requestAnimationFrame for better performance
                            requestAnimationFrame(() => {
                                if (songChanged) {
                                    debouncedUpdateSelectedSong(state.currentSongId);
                                }

                                // Update queue highlighting
                                if (window.updateQueueCurrentSong) {
                                    window.updateQueueCurrentSong(state.currentSongId);
                                }

                                // Update queue count
                                if (state.cue) {
                                    debouncedUpdateQueueCount(state.cue.length);
                                }

                                // Emit queue change event when queue content actually changed
                                if (queueChanged || queueLengthChanged) {
                                    window.dispatchEvent(new CustomEvent('queueChanged', {
                                        detail: {
                                            queueSize: state.cue?.length || 0,
                                            queueChanged: queueChanged,
                                            queueLengthChanged: queueLengthChanged
                                        }
                                    }));
                                }
                            });
                    }
                    })
                    .catch(error => console.error("Error fetching song context:", error));
        }

        // Time synchronization only when needed
        if (!songChanged && !playChanged && !queueChanged) {
            const clientReceiveTime = Date.now();
            const serverSendTime = state.lastUpdateTime;
            const estimatedLatencyMs = clientReceiveTime - serverSendTime;
            const estimatedLatencySeconds = estimatedLatencyMs / 1000.0;
            const currentAudioTime = audio.currentTime;
            const projectedRemoteTime = state.currentTime + estimatedLatencySeconds;
            const timeDifference = Math.abs(currentAudioTime - projectedRemoteTime);

            // Only adjust if the difference is significant
            if (timeDifference > 0.5) {
                audio.currentTime = projectedRemoteTime;
            }
        }
    } else if (message.type === 'history-update') {
        // Trigger history refresh if the function exists
        if (window.refreshHistory) {
            window.refreshHistory();
        } else {
            // Wait a bit and try again in case history.js hasn't loaded yet
            setTimeout(() => {
                if (window.refreshHistory) {
                    window.refreshHistory();
                }
            }, 100);
        }
    }

    // Always update music bar at the end
    updateMusicBar();
}

function sendWS(type, payload) {
    if (ws && ws.readyState === WebSocket.OPEN)
        ws.send(JSON.stringify({type, payload}));
}

// ---------------- Controls ----------------
function setPlaybackTime(newTime, fromClient = false) {
    musicState.currentTime = newTime;
    audio.currentTime = newTime;
    updateMusicBar();
    if (fromClient) {
        sendWS('seek', {value: newTime});
}
}

function handleSeek(newTime) {
    musicState.currentTime = newTime; // Immediate UI update
    updateMusicBar();
    sendWS('seek', {value: newTime});
}

function bindTimeSlider() {
    const slider = document.querySelector('input[name="seconds"]');
    if (!slider)
        return;
    slider.onmousedown = slider.ontouchstart = () => draggingSeconds = true;
    slider.onmouseup = slider.ontouchend = () => {
        draggingSeconds = false;
        handleSeek(parseInt(slider.value, 10));
    };
    slider.oninput = e => {
        setPlaybackTime(parseInt(e.target.value, 10), true);
    };
}

function bindVolumeSlider() {
    const slider = document.querySelector('input[name="level"]');
    if (!slider)
        return;
    const volumeExponent = 2; // Adjust this value to change the curve (e.g., 2 for quadratic, 3 for cubic)

    const calculateExponentialVolume = (sliderValue) => {
        const linearVol = sliderValue / 100;
        return Math.pow(linearVol, volumeExponent);
    };
    // Inverse function to convert exponential volume back to linear slider value (0-100)
    const calculateLinearSliderValue = (exponentialVol) => {
        const linearVol = Math.pow(exponentialVol, 1 / volumeExponent);
        return linearVol * 100;
    };
    slider.onmousedown = slider.ontouchstart = () => draggingVolume = true;
    slider.onmouseup = slider.ontouchend = () => {
        draggingVolume = false;
        const vol = calculateExponentialVolume(parseInt(slider.value, 10));
        sendWS('volume', {value: vol});
    };
    slider.oninput = e => {
        const vol = calculateExponentialVolume(parseInt(e.target.value, 10));
        musicState.volume = vol;
        audio.volume = vol;
        updateMusicBar(); // Add this line
        sendWS('volume', {value: vol});
    };
}

function bindPlaybackButtons() {
    const playPauseBtn = document.getElementById('playPauseBtn');
    const prevBtn = document.getElementById('prevBtn');
    const nextBtn = document.getElementById('nextBtn');
    const prevSongCoverImage = document.getElementById('prevSongCoverImage');
    const nextSongCoverImage = document.getElementById('nextSongCoverImage');
    
    // Remove existing event listeners by cloning and replacing elements
    if (playPauseBtn) {
        const newPlayPauseBtn = playPauseBtn.cloneNode(true);
        playPauseBtn.parentNode.replaceChild(newPlayPauseBtn, playPauseBtn);
        newPlayPauseBtn.onclick = async () => {
            // Race condition mitigation: store previous state for rollback
            const previousPlayingState = musicState.playing;
            
            // Optimistic UI update - toggle immediately
            musicState.playing = !previousPlayingState;
            updateMusicBar();

            try {
                // Then send to backend for synchronization
                const response = await apiPost('toggle', window.globalActiveProfileId);
                
                // Race condition mitigation: verify response success
                if (!response.ok) {
                    throw new Error('Toggle request failed');
                }
            } catch (error) {
                // Race condition mitigation: rollback optimistic update on failure
                console.error('[musicBar] Play/pause toggle failed, rolling back:', error);
                musicState.playing = previousPlayingState;
                updateMusicBar();
                Toast.error('Failed to toggle playback');
            }
        };
    }
    if (prevBtn) {
        const newPrevBtn = prevBtn.cloneNode(true);
        prevBtn.parentNode.replaceChild(newPrevBtn, prevBtn);
        newPrevBtn.onclick = () => {
            apiPost('previous', window.globalActiveProfileId);
        };
    }
    if (nextBtn) {
        const newNextBtn = nextBtn.cloneNode(true);
        nextBtn.parentNode.replaceChild(newNextBtn, nextBtn);
        newNextBtn.onclick = () => {
            apiPost('next', window.globalActiveProfileId);
        };
    }
    
    // Add click listeners for previous and next song cover images
    if (prevSongCoverImage) {
        const newPrevSongCoverImage = prevSongCoverImage.cloneNode(true);
        prevSongCoverImage.parentNode.replaceChild(newPrevSongCoverImage, prevSongCoverImage);
        newPrevSongCoverImage.onclick = () => {
            apiPost('previous', window.globalActiveProfileId);
        };
    }

    if (nextSongCoverImage) {
        const newNextSongCoverImage = nextSongCoverImage.cloneNode(true);
        nextSongCoverImage.parentNode.replaceChild(newNextSongCoverImage, nextSongCoverImage);
        newNextSongCoverImage.onclick = () => {
            apiPost('next', window.globalActiveProfileId);
        };
    }


    document.getElementById('shuffleBtn').onclick = async () => {
        // Race condition mitigation: store previous state for rollback
        const previousMode = musicState.shuffleMode;
        
        // Optimistic UI update
        let newMode;
        switch (previousMode) {
            case "OFF":
                newMode = "SHUFFLE";
                break;
            case "SHUFFLE":
                newMode = "SMART_SHUFFLE";
                break;
            case "SMART_SHUFFLE":
            default:
                newMode = "OFF";
                break;
        }
        musicState.shuffleMode = newMode;
        updateMusicBar(); // Update UI immediately

        try {
            // Send request to backend for persistence and synchronization
            const response = await apiPost('shuffle', window.globalActiveProfileId);
            
            // Race condition mitigation: verify response success
            if (!response.ok) {
                throw new Error('Shuffle mode change failed');
            }
        } catch (error) {
            // Race condition mitigation: rollback optimistic update on failure
            console.error('[musicBar] Shuffle mode change failed, rolling back:', error);
            musicState.shuffleMode = previousMode;
            updateMusicBar();
            Toast.error('Failed to change shuffle mode');
        }
    };
    document.getElementById('repeatBtn').onclick = async () => {
        // Race condition mitigation: store previous state for rollback
        const previousMode = musicState.repeatMode;
        
        // Optimistic UI update
        let newMode;
        switch (previousMode) {
            case "OFF":
                newMode = "ONE";
                break;
            case "ONE":
                newMode = "ALL";
                break;
            case "ALL":
            default:
                newMode = "OFF";
                break;
        }
        musicState.repeatMode = newMode;
        updateMusicBar(); // Update UI immediately

        try {
            // Send request to backend for persistence and synchronization
            const response = await apiPost('repeat', window.globalActiveProfileId);
            
            // Race condition mitigation: verify response success
            if (!response.ok) {
                throw new Error('Repeat mode change failed');
            }
        } catch (error) {
            // Race condition mitigation: rollback optimistic update on failure
            console.error('[musicBar] Repeat mode change failed, rolling back:', error);
            musicState.repeatMode = previousMode;
            updateMusicBar();
            Toast.error('Failed to change repeat mode');
        }
    };
}


// ---------------- UI ----------------
async function refreshSongTable() {
    const profileId = window.globalActiveProfileId;
    const playlistId = sessionStorage.getItem('currentPlaylistId') || '0';

    if (profileId) {
        try {
            await htmx.ajax('GET', `/api/music/ui/tbody/${profileId}/${playlistId}`, {
                target: '#songTableBody',
                swap: 'innerHTML'
            });
        } catch (error) {
            console.error('Error refreshing song table:', error);
        }
    }
}

// Race condition mitigation: DOM operation batching
let domOperationQueue = [];
let isProcessingDOMOperations = false;
let domOperationSequence = 0;

function processDOMOperations() {
    if (isProcessingDOMOperations || domOperationQueue.length === 0) {
        return;
    }
    
    isProcessingDOMOperations = true;
    
    const processNext = () => {
        if (domOperationQueue.length === 0) {
            isProcessingDOMOperations = false;
            return;
        }
        
        const operation = domOperationQueue.shift();
        
        try {
            operation.fn();
        } catch (error) {
            console.error(`[musicBar] DOM operation ${operation.id} failed:`, error);
        }
        
        // Batch DOM updates using requestAnimationFrame for performance
        if (domOperationQueue.length > 0) {
            requestAnimationFrame(processNext);
        } else {
            isProcessingDOMOperations = false;
        }
    };
    
    processNext();
}

// Race condition mitigation: safe DOM operation scheduling
function scheduleDOMOperation(fn, priority = 'normal') {
    const operationId = `dom_${++domOperationSequence}`;
    
    const operation = {
        id: operationId,
        fn: fn,
        priority: priority,
        timestamp: Date.now()
    };
    
    // Insert based on priority (high first, then normal)
    if (priority === 'high') {
        domOperationQueue.unshift(operation);
    } else {
        domOperationQueue.push(operation);
    }
    
    processDOMOperations();
    
    return operationId;
}

function updateSelectedSongRow(songId) {
    // Race condition mitigation: batch DOM updates
    scheduleDOMOperation(() => {
        // Remove current-song-row class from all rows
        const allRows = document.querySelectorAll('#songTableBody tr[data-song-id]');
        allRows.forEach(row => {
            row.classList.remove('current-song-row');
        });

        // Add current-song-row class to the selected row
        const selectedRow = document.querySelector(`#songTableBody tr[data-song-id="${songId}"]`);
        if (selectedRow) {
            selectedRow.classList.add('current-song-row');
        }
    }, 'normal');
}

function updatePageTitle(song) {
    if (!song) {
        document.getElementById('pageTitle').innerText = "JMedia Home";
        document.title = "JMedia Home";
        return;
    }
    document.getElementById('pageTitle').innerText = `${song.name} - ${song.artist}`;
    document.title = `${song.name} : ${song.artist}`;
}

function bindMusicBarControls() {

    bindTimeSlider();
    bindVolumeSlider();
    bindPlaybackButtons();
}

// Theme Toggle Logic
function applyThemePreference() {
    const themeToggle = document.getElementById('themeToggle');
    const themeIcon = document.getElementById('themeIcon');
    if (!themeToggle || !themeIcon)
        return;
    let isDarkMode = localStorage.getItem('darkMode');
    if (isDarkMode === null) {
// No preference saved, check system preference
        isDarkMode = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'true' : 'false';
    }

    if (isDarkMode === 'true') {
        document.documentElement.setAttribute('data-theme', 'dark');
        themeIcon.classList.remove('pi-sun');
        themeIcon.classList.add('pi-moon');
    } else {
        document.documentElement.removeAttribute('data-theme');
        themeIcon.classList.remove('pi-moon');
        themeIcon.classList.add('pi-sun');
    }
}

function bindImageExpansion() {
    const expandImageBtn = document.getElementById('expandImageBtn');
    const imageContainer = document.querySelector('.image-container');
    if (expandImageBtn && imageContainer) {
        expandImageBtn.addEventListener('click', () => {
            window.location.href = '/player.html';
        });
    }
}

let currentRightClickedSongId = null; // Global variable to store the ID

// Function to show the custom context menu
function showContextMenu(x, y) {
    const contextMenu = document.getElementById('customContextMenu');
    const removeFromPlaylistMenuItem = document.getElementById('removeFromPlaylistMenuItem');

    // Check if we're currently in a playlist view
    const currentPlaylistId = sessionStorage.getItem('currentPlaylistId');
    const isInPlaylistView = currentPlaylistId && currentPlaylistId !== '0';

    // Show/hide the "Remove from Playlist" menu item
    if (removeFromPlaylistMenuItem) {
        removeFromPlaylistMenuItem.style.display = isInPlaylistView ? 'flex' : 'none';
    }

    if (contextMenu) {
        contextMenu.style.left = `${x}px`;
        contextMenu.style.top = `${y}px`;
        contextMenu.style.display = 'block';
    }
}

// Function to hide the custom context menu
function hideContextMenu() {
    const contextMenu = document.getElementById('customContextMenu');
    if (contextMenu) {
        contextMenu.style.display = 'none';
        currentRightClickedSongId = null; // Clear the stored ID
        // Also hide sub-menu if it's open
        const playlistSubMenu = document.getElementById('playlistSubMenu');
        if (playlistSubMenu) {
            playlistSubMenu.style.display = 'none';
        }
    }
}

// Function to fetch playlists and populate the sub-menu
async function populatePlaylistSubMenu() {
    const playlistSubMenu = document.getElementById('playlistSubMenu');
    if (!playlistSubMenu)
        return;
    playlistSubMenu.innerHTML = '<div class="context-menu-item">Loading Playlists...</div>'; // Show loading state

    try {
        const response = await fetch(`/api/music/playlists/${window.globalActiveProfileId}`); // Assuming this endpoint returns JSON
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        const apiResponse = await response.json();
        const playlists = apiResponse.data; // Extract the actual playlist array from the ApiResponse

        playlistSubMenu.innerHTML = ''; // Clear loading state

        if (!playlists || playlists.length === 0) { // Add a check for null/undefined playlists
            playlistSubMenu.innerHTML = '<div class="context-menu-item" data-disabled="true">No Playlists</div>';
            return;
        }

        playlists.forEach(playlist => {
            const playlistItem = document.createElement('div');
            playlistItem.classList.add('context-menu-item');
            playlistItem.textContent = playlist.name;
            playlistItem.dataset.playlistId = playlist.id;
            playlistItem.dataset.action = 'add-to-specific-playlist';
            playlistSubMenu.appendChild(playlistItem);
        });
    } catch (error) {
        console.error('Error fetching playlists:', error);
        playlistSubMenu.innerHTML = '<div class="context-menu-item" data-disabled="true">Error loading playlists</div>';
    }
}

// Function to add song to a specific playlist
async function addSongToPlaylist(playlistId, songId) {
    try {
        const response = await fetch(`/api/music/playlists/${playlistId}/songs/${songId}/${window.globalActiveProfileId}`, {
            method: 'POST'
        });
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        console.log(`Song ${songId} added to playlist ${playlistId}`);
        showToast('Song added to playlist successfully', 'success');
    } catch (error) {
        console.error(`Error adding song ${songId} to playlist ${playlistId}:`, error);
        showToast('Failed to add song to playlist', 'error');
    }
}

// Function to remove song from current playlist
async function removeSongFromCurrentPlaylist(songId) {
    const currentPlaylistId = sessionStorage.getItem('currentPlaylistId');
    if (!currentPlaylistId || currentPlaylistId === '0') {
        showToast('No playlist selected', 'error');
        return;
    }

    try {
        const response = await fetch(`/api/music/playlists/${currentPlaylistId}/songs/${songId}`, {
            method: 'DELETE'
        });
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        console.log(`Song ${songId} removed from playlist ${currentPlaylistId}`);
        showToast('Song removed from playlist successfully', 'success');

        // Refresh the playlist view to show the updated song list
        if (window.globalActiveProfileId) {
            htmx.ajax('GET', `/api/music/ui/playlist-view/${window.globalActiveProfileId}`, {
                target: '#playlistView',
                swap: 'outerHTML'
            });
        }
    } catch (error) {
        console.error(`Error removing song ${songId} from playlist ${currentPlaylistId}:`, error);
        showToast('Failed to remove song from playlist', 'error');
    }
}

// Function to rescan a song
async function rescanSong(songId) {
    try {
        const response = await fetch(`/api/settings/${window.globalActiveProfileId}/rescan-song/${songId}`, {
            method: 'POST'
        });

        if (!response.ok) {
            const errorText = await response.text();

            // Parse the error message to provide better user feedback
            let userMessage = "Failed to rescan song.";
            try {
                const errorData = JSON.parse(errorText);
                if (errorData.error) {
                    if (errorData.error.includes("Song file not found")) {
                        userMessage = "The song file is missing from your music library. The file may have been moved or deleted.";
                    } else if (errorData.error.includes("not found")) {
                        userMessage = "Song not found in the database.";
                    } else {
                        userMessage = errorData.error;
                    }
                }
            } catch (e) {
                userMessage = `Server error: ${response.status}`;
            }

            showToast(userMessage, 'error');
            throw new Error(`HTTP error! status: ${response.status}, message: ${errorText}`);
        }

        const result = await response.json();

        // Show success message
        if (result.message) {
            showToast(result.message, 'success');
        } else {
            showToast('Song re-scan initiated successfully', 'success');
        }

        reloadSongTableBody(); // Reload the table to show potential updates
    } catch (error) {
        console.error(`Error initiating re-scan for song ${songId}:`, error);
    }
}

// Function to delete a song
async function deleteSong(songId) {
    if (confirm('Are you sure you want to delete this song? This action cannot be undone.')) {
        try {
            const response = await fetch(`/api/settings/${window.globalActiveProfileId}/songs/${songId}`, {
                method: 'DELETE'
            });
            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }
            console.log(`Song ${songId} deleted.`);
            showToast('Song deleted successfully', 'success');
            reloadSongTableBody(); // Reload the table to remove the deleted song
            // Emit queue change event when song is deleted
            window.dispatchEvent(new CustomEvent('queueChanged', {
                detail: {
                    queueSize: musicState.cue?.length || 0,
                    queueChanged: true,
                    queueLengthChanged: true
                }
            }));
        } catch (error) {
            console.error(`Error deleting song ${songId}:`, error);
        }
    }
}

// Function to reload the song table, preserving search and sort state
function reloadSongTableBody() {
    const songTableBody = document.getElementById('songTableBody');
    if (songTableBody) {
        const playlistId = window.getCurrentPlaylistId ? window.getCurrentPlaylistId() : '0';
        const searchTerm = window.getCurrentSearchTerm ? window.getCurrentSearchTerm() : '';
        const sortBy = window.currentSortField || 'title';
        const sortDir = window.currentSortDirection || 'asc';

        // When reloading, we can go back to the first page.
        let url = `/api/music/ui/tbody/${window.globalActiveProfileId}/${playlistId}?page=1&sortBy=${sortBy}&sortDirection=${sortDir}`;
        if (searchTerm) {
            url += `&search=${encodeURIComponent(searchTerm)}`;
        }

        htmx.ajax('GET', url, {target: '#songTableBody', swap: 'innerHTML'});
    }
}

// ---------------- Init ----------------

// Parallel initialization system
class InitializationManager {
    constructor() {
        this.isInitialized = false;
        this.initTasks = new Map();
        this.setupInitTasks();
    }

    setupInitTasks() {
        // Define initialization tasks with their dependencies
        this.initTasks.set('domElements', {
            dependencies: [],
            execute: () => this.initializeDOMElements(),
            priority: 1
        });

        this.initTasks.set('profileData', {
            dependencies: [],
            execute: () => this.waitForProfileData(),
            priority: 2
        });

        this.initTasks.set('webSocket', {
            dependencies: ['profileData'],
            execute: () => this.initializeWebSocket(),
            priority: 3
        });

        this.initTasks.set('mediaSession', {
            dependencies: ['domElements'],
            execute: () => this.initializeMediaSession(),
            priority: 4
        });

        this.initTasks.set('uiBindings', {
            dependencies: ['domElements'],
            execute: () => this.initializeUIBindings(),
            priority: 5
        });
    }

    async initializeDOMElements() {
        initializeDOMElements();
        return Promise.resolve();
    }

    async waitForProfileData() {
        return new Promise((resolve) => {
            const checkProfile = () => {
                if (window.globalActiveProfileId) {
                    resolve();
                } else {
                    setTimeout(checkProfile, 50);
                }
            };
            checkProfile();
        });
    }

    async initializeWebSocket() {
        connectWS();
        return Promise.resolve();
    }

    async initializeMediaSession() {
        if ('mediaSession' in navigator && window.setupMediaSessionHandlers) {
            window.setupMediaSessionHandlers(apiPost, setPlaybackTime, audio);
        }
        return Promise.resolve();
    }

    async initializeUIBindings() {
        bindMusicBarControls();
        bindImageExpansion();
        return Promise.resolve();
    }

    async start() {
        if (this.isInitialized)
            return;

        // Execute tasks in dependency order, but parallelize where possible
        const executedTasks = new Set();

        const executeTask = async (taskName) => {
            if (executedTasks.has(taskName))
                return;

            const task = this.initTasks.get(taskName);
            if (!task)
                return;

            // Wait for dependencies
            for (const dep of task.dependencies) {
                if (!executedTasks.has(dep)) {
                    await executeTask(dep);
                }
            }

            // Execute task
            await task.execute();
            executedTasks.add(taskName);
        };

        // Execute all tasks
        const taskNames = Array.from(this.initTasks.keys())
                .sort((a, b) => this.initTasks.get(a).priority - this.initTasks.get(b).priority);

        for (const taskName of taskNames) {
            await executeTask(taskName);
        }

        this.isInitialized = true;

        // Setup event listeners after all initialization is done
        this.setupEventListeners();
    }

    setupEventListeners() {
        // HTMX highlighting is now handled server-side via template rendering
        document.body.addEventListener('htmx:afterRequest', function (event) {
            const url = event.detail.requestConfig?.url || event.detail.path;
            if (url && url.includes('/api/music/playback/select/')) {
                // Extract song ID from the URL
                const urlParts = url.split('/');
                const songId = urlParts[urlParts.length - 1];

                // Update last refreshed song ID to prevent duplicate refreshes
                window.lastRefreshedSongId = songId;

                // Update row styling after a short delay to allow server to update state
                setTimeout(() => {
                    updateSelectedSongRow(songId);
                    // Update queue highlighting when song is selected
                    if (window.updateQueueCurrentSong) {
                        window.updateQueueCurrentSong(songId);
                    }
                }, 100);
            }
        });
    }
}

// Loading indicator system
function showLoadingIndicator(message = 'Initializing...') {
    const existingIndicator = document.getElementById('initLoadingIndicator');
    if (existingIndicator)
        return;

    const indicator = document.createElement('div');
    indicator.id = 'initLoadingIndicator';
    indicator.className = 'notification is-info is-light';
    indicator.style.cssText = `
        position: fixed;
        top: 20px;
        right: 20px;
        z-index: 10000;
        min-width: 200px;
        box-shadow: 0 4px 6px rgba(10, 10, 10, 0.1);
    `;
    indicator.innerHTML = `
        <div class="level">
            <div class="level-left">
                <div class="level-item">
                    <span class="icon">
                        <i class="pi pi-spin pi-spinner"></i>
                    </span>
                    <span>${message}</span>
                </div>
            </div>
        </div>
    `;

    document.body.appendChild(indicator);
}

function updateLoadingIndicator(message) {
    const indicator = document.getElementById('initLoadingIndicator');
    if (indicator) {
        const messageSpan = indicator.querySelector('span:last-child');
        if (messageSpan) {
            messageSpan.textContent = message;
        }
    }
}

function hideLoadingIndicator() {
    const indicator = document.getElementById('initLoadingIndicator');
    if (indicator) {
        indicator.remove();
    }
}

// Create global initialization manager
const initManager = new InitializationManager();

document.addEventListener('DOMContentLoaded', () => {
    // Show loading indicator
    showLoadingIndicator('Loading JMedia...');

    // Start parallel initialization
    initManager.start()
            .then(() => {
                hideLoadingIndicator();
            })
            .catch(error => {
                console.error('[Init] Initialization failed:', error);
                updateLoadingIndicator('Initialization failed');
                setTimeout(() => {
                    hideLoadingIndicator();
                }, 3000);
            });

    // Add context menu listener to document, using event delegation
    document.addEventListener('contextmenu', (event) => {
        const songTableBody = document.getElementById('songTableBody'); // Using getElementById for a more direct selection
        const songCoverImage = document.getElementById('songCoverImage');

        // If right-click is within song table body
        if (songTableBody && songTableBody.contains(event.target)) {
            event.preventDefault();
            const clickedRow = event.target.closest('tr');

            if (clickedRow && clickedRow.dataset.songId) {
                currentRightClickedSongId = clickedRow.dataset.songId;
                showContextMenu(event.clientX, event.clientY);
            } else {
                hideContextMenu();
            }
        }
        // NEW: If right-click is on song cover image in player
        else if (songCoverImage && songCoverImage.contains(event.target)) {
            event.preventDefault();

            if (musicState.currentSongId) {
                currentRightClickedSongId = musicState.currentSongId;
                showContextMenu(event.clientX, event.clientY);
            } else {
                hideContextMenu();
            }
        } else {
            hideContextMenu(); // Hide menu for clicks outside song table
        }
    });

    // Add click listener to document to hide context menu when clicking elsewhere
    document.addEventListener('click', (event) => {
        const contextMenu = document.getElementById('customContextMenu');
        if (contextMenu && !contextMenu.contains(event.target)) {
            hideContextMenu();
        }
    });

    // Add click listener to context menu items
    document.addEventListener('click', (event) => {
        if (event.target.closest('#customContextMenu')) {
            const menuItem = event.target.closest('.context-menu-item');
            if (menuItem) {
                const action = menuItem.dataset.action;
                if (action === 'play-next') {
                    if (currentRightClickedSongId) {
                        apiPost(`queue/add/${currentRightClickedSongId}?playNext=true`);
                    }
                    hideContextMenu();
                } else if (action === 'add-to-playlist') {
                    if (currentRightClickedSongId) {
                        window.showAddToPlaylistDialog(currentRightClickedSongId);
                    }
                    hideContextMenu();
                } else if (action === 'share-song') {
                    // TODO: Implement "Share" logic
                    hideContextMenu();
                } else if (action === 'remove-from-playlist') {
                    if (currentRightClickedSongId) {
                        removeSongFromCurrentPlaylist(currentRightClickedSongId);
                    }
                    hideContextMenu();
                } else {
                    hideContextMenu(); // Hide menu for other clicks within menu
                }
            }
        }
    });

    applyThemePreference(); // Apply theme on load

    const themeToggle = document.getElementById('themeToggle');
    if (themeToggle) {
        themeToggle.addEventListener('click', () => {
            const isDarkMode = document.documentElement.getAttribute('data-theme') === 'dark';
            localStorage.setItem('darkMode', !isDarkMode);
            applyThemePreference(); // Re-apply to update icon and attribute
        });
    }

    // Initial call to set lyrics icon visibility
    updateLyricsIconVisibility();
});

// Add context menu listener to the document, using event delegation
document.addEventListener('contextmenu', (event) => {
    const songTableBody = document.getElementById('songTableBody'); // Using getElementById for a more direct selection
    const songCoverImage = document.getElementById('songCoverImage');

    // If right-click is within the song table body
    if (songTableBody && songTableBody.contains(event.target)) {
        event.preventDefault();
        const clickedRow = event.target.closest('tr');

        if (clickedRow && clickedRow.dataset.songId) {
            currentRightClickedSongId = clickedRow.dataset.songId;
            showContextMenu(event.clientX, event.clientY);
        } else {
            hideContextMenu();
        }
    }
    // NEW: If right-click is on the song cover image in the player
    else if (songCoverImage && songCoverImage.contains(event.target)) {
        event.preventDefault();

        if (musicState.currentSongId) {
            currentRightClickedSongId = musicState.currentSongId;
            showContextMenu(event.clientX, event.clientY);
        } else {
            hideContextMenu();
        }
    } else {
        hideContextMenu();
    }
});
// Hide context menu and sub-menu when clicking anywhere else
document.addEventListener('click', (event) => {
    const contextMenu = document.getElementById('customContextMenu');
    const playlistSubMenu = document.getElementById('playlistSubMenu'); // Get sub-menu
    if (contextMenu && contextMenu.style.display === 'block' && !contextMenu.contains(event.target)) {
        hideContextMenu();
    }
    // Also hide sub-menu if it's open and click is outside
    if (playlistSubMenu && playlistSubMenu.style.display === 'block' && !playlistSubMenu.contains(event.target) && !event.target.closest('#addToPlaylistMenuItem')) {
        playlistSubMenu.style.display = 'none';
    }
});
// Add click listeners to context menu items
const customContextMenu = document.getElementById('customContextMenu');
if (customContextMenu) {
    customContextMenu.addEventListener('click', async (event) => { // Made async to use await
        const menuItem = event.target.closest('.context-menu-item');
        if (menuItem) {
            const action = menuItem.dataset.action;
            if (action === 'add-to-playlist') {
// Toggle visibility of sub-menu
                const playlistSubMenu = document.getElementById('playlistSubMenu');
                if (playlistSubMenu) {
                    if (playlistSubMenu.style.display === 'block') {
                        playlistSubMenu.style.display = 'none';
                    } else {
                        playlistSubMenu.style.display = 'block';
                        await populatePlaylistSubMenu(); // Populate when shown
                    }
                }
            } else if (action === 'add-to-specific-playlist') {
                const playlistId = menuItem.dataset.playlistId;
                if (currentRightClickedSongId && playlistId) {
                    await addSongToPlaylist(playlistId, currentRightClickedSongId);
                }
                hideContextMenu(); // Hide all menus after adding
            } else if (action === 'queue-song') {
                if (currentRightClickedSongId) {
                    htmx.ajax('POST', `/api/music/queue/add/${window.globalActiveProfileId}/${currentRightClickedSongId}`, {
                        handler: function () {
                            console.log(`Song ${currentRightClickedSongId} added to queue.`);
                            showToast('Song added to queue', 'success');
                            // Emit queue change event when song is added to queue
                            window.dispatchEvent(new CustomEvent('queueChanged', {
                                detail: {
                                    queueSize: musicState.cue?.length || 0,
                                    queueChanged: true,
                                    queueLengthChanged: true
                                }
                            }));
                        }
                    });
                }
                hideContextMenu(); // Hide all menus after queuing
            } else if (action === 'rescan-song') {
                if (currentRightClickedSongId) {
                    await rescanSong(currentRightClickedSongId);
                }
                hideContextMenu();
            } else if (action === 'delete-song') {
                if (currentRightClickedSongId) {
                    await deleteSong(currentRightClickedSongId);
                }
                hideContextMenu();
            } else if (action === 'edit-song') {
                // TODO: Implement "Edit Song" logic
                hideContextMenu();
            } else if (action === 'share-song') {
                // TODO: Implement "Share" logic
                hideContextMenu();
            } else if (action === 'remove-from-playlist') {
                if (currentRightClickedSongId) {
                    await removeSongFromCurrentPlaylist(currentRightClickedSongId);
                }
                hideContextMenu();
            } else {
                hideContextMenu(); // Hide menu for other clicks within the menu
            }
        }
    });
}

applyThemePreference(); // Apply theme on load

const themeToggle = document.getElementById('themeToggle');
if (themeToggle) {
    themeToggle.addEventListener('click', () => {
        const isDarkMode = document.documentElement.getAttribute('data-theme') === 'dark';
        localStorage.setItem('darkMode', !isDarkMode);
        applyThemePreference(); // Re-apply to update icon and attribute
    });
}

// Initial call to set lyrics icon visibility
updateLyricsIconVisibility();

function updateLyricsIconVisibility() {
    const lyricsIcon = document.getElementById('viewLyricsIcon');
    if (lyricsIcon) {
        if (musicState.hasLyrics) {
            lyricsIcon.style.display = 'flex'; // Show the icon
        } else {
            lyricsIcon.style.display = 'none'; // Hide the icon
        }
    }
}
