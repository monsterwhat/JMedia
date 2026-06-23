(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    JMedia.HistoryManager = {
        currentPage: 1,
        searchQuery: '',
        limit: 50,
        totalSize: Infinity,
        isFetching: false,
        loaded: false,

        init: function() {
            window.refreshHistory = () => {
                JMedia.HistoryManager.loadPage(JMedia.HistoryManager.currentPage, undefined, JMedia.HistoryManager.searchQuery);
            };
            window.refreshHistoryFromStart = () => {
                JMedia.HistoryManager.loadPage(1, undefined, JMedia.HistoryManager.searchQuery);
            };
            window.loadHistoryOnFirstClick = () => {
                if (!JMedia.HistoryManager.loaded) {
                    JMedia.HistoryManager.loaded = true;
                    JMedia.HistoryManager.loadPage(1);
                }
            };
        },

        getSearchQuery: function() {
            const input = document.getElementById('historySearchInput');
            return input ? input.value.trim() : '';
        },

        loadPage: function(page = 1, profileIdParam, searchParam) {
            if (this.isFetching) return;
            this.isFetching = true;
            this.currentPage = page;
            const profileId = profileIdParam || JMedia.Helpers.getActiveProfileId();
            const search = searchParam !== undefined ? searchParam : this.getSearchQuery();
            this.searchQuery = search;

            const searchEncoded = encodeURIComponent(search);
            fetch(`/api/music/ui/history-fragment/${profileId}?page=${this.currentPage}&limit=${this.limit}&search=${searchEncoded}`, {
                headers: {'Accept': 'application/json'}
            })
                .then(response => {
                    if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
                    return response.json();
                })
                .then(data => {
                    const tbody = document.querySelector('#songHistoryTable tbody');
                    if (!tbody) {
                        this.isFetching = false;
                        return;
                    }

                    tbody.innerHTML = data.html;

                    if (window.htmx && window.htmx.process) {
                        window.htmx.process(tbody);
                    }

                    this.totalSize = data.totalHistorySize;
                    this.isFetching = false;

                    tbody.querySelectorAll('tr').forEach(row => {
                        const cell = row.querySelector('td:nth-child(2)');
                        if (cell) JMedia.Helpers.applyMarqueeEffect(cell);
                    });
                })
                .catch(error => {
                    console.error('[HistoryManager] loadPage failed:', error);
                    this.isFetching = false;
                });
        }
    };

    JMedia.HistoryManager.init();

})(window);
