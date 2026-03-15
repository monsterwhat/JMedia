// Patch fetch to always include credentials for same-origin requests
const originalFetch = window.fetch;
window.fetch = function(input, init = {}) {
    const options = { ...init };
    if (!options.credentials) {
        options.credentials = 'same-origin';
    }
    return originalFetch.call(this, input, options);
};
