(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    console.log('[XtreamCats] XtreamCategories.js loaded');

    function escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    const state = {
        type: 'movie',
        q: '',
        genreId: '',
        offset: 0,
        limit: 48,
        total: 0,
        selection: new Set(),
        genres: [],
        singleDetail: null
    };

    let searchTimer = null;

    function showLoading(show) {
        const loading = document.getElementById('xtreamCatsLoading');
        if (loading) {
            loading.style.display = show ? 'block' : 'none';
            if (show) {
                loading.innerHTML = '<i class="pi pi-spin pi-spinner mr-2"></i> Loading titles...';
            }
        }
    }

    function showError(message) {
        const error = document.getElementById('xtreamCatsError');
        if (error) {
            error.textContent = message;
            error.style.display = 'block';
        }
    }

    function clearError() {
        const error = document.getElementById('xtreamCatsError');
        if (error) error.style.display = 'none';
    }

    function setCardSelected(card, selected) {
        card.classList.toggle('is-selected', selected);
        card.style.borderColor = selected ? '#48c774' : 'transparent';
        card.style.boxShadow = selected ? '0 0 0 2px rgba(72,199,116,0.35)' : 'none';
    }

    function updateBulkBar() {
        const bar = document.getElementById('xtreamCatsBulkBar');
        const count = document.getElementById('xtreamCatsSelectedCount');
        const singleBox = document.getElementById('xtreamCatsSingleBox');
        const multiBox = document.getElementById('xtreamCatsMultiBox');
        if (!bar) return;
        const n = state.selection.size;
        bar.style.display = n > 0 ? 'block' : 'none';
        if (count) count.textContent = n + ' selected';
        if (singleBox) singleBox.style.display = n === 1 ? 'block' : 'none';
        if (multiBox) multiBox.style.display = n > 1 ? 'block' : 'none';
        if (n === 1) {
            const parts = Array.from(state.selection)[0].split(':');
            loadSingleDetail(parts[0], parts[1]);
        } else {
            state.singleDetail = null;
        }
    }

    function updatePager() {
        const info = document.getElementById('xtreamCatsPagerInfo');
        const prev = document.getElementById('xtreamCatsPrevBtn');
        const next = document.getElementById('xtreamCatsNextBtn');
        if (info) {
            if (state.total === 0) {
                info.textContent = 'Showing 0 of 0';
            } else {
                const from = state.offset + 1;
                const to = Math.min(state.offset + state.limit, state.total);
                info.textContent = `Showing ${from}-${to} of ${state.total}`;
            }
        }
        if (prev) prev.disabled = state.offset <= 0;
        if (next) next.disabled = state.offset + state.limit >= state.total;
    }

    function renderTitleCard(t) {
        const key = state.type + ':' + t.id;
        const selected = state.selection.has(key);
        const poster = t.posterUrl
            ? `<img src="${escapeHtml(t.posterUrl)}" alt="${escapeHtml(t.title)}" loading="lazy" onerror="this.style.display='none'" class="xtream-cat-poster" style="width: 100%; aspect-ratio: 2 / 3; object-fit: cover; display: block;">`
            : '<div class="xtream-cat-poster xtream-cat-poster-fallback" style="width: 100%; aspect-ratio: 2 / 3; display: flex; align-items: center; justify-content: center; background: rgba(255,255,255,0.05);"><i class="pi pi-image has-text-grey"></i></div>';
        const year = t.year ? `<span class="is-size-7 has-text-grey">${escapeHtml(String(t.year))}</span>` : '';
        const tags = (t.genres || []).map(g => `<span class="tag is-small is-info is-light mr-1">${escapeHtml(g)}</span>`).join('');
        return `
            <div class="xtream-cat-card ${selected ? 'is-selected' : ''}" data-xtream-key="${key}"
                 style="border: 2px solid ${selected ? '#48c774' : 'transparent'}; border-radius: 8px; overflow: hidden; cursor: pointer; background: rgba(255,255,255,0.05); transition: border-color 0.15s, box-shadow 0.15s;${selected ? ' box-shadow: 0 0 0 2px rgba(72,199,116,0.35);' : ''}">
                ${poster}
                <div class="p-2">
                    <p class="xtream-cat-title has-text-weight-semibold is-size-7" style="line-height: 1.3; min-height: 2.6em;">${escapeHtml(t.title)}</p>
                    ${year}
                    <div class="xtream-cat-tags mt-1">${tags}</div>
                </div>
            </div>
        `;
    }

    function renderGenreFilter() {
        const select = document.getElementById('xtreamCatsGenreFilter');
        if (!select) return;
        const current = select.value;
        const options = ['<option value="">All genres</option>']
            .concat(state.genres.map(g => `<option value="${escapeHtml(String(g.id))}">${escapeHtml(g.name)}</option>`))
            .join('');
        select.innerHTML = options;
        if (current && state.genres.some(g => String(g.id) === current)) {
            select.value = current;
        }
    }

    function populatePickers() {
        const single = document.getElementById('xtreamCatsSingleAddPicker');
        if (single) {
            const cur = single.value;
            single.innerHTML = '<option value="">Add a genre...</option>' + state.genres.map(g =>
                `<option value="${escapeHtml(String(g.id))}">${escapeHtml(g.name)}</option>`).join('');
            if (cur) single.value = cur;
        }
        const multi = document.getElementById('xtreamCatsMultiGenrePicker');
        if (multi) {
            const curM = multi.value;
            multi.innerHTML = '<option value="">Choose a genre...</option>' + state.genres.map(g =>
                `<option value="${escapeHtml(String(g.id))}">${escapeHtml(g.name)}</option>`).join('');
            if (curM) multi.value = curM;
        }
    }

    function findCardByKey(key) {
        const cards = document.querySelectorAll('#xtreamCatsGrid .xtream-cat-card');
        for (let i = 0; i < cards.length; i++) {
            if (cards[i].getAttribute('data-xtream-key') === key) return cards[i];
        }
        return null;
    }

    function onGridClick(e) {
        const card = e.target.closest('.xtream-cat-card');
        if (!card) return;
        const key = card.getAttribute('data-xtream-key');
        if (!key) return;
        const parts = key.split(':');
        JMedia.XtreamCategories.toggleXtreamSelect(parts[1], parts[0]);
    }

    function onSearchInput() {
        const input = document.getElementById('xtreamCatsSearch');
        if (!input) return;
        clearTimeout(searchTimer);
        searchTimer = setTimeout(() => {
            state.q = input.value.trim();
            state.offset = 0;
            JMedia.XtreamCategories.loadXtreamTitles();
        }, 300);
    }

    function onGenreFilterChange() {
        const select = document.getElementById('xtreamCatsGenreFilter');
        state.genreId = select ? select.value : '';
        state.offset = 0;
        JMedia.XtreamCategories.loadXtreamTitles();
    }

    function renderSingleEditor() {
        const titleEl = document.getElementById('xtreamCatsSingleTitle');
        const listEl = document.getElementById('xtreamCatsSingleGenres');
        const d = state.singleDetail;
        if (titleEl) titleEl.textContent = d ? (d.title || '') : '';
        if (!listEl) return;
        if (!d) {
            listEl.innerHTML = '<p class="is-size-7 has-text-grey">Loading...</p>';
            return;
        }
        if (d.error) {
            listEl.innerHTML = `<p class="is-size-7 has-text-danger">${escapeHtml(d.error)}</p>`;
            return;
        }
        if (d.genres.length === 0) {
            listEl.innerHTML = '<p class="is-size-7 has-text-grey">No genres yet — add one below.</p>';
            return;
        }
        listEl.innerHTML = d.genres.map((g, i) => `
            <div class="box p-2 mb-1" style="display:flex;align-items:center;gap:6px;background:rgba(255,255,255,0.04);">
                <span class="tag ${i === 0 ? 'is-success' : 'is-info is-light'}">${i === 0 ? '1 · Primary' : (i + 1)}</span>
                <span class="is-size-7 has-text-weight-semibold" style="flex:1;">${escapeHtml(g.name)}</span>
                <div class="buttons are-small mb-0">
                    <button class="button is-small is-light" data-act="up" data-idx="${i}" ${i === 0 ? 'disabled' : ''} title="Move up"><i class="pi pi-chevron-up"></i></button>
                    <button class="button is-small is-light" data-act="down" data-idx="${i}" ${i === d.genres.length - 1 ? 'disabled' : ''} title="Move down"><i class="pi pi-chevron-down"></i></button>
                    <button class="button is-small is-danger is-outlined" data-act="remove" data-idx="${i}" title="Remove"><i class="pi pi-times"></i></button>
                </div>
            </div>
        `).join('');
    }

    function singleMove(idx, dir) {
        const d = state.singleDetail;
        if (!d) return;
        const j = idx + dir;
        if (j < 0 || j >= d.genres.length) return;
        const tmp = d.genres[idx];
        d.genres[idx] = d.genres[j];
        d.genres[j] = tmp;
        renderSingleEditor();
    }

    function singleRemove(idx) {
        const d = state.singleDetail;
        if (!d || idx < 0 || idx >= d.genres.length) return;
        if (d.genres.length <= 1) {
            Toast.error('A title must keep at least one genre');
            return;
        }
        d.genres.splice(idx, 1);
        renderSingleEditor();
    }

    function singleAdd() {
        const d = state.singleDetail;
        const picker = document.getElementById('xtreamCatsSingleAddPicker');
        if (!d || !picker || !picker.value) return;
        const gid = picker.value;
        const match = state.genres.find(g => String(g.id) === gid);
        if (!match || match.id === undefined || match.id === null) {
            Toast.error('Unknown genre selected');
            return;
        }
        if (d.genres.some(g => String(g.id) === gid || g.name.toLowerCase() === String(match.name).toLowerCase())) {
            Toast.error('Genre already assigned');
            return;
        }
        d.genres.push({ id: match.id, name: match.name });
        picker.value = '';
        renderSingleEditor();
    }

    JMedia.XtreamCategories = {
        loadXtreamGenres: async function() {
            console.log('[XtreamCats] loadXtreamGenres called');
            clearError();
            showLoading(true);
            try {
                const response = await fetch('/api/admin/xtream-categories/genres');
                const result = await response.json();
                showLoading(false);
                if (!response.ok || result.success === false) {
                    showError(result.error || `Failed to load genres (${response.status})`);
                    return;
                }
                state.genres = result.data || [];
                console.log('[XtreamCats] Received genres:', state.genres.length);
                renderGenreFilter();
                populatePickers();
            } catch (e) {
                console.error('[XtreamCats] Error loading genres:', e);
                showLoading(false);
                showError('Connection error: ' + e.message);
            }
        },

        loadXtreamTitles: async function() {
            console.log('[XtreamCats] loadXtreamTitles called');
            const grid = document.getElementById('xtreamCatsGrid');
            if (!grid) return;
            clearError();
            showLoading(true);
            grid.innerHTML = '';
            try {
                const params = new URLSearchParams({
                    type: state.type,
                    q: state.q,
                    genreId: state.genreId,
                    offset: String(state.offset),
                    limit: String(state.limit)
                });
                const response = await fetch('/api/admin/xtream-categories/titles?' + params.toString());
                const result = await response.json();
                showLoading(false);
                if (!response.ok || result.success === false) {
                    showError(result.error || `Failed to load titles (${response.status})`);
                    return;
                }
                const titles = result.data || [];
                state.total = result.total || titles.length;
                console.log('[XtreamCats] Received titles:', titles.length, 'total:', state.total);
                if (titles.length === 0) {
                    grid.innerHTML = '<div class="has-text-centered p-4 has-text-grey" style="grid-column: 1 / -1;">No titles found</div>';
                } else {
                    grid.innerHTML = titles.map(renderTitleCard).join('');
                }
                updatePager();
            } catch (e) {
                console.error('[XtreamCats] Error loading titles:', e);
                showLoading(false);
                showError('Connection error: ' + e.message);
            }
        },

        toggleXtreamSelect: function(id, kind) {
            const key = kind + ':' + id;
            const selected = !state.selection.has(key);
            if (selected) {
                state.selection.add(key);
            } else {
                state.selection.delete(key);
            }
            const card = findCardByKey(key);
            if (card) setCardSelected(card, selected);
            updateBulkBar();
        },

        selectAllVisibleXtream: function() {
            document.querySelectorAll('#xtreamCatsGrid .xtream-cat-card').forEach(card => {
                const key = card.getAttribute('data-xtream-key');
                if (key) {
                    state.selection.add(key);
                    setCardSelected(card, true);
                }
            });
            updateBulkBar();
        },

        clearXtreamSelection: function() {
            state.selection.clear();
            document.querySelectorAll('#xtreamCatsGrid .xtream-cat-card.is-selected').forEach(card => {
                setCardSelected(card, false);
            });
            updateBulkBar();
        },

        loadSingleDetail: async function(kind, id) {
            if (state.singleDetail && state.singleDetail.kind === kind && String(state.singleDetail.id) === String(id)) {
                renderSingleEditor();
                return;
            }
            state.singleDetail = null;
            renderSingleEditor();
            try {
                const response = await fetch(`/api/admin/xtream-categories/titles/${encodeURIComponent(kind)}/${encodeURIComponent(id)}`);
                const result = await response.json();
                if (!response.ok || result.success === false || !result.data) {
                    state.singleDetail = { kind: kind, id: id, title: '', genres: [], error: result.error || 'Failed to load title' };
                } else {
                    state.singleDetail = {
                        kind: kind,
                        id: id,
                        title: result.data.title || '',
                        genres: (result.data.genres || []).map(g => ({ id: g.id, name: g.name }))
                    };
                }
            } catch (e) {
                console.error('[XtreamCats] Error loading title detail:', e);
                state.singleDetail = { kind: kind, id: id, title: '', genres: [], error: 'Connection error' };
            }
            renderSingleEditor();
        },

        saveSingleDetail: async function() {
            const d = state.singleDetail;
            if (!d) return;
            if (d.genres.length === 0) {
                Toast.error('A title must keep at least one genre');
                return;
            }
            const genreIds = d.genres.map(g => g.id);
            try {
                const response = await fetch(`/api/admin/xtream-categories/titles/${encodeURIComponent(d.kind)}/${encodeURIComponent(d.id)}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ genreIds: genreIds })
                });
                const result = await response.json();
                if (!response.ok || result.success === false) {
                    Toast.error(result.error || 'Failed to save genres');
                    return;
                }
                Toast.success('Genres saved');
                const kind = d.kind, id = d.id;
                state.singleDetail = null;
                JMedia.XtreamCategories.loadSingleDetail(kind, id);
                JMedia.XtreamCategories.loadXtreamTitles();
            } catch (e) {
                console.error('[XtreamCats] Error saving genres:', e);
                Toast.error('Error saving genres');
            }
        },

        applySinglePrimary: async function() {
            console.log('[XtreamCats] applySinglePrimary called');
            if (state.selection.size === 0) {
                Toast.error('No titles selected');
                return;
            }
            const picker = document.getElementById('xtreamCatsMultiGenrePicker');
            const genreId = picker ? picker.value : '';
            if (!genreId) {
                Toast.error('Choose a genre first');
                return;
            }
            const ids = Array.from(state.selection).map(key => key.split(':')[1]);
            const body = { kind: state.type, ids: ids, genreId: genreId };
            try {
                const response = await fetch('/api/admin/xtream-categories/titles/bulk-primary', {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
                const result = await response.json();
                if (!response.ok || result.success === false) {
                    Toast.error(result.error || 'Failed to set primary genre');
                    return;
                }
                const updated = result.data && result.data.updated;
                Toast.success(`Primary genre set on ${updated !== undefined ? updated : ids.length} title(s)`);
                state.selection.clear();
                updateBulkBar();
                JMedia.XtreamCategories.loadXtreamTitles();
            } catch (e) {
                console.error('[XtreamCats] Error setting primary genre:', e);
                Toast.error('Error setting primary genre');
            }
        },

        setXtreamCatsType: function(type) {
            if (state.type === type) return;
            console.log('[XtreamCats] Switching type to:', type);
            state.type = type;
            state.offset = 0;
            state.selection.clear();
            updateBulkBar();
            const moviesBtn = document.getElementById('xtreamCatsTypeMoviesBtn');
            const seriesBtn = document.getElementById('xtreamCatsTypeSeriesBtn');
            if (moviesBtn) moviesBtn.classList.toggle('is-selected', type === 'movie');
            if (seriesBtn) seriesBtn.classList.toggle('is-selected', type === 'series');
            JMedia.XtreamCategories.loadXtreamTitles();
        },

        xtreamCatsPagePrev: function() {
            if (state.offset <= 0) return;
            state.offset = Math.max(0, state.offset - state.limit);
            JMedia.XtreamCategories.loadXtreamTitles();
        },

        xtreamCatsPageNext: function() {
            if (state.offset + state.limit >= state.total) return;
            state.offset += state.limit;
            JMedia.XtreamCategories.loadXtreamTitles();
        }
    };

    // Backward-compatible window aliases
    window.loadXtreamGenres = JMedia.XtreamCategories.loadXtreamGenres;
    window.loadXtreamTitles = JMedia.XtreamCategories.loadXtreamTitles;
    window.toggleXtreamSelect = JMedia.XtreamCategories.toggleXtreamSelect;
    window.selectAllVisibleXtream = JMedia.XtreamCategories.selectAllVisibleXtream;
    window.clearXtreamSelection = JMedia.XtreamCategories.clearXtreamSelection;
    window.loadSingleDetail = JMedia.XtreamCategories.loadSingleDetail;
    window.saveSingleDetail = JMedia.XtreamCategories.saveSingleDetail;
    window.applySinglePrimary = JMedia.XtreamCategories.applySinglePrimary;
    window.setXtreamCatsType = JMedia.XtreamCategories.setXtreamCatsType;
    window.xtreamCatsPagePrev = JMedia.XtreamCategories.xtreamCatsPagePrev;
    window.xtreamCatsPageNext = JMedia.XtreamCategories.xtreamCatsPageNext;

    // Settings views are fetched and injected long after page load (App.js),
    // so bind on document — per-element binding would miss the lazy DOM.
    document.addEventListener('click', (e) => {
        const card = e.target.closest ? e.target.closest('#xtreamCatsGrid .xtream-cat-card') : null;
        if (card) {
            onGridClick(e);
            return;
        }
        const rowBtn = e.target.closest ? e.target.closest('#xtreamCatsSingleGenres button[data-act]') : null;
        if (rowBtn) {
            const idx = parseInt(rowBtn.getAttribute('data-idx'), 10) || 0;
            const act = rowBtn.getAttribute('data-act');
            if (act === 'up') singleMove(idx, -1);
            else if (act === 'down') singleMove(idx, 1);
            else if (act === 'remove') singleRemove(idx);
            return;
        }
        const t = e.target.closest ? e.target.closest('button') : null;
        if (!t || !t.id) return;
        if (t.id === 'xtreamCatsRefreshBtn') {
            JMedia.XtreamCategories.loadXtreamGenres();
            JMedia.XtreamCategories.loadXtreamTitles();
        } else if (t.id === 'xtreamCatsSelectAllBtn') {
            JMedia.XtreamCategories.selectAllVisibleXtream();
        } else if (t.id === 'xtreamCatsClearBtn') {
            JMedia.XtreamCategories.clearXtreamSelection();
        } else if (t.id === 'xtreamCatsSingleAddBtn') {
            singleAdd();
        } else if (t.id === 'xtreamCatsSingleSaveBtn') {
            JMedia.XtreamCategories.saveSingleDetail();
        } else if (t.id === 'xtreamCatsMultiSetBtn') {
            JMedia.XtreamCategories.applySinglePrimary();
        }
    });
    document.addEventListener('input', (e) => {
        if (e.target && e.target.id === 'xtreamCatsSearch') onSearchInput();
    });
    document.addEventListener('change', (e) => {
        if (e.target && e.target.id === 'xtreamCatsGenreFilter') onGenreFilterChange();
    });

})(window);