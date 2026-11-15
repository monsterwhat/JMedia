// Cinema view: Bootstrap (observer, body class), shared state, deep-link/URL helpers, DOM + card helpers.
// Split out of cinema-mode.js. Loaded by views/video.html in a fixed order;
// these are classic scripts, so they share one global scope.

// ============================================================
// Cinema Mode: force-fix SPA shell for scrollable cinema content
// ============================================================
// Must be initialized before the IIFE below runs, because that IIFE calls
// _trackedAddEventListener(). A `var` assignment further down the file would
// still be `undefined` at this point (only the declaration is hoisted).
var _cinemaListeners = window._cinemaListeners || [];
window._cinemaListeners = _cinemaListeners;

(function() {
  // The video view is re-injected on every SPA visit, so this script runs
  // again each time. Tear down the previous instance (observer + timers)
  // before installing a new one, otherwise they accumulate.
  if (typeof window.__cinemaModeCleanup === 'function') {
    try { window.__cinemaModeCleanup(); } catch (e) { console.error('[cinema-mode] previous cleanup failed', e); }
  }

  function applyCinemaOverrides() {
    // SPA route guard: the observer below stays alive across client-side
    // navigations, so a music-view mutation used to re-apply these overrides
    // and hide the sidebar/player. Only cinema routes may apply them.
    if (window.location.pathname.indexOf('/video') !== 0) {
      clearCinemaOverrides();
      return;
    }
    document.body.classList.add('cinema-mode');
    var sidebar = document.getElementById('standard-sidebar');
    if (sidebar) sidebar.style.display = 'none';
    var player = document.getElementById('musicPlayerContainer');
    if (player) player.style.display = 'none';

    var stdLayout = document.getElementById('standard-layout');
    if (stdLayout) {
      stdLayout.style.display = 'block';
      stdLayout.style.height = 'auto';
      stdLayout.style.overflow = 'visible';
      stdLayout.style.position = 'relative';
      stdLayout.style.marginLeft = '0';
    }
    var stdMain = document.querySelector('.standard-main');
    if (stdMain) {
      stdMain.style.display = 'block';
      stdMain.style.height = 'auto';
      stdMain.style.overflow = 'visible';
      stdMain.style.flex = 'none';
      stdMain.style.marginLeft = '0';
    }
    var appContent = document.getElementById('app-content');
    if (appContent) {
      appContent.style.display = 'block';
      appContent.style.height = 'auto';
      appContent.style.overflowY = 'visible';
      appContent.style.overflowX = 'hidden';
      appContent.style.flex = 'none';
    }
  }

  var _observer = null;

  /**
   * Undo the inline styles applyCinemaOverrides() writes. The music shell
   * (sidebar + persistent player) is hidden by inline styles, not only by the
   * body.cinema-mode class, so both must be cleared when leaving /video.
   */
  function clearCinemaOverrides() {
    document.body.classList.remove('cinema-mode');
    document.body.classList.remove('video-active');
    document.body.setAttribute('data-video-active', 'false');
    var sidebar = document.getElementById('standard-sidebar');
    if (sidebar) sidebar.style.removeProperty('display');
    var player = document.getElementById('musicPlayerContainer');
    if (player) {
      player.style.removeProperty('display');
      // player.css also hides it through these classes, not only inline styles
      player.classList.remove('video-active', 'video-playing');
    }
    var stdLayout = document.getElementById('standard-layout');
    if (stdLayout) {
      stdLayout.style.removeProperty('display');
      stdLayout.style.removeProperty('height');
      stdLayout.style.removeProperty('overflow');
      stdLayout.style.removeProperty('position');
      stdLayout.style.removeProperty('margin-left');
    }
    var stdMain = document.querySelector('.standard-main');
    if (stdMain) {
      stdMain.style.removeProperty('display');
      stdMain.style.removeProperty('height');
      stdMain.style.removeProperty('overflow');
      stdMain.style.removeProperty('flex');
      stdMain.style.removeProperty('margin-left');
    }
    var appContent = document.getElementById('app-content');
    if (appContent) {
      appContent.style.removeProperty('display');
      appContent.style.removeProperty('height');
      appContent.style.removeProperty('overflow-y');
      appContent.style.removeProperty('overflow-x');
      appContent.style.removeProperty('flex');
    }
  }
  function _startObserver() {
    if (_observer) _observer.disconnect();
    _observer = new MutationObserver(function() {
      applyCinemaOverrides();
    });
    var root = document.getElementById('app-content') || document.body;
    _observer.observe(root, { childList: true, subtree: true });
  }

  applyCinemaOverrides();
  if (document.readyState === 'loading') {
    _trackedAddEventListener(document, 'DOMContentLoaded', applyCinemaOverrides);
  }
  _startObserver();

  window._cleanupCinemaMode = function() {
    if (_observer) {
      _observer.disconnect();
      _observer = null;
    }
    if (heroTimer) {
      clearInterval(heroTimer);
      heroTimer = null;
    }
    // Remove all tracked event listeners to prevent accumulation across visits.
    _cinemaListeners.forEach(function(l) {
      try { l.target.removeEventListener(l.type, l.handler, l.options); } catch (e) { console.error('[cinema-mode] listener removal failed', e); }
    });
    _cinemaListeners.length = 0;
    clearCinemaOverrides();
  };
  window.__cinemaModeCleanup = window._cleanupCinemaMode;

  _trackedAddEventListener(window, 'pagehide', window._cleanupCinemaMode);
  _trackedAddEventListener(window, 'beforeunload', window._cleanupCinemaMode);

  // Reconnect observer when SPA routes back to cinema view
  _trackedAddEventListener(window, 'popstate', function() {
    var p = new URLSearchParams(window.location.search);
    var sec = p.get('section');
    if (!sec || sec === 'home' || sec === 'movies' || sec === 'shows') {
      if (!_observer) _startObserver();
    }
  });
})();

// ============================================================
// State
// ============================================================
// These are `var`, not `let`/`const`, on purpose. The SPA injects this script
// with executeScripts() when it swaps in the cinema view, so a duplicate route
// change could evaluate the file twice. A second evaluation of a top-level
// `let`/`const` throws "Identifier ... has already been declared" before any
// statement of the file can run, which silently kills the whole view. `var`
// redeclaration is legal, so a stray second load degrades instead of breaking.
// Tracked addEventListener helper: records registrations so _cleanupCinemaMode
// can remove them on re-execution, preventing listener accumulation.
// (_cinemaListeners is initialized at the top of the file, before the IIFE.)
function _trackedAddEventListener(target, type, handler, options) {
  if (!target || typeof target.addEventListener !== 'function') return;
  target.addEventListener(type, handler, options);
  _cinemaListeners.push({ target: target, type: type, handler: handler, options: options });
}

var heroItems = [];
var allVideos = [];
var allTvShows = [];
var currentHeroIndex = 0;
var heroTimer = null;
var HERO_INTERVAL = 15000;
var allSeries = [];
var seriesLookup = new Map();

// Episode rows from /api/video/videos carry only `seriesTitle` (no nested
// series object), so card rendering resolves the show — and therefore its
// poster — through this title index instead of falling back to a frame grab.
var _seriesTitleIndex = null;
var _seriesTitleIndexSize = -1;
function seriesByTitle(title) {
  if (!title) return null;
  if (!_seriesTitleIndex || _seriesTitleIndexSize !== seriesLookup.size) {
    _seriesTitleIndex = new Map();
    seriesLookup.forEach(s => {
      if (s && s.title) _seriesTitleIndex.set(String(s.title).trim().toLowerCase(), s);
    });
    _seriesTitleIndexSize = seriesLookup.size;
  }
  return _seriesTitleIndex.get(String(title).trim().toLowerCase()) || null;
}
var _episodesCache = new Map();
var _episodeById = new Map();
var allContinueWatching = [];
// Throttle for the on-return home refresh (the fragment is fetched once at boot;
// dock navigation used to reuse that stale DOM until a full page reload).
var _lastHomeRefresh = 0;
var _modalGeneration = 0;

// ── Shareable URLs (/video/info/… , /video/watch/…) ─────────────────────────
// Set while a deep link is being opened: the open functions then skip writing the
// URL again, so the history entry the user arrived with stays untouched.
var _suppressUrlWrite = false;

// True when the current /video/watch URL was pushed by us (as opposed to the user
// opening a shareable link directly). Lets the player's close button go "back" to
// the view the user came from instead of always landing on the library home.
var _watchUrlPushed = false;

// Set immediately before history.back() from the player's close button. The view
// behind the player (details modal / library) is already correct, so the resulting
// popstate must not re-open it (which resets the skeleton and causes the logo/title
// swap the user sees when returning from playback).
var _skipNextPopstateOpen = false;

// Generation counter for openPlayerModal. Opening is async (two network round
// trips before the fragment is injected), so a slow open for episode A could land
// after the user opened B or closed the modal, re-injecting a player into a dead
// modal — the "audio starts after close" symptom. Every open and every close
// bumps this; stale continuations bail out.
var _playerOpenGen = 0;

// Same idea for the search dropdown: the external TMDb lookup is async, and a slow
// response for "ab" must not replace the results for "abc" (or reopen a closed
// dropdown). Bumped on every keystroke-driven search and on close.
var _searchGen = 0;

// And for the dynamic (non-home) sections. openCollectionDetail / showSection /
// showCollectionsGrid all fetch before writing into #dynamic-content, so a slow
// response for the previous view could repaint the current one.
var _dynamicViewGen = 0;

function _pushInfoUrl(token) {
  if (_suppressUrlWrite || !window.VideoRoutes || !token) return;
  const url = VideoRoutes.infoUrl(token);
  if (!url || window.location.pathname === url) return;
  try {
    history.pushState({ video: { area: 'info', token: token } }, '', url);
  } catch (e) {
    console.warn('[cinema] pushState info failed', e);
  }
}

/** Shareable URL for a catalogue (not-in-library) card. */
function _externalInfoUrl(token, kind, mediaType) {
  var url = '/video/info/' + (kind || 'tmdb') + '/' + encodeURIComponent(token);
  if (mediaType) url += '?type=' + encodeURIComponent(mediaType);
  return url;
}

/**
 * Open a catalogue card in place, with the same shareable history entry that
 * openDetails/openSeriesDetail give library titles. Assigning
 * window.location.href here used to reload the whole SPA (all scripts, sockets
 * and home rows) before the modal could paint. Respects _suppressUrlWrite so
 * the deep-link boot path does not push a second entry.
 */
function _openExternalFromCard(tmdbId, mediaType, meta, kind) {
  if (_suppressUrlWrite) return openExternalDetails(tmdbId, mediaType, meta);
  var url = _externalInfoUrl(tmdbId, kind, mediaType);
  var current = window.location.pathname + window.location.search;
  if (current !== url) {
    try {
      history.pushState({ video: { area: 'info', token: String(tmdbId) } }, '', url);
    } catch (e) {
      console.warn('[cinema] pushState external info failed', e);
    }
  }
  return openExternalDetails(tmdbId, mediaType, meta);
}

function _pushWatchUrl(token, season, episode) {
  if (_suppressUrlWrite || !window.VideoRoutes || !token) return;
  const url = VideoRoutes.watchUrl(token, season, episode);
  if (!url || window.location.pathname === url) return;
  try {
    history.pushState({ video: { area: 'watch', token: token } }, '', url);
    _watchUrlPushed = true;
  } catch (e) {
    console.warn('[cinema] pushState watch failed', e);
  }
}

/** Back to the browse URL without leaving a modal entry in history. */
function _resetUrlToBrowse() {
  if (window.location.pathname === '/video') return;
  try {
    history.replaceState({ video: { area: 'browse' } }, '', '/video');
  } catch (e) {
    console.warn('[cinema] replaceState browse failed', e);
  }
}

/** Resolve /video/watch/db123 to the canonical series+episode URL, then push it. */
async function _pushWatchUrlForVideo(videoId) {
  if (_suppressUrlWrite || !window.VideoRoutes || !videoId) return;
  if (window.location.pathname.indexOf('/video/watch/') === 0) return;
  // Push an internal dbN entry synchronously so Back always has an in-app entry to
  // return to, even when the canonical resolve is slow or the user closes quickly.
  // The canonical token URL is upgraded in place once the resolver responds.
  _pushWatchUrl('db' + videoId);
  try {
    const res = await fetch('/api/video/resolve/db' + videoId, { credentials: 'include' });
    if (!res.ok) return;
    const data = await res.json();
    if (data && data.found && data.watch
        && window.location.pathname.indexOf('/video/watch/') === 0) {
      history.replaceState({ video: { area: 'watch', token: data.token } }, '', data.watch);
    }
  } catch (e) {
    console.warn('[cinema] watch url resolve failed', e);
  }
}

function _parseVideoUrl() {
  return window.VideoRoutes ? VideoRoutes.parse() : null;
}

function _isDeepLink(parsed) {
  return !!(window.VideoRoutes && VideoRoutes.isDeepLink(parsed));
}

async function _openFromUrl(parsed) {
  if (!_isDeepLink(parsed)) return false;
  _suppressUrlWrite = true;
  try {
    const resolved = await VideoRoutes.resolve(parsed);
    if (!resolved || resolved.found === false) {
      // Keep the shared URL (do NOT fall back to /video): the link stays shareable
      // and the card explains why the title cannot be played here.
      const meta = resolved && resolved.meta ? resolved.meta : null;
      const tmdbId = (meta && meta.tmdbId) ? String(meta.tmdbId)
        : ((parsed && parsed.kind === 'tmdb') ? String(parsed.token || '') : '');
      if (tmdbId) {
        const mediaType = (meta && meta.mediaType) || (parsed && parsed.mediaType) || '';
        const opened = await _openExternalFromCard(tmdbId, mediaType, meta).catch(function (e) {
          console.warn('[cinema] catalogue card failed', e);
          return false;
        });
        if (opened) return true;
      }
      showNotInLibraryPanel(parsed, resolved);
      return true;
    }
    if (parsed.area === 'info') {
      _removeNotInLibraryPage();
      if (resolved.type === 'series') {
        await openSeriesDetail(resolved.title);
      } else {
        await openDetails(resolved.id);
      }
      return true;
    }
    // watch
    const episodeId = resolved.episode && resolved.episode.found ? resolved.episode.videoId : null;

    // A shareable watch URL opened directly has no in-app entry behind it, so
    // Back would leave the site and the player would never be closed. Replace the
    // initial entry with the show's info URL and push the watch URL on top, so
    // Back returns to the details page.
    let deepLinkEntry = false;
    if (window.location.pathname.indexOf('/video/watch/') === 0) {
      const currentPath = window.location.pathname + window.location.search;
      const infoUrl = resolved.info
        || (resolved.token ? '/video/info/' + encodeURIComponent(resolved.token) : '/video');
      try {
        history.replaceState({ video: { area: 'info', token: resolved.token } }, '', infoUrl);
        history.pushState({ video: { area: 'watch', token: resolved.token } }, '', currentPath);
        deepLinkEntry = true;
      } catch (e) {
        console.warn('[cinema] deep-link history init failed', e);
      }
    }

    let openedPlayer = false;
    if (episodeId) {
      await openPlayerModal(episodeId);
      openedPlayer = true;
    } else if (resolved.type === 'series') {
      // No episode in the URL: open the show so the user can pick one (the modal's
      // Play button resumes, same as clicking the card).
      await openSeriesDetail(resolved.title);
    } else if (resolved.id) {
      await openPlayerModal(resolved.id);
      openedPlayer = true;
    }
    if (deepLinkEntry && openedPlayer) _watchUrlPushed = true;
    return true;
  } finally {
    _suppressUrlWrite = false;
  }
}

/**
 * Full-page "this title is not in your library" state for a shareable link.
 * Rendered as a body-level overlay, not inside the view: the SPA re-injects the
 * video view (and can do it again after a slow boot), and anything inside it
 * would be wiped. The overlay survives that and keeps the URL unchanged.
 */
function showNotInLibraryPanel(parsed, resolved) {
  const meta = resolved && resolved.meta ? resolved.meta : null;
  const token = (resolved && resolved.token) || (parsed && parsed.token) || '';
  const kind = (resolved && resolved.kind) || (parsed && parsed.kind) || '';

  // Only ever point an <img> at a TMDb image URL: the payload comes from an
  // external service, so never interpolate an arbitrary URL into src.
  const rawPoster = meta && meta.posterUrl ? String(meta.posterUrl) : '';
  const poster = /^https:\/\/image\.tmdb\.org\/t\/p\/[A-Za-z0-9_]+\/[A-Za-z0-9_.-]+$/.test(rawPoster)
    ? rawPoster : '';
  const title = meta && meta.title ? String(meta.title) : 'This title';
  const yearText = meta && meta.year ? ' (' + meta.year + ')' : '';
  const overview = meta && meta.overview ? String(meta.overview) : '';
  const kindLabel = kind === 'imdb' ? 'IMDb' : kind === 'tmdb' ? 'TMDb' : kind === 'tvdb' ? 'TVDB' : 'id';
  const copyText = title + yearText + (token ? ' — /video/info/' + token : '');
  const yearNum = meta && meta.year != null && meta.year !== '' ? String(meta.year) : '';
  const mediaType = meta && meta.mediaType ? String(meta.mediaType) : '';
  const tmdbId = meta && meta.tmdbId != null && meta.tmdbId !== '' ? String(meta.tmdbId) : '';

  let page = document.getElementById('not-in-library-page');
  if (!page) {
    page = document.createElement('div');
    page.id = 'not-in-library-page';
    document.body.appendChild(page);
  }
  page.innerHTML =
      '<div style="display:flex;gap:2rem;flex-wrap:wrap;align-items:flex-start;max-width:1000px;margin:0 auto;">' +
        (poster ? '<img src="' + escapeHtml(poster) + '" alt="" style="width:200px;max-width:45vw;border-radius:12px;box-shadow:0 12px 40px rgba(0,0,0,.5);">' : '') +
        '<div style="flex:1;min-width:260px;">' +
          '<div class="cinema-section-title" style="margin-bottom:.75rem;">Not in your library</div>' +
          '<h2 style="font-size:1.9rem;font-weight:700;margin:0 0 .35rem;">' + escapeHtml(title) + escapeHtml(yearText) + '</h2>' +
          (overview ? '<p class="cinema-modal-desc" style="margin:0 0 1.25rem;">' + escapeHtml(overview) + '</p>' : '') +
          '<p style="color:rgba(255,255,255,.7);margin:0 0 1.5rem;">' +
            'This link points to ' + escapeHtml(kindLabel) + ' id <strong>' + escapeHtml(token) + '</strong>, ' +
            'which is not in this JMedia library, so there is nothing to play here.' +
          '</p>' +
          '<div style="display:flex;gap:.75rem;flex-wrap:wrap;">' +
            '<button type="button" class="button is-light" data-click="closeNotInLibrary">' +
              '<i class="fa-solid fa-arrow-left"></i>&nbsp;Browse library</button>' +
            '<button type="button" class="button is-link" data-click="requestNotInLibraryTitle"' +
              ' data-token="' + escapeHtml(token) + '" data-kind="' + escapeHtml(kind) + '"' +
              ' data-title="' + escapeHtml(title) + '" data-year="' + escapeHtml(yearNum) + '"' +
              ' data-poster="' + escapeHtml(poster) + '" data-media-type="' + escapeHtml(mediaType) + '"' +
              ' data-tmdb-id="' + escapeHtml(tmdbId) + '">' +
              '<i class="fa-solid fa-paper-plane"></i>&nbsp;Request it</button>' +
            '<button type="button" class="button is-light" data-click="copyNotInLibraryTitle" data-copy="' + escapeHtml(copyText) + '">' +
              '<i class="fa-solid fa-copy"></i>&nbsp;Copy title</button>' +
          '</div>' +
        '</div>' +
      '</div>';
}

/** Remove the not-in-library overlay (and clear the guard it sets). */
function _removeNotInLibraryPage() {
  const page = document.getElementById('not-in-library-page');
  if (page) page.remove();
}

function _hideRequestButton() {
  const btn = document.getElementById('modal-request-btn');
  if (btn) {
    btn.style.display = 'none';
    btn.onclick = null;
    btn.removeAttribute('data-token');
    btn.removeAttribute('data-kind');
    btn.removeAttribute('data-title');
    btn.removeAttribute('data-year');
    btn.removeAttribute('data-poster');
    btn.removeAttribute('data-media-type');
    btn.removeAttribute('data-tmdb-id');
  }
}

// ============================================================
// API Helpers
// ============================================================
async function fetchJSON(url) {
  try {
    const res = await fetch(url);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const json = await res.json();
    // Handle both ApiResponse format {success, data} and raw arrays
    if (json && typeof json === 'object' && json.success !== undefined && json.data !== undefined) {
      return json.data;
    }
    return json;
  } catch (e) {
    console.warn('[cinema] fetch failed:', url, e);
    return null;
  }
}

function getThumbnailUrl(videoId) {
  return `/api/video/thumbnail/${videoId}`;
}
function withArtWidth(url, w) { return url + (url.indexOf('?') >= 0 ? '&' : '?') + 'w=' + w; }
function artSrcset(url) { return [144,288,400].map(w => withArtWidth(url,w) + ' ' + w + 'w').join(', '); }
// Poster fallback for images that also carry artSrcset(). The srcset candidates must be
// dropped first: while a width-descriptor candidate exists the browser ignores src, so
// "this.src='/logo.png'" re-selects the same failing URL and re-fires onerror in a tight
// loop (measured ~290 errors and ~117 requests per second for a 404 poster). Disarming
// onerror as well keeps the fallback from looping when the logo itself is unavailable.
function artFallbackAttr() {
  return 'onerror="this.onerror=null;this.removeAttribute(\'srcset\');this.src=\'/logo.png\'"';
}

function getHeroUrl(videoId) {
  return `/api/video/hero/${videoId}`;
}

function getBackdropUrl(video) {
  return `/api/video/backdrop/${video?.id}`;
}
function getLogoUrl(videoId) {
  return `/api/video/logo/${videoId}`;
}
function getSeriesImageUrl(seriesId, type) {
  return `/api/series/${seriesId}/${type}`;
}
function formatDuration(ms) {
  if (!ms || ms <= 0) return '';
  const totalSeconds = Math.floor(ms / 1000);
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

function ratingColor(rating) {
  const r = parseFloat(rating) || 0;
  if (r < 5) return '#ff4d4f';
  if (r < 7) return '#ff8c00';
  if (r < 8) return '#46d369';
  if (r < 9) return '#4fc3f7';
  return '#ffd700';
}

function renderStars(rating, color) {
  const r = parseFloat(rating) || 0;
  if (r <= 0) return '';
  const stars = Math.round(r / 2);
  const svgNS = 'http://www.w3.org/2000/svg';
  let html = '';
  for (let i = 0; i < 5; i++) {
    const filled = i < stars;
    const halfFilled = !filled && i < stars + 0.5;
    const fillColor = color || 'var(--cinema-text, #ffffff)';
    const emptyColor = 'var(--cinema-text-dim, #888888)';
    if (halfFilled) {
      html += `<svg width="14" height="14" viewBox="0 0 24 24" style="vertical-align: middle;"><defs><linearGradient id="halfStar${i}"><stop offset="50%" stop-color="${fillColor}"/><stop offset="50%" stop-color="${emptyColor}"/></linearGradient></defs><path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z" fill="url(#halfStar${i})" stroke="none"/></svg>`;
    } else {
      const color = filled ? fillColor : emptyColor;
      html += `<svg width="14" height="14" viewBox="0 0 24 24" style="vertical-align: middle;"><path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z" fill="${color}" stroke="none"/></svg>`;
    }
  }
  return html;
}

function createCardHTML(video) {
  const id = video.id;
  const isTvCard = video.type === 'episode' && video.seriesTitle;
  const title = isTvCard ? video.seriesTitle : (video.title || video.seriesTitle || 'Untitled');
  const show = isTvCard ? seriesByTitle(video.seriesTitle) : null;
  // Episode videos carry no rating/year (fields default to 0.0) - fall back to the series values
  let year = video.releaseYear || (show ? show.releaseYear : '') || '';
  const ratingValue = parseFloat(video.imdbRating || video.tmdbRating || (show ? (show.imdbRating || show.tmdbRating) : '') || '') || 0;
  const ratingText = ratingValue > 0 ? ratingValue.toFixed(1) : '';
  const starColor = ratingText ? ratingColor(ratingValue) : '';
  const imgSrc = isTvCard && show && show.id
    ? getSeriesImageUrl(show.id, 'poster')
    : getThumbnailUrl(id);
  const progress = video.watchProgress || 0;
  const watched = video.watched;
  const statusClass = watched ? 'status-watched' : (progress > 0 ? 'status-partial' : '');
  return `
    <div class="cinema-card ${statusClass}" data-video-id="${id}" data-type="${video.type || 'movie'}" data-title="${escapeHtml(title)}" data-click="${(video.type === 'episode' && video.seriesTitle) ? 'openSeriesDetailFromCard' : 'openDetailsFromCard'}"${(video.type === 'episode' && video.seriesTitle) ? ` data-series-title="${encodeURIComponent(video.seriesTitle || '')}"` : ''}>
      <div class="cinema-card-poster">
        ${statusClass ? `<span class="card-progress-indicator ${statusClass}"></span>` : ''}
        <img src="${withArtWidth(imgSrc,400)}" srcset="${artSrcset(imgSrc)}" sizes="(min-width: 768px) 176px, 144px" alt="${escapeHtml(title)}" loading="lazy" ${artFallbackAttr()}>
        <div class="cinema-card-play-overlay">
          <div class="cinema-card-play-icon">
            <i class="fa-solid fa-play"></i>
          </div>
        </div>
      </div>
      <div class="cinema-card-title">${escapeHtml(title)}</div>
      <div class="cinema-card-meta">
        <span class="cinema-card-meta-text"><i class="fa-solid fa-star" style="font-size: 0.7rem;${starColor ? ` color: ${starColor};` : ''}"></i> <span style="color:${starColor || 'inherit'}">${ratingText}</span> ${year}</span>
      </div>
    </div>
  `;
}

// ============================================================
// Hero Color Extraction
// ============================================================
function extractHeroColor(imgElement) {
  if (!imgElement || !imgElement.complete || !imgElement.naturalWidth) return;
  try {
    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d');
    const size = 64;
    canvas.width = size;
    canvas.height = size;
    ctx.drawImage(imgElement, 0, 0, size, size);
    const imageData = ctx.getImageData(0, 0, size, size);
    const data = imageData.data;
    let r = 0, g = 0, b = 0, count = 0;
    for (let i = 0; i < data.length; i += 4) {
      if (data[i] > 20 && data[i] < 235 && data[i+1] > 20 && data[i+1] < 235 && data[i+2] > 20 && data[i+2] < 235) {
        r += data[i]; g += data[i+1]; b += data[i+2]; count++;
      }
    }
    if (count > 0) {
      r = Math.round(r / count); g = Math.round(g / count); b = Math.round(b / count);
      const heroSection = document.querySelector('.cinema-hero');
      if (heroSection) heroSection.style.backgroundColor = `rgb(${r}, ${g}, ${b})`;
      // Apply gradient overlay that extends color down through content
      const heroGradient = document.getElementById('hero-color-gradient');
      if (heroGradient) {
        heroGradient.style.background = `linear-gradient(to bottom, transparent 0%, rgba(${r}, ${g}, ${b}, 0.12) 45%, rgba(${r}, ${g}, ${b}, 0.35) 72%, rgba(${r}, ${g}, ${b}, 0.55) 100%)`;
        heroGradient.classList.add('active');
      }
    }
  } catch (e) { /* Canvas tainted or image not loaded � skip gracefully */ }
}

function extractAndApplyModalColor(imgUrl, posterUrl) {
    // Set a neutral dark background on the scroll container as fallback
    const scroll = document.querySelector('.cinema-modal-scroll');
    if (scroll) {
      scroll.style.backgroundColor = '#09090b';
    }
}

