package Services;

import Models.Series;
import Models.Video;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Migrates existing Video data into Series entities on first boot.
 * <p>
 * For each distinct seriesTitle found in Video rows, creates a Series entity
 * copying show-level fields from the first episode (sorted by seasonNumber ASC,
 * episodeNumber ASC), then links all episodes to the new Series.
 * <p>
 * Also sets contentType on all videos that don't have one yet.
 * <p>
 * Idempotent: safe to run multiple times without creating duplicates.
 * Uses batch processing (flush/clear every BATCH_SIZE entities) to avoid OOM.
 */
@ApplicationScoped
public class SeriesMigrationService {

    private static final Logger LOG = LoggerFactory.getLogger(SeriesMigrationService.class);
    private static final int BATCH_SIZE = 100;

    @PersistenceContext
    EntityManager em;

    // ========== STARTUP HOOK ==========

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        try {
            long unlinked = Video.count(
                    "seriesTitle IS NOT NULL AND seriesTitle <> '' AND series IS NULL");
            if (unlinked > 0) {
                LOG.info("Series migration needed: {} videos with seriesTitle but no series link", unlinked);
                migrateAll();
            } else {
                LOG.debug("No series migration needed.");
            }
        } catch (Exception e) {
            LOG.error("Series migration failed", e);
        }
    }

    // ========== PUBLIC API ==========

    /**
     * Full migration: sets contentType on all videos, then creates Series
     * entities and links episodes. Safe to call multiple times (idempotent).
     */
    @Transactional
    public void migrateAll() {
        long start = System.currentTimeMillis();
        int seriesCreated = 0;
        int episodesLinked = 0;

        setContentTypeForAllVideos();

        @SuppressWarnings("unchecked")
        List<String> seriesTitles = em.createQuery(
                "SELECT DISTINCT v.seriesTitle FROM Video v " +
                "WHERE v.seriesTitle IS NOT NULL AND v.seriesTitle <> ''")
                .getResultList();

        if (seriesTitles.isEmpty()) {
            LOG.info("No series titles found to migrate.");
            return;
        }

        LOG.info("Found {} unique series titles to migrate.", seriesTitles.size());

        for (String title : seriesTitles) {
            List<Video> episodes = Video.find(
                    "seriesTitle = ?1 AND series IS NULL", title).list();

            if (episodes.isEmpty()) continue;

            Series series = findOrCreateSeries(title, episodes);

            for (Video ep : episodes) {
                ep.series = series;
                if (ep.contentType == null) {
                    ep.contentType = determineContentType(ep);
                }
            }

            episodesLinked += episodes.size();

            if (++seriesCreated % BATCH_SIZE == 0) {
                em.flush();
                em.clear();
                LOG.info("Series migration progress: {} series, {} episodes linked",
                        seriesCreated, episodesLinked);
            }
        }

        em.flush();
        em.clear();

        long elapsed = System.currentTimeMillis() - start;
        LOG.info("Series migration complete: {} series created/updated, {} episodes linked in {}ms.",
                seriesCreated, episodesLinked, elapsed);
    }

    // ========== CONTENT TYPE ==========

    /**
     * Sets contentType on all videos that don't have one yet.
     * Processes in batches of {@link #BATCH_SIZE}, flushing and clearing
     * the persistence context after each batch to avoid OOM.
     */
    private void setContentTypeForAllVideos() {
        long total = Video.count("contentType IS NULL");
        if (total == 0) {
            LOG.info("All videos already have contentType set.");
            return;
        }

        LOG.info("Setting contentType for {} videos...", total);
        int processed = 0;

        // Always use page 0 — after flush/clear, processed records are removed
        // from the result set, so the next batch starts from the beginning.
        while (true) {
            List<Video> batch = Video.find("contentType IS NULL")
                    .page(0, BATCH_SIZE).list();

            if (batch.isEmpty()) break;

            for (Video v : batch) {
                v.contentType = determineContentType(v);
            }

            em.flush();
            em.clear();
            processed += batch.size();
            LOG.info("contentType progress: {}/{}", processed, total);
        }
    }

    /**
     * Determines contentType based on Video.type, folder patterns, and seasonNumber.
     * <ul>
     *   <li>type == "movie" → "movie"</li>
     *   <li>folder == "Featurette" or "Behind the Scenes" → "featurette"</li>
     *   <li>seasonNumber == 0 → "special"</li>
     *   <li>otherwise → "episode"</li>
     * </ul>
     */
    private String determineContentType(Video video) {
        if (video.type == null) return "episode";

        if ("movie".equalsIgnoreCase(video.type)) return "movie";

        if (video.folder != null) {
            String folder = video.folder.trim();
            if ("Featurette".equalsIgnoreCase(folder)
                    || "Behind the Scenes".equalsIgnoreCase(folder)) {
                return "featurette";
            }
        }

        if (video.seasonNumber != null && video.seasonNumber == 0) return "special";

        return "episode";
    }

    // ========== SERIES CREATION ==========

    /**
     * Finds an existing Series by title, or creates a new one from the first episode.
     * "First episode" is determined by seasonNumber ASC, episodeNumber ASC.
     */
    private Series findOrCreateSeries(String title, List<Video> episodes) {
        Series existing = Series.find("title", title).firstResult();
        if (existing != null) {
            return existing;
        }

        Video firstEpisode = findFirstEpisode(episodes);
        return createSeriesFromEpisode(firstEpisode, title);
    }

    private Video findFirstEpisode(List<Video> episodes) {
        return episodes.stream()
                .sorted((a, b) -> {
                    int seasonCmp = compareNullsLast(a.seasonNumber, b.seasonNumber);
                    return seasonCmp != 0
                            ? seasonCmp
                            : compareNullsLast(a.episodeNumber, b.episodeNumber);
                })
                .findFirst()
                .orElse(episodes.get(0));
    }

    private int compareNullsLast(Integer a, Integer b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return Integer.compare(a, b);
    }

    /**
     * Creates a Series entity copying show-level fields from a source episode.
     * Does NOT copy thumbnailPath (that stays per-episode).
     */
    private Series createSeriesFromEpisode(Video source, String title) {
        Series series = new Series();
        series.title = title;

        // Descriptions
        series.description = source.description;
        series.tagline = source.tagline;
        series.overview = source.overview;

        // People — now both are List<String>, copy directly
        series.genres = source.genres != null ? new ArrayList<>(source.genres) : null;
        series.directors = source.directors != null ? new ArrayList<>(source.directors) : null;
        series.writers = source.writers != null ? new ArrayList<>(source.writers) : null;
        series.cast = source.cast != null ? new ArrayList<>(source.cast) : null;
        series.productionCompanies = source.productionCompanies != null ? new ArrayList<>(source.productionCompanies) : null;
        series.networks = source.networks != null ? new ArrayList<>(source.networks) : null;

        // Ratings
        series.imdbRating = source.imdbRating;
        series.tmdbRating = source.tmdbRating;
        series.metacriticRating = source.metacriticRating != null
                ? source.metacriticRating.doubleValue() : null;
        series.voteCount = source.voteCount;
        series.popularityScore = source.popularityScore;

        // Metadata
        series.releaseYear = source.releaseYear;
        series.runtimeMins = source.runtimeMins;
        series.mpaaRating = source.mpaaRating;
        series.status = source.status;
        series.originalLanguage = source.originalLanguage;
        series.productionCountries = source.productionCountries;
        series.releaseDate = source.releaseDate;
        series.trailerUrl = source.trailerUrl;
        series.parentsGuide = source.parentsGuide;

        // External IDs (Video stores as String, Series uses Integer)
        series.imdbId = source.imdbId;
        series.tmdbId = parseInteger(source.tmdbId);
        series.tvdbId = parseInteger(source.tvdbId);

        // Financial
        series.budget = source.budget;
        series.revenue = source.revenue;

        // Collections
        series.collectionName = source.collectionName;
        series.franchiseName = source.franchiseName;

        // Images — show-level paths only, NOT thumbnailPath (per-episode)
        series.logoPath = source.logoPath;
        series.posterPath = source.posterPath;
        series.backdropPath = source.backdropPath;
        series.heroPath = source.heroPath;
        series.fanartPath = source.fanartPath;
        series.stillPath = source.stillPath;

        // ElementCollections (List → Set)
        if (source.akas != null && !source.akas.isEmpty()) {
            series.akas = new HashSet<>(source.akas);
        }
        if (source.keywords != null && !source.keywords.isEmpty()) {
            series.keywords = new HashSet<>(source.keywords);
        }

        series.persist();
        return series;
    }

    // ========== HELPERS ==========

    private Integer parseInteger(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
