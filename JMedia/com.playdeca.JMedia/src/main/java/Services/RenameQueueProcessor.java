package Services;

import Models.Video;
import Utils.MediaPathResolver;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class RenameQueueProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(RenameQueueProcessor.class);

    @Inject
    ThumbnailService thumbnailService;

    @Inject
    VideoStoryboardService storyboardService;

    private final BlockingQueue<Long> renameQueue = new LinkedBlockingQueue<>();
    private ExecutorService executorService;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicInteger queuedCount = new AtomicInteger(0);
    private final AtomicInteger processedCount = new AtomicInteger(0);

    @PostConstruct
    void init() {
        start();
    }

    @PreDestroy
    void destroy() {
        stop();
    }

    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            executorService = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "RenameQueueProcessor");
                t.setDaemon(true);
                return t;
            });
            executorService.submit(this::processQueue);
            LOG.info("RenameQueueProcessor started");

            // Queue all videos for initial standardization pass on startup
            queueAllVideos();
        }
    }

    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            if (executorService != null) {
                executorService.shutdown();
                try {
                    if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        executorService.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executorService.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            LOG.info("RenameQueueProcessor stopped");
        }
    }

    private void processQueue() {
        LOG.info("Starting rename queue processing");
        while (isRunning.get()) {
            try {
                Long videoId = renameQueue.poll(5, TimeUnit.SECONDS);
                if (videoId == null) continue;

                processVideoRename(videoId);
                processedCount.incrementAndGet();

            } catch (InterruptedException e) {
                LOG.info("RenameQueueProcessor interrupted, shutting down");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.error("Error processing rename queue: {}", e.getMessage(), e);
            }
        }
        LOG.info("Rename queue processing stopped. Processed {} videos", processedCount.get());
    }

    private void processVideoRename(Long videoId) {
        try {
            Video video = Video.findById(videoId);
            if (video == null) return;

            String canonicalName = MediaPathResolver.resolveThumbnailName(video);
            if (canonicalName == null) return;

            // Check if the stored thumbnail path already matches canonical
            if (video.thumbnailPath != null && video.thumbnailPath.endsWith(canonicalName)) {
                return; // Already using canonical name
            }

            // Attempt rename via ThumbnailService
            thumbnailService.renameForExternalIds(videoId);
            storyboardService.renameForExternalIds(videoId);

        } catch (Exception e) {
            LOG.warn("Failed to rename assets for video {}: {}", videoId, e.getMessage());
        }
    }

    public void queueVideo(Long videoId) {
        if (videoId != null && renameQueue.offer(videoId)) {
            queuedCount.incrementAndGet();
            LOG.debug("Queued video {} for asset renaming", videoId);
        }
    }

    public void queueAllVideos() {
        try {
            java.util.List<Video> allVideos = Video.listAll();
            for (Video video : allVideos) {
                if (video != null && video.id != null) {
                    if (renameQueue.offer(video.id)) {
                        queuedCount.incrementAndGet();
                    }
                }
            }
            LOG.info("Queued {} videos for asset renaming standardization", allVideos.size());
        } catch (Exception e) {
            LOG.error("Error queueing all videos for renaming: {}", e.getMessage());
        }
    }

    public void clearQueue() {
        renameQueue.clear();
        queuedCount.set(0);
        processedCount.set(0);
        LOG.info("Cleared rename queue");
    }

    public int getQueueSize() {
        return renameQueue.size();
    }

    public int getQueuedCount() {
        return queuedCount.get();
    }

    public int getProcessedCount() {
        return processedCount.get();
    }

    public boolean isBusy() {
        return !renameQueue.isEmpty();
    }

    @Scheduled(cron = "0 0 3 * * ?")
    void scheduledStandardization() {
        LOG.info("Running scheduled daily asset standardization");
        queueAllVideos();
    }
}
