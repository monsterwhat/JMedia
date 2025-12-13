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
    const clearHistoryBtn = document.getElementById('clearHistoryBtn');

    // Clear history
    if (clearHistoryBtn) {
        clearHistoryBtn.addEventListener('click', () => {
            if (!confirm('Are you sure you want to clear your entire playback history? This action cannot be undone.')) {
                return;
            }
            
            const currentProfileId = globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
            fetch(`/api/music/ui/history/clear/${currentProfileId}`, {
                method: 'POST',
                headers: {'Accept': 'application/json'}
            }).then(response => {
                if (!response.ok) {
                    throw new Error(`HTTP error! status: ${response.status}`);
                }
                return response.json(); // Expecting JSON response from clearHistoryUi
            })
                    .then(data => {
                        const tbody = document.querySelector('#songHistoryTable tbody');
                        if (tbody) {
                            tbody.innerHTML = data.html; // Use HTML from response
                        }
                        loadHistoryPage(1); // Reload first page after clearing
                        showToast('History cleared successfully', 'success');
                    })
                    .catch(error => {
                        console.error("[history.js] clearHistoryBtn click: Request failed:", error);
                        showToast('Failed to clear history', 'error');
                    });
        });
    }

    // Expose a global function to refresh the history
    window.refreshHistory = () => {
        loadHistoryPage(1); // Reset page to load from the beginning
    };
});

// Global function to handle history actions (play/remove)
window.handleHistoryAction = (action, id, profileIdParam) => {
    console.log(`[history.js] handleHistoryAction: ${action} with id ${id}`);
    const currentProfileId = profileIdParam || globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    
    if (action === 'play') {
        // Play the song directly by selecting it
        fetch(`/api/music/playback/select/${currentProfileId}/${id}`, {
            method: 'POST',
            headers: {'Accept': 'application/json'}
        })
        .then(response => {
            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }
            return response.json();
        })
        .then(data => {
            showToast('Now playing from history', 'success');
            // Refresh queue if visible
            if (window.refreshQueue) {
                window.refreshQueue();
            }
        })
        .catch(error => {
            console.error(`[history.js] Failed to play song:`, error);
            showToast('Failed to play song', 'error');
        });
    } else if (action === 'remove') {
        // Remove the history entry
        fetch(`/api/music/ui/history/remove/${currentProfileId}/${id}`, {
            method: 'POST',
            headers: {'Accept': 'application/json'}
        })
        .then(response => {
            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }
            return response.json();
        })
        .then(data => {
            const tbody = document.querySelector('#songHistoryTable tbody');
            if (tbody) {
                tbody.innerHTML = data.html; // Update tbody with new HTML
            }
            showToast('Removed from history', 'success');
        })
        .catch(error => {
            console.error(`[history.js] handleHistoryAction: Request failed for ${action} with id ${id}:`, error);
            showToast('Failed to remove from history', 'error');
        });
    } else {
        console.error(`[history.js] handleHistoryAction: Unknown action type: ${action}`);
        return;
    }
};