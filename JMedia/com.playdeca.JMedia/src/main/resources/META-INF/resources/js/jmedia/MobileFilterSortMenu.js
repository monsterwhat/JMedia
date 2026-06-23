(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    class MobileFilterSortMenu {
        constructor() {
            this.isVisible = false;
            this.genres = [];
            this.filteredGenres = [];
            this.currentSort = 'dateAdded';
            this.init();
        }

        init() {
            this.setupEventListeners();
            this.loadGenres();
            console.log('[MobileFilterSortMenu] Filter/sort menu initialized');
        }

        setupEventListeners() {
            const filterBtn = document.getElementById('mobileFilterSort');
            if (filterBtn) {
                filterBtn.addEventListener('click', this.showMenu.bind(this));
            }
            const closeBtn = document.querySelector('.mobile-filter-close');
            if (closeBtn) {
                closeBtn.addEventListener('click', this.hideMenu.bind(this));
            }
            const backdrop = document.querySelector('.mobile-filter-backdrop');
            if (backdrop) {
                backdrop.addEventListener('click', this.hideMenu.bind(this));
            }
            const applyBtn = document.getElementById('applyFilters');
            if (applyBtn) {
                applyBtn.addEventListener('click', this.applyFilters.bind(this));
            }
            const resetBtn = document.getElementById('resetFilters');
            if (resetBtn) {
                resetBtn.addEventListener('click', this.resetFilters.bind(this));
            }
            const showMoreBtn = document.getElementById('showMoreGenres');
            if (showMoreBtn) {
                showMoreBtn.addEventListener('click', this.showMoreGenres.bind(this));
            }
            document.addEventListener('keydown', (e) => {
                if (e.key === 'Escape' && this.isVisible) {
                    this.hideMenu();
                }
            });
        }

        async loadGenres() {
            try {
                const response = await fetch('/api/music/ui/genres');
                if (response.ok) {
                    this.genres = await response.json();
                    this.populateGenreOptions();
                } else {
                    console.warn('[MobileFilterSortMenu] Failed to load genres');
                }
            } catch (error) {
                console.error('[MobileFilterSortMenu] Error loading genres:', error);
            }
        }

        populateGenreOptions() {
            const container = document.getElementById('genreFilterOptions');
            if (!container) return;
            const allGenresLabel = container.querySelector('label');
            container.innerHTML = '';
            if (allGenresLabel) {
                container.appendChild(allGenresLabel);
            }
            const genresToShow = this.genres.slice(0, 10);
            genresToShow.forEach(genre => {
                const label = document.createElement('label');
                label.innerHTML = `<input type="checkbox" name="genre" value="${genre}"> ${genre}`;
                container.appendChild(label);
            });
            if (this.genres.length > 10) {
                const showMoreBtn = document.getElementById('showMoreGenres');
                if (showMoreBtn) {
                    showMoreBtn.style.display = 'block';
                    showMoreBtn.textContent = `Show More (${this.genres.length - 10} more)`;
                }
            }
        }

        showMoreGenres() {
            const container = document.getElementById('genreFilterOptions');
            const showMoreBtn = document.getElementById('showMoreGenres');
            if (!container || !showMoreBtn) return;
            const remainingGenres = this.genres.slice(10);
            remainingGenres.forEach(genre => {
                const label = document.createElement('label');
                label.innerHTML = `<input type="checkbox" name="genre" value="${genre}"> ${genre}`;
                container.appendChild(label);
            });
            showMoreBtn.style.display = 'none';
        }

        showMenu() {
            const menu = document.getElementById('mobileFilterSortMenu');
            if (!menu) return;
            menu.setAttribute('aria-hidden', 'false');
            this.isVisible = true;
            document.body.style.overflow = 'hidden';
            this.loadCurrentState();
        }

        hideMenu() {
            const menu = document.getElementById('mobileFilterSortMenu');
            if (!menu) return;
            menu.setAttribute('aria-hidden', 'true');
            this.isVisible = false;
            document.body.style.overflow = '';
        }

        loadCurrentState() {
            const sortRadios = document.querySelectorAll('input[name="sortBy"]');
            sortRadios.forEach(radio => {
                radio.checked = radio.value === this.currentSort;
            });
            const genreCheckboxes = document.querySelectorAll('input[name="genre"]');
            genreCheckboxes.forEach(checkbox => {
                if (checkbox.value === '') {
                    checkbox.checked = this.filteredGenres.length === 0;
                } else {
                    checkbox.checked = this.filteredGenres.includes(checkbox.value);
                }
            });
        }

        applyFilters() {
            const selectedSort = document.querySelector('input[name="sortBy"]:checked');
            if (selectedSort) {
                this.currentSort = selectedSort.value;
            }
            const selectedGenreCheckboxes = document.querySelectorAll('input[name="genre"]:checked');
            this.filteredGenres = Array.from(selectedGenreCheckboxes)
                .map(cb => cb.value)
                .filter(value => value !== '');
            this.updateFilterButton();
            this.applyToSongList();
            this.hideMenu();
        }

        resetFilters() {
            this.currentSort = 'dateAdded';
            this.filteredGenres = [];
            this.loadCurrentState();
            this.updateFilterButton();
            this.applyToSongList();
            this.hideMenu();
        }

        updateFilterButton() {
            const filterBtn = document.getElementById('mobileFilterSort');
            if (!filterBtn) return;
            const hasActiveFilters = this.filteredGenres.length > 0 || this.currentSort !== 'dateAdded';
            if (hasActiveFilters) {
                filterBtn.classList.add('active');
                if (!filterBtn.querySelector('.filter-badge')) {
                    const badge = document.createElement('span');
                    badge.className = 'filter-badge';
                    badge.textContent = '●';
                    badge.style.cssText = `
                        position: absolute;
                        top: -2px;
                        right: -2px;
                        width: 8px;
                        height: 8px;
                        background: var(--mobile-primary);
                        border-radius: 50%;
                        font-size: 8px;
                    `;
                    filterBtn.style.position = 'relative';
                    filterBtn.appendChild(badge);
                }
            } else {
                filterBtn.classList.remove('active');
                const badge = filterBtn.querySelector('.filter-badge');
                if (badge) badge.remove();
            }
        }

        applyToSongList() {
            if (window.jmediaMobile && window.jmediaMobile.loadInitialContent) {
                const searchInput = document.getElementById('mobileSearch');
                const searchValue = searchInput ? searchInput.value : '';
                window.jmediaMobile.loadInitialContent(searchValue, this.currentSort, this.filteredGenres);
            }
        }

        getCurrentFilters() {
            return {
                sortBy: this.currentSort,
                genres: [...this.filteredGenres]
            };
        }
    }

    JMedia.MobileFilterSortMenu = MobileFilterSortMenu;

    document.addEventListener('DOMContentLoaded', () => {
        setTimeout(() => {
            if (document.getElementById('mobileFilterSortMenu')) {
                window.mobileFilterSortMenu = new MobileFilterSortMenu();
                console.log('[MobileFilterSortMenu] Filter/sort menu initialized');
            } else {
                console.warn('[MobileFilterSortMenu] Filter/sort menu element not found');
            }
        }, 1000);
    });

})(window);
