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
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
@Startup
public class VideoEnrichmentWorker {
    private static final Logger LOG = LoggerFactory.getLogger(VideoEnrichmentWorker.class);
    private static final int WORKER_THREADS = 6;
    private static final int POLL_TIMEOUT_SECONDS = 5;
    private static final int MAX_RETRIES = 2;

    @Inject
    VideoMetadataService videoMetadataService;

    @Inject
    RequestContextController requestContextController;

    private final LinkedBlockingQueue<Long> queue = new LinkedBlockingQueue<>();
    private ExecutorService workerPool;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private final AtomicInteger pending = new AtomicInteger(0);
    private final AtomicInteger processed = new AtomicInteger(0);
    private final AtomicInteger failed = new AtomicInteger(0);
    private final AtomicInteger enriched = new AtomicInteger(0);
    private final AtomicInteger notFound = new AtomicInteger(0);

    @PostConstruct
    void init() {
        start();
        Thread startupThread = new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            queueAllUnenriched();
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
            workerPool = Executors.newFixedThreadPool(WORKER_THREADS, r -> {
                Thread t = new Thread(r, "VideoEnrichment-worker");
                t.setDaemon(true);
                return t;
            });
            for (int i = 0; i < WORKER_THREADS; i++) {
                workerPool.submit(this::processQueue);
            }
            LOG.info("VideoEnrichmentWorker started with {} threads", WORKER_THREADS);
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
                if (videoId == null) continue;
                pending.decrementAndGet();
                processVideo(videoId);
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

    public void queueVideo(Long videoId) {
        if (videoId == null) return;
        queue.offer(videoId);
        pending.incrementAndGet();
    }

    @jakarta.enterprise.context.control.ActivateRequestContext
    public void queueAllUnenriched() {
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

    public EnrichmentProgress getProgress() {
        return new EnrichmentProgress(
            pending.get(), processed.get(), failed.get(),
            enriched.get(), notFound.get(), queue.size(), isRunning.get()
        );
    }

    public boolean isRunning() { return isRunning.get(); }
    public int getQueueSize() { return queue.size(); }

    public record EnrichmentProgress(
        int pending, int processed, int failed,
        int enriched, int notFound, int queueSize, boolean running
    ) {}
}
