// Convert UTC timestamps to local time
function convertUTCTimesToLocal() {
    document.querySelectorAll('[data-utc]').forEach(el => {
        const utcStr = el.getAttribute('data-utc');
        if (!utcStr) return;
        
        try {
            const date = new Date(utcStr);
            const formatted = date.toLocaleDateString('en-US', { 
                month: 'short', 
                day: 'numeric', 
                year: 'numeric',
                hour: 'numeric',
                minute: '2-digit',
                hour12: true 
            });
            el.textContent = formatted;
        } catch (e) {
            console.error('[Timezone] Error converting time:', e);
        }
    });
}

// Run on page load and HTMX swaps
document.addEventListener('DOMContentLoaded', convertUTCTimesToLocal);
document.addEventListener('htmx:afterSwap', convertUTCTimesToLocal);
