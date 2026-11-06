(function(window) {
    'use strict';

    window.noop = window.noop || function() {};

    if (!window.showToast) {
        window.showToast = function(msg, type) {
            try {
                if (window.Toast && typeof window.Toast[type === 'error' ? 'error' : (type === 'success' ? 'success' : 'info')] === 'function') {
                    window.Toast[type === 'error' ? 'error' : (type === 'success' ? 'success' : 'info')](msg);
                    return;
                }
            } catch (e) {}
            console.log('[toast:' + (type || 'info') + ']', msg);
        };
    }

    function getPlayerContainer() {
        var cp = document.getElementById('customPlayer');
        if (cp && cp.dataset && cp.dataset.videoId) return cp;
        return document.querySelector('#player-modal-content [data-series-title], #player-modal-content [data-collection-id]');
    }

    function getSubtitleSearchVideoId() {
        var c = getPlayerContainer();
        return c && c.dataset.videoId ? c.dataset.videoId : null;
    }

    function escapeHtml(str) {
        return String(str == null ? '' : str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function formatFileSize(bytes) {
        if (bytes == null) return '';
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
        return (bytes / 1048576).toFixed(1) + ' MB';
    }

    function readFileAsBase64(file) {
        return new Promise(function(resolve, reject) {
            var reader = new FileReader();
            reader.onload = function() { resolve(reader.result); };
            reader.onerror = function() { reject(new Error('Failed to read file')); };
            reader.readAsDataURL(file);
        });
    }

    function refreshPlayerSubtitleTracks() {
        if (window.currentPlayerInstance && typeof window.currentPlayerInstance.loadSubtitles === 'function') {
            window.currentPlayerInstance.loadSubtitles(true);
        }
    }

    function openSubtitleSearchSidebar() {
        var sidebar = document.getElementById('subtitle-search-sidebar');
        var backdrop = document.getElementById('subtitle-search-backdrop');
        var toggleBtn = document.getElementById('subtitle-search-toggle');
        if (sidebar) sidebar.classList.add('open');
        if (backdrop) backdrop.classList.add('active');
        if (toggleBtn) toggleBtn.classList.add('active');
        var aiTab = document.getElementById('subtitle-search-ai-tab');
        if (aiTab && aiTab.style.display !== 'none') loadSubtitleAiAudioTracks();
        if (aiTab && aiTab.style.display !== 'none') loadSidebarTranslateTracks();
    }

    function closeSubtitleSearchSidebar() {
        var sidebar = document.getElementById('subtitle-search-sidebar');
        var backdrop = document.getElementById('subtitle-search-backdrop');
        var toggleBtn = document.getElementById('subtitle-search-toggle');
        if (sidebar) sidebar.classList.remove('open');
        if (backdrop) backdrop.classList.remove('active');
        if (toggleBtn) toggleBtn.classList.remove('active');
    }

    function toggleSubtitleSearchSidebar() {
        var sidebar = document.getElementById('subtitle-search-sidebar');
        if (!sidebar) return;
        if (sidebar.classList.contains('open')) {
            closeSubtitleSearchSidebar();
            return;
        }
        openSubtitleSearchSidebar();
    }

    function switchSubtitleSearchTab(tab) {
        var tabs = ['local', 'upload', 'ai'];
        tabs.forEach(function(t) {
            var content = document.getElementById('subtitle-search-' + t + '-tab');
            var btn = document.querySelector('[data-click="switchSubtitleSearchTab:' + t + '"]');
            if (content) content.style.display = t === tab ? 'flex' : 'none';
            if (btn) btn.classList.toggle('active', t === tab);
        });
        if (tab === 'local') scanLocalSubtitles();
        if (tab === 'ai') loadSubtitleAiAudioTracks();
        if (tab === 'ai') loadSidebarTranslateTracks();
    }

    async function loadSubtitleAiAudioTracks() {
        var sel = document.getElementById('subtitleSearchAiAudio');
        if (!sel) return;
        var videoId = getSubtitleSearchVideoId();
        var prev = sel.value;
        sel.innerHTML = '<option value="">Default audio track</option>';
        if (!videoId) return;
        try {
            var resp = await fetch('/api/video/' + encodeURIComponent(videoId) + '/audio-tracks');
            if (!resp.ok) return;
            var json = await resp.json();
            var tracks = json.data || [];
            tracks.forEach(function(track, idx) {
                var label = track.displayName || track.languageName || track.languageCode || ('Audio ' + (idx + 1));
                var opt = document.createElement('option');
                opt.value = track.trackIndex != null ? track.trackIndex : idx;
                opt.textContent = label;
                sel.appendChild(opt);
            });
            if (prev && Array.prototype.some.call(sel.options, function(o) { return o.value === prev; })) sel.value = prev;
        } catch (e) {
            console.warn('Failed to load AI audio tracks:', e);
        }
    }

    var _aiPollInterval = null;
    var _aiCancelRequested = false;

    async function generateAiSubtitles() {
        var videoId = getSubtitleSearchVideoId();
        if (!videoId) {
            window.showToast('No video selected', 'error');
            return;
        }
        var btn = document.getElementById('startAiSidebarBtn');
        if (btn) {
            btn.classList.add('loading');
            btn.disabled = true;
        }
        var langSelect = document.getElementById('subtitleSearchAiLang');
        var lang = langSelect ? langSelect.value : 'en';
        var audioSel = document.getElementById('subtitleSearchAiAudio');
        var audioTrack = audioSel && audioSel.value !== '' ? audioSel.value : null;
        var url = '/api/video/subtitles/' + videoId + '/generate?language=' + encodeURIComponent(lang);
        if (audioTrack != null) url += '&audioTrack=' + encodeURIComponent(audioTrack);
        try {
            var resp = await fetch(url, { method: 'POST' });
            if (resp.status === 503) {
                window.showToast('Parakeet AI is not available on this server', 'error');
                resetAiGenerationUI();
                return;
            }
            if (!resp.ok) {
                var data = await resp.json().catch(function() { return {}; });
                window.showToast(data.error || 'Failed to start AI generation', 'error');
                resetAiGenerationUI();
                return;
            }
            var progress = document.getElementById('subtitleAiGenerationProgress');
            if (progress) progress.style.display = 'flex';
            var statusEl = document.getElementById('subtitleAiGenerationStatus');
            if (statusEl) statusEl.textContent = 'Initializing...';
            if (_aiPollInterval === null) {
                _aiPollInterval = setInterval(pollAiGenerationStatus, 2000);
            }
        } catch (e) {
            console.error('Failed to start AI generation:', e);
            window.showToast('Failed to start AI generation: ' + e.message, 'error');
            resetAiGenerationUI();
        }
    }

    async function pollAiGenerationStatus() {
        var videoId = getSubtitleSearchVideoId();
        if (!videoId) return;
        try {
            var resp = await fetch('/api/video/subtitles/' + videoId + '/generate/status');
            if (!resp.ok) return;
            var data = await resp.json();
            var statusEl = document.getElementById('subtitleAiGenerationStatus');
            if (statusEl) {
                var stage = data.stage || 'generating';
                var label = stage.charAt(0).toUpperCase() + stage.slice(1);
                statusEl.textContent = data.progress != null ? label + '... ' + Math.round(data.progress) + '%' : label + '...';
            }
            if (data.running === false) {
                if (_aiPollInterval !== null) {
                    clearInterval(_aiPollInterval);
                    _aiPollInterval = null;
                }
                if (_aiCancelRequested) {
                    _aiCancelRequested = false;
                    resetAiGenerationUI();
                    return;
                }
                resetAiGenerationUI();
                if (data.error) {
                    window.showToast(data.error, 'error');
                    if (statusEl) statusEl.textContent = 'Failed: ' + data.error;
                } else {
                    window.showToast('AI subtitles generated!', 'success');
                    refreshPlayerSubtitleTracks();
                }
            }
        } catch (e) {
            console.error('Failed to poll AI generation status:', e);
        }
    }

    async function cancelAiGeneration() {
        var videoId = getSubtitleSearchVideoId();
        if (_aiPollInterval !== null) {
            clearInterval(_aiPollInterval);
            _aiPollInterval = null;
        }
        _aiCancelRequested = true;
        var btn = document.getElementById('cancelAiSidebarBtn');
        if (btn) btn.classList.add('loading');
        try {
            if (videoId) {
                await fetch('/api/video/subtitles/' + videoId + '/generate/cancel', { method: 'POST' });
            }
            window.showToast('Generation cancelled', 'info');
        } catch (e) {
            console.error('Failed to cancel AI generation:', e);
        } finally {
            if (btn) btn.classList.remove('loading');
            resetAiGenerationUI();
        }
    }

    function resetAiGenerationUI() {
        var progress = document.getElementById('subtitleAiGenerationProgress');
        if (progress) progress.style.display = 'none';
        var statusEl = document.getElementById('subtitleAiGenerationStatus');
        if (statusEl) statusEl.textContent = 'Generating subtitles...';
        var btn = document.getElementById('startAiSidebarBtn');
        if (btn) {
            btn.classList.remove('loading');
            btn.disabled = false;
        }
    }

    async function loadSidebarTranslateTracks() {
        var sel = document.getElementById('subtitleSearchTranslateTrack');
        var btn = document.getElementById('startAiTranslateBtn');
        if (!sel) return;
        sel.innerHTML = '<option value="">Loading...</option>';
        if (btn) btn.disabled = true;
        var videoId = getSubtitleSearchVideoId();
        if (!videoId) return;
        try {
            var resp = await fetch('/api/video/subtitles/' + videoId);
            if (!resp.ok) throw new Error('HTTP ' + resp.status);
            var json = await resp.json();
            var tracks = json.tracks || json.data || [];
            if (!tracks || tracks.length === 0) {
                sel.innerHTML = '<option value="">No existing subtitles</option>';
                if (btn) btn.disabled = true;
                return;
            }
            sel.innerHTML = tracks.map(function(t) {
                var label = (t.displayName || t.languageName || t.languageCode || t.filename || ('Track ' + t.id)) + (t.isEmbedded ? ' (embedded)' : '');
                return '<option value="' + escapeHtml(String(t.id)) + '">' + escapeHtml(label) + '</option>';
            }).join('');
            if (btn) btn.disabled = false;
        } catch (e) {
            console.error('Failed to load subtitle tracks for translation:', e);
            sel.innerHTML = '<option value="">No existing subtitles</option>';
            if (btn) btn.disabled = true;
            window.showToast('Failed to load subtitle tracks', 'error');
        }
    }

    async function translateExistingSubtitles() {
        var videoId = getSubtitleSearchVideoId();
        if (!videoId) {
            window.showToast('No video selected', 'error');
            return;
        }
        var btn = document.getElementById('startAiTranslateBtn');
        if (btn) {
            btn.classList.add('loading');
            btn.disabled = true;
        }
        var trackSel = document.getElementById('subtitleSearchTranslateTrack');
        var trackId = trackSel ? trackSel.value : '';
        if (!trackId) {
            window.showToast('Select a subtitle track to translate', 'error');
            if (btn) {
                btn.classList.remove('loading');
                btn.disabled = false;
            }
            return;
        }
        var langSelect = document.getElementById('subtitleSearchTranslateLang');
        var lang = langSelect && langSelect.value ? langSelect.value : 'en';
        var url = '/api/video/subtitles/' + videoId + '/translate?trackId=' + encodeURIComponent(trackId) + '&language=' + encodeURIComponent(lang);
        try {
            var resp = await fetch(url, { method: 'POST' });
            if (resp.status === 503) {
                window.showToast('Parakeet AI is not available on this server', 'error');
                resetAiGenerationUI();
                return;
            }
            if (!resp.ok) {
                var data = await resp.json().catch(function() { return {}; });
                window.showToast(data.error || 'Failed to start translation', 'error');
                resetAiGenerationUI();
                return;
            }
            var progress = document.getElementById('subtitleAiGenerationProgress');
            if (progress) progress.style.display = 'flex';
            var statusEl = document.getElementById('subtitleAiGenerationStatus');
            if (statusEl) statusEl.textContent = 'Translating...';
            if (_aiPollInterval === null) {
                _aiPollInterval = setInterval(pollAiGenerationStatus, 2000);
            }
        } catch (e) {
            console.error('Failed to start translation:', e);
            window.showToast('Failed to start translation: ' + e.message, 'error');
            resetAiGenerationUI();
        } finally {
            if (btn) {
                btn.classList.remove('loading');
                btn.disabled = false;
            }
        }
    }

    async function scanLocalSubtitles() {
        var videoId = getSubtitleSearchVideoId();
        var body = document.getElementById('subtitleLocalResultsBody');
        if (!body) return;
        if (!videoId) {
            body.innerHTML = '<div class="subtitle-search-empty">No video selected.</div>';
            return;
        }
        body.innerHTML = '<div class="subtitle-search-status"><i class="fa-solid fa-spinner fa-spin"></i><span>Scanning video folder...</span></div>';
        try {
            var resp = await fetch('/api/video/subtitles/' + videoId + '/local-files');
            if (!resp.ok) throw new Error('HTTP ' + resp.status);
            var files = await resp.json();
            if (!files || files.length === 0) {
                body.innerHTML = '<div class="subtitle-search-empty">No local subtitle files found in the video folder.</div>';
                return;
            }
            body.innerHTML = files.map(function(f) {
                var meta = [escapeHtml(f.format || ''), formatFileSize(f.fileSize)].filter(Boolean).join(' • ');
                return '\
                <button class="episode-sidebar-item subtitle-result-item" data-click="addLocalSubtitle" data-path="' + escapeHtml(f.fullPath) + '">\
                    <div class="subtitle-result-badge">' + escapeHtml(f.languageName || '?') + '</div>\
                    <div class="subtitle-result-main">\
                        <div class="subtitle-result-title">' + escapeHtml(f.filename) + '</div>\
                        <div class="subtitle-result-meta">' + meta + '</div>\
                    </div>\
                    <span class="subtitle-result-download"><i class="fa-solid fa-plus"></i> Add</span>\
                </button>';
            }).join('');
        } catch (e) {
            body.innerHTML = '<div class="subtitle-search-empty">Scan failed — could not read the video folder.</div>';
        }
    }

    async function addLocalSubtitle(el) {
        var videoId = getSubtitleSearchVideoId();
        if (!videoId || !el) return;
        var filePath = el.getAttribute('data-path');
        if (!filePath) return;
        var item = el.classList.contains('subtitle-result-item') ? el : el.closest('.subtitle-result-item');
        var pill = item ? item.querySelector('.subtitle-result-download') : null;
        el.classList.add('loading');
        el.style.pointerEvents = 'none';
        try {
            var resp = await fetch('/api/video/subtitles/' + videoId + '/add-local', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ filePath: filePath })
            });
            if (resp.ok) {
                window.showToast('Subtitle added!', 'success');
                refreshPlayerSubtitleTracks();
                if (item) item.classList.add('added');
                if (pill) pill.innerHTML = '<i class="fa-solid fa-check"></i> Added';
            } else {
                window.showToast('Failed to add subtitle', 'error');
            }
        } catch (e) {
            window.showToast('Failed to add subtitle — ' + e.message, 'error');
        } finally {
            el.classList.remove('loading');
            el.style.pointerEvents = '';
        }
    }

    function subtitleUploadFileSelected(event) {
        var file = event.target.files[0];
        var nameEl = document.getElementById('subtitleUploadFileName');
        var btn = document.getElementById('uploadSubtitleBtn');
        if (!nameEl || !btn) return;
        if (file) {
            nameEl.textContent = file.name;
            btn.disabled = false;
        } else {
            nameEl.textContent = 'No file selected';
            btn.disabled = true;
        }
    }

    async function uploadSubtitleFile(el) {
        var videoId = getSubtitleSearchVideoId();
        var fileInput = document.getElementById('subtitleFileInput');
        var file = fileInput && fileInput.files[0];
        if (!videoId || !file) return;

        var btn = document.getElementById('uploadSubtitleBtn');
        if (btn) {
            btn.classList.add('loading');
            btn.disabled = true;
        }
        try {
            var content = await readFileAsBase64(file);
            var langSelect = document.getElementById('subtitleUploadLanguage');
            var nameInput = document.getElementById('subtitleUploadName');
            var language = langSelect ? langSelect.value : 'en';
            var displayName = nameInput ? nameInput.value.trim() : '';

            var resp = await fetch('/api/video/subtitles/' + videoId + '/upload', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    content: content,
                    filename: file.name,
                    language: language,
                    languageName: displayName || file.name
                })
            });
            var data = await resp.json().catch(function() { return {}; });
            if (resp.ok) {
                window.showToast('Subtitle uploaded!', 'success');
                refreshPlayerSubtitleTracks();
                fileInput.value = '';
                var nameEl = document.getElementById('subtitleUploadFileName');
                if (nameEl) nameEl.textContent = 'No file selected';
                if (nameInput) nameInput.value = '';
            } else {
                window.showToast(data.error || 'Upload failed', 'error');
            }
        } catch (e) {
            console.error('Upload error:', e);
            window.showToast('Upload error: ' + e.message, 'error');
        } finally {
            if (btn) {
                btn.classList.remove('loading');
                btn.disabled = true;
            }
        }
    }

    function initSubtitleSearchSidebar() {
        var toggleBtn = document.getElementById('subtitle-search-toggle');
        if (!toggleBtn) return;
        var videoId = getSubtitleSearchVideoId();
        if (!videoId) {
            toggleBtn.style.display = 'none';
            return;
        }
        toggleBtn.style.display = '';
    }

    // Event delegation for data-click / data-change
    document.addEventListener('click', function(e) {
        var el = e.target;
        while (el && el !== document) {
            var action = el.getAttribute && el.getAttribute('data-click');
            if (action) {
                if (el.hasAttribute && el.hasAttribute('data-stop-propagation')) {
                    e.stopPropagation();
                }
                var colonIdx = action.indexOf(':');
                var fn = colonIdx >= 0 ? action.substring(0, colonIdx) : action;
                var arg = colonIdx >= 0 ? action.substring(colonIdx + 1) : null;
                var callFn = window[fn];
                if (typeof callFn === 'function') {
                    if (arg !== null) {
                        callFn(arg, el);
                    } else {
                        callFn(el);
                    }
                }
                break;
            }
            el = el.parentElement;
        }
    });

    document.addEventListener('change', function(e) {
        var el = e.target;
        var action = el.getAttribute && el.getAttribute('data-change');
        if (action) {
            var callFn = window[action];
            if (typeof callFn === 'function') {
                callFn(el.value, el);
            }
        }
    });

    // Expose globals used by HTML/data-click
    window.getPlayerContainer = getPlayerContainer;
    window.getSubtitleSearchVideoId = getSubtitleSearchVideoId;
    window.openSubtitleSearchSidebar = openSubtitleSearchSidebar;
    window.closeSubtitleSearchSidebar = closeSubtitleSearchSidebar;
    window.toggleSubtitleSearchSidebar = toggleSubtitleSearchSidebar;
    window.switchSubtitleSearchTab = switchSubtitleSearchTab;
    window.loadSubtitleAiAudioTracks = loadSubtitleAiAudioTracks;
    window.generateAiSubtitles = generateAiSubtitles;
    window.pollAiGenerationStatus = pollAiGenerationStatus;
    window.cancelAiGeneration = cancelAiGeneration;
    window.resetAiGenerationUI = resetAiGenerationUI;
    window.loadSidebarTranslateTracks = loadSidebarTranslateTracks;
    window.translateExistingSubtitles = translateExistingSubtitles;
    window.scanLocalSubtitles = scanLocalSubtitles;
    window.addLocalSubtitle = addLocalSubtitle;
    window.subtitleUploadFileSelected = subtitleUploadFileSelected;
    window.uploadSubtitleFile = uploadSubtitleFile;
    window.initSubtitleSearchSidebar = initSubtitleSearchSidebar;
    window.refreshPlayerSubtitleTracks = refreshPlayerSubtitleTracks;

    // Inject sidebar HTML into root if present
    function inject() {
        var root = document.getElementById('subtitle-search-sidebar-root');
        if (root && typeof window.createSubtitleSearchSidebarHTML === 'function' && !root.innerHTML.trim()) {
            root.innerHTML = window.createSubtitleSearchSidebarHTML();
        }
        initSubtitleSearchSidebar();
    }
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', inject);
    } else {
        inject();
    }
})(window);
