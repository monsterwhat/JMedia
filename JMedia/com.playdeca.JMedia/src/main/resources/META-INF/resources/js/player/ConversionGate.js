if (typeof window.ConversionGate === 'undefined') {
    (function(window) {
        'use strict';

        /* ConversionGate — runs BEFORE any player initializes.
         *
         * When the backend flags a video for conversion (data-needs-conversion="true")
         * because the browser cannot play its codec natively, this script blocks player
         * init, shows a converting overlay + progress toast, polls the conversion job,
         * and reloads the playback fragment when it completes.
         *
         * Exceptions:
         *  - native-HEVC: if the codec is HEVC/H.265 and the browser can play it natively,
         *    we do NOT block — the player requests the lightweight nativeHevc=1 remux.
         *  - native-AV1:  if the codec is AV1 and the browser can play it natively,
         *    we do NOT block — the player requests the lightweight nativeAv1=1 remux.
         */
        var POLL_INTERVAL_MS = 2000;
        var CONVERSION_TOAST_ID = 'video-conversion-toast';

        var state = {
            blocking: false,
            destroyed: false,
            container: null,
            videoId: null,
            jobId: null,
            overlayEl: null,
            overlayLabel: null,
            overlayBar: null,
            pollTimer: null,
            forcedRelative: false
        };
        var lastRunKey = null;

        function findContainer() {
            if (state.container && document.body.contains(state.container)) return state.container;
            state.container = document.getElementById('customPlayer') ||
                document.querySelector('[data-video-id][data-needs-conversion]');
            return state.container || null;
        }

        function isStale() {
            return !state.container || !document.body.contains(state.container);
        }

        /* The container carries the data-* attrs (data-video-id, data-needs-conversion,
         * data-video-codec, data-conversion-job-id, data-conversion-status). */
        function isNativeHevcEligible() {
            if (!state.container || state.container.dataset.needsConversion !== 'true') return false;
            var codec = (state.container.dataset.videoCodec || '').toLowerCase();
            if (!(codec.indexOf('hevc') !== -1 || codec.indexOf('h265') !== -1)) return false;
            return !!(window.PlayerStreamManager && window.PlayerStreamManager.hasNativeHevcSupport());
        }

        function isNativeAv1Eligible() {
            if (!state.container || state.container.dataset.needsConversion !== 'true') return false;
            var codec = (state.container.dataset.videoCodec || '').toLowerCase();
            if (!(codec.indexOf('av1') !== -1 || codec.indexOf('av01') !== -1)) return false;
            return !!(window.PlayerStreamManager && window.PlayerStreamManager.hasNativeAv1Support());
        }

        function updateProgress(message, percent) {
            if (window.Toast) {
                window.Toast.progress(message || 'Converting video...', percent || 0, { id: CONVERSION_TOAST_ID });
            }
            if (state.overlayLabel) state.overlayLabel.textContent = message || 'Converting video...';
            if (state.overlayBar) state.overlayBar.style.width = Math.max(0, Math.min(100, percent || 0)) + '%';
        }

        function showOverlay() {
            hideOverlay();
            var container = state.container;
            if (!container) return;

            /* Make sure the absolutely-positioned overlay covers just the player area. */
            var pos = window.getComputedStyle(container).position;
            if (pos === 'static') {
                container.style.position = 'relative';
                state.forcedRelative = true;
            }

            var overlay = document.createElement('div');
            overlay.id = 'conversion-overlay';
            overlay.style.cssText = [
                'position:absolute',
                'top:0',
                'left:0',
                'right:0',
                'bottom:0',
                'z-index:9999',
                'display:flex',
                'flex-direction:column',
                'align-items:center',
                'justify-content:center',
                'gap:16px',
                'background:rgba(0,0,0,0.85)',
                'color:#fff',
                'font-family:sans-serif',
                'text-align:center',
                'padding:16px'
            ].join(';');

            var spinner = document.createElement('div');
            spinner.className = 'pi pi-spin pi-spinner';
            spinner.style.cssText = 'font-size:3rem;color:#48c774;';

            var label = document.createElement('div');
            label.textContent = 'Converting video for playback...';
            label.style.cssText = 'font-size:1.1rem;font-weight:600;';

            var barWrap = document.createElement('div');
            barWrap.style.cssText = 'width:70%;max-width:320px;height:6px;background:rgba(255,255,255,0.2);border-radius:3px;overflow:hidden;';
            var bar = document.createElement('div');
            bar.id = 'conversion-overlay-bar';
            bar.style.cssText = 'width:0%;height:100%;background:#48c774;transition:width 0.3s ease;';

            var caption = document.createElement('div');
            caption.textContent = 'This file\u2019s format isn\u2019t compatible with your device, so we\u2019re converting it into a format that is \u2014 this can take a moment.';
            caption.style.cssText = 'font-size:0.85rem;color:rgba(255,255,255,0.65);max-width:340px;line-height:1.35;';

            barWrap.appendChild(bar);
            overlay.appendChild(spinner);
            overlay.appendChild(label);
            overlay.appendChild(barWrap);
            overlay.appendChild(caption);
            container.appendChild(overlay);

            state.overlayEl = overlay;
            state.overlayLabel = label;
            state.overlayBar = bar;

            if (window.Toast) {
                window.Toast.progress('Preparing video for playback...', 0, {
                    id: CONVERSION_TOAST_ID,
                    caption: 'Converting video for browser playback'
                });
            }
        }

        function hideOverlay() {
            if (state.overlayEl && state.overlayEl.parentNode) {
                state.overlayEl.parentNode.removeChild(state.overlayEl);
            }
            state.overlayEl = null;
            state.overlayLabel = null;
            state.overlayBar = null;
            if (state.container && state.forcedRelative) {
                state.container.style.position = '';
                state.forcedRelative = false;
            }
        }

        function stopPolling() {
            if (state.pollTimer) {
                clearTimeout(state.pollTimer);
                state.pollTimer = null;
            }
        }

        function scheduleNextPoll(jobId) {
            stopPolling();
            if (state.destroyed || isStale()) return;
            state.pollTimer = setTimeout(function() { pollConversion(jobId); }, POLL_INTERVAL_MS);
        }

        /* Reload the playback fragment so the player initializes with the converted
         * file. Reuses the strategy the codebase already uses for player reloads. */
        function reloadFragment() {
            if (isStale()) return;
            var container = state.container;
            var modalContent = document.getElementById('player-modal-content');
            if (modalContent && container && modalContent.contains(container) && typeof window.openPlayerModal === 'function') {
                window.openPlayerModal(state.videoId);
            } else if (window.videoSPA && typeof window.videoSPA.switchSection === 'function') {
                window.videoSPA.switchSection('playback', { videoId: state.videoId });
            } else {
                window.location.reload();
            }
        }

        function pollConversion(jobId) {
            if (state.destroyed || isStale()) return;
            fetch('/api/video/manage/convert/status/' + encodeURIComponent(jobId))
                .then(function(r) { return r.json(); })
                .then(function(status) {
                    if (state.destroyed || isStale()) return;
                    if (!status || typeof status !== 'object') {
                        scheduleNextPoll(jobId);
                        return;
                    }
                    updateProgress(status.message || 'Converting video...', status.progressPercent || 0);
                    var st = (status.status || '').toUpperCase();
                    if (st === 'COMPLETED') {
                        conversionCompleted();
                    } else if (st === 'FAILED') {
                        conversionFailed(status.errorMessage || 'Conversion failed');
                    } else {
                        scheduleNextPoll(jobId);
                    }
                })
                .catch(function(err) {
                    console.error('[ConversionGate] Status poll failed:', err);
                    if (state.destroyed || isStale()) return;
                    scheduleNextPoll(jobId);
                });
        }

        function startConversion() {
            if (state.destroyed || isStale()) return;
            fetch('/api/video/manage/convert/' + encodeURIComponent(state.videoId), { method: 'POST' })
                .then(function(r) { return r.json(); })
                .then(function(data) {
                    if (state.destroyed || isStale()) return;
                    if (data && data.jobId) {
                        state.jobId = data.jobId;
                        if (state.container) state.container.dataset.conversionJobId = data.jobId;
                        pollConversion(data.jobId);
                    } else {
                        conversionFailed('No conversion job was created');
                    }
                })
                .catch(function(err) {
                    console.error('[ConversionGate] Conversion start failed:', err);
                    conversionFailed('Could not start conversion');
                });
        }

        function conversionCompleted() {
            if (state.destroyed) return;
            stopPolling();
            state.blocking = false;
            hideOverlay();
            if (window.Toast) {
                window.Toast.progress('Conversion complete - loading video...', 100, { id: CONVERSION_TOAST_ID });
            }
            setTimeout(function() {
                if (state.destroyed) return;
                reloadFragment();
            }, 800);
        }

        function conversionFailed(message) {
            if (state.destroyed) return;
            stopPolling();
            state.blocking = false;
            hideOverlay();
            if (window.Toast) {
                window.Toast.hideToast(CONVERSION_TOAST_ID);
                window.Toast.error('Conversion failed: ' + message);
            }
        }

        function destroy() {
            stopPolling();
            state.destroyed = true;
            state.blocking = false;
            hideOverlay();
            if (window.Toast) {
                window.Toast.hideToast(CONVERSION_TOAST_ID);
            }
            state.container = null;
            state.videoId = null;
            state.jobId = null;
        }

        function run() {
            state.destroyed = false;
            var container = findContainer();
            if (!container) {
                state.blocking = false;
                return;
            }
            // Player init IIFEs re-invoke init() every 50ms while they poll for
            // their player library. Re-evaluating unchanged fragment state would
            // rebuild the overlay (resetting its progress bar) and re-fire the
            // failure toast on every tick, so skip when nothing changed.
            var runKey = (container.dataset.videoId || '') + '|' +
                (container.dataset.needsConversion || '') + '|' +
                (container.dataset.conversionStatus || '');
            if (lastRunKey === runKey && !isStale()) return;
            lastRunKey = runKey;

            state.videoId = container.dataset.videoId;
            state.jobId = container.dataset.conversionJobId || '';

            var needsConversion = container.dataset.needsConversion === 'true';

            if (!needsConversion || isNativeHevcEligible() || isNativeAv1Eligible()) {
                state.blocking = false;
                return;
            }

            var conversionStatus = (container.dataset.conversionStatus || '').toUpperCase();

            /* Defensive handling of a stale snapshot: FAILED → don't block (player will
             * surface its own error), COMPLETED → don't block (file is already converted). */
            if (conversionStatus === 'FAILED') {
                state.blocking = false;
                if (window.Toast) window.Toast.error('Conversion failed: ' + (container.dataset.conversionStatus || ''));
                return;
            }
            if (conversionStatus === 'COMPLETED') {
                state.blocking = false;
                return;
            }

            /* Block: show the overlay/toast and drive the job until it completes. */
            state.blocking = true;
            showOverlay();

            if (state.jobId) {
                updateProgress('Converting video...', 0);
                pollConversion(state.jobId);
            } else {
                /* Backend race: no job was started yet. Kick it off (idempotent) then poll. */
                startConversion();
            }
        }

        window.ConversionGate = {
            isBlocking: function() { return state.blocking; },
            init: function() { run(); },
            destroy: function() { destroy(); }
        };

        /* The fragment scripts run after the container is already in the DOM,
         * so auto-invoke immediately if we can find it. */
        if (findContainer()) {
            window.ConversionGate.init();
        }
    })(window);
}
