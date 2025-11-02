// New function to apply marquee effect conditionally to queue table cells
function applyMarqueeEffectToQueue(element) {
    const span = element.querySelector('span');
    if (span) {
        // Temporarily remove overflow to measure scrollWidth correctly
        const originalOverflow = element.style.overflow;
        element.style.overflow = 'visible';

        if (span.scrollWidth > element.clientWidth) {
            span.classList.add('marquee');
            span.classList.remove('no-scroll');
        } else {
            span.classList.remove('marquee');
            span.classList.add('no-scroll');
        }
        element.style.overflow = originalOverflow; // Restore original overflow
    }
}

// Function to update the song queue count
function updateQueueCount() {
    const queueCountSpan = document.getElementById('queueCount');
    const songQueueTableBody = document.querySelector('#songQueueTable tbody');
    if (queueCountSpan && songQueueTableBody) {
        const rowCount = songQueueTableBody.children.length;
        queueCountSpan.textContent = rowCount.toString();
    }
}

document.addEventListener('DOMContentLoaded', () => {
    const toggleQueueBtn = document.getElementById('toggleQueueBtn');
    const queueContent = document.getElementById('queueContent');
    const songQueueCard = document.getElementById('songQueueCard');

    if (toggleQueueBtn && queueContent && songQueueCard) {
        toggleQueueBtn.addEventListener('click', () => {
            const isHidden = queueContent.style.display === 'none';
            queueContent.style.display = isHidden ? 'block' : 'none';
            toggleQueueBtn.querySelector('i').className = isHidden ? 'pi pi-angle-down' : 'pi pi-angle-up';
            songQueueCard.style.height = isHidden ? 'calc(50vh - 100px)' : 'auto';
        });
    }

    const clearQueueBtn = document.getElementById('clearQueueBtn');
    if (clearQueueBtn) {
        clearQueueBtn.addEventListener('click', () => {
            htmx.ajax('POST', '/api/music/queue/clear', {
                swap: 'none',
                headers: {'Content-Type': 'application/json'}
            }).then(() => {
                htmx.trigger('#songQueueTable', 'load');
                updateQueueCount(); // Update count after clearing
            });
        });
    }

    const songQueueTable = document.getElementById('songQueueTable');
    if (songQueueTable) {
        songQueueTable.setAttribute('hx-get', '/api/music/ui/queue-fragment');
        songQueueTable.setAttribute('hx-trigger', 'load, queueChanged from:body');
        htmx.process(songQueueTable);

        // Listen for afterSwap to apply marquee effect and update count
        songQueueTable.addEventListener('htmx:afterSwap', (event) => {
            const titleCells = event.detail.target.querySelectorAll('td:nth-child(1)');
            const artistCells = event.detail.target.querySelectorAll('td:nth-child(2)');

            titleCells.forEach(applyMarqueeEffectToQueue);
            artistCells.forEach(applyMarqueeEffectToQueue);
            updateQueueCount(); // Update count after HTMX swap
        });
    }

    updateQueueCount(); // Initial update on DOMContentLoaded
});
