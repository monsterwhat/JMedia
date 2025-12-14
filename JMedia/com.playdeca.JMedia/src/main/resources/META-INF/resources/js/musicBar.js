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
// Global API Post function
const apiPost = (path, profileId) => {
    const profileIdValue = profileId || window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
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
    const {songName, artist, playing, currentTime, duration, volume, shuffleEnabled, repeatMode} = musicState;
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
    console.log("[musicBar.js] audio.onended: Song ended, calling /api/music/playback/next.");
    fetch(`/api/music/playback/next/${window.globalActiveProfileId}`, {method: 'POST'});
};
// ---------------- Update Audio Source ----------------
function UpdateAudioSource(currentSong, prevSong = null, nextSong = null, play = false, backendTime = 0) {
    isUpdatingAudioSource = true; // Set flag to true at the beginning

    if (!currentSong || !currentSong.id) {
        isUpdatingAudioSource = false; // Reset flag if no current song
        return;
    }

    const sameSong = String(musicState.currentSongId) === String(currentSong.id);
    musicState.currentSongId = currentSong.id;
    musicState.songName = currentSong.title ?? "Unknown Title";
    // Only update artist if it's provided by the currentSong object, otherwise retain current value
    if (currentSong.artist !== null && currentSong.artist !== undefined) {
        musicState.artist = currentSong.artist;
    }
    musicState.currentTime = (sameSong || backendTime !== 0) ? (backendTime ?? 0) : 0;
    musicState.duration = currentSong.durationSeconds ?? 0; // Prioritize duration from backend

    // Clear previous song data and image sources before setting new ones
    try {
        const songCoverImageEl = document.getElementById('songCoverImage');
        const prevSongCoverImageEl = document.getElementById('prevSongCoverImage');
        const nextSongCoverImageEl = document.getElementById('nextSongCoverImage');
        const faviconEl = document.getElementById('favicon');
        
        // Clear image sources
        if (songCoverImageEl) songCoverImageEl.src = '';
        if (prevSongCoverImageEl) prevSongCoverImageEl.src = '';
        if (nextSongCoverImageEl) nextSongCoverImageEl.src = '';
        if (faviconEl) faviconEl.href = '';
        
        // Clear base64 data from previous song objects to free memory
        if (window.previousSongData) {
            if (window.previousSongData.currentSong) window.previousSongData.currentSong.artworkBase64 = null;
            if (window.previousSongData.prevSong) window.previousSongData.prevSong.artworkBase64 = null;
            if (window.previousSongData.nextSong) window.previousSongData.nextSong.artworkBase64 = null;
        }
        
        // Clear previous song data references
        window.previousSongData = {
            currentSong: musicState.currentSongId ? { id: musicState.currentSongId } : null,
            prevSong: null,
            nextSong: null
        };
    } catch (error) {
        // Silent cleanup failure - non-critical
    }

    // Update current song cover image
    const songCoverImageEl = document.getElementById('songCoverImage');
    if (songCoverImageEl) {
        songCoverImageEl.src = currentSong.artworkBase64 && currentSong.artworkBase64 !== '' ? 'data:image/jpeg;base64,' + currentSong.artworkBase64 : '/logo.png';
    }

    // Update previous song cover image
    const prevSongCoverImageEl = document.getElementById('prevSongCoverImage');
    if (prevSongCoverImageEl) {
        if (prevSong && prevSong.artworkBase64 && prevSong.artworkBase64 !== '') {
            prevSongCoverImageEl.src = 'data:image/jpeg;base64,' + prevSong.artworkBase64;
            prevSongCoverImageEl.style.display = 'block'; // Show if available
        } else {
            prevSongCoverImageEl.src = '/logo.png'; // Default image
            prevSongCoverImageEl.style.display = 'none'; // Hide if no previous song
        }
    }

    // Update next song cover image
    const nextSongCoverImageEl = document.getElementById('nextSongCoverImage');
    if (nextSongCoverImageEl) {
        if (nextSong && nextSong.artworkBase64 && nextSong.artworkBase64 !== '') {
            nextSongCoverImageEl.src = 'data:image/jpeg;base64,' + nextSong.artworkBase64;
            nextSongCoverImageEl.style.display = 'block'; // Show if available
        } else {
            nextSongCoverImageEl.src = '/logo.png'; // Default image
            nextSongCoverImageEl.style.display = 'none'; // Hide if no next song
        }
    }

    const faviconEl = document.getElementById('favicon');
    if (faviconEl) {
        faviconEl.href = currentSong.artworkBase64 && currentSong.artworkBase64 !== '' ? 'data:image/jpeg;base64,' + currentSong.artworkBase64 : '/logo.png';
    }

    // Update musicState.hasLyrics
    musicState.hasLyrics = currentSong.lyrics !== null && currentSong.lyrics !== undefined && currentSong.lyrics !== '';

    updatePageTitle({name: musicState.songName, artist: musicState.artist});
    
    // Clear audio buffer before setting new source
    try {
        audio.src = '';
        audio.load(); // Clear buffer
    } catch (error) {
        // Silent cleanup failure - non-critical
    }
    
    audio.src = `/api/music/stream/${window.globalActiveProfileId}/${currentSong.id}`;
    audio.load();
    audio.volume = musicState.volume;
    audio.onloadedmetadata = () => {
        console.log("[musicBar.js] onloadedmetadata: audio.duration=", audio.duration);
        // Only update musicState.duration from audio.duration if it's a valid, non-zero number
        if (typeof audio.duration === 'number' && !isNaN(audio.duration) && audio.duration > 0) {
            musicState.duration = audio.duration;
        }
        audio.currentTime = musicState.currentTime; // set backend time after duration is known
        if (play)
            audio.play().catch(console.error);
        else // Pause if 'play' is false
            audio.pause();
        updateMusicBar();
        console.log("[musicBar.js] onloadedmetadata: musicState.duration (final)=", musicState.duration);
        
        // Clear current song base64 data after UI is updated to free memory
        try {
            if (window.previousSongData && window.previousSongData.currentSong) {
                window.previousSongData.currentSong.artworkBase64 = null;
            }
        } catch (error) {
            // Silent cleanup failure - non-critical
        }
        
        isUpdatingAudioSource = false; // Reset flag after UI update
    };
}


// ---------------- WebSocket ----------------
let ws;
function connectWS() {
    if (!window.globalActiveProfileId) {
        console.log('[WS] globalActiveProfileId not available, retrying in 500ms...');
        setTimeout(connectWS, 500);
        return;
    }
    ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + `/api/music/ws/${window.globalActiveProfileId}`);
    ws.onopen = () => console.log('[WS] Connected');
    ws.onclose = () => {
        console.log('[WS] Disconnected. Attempting to reconnect...');
        setTimeout(connectWS, 1000); // Reconnect after 1 second
    };
    ws.onerror = e => console.error('[WS] Error', e);
    ws.onmessage = handleWSMessage;
}

function handleWSMessage(msg) {

    let message;
    try {
        message = JSON.parse(msg.data);
    } catch (e) {
        console.error("[musicBar.js] handleWSMessage: Error parsing message:", e);
        return console.error(e);
    }

    if (message.type === 'state') {
        const state = message.payload;
        const songChanged = String(state.currentSongId) !== String(musicState.currentSongId);
        const playChanged = state.playing !== musicState.playing;
        musicState.currentSongId = state.currentSongId;
        if (state.artist !== null && state.artist !== undefined) {
            musicState.artist = state.artist;
        }
        musicState.playing = state.playing;
        musicState.currentTime = state.currentTime;
        musicState.duration = state.duration;
        musicState.volume = state.volume;
        audio.volume = state.volume; // Synchronize actual audio volume
        musicState.shuffleMode = state.shuffleMode;
        musicState.repeatMode = state.repeatMode;
        // Check if the queue itself has changed (e.g., due to shuffle, or songs added/removed)
        // Check if the queue has been re-ordered or its length has changed.
        const oldCueLength = musicState.cue ? musicState.cue.length : 0;
        const newCueLength = state.cue ? state.cue.length : 0;
        const queueChanged = (state.cue && musicState.cue && JSON.stringify(state.cue) !== JSON.stringify(musicState.cue)) ||
                (state.cue && !musicState.cue) || (!state.cue && musicState.cue);

        // Check if queue length changed (songs removed or added)
        const queueLengthChanged = oldCueLength !== newCueLength;

        // Update musicState.cue for future comparisons
        musicState.cue = state.cue;

        if (songChanged && state.currentSongId !== window.lastRefreshedSongId) { // Only refresh when song changes AND we haven't refreshed for this song yet
            window.lastRefreshedSongId = state.currentSongId;

            Promise.all([
                fetch(`/api/music/playback/current/${window.globalActiveProfileId}`).then(r => r.json()),
                fetch(`/api/music/playback/previousSong/${window.globalActiveProfileId}`).then(r => r.json()),
                fetch(`/api/music/playback/nextSong/${window.globalActiveProfileId}`).then(r => r.json())
            ])
                    .then(([currentSongResponse, prevSongResponse, nextSongResponse]) => {
                        const currentSong = currentSongResponse.data;
                        const prevSong = prevSongResponse.data;
                        const nextSong = nextSongResponse.data;
                        if (currentSong) {
                            UpdateAudioSource(currentSong, prevSong, nextSong, state.playing, state.currentTime ?? 0);
                            // Update highlighting without refreshing entire table
                            updateSelectedSongRow(state.currentSongId);
                            // Update queue highlighting
                            if (window.updateQueueCurrentSong) {
                                window.updateQueueCurrentSong(state.currentSongId);
                            }
                            // Update queue count when song changes (song advanced)
                            if (window.updateQueueCount && state.cue) {
                                // Update queue count based on current queue length
                                window.updateQueueCount(state.cue.length);
                            }
                            // Refresh queue table when queue content actually changed
                            if (window.refreshQueue && (queueChanged || queueLengthChanged)) {
                                console.log("[musicBar.js] Song changed and queue content changed, refreshing queue table. queueChanged:", queueChanged, "queueLengthChanged:", queueLengthChanged);
                                window.refreshQueue();
                            }
                    }
                    }).catch(error => console.error("Error fetching song context:", error));
        } else if (playChanged || queueChanged) { // Handle play state and queue changes without table refresh
            Promise.all([
                fetch(`/api/music/playback/current/${window.globalActiveProfileId}`).then(r => r.json()),
                fetch(`/api/music/playback/previousSong/${window.globalActiveProfileId}`).then(r => r.json()),
                fetch(`/api/music/playback/nextSong/${window.globalActiveProfileId}`).then(r => r.json())
            ])
                    .then(([currentSongResponse, prevSongResponse, nextSongResponse]) => {
                        const currentSong = currentSongResponse.data;
                        const prevSong = prevSongResponse.data;
                        const nextSong = nextSongResponse.data;
                        if (currentSong) {
                            UpdateAudioSource(currentSong, prevSong, nextSong, state.playing, state.currentTime ?? 0);
                            // Update queue highlighting even when queue hasn't changed
                            if (window.updateQueueCurrentSong) {
                                window.updateQueueCurrentSong(state.currentSongId);
                            }
                        }
                        // Update queue count when queue changes
                        if (window.updateQueueCount && state.cue) {
                            window.updateQueueCount(state.cue.length);
                        }
                        // Only refresh queue, not the song table
                        if (window.refreshQueue && queueChanged) {
                            window.refreshQueue();
                    }
                    }).catch(error => console.error("Error fetching song context:", error));
        }

// If neither song nor play state changed, but time might have drifted, synchronize currentTime

        if (!songChanged && !playChanged) {

            const clientReceiveTime = Date.now(); // Use Date.now() for epoch-based timestamp

            const serverSendTime = state.lastUpdateTime; // Server's timestamp when state was sent

            // Calculate estimated latency (in milliseconds)

            const estimatedLatencyMs = clientReceiveTime - serverSendTime;
            const estimatedLatencySeconds = estimatedLatencyMs / 1000.0;
            // Project the server's currentTime to the client's current local time

            const currentAudioTime = audio.currentTime;
            const projectedRemoteTime = state.currentTime + estimatedLatencySeconds;
            const timeDifference = Math.abs(currentAudioTime - projectedRemoteTime);
            // Only adjust if the difference is significant to avoid constant small adjustments

            if (timeDifference > 0.5) { // e.g., if difference is more than 0.5 seconds
                console.log(`[WS] handleWSMessage: Correcting time. Local: ${currentAudioTime.toFixed(2)}, Server (projected): ${projectedRemoteTime.toFixed(2)}, Diff: ${timeDifference.toFixed(2)}, Latency: ${estimatedLatencyMs.toFixed(2)}ms`);
                audio.currentTime = projectedRemoteTime;
            }
        } else if (message.type === 'history-update') {
            console.log('[WS] History update received for profile', message.profileId, ', refreshing history table');
            // Trigger history refresh if the function exists
            if (window.refreshHistory) {
                console.log('[WS] Calling refreshHistory function');
                window.refreshHistory();
            } else {
                console.error('[WS] refreshHistory function not available, retrying in 100ms...');
                // Wait a bit and try again in case history.js hasn't loaded yet
                setTimeout(() => {
                    if (window.refreshHistory) {
                        console.log('[WS] Retrying refreshHistory function');
                        window.refreshHistory();
                    } else {
                        console.error('[WS] refreshHistory function still not available after retry');
                    }
                }, 100);
            }
        }
        updateMusicBar();

        // Update queue count based on current state
        if (window.updateQueueCount && state.cue) {
            window.updateQueueCount(state.cue.length);
        }
    }
}

function sendWS(type, payload) {
    console.log(`[musicBar.js] sendWS: Sending type=${type}, payload=`, payload);
    if (ws && ws.readyState === WebSocket.OPEN)
        ws.send(JSON.stringify({type, payload}));
}

// ---------------- Controls ----------------
function setPlaybackTime(newTime, fromClient = false) {
    console.log(`[musicBar.js] setPlaybackTime called: newTime=${newTime}, fromClient=${fromClient}`);
    musicState.currentTime = newTime;
    audio.currentTime = newTime;
    updateMusicBar();
    if (fromClient) {
        console.log(`[musicBar.js] setPlaybackTime: Sending seek WS message for newTime=${newTime}`);
        sendWS('seek', {value: newTime});
}
}

function handleSeek(newTime) {
    musicState.currentTime = newTime; // Immediate UI update
    updateMusicBar();
    sendWS('seek', {value: newTime});
    console.log("[musicBar.js] User manually seeked to sec=", newTime);
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
    document.getElementById('playPauseBtn').onclick = () => {
        console.log("[musicBar.js] playPauseBtn clicked");
        apiPost('toggle', window.globalActiveProfileId);
    };
    document.getElementById('prevBtn').onclick = () => {
        console.log("[musicBar.js] prevBtn clicked");
        apiPost('previous', window.globalActiveProfileId);
    };
    document.getElementById('nextBtn').onclick = () => {
        console.log("[musicBar.js] nextBtn clicked");
        apiPost('next', window.globalActiveProfileId);
    };
    // Add click listeners for previous and next song cover images
    const prevSongCoverImage = document.getElementById('prevSongCoverImage');
    if (prevSongCoverImage) {
        prevSongCoverImage.onclick = () => {
            console.log("[musicBar.js] prevSongCoverImage clicked");
            apiPost('previous', window.globalActiveProfileId);
        };
    }

    const nextSongCoverImage = document.getElementById('nextSongCoverImage');
    if (nextSongCoverImage) {
        nextSongCoverImage.onclick = () => {
            console.log("[musicBar.js] nextSongCoverImage clicked");
            apiPost('next', window.globalActiveProfileId);
        };
    }


    document.getElementById('shuffleBtn').onclick = () => {
        console.log("[musicBar.js] shuffleBtn clicked");
        // Optimistic UI update
        let currentMode = musicState.shuffleMode;
        let newMode;
        switch (currentMode) {
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

        // Send request to backend for persistence and synchronization
        apiPost('shuffle', window.globalActiveProfileId);
    };
    document.getElementById('repeatBtn').onclick = () => {
        console.log("[musicBar.js] repeatBtn clicked");
        // Optimistic UI update
        let currentMode = musicState.repeatMode;
        let newMode;
        switch (currentMode) {
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

        // Send request to backend for persistence and synchronization
        apiPost('repeat', window.globalActiveProfileId);
    };
}


// ---------------- UI ----------------
async function refreshSongTable() {
    const profileId = window.globalActiveProfileId;
    const playlistId = sessionStorage.getItem('currentPlaylistId') || '0';

    if (profileId) {
        console.log(`[musicBar.js] Refreshing song table for profile ${profileId}, playlist ${playlistId}`);
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

function updateSelectedSongRow(songId) {
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
        document.documentElement.classList.add('is-dark-mode');
        themeIcon.classList.remove('pi-sun');
        themeIcon.classList.add('pi-moon');
    } else {
        document.documentElement.classList.remove('is-dark-mode');
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
        console.log(`[musicBar.js] Attempting to rescan song with ID: ${songId}`);
        const response = await fetch(`/api/settings/${window.globalActiveProfileId}/rescan-song/${songId}`, {
            method: 'POST'
        });

        if (!response.ok) {
            const errorText = await response.text();
            console.error(`[musicBar.js] Server response (${response.status}): ${errorText}`);

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
        console.log(`Song ${songId} re-scan initiated. Server response:`, result);

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
            if (window.refreshQueue) {
                window.refreshQueue();
            }
        } catch (error) {
            console.error(`Error deleting song ${songId}:`, error);
        }
    }
}

// Function to reload the song table, preserving search and sort state
function reloadSongTableBody() {
    console.log("[musicBar.js] reloadSongTableBody called");
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

        console.log(`[musicBar.js] Reloading song table with URL: ${url}`);
        htmx.ajax('GET', url, {target: '#songTableBody', swap: 'innerHTML'});
    } else {
        console.error("[musicBar.js] reloadSongTableBody: #songTableBody element not found.");
    }
}

// ---------------- Init ----------------

document.addEventListener('DOMContentLoaded', () => {

    bindMusicBarControls();
    bindImageExpansion();
    // Initialize Media Session API handlers
    if ('mediaSession' in navigator && window.setupMediaSessionHandlers) {
        window.setupMediaSessionHandlers(apiPost, setPlaybackTime, audio);
    }

    connectWS();

    // HTMX highlighting is now handled server-side via template rendering

    // Update selected song row styling without full table reload
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
                                // Refresh the queue display
                                if (window.refreshQueue) {
                                    window.refreshQueue();
                                }
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
                    console.log('Edit Song clicked for song ID:', currentRightClickedSongId);
                    // TODO: Implement "Edit Song" logic
                    hideContextMenu();
                } else if (action === 'share-song') {
                    console.log('Share clicked for song ID:', currentRightClickedSongId);
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
            const isDarkMode = document.documentElement.classList.contains('is-dark-mode');
            localStorage.setItem('darkMode', !isDarkMode);
            applyThemePreference(); // Re-apply to update icon and class
        });
    }

    // Initial call to set lyrics icon visibility
    updateLyricsIconVisibility();
});

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
