package Services;

import Models.Video.Series;
import Models.Video.Video;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot backfill of blank {@code imdbId} values on Series and movies, resolved
 * through the TMDb ids those rows already carry.
 *
 * <p>Deliberately NOT annotated {@code @Transactional}: a full pass over a large
 * library needs far more than the 5 minute Narayana default transaction timeout.
 * Each row is read and written in its own short {@link QuarkusTransaction#requiringNew()}
 * transaction, and the TMDb HTTP call happens between the two so no database
 * transaction (or row lock) is ever held across network I/O.
 *
 * <p>Only rows that already have a tmdbId but no imdbId are candidates. Episodes are
 * never touched: their IMDb ids come from the parent show's external ids, not from
 * a per-episode TMDb lookup.
 */
@ApplicationScoped
public class ImdbIdBackfillService {

    private static final Logger LOG = LoggerFactory.getLogger(ImdbIdBackfillService.class);

    /** Pause between TMDb requests so a full pass stays well under the provider rate limit. */
    private static final long NETWORK_PAUSE_MS = 25L;

    private static final String SERIES_CANDIDATES =
            "select s.id from Series s where s.tmdbId is not null and (s.imdbId is null or s.imdbId = '')";
    private static final String MOVIE_CANDIDATES =
            "select v.id from Video v where v.type = 'movie' and v.tmdbId is not null and v.tmdbId <> '' "
            + "and (v.imdbId is null or v.imdbId = '')";

    @Inject
    VideoMetadataService videoMetadataService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public record BackfillResult(int seriesScanned, int seriesUpdated, int seriesNoImdb,
                                 int moviesScanned, int moviesUpdated, int moviesNoImdb,
                                 int failed) {
    }

    /** Claim the pass; false when another pass is already in flight. */
    public boolean tryStart() {
        return running.compareAndSet(false, true);
    }

    /** Release the pass; must be called once by whoever won {@link #tryStart()}. */
    public void finish() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Resolves IMDb ids for every Series and movie that has a TMDb id but no IMDb id.
     * Never throws: row-level failures are counted and logged, and the pass continues.
     */
    public BackfillResult backfillAll() {
        final List<Long> seriesIds;
        final List<Long> movieIds;
        try {
            seriesIds = QuarkusTransaction.requiringNew().call(() ->
                    Series.find(SERIES_CANDIDATES).project(Long.class).list());
        } catch (Exception e) {
            LOG.error("IMDb backfill: could not list Series candidates, skipping Series pass", e);
            return new BackfillResult(0, 0, 0, 0, 0, 0, 1);
        }
        try {
            movieIds = QuarkusTransaction.requiringNew().call(() ->
                    Video.find(MOVIE_CANDIDATES).project(Long.class).list());
        } catch (Exception e) {
            LOG.error("IMDb backfill: could not list movie candidates, skipping movie pass", e);
            return new BackfillResult(0, 0, 0, 0, 0, 0, 1);
        }

        // Per-run caches: duplicate TMDb ids cost exactly one request per media type.
        // TV and movie id spaces are separate on TMDb, so they get separate maps to
        // avoid a numeric collision resolving a show id against the movie endpoint.
        final Map<Integer, Optional<String>> seriesImdbByTmdbId = new HashMap<>();
        final Map<Integer, Optional<String>> movieImdbByTmdbId = new HashMap<>();

        int seriesScanned = 0;
        int seriesUpdated = 0;
        int seriesNoImdb = 0;
        int moviesScanned = 0;
        int moviesUpdated = 0;
        int moviesNoImdb = 0;
        int failed = 0;
        boolean interrupted = false;

        LOG.info("IMDb backfill started: {} Series candidate(s), {} movie candidate(s)",
                seriesIds.size(), movieIds.size());

        for (final Long id : seriesIds) {
            seriesScanned++;
            try {
                final Integer tmdbId = readSeriesTmdbIdIfImdbBlank(id);
                if (tmdbId == null || tmdbId <= 0) {
                    seriesNoImdb++;
                    continue;
                }
                Optional<String> resolved = seriesImdbByTmdbId.get(tmdbId);
                if (resolved == null) {
                    if (!pauseBetweenRequests()) {
                        interrupted = true;
                        break;
                    }
                    resolved = videoMetadataService.fetchImdbIdFromTmdb(tmdbId, true);
                    seriesImdbByTmdbId.put(tmdbId, resolved);
                }
                if (resolved.isEmpty()) {
                    seriesNoImdb++;
                    continue;
                }
                final String imdbId = resolved.get();
                if (applySeriesImdbId(id, imdbId)) {
                    seriesUpdated++;
                    LOG.debug("IMDb backfill: Series id={} tmdbId={} -> imdbId={}", id, tmdbId, imdbId);
                } else {
                    LOG.debug("IMDb backfill: Series id={} already had an imdbId, left untouched", id);
                }
            } catch (Exception e) {
                failed++;
                LOG.error("IMDb backfill failed for Series id={}", id, e);
            }
        }

        if (!interrupted) {
            for (final Long id : movieIds) {
                moviesScanned++;
                try {
                    final String rawTmdbId = readMovieTmdbIdIfImdbBlank(id);
                    if (rawTmdbId == null || rawTmdbId.isBlank()) {
                        moviesNoImdb++;
                        continue;
                    }
                    final int tmdbId;
                    try {
                        tmdbId = Integer.parseInt(rawTmdbId.trim());
                    } catch (NumberFormatException e) {
                        LOG.debug("IMDb backfill: skipping movie id={} with non-numeric tmdbId '{}'", id, rawTmdbId);
                        moviesNoImdb++;
                        continue;
                    }
                    if (tmdbId <= 0) {
                        LOG.debug("IMDb backfill: skipping movie id={} with invalid tmdbId {}", id, tmdbId);
                        moviesNoImdb++;
                        continue;
                    }
                    Optional<String> resolved = movieImdbByTmdbId.get(tmdbId);
                    if (resolved == null) {
                        if (!pauseBetweenRequests()) {
                            interrupted = true;
                            break;
                        }
                        resolved = videoMetadataService.fetchImdbIdFromTmdb(tmdbId, false);
                        movieImdbByTmdbId.put(tmdbId, resolved);
                    }
                    if (resolved.isEmpty()) {
                        moviesNoImdb++;
                        continue;
                    }
                    final String imdbId = resolved.get();
                    if (applyMovieImdbId(id, imdbId)) {
                        moviesUpdated++;
                        LOG.debug("IMDb backfill: movie id={} tmdbId={} -> imdbId={}", id, tmdbId, imdbId);
                    } else {
                        LOG.debug("IMDb backfill: movie id={} already had an imdbId, left untouched", id);
                    }
                } catch (Exception e) {
                    failed++;
                    LOG.error("IMDb backfill failed for movie id={}", id, e);
                }
            }
        }

        if (interrupted) {
            LOG.warn("IMDb backfill interrupted, pass stopped early");
        }
        LOG.info("IMDb backfill finished: series scanned={}, updated={}, no-imdb={}; "
                        + "movies scanned={}, updated={}, no-imdb={}; failed={}",
                seriesScanned, seriesUpdated, seriesNoImdb,
                moviesScanned, moviesUpdated, moviesNoImdb, failed);
        return new BackfillResult(seriesScanned, seriesUpdated, seriesNoImdb,
                moviesScanned, moviesUpdated, moviesNoImdb, failed);
    }

    /** Reads the tmdbId of a Series row, or null when the row is gone or already has an imdbId. */
    private Integer readSeriesTmdbIdIfImdbBlank(Long id) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Series series = Series.findById(id);
            if (series == null || hasImdbId(series.imdbId)) {
                return null;
            }
            return series.tmdbId;
        });
    }

    /** Reads the tmdbId of a movie row, or null when the row is gone or already has an imdbId. */
    private String readMovieTmdbIdIfImdbBlank(Long id) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Video video = Video.findById(id);
            if (video == null || hasImdbId(video.imdbId)) {
                return null;
            }
            return video.tmdbId;
        });
    }

    /**
     * Writes the resolved IMDb id in its own short transaction. Re-checks the blank
     * condition on the freshly loaded row so a concurrent enrichment never gets
     * overwritten. Returns false when nothing was written.
     */
    private boolean applySeriesImdbId(Long id, String imdbId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Series series = Series.findById(id);
            if (series == null || hasImdbId(series.imdbId)) {
                return false;
            }
            series.imdbId = imdbId;
            return true;
        });
    }

    /** Movie counterpart of {@link #applySeriesImdbId(Long, String)}. */
    private boolean applyMovieImdbId(Long id, String imdbId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Video video = Video.findById(id);
            if (video == null || hasImdbId(video.imdbId)) {
                return false;
            }
            video.imdbId = imdbId;
            return true;
        });
    }

    private static boolean hasImdbId(String imdbId) {
        return imdbId != null && !imdbId.isBlank();
    }

    /** @return false when the thread was interrupted and the pass should stop. */
    private static boolean pauseBetweenRequests() {
        try {
            Thread.sleep(NETWORK_PAUSE_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("IMDb backfill interrupted while pausing between TMDb requests");
            return false;
        }
    }
}
