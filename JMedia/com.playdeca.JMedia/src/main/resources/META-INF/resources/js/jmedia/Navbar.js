(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    JMedia.Navbar = {};

    JMedia.Navbar.setupPlayerToggle = function() {
        const playerToggle = document.getElementById('playerToggle');
        const playerIcon = document.getElementById('playerIcon');
        if (playerToggle && playerIcon) {
            const currentPage = window.location.pathname;
            if (currentPage.includes('/player.html')) {
                playerIcon.classList.remove('pi-expand');
                playerIcon.classList.add('pi-desktop');
                playerToggle.href = '/';
                playerToggle.title = 'Switch to Desktop View';
            } else {
                playerIcon.classList.remove('pi-desktop');
                playerIcon.classList.add('pi-expand');
                playerToggle.href = '/player.html';
                playerToggle.title = 'Switch to Player View';
            }
        }
    };

    JMedia.Navbar.setupBurgerMenu = function() {
        const $navbarBurgers = Array.prototype.slice.call(document.querySelectorAll('.navbar-burger'), 0);
        if ($navbarBurgers.length > 0) {
            $navbarBurgers.forEach(el => {
                el.addEventListener('click', () => {
                    const target = el.dataset.target;
                    const $target = document.getElementById(target);
                    el.classList.toggle('is-active');
                    $target.classList.toggle('is-active');
                });
            });
        }
    };

    JMedia.Navbar.setupDropdowns = function() {
        function closeSearchResults() {
            const searchResultsContainer = document.getElementById('searchResultsContainer');
            const mainContent = document.getElementById('mainContent');
            if (searchResultsContainer && mainContent) {
                searchResultsContainer.classList.add('is-hidden');
                mainContent.classList.remove('is-hidden');
                searchResultsContainer.innerHTML = '';
            }
        }

        document.addEventListener('htmx:afterSwap', function(event) {
            if (event.detail.target.id === 'searchResultsContainer') {
                const closeButton = document.querySelector('#searchResultsContainer .card-header-icon');
                if (closeButton) {
                    closeButton.addEventListener('click', closeSearchResults);
                }
            }
        });

        function updateNavbarButtonsVisibility(currentProfile) {
            const settingsBtn = document.querySelector('a[href="/settings"]');
            const importBtn = document.querySelector('a[href="/import"]');
            if (settingsBtn) {
                settingsBtn.style.display = (currentProfile && currentProfile.isMainProfile) ? '' : 'none';
            }
            if (importBtn) {
                importBtn.style.display = '';
            }
        }

        function fetchCurrentProfileAndThenUpdateNavbar() {
            fetch('/api/profiles/current')
                .then(response => response.json())
                .then(currentProfile => updateNavbarButtonsVisibility(currentProfile))
                .catch(error => {
                    console.error('Error fetching current profile:', error);
                    updateNavbarButtonsVisibility(null);
                });
        }

        fetchCurrentProfileAndThenUpdateNavbar();
        document.body.addEventListener('profileSwitched', fetchCurrentProfileAndThenUpdateNavbar);
    };

    window.handleLogout = function() {
        fetch('/api/auth/logout', { method: 'POST' }).then(() => location.href = '/login.html');
    };

    document.addEventListener('DOMContentLoaded', () => {
        JMedia.Navbar.setupPlayerToggle();
        JMedia.Navbar.setupBurgerMenu();
        JMedia.Navbar.setupDropdowns();
    });

})(window);
