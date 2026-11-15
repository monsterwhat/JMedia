// Cinema view: External (not-in-library) details, series/detail modal, episode list, more-like-this, comments.
// Split out of cinema-mode.js. Loaded by views/video.html in a fixed order;
// these are classic scripts, so they share one global scope.
/**
 * The full cinema card for a title that is NOT in the library: same hero,
 * badges, genres, overview and season/episode list as a local show, but Play is
 * replaced by Request and every episode is marked missing/unreleased/TBA.
 * Returns false (after closing the skeleton) when TMDb has nothing to show, so
 * the caller can fall back to the plain "not in your library" page.
 */
async function openExternalDetails(tmdbId, mediaType, meta) {
  if (!tmdbId) return false;
  const gen = ++_modalGeneration;
  const modal = document.getElementById('cinema-modal');
  if (!modal) { console.warn('[cinema] cinema-modal not found'); return false; }
  if (modal._seriesPollInterval) { clearInterval(modal._seriesPollInterval); modal._seriesPollInterval = null; }
  if (modal._backdropRetry) { clearTimeout(modal._backdropRetry); modal._backdropRetry = null; }
  if (modal._logoRetry) { clearTimeout(modal._logoRetry); modal._logoRetry = null; }

  // Reset the card from whatever was open before.
  const resetDesc = document.getElementById('modal-desc');
  if (resetDesc) { resetDesc.innerHTML = ''; resetDesc.textContent = ''; resetDesc.classList.add('is-skeleton'); resetDesc.classList.remove('expanded'); }
  const resetGenres = document.getElementById('modal-genres');
  if (resetGenres) { resetGenres.innerHTML = ''; resetGenres.classList.add('is-skeleton'); }
  const resetCreator = document.getElementById('modal-creator');
  if (resetCreator) { resetCreator.textContent = ''; resetCreator.classList.add('is-skeleton'); }
  const resetMeta = document.getElementById('modal-meta-row');
  if (resetMeta) resetMeta.innerHTML = '';
  const resetToggle = document.getElementById('modal-desc-toggle');
  if (resetToggle) resetToggle.style.display = 'none';
  const content = modal.querySelector('.cinema-modal-content');
  if (content) {
    const oldEpisodes = content.querySelector('.cinema-episodes-section, .cinema-series-content');
    if (oldEpisodes) oldEpisodes.remove();
    const oldMoreLike = content.querySelector('.cinema-more-like-section');
    if (oldMoreLike) oldMoreLike.remove();
  }

  // Open immediately with a skeleton.
  modal.dataset.originalParent = modal.parentElement.id || 'app-content';
  document.body.appendChild(modal);
  document.getElementById('cinema-modal-backdrop').classList.add('active');
  modal.classList.add('active');
  trapFocus(modal);
  document.body.style.overflow = 'hidden';
  initModalScrollBlur();

  const textTitleEl = document.getElementById('modal-text-title');
  const logoHeroImg = document.getElementById('modal-logo-hero-img');
  const fallbackTitle = meta && meta.title ? String(meta.title) : '';
  if (textTitleEl) { textTitleEl.textContent = fallbackTitle; textTitleEl.style.display = ''; }
  if (logoHeroImg) {
    logoHeroImg.onerror = null;
    logoHeroImg.onload = null;
    logoHeroImg.removeAttribute('src');
    logoHeroImg.classList.remove('is-skeleton');
    logoHeroImg.style.display = 'none';
  }

  // No playback for a catalogue title.
  const playBtn = document.getElementById('modal-play-btn');
  if (playBtn) { playBtn.style.display = 'none'; playBtn.onclick = null; }
  const dlBtn = document.getElementById('modal-download-btn');
  if (dlBtn) { dlBtn.style.display = 'none'; dlBtn.onclick = null; }
  const watchBtn = document.getElementById('modal-watchlist-btn');
  if (watchBtn) { watchBtn.style.display = 'none'; watchBtn.onclick = null; }
  _hideRequestButton();

  let url = '/api/video/ui/external-details?tmdbId=' + encodeURIComponent(tmdbId);
  if (mediaType) url += '&type=' + encodeURIComponent(mediaType);
  const data = await fetchJSON(url).catch(() => null);
  if (gen !== _modalGeneration) return false;

  if (!data || data.found === false) {
    // Undo the skeleton without touching the shareable URL.
    if (textTitleEl) { textTitleEl.textContent = ''; textTitleEl.style.display = 'none'; }
    document.getElementById('cinema-modal-backdrop').classList.remove('active');
    modal.classList.remove('active');
    document.body.style.overflow = '';
    releaseFocusTrap(modal);
    return false;
  }

  _removeNotInLibraryPage();
  const isTv = data.mediaType === 'tv';
  const title = data.title || fallbackTitle;
  modal.dataset.seriesTitle = 'tmdb:' + tmdbId;
  modal.dataset.catalogueTmdbId = String(tmdbId);
  if (textTitleEl) { textTitleEl.textContent = title; textTitleEl.style.display = ''; }
  if (logoHeroImg) {
    logoHeroImg.onerror = null;
    logoHeroImg.onload = null;
    logoHeroImg.removeAttribute('src');
    logoHeroImg.classList.remove('is-skeleton');
    logoHeroImg.style.display = 'none';
  }

  // Hero backdrop (TMDb URLs only; the server builds them).
  const isTmdbImage = function (u) { return typeof u === 'string' && /^https:\/\/image\.tmdb\.org\/t\/p\/[A-Za-z0-9_]+\/[A-Za-z0-9_.-]+$/.test(u); };

  // Title art: TMDb's transparent logo when the show/movie has one, else text.
  if (logoHeroImg) {
    const logoUrl = isTmdbImage(data.logo) ? data.logo : '';
    if (logoUrl) {
      logoHeroImg.onerror = function () {
        logoHeroImg.onerror = null;
        logoHeroImg.onload = null;
        logoHeroImg.style.display = 'none';
        if (textTitleEl) { textTitleEl.textContent = title; textTitleEl.style.display = ''; }
      };
      logoHeroImg.onload = function () {
        logoHeroImg.style.display = 'block';
        if (textTitleEl) textTitleEl.style.display = 'none';
      };
      logoHeroImg.src = logoUrl;
      logoHeroImg.style.maxWidth = '288px';
      logoHeroImg.style.maxHeight = '80px';
      logoHeroImg.classList.remove('is-skeleton');
    } else {
      logoHeroImg.style.display = 'none';
      logoHeroImg.classList.remove('is-skeleton');
      if (textTitleEl) { textTitleEl.textContent = title; textTitleEl.style.display = ''; }
    }
  }
  const heroUrl = isTmdbImage(data.backdrop) ? data.backdrop
    : (isTmdbImage(data.poster) ? data.poster : '');
  const backdropImg = document.getElementById('modal-backdrop-img');
  if (backdropImg) {
    backdropImg.onerror = null;
    backdropImg.onload = null;
    if (heroUrl) {
      backdropImg.src = heroUrl;
      backdropImg.classList.remove('is-skeleton');
      backdropImg.onerror = function () { backdropImg.onerror = null; backdropImg.classList.add('is-skeleton'); };
    } else {
      backdropImg.removeAttribute('src');
      backdropImg.classList.add('is-skeleton');
    }
  }
  const bgBlur = document.getElementById('modal-bg-blur');
  if (bgBlur) bgBlur.style.backgroundImage = heroUrl ? `url(${heroUrl})` : '';
  const bgBlurInner = document.getElementById('modal-bg-blur-inner');
  if (bgBlurInner) bgBlurInner.style.backgroundImage = heroUrl ? `url(${heroUrl})` : '';
  if (heroUrl) extractAndApplyModalColor(heroUrl, document.querySelector('.cinema-modal-hero-overlay'));

  if (window.JMedia && window.JMedia.PageChrome) {
    window.JMedia.PageChrome.setForVideoDetails(title);
  }

  // Meta badges: rating, year, seasons/length, availability.
  const metaRow = document.getElementById('modal-meta-row');
  if (metaRow) {
    let html = '';
    const ratingVal = Number(data.voteAverage) || 0;
    if (ratingVal > 0) html += `<span style="color:${ratingColor(ratingVal)};font-weight:600;"><i class="fa-solid fa-star" style="font-size:0.6rem;"></i> ${ratingVal.toFixed(1)}</span>`;
    if (data.year) html += `<span class="meta-badge">${escapeHtml(String(data.year))}</span>`;
    if (isTv) {
      const sc = Number(data.seasonCount) || (data.seasons ? data.seasons.length : 0);
      if (sc > 0) html += `<span class="meta-badge">${sc} Season${sc > 1 ? 's' : ''}</span>`;
    } else if (data.runtime) {
      html += `<span class="meta-badge">${Number(data.runtime)} min</span>`;
    }
    html += '<span class="meta-badge" style="border:1px solid rgba(245,158,11,0.55);color:#f0b24a;">Not in library</span>';
    metaRow.innerHTML = html;
  }

  const genresEl = document.getElementById('modal-genres');
  if (genresEl) {
    const genres = Array.isArray(data.genres) ? data.genres : [];
    genresEl.innerHTML = genres.map(g => `<span class="cinema-genre-chip">${escapeHtml(String(g))}</span>`).join('');
    genresEl.classList.remove('is-skeleton');
  }

  const creatorEl = document.getElementById('modal-creator');
  if (creatorEl) {
    creatorEl.textContent = data.creator ? String(data.creator) : '';
    if (data.creator) creatorEl.classList.remove('is-skeleton');
  }

  const descEl = document.getElementById('modal-desc');
  const descToggle = document.getElementById('modal-desc-toggle');
  const desc = data.overview ? String(data.overview) : '';
  if (descEl) {
    descEl.textContent = desc;
    descEl.classList.remove('expanded');
    if (desc) descEl.classList.remove('is-skeleton');
  }
  if (descToggle) {
    descToggle.style.display = desc.length > 150 ? 'inline' : 'none';
    descToggle.textContent = 'more...';
  }

  // Request replaces Play.
  const reqBtn = document.getElementById('modal-request-btn');
  if (reqBtn) {
    reqBtn.style.display = '';
    reqBtn.dataset.token = String(tmdbId);
    reqBtn.dataset.kind = 'tmdb';
    reqBtn.dataset.title = title || '';
    reqBtn.dataset.year = data.year ? String(data.year) : '';
    reqBtn.dataset.poster = isTmdbImage(data.poster) ? data.poster : '';
    reqBtn.dataset.mediaType = isTv ? 'tv' : 'movie';
    reqBtn.dataset.tmdbId = String(tmdbId);
    reqBtn.onclick = function () { requestNotInLibraryTitle(reqBtn); };
  }

  // Episodes: every catalogue episode is missing (aired), unreleased or TBA.
  // Only the first season is preloaded; the dropdown lists every TMDb season
  // and the rest are fetched by switchSeason() on demand.
  if (isTv && Array.isArray(data.seasons) && data.seasons.length) {
    const cacheKey = 'tmdb:' + tmdbId;
    const loaded = Array.isArray(data.episodes) ? data.episodes : [];
    _episodesCache.set(cacheKey, loaded);
    const numbers = (data.seasons || [])
      .map(s => Number(s.number))
      .filter(n => !isNaN(n));
    loaded.forEach(ep => {
      const n = Number(ep.seasonNumber);
      if (!numbers.includes(n)) numbers.push(n);
    });
    const ordered = numbers.filter(n => n > 0).sort((a, b) => a - b)
      .concat(numbers.filter(n => n === 0));
    const groups = {};
    const seasonKeys = [];
    ordered.forEach(n => {
      const key = n + ':episode';
      seasonKeys.push(key);
      groups[key] = [];
    });
    loaded.forEach(ep => {
      const key = (ep.seasonNumber != null ? ep.seasonNumber : 1) + ':' + (ep.contentType || 'episode');
      if (!groups[key]) { groups[key] = []; seasonKeys.push(key); }
      groups[key].push(ep);
    });
    const episodesHtml = buildCinemaEpisodesHtml(groups, seasonKeys, '');
    if (content) content.insertAdjacentHTML('beforeend', episodesHtml);
  }

  return true;
}


/** Leave the not-in-library state and restore the browse URL. */
function closeNotInLibrary() {
  _removeNotInLibraryPage();
  _resetUrlToBrowse();
  if (typeof showSection === 'function') showSection('home');
}

/** Copy the title/link so the user can ask for it to be added. */
function copyNotInLibraryTitle(el) {
  const text = el && el.dataset ? el.dataset.copy : '';
  if (!text) return;
  const done = function() {
    if (window.showToast) window.showToast('Title copied', 'success');
  };
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(done).catch(function(e) {
      console.warn('[cinema] clipboard copy failed', e);
      if (window.showToast) window.showToast('Could not copy the title', 'error');
    });
  } else if (window.showToast) {
    window.showToast('Clipboard is not available', 'warning');
  }
}

/** Submit a library request for a title that is not in the local library. */
function requestNotInLibraryTitle(el) {
  if (!el || !el.dataset) return;
  var token = el.dataset.token || '';
  var kind = el.dataset.kind || '';
  var title = el.dataset.title || '';
  var yearVal = el.dataset.year || '';
  var posterUrl = el.dataset.poster || '';
  var mediaType = el.dataset.mediaType || '';
  var tmdbIdVal = el.dataset.tmdbId || '';
  var payload = {
    token: token,
    kind: kind,
    title: title,
    year: yearVal ? Number(yearVal) : null,
    posterUrl: posterUrl || null,
    mediaType: mediaType || null,
    tmdbId: tmdbIdVal ? Number(tmdbIdVal) : null
  };

  el.disabled = true;
  el.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i>&nbsp;Requesting…';

  fetch('/api/library/requests', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  }).then(function(res) {
    return res.json().catch(function() { return {}; }).then(function(data) {
      return { ok: res.ok, status: res.status, data: data || {} };
    });
  }).then(function(result) {
    if (result.ok && result.data && result.data.existing === true) {
      el.innerHTML = '<i class="fa-solid fa-check"></i>&nbsp;Already requested';
      return;
    }
    if (result.ok) {
      el.innerHTML = '<i class="fa-solid fa-check"></i>&nbsp;Requested';
      return;
    }
    el.disabled = false;
    el.innerHTML = '<i class="fa-solid fa-paper-plane"></i>&nbsp;Request it';
    console.error('[cinema] media request failed', result.status, result.data);
    if (window.showToast) window.showToast('Could not submit request', 'error');
  }).catch(function(e) {
    el.disabled = false;
    el.innerHTML = '<i class="fa-solid fa-paper-plane"></i>&nbsp;Request it';
    console.error('[cinema] media request failed', e);
    if (window.showToast) window.showToast('Could not submit request', 'error');
  });
}



// ============================================================
// Modals
// ============================================================

async function openSeriesDetail(seriesTitle, resumeEpisodeId) {
  if (!seriesTitle) return;
  const gen = ++_modalGeneration;
  const modal = document.getElementById('cinema-modal');
  if (!modal) { console.warn('[cinema] cinema-modal not found'); return; }
  if (modal._seriesPollInterval) { clearInterval(modal._seriesPollInterval); modal._seriesPollInterval = null; }
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

  // Open modal immediately with skeleton state � don't wait for network
  modal.dataset.originalParent = modal.parentElement.id || 'app-content';
  document.body.appendChild(modal);
  document.getElementById('cinema-modal-backdrop').classList.add('active');
  modal.classList.add('active');
  trapFocus(modal);
  document.body.style.overflow = 'hidden';
  initModalScrollBlur();

  // Show text title immediately, hide logo until it loads
  modal.dataset.seriesTitle = seriesTitle;
  delete modal.dataset.catalogueTmdbId;
  const textTitleEl = document.getElementById('modal-text-title');
  const logoHeroImg = document.getElementById('modal-logo-hero-img');
  if (textTitleEl) { textTitleEl.textContent = seriesTitle; textTitleEl.style.display = ''; }
  if (logoHeroImg) { logoHeroImg.style.display = 'none'; logoHeroImg.classList.add('is-skeleton'); }

  // Hide action buttons until episode data loads
  const playBtnEarly = document.getElementById('modal-play-btn');
  if (playBtnEarly) playBtnEarly.style.display = 'none';
  const watchBtnEarly = document.getElementById('modal-watchlist-btn');
  if (watchBtnEarly) watchBtnEarly.style.display = 'none';
  const dlBtnEarly = document.getElementById('modal-download-btn');
  if (dlBtnEarly) dlBtnEarly.style.display = 'none';
  _hideRequestButton();

  // Fetch episodes for this series from the JSON endpoint
  const epResp = await fetch(`/api/video/ui/series/${encodeURIComponent(seriesTitle)}/episodes`).catch(() => null);
  if (gen !== _modalGeneration) return;
  if (!epResp || !epResp.ok) {
    if (window.showToast) window.showToast('Could not load this show', 'error');
    closeModal();
    return;
  }
  const episodes = await epResp.json();
  if (gen !== _modalGeneration) return;
  if (!Array.isArray(episodes) || !episodes.length) {
    if (window.showToast) window.showToast('No episodes found for this show', 'warning');
    closeModal();
    return;
  }
  // Sort episodes by season then episode number, then group by
  // (seasonNumber, contentType) pair - each distinct pair becomes a dropdown tab.
  const { groups, seasonKeys } = sortAndGroupEpisodes(episodes);
  // Cache episodes for the season tab handler
  _episodesCache.set(seriesTitle, episodes);
  const firstEp = episodes.find(e => e.id != null) || episodes[0];
  const resumeEp = resolveResumeEpisode(episodes, seriesTitle, resumeEpisodeId) || firstEp;
  if (gen !== _modalGeneration) return;

  const playBtn = document.getElementById('modal-play-btn');
  if (playBtn) { playBtn.style.display = ''; playBtn.onclick = () => playVideo(resumeEp); }
  const watchBtn = document.getElementById('modal-watchlist-btn');
  if (watchBtn) { watchBtn.style.display = ''; watchBtn.onclick = () => toggleWatchlist(firstEp.id); }
  const dlBtnSeries = document.getElementById('modal-download-btn');
  if (dlBtnSeries) dlBtnSeries.style.display = 'none';

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
  // Cache may have stale un-enriched data � fetch fresh from API (triggers enrichment).
  // Only fetch when the cached entry lacks both genres and overview, to avoid a
  // duplicate series fetch on every open.
  if (seriesData && seriesData.id && !(seriesData.genres?.length) && !(seriesData.overview || seriesData.description)) {
    try {
      const fresh = await fetchJSON(`/api/series/${seriesData.id}`);
      if (gen !== _modalGeneration) return;
      if (fresh && (fresh.genres?.length || fresh.overview || fresh.networks?.length)) {
        seriesData = fresh;
        seriesLookup.set(String(seriesData.id), fresh);
      }
    } catch (e) { console.debug('[cinema] series refresh failed, keeping cached metadata', e); }
  }
  if (gen !== _modalGeneration) return;
  // Shareable URL: write /video/info/{imdb|tmdb} once the ids are known.
  if (window.VideoRoutes) {
    const token = VideoRoutes.tokenFor(seriesData)
      || VideoRoutes.tokenFor(firstEp && firstEp.series)
      || (seriesId ? 'db' + seriesId : null);
    _pushInfoUrl(token);
  }
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
            const retryUrl = getSeriesImageUrl(seriesData.id, 'backdrop');
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
            logoHeroImg.src = getSeriesImageUrl(seriesData.id, 'logo');
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

  // Update the browser tab: show name + JMedia logo favicon
  if (window.JMedia && window.JMedia.PageChrome) {
    window.JMedia.PageChrome.setForVideoDetails(seriesTitle);
  }

  // Meta badges � from Series entity
  const rating = imgSource.imdbRating || imgSource.tmdbRating || '';
    const ratingVal = rating ? parseFloat(rating) : 0;
  const year = imgSource.releaseYear || '';
  const seasonCount = seasonKeys.length;
  const metaRow = document.getElementById('modal-meta-row');
  if (metaRow) {
    metaRow.innerHTML = '';
    if (ratingVal > 0) metaRow.innerHTML += `<span style="color:${ratingColor(ratingVal)};font-weight:600;"><i class="fa-solid fa-star" style="font-size:0.6rem;"></i> ${ratingVal.toFixed(1)}</span>`;
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
  const episodesHtml = buildCinemaEpisodesHtml(groups, seasonKeys, '');

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

    const seriesSynopsis = (seriesData && (seriesData.description || seriesData.overview)) || '';
    fetchMissingModalEpisodeData(seriesTitle, seasonKeys[0], seriesSynopsis);
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
        if (gen !== _modalGeneration) { clearInterval(modal._seriesPollInterval); return; }
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
          const ratingVal = parseFloat(rating);
          const metaRow = document.getElementById('modal-meta-row');
          if (metaRow) {
            const existingHtml = metaRow.innerHTML;
            if (!existingHtml.includes('fa-star')) {
              metaRow.innerHTML = `<span style="color:${ratingColor(ratingVal)};font-weight:600;"><i class="fa-solid fa-star" style="font-size:0.6rem;"></i> ${ratingVal.toFixed(1)}</span>` + existingHtml;
            }
          }
        }

        // Stop polling if all fields populated
        const allFilled = descText && updated.genres?.length && updated.networks?.length;
        if (allFilled) clearInterval(modal._seriesPollInterval);
      } catch (e) { console.debug('[cinema] modal metadata poll failed', e); }
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
    if (ep.id != null) _episodeById.set(String(ep.id), ep);
    const epTitle = ep.episodeTitle || ep.title || `Episode ${(ep.episodeNumber || i + 1)}`;
    const epNum = ep.episodeNumber || (i + 1);

    if (ep.missing) {
      const state = ep.state;
      const badge = state === 'UNRELEASED' ? 'Unreleased' : (state === 'TBA' ? 'TBA' : 'Not in library');
      const sub = state === 'UNRELEASED'
        ? (ep.airDateDisplay ? 'Airs ' + escapeHtml(ep.airDateDisplay) : (ep.airDate ? 'Airs ' + escapeHtml(ep.airDate) : 'Air date TBA'))
        : (state === 'TBA' ? 'Air date TBA' : 'Not in library');
      const img = ep.stillUrl
        ? `<img src="${escapeHtml(ep.stillUrl)}" alt="${escapeHtml(epTitle)}" loading="lazy" style="width:100%;height:100%;object-fit:cover;border-radius:8px;">`
        : '';
      const request = (state === 'MISSING' && ep.requestToken)
        ? `<button type="button" class="button is-small is-link is-light mt-2"
                   data-token="${escapeHtml(ep.requestToken)}" data-kind="${escapeHtml(ep.requestKind || 'imdb')}"
                   data-title="${escapeHtml(ep.seriesTitle || '')}"
                   onclick="event.stopPropagation(); window.requestMissingTitle && window.requestMissingTitle(this)">Request</button>`
        : '';
      const overview = ep.overview
        ? `<p class="cinema-episode-desc" style="margin-top:.35rem;">${escapeHtml(String(ep.overview))}</p>`
        : '';
      return `<div class="cinema-episode-card is-missing" data-episode-number="${epNum}" style="opacity:.62;cursor:default;">
        <div class="cinema-episode-thumb-wrap">
          ${img}
          <span class="tag is-warning is-light" style="position:absolute;top:6px;left:6px;">${badge}</span>
        </div>
        <div class="cinema-episode-info">
          <div class="cinema-episode-header">
            <h3 class="cinema-episode-title">${epNum}. ${escapeHtml(epTitle)}</h3>
          </div>
          <p class="cinema-episode-desc">${sub}</p>
          ${overview}
          ${request}
        </div>
      </div>`;
    }

    const dur = formatDuration(ep.duration);
    const desc = ep.description || ep.overview || '';
    const img = getThumbnailUrl(ep.id);
    const durHtml = dur ? `<span class="cinema-episode-duration">${dur}</span>` : '';
    const metaRight = durHtml ? `<span class="cinema-episode-meta">${durHtml}</span>` : '';
    const downloadBtn = `<button class="cinema-episode-download" title="Download episode" onclick="event.stopPropagation(); downloadVideo(${ep.id})"><i class="fa-solid fa-download"></i></button>`;
    return `<div class="cinema-episode-card" data-video-id="${ep.id}" data-click="playEpisodeFromCard">
      <div class="cinema-episode-thumb-wrap">
        <img src="${withArtWidth(img,400)}" srcset="${artSrcset(img)}" sizes="112px" alt="${escapeHtml(epTitle)}" loading="lazy">
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
          ${metaRight}
          ${downloadBtn}
        </div>
        ${desc ? `<p class="cinema-episode-desc">${desc}</p>` : '<div class="cinema-episode-desc is-skeleton"></div>'}
      </div>
    </div>`;
  }).join('');
}

/**
 * Sorts episodes by season then episode number and groups them by
 * (seasonNumber, contentType) pair. Each distinct pair becomes a separate
 * dropdown tab. Returns { groups, seasonKeys } where seasonKeys is sorted by
 * seasonNumber, then contentType priority.
 */
function sortAndGroupEpisodes(episodes) {
  // Sort episodes by season then episode number
  episodes.sort((a, b) => {
    const sa = a.seasonNumber ?? 1, sb = b.seasonNumber ?? 1;
    const ea = a.episodeNumber || 1, eb = b.episodeNumber || 1;
    return sa - sb || ea - eb;
  });

  // Group episodes by (seasonNumber, contentType) pair.
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
    let na = parseInt(sa), nb = parseInt(sb);
    // Specials (season 0) go last: the modal should open on a normal season.
    if (na === 0) na = Number.MAX_SAFE_INTEGER;
    if (nb === 0) nb = Number.MAX_SAFE_INTEGER;
    if (na !== nb) return na - nb;
    return (contentTypePriority[ca] ?? 99) - (contentTypePriority[cb] ?? 99);
  });

  return { groups, seasonKeys };
}

/**
 * Builds the native cinema episodes section HTML: optional title header,
 * a season dropdown when more than one (season, contentType) group exists,
 * and the episode cards for the first group.
 */
function buildCinemaEpisodesHtml(groups, seasonKeys, sectionTitle) {
  let episodesHtml = '<div class="cinema-episodes-section" style="margin-top: 1.5rem;">';
  if (sectionTitle) {
    episodesHtml += `<div class="cinema-section-header"><h2 class="cinema-section-title">${sectionTitle}</h2></div>`;
  }

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

  return episodesHtml;
}

/**
 * Fetches a series' episodes from the backend, renders the native
 * season-grouped episodes section into the cinema modal, and kicks off
 * per-episode description enrichment. Used by the "Featurettes, Extras & TV Shows"
 * section of movie modals; the series detail modal renders the same markup
 * via buildCinemaEpisodesHtml().
 */
async function renderCinemaEpisodesSection(seriesTitle, sectionTitle, seriesSynopsis) {
  if (!seriesTitle) return false;
  const gen = _modalGeneration;
  const epResp = await fetch(`/api/video/ui/series/${encodeURIComponent(seriesTitle)}/episodes`).catch(() => null);
  if (!epResp || !epResp.ok) return false;
  const episodes = await epResp.json();
  if (!Array.isArray(episodes) || !episodes.length) return false;
  if (gen !== _modalGeneration) return false;

  // Cache episodes + expose the series title so switchSeason() can resolve
  // season-tab lookups for this section.
  _episodesCache.set(seriesTitle, episodes);
  const modal = document.getElementById('cinema-modal');
  if (modal) modal.dataset.seriesTitle = seriesTitle;

  const { groups, seasonKeys } = sortAndGroupEpisodes(episodes);
  const episodesHtml = buildCinemaEpisodesHtml(groups, seasonKeys, sectionTitle);

  // Inject into modal content (replacing any previous episodes section)
  const content = document.querySelector('#cinema-modal .cinema-modal-content');
  if (content) {
    const old = content.querySelector('.cinema-episodes-section, .cinema-series-content');
    if (old) old.remove();
    content.insertAdjacentHTML('beforeend', episodesHtml);
  }

  fetchMissingModalEpisodeData(seriesTitle, seasonKeys[0], (seriesSynopsis || ''));
  return true;
}

/**
 * Polls episodes that still show the series-synopsis fallback until their own
 * per-episode description arrives. The backend injects the series synopsis
 * into `description` before enrichment lands, so "description exists" is not
 * proof the episode has its own text. Retries every ~5s (max 6 tries, ~30s),
 * updates the rendered card in place, and stops when the modal closes or
 * another series is opened.
 */
async function fetchMissingModalEpisodeData(seriesTitle, defaultSeasonKey, seriesSynopsis) {
  const episodes = _episodesCache.get(seriesTitle);
  if (!Array.isArray(episodes) || !episodes.length) return;

  const modal = document.getElementById('cinema-modal');
  if (!modal || !modal.classList.contains('active')) return;

  const gen = _modalGeneration;
  const synopsis = (seriesSynopsis || '').trim();

  // Episode still lacks its own text when the description is missing or is
  // exactly the series synopsis injected by the backend, or when the official
  // episode name (episodeTitle) has not been fetched yet.
  const isFallback = ep => {
    const desc = (ep.description || '').trim();
    const missingDesc = desc === '' || (synopsis !== '' && desc === synopsis);
    const missingTitle = !((ep.episodeTitle || '').trim());
    return missingDesc || missingTitle;
  };

  const stillMissing = episodes.filter(ep => ep.id != null && isFallback(ep));
  if (!stillMissing.length) return;

  const activeItem = document.querySelector('#cinema-season-list .cinema-season-dropdown-item.active');
  const seasonKey = activeItem ? activeItem.dataset.sk : defaultSeasonKey;
  const activeSeason = parseInt((seasonKey || '').split(':')[0]) || null;

  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const isStale = () => !modal.classList.contains('active') || gen !== _modalGeneration;

  // Fetch the fresh episodes list; its `overview`/`description`/`episodeTitle`
  // fields expose the raw per-episode data (the single-video DTO masks it with
  // series text).
  const fetchFreshList = async () => {
    try {
      const resp = await fetch(`/api/video/ui/series/${encodeURIComponent(seriesTitle)}/episodes`);
      if (!resp.ok) return null;
      return await resp.json();
    } catch (e) {
      return null;
    }
  };

  const updateCardInPlace = ep => {
    const card = modal.querySelector(`.cinema-episode-card[data-video-id="${ep.id}"]`);
    if (!card) return;
    const descEl = card.querySelector('.cinema-episode-desc');
    const text = ep.description || ep.overview || '';
    if (descEl && text) {
      descEl.classList.remove('is-skeleton');
      descEl.textContent = text;
    }
    const titleEl = card.querySelector('.cinema-episode-title');
    const title = (ep.episodeTitle || '').trim();
    if (titleEl && title) {
      const numMatch = (titleEl.textContent || '').match(/^(\d+)\.\s/);
      const epNum = ep.episodeNumber || (numMatch ? parseInt(numMatch[1], 10) : 1);
      titleEl.textContent = `${epNum}. ${title}`;
    }
  };

  const isActiveSeason = ep => activeSeason != null && (ep.seasonNumber ?? 1) === activeSeason;
  const activeMissing = stillMissing.filter(isActiveSeason);
  const restMissing = stillMissing.filter(ep => !isActiveSeason(ep));

  const pollBatch = async (batch) => {
    const triggered = new Set();
    for (let attempt = 0; attempt < 6 && batch.length > 0; attempt++) {
      if (isStale()) return;

      // Fire-and-forget enrichment triggers (batched) — the per-video call
      // persists the episode's TMDB overview and episode name server-side.
      // Each episode is triggered at most once per session (unless the call
      // failed); later attempts only re-check the list. Previously every
      // attempt re-triggered every still-missing episode, hammering
      // /api/video up to 6x per episode even when TMDB had no data to give.
      const toTrigger = batch.filter(ep => !triggered.has(String(ep.id)));
      for (let i = 0; i < toTrigger.length; i += 5) {
        await Promise.all(toTrigger.slice(i, i + 5).map(async ep => {
          triggered.add(String(ep.id));
          if (await fetchJSON(`/api/video/${ep.id}?textOnly=true`) === null) {
            triggered.delete(String(ep.id)); // transient failure — retry next attempt
          }
        }));
        if (isStale()) return;
      }

      const fresh = await fetchFreshList();
      if (isStale()) return;
      if (!Array.isArray(fresh)) {
        if (attempt < 5) await sleep(5000);
        continue;
      }

      const freshById = new Map(fresh.map(f => [String(f.id), f]));
      const still = [];
      for (const ep of batch) {
        const f = freshById.get(String(ep.id));
        const freshText = f && ((f.overview || '').trim() || (f.description || '').trim());
        const realText = freshText && (synopsis === '' || freshText !== synopsis) ? freshText : '';
        const freshTitle = f && (f.episodeTitle || '').trim();
        let pending = false;
        if (realText) {
          ep.description = realText;
          ep.overview = ep.overview || realText;
        } else {
          pending = true;
        }
        if (freshTitle) {
          ep.episodeTitle = freshTitle;
        } else {
          pending = true;
        }
        updateCardInPlace(ep);
        if (pending) still.push(ep);
      }
      // Stop when nothing converged this attempt and every remaining episode
      // already had a successful trigger — re-checking can't change anything.
      const noProgress = still.length === batch.length;
      const allTriggered = still.every(ep => triggered.has(String(ep.id)));
      batch = still;
      if (batch.length && attempt < 5) {
        if (noProgress && allTriggered) break;
        await sleep(5000);
      }
    }
  };

  await pollBatch(activeMissing);
  if (isStale()) return;
  await pollBatch(restMissing);
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
          const ratingVal = rating ? parseFloat(rating) : 0;
  const ratingText = ratingVal > 0 ? ratingVal.toFixed(1) : '';
  const starColor = ratingText ? ratingColor(ratingVal) : '';
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
            <div class="cinema-card-title">${escapeHtml(title)}</div>
            <div class="cinema-card-meta-row">
              ${ratingText ? `<span style="color:${starColor};font-size:0.7rem;font-weight:600;"><i class="fa-solid fa-star" style="font-size:0.6rem;"></i> ${ratingText}</span>` : ''}
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
  const seriesByTitle = new Map();
  seriesLookup.forEach(s => {
    if (s && s.title) seriesByTitle.set(String(s.title).trim().toLowerCase(), s);
  });
  const currentTitleKey = String(currentVideo.seriesTitle || '').trim().toLowerCase();
  const currentSeries = (currentVideo.series?.id ? seriesLookup.get(String(currentVideo.series.id)) : null)
    || (currentTitleKey ? seriesByTitle.get(currentTitleKey) : null);
  const franchiseName = currentVideo.franchiseName || currentSeries?.franchiseName;
  const collectionName = currentVideo.collectionName || currentSeries?.collectionName;
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

  // A. Genre-matched series. The videos API returns only `seriesTitle` for
  // episodes (no nested series object), so look the show up by title and fall
  // back to the episode row itself. Requiring `v.series?.id` left this list
  // empty and the section stuck on skeletons for every TV show.
  const seenSeries = new Map();
  const currentKey = currentSeries ? 'id:' + currentSeries.id : 'title:' + currentTitleKey;
  allVideos.filter(v => v.type === 'episode' && v.seriesTitle)
    .forEach(v => {
      const s = seriesByTitle.get(String(v.seriesTitle).trim().toLowerCase()) || null;
      const key = s ? 'id:' + s.id : 'title:' + String(v.seriesTitle).trim().toLowerCase();
      if (key === currentKey || seenSeries.has(key)) return;
      const genres = (s && s.genres && s.genres.length ? s.genres : (v.genres || []));
      if (genres.some(g => videoGenres.includes(g))) {
        seenSeries.set(key, s
          ? Object.assign({}, s, { __thumbId: v.id })
          : { id: null, title: v.seriesTitle, releaseYear: v.releaseYear,
              imdbRating: v.imdbRating, tmdbRating: v.tmdbRating, genres, __thumbId: v.id });
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
    const ratingVal = rating ? parseFloat(rating) : 0;
  const ratingText = ratingVal > 0 ? ratingVal.toFixed(1) : '';
  const starColor = ratingText ? ratingColor(ratingVal) : '';
    allCards.push(`<div class="cinema-more-like-card" onclick="closeModal(); openDetails(${v.id})">
      <div class="cinema-more-like-img-wrap">
        <img src="${img}" alt="${title}" loading="lazy" onerror="this.src='/logo.png'">
        <div class="cinema-more-like-play-overlay">
          <div class="cinema-more-like-play-icon">
            <i class="fa-solid fa-play"></i>
          </div>
        </div>
      </div>
      <div class="cinema-card-title">${escapeHtml(title)}</div>
      <div class="cinema-card-meta-row">
        ${ratingText ? `<span style="color:${starColor};font-size:0.7rem;font-weight:600;"><i class="fa-solid fa-star" style="font-size:0.6rem;"></i> ${ratingText}</span>` : ''}
        ${year ? `<span class="cinema-card-year">${year}</span>` : ''}
      </div>
    </div>`);
  });

  // Genre-matched series cards
  genreSeries.forEach(series => {
    const title = series.title || 'Untitled';
    const year = series.releaseYear || '';
    const hasPoster = !!series.id;
    const img = hasPoster ? getSeriesImageUrl(series.id, 'poster')
      : (series.__thumbId ? getThumbnailUrl(series.__thumbId) : '/logo.png');
    const cardSrc = hasPoster ? withArtWidth(img, 400) : img;
    const cardSrcSet = hasPoster ? artSrcset(img) : '';
    const rating = series.imdbRating || series.tmdbRating || '';
    const ratingVal = rating ? parseFloat(rating) : 0;
  const ratingText = ratingVal > 0 ? ratingVal.toFixed(1) : '';
  const starColor = ratingText ? ratingColor(ratingVal) : '';
    allCards.push(`<div class="cinema-more-like-card" data-click="openSeriesDetailFromCard" data-series-title="${encodeURIComponent(series.title || '')}">
      <div class="cinema-more-like-img-wrap">
        <img src="${cardSrc}" srcset="${cardSrcSet}" sizes="(min-width: 768px) 176px, 144px" alt="${title}" loading="lazy" ${artFallbackAttr()}>
        <div class="cinema-more-like-play-overlay">
          <div class="cinema-more-like-play-icon">
            <i class="fa-solid fa-play"></i>
          </div>
        </div>
      </div>
      <div class="cinema-card-title">${escapeHtml(title)}</div>
      <div class="cinema-card-meta-row">
        ${ratingText ? `<span style="color:${starColor};font-size:0.7rem;font-weight:600;"><i class="fa-solid fa-star" style="font-size:0.6rem;"></i> ${ratingText}</span>` : ''}
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

_trackedAddEventListener(document, 'click', function(e) {
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

  // Find the episodes for this season key from cache
  const seriesTitle = document.getElementById('cinema-modal')?.dataset.seriesTitle || '';
  const [snStr, ct] = seasonKey.split(':');
  const sn = parseInt(snStr);
  
  const allSeriesEps = _episodesCache.get(seriesTitle) || [];
  let episodes = allSeriesEps
    .filter(v => (v.seasonNumber != null ? v.seasonNumber : 1) === sn
      && (v.contentType || 'episode') === (ct || 'episode'))
    .sort((a, b) => (a.episodeNumber || 0) - (b.episodeNumber || 0));

  // Catalogue cards preload only the first season; fetch the rest on demand.
  const catalogueId = document.getElementById('cinema-modal')?.dataset.catalogueTmdbId || '';
  if (!episodes.length && catalogueId) {
    const gen = _modalGeneration;
    const sectionNow = document.querySelector('#cinema-modal .cinema-episodes-section');
    if (!sectionNow) return;
    const rowNow = sectionNow.querySelector('.cinema-season-row');
    sectionNow.querySelectorAll('.cinema-episode-card').forEach(el => el.remove());
    const placeholder = '<div class="cinema-episode-card is-missing" style="opacity:.7;cursor:default;"><div class="cinema-episode-info"><p class="cinema-episode-desc">Loading episodes…</p></div></div>';
    if (rowNow) rowNow.insertAdjacentHTML('afterend', placeholder);
    else sectionNow.insertAdjacentHTML('afterbegin', placeholder);

    fetch('/api/video/ui/external-season?tmdbId=' + encodeURIComponent(catalogueId) + '&season=' + encodeURIComponent(sn))
      .then(res => res.ok ? res.json() : [])
      .then(list => {
        if (gen !== _modalGeneration) return;
        if (!Array.isArray(list)) list = [];
        if (list.length) {
          _episodesCache.set(seriesTitle, (_episodesCache.get(seriesTitle) || []).concat(list));
        }
        const sectionAfter = document.querySelector('#cinema-modal .cinema-episodes-section');
        if (!sectionAfter) return;
        sectionAfter.querySelectorAll('.cinema-episode-card').forEach(el => el.remove());
        const rowAfter = sectionAfter.querySelector('.cinema-season-row');
        const html = list.length ? buildEpisodesList(list)
          : '<div class="cinema-episode-card is-missing" style="opacity:.7;cursor:default;"><div class="cinema-episode-info"><p class="cinema-episode-desc">No episodes listed for this season.</p></div></div>';
        if (rowAfter) rowAfter.insertAdjacentHTML('afterend', html);
        else sectionAfter.insertAdjacentHTML('afterbegin', html);
      })
      .catch(err => {
        console.warn('[cinema] catalogue season fetch failed', err);
        const sectionErr = document.querySelector('#cinema-modal .cinema-episodes-section');
        if (!sectionErr) return;
        sectionErr.querySelectorAll('.cinema-episode-card').forEach(el => el.remove());
        const rowErr = sectionErr.querySelector('.cinema-season-row');
        const html = '<div class="cinema-episode-card is-missing" style="opacity:.7;cursor:default;"><div class="cinema-episode-info"><p class="cinema-episode-desc">Could not load this season.</p></div></div>';
        if (rowErr) rowErr.insertAdjacentHTML('afterend', html);
        else sectionErr.insertAdjacentHTML('afterbegin', html);
      });
    return;
  }
  
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
  if (!modal) { console.warn('[cinema] cinema-modal not found'); return; }
  if (modal._seriesPollInterval) { clearInterval(modal._seriesPollInterval); modal._seriesPollInterval = null; }
  if (modal._backdropRetry) { clearTimeout(modal._backdropRetry); modal._backdropRetry = null; }
  if (modal._logoRetry) { clearTimeout(modal._logoRetry); modal._logoRetry = null; }
  delete modal.dataset.seriesTitle;
  delete modal.dataset.catalogueTmdbId;
  _hideRequestButton();

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
  const preDlBtn = document.getElementById('modal-download-btn');
  if (preDlBtn) preDlBtn.style.display = 'none';

  fetchJSON(`/api/video/${videoId}`).then(data => {
    if (!data) return;
    if (gen !== _modalGeneration) return;
    // Shareable URL: /video/info/{imdb|tmdb} for a movie.
    if (window.VideoRoutes) _pushInfoUrl(VideoRoutes.tokenFor(data));

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
            const retryUrl = getBackdropUrl(data);
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

    // Update the browser tab: video title + logo favicon (thumbnail fallback)
    if (window.JMedia && window.JMedia.PageChrome) {
      window.JMedia.PageChrome.setForVideoDetails(data.title || data.seriesTitle || 'JMedia', data.id, getLogoUrl(data.id));
    }

    // Meta badges
    const rating = data.imdbRating || data.tmdbRating || '';
    const year = data.releaseYear || '';
    const duration = formatDuration(data.duration);
    const type = data.type === 'movie' ? 'Movie' : 'TV Show';

    let metaHtml = '';
  const ratingVal = rating ? parseFloat(rating) : 0;

    if (ratingVal > 0) metaHtml += `<span style="color:${ratingColor(ratingVal)};font-weight:600;"><i class="fa-solid fa-star" style="font-size:0.6rem;"></i> ${ratingVal.toFixed(1)}</span>`;
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

    document.getElementById('modal-play-btn').onclick = () => playVideo(data);
    document.getElementById('modal-watchlist-btn').onclick = () => toggleWatchlist(videoId);
    const dlBtn = document.getElementById('modal-download-btn');
    if (dlBtn) {
      dlBtn.onclick = () => downloadVideo(data.id);
      const isMovie = !data.type || data.type === 'movie';
      dlBtn.style.display = isMovie ? '' : 'none';
    }

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

    // Featurettes, Extras & TV Shows section (for movies with bonus content) - renders
    // the same native season-grouped episodes section as TV series: fetched
    // from the episodes endpoint, sorted, and auto-enriched.
    if (data.type === 'movie' && data.seriesTitle) {
      renderCinemaEpisodesSection(data.seriesTitle, 'Featurettes, Extras & TV Shows');
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
          if (gen !== _modalGeneration) { clearInterval(modal._seriesPollInterval); return; }
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
        } catch (e) { console.debug('[cinema] modal metadata poll failed', e); }
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

var previousFocusElement = null;
var focusTrapHandler = null;

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
  if (window.location.pathname.indexOf('/video/info/') === 0) _resetUrlToBrowse();
  _episodeById.clear();
  // Clear series data polling
  const modal = document.getElementById('cinema-modal');
  if (!modal) { console.warn('[cinema] cinema-modal not found'); return; }
  if (modal._seriesPollInterval) { clearInterval(modal._seriesPollInterval); modal._seriesPollInterval = null; }
  if (modal._backdropRetry) { clearTimeout(modal._backdropRetry); modal._backdropRetry = null; }
  if (modal._logoRetry) { clearTimeout(modal._logoRetry); modal._logoRetry = null; }
  delete modal.dataset.seriesTitle;
  delete modal.dataset.catalogueTmdbId;

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
  const dlBtnClose = document.getElementById('modal-download-btn');
  if (playBtn) playBtn.style.display = '';
  if (watchBtn) watchBtn.style.display = '';
  if (dlBtnClose) { dlBtnClose.style.display = ''; dlBtnClose.onclick = null; }
  _hideRequestButton();
  document.getElementById('cinema-modal-backdrop').classList.remove('active');
  document.getElementById('cinema-modal').classList.remove('active');
  document.body.style.overflow = '';
  // Portal: restore modal to original parent
  const originalParent = document.getElementById(modal.dataset.originalParent) || document.getElementById('app-content');
  if (originalParent) originalParent.appendChild(modal);

  // Reset browser tab chrome to the video home, unless a player is still open
  const playerModal = document.getElementById('player-modal');
  const playerActive = playerModal && playerModal.classList.contains('active');
  if (!playerActive && window.JMedia && window.JMedia.PageChrome) {
    window.JMedia.PageChrome.setVideoHome();
  }
}

