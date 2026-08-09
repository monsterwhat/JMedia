/**
 * DjSettingsPanel - expandable DJ Mode settings panel
 * Expands from the #djModeIndicator strip. Per-profile persistence via
 * /api/music/playback/dj-settings/{profileId}.
 *
 * Survivability notes:
 * - DjTransitionManager.updateDjIndicator() resets the indicator's className
 *   and innerHTML on every transition state change, destroying any panel
 *   markup and the .expanded class. A MutationObserver re-asserts both while
 *   the panel is open.
 * - EventBindings clones #djModeBtn and attaches its own onclick, so clicks
 *   are handled via document-level delegation. PlaybackController.toggleDjMode
 *   flips djModeActive synchronously before this bubble listener runs, so the
 *   post-click state tells us whether the mode just turned on or off.
 */
(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};
    const doc = document;

    const DEFAULT_SETTINGS = {
        genrePool: [],
        songsPerGenre: 3,
        crossfade: -1,
        strictness: 'MEDIUM',
        bpmMin: 0,
        bpmMax: 0,
        maxConsecutiveByArtist: 0,
        enabled: true
    };

    const PANEL_HTML =
        '<div class="dj-settings-panel">' +
            '<div class="dj-settings-header">' +
                '<span class="dj-settings-title"><i class="pi pi-sliders-h"></i> DJ Settings</span>' +
                '<button type="button" class="dj-settings-close" data-dj-action="close" title="Close (Esc)"><i class="pi pi-times"></i></button>' +
            '</div>' +
            '<div class="dj-settings-body">' +
                '<div class="dj-settings-section">' +
                    '<span class="dj-settings-label">Genre Pool</span>' +
                    '<input type="text" id="djGenreFilter" class="dj-genre-filter" placeholder="Filter genres..." autocomplete="off" spellcheck="false">' +
                    '<div class="dj-settings-chips" id="djGenrePoolChips"><span class="dj-chip-loading">Loading genres...</span></div>' +
                '</div>' +
                '<div class="dj-settings-section">' +
                    '<span class="dj-settings-label">Songs per Genre</span>' +
                    '<div class="dj-stepper">' +
                        '<button type="button" class="dj-stepper-btn" data-dj-action="step" data-dj-step="-1" title="Decrease"><i class="pi pi-minus"></i></button>' +
                        '<span class="dj-stepper-value" id="djSongsPerGenreValue">3</span>' +
                        '<button type="button" class="dj-stepper-btn" data-dj-action="step" data-dj-step="1" title="Increase"><i class="pi pi-plus"></i></button>' +
                    '</div>' +
                '</div>' +
                '<div class="dj-settings-section">' +
                    '<span class="dj-settings-label">Max Songs per Artist <span class="dj-settings-hint">(0 = off)</span></span>' +
                    '<div class="dj-stepper">' +
                        '<button type="button" class="dj-stepper-btn" data-dj-action="stepArtist" data-dj-step="-1" title="Decrease"><i class="pi pi-minus"></i></button>' +
                        '<span class="dj-stepper-value" id="djMaxConsecutiveByArtistValue">0</span>' +
                        '<button type="button" class="dj-stepper-btn" data-dj-action="stepArtist" data-dj-step="1" title="Increase"><i class="pi pi-plus"></i></button>' +
                    '</div>' +
                '</div>' +
                '<div class="dj-settings-section">' +
                    '<span class="dj-settings-label">Crossfade Override</span>' +
                    '<div class="dj-segmented" data-dj-field="crossfade">' +
                        '<button type="button" class="dj-seg-btn" data-dj-value="-1">Auto</button>' +
                        '<button type="button" class="dj-seg-btn" data-dj-value="0">0s</button>' +
                        '<button type="button" class="dj-seg-btn" data-dj-value="3">3s</button>' +
                        '<button type="button" class="dj-seg-btn" data-dj-value="5">5s</button>' +
                        '<button type="button" class="dj-seg-btn" data-dj-value="10">10s</button>' +
                    '</div>' +
                '</div>' +
                '<div class="dj-settings-section">' +
                    '<span class="dj-settings-label">Match Strictness</span>' +
                    '<div class="dj-segmented" data-dj-field="strictness">' +
                        '<button type="button" class="dj-seg-btn" data-dj-value="LOW">Relaxed</button>' +
                        '<button type="button" class="dj-seg-btn" data-dj-value="MEDIUM">Medium</button>' +
                        '<button type="button" class="dj-seg-btn" data-dj-value="HIGH">Strict</button>' +
                    '</div>' +
                '</div>' +
                '<div class="dj-settings-section">' +
                    '<span class="dj-settings-label">BPM Range <span class="dj-settings-hint">(0 = auto)</span></span>' +
                    '<div class="dj-bpm-row">' +
                        '<input type="number" id="djBpmMin" class="dj-number-input" min="0" max="400" placeholder="Min">' +
                        '<span class="dj-bpm-sep">&ndash;</span>' +
                        '<input type="number" id="djBpmMax" class="dj-number-input" min="0" max="400" placeholder="Max">' +
                    '</div>' +
                '</div>' +
            '</div>' +
            '<div class="dj-settings-footer">' +
                '<label class="switch dj-settings-switch" title="Enable / disable DJ Mode">' +
                    '<input type="checkbox" id="djEnabledToggle" checked>' +
                    '<span class="check"></span>' +
                '</label>' +
                '<span class="dj-settings-footer-label" id="djEnabledLabel">Enable DJ Mode</span>' +
                '<span class="dj-settings-save-state" id="djSettingsSaveState"></span>' +
                '<button type="button" class="dj-settings-reset" data-dj-action="reset"><i class="pi pi-refresh"></i> Reset</button>' +
            '</div>' +
        '</div>';

    const state = {
        open: false,
        settings: Object.assign({}, DEFAULT_SETTINGS),
        genreList: [],
        saveTimer: null,
        saveScheduled: false,
        saveStateClear: null,
        genresLoaded: false,
        genreFilter: '',
        touched: {},
        saving: false,
        saveQueued: false
    };

    function getIndicator() {
        return doc.getElementById('djModeIndicator');
    }

    function getPanel() {
        const indicator = getIndicator();
        return indicator ? indicator.querySelector('#djSettingsPanel') : null;
    }

    function getProfileId() {
        if (JMedia.Helpers && typeof JMedia.Helpers.getActiveProfileId === 'function') {
            return JMedia.Helpers.getActiveProfileId();
        }
        return window.globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    }

    function log() {
        if (window.Helpers && typeof window.Helpers.log === 'function') {
            window.Helpers.log.apply(window.Helpers, ['[DjSettingsPanel]'].concat(Array.prototype.slice.call(arguments)));
        }
    }

    function debounce(func, delay) {
        let timeoutId;
        return function() {
            const args = arguments;
            const context = this;
            clearTimeout(timeoutId);
            timeoutId = setTimeout(() => func.apply(context, args), delay);
        };
    }

    function apiLoadSettings() {
        return fetch('/api/music/playback/dj-settings/' + getProfileId(), { method: 'GET' })
            .then((res) => {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                return res.json();
            });
    }

    function apiSaveSettings(settings) {
        return fetch('/api/music/playback/dj-settings/' + getProfileId(), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(settings)
        }).then((res) => {
            if (!res.ok) throw new Error('HTTP ' + res.status);
            return res.json();
        });
    }

    function apiSetDjMode(on) {
        return fetch('/api/music/playback/dj-mode-set/' + getProfileId() + '/' + (on ? 'true' : 'false'), { method: 'POST' })
            .then((res) => {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                return res.json();
            });
    }

    function apiLoadGenres() {
        return fetch('/api/music/ui/genres', { method: 'GET' })
            .then((res) => {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                return res.json();
            });
    }

    function mergeSettings(raw) {
        // The backend wraps DJ settings in an ApiResponse envelope
        // ({ success, data: { ...settings } }) - unwrap it defensively so a
        // bare settings object also works.
        const payload = raw && raw.data && typeof raw.data === 'object' ? raw.data : raw;
        const merged = Object.assign({}, DEFAULT_SETTINGS);
        if (payload && typeof payload === 'object') {
            if (Array.isArray(payload.genrePool)) merged.genrePool = payload.genrePool.slice();
            if (typeof payload.songsPerGenre === 'number' && isFinite(payload.songsPerGenre)) merged.songsPerGenre = payload.songsPerGenre;
            if (typeof payload.crossfade === 'number' && isFinite(payload.crossfade)) merged.crossfade = payload.crossfade;
            if (typeof payload.strictness === 'string' && payload.strictness) merged.strictness = payload.strictness;
            if (typeof payload.bpmMin === 'number' && isFinite(payload.bpmMin)) merged.bpmMin = payload.bpmMin;
            if (typeof payload.bpmMax === 'number' && isFinite(payload.bpmMax)) merged.bpmMax = payload.bpmMax;
            if (typeof payload.maxConsecutiveByArtist === 'number' && isFinite(payload.maxConsecutiveByArtist)) merged.maxConsecutiveByArtist = payload.maxConsecutiveByArtist;
            if (typeof payload.enabled === 'boolean') merged.enabled = payload.enabled;
        }
        return merged;
    }

    function syncState() {
        if (!window.StateManager) return;
        window.StateManager.updateState({
            djGenrePool: state.settings.genrePool.slice(),
            djSongsPerGenre: state.settings.songsPerGenre,
            djCrossfadeOverride: state.settings.crossfade,
            djStrictness: state.settings.strictness,
            djBpmMin: state.settings.bpmMin,
            djBpmMax: state.settings.bpmMax,
            djMaxConsecutiveByArtist: state.settings.maxConsecutiveByArtist
        }, 'djSettingsPanel');
    }

    function ensurePanel() {
        const indicator = getIndicator();
        if (!indicator) return null;
        let panel = indicator.querySelector('#djSettingsPanel');
        if (!panel) {
            const wrapper = doc.createElement('div');
            wrapper.innerHTML = PANEL_HTML;
            panel = wrapper.firstElementChild;
            panel.id = 'djSettingsPanel';
            indicator.appendChild(panel);
        }
        return panel;
    }

    function setSaveState(text, cssClass) {
        const panel = getPanel();
        if (!panel) return;
        const el = panel.querySelector('#djSettingsSaveState');
        if (!el) return;
        clearTimeout(state.saveStateClear);
        el.textContent = text || '';
        el.className = 'dj-settings-save-state' + (cssClass ? ' ' + cssClass : '');
        if (text) {
            state.saveStateClear = setTimeout(() => {
                el.textContent = '';
                el.className = 'dj-settings-save-state';
            }, cssClass === 'failed' ? 3500 : 1800);
        }
    }

    function persist() {
        // Serialize saves: never overlap POSTs. If a save is in flight, queue
        // the latest state and send it only after the in-flight one completes,
        // so out-of-order completion can never write stale values back.
        if (state.saving) {
            state.saveQueued = true;
            return;
        }
        state.saving = true;
        state.saveQueued = false;
        setSaveState('Saving...', 'saving');
        apiSaveSettings(state.settings).then(() => {
            state.saving = false;
            if (state.saveQueued) {
                persist();
                return;
            }
            syncState();
            setSaveState('Saved', 'saved');
        }).catch((err) => {
            log('Save failed:', err);
            state.saving = false;
            state.saveQueued = false;
            setSaveState('Save failed', 'failed');
        });
    }

    function saveNow() {
        const panel = getPanel();
        if (!panel || !state.open) return;
        persist();
    }

    function scheduleSave() {
        state.saveScheduled = true;
        clearTimeout(state.saveTimer);
        state.saveTimer = setTimeout(() => {
            state.saveScheduled = false;
            saveNow();
        }, 400);
    }

    function flushPendingSave() {
        if (!state.saveScheduled) return;
        clearTimeout(state.saveTimer);
        state.saveTimer = null;
        state.saveScheduled = false;
        persist();
    }

    function render() {
        const panel = getPanel();
        if (!panel || !state.open) return;

        const stepperValue = panel.querySelector('#djSongsPerGenreValue');
        if (stepperValue) stepperValue.textContent = state.settings.songsPerGenre;

        const artistStepperValue = panel.querySelector('#djMaxConsecutiveByArtistValue');
        if (artistStepperValue) artistStepperValue.textContent = state.settings.maxConsecutiveByArtist;

        panel.querySelectorAll('.dj-segmented').forEach((container) => {
            const field = container.getAttribute('data-dj-field');
            container.querySelectorAll('.dj-seg-btn').forEach((btn) => {
                btn.classList.toggle('selected', String(state.settings[field]) === String(btn.getAttribute('data-dj-value')));
            });
        });

        const minInput = panel.querySelector('#djBpmMin');
        const maxInput = panel.querySelector('#djBpmMax');
        if (minInput) minInput.value = state.settings.bpmMin || '';
        if (maxInput) maxInput.value = state.settings.bpmMax || '';

        const enabledToggle = panel.querySelector('#djEnabledToggle');
        if (enabledToggle) enabledToggle.checked = !!state.settings.enabled;

        const enabledLabel = panel.querySelector('#djEnabledLabel');
        if (enabledLabel) enabledLabel.textContent = state.settings.enabled ? 'DJ Mode Enabled' : 'Enable DJ Mode';

        panel.classList.toggle('dj-settings-off', !state.settings.enabled);

        renderChips();
    }

    // Client-side similarity heuristic: "Rock" ~ "Classic Rock" (token
    // containment), "Metal" ~ "Metall" (fuzzy), "Rock" ~ "Jazz" never matches.
    function isSimilarGenre(genre, pool) {
        const norm = (name) => name.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
        const tokens = norm(genre).split(' ').filter(Boolean);
        if (!tokens.length) return false;

        return pool.some((poolGenre) => {
            const poolTokens = norm(poolGenre).split(' ').filter(Boolean);
            if (poolTokens.join(' ') === tokens.join(' ')) return false;

            const shared = poolTokens.filter((t) => tokens.indexOf(t) !== -1).length;
            // One token set fully contained in the other
            if (shared && (shared === poolTokens.length || shared === tokens.length)) return true;
            // Jaccard overlap of token sets
            if (shared / new Set(poolTokens.concat(tokens)).size >= 0.4) return true;
            // Single-token fuzzy match
            if (tokens.length === 1 && poolTokens.length === 1) {
                return levenshteinSimilarity(tokens[0], poolTokens[0]) >= 0.8;
            }
            return false;
        });
    }

    function levenshteinSimilarity(a, b) {
        const dp = Array.from({ length: a.length + 1 }, (_, i) => [i]);
        for (let j = 1; j <= b.length; j++) dp[0][j] = j;
        for (let i = 1; i <= a.length; i++) {
            for (let j = 1; j <= b.length; j++) {
                dp[i][j] = Math.min(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1)
                );
            }
        }
        return 1 - dp[a.length][b.length] / Math.max(a.length, b.length);
    }

    function renderChips() {
        const panel = getPanel();
        if (!panel) return;
        const chipsBox = panel.querySelector('#djGenrePoolChips');
        if (!chipsBox) return;

        if (!state.genresLoaded) {
            chipsBox.innerHTML = '<span class="dj-chip-loading">Loading genres...</span>';
            return;
        }
        if (!state.genreList.length) {
            chipsBox.innerHTML = '<span class="dj-chip-loading">No genres available</span>';
            return;
        }

        const filter = (state.genreFilter || '').trim().toLowerCase();
        const pool = state.settings.genrePool || [];
        const visible = state.genreList.filter((g) => !filter || g.toLowerCase().includes(filter));

        if (!visible.length) {
            chipsBox.innerHTML = '<span class="dj-chip-loading">No genres match</span>';
            return;
        }

        chipsBox.innerHTML = '';
        visible.forEach((genre) => {
            const chip = doc.createElement('button');
            chip.type = 'button';
            const inPool = pool.indexOf(genre) !== -1;
            const similar = !inPool && isSimilarGenre(genre, pool);
            chip.className = 'dj-chip' + (inPool ? ' selected' : (similar ? ' similar' : ''));
            chip.setAttribute('data-genre', genre);
            chip.textContent = genre;
            chipsBox.appendChild(chip);
        });
    }

    function open() {
        if (state.open) return;
        const indicator = getIndicator();
        if (!indicator) return;

        state.open = true;
        state.touched = {};
        state.settings = Object.assign({}, DEFAULT_SETTINGS);
        state.genresLoaded = false;
        state.genreFilter = '';

        ensurePanel();
        indicator.classList.add('expanded');
        render();

        apiLoadSettings().then((raw) => {
            // Merge server state without clobbering fields the user already
            // edited since the panel opened (async GET may resolve after the
            // user started clicking). Server wins for untouched fields; local
            // edits win for touched ones.
            const server = mergeSettings(raw);
            Object.keys(server).forEach((key) => {
                if (!state.touched[key]) {
                    state.settings[key] = server[key];
                }
            });
            syncState();
            render();
        }).catch((err) => {
            log('Load failed, using defaults:', err);
            syncState();
            if (window.showToast) {
                window.showToast('Failed to load DJ settings', 'error', 4000);
            }
        });

        if (!state.genreList.length) {
            apiLoadGenres().then((genres) => {
                state.genreList = Array.isArray(genres) ? genres : [];
                state.genresLoaded = true;
                render();
            }).catch((err) => {
                log('Genre load failed:', err);
                state.genreList = [];
                state.genresLoaded = true;
                render();
            });
        } else {
            state.genresLoaded = true;
        }
    }

    function close() {
        if (!state.open) return;
        flushPendingSave();
        state.open = false;
        const indicator = getIndicator();
        if (indicator) {
            indicator.classList.remove('expanded');
            const modeOn = window.StateManager ? !!window.StateManager.getState().djModeActive : false;
            if (!modeOn) {
                indicator.classList.add('is-hidden');
                indicator.style.display = 'none';
            }
        }
    }

    function isOpen() {
        return state.open;
    }

    function toggle() {
        if (state.open) {
            close();
        } else {
            open();
        }
    }

    function adjustSongsPerGenre(delta) {
        const next = Math.max(0, Math.min(10, state.settings.songsPerGenre + delta));
        if (next === state.settings.songsPerGenre) return;
        state.settings.songsPerGenre = next;
        state.touched.songsPerGenre = true;
        render();
        scheduleSave();
    }

    function adjustMaxConsecutiveByArtist(delta) {
        const next = Math.max(0, Math.min(10, state.settings.maxConsecutiveByArtist + delta));
        if (next === state.settings.maxConsecutiveByArtist) return;
        state.settings.maxConsecutiveByArtist = next;
        state.touched.maxConsecutiveByArtist = true;
        render();
        scheduleSave();
    }

    function setField(field, value) {
        if (state.settings[field] === value) return;
        state.settings[field] = value;
        state.touched[field] = true;
        render();
        scheduleSave();
    }

    function toggleGenre(genre) {
        const pool = state.settings.genrePool;
        const index = pool.indexOf(genre);
        if (index !== -1) {
            pool.splice(index, 1);
        } else {
            pool.push(genre);
        }
        state.touched.genrePool = true;
        renderChips();
        scheduleSave();
    }

    function resetToDefaults() {
        const modeOn = window.StateManager ? !!window.StateManager.getState().djModeActive : true;
        state.settings = Object.assign({}, DEFAULT_SETTINGS, { enabled: modeOn });
        state.touched = {};
        Object.keys(DEFAULT_SETTINGS).forEach((k) => { state.touched[k] = true; });
        render();
        saveNow();
    }

    function onEnabledToggle(checked) {
        state.settings.enabled = checked;
        state.touched.enabled = true;
        if (window.StateManager) {
            const currentMode = !!window.StateManager.getState().djModeActive;
            if (checked !== currentMode && window.PlaybackController) {
                window.PlaybackController.toggleDjMode(getProfileId());
            }
        }
        scheduleSave();
        render();
    }

    function handlePanelClick(e) {
        const actionEl = e.target.closest('[data-dj-action]');
        if (actionEl) {
            const action = actionEl.getAttribute('data-dj-action');
            if (action === 'close') {
                close();
            } else if (action === 'step') {
                adjustSongsPerGenre(parseInt(actionEl.getAttribute('data-dj-step'), 10) || 0);
            } else if (action === 'stepArtist') {
                adjustMaxConsecutiveByArtist(parseInt(actionEl.getAttribute('data-dj-step'), 10) || 0);
            } else if (action === 'reset') {
                resetToDefaults();
            }
            return;
        }

        const chip = e.target.closest('.dj-chip[data-genre]');
        if (chip) {
            toggleGenre(chip.getAttribute('data-genre'));
            return;
        }

        const segBtn = e.target.closest('.dj-seg-btn');
        if (segBtn) {
            const container = segBtn.closest('[data-dj-field]');
            if (container) {
                const field = container.getAttribute('data-dj-field');
                const raw = segBtn.getAttribute('data-dj-value');
                setField(field, field === 'crossfade' ? parseInt(raw, 10) : raw);
            }
            return;
        }
    }

    function onClick(e) {
        const panel = getPanel();
        if (panel && panel.contains(e.target)) {
            handlePanelClick(e);
            return;
        }

        if (e.target.closest('#djModeBtn')) {
            const modeOn = window.StateManager ? !!window.StateManager.getState().djModeActive : false;
            if (modeOn) {
                open();
            } else {
                close();
            }
            return;
        }

        if (e.target.closest('#djModeIndicator')) {
            toggle();
            return;
        }

        if (state.open) {
            close();
        }
    }

    function onInput(e) {
        if (e.target.id === 'djGenreFilter') {
            state.genreFilter = e.target.value;
            renderChips();
            return;
        }
        if (!state.open || !e.target.classList || !e.target.classList.contains('dj-number-input')) return;
        const id = e.target.id;
        if (id !== 'djBpmMin' && id !== 'djBpmMax') return;
        const value = Math.max(0, Math.min(400, parseInt(e.target.value, 10) || 0));
        if (id === 'djBpmMin') {
            state.settings.bpmMin = value;
            state.touched.bpmMin = true;
        } else {
            state.settings.bpmMax = value;
            state.touched.bpmMax = true;
        }
        scheduleSave();
    }

    function onChange(e) {
        if (!state.open) return;
        if (e.target.id === 'djEnabledToggle') {
            onEnabledToggle(e.target.checked);
        }
    }

    function onKeyDown(e) {
        if (e.key !== 'Escape' || !state.open) return;
        if (doc.activeElement && doc.activeElement.id === 'djGenreFilter' && state.genreFilter) {
            state.genreFilter = '';
            renderChips();
            return;
        }
        close();
    }

    function onIndicatorMutation() {
        if (!state.open) return;
        const indicator = getIndicator();
        if (!indicator) return;

        if (window.StateManager) {
            const djActive = !!window.StateManager.getState().djModeActive;
            if (state.settings.enabled !== djActive) {
                state.settings.enabled = djActive;
                render();
            }
        }

        if (indicator.classList.contains('is-hidden')) {
            const djActive = window.StateManager ? !!window.StateManager.getState().djModeActive : false;
            if (djActive) {
                close();
                return;
            }
            indicator.classList.remove('is-hidden');
            indicator.style.display = 'flex';
        }

        if (!indicator.classList.contains('expanded')) {
            indicator.classList.add('expanded');
        }

        if (!indicator.querySelector('#djSettingsPanel')) {
            ensurePanel();
        }
        render();
    }

    const DjSettingsPanel = {
        open: open,
        close: close,
        toggle: toggle,
        isOpen: isOpen,
        getSettings: function() {
            return Object.assign({}, state.settings);
        }
    };

    JMedia.DjSettingsPanel = DjSettingsPanel;
    window.DjSettingsPanel = DjSettingsPanel;

    function init() {
        const indicator = getIndicator();
        if (indicator) {
            const observer = new MutationObserver(onIndicatorMutation);
            observer.observe(indicator, {
                attributes: true,
                attributeFilter: ['class', 'style'],
                childList: true
            });
        }

        doc.addEventListener('click', onClick, false);
        doc.addEventListener('input', onInput, false);
        doc.addEventListener('change', onChange, false);
        doc.addEventListener('keydown', onKeyDown, false);
    }

    init();

})(window);
