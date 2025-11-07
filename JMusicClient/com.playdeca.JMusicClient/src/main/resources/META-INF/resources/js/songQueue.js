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
        queueCountSpan.textContent = songQueueTableBody.children.length.toString();
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
    if (isFetchingQueue || queueOffset >= totalQueueSize)
        return;
    isFetchingQueue = true;

    htmx.ajax('GET', `/api/music/ui/queue-fragment?offset=${queueOffset}&limit=${queueLimit}`, {
        target: '#songQueueTable tbody',
        swap: queueOffset === 0 ? 'innerHTML' : 'afterend',
        headers: {'Content-Type': 'application/json'}
    }).then(() => {
        const table = document.getElementById('songQueueTable');
        const tbody = table.querySelector('tbody');

        // Update offset and total size
        queueOffset += queueLimit;
        totalQueueSize = parseInt(table.dataset.totalQueueSize || totalQueueSize);
        isFetchingQueue = false;

        // Apply marquee effect to new rows
        const rows = tbody.querySelectorAll('tr');
        rows.forEach(row => {
            const titleCell = row.querySelector('td:nth-child(2)');
            if (titleCell)
                applyMarqueeEffectToQueue(titleCell);
        });

        updateQueueCount();
    });
}

// -------------------------
// DOM Ready
// -------------------------
document.addEventListener('DOMContentLoaded', () => {
    const toggleQueueBtn = document.getElementById('toggleQueueBtn');
    const queueContent = document.getElementById('queueContent');
    const songQueueCard = document.getElementById('songQueueCard');
    const clearQueueBtn = document.getElementById('clearQueueBtn');
    const table = document.getElementById('songQueueTable');
    
    table.dataset.totalQueueSize = totalQueueSize;

    // Toggle queue visibility
    if (toggleQueueBtn && queueContent && songQueueCard) {
        toggleQueueBtn.addEventListener('click', () => {
            const isHidden = queueContent.style.display === 'none';
            queueContent.style.display = isHidden ? 'block' : 'none';
            toggleQueueBtn.querySelector('i').className = isHidden ? 'pi pi-angle-down' : 'pi pi-angle-up';
            songQueueCard.style.height = isHidden ? 'calc(50vh - 100px)' : 'auto';
        });
    }

    // Clear queue
    if (clearQueueBtn) {
        clearQueueBtn.addEventListener('click', () => {
            htmx.ajax('POST', '/api/music/queue/clear', {
                swap: 'none',
                headers: {'Content-Type': 'application/json'}
            }).then(() => {
                const tbody = document.querySelector('#songQueueTable tbody');
                tbody.innerHTML = ''; // clear table
                queueOffset = 0;
                totalQueueSize = Infinity;
                loadQueuePage(); // reload first page (empty or initial)
                updateQueueCount();
            });
        });
    }

    // Infinite scroll listener
    if (queueContent) {
        queueContent.addEventListener('scroll', () => {
            if (queueContent.scrollTop + queueContent.clientHeight >= queueContent.scrollHeight - 50) {
                loadQueuePage();
            }
        });
    }

    // Initial load
    loadQueuePage();
});
