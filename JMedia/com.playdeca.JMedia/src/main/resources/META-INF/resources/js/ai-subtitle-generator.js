// AI Subtitle Generator - Settings Tab Logic

window.aiSubState = {
    selectedIds: new Set(),
    currentPage: 0,
    currentSearch: '',
    currentFilter: 'all',
    aiCompletedPage: 0,
    pollingInterval: null,
    jobId: null,
    parakeetAvailable: false,
    view: 'list',           // 'list' or 'shows'
    currentShow: null,       // seriesTitle when browsing episodes
    currentEpisodes: [],     // current episode list for shift-click
    lastClickedIndex: -1     // for shift-click range selection
};

// ========== VIEW MODE SWITCHING ==========

window.switchAiView = function(view) {
    window.aiSubState.view = view;
    document.getElementById('viewListBtn').className = view === 'list' ? 'button is-info is-selected' : 'button';
    document.getElementById('viewShowsBtn').className = view === 'shows' ? 'button is-info is-selected' : 'button';
    window.aiSubState.currentShow = null;
    document.getElementById('aiShowEpisodesView').style.display = 'none';
    window.refreshCurrentAiView();
};

window.refreshCurrentAiView = function() {
    if (window.aiSubState.currentShow) {
        window.updateEpisodeTableUI();
    } else if (window.aiSubState.view === 'shows') {
        window.loadAiShows();
    } else {
        window.loadAiSubtitleVideos(0);
    }
};

// Debounced search helper
window.debouncedSearchAiVideos = function() {
    if (window._aiSearchTimer) clearTimeout(window._aiSearchTimer);
    window._aiSearchTimer = setTimeout(() => {
        window.refreshCurrentAiView();
    }, 400);
};

// ========== LIST VIEW (individual videos) ==========

window.loadAiSubtitleVideos = async function(page) {
    const search = (document.getElementById('aiSubSearch') || {}).value || '';
    const filter = (document.getElementById('aiSubFilter') || {}).value || 'all';
    window.aiSubState.currentPage = page;
    window.aiSubState.currentSearch = search;
    window.aiSubState.currentFilter = filter;

    const grid = document.getElementById('aiVideoGrid');
    if (!grid) return;
    grid.innerHTML = '<div class="has-text-centered p-4"><i class="pi pi-spin pi-spinner mr-2"></i> Loading videos...</div>';
    grid.style.display = 'grid';

    try {
        const res = await fetch(`/api/ai-subtitles/videos?page=${page}&limit=50&search=${encodeURIComponent(search)}&filter=${filter}`);
        const data = await res.json();
        window.updateParakeetStatus(data.parakeetAvailable);

        const totalSpan = document.getElementById('aiSubTotalCount');
        if (totalSpan) totalSpan.textContent = data.total + ' total';

        if (!data.videos || data.videos.length === 0) {
            grid.innerHTML = '<div class="has-text-centered p-4 has-text-grey">No videos found.</div>';
            window.updateGenerateButton();
            return;
        }

        grid.innerHTML = data.videos.map(v => {
            const selected = window.aiSubState.selectedIds.has(v.id);
            const aiBadge = v.hasAiSubtitles ? '<span class="tag is-info is-small is-light" style="position:absolute;top:4px;right:4px;font-size:0.6rem;">AI</span>' : '';
            return `<div class="ai-video-card ${selected ? 'is-success' : ''}" onclick="window.toggleAiVideoSelection(${v.id})" 
                        style="cursor:pointer;border:2px solid ${selected ? 'var(--standard-accent)' : 'transparent'};border-radius:8px;padding:6px;background:rgba(255,255,255,0.03);transition:0.2s;position:relative;">
                <div style="aspect-ratio:16/9;background:rgba(0,0,0,0.3);border-radius:4px;margin-bottom:4px;display:flex;align-items:center;justify-content:center;overflow:hidden;font-size:1.5rem;">
                    <i class="pi pi-video" style="opacity:0.3;"></i>
                </div>
                ${aiBadge}
                <div class="is-size-7" style="display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;line-height:1.2;">${v.title || v.filename}</div>
                <div class="is-size-7 has-text-grey">${v.type || 'unknown'}</div>
            </div>`;
        }).join('');

        window.updateGenerateButton();
    } catch (e) {
        console.error('Error loading AI subtitle videos:', e);
        grid.innerHTML = '<div class="has-text-centered p-4 has-text-danger">Failed to load videos.</div>';
    }
};

// ========== SHOWS VIEW ==========

window.loadAiShows = async function() {
    const search = (document.getElementById('aiSubSearch') || {}).value || '';
    const filter = (document.getElementById('aiSubFilter') || {}).value || 'all';
    window.aiSubState.currentSearch = search;
    window.aiSubState.currentFilter = filter;

    const grid = document.getElementById('aiVideoGrid');
    if (!grid) return;
    grid.innerHTML = '<div class="has-text-centered p-4"><i class="pi pi-spin pi-spinner mr-2"></i> Loading shows...</div>';
    grid.style.display = 'grid';

    try {
        const res = await fetch(`/api/ai-subtitles/shows?search=${encodeURIComponent(search)}&filter=${filter}`);
        const data = await res.json();
        window.updateParakeetStatus(data.parakeetAvailable);

        const totalSpan = document.getElementById('aiSubTotalCount');
        if (totalSpan) totalSpan.textContent = data.total + ' shows';

        if (!data.shows || data.shows.length === 0) {
            grid.innerHTML = '<div class="has-text-centered p-4 has-text-grey">No shows found.</div>';
            window.updateGenerateButton();
            return;
        }

        grid.innerHTML = data.shows.map(s => {
            const allAi = s.allHaveAi;
            const noneAi = s.aiEpisodes === 0;
            const badgeColor = allAi ? 'is-success' : (noneAi ? 'is-danger' : 'is-warning');
            const badgeText = allAi ? 'All AI' : (s.aiEpisodes + '/' + s.totalEpisodes + ' AI');
            return `<div class="ai-show-card" onclick="window.openShowEpisodes('${encodeURIComponent(s.seriesTitle)}')" 
                        style="cursor:pointer;border:2px solid transparent;border-radius:8px;padding:10px;background:rgba(255,255,255,0.03);transition:0.2s;position:relative;">
                <div style="aspect-ratio:16/9;background:rgba(0,0,0,0.3);border-radius:4px;margin-bottom:6px;display:flex;align-items:center;justify-content:center;font-size:2rem;">
                    <i class="pi pi-play-circle" style="opacity:0.3;"></i>
                </div>
                <div class="is-size-7 has-text-weight-semibold" style="display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;line-height:1.2;">${s.seriesTitle}</div>
                <div class="is-size-7 has-text-grey">${s.totalEpisodes} episodes</div>
                <span class="tag ${badgeColor} is-small is-light" style="margin-top:4px;">${badgeText}</span>
            </div>`;
        }).join('');

        window.updateGenerateButton();
    } catch (e) {
        console.error('Error loading shows:', e);
        grid.innerHTML = '<div class="has-text-centered p-4 has-text-danger">Failed to load shows.</div>';
    }
};

window.openShowEpisodes = function(encodedTitle) {
    const seriesTitle = decodeURIComponent(encodedTitle);
    window.aiSubState.currentShow = seriesTitle;

    document.getElementById('aiVideoGrid').style.display = 'none';
    document.getElementById('aiShowEpisodesView').style.display = 'block';
    document.getElementById('aiCurrentShowTitle').textContent = seriesTitle;

    window.loadShowEpisodes(seriesTitle, 0);
};

window.backToShowsView = function() {
    window.aiSubState.currentShow = null;
    document.getElementById('aiShowEpisodesView').style.display = 'none';
    document.getElementById('aiVideoGrid').style.display = 'grid';
    window.loadAiShows();
};

window.updateEpisodeTableUI = function() {
    const eps = window.aiSubState.currentEpisodes;
    const tbody = document.getElementById('aiEpisodeTableBody');
    const toolbar = document.getElementById('aiShowEpisodesToolbar');
    if (!tbody || !eps) return;

    const totalSelected = eps.filter(e => window.aiSubState.selectedIds.has(e.id)).length;
    const allSelected = totalSelected === eps.length;

    // Update rows
    tbody.querySelectorAll('tr').forEach(tr => {
        const id = parseInt(tr.dataset.id);
        const cb = tr.querySelector('.ai-episode-check');
        const checked = window.aiSubState.selectedIds.has(id);
        if (cb) cb.checked = checked;
        tr.style.background = checked ? 'rgba(72,199,116,0.1)' : '';
        tr.className = 'ai-episode-row' + (checked ? ' is-selected' : '');
    });

    // Update header checkbox
    const headerCheck = document.getElementById('aiEpisodeSelectAllCheck');
    if (headerCheck) headerCheck.checked = allSelected;

    // Update toolbar
    if (toolbar) {
        toolbar.querySelector('.tag').textContent = totalSelected + ' of ' + eps.length + ' selected';
        toolbar.querySelector('.button').textContent = allSelected ? 'Deselect All' : 'Select All';
    }
};

window.loadShowEpisodes = async function(seriesTitle, page) {
    const search = (document.getElementById('aiSubSearch') || {}).value || '';
    const filter = (document.getElementById('aiSubFilter') || {}).value || 'all';

    const container = document.getElementById('aiShowEpisodesGrid');
    if (!container) return;
    container.innerHTML = '<div class="has-text-centered p-4"><i class="pi pi-spin pi-spinner mr-2"></i> Loading episodes...</div>';

    // Remove old toolbar if exists
    const oldToolbar = document.getElementById('aiShowEpisodesToolbar');
    if (oldToolbar) oldToolbar.remove();

    try {
        const res = await fetch(`/api/ai-subtitles/shows/${encodeURIComponent(seriesTitle)}/episodes?page=${page}&limit=500&search=${encodeURIComponent(search)}&filter=${filter}`);
        const data = await res.json();
        window.aiSubState.currentEpisodes = data.episodes || [];

        if (!data.episodes || data.episodes.length === 0) {
            container.innerHTML = '<div class="has-text-centered p-4 has-text-grey">No episodes found.</div>';
            return;
        }

        const totalSelected = data.episodes.filter(e => window.aiSubState.selectedIds.has(e.id)).length;
        const allSelected = totalSelected === data.episodes.length;

        // Build table
        container.innerHTML = `<table class="table is-fullwidth is-hoverable" style="font-size:0.85rem;">
            <thead>
                <tr>
                    <th style="width:40px;"><input type="checkbox" id="aiEpisodeSelectAllCheck" ${allSelected ? 'checked' : ''} onchange="window.toggleSelectAllShowEpisodes()"></th>
                    <th style="width:70px;">#</th>
                    <th>Title</th>
                    <th style="width:60px;">Status</th>
                </tr>
            </thead>
            <tbody id="aiEpisodeTableBody">
                ${data.episodes.map((e, i) => {
                    const checked = window.aiSubState.selectedIds.has(e.id);
                    const epLabel = e.seasonNumber != null && e.episodeNumber != null ? `S${e.seasonNumber}E${e.episodeNumber}` : e.id;
                    const statusBadge = e.hasAiSubtitles
                        ? '<span class="tag is-success is-small is-light">AI</span>'
                        : (e.hasSubtitles ? '<span class="tag is-warning is-small is-light">Has</span>' : '<span class="tag is-danger is-small is-light">None</span>');
                    return `<tr class="ai-episode-row ${checked ? 'is-selected' : ''}" data-index="${i}" data-id="${e.id}" style="cursor:pointer;${checked ? 'background:rgba(72,199,116,0.1);' : ''}">
                        <td><input type="checkbox" class="ai-episode-check" data-id="${e.id}" ${checked ? 'checked' : ''}></td>
                        <td><span class="has-text-grey">${epLabel}</span></td>
                        <td>${e.episodeTitle || e.title || e.filename}</td>
                        <td>${statusBadge}</td>
                    </tr>`;
                }).join('')}
            </tbody>
        </table>`;

        // Add toolbar below table
        const toolbar = document.createElement('div');
        toolbar.id = 'aiShowEpisodesToolbar';
        toolbar.className = 'level is-mobile mt-2';
        toolbar.innerHTML = `
            <div class="level-left">
                <span class="tag is-info">${totalSelected} of ${data.episodes.length} selected</span>
            </div>
            <div class="level-right">
                <button class="button is-small is-light" onclick="window.toggleSelectAllShowEpisodes()">
                    ${allSelected ? 'Deselect All' : 'Select All'}
                </button>
            </div>`;
        container.parentElement.appendChild(toolbar);

        // Attach shift-click handlers (local only, no re-fetch)
        const rows = container.querySelectorAll('.ai-episode-row');
        rows.forEach(row => {
            row.addEventListener('click', function(e) {
                if (e.target.type === 'checkbox') return;
                const index = parseInt(this.dataset.id);
                if (e.shiftKey && window.aiSubState.lastClickedIndex >= 0) {
                    const from = Math.min(window.aiSubState.lastClickedIndex, index);
                    const to = Math.max(window.aiSubState.lastClickedIndex, index);
                    const eps = window.aiSubState.currentEpisodes;
                    if (from === index || to === index) {
                        // Find the actual indices in the array
                        let startIdx = -1, endIdx = -1;
                        for (let i = 0; i < eps.length; i++) {
                            if (eps[i].id === window.aiSubState.lastClickedIndex) startIdx = i;
                            if (eps[i].id === index) endIdx = i;
                        }
                        if (startIdx >= 0 && endIdx >= 0) {
                            const lo = Math.min(startIdx, endIdx);
                            const hi = Math.max(startIdx, endIdx);
                            for (let i = lo; i <= hi; i++) {
                                window.aiSubState.selectedIds.add(eps[i].id);
                            }
                        }
                    }
                } else {
                    window.toggleSingleSelection(index);
                }
                window.aiSubState.lastClickedIndex = index;
                window.updateEpisodeTableUI();
                window.updateGenerateButton();
            });
        });

        // Handle checkbox clicks (local only, no re-fetch)
        container.querySelectorAll('.ai-episode-check').forEach(cb => {
            cb.addEventListener('change', function(e) {
                e.stopPropagation();
                const id = parseInt(this.dataset.id);
                window.toggleSingleSelection(id);
                window.aiSubState.lastClickedIndex = id;
                window.updateEpisodeTableUI();
                window.updateGenerateButton();
            });
        });

        // Header checkbox
        const headerCheck = document.getElementById('aiEpisodeSelectAllCheck');
        if (headerCheck) {
            headerCheck.addEventListener('change', function() {
                window.toggleSelectAllShowEpisodes();
            });
        }

        window.updateGenerateButton();
    } catch (e) {
        console.error('Error loading episodes:', e);
        container.innerHTML = '<div class="has-text-centered p-4 has-text-danger">Failed to load episodes.</div>';
    }
};

window.toggleSingleSelection = function(id) {
    if (window.aiSubState.selectedIds.has(id)) {
        window.aiSubState.selectedIds.delete(id);
    } else {
        window.aiSubState.selectedIds.add(id);
    }
};

window.toggleSelectAllShowEpisodes = function() {
    const eps = window.aiSubState.currentEpisodes;
    if (!eps || eps.length === 0) return;
    const allSelected = eps.every(e => window.aiSubState.selectedIds.has(e.id));
    if (allSelected) {
        eps.forEach(e => window.aiSubState.selectedIds.delete(e.id));
    } else {
        eps.forEach(e => window.aiSubState.selectedIds.add(e.id));
    }
    window.updateEpisodeTableUI();
    window.updateGenerateButton();
};

// ========== VIDEO SELECTION (shared) ==========

window.toggleAiVideoSelection = function(id) {
    window.toggleSingleSelection(id);
    if (window.aiSubState.currentShow) {
        window.updateEpisodeTableUI();
    } else if (window.aiSubState.view === 'shows') {
        window.loadAiShows();
    } else {
        window.loadAiSubtitleVideos(window.aiSubState.currentPage);
    }
    window.updateGenerateButton();
};

window.toggleSelectAllAiVideos = function() {
    if (window.aiSubState.view === 'shows' || window.aiSubState.currentShow) return; // handled separately

    const cards = document.querySelectorAll('#aiVideoGrid .ai-video-card');
    const visibleIds = Array.from(cards).map(c => {
        const onclick = c.getAttribute('onclick') || '';
        const match = onclick.match(/toggleAiVideoSelection\((\d+)\)/);
        return match ? parseInt(match[1]) : null;
    }).filter(id => id !== null);

    const allSelected = visibleIds.every(id => window.aiSubState.selectedIds.has(id));
    if (allSelected) {
        visibleIds.forEach(id => window.aiSubState.selectedIds.delete(id));
    } else {
        visibleIds.forEach(id => window.aiSubState.selectedIds.add(id));
    }
    window.loadAiSubtitleVideos(window.aiSubState.currentPage);
    window.updateGenerateButton();
};

window.clearAiVideoSelection = function() {
    window.aiSubState.selectedIds.clear();
    window.refreshCurrentAiView();
    window.updateGenerateButton();
};

window.updateParakeetStatus = function(available) {
    window.aiSubState.parakeetAvailable = available;
    const badge = document.getElementById('parakeetStatusBadge');
    if (badge) {
        badge.textContent = available ? 'Parakeet Ready' : 'Parakeet Not Installed';
        badge.className = available ? 'tag is-success ml-3' : 'tag is-danger ml-3';
    }
};

window.updateGenerateButton = function() {
    const btn = document.getElementById('generateAiSubtitlesBtn');
    const countSpan = document.getElementById('aiSubSelectedCount');
    const count = window.aiSubState.selectedIds.size;
    if (countSpan) countSpan.textContent = count + ' selected';
    if (btn) {
        btn.disabled = count === 0 || !window.aiSubState.parakeetAvailable;
        btn.innerHTML = `<i class="pi pi-play mr-1"></i> Generate (${count})`;
    }
};

window.startAiSubtitleGeneration = async function() {
    const videoIds = Array.from(window.aiSubState.selectedIds);
    if (videoIds.length === 0) return;

    const langSelect = document.getElementById('aiSubLanguage');
    const language = langSelect ? langSelect.value : 'en';

    try {
        const res = await fetch('/api/ai-subtitles/generate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ videoIds: videoIds, language: language })
        });
        const data = await res.json();
        if (res.ok) {
            window.aiSubState.jobId = data.jobId;
            const panel = document.getElementById('aiSubProgressPanel');
            if (panel) panel.style.display = 'block';
            const btn = document.getElementById('generateAiSubtitlesBtn');
            if (btn) btn.disabled = true;
            window.startPollingAiProgress();
            if (window.showToast) window.showToast('Generation started for ' + videoIds.length + ' videos', 'success');
        } else {
            if (window.showToast) window.showToast(data.error || 'Failed to start generation', 'error');
        }
    } catch (e) {
        if (window.showToast) window.showToast('Error: ' + e.message, 'error');
    }
};

// ========== PROGRESS POLLING ==========

window.startPollingAiProgress = function() {
    if (window.aiSubState.pollingInterval) {
        clearInterval(window.aiSubState.pollingInterval);
    }
    window.aiSubState.pollingInterval = setInterval(window.pollAiProgress, 2000);
    window.pollAiProgress();
};

window.pollAiProgress = async function() {
    try {
        const res = await fetch('/api/ai-subtitles/status');
        const data = await res.json();

        const panel = document.getElementById('aiSubProgressPanel');
        const bar = document.getElementById('aiSubProgressBar');
        const statusTag = document.getElementById('aiSubProgressStatus');
        const titleSpan = document.getElementById('aiSubProgressTitle');
        const countSpan = document.getElementById('aiSubProgressCount');
        const errorsDiv = document.getElementById('aiSubProgressErrors');

        if (!data.running && data.status === 'idle') {
            window.stopPollingAiProgress();
            if (panel) panel.style.display = 'none';
            window.updateGenerateButton();
            window.refreshCurrentAiView();
            window.loadCompletedAiSubtitles(0);
            return;
        }

        if (bar) bar.value = data.overallProgress || 0;
        if (statusTag) {
            statusTag.textContent = Math.round(data.overallProgress || 0) + '%';
            statusTag.className = 'tag ' + (
                data.status === 'completed' ? 'is-success' :
                data.status === 'cancelled' ? 'is-warning' :
                data.failed > 0 ? 'is-danger' : 'is-info');
        }
        if (titleSpan) {
            const progress = data.overallProgress || 0;
            const seriesSpan = document.getElementById('aiSubProgressSeries');

            if (progress >= 100 && (data.status === 'running' || data.running)) {
                titleSpan.textContent = 'Finalizing...';
                if (seriesSpan) seriesSpan.style.display = 'none';
            } else if (data.currentVideoTitle) {
                titleSpan.textContent = data.currentVideoTitle;
                if (seriesSpan) {
                    if (data.currentVideoSeries) {
                        let s = data.currentVideoSeries;
                        if (data.currentVideoSeason != null) {
                            s += ' — Season ' + data.currentVideoSeason;
                        }
                        seriesSpan.textContent = s;
                        seriesSpan.style.display = '';
                    } else {
                        seriesSpan.style.display = 'none';
                    }
                }
            } else if (data.status === 'running' || data.running) {
                const done = data.completed || 0;
                const total = data.total || 0;
                titleSpan.textContent = done > 0 ? 'Processing video ' + (done + 1) + ' of ' + total + '...' : 'Starting Parakeet...';
                if (seriesSpan) seriesSpan.style.display = 'none';
            } else {
                titleSpan.textContent = 'Preparing...';
                if (seriesSpan) seriesSpan.style.display = 'none';
            }
        }
        if (countSpan) {
            countSpan.textContent = (data.completed || 0) + ' of ' + (data.total || 0) + ' completed' +
                (data.failed > 0 ? ' (' + data.failed + ' failed)' : '');
        }

        if (errorsDiv && data.errors && data.errors.length > 0) {
            errorsDiv.style.display = 'block';
            errorsDiv.innerHTML = data.errors.map(e =>
                '<div class="notification is-danger is-light py-2 px-3 mb-1 is-size-7">' + e + '</div>'
            ).join('');
        }

        if (data.status === 'completed' || data.status === 'cancelled' || data.status === 'failed') {
            window.stopPollingAiProgress();
            if (panel) setTimeout(() => { panel.style.display = 'none'; }, 3000);
            window.updateGenerateButton();
            window.refreshCurrentAiView();
            window.loadCompletedAiSubtitles(0);
        }
    } catch (e) {
        console.error('Polling error:', e);
    }
};

window.stopPollingAiProgress = function() {
    if (window.aiSubState.pollingInterval) {
        clearInterval(window.aiSubState.pollingInterval);
        window.aiSubState.pollingInterval = null;
    }
};

window.cancelAiSubtitleGeneration = async function() {
    try {
        await fetch('/api/ai-subtitles/cancel', { method: 'POST' });
        if (window.showToast) window.showToast('Generation cancelled', 'warning');
    } catch (e) {
        if (window.showToast) window.showToast('Error cancelling: ' + e.message, 'error');
    }
};

// ========== COMPLETED LIST ==========

window.loadCompletedAiSubtitles = async function(page) {
    window.aiSubState.aiCompletedPage = page;
    const container = document.getElementById('aiCompletedList');
    const countSpan = document.getElementById('aiSubCompletedCount');
    if (!container) return;

    container.innerHTML = '<div class="has-text-centered p-4"><i class="pi pi-spin pi-spinner mr-2"></i> Loading...</div>';

    try {
        const res = await fetch(`/api/ai-subtitles/completed?page=${page}&limit=50`);
        const data = await res.json();

        if (countSpan) countSpan.textContent = data.total || 0;

        const pagination = document.getElementById('aiCompletedPagination');
        if (pagination) pagination.style.display = data.total > 50 ? 'block' : 'none';
        const prevBtn = document.getElementById('aiCompletedPrevBtn');
        const nextBtn = document.getElementById('aiCompletedNextBtn');
        if (prevBtn) prevBtn.disabled = page === 0;
        if (nextBtn) nextBtn.disabled = (page * 50 + 50) >= (data.total || 0);

        if (!data.videos || data.videos.length === 0) {
            container.innerHTML = '<div class="has-text-centered p-4 has-text-grey">No AI-generated subtitles yet. Select videos above and click Generate.</div>';
            return;
        }

        container.innerHTML = `<table class="table is-fullwidth is-hoverable">
            <thead>
                <tr>
                    <th>Video</th>
                    <th>Language</th>
                    <th>Track File</th>
                    <th>Actions</th>
                </tr>
            </thead>
            <tbody>
                ${data.videos.map(v => {
                    const tracks = v.aiTracks || [];
                    if (tracks.length === 0) return `<tr><td>${v.title || v.filename}</td><td colspan="3"><span class="tag is-warning is-light">Track info unavailable</span></td></tr>`;
                    return tracks.map(t => `<tr>
                        <td>${v.title || v.filename}</td>
                        <td><span class="tag">${t.languageName || t.languageCode || 'unknown'}</span></td>
                        <td class="is-size-7 has-text-grey">${t.filename || ''}</td>
                        <td>
                            <div class="buttons">
                                <button class="button is-small is-info is-outlined" onclick="window.regenAiSubtitle(${v.id}, '${t.languageCode || 'en'}')"><i class="pi pi-refresh"></i> Regen</button>
                                <button class="button is-small is-danger is-outlined" onclick="window.deleteAiSubtitleTrack(${t.id})"><i class="pi pi-trash"></i></button>
                            </div>
                        </td>
                    </tr>`).join('');
                }).join('')}
            </tbody>
        </table>`;
    } catch (e) {
        console.error('Error loading completed AI subtitles:', e);
        container.innerHTML = '<div class="has-text-centered p-4 has-text-danger">Failed to load completed subtitles.</div>';
    }
};

window.deleteAiSubtitleTrack = async function(trackId) {
    if (!confirm('Delete this AI-generated subtitle track? This will also remove the file.')) return;
    try {
        const res = await fetch(`/api/ai-subtitles/track/${trackId}`, { method: 'DELETE' });
        if (res.ok) {
            if (window.showToast) window.showToast('Subtitle track deleted', 'success');
            window.loadCompletedAiSubtitles(window.aiSubState.aiCompletedPage);
            window.refreshCurrentAiView();
        } else {
            const data = await res.json();
            if (window.showToast) window.showToast(data.error || 'Failed to delete', 'error');
        }
    } catch (e) {
        if (window.showToast) window.showToast('Error: ' + e.message, 'error');
    }
};

window.regenAiSubtitle = async function(videoId, languageCode) {
    if (!confirm('Regenerate AI subtitles for this video? This will start a new generation job.')) return;
    try {
        const res = await fetch('/api/ai-subtitles/generate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ videoIds: [videoId], language: languageCode })
        });
        const data = await res.json();
        if (res.ok) {
            window.aiSubState.jobId = data.jobId;
            const panel = document.getElementById('aiSubProgressPanel');
            if (panel) panel.style.display = 'block';
            window.startPollingAiProgress();
            if (window.showToast) window.showToast('Regeneration started', 'success');
        } else {
            if (window.showToast) window.showToast(data.error || 'Failed to start regeneration', 'error');
        }
    } catch (e) {
        if (window.showToast) window.showToast('Error: ' + e.message, 'error');
    }
};

// Resume progress panel if a job was running before page reload
window.resumeIfJobRunning = async function() {
    try {
        const res = await fetch('/api/ai-subtitles/status');
        const data = await res.json();
        if (data.running || data.status === 'running') {
            window.aiSubState.jobId = data.jobId;
            const panel = document.getElementById('aiSubProgressPanel');
            if (panel) panel.style.display = 'block';
            window.startPollingAiProgress();
        }
    } catch (e) {
        console.error('Error checking job status on resume:', e);
    }
};

// Auto-initialize (works with both defer and non-defer)
function initAiSubtitleTab() {
    const activeTab = document.querySelector('#settingsSideTabs .nav-item.active');
    if (activeTab && activeTab.getAttribute('data-tab') === 'ai-subtitle-generator') {
        window.loadAiSubtitleVideos(0);
        window.loadCompletedAiSubtitles(0);
        window.resumeIfJobRunning();
    }
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initAiSubtitleTab);
} else {
    initAiSubtitleTab();
}