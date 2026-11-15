// Cinema view: Live channel cards and playback, collection cards and the collections grid.
// Split out of cinema-mode.js. Loaded by views/video.html in a fixed order;
// these are classic scripts, so they share one global scope.
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
    <div class="cinema-card" data-video-id="${id}" data-type="live-channel" data-title="${escapeHtml(name)}" onclick="playChannel(${id})">
      <div class="cinema-card-poster">
        <img src="${escapeHtml(logo || '/logo.png')}" alt="${escapeHtml(name)}" loading="lazy" onerror="this.src='/logo.png'">
        <div class="cinema-card-play-overlay">
          <div class="cinema-card-play-icon">
            <i class="fa-solid fa-play"></i>
          </div>
        </div>
      </div>
      <div class="cinema-card-title">${escapeHtml(name)}</div>
      <div class="cinema-card-meta"><i class="fa-solid fa-tower-broadcast" style="font-size:0.65rem;margin-right:2px;"></i> ${num ? `CH ${escapeHtml(num)}` : escapeHtml(group)}</div>
    </div>
  `;
}

async function playChannel(channelId) {
  // Use existing player modal + live channel playback fragment
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
  modal.classList.add('is-expanded');
  document.body.style.overflow = 'hidden';
  setPlayerViewActive(true);
  splashShow(true);

  // Set title
  const titleEl = document.getElementById('player-modal-title');
  if (titleEl) titleEl.textContent = 'Live TV';

  try {
    const resp = await fetch(`/api/video/ui/live-channel-playback-fragment?channelId=${channelId}`);
    if (!isCurrentOpen()) return;
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const html = await resp.text();
    if (!isCurrentOpen()) return;

    const tmp = document.createElement('div');
    tmp.innerHTML = html;

    const scripts = Array.from(tmp.querySelectorAll('script'));
    scripts.forEach(s => s.remove());

    content.querySelectorAll('video').forEach(function(v) {
      try { v.pause(); } catch (e) { console.warn('[cinema] video pause failed during teardown', e); }
      try { v.removeAttribute('src'); } catch (e) { console.warn('[cinema] video src removal failed during teardown', e); }
      try { v.load(); } catch (e) { console.warn('[cinema] video load failed during teardown', e); }
    });
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
    initEpisodeSidebar();
    initSubtitleSearchSidebar();
    splashHide();
  } catch (err) {
    if (!isCurrentOpen()) return;
    console.error('[cinema] failed to load channel playback', err);
    content.innerHTML = '<div style="display:flex;align-items:center;justify-content:center;height:100%;color:rgba(255,255,255,0.5);">Failed to load channel</div>';
    splashHide();
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
    <div class="cinema-card" data-video-id="" data-type="collection" data-title="${escapeHtml(name)}" onclick="openCollectionDetail(${id})">
      <div class="cinema-card-poster">
        <img src="${escapeHtml(imgSrc || '/logo.png')}" alt="${escapeHtml(name)}" loading="lazy" onerror="this.src='/logo.png'">
        <div class="cinema-card-play-overlay">
          <div class="cinema-card-play-icon">
            <i class="fa-solid fa-eye"></i>
          </div>
        </div>
      </div>
      <div class="cinema-card-title">${escapeHtml(name)}</div>
      <div class="cinema-card-meta"><i class="fa-solid fa-layer-group" style="font-size:0.65rem;margin-right:2px;"></i> ${escapeHtml(desc ? desc.substring(0, 60) + (desc.length > 60 ? '…' : '') : 'Collection')}</div>
    </div>
  `;
}



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

  const viewGen = ++_dynamicViewGen;
  try {
    const [collectionResp, entriesResp] = await Promise.all([
      fetch('/api/collections/' + collectionId),
      fetch('/api/collections/' + collectionId + '/entries')
    ]);

    if (viewGen !== _dynamicViewGen) return;
    if (!collectionResp.ok || !entriesResp.ok) throw new Error('Failed to fetch');

    const collectionData = await collectionResp.json();
    const entriesData = await entriesResp.json();
    if (viewGen !== _dynamicViewGen) return;

    // Parse ApiResponse wrappers
    const collection = (collectionData && collectionData.success ? collectionData.data : collectionData);
    const entries = (entriesData && entriesData.success ? entriesData.data : entriesData);

    let html = '';
    html += '<div style="padding:0 0 0.5rem 0;">';
    html += '<button class="button is-ghost" onclick="showCollectionsGrid()" style="color:rgba(255,255,255,0.6);font-size:0.85rem;"><i class="fa-solid fa-arrow-left"></i> Back to Collections</button>';
    html += '</div>';
    html += '<section class="cinema-section" style="display:block;margin-bottom:1.5rem;">';
    html += '<div class="cinema-section-header">';
    html += '<h2 class="cinema-section-title">' + escapeHtml(collection.name || 'Collection') + '</h2>';
    if (collection.description) html += '<p style="color:rgba(255,255,255,0.5);margin:0.25rem 0 0 0;">' + escapeHtml(collection.description) + '</p>';
    html += '</div>';

    const items = Array.isArray(entries) ? entries : [];
    if (items.length === 0) {
      html += '<div style="text-align:center;padding:3rem;color:rgba(255,255,255,0.3);">This collection is empty.</div>';
    } else {
      html += '<div class="collection-grid">';
      for (let i = 0; i < items.length; i++) {
        const entry = items[i];
        const videoId = entry.videoId || entry.externalVideoId;
        const title = entry.title || 'Untitled';
        const imgSrc = videoId ? `/api/video/thumbnail/${videoId}` : '';
        const notes = entry.notes || '';
        const orderNum = i + 1;
        const progress = entry.watchProgress || 0;
        const watched = entry.watched;
        const statusClass = watched ? 'status-watched' : (progress > 0 ? 'status-partial' : '');
        const ratingValue = parseFloat(entry.imdbRating || entry.tmdbRating || '') || 0;
        const ratingText = ratingValue > 0 ? ratingValue.toFixed(1) : '';
        const starColor = ratingText ? ratingColor(ratingValue) : '';
        const year = entry.releaseYear || '';
        html += '<div class="cinema-card collection-card ' + statusClass + '" data-video-id="' + videoId + '" data-collection-id="' + collectionId + '" data-click="openDetailsFromCard">';
        html += '  <div class="cinema-card-poster">';
        html += '    <span class="card-order-badge">' + orderNum + '</span>';
        if (statusClass) {
          html += '    <span class="card-progress-indicator ' + statusClass + '"></span>';
        }
        html += '    <img src="' + (imgSrc ? withArtWidth(imgSrc, 400) : '') + '" srcset="' + (imgSrc ? artSrcset(imgSrc) : '') + '" sizes="(min-width: 768px) 176px, 144px" alt="' + title + '" loading="lazy" ' + artFallbackAttr() + '>';
        html += '    <div class="cinema-card-play-overlay"><div class="cinema-card-play-icon"><i class="fa-solid fa-play"></i></div></div>';
        html += '  </div>';
        html += '  <div class="cinema-card-title">' + title + '</div>';
        html += '  <div class="cinema-card-meta">';
        html += '    <span class="cinema-card-meta-text"><i class="fa-solid fa-star" style="font-size: 0.7rem;'+(starColor ? ' color: '+starColor+';' : '')+'"></i> <span style="color:'+(starColor || 'inherit')+'">'+ratingText+'</span> '+( year ? '· ' + year : '')+'</span>';
        html += '  </div>';
        html += '</div>';
      }
      html += '</div>';
    }
    html += '</section>';

    container.innerHTML = html;
  } catch(e) {
    console.error('[cinema] Failed to load collection', e);
    if (viewGen !== _dynamicViewGen) return;
    container.innerHTML = '<div style="padding:0 0 0.5rem 0;"><button class="button is-ghost" onclick="showCollectionsGrid()" style="color:rgba(255,255,255,0.6);font-size:0.85rem;"><i class="fa-solid fa-arrow-left"></i> Back to Collections</button></div><div style="text-align:center;padding:3rem;color:rgba(255,255,255,0.5);">Error loading collection.</div>';
  }
}

async function showCollectionsGrid() {
  const viewGen = ++_dynamicViewGen;
  const container = document.getElementById('dynamic-content');
  container.innerHTML = '<div style="text-align:center;padding:3rem;color:rgba(255,255,255,0.5);"><i class="fa-solid fa-spinner fa-spin" style="font-size:2rem;"></i></div>';
  await renderCollectionsSection(container, viewGen);
}

