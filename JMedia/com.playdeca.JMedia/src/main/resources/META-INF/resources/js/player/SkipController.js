(function(window) {
    'use strict';

    window.PlayerSkipController = class {
        constructor(player) {
            this.player = player;
        }

        checkAutoSkip(t) {
            const p = this.player;
            if (p._isUndoing) return;

            // Skip sections the user manually undid — they want to watch them
            if (p._manuallyUndoneSections) {
                if (p._manuallyUndoneSections.has('intro') && t >= p.markers.introStart && t < p.markers.introEnd) return;
                if (p._manuallyUndoneSections.has('recap') && t >= p.markers.recapStart && t < p.markers.recapEnd) return;
                if (p._manuallyUndoneSections.has('outro') && t >= p.markers.outroStart && p.markers.outroStart > 0) return;
            }

            // A section is auto-skipped ONCE per playback session. After a skip the
            // rebuild can leave the clock at the segment/keyframe origin, which is
            // still inside [start, end); re-checking on every timeupdate re-fired the
            // same skip forever (the "frozen right after the intro" symptom). Only an
            // explicit rewind before the section re-arms it.
            const skipped = p._autoSkippedSections;
            if (skipped && skipped.size) {
                const rewindMargin = 5;
                if (p.markers.introStart > 0 && t < p.markers.introStart - rewindMargin) skipped.delete('intro');
                if (p.markers.recapStart > 0 && t < p.markers.recapStart - rewindMargin) skipped.delete('recap');
                if (p.markers.outroStart > 0 && t < p.markers.outroStart - rewindMargin) skipped.delete('outro');
            }
            const alreadySkipped = (section) => !!(skipped && skipped.has(section));

            // A transcode seek lands on the keyframe BEFORE the requested time, so
            // right after skipping the intro the clock can still read < introEnd.
            // Without this guard the same section re-triggers forever:
            // seek(237) -> origin 236.2 -> "still in intro" -> seek(237) -> ...
            const inSkipGuard = (section, end) => {
                const g = p._autoSkipGuard;
                return !!(g && g.section === section && Date.now() < g.until && t < end + 2);
            };

            if (p.autoSkipIntro && !alreadySkipped('intro') && !inSkipGuard('intro', p.markers.introEnd) && t >= p.markers.introStart && t < p.markers.introEnd) {
                this._performAutoSkip('intro', p.markers.introStart, p.markers.introEnd);
                return;
            }
            if (p.autoSkipRecap && !alreadySkipped('recap') && !inSkipGuard('recap', p.markers.recapEnd) && t >= p.markers.recapStart && t < p.markers.recapEnd) {
                this._performAutoSkip('recap', p.markers.recapStart, p.markers.recapEnd);
                return;
            }
            if (p.autoSkipOutro && !alreadySkipped('outro') && !inSkipGuard('outro', p.markers.outroEnd) && t >= p.markers.outroStart && p.markers.outroStart > 0) {
                this._performAutoSkip('outro', p.markers.outroStart, p.markers.outroEnd);
                return;
            }
        }

        _performAutoSkip(section, start, end) {
            const p = this.player;
            // checkAutoSkip() runs on every timeupdate, so the guard has to be in
            // place before any path returns - including outro, which used to return
            // before it was set and therefore called playNextEpisode() on every
            // timeupdate ("No next episode" toast spam).
            if (p._autoSkipGuard && p._autoSkipGuard.section === section
                    && Date.now() < p._autoSkipGuard.until) {
                return;
            }
            if (!p._autoSkippedSections) p._autoSkippedSections = new Set();
            p._autoSkippedSections.add(section);
            p._autoSkipUndoTime = start;
            p._autoSkipSection = section;

            if (section === 'outro') {
                // One attempt per playback session: there may be no next episode,
                // and every failed attempt toasts.
                p._autoSkipGuard = { section: 'outro', end: end, until: Date.now() + 6 * 60 * 60 * 1000 };
                this._skipOutro(end);
                return;
            }

            // Land a little past the marker end: keyframe-aligned transcode seeks
            // can start before the requested time, and sitting in [start, end)
            // after a skip is exactly what caused the skip loop.
            const target = end + 1;
            p._autoSkipGuard = { section: section, end: target, until: Date.now() + 10000 };

            if (p.needsTranscode) {
                p.streamMgr.performServerSeek(target);
            } else {
                p.video.currentTime = target;
            }

            this._showAutoSkipNotice(section);
        }

        /**
         * Ending a section is not always "episode over". An ED/credits block often has a
         * preview or a post-credits scene after it — these episodes carry ~80s between
         * outroEnd and the real end — so skipping the credits must not throw that away.
         * When real tail content remains we seek past the outro and let the episode end
         * itself; only when the markers already reach the end do we advance.
         */
        _skipOutro(end) {
            const p = this.player;
            const minTailSeconds = 20; // shorter than this is a fade/logo, not a scene

            let mediaEnd = 0;
            const elementDuration = p.video ? p.video.duration : 0;
            if (isFinite(elementDuration) && elementDuration > 0) {
                mediaEnd = (p.streamStartOffset || 0) + elementDuration;
            }
            if (!(mediaEnd > 0)) {
                mediaEnd = p.totalDuration || 0;
            }
            const tail = mediaEnd > 0 ? mediaEnd - end : 0;

            if (tail >= minTailSeconds) {
                const target = end + 1;
                console.log('[SimplePlayer] Outro skipped, ' + Math.round(tail)
                        + 's of tail remains — resuming after the credits');
                if (p.needsTranscode) {
                    p.streamMgr.performServerSeek(target);
                } else {
                    p.video.currentTime = target;
                }
                return;
            }
            p.playNextEpisode();
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

                // Track this section as manually undone — prevents re-auto-skip
                if (p._autoSkipSection) {
                    if (!p._manuallyUndoneSections) p._manuallyUndoneSections = new Set();
                    p._manuallyUndoneSections.add(p._autoSkipSection);
                }

                // Change the notice text to guide the user, keep it visible so Auto toggle stays accessible
                if (p.autoSkipNoticeText && p._autoSkipSection) {
                    const labels = { intro: 'Watching intro — disable Auto to skip next time', recap: 'Watching recap', outro: 'Watching outro' };
                    p.autoSkipNoticeText.textContent = labels[p._autoSkipSection] || 'Auto-skip disabled for this section';
                }
                if (p.autoSkipNotice) {
                    p.autoSkipNotice.style.display = 'flex';
                }
                if (p._autoSkipTimer) {
                    clearTimeout(p._autoSkipTimer);
                    p._autoSkipTimer = null;
                }
                // Extend the timer to dismiss the guidance notice after 8 seconds
                p._autoSkipTimer = setTimeout(() => {
                    if (p.autoSkipNotice) {
                        p.autoSkipNotice.style.display = 'none';
                    }
                }, 8000);
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
                // A manual skip must not be re-triggered by the auto-skip check.
                if (!p._autoSkippedSections) p._autoSkippedSections = new Set();
                p._autoSkippedSections.add('outro');
                p._autoSkipUndoTime = p.markers.outroStart;
                p._autoSkipSection = 'outro';
                this._skipOutro(p.markers.outroEnd);
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
