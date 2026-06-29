(function(window) {
    'use strict';

    window.PlayerStateManager = class {
        constructor(player) {
            this.player = player;
        }

        initState() {
            const p = this.player;

            p.profileId = localStorage.getItem('activeProfileId') || '1';
            p.deviceToken = sessionStorage.getItem('jmedia_device_token');
            if (!p.deviceToken) {
                p.deviceToken = (crypto.randomUUID && crypto.randomUUID()) || 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
                    const r = (Math.random() * 16) | 0;
                    const v = c === 'x' ? r : (r & 0x3) | 0x8;
                    return v.toString(16);
                });
                sessionStorage.setItem('jmedia_device_token', p.deviceToken);
            }
            p.volumeKey = 'jmedia_video_volume_' + p.profileId;
            p.muteKey = 'jmedia_video_mute_' + p.profileId;
            p.userActiveTimeout = null;
            p.isIOSNativeFullscreen = false;
            p._streamFallbackCount = 0;
            p._maxStreamFallbacks = 2;
            p._preferredQuality = 720;
            p._previousQuality = 0;
            p._qualitySwitching = false;
            p._hasPlayedData = false;
            p._stallTimer = null;
            p._destroyed = false;

            const rawDur = parseFloat(p.container.dataset.duration || 0);
            p.totalDuration = rawDur > 5000 ? rawDur / 1000 : rawDur;

            p.state = {
                playing: false,
                volume: parseFloat(localStorage.getItem(p.volumeKey) || '0.7'),
                muted: localStorage.getItem(p.muteKey) === 'true',
                lastSeekTime: 0
            };

            p.currentAudioTrackIndex = null;

            p.markers = {
                introStart: parseFloat(p.container.dataset.introStart || 0),
                introEnd: parseFloat(p.container.dataset.introEnd || 0),
                outroStart: parseFloat(p.container.dataset.outroStart || 0),
                outroEnd: parseFloat(p.container.dataset.outroEnd || 0),
                recapStart: parseFloat(p.container.dataset.recapStart || 0),
                recapEnd: parseFloat(p.container.dataset.recapEnd || 0)
            };

            p.originalMarkers = { ...p.markers };

            p.markerSources = {
                introStart: 'FILE',
                introEnd: 'FILE',
                outroStart: 'FILE',
                outroEnd: 'FILE',
                recapStart: 'FILE',
                recapEnd: 'FILE'
            };

            p.autoSkipIntro = p.container.dataset.autoSkipIntro === 'true';
            p.autoSkipRecap = p.container.dataset.autoSkipRecap === 'true';
            p.autoSkipOutro = p.container.dataset.autoSkipOutro === 'true';
            p._autoSkipUndoTime = 0;
            p._autoSkipSection = null;
            p._autoSkipTimer = null;
            p._isUndoing = false;

            p.storyboard = { metadata: null, loaded: false };

            const videoTrack = localStorage.getItem('jmedia_last_track_' + p.videoId);
            const globalTrack = sessionStorage.getItem('jmedia_global_subtitle_track');
            p.lastSelectedTrackId = videoTrack || globalTrack;

            p.preferredAudioLanguage = p.container.dataset.preferredAudioLanguage || null;
            p.defaultAudioTrackId = p.container.dataset.defaultAudioTrackId || null;

            p.initialResumeTime = parseFloat(p.container.dataset.startTime || 0);
        }

        applyAudioPreference() {
            const p = this.player;
            const videoId = p.videoId;
            let trackToApply = null;
            let isDefault = false;

            const savedTrack = localStorage.getItem('jmedia_audio_track_' + videoId);
            if (savedTrack) {
                if (savedTrack === 'default') {
                    isDefault = true;
                    trackToApply = -1;
                } else {
                    trackToApply = parseInt(savedTrack);
                }
                console.log('[SimplePlayer] Found saved track in localStorage:', savedTrack);
            }

            if (trackToApply === null && p.defaultAudioTrackId) {
                trackToApply = parseInt(p.defaultAudioTrackId);
                console.log('[SimplePlayer] Using defaultAudioTrackId:', trackToApply);
            }

            if (isDefault || (trackToApply !== null && !isNaN(trackToApply))) {
                if (isDefault && p.setAudioTrack) {
                    p.setAudioTrack('default');
                } else {
                    if (window.player && window.player.switchAudioTrack) {
                        window.player.switchAudioTrack(trackToApply);
                    }
                    if (p.video && p.video.audioTracks && p.video.audioTracks.length > 0) {
                        p.video.audioTracks.forEach((track, idx) => {
                            track.enabled = (idx === trackToApply);
                        });
                    }
                }
                p.currentAudioTrackIndex = isDefault ? null : trackToApply;
                console.log('[SimplePlayer] Applied audio preference:', isDefault ? 'default' : trackToApply);
            }
        }

        async refreshMarkers(retries = 3) {
            const p = this.player;
            console.log('[SimplePlayer] Refreshing markers for episode...');
            try {
                const res = await fetch(`/api/video/${p.videoId}`);
                if (res.ok) {
                    const json = await res.json();
                    const data = json.data || json;

                    if (data.introStart !== undefined) {
                        p.markerSources = {
                            introStart: 'SERVER-REFRESHED',
                            introEnd: 'SERVER-REFRESHED',
                            outroStart: 'SERVER-REFRESHED',
                            outroEnd: 'SERVER-REFRESHED',
                            recapStart: 'SERVER-REFRESHED',
                            recapEnd: 'SERVER-REFRESHED'
                        };

                        p.markers = {
                            introStart: data.introStart || 0,
                            introEnd: data.introEnd || 0,
                            outroStart: data.outroStart || 0,
                            outroEnd: data.outroEnd || 0,
                            recapStart: data.recapStart || 0,
                            recapEnd: data.recapEnd || 0
                        };
                        console.log('[SimplePlayer] Markers refreshed:', p.markers);
                        p.updateMarkers();
                        p.checkMarkers();

                        const allZeroNow = Object.values(p.markers).every(v => v === 0);
                        if (allZeroNow && retries > 0) {
                            console.log(`[SimplePlayer] Markers still zero, retrying in 2s... (${retries} left)`);
                            setTimeout(() => this.refreshMarkers(retries - 1), 2000);
                        }
                    }
                }
            } catch (e) {
                console.error('[SimplePlayer] Failed to refresh markers', e);
            }
        }

        forceRefreshEpisode() {
            const p = this.player;
            this._triggerDebugRefresh('episode', () => {
                p.refreshMarkers(3);
                return fetch(`/api/video/metadata/${p.videoId}/reload`, { method: 'POST' });
            });
        }

        forceRefreshSeason() {
            const p = this.player;
            this._triggerDebugRefresh('season', () =>
                fetch(`/api/video/metadata/series/${encodeURIComponent(p.debugInfo.seriesTitle)}/season/${p.debugInfo.seasonNumber}/reload`, { method: 'POST' })
            );
        }

        forceRefreshShow() {
            const p = this.player;
            this._triggerDebugRefresh('show', () =>
                fetch(`/api/video/metadata/series/${encodeURIComponent(p.debugInfo.seriesTitle)}/reload`, { method: 'POST' })
            );
        }

        _triggerDebugRefresh(type, apiCall) {
            const p = this.player;
            console.log(`[SimplePlayer] Manual ${type} refresh:`,
                p.container.dataset.title,
                `S${p.debugInfo.seasonNumber}E${p.debugInfo.episodeNumber}`,
                `IMDb ID: ${p.debugInfo.seriesImdbId}`
            );

            p.refreshStatus = `Refreshing ${type}...`;
            p.refreshError = null;
            p._updateDebugDialog();

            if (window.Toast) window.Toast.info(`Refreshing ${type} metadata...`);

            apiCall()
                .then(res => {
                    if (res.ok) {
                        console.log(`[SimplePlayer] ${type} refresh initiated`);
                        p.refreshStatus = `${type.charAt(0).toUpperCase() + type.slice(1)} refresh started`;
                        p.refreshError = null;
                        if (window.Toast) window.Toast.success(`${type.charAt(0).toUpperCase() + type.slice(1)} metadata refresh started`);

                        if (type === 'episode') {
                            setTimeout(() => p.refreshMarkers(3), 1500);
                        }
                    } else {
                        throw new Error(`HTTP ${res.status}`);
                    }
                })
                .catch(err => {
                    console.error(`[SimplePlayer] ${type} refresh failed:`, err);
                    p.refreshStatus = `Failed`;
                    p.refreshError = err.message || 'Unknown error';
                    if (window.Toast) window.Toast.error(`Failed to refresh ${type} metadata`);
                })
                .finally(() => {
                    p._updateDebugDialog();
                });
        }

        _postAutoSkipSetting(section, enabled) {
            const p = this.player;
            const profileId = localStorage.getItem('activeProfileId') || '1';
            const key = 'autoSkip' + section.charAt(0).toUpperCase() + section.slice(1);
            const body = {};
            body[key] = enabled;
            fetch(`/api/settings/${profileId}/auto-skip`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            }).catch(err => console.error('[SimplePlayer] Failed to save auto-skip setting:', err));
        }

        saveAndReload() {
            const p = this.player;
            (async () => {
                const seriesTitle = p.dialogSeriesInput?.value?.trim() || '';
                const seasonNumber = p.dialogSeasonInput?.value?.trim() || '';
                const episodeNumber = p.dialogEpisodeInput?.value?.trim() || '';
                const showImdbId = p.dialogImdbInput?.value?.trim() || '';

                const formData = new FormData();
                if (seriesTitle) formData.append('seriesTitle', seriesTitle);
                if (seasonNumber) formData.append('seasonNumber', seasonNumber);
                if (episodeNumber) formData.append('episodeNumber', episodeNumber);
                if (showImdbId) formData.append('showImdbId', showImdbId);

                p.refreshStatus = 'Saving overrides...';
                p._updateDebugDialog();

                try {
                    const saveRes = await fetch(`/api/video/manage/update/${p.videoId}`, {
                        method: 'POST',
                        body: new URLSearchParams(formData)
                    });

                    if (!saveRes.ok) throw new Error(`Save failed: HTTP ${saveRes.status}`);

                    p.refreshStatus = 'Overrides saved, re-enriching...';
                    p._updateDebugDialog();

                    const reloadRes = await fetch(`/api/video/metadata/${p.videoId}/reload`, { method: 'POST' });
                    if (!reloadRes.ok) throw new Error(`Reload failed: HTTP ${reloadRes.status}`);

                    p.refreshStatus = 'Re-enrichment triggered, fetching fresh data...';

                    await new Promise(r => setTimeout(r, 2000));

                    const videoRes = await fetch(`/api/video/${p.videoId}`);
                    if (videoRes.ok) {
                        const json = await videoRes.json();
                        const data = json.data || json;

                        p.debugInfo.seriesTitle = data.seriesTitle || p.debugInfo.seriesTitle;
                        p.debugInfo.seasonNumber = data.seasonNumber || p.debugInfo.seasonNumber;
                        p.debugInfo.episodeNumber = data.episodeNumber || p.debugInfo.episodeNumber;
                        p.debugInfo.seriesImdbId = data.showImdbId || data.seriesImdbId || p.debugInfo.seriesImdbId;

                        if (data.introStart !== undefined) {
                            p.markers = {
                                introStart: data.introStart || 0,
                                introEnd: data.introEnd || 0,
                                outroStart: data.outroStart || 0,
                                outroEnd: data.outroEnd || 0,
                                recapStart: data.recapStart || 0,
                                recapEnd: data.recapEnd || 0
                            };
                            p.markerSources = {
                                introStart: 'SERVER-REFRESHED',
                                introEnd: 'SERVER-REFRESHED',
                                outroStart: 'SERVER-REFRESHED',
                                outroEnd: 'SERVER-REFRESHED',
                                recapStart: 'SERVER-REFRESHED',
                                recapEnd: 'SERVER-REFRESHED'
                            };
                        }

                        p.refreshStatus = 'Overrides saved and data refreshed';
                        p.refreshError = null;
                    } else {
                        p.refreshStatus = 'Overrides saved, but failed to fetch fresh data';
                    }
                } catch (err) {
                    console.error('[SimplePlayer] Save & reload failed:', err);
                    p.refreshStatus = 'Save failed';
                    p.refreshError = err.message || 'Unknown error';
                }

                p._updateDebugDialog();
            })();
        }
    };
})(window);
