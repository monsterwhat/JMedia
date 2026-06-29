let externalHlsInstance = null;
let externalProgressInterval = null;

function getProfileId() {
    return localStorage.getItem('activeProfileId') || '1';
}

function initExternalVideoView() {
    loadExternalVideos();
    resetExternalForm();
}

function onEntryTypeChange() {
    const val = document.getElementById('externalEntryType').value;
    document.getElementById('externalMovieFields').classList.toggle('is-hidden', val !== 'MOVIE');
    document.getElementById('externalEpisodeFields').classList.toggle('is-hidden', val !== 'EPISODE');
}

function resetExternalForm() {
    document.getElementById('externalEntryType').value = '';
    document.getElementById('externalMovieFields').classList.add('is-hidden');
    document.getElementById('externalEpisodeFields').classList.add('is-hidden');
    document.getElementById('externalMovieNameInput').value = '';
    document.getElementById('externalSeriesTitleInput').value = '';
    document.getElementById('externalSeasonInput').value = '';
    document.getElementById('externalEpisodeInput').value = '';
    document.getElementById('externalEpisodeTitleInput').value = '';
    document.getElementById('externalTitleInput').value = '';
    const container = document.getElementById('externalUrlFields');
    container.innerHTML = `
        <div class="field has-addons mb-2 url-row">
            <div class="control is-expanded">
                <input class="input url-input" type="url" placeholder="https://example.com/stream/video.m3u8">
            </div>
            <div class="control">
                <input class="input url-label-input" type="text" placeholder="Label (e.g. 1080p)" style="width: 100px;">
            </div>
        </div>
    `;
    document.getElementById('externalSaveFeedback').style.display = 'none';
}

function addExternalUrlField() {
    const container = document.getElementById('externalUrlFields');
    const row = document.createElement('div');
    row.className = 'field has-addons mb-2 url-row';
    row.innerHTML = `
        <div class="control is-expanded">
            <input class="input url-input" type="url" placeholder="https://example.com/stream/alt.m3u8">
        </div>
        <div class="control">
            <input class="input url-label-input" type="text" placeholder="Label" style="width: 100px;">
        </div>
        <div class="control">
            <button class="button is-small is-danger" onclick="this.closest('.url-row').remove()">
                <i class="pi pi-times"></i>
            </button>
        </div>
    `;
    container.appendChild(row);
}

function collectUrls() {
    const rows = document.querySelectorAll('#externalUrlFields .url-row');
    const urls = [];
    rows.forEach(row => {
        const urlInput = row.querySelector('.url-input');
        const labelInput = row.querySelector('.url-label-input');
        const url = urlInput.value.trim();
        if (url) {
            const entry = { url };
            const label = labelInput ? labelInput.value.trim() : '';
            if (label) entry.label = label;
            urls.push(entry);
        }
    });
    return urls;
}

async function saveExternalVideo() {
    const entryType = document.getElementById('externalEntryType').value;
    const title = document.getElementById('externalTitleInput').value.trim();
    const urls = collectUrls();
    const feedback = document.getElementById('externalSaveFeedback');

    if (!entryType) {
        showFeedback('Please select an entry type (Movie or TV Show Episode)', 'danger');
        return;
    }
    if (!title) {
        showFeedback('Please enter a title', 'danger');
        return;
    }
    if (urls.length === 0) {
        showFeedback('Please enter at least one URL', 'danger');
        return;
    }

    const primaryUrl = urls[0].url;
    const alternatives = urls.length > 1 ? urls.slice(1) : [];

    const body = {
        url: primaryUrl,
        title: title,
        profileId: parseInt(getProfileId()),
        entryType: entryType
    };
    if (alternatives.length > 0) {
        body.alternativeUrls = alternatives;
    }

    if (entryType === 'MOVIE') {
        const movieName = document.getElementById('externalMovieNameInput').value.trim();
        if (movieName) body.seriesTitle = movieName;
    } else if (entryType === 'EPISODE') {
        body.seriesTitle = document.getElementById('externalSeriesTitleInput').value.trim();
        const season = parseInt(document.getElementById('externalSeasonInput').value);
        const episode = parseInt(document.getElementById('externalEpisodeInput').value);
        if (!isNaN(season)) body.seasonNumber = season;
        if (!isNaN(episode)) body.episodeNumber = episode;
        body.episodeTitle = document.getElementById('externalEpisodeTitleInput').value.trim();
    }

    const btn = document.getElementById('saveExternalBtn');
    btn.classList.add('is-loading');

    try {
        const res = await fetch('/api/video/external/create', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        const json = await res.json();
        if (json.success) {
            showFeedback('Saved!', 'success');
            resetExternalForm();
            loadExternalVideos();
        } else {
            showFeedback(json.error || 'Failed to save', 'danger');
        }
    } catch (e) {
        showFeedback('Error: ' + e.message, 'danger');
    } finally {
        btn.classList.remove('is-loading');
    }
}

function showFeedback(msg, type) {
    const el = document.getElementById('externalSaveFeedback');
    el.textContent = msg;
    el.className = 'mt-2 notification is-' + type + ' is-light py-2 px-3';
    el.style.display = 'block';
    setTimeout(() => { el.style.display = 'none'; }, 3000);
}

async function loadExternalVideos() {
    const listEl = document.getElementById('externalVideoList');
    if (!listEl) return;

    try {
        const res = await fetch('/api/video/external/list?profileId=' + getProfileId());
        const json = await res.json();
        if (!json.success) {
            listEl.innerHTML = '<div class="notification is-danger is-light">Failed to load external videos</div>';
            return;
        }

        const videos = json.data || [];
        if (videos.length === 0) {
            listEl.innerHTML = `
                <div class="has-text-centered has-text-grey p-4">
                    <i class="pi pi-external-link" style="font-size: 2rem; opacity: 0.3;"></i>
                    <p class="mt-2">No external links yet. Add one above!</p>
                </div>
            `;
            return;
        }

        listEl.innerHTML = videos.map(v => renderExternalVideoCard(v)).join('');
    } catch (e) {
        listEl.innerHTML = '<div class="notification is-danger is-light">Error loading: ' + e.message + '</div>';
    }
}

function renderExternalVideoCard(v) {
    const hasAlts = v.alternativeUrls && Array.isArray(v.alternativeUrls) && v.alternativeUrls.length > 0;
    const altCount = hasAlts ? v.alternativeUrls.length : 0;
    const progress = v.watchProgress ? Math.round(v.watchProgress * 100) : 0;
    const sourceIcon = getSourceIcon(v.sourceType);
    const isWatched = v.watched;
    const altHtml = hasAlts ? v.alternativeUrls.map(a => escapeHtml(a.url || '')).join('\n') : '';
    const entryType = v.entryType || '';
    const isEpisode = entryType === 'EPISODE';

    let metaHtml = '';
    if (isEpisode) {
        const parts = [];
        if (v.seriesTitle) parts.push(escapeHtml(v.seriesTitle));
        if (v.seasonNumber || v.episodeNumber) {
            parts.push('S' + (v.seasonNumber || '?') + 'E' + (v.episodeNumber || '?'));
        }
        metaHtml = parts.join(' — ');
    } else if (entryType === 'MOVIE') {
        metaHtml = v.seriesTitle ? escapeHtml(v.seriesTitle) : 'Movie';
    } else {
        metaHtml = escapeHtml(v.title);
    }

    return `
        <div class="external-video-card ${isWatched ? 'is-watched' : ''}" data-id="${v.id}">
            <div class="external-video-card-body">
                <div class="external-video-card-info">
                    <div class="external-video-card-title-row">
                        <span class="external-video-source-badge">${sourceIcon} ${v.sourceType || 'direct'}</span>
                        ${isEpisode ? '<span class="tag is-info is-light is-small ml-1">TV</span>' : (entryType === 'MOVIE' ? '<span class="tag is-success is-light is-small ml-1">Movie</span>' : '')}
                        <span class="external-video-title">${escapeHtml(v.title)}</span>
                    </div>
                    <div class="external-video-card-meta">
                        <span class="is-size-7 has-text-grey" title="${escapeHtml(v.url)}">${truncateUrl(v.url, 60)}</span>
                        ${altCount > 0 ? `<span class="tag is-small is-info is-light ml-2">+${altCount} alt</span>` : ''}
                    </div>
                    ${metaHtml ? `<div class="is-size-7 has-text-grey-light mt-1">${metaHtml}</div>` : ''}
                    ${progress > 0 ? `
                    <div class="external-video-progress mt-1">
                        <progress class="progress is-small is-success" value="${progress}" max="100">${progress}%</progress>
                    </div>` : ''}
                </div>
                <div class="external-video-card-actions">
                    <button class="button is-small is-success" onclick="playExternalVideo(${v.id})" title="Play">
                        <i class="pi pi-play"></i>
                    </button>
                    <button class="button is-small is-info is-light" onclick="editExternalVideo(${v.id})" title="Edit">
                        <i class="pi pi-pencil"></i>
                    </button>
                    <button class="button is-small is-danger is-light" onclick="deleteExternalVideo(${v.id})" title="Delete">
                        <i class="pi pi-trash"></i>
                    </button>
                </div>
            </div>
            <div class="external-video-edit-form" id="edit-form-${v.id}" style="display: none;">
                <hr class="my-2">
                <div class="field">
                    <label class="label is-small">Entry Type</label>
                    <div class="select is-small is-fullwidth">
                        <select class="edit-entry-type" onchange="onEditEntryTypeChange(${v.id})">
                            <option value="MOVIE" ${v.entryType === 'MOVIE' ? 'selected' : ''}>Movie</option>
                            <option value="EPISODE" ${v.entryType === 'EPISODE' ? 'selected' : ''}>TV Show Episode</option>
                        </select>
                    </div>
                </div>
                <div class="edit-movie-fields-${v.id} ${v.entryType === 'MOVIE' ? '' : 'is-hidden'}">
                    <div class="field">
                        <label class="label is-small">Movie Name</label>
                        <input class="input is-small edit-movie-name" value="${escapeHtml(v.seriesTitle || '')}">
                    </div>
                </div>
                <div class="edit-episode-fields-${v.id} ${v.entryType === 'EPISODE' ? '' : 'is-hidden'}">
                    <div class="field">
                        <label class="label is-small">Series Title</label>
                        <input class="input is-small edit-series-title" value="${escapeHtml(v.seriesTitle || '')}">
                    </div>
                    <div class="columns is-mobile">
                        <div class="column">
                            <div class="field">
                                <label class="label is-small">Season</label>
                                <input class="input is-small edit-season" type="number" value="${v.seasonNumber || ''}">
                            </div>
                        </div>
                        <div class="column">
                            <div class="field">
                                <label class="label is-small">Episode</label>
                                <input class="input is-small edit-episode" type="number" value="${v.episodeNumber || ''}">
                            </div>
                        </div>
                    </div>
                    <div class="field">
                        <label class="label is-small">Episode Title</label>
                        <input class="input is-small edit-episode-title" value="${escapeHtml(v.episodeTitle || '')}">
                    </div>
                </div>
                <div class="field">
                    <label class="label is-small">Title</label>
                    <input class="input is-small edit-title" value="${escapeHtml(v.title)}">
                </div>
                <div class="field">
                    <label class="label is-small">Primary URL</label>
                    <input class="input is-small edit-url" value="${escapeHtml(v.url)}">
                </div>
                <div class="field">
                    <label class="label is-small">Alternative URLs (one per line)</label>
                    <textarea class="textarea is-small edit-alts" rows="2">${escapeHtml(altHtml)}</textarea>
                </div>
                <div class="field is-grouped is-grouped-right">
                    <button class="button is-small is-success" onclick="updateExternalVideo(${v.id})"><i class="pi pi-check mr-1"></i>Save</button>
                    <button class="button is-small" onclick="cancelEditExternalVideo(${v.id})">Cancel</button>
                </div>
            </div>
        </div>
    `;
}

function onEditEntryTypeChange(id) {
    const val = document.querySelector('#edit-form-' + id + ' .edit-entry-type').value;
    document.querySelector('.edit-movie-fields-' + id).classList.toggle('is-hidden', val !== 'MOVIE');
    document.querySelector('.edit-episode-fields-' + id).classList.toggle('is-hidden', val !== 'EPISODE');
}

function getSourceIcon(type) {
    const icons = {
        hls: '<i class="pi pi-video"></i>',
        mp4: '<i class="pi pi-file"></i>',
        webm: '<i class="pi pi-file"></i>',
        youtube: '<i class="pi pi-play-circle"></i>',
        streamtape: '<i class="pi pi-external-link"></i>',
        direct: '<i class="pi pi-link"></i>'
    };
    return icons[type] || '<i class="pi pi-link"></i>';
}

function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

function truncateUrl(url, max) {
    if (!url || url.length <= max) return url || '';
    return url.substring(0, max) + '...';
}

async function playExternalVideo(id) {
    try {
        const res = await fetch('/api/video/external/' + id);
        const json = await res.json();
        if (!json.success || !json.data) {
            if (window.showToast) window.showToast('Failed to load video', 'danger');
            return;
        }
        window.videoSPA.switchSection('playback', { externalVideoId: id });
    } catch (e) {
        console.error('[ExternalVideo] Error playing:', e);
        if (window.showToast) window.showToast('Error playing video', 'danger');
    }
}

async function deleteExternalVideo(id) {
    if (!confirm('Delete this external link?')) return;

    try {
        const res = await fetch('/api/video/external/' + id, { method: 'DELETE' });
        const text = await res.text();
        let json;
        try { json = JSON.parse(text); } catch (e) { json = { success: false }; }
        if (json.success) {
            loadExternalVideos();
        } else if (window.showToast) {
            window.showToast(json.error || 'Delete failed', 'danger');
        }
    } catch (e) {
        console.error('[ExternalVideo] Error deleting:', e);
        if (window.showToast) window.showToast('Error deleting', 'danger');
    }
}

function editExternalVideo(id) {
    const form = document.getElementById('edit-form-' + id);
    if (form) form.style.display = form.style.display === 'none' ? 'block' : 'none';
}

async function updateExternalVideo(id) {
    const form = document.getElementById('edit-form-' + id);
    if (!form) return;

    const title = form.querySelector('.edit-title').value.trim();
    const url = form.querySelector('.edit-url').value.trim();
    const altsText = form.querySelector('.edit-alts').value.trim();
    const entryType = form.querySelector('.edit-entry-type').value;

    if (!title || !url) {
        if (window.showToast) window.showToast('Title and URL are required', 'warning');
        return;
    }

    const body = { title, url, entryType };

    if (entryType === 'MOVIE') {
        const movieName = form.querySelector('.edit-movie-name').value.trim();
        if (movieName) body.seriesTitle = movieName;
    } else if (entryType === 'EPISODE') {
        body.seriesTitle = form.querySelector('.edit-series-title').value.trim();
        const season = parseInt(form.querySelector('.edit-season').value);
        const episode = parseInt(form.querySelector('.edit-episode').value);
        if (!isNaN(season)) body.seasonNumber = season;
        if (!isNaN(episode)) body.episodeNumber = episode;
        body.episodeTitle = form.querySelector('.edit-episode-title').value.trim();
    }

    const alternativeUrls = altsText ? altsText.split('\n').filter(u => u.trim()).map(u => ({ url: u.trim() })) : [];
    if (alternativeUrls.length > 0) body.alternativeUrls = alternativeUrls;

    try {
        const res = await fetch('/api/video/external/' + id, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        const text = await res.text();
        let json;
        try { json = JSON.parse(text); } catch (e) { json = { success: false }; }

        if (json.success) {
            form.style.display = 'none';
            loadExternalVideos();
            if (window.showToast) window.showToast('Updated!', 'success');
        } else {
            if (window.showToast) window.showToast(json.error || 'Update failed', 'danger');
        }
    } catch (e) {
        console.error('[ExternalVideo] Error updating:', e);
        if (window.showToast) window.showToast('Error updating', 'danger');
    }
}

function cancelEditExternalVideo(id) {
    const form = document.getElementById('edit-form-' + id);
    if (form) form.style.display = 'none';
}

function destroyExternalPlayer() {
    if (externalHlsInstance) {
        externalHlsInstance.destroy();
        externalHlsInstance = null;
    }
    if (externalProgressInterval) {
        clearInterval(externalProgressInterval);
        externalProgressInterval = null;
    }
}
