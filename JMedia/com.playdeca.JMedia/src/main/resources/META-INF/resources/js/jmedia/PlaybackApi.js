(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    JMedia.PlaybackApi = {
        select: function(songId, profileId) {
            profileId = profileId || JMedia.Helpers.getActiveProfileId();
            return fetch(`/api/music/playback/select/${profileId}/${songId}`, {method: 'POST'});
        },

        pause: function(profileId, silent) {
            profileId = profileId || JMedia.Helpers.getActiveProfileId();
            return fetch(`/api/music/playback/pause/${profileId}`, {method: 'POST'});
        },

        play: function(profileId, silent) {
            profileId = profileId || JMedia.Helpers.getActiveProfileId();
            return fetch(`/api/music/playback/play/${profileId}`, {method: 'POST'});
        },

        toggle: function(profileId) {
            profileId = profileId || JMedia.Helpers.getActiveProfileId();
            return fetch(`/api/music/playback/toggle/${profileId}`, {method: 'POST'});
        },

        next: function(profileId) {
            profileId = profileId || JMedia.Helpers.getActiveProfileId();
            return fetch(`/api/music/playback/next/${profileId}`, {method: 'POST'});
        },

        previous: function(profileId) {
            profileId = profileId || JMedia.Helpers.getActiveProfileId();
            return fetch(`/api/music/playback/previous/${profileId}`, {method: 'POST'});
        },

        shuffle: function(profileId) {
            profileId = profileId || JMedia.Helpers.getActiveProfileId();
            return fetch(`/api/music/playback/shuffle/${profileId}`, {method: 'POST'});
        },

        repeat: function(profileId) {
            profileId = profileId || JMedia.Helpers.getActiveProfileId();
            return fetch(`/api/music/playback/repeat/${profileId}`, {method: 'POST'});
        },

        queueSimilar: function(songId, profileId) {
            profileId = profileId || JMedia.Helpers.getActiveProfileId();
            return fetch(`/api/music/queue/similar/${profileId}/${songId}`, {method: 'POST'});
        },

        enrichMetadata: function(songId, profileId) {
            profileId = profileId || JMedia.Helpers.getActiveProfileId();
            return fetch(`/api/music/playback/enrich/${songId}`, {method: 'POST'});
        },

        seek: function(value, profileId) {
            profileId = profileId || JMedia.Helpers.getActiveProfileId();
            return fetch(`/api/music/playback/seek/${profileId}`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({position: value})
            });
        },

        getAudioElement: function() {
            return window.AudioEngine ? window.AudioEngine.getAudioElement() : (window.audio || null);
        },

        rescanSong: function(songId) {
            if (window.WebSocketManager && typeof window.WebSocketManager.send === 'function') {
                window.WebSocketManager.send('rescanLibrary', { songId: songId });
            }
        },

        deleteSong: function(songId) {
            return fetch(`/api/music/songs/${songId}`, {method: 'DELETE'});
        },

        getCurrentSongId: function() {
            const state = window.StateManager ? window.StateManager.getState() : null;
            return state ? state.currentSongId : null;
        },

        isPlaying: function() {
            const state = window.StateManager ? window.StateManager.getState() : null;
            return state ? state.playing : false;
        },

        getQueueSize: function() {
            const state = window.StateManager ? window.StateManager.getState() : null;
            return state && state.cue ? state.cue.length : 0;
        },

        addSongToPlaylist: function(playlistId, songId) {
            if (!playlistId || !songId) {
                return Promise.reject(new Error('Missing parameters'));
            }
            return fetch(`/api/music/playlists/${playlistId}/songs/${songId}`, {
                method: 'POST'
            });
        },

        setPlaybackTime: function(newTime, fromClient) {
            if (window.TimeController) {
                if (fromClient) {
                    window.TimeController.handleSeek(newTime);
                } else {
                    window.TimeController.handleTimeChange(newTime);
                }
            }
        }
    };

    // Keep backward-compatible apiPost for old code
    window.apiPost = async (path, arg2 = null) => {
        const profileId = JMedia.Helpers.getActiveProfileId();
        if (typeof path === 'string' && path.startsWith('select/')) {
            const parts = path.split('/');
            if (parts.length === 3) {
                return JMedia.PlaybackApi.select(parts[2], parts[1]);
            }
            return JMedia.PlaybackApi.select(parts[1], profileId);
        } else if (path === 'select' && arg2) {
            return JMedia.PlaybackApi.select(arg2, profileId);
        } else if (path === 'pause') {
            return JMedia.PlaybackApi.pause(profileId, arg2);
        } else if (path === 'play') {
            return JMedia.PlaybackApi.play(profileId, arg2);
        } else if (path === 'toggle') {
            return JMedia.PlaybackApi.toggle(profileId);
        } else if (path === 'next') {
            return JMedia.PlaybackApi.next(profileId);
        } else if (path === 'previous') {
            return JMedia.PlaybackApi.previous(profileId);
        } else if (path === 'shuffle') {
            return JMedia.PlaybackApi.shuffle(profileId);
        } else if (path === 'repeat') {
            return JMedia.PlaybackApi.repeat(profileId);
        } else if (path === 'seek' && arg2 !== null) {
            return JMedia.PlaybackApi.seek(arg2, profileId);
        }
        return fetch(`/api/music/playback/${path}/${profileId}`, {method: 'POST'});
    };

    window.addSongToPlaylist = function(playlistId, songId) {
        return JMedia.PlaybackApi.addSongToPlaylist(playlistId, songId);
    };

    window.setPlaybackTime = function(newTime, fromClient) {
        if (window.TimeController) {
            if (fromClient) {
                window.TimeController.handleSeek(newTime);
            } else {
                window.TimeController.handleTimeChange(newTime);
            }
        }
    };

    document.addEventListener('click', (e) => {
        const songItem = e.target.closest('.mobile-song-item, tr[data-song-id]');
        if (songItem && !e.defaultPrevented) {
            const songId = songItem.dataset.songId;
            if (songId && window.apiPost) {
                window.apiPost('select', songId);
            }
        }
    });

})(window);
