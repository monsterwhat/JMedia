document.addEventListener('DOMContentLoaded', () => {

    function setupPlayerToggle() {
        const playerToggle = document.getElementById('playerToggle');
        const playerIcon = document.getElementById('playerIcon');

        if (playerToggle && playerIcon) {
            const currentPage = window.location.pathname;

            if (currentPage.includes('/player.html')) {
                playerIcon.classList.remove('pi-expand'); // Remove expand if it was set
                playerIcon.classList.add('pi-desktop');   // Add desktop icon
                playerToggle.href = '/';        // Link to index (desktop view)
                playerToggle.title = 'Switch to Desktop View';
            } else {
                playerIcon.classList.remove('pi-desktop'); // Remove desktop if it was set
                playerIcon.classList.add('pi-expand');    // Add expand icon
                playerToggle.href = '/player.html';       // Link to player (player view)
                playerToggle.title = 'Switch to Player View';
            }
        }
    }

    setupPlayerToggle();

    // Get all "navbar-burger" elements
    const $navbarBurgers = Array.prototype.slice.call(document.querySelectorAll('.navbar-burger'), 0);

    // Check if there are any navbar burgers
    if ($navbarBurgers.length > 0) {

        // Add a click event on each of them
        $navbarBurgers.forEach(el => {
            el.addEventListener('click', () => {

                // Get the target from the "data-target" attribute
                const target = el.dataset.target;
                const $target = document.getElementById(target);

                // Toggle the "is-active" class on both the "navbar-burger" and the "navbar-menu"
                el.classList.toggle('is-active');
                $target.classList.toggle('is-active');

            });
        });
    }

    function closeSearchResults() {
        const searchResultsContainer = document.getElementById('searchResultsContainer');
        const mainContent = document.getElementById('mainContent');
        if (searchResultsContainer && mainContent) {
            searchResultsContainer.classList.add('is-hidden');
            mainContent.classList.remove('is-hidden');
            // Clear the search results
            searchResultsContainer.innerHTML = '';
        }
    }

    document.addEventListener('htmx:afterSwap', function (event) {
        if (event.detail.target.id === 'searchResultsContainer') {
            const closeButton = document.querySelector('#searchResultsContainer .card-header-icon');
            if (closeButton) {
                closeButton.addEventListener('click', closeSearchResults);
            }
        }
    });

    // --- New code for navbar button visibility ---

    function updateNavbarButtonsVisibility(currentProfile) {
        const settingsBtn = document.querySelector('a[href="/settings"]');
        const importBtn = document.querySelector('a[href="/import"]');

        if (settingsBtn) {
            if (currentProfile && currentProfile.isMainProfile) {
                settingsBtn.style.display = ''; // Show
            } else {
                settingsBtn.style.display = 'none'; // Hide
            }
        }
        // Import button should always be visible
        if (importBtn) {
            importBtn.style.display = ''; // Always show
        }
    }

    function fetchCurrentProfileAndThenUpdateNavbar() {
        fetch('/api/profiles/current')
            .then(response => response.json())
            .then(currentProfile => {
                updateNavbarButtonsVisibility(currentProfile);
            })
            .catch(error => {
                console.error('Error fetching current profile:', error);
                // In case of error, default to hiding settings for safety
                updateNavbarButtonsVisibility(null); 
            });
    }

    // Call the function on DOMContentLoaded
    fetchCurrentProfileAndThenUpdateNavbar();

    // Re-fetch and update visibility when a profile is switched (e.g., from modal)
    document.body.addEventListener('profileSwitched', fetchCurrentProfileAndThenUpdateNavbar);
});