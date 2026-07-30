
// ============================================================
// Cinema Mode: force-fix SPA shell for scrollable cinema content
// ============================================================
(function() {
  function applyCinemaOverrides() {
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
  function _startObserver() {
    if (_observer) _observer.disconnect();
    _observer = new MutationObserver(function() {
      applyCinemaOverrides();
    });
    var root = document.getElementById('app-content') || document.body;
    _observer.observe(root, { childList: true, subtree: true });
    window._cinemaObserver = _observer;
  }

  applyCinemaOverrides();
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', applyCinemaOverrides);
  }
  _startObserver();

  window._cleanupCinemaMode = function() {
    if (_observer) {
      _observer.disconnect();
      _observer = null;
      window._cinemaObserver = null;
    }
  };

  window.addEventListener('pagehide', window._cleanupCinemaMode);
  window.addEventListener('beforeunload', window._cleanupCinemaMode);

  // Reconnect observer when SPA routes back to cinema view
  window.addEventListener('popstate', function() {
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
let heroItems = [];
var allVideos = [];
var allTvShows = [];
let currentHeroIndex = 0;
let heroTimer = null;
const HERO_INTERVAL = 15000;
let allSeries = [];
const seriesLookup = new Map();
let allContinueWatching = [];
let _modalGeneration = 0;

// Show All state
let _showAllMode = null;
let _showAllData = [];
let _showAllIndex = 0;
let _showAllSort = 'dateAdded';
let _showAllObserver = null;
const _SHOW_ALL_BATCH = 20;

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

function renderStars(rating) {
  const stars = Math.round((rating || 0) / 2);
  const svgNS = 'http://www.w3.org/2000/svg';
  let html = '';
  for (let i = 0; i < 5; i++) {
    const filled = i < stars;
    const halfFilled = !filled && i < stars + 0.5;
    const fillColor = 'var(--cinema-text, #ffffff)';
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
  const year = video.releaseYear || '';
  const rating = video.imdbRating || video.tmdbRating || '';
  const imgSrc = getThumbnailUrl(id);
  return `
    <div class="cinema-card" data-video-id="${id}" data-type="${video.type || 'movie'}" data-title="${title.replace(/"/g, '&quot;')}" data-click="${(video.type === 'episode' && video.seriesTitle) ? 'openSeriesDetailFromCard' : 'openDetailsFromCard'}"${(video.type === 'episode' && video.seriesTitle) ? ` data-series-title="${encodeURIComponent(video.seriesTitle || '')}"` : ''}>
      <div class="cinema-card-poster">
        <img src="${imgSrc}" alt="${title}" loading="lazy" onerror="this.src='/logo.png'">
        <div class="cinema-card-play-overlay">
          <div class="cinema-card-play-icon">
            <i class="fa-solid fa-play"></i>
          </div>
        </div>
      </div>
      <div class="cinema-card-title">${title}</div>
      <div class="cinema-card-meta"><i class="fa-solid fa-star" style="font-size: 0.7rem;"></i> ${rating} ${year}</div>
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

// ============================================================
// Hero Rotation
// ============================================================
function updateHero(index) {
  if (!heroItems.length || index >= heroItems.length) return;
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
      logoImg.onerror = function() {
        logoImg.style.display = 'none';
        logoImg.onerror = null;
        var wrap = document.getElementById('hero-title-wrap');
        if (wrap) {
          var textEl = wrap.querySelector('.cinema-hero-title');
          if (!textEl) {
            textEl = document.createElement('h1');
            textEl.className = 'cinema-hero-title';
            wrap.appendChild(textEl);
          }
          textEl.textContent = item.title || item.seriesTitle || '';
          textEl.style.display = 'block';
        }
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
    rating.textContent = item.imdbRating || item.tmdbRating ? (Math.round(parseFloat(item.imdbRating || item.tmdbRating || 0) * 10) + '%') : '';
    stars.innerHTML = renderStars(parseFloat(item.imdbRating || item.tmdbRating || 0));

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
    openSeriesDetail(seriesTitle);
  } else {
    openDetails(id);
  }
}

async function removeContinueWatching(id) {
  try {
    await fetch(`/api/video/progress/${id}/toggle-watched`, { method: 'POST' });
    // Remove card from DOM
    const card = document.querySelector(`.cw-card[data-id="${id}"]`);
    if (card) card.remove();
    // Filter out from array for immediate effect
    allContinueWatching = allContinueWatching.filter(item => item.id !== id);
    // Hide section if empty
    const container = document.getElementById('continue-watching-carousel');
    const section = document.getElementById('section-continue-watching');
    if (container && container.children.length === 0 && section) {
      section.style.display = 'none';
    }
  } catch (e) {
    console.error('Failed to remove from continue watching:', e);
  }
}

// ============================================================
// Carousel Rendering
// ============================================================
function renderCarousel(containerId, items) {
  const container = document.getElementById(containerId);
  if (!container || !items.length) return;
  container.innerHTML = items.map(v => createCardHTML(v)).join('');
  // Show parent section
  const section = container.closest('.cinema-section');
  if (section) section.style.display = '';
}

// ============================================================
// Live Channel Card & Playback
// ============================================================
function createChannelCard(channel) {
  const id = channel.id;
  const name = channel.name || 'Untitled';
  const num = channel.channelNumber || '';
  const logo = channel.logoUrl || '';
  const group = channel.groupTitle || '';
  return `
    <div class="cinema-card" data-video-id="${id}" data-type="live-channel" data-title="${name.replace(/"/g, '&quot;')}" onclick="playChannel(${id})">
      <div class="cinema-card-poster">
        <img src="${logo || '/logo.png'}" alt="${name}" loading="lazy" onerror="this.src='/logo.png'">
        <div class="cinema-card-play-overlay">
          <div class="cinema-card-play-icon">
            <i class="fa-solid fa-play"></i>
          </div>
        </div>
      </div>
      <div class="cinema-card-title">${name}</div>
      <div class="cinema-card-meta"><i class="fa-solid fa-tower-broadcast" style="font-size:0.65rem;margin-right:2px;"></i> ${num ? `CH ${num}` : group}</div>
    </div>
  `;
}

async function playChannel(channelId) {
  // Use existing player modal + live channel playback fragment
  const backdrop = document.getElementById('player-modal-backdrop');
  const modal = document.getElementById('player-modal');
  const content = document.getElementById('player-modal-content');
  if (!backdrop || !modal || !content) return;

  backdrop.classList.add('active');
  modal.classList.add('active');
  document.body.style.overflow = 'hidden';

  // Set title
  const titleEl = document.getElementById('player-modal-title');
  if (titleEl) titleEl.textContent = 'Live TV';

  try {
    const resp = await fetch(`/api/video/ui/live-channel-playback-fragment?channelId=${channelId}`);
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const html = await resp.text();

    const tmp = document.createElement('div');
    tmp.innerHTML = html;

    const scripts = Array.from(tmp.querySelectorAll('script'));
    scripts.forEach(s => s.remove());

    content.innerHTML = tmp.innerHTML;

    for (const old of scripts) {
      const el = document.createElement('script');
      if (old.src) {
        for (const attr of old.attributes) el.setAttribute(attr.name, attr.value);
        el.async = false;
      } else {
        el.textContent = old.textContent;
      }
      content.appendChild(el);
    }
  } catch (err) {
    console.error('[cinema] failed to load channel playback', err);
    content.innerHTML = '<div style="display:flex;align-items:center;justify-content:center;height:100%;color:rgba(255,255,255,0.5);">Failed to load channel</div>';
  }
  closeEpisodeSidebar();
}

// ============================================================
// Collection Card & Detail
// ============================================================
function createCollectionCard(collection) {
  const id = collection.id;
  const name = collection.name || 'Untitled';
  const desc = collection.description || '';
  const coverId = collection.coverVideoId || null;
  const imgSrc = coverId ? `/api/video/thumbnail/${coverId}` : '';
  return `
    <div class="cinema-card" data-video-id="" data-type="collection" data-title="${name.replace(/"/g, '&quot;')}" onclick="openCollectionDetail(${id})">
      <div class="cinema-card-poster">
        <img src="${imgSrc || '/logo.png'}" alt="${name}" loading="lazy" onerror="this.src='/logo.png'">
        <div class="cinema-card-play-overlay">
          <div class="cinema-card-play-icon">
            <i class="fa-solid fa-eye"></i>
          </div>
        </div>
      </div>
      <div class="cinema-card-title">${name}</div>
      <div class="cinema-card-meta"><i class="fa-solid fa-layer-group" style="font-size:0.65rem;margin-right:2px;"></i> ${desc ? desc.substring(0, 60) + (desc.length > 60 ? '…' : '') : 'Collection'}</div>
    </div>
  `;
}

let _collectionDataCache = null;

async function openCollectionDetail(collectionId) {
  // Close any existing player modal
  closePlayerModal();

  const hero = document.getElementById('cinema-hero');
  const allSections = document.querySelectorAll('.cinema-section');
  const dynamicSection = document.getElementById('section-dynamic');

  allSections.forEach(s => { s.style.display = 'none'; });

  // Show the dynamic section (hero stays visible)
  if (dynamicSection) dynamicSection.style.display = '';

  const container = document.getElementById('dynamic-content');
  container.innerHTML = '<div style="text-align:center;padding:3rem;color:rgba(255,255,255,0.5);"><i class="fa-solid fa-spinner fa-spin" style="font-size:2rem;"></i></div>';

  try {
    const [collectionResp, entriesResp] = await Promise.all([
      fetch('/api/collections/' + collectionId),
      fetch('/api/collections/' + collectionId + '/entries')
    ]);

    if (!collectionResp.ok || !entriesResp.ok) throw new Error('Failed to fetch');

    const collectionData = await collectionResp.json();
    const entriesData = await entriesResp.json();

    // Parse ApiResponse wrappers
    const collection = (collectionData && collectionData.success ? collectionData.data : collectionData);
    const entries = (entriesData && entriesData.success ? entriesData.data : entriesData);

    let html = '<div class="collection-detail-header" style="padding:1.5rem 0;">';
    html += '<button onclick="showCollectionsGrid()" style="background:none;border:none;color:rgba(255,255,255,0.6);cursor:pointer;font-size:0.9rem;margin-bottom:0.5rem;"><i class="fa-solid fa-arrow-left"></i> Back to Collections</button>';
    html += '<h2 style="font-size:1.5rem;font-weight:600;margin:0;">' + (collection.name || 'Collection') + '</h2>';
    if (collection.description) html += '<p style="color:rgba(255,255,255,0.5);margin:0.25rem 0 0 0;">' + collection.description + '</p>';
    html += '</div>';

    const items = Array.isArray(entries) ? entries : [];
    if (items.length === 0) {
      html += '<div style="text-align:center;padding:3rem;color:rgba(255,255,255,0.3);">This collection is empty.</div>';
    } else {
      html += '<div class="cinema-carousel scrollbar-hide" style="display:flex;gap:0.5rem;overflow-x:auto;padding:0 0 1rem 0;">';
      for (const entry of items) {
        const videoId = entry.videoId || entry.video?.id || entry.externalVideoId;
        const title = entry.video?.title || entry.title || 'Untitled';
        const imgSrc = videoId ? `/api/video/thumbnail/${videoId}` : '';
        html += '<div class="cinema-card" data-video-id="' + videoId + '" onclick="playVideo(' + videoId + ')" style="cursor:pointer;flex-shrink:0;">';
        html += '  <div class="cinema-card-poster">';
        html += '    <img src="' + imgSrc + '" alt="' + title + '" loading="lazy" onerror="this.src=\'/logo.png\'">';
        html += '    <div class="cinema-card-play-overlay"><div class="cinema-card-play-icon"><i class="fa-solid fa-play"></i></div></div>';
        html += '  </div>';
        html += '  <div class="cinema-card-title">' + title + '</div>';
        html += '</div>';
      }
      html += '</div>';
    }

    container.innerHTML = html;
  } catch(e) {
    container.innerHTML = '<div style="text-align:center;padding:3rem;color:rgba(255,255,255,0.5);">Error loading collection.</div>';
  }
}

async function showCollectionsGrid() {
  const container = document.getElementById('dynamic-content');
  container.innerHTML = '<div style="text-align:center;padding:3rem;color:rgba(255,255,255,0.5);"><i class="fa-solid fa-spinner fa-spin" style="font-size:2rem;"></i></div>';
  await renderCollectionsSection(container);
}

// ============================================================
// Data Fetching
// ============================================================
async function showSection(section) {
  const allSections = document.querySelectorAll('.cinema-section');
  const hero = document.getElementById('cinema-hero');
  const _showAllModeSection = _showAllMode === 'tvshows' ? 'shows' : _showAllMode;
  if (_showAllMode && section !== _showAllModeSection) { showLess(); }

  // Handle dynamic sections (liveTv, collections, nowPlaying)
  if (section === 'liveTv' || section === 'collections' || section === 'nowPlaying') {
    // Hide all static sections
    allSections.forEach(s => { s.style.display = 'none'; });
    document.querySelectorAll('.cinema-section-header[id]').forEach(h => h.style.display = 'none');

    // Show dynamic container with hero still visible
    const dynamicSection = document.getElementById('section-dynamic');
    if (dynamicSection) dynamicSection.style.display = '';

    const container = document.getElementById('dynamic-content');
    container.innerHTML = '<div style="text-align:center;padding:3rem;color:rgba(255,255,255,0.5);"><i class="fa-solid fa-spinner fa-spin" style="font-size:2rem;"></i></div>';

    if (section === 'liveTv') {
      renderLiveTvSection(container);
    } else if (section === 'collections') {
      renderCollectionsSection(container);
    } else if (section === 'nowPlaying') {
      // Keep fragment approach for nowPlaying (mobile-only)
      try {
        const resp = await fetch('/api/video/ui/now-playing-fragment');
        if (resp.ok) {
          container.innerHTML = await resp.text();
        } else {
          container.innerHTML = '<div style="text-align:center;padding:3rem;color:rgba(255,255,255,0.5);">No media currently playing.</div>';
        }
      } catch(e) {
        container.innerHTML = '<div style="text-align:center;padding:3rem;color:rgba(255,255,255,0.5);">Error loading now playing.</div>';
      }
    }

    return;
  }

      if (section === 'home') {
    allSections.forEach(s => {
      if (s.id === 'section-movies' || s.id === 'section-tvshows' || !s.dataset.category) {
        s.style.display = '';
      } else {
        s.style.display = 'none';
      }
    });
    if (hero) hero.style.display = '';
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

async function renderLiveTvSection(container) {
  try {
    // Fetch channel groups
    const groupsResp = await fetch('/api/channels/groups');
    if (!groupsResp.ok) throw new Error('Failed to fetch groups');
    const groupsJson = await groupsResp.json();
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
        if (!resp.ok) throw new Error('Failed');
        const json = await resp.json();
        const data = json.success ? json.data : json;
        const channels = Array.isArray(data) ? data : [];
        if (channels.length === 0) {
          carousel.innerHTML = '<div style="text-align:center;padding:2rem;color:rgba(255,255,255,0.3);width:100%;">No channels</div>';
          return;
        }
        carousel.innerHTML = channels.map(ch => createChannelCard(ch)).join('');
      } catch (e) {
        carousel.innerHTML = '<div style="text-align:center;padding:2rem;color:rgba(255,255,255,0.3);width:100%;">Error loading</div>';
      }
    });

    await Promise.all(loadPromises);
    updateCarouselArrows();

  } catch (e) {
    container.innerHTML = '<div style="text-align:center;padding:3rem;color:rgba(255,255,255,0.5);">Failed to load Live TV.</div>';
  }
}

async function renderCollectionsSection(container) {
  try {
    const resp = await fetch('/api/collections');
    if (!resp.ok) throw new Error('Failed to fetch collections');
    const json = await resp.json();
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
    container.innerHTML = '<div style="text-align:center;padding:3rem;color:rgba(255,255,255,0.5);">Failed to load Collections.</div>';
  }
}

async function loadCinemaData() {
  const params = new URLSearchParams(window.location.search);
  const section = params.get('section') || 'home';
  // Fetch videos, continue-watching, and carousels in parallel
  const [allVideosResp, cwResp, seriesResp, carouselsHtml] = await Promise.all([
    fetch('/api/video/videos'),
    fetch('/api/video/continue-watching'),
    fetch('/api/series').then(r => r.ok ? r.json() : null).catch(() => null),
    fetch('/api/video/ui/optimized-carousels').then(r => r.ok ? r.text() : '').catch(() => '')
  ]);

  // Parse responses � handle both ApiResponse {success, data} and raw arrays
  const parseResponse = (json) => {
    if (json && typeof json === 'object' && json.success !== undefined && json.data !== undefined) return json.data;
    return json;
  };
  const allVideosData = allVideosResp.ok ? parseResponse(await allVideosResp.json()) : [];
  const cwData = cwResp.ok ? parseResponse(await cwResp.json()) : [];
  allVideos = Array.isArray(allVideosData) ? allVideosData : [];
  allContinueWatching = Array.isArray(cwData) ? cwData : [];
  // Parse series data into lookup map
  if (seriesResp) {
    const seriesDataParsed = parseResponse(seriesResp);
    // Paginated response: {series: [...], page, size, ...} or plain array
    const seriesArray = Array.isArray(seriesDataParsed) ? seriesDataParsed
      : (seriesDataParsed && Array.isArray(seriesDataParsed.series) ? seriesDataParsed.series : []);
    allSeries = seriesArray;
    seriesLookup.clear();
    allSeries.forEach(s => seriesLookup.set(String(s.id), s));
    // Enrich CW items with series-level description (same as allTvShows enrichment at line 1125)
    if (Array.isArray(cwData) && allSeries.length > 0) {
      const seriesByTitle = new Map();
      allSeries.forEach(s => { if (s.title) seriesByTitle.set(s.title.toLowerCase(), s); });
      cwData.forEach(item => {
        if (!item.description && item.seriesTitle) {
          const series = seriesByTitle.get(item.seriesTitle.toLowerCase());
          if (series) {
            item.description = series.description || series.overview || '';
          }
        }
      });
    }
  }

  if (allVideos.length) {
    // Hero: 3-tier fallback � continue-watching ? top-rated ? most recently added

    // Tier 1: Most recently watched from continue-watching endpoint
    if (Array.isArray(cwData) && cwData.length > 0) {
      heroItems = cwData.slice(0, 5);

    }

    // Tier 2: Highest-rated movie (with a rating > 0)
    if (heroItems.length === 0) {
      heroItems = allVideos.filter(v => v.type === 'movie' && v.description)
        .sort((a, b) => (parseFloat(b.imdbRating || b.tmdbRating) || 0) - (parseFloat(a.imdbRating || a.tmdbRating) || 0))
        .filter(v => (parseFloat(v.imdbRating || v.tmdbRating) || 0) > 0)
        .slice(0, 5);
    }

    // Tier 3: Most recently added movie
    if (heroItems.length === 0) {
      heroItems = [...allVideos]
        .sort((a, b) => (b.dateAdded || '').localeCompare(a.dateAdded || ''))
        .slice(0, 5);
    }

    if (heroItems.length > 0) {
      updateHero(0);
      startHeroRotation();
    }

    // Update hero badge based on continue watching data
    const heroBadge = document.getElementById('hero-badge');
    if (heroBadge) {
      if (Array.isArray(cwData) && cwData.length > 0) {
        heroBadge.innerHTML = '<i class="fa-solid fa-clock-rotate-left" style="color: #60a5fa;"></i> Continue Watching';
        heroBadge.style.display = '';
      } else {
        // Keep the badge but could change text or hide
        // For now, keep it visible as a fallback
      }
    }

    // Render Continue Watching carousel
    if (Array.isArray(cwData) && cwData.length > 0) {
      renderContinueWatchingCarousel(cwData);
    }

    // Render carousels
    const movies = allVideos.filter(v => v.type === 'movie');

    // TV Shows: dedup by seriesTitle � one card per series
    const seriesMap = new Map();
    allVideos.filter(v => v.type === 'episode' && v.seriesTitle).forEach(v => {
      const key = v.seriesTitle.toLowerCase().replace(/[^a-z0-9]/g, '');
      const existing = seriesMap.get(key);
      if (!existing || (v.dateAdded || '') > (existing.dateAdded || '')) {
        seriesMap.set(key, v);
      }
    });
    const tvShows = Array.from(seriesMap.values()).slice(0, 20);

    // Trending: sorted by rating
    const trending = [...allVideos].sort((a, b) => (parseFloat(b.imdbRating || b.tmdbRating) || 0) - (parseFloat(a.imdbRating || a.tmdbRating) || 0)).slice(0, 20);

    // Recently Updated: episodes sorted by dateAdded (new releases)
    const recentlyUpdated = allVideos
      .filter(v => v.type === 'episode')
      .sort((a, b) => (b.dateAdded || '').localeCompare(a.dateAdded || ''))
      .slice(0, 20);

    // Deduplicate TV episodes � keep only the latest per series
    const seenSeries = new Set();
    const dedupedUpdates = [];
    for (const item of recentlyUpdated) {
      if (item.type === 'episode') {
        const key = item.seriesTitle || item.seriesId || item.id;
        if (seenSeries.has(key)) continue;
        seenSeries.add(key);
      }
      dedupedUpdates.push(item);
    }

    // Render and show sections with data
    if (dedupedUpdates.length) {
      renderCarousel('recently-updated-carousel', dedupedUpdates);
      document.getElementById('section-recently-updated').style.display = '';
    }
    if (trending.length) {
      renderCarousel('trending-carousel', trending);
      document.getElementById('section-trending').style.display = '';
    }
    if (movies.length) {
      renderCarousel('movies-carousel', movies.slice(0, 20));
      document.getElementById('section-movies').style.display = '';
    }
    if (tvShows.length) {
      renderCarousel('tvshows-carousel', tvShows);
      document.getElementById('section-tvshows').style.display = '';
    }
    updateCarouselArrows();

    // === Dynamic genre-based carousel rows & "Recently Added" rows ===
    // Helper: create a cinema-section with carousel structure
    function _buildSection(title, carouselId, category) {
      const sec = document.createElement('section');
      sec.className = 'cinema-section';
      sec.setAttribute('data-category', category);
      sec.innerHTML = `
        <div class="cinema-section-header">
          <h2 class="cinema-section-title">${title}</h2>
        </div>
        <div class="cinema-carousel-wrapper">
          <button class="cinema-carousel-arrow cinema-carousel-arrow-left" data-carousel="${carouselId}"><i class="fa-solid fa-chevron-left"></i></button>
          <div class="cinema-carousel scrollbar-hide" id="${carouselId}"></div>
          <button class="cinema-carousel-arrow cinema-carousel-arrow-right" data-carousel="${carouselId}"><i class="fa-solid fa-chevron-right"></i></button>
        </div>`;
      return sec;
    }

    // --- Recently Added Movies ---
    const recentlyAddedMovies = [...movies]
      .sort((a, b) => (b.dateAdded || '').localeCompare(a.dateAdded || ''))
      .slice(0, 20);
    let lastMovieEl = document.getElementById('section-movies');
    if (recentlyAddedMovies.length > 0) {
      const cid = 'movies-recently-added-carousel';
      const sec = _buildSection('Recently Added Movies', cid, 'movies');
      lastMovieEl.before(sec);
      lastMovieEl = sec;
      renderCarousel(cid, recentlyAddedMovies);
    }

    // --- Movie Genre Rows (=2 movies per genre, sorted by count desc) ---
    const movieGenreMap = new Map();
    movies.forEach(v => {
      if (Array.isArray(v.genres)) {
        v.genres.forEach(g => {
          if (!movieGenreMap.has(g)) movieGenreMap.set(g, []);
          movieGenreMap.get(g).push(v);
        });
      }
    });
    Array.from(movieGenreMap.entries())
      .filter(([genre]) => genre.toLowerCase() !== 'anime')
      .filter(([, vids]) => vids.length >= 2)
      .sort((a, b) => b[1].length - a[1].length)
      .forEach(([genre, vids]) => {
        const slug = genre.toLowerCase().replace(/[^a-z0-9]+/g, '-');
        const cid = `movies-genre-${slug}-carousel`;
        const sec = _buildSection(genre, cid, 'movies');
        lastMovieEl.after(sec);
        lastMovieEl = sec;
        renderCarousel(cid, vids.slice(0, 20));
      });

    // --- Recently Added TV Shows (deduped by seriesTitle, top 20 by dateAdded) ---
    allTvShows = Array.from(seriesMap.values())
      .sort((a, b) => (b.dateAdded || '').localeCompare(a.dateAdded || ''));
    // Enrich TV show episodes with Series-level metadata (genres, description, backdrop, etc.)
    allTvShows.forEach(v => {
      const series = seriesLookup.get(String(v.series?.id));
      if (series) {
        if (!v.genres || v.genres.length === 0) v.genres = series.genres || [];
        if (!v.description) v.description = series.description || series.overview || '';
        if (!v.backdrop) v.backdrop = series.backdrop || series.backdropPath || '';
        if (!v.poster) v.poster = series.poster || series.posterPath || '';
      }
    });
    const recentlyAddedTvShows = allTvShows.slice(0, 20);
    let lastTvEl = document.getElementById('section-tvshows');
    if (recentlyAddedTvShows.length > 0) {
      const cid = 'tvshows-recently-added-carousel';
      const sec = _buildSection('Recently Added TV Shows', cid, 'shows');
      lastTvEl.before(sec);
      lastTvEl = sec;
      renderCarousel(cid, recentlyAddedTvShows);
    }

    // --- TV Show Genre Rows (=2 shows per genre, sorted by count desc) ---
    const tvShowGenreMap = new Map();
    allTvShows.forEach(v => {
      // Resolve genres from Series entity (episode Video objects don't have genres)
      let genres = v.genres;
      if (!Array.isArray(genres) || genres.length === 0) {
        const series = seriesLookup.get(String(v.series?.id));
        genres = series?.genres || [];
      }
      genres.forEach(g => {
        if (!tvShowGenreMap.has(g)) tvShowGenreMap.set(g, []);
        tvShowGenreMap.get(g).push(v);
      });
    });
    Array.from(tvShowGenreMap.entries())
      .filter(([genre]) => genre.toLowerCase() !== 'anime')
      .filter(([, vids]) => vids.length >= 2)
      .sort((a, b) => b[1].length - a[1].length)
      .forEach(([genre, vids]) => {
        const slug = genre.toLowerCase().replace(/[^a-z0-9]+/g, '-');
        const cid = `tvshows-genre-${slug}-carousel`;
        const sec = _buildSection(genre, cid, 'shows');
        lastTvEl.after(sec);
        lastTvEl = sec;
        renderCarousel(cid, vids.slice(0, 20));
      });

    // Re-initialize arrow visibility and attach scroll listeners for new carousels
    updateCarouselArrows();
    document.querySelectorAll('.cinema-carousel').forEach(c => {
      if (!c._scrollBound) {
        c.addEventListener('scroll', updateCarouselArrows);
        c._scrollBound = true;
      }
    });
  }

  // If we got HTMX carousel HTML, also try rendering it
  if (carouselsHtml && document.getElementById('movies-carousel')?.children.length === 0) {
    const container = document.querySelector('.cinema-content');
    if (container) {
      // Insert HTMX carousels as fallback
      const temp = document.createElement('div');
      temp.innerHTML = carouselsHtml;
      // Don't replace � we already have cinema-styled carousels
    }
  }

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

// ============================================================
// Modals
// ============================================================
async function openSeriesFromEpisode(episodeId) {
  // Fetch the episode to get its seriesTitle
  const ep = await fetchJSON(`/api/video/${episodeId}`);
  if (!ep || !ep.seriesTitle) { openDetails(episodeId); return; }
  openSeriesByTitle(ep.seriesTitle);
}

async function openSeriesByTitle(seriesTitle) {
  // Fetch series info from the shows endpoint
  const resp = await fetch(`/api/video/ui/shows/${encodeURIComponent(seriesTitle)}/seasons-fragment`);
  if (!resp.ok) { return; }
  const html = await resp.text();

  // Show in the existing modal
  const modal = document.getElementById('cinema-modal');
  const scroll = modal.querySelector('.cinema-modal-scroll');
  
  const bgBlur = document.getElementById('modal-bg-blur');
  if (bgBlur) bgBlur.style.backgroundImage = '';
  const bgBlurInner = document.getElementById('modal-bg-blur-inner');
  if (bgBlurInner) bgBlurInner.style.backgroundImage = '';
  const backdropImg = document.getElementById('modal-backdrop-img');
  if (backdropImg) backdropImg.src = '/logo.png';

  // Title is now handled via logo image; no text title element
  document.getElementById('cinema-modal').dataset.seriesTitle = seriesTitle;

  // Clear meta row, genres, and description
  const metaRow = document.getElementById('modal-meta-row');
  if (metaRow) metaRow.innerHTML = '<span class="meta-badge">TV Show</span>';
  const genresEl = document.getElementById('modal-genres');
  if (genresEl) genresEl.innerHTML = '';
  const desc = document.getElementById('modal-desc');
  if (desc) desc.innerHTML = '';

  // Replace modal content with seasons HTML
  const content = modal.querySelector('.cinema-modal-content');
  if (content) {
    // Keep the close button, replace the rest
    const existingActions = content.querySelector('.cinema-modal-actions');
    content.innerHTML = '';
    content.appendChild(metaRow);
    content.insertAdjacentHTML('beforeend', '<div class="cinema-series-content" style="padding: 0.5rem 0;">' + html + '</div>');
    if (existingActions) content.appendChild(existingActions);
  }

  // Hide play/watchlist for series view
  const playBtn = document.getElementById('modal-play-btn');
  const watchBtn = document.getElementById('modal-watchlist-btn');
  if (playBtn) playBtn.style.display = 'none';
  if (watchBtn) watchBtn.style.display = 'none';

  modal.classList.add('active');
  document.body.style.overflow = 'hidden';
}

async function openSeriesDetail(seriesTitle) {
  if (!seriesTitle) return;
  const gen = ++_modalGeneration;
  const modal = document.getElementById('cinema-modal');
  if (modal._backdropRetry) { clearTimeout(modal._backdropRetry); modal._backdropRetry = null; }
  if (modal._logoRetry) { clearTimeout(modal._logoRetry); modal._logoRetry = null; }
  // Reset modal fields from previous series to prevent stale data
  const resetDesc = document.getElementById('modal-desc');
  if (resetDesc) { resetDesc.innerHTML = ''; resetDesc.classList.add('is-skeleton'); }
  const resetGenres = document.getElementById('modal-genres');
  if (resetGenres) { resetGenres.innerHTML = ''; resetGenres.classList.add('is-skeleton'); }
  const resetCreator = document.getElementById('modal-creator');
  if (resetCreator) { resetCreator.textContent = ''; resetCreator.classList.add('is-skeleton'); }
  const resetDescToggle = document.getElementById('modal-desc-toggle');
  if (resetDescToggle) resetDescToggle.style.display = 'none';
  // Reset gradient overlay
  // gradient overlay removed; nothing to clear
  // Filter allVideos for this series (only actual episodes)
  const episodes = allVideos
    .filter(v => v.type === 'episode' && v.seriesTitle === seriesTitle)
    .sort((a, b) => {
      const sa = a.seasonNumber ?? 1, sb = b.seasonNumber ?? 1;
      const ea = a.episodeNumber || 1, eb = b.episodeNumber || 1;
      return sa - sb || ea - eb;
    });
  if (!episodes.length) return;

  // Group episodes by (seasonNumber, contentType) pair.
  // Each distinct pair becomes a separate dropdown tab.
  const groups = {};
  episodes.forEach(ep => {
    const sn = ep.seasonNumber != null ? ep.seasonNumber : 1;
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
    const na = parseInt(sa), nb = parseInt(sb);
    if (na !== nb) return na - nb;
    return (contentTypePriority[ca] ?? 99) - (contentTypePriority[cb] ?? 99);
  });
  const firstEp = episodes[0];

  // Open modal immediately with skeleton state � don't wait for network
  modal.dataset.originalParent = modal.parentElement.id || 'app-content';
  document.body.appendChild(modal);
  document.getElementById('cinema-modal-backdrop').classList.add('active');
  modal.classList.add('active');
  trapFocus(modal);
  document.body.style.overflow = 'hidden';
  initModalScrollBlur();

  // Show text title immediately, hide logo until it loads
  document.getElementById('cinema-modal').dataset.seriesTitle = seriesTitle;
  const textTitleEl = document.getElementById('modal-text-title');
  const logoHeroImg = document.getElementById('modal-logo-hero-img');
  if (textTitleEl) { textTitleEl.textContent = seriesTitle; textTitleEl.style.display = ''; }
  if (logoHeroImg) { logoHeroImg.style.display = 'none'; logoHeroImg.classList.add('is-skeleton'); }

  // Show play/watchlist buttons immediately
  const playBtn = document.getElementById('modal-play-btn');
  if (playBtn) { playBtn.style.display = ''; playBtn.onclick = () => playVideo(firstEp); }
  const watchBtn = document.getElementById('modal-watchlist-btn');
  if (watchBtn) { watchBtn.style.display = ''; watchBtn.onclick = () => toggleWatchlist(firstEp.id); }

  // Fetch Series entity � try by ID first, fall back to title lookup
  let seriesData = null;
  const seriesId = firstEp.series?.id;
  if (seriesId) {
    seriesData = seriesLookup.get(String(seriesId)) || null;
    if (!seriesData) {
      seriesData = await fetchJSON(`/api/series/${seriesId}`);
      if (seriesData) seriesLookup.set(String(seriesId), seriesData);
    }
    if (gen !== _modalGeneration) return;
  }
  // Fallback: match by seriesTitle from cache (when lazy proxy is null)
  if (!seriesData && seriesTitle) {
    const normalizedTitle = seriesTitle.toLowerCase();
    for (const [key, cached] of seriesLookup) {
      if (cached.title && cached.title.toLowerCase() === normalizedTitle) {
        seriesData = cached;
        break;
      }
    }
  }
  // Cache may have stale un-enriched data � fetch fresh from API (triggers enrichment)
  if (seriesData && seriesData.id) {
    try {
      const fresh = await fetchJSON(`/api/series/${seriesData.id}`);
      if (fresh && (fresh.genres?.length || fresh.overview || fresh.networks?.length)) {
        seriesData = fresh;
        seriesLookup.set(String(seriesData.id), fresh);
      }
    } catch (e) { /* keep cached version on transient error */ }
  }
  if (gen !== _modalGeneration) return;
  const imgSource = seriesData || firstEp;

  // Populate modal � Series images take priority
  const heroUrl = seriesData
    ? getSeriesImageUrl(seriesData.id, 'backdrop')
    : getBackdropUrl(firstEp);
  const episodeBackdropUrl = getBackdropUrl(firstEp);
  const backdropImg = document.getElementById('modal-backdrop-img');
  if (backdropImg) {
    backdropImg.src = heroUrl;
    backdropImg.classList.remove('is-skeleton');
    // Retry: series images download on first request (404), should exist shortly after
    if (seriesData) {
      let backdropRetries = 0;
      backdropImg.onerror = function() {
        if (backdropRetries < 3) {
          backdropRetries++;
          modal._backdropRetry = setTimeout(() => {
            const retryUrl = getSeriesImageUrl(seriesData.id, 'backdrop') + '?r=' + Date.now();
            backdropImg.src = retryUrl;
            if (bgBlur) bgBlur.style.backgroundImage = `url(${retryUrl})`;
            if (bgBlurInner) bgBlurInner.style.backgroundImage = `url(${retryUrl})`;
          }, 2000 * backdropRetries);
        } else {
          backdropImg.onerror = null;
          backdropImg.src = episodeBackdropUrl;
          if (bgBlur) bgBlur.style.backgroundImage = `url(${episodeBackdropUrl})`;
          if (bgBlurInner) bgBlurInner.style.backgroundImage = `url(${episodeBackdropUrl})`;
        }
      };
    }
  }
  const bgBlur = document.getElementById('modal-bg-blur');
  if (bgBlur) bgBlur.style.backgroundImage = `url(${heroUrl})`;
  const bgBlurInner = document.getElementById('modal-bg-blur-inner');
  if (bgBlurInner) bgBlurInner.style.backgroundImage = `url(${heroUrl})`;

  // Dynamic hero overlay color from backdrop
  const heroOverlay = document.querySelector('.cinema-modal-hero-overlay');
  extractAndApplyModalColor(heroUrl, heroOverlay);

  // Modal logo � Series logo first, video logo fallback, text title fallback
  // logoHeroImg and textTitleEl already declared above in the early-open block
  if (logoHeroImg) {
    // Always try to load a logo � backend handles missing files gracefully
    const logoUrl = seriesData
      ? getSeriesImageUrl(seriesData.id, 'logo')
      : getLogoUrl(firstEp.id);
    logoHeroImg.src = logoUrl;
    logoHeroImg.style.maxWidth = '288px';
    logoHeroImg.style.maxHeight = '80px';
    logoHeroImg.style.display = 'block';
    logoHeroImg.classList.remove('is-skeleton');
    if (textTitleEl) textTitleEl.style.display = 'none';
    // Retry: series logo downloads on first request, retry before giving up
    if (seriesData) {
      let logoRetries = 0;
      logoHeroImg.onerror = function() {
        if (logoRetries < 3) {
          logoRetries++;
          modal._logoRetry = setTimeout(() => {
            logoHeroImg.src = getSeriesImageUrl(seriesData.id, 'logo') + '?r=' + Date.now();
          }, 2000 * logoRetries);
        } else {
          logoHeroImg.onerror = null;
          logoHeroImg.style.display = 'none';
          if (textTitleEl) { textTitleEl.textContent = seriesTitle; textTitleEl.style.display = ''; }
        }
      };
    } else {
      logoHeroImg.onerror = function() {
        logoHeroImg.style.display = 'none';
        if (textTitleEl) { textTitleEl.textContent = seriesTitle; textTitleEl.style.display = ''; }
      };
    }
    // Defense-in-depth: if server returns generic /logo.png as a valid image, override to text
    logoHeroImg.onload = function() {
      if (logoHeroImg.src.includes('/logo.png')) {
        logoHeroImg.style.display = 'none';
        if (textTitleEl) { textTitleEl.textContent = seriesTitle; textTitleEl.style.display = ''; }
      }
    };
  }

  // Title logo handled separately via hero logo img
  modal.dataset.seriesTitle = seriesTitle;

  // Meta badges � from Series entity
  const rating = imgSource.imdbRating || imgSource.tmdbRating || '';
    const matchPct = rating ? Math.round(parseFloat(rating) * 10) : null;
  const year = imgSource.releaseYear || '';
  const seasonCount = seasonKeys.length;
  const metaRow = document.getElementById('modal-meta-row');
  if (metaRow) {
    metaRow.innerHTML = '';
    if (matchPct !== null) metaRow.innerHTML += `<span style="color:#46d369;font-weight:600;">${matchPct}% Match</span>`;
    if (year) metaRow.innerHTML += `<span class="meta-badge">${year}</span>`;
    metaRow.innerHTML += `<span class="meta-badge">${seasonCount} Season${seasonCount > 1 ? 's' : ''}</span>`;
    metaRow.innerHTML += `<span class="meta-badge" style="border:1px solid rgba(255,255,255,0.4);padding:0.15rem 0.5rem;border-radius:0.25rem;">HD</span>`;
  }

  // Genres � from Series entity directly
  const genresEl = document.getElementById('modal-genres');
  if (genresEl && imgSource.genres?.length) {
    genresEl.innerHTML = imgSource.genres.map(g => `<span class="cinema-genre-chip">${g}</span>`).join('');
    genresEl.classList.remove('is-skeleton');
  }

  // Creator � from Series entity directly
  const creatorEl = document.getElementById('modal-creator');
  if (creatorEl) {
    const creatorNames = (imgSource.networks || []).join(', ');
    creatorEl.textContent = creatorNames;
    if (creatorNames) creatorEl.classList.remove('is-skeleton');
  }

  // Description � from Series entity directly
  const descEl = document.getElementById('modal-desc');
  const desc = imgSource.description || imgSource.overview || '';
  if (descEl) {
    descEl.innerHTML = desc ? `<p>${desc}</p>` : '';
    if (desc) descEl.classList.remove('is-skeleton');
  }

  // Build episodes HTML
  let episodesHtml = '<div class="cinema-episodes-section" style="margin-top: 1.5rem;">';
  
  // Season tabs if multiple seasons
  if (seasonKeys.length > 1) {
    const firstKey = seasonKeys[0];
    const firstLabel = getGroupLabel(firstKey);
    episodesHtml += `<div class="cinema-season-row"><span style="font-size:0.875rem;font-weight:600;color:white;">Episodes</span><div class="cinema-season-dropdown">
      <button class="cinema-season-dropdown-btn" onclick="toggleSeasonDropdown()">
        <span id="cinema-season-label">${firstLabel}</span>
        <i class="fa-solid fa-chevron-down"></i>
      </button>
      <div class="cinema-season-dropdown-list" id="cinema-season-list">`;
    seasonKeys.forEach((key) => {
        const isActive = key === firstKey;
        const label = getGroupLabel(key);
        episodesHtml += `<button class="cinema-season-dropdown-item${isActive ? ' active' : ''}" data-sk="${key}" onclick="switchSeasonFromSk(this)">${label}</button>`;
    });
    episodesHtml += '</div></div></div>';
  }

  // Episode cards for first season
  const firstSeasonEps = groups[seasonKeys[0]];
  episodesHtml += buildEpisodesList(firstSeasonEps);
  episodesHtml += '</div>';

  // Inject into modal content
  const content = document.querySelector('#cinema-modal .cinema-modal-content');
  if (content) {
    // Remove old episodes section if any
    const old = content.querySelector('.cinema-episodes-section, .cinema-series-content');
    if (old) old.remove();
    content.insertAdjacentHTML('beforeend', episodesHtml);

    const oldMoreLike = content.querySelector('.cinema-more-like-section');
    if (oldMoreLike) oldMoreLike.remove();
    const moreLikeHtml = buildMoreLikeThis(firstEp, imgSource?.genres);
    if (moreLikeHtml) content.insertAdjacentHTML('beforeend', moreLikeHtml);
  }

  // Auto-refresh: if key fields are empty, poll for updated series data
  const needsRefresh = !seriesData || !(seriesData.description || seriesData.overview) || !(seriesData.genres?.length) || !(seriesData.networks?.length);
  if (needsRefresh && seriesData && seriesData.id) {
    // Clear any previous polling
    if (modal._seriesPollInterval) clearInterval(modal._seriesPollInterval);
    let attempts = 0;
    modal._seriesPollInterval = setInterval(async () => {
      attempts++;
      if (attempts > 7) { clearInterval(modal._seriesPollInterval); return; } // ~14s max
      try {
        const updated = await fetchJSON(`/api/series/${seriesData.id}`);
        if (!updated) return;
        // Update seriesLookup cache
        seriesLookup.set(String(seriesData.id), updated);

        // Update description
        const descText = updated.description || updated.overview || '';
        const descEl = document.getElementById('modal-desc');
        if (descEl && descText) {
          descEl.innerHTML = `<p>${descText}</p>`;
          descEl.classList.remove('is-skeleton');
        }

        // Update genres
        const genresEl = document.getElementById('modal-genres');
        if (genresEl && updated.genres?.length) {
          genresEl.innerHTML = updated.genres.map(g => `<span class="cinema-genre-chip">${g}</span>`).join('');
          genresEl.classList.remove('is-skeleton');
        }

        // Update creator/networks
        const creatorEl = document.getElementById('modal-creator');
        if (creatorEl && updated.networks?.length) {
          creatorEl.textContent = updated.networks.join(', ');
          creatorEl.classList.remove('is-skeleton');
        }

        // Update meta badges if now available
        if (updated.imdbRating || updated.tmdbRating) {
          const rating = updated.imdbRating || updated.tmdbRating;
          const matchPct = Math.round(parseFloat(rating) * 10);
          const metaRow = document.getElementById('modal-meta-row');
          if (metaRow) {
            const existingHtml = metaRow.innerHTML;
            if (!existingHtml.includes('% Match')) {
              metaRow.innerHTML = `<span style="color:#46d369;font-weight:600;">${matchPct}% Match</span>` + existingHtml;
            }
          }
        }

        // Stop polling if all fields populated
        const allFilled = descText && updated.genres?.length && updated.networks?.length;
        if (allFilled) clearInterval(modal._seriesPollInterval);
      } catch (e) { /* ignore transient errors */ }
    }, 2000);
  }
}

function renderContentTypeBadge(contentType) {
  if (!contentType || contentType === 'episode') return '';
  const badgeColors = {
    movie: { bg: 'rgba(34,197,94,0.2)', color: '#22c55e', label: 'Movie' },
    special: { bg: 'rgba(249,115,22,0.2)', color: '#f97316', label: 'Special' },
    featurette: { bg: 'rgba(168,85,247,0.2)', color: '#a855f7', label: 'Featurette' },
    extra: { bg: 'rgba(239,68,68,0.2)', color: '#ef4444', label: 'Extra' }
  };
  const badge = badgeColors[contentType];
  if (!badge) return '';
  return `<span style="display:inline-block;font-size:0.65rem;font-weight:600;padding:0.1rem 0.4rem;border-radius:0.2rem;background:${badge.bg};color:${badge.color};text-transform:uppercase;letter-spacing:0.03em;vertical-align:middle;">${badge.label}</span> `;
}

function buildEpisodesList(episodeArray) {
  return episodeArray.map((ep, i) => {
    const epTitle = ep.episodeTitle || ep.title || `Episode ${(ep.episodeNumber || i + 1)}`;
    const epNum = ep.episodeNumber || (i + 1);
    const dur = formatDuration(ep.duration);
    const desc = ep.description || '';
    const img = getThumbnailUrl(ep.id);
    return `<div class="cinema-episode-card" onclick="playVideo(${JSON.stringify(ep).replace(/"/g, '&quot;')})">
      <div class="cinema-episode-thumb-wrap">
        <img src="${img}" alt="${epTitle}" loading="lazy">
        <div class="cinema-episode-play-overlay">
          <div class="cinema-episode-play-icon">
            <i class="fa-solid fa-play"></i>
          </div>
        </div>
      </div>
      <div class="cinema-episode-info">
        <div class="cinema-episode-header">
          ${renderContentTypeBadge(ep.contentType)}
          <h3 class="cinema-episode-title">${epNum}. ${epTitle}</h3>
          ${dur ? `<span class="cinema-episode-duration">${dur}</span>` : ''}
        </div>
        ${desc ? `<p class="cinema-episode-desc">${desc}</p>` : '<div class="cinema-episode-desc is-skeleton"></div>'}
      </div>
    </div>`;
  }).join('');
}

function buildMoreLikeThis(currentVideo, overrideGenres) {
  if (!currentVideo) return '';
  const videoGenres = overrideGenres || currentVideo.genres || [];

  // Non-episode (movies): keep existing behavior — genre-matched same-type videos
  if (currentVideo.type !== 'episode') {
    const matches = allVideos.filter(v =>
      v.id !== currentVideo.id &&
      v.type === currentVideo.type &&
      v.genres?.some(g => videoGenres.includes(g))
    ).slice(0, 12);
    if (!matches.length) {
      return `<div class="cinema-more-like-section">
      <div style="display:flex;align-items:center;gap:0.5rem;margin-bottom:0.75rem;">
        <div style="width:0.25rem;height:1.5rem;background:rgba(255,255,255,0.15);border-radius:9999px;"></div>
        <h3 style="font-size:1rem;font-weight:700;">More Like This</h3>
      </div>
      <div style="position:relative;">
        <div class="cinema-more-like-scroll">
          ${Array(6).fill('').map(() => `<div class="cinema-more-like-card">
            <div class="cinema-more-like-img-wrap is-skeleton"></div>
            <div class="cinema-card-title is-skeleton">&nbsp;</div>
            <div class="cinema-card-meta-row is-skeleton">&nbsp;</div>
          </div>`).join('')}
        </div>
      </div>
    </div>`;
    }
    return `<div class="cinema-more-like-section">
    <div style="display:flex;align-items:center;gap:0.5rem;margin-bottom:0.75rem;">
      <div style="width:0.25rem;height:1.5rem;background:var(--cinema-accent,#48c774);border-radius:9999px;"></div>
      <h3 style="font-size:1rem;font-weight:700;">More Like This</h3>
    </div>
    <div style="position:relative;">
      <button class="cinema-more-like-arrow cinema-more-like-arrow-left" onclick="scrollMoreLike(this,-1)" style="display:none;">
        <i class="fa-solid fa-chevron-left"></i>
      </button>
      <div class="cinema-more-like-scroll">
        ${matches.map(v => {
          const title = v.title || v.seriesTitle || 'Untitled';
          const year = v.releaseYear || '';
          const img = getThumbnailUrl(v.id);
          const rating = v.imdbRating || v.tmdbRating || '';
          const matchPct = rating ? Math.round(parseFloat(rating) * 10) : '';
          const clickAction = `closeModal(); openDetails(${v.id})`;
          return `<div class="cinema-more-like-card" onclick="${clickAction}">
            <div class="cinema-more-like-img-wrap">
              <img src="${img}" alt="${title}" loading="lazy" onerror="this.src='/logo.png'">
              <div class="cinema-more-like-play-overlay">
                <div class="cinema-more-like-play-icon">
                  <i class="fa-solid fa-play"></i>
                </div>
              </div>
            </div>
            <div class="cinema-card-title">${title}</div>
            <div class="cinema-card-meta-row">
              ${matchPct ? `<span style="color:#46d369;font-size:0.7rem;font-weight:600;"><i class="fa-solid fa-star" style="font-size:0.6rem;"></i> ${matchPct}%</span>` : ''}
              ${year ? `<span class="cinema-card-year">${year}</span>` : ''}
            </div>
          </div>`;
        }).join('')}
      </div>
      <button class="cinema-more-like-arrow cinema-more-like-arrow-right" onclick="scrollMoreLike(this,1)">
        <i class="fa-solid fa-chevron-right"></i>
      </button>
    </div>
  </div>`;
  }

  // --- Episode type below ---

  // No genres → empty
  if (!videoGenres.length) {
    return `<div class="cinema-more-like-section">
      <div style="display:flex;align-items:center;gap:0.5rem;margin-bottom:0.75rem;">
        <div style="width:0.25rem;height:1.5rem;background:rgba(255,255,255,0.15);border-radius:9999px;"></div>
        <h3 style="font-size:1rem;font-weight:700;">More Like This</h3>
      </div>
      <div style="position:relative;">
        <div class="cinema-more-like-scroll">
          ${Array(6).fill('').map(() => `<div class="cinema-more-like-card">
            <div class="cinema-more-like-img-wrap is-skeleton"></div>
            <div class="cinema-card-title is-skeleton">&nbsp;</div>
            <div class="cinema-card-meta-row is-skeleton">&nbsp;</div>
          </div>`).join('')}
        </div>
      </div>
    </div>`;
  }

  // B. Franchise/collection movies
  const currentSeries = currentVideo.series?.id ? seriesLookup.get(String(currentVideo.series.id)) : null;
  const franchiseName = currentSeries?.franchiseName || currentVideo.franchiseName;
  const collectionName = currentSeries?.collectionName || currentVideo.collectionName;
  const franchiseMovies = [];
  if (franchiseName || collectionName) {
    allVideos.forEach(v => {
      if (v.id === currentVideo.id || v.type === 'episode') return;
      if ((franchiseName && v.franchiseName === franchiseName) ||
          (collectionName && v.collectionName === collectionName)) {
        franchiseMovies.push(v);
      }
    });
  }

  // A. Genre-matched series from seriesLookup
  const seenSeries = new Map();
  allVideos.filter(v => v.type === 'episode' && v.series?.id)
    .forEach(v => {
      const sId = String(v.series.id);
      if (sId === String(currentVideo.series?.id) || seenSeries.has(sId)) return;
      const series = seriesLookup.get(sId);
      if (series && series.genres?.some(g => videoGenres.includes(g))) {
        seenSeries.set(sId, series);
      }
    });
  const genreSeries = Array.from(seenSeries.values()).slice(0, 8);

  // E. Empty state
  if (!franchiseMovies.length && !genreSeries.length) {
    return `<div class="cinema-more-like-section">
      <div style="display:flex;align-items:center;gap:0.5rem;margin-bottom:0.75rem;">
        <div style="width:0.25rem;height:1.5rem;background:rgba(255,255,255,0.15);border-radius:9999px;"></div>
        <h3 style="font-size:1rem;font-weight:700;">More Like This</h3>
      </div>
      <div style="position:relative;">
        <div class="cinema-more-like-scroll">
          ${Array(6).fill('').map(() => `<div class="cinema-more-like-card">
            <div class="cinema-more-like-img-wrap is-skeleton"></div>
            <div class="cinema-card-title is-skeleton">&nbsp;</div>
            <div class="cinema-card-meta-row is-skeleton">&nbsp;</div>
          </div>`).join('')}
        </div>
      </div>
    </div>`;
  }

  // C. Combined rendering — franchise movies first, then genre-matched series
  const allCards = [];

  // Franchise movies (cap 4)
  franchiseMovies.slice(0, 4).forEach(v => {
    const title = v.title || 'Untitled';
    const year = v.releaseYear || '';
    const img = getThumbnailUrl(v.id);
    const rating = v.imdbRating || v.tmdbRating || '';
    const matchPct = rating ? Math.round(parseFloat(rating) * 10) : '';
    allCards.push(`<div class="cinema-more-like-card" onclick="closeModal(); openDetails(${v.id})">
      <div class="cinema-more-like-img-wrap">
        <img src="${img}" alt="${title}" loading="lazy" onerror="this.src='/logo.png'">
        <div class="cinema-more-like-play-overlay">
          <div class="cinema-more-like-play-icon">
            <i class="fa-solid fa-play"></i>
          </div>
        </div>
      </div>
      <div class="cinema-card-title">${title}</div>
      <div class="cinema-card-meta-row">
        ${matchPct ? `<span style="color:#46d369;font-size:0.7rem;font-weight:600;"><i class="fa-solid fa-star" style="font-size:0.6rem;"></i> ${matchPct}%</span>` : ''}
        ${year ? `<span class="cinema-card-year">${year}</span>` : ''}
      </div>
    </div>`);
  });

  // Genre-matched series cards
  genreSeries.forEach(series => {
    const title = series.title || 'Untitled';
    const year = series.releaseYear || '';
    const repEpisode = allVideos.find(v => v.type === 'episode' && v.seriesTitle === series.title);
    const img = repEpisode ? getThumbnailUrl(repEpisode.id) : '/logo.png';
    const rating = series.imdbRating || series.tmdbRating || '';
    const matchPct = rating ? Math.round(parseFloat(rating) * 10) : '';
    allCards.push(`<div class="cinema-more-like-card" data-click="openSeriesDetailFromCard" data-series-title="${encodeURIComponent(series.title || '')}">
      <div class="cinema-more-like-img-wrap">
        <img src="${img}" alt="${title}" loading="lazy" onerror="this.src='/logo.png'">
        <div class="cinema-more-like-play-overlay">
          <div class="cinema-more-like-play-icon">
            <i class="fa-solid fa-play"></i>
          </div>
        </div>
      </div>
      <div class="cinema-card-title">${title}</div>
      <div class="cinema-card-meta-row">
        ${matchPct ? `<span style="color:#46d369;font-size:0.7rem;font-weight:600;"><i class="fa-solid fa-star" style="font-size:0.6rem;"></i> ${matchPct}%</span>` : ''}
        ${year ? `<span class="cinema-card-year">${year}</span>` : ''}
      </div>
    </div>`);
  });

  const displayCards = allCards.slice(0, 12);

  return `<div class="cinema-more-like-section">
    <div style="display:flex;align-items:center;gap:0.5rem;margin-bottom:0.75rem;">
      <div style="width:0.25rem;height:1.5rem;background:var(--cinema-accent,#48c774);border-radius:9999px;"></div>
      <h3 style="font-size:1rem;font-weight:700;">More Like This</h3>
    </div>
    <div style="position:relative;">
      <button class="cinema-more-like-arrow cinema-more-like-arrow-left" onclick="scrollMoreLike(this,-1)" style="display:none;">
        <i class="fa-solid fa-chevron-left"></i>
      </button>
      <div class="cinema-more-like-scroll">
        ${displayCards.join('')}
      </div>
      <button class="cinema-more-like-arrow cinema-more-like-arrow-right" onclick="scrollMoreLike(this,1)">
        <i class="fa-solid fa-chevron-right"></i>
      </button>
    </div>
  </div>`;
}

function scrollMoreLike(btn, dir) {
  const container = btn.closest('.cinema-more-like-section').querySelector('.cinema-more-like-scroll');
  if (!container) return;
  const scrollAmount = container.offsetWidth * 0.7;
  container.scrollBy({ left: dir * scrollAmount, behavior: 'smooth' });
  setTimeout(() => {
    const leftArrow = btn.closest('.cinema-more-like-section').querySelector('.cinema-more-like-arrow-left');
    const rightArrow = btn.closest('.cinema-more-like-section').querySelector('.cinema-more-like-arrow-right');
    if (leftArrow) leftArrow.style.display = container.scrollLeft > 10 ? '' : 'none';
    if (rightArrow) rightArrow.style.display = container.scrollLeft + container.offsetWidth < container.scrollWidth - 10 ? '' : 'none';
  }, 350);
}

function toggleSeasonDropdown() {
  const list = document.getElementById('cinema-season-list');
  if (list) list.classList.toggle('show');
}

document.addEventListener('click', function(e) {
  const dropdown = document.querySelector('.cinema-season-dropdown');
  if (dropdown && !dropdown.contains(e.target)) {
    const list = document.getElementById('cinema-season-list');
    if (list) list.classList.remove('show');
  }
});

function getGroupLabel(key) {
  const [snStr, ct] = key.split(':');
  const sn = parseInt(snStr);
  if (ct === 'episode' || !ct) {
    if (sn === 0) return 'Specials';
    return 'Season ' + sn;
  }
  const label = ct.charAt(0).toUpperCase() + ct.slice(1);
  if (sn === 0) return label + 's';
  return label + ' Season ' + sn;
}

function switchSeasonFromSk(btn) {
  switchSeason(btn.dataset.sk);
}

function switchSeason(seasonKey) {
  // Close dropdown
  const list = document.getElementById('cinema-season-list');
  if (list) list.classList.remove('show');

  // Update label
  const label = document.getElementById('cinema-season-label');
  if (label) {
    label.textContent = getGroupLabel(seasonKey);
  }

  // Update active state on dropdown items
  document.querySelectorAll('.cinema-season-dropdown-item').forEach(item => {
    item.classList.toggle('active', item.dataset.sk === seasonKey);
  });

  // Find the episodes for this season key
  const seriesTitle = document.getElementById('cinema-modal')?.dataset.seriesTitle || '';
  const [snStr, ct] = seasonKey.split(':');
  const sn = parseInt(snStr);
  
  let episodes = allVideos
    .filter(v => v.type === 'episode' && v.seriesTitle === seriesTitle
      && (v.seasonNumber != null ? v.seasonNumber : 1) === sn
      && (v.contentType || 'episode') === (ct || 'episode'))
    .sort((a, b) => (a.episodeNumber || 0) - (b.episodeNumber || 0));
  
  // Update episode list
  const section = document.querySelector('#cinema-modal .cinema-episodes-section');
  if (!section) return;
  
  // Rebuild episode list (keep the season dropdown)
  const existingList = section.querySelectorAll('.cinema-episode-card');
  existingList.forEach(el => el.remove());
  const seasonRow = section.querySelector('.cinema-season-row');
  if (seasonRow) {
    seasonRow.insertAdjacentHTML('afterend', buildEpisodesList(episodes));
  } else {
    section.insertAdjacentHTML('afterbegin', buildEpisodesList(episodes));
  }
}

function openDetails(videoId) {
  const gen = ++_modalGeneration;

  // Reset modal fields from previous open
  const modal = document.getElementById('cinema-modal');
  delete modal.dataset.seriesTitle;

  const descEl = document.getElementById('modal-desc');
  if (descEl) { descEl.innerHTML = ''; descEl.classList.add('is-skeleton'); }
  const genresEl = document.getElementById('modal-genres');
  if (genresEl) { genresEl.innerHTML = ''; genresEl.classList.add('is-skeleton'); }
  const creatorEl = document.getElementById('modal-creator');
  if (creatorEl) { creatorEl.textContent = ''; creatorEl.classList.add('is-skeleton'); }
  const metaRow = document.getElementById('modal-meta-row');
  if (metaRow) metaRow.innerHTML = '';
  const descToggle = document.getElementById('modal-desc-toggle');
  if (descToggle) descToggle.style.display = 'none';
  // Remove leftover episode/series sections from a previously opened TV show
  const content = modal.querySelector('.cinema-modal-content');
  const oldEpisodes = content?.querySelector('.cinema-episodes-section, .cinema-series-content');
  if (oldEpisodes) oldEpisodes.remove();
  const oldMoreLike = content?.querySelector('.cinema-more-like-section');
  if (oldMoreLike) oldMoreLike.remove();

  // Reset gradient overlay
  // gradient overlay removed; nothing to clear

  // Open modal immediately with skeleton state
  modal.dataset.originalParent = modal.parentElement.id || 'app-content';
  document.body.appendChild(modal);
  document.getElementById('cinema-modal-backdrop').classList.add('active');
  modal.classList.add('active');
  trapFocus(modal);
  document.body.style.overflow = 'hidden';
  initModalScrollBlur();

  fetchJSON(`/api/video/${videoId}`).then(data => {
    if (!data) return;
    if (gen !== _modalGeneration) return;

    // Hero image � prefer wider images
    const heroUrl = getBackdropUrl(data);
    const backdropImg = document.getElementById('modal-backdrop-img');
    if (backdropImg) {
      backdropImg.src = heroUrl;
      backdropImg.classList.remove('is-skeleton');
      let retries = 0;
      backdropImg.onerror = function() {
        if (retries < 3) {
          retries++;
          modal._backdropRetry = setTimeout(() => {
            const retryUrl = getBackdropUrl(data) + '?r=' + Date.now();
            backdropImg.src = retryUrl;
            if (bgBlur) bgBlur.style.backgroundImage = `url(${retryUrl})`;
            if (bgBlurInner) bgBlurInner.style.backgroundImage = `url(${retryUrl})`;
          }, 2000 * retries);
        } else {
          backdropImg.onerror = null;
        }
      };
    }

    // Dynamic hero overlay color from backdrop
    const heroOverlay = document.querySelector('.cinema-modal-hero-overlay');
    extractAndApplyModalColor(heroUrl, heroOverlay);

    // Blurred background
    const bgBlur = document.getElementById('modal-bg-blur');
    if (bgBlur) bgBlur.style.backgroundImage = `url(${heroUrl})`;
    const bgBlurInner = document.getElementById('modal-bg-blur-inner');
    if (bgBlurInner) bgBlurInner.style.backgroundImage = `url(${heroUrl})`;

    const logoHeroImg = document.getElementById('modal-logo-hero-img');
    const textTitleEl = document.getElementById('modal-text-title');
    if (logoHeroImg) {
      logoHeroImg.src = getLogoUrl(data.id);
      logoHeroImg.style.display = 'block';
      logoHeroImg.classList.remove('is-skeleton');
      if (textTitleEl) textTitleEl.style.display = 'none';
      logoHeroImg.onerror = function() {
        logoHeroImg.style.display = 'none';
        if (textTitleEl) { textTitleEl.textContent = data.title || ''; textTitleEl.style.display = ''; }
      };
      // Defense-in-depth: if server returns generic /logo.png as a valid image, override to text
      logoHeroImg.onload = function() {
        if (logoHeroImg.src.includes('/logo.png')) {
          logoHeroImg.style.display = 'none';
          if (textTitleEl) { textTitleEl.textContent = data.title || ''; textTitleEl.style.display = ''; }
        }
      };
    }

    // Title logo handled via hero logo img

    // Meta badges
    const rating = data.imdbRating || data.tmdbRating || '';
    const year = data.releaseYear || '';
    const duration = formatDuration(data.duration);
    const type = data.type === 'movie' ? 'Movie' : 'TV Show';

    let metaHtml = '';
  const matchPct = rating ? Math.round(parseFloat(rating) * 10) : null;

    if (matchPct !== null) metaHtml += `<span style="color:#46d369;font-weight:600;">${matchPct}% Match</span>`;
    if (year) metaHtml += `<span class="meta-badge">${year}</span>`;
    if (duration) metaHtml += `<span class="meta-badge">${duration}</span>`;
    metaHtml += `<span class="meta-badge" style="border:1px solid rgba(255,255,255,0.4);padding:0.15rem 0.5rem;border-radius:0.25rem;">HD</span>`;

    const metaRow = document.getElementById('modal-meta-row');
    if (metaRow) metaRow.innerHTML = metaHtml;

    // Genres
    const genresEl = document.getElementById('modal-genres');
    if (genresEl) {
      genresEl.innerHTML = data.genres?.map(g => `<span class="cinema-genre-chip">${g}</span>`).join('') || '';
      if (data.genres?.length) genresEl.classList.remove('is-skeleton');
    }

    // Creator (directors for movies, networks for TV shows)
    const creatorEl = document.getElementById('modal-creator');
    if (creatorEl) {
      const creatorNames = (data.type === 'movie' ? data.directors : data.networks)?.join(', ') || '';
      creatorEl.textContent = creatorNames;
      if (creatorNames) {
        creatorEl.classList.remove('is-skeleton');
      }
    }

    // Description with expand toggle
    const desc = data.description || data.overview || '';
    const descEl = document.getElementById('modal-desc');
    const descToggle = document.getElementById('modal-desc-toggle');
    if (descEl) {
      descEl.textContent = desc;
      descEl.classList.remove('expanded');
      if (desc) {
        descEl.classList.remove('is-skeleton');
      }
    }
    if (descToggle) {
      descToggle.style.display = desc.length > 150 ? 'inline' : 'none';
      descToggle.textContent = 'more...';
    }

    // Buttons
    document.getElementById('modal-play-btn').onclick = () => playVideo(data);
    document.getElementById('modal-watchlist-btn').onclick = () => toggleWatchlist(videoId);

    // Set watchlist button data attribute and initial icon
    const watchBtn = document.getElementById('modal-watchlist-btn');
    if (watchBtn) {
      watchBtn.dataset.watchlistId = data.id;
      const modalIcon = watchBtn.querySelector('i');
      if (modalIcon) modalIcon.className = data.favorite ? 'fa-solid fa-check' : 'fa-solid fa-plus';
      watchBtn.title = data.favorite ? 'Remove from Watchlist' : 'Add to Watchlist';
    }

    // Build "More Like This" section
    const content = document.querySelector('#cinema-modal .cinema-modal-content');
    const moreLikeHtml = buildMoreLikeThis(data);
    if (moreLikeHtml) {
      content?.insertAdjacentHTML('beforeend', moreLikeHtml);
    }

    // Featurettes & Extras section (for movies with bonus content)
    if (data.type === 'movie' && data.seriesTitle) {
      const extras = allVideos.filter(v =>
        v.seriesTitle === data.seriesTitle &&
        v.type === 'episode' &&
        v.id !== data.id
      );
      if (extras.length) {
        const groups = {};
        extras.forEach(v => {
          const ct = v.contentType || 'extra';
          if (!groups[ct]) groups[ct] = [];
          groups[ct].push(v);
        });
        let extrasHtml = '<div class="cinema-episodes-section" style="margin-top:1.5rem;"><div class="cinema-section-header"><h2 class="cinema-section-title">Featurettes & Extras</h2></div>';
        Object.entries(groups).forEach(([ct, eps]) => {
          extrasHtml += '<div style="margin-bottom:1rem;"><div style="color:rgba(255,255,255,0.5);font-size:0.75rem;font-weight:600;text-transform:uppercase;letter-spacing:0.05em;margin-bottom:0.5rem;">' + ct.charAt(0).toUpperCase() + ct.slice(1) + 's</div>';
          extrasHtml += buildEpisodesList(eps);
          extrasHtml += '</div>';
        });
        extrasHtml += '</div>';
        content?.insertAdjacentHTML('beforeend', extrasHtml);
      }
    }

    // Auto-refresh: if key fields are empty, poll for updated video data
    const needsRefresh = !data.overview || !data.genres?.length || (!data.directors?.length && data.type === 'movie') || (!data.networks?.length && data.type !== 'movie');
    if (needsRefresh) {
      if (modal._seriesPollInterval) clearInterval(modal._seriesPollInterval);
      let attempts = 0;
      modal._seriesPollInterval = setInterval(async () => {
        attempts++;
        if (attempts > 7) { clearInterval(modal._seriesPollInterval); return; }
        try {
          const updated = await fetchJSON(`/api/video/${videoId}`);
          if (!updated) return;

          const descText = updated.description || updated.overview || '';
          const descEl = document.getElementById('modal-desc');
          if (descEl && descText) {
            descEl.textContent = descText;
            descEl.classList.remove('is-skeleton');
          }

          const genresEl = document.getElementById('modal-genres');
          if (genresEl && updated.genres?.length) {
            genresEl.innerHTML = updated.genres.map(g => `<span class="cinema-genre-chip">${g}</span>`).join('');
            genresEl.classList.remove('is-skeleton');
          }

          const creatorEl = document.getElementById('modal-creator');
          if (creatorEl) {
            const creatorNames = (updated.type === 'movie' ? updated.directors : updated.networks)?.join(', ') || '';
            if (creatorNames) {
              creatorEl.textContent = creatorNames;
              creatorEl.classList.remove('is-skeleton');
            }
          }

          if (descText && updated.genres?.length) clearInterval(modal._seriesPollInterval);
        } catch (e) { /* ignore transient errors */ }
      }, 2000);
    }
  });
}

function toggleDescription() {
  const desc = document.getElementById('modal-desc');
  const toggle = document.getElementById('modal-desc-toggle');
  if (!desc || !toggle) return;

  const isExpanded = desc.classList.contains('expanded');
  if (isExpanded) {
    desc.classList.remove('expanded');
    toggle.textContent = 'more...';
  } else {
    desc.classList.add('expanded');
    toggle.textContent = 'less...';
  }
}

let previousFocusElement = null;
let focusTrapHandler = null;

function trapFocus(modalElement) {
  previousFocusElement = document.activeElement;
  const focusable = modalElement.querySelectorAll('button, input, a, [tabindex]:not([tabindex="-1"])');
  if (focusable.length === 0) return;

  focusTrapHandler = function(e) {
    if (e.key === 'Tab') {
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    } else if (e.key === 'Escape') {
      closeModal();
    }
  };
  modalElement.addEventListener('keydown', focusTrapHandler);
  focusable[0].focus();
}

function releaseFocusTrap(modalElement) {
  if (focusTrapHandler) {
    modalElement.removeEventListener('keydown', focusTrapHandler);
    focusTrapHandler = null;
  }
  if (previousFocusElement) {
    previousFocusElement.focus();
    previousFocusElement = null;
  }
}

function initModalScrollBlur() {
  const scroll = document.querySelector('.cinema-modal-scroll');
  if (!scroll || scroll._blurListenerAttached) return;
  scroll.addEventListener('scroll', function() {
    const scrollY = this.scrollTop;
    const maxScroll = this.scrollHeight - this.clientHeight;
    const progress = maxScroll > 0 ? Math.min(scrollY / maxScroll, 1) : 0;

    // Shift blur background position for parallax
    const blurInner = document.getElementById('modal-bg-blur-inner');
    if (blurInner) {
      const shift = progress * 20; // shift up to 20px
      blurInner.style.backgroundPosition = `center calc(50% + ${shift}px)`;
    }
  }, { passive: true });
  scroll._blurListenerAttached = true;
}

function closeModal() {
  // Clear series data polling
  const modal = document.getElementById('cinema-modal');
  if (modal && modal._seriesPollInterval) { clearInterval(modal._seriesPollInterval); modal._seriesPollInterval = null; }
  if (modal._backdropRetry) { clearTimeout(modal._backdropRetry); modal._backdropRetry = null; }
  if (modal._logoRetry) { clearTimeout(modal._logoRetry); modal._logoRetry = null; }

  // Reset dynamic hero overlay color
  const heroOverlay = document.querySelector('.cinema-modal-hero-overlay');
  if (heroOverlay) heroOverlay.style.backgroundColor = '';

  // Clear hero backdrop image
  const backdropImg = document.getElementById('modal-backdrop-img');
  if (backdropImg) {
    backdropImg.onerror = null;
    backdropImg.onload = null;
    backdropImg.src = '';
    backdropImg.classList.add('is-skeleton');
  }

  // Clear blurred backgrounds
  const bgBlur = document.getElementById('modal-bg-blur');
  if (bgBlur) bgBlur.style.backgroundImage = '';
  const bgBlurInner = document.getElementById('modal-bg-blur-inner');
  if (bgBlurInner) bgBlurInner.style.backgroundImage = '';

  // Reset logo hero image + text title fallback
  const logoHeroImg = document.getElementById('modal-logo-hero-img');
  if (logoHeroImg) {
    logoHeroImg.onerror = null;
    logoHeroImg.onload = null;
    logoHeroImg.src = '';
    logoHeroImg.style.display = '';
    logoHeroImg.classList.add('is-skeleton');
  }
  const textTitleEl = document.getElementById('modal-text-title');
  if (textTitleEl) { textTitleEl.textContent = ''; textTitleEl.style.display = 'none'; }

  // Reset description
  const descEl = document.getElementById('modal-desc');
  if (descEl) { descEl.innerHTML = ''; descEl.classList.add('is-skeleton'); }
  const descToggle = document.getElementById('modal-desc-toggle');
  if (descToggle) descToggle.style.display = 'none';

  // Reset genres
  const genresEl = document.getElementById('modal-genres');
  if (genresEl) { genresEl.innerHTML = ''; genresEl.classList.add('is-skeleton'); }

  // Reset creator
  const creatorEl = document.getElementById('modal-creator');
  if (creatorEl) { creatorEl.textContent = ''; creatorEl.classList.add('is-skeleton'); }

  // Reset meta row
  const metaRow = document.getElementById('modal-meta-row');
  if (metaRow) metaRow.innerHTML = '';

  releaseFocusTrap(document.getElementById('cinema-modal'));
  const playBtn = document.getElementById('modal-play-btn');
  const watchBtn = document.getElementById('modal-watchlist-btn');
  if (playBtn) playBtn.style.display = '';
  if (watchBtn) watchBtn.style.display = '';
  document.getElementById('cinema-modal-backdrop').classList.remove('active');
  document.getElementById('cinema-modal').classList.remove('active');
  document.body.style.overflow = '';
  // Portal: restore modal to original parent
  const originalParent = document.getElementById(modal.dataset.originalParent) || document.getElementById('app-content');
  if (originalParent) originalParent.appendChild(modal);
}

function openSettingsModal() {
  document.getElementById('settings-modal-backdrop').classList.add('active');
  document.getElementById('settings-modal').classList.add('active');
  document.body.style.overflow = 'hidden';
  loadSettings();
}

function closeSettingsModal() {
  document.getElementById('settings-modal-backdrop').classList.remove('active');
  document.getElementById('settings-modal').classList.remove('active');
  document.body.style.overflow = '';
}

function switchSettingsTab(tabName) {
  document.querySelectorAll('.cinema-settings-tab').forEach(t => t.classList.remove('active'));
  document.querySelectorAll('.cinema-settings-panel').forEach(p => p.classList.remove('active'));
  const tab = document.querySelector(`.cinema-settings-tab[data-tab="${tabName}"]`);
  const panel = document.getElementById(`settings-panel-${tabName}`);
  if (tab) tab.classList.add('active');
  if (panel) panel.classList.add('active');
  if (tabName === 'account') {
    loadAccountPanel();
  }
}

function setTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  localStorage.setItem('jmedia-theme', theme);
  const darkBtn = document.getElementById('theme-dark-btn');
  const lightBtn = document.getElementById('theme-light-btn');
  if (darkBtn && lightBtn) {
    darkBtn.classList.toggle('active', theme === 'dark');
    lightBtn.classList.toggle('active', theme === 'light');
  }
}

function savePlayerSetting(value) {
  localStorage.setItem('video-test-player', value);
  reloadPlayerIfOpen();
}

function saveDefaultPlayerSetting(value) {
  localStorage.setItem('video-test-default-player', value);
  const profileId = localStorage.getItem('activeProfileId');
  fetch(`/api/settings/${profileId}/default-player`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ defaultPlayer: value })
  }).catch(() => {});
  reloadPlayerIfOpen();
}

function reloadPlayerIfOpen() {
  const modal = document.getElementById('player-modal');
  if (modal && modal.classList.contains('active')) {
    const content = document.getElementById('player-modal-content');
    const playerEl = content?.querySelector('[data-video-id]');
    if (playerEl) {
      const videoId = playerEl.getAttribute('data-video-id');
      if (videoId) {
        closePlayerModal();
        setTimeout(() => openPlayerModal(parseInt(videoId)), 300);
      }
    }
  }
}

function updateCrossfadeDisplay(value) {
  const display = document.getElementById('crossfade-value');
  if (display) display.textContent = `${value}s`;
  localStorage.setItem('video-crossfade', value);
  saveSettingsDebounced();
}

function toggleAutoSkip(type, el) {
  el.classList.toggle('active');
  saveSettingsDebounced();
}

let _saveSettingsTimer;
function saveSettingsDebounced() {
  clearTimeout(_saveSettingsTimer);
  _saveSettingsTimer = setTimeout(saveSettings, 500);
}

function saveSettings() {
  const skipIntro = document.getElementById('skip-intro-toggle')?.classList.contains('active') || false;
  const skipRecap = document.getElementById('skip-recap-toggle')?.classList.contains('active') || false;
  const skipOutro = document.getElementById('skip-outro-toggle')?.classList.contains('active') || false;
  const profileId = localStorage.getItem('activeProfileId');

  fetch(`/api/settings/${profileId}/auto-skip`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      autoSkipIntro: skipIntro,
      autoSkipRecap: skipRecap,
      autoSkipOutro: skipOutro
    })
  }).catch(() => {});
}

async function loadSettings() {
  const savedPlayer = localStorage.getItem('video-test-player');
  if (savedPlayer) {
    const select = document.getElementById('player-select');
    if (select) select.value = savedPlayer;
  }
  const savedDefaultPlayer = localStorage.getItem('video-test-default-player');
  if (savedDefaultPlayer) {
    const defaultSel = document.getElementById('default-player-select');
    if (defaultSel) defaultSel.value = savedDefaultPlayer;
  }

  try {
    const profileId = localStorage.getItem('activeProfileId');
    const playbackData = await fetchJSON(`/api/settings/${profileId}`);
    if (playbackData) {
      const toggles = { autoSkipIntro: 'skip-intro-toggle', autoSkipRecap: 'skip-recap-toggle', autoSkipOutro: 'skip-outro-toggle' };
      for (const [key, id] of Object.entries(toggles)) {
        const toggle = document.getElementById(id);
        if (toggle && playbackData[key] !== undefined) {
          toggle.classList.toggle('active', !!playbackData[key]);
        }
      }
      if (playbackData.defaultPlayer) {
        const defaultSel = document.getElementById('default-player-select');
        if (defaultSel) defaultSel.value = playbackData.defaultPlayer;
      }
    }
  } catch (e) {}

  const savedCrossfade = localStorage.getItem('video-crossfade');
  if (savedCrossfade !== null) {
    const slider = document.getElementById('crossfade-slider');
    if (slider) {
      slider.value = savedCrossfade;
      updateCrossfadeDisplay(parseInt(savedCrossfade, 10));
    }
  }

  try {
    const verData = await fetchJSON('/api/update/latest');
    const verEl = document.getElementById('cinema-version');
    if (verEl) {
      verEl.textContent = verData?.version || 'unknown';
    }
  } catch (e) {
    const verEl = document.getElementById('cinema-version');
    if (verEl) verEl.textContent = 'unknown';
  }
}

// ============================================================
// Profile Management
// ============================================================
async function loadAccountPanel() {
  const list = document.getElementById('profile-list');
  if (!list) return;
  try {
    const res = await fetch('/api/profiles');
    const profiles = await res.json();
    const curRes = await fetch('/api/profiles/current');
    const cur = await curRes.json();
    list.innerHTML = profiles.map(p => `
      <div class="account-profile-item${cur.id === p.id ? ' active' : ''}" onclick="switchProfile(${p.id})">
        <span class="profile-name">${p.name}</span>
        ${p.isMainProfile ? '<span class="profile-badge main">Main</span>' : ''}
        ${cur.id === p.id ? '<span class="profile-badge current">Current</span>' : ''}
      </div>
    `).join('');
  } catch (e) {
    list.innerHTML = '<span style="color:var(--cinema-text-dim);font-size:0.75rem;">Failed to load profiles</span>';
  }
}

function switchProfile(profileId) {
  fetch(`/api/profiles/switch/${profileId}`, { method: 'POST' })
    .then(() => { window.location.reload(); })
    .catch(e => console.error('Error switching profile:', e));
}

function createProfile() {
  const input = document.getElementById('create-profile-input');
  if (!input) return;
  const name = input.value.trim();
  if (!name) return;
  fetch('/api/profiles', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: name })
  })
  .then(response => {
    if (response.ok) {
      input.value = '';
      loadAccountPanel();
    }
  })
  .catch(e => console.error('Error creating profile:', e));
}

// ============================================================
// Actions
// ============================================================
async function openPlayerModal(videoId) {
  const backdrop = document.getElementById('player-modal-backdrop');
  const modal = document.getElementById('player-modal');
  const content = document.getElementById('player-modal-content');
  if (!backdrop || !modal || !content) return;

  backdrop.classList.add('active');
  modal.classList.add('active');
  document.body.style.overflow = 'hidden';

  try {
    const resp = await fetch(`/api/video/ui/playback-fragment?videoId=${videoId}&cinema=true`);
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const html = await resp.text();

    // innerHTML does NOT execute <script> tags per browser spec.
    // Parse the fragment, strip scripts, inject HTML, then re-create script elements.
    const tmp = document.createElement('div');
    tmp.innerHTML = html;

    const scripts = Array.from(tmp.querySelectorAll('script'));
    scripts.forEach(s => s.remove());

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
  } catch (err) {
    console.error('[player-modal] failed to load playback fragment', err);
    content.innerHTML = `<div style="display:flex;align-items:center;justify-content:center;height:100%;color:rgba(255,255,255,0.5);">Failed to load player</div>`;
  }
}

function closePlayerModal() {
  const backdrop = document.getElementById('player-modal-backdrop');
  const modal = document.getElementById('player-modal');
  const content = document.getElementById('player-modal-content');

  if (window.currentPlayerInstance) {
    try {
      if (window.currentPlayerInstance.progressReporter) {
        window.currentPlayerInstance.progressReporter.saveNow();
      }
      if (typeof window.currentPlayerInstance.destroy === 'function') {
        window.currentPlayerInstance.destroy();
      } else if (window.currentPlayerInstance.pause) {
        window.currentPlayerInstance.pause();
      }
    } catch (_) { /* best-effort cleanup */ }
  }
  window.currentPlayerInstance = null;
  window.player = null;

  if (content) content.innerHTML = '';
  if (modal) {
    modal.classList.remove('active');
    modal.classList.remove('is-expanded');
  }
  if (backdrop) backdrop.classList.remove('active');
  document.body.style.overflow = '';

  const expandIcon = document.getElementById('player-expand-icon');
  if (expandIcon) {
    expandIcon.className = 'fa-solid fa-expand';
  }

  closeEpisodeSidebar();
}

// ============================================================
// Episode Sidebar
// ============================================================

function getPlayerContainer() {
  return document.querySelector('#player-modal-content [data-series-title]');
}

function initEpisodeSidebar() {
  const toggleBtn = document.getElementById('episode-sidebar-toggle');
  if (!toggleBtn) return;
  const container = getPlayerContainer();
  if (container && container.dataset.seriesTitle && (container.dataset.type === 'episode' || container.dataset.type === 'Episode')) {
    toggleBtn.style.display = '';
  } else {
    toggleBtn.style.display = 'none';
    closeEpisodeSidebar();
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
      const data = await fetchJSON(`/api/video/${ep.id}`);
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
      // Silently skip — UI shows fallback text
    }
  }));

  // Re-render if sidebar is still open (data may have been populated)
  if (sidebar.classList.contains('open')) {
    renderEpisodeSidebarList(episodes, currentVideoId, initialSeasonKey);
  }
}

function toggleEpisodeSidebar() {
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

  const seriesTitle = container.dataset.seriesTitle;
  const currentVideoId = container.dataset.videoId;
  const currentSeason = parseInt(container.dataset.seasonNumber) || 1;
  if (!seriesTitle) return;

  // Filter allVideos for episodes of this series
  const episodes = allVideos
    .filter(v => v.type === 'episode' && v.seriesTitle === seriesTitle)
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
    const na = parseInt(sa), nb = parseInt(sb);
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
  if (sidebar) sidebar.classList.remove('open');
  if (backdrop) backdrop.classList.remove('active');
  if (toggleBtn) toggleBtn.classList.remove('active');
}

// Audio language pill in sidebar header
function toggleSidebarAudioMenu(e) {
  e.stopPropagation();
  var menu = document.getElementById('sidebarAudioMenu');
  if (!menu) return;
  menu.style.display = menu.style.display === 'none' ? 'block' : 'none';
}

// Close audio menu when clicking outside
document.addEventListener('click', function(e) {
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

  const episodes = allVideos
    .filter(v => v.type === 'episode' && v.seriesTitle === seriesTitle
      && (v.contentType || 'episode') === ct)
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
        <img src="${thumbSrc}" alt="${epTitle}" loading="lazy">
        ${overlayHtml}
      </div>
      <div class="episode-sidebar-info">
        <div class="episode-sidebar-top-row">
          <span class="episode-sidebar-ep-number">E${epNum}</span>
          ${renderContentTypeBadge(ep.contentType)}
          ${dur ? `<span class="episode-sidebar-duration-badge">${dur}</span>` : ''}
        </div>
        <div class="episode-sidebar-ep-title">${epTitle}</div>
        ${description ? `<div class="episode-sidebar-ep-description">${description}</div>` : ''}
      </div>
    </button>`;
  }).join('');
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

// Wire season scroll state (fade edges) when seasons container scrolls
(function() {
  var container = document.getElementById('episode-sidebar-seasons');
  if (container) {
    container.addEventListener('scroll', updateSeasonScrollButtons);
  }
})();

function playSidebarEpisode(videoId) {
  // Close the sidebar
  closeEpisodeSidebar();

  // Close the current player modal
  closePlayerModal();

  // Open a new player for the clicked episode (small delay to let close animation finish)
  setTimeout(() => openPlayerModal(videoId), 100);
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

// ============================================================
// Dock + Search Wiring
// ============================================================
document.getElementById('settingsDockBtn')?.addEventListener('click', openSettingsModal);
document.getElementById('searchDockBtn')?.addEventListener('click', openSearch);
document.getElementById('nowPlayingDockBtn')?.addEventListener('click', function(e) {
  e.preventDefault();
  const sec = 'nowPlaying';
  const url = `/video-test?section=${sec}`;
  history.pushState({}, '', url);
  showSection(sec);
  document.querySelectorAll('.cinema-dock-item').forEach(i => i.classList.remove('active'));
  this.classList.add('active');
});

// SPA section navigation
document.querySelectorAll('.cinema-dock-item[data-section]').forEach(item => {
  item.addEventListener('click', function(e) {
    e.preventDefault();
    const sec = this.dataset.section;
    const url = `/video-test?section=${sec}`;
    history.pushState({}, '', url);
    showSection(sec);
    document.querySelectorAll('.cinema-dock-item').forEach(i => i.classList.remove('active'));
    this.classList.add('active');
  });
});
// Home button � SPA style
const homeDockBtn = document.querySelector('.cinema-dock-item[href="/video-test"]');
if (homeDockBtn) {
  homeDockBtn.addEventListener('click', function(e) {
    e.preventDefault();
    history.pushState({}, '', '/video-test');
    showSection('home');
    document.querySelectorAll('.cinema-dock-item').forEach(i => i.classList.remove('active'));
    this.classList.add('active');
  });
}
// Browser back/forward
window.addEventListener('popstate', () => {
  const p = new URLSearchParams(window.location.search);
  const sec = p.get('section') || 'home';
  showSection(sec);
  document.querySelectorAll('.cinema-dock-item').forEach(i => i.classList.remove('active'));
  if (sec !== 'home') {
    const t = document.querySelector(`.cinema-dock-item[data-section="${sec}"]`);
    if (t) t.classList.add('active');
  } else {
    document.querySelector('.cinema-dock-item')?.classList.add('active');
  }
});

// Wire logout
document.getElementById('logout-btn')?.addEventListener('click', function() {
  fetch('/api/auth/logout', { method: 'POST' }).then(() => {
    window.location.href = '/login.html';
  });
});

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
  if (input) { input.value = ''; input.focus(); }
}

function closeSearch() {
  const dock = document.querySelector('.cinema-dock');
  if (dock) dock.classList.remove('searching');
  const input = document.getElementById('searchInput');
  if (input) input.value = '';
  const dropdown = document.getElementById('searchDropdown');
  if (dropdown) { dropdown.innerHTML = ''; dropdown.classList.remove('visible'); }
}

async function performSearch(query) {
  const dropdown = document.getElementById('searchDropdown');
  if (!dropdown) return;
  const q = query.trim().toLowerCase();
  if (!q) { dropdown.innerHTML = ''; dropdown.classList.remove('visible'); return; }
  
  // Use the global allVideos already loaded by loadCinemaData()
  if (!window.allVideos || !window.allVideos.length) return;
  
  // Deduplicate: one result per movie, one per TV series
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
  const results = matches.slice(0, 20);
  
  if (!results.length) { dropdown.innerHTML = '<div class="cinema-search-hint">No results found.</div>'; dropdown.classList.add('visible'); return; }
  dropdown.innerHTML = results.map(v => {
    const id = v.id;
    const title = v.type === 'movie' ? (v.title || 'Untitled') : (v.seriesTitle || v.title || 'Untitled');
    const type = v.type === 'movie' ? 'Movie' : 'TV';
    const year = v.releaseYear ? ` \u2022 ${v.releaseYear}` : '';
    return `<button class="cinema-search-result" data-click="closeSearchByClick" data-search-action="${v.type === 'movie' ? 'openDetails' : 'openSeriesDetailFromSearch'}" ${v.type === 'movie' ? `data-video-id="${id}"` : `data-series-title="${encodeURIComponent(v.seriesTitle || '')}"`}>
      <img class="cinema-search-result-thumb" src="/api/video/poster/${id}" alt="" loading="lazy">
      <div class="cinema-search-result-info">
        <div class="cinema-search-result-title">${title}</div>
        <div class="cinema-search-result-sub">${type}${year}</div>
      </div>
    </button>`;
  }).join('');
  dropdown.classList.add('visible');
}

document.getElementById('searchInput')?.addEventListener('input', debounce(function(e) {
  performSearch(e.target.value);
}, 250));

document.addEventListener('keydown', (e) => {
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
      if (playerModal.classList.contains('is-expanded')) {
        togglePlayerExpand();
      } else {
        closePlayerModal();
      }
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

document.addEventListener('click', (e) => {
  const dock = document.querySelector('.cinema-dock');
  if (dock && dock.classList.contains('searching')) {
    if (!dock.contains(e.target)) {
      closeSearch();
    }
  }
});

// ============================================================
// Carousel Arrow Navigation
// ============================================================
document.addEventListener('click', (e) => {
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

document.querySelectorAll('.cinema-carousel').forEach(c => c.addEventListener('scroll', updateCarouselArrows));
window.addEventListener('resize', function() {
  updateCarouselArrows();
  updateSeasonScrollButtons();
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
})();

// Wire user info in settings sidebar
fetchJSON('/api/auth/current-user').then(data => {
  if (data?.loggedIn && data?.username) {
    const el = document.getElementById('settings-username');
    if (el) el.textContent = data.username;
  }
}).catch(() => {});

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

// === Show All / Show Less toggle ===
function toggleShowAll(mode) {
  if (_showAllMode === mode) showLess();
  else showAll(mode);
}

function showAll(mode) {
  if (_showAllMode && _showAllMode !== mode) showLess();
  _showAllMode = mode;
  const sectionId = mode === 'movies' ? 'section-movies' : 'section-tvshows';
  const section = document.getElementById(sectionId);
  const carouselWrapper = section.querySelector('.cinema-carousel-wrapper');
  if (carouselWrapper) carouselWrapper.style.display = 'none';
  let grid = document.getElementById(`${mode}-show-all-grid`);
  if (!grid) {
    grid = document.createElement('div');
    grid.id = `${mode}-show-all-grid`;
    grid.style.cssText = 'display: flex; flex-wrap: wrap; gap: 1rem; padding: 0 24px 1rem;';
    section.insertBefore(grid, carouselWrapper);
  }
  _showAllData = mode === 'movies' ? allVideos.filter(v => v.type === 'movie') : [...allTvShows];
  _showAllIndex = 0;
  grid.innerHTML = '';
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
  const sectionId = _showAllMode === 'movies' ? 'section-movies' : 'section-tvshows';
  const section = document.getElementById(sectionId);
  const cw = section.querySelector('.cinema-carousel-wrapper');
  if (cw) cw.style.display = '';
  const sc = document.getElementById(`${_showAllMode}-sort-controls`);
  if (sc) sc.style.display = 'none';
  const btn = document.getElementById(`${_showAllMode}-show-all-btn`);
  if (btn) btn.textContent = 'Show All';
  _showAllMode = null;
}

function loadMoreShowAll() {
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

// ============================================================
// XSS-Safe Delegated Handlers for Cards (replaces inline onclick)
// ============================================================
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
  if (card && card.dataset.videoId) openDetails(parseInt(card.dataset.videoId));
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
  }
}

// ============================================================
// Event Delegation: data-click and data-change attributes
// ============================================================
document.addEventListener('click', function(e) {
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

document.addEventListener('change', function(e) {
  var el = e.target;
  var action = el.getAttribute('data-change');
  if (action) {
    var callFn = window[action];
    if (typeof callFn === 'function') {
      callFn(el.value, el);
    }
  }
});
