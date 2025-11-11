document.addEventListener('DOMContentLoaded', () => {

    function setupPlayerToggle() {
        const playerToggle = document.getElementById('playerToggle');
        const playerIcon = document.getElementById('playerIcon');

        if (playerToggle && playerIcon) {
            const currentPage = window.location.pathname;

            if (currentPage.includes('/player.html')) {
                playerIcon.classList.remove('pi-mobile');
                playerIcon.classList.add('pi-desktop');
                playerToggle.href = '/index.html';
                playerToggle.title = 'Switch to Desktop View';
            } else {
                playerIcon.classList.remove('pi-desktop');
                playerIcon.classList.add('pi-mobile');
                playerToggle.href = '/player.html';
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

});
