
let audio = null;
let audioElementReady = false;

function initializeAudioElement() {
    audio = document.getElementById('audioPlayer');
    if (!audio) {
        setTimeout(initializeAudioElement, 100);
        return;
    }
    window.audio = audio;
    audioElementReady = true;
    bindAudioTimeUpdate();
}
initializeAudioElement();

const musicState = {
    currentSongId: null, songName: "Loading...", artist: "Unknown Artist",
    playing: false, currentTime: 0, duration: 0, volume: 0.8,
    shuffleMode: "OFF", repeatMode: "OFF", cue: [], hasLyrics: false,
    currentSongData: null
};
window.musicState = musicState;

let deviceVolumes = {};
let deviceId = localStorage.getItem('musicDeviceId') || ('dev-' + Date.now());
localStorage.setItem('musicDeviceId', deviceId);

try {
    const savedVol = localStorage.getItem(`musicDeviceVolume:${deviceId}`);
    if (savedVol !== null) {
        const v = parseFloat(savedVol);
        deviceVolumes[deviceId] = v;
        musicState.volume = v;
        if (audio) audio.volume = v;
    }
} catch (e) {}

let draggingSeconds = false;
let draggingVolume = false;
let isUpdatingAudioSource = false;
let activeAudioOperation = null;
let audioOperationSequence = 0;

// --- Sync Logic ---
function performSync() {
    if (document.hidden || !audio || audio.paused || draggingSeconds || isUpdatingAudioSource) return;
    const pid = window.globalActiveProfileId || '1';
    const lastTime = lastServerTimeByProfile[pid];
    const lastTs = lastServerTimestampByProfile[pid];
    if (lastTime === undefined || lastTs === undefined) return;

    const elapsed = (Date.now() - lastTs) / 1000.0;
    const estimatedServerNow = lastTime + elapsed;
    const drift = estimatedServerNow - audio.currentTime;

    if (Math.abs(drift) > 3.0) {
        audio.currentTime = estimatedServerNow;
    } else if (Math.abs(drift) > 0.3) {
        audio.playbackRate = drift > 0 ? 1.02 : 0.98;
    } else {
        audio.playbackRate = 1.0;
    }
}
setInterval(performSync, 1000);

// --- UI Updates ---
function formatTime(s) {
    if (!s || isNaN(s)) return "0:00";
    return `${Math.floor(s / 60)}:${Math.floor(s % 60).toString().padStart(2, '0')}`;
}

function updateCoverImage() {
    const coverImg = document.getElementById('songCoverImage');
    const coverFallback = document.getElementById('songCoverFallback');
    if (coverImg && musicState.currentSongId) {
        if (musicState.currentSongData && musicState.currentSongData.artworkBase64) {
            coverImg.src = `data:image/jpeg;base64,${musicState.currentSongData.artworkBase64}`;
        } else {
            coverImg.src = `/api/music/cover/${musicState.currentSongId}`;
        }
        coverImg.style.display = 'block';
        if (coverFallback) coverFallback.style.display = 'none';
    }
}
window.updateCoverImage = updateCoverImage;

function updateMusicBar() {
    const {songName, artist, playing, currentTime, duration, volume, shuffleMode, repeatMode} = musicState;

    const titleEl = document.getElementById('songTitle');
    const artistEl = document.getElementById('songArtist');
    if (titleEl) titleEl.innerText = songName || "Unknown Title";
    if (artistEl) artistEl.innerText = artist || "Unknown Artist";

    const playPauseIcon = document.getElementById('playPauseIcon');
    if (playPauseIcon) {
        playPauseIcon.className = playing ? "pi pi-pause has-text-warning" : "pi pi-play has-text-success";
    }

    const timeEl = document.getElementById('musicCurrentTime');
    const totalTimeEl = document.getElementById('musicTotalTime');
    if (timeEl) timeEl.innerText = formatTime(currentTime);
    if (totalTimeEl) totalTimeEl.innerText = formatTime(duration);

    const timeSlider = document.getElementById('musicProgressBar');
    if (timeSlider && !draggingSeconds) {
        timeSlider.max = duration;
        timeSlider.value = currentTime;
        const progress = (duration > 0) ? (currentTime / duration) * 100 : 0;
        timeSlider.style.setProperty('--slider-progress', `${progress}%`);
        timeSlider.style.setProperty('--progress-value', `${progress}%`);
    }

    const volumeSlider = document.getElementById('musicVolumeProgressBar');
    if (volumeSlider && !draggingVolume) {
        const v = Math.sqrt(volume) * 100; // Square root to reverse the curve
        volumeSlider.value = v;
        volumeSlider.style.setProperty('--slider-progress', `${v}%`);
        volumeSlider.style.setProperty('--progress-value', `${v}%`);
    }

    const shuffleIcon = document.getElementById('shuffleIcon');
    if (shuffleIcon) {
        if (shuffleMode === "SMART_SHUFFLE") {
            shuffleIcon.className = "pi pi-sparkles has-text-success";
        } else if (shuffleMode === "SHUFFLE") {
            shuffleIcon.className = "pi pi-sort-alt has-text-info";
        } else {
            shuffleIcon.className = "pi pi-sort-alt";
        }
    }

    const repeatIcon = document.getElementById('repeatIcon');
    if (repeatIcon) {
        if (repeatMode === "ALL") {
            repeatIcon.className = "pi pi-refresh has-text-success";
        } else if (repeatMode === "ONE") {
            repeatIcon.className = "pi pi-refresh has-text-info";
        } else {
            repeatIcon.className = "pi pi-refresh";
        }
    }
    
    // Artwork - use stored artworkBase64 if available
    const coverImg = document.getElementById('songCoverImage');
    const coverFallback = document.getElementById('songCoverFallback');
    if (coverImg && musicState.currentSongId) {
        if (musicState.currentSongData && musicState.currentSongData.artworkBase64) {
            coverImg.src = `data:image/jpeg;base64,${musicState.currentSongData.artworkBase64}`;
        } else {
            coverImg.src = `/api/music/cover/${musicState.currentSongId}`;
        }
        coverImg.style.display = 'block';
        if (coverFallback) coverFallback.style.display = 'none';
    }
}

function updateAudioSource(songId, play = true) {
    if (!audioElementReady) return;
    isUpdatingAudioSource = true;
    const currentOp = ++audioOperationSequence;
    activeAudioOperation = currentOp;

    const profileId = window.globalActiveProfileId || '1';
    const source = `/api/music/stream/${profileId}/${songId}`;
    
    // Always bind the metadata listener to ensure isUpdatingAudioSource is cleared
    audio.onloadedmetadata = finalizeUpdate;

    if (audio.src !== window.location.origin + source) {
        audio.src = source;
        audio.volume = deviceVolumes[deviceId] !== undefined ? deviceVolumes[deviceId] : 0.8;
        
        if (play) {
            audio.play().catch(err => {
                console.log("Auto-play prevented or failed:", err);
                musicState.playing = false;
                updateMusicBar();
            });
        } else {
            audio.pause();
        }
        audio.load();
    } else {
        if (audio.readyState >= 1) {
            finalizeUpdate();
        }
    }

    function finalizeUpdate() {
        if (activeAudioOperation !== currentOp) return;
        isUpdatingAudioSource = false;
        console.log("🎵 Audio source update finalized");
        updateMusicBar();
    }
}

function bindAudioTimeUpdate() {
    audio.ontimeupdate = () => {
        if (!draggingSeconds && !isUpdatingAudioSource) {
            musicState.currentTime = audio.currentTime;
            if (audio.duration && !isNaN(audio.duration)) {
                musicState.duration = audio.duration;
            }
            updateMusicBar();
        }
    };
    audio.onloadedmetadata = () => {
        if (audio.duration && !isNaN(audio.duration)) {
            musicState.duration = audio.duration;
        }
        updateMusicBar();
    };
}

function bindControls() {
    // Play/Pause button handler
    const playPauseBtn = document.getElementById('playPauseBtn');
    if (playPauseBtn) {
        playPauseBtn.onclick = () => {
            // Optimistic UI update
            musicState.playing = !musicState.playing;
            updateMusicBar();
            
            // Immediate audio control for better UX
            if (audio) {
                if (musicState.playing) {
                    audio.play().catch(err => {
                        console.log("Play prevented or failed:", err);
                        musicState.playing = false;
                        updateMusicBar();
                    });
                } else {
                    audio.pause();
                }
            }
            
            // Server sync
            apiPost('toggle');
        };
    }

    const prevBtn = document.getElementById('prevBtn');
    if (prevBtn) {
        prevBtn.onclick = () => apiPost('previous');
    }

    const nextBtn = document.getElementById('nextBtn');
    if (nextBtn) {
        nextBtn.onclick = () => apiPost('next');
    }

    const shuffleBtn = document.getElementById('shuffleBtn');
    if (shuffleBtn) {
        shuffleBtn.onclick = () => apiPost('shuffle');
    }

    const repeatBtn = document.getElementById('repeatBtn');
    if (repeatBtn) {
        repeatBtn.onclick = () => apiPost('repeat');
    }

    const ts = document.getElementById('musicProgressBar');
    if (ts) {
        ts.onmousedown = ts.ontouchstart = () => { draggingSeconds = true; };
        ts.onmouseup = ts.ontouchend = () => {
            draggingSeconds = false;
            apiPost('seek', ts.value);
        };
        ts.oninput = () => {
            const progress = (ts.max > 0) ? (ts.value / ts.max) * 100 : 0;
            ts.style.setProperty('--slider-progress', `${progress}%`);
            ts.style.setProperty('--progress-value', `${progress}%`);
            const timeEl = document.getElementById('musicCurrentTime');
            if (timeEl) timeEl.innerText = formatTime(ts.value);
        };
    }

    const vs = document.getElementById('musicVolumeProgressBar');
    if (vs) {
        vs.onmousedown = vs.ontouchstart = () => { draggingVolume = true; };
        vs.onmouseup = vs.ontouchend = () => {
            draggingVolume = false;
        };
        vs.oninput = () => {
            const val = vs.value;
            const vol = Math.pow(val / 100.0, 2); // Squared curve
            if (audio) audio.volume = vol;
            musicState.volume = vol;
            deviceVolumes[deviceId] = vol;
            localStorage.setItem(`musicDeviceVolume:${deviceId}`, vol);
            vs.style.setProperty('--slider-progress', `${val}%`);
            vs.style.setProperty('--progress-value', `${val}%`);
        };
    }
}

function apiPost(type, value = null, silent = false) {
    console.log(`[MusicBar] apiPost: ${type}`, value, silent ? "(silent)" : "");
    const profileId = window.globalActiveProfileId || '1';
    const token = localStorage.getItem('authToken');
    
    let url = `/api/music/playback/${type}/${profileId}`;
    let successMessage = "";

    // Handle special cases where value is part of the URL
    if (type === 'seek') {
        url = `/api/music/playback/position/${profileId}/${value}`;
    } else if (type === 'volume') {
        url = `/api/music/playback/volume/${profileId}/${value}`;
    } else if (type === 'select') {
        url = `/api/music/playback/select/${profileId}/${value}`;
        successMessage = "Song selected";
    } else if (type === 'toggle') {
        successMessage = musicState.playing ? "Playing" : "Paused";
    } else if (type === 'pause') {
        successMessage = "Paused";
    } else if (type === 'play') {
        successMessage = "Playing";
    } else if (type === 'next') {
        successMessage = "Skipped to next";
    } else if (type === 'previous') {
        successMessage = "Skipped to previous";
    } else if (type === 'shuffle') {
        successMessage = "Shuffle toggled";
    } else if (type === 'repeat') {
        successMessage = "Repeat toggled";
    }

    const headers = {
        'Content-Type': 'application/json'
    };
    if (token) {
        headers['Authorization'] = `Bearer ${token}`;
    }

    fetch(url, {
        method: 'POST',
        headers: headers,
        credentials: 'same-origin'
    }).then(response => {
        if (response.ok && successMessage && window.Toast && !silent) {
            window.Toast.success(successMessage);
        } else if (response.status === 401) {
            console.error("[MusicBar] Unauthorized API call - check token");
        }
    });
}

// --- WebSocket Sync ---
let ws;
const lastServerTimeByProfile = {};
const lastServerTimestampByProfile = {};

window.setVideoPlaying = (active) => {
    window.videoPlaying = active;
    console.log(`[MusicBar] setVideoPlaying: ${active}`);
    const player = document.getElementById('musicPlayerContainer');
    if (active) {
        if (player) player.style.setProperty('display', 'none', 'important');
        
        // Only send the pause command if the music is actually playing
        if (musicState.playing) {
            if (audio && !audio.paused) {
                console.log("[MusicBar] Force pausing local audio for video");
                audio.pause();
            }
            // Use the new silent flag to prevent the "Paused" toast
            apiPost('pause', null, true);
        }
    } else {
        if (player) player.style.setProperty('display', 'flex', 'important');
    }
};

// Smooth UI update loop
setInterval(() => {
    // Normal UI updates
    if (audio && !audio.paused && !draggingSeconds && !isUpdatingAudioSource) {
        musicState.currentTime = audio.currentTime;
        updateMusicBar();
    }
    
    // EXTREMELY AGGRESSIVE Safety check for video playback
    if (window.videoPlaying === true) {
        const player = document.getElementById('musicPlayerContainer');
        if (player) {
            // Force it hidden no matter what
            if (player.style.display !== 'none') {
                console.log("[MusicBar] Failsafe: Hiding player because video is active");
                player.style.setProperty('display', 'none', 'important');
            }
        }
        if (audio && !audio.paused) {
            console.log("[MusicBar] Failsafe: Pausing audio because video is active");
            audio.pause();
        }
    }
}, 250);

function connectWS() {
    const profileId = window.globalActiveProfileId || '1';
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(`${protocol}//${window.location.host}/api/music/ws/${profileId}`);
    ws.onmessage = (e) => {
        const message = JSON.parse(e.data);
        if (message.type === 'state') {
            const state = message.payload;
            console.log('[MusicBar] WS State:', state.playing ? 'Playing' : 'Paused', state.currentSongId);
            lastServerTimeByProfile[profileId] = state.currentTime;
            lastServerTimestampByProfile[profileId] = Date.now();
            
            // ... (rest of the logic)

            // Always sync these basic state properties
            musicState.playing = state.playing;
            musicState.shuffleMode = state.shuffleMode;
            musicState.repeatMode = state.repeatMode;
            
            if (state.duration && state.duration > 0) {
                musicState.duration = state.duration;
            }

            // Sync current time if not dragging and either significantly drifted or paused
            if (!draggingSeconds) {
                const drift = Math.abs(musicState.currentTime - state.currentTime);
                if (!musicState.playing || drift > 2) {
                    musicState.currentTime = state.currentTime;
                    if (audio && Math.abs(audio.currentTime - state.currentTime) > 2) {
                        audio.currentTime = state.currentTime;
                    }
                }
            }

            if (state.currentSongId !== musicState.currentSongId) {
                musicState.currentSongId = state.currentSongId;
                musicState.songName = state.songName;
                musicState.artist = state.artistName;
                
                // Fetch full song data including artworkBase64
                const profileId = window.globalActiveProfileId || '1';
                if (state.currentSongId) {
                    fetch(`/api/music/playback/current/${profileId}`, { credentials: 'same-origin' })
                        .then(r => r.json())
                        .then(data => {
                            if (data && data.data) {
                                musicState.currentSongData = data.data;
                                musicState.hasLyrics = data.data.lyrics != null;
                                updateCoverImage();
                                
                                // Update media session with artwork
                                let artworkUrl = null;
                                if (musicState.currentSongData && musicState.currentSongData.artworkBase64) {
                                    artworkUrl = `data:image/jpeg;base64,${musicState.currentSongData.artworkBase64}`;
                                }
                                
                                if (window.updateMediaSessionMetadata) {
                                    window.updateMediaSessionMetadata(
                                        state.songName,
                                        state.artistName,
                                        artworkUrl
                                    );
                                }
                            }
                        })
                        .catch(err => console.error('[MusicBar] Failed to fetch song data:', err));
                }
                
                updateAudioSource(state.currentSongId, state.playing);
            } else {
                if (state.playing && audio.paused) {
                    audio.play().catch(() => {});
                } else if (!state.playing && !audio.paused) {
                    audio.pause();
                }
                if (window.updateMediaSessionPlaybackState) {
                    window.updateMediaSessionPlaybackState(state.playing);
                }
                updateMusicBar();
            }
        }
    };
    ws.onclose = () => setTimeout(connectWS, 2000);
}

document.addEventListener('DOMContentLoaded', () => { 
    bindControls(); 
    connectWS(); 
    
    // Initialize Media Session Handlers
    if (window.setupMediaSessionHandlers) {
        window.setupMediaSessionHandlers(apiPost, window.setPlaybackTime, audio);
    }
});
