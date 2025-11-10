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
    shuffleEnabled: false,
    repeatMode: "OFF"
};

let draggingSeconds = false;
let draggingVolume = false;

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
    const {songName, artist, playing, currentTime, duration, volume, shuffleEnabled, repeatEnabled} = musicState;

    const titleEl = document.getElementById('songTitle');
    const artistEl = document.getElementById('songArtist');
    if (titleEl)
        titleEl.innerText = songName ?? "Unknown Title";
    if (artistEl)
        artistEl.innerText = artist ?? "Unknown Artist";

    // Apply marquee effect to main song title and artist
    applyMarqueeEffect('songTitle', songName ?? "Unknown Title");
    applyMarqueeEffect('songArtist', artist ?? "Unknown Artist");

    document.getElementById('playPauseIcon').className = playing
            ? "pi pi-pause button is-warning is-rounded is-large"
            : "pi pi-play button is-success is-rounded is-large";

    document.getElementById('currentTime').innerText = formatTime(currentTime);
    document.getElementById('totalTime').innerText = formatTime(duration);

    const timeSlider = document.getElementById('playbackProgressBar'); // Use getElementById for direct access
    if (timeSlider) {
        timeSlider.max = duration;

        // Update the slider's value based on currentTime if not dragging
        // If dragging, the slider's value is already updated by the browser
        if (!draggingSeconds) {
            timeSlider.value = currentTime;
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

    document.getElementById('shuffleIcon').className = shuffleEnabled
            ? "pi pi-sort-alt-slash has-text-success"
            : "pi pi-sort-alt";

    const repeatIcon = document.getElementById('repeatIcon');
    if (repeatIcon) {
        repeatIcon.className = "pi pi-refresh"; // Base class
        if (musicState.repeatMode === "ONE") {
            repeatIcon.classList.add("has-text-info"); // Example: blue for repeat one
        } else if (musicState.repeatMode === "ALL") {
            repeatIcon.classList.add("has-text-success"); // Example: green for repeat all
            repeatIcon.classList.add("pi-spin"); // Add spin for repeat all
        }
    }
}

// ---------------- Audio Events ----------------
const throttledSendWS = throttle(sendWS, 300); // Send a message at most every 300ms

audio.ontimeupdate = () => {
    if (!draggingSeconds) {
        musicState.currentTime = audio.currentTime;
        throttledSendWS("seek", {value: audio.currentTime});
        updateMusicBar(); // Update UI more frequently

    }
};

audio.onended = () => {
    console.log("[musicBar.js] audio.onended: Song ended, calling /api/music/playback/next.");
    fetch('/api/music/playback/next', { method: 'POST' });
};


// ---------------- Update Audio Source ----------------
function UpdateAudioSource(song, play = false, backendTime = 0) {
    console.log("[musicBar.js] UpdateAudioSource called with song:", song, "play:", play, "backendTime:", backendTime);
    if (!song || !song.id)
        return;

    const sameSong = String(musicState.currentSongId) === String(song.id);
    musicState.currentSongId = song.id;
    musicState.songName = song.title ?? "Unknown Title";
    musicState.artist = song.artist ?? "Unknown Artist";
    musicState.currentTime = (sameSong || backendTime !== 0) ? (backendTime ?? 0) : 0;
    musicState.duration = song.durationSeconds ?? 0; // Prioritize duration from backend

    // Update song cover image
    const songCoverImageEl = document.getElementById('songCoverImage');
    if (songCoverImageEl) {
        songCoverImageEl.src = song.artworkBase64 && song.artworkBase64 !== '' ? 'data:image/jpeg;base64,' + song.artworkBase64 : '/logo.png';
    }

    const faviconEl = document.getElementById('favicon');
    if (faviconEl) {
        faviconEl.href = song.artworkBase64 && song.artworkBase64 !== '' ? 'data:image/jpeg;base64,' + song.artworkBase64 : '/logo.png';
    }

    updateMusicBar();
    updatePageTitle({name: musicState.songName, artist: musicState.artist});

    audio.src = `/api/music/stream/${song.id}`;
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
        updateMusicBar();
        console.log("[musicBar.js] onloadedmetadata: musicState.duration (final)=", musicState.duration);
    };
}


// ---------------- WebSocket ----------------
let ws;
function connectWS() {
    ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/api/music/ws');
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
        return console.error(e);
    }

    if (message.type === 'state') {
        const state = message.payload;
        const songChanged = String(state.currentSongId) !== String(musicState.currentSongId);
        const playChanged = state.playing !== musicState.playing;

        musicState.currentSongId = state.currentSongId;
        // musicState.songName = state.songName; // Removed: Prioritize API for songName
        musicState.artist = state.artist ?? "Unknown Artist";
        musicState.playing = state.playing;
        musicState.currentTime = state.currentTime;
        musicState.duration = state.duration;
        musicState.volume = state.volume;
        musicState.shuffleEnabled = state.shuffleEnabled;
        musicState.repeatMode = state.repeatMode;

        console.log("[WS] handleWSMessage: songChanged=", songChanged, "state.currentSongId=", state.currentSongId, "musicState.currentSongId=", musicState.currentSongId);

        if (songChanged) {
            console.log("[WS] handleWSMessage: Song changed, fetching current song details.");
            fetch(`/api/music/playback/current`)
                    .then(r => r.json())
                    .then(json => {
                        if (json.data) {
                            console.log("[WS] handleWSMessage: Calling UpdateAudioSource with data:", json.data);
                            UpdateAudioSource(json.data, state.playing, state.currentTime ?? 0);
                            refreshSongTable();
                            if (window.refreshQueue) {
                                window.refreshQueue();
                            }
                        }
                    });
        } else if (playChanged) { // If only play state changed, re-initialize audio source to ensure duration is correct
            fetch(`/api/music/playback/current`)
                    .then(r => r.json())
                    .then(json => {
                        if (json.data) {
                            UpdateAudioSource(json.data, state.playing, state.currentTime ?? 0);
                        }
                    });
        }

        if (!draggingSeconds) {
            if (Math.abs(audio.currentTime - musicState.currentTime) > 1) {
                audio.currentTime = musicState.currentTime;
            }
        }

        if (playChanged) {
            audio.currentTime = musicState.currentTime; // Synchronize audio element's current time
            if (musicState.playing) {
                audio.play().catch(console.error);
            } else {
                audio.pause();
            }
        }

        updateMusicBar();
    }
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
        sendWS('volume', {value: vol});
    };
}

function bindPlaybackButtons() {
    const apiPost = (path) => fetch(`/api/music/playback/${path}`, {method: 'POST'});

    document.getElementById('playPauseBtn').onclick = () => {
        console.log("[musicBar.js] playPauseBtn clicked");
        apiPost('toggle');
    };
    document.getElementById('prevBtn').onclick = () => {
        console.log("[musicBar.js] prevBtn clicked");
        apiPost('previous');
    };
    document.getElementById('nextBtn').onclick = () => {
        console.log("[musicBar.js] nextBtn clicked");
        apiPost('next');
    };
    document.getElementById('shuffleBtn').onclick = () => {
        console.log("[musicBar.js] shuffleBtn clicked");
        apiPost('shuffle');
    };
    document.getElementById('repeatBtn').onclick = () => {
        console.log("[musicBar.js] repeatBtn clicked");
        apiPost('repeat');
    };
}


// ---------------- UI ----------------
async function refreshSongTable() { // Make it async
    console.log("[musicBar.js] refreshSongTable called");
    const songTableBody = document.querySelector('#songTable tbody');
    if (!songTableBody) {
        console.log("[musicBar.js] refreshSongTable: #songTable tbody not found.");
        return;
    }

    try {
        const response = await fetch('/api/music/playback/current');
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        const currentSongData = await response.json();
        const currentSongId = currentSongData.data ? String(currentSongData.data.id) : null;

        console.log("[musicBar.js] refreshSongTable: Current song ID from API:", currentSongId);

        // Iterate through all song rows and update their styling
        const songRows = songTableBody.querySelectorAll('tr');
        songRows.forEach(row => {
            const rowSongId = row.dataset.songId; // Assuming song ID is stored in data-song-id attribute on the tr
            if (rowSongId && rowSongId === currentSongId) {
                row.classList.add('has-background-grey-dark', 'has-text-white');
                console.log(`[musicBar.js] refreshSongTable: Highlighted song ID: ${rowSongId}`);
            } else {
                row.classList.remove('has-background-grey-dark', 'has-text-white');
            }
        });
    } catch (error) {
        console.error("[musicBar.js] refreshSongTable: Error fetching current song or updating table:", error);
    }
}


function updatePageTitle(song) {
    if (!song) {
        document.getElementById('pageTitle').innerText = "JMusic Home";
        document.title = "JMusic Home";
        return;
    }
    document.getElementById('pageTitle').innerText = `${song.name} — ${song.artist}`;
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
            imageContainer.classList.toggle('is-expanded');
        });
    }
}

let currentRightClickedSongId = null; // Global variable to store the ID

// Function to show the custom context menu
function showContextMenu(x, y) {
    const contextMenu = document.getElementById('customContextMenu');
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
        const response = await fetch('/api/music/playlists'); // Assuming this endpoint returns JSON
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
        const response = await fetch(`/api/music/playlists/${playlistId}/songs/${songId}`, {
            method: 'POST'
        });
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        console.log(`Song ${songId} added to playlist ${playlistId}`);
        // Optionally, show a success message to the user
    } catch (error) {
        console.error(`Error adding song ${songId} to playlist ${playlistId}:`, error);
        // Optionally, show an error message to the user
    }
}

// ---------------- Init ----------------

document.addEventListener('DOMContentLoaded', () => {

    bindMusicBarControls();
    bindImageExpansion();

    connectWS();

    // Add context menu listener to the document, using event delegation
    document.addEventListener('contextmenu', (event) => {
        console.log('[ContextMenu] Right-click detected.');
        const songTableBody = document.querySelector('#songTable tbody');

        // If right-click is within the song table body, prevent default browser context menu
        if (songTableBody && songTableBody.contains(event.target)) {
            event.preventDefault();
            console.log('[ContextMenu] Right-click within songTable tbody. Default prevented.');

            const clickedRow = event.target.closest('tr'); // Find the closest row

            if (clickedRow && clickedRow.dataset.songId) {
                currentRightClickedSongId = clickedRow.dataset.songId;
                console.log('[ContextMenu] Valid song row clicked. Song ID:', currentRightClickedSongId);
                showContextMenu(event.clientX, event.clientY);
            } else {
                console.log('[ContextMenu] Clicked within tbody but not on a valid song row. Hiding custom menu.');
                // If right-click was within tbody but not on a valid song row, just hide the custom menu
                hideContextMenu();
            }
        } else {
            console.log('[ContextMenu] Right-click outside songTable tbody. Hiding custom menu.');
            // If right-click was outside the song table body, hide the custom menu
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
                } else if (action === 'edit-song') {
                    console.log('Edit Song clicked for song ID:', currentRightClickedSongId);
                    // TODO: Implement "Edit Song" logic
                    hideContextMenu();
                } else if (action === 'share-song') {
                    console.log('Share clicked for song ID:', currentRightClickedSongId);
                    // TODO: Implement "Share" logic
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

});