// Cinema view: Hero rotation, continue-watching carousel, section router, catalog loading, carousel arrows.
// Split out of cinema-mode.js. Loaded by views/video.html in a fixed order;
// these are classic scripts, so they share one global scope.
// ============================================================
// Hero Rotation
// ============================================================
function updateHero(index) {
  if (!heroItems.length || index >= heroItems.length) return;
  // A pending rotation timer can fire after the SPA left /video; the hero
  // elements no longer exist then (this used to throw at hero-play-btn).
  if (!document.getElementById('hero-play-btn')) return;
  currentHeroIndex = index;
  const item = heroItems[index];

  // Reset hero gradient
  const heroGradient = document.getElementById('hero-color-gradient');
  if (heroGradient) {
    heroGradient.style.background = '';
    heroGradient.classList.remove('active');
  }

  const backdrop = document.getElementById('hero-backdrop');
  const desc = document.getElementById('hero-desc');
  const meta = document.getElementById('hero-meta');
  const rating = document.getElementById('hero-rating');
  const stars = document.getElementById('hero-stars');

  // Fade out
  const content = document.getElementById('hero-content');
  content.style.opacity = '0';
  content.style.transition = 'opacity 0.3s ease';

  setTimeout(() => {
    if (item.thumbnailPath || item.id) {
      const heroSeriesData = item.series?.id ? seriesLookup.get(String(item.series.id)) : null;
      const heroSrc = heroSeriesData
        ? getSeriesImageUrl(heroSeriesData.id, 'hero')
        : getHeroUrl(item.id);
      backdrop.src = heroSrc;
      backdrop.onload = () => extractHeroColor(backdrop);
      var ambient = document.getElementById('ambient-blur-img');
      if (ambient) ambient.src = heroSrc;
    }
    const logoImg = document.getElementById('hero-logo-img');
    if (logoImg) {
      const heroSeriesData = item.series?.id ? seriesLookup.get(String(item.series.id)) : null;
      logoImg.src = heroSeriesData
        ? getSeriesImageUrl(heroSeriesData.id, 'logo')
        : getLogoUrl(item.id);
      logoImg.style.maxWidth = '288px';
      logoImg.style.maxHeight = '80px';
      logoImg.style.display = 'block';
      logoImg.classList.remove('is-skeleton');

      // Hide the text title left over from the previous hero item so it cannot linger
      var wrap = document.getElementById('hero-title-wrap');
      var textEl = wrap ? wrap.querySelector('.cinema-hero-title') : null;
      if (textEl) textEl.style.display = 'none';

      var showTextTitle = function() {
        if (!wrap) return;
        if (!textEl) {
          textEl = document.createElement('h1');
          textEl.className = 'cinema-hero-title';
          wrap.appendChild(textEl);
        }
        textEl.textContent = item.title || item.seriesTitle || '';
        textEl.style.display = 'block';
      };

      logoImg.onload = function() {
        // A generic /logo.png response means no real logo exists — fall back to text
        if (logoImg.src.includes('/logo.png')) {
          logoImg.style.display = 'none';
          showTextTitle();
        } else if (textEl) {
          textEl.style.display = 'none';
        }
      };
      logoImg.onerror = function() {
        logoImg.style.display = 'none';
        showTextTitle();
      };
    }
    const descText = item.description || item.overview || '';
    if (descText) {
      desc.textContent = descText;
      desc.classList.remove('is-skeleton');
    } else {
      desc.textContent = '';
      desc.classList.add('is-skeleton');
    }
    const heroRating = parseFloat(item.imdbRating || item.tmdbRating || 0);
    rating.textContent = heroRating > 0 ? heroRating.toFixed(1) : '';
    rating.style.color = heroRating > 0 ? ratingColor(heroRating) : '';
    stars.innerHTML = heroRating > 0 ? renderStars(heroRating, ratingColor(heroRating)) : '';

    const yearEl = document.getElementById('hero-year');
    const year = item.releaseYear || '';
    if (yearEl) yearEl.textContent = year ? '\u00A0\u2022\u00A0' + year : '';

    const duration = formatDuration(item.duration);
    const type = item.type === 'movie' ? 'Movie' : 'TV Show';
    meta.innerHTML = [duration, type].filter(Boolean).map(s => `<span>${s}</span><span class="cinema-hero-meta-divider"></span>`).join('');
    meta.lastElementChild?.remove();

    // Update play button
    document.getElementById('hero-play-btn').onclick = () => playHeroVideo(item);
    document.getElementById('hero-watchlist-btn').onclick = () => toggleWatchlist(item.id);

    // Set watchlist button data attribute and initial icon
    const heroWatchBtn = document.getElementById('hero-watchlist-btn');
    if (heroWatchBtn) {
      heroWatchBtn.dataset.watchlistId = item.id;
      const heroIcon = heroWatchBtn.querySelector('i');
      if (heroIcon) heroIcon.className = item.favorite ? 'fa-solid fa-check' : 'fa-solid fa-plus';
      heroWatchBtn.title = item.favorite ? 'Remove from Watchlist' : 'Add to Watchlist';
    }
    document.getElementById('hero-info-btn').onclick = () => openDetails(item.id);

    // Fade in
    content.style.opacity = '1';
  }, 300);

  // Update timer indicators
  updateTimerIndicators(index);
}

function updateTimerIndicators(activeIndex) {
  const container = document.getElementById('heroTimers');
  if (!container) return;
  container.innerHTML = '';
  heroItems.forEach((_, i) => {
    const btn = document.createElement('button');
    btn.className = 'cinema-timer-btn' + (i === activeIndex ? ' active' : '');
    btn.innerHTML = `
      <svg viewBox="0 0 28 28">
        <circle class="track" cx="14" cy="14" r="10"></circle>
        <circle class="progress" cx="14" cy="14" r="10"></circle>
      </svg>
      <div class="cinema-timer-dot"></div>
    `;
    btn.onclick = () => { resetHeroTimer(); updateHero(i); };
    container.appendChild(btn);
  });
}

function startHeroRotation() {
  if (heroItems.length <= 1) return;
  heroTimer = setInterval(() => {
    const next = (currentHeroIndex + 1) % heroItems.length;
    updateHero(next);
  }, HERO_INTERVAL);
}

function resetHeroTimer() {
  clearInterval(heroTimer);
  startHeroRotation();
}

// ============================================================
// Continue Watching Carousel
// ============================================================
function renderContinueWatchingCarousel(items) {
  const container = document.getElementById('continue-watching-carousel');
  const section = document.getElementById('section-continue-watching');
  if (!container || !section || !items.length) return;

  container.innerHTML = items.map(item => {
    const title = item.title || item.seriesTitle || 'Untitled';
    const isEpisode = item.type === 'episode';
    const meta = isEpisode
      ? `S${item.seasonNumber ?? 1} E${item.episodeNumber || 1}${item.duration ? ' - ' + formatRemainingTime(item.duration, item.watchProgressPercent) : ''}`
      : (item.releaseYear ? item.releaseYear : '');
    const imgSrc = getBackdropUrl(item) || getThumbnailUrl(item.id);
    const progress = Math.min(100, Math.max(0, item.watchProgressPercent || 0));

    return `
      <div class="cw-card" data-cw-id="${item.id}" data-cw-type="${item.type}" data-cw-series="${encodeURIComponent(item.seriesTitle || '')}" data-click="playContinueWatchingFromCard">
        <div class="cw-card-img-wrap">
          <img src="${imgSrc}" alt="${title}" loading="lazy">
          <div class="cw-play-overlay">
            <button class="cw-play-btn" data-click="playContinueWatchingFromCard" data-cw-id="${item.id}" data-cw-type="${item.type}" data-cw-series="${encodeURIComponent(item.seriesTitle || '')}" data-stop-propagation>
              <i class="fa-solid fa-play"></i>
            </button>
          </div>
          <button class="cw-remove-btn" onclick="event.stopPropagation(); removeContinueWatching(${item.id})" title="Remove">
            <i class="fa-solid fa-xmark"></i>
          </button>
          <div class="cw-progress-bar">
            <div class="cw-progress-fill" style="width: ${progress}%"></div>
          </div>
        </div>
        <div class="cw-card-info">
          <div class="cw-card-title">${title}</div>
          <div class="cw-card-meta">${meta}</div>
        </div>
      </div>
    `;
  }).join('');

  section.style.display = '';
  updateCarouselArrows();
}

function renderFilteredContinueWatching(section) {
  let filtered;
  if (section === 'movies') {
    filtered = allContinueWatching.filter(item => item.type === 'movie');
  } else if (section === 'shows') {
    filtered = allContinueWatching.filter(item => item.type === 'episode');
  } else {
    filtered = allContinueWatching;
  }
  if (filtered.length > 0) {
    renderContinueWatchingCarousel(filtered);
  } else {
    const sectionEl = document.getElementById('section-continue-watching');
    if (sectionEl) sectionEl.style.display = 'none';
  }
}

function formatRemainingTime(durationMs, progressPercent) {
  if (!durationMs) return '';
  const remainingSec = (durationMs / 1000) * (1 - (progressPercent || 0) / 100);
  const mins = Math.ceil(remainingSec / 60);
  if (mins < 60) return `${mins}m left`;
  const hrs = Math.floor(mins / 60);
  const remainMins = mins % 60;
  return remainMins > 0 ? `${hrs}h ${remainMins}m left` : `${hrs}h left`;
}

function playContinueWatching(id, type, seriesTitle) {
  if (type === 'episode' && seriesTitle) {
    openSeriesDetail(seriesTitle, id);
  } else {
    openDetails(id);
  }
}

function resolveResumeEpisode(episodes, seriesTitle, resumeEpisodeId) {
  if (resumeEpisodeId != null) {
    return episodes.find(e => Number(e.id) === Number(resumeEpisodeId)) || null;
  }
  const cw = allContinueWatching.find(item =>
    item.type === 'episode' &&
    item.seriesTitle && item.seriesTitle.toLowerCase() === seriesTitle.toLowerCase()
  );
  if (cw) {
    const match = episodes.find(e => Number(e.id) === Number(cw.id));
    if (match) return match;
  }
  return episodes
    .filter(e => !e.watched && e.watchProgress > 0 && e.watchProgress < 0.95)
    .sort((a, b) => (b.watchProgress || 0) - (a.watchProgress || 0))[0] || null;
}

function getCurrentCinemaSection() {
  return new URLSearchParams(window.location.search).get('section') || 'home';
}

function updateContinueWatchingBadge() {
  const heroBadge = document.getElementById('hero-badge');
  if (!heroBadge) return;
  if (allContinueWatching.length > 0) {
    heroBadge.innerHTML = '<i class="fa-solid fa-clock-rotate-left" style="color: #60a5fa;"></i> Continue Watching';
    heroBadge.style.display = '';
  } else {
    heroBadge.style.display = 'none';
  }
}

async function removeContinueWatching(id) {
  const idNum = Number(id);
  try {
    const resp = await fetch(`/api/video/progress/${id}/remove-from-continue-watching`, { method: 'POST' });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);

    // Removing an episode takes the whole series off Continue Watching,
    // otherwise the previous in-progress episode would take its place.
    const target = allContinueWatching.find(item => Number(item.id) === idNum);
    if (target && target.type === 'episode' && target.seriesTitle) {
      const seriesKey = target.seriesTitle.toLowerCase().replace(/[^a-z0-9]/g, '');
      allContinueWatching = allContinueWatching.filter(item =>
        !(item.type === 'episode' && item.seriesTitle &&
          item.seriesTitle.toLowerCase().replace(/[^a-z0-9]/g, '') === seriesKey)
      );
    } else {
      allContinueWatching = allContinueWatching.filter(item => Number(item.id) !== idNum);
    }

    const section = getCurrentCinemaSection();
    if (section === 'home' || section === 'movies' || section === 'shows') {
      renderFilteredContinueWatching(section);
    }
    updateContinueWatchingBadge();
  } catch (e) {
    console.error('Failed to remove from continue watching:', e);
  }
}



// ============================================================
// Data Fetching
// ============================================================
// video.html (cinema) does not load video-spa.js, so the Now Playing
// section stays an empty shell unless the controller is loaded here.
function ensureNowPlayingController() {
  if (window.videoSidebarController) {
    window.videoSidebarController._lastStructKey = null;
    return;
  }
  if (window.VideoSidebarController) {
    window.videoSidebarController = new window.VideoSidebarController();
    return;
  }
  if (window.__npControllerScriptLoading) return;
  window.__npControllerScriptLoading = true;
  var s = document.createElement('script');
  s.src = '/js/video/VideoSidebarController.js';
  document.head.appendChild(s);
  var attempts = 0;
  var pollId = setInterval(function() {
    if (window.VideoSidebarController) {
      clearInterval(pollId);
      if (!window.videoSidebarController) {
        window.videoSidebarController = new window.VideoSidebarController();
      }
      window.__npControllerScriptLoading = false;
    } else if (++attempts >= 20) {
      clearInterval(pollId);
      window.__npControllerScriptLoading = false;
      console.warn('[cinema] ensureNowPlayingController: VideoSidebarController failed to load after 20 attempts');
    }
  }, 50);
}

async function showSection(section) {
  // Each navigation supersedes any previous async section render.
  const viewGen = ++_dynamicViewGen;
  const allSections = document.querySelectorAll('.cinema-section');
  const hero = document.getElementById('cinema-hero');
  const dynEl = document.getElementById('section-dynamic');
  if (dynEl) dynEl.classList.toggle('np-view-active', section === 'nowPlaying');
  const _showAllModeSection = _showAllMode === 'tvshows' ? 'shows' : _showAllMode;
  if (_showAllMode && section !== _showAllModeSection) { showLess(); }

  // Handle dynamic sections (liveTv, collections, nowPlaying)
  if (section === 'liveTv' || section === 'collections' || section === 'nowPlaying') {
    splashShow(true);
    // Hide all static sections
    allSections.forEach(s => { s.style.display = 'none'; });
    document.querySelectorAll('.cinema-section-header[id]').forEach(h => h.style.display = 'none');

    // Hide the cinema hero on the Now Playing view (player-focused, no hero needed)
    if (hero) hero.style.display = section === 'nowPlaying' ? 'none' : '';

    // Show dynamic container (hero stays visible for liveTv/collections)
    const dynamicSection = document.getElementById('section-dynamic');
    if (dynamicSection) dynamicSection.style.display = '';

    const container = document.getElementById('dynamic-content');
    container.innerHTML = '<div style="text-align:center;padding:3rem;color:rgba(255,255,255,0.5);"><i class="fa-solid fa-spinner fa-spin" style="font-size:2rem;"></i></div>';

    if (section === 'liveTv') {
      Promise.resolve(renderLiveTvSection(container, viewGen)).finally(splashHide);
    } else if (section === 'collections') {
      Promise.resolve(renderCollectionsSection(container, viewGen)).finally(splashHide);
    } else if (section === 'nowPlaying') {
      // Keep fragment approach for nowPlaying (mobile-only)
      try {
        const resp = await fetch('/api/video/ui/now-playing-fragment');
        if (viewGen !== _dynamicViewGen) return;
        if (resp.ok) {
          container.innerHTML = await resp.text();
          // Populate the shell with the current playback state
          ensureNowPlayingController();
        } else {
          container.innerHTML = '<div style="text-align:center;padding:3rem;color:rgba(255,255,255,0.5);">No media currently playing.</div>';
        }
      } catch(e) {
        console.warn('[cinema] now-playing fragment failed', e);
        if (viewGen !== _dynamicViewGen) return;
        container.innerHTML = '<div style="text-align:center;padding:3rem;color:rgba(255,255,255,0.5);">Error loading now playing.</div>';
      }
      splashHide();
    }

    return;
  }

      if (section === 'home') {
    allSections.forEach(s => {
      if (!s.dataset.category || s.dataset.category === 'home' || s.dataset.category === 'movies' || s.dataset.category === 'shows') {
        s.style.display = '';
      } else {
        s.style.display = 'none';
      }
    });
    if (hero) hero.style.display = '';
    refreshCinemaHome(false);
  } else {
    const targetCategory = section === 'movies' ? 'movies' : 'shows';
    allSections.forEach(s => {
      if (s.dataset.category === targetCategory) {
        s.style.display = '';
      } else if (s.id === 'section-continue-watching') {
        // Handled by renderFilteredContinueWatching below
      } else {
        s.style.display = 'none';
      }
    });
    if (hero) hero.style.display = '';
  }

  renderFilteredContinueWatching(section);
  ['movies-show-all-btn','tvshows-show-all-btn'].forEach(id=>{const e=document.getElementById(id);if(e)e.style.display='none';});
  ['movies-sort-controls','tvshows-sort-controls'].forEach(id=>{const e=document.getElementById(id);if(e)e.style.display='none';});
  if(section==='movies'){const b=document.getElementById('movies-show-all-btn');if(b)b.style.display='';}
  else if(section==='shows'){const b=document.getElementById('tvshows-show-all-btn');if(b)b.style.display='';}
}

async function renderLiveTvSection(container, viewGen) {
  if (viewGen === undefined) viewGen = _dynamicViewGen;
  try {
    // Fetch channel groups
    const groupsResp = await fetch('/api/channels/groups');
    if (viewGen !== _dynamicViewGen) return;
    if (!groupsResp.ok) throw new Error('Failed to fetch groups');
    const groupsJson = await groupsResp.json();
    if (viewGen !== _dynamicViewGen) return;
    const groupsData = groupsJson.success ? groupsJson.data : groupsJson;
    const groups = Array.isArray(groupsData) ? groupsData : [];

    if (groups.length === 0) {
      container.innerHTML = '<div style="text-align:center;padding:3rem;color:rgba(255,255,255,0.5);">No live channels available.</div>';
      return;
    }

    // Build the HTML by loading channels per group
    let html = '';
    for (const group of groups) {
      const groupName = group.name || 'Uncategorized';
      html += `
        <section class="cinema-section" style="display:block;margin-bottom:1.5rem;">
          <div class="cinema-section-header">
            <h2 class="cinema-section-title">${groupName} <span style="font-size:0.8rem;color:rgba(255,255,255,0.35);font-weight:400;">(${group.count || 0})</span></h2>
          </div>
          <div class="cinema-carousel-wrapper">
            <button class="cinema-carousel-arrow cinema-carousel-arrow-left" data-carousel="live-${groupName.replace(/[^a-zA-Z0-9]/g, '-')}-carousel"><i class="fa-solid fa-chevron-left"></i></button>
            <div class="cinema-carousel scrollbar-hide" id="live-${groupName.replace(/[^a-zA-Z0-9]/g, '-')}-carousel">
              <div style="text-align:center;padding:2rem;color:rgba(255,255,255,0.3);width:100%;"><i class="fa-solid fa-spinner fa-spin"></i></div>
            </div>
            <button class="cinema-carousel-arrow cinema-carousel-arrow-right" data-carousel="live-${groupName.replace(/[^a-zA-Z0-9]/g, '-')}-carousel"><i class="fa-solid fa-chevron-right"></i></button>
          </div>
        </section>
      `;
    }
    container.innerHTML = html;

    // Load channels for each group in parallel
    const loadPromises = groups.map(async (group) => {
      const groupName = group.name || 'Uncategorized';
      const carouselId = 'live-' + groupName.replace(/[^a-zA-Z0-9]/g, '-') + '-carousel';
      const carousel = document.getElementById(carouselId);
      if (!carousel) return;

      try {
        const resp = await fetch('/api/channels?group=' + encodeURIComponent(groupName) + '&limit=50');
        if (viewGen !== _dynamicViewGen) return;
        if (!resp.ok) throw new Error('Failed');
        const json = await resp.json();
        if (viewGen !== _dynamicViewGen) return;
        const data = json.success ? json.data : json;
        const channels = Array.isArray(data) ? data : [];
        if (channels.length === 0) {
          carousel.innerHTML = '<div style="text-align:center;padding:2rem;color:rgba(255,255,255,0.3);width:100%;">No channels</div>';
          return;
        }
        carousel.innerHTML = channels.map(ch => createChannelCard(ch)).join('');
      } catch (e) {
        console.warn('[cinema] live channel group failed: ' + groupName, e);
        if (viewGen !== _dynamicViewGen) return;
        carousel.innerHTML = '<div style="text-align:center;padding:2rem;color:rgba(255,255,255,0.3);width:100%;">Error loading</div>';
      }
    });

    await Promise.all(loadPromises);
    if (viewGen !== _dynamicViewGen) return;
    updateCarouselArrows();

  } catch (e) {
    console.warn('[cinema] Live TV section failed', e);
    if (viewGen !== _dynamicViewGen) return;
    container.innerHTML = '<div style="text-align:center;padding:3rem;color:rgba(255,255,255,0.5);">Failed to load Live TV.</div>';
  }
}

async function renderCollectionsSection(container, viewGen) {
  if (viewGen === undefined) viewGen = _dynamicViewGen;
  try {
    const resp = await fetch('/api/collections');
    if (viewGen !== _dynamicViewGen) return;
    if (!resp.ok) throw new Error('Failed to fetch collections');
    const json = await resp.json();
    if (viewGen !== _dynamicViewGen) return;
    const data = json.success ? json.data : json;
    const collections = Array.isArray(data) ? data : [];

    if (collections.length === 0) {
      container.innerHTML = '<div style="text-align:center;padding:3rem;color:rgba(255,255,255,0.5);">No collections yet.</div>';
      return;
    }

    // Render as a carousel of collection cards
    let html = `
      <div class="cinema-section" style="display:block;">
        <div class="cinema-section-header">
          <h2 class="cinema-section-title">All Collections</h2>
        </div>
        <div class="cinema-carousel-wrapper">
          <button class="cinema-carousel-arrow cinema-carousel-arrow-left" data-carousel="collections-carousel"><i class="fa-solid fa-chevron-left"></i></button>
          <div class="cinema-carousel scrollbar-hide" id="collections-carousel">
    `;
    html += collections.map(c => createCollectionCard(c)).join('');
    html += '</div><button class="cinema-carousel-arrow cinema-carousel-arrow-right" data-carousel="collections-carousel"><i class="fa-solid fa-chevron-right"></i></button></div></div>';
    container.innerHTML = html;

  } catch (e) {
    console.warn('[cinema] Collections section failed', e);
    if (viewGen !== _dynamicViewGen) return;
    container.innerHTML = '<div style="text-align:center;padding:3rem;color:rgba(255,255,255,0.5);">Failed to load Collections.</div>';
  }
}

// ============================================================
// Catalog loader — single-flight promise over /api/video/videos
// ============================================================
var catalogPromise = null;

var fetchCatalog = () => {
  if (catalogPromise) return catalogPromise;
  catalogPromise = fetch('/api/video/videos').then(r => r.ok ? r.json() : []).then(data => {
    const d = data && typeof data === 'object' && data.success !== undefined && data.data !== undefined ? data.data : data;
    allVideos = Array.isArray(d) ? d : [];
    // Collect TV shows (episodes deduplicated by seriesTitle)
    const seenTitles = new Set();
    allTvShows = allVideos.filter(v => v.type === 'episode' && v.seriesTitle)
      .filter(v => { const key = v.seriesTitle.toLowerCase().replace(/[^a-z0-9]/g, ''); if (seenTitles.has(key)) return false; seenTitles.add(key); return true; });
    return allVideos;
  }).catch(err => {
    // Do NOT swallow: keep the promise rejected so the caller can surface the failure in showAll.
    console.error('Failed to load video catalog:', err);
    throw err;
  });
  return catalogPromise;
};

function awaitCatalog() {
  if (!catalogPromise) catalogPromise = fetchCatalog();
  return catalogPromise;
}

async function loadCinemaData() {
  const params = new URLSearchParams(window.location.search);
  const section = params.get('section') || 'home';

  // Fetch series data for lookup map (used by multiple features). The default
  // page size is 50, which left most shows out of the lookup and made cards
  // fall back to episode frame grabs instead of the series poster.
  const [seriesResp, cwResp] = await Promise.all([
    fetch('/api/series?page=0&size=2000').then(r => r.ok ? r.json() : null).catch(() => null),
    fetch('/api/video/continue-watching').then(r => r.ok ? r.json() : null).catch(() => null)
  ]);

  // Parse series data into lookup map
  const parseResponse = (json) => {
    if (json && typeof json === 'object' && json.success !== undefined && json.data !== undefined) return json.data;
    return json;
  };
  if (seriesResp) {
    const seriesDataParsed = parseResponse(seriesResp);
    const seriesArray = Array.isArray(seriesDataParsed) ? seriesDataParsed
      : (seriesDataParsed && Array.isArray(seriesDataParsed.series) ? seriesDataParsed.series : []);
    allSeries = seriesArray;
    seriesLookup.clear();
    allSeries.forEach(s => seriesLookup.set(String(s.id), s));
  }
  const cwData = Array.isArray(cwResp) ? cwResp : (cwResp ? parseResponse(cwResp) : []);
  allContinueWatching = Array.isArray(cwData) ? cwData : [];

  // Fetch server-rendered cinema home fragment (carousels + hero data)
  const fragmentResp = await fetch('/api/video/ui/cinema-home-fragment').catch(() => null);
  if (fragmentResp && fragmentResp.ok) {
    const html = await fragmentResp.text();
    // Insert into the cinema-sections container (replacing any placeholder)
    const container = document.querySelector('.cinema-sections');
    if (container) {
    container.innerHTML = html;
    }
    const scriptTag = document.getElementById('cinema-home-data');
    if (scriptTag) {
      try {
        const heroData = JSON.parse(scriptTag.textContent);
        if (Array.isArray(heroData) && heroData.length > 0) {
          heroItems = heroData;
          updateHero(0);
          startHeroRotation();
        }
      } catch (e) {
        console.warn('Failed to parse cinema home data', e);
      }
    }
    _lastHomeRefresh = Date.now();
    // Update hero badge
    updateContinueWatchingBadge();
    // Render continue-watching carousel if server didn't (server does include it)
    // Initialize carousel arrow visibility
    updateCarouselArrows();
  }

  // Lazy-load allVideos in background for other features (series detail, sidebar, search).
  // Kick-off stays here (same timing as before); showAll awaits this same promise.
  catalogPromise = fetchCatalog();
  // Suppress unhandledrejection noise when Show All is never opened; the
  // rejection stays observable via awaitCatalog() (showAll surfaces it).
  catalogPromise.catch((e) => console.warn('[cinema] catalog prefetch failed (surfaced on Show All)', e));

  showSection(section);

  // Set initial active dock state
  document.querySelectorAll('.cinema-dock-item').forEach(i => i.classList.remove('active'));
  if (section !== 'home') {
    const activeItem = document.querySelector(`.cinema-dock-item[data-section="${section}"]`);
    if (activeItem) activeItem.classList.add('active');
  } else {
    document.querySelector('.cinema-dock-item')?.classList.add('active');
  }
}

/**
 * Re-fetch the server-rendered home fragment (carousels + hero) so navigating
 * back to Home or returning to the tab does not keep showing the DOM captured at
 * page load. Throttled because the dock and the popstate/visibility handlers can
 * all fire in quick succession.
 */
async function refreshCinemaHome(force) {
  const now = Date.now();
  if (!force && now - _lastHomeRefresh < 15000) return;
  _lastHomeRefresh = now;

  const [fragmentResp, cwResp] = await Promise.all([
    fetch('/api/video/ui/cinema-home-fragment', { cache: 'no-store' }).catch(() => null),
    fetch('/api/video/continue-watching', { cache: 'no-store' }).catch(() => null)
  ]);

  if (cwResp && cwResp.ok) {
    try {
      const cwJson = await cwResp.json();
      const cwData = Array.isArray(cwJson) ? cwJson
        : (cwJson && cwJson.data !== undefined ? cwJson.data : cwJson);
      if (Array.isArray(cwData)) allContinueWatching = cwData;
    } catch (e) {
      console.warn('[cinema] continue-watching refresh failed', e);
    }
  }

  if (!fragmentResp || !fragmentResp.ok) return;
  const html = await fragmentResp.text();
  const container = document.querySelector('.cinema-sections');
  if (!container) return;
  container.innerHTML = html;

  const scriptTag = document.getElementById('cinema-home-data');
  if (scriptTag) {
    try {
      const heroData = JSON.parse(scriptTag.textContent);
      if (Array.isArray(heroData) && heroData.length > 0) {
        heroItems = heroData;
        updateHero(0);
        if (!heroTimer) startHeroRotation();
      }
    } catch (e) {
      console.warn('[cinema] failed to parse refreshed home data', e);
    }
  }
  updateContinueWatchingBadge();
  updateCarouselArrows();
}

