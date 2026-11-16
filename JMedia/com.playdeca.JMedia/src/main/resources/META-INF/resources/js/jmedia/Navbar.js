(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    JMedia.Navbar = {};

    JMedia.Navbar.setupDropdowns = function() {
        function closeSearchResults() {
            const searchResultsContainer = document.getElementById('searchResultsContainer');
            if (!searchResultsContainer) return;
            searchResultsContainer.classList.add('is-hidden');
            searchResultsContainer.innerHTML = '';
            const mainContent = document.getElementById('mainContent');
            if (mainContent) {
                mainContent.classList.remove('is-hidden');
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
                .then(response => {
                    if (!response.ok) {
                        console.warn('[Navbar] profile request failed', response.status);
                        return null;
                    }
                    return response.json();
                })
                .then(currentProfile => {
                    if (currentProfile) {
                        updateNavbarButtonsVisibility(currentProfile);
                    } else {
                        updateNavbarButtonsVisibility(null);
                    }
                })
                .catch(error => {
                    console.error('Error fetching current profile:', error);
                    updateNavbarButtonsVisibility(null);
                });
        }

        fetchCurrentProfileAndThenUpdateNavbar();
        document.body.addEventListener('profileSwitched', fetchCurrentProfileAndThenUpdateNavbar);
    };

    window.handleLogout = function() {
        localStorage.removeItem('authToken');
        localStorage.removeItem('activeProfileId');
        fetch('/api/auth/logout', { method: 'POST' })
            .then(() => {
                window.location.href = '/login.html';
            })
            .catch(err => {
                console.error('[Navbar] logout request failed', err);
                window.location.href = '/login.html';
            });
    };

    document.addEventListener('DOMContentLoaded', () => {
        JMedia.Navbar.setupDropdowns();
    });

})(window);