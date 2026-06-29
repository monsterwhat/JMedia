(function() {
    'use strict';

    /* ---------- iOS touch-compatible event binding ---------- *
     * On iOS Safari, click events can fail to fire on elements
     * positioned over a <video> element because iOS consumes
     * touch events for its native video controls.  Using touchend
     * with preventDefault() suppresses the delayed synthetic
     * click, avoiding double-fire.  On desktop only the click
     * fires, so the behaviour is transparent everywhere.           */
    function onTap(el, handler) {
        el.addEventListener('touchend', function(e) {
            e.preventDefault();               /* Prevent the delayed synthetic click on iOS */
            handler(e);
        });
        el.addEventListener('click', handler);
    }

    window.initOPlayerAdapter = function(videoId) {
        if (!videoId) {
            var container = document.getElementById('customPlayer');
            if (container) videoId = container.dataset.videoId;
        }
        if (!videoId) {
            console.error('[OPlayerAdapter] No videoId provided');
            return;
        }

        /* ---------- DOM refs (only custom overlay elements) ---------- */
        var container       = document.getElementById('customPlayer');
        var oplayerContainer= document.getElementById('oplayerContainer');
        var subtitleMenu    = document.getElementById('subtitleMenu');
        var backBtn         = document.getElementById('backBtn');
        var assCanvas       = document.getElementById('assCanvas');
        var settingsToggleBtn = document.getElementById('settingsToggleBtn');

        /* ---------- Build stream URL ---------- */
        var streamUrl = '/api/video/stream/' + encodeURIComponent(videoId) + '.mp4';

        var profileId = localStorage.getItem('activeProfileId') || '1';
        var volumeKey = 'jmedia_video_volume_' + profileId;
        var muteKey = 'jmedia_video_mute_' + profileId;

        var player = null;
        var video = null;

        /* ---------- Settings Menu Navigation ---------- */
        onTap(subtitleMenu, function(e) {
            var item = e.target.closest('.settings-item');
            if (item) {
                e.stopPropagation();
                var page = item.dataset.page;
                var target = subtitleMenu.querySelector('.settings-page[data-page="' + page + '"]');
                if (target) {
                    subtitleMenu.querySelectorAll('.settings-page').forEach(function(p) { p.classList.remove('active'); });
                    target.classList.add('active');
                }
                return;
            }
            var back = e.target.closest('.settings-back');
            if (back) {
                e.stopPropagation();
                subtitleMenu.querySelectorAll('.settings-page').forEach(function(p) { p.classList.remove('active'); });
                var mainPage = subtitleMenu.querySelector('.settings-page[data-page="main"]');
                if (mainPage) mainPage.classList.add('active');
                return;
            }
            var manageBtn = e.target.closest('#manageSubtitlesBtn');
            if (manageBtn) {
                e.stopPropagation();
                subtitleMenu.classList.remove('active');
                if (window.subtitleManager) {
                    window.subtitleManager.openModal(videoId, container.dataset.title, container.dataset.path);
                }
                return;
            }
            var playerOpt = e.target.closest('.player-option');
            if (playerOpt) {
                e.stopPropagation();
                var playerName = playerOpt.dataset.player;
                if (playerName) {
                    subtitleMenu.querySelectorAll('.player-option').forEach(function(b) { b.style.borderColor = ''; b.style.color = ''; });
                    playerOpt.style.borderColor = '#48c774';
                    playerOpt.style.color = '#48c774';
                    if (window.Toast) window.Toast.info('Switching to ' + playerName + '...');
                    var profileId = localStorage.getItem('activeProfileId') || '1';
                    fetch('/api/settings/' + profileId + '/default-player', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ defaultPlayer: playerName })
                    }).then(function() {
                        location.reload();
                    }).catch(function() {
                        if (window.Toast) window.Toast.error('Failed to switch player');
                    });
                }
                return;
            }

            var qualityBtn = e.target.closest('.quality-btn');
            if (qualityBtn && video) {
                e.stopPropagation();
                var quality = parseInt(qualityBtn.dataset.quality);
                var label = qualityBtn.textContent.trim();
                subtitleMenu.querySelectorAll('.quality-btn').forEach(function(b) { b.classList.remove('active'); });
                qualityBtn.classList.add('active');
                if (window.Toast) window.Toast.info('Quality: ' + label);

                var currentTime = video.currentTime || 0;
                var url = '/api/video/stream/' + encodeURIComponent(videoId) + '.mp4?start=' + currentTime + '&quality=' + quality;
                video.src = url;
                video.load();
                video.play().catch(function() {});
                return;
            }
        });

        /* ---------- Settings toggle ---------- */
        onTap(settingsToggleBtn, function(e) {
            e.stopPropagation();
            subtitleMenu.classList.toggle('active');
        });

        /* Close menu when tapping/clicking outside */
        function closeMenuIfOpen(e) {
            if (subtitleMenu.classList.contains('active') &&
                !subtitleMenu.contains(e.target) &&
                e.target !== settingsToggleBtn &&
                !settingsToggleBtn.contains(e.target)) {
                subtitleMenu.classList.remove('active');
            }
        }
        document.addEventListener('click', closeMenuIfOpen);
        document.addEventListener('touchend', function(e) {
            /* Only close, never preventDefault — must not break scrolling */
            closeMenuIfOpen(e);
        }, { passive: true });

        /* ---------- Subtitle Timing Offset ---------- */
        (function() {
            var subMinus = document.getElementById('subMinusBtn');
            if (subMinus) onTap(subMinus, function(e) {
                e.stopPropagation();
                if (window.subtitleManager) window.subtitleManager.adjustCorrection(-0.2);
                if (window.testPlayerFeatures && window.testPlayerFeatures.loadSubtitles) {
                    window.testPlayerFeatures.loadSubtitles(true);
                }
            });
            var subPlus = document.getElementById('subPlusBtn');
            if (subPlus) onTap(subPlus, function(e) {
                e.stopPropagation();
                if (window.subtitleManager) window.subtitleManager.adjustCorrection(0.2);
                if (window.testPlayerFeatures && window.testPlayerFeatures.loadSubtitles) {
                    window.testPlayerFeatures.loadSubtitles(true);
                }
            });
            var subReset = document.getElementById('subResetBtn');
            if (subReset) onTap(subReset, function(e) {
                e.stopPropagation();
                localStorage.setItem('jmedia_subtitle_correction', 0);
                var valEl = document.getElementById('subCorrectionVal');
                if (valEl) valEl.textContent = '0.0s';
                if (window.testPlayerFeatures && window.testPlayerFeatures.loadSubtitles) {
                    window.testPlayerFeatures.loadSubtitles(true);
                }
            });
            var correctionVal = document.getElementById('subCorrectionVal');
            if (correctionVal) {
                var initialCorrection = parseFloat(localStorage.getItem('jmedia_subtitle_correction') || '0');
                correctionVal.textContent = (initialCorrection >= 0 ? '+' : '') + initialCorrection.toFixed(1) + 's';
                onTap(correctionVal, function(e) {
                    e.stopPropagation();
                    var currentVal = parseFloat(localStorage.getItem('jmedia_subtitle_correction') || '0');
                    var input = document.createElement('input');
                    input.type = 'number';
                    input.step = '0.1';
                    input.value = currentVal.toFixed(1);
                    input.className = 'correction-input';
                    input.style.cssText = 'width:70px;text-align:center;background:#333;color:white;border:1px solid #48c774;border-radius:4px;padding:4px;font-family:monospace;font-size:0.9rem;';

                    function saveValue() {
                        var val = parseFloat(input.value) || 0;
                        val = Math.round(val * 10) / 10;
                        localStorage.setItem('jmedia_subtitle_correction', val);
                        correctionVal.textContent = (val >= 0 ? '+' : '') + val.toFixed(1) + 's';
                        if (window.testPlayerFeatures && window.testPlayerFeatures.loadSubtitles) {
                            window.testPlayerFeatures.loadSubtitles(true);
                        }
                    }
                    input.onblur = function() {
                        saveValue();
                        if (correctionVal.parentNode) correctionVal.parentNode.replaceChild(correctionVal, input);
                    };
                    input.onkeydown = function(ke) {
                        if (ke.key === 'Enter') { ke.preventDefault(); input.blur(); }
                        else if (ke.key === 'Escape') { ke.preventDefault(); input.value = currentVal.toFixed(1); input.blur(); }
                    };
                    correctionVal.parentNode.replaceChild(input, correctionVal);
                    input.focus();
                    input.select();
                });
            }
        })();

        /* ---------- Fullscreen (iOS-aware) ---------- */
        var _isCssFS = false;

        function enterCssFullscreen() {
            _isCssFS = true;
            container.classList.add('is-css-fullscreen', 'is-fullscreen');
            document.body.style.overflow = 'hidden';
        }

        function exitCssFullscreen() {
            _isCssFS = false;
            container.classList.remove('is-css-fullscreen', 'is-fullscreen');
            document.body.style.overflow = '';
        }

        function toggleFullscreen() {
            var isNativeFS = !!(document.fullscreenElement || document.webkitFullscreenElement);

            if (isNativeFS) {
                if (document.exitFullscreen) document.exitFullscreen();
                else if (document.webkitExitFullscreen) document.webkitExitFullscreen();
                return;
            }

            if (_isCssFS) {
                exitCssFullscreen();
                return;
            }

            var isIOS = window.PlayerUtils && window.PlayerUtils.isIOS();
            if (isIOS && video) {
                try {
                    if (video.requestFullscreen) {
                        video.requestFullscreen();
                    } else if (video.webkitEnterFullscreen) {
                        video.webkitEnterFullscreen();
                    } else {
                        enterCssFullscreen();
                    }
                } catch (err) {
                    console.warn('[OPlayerAdapter] iOS fullscreen failed:', err);
                    enterCssFullscreen();
                }
            } else {
                if (container.requestFullscreen) {
                    container.requestFullscreen().catch(function() {
                        console.warn('[OPlayerAdapter] Fullscreen denied, using CSS fallback');
                        enterCssFullscreen();
                    });
                } else if (container.webkitRequestFullscreen) {
                    container.webkitRequestFullscreen();
                } else {
                    enterCssFullscreen();
                    return;
                }
                container.classList.add('is-fullscreen');
            }
        }

        document.addEventListener('fullscreenchange', syncFSIcon);
        document.addEventListener('webkitfullscreenchange', syncFSIcon);

        function syncFSIcon() {
            if (_isCssFS) return;
            var isFS = document.fullscreenElement || document.webkitFullscreenElement;
            container.classList.toggle('is-fullscreen', !!isFS);
        }

        /* ---------- Back button ---------- */
        onTap(backBtn, function() { history.back(); });

        /* ---------- Keyboard shortcuts ---------- */
        /* Use capture phase so shortcuts work even when OPlayer UI has focus
         * and would otherwise consume keyboard events during bubbling.         */
        document.addEventListener('keydown', function(e) {
            if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;
            switch (e.key) {
                case ' ':
                case 'k': e.preventDefault(); if (video) { if (video.paused) video.play(); else video.pause(); } break;
                case 'f': e.preventDefault(); toggleFullscreen(); break;
                case 'm': e.preventDefault(); if (video) { video.muted = !video.muted; } break;
                case 'ArrowLeft': e.preventDefault(); if (video) { video.currentTime = Math.max(0, (video.currentTime || 0) - 15); } break;
                case 'ArrowRight': e.preventDefault(); if (video) { video.currentTime = Math.min(video.duration || 0, (video.currentTime || 0) + 15); } break;
                case 'ArrowUp':
                    e.preventDefault();
                    if (video) { video.volume = Math.min(1, (video.volume || 0) + 0.1); }
                    break;
                case 'ArrowDown':
                    e.preventDefault();
                    if (video) { video.volume = Math.max(0, (video.volume || 0) - 0.1); }
                    break;
            }
        }, true);

        /* ---------- Initialize OPlayer ---------- */
        function initPlayer() {
            if (typeof OPlayer === 'undefined' || typeof OUI === 'undefined') {
                setTimeout(initPlayer, 200);
                return;
            }

            PlayerUtils?.requestWakeLock?.();

            try {
                var oplayerOptions = {
                    source: {
                        src: streamUrl,
                        title: 'Video ' + videoId,
                        format: 'auto'
                    },
                    autoplay: true,
                    muted: localStorage.getItem(muteKey) === 'true',
                    volume: Math.pow(parseFloat(localStorage.getItem(volumeKey) || '0.7'), 2),
                    playbackRate: 1,
                    playsinline: true,
                    preload: 'auto',
                    videoAttr: { 'crossorigin': 'anonymous' }
                };

                /* Register @oplayer/ui for native controls */
                if (typeof OUI !== 'undefined') {
                    if (settingsToggleBtn) settingsToggleBtn.style.display = 'none';

                    player = OPlayer.make('#' + oplayerContainer.id, oplayerOptions)
                        .use([OUI({
                            icons: {
                                next: '<svg style="transform:scale(0.7)" viewBox="0 0 1024 1024"><path d="M743.36 427.52L173.76 119.04A96 96 0 0 0 32 203.52v616.96a96 96 0 0 0 141.76 84.48l569.6-308.48a96 96 0 0 0 0-168.96zM960 96a32 32 0 0 0-32 32v768a32 32 0 0 0 64 0V128a32 32 0 0 0-32-32z"/></svg>',
                                previous: '<svg style="transform:scale(0.7)" viewBox="0 0 1024 1024"><g transform="translate(1024,0) scale(-1,1)"><path d="M743.36 427.52L173.76 119.04A96 96 0 0 0 32 203.52v616.96a96 96 0 0 0 141.76 84.48l569.6-308.48a96 96 0 0 0 0-168.96zM960 96a32 32 0 0 0-32 32v768a32 32 0 0 0 64 0V128a32 32 0 0 0-32-32z"/></g></svg>'
                            },
                            theme: {
                                controller: {
                                    header: true,
                                    display: 'always',
                                    coverButton: true
                                }
                            },
                            settings: [
                                'loop',
                                {
                                    key: 'quality',
                                    type: 'selector',
                                    name: 'Video Quality',
                                    icon: '',
                                    children: [
                                        { name: 'Source', value: '0', default: true },
                                        { name: '480p', value: '480' },
                                        { name: '720p', value: '720' },
                                        { name: '1080p', value: '1080' },
                                        { name: '4K', value: '2160' }
                                    ],
                                    onChange: function onChange(_ref) {
                                        var value = _ref.value;
                                        var vid = player && player.$video;
                                        if (!vid) return;
                                        var currentTime = vid.currentTime || 0;
                                        var url = '/api/video/stream/' + encodeURIComponent(videoId) + '.mp4?start=' + currentTime + '&quality=' + value;
                                        vid.src = url;
                                        vid.load();
                                        vid.play().catch(function () {});
                                    }
                                },
                                {
                                    key: 'player',
                                    type: 'selector',
                                    name: 'Video Player',
                                    icon: '',
                                    children: [
                                        { name: 'JMedia Player', value: 'simple' },
                                        { name: 'Video.js', value: 'videojs' },
                                        { name: 'OPlayer', value: 'oplayer', default: true }
                                    ],
                                    onChange: function onChange(_ref2) {
                                        var value = _ref2.value;
                                        if (window.Toast) window.Toast.info('Switching to ' + value + '...');
                                        var profileId = localStorage.getItem('activeProfileId') || '1';
                                        fetch('/api/settings/' + profileId + '/default-player', {
                                            method: 'POST',
                                            headers: { 'Content-Type': 'application/json' },
                                            body: JSON.stringify({ defaultPlayer: value })
                                        }).then(function () {
                                            location.reload();
                                        }).catch(function () {
                                            if (window.Toast) window.Toast.error('Failed to switch player');
                                        });
                                    }
                                },
                                {
                                    key: 'subtitleOffset',
                                    type: 'selector',
                                    name: 'Subtitle Offset',
                                    icon: '',
                                    children: [
                                        { name: '-2s', value: '-2' },
                                        { name: '-1s', value: '-1' },
                                        { name: '-0.5s', value: '-0.5' },
                                        { name: '0', value: '0', default: true },
                                        { name: '+0.5s', value: '0.5' },
                                        { name: '+1s', value: '1' },
                                        { name: '+2s', value: '2' }
                                    ],
                                    onChange: function onChange(_ref3) {
                                        var value = _ref3.value;
                                        var offset = parseFloat(value) || 0;
                                        localStorage.setItem('jmedia_subtitle_correction', offset);
                                        var valEl = document.getElementById('subCorrectionVal');
                                        if (valEl) valEl.textContent = (offset >= 0 ? '+' : '') + offset.toFixed(1) + 's';
                                        if (window.testPlayerFeatures && window.testPlayerFeatures.loadSubtitles) {
                                            window.testPlayerFeatures.loadSubtitles(true);
                                        }
                                    }
                                },
                                {
                                    key: 'pip',
                                    type: 'switcher',
                                    name: 'Picture in Picture',
                                    onChange: function onChange(_ref4) {
                                        var value = _ref4.value;
                                        var vid = player && player.$video;
                                        if (!vid || typeof vid.requestPictureInPicture !== 'function') return;
                                        if (document.pictureInPictureElement) {
                                            document.exitPictureInPicture().catch(function () {});
                                        } else {
                                            vid.requestPictureInPicture().catch(function (err) {
                                                console.warn('[OPlayerAdapter] PiP failed:', err);
                                            });
                                        }
                                    }
                                }
                            ]
                        })])
                        .create();

                    /* Expose OPlayer instance globally for subtitle API integration */
                    window.__oplayerPlayer = player;

                    /* Sync PiP switcher with native PiP events */
                    (function() {
                        var pipSyncTimer = null;
                        function syncPipSwitcher() {
                            if (!player || !player.context || !player.context.ui) return;
                            try {
                                player.context.ui.setting.select('pip', document.pictureInPictureElement ? 1 : 0);
                            } catch(e) { /* setting may not be registered yet */ }
                        }
                        player.on('enterpictureinpicture', function() { syncPipSwitcher(); });
                        player.on('leavepictureinpicture', function() { syncPipSwitcher(); });
                        // Also sync when setting panel opens
                        document.addEventListener('click', function(e) {
                            if (e.target.closest && e.target.closest('.o-setting')) {
                                if (pipSyncTimer) clearTimeout(pipSyncTimer);
                                pipSyncTimer = setTimeout(syncPipSwitcher, 100);
                            }
                        });
                    })();

                    player.on('next', function() {
                        if (window.testPlayerFeatures) {
                            window.testPlayerFeatures._navigate('next');
                        }
                    });
                    player.on('previous', function() {
                        if (window.testPlayerFeatures) {
                            window.testPlayerFeatures._navigate('previous');
                        }
                    });
                } else {
                    console.warn('[OPlayerAdapter] @oplayer/ui not loaded, falling back to headless OPlayer');
                    player = OPlayer.make('#' + oplayerContainer.id, oplayerOptions).create();
                }

                var waitForVideo = setInterval(function() {
                    if (player && player.$video) {
                        clearInterval(waitForVideo);
                        onVideoReady(player.$video);
                        /* Restore saved subtitle offset in OPlayer settings selector */
                        (function() {
                            var savedOffset = parseFloat(localStorage.getItem('jmedia_subtitle_correction') || '0');
                            if (savedOffset !== 0) {
                                var offsetValues = ['-2', '-1', '-0.5', '0', '0.5', '1', '2'];
                                var idx = offsetValues.indexOf(String(savedOffset));
                                if (idx >= 0 && player.context && player.context.ui) {
                                    try {
                                        player.context.ui.setting.select('subtitleOffset', idx);
                                    } catch(e) {}
                                }
                            }
                        })();

                        /* ---------- Custom Timeline Preview Thumbnails ----------
                         * OPlayer's built-in thumbnail system uses percentage-based
                         * background-position ( -${index}00% ) which is broken for
                         * grid sprite sheets. We implement our own pixel-based approach
                         * matching the working simple-player storyboard logic.          */
                        (function() {
                            var sbUrl = '/api/video/storyboard/' + encodeURIComponent(videoId);
                            var metaUrl = sbUrl + '/metadata';
                            var sbMeta = null;
                            var $thumbEl = null;
                            var thumbReady = false;

                            function pollMeta() {
                                fetch(metaUrl)
                                    .then(function(r) { return r.json(); })
                                    .then(function(json) {
                                        var d = json.data || json;
                                        if (d && d.isReady && d.totalTiles > 0) {
                                            sbMeta = d;
                                            if (!thumbReady) tryInit();
                                        } else {
                                            setTimeout(pollMeta, 3000);
                                        }
                                    })
                                    .catch(function() { setTimeout(pollMeta, 5000); });
                            }

                            function tryInit() {
                                if (thumbReady) return;
                                if (!player || !player.context || !player.context.ui) {
                                    return setTimeout(tryInit, 500);
                                }
                                var $prog = player.context.ui.$progress;
                                if (!$prog) return setTimeout(tryInit, 500);

                                thumbReady = true;
                                var m = sbMeta;

                                $thumbEl = document.createElement('div');
                                $thumbEl.style.cssText = [
                                    'position:absolute',
                                    'left:0',
                                    'bottom:14px',
                                    'pointer-events:none',
                                    'transform:translateX(-50%)',
                                    'display:none',
                                    'z-index:10',
                                    'border-radius:2px',
                                    'width:' + m.width + 'px',
                                    'height:' + m.height + 'px',
                                    'background-image:url(' + sbUrl + ')',
                                    'background-size:' + (m.width * m.columns) + 'px ' + (m.height * m.rows) + 'px',
                                    'background-repeat:no-repeat'
                                ].join(';');

                                $prog.appendChild($thumbEl);

                                $prog.addEventListener('mouseenter', function() {
                                    if ($thumbEl) $thumbEl.style.display = 'block';
                                });

                                $prog.addEventListener('mousemove', function(e) {
                                    if (!$thumbEl || !sbMeta) return;
                                    var rect = $prog.getBoundingClientRect();
                                    var rate = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
                                    var dur = player.duration || 0;
                                    if (!dur || dur === Infinity) return;
                                    var time = rate * dur;
                                    var idx = Math.min(Math.floor(time / m.interval), m.totalTiles - 1);
                                    if (idx < 0) return;
                                    var col = idx % m.columns;
                                    var row = Math.floor(idx / m.columns);
                                    $thumbEl.style.backgroundPosition = '-' + (col * m.width) + 'px -' + (row * m.height) + 'px';
                                    var halfThumb = m.width / 2;
                                    var barWidth = rect.width;
                                    var leftPct;
                                    if (rate * barWidth < halfThumb) {
                                        leftPct = (halfThumb / barWidth) * 100;
                                    } else if (rate * barWidth > barWidth - halfThumb) {
                                        leftPct = ((barWidth - halfThumb) / barWidth) * 100;
                                    } else {
                                        leftPct = rate * 100;
                                    }
                                    $thumbEl.style.left = leftPct + '%';
                                });

                                $prog.addEventListener('mouseleave', function() {
                                    if ($thumbEl) $thumbEl.style.display = 'none';
                                });
                            }

                            setTimeout(pollMeta, 1500);
                        })();
                    }
                }, 50);
                setTimeout(function() { clearInterval(waitForVideo); }, 10000);

            } catch (err) {
                console.error('[OPlayerAdapter] Init error:', err);
            }
        }

        /* ---------- Suppress transient MEDIA_ERR_SRC_NOT_SUPPORTED ---------- */
        /* fMP4 streaming sends a 2-byte probe response ([0,0]) before real
           data is available.  Safari fires MEDIA_ERR_SRC_NOT_SUPPORTED (code 4)
           during this probe but recovers once actual fMP4 data arrives.
           Intercept at capture phase on the container to prevent OPlayer's
           internal error handler from showing a permanent error overlay. */
        oplayerContainer.addEventListener('error', function(e) {
            var vid = e.target;
            if (vid && vid.error && vid.error.code === MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED) {
                console.warn('[OPlayerAdapter] Suppressed MEDIA_ERR_SRC_NOT_SUPPORTED (transient with fMP4 stream)');
                e.stopImmediatePropagation();
                e.preventDefault();
            }
        }, true);

        function onVideoReady(vid) {
            video = vid;

            container.dataset.videoId = videoId;

            video.addEventListener('play', function() {
                PlayerUtils?.requestWakeLock?.();
            });

            video.addEventListener('pause', function() {
                PlayerUtils?.releaseWakeLock?.();
            });

            video.addEventListener('error', function() {
                /* MEDIA_ERR_SRC_NOT_SUPPORTED is suppressed at the container
                   level above.  If we get here it is a real error. */
                PlayerUtils?.releaseWakeLock?.();
            });

            video.addEventListener('ended', function() {
                PlayerUtils?.releaseWakeLock?.();
                if (window.testPlayerFeatures) {
                    window.testPlayerFeatures._navigate('next');
                }
            });

            /* iOS native fullscreen events */
            video.addEventListener('webkitbeginfullscreen', function() {
                _isCssFS = false;
                container.classList.add('is-fullscreen');
            });
            video.addEventListener('webkitendfullscreen', function() {
                container.classList.remove('is-fullscreen');
                if (!video.paused) {
                    video.play().catch(function() {});
                }
            });

            console.log('[OPlayerAdapter] Initialized with videoId:', videoId);

            /* ---------- Volume persistence with exponential curve (matching JMedia default player) ---------- */
            video.addEventListener('volumechange', function() {
                var rawVol = video.volume;
                /* Invert the exponential curve to store the "slider position" —
                 * same as SimplePlayer (EventBinder.js): video.volume = Math.pow(sliderPos, 2)
                 * so sliderPos = Math.pow(video.volume, 1/2) */
                var sliderPos = Math.pow(Math.max(rawVol, 0), 1/2);
                localStorage.setItem(volumeKey, sliderPos);
                localStorage.setItem(muteKey, video.muted);
            });

            /* ---------- Inject seek buttons into OPlayer controller bar ---------- */
            (function injectSeekButtons() {
                if (!player || !player.context || !player.context.ui) {
                    setTimeout(injectSeekButtons, 300);
                    return;
                }
                var controllerBottom = player.context.ui.$controllerBottom;
                if (!controllerBottom) {
                    setTimeout(injectSeekButtons, 300);
                    return;
                }
                /* Find time display to use as insertion anchor */
                var timeEl = controllerBottom.querySelector('[aria-label="time"]');
                if (!timeEl) {
                    setTimeout(injectSeekButtons, 300);
                    return;
                }
                /* Prevent double injection */
                if (controllerBottom.querySelector('.o-seek-btn')) return;

                var videoRef = video;
                var btnStyle = [
                    'background:none',
                    'border:none',
                    'color:#fff',
                    'cursor:pointer',
                    'opacity:0.85',
                    'padding:0 2px',
                    'height:100%',
                    'display:inline-flex',
                    'align-items:center',
                    'justify-content:center',
                    'font-size:0.75rem',
                    'font-family:inherit',
                    'gap:1px',
                    'min-width:28px'
                ].join(';');

                var svgAttrs = 'viewBox="0 0 24 24" width="14" height="14" fill="currentColor"';

                function createSeekBtn(seconds) {
                    var isForward = seconds > 0;
                    var absSec = Math.abs(seconds);
                    /* Single arrow (15s) / double arrows (30s) */
                    var svgPath;
                    if (isForward) {
                        svgPath = absSec >= 30
                            ? '<path d="M5 6l9 6-9 6V6zM14 6l9 6-9 6V6z"/>'
                            : '<path d="M8 6l12 6-12 6V6z"/>';
                    } else {
                        svgPath = absSec >= 30
                            ? '<path d="M19 6l-9 6 9 6V6zM10 6l-9 6 9 6V6z"/>'
                            : '<path d="M16 6L4 12l12 6V6z"/>';
                    }

                    var btn = document.createElement('button');
                    btn.className = 'o-seek-btn';
                    btn.style.cssText = btnStyle;
                    btn.title = (isForward ? '+' : '-') + absSec + 's';
                    btn.setAttribute('aria-label', 'seek ' + (isForward ? '+' : '-') + absSec + 's');
                    var arrowSvg = '<svg ' + svgAttrs + '>' + svgPath + '</svg>';
                    var numSpan = '<span style="font-size:9px;font-weight:700;font-family:sans-serif;line-height:1;color:#fff">' + absSec + '</span>';
                    btn.innerHTML = isForward ? numSpan + arrowSvg : arrowSvg + numSpan;
                    btn.addEventListener('click', function(e) {
                        e.stopPropagation();
                        if (videoRef) {
                            videoRef.currentTime = Math.max(0, Math.min(videoRef.duration || 0, (videoRef.currentTime || 0) + seconds));
                        }
                    });
                    return btn;
                }

                /* Insert seek buttons before the time display */
                timeEl.parentNode.insertBefore(createSeekBtn(-30), timeEl);
                timeEl.parentNode.insertBefore(createSeekBtn(-15), timeEl);
                timeEl.parentNode.insertBefore(createSeekBtn(15), timeEl);
                timeEl.parentNode.insertBefore(createSeekBtn(30), timeEl);

                console.log('[OPlayerAdapter] Seek buttons injected');
            })();

            /* ---------- Build adapter for TestPlayerFeatures ---------- */
            var oAdapter = {
                getVideoElement: function() { return video; },
                getCurrentTime: function() { return video.currentTime; },
                setCurrentTime: function(t) { video.currentTime = t; },
                getDuration: function() { return video.duration; },
                isPaused: function() { return video.paused; },
                play: function() { video.play(); },
                pause: function() { video.pause(); },
                getVolume: function() { return video.volume; },
                setVolume: function(v) { video.volume = v; },
                isMuted: function() { return video.muted; },
                setMuted: function(m) { video.muted = m; },
                getPlaybackRate: function() { return video.playbackRate; },
                setPlaybackRate: function(r) { video.playbackRate = r; },
                on: function(event, cb) { video.addEventListener(event, cb); },
                off: function(event, cb) { video.removeEventListener(event, cb); },
                getVideoSrc: function() { return video.src; },
                setVideoSrc: function(url) { video.src = url; },
                requestFullscreen: function() {
                    var c = document.getElementById('customPlayer');
                    if (c.requestFullscreen) c.requestFullscreen();
                    else if (c.webkitRequestFullscreen) c.webkitRequestFullscreen();
                }
            };

            if (window.TestPlayerFeatures) {
                window.testPlayerFeatures = new window.TestPlayerFeatures(videoId, oAdapter);
            }
        }

        if (document.readyState === 'complete' || document.readyState === 'interactive') {
            initPlayer();
        } else {
            document.addEventListener('DOMContentLoaded', initPlayer);
        }

        /* Re-acquire wake lock when page becomes visible again and video is playing */
        document.addEventListener('visibilitychange', function() {
            if (document.visibilityState === 'visible' && video && !video.paused) {
                PlayerUtils?.requestWakeLock?.();
            }
        });
    };

    /* ---------- WSS trackers for browser WebTorrent ---------- *
     * WebTorrent in the browser uses WebRTC, which can only
     * discover peers via WSS (WebSocket Secure) trackers.
     * Most magnet links only have UDP/HTTP trackers, so we
     * inject well-known WSS trackers as fallback.               */
    var WEBTORRENT_WSS_TRACKERS = [
        'wss://tracker.btorrent.xyz',
        'wss://tracker.openwebtorrent.com',
        'wss://tracker.webtorrent.dev:443/announce',
        'wss://tracker.files.fm:7073/announce'
    ];

    function ensureWssTrackers(url) {
        if (!url || typeof url !== 'string') return url;
        if (!url.startsWith('magnet:')) return url;
        var hasWss = /tr=wss%3A/i.test(url) || /[?&]tr=wss:/i.test(url);
        if (hasWss) return url;
        var enhanced = url;
        for (var i = 0; i < WEBTORRENT_WSS_TRACKERS.length; i++) {
            enhanced += '&tr=' + encodeURIComponent(WEBTORRENT_WSS_TRACKERS[i]);
        }
        console.log('[OPlayerTorrent] Appended ' + WEBTORRENT_WSS_TRACKERS.length + ' WSS trackers to magnet URL');
        return enhanced;
    }

    function createTorrentStatusOverlay(container) {
        var el = document.createElement('div');
        el.id = 'torrentStatus';
        el.style.cssText = [
            'position:absolute',
            'bottom:70px',
            'left:50%',
            'transform:translateX(-50%)',
            'color:#fff',
            'background:rgba(0,0,0,0.75)',
            'padding:8px 18px',
            'border-radius:6px',
            'font-size:13px',
            'z-index:100',
            'text-align:center',
            'pointer-events:none',
            'white-space:nowrap',
            'transition:opacity 0.4s'
        ].join(';');
        el.textContent = 'Starting torrent...';
        container.appendChild(el);
        return el;
    }

    function updateTorrentStatus(el, msg) {
        if (el) el.textContent = msg;
    }

    function hideTorrentStatus(el) {
        if (!el) return;
        el.style.opacity = '0';
        setTimeout(function() { if (el.parentNode) el.parentNode.removeChild(el); }, 500);
    }

    /* ---------- External Torrent Player ---------- */
    window.initExternalOPlayerTorrent = function(containerId, sourceUrl, title, externalId) {
        if (typeof OPlayer === 'undefined' || typeof OTorrent === 'undefined') {
            setTimeout(function() {
                window.initExternalOPlayerTorrent(containerId, sourceUrl, title, externalId);
            }, 200);
            return;
        }

        var container = document.getElementById(containerId) || document.getElementById('customPlayer');
        var oplayerContainer = document.getElementById('oplayerContainer');
        if (!oplayerContainer) {
            console.error('[OPlayerTorrent] No oplayerContainer found');
            return;
        }

        /* Enhance magnet URL with WSS trackers for browser peer discovery */
        sourceUrl = ensureWssTrackers(sourceUrl);

        /* Create status overlay so user sees progress */
        var videoWrapper = container && container.querySelector('.video-wrapper');
        var statusEl = videoWrapper ? createTorrentStatusOverlay(videoWrapper) : null;
        updateTorrentStatus(statusEl, 'Initializing torrent player...');

        var profileId = localStorage.getItem('activeProfileId') || '1';
        var volumeKey = 'jmedia_video_volume_' + profileId;
        var muteKey = 'jmedia_video_mute_' + profileId;

        PlayerUtils?.requestWakeLock?.();

        try {
            var oplayerOptions = {
                source: {
                    src: sourceUrl,
                    title: title || 'Torrent Video',
                    format: 'auto'
                },
                autoplay: true,
                muted: localStorage.getItem(muteKey) === 'true',
                volume: Math.pow(parseFloat(localStorage.getItem(volumeKey) || '0.7'), 2),
                playbackRate: 1,
                playsinline: true,
                preload: 'auto',
                videoAttr: { 'crossorigin': 'anonymous' }
            };

            updateTorrentStatus(statusEl, 'Connecting to peers...');
            /* Pass custom WebTorrent config to fix:
             * - Buggy STUN URL in webtorrent@0.98.18 (query params on STUN URLs are invalid)
             * - Default STUN from Google is more reliable
             * - webtorrent@0.98.x requires rtcConfig under opts.tracker.rtcConfig (not top-level) */
            var plugins = [OTorrent({
                config: {
                    tracker: {
                        rtcConfig: {
                            iceServers: [
                                { urls: 'stun:stun.l.google.com:19302' },
                                { urls: 'stun:stun1.l.google.com:19302' }
                            ]
                        }
                    }
                }
            })];

            /* Register OUI for visible player controls */
            if (typeof OUI !== 'undefined') {
                plugins.push(OUI({
                    theme: {
                        controller: {
                            header: true,
                            display: 'always',
                            coverButton: true
                        }
                    },
                    settings: [
                        'loop',
                        {
                            key: 'pip',
                            type: 'switcher',
                            name: 'Picture in Picture',
                            onChange: function onChange(_ref) {
                                var value = _ref.value;
                                var vid = player && player.$video;
                                if (!vid || typeof vid.requestPictureInPicture !== 'function') return;
                                if (document.pictureInPictureElement) {
                                    document.exitPictureInPicture().catch(function() {});
                                } else {
                                    vid.requestPictureInPicture().catch(function(err) {
                                        console.warn('[OPlayerTorrent] PiP failed:', err);
                                    });
                                }
                            }
                        }
                    ]
                }));
            }

            var player = OPlayer.make('#' + oplayerContainer.id, oplayerOptions)
                .use(plugins)
                .create();

            window.__oplayerPlayer = player;

            /* Volume persistence with exponential curve (matching existing adapter) */
            player.on('volumechange', function() {
                var vid = player.$video;
                if (!vid) return;
                var rawVol = vid.volume;
                var sliderPos = Math.pow(Math.max(rawVol, 0), 1 / 2);
                localStorage.setItem(volumeKey, sliderPos);
                localStorage.setItem(muteKey, vid.muted);
            });

            player.on('play', function() { PlayerUtils?.requestWakeLock?.(); });
            player.on('pause', function() { PlayerUtils?.releaseWakeLock?.(); });
            player.on('ended', function() { PlayerUtils?.releaseWakeLock?.(); });

            /* Track progress for external video via simple interval */
            if (externalId) {
                setInterval(function() {
                    var vid = player && player.$video;
                    if (!vid || vid.paused || !vid.duration) return;
                    var ct = vid.currentTime || 0;
                    var dur = vid.duration || 0;
                    fetch('/api/video/external/' + externalId + '/progress', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ currentTime: ct, duration: dur })
                    }).catch(function() {});
                }, 15000);
            }

            console.log('[OPlayerTorrent] Initialized with source:', sourceUrl.substring(0, 80) + '...');

            /* ---------- Torrent status feedback ---------- */
            var torrentTimeout = setTimeout(function() {
                updateTorrentStatus(statusEl, 'Still looking for peers...');
            }, 15000);

            var torrentLongTimeout = setTimeout(function() {
                updateTorrentStatus(statusEl, 'No peers found. Check the magnet link or try a different source.');
            }, 45000);

            /* Poll for video readiness and hide status once playing */
            var readyCheck = setInterval(function() {
                var vid = player && player.$video;
                if (vid && vid.readyState > 0 && vid.duration && vid.duration !== Infinity) {
                    clearTimeout(torrentTimeout);
                    clearTimeout(torrentLongTimeout);
                    clearInterval(readyCheck);
                    hideTorrentStatus(statusEl);
                    statusEl = null;
                }
            }, 1000);

            /* Also hide on first play event */
            player.on('play', function() {
                clearTimeout(torrentTimeout);
                clearTimeout(torrentLongTimeout);
                clearInterval(readyCheck);
                if (statusEl) {
                    hideTorrentStatus(statusEl);
                    statusEl = null;
                }
            });

            /* Cleanup on error */
            player.on('error', function() {
                clearTimeout(torrentTimeout);
                clearTimeout(torrentLongTimeout);
                clearInterval(readyCheck);
                if (statusEl) {
                    updateTorrentStatus(statusEl, 'Playback error');
                    setTimeout(function() { hideTorrentStatus(statusEl); statusEl = null; }, 5000);
                }
            });

            /* Guard: clean up intervals if video never materialises (OPlayer destroy) */
            var guardCleanup = function() {
                clearTimeout(torrentTimeout);
                clearTimeout(torrentLongTimeout);
                clearInterval(readyCheck);
                if (statusEl) { hideTorrentStatus(statusEl); statusEl = null; }
            };
            /* Use beforeunload for page navigation, and also hook into destroy */
            window.addEventListener('beforeunload', guardCleanup);
            /* If player is destroyed externally, clean up */
            var origDestroy = player.destroy && player.destroy.bind(player);
            if (origDestroy) {
                player.destroy = function() {
                    guardCleanup();
                    window.removeEventListener('beforeunload', guardCleanup);
                    return origDestroy();
                };
            }

            /* Hide status immediately if video is already ready (rare fast case) */
            (function() {
                var checkFast = setInterval(function() {
                    var vid = player && player.$video;
                    if (vid && vid.readyState > 0) {
                        clearTimeout(torrentTimeout);
                        clearTimeout(torrentLongTimeout);
                        clearInterval(checkFast);
                        hideTorrentStatus(statusEl);
                        statusEl = null;
                    }
                }, 100);
                setTimeout(function() { clearInterval(checkFast); }, 5000);
            })();
        } catch (err) {
            console.error('[OPlayerTorrent] Init error:', err);
            if (statusEl) {
                updateTorrentStatus(statusEl, 'Failed to initialise torrent player');
            }
        }
    };
})();
