package Services;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Remembers enrichment lookups that are known to fail, so the app stops
 * hammering providers for dead IDs and missing entries.
 *
 * <p>Two block reasons, both expiry-based (no manual cleanup needed):
 * <ul>
 *   <li>NOT_FOUND (30 days): the URL 404'd — wrong IDs (e.g. a movie TMDB
 *       ID stored on a series), removed entries. IDs don't come back.</li>
 *   <li>EMPTY (7 days): 200 response with no usable data (empty search,
 *       episode without skip data). Data may appear later, so re-check
 *       weekly instead of every enrichment cycle.</li>
 * </ul>
 * Transient failures (timeouts, 5xx, 429, connection errors) are never
 * blocked — the existing retry cooldowns handle those.
 *
 * <p>Keys are normalized URLs (secret query params stripped) plus
 * endpoint-specific prefixes where callers need them. Blocks are lifted
 * early when the user changes IDs or force-refreshes
 * (see {@link #evictContaining}), so manual corrections take effect
 * immediately instead of waiting out the TTL.
 *
 * <p>Static on purpose, matching the existing static enrichment caches:
 * usable from static contexts, no injection required, trivially testable.
 */
public final class EnrichmentNegativeCache {

    public static final Duration NOT_FOUND_TTL = Duration.ofDays(30);
    public static final Duration EMPTY_TTL = Duration.ofDays(7);

    private static final Pattern SECRET_PARAM =
            Pattern.compile("(?i)([?&])(api_?key)=[^&]*");

    private static final Map<String, Long> BLOCKS = new ConcurrentHashMap<>();

    private EnrichmentNegativeCache() {
    }

    /** True when this key is currently blocked (unknown keys and expired entries: false). */
    public static boolean isBlocked(String key) {
        if (key == null) {
            return false;
        }
        Long expiry = BLOCKS.get(key);
        if (expiry == null) {
            return false;
        }
        if (isExpired(expiry, System.currentTimeMillis())) {
            BLOCKS.remove(key, expiry);
            return false;
        }
        return true;
    }

    /**
     * Records a 404 for this key.
     *
     * @return true when this call newly blocked the key (caller should log
     *         once); false when it was already blocked (caller stays quiet).
     */
    public static boolean rememberNotFound(String key) {
        return remember(key, NOT_FOUND_TTL);
    }

    /**
     * Records an empty-but-successful lookup for this key.
     *
     * @return true when this call newly blocked the key.
     */
    public static boolean rememberEmpty(String key) {
        return remember(key, EMPTY_TTL);
    }

    private static boolean remember(String key, Duration ttl) {
        if (key == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long previous = BLOCKS.put(key, now + ttl.toMillis());
        return previous == null || isExpired(previous, now);
    }

    /** Lifts every block whose key contains the given fragment (e.g. a corrected TMDB/IMDb ID). */
    public static void evictContaining(String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return;
        }
        BLOCKS.keySet().removeIf(k -> k != null && k.contains(fragment));
    }

    /**
     * Normalizes a fetch URL into a cache key: strips secret query params
     * ({@code api_key}/{@code apikey}, any case) so keys never retain
     * credentials and key rotation doesn't orphan blocks. Identity params
     * (IDs, titles, seasons) are preserved for per-ID granularity.
     */
    public static String keyForUrl(String url) {
        if (url == null) {
            return null;
        }
        try {
            return SECRET_PARAM.matcher(url).replaceAll("$1$2=***");
        } catch (Exception e) {
            return url;
        }
    }

    static boolean isExpired(long expiryEpochMillis, long nowEpochMillis) {
        return nowEpochMillis >= expiryEpochMillis;
    }

    // ---- test hooks ----

    static void putForTest(String key, long expiryEpochMillis) {
        BLOCKS.put(key, expiryEpochMillis);
    }

    static void clearForTest() {
        BLOCKS.clear();
    }

    static int sizeForTest() {
        return BLOCKS.size();
    }
}
