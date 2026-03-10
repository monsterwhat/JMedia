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
    shuffleMode: "OFF", repeatMode: "OFF", cue: [], hasLyrics: false
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

let lastServerTimeByProfile = {};
let lastServerTimestampByProfile = {};
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
    
    const timeEl = document.getElementById('currentTime');
    const totalTimeEl = document.getElementById('totalTime');
    if (timeEl) timeEl.innerText = formatTime(currentTime);
    if (totalTimeEl) totalTimeEl.innerText = formatTime(duration);

    const timeSlider = document.getElementById('playbackProgressBar');
    if (timeSlider && !draggingSeconds) {
        timeSlider.max = duration;
        timeSlider.value = currentTime;
        const progress = (duration > 0) ? (currentTime / duration) * 100 : 0;
        timeSlider.style.setProperty('--slider-progress', `${progress}%`);
    }

    const volumeSlider = document.getElementById('volumeProgressBar');
    if (volumeSlider && !draggingVolume) {
        const volVal = Math.sqrt(volume) * 100;
        volumeSlider.value = volVal;
        volumeSlider.style.setProperty('--slider-progress', `${volVal}%`);
    }

    // Update Shuffle Button
    const shuffleBtn = document.getElementById('shuffleBtn');
    const shuffleIcon = document.getElementById('shuffleIcon');
    if (shuffleBtn && shuffleIcon) {
        shuffleBtn.classList.remove('shuffle-off', 'shuffle-on', 'shuffle-smart');
        shuffleIcon.className = 'pi';
        if (shuffleMode === 'SHUFFLE') {
            shuffleBtn.classList.add('shuffle-on');
            shuffleIcon.classList.add('pi-sort-alt-slash');
        } else if (shuffleMode === 'SMART_SHUFFLE') {
            shuffleBtn.classList.add('shuffle-smart');
            shuffleIcon.classList.add('pi-sparkles');
        } else {
            shuffleBtn.classList.add('shuffle-off');
            shuffleIcon.classList.add('pi-sort-alt');
        }
    }

    // Update Repeat Button
    const repeatBtn = document.getElementById('repeatBtn');
    if (repeatBtn) {
        repeatBtn.classList.remove('repeat-off', 'repeat-one', 'repeat-all');
        if (repeatMode === 'ONE') repeatBtn.classList.add('repeat-one');
        else if (repeatMode === 'ALL') repeatBtn.classList.add('repeat-all');
        else repeatBtn.classList.add('repeat-off');
    }

    // Update Media Session API
    if (window.updateMediaSessionPlaybackState) {
        window.updateMediaSessionPlaybackState(playing);
    }
}

function bindAudioTimeUpdate() {
    audio.ontimeupdate = () => {
        if (!draggingSeconds && !isUpdatingAudioSource) {
            musicState.currentTime = audio.currentTime;
            updateMusicBar();
        }
    };
    audio.onended = () => apiPost('next', 'Next Song');
}

// --- Audio Source ---
async function UpdateAudioSource(currentSong, prevSong, nextSong, play, backendTime) {
    if (!currentSong) return;
    const opId = ++audioOperationSequence;
    activeAudioOperation = opId;
    isUpdatingAudioSource = true;

    const profileId = window.globalActiveProfileId || '1';
    const newSrc = `/api/music/stream/${profileId}/${currentSong.id}`;

    // Update state immediately
    musicState.currentSongId = currentSong.id;
    musicState.songName = currentSong.title;
    musicState.artist = currentSong.artist;
    musicState.duration = currentSong.durationSeconds;
    
    const artworkUrl = currentSong.artworkBase64 ? `data:image/jpeg;base64,${currentSong.artworkBase64}` : '/logo.png';
    const img = document.getElementById('songCoverImage');
    if (img) img.src = artworkUrl;

    // Update Media Session Metadata
    if (window.updateMediaSessionMetadata) {
        window.updateMediaSessionMetadata(currentSong.title, currentSong.artist, artworkUrl);
    }

    updateMusicBar();

    // Finalize update function
    const finalizeUpdate = () => {
        if (activeAudioOperation !== opId) return;
        
        // Only seek if we have a valid backend time and it's significantly different
        if (backendTime !== undefined && backendTime !== null) {
            const drift = Math.abs(audio.currentTime - backendTime);
            if (drift > 2.0 || audio.currentTime === 0) {
                audio.currentTime = backendTime;
            }
        }
        
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
        
        isUpdatingAudioSource = false;
        updateMusicBar();
    };

    if (audio.src !== new URL(newSrc, window.location.origin).href) {
        audio.src = newSrc;
        audio.onloadedmetadata = finalizeUpdate;
        audio.load();
    } else {
        // If src is the same, check if metadata is already there
        if (audio.readyState >= 1) {
            finalizeUpdate();
        } else {
            audio.onloadedmetadata = finalizeUpdate;
        }
    }
}

// --- WS & API ---
let ws;
const apiPost = (path, toastMsg) => {
    return fetch(`/api/music/playback/${path}/${window.globalActiveProfileId || '1'}`, {method: 'POST'})
        .then(res => {
            if (res.ok && toastMsg && window.Toast) {
                window.Toast.success(toastMsg);
            }
            return res;
        });
};
window.apiPost = apiPost;

window.setPlaybackTime = (newTime, fromClient = false) => {
    if (audio) {
        audio.currentTime = newTime;
        if (fromClient) {
            fetch(`/api/music/playback/position/${window.globalActiveProfileId || '1'}/${newTime}`, {method: 'POST'});
        }
    }
};

function connectWS() {
    const profileId = window.globalActiveProfileId || '1';
    ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + `/api/music/ws/${profileId}`);
    ws.onmessage = (msg) => {
        const data = JSON.parse(msg.data);
        if (data.type === 'state') {
            const state = data.payload;
            lastServerTimeByProfile[profileId] = state.currentTime;
            lastServerTimestampByProfile[profileId] = Date.now();
            
            if (String(state.currentSongId) !== String(musicState.currentSongId)) {
                fetch(`/api/music/playback/current/${profileId}`).then(r => r.json()).then(res => {
                    UpdateAudioSource(res.data, null, null, state.playing, state.currentTime);
                });
            } else {
                musicState.playing = state.playing;
                musicState.shuffleMode = state.shuffleMode;
                musicState.repeatMode = state.repeatMode;
                if (state.playing && audio.paused) audio.play().catch(() => {});
                else if (!state.playing && !audio.paused) audio.pause();
                updateMusicBar();
            }
        }
    };
    ws.onclose = () => setTimeout(connectWS, 2000);
}

// --- Controls ---
function bindControls() {
    const ts = document.getElementById('playbackProgressBar');
    if (ts) {
        ts.onmousedown = ts.ontouchstart = () => draggingSeconds = true;
        window.addEventListener('mouseup', () => {
            if (draggingSeconds) {
                draggingSeconds = false;
                audio.currentTime = ts.value;
                fetch(`/api/music/playback/position/${window.globalActiveProfileId || '1'}/${ts.value}`, {method: 'POST'});
            }
        });
        ts.oninput = () => {
            ts.style.setProperty('--slider-progress', `${(ts.value / (ts.max || 1)) * 100}%`);
            document.getElementById('currentTime').innerText = formatTime(ts.value);
        };
    }

    const vs = document.getElementById('volumeProgressBar');
    if (vs) {
        vs.onmousedown = vs.ontouchstart = () => draggingVolume = true;
        window.addEventListener('mouseup', () => { draggingVolume = false; });
        vs.oninput = () => {
            const val = vs.value;
            const vol = Math.pow(val / 100, 2);
            audio.volume = vol;
            musicState.volume = vol;
            deviceVolumes[deviceId] = vol;
            localStorage.setItem(`musicDeviceVolume:${deviceId}`, vol);
            vs.style.setProperty('--slider-progress', `${val}%`);
        };
    }

    document.getElementById('playPauseBtn').onclick = () => apiPost('toggle', musicState.playing ? 'Paused' : 'Playing');
    document.getElementById('prevBtn').onclick = () => apiPost('previous', 'Previous Song');
    document.getElementById('nextBtn').onclick = () => apiPost('next', 'Next Song');
    document.getElementById('shuffleBtn').onclick = () => apiPost('shuffle', 'Shuffle Toggled');
    document.getElementById('repeatBtn').onclick = () => apiPost('repeat', 'Repeat Toggled');
}

// --- Context Menu Logic ---
window.selectedContextMenuSongId = null;

function showContextMenu(x, y, songId) {
    const menu = document.getElementById('customContextMenu');
    if (!menu) return;
    window.selectedContextMenuSongId = songId;
    menu.style.left = `${x}px`;
    menu.style.top = `${y}px`;
    menu.style.display = 'block';
}

function hideContextMenu() {
    const menu = document.getElementById('customContextMenu');
    if (menu) menu.style.display = 'none';
}

document.addEventListener('contextmenu', e => {
    const item = e.target.closest('.mobile-song-item');
    if (item) { e.preventDefault(); showContextMenu(e.clientX, e.clientY, item.dataset.songId); }
});

document.addEventListener('click', e => {
    if (!e.target.closest('#customContextMenu')) hideContextMenu();
    
    const actionItem = e.target.closest('.context-menu-item, .mobile-context-list li');
    if (actionItem && window.selectedContextMenuSongId) {
        const action = actionItem.dataset.action;
        const songId = window.selectedContextMenuSongId;
        const pid = window.globalActiveProfileId || '1';

        if (action === 'queue') {
            fetch(`/api/music/queue/add/${pid}/${songId}`, {method: 'POST'}).then(() => { if(window.Toast) window.Toast.success('Song added to queue'); });
        } else if (action === 'queue-similar') {
            fetch(`/api/music/queue/similar/${pid}/${songId}`, {method: 'POST'}).then(() => { if(window.Toast) window.Toast.success('Similar songs queued'); });
        } else if (action === 'rescan') {
            fetch(`/api/settings/${pid}/rescan-song/${songId}`, {method: 'POST'}).then(() => { if(window.Toast) window.Toast.success('Rescan started'); });
        } else if (action === 'enrich') {
            fetch(`/api/metadata/enrich/${songId}?profileId=${pid}`, {method: 'POST'}).then(() => { if(window.Toast) window.Toast.success('Metadata update started'); });
        } else if (action === 'delete') {
            if (confirm('Delete?')) fetch(`/api/settings/${pid}/songs/${songId}`, {method: 'DELETE'}).then(() => location.reload());
        } else if (action === 'add-to-playlist' || action === 'playlist') {
            const sub = document.getElementById('playlistSubMenu');
            if (sub) {
                if (sub.style.display === 'block') sub.style.display = 'none';
                else {
                    sub.style.display = 'block';
                    fetch(`/api/music/playlists/${pid}`).then(r => r.json()).then(res => {
                        const lists = res.data || [];
                        sub.innerHTML = lists.map(l => `<div class="context-menu-item" onclick="window.addSongToPlaylist(${l.id}, ${songId})">${l.name}</div>`).join('');
                    });
                }
                return;
            }
        }
        hideContextMenu();
        if (window.hideMobileContextMenu) window.hideMobileContextMenu();
    }
});

window.addSongToPlaylist = (playlistId, songId) => {
    fetch(`/api/music/playlists/${playlistId}/songs/${songId}/${window.globalActiveProfileId || '1'}`, {method: 'POST'})
        .then(() => { if(window.Toast) window.Toast.success('Added to playlist'); hideContextMenu(); });
};

// --- Mobile Long Press ---
let lpTimer;
document.addEventListener('touchstart', e => {
    const item = e.target.closest('.mobile-song-item');
    if (item) lpTimer = setTimeout(() => {
        window.selectedContextMenuSongId = item.dataset.songId;
        const menu = document.getElementById('mobileContextMenu');
        if (menu) { menu.style.display = 'block'; menu.classList.add('active'); }
    }, 600);
}, {passive: true});
document.addEventListener('touchend', () => clearTimeout(lpTimer));

window.hideMobileContextMenu = () => {
    const menu = document.getElementById('mobileContextMenu');
    if (menu) { menu.classList.remove('active'); setTimeout(() => menu.style.display = 'none', 300); }
};

document.addEventListener('DOMContentLoaded', () => { 
    bindControls(); 
    connectWS(); 
    
    // Initialize Media Session Handlers
    if (window.setupMediaSessionHandlers) {
        window.setupMediaSessionHandlers(apiPost, window.setPlaybackTime, audio);
    }
});
