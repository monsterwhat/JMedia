package Services;

import Models.Video.LiveGroupCategory;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stable live-TV group → Xtream {@code category_id} mapping.
 *
 * <p>The previous scheme derived the id from a name hash inside an 8M slot
 * space. Two different groups could land on the same id; the category list then
 * kept only the first name while the other group's channels still reported the
 * shared id, so a client showed them under the wrong group. The mapping is now
 * persisted in {@link LiveGroupCategory} and guaranteed unique.
 *
 * <p>Compatibility: a group that had a legacy hash id keeps it (so existing
 * client caches and favourites survive), as long as no other group already
 * owns that id. Collisions are allocated above {@link #LEGACY_ID_MAX}.
 */
@ApplicationScoped
public class LiveGroupService {

    private static final Logger LOG = LoggerFactory.getLogger(LiveGroupService.class);

    public static final long LIVE_GROUP_ID_BASE = 1_000_000L;
    private static final long LEGACY_ID_RANGE = 8_000_000L;
    /** Highest id the legacy 1M..8,999,999 hash could produce. */
    static final long LEGACY_ID_MAX = LIVE_GROUP_ID_BASE + LEGACY_ID_RANGE - 1;

    private static final Map<String, String> LEGACY_IDS = new ConcurrentHashMap<>();
    private static final AtomicLong CATEGORY_GENERATION = new AtomicLong();

    /** Bumped when the live category list may have changed (import/delete/visibility). */
    public static long categoryGeneration() {
        return CATEGORY_GENERATION.get();
    }

    public static void invalidateCategories() {
        CATEGORY_GENERATION.incrementAndGet();
    }

    /** The pre-1.31 name hash, kept so existing client category ids stay valid. */
    public static String legacyId(String normalizedGroup) {
        if (normalizedGroup == null || normalizedGroup.isBlank()) {
            return "0";
        }
        return LEGACY_IDS.computeIfAbsent(normalizedGroup, key -> {
            long h = UUID.nameUUIDFromBytes(("live-group:" + key).getBytes(StandardCharsets.UTF_8))
                    .getMostSignificantBits() & Long.MAX_VALUE;
            return String.valueOf(LIVE_GROUP_ID_BASE + (h % LEGACY_ID_RANGE));
        });
    }

    /** Id for one group; "0" for an empty group. */
    public String idFor(String normalizedGroup) {
        if (normalizedGroup == null || normalizedGroup.isBlank()) {
            return "0";
        }
        return idsFor(List.of(normalizedGroup)).getOrDefault(normalizedGroup, "0");
    }

    /**
     * Resolve ids for every key, allocating and persisting rows for new groups.
     * Idempotent: existing mappings never change.
     */
    public synchronized Map<String, String> idsFor(Collection<String> normalizedGroups) {
        if (normalizedGroups == null || normalizedGroups.isEmpty()) {
            return Map.of();
        }
        Map<String, String> ids = loadAll();
        Map<String, String> planned = planNewIds(ids, normalizedGroups);
        if (planned.isEmpty()) {
            return ids;
        }
        ids.putAll(planned);

        List<LiveGroupCategory> rows = new ArrayList<>(planned.size());
        for (Map.Entry<String, String> entry : planned.entrySet()) {
            LiveGroupCategory row = new LiveGroupCategory();
            row.groupKey = entry.getKey();
            row.categoryId = parseId(entry.getValue());
            row.createdAt = LocalDateTime.now();
            rows.add(row);
        }
        try {
            QuarkusTransaction.requiringNew().run(() -> rows.forEach(row -> row.persist()));
        } catch (RuntimeException e) {
            // The response for this request is still consistent; the next request
            // retries. Legacy ids are deterministic, so only collision-allocated
            // ids could shift — log loudly because that would break client caches.
            LOG.warn("[LiveGroups] could not persist {} new category ids: {}", rows.size(), e.getMessage());
        }
        return ids;
    }

    private Map<String, String> loadAll() {
        Map<String, String> ids = new HashMap<>();
        try {
            for (LiveGroupCategory row : LiveGroupCategory.<LiveGroupCategory>listAll()) {
                if (row.groupKey != null && row.categoryId != null) {
                    ids.put(row.groupKey, String.valueOf(row.categoryId));
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("[LiveGroups] could not read category ids: {}", e.getMessage());
        }
        return ids;
    }

    /**
     * Pure allocation step (no DB): returns ids for keys not already mapped.
     * Extracted so the collision rule can be unit-tested.
     */
    static Map<String, String> planNewIds(Map<String, String> existing, Collection<String> keys) {
        Map<String, String> planned = new LinkedHashMap<>();
        if (keys == null || keys.isEmpty()) {
            return planned;
        }
        Set<String> used = new HashSet<>(existing.values());
        long maxAllocated = LEGACY_ID_MAX;
        for (String value : existing.values()) {
            maxAllocated = Math.max(maxAllocated, parseId(value));
        }
        for (String key : keys) {
            if (key == null || key.isBlank() || existing.containsKey(key) || planned.containsKey(key)) {
                continue;
            }
            long candidate = parseId(legacyId(key));
            if (used.contains(String.valueOf(candidate))) {
                candidate = ++maxAllocated;
            } else {
                maxAllocated = Math.max(maxAllocated, candidate);
            }
            used.add(String.valueOf(candidate));
            planned.put(key, String.valueOf(candidate));
        }
        return planned;
    }

    static long parseId(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
