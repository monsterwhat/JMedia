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
function updateQueueCount() {
    const queueCountSpan = document.getElementById('queueCount');
    const songQueueTableBody = document.querySelector('#songQueueTable tbody');
    if (queueCountSpan && songQueueTableBody) {
        const newCount = songQueueTableBody.children.length.toString();
        console.log("[songQueue.js] updateQueueCount: Updating queue count to", newCount);
        queueCountSpan.textContent = newCount;
    } else {
        console.log("[songQueue.js] updateQueueCount: queueCountSpan or songQueueTableBody not found.");
    }
}

// -------------------------
// Infinite scroll variables
// -------------------------
let queueOffset = 0;
const queueLimit = 50;
let totalQueueSize = Infinity;
let isFetchingQueue = false;

// -------------------------
// Load a page of the queue
// -------------------------
function loadQueuePage() {
    console.log("[songQueue.js] loadQueuePage called. isFetchingQueue:", isFetchingQueue, "queueOffset:", queueOffset, "totalQueueSize:", totalQueueSize);
    if (isFetchingQueue || queueOffset >= totalQueueSize) {
        console.log("[songQueue.js] loadQueuePage: Aborting due to fetching in progress or end of queue.");
        return;
    }
    isFetchingQueue = true;

    fetch(`/api/music/ui/queue-fragment?offset=${queueOffset}&limit=${queueLimit}`, {
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
            console.error("[songQueue.js] loadQueuePage: #songQueueTable tbody not found.");
            isFetchingQueue = false;
            return;
        }

        // Manually swap the HTML
        if (queueOffset === 0) {
            tbody.innerHTML = data.html;
        } else {
            tbody.insertAdjacentHTML('beforeend', data.html);
        }
        
        // Update offset and total size from the JSON response
        queueOffset += queueLimit;
        totalQueueSize = data.totalQueueSize; // Get totalQueueSize from JSON
        isFetchingQueue = false;

        console.log("[songQueue.js] loadQueuePage: Request successful. New queueOffset:", queueOffset, "totalQueueSize:", totalQueueSize);

        // Apply marquee effect to new rows
        const rows = tbody.querySelectorAll('tr');
        rows.forEach(row => {
            const titleCell = row.querySelector('td:nth-child(2)');
            if (titleCell)
                applyMarqueeEffectToQueue(titleCell);
        });

        updateQueueCount();
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

    table.dataset.totalQueueSize = totalQueueSize;

    // Clear queue
    if (clearQueueBtn) {
        clearQueueBtn.addEventListener('click', () => {
            fetch('/api/music/queue/clear', {
                method: 'POST',
                headers: {'Accept': 'application/json'}
            })
            .then(response => {
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
                queueOffset = 0;
                totalQueueSize = data.totalQueueSize; // Get totalQueueSize from JSON
                // No need to call loadQueuePage() here, as the HTML is already in data.html
                updateQueueCount();
            })
            .catch(error => {
                console.error("[songQueue.js] clearQueueBtn click: Request failed:", error);
            });
        });
    }

    // Infinite scroll listener
    if (queueTabContent) {
        queueTabContent.addEventListener('scroll', () => {
            if (queueTabContent.scrollTop + queueTabContent.clientHeight >= queueTabContent.scrollHeight - 50) {
                loadQueuePage();
            }
        });
    }

    // Expose a global function to refresh the queue
    window.refreshQueue = () => {
        queueOffset = 0; // Reset offset to load from the beginning
        totalQueueSize = Infinity; // Reset total size
        loadQueuePage();
    };

    // Initial load
    window.refreshQueue();
});

// Global function to handle queue actions (skip/remove)
window.handleQueueAction = (action, index) => {
    console.log(`[songQueue.js] handleQueueAction: ${action} at index ${index}`);
    let url = '';
    if (action === 'skip') {
        url = `/api/music/ui/queue/skip-to/${index}`;
    } else if (action === 'remove') {
        url = `/api/music/ui/queue/remove/${index}`;
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
        // After any action, reset offset and update totalQueueSize
        queueOffset = 0; // Reset offset to load from the beginning next time
        totalQueueSize = data.totalQueueSize; // Get totalQueueSize from JSON
        updateQueueCount();
        // Optionally, re-apply marquee effect to new rows if needed
        const rows = tbody.querySelectorAll('tr');
        rows.forEach(row => {
            const titleCell = row.querySelector('td:nth-child(2)');
            if (titleCell)
                applyMarqueeEffectToQueue(titleCell);
        });
    })
    .catch(error => {
        console.error(`[songQueue.js] handleQueueAction: Request failed for ${action} at index ${index}:`, error);
    });
};
