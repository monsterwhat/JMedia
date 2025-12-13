// -------------------------
// Marquee effect for overflowing text
// -------------------------
function applyMarqueeEffectToHistory(element) {
    const span = element.querySelector('span');
    if (span) {
        const originalOverflow = element.style.overflow;
        element.style.overflow = 'visible';

        if (span.scrollWidth > element.clientWidth) {
            span.classList.add('marquee');
            span.classList.remove('no-scroll');
        } else {
            span.classList.remove('marquee');
            span.classList.add('no-scroll');
        }

        element.style.overflow = originalOverflow;
    }
}

// -------------------------
// Pagination variables
// -------------------------
let currentHistoryPage = 1;
const historyLimit = 50;
let totalHistorySize = Infinity;
let isFetchingHistory = false;

// -------------------------
// Load a page of the history
// -------------------------
function loadHistoryPage(page = 1, profileIdParam) {
    if (isFetchingHistory) {
        return;
    }
    isFetchingHistory = true;
    currentHistoryPage = page; // Update current page
    const currentProfileId = profileIdParam || globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';

    fetch(`/api/music/ui/history-fragment/${currentProfileId}?page=${currentHistoryPage}&limit=${historyLimit}`, {
        headers: {'Accept': 'application/json'} // Request JSON
    })
            .then(response => {
                if (!response.ok) {
                    throw new Error(`HTTP error! status: ${response.status}`);
                }
                return response.json();
            })
            .then(data => {
                const tbody = document.querySelector('#songHistoryTable tbody');
                if (!tbody) {
                    isFetchingHistory = false;
                    return;
                }

                tbody.innerHTML = data.html; // Replace content with new HTML

                // Re-initialize HTMX on the new content
                if (window.htmx && window.htmx.process) {
                    window.htmx.process(tbody);
                    console.log("[history.js] HTMX re-initialized on new content");
                }

                totalHistorySize = data.totalHistorySize; // Get totalHistorySize from JSON
                isFetchingHistory = false;

                console.log("[history.js] loadHistoryPage: Request successful. CurrentPage:", currentHistoryPage, "totalHistorySize:", totalHistorySize);

                // Apply marquee effect to new rows
                const rows = tbody.querySelectorAll('tr');
                rows.forEach(row => {
                    const titleCell = row.querySelector('td:nth-child(2)');
                    if (titleCell)
                        applyMarqueeEffectToHistory(titleCell);
                });
            })
            .catch(error => {
                console.error("[history.js] loadHistoryPage: Request failed:", error);
                isFetchingHistory = false;
            });
}

// -------------------------
// Load history on first tab click
// -------------------------
let historyLoaded = false;
window.loadHistoryOnFirstClick = () => {
    if (!historyLoaded) {
        historyLoaded = true;
        loadHistoryPage(1); // Load first page of history
    }
};

// -------------------------
// DOM Ready
// -------------------------
document.addEventListener('DOMContentLoaded', () => {
    const historyTabContent = document.getElementById('historyTabContent');

    // Expose a global function to refresh the history
    window.refreshHistory = () => {
        console.log('[history.js] refreshHistory called, current page:', currentHistoryPage);
        loadHistoryPage(currentHistoryPage); // Maintain current page
    };

    // Expose a function to refresh history from the beginning (for manual refresh)
    window.refreshHistoryFromStart = () => {
        loadHistoryPage(1); // Reset page to load from the beginning
    };
});

// Note: History playback is now handled via HTMX on the row click
// Individual song removal from history is no longer supported