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

    function updateHero() {
        if (!hero || !window.StateManager) return;

        var state = window.StateManager.getState();
        var hasSong = !!state.currentSongId;
        var artworkUrl = getArtworkUrl(state) || '/logo.png';
        var titleText = hasSong && state.songName ? state.songName : 'Nothing Playing';
        var artistText = hasSong ? (state.artistName || state.artist || 'Unknown Artist') : 'Unknown Artist';

        if (heroArtwork) heroArtwork.src = artworkUrl;
        if (heroBackdrop) heroBackdrop.src = artworkUrl;
        if (heroTitle) heroTitle.textContent = titleText;
        if (heroArtist) heroArtist.textContent = artistText;
        if (heroBadge) heroBadge.style.display = hasSong ? '' : 'none';

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
           then collapse the shell so the dock controls return. */
        var dropdown = document.getElementById('dockSearchDropdown');
        if (dropdown) {
            dropdown.addEventListener('click', function() {
                setTimeout(closeSearch, 120);
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
            });
        });
    }

    function init() {
        hero = document.getElementById('musicHero');
        heroArtwork = document.getElementById('musicHeroArtwork');
        heroBackdrop = document.getElementById('musicHeroBackdrop');
        heroTitle = document.getElementById('musicHeroTitle');
        heroArtist = document.getElementById('musicHeroArtist');
        heroBadge = document.querySelector('.music-hero-badge');

        setupSearchButton();
        setupNavSidebar();
        setupScrollListener();
        updateHero();

        window.addEventListener('musicStateChanged', updateHero);
        window.addEventListener('refreshDOMCache', updateHero);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})(window);
