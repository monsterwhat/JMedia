// Cinema view: Subtitle search sidebar: track selection, AI generation, translation, local upload.
// Split out of cinema-mode.js. Loaded by views/video.html in a fixed order;
// these are classic scripts, so they share one global scope.
// ============================================================
// Subtitle Search Sidebar
// ============================================================

function getSubtitleSearchVideoId() {
  var c = getPlayerContainer();
  return c && c.dataset.videoId ? c.dataset.videoId : null;
}

/**
 * Available subtitle tracks for the current video, shown at the top of the
 * subtitle side panel. Selecting one drives the player's own menu entry so the
 * <track> element, ?start= offset and lastSelectedTrackId all stay consistent.
 */
async function loadSidebarSubtitleTracks() {
  var list = document.getElementById('subtitleSidebarTracks');
  if (!list) return;
  var videoId = getSubtitleSearchVideoId();
  if (!videoId) { list.innerHTML = ''; return; }
  try {
    var resp = await fetch('/api/video/subtitles/' + encodeURIComponent(videoId));
    // The viewer may have moved on; these rows belong to the old episode.
    if (getSubtitleSearchVideoId() !== videoId) return;
    if (!resp.ok) { list.innerHTML = ''; return; }
    var json = await resp.json();
    if (getSubtitleSearchVideoId() !== videoId) return;
    var tracks = json.tracks || [];
    var p = window.player || window.currentPlayerInstance;
    var activeId = (p && p.lastSelectedTrackId != null) ? String(p.lastSelectedTrackId)
      : (localStorage.getItem('jmedia_last_track_' + videoId) || '');
    var rows = ['<button type="button" class="episode-sidebar-item' + ((activeId === 'off' || activeId === '') ? ' active' : '') + '" data-click="selectSidebarSubtitle" data-sub-id="off">' +
      '<div class="episode-sidebar-info"><div class="episode-sidebar-ep-title">Off</div>' +
      '<div class="episode-sidebar-ep-description">Hide subtitles</div></div></button>'];
    tracks.forEach(function (t) {
      var name = t.displayName || t.languageName || t.filename || ('Track ' + t.id);
      var isActive = String(t.id) === activeId;
      rows.push('<button type="button" class="episode-sidebar-item' + (isActive ? ' active' : '') + '" data-click="selectSidebarSubtitle" data-sub-id="' + t.id + '">' +
        '<div class="subtitle-result-badge">' + escapeHtml((t.languageCode || '').toUpperCase()) + '</div>' +
        '<div class="episode-sidebar-info"><div class="episode-sidebar-ep-title">' + escapeHtml(name) + '</div>' +
        '<div class="episode-sidebar-ep-description">' + escapeHtml(t.format || '') + (t.isForced ? ' - forced' : '') + '</div></div></button>');
    });
    list.innerHTML = rows.join('');
  } catch (e) {
    console.warn('[cinema] sidebar subtitle tracks failed', e);
    list.innerHTML = '';
  }
}

function selectSidebarSubtitle(el) {
  var id = el && el.getAttribute ? el.getAttribute('data-sub-id') : null;
  if (!id) return;
  var opt = document.querySelector('#subtitleList .subtitle-option[data-id="' + id + '"]');
  if (opt) {
    opt.click();
  } else {
    var ctrl = window.player && window.player.subtitleController;
    if (ctrl) {
      if (id === 'off') ctrl.turnOffSubtitles();
      else if (window.player.selectSubtitle) window.player.selectSubtitle(id);
    }
  }
  setTimeout(loadSidebarSubtitleTracks, 300);
}

function openSubtitleSearchSidebar() {
  var sidebar = document.getElementById('subtitle-search-sidebar');
  var backdrop = document.getElementById('subtitle-search-backdrop');
  var toggleBtn = document.getElementById('subtitle-search-toggle');
  if (sidebar) sidebar.classList.add('open');
  if (backdrop) backdrop.classList.add('active');
  if (toggleBtn) toggleBtn.classList.add('active');
  // All three tab bodies ship with display:none, so without activating one the
  // panel opens showing only the header and tabs. Default to Local Files (which
  // also runs the folder scan) unless a tab is already visible.
  var visibleTab = ['local', 'upload', 'ai'].filter(function (t) {
    var el = document.getElementById('subtitle-search-' + t + '-tab');
    return el && getComputedStyle(el).display !== 'none';
  });
  if (visibleTab.length === 0) switchSubtitleSearchTab('local');
  loadSidebarSubtitleTracks();
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
  // The AI status poll has no natural end: left running it keeps hitting the
  // server every 2s after the panel (and possibly the player) is gone.
  stopAiGenerationPoll();
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
// Which video the running poll belongs to. Without it the 2s interval silently
// followed the viewer to the next episode and polled that video's status.
var _aiPollVideoId = null;

function stopAiGenerationPoll() {
  if (_aiPollInterval !== null) {
    clearInterval(_aiPollInterval);
    _aiPollInterval = null;
  }
  _aiPollVideoId = null;
}

async function generateAiSubtitles() {
  var videoId = getSubtitleSearchVideoId();
  if (!videoId) {
    if (window.showToast) window.showToast('No video selected', 'error');
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
      if (window.showToast) window.showToast('Parakeet AI is not available on this server', 'error');
      resetAiGenerationUI();
      return;
    }
    if (!resp.ok) {
      var data = await resp.json().catch(function() { return {}; });
      if (window.showToast) window.showToast(data.error || 'Failed to start AI generation', 'error');
      resetAiGenerationUI();
      return;
    }
    var progress = document.getElementById('subtitleAiGenerationProgress');
    if (progress) progress.style.display = 'flex';
    var statusEl = document.getElementById('subtitleAiGenerationStatus');
    if (statusEl) statusEl.textContent = 'Initializing...';
    if (_aiPollInterval === null) {
      _aiPollVideoId = videoId;
      _aiPollInterval = setInterval(pollAiGenerationStatus, 2000);
    }
  } catch (e) {
    console.error('Failed to start AI generation:', e);
    if (window.showToast) window.showToast('Failed to start AI generation: ' + e.message, 'error');
    resetAiGenerationUI();
  }
}

async function pollAiGenerationStatus() {
  var videoId = getSubtitleSearchVideoId();
  if (!videoId) { stopAiGenerationPoll(); return; }
  if (_aiPollVideoId && String(videoId) !== String(_aiPollVideoId)) {
    // The viewer moved to another episode. This generation belongs to the old one:
    // stop polling and stop showing its progress on the new episode.
    stopAiGenerationPoll();
    resetAiGenerationUI();
    return;
  }
  try {
    var resp = await fetch('/api/video/subtitles/' + videoId + '/generate/status');
    if (!resp.ok) return;
    var data = await resp.json();
    if (!_aiPollInterval) return; // stopped while this request was in flight
    var statusEl = document.getElementById('subtitleAiGenerationStatus');
    if (statusEl) {
      var stage = data.stage || 'generating';
      var label = stage.charAt(0).toUpperCase() + stage.slice(1);
      statusEl.textContent = data.progress != null ? label + '... ' + Math.round(data.progress) + '%' : label + '...';
    }
    if (data.running === false) {
      stopAiGenerationPoll();
      resetAiGenerationUI();
      if (data.error) {
        if (window.showToast) window.showToast(data.error, 'error');
        if (statusEl) statusEl.textContent = 'Failed: ' + data.error;
      } else {
        if (window.showToast) window.showToast('AI subtitles generated!', 'success');
        refreshPlayerSubtitleTracks();
      }
    }
  } catch (e) {
    console.error('Failed to poll AI generation status:', e);
  }
}

async function cancelAiGeneration() {
  var videoId = getSubtitleSearchVideoId();
  stopAiGenerationPoll();
  var btn = document.getElementById('cancelAiSidebarBtn');
  if (btn) btn.classList.add('loading');
  try {
    if (videoId) {
      await fetch('/api/video/subtitles/' + videoId + '/generate/cancel', { method: 'POST' });
    }
    if (window.showToast) window.showToast('Generation cancelled', 'info');
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
    if (window.showToast) window.showToast('Failed to load subtitle tracks', 'error');
  }
}

async function translateExistingSubtitles() {
  var videoId = getSubtitleSearchVideoId();
  if (!videoId) {
    if (window.showToast) window.showToast('No video selected', 'error');
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
    if (window.showToast) window.showToast('Select a subtitle track to translate', 'error');
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
      if (window.showToast) window.showToast('Parakeet AI is not available on this server', 'error');
      resetAiGenerationUI();
      return;
    }
    if (!resp.ok) {
      var data = await resp.json().catch(function() { return {}; });
      if (window.showToast) window.showToast(data.error || 'Failed to start translation', 'error');
      resetAiGenerationUI();
      return;
    }
    var progress = document.getElementById('subtitleAiGenerationProgress');
    if (progress) progress.style.display = 'flex';
    var statusEl = document.getElementById('subtitleAiGenerationStatus');
    if (statusEl) statusEl.textContent = 'Translating...';
    if (_aiPollInterval === null) {
      _aiPollVideoId = videoId;
      _aiPollInterval = setInterval(pollAiGenerationStatus, 2000);
    }
  } catch (e) {
    console.error('Failed to start translation:', e);
    if (window.showToast) window.showToast('Failed to start translation: ' + e.message, 'error');
    resetAiGenerationUI();
  } finally {
    if (btn) {
      btn.classList.remove('loading');
      btn.disabled = false;
    }
  }
}

function escapeHtml(str) {
  return String(str == null ? '' : str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
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

function formatFileSize(bytes) {
  if (bytes == null) return '';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / 1048576).toFixed(1) + ' MB';
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
      if (window.showToast) window.showToast('Subtitle added!', 'success');
      refreshPlayerSubtitleTracks();
      if (item) item.classList.add('added');
      if (pill) pill.innerHTML = '<i class="fa-solid fa-check"></i> Added';
    } else {
      if (window.showToast) window.showToast('Failed to add subtitle', 'error');
    }
  } catch (e) {
    if (window.showToast) window.showToast('Failed to add subtitle — ' + e.message, 'error');
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
      if (window.showToast) window.showToast('Subtitle uploaded!', 'success');
      refreshPlayerSubtitleTracks();
      fileInput.value = '';
      var nameEl = document.getElementById('subtitleUploadFileName');
      if (nameEl) nameEl.textContent = 'No file selected';
      if (nameInput) nameInput.value = '';
    } else {
      if (window.showToast) window.showToast(data.error || 'Upload failed', 'error');
    }
  } catch (e) {
    console.error('Upload error:', e);
    if (window.showToast) window.showToast('Upload error: ' + e.message, 'error');
  } finally {
    if (btn) {
      btn.classList.remove('loading');
      btn.disabled = true;
    }
  }
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
  // TestPlayerFeatures re-fetches tracks and rebuilds #subtitleList.
  // currentPlayerInstance is SimplePlayer-only, so it is a no-op for it.
  if (window.testPlayerFeatures && typeof window.testPlayerFeatures.loadSubtitles === 'function') {
    window.testPlayerFeatures.loadSubtitles(true);
    return;
  }
  if (window.currentPlayerInstance && typeof window.currentPlayerInstance.loadSubtitles === 'function') {
    window.currentPlayerInstance.loadSubtitles(true);
  }
}

async function initSubtitleSearchSidebar() {
  var toggleBtn = document.getElementById('subtitle-search-toggle');
  if (!toggleBtn) return;
  var videoId = getSubtitleSearchVideoId();
  if (!videoId) {
    toggleBtn.style.display = 'none';
    return;
  }
  toggleBtn.style.display = '';
}

