package Services.Thumbnail;

import Services.SettingsService;
import Services.ThumbnailService;
import Controllers.SettingsController;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import Models.Settings;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background service for processing thumbnail generation queue with rate limiting and offline support
 */
@ApplicationScoped
public class ThumbnailQueueProcessor {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(ThumbnailQueueProcessor.class);
    
    @Inject
    ThumbnailService thumbnailService;
    
    @Inject
    SettingsService settingsService;
    
    @Inject
    SettingsController settingsController;
    
    // Queue management
    private final PriorityBlockingQueue<ThumbnailJob> highPriorityQueue = 
        new PriorityBlockingQueue<>(100, this::compareJobs);
    private final LinkedBlockingQueue<ThumbnailJob> lowPriorityQueue = 
        new LinkedBlockingQueue<>();
    
    // Status tracking
    private final ThumbnailProcessingStatus status = new ThumbnailProcessingStatus();
    
    // Thread management
    private ExecutorService executorService;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    
    // Configuration defaults
    private static final int DEFAULT_API_DELAY_MS = 1000;
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_PROCESSING_THREADS = 2;
    private static final int OFFLINE_CHECK_INTERVAL_MS = 30000; // 30 seconds
    
    /**
     * Helper method to log to settings (UI) similar to file processing
     */
    private void logToSettings(String message) {
        if (settingsController != null) {
            settingsController.addLog(message);
        } else {
            // Fallback to console if settingsController not available
            LOGGER.info("UI LOG: " + message);
        }
    }
    
    @PostConstruct
    void init() {
        start();
    }
    
    @PreDestroy
    void destroy() {
        stop();
    }
    
    /**
     * Start the thumbnail processing threads
     */
    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            int processingThreads = getProcessingThreads();
            executorService = Executors.newFixedThreadPool(processingThreads);
            
            // Start processing threads
            for (int i = 0; i < processingThreads; i++) {
                executorService.submit(this::processJobs);
            }
            
            // Start offline checker thread
            executorService.submit(this::offlineChecker);
            
            LOGGER.info("Thumbnail queue processor started with {} threads", processingThreads);
        }
    }
    
    /**
     * Stop the thumbnail processing threads
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
            LOGGER.info("Thumbnail queue processor stopped");
        }
    }
    
    /**
     * Add a job to the appropriate queue based on priority
     */
    public void queueJob(ThumbnailJob job) {
        if (job.priority) {
            highPriorityQueue.offer(job);
        } else {
            lowPriorityQueue.offer(job);
        }
        updateStatus();
    }
    
    /**
     * Main processing loop for thumbnail jobs
     */
    private void processJobs() {
        while (isRunning.get()) {
            try {
                ThumbnailJob job = getNextJob();
                if (job != null) {
                    processJob(job);
                    updateStatus();
                } else {
                    Thread.sleep(1000); // Wait for jobs
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.error("Error in thumbnail processing thread", e);
            }
        }
    }
    
    /**
     * Get the next job from queues (high priority first)
     */
    private ThumbnailJob getNextJob() throws InterruptedException {
        ThumbnailJob job = highPriorityQueue.poll();
        return job != null ? job : lowPriorityQueue.take();
    }
    
    /**
     * Update processing status
     */
    private void updateStatus() {
        status.totalJobs = highPriorityQueue.size() + lowPriorityQueue.size();
    }
    
    /**
     * Check if online status has changed
     */
    private void offlineChecker() {
        boolean wasOnline = status.isOnline;
        
        while (isRunning.get()) {
            try {
                Thread.sleep(OFFLINE_CHECK_INTERVAL_MS);
                boolean isOnline = checkOnlineStatus();
                
                if (wasOnline != isOnline) {
                    status.isOnline = isOnline;
                    String statusMsg = isOnline ? "Online" : "Offline";
                    LOGGER.info("Connection status changed to: {}", statusMsg);
                    logToSettings("Network status changed to: " + statusMsg);
                }
                
                wasOnline = isOnline;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    /**
     * Check online status by connecting to external API
     */
    private boolean checkOnlineStatus() {
        try {
            URL url = new URL("https://imdbapi.dev");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            
            int responseCode = connection.getResponseCode();
            connection.disconnect();
            
            return responseCode == HttpURLConnection.HTTP_OK;
        } catch (Exception e) {
            LOGGER.debug("Online check failed: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Compare jobs for priority queue ordering
     */
    private int compareJobs(ThumbnailJob job1, ThumbnailJob job2) {
        // High priority jobs first
        if (job1.priority != job2.priority) {
            return Boolean.compare(job2.priority, job1.priority);
        }
        
        // Then by queued time (earlier jobs first)
        return job1.queuedAt.compareTo(job2.queuedAt);
    }
    
    /**
     * Get processing status
     */
    public ThumbnailProcessingStatus getStatus() {
        return status;
    }
    
    /**
     * Check if any queues have jobs
     */
    public boolean hasJobs() {
        return !highPriorityQueue.isEmpty() || !lowPriorityQueue.isEmpty();
    }
    
    /**
     * Queue all videos for thumbnail regeneration
     */
    public void regenerateAllThumbnails() {
        try {
            LOGGER.info("Starting thumbnail regeneration for all videos");
            status.isProcessing = true;
            status.totalJobs = 0;
            status.processedJobs = 0;
            status.currentJob = "Initializing...";
            
            logToSettings("Starting batch thumbnail regeneration for all videos...");
            logToSettings("Scanning database for video entries...");
            
            thumbnailService.queueAllVideosForRegeneration();
            
            String successMsg = String.format("Queued %d videos for thumbnail regeneration", status.totalJobs);
            LOGGER.info("Queued all videos for thumbnail regeneration");
            logToSettings(successMsg);
            logToSettings("Thumbnail regeneration queue initialized - processing will begin shortly...");
            
        } catch (Exception e) {
            LOGGER.error("Error during thumbnail regeneration", e);
            status.isProcessing = false;
            logToSettings("Error during thumbnail regeneration initialization: " + e.getMessage());
        }
    }
    
    /**
     * Process a single thumbnail job
     */
    private void processJob(ThumbnailJob job) {
        String mediaTitle = job.title != null ? job.title : (job.showName != null ? job.showName + " S" + job.season + "E" + job.episode : "Video " + job.videoId);
        status.currentJob = String.format("Processing %s: %s", job.mediaType, mediaTitle);
        
        try {
            LOGGER.debug("Processing thumbnail job: {}", job);
            
            // Add progress logging to UI
            logToSettings(String.format("Starting thumbnail generation for: %s (%s)", mediaTitle, job.mediaType));
            logToSettings("Analyzing video file for thumbnail extraction...");
            
            // Check online status first
            if (!status.isOnline) {
                LOGGER.info("Offline detected, using local extraction for job: {}", job);
                logToSettings("Offline mode: Using local thumbnail extraction");
                processOfflineJob(job);
                logToSettings("Thumbnail generation completed for: " + mediaTitle);
                return;
            }
            
            logToSettings("Attempting API thumbnail generation first...");
            
            // API-first approach
            String result = thumbnailService.processApiFirstThumbnail(job);
            boolean success = result != null && !result.contains("picsum.photos");
            
            if (success) {
                status.markApiSuccess();
                LOGGER.debug("API thumbnail generation successful for job: {}", job);
                logToSettings("API thumbnail generation successful for: " + mediaTitle);
                logToSettings("Thumbnail generation completed for: " + mediaTitle);
            } else {
                // API failed, try local extraction
                LOGGER.debug("API failed, trying local extraction for job: {}", job);
                logToSettings("API failed, switching to local extraction...");
                processOfflineJob(job);
                logToSettings("Thumbnail generation completed for: " + mediaTitle);
            }
            
            // Apply rate limiting delay
            applyApiDelay();
            
        } catch (Exception e) {
            LOGGER.error("Error processing thumbnail job: {}", job, e);
            logToSettings("Error generating thumbnail for " + mediaTitle + ": " + e.getMessage());
            handleJobFailure(job, e);
        }
    }
    
    /**
     * Process job when offline or API failed
     */
    private void processOfflineJob(ThumbnailJob job) {
        String mediaTitle = job.title != null ? job.title : (job.showName != null ? job.showName + " S" + job.season + "E" + job.episode : "Video " + job.videoId);
        
        try {
            logToSettings("Starting local thumbnail extraction...");
            logToSettings("Reading video file: " + job.videoPath);
            
            String result = thumbnailService.generateLocalThumbnail(job);
            boolean success = result != null && !result.contains("picsum.photos");
            
            if (success) {
                status.markLocalSuccess();
                LOGGER.debug("Local thumbnail generation successful for job: {}", job);
                logToSettings("Local thumbnail extraction successful for: " + mediaTitle);
            } else {
                status.markFailure();
                LOGGER.warn("Both API and local thumbnail generation failed for job: {}", job);
                logToSettings("Thumbnail generation failed for: " + mediaTitle + " - No suitable method available");
            }
            
        } catch (Exception e) {
            LOGGER.error("Local thumbnail generation failed for job: {}", job, e);
            logToSettings("Local thumbnail extraction failed for " + mediaTitle + ": " + e.getMessage());
        }
    }
    
    /**
     * Handle job failure and retry logic
     */
    private void handleJobFailure(ThumbnailJob job, Exception e) {
        String mediaTitle = job.title != null ? job.title : (job.showName != null ? job.showName + " S" + job.season + "E" + job.episode : "Video " + job.videoId);
        job.incrementRetryCount();
        
        int maxRetries = getMaxRetries();
        
        if (job.shouldRetry(maxRetries)) {
            String retryMsg = String.format("Retrying thumbnail generation for %s (attempt %d/%d)", mediaTitle, job.retryCount, maxRetries);
            LOGGER.warn("Retrying thumbnail job {} (attempt {}/{})", job, job.retryCount, maxRetries);
            status.currentJob = retryMsg;
            logToSettings(retryMsg);
            logToSettings("Waiting " + (2000 * job.retryCount / 1000) + "s before retry...");
            
            // Add delay before retry
            try {
                Thread.sleep(2000 * job.retryCount); // Exponential backoff
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            
            // Re-queue for retry
            queueJob(job);
        } else {
            String failMsg = String.format("Thumbnail generation failed for %s after %d attempts", mediaTitle, maxRetries);
            LOGGER.error("Thumbnail job {} failed after {} attempts, giving up", job, maxRetries);
            status.markFailure();
            logToSettings(failMsg);
            logToSettings("Error details: " + e.getMessage());
        }
    }
    
    /**
     * Apply rate limiting delay between API calls
     */
    private void applyApiDelay() {
        try {
            long delayMs = getApiDelay();
            
            // Add jitter to avoid thundering herd
            long jitter = ThreadLocalRandom.current().nextLong(200);
            long totalDelay = delayMs + jitter;
            
            if (totalDelay > 0) {
                LOGGER.debug("Applying rate limiting delay: {}ms (base: {}ms, jitter: {}ms)", totalDelay, delayMs, jitter);
                Thread.sleep(totalDelay);
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.debug("Rate limiting delay interrupted");
        }
    }
    
    // Configuration getter methods with fallbacks
    private long getApiDelay() {
        try {
            if (settingsService != null) {
                Settings settings = settingsService.getOrCreateSettings();
                return settings.getThumbnailApiDelayMs() != null ? settings.getThumbnailApiDelayMs() : DEFAULT_API_DELAY_MS;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to get thumbnail API delay from settings, using default", e);
        }
        return DEFAULT_API_DELAY_MS;
    }
    
    private int getMaxRetries() {
        try {
            if (settingsService != null) {
                Settings settings = settingsService.getOrCreateSettings();
                return settings.getThumbnailMaxRetries() != null ? settings.getThumbnailMaxRetries() : DEFAULT_MAX_RETRIES;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to get thumbnail max retries from settings, using default", e);
        }
        return DEFAULT_MAX_RETRIES;
    }
    
    private int getProcessingThreads() {
        try {
            if (settingsService != null) {
                Settings settings = settingsService.getOrCreateSettings();
                return settings.getThumbnailProcessingThreads() != null ? settings.getThumbnailProcessingThreads() : DEFAULT_PROCESSING_THREADS;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to get thumbnail processing threads from settings, using default", e);
        }
        return DEFAULT_PROCESSING_THREADS;
    }
}