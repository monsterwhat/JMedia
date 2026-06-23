(function(window) {
    'use strict';

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

            document.addEventListener('DOMContentLoaded', () => {
                if (window.subtitleManager) this.init();
            });
        }

        init() {
            const mgr = window.subtitleManager;
            if (!mgr) return;

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
            document.getElementById('subtitleColor').value = color;
            this._updatePreview();
        }

        _updatePreview() {
            const size = document.getElementById('subtitleSize').value;
            const color = document.getElementById('subtitleColor').value;
            const opacity = document.getElementById('subtitleBgOpacity').value;
            const bottom = document.getElementById('subtitleBottom').value;

            document.getElementById('subtitleSizeValue').textContent = size + 'px';
            document.getElementById('subtitleBgOpacityValue').textContent = opacity;
            document.getElementById('subtitleBottomValue').textContent = bottom + 'px';

            const preview = document.getElementById('subSettingsPreviewText');
            preview.style.fontSize = (size * 0.8) + 'px';
            preview.style.color = color;
            preview.style.backgroundColor = 'rgba(0,0,0,' + opacity + ')';

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

            try {
                const res = await fetch('/api/video/subtitles/' + videoId);
                if (res.ok) {
                    const data = await res.json();
                    const select = document.getElementById('subtitleTrack');
                    const tracks = data.tracks || [];

                    select.innerHTML = '<option value="off">Off</option>';

                    tracks.forEach(t => {
                        const opt = document.createElement('option');
                        opt.value = t.id;
                        opt.textContent = (t.displayName || t.filename) + ' (' + (t.isEmbedded ? 'Embedded' : 'External') + ')';
                        select.appendChild(opt);
                    });

                    const lastTrackId = localStorage.getItem('jmedia_last_track_' + videoId);
                    if (lastTrackId) select.value = lastTrackId;
                }
            } catch (e) {}
        }

        changeTrack(trackId) {
            const p = this.player || window.currentPlayerInstance;
            if (p) {
                const list = p.subtitleList;
                const opt = list?.querySelector('.subtitle-option[data-id="' + trackId + '"]');
                if (opt) opt.click();
                else if (trackId === 'off') p.turnOffSubtitles();
            }
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

                document.getElementById('subtitleSize').value = defaults.size;
                document.getElementById('subtitleColor').value = defaults.color;
                document.getElementById('subtitleBgOpacity').value = defaults.bgOpacity;
                document.getElementById('subtitleBottom').value = defaults.bottom;

                this._updatePreview();
            }
        }

        save() {
            if (window.subtitleManager) {
                window.subtitleManager.saveStyle({
                    font: "'Segoe UI', sans-serif",
                    size: parseInt(document.getElementById('subtitleSize').value),
                    color: document.getElementById('subtitleColor').value,
                    bgOpacity: parseFloat(document.getElementById('subtitleBgOpacity').value),
                    lineHeight: 1.4,
                    bottom: parseInt(document.getElementById('subtitleBottom').value)
                });
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
