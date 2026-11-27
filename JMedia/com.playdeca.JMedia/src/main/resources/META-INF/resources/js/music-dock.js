/**
 * music-dock.js — Music Dock page enhancements (loaded only by music-dock.html)
 *   • Drives the ultrawide hero from the currently playing song
 *   • Toggles the glass search shell (dock-search-shell) via the dock search pill
 */
(function(window) {
    'use strict';

    var hero = null;
    var heroArtwork = null;
    var heroBackdrop = null;
    var heroTitle = null;
    var heroArtist = null;
    var heroBadge = null;

    function getArtworkUrl(state) {
        if (state && state.currentSongData && state.currentSongData.id) {
            return '/api/music/stream/artwork/' + state.currentSongData.id;
        }
        if (state && state.currentSongId) {
            return '/api/music/stream/artwork/' + state.currentSongId;
        }
        return null;
    }

    var lastHero = { title: '', artist: '', artwork: '' };

    function updateHero() {
        if (!hero || !window.StateManager) return;

        var state = (typeof window.StateManager.getState === 'function')
            ? window.StateManager.getState()
            : window.StateManager.state;
        if (!state) return;

        /* A late/partial state update used to blank the hero while the audio kept
           playing. Treat "audio is playing" as a song, and keep the last good
           title/artist/artwork instead of falling back to the empty state. */
        var audio = document.getElementById('audioPlayer');
        var audioLive = !!audio && !audio.paused && !!audio.currentSrc;

        var hasSong = !!state.currentSongId || !!state.songName;
        var artworkUrl = getArtworkUrl(state);
        var titleText = hasSong && state.songName ? state.songName : '';
        var artistText = hasSong ? (state.artistName || state.artist || 'Unknown Artist') : '';

        if (hasSong) {
            lastHero.title = titleText;
            lastHero.artist = artistText;
            lastHero.artwork = artworkUrl || lastHero.artwork;
        } else if (audioLive && lastHero.title) {
            hasSong = true;
            titleText = lastHero.title;
            artistText = lastHero.artist || 'Unknown Artist';
            artworkUrl = lastHero.artwork;
        } else {
            titleText = 'Nothing Playing';
            artistText = 'Unknown Artist';
        }

        artworkUrl = artworkUrl || '/logo.png';

        if (heroArtwork) heroArtwork.src = artworkUrl;
        if (heroBackdrop) heroBackdrop.src = artworkUrl;
        if (heroTitle) heroTitle.textContent = titleText;
        if (heroArtist) heroArtist.textContent = artistText;
        if (heroBadge) heroBadge.style.display = hasSong ? '' : 'none';

        /* The logo is a wordmark: as a blurred full-band backdrop it reads as a
           gigantic logo. Empty state gets a neutral gradient instead. */
        if (hero) hero.classList.toggle('is-empty', !hasSong);

        /* Keep the dock pill's title/artist marquee-aware (UIUpdater also writes
           these elements; same text, so no conflict). */
        applyDockMarquee('songTitle', titleText);
        applyDockMarquee('songArtist', artistText);
    }

    var searchDebounce = null;

    /* Hide the whole dock while the page is at the top (hero visible).
       body.dock-at-top is consumed by music-dock.css to slide the container out. */
    var DOCK_AT_TOP_PX = 80;

    function updateDockVisibility() {
        var atTop = (window.scrollY || window.pageYOffset) <= DOCK_AT_TOP_PX;
        document.body.classList.toggle('dock-at-top', atTop);
    }

    function setupScrollListener() {
        var ticking = false;
        window.addEventListener('scroll', function() {
            if (!ticking) {
                window.requestAnimationFrame(function() {
                    updateDockVisibility();
                    ticking = false;
                });
                ticking = true;
            }
        }, { passive: true });
        updateDockVisibility();
    }

    /* Sync the dock pill's title/artist with the marquee helper (which toggles
       .marquee / .no-scroll) and expose the overflow distance for the CSS slide. */
    function applyDockMarquee(elementId, text) {
        if (!window.Helpers) return;
        window.Helpers.applyMarqueeEffect(elementId, text);
        var el = document.getElementById(elementId);
        if (el && el.classList.contains('marquee')) {
            var dist = el.scrollWidth - el.clientWidth + 32;
            el.style.setProperty('--marquee-dist', dist + 'px');
        }
    }

    function closeSearch() {
        document.body.classList.remove('dock-search-open');
        var btn = document.getElementById('dockSearchBtn');
        if (btn) btn.classList.remove('is-active');
        var input = document.getElementById('dockSearchInput');
        if (input) input.value = '';
        var dropdown = document.getElementById('dockSearchDropdown');
        if (dropdown) {
            dropdown.classList.remove('visible');
            dropdown.innerHTML = '';
        }
    }


    /* ---------------- Sort bar (title/date added, asc/desc) ---------------- */
    var dockSort = {
        by: localStorage.getItem('dockSortBy') || 'title',
        dir: localStorage.getItem('dockSortDir') || 'asc'
    };

    function renderSortBar() {
        var dirIcon = document.getElementById('dockSortDirIcon');
        if (dirIcon) {
            dirIcon.className = dockSort.dir === 'asc'
                ? 'pi pi-sort-amount-up-alt'
                : 'pi pi-sort-amount-down';
        }
        var fieldIcon = document.getElementById('dockSortFieldIcon');
        if (fieldIcon) {
            fieldIcon.className = dockSort.by === 'title' ? 'pi pi-sort-alpha-down' : 'pi pi-clock';
        }
        var label = document.getElementById('dockSortFieldLabel');
        if (label) label.textContent = dockSort.by === 'title' ? 'A–Z' : 'Newest';
    }

    /* The playlist currently shown in #mobileSongList. MusicIndex keeps this in
       window.mobileSongListState; the DOM attribute is only a fallback, because
       an empty (or freshly swapped) list has no item to read it from and the old
       code silently fell back to 0 = the whole library. */
    function currentPlaylistId(list) {
        var state = window.mobileSongListState;
        if (state && state.view === 'list' && state.playlistId !== undefined && state.playlistId !== null) {
            return String(state.playlistId);
        }
        var first = list ? list.querySelector('.mobile-song-item') : null;
        if (first) {
            var attr = first.getAttribute('data-playlist-id');
            if (attr !== null && attr !== '') return attr;
        }
        return '0';
    }

    function applySort() {
        var profileId = activeProfileId();
        var list = document.getElementById('mobileSongList');
        if (!profileId || !list || !window.htmx) return;

        var playlistId = currentPlaylistId(list);
        var url = '/api/music/ui/mobile-tbody/' + profileId + '/' + playlistId +
            '?page=1&limit=50&sortBy=' + encodeURIComponent(dockSort.by) +
            '&sortDirection=' + encodeURIComponent(dockSort.dir) +
            (alphaActive ? '&letter=' + encodeURIComponent(alphaActive) : '');

        window.htmx.ajax('GET', url, { target: list, swap: 'innerHTML' });
    }

    /* ---------------- A–Z jump rail ----------------
       Built from the songs currently rendered (infinite scroll adds more over
       time). Letters with no loaded song are dimmed; tapping scrolls to the
       first match. */
    var ALPHA_LETTERS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('');
    var alphaCounts = null;   // { A: 412, ... } from the server, null until loaded
    var alphaActive = '';     // letter currently filtering the list ('' = everything)
    var alphaScrub = null;    // live press-and-hold gesture

    /* Server-side filter: re-render the list for one letter (or the whole library). */
    function requestLetter(letter) {
        var profileId = activeProfileId();
        var list = document.getElementById('mobileSongList');
        if (!profileId || !list || !window.htmx) return;

        var playlistId = currentPlaylistId(list);

        var url = '/api/music/ui/mobile-tbody/' + profileId + '/' + playlistId +
            '?page=1&limit=50&sortBy=' + encodeURIComponent(dockSort.by) +
            '&sortDirection=' + encodeURIComponent(dockSort.dir) +
            (letter ? '&letter=' + encodeURIComponent(letter) : '');

        window.htmx.ajax('GET', url, { target: list, swap: 'innerHTML' });
    }

    function applyLetter(letter) {
        var next = (letter && letter !== alphaActive) ? letter : '';
        alphaActive = next;
        renderAlphaRail();
        requestLetter(next);
        if (document.body.classList.contains('dock-search-open')) {
            document.body.classList.remove('dock-search-open');
        }
    }

    function loadLetterCounts() {
        var profileId = activeProfileId();
        if (!profileId) return;

        fetch('/api/music/ui/mobile-letter-counts/' + encodeURIComponent(profileId), {
            credentials: 'same-origin',
            headers: { 'Accept': 'application/json' }
        }).then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        }).then(function (data) {
            alphaCounts = data || {};
            renderAlphaRail();
        }).catch(function (err) {
            console.warn('[MusicDock] letter counts unavailable, showing every letter:', err);
        });
    }

    function renderAlphaRail() {
        var rail = document.getElementById('dockAlpha');
        if (!rail) return;

        var letters = ALPHA_LETTERS.concat(['#']);
        if (rail.children.length !== letters.length) {
            rail.innerHTML = '';
            letters.forEach(function (letter) {
                var btn = document.createElement('button');
                btn.type = 'button';
                btn.className = 'dock-alpha-btn';
                btn.dataset.letter = letter;
                btn.textContent = letter;
                btn.setAttribute('aria-label', 'Show songs starting with ' + letter);
                btn.addEventListener('click', function (e) {
                    /* Pointer taps are handled by the scrub gesture (e.detail > 0);
                       this keeps keyboard activation working. */
                    if (e.detail !== 0) return;
                    applyLetter(letter);
                });
                rail.appendChild(btn);
            });
            bindAlphaScrub(rail);
        }

        letters.forEach(function (letter, i) {
            var btn = rail.children[i];
            if (!btn) return;
            var count = alphaCounts ? (alphaCounts[letter] || 0) : null;
            btn.classList.toggle('is-empty', count === 0);
            btn.classList.toggle('is-active', letter === alphaActive);
        });
    }

    /* ---------------- press-and-hold scrub ---------------- */

    function alphaStep(rail) {
        var btns = rail.children;
        if (!btns.length) return 0;
        if (btns.length < 2) return btns[0].getBoundingClientRect().height || 14;
        var step = btns[1].getBoundingClientRect().top - btns[0].getBoundingClientRect().top;
        return step > 0 ? step : 14;
    }

    function alphaIndexForY(rail, clientY) {
        var btns = rail.children;
        if (!btns.length) return 0;
        var first = btns[0].getBoundingClientRect();
        var idx = Math.round((clientY - (first.top + first.height / 2)) / alphaStep(rail));
        return Math.max(0, Math.min(btns.length - 1, idx));
    }

    function alphaClamp(rail, value) {
        return Math.max(0, Math.min(rail.children.length - 1, value));
    }

    function alphaBubble() {
        var bubble = document.getElementById('dockAlphaBubble');
        if (!bubble) {
            bubble = document.createElement('div');
            bubble.id = 'dockAlphaBubble';
            bubble.className = 'dock-alpha-bubble';
            bubble.setAttribute('aria-hidden', 'true');
            document.body.appendChild(bubble);
        }
        return bubble;
    }

    function showAlphaBubble(letter, clientY) {
        var rail = document.getElementById('dockAlpha');
        var bubble = alphaBubble();
        bubble.textContent = letter;
        bubble.classList.toggle('is-empty', !!(alphaCounts && !alphaCounts[letter]));

        var size = bubble.offsetWidth || 66;
        var railLeft = rail ? rail.getBoundingClientRect().left : window.innerWidth;
        /* left/top are the bubble's centre (CSS translate(-50%, -50%)). */
        var x = Math.max(size / 2 + 10, railLeft - size / 2 - 12);
        var y = Math.min(window.innerHeight - size / 2 - 10, Math.max(size / 2 + 10, clientY));
        bubble.style.left = x + 'px';
        bubble.style.top = y + 'px';
        bubble.classList.add('is-visible');
    }

    function hideAlphaBubble() {
        var bubble = document.getElementById('dockAlphaBubble');
        if (bubble) bubble.classList.remove('is-visible');
    }

    function paintAlphaScrub() {
        if (!alphaScrub) return;
        var rail = alphaScrub.rail;
        var idx = alphaClamp(rail, Math.round(alphaScrub.pos));
        var btn = rail.children[idx];
        if (!btn) return;

        if (alphaScrub.pressed !== btn) {
            if (alphaScrub.pressed) alphaScrub.pressed.classList.remove('is-pressed');
            btn.classList.add('is-pressed');
            alphaScrub.pressed = btn;
        }

        if (alphaScrub.holding) {
            showAlphaBubble(btn.dataset.letter, alphaScrub.lastY);
        }
    }

    function bindAlphaScrub(rail) {
        if (rail._alphaScrubBound) return;
        rail._alphaScrubBound = true;

        rail.addEventListener('pointerdown', function (e) {
            if (typeof e.button === 'number' && e.button > 0) return;
            e.preventDefault();

            alphaScrub = {
                rail: rail,
                pos: alphaIndexForY(rail, e.clientY),
                lastY: e.clientY,
                moved: false,
                holding: false,
                pressed: null,
                pointerId: e.pointerId,
                timer: window.setTimeout(function () {
                    if (!alphaScrub) return;
                    alphaScrub.holding = true;
                    rail.classList.add('is-scrubbing');
                    paintAlphaScrub();
                }, 110)
            };

            try {
                rail.setPointerCapture(e.pointerId);
            } catch (err) {
                console.debug('[MusicDock] pointer capture unavailable for the A-Z rail:', err);
            }

            paintAlphaScrub();
        });

        rail.addEventListener('pointermove', function (e) {
            var scrub = alphaScrub;
            if (!scrub || e.pointerId !== scrub.pointerId) return;
            e.preventDefault();

            var dy = e.clientY - scrub.lastY;
            scrub.lastY = e.clientY;
            if (Math.abs(dy) > 0.5) scrub.moved = true;

            /* Sliding away from the rail (to the left) lowers the gain, so the
               same finger travel covers fewer letters — fine-grained picking. */
            var leftness = Math.max(0, rail.getBoundingClientRect().right - e.clientX);
            var gain = 1 / (1 + leftness / 36);

            scrub.pos = alphaClamp(rail, scrub.pos + (dy / alphaStep(rail)) * gain);
            paintAlphaScrub();
        });

        function endScrub(e) {
            var scrub = alphaScrub;
            if (!scrub || (e && typeof e.pointerId === 'number' && e.pointerId !== scrub.pointerId)) return;

            alphaScrub = null;
            window.clearTimeout(scrub.timer);
            if (scrub.pressed) scrub.pressed.classList.remove('is-pressed');
            rail.classList.remove('is-scrubbing');
            hideAlphaBubble();

            try {
                rail.releasePointerCapture(scrub.pointerId);
            } catch (err) {
                console.debug('[MusicDock] pointer capture already released:', err);
            }

            var btn = rail.children[alphaClamp(rail, Math.round(scrub.pos))];
            if (!btn) return;
            var letter = btn.dataset.letter;
            if (btn.classList.contains('is-empty') && letter !== alphaActive) return;
            applyLetter(letter);
        }

        rail.addEventListener('pointerup', endScrub);
        rail.addEventListener('pointercancel', endScrub);
        rail.addEventListener('lostpointercapture', function (e) {
            /* Fired after an explicit release too; endScrub is a no-op by then. */
            if (alphaScrub) endScrub(e);
        });
    }

    function setupAlphaRail() {
        var attempts = 40;

        function attach() {
            var list = document.getElementById('mobileSongList');
            if (!list) {
                if (attempts-- <= 0) {
                    console.warn('[MusicDock] song list never appeared; A-Z rail disabled');
                    return;
                }
                window.setTimeout(attach, 250);
                return;
            }

            renderAlphaRail();
            loadLetterCounts();

            /* Anything else that reloads the list (a playlist, a tab, a search)
               drops the letter filter, otherwise the next sort would re-apply a
               letter that belonged to the view the user just left. */
            document.addEventListener('htmx:afterRequest', function (evt) {
                var xhr = evt.detail && evt.detail.xhr;
                var url = xhr && xhr.responseURL ? xhr.responseURL : '';
                if (!url || url.indexOf('mobile-tbody') === -1) return;
                if (url.indexOf('letter=') === -1 && alphaActive) {
                    alphaActive = '';
                    renderAlphaRail();
                }
            });

            if (typeof window.MutationObserver !== 'function') return;
            var pending = false;
            var observer = new window.MutationObserver(function () {
                if (pending) return;
                pending = true;
                window.requestAnimationFrame(function () {
                    pending = false;
                    renderAlphaRail();
                });
            });
            /* The list node itself is replaced when the view reloads, so watch the
               content container and re-derive everything on any change. */
            observer.observe(document.getElementById('app-content') || list,
                             { childList: true, subtree: true });
        }

        attach();
    }

    function setupSortBar() {
        var dirBtn = document.getElementById('dockSortDirBtn');
        if (dirBtn) {
            dirBtn.addEventListener('click', function() {
                dockSort.dir = dockSort.dir === 'asc' ? 'desc' : 'asc';
                try {
                    localStorage.setItem('dockSortDir', dockSort.dir);
                } catch (e) {
                    console.warn('[MusicDock] could not persist sort direction:', e && e.message ? e.message : e);
                }
                renderSortBar();
                applySort();
            });
        }

        var fieldBtn = document.getElementById('dockSortFieldBtn');
        if (fieldBtn) {
            fieldBtn.addEventListener('click', function() {
                dockSort.by = dockSort.by === 'title' ? 'dateAdded' : 'title';
                try {
                    localStorage.setItem('dockSortBy', dockSort.by);
                } catch (e) {
                    console.warn('[MusicDock] could not persist sort field:', e && e.message ? e.message : e);
                }
                renderSortBar();
                applySort();
            });
        }

        renderSortBar();
    }

    function setupSearchButton() {
        var btn = document.getElementById('dockSearchBtn');
        if (!btn) return;

        btn.addEventListener('click', function() {
            var open = document.body.classList.toggle('dock-search-open');
            btn.classList.toggle('is-active', open);
            if (open) {
                var input = document.getElementById('dockSearchInput');
                if (input) setTimeout(function() { input.focus(); }, 60);
            } else {
                closeSearch();
            }
        });

        var close = document.getElementById('dockSearchClose');
        if (close) close.addEventListener('click', closeSearch);

        document.addEventListener('keydown', function(e) {
            if (e.key === 'Escape' && document.body.classList.contains('dock-search-open')) {
                closeSearch();
            }
        });

        var input = document.getElementById('dockSearchInput');
        if (input && !input._dockSearchBound) {
            input._dockSearchBound = true;
            input.addEventListener('input', function() {
                clearTimeout(searchDebounce);
                searchDebounce = setTimeout(function() {
                    var query = input.value.trim();
                    var dropdown = document.getElementById('dockSearchDropdown');
                    if (!dropdown) return;
                    if (!query) {
                        dropdown.classList.remove('visible');
                        dropdown.innerHTML = '';
                        return;
                    }
                    var profileId = window.JMedia && JMedia.Helpers ? JMedia.Helpers.getActiveProfileId() : 1;
                    window.htmx.ajax('GET', '/api/music/ui/mobile-tbody/' + profileId + '/0?search=' + encodeURIComponent(query), {
                        target: '#dockSearchDropdown', swap: 'innerHTML'
                    });
                    dropdown.classList.add('visible');
                }, 500);
            });
        }

        /* Clicking a result plays it via PlaybackApi's document-level handler;
           then collapse the shell so the dock controls return. Only a real result
           may close it - on iOS a tap on the input/dropdown background was
           closing the search immediately. */
        var dropdown = document.getElementById('dockSearchDropdown');
        if (dropdown) {
            dropdown.addEventListener('click', function(e) {
                if (e.target.closest('.mobile-song-item, [data-song-id]')) {
                    setTimeout(closeSearch, 120);
                }
            });
        }
    }

    var dockNavSidebar = null;
    var dockNavBackdrop = null;

    function openDockNav() {
        if (dockNavSidebar) dockNavSidebar.classList.add('open');
        if (dockNavBackdrop) dockNavBackdrop.classList.add('active');
        var btn = document.getElementById('dockNavBtn');
        if (btn) btn.classList.add('active');
    }

    /* Browse -> a tab swaps the list but the page stayed where it was: from the
       full-screen hero the new list is entirely below the fold, and from further
       down the old offset landed you mid-list. Always bring the library top into
       view (the sort bar when it is shown, otherwise the content area). */
    function libraryTopOffset() {
        var bar = document.getElementById('dockSortBar');
        var target = (bar && bar.getBoundingClientRect().height > 0) ? bar : document.getElementById('app-content');
        if (!target) return null;
        /* The navbar is fixed: leave its height above the target, otherwise the
           sort bar lands underneath it. */
        var nav = document.querySelector('.cinema-nav');
        var navH = nav ? nav.getBoundingClientRect().height : 0;
        var top = target.getBoundingClientRect().top +
            (window.pageYOffset || document.documentElement.scrollTop || 0) - navH;
        return Math.max(0, top);
    }

    function scrollToLibraryTop() {
        var top = libraryTopOffset();
        if (top === null || top <= 1) return;
        try {
            window.scrollTo({ top: top, behavior: 'smooth' });
        } catch (e) {
            console.warn('[MusicDock] smooth scroll unavailable, jumping to the library:', e && e.message ? e.message : e);
            window.scrollTo(0, top);
        }
    }

    /* The tab fragment arrives a few hundred ms after the click, which changes the
       page height and can stop the smooth scroll short (measured 105px on an
       iPhone 11). Re-assert the target once the swap settles; the timeout covers
       a tab that does not fetch anything. */
    var libraryJumpTimer = null;

    function jumpToLibraryAfterSwap() {
        var settled = false;
        function finish() {
            if (settled) return;
            settled = true;
            document.removeEventListener('htmx:afterSettle', finish);
            if (libraryJumpTimer) {
                window.clearTimeout(libraryJumpTimer);
                libraryJumpTimer = null;
            }
            var top = libraryTopOffset();
            if (top === null) return;
            var drift = Math.abs((window.pageYOffset || 0) - top);
            if (drift <= 24) return;
            try {
                window.scrollTo({ top: top, behavior: 'smooth' });
            } catch (e) {
                console.warn('[MusicDock] could not re-align the library:', e && e.message ? e.message : e);
                window.scrollTo(0, top);
            }
        }
        document.addEventListener('htmx:afterSettle', finish);
        libraryJumpTimer = window.setTimeout(finish, 1500);
    }

    function closeDockNav() {
        if (dockNavSidebar) dockNavSidebar.classList.remove('open');
        if (dockNavBackdrop) dockNavBackdrop.classList.remove('active');
        var btn = document.getElementById('dockNavBtn');
        if (btn) btn.classList.remove('active');
    }

    function toggleDockNav() {
        if (dockNavSidebar && dockNavSidebar.classList.contains('open')) {
            closeDockNav();
        } else {
            openDockNav();
        }
    }

    function navigateTo(path) {
        closeDockNav();
        if (window.app && typeof window.app.navigate === 'function') {
            try {
                window.app.navigate(path);
                return;
            } catch (e) {
                console.warn('[MusicDock] navigate(' + path + ') failed:', e && e.message ? e.message : e);
            }
        }
        window.location.href = path;
    }

    /* ---------------- Dock dialogs: settings + import ----------------
       Fragments are injected into the page and the app's own initialisers are
       reused. (An iframe around /settings booted a second audio engine, which is
       why music played twice.) */

    var dialogReturnFocus = null;

    function openDockDialog(dialog, backdrop) {
        if (!dialog) return;
        dialogReturnFocus = document.activeElement;
        if (backdrop) backdrop.classList.add('is-open');
        dialog.classList.add('is-open');
        dialog.setAttribute('aria-hidden', 'false');
        document.body.classList.add('dock-dialog-open');
        closeDockNav();
    }

    function closeDockDialog(dialog, backdrop) {
        if (!dialog) return;
        if (backdrop) backdrop.classList.remove('is-open');
        dialog.classList.remove('is-open');
        dialog.setAttribute('aria-hidden', 'true');
        document.body.classList.remove('dock-dialog-open');
        if (dialogReturnFocus && typeof dialogReturnFocus.focus === 'function') {
            dialogReturnFocus.focus();
        }
    }

    function loadingMarkup(label) {
        return '<div class="dock-dialog-loading"><i class="pi pi-spin pi-spinner"></i><span>' + label + '</span></div>';
    }

    function loadFragment(url, container, label) {
        container.innerHTML = loadingMarkup(label);
        return fetch(url, { credentials: 'same-origin' })
            .then(function(res) {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                return res.text();
            })
            .then(function(html) {
                container.innerHTML = html;
                if (window.htmx && typeof window.htmx.process === 'function') {
                    window.htmx.process(container);
                }
                return true;
            })
            .catch(function(e) {
                console.error('[MusicDock] could not load ' + url + ':', e && e.message ? e.message : e);
                container.innerHTML = '<div class="dock-dialog-error">Could not load this section (' +
                    (e && e.message ? e.message : 'unknown error') + ').</div>';
                return false;
            });
    }

    /* ---------------- Theme toggle (Browse header) ---------------- */
    function updateThemeIcon() {
        var icon = document.getElementById('dockThemeIcon');
        if (!icon) return;
        var isDark = document.documentElement.getAttribute('data-theme') === 'dark';
        icon.classList.toggle('pi-moon', isDark);
        icon.classList.toggle('pi-sun', !isDark);
    }

    function toggleTheme() {
        var isDark = document.documentElement.getAttribute('data-theme') === 'dark';
        var next = isDark ? 'light' : 'dark';
        try {
            localStorage.setItem('darkMode', next === 'dark' ? 'true' : 'false');
        } catch (e) {
            console.warn('[MusicDock] could not persist theme preference:', e && e.message ? e.message : e);
        }
        document.documentElement.setAttribute('data-theme', next);
        updateThemeIcon();
    }

    function setupThemeToggle() {
        var btn = document.getElementById('dockThemeToggle');
        if (btn) btn.addEventListener('click', toggleTheme);
        updateThemeIcon();
    }

    /* ---------------- Sidebar position (left/right) ----------------
       Mirrors the app setting that moves the shell sidebar. The Browse panel
       follows it too, and both react immediately when the setting is changed. */
    function activeProfileId() {
        if (window.JMedia && JMedia.Helpers && typeof JMedia.Helpers.getActiveProfileId === 'function') {
            return JMedia.Helpers.getActiveProfileId();
        }
        return window.globalActiveProfileId || localStorage.getItem('activeProfileId');
    }

    function applySidebarPosition(position) {
        var side = position === 'left' ? 'left' : 'right';

        var layout = document.getElementById('standard-layout');
        if (layout) layout.classList.toggle('sidebar-right', side === 'right');

        var browse = document.getElementById('dockNavSidebar');
        if (browse) browse.classList.toggle('from-left', side === 'left');
    }

    function syncSidebarPositionFromServer() {
        var profileId = activeProfileId();
        if (!profileId) return;

        fetch('/api/settings/' + profileId + '/sidebar-position', { credentials: 'same-origin' })
            .then(function(res) {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                return res.json();
            })
            .then(function(json) {
                var position = (json && json.data) ? json.data : 'left';
                try {
                    localStorage.setItem('sidebarPosition', position);
                } catch (e) {
                    console.warn('[MusicDock] could not cache sidebar position:', e && e.message ? e.message : e);
                }
                applySidebarPosition(position);
            })
            .catch(function(e) {
                console.warn('[MusicDock] sidebar position fetch failed:', e && e.message ? e.message : e);
            });
    }

    function initSidebarPosition() {
        applySidebarPosition(localStorage.getItem('sidebarPosition') || 'right');
        syncSidebarPositionFromServer();

        // Another tab changed the setting.
        window.addEventListener('storage', function(e) {
            if (e.key === 'sidebarPosition') applySidebarPosition(e.newValue || 'right');
        });
    }

    /* The settings view saves via window.saveUiSettings; wrap it so the dock
       picks the new position up without a reload. */
    function watchSidebarPositionSetting() {
        var select = document.getElementById('sidebarPositionSelect');
        if (select && !select.dataset.dockWatched) {
            select.dataset.dockWatched = 'true';
            select.addEventListener('change', function() {
                applySidebarPosition(select.value);
            });
        }

        if (typeof window.saveUiSettings === 'function' && !window.__dockSidebarSaveWrapped) {
            var previousSave = window.saveUiSettings;
            window.__dockSidebarSaveWrapped = true;
            window.saveUiSettings = function() {
                var result = previousSave.apply(this, arguments);
                Promise.resolve(result)
                    .then(function() {
                        applySidebarPosition(localStorage.getItem('sidebarPosition') || 'right');
                    })
                    .catch(function(e) {
                        console.warn('[MusicDock] could not re-apply sidebar position:', e && e.message ? e.message : e);
                    });
                return result;
            };
        }
    }

    /* ---------------- Settings dialog ---------------- */
    var settingsDialog = null;
    var settingsContent = null;
    var settingsLoaded = false;
    var settingsLoading = false;

    function ensureAdminVisibility() {
        if (window.__dockAdminChecked) return Promise.resolve();
        window.__dockAdminChecked = true;
        return fetch('/api/auth/is-admin', { credentials: 'same-origin' })
            .then(function(res) { return res.json(); })
            .then(function(json) {
                var isAdmin = !!(json && json.data && json.data.isAdmin);
                if (!isAdmin) return;
                document.querySelectorAll('#settingsSideTabs .admin-only').forEach(function(el) {
                    el.style.display = 'flex';
                });
            })
            .catch(function(e) {
                console.warn('[MusicDock] admin check failed:', e && e.message ? e.message : e);
            });
    }

    function settingsTabItems() {
        var items = document.querySelectorAll('#settingsSideTabs .nav-item[data-tab]');
        var visible = [];
        for (var i = 0; i < items.length; i++) {
            var display = window.getComputedStyle(items[i]).display;
            if (display === 'none') continue;
            visible.push(items[i]);
        }
        return visible;
    }

    function syncSettingsTabState() {
        var nav = document.getElementById('dockSettingsTabs');
        if (!nav) return;
        var active = document.querySelector('#settingsSideTabs .nav-item.active');
        var activeTab = active ? active.getAttribute('data-tab') : null;
        var buttons = nav.querySelectorAll('.dock-dialog-tab');
        for (var i = 0; i < buttons.length; i++) {
            buttons[i].classList.toggle('is-active', buttons[i].getAttribute('data-tab') === activeTab);
        }
    }

    function buildSettingsTabs() {
        var nav = document.getElementById('dockSettingsTabs');
        if (!nav) return;

        var items = settingsTabItems();
        var ids = [];
        for (var i = 0; i < items.length; i++) ids.push(items[i].getAttribute('data-tab'));
        var signature = ids.join('|');

        if (nav.dataset.tabsSignature === signature && nav.children.length === items.length) {
            syncSettingsTabState();
            return;
        }

        nav.innerHTML = '';
        items.forEach(function(item) {
            var tab = item.getAttribute('data-tab');
            var btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'dock-dialog-tab';
            btn.setAttribute('data-tab', tab);

            var icon = item.querySelector('i');
            if (icon) btn.appendChild(icon.cloneNode(true));

            var label = item.querySelector('span');
            var span = document.createElement('span');
            span.textContent = (label ? label.textContent : item.textContent || '').trim();
            btn.appendChild(span);

            btn.addEventListener('click', function() {
                var target = document.querySelector('#settingsSideTabs .nav-item[data-tab="' + tab + '"]');
                if (target) {
                    target.click();   // Settings.js switches the pane and runs its loader
                    syncSettingsTabState();
                } else {
                    console.warn('[MusicDock] settings tab is missing:', tab);
                }
            });

            nav.appendChild(btn);
        });

        nav.dataset.tabsSignature = signature;
        syncSettingsTabState();
    }

    function initSettingsFragment() {
        /* Settings.js owns all of this: tab bindings, admin visibility and the
           saved values (directories, import/playback/UI settings, system panel).
           App.js calls it for /settings; the dialog has to call it too. */
        var init = (window.JMedia && JMedia.Settings && JMedia.Settings.initSettingsView)
            || window.initSettingsView;

        if (typeof init !== 'function') {
            console.warn('[MusicDock] initSettingsView is unavailable; the settings dialog will stay inert');
            buildSettingsTabs();
            return;
        }

        Promise.resolve(init.call(window.JMedia ? JMedia.Settings : null))
            .then(function() {
                buildSettingsTabs();
                watchSidebarPositionSetting();
            })
            .catch(function(e) {
                console.error('[MusicDock] initSettingsView failed:', e && e.message ? e.message : e);
                buildSettingsTabs();
                watchSidebarPositionSetting();
            });
    }

    function openSettingsDialog() {
        settingsDialog = settingsDialog || document.getElementById('dockSettingsModal');
        settingsContent = settingsContent || document.getElementById('dockSettingsContent');
        if (!settingsDialog || !settingsContent) {
            console.warn('[MusicDock] settings dialog markup is missing');
            return;
        }

        openDockDialog(settingsDialog, document.getElementById('dockSettingsBackdrop'));

        if (!settingsLoaded && !settingsLoading) {
            settingsLoading = true;
            ensureAdminVisibility()
                .then(function() { return loadFragment('/views/settings.html', settingsContent, 'Loading settings…'); })
                .then(function(ok) {
                    settingsLoading = false;
                    if (!ok) return;
                    settingsLoaded = true;
                    initSettingsFragment();
                });
        } else if (settingsLoaded) {
            buildSettingsTabs();
            syncSettingsTabState();
        }

        var closeBtn = document.getElementById('dockSettingsClose');
        if (closeBtn) closeBtn.focus();
    }

    function closeSettingsDialog() {
        closeDockDialog(settingsDialog, document.getElementById('dockSettingsBackdrop'));
    }

    /* ---------------- Import dialog ---------------- */
    var importDialog = null;
    var importContent = null;
    var importLoaded = false;
    var importLoading = false;

    function openImportDialog() {
        importDialog = importDialog || document.getElementById('dockImportModal');
        importContent = importContent || document.getElementById('dockImportContent');
        if (!importDialog || !importContent) {
            console.warn('[MusicDock] import dialog markup is missing');
            return;
        }

        openDockDialog(importDialog, document.getElementById('dockImportBackdrop'));

        if (!importLoaded && !importLoading) {
            importLoading = true;
            loadFragment('/views/import.html', importContent, 'Loading import…').then(function(ok) {
                importLoading = false;
                if (!ok) return;
                importLoaded = true;
                if (typeof window.initImportView === 'function') {
                    try {
                        window.initImportView();
                    } catch (e) {
                        console.error('[MusicDock] initImportView failed:', e && e.message ? e.message : e);
                    }
                } else {
                    console.warn('[MusicDock] initImportView is unavailable; the import form stays inert');
                }
            });
        }

        var closeBtn = document.getElementById('dockImportClose');
        if (closeBtn) closeBtn.focus();
    }

    function closeImportDialog() {
        closeDockDialog(importDialog, document.getElementById('dockImportBackdrop'));
    }

    function setupDockDialogs() {
        var settingsNav = document.getElementById('dockNavSettings');
        if (settingsNav) settingsNav.addEventListener('click', openSettingsDialog);

        var gear = document.getElementById('dockSettingsBtn');
        if (gear) gear.addEventListener('click', openSettingsDialog);

        var importNav = document.getElementById('dockNavImport');
        if (importNav) importNav.addEventListener('click', openImportDialog);

        var settingsClose = document.getElementById('dockSettingsClose');
        if (settingsClose) settingsClose.addEventListener('click', closeSettingsDialog);

        var settingsBackdrop = document.getElementById('dockSettingsBackdrop');
        if (settingsBackdrop) settingsBackdrop.addEventListener('click', closeSettingsDialog);

        var importClose = document.getElementById('dockImportClose');
        if (importClose) importClose.addEventListener('click', closeImportDialog);

        var importBackdrop = document.getElementById('dockImportBackdrop');
        if (importBackdrop) importBackdrop.addEventListener('click', closeImportDialog);

        document.addEventListener('keydown', function(e) {
            if (e.key !== 'Escape') return;
            var importModal = document.getElementById('dockImportModal');
            if (importModal && importModal.classList.contains('is-open')) {
                closeImportDialog();
                return;
            }
            var settingsModal = document.getElementById('dockSettingsModal');
            if (settingsModal && settingsModal.classList.contains('is-open')) {
                closeSettingsDialog();
            }
        });
    }
    /* "All Music" is the plain song list; switchToTab() has no branch for it. */
    function showAllMusic() {
        ['mobileAlbumContent', 'mobileGenreContent', 'mobileGenreGroupedContent',
            'mobileGenreFamilyContent', 'mobilePlaylistContent', 'mobileQueueContent',
            'mobileHistoryContent'].forEach(function(id) {
            var el = document.getElementById(id);
            if (el) el.classList.add('is-hidden');
        });
        var toggle = document.getElementById('mobileGenreViewToggle');
        if (toggle) toggle.classList.add('is-hidden');
        var songs = document.getElementById('mobileSongList');
        if (songs) songs.classList.remove('is-hidden');

        document.querySelectorAll('.nav-item, .nav-sub-item').forEach(function(el) {
            el.classList.remove('active');
        });
        var navMusic = document.getElementById('nav-music');
        if (navMusic) navMusic.classList.add('active');

        document.querySelectorAll('.dock-nav-item').forEach(function(el) {
            el.classList.remove('active');
        });
        var allItem = document.getElementById('dockNavAll');
        if (allItem) allItem.classList.add('active');

        closeDockNav();
    }

    /* Phones: #musicPlayerContainer lays its children out as a row, so the progress
       bar could never sit above the dock on its own (it ended up to the left).
       Move it into the dock row as a full-width first line; put it back on wider
       viewports. */
    function placeProgressBar() {
        if (document.body.classList.contains('dock-search-open')) return;
        var bar = document.querySelector('.mobile-progress-container');
        var row = document.querySelector('.mobile-dock-row');
        var container = document.getElementById('musicPlayerContainer');
        var player = document.querySelector('.mobile-player');
        if (!bar || !row || !container || !player) return;

        var isPhone = window.matchMedia('(max-width: 640px)').matches;
        if (isPhone && bar.parentElement !== row) {
            row.insertBefore(bar, row.firstChild);
            bar.classList.add('in-dock-row');
        } else if (!isPhone && bar.classList.contains('in-dock-row')) {
            bar.classList.remove('in-dock-row');
            container.insertBefore(bar, player);
        }
    }

    /* Phones: the gear and search sit INSIDE the dock pill (gear in the slot the
       volume button used to occupy, search mirrored on the left) so the dock is a
       single centred pill. Wider viewports keep the two side pills. */
    function placeDockSideButtons() {
        if (document.body.classList.contains('dock-search-open')) return;
        var controls = document.querySelector('.mobile-player-controls');
        var gear = document.getElementById('dockSettingsBtn');
        var search = document.getElementById('dockSearchBtn');
        var gearWrap = document.querySelector('.mobile-gear-wrapper');
        var searchWrap = document.querySelector('.mobile-search-wrapper');
        if (!controls || !gear || !search || !gearWrap || !searchWrap) return;

        var isPhone = window.matchMedia('(max-width: 640px)').matches;

        if (isPhone) {
            /* Only the gear moves into the dock (the slot the volume button had).
               The search keeps its own pill. */
            if (gear.parentElement !== controls) controls.appendChild(gear);
            if (search.parentElement !== searchWrap) searchWrap.appendChild(search);
        } else {
            if (search.parentElement !== searchWrap) searchWrap.appendChild(search);
            if (gear.parentElement !== gearWrap) gearWrap.appendChild(gear);
        }
    }

        /* ---------------- Shareable URLs ----------------
       /music/playlist/{id}, /music/song/{id}, /music/album/{id}, /music/artist/{id},
       /music/genre/{key}. The page opens the matching view on boot (after login,
       the 307 carries the full path), and browsing updates the address bar so the
       link can just be copied. */
    /* Read the deep link before anything else runs: the app's own boot calls
       loadMobilePlaylistSongs(0), which would push /music over it. */
    var bootMusicRoute = (window.MusicRoutes) ? window.MusicRoutes.parse(window.location.pathname) : null;
    var routeBootPending = !!(bootMusicRoute && bootMusicRoute.view !== 'all');

    function musicRouteNavigate(kind, id) {
        if (routeBootPending) return;   // never overwrite the deep link during boot
        try {
            if (!window.MusicRoutes) return;
            var url = window.MusicRoutes[kind + 'Url'](id);
            if (window.location.pathname === url) return;
            window.history.pushState({ music: kind, id: id }, '', url);
        } catch (e) {
            console.warn('[MusicDock] could not update the address bar:', e && e.message ? e.message : e);
        }
    }

    function applyMusicRoute(explicitRoute) {
        if (!window.MusicRoutes) return false;
        var route = explicitRoute || window.MusicRoutes.parse(window.location.pathname);
        if (!route || route.view === 'all') return false;

        var list = document.getElementById('mobileSongList');
        if (!list) return false;   // app not ready yet

        switch (route.view) {
            case 'playlist':
                if (window.loadMobilePlaylistSongs) window.loadMobilePlaylistSongs(route.id);
                break;
            case 'album':
                if (window.showAlbumPageById) window.showAlbumPageById(route.id, null);
                break;
            case 'artist':
                if (window.showArtistPageById) window.showArtistPageById(route.id, null);
                break;
            case 'song':
                if (window.showSongDetail) window.showSongDetail(route.id);
                break;
            case 'genre':
                if (window.loadGenreSongs) window.loadGenreSongs(route.id);
                break;
            default:
                return false;
        }
        scrollToLibraryTop();
        return true;
    }

    function setupMusicRouting() {
        if (!window.MusicRoutes || setupMusicRouting._done) return;
        setupMusicRouting._done = true;

        function wrap(name, kind) {
            var fn = window[name];
            if (typeof fn !== 'function' || fn._musicRouteWrapped) return;
            var wrapped = function () {
                var result = fn.apply(this, arguments);
                var id = arguments[0];
                if (id !== undefined && id !== null && String(id) !== '') {
                    musicRouteNavigate(kind, id);
                }
                return result;
            };
            wrapped._musicRouteWrapped = true;
            window[name] = wrapped;
        }

        wrap('loadMobilePlaylistSongs', 'playlist');
        wrap('showAlbumPageById', 'album');
        wrap('showArtistPageById', 'artist');
        wrap('showSongDetail', 'song');

        window.addEventListener('popstate', function () {
            routeBootPending = false;
            var route = window.MusicRoutes.parse(window.location.pathname);
            if (!route || route.view === 'all') {
                if (window.loadMobilePlaylistSongs) window.loadMobilePlaylistSongs(0);
                return;
            }
            applyMusicRoute();
        });

        if (!routeBootPending) {
            return;   // /music or the library: nothing to open
        }

        var attempts = 25;
        (function openRoute() {
            if (applyMusicRoute(bootMusicRoute)) {
                routeBootPending = false;
                try {
                    var canonical = window.MusicRoutes[bootMusicRoute.view + 'Url'](bootMusicRoute.id);
                    if (canonical && window.location.pathname !== canonical) {
                        window.history.replaceState({ music: bootMusicRoute.view, id: bootMusicRoute.id }, '', canonical);
                    }
                } catch (e) {
                    console.warn('[MusicDock] could not restore the deep link:', e && e.message ? e.message : e);
                }
                return;
            }
            if (--attempts <= 0) {
                console.warn('[MusicDock] route target never became ready:', bootMusicRoute);
                routeBootPending = false;
                return;
            }
            window.setTimeout(openRoute, 200);
        })();
    }

    function setupBackToTop() {        var btn = document.getElementById('dockToTop');
        if (!btn) return;
        var ticking = false;

        function update() {
            var y = window.pageYOffset || document.documentElement.scrollTop || 0;
            btn.classList.toggle('is-visible', y > 420);
            ticking = false;
        }

        window.addEventListener('scroll', function() {
            if (ticking) return;
            ticking = true;
            if (typeof window.requestAnimationFrame === 'function') {
                window.requestAnimationFrame(update);
            } else {
                update();
            }
        }, { passive: true });

        btn.addEventListener('click', function() {
            try {
                window.scrollTo({ top: 0, behavior: 'smooth' });
            } catch (e) {
                console.warn('[MusicDock] smooth scroll unsupported, jumping to top:', e && e.message ? e.message : e);
                window.scrollTo(0, 0);
            }
            btn.classList.remove('is-visible');
        });

        update();
    }

    /* The dock page hides the injected search bars, and the real Play All /
       Clear All buttons live inside them. Mirror them here and proxy clicks so
       there is a single source of truth for the behaviour. */
    function syncDockActions() {
        var realPlay = document.getElementById('musicPlayAllBtn');
        var dockPlay = document.getElementById('dockPlayAllBtn');
        var showPlay = !!realPlay && realPlay.style.display !== 'none'
            && !realPlay.classList.contains('is-hidden');
        if (dockPlay) dockPlay.hidden = !showPlay;

        var queue = document.getElementById('mobileQueueContent');
        var dockClear = document.getElementById('dockClearQueueBtn');
        var showClear = !!queue && !queue.classList.contains('is-hidden');
        if (dockClear) dockClear.hidden = !showClear;

        var row = document.getElementById('dockActions');
        if (row) row.hidden = !(showPlay || showClear);
    }

    function wrapSwitchToTab() {
        if (typeof window.switchToTab !== 'function' || window.__dockSwitchWrapped) return;
        var previous = window.switchToTab;
        window.__dockSwitchWrapped = true;
        window.switchToTab = function(tab) {
            var result = previous.apply(this, arguments);
            window.setTimeout(syncDockActions, 80);
            return result;
        };
    }

    function setupDockActions() {
        if (!window.__dockActionsWired) {
            window.__dockActionsWired = true;

            var dockPlay = document.getElementById('dockPlayAllBtn');
            if (dockPlay) {
                dockPlay.addEventListener('click', function() {
                    var real = document.getElementById('musicPlayAllBtn');
                    if (real) real.click();
                    else console.warn('[MusicDock] Play All: #musicPlayAllBtn is not in the DOM');
                });
            }

            var dockClear = document.getElementById('dockClearQueueBtn');
            if (dockClear) {
                dockClear.addEventListener('click', function() {
                    var real = document.getElementById('clearQueueBtn');
                    if (real) real.click();
                    else console.warn('[MusicDock] Clear All: #clearQueueBtn is not in the DOM');
                });
            }

            var container = document.getElementById('app-content');
            if (container && typeof window.MutationObserver === 'function') {
                var pending = false;
                var observer = new window.MutationObserver(function() {
                    if (pending) return;
                    pending = true;
                    window.requestAnimationFrame(function() {
                        pending = false;
                        syncDockActions();
                    });
                });
                observer.observe(container, {
                    childList: true, subtree: true, attributes: true,
                    attributeFilter: ['style', 'class']
                });
            }
        }

        wrapSwitchToTab();
        syncDockActions();
    }

    function setupNavSidebar() {
        dockNavSidebar = document.getElementById('dockNavSidebar');
        dockNavBackdrop = document.getElementById('dockNavBackdrop');

        var btn = document.getElementById('dockNavBtn');
        if (btn) btn.addEventListener('click', toggleDockNav);

        var close = document.getElementById('dockNavClose');
        if (close) close.addEventListener('click', closeDockNav);

        if (dockNavBackdrop) dockNavBackdrop.addEventListener('click', closeDockNav);

        document.addEventListener('keydown', function(e) {
            if (e.key === 'Escape' && dockNavSidebar && dockNavSidebar.classList.contains('open')) {
                closeDockNav();
            }
        });

        document.querySelectorAll('.dock-nav-item[data-dock-tab]').forEach(function(item) {
            item.addEventListener('click', function() {
                var tab = item.dataset.dockTab;
                document.querySelectorAll('.dock-nav-item[data-dock-tab]').forEach(function(el) {
                    el.classList.toggle('active', el === item);
                });
                if (tab && typeof window.switchToTab === 'function') {
                    try {
                        window.switchToTab(tab);
                    } catch (e) {
                        console.error('[MusicDock] switchToTab(' + tab + ') failed:', e);
                    }
                }
                closeDockNav();
                scrollToLibraryTop();
                jumpToLibraryAfterSwap();
            });
        });

        var allItem = document.getElementById('dockNavAll');
        if (allItem) allItem.addEventListener('click', function() {
            showAllMusic();
            scrollToLibraryTop();
            jumpToLibraryAfterSwap();
        });

        var importItem = document.getElementById('dockNavImport');
        if (importItem) importItem.addEventListener('click', function() { openImportDialog(); });
    }

    function wireMusicState() {
        updateHero();
        window.addEventListener('musicStateChanged', updateHero);
        window.addEventListener('refreshDOMCache', updateHero);
        setupDockActions();
        /* The player is rebuilt by the music bundle after load, which puts the
           progress bar back into the container; re-place it and keep watching. */
        placeProgressBar();
        placeDockSideButtons();
        window.addEventListener('musicStateChanged', placeProgressBar);
        window.addEventListener('refreshDOMCache', placeProgressBar);
        window.addEventListener('musicStateChanged', placeDockSideButtons);
        window.addEventListener('refreshDOMCache', placeDockSideButtons);

        var container = document.getElementById('musicPlayerContainer');
        if (container && typeof window.MutationObserver === 'function') {
            var pending = false;
            var observer = new window.MutationObserver(function() {
                if (pending) return;
                pending = true;
                window.requestAnimationFrame(function() {
                    pending = false;
                    placeProgressBar();
                });
            });
            observer.observe(container, { childList: true, subtree: false });
        }
    }

    function init() {
        hero = document.getElementById('musicHero');
        heroArtwork = document.getElementById('musicHeroArtwork');
        heroBackdrop = document.getElementById('musicHeroBackdrop');
        heroTitle = document.getElementById('musicHeroTitle');
        heroArtist = document.getElementById('musicHeroArtist');
        heroBadge = document.querySelector('.music-hero-badge');

        setupSearchButton();
        setupSortBar();
        setupAlphaRail();
        setupNavSidebar();
        setupScrollListener();
        setupBackToTop();
        setupDockActions();
        setupDockDialogs();
        setupThemeToggle();
        initSidebarPosition();
        placeProgressBar();
        placeDockSideButtons();

        var resizePending = false;
        window.addEventListener('resize', function() {
            if (resizePending) return;
            resizePending = true;
            window.requestAnimationFrame(function() {
                resizePending = false;
                placeProgressBar();
                placeDockSideButtons();
            });
        });

        // The music engine (StateManager, Helpers, ...) is injected by
        // music-bundle-loader.js after this script may already have run, so
        // never read those globals synchronously here. Wait for the bundle
        // promise first; warn (do not silently fail) on timeout/skip.
        var bundlePromise = (typeof window.__musicBundleLoaded !== 'undefined')
            ? window.__musicBundleLoaded : null;

        if (bundlePromise && typeof bundlePromise.then === 'function') {
            var timeoutMs = 10000;
            Promise.race([
                bundlePromise,
                new Promise(function (_, reject) {
                    setTimeout(function () {
                        reject(new Error('music bundle load timed out after ' + timeoutMs + 'ms'));
                    }, timeoutMs);
                })
            ]).then(function (loaded) {
                if (loaded === false) {
                    console.warn('[MusicDock] Music bundle was skipped; music globals unavailable.');
                    return;
                }
                                wireMusicState();
                setupMusicRouting();
            }).catch(function (err) {
                console.warn('[MusicDock] Music bundle not ready:', err && err.message ? err.message : err);
                wireMusicState();
            });
        } else {
            console.warn('[MusicDock] window.__musicBundleLoaded not found; proceeding without bundle wait.');
            wireMusicState();
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})(window);
