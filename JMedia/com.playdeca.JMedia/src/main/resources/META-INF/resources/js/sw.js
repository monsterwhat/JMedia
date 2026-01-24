/**
 * Simple Service Worker for JMedia Video Platform
 * Focuses on caching and basic offline support
 */

const CACHE_NAME = 'jmedia-video-v1';
const CACHE_VERSION = '1.0.0';

// Race condition mitigation: cache operation tracking
let cacheOperationQueue = [];
let isProcessingCacheOperation = false;
let cacheOperationSequence = 0;

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

// Race condition mitigation: process cache operations in sequence
function processCacheOperations() {
    if (isProcessingCacheOperation || cacheOperationQueue.length === 0) {
        return;
    }
    
    isProcessingCacheOperation = true;
    
    const processNext = () => {
        if (cacheOperationQueue.length === 0) {
            isProcessingCacheOperation = false;
            return;
        }
        
        const operation = cacheOperationQueue.shift();
        operation().finally(() => {
            setTimeout(processNext, 0);
        });
    };
    
    processNext();
}

// Race condition mitigation: safe cache operations
function safeCacheOperation(cache, request, operation) {
    return new Promise((resolve) => {
        const operationId = ++cacheOperationSequence;
        
        cacheOperationQueue.push(async () => {
            try {
                const result = await operation(cache, request);
                resolve(result);
            } catch (error) {
                console.error(`[SW] Cache operation ${operationId} failed:`, error);
                resolve(null);
            }
        });
        
        processCacheOperations();
    });
}

// Fetch event with network-first strategy and race condition mitigation
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
                return safeCacheOperation(cache, request, async (cache, request) => {
                    const cachedResponse = await cache.match(request);
                    
                    // Return cached if available
                    if (cachedResponse) {
                        // Race condition mitigation: check if cache is stale (>5 minutes)
                        const cacheTime = cachedResponse.headers.get('date');
                        if (cacheTime) {
                            const cacheAge = Date.now() - new Date(cacheTime).getTime();
                            if (cacheAge < 5 * 60 * 1000) { // 5 minutes
                                return cachedResponse;
                            }
                        }
                    }
                    
                    // Otherwise fetch from network with timeout
                    try {
                        const networkResponse = await Promise.race([
                            fetch(request),
                            new Promise((_, reject) => setTimeout(() => reject(new Error('Network timeout')), 8000))
                        ]);
                        
                        // Race condition mitigation: cache successful responses with validation
                        if (networkResponse && networkResponse.ok && networkResponse.status === 200) {
                            await cache.put(request, networkResponse.clone());
                        }
                        return networkResponse;
                    } catch (networkError) {
                        console.warn(`[SW] Network failed for ${request.url}, trying cache fallback:`, networkError);
                        
                        // Network failed, try cache as fallback even if stale
                        const fallbackResponse = await cache.match(request);
                        return fallbackResponse || new Response('Service Unavailable', {status: 503});
                    }
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