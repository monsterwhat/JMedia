package Services;

import Models.MediaFile;
import Models.PendingMedia;
import Models.PendingMedia.ProcessingStatus;
import Detectors.EpisodeDetector;
import Detectors.MovieDetector;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@ApplicationScoped
public class MediaPreProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaPreProcessor.class);
    
    @Inject
    SmartNamingService smartNamingService;
    
    // Cache to avoid processing the same file multiple times
    private final Map<String, Boolean> processingCache = new ConcurrentHashMap<>();
    
    /**
     * Creates a pending media record for basic processing without making final decisions
     */
    @Transactional
    public PendingMedia createPendingMedia(MediaFile mediaFile, Path videoPath, Path rootPath) {
        String filename = videoPath.getFileName().toString();
        String relativePath = rootPath.relativize(videoPath).toString();
        
        // Check if already processed
        if (processingCache.containsKey(relativePath)) {
            LOGGER.debug("File already being processed: {}", relativePath);
            return null;
        }
        processingCache.put(relativePath, true);
        
        // Check if pending media already exists
        PendingMedia existing = PendingMedia.findByMediaFile(mediaFile);
        if (existing != null) {
            LOGGER.debug("PendingMedia already exists for: {}", relativePath);
            return existing;
        }
        
        PendingMedia pendingMedia = new PendingMedia();
        pendingMedia.mediaFile = mediaFile;
        pendingMedia.originalFilename = filename;
        pendingMedia.originalPath = relativePath;
        
        // Perform basic, raw detection first
        extractRawDetectionData(pendingMedia, filename, videoPath);
        
        pendingMedia.persist();
        LOGGER.info("Created PendingMedia for: {} (Status: {})", filename, pendingMedia.status);
        
        return pendingMedia;
    }
    
    /**
     * Extracts raw detection data using existing detectors (Phase 1)
     */
    private void extractRawDetectionData(PendingMedia pendingMedia, String filename, Path videoPath) {
        // Try episode detection first
        Optional<EpisodeDetector.EpisodeInfo> episodeInfoOpt = EpisodeDetector.detect(filename);
        if (episodeInfoOpt.isPresent()) {
            EpisodeDetector.EpisodeInfo episodeInfo = episodeInfoOpt.get();
            pendingMedia.rawMediaType = "episode";
            pendingMedia.rawSeason = episodeInfo.season;
            pendingMedia.rawEpisode = episodeInfo.episode;
            pendingMedia.rawTitle = episodeInfo.titleHint;
            pendingMedia.rawShowName = inferBasicShowName(videoPath);
        } else {
            // Try movie detection
            MovieDetector.MovieInfo movieInfo = MovieDetector.detect(filename);
            pendingMedia.rawMediaType = "movie";
            pendingMedia.rawTitle = movieInfo.title;
            pendingMedia.rawYear = movieInfo.releaseYear;
            pendingMedia.rawShowName = null; // Movies don't have show names
        }
    }
    
    /**
     * Basic show name inference from folder structure (existing logic)
     */
    private String inferBasicShowName(Path videoPath) {
        Path parent = videoPath.getParent();
        if (parent == null) return "Unknown Show";

        String showNameCandidate = parent.getFileName().toString();
        
        // If parent is a season folder, go up one more level
        if (showNameCandidate.matches("(?i)season[s]?[-_.]?\\d{1,3}")) {
            Path grandParent = parent.getParent();
            if (grandParent != null) {
                showNameCandidate = grandParent.getFileName().toString();
            }
        }
        
        // Basic cleanup (simplified version of existing logic)
        String cleanedShowName = showNameCandidate
                .replaceAll("(?i)\\b(?:s\\d{1,3}|season(?:s)?\\d{1,3})\\b", "")
                .replaceAll("(?i)\\b(?:\\d{3,4}p|\\d{3,4}i)\\b", "")
                .replaceAll("[._-]+", " ")
                .trim();
        
        return cleanedShowName.isEmpty() ? "Unknown Show" : cleanedShowName;
    }
    
    /**
     * Processes all pending media with smart detection (Phase 2)
     */
    @Transactional
    public void processPendingMedia() {
        List<PendingMedia> pendingList = PendingMedia.findPendingProcessing();
        LOGGER.info("MediaProcessor: Starting smart processing for {} pending media items", pendingList.size());
        
        int processedCount = 0;
        int successCount = 0;
        int retryCount = 0;
        int failedCount = 0;
        
        for (PendingMedia pending : pendingList) {
            try {
                boolean success = processSinglePendingMediaWithRetry(pending);
                processedCount++;
                if (success) {
                    successCount++;
                } else {
                    failedCount++;
                }
                
                // Log progress every 50 items
                if (processedCount % 50 == 0) {
                    LOGGER.info("MediaProcessor: Processed batch of 50 pending media items ({}/{})", 
                               processedCount, pendingList.size());
                }
                
            } catch (Exception e) {
                LOGGER.error("MediaProcessor: Critical error processing pending media {} (ID: {}): {}", 
                            pending.originalFilename, pending.id, e.getMessage(), e);
                failedCount++;
                processedCount++;
            }
        }
        
        LOGGER.info("MediaProcessor: Smart processing completed. Total: {}, Success: {}, Failed: {}, Retries attempted: {}", 
                   processedCount, successCount, failedCount, retryCount);
    }
    
    /**
     * Process a single pending media item with retry logic (max 2 retries, skip on 3rd failure)
     */ 
    private boolean processSinglePendingMediaWithRetry(PendingMedia pending) {
        int retryCount = 0;
        boolean success = false;
        
        while (retryCount <= 2 && !success) {
            try {
                processSinglePendingMedia(pending);
                success = true;
                LOGGER.info("MediaProcessor: Successfully processed: {} (type: {}, confidence: {:.2f})", 
                           pending.originalFilename, pending.detectedMediaType, pending.confidenceScore);
                
            } catch (Exception e) {
                retryCount++;
                if (retryCount <= 2) {
                    LOGGER.warn("MediaProcessor: Retry {}/3 for: {} (full exception: {})", 
                               retryCount + 1, pending.originalFilename, e.toString(), e);
                } else {
                    LOGGER.error("MediaProcessor: Skipping after 3 failures: {} (final exception: {})", 
                               pending.originalFilename, e.toString(), e);
                    
                    // Mark as failed after 3 attempts
                    pending.status = ProcessingStatus.FAILED;
                    pending.errorMessage = "Failed after 3 attempts: " + e.getMessage();
                    pending.processedAt = java.time.LocalDateTime.now();
                    pending.persist();
                }
            }
        }
        
        return success;
    }
    
    /**
     * Processes a single pending media item with smart detection
     */
    @Transactional
    public void processSinglePendingMedia(PendingMedia pending) {
        LOGGER.info("MediaProcessor: Processing pending media: {} ({})", pending.originalFilename, pending.id);
        
        pending.status = ProcessingStatus.PROCESSING;
        pending.persist();
        
        // Use smart naming service for advanced detection
        SmartNamingService.NamingResult result = smartNamingService.detectSmartNames(
            pending.mediaFile,
            pending.originalFilename,
            pending.originalPath,
            pending.rawMediaType,
            pending.rawShowName,
            pending.rawTitle,
            pending.rawSeason,
            pending.rawEpisode,
            pending.rawYear
        );
        
        // Apply smart detection results
        pending.detectedMediaType = result.mediaType;
        pending.detectedShowName = result.showName;
        pending.detectedTitle = result.title;
        pending.detectedSeason = result.season;
        pending.detectedEpisode = result.episode;
        pending.detectedYear = result.year;
        pending.confidenceScore = result.confidence;
        
        // Determine final status
        if (result.confidence >= 0.8) {
            pending.status = ProcessingStatus.COMPLETED;
        } else if (result.confidence >= 0.5) { // Changed from 0.6 to 0.5 to align with entity creation threshold
            pending.status = ProcessingStatus.COMPLETED; 
        } else {
            pending.status = ProcessingStatus.USER_CORRECTION_NEEDED;
        }
        
        pending.processedAt = java.time.LocalDateTime.now();
        pending.persist();
        
        if ("episode".equalsIgnoreCase(result.mediaType)) {
            LOGGER.info("MediaProcessor: Detected Episode: {} S{:02d}E{:02d} (confidence: {:.2f})", 
                       pending.originalFilename, result.season, result.episode, result.confidence);
        } else if ("movie".equalsIgnoreCase(result.mediaType)) {
            LOGGER.info("MediaProcessor: Detected Movie: {} (confidence: {:.2f})", 
                       pending.originalFilename, result.confidence);
        } else {
            LOGGER.info("MediaProcessor: Smart processing completed for {}: {} (confidence: {:.2f})", 
                       pending.originalFilename, pending.status, result.confidence);
        }
    }
    
    /**
     * Gets statistics about pending media processing
     */
    public ProcessingStats getProcessingStats() {
        ProcessingStats stats = new ProcessingStats();
        
        stats.totalPending = PendingMedia.count("status", ProcessingStatus.PENDING);
        stats.totalProcessing = PendingMedia.count("status", ProcessingStatus.PROCESSING);
        stats.totalCompleted = PendingMedia.count("status", ProcessingStatus.COMPLETED);
        stats.totalFailed = PendingMedia.count("status", ProcessingStatus.FAILED);
        stats.totalUserCorrectionNeeded = PendingMedia.count("status", ProcessingStatus.USER_CORRECTION_NEEDED);
        stats.totalUserApproved = PendingMedia.count("status", ProcessingStatus.USER_APPROVED);
        
        // Count items needing attention
        stats.needingAttention = PendingMedia.findNeedingUserAttention().size();
        
        return stats;
    }
    
    /**
     * Clears the processing cache (useful for testing)
     */
    public void clearCache() {
        processingCache.clear();
    }
    
    /**
     * Statistics class for processing status
     */
    public static class ProcessingStats {
        public long totalPending;
        public long totalProcessing;
        public long totalCompleted;
        public long totalFailed;
        public long totalUserCorrectionNeeded;
        public long totalUserApproved;
        public long needingAttention;
        
        @Override
        public String toString() {
            return String.format(
                "Processing Stats - Pending: %d, Processing: %d, Completed: %d, Failed: %d, User Correction Needed: %d, User Approved: %d, Needing Attention: %d",
                totalPending, totalProcessing, totalCompleted, totalFailed, 
                totalUserCorrectionNeeded, totalUserApproved, needingAttention
            );
        }
    }
}