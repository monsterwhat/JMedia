package Services;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.inject.Inject;
import io.quarkus.runtime.Startup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import Models.Video.Video;
import Models.Video.Series;
import Models.Settings.Settings;
import Utils.PoolSizeResolver;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
@Startup
public class VideoEnrichmentWorker {
    private static final Logger LOG = LoggerFactory.getLogger(VideoEnrichmentWorker.class);
    private static final int POLL_TIMEOUT_SECONDS = 5;
    private static final int MAX_RETRIES = 2;

    @Inject
    VideoMetadataService videoMetadataService;

    @Inject
    SettingsService settingsService;

    @Inject
    ThumbnailService thumbnailService;

    @Inject
    RequestContextController requestContextController;

    private final LinkedBlockingQueue<Long> queue = new LinkedBlockingQueue<>();
    private ExecutorService workerPool;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    // Effective pool size as of the last start(); 0 means enrichment is off
    private volatile int workerPoolSize = 0;

    private final AtomicInteger pending = new AtomicInteger(0);
    private final AtomicInteger processed = new AtomicInteger(0);
    private final AtomicInteger failed = new AtomicInteger(0);
    private final AtomicInteger enriched = new AtomicInteger(0);
    private final AtomicInteger notFound = new AtomicInteger(0);

    // Genre-only (category) sweep queue — reuses the same virtual-thread pool as the main
    // enrichment queue. needsEnrichment() skips ENRICHED-status videos, so genre-missing
    // ENRICHED videos need this dedicated queue (Xtream categories derive from genres).
    private final LinkedBlockingQueue<Long> categoryQueue = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<Long> categorySeriesQueue = new LinkedBlockingQueue<>();
    private static final int CATEGORY_PAGE_SIZE = 500;
    private final AtomicInteger categoryPending = new AtomicInteger(0);
    private final AtomicInteger categoryProcessed = new AtomicInteger(0);

    // Release-date backfill queue — reuses the same virtual-thread pool as the main
    // enrichment queue. needsEnrichment() skips ENRICHED-status videos, so enriched movies
    // with a tmdbId but empty releaseDate need this dedicated queue (Xtream get_vod_info
    // exposes releasedate, which was left empty for movies before the TMDB wiring).
    private final LinkedBlockingQueue<Long> releaseDateQueue = new LinkedBlockingQueue<>();
    private final AtomicInteger releaseDatePending = new AtomicInteger(0);
    private final AtomicInteger releaseDateProcessed = new AtomicInteger(0);

    @PostConstruct
    void init() {
        start();
        Thread startupThread = new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            queueAllUnenriched();
            // Auto-run the genre/category backfill on startup so Xtream categories fill
            // in without a manual scan trigger (covers ENRICHED videos with empty genres).
            queueAllMissingGenres();
            // Auto-run the release-date backfill on startup so enriched movies missing a
            // release date get one without a manual re-enrich (Xtream get_vod_info exposes it).
            queueAllMissingReleaseDates();
            // Fetch TMDB posters for series that have neither a remote cover URL nor a
            // local poster file (their Xtream covers would otherwise fall back to
            // episode art). Runs inline on this background thread; per-series guarded.
            backfillSeriesPosters();
        }, "VideoEnrichmentWorker-startup");
        startupThread.setDaemon(true);
        startupThread.start();
    }

    @PreDestroy
    void destroy() {
        stop();
    }

    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            workerPoolSize = resolveWorkerThreads();
            if (workerPoolSize > 0) {
                workerPool = Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("VideoEnrichment-worker-", 0).factory());
                for (int i = 0; i < workerPoolSize; i++) {
                    workerPool.submit(this::processQueue);
                }
            } else {
                workerPool = null;
                LOG.info("VideoEnrichmentWorker: enrichment workers disabled in system settings");
            }
            LOG.info("VideoEnrichmentWorker started with {} virtual threads", workerPoolSize);
        }
    }

    public void reconfigure() {
        stop();
        start();
    }

    private int resolveWorkerThreads() {
        try {
            Settings s = settingsService.getSettingsOrNull();
            Integer configured = s != null ? s.getVideoEnrichmentThreads() : null;
            return PoolSizeResolver.resolve(configured, PoolSizeResolver.autoIoThreads(2, 8));
        } catch (Exception e) {
            LOG.warn("Failed to read videoEnrichmentThreads setting, using default", e);
            return PoolSizeResolver.autoIoThreads(2, 8);
        }
    }

    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            if (workerPool != null) {
                workerPool.shutdown();
                try {
                    if (!workerPool.awaitTermination(15, TimeUnit.SECONDS)) {
                        workerPool.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    workerPool.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            LOG.info("VideoEnrichmentWorker stopped");
        }
    }

    private void processQueue() {
        LOG.info("VideoEnrichmentWorker queue worker started");
        while (isRunning.get()) {
            try {
                Long videoId = queue.poll(POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (videoId != null) {
                    pending.decrementAndGet();
                    processVideo(videoId);
                    continue;
                }
                Long categoryVideoId = categoryQueue.poll(1, TimeUnit.SECONDS);
                if (categoryVideoId != null) {
                    categoryPending.decrementAndGet();
                    processCategoryVideo(categoryVideoId);
                    continue;
                }
                Long categorySeriesId = categorySeriesQueue.poll(1, TimeUnit.SECONDS);
                if (categorySeriesId != null) {
                    categoryPending.decrementAndGet();
                    processCategorySeries(categorySeriesId);
                    continue;
                }
                Long releaseDateVideoId = releaseDateQueue.poll(1, TimeUnit.SECONDS);
                if (releaseDateVideoId != null) {
                    releaseDatePending.decrementAndGet();
                    processReleaseDateVideo(releaseDateVideoId);
                }
            } catch (InterruptedException e) {
                LOG.info("VideoEnrichmentWorker worker interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.error("VideoEnrichmentWorker: unexpected error in worker", e);
            }
        }
        LOG.info("VideoEnrichmentWorker queue worker stopped");
    }

    private void processVideo(Long videoId) {
        requestContextController.activate();
        try {
            Video video = Video.findById(videoId);
            if (video == null || !video.isActive) {
                LOG.debug("VideoEnrichmentWorker: video {} not found or inactive, skipping", videoId);
                return;
            }

            if (!videoMetadataService.needsEnrichment(video)) {
                LOG.debug("VideoEnrichmentWorker: video {} ({}) doesn't need enrichment, skipping", videoId, video.title);
                return;
            }

            LOG.info("VideoEnrichmentWorker: enriching '{}' (id={}, type={})", video.title, videoId, video.type);
            videoMetadataService.fetchAndEnrichMetadata(video);

            if (video.tmdbId != null && !video.tmdbId.isBlank()) {
                enriched.incrementAndGet();
            } else {
                notFound.incrementAndGet();
            }
            processed.incrementAndGet();
            LOG.info("VideoEnrichmentWorker: done for '{}' (total: {} enriched, {} not-found, {} failed)",
                video.title, enriched.get(), notFound.get(), failed.get());

        } catch (Exception e) {
            failed.incrementAndGet();
            LOG.error("VideoEnrichmentWorker: failed for video id={}: {}", videoId, e.getMessage(), e);
        } finally {
            requestContextController.deactivate();
        }
    }

    private void processCategoryVideo(Long videoId) {
        requestContextController.activate();
        try {
            Video video = Video.findById(videoId);
            if (video == null || !video.isActive) {
                LOG.debug("VideoEnrichmentWorker: category video {} not found or inactive, skipping", videoId);
                return;
            }
            if (!needsCategoryEnrichment(video)) {
                LOG.debug("VideoEnrichmentWorker: video {} ({}) already has genres, skipping", videoId, video.title);
                return;
            }
            LOG.info("VideoEnrichmentWorker: enriching genres for '{}' (id={}, type={})", video.title, videoId, video.type);
            // enrichGenresOnly fills ONLY the genre lists (video + parent series for episodes)
            // from TMDB/IMDb Dev without overwriting existing data — lighter than full enrichment.
            videoMetadataService.enrichGenresOnly(videoId);
            categoryProcessed.incrementAndGet();
            LOG.info("VideoEnrichmentWorker: genre enrichment done for '{}' (category processed: {})", video.title, categoryProcessed.get());
        } catch (Exception e) {
            LOG.error("VideoEnrichmentWorker: failed genre enrichment for video id={}: {}", videoId, e.getMessage(), e);
        } finally {
            requestContextController.deactivate();
        }
    }

    private boolean needsCategoryEnrichment(Video video) {
        return videoMetadataService.needsCategoryEnrichment(video);
    }

    private void processCategorySeries(Long seriesId) {
        requestContextController.activate();
        try {
            Series series = Series.findById(seriesId);
            if (series == null) {
                LOG.debug("VideoEnrichmentWorker: category series {} not found, skipping", seriesId);
                return;
            }
            if (series.genres != null && !series.genres.isEmpty()) {
                int rows = videoMetadataService.propagateSeriesGenresToEpisodes(seriesId);
                categoryProcessed.incrementAndGet();
                LOG.info("VideoEnrichmentWorker: propagated {} genre rows for series '{}' (category processed: {})",
                        rows, series.title, categoryProcessed.get());
                return;
            }
            List<Video> episodes = Video.list("series.id = ?1 AND type = 'episode'", seriesId);
            Video firstMissing = null;
            for (Video ep : episodes) {
                if (ep != null && ep.id != null && needsCategoryEnrichment(ep)) {
                    firstMissing = ep;
                    break;
                }
            }
            if (firstMissing == null) {
                categoryProcessed.incrementAndGet();
                LOG.debug("VideoEnrichmentWorker: series '{}' needs no genre work, skipping", series.title);
                return;
            }
            videoMetadataService.enrichGenresOnly(firstMissing.id);
            Series reloaded = Series.findById(seriesId);
            if (reloaded != null && reloaded.genres != null && !reloaded.genres.isEmpty()) {
                int rows = videoMetadataService.propagateSeriesGenresToEpisodes(seriesId);
                LOG.info("VideoEnrichmentWorker: fetched genres for series '{}', propagated {} rows (category processed: {})",
                        series.title, rows, categoryProcessed.get() + 1);
            } else {
                LOG.warn("VideoEnrichmentWorker: no genres found for series '{}', skipping its episodes", series.title);
            }
            categoryProcessed.incrementAndGet();
        } catch (Exception e) {
            LOG.error("VideoEnrichmentWorker: failed genre enrichment for series id={}: {}", seriesId, e.getMessage(), e);
        } finally {
            requestContextController.deactivate();
        }
    }

    private void processReleaseDateVideo(Long videoId) {
        requestContextController.activate();
        try {
            Video video = Video.findById(videoId);
            if (video == null || !video.isActive) {
                LOG.debug("VideoEnrichmentWorker: release-date video {} not found or inactive, skipping", videoId);
                return;
            }
            if (!needsReleaseDateEnrichment(video)) {
                LOG.debug("VideoEnrichmentWorker: video {} ({}) already has a release date, skipping", videoId, video.title);
                return;
            }
            LOG.info("VideoEnrichmentWorker: enriching release date for '{}' (id={}, type={})", video.title, videoId, video.type);
            videoMetadataService.enrichReleaseDateOnly(videoId);
            releaseDateProcessed.incrementAndGet();
            LOG.info("VideoEnrichmentWorker: release-date enrichment done for '{}' (release-date processed: {})", video.title, releaseDateProcessed.get());
        } catch (Exception e) {
            LOG.error("VideoEnrichmentWorker: failed release-date enrichment for video id={}: {}", videoId, e.getMessage(), e);
        } finally {
            requestContextController.deactivate();
        }
    }

    private boolean needsReleaseDateEnrichment(Video video) {
        return videoMetadataService.needsReleaseDateEnrichment(video);
    }

    public void queueVideo(Long videoId) {
        if (videoId == null || workerPoolSize == 0) return;
        queue.offer(videoId);
        pending.incrementAndGet();
    }

    @jakarta.enterprise.context.control.ActivateRequestContext
    public void queueAllUnenriched() {
        if (workerPoolSize == 0) {
            LOG.debug("VideoEnrichmentWorker: enrichment disabled in system settings, skipping queue sweep");
            return;
        }
        LOG.info("VideoEnrichmentWorker: scanning library for unenriched videos...");
        try {
            List<Video> allVideos = Video.listAll();
            int queued = 0;
            for (Video video : allVideos) {
                if (video != null && video.id != null && videoMetadataService.needsEnrichment(video)) {
                    queue.offer(video.id);
                    queued++;
                    pending.incrementAndGet();
                }
            }
            LOG.info("VideoEnrichmentWorker: queued {}/{} videos for enrichment", queued, allVideos.size());
        } catch (Exception e) {
            LOG.error("VideoEnrichmentWorker: failed to scan library for unenriched videos", e);
        }
    }

    /**
     * Queue all active movies/episodes that are missing genre assignments into the
     * dedicated category queue. This is the category backfill that makes Xtream
     * get_vod_categories / get_series_categories return full category lists without
     * requiring a full library scan.
     *
     * @return the number of videos queued for genre backfill
     */
    @jakarta.enterprise.context.control.ActivateRequestContext
    public int queueAllMissingGenres() {
        if (workerPoolSize == 0) {
            LOG.info("VideoEnrichmentWorker: enrichment disabled in system settings, skipping genre backfill");
            return 0;
        }
        LOG.info("VideoEnrichmentWorker: scanning library for videos missing genres...");
        int queued = 0;
        int seriesQueued = 0;
        try {
            List<Video> movies = Video.list("isActive = ?1 AND type = 'movie'", true);
            for (Video video : movies) {
                if (video != null && video.id != null && needsCategoryEnrichment(video)) {
                    categoryQueue.offer(video.id);
                    queued++;
                    categoryPending.incrementAndGet();
                }
            }
            List<Video> episodes = Video.list("isActive = ?1 AND type = 'episode'", true);
            Set<Long> seenSeries = new HashSet<>();
            for (Video video : episodes) {
                if (video == null || video.id == null || !needsCategoryEnrichment(video)) continue;
                Long sid = video.series != null ? video.series.id : null;
                if (sid == null) {
                    categoryQueue.offer(video.id);
                    queued++;
                    categoryPending.incrementAndGet();
                } else if (seenSeries.add(sid)) {
                    categorySeriesQueue.offer(sid);
                    seriesQueued++;
                    categoryPending.incrementAndGet();
                }
            }
            LOG.info("VideoEnrichmentWorker: queued {} movies and {} series for genre backfill ({} episodes scanned)",
                    queued, seriesQueued, episodes.size());
        } catch (Exception e) {
            LOG.error("VideoEnrichmentWorker: failed to scan library for videos missing genres", e);
        }
        return queued + seriesQueued;
    }

    /**
     * Fetch TMDB posters for series missing both a remote cover URL and a local
     * poster file (their Xtream covers would otherwise fall back to episode art).
     * Runs inline on the startup background thread; each series is individually
     * guarded so one failure never aborts the sweep.
     */
    @jakarta.enterprise.context.control.ActivateRequestContext
    public void backfillSeriesPosters() {
        LOG.info("VideoEnrichmentWorker: scanning library for series missing posters...");
        int fixed = 0;
        int scanned = 0;
        try {
            List<Models.Video.Series> all = Models.Video.Series.listAll();
            for (Models.Video.Series s : all) {
                try {
                    if (s == null || s.id == null) continue;
                    scanned++;
                    if (s.posterPath != null && s.posterPath.startsWith("http")) continue;
                    if (thumbnailService.findSeriesImageFile(s.id, "poster") != null) continue;
                    if (thumbnailService.ensureSeriesMediaImages(s.id)) fixed++;
                } catch (Exception e) {
                    LOG.warn("VideoEnrichmentWorker: series poster backfill skipped for series id={}: {}",
                            s != null ? s.id : null, e.getMessage());
                }
            }
            LOG.info("VideoEnrichmentWorker: series poster backfill scanned {} series, fetched images for {}", scanned, fixed);
        } catch (Exception e) {
            LOG.warn("VideoEnrichmentWorker: series poster backfill failed: {}", e.getMessage());
        }
    }

    /**
     * Queue all active enriched movies that have a tmdbId but no release date into the
     * dedicated release-date queue. This is the release-date backfill that makes Xtream
     * get_vod_info return a non-empty releasedate for already-enriched movies without
     * requiring a full re-enrichment.
     *
     * @return the number of movies queued for release-date backfill
     */
    @jakarta.enterprise.context.control.ActivateRequestContext
    public int queueAllMissingReleaseDates() {
        if (workerPoolSize == 0) {
            LOG.info("VideoEnrichmentWorker: enrichment disabled in system settings, skipping release-date backfill");
            return 0;
        }
        LOG.info("VideoEnrichmentWorker: scanning library for enriched movies missing release dates...");
        int queued = 0;
        try {
            List<Video> movies = Video.list(
                    "isActive = ?1 AND type = 'movie' AND tmdbId IS NOT NULL AND tmdbId != '' AND (releaseDate IS NULL OR releaseDate = '')",
                    true);
            for (Video video : movies) {
                if (video != null && video.id != null && needsReleaseDateEnrichment(video)) {
                    releaseDateQueue.offer(video.id);
                    queued++;
                    releaseDatePending.incrementAndGet();
                }
            }
            LOG.info("VideoEnrichmentWorker: queued {} movies for release-date backfill", queued);
        } catch (Exception e) {
            LOG.error("VideoEnrichmentWorker: failed to scan library for movies missing release dates", e);
        }
        return queued;
    }

    /**
     * Queue episodes of a single series that are missing genre assignments, for
     * targeted category propagation without a full-library sweep.
     *
     * @param seriesTitle the series whose episodes should be backfilled
     * @return the number of episodes queued for genre backfill
     */
    @jakarta.enterprise.context.control.ActivateRequestContext
    public int queueSeriesGenres(String seriesTitle) {
        if (workerPoolSize == 0) {
            LOG.info("VideoEnrichmentWorker: enrichment disabled in system settings, skipping series genre backfill");
            return 0;
        }
        if (seriesTitle == null || seriesTitle.isBlank()) {
            LOG.warn("VideoEnrichmentWorker: queueSeriesGenres called with blank seriesTitle");
            return 0;
        }
        Series series = Series.find("title = ?1", seriesTitle).firstResult();
        if (series != null && series.id != null) {
            categorySeriesQueue.offer(series.id);
            categoryPending.incrementAndGet();
            LOG.info("VideoEnrichmentWorker: queued series '{}' for genre backfill", seriesTitle);
            return 1;
        }
        LOG.info("VideoEnrichmentWorker: scanning series '{}' for episodes missing genres...", seriesTitle);
        int queued = 0;
        try {
            List<Video> episodes = Video.list("isActive = ?1 AND type = 'episode' AND seriesTitle = ?2", true, seriesTitle);
            for (Video video : episodes) {
                if (video != null && video.id != null && needsCategoryEnrichment(video)) {
                    categoryQueue.offer(video.id);
                    queued++;
                    categoryPending.incrementAndGet();
                }
            }
            LOG.info("VideoEnrichmentWorker: queued {}/{} episodes for genre backfill in series '{}'",
                    queued, episodes.size(), seriesTitle);
        } catch (Exception e) {
            LOG.error("VideoEnrichmentWorker: failed to scan series '{}' for episodes missing genres", seriesTitle, e);
        }
        return queued;
    }

    public EnrichmentProgress getProgress() {
        return new EnrichmentProgress(
            pending.get(), processed.get(), failed.get(),
            enriched.get(), notFound.get(), queue.size(), isRunning.get()
        );
    }

    public boolean isRunning() { return isRunning.get(); }
    public int getQueueSize() { return queue.size(); }

    /** True when enrichment workers are enabled in system settings (pool size > 0). */
    public boolean isEnabled() { return workerPoolSize > 0; }

    public CategoryProgress getCategoryProgress() {
        return new CategoryProgress(
            categoryPending.get(), categoryProcessed.get(),
            categoryQueue.size() + categorySeriesQueue.size(), isRunning.get()
        );
    }

    public record EnrichmentProgress(
        int pending, int processed, int failed,
        int enriched, int notFound, int queueSize, boolean running
    ) {}

    public record CategoryProgress(
        int pending, int processed, int queueSize, boolean running
    ) {}
}