(function(window) {
    'use strict';

    window.PlayerSkipController = class {
        constructor(player) {
            this.player = player;
        }

        checkAutoSkip(t) {
            const p = this.player;
            if (p._isUndoing) return;

            if (p.autoSkipIntro && t >= p.markers.introStart && t < p.markers.introEnd) {
                this._performAutoSkip('intro', p.markers.introStart, p.markers.introEnd);
                return;
            }
            if (p.autoSkipRecap && t >= p.markers.recapStart && t < p.markers.recapEnd) {
                this._performAutoSkip('recap', p.markers.recapStart, p.markers.recapEnd);
                return;
            }
            if (p.autoSkipOutro && t >= p.markers.outroStart && p.markers.outroStart > 0) {
                this._performAutoSkip('outro', p.markers.outroStart, p.markers.outroEnd);
                return;
            }
        }

        _performAutoSkip(section, start, end) {
            const p = this.player;
            p._autoSkipUndoTime = start;
            p._autoSkipSection = section;

            if (section === 'outro') {
                p.playNextEpisode();
                return;
            }

            if (p.needsTranscode) {
                p.streamMgr.performServerSeek(end);
            } else {
                p.video.currentTime = end;
            }

            this._showAutoSkipNotice(section);
        }

        _showAutoSkipNotice(section) {
            const p = this.player;
            if (!p.autoSkipNotice || !p.autoSkipNoticeText) return;

            const labels = { intro: 'Intro skipped', recap: 'Recap skipped', outro: 'Outro skipped' };
            p.autoSkipNoticeText.textContent = labels[section] || 'Section skipped';
            p.autoSkipNotice.style.display = 'flex';

            if (p._autoSkipTimer) {
                clearTimeout(p._autoSkipTimer);
            }
            p._autoSkipTimer = setTimeout(() => {
                if (p.autoSkipNotice) {
                    p.autoSkipNotice.style.display = 'none';
                }
            }, 5000);
        }

        _undoAutoSkip() {
            const p = this.player;
            if (p._autoSkipUndoTime > 0) {
                p._isUndoing = true;
                if (p.needsTranscode) {
                    p.streamMgr.performServerSeek(p._autoSkipUndoTime);
                } else {
                    p.video.currentTime = p._autoSkipUndoTime;
                }
                if (p.autoSkipNotice) {
                    p.autoSkipNotice.style.display = 'none';
                }
                if (p._autoSkipTimer) {
                    clearTimeout(p._autoSkipTimer);
                    p._autoSkipTimer = null;
                }
                setTimeout(() => { p._isUndoing = false; }, 1000);
            }
        }

        _disableAutoSkip(section) {
            const p = this.player;
            p['autoSkip' + section.charAt(0).toUpperCase() + section.slice(1)] = false;
            p.stateMgr._postAutoSkipSetting(section, false);
            if (p.autoSkipNotice) {
                p.autoSkipNotice.style.display = 'none';
            }
            if (p._autoSkipTimer) {
                clearTimeout(p._autoSkipTimer);
                p._autoSkipTimer = null;
            }
            const toggle = p.container.querySelector(`.skip-auto-toggle[data-section="${section}"]`);
            if (toggle) {
                toggle.classList.remove('active');
                toggle.querySelector('input[type="checkbox"]').checked = false;
            }
        }

        handleSkipClick(e, section) {
            const p = this.player;
            if (e.target.closest('.skip-auto-toggle')) return;
            if (section === 'outro') {
                p.playNextEpisode();
            } else {
                const endKey = section + 'End';
                const end = p.markers[endKey];
                if (p.needsTranscode) {
                    p.streamMgr.performServerSeek(end);
                } else {
                    p.video.currentTime = end;
                }
            }
        }
    };
})(window);
