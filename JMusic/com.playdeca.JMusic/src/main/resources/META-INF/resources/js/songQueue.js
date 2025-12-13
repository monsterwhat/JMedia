// -------------------------
// Marquee effect for overflowing text
// -------------------------
function applyMarqueeEffectToQueue(element) {
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
// Update queue count
// -------------------------
function updateQueueCount(totalSize) {
    const queueCountSpan = document.getElementById('queueCount');
    if (queueCountSpan) {
        queueCountSpan.textContent = totalSize;
    }
}

// Expose updateQueueCount globally so other scripts can call it
window.updateQueueCount = updateQueueCount;

// -------------------------
// Update queue current song highlighting
// -------------------------
function updateQueueCurrentSong(songId) {
    // Remove current-song-row class from all rows in queue
    const allRows = document.querySelectorAll('#songQueueTable tr[data-song-id]');
    allRows.forEach(row => {
        row.classList.remove('current-song-row');
    });

    // Add current-song-row class to the selected row in queue
    const selectedRow = document.querySelector(`#songQueueTable tr[data-song-id="${songId}"]`);
    if (selectedRow) {
        selectedRow.classList.add('current-song-row');
    }
}

// Expose updateQueueCurrentSong globally so musicBar.js can call it
window.updateQueueCurrentSong = updateQueueCurrentSong;

// -------------------------
// Pagination variables
// -------------------------
let currentPage = 1;
const queueLimit = 50;
let totalQueueSize = Infinity;
let isFetchingQueue = false;

// -------------------------
// Load a page of the queue
// -------------------------
function loadQueuePage(page = 1, profileIdParam) { // Added profileIdParam
    if (isFetchingQueue) {
        return;
    }
    isFetchingQueue = true;
    currentPage = page; // Update current page
    const currentProfileId = profileIdParam || globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';

    fetch(`/api/music/ui/queue-fragment/${currentProfileId}?page=${currentPage}&limit=${queueLimit}`, {
        headers: {'Accept': 'application/json'} // Request JSON
    })
            .then(response => {
                if (!response.ok) {
                    throw new Error(`HTTP error! status: ${response.status}`);
                }
                return response.json();
            })
            .then(data => {
                const tbody = document.querySelector('#songQueueTable tbody');
                if (!tbody) {
                    isFetchingQueue = false;
                    return;
                }

                tbody.innerHTML = data.html; // Replace content with new HTML

                totalQueueSize = data.totalQueueSize; // Get totalQueueSize from JSON
                isFetchingQueue = false;

                console.log("[songQueue.js] loadQueuePage: Request successful. CurrentPage:", currentPage, "totalQueueSize:", totalQueueSize);

                // Apply marquee effect to new rows
                const rows = tbody.querySelectorAll('tr');
                rows.forEach(row => {
                    const titleCell = row.querySelector('td:nth-child(2)');
                    if (titleCell)
                        applyMarqueeEffectToQueue(titleCell);
                });

                updateQueueCount(totalQueueSize);
            })
            .catch(error => {
                console.error("[songQueue.js] loadQueuePage: Request failed:", error);
                isFetchingQueue = false;
            });
}

// -------------------------
// DOM Ready
// -------------------------
document.addEventListener('DOMContentLoaded', () => {
    const queueTabContent = document.getElementById('queueTabContent');
    const clearQueueBtn = document.getElementById('clearQueueBtn');
    const table = document.getElementById('songQueueTable');

    // Clear queue
    if (clearQueueBtn) {
        clearQueueBtn.addEventListener('click', () => {
            const currentProfileId = globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
            fetch(`/api/music/queue/clear/${currentProfileId}`, {
                method: 'POST',
                headers: {'Accept': 'application/json'}
            }).then(response => {
                if (!response.ok) {
                    throw new Error(`HTTP error! status: ${response.status}`);
                }
                return response.json(); // Expecting JSON response from clearQueueUi
            })
                    .then(data => {
                        const tbody = document.querySelector('#songQueueTable tbody');
                        if (tbody) {
                            tbody.innerHTML = data.html; // Use HTML from response
                        }
                        // Update queue count immediately when cleared
                        if (window.updateQueueCount) {
                            window.updateQueueCount(0);
                        }
                        loadQueuePage(1); // Reload first page after clearing
                    })
                    .catch(error => {
                        console.error("[songQueue.js] clearQueueBtn click: Request failed:", error);
                    });
        });
    }

    // Infinite scroll listener - Removed for classic pagination
    // if (queueTabContent) {
    //     queueTabContent.addEventListener('scroll', () => {
    //         // Check if the "Load More" button is present and visible
    //         const loadMoreButton = document.getElementById('loadMoreQueueRow');
    //         if (loadMoreButton && loadMoreButton.offsetParent !== null) { // offsetParent checks if element is visible
    //             const rect = loadMoreButton.getBoundingClientRect();
    //             // Trigger load if button is in view or near the bottom
    //             if (rect.top <= (window.innerHeight || document.documentElement.clientHeight) + 100) {
    //                 // Trigger the hx-get on the button
    //                 htmx.trigger(loadMoreButton.querySelector('button'), 'click');
    //             }
    //         }
    //     });
    // }

    // Expose a global function to refresh the queue
    window.refreshQueue = () => {
        loadQueuePage(1); // Reset page to load from the beginning
    };

    // Initial load
    window.refreshQueue();
});

// Global function to handle queue actions (skip/remove)
window.handleQueueAction = (action, index, profileIdParam) => { // Added profileIdParam
    console.log(`[songQueue.js] handleQueueAction: ${action} at index ${index}`);
    const currentProfileId = profileIdParam || globalActiveProfileId || localStorage.getItem('activeProfileId') || '1';
    let url = '';
    if (action === 'skip') {
        url = `/api/music/queue/skip-to/${currentProfileId}/${index}`;
    } else if (action === 'remove') {
        url = `/api/music/queue/remove/${currentProfileId}/${index}`;
    } else {
        console.error(`[songQueue.js] handleQueueAction: Unknown action type: ${action}`);
        return;
    }

    fetch(url, {
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
                const tbody = document.querySelector('#songQueueTable tbody');
                if (tbody) {
                    tbody.innerHTML = data.html; // Update tbody with new HTML
                }
                
                // Update queue count directly from response data if available
                if (data.totalQueueSize !== undefined) {
                    updateQueueCount(data.totalQueueSize);
                } else {
                    loadQueuePage(1); // Reload first page after action to get the count
                }
                
                // Show success message based on action
                if (action === 'skip') {
                    showToast('Skipped to selected song in queue', 'success');
                } else if (action === 'remove') {
                    showToast('Song removed from queue', 'success');
                }
            })
            .catch(error => {
                console.error(`[songQueue.js] handleQueueAction: Request failed for ${action} at index ${index}:`, error);
                showToast(`Failed to ${action} song from queue`, 'error');
            });
};
