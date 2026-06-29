package Services;

import Models.Video;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import io.quarkus.scheduler.Scheduled;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * Background worker that continuously finds videos with missing external IDs
 * (imdbId, tmdbId, showImdbId) and intro/outro/recap timestamps, then enriches them.
 * <p>
 * Runs on a configurable schedule, processes a limited batch per tick to avoid
 * overwhelming external APIs, and retries failed enrichments after a cooldown period.
 * <p>
 * Follows the same pattern as {@link AnalysisWorker} — Phase 1 → Phase 2 → retry failures.
 */
@ApplicationScoped
public class MetadataEnrichmentWorker {

    private static final Logger LOG = LoggerFactory.getLogger(MetadataEnrichmentWorker.class);

    @Inject
    VideoMetadataService videoMetadataService;

    @Inject
    @ConfigProperty(name = "metadata.enrichment.batch.size", defaultValue = "3")
    int batchSize;

    @Inject
    @ConfigProperty(name = "metadata.enrichment.retry.cooldown.minutes", defaultValue = "30")
    int retryCooldownMinutes;

    @PersistenceContext
    EntityManager em;

    private final Semaphore guard = new Semaphore(1);

    // Tracks failed enrichment attempts: videoId -> last failure timestamp (epoch millis)
    private final Map<Long, Long> failedEnrichments = new ConcurrentHashMap<>();

    private long retryCooldownMs;

    @PostConstruct
    void init() {
        this.retryCooldownMs = retryCooldownMinutes * 60 * 1000L;
        LOG.info("MetadataEnrichmentWorker initialized: batchSize={}, retryCooldown={}min ({}ms)",
                batchSize, retryCooldownMinutes, retryCooldownMs);
    }

    @Scheduled(every = "{metadata.enrichment.interval}")
    @ActivateRequestContext
    void processEnrichment() {
        if (!guard.tryAcquire()) {
            LOG.debug("MetadataEnrichmentWorker: previous tick still running, skipping");
            return;
        }
        try {
            int total = 0;

            // Phase 1: Videos missing external IDs -> full metadata enrichment
            int phase1 = processMissingExternalIds();
            total += phase1;

            // Phase 2: Episodes with showImdbId but missing intro/outro/recap timestamps
            int phase2 = processMissingIntroOutro();
            total += phase2;

            // Phase 3: Retry previously failed enrichments after cooldown
            int phase3 = processFailedRetries();
            total += phase3;

            if (total > 0) {
                LOG.info("MetadataEnrichmentWorker tick complete: {} enriched ({} IDs, {} intro/outro, {} retries, {} pending failures)",
                        total, phase1, phase2, phase3, failedEnrichments.size());
            }
        } catch (Exception e) {
            LOG.error("MetadataEnrichmentWorker: unexpected error in processing tick", e);
        } finally {
            guard.release();
        }
    }

    /**
     * Phase 1: Find active videos missing external IDs (imdbId, tmdbId, or showImdbId for episodes)
     * and run full metadata enrichment on them. Skips videos with manually-edited titles.
     */
    private int processMissingExternalIds() {
        int count = 0;
        try {
            List<Video> candidates = Video.find(
                    "isActive = ?1 AND titleManuallyEdited = ?2 AND " +
                    "(imdbId IS NULL OR tmdbId IS NULL OR (type = 'episode' AND showImdbId IS NULL))",
                    true, false
            ).page(0, batchSize).list();

            for (Video video : candidates) {
                try {
                    LOG.info("Enriching missing IDs for '{}' (id={}, type={})", video.title, video.id, video.type);
                    videoMetadataService.fetchAndEnrichMetadata(video);
                    failedEnrichments.remove(video.id);
                    count++;
                } catch (Exception e) {
                    LOG.warn("Failed to enrich IDs for '{}' (id={}): {}", video.title, video.id, e.getMessage());
                    failedEnrichments.put(video.id, System.currentTimeMillis());
                }
            }
        } catch (Exception e) {
            LOG.error("Error querying for videos missing external IDs", e);
        }
        return count;
    }

    /**
     * Phase 2: Find episodes that have a showImdbId but are still missing
     * intro/outro/recap timestamps. Calls IntroDB enrichment specifically.
     */
    private int processMissingIntroOutro() {
        int count = 0;
        try {
            List<Video> candidates = Video.find(
                    "isActive = ?1 AND type = 'episode' AND showImdbId IS NOT NULL AND showImdbId != '' AND " +
                    "(introStart IS NULL OR outroStart IS NULL OR recapStart IS NULL)",
                    true
            ).page(0, batchSize).list();

            for (Video video : candidates) {
                try {
                    LOG.info("Fetching intro/outro for '{}' S{}E{} (id={})",
                            video.seriesTitle, video.seasonNumber, video.episodeNumber, video.id);
                    videoMetadataService.enrichVideoWithIntroData(video);
                    count++;
                } catch (Exception e) {
                    LOG.warn("Failed to fetch intro/outro for episode '{}' (id={}): {}",
                            video.title, video.id, e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.error("Error querying for episodes missing intro/outro", e);
        }
        return count;
    }

    /**
     * Phase 3: Retry videos that previously failed enrichment, but only after
     * the configured cooldown period has elapsed since their last failure.
     */
    private int processFailedRetries() {
        int count = 0;
        long now = System.currentTimeMillis();

        // Find failures whose cooldown has elapsed
        List<Long> retryIds = failedEnrichments.entrySet().stream()
                .filter(entry -> (now - entry.getValue()) >= retryCooldownMs)
                .map(Map.Entry::getKey)
                .limit(batchSize)
                .collect(Collectors.toList());

        for (Long videoId : retryIds) {
            try {
                Video video = Video.findById(videoId);
                if (video == null || !video.isActive) {
                    failedEnrichments.remove(videoId);
                    continue;
                }

                LOG.info("Retrying enrichment for '{}' (id={})", video.title, videoId);
                videoMetadataService.fetchAndEnrichMetadata(video);
                failedEnrichments.remove(videoId);
                count++;
            } catch (Exception e) {
                LOG.warn("Retry failed for video id={}: {}", videoId, e.getMessage());
                // Update timestamp so cooldown restarts from now
                failedEnrichments.put(videoId, now);
            }
        }
        return count;
    }

    // ---- Monitoring / Admin hooks ----

    public int getPendingFailureCount() {
        return failedEnrichments.size();
    }

    public boolean isRunning() {
        return guard.availablePermits() == 0;
    }
}
