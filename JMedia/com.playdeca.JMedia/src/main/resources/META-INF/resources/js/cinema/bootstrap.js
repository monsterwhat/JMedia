// Cinema view: Evaluated last: dock/search/delegation listeners, deep-link boot, initial data load.
// Split out of cinema-mode.js. Loaded by views/video.html in a fixed order;
// these are classic scripts, so they share one global scope.
// ============================================================
// Dock + Search Wiring
// ============================================================
_trackedAddEventListener(document.getElementById('settingsDockBtn'), 'click', openSettingsModal);
_trackedAddEventListener(document.getElementById('searchDockBtn'), 'click', openSearch);
_trackedAddEventListener(document.getElementById('nowPlayingDockBtn'), 'click', function(e) {
  e.preventDefault();
  const sec = 'nowPlaying';
  const url = `/video?section=${sec}`;
  history.pushState({}, '', url);
  showSection(sec);
  document.querySelectorAll('.cinema-dock-item').forEach(i => i.classList.remove('active'));
  this.classList.add('active');
});

// SPA section navigation
document.querySelectorAll('.cinema-dock-item[data-section]').forEach(item => {
  _trackedAddEventListener(item, 'click', function(e) {
    e.preventDefault();
    _removeNotInLibraryPage();
    const sec = this.dataset.section;
    const url = `/video?section=${sec}`;
    history.pushState({}, '', url);
    showSection(sec);
    document.querySelectorAll('.cinema-dock-item').forEach(i => i.classList.remove('active'));
    this.classList.add('active');
  });
});
// Home button � SPA style
var homeDockBtn = document.querySelector('.cinema-dock-item[href="/video"]');
if (homeDockBtn) {
  _trackedAddEventListener(homeDockBtn, 'click', function(e) {
    e.preventDefault();
    _removeNotInLibraryPage();
    history.pushState({}, '', '/video');
    showSection('home');
    document.querySelectorAll('.cinema-dock-item').forEach(i => i.classList.remove('active'));
    this.classList.add('active');
  });
}
// Refresh the home when the tab comes back to the foreground (library changed
// elsewhere, progress reported, etc.) instead of waiting for a manual reload.
_trackedAddEventListener(document, 'visibilitychange', () => {
  if (document.hidden) return;
  const sec = new URLSearchParams(window.location.search).get('section') || 'home';
  if (sec === 'home') refreshCinemaHome(false);
});

// Browser back/forward - deep links first, then the dock sections
_trackedAddEventListener(window, 'popstate', () => {
  // The player's own close button already restored the view before going back;
  // do not re-open it (avoids the details logo/title skeleton swap).
  if (_skipNextPopstateOpen) {
    _skipNextPopstateOpen = false;
    return;
  }
  const parsed = _parseVideoUrl();
  if (_isDeepLink(parsed)) {
    // Returning from playback to an already-open details modal: just reveal it.
    // Always close the player first so it cannot stay on top of the details view.
    if (parsed.area === 'info') {
      const playerModal = document.getElementById('player-modal');
      if (playerModal && playerModal.classList.contains('active')) closePlayerModal('back');
      const openDetailsModal = document.getElementById('cinema-modal');
      if (openDetailsModal && openDetailsModal.classList.contains('active')) return;
    }
    _openFromUrl(parsed);
    return;
  }
  // Left a deep link: close whatever was open and show the browse section.
  const playerModal = document.getElementById('player-modal');
  if (playerModal && playerModal.classList.contains('active')) closePlayerModal('back');
  const cinemaModal = document.getElementById('cinema-modal');
  if (cinemaModal && cinemaModal.classList.contains('active')) closeModal();
  const p = new URLSearchParams(window.location.search);
  const sec = p.get('section') || 'home';
  _removeNotInLibraryPage();
  showSection(sec);
  document.querySelectorAll('.cinema-dock-item').forEach(i => i.classList.remove('active'));
  if (sec !== 'home') {
    const t = document.querySelector(`.cinema-dock-item[data-section="${sec}"]`);
    if (t) t.classList.add('active');
  } else {
    document.querySelector('.cinema-dock-item')?.classList.add('active');
  }
});

// First load of a deep link (/video/info/… or /video/watch/…)
// The video view can be injected more than once (SPA route load + a re-render),
// which replaces the DOM the first attempt rendered into. Retry briefly, guarded
// by the URL so a handled link is never opened twice.
(function bootVideoDeepLink() {
  // Only the URL that was present when this view booted may be auto-opened.
  // Opening a series writes /video/info/<id> into the history; the retry timer
  // kept re-parsing whatever URL was current, so a normal click could be
  // reopened as a "deep link" a few hundred ms later. When the resolver maps
  // that id to a duplicate/stale series with no episodes, the modal is replaced
  // by "No episodes found" and closed.
  const bootKey = window.location.pathname + window.location.search;
  let attempts = 0;
  const tryOpen = function() {
    if (window.location.pathname + window.location.search !== bootKey) return;
    const parsed = _parseVideoUrl();
    // VideoRoutes may not be evaluated yet on the first pass (the SPA injects the
    // view scripts asynchronously, in order), so re-check instead of giving up.
    if (!_isDeepLink(parsed)) {
      if (++attempts < 8) setTimeout(tryOpen, 500 * attempts);
      return;
    }
    const key = window.location.pathname + window.location.search;
    if (window.__videoDeepLinkDone === key || window.__videoDeepLinkOpening === key) return;
    window.__videoDeepLinkOpening = key;
    Promise.resolve(_openFromUrl(parsed)).then(function(handled) {
      if (window.__videoDeepLinkOpening === key) window.__videoDeepLinkOpening = null;
      if (handled) window.__videoDeepLinkDone = key;
    }).catch(function(e) {
      if (window.__videoDeepLinkOpening === key) window.__videoDeepLinkOpening = null;
      console.warn('[cinema] deep-link open failed', e);
    });
    if (++attempts < 5) setTimeout(tryOpen, 700 * attempts);
  };
  // Open after the first paint so the modal animates over a rendered page.
  setTimeout(tryOpen, 0);
})();

// Wire logout
_trackedAddEventListener(document.getElementById('logout-btn'), 'click', function() {
  localStorage.removeItem('activeProfileId');
  fetch('/api/auth/logout', { method: 'POST' }).then(() => {
    window.location.href = '/login.html';
  });
});

// ============================================================
// Init
// ============================================================
// Inject dynamic templates
(function() {
  var root = document.getElementById('episode-sidebar-root');
  if (root && typeof createEpisodeSidebarHTML === 'function') {
    root.innerHTML = createEpisodeSidebarHTML();
  }
  var subRoot = document.getElementById('subtitle-search-sidebar-root');
  if (subRoot && typeof createSubtitleSearchSidebarHTML === 'function') {
    subRoot.innerHTML = createSubtitleSearchSidebarHTML();
  }
})();

// Wire user info in settings sidebar
fetchJSON('/api/auth/current-user').then(data => {
  if (data?.loggedIn && data?.username) {
    const el = document.getElementById('settings-username');
    if (el) el.textContent = data.username;
  }
}).catch((e) => console.warn('[cinema] current-user lookup failed', e));

loadCinemaData();

// === Autoplay query parameter handler ===
(function handleAutoplay() {
    const params = new URLSearchParams(window.location.search);
    const autoplayId = params.get('autoplay');
    if (autoplayId) {
        const id = parseInt(autoplayId, 10);
        if (!isNaN(id)) {
            // Wait for page to fully initialize before opening player
            const checkReady = setInterval(() => {
                if (typeof openPlayerModal === 'function' && typeof allVideos !== 'undefined') {
                    clearInterval(checkReady);
                    setTimeout(() => openPlayerModal(id), 300);
                }
            }, 100);
            // Fallback: stop checking after 10 seconds
            setTimeout(() => clearInterval(checkReady), 10000);
        }
    }
})();

// ============================================================
// XSS-Safe Delegated Handlers for Cards (replaces inline onclick)
// ============================================================
function playEpisodeFromCard(el) {
  var card = el && el.closest ? el.closest('[data-video-id]') : null;
  if (!card || !card.dataset.videoId) return;
  var id = card.dataset.videoId;
  var ep = _episodeById.get(String(id));
  if (!ep) {
    for (const [title, eps] of _episodesCache) {
      if (!Array.isArray(eps)) continue;
      const found = eps.find(e => String(e.id) === String(id));
      if (found) { ep = found; break; }
    }
  }
  if (ep) playVideo(ep);
}
function openSeriesDetailFromCard(el) {
  var card = el.closest('[data-series-title]');
  if (card) openSeriesDetail(decodeURIComponent(card.dataset.seriesTitle));
}
function playContinueWatchingFromCard(el) {
  var card = el.closest('[data-cw-id]');
  if (!card) return;
  var id = parseInt(card.dataset.cwId);
  var type = card.dataset.cwType || '';
  var seriesTitle = card.dataset.cwSeries ? decodeURIComponent(card.dataset.cwSeries) : '';
  playContinueWatching(id, type, seriesTitle);
}
function openDetailsFromCard(el) {
  var card = el.closest('[data-video-id]');
  if (card && card.dataset.videoId) {
    if (card.dataset.collectionId) {
      openPlayerModal(parseInt(card.dataset.videoId), parseInt(card.dataset.collectionId));
    } else {
      openDetails(parseInt(card.dataset.videoId));
    }
  }
}
function closeSearchByClick(el) {
  closeSearch();
  var action = el.getAttribute('data-search-action');
  if (action === 'openDetails') {
    var id = el.getAttribute('data-video-id');
    if (id) openDetails(parseInt(id));
  } else if (action === 'openSeriesDetailFromSearch') {
    var title = el.getAttribute('data-series-title');
    if (title) openSeriesDetail(decodeURIComponent(title));
  } else if (action === 'openExternalInfo') {
    var token = el.getAttribute('data-token');
    var kind = el.getAttribute('data-kind') || 'tmdb';
    var mediaType = el.getAttribute('data-media-type') || '';
    if (!token) return;
    var tmdbId = parseInt(token, 10);
    if (isNaN(tmdbId)) {
      // Unknown id kinds keep the old navigation behaviour.
      window.location.href = _externalInfoUrl(token, kind, mediaType);
      return;
    }
    _openExternalFromCard(tmdbId, mediaType, null, kind);
  }
}

/** Request a missing episode's show from the info view. */
window.requestMissingTitle = function(el) {
  requestNotInLibraryTitle(el);
};


/** Open the not-in-library info page for a requested title card. */
window.openRequestedTitle = function(el) {
  if (!el || !el.dataset) return;
  var token = el.dataset.token;
  var kind = el.dataset.kind || 'tmdb';
  if (!token) return;
  var tmdbId = parseInt(token, 10);
  if (isNaN(tmdbId)) {
    window.location.href = _externalInfoUrl(token, kind, null);
    return;
  }
  _openExternalFromCard(tmdbId, null, null, kind);
};

// ============================================================
// Event Delegation: data-click and data-change attributes
// ============================================================
_trackedAddEventListener(document, 'click', function(e) {
  var el = e.target;
  while (el && el !== document) {
    var action = el.getAttribute('data-click');
    if (action) {
      if (el.hasAttribute('data-stop-propagation')) {
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

_trackedAddEventListener(document, 'change', function(e) {
  var el = e.target;
  var action = el.getAttribute('data-change');
  if (action) {
    var callFn = window[action];
    if (typeof callFn === 'function') {
      callFn(el.value, el);
    }
  }
});
