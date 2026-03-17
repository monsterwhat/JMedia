package Services;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import Models.Video;
import Models.SubtitleTrack;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class SubtitleDiscoveryQueueProcessor {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(SubtitleDiscoveryQueueProcessor.class);
    
    private static final int PROCESSING_THREADS = 2;
    private static final int DELAY_MS = 500;
    private static final int MAX_RETRIES = 2;
    
    @Inject
    EnhancedSubtitleMatcher subtitleMatcher;
    
    @Inject
    VideoService videoService;
    
    private final BlockingQueue<Long> queue = new LinkedBlockingQueue<>();
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
    
    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            executorService = Executors.newFixedThreadPool(PROCESSING_THREADS, r -> {
                Thread t = new Thread(r, "SubtitleDiscovery");
                t.setDaemon(true);
                return t;
            });
            for (int i = 0; i < PROCESSING_THREADS; i++) {
                executorService.submit(this::processQueue);
            }
            LOGGER.info("SubtitleDiscoveryQueueProcessor started with {} threads", PROCESSING_THREADS);
        }
    }
    
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
            LOGGER.info("SubtitleDiscoveryQueueProcessor stopped");
        }
    }
    
    public void queueVideo(Long videoId) {
        queue.offer(videoId);
    }
    
    public void queueAllVideos() {
        LOGGER.info("Queueing all videos for subtitle discovery...");
        List<Video> videos = Video.list("isActive = true and type = 'episode'");
        int queued = 0;
        for (Video video : videos) {
            if (video.subtitleTracks == null || video.subtitleTracks.isEmpty()) {
                queue.offer(video.id);
                queued++;
            }
        }
        LOGGER.info("Queued {} videos for subtitle discovery", queued);
    }
    
    private void processQueue() {
        LOGGER.info("Subtitle discovery worker started");
        
        while (isRunning.get()) {
            try {
                Long videoId = queue.poll(5, TimeUnit.SECONDS);
                
                if (videoId == null) {
                    continue;
                }
                
                processVideo(videoId);
                
            } catch (InterruptedException e) {
                LOGGER.info("SubtitleDiscoveryQueueProcessor interrupted, shutting down");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.error("Error processing subtitle discovery queue: {}", e.getMessage());
            }
        }
        
        LOGGER.info("Subtitle discovery worker stopped");
    }
    
    private void processVideo(Long videoId) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                Video video = Video.findById(videoId);
                if (video == null) {
                    LOGGER.debug("Video {} not found, skipping", videoId);
                    return;
                }
                
                if (video.subtitleTracks != null && !video.subtitleTracks.isEmpty()) {
                    LOGGER.debug("Video {} already has subtitle tracks, skipping", videoId);
                    return;
                }
                
                List<SubtitleTrack> subtitleTracks = subtitleMatcher.discoverSubtitleTracks(
                        Path.of(video.path), video);
                
                if (!subtitleTracks.isEmpty()) {
                    videoService.updateSubtitleTracks(videoId, subtitleTracks);
                    LOGGER.debug("Found {} subtitle tracks for video: {}", subtitleTracks.size(), video.title);
                }
                
                Thread.sleep(DELAY_MS);
                return;
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOGGER.warn("Attempt {}/{} failed for video {}: {}", 
                    attempt + 1, MAX_RETRIES + 1, videoId, e.getMessage());
                
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep((attempt + 1) * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        
        LOGGER.error("Failed to discover subtitles for video {} after {} attempts", videoId, MAX_RETRIES + 1);
    }
    
    public int getQueueSize() {
        return queue.size();
    }
    
    public boolean isRunning() {
        return isRunning.get();
    }
}
