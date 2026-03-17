package Services;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import Models.Video;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background processor for metadata enrichment queue.
 * Processes TMDb, OMDb, and IMDb API calls with rate limiting.
 */
@ApplicationScoped
public class MetadataQueueProcessor {
    
    private static final Logger LOG = LoggerFactory.getLogger(MetadataQueueProcessor.class);
    
    private static final int TMDB_DELAY_MS = 500;
    private static final int OMDB_DELAY_MS = 250;
    private static final int MAX_RETRIES = 2;
    
    @Inject
    VideoMetadataService videoMetadataService;
    
    private ExecutorService executorService;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    
    @PostConstruct
    void init() {
        start();
    }
    
    @PreDestroy
    void destroy() {
        stop();
    }
    
    /**
     * Start the metadata processing thread
     */
    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            executorService = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "MetadataQueueProcessor");
                t.setDaemon(true);
                return t;
            });
            executorService.submit(this::processQueue);
            LOG.info("MetadataQueueProcessor started");
        }
    }
    
    /**
     * Stop the metadata processing thread
     */
    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            if (executorService != null) {
                executorService.shutdown();
                try {
                    if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                        executorService.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executorService.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            LOG.info("MetadataQueueProcessor stopped");
        }
    }
    
    /**
     * Process the metadata queue
     */
    private void processQueue() {
        LOG.info("Starting metadata enrichment queue processing");
        
        while (isRunning.get()) {
            try {
                Long videoId = videoMetadataService.getMetadataQueue().poll(5, TimeUnit.SECONDS);
                
                if (videoId == null) {
                    continue;
                }
                
                processVideoMetadata(videoId);
                
            } catch (InterruptedException e) {
                LOG.info("MetadataQueueProcessor interrupted, shutting down");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.error("Error processing metadata queue: {}", e.getMessage(), e);
            }
        }
        
        LOG.info("Metadata enrichment queue processing stopped");
    }
    
    /**
     * Process metadata for a single video with retry logic
     */
    private void processVideoMetadata(Long videoId) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                Video video = Video.findById(videoId);
                if (video == null) {
                    LOG.debug("Video {} not found, skipping", videoId);
                    return;
                }
                
                // Skip if already enriched (has TMDb ID)
                if (video.tmdbId != null && !video.tmdbId.isBlank()) {
                    LOG.debug("Video {} already has TMDb ID, skipping enrichment", videoId);
                    return;
                }
                
                videoMetadataService.fetchAndEnrichMetadata(video);
                
                // Rate limiting after each video
                Thread.sleep(TMDB_DELAY_MS);
                
                LOG.debug("Successfully enriched metadata for video: {}", video.title);
                return;
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOG.warn("Attempt {}/{} failed for video {}: {}", 
                    attempt + 1, MAX_RETRIES + 1, videoId, e.getMessage());
                
                if (attempt < MAX_RETRIES) {
                    try {
                        // Exponential backoff
                        Thread.sleep((attempt + 1) * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        
        LOG.error("Failed to enrich metadata for video {} after {} attempts", videoId, MAX_RETRIES + 1);
    }
    
    /**
     * Get processing status
     */
    public boolean isRunning() {
        return isRunning.get();
    }
    
    /**
     * Get queue size
     */
    public int getQueueSize() {
        return videoMetadataService.getMetadataQueue().size();
    }
}
