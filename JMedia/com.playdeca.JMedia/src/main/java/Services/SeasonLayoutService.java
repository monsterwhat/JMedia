package Services;

import Models.Video.Series;
import Models.Video.Video;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Works out which season an episode should be shown under in the UI.
 *
 * <p>The library keeps whatever season layout the release shipped with (folders
 * and file names are never rewritten). Metadata, however, describes the show
 * the way a viewer expects it — e.g. Black Clover is 170 episodes in season 1
 * and the 2026 run in season 2, even when a release splits season 1 across four
 * folders. This service bridges the two: it orders the local episodes and, when
 * TMDb's season counts fit the local run, groups them by those counts for
 * display only.
 *
 * <p>Rules, in order:
 * <ol>
 *   <li>No metadata, or metadata that cannot describe this run → folder layout.</li>
 *   <li>Every local season fits inside the same numbered metadata season
 *       (partial seasons included) → folder layout, with the metadata counts
 *       still used for the "N of M available" hint.</li>
 *   <li>Totals match but the split differs (e.g. 58/78/12 vs 62/74/12) → the
 *       ordered episodes are re-split by the metadata counts.</li>
 * </ol>
 *
 * <p>Playback, subtitles, progress and file paths are untouched: only the
 * grouping and the episode numbers shown in the UI change.
 */
@ApplicationScoped
public class SeasonLayoutService {

    private static final Logger LOG = LoggerFactory.getLogger(SeasonLayoutService.class);

    @Inject
    TmdbCatalogService tmdbCatalogService;

    /** Resolved display layout for one series. */
    public static final class Layout {
        /** Display season number -> episodes in display order (season 0 = specials, last). */
        public final Map<Integer, List<Video>> seasons = new LinkedHashMap<>();
        /** Display season number -> episodes the metadata expects (0 when unknown). */
        public final Map<Integer, Integer> expected = new LinkedHashMap<>();
        /** Video id -> episode number to show (only set when the split was re-mapped). */
        public final Map<Long, Integer> displayEpisodeNumber = new LinkedHashMap<>();
        /** True when the metadata split differed from the folder split and won. */
        public boolean regrouped;

        public List<Video> episodesFor(Integer seasonNumber) {
            return seasons.getOrDefault(seasonNumber, List.of());
        }
    }

    public Layout resolve(String seriesTitle) {
        List<Video> episodes = Video.list(
                "type = ?1 and seriesTitle = ?2 and isActive = ?3"
                        + " and (contentType is null or contentType = 'episode')"
                        + " and seasonNumber is not null",
                "episode", seriesTitle, true);
        return resolve(seriesTitle, episodes);
    }

    /**
     * @param allEpisodes every active episode of the series, any season, unsorted
     */
    public Layout resolve(String seriesTitle, List<Video> allEpisodes) {
        Layout layout = new Layout();

        List<Video> normal = new ArrayList<>();
        Map<Integer, List<Video>> local = new TreeMap<>();
        for (Video v : allEpisodes) {
            if (v.seasonNumber == null) {
                continue;
            }
            if (v.contentType != null && !"episode".equalsIgnoreCase(v.contentType)) {
                continue;
            }
            // OVA/OAD seasons carry a suffix and stay their own cards.
            if (v.seasonSuffix != null && !v.seasonSuffix.isBlank()) {
                continue;
            }
            normal.add(v);
            local.computeIfAbsent(v.seasonNumber, k -> new ArrayList<>()).add(v);
        }
        normal.sort(Comparator
                .comparingInt((Video v) -> v.seasonNumber == null ? 0 : v.seasonNumber)
                .thenComparingInt(v -> v.episodeNumber == null ? 0 : v.episodeNumber));
        local.values().forEach(list -> list.sort(
                Comparator.comparingInt(v -> v.episodeNumber == null ? 0 : v.episodeNumber)));

        Map<Integer, Integer> metadata = metadataSeasonCounts(seriesTitle);

        boolean fits = !metadata.isEmpty() && fitsFolderLayout(local, metadata);
        long localTotal = normal.stream().filter(v -> v.seasonNumber != null && v.seasonNumber > 0).count();
        long metadataTotal = metadata.values().stream().mapToLong(Integer::longValue).sum();

        if (!fits && !metadata.isEmpty() && localTotal == metadataTotal) {
            remapByMetadata(layout, normal, metadata, local.get(0));
            layout.regrouped = true;
            LOG.info("Season layout for '{}': re-split {} episodes by TMDb counts {}", seriesTitle,
                    localTotal, metadata);
        } else {
            folderLayout(layout, local, metadata);
            if (!metadata.isEmpty() && !fits) {
                LOG.debug("Season layout for '{}': folder layout kept (local {} episodes vs metadata {})",
                        seriesTitle, localTotal, metadataTotal);
            }
        }
        return layout;
    }

    private Map<Integer, Integer> metadataSeasonCounts(String seriesTitle) {
        Map<Integer, Integer> counts = new TreeMap<>();
        try {
            Series series = Series.find("lower(title) = lower(?1)", seriesTitle).firstResult();
            if (series == null || series.tmdbId == null) {
                return counts;
            }
            TmdbCatalogService.CatalogShow show = tmdbCatalogService.getShow(series.tmdbId);
            if (show == null || show.counts() == null) {
                return counts;
            }
            for (Map.Entry<Integer, Integer> e : show.counts().entrySet()) {
                if (e.getKey() != null && e.getKey() > 0 && e.getValue() != null && e.getValue() > 0) {
                    counts.put(e.getKey(), e.getValue());
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("Season layout: TMDb lookup failed for '{}': {}", seriesTitle, e.getMessage());
        }
        return counts;
    }

    /** True when every local (non-special) season sits inside the matching metadata season. */
    private boolean fitsFolderLayout(Map<Integer, List<Video>> local, Map<Integer, Integer> metadata) {
        for (Map.Entry<Integer, List<Video>> e : local.entrySet()) {
            int season = e.getKey();
            if (season <= 0) {
                continue;
            }
            Integer expected = metadata.get(season);
            if (expected == null || e.getValue().size() > expected) {
                return false;
            }
        }
        return local.keySet().stream().anyMatch(s -> s > 0);
    }

    private void folderLayout(Layout layout, Map<Integer, List<Video>> local, Map<Integer, Integer> metadata) {
        for (Map.Entry<Integer, List<Video>> e : local.entrySet()) {
            int season = e.getKey();
            layout.seasons.put(season, new ArrayList<>(e.getValue()));
            Integer expected = metadata.get(season);
            layout.expected.put(season, expected != null ? expected : e.getValue().size());
        }
        // Seasons TMDb knows but the library has no file for (aired or announced).
        for (Map.Entry<Integer, Integer> e : metadata.entrySet()) {
            if (!layout.seasons.containsKey(e.getKey())) {
                layout.seasons.put(e.getKey(), new ArrayList<>());
                layout.expected.put(e.getKey(), e.getValue());
            }
        }
        orderSpecialsLast(layout);
    }

    private void remapByMetadata(Layout layout, List<Video> ordered,
                                 Map<Integer, Integer> metadata, List<Video> specials) {
        int index = 0;
        for (Map.Entry<Integer, Integer> e : metadata.entrySet()) {
            int season = e.getKey();
            int count = e.getValue();
            List<Video> group = new ArrayList<>(count);
            for (int i = 0; i < count && index < ordered.size(); i++) {
                Video v = ordered.get(index++);
                group.add(v);
                layout.displayEpisodeNumber.put(v.id, i + 1);
            }
            layout.seasons.put(season, group);
            layout.expected.put(season, count);
        }
        if (specials != null && !specials.isEmpty()) {
            layout.seasons.put(0, new ArrayList<>(specials));
            layout.expected.put(0, specials.size());
        }
        orderSpecialsLast(layout);
    }

    private void orderSpecialsLast(Layout layout) {
        if (!layout.seasons.containsKey(0)) {
            return;
        }
        List<Video> specials = layout.seasons.remove(0);
        Integer expected = layout.expected.remove(0);
        layout.seasons.put(0, specials);
        layout.expected.put(0, expected != null ? expected : specials.size());
    }
}
