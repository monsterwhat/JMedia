// Cinema view: Inline dock search and the Show All grid.
// Split out of cinema-mode.js. Loaded by views/video.html in a fixed order;
// these are classic scripts, so they share one global scope.
// Show All state
var _showAllMode = null;
var _showAllData = [];
var _showAllIndex = 0;
var _showAllSort = 'dateAdded';
var _showAllObserver = null;
var _SHOW_ALL_BATCH = 20;
// ============================================================
// Inline Dock Search
// ============================================================
function debounce(fn, ms) {
  let timer;
  return function(...args) {
    clearTimeout(timer);
    timer = setTimeout(() => fn.apply(this, args), ms);
  };
}

function openSearch() {
  const dock = document.querySelector('.cinema-dock');
  if (dock) dock.classList.add('searching');
  const input = document.getElementById('searchInput');
  window._searchOpenedAt = Date.now();

  // The dock slides to the bottom while the field holds focus. iOS cancels the keyboard
  // when a focused element is repositioned, which is what made the search look like it
  // minimised a second after opening. Re-assert focus once the move has finished, but
  // only while the search is still open (closeSearch removes `.searching`, after which
  // this must not steal focus back).
  const focusInput = () => {
    if (!input || !dock || !dock.classList.contains('searching')) return;
    if (document.activeElement === input) return;
    input.focus();
  };
  if (input) {
    // Restored: focus inside the tap gesture so iOS opens the keyboard with the field.
    input.value = '';
    input.focus();
  }
}

function closeSearch() {
    const dock = document.querySelector('.cinema-dock');
  if (dock) dock.classList.remove('searching');
  // Invalidate any search still in flight so its results cannot reopen the
  // dropdown after the user closed it.
  _searchGen++;
  const input = document.getElementById('searchInput');
  if (input) input.value = '';
  const dropdown = document.getElementById('searchDropdown');
  if (dropdown) { dropdown.innerHTML = ''; dropdown.classList.remove('visible'); }
}

async function performSearch(query) {
  const dropdown = document.getElementById('searchDropdown');
  if (!dropdown) return;
  const searchGen = ++_searchGen;
  const q = query.trim().toLowerCase();
  if (!q) { dropdown.innerHTML = ''; dropdown.classList.remove('visible'); return; }

  // Local results (one per movie / one per TV series)
  let results = [];
  if (window.allVideos && window.allVideos.length) {
    const seen = new Map();
    const matches = [];
    window.allVideos.forEach(v => {
      const title = (v.title || '').toLowerCase();
      const series = (v.seriesTitle || '').toLowerCase();
      if (!title.includes(q) && !series.includes(q)) return;

      if (v.type === 'movie') {
        matches.push(v);
      } else if (v.type === 'episode' && v.seriesTitle) {
        const key = v.seriesTitle.toLowerCase().replace(/[^a-z0-9]/g, '');
        if (!seen.has(key)) {
          seen.set(key, true);
          matches.push({ ...v, _isSeries: true });
        }
      }
    });
    results = matches.slice(0, 20);
  }

  // TMDb results for titles that are not in the library.
  let external = [];
  if (q.length >= 2) {
    try {
      const resp = await fetch('/api/video/ui/search-external?q=' + encodeURIComponent(query.trim()), { credentials: 'include' });
      if (resp.ok) {
        const data = await resp.json();
        external = (Array.isArray(data) ? data : []).filter(r => !r.inLibrary).slice(0, 10);
      }
    } catch (e) {
      console.warn('[cinema] external search failed', e);
    }
  }

  // A newer keystroke (or closeSearch) superseded this request.
  if (searchGen !== _searchGen) return;

  if (!results.length && !external.length) {
    dropdown.innerHTML = '<div class="cinema-search-hint">No results found.</div>';
    dropdown.classList.add('visible');
    return;
  }

  let html = results.map(v => {
    const id = v.id;
    const title = v.type === 'movie' ? (v.title || 'Untitled') : (v.seriesTitle || v.title || 'Untitled');
    const type = v.type === 'movie' ? 'Movie' : 'TV';
    const year = v.releaseYear ? ` \u2022 ${v.releaseYear}` : '';
    return `<button class="cinema-search-result" data-click="closeSearchByClick" data-search-action="${v.type === 'movie' ? 'openDetails' : 'openSeriesDetailFromSearch'}" ${v.type === 'movie' ? `data-video-id="${id}"` : `data-series-title="${encodeURIComponent(v.seriesTitle || '')}"`}>
      <img class="cinema-search-result-thumb" src="${withArtWidth('/api/video/poster/' + id, 400)}" srcset="${artSrcset('/api/video/poster/' + id)}" sizes="40px" alt="" loading="lazy">
      <div class="cinema-search-result-info">
        <div class="cinema-search-result-title">${title}</div>
        <div class="cinema-search-result-sub">${type}${year}</div>
      </div>
    </button>`;
  }).join('');

  if (external.length) {
    html += '<div class="cinema-search-hint" style="margin-top:.5rem;font-size:.7rem;text-transform:uppercase;letter-spacing:1.5px;color:rgba(255,255,255,.45);">Not in your library</div>';
    html += external.map(r => {
      const title = escapeHtml(r.title || 'Untitled');
      const type = r.mediaType === 'movie' ? 'Movie' : 'TV';
      const year = r.year ? ' \u2022 ' + r.year : '';
      const poster = r.posterUrl
        ? `<img class="cinema-search-result-thumb" src="${escapeHtml(r.posterUrl)}" alt="" loading="lazy">`
        : '<div class="cinema-search-result-thumb" style="display:flex;align-items:center;justify-content:center;opacity:.4;"><i class="fa-solid fa-film"></i></div>';
      return `<button class="cinema-search-result" data-click="closeSearchByClick" data-search-action="openExternalInfo" data-token="${escapeHtml(r.token || '')}" data-kind="tmdb" data-media-type="${escapeHtml(r.mediaType || '')}">
        ${poster}
        <div class="cinema-search-result-info">
          <div class="cinema-search-result-title">${title}</div>
          <div class="cinema-search-result-sub">${type}${year} \u2022 Not in library</div>
        </div>
      </button>`;
    }).join('');
  }

  dropdown.innerHTML = html;
  dropdown.classList.add('visible');
}


_trackedAddEventListener(document.getElementById('searchInput'), 'input', debounce(function(e) {
  performSearch(e.target.value);
}, 250));

_trackedAddEventListener(document, 'keydown', (e) => {
  if (e.key === 'Escape') {
    const dock = document.querySelector('.cinema-dock');
    const playerModal = document.getElementById('player-modal');
    const sidebar = document.getElementById('episode-sidebar');

    // Close episode sidebar first if open
    if (sidebar && sidebar.classList.contains('open')) {
      closeEpisodeSidebar();
      return;
    }

    if (dock && dock.classList.contains('searching')) {
      closeSearch();
    } else if (playerModal?.classList.contains('active')) {
      closePlayerView();
    } else {
      const settingsModal = document.getElementById('settings-modal');
      const detailsModal = document.getElementById('cinema-modal');
      if (settingsModal?.classList.contains('active')) {
        closeSettingsModal();
      } else if (detailsModal?.classList.contains('active')) {
        closeModal();
      }
    }
  }
});

_trackedAddEventListener(document, 'click', (e) => {
  const dock = document.querySelector('.cinema-dock');
  if (dock && dock.classList.contains('searching')) {
    // Skip the iOS ghost click (detail === 0) fired when the keyboard opens,
    // and any close attempt within 400ms of opening — both are artifacts of
    // the keyboard-open layout shift, not real outside taps.
    if (e.detail === 0) return;
    if (Date.now() - (window._searchOpenedAt || 0) < 400) return;
    if (!dock.contains(e.target)) {
      closeSearch();
    }
  }
});

// ============================================================
// Carousel Arrow Navigation
// ============================================================
_trackedAddEventListener(document, 'click', (e) => {
  const arrow = e.target.closest('.cinema-carousel-arrow');
  if (!arrow) return;
  const carouselId = arrow.dataset.carousel;
  const carousel = document.getElementById(carouselId);
  if (!carousel) return;
  const cardWidth = carousel.querySelector('.cinema-card')?.offsetWidth || 144;
  const gap = 16;
  const cardsToScroll = 4;
  const scrollAmount = (cardWidth + gap) * cardsToScroll;
  const direction = arrow.classList.contains('cinema-carousel-arrow-left') ? -1 : 1;
  carousel.scrollBy({ left: scrollAmount * direction, behavior: 'smooth' });
});

function updateCarouselArrows() {
  document.querySelectorAll('.cinema-carousel').forEach(carousel => {
    const wrapper = carousel.closest('.cinema-carousel-wrapper');
    if (!wrapper) return;
    const leftArrow = wrapper.querySelector('.cinema-carousel-arrow-left');
    const rightArrow = wrapper.querySelector('.cinema-carousel-arrow-right');
    if (leftArrow) leftArrow.style.display = carousel.scrollLeft > 10 ? 'flex' : 'none';
    if (rightArrow) {
      const maxScroll = carousel.scrollWidth - carousel.clientWidth;
      rightArrow.style.display = carousel.scrollLeft < maxScroll - 10 ? 'flex' : 'none';
    }
  });
}

document.querySelectorAll('.cinema-carousel').forEach(c => _trackedAddEventListener(c, 'scroll', updateCarouselArrows));
_trackedAddEventListener(window, 'resize', function() {
  updateCarouselArrows();
  updateSeasonScrollButtons();
});

// === Show All / Show Less toggle ===
function renderShowAllMessage(text, opts) {
  opts = opts || {};
  const mode = opts.mode || _showAllMode || 'movies';
  const grid = document.getElementById(`${mode}-show-all-grid`);
  if (!grid) return;
  let el = document.getElementById(`${mode}-show-all-message`);
  if (!el) {
    el = document.createElement('div');
    el.id = `${mode}-show-all-message`;
    el.style.cssText = 'display:flex;flex-direction:column;align-items:center;justify-content:center;gap:1rem;width:100%;padding:3rem 24px;text-align:center;color:rgba(255,255,255,0.5);';
  }
  if (el.parentNode !== grid) grid.appendChild(el);
  el.style.display = 'flex';
  el.innerHTML = (opts.loading ? '<i class="fa-solid fa-spinner fa-spin" style="font-size:2rem;"></i>' : '') + '<div>' + text + '</div>';
  if (opts.retry) {
    const btn = document.createElement('button');
    btn.style.cssText = 'background:rgba(255,255,255,0.1);border:1px solid rgba(255,255,255,0.25);color:#fff;border-radius:6px;padding:0.5rem 1.5rem;font-size:0.9rem;cursor:pointer;';
    btn.textContent = 'Retry';
    btn.onclick = () => { catalogPromise = null; catalogPromise = fetchCatalog(); showAll(mode); };
    el.appendChild(btn);
  }
  return el;
}

function toggleShowAll(mode) {
  if (_showAllMode === mode) showLess();
  else showAll(mode);
}

async function showAll(mode) {
  if (_showAllMode && _showAllMode !== mode) showLess();
  _showAllMode = mode;
  const category = mode === 'movies' ? 'movies' : 'shows';
  const section = document.querySelector(`.cinema-section[data-category="${category}"]`);
  if (!section) return;
  const carouselWrapper = section.querySelector('.cinema-carousel-wrapper');
  if (carouselWrapper) carouselWrapper.style.display = 'none';
  let grid = document.getElementById(`${mode}-show-all-grid`);
  if (!grid) {
    grid = document.createElement('div');
    grid.id = `${mode}-show-all-grid`;
    grid.style.cssText = 'display: flex; flex-wrap: wrap; gap: 1rem; padding: 0 24px 1rem;';
    section.insertBefore(grid, carouselWrapper);
  }
  grid.innerHTML = '';
  renderShowAllMessage('Loading…', { loading: true, mode });
  try {
    await awaitCatalog();
  } catch (e) {
    renderShowAllMessage('Failed to load the video catalog.', { retry: true, mode });
    return;
  }
  if (_showAllMode !== mode) return; // user left Show All while the catalog was loading
  _showAllData = mode === 'movies' ? allVideos.filter(v => v.type === 'movie') : [...allTvShows];
  _showAllIndex = 0;
  sortShowAll(_showAllSort);
  loadMoreShowAll();
  const sc = document.getElementById(`${mode}-sort-controls`);
  if (sc) sc.style.display = 'flex';
  const btn = document.getElementById(`${mode}-show-all-btn`);
  if (btn) btn.textContent = 'Back';
  if (_showAllObserver) _showAllObserver.disconnect();
  let sentinel = document.getElementById(`${mode}-show-all-sentinel`);
  if (!sentinel) {
    sentinel = document.createElement('div');
    sentinel.id = `${mode}-show-all-sentinel`;
    sentinel.style.cssText = 'width: 100%; height: 1px;';
    section.appendChild(sentinel);
  }
  _showAllObserver = new IntersectionObserver((entries) => {
    if (entries[0].isIntersecting) loadMoreShowAll();
  }, { rootMargin: '200px' });
  _showAllObserver.observe(sentinel);
}

function showLess() {
  if (!_showAllMode) return;
  if (_showAllObserver) { _showAllObserver.disconnect(); _showAllObserver = null; }
  const sentinel = document.getElementById(`${_showAllMode}-show-all-sentinel`);
  if (sentinel) sentinel.remove();
  const grid = document.getElementById(`${_showAllMode}-show-all-grid`);
  if (grid) grid.remove();
  const category = _showAllMode === 'movies' ? 'movies' : 'shows';
  const section = document.querySelector(`.cinema-section[data-category="${category}"]`);
  if (!section) { _showAllMode = null; return; }
  const cw = section.querySelector('.cinema-carousel-wrapper');
  if (cw) cw.style.display = '';
  const sc = document.getElementById(`${_showAllMode}-sort-controls`);
  if (sc) sc.style.display = 'none';
  const btn = document.getElementById(`${_showAllMode}-show-all-btn`);
  if (btn) btn.textContent = 'Show All';
  _showAllMode = null;
}

function loadMoreShowAll() {
  if (_showAllData.length === 0) {
    if (_showAllObserver) _showAllObserver.disconnect();
    const grid = document.getElementById(`${_showAllMode}-show-all-grid`);
    if (grid) grid.innerHTML = '';
    renderShowAllMessage('No movies/TV shows found.');
    return;
  }
  if (_showAllIndex >= _showAllData.length) {
    if (_showAllObserver) _showAllObserver.disconnect();
    return;
  }
  const grid = document.getElementById(`${_showAllMode}-show-all-grid`);
  if (!grid) return;
  const batch = _showAllData.slice(_showAllIndex, _showAllIndex + _SHOW_ALL_BATCH);
  grid.innerHTML += batch.map(v => createCardHTML(v)).join('');
  _showAllIndex += _SHOW_ALL_BATCH;
}

function sortShowAll(sortBy) {
  _showAllSort = sortBy;
  _showAllData.sort((a, b) => {
    if (sortBy === 'title') return (a.title || '').toLowerCase().localeCompare((b.title || '').toLowerCase());
    if (sortBy === 'dateAdded') return (b.dateAdded || '').localeCompare(a.dateAdded || '');
    if (sortBy === 'lastWatched') return (b.lastWatched || b.dateAdded || '').localeCompare(a.lastWatched || a.dateAdded || '');
    return 0;
  });
  _showAllIndex = 0;
  const grid = document.getElementById(`${_showAllMode}-show-all-grid`);
  if (grid) grid.innerHTML = '';
  loadMoreShowAll();
  const sc = document.getElementById(`${_showAllMode}-sort-controls`);
  if (sc) {
    sc.querySelectorAll('button').forEach(b => {
      b.style.color = b.dataset.sort === sortBy ? 'white' : 'rgba(255,255,255,0.6)';
    });
  }
}

