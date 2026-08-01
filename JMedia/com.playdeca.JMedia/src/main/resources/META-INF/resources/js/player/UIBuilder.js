(function(window) {
    'use strict';

    window.PlayerUIBuilder = class {
        constructor(player) {
            this.player = player;
        }

        build() {
            const p = this.player;
            const uiHTML = `
                <div class="video-click-overlay" id="clickOverlay"></div>
                <div class="tap-to-play-overlay" id="tapToPlayOverlay">
                    <div class="tap-to-play-icon"><i class="pi pi-play" style="font-size:2.5rem;"></i></div>
                    <div class="tap-to-play-text">Tap to Play</div>
                </div>
                <div class="big-play-btn"><img src="/logo.png" alt="Play"></div>
                <div class="buffering-overlay"><i class="pi pi-spin pi-spinner" style="font-size: 3rem; color: #48c774;"></i></div>

                <div class="player-logo-overlay" style="position:absolute;top:15px;left:15px;z-index:100;display:flex;align-items:center;gap:12px;pointer-events:none;">
                    <img class="series-logo" src="${p.container.dataset.logoPath || ''}" alt=""
                         onload="this.style.display='block';var c=this.closest('.player-container');var m=c&&c.querySelector('.media-info');if(m)m.style.display='none';var f=this.parentElement.querySelector('.series-title-fallback');if(f)f.style.display='none';"
                         onerror="var o=this.parentElement;if(o)o.style.display='none';"
                         style="display:none;max-height:40px;width:auto;pointer-events:none;">
                    <span class="series-title-fallback" style="display:inline;color:#fff;font-weight:600;font-size:1rem;pointer-events:none;">${p.container.dataset.title || 'Video'}</span>
                </div>

                <div class="skip-recap-container" id="skipRecapBtn" style="display: none;">
                    <button class="button is-info is-rounded"><i class="pi pi-history mr-2"></i> Skip Recap</button>
                    <label class="skip-auto-toggle ${p.autoSkipRecap ? 'active' : ''}" data-section="recap">
                        <input type="checkbox" ${p.autoSkipRecap ? 'checked' : ''}> Auto
                    </label>
                </div>
                <div class="skip-intro-container" id="skipIntroBtn" style="display: none;">
                    <button class="button is-info is-rounded"><i class="pi pi-fast-forward mr-2"></i> Skip Intro</button>
                    <label class="skip-auto-toggle ${p.autoSkipIntro ? 'active' : ''}" data-section="intro">
                        <input type="checkbox" ${p.autoSkipIntro ? 'checked' : ''}> Auto
                    </label>
                </div>
                <div class="skip-outro-container" id="skipOutroBtn" style="display: none;">
                    <button class="button is-info is-rounded"><i class="pi pi-step-forward mr-2"></i> Skip Outro</button>
                    <label class="skip-auto-toggle ${p.autoSkipOutro ? 'active' : ''}" data-section="outro">
                        <input type="checkbox" ${p.autoSkipOutro ? 'checked' : ''}> Auto
                    </label>
                 </div>

                <div class="auto-skip-notice" id="autoSkipNotice" style="display: none;">
                    <span id="autoSkipNoticeText">Intro skipped</span>
                    <button class="button is-small is-light" id="autoSkipUndoBtn"><i class="pi pi-undo mr-1"></i>Undo</button>
                    <button class="button is-small is-light" id="autoSkipToggleBtn"><i class="pi pi-times mr-1"></i>Auto</button>
                </div>

                <div class="media-info">
                    <div class="info-text">
                        <div class="info-title" id="videoTitleLink">${p.container.dataset.title || 'Video'}</div>
                        <div class="info-subtitle" id="videoSubtitle"></div>
                    </div>
                </div>

                <div class="controls-container">
                    <div class="preview-container" id="scrollPreview">
                        <div class="storyboard-img" id="storyboardImg"></div>
                        <div class="preview-time" id="previewTime">0:00</div>
                    </div>
                    <div class="progress-container">
                        <div class="progress-filled" style="width: 0%;"></div>
                        <div class="progress-intro-marker" style="display: none;"></div>
                        <div class="progress-outro-marker" style="display: none;"></div>
                    </div>
                    <div class="controls-row">
                        <button class="control-btn" id="videoPrevBtn"><i class="pi pi-step-backward"></i></button>
                        <button class="control-btn" id="videoPlayPauseBtn"><i class="pi pi-play"></i></button>
                        <button class="control-btn" id="videoNextBtn"><i class="pi pi-step-forward"></i></button>

                        <div class="skip-buttons-row">
                            <button class="control-btn skip-btn" data-skip="-30"><i class="pi pi-angle-double-left"></i><span class="skip-val">30</span></button>
                            <button class="control-btn skip-btn" data-skip="-15"><i class="pi pi-angle-left"></i><span class="skip-val">15</span></button>
                            <button class="control-btn skip-btn" data-skip="15"><i class="pi pi-angle-right"></i><span class="skip-val">15</span></button>
                            <button class="control-btn skip-btn" data-skip="30"><i class="pi pi-angle-double-right"></i><span class="skip-val">30</span></button>
                        </div>

                        <div class="time-display"><span id="videoCurrentTime">0:00</span> / <span id="videoTotalTime">0:00</span></div>
                        <div class="spacer"></div>

                        <div id="audioSelectorPlaceholder"></div>

                        <div class="volume-container">
                            <button class="control-btn" id="videoMuteBtn"><i class="pi pi-volume-up"></i></button>
                            <input type="range" class="volume-slider" min="0" max="1" step="0.01" value="${p.state.volume}">
                        </div>

                        <button class="control-btn" id="videoSpeedBtn"><span id="speedValue">1.0x</span></button>
                        <button class="control-btn" id="videoSubtitleBtn"><i class="pi pi-cog"></i></button>
                        <button class="control-btn" id="videoFullscreenBtn"><i class="pi pi-expand"></i></button>
                    </div>
                </div>

                <div class="subtitle-menu settings-menu" id="subtitleMenu">
                    <div class="settings-page active" data-page="main">
                        <div class="menu-header">Settings</div>
                        <div class="settings-item" data-page="subtitles">
                            <span>Subtitles</span>
                            <i class="pi pi-chevron-right"></i>
                        </div>
                        <div class="settings-item" data-page="offset">
                            <span>Timing Offset</span>
                            <i class="pi pi-chevron-right"></i>
                        </div>
                        <div class="settings-item" data-page="style">
                            <span>Style</span>
                            <i class="pi pi-chevron-right"></i>
                        </div>
                        <div class="settings-item" data-page="quality">
                            <span>Video Quality</span>
                            <i class="pi pi-chevron-right"></i>
                        </div>
                        <div class="settings-item" data-page="player">
                            <span>Video Player</span>
                            <i class="pi pi-chevron-right"></i>
                        </div>
                    </div>

                    <div class="settings-page" data-page="subtitles">
                        <div class="settings-back"><i class="pi pi-chevron-left"></i> Subtitles</div>
                        <div class="subtitle-list" id="subtitleList">
                            <div class="subtitle-option" id="sub-off" data-id="off">Off</div>
                        </div>
                    </div>

                    <div class="settings-page" data-page="offset">
                        <div class="settings-back"><i class="pi pi-chevron-left"></i> Timing Offset</div>
                        <div class="subtitle-correction-row">
                            <button class="correction-btn" id="subMinusBtn">-0.2s</button>
                            <div class="correction-val" id="subCorrectionVal" title="Click to edit manually">0.0s</div>
                            <button class="correction-btn" id="subPlusBtn">+0.2s</button>
                            <button class="correction-btn correction-reset" id="subResetBtn" title="Reset to 0">↺</button>
                        </div>
                    </div>

                    <div class="settings-page" data-page="style">
                        <div class="settings-back"><i class="pi pi-chevron-left"></i> Style</div>
                        <div class="subtitle-option" id="manageSubtitlesBtn"><i class="pi pi-palette"></i> Subtitle Settings</div>
                    </div>

                    <div class="settings-page" data-page="quality">
                        <div class="settings-back"><i class="pi pi-chevron-left"></i> Video Quality</div>
                        <div class="quality-options" id="qualityOptions">
                            <button class="quality-btn" data-quality="720">720p</button>
                            <button class="quality-btn" data-quality="1080">1080p</button>
                            <button class="quality-btn" data-quality="2160">4K</button>
                            <button class="quality-btn" data-quality="480">480p</button>
                            <button class="quality-btn" data-quality="0">Source</button>
                        </div>
                    </div>
                    <div class="settings-page" data-page="player">
                        <div class="settings-back"><i class="pi pi-chevron-left"></i> Video Player</div>
                        <div class="quality-options" id="playerOptions">
                            <button class="quality-btn player-option" data-player="simple" style="border-color:#48c774;color:#48c774;">JMedia Player</button>
                            <button class="quality-btn player-option" data-player="videojs">Video.js</button>
                            <button class="quality-btn player-option" data-player="oplayer">OPlayer</button>
                        </div>
                    </div>
                </div>

                <div class="speed-menu" id="speedMenu">
                    <div class="menu-header">Speed</div>
                    <div class="speed-list">
                        <div class="speed-option active" data-speed="1.0">1.0x</div>
                        <div class="speed-option" data-speed="1.25">1.25x</div>
                        <div class="speed-option" data-speed="1.5">1.5x</div>
                        <div class="speed-option" data-speed="2.0">2.0x</div>
                    </div>
                 </div>

                  <div class="debug-dialog" id="debugDialog" style="display: none;">
                      <div class="debug-dialog-content">
                          <div class="debug-dialog-header">
                              <h3>Debug Controls</h3>
                              <button class="debug-dialog-close" id="debugDialogClose">&times;</button>
                          </div>
                          <div class="debug-dialog-body">
                           <div class="debug-info">
                                    <div><strong>Show:</strong> <input type="text" id="dialog-series-input" class="debug-input" /></div>
                                    <div><strong>Season:</strong> <input type="number" id="dialog-season-input" class="debug-input debug-input-sm" /></div>
                                    <div><strong>Episode:</strong> <input type="number" id="dialog-episode-input" class="debug-input debug-input-sm" /></div>
                                    <div><strong>IMDb ID:</strong> <input type="text" id="dialog-imdb-input" class="debug-input" /></div>
                                    <div><button class="debug-save-btn" id="debugSaveBtn">Save Overrides &amp; Reload</button></div>
                                </div>
                              <div class="debug-marker-info">
                                  <h4>Marker Values:</h4>
                                  <div><strong>Intro Start:</strong> <span id="dialog-intro-start">0 (UNKNOWN)</span></div>
                                  <div><strong>Intro End:</strong> <span id="dialog-intro-end">0 (UNKNOWN)</span></div>
                                  <div><strong>Outro Start:</strong> <span id="dialog-outro-start">0 (UNKNOWN)</span></div>
                                  <div><strong>Outro End:</strong> <span id="dialog-outro-end">0 (UNKNOWN)</span></div>
                                  <div><strong>Recap Start:</strong> <span id="dialog-recap-start">0 (UNKNOWN)</span></div>
                                  <div><strong>Recap End:</strong> <span id="dialog-recap-end">0 (UNKNOWN)</span></div>
                                  <div class="debug-marker-original">
                                      <h4>Original Values (at load):</h4>
                                      <div><strong>Intro Start:</strong> <span id="dialog-original-intro-start">0</span></div>
                                      <div><strong>Intro End:</strong> <span id="dialog-original-intro-end">0</span></div>
                                      <div><strong>Outro Start:</strong> <span id="dialog-original-outro-start">0</span></div>
                                      <div><strong>Outro End:</strong> <span id="dialog-original-outro-end">0</span></div>
                                      <div><strong>Recap Start:</strong> <span id="dialog-original-recap-start">0</span></div>
                                      <div><strong>Recap End:</strong> <span id="dialog-original-recap-end">0</span></div>
                                  </div>
                              </div>
                           <div class="debug-refresh-status">
                                    <h4>Refresh Status:</h4>
                                    <div><strong>Status:</strong> <span id="dialog-refresh-status">No refresh attempted</span></div>
                                    <div><strong>Error:</strong> <span id="dialog-refresh-error">None</span></div>
                                </div>
                           </div>
                          </div>
                      </div>
                  </div>
              `;
            p.container.insertAdjacentHTML('beforeend', uiHTML);

            p.playBtn = p.container.querySelector('#videoPlayPauseBtn');
            p.playIcon = p.playBtn.querySelector('i');
            p.bigPlay = p.container.querySelector('.big-play-btn');
            p.tapToPlayOverlay = p.container.querySelector('.tap-to-play-overlay');
            p.progressBar = p.container.querySelector('.progress-filled');
            p.progressContainer = p.container.querySelector('.progress-container');
            p.timeCurrent = p.container.querySelector('#videoCurrentTime');
            p.timeTotal = p.container.querySelector('#videoTotalTime');
            p.volSlider = p.container.querySelector('.volume-slider');
            p.muteBtn = p.container.querySelector('#videoMuteBtn');
            p.fullscreenBtn = p.container.querySelector('#videoFullscreenBtn');
            p.speedBtn = p.container.querySelector('#videoSpeedBtn');
            p.speedValue = p.container.querySelector('#speedValue');
            p.subtitleBtn = p.container.querySelector('#videoSubtitleBtn');
            p.subtitleMenu = p.container.querySelector('#subtitleMenu');
            p.speedMenu = p.container.querySelector('#speedMenu');
            p.debugDialog = p.container.querySelector('#debugDialog');
            p.debugDialogClose = p.container.querySelector('#debugDialogClose');
            p.dialogSeriesInput = p.container.querySelector('#dialog-series-input');
            p.dialogSeasonInput = p.container.querySelector('#dialog-season-input');
            p.dialogEpisodeInput = p.container.querySelector('#dialog-episode-input');
            p.dialogImdbInput = p.container.querySelector('#dialog-imdb-input');
            p.debugSaveBtn = p.container.querySelector('#debugSaveBtn');
            p.dialogIntroStart = p.container.querySelector('#dialog-intro-start');
            p.dialogIntroEnd = p.container.querySelector('#dialog-intro-end');
            p.dialogOutroStart = p.container.querySelector('#dialog-outro-start');
            p.dialogOutroEnd = p.container.querySelector('#dialog-outro-end');
            p.dialogRecapStart = p.container.querySelector('#dialog-recap-start');
            p.dialogRecapEnd = p.container.querySelector('#dialog-recap-end');
            p.dialogOriginalIntroStart = p.container.querySelector('#dialog-original-intro-start');
            p.dialogOriginalIntroEnd = p.container.querySelector('#dialog-original-intro-end');
            p.dialogOriginalOutroStart = p.container.querySelector('#dialog-original-outro-start');
            p.dialogOriginalOutroEnd = p.container.querySelector('#dialog-original-outro-end');
            p.dialogOriginalRecapStart = p.container.querySelector('#dialog-original-recap-start');
            p.dialogOriginalRecapEnd = p.container.querySelector('#dialog-original-recap-end');
            p.dialogRefreshStatus = p.container.querySelector('#dialog-refresh-status');
            p.dialogRefreshError = p.container.querySelector('#dialog-refresh-error');
            p.buffering = p.container.querySelector('.buffering-overlay');
            p.clickOverlay = p.container.querySelector('.video-click-overlay');
            p.prevBtn = p.container.querySelector('#videoPrevBtn');
            p.nextBtn = p.container.querySelector('#videoNextBtn');
            p.preview = p.container.querySelector('#scrollPreview');
            p.previewImg = p.container.querySelector('#storyboardImg');
            p.previewTime = p.container.querySelector('#previewTime');
            p.subtitleList = p.container.querySelector('#subtitleList');

            const videoTrack = localStorage.getItem('jmedia_last_track_' + p.videoId);
            const globalTrack = sessionStorage.getItem('jmedia_global_subtitle_track');
            p.lastSelectedTrackId = videoTrack || globalTrack;

            const offBtn = p.container.querySelector('#sub-off');
            if (offBtn) {
                offBtn.onclick = (e) => { e.stopPropagation(); p.selectSubtitle('off', offBtn); };
            }

            const audioSelector = document.getElementById('audioTrackSelector');
            const audioPlaceholder = p.container.querySelector('#audioSelectorPlaceholder');
            if (audioSelector && audioPlaceholder) {
                // Clone the selector into the player so the ORIGINAL stays in the
                // static page layout.  Previously we used appendChild, which MOVED
                // the element into the player DOM.  When the SPA later navigates
                // (e.g. episode change, section switch), contentDiv.innerHTML = html
                // destroys the player — and the moved selector with it.  On re-init
                // document.getElementById('audioTrackSelector') returns null and the
                // audio-track selector is permanently gone until the page reloads.
                const clone = audioSelector.cloneNode(true);
                audioPlaceholder.appendChild(clone);
                console.log('[SimplePlayer] Cloned audioTrackSelector into player UI');

                // Move the dropdown menu OUT of .controls-row so overflow-x:auto
                // doesn't clip the absolutely-positioned menu (CSS spec forces
                // overflow-y:auto when overflow-x is auto).  Find the menu inside
                // the cloned content, not the original in the static layout.
                const audioMenu = audioPlaceholder.querySelector('#audioTrackMenu');
                const controlsContainer = p.container.querySelector('.controls-container');
                if (audioMenu && controlsContainer) {
                    controlsContainer.appendChild(audioMenu);
                    console.log('[SimplePlayer] Moved audioTrackMenu outside controls-row');
                }
            } else if (audioPlaceholder) {
                // Safety net: the original #audioTrackSelector was destroyed by a
                // previous SPA navigation (e.g. Bug 3 before this fix).  Build a
                // minimal inline replacement so the player still has track controls.
                console.warn('[SimplePlayer] audioTrackSelector missing — creating inline replacement');
                audioPlaceholder.innerHTML =
                    '<div class="audio-track-selector" id="audioTrackSelector">' +
                        '<button class="audio-track-button" onclick="window.toggleAudioTrackMenu()" title="Audio Language">' +
                            '<i class="fa-solid fa-language"></i>' +
                            '<span class="current-track" id="currentAudioTrackDisplay">Default</span>' +
                            '<i class="fa-solid fa-chevron-down" style="font-size:0.6rem;"></i>' +
                        '</button>' +
                        '<div class="audio-track-menu" id="audioTrackMenu" style="display:none;">' +
                            '<div class="track-list-header">Audio Tracks</div>' +
                            '<div class="track-list" id="audioTrackList">' +
                                '<div class="track-item" data-track="default" onclick="window.selectAudioTrack(\'default\')">' +
                                    '<span class="track-name">Default</span>' +
                                    '<i class="pi pi-check track-selected" style="display:none;"></i>' +
                                '</div>' +
                            '</div>' +
                        '</div>' +
                    '</div>';
                // Also move the newly created menu out of the controls-row
                const newMenu = audioPlaceholder.querySelector('#audioTrackMenu');
                const ctrlCont = p.container.querySelector('.controls-container');
                if (newMenu && ctrlCont) {
                    ctrlCont.appendChild(newMenu);
                }
            }

            // Raise logo-overlay z-index above .media-info (z-index:100) so the
            // logo image and fallback title are always visible, not covered by
            // the gradient overlay.
            const logoOverlay = p.container.querySelector('.player-logo-overlay');
            if (logoOverlay) {
                logoOverlay.style.setProperty('z-index', '101', 'important');
            }

            if (p.debugDialogClose) {
                p.debugDialogClose.onclick = (e) => {
                    e.stopPropagation();
                    p.closeDebugDialog();
                };
            }

            p.debugDialog.onclick = (e) => {
                if (e.target === p.debugDialog) {
                    p.closeDebugDialog();
                }
            };

            if (p.debugSaveBtn) {
                p.debugSaveBtn.onclick = (e) => {
                    e.stopPropagation();
                    p.stateMgr.saveAndReload();
                };
            }
        }
    };
})(window);
