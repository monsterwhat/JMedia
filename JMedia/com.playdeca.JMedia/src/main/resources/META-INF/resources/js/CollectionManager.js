(function(window) {
    'use strict';

    window.CollectionManager = class {
        constructor(videoSPA) {
            this.videoSPA = videoSPA;
            this._editingEntryId = null;

            window.showCreateCollectionModal = () => this.showCreateCollectionModal();
            window.submitCreateCollection = () => this.submitCreateCollection();
            window.showRenameCollectionModal = () => this.showRenameCollectionModal();
            window.submitRenameCollection = () => this.submitRenameCollection();
            window.deleteCollection = (id, name) => this.deleteCollection(id, name);
            window.toggleGroup = (header) => this.toggleGroup(header);
            window.tvShowSelectSeries = (index) => this.tvShowSelectSeries(index);
            window.tvShowBackToSeries = () => this.tvShowBackToSeries();
            window.tvShowSelectSeason = (seriesIndex, seasonIndex) => this.tvShowSelectSeason(seriesIndex, seasonIndex);
            window.tvShowBackToSeasons = (seriesIndex) => this.tvShowBackToSeasons(seriesIndex);
            window.filterAddVideoList = (query) => this.filterAddVideoList(query);
            window.filterCollectionEntries = (query) => this.filterCollectionEntries(query);
            window.showAddEntryModal = () => this.showAddEntryModal();
            window.updateCardToAdded = (card, entryId) => this.updateCardToAdded(card, entryId);
            window.updateCardToRemoved = (card) => this.updateCardToRemoved(card);
            window.buildEntryHtml = (mediaId, entryId, orderIndex, card, mediaType, collectionId) => this.buildEntryHtml(mediaId, entryId, orderIndex, card, mediaType, collectionId);
            window.addEntryToCollectionList = (mediaId, entryId, mediaType) => this.addEntryToCollectionList(mediaId, entryId, mediaType);
            window.removeEntryFromCollectionList = (entryId) => this.removeEntryFromCollectionList(entryId);
            window.addEntry = (videoId) => this.addEntry(videoId);
            window.addEntryByExternal = (externalVideoId) => this.addEntryByExternal(externalVideoId);
            window.removeEntry = (entryId) => this.removeEntry(entryId);
            window.playCollection = (collectionId, startIndex) => this.playCollection(collectionId, startIndex);
            window.playNextEntry = (entryId) => this.playNextEntry(entryId);
            window.addEntryToQueue = (entryId) => this.addEntryToQueue(entryId);
            window.toggleCollectionEntry = (mediaId, entryId, inCollection, mediaType) => this.toggleCollectionEntry(mediaId, entryId, inCollection, mediaType);
            window.batchAddEpisodes = (seriesIndex, seasonIndex) => this.batchAddEpisodes(seriesIndex, seasonIndex);
            window.batchRemoveEpisodes = (seriesIndex, seasonIndex) => this.batchRemoveEpisodes(seriesIndex, seasonIndex);
            window.showEditEntryModal = (entryId, notes) => this.showEditEntryModal(entryId, notes);
            window.submitEditEntry = () => this.submitEditEntry();
            window.initCollectionDragDrop = () => this.initCollectionDragDrop();
            window.updateEntryOrders = () => this.updateEntryOrders();
        }

        showCreateCollectionModal() {
            const nameInput = document.getElementById('collectionNameInput');
            const descInput = document.getElementById('collectionDescInput');
            if (nameInput) nameInput.value = '';
            if (descInput) descInput.value = '';
            const modal = document.getElementById('createCollectionModal');
            if (modal) modal.classList.add('is-active');
        }

        async submitCreateCollection() {
            const name = document.getElementById('collectionNameInput')?.value?.trim();
            if (!name) { if (window.showToast) window.showToast('Name is required', 'warning'); return; }
            const desc = document.getElementById('collectionDescInput')?.value?.trim() || '';
            const isPublic = document.getElementById('collectionIsPublicInput')?.checked;
            try {
                const params = new URLSearchParams();
                params.set('name', name);
                if (desc) params.set('description', desc);
                if (isPublic !== undefined) params.set('isPublic', String(isPublic));
                const res = await fetch('/api/collections', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: params.toString()
                });
                const json = await res.json();
                if (json.success) {
                    const modal = document.getElementById('createCollectionModal');
                    if (modal) modal.classList.remove('is-active');
                    if (window.showToast) window.showToast('Collection created', 'success');
                    window.switchSection('collections');
                } else {
                    if (window.showToast) window.showToast(json.error || 'Failed to create', 'danger');
                }
            } catch (e) {
                if (window.showToast) window.showToast('Error creating collection', 'danger');
            }
        }

        showRenameCollectionModal() {
            const nameInput = document.getElementById('renameCollectionNameInput');
            const descInput = document.getElementById('renameCollectionDescInput');
            const collectionName = document.querySelector('.library-title')?.textContent?.trim() || '';
            if (nameInput) nameInput.value = collectionName;
            if (descInput) descInput.value = '';
            const modal = document.getElementById('renameCollectionModal');
            if (modal) modal.classList.add('is-active');
        }

        async submitRenameCollection() {
            const name = document.getElementById('renameCollectionNameInput')?.value?.trim();
            if (!name) { if (window.showToast) window.showToast('Name is required', 'warning'); return; }
            const desc = document.getElementById('renameCollectionDescInput')?.value?.trim() || '';
            const collectionId = this.videoSPA?.currentParams?.collectionId;
            if (!collectionId) { if (window.showToast) window.showToast('No collection selected', 'warning'); return; }
            try {
                const params = new URLSearchParams();
                params.set('name', name);
                params.set('description', desc);
                const res = await fetch('/api/collections/' + collectionId, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: params.toString()
                });
                const json = await res.json();
                if (json.success) {
                    const modal = document.getElementById('renameCollectionModal');
                    if (modal) modal.classList.remove('is-active');
                    if (window.showToast) window.showToast('Collection renamed', 'success');
                    window.switchSection('collectionEntries', {collectionId: collectionId});
                } else {
                    if (window.showToast) window.showToast(json.error || 'Failed to rename', 'danger');
                }
            } catch (e) {
                if (window.showToast) window.showToast('Error renaming collection', 'danger');
            }
        }

        async deleteCollection(id, name) {
            if (!confirm('Delete collection "' + name + '"? This cannot be undone.')) return;
            try {
                const res = await fetch('/api/collections/' + id, { method: 'DELETE' });
                const json = await res.json();
                if (json.success) {
                    if (window.showToast) window.showToast('Collection deleted', 'success');
                    window.switchSection('collections');
                } else {
                    if (window.showToast) window.showToast(json.error || 'Failed to delete', 'danger');
                }
            } catch (e) {
                if (window.showToast) window.showToast('Error deleting collection', 'danger');
            }
        }

        toggleGroup(header) {
            const body = header.nextElementSibling;
            const icon = header.querySelector('.pi-chevron-down');
            if (body) {
                body.classList.toggle('collapsed');
                if (icon) icon.style.transform = body.classList.contains('collapsed') ? 'rotate(-90deg)' : 'rotate(0deg)';
            }
        }

        tvShowSelectSeries(index) {
            document.getElementById('tvSeriesGrid').classList.add('is-hidden');
            document.querySelectorAll('.tv-season-list').forEach(el => el.classList.add('is-hidden'));
            const target = document.querySelector('.tv-season-list[data-series-index="' + index + '"]');
            if (target) target.classList.remove('is-hidden');
        }

        tvShowBackToSeries() {
            document.getElementById('tvSeriesGrid').classList.remove('is-hidden');
            document.querySelectorAll('.tv-season-list').forEach(el => el.classList.add('is-hidden'));
            document.querySelectorAll('.tv-episode-grid').forEach(el => el.classList.add('is-hidden'));
        }

        tvShowSelectSeason(seriesIndex, seasonIndex) {
            document.querySelectorAll('.tv-season-list').forEach(el => el.classList.add('is-hidden'));
            document.querySelectorAll('.tv-episode-grid').forEach(el => el.classList.add('is-hidden'));
            const target = document.querySelector('.tv-episode-grid[data-series-index="' + seriesIndex + '"][data-season-index="' + seasonIndex + '"]');
            if (target) target.classList.remove('is-hidden');
        }

        tvShowBackToSeasons(seriesIndex) {
            document.querySelectorAll('.tv-episode-grid').forEach(el => el.classList.add('is-hidden'));
            const target = document.querySelector('.tv-season-list[data-series-index="' + seriesIndex + '"]');
            if (target) target.classList.remove('is-hidden');
        }

        filterAddVideoList(query) {
            const q = query.toLowerCase().trim();
            document.querySelectorAll('.add-card').forEach(el => {
                const searchData = el.getAttribute('data-search') || '';
                el.style.display = (!q || searchData.includes(q)) ? '' : 'none';
            });
            document.querySelectorAll('.tv-episode-grid').forEach(grid => {
                if (!q) { grid.classList.add('is-hidden'); return; }
                const hasVisible = [...grid.querySelectorAll('.add-card')].some(el => el.style.display !== 'none');
                grid.classList.toggle('is-hidden', !hasVisible);
            });
            if (!q) {
                document.querySelectorAll('.tv-season-list').forEach(el => el.classList.add('is-hidden'));
                document.getElementById('tvSeriesGrid').classList.remove('is-hidden');
            }
        }

        showAddEntryModal() {
            const input = document.getElementById('addVideoSearchInput');
            if (input) input.value = '';
            document.querySelectorAll('.add-card').forEach(el => el.style.display = '');
            document.querySelectorAll('.tv-episode-grid').forEach(el => el.classList.add('is-hidden'));
            document.querySelectorAll('.tv-season-list').forEach(el => el.classList.add('is-hidden'));
            document.getElementById('tvSeriesGrid').classList.remove('is-hidden');
            document.querySelectorAll('.video-group-body').forEach(body => body.classList.remove('collapsed'));
            const search = document.getElementById('addVideoSearchInput');
            if (search) search.scrollIntoView({ behavior: 'smooth' });
        }

        filterCollectionEntries(query) {
            const q = query.toLowerCase().trim();
            document.querySelectorAll('.collection-entry').forEach(el => {
                const titleEl = el.querySelector('.entry-title-text');
                const text = titleEl ? titleEl.textContent.toLowerCase() : '';
                el.style.display = (!q || text.includes(q)) ? '' : 'none';
            });
            const container = document.getElementById('sortableEntries');
            if (container) {
                const visible = [...container.querySelectorAll('.collection-entry')].some(el => el.style.display !== 'none');
                let noResult = container.querySelector('.collection-filter-empty');
                if (!q || visible) {
                    if (noResult) noResult.remove();
                } else {
                    if (!noResult) {
                        noResult = document.createElement('div');
                        noResult.className = 'carousel-empty-state collection-filter-empty';
                        noResult.style.padding = '40px 0';
                        noResult.innerHTML = '<i class="pi pi-search"></i><h3>No matching entries</h3><p>Try a different search term.</p>';
                        container.appendChild(noResult);
                    }
                }
            }
        }

        updateCardToAdded(card, entryId) {
            if (!card) return;
            card.setAttribute('data-in-collection', 'true');
            card.setAttribute('data-entry-id', entryId);
            const overlay = card.querySelector('.standard-card-overlay');
            if (overlay) {
                const btn = overlay.querySelector('.standard-play-btn');
                if (btn) {
                    btn.style.background = '#e74c3c';
                    btn.innerHTML = '<i class="pi pi-times"></i>';
                    btn.onclick = function(e) {
                        e.stopPropagation();
                        window.removeEntry(entryId);
                    };
                }
            }
            const info = card.querySelector('.standard-card-info');
            if (info) {
                const titleDiv = info.querySelector('.standard-card-title');
                if (titleDiv && !titleDiv.querySelector('.tag.is-warning')) {
                    titleDiv.insertAdjacentHTML('beforeend', ' <span class="tag is-warning is-light is-small">In Collection</span>');
                }
            }
        }

        updateCardToRemoved(card) {
            if (!card) return;
            card.setAttribute('data-in-collection', 'false');
            card.removeAttribute('data-entry-id');
            const overlay = card.querySelector('.standard-card-overlay');
            if (overlay) {
                const btn = overlay.querySelector('.standard-play-btn');
                if (btn) {
                    const mediaType = card.getAttribute('data-media-type');
                    if (mediaType === 'external') {
                        const extId = card.getAttribute('data-media-id');
                        btn.style.background = '';
                        btn.innerHTML = '<i class="pi pi-plus"></i>';
                        btn.onclick = function(e) {
                            e.stopPropagation();
                            window.addEntryByExternal(extId);
                        };
                    } else {
                        const videoId = card.getAttribute('data-media-id');
                        btn.style.background = '';
                        btn.innerHTML = '<i class="pi pi-plus"></i>';
                        btn.onclick = function(e) {
                            e.stopPropagation();
                            window.addEntry(videoId);
                        };
                    }
                }
            }
            const info = card.querySelector('.standard-card-info');
            if (info) {
                const titleDiv = info.querySelector('.standard-card-title');
                if (titleDiv) {
                    const tag = titleDiv.querySelector('.tag.is-warning');
                    if (tag) tag.remove();
                }
            }
        }

        buildEntryHtml(mediaId, entryId, orderIndex, card, mediaType, collectionId) {
            let title = '';
            let metaHtml = '';
            let thumbHtml = '';
            let playOnclick = '';
            if (card) {
                const titleEl = card.querySelector('.standard-card-title');
                if (titleEl) {
                    const clone = titleEl.cloneNode(true);
                    const tag = clone.querySelector('.tag');
                    if (tag) tag.remove();
                    title = clone.textContent.trim();
                }
                const metaEl = card.querySelector('.standard-card-meta');
                if (metaEl) {
                    metaHtml = metaEl.textContent.trim();
                }
                if (mediaType === 'external') {
                    thumbHtml = '<div class="entry-thumbnail-placeholder" style="height:100%;display:flex;align-items:center;justify-content:center;background:linear-gradient(135deg,rgba(255,255,255,0.1),rgba(255,255,255,0.02));color:rgba(255,255,255,0.5);"><span style="font-size:1.5rem;font-weight:700;">' + (title.charAt(0) || '?') + '</span></div>';
                    playOnclick = 'event.stopPropagation(); window.selectExternalVideo(' + mediaId + ')';
                } else {
                    thumbHtml = '<img src="/api/video/thumbnail/' + mediaId + '" alt="' + title.replace(/"/g, '&quot;') + '" loading="lazy">';
                    playOnclick = 'window.selectItem(' + mediaId + ', \'details\')';
                }
            } else {
                if (mediaType === 'external') {
                    thumbHtml = '<div class="entry-thumbnail-placeholder" style="height:100%;display:flex;align-items:center;justify-content:center;background:linear-gradient(135deg,rgba(255,255,255,0.1),rgba(255,255,255,0.02));color:rgba(255,255,255,0.5);"><span style="font-size:1.5rem;font-weight:700;">?</span></div>';
                    playOnclick = 'event.stopPropagation(); window.selectExternalVideo(' + mediaId + ')';
                } else {
                    thumbHtml = '<img src="/api/video/thumbnail/' + mediaId + '" alt="" loading="lazy">';
                    playOnclick = 'window.selectItem(' + mediaId + ', \'details\')';
                }
            }
            const escapedTitle = title.replace(/"/g, '&quot;');
            const mediaTypeAttr = mediaType === 'external' ? 'external' : 'video';

            if (mediaType === 'external') {
                return '<div class="collection-entry" data-entry-id="' + entryId + '" data-media-type="external">' +
                    '<div class="entry-order-handle" title="Drag to reorder"><i class="pi pi-bars"></i></div>' +
                    '<div class="entry-order-badge">' + orderIndex + '</div>' +
                    '<div class="entry-thumbnail" onclick="' + playOnclick + '">' + thumbHtml + '</div>' +
                    '<div class="entry-info" onclick="' + playOnclick + '">' +
                    '<div class="entry-title entry-title-text">' + escapedTitle + '</div>' +
                    '<div class="entry-meta">' + (metaHtml || 'External') + '</div>' +
                    '</div>' +
                    '<div class="entry-actions">' +
                    '<button class="button is-small is-rounded is-success" onclick="event.stopPropagation(); window.selectExternalVideo(' + mediaId + ')" title="Play"><i class="pi pi-play"></i></button>' +
                    '<button class="button is-small is-rounded" onclick="event.stopPropagation(); showEditEntryModal(' + entryId + ', \'\')" title="Edit"><i class="pi pi-pencil"></i></button>' +
                    '<button class="button is-small is-rounded is-danger" onclick="event.stopPropagation(); window.removeEntry(' + entryId + ')" title="Remove"><i class="pi pi-times"></i></button>' +
                    '</div></div>';
            } else {
                return '<div class="collection-entry" data-entry-id="' + entryId + '" data-media-type="video">' +
                    '<div class="entry-order-handle" title="Drag to reorder"><i class="pi pi-bars"></i></div>' +
                    '<div class="entry-order-badge">' + orderIndex + '</div>' +
                    '<div class="entry-thumbnail" onclick="' + playOnclick + '">' + thumbHtml + '</div>' +
                    '<div class="entry-info" onclick="' + playOnclick + '">' +
                    '<div class="entry-title entry-title-text">' + escapedTitle + '</div>' +
                    '<div class="entry-meta">' + metaHtml + '</div>' +
                    '</div>' +
                    '<div class="entry-actions">' +
                    '<button class="button is-small is-rounded is-success" onclick="event.stopPropagation(); window.selectItem(' + mediaId + ', \'play\', {collectionId: ' + (collectionId || 'null') + ', entryId: ' + entryId + '})" title="Play"><i class="pi pi-play"></i></button>' +
                    '<button class="button is-small is-rounded" onclick="event.stopPropagation(); window.playNextEntry(' + entryId + ')" title="Play Next"><i class="pi pi-forward"></i></button>' +
                    '<button class="button is-small is-rounded" onclick="event.stopPropagation(); window.addEntryToQueue(' + entryId + ')" title="Add to Queue"><i class="pi pi-plus"></i></button>' +
                    '<button class="button is-small is-rounded" onclick="event.stopPropagation(); showEditEntryModal(' + entryId + ', \'\')" title="Edit"><i class="pi pi-pencil"></i></button>' +
                    '<button class="button is-small is-rounded is-danger" onclick="event.stopPropagation(); window.removeEntry(' + entryId + ')" title="Remove"><i class="pi pi-times"></i></button>' +
                    '</div></div>';
            }
        }

        addEntryToCollectionList(mediaId, entryId, mediaType) {
            const entriesContainer = document.getElementById('collectionEntriesList');
            if (!entriesContainer) return;
            const existingEntries = entriesContainer.querySelectorAll('.collection-entry');
            let nextOrder = 1;
            if (existingEntries.length > 0) {
                const lastBadge = existingEntries[existingEntries.length - 1]?.querySelector('.entry-order-badge');
                const lastOrder = parseInt(lastBadge?.textContent) || 0;
                nextOrder = lastOrder + 1;
            }
            const selector = mediaType === 'external'
                ? '.add-card[data-media-type="external"][data-media-id="' + mediaId + '"]'
                : '.add-card[data-media-type="video"][data-media-id="' + mediaId + '"]';
            const card = document.querySelector(selector);
            const html = this.buildEntryHtml(mediaId, entryId, nextOrder, card, mediaType || 'video');
            const emptyState = entriesContainer.querySelector('.carousel-empty-state');
            if (emptyState) {
                emptyState.outerHTML = '<div class="collection-entries" id="sortableEntries">' + html + '</div>';
            } else {
                let sortableList = document.getElementById('sortableEntries');
                if (sortableList) {
                    sortableList.insertAdjacentHTML('beforeend', html);
                } else {
                    entriesContainer.innerHTML = '<div class="collection-entries" id="sortableEntries">' + html + '</div>';
                }
            }
            window.initCollectionDragDrop();
        }

        removeEntryFromCollectionList(entryId) {
            const entry = document.querySelector('.collection-entry[data-entry-id="' + entryId + '"]');
            if (entry) {
                entry.remove();
                const entries = document.querySelectorAll('.collection-entry');
                if (entries.length === 0) {
                    const container = document.getElementById('collectionEntriesList');
                    if (container) {
                        container.innerHTML = '<div class="carousel-empty-state">' +
                            '<i class="pi pi-th-large"></i>' +
                            '<h3>Collection is empty</h3>' +
                            '<p>Click "Add Video" to start building your watch order.</p>' +
                            '</div>';
                    }
                    return;
                }
                entries.forEach((el, idx) => {
                    const badge = el.querySelector('.entry-order-badge');
                    if (badge) badge.textContent = (idx + 1).toString();
                });
                window.initCollectionDragDrop();
            }
        }

        async addEntry(videoId) {
            const collectionId = this.videoSPA?.currentParams?.collectionId;
            if (!collectionId) return;
            const entries = document.querySelectorAll('.collection-entry');
            let nextOrder = 1;
            if (entries.length > 0) {
                const lastBadge = entries[entries.length - 1]?.querySelector('.entry-order-badge');
                const lastOrder = parseInt(lastBadge?.textContent) || 0;
                nextOrder = lastOrder + 1;
            }
            try {
                const res = await fetch(`/api/collections/${collectionId}/entries`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: 'videoId=' + videoId + '&orderIndex=' + nextOrder
                });
                const json = await res.json();
                if (json.success) {
                    const entryId = json.data?.id;
                    const card = document.querySelector('.add-card[data-media-type="video"][data-media-id="' + videoId + '"]');
                    if (card) {
                        this.updateCardToAdded(card, entryId);
                    }
                    this.addEntryToCollectionList(videoId, entryId, 'video');
                    if (window.showToast) window.showToast('Added to collection', 'success');
                } else {
                    if (window.showToast) window.showToast(json.error || 'Failed to add', 'danger');
                }
            } catch (e) {
                if (window.showToast) window.showToast('Error adding entry', 'danger');
            }
        }

        async addEntryByExternal(externalVideoId) {
            const collectionId = this.videoSPA?.currentParams?.collectionId;
            if (!collectionId) return;
            const entries = document.querySelectorAll('.collection-entry');
            let nextOrder = 1;
            if (entries.length > 0) {
                const lastBadge = entries[entries.length - 1]?.querySelector('.entry-order-badge');
                const lastOrder = parseInt(lastBadge?.textContent) || 0;
                nextOrder = lastOrder + 1;
            }
            try {
                const res = await fetch(`/api/collections/${collectionId}/entries`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: 'externalVideoId=' + externalVideoId + '&orderIndex=' + nextOrder
                });
                const json = await res.json();
                if (json.success) {
                    const entryId = json.data?.id;
                    const card = document.querySelector('.add-card[data-media-type="external"][data-media-id="' + externalVideoId + '"]');
                    if (card) {
                        this.updateCardToAdded(card, entryId);
                    }
                    this.addEntryToCollectionList(externalVideoId, entryId, 'external');
                    if (window.showToast) window.showToast('Added to collection', 'success');
                } else {
                    if (window.showToast) window.showToast(json.error || 'Failed to add', 'danger');
                }
            } catch (e) {
                if (window.showToast) window.showToast('Error adding entry', 'danger');
            }
        }

        async removeEntry(entryId) {
            const collectionId = this.videoSPA?.currentParams?.collectionId;
            if (!collectionId) return;
            try {
                const res = await fetch('/api/collections/entries/' + entryId, { method: 'DELETE' });
                const json = await res.json();
                if (json.success) {
                    const card = document.querySelector('.add-card[data-entry-id="' + entryId + '"]');
                    if (card) {
                        this.updateCardToRemoved(card);
                    }
                    this.removeEntryFromCollectionList(entryId);
                    if (window.showToast) window.showToast('Entry removed', 'success');
                } else {
                    if (window.showToast) window.showToast(json.error || 'Failed to remove', 'danger');
                }
            } catch (e) {
                if (window.showToast) window.showToast('Error removing entry', 'danger');
            }
        }

        async playNextEntry(entryId) {
            const collectionId = this.videoSPA?.currentParams?.collectionId;
            if (!collectionId) return;
            try {
                const res = await fetch(`/api/collections/${collectionId}/entries/${entryId}/play-next`, { method: 'POST' });
                const json = await res.json();
                if (json.success) {
                    if (window.showToast) window.showToast('Added to play next', 'success');
                } else {
                    if (window.showToast) window.showToast(json.error || 'Failed to queue', 'danger');
                }
            } catch (e) {
                if (window.showToast) window.showToast('Error queuing entry', 'danger');
            }
        }

        async addEntryToQueue(entryId) {
            const collectionId = this.videoSPA?.currentParams?.collectionId;
            if (!collectionId) return;
            try {
                const res = await fetch(`/api/collections/${collectionId}/entries/${entryId}/add-to-queue`, { method: 'POST' });
                const json = await res.json();
                if (json.success) {
                    if (window.showToast) window.showToast('Added to queue', 'success');
                } else {
                    if (window.showToast) window.showToast(json.error || 'Failed to queue', 'danger');
                }
            } catch (e) {
                if (window.showToast) window.showToast('Error queuing entry', 'danger');
            }
        }

        async playCollection(collectionId, startIndex) {
            if (startIndex === undefined) startIndex = 0;
            this.videoSPA.showLoading();
            try {
                const res = await fetch('/api/collections/' + collectionId + '/play', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: 'startIndex=' + startIndex
                });
                const json = await res.json();
                if (json.success && json.data && json.data.videoId) {
                    this.videoSPA.playVideo(json.data.videoId, {collectionId: collectionId, entryId: json.data.videoId});
                } else {
                    if (window.showToast) window.showToast(json.error || 'Failed to play collection', 'danger');
                    this.videoSPA.hideLoading();
                }
            } catch (e) {
                if (window.showToast) window.showToast('Error playing collection', 'danger');
                this.videoSPA.hideLoading();
            }
        }

        toggleCollectionEntry(mediaId, entryId, inCollection, mediaType) {
            if (inCollection && entryId) {
                window.removeEntry(entryId);
            } else if (!inCollection) {
                if (mediaType === 'external') {
                    window.addEntryByExternal(mediaId);
                } else {
                    window.addEntry(mediaId);
                }
            }
        }

        async batchAddEpisodes(seriesIndex, seasonIndex) {
            const collectionId = this.videoSPA?.currentParams?.collectionId;
            if (!collectionId) return;
            const container = document.querySelector('.tv-episode-grid[data-series-index="' + seriesIndex + '"][data-season-index="' + seasonIndex + '"]');
            if (!container) return;
            const entries = document.querySelectorAll('.collection-entry');
            let nextOrder = 1;
            if (entries.length > 0) {
                const lastBadge = entries[entries.length - 1]?.querySelector('.entry-order-badge');
                nextOrder = (parseInt(lastBadge?.textContent) || 0) + 1;
            }
            const cards = container.querySelectorAll('.add-card[data-in-collection="false"]');
            let added = 0;
            for (const card of cards) {
                const mediaType = card.getAttribute('data-media-type') || 'video';
                const mediaId = card.getAttribute('data-media-id');
                if (!mediaId) continue;
                try {
                    const body = mediaType === 'external'
                        ? 'externalVideoId=' + mediaId + '&orderIndex=' + (nextOrder + added)
                        : 'videoId=' + mediaId + '&orderIndex=' + (nextOrder + added);
                    const res = await fetch('/api/collections/' + collectionId + '/entries', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                        body: body
                    });
                    const json = await res.json();
                    if (json.success && json.data?.id) {
                        this.updateCardToAdded(card, json.data.id);
                        this.addEntryToCollectionList(mediaId, json.data.id, mediaType);
                        added++;
                    }
                } catch (e) {}
            }
            if (added > 0) {
                if (window.showToast) window.showToast('Added ' + added + ' episode' + (added > 1 ? 's' : ''), 'success');
            }
        }

        async batchRemoveEpisodes(seriesIndex, seasonIndex) {
            const collectionId = this.videoSPA?.currentParams?.collectionId;
            if (!collectionId) return;
            const container = document.querySelector('.tv-episode-grid[data-series-index="' + seriesIndex + '"][data-season-index="' + seasonIndex + '"]');
            if (!container) return;
            const cards = container.querySelectorAll('.add-card[data-in-collection="true"]');
            let removed = 0;
            for (const card of cards) {
                const entryId = card.getAttribute('data-entry-id');
                if (!entryId) continue;
                try {
                    const res = await fetch('/api/collections/entries/' + entryId, { method: 'DELETE' });
                    const json = await res.json();
                    if (json.success) {
                        this.updateCardToRemoved(card);
                        this.removeEntryFromCollectionList(entryId);
                        removed++;
                    }
                } catch (e) {}
            }
            if (removed > 0) {
                if (window.showToast) window.showToast('Removed ' + removed + ' episode' + (removed > 1 ? 's' : ''), 'success');
            }
        }

        showEditEntryModal(entryId, notes) {
            const input = document.getElementById('editEntryNotesInput');
            if (input) {
                input.value = notes || '';
                this._editingEntryId = entryId;
            }
            const modal = document.getElementById('editEntryModal');
            if (modal) modal.classList.add('is-active');
        }

        async submitEditEntry() {
            const entryId = this._editingEntryId;
            if (!entryId) return;
            const notes = document.getElementById('editEntryNotesInput')?.value?.trim() || '';
            try {
                const res = await fetch('/api/collections/entries/' + entryId, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: notes ? 'notes=' + encodeURIComponent(notes) : ''
                });
                const json = await res.json();
                if (json.success) {
                    const modal = document.getElementById('editEntryModal');
                    if (modal) modal.classList.remove('is-active');
                    const entryEl = document.querySelector('.collection-entry[data-entry-id="' + entryId + '"]');
                    if (entryEl) {
                        const metaEl = entryEl.querySelector('.entry-meta');
                        const existingTag = metaEl ? metaEl.querySelector('.tag.is-info.is-light.is-small') : null;
                        if (notes) {
                            if (existingTag) {
                                existingTag.textContent = notes;
                            } else if (metaEl) {
                                const tag = document.createElement('span');
                                tag.className = 'tag is-info is-light is-small ml-2';
                                tag.textContent = notes;
                                metaEl.appendChild(tag);
                            }
                        } else if (existingTag) {
                            existingTag.remove();
                        }
                    }
                    if (window.showToast) window.showToast('Entry updated', 'success');
                } else {
                    if (window.showToast) window.showToast(json.error || 'Failed to update', 'danger');
                }
            } catch (e) {
                if (window.showToast) window.showToast('Error updating entry', 'danger');
            }
        }

        initCollectionDragDrop() {
            const list = document.getElementById('sortableEntries');
            if (!list) return;
            let dragSrcEl = null;

            const onDragStart = function(e) {
                dragSrcEl = this;
                e.dataTransfer.effectAllowed = 'move';
                e.dataTransfer.setData('text/plain', this.getAttribute('data-entry-id'));
                this.classList.add('dragging');
            };
            const onDragEnter = function(e) {
                if (this !== dragSrcEl) this.classList.add('drag-over');
            };
            const onDragLeave = function(e) {
                this.classList.remove('drag-over');
            };
            const onDragOver = function(e) {
                e.preventDefault();
                e.dataTransfer.dropEffect = 'move';
            };
            const onDrop = function(e) {
                e.preventDefault();
                this.classList.remove('drag-over');
                if (dragSrcEl && this !== dragSrcEl) {
                    const parent = document.getElementById('sortableEntries');
                    const items = Array.from(parent.querySelectorAll('.collection-entry'));
                    const fromIdx = items.indexOf(dragSrcEl);
                    const toIdx = items.indexOf(this);
                    if (fromIdx < toIdx) {
                        parent.insertBefore(dragSrcEl, this.nextSibling);
                    } else {
                        parent.insertBefore(dragSrcEl, this);
                    }
                    window.updateEntryOrders();
                }
            };
            const onDragEnd = function(e) {
                this.classList.remove('dragging');
                document.querySelectorAll('.collection-entry').forEach(el => el.classList.remove('drag-over'));
            };

            list.querySelectorAll('.collection-entry').forEach(el => {
                el.setAttribute('draggable', 'true');
                el.addEventListener('dragstart', onDragStart);
                el.addEventListener('dragenter', onDragEnter);
                el.addEventListener('dragleave', onDragLeave);
                el.addEventListener('dragover', onDragOver);
                el.addEventListener('drop', onDrop);
                el.addEventListener('dragend', onDragEnd);
            });
        }

        async updateEntryOrders() {
            const collectionId = this.videoSPA?.currentParams?.collectionId;
            if (!collectionId) return;
            const items = document.querySelectorAll('.collection-entry');
            const orderMap = {};
            items.forEach((el, idx) => {
                const entryId = el.getAttribute('data-entry-id');
                const newOrder = idx + 1;
                orderMap[entryId] = newOrder;
                const badge = el.querySelector('.entry-order-badge');
                if (badge) badge.textContent = newOrder;
            });
            try {
                await fetch(`/api/collections/${collectionId}/entries/reorder`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(orderMap)
                });
            } catch (e) {
                console.error('Failed to save reorder', e);
            }
        }
    };
})(window);
