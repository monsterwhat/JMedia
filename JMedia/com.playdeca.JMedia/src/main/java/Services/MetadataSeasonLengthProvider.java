package Services;

import Models.Video.Series;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 2 {@link SeasonLengthProvider}: serves the per-season episode counts
 * persisted on {@link Series#seasonEpisodeCounts} during IMDb Dev enrichment
 * (see {@code VideoMetadataService.enrichWithImdbDevMetadata}).
 *
 * <p>Phase 4b adds the per-show override: when
 * {@link Series#forceAbsoluteNumbering} is TRUE and
 * {@link Series#absoluteSeasonLengths} parses to a valid map, the override
 * wins over the enriched column (highest precedence). An invalid override
 * logs a warning and falls through to the enriched column, then null.
 *
 * <p>Lookup is an exact match only, after normalizing both the query key and
 * every stored title with {@link SmartNamingService#cleanShowName} — no fuzzy
 * logic. Returns {@code null} when the show is unknown, has no persisted map,
 * or on any error (callers treat null as "keep current behavior", so this
 * provider never throws).
 */
@ApplicationScoped
public class MetadataSeasonLengthProvider implements SeasonLengthProvider {

    private static final Logger LOG = LoggerFactory.getLogger(MetadataSeasonLengthProvider.class);

    /**
     * Cleaned show name -&gt; immutable season lengths. Misses are deliberately
     * not cached so a later enrichment becomes visible on the next lookup.
     * Override-derived maps are never cached either, so a manage-panel edit
     * takes effect immediately. Shows number in the hundreds, so no eviction
     * is needed for now.
     */
    private final Map<String, Map<Integer, Integer>> cache = new ConcurrentHashMap<>();

    @Override
    public Map<Integer, Integer> getSeasonLengths(String showKey) {
        try {
            if (showKey == null || showKey.isBlank()) {
                return null;
            }
            String cleaned = SmartNamingService.cleanShowName(showKey);
            if (cleaned == null || cleaned.isBlank()) {
                return null;
            }
            Series match = null;
            for (io.quarkus.hibernate.orm.panache.PanacheEntityBase entity : Series.listAll()) {
                if (!(entity instanceof Series series) || series.title == null) {
                    continue;
                }
                if (cleaned.equals(SmartNamingService.cleanShowName(series.title))) {
                    match = series;
                    break;
                }
            }
            if (match == null) {
                return null;
            }
            // Phase 4b: override first (only when forced on and valid).
            if (Boolean.TRUE.equals(match.forceAbsoluteNumbering)) {
                Map<Integer, Integer> override = parseAndValidateOverride(match.absoluteSeasonLengths);
                if (override != null) {
                    return Collections.unmodifiableMap(new TreeMap<>(override));
                }
                if (match.absoluteSeasonLengths != null && !match.absoluteSeasonLengths.isBlank()) {
                    LOG.warn("[SeasonLengths] Invalid absoluteSeasonLengths override for '{}', "
                            + "falling through to enriched lengths", match.title);
                }
            }
            if (match.seasonEpisodeCounts == null
                    || match.seasonEpisodeCounts.isBlank()) {
                return null;
            }
            Map<Integer, Integer> cached = cache.get(cleaned);
            if (cached != null) {
                return cached;
            }
            Map<Integer, Integer> lengths =
                    VideoMetadataService.parseSeasonEpisodeCountsJson(match.seasonEpisodeCounts);
            if (lengths == null || lengths.isEmpty()) {
                return null;
            }
            Map<Integer, Integer> frozen =
                    Collections.unmodifiableMap(new TreeMap<>(lengths));
            cache.put(cleaned, frozen);
            return frozen;
        } catch (Exception e) {
            LOG.warn("[SeasonLengths] getSeasonLengths failed for '{}': {}", showKey, e.getMessage());
            return null;
        }
    }

    /**
     * Phase 4b pure validator for the {@code absoluteSeasonLengths} override
     * column: parses the raw JSON and requires seasons contiguous from 1
     * with positive counts. Returns the map, or null when missing/invalid
     * (callers fall through to the enriched column). Never throws.
     */
    public static Map<Integer, Integer> parseAndValidateOverride(String json) {
        try {
            Map<Integer, Integer> parsed = VideoMetadataService.parseSeasonEpisodeCountsJson(json);
            if (parsed == null || parsed.isEmpty()) {
                return null;
            }
            if (!isContiguousFromOne(parsed)) {
                LOG.warn("[SeasonLengths] Override lengths not contiguous from 1: {}", json);
                return null;
            }
            return parsed;
        } catch (Exception e) {
            LOG.warn("[SeasonLengths] Failed to validate override JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Phase 4b pure precedence selector (offline-testable): the forced-on
     * override wins when valid; anything invalid or unforced falls through
     * to the enriched column; null when neither yields lengths. Never throws.
     */
    public static Map<Integer, Integer> selectLengths(String overrideJson, Boolean forceFlag,
            String enrichedJson) {
        try {
            if (Boolean.TRUE.equals(forceFlag)) {
                Map<Integer, Integer> override = parseAndValidateOverride(overrideJson);
                if (override != null) {
                    return override;
                }
            }
            return VideoMetadataService.parseSeasonEpisodeCountsJson(enrichedJson);
        } catch (Exception e) {
            LOG.warn("[SeasonLengths] selectLengths failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Requires exactly the keys {1..N} with positive counts. The resolver
     * refuses to guess on gapped data, so overrides must meet the same bar.
     */
    static boolean isContiguousFromOne(Map<Integer, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return false;
        }
        int size = counts.size();
        for (int season = 1; season <= size; season++) {
            Integer count = counts.get(season);
            if (count == null || count <= 0) {
                return false;
            }
        }
        return true;
    }
}
