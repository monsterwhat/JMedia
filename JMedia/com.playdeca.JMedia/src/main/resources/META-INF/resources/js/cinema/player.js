// Cinema view: Player modal open/close, splash, episode sidebar shell, player actions (expand, watchlist).
// Split out of cinema-mode.js. Loaded by views/video.html in a fixed order;
// these are classic scripts, so they share one global scope.
// ============================================================
// Actions
// ============================================================
/** Show/hide the global first-paint splash when navigating inside the SPA. */
function splashShow(dark) {
  if (!window.JMediaSplash) return;
  var el = document.getElementById('jmedia-splash');
  if (el && dark !== false) el.classList.add('is-dark');
  window.JMediaSplash.show();
}
function splashHide() {
  if (window.JMediaSplash) window.JMediaSplash.hide();
}

/**
 * Toggle the SPA between the browse chrome and the dedicated player view.
 * The player is a full-page view, not a floating dialog: while it is open the
 * hero / browse sections / dock are hidden so nothing from the library shows
 * behind it, and they are restored when playback closes.
 */
function setPlayerViewActive(active) {
  document.body.classList.toggle('player-view-active', active);
  const hero = document.getElementById('cinema-hero');
  const content = document.querySelector('.cinema-content');
  const dynamic = document.getElementById('section-dynamic');
  const dock = document.querySelector('.cinema-dock');
  const nav = document.querySelector('.cinema-nav');
  if (hero) hero.style.display = active ? 'none' : '';
  if (content) content.style.display = active ? 'none' : '';
  if (dynamic) dynamic.style.display = active ? 'none' : (dynamic.dataset.section ? '' : 'none');
  if (dock) dock.style.display = active ? 'none' : '';
  if (nav) nav.style.display = active ? 'none' : '';
}

async function openPlayerModal(videoId, collectionId) {
  // Supersede any open still in flight. Without this, the awaits below can resume
  // after the user already opened another episode or closed the player, and then
  // overwrite the live modal (or resurrect a closed one) with a stale fragment.
  const openGen = ++_playerOpenGen;
  const isCurrentOpen = () => openGen === _playerOpenGen;

  // B11: teardown before innerHTML — destroys the ghost from the previous episode
  if (typeof currentPlayerInstance !== 'undefined' && currentPlayerInstance) {
    currentPlayerInstance.destroy();
    currentPlayerInstance = null;
  }
  if (window.ConversionGate) window.ConversionGate.destroy();

  const backdrop = document.getElementById('player-modal-backdrop');
  const modal = document.getElementById('player-modal');
  const content = document.getElementById('player-modal-content');
  if (!backdrop || !modal || !content) return;

  backdrop.classList.add('active');
  modal.classList.add('active');
  // Playback is a full-screen experience, not a dialog: expand by default so the
  // player fills the viewport. The topbar still auto-hides and the expand button
  // can still collapse it back to the windowed size.
  modal.classList.add('is-expanded');
  document.body.style.overflow = 'hidden';
  setPlayerViewActive(true);
  splashShow(true);

  // Shareable player URL: /video/watch/{imdb|tmdb}[/s01e02]
  _watchUrlPushed = false;
  _pushWatchUrlForVideo(videoId);

  try {
    // Command the server before loading the fragment so the new player's first
    // state broadcast has the locked id. Only POST when the target differs from
    // the current server video — selectVideo with the SAME id toggles playing
    // (VideoController.java:160-163). POSTing without startTime resumes from the
    // per-video VideoState (VideoController.java:176-181), the same source the
    // fragment's data-start-time reads.
    const cur = await fetch('/api/video/playback/current').then(r => r.json());
    if (!isCurrentOpen()) return;
    if (!cur.video || cur.video.id !== videoId) {
      await fetch('/api/video/playback/play/' + videoId, { method: 'POST' });
      if (!isCurrentOpen()) return;
    }

    let fragmentUrl = `/api/video/ui/playback-fragment?videoId=${videoId}&cinema=true`;
    if (collectionId) fragmentUrl += `&collectionId=${collectionId}`;
    const resp = await fetch(fragmentUrl);
    if (!isCurrentOpen()) return;
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const html = await resp.text();
    if (!isCurrentOpen()) return;

    // innerHTML does NOT execute <script> tags per browser spec.
    // Parse the fragment, strip scripts, inject HTML, then re-create script elements.
    const tmp = document.createElement('div');
    tmp.innerHTML = html;

    const scripts = Array.from(tmp.querySelectorAll('script'));
    scripts.forEach(s => s.remove());

    // Same belt-and-suspenders as closePlayerModal: blank the outgoing episode's
    // media element before replacing the DOM, so the browser drops the connection
    // and the server's transcode for the previous episode actually dies.
    content.querySelectorAll('video').forEach(function(v) {
      try { v.pause(); } catch (e) { console.warn('[cinema] video pause failed during teardown', e); }
      try { v.removeAttribute('src'); } catch (e) { console.warn('[cinema] video src removal failed during teardown', e); }
      try { v.load(); } catch (e) { console.warn('[cinema] video load failed during teardown', e); }
    });
    content.innerHTML = tmp.innerHTML;

    // Re-create scripts as real DOM elements so the browser fetches/executes them
    for (const old of scripts) {
      const el = document.createElement('script');
      if (old.src) {
        for (const attr of old.attributes) el.setAttribute(attr.name, attr.value);
        el.async = false; // preserve document order (Utils ? StateManager ? � ? SimplePlayer)
      } else {
        el.textContent = old.textContent;
      }
      content.appendChild(el);
    }

    // Init episode sidebar after player fragment is loaded
    initEpisodeSidebar();
    initSubtitleSearchSidebar();
    splashHide();
  } catch (err) {
    if (!isCurrentOpen()) return;
    console.error('[player-modal] failed to load playback fragment', err);
    content.innerHTML = `<div style="display:flex;align-items:center;justify-content:center;height:100%;color:rgba(255,255,255,0.5);">Failed to load player</div>`;
    splashHide();
  }
}

function closePlayerModal(reason) {
  // Invalidate any open still in flight so it cannot resurrect the modal after
  // this close (a late fragment would inject a player and start audio).
  _playerOpenGen++;
  // That invalidated open also owned the splash; hide it here or closing while
  // the fragment is loading leaves the overlay stuck over the library.
  splashHide();
  const wasWatchUrl = window.location.pathname.indexOf('/video/watch/') === 0;
  const backdrop = document.getElementById('player-modal-backdrop');
  const modal = document.getElementById('player-modal');
  const content = document.getElementById('player-modal-content');

  try {
    if (window.ConversionGate) window.ConversionGate.destroy();
    if (window.currentPlayerInstance) {
      if (window.currentPlayerInstance.progressReporter) {
        window.currentPlayerInstance.progressReporter.saveNow();
      }
      if (typeof window.currentPlayerInstance.destroy === 'function') {
        window.currentPlayerInstance.destroy();
      } else if (window.currentPlayerInstance.pause) {
        window.currentPlayerInstance.pause();
      }
    }
  } catch (err) {
    // Teardown must never throw into the caller (Escape/popstate handlers), but the
    // failure still needs to be visible: a player that failed to destroy is exactly
    // what leaves audio running after close.
    console.warn('[player-modal] teardown failed during close', err);
  }
  window.currentPlayerInstance = null;
  window.player = null;

  if (content) {
    // B12 belt-and-suspenders: force-abort any media fetch still alive inside
    // the modal. Blanking
    // src + load() guarantees the browser drops the connection, so the server's
    // output.write throws and the ffmpeg remux/transcode is killed.
    content.querySelectorAll('video').forEach(function(v) {
      try { v.pause(); } catch (e) { console.warn('[cinema] video pause failed during teardown', e); }
      try { v.removeAttribute('src'); } catch (e) { console.warn('[cinema] video src removal failed during teardown', e); }
      try { v.load(); } catch (e) { console.warn('[cinema] video load failed during teardown', e); }
    });
    content.innerHTML = '';
  }
  if (modal) {
    modal.classList.remove('active');
    modal.classList.remove('is-expanded');
  }
  if (backdrop) backdrop.classList.remove('active');
  document.body.style.overflow = '';
  setPlayerViewActive(false);

  closeEpisodeSidebar();
  closeSubtitleSearchSidebar();

  // reason === 'view': the user closed the player (X / backdrop / Escape). If we
  // pushed the watch URL, go back to the view they came from so it behaves like a
  // real routed page; otherwise fall back to the library. 'back' means popstate
  // already moved the URL. Anything else is an internal close (episode switch) and
  // keeps the legacy reset so the next open can write the new watch URL.
  if (reason === 'view') {
    if (wasWatchUrl && _watchUrlPushed && window.history.length > 1) {
      _watchUrlPushed = false;
      // If a details modal is already underneath, the view is intact: skip the
      // popstate re-open so the logo/title don't re-animate. Otherwise let the
      // popstate open the details page the watch URL sits on top of.
      const detailsOpen = document.getElementById('cinema-modal');
      if (detailsOpen && detailsOpen.classList.contains('active')) {
        _skipNextPopstateOpen = true;
        setTimeout(function () { _skipNextPopstateOpen = false; }, 1200);
      }
      history.back();
    } else if (wasWatchUrl) {
      _resetUrlToBrowse();
    }
  } else if (reason !== 'back' && wasWatchUrl) {
    _resetUrlToBrowse();
  }
}

function closePlayerView() {
  closePlayerModal('view');
}

// ============================================================
// Episode Sidebar
// ============================================================

function getPlayerContainer() {
  return document.querySelector('#player-modal-content [data-series-title], #player-modal-content [data-collection-id]');
}


function initEpisodeSidebar() {
  const toggleBtn = document.getElementById('episode-sidebar-toggle');
  if (!toggleBtn) return;
  const container = getPlayerContainer();
  if (!container) {
    toggleBtn.style.display = 'none';
    closeEpisodeSidebar();
    if (window.JMedia && window.JMedia.PageChrome) window.JMedia.PageChrome.setVideoHome();
    return;
  }

  const isEpisode = container.dataset.seriesTitle && (container.dataset.type === 'episode' || container.dataset.type === 'Episode');
  const isCollection = !!container.dataset.collectionId;

  if (isEpisode || isCollection) {
    toggleBtn.style.display = '';
  } else {
    toggleBtn.style.display = 'none';
    closeEpisodeSidebar();

    // Reset the browser tab to the video home branding (the music chrome
    // must not leak into the video section after playback ends)
    if (window.JMedia && window.JMedia.PageChrome) window.JMedia.PageChrome.setVideoHome();
  }
  // Wire season scroll state (fade edges) now that sidebar markup exists
  const _seasonsContainer = document.getElementById('episode-sidebar-seasons');
  if (_seasonsContainer && !_seasonsContainer._seasonScrollWired) {
    _trackedAddEventListener(_seasonsContainer, 'scroll', updateSeasonScrollButtons);
    _seasonsContainer._seasonScrollWired = true;
  }
}

/**
 * Fetch missing episode metadata (description, title, duration) from the backend
 * for any episodes that lack these fields. Runs asynchronously and re-renders
 * the sidebar list when data arrives.
 */
async function fetchMissingEpisodeData(episodes, currentVideoId, initialSeasonKey) {
  const missing = episodes.filter(ep => {
    const hasTitle = !!(ep.episodeTitle || ep.title);
    const hasDesc = !!(ep.description || ep.overview);
    const hasDur = !!ep.duration;
    return !hasTitle || !hasDesc || !hasDur;
  });

  if (missing.length === 0) return;

  const sidebar = document.getElementById('episode-sidebar');
  if (!sidebar || !sidebar.classList.contains('open')) return;

  await Promise.all(missing.map(async (ep) => {
    try {
      const data = await fetchJSON(`/api/video/${ep.id}?textOnly=true`);
      if (!data) return;
      // Merge fetched data back into the episode object (mutates allVideos entry)
      if (!ep.episodeTitle) ep.episodeTitle = data.episodeTitle || data.title || '';
      if (!ep.title) ep.title = data.title || data.episodeTitle || '';
      if (!ep.description) ep.description = data.description || data.overview || '';
      if (!ep.overview) ep.overview = data.overview || data.description || '';
      if (!ep.duration) ep.duration = data.duration || 0;
      if (ep.seasonNumber == null) ep.seasonNumber = data.seasonNumber ?? 1;
      if (!ep.episodeNumber) ep.episodeNumber = data.episodeNumber || 0;
    } catch (e) {
      // Fallback text is shown for this episode; the failure itself still needs
      // to be visible in the console.
      console.debug('[cinema] episode metadata fetch failed for ' + ep.id, e);
    }
  }));

  // Re-render if sidebar is still open (data may have been populated). Re-read the
  // live video id: the viewer may have moved to another episode meanwhile, and
  // highlighting the old one in the list is wrong.
  if (sidebar.classList.contains('open')) {
    const liveContainer = getPlayerContainer();
    const liveVideoId = (liveContainer && liveContainer.dataset.videoId) || currentVideoId;
    renderEpisodeSidebarList(episodes, liveVideoId, initialSeasonKey);
  }
}

async function toggleEpisodeSidebar() {
  const sidebar = document.getElementById('episode-sidebar');
  const backdrop = document.getElementById('episode-sidebar-backdrop');
  const toggleBtn = document.getElementById('episode-sidebar-toggle');
  if (!sidebar || !backdrop) return;

  if (sidebar.classList.contains('open')) {
    closeEpisodeSidebar();
    return;
  }

  // Gather current video context from the player container
  const container = getPlayerContainer();
  if (!container) return;

  if (container.dataset.collectionId) {
    await openCollectionSidebar(container);
    return;
  }

  const seriesTitle = container.dataset.seriesTitle;
  const currentVideoId = container.dataset.videoId;
  const currentSeason = parseInt(container.dataset.seasonNumber) || 1;
  if (!seriesTitle) return;

  // Fetch episodes from the JSON endpoint (or use cache)
  let episodes = _episodesCache.get(seriesTitle);
  if (!episodes) {
    try {
      const resp = await fetch(`/api/video/ui/series/${encodeURIComponent(seriesTitle)}/episodes`);
      if (!resp.ok) {
        console.warn('[cinema] episode sidebar fetch failed with HTTP ' + resp.status);
        return;
      }
      episodes = await resp.json();
      if (!Array.isArray(episodes)) return;
      _episodesCache.set(seriesTitle, episodes);
    } catch (e) {
      console.warn('[cinema] episode sidebar fetch failed', e);
      return;
    }
  }
  // The video may have changed while that request was in flight; rendering now
  // would list the previous series' episodes over the new one.
  if (container.dataset.videoId !== currentVideoId) return;
  episodes = episodes.filter(v => v.type === 'episode')
    .sort((a, b) => {
      const sa = a.seasonNumber ?? 1, sb = b.seasonNumber ?? 1;
      const ea = a.episodeNumber || 0, eb = b.episodeNumber || 0;
      return sa - sb || ea - eb;
    });
  if (!episodes.length) return;

  // Group by (seasonNumber:contentType) — same key pattern as openSeriesDetail
  const groups = {};
  episodes.forEach(ep => {
    const sn = ep.seasonNumber ?? 1;
    const ct = ep.contentType || 'episode';
    const key = sn + ':' + ct;
    if (!groups[key]) groups[key] = [];
    groups[key].push(ep);
  });

  // Sort keys: by seasonNumber (numeric), then by contentType priority
  const contentTypePriority = { 'episode': 0, 'featurette': 1, 'extra': 2, 'special': 3 };
  const seasonKeys = Object.keys(groups).sort((a, b) => {
    const [sa, ca] = a.split(':');
    const [sb, cb] = b.split(':');
    let na = parseInt(sa), nb = parseInt(sb);
    if (na === 0) na = Number.MAX_SAFE_INTEGER;
    if (nb === 0) nb = Number.MAX_SAFE_INTEGER;
    if (na !== nb) return na - nb;
    return (contentTypePriority[ca] ?? 99) - (contentTypePriority[cb] ?? 99);
  });

  // Determine initial season: prefer the current one, else the first
  const initialSeasonKey = seasonKeys.find(k => k.startsWith(currentSeason + ':')) || seasonKeys[0];

  // Render season tabs
  renderEpisodeSidebarSeasons(seasonKeys, initialSeasonKey);

  // Render episode list immediately with whatever data we have
  renderEpisodeSidebarList(groups[initialSeasonKey] || [], currentVideoId, initialSeasonKey);

  sidebar.classList.add('open');
  backdrop.classList.add('active');
  if (toggleBtn) toggleBtn.classList.add('active');

  // Asynchronously fetch missing metadata and re-render when available
  fetchMissingEpisodeData(episodes, currentVideoId, initialSeasonKey);
}

function closeEpisodeSidebar() {
  const sidebar = document.getElementById('episode-sidebar');
  const backdrop = document.getElementById('episode-sidebar-backdrop');
  const toggleBtn = document.getElementById('episode-sidebar-toggle');
  if (sidebar) {
    sidebar.classList.remove('open');
    const titleEl = sidebar.querySelector('.episode-sidebar-title');
    if (titleEl) titleEl.textContent = 'Episodes';
    const seasonsWrap = sidebar.querySelector('.episode-sidebar-seasons-wrap');
    if (seasonsWrap) seasonsWrap.style.display = '';
  }
  if (backdrop) backdrop.classList.remove('active');
  if (toggleBtn) toggleBtn.classList.remove('active');
}

function togglePlayerExpand() {
  const modal = document.getElementById('player-modal');
  if (!modal) return;
  modal.classList.toggle('is-expanded');
  const icon = document.getElementById('player-expand-icon');
  if (icon) {
    icon.className = modal.classList.contains('is-expanded')
      ? 'fa-solid fa-compress'
      : 'fa-solid fa-expand';
  }
}

function playVideo(data) {
  if (!data) return;
  openPlayerModal(data.id);
}

function downloadVideo(videoId) {
  if (!videoId) return;
  const url = `/api/video/download/${videoId}`;
  const a = document.createElement('a');
  a.href = url;
  a.style.display = 'none';
  document.body.appendChild(a);
  a.click();
  setTimeout(() => { if (a.parentNode) a.parentNode.removeChild(a); }, 1000);
  if (window.showToast) window.showToast('Download started', 'info');
}


function playHeroVideo(item) {
  playVideo(item);
}

async function toggleWatchlist(videoId) {
  try {
    const resp = await fetch(`/api/video/watchlist/toggle/${videoId}`, { method: 'POST' });
    const json = await resp.json();
    const isFavorited = json.data;

    // Update all watchlist buttons for this video
    document.querySelectorAll(`[data-watchlist-id="${videoId}"]`).forEach(btn => {
      const icon = btn.querySelector('i');
      if (icon) {
        icon.className = isFavorited ? 'fa-solid fa-check' : 'fa-solid fa-plus';
      }
      btn.title = isFavorited ? 'Remove from Watchlist' : 'Add to Watchlist';
    });

    if (window.showToast) {
      window.showToast(
        isFavorited ? 'Added to My List' : 'Removed from My List',
        isFavorited ? 'success' : 'info'
      );
    }
  } catch (e) {
    console.warn('[cinema] watchlist toggle failed', e);
    if (window.showToast) window.showToast('Failed to update watchlist', 'danger');
  }
}

