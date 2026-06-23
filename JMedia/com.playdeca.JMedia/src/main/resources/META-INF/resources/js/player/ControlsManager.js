(function(window) {
    'use strict';

    window.PlayerControlsManager = class {
        constructor(player) {
            this.player = player;
        }

        showControls() {
            const p = this.player;
            p.container.classList.remove('controls-hidden');
            if (window.subtitleManager) window.subtitleManager.setSubtitleLift(45);

            clearTimeout(p.userActiveTimeout);
            if (!p.video.paused) {
                p.userActiveTimeout = setTimeout(() => {
                    p.container.classList.add('controls-hidden');
                    if (window.subtitleManager) window.subtitleManager.setSubtitleLift(0);
                }, 3000);
            }
        }

        applyInitialState() {
            const p = this.player;
            p.video.volume = Math.pow(p.state.volume, 2);
            p.video.muted = p.state.muted;
            this.updateVolumeUI();
            if (p.totalDuration > 0) p.timeTotal.innerText = p.utils.formatTime(p.totalDuration);

            const displayOffset = p.streamStartOffset || 0;
            if (displayOffset > 0) {
                p.timeCurrent.innerText = p.utils.formatTime(displayOffset);
                const pct = (displayOffset / p.totalDuration) * 100;
                p.progressBar.style.width = Math.min(100, pct) + '%';
            }
        }

        updateVolumeUI() {
            const p = this.player;
            const icon = p.muteBtn.querySelector('i');
            if (p.video.muted || p.state.volume === 0) icon.className = 'pi pi-volume-off';
            else icon.className = p.state.volume < 0.5 ? 'pi pi-volume-down' : 'pi pi-volume-up';
            p.volSlider.value = p.state.volume;
        }

        updateMarkers() {
            const p = this.player;
            let dur = p.totalDuration;
            if (!dur || dur === Infinity) {
                dur = p.video.duration;
            }
            if (!dur || dur === Infinity) return;
            const showMarker = (m, start, end) => {
                const el = p.container.querySelector(m);
                if (el && start > 0 && end > 0) {
                    el.style.left = (start / dur * 100) + '%';
                    el.style.width = ((end - start) / dur * 100) + '%';
                    el.style.display = 'block';
                }
            };
            showMarker('.progress-intro-marker', p.markers.introStart, p.markers.introEnd);
            showMarker('.progress-outro-marker', p.markers.outroStart, dur);
        }

        checkMarkers() {
            const p = this.player;
            const t = p.video.currentTime + (p.streamStartOffset || 0);
            const show = (id, visible) => {
                const el = document.getElementById(id);
                if (el) el.style.display = visible ? 'flex' : 'none';
            };
            show('skipIntroBtn', t >= p.markers.introStart && t < p.markers.introEnd);
            show('skipRecapBtn', t >= p.markers.recapStart && t < p.markers.recapEnd);
            show('skipOutroBtn', t >= p.markers.outroStart && p.markers.outroStart > 0);
        }

        updatePageTitle() {
            const p = this.player;
            const title = p.container.dataset.title || 'JMedia';
            const pageTitleEl = document.getElementById('pageTitle');
            if (pageTitleEl) {
                pageTitleEl.textContent = title;
                pageTitleEl.title = title;
            }
            document.title = title;
        }

        updateSubtitle() {
            const p = this.player;
            const subtitleEl = p.container.querySelector('#videoSubtitle');
            if (!subtitleEl) return;
            if (p.videoType === 'episode' || p.videoType === 'Episode') {
                const series = p.container.dataset.seriesTitle || '';
                const season = p.container.dataset.seasonNumber || '';
                const episode = p.container.dataset.episodeNumber || '';
                subtitleEl.textContent = `${series} • S${season}E${episode}`;
            }
        }

        switchSettingsPage(page) {
            const p = this.player;
            p.subtitleMenu.querySelectorAll('.settings-page').forEach(pg => pg.classList.remove('active'));
            const target = p.subtitleMenu.querySelector(`.settings-page[data-page="${page}"]`);
            if (target) target.classList.add('active');
        }

        toggleDebugDialog() {
            const p = this.player;
            if (p.debugDialog) {
                p._updateDebugDialog();
                const isHidden = p.debugDialog.style.display === 'none' || !p.debugDialog.style.display;
                p.debugDialog.style.display = isHidden ? 'flex' : 'none';
            }
        }

        closeDebugDialog() {
            const p = this.player;
            if (p.debugDialog) {
                p.debugDialog.style.display = 'none';
            }
        }

        _updateDebugDialog() {
            const p = this.player;
            if (p.dialogSeriesInput) p.dialogSeriesInput.value = p.debugInfo.seriesTitle;
            if (p.dialogSeasonInput) p.dialogSeasonInput.value = p.debugInfo.seasonNumber;
            if (p.dialogEpisodeInput) p.dialogEpisodeInput.value = p.debugInfo.episodeNumber;
            if (p.dialogImdbInput) p.dialogImdbInput.value = p.debugInfo.seriesImdbId;

            const updateElement = (id, value) => {
                const el = document.getElementById(id);
                if (el) el.textContent = value;
            };

            updateElement('dialog-intro-start', `${p.markers.introStart} (${p.markerSources.introStart || 'UNKNOWN'})`);
            updateElement('dialog-intro-end', `${p.markers.introEnd} (${p.markerSources.introEnd || 'UNKNOWN'})`);
            updateElement('dialog-outro-start', `${p.markers.outroStart} (${p.markerSources.outroStart || 'UNKNOWN'})`);
            updateElement('dialog-outro-end', `${p.markers.outroEnd} (${p.markerSources.outroEnd || 'UNKNOWN'})`);
            updateElement('dialog-recap-start', `${p.markers.recapStart} (${p.markerSources.recapStart || 'UNKNOWN'})`);
            updateElement('dialog-recap-end', `${p.markers.recapEnd} (${p.markerSources.recapEnd || 'UNKNOWN'})`);

            updateElement('dialog-original-intro-start', `${p.originalMarkers?.introStart ?? 0}`);
            updateElement('dialog-original-intro-end', `${p.originalMarkers?.introEnd ?? 0}`);
            updateElement('dialog-original-outro-start', `${p.originalMarkers?.outroStart ?? 0}`);
            updateElement('dialog-original-outro-end', `${p.originalMarkers?.outroEnd ?? 0}`);
            updateElement('dialog-original-recap-start', `${p.originalMarkers?.recapStart ?? 0}`);
            updateElement('dialog-original-recap-end', `${p.originalMarkers?.recapEnd ?? 0}`);

            updateElement('dialog-refresh-status', p.refreshStatus || 'No refresh attempted');
            updateElement('dialog-refresh-error', p.refreshError || 'None');
        }
    };
})(window);
