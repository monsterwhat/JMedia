package Services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline tests for the enrichment negative cache.
 * No network, no Quarkus — pure in-memory + string logic.
 */
class EnrichmentNegativeCacheTest {

    @BeforeEach
    void clear() {
        EnrichmentNegativeCache.clearForTest();
    }

    @Test
    void testBlockAndSkip() {
        assertFalse(EnrichmentNegativeCache.isBlocked("https://api.themoviedb.org/3/tv/333584"));
        assertTrue(EnrichmentNegativeCache.rememberNotFound("https://api.themoviedb.org/3/tv/333584"));
        assertTrue(EnrichmentNegativeCache.isBlocked("https://api.themoviedb.org/3/tv/333584"));
        // Second remember reports already-blocked (callers stay quiet).
        assertFalse(EnrichmentNegativeCache.rememberNotFound("https://api.themoviedb.org/3/tv/333584"));
    }

    @Test
    void testEmptyBlockShorterThanNotFound() {
        assertTrue(EnrichmentNegativeCache.NOT_FOUND_TTL.toDays() >= 30,
                "404 blocks must last at least 30 days");
        assertTrue(EnrichmentNegativeCache.EMPTY_TTL.toDays() >= 1
                        && EnrichmentNegativeCache.EMPTY_TTL.compareTo(EnrichmentNegativeCache.NOT_FOUND_TTL) < 0,
                "empty-result blocks must be shorter than not-found blocks");
    }

    @Test
    void testExpiry() {
        assertTrue(EnrichmentNegativeCache.isExpired(1000L, 1001L));
        assertFalse(EnrichmentNegativeCache.isExpired(1001L, 1000L));
        EnrichmentNegativeCache.putForTest("k", 1000L);
        assertFalse(EnrichmentNegativeCache.isBlocked("k"),
                "expired test entry must read as unblocked (and be evicted)");
        assertEquals(0, EnrichmentNegativeCache.sizeForTest());
    }

    @Test
    void testEvictContaining() {
        EnrichmentNegativeCache.rememberNotFound("https://api.themoviedb.org/3/tv/333584?append_to_response=credits");
        EnrichmentNegativeCache.rememberEmpty("https://api.themoviedb.org/3/search/tv?query=One+Pace");
        EnrichmentNegativeCache.evictContaining("333584");
        assertFalse(EnrichmentNegativeCache.isBlocked("https://api.themoviedb.org/3/tv/333584?append_to_response=credits"));
        assertTrue(EnrichmentNegativeCache.isBlocked("https://api.themoviedb.org/3/search/tv?query=One+Pace"));
        EnrichmentNegativeCache.evictContaining(null);
        EnrichmentNegativeCache.evictContaining("   ");
        assertTrue(EnrichmentNegativeCache.isBlocked("https://api.themoviedb.org/3/search/tv?query=One+Pace"));
    }

    @Test
    void testKeyNormalizationStripsSecrets() {
        assertEquals("https://api.themoviedb.org/3/movie/1?api_key=***&query=x",
                EnrichmentNegativeCache.keyForUrl("https://api.themoviedb.org/3/movie/1?api_key=SECRET&query=x"));
        assertEquals("https://h/?apikey=***&id=5",
                EnrichmentNegativeCache.keyForUrl("https://h/?apikey=SECRET&id=5"));
        assertEquals("https://h/?API_KEY=***",
                EnrichmentNegativeCache.keyForUrl("https://h/?API_KEY=SECRET"));
        // Identity params are preserved for per-ID granularity.
        assertEquals("https://api.themoviedb.org/3/tv/333584?append_to_response=credits",
                EnrichmentNegativeCache.keyForUrl("https://api.themoviedb.org/3/tv/333584?append_to_response=credits"));
        assertNull(EnrichmentNegativeCache.keyForUrl(null));
    }

    @Test
    void testNullSafety() {
        assertFalse(EnrichmentNegativeCache.isBlocked(null));
        assertFalse(EnrichmentNegativeCache.rememberNotFound(null));
        assertFalse(EnrichmentNegativeCache.rememberEmpty(null));
        assertEquals(0, EnrichmentNegativeCache.sizeForTest());
    }
}
