document.addEventListener('DOMContentLoaded', () => {
    const tbody = document.getElementById('songTableBody');
    const loadAllBtn = document.getElementById('loadAllBtn');

    if (!tbody || !loadAllBtn)
        return;

    function getOffset() {
        return parseInt(tbody.dataset.offset || "0", 10);
    }
    function getLimit() {
        return parseInt(tbody.dataset.limit || "20", 10);
    }
    function getTotal() {
        return parseInt(tbody.dataset.totalSongs || "0", 10);
    }
    function getPlaylistId() {
        return tbody.dataset.playlistId || "0";
    }

    function updateButton() {
        loadAllBtn.style.display = (getOffset() >= getTotal()) ? 'none' : 'inline-block';
    }

    function loadAll() {
        const offset = getOffset();
        const playlistId = getPlaylistId();
        const remaining = getTotal() - offset;

        if (remaining <= 0 || !playlistId)
            return;

        htmx.ajax('GET', `/api/music/ui/tbody/${playlistId}?offset=${offset}&limit=${remaining}`, {
            target: tbody,
            swap: 'beforeend',
            afterSwap: () => {
                tbody.dataset.offset = getTotal(); // mark all loaded
                updateButton();
            }
        });
    }

    loadAllBtn.addEventListener('click', loadAll);

    // Reset offset after playlist switch
    document.body.addEventListener('htmx:afterSwap', evt => {
        if (evt.detail.target === tbody) {
            tbody.dataset.offset = parseInt(tbody.dataset.offset || tbody.dataset.limit || "20", 10);
            tbody.dataset.limit = tbody.dataset.limit || "20";
            tbody.dataset.totalSongs = tbody.dataset.totalSongs || "0";
            updateButton();
        }
    });

    updateButton();
});
