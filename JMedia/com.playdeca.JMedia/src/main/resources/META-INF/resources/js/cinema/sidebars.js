// Cinema view: Collection sidebar and episode sidebar rendering/navigation.
// Split out of cinema-mode.js. Loaded by views/video.html in a fixed order;
// these are classic scripts, so they share one global scope.
// ============================================================
// Collection Sidebar
// ============================================================

async function openCollectionSidebar(container) {
  const sidebar = document.getElementById('episode-sidebar');
  const backdrop = document.getElementById('episode-sidebar-backdrop');
  const toggleBtn = document.getElementById('episode-sidebar-toggle');
  if (!sidebar || !backdrop) return;

  const collectionId = container.dataset.collectionId;
  const collectionName = container.dataset.collectionName || 'Collection';
  const currentVideoId = container.dataset.videoId;
  if (!collectionId) return;

  const titleEl = sidebar.querySelector('.episode-sidebar-title');
  if (titleEl) titleEl.textContent = collectionName;

  const seasonsWrap = sidebar.querySelector('.episode-sidebar-seasons-wrap');
  if (seasonsWrap) seasonsWrap.style.display = 'none';

  let entries;
  try {
    const resp = await fetch(`/api/collections/${collectionId}/entries`);
    if (!resp.ok) {
      console.warn('[cinema] collection sidebar fetch failed with HTTP ' + resp.status);
      return;
    }
    const body = await resp.json();
    entries = (body && body.success ? body.data : body);
    if (!Array.isArray(entries)) return;
  } catch (e) {
    console.warn('[cinema] collection sidebar fetch failed', e);
    return;
  }
  if (!entries.length) return;
  // Switching episodes inside the collection while this request was in flight
  // would otherwise render the list centred on a stale video.
  if (container.dataset.videoId !== currentVideoId) return;

  renderCollectionSidebarList(entries, currentVideoId);

  sidebar.classList.add('open');
  backdrop.classList.add('active');
  if (toggleBtn) toggleBtn.classList.add('active');
}

function renderCollectionSidebarList(entries, currentVideoId) {
  const container = document.getElementById('episode-sidebar-list');
  if (!container) return;

  container.innerHTML = entries.map(entry => {
    const videoId = entry.videoId || entry.externalVideoId;
    const isCurrent = String(videoId) === String(currentVideoId);
    const orderNum = entry.orderIndex != null ? entry.orderIndex : '';
    const title = entry.title || `Entry ${orderNum}`;
    const progressClass = entry.watched ? 'watched' : (entry.watchProgress > 0 ? 'partial' : '');
    const progressDot = progressClass
      ? `<span class="episode-sidebar-progress-dot ${progressClass}"></span>`
      : '';

    return `<button type="button" class="episode-sidebar-item${isCurrent ? ' active' : ''}" onclick="playSidebarCollectionEntry(${videoId})">
      <div class="episode-sidebar-info" style="width:100%">
        <div class="episode-sidebar-top-row">
          <span class="episode-sidebar-ep-number">#${orderNum}</span>
          ${progressDot}
        </div>
        <div class="episode-sidebar-ep-title">${escapeHtml(title)}</div>
      </div>
    </button>`;
  }).join('');

  const activeEl = container.querySelector('.episode-sidebar-item.active');
  if (activeEl) {
    const target = activeEl.offsetTop - container.clientHeight / 2 + activeEl.clientHeight / 2;
    container.scrollTop = Math.max(0, target);
  }
}

function switchPlayerVideo(videoId) {
  const container = getPlayerContainer();
  const collectionId = container ? container.dataset.collectionId : null;
  closeEpisodeSidebar();
  closePlayerModal();
  setTimeout(() => openPlayerModal(videoId, collectionId), 100);
}

function playSidebarCollectionEntry(videoId) {
  switchPlayerVideo(videoId);
}

// Audio language pill in sidebar header
function toggleSidebarAudioMenu(e) {
  if (e && typeof e.stopPropagation === 'function') e.stopPropagation();
  var menu = document.getElementById('sidebarAudioMenu');
  if (!menu) return;
  menu.style.display = menu.style.display === 'none' ? 'block' : 'none';
}

// Close audio menu when clicking outside
_trackedAddEventListener(document, 'click', function(e) {
  var menu = document.getElementById('sidebarAudioMenu');
  var btn = document.getElementById('sidebarAudioBtn');
  if (menu && menu.style.display !== 'none' && !menu.contains(e.target) && !btn.contains(e.target)) {
    menu.style.display = 'none';
  }
});

function renderEpisodeSidebarSeasons(seasonKeys, activeSeasonKey) {
  const container = document.getElementById('episode-sidebar-seasons');
  if (!container) return;

  if (seasonKeys.length <= 1) {
    container.innerHTML = '';
    container.style.display = 'none';
    return;
  }

  container.style.display = 'flex';
  container.innerHTML = seasonKeys.map(key => {
    const isActive = key === activeSeasonKey;
    return `<button class="episode-sidebar-season-btn${isActive ? ' active' : ''}"
      data-sk="${key}"
      onclick="switchEpisodeSidebarSeason(this.dataset.sk)">${getGroupLabel(key)}</button>`;
  }).join('');
  updateSeasonScrollButtons();
}

/**
 * The player re-points itself at the next episode without navigating, so the
 * container dataset and the open episode sidebar kept the previous episode.
 * Update both so the highlighted item follows what is actually playing.
 */
function syncEpisodeSidebarForVideo(videoId) {
  const container = getPlayerContainer();
  if (!container) return;
  container.dataset.videoId = String(videoId);
  const sidebar = document.getElementById('episode-sidebar');
  if (!sidebar || !sidebar.classList.contains('open')) return;
  const seriesTitle = container.dataset.seriesTitle;
  const eps = (seriesTitle && typeof _episodesCache !== 'undefined') ? _episodesCache.get(seriesTitle) : null;
  if (!Array.isArray(eps)) return;
  const ep = eps.find(function (e) { return String(e.id) === String(videoId); });
  const sn = ep && ep.seasonNumber != null ? ep.seasonNumber : 1;
  const ct = ep && ep.contentType ? ep.contentType : 'episode';
  container.dataset.seasonNumber = String(sn);
  switchEpisodeSidebarSeason(sn + ':' + ct);
}

function switchEpisodeSidebarSeason(seasonKey) {
  // Parse the key to get season number and contentType
  const [snStr, ct] = seasonKey.split(':');
  const sn = parseInt(snStr);

  // Update active tab
  document.querySelectorAll('.episode-sidebar-season-btn').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.sk === seasonKey);
  });

  // Auto-center the active season pill
  var seasonsContainer = document.getElementById('episode-sidebar-seasons');
  if (seasonsContainer) {
    var activeBtn = seasonsContainer.querySelector('.episode-sidebar-season-btn.active');
    if (activeBtn) {
      activeBtn.scrollIntoView({ behavior: 'smooth', inline: 'center', block: 'nearest' });
    }
  }

  // Re-gather current context and re-render list for the selected season
  const container = getPlayerContainer();
  if (!container) return;
  const seriesTitle = container.dataset.seriesTitle;
  const currentVideoId = container.dataset.videoId;
  if (!seriesTitle) return;

  const allSeriesEps = _episodesCache.get(seriesTitle) || [];
  const episodes = allSeriesEps
    .filter(v => (v.contentType || 'episode') === ct)
    .sort((a, b) => {
      const sa = a.seasonNumber ?? 1, sb = b.seasonNumber ?? 1;
      const ea = a.episodeNumber || 0, eb = b.episodeNumber || 0;
      return sa - sb || ea - eb;
    });

  renderEpisodeSidebarList(episodes, currentVideoId, seasonKey);
}

function renderEpisodeSidebarList(episodes, currentVideoId, seasonKey) {
  const container = document.getElementById('episode-sidebar-list');
  if (!container) return;

  // Parse seasonKey to extract the numeric season for filtering
  const [snStr] = seasonKey.split(':');
  const targetSeason = parseInt(snStr);

  // Caller already passes the correct content-type group; filter only by seasonNumber
  const filtered = episodes.filter(ep => (ep.seasonNumber != null ? ep.seasonNumber : 1) === targetSeason);

  container.innerHTML = filtered.map(ep => {
    const epId = String(ep.id);
    const epNum = ep.episodeNumber || 0;
    const epTitle = ep.episodeTitle || ep.title || `Episode ${epNum}`;
    const dur = formatDuration(ep.duration);
    const isCurrent = epId === String(currentVideoId);
    const thumbSrc = getThumbnailUrl(ep.id);
    const description = ep.description || '';
    const overlayHtml = isCurrent
      ? '<div class="episode-sidebar-thumb-overlay"><div class="episode-sidebar-thumb-tint"></div></div>'
      : '<div class="episode-sidebar-thumb-overlay"></div>';

    return `<button type="button" class="episode-sidebar-item${isCurrent ? ' active' : ''}" onclick="playSidebarEpisode(${ep.id})">
      <div class="episode-sidebar-thumb">
        <img src="${thumbSrc}" alt="${escapeHtml(epTitle)}" loading="lazy">
        ${overlayHtml}
      </div>
      <div class="episode-sidebar-info">
        <div class="episode-sidebar-top-row">
          <span class="episode-sidebar-ep-number">E${epNum}</span>
          ${renderContentTypeBadge(ep.contentType)}
          ${dur ? `<span class="episode-sidebar-duration-badge">${dur}</span>` : ''}
        </div>
        <div class="episode-sidebar-ep-title">${escapeHtml(epTitle)}</div>
        ${description ? `<div class="episode-sidebar-ep-description">${escapeHtml(description)}</div>` : ''}
      </div>
    </button>`;
  }).join('');

  const activeEl = container.querySelector('.episode-sidebar-item.active');
  if (activeEl) {
    const target = activeEl.offsetTop - container.clientHeight / 2 + activeEl.clientHeight / 2;
    container.scrollTop = Math.max(0, target);
  }
}

// --- Season pill scroll affordance ---
function updateSeasonScrollButtons() {
  var wrap = document.querySelector('.episode-sidebar-seasons-wrap');
  var container = document.getElementById('episode-sidebar-seasons');
  var leftBtn = document.getElementById('season-scroll-left');
  var rightBtn = document.getElementById('season-scroll-right');
  if (!container || !leftBtn || !rightBtn) return;

  var hasOverflow = container.scrollWidth > container.clientWidth;

  if (!hasOverflow) {
    leftBtn.classList.add('hidden');
    rightBtn.classList.add('hidden');
    if (wrap) { wrap.classList.remove('fade-left', 'fade-right'); }
    return;
  }

  leftBtn.classList.remove('hidden');
  rightBtn.classList.remove('hidden');

  if (wrap) {
    wrap.classList.toggle('fade-left', container.scrollLeft > 4);
    wrap.classList.toggle('fade-right', container.scrollLeft + container.clientWidth < container.scrollWidth - 4);
  }
}

// Season scroll arrow handlers (used by onclick on buttons)
function scrollSeasonsLeft() {
  var c = document.getElementById('episode-sidebar-seasons');
  if (c) c.scrollBy({ left: -200, behavior: 'smooth' });
}
function scrollSeasonsRight() {
  var c = document.getElementById('episode-sidebar-seasons');
  if (c) c.scrollBy({ left: 200, behavior: 'smooth' });
}

function playSidebarEpisode(videoId) {
  switchPlayerVideo(videoId);
}

