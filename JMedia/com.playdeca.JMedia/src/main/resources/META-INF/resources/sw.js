/**
 * Simple Service Worker for JMedia Video Platform
 * Focuses on caching and basic offline support
 */

const CACHE_NAME = 'jmedia-video-v1';
const CACHE_VERSION = '1.0.0';

// Install event
self.addEventListener('install', (event) => {
    console.log('SW: Installing version', CACHE_VERSION);
    event.skipWaiting();
    self.clients.claim();
});

// Activate event
self.addEventListener('activate', (event) => {
    console.log('SW: Activated');
    event.clients.claim();
});

// Fetch event with network-first strategy
self.addEventListener('fetch', (event) => {
    const request = event.request;
    const url = new URL(request.url);
    
    // Skip non-GET requests and external resources
    if (request.method !== 'GET' || url.origin !== self.location.origin) {
        return fetch(request);
    }
    
    event.respondWith(
        caches.open(CACHE_NAME)
            .then((cache) => {
                return cache.match(request)
                    .then((response) => {
                        // Return cached if available
                        if (response) {
                            return response;
                        }
                        
                        // Otherwise fetch from network
                        return fetch(request)
                            .then((networkResponse) => {
                                // Cache successful responses
                                if (networkResponse.ok && networkResponse.status === 200) {
                                    cache.put(request, networkResponse.clone()).catch(() => {
                                        // Ignore cache errors
                                    });
                                }
                                return networkResponse;
                            })
                            .catch(() => {
                                // Network failed, try cache as fallback
                                return cache.match(request).then(cached => cached || new Response('Service Unavailable', {status: 503}));
                            });
                    });
            })
            .catch(() => {
                // Cache failed, fetch from network
                return fetch(request);
            })
    );
});

// Message handling
self.addEventListener('message', (event) => {
    if (event.data && event.data.type === 'SKIP_WAITING') {
        self.skipWaiting();
    }
});

console.log('SW: Loaded');