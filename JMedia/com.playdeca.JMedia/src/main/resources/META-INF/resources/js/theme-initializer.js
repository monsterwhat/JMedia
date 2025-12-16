// Apply theme preference immediately to prevent FOUC
(function() {
    const savedTheme = localStorage.getItem('darkMode');
    if (savedTheme === 'true') {
        document.documentElement.setAttribute('data-theme', 'dark');
    } else if (savedTheme === 'false') {
        document.documentElement.removeAttribute('data-theme');
    }
})();

// Theme Toggle Logic
function applyThemePreference() {
    const themeToggle = document.getElementById('themeToggle');
    const themeIcon = document.getElementById('themeIcon');
    if (!themeToggle || !themeIcon) return;

    let isDarkMode = localStorage.getItem('darkMode');

    if (isDarkMode === null) {
        // No preference saved, check system preference
        isDarkMode = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'true' : 'false';
    }

    if (isDarkMode === 'true') {
        document.documentElement.setAttribute('data-theme', 'dark');
        themeIcon.classList.remove('pi-sun');
        themeIcon.classList.add('pi-moon');
    } else {
        document.documentElement.removeAttribute('data-theme');
        themeIcon.classList.remove('pi-moon');
        themeIcon.classList.add('pi-sun');
    }
}

document.addEventListener('DOMContentLoaded', () => {
    applyThemePreference(); // Apply theme on load

    const themeToggle = document.getElementById('themeToggle');
    if (themeToggle) {
        themeToggle.addEventListener('click', () => {
            const isDarkMode = document.documentElement.getAttribute('data-theme') === 'dark';
            localStorage.setItem('darkMode', !isDarkMode);
            applyThemePreference(); // Apply theme changes immediately
        });
    }
});