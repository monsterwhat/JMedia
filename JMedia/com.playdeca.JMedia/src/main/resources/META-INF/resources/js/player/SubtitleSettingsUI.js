(function(window) {
    'use strict';

    function warnSubtitleApiOnce() {
        if (!window.__subtitleApiMissingWarned) {
            window.__subtitleApiMissingWarned = true;
            console.warn('[SubtitleApi] window.SubtitleApi not available; falling back to inline fetch');
        }
    }

    window.PlayerSubtitleSettingsUI = class {
        constructor(player) {
            this.player = player;

            window.initializeSubtitleSettings = () => this.init();
            window.saveSubtitleSettings = () => this.save();
            window.toggleSubtitleSettings = () => this.toggle();
            window.adjustSubtitleCorrection = (delta) => this.adjustCorrection(delta);
            window.resetSubtitleCorrection = () => this.resetCorrection();
            window.setSubtitleColor = (color) => this.setColor(color);
            window.updatePreviewStyle = () => this._updatePreview();
            window.changeSubtitleTrack = (trackId) => this.changeTrack(trackId);
            window.resetSubtitleStyles = () => this.resetStyles();

            // Populate the panel for THIS player as soon as it exists. DOMContentLoaded
            // only fires on the initial document load, so every player opened later
            // (cinema modal, SPA playback switch) used to keep the fragment's default
            // slider values instead of the saved style.
            const start = () => {
                if (!window.subtitleManager) return;
                try {
                    this.init();
                } catch (e) {
                    console.warn('[SubtitleSettings] init failed', e);
                }
            };
            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', start, { once: true });
            } else {
                start();
            }
        }

        init() {
            const mgr = window.subtitleManager;
            if (!mgr) return;
            // Not every player fragment ships the settings panel (live channels do
            // not); bail out instead of throwing on a missing element.
            if (!document.getElementById('subtitleSize')) return;

            const style = mgr.getStyle();

            document.getElementById('subtitleSize').value = style.size;
            document.getElementById('subtitleSizeValue').textContent = style.size + 'px';
            document.getElementById('subtitleColor').value = style.color;
            document.getElementById('subtitleBgOpacity').value = style.bgOpacity;
            document.getElementById('subtitleBgOpacityValue').textContent = style.bgOpacity;
            document.getElementById('subtitleBottom').value = style.bottom;
            document.getElementById('subtitleBottomValue').textContent = style.bottom + 'px';

            this._updateCorrectionUI(style.correction);

            this._updatePreview();
            this._updateTrackOptions();
        }

        _updateCorrectionUI(val) {
            const el = document.getElementById('subtitleCorrectionVal');
            if (el) el.textContent = (val > 0 ? '+' : '') + val.toFixed(1) + 's';
        }

        adjustCorrection(delta) {
            let current = parseFloat(localStorage.getItem('jmedia_subtitle_correction') || 0);
            current += delta;
            localStorage.setItem('jmedia_subtitle_correction', current);
            this._updateCorrectionUI(current);

            const p = this.player || window.currentPlayerInstance;
            if (p && p.loadSubtitles) {
                p.loadSubtitles();
            }
        }

        resetCorrection() {
            localStorage.setItem('jmedia_subtitle_correction', 0);
            this._updateCorrectionUI(0);

            const p = this.player || window.currentPlayerInstance;
            if (p && p.loadSubtitles) {
                p.loadSubtitles();
            }
        }

        setColor(color) {
            const el = document.getElementById('subtitleColor');
            if (!el) { console.warn('[SubtitleSettings] #subtitleColor not found'); return; }
            el.value = color;
            this._updatePreview();
        }

        _updatePreview() {
            const sizeEl = document.getElementById('subtitleSize');
            const colorEl = document.getElementById('subtitleColor');
            const opacityEl = document.getElementById('subtitleBgOpacity');
            const bottomEl = document.getElementById('subtitleBottom');
            if (!sizeEl || !colorEl || !opacityEl || !bottomEl) {
                console.warn('[SubtitleSettings] preview controls not found');
                return;
            }
            const size = sizeEl.value;
            const color = colorEl.value;
            const opacity = opacityEl.value;
            const bottom = bottomEl.value;

            const sizeValEl = document.getElementById('subtitleSizeValue');
            const opacityValEl = document.getElementById('subtitleBgOpacityValue');
            const bottomValEl = document.getElementById('subtitleBottomValue');
            if (sizeValEl) sizeValEl.textContent = size + 'px';
            if (opacityValEl) opacityValEl.textContent = opacity;
            if (bottomValEl) bottomValEl.textContent = bottom + 'px';

            const preview = document.getElementById('subSettingsPreviewText');
            if (preview) {
                preview.style.fontSize = (size * 0.8) + 'px';
                preview.style.color = color;
                preview.style.backgroundColor = 'rgba(0,0,0,' + opacity + ')';
            }

            if (window.subtitleManager) {
                window.subtitleManager.applyGlobalStyle({
                    font: "'Segoe UI', sans-serif",
                    size: parseInt(size),
                    color: color,
                    bgOpacity: parseFloat(opacity),
                    lineHeight: 1.4,
                    bottom: parseInt(bottom)
                });
            }
        }

        async _updateTrackOptions() {
            const p = this.player || window.currentPlayerInstance;
            const videoId = p?.videoId;
            if (!videoId) return;

            const select = document.getElementById('subtitleTrack');
            if (!select) {
                console.warn('[SubtitleSettings] #subtitleTrack is not in the DOM; cannot refresh track options');
                return;
            }

            try {
                let tracks;
                if (window.SubtitleApi) {
                    tracks = await window.SubtitleApi.getTracks(videoId);
                } else {
                    warnSubtitleApiOnce();
                    const res = await fetch('/api/video/subtitles/' + videoId);
                    // The options list is shared UI: a response for the previous episode must
                    // not repopulate it while a different one is playing.
                    if (!this.player || this.player._destroyed || String(this.player.videoId) !== String(videoId)) {
                        console.warn('[SubtitleSettings] stale player; discarding track options');
                        return;
                    }
                    if (!res.ok) {
                        console.warn('[SubtitleSettings] Track list request failed with HTTP ' + res.status);
                        return;
                    }
                    const data = await res.json();
                    tracks = data.tracks || data.data || [];
                }
                // The options list is shared UI: a response for the previous episode must
                // not repopulate it while a different one is playing.
                if (!this.player || this.player._destroyed || String(this.player.videoId) !== String(videoId)) {
                    console.warn('[SubtitleSettings] stale player; discarding track options');
                    return;
                }

                select.innerHTML = '<option value="off">Off</option>';

                tracks.forEach(t => {
                    const opt = document.createElement('option');
                    opt.value = t.id;
                    opt.textContent = (t.displayName || t.filename) + ' (' + (t.isEmbedded ? 'Embedded' : 'External') + ')';
                    select.appendChild(opt);
                });

                const lastTrackId = localStorage.getItem('jmedia_last_track_' + videoId);
                if (lastTrackId) select.value = lastTrackId;
            } catch (e) {
                console.error('[SubtitleSettings] Failed to load subtitle tracks for the settings panel:', e);
            }
        }

        changeTrack(trackId) {
            // Prefer a player whose container is still in the document. A stale
            // instance from a previous modal would silently click options in a
            // detached DOM tree, which looks like "selecting does nothing".
            const live = (this.player && this.player.container && document.body.contains(this.player.container))
                ? this.player
                : window.currentPlayerInstance;
            if (!live) {
                console.warn('[SubtitleSettings] No live player to apply subtitle track ' + trackId);
                return;
            }

            const id = String(trackId);
            const findOption = () => (live.subtitleList
                ? live.subtitleList.querySelector('.subtitle-option[data-id="' + id + '"]')
                : null);

            const opt = findOption();
            if (opt) {
                opt.click();
                return;
            }

            // The settings panel can be filled before the stream opens, so its ids may
            // predate the player's track list. Refresh once and retry before giving up.
            const refresh = (typeof live.loadSubtitles === 'function') ? live.loadSubtitles() : Promise.resolve();
            Promise.resolve(refresh).then(() => {
                const retry = findOption();
                if (retry) {
                    retry.click();
                } else if (id === 'off' && typeof live.turnOffSubtitles === 'function') {
                    live.turnOffSubtitles();
                } else {
                    console.warn('[SubtitleSettings] Track ' + id + ' is not in the player track list after refresh');
                }
            }).catch(e => {
                console.error('[SubtitleSettings] Failed to apply subtitle track ' + id, e);
            });
        }

        resetStyles() {
            if (window.subtitleManager) {
                const defaults = {
                    font: "'Segoe UI', sans-serif",
                    size: 20,
                    color: '#ffffff',
                    bgOpacity: 0.7,
                    lineHeight: 1.4,
                    bottom: 60
                };

                const sizeEl = document.getElementById('subtitleSize');
                const colorEl = document.getElementById('subtitleColor');
                const opacityEl = document.getElementById('subtitleBgOpacity');
                const bottomEl = document.getElementById('subtitleBottom');
                if (!sizeEl || !colorEl || !opacityEl || !bottomEl) {
                    console.warn('[SubtitleSettings] reset controls not found');
                    return;
                }
                sizeEl.value = defaults.size;
                colorEl.value = defaults.color;
                opacityEl.value = defaults.bgOpacity;
                bottomEl.value = defaults.bottom;

                this._updatePreview();
            }
        }

        save() {
            if (window.subtitleManager) {
                const sizeEl = document.getElementById('subtitleSize');
                const colorEl = document.getElementById('subtitleColor');
                const opacityEl = document.getElementById('subtitleBgOpacity');
                const bottomEl = document.getElementById('subtitleBottom');
                if (!sizeEl || !colorEl || !opacityEl || !bottomEl) {
                    console.warn('[SubtitleSettings] save controls not found');
                    return;
                }
                window.subtitleManager.saveStyle({
                    font: "'Segoe UI', sans-serif",
                    size: parseInt(sizeEl.value),
                    color: colorEl.value,
                    bgOpacity: parseFloat(opacityEl.value),
                    lineHeight: 1.4,
                    bottom: parseInt(bottomEl.value)
                });
            }
            if (typeof window.reapplyAssSubtitleStyles === 'function') {
                window.reapplyAssSubtitleStyles();
            }
            this.toggle();
        }

        toggle() {
            const overlay = document.getElementById('subtitleSettingsOverlay');
            if (overlay) {
                const isVisible = overlay.style.display !== 'none';
                overlay.style.display = isVisible ? 'none' : 'flex';
                if (!isVisible) this.init();
            }
        }
    };
})(window);
